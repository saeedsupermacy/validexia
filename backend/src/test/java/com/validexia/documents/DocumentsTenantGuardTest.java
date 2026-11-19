package com.validexia.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.support.TestDatabase;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Fail-closed tenant guards (V13, same contract as V6/V12). Every documents function must reject
 * execution unless the session's {@code app.tenant_id} exactly matches the tenant argument: missing
 * context, empty context and a different tenant's context are all rejected, for reads as well as
 * mutations. Also covers cross-tenant invisibility under a VALID context (tenant B asking about
 * tenant A's document sees nothing and cannot mutate it).
 *
 * <p>All calls run as the restricted application role on pinned single connections, because the
 * guard reads the session's tenant setting.
 */
class DocumentsTenantGuardTest {

  private final JdbcTemplate admin = TestDatabase.adminJdbc();

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

  private UUID seedTenant(String slug) {
    UUID tenant = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'T', ?)", tenant, slug + tenant);
    return tenant;
  }

  private static final String CREATE_SQL =
      "SELECT public.create_document(?, null, null, 'questionnaire.pdf', 'application/pdf',"
          + " 1024, 'sha256', 'tenant/document/questionnaire.pdf', 'Ready', 0, null)";
  private static final String GET_SQL = "SELECT * FROM public.get_document(?, ?)";
  private static final String LIST_SQL = "SELECT * FROM public.list_documents(?, null)";
  private static final String SET_STATUS_SQL =
      "SELECT public.set_document_status(?, ?, 'Processing')";
  private static final String REPLACE_PAGES_SQL =
      "SELECT public.replace_document_pages(?, ?, ?::jsonb)";
  private static final String LIST_PAGES_SQL = "SELECT * FROM public.list_document_pages(?, ?)";
  private static final String CREATE_EVIDENCE_SQL =
      "SELECT public.create_evidence(?, ?, null, 1, 'a quote from page one', 'quote-sha')";
  private static final String LIST_EVIDENCE_SQL = "SELECT * FROM public.list_evidence(?, ?)";

  private static final String TWO_PAGES_JSON =
      "[{\"page_number\":1,\"text_content\":\"Page one\",\"text_sha256\":\"h1\"},"
          + "{\"page_number\":2,\"text_content\":\"Page two\",\"text_sha256\":\"h2\"}]";

  @Test
  void validContextAllowsTheIntendedOperations() throws Exception {
    UUID tenant = seedTenant("ok-");
    JdbcTemplate session = appSession();
    try {
      setContext(session, tenant.toString());
      UUID documentId = session.queryForObject(CREATE_SQL, UUID.class, tenant);
      assertNotNull(documentId);

      assertEquals(
          "questionnaire.pdf",
          session
              .query(GET_SQL, (rs, row) -> rs.getString("file_name"), tenant, documentId)
              .getFirst());
      assertEquals(1, session.query(LIST_SQL, (rs, row) -> 1, tenant).size());
      assertTrue(session.queryForObject(SET_STATUS_SQL, Boolean.class, tenant, documentId));

      assertTrue(
          session.queryForObject(
              REPLACE_PAGES_SQL, Boolean.class, tenant, documentId, TWO_PAGES_JSON));
      assertEquals(
          List.of(1, 2),
          session.query(LIST_PAGES_SQL, (rs, row) -> rs.getInt("page_number"), tenant, documentId));

      UUID evidenceId = session.queryForObject(CREATE_EVIDENCE_SQL, UUID.class, tenant, documentId);
      assertNotNull(evidenceId);
      assertEquals(
          List.of("a quote from page one"),
          session.query(
              LIST_EVIDENCE_SQL, (rs, row) -> rs.getString("quote_text"), tenant, documentId));
    } finally {
      close(session);
    }
  }

  @Test
  void missingEmptyAndForeignContextsAreAllRejected() throws Exception {
    UUID tenant = seedTenant("guard-");
    UUID other = seedTenant("other-");
    UUID documentId = UUID.randomUUID();

    // Missing context: a fresh session that never set app.tenant_id.
    JdbcTemplate noContext = appSession();
    try {
      assertRejected(noContext, CREATE_SQL, tenant);
      assertRejected(noContext, GET_SQL, tenant, documentId);
      assertRejected(noContext, LIST_SQL, tenant);
      assertRejected(noContext, SET_STATUS_SQL, tenant, documentId);
      assertRejected(noContext, REPLACE_PAGES_SQL, tenant, documentId, TWO_PAGES_JSON);
      assertRejected(noContext, LIST_PAGES_SQL, tenant, documentId);
      assertRejected(noContext, CREATE_EVIDENCE_SQL, tenant, documentId);
      assertRejected(noContext, LIST_EVIDENCE_SQL, tenant, documentId);
    } finally {
      close(noContext);
    }

    // Empty context.
    JdbcTemplate emptyContext = appSession();
    try {
      setContext(emptyContext, "");
      assertRejected(emptyContext, CREATE_SQL, tenant);
      assertRejected(emptyContext, LIST_SQL, tenant);
      assertRejected(emptyContext, LIST_EVIDENCE_SQL, tenant, documentId);
    } finally {
      close(emptyContext);
    }

    // Foreign context: the session belongs to `other`, the call targets `tenant`.
    JdbcTemplate foreign = appSession();
    try {
      setContext(foreign, other.toString());
      assertRejected(foreign, CREATE_SQL, tenant);
      assertRejected(foreign, GET_SQL, tenant, documentId);
      assertRejected(foreign, LIST_SQL, tenant);
      assertRejected(foreign, SET_STATUS_SQL, tenant, documentId);
      assertRejected(foreign, REPLACE_PAGES_SQL, tenant, documentId, TWO_PAGES_JSON);
      assertRejected(foreign, LIST_PAGES_SQL, tenant, documentId);
      assertRejected(foreign, CREATE_EVIDENCE_SQL, tenant, documentId);
      assertRejected(foreign, LIST_EVIDENCE_SQL, tenant, documentId);
    } finally {
      close(foreign);
    }
  }

  @Test
  void anotherTenantsDocumentIsInvisibleUnderAValidContext() throws Exception {
    UUID tenantA = seedTenant("a-");
    UUID tenantB = seedTenant("b-");
    UUID documentId;
    JdbcTemplate sessionA = appSession();
    try {
      setContext(sessionA, tenantA.toString());
      documentId = sessionA.queryForObject(CREATE_SQL, UUID.class, tenantA);
    } finally {
      close(sessionA);
    }

    JdbcTemplate sessionB = appSession();
    try {
      setContext(sessionB, tenantB.toString());
      assertEquals(0, sessionB.query(GET_SQL, (rs, row) -> 1, tenantB, documentId).size());
      assertEquals(0, sessionB.query(LIST_SQL, (rs, row) -> 1, tenantB).size());
      assertFalse(sessionB.queryForObject(SET_STATUS_SQL, Boolean.class, tenantB, documentId));
      assertFalse(
          sessionB.queryForObject(
              REPLACE_PAGES_SQL, Boolean.class, tenantB, documentId, TWO_PAGES_JSON));
      assertEquals(0, sessionB.query(LIST_PAGES_SQL, (rs, row) -> 1, tenantB, documentId).size());
      List<UUID> evidence =
          sessionB.queryForList(CREATE_EVIDENCE_SQL, UUID.class, tenantB, documentId);
      assertEquals(1, evidence.size());
      assertNull(evidence.getFirst());
      assertEquals(
          0, sessionB.query(LIST_EVIDENCE_SQL, (rs, row) -> 1, tenantB, documentId).size());
    } finally {
      close(sessionB);
    }
  }

  @Test
  void pageReplacementIsAtomicAndDropsThePreviousSet() throws Exception {
    UUID tenant = seedTenant("pages-");
    JdbcTemplate session = appSession();
    try {
      setContext(session, tenant.toString());
      UUID documentId = session.queryForObject(CREATE_SQL, UUID.class, tenant);

      assertTrue(
          session.queryForObject(
              REPLACE_PAGES_SQL, Boolean.class, tenant, documentId, TWO_PAGES_JSON));
      assertTrue(
          session.queryForObject(
              REPLACE_PAGES_SQL,
              Boolean.class,
              tenant,
              documentId,
              "[{\"page_number\":7,\"text_content\":\"Only page\",\"text_sha256\":\"h7\"}]"));

      assertEquals(
          List.of(7),
          session.query(LIST_PAGES_SQL, (rs, row) -> rs.getInt("page_number"), tenant, documentId));
    } finally {
      close(session);
    }
  }

  @Test
  void replacePagesReturnsFalseForADocumentOutsideTheCallingTenant() throws Exception {
    UUID tenantA = seedTenant("rp-a-");
    UUID tenantB = seedTenant("rp-b-");
    UUID documentId;
    JdbcTemplate sessionA = appSession();
    try {
      setContext(sessionA, tenantA.toString());
      documentId = sessionA.queryForObject(CREATE_SQL, UUID.class, tenantA);
    } finally {
      close(sessionA);
    }

    JdbcTemplate sessionB = appSession();
    try {
      setContext(sessionB, tenantB.toString());
      assertFalse(
          sessionB.queryForObject(
              REPLACE_PAGES_SQL, Boolean.class, tenantB, documentId, TWO_PAGES_JSON));
      // Tenant A's pages were untouched.
    } finally {
      close(sessionB);
    }

    JdbcTemplate sessionAAgain = appSession();
    try {
      setContext(sessionAAgain, tenantA.toString());
      assertEquals(
          0, sessionAAgain.query(LIST_PAGES_SQL, (rs, row) -> 1, tenantA, documentId).size());
    } finally {
      close(sessionAAgain);
    }
  }
}
