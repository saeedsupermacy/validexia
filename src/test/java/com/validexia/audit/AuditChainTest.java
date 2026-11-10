package com.validexia.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.platform.tenancy.TenantContext;
import com.validexia.support.TestDatabase;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Audit-chain guarantees from the application role's seat (M02-1 v2.6 §6): the database assigns
 * ordering and hashes, forged caller values are discarded, tampering is detected, and the app role
 * has no path around the trigger.
 */
@SpringBootTest
class AuditChainTest {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.springProperties().forEach((key, value) -> registry.add(key, () -> value));
  }

  @Autowired AuditService audit;
  @Autowired JdbcTemplate appJdbc;
  @Autowired PlatformTransactionManager transactionManager;

  private final JdbcTemplate admin = TestDatabase.adminJdbc();

  @Test
  void appendsAreSequencedAndChainedByTheDatabase() {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    audit.appendForTenant(tenant, actor, "FIRST", "SESSION", "s1", "{}");
    audit.appendForTenant(tenant, actor, "SECOND", "SESSION", "s2", "{}");
    audit.appendForTenant(tenant, actor, "THIRD", "SESSION", "s3", "{}");

    var rows =
        admin.query(
            "SELECT seq, encode(prev_hash, 'hex') AS prev, encode(event_hash, 'hex') AS hash"
                + " FROM public.audit_events WHERE tenant_key = ? ORDER BY seq",
            (rs, n) ->
                new String[] {rs.getString("seq"), rs.getString("prev"), rs.getString("hash")},
            tenant.toString());
    assertEquals(3, rows.size());
    assertEquals("1", rows.get(0)[0]);
    assertEquals("0".repeat(64), rows.get(0)[1], "genesis prev_hash is 32 zero bytes");
    assertEquals(rows.get(0)[2], rows.get(1)[1], "each event chains to its predecessor");
    assertEquals(rows.get(1)[2], rows.get(2)[1]);
    assertTrue(audit.verifyChain(tenant.toString()).ok());
  }

  @Test
  void callerSuppliedSeqAndHashesAreOverwritten() {
    UUID tenant = UUID.randomUUID();
    TenantContext.set(tenant.toString(), null);
    try {
      new TransactionTemplate(transactionManager)
          .executeWithoutResult(
              tx ->
                  appJdbc.update(
                      "INSERT INTO public.audit_events (tenant_key, seq, actor_type, actor_id,"
                          + " action, entity_type, entity_id, details, prev_hash, event_hash)"
                          + " VALUES (?, 999, 'USER', 'x', 'FORGED', 'SESSION', NULL, '{}',"
                          + " decode(repeat('ab', 32), 'hex'), decode(repeat('cd', 32), 'hex'))",
                      tenant.toString()));
    } finally {
      TenantContext.clear();
    }
    Long seq =
        admin.queryForObject(
            "SELECT seq FROM public.audit_events WHERE tenant_key = ?",
            Long.class,
            tenant.toString());
    assertEquals(1L, seq, "the trigger assigns the real sequence, not the caller's 999");
    assertTrue(audit.verifyChain(tenant.toString()).ok());
  }

  @Test
  void tamperingIsDetectedWithTheBrokenSequence() {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    audit.appendForTenant(tenant, actor, "ONE", "SESSION", null, "{}");
    audit.appendForTenant(tenant, actor, "TWO", "SESSION", null, "{}");
    audit.appendForTenant(tenant, actor, "THREE", "SESSION", null, "{}");
    assertTrue(audit.verifyChain(tenant.toString()).ok());

    admin.update(
        "UPDATE public.audit_events SET action = 'FORGED' WHERE tenant_key = ? AND seq = 2",
        tenant.toString());
    AuditService.ChainVerification result = audit.verifyChain(tenant.toString());
    assertFalse(result.ok());
    assertEquals(2L, result.firstBadSeq());
  }

  @Test
  void applicationRoleCannotUpdateOrReadChainHeads() {
    assertThrows(
        DataAccessException.class,
        () -> appJdbc.update("UPDATE public.audit_events SET action = 'X'"));
    assertThrows(
        DataAccessException.class,
        () ->
            appJdbc.queryForObject("SELECT count(*) FROM public.audit_chain_heads", Integer.class));
    assertThrows(
        DataAccessException.class,
        () -> appJdbc.queryForObject("SELECT public.audit_events_chain_trigger()", Object.class));
  }

  @Test
  void concurrentAppendsStayGaplessAndValid() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    int threads = 8;
    int perThread = 5;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    for (int t = 0; t < threads; t++) {
      pool.submit(
          () -> {
            for (int i = 0; i < perThread; i++) {
              audit.appendForTenant(tenant, actor, "CONCURRENT", "SESSION", null, "{}");
            }
            return null;
          });
    }
    pool.shutdown();
    assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
    Integer count =
        admin.queryForObject(
            "SELECT count(*) FROM public.audit_events WHERE tenant_key = ?",
            Integer.class,
            tenant.toString());
    Integer distinctSeqs =
        admin.queryForObject(
            "SELECT count(DISTINCT seq) FROM public.audit_events WHERE tenant_key = ?",
            Integer.class,
            tenant.toString());
    assertEquals(threads * perThread, count);
    assertEquals(threads * perThread, distinctSeqs);
    assertTrue(audit.verifyChain(tenant.toString()).ok());
  }
}
