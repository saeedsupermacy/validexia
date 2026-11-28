package com.validexia.access.api;

import com.validexia.access.application.AccessData.GrantData;
import com.validexia.access.application.AccessData.LinkData;
import com.validexia.access.application.AccessService;
import com.validexia.platform.errors.ApiException;
import com.validexia.platform.security.RequestContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Access-grant and evidence endpoints (M1, U1 freeze v1.4.1 §5.1 endpoints 7–18): the access
 * vendors hold into the tenant's internal systems, across the five independent axes (privilege
 * level, access method, source kind, verification status, lifecycle status), plus the evidence
 * links that support a grant. Scoped to the caller's per-request tenant; no delete — revocation and
 * unlinking are lifecycle states.
 */
@RestController
@RequestMapping("/api/v1/access-grants")
public class AccessGrantsController {

  private static final Set<String> PRIVILEGE_LEVELS = Set.of("Read", "Write", "Admin");
  private static final Set<String> ACCESS_METHODS =
      Set.of("Direct Login", "API", "Network", "File Exchange");
  private static final Set<String> SOURCE_KINDS =
      Set.of("Customer Entered", "Vendor Provided", "Unknown");
  private static final Set<String> LIFECYCLE_STATUSES = Set.of("Active", "Suspended", "Revoked");
  private static final Set<String> VERIFICATION_STATUSES =
      Set.of("Unverified", "Confirmed", "Rejected");

  public record GrantCreateRequest(
      UUID vendorId,
      UUID internalSystemId,
      String privilegeLevel,
      String accessMethod,
      String sourceKind) {}

  public record DiscoveryRequest(
      UUID vendorId,
      UUID internalSystemId,
      String privilegeLevel,
      String accessMethod,
      String sourceKind,
      String discoveryNote) {}

  public record ReasonRequest(String reason) {}

  public record LinkRequest(UUID documentId) {}

  public record AccessGrantView(
      UUID id,
      UUID vendorId,
      UUID internalSystemId,
      String privilegeLevel,
      String accessMethod,
      String sourceKind,
      String lifecycleStatus,
      String verificationStatus,
      UUID createdByUserId,
      @Nullable UUID verifiedByUserId,
      @Nullable Instant verifiedAt,
      @Nullable String rejectionReason,
      @Nullable UUID suspendedByUserId,
      @Nullable Instant suspendedAt,
      @Nullable String suspensionReason,
      @Nullable UUID reinstatedByUserId,
      @Nullable Instant reinstatedAt,
      @Nullable UUID revokedByUserId,
      @Nullable Instant revokedAt,
      @Nullable String revocationReason,
      @Nullable String discoveryNote,
      Instant createdAt,
      Instant updatedAt) {}

  public record EvidenceLinkView(
      UUID id,
      UUID grantId,
      UUID documentId,
      UUID linkedByUserId,
      Instant linkedAt,
      @Nullable UUID unlinkedByUserId,
      @Nullable Instant unlinkedAt,
      boolean active) {}

  private final AccessService access;

  public AccessGrantsController(AccessService access) {
    this.access = access;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AccessGrantView createGrant(@RequestBody GrantCreateRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    validateGrantFields(
        request.vendorId(),
        request.internalSystemId(),
        request.privilegeLevel(),
        request.accessMethod(),
        request.sourceKind());
    return toView(
        access.createGrant(
            tenantId,
            context.userId(),
            request.vendorId(),
            request.internalSystemId(),
            request.privilegeLevel(),
            request.accessMethod(),
            request.sourceKind()));
  }

  @PostMapping("/discoveries")
  @ResponseStatus(HttpStatus.CREATED)
  public AccessGrantView recordDiscovery(@RequestBody DiscoveryRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    validateGrantFields(
        request.vendorId(),
        request.internalSystemId(),
        request.privilegeLevel(),
        request.accessMethod(),
        request.sourceKind());
    if (request.discoveryNote() == null || request.discoveryNote().isBlank()) {
      throw ApiException.validation("discovery note is required");
    }
    return toView(
        access.recordDiscoveredGrant(
            tenantId,
            context.userId(),
            request.vendorId(),
            request.internalSystemId(),
            request.privilegeLevel(),
            request.accessMethod(),
            request.sourceKind(),
            request.discoveryNote()));
  }

  @PostMapping("/{grantId}/confirm")
  public AccessGrantView confirmGrant(@PathVariable UUID grantId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return toView(access.confirmGrant(tenantId, context.userId(), grantId));
  }

  @PostMapping("/{grantId}/reject")
  public AccessGrantView rejectGrant(
      @PathVariable UUID grantId, @RequestBody ReasonRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    requireReason(request.reason(), "rejection reason is required");
    return toView(access.rejectGrant(tenantId, context.userId(), grantId, request.reason()));
  }

  @PostMapping("/{grantId}/suspend")
  public AccessGrantView suspendGrant(
      @PathVariable UUID grantId, @RequestBody ReasonRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    requireReason(request.reason(), "suspension reason is required");
    return toView(access.suspendGrant(tenantId, context.userId(), grantId, request.reason()));
  }

  @PostMapping("/{grantId}/reinstate")
  public AccessGrantView reinstateGrant(@PathVariable UUID grantId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return toView(access.reinstateGrant(tenantId, context.userId(), grantId));
  }

  @PostMapping("/{grantId}/revoke")
  public AccessGrantView revokeGrant(
      @PathVariable UUID grantId, @RequestBody ReasonRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    requireReason(request.reason(), "revocation reason is required");
    return toView(access.revokeGrant(tenantId, context.userId(), grantId, request.reason()));
  }

  @GetMapping("/{grantId}")
  public AccessGrantView getGrant(@PathVariable UUID grantId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return toView(access.getGrant(tenantId, context.userId(), grantId));
  }

  @GetMapping
  public List<AccessGrantView> listGrants(
      @RequestParam(required = false) @Nullable UUID vendorId,
      @RequestParam(required = false) @Nullable UUID internalSystemId,
      @RequestParam(required = false) @Nullable String lifecycleStatus,
      @RequestParam(required = false) @Nullable String verificationStatus) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    if (lifecycleStatus != null && !LIFECYCLE_STATUSES.contains(lifecycleStatus)) {
      throw ApiException.validation("lifecycleStatus must be one of Active, Suspended, Revoked");
    }
    if (verificationStatus != null && !VERIFICATION_STATUSES.contains(verificationStatus)) {
      throw ApiException.validation(
          "verificationStatus must be one of Unverified, Confirmed, Rejected");
    }
    return access
        .listGrants(
            tenantId,
            context.userId(),
            vendorId,
            internalSystemId,
            lifecycleStatus,
            verificationStatus)
        .stream()
        .map(AccessGrantsController::toView)
        .toList();
  }

