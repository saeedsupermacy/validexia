package com.validexia.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.validexia.audit.AuditService;
import com.validexia.identity.infrastructure.DirectoryRepository;
import com.validexia.support.TestClerkConfig;
import com.validexia.support.TestDatabase;
import com.validexia.support.TestTokens;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The frozen atomicity proof (U1 freeze v1.4.1 §9): a real grant creation whose REAL audit append
 * is followed, inside the same worker transaction, by an injected failure must leave neither the
 * grant nor the audit event behind — and the chain untouched. The injection wraps the real {@link
 * AuditService} (never a mock): its {@code appendForTenant} executes the real INSERT first and
 * throws only afterwards, only when armed.
 *
 * <p>The observer is a fresh application-role connection that sets its tenant context
 * transaction-locally and looks only through production-permitted channels: grant visibility
 * exclusively via G8 (the app role holds no direct privilege on {@code access_grants}), audit
 * events via the app role's existing SELECT on {@code audit_events}, and chain state via {@code
 * audit_verify_chain} (the role holds no grant on {@code audit_chain_heads}; an unchanged event set
 * — same count, same max(seq) — is the observable equivalent of an unadvanced chain head, since the
 * head advances only via the BEFORE INSERT chain trigger). The positive control calibrates the
 * observer: the same channels DO see a committed grant and its event, so the failure case's empty
 * results mean rollback, not blindness.
 */
@SpringBootTest(
    properties = {
      "validexia.clerk.issuer=https://clerk.test.example",
      "validexia.clerk.jwks-url=https://clerk.test.example/.well-known/jwks.json",
      "validexia.clerk.authorized-parties=https://app.validexia.test"
    })
@AutoConfigureMockMvc
@Import({TestClerkConfig.class, AccessAtomicityTest.ArmedAuditConfig.class})
class AccessAtomicityTest {

  /** The dedicated failure thrown immediately after a real audit append, when armed. */
  static class InjectedPostAppendFailure extends RuntimeException {
    InjectedPostAppendFailure() {
      super("injected post-append failure");
    }
  }

  /** Decorates the real AuditService: records each append's target, delegates, then throws. */
  static class ArmedAuditService extends AuditService {
    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicReference<String> lastAction = new AtomicReference<>();
    private final AtomicReference<String> lastEntityId = new AtomicReference<>();

    ArmedAuditService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
      super(jdbc, transactionManager);
    }

    @Override
    public void appendForTenant(
        UUID tenantId,
        UUID actorUserId,
        String action,
        String entityType,
        @Nullable String entityId,
        String detailsJson) {
      lastAction.set(action);
      lastEntityId.set(entityId);
      super.appendForTenant(tenantId, actorUserId, action, entityType, entityId, detailsJson);
      if (armed.get()) {
        throw new InjectedPostAppendFailure();
      }
    }

    void arm() {
      armed.set(true);
    }

    void disarm() {
      armed.set(false);
    }

    String lastAction() {
      return lastAction.get();
    }

