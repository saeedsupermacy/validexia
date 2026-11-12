package com.validexia.agents.application;

import com.validexia.agents.domain.ModeGate;
import com.validexia.agents.domain.RiskClass;
import com.validexia.agents.domain.RunMode;
import com.validexia.agents.domain.RunState;
import com.validexia.agents.domain.TaskState;
import com.validexia.agents.infrastructure.AgentsRepository;
import com.validexia.agents.infrastructure.AgentsRepository.AgentApprovalRow;
import com.validexia.agents.infrastructure.AgentsRepository.AgentRunRow;
import com.validexia.agents.infrastructure.AgentsRepository.AgentTaskRow;
import com.validexia.agents.infrastructure.AgentsRepository.ClaimLostException;
import com.validexia.agents.infrastructure.AgentsRepository.ClaimOutcome;
import com.validexia.agents.infrastructure.AgentsRepository.CreateRunResult;
import com.validexia.agents.infrastructure.AgentsRepository.PlanTask;
import com.validexia.audit.AuditService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The orchestration engine: creates runs from registered planners, persists their tasks, and
 * executes them in order under the run's autonomy mode. Every state change goes through the
 * repository's compare-and-swap transitions, so execution is resumable: a run that stopped —
 * waiting for approval, or because the process died mid-task — continues from its persisted state
 * without repeating completed tasks. Tasks are executed under leases with fencing (migration V7):
 * an executor claims a task before touching it, only the claim owner can park or complete it, and a
 * takeover is possible only after the lease expires — so concurrent executors divide a run's work
 * instead of duplicating it, and stale executors cannot commit state. A run and its plan are
 * persisted atomically (migration V8): the plan is computed first, then committed with the run in a
 * single transaction, so no caller can ever observe — let alone execute or settle — a partially
 * initialized run. Every meaningful transition is written to the audit chain with the run's
 * initiating user as the actor, and only by the executor whose transition actually won.
 */
@Service
public class AgentEngine {

  /**
   * How long a task claim lasts before another executor may treat the owner as dead and take over.
   * The database validates the range (1..3600 seconds, migration V8).
   */
  private static final int LEASE_SECONDS = 30;

  /**
   * Hard ceiling on a single tool invocation, enforced by the engine. It sits below the lease with
   * a 10-second margin: a tool that overruns is interrupted and its task failed while the claim is
   * still ours, so a timeout can never strand a task in a state where a takeover executor re-runs
   * it while the original call is still in flight. A tool that cannot finish within this ceiling
   * must not be registered until lease renewal (heartbeat) exists — see the concurrency audit's
   * remaining limits.
   */
  private static final int TOOL_TIMEOUT_SECONDS = 20;

  private final AgentsRepository repository;
  private final AgentRegistry agents;
  private final ToolRegistry tools;
  private final AuditService audit;
  private final int leaseSeconds;
  private final int toolTimeoutSeconds;
  private final ExecutorService toolExecutor;

  @Autowired
  public AgentEngine(
      AgentsRepository repository, AgentRegistry agents, ToolRegistry tools, AuditService audit) {
    this(repository, agents, tools, audit, LEASE_SECONDS, TOOL_TIMEOUT_SECONDS);
  }

