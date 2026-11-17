package com.validexia.ai.eval;

import com.validexia.ai.AiProperties;
import com.validexia.ai.AnthropicModelProvider;
import com.validexia.ai.GroqModelProvider;
import com.validexia.ai.ModelProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * The live evaluation's provider selection: the ONE place in the harness that knows which concrete
 * provider class a run delegates to. The selection arrives through the harness-only environment
 * variable {@code VALIDEXIA_LIVE_EVAL_PROVIDER} ({@code anthropic} or {@code groq}); when it is
 * absent the delegate is the Anthropic provider, so an Anthropic evaluation behaves exactly as it
 * did before this selection existed. The model, base URL and API key are NOT selected here — they
 * reach the delegate through the application's bound {@link AiProperties}, driven by the usual
 * {@code VALIDEXIA_AI_*} environment variables, exactly as in production wiring.
 *
 * <p>The selection logic is split into pure functions of the raw variable value ({@link
 * #normaliseSelection(String)}, {@link #createDelegate(AiProperties, ObjectMapper, String)}, {@link
 * #evaluationModel(String)}) so the deterministic unit tests in {@code LiveEvalProvidersTest} can
 * exercise every branch without an environment they cannot set, an API key, or a network call:
 * constructing a provider object performs no I/O — the providers only call out when {@code
 * complete} is invoked, which the tests never do.
 *
 * <p>This class lives in test sources only. Nothing in the application knows it exists, and no
 * provider-specific logic leaks into the gateway, the workflows, or the evaluation cases: they all
 * see only the {@link ModelProvider} interface and the delegate's own {@code name()}.
 */
public final class LiveEvalProviders {

  /** Harness-only selection variable; deliberately NOT one of the application's VALIDEXIA_AI_*. */
  static final String SELECTION_VARIABLE = "VALIDEXIA_LIVE_EVAL_PROVIDER";

  /** The Anthropic evaluation model, used when VALIDEXIA_AI_MODEL does not name one. */
  public static final String DEFAULT_EVALUATION_MODEL = "claude-sonnet-5-5";

  private LiveEvalProviders() {}

  /**
   * The normalised selection from the environment: {@code anthropic} unless it says {@code groq}.
   */
  static String selection() {
    return normaliseSelection(System.getenv(SELECTION_VARIABLE));
  }

  /**
   * Normalises a raw selection value: absent or blank means {@code anthropic}; {@code anthropic}
   * and {@code groq} are accepted (any case, surrounding whitespace ignored); anything else is
   * rejected loudly rather than silently evaluating the wrong provider.
   */
  public static String normaliseSelection(String raw) {
    if (raw == null || raw.isBlank()) {
      return "anthropic";
    }
    String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
    if (!value.equals("anthropic") && !value.equals("groq")) {
      throw new IllegalStateException(
          SELECTION_VARIABLE
              + " must be \"anthropic\" or \"groq\" (or unset for the Anthropic default), was \""
              + raw
              + "\"");
    }
    return value;
  }

  /** The {@code name()} the selected delegate reports — what provenance envelopes will carry. */
  static String delegateName() {
    return selection();
  }

  /**
   * Constructs the selected production provider from the application's bound configuration, exactly
   * as the gateway would receive it — only wrapped by the harness's counting guard.
   */
  static ModelProvider createDelegate(AiProperties properties, ObjectMapper mapper) {
    return createDelegate(properties, mapper, selection());
  }

  /** As {@link #createDelegate(AiProperties, ObjectMapper)}, for an explicit raw selection. */
  public static ModelProvider createDelegate(
      AiProperties properties, ObjectMapper mapper, String rawSelection) {
    return switch (normaliseSelection(rawSelection)) {
      case "groq" -> new GroqModelProvider(properties, mapper);
      default -> new AnthropicModelProvider(properties, mapper);
    };
  }

  /**
   * The model the provider-failure evaluation runs against: the {@code VALIDEXIA_AI_MODEL}
   * environment value when set, otherwise the Anthropic evaluation default — so an Anthropic
   * failure run is unchanged and a Groq run exercises the dispatched Groq model.
   */
  static String evaluationModel() {
    return evaluationModel(System.getenv("VALIDEXIA_AI_MODEL"));
  }

  /** As {@link #evaluationModel()}, for an explicit raw environment value. */
  public static String evaluationModel(String rawModel) {
    return rawModel == null || rawModel.isBlank() ? DEFAULT_EVALUATION_MODEL : rawModel;
  }
}
