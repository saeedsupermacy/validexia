package com.validexia.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.validexia.ai.eval.LiveEvalProviders;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Deterministic unit tests for the live evaluation's provider selection ({@link
 * LiveEvalProviders}). These tests are deliberately in THIS package and not in {@code
 * com.validexia.ai.eval}: the default {@code test} task excludes the eval package (its classes make
 * real provider calls), while this class must run in ordinary CI. It needs no Spring context, no
 * database, no API key and no network — the selection functions are pure, and constructing a
 * provider performs no I/O (the providers only call out when {@code complete} is invoked, which
 * these tests never do).
 */
class LiveEvalProvidersTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private final AiProperties properties =
      new AiProperties(
          true, "live-eval-counting", "dummy-eval-key", "any-model", "http://127.0.0.1:9", 10);

  @Test
  void absentSelectionDefaultsToAnthropic() {
    assertThat(LiveEvalProviders.normaliseSelection(null)).isEqualTo("anthropic");

    ModelProvider delegate = LiveEvalProviders.createDelegate(properties, mapper, null);
    assertThat(delegate).isInstanceOf(AnthropicModelProvider.class);
    assertThat(delegate.name()).isEqualTo("anthropic");
  }

  @Test
  void blankSelectionDefaultsToAnthropic() {
    assertThat(LiveEvalProviders.normaliseSelection("  ")).isEqualTo("anthropic");
  }

  @Test
  void explicitAnthropicSelection() {
    ModelProvider delegate = LiveEvalProviders.createDelegate(properties, mapper, "anthropic");
    assertThat(delegate).isInstanceOf(AnthropicModelProvider.class);
    assertThat(delegate.name()).isEqualTo("anthropic");
  }

  @Test
  void explicitGroqSelection() {
    ModelProvider delegate = LiveEvalProviders.createDelegate(properties, mapper, "groq");
    assertThat(delegate).isInstanceOf(GroqModelProvider.class);
    assertThat(delegate.name()).isEqualTo("groq");
  }

  @Test
  void selectionIsCaseAndWhitespaceTolerant() {
    assertThat(LiveEvalProviders.normaliseSelection(" Groq ")).isEqualTo("groq");
    assertThat(LiveEvalProviders.normaliseSelection("ANTHROPIC")).isEqualTo("anthropic");
  }

  @Test
  void unknownSelectionIsRejected() {
    assertThatThrownBy(() -> LiveEvalProviders.normaliseSelection("openai"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("VALIDEXIA_LIVE_EVAL_PROVIDER");
    assertThatThrownBy(() -> LiveEvalProviders.createDelegate(properties, mapper, "bogus"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void evaluationModelFallsBackToTheAnthropicDefaultWhenUnset() {
    assertThat(LiveEvalProviders.evaluationModel(null)).isEqualTo("claude-sonnet-5-5");
    assertThat(LiveEvalProviders.evaluationModel("")).isEqualTo("claude-sonnet-5-5");
    assertThat(LiveEvalProviders.evaluationModel("  ")).isEqualTo("claude-sonnet-5-5");
  }

  @Test
  void evaluationModelUsesTheEnvironmentValueWhenSet() {
    assertThat(LiveEvalProviders.evaluationModel("openai/gpt-oss-120b"))
        .isEqualTo("openai/gpt-oss-120b");
    assertThat(LiveEvalProviders.evaluationModel("claude-sonnet-5-5"))
        .isEqualTo("claude-sonnet-5-5");
  }
}
