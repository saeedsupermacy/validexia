package com.validexia.assessments.application;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Application-layer views of the assessments slice: the records the API returns, the inputs it
 * accepts, and the enumerated values whose meaning is fixed by deterministic code (D6 — the model
 * never supplies a severity, a status transition or a score).
 *
 * <p><b>Evidence provenance.</b> No record in this file carries document text or a hash as an
 * <em>input</em>. A caller supplies a reference to a page of one of its own tenant's documents
 * ({@link EvidenceRequest}) and, optionally, a proposed quotation ({@link CitationRequest}). The
 * server resolves the stored text and hash from the database and verifies the quotation against
 * them; only the resulting {@link EvidenceRef} — a real {@code evidence_records} row — reaches the
 * store. A caller therefore cannot assert what a document says.
 *
 * <p>The API layer depends on these records only, never on repository row types, so the layering
 * rule (controller → application service → repository) holds for this module exactly as it does for
 * identity, vendors and documents.
 */
public final class AssessmentData {

  private AssessmentData() {}

  /** Lifecycle of an assessment. Approved and Rejected are terminal (enforced in migration V15). */
  public enum AssessmentStatus {
    DRAFT("Draft"),
    IN_REVIEW("In Review"),
    APPROVED("Approved"),
    REJECTED("Rejected");

    private final String text;

    AssessmentStatus(String text) {
      this.text = text;
    }

    @JsonValue
    public String text() {
      return text;
    }

    @JsonCreator
    public static AssessmentStatus fromText(String text) {
      for (AssessmentStatus status : values()) {
        if (status.text.equals(text)) {
          return status;
        }
      }
      throw new IllegalArgumentException("unknown assessment status: " + text);
    }
  }

  /** The verdict a piece of evidence supports for one claim. */
  public enum FindingType {
    SUPPORTED("Supported"),
    UNSUPPORTED("Unsupported"),
    CONTRADICTORY("Contradictory"),
    MISSING("Missing");

    private final String text;

    FindingType(String text) {
      this.text = text;
    }

    @JsonValue
    public String text() {
      return text;
    }

    @JsonCreator
    public static FindingType fromText(String text) {
      for (FindingType type : values()) {
        if (type.text.equals(text)) {
          return type;
        }
      }
      throw new IllegalArgumentException("unknown finding type: " + text);
    }
  }

  /**
   * Severity of a finding. Deterministic by rule (D6): the severity is a function of the finding
   * type, computed here, and is never read from a model response or a client request.
   */
  public enum Severity {
    CRITICAL("Critical"),
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low");

    private final String text;

    Severity(String text) {
      this.text = text;
    }

    @JsonValue
    public String text() {
      return text;
    }

    @JsonCreator
    public static Severity fromText(String text) {
      for (Severity severity : values()) {
        if (severity.text.equals(text)) {
          return severity;
        }
      }
      throw new IllegalArgumentException("unknown severity: " + text);
    }

    /** The one deterministic severity rule of this slice. */
    public static Severity forFindingType(FindingType type) {
      return switch (type) {
        case CONTRADICTORY -> CRITICAL;
        case UNSUPPORTED -> HIGH;
        case MISSING -> MEDIUM;
        case SUPPORTED -> LOW;
      };
    }
  }

  /** Approval workflow of a finding. Approval is a human decision, never an automatic one. */
  public enum FindingStatus {
    DRAFT("Draft"),
    PENDING_APPROVAL("Pending Approval"),
    APPROVED("Approved"),
    REJECTED("Rejected");

    private final String text;

    FindingStatus(String text) {
      this.text = text;
    }

    @JsonValue
    public String text() {
      return text;
    }

    @JsonCreator
    public static FindingStatus fromText(String text) {
      for (FindingStatus status : values()) {
        if (status.text.equals(text)) {
          return status;
        }
      }
      throw new IllegalArgumentException("unknown finding status: " + text);
    }
  }

  public record Assessment(
      UUID id,
      UUID tenantId,
      UUID vendorId,
      AssessmentStatus status,
      UUID createdByUserId,
      Instant createdAt,
      Instant updatedAt) {}

  public record Claim(
      UUID id, UUID assessmentId, String questionText, String answerText, Instant createdAt) {}

  /** A questionnaire answer as supplied by a caller, before any model sees it. */
  public record ClaimInput(String questionText, String answerText) {}

  /**
   * A caller's reference to one page of one of the tenant's documents.
   *
   * <p>Deliberately carries neither text nor a hash. The stored text is read from the database by
   * the server, so a fabricated passage cannot be smuggled in as evidence.
   */
  public record EvidenceRequest(UUID documentId, int pageNumber) {}

  /**
   * A caller's proposed quotation of one referenced page. The quote is verified against the
   * server-retrieved page text before anything is stored; it is never stored on trust.
   */
  public record CitationRequest(UUID documentId, int pageNumber, String quote) {}

  /**
   * What a caller submits to create a finding: no id, no severity, no status, and — crucially — no
   * evidence record id. Those are code-owned.
   */
  public record FindingInput(
      UUID assessmentId,
      @Nullable UUID claimId,
      FindingType findingType,
      String title,
      String explanation,
      @Nullable CitationRequest evidence) {}

  /**
   * A citation the server has resolved and verified. This is the only evidence shape that reaches
   * the store; {@code recordId} names a real {@code evidence_records} row of the caller's tenant.
   */
  public record EvidenceRef(UUID recordId, int pageNumber, String quote, String quoteSha256) {}

  /** A finding as the store persists it: the citation is already server-resolved. */
  public record FindingDraft(
      UUID assessmentId,
      @Nullable UUID claimId,
      FindingType findingType,
      String title,
      String explanation,
      @Nullable EvidenceRef evidence) {}

  public record Finding(
      UUID id,
      UUID tenantId,
      UUID assessmentId,
      @Nullable UUID claimId,
      FindingType findingType,
      Severity severity,
      String title,
      String explanation,
      @Nullable UUID evidenceRecordId,
      @Nullable Integer evidencePageNumber,
      @Nullable String evidenceQuote,
      @Nullable String evidenceQuoteSha256,
      FindingStatus status,
      @Nullable UUID decidedByUserId,
      @Nullable Instant decidedAt,
      @Nullable String decisionReason,
      Instant createdAt,
      Instant updatedAt) {}

  public record ExtractClaimsResult(
      UUID assessmentId, int claimCount, List<Claim> claims, String provider, String model) {}

  public record CompareClaimsResult(
      UUID assessmentId, int findingCount, List<Finding> findings, String provider, String model) {}

  public record CreateFindingResult(
      UUID findingId, FindingStatus status, FindingType findingType, Severity severity) {}
}
