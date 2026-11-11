package com.validexia.agents.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.validexia.agents.application.foundation.FoundationEchoAgent;
import com.validexia.agents.domain.RiskClass;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRegistryTest {

  @Test
  void echoAgentPlansOneTaskPerRiskClassInOrder() {
    FoundationEchoAgent agent = new FoundationEchoAgent();
    List<AgentPlanner.PlannedTask> plan = agent.plan("review Acme", "{}");
    assertEquals(3, plan.size());
    assertEquals(FoundationEchoAgent.READ_TOOL, plan.get(0).toolKey());
    assertEquals(FoundationEchoAgent.WRITE_TOOL, plan.get(1).toolKey());
    assertEquals(FoundationEchoAgent.SENSITIVE_TOOL, plan.get(2).toolKey());
  }

  @Test
  void echoAgentIsDeterministic() {
    FoundationEchoAgent agent = new FoundationEchoAgent();
    assertEquals(agent.plan("x", "{}"), agent.plan("x", "{}"));
  }

  @Test
  void registryRejectsUnknownAgent() {
    AgentRegistry registry = new AgentRegistry(List.of(new FoundationEchoAgent()));
    assertThrows(IllegalArgumentException.class, () -> registry.require("nope"));
  }

  @Test
  void registryRejectsDuplicateKeys() {
    assertThrows(
        IllegalStateException.class,
        () -> new AgentRegistry(List.of(new FoundationEchoAgent(), new FoundationEchoAgent())));
  }

  @Test
  void toolRegistryRejectsUnknownToolAndDuplicates() {
    ToolHandler read = new DefinedTool("t.read", RiskClass.READ, in -> "{}");
    ToolRegistry registry = new ToolRegistry(List.of(read));
    assertEquals(read, registry.require("t.read"));
    assertThrows(IllegalArgumentException.class, () -> registry.require("t.missing"));
    assertThrows(IllegalStateException.class, () -> new ToolRegistry(List.of(read, read)));
  }
}
