package com.validexia.vendors.infrastructure;

import com.validexia.platform.tenancy.TenantContext;
import com.validexia.vendors.application.VendorData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The vendors module's only door to its tables. Every method calls a SECURITY DEFINER function from
 * migration V12; the application role holds no direct grants on the vendor tables. Each call runs
 * in its own transaction opened through the tenant-aware transaction manager, with the tenant
 * context set first — the same pattern {@code AgentsRepository} uses, so {@code app.tenant_id} is
 * the transaction's first statement and the functions' fail-closed tenant guard sees it.
 */
@Repository
public class VendorsRepository {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public VendorsRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
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
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.queryForObject(
                "SELECT public.create_vendor(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.class,
                tenantId,
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
                tieringComplete));
  }

  /** Updates every mutable field. The vendor {@code code} is immutable and not a parameter. */
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
    return inTenant(
        tenantId,
        userId,
        () ->
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "SELECT public.update_vendor(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Boolean.class,
                    tenantId,
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
                    tieringComplete)));
  }

  /** Lifecycle transition to Terminated. Returns false when no active vendor matched. */
  public boolean terminateVendor(UUID tenantId, UUID userId, UUID vendorId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "SELECT public.terminate_vendor(?, ?)", Boolean.class, tenantId, vendorId)));
  }

  public Optional<VendorData> getVendor(UUID tenantId, UUID userId, UUID vendorId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc
                .query(
                    "SELECT * FROM public.get_vendor(?, ?)",
                    VendorsRepository::mapVendor,
                    tenantId,
                    vendorId)
                .stream()
                .findFirst());
  }

  public List<VendorData> listVendors(UUID tenantId, UUID userId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.query(
                "SELECT * FROM public.list_vendors(?)", VendorsRepository::mapVendor, tenantId));
  }

  /**
   * Replaces the vendor's dependent-system set atomically: clear and re-add run inside this
   * method's single transaction, so a partial replacement is never observable. Returns false when
   * the vendor does not belong to the tenant.
   */
  public boolean replaceDependentSystems(
      UUID tenantId, UUID userId, UUID vendorId, List<VendorData.DependentSystemInput> systems) {
    return inTenant(
        tenantId,
        userId,
        () -> {
          boolean cleared =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT public.clear_vendor_dependent_systems(?, ?)",
                      Boolean.class,
                      tenantId,
                      vendorId));
          if (!cleared) {
            return false;
          }
          for (VendorData.DependentSystemInput system : systems) {
            Boolean added =
                jdbc.queryForObject(
                    "SELECT public.add_vendor_dependent_system(?, ?, ?, ?, ?)",
                    Boolean.class,
                    tenantId,
                    vendorId,
                    system.name(),
                    system.dataHandled(),
                    system.residency());
            if (!Boolean.TRUE.equals(added)) {
              throw new IllegalStateException("Dependent system add refused after clear succeeded");
            }
          }
          return true;
        });
  }

  public List<VendorData.DependentSystem> listDependentSystems(
      UUID tenantId, UUID userId, UUID vendorId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.query(
                "SELECT * FROM public.list_vendor_dependent_systems(?, ?)",
                (rs, row) ->
                    new VendorData.DependentSystem(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("data_handled"),
                        rs.getString("residency")),
                tenantId,
                vendorId));
  }

  private static VendorData mapVendor(ResultSet rs, int row) throws SQLException {
    return new VendorData(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("legal_name"),
        rs.getString("lei"),
        rs.getString("jurisdiction"),
        rs.getString("code"),
        rs.getString("category"),
        rs.getString("domain"),
        rs.getString("website"),
        rs.getString("description"),
        rs.getString("criticality"),
        rs.getObject("owner_user_id", UUID.class),
        rs.getString("status"),
        rs.getObject("contract_start", LocalDate.class),
        rs.getObject("contract_end", LocalDate.class),
        rs.getBoolean("tiering_complete"),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)),
        toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
  }

  private <T> T inTenant(UUID tenantId, @Nullable UUID userId, Supplier<T> work) {
    TenantContext.set(tenantId.toString(), userId == null ? null : userId.toString());
    try {
      return transactions.execute(tx -> work.get());
    } finally {
      TenantContext.clear();
    }
  }

  private static Instant toInstant(@Nullable OffsetDateTime value) {
    return value == null ? Instant.EPOCH : value.toInstant();
  }
}
