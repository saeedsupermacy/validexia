package com.validexia.access.application;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The access module's data records (M1, U1 freeze v1.4.1): rows as the V16 SECURITY DEFINER
 * functions return them, plus the transition-result contract of freeze §9. The two models —
 * internal systems and vendor access grants — are separate records and are never merged.
 */
public final class AccessData {

  private AccessData() {}

  /** An internal system row (S5/S6 shape; also S2's returned before-image). */
  public record SystemData(
      UUID id,
      String name,
      @Nullable String description,
      @Nullable UUID ownerUserId,
      String status,
      @Nullable UUID retiredByUserId,
      @Nullable Instant retiredAt,
      Instant createdAt,
      Instant updatedAt) {}

  /** A system plus the service-computed count of its Active grants (freeze §5.2). */
  public record SystemViewData(SystemData system, long activeGrantCount) {}

  /** An access grant row (G8/G9 shape): all five axes and all lifecycle metadata. */
  public record GrantData(
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

  /** An evidence link row (E3 shape). {@code active} is derived: not yet unlinked. */
  public record LinkData(
      UUID id,
      UUID grantId,
      UUID documentId,
      UUID linkedByUserId,
      Instant createdAt,
      @Nullable UUID unlinkedByUserId,
      @Nullable Instant unlinkedAt) {

    public boolean active() {
      return unlinkedAt == null;
    }
  }

  /** Why a transition was refused, when the row's own status does not explain it (freeze §9). */
  public enum Blocker {
    NONE,
    VENDOR_TERMINATED,
    ALREADY_UNLINKED
  }

  /**
   * The §9 transition-result contract: everything the facade needs to interpret a transition
   * outcome, captured by the worker inside its transaction. {@code currentStatus} is the target
   * row's status at the moment of refusal (read back in-transaction under the function's held row
   * lock); it is null when the transition applied.
   */
  public record TransitionResult(boolean applied, @Nullable String currentStatus, Blocker blocker) {

    public static TransitionResult appliedResult() {
      return new TransitionResult(true, null, Blocker.NONE);
    }

    public static TransitionResult refused(@Nullable String currentStatus, Blocker blocker) {
      return new TransitionResult(false, currentStatus, blocker);
    }
  }
}
