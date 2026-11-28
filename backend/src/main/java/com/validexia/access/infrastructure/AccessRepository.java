package com.validexia.access.infrastructure;

import com.validexia.access.application.AccessData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The access module's only door to its tables. Every method calls a SECURITY DEFINER function from
 * migration V16; the application role holds no direct grants on the three access tables.
 *
 * <p>Unlike the older repositories, these methods manage no transaction and set no tenant context:
 * the caller owns both (U1 freeze v1.4.1 §9). Mutations run inside the {@code AccessTransactions}
 * worker bean's transaction, whose tenant context the {@code TenantTransactionManager} established
 * before the transaction began; reads run inside the facade's own transaction template. A call made
 * with no surrounding transaction and no tenant context fails closed at the functions' tenant
 * guard.
 */
@Repository
public class AccessRepository {

  private final JdbcTemplate jdbc;

  public AccessRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // --- Internal systems -----------------------------------------------------

  public UUID createSystem(
      UUID tenantId,
      @Nullable String name,
      @Nullable String description,
      @Nullable UUID ownerUserId,
      UUID actorId) {
    return jdbc.queryForObject(
        "SELECT public.create_internal_system(?, ?, ?, ?, ?)",
        UUID.class,
        tenantId,
        name,
        description,
        ownerUserId,
        actorId);
  }

  /** Returns S2's pre-update row, or empty when the update was refused (Retired system). */
  public Optional<AccessData.SystemData> updateSystem(
      UUID tenantId,
      UUID systemId,
      @Nullable String name,
      @Nullable String description,
      @Nullable UUID ownerUserId,
      UUID actorId) {
    return jdbc
        .query(
            "SELECT * FROM public.update_internal_system(?, ?, ?, ?, ?, ?)",
            AccessRepository::mapSystem,
            tenantId,
            systemId,
            name,
            description,
            ownerUserId,
            actorId)
        .stream()
        .findFirst();
  }