  @PostMapping("/{grantId}/evidence")
  @ResponseStatus(HttpStatus.CREATED)
  public EvidenceLinkView linkEvidence(
      @PathVariable UUID grantId, @RequestBody LinkRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    if (request.documentId() == null) {
      throw ApiException.validation("documentId is required");
    }
    return toView(access.linkEvidence(tenantId, context.userId(), grantId, request.documentId()));
  }

  @PostMapping("/{grantId}/evidence/{linkId}/unlink")
  public EvidenceLinkView unlinkEvidence(@PathVariable UUID grantId, @PathVariable UUID linkId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return toView(access.unlinkEvidence(tenantId, context.userId(), grantId, linkId));
  }

  @GetMapping("/{grantId}/evidence")
  public List<EvidenceLinkView> listEvidence(@PathVariable UUID grantId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return access.listEvidence(tenantId, context.userId(), grantId).stream()
        .map(AccessGrantsController::toView)
        .toList();
  }

  /** Controller validation mirrors the database contracts (freeze §5): 400s, not DB errors. */
  private static void validateGrantFields(
      @Nullable UUID vendorId,
      @Nullable UUID internalSystemId,
      @Nullable String privilegeLevel,
      @Nullable String accessMethod,
      @Nullable String sourceKind) {
    if (vendorId == null) {
      throw ApiException.validation("vendorId is required");
    }
    if (internalSystemId == null) {
      throw ApiException.validation("internalSystemId is required");
    }
    if (privilegeLevel == null || !PRIVILEGE_LEVELS.contains(privilegeLevel)) {
      throw ApiException.validation("privilegeLevel must be one of Read, Write, Admin");
    }
    if (accessMethod == null || !ACCESS_METHODS.contains(accessMethod)) {
      throw ApiException.validation(
          "accessMethod must be one of Direct Login, API, Network, File Exchange");
    }
    if (sourceKind == null || !SOURCE_KINDS.contains(sourceKind)) {
      throw ApiException.validation(
          "sourceKind must be one of Customer Entered, Vendor Provided, Unknown");
    }
  }

  private static void requireReason(@Nullable String reason, String message) {
    if (reason == null || reason.isBlank()) {
      throw ApiException.validation(message);
    }
  }

  private static AccessGrantView toView(GrantData grant) {
    return new AccessGrantView(
        grant.id(),
        grant.vendorId(),
        grant.internalSystemId(),
        grant.privilegeLevel(),
        grant.accessMethod(),
        grant.sourceKind(),
        grant.lifecycleStatus(),
        grant.verificationStatus(),
        grant.createdByUserId(),
        grant.verifiedByUserId(),
        grant.verifiedAt(),
        grant.rejectionReason(),
        grant.suspendedByUserId(),
        grant.suspendedAt(),
        grant.suspensionReason(),
        grant.reinstatedByUserId(),
        grant.reinstatedAt(),
        grant.revokedByUserId(),
        grant.revokedAt(),
        grant.revocationReason(),
        grant.discoveryNote(),
        grant.createdAt(),
        grant.updatedAt());
  }

  private static EvidenceLinkView toView(LinkData link) {
    return new EvidenceLinkView(
        link.id(),
        link.grantId(),
        link.documentId(),
        link.linkedByUserId(),
        link.createdAt(),
        link.unlinkedByUserId(),
        link.unlinkedAt(),
        link.active());
  }

  private static UUID requireTenant(RequestContext.Context context) {
    if (context.tenantId() == null) {
      throw ApiException.validation("X-Tenant-Id header is required for access endpoints");
    }
    return context.tenantId();
  }
}
