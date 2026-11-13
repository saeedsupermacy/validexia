# ADR-001: AI agent foundation — custom orchestration on Spring + PostgreSQL

Status: Accepted and implemented (first vertical slice). Date: 2026-11-11.

## Context

Validexia's core vision is an AI-native autonomous TPRM platform: multiple specialised agents,
a central orchestration layer, three autonomy modes (Assist / Approval / Autonomous), human
approval for sensitive actions, resumable execution, complete audit, and strict tenant
isolation. The existing backend provides identity, tenancy (PostgreSQL RLS + SECURITY
DEFINER functions, app role with zero direct grants) and a DB-enforced hash-chain audit trail.

## Decision

Build the orchestration ourselves on Spring + PostgreSQL, in a new top-level module
`com.validexia.agents`, with run/task state persisted in PostgreSQL under the same security
regime as the identity foundation (migration V5). No agent framework is introduced.

- **Spring AI** is a model-integration layer, not an orchestrator (no persistent run state, no
  approval workflow). It may be adopted later purely as the model-provider adapter behind an
  internal planner interface; its user-controlled tool-execution mode
  (`internalToolExecutionEnabled=false`) keeps our gate in charge of every tool call. Note its
  2.0 line is the Spring Boot 4 line; the 1.x line targets Boot 3 only.
- **LangGraph / langgraph4j** would store execution state outside our RLS + definer-function +
  audit regime (its own checkpoint tables; the Java port defaults to object-stream state) — a
  second, unaudited source of truth for the most sensitive state in the product.
- **Temporal** is the strongest durable-execution alternative and the documented escape hatch
  if runs ever wait >24h, execute across services, or outgrow in-process polling. Today it
  would add a separate cluster to operate and duplicate the audit story.
- **Camunda 8** is excluded on licence (self-managed production requires a paid licence).

Full comparison and evidence: RESEARCH-NOTE.md in the milestone's document set
(validexia-agent-foundation). The deciding argument is the security shape, not features: every
framework option keeps execution state in its own store, outside the controls this product's
audit and isolation guarantees rest on.

## The slice

- Migration **V5** (not V4 — V4 is reserved by the paper M1 Logto plan; on a fresh database
  Flyway applies versions in order, so M1's V4 slots in before V5 when authored):
  `agent_definitions`, `agent_runs`, `agent_tasks`, `agent_approvals`, owned by
  `validexia_agents_owner`, RLS enabled, zero direct grants for `validexia_app`, all access via
  SECURITY DEFINER functions with an explicit tenant guard and compare-and-swap transitions.
- `ModeGate` is the single decision point for autonomy: READ executes in all modes;
  ROUTINE_WRITE executes automatically only in AUTONOMOUS; SENSITIVE_WRITE always waits for a
  human decision, in every mode, with no configuration that changes it.
- Tools and agent planners are registered in code (Spring beans). A database definition row
  alone can never make an agent run, and no function or endpoint lets an agent change a
  definition, a tool, or a risk class.
- The engine (`AgentEngine`) executes tasks in order, parks approval-required tasks
  (task → WAITING_APPROVAL, run → WAITING_APPROVAL), resumes after decisions, requeues tasks
  left RUNNING by a dead executor, and never repeats SUCCEEDED tasks. Run creation is
  idempotent per (tenant, idempotency key).
- Audit: run created / task executed / approval requested / approval decided / run succeeded /
  run failed are appended to the existing per-tenant hash chain.
- A deterministic foundation agent (`foundation.echo`) and three mock tools (one per risk
  class) exercise the whole machinery without any model provider or external service.

## Boundaries respected

No authentication or M1-related files touched; no frontend changes; no new dependencies; no
deployment; tenant isolation, RLS and audit controls unchanged for existing modules; all 17
frontend workspaces and the full product vision preserved.

## Known limitations (deliberate, for this slice)

- Audit events record the run's initiating user as actor; a distinct "AI operator" actor type
  in the chain is future work.
- Definition registration via the API is available to any authenticated tenant member;
  restricting it to owner/admin roles is future work.
- The engine executes synchronously inside the creating/deciding request; a background worker
  with polling is the next increment if long-running plans arrive.
- `decideApproval` locates the approval through the tenant's approval list (foundation scale);
  a dedicated by-id definer function can replace it without changing the API.
