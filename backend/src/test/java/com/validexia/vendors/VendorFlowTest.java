package com.validexia.vendors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.validexia.identity.infrastructure.DirectoryRepository;
import com.validexia.support.TestClerkConfig;
import com.validexia.support.TestDatabase;
import com.validexia.support.TestTokens;
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

/**
 * End-to-end vendor flows through the real filter chain: create, read, update, dependent systems
 * and termination for one tenant; a second tenant sees none of it; the tenant header is required.
 */
@SpringBootTest(
    properties = {
      "validexia.clerk.issuer=https://clerk.test.example",
      "validexia.clerk.jwks-url=https://clerk.test.example/.well-known/jwks.json",
      "validexia.clerk.authorized-parties=https://app.validexia.test"
    })
@AutoConfigureMockMvc
@Import(TestClerkConfig.class)
class VendorFlowTest {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.springProperties().forEach((key, value) -> registry.add(key, () -> value));
  }

  @Autowired MockMvc mvc;
  @Autowired DirectoryRepository directory;

  private final JdbcTemplate admin = TestDatabase.adminJdbc();
  private final ObjectMapper json = new ObjectMapper();

  private String token(String subject) throws Exception {
    return TestTokens.mint(
        TestClerkConfig.key(), Instant.now(), 60, Map.of("sub", subject, "sid", "s-" + subject));
  }

  /** Creates a tenant and a provisioned admin member; returns the tenant id. */
  private UUID tenantWithMember(String subject) throws Exception {
    UUID tenantId = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'Vendor Tenant', ?)",
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
    return tenantId;
  }

  private static final String CREATE_BODY =
      "{\"name\":\"CloudScale Infrastructure\",\"legalName\":\"CloudScale Inc.\","
          + "\"lei\":\"1234567890ABCDEFGH12\",\"jurisdiction\":\"US-DE\",\"code\":\"VND-001\","
          + "\"category\":\"Cloud & Hosting\",\"domain\":\"cloudscale.example\","
          + "\"criticality\":\"Tier 1\",\"contractStart\":\"2024-01-15\","
          + "\"contractEnd\":\"2027-01-15\",\"tieringComplete\":true}";

  private JsonNode createVendor(String token, UUID tenantId) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v1/vendors")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CREATE_BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("CloudScale Infrastructure"))
            .andExpect(jsonPath("$.legalName").value("CloudScale Inc."))
            .andExpect(jsonPath("$.lei").value("1234567890ABCDEFGH12"))
            .andExpect(jsonPath("$.code").value("VND-001"))
            .andExpect(jsonPath("$.status").value("Active"))
            .andExpect(jsonPath("$.contractStart").value("2024-01-15"))
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  @Test
  void vendorLifecycleEndToEnd() throws Exception {
    String subject = "vendor-flow-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    String vendorId = createVendor(token, tenantId).get("id").asText();

    mvc.perform(
            get("/api/v1/vendors")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(vendorId));

    mvc.perform(
            put("/api/v1/vendors/" + vendorId)
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"CloudScale Renamed\",\"criticality\":\"Tier 2\","
                        + "\"tieringComplete\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("CloudScale Renamed"))
        .andExpect(jsonPath("$.criticality").value("Tier 2"))
        // The code is immutable: the update body carries no code and the stored one survives.
        .andExpect(jsonPath("$.code").value("VND-001"));

    mvc.perform(
            put("/api/v1/vendors/" + vendorId + "/dependent-systems")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "[{\"name\":\"Core Billing Engine\",\"dataHandled\":\"Financial Ledger\","
                        + "\"residency\":\"AWS us-east-1\"}]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("Core Billing Engine"));

    mvc.perform(
            get("/api/v1/vendors/" + vendorId + "/dependent-systems")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].dataHandled").value("Financial Ledger"));

    mvc.perform(
            post("/api/v1/vendors/" + vendorId + "/terminate")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("Terminated"));
  }

  @Test
  void tenantsCannotSeeEachOthersVendors() throws Exception {
    String subjectA = "vendor-a-" + UUID.randomUUID();
    String tokenA = token(subjectA);
    UUID tenantA = tenantWithMember(subjectA);
    String vendorId = createVendor(tokenA, tenantA).get("id").asText();

    String subjectB = "vendor-b-" + UUID.randomUUID();
    String tokenB = token(subjectB);
    UUID tenantB = tenantWithMember(subjectB);

    mvc.perform(
            get("/api/v1/vendors")
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Tenant-Id", tenantB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    mvc.perform(
            get("/api/v1/vendors/" + vendorId)
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Tenant-Id", tenantB))
        .andExpect(status().isNotFound());
  }

  @Test
  void tenantHeaderIsRequiredAndDuplicateCodeIsAValidationError() throws Exception {
    String subject = "vendor-misc-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);

    mvc.perform(
            post("/api/v1/vendors")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_BODY))
        .andExpect(status().isBadRequest());

    createVendor(token, tenantId);
    mvc.perform(
            post("/api/v1/vendors")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_BODY))
        .andExpect(status().isBadRequest());
  }
}
