package com.validexia.vendors.application.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.validexia.identity.infrastructure.DirectoryRepository;
import com.validexia.support.TestClerkConfig;
import com.validexia.support.TestDatabase;
import com.validexia.support.TestTokens;
import com.validexia.vendors.application.VendorService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
 * Acceptance case (f): with no model provider configured (the shipped default), the brief step
 * fails closed — the run fails with the gateway's UNCONFIGURED error and no brief is fabricated —
 * while the profile step, which needs no model, still succeeds. Nothing about the agent's
 * registration changes in this deployment state; only the model call refuses.
 */
@SpringBootTest(
    properties = {
      "validexia.clerk.issuer=https://clerk.test.example",
      "validexia.clerk.jwks-url=https://clerk.test.example/.well-known/jwks.json",
      "validexia.clerk.authorized-parties=https://app.validexia.test"
    })
@AutoConfigureMockMvc
@Import(TestClerkConfig.class)
class VendorIntelligenceUnconfiguredTest {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.springProperties().forEach((key, value) -> registry.add(key, () -> value));
  }

  @Autowired MockMvc mvc;
  @Autowired DirectoryRepository directory;
  @Autowired VendorService vendors;

  private final JdbcTemplate admin = TestDatabase.adminJdbc();
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void anUnconfiguredProviderFailsTheBriefStepClosed() throws Exception {
    String subject = "vib-unconfigured-" + UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'Brief Tenant', ?)",
        tenantId,
        "t-" + tenantId);
    String token =
        TestTokens.mint(
            TestClerkConfig.key(),
            Instant.now(),
            60,
            Map.of("sub", subject, "sid", "s-" + subject));
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
            "Acme Cloud",
            null,
            null,
            null,
            "VND-001",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false);
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
                        "{\"agentKey\":\"vendor.intelligence\",\"objective\":\"Brief\","
                            + "\"mode\":\"AUTONOMOUS\",\"idempotencyKey\":\"vib-unconfigured\","
                            + "\"context\":\"{\\\"vendorId\\\":\\\""
                            + vendorId
                            + "\\\"}\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode run = mapper.readTree(result.getResponse().getContentAsString());

    assertEquals("FAILED", run.path("state").asText());
    assertEquals(
        "SUCCEEDED",
        run.path("tasks").get(0).path("state").asText(),
        "the profile step needs no model and still succeeds");
    JsonNode briefTask = run.path("tasks").get(1);
    assertEquals("FAILED", briefTask.path("state").asText());
    assertTrue(
        briefTask.path("error").asText().contains("no model provider is configured"),
        "the failure is the gateway's fail-closed UNCONFIGURED error");
    assertTrue(briefTask.path("output").isMissingNode() || briefTask.path("output").isNull());
  }
}
