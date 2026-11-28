package com.validexia.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.support.TestDatabase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V16 schema shape: the three access tables exist under the access owner role with row-level
 * security enabled AND forced, the application role has no direct table access, every access
 * function is owned by the access owner role with the frozen ACL end state (owner plus application
 * EXECUTE, never PUBLIC), all 21 named CHECK constraints and all 4 indexes from the U1 freeze
 * exist, and the cross-module grants are SELECT-only to the owner role.
 */
class AccessSchemaTest {

  private final JdbcTemplate app = TestDatabase.appJdbc();
  private final JdbcTemplate admin = TestDatabase.adminJdbc();

  private static final String FUNCTION_NAMES =
      "'create_internal_system', 'update_internal_system', 'retire_internal_system',"
          + " 'restore_internal_system', 'get_internal_system', 'list_internal_systems',"
          + " 'create_access_grant', 'record_discovered_access_grant', 'confirm_access_grant',"
          + " 'reject_access_grant', 'suspend_access_grant', 'reinstate_access_grant',"
          + " 'revoke_access_grant', 'get_access_grant', 'list_access_grants',"
          + " 'link_access_grant_evidence', 'unlink_access_grant_evidence',"
          + " 'list_access_grant_evidence'";

  @Test
  void accessTablesExistWithRlsEnabledAndForced() {
    Integer count =
        admin.queryForObject(
            "SELECT count(*) FROM pg_class WHERE relname IN ('internal_systems', 'access_grants',"
                + " 'access_grant_evidence') AND relrowsecurity = true"
                + " AND relforcerowsecurity = true",
            Integer.class);
    assertEquals(3, count);
  }

  @Test
  void accessTablesAreOwnedByTheAccessOwner() {
    Integer count =
        admin.queryForObject(
            "SELECT count(*) FROM pg_tables WHERE tablename IN ('internal_systems',"
                + " 'access_grants', 'access_grant_evidence')"
                + " AND tableowner = 'validexia_access_owner'",
            Integer.class);
    assertEquals(3, count);
  }

  @Test
  void appRoleHasNoDirectTableAccess() {
    assertThrows(
        DataAccessException.class,
        () -> app.queryForObject("SELECT count(*) FROM public.internal_systems", Integer.class));
    assertThrows(
        DataAccessException.class,
        () -> app.queryForObject("SELECT count(*) FROM public.access_grants", Integer.class));
    assertThrows(
        DataAccessException.class,
        () ->
            app.queryForObject("SELECT count(*) FROM public.access_grant_evidence", Integer.class));
    assertThrows(
        DataAccessException.class,
        () ->
            app.update("INSERT INTO public.internal_systems (tenant_id, name) VALUES (NULL, 'x')"));
  }

  @Test
  void accessFunctionsAreOwnedByTheAccessOwner() {
    Integer count =
        admin.queryForObject(
            "SELECT count(*) FROM pg_proc p JOIN pg_roles r ON r.oid = p.proowner"
                + " WHERE p.proname IN ("
                + FUNCTION_NAMES
                + ") AND r.rolname = 'validexia_access_owner'",
            Integer.class);
    assertEquals(18, count);
  }

  @Test
  void functionAclIsOwnerPlusAppExecuteNeverPublic() {
    List<String> publicOffenders =
        admin.queryForList(
            "SELECT p.proname FROM pg_proc p WHERE p.proname IN ("
                + FUNCTION_NAMES
                + ") AND pg_catalog.has_function_privilege('public', p.oid, 'EXECUTE')",
            String.class);
    assertEquals(List.of(), publicOffenders, "PUBLIC may not execute these functions");
    Integer appExecutable =
        admin.queryForObject(
            "SELECT count(*) FROM pg_proc p WHERE p.proname IN ("
                + FUNCTION_NAMES
                + ") AND pg_catalog.has_function_privilege('validexia_app', p.oid, 'EXECUTE')",
            Integer.class);
    assertEquals(18, appExecutable);
  }

  @Test
  void allTwentyOneNamedCheckConstraintsExist() {
    Integer count =
        admin.queryForObject(
            "SELECT count(*) FROM pg_constraint WHERE connamespace = 'public'::regnamespace"
                + " AND conname IN ('chk_internal_systems_status',"
                + " 'chk_internal_systems_name_nonempty', 'chk_internal_systems_retired_metadata',"
                + " 'chk_internal_systems_updated_after_created', 'chk_access_grants_privilege_level',"
                + " 'chk_access_grants_access_method', 'chk_access_grants_source_kind',"
                + " 'chk_access_grants_lifecycle_status', 'chk_access_grants_verification_status',"
                + " 'chk_access_grants_revocation_reason_nonempty',"
                + " 'chk_access_grants_discovery_note_nonempty', 'chk_access_grants_revoked_metadata',"
                + " 'chk_access_grants_revocation_source_xor', 'chk_access_grants_verification_metadata',"
                + " 'chk_access_grants_suspension_metadata',"
                + " 'chk_access_grants_suspended_requires_metadata', 'chk_access_grants_reinstate_metadata',"
                + " 'chk_access_grants_temporal_order', 'chk_access_grants_updated_after_created',"
                + " 'chk_access_grant_evidence_unlink_metadata',"
                + " 'chk_access_grant_evidence_unlink_after_link')",
            Integer.class);
    assertEquals(21, count);
  }

  @Test
  void allFourIndexesExist() {
    Integer count =
        admin.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname IN"
                + " ('internal_systems_active_name_key', 'access_grants_tenant_vendor_idx',"
                + " 'access_grants_tenant_system_idx', 'access_grant_evidence_active_link_key')",
            Integer.class);
    assertEquals(4, count);
  }

  @Test
  void crossModuleGrantsAreSelectOnlyToTheAccessOwner() {
    for (String table : List.of("memberships", "documents", "vendors")) {
      assertTrue(
          admin.queryForObject(
              "SELECT pg_catalog.has_table_privilege('validexia_access_owner', 'public."
                  + table
                  + "', 'SELECT')",
              Boolean.class),
          "access owner must read " + table);
      assertTrue(
          admin.queryForObject(
              "SELECT NOT pg_catalog.has_table_privilege('validexia_access_owner', 'public."
                  + table
                  + "', 'INSERT') AND NOT pg_catalog.has_table_privilege("
                  + "'validexia_access_owner', 'public."
                  + table
                  + "', 'UPDATE') AND NOT pg_catalog.has_table_privilege("
                  + "'validexia_access_owner', 'public."
                  + table
                  + "', 'DELETE')",
              Boolean.class),
          "access owner must hold no write privilege on " + table);
    }
  }
}