    String lastEntityId() {
      return lastEntityId.get();
    }
  }

  @TestConfiguration
  static class ArmedAuditConfig {
    @Bean
    @Primary
    ArmedAuditService armedAuditService(
        JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
      return new ArmedAuditService(jdbc, transactionManager);
    }
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.springProperties().forEach((key, value) -> registry.add(key, () -> value));
  }

  @Autowired MockMvc mvc;
  @Autowired DirectoryRepository directory;
  @Autowired ArmedAuditService audit;

  private final JdbcTemplate admin = TestDatabase.adminJdbc();
  private final ObjectMapper json = new ObjectMapper();

  private record Observation(
      int grantRows,
      @Nullable String lifecycleStatus,
      @Nullable String verificationStatus,
      long eventsForEntity,
      long totalEvents,
      long maxSeq,
      boolean chainOk) {}

  /**
   * Observes the tenant exactly as §9 prescribes: app role, own transaction, G8 + permitted reads.
   */
  private Observation observe(UUID tenantId, @Nullable String entityId) throws Exception {
    try (Connection connection = TestDatabase.appDataSource().getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement ps =
            connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
          ps.setString(1, tenantId.toString());
          ps.execute();
        }
        int grantRows = 0;
        String lifecycle = null;
        String verification = null;
        long eventsForEntity = 0;
        if (entityId != null) {
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "SELECT lifecycle_status, verification_status FROM public.get_access_grant(?, ?)")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, UUID.fromString(entityId));
            try (ResultSet rs = ps.executeQuery()) {
              if (rs.next()) {
                grantRows = 1;
                lifecycle = rs.getString(1);
                verification = rs.getString(2);
              }
            }
          }
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "SELECT count(*) FROM public.audit_events WHERE tenant_key = ? AND entity_id = ?")) {
            ps.setString(1, tenantId.toString());
            ps.setString(2, entityId);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              eventsForEntity = rs.getLong(1);
            }
          }
        }
        long totalEvents;
        long maxSeq;
        try (PreparedStatement ps =
            connection.prepareStatement(
                "SELECT count(*), coalesce(max(seq), 0) FROM public.audit_events"
                    + " WHERE tenant_key = ?")) {
          ps.setString(1, tenantId.toString());
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            totalEvents = rs.getLong(1);
            maxSeq = rs.getLong(2);
          }
        }
        boolean chainOk;
        try (PreparedStatement ps =
            connection.prepareStatement("SELECT ok FROM public.audit_verify_chain(?)")) {
          ps.setString(1, tenantId.toString());
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            chainOk = rs.getBoolean(1);
          }
        }
        return new Observation(
            grantRows, lifecycle, verification, eventsForEntity, totalEvents, maxSeq, chainOk);
      } finally {
        connection.rollback();
      }
    }
  }

  private String token(String subject) throws Exception {
    return TestTokens.mint(
        TestClerkConfig.key(), Instant.now(), 60, Map.of("sub", subject, "sid", "s-" + subject));
  }

  private UUID tenantWithAdmin(String subject) throws Exception {
    UUID tenantId = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.tenants (id, name, slug) VALUES (?, 'Atomicity Tenant', ?)",
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

  private String createSystem(String token, UUID tenantId) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v1/internal-systems")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"CRM\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private org.springframework.test.web.servlet.ResultActions createGrant(
      String token, UUID tenantId, UUID vendorId, String systemId) throws Exception {
    return mvc.perform(
        post("/api/v1/access-grants")
            .header("Authorization", "Bearer " + token)
            .header("X-Tenant-Id", tenantId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"vendorId\":\""
                    + vendorId
                    + "\",\"internalSystemId\":\""
                    + systemId
                    + "\",\"privilegeLevel\":\"Read\",\"accessMethod\":\"API\","
                    + "\"sourceKind\":\"Customer Entered\"}"));
  }

  @Test
  void mutationAndAuditAppendRollBackTogether() throws Exception {
    String subject = "access-atomicity-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithAdmin(subject);
    UUID vendor = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.vendors (id, tenant_id, name, code) VALUES (?, ?, 'V', ?)",
        vendor,
        tenantId,
        "V-" + vendor);
    String systemId = createSystem(token, tenantId);

    Observation baseline = observe(tenantId, null);
    assertTrue(baseline.chainOk());

    // Failure case: the real append executes, then the injected failure fires inside the
    // worker transaction. The request surfaces as a 500; nothing may persist.
    audit.arm();
    createGrant(token, tenantId, vendor, systemId)
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL"));
    String rolledBackGrantId = audit.lastEntityId();
    audit.disarm();
    assertEquals("ACCESS_GRANT_CREATED", audit.lastAction());

    Observation afterFailure = observe(tenantId, rolledBackGrantId);
    assertEquals(0, afterFailure.grantRows(), "the grant insert must have rolled back");
    assertEquals(0, afterFailure.eventsForEntity(), "the audit append must have rolled back");
    assertEquals(baseline.totalEvents(), afterFailure.totalEvents());
    assertEquals(baseline.maxSeq(), afterFailure.maxSeq(), "the chain head must not advance");
    assertTrue(afterFailure.chainOk());

    // Positive control: the identical call, disarmed, commits — and the same observer
    // channels see the grant, its event, and a valid chain.
    MvcResult created =
        createGrant(token, tenantId, vendor, systemId).andExpect(status().isCreated()).andReturn();
    String committedGrantId =
        json.readTree(created.getResponse().getContentAsString()).get("id").asText();
    assertEquals(committedGrantId, audit.lastEntityId());

    Observation afterControl = observe(tenantId, committedGrantId);
    assertEquals(1, afterControl.grantRows());
    assertEquals("Active", afterControl.lifecycleStatus());
    assertEquals("Unverified", afterControl.verificationStatus());
    assertEquals(1, afterControl.eventsForEntity());
    assertEquals(baseline.totalEvents() + 1, afterControl.totalEvents());
    assertTrue(afterControl.maxSeq() > baseline.maxSeq());
    assertTrue(afterControl.chainOk());
  }

  @Test
  void refusedTransitionAppendsNoAuditEvent() throws Exception {
    String subject = "access-atomicity-refusal-" + UUID.randomUUID();
    String token = token(subject);
    UUID tenantId = tenantWithAdmin(subject);
    UUID vendor = UUID.randomUUID();
    admin.update(
        "INSERT INTO public.vendors (id, tenant_id, name, code) VALUES (?, ?, 'V', ?)",
        vendor,
        tenantId,
        "V-" + vendor);
    String systemId = createSystem(token, tenantId);
    MvcResult created =
        createGrant(token, tenantId, vendor, systemId).andExpect(status().isCreated()).andReturn();
    String grantId = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/suspend")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"hold\"}"))
        .andExpect(status().isOk());
    Observation before = observe(tenantId, grantId);
    assertEquals(2, before.eventsForEntity()); // created + suspended

    // A refused transition (already Suspended) appends nothing — structurally, the append
    // sits on the applied branch only.
    mvc.perform(
            post("/api/v1/access-grants/" + grantId + "/suspend")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"again\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("transition_not_allowed"));

    Observation after = observe(tenantId, grantId);
    assertEquals(before.eventsForEntity(), after.eventsForEntity());
    assertEquals(before.totalEvents(), after.totalEvents());
    assertTrue(after.chainOk());
  }
}
