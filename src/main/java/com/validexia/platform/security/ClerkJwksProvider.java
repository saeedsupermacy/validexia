package com.validexia.platform.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.validexia.platform.config.ClerkProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * In-memory JWKS cache (M02-1 v2.6 §1). Keys are looked up by {@code kid}; an unknown {@code kid}
 * triggers at most one throttled refetch (which absorbs Clerk key rotation). A failed refetch
 * keeps serving the last-good cache. A cold start with no cache fails closed: no key is ever
 * returned until a JWKS document has actually been fetched.
 */
@Component
public class ClerkJwksProvider {

  private final URI jwksUri;
  private final long refreshThrottleMs;
  private final HttpClient httpClient;

  private volatile @Nullable JWKSet cache;
  private long lastFetchAttemptMs;

  public ClerkJwksProvider(ClerkProperties properties) {
    String url = properties.jwksUrl();
    this.jwksUri = URI.create(url == null ? "" : url);
    this.refreshThrottleMs = properties.jwksRefreshThrottleMs();
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  /** Test-facing constructor: injects the HTTP client and throttle. */
  public ClerkJwksProvider(URI jwksUri, long refreshThrottleMs, HttpClient httpClient) {
    this.jwksUri = jwksUri;
    this.refreshThrottleMs = refreshThrottleMs;
    this.httpClient = httpClient;
  }

  public JWK getKey(String kid) {
    JWKSet snapshot = cache;
    if (snapshot != null) {
      JWK key = snapshot.getKeyByKeyId(kid);
      if (key != null) {
        return key;
      }
    }
    refreshIfDue();
    snapshot = cache;
    if (snapshot == null) {
      throw new TokenValidationException("JWKS unavailable and no cached keys (fail closed)");
    }
    JWK key = snapshot.getKeyByKeyId(kid);
    if (key == null) {
      throw new TokenValidationException("Unknown signing key id");
    }
    return key;
  }

  private synchronized void refreshIfDue() {
    long now = System.currentTimeMillis();
    if (cache != null && now - lastFetchAttemptMs < refreshThrottleMs) {
      return;
    }
    lastFetchAttemptMs = now;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(jwksUri).timeout(Duration.ofSeconds(5)).GET().build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IOException("JWKS endpoint returned " + response.statusCode());
      }
      cache = JWKSet.parse(response.body());
    } catch (Exception e) {
      // Keep the last-good cache (possibly still null, i.e. cold). The caller decides:
      // getKey throws when there is no cache at all.
      if (cache == null) {
        throw new TokenValidationException("JWKS fetch failed on cold start", e);
      }
    }
  }
}
