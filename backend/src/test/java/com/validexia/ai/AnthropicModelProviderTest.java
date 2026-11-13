package com.validexia.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

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
  void aStructuredCallForcesTheResultToolAndParsesItsInput() throws Exception {
    AtomicReference<String> url = new AtomicReference<>();
    AtomicReference<Map<String, String>> headers = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    AnthropicModelProvider provider =
        providerReturning(
            url,
            headers,
            body,
            200,
            "{\"model\":\"test-model\",\"content\":[{\"type\":\"tool_use\",\"name\":\"emit_result\","
                + "\"input\":{\"verdict\":\"PASS\",\"quotes\":[\"q1\"]}}],"
                + "\"usage\":{\"input_tokens\":11,\"output_tokens\":7}}");

    ModelResponse response =
        provider.complete(ModelRequest.structured("evidence.extraction", "sys", "user", schema()));

    assertEquals("https://api.anthropic.test/v1/messages", url.get());
    assertEquals("test-key-123", headers.get().get("x-api-key"));
    assertEquals("2023-06-01", headers.get().get("anthropic-version"));
    JsonNode sent = mapper.readTree(body.get());
    assertEquals("test-model", sent.path("model").asText());
    assertEquals("emit_result", sent.path("tool_choice").path("name").asText());
    JsonNode inputSchema = sent.path("tools").get(0).path("input_schema");
    assertTrue(inputSchema.path("required").toString().contains("verdict"));
    assertEquals("PASS", response.structured().path("verdict").asText());
    assertEquals("q1", response.structured().path("quotes").get(0).asText());
    assertEquals(11, response.inputTokens());
    assertEquals(7, response.outputTokens());
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

    assertFalse(mapper.readTree(body.get()).has("tools"));
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
    assertFalse(thrown.getMessage().contains("test-key-123"));
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
