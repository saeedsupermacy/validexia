package com.validexia.identity.application;

import com.validexia.platform.config.ClerkProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal Clerk Backend API client: fetch a user (for the locked-only JIT resync) and revoke a
 * session (on logout). Both are best-effort from the caller's perspective; failures are reported as
 * empty/exception and never change local state by themselves.
 */
@Component
public class ClerkBackendClient {

  private static final Logger log = LoggerFactory.getLogger(ClerkBackendClient.class);

  /** The lifecycle-relevant slice of Clerk's Backend User object (verified schema fields only). */
  public record ClerkUserSnapshot(
      boolean banned, boolean locked, boolean deprovisioned, Instant updatedAt) {}

  private final ClerkProperties properties;
  private final HttpClient httpClient;
  private final ObjectMapper mapper;

  public ClerkBackendClient(ClerkProperties properties, ObjectMapper mapper) {
    this(properties, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
  }

  /** Test-facing constructor: injects the HTTP client so tests need no live network. */
  public ClerkBackendClient(
      ClerkProperties properties, ObjectMapper mapper, HttpClient httpClient) {
    this.properties = properties;
    this.mapper = mapper;
    this.httpClient = httpClient;
  }

  public Optional<ClerkUserSnapshot> fetchUser(String subject) {
    if (!properties.hasSecretKey()) {
      return Optional.empty();
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(properties.apiBaseUrl() + "/v1/users/" + subject))
              .timeout(Duration.ofSeconds(5))
              .header("Authorization", "Bearer " + properties.secretKey())
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.warn("Clerk user fetch returned {}", response.statusCode());
        return Optional.empty();
      }
      JsonNode user = mapper.readTree(response.body());
      return Optional.of(
          new ClerkUserSnapshot(
              user.path("banned").asBoolean(false),
              user.path("locked").asBoolean(false),
              user.path("deprovisioned").asBoolean(false),
              Instant.ofEpochMilli(user.path("updated_at").asLong(0))));
    } catch (Exception e) {
      log.warn("Clerk user fetch failed: {}", e.getMessage());
      return Optional.empty();
    }
  }

  /** Best effort: the local sid denylist is authoritative for this app either way. */
  public void revokeSession(String sessionId) {
    if (!properties.hasSecretKey()) {
      return;
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(properties.apiBaseUrl() + "/v1/sessions/" + sessionId + "/revoke"))
              .timeout(Duration.ofSeconds(5))
              .header("Authorization", "Bearer " + properties.secretKey())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build();
      httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    } catch (Exception e) {
      log.warn("Clerk session revoke failed: {}", e.getMessage());
    }
  }
}
