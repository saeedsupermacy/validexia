package com.validexia.platform.security;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Per-request authentication context, established by {@link ClerkAuthenticationFilter} after token
 * verification and directory checks. Tenant selection is per-request (the {@code X-Tenant-Id}
 * header), never a server-side session attribute.
 */
public final class RequestContext {

  public record Context(
      String subject,
      UUID userId,
      String sessionId,
      java.time.Instant tokenExpiresAt,
      @Nullable UUID tenantId) {}

  private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

  private RequestContext() {}

  public static void set(Context context) {
    CURRENT.set(context);
  }

  public static Optional<Context> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static Context require() {
    Context context = CURRENT.get();
    if (context == null) {
      throw new IllegalStateException("No authenticated request context");
    }
    return context;
  }

  public static void clear() {
    CURRENT.remove();
  }
}
