package com.validexia.assessments.api;

import com.validexia.assessments.application.AssessmentData;
import com.validexia.assessments.application.AssessmentData.AssessmentStatus;
import com.validexia.assessments.application.AssessmentData.CitationRequest;
import com.validexia.assessments.application.AssessmentData.EvidenceRequest;
import com.validexia.assessments.application.AssessmentData.FindingStatus;
import com.validexia.assessments.application.AssessmentData.FindingType;
import com.validexia.assessments.application.AssessmentService;
import com.validexia.platform.errors.ApiException;
import com.validexia.platform.security.RequestContext;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Assessment endpoints (migration V15). Everything is scoped to the caller's per-request tenant —
 * the {@code X-Tenant-Id} header verified by the authentication filter — so there is no
 * cross-tenant view.
 *
 * <p><b>Evidence contract.</b> {@code /compare} and {@code /findings} accept document
 * <em>references</em> ({@link EvidenceRequest}, {@link CitationRequest}) — a document id, a page
 * number and, for a finding, a proposed quotation. They never accept page text, a page hash, a
 * quote hash or an evidence record id: those are resolved server-side from the documents slice, and
 * a quotation that cannot be verified against the stored page text is rejected.
 *
 * <p>The model-driven endpoints ({@code /extract}, {@code /compare}) call the model layer, which
 * fails closed when no provider is configured: an unconfigured deployment gets an error, never a
 * fabricated answer. Findings created here are always {@code Pending Approval}; the only path to
 * {@code Approved} or {@code Rejected} is the {@code /decision} endpoint, which records the human
 * decider and reason.
 *
 * <p>Document and questionnaire content is DATA, never instructions (D8).
 */
@RestController
@RequestMapping("/api/v1/assessments")
public class AssessmentController {

  private final AssessmentService assessments;

  public AssessmentController(AssessmentService assessments) {
    this.assessments = assessments;
  }

  @PostMapping
  public AssessmentData.Assessment create(@RequestBody CreateAssessmentRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    if (request.vendorId() == null) {
      throw ApiException.validation("vendorId is required");
    }
    return call(() -> assessments.createAssessment(tenantId, context.userId(), request.vendorId()));
  }

  @GetMapping
  public List<AssessmentData.Assessment> list(
      @RequestParam(value = "vendorId", required = false) @Nullable UUID vendorId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return assessments.listAssessments(tenantId, context.userId(), vendorId);
  }

  @GetMapping("/{assessmentId}")
  public AssessmentData.Assessment get(@PathVariable UUID assessmentId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return call(() -> assessments.requireAssessment(tenantId, context.userId(), assessmentId));
  }

  /** Moves an assessment through its lifecycle. Terminal states are enforced in migration V15. */
  @PostMapping("/{assessmentId}/status")
  public AssessmentData.Assessment setStatus(
      @PathVariable UUID assessmentId, @RequestBody StatusRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    AssessmentStatus status; // parsed here so a bad value is a 400, not a 500
    try {
      status = AssessmentStatus.fromText(request.status());
    } catch (IllegalArgumentException e) {
      throw ApiException.validation(e.getMessage());
    }
    return call(
        () -> assessments.setAssessmentStatus(tenantId, context.userId(), assessmentId, status));
  }

  @GetMapping("/{assessmentId}/claims")
  public List<AssessmentData.Claim> claims(@PathVariable UUID assessmentId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return call(() -> assessments.listAssessmentClaims(tenantId, context.userId(), assessmentId));
  }

  /** Replaces the claim set by hand (no model involved). */
  @PostMapping("/{assessmentId}/claims")
  public List<AssessmentData.Claim> replaceClaims(
      @PathVariable UUID assessmentId, @RequestBody ClaimListRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return call(
        () ->
            assessments.replaceAssessmentClaims(
                tenantId, context.userId(), assessmentId, request.claims()));
  }

