package com.validexia.identity.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * End-to-end request flows through the real filter chain (M02-1 v2.6 §9): provisioning on first
 * sight, tenant selection, lifecycle gating, the epoch check, and logout revocation.
 */
@SpringBootTest(
    properties = {
      "validexia.clerk.issuer=https://clerk.test.example",
      "validexia.clerk.jwks-url=https://clerk.test.example/.well-known/jwks.json",
      "validexia.clerk.authorized-parties=https://app.validexia.test"
    })
@AutoConfigureMockMvc
@Import(TestClerkConfig.class)
class SessionFlowTest {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.springProperties().forEach((key, value) -> registry.add(key, () -> value));
  }

  @Autowired MockMvc mvc;
  @Autowired DirectoryRepository repository;

  private final JdbcTemplate admin = TestDatabase.adminJdbc();

  private String token(String subject, String sid, Instant issuedAt) throws Exception {
    return TestTokens.mint(
        TestClerkConfig.key(), issuedAt, 60, Map.of("sub", subject, "sid", sid));
  }

  @Test
  void healthIsPublic() throws Exception {
    mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
  }

  @Test
  void missingTokenIs401() throws Exception {
    mvc.perform(get("/api/v1/session"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void firstRequestProvisionsTheUser() throws Exception {
    String subject = "user_flow_" + UUID.randomUUID();
    mvc.perform(
            get("/api/v1/session").header("Authorization", "Bearer " + token(subject, "sess_p", Instant.now())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.subject").value(subject))
        .andExpect(jsonPath("$.memberships").isArray());
  }

  @Test
  void tenantSelectionRequiresActiveMembership() throws Exception {
    String subject = "user_flow_" + UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'Flow Tenant', ?)",
        tenantId, "flow-" + tenantId);
    UUID userId = repository.provisionUser(subject, null, null);
    admin.update(
        "INSERT INTO public.memberships (user_id, tenant_id, role) VALUES (?, ?, 'admin')",
        userId, tenantId);
    String token = token(subject, "sess_t", Instant.now());

    mvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.memberships[0].tenantId").value(tenantId.toString()))
        .andExpect(jsonPath("$.memberships[0].role").value("admin"));

    mvc.perform(
            get("/api/v1/session")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeTenantId").value(tenantId.toString()));

    mvc.perform(
            get("/api/v1/session")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));

    mvc.perform(
            get("/api/v1/session")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", "not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    mvc.perform(
            post("/api/v1/session/active-tenant")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + tenantId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("admin"));

    mvc.perform(
            post("/api/v1/session/active-tenant")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));
  }

  @Test
  void logoutRevokesTheSessionLocally() throws Exception {
    String subject = "user_flow_" + UUID.randomUUID();
    String sid = "sess_logout_" + UUID.randomUUID();
    String token = token(subject, sid, Instant.now());
    mvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void bannedLockedAndDeprovisionedUsersAreGated() throws Exception {
    Instant past = Instant.now().minusSeconds(30);

    String banned = "user_flow_" + UUID.randomUUID();
    repository.provisionUser(banned, null, null);
    repository.applyLifecycle(banned, null, true, null, null, past);
    mvc.perform(
            get("/api/v1/session").header("Authorization", "Bearer " + token(banned, "sess_b", Instant.now())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCOUNT_BANNED"));

    String locked = "user_flow_" + UUID.randomUUID();
    repository.provisionUser(locked, null, null);
    repository.applyLifecycle(locked, null, null, true, null, past);
    mvc.perform(
            get("/api/v1/session").header("Authorization", "Bearer " + token(locked, "sess_l", Instant.now())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

    String deprovisioned = "user_flow_" + UUID.randomUUID();
    repository.provisionUser(deprovisioned, null, null);
    repository.applyLifecycle(deprovisioned, null, null, null, true, past);
    mvc.perform(
            get("/api/v1/session")
                .header("Authorization", "Bearer " + token(deprovisioned, "sess_d", Instant.now())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCOUNT_DEPROVISIONED"));
  }

  @Test
  void tokenIssuedBeforeABlockingTransitionStaysDeadAfterTheFlagClears() throws Exception {
    String subject = "user_flow_" + UUID.randomUUID();
    repository.provisionUser(subject, null, null);
    Instant issuedAt = Instant.now().minusSeconds(10);
    String oldToken = token(subject, "sess_epoch", issuedAt);
    // Ban now, unban a second later: the flag clears, but the epoch remembers.
    repository.applyLifecycle(subject, null, true, null, null, Instant.now());
    repository.applyLifecycle(subject, null, false, null, null, Instant.now().plusSeconds(1));
    mvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + oldToken))
        .andExpect(status().isUnauthorized());
    // A token minted after the transition works again.
    mvc.perform(
            get("/api/v1/session")
                .header("Authorization", "Bearer " + token(subject, "sess_epoch2", Instant.now().plusSeconds(2))))
        .andExpect(status().isOk());
  }
}
