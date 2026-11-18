package com.validexia.ai.eval;

import java.util.Comparator;
import java.util.List;

/**
 * Pacing for the live evaluation: how long to wait before the next counted provider call so the run
 * stays inside the evaluated provider's per-minute token budget.
 *
 * <p><b>Why this exists.</b> The first Groq live evaluation (2026-11-18) lost three of its seven
 * calls to HTTP 429 (error type {@code tokens}): Groq's free plan allows 8,000 tokens per minute
 * for {@code openai/gpt-oss-120b} (limit read from Groq's model/rate-limit documentation,
 * 2026-11-17), and the first three calls alone consumed 8,147 actual tokens (3,086 + 2,705 + 2,356)
 * back-to-back. The harness fired all seven calls as fast as the engine could run them.
 *
 * <p><b>The approach.</b> A conservative rolling-window budget over the counting guard's own
 * recorded history: before a case that will make a provider call, sum the actual tokens of the
 * calls completed inside the trailing {@link #WINDOW_MS} window, add a reserve for the next call,
 * and — if the sum would exceed {@link #TOKEN_BUDGET_PER_WINDOW} — wait until enough of the oldest
 * calls have aged out of the window. The pacer is a pure function of recorded history: it holds no
 * provider reference, makes no calls, and cannot create, hide, or retry an invocation. Every real
 * invocation still goes through the counting guard exactly once, so the hard cap (12) and the
 * planned-call accounting (exactly 7) are untouched.
 *
 * <p><b>Assumptions and remaining uncertainty.</b> (1) The window is treated as a rolling 60
 * seconds; Groq does not publish whether its window is rolling or fixed-minute, and the rolling
 * reading is the conservative one. (2) Calls are charged at their ACTUAL recorded tokens. The
 * 2026-11-18 run supports this: under a "charged at prompt + full output allowance" reading the
 * second call would already have exceeded the budget, yet the first three calls succeeded and the
 * rejections began exactly when cumulative actual tokens passed 8,000. (3) The budget is set 1,000
 * tokens under the published limit, and the next-call reserve (3,600) is above the largest call
 * observed in that run (3,086), as margins against both uncertainties. (4) Pacing is applied only
 * when the evaluated provider is Groq; Anthropic's limits are far above this budget, and slowing
 * that evaluation down would buy nothing. If a call is nevertheless rate-limited, pacing has failed
 * safe: the case is classified rate-limited-inconclusive (see {@link LiveEvalOutcomes}) and the
 * evaluation fails — pacing never retries and never hides a call.
 */
public final class LiveEvalPacer {

  /** Length of the rolling token-budget window: one minute. */
  public static final long WINDOW_MS = 60_000;

  /**
   * Token budget per window: Groq's published free-plan limit for the evaluated model is 8,000
   * tokens per minute; the harness paces to 7,000 to keep a safety margin.
   */
  public static final int TOKEN_BUDGET_PER_WINDOW = 7_000;

  /**
   * Tokens reserved for the next call when deciding whether it fits the window: above the largest
   * single call observed in the 2026-11-18 run (3,086 actual tokens).
   */
  public static final int NEXT_CALL_RESERVE_TOKENS = 3_600;

  /** One completed counted call: when it completed and how many tokens it actually used. */
  public record CallFact(long completedAtMs, int totalTokens) {}

  private LiveEvalPacer() {}

  /**
   * Milliseconds to wait before making the next provider call, given the completed calls so far and
   * the current time. Zero when the next call (at its reserved size) fits inside the budget
   * alongside everything still in the window; otherwise the time until the oldest calls whose
   * expiry makes room have left the window.
   */
  public static long millisToWait(List<CallFact> history, long nowMs) {
    List<CallFact> window =
        history.stream()
            .filter(fact -> fact.completedAtMs() > nowMs - WINDOW_MS)
            .sorted(Comparator.comparingLong(CallFact::completedAtMs))
            .toList();
    long inWindow = window.stream().mapToLong(CallFact::totalTokens).sum();
    int drop = 0;
    while (inWindow + NEXT_CALL_RESERVE_TOKENS > TOKEN_BUDGET_PER_WINDOW && drop < window.size()) {
      inWindow -= window.get(drop).totalTokens();
      drop++;
    }
    if (drop == 0) {
      return 0;
    }
    long freeAtMs = window.get(drop - 1).completedAtMs() + WINDOW_MS;
    return Math.max(0, freeAtMs - nowMs);
  }
}
