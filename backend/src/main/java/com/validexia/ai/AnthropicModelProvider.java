package com.validexia.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A real client for the Anthropic Messages API. Structured requests use Anthropic Structured
 * Outputs: the caller's {@link OutputSchema} is sent as {@code output_config.format} (type {@code
 * json_schema}), the API constrains the answer to schema-valid JSON returned in a text block, and
 * that JSON is parsed and returned as the structured answer for the gateway to validate.
 * (Current-generation models reject a forced {@code tool_choice}, so no tool is declared or forced
 * for structured answers.) Unstructured requests return the concatenated text blocks.
 *
 * <p>The HTTP call sits behind a {@link Transport} seam so the request shape and response parsing
 * are unit-testable without network access; production uses {@link HttpClient}. The API key is sent
 * only as the {@code x-api-key} header of the provider call itself — it is never logged and never
 * appears in exception messages. Exception messages likewise never carry provider response bodies
 * (which could echo caller content back): they report the HTTP status and, when the provider sent
 * one, its machine-readable error type — nothing else.
 */
@Component
public class AnthropicModelProvider implements ModelProvider {

  /** Sends one POST and returns the raw response. Implemented by HttpClient in production. */
  interface Transport {
    TransportResponse post(String url, Map<String, String> headers, String body)
        throws IOException, InterruptedException;
  }

  record TransportResponse(int status, String body) {}

  private static final String ANTHROPIC_VERSION = "2023-06-01";

  private final AiProperties properties;
  private final ObjectMapper mapper;
  private final Transport transport;

  @Autowired
  public AnthropicModelProvider(AiProperties properties, ObjectMapper mapper) {
    this(properties, mapper, httpTransport(properties));
  }

  AnthropicModelProvider(AiProperties properties, ObjectMapper mapper, Transport transport) {
    this.properties = properties;
    this.mapper = mapper;
    this.transport = transport;
  }

  private static Transport httpTransport(AiProperties properties) {
    // AiProperties already validates the range; clamp again so a properties instance built by any
    // other path can still never produce a zero or unbounded timeout.
    Duration timeout =
        Duration.ofSeconds(
            Math.max(
                AiProperties.MIN_TIMEOUT_SECONDS,
                Math.min(AiProperties.MAX_TIMEOUT_SECONDS, properties.timeoutSeconds())));
    HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
    return (url, headers, body) -> {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(timeout)
              .POST(HttpRequest.BodyPublishers.ofString(body));
      headers.forEach(builder::header);
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      return new TransportResponse(response.statusCode(), response.body());
    };
  }

  @Override
  public String name() {
    return "anthropic";
  }

  @Override
  public String model() {
    return properties.model();
  }

  @Override
  public ModelResponse complete(ModelRequest request) {
    if (!properties.configured()) {
      throw new ModelProviderException(
          ModelProviderException.Kind.UNCONFIGURED,
          "Anthropic provider is not configured (enabled, API key and model are required)");
    }
    String body = requestBody(request);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("x-api-key", properties.apiKey());
    headers.put("anthropic-version", ANTHROPIC_VERSION);
    headers.put("content-type", "application/json");
    TransportResponse response;
    try {
      response = transport.post(messagesUrl(), headers, body);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ModelProviderException(
          ModelProviderException.Kind.TRANSPORT, "Anthropic call was interrupted", e);
    } catch (IOException e) {
      throw new ModelProviderException(
          ModelProviderException.Kind.TRANSPORT, "Anthropic call failed: " + e.getMessage(), e);
    }
    if (response.status() < 200 || response.status() >= 300) {
      int status = response.status();
      // The provider rejected or failed the call. A 429 or a 5xx is transient — the engine may
      // retry it; a 4xx such as a bad key (401) or a malformed request (400) is permanent for this
      // request and must not burn task attempts. The body is never echoed into the message: it can
      // contain provider prose and fragments of the caller's own content.
      boolean retryable = status == 429 || status >= 500;
      throw new ModelProviderException(
          ModelProviderException.Kind.PROVIDER_ERROR,
          retryable,
          "Anthropic returned HTTP " + status + errorTypeSuffix(response.body()));
    }
    ModelResponse parsed = parseResponse(response.body(), request.schema() != null);
    if (request.schema() == null && parsed.text() == null) {
      // An unstructured call that produced no text at all answered nothing. Returning a response
      // with null text would let a caller mistake a provider malfunction for a genuine answer.
      throw new ModelProviderException(
          ModelProviderException.Kind.INVALID_OUTPUT,
          "Anthropic answered a text request without any text content");
    }
    return parsed;
  }

