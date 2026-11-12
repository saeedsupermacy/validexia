package com.validexia.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.agents.application.AgentData;
import com.validexia.agents.application.AgentEngine;
import com.validexia.agents.application.AgentPlanner;
import com.validexia.agents.application.AgentRegistry;
import com.validexia.agents.application.ToolHandler;
import com.validexia.agents.application.ToolInvocation;
import com.validexia.agents.application.ToolRegistry;
import com.validexia.agents.domain.RiskClass;
import com.validexia.agents.domain.RunMode;
import com.validexia.agents.infrastructure.AgentsRepository;
import com.validexia.agents.infrastructure.AgentsRepository.AgentTaskRow;
import com.validexia.agents.infrastructure.AgentsRepository.ClaimOutcome;
import com.validexia.agents.infrastructure.AgentsRepository.PlanTask;
import com.validexia.audit.AuditService;
import com.validexia.support.TestClerkConfig;
import com.validexia.support.TestDatabase;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Concurrency safety of the execution engine (migration V7): task leases with fencing,
 * claim-before-park approval coordination, CAS-honest audit events, convergent idempotent run
 * creation, and stable tool idempotency keys across takeovers. All interleavings are driven by
 * latches, never sleeps.
 */
@SpringBootTest(
    properties = {
      "validexia.clerk.issuer=https://clerk.test.example",
      "validexia.clerk.jwks-url=https://clerk.test.example/.well-known/jwks.json",
      "validexia.clerk.authorized-parties=https://app.validexia.test"
    })
