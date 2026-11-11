package com.validexia.agents.application.foundation;

import com.validexia.agents.application.DefinedTool;
import com.validexia.agents.application.ToolHandler;
import com.validexia.agents.domain.RiskClass;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The mock tools of the foundation slice — one per risk class, all deterministic, none touching the
 * database, the network, or anything outside this process. They are the controlled doubles that
 * real workspace tools (vendor reads, questionnaire sends, …) will later be registered alongside,
 * under the same registry and gating.
 */
@Configuration
public class FoundationTools {

  private final AtomicInteger noteSequence = new AtomicInteger();

  @Bean
  ToolHandler foundationReadContext(ToolInvocationLog log) {
    return new DefinedTool(
        FoundationEchoAgent.READ_TOOL,
        RiskClass.READ,
        input -> {
          log.record(FoundationEchoAgent.READ_TOOL, input);
          return "{\"read\":true,\"echo\":" + input + "}";
        });
  }

  @Bean
  ToolHandler foundationWriteNote(ToolInvocationLog log) {
    return new DefinedTool(
        FoundationEchoAgent.WRITE_TOOL,
        RiskClass.ROUTINE_WRITE,
        input -> {
          log.record(FoundationEchoAgent.WRITE_TOOL, input);
          return "{\"recorded\":true,\"noteId\":" + noteSequence.incrementAndGet() + "}";
        });
  }

  @Bean
  ToolHandler foundationSensitiveAction(ToolInvocationLog log) {
    return new DefinedTool(
        FoundationEchoAgent.SENSITIVE_TOOL,
        RiskClass.SENSITIVE_WRITE,
        input -> {
          log.record(FoundationEchoAgent.SENSITIVE_TOOL, input);
          return "{\"performed\":true}";
        });
  }
}
