package com.validexia.platform.tenancy;

import com.validexia.platform.security.RequestContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tenant-aware transaction manager (M02-1 v2.6 §1). Decorates the standard manager so that the
 * FIRST statements of every transaction set the transaction-local PostgreSQL configuration RLS
 * depends on: {@code app.tenant_id} and {@code app.user_id}. Transaction-local {@code set_config}
 * means the values vanish on commit/rollback — a pooled connection can never leak one request's
 * tenant into another request.
 *
 * <p>Values resolve in order: explicit {@link TenantContext} (service layer) → {@link
 * RequestContext} (authentication filter) → empty strings, which match no tenant and no user (fail
 * closed).
 */
public class TenantTransactionManager extends DataSourceTransactionManager {

  public TenantTransactionManager(DataSource dataSource) {
    super(dataSource);
  }

  @Override
  protected void doBegin(
      Object transaction, org.springframework.transaction.TransactionDefinition definition) {
    super.doBegin(transaction, definition);
    DataSource dataSource = getDataSource();
    Object resource = TransactionSynchronizationManager.getResource(dataSource);
    if (!(resource instanceof org.springframework.jdbc.datasource.ConnectionHolder holder)) {
      throw new CannotCreateTransactionException("No connection holder for tenant context setup");
    }
    Connection connection = holder.getConnection();
    String tenantId = resolveTenantId();
    String userId = resolveUserId();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT set_config('app.tenant_id', ?, true), set_config('app.user_id', ?, true)")) {
      statement.setString(1, tenantId);
      statement.setString(2, userId);
      statement.execute();
    } catch (SQLException e) {
      throw new CannotCreateTransactionException("Failed to establish tenant context", e);
    }
  }

  private String resolveTenantId() {
    return TenantContext.current()
        .map(TenantContext.Values::tenantId)
        .or(
            () ->
                RequestContext.current()
                    .map(c -> c.tenantId() == null ? null : c.tenantId().toString()))
        .orElse("");
  }

  private String resolveUserId() {
    return TenantContext.current()
        .map(TenantContext.Values::userId)
        .or(() -> RequestContext.current().map(c -> c.userId().toString()))
        .orElse("");
  }
}
