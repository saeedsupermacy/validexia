package com.validexia.identity.api;

import com.validexia.audit.AuditService;
import com.validexia.identity.infrastructure.DirectoryRepository;
import com.validexia.identity.infrastructure.DirectoryRepository.MembershipView;
import com.validexia.platform.errors.ApiException;
import com.validexia.platform.security.RequestContext;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session + tenant-selection endpoints (M02-1 v2.6 §5). */
@RestController
@RequestMapping("/api/v1")
public class SessionController {

  public record UserView(UUID id, String subject) {}

  public record MembershipDto(UUID tenantId, String tenantName, String role, String status) {}

  public record SessionResponse(
      UserView user, List<MembershipDto> memberships, @Nullable UUID activeTenantId) {}

  public record ActiveTenantRequest(@Nullable UUID tenantId) {}

  public record ActiveTenantResponse(UUID tenantId, String tenantName, String role) {}

  private final DirectoryRepository directory;
  private final AuditService audit;

  public SessionController(DirectoryRepository directory, AuditService audit) {
    this.directory = directory;
    this.audit = audit;
  }

  @GetMapping("/session")
  public SessionResponse session() {
    RequestContext.Context context = RequestContext.require();
    List<MembershipDto> memberships =
        directory.listMemberships(context.subject()).stream()
            .map(SessionController::toDto)
            .toList();
    return new SessionResponse(
        new UserView(context.userId(), context.subject()), memberships, context.tenantId());
  }

  /**
   * Validates the requested tenant selection against the caller's memberships. Selection is
   * per-request and stateless (the client sends {@code X-Tenant-Id} on subsequent calls); this
   * endpoint exists so the client can establish and audit the choice.
   */
  @PostMapping("/session/active-tenant")
  public ActiveTenantResponse selectActiveTenant(@RequestBody ActiveTenantRequest request) {
    if (request.tenantId() == null) {
      throw ApiException.validation("tenantId is required");
    }
    RequestContext.Context context = RequestContext.require();
    MembershipView membership =
        directory.listMemberships(context.subject()).stream()
            .filter(m -> m.tenantId().equals(request.tenantId()) && "active".equals(m.status()))
            .findFirst()
            .orElseThrow(
                () ->
                    ApiException.forbidden(
                        "TENANT_ACCESS_DENIED", "Not an active member of the selected tenant"));
    audit.appendForTenant(
        membership.tenantId(),
        context.userId(),
        "TENANT_CONTEXT_ESTABLISHED",
        "SESSION",
        context.sessionId(),
        "{\"method\": \"clerk\"}");
    return new ActiveTenantResponse(
        membership.tenantId(), membership.tenantName(), membership.role());
  }

  private static MembershipDto toDto(MembershipView view) {
    return new MembershipDto(view.tenantId(), view.tenantName(), view.role(), view.status());
  }
}
