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
    // One 3,000-token call in the window: 3,000 + 3,600 reserve <= 7,000 budget.
    assertEquals(0, LiveEvalPacer.millisToWait(List.of(call(90_000, 3_000)), 100_000));
  }

  @Test
  void theObservedGroqRunWouldHaveWaitedForTheOldestCallToAgeOut() {
    // The 2026-11-18 run's first two calls: 3,086 and 2,705 actual tokens. Before a third call
    // at t=25s: 5,791 in window + 3,600 reserve > 7,000, so the pacer waits until the first
    // call leaves the 60-second window (t=10s + 60s = t=70s), i.e. 45 seconds.
    List<CallFact> history = List.of(call(10_000, 3_086), call(20_000, 2_705));
    assertEquals(45_000, LiveEvalPacer.millisToWait(history, 25_000));
  }

  @Test
  void callsOlderThanTheWindowAreIgnored() {
    // A 6,000-token call that completed 61 seconds ago no longer counts against the budget.
    assertEquals(0, LiveEvalPacer.millisToWait(List.of(call(0, 6_000)), 61_000));
  }

  @Test
  void dropsOnlyAsManyOldCallsAsNeeded() {
    // Three 3,000-token calls at t=50s/52s/54s, deciding at t=55s: dropping the oldest still
    // leaves 6,000 + 3,600 > 7,000, so two must age out; the wait ends when the second-oldest
    // leaves the window (t=52s + 60s = t=112s), i.e. 57 seconds.
    List<CallFact> history = List.of(call(50_000, 3_000), call(52_000, 3_000), call(54_000, 3_000));
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
    // (read 2026-11-17). The pacing budget must stay below it, and the reserve must cover a
    // realistic next call (largest observed: 3,086 tokens) without exceeding the budget alone.
    assertTrue(LiveEvalPacer.TOKEN_BUDGET_PER_WINDOW < 8_000);
    assertTrue(LiveEvalPacer.NEXT_CALL_RESERVE_TOKENS >= 3_086);
    assertTrue(LiveEvalPacer.NEXT_CALL_RESERVE_TOKENS < LiveEvalPacer.TOKEN_BUDGET_PER_WINDOW);
    assertEquals(60_000, LiveEvalPacer.WINDOW_MS);
  }
}
