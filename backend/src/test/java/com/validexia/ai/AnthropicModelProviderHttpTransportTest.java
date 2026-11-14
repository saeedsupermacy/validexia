package com.validexia.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exercises the provider's PRODUCTION transport (a real {@link java.net.http.HttpClient}) against a
 * JDK built-in HTTP server on an ephemeral localhost port. The seam-based tests in {@link
 * AnthropicModelProviderTest} prove request/response logic; these prove the actual wire behaviour —
 * headers sent, status classification, transport failure — that the seam cannot see.
 */
class AnthropicModelProviderHttpTransportTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private HttpServer server;
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
  private final AtomicReference<String> seenApiKey = new AtomicReference<>();
  private final AtomicReference<String> seenContentType = new AtomicReference<>();
  private final AtomicReference<String> seenPath = new AtomicReference<>();
  private final AtomicReference<String> seenRequestBody = new AtomicReference<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/messages",
        exchange -> {
          seenApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
          seenContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          seenPath.set(exchange.getRequestURI().getPath());
          seenRequestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] payload = responseBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status.get(), payload.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
          }
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private AnthropicModelProvider realProvider() {
    return realProvider("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private AnthropicModelProvider realProvider(String baseUrl) {
    AiProperties properties =
        new AiProperties(true, "anthropic", "real-transport-key", "test-model", baseUrl, 30);
    // The two-argument constructor wires the production HttpClient transport.
    return new AnthropicModelProvider(properties, mapper);
  }

  @Test
  void aSuccessfulCallUsesTheRealTransportAndSendsTheKeyHeader() {
    status.set(200);
    responseBody.set(
        "{\"model\":\"test-model\",\"content\":[{\"type\":\"text\",\"text\":\"real answer\"}],"
            + "\"usage\":{\"input_tokens\":4,\"output_tokens\":2}}");

    ModelResponse response = realProvider().complete(ModelRequest.text("purpose", "sys", "user"));

    assertEquals("real answer", response.text());
    assertEquals("/v1/messages", seenPath.get());
    assertEquals("real-transport-key", seenApiKey.get());
    assertTrue(seenContentType.get().contains("application/json"));
    assertTrue(seenRequestBody.get().contains("\"model\":\"test-model\""));
  }

  @Test
  void aStructuredCallOverTheRealTransportSendsOutputConfigAndNoForcedTool() throws Exception {
    status.set(200);
    responseBody.set(
        "{\"model\":\"test-model\",\"stop_reason\":\"end_turn\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"{\\\"verdict\\\":\\\"PASS\\\"}\"}],"
            + "\"usage\":{\"input_tokens\":9,\"output_tokens\":3}}");
    OutputSchema schema =
        new OutputSchema(List.of(OutputSchema.Field.required("verdict", OutputSchema.Type.STRING)));

    ModelResponse response =
        realProvider()
            .complete(ModelRequest.structured("evidence.extraction", "sys", "user", schema));

    assertEquals("PASS", response.structured().path("verdict").asText());
    JsonNode sent = mapper.readTree(seenRequestBody.get());
    assertFalse(sent.has("tools"));
    assertFalse(sent.has("tool_choice"));
    assertEquals("json_schema", sent.path("output_config").path("format").path("type").asText());
    assertFalse(
        sent.path("output_config")
            .path("format")
            .path("schema")
            .path("additionalProperties")
            .asBoolean(true));
  }

  @Test
  void aRateLimitFromTheRealTransportIsRetryableAndLeaksNothing() {
    status.set(429);
    responseBody.set(
        "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\","
            + "\"message\":\"slow down, BODY_MARKER_4c2 was too fast\"}}");

    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () -> realProvider().complete(ModelRequest.text("purpose", "sys", "user")));

    assertEquals(ModelProviderException.Kind.PROVIDER_ERROR, thrown.kind());
    assertTrue(thrown.retryable());
    assertTrue(thrown.getMessage().contains("429"));
    assertFalse(thrown.getMessage().contains("BODY_MARKER_4c2"));
    assertFalse(thrown.getMessage().contains("real-transport-key"));
  }

  @Test
  void anAuthFailureFromTheRealTransportIsNotRetryable() {
    status.set(401);
    responseBody.set(
        "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
            + "\"message\":\"invalid key BODY_MARKER_8d1\"}}");

    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () -> realProvider().complete(ModelRequest.text("purpose", "sys", "user")));

    assertEquals(ModelProviderException.Kind.PROVIDER_ERROR, thrown.kind());
    assertFalse(thrown.retryable());
    assertTrue(thrown.getMessage().contains("401"));
    assertFalse(thrown.getMessage().contains("BODY_MARKER_8d1"));
  }

  @Test
  void aRefusedConnectionIsARetryableTransportFailure() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      closedPort = socket.getLocalPort();
    }

    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () ->
                realProvider("http://127.0.0.1:" + closedPort)
                    .complete(ModelRequest.text("purpose", "sys", "user")));

    assertEquals(ModelProviderException.Kind.TRANSPORT, thrown.kind());
    assertTrue(thrown.retryable());
  }
}
