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
import com.validexia.agents.infrastructure.AgentsRepository.CreateRunResult;
import com.validexia.audit.AuditService;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The orchestration engine: creates runs from registered planners, persists their tasks, and
 * executes them in order under the run's autonomy mode. Every state change goes through the
 * repository's compare-and-swap transitions, so execution is resumable: a run that stopped —
 * waiting for approval, or because the process died mid-task — continues from its persisted state
 * without repeating completed tasks. Every meaningful transition is written to the audit chain with
 * the run's initiating user as the actor.
 */
@Service
public class AgentEngine {

  private final AgentsRepository repository;
  private final AgentRegistry agents;
  private final ToolRegistry tools;
  private final AuditService audit;

  public AgentEngine(
      AgentsRepository repository, AgentRegistry agents, ToolRegistry tools, AuditService audit) {
    this.repository = repository;
    this.agents = agents;
    this.tools = tools;
    this.audit = audit;
  }

  /** Creates a run (idempotently when a key is given), persists its plan, and starts execution. */
  public AgentRunRow createAndStart(
      UUID tenantId,
      UUID userId,
      String agentKey,
      String objective,
      RunMode mode,
      @Nullable String idempotencyKey,
      String contextJson) {
    AgentPlanner planner = agents.require(agentKey);
    CreateRunResult created =
        repository.createRun(
            tenantId, userId, agentKey, objective, mode.name(), idempotencyKey, contextJson);
    UUID runId = created.runId();
    if (created.wasCreated()) {
      List<AgentPlanner.PlannedTask> plan = planner.plan(objective, contextJson);
      int seq = 1;
      for (AgentPlanner.PlannedTask task : plan) {
        ToolHandler tool = tools.require(task.toolKey());
        repository.addTask(
            tenantId,
            userId,
            runId,
            seq,
            task.toolKey(),
            tool.riskClass().name(),
            task.inputJson(),
            runId + ":" + seq);
        seq++;
      }
      audit.appendForTenant(
          tenantId,
          userId,
          "AGENT_RUN_CREATED",
          "agent_run",
          runId.toString(),
          "{\"agentKey\":\"" + agentKey + "\",\"mode\":\"" + mode.name() + "\"}");
    }
    return execute(tenantId, userId, runId);
  }

  /** Continues a run from its persisted state. Safe to call repeatedly. */
  public AgentRunRow execute(UUID tenantId, UUID userId, UUID runId) {
    AgentRunRow run = requireRun(tenantId, userId, runId);
    RunState state = RunState.valueOf(run.state());
    if (state == RunState.SUCCEEDED || state == RunState.FAILED || state == RunState.CANCELLED) {
      return run;
    }
    if (state == RunState.PENDING) {
      if (!repository.transitionRun(
          tenantId, userId, runId, RunState.PENDING.name(), RunState.RUNNING.name(), null, null)) {
        return requireRun(tenantId, userId, runId);
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
        return requireRun(tenantId, userId, runId);
      }
    }

    RunMode mode = RunMode.valueOf(run.mode());
    List<AgentTaskRow> tasks = repository.listTasks(tenantId, userId, runId);
    for (AgentTaskRow task : tasks) {
      TaskState taskState = TaskState.valueOf(task.state());
      switch (taskState) {
        case SUCCEEDED, SKIPPED, CANCELLED -> {
          // Already settled; never repeated.
        }
        case FAILED -> {
          repository.transitionRun(
              tenantId,
              userId,
              runId,
              RunState.RUNNING.name(),
              RunState.FAILED.name(),
              null,
              task.error() == null ? "A task failed" : task.error());
          audit.appendForTenant(
              tenantId, userId, "AGENT_RUN_FAILED", "agent_run", runId.toString(), "{}");
          return requireRun(tenantId, userId, runId);
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
          return requireRun(tenantId, userId, runId);
        }
        case RUNNING -> {
          // A previous executor died mid-task. Requeue it and execute it as PENDING.
          repository.transitionTask(
              tenantId,
              userId,
              task.id(),
              TaskState.RUNNING.name(),
              TaskState.PENDING.name(),
              null,
              null);
          AgentRunRow outcome =
              executeTask(
                  tenantId, userId, run, mode, reloadTask(tenantId, userId, runId, task.id()));
          if (outcome != null) {
            return outcome;
          }
        }
        case PENDING -> {
          AgentRunRow outcome = executeTask(tenantId, userId, run, mode, task);
          if (outcome != null) {
            return outcome;
          }
        }
      }
      if (Thread.currentThread().isInterrupted()) {
        return requireRun(tenantId, userId, runId);
      }
    }

    int succeeded =
        (int)
            repository.listTasks(tenantId, userId, runId).stream()
                .filter(t -> TaskState.valueOf(t.state()) == TaskState.SUCCEEDED)
                .count();
    repository.transitionRun(
        tenantId,
        userId,
        runId,
        RunState.RUNNING.name(),
        RunState.SUCCEEDED.name(),
        "{\"tasksSucceeded\":" + succeeded + "}",
        null);
    audit.appendForTenant(
        tenantId, userId, "AGENT_RUN_SUCCEEDED", "agent_run", runId.toString(), "{}");
    return requireRun(tenantId, userId, runId);
  }

