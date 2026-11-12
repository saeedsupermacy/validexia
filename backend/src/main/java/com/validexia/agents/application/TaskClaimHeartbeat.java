package com.validexia.agents.application;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a task claim alive while its tool invocation is genuinely running (migration V9). The
 * heartbeat renews the lease on a fixed interval, so a live invocation's claim never expires and no
 * takeover — and therefore no second, concurrent invocation of the same operation — can occur.
 *
 * <p>Failure semantics are deliberately asymmetric:
 *
 * <ul>
 *   <li>The renewer returning {@code false} means ownership is <b>proven lost</b> (fenced out, or
 *       the lease already lapsed): {@link #claimLost()} is set and renewals stop.
 *   <li>The renewer <b>throwing</b> (for example the database is briefly unavailable) proves
 *       nothing: the failure is treated as transient, the heartbeat keeps retrying on schedule, and
 *       loss is never declared on the basis of an error. If an outage lasts longer than the lease,
 *       the claim can still expire underneath a running invocation — that residual risk is
 *       documented in the concurrency audit and is one reason external write tools stay gated on
 *       verified provider idempotency.
 * </ul>
 */
final class TaskClaimHeartbeat implements AutoCloseable {

  /** Renews the claim once. Returns true while the caller still owns it; may throw transiently. */
  interface Renewer {
    boolean renew();
  }

  private final AtomicBoolean claimLost = new AtomicBoolean();
  private volatile @Nullable ScheduledFuture<?> future;

  private TaskClaimHeartbeat() {}

  static TaskClaimHeartbeat start(
      ScheduledExecutorService scheduler, Renewer renewer, long intervalSeconds) {
    TaskClaimHeartbeat heartbeat = new TaskClaimHeartbeat();
    heartbeat.future =
        scheduler.scheduleWithFixedDelay(
            () -> {
              if (heartbeat.claimLost.get()) {
                return;
              }
              boolean owned;
              try {
                owned = renewer.renew();
              } catch (RuntimeException transientFailure) {
                return; // Proves nothing; retry on the next tick.
              }
              if (!owned) {
                heartbeat.claimLost.set(true);
              }
            },
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS);
    return heartbeat;
  }

  boolean claimLost() {
    return claimLost.get();
  }

  @Override
  public void close() {
    ScheduledFuture<?> scheduled = future;
    if (scheduled != null) {
      scheduled.cancel(false);
    }
  }
}
