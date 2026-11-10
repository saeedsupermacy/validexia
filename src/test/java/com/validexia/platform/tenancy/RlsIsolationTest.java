package com.validexia.platform.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.audit.AuditService;
import com.validexia.support.TestDatabase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant isolation against the REAL pool (size 1, from TestDatabase): context is transaction
 * -local, so sequential transactions on the same physical connection cannot leak tenants into each
 * other — including after a rollback (M02-1 v2.6 §1). A scratch probe table stands in for future
 * tenant-owned tables; audit_events provides the same proof for a real M02-1 table.
 */
@SpringBootTest
class RlsIsolationTest {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.springProperties().forEach((key, value) -> registry.add(key, () -> value));
  }

  @Autowired JdbcTemplate appJdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired AuditService audit;

  private static final JdbcTemplate ADMIN = TestDatabase.adminJdbc();
  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();

  @BeforeAll
  static void seed() {
    ADMIN.execute("DROP TABLE IF EXISTS public.rls_probe");
    ADMIN.execute("CREATE TABLE public.rls_probe (tenant_key text NOT NULL, note text NOT NULL)");
    ADMIN.execute("ALTER TABLE public.rls_probe ENABLE ROW LEVEL SECURITY");
    ADMIN.execute("ALTER TABLE public.rls_probe FORCE ROW LEVEL SECURITY");
    ADMIN.execute(
        "CREATE POLICY rls_probe_app ON public.rls_probe FOR ALL TO validexia_app"
            + " USING (tenant_key = current_setting('app.tenant_id', true))"
            + " WITH CHECK (tenant_key = current_setting('app.tenant_id', true))");
    ADMIN.execute("GRANT SELECT, INSERT ON public.rls_probe TO validexia_app");
    ADMIN.update("DELETE FROM public.rls_probe");
    ADMIN.update("INSERT INTO public.rls_probe VALUES (?, 'a-row')", TENANT_A.toString());
    ADMIN.update("INSERT INTO public.rls_probe VALUES (?, 'b-row')", TENANT_B.toString());
  }

  @AfterAll
  static void cleanup() {
    ADMIN.execute("DROP TABLE IF EXISTS public.rls_probe");
  }

  private List<String> probeNotes() {
    return appJdbc.queryForList("SELECT note FROM public.rls_probe ORDER BY note", String.class);
  }

  private <T> T inTenant(UUID tenant, java.util.function.Supplier<T> work) {
    TenantContext.set(tenant == null ? null : tenant.toString(), null);
    try {
      return new TransactionTemplate(transactionManager).execute(tx -> work.get());
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void eachTenantSeesOnlyItsOwnRows() {
    assertEquals(List.of("a-row"), inTenant(TENANT_A, this::probeNotes));
    assertEquals(List.of("b-row"), inTenant(TENANT_B, this::probeNotes));
  }

  @Test
  void missingContextSeesNothing() {
    assertEquals(List.of(), inTenant(null, this::probeNotes));
  }

  @Test
  void abortedTransactionLeavesNoResidueOnThePooledConnection() {
    assertThrows(
        IllegalStateException.class,
        () ->
            inTenant(
                TENANT_A,
                () -> {
                  assertEquals(List.of("a-row"), probeNotes());
                  throw new IllegalStateException("boom");
                }));
    // Same physical connection (pool size 1): tenant B must not inherit A's context.
    assertEquals(List.of("b-row"), inTenant(TENANT_B, this::probeNotes));
    assertEquals(List.of(), inTenant(null, this::probeNotes));
  }

  @Test
  void auditEventsFollowTheSameIsolation() {
    UUID actor = UUID.randomUUID();
    audit.appendForTenant(TENANT_A, actor, "PROBE_A", "SESSION", null, "{}");
    audit.appendForTenant(TENANT_B, actor, "PROBE_B", "SESSION", null, "{}");
    List<String> visibleToA =
        inTenant(
            TENANT_A,
            () ->
                appJdbc.queryForList(
                    "SELECT action FROM public.audit_events WHERE tenant_key = ?",
                    String.class,
                    TENANT_A.toString()));
    assertTrue(visibleToA.contains("PROBE_A"));
    List<String> bRowsSeenFromA =
        inTenant(
            TENANT_A,
            () ->
                appJdbc.queryForList(
                    "SELECT action FROM public.audit_events WHERE tenant_key = ?",
                    String.class,
                    TENANT_B.toString()));
    assertEquals(List.of(), bRowsSeenFromA, "tenant B rows must be invisible under tenant A");
  }
}