@Import(TestClerkConfig.class)
class AgentConcurrencyTest {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.springProperties().forEach((key, value) -> registry.add(key, () -> value));
  }

  @Autowired AgentsRepository repository;
  @Autowired AuditService audit;

  private final JdbcTemplate admin = TestDatabase.adminJdbc();

  // -- Test doubles ---------------------------------------------------------------

  /** A READ tool whose first invocation blocks until the test releases it. */
  static final class BlockingTool implements ToolHandler {
    final AtomicInteger entries = new AtomicInteger();
    final CountDownLatch firstEntered = new CountDownLatch(1);
    final CountDownLatch releaseFirst = new CountDownLatch(1);

    @Override
    public String key() {
      return "test.blocking";
    }

    @Override
    public RiskClass riskClass() {
      return RiskClass.READ;
    }

    @Override
    public String execute(String inputJson) {
      int entry = entries.incrementAndGet();
      if (entry == 1) {
        firstEntered.countDown();
        try {
          if (!releaseFirst.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("test latch was never released");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
      return "{\"entry\":" + entry + "}";
    }
  }

  /** A no-op READ tool for creation races. */
  static final class NoopTool implements ToolHandler {
    @Override
    public String key() {
      return "test.noop";
    }

    @Override
    public RiskClass riskClass() {
      return RiskClass.READ;
    }

    @Override
    public String execute(String inputJson) {
      return "{}";
    }
  }

  /** A sensitive tool that must only ever be parked, never executed, in the race test. */
  static final class SensitiveTool implements ToolHandler {
    final AtomicInteger entries = new AtomicInteger();

    @Override
    public String key() {
      return "test.sensitive";
    }

    @Override
    public RiskClass riskClass() {
      return RiskClass.SENSITIVE_WRITE;
    }

    @Override
    public String execute(String inputJson) {
      entries.incrementAndGet();
      return "{}";
    }
  }

  /**
   * Simulates a provider that deduplicates by idempotency key: the external effect happens at most
   * once per key, however many times the tool is invoked with it.
   */
  static final class DedupTool implements ToolHandler {
    final ConcurrentHashMap<String, String> effects = new ConcurrentHashMap<>();
    final List<ToolInvocation> invocations = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger effectCount = new AtomicInteger();

    @Override
    public String key() {
      return "test.dedup";
    }

    @Override
    public RiskClass riskClass() {
      return RiskClass.ROUTINE_WRITE;
    }

    @Override
    public String execute(ToolInvocation invocation, String inputJson) {
      invocations.add(invocation);
      return effects.computeIfAbsent(
          invocation.idempotencyKey(),
          key -> {
            effectCount.incrementAndGet();
            return "{\"effect\":true}";
          });
    }

    @Override
    public String execute(String inputJson) {
      throw new UnsupportedOperationException("this tool requires its invocation context");
    }
  }

  // -- Helpers ----------------------------------------------------------------------

  private AgentEngine engineFor(ToolHandler tool, String agentKey, String toolKey) {
    AgentPlanner planner =
        new AgentPlanner() {
          @Override
          public String agentKey() {
            return agentKey;
          }

          @Override
          public List<PlannedTask> plan(String objective, String contextJson) {
            return List.of(new PlannedTask(toolKey, "{}"));
          }
        };
    return new AgentEngine(
        repository, new AgentRegistry(List.of(planner)), new ToolRegistry(List.of(tool)), audit);
  }

  /** Creates a bare tenant and user; returns [tenantId, userId]. */
  private UUID[] tenantAndUser() {
    UUID tenantId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'Concurrency', ?)",
        tenantId,
        "c-" + tenantId);
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

  private int auditCountByAction(UUID tenantId, String action) {
    return admin.queryForObject(
        "SELECT count(*) FROM public.audit_events WHERE tenant_key = ? AND action = ?",
        Integer.class,
        tenantId.toString(),
        action);
  }

  // -- Tests ------------------------------------------------------------------------

  @Test
  void concurrentExecutorsShareWorkWithoutDuplicating() throws Exception {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.concurrent");
    BlockingTool tool = new BlockingTool();
    AgentEngine engine = engineFor(tool, "test.concurrent", "test.blocking");

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<AgentData.Run> first =
          pool.submit(
              () ->
                  engine.createAndStart(
                      tenantId, userId, "test.concurrent", "obj", RunMode.AUTONOMOUS, "k-1", "{}"));
      assertTrue(tool.firstEntered.await(15, TimeUnit.SECONDS), "first executor entered the tool");
      UUID runId = repository.listRuns(tenantId, userId).get(0).id();
      UUID taskId = repository.listTasks(tenantId, userId, runId).get(0).id();

      // A second executor touching the same run must not execute the task again: the lease
      // is live, so it reports the run as RUNNING and leaves the work to the owner.
      AgentData.Run second =
          pool.submit(() -> engine.execute(tenantId, userId, runId)).get(15, TimeUnit.SECONDS);
      assertEquals("RUNNING", second.state());
      assertEquals(1, tool.entries.get());

      tool.releaseFirst.countDown();
      AgentData.Run finished = first.get(15, TimeUnit.SECONDS);
      assertEquals("SUCCEEDED", finished.state());

      // Re-executing a finished run appends nothing: completion events exist exactly once.
      engine.execute(tenantId, userId, runId);
      assertEquals(1, auditCount(tenantId, "AGENT_TASK_EXECUTED", taskId));
      assertEquals(1, auditCount(tenantId, "AGENT_RUN_SUCCEEDED", runId));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void leaseExpiryTakeoverFencesTheStaleExecutor() throws Exception {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.lease");
    BlockingTool tool = new BlockingTool();
    AgentEngine engine = engineFor(tool, "test.lease", "test.blocking");

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<AgentData.Run> first =
          pool.submit(
              () ->
                  engine.createAndStart(
                      tenantId, userId, "test.lease", "obj", RunMode.AUTONOMOUS, "k-lease", "{}"));
      assertTrue(tool.firstEntered.await(15, TimeUnit.SECONDS));
      UUID runId = repository.listRuns(tenantId, userId).get(0).id();
      UUID taskId = repository.listTasks(tenantId, userId, runId).get(0).id();

      // The first executor is presumed dead: expire its lease.
      admin.update(
          "UPDATE public.agent_tasks SET claim_expires_at = now() - interval '1 minute'"
              + " WHERE id = ?",
          taskId);

      // The takeover executor re-executes the task (at-least-once) and commits its result.
      AgentData.Run takeover =
          pool.submit(() -> engine.execute(tenantId, userId, runId)).get(15, TimeUnit.SECONDS);
      assertEquals("SUCCEEDED", takeover.state());
      assertEquals(2, tool.entries.get());
      AgentTaskRow task = repository.listTasks(tenantId, userId, runId).get(0);
      assertEquals("SUCCEEDED", task.state());
      assertTrue(
          task.outputJson() != null && task.outputJson().replace(" ", "").contains("\"entry\":2"),
          "the takeover's output must be the committed one, got: " + task.outputJson());

      // The stale executor finally returns: fencing rejects its completion, silently.
      tool.releaseFirst.countDown();
      AgentData.Run stale = first.get(15, TimeUnit.SECONDS);
      assertEquals("SUCCEEDED", stale.state());
      AgentTaskRow after = repository.listTasks(tenantId, userId, runId).get(0);
      assertTrue(
          after.outputJson() != null
              && after.outputJson().replace(" ", "").contains("\"entry\":2"));
      assertEquals(1, auditCount(tenantId, "AGENT_TASK_EXECUTED", taskId));
      assertEquals(1, auditCount(tenantId, "AGENT_RUN_SUCCEEDED", runId));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void approvalRaceParksOnceWithoutErrors() throws Exception {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.race");
    SensitiveTool tool = new SensitiveTool();
    AgentEngine engine = engineFor(tool, "test.race", "test.sensitive");

    UUID runId =
        repository
            .createRunWithPlan(
                tenantId,
                userId,
                "test.race",
                "obj",
                "APPROVAL",
                null,
                "{}",
                List.of(new PlanTask("test.sensitive", "SENSITIVE_WRITE", "{}")))
            .runId();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<AgentData.Run> a =
          pool.submit(
              () -> {
                start.await();
                return engine.execute(tenantId, userId, runId);
              });
      Future<AgentData.Run> b =
          pool.submit(
              () -> {
                start.await();
                return engine.execute(tenantId, userId, runId);
              });
      start.countDown();
      // Neither executor may fail. One parks the task; the other either observes the parked
      // state or loses the run-entry transition and reports RUNNING — both are correct
      // observations of the same interleaving. The settled state is what must be singular.
      String stateA = a.get(15, TimeUnit.SECONDS).state();
      String stateB = b.get(15, TimeUnit.SECONDS).state();
      assertTrue(
          stateA.equals("WAITING_APPROVAL") || stateA.equals("RUNNING"), "state A: " + stateA);
      assertTrue(
          stateB.equals("WAITING_APPROVAL") || stateB.equals("RUNNING"), "state B: " + stateB);
      assertEquals("WAITING_APPROVAL", engine.requireRun(tenantId, userId, runId).state());
    } finally {
      pool.shutdownNow();
    }
    assertEquals(0, tool.entries.get());
    assertEquals(1, repository.listApprovals(tenantId, userId, "PENDING").size());
    assertEquals(1, auditCountByAction(tenantId, "AGENT_APPROVAL_REQUESTED"));
  }

  @Test
  void concurrentCreateWithTheSameKeyConverges() throws Exception {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.create");
    AgentEngine engine = engineFor(new NoopTool(), "test.create", "test.noop");

    int creators = 8;
    ExecutorService pool = Executors.newFixedThreadPool(creators);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<AgentData.Run>> futures = new ArrayList<>();
      for (int i = 0; i < creators; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return engine.createAndStart(
                      tenantId,
                      userId,
                      "test.create",
                      "obj",
                      RunMode.AUTONOMOUS,
                      "shared-key",
                      "{}");
                }));
      }
      start.countDown();
      List<UUID> runIds = new ArrayList<>();
      for (Future<AgentData.Run> future : futures) {
        runIds.add(future.get(20, TimeUnit.SECONDS).id()); // no caller may error out
      }
      UUID runId = runIds.get(0);
      assertTrue(runIds.stream().allMatch(runId::equals), "all creators converge on one run");
      assertEquals(
          1,
          admin
              .queryForObject(
                  "SELECT count(*) FROM public.agent_runs WHERE tenant_id = ?"
                      + " AND idempotency_key = 'shared-key'",
                  Integer.class,
                  tenantId)
              .intValue());
      assertEquals(1, repository.listTasks(tenantId, userId, runId).size());
      assertEquals(1, auditCount(tenantId, "AGENT_RUN_CREATED", runId));
      assertEquals("SUCCEEDED", engine.execute(tenantId, userId, runId).state());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void ambiguousOutcomeIsDeduplicatedByTheStableKeyOnTakeover() throws Exception {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID userId = fixture[1];
    register(tenantId, userId, "test.ambiguous");
    DedupTool tool = new DedupTool();
    AgentEngine engine = engineFor(tool, "test.ambiguous", "test.dedup");

    UUID runId =
        repository
            .createRunWithPlan(
                tenantId,
                userId,
                "test.ambiguous",
                "obj",
                "AUTONOMOUS",
                null,
                "{}",
                List.of(new PlanTask("test.dedup", "ROUTINE_WRITE", "{}")))
            .runId();
    AgentTaskRow task = repository.listTasks(tenantId, userId, runId).get(0);
    UUID taskId = task.id();

    // Executor X claims the task and its provider call succeeds — then X dies before
    // recording completion. The outcome is ambiguous from the system's point of view.
    ClaimOutcome claim = repository.claimTask(tenantId, userId, runId, taskId, "executor-x", 30);
    assertTrue(claim.claimed());
    String firstOutput =
        tool.execute(
            new ToolInvocation(runId, taskId, task.idempotencyKey(), claim.attempt()), "{}");
    assertEquals(1, tool.effectCount.get());

    // X never completes. Its lease expires and the engine takes over.
    admin.update(
        "UPDATE public.agent_tasks SET claim_expires_at = now() - interval '1 minute'"
            + " WHERE id = ?",
        taskId);
    AgentData.Run result = engine.execute(tenantId, userId, runId);
    assertEquals("SUCCEEDED", result.state());

    // The tool was invoked twice, with the SAME idempotency key, and the provider-side
    // effect happened exactly once — the takeover deduplicated instead of repeating.
    assertEquals(2, tool.invocations.size());
    assertEquals(
        tool.invocations.get(0).idempotencyKey(), tool.invocations.get(1).idempotencyKey());
    assertEquals(firstOutput, tool.effects.get(task.idempotencyKey()));
    assertEquals(1, tool.effectCount.get());
    assertEquals(1, auditCount(tenantId, "AGENT_TASK_EXECUTED", taskId));
  }

  @Test
  void newLeaseFunctionsKeepTheFailClosedTenantGuard() throws Exception {
    UUID[] fixture = tenantAndUser();
    UUID tenantId = fixture[0];
    UUID otherTenant = UUID.randomUUID();
    Connection connection = TestDatabase.appDataSource().getConnection();
    JdbcTemplate app = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    try {
      app.queryForObject(
          "SELECT set_config('app.tenant_id', ?, false)", String.class, otherTenant.toString());
      // Context names a different tenant than the calls: every V7 function must reject.
      assertThrows(
          DataAccessException.class,
          () ->
              app.queryForObject(
                  "SELECT claimed FROM public.claim_agent_task(?, ?, ?, 'exec', 30)",
                  Boolean.class,
                  tenantId,
                  UUID.randomUUID(),
                  UUID.randomUUID()));
      assertThrows(
          DataAccessException.class,
          () ->
              app.queryForObject(
                  "SELECT public.complete_agent_task(?, ?, 'exec', 1, 'SUCCEEDED', NULL, NULL)",
                  Boolean.class,
                  tenantId,
                  UUID.randomUUID()));
      assertThrows(
          DataAccessException.class,
          () ->
              app.queryForObject(
                  "SELECT public.park_agent_task_for_approval(?, ?, 'exec', 1, 'r')",
                  UUID.class,
                  tenantId,
                  UUID.randomUUID()));
    } finally {
      ((SingleConnectionDataSource) app.getDataSource()).destroy();
    }
  }
}
