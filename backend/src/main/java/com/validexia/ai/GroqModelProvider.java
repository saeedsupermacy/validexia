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
 * A real client for Groq's OpenAI-compatible Chat Completions API. The provider is selected by
 * configuration exactly like the Anthropic one ({@code validexia.ai.provider=groq}, with the model
 * — e.g. {@code openai/gpt-oss-120b} — and the base URL {@code https://api.groq.com/openai/v1}
 * coming from the same environment-driven {@link AiProperties}); nothing Groq-specific leaks into
 * the gateway, the tools, or any workflow.
 *
 * <p>Structured requests use Groq's strict JSON Schema mode ({@code response_format} with {@code
 * strict: true}): the API constrains generation to the schema, and the JSON carried in the message
 * content is parsed and returned as the structured answer for the gateway to validate. Groq's
 * strict mode imposes two rules our {@link OutputSchema} emission does not natively meet, so the
 * adapter translates the schema at the boundary (see {@link #strictSchema}): every field must be
 * listed in {@code required}, and optional fields are expressed as a union with {@code null}.
 * Symmetrically, a {@code null} the model emits for an optional field is translated back — removed
 * — before the answer is returned, so the structured result has the same shape (an absent optional
 * field) whichever provider produced it. A {@code null} for a required field is left in place for
 * the gateway's validator to reject.
 *
 * <p>Fail-closed semantics mirror the Anthropic adapter. A structured answer is accepted only when
 * the completion's {@code finish_reason} is {@code stop} — Groq does not publish a full
 * finish-reason enumeration, so an allowlist (rather than a denylist of known-bad values) is the
 * safe reading: {@code length} is a truncated, incomplete answer, and any other or missing value
 * describes a response shape this code was not written against. A provider refusal carried in the
 * message's {@code refusal} field likewise fails as {@link
 * ModelProviderException.Kind#INVALID_OUTPUT}. Schema compliance is not correctness: the gateway
 * still validates the parsed JSON against the caller's schema, and downstream citation and
 * provenance checks apply unchanged.
 *
 * <p>The HTTP call sits behind a {@link Transport} seam so the request shape and response parsing
 * are unit-testable without network access; production uses {@link HttpClient}. The API key is sent
 * only as the {@code Authorization: Bearer} header of the provider call itself — it is never logged
 * and never appears in exception messages. Exception messages likewise never carry provider
 * response bodies (which could echo caller content back): they report the HTTP status and, when the
 * provider sent one, its machine-readable error type — nothing else.
 *
 * <p>Deliberately not sent: any reasoning-effort parameter. GPT-OSS models reason by default and
 * the reasoning shares the completion budget, but choosing an effort level is a model-quality
 * decision that belongs to the provider evaluation, not to this adapter; a truncated answer already
 * fails closed as {@code INVALID_OUTPUT} rather than degrading silently.
 */
@Component
public class GroqModelProvider implements ModelProvider {

  /** Sends one POST and returns the raw response. Implemented by HttpClient in production. */
  interface Transport {
    TransportResponse post(String url, Map<String, String> headers, String body)
        throws IOException, InterruptedException;
  }

  record TransportResponse(int status, String body) {}

  /** Name carried in the strict-mode {@code json_schema} block; Groq accepts word characters. */
  private static final String RESULT_SCHEMA_NAME = "result";

  private final AiProperties properties;
  private final ObjectMapper mapper;
  private final Transport transport;

  @Autowired
  public GroqModelProvider(AiProperties properties, ObjectMapper mapper) {
    this(properties, mapper, httpTransport(properties));
  }

  GroqModelProvider(AiProperties properties, ObjectMapper mapper, Transport transport) {
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
    return "groq";
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
          "Groq provider is not configured (enabled, API key and model are required)");
    }
    String body = requestBody(request);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer " + properties.apiKey());
    headers.put("content-type", "application/json");
    TransportResponse response;
    try {
      response = transport.post(chatCompletionsUrl(), headers, body);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ModelProviderException(
          ModelProviderException.Kind.TRANSPORT, "Groq call was interrupted", e);
    } catch (IOException e) {
      throw new ModelProviderException(
          ModelProviderException.Kind.TRANSPORT, "Groq call failed: " + e.getMessage(), e);
    }
    if (response.status() < 200 || response.status() >= 300) {
      int status = response.status();
      // The provider rejected or failed the call. The retryable flag below is classification
      // metadata only: a 429 or a 5xx marks the failure as transient (a deliberate future retry
      // by a caller could succeed), while a 4xx such as a bad key (401) or a malformed request
      // (400) is permanent for this request. Nothing in the current execution path retries
      // automatically — this provider makes exactly one call (in particular, a 429 is NOT
      // retried here), the gateway rethrows failures unchanged, and the engine settles a failed
      // task without re-executing it. The body is never echoed into the message: it can contain
      // provider prose and fragments of the caller's own content.
      boolean retryable = status == 429 || status >= 500;
      throw new ModelProviderException(
          ModelProviderException.Kind.PROVIDER_ERROR,
          retryable,
          "Groq returned HTTP " + status + errorTypeSuffix(response.body()));
    }
    ModelResponse parsed = parseResponse(response.body(), request);
    if (request.schema() == null && parsed.text() == null) {
      // An unstructured call that produced no text at all answered nothing. Returning a response
      // with null text would let a caller mistake a provider malfunction for a genuine answer.
      throw new ModelProviderException(
          ModelProviderException.Kind.INVALID_OUTPUT,
          "Groq answered a text request without any text content");
    }
    return parsed;
  }

  /** The Chat Completions endpoint URL; a trailing slash on the base URL never doubles. */
  private String chatCompletionsUrl() {
    return properties.baseUrl().replaceAll("/+$", "") + "/chat/completions";
  }

  /**
   * Extracts the provider's machine-readable error type (e.g. {@code invalid_request_error}) from
   * an error body, for the exception message. Only the type token is taken — never the free-text
   * error message or any other body content — and anything that is not a plain token is dropped, so
   * a malformed or hostile body degrades to the bare HTTP status.
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

  /** Builds the Chat Completions request body. Package-private so tests can assert its shape. */
  String requestBody(ModelRequest request) {
    ObjectNode root = mapper.createObjectNode();
    root.put("model", properties.model());
    root.put("max_completion_tokens", request.maxOutputTokens());
    ArrayNode messages = root.putArray("messages");
    ObjectNode system = messages.addObject();
    system.put("role", "system");
    system.put("content", request.systemPrompt());
    ObjectNode user = messages.addObject();
    user.put("role", "user");
    user.put("content", request.userPrompt());
    if (request.schema() != null) {
      ObjectNode format = root.putObject("response_format");
      format.put("type", "json_schema");
      ObjectNode jsonSchema = format.putObject("json_schema");
      jsonSchema.put("name", RESULT_SCHEMA_NAME);
      jsonSchema.put("strict", true);
      jsonSchema.set("schema", strictSchema(request.schema()));
    }
    return root.toString();
  }

  /**
   * Translates our {@link OutputSchema} emission into the shape Groq's strict mode requires: every
   * declared field appears in {@code required}, and a field our schema marks optional is given a
   * {@code null} alternative — appended to its {@code enum} values when it has any, otherwise as a
   * union on its {@code type} — because strict mode admits no absent properties. The translation is
   * one level deep because the schema subset itself is flat (object-typed fields declare no inner
   * properties). Our own validator remains the authority afterwards: it checks the answer against
   * the untranslated schema.
   */
  private ObjectNode strictSchema(OutputSchema schema) {
    ObjectNode root = schema.toJsonSchema(mapper);
    JsonNode propertiesNode = root.get("properties");
    ArrayNode required = mapper.createArrayNode();
    for (OutputSchema.Field field : schema.fields()) {
      required.add(field.name());
      if (!field.required() && propertiesNode instanceof ObjectNode properties) {
        JsonNode property = properties.get(field.name());
        if (property instanceof ObjectNode propertyObject) {
          if (propertyObject.get("enum") instanceof ArrayNode enumValues) {
            enumValues.addNull();
          } else if (propertyObject.has("type")) {
            String type = propertyObject.path("type").asText();
            ArrayNode union = mapper.createArrayNode();
            union.add(type);
            union.add("null");
            propertyObject.set("type", union);
          }
        }
      }
    }
    root.set("required", required);
    return root;
  }

  private ModelResponse parseResponse(String body, ModelRequest request) {
    JsonNode root;
    try {
      root = mapper.readTree(body);
    } catch (JacksonException e) {
      throw new ModelProviderException(
          ModelProviderException.Kind.PROVIDER_ERROR, "Groq response was not valid JSON", e);
    }
    JsonNode choice =
        root.path("choices").isArray() && !root.path("choices").isEmpty()
            ? root.path("choices").get(0)
            : null;
    JsonNode message = choice == null ? null : choice.get("message");
    @Nullable String content = null;
    if (message != null && message.get("content") != null && message.get("content").isString()) {
      content = message.get("content").asText();
    }
    @Nullable JsonNode structured = null;
    if (request.schema() != null) {
      structured = parseStructuredAnswer(choice, message, content, request.schema());
    }
    JsonNode usage = root.get("usage");
    int inputTokens = usage == null ? 0 : usage.path("prompt_tokens").asInt(0);
    int outputTokens = usage == null ? 0 : usage.path("completion_tokens").asInt(0);
    return new ModelResponse(
        name(),
        root.path("model").asText(properties.model()),
        content,
        structured,
        inputTokens,
        outputTokens,
        0);
  }

  /**
   * Extracts the structured answer of a strict-mode response. The answer is the JSON carried in the
   * message content, and it is accepted only when the completion's {@code finish_reason} is {@code
   * stop}, the successful completion. Anything else — a refusal (carried in the message's {@code
   * refusal} field or as a non-stop finish), a truncated answer ({@code length}, rejected before
   * parsing because a cut-off JSON document must never be mistaken for a complete result), a
   * missing or unexpected finish reason, missing content, or content that is not valid JSON — fails
   * closed as {@link ModelProviderException.Kind#INVALID_OUTPUT}. A {@code null} emitted for an
   * optional field is removed, translating Groq's strict-mode encoding back into ours (an absent
   * optional field); a {@code null} for a required field is left for the gateway's validator to
   * reject. The gateway still validates the parsed JSON against the caller's schema afterwards;
   * parsing here only establishes that the answer is JSON at all.
   */
  private JsonNode parseStructuredAnswer(
      @Nullable JsonNode choice,
      @Nullable JsonNode message,
      @Nullable String content,
      OutputSchema schema) {
    if (message != null) {
      String refusal = message.path("refusal").asText("");
      if (!refusal.isBlank()) {
        throw new ModelProviderException(
            ModelProviderException.Kind.INVALID_OUTPUT,
            "Groq refused the structured request; no structured result was produced");
      }
    }
    String finishReason = choice == null ? "" : choice.path("finish_reason").asText("");
    if (finishReason.equals("length")) {
      throw new ModelProviderException(
          ModelProviderException.Kind.INVALID_OUTPUT,
          "Groq hit the output token limit; the structured result is incomplete");
    }
    if (!finishReason.equals("stop")) {
      // Fully fail-closed: stop is the only accepted completion. A missing finish_reason, or any
      // other value (tool_calls, content_filter, or a future addition), describes a response
      // shape this code was not written against — reject it rather than guessing. The value is
      // echoed only when it is a plain token, matching the error-hygiene rule used for provider
      // error types.
      String shown = finishReason.matches("[a-z0-9_]{1,64}") ? finishReason : "<unexpected>";
      throw new ModelProviderException(
          ModelProviderException.Kind.INVALID_OUTPUT,
          "Groq's structured answer ended without a successful completion (finish_reason: "
              + (finishReason.isEmpty() ? "<missing>" : shown)
              + ")");
    }
    if (content == null || content.isBlank()) {
      throw new ModelProviderException(
          ModelProviderException.Kind.INVALID_OUTPUT,
          "Groq answered a structured request without any text content");
    }
    JsonNode parsed;
    try {
      parsed = mapper.readTree(content);
    } catch (JacksonException e) {
      throw new ModelProviderException(
          ModelProviderException.Kind.INVALID_OUTPUT,
          "Groq's structured answer was not valid JSON",
          e);
    }
    if (parsed instanceof ObjectNode object) {
      for (OutputSchema.Field field : schema.fields()) {
        if (!field.required()
            && object.get(field.name()) != null
            && object.get(field.name()).isNull()) {
          object.remove(field.name());
        }
      }
    }
    return parsed;
  }
}
