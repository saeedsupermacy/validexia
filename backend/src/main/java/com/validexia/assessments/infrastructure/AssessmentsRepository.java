package com.validexia.assessments.infrastructure;

import com.validexia.assessments.application.AssessmentData.Assessment;
import com.validexia.assessments.application.AssessmentData.AssessmentStatus;
import com.validexia.assessments.application.AssessmentData.Claim;
import com.validexia.assessments.application.AssessmentData.ClaimInput;
import com.validexia.assessments.application.AssessmentData.Finding;
import com.validexia.assessments.application.AssessmentData.FindingInput;
import com.validexia.assessments.application.AssessmentData.FindingStatus;
import com.validexia.assessments.application.AssessmentData.Severity;
import com.validexia.assessments.application.AssessmentStore;
import com.validexia.platform.tenancy.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The assessments module's only door to its tables, implementing the {@link AssessmentStore} port.
 * Every method calls a SECURITY DEFINER function from migration V14; the application role holds no
 * direct grants on the assessment tables. Each call runs in its own transaction opened through the
 * tenant-aware transaction manager, with the tenant context set first — the same pattern {@code
 * VendorsRepository} and {@code DocumentsRepository} use, so {@code app.tenant_id} is the
 * transaction's first statement and the functions' fail-closed tenant guard sees it.
 *
 * <p>A create or decide is a two-step read-back: the mutation function returns an id or a boolean,
 * and the stored record is then read through the tenant-scoped list function, so the caller always
 * gets the record the database actually holds — never a locally assembled one.
 */
