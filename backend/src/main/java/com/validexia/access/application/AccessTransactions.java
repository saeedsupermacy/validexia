package com.validexia.access.application;

import com.validexia.access.application.AccessData.Blocker;
import com.validexia.access.application.AccessData.GrantData;
import com.validexia.access.application.AccessData.SystemData;
import com.validexia.access.application.AccessData.TransitionResult;
import com.validexia.access.infrastructure.AccessRepository;
import com.validexia.audit.AuditService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The transactional worker for M1 mutations (U1 freeze v1.4.1 §9). This is a separate Spring bean
 * from the {@link AccessService} facade, so every call crosses the transaction proxy —
 * self-invocation is structurally impossible. The facade sets the tenant context BEFORE calling a
 * method here; the {@code TenantTransactionManager} turns it into the transaction's first {@code
 * set_config} statements.
 *
 * <p>Inside each method, in order: (1) the V16 function call; (2) on success only, the §6 audit
 * append via {@link AuditService#appendForTenant} — structurally last, in the same transaction (its
 * REQUIRED template joins this one), so mutation and event commit or roll back together. No
 * exception is caught inside this bean: in particular a unique violation propagates out, the proxy
 * rolls the transaction back, and the facade translates it afterwards (§5.3).
 *
 * <p>Transition methods never return a bare boolean: refusals are reported as {@link
 * TransitionResult} with the row's status read back inside this transaction, while the transition
 * function still holds the row's {@code FOR UPDATE} lock — so the status the facade reports is the
 * very version the function's guard evaluated (§9, v1.4 lock guarantee).
 */
@Service
@Transactional
public class AccessTransactions {

  private final AccessRepository access;
  private final AuditService audit;
  private final ObjectMapper json;

  public AccessTransactions(AccessRepository access, AuditService audit, ObjectMapper json) {
    this.access = access;
    this.audit = audit;
    this.json = json;
  }

  // --- Internal systems -----------------------------------------------------

  public UUID createSystem(
      UUID tenantId,
      UUID actorId,
      @Nullable String name,
      @Nullable String description,
      @Nullable UUID ownerUserId) {
    UUID id = access.createSystem(tenantId, name, description, ownerUserId, actorId);
    append(
        tenantId, actorId, "INTERNAL_SYSTEM_CREATED", "internal_system", id, details("name", name));
    return id;
  }

  public TransitionResult updateSystem(
      UUID tenantId,
      UUID actorId,
      UUID systemId,
      @Nullable String name,
      @Nullable String description,
      @Nullable UUID ownerUserId) {
    Optional<SystemData> before =
        access.updateSystem(tenantId, systemId, name, description, ownerUserId, actorId);
    if (before.isEmpty()) {
      // Refused (Retired): read the locked row's status back in this transaction.
      String status = access.getSystem(tenantId, systemId).map(SystemData::status).orElse(null);
      return TransitionResult.refused(status, Blocker.NONE);
    }
    SystemData after = access.getSystem(tenantId, systemId).orElseThrow();
    Map<String, Object> changes = new LinkedHashMap<>();
    if (!before.get().name().equals(after.name())) {
      changes.put("name", details("from", before.get().name(), "to", after.name()));
    }
    if (!java.util.Objects.equals(before.get().description(), after.description())) {
      changes.put(
          "description", details("from", before.get().description(), "to", after.description()));
    }
    if (!java.util.Objects.equals(before.get().ownerUserId(), after.ownerUserId())) {
      changes.put(
          "ownerUserId",
          details(
              "from",
              before.get().ownerUserId() == null ? null : before.get().ownerUserId().toString(),
              "to",
              after.ownerUserId() == null ? null : after.ownerUserId().toString()));
    }
    append(tenantId, actorId, "INTERNAL_SYSTEM_UPDATED", "internal_system", systemId, changes);
    return TransitionResult.appliedResult();
  }

