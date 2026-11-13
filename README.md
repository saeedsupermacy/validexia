# Validexia

Validexia is a third-party risk management (TPRM) platform. It helps security and
compliance teams keep track of their vendors, the evidence those vendors provide,
the risks that follow from relying on them, and the work needed to reduce those
risks.

The platform is under active development. This README describes what is in the
repository today and, separately, where the product is going. The two are not
the same thing yet, and we keep them separate on purpose.

## What is in this repository

**Web interface** (repository root) — a React 19 + Vite + TypeScript + Tailwind
CSS application covering the main TPRM work areas: vendors, assessments,
evidence, remediation, monitoring, fourth parties, decisions, and reports. It
currently runs on demonstration data stored in the browser, marked with a
**Demo** badge in the top bar. Workspaces labelled "Simulated" (including the
AI Workbench) use pre-scripted responses, not live systems.

**Backend** (`backend/`) — a Java 21 + Spring Boot + PostgreSQL service that
provides the platform's foundation:

- Identity, sessions, and multi-tenant data isolation (PostgreSQL row-level
  security; the application role has no direct table access).
- A tamper-evident, hash-chained audit log.
- An agent execution engine: registered agent definitions, planned runs, a
  tool registry with risk classes, three autonomy modes (Assist, Approval,
  Autonomous), and human approval for sensitive actions. See
  `backend/docs/agents/ADR-001-agent-foundation.md`.

Connecting the interface to the backend, and building the domain data model
(vendors, evidence, assessments) on top of this foundation, is the work
currently in progress.

## Direction

Validexia is being built as an AI-assisted TPRM platform, with these long-term
goals:

- AI agents that handle routine TPRM work — researching vendors, checking
  evidence, flagging gaps — under human oversight, with approval required for
  consequential decisions.
- Continuous vendor intelligence: ongoing monitoring that surfaces emerging
  vendor risk early (Validexia Foresight).
- A Living Evidence Graph connecting vendors, services, dependencies,
  evidence, and risk signals, so a change in one place shows its effects
  elsewhere.
- A Risk Flight Simulator for exploring how a risk could spread across vendor
  relationships before it does.

These are design goals, not finished features. Nothing in the current build
performs live monitoring, AI analysis, or autonomous action against real
vendor data.

## Repository layout

- `src/` — the web interface (one feature folder per work area under
  `src/features/`)
- `backend/` — the backend service (see `backend/README.md` for setup,
  configuration, and the security model)
- `backend/docs/` — architecture decision records and engineering audits
- `SCORE_CONTRACT.md` — the risk-scoring semantics used by the interface

## Running the interface

Requires Node.js 20+.

```bash
npm install
npm run dev      # dev server
npm run build    # production build (outputs to dist/)
npx tsc --noEmit # type check
```

Copy `.env.example` to `.env` and fill in values. Never commit `.env`.

For the backend, see `backend/README.md`.

## Risk scoring

In the interface, a higher score means higher risk (0–100). Inherent and
residual risk are tracked separately, and residual risk is never shown above
inherent risk. The full semantics are in
[SCORE_CONTRACT.md](./SCORE_CONTRACT.md). How these scores will be produced and
reconciled with backend scoring is an open design decision; the backend does
not currently emit risk scores.
