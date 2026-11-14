package com.validexia.assessments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end assessment flows through the real filter chain: create an assessment, enter claims,
 * create a finding (always {@code Pending Approval}), approve it by a human decision, and confirm a
 * second tenant sees none of it and that the approval is audit-recorded.
 */
@SpringBootTest(
    properties = {
      "validexia.clerk.issuer=https://clerk.test.example",
      "validexia.clerk.jwks-url=https://clerk.test.example/.well-known/jwks.json",
      "validexia.clerk.authorized-parties=https://app.validexia.test"
    })
@AutoConfigureMockMvc
@Import(TestClerkConfig.class)
class AssessmentsFlowTest {

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

  private UUID tenantWithMember(String subject) throws Exception {
    UUID tenantId = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'Assessment Tenant', ?)",
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

  private JsonNode call(String token, UUID tenantId, String method, String path, String body)
      throws Exception {
    MvcResult result =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                        org.springframework.http.HttpMethod.valueOf(method), path)
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId)
                    .contentType("application/json")
                    .content(body == null ? "" : body))
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  @Test
  void aFindingIsPendingApprovalUntilAHumanDecides() throws Exception {
    String subject = "assess-flow-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithMember(subject);
    UUID vendorId = UUID.randomUUID();

    JsonNode assessment =
        call(token, tenantId, "POST", "/api/v1/assessments", "{\"vendorId\":\"" + vendorId + "\"}");
    String assessmentId = assessment.get("id").asText();
    org.junit.jupiter.api.Assertions.assertEquals("Draft", assessment.get("status").asText());

    call(
        token,
        tenantId,
        "POST",
        "/api/v1/assessments/" + assessmentId + "/claims",
        "{\"claims\":[{\"questionText\":\"Are access reviews performed quarterly?\","
            + "\"answerText\":\"Yes, quarterly.\"}]}");

    // A finding is created Pending Approval — never Approved, never Rejected.
    JsonNode finding =
        call(
            token,
            tenantId,
            "POST",
            "/api/v1/assessments/" + assessmentId + "/findings",
            "{\"findingType\":\"Unsupported\",\"title\":\"No evidence of quarterly review\","
                + "\"explanation\":\"The answer claims quarterly reviews but no supporting "
                + "document was supplied.\"}");
    String findingId = finding.get("findingId").asText();
    org.junit.jupiter.api.Assertions.assertEquals(
        "Pending Approval", finding.get("status").asText());
    // Severity is deterministic: Unsupported -> High, regardless of what a caller asked for.
    org.junit.jupiter.api.Assertions.assertEquals("High", finding.get("severity").asText());

    // It is visible as pending.
    mvc.perform(
            get("/api/v1/assessments/" + assessmentId + "/findings")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].status").value("Pending Approval"))
        .andExpect(jsonPath("$[0].id").value(findingId));

    // The human decision is the only path to Approved.
    mvc.perform(
            post("/api/v1/assessments/" + assessmentId + "/findings/" + findingId + "/decision")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType("application/json")
                .content(
                    "{\"decision\":\"Approved\",\"reason\":\"Verified by the security team\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("Approved"))
        .andExpect(jsonPath("$.decisionReason").value("Verified by the security team"));

    // A second decision is refused rather than overwriting the first.
    mvc.perform(
            post("/api/v1/assessments/" + assessmentId + "/findings/" + findingId + "/decision")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType("application/json")
                .content("{\"decision\":\"Rejected\",\"reason\":\"changed my mind\"}"))
        .andExpect(status().isBadRequest());

    // The approval is on the tenant's audit chain.
    Integer audited =
        admin.queryForObject(
            "SELECT count(*) FROM public.audit_events WHERE tenant_key = ? "
                + "AND entity_type = 'assessment_finding' AND action = 'ASSESSMENT_FINDING_DECIDED'",
            Integer.class,
            tenantId.toString());
    org.junit.jupiter.api.Assertions.assertTrue(
        audited != null && audited >= 1, "a human decision must be audit-recorded");
  }

  @Test
  void tenantsCannotSeeEachOthersAssessmentsOrFindings() throws Exception {
    String subjectA = "assess-a-" + UUID.randomUUID();
    String tokenA = token(subjectA);
    UUID tenantA = tenantWithMember(subjectA);
    JsonNode assessment =
        call(
            tokenA,
            tenantA,
            "POST",
            "/api/v1/assessments",
            "{\"vendorId\":\"" + UUID.randomUUID() + "\"}");
    String assessmentId = assessment.get("id").asText();

    String subjectB = "assess-b-" + UUID.randomUUID();
    String tokenB = token(subjectB);
    UUID tenantB = tenantWithMember(subjectB);

    mvc.perform(
            get("/api/v1/assessments")
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Tenant-Id", tenantB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    mvc.perform(
            get("/api/v1/assessments/" + assessmentId + "/findings")
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Tenant-Id", tenantB))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theTenantHeaderIsRequired() throws Exception {
    String subject = "assess-noheader-" + UUID.randomUUID();
    String token = token(subject);

    mvc.perform(
            post("/api/v1/assessments")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"vendorId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isBadRequest());
  }
}
