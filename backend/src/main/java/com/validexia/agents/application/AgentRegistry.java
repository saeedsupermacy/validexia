package com.validexia.agents.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Every {@link AgentPlanner} bean, keyed by agent key. A tenant-visible agent definition (stored
 * via migration V5's functions) becomes executable only when a planner with the same key is
 * registered here — a database row alone can never make an agent run.
 */
@Component
public class AgentRegistry {

  private final Map<String, AgentPlanner> planners = new LinkedHashMap<>();

  public AgentRegistry(List<AgentPlanner> planners) {
    for (AgentPlanner planner : planners) {
      AgentPlanner previous = this.planners.putIfAbsent(planner.agentKey(), planner);
      if (previous != null) {
        throw new IllegalStateException("Duplicate agent key registered: " + planner.agentKey());
      }
    }
  }

  public AgentPlanner require(String agentKey) {
    AgentPlanner planner = planners.get(agentKey);
    if (planner == null) {
      throw new IllegalArgumentException("No agent registered with key: " + agentKey);
    }
    return planner;
  }
}
