package com.validexia.access.application;

import com.validexia.access.application.AccessData.Blocker;
import com.validexia.access.application.AccessData.GrantData;
import com.validexia.access.application.AccessData.LinkData;
import com.validexia.access.application.AccessData.SystemData;
import com.validexia.access.application.AccessData.SystemViewData;
import com.validexia.access.application.AccessData.TransitionResult;
import com.validexia.access.infrastructure.AccessRepository;
import com.validexia.platform.errors.ApiException;
import com.validexia.platform.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application facade for M1 Access Intelligence (U1 freeze v1.4.1 §§5, 9). Deliberately NOT
 * transactional: it sets the tenant context from the request, delegates mutations to the proxied
 * {@link AccessTransactions} worker bean, translates the worker's {@link TransitionResult}s and
 * database errors into the frozen §5.3 HTTP mapping, and builds responses from the normal read path
 * after the worker returns. The facade never re-reads state to interpret a refusal — everything it
 * needs crossed the bean boundary inside the transition result.
 *
 * <p>Reads run through a transaction template over the same tenant-aware transaction manager, the
 * repositories' existing programmatic pattern.
 */
@Service
public class AccessService {

  private final AccessRepository access;
  private final AccessTransactions transactions;
  private final TransactionTemplate reads;

  public AccessService(
      AccessRepository access,
      AccessTransactions transactions,
      PlatformTransactionManager transactionManager) {
    this.access = access;
    this.transactions = transactions;
    this.reads = new TransactionTemplate(transactionManager);
  }

  // --- Internal systems -----------------------------------------------------

  public SystemViewData createSystem(
      UUID tenantId,
      UUID userId,
      @Nullable String name,
      @Nullable String description,
      @Nullable UUID ownerUserId) {
    UUID id =
        call(
            () ->
                withContext(
                    tenantId,
                    userId,
                    () ->
                        transactions.createSystem(
                            tenantId, userId, name, description, ownerUserId)));
    return getSystem(tenantId, userId, id);
  }

  public SystemViewData updateSystem(
      UUID tenantId,
      UUID userId,
      UUID systemId,
      @Nullable String name,
      @Nullable String description,
      @Nullable UUID ownerUserId) {
    TransitionResult result =
        call(
            () ->
                withContext(
                    tenantId,
                    userId,
                    () ->
                        transactions.updateSystem(
                            tenantId, userId, systemId, name, description, ownerUserId)));
    if (!result.applied()) {
      throw transitionRefused("update", "internal system", result);
    }
    return getSystem(tenantId, userId, systemId);
  }

  public SystemViewData retireSystem(UUID tenantId, UUID userId, UUID systemId) {
    TransitionResult result =
        call(
            () ->
                withContext(
                    tenantId, userId, () -> transactions.retireSystem(tenantId, userId, systemId)));
    if (!result.applied()) {
      throw transitionRefused("retire", "internal system", result);
    }
    return getSystem(tenantId, userId, systemId);
  }

  public SystemViewData restoreSystem(UUID tenantId, UUID userId, UUID systemId) {
    TransitionResult result =
        call(
            () ->
                withContext(
                    tenantId,
                    userId,
                    () -> transactions.restoreSystem(tenantId, userId, systemId)));
    if (!result.applied()) {
      throw transitionRefused("restore", "internal system", result);
    }
    return getSystem(tenantId, userId, systemId);
  }

  public SystemViewData getSystem(UUID tenantId, UUID userId, UUID systemId) {
    return call(
        () ->
            read(
                tenantId,
                userId,
                () -> {
                  SystemData system =
                      access
                          .getSystem(tenantId, systemId)
                          .orElseThrow(
                              () ->
                                  new ApiException(
                                      HttpStatus.NOT_FOUND,
                                      "internal_system_not_found",
                                      "internal system not found in tenant"));
                  return new SystemViewData(system, activeGrantCount(tenantId, systemId));
                }));
  }

  public List<SystemViewData> listSystems(UUID tenantId, UUID userId) {
    return call(
        () ->
            read(
                tenantId,
                userId,
                () -> {
                  List<SystemData> systems = access.listSystems(tenantId);
                  Map<UUID, Long> counts =
                      access.listGrants(tenantId, null, null).stream()
                          .filter(grant -> "Active".equals(grant.lifecycleStatus()))
                          .collect(
                              Collectors.groupingBy(
                                  GrantData::internalSystemId, Collectors.counting()));
                  return systems.stream()
                      .map(
                          system ->
                              new SystemViewData(system, counts.getOrDefault(system.id(), 0L)))
                      .toList();
                }));
  }

