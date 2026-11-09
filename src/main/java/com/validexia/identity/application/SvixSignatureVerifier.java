package com.validexia.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Svix webhook signature verification, as used by Clerk (M02-1 v2.6 §2): HMAC-SHA256 over {@code
 * svix-id + "." + svix-timestamp + "." + raw-body}, compared in constant time against the {@code
 * v1,} entries of the signature header, with a 5-minute timestamp tolerance.
 */
@Component
public class SvixSignatureVerifier {

  private static final long TOLERANCE_SECONDS = 300;

  private final Clock clock;

  public SvixSignatureVerifier(Clock clock) {
    this.clock = clock;
  }

  public boolean verify(
      String signingSecret,
      @Nullable String svixId,
      @Nullable String svixTimestamp,
      @Nullable String svixSignature,
      byte[] rawBody) {
    if (svixId == null || svixTimestamp == null || svixSignature == null) {
      return false;
    }
    long timestamp;
    try {
      timestamp = Long.parseLong(svixTimestamp.trim());
    } catch (NumberFormatException e) {
      return false;
    }
    long now = clock.instant().getEpochSecond();
    if (Math.abs(now - timestamp) > TOLERANCE_SECONDS) {
      return false;
    }
    byte[] key;
    try {
      String base64 = signingSecret.startsWith("whsec_") ? signingSecret.substring(6) : signingSecret;
      key = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      return false;
    }
    String signedContent = svixId + "." + svixTimestamp + "." + new String(rawBody, StandardCharsets.UTF_8);
    byte[] expected;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      expected = mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      return false;
    }
    String expectedBase64 = Base64.getEncoder().encodeToString(expected);
    for (String entry : svixSignature.split(" ")) {
      String[] parts = entry.split(",", 2);
      if (parts.length == 2
          && "v1".equals(parts[0])
          && MessageDigest.isEqual(
              expectedBase64.getBytes(StandardCharsets.UTF_8),
              parts[1].getBytes(StandardCharsets.UTF_8))) {
        return true;
      }
    }
    return false;
  }
}
