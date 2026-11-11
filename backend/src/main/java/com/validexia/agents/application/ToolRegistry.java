package com.validexia.agents.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The tool registry: every {@link ToolHandler} bean in the application, keyed by tool key. The
 * engine resolves tools only through here, so an agent can never invoke anything that was not
 * explicitly registered with a risk class.
 */
@Component
public class ToolRegistry {

  private final Map<String, ToolHandler> tools = new LinkedHashMap<>();

  public ToolRegistry(List<ToolHandler> handlers) {
    for (ToolHandler handler : handlers) {
      ToolHandler previous = tools.putIfAbsent(handler.key(), handler);
      if (previous != null) {
        throw new IllegalStateException("Duplicate tool key registered: " + handler.key());
      }
    }
  }

  public ToolHandler require(String key) {
    ToolHandler handler = tools.get(key);
    if (handler == null) {
      throw new IllegalArgumentException("No tool registered with key: " + key);
    }
    return handler;
  }

  public Collection<ToolHandler> all() {
    return List.copyOf(tools.values());
  }
}
