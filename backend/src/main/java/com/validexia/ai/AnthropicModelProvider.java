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
 * A real client for the Anthropic Messages API. Structured requests are executed as a forced tool
 * call ({@code emit_result}) whose input schema is the caller's {@link OutputSchema} — the
 * provider's most reliable structured-output path — and the tool input is returned as the
 * structured answer for the gateway to validate. Unstructured requests return the concatenated text
 * blocks.
 *
 * <p>The HTTP call sits behind a {@link Transport} seam so the request shape and response parsing
 * are unit-testable without network access; production uses {@link HttpClient}. The API key is sent
 * only as the {@code x-api-key} header of the provider call itself — it is never logged and never
 * appears in exception messages.
 */
@Component
public class AnthropicModelProvider implements ModelProvider {

  /** Sends one POST and returns the raw response. Implemented by HttpClient in production. */
  interface Transport {
    TransportResponse post(String url, Map<String, String> headers, String body)
        throws IOException, InterruptedException;
  }

  record TransportResponse(int status, String body) {}

  static final String RESULT_TOOL_NAME = "emit_result";
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
    Duration timeout = Duration.ofSeconds(Math.max(1, properties.timeoutSeconds()));
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
      response = transport.post(properties.baseUrl() + "/v1/messages", headers, body);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ModelProviderException(
          ModelProviderException.Kind.TRANSPORT, "Anthropic call was interrupted", e);
    } catch (IOException e) {
      throw new ModelProviderException(
          ModelProviderException.Kind.TRANSPORT, "Anthropic call failed: " + e.getMessage(), e);
    }
    if (response.status() < 200 || response.status() >= 300) {
      throw new ModelProviderException(
          ModelProviderException.Kind.PROVIDER_ERROR,
          "Anthropic returned HTTP " + response.status() + ": " + abbreviate(response.body()));
    }
    return parseResponse(response.body());
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
      ArrayNode tools = root.putArray("tools");
      ObjectNode tool = tools.addObject();
      tool.put("name", RESULT_TOOL_NAME);
      tool.put(
          "description",
          "Emit the final structured result. Call this tool exactly once with the complete"
              + " result; do not answer in free text instead.");
      tool.set("input_schema", request.schema().toJsonSchema(mapper));
      ObjectNode choice = root.putObject("tool_choice");
      choice.put("type", "tool");
      choice.put("name", RESULT_TOOL_NAME);
    }
    return root.toString();
  }

  private ModelResponse parseResponse(String body) {
    JsonNode root;
    try {
      root = mapper.readTree(body);
    } catch (JacksonException e) {
      throw new ModelProviderException(
          ModelProviderException.Kind.PROVIDER_ERROR,
          "Anthropic response was not valid JSON: " + abbreviate(body),
          e);
    }
    StringBuilder text = new StringBuilder();
    @Nullable JsonNode structured = null;
    JsonNode content = root.get("content");
    if (content != null && content.isArray()) {
      for (JsonNode block : content) {
        String type = block.path("type").asText("");
        if (type.equals("text")) {
          text.append(block.path("text").asText(""));
        } else if (type.equals("tool_use")
            && block.path("name").asText("").equals(RESULT_TOOL_NAME)) {
          structured = block.get("input");
        }
      }
    }
    JsonNode usage = root.get("usage");
    int inputTokens = usage == null ? 0 : usage.path("input_tokens").asInt(0);
    int outputTokens = usage == null ? 0 : usage.path("output_tokens").asInt(0);
    return new ModelResponse(
        name(),
        root.path("model").asText(properties.model()),
        text.isEmpty() ? null : text.toString(),
        structured,
        inputTokens,
        outputTokens,
        0);
  }

  private static String abbreviate(@Nullable String body) {
    if (body == null) {
      return "";
    }
    String flat = body.replaceAll("\\s+", " ").strip();
    return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
  }
}
