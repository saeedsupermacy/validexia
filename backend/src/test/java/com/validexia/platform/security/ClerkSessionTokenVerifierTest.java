package com.validexia.platform.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.jwk.RSAKey;
import com.validexia.platform.config.ClerkProperties;
import com.validexia.support.StubHttpClient;
import com.validexia.support.TestTokens;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Token-verification matrix (M02-1 v2.6 §9). The JWKS document is served by a stub HTTP client — no
 * sockets — and the clock is fixed, so every case is deterministic.
 */
class ClerkSessionTokenVerifierTest {

  private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

  private RSAKey key;
  private AtomicReference<String> jwksBody;
  private ClerkSessionTokenVerifier verifier;

  @BeforeEach
  void setUp() {
    key = TestTokens.generateKey(TestTokens.KID);
    jwksBody = new AtomicReference<>(TestTokens.jwksJson(key.toPublicJWK()));
    StubHttpClient http =
        new StubHttpClient(request -> new StubHttpClient.StubResponse(200, jwksBody.get()));
    ClerkJwksProvider provider =
        new ClerkJwksProvider(
            URI.create("https://clerk.test.example/.well-known/jwks.json"), 0, http);
    verifier =
        new ClerkSessionTokenVerifier(properties(), provider, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private ClerkProperties properties() {
    return new ClerkProperties(
        TestTokens.ISSUER,
        "https://clerk.test.example/.well-known/jwks.json",
        List.of("https://app.validexia.test"),
        120,
        5,
        "",
        "",
        "https://api.clerk.com",
        0);
  }

  @Test
  void acceptsWellFormedToken() throws Exception {
    String token = TestTokens.mint(key, NOW.minusSeconds(30), 60);
    ClerkIdentity identity = verifier.verify(token);
    assertEquals("user_test_1", identity.subject());
    assertEquals("sess_test_1", identity.sessionId());
  }

  @Test
  void rejectsExpiredToken() throws Exception {
    String token = TestTokens.mint(key, NOW.minusSeconds(3600), 60);
    assertThrows(TokenValidationException.class, () -> verifier.verify(token));
  }

  @Test
  void rejectsTokenFromTheFuture() throws Exception {
    String token = TestTokens.mint(key, NOW.plusSeconds(3600), 60);
    assertThrows(TokenValidationException.class, () -> verifier.verify(token));
  }

  @Test
  void rejectsWrongIssuer() throws Exception {
    String token =
        TestTokens.mint(key, NOW.minusSeconds(30), 60, Map.of("iss", "https://evil.example"));
    assertThrows(TokenValidationException.class, () -> verifier.verify(token));
  }

  @Test
  void rejectsHs256AlgConfusion() throws Exception {
    String token = TestTokens.mintHs256(NOW.minusSeconds(30));
    assertThrows(TokenValidationException.class, () -> verifier.verify(token));
  }

  @Test
  void rejectsMissingSid() throws Exception {
    // sid overridden to blank: the verifier requires a non-empty session id.
    String token = TestTokens.mint(key, NOW.minusSeconds(30), 60, Map.of("sid", ""));
    assertThrows(TokenValidationException.class, () -> verifier.verify(token));
  }

  @Test
  void rejectsUnlistedAzp() throws Exception {
    String token =
        TestTokens.mint(key, NOW.minusSeconds(30), 60, Map.of("azp", "https://phishing.example"));
    assertThrows(TokenValidationException.class, () -> verifier.verify(token));
  }

  @Test
  void rejectsLifetimeAboveVerifierCeiling() throws Exception {
    // A 1-hour token: legal for Clerk if so configured, but above OUR 120s ceiling.
    String token = TestTokens.mint(key, NOW.minusSeconds(30), 3600);
    TokenValidationException e =
        assertThrows(TokenValidationException.class, () -> verifier.verify(token));
    assertTrue(e.getMessage().contains("ceiling"));
  }

  @Test
  void rejectsUnknownKid() throws Exception {
    RSAKey other = TestTokens.generateKey("some-other-key");
    String token = TestTokens.mint(other, NOW.minusSeconds(30), 60);
    assertThrows(TokenValidationException.class, () -> verifier.verify(token));
  }

  @Test
  void acceptsRotatedKeyAfterRefetch() throws Exception {
    RSAKey rotated = TestTokens.generateKey("test-key-2");
    // The endpoint now serves only the rotated key; throttle is 0 so the refetch happens.
    jwksBody.set(TestTokens.jwksJson(rotated.toPublicJWK()));
    String token = TestTokens.mint(rotated, NOW.minusSeconds(30), 60);
    ClerkIdentity identity = verifier.verify(token);
    assertEquals("user_test_1", identity.subject());
  }

  @Test
  void rejectsTamperedPayload() throws Exception {
    String token = TestTokens.mint(key, NOW.minusSeconds(30), 60);
    String[] parts = token.split("\\.");
    String tampered = parts[0] + "." + parts[1] + "x." + parts[2];
    assertThrows(TokenValidationException.class, () -> verifier.verify(tampered));
  }
}
