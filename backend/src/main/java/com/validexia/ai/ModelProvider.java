package com.validexia.ai;

/**
 * A destination that can execute a {@link ModelRequest}. Implementations are real provider clients;
 * there is deliberately no test-double implementation in production code — tests supply their own.
 * An implementation must throw {@link ModelProviderException} (never return null or a placeholder
 * answer) when a call cannot be completed truthfully.
 */
public interface ModelProvider {

  /** Stable provider name, matched against configuration (e.g. {@code "anthropic"}). */
  String name();

  /** The model this provider instance is configured to call. */
  String model();

  ModelResponse complete(ModelRequest request);
}
