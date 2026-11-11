package com.validexia.agents.infrastructure;

import com.validexia.platform.tenancy.TenantContext;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The agents module's only door to its tables. Every method calls a SECURITY DEFINER function from
 * migration V5; the application role holds no direct grants on the agent tables. Each call runs in
 * its own transaction opened through the tenant-aware transaction manager, with the tenant context
 * set first — the same pattern {@code AuditService} uses, so {@code app.tenant_id} is the
 * transaction's first statement and the functions' tenant guard sees it.
 */
@Repository
public class AgentsRepository {

  public record AgentDefinitionRow(
      UUID id,
      String agentKey,
      String name,
      String description,
      String kind,
      String status,
      String configJson,
      Instant createdAt) {}

  public record AgentRunRow(
      UUID id,
      String agentKey,
      String objective,
      String mode,
      String state,
      @Nullable String resultJson,
      @Nullable String error,
      UUID initiatedBy,
      Instant createdAt,
      Instant updatedAt) {}

  public record AgentTaskRow(
      UUID id,
      int seq,
      String toolKey,
      String riskClass,
      String state,
      String inputJson,
      @Nullable String outputJson,
      @Nullable String error,
      int attempts) {}

  public record AgentApprovalRow(
      UUID id,
      UUID runId,
      UUID taskId,
      String state,
      String reason,
      Instant requestedAt,
      @Nullable UUID decidedBy,
      @Nullable String decisionReason,
      @Nullable Instant decidedAt) {}

