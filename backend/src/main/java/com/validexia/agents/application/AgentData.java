package com.validexia.agents.application;

import com.validexia.agents.infrastructure.AgentsRepository.AgentApprovalRow;
import com.validexia.agents.infrastructure.AgentsRepository.AgentDefinitionRow;
import com.validexia.agents.infrastructure.AgentsRepository.AgentRunRow;
import com.validexia.agents.infrastructure.AgentsRepository.AgentTaskRow;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Application-layer views of agent data. The API layer depends on these records only — never on
 * repository row types — so the layering rule (controller → application service → repository) holds
 * for the agents module exactly as it does for identity.
 */
public final class AgentData {

  private AgentData() {}

  public record Definition(
      UUID id, String agentKey, String name, String description, String kind, String status) {

    public static Definition from(AgentDefinitionRow row) {
      return new Definition(
          row.id(), row.agentKey(), row.name(), row.description(), row.kind(), row.status());
    }
  }

  public record Run(
      UUID id,
      String agentKey,
      String objective,
      String mode,
      String state,
      @Nullable String resultJson,
      @Nullable String error) {

    public static Run from(AgentRunRow row) {
      return new Run(
          row.id(),
          row.agentKey(),
          row.objective(),
          row.mode(),
          row.state(),
          row.resultJson(),
          row.error());
    }
  }

  public record Task(
      UUID id,
      int seq,
      String toolKey,
      String riskClass,
      String state,
      @Nullable String outputJson,
      @Nullable String error,
      int attempts) {

    public static Task from(AgentTaskRow row) {
      return new Task(
          row.id(),
          row.seq(),
          row.toolKey(),
          row.riskClass(),
          row.state(),
          row.outputJson(),
          row.error(),
          row.attempts());
    }
  }

  public record Approval(
      UUID id,
      UUID runId,
      UUID taskId,
      String state,
      String reason,
      @Nullable String decisionReason) {

    public static Approval from(AgentApprovalRow row) {
      return new Approval(
          row.id(), row.runId(), row.taskId(), row.state(), row.reason(), row.decisionReason());
    }
  }
}
