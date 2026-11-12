package com.validexia.agents.application;

import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * One tool invocation, with its queued/running/cancelled lifecycle coordinated under a single lock
 * so there is no check-then-act window between "was cancellation requested?" and "the tool starts"
 * (PR #3 review, Finding 9):
 *
 * <ul>
 *   <li>If {@link #requestInterrupt()} wins before {@link #call()} enters its critical section, the
 *       invocation is cancelled before start: {@code call()} throws {@link NotStartedException} and
 *       the tool is NEVER invoked.
 *   <li>If {@code call()} wins, the runner thread is published inside the same critical section, so
 *       a later {@code requestInterrupt()} reliably interrupts the actual runner.
 *   <li>An interrupt delivered after the invocation finished is impossible: the runner reference is
 *       cleared under the lock before {@code call()} returns, so a pooled thread can never be
 *       interrupted on behalf of an invocation it has already left.
 * </ul>
 *
 * <p>({@code Future.cancel(true)} is deliberately not used anywhere in this lifecycle: it cannot
 * stop an uncooperative thread, and cancelling the future would make the invocation's real outcome
 * unobservable.)
 */
final class ToolRun implements Callable<String> {

  /** The invocation was cancelled before it started; the tool was never invoked. */
  static final class NotStartedException extends RuntimeException {
    NotStartedException(String toolKey) {
      super("Invocation of " + toolKey + " was cancelled before it started");
    }
  }

  private final ToolHandler tool;
  private final ToolInvocation invocation;
  private final String inputJson;
  private final Object lock = new Object();
  private boolean cancelRequested;
  private boolean active;
  private @Nullable Thread runner;

  ToolRun(ToolHandler tool, ToolInvocation invocation, String inputJson) {
    this.tool = tool;
    this.invocation = invocation;
    this.inputJson = inputJson;
  }

  @Override
  public String call() {
    Thread.interrupted(); // Drop any stale interrupt flag left on this pooled thread.
    synchronized (lock) {
      if (cancelRequested) {
        throw new NotStartedException(tool.key());
      }
      active = true;
      runner = Thread.currentThread();
    }
    try {
      return tool.execute(invocation, inputJson);
    } finally {
      synchronized (lock) {
        active = false;
        runner = null;
      }
    }
  }

  /**
   * Requests interruption. Before start: the invocation will never start. While running: the runner
   * thread is interrupted (a request — the tool may ignore it; the engine's lifecycle, not this
   * class, decides what that means). After completion: a no-op.
   */
  void requestInterrupt() {
    Thread toInterrupt;
    synchronized (lock) {
      cancelRequested = true;
      toInterrupt = active ? runner : null;
    }
    if (toInterrupt != null) {
      toInterrupt.interrupt();
    }
  }
}
