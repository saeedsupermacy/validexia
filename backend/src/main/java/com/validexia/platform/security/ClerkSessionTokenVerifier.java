package com.validexia.platform.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.validexia.platform.config.ClerkProperties;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Clerk session-token verification (M02-1 v2.6 §1, Architecture A — networkless verification
 * against the cached JWKS). Every rejection raises {@link TokenValidationException}.
 *
 * <p>Notable choices, per spec: RS256 only; {@code sid} is required; audience is not required for
 * default session tokens; and the verifier enforces its OWN token-lifetime ceiling (exp − iat),
 * because Clerk's token lifetime is configurable and its defaults are never relied on.
 */
@Component
public class ClerkSessionTokenVerifier {

  private final ClerkProperties properties;
  private final ClerkJwksProvider jwksProvider;
  private final Clock clock;

  public ClerkSessionTokenVerifier(
      ClerkProperties properties, ClerkJwksProvider jwksProvider, Clock clock) {
    this.properties = properties;
    this.jwksProvider = jwksProvider;
    this.clock = clock;
  }

  public ClerkIdentity verify(String token) {
    SignedJWT jwt;
    try {
      jwt = SignedJWT.parse(token);
    } catch (ParseException e) {
      throw new TokenValidationException("Malformed token", e);
    }
    if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
      throw new TokenValidationException("Unexpected token algorithm");
    }
    String kid = jwt.getHeader().getKeyID();
    if (kid == null || kid.isBlank()) {
      throw new TokenValidationException("Token has no key id");
    }
    JWK jwk = jwksProvider.getKey(kid);
    if (!(jwk instanceof RSAKey rsaKey)) {
      throw new TokenValidationException("Signing key is not an RSA key");
    }
    boolean signatureValid;
    try {
      signatureValid = jwt.verify(new RSASSAVerifier(rsaKey));
    } catch (Exception e) {
      throw new TokenValidationException("Signature verification failed", e);
    }
    if (!signatureValid) {
      throw new TokenValidationException("Invalid token signature");
    }

    JWTClaimsSet claims;
    try {
      claims = jwt.getJWTClaimsSet();
    } catch (ParseException e) {
      throw new TokenValidationException("Unreadable token claims", e);
    }
    String issuer = properties.issuer();
    if (issuer == null || issuer.isBlank() || !issuer.equals(claims.getIssuer())) {
      throw new TokenValidationException("Unexpected token issuer");
    }
    Instant now = clock.instant();
    long skew = properties.clockSkewSeconds();
    Instant expiresAt = claims.getExpirationTime() == null ? null : claims.getExpirationTime().toInstant();
    Instant issuedAt = claims.getIssueTime() == null ? null : claims.getIssueTime().toInstant();
    Instant notBefore = claims.getNotBeforeTime() == null ? null : claims.getNotBeforeTime().toInstant();
    if (expiresAt == null || issuedAt == null) {
      throw new TokenValidationException("Token is missing exp or iat");
    }
    if (now.isAfter(expiresAt.plusSeconds(skew))) {
      throw new TokenValidationException("Token expired");
    }
    if (notBefore != null && now.isBefore(notBefore.minusSeconds(skew))) {
      throw new TokenValidationException("Token not yet valid");
    }
    long lifetimeSeconds = expiresAt.getEpochSecond() - issuedAt.getEpochSecond();
    if (lifetimeSeconds > properties.maxTokenLifetimeSeconds()) {
      throw new TokenValidationException("Token lifetime exceeds the verifier ceiling");
    }
    try {
      String azp = claims.getStringClaim("azp");
      if (azp != null && !properties.authorizedParties().contains(azp)) {
        throw new TokenValidationException("Unauthorized token party (azp)");
      }
      String sid = claims.getStringClaim("sid");
      if (sid == null || sid.isBlank()) {
        throw new TokenValidationException("Token is missing the session id (sid)");
      }
      String subject = claims.getSubject();
      if (subject == null || subject.isBlank()) {
        throw new TokenValidationException("Token is missing the subject");
      }
      return new ClerkIdentity(subject, sid, issuedAt, expiresAt, claims.getStringClaim("email"));
    } catch (ParseException e) {
      throw new TokenValidationException("Unreadable token claims", e);
    }
  }
}
