package com.validexia.agents.domain;

/**
 * The single place that decides whether a task may execute without a human decision, given the
 * run's autonomy mode and the tool's risk class.
 *
 * <ul>
 *   <li>READ tools execute in every mode.
 *   <li>ROUTINE_WRITE executes automatically only in AUTONOMOUS mode; in ASSIST and APPROVAL it
 *       waits for a human decision (in ASSIST the prepared, unexecuted action IS the recommendation
 *       the human reviews).
 *   <li>SENSITIVE_WRITE always waits for a human decision, in every mode. There is no configuration
 *       that changes this: sensitive actions are never autonomous.
 * </ul>
 */
public final class ModeGate {

  private ModeGate() {}

  public static boolean requiresApproval(RunMode mode, RiskClass risk) {
    return switch (risk) {
      case READ -> false;
      case ROUTINE_WRITE -> mode != RunMode.AUTONOMOUS;
      case SENSITIVE_WRITE -> true;
    };
  }
}
