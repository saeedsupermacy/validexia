package com.validexia.ai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AiPropertiesTest {

  private AiProperties withTimeout(int timeoutSeconds) {
    return new AiProperties(
        true, "anthropic", "k", "m", "https://api.anthropic.test", timeoutSeconds);
  }

  @Test
  void aTimeoutBelowOneSecondIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> withTimeout(0));
  }

  @Test
  void aTimeoutAboveTenMinutesIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> withTimeout(601));
    assertThrows(IllegalArgumentException.class, () -> withTimeout(86_400));
  }

  @Test
  void theBoundaryTimeoutsAreAccepted() {
    assertDoesNotThrow(() -> withTimeout(1));
    assertDoesNotThrow(() -> withTimeout(600));
  }
}
