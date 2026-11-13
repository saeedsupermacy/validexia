package com.validexia.ai;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The single entry point for model calls. It selects the configured provider, enforces the
 * fail-closed rule (an unconfigured deployment gets {@link
 * ModelProviderException.Kind#UNCONFIGURED}, never a fabricated answer), validates structured
 * outputs against the request's schema, and records one usage line per call (provider, model,
 * purpose, tokens, latency — never prompt or response content, which may contain customer document
 * text).
 *
 * <p>Retries are deliberately NOT done here: a failed call surfaces to the agent engine, whose task
 * lifecycle (leases, attempts, idempotency keys) owns retry semantics. Retrying silently at this
 * layer would double-charge and double-execute against that accounting. The contract the engine
 * retries against is {@link ModelProviderException#retryable()}: it is {@code true} only for
 * transient failures (rate limiting, provider 5xx, transport errors) where another attempt could
 * succeed, and {@code false} for permanent ones (authentication, malformed request, unconfigured
 * deployment, schema-invalid output), which must fail the task instead of burning attempts.
 *
 * <p>Usage accounting covers failures too: a call the provider rejects or that fails validation
 * still consumed provider resources, so the one usage line per call is written for failed calls as
 * well (with the failure kind and retryable flag instead of token counts) — still never with prompt
 * or response content.
 */
@Service
public class ModelGateway {

  private static final Logger LOG = LoggerFactory.getLogger(ModelGateway.class);

  private final List<ModelProvider> providers;
  private final AiProperties properties;
  private final StructuredOutputValidator validator;

  public ModelGateway(
      List<ModelProvider> providers, AiProperties properties, StructuredOutputValidator validator) {
    this.providers = List.copyOf(providers);
    this.properties = properties;
    this.validator = validator;
  }

  public ModelResponse execute(ModelRequest request) {
    ModelProvider provider = selectProvider();
    long started = System.nanoTime();
    try {
      ModelResponse response = provider.complete(request);
      long latencyMs = (System.nanoTime() - started) / 1_000_000;
      if (request.schema() != null) {
        if (response.structured() == null) {
          throw new ModelProviderException(
              ModelProviderException.Kind.INVALID_OUTPUT,
              "provider "
                  + provider.name()
                  + " answered a structured request without a structured result");
        }
        validator.requireValid(response.structured(), request.schema());
      }
      ModelResponse measured = response.withLatency(latencyMs);
      LOG.info(
          "model call purpose={} provider={} model={} inputTokens={} outputTokens={} latencyMs={}",
          request.purpose(),
          measured.provider(),
          measured.model(),
          measured.inputTokens(),
          measured.outputTokens(),
          measured.latencyMs());
      return measured;
    } catch (ModelProviderException e) {
      long latencyMs = (System.nanoTime() - started) / 1_000_000;
      LOG.info(
          "model call failed purpose={} provider={} model={} kind={} retryable={} latencyMs={}",
          request.purpose(),
          provider.name(),
          provider.model(),
          e.kind(),
          e.retryable(),
          latencyMs);
      throw e;
    }
  }

  private ModelProvider selectProvider() {
    if (!properties.configured()) {
      throw new ModelProviderException(
          ModelProviderException.Kind.UNCONFIGURED,
          "no model provider is configured (validexia.ai.enabled, an API key and a model are"
              + " required); refusing to fabricate an answer");
    }
    return providers.stream()
        .filter(p -> p.name().equals(properties.provider()))
        .findFirst()
        .orElseThrow(
            () ->
                new ModelProviderException(
                    ModelProviderException.Kind.UNCONFIGURED,
                    "configured provider \"" + properties.provider() + "\" is not available"));
  }
}
