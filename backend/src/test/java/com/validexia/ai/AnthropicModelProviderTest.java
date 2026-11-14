package com.validexia.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AnthropicModelProviderTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private AiProperties properties() {
    return new AiProperties(
        true, "anthropic", "test-key-123", "test-model", "https://api.anthropic.test", 30);
  }

  private OutputSchema schema() {
    return new OutputSchema(
        List.of(
            OutputSchema.Field.requiredEnum("verdict", List.of("PASS", "FAIL")),
            OutputSchema.Field.requiredStringArray("quotes")));
  }

  private AnthropicModelProvider providerReturning(
      AtomicReference<String> capturedUrl,
      AtomicReference<Map<String, String>> capturedHeaders,
      AtomicReference<String> capturedBody,
      int status,
      String responseBody) {
    return new AnthropicModelProvider(
        properties(),
        mapper,
        (url, headers, body) -> {
          capturedUrl.set(url);
          capturedHeaders.set(headers);
          capturedBody.set(body);
          return new AnthropicModelProvider.TransportResponse(status, responseBody);
        });
  }

  @Test
  void aStructuredCallUsesStructuredOutputsAndParsesTheJsonText() throws Exception {
    AtomicReference<String> url = new AtomicReference<>();
    AtomicReference<Map<String, String>> headers = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    AnthropicModelProvider provider =
        providerReturning(
            url,
            headers,
            body,
            200,
            "{\"model\":\"test-model\",\"stop_reason\":\"end_turn\","
                + "\"content\":[{\"type\":\"text\",\"text\":"
                + "\"{\\\"verdict\\\":\\\"PASS\\\",\\\"quotes\\\":[\\\"q1\\\"]}\"}],"
                + "\"usage\":{\"input_tokens\":11,\"output_tokens\":7}}");

    ModelResponse response =
        provider.complete(ModelRequest.structured("evidence.extraction", "sys", "user", schema()));

    assertEquals("https://api.anthropic.test/v1/messages", url.get());
    assertEquals("test-key-123", headers.get().get("x-api-key"));
    assertEquals("2023-06-01", headers.get().get("anthropic-version"));
    JsonNode sent = mapper.readTree(body.get());
    assertEquals("test-model", sent.path("model").asText());
    // Structured Outputs replaces the forced tool call entirely: no tools, no tool_choice.
    assertFalse(sent.has("tools"));
    assertFalse(sent.has("tool_choice"));
    JsonNode format = sent.path("output_config").path("format");
    assertEquals("json_schema", format.path("type").asText());
    JsonNode sentSchema = format.path("schema");
    assertTrue(sentSchema.path("required").toString().contains("verdict"));
    assertFalse(sentSchema.path("additionalProperties").asBoolean(true));
    assertEquals("PASS", response.structured().path("verdict").asText());
    assertEquals("q1", response.structured().path("quotes").get(0).asText());
    assertEquals(11, response.inputTokens());
    assertEquals(7, response.outputTokens());
  }

  @Test
  void theEmittedSchemaForbidsAdditionalPropertiesOnEveryObject() {
    OutputSchema briefShaped =
        new OutputSchema(
            List.of(
                OutputSchema.Field.required("summary", OutputSchema.Type.STRING),
                OutputSchema.Field.requiredStringArray("keyFacts"),
                OutputSchema.Field.required("abstained", OutputSchema.Type.BOOLEAN),
                OutputSchema.Field.optional("meta", OutputSchema.Type.OBJECT)));

    JsonNode jsonSchema = briefShaped.toJsonSchema(mapper);

    assertFalse(jsonSchema.path("additionalProperties").asBoolean(true));
    assertFalse(
        jsonSchema.path("properties").path("meta").path("additionalProperties").asBoolean(true));
    // No existing constraint is dropped: types, required fields and array items survive.
    assertEquals("string", jsonSchema.path("properties").path("summary").path("type").asText());
    assertEquals(
        "string",
        jsonSchema.path("properties").path("keyFacts").path("items").path("type").asText());
    assertTrue(jsonSchema.path("required").toString().contains("summary"));
    assertTrue(jsonSchema.path("required").toString().contains("abstained"));
  }

  @Test
  void aRefusedStructuredAnswerFailsWithInvalidOutput() {
    AnthropicModelProvider provider =
        providerReturning(
            new AtomicReference<>(),
            new AtomicReference<>(),
            new AtomicReference<>(),
            200,
            "{\"model\":\"test-model\",\"stop_reason\":\"refusal\",\"content\":[],"
                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}");
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("evidence.extraction", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, thrown.kind());
    assertFalse(thrown.retryable());
  }

  @Test
  void aTruncatedStructuredAnswerFailsWithInvalidOutput() {
    AnthropicModelProvider provider =
        providerReturning(
            new AtomicReference<>(),
            new AtomicReference<>(),
            new AtomicReference<>(),
            200,
            "{\"model\":\"test-model\",\"stop_reason\":\"max_tokens\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"{\\\"verdict\\\":\\\"PA\"}],"
                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":4096}}");
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("evidence.extraction", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, thrown.kind());
    assertFalse(thrown.retryable());
  }

  @Test
  void aRefusalWithCompleteValidJsonStillFailsWithInvalidOutput() {
    // The stop_reason is inspected BEFORE the text is parsed: a refusal accompanied by
    // complete, schema-valid JSON must still fail — the JSON must never be accepted.
    AnthropicModelProvider provider =
        providerReturning(
            new AtomicReference<>(),
            new AtomicReference<>(),
            new AtomicReference<>(),
            200,
            "{\"model\":\"test-model\",\"stop_reason\":\"refusal\","
                + "\"content\":[{\"type\":\"text\",\"text\":"
                + "\"{\\\"verdict\\\":\\\"PASS\\\",\\\"quotes\\\":[\\\"q1\\\"]}\"}],"
                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":9}}");
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("evidence.extraction", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, thrown.kind());
    assertFalse(thrown.retryable());
  }

  @Test
  void aMaxTokensAnswerWithCompleteValidJsonStillFailsWithInvalidOutput() {
    // Same ordering guarantee for truncation: even if the visible text happens to parse as
    // complete JSON, a max_tokens stop means the answer was cut off and must fail closed.
    AnthropicModelProvider provider =
        providerReturning(
            new AtomicReference<>(),
            new AtomicReference<>(),
            new AtomicReference<>(),
            200,
            "{\"model\":\"test-model\",\"stop_reason\":\"max_tokens\","
                + "\"content\":[{\"type\":\"text\",\"text\":"
                + "\"{\\\"verdict\\\":\\\"PASS\\\",\\\"quotes\\\":[\\\"q1\\\"]}\"}],"
                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":4096}}");
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("evidence.extraction", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, thrown.kind());
    assertFalse(thrown.retryable());
  }

  @Test
  void aStructuredAnswerWithAMissingStopReasonFailsWithInvalidOutput() {
    // No stop_reason at all: the response shape is not the documented successful completion,
    // so even complete, schema-valid JSON must be rejected rather than guessed at.
    AnthropicModelProvider provider =
        providerReturning(
            new AtomicReference<>(),
            new AtomicReference<>(),
            new AtomicReference<>(),
            200,
            "{\"model\":\"test-model\","
                + "\"content\":[{\"type\":\"text\",\"text\":"
                + "\"{\\\"verdict\\\":\\\"PASS\\\",\\\"quotes\\\":[\\\"q1\\\"]}\"}],"
                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":9}}");
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("evidence.extraction", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, thrown.kind());
    assertFalse(thrown.retryable());
  }

  @Test
  void aStructuredAnswerWithAnUnexpectedStopReasonFailsWithInvalidOutput() {
    // An unexpected stop reason (here stop_sequence, which this client never requests) is
    // rejected the same way: only end_turn is an accepted structured completion.
    AnthropicModelProvider provider =
        providerReturning(
            new AtomicReference<>(),
            new AtomicReference<>(),
            new AtomicReference<>(),
            200,
            "{\"model\":\"test-model\",\"stop_reason\":\"stop_sequence\","
                + "\"content\":[{\"type\":\"text\",\"text\":"
                + "\"{\\\"verdict\\\":\\\"PASS\\\",\\\"quotes\\\":[\\\"q1\\\"]}\"}],"
                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":9}}");
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("evidence.extraction", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, thrown.kind());
    assertFalse(thrown.retryable());
  }

  @Test
  void aStructuredAnswerWithoutAnyTextFailsWithInvalidOutput() {
    AnthropicModelProvider provider =
        providerReturning(
            new AtomicReference<>(),
            new AtomicReference<>(),
            new AtomicReference<>(),
            200,
            "{\"model\":\"test-model\",\"stop_reason\":\"end_turn\",\"content\":[],"
                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}");
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("evidence.extraction", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, thrown.kind());
    assertFalse(thrown.retryable());
  }

  @Test
  void aStructuredAnswerThatIsNotJsonFailsWithInvalidOutput() {
    AnthropicModelProvider provider =
        providerReturning(
            new AtomicReference<>(),
            new AtomicReference<>(),
            new AtomicReference<>(),
            200,
            "{\"model\":\"test-model\",\"stop_reason\":\"end_turn\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"I cannot answer in JSON.\"}],"
                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":6}}");
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("evidence.extraction", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, thrown.kind());
    assertFalse(thrown.retryable());
  }

  @Test
  void aTextCallSendsNoToolsAndReturnsConcatenatedText() throws Exception {
    AtomicReference<String> url = new AtomicReference<>();
    AtomicReference<Map<String, String>> headers = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    AnthropicModelProvider provider =
        providerReturning(
            url,
            headers,
            body,
            200,
            "{\"model\":\"test-model\",\"content\":[{\"type\":\"text\",\"text\":\"Hello \"},"
                + "{\"type\":\"text\",\"text\":\"world\"}],"
                + "\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}");

    ModelResponse response = provider.complete(ModelRequest.text("purpose", "sys", "user"));

    JsonNode sent = mapper.readTree(body.get());
    assertFalse(sent.has("tools"));
    assertFalse(sent.has("tool_choice"));
    assertFalse(sent.has("output_config"));
    assertEquals("Hello world", response.text());
    assertEquals(3, response.inputTokens());
  }

  @Test
  void anErrorStatusBecomesAProviderErrorWithoutLeakingTheKey() {
    AtomicReference<String> url = new AtomicReference<>();
    AtomicReference<Map<String, String>> headers = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    AnthropicModelProvider provider =
        providerReturning(url, headers, body, 429, "{\"error\":{\"message\":\"rate limited\"}}");
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () -> provider.complete(ModelRequest.text("purpose", "sys", "user")));
    assertEquals(ModelProviderException.Kind.PROVIDER_ERROR, thrown.kind());
    assertTrue(thrown.getMessage().contains("429"));
    assertTrue(thrown.retryable());
    assertFalse(thrown.getMessage().contains("test-key-123"));
  }

  @Test
  void permanentClientErrorsAreNotRetryable() {
    for (int status : new int[] {400, 401, 403, 404}) {
      AnthropicModelProvider provider =
          providerReturning(
              new AtomicReference<>(),
              new AtomicReference<>(),
              new AtomicReference<>(),
              status,
              "{\"error\":{\"type\":\"some_error\",\"message\":\"nope\"}}");
      ModelProviderException thrown =
          assertThrows(
              ModelProviderException.class,
              () -> provider.complete(ModelRequest.text("purpose", "sys", "user")));
      assertEquals(ModelProviderException.Kind.PROVIDER_ERROR, thrown.kind());
      assertFalse(thrown.retryable(), "HTTP " + status + " must not be retryable");
    }
  }

  @Test
  void serverErrorsAreRetryable() {
    for (int status : new int[] {500, 503}) {
      AnthropicModelProvider provider =
          providerReturning(
              new AtomicReference<>(),
              new AtomicReference<>(),
              new AtomicReference<>(),
              status,
              "{\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}");
      ModelProviderException thrown =
          assertThrows(
              ModelProviderException.class,
              () -> provider.complete(ModelRequest.text("purpose", "sys", "user")));
      assertTrue(thrown.retryable(), "HTTP " + status + " must be retryable");
    }
  }

  @Test
  void anErrorBodyNeverLeaksIntoTheExceptionMessage() {
    AnthropicModelProvider provider =
        providerReturning(
            new AtomicReference<>(),
            new AtomicReference<>(),
            new AtomicReference<>(),
            400,
            "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"rejected because BODY_MARKER_7f3a was in the prompt\"}}");
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () -> provider.complete(ModelRequest.text("purpose", "sys", "user")));
    assertTrue(thrown.getMessage().contains("400"));
    assertTrue(thrown.getMessage().contains("invalid_request_error"));
    assertFalse(thrown.getMessage().contains("BODY_MARKER_7f3a"));
  }

  @Test
  void aNonJsonSuccessBodyMessageCarriesNoBodyContent() {
    AnthropicModelProvider provider =
        new AnthropicModelProvider(
            properties(),
            mapper,
            (u, h, b) ->
                new AnthropicModelProvider.TransportResponse(200, "BODY_MARKER_9e1 not json"));
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () -> provider.complete(ModelRequest.text("purpose", "sys", "user")));
    assertEquals(ModelProviderException.Kind.PROVIDER_ERROR, thrown.kind());
    assertFalse(thrown.getMessage().contains("BODY_MARKER_9e1"));
  }

  @Test
  void aTransportFailureIsClassifiedRetryable() {
    AnthropicModelProvider provider =
        new AnthropicModelProvider(
            properties(),
            mapper,
            (u, h, b) -> {
              throw new java.io.IOException("connection reset");
            });
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () -> provider.complete(ModelRequest.text("purpose", "sys", "user")));
    assertEquals(ModelProviderException.Kind.TRANSPORT, thrown.kind());
    assertTrue(thrown.retryable());
  }

  @Test
  void aTextCallAnsweredWithoutAnyTextFailsWithInvalidOutput() {
    AnthropicModelProvider provider =
        providerReturning(
            new AtomicReference<>(),
            new AtomicReference<>(),
            new AtomicReference<>(),
            200,
            "{\"model\":\"test-model\",\"content\":[],"
                + "\"usage\":{\"input_tokens\":3,\"output_tokens\":0}}");
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () -> provider.complete(ModelRequest.text("purpose", "sys", "user")));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, thrown.kind());
    assertFalse(thrown.retryable());
  }

  @Test
  void aTrailingSlashBaseUrlDoesNotDoubleTheSlash() {
    AtomicReference<String> url = new AtomicReference<>();
    AnthropicModelProvider provider =
        new AnthropicModelProvider(
            new AiProperties(
                true, "anthropic", "test-key-123", "test-model", "https://api.anthropic.test/", 30),
            mapper,
            (u, h, b) -> {
              url.set(u);
              return new AnthropicModelProvider.TransportResponse(
                  200,
                  "{\"model\":\"test-model\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");
            });
    provider.complete(ModelRequest.text("purpose", "sys", "user"));
    assertEquals("https://api.anthropic.test/v1/messages", url.get());
  }

  @Test
  void anUnconfiguredProviderRefusesBeforeAnyTransportCall() {
    AtomicReference<String> url = new AtomicReference<>();
    AnthropicModelProvider provider =
        new AnthropicModelProvider(
            new AiProperties(true, "anthropic", "", "test-model", "https://x.test", 30),
            mapper,
            (u, h, b) -> {
              url.set(u);
              return new AnthropicModelProvider.TransportResponse(200, "{}");
            });
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () -> provider.complete(ModelRequest.text("purpose", "sys", "user")));
    assertEquals(ModelProviderException.Kind.UNCONFIGURED, thrown.kind());
    assertEquals(null, url.get());
  }

  @Test
  void aNonJsonSuccessBodyBecomesAProviderError() {
    AnthropicModelProvider provider =
        new AnthropicModelProvider(
            properties(),
            mapper,
            (u, h, b) -> new AnthropicModelProvider.TransportResponse(200, "not json"));
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () -> provider.complete(ModelRequest.text("purpose", "sys", "user")));
    assertEquals(ModelProviderException.Kind.PROVIDER_ERROR, thrown.kind());
  }
}