  // --- Access grants ----------------------------------------------------------

  public GrantData createGrant(
      UUID tenantId,
      UUID userId,
      UUID vendorId,
      UUID systemId,
      String privilegeLevel,
      String accessMethod,
      String sourceKind) {
    UUID id =
        call(
            () ->
                withContext(
                    tenantId,
                    userId,
                    () ->
                        transactions.createGrant(
                            tenantId,
                            userId,
                            vendorId,
                            systemId,
                            privilegeLevel,
                            accessMethod,
                            sourceKind)));
    return getGrant(tenantId, userId, id);
  }

  public GrantData recordDiscoveredGrant(
      UUID tenantId,
      UUID userId,
      UUID vendorId,
      UUID systemId,
      String privilegeLevel,
      String accessMethod,
      String sourceKind,
      String discoveryNote) {
    UUID id =
        call(
            () ->
                withContext(
                    tenantId,
                    userId,
                    () ->
                        transactions.recordDiscoveredGrant(
                            tenantId,
                            userId,
                            vendorId,
                            systemId,
                            privilegeLevel,
                            accessMethod,
                            sourceKind,
                            discoveryNote)));
    return getGrant(tenantId, userId, id);
  }

  public GrantData confirmGrant(UUID tenantId, UUID userId, UUID grantId) {
    TransitionResult result =
        call(
            () ->
                withContext(
                    tenantId, userId, () -> transactions.confirmGrant(tenantId, userId, grantId)));
    if (!result.applied()) {
      throw transitionRefused("confirm", "access grant", result);
    }
    return getGrant(tenantId, userId, grantId);
  }

  public GrantData rejectGrant(UUID tenantId, UUID userId, UUID grantId, String reason) {
    TransitionResult result =
        call(
            () ->
                withContext(
                    tenantId,
                    userId,
                    () -> transactions.rejectGrant(tenantId, userId, grantId, reason)));
    if (!result.applied()) {
      throw transitionRefused("reject", "access grant", result);
    }
    return getGrant(tenantId, userId, grantId);
  }

  public GrantData suspendGrant(UUID tenantId, UUID userId, UUID grantId, String reason) {
    TransitionResult result =
        call(
            () ->
                withContext(
                    tenantId,
                    userId,
                    () -> transactions.suspendGrant(tenantId, userId, grantId, reason)));
    if (!result.applied()) {
      throw transitionRefused("suspend", "access grant", result);
    }
    return getGrant(tenantId, userId, grantId);
  }

  public GrantData reinstateGrant(UUID tenantId, UUID userId, UUID grantId) {
    TransitionResult result =
        call(
            () ->
                withContext(
                    tenantId,
                    userId,
                    () -> transactions.reinstateGrant(tenantId, userId, grantId)));
    if (!result.applied()) {
      throw transitionRefused("reinstate", "access grant", result);
    }
    return getGrant(tenantId, userId, grantId);
  }

  public GrantData revokeGrant(UUID tenantId, UUID userId, UUID grantId, String reason) {
    TransitionResult result =
        call(
            () ->
                withContext(
                    tenantId,
                    userId,
                    () -> transactions.revokeGrant(tenantId, userId, grantId, reason)));
    if (!result.applied()) {
      throw transitionRefused("revoke", "access grant", result);
    }
    return getGrant(tenantId, userId, grantId);
  }

  public GrantData getGrant(UUID tenantId, UUID userId, UUID grantId) {
    return call(
        () ->
            read(
                tenantId,
                userId,
                () ->
                    access
                        .getGrant(tenantId, grantId)
                        .orElseThrow(
                            () ->
                                new ApiException(
                                    HttpStatus.NOT_FOUND,
                                    "access_grant_not_found",
                                    "access grant not found in tenant"))));
  }

  public List<GrantData> listGrants(
      UUID tenantId,
      UUID userId,
      @Nullable UUID vendorId,
      @Nullable UUID systemId,
      @Nullable String lifecycleStatus,
      @Nullable String verificationStatus) {
    return call(
        () ->
            read(
                tenantId,
                userId,
                () ->
                    access.listGrants(tenantId, vendorId, systemId).stream()
                        .filter(
                            grant ->
                                lifecycleStatus == null
                                    || lifecycleStatus.equals(grant.lifecycleStatus()))
                        .filter(
                            grant ->
                                verificationStatus == null
                                    || verificationStatus.equals(grant.verificationStatus()))
                        .toList()));
  }

