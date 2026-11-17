package com.validexia.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Deterministic tests for the Groq adapter: every response is a fixture behind the transport seam,
 * no network is touched, and no real key exists in this file. The cases mirror the Anthropic
 * adapter's — the two providers must fail in the same ways for the same reasons.
 */
class GroqModelProviderTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private AiProperties properties() {
    return new AiProperties(
        true, "groq", "test-key-123", "openai/gpt-oss-120b", "https://api.groq.test/openai/v1", 30);
  }

  /** A schema shaped like the Vendor Intelligence Brief's: required fields plus one optional. */
  private OutputSchema schema() {
    return new OutputSchema(
        List.of(
            OutputSchema.Field.required("summary", OutputSchema.Type.STRING),
            OutputSchema.Field.requiredStringArray("keyFacts"),
            OutputSchema.Field.required("abstained", OutputSchema.Type.BOOLEAN),
            OutputSchema.Field.optional("abstentionReason", OutputSchema.Type.STRING)));
  }

  private GroqModelProvider providerReturning(
      AtomicReference<String> capturedUrl,
      AtomicReference<Map<String, String>> capturedHeaders,
      AtomicReference<String> capturedBody,
      AtomicInteger callCount,
      int status,
      String responseBody) {
    return new GroqModelProvider(
        properties(),
        mapper,
        (url, headers, body) -> {
          capturedUrl.set(url);
          capturedHeaders.set(headers);
          capturedBody.set(body);
          callCount.incrementAndGet();
          return new GroqModelProvider.TransportResponse(status, responseBody);
        });
  }

  private GroqModelProvider providerReturning(int status, String responseBody) {
    return providerReturning(
        new AtomicReference<>(),
        new AtomicReference<>(),
        new AtomicReference<>(),
        new AtomicInteger(),
        status,
        responseBody);
  }

  private static String completion(String finishReason, String contentJson) {
    return "{\"model\":\"openai/gpt-oss-120b\",\"choices\":[{\"finish_reason\":\""
        + finishReason
        + "\",\"message\":{\"role\":\"assistant\",\"content\":"
        + contentJson
        + "}}],\"usage\":{\"prompt_tokens\":21,\"completion_tokens\":9}}";
  }

  private static String jsonString(String raw) {
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  @Test
  void aStructuredCallUsesStrictJsonSchemaAndParsesTheAnswer() throws Exception {
    AtomicReference<String> url = new AtomicReference<>();
    AtomicReference<Map<String, String>> headers = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    AtomicInteger calls = new AtomicInteger();
    GroqModelProvider provider =
        providerReturning(
            url,
            headers,
            body,
            calls,
            200,
            completion(
                "stop",
                jsonString(
                    "{\"summary\":\"A vendor.\",\"keyFacts\":[\"f1\"],\"abstained\":false,"
                        + "\"abstentionReason\":\"\"}")));

    ModelResponse response =
        provider.complete(ModelRequest.structured("vendor.intelligence", "sys", "user", schema()));

    assertEquals("https://api.groq.test/openai/v1/chat/completions", url.get());
    assertEquals("Bearer test-key-123", headers.get().get("Authorization"));
    JsonNode sent = mapper.readTree(body.get());
    assertEquals("openai/gpt-oss-120b", sent.path("model").asText());
    assertEquals("sys", sent.path("messages").get(0).path("content").asText());
    assertEquals("system", sent.path("messages").get(0).path("role").asText());
    assertEquals("user", sent.path("messages").get(1).path("content").asText());
    JsonNode format = sent.path("response_format");
    assertEquals("json_schema", format.path("type").asText());
    assertTrue(format.path("json_schema").path("strict").asBoolean());
    JsonNode sentSchema = format.path("json_schema").path("schema");
    assertFalse(sentSchema.path("additionalProperties").asBoolean(true));
    // Strict mode: EVERY field is required, including the one our schema marks optional, and
    // the optional field's type gains the null union that makes its absence representable.
    String required = sentSchema.path("required").toString();
    assertTrue(required.contains("summary"));
    assertTrue(required.contains("keyFacts"));
    assertTrue(required.contains("abstained"));
    assertTrue(required.contains("abstentionReason"));
    JsonNode reasonType = sentSchema.path("properties").path("abstentionReason").path("type");
    assertTrue(reasonType.isArray());
    assertEquals("string", reasonType.get(0).asText());
    assertEquals("null", reasonType.get(1).asText());
    assertEquals("A vendor.", response.structured().path("summary").asText());
    assertEquals("f1", response.structured().path("keyFacts").get(0).asText());
    assertEquals(21, response.inputTokens());
    assertEquals(9, response.outputTokens());
    assertEquals(1, calls.get());
  }

  @Test
  void aNullOptionalFieldIsTranslatedBackToAbsent() {
    GroqModelProvider provider =
        providerReturning(
            200,
            completion(
                "stop",
                jsonString(
                    "{\"summary\":\"Thin.\",\"keyFacts\":[],\"abstained\":true,"
                        + "\"abstentionReason\":null}")));

    ModelResponse response =
        provider.complete(ModelRequest.structured("vendor.intelligence", "sys", "user", schema()));

    assertTrue(response.structured().path("abstained").asBoolean());
    assertFalse(response.structured().has("abstentionReason"));
  }

  @Test
  void aNullRequiredFieldSurvivesForTheGatewayValidatorToReject() {
    GroqModelProvider provider =
        providerReturning(
            200,
            completion(
                "stop", jsonString("{\"summary\":null,\"keyFacts\":[],\"abstained\":false}")));

    ModelResponse response =
        provider.complete(ModelRequest.structured("vendor.intelligence", "sys", "user", schema()));

    // The provider parses; it does not validate. The shared validator must reject the null
    // required field — this is the fail-closed chain the gateway enforces for every provider.
    assertTrue(response.structured().has("summary"));
    ModelProviderException e =
        assertThrows(
            ModelProviderException.class,
            () -> new StructuredOutputValidator().requireValid(response.structured(), schema()));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, e.kind());
  }

  @Test
  void aStructuredAnswerThatIsNotJsonFailsClosed() {
    GroqModelProvider provider = providerReturning(200, completion("stop", jsonString("not json")));

    ModelProviderException e =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("vendor.intelligence", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, e.kind());
    assertFalse(e.retryable());
  }

  @Test
  void aResponseBodyThatIsNotJsonIsAProviderError() {
    GroqModelProvider provider = providerReturning(200, "garbage{{{");

    ModelProviderException e =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("vendor.intelligence", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.PROVIDER_ERROR, e.kind());
  }

  @Test
  void anAuthenticationFailureIsPermanentAndNeverEchoesTheKey() {
    // The provider's error message deliberately contains the key string: only the error TYPE
    // token may reach the exception message, never body content.
    GroqModelProvider provider =
        providerReturning(
            401,
            "{\"error\":{\"message\":\"Invalid API Key: test-key-123\","
                + "\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}");

    ModelProviderException e =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("vendor.intelligence", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.PROVIDER_ERROR, e.kind());
    assertFalse(e.retryable());
    assertTrue(e.getMessage().contains("401"));
    assertTrue(e.getMessage().contains("invalid_request_error"));
    assertFalse(e.getMessage().contains("test-key-123"));
  }

  @Test
  void aRateLimitIsClassifiedRetryableButNeverRetriedHere() {
    AtomicInteger calls = new AtomicInteger();
    GroqModelProvider provider =
        providerReturning(
            new AtomicReference<>(),
            new AtomicReference<>(),
            new AtomicReference<>(),
            calls,
            429,
            "{\"error\":{\"message\":\"slow down\",\"type\":\"rate_limit_error\"}}");

    ModelProviderException e =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("vendor.intelligence", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.PROVIDER_ERROR, e.kind());
    // Classification metadata only — the adapter itself made exactly one call.
    assertTrue(e.retryable());
    assertEquals(1, calls.get());
  }

  @Test
  void aTimeoutIsATransportFailure() {
    GroqModelProvider provider =
        new GroqModelProvider(
            properties(),
            mapper,
            (url, headers, body) -> {
              throw new HttpTimeoutException("request timed out");
            });

    ModelProviderException e =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("vendor.intelligence", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.TRANSPORT, e.kind());
    assertTrue(e.retryable());
  }

  @Test
  void aNetworkFailureIsATransportFailure() {
    GroqModelProvider provider =
        new GroqModelProvider(
            properties(),
            mapper,
            (url, headers, body) -> {
              throw new IOException("connection reset");
            });

    ModelProviderException e =
        assertThrows(
            ModelProviderException.class,
            () -> provider.complete(ModelRequest.text("some.purpose", "sys", "user")));
    assertEquals(ModelProviderException.Kind.TRANSPORT, e.kind());
    assertTrue(e.retryable());
  }

  @Test
  void aTruncatedAnswerFailsClosedEvenWhenTheJsonIsComplete() {
    // finish_reason "length" means the provider stopped mid-answer; even a fixture whose JSON
    // happens to parse must be rejected, because in reality that JSON is a prefix of the answer.
    GroqModelProvider provider =
        providerReturning(
            200,
            completion(
                "length",
                jsonString("{\"summary\":\"A vendor.\",\"keyFacts\":[],\"abstained\":false}")));

    ModelProviderException e =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("vendor.intelligence", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, e.kind());
    assertTrue(e.getMessage().contains("incomplete"));
  }

  @Test
  void aMissingFinishReasonFailsClosed() {
    String body =
        "{\"model\":\"openai/gpt-oss-120b\",\"choices\":[{\"message\":{\"content\":"
            + jsonString("{\"summary\":\"x\",\"keyFacts\":[],\"abstained\":false}")
            + "}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";
    GroqModelProvider provider = providerReturning(200, body);

    ModelProviderException e =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("vendor.intelligence", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, e.kind());
    assertTrue(e.getMessage().contains("<missing>"));
  }

  @Test
  void anUnexpectedFinishReasonFailsClosed() {
    GroqModelProvider provider = providerReturning(200, completion("tool_calls", jsonString("{}")));

    ModelProviderException e =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("vendor.intelligence", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, e.kind());
    assertTrue(e.getMessage().contains("tool_calls"));
  }

  @Test
  void aRefusalFailsClosed() {
    String body =
        "{\"model\":\"openai/gpt-oss-120b\",\"choices\":[{\"finish_reason\":\"stop\","
            + "\"message\":{\"role\":\"assistant\",\"content\":\"\",\"refusal\":\"I cannot help"
            + " with that.\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";
    GroqModelProvider provider = providerReturning(200, body);

    ModelProviderException e =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("vendor.intelligence", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, e.kind());
    assertTrue(e.getMessage().contains("refused"));
  }

  @Test
  void aStructuredAnswerWithoutContentFailsClosed() {
    GroqModelProvider provider = providerReturning(200, completion("stop", "\"\""));

    ModelProviderException e =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("vendor.intelligence", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, e.kind());
  }

  @Test
  void anUnstructuredCallReturnsTextAndSendsNoSchema() throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    GroqModelProvider provider =
        providerReturning(
            new AtomicReference<>(),
            new AtomicReference<>(),
            body,
            new AtomicInteger(),
            200,
            completion("stop", jsonString("plain answer")));

    ModelResponse response = provider.complete(ModelRequest.text("some.purpose", "sys", "user"));

    assertEquals("plain answer", response.text());
    assertFalse(mapper.readTree(body.get()).has("response_format"));
  }

  @Test
  void anUnstructuredAnswerWithoutTextFailsClosed() {
    GroqModelProvider provider = providerReturning(200, completion("stop", "\"\""));

    ModelProviderException e =
        assertThrows(
            ModelProviderException.class,
            () -> provider.complete(ModelRequest.text("some.purpose", "sys", "user")));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, e.kind());
  }

  @Test
  void anUnconfiguredProviderFailsClosedWithoutCallingOut() {
    AtomicInteger calls = new AtomicInteger();
    GroqModelProvider provider =
        new GroqModelProvider(
            new AiProperties(true, "groq", "", "openai/gpt-oss-120b", "https://api.groq.test", 30),
            mapper,
            (url, headers, body) -> {
              calls.incrementAndGet();
              return new GroqModelProvider.TransportResponse(200, "{}");
            });

    ModelProviderException e =
        assertThrows(
            ModelProviderException.class,
            () ->
                provider.complete(
                    ModelRequest.structured("vendor.intelligence", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.UNCONFIGURED, e.kind());
    assertEquals(0, calls.get());
  }
}
