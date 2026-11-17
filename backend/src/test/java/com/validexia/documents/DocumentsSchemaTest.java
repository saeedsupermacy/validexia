package com.validexia.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V13 schema shape: the three documents tables exist under the documents owner role with row-level
 * security enabled AND forced, the expected tenant policies are in place, the eight SECURITY
 * DEFINER functions are owned by that role with a pinned search_path, and neither PUBLIC nor the
 * application role holds access it should not have.
 *
 * <p>Schema assertions run as the admin (migrator) role; the last test connects as admin, {@code
 * SET ROLE}s to the table owner and proves that FORCE ROW LEVEL SECURITY binds even the owner.
 */
class DocumentsSchemaTest {

  private static final String OWNER = "validexia_documents_owner";
  private static final String APP = "validexia_app";

  private static final String TABLES = "('documents', 'document_pages', 'evidence_records')";

  private static final String FUNCTION_NAMES =
      "('create_document', 'get_document', 'list_documents', 'set_document_status',"
          + " 'replace_document_pages', 'list_document_pages', 'create_evidence',"
          + " 'list_evidence')";

  private final JdbcTemplate app = TestDatabase.appJdbc();
  private final JdbcTemplate admin = TestDatabase.adminJdbc();

  @Test
  void documentTablesHaveRlsEnabledAndForced() {
    Integer count =
        admin.queryForObject(
            "SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace"
                + " AND relname IN "
                + TABLES
                + " AND relrowsecurity = true AND relforcerowsecurity = true",
            Integer.class);
    assertEquals(3, count);
  }

  @Test
  void documentTablesAreOwnedByTheDocumentsOwner() {
    Integer count =
        admin.queryForObject(
            "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'"
                + " AND tablename IN "
                + TABLES
                + " AND tableowner = '"
                + OWNER
                + "'",
            Integer.class);
    assertEquals(3, count);
  }

  @Test
  void documentsOwnerRoleIsNoLoginAndCannotBypassRls() {
    Map<String, Object> role =
        admin.queryForMap(
            "SELECT rolcanlogin, rolsuper, rolbypassrls, rolcreatedb, rolcreaterole"
                + " FROM pg_roles WHERE rolname = '"
                + OWNER
                + "'");
    assertEquals(false, role.get("rolcanlogin"));
    assertEquals(false, role.get("rolsuper"));
    assertEquals(false, role.get("rolbypassrls"));
    assertEquals(false, role.get("rolcreatedb"));
    assertEquals(false, role.get("rolcreaterole"));
  }

  @Test
  void appRoleHasNoDirectTableAccess() {
    for (String table : List.of("documents", "document_pages", "evidence_records")) {
      assertThrows(
          DataAccessException.class,
          () -> app.queryForObject("SELECT count(*) FROM public." + table, Integer.class),
          table);
    }
    assertThrows(
        DataAccessException.class,
        () ->
            app.update(
                "INSERT INTO public.documents (tenant_id, file_name, file_type, file_size,"
                    + " sha256, storage_key) VALUES (NULL, 'x', 'y', 0, 'h', 'k')"));
  }

  @Test
  void documentFunctionsAreOwnedByTheDocumentsOwner() {
    Integer count =
        admin.queryForObject(
            "SELECT count(*) FROM pg_proc p JOIN pg_roles r ON r.oid = p.proowner"
                + " WHERE p.pronamespace = 'public'::regnamespace"
                + " AND p.proname IN "
                + FUNCTION_NAMES
                + " AND r.rolname = '"
                + OWNER
                + "'",
            Integer.class);
    assertEquals(8, count);
  }

  @Test
  void documentFunctionsAreSecurityDefinerWithPinnedSearchPath() {
    List<String> offenders =
        admin.queryForList(
            "SELECT p.proname FROM pg_proc p"
                + " WHERE p.pronamespace = 'public'::regnamespace"
                + " AND p.proname IN "
                + FUNCTION_NAMES
                + " AND (p.prosecdef = false"
                + "      OR p.proconfig IS NULL"
                + "      OR (SELECT count(*) FROM pg_catalog.unnest(p.proconfig)) <> 1"
                + "      OR NOT EXISTS ("
                + "          SELECT 1 FROM pg_catalog.unnest(p.proconfig) AS c(item)"
                + "          WHERE pg_catalog.replace(c.item, ' ', '')"
                + "                = 'search_path=pg_catalog,pg_temp'))",
            String.class);
    assertEquals(List.of(), offenders, "functions without the pinned search_path");
  }

  @Test
  void publicHasNoExecuteOnDocumentFunctions() {
    List<String> offenders =
        admin.queryForList(
            "SELECT p.proname FROM pg_proc p"
                + " WHERE p.pronamespace = 'public'::regnamespace"
                + " AND p.proname IN "
                + FUNCTION_NAMES
                + " AND pg_catalog.has_function_privilege('public', p.oid, 'EXECUTE')",
            String.class);
    assertEquals(List.of(), offenders, "PUBLIC may not execute these functions");
  }

