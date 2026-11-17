package com.validexia.ai.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.validexia.ai.AiProperties;
import com.validexia.ai.ModelProvider;
import com.validexia.ai.ModelProviderException;
import com.validexia.ai.ModelRequest;
import com.validexia.ai.ModelResponse;
import com.validexia.identity.infrastructure.DirectoryRepository;
import com.validexia.support.TestClerkConfig;
import com.validexia.support.TestDatabase;
import com.validexia.support.TestTokens;
import com.validexia.vendors.application.VendorData;
import com.validexia.vendors.application.VendorService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The live evaluation's provider-failure case, kept in its own class because it needs a different
 * configuration from the main sequence: the API key here is the literal dummy {@code
 * invalid-eval-key} (NOT a real secret — it exists only so the real provider attempts the call and
 * the selected provider rejects it with HTTP 401; the attempt is unbilled). The brief run must then
 * fail cleanly — run FAILED, no brief persisted as a success — after exactly one counted
 * invocation. A fabricated or "successful" brief here would be the worst outcome this evaluation
 * can produce. The provider under test is selected exactly as in the main sequence (see {@link
 * LiveEvalProviders}); the model comes from the {@code VALIDEXIA_AI_MODEL} environment variable,
 * defaulting to the Anthropic evaluation model.
 *
 * <p>Like the main sequence, this class runs ONLY through the {@code liveEval} Gradle task; the
 * default {@code test} task excludes this package. The counting wrapper is duplicated from {@code
 * LiveVendorBriefEvaluationTest} deliberately, mirroring how each flow test carries its own stub:
 * the two configurations must stay independent.
 */
@SpringBootTest(
    properties = {
      "validexia.clerk.issuer=https://clerk.test.example",
      "validexia.clerk.jwks-url=https://clerk.test.example/.well-known/jwks.json",
      "validexia.clerk.authorized-parties=https://app.validexia.test",
      "validexia.ai.enabled=true",
      "validexia.ai.provider=live-eval-counting",
      "validexia.ai.api-key=invalid-eval-key"
    })
@AutoConfigureMockMvc
@Import({TestClerkConfig.class, LiveProviderFailureEvaluationTest.LiveEvalAiConfig.class})
class LiveProviderFailureEvaluationTest {

  /** Counting + capping wrapper, identical in behaviour to the main sequence's guard. */
  static final class CountingModelProvider implements ModelProvider {

    static final int HARD_CAP = 12;

    record Call(
        int index,
        String purpose,
        int inputTokens,
        int outputTokens,
        long durationMs,
        String failure) {}

    private final ModelProvider delegate;
    private final AtomicInteger invocations = new AtomicInteger();
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    CountingModelProvider(ModelProvider delegate) {
      this.delegate = delegate;
    }

    @Override
    public String name() {
      return "live-eval-counting";
    }

    @Override
    public String model() {
      return delegate.model();
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
      int index = invocations.incrementAndGet();
      if (index > HARD_CAP) {
        throw new IllegalStateException(
            "live evaluation hard cap exceeded: invocation "
                + index
                + " refused before delegating (cap "
                + HARD_CAP
                + ")");
      }
      long startedNanos = System.nanoTime();
      try {
        ModelResponse response = delegate.complete(request);
        calls.add(
            new Call(
                index,
                request.purpose(),
                response.inputTokens(),
                response.outputTokens(),
                (System.nanoTime() - startedNanos) / 1_000_000,
                null));
        return response;
      } catch (RuntimeException e) {
        String failure =
            e instanceof ModelProviderException mpe
                ? mpe.kind().name()
                : e.getClass().getSimpleName();
        calls.add(
            new Call(
                index,
                request.purpose(),
                0,
                0,
                (System.nanoTime() - startedNanos) / 1_000_000,
                failure));
        throw e;
      }
    }

    int invocations() {
      return invocations.get();
    }

    List<Call> calls() {
      return List.copyOf(calls);
    }
  }

