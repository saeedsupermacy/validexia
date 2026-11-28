package com.validexia.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.support.TestDatabase;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Fail-closed guards for the M1 access functions (U1 freeze v1.4.1 §§1, 7). Every function rejects
 * execution unless the session's {@code app.tenant_id} exactly matches the tenant argument. Every
 * mutation additionally binds its actor argument to the session's {@code app.user_id} and requires
 * an ACTIVE membership in the tenant: a missing, malformed or mismatched actor context, a
 * non-member, and an invited or suspended member are all refused. Also covers cross-tenant
 * invisibility under a VALID context.
 *
 * <p>All calls run as the restricted application role on pinned single connections, because the
 * guards read the session's tenant and user settings. Session cleanup follows the C1 rule for new
 * M1 tests: a cleanup failure is suppressed onto the test's primary failure, never masking it.
 */
class AccessTenantGuardTest {

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

  private void withSession(SessionWork work) throws Exception {
    JdbcTemplate session = appSession();
    Throwable primary = null;
    try {
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

  private void setContext(JdbcTemplate session, String tenantId, String userId) {
    session.queryForObject("SELECT set_config('app.tenant_id', ?, false)", String.class, tenantId);
    if (userId != null) {
      session.queryForObject("SELECT set_config('app.user_id', ?, false)", String.class, userId);
    }
  }

  private void probe(JdbcTemplate session, String sql, Object... args) {
    session.query(sql, (RowCallbackHandler) rs -> {}, args);
  }

  private void assertRejected(
      String expectedMessage, JdbcTemplate session, String sql, Object... args) {
    DataAccessException error =
        assertThrows(DataAccessException.class, () -> probe(session, sql, args));
    assertTrue(
        error.getMessage() != null && error.getMessage().contains(expectedMessage),
        "expected rejection '" + expectedMessage + "' but got: " + error.getMessage());
  }

  private UUID seedTenant(String slug) {
    UUID tenant = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'T', ?)", tenant, slug + tenant);
    return tenant;
  }

  private UUID seedUser() {
    UUID user = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.users (id, external_subject) VALUES (?, ?)", user, "u-" + user);
    return user;
  }

  private void seedMembership(UUID user, UUID tenant, String role, String status) {
    admin.update(
        "INSERT INTO public.memberships (user_id, tenant_id, role, status) VALUES (?, ?, ?, ?)",
        user,
        tenant,
        role,
        status);
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

  private static final String CREATE_SYSTEM_SQL =
      "SELECT public.create_internal_system(?, 'CRM', null, null, ?)";
  private static final String UPDATE_SYSTEM_SQL =
      "SELECT * FROM public.update_internal_system(?, ?, 'CRM', null, null, ?)";
  private static final String CREATE_GRANT_SQL =
      "SELECT public.create_access_grant(?, ?, ?, 'Read', 'API', 'Unknown', ?)";
  private static final String RECORD_GRANT_SQL =
      "SELECT public.record_discovered_access_grant(?, ?, ?, 'Read', 'API', 'Unknown', 'note', ?)";

  @Test
  void createAndReadSucceedUnderTheMatchingContext() throws Exception {
    UUID tenant = seedTenant("ok-");
    UUID actor = seedUser();
    seedMembership(actor, tenant, "admin", "active");
    UUID vendor = seedVendor(tenant);
    withSession(
        session -> {
          setContext(session, tenant.toString(), actor.toString());
          UUID system = session.queryForObject(CREATE_SYSTEM_SQL, UUID.class, tenant, actor);
          UUID grant =
              session.queryForObject(CREATE_GRANT_SQL, UUID.class, tenant, vendor, system, actor);
          List<String> names =
              session.query(
                  "SELECT * FROM public.list_internal_systems(?)",
                  (rs, row) -> rs.getString("name"),
                  tenant);
          assertEquals(List.of("CRM"), names);
          assertEquals(
              1,
              session
                  .query(
                      "SELECT * FROM public.get_access_grant(?, ?)", (rs, row) -> 1, tenant, grant)
                  .size());
        });
  }

  @Test
  void missingEmptyAndForeignContextsAreAllRejected() throws Exception {
    UUID tenant = seedTenant("guard-");
    UUID other = seedTenant("other-");
    UUID actor = seedUser();
    seedMembership(actor, tenant, "admin", "active");
    UUID any = UUID.randomUUID();

    withSession(
        session -> {
          // Missing context: a fresh session that never set app.tenant_id.
          assertRejected("tenant context mismatch", session, CREATE_SYSTEM_SQL, tenant, actor);
          assertRejected(
              "tenant context mismatch",
              session,
              "SELECT * FROM public.list_internal_systems(?)",
              tenant);
          assertRejected(
              "tenant context mismatch", session, CREATE_GRANT_SQL, tenant, any, any, actor);
          assertRejected(
              "tenant context mismatch", session, RECORD_GRANT_SQL, tenant, any, any, actor);
          assertRejected(
              "tenant context mismatch",
              session,
              "SELECT public.confirm_access_grant(?, ?, ?)",
              tenant,
              any,
              actor);
          assertRejected(
              "tenant context mismatch",
              session,
              "SELECT * FROM public.list_access_grants(?, null, null)",
              tenant);
          assertRejected(
              "tenant context mismatch",
              session,
              "SELECT public.link_access_grant_evidence(?, ?, ?, ?)",
              tenant,
              any,
              any,
              actor);
        });

    withSession(
        session -> {
          // Empty tenant context.
          setContext(session, "", actor.toString());
          assertRejected("tenant context mismatch", session, CREATE_SYSTEM_SQL, tenant, actor);
        });

    withSession(
        session -> {
          // Foreign context: the session belongs to `other`, the calls target `tenant`.
          setContext(session, other.toString(), actor.toString());
          assertRejected("tenant context mismatch", session, CREATE_SYSTEM_SQL, tenant, actor);
          assertRejected("tenant context mismatch", session, UPDATE_SYSTEM_SQL, tenant, any, actor);
          assertRejected(
              "tenant context mismatch",
              session,
              "SELECT public.retire_internal_system(?, ?, ?)",
              tenant,
              any,
              actor);
          assertRejected(
              "tenant context mismatch",
              session,
              "SELECT public.restore_internal_system(?, ?, ?)",
              tenant,
              any,
              actor);
          assertRejected(
              "tenant context mismatch",
              session,
              "SELECT * FROM public.get_internal_system(?, ?)",
              tenant,
              any);
          assertRejected(
              "tenant context mismatch", session, CREATE_GRANT_SQL, tenant, any, any, actor);
          assertRejected(
              "tenant context mismatch",
              session,
              "SELECT public.reject_access_grant(?, ?, 'r', ?)",
              tenant,
              any,
              actor);
          assertRejected(
              "tenant context mismatch",
              session,
              "SELECT public.suspend_access_grant(?, ?, 'r', ?)",
              tenant,
              any,
              actor);
          assertRejected(
              "tenant context mismatch",
              session,
              "SELECT public.reinstate_access_grant(?, ?, ?)",
              tenant,
              any,
              actor);
          assertRejected(
              "tenant context mismatch",
              session,
              "SELECT public.revoke_access_grant(?, ?, 'r', ?)",
              tenant,
              any,
              actor);
          assertRejected(
              "tenant context mismatch",
              session,
              "SELECT * FROM public.get_access_grant(?, ?)",
              tenant,
              any);
          assertRejected(
              "tenant context mismatch",
              session,
              "SELECT public.unlink_access_grant_evidence(?, ?, ?)",
              tenant,
              any,
              actor);
          assertRejected(
              "tenant context mismatch",
              session,
              "SELECT * FROM public.list_access_grant_evidence(?, ?)",
              tenant,
              any);
        });
  }

  @Test
  void actorBindingRefusesMissingMalformedMismatchedAndNonMemberActors() throws Exception {
    UUID tenant = seedTenant("actor-");
    UUID actor = seedUser();
    seedMembership(actor, tenant, "admin", "active");
    UUID invited = seedUser();
    seedMembership(invited, tenant, "member", "invited");
    UUID suspended = seedUser();
    seedMembership(suspended, tenant, "member", "suspended");
    UUID outsider = seedUser();

    withSession(
        session -> {
          setContext(session, tenant.toString(), null);
          // No app.user_id at all.
          assertRejected("actor context mismatch", session, CREATE_SYSTEM_SQL, tenant, actor);
          // Malformed app.user_id.
          setContext(session, tenant.toString(), "not-a-uuid");
          assertRejected("actor context mismatch", session, CREATE_SYSTEM_SQL, tenant, actor);
          // Actor argument disagrees with app.user_id.
          setContext(session, tenant.toString(), actor.toString());
          assertRejected("actor context mismatch", session, CREATE_SYSTEM_SQL, tenant, outsider);
          // Bound actor with no membership row.
          setContext(session, tenant.toString(), outsider.toString());
          assertRejected(
              "actor is not an active member of the tenant",
              session,
              CREATE_SYSTEM_SQL,
              tenant,
              outsider);
          // Invited and suspended memberships are not ACTIVE memberships.
          setContext(session, tenant.toString(), invited.toString());
          assertRejected(
              "actor is not an active member of the tenant",
              session,
              CREATE_SYSTEM_SQL,
              tenant,
              invited);
          setContext(session, tenant.toString(), suspended.toString());
          assertRejected(
              "actor is not an active member of the tenant",
              session,
              CREATE_SYSTEM_SQL,
              tenant,
              suspended);
        });
  }

  @Test
  void anotherTenantsRowsAreInvisibleUnderAValidContext() throws Exception {
    UUID tenantA = seedTenant("a-");
    UUID tenantB = seedTenant("b-");
    UUID actor = seedUser();
    seedMembership(actor, tenantA, "admin", "active");
    seedMembership(actor, tenantB, "admin", "active");
    UUID vendorA = seedVendor(tenantA);

    UUID[] created = new UUID[2];
    withSession(
        session -> {
          setContext(session, tenantA.toString(), actor.toString());
          created[0] = session.queryForObject(CREATE_SYSTEM_SQL, UUID.class, tenantA, actor);
          created[1] =
              session.queryForObject(
                  CREATE_GRANT_SQL, UUID.class, tenantA, vendorA, created[0], actor);
        });

    withSession(
        session -> {
          setContext(session, tenantB.toString(), actor.toString());
          assertEquals(
              0,
              session
                  .query(
                      "SELECT * FROM public.get_internal_system(?, ?)",
                      (rs, row) -> 1,
                      tenantB,
                      created[0])
                  .size());
          assertEquals(
              0,
              session
                  .query("SELECT * FROM public.list_internal_systems(?)", (rs, row) -> 1, tenantB)
                  .size());
          assertEquals(
              0,
              session
                  .query(
                      "SELECT * FROM public.get_access_grant(?, ?)",
                      (rs, row) -> 1,
                      tenantB,
                      created[1])
                  .size());
          // A mutation against tenant A's row under tenant B's context resolves
          // no row in B and raises the frozen not-found error.
          assertRejected(
              "internal system not found in tenant",
              session,
              "SELECT public.retire_internal_system(?, ?, ?)",
              tenantB,
              created[0],
              actor);
        });
  }
}
