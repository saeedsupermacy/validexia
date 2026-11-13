package com.validexia.assessments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.assessments.application.CitationVerifier;
import com.validexia.assessments.application.AssessmentData.EvidencePassage;
import com.validexia.assessments.application.AssessmentData.EvidenceRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Citation verification is the guarantee that makes a model-produced finding trustworthy: a quote
 * must actually appear on the page it cites, and the hashes must be intact. These tests are pure
 * (no database) because the rule is deterministic — D6.
 */
class CitationVerifierTest {

  private static final String PAGE_TEXT =
      "The vendor maintains ISO 27001 certification across all production regions.";

  private static EvidencePassage page() {
    return new EvidencePassage(3, PAGE_TEXT, CitationVerifier.sha256Hex(PAGE_TEXT));
  }

  @Test
  void aQuoteThatAppearsOnTheCitedPageVerifies() {
    EvidenceRef citation = new EvidenceRef(UUID.randomUUID(), 3, "ISO 27001 certification", null);
    CitationVerifier.Result result = CitationVerifier.verify(citation, List.of(page()));

    assertTrue(result.verified(), "an exact quote on the cited page must verify");
    assertNull(result.failure());
    assertEquals(
        CitationVerifier.sha256Hex("ISO 27001 certification"),
        result.computedQuoteSha256(),
        "the stored quote hash must be the one computed here, never a caller-supplied value");
  }

  @Test
  void aFabricatedQuoteIsRejected() {
    EvidenceRef citation = new EvidenceRef(null, 3, "SOC 2 Type II certification", null);
    CitationVerifier.Result result = CitationVerifier.verify(citation, List.of(page()));

    assertFalse(result.verified(), "a quote that is not on the page must never verify");
    assertNotNull(result.failure());
    assertNull(result.computedQuoteSha256(), "a failed citation stores no hash");
  }

  @Test
  void aTamperedPageHashIsRejectedBeforeTheQuoteIsConsidered() {
    EvidencePassage tampered =
        new EvidencePassage(3, PAGE_TEXT, CitationVerifier.sha256Hex("different text"));
    EvidenceRef citation = new EvidenceRef(null, 3, "ISO 27001 certification", null);

    CitationVerifier.Result result = CitationVerifier.verify(citation, List.of(tampered));

    assertFalse(result.verified(), "a page whose stored hash does not match its text is untrusted");
  }

  @Test
  void aCallerSuppliedQuoteHashThatDoesNotMatchTheQuoteIsRejected() {
    EvidenceRef citation =
        new EvidenceRef(
            null,
            3,
            "ISO 27001 certification",
            CitationVerifier.sha256Hex("some other text"));

    CitationVerifier.Result result = CitationVerifier.verify(citation, List.of(page()));

    assertFalse(result.verified(), "a caller cannot smuggle a citation through a bad hash");
  }

  @Test
  void aCitedPageThatWasNotSuppliedAsEvidenceIsRejected() {
    EvidenceRef citation = new EvidenceRef(null, 9, "ISO 27001 certification", null);

    CitationVerifier.Result result = CitationVerifier.verify(citation, List.of(page()));

    assertFalse(result.verified(), "citing a page that was never supplied must fail");
  }

  @Test
  void whitespaceDifferencesDoNotManufactureAMiss() {
    // PDF extraction inserts line breaks; the quote is the same text reflowed.
    EvidenceRef citation =
        new EvidenceRef(null, 3, "The vendor maintains ISO 27001 certification across all", null);

    CitationVerifier.Result result = CitationVerifier.verify(citation, List.of(page()));

    assertTrue(result.verified(), "whitespace normalization must not turn a real quote into a miss");
  }

  @Test
  void aNonPositivePageNumberIsRejected() {
    EvidenceRef citation = new EvidenceRef(null, 0, "ISO 27001 certification", null);

    CitationVerifier.Result result = CitationVerifier.verify(citation, List.of(page()));

    assertFalse(result.verified());
  }

  @Test
  void anEmptyQuoteIsRejected() {
    EvidenceRef citation = new EvidenceRef(null, 3, "   ", null);

    CitationVerifier.Result result = CitationVerifier.verify(citation, List.of(page()));

    assertFalse(result.verified());
  }

  @Test
  void normalizationNeverReordersOrDropsCharacters() {
    String text = "alpha beta gamma";
    assertEquals("alpha beta gamma", CitationVerifier.normalize(text));
    assertEquals("alpha beta", CitationVerifier.normalize("  alpha   beta  "));
    // A quote that only matches after reordering must NOT match.
    assertFalse(
        CitationVerifier.normalize(text).contains(CitationVerifier.normalize("gamma beta alpha")));
  }
}
