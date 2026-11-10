package com.validexia.identity.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.identity.infrastructure.DirectoryRepository.DirectoryUser;
import com.validexia.support.TestDatabase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lifecycle convergence rules (M02-1 v2.6 §2/§5): independent per-flag timestamps, restrictive
 * tie-breaks, epoch bumps only on false→true, tombstones, merge semantics, and snapshot safety.
 * All calls run as the application role through the definer functions.
 */
class LifecycleStateMachineTest {

  private final JdbcTemplate app = TestDatabase.appJdbc();
  private final JdbcTemplate admin = TestDatabase.adminJdbc();
  private final DirectoryRepository repository = new DirectoryRepository(app);

  private static Instant ts(long epochSeconds) {
    return Instant.ofEpochSecond(epochSeconds);
  }

  private String freshUser() {
    String subject = "user_lc_" + UUID.randomUUID();
    repository.provisionUser(subject, null, null);
    return subject;
  }

  private DirectoryUser user(String subject) {
    return repository.findUser(subject).orElseThrow();
  }

  @Test
  void unbanDoesNotUnlockAndUnlockDoesNotUnban() {
    String s = freshUser();
    repository.applyLifecycle(s, null, true, true, null, ts(1000));
    repository.applyLifecycle(s, null, false, null, null, ts(1010));
    assertFalse(user(s).banned());
    assertTrue(user(s).locked(), "unban must leave the lock untouched");

    String s2 = freshUser();
    repository.applyLifecycle(s2, null, true, true, null, ts(1000));
    repository.applyLifecycle(s2, null, null, false, null, ts(1010));
    assertTrue(user(s2).banned(), "unlock must leave the ban untouched");
    assertFalse(user(s2).locked());
  }

  @Test
  void staleFlagValuesAreIgnoredPerFlag() {
    String s = freshUser();
    repository.applyLifecycle(s, null, true, null, null, ts(2000));
    repository.applyLifecycle(s, null, false, null, null, ts(1500)); // stale unban
    assertTrue(user(s).banned());
    repository.applyLifecycle(s, null, null, true, null, ts(1200));
    repository.applyLifecycle(s, null, null, false, null, ts(1100)); // stale unlock
    assertTrue(user(s).locked());
    repository.applyLifecycle(s, null, null, false, null, ts(1300)); // fresh unlock applies
    assertFalse(user(s).locked());
    assertTrue(user(s).banned(), "the ban is on its own clock and survives");
  }

  @Test
  void epochBumpsOnlyOnBlockingTransitions() {
    String s = freshUser();
    assertNull(user(s).tokensValidAfter());
    repository.applyLifecycle(s, null, null, true, null, ts(3000));
    assertEquals(ts(3000), user(s).tokensValidAfter());
    repository.applyLifecycle(s, null, null, false, null, ts(3010)); // clearing never bumps
    assertEquals(ts(3000), user(s).tokensValidAfter());
    repository.applyLifecycle(s, null, true, null, null, ts(3020));
    assertEquals(ts(3020), user(s).tokensValidAfter());
    repository.applyLifecycle(s, null, false, null, null, ts(3030));
    assertEquals(ts(3020), user(s).tokensValidAfter());
  }

  @Test
  void deletionTombstoneCannotBeResurrectedByProvisioningOrStaleEvents() {
    String s = "user_tomb_" + UUID.randomUUID();
    repository.applyLifecycle(s, "deleted", null, null, null, ts(4000));
    assertEquals("deleted", user(s).status());
    repository.applyLifecycle(s, "active", null, null, null, ts(3500)); // stale
    assertEquals("deleted", user(s).status());
    repository.provisionUser(s, "ghost@example.test", "Ghost");
    assertEquals("deleted", user(s).status(), "provisioning must never resurrect");
  }

  @Test
  void snapshotCannotResurrectAndStaleSnapshotLoses() {
    String s = freshUser();
    repository.applyLifecycle(s, null, true, null, null, ts(5000));
    repository.applyLifecycleSnapshot(s, false, false, false, ts(4500)); // stale
    assertTrue(user(s).banned(), "stale snapshot must not clear the ban");

    String s2 = freshUser();
    repository.applyLifecycle(s2, "deleted", null, null, null, ts(5000));
    repository.applyLifecycleSnapshot(s2, false, false, false, ts(6000)); // fresher, all-clear
    assertEquals("deleted", user(s2).status(), "a snapshot never touches status");
  }

  @Test
  void mergeMovesMembershipsAndSurvivorWinsConflicts() {
    UUID tenantId = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'Merge Tenant', ?)",
        tenantId, "merge-" + tenantId);
    String survivor = freshUser();
    String removed = freshUser();
    UUID survivorId = user(survivor).userId();
    UUID removedId = user(removed).userId();
    admin.update(
        "INSERT INTO public.memberships (user_id, tenant_id, role) VALUES (?, ?, 'member')",
        survivorId, tenantId);
    admin.update(
        "INSERT INTO public.memberships (user_id, tenant_id, role) VALUES (?, ?, 'owner')",
        removedId, tenantId);

    repository.mergeUsers(survivor, removed, ts(7000));

    var memberships = repository.listMemberships(survivor);
    assertEquals(1, memberships.size());
    assertEquals("member", memberships.get(0).role(), "the survivor's own role wins the conflict");
    assertEquals("merged", user(removed).status());
    assertTrue(repository.listMemberships(removed).isEmpty());
  }
}
