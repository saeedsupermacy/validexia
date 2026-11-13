package com.validexia.agents.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The context the engine hands a tool on every invocation.
 *
 * <p>{@code idempotencyKey} is derived from the run/task identity when the run's plan is persisted
 * and is <b>stable</b>: the same task always presents the same key, across retries, lease requeues,
 * and different executors. It is never generated per attempt. A tool that performs an external
 * write must pass this key to the provider's idempotency mechanism wherever one exists, or
 * deduplicate against it equivalently.
 *
 * <p>{@code attempt} is the fencing generation of the current claim (1 for the first executor,
 * incremented on every takeover). It distinguishes invocations in logs and traces; it must never be
 * folded into the idempotency key.
 *
 * <p>{@code tenantId} and {@code userId} are the run's scope: the tenant the run was created under
 * and the user who initiated it, exactly as the engine received them from the authenticated caller
 * and persisted on the run — never values taken from plan or task input data. A tool that reads or
 * writes tenant data MUST scope its work by them (through the tenant-guarded repository functions,
 * which fail closed on any mismatch); they are the only tenant identity a tool may trust. They are
 * {@code null} only for invocations constructed outside the engine (unit tests of the invocation
 * lifecycle itself); the engine always supplies both.
 */
public record ToolInvocation(
    UUID runId,
    UUID taskId,
    String idempotencyKey,
    int attempt,
    @Nullable UUID tenantId,
    @Nullable UUID userId) {

  /** An invocation without a tenant scope, for lifecycle tests that never touch tenant data. */
  public ToolInvocation(UUID runId, UUID taskId, String idempotencyKey, int attempt) {
    this(runId, taskId, idempotencyKey, attempt, null, null);
  }
}
