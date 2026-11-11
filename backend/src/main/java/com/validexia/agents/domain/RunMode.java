package com.validexia.agents.domain;

/**
 * Autonomy mode of an agent run (founder's core vision, 2026-11-09). The mode never widens what a
 * tool's risk class allows a human to approve: sensitive writes always require a human decision, in
 * every mode (see {@link ModeGate}).
 */
public enum RunMode {
  ASSIST,
  APPROVAL,
  AUTONOMOUS
}
