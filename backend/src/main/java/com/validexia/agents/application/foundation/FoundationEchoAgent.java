package com.validexia.agents.application.foundation;

import com.validexia.agents.application.AgentPlanner;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The deterministic foundation agent. It exists so the whole engine — planning, risk gating,
 * approval, resumption, audit — can be exercised end to end without any model provider. Its plan is
 * fixed by construction: read the run context, record a note (a routine write), then perform a
 * sensitive action (which must always stop for a human, in every mode).
 */
@Component
public class FoundationEchoAgent implements AgentPlanner {

  public static final String AGENT_KEY = "foundation.echo";
  public static final String READ_TOOL = "foundation.read_context";
  public static final String WRITE_TOOL = "foundation.write_note";
  public static final String SENSITIVE_TOOL = "foundation.sensitive_action";

  @Override
  public String agentKey() {
    return AGENT_KEY;
  }

  @Override
  public List<PlannedTask> plan(String objective, String contextJson) {
    String quoted = FoundationJson.quote(objective);
    return List.of(
        new PlannedTask(READ_TOOL, "{\"objective\":" + quoted + "}"),
        new PlannedTask(
            WRITE_TOOL,
            "{\"note\":\"Echo note for objective: " + objective.replace("\"", "'") + "\"}"),
        new PlannedTask(
            SENSITIVE_TOOL, "{\"action\":\"mark_vendor_reviewed\",\"objective\":" + quoted + "}"));
  }
}
