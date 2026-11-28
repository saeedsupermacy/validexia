package com.validexia.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.support.TestDatabase;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Function-level behavior of the M1 access slice (U1 freeze v1.4.1 §§1–4): the system lifecycle
 * with its before-image update contract, the owner-membership rule, the grant lifecycle and
 * verification state machines (including the §3.6 repeated-suspension sequence and the
 * terminated-vendor rules), born-Revoked discovery, and the evidence link rules. All calls run as
 * the restricted application role on pinned single connections with session-level tenant and user
 * context, exactly as the TenantTransactionManager will set them per transaction. Session cleanup
 * follows the C1 rule for new M1 tests (cleanup failures are suppressed onto the primary failure).
 */
class AccessFlowTest {

  private final JdbcTemplate admin = TestDatabase.adminJdbc();

  private interface SessionWork {
    void run(JdbcTemplate session) throws Exception;
  }

  private JdbcTemplate appSession() throws Exception {
    Connection connection = TestDatabase.appDataSource().getConnection();
    return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
  }

  private void close(JdbcTemplate session) {
    ((SingleConnectionDataSource) session.getDataSource()).destroy();
  }

  private void withActor(UUID tenant, UUID actor, SessionWork work) throws Exception {
    JdbcTemplate session = appSession();
    Throwable primary = null;
    try {
      session.queryForObject(
          "SELECT set_config('app.tenant_id', ?, false)", String.class, tenant.toString());
      session.queryForObject(
          "SELECT set_config('app.user_id', ?, false)", String.class, actor.toString());
      work.run(session);
    } catch (Throwable t) {
      primary = t;
      throw t;
    } finally {
      try {
        close(session);
      } catch (Throwable closeFailure) {
        if (primary != null) {
          primary.addSuppressed(closeFailure);
        } else {
          throw closeFailure;
        }
      }
    }
  }

