package com.validexia.assessments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.assessments.application.CitationVerifier;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for deterministic citation verification. No database: these prove the rules that
 * decide whether a quotation may be stored as evidence.
 *
 * <p>The page text and hash here stand in for what the documents slice stored. The production path
 * ({@code EvidenceResolver}) is what guarantees they came from the database rather than a caller;
 * these tests prove the verification rules themselves.
 */
class CitationVerifierTest {

  private static final String PAGE_TEXT =
      "The vendor maintains ISO 27001 certification across all production regions.";

  private static CitationVerifier.StoredPage page() {
    return new CitationVerifier.StoredPage(3, PAGE_TEXT, CitationVerifier.sha256Hex(PAGE_TEXT));
  }

  private static CitationVerifier.StoredPage pageWithHashOfDifferentText() {
    return new CitationVerifier.StoredPage(
        3, PAGE_TEXT, CitationVerifier.sha256Hex("different text"));
  }

  @Test
  void aQuoteThatAppearsOnTheCitedPageVerifies() {
    CitationVerifier.Result result =
        CitationVerifier.verify(
            new CitationVerifier.Citation(3, "ISO 27001 certification"), List.of(page()));

    assertTrue(result.verified());
    assertNull(result.failure());
    assertEquals(
        CitationVerifier.sha256Hex("ISO 27001 certification"),
        result.computedQuoteSha256(),
        "the stored hash is always computed here, never supplied");
  }

  @Test
  void aFabricatedQuoteIsRejected() {
    CitationVerifier.Result result =
        CitationVerifier.verify(
            new CitationVerifier.Citation(3, "SOC 2 Type II certification"), List.of(page()));

    assertFalse(result.verified());
    assertNotNull(result.failure());
    assertNull(result.computedQuoteSha256(), "no hash is produced for a failed citation");
  }

  @Test
  void aTamperedPageHashIsRejectedBeforeTheQuoteIsConsidered() {
    // The quote IS present in the text, but the stored hash does not match the stored text — so the
    // text cannot be trusted as the stored text, and the citation must fail.
    CitationVerifier.Result result =
        CitationVerifier.verify(
            new CitationVerifier.Citation(3, "ISO 27001 certification"),
            List.of(pageWithHashOfDifferentText()));

    assertFalse(result.verified());
    assertNotNull(result.failure());
  }

  @Test
  void aStoredPageWithoutAHashIsRejected() {
    CitationVerifier.Result result =
        CitationVerifier.verify(
            new CitationVerifier.Citation(3, "ISO 27001 certification"),
            List.of(new CitationVerifier.StoredPage(3, PAGE_TEXT, " ")));

    assertFalse(result.verified());
    assertNotNull(result.failure());
  }

  @Test
  void aCitedPageThatWasNotSuppliedAsEvidenceIsRejected() {
    CitationVerifier.Result result =
        CitationVerifier.verify(
            new CitationVerifier.Citation(9, "ISO 27001 certification"), List.of(page()));

    assertFalse(result.verified());
    assertNotNull(result.failure());
  }

  @Test
  void whitespaceDifferencesDoNotManufactureAMiss() {
    // PDF extraction inserts line breaks; the quote is the same text reflowed.
    CitationVerifier.Result result =
        CitationVerifier.verify(
            new CitationVerifier.Citation(
                3, "The vendor maintains ISO 27001 certification across all"),
            List.of(page()));

    assertTrue(result.verified());
  }

  @Test
  void aNonPositivePageNumberIsRejected() {
    CitationVerifier.Result result =
        CitationVerifier.verify(new CitationVerifier.Citation(0, "ISO 27001"), List.of(page()));

    assertFalse(result.verified());
    assertNotNull(result.failure());
  }

  @Test
  void aNegativePageNumberIsRejected() {
    CitationVerifier.Result result =
        CitationVerifier.verify(new CitationVerifier.Citation(-1, "ISO 27001"), List.of(page()));

    assertFalse(result.verified());
  }

  @Test
  void anEmptyQuoteIsRejected() {
    CitationVerifier.Result result =
        CitationVerifier.verify(new CitationVerifier.Citation(3, "   "), List.of(page()));

    assertFalse(result.verified());
    assertNotNull(result.failure());
  }

  @Test
  void normalizationNeverReordersOrDropsCharacters() {
    assertFalse(
        CitationVerifier.normalize("ISO 27001 certification maintains vendor the")
            .contains(CitationVerifier.normalize("The vendor maintains ISO 27001 certification")),
        "reordered words must not match");
    assertEquals("a b", CitationVerifier.normalize("  a\t\nb  "));
  }

  @Test
  void duplicatePageNumbersResolveToTheFirstStoredPage() {
    // Two rows claiming page 3: the first is the deterministic answer, so the outcome does not
    // depend on list ordering beyond insertion order.
    CitationVerifier.Result result =
        CitationVerifier.verify(
            new CitationVerifier.Citation(3, "ISO 27001 certification"),
            List.of(page(), new CitationVerifier.StoredPage(3, "unrelated", "unrelated")));

    assertTrue(result.verified());
  }
}