  public boolean retireSystem(UUID tenantId, UUID systemId, UUID actorId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT public.retire_internal_system(?, ?, ?)",
            Boolean.class,
            tenantId,
            systemId,
            actorId));
  }

  public boolean restoreSystem(UUID tenantId, UUID systemId, UUID actorId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT public.restore_internal_system(?, ?, ?)",
            Boolean.class,
            tenantId,
            systemId,
            actorId));
  }

  public Optional<AccessData.SystemData> getSystem(UUID tenantId, UUID systemId) {
    return jdbc
        .query(
            "SELECT * FROM public.get_internal_system(?, ?)",
            AccessRepository::mapSystem,
            tenantId,
            systemId)
        .stream()
        .findFirst();
  }

  public List<AccessData.SystemData> listSystems(UUID tenantId) {
    return jdbc.query(
        "SELECT * FROM public.list_internal_systems(?)", AccessRepository::mapSystem, tenantId);
  }

  // --- Access grants ----------------------------------------------------------

  public UUID createGrant(
      UUID tenantId,
      UUID vendorId,
      UUID systemId,
      String privilegeLevel,
      String accessMethod,
      String sourceKind,
      UUID actorId) {
    return jdbc.queryForObject(
        "SELECT public.create_access_grant(?, ?, ?, ?, ?, ?, ?)",
        UUID.class,
        tenantId,
        vendorId,
        systemId,
        privilegeLevel,
        accessMethod,
        sourceKind,
        actorId);
  }

  public UUID recordDiscoveredGrant(
      UUID tenantId,
      UUID vendorId,
      UUID systemId,
      String privilegeLevel,
      String accessMethod,
      String sourceKind,
      String discoveryNote,
      UUID actorId) {
    return jdbc.queryForObject(
        "SELECT public.record_discovered_access_grant(?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.class,
        tenantId,
        vendorId,
        systemId,
        privilegeLevel,
        accessMethod,
        sourceKind,
        discoveryNote,
        actorId);
  }

  public boolean confirmGrant(UUID tenantId, UUID grantId, UUID actorId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT public.confirm_access_grant(?, ?, ?)",
            Boolean.class,
            tenantId,
            grantId,
            actorId));
  }

  public boolean rejectGrant(UUID tenantId, UUID grantId, String reason, UUID actorId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT public.reject_access_grant(?, ?, ?, ?)",
            Boolean.class,
            tenantId,
            grantId,
            reason,
            actorId));
  }

  public boolean suspendGrant(UUID tenantId, UUID grantId, String reason, UUID actorId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT public.suspend_access_grant(?, ?, ?, ?)",
            Boolean.class,
            tenantId,
            grantId,
            reason,
            actorId));
  }

  public boolean reinstateGrant(UUID tenantId, UUID grantId, UUID actorId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT public.reinstate_access_grant(?, ?, ?)",
            Boolean.class,
            tenantId,
            grantId,
            actorId));
  }

  public boolean revokeGrant(UUID tenantId, UUID grantId, String reason, UUID actorId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT public.revoke_access_grant(?, ?, ?, ?)",
            Boolean.class,
            tenantId,
            grantId,
            reason,
            actorId));
  }

  public Optional<AccessData.GrantData> getGrant(UUID tenantId, UUID grantId) {
    return jdbc
        .query(
            "SELECT * FROM public.get_access_grant(?, ?)",
            AccessRepository::mapGrant,
            tenantId,
            grantId)
        .stream()
        .findFirst();
  }

  public List<AccessData.GrantData> listGrants(
      UUID tenantId, @Nullable UUID vendorId, @Nullable UUID systemId) {
    return jdbc.query(
        "SELECT * FROM public.list_access_grants(?, ?, ?)",
        AccessRepository::mapGrant,
        tenantId,
        vendorId,
        systemId);
  }

  // --- Evidence ---------------------------------------------------------------

  public UUID linkEvidence(UUID tenantId, UUID grantId, UUID documentId, UUID actorId) {
    return jdbc.queryForObject(
        "SELECT public.link_access_grant_evidence(?, ?, ?, ?)",
        UUID.class,
        tenantId,
        grantId,
        documentId,
        actorId);
  }

  public boolean unlinkEvidence(UUID tenantId, UUID linkId, UUID actorId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT public.unlink_access_grant_evidence(?, ?, ?)",
            Boolean.class,
            tenantId,
            linkId,
            actorId));
  }

  public List<AccessData.LinkData> listEvidence(UUID tenantId, UUID grantId) {
    return jdbc.query(
        "SELECT * FROM public.list_access_grant_evidence(?, ?)",
        AccessRepository::mapLink,
        tenantId,
        grantId);
  }

  // --- Row mappers --------------------------------------------------------------

  private static AccessData.SystemData mapSystem(ResultSet rs, int row) throws SQLException {
    return new AccessData.SystemData(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("description"),
        rs.getObject("owner_user_id", UUID.class),
        rs.getString("status"),
        rs.getObject("retired_by_user_id", UUID.class),
        toInstantOrNull(rs.getObject("retired_at", OffsetDateTime.class)),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)),
        toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
  }

  private static AccessData.GrantData mapGrant(ResultSet rs, int row) throws SQLException {
    return new AccessData.GrantData(
        rs.getObject("id", UUID.class),
        rs.getObject("vendor_id", UUID.class),
        rs.getObject("internal_system_id", UUID.class),
        rs.getString("privilege_level"),
        rs.getString("access_method"),
        rs.getString("source_kind"),
        rs.getString("lifecycle_status"),
        rs.getString("verification_status"),
        rs.getObject("created_by_user_id", UUID.class),
        rs.getObject("verified_by_user_id", UUID.class),
        toInstantOrNull(rs.getObject("verified_at", OffsetDateTime.class)),
        rs.getString("rejection_reason"),
        rs.getObject("suspended_by_user_id", UUID.class),
        toInstantOrNull(rs.getObject("suspended_at", OffsetDateTime.class)),
        rs.getString("suspension_reason"),
        rs.getObject("reinstated_by_user_id", UUID.class),
        toInstantOrNull(rs.getObject("reinstated_at", OffsetDateTime.class)),
        rs.getObject("revoked_by_user_id", UUID.class),
        toInstantOrNull(rs.getObject("revoked_at", OffsetDateTime.class)),
        rs.getString("revocation_reason"),
        rs.getString("discovery_note"),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)),
        toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
  }

  private static AccessData.LinkData mapLink(ResultSet rs, int row) throws SQLException {
    return new AccessData.LinkData(
        rs.getObject("id", UUID.class),
        rs.getObject("grant_id", UUID.class),
        rs.getObject("document_id", UUID.class),
        rs.getObject("linked_by_user_id", UUID.class),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)),
        rs.getObject("unlinked_by_user_id", UUID.class),
        toInstantOrNull(rs.getObject("unlinked_at", OffsetDateTime.class)));
  }

  private static Instant toInstant(@Nullable OffsetDateTime value) {
    return value == null ? Instant.EPOCH : value.toInstant();
  }

  private static @Nullable Instant toInstantOrNull(@Nullable OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
