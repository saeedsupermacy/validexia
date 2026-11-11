package com.validexia.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.validexia.agents.application.foundation.FoundationEchoAgent;
import com.validexia.agents.application.foundation.ToolInvocationLog;
import com.validexia.agents.infrastructure.AgentsRepository;
import com.validexia.identity.infrastructure.DirectoryRepository;
import com.validexia.support.TestClerkConfig;
import com.validexia.support.TestDatabase;
import com.validexia.support.TestTokens;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * End-to-end agent foundation flows through the real filter chain: a run in APPROVAL mode walks
 * task by task, parking each write for a human decision; AUTONOMOUS executes the routine write but
 * still parks the sensitive one; a rejection fails the run; tenants cannot see each other.
 */
@SpringBootTest(
    properties = {
      "validexia.clerk.issuer=https://clerk.test.example",
      "validexia.clerk.jwks-url=https://clerk.test.example/.well-known/jwks.json",
      "validexia.clerk.authorized-parties=https://app.validexia.test"
    })
@AutoConfigureMockMvc
@Import(TestClerkConfig.class)
class AgentFlowTest {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.springProperties().forEach((key, value) -> registry.add(key, () -> value));
  }

  @Autowired MockMvc mvc;
  @Autowired DirectoryRepository directory;
  @Autowired AgentsRepository agentsRepository;
  @Autowired ToolInvocationLog invocationLog;

  private final JdbcTemplate admin = TestDatabase.adminJdbc();
  private final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void clearLog() {
    invocationLog.clear();
  }

  private String token(String subject) throws Exception {
    return TestTokens.mint(
        TestClerkConfig.key(), Instant.now(), 300, Map.of("sub", subject, "sid", "s-" + subject));
  }

  /** Creates a tenant and a provisioned admin member; returns the tenant id. */
  private UUID tenantWithMember(String subject) throws Exception {
    UUID tenantId = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'Agent Tenant', ?)",
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

  private void registerEcho(String token, UUID tenantId) throws Exception {
    mvc.perform(
            post("/api/v1/agents/definitions")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"agentKey\":\"foundation.echo\",\"name\":\"Echo\",\"kind\":\"DETERMINISTIC\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agentKey").value("foundation.echo"));
  }

  private JsonNode createRun(String token, UUID tenantId, String mode, String idempotencyKey)
      throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v1/agents/runs")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"agentKey\":\"foundation.echo\",\"objective\":\"review Acme\",\"mode\":\""
                            + mode
                            + "\",\"idempotencyKey\":\""
                            + idempotencyKey
                            + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  private UUID pendingApprovalId(String token, UUID tenantId) throws Exception {
    MvcResult result =
        mvc.perform(
                get("/api/v1/agents/approvals")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode approvals = json.readTree(result.getResponse().getContentAsString());
    assertEquals(1, approvals.size(), "expected exactly one pending approval");
    return UUID.fromString(approvals.get(0).get("id").asText());
  }

  private JsonNode decide(String token, UUID tenantId, UUID approvalId, String decision)
      throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v1/agents/approvals/" + approvalId + "/decision")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"" + decision + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  @Test
  void approvalModeParksEachWriteAndCompletesAfterApprovals() throws Exception {
    String subject = "agent-approval-" + UUID.randomUUID();
    UUID tenantId = tenantWithMember(subject);
    String token = token(subject);
    registerEcho(token, tenantId);

    JsonNode run = createRun(token, tenantId, "APPROVAL", "k-approval");
    assertEquals("WAITING_APPROVAL", run.get("state").asText());
    assertEquals("SUCCEEDED", run.get("tasks").get(0).get("state").asText());
    assertEquals("WAITING_APPROVAL", run.get("tasks").get(1).get("state").asText());
    assertEquals(0, invocationLog.count(FoundationEchoAgent.WRITE_TOOL));
    assertEquals(0, invocationLog.count(FoundationEchoAgent.SENSITIVE_TOOL));

    JsonNode afterFirst = decide(token, tenantId, pendingApprovalId(token, tenantId), "APPROVED");
    assertEquals("WAITING_APPROVAL", afterFirst.get("state").asText());
    assertEquals("SUCCEEDED", afterFirst.get("tasks").get(1).get("state").asText());
    assertEquals("WAITING_APPROVAL", afterFirst.get("tasks").get(2).get("state").asText());
    assertEquals(1, invocationLog.count(FoundationEchoAgent.WRITE_TOOL));
    assertEquals(0, invocationLog.count(FoundationEchoAgent.SENSITIVE_TOOL));

    JsonNode done = decide(token, tenantId, pendingApprovalId(token, tenantId), "APPROVED");
    assertEquals("SUCCEEDED", done.get("state").asText());
    assertEquals("SUCCEEDED", done.get("tasks").get(2).get("state").asText());
    assertEquals(1, invocationLog.count(FoundationEchoAgent.SENSITIVE_TOOL));
  }

  @Test
  void autonomousModeRunsRoutineWriteButStillParksSensitive() throws Exception {
    String subject = "agent-auto-" + UUID.randomUUID();
    UUID tenantId = tenantWithMember(subject);
    String token = token(subject);
    registerEcho(token, tenantId);

    JsonNode run = createRun(token, tenantId, "AUTONOMOUS", "k-auto");
    assertEquals("WAITING_APPROVAL", run.get("state").asText());
    assertEquals("SUCCEEDED", run.get("tasks").get(1).get("state").asText());
    assertEquals(1, invocationLog.count(FoundationEchoAgent.WRITE_TOOL));
    assertEquals(0, invocationLog.count(FoundationEchoAgent.SENSITIVE_TOOL));

    JsonNode done = decide(token, tenantId, pendingApprovalId(token, tenantId), "APPROVED");
    assertEquals("SUCCEEDED", done.get("state").asText());
  }

  @Test
  void rejectionFailsTheRunAndTheSensitiveToolNeverRuns() throws Exception {
    String subject = "agent-reject-" + UUID.randomUUID();
    UUID tenantId = tenantWithMember(subject);
    String token = token(subject);
    registerEcho(token, tenantId);

    JsonNode run = createRun(token, tenantId, "AUTONOMOUS", "k-reject");
    assertEquals("WAITING_APPROVAL", run.get("state").asText());
    JsonNode failed = decide(token, tenantId, pendingApprovalId(token, tenantId), "REJECTED");
    assertEquals("FAILED", failed.get("state").asText());
    assertEquals("FAILED", failed.get("tasks").get(2).get("state").asText());
    assertEquals(0, invocationLog.count(FoundationEchoAgent.SENSITIVE_TOOL));
  }

  @Test
  void assistModeParksTheFirstWrite() throws Exception {
    String subject = "agent-assist-" + UUID.randomUUID();
    UUID tenantId = tenantWithMember(subject);
    String token = token(subject);
    registerEcho(token, tenantId);

    JsonNode run = createRun(token, tenantId, "ASSIST", "k-assist");
    assertEquals("WAITING_APPROVAL", run.get("state").asText());
    assertEquals("SUCCEEDED", run.get("tasks").get(0).get("state").asText());
    assertEquals("WAITING_APPROVAL", run.get("tasks").get(1).get("state").asText());
  }

  @Test
  void runsAreTenantIsolatedAndCreationIsIdempotent() throws Exception {
    String subjectA = "agent-iso-a-" + UUID.randomUUID();
    String subjectB = "agent-iso-b-" + UUID.randomUUID();
    UUID tenantA = tenantWithMember(subjectA);
    UUID tenantB = tenantWithMember(subjectB);
    String tokenA = token(subjectA);
    String tokenB = token(subjectB);
    registerEcho(tokenA, tenantA);

    JsonNode first = createRun(tokenA, tenantA, "AUTONOMOUS", "k-iso");
    JsonNode replay = createRun(tokenA, tenantA, "AUTONOMOUS", "k-iso");
    assertEquals(first.get("id").asText(), replay.get("id").asText());

    mvc.perform(
            get("/api/v1/agents/runs/" + first.get("id").asText())
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Tenant-Id", tenantB))
        .andExpect(status().isNotFound());

    mvc.perform(
            get("/api/v1/agents/definitions")
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Tenant-Id", tenantB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    assertNotEquals(tenantA, tenantB);
  }

  @Test
  void auditChainRecordsTheRunLifecycle() throws Exception {
    String subject = "agent-audit-" + UUID.randomUUID();
    UUID tenantId = tenantWithMember(subject);
    String token = token(subject);
    registerEcho(token, tenantId);
    JsonNode run = createRun(token, tenantId, "AUTONOMOUS", "k-audit");
    decide(token, tenantId, pendingApprovalId(token, tenantId), "APPROVED");

    Integer events =
        admin.queryForObject(
            "SELECT count(*) FROM public.audit_events WHERE tenant_key = ? AND action LIKE"
                + " 'AGENT_%'",
            Integer.class, tenantId.toString());
    // created, read executed, write executed, approval requested, approval decided,
    // sensitive executed, run succeeded
    assertEquals(7, events);
    assertEquals(run.get("state").asText(), "WAITING_APPROVAL");
  }
}
