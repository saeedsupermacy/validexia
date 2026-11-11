package com.validexia.agents.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The full mode × risk matrix. The load-bearing cells: sensitive writes always wait. */
class ModeGateTest {

  @Test
  void readsNeverRequireApproval() {
    for (RunMode mode : RunMode.values()) {
      assertFalse(ModeGate.requiresApproval(mode, RiskClass.READ), mode.name());
    }
  }

  @Test
  void routineWritesRequireApprovalExceptInAutonomous() {
    assertTrue(ModeGate.requiresApproval(RunMode.ASSIST, RiskClass.ROUTINE_WRITE));
    assertTrue(ModeGate.requiresApproval(RunMode.APPROVAL, RiskClass.ROUTINE_WRITE));
    assertFalse(ModeGate.requiresApproval(RunMode.AUTONOMOUS, RiskClass.ROUTINE_WRITE));
  }

  @Test
  void sensitiveWritesAlwaysRequireApproval() {
    for (RunMode mode : RunMode.values()) {
      assertTrue(ModeGate.requiresApproval(mode, RiskClass.SENSITIVE_WRITE), mode.name());
    }
  }
}
