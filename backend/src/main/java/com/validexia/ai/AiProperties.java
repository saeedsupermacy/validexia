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

  /** Smallest permitted {@code timeout-seconds}: a call must get at least a second. */
  static final int MIN_TIMEOUT_SECONDS = 1;

  /**
   * Largest permitted {@code timeout-seconds}: no single model call may occupy a caller for longer.
   */
  static final int MAX_TIMEOUT_SECONDS = 600;

  public AiProperties {
    if (timeoutSeconds < MIN_TIMEOUT_SECONDS || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
      throw new IllegalArgumentException(
          "validexia.ai.timeout-seconds must be between "
              + MIN_TIMEOUT_SECONDS
              + " and "
              + MAX_TIMEOUT_SECONDS
              + ", was "
              + timeoutSeconds);
    }
  }

  /** True when a live call can actually be attempted: enabled, keyed, and model named. */
  public boolean configured() {
    return enabled && apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
  }
}
