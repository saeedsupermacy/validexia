package com.validexia.identity.application;

import com.validexia.audit.AuditService;
import com.validexia.identity.infrastructure.DirectoryRepository;
import com.validexia.identity.infrastructure.DirectoryRepository.MembershipView;
import com.validexia.platform.security.RequestContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Session-facing application service: the controllers' only path to directory data (layering
 * rule — repositories are used by services, never by the API layer directly).
 */
@Service
public class SessionService {

  private final DirectoryRepository directory;
  private final ClerkBackendClient clerk;
  private final AuditService audit;

  public SessionService(DirectoryRepository directory, ClerkBackendClient clerk, AuditService audit) {
    this.directory = directory;
    this.clerk = clerk;
    this.audit = audit;
  }

  /** The service layer's own view of a membership — the API layer never sees repository types. */
  public record Membership(UUID tenantId, String tenantName, String role, String status) {}

  public List<Membership> memberships(String subject) {
    return directory.listMemberships(subject).stream().map(SessionService::toMembership).toList();
  }

  public Optional<Membership> activeMembership(String subject, UUID tenantId) {
    return directory.listMemberships(subject).stream()
        .filter(m -> m.tenantId().equals(tenantId) && "active".equals(m.status()))
        .map(SessionService::toMembership)
        .findFirst();
  }

  private static Membership toMembership(MembershipView view) {
    return new Membership(view.tenantId(), view.tenantName(), view.role(), view.status());
  }

  /** Establishing a tenant context is audited in that tenant's chain. */
  public void recordTenantContextEstablished(Membership membership, RequestContext.Context context) {
    audit.appendForTenant(
        membership.tenantId(),
        context.userId(),
        "TENANT_CONTEXT_ESTABLISHED",
        "SESSION",
        context.sessionId(),
        "{\"method\": \"clerk\"}");
  }

  /** Logout: local denylist first (authoritative), Clerk revocation second (best effort). */
  public void logout(RequestContext.Context context) {
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
  }
}
