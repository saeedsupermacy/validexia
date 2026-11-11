package com.validexia.agents.application;

import com.validexia.agents.domain.RiskClass;
import java.util.function.Function;

/** A {@link ToolHandler} defined by a lambda — the shape the foundation tools use. */
public record DefinedTool(String key, RiskClass riskClass, Function<String, String> body)
    implements ToolHandler {

  @Override
  public String execute(String inputJson) {
    return body.apply(inputJson);
  }
}
