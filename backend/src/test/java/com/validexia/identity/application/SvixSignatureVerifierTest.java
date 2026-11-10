package com.validexia.identity.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SvixSignatureVerifierTest {

  private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
  private static final byte[] KEY_BYTES = new byte[32];

  static {
    for (int i = 0; i < KEY_BYTES.length; i++) {
      KEY_BYTES[i] = (byte) (i + 1);
    }
  }

  private static final String SECRET =
      "whsec_" + Base64.getEncoder().encodeToString(KEY_BYTES);
  private static final byte[] BODY = "{\"type\":\"user.updated\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);

  private final SvixSignatureVerifier verifier =
      new SvixSignatureVerifier(Clock.fixed(NOW, ZoneOffset.UTC));

  private String sign(String id, long timestamp, byte[] body) throws Exception {
    String content = id + "." + timestamp + "." + new String(body, StandardCharsets.UTF_8);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(KEY_BYTES, "HmacSHA256"));
    return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void acceptsValidSignature() throws Exception {
    long ts = NOW.getEpochSecond();
    assertTrue(verifier.verify(SECRET, "msg_1", Long.toString(ts), sign("msg_1", ts, BODY), BODY));
  }

  @Test
  void rejectsTamperedBody() throws Exception {
    long ts = NOW.getEpochSecond();
    String signature = sign("msg_1", ts, BODY);
    byte[] tampered = "{\"type\":\"user.deleted\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
    assertFalse(verifier.verify(SECRET, "msg_1", Long.toString(ts), signature, tampered));
  }

  @Test
  void rejectsStaleTimestamp() throws Exception {
    long ts = NOW.getEpochSecond() - 3600;
    assertFalse(verifier.verify(SECRET, "msg_1", Long.toString(ts), sign("msg_1", ts, BODY), BODY));
  }

  @Test
  void rejectsMissingHeaders() {
    assertFalse(verifier.verify(SECRET, null, "1", "v1,x", BODY));
    assertFalse(verifier.verify(SECRET, "msg_1", null, "v1,x", BODY));
    assertFalse(verifier.verify(SECRET, "msg_1", "1", null, BODY));
  }
}
