package com.validexia.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * Cross-checks the canonical audit hash serialization (M02-1 v2.5 §3) in Java against the normative
 * test vectors. The PostgreSQL trigger implements the same encoding; AuditChainTest proves the
 * database reproduces these hashes end-to-end.
 */
class AuditHashTest {

  private static byte[] enc(String value) throws Exception {
    if (value == null) {
      return new byte[] {0};
    }
    byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(1);
    out.write((utf8.length >>> 24) & 0xFF);
    out.write((utf8.length >>> 16) & 0xFF);
    out.write((utf8.length >>> 8) & 0xFF);
    out.write(utf8.length & 0xFF);
    out.write(utf8);
    return out.toByteArray();
  }

  private static byte[] be64(long value) {
    byte[] out = new byte[8];
    for (int i = 7; i >= 0; i--) {
      out[i] = (byte) (value & 0xFF);
      value >>>= 8;
    }
    return out;
  }

  private static String hash(
      byte[] prevHash,
      String tenantKey,
      long seq,
      String actorType,
      String actorId,
      String action,
      String entityType,
      String entityId,
      String detailsJsonbText,
      long createdAtMicros)
      throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(prevHash);
    out.write(enc(tenantKey));
    out.write(be64(seq));
    out.write(enc(actorType));
    out.write(enc(actorId));
    out.write(enc(action));
    out.write(enc(entityType));
    out.write(enc(entityId));
    out.write(enc(detailsJsonbText));
    out.write(be64(createdAtMicros));
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()));
  }

  private static final byte[] GENESIS = new byte[32];

  @Test
  void vectorA() throws Exception {
    String actual =
        hash(
            GENESIS,
            "SYSTEM",
            1,
            "SYSTEM",
            null,
            "CHAIN_GENESIS",
            "AUDIT_CHAIN",
            null,
            "{}",
            1735689600000000L);
    assertEquals("c6cd4709f4b4be900dc02e44d4819366f04cde7d06925f0fba1238d61fcbd81b", actual);
  }

  @Test
  void vectorD_nullVersusEmptyString() throws Exception {
    String actual =
        hash(
            GENESIS,
            "SYSTEM",
            1,
            "SYSTEM",
            "",
            "CHAIN_GENESIS",
            "AUDIT_CHAIN",
            null,
            "{}",
            1735689600000000L);
    assertEquals("1695f9873628ff3a859e450370aff49262569da4507f4dd3c53c549b4ed6e0e5", actual);
  }

  @Test
  void vectorB() throws Exception {
    String actual =
        hash(
            GENESIS,
            "123e4567-e89b-42d3-a456-426614174000",
            1,
            "USER",
            "987e6543-e21b-43d3-a456-426614174999",
            "TENANT_CONTEXT_ESTABLISHED",
            "SESSION",
            null,
            "{\"method\": \"clerk\"}",
            1735689601000000L);
    assertEquals("78e62cb2af53fe99db9fe2be21ae974a26a6a09f1b422742eb763a53c7ba5b2e", actual);
  }

  @Test
  void vectorC() throws Exception {
    byte[] prevB =
        HexFormat.of().parseHex("78e62cb2af53fe99db9fe2be21ae974a26a6a09f1b422742eb763a53c7ba5b2e");
    String actual =
        hash(
            prevB,
            "123e4567-e89b-42d3-a456-426614174000",
            2,
            "USER",
            "987e6543-e21b-43d3-a456-426614174999",
            "LOGOUT",
            "SESSION",
            "sess_0000000001",
            "{}",
            1735689661000000L);
    assertEquals("aea7f05d647a188d4ff27ab4ef2271b737fb1b5e8cfacf6e389172470550ff38", actual);
  }
}
