# PROJECT STATE — where things actually stand

Last updated: 2026-11-13. Update this file whenever a milestone lands or a PR merges.

## Repository

- GitHub: `saeedsupermacy/validexia`. Monorepo: React frontend at root, Java backend
  in `backend/`. **Main = `c66fae7`** (PR #4 hygiene + PR #3 agent concurrency safety).
- Live frontend reference: https://validexia-g6q9.vercel.app/ (prototype; fixture data).

## What is real today (on main)

- **Backend** (Java 21, Spring Boot 4.1.2, PostgreSQL): identity/directory,
  sessions, webhooks (migrations V1–V3); hash-chained audit log (V2); the **agent
  foundation** (V5–V11): tenant-isolated agent definitions/runs/tasks/approvals,
  ModeGate approvals, leases/fencing/heartbeat concurrency safety, REST at
  `/api/v1/agents`. One deterministic agent (`foundation.echo`) and three mock tools.
- **Tenant isolation pattern** (the house standard): NOLOGIN owner roles hold tables,
  the app role has no direct table access, all access via SECURITY DEFINER functions
  with the V6 fail-closed tenant guard, FORCE ROW LEVEL SECURITY on the guarded tables (agent tables, audit events, and each domain slice as it lands; the identity directory tables predate the pattern and have no RLS).

## What is real but NOT yet on main (open PRs awaiting founder review; #5 and #6 are CI-green)

- **PR #5** (`feat/ai-foundation`, head `0b1698c`): AI model layer — provider
  abstraction, real Anthropic Messages client (structured output via a forced tool
  call), fail-closed gateway, schema validator; 20 tests; CI green (136). Also carries
  `backend/docs/ai/AI-FOUNDATION-DESIGN.md`, the platform design. **No live model call
  has ever been made** — the founder's Anthropic API key (via Secure Vault) is pending.
- **PR #7** (`docs/project-continuity`): this continuity document set itself.
- **PR #6** (`feat/vendors-data-slice`, head `f3dea14`): vendors data slice — V12
  migration, repository/service, REST at `/api/v1/vendors`; 13 tests; CI green (129 on
  that branch). First domain table in the platform.

## What is NOT real (never claim otherwise)

- The **frontend** (18 modules) runs entirely on TypeScript fixtures and
  localStorage. Its AI Workbench answers are three hard-coded scripted responses.
  Uploads never read file bytes. Zero modules have backend support until PR #6 merges
  and the Vendors module is wired to it (next step).
- No domain tables exist beyond vendors-in-PR: no evidence/documents, assessments,
  findings, remediation, or dependency graph yet.
- The engine has **no scheduler and no system-initiated runs** — everything proactive
  (monitoring, Foresight) is gated on that prerequisite (roadmap step 9).
- No external write tool exists, by design (see ARCHITECTURE_DECISIONS.md, the gate).

## Paper-only work (do not code without founder authorisation)

- **Logto identity migration (M1)**: spec v2.8 complete, M0 verified live; M1 is NOT
  authorised. Material open point: API resources require Logto Pro in production
  (Logto-spec decision D12 gates M3 only).
- **Score direction**: frontend score semantics vs backend LT-VRM semantics conflict;
  the conversion memo and the ADR disagree. Founder decision pending; no score wiring
  until then. LLM involvement in scores is prohibited regardless.