  private UUID seedTenant() {
    UUID tenant = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'T', ?)", tenant, "t-" + tenant);
    return tenant;
  }

  private UUID seedUser() {
    UUID user = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.users (id, external_subject) VALUES (?, ?)", user, "u-" + user);
    return user;
  }

  private UUID seedMember(UUID tenant, String role) {
    UUID user = seedUser();
    admin.update(
        "INSERT INTO public.memberships (user_id, tenant_id, role, status)"
            + " VALUES (?, ?, ?, 'active')",
        user,
        tenant,
        role);
    return user;
  }

  private UUID seedVendor(UUID tenant) {
    UUID vendor = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.vendors (id, tenant_id, name, code) VALUES (?, ?, 'V', ?)",
        vendor,
        tenant,
        "V-" + vendor);
    return vendor;
  }

  private UUID seedDocument(UUID tenant, UUID vendorId, String processingStatus) {
    UUID document = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.documents (id, tenant_id, vendor_id, file_name, file_type, file_size,"
            + " sha256, storage_key, processing_status) VALUES (?, ?, ?, 'f.pdf', 'pdf', 1, ?, ?, ?)",
        document,
        tenant,
        vendorId,
        "h-" + document,
        "k-" + document,
        processingStatus);
    return document;
  }

  private UUID createSystem(JdbcTemplate session, UUID tenant, UUID actor, String name) {
    return session.queryForObject(
        "SELECT public.create_internal_system(?, ?, null, null, ?)",
        UUID.class,
        tenant,
        name,
        actor);
  }

  private UUID createGrant(
      JdbcTemplate session, UUID tenant, UUID actor, UUID vendor, UUID system) {
    return session.queryForObject(
        "SELECT public.create_access_grant(?, ?, ?, 'Read', 'API', 'Customer Entered', ?)",
        UUID.class,
        tenant,
        vendor,
        system,
        actor);
  }

  private Map<String, Object> getGrant(JdbcTemplate session, UUID tenant, UUID grant) {
    List<Map<String, Object>> rows =
        session.queryForList("SELECT * FROM public.get_access_grant(?, ?)", tenant, grant);
    assertEquals(1, rows.size());
    return rows.getFirst();
  }

  private void assertRejected(String expectedMessage, Runnable call) {
    DataAccessException error = assertThrows(DataAccessException.class, call::run);
    assertTrue(
        error.getMessage() != null && error.getMessage().contains(expectedMessage),
        "expected rejection '" + expectedMessage + "' but got: " + error.getMessage());
  }

  @Test
  void systemLifecycleAndBeforeImageContract() throws Exception {
    UUID tenant = seedTenant();
    UUID admin = seedMember(tenant, "admin");
    withActor(
        tenant,
        admin,
        session -> {
          UUID system = createSystem(session, tenant, admin, "CRM");
          // S2 returns exactly one row: the PRE-update image.
          List<Map<String, Object>> before =
              session.queryForList(
                  "SELECT * FROM public.update_internal_system(?, ?, 'CRM Renamed', 'd', null, ?)",
                  tenant,
                  system,
                  admin);
          assertEquals(1, before.size());
          assertEquals("CRM", before.getFirst().get("name"));
          assertEquals(
              "CRM Renamed",
              session.queryForObject(
                  "SELECT name FROM public.get_internal_system(?, ?)",
                  String.class,
                  tenant,
                  system));

          assertTrue(
              session.queryForObject(
                  "SELECT public.retire_internal_system(?, ?, ?)",
                  Boolean.class,
                  tenant,
                  system,
                  admin));
          assertFalse(
              session.queryForObject(
                  "SELECT public.retire_internal_system(?, ?, ?)",
                  Boolean.class,
                  tenant,
                  system,
                  admin));
          // A refused update on a Retired system returns zero rows (freeze §1.1).
          assertEquals(
              0,
              session
                  .queryForList(
                      "SELECT * FROM public.update_internal_system(?, ?, 'Nope', null, null, ?)",
                      tenant,
                      system,
                      admin)
                  .size());
          assertTrue(
              session.queryForObject(
                  "SELECT public.restore_internal_system(?, ?, ?)",
                  Boolean.class,
                  tenant,
                  system,
                  admin));
          assertFalse(
              session.queryForObject(
                  "SELECT public.restore_internal_system(?, ?, ?)",
                  Boolean.class,
                  tenant,
                  system,
                  admin));
        });
  }

  @Test
  void activeSystemNamesAreUniquePerTenant() throws Exception {
    UUID tenant = seedTenant();
    UUID admin = seedMember(tenant, "admin");
    withActor(
        tenant,
        admin,
        session -> {
          UUID first = createSystem(session, tenant, admin, "CRM");
          assertRejected(
              "internal system name already in use",
              () -> createSystem(session, tenant, admin, "CRM"));
          UUID second = createSystem(session, tenant, admin, "ERP");
          assertRejected(
              "internal system name already in use",
              () ->
                  session.queryForList(
                      "SELECT * FROM public.update_internal_system(?, ?, 'CRM', null, null, ?)",
                      tenant,
                      second,
                      admin));
          // Retiring the holder frees the name; restoring it afterwards collides.
          assertTrue(
              session.queryForObject(
                  "SELECT public.retire_internal_system(?, ?, ?)",
                  Boolean.class,
                  tenant,
                  first,
                  admin));
          session.queryForList(
              "SELECT * FROM public.update_internal_system(?, ?, 'CRM', null, null, ?)",
              tenant,
              second,
              admin);
          assertRejected(
              "internal system name already in use",
              () ->
                  session.queryForObject(
                      "SELECT public.restore_internal_system(?, ?, ?)",
                      Boolean.class,
                      tenant,
                      first,
                      admin));
        });
  }

  @Test
  void ownerMustHoldAMembershipRowOfAnyStatus() throws Exception {
    UUID tenant = seedTenant();
    UUID admin = seedMember(tenant, "admin");
    UUID invited = seedUser();
    adminJdbcInsertMembership(invited, tenant, "member", "invited");
    UUID outsider = seedUser();
    withActor(
        tenant,
        admin,
        session -> {
          // An invited (non-active) member may be named owner: any status counts.
          UUID system =
              session.queryForObject(
                  "SELECT public.create_internal_system(?, 'Owned', null, ?, ?)",
                  UUID.class,
                  tenant,
                  invited,
                  admin);
          assertEquals(
              invited.toString(),
              session
                  .queryForList("SELECT * FROM public.get_internal_system(?, ?)", tenant, system)
                  .getFirst()
                  .get("owner_user_id")
                  .toString());
          // No membership row at all — including a user id that does not exist —
          // raises the frozen owner error, never a raw FK failure.
          assertRejected(
              "owner user is not a member of the tenant",
              () ->
                  session.queryForObject(
                      "SELECT public.create_internal_system(?, 'Bad', null, ?, ?)",
                      UUID.class,
                      tenant,
                      outsider,
                      admin));
          assertRejected(
              "owner user is not a member of the tenant",
              () ->
                  session.queryForObject(
                      "SELECT public.create_internal_system(?, 'Bad2', null, ?, ?)",
                      UUID.class,
                      tenant,
                      UUID.randomUUID(),
                      admin));
        });
  }

  private void adminJdbcInsertMembership(UUID user, UUID tenant, String role, String status) {
    admin.update(
        "INSERT INTO public.memberships (user_id, tenant_id, role, status) VALUES (?, ?, ?, ?)",
        user,
        tenant,
        role,
        status);
  }

  @Test
  void grantLifecycleVerificationAndRepeatedSuspension() throws Exception {
    UUID tenant = seedTenant();
    UUID admin = seedMember(tenant, "admin");
    UUID member = seedMember(tenant, "member");
    UUID vendor = seedVendor(tenant);
    UUID[] ids = new UUID[2];
    withActor(
        tenant,
        admin,
        session -> {
          UUID system = createSystem(session, tenant, admin, "CRM");
          UUID grant = createGrant(session, tenant, admin, vendor, system);
          ids[0] = system;
          ids[1] = grant;
          Map<String, Object> row = getGrant(session, tenant, grant);
          assertEquals("Active", row.get("lifecycle_status"));
          assertEquals("Unverified", row.get("verification_status"));

          // Confirm/reject are role-gated; suspend is not.
          assertTrue(
              session.queryForObject(
                  "SELECT public.confirm_access_grant(?, ?, ?)",
                  Boolean.class,
                  tenant,
                  grant,
                  admin));
          assertFalse(
              session.queryForObject(
                  "SELECT public.confirm_access_grant(?, ?, ?)",
                  Boolean.class,
                  tenant,
                  grant,
                  admin));
          assertTrue(
              session.queryForObject(
                  "SELECT public.reject_access_grant(?, ?, 'weak proof', ?)",
                  Boolean.class,
                  tenant,
                  grant,
                  admin));
          assertTrue(
              session.queryForObject(
                  "SELECT public.confirm_access_grant(?, ?, ?)",
                  Boolean.class,
                  tenant,
                  grant,
                  admin));
          row = getGrant(session, tenant, grant);
          assertEquals("Confirmed", row.get("verification_status"));
          assertNull(row.get("rejection_reason"));

          // Suspend -> reinstate -> suspend again: the second suspension clears
          // the reinstatement pair before writing the new triple (freeze §3.6).
          assertTrue(
              session.queryForObject(
                  "SELECT public.suspend_access_grant(?, ?, 'first', ?)",
                  Boolean.class,
                  tenant,
                  grant,
                  admin));
          assertTrue(
              session.queryForObject(
                  "SELECT public.reinstate_access_grant(?, ?, ?)",
                  Boolean.class,
                  tenant,
                  grant,
                  admin));
          row = getGrant(session, tenant, grant);
          assertEquals("Active", row.get("lifecycle_status"));
          assertEquals("first", row.get("suspension_reason"));
          assertTrue(
              session.queryForObject(
                  "SELECT public.suspend_access_grant(?, ?, 'second', ?)",
                  Boolean.class,
                  tenant,
                  grant,
                  admin));
          row = getGrant(session, tenant, grant);
          assertEquals("Suspended", row.get("lifecycle_status"));
          assertEquals("second", row.get("suspension_reason"));
          assertNull(row.get("reinstated_at"));
          assertNull(row.get("reinstated_by_user_id"));

          // Revocation is terminal.
          assertTrue(
              session.queryForObject(
                  "SELECT public.revoke_access_grant(?, ?, 'gone', ?)",
                  Boolean.class,
                  tenant,
                  grant,
                  admin));
          assertFalse(
              session.queryForObject(
                  "SELECT public.revoke_access_grant(?, ?, 'gone', ?)",
                  Boolean.class,
                  tenant,
                  grant,
                  admin));
          assertFalse(
              session.queryForObject(
                  "SELECT public.reinstate_access_grant(?, ?, ?)",
                  Boolean.class,
                  tenant,
                  grant,
                  admin));
          assertFalse(
              session.queryForObject(
                  "SELECT public.suspend_access_grant(?, ?, 'x', ?)",
                  Boolean.class,
                  tenant,
                  grant,
                  admin));
        });

    // The plain member hits the role gate on confirm, but may suspend.
    withActor(
        tenant,
        member,
        session -> {
          UUID grant = createGrant(session, tenant, member, vendor, ids[0]);
          assertRejected(
              "actor is not permitted to confirm or reject access grants",
              () ->
                  session.queryForObject(
                      "SELECT public.confirm_access_grant(?, ?, ?)",
                      Boolean.class,
                      tenant,
                      grant,
                      member));
          assertTrue(
              session.queryForObject(
                  "SELECT public.suspend_access_grant(?, ?, 'member may suspend', ?)",
                  Boolean.class,
                  tenant,
                  grant,
                  member));
          assertRejected(
              "suspension reason is required",
              () ->
                  session.queryForObject(
                      "SELECT public.suspend_access_grant(?, ?, '  ', ?)",
                      Boolean.class,
                      tenant,
                      grant,
                      member));
        });
  }

  @Test
  void discoveryIsBornRevokedAndAlwaysRecordable() throws Exception {
    UUID tenant = seedTenant();
    UUID admin = seedMember(tenant, "admin");
    UUID vendor = seedVendor(tenant);
    withActor(
        tenant,
        admin,
        session -> {
          UUID system = createSystem(session, tenant, admin, "CRM");
          UUID grant =
              session.queryForObject(
                  "SELECT public.record_discovered_access_grant(?, ?, ?, 'Admin', 'Direct Login',"
                      + " 'Unknown', 'found in export', ?)",
                  UUID.class,
                  tenant,
                  vendor,
                  system,
                  admin);
          Map<String, Object> row = getGrant(session, tenant, grant);
          assertEquals("Revoked", row.get("lifecycle_status"));
          assertEquals("found in export", row.get("discovery_note"));
          assertNull(row.get("revocation_reason"));
          assertEquals(admin.toString(), row.get("revoked_by_user_id").toString());

          // Discovery stays recordable against a Retired system and a
          // Terminated vendor; live creation does not.
          assertTrue(
              session.queryForObject(
                  "SELECT public.retire_internal_system(?, ?, ?)",
                  Boolean.class,
                  tenant,
                  system,
                  admin));
          assertRejected(
              "internal system is retired",
              () -> createGrant(session, tenant, admin, vendor, system));
          session.queryForObject(
              "SELECT public.record_discovered_access_grant(?, ?, ?, 'Read', 'API', 'Unknown',"
                  + " 'still recorded', ?)",
              UUID.class,
              tenant,
              vendor,
              system,
              admin);
          assertTrue(
              session.queryForObject(
                  "SELECT public.terminate_vendor(?, ?)", Boolean.class, tenant, vendor));
          assertRejected(
              "vendor is terminated", () -> createGrant(session, tenant, admin, vendor, system));
          session.queryForObject(
              "SELECT public.record_discovered_access_grant(?, ?, ?, 'Read', 'API', 'Unknown',"
                  + " 'offboarding find', ?)",
              UUID.class,
              tenant,
              vendor,
              system,
              admin);
        });
  }

  @Test
  void terminatedVendorBlocksReinstatement() throws Exception {
    UUID tenant = seedTenant();
    UUID admin = seedMember(tenant, "admin");
    UUID vendor = seedVendor(tenant);
    withActor(
        tenant,
        admin,
        session -> {
          UUID system = createSystem(session, tenant, admin, "CRM");
          UUID grant = createGrant(session, tenant, admin, vendor, system);
          assertTrue(
              session.queryForObject(
                  "SELECT public.suspend_access_grant(?, ?, 'hold', ?)",
                  Boolean.class,
                  tenant,
                  grant,
                  admin));
          assertTrue(
              session.queryForObject(
                  "SELECT public.terminate_vendor(?, ?)", Boolean.class, tenant, vendor));
          assertFalse(
              session.queryForObject(
                  "SELECT public.reinstate_access_grant(?, ?, ?)",
                  Boolean.class,
                  tenant,
                  grant,
                  admin));
          assertEquals("Suspended", getGrant(session, tenant, grant).get("lifecycle_status"));
        });
  }

  @Test
  void evidenceLinkingFollowsTheFrozenCheckOrder() throws Exception {
    UUID tenant = seedTenant();
    UUID admin = seedMember(tenant, "admin");
    UUID vendor = seedVendor(tenant);
    UUID otherVendor = seedVendor(tenant);
    UUID readyForVendor = seedDocument(tenant, vendor, "Ready");
    UUID pending = seedDocument(tenant, vendor, "Pending");
    UUID readyForOther = seedDocument(tenant, otherVendor, "Ready");
    UUID readyUnassigned = seedDocument(tenant, null, "Ready");
    withActor(
        tenant,
        admin,
        session -> {
          UUID system = createSystem(session, tenant, admin, "CRM");
          UUID grant = createGrant(session, tenant, admin, vendor, system);
          UUID link =
              session.queryForObject(
                  "SELECT public.link_access_grant_evidence(?, ?, ?, ?)",
                  UUID.class,
                  tenant,
                  grant,
                  readyForVendor,
                  admin);
          assertRejected(
              "evidence is already linked to this grant",
              () ->
                  session.queryForObject(
                      "SELECT public.link_access_grant_evidence(?, ?, ?, ?)",
                      UUID.class,
                      tenant,
                      grant,
                      readyForVendor,
                      admin));
          assertRejected(
              "document is not Ready",
              () ->
                  session.queryForObject(
                      "SELECT public.link_access_grant_evidence(?, ?, ?, ?)",
                      UUID.class,
                      tenant,
                      grant,
                      pending,
                      admin));
          assertRejected(
              "document belongs to a different vendor",
              () ->
                  session.queryForObject(
                      "SELECT public.link_access_grant_evidence(?, ?, ?, ?)",
                      UUID.class,
                      tenant,
                      grant,
                      readyForOther,
                      admin));
          assertRejected(
              "document not found in tenant",
              () ->
                  session.queryForObject(
                      "SELECT public.link_access_grant_evidence(?, ?, ?, ?)",
                      UUID.class,
                      tenant,
                      grant,
                      UUID.randomUUID(),
                      admin));
          // A vendor-unassigned Ready document links fine.
          session.queryForObject(
              "SELECT public.link_access_grant_evidence(?, ?, ?, ?)",
              UUID.class,
              tenant,
              grant,
              readyUnassigned,
              admin);
          // Unlink is soft and idempotent-false; the list keeps both links.
          assertTrue(
              session.queryForObject(
                  "SELECT public.unlink_access_grant_evidence(?, ?, ?)",
                  Boolean.class,
                  tenant,
                  link,
                  admin));
          assertFalse(
              session.queryForObject(
                  "SELECT public.unlink_access_grant_evidence(?, ?, ?)",
                  Boolean.class,
                  tenant,
                  link,
                  admin));
          assertEquals(
              2,
              session
                  .queryForList(
                      "SELECT * FROM public.list_access_grant_evidence(?, ?)", tenant, grant)
                  .size());
          // After unlinking, the same document can be linked again.
          session.queryForObject(
              "SELECT public.link_access_grant_evidence(?, ?, ?, ?)",
              UUID.class,
              tenant,
              grant,
              readyForVendor,
              admin);
        });
  }
}
