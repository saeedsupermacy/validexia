package com.validexia.identity.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.validexia.identity.infrastructure.DirectoryRepository;
import com.validexia.support.TestClerkConfig;
import com.validexia.support.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * Webhook receiver flows (M02-1 v2.6 §2): Svix verification, insert-first idempotency, and the
 * payload-driven lifecycle (ban/lock state arrives ONLY via user.updated — Clerk has no ban/lock
 * webhook events).
 */
@SpringBootTest(
    properties = {
      "validexia.clerk.issuer=https://clerk.test.example",
      "validexia.clerk.jwks-url=https://clerk.test.example/.well-known/jwks.json",
      "validexia.clerk.webhook-signing-secret=whsec_"
          + "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=" // base64 of bytes 1..32
    })
@AutoConfigureMockMvc
@Import(TestClerkConfig.class)
class ClerkWebhookTest {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.springProperties().forEach((key, value) -> registry.add(key, () -> value));
  }

  private static final byte[] KEY_BYTES = new byte[32];

  static {
    for (int i = 0; i < KEY_BYTES.length; i++) {
      KEY_BYTES[i] = (byte) (i + 1);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired DirectoryRepository repository;

  private final JdbcTemplate admin = TestDatabase.adminJdbc();

  private org.springframework.test.web.servlet.ResultActions deliver(
      String svixId, String payload, boolean validSignature) throws Exception {
    long ts = System.currentTimeMillis() / 1000;
    String content = svixId + "." + ts + "." + payload;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(KEY_BYTES, "HmacSHA256"));
    String signature =
        "v1," + Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    if (!validSignature) {
      signature = "v1," + Base64.getEncoder().encodeToString(new byte[32]);
    }
    return mvc.perform(
        post("/api/v1/webhooks/clerk")
            .header("svix-id", svixId)
            .header("svix-timestamp", Long.toString(ts))
            .header("svix-signature", signature)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload));
  }

  private static String userUpdated(String subject, boolean banned, boolean locked, long updatedAtMs) {
    return "{\"type\":\"user.updated\",\"data\":{\"id\":\"" + subject + "\","
        + "\"first_name\":\"Wh\",\"last_name\":\"User\","
        + "\"email_addresses\":[{\"email_address\":\"wh@example.test\"}],"
        + "\"banned\":" + banned + ",\"locked\":" + locked + ",\"deprovisioned\":false,"
        + "\"updated_at\":" + updatedAtMs + "}}";
  }

  @Test
  void userUpdatedAppliesBanFromPayload() throws Exception {
    String subject = "user_wh_" + UUID.randomUUID();
    long ts = System.currentTimeMillis() - 5000;
    deliver("msg_" + UUID.randomUUID(), userUpdated(subject, true, false, ts), true)
        .andExpect(status().isOk());
    var user = repository.findUser(subject).orElseThrow();
    assertTrue(user.banned());
    assertEquals("wh@example.test", admin.queryForObject(
        "SELECT email FROM public.users WHERE external_subject = ?", String.class, subject));
  }

  @Test
  void duplicateDeliveryIsAppliedOnce() throws Exception {
    String subject = "user_wh_" + UUID.randomUUID();
    String svixId = "msg_" + UUID.randomUUID();
    String payload = userUpdated(subject, true, false, System.currentTimeMillis() - 5000);
    deliver(svixId, payload, true).andExpect(status().isOk());
    deliver(svixId, payload, true).andExpect(status().isOk());
    Integer rows =
        admin.queryForObject(
            "SELECT count(*) FROM public.webhook_events WHERE svix_id = ?", Integer.class, svixId);
    assertEquals(1, rows);
    String outcome =
        admin.queryForObject(
            "SELECT outcome FROM public.webhook_events WHERE svix_id = ?", String.class, svixId);
    assertEquals("applied", outcome);
  }

  @Test
  void invalidSignatureIsRejected() throws Exception {
    String subject = "user_wh_" + UUID.randomUUID();
    deliver(
            "msg_" + UUID.randomUUID(),
            userUpdated(subject, true, false, System.currentTimeMillis()),
            false)
        .andExpect(status().isBadRequest());
    assertTrue(repository.findUser(subject).isEmpty(), "a forged event must change nothing");
  }

  @Test
  void outOfOrderUpdatesConvergeOnTheNewestFlagValue() throws Exception {
    String subject = "user_wh_" + UUID.randomUUID();
    long newer = System.currentTimeMillis() - 1000;
    long older = newer - 60_000;
    deliver("msg_" + UUID.randomUUID(), userUpdated(subject, true, false, newer), true)
        .andExpect(status().isOk());
    deliver("msg_" + UUID.randomUUID(), userUpdated(subject, false, false, older), true)
        .andExpect(status().isOk());
    assertTrue(
        repository.findUser(subject).orElseThrow().banned(),
        "the stale unban must be ignored");
  }

  @Test
  void sessionRevokedDenylistsTheSession() throws Exception {
    String subject = "user_wh_" + UUID.randomUUID();
    String payload =
        "{\"type\":\"session.revoked\",\"data\":{\"id\":\"sess_wh_revoked\",\"user_id\":\""
            + subject + "\"}}";
    deliver("msg_" + UUID.randomUUID(), payload, true).andExpect(status().isOk());
    assertTrue(repository.isSessionRevoked("sess_wh_revoked"));
  }
}
