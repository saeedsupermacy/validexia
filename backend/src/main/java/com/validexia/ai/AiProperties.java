package com.validexia.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Model-layer configuration. The API key arrives ONLY from the environment ({@code
 * VALIDEXIA_AI_API_KEY} — see application.yaml); it is never committed, never logged, and never
 * defaulted. A deployment without a key is a supported state: the layer is present, and every call
 * fails closed with {@link ModelProviderException.Kind#UNCONFIGURED} instead of producing a
 * fabricated answer.
 */
@ConfigurationProperties(prefix = "validexia.ai")
public record AiProperties(
    boolean enabled,
    String provider,
    String apiKey,
    String model,
    String baseUrl,
    int timeoutSeconds) {

  /** True when a live call can actually be attempted: enabled, keyed, and model named. */
  public boolean configured() {
    return enabled && apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
  }
}
