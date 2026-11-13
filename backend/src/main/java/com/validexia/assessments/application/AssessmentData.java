package com.validexia.assessments.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Application-layer views of the assessments slice: the records the API returns, the inputs it
 * accepts, and the enumerated values whose meaning is fixed by deterministic code (D6 — the model
 * never supplies a severity, a status transition or a score).
 *
 * <p>The API layer depends on these records only, never on repository row types, so the layering
 * rule (controller → application service → repository) holds for this module exactly as it does
 * for identity and agents.
 */
public final class AssessmentData {

  private AssessmentData() {}

  /** Lifecycle of an assessment. Approved and Rejected are terminal (enforced in migration V14). */
  public enum AssessmentStatus {
    DRAFT("Draft"),
    IN_REVIEW("In Review"),
    APPROVED("Approved"),
    REJECTED("Rejected");

    private final String text;

    AssessmentStatus(String text) {
      this.text = text;
    }

    public String text() {
      return text;
    }

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

    public String text() {
      return text;
    }

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

    public String text() {
      return text;
    }

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

    public String text() {
      return text;
    }

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
   * One page of document text supplied as comparison evidence, with the hash the documents slice
   * stored for it. The hash is verified before any citation drawn from the page is stored.
   */
  public record EvidencePassage(int pageNumber, String text, String textSha256) {}

  /**
   * A citation of one evidence passage. {@code quoteSha256} is optional: when the caller supplies
   * one it is verified against the hash recomputed from the quote, and a mismatch fails the
   * citation. When it is absent the stored hash is the deterministically computed one.
   */
  public record EvidenceRef(
      @Nullable UUID recordId, int pageNumber, String quote, @Nullable String quoteSha256) {}

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

  /** A finding as a caller supplies it: no id, no severity and no status — those are code-owned. */
  public record FindingInput(
      UUID assessmentId,
      @Nullable UUID claimId,
      FindingType findingType,
      String title,
      String explanation,
      @Nullable EvidenceRef evidence) {}

  public record ExtractClaimsResult(
      UUID assessmentId,
      int claimCount,
      List<Claim> claims,
      String provider,
      String model) {}

  public record CompareClaimsResult(
      UUID assessmentId,
      int findingCount,
      List<Finding> findings,
      String provider,
      String model) {}

  public record CreateFindingResult(
      UUID findingId, FindingStatus status, FindingType findingType, Severity severity) {}
}
