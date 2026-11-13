package com.validexia.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModelGatewayTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private static final class FakeProvider implements ModelProvider {
    private final ModelResponse response;
    private final AtomicInteger calls = new AtomicInteger();

    FakeProvider(ModelResponse response) {
      this.response = response;
    }

    @Override
    public String name() {
      return "anthropic";
    }

    @Override
    public String model() {
      return "fake-model";
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
      calls.incrementAndGet();
      return response;
    }
  }

  private AiProperties configured() {
    return new AiProperties(true, "anthropic", "test-key", "fake-model", "https://x.test", 30);
  }

  private OutputSchema schema() {
    return new OutputSchema(
        List.of(OutputSchema.Field.required("verdict", OutputSchema.Type.STRING)));
  }

  private ModelResponse structuredResponse(String json) throws Exception {
    return new ModelResponse("anthropic", "fake-model", null, mapper.readTree(json), 10, 5, 0);
  }

  @Test
  void anUnconfiguredDeploymentFailsClosedWithoutCallingTheProvider() throws Exception {
    FakeProvider provider = new FakeProvider(structuredResponse("{\"verdict\":\"PASS\"}"));
    ModelGateway gateway =
        new ModelGateway(
            List.of(provider),
            new AiProperties(false, "anthropic", "", "", "https://x.test", 30),
            new StructuredOutputValidator());
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () ->
                gateway.execute(
                    ModelRequest.structured("evidence.extraction", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.UNCONFIGURED, thrown.kind());
    assertEquals(0, provider.calls.get());
  }

  @Test
  void anUnknownConfiguredProviderFailsClosed() throws Exception {
    FakeProvider provider = new FakeProvider(structuredResponse("{\"verdict\":\"PASS\"}"));
    ModelGateway gateway =
        new ModelGateway(
            List.of(provider),
            new AiProperties(true, "other-provider", "k", "m", "https://x.test", 30),
            new StructuredOutputValidator());
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () -> gateway.execute(ModelRequest.text("purpose", "sys", "user")));
    assertEquals(ModelProviderException.Kind.UNCONFIGURED, thrown.kind());
    assertEquals(0, provider.calls.get());
  }

  @Test
  void aValidStructuredAnswerPassesAndCarriesMeasuredLatency() throws Exception {
    FakeProvider provider = new FakeProvider(structuredResponse("{\"verdict\":\"PASS\"}"));
    ModelGateway gateway =
        new ModelGateway(List.of(provider), configured(), new StructuredOutputValidator());
    ModelResponse response =
        gateway.execute(ModelRequest.structured("evidence.extraction", "sys", "user", schema()));
    assertEquals("PASS", response.structured().path("verdict").asText());
    assertEquals(10, response.inputTokens());
    assertTrue(response.latencyMs() >= 0);
    assertEquals(1, provider.calls.get());
  }

  @Test
  void aStructuredRequestAnsweredWithoutStructuredOutputFails() throws Exception {
    FakeProvider provider =
        new FakeProvider(new ModelResponse("anthropic", "fake-model", "free text", null, 3, 2, 0));
    ModelGateway gateway =
        new ModelGateway(List.of(provider), configured(), new StructuredOutputValidator());
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () ->
                gateway.execute(
                    ModelRequest.structured("evidence.extraction", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, thrown.kind());
  }

  @Test
  void aSchemaViolatingAnswerFailsWithInvalidOutput() throws Exception {
    FakeProvider provider = new FakeProvider(structuredResponse("{\"other\":1}"));
    ModelGateway gateway =
        new ModelGateway(List.of(provider), configured(), new StructuredOutputValidator());
    ModelProviderException thrown =
        assertThrows(
            ModelProviderException.class,
            () ->
                gateway.execute(
                    ModelRequest.structured("evidence.extraction", "sys", "user", schema())));
    assertEquals(ModelProviderException.Kind.INVALID_OUTPUT, thrown.kind());
    assertTrue(thrown.getMessage().contains("verdict"));
  }

  @Test
  void aTextAnswerPassesThroughForUnstructuredRequests() {
    FakeProvider provider =
        new FakeProvider(new ModelResponse("anthropic", "fake-model", "an answer", null, 3, 2, 0));
    ModelGateway gateway =
        new ModelGateway(List.of(provider), configured(), new StructuredOutputValidator());
    ModelResponse response = gateway.execute(ModelRequest.text("purpose", "sys", "user"));
    assertEquals("an answer", response.text());
  }
}
