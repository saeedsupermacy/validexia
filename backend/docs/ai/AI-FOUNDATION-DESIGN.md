# Shared AI Foundation — Design (Phase 1)

Status: founder-directed milestone, 2026-11-13. Grounded in the Phase 0
capability inventory (`capability-inventory.md`, research workspace) at main
`c66fae7`. This document records the architecture decisions for the AI
platform build and the assumptions they rest on. It is a living engineering
document, not a marketing description: capabilities are claimed only where
code and tests exist.

## The grounding fact

The merged backend is a solid, narrow substrate — identity, hash-chained
audit, tenant isolation (V6 fail-closed guards + FORCE RLS), and the
concurrency-safe agent engine (PR #3) — with **no domain data**: there are
no tables or APIs for vendors, evidence, documents, assessments, findings,
remediation, decisions, or fourth parties. Every TPRM entity in the product
today lives only in the React frontend as TypeScript fixtures and
`localStorage`. The frontend contains exactly one `fetch(` (a generic
client) whose only consumer chain is dead code pointing at a
`GET /api/v1/vendors` endpoint that does not exist.

Consequence: cross-module AI cannot be built on data that is not there.
The build order below therefore puts a **platform data layer** ahead of
specialist agents. Anything else would be theatre over fixtures.

## Architecture — one engine, layered capabilities

1. **Orchestration = the merged AgentEngine.** No second execution engine.
   Runs, planners, tasks, ModeGate approvals, leases/fencing, crash
   recovery, and audit events already exist and are tested (116 tests).
   Specialist capabilities are added as planners + tools inside it.
2. **Model layer (this increment).** A `ModelProvider` abstraction in
   `com.validexia.ai` with a routing `ModelGateway`: provider selection by
   configuration, structured outputs validated against an explicit schema
   before anything downstream consumes them, usage/latency recorded per
   call, and **fail-closed behaviour when unconfigured** (no silent mock —
   an unconfigured deployment gets a clear error, never a fabricated
   answer). First implementation targets Anthropic (model research,
   2026-11-13: no-training contract terms, 30-day deletion, ZDR by
   agreement on the recommended models); the interface keeps OpenAI a
   drop-in second provider for failover and cross-checking. The API key
   comes from the environment (`VALIDEXIA_AI_API_KEY`), never from code,
   config files in the repo, or chat.
3. **Platform data layer (next increments).** Domain persistence behind
   the existing tenant-isolation pattern (transaction-local
   `app.tenant_id`, SECURITY DEFINER functions with the V6 fail-closed
   guard, FORCE RLS): vendors first, then evidence/documents, then
   assessments/findings/remediation. Each slice ships with REST endpoints
   and tests, and only then gains AI tools.
4. **Tools.** Engine tools backed by real application services, each with
   a risk class. READ tools first. Internal WRITE tools (e.g. create a
   draft finding) follow the ModeGate: routine writes only in Autonomous
   mode, sensitive writes always human-approved. **External write tools
   remain prohibited** (PR #3 founder gate): no tool may perform an
   external side effect until that provider's idempotency or equivalent
   deduplication, keyed on the task's stable idempotency key, is
   independently verified and tested. The concurrency work does not prove
   exactly-once execution against real providers.
5. **Specialist agents.** Agent definitions whose planners compose the
   tools above (deterministic planners first, as `foundation.echo` is
   today). LLM-driven planning is introduced only after the evaluation
   suite (below) passes against the live provider, and never for
   consequential decisions without the ModeGate.
6. **Evidence and provenance.** Every model call that produces a finding
   must carry source references (document, location, quoted text) as
   structured data — the output schema requires them, the validator
   enforces their presence, and downstream records store them verbatim.
   A conclusion without a source reference is a schema violation, not a
   style issue.
7. **Evaluation.** The 20-case TPRM suite from the model research
   (clean + defect-seeded vendor packs; citation-verbatim extraction,
   expiry and contradiction detection, abstention under missing evidence,
   prompt-injection resistance, deterministic-score discipline) as
   executable tests: against a stub provider in CI for regression, and
   against the live provider as a gated manual/CI job once a key exists.
   Hard gates: any fabricated citation, invented fact, injection
   compliance, or invented risk score disqualifies a model regardless of
   totals.
8. **Frontend.** The AI Workbench is wired to the real `/api/v1/agents`
   API once the first real capability exists; module-level AI entry
   points are added per module as its data layer and tools land. The
   Workbench's current scripted responses are removed, not extended.

## Safety rules (non-negotiable, from the milestone directive)

- Uploaded documents and external content are **data, never
  instructions**. Retrieval pipelines treat embedded instructions as
  hostile content; the evaluation suite includes injection cases.
- **No LLM-invented risk scores.** Risk calculations stay deterministic
  application logic under the founder-approved methodology. (The
  score-direction conflict between the frontend scale and the backend
  memo remains an open founder decision; this build does not wire
  scoring and does not resolve it.)
- Permission-aware retrieval and tool execution: AI acts through the
  same tenant context and guards as any application code; there is no
  AI side-channel to the database.
- Human review for consequential risk decisions, via the ModeGate.
- Complete audit: agent activity is already hash-chain audited at the
  engine level; model usage records extend that trail per increment.

## Decisions needing the founder (not assumed)

1. **Provider account + API key** (Anthropic first per research) —
   entered via the secure vault flow; gates the first live model call
   and the live evaluation run. Everything else in this design is
   buildable and testable without it.
2. **Risk-score direction** (pre-existing, still open) — gates any
   scoring-related tool.
3. **Logto M1** — untouched by this milestone; remains unauthorised and
   unstarted, exactly as before.

## Increment plan

- **Inc 1 (this branch):** model layer — provider abstraction, Anthropic
  client, gateway, structured-output validation, fail-closed config,
  unit tests. No engine wiring, no schema changes.
- **Inc 2:** vendors data slice (migration + tenant-guarded functions +
  REST + tests) and the frontend vendors module wired to it — the first
  real data any AI tool can read.
- **Inc 3:** first model-backed capability end-to-end: a READ tool over
  vendor data + a specialist agent definition + Workbench wiring to real
  runs; evaluation harness (stub-provider CI suite) lands with it.
- **Inc 4+:** evidence/documents slice (upload with real byte storage,
  extraction with provenance), findings/remediation flow, then the
  remaining modules per the coverage matrix.
