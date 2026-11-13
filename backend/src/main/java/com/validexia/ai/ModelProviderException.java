package com.validexia.ai;

/**
 * A model call failed in a way the caller must not paper over. The {@link Kind} distinguishes a
 * deployment problem (no provider configured) from a provider/transport failure and from a response
 * that failed structured-output validation — the three cases have different remedies, and none of
 * them may degrade into a fabricated answer.
 */
public final class ModelProviderException extends RuntimeException {

  public enum Kind {
    /** No provider is configured (disabled, or the API key / model is unset). Fail closed. */
    UNCONFIGURED,
    /** The provider could not be reached (network error, timeout, interruption). */
    TRANSPORT,
    /** The provider answered with an error status or an unparseable body. */
    PROVIDER_ERROR,
    /** The provider answered, but the output failed schema validation. */
    INVALID_OUTPUT
  }

  private final Kind kind;

  public ModelProviderException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public ModelProviderException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
