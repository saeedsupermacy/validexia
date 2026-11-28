package com.validexia.access.api;

import com.validexia.access.application.AccessData.SystemViewData;
import com.validexia.access.application.AccessService;
import com.validexia.platform.errors.ApiException;
import com.validexia.platform.security.RequestContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal-system endpoints (M1, U1 freeze v1.4.1 §5.1 endpoints 1–6): the tenant's own systems
 * inventory. Everything is scoped to the caller's per-request tenant (the {@code X-Tenant-Id}
 * header, verified by the authentication filter); there is no cross-tenant view and no delete —
 * retirement is a lifecycle status.
 */
@RestController
@RequestMapping("/api/v1/internal-systems")
public class InternalSystemsController {

  public record SystemRequest(
      String name, @Nullable String description, @Nullable UUID ownerUserId) {}

  public record InternalSystemView(
      UUID id,
      String name,
      @Nullable String description,
      @Nullable UUID ownerUserId,
      String status,
      @Nullable UUID retiredByUserId,
      @Nullable Instant retiredAt,
      long activeGrantCount,
      Instant createdAt,
      Instant updatedAt) {}

  private final AccessService access;

  public InternalSystemsController(AccessService access) {
    this.access = access;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public InternalSystemView createSystem(@RequestBody SystemRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    requireName(request.name());
    return toView(
        access.createSystem(
            tenantId,
            context.userId(),
            request.name(),
            request.description(),
            request.ownerUserId()));
  }

  @PutMapping("/{systemId}")
  public InternalSystemView updateSystem(
      @PathVariable UUID systemId, @RequestBody SystemRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    requireName(request.name());
    // Full replacement (freeze §5.1): omitted optional fields clear, never preserve.
    return toView(
        access.updateSystem(
            tenantId,
            context.userId(),
            systemId,
            request.name(),
            request.description(),
            request.ownerUserId()));
  }

  @PostMapping("/{systemId}/retire")
  public InternalSystemView retireSystem(@PathVariable UUID systemId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return toView(access.retireSystem(tenantId, context.userId(), systemId));
  }

  @PostMapping("/{systemId}/restore")
  public InternalSystemView restoreSystem(@PathVariable UUID systemId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return toView(access.restoreSystem(tenantId, context.userId(), systemId));
  }

  @GetMapping("/{systemId}")
  public InternalSystemView getSystem(@PathVariable UUID systemId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return toView(access.getSystem(tenantId, context.userId(), systemId));
  }

  @GetMapping
  public List<InternalSystemView> listSystems() {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return access.listSystems(tenantId, context.userId()).stream()
        .map(InternalSystemsController::toView)
        .toList();
  }

  private static void requireName(@Nullable String name) {
    if (name == null || name.isBlank()) {
      throw ApiException.validation("name is required");
    }
  }

  private static InternalSystemView toView(SystemViewData data) {
    return new InternalSystemView(
        data.system().id(),
        data.system().name(),
        data.system().description(),
        data.system().ownerUserId(),
        data.system().status(),
        data.system().retiredByUserId(),
        data.system().retiredAt(),
        data.activeGrantCount(),
        data.system().createdAt(),
        data.system().updatedAt());
  }

  private static UUID requireTenant(RequestContext.Context context) {
    if (context.tenantId() == null) {
      throw ApiException.validation("X-Tenant-Id header is required for access endpoints");
    }
    return context.tenantId();
  }
}
