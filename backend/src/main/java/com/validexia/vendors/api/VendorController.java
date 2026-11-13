package com.validexia.vendors.api;

import com.validexia.platform.errors.ApiException;
import com.validexia.platform.security.RequestContext;
import com.validexia.vendors.application.VendorData;
import com.validexia.vendors.application.VendorService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
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

  private static final Set<String> CRITICALITIES = Set.of("Tier 1", "Tier 2", "Tier 3");

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

  /**
   * Full-replace update body. Unlike create — where the database defaults a missing criticality to
   * Tier 3 — {@code criticality} is required here: a replace that omits it is a client error, not a
   * silent reset.
   */
  public record VendorUpdateRequest(
      String name,
      @Nullable String legalName,
      @Nullable String lei,
      @Nullable String jurisdiction,
      @Nullable String category,
      @Nullable String domain,
      @Nullable String website,
      @Nullable String description,
      String criticality,
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

  private final VendorService vendors;

  public VendorController(VendorService vendors) {
    this.vendors = vendors;
  }

  @PostMapping
  public VendorView createVendor(@RequestBody VendorCreateRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    validateFields(
        request.name(),
        request.criticality(),
        request.lei(),
        request.contractStart(),
        request.contractEnd(),
        false);
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
      throw integrityViolation(e);
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
    validateFields(
        request.name(),
        request.criticality(),
        request.lei(),
        request.contractStart(),
        request.contractEnd(),
        true);
    boolean updated;
    try {
      updated =
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
    } catch (DataIntegrityViolationException e) {
      throw integrityViolation(e);
    }
    if (!updated) {
      throw notFound(vendorId);
    }
    return toView(requireVendor(tenantId, context.userId(), vendorId));
  }

  @PostMapping("/{vendorId}/terminate")
  public VendorView terminateVendor(@PathVariable UUID vendorId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    vendors.terminateVendor(tenantId, context.userId(), vendorId);
    // Termination is idempotent: terminating an already-terminated vendor is not an error — the
    // vendor exists and is in the desired end state, so its view is returned. requireVendor
    // throws 404 only when the vendor does not exist in this tenant at all.
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

  /**
   * Field validation shared by create and update, so routine client mistakes are 400s with an
   * accurate message instead of database errors. {@code criticalityRequired} is true for the
   * full-replace update (no default applies there) and false for create (the database defaults a
   * missing criticality to Tier 3).
   */
  private static void validateFields(
      @Nullable String name,
      @Nullable String criticality,
      @Nullable String lei,
      @Nullable LocalDate contractStart,
      @Nullable LocalDate contractEnd,
      boolean criticalityRequired) {
    if (name == null || name.isBlank()) {
      throw ApiException.validation("name is required");
    }
    if (criticality == null) {
      if (criticalityRequired) {
        throw ApiException.validation("criticality is required");
      }
    } else if (!CRITICALITIES.contains(criticality)) {
      throw ApiException.validation("criticality must be one of Tier 1, Tier 2, Tier 3");
    }
    if (lei != null && lei.length() != 20) {
      throw ApiException.validation("lei must be exactly 20 characters");
    }
    if (contractStart != null && contractEnd != null && contractEnd.isBefore(contractStart)) {
      throw ApiException.validation("contractEnd must not be before contractStart");
    }
  }

  /**
   * Maps a database integrity failure onto an accurate 400. The PostgreSQL server error in the
   * cause chain names the violated constraint, and each known constraint gets its own message — a
   * client is never told "duplicate code" for an unrelated problem. The owner-membership failure
   * raised by the V12 functions carries no constraint name, so it is recognised by its message
   * text. Anything unrecognised still maps to a generic (but accurate) constraint message: client
   * data must never surface as a 500.
   */
  private static ApiException integrityViolation(DataIntegrityViolationException failure) {
    PSQLException pg = findPostgresError(failure);
    if (pg != null) {
      ServerErrorMessage serverError = pg.getServerErrorMessage();
      if (serverError != null) {
        String constraint = serverError.getConstraint();
        String message =
            switch (constraint == null ? "" : constraint) {
              case "vendors_tenant_id_code_key" ->
                  "a vendor with this code already exists in the tenant";
              case "vendors_criticality_check" ->
                  "criticality must be one of Tier 1, Tier 2, Tier 3";
              case "vendors_lei_check" -> "lei must be exactly 20 characters";
              case "vendors_check" -> "contractEnd must not be before contractStart";
              case "vendors_owner_user_id_fkey" ->
                  "ownerUserId does not reference an existing user";
              default -> null;
            };
        if (message != null) {
          return ApiException.validation(message);
        }
        String serverMessage = serverError.getMessage();
        if (serverMessage != null
            && serverMessage.contains("owner is not a member of the tenant")) {
          return ApiException.validation("owner is not a member of the tenant");
        }
      }
    }
    return ApiException.validation("the request violates a data constraint");
  }

  private static @Nullable PSQLException findPostgresError(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof PSQLException pg) {
        return pg;
      }
      current = current.getCause();
    }
    return null;
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
