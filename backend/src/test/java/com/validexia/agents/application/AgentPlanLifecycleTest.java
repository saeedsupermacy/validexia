package com.validexia.agents.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.agents.domain.RiskClass;
import com.validexia.agents.domain.RunMode;
import com.validexia.agents.infrastructure.AgentsRepository;
import com.validexia.agents.infrastructure.AgentsRepository.AgentRunRow;
import com.validexia.agents.infrastructure.AgentsRepository.AgentTaskRow;
import com.validexia.agents.infrastructure.AgentsRepository.ClaimOutcome;
import com.validexia.agents.infrastructure.AgentsRepository.PlanTask;
import com.validexia.audit.AuditService;
import com.validexia.support.TestClerkConfig;
import com.validexia.support.TestDatabase;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

  /** A tool that deliberately swallows interrupts and keeps running until released. */
  static final class IgnoringTool implements ToolHandler {
    final AtomicInteger entries = new AtomicInteger();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch interruptIgnored = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @Override
    public String key() {
      return "test.ignoring";
    }

    @Override
    public RiskClass riskClass() {
      return RiskClass.READ;
    }

    @Override
    public String execute(String inputJson) {
      entries.incrementAndGet();
      entered.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (release.getCount() > 0 && System.nanoTime() < deadline) {
        try {
          release.await(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          // Deliberately swallowed: this tool ignores interruption and keeps running.
          interruptIgnored.countDown();
        }
      }
      return "{\"done\":true}";
    }
  }

  /** A tool that blocks until the test releases it. */
  static final class LatchTool implements ToolHandler {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @Override
    public String key() {
      return "test.latch";
    }

    @Override
    public RiskClass riskClass() {
      return RiskClass.READ;
    }

    @Override
    public String execute(String inputJson) {
      entered.countDown();
      try {
        if (!release.await(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test latch was never released");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
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
   * The execution budget is a request, not a kill: a tool that RESPONDS to interruption ends, and
   * only then is its task failed — the recorded outcome belongs to an invocation that has actually
   * ended, settled by the claim owner while its (heartbeat-held) claim is valid.
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
            1,
            4,
            16);

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

  /**
   * Finding 7: a tool that IGNORES interruption keeps running past the execution budget and past
   * the point where its lease would have expired. The heartbeat holds its claim, so no takeover can
   * start a second invocation; the task stays honestly RUNNING; and when the tool finally ends, its
   * real outcome is recorded exactly once.
   */
  @Test
  void aToolThatIgnoresInterruptionIsNeverInvokedTwice() throws Exception {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.ignoring");
    IgnoringTool tool = new IgnoringTool();
    AgentEngine engine =
        new AgentEngine(
            repository,
            new AgentRegistry(
                List.of(
                    immediatePlanner(
                        "test.ignoring",
                        List.of(new AgentPlanner.PlannedTask("test.ignoring", "{}"))))),
            new ToolRegistry(List.of(tool)),
            audit,
            3,
            1,
            4,
            16);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<AgentData.Run> owner =
          pool.submit(
              () ->
                  engine.createAndStart(
                      tenantId,
                      userId,
                      "test.ignoring",
                      "obj",
                      RunMode.AUTONOMOUS,
                      "ignore-key",
                      "{}"));
      assertTrue(tool.entered.await(15, TimeUnit.SECONDS), "the tool started");
      assertTrue(
          tool.interruptIgnored.await(15, TimeUnit.SECONDS),
          "the budget fired, interruption was requested — and ignored");
      UUID runId = repository.listRuns(tenantId, userId).get(0).id();

      // Let more than a full lease duration pass with the tool still running. Without the
      // heartbeat the claim would now be expired and takeable.
      Thread.sleep(5000);
      AgentTaskRow during = repository.listTasks(tenantId, userId, runId).get(0);
      assertEquals("RUNNING", during.state());

      // A second executor observes the live claim and cannot invoke the tool again.
      AgentData.Run second =
          pool.submit(() -> engine.execute(tenantId, userId, runId)).get(15, TimeUnit.SECONDS);
      assertEquals("RUNNING", second.state());
      assertEquals(1, tool.entries.get());

      // When the tool finally ends, its real outcome is recorded — exactly once.
      tool.release.countDown();
      AgentData.Run finished = owner.get(15, TimeUnit.SECONDS);
      assertEquals("SUCCEEDED", finished.state());
      assertEquals(1, tool.entries.get());
      UUID taskId = repository.listTasks(tenantId, userId, runId).get(0).id();
      assertEquals(1, auditCount(tenantId, "AGENT_TASK_EXECUTED", taskId));
      assertEquals(1, auditCount(tenantId, "AGENT_RUN_SUCCEEDED", runId));
    } finally {
      pool.shutdownNow();
    }
  }

  /** Renewal is fenced exactly like completion: executor id + attempt, and no resurrection. */
  @Test
  void renewalIsFencedByExecutorAndAttempt() {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.renew");
    UUID runId =
        repository
            .createRunWithPlan(
                tenantId,
                userId,
                "test.renew",
                "obj",
                "AUTONOMOUS",
                null,
                "{}",
                List.of(new PlanTask("test.count", "READ", "{}")))
            .runId();
    AgentTaskRow task = repository.listTasks(tenantId, userId, runId).get(0);

    ClaimOutcome claim = repository.claimTask(tenantId, userId, runId, task.id(), "exec-1", 30);
    assertTrue(claim.claimed());
    // Neither a different executor nor a different attempt can renew this claim.
    assertFalse(
        repository.renewTaskClaim(tenantId, userId, task.id(), "exec-2", claim.attempt(), 30));
    assertFalse(
        repository.renewTaskClaim(tenantId, userId, task.id(), "exec-1", claim.attempt() + 1, 30));
    // The owner renews.
    assertTrue(
        repository.renewTaskClaim(tenantId, userId, task.id(), "exec-1", claim.attempt(), 30));

    // An expired claim cannot be resurrected by its old owner.
    admin.update(
        "UPDATE public.agent_tasks SET claim_expires_at = now() - interval '1 minute'"
            + " WHERE id = ?",
        task.id());
    assertFalse(
        repository.renewTaskClaim(tenantId, userId, task.id(), "exec-1", claim.attempt(), 30));

    // After a takeover the fence has moved: the stale owner stays locked out.
    ClaimOutcome takeover = repository.claimTask(tenantId, userId, runId, task.id(), "exec-2", 30);
    assertTrue(takeover.claimed());
    assertFalse(
        repository.renewTaskClaim(tenantId, userId, task.id(), "exec-1", claim.attempt(), 30));
    assertTrue(
        repository.renewTaskClaim(tenantId, userId, task.id(), "exec-2", takeover.attempt(), 30));
  }

  /**
   * When the database is unavailable during renewal, the heartbeat treats the failure as transient:
   * it keeps retrying and never declares the claim lost on the basis of an error.
   */
  @Test
  void heartbeatTreatsRenewerFailuresAsTransient() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      AtomicInteger calls = new AtomicInteger();
      CountDownLatch fourthCall = new CountDownLatch(1);
      TaskClaimHeartbeat.Renewer renewer =
          () -> {
            int call = calls.incrementAndGet();
            if (call >= 4) {
              fourthCall.countDown();
            }
            if (call <= 3) {
              throw new RuntimeException("database unavailable");
            }
            return true;
          };
      try (TaskClaimHeartbeat heartbeat = TaskClaimHeartbeat.start(scheduler, renewer, 1)) {
        assertTrue(
            fourthCall.await(15, TimeUnit.SECONDS), "the heartbeat kept retrying after failures");
        assertFalse(heartbeat.claimLost());
      }
    } finally {
      scheduler.shutdownNow();
    }
  }

  /** Only an explicit fenced-out renewal (a false) signals claim loss. */
  @Test
  void heartbeatSignalsProvenClaimLoss() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      CountDownLatch called = new CountDownLatch(1);
      TaskClaimHeartbeat.Renewer renewer =
          () -> {
            called.countDown();
            return false;
          };
      try (TaskClaimHeartbeat heartbeat = TaskClaimHeartbeat.start(scheduler, renewer, 1)) {
        assertTrue(called.await(15, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!heartbeat.claimLost() && System.nanoTime() < deadline) {
          Thread.sleep(25);
        }
        assertTrue(heartbeat.claimLost(), "a fenced-out renewal must signal claim loss");
      }
    } finally {
      scheduler.shutdownNow();
    }
  }

  /**
   * Bounded execution capacity: with the single tool thread busy and no queue, a second invocation
   * is rejected BEFORE it starts — its task fails with the capacity error and its tool is never
   * invoked. (Failing a task is only safe here because nothing ran.)
   */
  @Test
  void saturatedToolCapacityRejectsWithoutStartingTheInvocation() throws Exception {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.sat-block");
    register(tenantId, userId, "test.sat-count");
    LatchTool blocking = new LatchTool();
    CountingTool counting = new CountingTool();
    AgentEngine engine =
        new AgentEngine(
            repository,
            new AgentRegistry(
                List.of(
                    immediatePlanner(
                        "test.sat-block",
                        List.of(new AgentPlanner.PlannedTask("test.latch", "{}"))),
                    immediatePlanner(
                        "test.sat-count",
                        List.of(new AgentPlanner.PlannedTask("test.count", "{}"))))),
            new ToolRegistry(List.of(blocking, counting)),
            audit,
            30,
            20,
            1,
            0);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<AgentData.Run> first =
          pool.submit(
              () ->
                  engine.createAndStart(
                      tenantId,
                      userId,
                      "test.sat-block",
                      "obj",
                      RunMode.AUTONOMOUS,
                      "sat-key-1",
                      "{}"));
      assertTrue(blocking.entered.await(15, TimeUnit.SECONDS), "the only tool thread is busy");

      AgentData.Run rejected =
          pool.submit(
                  () ->
                      engine.createAndStart(
                          tenantId,
                          userId,
                          "test.sat-count",
                          "obj",
                          RunMode.AUTONOMOUS,
                          "sat-key-2",
                          "{}"))
              .get(15, TimeUnit.SECONDS);
      assertEquals("FAILED", rejected.state());
      assertEquals(0, counting.entries.get());
      AgentTaskRow rejectedTask = repository.listTasks(tenantId, userId, rejected.id()).get(0);
      assertEquals("FAILED", rejectedTask.state());
      assertTrue(
          rejectedTask.error() != null && rejectedTask.error().contains("capacity"),
          "the rejection must name the capacity limit, got: " + rejectedTask.error());

      blocking.release.countDown();
      assertEquals("SUCCEEDED", first.get(15, TimeUnit.SECONDS).state());
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Finding 8: interrupting the ENGINE thread must not abandon the invocation. The old handler
   * restored the flag and threw at once, closing the heartbeat while the tool kept running — its
   * claim could then expire and a takeover could invoke the same task concurrently. Now the engine
   * requests the tool's interruption, keeps observing with the heartbeat alive, and restores the
   * caller's interrupt status only after the invocation has actually ended.
   */
  @Test
  void callerInterruptionKeepsTheHeartbeatAndRestoresTheFlag() throws Exception {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.caller-interrupt");
    IgnoringTool tool = new IgnoringTool();
    AgentEngine engine =
        new AgentEngine(
            repository,
            new AgentRegistry(
                List.of(
                    immediatePlanner(
                        "test.caller-interrupt",
                        List.of(new AgentPlanner.PlannedTask("test.ignoring", "{}"))))),
            new ToolRegistry(List.of(tool)),
            audit,
            6,
            3,
            4,
            16);

    AtomicReference<AgentData.Run> result = new AtomicReference<>();
    AtomicBoolean restored = new AtomicBoolean();
    CountDownLatch done = new CountDownLatch(1);
    Thread engineThread =
        new Thread(
            () -> {
              try {
                result.set(
                    engine.createAndStart(
                        tenantId,
                        userId,
                        "test.caller-interrupt",
                        "obj",
                        RunMode.AUTONOMOUS,
                        "caller-key",
                        "{}"));
              } finally {
                restored.set(Thread.currentThread().isInterrupted());
                done.countDown();
              }
            });
    engineThread.start();
    assertTrue(tool.entered.await(15, TimeUnit.SECONDS), "the tool started");
    engineThread.interrupt();
    assertTrue(
        tool.interruptIgnored.await(15, TimeUnit.SECONDS),
        "the engine requested the tool's interruption — and the tool ignored it");

    // Beyond a full lease duration, the engine thread must STILL be observing (heartbeat
    // alive), the task still RUNNING, and no second executor able to invoke the tool.
    Thread.sleep(7000);
    assertEquals(1L, done.getCount(), "the engine thread must not abandon a running invocation");
    UUID runId = repository.listRuns(tenantId, userId).get(0).id();
    assertEquals("RUNNING", repository.listTasks(tenantId, userId, runId).get(0).state());
    AgentData.Run second = engine.execute(tenantId, userId, runId);
    assertEquals("RUNNING", second.state());
    assertEquals(1, tool.entries.get());

    tool.release.countDown();
    assertTrue(done.await(15, TimeUnit.SECONDS));
    // The invocation's real outcome was recorded exactly once. The run itself is still RUNNING:
    // restoring the caller's interrupt status makes the engine stop its walk after the current
    // task (its designed response to an interrupt) — a later execute() settles the run.
    assertEquals("RUNNING", result.get().state());
    assertTrue(restored.get(), "the caller's interrupt status is restored after the invocation");
    assertEquals(1, tool.entries.get());
    UUID taskId = repository.listTasks(tenantId, userId, runId).get(0).id();
    assertEquals("SUCCEEDED", repository.listTasks(tenantId, userId, runId).get(0).state());
    assertEquals(1, auditCount(tenantId, "AGENT_TASK_EXECUTED", taskId));
    AgentData.Run settled = engine.execute(tenantId, userId, runId);
    assertEquals("SUCCEEDED", settled.state());
    assertEquals(1, auditCount(tenantId, "AGENT_RUN_SUCCEEDED", runId));
    assertEquals(1, tool.entries.get());
  }

  /** An engine with one tool thread: the first run occupies it, the second invocation queues. */
  private AgentEngine queuedEngine(
      IgnoringTool blocking, CountingTool counting, int leaseSeconds, int budgetSeconds) {
    return new AgentEngine(
        repository,
        new AgentRegistry(
            List.of(
                immediatePlanner(
                    "test.q-block", List.of(new AgentPlanner.PlannedTask("test.ignoring", "{}"))),
                immediatePlanner(
                    "test.q-count", List.of(new AgentPlanner.PlannedTask("test.count", "{}"))))),
        new ToolRegistry(List.of(blocking, counting)),
        audit,
        leaseSeconds,
        budgetSeconds,
        1,
        4);
  }

  /** Polls until the given run's only task is claimed (RUNNING) or the deadline passes. */
  private void awaitTaskRunning(UUID tenantId, UUID userId, UUID runId) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      List<AgentTaskRow> tasks = repository.listTasks(tenantId, userId, runId);
      if (!tasks.isEmpty() && "RUNNING".equals(tasks.get(0).state())) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("task of run " + runId + " never became RUNNING");
  }

  /** Polls until a run for the agent key exists (its creator thread commits it asynchronously). */
  private UUID runIdOf(UUID tenantId, UUID userId, String agentKey) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      var runs = repository.listRuns(tenantId, userId);
      for (AgentRunRow run : runs) {
        if (agentKey.equals(run.agentKey())) {
          return run.id();
        }
      }
      Thread.sleep(50);
    }
    throw new AssertionError("no run for " + agentKey);
  }

  /**
   * Finding 9 (claim loss while queued): the second invocation sits in the executor queue when its
   * claim is lost. When a worker finally picks it up, it must NEVER call the tool — the queued run
   * observes the cancellation recorded before its start and refuses to execute.
   */
  @Test
  void claimLossWhileQueuedSuppressesTheInvocation() throws Exception {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.q-block");
    register(tenantId, userId, "test.q-count");
    IgnoringTool blocking = new IgnoringTool();
    CountingTool counting = new CountingTool();
    AgentEngine engine = queuedEngine(blocking, counting, 3, 2);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<AgentData.Run> first =
          pool.submit(
              () ->
                  engine.createAndStart(
                      tenantId,
                      userId,
                      "test.q-block",
                      "obj",
                      RunMode.AUTONOMOUS,
                      "q-key-1",
                      "{}"));
      assertTrue(blocking.entered.await(15, TimeUnit.SECONDS), "the only tool thread is busy");

      Future<AgentData.Run> second =
          pool.submit(
              () ->
                  engine.createAndStart(
                      tenantId,
                      userId,
                      "test.q-count",
                      "obj",
                      RunMode.AUTONOMOUS,
                      "q-key-2",
                      "{}"));
      UUID run2Id = runIdOf(tenantId, userId, "test.q-count");
      awaitTaskRunning(tenantId, userId, run2Id);

      // The claim is lost while the invocation is still queued (its lease lapses; the next
      // renewal reports the loss and the engine cancels the queued run before it can start).
      admin.update(
          "UPDATE public.agent_tasks SET claim_expires_at = now() - interval '1 minute'"
              + " WHERE run_id = ?",
          run2Id);
      Thread.sleep(3000);

      blocking.release.countDown();
      assertEquals("SUCCEEDED", first.get(15, TimeUnit.SECONDS).state());
      assertEquals("FAILED", second.get(15, TimeUnit.SECONDS).state());
      assertEquals(
          0, counting.entries.get(), "a claim lost while queued must never invoke the tool");
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Finding 9 (interruption while queued): the engine thread executing the second run is
   * interrupted while its invocation waits in the queue. The queued run is cancelled before start,
   * the tool is never invoked, and the caller's interrupt status is restored afterwards.
   */
  @Test
  void interruptionWhileQueuedSuppressesTheInvocation() throws Exception {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.q-block");
    register(tenantId, userId, "test.q-count");
    IgnoringTool blocking = new IgnoringTool();
    CountingTool counting = new CountingTool();
    // Long lease: no renewal traffic interferes with the interrupt-status assertion.
    AgentEngine engine = queuedEngine(blocking, counting, 30, 25);

    ExecutorService pool = Executors.newFixedThreadPool(1);
    try {
      Future<AgentData.Run> first =
          pool.submit(
              () ->
                  engine.createAndStart(
                      tenantId,
                      userId,
                      "test.q-block",
                      "obj",
                      RunMode.AUTONOMOUS,
                      "q-key-1",
                      "{}"));
      assertTrue(blocking.entered.await(15, TimeUnit.SECONDS), "the only tool thread is busy");

      AtomicReference<AgentData.Run> result = new AtomicReference<>();
      AtomicBoolean restored = new AtomicBoolean();
      CountDownLatch done = new CountDownLatch(1);
      Thread secondThread =
          new Thread(
              () -> {
                try {
                  result.set(
                      engine.createAndStart(
                          tenantId,
                          userId,
                          "test.q-count",
                          "obj",
                          RunMode.AUTONOMOUS,
                          "q-key-2",
                          "{}"));
                } finally {
                  restored.set(Thread.currentThread().isInterrupted());
                  done.countDown();
                }
              });
      secondThread.start();
      UUID run2Id = runIdOf(tenantId, userId, "test.q-count");
      awaitTaskRunning(tenantId, userId, run2Id);
      secondThread.interrupt();
      Thread.sleep(1000); // the engine thread absorbs the interrupt and cancels the queued run

      blocking.release.countDown();
      assertTrue(done.await(15, TimeUnit.SECONDS));
      assertEquals("SUCCEEDED", first.get(15, TimeUnit.SECONDS).state());
      assertEquals("FAILED", result.get().state());
      assertEquals(
          0, counting.entries.get(), "an interruption while queued must never invoke the tool");
      assertTrue(restored.get(), "the caller's interrupt status is restored afterwards");
    } finally {
      pool.shutdownNow();
    }
  }

  /** ToolRun unit: cancellation requested before start — the tool is never invoked. */
  @Test
  void toolRunCancelledBeforeStartNeverExecutes() {
    CountingTool tool = new CountingTool();
    ToolRun run =
        new ToolRun(
            tool, new ToolInvocation(UUID.randomUUID(), UUID.randomUUID(), "run:1", 1), "{}");
    run.requestInterrupt();
    assertThrows(ToolRun.NotStartedException.class, run::call);
    assertEquals(0, tool.entries.get());
  }

  /** ToolRun unit: once started, the interruption reaches the actual runner thread. */
  @Test
  void toolRunInterruptReachesTheActualRunner() throws Exception {
    LatchTool tool = new LatchTool();
    ToolRun run =
        new ToolRun(
            tool, new ToolInvocation(UUID.randomUUID(), UUID.randomUUID(), "run:1", 1), "{}");
    Thread thread =
        new Thread(
            () -> {
              try {
                run.call();
              } catch (RuntimeException expected) {
                // The tool surfaces the interruption as a failure; delivery is what matters.
              }
            });
    thread.start();
    assertTrue(tool.entered.await(15, TimeUnit.SECONDS), "the tool started");
    run.requestInterrupt();
    thread.join(TimeUnit.SECONDS.toMillis(10));
    assertFalse(thread.isAlive(), "the runner thread must receive the interruption");
  }

  /**
   * ToolRun unit: the startup/interruption race, hammered. Every iteration must end in exactly one
   * of two states — cancelled before start (tool never invoked) or started (invoked once) — never
   * executed-without-being-started, never stuck.
   */
  @Test
  void toolRunStartupRaceKeepsExactlyOneOutcome() throws Exception {
    for (int i = 0; i < 100; i++) {
      CountingTool tool = new CountingTool();
      ToolRun run =
          new ToolRun(
              tool, new ToolInvocation(UUID.randomUUID(), UUID.randomUUID(), "run:1", 1), "{}");
      CountDownLatch gate = new CountDownLatch(1);
      AtomicReference<Throwable> callError = new AtomicReference<>();
      Thread caller =
          new Thread(
              () -> {
                try {
                  gate.await();
                  run.call();
                } catch (Throwable t) {
                  callError.set(t);
                }
              });
      Thread interrupter =
          new Thread(
              () -> {
                try {
                  gate.await();
                } catch (InterruptedException e) {
                  return;
                }
                run.requestInterrupt();
              });
      caller.start();
      interrupter.start();
      gate.countDown();
      caller.join(TimeUnit.SECONDS.toMillis(10));
      interrupter.join(TimeUnit.SECONDS.toMillis(10));
      assertFalse(caller.isAlive(), "iteration " + i + ": call() stuck");
      if (tool.entries.get() == 0) {
        assertTrue(
            callError.get() instanceof ToolRun.NotStartedException,
            "iteration "
                + i
                + ": not invoked ⇒ must be cancelled-before-start, got "
                + callError.get());
      } else {
        assertEquals(1, tool.entries.get(), "iteration " + i + ": invoked at most once");
      }
    }
  }

  /**
   * The heartbeat scheduler is parallel: one heartbeat stuck inside a blocked renewal must not
   * stall the renewals of every other active task. (On the old single-thread scheduler this test
   * fails: B never gets a tick while A is blocked.)
   */
  @Test
  void oneBlockedHeartbeatDoesNotStallTheOthers() throws Exception {
    ScheduledExecutorService scheduler = AgentEngine.newHeartbeatScheduler();
    try {
      CountDownLatch aEntered = new CountDownLatch(1);
      CountDownLatch releaseA = new CountDownLatch(1);
      TaskClaimHeartbeat.Renewer blockedRenewer =
          () -> {
            aEntered.countDown();
            try {
              releaseA.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return true;
          };
      AtomicInteger bCalls = new AtomicInteger();
      CountDownLatch bThirdCall = new CountDownLatch(1);
      TaskClaimHeartbeat.Renewer countingRenewer =
          () -> {
            if (bCalls.incrementAndGet() >= 3) {
              bThirdCall.countDown();
            }
            return true;
          };
      try (TaskClaimHeartbeat a = TaskClaimHeartbeat.start(scheduler, blockedRenewer, 1);
          TaskClaimHeartbeat b = TaskClaimHeartbeat.start(scheduler, countingRenewer, 1)) {
        assertTrue(
            aEntered.await(15, TimeUnit.SECONDS), "heartbeat A is inside its blocked renewal");
        assertTrue(
            bThirdCall.await(15, TimeUnit.SECONDS),
            "heartbeat B kept renewing while A was blocked");
        releaseA.countDown();
      }
    } finally {
      scheduler.shutdownNow();
    }
  }

  /**
   * A renewal statement blocked on a row lock must be freed by its JDBC query timeout (lease 3 ⇒ 1
   * second) as a transient error — not hang until the lock goes away.
   */
  @Test
  void renewalIsBoundedByAQueryTimeout() throws Exception {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.lock");
    UUID runId =
        repository
            .createRunWithPlan(
                tenantId,
                userId,
                "test.lock",
                "obj",
                "AUTONOMOUS",
                null,
                "{}",
                List.of(new PlanTask("test.count", "READ", "{}")))
            .runId();
    AgentTaskRow task = repository.listTasks(tenantId, userId, runId).get(0);
    ClaimOutcome claim = repository.claimTask(tenantId, userId, runId, task.id(), "exec-1", 30);
    assertTrue(claim.claimed());

    Connection lock = admin.getDataSource().getConnection();
    try {
      lock.setAutoCommit(false);
      try (Statement statement = lock.createStatement()) {
        statement.execute(
            "UPDATE public.agent_tasks SET updated_at = now() WHERE id = '" + task.id() + "'");
      }
      long startedNanos = System.nanoTime();
      assertThrows(
          DataAccessException.class,
          () ->
              repository.renewTaskClaim(tenantId, userId, task.id(), "exec-1", claim.attempt(), 3));
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
      assertTrue(
          elapsedMs < 15_000, "the query timeout must free the renewal, took " + elapsedMs + "ms");
      assertTrue(
          elapsedMs >= 800, "the renewal should wait for its timeout, took " + elapsedMs + "ms");
    } finally {
      lock.rollback();
      lock.close();
    }
    assertTrue(
        repository.renewTaskClaim(tenantId, userId, task.id(), "exec-1", claim.attempt(), 30));
  }
}
