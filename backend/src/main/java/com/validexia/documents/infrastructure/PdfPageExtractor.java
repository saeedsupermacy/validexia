package com.validexia.documents.infrastructure;

import com.validexia.documents.application.DocumentData.ExtractedPage;
import com.validexia.documents.application.DocumentStorageProperties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessStreamCache.StreamCacheCreateFunction;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Extracts one text record per PDF page with Apache PDFBox 3.x, preserving page boundaries so a
 * citation can be pinned to the page it came from.
 *
 * <p><b>Page ceiling.</b> {@code getNumberOfPages()} returns the page tree's declared {@code
 * /Count} as written, so a small file can claim a very large page count. The declared count is
 * checked against {@code validexia.documents.max-page-count} before the page list is allocated or
 * any per-page extraction begins; a document above the ceiling raises {@link
 * DocumentExtractionException} and is marked {@code Failed} by the service. The ceiling bounds the
 * <em>declared</em> count only — the file must still be parsed to read it, so it is a work bound,
 * not a memory guarantee.
 *
 * <p><b>Bounded parse memory.</b> PDFBox 3's {@code Loader} buffers streams in unrestricted main
 * memory unless the caller supplies a cache. Extraction therefore loads through {@link
 * MemoryUsageSetting#setupMixed(long, long)}: at most {@link #MAX_MAIN_MEMORY_BYTES} on the heap,
 * spilling to at most {@link #MAX_STORAGE_BYTES} of scratch space. The cache is owned by the {@link
 * PDDocument} and released when the try-with-resources block closes it.
 *
 * <p>Encrypted documents are handled gracefully: a PDF that carries only an empty user password
 * (the common "restricted permissions" case) is decrypted and read; a PDF protected by a real
 * password cannot be read and raises {@link DocumentExtractionException}, which the service turns
 * into a {@code Failed} document rather than a failed request. Blank or image-only pages extract to
 * the empty string and are still persisted, so page numbers stay aligned with the document.
 *
 * <p>Extracted text is untrusted content (D8): it is stored and hashed, never interpreted.
 */
@Component
public class PdfPageExtractor {

  /** Heap budget for parsing one document; larger documents spill to scratch space. */
  static final long MAX_MAIN_MEMORY_BYTES = 8L * 1024 * 1024;

  /** Scratch-space budget for parsing one document. */
  static final long MAX_STORAGE_BYTES = 64L * 1024 * 1024;

  private final DocumentStorageProperties properties;

  public PdfPageExtractor(DocumentStorageProperties properties) {
    this.properties = properties;
  }

  /** Raised when a PDF cannot be opened or its text extracted. */
  public static final class DocumentExtractionException extends RuntimeException {

    public DocumentExtractionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Returns one record per page, in page order. */
  public List<ExtractedPage> extract(byte[] pdfBytes) {
    try (PDDocument document = open(pdfBytes)) {
      int pageCount = document.getNumberOfPages();
      if (pageCount > properties.maxPageCount()) {
        throw new DocumentExtractionException(
            "PDF declares " + pageCount + " pages, above the limit of " + properties.maxPageCount(),
            null);
      }
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      List<ExtractedPage> pages = new ArrayList<>(Math.max(pageCount, 0));
      for (int number = 1; number <= pageCount; number++) {
        stripper.setStartPage(number);
        stripper.setEndPage(number);
        String text = stripper.getText(document);
        pages.add(new ExtractedPage(number, text == null ? "" : text));
      }
      return pages;
    } catch (IOException e) {
      throw new DocumentExtractionException("PDF text extraction failed", e);
    }
  }

  /**
   * Opens the document with a bounded stream cache. The empty-string password is PDFBox's
   * convention: unencrypted documents load directly and documents that only restrict permissions
   * are decrypted with the empty user password. A document protected by a real password raises
   * {@link InvalidPasswordException} (an {@link IOException}), which the caller maps to a {@code
   * Failed} document.
   */
  private PDDocument open(byte[] pdfBytes) throws IOException {
    return Loader.loadPDF(pdfBytes, "", null, null, newCache());
  }

  private static StreamCacheCreateFunction newCache() {
    return MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY_BYTES, MAX_STORAGE_BYTES).streamCache;
  }
}