  public record CreateRunResult(UUID runId, boolean wasCreated) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public AgentsRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public UUID registerDefinition(
      UUID tenantId,
      UUID userId,
      String agentKey,
      String name,
      String description,
      String kind,
      String configJson) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.queryForObject(
                "SELECT public.register_agent_definition(?, ?, ?, ?, ?, CAST(? AS jsonb), ?)",
                UUID.class,
                tenantId,
                agentKey,
                name,
                description,
                kind,
                configJson,
                userId));
  }

  public List<AgentDefinitionRow> listDefinitions(UUID tenantId, UUID userId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.query(
                "SELECT id, agent_key, name, description, kind, status, config, created_at"
                    + " FROM public.list_agent_definitions(?)",
                (rs, rowNum) ->
                    new AgentDefinitionRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("agent_key"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("kind"),
                        rs.getString("status"),
                        rs.getString("config"),
                        toInstant(rs.getObject("created_at", OffsetDateTime.class))),
                tenantId));
  }

  public CreateRunResult createRun(
      UUID tenantId,
      UUID userId,
      String agentKey,
      String objective,
      String mode,
      @Nullable String idempotencyKey,
      String contextJson) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.queryForObject(
                "SELECT run_id, was_created FROM public.create_agent_run(?, ?, ?, ?, ?, ?,"
                    + " CAST(? AS jsonb))",
                new Object[] {
                  tenantId, agentKey, objective, mode, userId, idempotencyKey, contextJson
                },
                new int[] {
                  Types.OTHER,
                  Types.VARCHAR,
                  Types.VARCHAR,
                  Types.VARCHAR,
                  Types.OTHER,
                  Types.VARCHAR,
                  Types.OTHER
                },
                (rs, rowNum) ->
                    new CreateRunResult(
                        rs.getObject("run_id", UUID.class), rs.getBoolean("was_created"))));
  }

  public Optional<AgentRunRow> getRun(UUID tenantId, UUID userId, UUID runId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc
                .query(
                    "SELECT id, agent_key, objective, mode, state, result, error, initiated_by,"
                        + " created_at, updated_at FROM public.get_agent_run(?, ?)",
                    (rs, rowNum) -> mapRun(rs),
                    tenantId,
                    runId)
                .stream()
                .findFirst());
  }

  public List<AgentRunRow> listRuns(UUID tenantId, UUID userId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.query(
                "SELECT id, agent_key, objective, mode, state, result, error, initiated_by,"
                    + " created_at, updated_at FROM public.list_agent_runs(?)",
                (rs, rowNum) -> mapRun(rs),
                tenantId));
  }

  public boolean transitionRun(
      UUID tenantId,
      UUID userId,
      UUID runId,
      String expected,
      String target,
      @Nullable String resultJson,
      @Nullable String error) {
    return inTenant(
        tenantId,
        userId,
        () ->
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "SELECT public.transition_agent_run(?, ?, ?, ?, CAST(? AS jsonb), ?)",
                    new Object[] {tenantId, runId, expected, target, resultJson, error},
                    new int[] {
                      Types.OTHER,
                      Types.OTHER,
                      Types.VARCHAR,
                      Types.VARCHAR,
                      Types.OTHER,
                      Types.VARCHAR
                    },
                    Boolean.class)));
  }

  public UUID addTask(
      UUID tenantId,
      UUID userId,
      UUID runId,
      int seq,
      String toolKey,
      String riskClass,
      String inputJson,
      String idempotencyKey) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.queryForObject(
                "SELECT public.add_agent_task(?, ?, ?, ?, ?, CAST(? AS jsonb), ?)",
                UUID.class,
                tenantId,
                runId,
                seq,
                toolKey,
                riskClass,
                inputJson,
                idempotencyKey));
  }

  public List<AgentTaskRow> listTasks(UUID tenantId, UUID userId, UUID runId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.query(
                "SELECT id, seq, tool_key, risk_class, state, input, output, error, attempts"
                    + " FROM public.list_agent_tasks(?, ?)",
                (rs, rowNum) ->
                    new AgentTaskRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("seq"),
                        rs.getString("tool_key"),
                        rs.getString("risk_class"),
                        rs.getString("state"),
                        rs.getString("input"),
                        rs.getString("output"),
                        rs.getString("error"),
                        rs.getInt("attempts")),
                tenantId,
                runId));
  }

  public boolean transitionTask(
      UUID tenantId,
      UUID userId,
      UUID taskId,
      String expected,
      String target,
      @Nullable String outputJson,
      @Nullable String error) {
    return inTenant(
        tenantId,
        userId,
        () ->
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "SELECT public.transition_agent_task(?, ?, ?, ?, CAST(? AS jsonb), ?)",
                    new Object[] {tenantId, taskId, expected, target, outputJson, error},
                    new int[] {
                      Types.OTHER,
                      Types.OTHER,
                      Types.VARCHAR,
                      Types.VARCHAR,
                      Types.OTHER,
                      Types.VARCHAR
                    },
                    Boolean.class)));
  }

  public UUID requestApproval(UUID tenantId, UUID userId, UUID taskId, String reason) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.queryForObject(
                "SELECT public.request_agent_approval(?, ?, ?)",
                UUID.class,
                tenantId,
                taskId,
                reason));
  }

  public boolean decideApproval(
      UUID tenantId, UUID userId, UUID approvalId, String decision, @Nullable String reason) {
    return inTenant(
        tenantId,
        userId,
        () ->
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "SELECT public.decide_agent_approval(?, ?, ?, ?, ?)",
                    new Object[] {tenantId, approvalId, decision, userId, reason},
                    new int[] {Types.OTHER, Types.OTHER, Types.VARCHAR, Types.OTHER, Types.VARCHAR},
                    Boolean.class)));
  }

  public List<AgentApprovalRow> listApprovals(UUID tenantId, UUID userId, @Nullable String state) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.query(
                "SELECT id, run_id, task_id, state, reason, requested_at, decided_by,"
                    + " decision_reason, decided_at FROM public.list_agent_approvals(?, ?)",
                new Object[] {tenantId, state},
                new int[] {Types.OTHER, Types.VARCHAR},
                (rs, rowNum) ->
                    new AgentApprovalRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        rs.getString("state"),
                        rs.getString("reason"),
                        toInstant(rs.getObject("requested_at", OffsetDateTime.class)),
                        rs.getObject("decided_by", UUID.class),
                        rs.getString("decision_reason"),
                        toInstant(rs.getObject("decided_at", OffsetDateTime.class)))));
  }

  private AgentRunRow mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new AgentRunRow(
        rs.getObject("id", UUID.class),
        rs.getString("agent_key"),
        rs.getString("objective"),
        rs.getString("mode"),
        rs.getString("state"),
        rs.getString("result"),
        rs.getString("error"),
        rs.getObject("initiated_by", UUID.class),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)),
        toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
  }

  private <T> T inTenant(UUID tenantId, @Nullable UUID userId, Supplier<T> work) {
    TenantContext.set(tenantId.toString(), userId == null ? null : userId.toString());
    try {
      return transactions.execute(tx -> work.get());
    } finally {
      TenantContext.clear();
    }
  }

  private static Instant toInstant(@Nullable OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
