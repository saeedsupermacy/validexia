package com.validexia.platform.security;

import com.validexia.identity.application.LockedResyncService;
import com.validexia.identity.infrastructure.DirectoryRepository;
import com.validexia.identity.infrastructure.DirectoryRepository.DirectoryUser;
import com.validexia.platform.errors.ApiErrorHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every {@code /api/v1/**} request (except the health probe and the webhook
 * receiver) from its Clerk session token, then establishes the request context (M02-1 v2.6 §2):
 * directory lookup with lazy provisioning, lifecycle gating (with the locked-only JIT resync),
 * the {@code tokens_valid_after} epoch check, the session denylist, and per-request tenant
 * selection via {@code X-Tenant-Id} (never trusted — membership is re-verified here, on every
 * request).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ClerkAuthenticationFilter extends OncePerRequestFilter {

  private final ClerkSessionTokenVerifier verifier;
  private final DirectoryRepository directory;
  private final LockedResyncService resyncService;

  public ClerkAuthenticationFilter(
      ClerkSessionTokenVerifier verifier,
      DirectoryRepository directory,
      LockedResyncService resyncService) {
    this.verifier = verifier;
    this.directory = directory;
    this.resyncService = resyncService;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !path.startsWith("/api/v1/")
        || path.equals("/api/v1/health")
        || path.startsWith("/api/v1/webhooks/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String authorization = request.getHeader("Authorization");
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Missing bearer token");
      return;
    }
    ClerkIdentity identity;
    try {
      identity = verifier.verify(authorization.substring("Bearer ".length()).trim());
    } catch (TokenValidationException e) {
      writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", e.getMessage());
      return;
    }
    String subject = identity.subject();

    DirectoryUser user = directory.findUser(subject).orElse(null);
    if (user == null) {
      directory.provisionUser(subject, identity.email(), null);
      user = directory.findUser(subject).orElse(null);
      if (user == null) {
        writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Identity unavailable");
        return;
      }
    }

    if (!"active".equals(user.status())) {
      writeError(response, HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "Account is not active");
      return;
    }
    // Locked-only block: give the JIT resync one chance to refresh the flags, then judge
    // the complete flag set from fresh local state (the snapshot may also ban/deprovision).
    if (user.locked() && !user.banned() && !user.deprovisioned()) {
      resyncService.tryResync(subject);
      DirectoryUser refreshed = directory.findUser(subject).orElse(user);
      user = refreshed;
      if (!"active".equals(user.status())) {
        writeError(response, HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "Account is not active");
        return;
      }
    }
    if (user.banned()) {
      writeError(response, HttpStatus.FORBIDDEN, "ACCOUNT_BANNED", "Account is banned");
      return;
    }
    if (user.deprovisioned()) {
      writeError(
          response, HttpStatus.FORBIDDEN, "ACCOUNT_DEPROVISIONED", "Account is deprovisioned");
      return;
    }
    if (user.locked()) {
      writeError(response, HttpStatus.FORBIDDEN, "ACCOUNT_LOCKED", "Account is temporarily locked");
      return;
    }
    if (user.tokensValidAfter() != null && identity.issuedAt().isBefore(user.tokensValidAfter())) {
      writeError(
          response,
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Token predates a blocking account change; sign in again");
      return;
    }
    if (directory.isSessionRevoked(identity.sessionId())) {
      writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Session has been revoked");
      return;
    }

    UUID tenantId = null;
    String tenantHeader = request.getHeader("X-Tenant-Id");
    if (tenantHeader != null && !tenantHeader.isBlank()) {
      try {
        tenantId = UUID.fromString(tenantHeader.trim());
      } catch (IllegalArgumentException e) {
        writeError(
            response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "X-Tenant-Id must be a UUID");
        return;
      }
      UUID selected = tenantId;
      boolean member =
          directory.listMemberships(subject).stream()
              .anyMatch(m -> m.tenantId().equals(selected) && "active".equals(m.status()));
      if (!member) {
        writeError(
            response,
            HttpStatus.FORBIDDEN,
            "TENANT_ACCESS_DENIED",
            "Not an active member of the selected tenant");
        return;
      }
    }

    RequestContext.set(
        new RequestContext.Context(
            subject, user.userId(), identity.sessionId(), identity.expiresAt(), tenantId));
    try {
      chain.doFilter(request, response);
    } finally {
      RequestContext.clear();
    }
  }

  private void writeError(
      HttpServletResponse response, HttpStatus status, String code, String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(ApiErrorHandler.bodyJson(code, message));
  }
}
