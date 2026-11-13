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
 * layer would double-charge and double-execute against that accounting.
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
