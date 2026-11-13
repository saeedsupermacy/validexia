package com.validexia.ai;

import org.jspecify.annotations.Nullable;

/**
 * One model call. {@code purpose} names the workload (e.g. {@code "evidence.extraction"}) so the
 * gateway can route and account for it; it is never sent to the provider as content. When {@code
 * schema} is present the call is a structured call: the provider must answer through the forced
 * result tool, and the gateway validates the answer against the schema before returning it.
 *
 * <p>Caller content — above all, text retrieved from vendor documents — is DATA for the model,
 * never instructions to it. System prompts for document workloads must say so, and the evaluation
 * suite tests that a model which obeys an instruction embedded in a document is disqualified.
 */
public record ModelRequest(
    String purpose,
    String systemPrompt,
    String userPrompt,
    @Nullable OutputSchema schema,
    int maxOutputTokens) {

  /** Default output budget when a caller does not specify one. */
  public static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;

  public ModelRequest {
    if (purpose == null || purpose.isBlank()) {
      throw new IllegalArgumentException("a model request needs a purpose");
    }
    if (systemPrompt == null || userPrompt == null) {
      throw new IllegalArgumentException("a model request needs system and user prompts");
    }
    if (maxOutputTokens < 1 || maxOutputTokens > 64_000) {
      throw new IllegalArgumentException("maxOutputTokens out of range: " + maxOutputTokens);
    }
  }

  public static ModelRequest text(String purpose, String systemPrompt, String userPrompt) {
    return new ModelRequest(purpose, systemPrompt, userPrompt, null, DEFAULT_MAX_OUTPUT_TOKENS);
  }

  public static ModelRequest structured(
      String purpose, String systemPrompt, String userPrompt, OutputSchema schema) {
    return new ModelRequest(purpose, systemPrompt, userPrompt, schema, DEFAULT_MAX_OUTPUT_TOKENS);
  }
}
