package com.validexia.assessments.application;

import com.validexia.agents.application.DefinedTool;
import com.validexia.agents.application.ToolHandler;
import com.validexia.agents.domain.RiskClass;
import com.validexia.assessments.application.AssessmentData.ClaimInput;
import com.validexia.assessments.application.AssessmentData.EvidencePassage;
import com.validexia.assessments.application.AssessmentData.EvidenceRef;
import com.validexia.assessments.application.AssessmentData.FindingInput;
import com.validexia.assessments.application.AssessmentData.FindingType;
import com.validexia.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The assessment tools registered on the AgentEngine's tool registry, under the same risk classes
 * and gating as every other tool. They are thin adapters over {@link AssessmentService}: the
 * service owns the workflow, the deterministic citation verification and the approval boundary; the
 * tools exist so a registered agent can drive that workflow through a run.
 *
 * <p><b>Tenant context.</b> A tool invocation runs on a pooled thread, so the tenant is not a
 * parameter — it is the run's tenant, published onto the tool thread by the engine (see {@code
 * ToolRun}). A tool that finds no tenant context fails rather than guessing one.
 *
 * <p><b>Risk classes.</b> {@code extract_claims} and {@code compare_claims} are {@link
 * RiskClass#READ}: they read tenant data and call a model, but change nothing the caller did not
 * already ask the service to change. {@code create_finding} is {@link RiskClass#SENSITIVE_WRITE}:
 * it creates a consequential record, so ModeGate always requires a human decision before it runs.
 * The created finding is {@code Pending Approval} — the tool cannot approve anything.
 *
 * <p><b>Prompt injection (D8).</b> Questionnaire answers and document text are supplied to the
 * model as DATA. Nothing here interprets document content as an instruction, and the service
 * verifies every citation in code before it is stored.
 */
@Configuration
public class AssessmentTools {

  private final AssessmentService assessments;
  private final ObjectMapper json;

  public AssessmentTools(AssessmentService assessments, ObjectMapper json) {
    this.assessments = assessments;
    this.json = json;
  }

  /** Extracts discrete, checkable claims from questionnaire answers. */
  @Bean
  ToolHandler assessmentExtractClaims() {
    return new DefinedTool(
        "assessment.extract_claims",
        RiskClass.READ,
        input -> {
          JsonNode root = parse(input);
          UUID assessmentId = requireId(root, "assessmentId");
          List<ClaimInput> answers = readClaims(root.get("answers"));
          var result = assessments.extractClaims(tenant(), userId(), assessmentId, answers);
          ObjectNode out = json.createObjectNode();
          out.put("assessmentId", result.assessmentId().toString());
          out.put("claimCount", result.claimCount());
          out.put("provider", result.provider());
          out.put("model", result.model());
          return out.toString();
        });
  }

  /** Compares the assessment's claims against supplied evidence passages. */
  @Bean
  ToolHandler assessmentCompareClaims() {
    return new DefinedTool(
        "assessment.compare_claims",
        RiskClass.READ,
        input -> {
          JsonNode root = parse(input);
          UUID assessmentId = requireId(root, "assessmentId");
          List<EvidencePassage> passages = readPassages(root.get("passages"));
          var result = assessments.compareClaims(tenant(), userId(), assessmentId, passages);
          ObjectNode out = json.createObjectNode();
          out.put("assessmentId", result.assessmentId().toString());
          out.put("findingCount", result.findingCount());
          out.put("provider", result.provider());
          out.put("model", result.model());
          ArrayNode findings = out.putArray("findings");
          for (var finding : result.findings()) {
            findings.add(finding.id().toString());
          }
          return out.toString();
        });
  }

  /**
   * Creates a finding as {@code Pending Approval}. SENSITIVE_WRITE: ModeGate requires a human
   * decision in every autonomy mode, so this tool never executes unattended.
   */
  @Bean
  ToolHandler assessmentCreateFinding() {
    return new DefinedTool(
        "assessment.create_finding",
        RiskClass.SENSITIVE_WRITE,
        input -> {
          JsonNode root = parse(input);
          UUID assessmentId = requireId(root, "assessmentId");
          FindingType type = readFindingType(root.get("findingType"));
          String title = requireText(root, "title");
          String explanation = requireText(root, "explanation");
          UUID claimId = optionalId(root, "claimId");
          EvidenceRef evidence = readEvidence(root.get("evidence"));
          var findingInput =
              new FindingInput(assessmentId, claimId, type, title, explanation, evidence);
          var result = assessments.createFinding(tenant(), userId(), findingInput);
          ObjectNode out = json.createObjectNode();
          out.put("findingId", result.findingId().toString());
          out.put("status", result.status().text());
          out.put("findingType", result.findingType().text());
          out.put("severity", result.severity().text());
          return out.toString();
        });
  }

  // -- input reading ---------------------------------------------------------------

  private UUID tenant() {
    TenantContext.Values values =
        TenantContext.current()
            .orElseThrow(
                () ->
                    new IllegalStateException("assessment tool invoked without a tenant context"));
    if (values.tenantId() == null) {
      throw new IllegalStateException("assessment tool invoked without a tenant context");
    }
    return UUID.fromString(values.tenantId());
  }

  private UUID userId() {
    TenantContext.Values values =
        TenantContext.current()
            .orElseThrow(
                () -> new IllegalStateException("assessment tool invoked without a user context"));
    if (values.userId() == null) {
      throw new IllegalStateException("assessment tool invoked without a user context");
    }
    return UUID.fromString(values.userId());
  }

  private JsonNode parse(String input) {
    try {
      JsonNode root = json.readTree(input);
      if (root == null || !root.isObject()) {
        throw new IllegalArgumentException("tool input must be a JSON object");
      }
      return root;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("tool input is not valid JSON", e);
    }
  }

  private List<ClaimInput> readClaims(@Nullable JsonNode node) {
    if (node == null || !node.isArray()) {
      throw new IllegalArgumentException("answers must be an array");
    }
    List<ClaimInput> claims = new ArrayList<>();
    for (JsonNode item : node) {
      claims.add(
          new ClaimInput(requireField(item, "questionText"), requireField(item, "answerText")));
    }
    return claims;
  }

  private List<EvidencePassage> readPassages(@Nullable JsonNode node) {
    if (node == null || !node.isArray()) {
      throw new IllegalArgumentException("passages must be an array");
    }
    List<EvidencePassage> passages = new ArrayList<>();
    for (JsonNode item : node) {
      passages.add(
          new EvidencePassage(
              requireInt(item, "pageNumber"),
              requireField(item, "text"),
              requireField(item, "textSha256")));
    }
    return passages;
  }

  private static @Nullable EvidenceRef readEvidence(@Nullable JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    return new EvidenceRef(
        optionalId(node, "recordId"),
        requireInt(node, "pageNumber"),
        requireField(node, "quote"),
        null);
  }

  private static FindingType readFindingType(@Nullable JsonNode node) {
    if (node == null || node.isNull()) {
      throw new IllegalArgumentException("findingType is required");
    }
    try {
      return FindingType.fromText(node.asText());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("findingType is not a known type", e);
    }
  }

  private static UUID requireId(JsonNode node, String field) {
    return UUID.fromString(requireField(node, field));
  }

  private static @Nullable UUID optionalId(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    return UUID.fromString(value.asText());
  }

  private static String requireText(JsonNode node, String field) {
    return requireField(node, field);
  }

  private static String requireField(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || value.asText().isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.asText();
  }

  private static int requireInt(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isNumber()) {
      throw new IllegalArgumentException(field + " must be a number");
    }
    return value.asInt();
  }
}
