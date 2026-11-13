package com.validexia.vendors.api;

import com.validexia.platform.errors.ApiException;
import com.validexia.platform.security.RequestContext;
import com.validexia.vendors.application.VendorData;
import com.validexia.vendors.infrastructure.VendorsRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vendor endpoints — the first domain slice of the platform data layer (migration V12). Everything
 * is scoped to the caller's per-request tenant (the {@code X-Tenant-Id} header, verified by the
 * authentication filter); there is no cross-tenant view and no delete: termination is a lifecycle
 * status, so vendor history survives for audit and dependency records.
 */
@RestController
@RequestMapping("/api/v1/vendors")
public class VendorController {

  public record VendorCreateRequest(
      String name,
      @Nullable String legalName,
      @Nullable String lei,
      @Nullable String jurisdiction,
      String code,
      @Nullable String category,
      @Nullable String domain,
      @Nullable String website,
      @Nullable String description,
      @Nullable String criticality,
      @Nullable UUID ownerUserId,
      @Nullable LocalDate contractStart,
      @Nullable LocalDate contractEnd,
      boolean tieringComplete) {}

  public record VendorUpdateRequest(
      String name,
      @Nullable String legalName,
      @Nullable String lei,
      @Nullable String jurisdiction,
      @Nullable String category,
      @Nullable String domain,
      @Nullable String website,
      @Nullable String description,
      @Nullable String criticality,
      @Nullable UUID ownerUserId,
      @Nullable LocalDate contractStart,
      @Nullable LocalDate contractEnd,
      boolean tieringComplete) {}

  public record VendorView(
      UUID id,
      String name,
      @Nullable String legalName,
      @Nullable String lei,
      @Nullable String jurisdiction,
      String code,
      @Nullable String category,
      @Nullable String domain,
      @Nullable String website,
      @Nullable String description,
      String criticality,
      @Nullable UUID ownerUserId,
      String status,
      @Nullable LocalDate contractStart,
      @Nullable LocalDate contractEnd,
      boolean tieringComplete) {}

  public record DependentSystemRequest(
      String name, @Nullable String dataHandled, @Nullable String residency) {}

  public record DependentSystemView(
      UUID id, String name, @Nullable String dataHandled, @Nullable String residency) {}

  private final VendorsRepository vendors;

  public VendorController(VendorsRepository vendors) {
    this.vendors = vendors;
  }

  @PostMapping
  public VendorView createVendor(@RequestBody VendorCreateRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    if (request.name() == null || request.name().isBlank()) {
      throw ApiException.validation("name is required");
    }
    if (request.code() == null || request.code().isBlank()) {
      throw ApiException.validation("code is required");
    }
    UUID id;
    try {
      id =
          vendors.createVendor(
              tenantId,
              context.userId(),
              request.name(),
              request.legalName(),
              request.lei(),
              request.jurisdiction(),
              request.code(),
              request.category(),
              request.domain(),
              request.website(),
              request.description(),
              request.criticality(),
              request.ownerUserId(),
              request.contractStart(),
              request.contractEnd(),
              request.tieringComplete());
    } catch (DataIntegrityViolationException e) {
      throw ApiException.validation("a vendor with this code already exists in the tenant");
    }
    return toView(requireVendor(tenantId, context.userId(), id));
  }

  @GetMapping
  public List<VendorView> listVendors() {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return vendors.listVendors(tenantId, context.userId()).stream()
        .map(VendorController::toView)
        .toList();
  }

  @GetMapping("/{vendorId}")
  public VendorView getVendor(@PathVariable UUID vendorId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return toView(requireVendor(tenantId, context.userId(), vendorId));
  }

  @PutMapping("/{vendorId}")
  public VendorView updateVendor(
      @PathVariable UUID vendorId, @RequestBody VendorUpdateRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    if (request.name() == null || request.name().isBlank()) {
      throw ApiException.validation("name is required");
    }
    boolean updated =
        vendors.updateVendor(
            tenantId,
            context.userId(),
            vendorId,
            request.name(),
            request.legalName(),
            request.lei(),
            request.jurisdiction(),
            request.category(),
            request.domain(),
            request.website(),
            request.description(),
            request.criticality(),
            request.ownerUserId(),
            request.contractStart(),
            request.contractEnd(),
            request.tieringComplete());
    if (!updated) {
      throw notFound(vendorId);
    }
    return toView(requireVendor(tenantId, context.userId(), vendorId));
  }

  @PostMapping("/{vendorId}/terminate")
  public VendorView terminateVendor(@PathVariable UUID vendorId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    if (!vendors.terminateVendor(tenantId, context.userId(), vendorId)) {
      throw notFound(vendorId);
    }
    return toView(requireVendor(tenantId, context.userId(), vendorId));
  }

  @GetMapping("/{vendorId}/dependent-systems")
  public List<DependentSystemView> listDependentSystems(@PathVariable UUID vendorId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    requireVendor(tenantId, context.userId(), vendorId);
    return vendors.listDependentSystems(tenantId, context.userId(), vendorId).stream()
        .map(
            system ->
                new DependentSystemView(
                    system.id(), system.name(), system.dataHandled(), system.residency()))
        .toList();
  }

  @PutMapping("/{vendorId}/dependent-systems")
  public List<DependentSystemView> replaceDependentSystems(
      @PathVariable UUID vendorId, @RequestBody List<DependentSystemRequest> request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    for (DependentSystemRequest system : request) {
      if (system.name() == null || system.name().isBlank()) {
        throw ApiException.validation("every dependent system needs a name");
      }
    }
    boolean replaced =
        vendors.replaceDependentSystems(
            tenantId,
            context.userId(),
            vendorId,
            request.stream()
                .map(
                    system ->
                        new VendorData.DependentSystemInput(
                            system.name(), system.dataHandled(), system.residency()))
                .toList());
    if (!replaced) {
      throw notFound(vendorId);
    }
    return listDependentSystems(vendorId);
  }

  private VendorData requireVendor(UUID tenantId, UUID userId, UUID vendorId) {
    return vendors.getVendor(tenantId, userId, vendorId).orElseThrow(() -> notFound(vendorId));
  }

  private static ApiException notFound(UUID vendorId) {
    return new ApiException(HttpStatus.NOT_FOUND, "vendor_not_found", "No vendor " + vendorId);
  }

  private static VendorView toView(VendorData vendor) {
    return new VendorView(
        vendor.id(),
        vendor.name(),
        vendor.legalName(),
        vendor.lei(),
        vendor.jurisdiction(),
        vendor.code(),
        vendor.category(),
        vendor.domain(),
        vendor.website(),
        vendor.description(),
        vendor.criticality(),
        vendor.ownerUserId(),
        vendor.status(),
        vendor.contractStart(),
        vendor.contractEnd(),
        vendor.tieringComplete());
  }

  private static UUID requireTenant(RequestContext.Context context) {
    if (context.tenantId() == null) {
      throw ApiException.validation("X-Tenant-Id header is required for vendor endpoints");
    }
    return context.tenantId();
  }
}
