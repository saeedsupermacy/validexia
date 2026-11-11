package com.validexia.agents.application;

import com.validexia.agents.domain.RiskClass;

/**
 * A tool an agent may call. Tools are registered in code (Spring beans), never in the database and
 * never by agents: the registry is the permission boundary, and a tool's {@link RiskClass} is fixed
 * at registration time. Inputs and outputs are JSON strings, matching how the persistence layer
 * stores them (jsonb columns carried as text).
 */
public interface ToolHandler {

  String key();

  RiskClass riskClass();

  /** Executes the tool. Implementations must be safe to call again with the same input. */
  String execute(String inputJson);
}
