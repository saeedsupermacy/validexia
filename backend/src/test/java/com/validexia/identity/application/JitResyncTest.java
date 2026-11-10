package com.validexia.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.identity.infrastructure.DirectoryRepository;
import com.validexia.platform.config.ClerkProperties;
import com.validexia.support.StubHttpClient;
import com.validexia.support.TestDatabase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The locked-only JIT resync safeguard (M02-1 v2.6): atomic claim, no transaction across the fetch,
 * stale snapshots lose. The Clerk API is a stub HTTP client — call counts are exact.
 */
class JitResyncTest {

  private final DirectoryRepository repository = new DirectoryRepository(TestDatabase.appJdbc());

  private ClerkProperties properties() {
    return new ClerkProperties(
        "https://clerk.test.example",
        "",
        List.of(),
        120,
        5,
        "",
        "sk_test_secret",
        "https://api.clerk.test",
        0);
  }

  private LockedResyncService serviceWith(StubHttpClient http) {
    ClerkBackendClient client =
        new ClerkBackendClient(properties(), JsonMapper.builder().build(), http);
    return new LockedResyncService(repository, client, properties());
  }

  private String lockedUser() {
    String subject = "user_jit_" + UUID.randomUUID();
    repository.provisionUser(subject, null, null);
    repository.applyLifecycle(subject, null, null, true, null, Instant.now().minusSeconds(3600));
    return subject;
  }

  private static String userJson(
      boolean banned, boolean locked, boolean deprovisioned, long updatedAtMs) {
    return "{\"id\":\"x\",\"banned\":"
        + banned
        + ",\"locked\":"
        + locked
        + ",\"deprovisioned\":"
        + deprovisioned
        + ",\"updated_at\":"
        + updatedAtMs
        + "}";
  }

  @Test
  void happyPathUnlocksOnFreshSnapshot() {
    String subject = lockedUser();
    long nowMs = System.currentTimeMillis();
    StubHttpClient http =
        new StubHttpClient(
            req -> new StubHttpClient.StubResponse(200, userJson(false, false, false, nowMs)));
    serviceWith(http).tryResync(subject);
    assertFalse(repository.findUser(subject).orElseThrow().locked());
    assertEquals(1, http.callCount());
  }

  @Test
  void failedFetchConsumesTheWindow() {
    String subject = lockedUser();
    AtomicReference<StubHttpClient.StubResponse> response =
        new AtomicReference<>(new StubHttpClient.StubResponse(500, "{}"));
    StubHttpClient http = new StubHttpClient(req -> response.get());
    LockedResyncService service = serviceWith(http);

    service.tryResync(subject);
    assertTrue(repository.findUser(subject).orElseThrow().locked());
    assertEquals(1, http.callCount());

    // Clerk recovers immediately, but the claimed window throttles any new attempt.
    response.set(
        new StubHttpClient.StubResponse(
            200, userJson(false, false, false, System.currentTimeMillis())));
    service.tryResync(subject);
    assertEquals(1, http.callCount(), "a failed fetch still consumes the 60-second window");
    assertTrue(repository.findUser(subject).orElseThrow().locked());
  }

  @Test
  void concurrentRequestsMakeExactlyOneApiCall() throws Exception {
    String subject = lockedUser();
    StubHttpClient http =
        new StubHttpClient(
            req -> {
              try {
                Thread.sleep(75);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return new StubHttpClient.StubResponse(
                  200, userJson(false, false, false, System.currentTimeMillis()));
            });
    LockedResyncService service = serviceWith(http);
    int threads = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    for (int i = 0; i < threads; i++) {
      pool.submit(
          () -> {
            ready.countDown();
            try {
              go.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            service.tryResync(subject);
            return null;
          });
    }
    ready.await();
    go.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
    assertEquals(1, http.callCount(), "the atomic claim must admit exactly one resync");
  }

  @Test
  void staleSnapshotDoesNotOverwriteNewerWebhookState() {
    String subject = "user_jit_stale_" + UUID.randomUUID();
    repository.provisionUser(subject, null, null);
    Instant banTime = Instant.now().minusSeconds(600);
    repository.applyLifecycle(subject, null, true, true, null, banTime);
    // The snapshot predates the ban: it reports all-clear with an older updated_at.
    long staleMs = banTime.toEpochMilli() - 60_000;
    StubHttpClient http =
        new StubHttpClient(
            req -> new StubHttpClient.StubResponse(200, userJson(false, false, false, staleMs)));
    serviceWith(http).tryResync(subject);
    var user = repository.findUser(subject).orElseThrow();
    assertTrue(user.banned(), "stale snapshot must not clear a newer ban");
    assertTrue(user.locked(), "stale snapshot must not clear a newer lock");
  }
}
