package com.validexia.agents.application;

import java.util.List;

/**
 * An agent's planning half: turns an objective into an ordered list of tool calls. Deterministic
 * planners (the foundation) return the same plan for the same input; a future LLM-backed planner
 * implements this same interface behind the same engine, so the execution, approval and audit
 * machinery never changes when a real model arrives.
 */
public interface AgentPlanner {

  /** One planned step: which tool to call, with what JSON input. */
  record PlannedTask(String toolKey, String inputJson) {}

  String agentKey();

  List<PlannedTask> plan(String objective, String contextJson);
}
