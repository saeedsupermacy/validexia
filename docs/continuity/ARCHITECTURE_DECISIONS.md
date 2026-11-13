# ARCHITECTURE DECISIONS — the register

Summary register only. Full texts live in the linked sources; this file records what
was decided, by whom, and what remains open. New decisions are appended, never edited
in place — a reversed decision gets a new entry naming what it supersedes.

## Settled

| # | Decision | Source |
|---|----------|--------|
| D1 | Stack: Java 21 + Spring Boot 4.1.2 + PostgreSQL; backend from scratch; React 19 frontend preserved; PHP/LT-VRM inspect-only, never extended. | Founder, 2026-11-09 |
| D2 | The merged AgentEngine is the ONLY orchestrator. Specialist capabilities are agents, deterministic services, scheduled jobs or tools on it — never a second engine. | Founder, 2026-11-13 |
| D3 | Tenant isolation house pattern: owner roles + SECURITY DEFINER functions + fail-closed tenant guard (V6 form) + FORCE RLS. Never weakened. | M02-1 / PR #2 |
| D4 | ModeGate: READ ungated; ROUTINE_WRITE auto only in AUTONOMOUS; SENSITIVE_WRITE always human-approved. AI never modifies its own permissions. | ADR-001 |
| D5 | **External write tools PROHIBITED** until the specific provider's idempotency/dedup (keyed on the task's stable idempotency key) is independently verified and tested. Concurrency safety does not prove exactly-once against real providers. | Founder gate, PR #3 |
| D6 | Risk scores are deterministic code. LLMs never invent scores or probabilities. Numerical forecasting requires defined targets, baselines, historical validation and calibration — qualitative outlooks until then. | Founder, 2026-11-13 |
| D7 | Model strategy: Anthropic Claude primary (no-training contract term, ZDR by agreement), OpenAI secondary. Gemini Developer API excluded from customer documents (retention, no ZDR path). API keys via environment/Secure Vault only, never in code or logs. | Model evaluation, 2026-11-13 |
| D8 | Documents and external content are DATA, never instructions (prompt-injection boundary). Evidence provenance is a schema requirement, not a UI courtesy. | AI foundation design |
| D9 | External signal data: consume from the source of record under its own licence. Sanctions from official lists directly — **OpenSanctions data is CC BY-NC and must not be used commercially**. GLEIF (CC0), EDGAR, KEV/NVD/EPSS, GDELT Project are the licensed-safe foundation. | Data-source assessment, 2026-11-13 |
| D10 | Exposure states (confirmed / unconfirmed / ruled-out) are data-model states on every externally derived relationship. A detected event is never presented as confirmed exposure. | Foresight design, 2026-11-13 |
| D11 | Layering: controllers (`..api..`) never depend on `..infrastructure..`; application services sit between (enforced by ArchitectureTest). | Codebase rule, PR #6 |
| D12 | Flyway 12.4.0 (never 13.x). Migrations are immutable once merged; corrections are new forward migrations. | M02-1 |
| D13 | jqwik is not used (its licence carries an anti-AI usage clause). | Engineering mission, 2026-11-09 |

## Open — founder decision required

| # | Question | Blocks |
|---|----------|--------|
| O1 | **Score direction**: memo prescribes converting backend scores for the frontend; ADR rev.5/6 forbids conversion; code follows the ADR. Which governs? | Any score wiring; trajectory semantics |
| O2 | **Logto M1 authorisation** + spec decisions D2/D4/D5/D7/D8, and D12: Logto Pro ($24/mo) vs redesign for production API resources. | Identity migration (M1); nothing else |
| O3 | **Anthropic API key** via Secure Vault. | First live model call; live evaluation run |
| O4 | ZDR agreements with model providers (founder action with the vendors). | Sending real customer documents to any model |

Detail: `backend/docs/agents/ADR-001-agent-foundation.md`,
`backend/docs/agents/AGENT-ENGINE-CONCURRENCY-AUDIT.md`, and (on the PR #5 branch)
`backend/docs/ai/AI-FOUNDATION-DESIGN.md`.
