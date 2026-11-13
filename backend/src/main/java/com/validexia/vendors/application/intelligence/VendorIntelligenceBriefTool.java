package com.validexia.vendors.application.intelligence;

import com.validexia.agents.application.ToolHandler;
import com.validexia.agents.application.ToolInvocation;
import com.validexia.agents.domain.RiskClass;
import com.validexia.agents.infrastructure.AgentsRepository;
import com.validexia.agents.infrastructure.AgentsRepository.AgentTaskRow;
import com.validexia.ai.ModelGateway;
import com.validexia.ai.ModelProviderException;
import com.validexia.ai.ModelRequest;
import com.validexia.ai.ModelResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code vendor.intelligence.brief} tool: step 2 of the {@code vendor.intelligence} agent. It
 * generates the brief from <b>the persisted output of this run's {@code vendor.profile} step</b> —
 * the exact retrieved-record snapshot step 1 produced — never from a fresh read, so the brief, its
 * citations and its input hash all describe the same bytes. (The engine's plan model freezes task
 * inputs at plan time and has no inter-task dataflow; reading the predecessor's persisted result
 * through the tenant-guarded repository is how a later step consumes an earlier step's output
 * without widening that model.)
 *
 * <p>The model call goes through {@link ModelGateway} with the brief's {@link
 * VendorBriefSupport#SCHEMA}: unconfigured deployments fail closed there, schema-invalid answers
 * fail there, and answers whose citations do not trace to the record fail here ({@link
 * VendorBriefSupport#validateBrief}). Nothing on any failure path fabricates a brief.
 *
 * <p>The persisted result is a provenance envelope around the brief, not rendered prose: {@code
 * schemaVersion}, the record id, a SHA-256 hash of the exact profile JSON the brief was generated
 * from (so a future verification step can re-check the brief against its inputs), the producing
 * provider and model, and the brief itself with its structured facts, data gaps and abstention
 * outcome as queryable fields.
 *
 * <p>Risk class READ: the tool performs no write — its only external effect is a model call, and
 * its output exists only as this task's result.
 */
@Component
public class VendorIntelligenceBriefTool implements ToolHandler {

  public static final String TOOL_KEY = "vendor.intelligence.brief";
  public static final int SCHEMA_VERSION = 1;

  private final AgentsRepository agents;
  private final ModelGateway gateway;
  private final ObjectMapper mapper;

  public VendorIntelligenceBriefTool(
      AgentsRepository agents, ModelGateway gateway, ObjectMapper mapper) {
    this.agents = agents;
    this.gateway = gateway;
    this.mapper = mapper;
  }

  @Override
  public String key() {
    return TOOL_KEY;
  }

  @Override
  public RiskClass riskClass() {
    return RiskClass.READ;
  }

  @Override
  public String execute(ToolInvocation invocation, String inputJson) {
    if (invocation.tenantId() == null || invocation.userId() == null) {
      throw new IllegalStateException(
          "vendor.intelligence.brief invoked without the run's tenant scope; refusing to proceed");
    }
    String profileJson = profileStepOutput(invocation);
    JsonNode profile = mapper.readTree(profileJson);

    ModelResponse response =
        gateway.execute(
            ModelRequest.structured(
                "vendor.intelligence.brief",
                VendorBriefSupport.SYSTEM_PROMPT,
                VendorBriefSupport.userPrompt(profileJson),
                VendorBriefSupport.SCHEMA));
    JsonNode brief = response.structured();
    if (brief == null) {
      // The gateway already guarantees this for schema requests; the check keeps the tool's own
      // contract honest if it is ever composed differently.
      throw new ModelProviderException(
          ModelProviderException.Kind.INVALID_OUTPUT,
          "the model call returned no structured brief");
    }
    VendorBriefSupport.validateBrief(mapper, brief, profile);

    ObjectNode envelope = mapper.createObjectNode();
    envelope.put("schemaVersion", SCHEMA_VERSION);
    envelope.put("agentKey", VendorIntelligenceAgent.AGENT_KEY);
    envelope.put("vendorId", profile.path("id").asText(""));
    envelope.put("inputRecordId", profile.path("id").asText(""));
    envelope.put("inputHash", sha256Hex(profileJson));
    ObjectNode model = envelope.putObject("model");
    model.put("provider", response.provider());
    model.put("model", response.model());
    envelope.set("brief", brief);
    return mapper.writeValueAsString(envelope);
  }

  @Override
  public String execute(String inputJson) {
    throw new IllegalStateException(
        "vendor.intelligence.brief requires an invocation context (the run's tenant scope)");
  }

  /**
   * Finds this run's completed {@code vendor.profile} step — the one preceding this task — and
   * returns its persisted output. Fails cleanly when the run has none: the brief is defined over
   * the retrieved snapshot, and inventing a substitute input is not an option.
   */
  private String profileStepOutput(ToolInvocation invocation) {
    var tasks = agents.listTasks(invocation.tenantId(), invocation.userId(), invocation.runId());
    int ownSeq =
        tasks.stream()
            .filter(t -> t.id().equals(invocation.taskId()))
            .mapToInt(AgentTaskRow::seq)
            .findFirst()
            .orElse(Integer.MAX_VALUE);
    return tasks.stream()
        .filter(t -> VendorProfileTool.TOOL_KEY.equals(t.toolKey()))
        .filter(t -> "SUCCEEDED".equals(t.state()))
        .filter(t -> t.seq() < ownSeq)
        .max(java.util.Comparator.comparingInt(AgentTaskRow::seq))
        .map(AgentTaskRow::outputJson)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "vendor.intelligence.brief found no completed vendor.profile step in run "
                        + invocation.runId()
                        + "; the brief is generated from that step's retrieved record"));
  }

  private static String sha256Hex(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
