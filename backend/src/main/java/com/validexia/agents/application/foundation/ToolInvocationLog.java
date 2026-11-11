package com.validexia.agents.application.foundation;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * In-memory record of foundation tool invocations. It gives tests (and local development) a way to
 * observe exactly what the mock tools were called with, in order. It holds no state worth
 * persisting: the durable record of an execution is the task row and the audit chain.
 */
@Component
public class ToolInvocationLog {

  private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> byTool =
      new ConcurrentHashMap<>();

  public void record(String toolKey, String inputJson) {
    byTool.computeIfAbsent(toolKey, k -> new CopyOnWriteArrayList<>()).add(inputJson);
  }

  public List<String> invocations(String toolKey) {
    return List.copyOf(byTool.getOrDefault(toolKey, new CopyOnWriteArrayList<>()));
  }

  public int count(String toolKey) {
    return invocations(toolKey).size();
  }

  public void clear() {
    byTool.clear();
  }
}
