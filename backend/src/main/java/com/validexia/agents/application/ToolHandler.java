package com.validexia.agents.application;

import com.validexia.agents.domain.RiskClass;

/**
 * A tool an agent may call. Tools are registered in code (Spring beans), never in the database and
 * never by agents: the registry is the permission boundary, and a tool's {@link RiskClass} is fixed
 * at registration time. Inputs and outputs are JSON strings, matching how the persistence layer
 * stores them (jsonb columns carried as text).
 *
 * <p><b>Execution semantics — read this before writing a tool with side effects.</b> The engine is
 * at-least-once: a task's tool may be invoked more than once (executor crash, lease expiry and
 * takeover). Database leases and fencing stop a stale executor from committing <i>state</i>, but
 * they cannot retract a provider request already in flight, so they do not give exactly-once
 * external execution. Therefore:
 *
 * <ul>
 *   <li>Every invocation carries a stable {@link ToolInvocation#idempotencyKey()} derived from the
 *       run/task identity — identical across retries and executors. Pass it to the provider's
 *       idempotency mechanism wherever supported, or deduplicate against it equivalently. An
 *       external write tool with neither provider idempotency nor an equivalent verified
 *       dedupe/reconciliation mechanism must not be registered.
 *   <li>If a provider call's outcome is <b>ambiguous</b> (a timeout or dropped connection after the
 *       request may have been accepted), reconcile with the provider — query what the idempotency
 *       key actually did — before any retry. Never blindly repeat an operation whose outcome is
 *       unknown.
 *   <li>Every invocation runs under a soft execution budget (currently 20s). At the budget the
 *       engine requests interruption of the invocation's thread — a request, never a guarantee: the
 *       engine records an outcome only once the invocation has actually ended, and while it runs, a
 *       fenced heartbeat holds the task's claim so no second invocation of the same operation can
 *       start. A tool that ignores interruption therefore keeps its task visibly RUNNING instead of
 *       being falsely reported as terminated. Tools must still respond to interruption promptly;
 *       hard termination of uncooperative code is not provided (it would require process
 *       isolation).
 * </ul>
 */
public interface ToolHandler {

  String key();

  RiskClass riskClass();

  /**
   * Executes the tool with its invocation context. Implementations that perform external writes
   * must honour the idempotency contract described on this interface. The default delegates to
   * {@link #execute(String)} for tools that do not need the context.
   */
  default String execute(ToolInvocation invocation, String inputJson) {
    return execute(inputJson);
  }

  /** Executes the tool. May be called again for the same task; see the contract above. */
  String execute(String inputJson);
}
