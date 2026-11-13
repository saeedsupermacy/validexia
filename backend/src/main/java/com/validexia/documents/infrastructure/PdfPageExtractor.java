package com.validexia.documents.infrastructure;

import com.validexia.documents.application.DocumentData.ExtractedPage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Extracts one text record per PDF page with Apache PDFBox 3.x, preserving page boundaries so a
 * citation can be pinned to the page it came from.
 *
 * <p>Encrypted documents are handled gracefully: a PDF that carries only an empty user password
 * (the common "restricted permissions" case) is decrypted and read; a PDF protected by a real
 * password cannot be read and raises {@link DocumentExtractionException}, which the service turns
 * into a {@code Failed} document rather than a failed request. Blank or image-only pages extract
 * to the empty string and are still persisted, so page numbers stay aligned with the document.
 *
 * <p>Extracted text is untrusted content (D8): it is stored and hashed, never interpreted.
 */
@Component
public class PdfPageExtractor {

  /** Raised when a PDF cannot be opened or its text extracted. */
  public static final class DocumentExtractionException extends RuntimeException {

    public DocumentExtractionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Returns one record per page, in page order. */
  public List<ExtractedPage> extract(byte[] pdfBytes) {
    try (PDDocument document = open(pdfBytes)) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      int pageCount = document.getNumberOfPages();
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
   * Opens the document, transparently decrypting PDFs that only restrict permissions. A document
   * protected by a real password fails to load; the caller marks the document {@code Failed}.
   */
  private PDDocument open(byte[] pdfBytes) throws IOException {
    try {
      return Loader.loadPDF(pdfBytes);
    } catch (InvalidPasswordException e) {
      return Loader.loadPDF(pdfBytes, "");
    }
  }
}
