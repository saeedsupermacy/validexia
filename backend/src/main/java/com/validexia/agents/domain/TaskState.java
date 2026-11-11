package com.validexia.agents.domain;

/** Lifecycle state of one task (step) inside an agent run. Mirrors the CHECK in migration V5. */
public enum TaskState {
  PENDING,
  RUNNING,
  WAITING_APPROVAL,
  SUCCEEDED,
  FAILED,
  SKIPPED,
  CANCELLED
}