  // --- Evidence ---------------------------------------------------------------

  public LinkData linkEvidence(UUID tenantId, UUID userId, UUID grantId, UUID documentId) {
    UUID linkId =
        call(
            () ->
                withContext(
                    tenantId,
                    userId,
                    () -> transactions.linkEvidence(tenantId, userId, grantId, documentId)));
    return listEvidence(tenantId, userId, grantId).stream()
        .filter(link -> link.id().equals(linkId))
        .findFirst()
        .orElseThrow();
  }

  public LinkData unlinkEvidence(UUID tenantId, UUID userId, UUID grantId, UUID linkId) {
    // The service verifies the link belongs to this grant in this tenant before
    // calling E2 (freeze §5.1): a link under a different grant is a 404 here.
    LinkData link =
        listEvidence(tenantId, userId, grantId).stream()
            .filter(candidate -> candidate.id().equals(linkId))
            .findFirst()
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.NOT_FOUND,
                        "evidence_link_not_found",
                        "evidence link not found in tenant"));
    TransitionResult result =
        call(
            () ->
                withContext(
                    tenantId,
                    userId,
                    () ->
                        transactions.unlinkEvidence(
                            tenantId, userId, grantId, linkId, link.documentId())));
    if (!result.applied()) {
      throw transitionRefused("unlink", "evidence link", result);
    }
    return listEvidence(tenantId, userId, grantId).stream()
        .filter(candidate -> candidate.id().equals(linkId))
        .findFirst()
        .orElseThrow();
  }

  public List<LinkData> listEvidence(UUID tenantId, UUID userId, UUID grantId) {
    return call(
        () ->
            read(
                tenantId,
                userId,
                () -> {
                  // Endpoint 18's frozen distinction (freeze §5.1): G8 first — a
                  // grant absent from this tenant is a 404; a present grant's
                  // evidence list is returned as-is, empty included.
                  access
                      .getGrant(tenantId, grantId)
                      .orElseThrow(
                          () ->
                              new ApiException(
                                  HttpStatus.NOT_FOUND,
                                  "access_grant_not_found",
                                  "access grant not found in tenant"));
                  return access.listEvidence(tenantId, grantId);
                }));
  }

  // --- Internals ----------------------------------------------------------------

  private long activeGrantCount(UUID tenantId, UUID systemId) {
    return access.listGrants(tenantId, null, systemId).stream()
        .filter(grant -> "Active".equals(grant.lifecycleStatus()))
        .count();
  }

  /** Runs a read inside a tenant transaction (the repositories' programmatic pattern). */
  private <T> T read(UUID tenantId, UUID userId, Supplier<T> work) {
    return withContext(tenantId, userId, () -> reads.execute(tx -> work.get()));
  }

  /**
   * Sets the tenant context for the duration of the work and clears it in a finally whose failure
   * cannot mask the work's own failure (freeze §9, the C1 rule): a cleanup failure is suppressed
   * onto the primary exception.
   */
  private <T> T withContext(UUID tenantId, UUID userId, Supplier<T> work) {
    TenantContext.set(tenantId.toString(), userId.toString());
    Throwable primary = null;
    try {
      return work.get();
    } catch (RuntimeException | Error failure) {
      primary = failure;
      throw failure;
    } finally {
      try {
        TenantContext.clear();
      } catch (RuntimeException | Error clearFailure) {
        if (primary != null) {
          primary.addSuppressed(clearFailure);
        } else {
          throw clearFailure;
        }
      }
    }
  }

  /** Applies the §5.3 error translation to anything the database layer raises. */
  private <T> T call(Supplier<T> work) {
    try {
      return work.get();
    } catch (DataAccessException failure) {
      throw translate(failure);
    }
  }

  private static RuntimeException translate(DataAccessException failure) {
    if (failure instanceof DataIntegrityViolationException) {
      // The 23505 translation lives here, outside the transactional boundary
      // (§5.3): only the two frozen partial-unique indexes are translated; any
      // other integrity violation propagates undisguised.
      String constraint = constraintName(failure);
      if ("internal_systems_active_name_key".equals(constraint)) {
        return new ApiException(
            HttpStatus.CONFLICT,
            "internal_system_name_in_use",
            "internal system name already in use");
      }
      if ("access_grant_evidence_active_link_key".equals(constraint)) {
        return new ApiException(
            HttpStatus.BAD_REQUEST,
            "evidence_already_linked",
            "evidence is already linked to this grant");
      }
      return failure;
    }
    String message = serverMessage(failure);
    if (message.contains("internal system not found in tenant")) {
      return new ApiException(
          HttpStatus.NOT_FOUND, "internal_system_not_found", "internal system not found in tenant");
    }
    if (message.contains("access grant not found in tenant")) {
      return new ApiException(
          HttpStatus.NOT_FOUND, "access_grant_not_found", "access grant not found in tenant");
    }
    if (message.contains("vendor not found in tenant")) {
      return new ApiException(
          HttpStatus.NOT_FOUND, "vendor_not_found", "vendor not found in tenant");
    }
    if (message.contains("document not found in tenant")) {
      return new ApiException(
          HttpStatus.NOT_FOUND, "document_not_found", "document not found in tenant");
    }
    if (message.contains("evidence link not found in tenant")) {
      return new ApiException(
          HttpStatus.NOT_FOUND, "evidence_link_not_found", "evidence link not found in tenant");
    }
    if (message.contains("actor context mismatch")
        || message.contains("actor is not an active member of the tenant")
        || message.contains("actor is not permitted to confirm or reject access grants")) {
      return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message.trim());
    }
    if (message.contains("vendor is terminated")) {
      return new ApiException(HttpStatus.BAD_REQUEST, "vendor_terminated", "vendor is terminated");
    }
    if (message.contains("internal system is retired")) {
      return new ApiException(
          HttpStatus.BAD_REQUEST, "internal_system_retired", "internal system is retired");
    }
    if (message.contains("document is not Ready")) {
      return new ApiException(
          HttpStatus.BAD_REQUEST, "document_not_ready", "document is not Ready");
    }
    if (message.contains("document belongs to a different vendor")) {
      return new ApiException(
          HttpStatus.BAD_REQUEST,
          "document_vendor_mismatch",
          "document belongs to a different vendor");
    }
    if (message.contains("evidence is already linked to this grant")) {
      return new ApiException(
          HttpStatus.BAD_REQUEST,
          "evidence_already_linked",
          "evidence is already linked to this grant");
    }
    if (message.contains("owner user is not a member of the tenant")) {
      return new ApiException(
          HttpStatus.BAD_REQUEST,
          "owner_not_tenant_member",
          "owner user is not a member of the tenant");
    }
    if (message.contains("internal system name already in use")) {
      return new ApiException(
          HttpStatus.CONFLICT,
          "internal_system_name_in_use",
          "internal system name already in use");
    }
    if (message.contains("reason is required") || message.contains("discovery note is required")) {
      return ApiException.validation(message.trim());
    }
    return failure;
  }

  private static ApiException transitionRefused(
      String action, String entity, TransitionResult result) {
    if (result.blocker() == Blocker.VENDOR_TERMINATED) {
      return new ApiException(
          HttpStatus.BAD_REQUEST,
          "transition_not_allowed",
          "Cannot " + action + ": vendor is terminated");
    }
    if (result.blocker() == Blocker.ALREADY_UNLINKED) {
      return new ApiException(
          HttpStatus.BAD_REQUEST,
          "transition_not_allowed",
          "Cannot " + action + ": evidence link is already unlinked");
    }
    return new ApiException(
        HttpStatus.BAD_REQUEST,
        "transition_not_allowed",
        "Cannot " + action + ": " + entity + " is " + result.currentStatus());
  }

  /** The violated constraint's name, when the failure carries a PostgreSQL error. */
  private static @Nullable String constraintName(DataAccessException failure) {
    PSQLException pg = findPostgresError(failure);
    if (pg == null || pg.getServerErrorMessage() == null) {
      return null;
    }
    return pg.getServerErrorMessage().getConstraint();
  }

  /** The PostgreSQL server message when present; the exception's own message otherwise. */
  private static String serverMessage(DataAccessException failure) {
    PSQLException pg = findPostgresError(failure);
    if (pg != null && pg.getServerErrorMessage() != null) {
      String message = pg.getServerErrorMessage().getMessage();
      if (message != null) {
        return message;
      }
    }
    return failure.getMessage() == null ? "" : failure.getMessage();
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
}
