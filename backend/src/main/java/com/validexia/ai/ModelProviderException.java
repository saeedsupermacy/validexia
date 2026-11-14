package com.validexia.ai;

import org.jspecify.annotations.Nullable;

/**
 * A model call failed in a way the caller must not paper over. The {@link Kind} distinguishes a
 * deployment problem (no provider configured) from a provider/transport failure and from a response
 * that failed structured-output validation — the cases have different remedies, and none of them
 * may degrade into a fabricated answer.
 *
 * <p>{@link #retryable()} is the machine-readable half of the retry contract: this layer never
 * retries, so the agent engine — which owns attempt accounting — reads it to decide whether another
 * attempt could plausibly succeed. It is {@code false} for permanent failures (the provider
 * rejected the request itself: authentication, malformed request, other 4xx), for unconfigured
 * deployments, and for schema-invalid output; it is {@code true} only for transient conditions
 * (rate limiting, provider 5xx, transport failures). A non-retryable failure must fail the task,
 * not burn attempts.
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
  private final boolean retryable;

  /**
   * Defaults {@code retryable} from the kind: transport failures are transient, the rest are not.
   */
  public ModelProviderException(Kind kind, String message) {
    this(kind, kind == Kind.TRANSPORT, message, null);
  }

  /**
   * Defaults {@code retryable} from the kind: transport failures are transient, the rest are not.
   */
  public ModelProviderException(Kind kind, String message, @Nullable Throwable cause) {
    this(kind, kind == Kind.TRANSPORT, message, cause);
  }

  public ModelProviderException(Kind kind, boolean retryable, String message) {
    this(kind, retryable, message, null);
  }

  public ModelProviderException(
      Kind kind, boolean retryable, String message, @Nullable Throwable cause) {
    super(message, cause);
    this.kind = kind;
    this.retryable = retryable;
  }

  public Kind kind() {
    return kind;
  }

  /**
   * Whether another attempt of the same call could plausibly succeed. Read by the agent engine,
   * which owns retry semantics; never used to retry inside this layer.
   */
  public boolean retryable() {
    return retryable;
  }
}