  /**
   * Executes one PENDING task, or parks it for approval. Returns the run row when the run has left
   * the RUNNING state (waiting or failed), or {@code null} to continue with the next task.
   */
  private @Nullable AgentRunRow executeTask(
      UUID tenantId, UUID userId, AgentRunRow run, RunMode mode, AgentTaskRow task) {
    RiskClass risk = RiskClass.valueOf(task.riskClass());
    if (ModeGate.requiresApproval(mode, risk)) {
      UUID approvalId =
          repository.requestApproval(
              tenantId,
              userId,
              task.id(),
              "Tool "
                  + task.toolKey()
                  + " ("
                  + risk.name()
                  + ") requires approval in "
                  + mode.name()
                  + " mode");
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
      return requireRun(tenantId, userId, run.id());
    }
    if (!repository.transitionTask(
        tenantId,
        userId,
        task.id(),
        TaskState.PENDING.name(),
        TaskState.RUNNING.name(),
        null,
        null)) {
      return null; // Another executor claimed it; move on.
    }
    ToolHandler tool = tools.require(task.toolKey());
    try {
      String output = tool.execute(task.inputJson());
      repository.transitionTask(
          tenantId,
          userId,
          task.id(),
          TaskState.RUNNING.name(),
          TaskState.SUCCEEDED.name(),
          output,
          null);
      audit.appendForTenant(
          tenantId,
          userId,
          "AGENT_TASK_EXECUTED",
          "agent_task",
          task.id().toString(),
          "{\"tool\":\"" + task.toolKey() + "\",\"riskClass\":\"" + risk.name() + "\"}");
      return null;
    } catch (RuntimeException e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      repository.transitionTask(
          tenantId,
          userId,
          task.id(),
          TaskState.RUNNING.name(),
          TaskState.FAILED.name(),
          null,
          message);
      repository.transitionRun(
          tenantId,
          userId,
          run.id(),
          RunState.RUNNING.name(),
          RunState.FAILED.name(),
          null,
          message);
      audit.appendForTenant(
          tenantId, userId, "AGENT_RUN_FAILED", "agent_run", run.id().toString(), "{}");
      return requireRun(tenantId, userId, run.id());
    }
  }

  /** Records a human decision; an approval resumes the run. Returns the run afterwards. */
  public AgentRunRow decideApproval(
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
    AgentRunRow run = requireRun(tenantId, deciderUserId, approval.runId());
    // Either way the run leaves WAITING_APPROVAL: an approval lets execution continue; a
    // rejection makes execute() observe the FAILED task and fail the run with its reason.
    return execute(tenantId, run.initiatedBy(), approval.runId());
  }

  public AgentRunRow requireRun(UUID tenantId, UUID userId, UUID runId) {
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
