package com.validexia.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.ai.eval.LiveEvalOutcomes;
import com.validexia.ai.eval.LiveEvalOutcomes.Outcome;
import org.junit.jupiter.api.Test;

/**
 * Offline tests for the live evaluation's outcome classification. The classifier lives in the
 * excluded eval package, so — exactly like {@link LiveEvalProvidersTest} — these tests sit outside
 * it and run in the normal CI suite. The safety property under test: a rate-limited case is
 * classified inconclusive, never as any kind of success, so the harness's conclusiveness gate fails
 * the evaluation for it.
 */
class LiveEvalOutcomesTest {

  @Test
  void aSuccessfulRunWithABriefIsBriefValid() {
    assertEquals(Outcome.BRIEF_VALID, LiveEvalOutcomes.classify(true, 1, false, "", true, true));
  }

  @Test
  void aCitationRejectionAfterASuccessfulProviderCallIsBriefInvalid() {
    // The 2026-11-18 Groq run's rich cases: the provider answered, validation rejected.
    assertEquals(
        Outcome.BRIEF_INVALID,
        LiveEvalOutcomes.classify(
            true,
            1,
            false,
            "brief failed citation validation: keyFacts[0] carries no fact text",
            false,
            false));
  }

  @Test
  void aProviderHttpErrorIsProviderError() {
    assertEquals(
        Outcome.PROVIDER_ERROR,
        LiveEvalOutcomes.classify(
            true,
            1,
            true,
            "Groq returned HTTP 401 (error type: invalid_request_error)",
            false,
            false));
  }

  @Test
  void aRateLimitedCaseIsInconclusiveNeverAValidBrief() {
    Outcome outcome =
        LiveEvalOutcomes.classify(
            true, 1, true, "Groq returned HTTP 429 (error type: tokens)", false, false);
    assertEquals(Outcome.RATE_LIMITED_INCONCLUSIVE, outcome);
    assertNotEquals(Outcome.BRIEF_VALID, outcome);
    assertFalse(LiveEvalOutcomes.isConclusive(outcome));
  }

  @Test
  void rateLimitTakesPrecedenceOverTheGenericProviderErrorBranch() {
    // A 429 also makes the counted call "fail"; the classification must still be the
    // rate-limited one, not provider-error.
    assertEquals(
        Outcome.RATE_LIMITED_INCONCLUSIVE,
        LiveEvalOutcomes.classify(
            true, 1, true, "Groq returned HTTP 429 (error type: tokens)", false, false));
  }

  @Test
  void everyNonRateLimitedOutcomeIsConclusive() {
    assertTrue(LiveEvalOutcomes.isConclusive(Outcome.BRIEF_VALID));
    assertTrue(LiveEvalOutcomes.isConclusive(Outcome.BRIEF_INVALID));
    assertTrue(LiveEvalOutcomes.isConclusive(Outcome.PROVIDER_ERROR));
    assertTrue(LiveEvalOutcomes.isConclusive(Outcome.NO_CALL_AS_EXPECTED));
  }

  @Test
  void aDesignedZeroCallCaseIsNoCallAsExpected() {
    assertEquals(
        Outcome.NO_CALL_AS_EXPECTED, LiveEvalOutcomes.classify(false, 0, false, "", false, false));
  }

  @Test
  void theDirectProbeWhoseProviderCallFailsByDesignIsProviderError() {
    // Case (g): no counted call is expected, but its direct provider call fails (transport) —
    // that is a provider-error outcome, not a no-call one.
    assertEquals(
        Outcome.PROVIDER_ERROR, LiveEvalOutcomes.classify(false, 0, true, "", false, false));
  }

  @Test
  void labelsAreTheExactReportStrings() {
    assertEquals("brief-valid", Outcome.BRIEF_VALID.label());
    assertEquals("brief-invalid", Outcome.BRIEF_INVALID.label());
    assertEquals("provider-error", Outcome.PROVIDER_ERROR.label());
    assertEquals("rate-limited-inconclusive", Outcome.RATE_LIMITED_INCONCLUSIVE.label());
    assertEquals("no-call-as-expected", Outcome.NO_CALL_AS_EXPECTED.label());
  }
}