  /**
   * Model-driven claim extraction. The model answers through a structured schema; the answers are
   * zipped into claims and persisted as the assessment's whole claim set.
   */
  @PostMapping("/{assessmentId}/extract")
  public AssessmentData.ExtractClaimsResult extract(
      @PathVariable UUID assessmentId, @RequestBody ClaimListRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return call(
        () ->
            assessments.extractClaims(tenantId, context.userId(), assessmentId, request.claims()));
  }

  /**
   * Model-driven comparison of the assessment's claims against the supplied document references.
   *
   * <p>The request carries {@code passages}: a list of {@code documentId} + {@code pageNumber}. The
   * server resolves each to stored page text, sends that text to the model, and re-verifies every
   * citation in code before storing it. A reference that cannot be resolved (foreign document, not
   * {@code Ready}, no vendor, vendor mismatch, unknown page) fails the request closed.
   */
  @PostMapping("/{assessmentId}/compare")
  public AssessmentData.CompareClaimsResult compare(
      @PathVariable UUID assessmentId, @RequestBody CompareRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return call(
        () ->
            assessments.compareClaims(
                tenantId, context.userId(), assessmentId, request.passages()));
  }

  @GetMapping("/{assessmentId}/findings")
  public List<AssessmentData.Finding> findings(@PathVariable UUID assessmentId) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    return call(() -> assessments.listFindings(tenantId, context.userId(), assessmentId));
  }

  /**
   * Creates a finding as {@code Pending Approval}. The severity is computed from the finding type
   * by deterministic code (D6) — a caller never supplies it.
   *
   * <p>{@code evidence} is a proposed citation: a document, a page and a quotation. It is verified
   * against the stored page text; an unverifiable quotation is a client error and nothing is
   * stored. The same rule applies to the model-driven {@code /compare} path.
   */
  @PostMapping("/{assessmentId}/findings")
  public AssessmentData.CreateFindingResult createFinding(
      @PathVariable UUID assessmentId, @RequestBody FindingRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    FindingType type;
    try {
      type = FindingType.fromText(request.findingType());
    } catch (IllegalArgumentException e) {
      throw ApiException.validation(e.getMessage());
    }
    var input =
        new AssessmentData.FindingInput(
            assessmentId,
            request.claimId(),
            type,
            request.title(),
            request.explanation(),
            request.evidence());
    return call(() -> assessments.createFinding(tenantId, context.userId(), input));
  }

  /** The only path to {@code Approved} or {@code Rejected}; records the decider and the reason. */
  @PostMapping("/{assessmentId}/findings/{findingId}/decision")
  public AssessmentData.Finding decide(
      @PathVariable UUID assessmentId,
      @PathVariable UUID findingId,
      @RequestBody DecisionRequest request) {
    RequestContext.Context context = RequestContext.require();
    UUID tenantId = requireTenant(context);
    FindingStatus decision;
    try {
      decision = FindingStatus.fromText(request.decision());
    } catch (IllegalArgumentException e) {
      throw ApiException.validation(e.getMessage());
    }
    return call(
        () ->
            assessments.decideFinding(
                tenantId, context.userId(), assessmentId, findingId, decision, request.reason()));
  }

  private static UUID requireTenant(RequestContext.Context context) {
    if (context.tenantId() == null) {
      throw ApiException.validation("X-Tenant-Id header is required for assessment endpoints");
    }
    return context.tenantId();
  }

  /** Maps a service {@link IllegalArgumentException} onto the API's 400 body. */
  private static <T> T call(Supplier<T> work) {
    try {
      return work.get();
    } catch (IllegalArgumentException e) {
      throw ApiException.validation(e.getMessage());
    }
  }

  public record CreateAssessmentRequest(UUID vendorId) {}

  public record StatusRequest(String status) {}

  public record ClaimListRequest(List<AssessmentData.ClaimInput> claims) {}

  /**
   * Comparison input. {@code passages} are document references, never page text: the stored text is
   * read from the database and verified in code.
   */
  public record CompareRequest(List<EvidenceRequest> passages) {}

  public record FindingRequest(
      @Nullable UUID claimId,
      String findingType,
      String title,
      String explanation,
      @Nullable CitationRequest evidence) {}

  public record DecisionRequest(String decision, @Nullable String reason) {}
}
