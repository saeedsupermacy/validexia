# IMPLEMENTATION LOG — what was built, merged, and proven

Append-only. "Proven" means CI against real PostgreSQL 17 (Testcontainers) unless
stated otherwise. Local JUnit cannot run in the build sandbox (network middlebox);
GitHub CI is the test venue.

## Merged to main

| Item | Evidence |
|------|----------|
| M02-1 backend foundation (identity, tenancy, audit; V1–V3) | CI green 63/63 (2026-11-10) |
| Milestone 1: AI Agent Foundation (V5, ModeGate, engine, REST) | Merged `6190b9d`, CI 81/81 |
| V6 fail-closed tenant guards + FORCE RLS (PR #2) | Merged `ae882da`, CI 85/85 |
| PR #4 repository hygiene (gitignore, untracked node_modules, env-var secrets) | Merged; main `2b91812`. Roles-init script separately verified on a real PostgreSQL 16.15 cluster: 35/35 checks |
| PR #3 agent-engine concurrency safety (V7–V11: leases/fencing, atomic plans, fenced heartbeat, V11 lock-then-check) | **Merged by the founder** as `c66fae7` (2026-11-12), CI 116/116, count reconciled exactly (116 `@Test` methods) |

## Open PRs (CI green, awaiting founder review)

| PR | Content | Evidence |
|----|---------|----------|
| #5 `feat/ai-foundation` (head `0b1698c`) | AI model layer: provider abstraction, Anthropic client with forced-tool structured output, fail-closed gateway, schema validator; + platform design doc | CI green, 153 tests. Two build fix rounds recorded on the PR (bean wiring; Jackson 2→3). Independent review 2026-11-13: merge-ready-with-fixes (1 major: HTTP status classification for the retry contract; 6 minors) — correction pass completed (`0b1698c`, 153 tests, CI green) and independently re-reviewed: merge-ready; 4 minor residual observations recorded on the PR |
| #6 `feat/vendors-data-slice` (head `f3dea14`) | Vendors data slice: V12 + repository/service + REST `/api/v1/vendors`, 13 tests | CI green, 137 tests on branch. Two build fix rounds recorded on the PR (grant-signature arity; api→infrastructure architecture rule). Independent review 2026-11-13: merge-ready-with-fixes (2 majors: update-path 500s on client errors; create-path violation misreporting; 7 minors) — correction pass completed (`fda1eb5` + test repair `0fca719` + membership-ordering fix `f3dea14`, 137 tests, CI green) and independently re-reviewed: all findings resolved |
| #7 `docs/project-continuity` | This continuity document set | Docs only. Independent audit 2026-11-13: accurate and substantially complete; 9 precision findings corrected in the founder-authorised fix pass |

## Known limitations carried openly

- Agent engine: renewal-path outage longer than a lease can expire a claim under a
  running invocation (state stays fenced; re-invocation carries the stable
  idempotency key). Cross-process executors untested. Execution is synchronous
  in-request. No scheduler / system actor yet.
- Model layer: never called live (no API key yet). Gateway has no retries by design
  (the engine owns retry semantics).
- Vendors slice: backend only — the frontend module still reads fixtures until the
  wiring step lands. Aliases deferred to the identity-resolution increment.
- Docker-dependent verification (compose env passthrough, postgres:17 entrypoint)
  has never run: Docker is unavailable in the sandbox and not installed on the
  founder's PC. The roles-init runbook remains the pending route.