@Repository
public class AssessmentsRepository implements AssessmentStore {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public AssessmentsRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public UUID createAssessment(UUID tenantId, UUID userId, UUID vendorId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.queryForObject(
                "SELECT public.create_assessment(?, ?, ?)",
                UUID.class,
                tenantId,
                vendorId,
                userId));
  }

  @Override
  public Optional<Assessment> getAssessment(UUID tenantId, UUID userId, UUID assessmentId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc
                .query(
                    "SELECT * FROM public.get_assessment(?, ?)",
                    AssessmentsRepository::mapAssessment,
                    tenantId,
                    assessmentId)
                .stream()
                .findFirst());
  }

  @Override
  public List<Assessment> listAssessments(UUID tenantId, UUID userId, @Nullable UUID vendorId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.query(
                "SELECT * FROM public.list_assessments(?, ?)",
                AssessmentsRepository::mapAssessment,
                tenantId,
                vendorId));
  }

  @Override
  public boolean setAssessmentStatus(
      UUID tenantId, UUID userId, UUID assessmentId, AssessmentStatus status) {
    return inTenant(
        tenantId,
        userId,
        () ->
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "SELECT public.set_assessment_status(?, ?, ?)",
                    Boolean.class,
                    tenantId,
                    assessmentId,
                    status.text())));
  }

  @Override
  public int replaceAssessmentClaims(
      UUID tenantId, UUID userId, UUID assessmentId, List<ClaimInput> claims) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.queryForObject(
                "SELECT public.replace_assessment_claims(?, ?, CAST(? AS jsonb))",
                Integer.class,
                tenantId,
                assessmentId,
                claimsJson(claims)));
  }

  @Override
  public List<Claim> listAssessmentClaims(UUID tenantId, UUID userId, UUID assessmentId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.query(
                "SELECT * FROM public.list_assessment_claims(?, ?)",
                (rs, row) ->
                    new Claim(
                        rs.getObject("id", UUID.class),
                        assessmentId,
                        rs.getString("question_text"),
                        rs.getString("answer_text"),
                        toInstant(rs.getObject("created_at", OffsetDateTime.class))),
                tenantId,
                assessmentId));
  }

  /**
   * Creates the finding and reads it back. The status is always {@code Pending Approval}: the
   * database refuses any decided status on create, and approval is a human decision recorded
   * through {@link #decideFinding}.
   */
  @Override
  public Finding createFinding(UUID tenantId, UUID userId, FindingInput finding) {
    Severity severity = Severity.forFindingType(finding.findingType());
    UUID id =
        inTenant(
            tenantId,
            userId,
            () ->
                jdbc.queryForObject(
                    "SELECT public.create_finding(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.class,
                    tenantId,
                    finding.assessmentId(),
                    finding.claimId(),
                    finding.findingType().text(),
                    severity.text(),
                    finding.title(),
                    finding.explanation(),
                    finding.evidence() == null ? null : finding.evidence().recordId(),
                    finding.evidence() == null ? null : finding.evidence().pageNumber(),
                    finding.evidence() == null ? null : finding.evidence().quote(),
                    finding.evidence() == null ? null : finding.evidence().quoteSha256(),
                    FindingStatus.PENDING_APPROVAL.text()));
    return listFindings(tenantId, userId, finding.assessmentId()).stream()
        .filter(row -> row.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Finding vanished after creation"));
  }

  @Override
  public List<Finding> listFindings(UUID tenantId, UUID userId, UUID assessmentId) {
    return inTenant(
        tenantId,
        userId,
        () ->
            jdbc.query(
                "SELECT * FROM public.list_findings(?, ?)",
                AssessmentsRepository::mapFinding,
                tenantId,
                assessmentId));
  }

  @Override
  public @Nullable Finding decideFinding(
      UUID tenantId,
      UUID userId,
      UUID assessmentId,
      UUID findingId,
      FindingStatus decision,
      @Nullable String reason) {
    Boolean changed =
        inTenant(
            tenantId,
            userId,
            () ->
                jdbc.queryForObject(
                    "SELECT public.decide_finding(?, ?, ?, ?, ?)",
                    Boolean.class,
                    tenantId,
                    findingId,
                    decision.text(),
                    userId,
                    reason));
    if (!Boolean.TRUE.equals(changed)) {
      return null;
    }
    return listFindings(tenantId, userId, assessmentId).stream()
        .filter(row -> row.id().equals(findingId))
        .findFirst()
        .orElse(null);
  }

  // -- mapping ---------------------------------------------------------------------

  private static Assessment mapAssessment(ResultSet rs, int row) throws SQLException {
    return new Assessment(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("vendor_id", UUID.class),
        AssessmentStatus.fromText(rs.getString("status")),
        rs.getObject("created_by_user_id", UUID.class),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)),
        toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
  }

  private static Finding mapFinding(ResultSet rs, int row) throws SQLException {
    return new Finding(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("assessment_id", UUID.class),
        rs.getObject("claim_id", UUID.class),
        com.validexia.assessments.application.AssessmentData.FindingType.fromText(
            rs.getString("finding_type")),
        Severity.fromText(rs.getString("severity")),
        rs.getString("title"),
        rs.getString("explanation"),
        rs.getObject("evidence_record_id", UUID.class),
        rs.getObject("evidence_page_number", Integer.class),
        rs.getString("evidence_quote"),
        rs.getString("evidence_quote_sha256"),
        FindingStatus.fromText(rs.getString("status")),
        rs.getObject("decided_by_user_id", UUID.class),
        toInstant(rs.getObject("decided_at", OffsetDateTime.class)),
        rs.getString("decision_reason"),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)),
        toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
  }

  private <T> T inTenant(UUID tenantId, @Nullable UUID userId, Supplier<T> work) {
    TenantContext.set(tenantId.toString(), userId == null ? null : userId.toString());
    try {
      return transactions.execute(tx -> work.get());
    } finally {
      TenantContext.clear();
    }
  }

  private static Instant toInstant(@Nullable OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  /**
   * Serializes the claim set for {@code replace_assessment_claims}. Keys are the snake_case field
   * names the function's {@code jsonb_array_elements} reads; answer text is user content and is
   * escaped so it can never break the payload.
   */
  private static String claimsJson(List<ClaimInput> claims) {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < claims.size(); i++) {
      ClaimInput claim = claims.get(i);
      if (i > 0) {
        json.append(',');
      }
      json.append("{\"question_text\":")
          .append(quoteJson(claim.questionText()))
          .append(",\"answer_text\":")
          .append(quoteJson(claim.answerText()))
          .append('}');
    }
    return json.append(']').toString();
  }

  /** Minimal JSON string quoting; claim text is user content and must never break the payload. */
  private static String quoteJson(String value) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}
