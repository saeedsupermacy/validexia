package com.validexia.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.support.TestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Migration V5's security shape, asserted as the restricted application role: the agent tables
 * exist with RLS on, the app role has no direct access to them at all, and every operation goes
 * through the definer functions.
 */
class AgentsSchemaTest {

  private final JdbcTemplate app = TestDatabase.appJdbc();
  private final JdbcTemplate admin = TestDatabase.adminJdbc();

  @Test
  void agentTablesExistWithRlsEnabled() {
    Integer count =
        admin.queryForObject(
            "SELECT count(*) FROM pg_class WHERE relname IN ('agent_definitions', 'agent_runs',"
                + " 'agent_tasks', 'agent_approvals') AND relrowsecurity = true",
            Integer.class);
    assertEquals(4, count);
  }

  @Test
  void appRoleHasNoDirectTableAccess() {
    assertThrows(
        DataAccessException.class,
        () -> app.queryForObject("SELECT count(*) FROM public.agent_runs", Integer.class));
    assertThrows(
        DataAccessException.class,
        () -> app.update("INSERT INTO public.agent_runs (tenant_id) VALUES (NULL)"));
  }

  @Test
  void agentTablesAreOwnedByTheAgentsOwner() {
    Integer count =
        admin.queryForObject(
            "SELECT count(*) FROM pg_tables WHERE tablename LIKE 'agent_%'"
                + " AND tableowner = 'validexia_agents_owner'",
            Integer.class);
    assertEquals(4, count);
  }

  @Test
  void functionsAreCallableAndTenantGuarded() throws Exception {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'A', ?)", tenantA, "a-" + tenantA);
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'B', ?)", tenantB, "b-" + tenantB);
    admin.update(
        "INSERT INTO public.users (id, external_subject) VALUES (?, ?)", user, "sub-" + user);

    // One shared connection: the tenant guard reads the session's app.tenant_id setting.
    JdbcTemplate session =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                TestDatabase.appDataSource().getConnection(), true));
    try {
      session.update("SELECT set_config('app.tenant_id', ?, false)", tenantA.toString());
      UUID definitionId =
          session.queryForObject(
              "SELECT public.register_agent_definition(?, 'foundation.echo', 'Echo', '',"
                  + " 'DETERMINISTIC', '{}'::jsonb, ?)",
              UUID.class,
              tenantA,
              user);
      assertTrue(definitionId != null);

      // The same call for tenant B while the session claims tenant A must fail closed.
      assertThrows(
          DataAccessException.class,
          () ->
              session.queryForObject(
                  "SELECT public.register_agent_definition(?, 'foundation.echo', 'Echo', '',"
                      + " 'DETERMINISTIC', '{}'::jsonb, ?)",
                  UUID.class,
                  tenantB,
                  user));
    } finally {
      ((org.springframework.jdbc.datasource.SingleConnectionDataSource) session.getDataSource())
          .destroy();
    }
  }
}
