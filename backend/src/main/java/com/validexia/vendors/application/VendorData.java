package com.validexia.vendors.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A vendor as the platform stores it: identity and lifecycle only. Risk scores, evidence status and
 * assessment state deliberately do NOT live here — they belong to the assessments/evidence slices
 * and the deterministic scoring design (migration V12).
 */
public record VendorData(
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
    boolean tieringComplete,
    Instant createdAt,
    Instant updatedAt) {

  /** A business system that depends on the vendor (frontend: dependentSystems). */
  public record DependentSystem(
      UUID id, String name, @Nullable String dataHandled, @Nullable String residency) {}

  /** A dependent system as supplied by a caller (no id yet). */
  public record DependentSystemInput(
      String name, @Nullable String dataHandled, @Nullable String residency) {}
}
