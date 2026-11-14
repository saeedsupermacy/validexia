package com.validexia.assessments.application;

import com.validexia.assessments.application.AssessmentData.Assessment;
import com.validexia.assessments.application.AssessmentData.AssessmentStatus;
import com.validexia.assessments.application.AssessmentData.Claim;
import com.validexia.assessments.application.AssessmentData.ClaimInput;
import com.validexia.assessments.application.AssessmentData.Finding;
import com.validexia.assessments.application.AssessmentData.FindingInput;
import com.validexia.assessments.application.AssessmentData.FindingStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The persistence port of the assessments slice. {@link AssessmentService} depends on this
 * interface only; {@code AssessmentsRepository} (infrastructure) implements it against the SECURITY
 * DEFINER functions of migration V14. Keeping the port in the application package is what lets the
 * service's rules — deterministic severity, citation verification, approval gating — be tested
 * without a database, and what keeps the controller free of data-access types (D11).
 *
 * <p>Every method carries the tenant and the acting user explicitly: the repository sets the tenant
 * context from them before opening its transaction, so the database's fail-closed tenant guard sees
 * the same tenant the caller asked for. A {@code null} {@code userId} is permitted only for reads;
 * mutations that are audited require a real actor.
 */
public interface AssessmentStore {

  UUID createAssessment(UUID tenantId, UUID userId, UUID vendorId);

  Optional<Assessment> getAssessment(UUID tenantId, UUID userId, UUID assessmentId);

  List<Assessment> listAssessments(UUID tenantId, UUID userId, @Nullable UUID vendorId);

  /** Returns false when the assessment is not in the tenant or the transition is not allowed. */
  boolean setAssessmentStatus(
      UUID tenantId, UUID userId, UUID assessmentId, AssessmentStatus status);

  /** Replaces the assessment's whole claim set atomically; returns the new claim count. */
  int replaceAssessmentClaims(
      UUID tenantId, UUID userId, UUID assessmentId, List<ClaimInput> claims);

  List<Claim> listAssessmentClaims(UUID tenantId, UUID userId, UUID assessmentId);

  /**
   * Creates a finding as {@code Pending Approval} and returns the stored record. The store never
   * accepts a decided status: approval is a human decision recorded through {@link #decideFinding}.
   */
  Finding createFinding(UUID tenantId, UUID userId, FindingInput finding);

  List<Finding> listFindings(UUID tenantId, UUID userId, UUID assessmentId);

  /**
   * Records a human decision and returns the updated finding, or {@code null} when the finding is
   * not in the tenant or is already decided. The assessment id is required so the updated record
   * can be read back through the tenant-scoped list function.
   */
  @Nullable Finding decideFinding(
      UUID tenantId,
      UUID userId,
      UUID assessmentId,
      UUID findingId,
      FindingStatus decision,
      @Nullable String reason);
}
