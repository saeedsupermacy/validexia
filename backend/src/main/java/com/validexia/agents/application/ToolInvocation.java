package com.validexia.agents.application;

import java.util.UUID;

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
 */
public record ToolInvocation(UUID runId, UUID taskId, String idempotencyKey, int attempt) {}
