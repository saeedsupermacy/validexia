package com.validexia.identity.api;

import com.validexia.audit.AuditService;
import com.validexia.identity.application.ClerkBackendClient;
import com.validexia.identity.infrastructure.DirectoryRepository;
import com.validexia.platform.security.RequestContext;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Logout (M02-1 v2.6 §2): the session id is denylisted locally until well past any outstanding
 * token's expiry, and Clerk is asked to revoke the session too (best effort — the local denylist
 * is authoritative for this application).
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

  private final DirectoryRepository directory;
  private final ClerkBackendClient clerk;
  private final AuditService audit;

  public AuthController(
      DirectoryRepository directory, ClerkBackendClient clerk, AuditService audit) {
    this.directory = directory;
    this.clerk = clerk;
    this.audit = audit;
  }

  @PostMapping("/auth/logout")
  public ResponseEntity<Void> logout() {
    RequestContext.Context context = RequestContext.require();
    directory.recordSessionRevocation(
        context.sessionId(), context.subject(), Instant.now().plusSeconds(3600));
    clerk.revokeSession(context.sessionId());
    if (context.tenantId() != null) {
      audit.appendForTenant(
          context.tenantId(), context.userId(), "LOGOUT", "SESSION", context.sessionId(), "{}");
    } else {
      audit.appendSystem(
          "USER", context.userId().toString(), "LOGOUT", "SESSION", context.sessionId(), "{}");
    }
    return ResponseEntity.noContent().build();
  }
}
