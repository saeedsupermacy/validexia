package com.validexia.vendors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Fail-closed tenant guards (V12, same contract as V6). Every vendor function must reject execution
 * unless the session's {@code app.tenant_id} exactly matches the tenant argument: missing context,
 * empty context and a different tenant's context are all rejected, for reads as well as mutations.
 * Also covers cross-tenant invisibility under a VALID context (tenant B asking about tenant A's
 * vendor simply sees nothing).
 *
 * <p>All calls run as the restricted application role on pinned single connections, because the
 * guard reads the session's tenant setting.
 */
class VendorsTenantGuardTest {

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
      "SELECT public.create_vendor(?, 'Acme', 'Acme Corp Ltd', '1234567890ABCDEFGH12', 'GB',"
          + " 'VND-001', 'Cloud', 'acme.example', null, null, 'Tier 1', null,"
          + " DATE '2024-01-15', DATE '2027-01-15', true)";

  @Test
  void createAndReadSucceedUnderTheMatchingContext() throws Exception {
    UUID tenant = seedTenant("ok-");
    JdbcTemplate session = appSession();
    try {
      setContext(session, tenant.toString());
      UUID vendorId = session.queryForObject(CREATE_SQL, UUID.class, tenant);
      List<String> names =
          session.query(
              "SELECT * FROM public.list_vendors(?)", (rs, row) -> rs.getString("name"), tenant);
      assertEquals(List.of("Acme"), names);
      Integer found =
          session
              .query("SELECT * FROM public.get_vendor(?, ?)", (rs, row) -> 1, tenant, vendorId)
              .size();
      assertEquals(1, found);
    } finally {
      close(session);
    }
  }

  @Test
  void missingEmptyAndForeignContextsAreAllRejected() throws Exception {
    UUID tenant = seedTenant("guard-");
    UUID other = seedTenant("other-");

    // Missing context: a fresh session that never set app.tenant_id.
    JdbcTemplate noContext = appSession();
    try {
      assertRejected(noContext, CREATE_SQL, tenant);
      assertRejected(noContext, "SELECT * FROM public.list_vendors(?)", tenant);
    } finally {
      close(noContext);
    }

    // Empty context.
    JdbcTemplate emptyContext = appSession();
    try {
      setContext(emptyContext, "");
      assertRejected(emptyContext, CREATE_SQL, tenant);
    } finally {
      close(emptyContext);
    }

    // Foreign context: the session belongs to `other`, the call targets `tenant`.
    JdbcTemplate foreign = appSession();
    try {
      setContext(foreign, other.toString());
      assertRejected(foreign, CREATE_SQL, tenant);
      assertRejected(foreign, "SELECT * FROM public.list_vendors(?)", tenant);
      assertRejected(foreign, "SELECT * FROM public.get_vendor(?, ?)", tenant, UUID.randomUUID());
      assertRejected(foreign, "SELECT public.terminate_vendor(?, ?)", tenant, UUID.randomUUID());
    } finally {
      close(foreign);
    }
  }

  @Test
  void anotherTenantsVendorIsInvisibleUnderAValidContext() throws Exception {
    UUID tenantA = seedTenant("a-");
    UUID tenantB = seedTenant("b-");
    JdbcTemplate sessionA = appSession();
    UUID vendorId;
    try {
      setContext(sessionA, tenantA.toString());
      vendorId = sessionA.queryForObject(CREATE_SQL, UUID.class, tenantA);
    } finally {
      close(sessionA);
    }

    JdbcTemplate sessionB = appSession();
    try {
      setContext(sessionB, tenantB.toString());
      assertEquals(
          0,
          sessionB
              .query("SELECT * FROM public.get_vendor(?, ?)", (rs, row) -> 1, tenantB, vendorId)
              .size());
      assertEquals(
          0,
          sessionB.query("SELECT * FROM public.list_vendors(?)", (rs, row) -> 1, tenantB).size());
      assertFalse(
          sessionB.queryForObject(
              "SELECT public.terminate_vendor(?, ?)", Boolean.class, tenantB, vendorId));
      assertFalse(
          sessionB.queryForObject(
              "SELECT public.clear_vendor_dependent_systems(?, ?)",
              Boolean.class,
              tenantB,
              vendorId));
    } finally {
      close(sessionB);
    }
  }

  @Test
  void duplicateCodeIsRejectedWithinATenantButAllowedAcrossTenants() throws Exception {
    UUID tenantA = seedTenant("dup-a-");
    UUID tenantB = seedTenant("dup-b-");
    JdbcTemplate session = appSession();
    try {
      setContext(session, tenantA.toString());
      session.queryForObject(CREATE_SQL, UUID.class, tenantA);
      assertThrows(
          DataAccessException.class, () -> session.queryForObject(CREATE_SQL, UUID.class, tenantA));
      setContext(session, tenantB.toString());
      session.queryForObject(CREATE_SQL, UUID.class, tenantB);
    } finally {
      close(session);
    }
  }

  @Test
  void malformedLeiIsRejectedByTheSchema() throws Exception {
    UUID tenant = seedTenant("lei-");
    JdbcTemplate session = appSession();
    try {
      setContext(session, tenant.toString());
      assertThrows(
          DataAccessException.class,
          () ->
              session.queryForObject(
                  "SELECT public.create_vendor(?, 'Bad LEI Co', null, 'TOOSHORT', null,"
                      + " 'VND-LEI', null, null, null, null, null, null, null, null, false)",
                  UUID.class,
                  tenant));
    } finally {
      close(session);
    }
  }

  @Test
  void dependentSystemsRoundTripAndTerminateLifecycle() throws Exception {
    UUID tenant = seedTenant("life-");
    JdbcTemplate session = appSession();
    try {
      setContext(session, tenant.toString());
      UUID vendorId = session.queryForObject(CREATE_SQL, UUID.class, tenant);
      assertTrue(
          session.queryForObject(
              "SELECT public.add_vendor_dependent_system(?, ?, 'Billing', 'PCI', 'us-east-1')",
              Boolean.class,
              tenant,
              vendorId));
      assertEquals(
          List.of("Billing"),
          session.query(
              "SELECT * FROM public.list_vendor_dependent_systems(?, ?)",
              (rs, row) -> rs.getString("name"),
              tenant,
              vendorId));
      assertTrue(
          session.queryForObject(
              "SELECT public.clear_vendor_dependent_systems(?, ?)",
              Boolean.class,
              tenant,
              vendorId));
      assertEquals(
          0,
          session
              .query(
                  "SELECT * FROM public.list_vendor_dependent_systems(?, ?)",
                  (rs, row) -> 1,
                  tenant,
                  vendorId)
              .size());

      assertTrue(
          session.queryForObject(
              "SELECT public.terminate_vendor(?, ?)", Boolean.class, tenant, vendorId));
      String status =
          session
              .query(
                  "SELECT * FROM public.get_vendor(?, ?)",
                  (rs, row) -> rs.getString("status"),
                  tenant,
                  vendorId)
              .getFirst();
      assertEquals("Terminated", status);
      // Terminating an already-terminated vendor is a no-op, not an error.
      assertFalse(
          session.queryForObject(
              "SELECT public.terminate_vendor(?, ?)", Boolean.class, tenant, vendorId));
    } finally {
      close(session);
    }
  }
}