  @Test
  void appRoleHasExecuteOnDocumentFunctionsButNoTablePrivileges() {
    List<String> missingExecute =
        admin.queryForList(
            "SELECT p.proname FROM pg_proc p"
                + " WHERE p.pronamespace = 'public'::regnamespace"
                + " AND p.proname IN "
                + FUNCTION_NAMES
                + " AND NOT pg_catalog.has_function_privilege('"
                + APP
                + "', p.oid, 'EXECUTE')",
            String.class);
    assertEquals(List.of(), missingExecute, "application role must execute every function");

    List<String> privilegedTables =
        admin.queryForList(
            "SELECT t.tablename FROM pg_tables t"
                + " WHERE t.schemaname = 'public' AND t.tablename IN "
                + TABLES
                + " AND (pg_catalog.has_table_privilege('"
                + APP
                + "', 'public.' || t.tablename,"
                + "        'SELECT')"
                + "      OR pg_catalog.has_table_privilege('"
                + APP
                + "', 'public.' || t.tablename,"
                + "        'INSERT')"
                + "      OR pg_catalog.has_table_privilege('"
                + APP
                + "', 'public.' || t.tablename,"
                + "        'UPDATE')"
                + "      OR pg_catalog.has_table_privilege('"
                + APP
                + "', 'public.' || t.tablename,"
                + "        'DELETE'))",
            String.class);
    assertEquals(List.of(), privilegedTables, "application role holds table privileges");

    List<String> publicTables =
        admin.queryForList(
            "SELECT t.tablename FROM pg_tables t"
                + " WHERE t.schemaname = 'public' AND t.tablename IN "
                + TABLES
                + " AND pg_catalog.has_table_privilege('public', 'public.' || t.tablename,"
                + "     'SELECT')",
            String.class);
    assertEquals(List.of(), publicTables, "PUBLIC may not read these tables");
  }

  @Test
  void tenantPoliciesExistOnAllThreeTables() {
    List<Map<String, Object>> policies =
        admin.queryForList(
            "SELECT tablename, policyname, cmd, qual, with_check FROM pg_policies"
                + " WHERE schemaname = 'public' AND tablename IN "
                + TABLES);
    assertEquals(3, policies.size(), "expected exactly one policy per documents table");
    for (Map<String, Object> policy : policies) {
      String table = (String) policy.get("tablename");
      assertEquals(table + "_owner_tenant", policy.get("policyname"));
      assertEquals("ALL", policy.get("cmd"));
      String using = (String) policy.get("qual");
      assertEquals(using, policy.get("with_check"));
      assertTrue(
          using != null && using.contains("app.tenant_id"),
          table + " policy must be bound to app.tenant_id");
    }
  }

  @Test
  void forceRlsBindsEvenTheTableOwner() throws Exception {
    UUID tenantA = seedTenant("rls-a-");
    UUID tenantB = seedTenant("rls-b-");

    JdbcTemplate owner = ownerSession();
    try {
      owner.update("SET ROLE " + OWNER);
      setContext(owner, tenantA.toString());
      owner.update(INSERT_DOCUMENT, tenantA);
      assertEquals(1, owner.queryForObject("SELECT count(*) FROM public.documents", Integer.class));

      // Tenant B sees nothing of tenant A's rows under FORCE ROW LEVEL SECURITY.
      setContext(owner, tenantB.toString());
      assertEquals(0, owner.queryForObject("SELECT count(*) FROM public.documents", Integer.class));

      // A row belonging to tenant A cannot be written while the session context is tenant B.
      assertThrows(
          DataAccessException.class,
          () -> owner.update(INSERT_DOCUMENT, tenantA),
          "FORCE RLS must reject a cross-tenant WITH CHECK");

      // Tenant B's own row is still allowed, so the policy is isolation, not a blanket deny.
      owner.update(INSERT_DOCUMENT, tenantB);
      assertEquals(1, owner.queryForObject("SELECT count(*) FROM public.documents", Integer.class));
    } finally {
      close(owner);
    }
  }

  private static final String INSERT_DOCUMENT =
      "INSERT INTO public.documents (tenant_id, file_name, file_type, file_size, sha256,"
          + " storage_key) VALUES (?, 'a.pdf', 'application/pdf', 1, 'h', 'k')";

  private JdbcTemplate ownerSession() throws Exception {
    Connection connection = admin.getDataSource().getConnection();
    return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
  }

  private void close(JdbcTemplate session) {
    ((SingleConnectionDataSource) session.getDataSource()).destroy();
  }

  private void setContext(JdbcTemplate session, String tenantId) {
    session.queryForObject("SELECT set_config('app.tenant_id', ?, false)", String.class, tenantId);
  }

  private UUID seedTenant(String slug) {
    UUID tenant = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'T', ?)", tenant, slug + tenant);
    return tenant;
  }
}
