package com.validexia.ai.eval;

/**
 * The outcome classification every case of the live evaluation receives in the report, computed
 * from the facts the harness recorded about the case (calls made, whether the provider call itself
 * failed, the run's error text, whether a brief was produced). The classification is a label for
 * the reader — it changes no gate by itself — with one deliberate exception: the harness adds a
 * conclusiveness gate on it, so a rate-limited case fails the evaluation.
 *
 * <p>The five outcomes:
 *
 * <ul>
 *   <li>{@code brief-valid} — a provider call was made and the run produced a brief that passed
 *       server-side validation. A correctly formed structured abstention (the thin-record case) is
 *       a valid brief outcome and classifies here; the case's own gates separately assert the
 *       abstention's shape.
 *   <li>{@code brief-invalid} — the provider answered, but no valid brief resulted: citation
 *       validation rejected the answer, or the run otherwise failed downstream of a successful
 *       provider call. This is a product finding, not an infrastructure event.
 *   <li>{@code provider-error} — the provider call itself failed (HTTP error, transport, or an
 *       unusable response), so no brief was ever attempted from an answer. The unreachable-
 *       endpoint probe, whose provider call fails by design, classifies here.
 *   <li>{@code rate-limited-inconclusive} — the provider rejected the call with HTTP 429. The case
 *       tested nothing: the model never saw the record. This outcome is INCONCLUSIVE, never a pass
 *       — {@link #isConclusive} returns false for it and the harness's conclusiveness gate fails
 *       the evaluation, so a quota event can neither masquerade as a product bug nor be quietly
 *       absorbed as a success.
 *   <li>{@code no-call-as-expected} — the case was designed to make no counted provider call
 *       (cross-tenant denial, missing vendor, provenance verification) and made none.
 * </ul>
 *
 * <p>Precedence matters and is deliberate: a 429 is recognised before the generic provider-error
 * branch (a rate-limited call also "failed"), and a provider failure is recognised before the
 * no-call branch (the direct probe makes no COUNTED call but its provider call did fail).
 */
public final class LiveEvalOutcomes {

  /** One case's outcome; {@link #label()} is the exact string used in the reports. */
  public enum Outcome {
    BRIEF_VALID("brief-valid"),
    BRIEF_INVALID("brief-invalid"),
    PROVIDER_ERROR("provider-error"),
    RATE_LIMITED_INCONCLUSIVE("rate-limited-inconclusive"),
    NO_CALL_AS_EXPECTED("no-call-as-expected");

    private final String label;

    Outcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  private LiveEvalOutcomes() {}

  /**
   * Classifies one case from its recorded facts. {@code providerCallFailed} covers both a counted
   * call whose delegate threw and the unreachable-endpoint case's direct probe. {@code error} is
   * the run's recorded error text (may be empty, never null in harness use; null is tolerated).
   */
  public static Outcome classify(
      boolean callExpected,
      int countedCallsMade,
      boolean providerCallFailed,
      String error,
      boolean briefPresent,
      boolean runSucceeded) {
    if (error != null && error.contains("HTTP 429")) {
      return Outcome.RATE_LIMITED_INCONCLUSIVE;
    }
    if (providerCallFailed) {
      return Outcome.PROVIDER_ERROR;
    }
    if (!callExpected && countedCallsMade == 0) {
      return Outcome.NO_CALL_AS_EXPECTED;
    }
    if (runSucceeded && briefPresent) {
      return Outcome.BRIEF_VALID;
    }
    return Outcome.BRIEF_INVALID;
  }

  /**
   * Whether an outcome lets the case count as evidence about the brief pipeline at all. Only a
   * rate-limited case is inconclusive; every other outcome — including the failures — is a real
   * result the evaluation stands on.
   */
  public static boolean isConclusive(Outcome outcome) {
    return outcome != Outcome.RATE_LIMITED_INCONCLUSIVE;
  }
}
