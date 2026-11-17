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
 * <p>This class lives in test sources only. Nothing in the application knows it exists, and no
 * provider-specific logic leaks into the gateway, the workflows, or the evaluation cases: they all
 * see only the {@link ModelProvider} interface and the delegate's own {@code name()}.
 */
final class LiveEvalProviders {

  /** Harness-only selection variable; deliberately NOT one of the application's VALIDEXIA_AI_*. */
  static final String SELECTION_VARIABLE = "VALIDEXIA_LIVE_EVAL_PROVIDER";

  private LiveEvalProviders() {}

  /** The normalised selection: {@code anthropic} unless the variable says {@code groq}. */
  static String selection() {
    String raw = System.getenv(SELECTION_VARIABLE);
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
    return switch (selection()) {
      case "groq" -> new GroqModelProvider(properties, mapper);
      default -> new AnthropicModelProvider(properties, mapper);
    };
  }
}
