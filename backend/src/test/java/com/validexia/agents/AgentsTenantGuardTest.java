package com.validexia.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.support.TestDatabase;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Fail-closed tenant guards (V6). Every agent function must reject execution unless the
 * transaction's {@code app.tenant_id} is exactly the tenant the function is asked to act on:
 * missing context, empty context and a different tenant's context are all rejected, for read
 * functions as well as mutations. Also covers cross-tenant reach (a valid context for tenant B
 * aimed at tenant A's data) and the FORCE ROW LEVEL SECURITY behaviour of the owner role.
 *
 * <p>All calls run as the restricted application role on pinned single connections, because the
 * guard reads the session's tenant setting.
 */
class AgentsTenantGuardTest {

  private final JdbcTemplate admin = TestDatabase.adminJdbc();

  private record Fixture(
      UUID tenantA,
      UUID tenantB,
      UUID userId,
      UUID definitionId,
      UUID runId,
      UUID taskId,
      UUID approvalId) {}

  /** Opens a pinned app-role session; the caller must {@link #close} it. */
  private JdbcTemplate appSession() throws Exception {
    Connection connection = TestDatabase.appDataSource().getConnection();
    return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
  }

  private void close(JdbcTemplate session) {
    ((SingleConnectionDataSource) session.getDataSource()).destroy();
  }

  private void setContext(JdbcTemplate session, String tenantId) {
    session.queryForObject("SELECT set_config('app.tenant_id', ?, false)", String.class, tenantId);
  }

  /** Executes a function call purely to observe whether it is rejected. */
  private void probe(JdbcTemplate session, String sql, Object... args) {
    session.query(sql, (RowCallbackHandler) rs -> {}, args);
  }

  private void assertRejected(JdbcTemplate session, String sql, Object... args) {
    DataAccessException error =
        assertThrows(DataAccessException.class, () -> probe(session, sql, args));
    assertTrue(
        error.getMessage() != null && error.getMessage().contains("tenant context mismatch"),
        "expected a tenant-guard rejection but got: " + error.getMessage());
  }

  /**
   * Builds tenant A's fixture data through the functions themselves, under tenant A's context: one
   * definition, one run, one PENDING task, and one task parked with a PENDING approval. Tenant B
   * gets a bare tenant row only.
   */
  private Fixture buildFixture(JdbcTemplate session) {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'A', ?)", tenantA, "a-" + tenantA);
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'B', ?)", tenantB, "b-" + tenantB);
    admin.update(
        "INSERT INTO public.users (id, external_subject) VALUES (?, ?)", userId, "sub-" + userId);

    setContext(session, tenantA.toString());
    UUID definitionId =
        session.queryForObject(
            "SELECT public.register_agent_definition(?, 'foundation.echo', 'Echo', '',"
                + " 'DETERMINISTIC', '{}'::jsonb, ?)",
            UUID.class,
            tenantA,
            userId);
    UUID runId =
        session.queryForObject(
            "SELECT run_id FROM public.create_agent_run(?, 'foundation.echo', 'objective',"
                + " 'APPROVAL', ?, 'guard-fixture', '{}'::jsonb)",
            UUID.class,
            tenantA,
            userId);
    UUID taskId =
        session.queryForObject(
            "SELECT public.add_agent_task(?, ?, 1, 'foundation.read_context', 'READ',"
                + " '{}'::jsonb, 'guard-task-1')",
            UUID.class,
            tenantA,
            runId);
    UUID parkedTaskId =
        session.queryForObject(
            "SELECT public.add_agent_task(?, ?, 2, 'foundation.sensitive_action',"
                + " 'SENSITIVE_WRITE', '{}'::jsonb, 'guard-task-2')",
            UUID.class,
            tenantA,
            runId);
    UUID approvalId =
        session.queryForObject(
            "SELECT public.request_agent_approval(?, ?, 'guard fixture')",
            UUID.class,
            tenantA,
            parkedTaskId);
    return new Fixture(tenantA, tenantB, userId, definitionId, runId, taskId, approvalId);
  }

  @Test
  void everyFunctionRejectsMissingEmptyAndMismatchedContext() throws Exception {
    JdbcTemplate session = appSession();
    JdbcTemplate untouched = appSession(); // never given a tenant context: the "missing" case
    try {
      Fixture f = buildFixture(session);
      UUID a = f.tenantA();

      // One representative call per function, always aimed at tenant A with valid arguments.
      List<String> calls =
          List.of(
              "SELECT * FROM public.register_agent_definition(?, 'guard.extra', 'Extra', '',"
                  + " 'DETERMINISTIC', '{}'::jsonb, ?)",
              "SELECT * FROM public.list_agent_definitions(?)",
              "SELECT * FROM public.create_agent_run(?, 'foundation.echo', 'o', 'APPROVAL', ?,"
                  + " 'guard-extra-run', '{}'::jsonb)",
              "SELECT * FROM public.get_agent_run(?, ?)",
              "SELECT * FROM public.list_agent_runs(?)",
              "SELECT * FROM public.transition_agent_run(?, ?, 'PENDING', 'RUNNING', NULL, NULL)",
              "SELECT * FROM public.add_agent_task(?, ?, 9, 'foundation.read_context', 'READ',"
                  + " '{}'::jsonb, 'guard-task-9')",
              "SELECT * FROM public.list_agent_tasks(?, ?)",
              "SELECT * FROM public.transition_agent_task(?, ?, 'PENDING', 'RUNNING', NULL,"
                  + " NULL)",
              "SELECT * FROM public.request_agent_approval(?, ?, 'r')",
              "SELECT * FROM public.decide_agent_approval(?, ?, 'APPROVED', ?, NULL)",
              "SELECT * FROM public.list_agent_approvals(?, NULL)");
      List<Object[]> arguments =
          List.of(
              new Object[] {a, f.userId()},
              new Object[] {a},
              new Object[] {a, f.userId()},
              new Object[] {a, f.runId()},
              new Object[] {a},
              new Object[] {a, f.runId()},
              new Object[] {a, f.runId()},
              new Object[] {a, f.runId()},
              new Object[] {a, f.taskId()},
              new Object[] {a, f.taskId()},
              new Object[] {a, f.approvalId(), f.userId()},
              new Object[] {a});

      for (int i = 0; i < calls.size(); i++) {
        String sql = calls.get(i);
        Object[] args = arguments.get(i);
        assertRejected(untouched, sql, args); // missing context
        setContext(session, "");
        assertRejected(session, sql, args); // empty context
        setContext(session, f.tenantB().toString());
        assertRejected(session, sql, args); // another tenant's context
      }

      // And with tenant A's exact context, every function works.
      setContext(session, a.toString());
      UUID extraDefinition =
          session.queryForObject(
              "SELECT public.register_agent_definition(?, 'guard.extra', 'Extra', '',"
                  + " 'DETERMINISTIC', '{}'::jsonb, ?)",
              UUID.class,
              a,
              f.userId());
      assertTrue(extraDefinition != null);
      assertEquals(
          2, session.queryForList("SELECT * FROM public.list_agent_definitions(?)", a).size());
      UUID extraRun =
          session.queryForObject(
              "SELECT run_id FROM public.create_agent_run(?, 'foundation.echo', 'o', 'APPROVAL',"
                  + " ?, 'guard-extra-run', '{}'::jsonb)",
              UUID.class,
              a,
              f.userId());
      assertTrue(extraRun != null);
      assertEquals(
          f.runId(),
          session.queryForObject(
              "SELECT id FROM public.get_agent_run(?, ?)", UUID.class, a, f.runId()));
      assertFalse(session.queryForList("SELECT * FROM public.list_agent_runs(?)", a).isEmpty());
      assertEquals(
          Boolean.TRUE,
          session.queryForObject(
              "SELECT public.transition_agent_run(?, ?, 'PENDING', 'RUNNING', NULL, NULL)",
              Boolean.class,
              a,
              extraRun));
      UUID extraTask =
          session.queryForObject(
              "SELECT public.add_agent_task(?, ?, 9, 'foundation.read_context', 'READ',"
                  + " '{}'::jsonb, 'guard-task-9')",
              UUID.class,
              a,
              f.runId());
      assertTrue(extraTask != null);
      assertFalse(
          session
              .queryForList("SELECT * FROM public.list_agent_tasks(?, ?)", a, f.runId())
              .isEmpty());
      assertEquals(
          Boolean.TRUE,
          session.queryForObject(
              "SELECT public.transition_agent_task(?, ?, 'PENDING', 'RUNNING', NULL, NULL)",
              Boolean.class,
              a,
              f.taskId()));
      UUID extraApproval =
          session.queryForObject(
              "SELECT public.request_agent_approval(?, ?, 'r')", UUID.class, a, extraTask);
      assertTrue(extraApproval != null);
      assertFalse(
          session.queryForList("SELECT * FROM public.list_agent_approvals(?, NULL)", a).isEmpty());
      assertEquals(
          Boolean.TRUE,
          session.queryForObject(
              "SELECT public.decide_agent_approval(?, ?, 'APPROVED', ?, NULL)",
              Boolean.class,
              a,
              extraApproval,
              f.userId()));
    } finally {
      close(session);
      close(untouched);
    }
  }

  @Test
  void tenantBCannotReachTenantADataWithItsOwnValidContext() throws Exception {
    JdbcTemplate session = appSession();
    try {
      Fixture f = buildFixture(session);
      setContext(session, f.tenantB().toString());
      UUID b = f.tenantB();

      // Reads aimed at A return nothing.
      assertTrue(
          session.queryForList("SELECT * FROM public.list_agent_definitions(?)", b).isEmpty());
      assertTrue(
          session.queryForList("SELECT * FROM public.get_agent_run(?, ?)", b, f.runId()).isEmpty());
      assertTrue(session.queryForList("SELECT * FROM public.list_agent_runs(?)", b).isEmpty());
      assertTrue(
          session
              .queryForList("SELECT * FROM public.list_agent_tasks(?, ?)", b, f.runId())
              .isEmpty());
      assertTrue(
          session.queryForList("SELECT * FROM public.list_agent_approvals(?, NULL)", b).isEmpty());

      // Mutations aimed at A's rows do not land.
      assertEquals(
          Boolean.FALSE,
          session.queryForObject(
              "SELECT public.transition_agent_run(?, ?, 'PENDING', 'RUNNING', NULL, NULL)",
              Boolean.class,
              b,
              f.runId()));
      assertEquals(
          Boolean.FALSE,
          session.queryForObject(
              "SELECT public.transition_agent_task(?, ?, 'PENDING', 'RUNNING', NULL, NULL)",
              Boolean.class,
              b,
              f.taskId()));
      assertThrows(
          DataAccessException.class,
          () ->
              session.queryForObject(
                  "SELECT public.add_agent_task(?, ?, 5, 'foundation.read_context', 'READ',"
                      + " '{}'::jsonb, 'intruder')",
                  UUID.class,
                  b,
                  f.runId()));
      assertThrows(
          DataAccessException.class,
          () ->
              session.queryForObject(
                  "SELECT public.request_agent_approval(?, ?, 'intruder')",
                  UUID.class,
                  b,
                  f.taskId()));
      assertEquals(
          Boolean.FALSE,
          session.queryForObject(
              "SELECT public.decide_agent_approval(?, ?, 'APPROVED', ?, NULL)",
              Boolean.class,
              b,
              f.approvalId(),
              f.userId()));

      // Tenant A's data is untouched: run still PENDING, approval still PENDING.
      setContext(session, f.tenantA().toString());
      Map<String, Object> run =
          session.queryForMap("SELECT * FROM public.get_agent_run(?, ?)", f.tenantA(), f.runId());
      assertEquals("PENDING", run.get("state"));
      List<Map<String, Object>> approvals =
          session.queryForList(
              "SELECT * FROM public.list_agent_approvals(?, 'PENDING')", f.tenantA());
      assertEquals(1, approvals.size());
    } finally {
      close(session);
    }
  }

  @Test
  void appRoleCannotAssumeTheOwnerRole() throws Exception {
    JdbcTemplate session = appSession();
    try {
      // The whole boundary rests on the app role reaching data only through the definer
      // functions. Assuming the owner role directly must be impossible.
      assertThrows(
          DataAccessException.class, () -> session.execute("SET ROLE validexia_agents_owner"));
      assertEquals(
          Boolean.FALSE,
          session.queryForObject(
              "SELECT pg_has_role('validexia_app', 'validexia_agents_owner', 'MEMBER')",
              Boolean.class));
    } finally {
      close(session);
    }
  }

  @Test
  void forceRlsBindsTheOwnerRole() throws Exception {
    JdbcTemplate app = appSession();
    JdbcTemplate owner = ownerSession();
    try {
      Fixture f = buildFixture(app);

      for (String table :
          List.of("agent_definitions", "agent_runs", "agent_tasks", "agent_approvals")) {
        assertEquals(
            Boolean.TRUE,
            admin.queryForObject(
                "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid ="
                    + " ('public."
                    + table
                    + "')::regclass",
                Boolean.class),
            table + " must have RLS enabled and forced");
      }

      owner.execute("SET ROLE validexia_agents_owner");
      try {
        // No tenant context: the owner sees nothing at all.
        assertEquals(
            0,
            owner
                .queryForObject("SELECT count(*) FROM public.agent_runs", Integer.class)
                .intValue());
        // Tenant A's context: the owner sees A's rows only.
        setContext(owner, f.tenantA().toString());
        assertEquals(
            1,
            owner
                .queryForObject(
                    "SELECT count(*) FROM public.agent_runs WHERE tenant_id = ?",
                    Integer.class,
                    f.tenantA())
                .intValue());
        assertEquals(
            0,
            owner
                .queryForObject(
                    "SELECT count(*) FROM public.agent_runs WHERE tenant_id = ?",
                    Integer.class,
                    f.tenantB())
                .intValue());
        // Writing another tenant's row under A's context violates the policy check.
        assertThrows(
            DataAccessException.class,
            () ->
                owner.update(
                    "INSERT INTO public.agent_runs (tenant_id, agent_id, objective, mode,"
                        + " initiated_by) VALUES (?, ?, 'x', 'APPROVAL', ?)",
                    f.tenantB(),
                    f.definitionId(),
                    f.userId()));
      } finally {
        owner.execute("RESET ROLE");
      }
    } finally {
      close(app);
      close(owner);
    }
  }

  /** A pinned connection as the test admin (superuser), for SET ROLE owner checks. */
  private JdbcTemplate ownerSession() throws Exception {
    javax.sql.DataSource adminDataSource =
        (javax.sql.DataSource) TestDatabase.adminJdbc().getDataSource();
    Connection connection = adminDataSource.getConnection();
    return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
  }
}