  AgentEngine(
      AgentsRepository repository,
      AgentRegistry agents,
      ToolRegistry tools,
      AuditService audit,
      int leaseSeconds,
      int toolTimeoutSeconds) {
    if (leaseSeconds < 1 || toolTimeoutSeconds < 1 || toolTimeoutSeconds >= leaseSeconds) {
      throw new IllegalArgumentException(
          "tool timeout must be positive and below the lease duration");
    }
    this.repository = repository;
    this.agents = agents;
    this.tools = tools;
    this.audit = audit;
    this.leaseSeconds = leaseSeconds;
    this.toolTimeoutSeconds = toolTimeoutSeconds;
    this.toolExecutor =
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable, "agent-tool-invocation");
              thread.setDaemon(true);
              return thread;
            });
  }

  /**
   * Creates a run (idempotently when a key is given) and starts execution. The plan is computed
   * BEFORE anything is persisted — a planner failure leaves no run behind — and the run and its
   * complete plan then commit in a single transaction (migration V8). A duplicate caller with the
   * same idempotency key receives the already-committed run and plan; it can never observe a
   * partially initialized one.
   */
  public AgentData.Run createAndStart(
      UUID tenantId,
      UUID userId,
      String agentKey,
      String objective,
      RunMode mode,
      @Nullable String idempotencyKey,
      String contextJson) {
    AgentPlanner planner = agents.require(agentKey);
    List<AgentPlanner.PlannedTask> planned = planner.plan(objective, contextJson);
    List<PlanTask> plan = new ArrayList<>(planned.size());
    for (AgentPlanner.PlannedTask task : planned) {
      ToolHandler tool = tools.require(task.toolKey());
      plan.add(new PlanTask(task.toolKey(), tool.riskClass().name(), task.inputJson()));
    }
    CreateRunResult created =
        repository.createRunWithPlan(
            tenantId, userId, agentKey, objective, mode.name(), idempotencyKey, contextJson, plan);
    UUID runId = created.runId();
    if (created.wasCreated()) {
      audit.appendForTenant(
          tenantId,
          userId,
          "AGENT_RUN_CREATED",
          "agent_run",
          runId.toString(),
          "{\"agentKey\":\"" + agentKey + "\",\"mode\":\"" + mode.name() + "\"}");
    }
    return AgentData.Run.from(executeRow(tenantId, userId, runId));
  }

  /** Continues a run from its persisted state. Safe to call repeatedly. */
  public AgentData.Run execute(UUID tenantId, UUID userId, UUID runId) {
    return AgentData.Run.from(executeRow(tenantId, userId, runId));
  }

  private AgentRunRow executeRow(UUID tenantId, UUID userId, UUID runId) {
    // Every execute() call is a distinct executor. Tasks are claimed under a lease owned by
    // this id and fenced by the claim's attempt number, so a stale executor can never commit
    // state after losing a task (migration V7; see the concurrency audit in docs/agents).
    String executorId = UUID.randomUUID().toString();
    AgentRunRow run = requireRunRow(tenantId, userId, runId);
    RunState state = RunState.valueOf(run.state());
    if (state == RunState.SUCCEEDED || state == RunState.FAILED || state == RunState.CANCELLED) {
      return run;
    }
    if (!run.planReady()) {
      // Plan initialization is incomplete (only possible for a run created outside the
      // atomic path). Such a run must never be executed or settled: an empty task list here
      // means "not yet initialized", not "nothing to do". Readiness is explicit state
      // (migration V8), never inferred from the task count.
      return run;
    }
    if (state == RunState.PENDING) {
      if (!repository.transitionRun(
          tenantId, userId, runId, RunState.PENDING.name(), RunState.RUNNING.name(), null, null)) {
        return requireRunRow(tenantId, userId, runId);
      }
    } else if (state == RunState.WAITING_APPROVAL) {
      boolean stillWaiting =
          repository.listTasks(tenantId, userId, runId).stream()
              .anyMatch(t -> TaskState.valueOf(t.state()) == TaskState.WAITING_APPROVAL);
      if (stillWaiting) {
        return run;
      }
      if (!repository.transitionRun(
          tenantId,
          userId,
          runId,
          RunState.WAITING_APPROVAL.name(),
          RunState.RUNNING.name(),
          null,
          null)) {
        return requireRunRow(tenantId, userId, runId);
      }
    }
    // A RUNNING run may be entered freely: every task is claimed under a lease before it is
    // touched, so concurrent executors divide the work instead of duplicating it.

    RunMode mode = RunMode.valueOf(run.mode());
    // A task a human already approved must not be parked again: the approval IS the gate
    // decision for that task. (Approvals are unique per task, and a decided approval cannot
    // be re-requested — re-parking would also violate that uniqueness.)
    Set<UUID> approvedTaskIds =
        repository.listApprovals(tenantId, userId, "APPROVED").stream()
            .map(AgentApprovalRow::taskId)
            .collect(Collectors.toSet());
    List<AgentTaskRow> tasks = repository.listTasks(tenantId, userId, runId);
    for (AgentTaskRow task : tasks) {
      TaskState taskState = TaskState.valueOf(task.state());
      switch (taskState) {
        case SUCCEEDED, SKIPPED, CANCELLED -> {
          // Already settled; never repeated.
        }
        case FAILED -> {
          return failRun(tenantId, userId, run, task.error());
        }
        case WAITING_APPROVAL -> {
          repository.transitionRun(
              tenantId,
              userId,
              runId,
              RunState.RUNNING.name(),
              RunState.WAITING_APPROVAL.name(),
              null,
              null);
          return requireRunRow(tenantId, userId, runId);
        }
        case PENDING, RUNNING -> {
          AgentRunRow outcome =
              executeTask(tenantId, userId, run, mode, task, approvedTaskIds, executorId);
          if (outcome != null) {
            return outcome;
          }
        }
      }
      if (Thread.currentThread().isInterrupted()) {
        return requireRunRow(tenantId, userId, runId);
      }
    }

    // Completion is claimed, not assumed: the run is marked SUCCEEDED only when a fresh read
    // shows every task settled, and the audit event is written only if our CAS won the
    // transition — a concurrent executor finishing the same run cannot duplicate it.
    List<AgentTaskRow> settled = repository.listTasks(tenantId, userId, runId);
    boolean allSettled =
        settled.stream()
            .allMatch(
                t ->
                    TaskState.valueOf(t.state()) == TaskState.SUCCEEDED
                        || TaskState.valueOf(t.state()) == TaskState.SKIPPED
                        || TaskState.valueOf(t.state()) == TaskState.CANCELLED);
    if (allSettled) {
      int succeeded =
          (int)
              settled.stream()
                  .filter(t -> TaskState.valueOf(t.state()) == TaskState.SUCCEEDED)
                  .count();
      if (repository.transitionRun(
          tenantId,
          userId,
          runId,
          RunState.RUNNING.name(),
          RunState.SUCCEEDED.name(),
          "{\"tasksSucceeded\":" + succeeded + "}",
          null)) {
        audit.appendForTenant(
            tenantId, userId, "AGENT_RUN_SUCCEEDED", "agent_run", runId.toString(), "{}");
      }
    }
    return requireRunRow(tenantId, userId, runId);
  }

  /** Fails a run whose task failed; the audit event is written only if our CAS won. */
  private AgentRunRow failRun(UUID tenantId, UUID userId, AgentRunRow run, @Nullable String error) {
    if (repository.transitionRun(
        tenantId,
        userId,
        run.id(),
        RunState.RUNNING.name(),
        RunState.FAILED.name(),
        null,
        error == null ? "A task failed" : error)) {
      audit.appendForTenant(
          tenantId, userId, "AGENT_RUN_FAILED", "agent_run", run.id().toString(), "{}");
    }
    return requireRunRow(tenantId, userId, run.id());
  }

  /**
   * Claims one task under a lease, then executes it or parks it for approval. Returns the run row
   * when the walk must stop (the task was parked, the run failed, or another executor holds the
   * work), or {@code null} to continue with the next task.
   */
  private @Nullable AgentRunRow executeTask(
      UUID tenantId,
      UUID userId,
      AgentRunRow run,
      RunMode mode,
      AgentTaskRow task,
      Set<UUID> approvedTaskIds,
      String executorId) {
    ClaimOutcome claim =
        repository.claimTask(tenantId, userId, run.id(), task.id(), executorId, leaseSeconds);
    if (!claim.claimed()) {
      switch (TaskState.valueOf(claim.taskState())) {
        case WAITING_APPROVAL -> {
          repository.transitionRun(
              tenantId,
              userId,
              run.id(),
              RunState.RUNNING.name(),
              RunState.WAITING_APPROVAL.name(),
              null,
              null);
          return requireRunRow(tenantId, userId, run.id());
        }
        case FAILED -> {
          AgentTaskRow current = reloadTask(tenantId, userId, run.id(), task.id());
          return failRun(tenantId, userId, run, current.error());
        }
        case SUCCEEDED, SKIPPED, CANCELLED -> {
          return null; // Another executor settled it; move on.
        }
        default -> {
          // RUNNING under another executor's live lease: the work is spoken for. Stop the
          // walk and report the run as it stands — never execute a second copy.
          return requireRunRow(tenantId, userId, run.id());
        }
      }
    }
    int attempt = claim.attempt();
    RiskClass risk = RiskClass.valueOf(task.riskClass());
    if (!approvedTaskIds.contains(task.id()) && ModeGate.requiresApproval(mode, risk)) {
      UUID approvalId;
      try {
        approvalId =
            repository.parkTaskForApproval(
                tenantId,
                userId,
                task.id(),
                executorId,
                attempt,
                "Tool "
                    + task.toolKey()
                    + " ("
                    + risk.name()
                    + ") requires approval in "
                    + mode.name()
                    + " mode");
      } catch (ClaimLostException claimLost) {
        // The lease was taken over between claim and park; the new owner coordinates the
        // task now. Losing quietly is correct — a duplicate approval or a 500 is not.
        return requireRunRow(tenantId, userId, run.id());
      }
      audit.appendForTenant(
          tenantId,
          userId,
          "AGENT_APPROVAL_REQUESTED",
          "agent_approval",
          approvalId.toString(),
          "{\"tool\":\"" + task.toolKey() + "\",\"riskClass\":\"" + risk.name() + "\"}");
      repository.transitionRun(
          tenantId,
          userId,
          run.id(),
          RunState.RUNNING.name(),
          RunState.WAITING_APPROVAL.name(),
          null,
          null);
      return requireRunRow(tenantId, userId, run.id());
    }
    ToolHandler tool = tools.require(task.toolKey());
    String output;
    try {
      output =
          invokeTool(
              tool,
              new ToolInvocation(run.id(), task.id(), task.idempotencyKey(), attempt),
              task.inputJson());
    } catch (RuntimeException e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      if (repository.completeTask(
          tenantId,
          userId,
          task.id(),
          executorId,
          attempt,
          TaskState.FAILED.name(),
          null,
          message)) {
        return failRun(tenantId, userId, run, message);
      }
      // The claim was lost while the tool ran; its failure is not ours to record.
      return requireRunRow(tenantId, userId, run.id());
    }
    if (repository.completeTask(
        tenantId,
        userId,
        task.id(),
        executorId,
        attempt,
        TaskState.SUCCEEDED.name(),
        output,
        null)) {
      audit.appendForTenant(
          tenantId,
          userId,
          "AGENT_TASK_EXECUTED",
          "agent_task",
          task.id().toString(),
          "{\"tool\":\"" + task.toolKey() + "\",\"riskClass\":\"" + risk.name() + "\"}");
      return null;
    }
    // The claim was lost while the tool ran (lease expired, another executor took over):
    // discard our result silently. The current owner's outcome is the one that counts, and
    // the tool carried the task's stable idempotency key, so a provider-side effect can be
    // deduplicated or reconciled by key.
    return requireRunRow(tenantId, userId, run.id());
  }

  /**
   * Runs one tool invocation under the engine's timeout (below the task lease, with margin). A tool
   * that overruns is interrupted and reported as a failure, so its task is settled by the claim
   * owner while the claim is still valid. A tool that ignores interruption may keep running in the
   * background, but its late result is discarded — nothing reads the future again — and its
   * invocation carried the task's stable idempotency key, so a provider-side effect can be
   * deduplicated or reconciled by key.
   */
  private String invokeTool(ToolHandler tool, ToolInvocation invocation, String inputJson) {
    Future<String> future = toolExecutor.submit(() -> tool.execute(invocation, inputJson));
    try {
      return future.get(toolTimeoutSeconds, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new IllegalStateException(
          "Tool "
              + tool.key()
              + " timed out after "
              + toolTimeoutSeconds
              + "s (lease "
              + leaseSeconds
              + "s)");
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while executing tool " + tool.key());
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException("Tool " + tool.key() + " failed: " + cause, cause);
    }
  }

  /** Records a human decision; an approval resumes the run. Returns the run afterwards. */
  public AgentData.Run decideApproval(
      UUID tenantId,
      UUID deciderUserId,
      UUID approvalId,
      String decision,
      @Nullable String reason) {
    AgentApprovalRow approval =
        repository.listApprovals(tenantId, deciderUserId, null).stream()
            .filter(a -> a.id().equals(approvalId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + approvalId));
    boolean decided =
        repository.decideApproval(tenantId, deciderUserId, approvalId, decision, reason);
    if (!decided) {
      throw new IllegalStateException("Approval was already decided: " + approvalId);
    }
    audit.appendForTenant(
        tenantId,
        deciderUserId,
        "AGENT_APPROVAL_DECIDED",
        "agent_approval",
        approvalId.toString(),
        "{\"decision\":\"" + decision + "\"}");
    AgentRunRow run = requireRunRow(tenantId, deciderUserId, approval.runId());
    // Either way the run leaves WAITING_APPROVAL: an approval lets execution continue; a
    // rejection makes execute() observe the FAILED task and fail the run with its reason.
    return AgentData.Run.from(executeRow(tenantId, run.initiatedBy(), approval.runId()));
  }

  /** Loads a run for the API layer, or throws if it is not visible to this tenant. */
  public AgentData.Run requireRun(UUID tenantId, UUID userId, UUID runId) {
    return AgentData.Run.from(requireRunRow(tenantId, userId, runId));
  }

  private AgentRunRow requireRunRow(UUID tenantId, UUID userId, UUID runId) {
    return repository
        .getRun(tenantId, userId, runId)
        .orElseThrow(() -> new IllegalArgumentException("Agent run not found: " + runId));
  }

  private AgentTaskRow reloadTask(UUID tenantId, UUID userId, UUID runId, UUID taskId) {
    return repository.listTasks(tenantId, userId, runId).stream()
        .filter(t -> t.id().equals(taskId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Task vanished: " + taskId));
  }
}
