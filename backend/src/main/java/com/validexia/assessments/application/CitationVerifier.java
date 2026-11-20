package com.validexia.assessments.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic citation verification (D6): a model's verdict is never trusted for the provenance
 * of a citation. Every citation is re-checked here, in code, against the page the documents slice
 * stored:
 *
 * <ol>
 *   <li>the cited page must have been supplied as evidence;
 *   <li>the page's stored SHA-256 must match the hash recomputed from the stored page text, so the
 *       text is intact and is the text the database holds;
 *   <li>the quote must appear verbatim in that text (whitespace-normalized, so PDF extraction line
 *       breaks cannot manufacture a miss).
 * </ol>
 *
 * <p><b>Provenance.</b> The page text and hash are read from the database by the caller; this class
 * never receives them from a client or a model. A fabricated page whose supplied hash matches its
 * own fabricated text is therefore impossible by construction — the hash is compared against text
 * the documents slice stored, not text the requester asserts.
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

  /** One page exactly as the documents slice stored it: its number, text and text hash. */
  public record StoredPage(int pageNumber, String text, String textSha256) {}

  /** A proposed quotation of one cited page. No hash: the hash is always computed here. */
  public record Citation(int pageNumber, String quote) {}

  /** The outcome of verifying one citation. */
  public record Result(
      boolean verified, @Nullable String failure, @Nullable String computedQuoteSha256) {}

  public static Result verify(Citation citation, List<StoredPage> pages) {
    if (citation.pageNumber() <= 0) {
      return new Result(false, "cited page number must be positive", null);
    }
    if (citation.quote() == null || citation.quote().isBlank()) {
      return new Result(false, "cited quote is empty", null);
    }
    StoredPage page = findPage(citation.pageNumber(), pages);
    if (page == null) {
      return new Result(
          false, "cited page " + citation.pageNumber() + " was not supplied as evidence", null);
    }
    if (page.text() == null || page.textSha256() == null || page.textSha256().isBlank()) {
      return new Result(
          false, "cited page " + citation.pageNumber() + " was stored without its text hash", null);
    }
    if (!sha256Hex(page.text()).equalsIgnoreCase(page.textSha256())) {
      return new Result(
          false,
          "cited page " + citation.pageNumber() + " text hash does not match the stored text",
          null);
    }
    if (!normalize(page.text()).contains(normalize(citation.quote()))) {
      return new Result(false, "cited quote is not present on page " + citation.pageNumber(), null);
    }
    return new Result(true, null, sha256Hex(citation.quote()));
  }

  private static @Nullable StoredPage findPage(int pageNumber, List<StoredPage> pages) {
    for (StoredPage page : pages) {
      if (page.pageNumber() == pageNumber) {
        return page;
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
