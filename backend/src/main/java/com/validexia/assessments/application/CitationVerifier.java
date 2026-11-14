package com.validexia.assessments.application;

import com.validexia.assessments.application.AssessmentData.EvidencePassage;
import com.validexia.assessments.application.AssessmentData.EvidenceRef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic citation verification (D6): a model's verdict is never trusted for the provenance
 * of a citation. Every citation a model produces is re-checked here, in code, against the evidence
 * page it claims to come from:
 *
 * <ol>
 *   <li>the cited page must have been supplied as evidence;
 *   <li>the page's stored SHA-256 must match the hash recomputed from the supplied page text, so
 *       the page itself is intact and is the page the caller meant;
 *   <li>the quote must appear verbatim in that page text (whitespace-normalized, so PDF extraction
 *       line breaks cannot manufacture a miss);
 *   <li>if the caller supplied a quote hash, it must match the hash recomputed from the quote.
 * </ol>
 *
 * <p>Only a citation that passes every check is stored. A failed citation is never stored as one:
 * the finding is downgraded to {@code Unsupported}, its evidence fields are cleared, and the
 * failure is recorded in the finding's explanation. The stored {@code evidence_quote_sha256} is
 * always the hash computed here, never a value read from a model response.
 *
 * <p>Whitespace normalization collapses every run of whitespace to a single space and trims the
 * ends. It never reorders, substitutes or drops characters, so it cannot turn a fabricated quote
 * into a match.
 */
public final class CitationVerifier {

  private CitationVerifier() {}

  /** The outcome of verifying one citation. */
  public record Result(
      boolean verified, @Nullable String failure, @Nullable String computedQuoteSha256) {}

  public static Result verify(EvidenceRef citation, List<EvidencePassage> passages) {
    if (citation.pageNumber() <= 0) {
      return new Result(false, "cited page number must be positive", null);
    }
    if (citation.quote() == null || citation.quote().isBlank()) {
      return new Result(false, "cited quote is empty", null);
    }
    EvidencePassage passage = findPassage(citation.pageNumber(), passages);
    if (passage == null) {
      return new Result(
          false, "cited page " + citation.pageNumber() + " was not supplied as evidence", null);
    }
    if (passage.text() == null || passage.textSha256() == null || passage.textSha256().isBlank()) {
      return new Result(
          false,
          "cited page " + citation.pageNumber() + " was supplied without its text hash",
          null);
    }
    if (!sha256Hex(passage.text()).equalsIgnoreCase(passage.textSha256())) {
      return new Result(
          false,
          "cited page " + citation.pageNumber() + " text hash does not match the supplied text",
          null);
    }
    if (!normalize(passage.text()).contains(normalize(citation.quote()))) {
      return new Result(false, "cited quote is not present on page " + citation.pageNumber(), null);
    }
    String computed = sha256Hex(citation.quote());
    if (citation.quoteSha256() != null
        && !citation.quoteSha256().isBlank()
        && !computed.equalsIgnoreCase(citation.quoteSha256())) {
      return new Result(false, "cited quote hash does not match the quote", null);
    }
    return new Result(true, null, computed);
  }

  private static @Nullable EvidencePassage findPassage(
      int pageNumber, List<EvidencePassage> passages) {
    for (EvidencePassage passage : passages) {
      if (passage.pageNumber() == pageNumber) {
        return passage;
      }
    }
    return null;
  }

  /** Lowercase hex SHA-256 of the UTF-8 bytes of {@code value}. */
  public static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString().toLowerCase(Locale.ROOT);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM has no SHA-256 digest", e);
    }
  }

  /** Collapses whitespace runs to one space and trims; never alters any other character. */
  public static String normalize(String value) {
    return value.replaceAll("\\s+", " ").trim();
  }
}
