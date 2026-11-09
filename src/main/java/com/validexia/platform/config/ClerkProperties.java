package com.validexia.platform.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Clerk wiring (M02-1 v2.6, Architecture A). All secrets arrive via environment variables; empty
 * values are legal in tests and local development and disable the dependent feature paths (JWKS
 * verification then fails closed for protected endpoints; webhooks return 503).
 */
@ConfigurationProperties(prefix = "validexia.clerk")
public record ClerkProperties(
    String issuer,
    String jwksUrl,
    List<String> authorizedParties,
    long maxTokenLifetimeSeconds,
    long clockSkewSeconds,
    String webhookSigningSecret,
    String secretKey,
    String apiBaseUrl,
    long jwksRefreshThrottleMs) {

  public ClerkProperties {
    if (authorizedParties == null) {
      authorizedParties = List.of();
    }
    if (maxTokenLifetimeSeconds <= 0) {
      maxTokenLifetimeSeconds = 120;
    }
    if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
      apiBaseUrl = "https://api.clerk.com";
    }
    if (jwksRefreshThrottleMs <= 0) {
      jwksRefreshThrottleMs = 5000;
    }
  }

  public boolean hasSecretKey() {
    return secretKey != null && !secretKey.isBlank();
  }

  public boolean hasWebhookSecret() {
    return webhookSigningSecret != null && !webhookSigningSecret.isBlank();
  }
}
