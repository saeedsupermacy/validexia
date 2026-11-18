package com.validexia.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.ai.eval.LiveEvalPacer;
import com.validexia.ai.eval.LiveEvalPacer.CallFact;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Offline tests for the live evaluation's pacing math. The pacer lives in the excluded eval
 * package, so — exactly like {@link LiveEvalProvidersTest} — these tests sit outside it and run in
 * the normal CI suite. They exercise the pure budget function only: the pacer holds no provider
 * reference and returns a wait duration, so by construction it cannot make, hide, or retry a
 * provider call; the counting guard's hard cap and the harness's planned-call accounting are
 * structurally out of its reach. The final test pins the budget constants the safety argument rests
 * on.
 */
class LiveEvalPacerTest {

  private static CallFact call(long completedAtMs, int totalTokens) {
    return new CallFact(completedAtMs, totalTokens);
  }

  @Test
  void noHistoryMeansNoWait() {
    assertEquals(0, LiveEvalPacer.millisToWait(List.of(), 100_000));
  }

  @Test
  void callsFittingTheBudgetWithTheReserveMeanNoWait() {
    // One 1,500-token call in the window: 1,500 + 5,296 reserve <= 7,000 budget.
    assertEquals(0, LiveEvalPacer.millisToWait(List.of(call(90_000, 1_500)), 100_000));
  }

  @Test
  void theObservedGroqRunWouldHaveWaitedForTheWindowToClear() {
    // The 2026-11-18 run's first two calls: 3,086 and 2,705 actual tokens. Before a third call
    // at t=25s: 5,791 in window + the 5,296 maximum-size reserve exceeds the budget even after
    // the oldest call ages out (2,705 + 5,296 = 8,001 > 7,000), so the pacer waits until BOTH
    // calls have left the window — the second at t=20s + 60s = t=80s, i.e. 55 seconds.
    List<CallFact> history = List.of(call(10_000, 3_086), call(20_000, 2_705));
    assertEquals(55_000, LiveEvalPacer.millisToWait(history, 25_000));
  }

  @Test
  void aMaximumSizeNextCallCannotPushThePlannedTotalAboveTheBudget() {
    // One 1,800-token call completed at t=10s; deciding at t=20s. An observation-based reserve
    // (3,600, above the largest call seen in the first run) would have allowed the next call
    // immediately: 1,800 + 3,600 = 5,400 <= 7,000. But a next call at its maximum permitted
    // size (5,296) would take the true rolling total to 1,800 + 5,296 = 7,096 — above the
    // pacing budget. The pacer must therefore wait until the 1,800-token call ages out
    // (t=10s + 60s = t=70s), i.e. 50 seconds.
    List<CallFact> history = List.of(call(10_000, 1_800));
    long wait = LiveEvalPacer.millisToWait(history, 20_000);
    assertEquals(50_000, wait);
    // And after waiting, the planned total — what remains in the window plus a maximum-size
    // next call — is within the budget by construction.
    long atMs = 20_000 + wait;
    long inWindowAfterWait =
        history.stream()
            .filter(fact -> fact.completedAtMs() > atMs - LiveEvalPacer.WINDOW_MS)
            .mapToLong(CallFact::totalTokens)
            .sum();
    assertTrue(
        inWindowAfterWait + LiveEvalPacer.NEXT_CALL_RESERVE_TOKENS
            <= LiveEvalPacer.TOKEN_BUDGET_PER_WINDOW);
  }

  @Test
  void callsOlderThanTheWindowAreIgnored() {
    // A 6,000-token call that completed 61 seconds ago no longer counts against the budget.
    assertEquals(0, LiveEvalPacer.millisToWait(List.of(call(0, 6_000)), 61_000));
  }

  @Test
  void dropsOnlyAsManyOldCallsAsNeeded() {
    // Three 1,200-token calls at t=50s/52s/54s, deciding at t=55s: dropping the oldest still
    // leaves 2,400 + 5,296 > 7,000, so two must age out; the wait ends when the second-oldest
    // leaves the window (t=52s + 60s = t=112s), i.e. 57 seconds.
    List<CallFact> history = List.of(call(50_000, 1_200), call(52_000, 1_200), call(54_000, 1_200));
    assertEquals(57_000, LiveEvalPacer.millisToWait(history, 55_000));
  }

  @Test
  void aSingleCallLargerThanTheBudgetStillWaitsOnlyForItsOwnExpiry() {
    // Even a pathological 9,000-token call resolves by waiting for it to leave the window —
    // the pacer never answers an over-budget history with anything but time.
    assertEquals(50_000, LiveEvalPacer.millisToWait(List.of(call(10_000, 9_000)), 20_000));
  }

  @Test
  void budgetConstantsStayConservativeAgainstThePublishedGroqLimit() {
    // Groq's published free-plan limit for openai/gpt-oss-120b is 8,000 tokens per minute
    // (read 2026-11-17). The pacing budget must stay below it.
    assertTrue(LiveEvalPacer.TOKEN_BUDGET_PER_WINDOW < 8_000);
    assertTrue(LiveEvalPacer.NEXT_CALL_RESERVE_TOKENS < LiveEvalPacer.TOKEN_BUDGET_PER_WINDOW);
    assertEquals(60_000, LiveEvalPacer.WINDOW_MS);
  }

  @Test
  void theReserveIsTheMaximumPermittedNextCallNotTheLargestObserved() {
    // The output side of the bound is the request's own hard cap — the brief tool's requests
    // carry ModelRequest.DEFAULT_MAX_OUTPUT_TOKENS (4,096), which the adapters send as the
    // completion-token limit. The reserve must be exactly max-input + that cap, so a next call
    // at its maximum permitted size is what the budget plans for — never the smaller figure of
    // the largest call that happened to be observed (3,086 in the first run).
    assertEquals(4_096, ModelRequest.DEFAULT_MAX_OUTPUT_TOKENS);
    assertEquals(
        LiveEvalPacer.MAX_NEXT_CALL_INPUT_TOKENS + ModelRequest.DEFAULT_MAX_OUTPUT_TOKENS,
        LiveEvalPacer.NEXT_CALL_RESERVE_TOKENS);
    assertTrue(LiveEvalPacer.NEXT_CALL_RESERVE_TOKENS > 3_086);
    // The input bound must cover the largest input actually observed (957 tokens, with the
    // shorter pre-format-fix prompt) with headroom.
    assertTrue(LiveEvalPacer.MAX_NEXT_CALL_INPUT_TOKENS >= 957);
  }
}
