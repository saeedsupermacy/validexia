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

## Vendor Intelligence Brief milestone (2026-11-13 — on branches, NOT on main, not yet PRs)

Built under the founder's parallel operating model on an integration baseline
(`integration/vendor-intelligence-brief` @ `8728a23` = PR #5 + PR #6 heads merged,
zero conflicts; parent PR histories untouched). Once PR #5/#6 merge, the feature
content is presented as ONE clean PR against main.

| Item | Evidence |
|------|----------|
| Backend `feat/vendor-intelligence-brief` (5 commits): `vendor.profile` READ tool + `vendor.intelligence` specialist (deterministic 2-step plan on the AgentEngine) + stub-provider evaluation harness (9 flow + 1 unconfigured + 14 unit tests: traceability, fabricated citation, value-snapshot mismatch, thin-record abstention, schema-invalid output, injection-as-data, unconfigured fail-closed, cross-tenant no-leak, provider failure) | CI green, run 43413627402 — 198 tests (174 baseline + 24 new, reconciled exactly) |
| Frontend `feat/vendors-frontend-wiring` (6 commits): Vendors module on the real API via a service seam (live rows carry NO scores anywhere — table, detail, CSV; demo fixtures only behind a labelled banner); Workbench gains ONE real intent (vendor.intelligence run, real states/failures/abstentions rendered) | `npm run build` passes; lint identical to baseline. No frontend CI workflow exists — build pass is human-verified |
| Integration fixes on the integration branch: `a429b74` (Workbench reads the brief from the brief task's output envelope, not run.result; unwraps JSON-string keyFacts), `fd6456b` (review D1: brief-shaped object must carry a brief signal) | Frontend build passes after each; integration CI green |
| Independent review (Workstream C, reviewer wrote none of the code) | All 8 non-negotiables HOLD (tenant isolation, no-fabrication path, injection, fail-closed, mode gate, frontend honesty, contract, regressions). Defects: D1 LOW frontend parser leniency (fixed, `fd6456b`); D2 LOW design boundary — **citation integrity is structural, not semantic**: field existence + value-snapshot equality are machine-enforced, prose truthfulness is prompt-constrained only; D3/D4 informational. Verdict: the capability is what it claims to be |

Engine seams changed (the only existing-code changes): `ToolInvocation` now carries
the run's tenant/user from the authenticated caller (tools refuse a null scope; all
reads still go through tenant-guarded functions), and the brief step reads its
predecessor's persisted task output (the engine freezes plan inputs at plan time) —
which makes the brief's input snapshot exact: the persisted result is a provenance
envelope {schemaVersion, agentKey, vendorId, inputRecordId, inputHash = SHA-256 of
the exact profile JSON, model {provider, model}, brief}. Citation validation checks
field existence, recorded-ness AND value-snapshot equality; `source_kind` enforced
`RECORD_FIELD`.

## Known limitations carried openly

- Agent engine: renewal-path outage longer than a lease can expire a claim under a
  running invocation (state stays fenced; re-invocation carries the stable
  idempotency key). Cross-process executors untested. Execution is synchronous
  in-request. No scheduler / system actor yet.
- Model layer: never called live (no API key yet). Gateway has no retries by design
  (the engine owns retry semantics).
- Vendors slice: backend on PR #6; frontend wiring + the brief exist on branches
  (above), not merged or deployed. Live-provider behaviour is UNVERIFIED — no
  Anthropic API key exists yet; all model evidence is the CI stub provider.
  Aliases deferred to the identity-resolution increment (accepted 2026-11-13:
  purely additive; capture-now was considered and recommended against).
- Docker-dependent verification (compose env passthrough, postgres:17 entrypoint)
  has never run: Docker is unavailable in the sandbox and not installed on the
  founder's PC. The roles-init runbook remains the pending route.
