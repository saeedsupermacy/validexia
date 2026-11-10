package com.validexia.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mints Clerk-shaped session tokens and JWKS documents for tests — no network involved. */
public final class TestTokens {

  public static final String ISSUER = "https://clerk.test.example";
  public static final String KID = "test-key-1";

  private TestTokens() {}

  public static RSAKey generateKey(String kid) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair pair = generator.generateKeyPair();
      return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
          .privateKey((RSAPrivateKey) pair.getPrivate())
          .keyID(kid)
          .build();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String jwksJson(RSAKey... keys) {
    JWKSet set = new JWKSet(java.util.List.of(keys));
    return set.toString();
  }

  /** A valid-by-default token; individual claims can be overridden or nulled via the map. */
  public static String mint(RSAKey key, Instant issuedAt, long lifetimeSeconds) throws Exception {
    return mint(key, issuedAt, lifetimeSeconds, Map.of());
  }

  public static String mint(
      RSAKey key, Instant issuedAt, long lifetimeSeconds, Map<String, Object> overrides)
      throws Exception {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", ISSUER);
    claims.put("sub", "user_test_1");
    claims.put("sid", "sess_test_1");
    claims.put("azp", "https://app.validexia.test");
    claims.putAll(overrides);
    JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
    claims.forEach(
        (name, value) -> {
          if (value != null) {
            builder.claim(name, value);
          }
        });
    builder.issueTime(Date.from(issuedAt));
    builder.notBeforeTime(Date.from(issuedAt.minusSeconds(10)));
    builder.expirationTime(Date.from(issuedAt.plusSeconds(lifetimeSeconds)));
    JWSSigner signer = new RSASSASigner(key);
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            builder.build());
    jwt.sign(signer);
    return jwt.serialize();
  }

  /** An HS256 token signed with a shared secret — the classic alg-confusion attempt. */
  public static String mintHs256(Instant issuedAt) throws Exception {
    byte[] secret = new byte[32];
    for (int i = 0; i < secret.length; i++) {
      secret[i] = (byte) i;
    }
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject("user_test_1")
            .claim("sid", "sess_test_1")
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(issuedAt.plusSeconds(60)))
            .build();
    SignedJWT jwt =
        new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KID).build(), claims);
    jwt.sign(new MACSigner(secret));
    return jwt.serialize();
  }
}
