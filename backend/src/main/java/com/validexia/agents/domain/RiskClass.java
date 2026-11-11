package com.validexia.agents.domain;

/**
 * Risk class of a registered tool. Fixed when the tool is registered in code; agents cannot change
 * a tool's class, and no database function can either.
 */
public enum RiskClass {
  READ,
  ROUTINE_WRITE,
  SENSITIVE_WRITE
}
