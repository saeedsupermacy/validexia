package com.validexia.agents.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.agents.domain.RiskClass;
import com.validexia.agents.domain.RunMode;
import com.validexia.agents.infrastructure.AgentsRepository;
import com.validexia.agents.infrastructure.AgentsRepository.AgentTaskRow;
import com.validexia.agents.infrastructure.AgentsRepository.PlanTask;
import com.validexia.audit.AuditService;
import com.validexia.support.TestClerkConfig;
import com.validexia.support.TestDatabase;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan-initialization lifecycle (migration V8): a run and its plan commit atomically, plan
 * readiness is explicit state, and no caller can execute or settle a partially initialized run.
 * Regression suite for the duplicate-creator race found in PR #3 review: a second creator with the
 * same idempotency key could reach execution before the winner persisted the plan and mark the
 * still-empty run SUCCEEDED. All interleavings are driven by latches, never sleeps.
 */
@SpringBootTest(
    properties = {
      "validexia.clerk.issuer=https://clerk.test.example",
      "validexia.clerk.jwks-url=https://clerk.test.example/.well-known/jwks.json",
      "validexia.clerk.authorized-parties=https://app.validexia.test"
    })
@Import(TestClerkConfig.class)
class AgentPlanLifecycleTest {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.springProperties().forEach((key, value) -> registry.add(key, () -> value));
  }

  @Autowired AgentsRepository repository;
  @Autowired AuditService audit;

  private final JdbcTemplate admin = TestDatabase.adminJdbc();

  // -- Test doubles ---------------------------------------------------------------

  /** A READ tool that counts its invocations. */
  static final class CountingTool implements ToolHandler {
    final AtomicInteger entries = new AtomicInteger();

    @Override
    public String key() {
      return "test.count";
    }

    @Override
    public RiskClass riskClass() {
      return RiskClass.READ;
    }

    @Override
    public String execute(String inputJson) {
      entries.incrementAndGet();
      return "{}";
    }
  }

  /** A tool that blocks until interrupted, recording that the interrupt arrived. */
  static final class InterruptibleTool implements ToolHandler {
    final CountDownLatch interrupted = new CountDownLatch(1);

    @Override
    public String key() {
      return "test.hanging";
    }

    @Override
    public RiskClass riskClass() {
      return RiskClass.READ;
    }

    @Override
    public String execute(String inputJson) {
      try {
        if (!new CountDownLatch(1).await(60, TimeUnit.SECONDS)) {
          throw new IllegalStateException("latch unexpectedly released");
        }
      } catch (InterruptedException e) {
        interrupted.countDown();
        Thread.currentThread().interrupt();
        throw new RuntimeException("interrupted", e);
      }
      return "{}";
    }
  }

  private static AgentPlanner immediatePlanner(
      String agentKey, List<AgentPlanner.PlannedTask> plan) {
    return new AgentPlanner() {
      @Override
      public String agentKey() {
        return agentKey;
      }

      @Override
      public List<AgentPlanner.PlannedTask> plan(String objective, String contextJson) {
        return plan;
      }
    };
  }

  /** A planner that pauses mid-plan until the test releases it. */
  static final class PausingPlanner implements AgentPlanner {
    final CountDownLatch planEntered = new CountDownLatch(1);
    final CountDownLatch releasePlan = new CountDownLatch(1);
    private final String agentKey;

    PausingPlanner(String agentKey) {
      this.agentKey = agentKey;
    }

    @Override
    public String agentKey() {
      return agentKey;
    }

    @Override
    public List<AgentPlanner.PlannedTask> plan(String objective, String contextJson) {
      planEntered.countDown();
      try {
        if (!releasePlan.await(20, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test latch was never released");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
      return List.of(new AgentPlanner.PlannedTask("test.count", "{}"));
    }
  }

  /** A planner that fails its first call and plans normally afterwards. */
  static final class FlakyPlanner implements AgentPlanner {
    final AtomicBoolean failNext = new AtomicBoolean(true);
    private final String agentKey;

    FlakyPlanner(String agentKey) {
      this.agentKey = agentKey;
    }

    @Override
    public String agentKey() {
      return agentKey;
    }

    @Override
    public List<AgentPlanner.PlannedTask> plan(String objective, String contextJson) {
      if (failNext.getAndSet(false)) {
        throw new IllegalStateException("planner unavailable");
      }
      return List.of(new AgentPlanner.PlannedTask("test.count", "{}"));
    }
  }

  // -- Helpers ----------------------------------------------------------------------

  private AgentEngine engineFor(AgentPlanner planner, ToolHandler tool) {
    return new AgentEngine(
        repository, new AgentRegistry(List.of(planner)), new ToolRegistry(List.of(tool)), audit);
  }

  /** Creates a bare tenant and user; returns [tenantId, userId]. */
  private UUID[] tenantAndUser() {
    UUID tenantId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'PlanLifecycle', ?)",
        tenantId,
        "p-" + tenantId);
    admin.update(
        "INSERT INTO public.users (id, external_subject) VALUES (?, ?)", userId, "sub-" + userId);
    return new UUID[] {tenantId, userId};
  }

  private void register(UUID tenantId, UUID userId, String agentKey) {
    repository.registerDefinition(
        tenantId, userId, agentKey, "Test agent", "", "DETERMINISTIC", "{}");
  }

  private int auditCount(UUID tenantId, String action, UUID entityId) {
    return admin.queryForObject(
        "SELECT count(*) FROM public.audit_events WHERE tenant_key = ? AND action = ?"
            + " AND entity_id = ?",
        Integer.class,
        tenantId.toString(),
        action,
        entityId.toString());
  }

  private int runCountByKey(UUID tenantId, String key) {
    return admin
        .queryForObject(
            "SELECT count(*) FROM public.agent_runs WHERE tenant_id = ? AND idempotency_key = ?",
            Integer.class,
            tenantId,
            key)
        .intValue();
  }

  // -- Tests ------------------------------------------------------------------------

  /**
   * The PR #3 review race, replayed against the fixed lifecycle. Creator A is paused before its
   * plan is persisted; creator B arrives with the same idempotency key. Under the old code B
   * received A's half-created run, saw an empty task list, and marked it SUCCEEDED. Now nothing of
   * A's run exists until the plan commits atomically: B creates and completes the whole run itself,
   * and A converges on the finished run without re-executing anything.
   */
  @Test
  void duplicateCreatorCannotSettleOrExecuteAnUnreadyPlan() throws Exception {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.planrace");
    CountingTool tool = new CountingTool();
    PausingPlanner pausing = new PausingPlanner("test.planrace");
    AgentEngine engineA = engineFor(pausing, tool);
    AgentEngine engineB =
        engineFor(
            immediatePlanner(
                "test.planrace", List.of(new AgentPlanner.PlannedTask("test.count", "{}"))),
            tool);

    ExecutorService pool = Executors.newFixedThreadPool(1);
    try {
      Future<AgentData.Run> creatorA =
          pool.submit(
              () ->
                  engineA.createAndStart(
                      tenantId,
                      userId,
                      "test.planrace",
                      "obj",
                      RunMode.AUTONOMOUS,
                      "race-key",
                      "{}"));
      assertTrue(pausing.planEntered.await(15, TimeUnit.SECONDS), "creator A reached its plan");

      // A is paused before persistence: no run is visible to anyone, so there is no
      // half-initialized run for B to settle or execute.
      assertTrue(repository.listRuns(tenantId, userId).isEmpty(), "no run may be visible yet");
      assertEquals(0, runCountByKey(tenantId, "race-key"));

      // B, same key: creates the run WITH its complete plan in one commit and executes it.
      AgentData.Run byB =
          engineB.createAndStart(
              tenantId, userId, "test.planrace", "obj", RunMode.AUTONOMOUS, "race-key", "{}");
      assertEquals("SUCCEEDED", byB.state());
      assertEquals(1, tool.entries.get());

      // A resumes and converges on the same finished run.
      pausing.releasePlan.countDown();
      AgentData.Run byA = creatorA.get(15, TimeUnit.SECONDS);
      assertEquals(byB.id(), byA.id());
      assertEquals("SUCCEEDED", byA.state());

      // The plan was persisted exactly once, and the tool executed exactly once.
      assertEquals(1, runCountByKey(tenantId, "race-key"));
      assertEquals(1, repository.listTasks(tenantId, userId, byB.id()).size());
      assertEquals(1, tool.entries.get());
      assertEquals(1, auditCount(tenantId, "AGENT_RUN_CREATED", byB.id()));
      UUID taskId = repository.listTasks(tenantId, userId, byB.id()).get(0).id();
      assertEquals(1, auditCount(tenantId, "AGENT_TASK_EXECUTED", taskId));
      assertEquals(1, auditCount(tenantId, "AGENT_RUN_SUCCEEDED", byB.id()));
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Defence in depth behind the atomic creation path: a run whose plan is not marked ready (only
   * reachable via the legacy split creation functions) can never be executed or settled — an empty
   * task list means "not initialized", never "done".
   */
  @Test
  void anUnreadyRunIsNeverExecutedOrSettled() {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.unready");
    CountingTool tool = new CountingTool();
    AgentEngine engine =
        engineFor(
            immediatePlanner(
                "test.unready", List.of(new AgentPlanner.PlannedTask("test.count", "{}"))),
            tool);

    UUID runId =
        repository
            .createRun(tenantId, userId, "test.unready", "obj", "AUTONOMOUS", null, "{}")
            .runId();
    // Even with a task attached, an unready run does not execute.
    repository.addTask(tenantId, userId, runId, 1, "test.count", "READ", "{}", runId + ":1");

    AgentData.Run result = engine.execute(tenantId, userId, runId);
    assertEquals("PENDING", result.state());
    assertEquals(0, tool.entries.get());
    assertEquals(0, auditCount(tenantId, "AGENT_RUN_SUCCEEDED", runId));
  }

  /** A planner failure happens before any persistence: no run, no tasks, and a clean retry. */
  @Test
  void plannerFailureLeavesNoRunBehind() {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.flaky");
    CountingTool tool = new CountingTool();
    AgentEngine engine = engineFor(new FlakyPlanner("test.flaky"), tool);

    assertThrows(
        IllegalStateException.class,
        () ->
            engine.createAndStart(
                tenantId, userId, "test.flaky", "obj", RunMode.AUTONOMOUS, "fail-key", "{}"));
    assertEquals(0, runCountByKey(tenantId, "fail-key"));
    assertTrue(repository.listRuns(tenantId, userId).isEmpty());

    AgentData.Run retried =
        engine.createAndStart(
            tenantId, userId, "test.flaky", "obj", RunMode.AUTONOMOUS, "fail-key", "{}");
    assertEquals("SUCCEEDED", retried.state());
    assertEquals(1, runCountByKey(tenantId, "fail-key"));
    assertEquals(1, repository.listTasks(tenantId, userId, retried.id()).size());
    assertEquals(1, tool.entries.get());
  }

  /** A legitimately empty plan is valid: explicitly plan-ready, zero tasks, settles at once. */
  @Test
  void aLegitimatelyEmptyPlanIsReadyAndSucceeds() {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.empty");
    CountingTool tool = new CountingTool();
    AgentEngine engine = engineFor(immediatePlanner("test.empty", List.of()), tool);

    AgentData.Run run =
        engine.createAndStart(
            tenantId, userId, "test.empty", "obj", RunMode.AUTONOMOUS, "empty-key", "{}");
    assertEquals("SUCCEEDED", run.state());
    assertTrue(repository.listTasks(tenantId, userId, run.id()).isEmpty());
    assertTrue(
        repository.getRun(tenantId, userId, run.id()).orElseThrow().planReady(),
        "an empty plan must still be explicitly plan-ready");
    assertEquals(0, tool.entries.get());
    assertEquals(1, auditCount(tenantId, "AGENT_RUN_CREATED", run.id()));
    assertEquals(1, auditCount(tenantId, "AGENT_RUN_SUCCEEDED", run.id()));
  }

  /** The database validates lease durations: positive and bounded (migration V8). */
  @Test
  void claimRejectsOutOfRangeLeaseDurations() {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.leasebounds");
    UUID runId =
        repository
            .createRunWithPlan(
                tenantId,
                userId,
                "test.leasebounds",
                "obj",
                "AUTONOMOUS",
                null,
                "{}",
                List.of(new PlanTask("test.count", "READ", "{}")))
            .runId();
    AgentTaskRow task = repository.listTasks(tenantId, userId, runId).get(0);

    assertThrows(
        DataAccessException.class,
        () -> repository.claimTask(tenantId, userId, runId, task.id(), "exec", 0));
    assertThrows(
        DataAccessException.class,
        () -> repository.claimTask(tenantId, userId, runId, task.id(), "exec", 3601));
    // A valid duration still claims normally.
    assertTrue(repository.claimTask(tenantId, userId, runId, task.id(), "exec", 30).claimed());
  }

  /**
   * The engine enforces a tool timeout below the lease: a hanging tool is interrupted and its task
   * failed while the claim is still valid, instead of overrunning the lease and being duplicated by
   * a takeover.
   */
  @Test
  void toolTimeoutFailsTheTaskWithinTheLease() throws Exception {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.timeout");
    InterruptibleTool tool = new InterruptibleTool();
    AgentEngine engine =
        new AgentEngine(
            repository,
            new AgentRegistry(
                List.of(
                    immediatePlanner(
                        "test.timeout",
                        List.of(new AgentPlanner.PlannedTask("test.hanging", "{}"))))),
            new ToolRegistry(List.of(tool)),
            audit,
            3,
            1);

    long startedNanos = System.nanoTime();
    AgentData.Run run =
        engine.createAndStart(
            tenantId, userId, "test.timeout", "obj", RunMode.AUTONOMOUS, "timeout-key", "{}");
    long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos);

    assertEquals("FAILED", run.state());
    assertTrue(elapsedSeconds < 15, "timeout must fire promptly, took " + elapsedSeconds + "s");
    AgentTaskRow task = repository.listTasks(tenantId, userId, run.id()).get(0);
    assertEquals("FAILED", task.state());
    assertTrue(
        task.error() != null && task.error().contains("timed out"),
        "task error must name the timeout, got: " + task.error());
    assertTrue(tool.interrupted.await(5, TimeUnit.SECONDS), "the tool thread was interrupted");
    assertEquals(1, auditCount(tenantId, "AGENT_RUN_FAILED", run.id()));
  }
}
