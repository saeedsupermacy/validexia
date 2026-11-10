package com.validexia.platform.tenancy;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Explicit tenant context for service-layer work that runs outside (or ahead of) the
 * request filter — principally audit appends, where the service selects the tenant bucket before
 * opening its transaction. {@link TenantTransactionManager} reads this first, then falls back to
 * the request context, then to empty values (which match nothing: fail closed).
 */
public final class TenantContext {

  public record Values(@Nullable String tenantId, @Nullable String userId) {}

  private static final ThreadLocal<Values> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  public static void set(@Nullable String tenantId, @Nullable String userId) {
    CURRENT.set(new Values(tenantId, userId));
  }

  public static Optional<Values> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static void clear() {
    CURRENT.remove();
  }
}