  public TransitionResult retireSystem(UUID tenantId, UUID actorId, UUID systemId) {
    boolean applied = access.retireSystem(tenantId, systemId, actorId);
    if (!applied) {
      String status = access.getSystem(tenantId, systemId).map(SystemData::status).orElse(null);
      return TransitionResult.refused(status, Blocker.NONE);
    }
    long activeGrantCount =
        access.listGrants(tenantId, null, systemId).stream()
            .filter(grant -> "Active".equals(grant.lifecycleStatus()))
            .count();
    append(
        tenantId,
        actorId,
        "INTERNAL_SYSTEM_RETIRED",
        "internal_system",
        systemId,
        details("from", "Active", "to", "Retired", "activeGrantCount", activeGrantCount));
    return TransitionResult.appliedResult();
  }

  public TransitionResult restoreSystem(UUID tenantId, UUID actorId, UUID systemId) {
    boolean applied = access.restoreSystem(tenantId, systemId, actorId);
    if (!applied) {
      String status = access.getSystem(tenantId, systemId).map(SystemData::status).orElse(null);
      return TransitionResult.refused(status, Blocker.NONE);
    }
    append(
        tenantId,
        actorId,
        "INTERNAL_SYSTEM_RESTORED",
        "internal_system",
        systemId,
        details("from", "Retired", "to", "Active"));
    return TransitionResult.appliedResult();
  }

  // --- Access grants ----------------------------------------------------------

  public UUID createGrant(
      UUID tenantId,
      UUID actorId,
      UUID vendorId,
      UUID systemId,
      String privilegeLevel,
      String accessMethod,
      String sourceKind) {
    UUID id =
        access.createGrant(
            tenantId, vendorId, systemId, privilegeLevel, accessMethod, sourceKind, actorId);
    append(
        tenantId,
        actorId,
        "ACCESS_GRANT_CREATED",
        "access_grant",
        id,
        classificationDetails(vendorId, systemId, privilegeLevel, accessMethod, sourceKind));
    return id;
  }

  public UUID recordDiscoveredGrant(
      UUID tenantId,
      UUID actorId,
      UUID vendorId,
      UUID systemId,
      String privilegeLevel,
      String accessMethod,
      String sourceKind,
      String discoveryNote) {
    UUID id =
        access.recordDiscoveredGrant(
            tenantId,
            vendorId,
            systemId,
            privilegeLevel,
            accessMethod,
            sourceKind,
            discoveryNote,
            actorId);
    append(
        tenantId,
        actorId,
        "ACCESS_GRANT_DISCOVERED",
        "access_grant",
        id,
        classificationDetails(vendorId, systemId, privilegeLevel, accessMethod, sourceKind));
    return id;
  }

  public TransitionResult confirmGrant(UUID tenantId, UUID actorId, UUID grantId) {
    Optional<GrantData> before = access.getGrant(tenantId, grantId);
    boolean applied = access.confirmGrant(tenantId, grantId, actorId);
    if (!applied) {
      String status =
          access.getGrant(tenantId, grantId).map(GrantData::verificationStatus).orElse(null);
      return TransitionResult.refused(status, Blocker.NONE);
    }
    append(
        tenantId,
        actorId,
        "ACCESS_GRANT_CONFIRMED",
        "access_grant",
        grantId,
        details("from", before.orElseThrow().verificationStatus(), "to", "Confirmed"));
    return TransitionResult.appliedResult();
  }

  public TransitionResult rejectGrant(UUID tenantId, UUID actorId, UUID grantId, String reason) {
    Optional<GrantData> before = access.getGrant(tenantId, grantId);
    boolean applied = access.rejectGrant(tenantId, grantId, reason, actorId);
    if (!applied) {
      String status =
          access.getGrant(tenantId, grantId).map(GrantData::verificationStatus).orElse(null);
      return TransitionResult.refused(status, Blocker.NONE);
    }
    append(
        tenantId,
        actorId,
        "ACCESS_GRANT_REJECTED",
        "access_grant",
        grantId,
        details(
            "from", before.orElseThrow().verificationStatus(), "to", "Rejected", "reason", reason));
    return TransitionResult.appliedResult();
  }

