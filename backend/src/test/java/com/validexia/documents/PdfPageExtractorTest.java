package com.validexia.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.documents.application.DocumentData.ExtractedPage;
import com.validexia.documents.application.DocumentStorageProperties;
import com.validexia.documents.infrastructure.PdfPageExtractor;
import com.validexia.documents.infrastructure.PdfPageExtractor.DocumentExtractionException;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/**
 * M1: the extraction ceiling and the parsing boundary. Pure unit tests — no Spring context, no
 * database. Every assertion runs against a real PDF produced by PDFBox, so the ceiling is exercised
 * through the same code path as a real upload.
 */
class PdfPageExtractorTest {

  private static PdfPageExtractor extractor(int maxPageCount) {
    return new PdfPageExtractor(new DocumentStorageProperties(null, 0, maxPageCount));
  }

  /** Builds a real, valid PDF with one text-bearing page per requested page. */
  private static byte[] pdfWithPages(int pageCount) {
    try (PDDocument document = new PDDocument()) {
      for (int i = 0; i < pageCount; i++) {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
          content.beginText();
          content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 12);
          content.newLineAtOffset(72, 700);
          content.showText("Page " + (i + 1));
          content.endText();
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("could not build a test PDF", e);
    }
  }

  @Test
  void defaultPageLimitIsTwoThousand() {
    assertEquals(2_000, new DocumentStorageProperties(null, 0, 0).maxPageCount());
    assertEquals(2_000, DocumentStorageProperties.DEFAULT_MAX_PAGE_COUNT);
  }

  @Test
  void aPdfWithinTheLimitIsAccepted() {
    List<ExtractedPage> pages = extractor(2_000).extract(pdfWithPages(3));
    assertEquals(3, pages.size());
    assertEquals(1, pages.get(0).pageNumber());
    assertTrue(pages.get(0).text().contains("Page 1"));
  }

  @Test
  void theConfiguredLimitIsRespected() {
    assertEquals(2, extractor(2).extract(pdfWithPages(2)).size());
    DocumentExtractionException error =
        assertThrows(
            DocumentExtractionException.class,
            () -> extractor(2).extract(pdfWithPages(3)),
            "a PDF above the configured ceiling must be rejected");
    assertTrue(error.getMessage().contains("above the limit"));
  }

  @Test
  void aPdfExceedingTheDefaultLimitIsRejectedBeforeExtraction() {
    // 2,001 blank pages: the declared count alone must be enough to reject the document.
    byte[] pdf = pdfWithPages(2_001);
    DocumentExtractionException error =
        assertThrows(DocumentExtractionException.class, () -> extractor(2_000).extract(pdf));
    assertTrue(error.getMessage().contains("2001"));
  }

  @Test
  void malformedInputIsRejectedRatherThanThrown() {
    assertThrows(
        DocumentExtractionException.class,
        () -> extractor(2_000).extract("this is not a PDF".getBytes()));
  }

  @Test
  void aSinglePagePdfExtractsExactlyOneRecord() {
    List<ExtractedPage> pages = extractor(2_000).extract(pdfWithPages(1));
    assertEquals(1, pages.size());
    assertTrue(pages.get(0).text().contains("Page 1"));
  }
}
