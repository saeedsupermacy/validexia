package com.validexia.ai;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The result of one model call, with the provenance every downstream record needs: which provider
 * and model produced it, how many tokens it consumed, and how long it took. {@code structured} is
 * non-null exactly when the request carried a schema and the provider answered through the result
 * tool; {@code text} carries the plain-text answer for unstructured calls (and may also be present
 * alongside a structured answer when the provider emitted text blocks — callers use one or the
 * other, per the request they made).
 */
public record ModelResponse(
    String provider,
    String model,
    @Nullable String text,
    @Nullable JsonNode structured,
    int inputTokens,
    int outputTokens,
    long latencyMs) {

  /** Returns a copy with the gateway-measured end-to-end latency. */
  public ModelResponse withLatency(long measuredMs) {
    return new ModelResponse(
        provider, model, text, structured, inputTokens, outputTokens, measuredMs);
  }

  /**
   * Metadata only — never the answer content. The text and structured payload are derived from
   * caller content, so the default record {@code toString} would leak them into any log line,
   * exception dump or debugger view that renders a response.
   */
  @Override
  public String toString() {
    return "ModelResponse[provider="
        + provider
        + ", model="
        + model
        + ", text="
        + (text == null ? "null" : text.length() + " chars")
        + ", structured="
        + (structured == null ? "null" : "present")
        + ", inputTokens="
        + inputTokens
        + ", outputTokens="
        + outputTokens
        + ", latencyMs="
        + latencyMs
        + "]";
  }
}