  public TransitionResult suspendGrant(UUID tenantId, UUID actorId, UUID grantId, String reason) {
    boolean applied = access.suspendGrant(tenantId, grantId, reason, actorId);
    if (!applied) {
      String status =
          access.getGrant(tenantId, grantId).map(GrantData::lifecycleStatus).orElse(null);
      return TransitionResult.refused(status, Blocker.NONE);
    }
    append(
        tenantId,
        actorId,
        "ACCESS_GRANT_SUSPENDED",
        "access_grant",
        grantId,
        details("from", "Active", "to", "Suspended", "reason", reason));
    return TransitionResult.appliedResult();
  }

  public TransitionResult reinstateGrant(UUID tenantId, UUID actorId, UUID grantId) {
    boolean applied = access.reinstateGrant(tenantId, grantId, actorId);
    if (!applied) {
      // The read-back runs while G6's row lock is still held by this transaction, so the
      // status is the version G6's guard evaluated. A grant read back as Suspended could
      // only have been refused by the vendor condition folded into G6's deciding UPDATE
      // (§9): the blocker is the terminated vendor, not the grant's own status.
      GrantData now = access.getGrant(tenantId, grantId).orElseThrow();
      Blocker blocker =
          "Suspended".equals(now.lifecycleStatus()) ? Blocker.VENDOR_TERMINATED : Blocker.NONE;
      return TransitionResult.refused(now.lifecycleStatus(), blocker);
    }
    append(
        tenantId,
        actorId,
        "ACCESS_GRANT_REINSTATED",
        "access_grant",
        grantId,
        details("from", "Suspended", "to", "Active"));
    return TransitionResult.appliedResult();
  }

  public TransitionResult revokeGrant(UUID tenantId, UUID actorId, UUID grantId, String reason) {
    Optional<GrantData> before = access.getGrant(tenantId, grantId);
    boolean applied = access.revokeGrant(tenantId, grantId, reason, actorId);
    if (!applied) {
      String status =
          access.getGrant(tenantId, grantId).map(GrantData::lifecycleStatus).orElse(null);
      return TransitionResult.refused(status, Blocker.NONE);
    }
    append(
        tenantId,
        actorId,
        "ACCESS_GRANT_REVOKED",
        "access_grant",
        grantId,
        details("from", before.orElseThrow().lifecycleStatus(), "to", "Revoked", "reason", reason));
    return TransitionResult.appliedResult();
  }

  // --- Evidence ---------------------------------------------------------------

  public UUID linkEvidence(UUID tenantId, UUID actorId, UUID grantId, UUID documentId) {
    UUID id = access.linkEvidence(tenantId, grantId, documentId, actorId);
    append(
        tenantId,
        actorId,
        "ACCESS_GRANT_EVIDENCE_LINKED",
        "access_grant_evidence",
        id,
        details("grantId", grantId.toString(), "documentId", documentId.toString()));
    return id;
  }

  public TransitionResult unlinkEvidence(
      UUID tenantId, UUID actorId, UUID grantId, UUID linkId, UUID documentId) {
    boolean applied = access.unlinkEvidence(tenantId, linkId, actorId);
    if (!applied) {
      return TransitionResult.refused(null, Blocker.ALREADY_UNLINKED);
    }
    append(
        tenantId,
        actorId,
        "ACCESS_GRANT_EVIDENCE_UNLINKED",
        "access_grant_evidence",
        linkId,
        details("grantId", grantId.toString(), "documentId", documentId.toString()));
    return TransitionResult.appliedResult();
  }

  // --- Helpers ------------------------------------------------------------------

  private Map<String, Object> classificationDetails(
      UUID vendorId, UUID systemId, String privilegeLevel, String accessMethod, String sourceKind) {
    return details(
        "vendorId",
        vendorId.toString(),
        "internalSystemId",
        systemId.toString(),
        "privilegeLevel",
        privilegeLevel,
        "accessMethod",
        accessMethod,
        "sourceKind",
        sourceKind);
  }

  private void append(
      UUID tenantId,
      UUID actorId,
      String action,
      String entityType,
      UUID entityId,
      Map<String, Object> details) {
    audit.appendForTenant(
        tenantId,
        actorId,
        action,
        entityType,
        entityId.toString(),
        json.writeValueAsString(details));
  }

  /** Builds an insertion-ordered details map from alternating keys and values. */
  private static Map<String, Object> details(Object... keysAndValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }
}
