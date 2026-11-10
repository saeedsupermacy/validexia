package com.validexia.identity.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.support.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Static configuration assertions (M02-1 v2.6 §1/§4): role attributes in the catalog, definer
 * functions' pinned search_path, and the application role's exact privilege surface.
 */
class RlsConfigurationTest {

  private final JdbcTemplate admin = TestDatabase.adminJdbc();

  @Test
  void applicationRoleCannotBypassRls() {
    Boolean ok =
        admin.queryForObject(
            "SELECT rolsuper = false AND rolbypassrls = false AND rolcanlogin = true"
                + " FROM pg_roles WHERE rolname = 'validexia_app'",
            Boolean.class);
    assertTrue(ok);
    Boolean ownersLockedDown =
        admin.queryForObject(
            "SELECT count(*) = 2 FROM pg_roles WHERE rolname IN"
                + " ('validexia_directory_owner', 'validexia_audit_owner')"
                + " AND rolsuper = false AND rolbypassrls = false AND rolcanlogin = false",
            Boolean.class);
    assertTrue(ownersLockedDown);
  }

  @Test
  void everyDefinerFunctionHasThePinnedSearchPath() {
    Integer offenders =
        admin.queryForObject(
            "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE n.nspname = 'public' AND p.prosecdef = true"
                + " AND NOT EXISTS (SELECT 1 FROM unnest(coalesce(p.proconfig, ARRAY[]::text[])) c"
                + "   WHERE c = 'search_path=pg_catalog, pg_temp')",
            Integer.class);
    assertEquals(0, offenders);
  }

  @Test
  void applicationRoleHasNoDirectTableGrantsOnDirectoryOrSecurityTables() {
    for (String table :
        new String[] {
          "users", "tenants", "memberships", "revoked_sessions", "webhook_events",
          "audit_chain_heads"
        }) {
      Boolean any =
          admin.queryForObject(
              "SELECT has_table_privilege('validexia_app', 'public.' || ?, 'SELECT,INSERT,UPDATE,DELETE')",
              Boolean.class,
              table);
      assertFalse(any, "validexia_app must have no DML privilege on " + table);
    }
  }

  @Test
  void applicationRoleAuditSurfaceIsExactlyInsertSelectAndVerify() {
    assertTrue(
        admin.queryForObject(
            "SELECT has_table_privilege('validexia_app', 'public.audit_events', 'INSERT')",
            Boolean.class));
    assertTrue(
        admin.queryForObject(
            "SELECT has_table_privilege('validexia_app', 'public.audit_events', 'SELECT')",
            Boolean.class));
    assertFalse(
        admin.queryForObject(
            "SELECT has_table_privilege('validexia_app', 'public.audit_events', 'UPDATE,DELETE')",
            Boolean.class));
    assertTrue(
        admin.queryForObject(
            "SELECT has_function_privilege('validexia_app', 'public.audit_verify_chain(text)', 'EXECUTE')",
            Boolean.class));
    assertFalse(
        admin.queryForObject(
            "SELECT has_function_privilege('validexia_app', 'public.audit_events_chain_trigger()', 'EXECUTE')",
            Boolean.class));
  }

  @Test
  void auditEventsHasRlsEnabledAndForced() {
    Boolean ok =
        admin.queryForObject(
            "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'public.audit_events'::regclass",
            Boolean.class);
    assertTrue(ok);
  }
}