  @TestConfiguration
  static class LiveEvalAiConfig {
    @Bean
    CountingModelProvider liveEvalCountingProvider(AiProperties properties, ObjectMapper mapper) {
      return new CountingModelProvider(LiveEvalProviders.createDelegate(properties, mapper));
    }
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.springProperties().forEach((key, value) -> registry.add(key, () -> value));
    // The evaluated model arrives from the environment (the workflow's model input), so the same
    // class exercises whichever provider LiveEvalProviders selected; the default preserves the
    // Anthropic evaluation's model when the variable is absent.
    registry.add(
        "validexia.ai.model",
        () -> System.getenv().getOrDefault("VALIDEXIA_AI_MODEL", "claude-sonnet-5-5"));
  }

  @Autowired MockMvc mvc;
  @Autowired DirectoryRepository directory;
  @Autowired VendorService vendors;
  @Autowired CountingModelProvider counting;

  private final JdbcTemplate admin = TestDatabase.adminJdbc();
  private final ObjectMapper mapper = new ObjectMapper();

  private String token(String subject) throws Exception {
    return TestTokens.mint(
        TestClerkConfig.key(), Instant.now(), 60, Map.of("sub", subject, "sid", "s-" + subject));
  }

  @Test
  void anInvalidApiKeyFailsTheRunCleanlyAfterExactlyOneCountedCall() throws Exception {
    String subject = "live-eval-fail-" + UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'Live Eval Tenant', ?)",
        tenantId,
        "t-" + tenantId);
    String token = token(subject);
    mvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    UUID userId = directory.findUser(subject).orElseThrow().userId();
    admin.update(
        "INSERT INTO public.memberships (user_id, tenant_id, role) VALUES (?, ?, 'admin')",
        userId,
        tenantId);

    UUID vendorId =
        vendors.createVendor(
            tenantId,
            userId,
            "Failure Case Vendor",
            "Failure Case Vendor Ltd",
            "549300FCEVAL00000044",
            "Ireland",
            "EVAL-FAIL",
            "Cloud hosting",
            "failurecase.example",
            "https://failurecase.example",
            "A rich synthetic record, seeded only so the brief step is genuinely attempted.",
            "Tier 1",
            null,
            LocalDate.of(2025, 1, 1),
            null,
            true);
    vendors.replaceDependentSystems(
        tenantId,
        userId,
        vendorId,
        List.of(new VendorData.DependentSystemInput("Hosting platform", "Customer data", "EU")));

    mvc.perform(
            post("/api/v1/agents/definitions")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"agentKey\":\"vendor.intelligence\",\"name\":\"Vendor Intelligence\","
                        + "\"kind\":\"DETERMINISTIC\"}"))
        .andExpect(status().isOk());

    MvcResult result =
        mvc.perform(
                post("/api/v1/agents/runs")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"agentKey\":\"vendor.intelligence\",\"objective\":\"Produce a vendor"
                            + " intelligence brief\",\"mode\":\"AUTONOMOUS\",\"idempotencyKey\":"
                            + "\"live-eval-invalid-key\",\"context\":\"{\\\"vendorId\\\":\\\""
                            + vendorId
                            + "\\\"}\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode run = mapper.readTree(result.getResponse().getContentAsString());

    assertEquals("FAILED", run.path("state").asText(), "no fake success on a provider failure");
    JsonNode briefTask = run.path("tasks").get(1);
    assertEquals("FAILED", briefTask.path("state").asText());
    assertTrue(
        briefTask.path("output").isMissingNode() || briefTask.path("output").isNull(),
        "no brief may be persisted for a failed run");
    assertTrue(
        briefTask.path("error").asText("").contains("401"),
        "the failure is the provider's HTTP 401, was: " + briefTask.path("error").asText(""));
    assertEquals(
        1, counting.invocations(), "exactly one counted invocation for the whole failed run");
    assertEquals(1, counting.calls().size());
    assertEquals("PROVIDER_ERROR", counting.calls().get(0).failure());
  }
}
