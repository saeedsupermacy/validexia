package com.validexia.agents.application;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a task claim alive while its tool invocation is genuinely running (migration V9). The
 * heartbeat renews the lease on a fixed cadence, so a live invocation's claim never expires and no
 * takeover — and therefore no second, concurrent invocation of the same operation — can occur.
 *
 * <p>Failure semantics are deliberately asymmetric:
 *
 * <ul>
 *   <li>The renewer returning {@code false} means ownership is <b>proven lost</b> (fenced out, or
 *       the lease already lapsed): {@link #claimLost()} is set and renewals stop.
 *   <li>The renewer <b>throwing</b> (for example the database is briefly unavailable) proves
 *       nothing: the failure is treated as transient and loss is never declared on the basis of an
 *       error.
 * </ul>
 *
 * <p>Retry timing is part of the safety argument (Finding 11). A failed attempt may have consumed
 * most of its bounded duration — up to ~5s waiting for a pooled connection plus ~2s of statement
 * time — so the next attempt is scheduled after a short failure-retry delay (1 second), NOT after a
 * full success interval: with the production cadence (a renewal every lease/6 = 5s against a 30s
 * lease), a claim survives two consecutive maximum-duration transient failures and is renewed by
 * the third attempt with time to spare. A fixed-delay schedule that waited a full interval after a
 * maximum-duration failure would start the next attempt at or after expiry, and the renewal
 * function rightly refuses an expired claim. Attempts for one claim never overlap: the next attempt
 * is only ever scheduled after the previous one has finished. If failures persist beyond that
 * budget — roughly, the renewal path failing continuously for most of the lease window — the claim
 * can legitimately expire underneath a running invocation; that residual risk is documented in the
 * concurrency audit and is one reason external write tools stay gated on verified provider
 * idempotency.
 */
final class TaskClaimHeartbeat implements AutoCloseable {

  /** Renews the claim once. Returns true while the caller still owns it; may throw transiently. */
  interface Renewer {
    boolean renew();
  }

  /** Delay before retrying after a transient failure — deliberately far below the interval. */
  private static final long FAILURE_RETRY_SECONDS = 1;

  private final AtomicBoolean claimLost = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile @Nullable ScheduledFuture<?> future;

  private TaskClaimHeartbeat() {}

  static TaskClaimHeartbeat start(
      ScheduledExecutorService scheduler, Renewer renewer, long intervalSeconds) {
    TaskClaimHeartbeat heartbeat = new TaskClaimHeartbeat();
    heartbeat.scheduleNext(scheduler, renewer, intervalSeconds, intervalSeconds);
    return heartbeat;
  }

  private void scheduleNext(
      ScheduledExecutorService scheduler,
      Renewer renewer,
      long delaySeconds,
      long intervalSeconds) {
    if (closed.get() || claimLost.get()) {
      return;
    }
    future =
        scheduler.schedule(
            () -> runOnce(scheduler, renewer, intervalSeconds), delaySeconds, TimeUnit.SECONDS);
  }

  private void runOnce(ScheduledExecutorService scheduler, Renewer renewer, long intervalSeconds) {
    if (closed.get() || claimLost.get()) {
      return;
    }
    boolean owned;
    try {
      owned = renewer.renew();
    } catch (RuntimeException transientFailure) {
      // Proves nothing; retry promptly instead of waiting out a full interval — the failed
      // attempt may already have consumed much of the remaining lease (Finding 11).
      scheduleNext(scheduler, renewer, FAILURE_RETRY_SECONDS, intervalSeconds);
      return;
    }
    if (!owned) {
      claimLost.set(true);
      return;
    }
    scheduleNext(scheduler, renewer, intervalSeconds, intervalSeconds);
  }

  boolean claimLost() {
    return claimLost.get();
  }

  @Override
  public void close() {
    closed.set(true);
    ScheduledFuture<?> scheduled = future;
    if (scheduled != null) {
      scheduled.cancel(false);
    }
  }
}
