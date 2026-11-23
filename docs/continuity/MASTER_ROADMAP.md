# MASTER ROADMAP — the straight path

Chain: NORTH STAR → this roadmap → current milestone → current task. If a task cannot
be traced to a step here, stop and ask why it exists.

Status key: ✅ done · 🔵 in review · ⬜ next · 🔒 gated (condition named).

1. ✅ **Agent foundation** — orchestration engine, ModeGate, approvals, audit
   (merged; concurrency safety V7–V11 merged as PR #3).
2. ✅ **Repository hygiene** — merged as PR #4.
3. 🔵 **AI model layer** — PR #5, CI green, awaiting founder review/merge.
4. 🔵 **Vendors data slice** — PR #6, CI green, awaiting founder review/merge.
   Includes wiring the frontend Vendors module to the real API (the remaining half
   of this step, done immediately after merge).
5. ⬜ **First model-backed capability end-to-end** — a READ tool over vendor data, a
   specialist agent definition, the Workbench running on real engine runs, and the
   stub-provider evaluation harness in CI. 🔒 Live model calls additionally gated on
   the founder's Anthropic API key.
6. ⬜ **Evidence/documents slice** — real byte storage, extraction with a provenance
   schema, control mappings, expiry dates. Unlocks freshness checks, contradiction
   detection, and affected-evidence on alerts.
7. ⬜ **Assessments + findings slice** — assessment instances, findings records,
   vendor recovery/exit fields. Unlocks prepared assessments and preventive-action
   drafts.
8. ⬜ **Remediation + decisions slice** — completes the core domain data layer.
9. ⬜ **Scheduler + system-actor runs** — the engine learns to start work on a clock
   and on events, as a named system actor under the same audit. **Prerequisite for
   everything proactive.**
10. ⬜ **Foresight F1** — deterministic signal screening (sanctions lists, status
    pages, KEV) over the licensed-safe sources, with vendor identity resolution
    (LEI-first) and confirmed/unconfirmed exposure states.
11. ⬜ **Qualitative risk trajectory outlooks** — direction, leading indicators,
    evidence freshness, confidence — no invented probabilities.
12. ⬜ **Dependencies/graph slice** — vendor ↔ fourth-party ↔ business-service
    graph with evidence per edge (confirmed vs inferred).
13. ⬜ **Fourth-party ripple analysis** — impact traversal over the graph.
14. ⬜ **Risk Flight Simulator** — assumption-explicit scenarios over the graph;
    outputs labelled scenarios, never predictions.
15. ⬜ **Preventive Action Planner agent** — cross-module plan composition,
    draft-only tools, ModeGate approvals.
16. ⬜ **Independent AI Verification Agent** — isolated READ-only verification runs;
    verdicts recorded in the audit chain; catch rates published internally first.
    External publication of measured agent accuracy is the NORTH_STAR ambition and
    follows once the verification layer itself is proven.
17. 🔒 **Numerical forecasting** — only when labelled outcome volume, baselines,
    calibration and drift monitoring exist, and the founder approves the methodology.
18. 🔒 **External communications / write tools** — only per provider, after that
    provider's idempotency (keyed on the task's stable idempotency key) is
    independently verified and tested. Until then: prohibited, no exceptions.

Parallel, founder-gated tracks (not on the critical path): Logto M1 (unauthorised),
score-direction decision (pending), repository presentation items PR-B…PR-I
(per-item approval).
