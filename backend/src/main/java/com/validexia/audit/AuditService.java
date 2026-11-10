package com.validexia.audit;

import com.validexia.platform.tenancy.TenantContext;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Audit append path (M02-1 v2.6 §6). Each append runs in its own transaction opened through the
 * tenant-aware transaction manager, with the tenant context set BEFORE the transaction begins, so
 * {@code set_config} is the transaction's first statement. Ordering and hashing are enforced by the
 * database trigger; this service only supplies the event fields.
 */
@Service
public class AuditService {

  public static final String SYSTEM_TENANT = "SYSTEM";

  public record ChainVerification(boolean ok, @Nullable Long firstBadSeq) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public AuditService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Append an event to a tenant's chain (tenantKey = canonical tenant UUID string). */
  public void appendForTenant(
      UUID tenantId,
      UUID actorUserId,
      String action,
      String entityType,
      @Nullable String entityId,
      String detailsJson) {
    append(
        tenantId.toString(),
        "USER",
        actorUserId.toString(),
        action,
        entityType,
        entityId,
        detailsJson);
  }

  /** Append a pre-tenant event to the SYSTEM bucket. */
  public void appendSystem(
      String actorType,
      @Nullable String actorId,
      String action,
      String entityType,
      @Nullable String entityId,
      String detailsJson) {
    append(SYSTEM_TENANT, actorType, actorId, action, entityType, entityId, detailsJson);
  }

  private void append(
      String tenantKey,
      String actorType,
      @Nullable String actorId,
      String action,
      String entityType,
      @Nullable String entityId,
      String detailsJson) {
    TenantContext.set(SYSTEM_TENANT.equals(tenantKey) ? null : tenantKey, actorId);
    try {
      transactions.executeWithoutResult(
          tx ->
              jdbc.update(
                  "INSERT INTO public.audit_events (tenant_key, actor_type, actor_id, action,"
                      + " entity_type, entity_id, details) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS"
                      + " jsonb))",
                  tenantKey,
                  actorType,
                  actorId,
                  action,
                  entityType,
                  entityId,
                  detailsJson == null ? "{}" : detailsJson));
    } finally {
      TenantContext.clear();
    }
  }

  /** Chain verification through the read-only definer function. */
  public ChainVerification verifyChain(String tenantKey) {
    return jdbc.queryForObject(
        "SELECT ok, first_bad_seq FROM public.audit_verify_chain(?)",
        (rs, rowNum) ->
            new ChainVerification(
                rs.getBoolean("ok"),
                rs.getObject("first_bad_seq") == null ? null : rs.getLong("first_bad_seq")),
        tenantKey);
  }
}
