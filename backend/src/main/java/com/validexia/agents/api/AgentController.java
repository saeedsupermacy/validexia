package com.validexia.agents.api;

import com.validexia.agents.application.AgentEngine;
import com.validexia.agents.application.ToolRegistry;
import com.validexia.agents.domain.RunMode;
import com.validexia.agents.infrastructure.AgentsRepository;
import com.validexia.agents.infrastructure.AgentsRepository.AgentApprovalRow;
import com.validexia.agents.infrastructure.AgentsRepository.AgentDefinitionRow;
import com.validexia.agents.infrastructure.AgentsRepository.AgentRunRow;
import com.validexia.agents.infrastructure.AgentsRepository.AgentTaskRow;
import com.validexia.platform.errors.ApiException;
import com.validexia.platform.security.RequestContext;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent foundation endpoints. Everything is scoped to the caller's per-request tenant (the {@code
 * X-Tenant-Id} header, verified by the authentication filter); there is no cross-tenant view.
 * Registering a definition is a human action taken through this API — agents themselves have no
 * endpoint that could alter definitions, tools, or permissions.
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

  public record DefinitionRequest(
      String agentKey,
      String name,
      @Nullable String description,
      String kind,
      @Nullable String config) {}

  public record DefinitionView(
      UUID id, String agentKey, String name, String description, String kind, String status) {}

  public record ToolView(String key, String riskClass) {}

  public record CreateRunRequest(
      String agentKey,
      String objective,
      String mode,
      @Nullable String idempotencyKey,
      @Nullable String context) {}

  public record TaskView(
      UUID id,
      int seq,
      String toolKey,
      String riskClass,
      String state,
      @Nullable String output,
      @Nullable String error,
      int attempts) {}

  public record RunView(
      UUID id,
      String agentKey,
      String objective,
      String mode,
      String state,
      @Nullable String result,
      @Nullable String error,
      List<TaskView> tasks) {}

  public record ApprovalView(
      UUID id,
      UUID runId,
      UUID taskId,
      String state,
      String reason,
      @Nullable String decisionReason) {}

  public record DecisionRequest(String decision, @Nullable String reason) {}

  private final AgentsRepository repository;
  private final AgentEngine engine;
  private final ToolRegistry tools;

  public AgentController(AgentsRepository repository, AgentEngine engine, ToolRegistry tools) {
    this.repository = repository;
    this.engine = engine;
    this.tools = tools;
  }

  @PostMapping("/definitions")
  public DefinitionView registerDefinition(@RequestBody DefinitionRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    if (request.agentKey() == null || request.name() == null || request.kind() == null) {
      throw ApiException.validation("agentKey, name and kind are required");
    }
    UUID id =
        repository.registerDefinition(
            tenantId,
            context.userId(),
            request.agentKey(),
            request.name(),
            request.description() == null ? "" : request.description(),
            request.kind(),
            request.config() == null ? "{}" : request.config());
    return repository.listDefinitions(tenantId, context.userId()).stream()
        .filter(d -> d.id().equals(id))
        .findFirst()
        .map(AgentController::toView)
        .orElseThrow(() -> new IllegalStateException("Definition vanished after registration"));
  }

  @GetMapping("/definitions")
  public List<DefinitionView> definitions() {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return repository.listDefinitions(tenantId, context.userId()).stream()
        .map(AgentController::toView)
        .toList();
  }

  @GetMapping("/tools")
  public List<ToolView> tools() {
    RequestContext.require();
    return tools.all().stream().map(t -> new ToolView(t.key(), t.riskClass().name())).toList();
  }

  @PostMapping("/runs")
  public RunView createRun(@RequestBody CreateRunRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    if (request.agentKey() == null || request.objective() == null || request.mode() == null) {
      throw ApiException.validation("agentKey, objective and mode are required");
    }
    RunMode mode;
    try {
      mode = RunMode.valueOf(request.mode());
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("mode must be ASSIST, APPROVAL or AUTONOMOUS");
    }
    AgentRunRow run;
    try {
      run =
          engine.createAndStart(
              tenantId,
              context.userId(),
              request.agentKey(),
              request.objective(),
              mode,
              request.idempotencyKey(),
              request.context() == null ? "{}" : request.context());
    } catch (IllegalArgumentException e) {
      throw ApiException.validation(e.getMessage());
    }
    return toView(tenantId, context.userId(), run);
  }

  @GetMapping("/runs/{runId}")
  public RunView run(@PathVariable UUID runId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    try {
      return toView(
          tenantId, context.userId(), engine.requireRun(tenantId, context.userId(), runId));
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          org.springframework.http.HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", e.getMessage());
    }
  }

  @GetMapping("/runs")
  public List<RunView> runs() {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return repository.listRuns(tenantId, context.userId()).stream()
        .map(r -> toView(tenantId, context.userId(), r))
        .toList();
  }

  @PostMapping("/runs/{runId}/resume")
  public RunView resume(@PathVariable UUID runId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    try {
      return toView(tenantId, context.userId(), engine.execute(tenantId, context.userId(), runId));
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          org.springframework.http.HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", e.getMessage());
    }
  }

  @GetMapping("/approvals")
  public List<ApprovalView> approvals() {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return repository.listApprovals(tenantId, context.userId(), "PENDING").stream()
        .map(AgentController::toView)
        .toList();
  }

  @PostMapping("/approvals/{approvalId}/decision")
  public RunView decide(@PathVariable UUID approvalId, @RequestBody DecisionRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    if (!"APPROVED".equals(request.decision()) && !"REJECTED".equals(request.decision())) {
      throw ApiException.validation("decision must be APPROVED or REJECTED");
    }
    try {
      AgentRunRow run =
          engine.decideApproval(
              tenantId, context.userId(), approvalId, request.decision(), request.reason());
      return toView(tenantId, context.userId(), run);
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          org.springframework.http.HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND", e.getMessage());
    } catch (IllegalStateException e) {
      throw ApiException.validation(e.getMessage());
    }
  }

  private RunView toView(UUID tenantId, UUID userId, AgentRunRow run) {
    List<TaskView> tasks =
        repository.listTasks(tenantId, userId, run.id()).stream()
            .map(AgentController::toView)
            .toList();
    return new RunView(
        run.id(),
        run.agentKey(),
        run.objective(),
        run.mode(),
        run.state(),
        run.resultJson(),
        run.error(),
        tasks);
  }

  private static DefinitionView toView(AgentDefinitionRow row) {
    return new DefinitionView(
        row.id(), row.agentKey(), row.name(), row.description(), row.kind(), row.status());
  }

  private static TaskView toView(AgentTaskRow row) {
    return new TaskView(
        row.id(),
        row.seq(),
        row.toolKey(),
        row.riskClass(),
        row.state(),
        row.outputJson(),
        row.error(),
        row.attempts());
  }

  private static ApprovalView toView(AgentApprovalRow row) {
    return new ApprovalView(
        row.id(), row.runId(), row.taskId(), row.state(), row.reason(), row.decisionReason());
  }

  private static UUID requireTenant(RequestContext.Context context) {
    if (context.tenantId() == null) {
      throw ApiException.validation("X-Tenant-Id header is required for agent endpoints");
    }
    return context.tenantId();
  }
}
