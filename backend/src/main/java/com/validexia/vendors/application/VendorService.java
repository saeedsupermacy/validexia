package com.validexia.vendors.application;

import com.validexia.vendors.infrastructure.VendorsRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Application layer for the vendors slice: the API layer talks to this service, never to the
 * repository directly (architecture rule: controllers do not use data access). Today it is a thin
 * delegation over {@link VendorsRepository}; it is where vendor-domain rules that span calls (for
 * example, dependent-system replacement semantics) live as the slice grows.
 */
@Service
public class VendorService {

  private final VendorsRepository vendors;

  public VendorService(VendorsRepository vendors) {
    this.vendors = vendors;
  }

  public UUID createVendor(
      UUID tenantId,
      UUID userId,
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
      boolean tieringComplete) {
    return vendors.createVendor(
        tenantId,
        userId,
        name,
        legalName,
        lei,
        jurisdiction,
        code,
        category,
        domain,
        website,
        description,
        criticality,
        ownerUserId,
        contractStart,
        contractEnd,
        tieringComplete);
  }

  public boolean updateVendor(
      UUID tenantId,
      UUID userId,
      UUID vendorId,
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
      boolean tieringComplete) {
    return vendors.updateVendor(
        tenantId,
        userId,
        vendorId,
        name,
        legalName,
        lei,
        jurisdiction,
        category,
        domain,
        website,
        description,
        criticality,
        ownerUserId,
        contractStart,
        contractEnd,
        tieringComplete);
  }

  public boolean terminateVendor(UUID tenantId, UUID userId, UUID vendorId) {
    return vendors.terminateVendor(tenantId, userId, vendorId);
  }

  public Optional<VendorData> getVendor(UUID tenantId, UUID userId, UUID vendorId) {
    return vendors.getVendor(tenantId, userId, vendorId);
  }

  public List<VendorData> listVendors(UUID tenantId, UUID userId) {
    return vendors.listVendors(tenantId, userId);
  }

  public boolean replaceDependentSystems(
      UUID tenantId, UUID userId, UUID vendorId, List<VendorData.DependentSystemInput> systems) {
    return vendors.replaceDependentSystems(tenantId, userId, vendorId, systems);
  }

  public List<VendorData.DependentSystem> listDependentSystems(
      UUID tenantId, UUID userId, UUID vendorId) {
    return vendors.listDependentSystems(tenantId, userId, vendorId);
  }
}
