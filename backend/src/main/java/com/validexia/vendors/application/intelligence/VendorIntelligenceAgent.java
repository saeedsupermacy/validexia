package com.validexia.vendors.application.intelligence;

import com.validexia.agents.application.AgentPlanner;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@code vendor.intelligence} specialist: the platform's first real agent. Its plan is
 * deterministic and fixed by construction, like the foundation agent's — two READ steps:
 *
 * <ol>
 *   <li>{@code vendor.profile} retrieves the vendor's record under the run's tenant.
 *   <li>{@code vendor.intelligence.brief} generates a schema-validated, citation-checked brief from
 *       that retrieved record through the model gateway.
 * </ol>
 *
 * Both steps run in every autonomy mode without an approval stop because both are READ-class (the
 * ModeGate never gates reads). The vendor to brief arrives in the run's context as {@code
 * {"vendorId": "<uuid>"}} — the objective stays a human-readable sentence for the run record. A run
 * whose context carries no valid vendor id fails at plan time, before anything is persisted.
 */
@Component
public class VendorIntelligenceAgent implements AgentPlanner {

  public static final String AGENT_KEY = "vendor.intelligence";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String agentKey() {
    return AGENT_KEY;
  }

  @Override
  public List<PlannedTask> plan(String objective, String contextJson) {
    UUID vendorId = parseVendorId(contextJson);
    String input = "{\"vendorId\":\"" + vendorId + "\"}";
    return List.of(
        new PlannedTask(VendorProfileTool.TOOL_KEY, input),
        new PlannedTask(VendorIntelligenceBriefTool.TOOL_KEY, input));
  }

  private static UUID parseVendorId(String contextJson) {
    JsonNode context;
    try {
      context = MAPPER.readTree(contextJson == null ? "{}" : contextJson);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "vendor.intelligence context must be a JSON object carrying a vendorId", e);
    }
    String raw = context.path("vendorId").asText("");
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "vendor.intelligence runs require a context of {\"vendorId\": \"<uuid>\"}", e);
    }
  }
}
