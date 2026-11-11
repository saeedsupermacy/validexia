package com.validexia.agents.domain;

/** Lifecycle state of an agent run. Mirrors the CHECK constraint in migration V5. */
public enum RunState {
  PENDING,
  RUNNING,
  WAITING_APPROVAL,
  SUCCEEDED,
  FAILED,
  CANCELLED
}
