package com.validexia.agents.application;

import com.validexia.agents.infrastructure.AgentsRepository;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The read/registration side of the agents API, kept separate from the execution engine: the
 * controller talks to this component (and to {@link AgentEngine} for execution), never to the
 * repository directly.
 */
@Component
public class AgentQueries {

  private final AgentsRepository repository;

  public AgentQueries(AgentsRepository repository) {
    this.repository = repository;
  }

  public UUID registerDefinition(
      UUID tenantId,
      UUID userId,
      String agentKey,
      String name,
      String description,
      String kind,
      String configJson) {
    return repository.registerDefinition(
        tenantId, userId, agentKey, name, description, kind, configJson);
  }

  public List<AgentData.Definition> listDefinitions(UUID tenantId, UUID userId) {
    return repository.listDefinitions(tenantId, userId).stream()
        .map(AgentData.Definition::from)
        .toList();
  }

  public List<AgentData.Run> listRuns(UUID tenantId, UUID userId) {
    return repository.listRuns(tenantId, userId).stream().map(AgentData.Run::from).toList();
  }

  public List<AgentData.Task> listTasks(UUID tenantId, UUID userId, UUID runId) {
    return repository.listTasks(tenantId, userId, runId).stream()
        .map(AgentData.Task::from)
        .toList();
  }

  public List<AgentData.Approval> listApprovals(
      UUID tenantId, UUID userId, @Nullable String state) {
    return repository.listApprovals(tenantId, userId, state).stream()
        .map(AgentData.Approval::from)
        .toList();
  }
}