  /** The Messages endpoint URL; a trailing slash on the configured base URL never doubles. */
  private String messagesUrl() {
    return properties.baseUrl().replaceAll("/+$", "") + "/v1/messages";
  }

  /**
   * Extracts the provider's machine-readable error type (e.g. {@code rate_limit_error}) from an
   * error body, for the exception message. Only the type token is taken — never the free-text error
   * message or any other body content — and anything that is not a plain token is dropped, so a
   * malformed or hostile body degrades to the bare HTTP status.
   */
  private String errorTypeSuffix(@Nullable String body) {
    if (body == null) {
      return "";
    }
    try {
      String type = mapper.readTree(body).path("error").path("type").asText("");
      return type.matches("[a-z0-9_]{1,64}") ? " (error type: " + type + ")" : "";
    } catch (JacksonException e) {
      return "";
    }
  }

  /** Builds the Messages API request body. Package-private so tests can assert its shape. */
  String requestBody(ModelRequest request) {
    ObjectNode root = mapper.createObjectNode();
    root.put("model", properties.model());
    root.put("max_tokens", request.maxOutputTokens());
    root.put("system", request.systemPrompt());
    ArrayNode messages = root.putArray("messages");
    ObjectNode user = messages.addObject();
    user.put("role", "user");
    user.put("content", request.userPrompt());
    if (request.schema() != null) {
      ObjectNode format = root.putObject("output_config").putObject("format");
      format.put("type", "json_schema");
      format.set("schema", request.schema().toJsonSchema(mapper));
    }
    return root.toString();
  }

  private ModelResponse parseResponse(String body, boolean structuredRequest) {
    JsonNode root;
    try {
      root = mapper.readTree(body);
    } catch (JacksonException e) {
      throw new ModelProviderException(
          ModelProviderException.Kind.PROVIDER_ERROR, "Anthropic response was not valid JSON", e);
    }
    StringBuilder text = new StringBuilder();
    JsonNode content = root.get("content");
    if (content != null && content.isArray()) {
      for (JsonNode block : content) {
        if (block.path("type").asText("").equals("text")) {
          text.append(block.path("text").asText(""));
        }
      }
    }
    String combinedText = text.isEmpty() ? null : text.toString();
    @Nullable JsonNode structured = null;
    if (structuredRequest) {
      structured = parseStructuredAnswer(root, combinedText);
    }
    JsonNode usage = root.get("usage");
    int inputTokens = usage == null ? 0 : usage.path("input_tokens").asInt(0);
    int outputTokens = usage == null ? 0 : usage.path("output_tokens").asInt(0);
    return new ModelResponse(
        name(),
        root.path("model").asText(properties.model()),
        combinedText,
        structured,
        inputTokens,
        outputTokens,
        0);
  }

  /**
   * Extracts the structured answer of a Structured Outputs response. The answer is the JSON carried
   * in the response's text blocks; anything else — a refusal, a truncated answer, missing text, or
   * text that is not valid JSON — fails closed as {@link
   * ModelProviderException.Kind#INVALID_OUTPUT} rather than degrading into a partial or fabricated
   * result. The gateway still validates the parsed JSON against the caller's schema afterwards;
   * parsing here only establishes that the answer is JSON at all.
   */
  private JsonNode parseStructuredAnswer(JsonNode root, @Nullable String combinedText) {
    String stopReason = root.path("stop_reason").asText("");
    if (stopReason.equals("refusal")) {
      throw new ModelProviderException(
          ModelProviderException.Kind.INVALID_OUTPUT,
          "Anthropic refused the structured request; no structured result was produced");
    }
    if (stopReason.equals("max_tokens")) {
      // A truncated answer is incomplete by definition: fail before parsing, because a cut-off
      // JSON document must never be mistaken for a complete result.
      throw new ModelProviderException(
          ModelProviderException.Kind.INVALID_OUTPUT,
          "Anthropic hit the output token limit; the structured result is incomplete");
    }
    if (combinedText == null || combinedText.isBlank()) {
      throw new ModelProviderException(
          ModelProviderException.Kind.INVALID_OUTPUT,
          "Anthropic answered a structured request without any text content");
    }
    try {
      return mapper.readTree(combinedText);
    } catch (JacksonException e) {
      throw new ModelProviderException(
          ModelProviderException.Kind.INVALID_OUTPUT,
          "Anthropic's structured answer was not valid JSON",
          e);
    }
  }
}
