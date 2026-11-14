# HANDOFF — continue Validexia in a new conversation

This file is the standing handoff. It is refreshed at the end of every substantial
assignment and whenever a conversation approaches its context limit. A new
conversation should read this file and the rest of `docs/continuity/` first, then
the design/audit sources it points to — and must not ask the founder to repeat
anything recorded here.

## Continuation prompt (paste into the new chat)

> Continue Validexia. THE VISION: an AI-native Third-Party Risk Management
> operating platform — one orchestration engine running specialised AI across
> every module, proactively monitoring vendors and external signals, producing
> evidence-backed conclusions, with humans owning consequential decisions.
> It is NOT a vendor app with a chatbot. WHERE WE ARE: main is `c66fae7`
> (identity/audit + the merged agent engine); PRs #5 (AI model layer), #6
> (vendors data slice) and #7 (continuity docs) are open and reviewed; the
> frontend is still a fixture prototype. NEXT MILESTONE: the Vendor
> Intelligence Brief — the first end-to-end capability (real vendor API →
> READ tool → specialist agent on the existing engine → real model gateway →
> validated brief → Workbench showing real runs → evaluation harness).
> BLOCKERS: the first live model call needs the founder's Anthropic API key
> (Secure Vault). RESTRICTIONS: never merge, deploy or send anything without
> the founder's explicit approval; external write tools are PROHIBITED;
> Logto M1 is unauthorised (do not code it); no risk-score wiring (the score
> decision is open); never present mocked/demo behaviour as real. WORKING
> RULES: read `docs/continuity/` in the repo for the full recorded state
> before acting; reconcile any pasted prompt (many are ChatGPT-drafted, its
> project memory is weak) against that record — name what is already done,
> what conflicts, and what is genuinely new; report each increment with
> files changed, tests and results.

## The 12-point handoff (per the founder's permanent instruction, §F)

1. **Vision** — NORTH_STAR.md: AI-native TPRM operating platform; one engine;
   evidence-backed AI; humans own consequential decisions.
2. **Ambition** — a trusted, indispensable platform in its category; differentiation
   via independent agent verification, published accuracy, trajectory outlooks, and
   the Foresight + evidence graph + simulator combination.
3. **Repository state** — PROJECT_STATE.md. Main `c66fae7`; PRs #5, #6 and #7 (this document set) open;
   #5/#6 CI-green; frontend is fixtures; backend is real but young.
4. **Previous prompts and outcomes** — core vision (2026-11-09) → AI Agent
   Foundation milestone (merged) → company-wide AI platform directive (2026-11-13,
   produced the inventory, model evaluation, competitor matrix, design, PR #5,
   PR #6) → strategic expansion directive (2026-11-13, produced the proactive gap
   analysis, licensing assessment, Foresight design set) → continuity instruction
   (2026-11-13, produced this document set).
5. **Completed implementation** — IMPLEMENTATION_LOG.md (with SHAs and CI counts).
6. **Research completed** — RESEARCH_REGISTER.md (finding → decision traceability).
7. **Approved architectural decisions** — ARCHITECTURE_DECISIONS.md, settled D1–D13.
8. **Unresolved issues** — open decisions O1–O4 (score direction; Logto M1 + Pro;
   API key; ZDR). Known limitations are listed in IMPLEMENTATION_LOG.md.
9. **Current assignment** — the AI platform milestone: data layer first, then the
   first model-backed capability (the Vendor Intelligence Brief). PRs #5/#6/#7
   await founder review; the review fix passes are the current task when this
   handoff is read before their completion — check NEXT_STEPS.md for the live queue.
10. **Exact next steps** — NEXT_STEPS.md.
11. **Tests actually performed** — CI suites: main 116/116; PR #5 branch 136 green;
    PR #6 branch 129 green (counts reconciled against `@Test` annotations).
    PostgreSQL 16.15 roles-init integration: 35/35. Docker-based verification has
    never run (unavailable) — do not claim it has.
12. **Restrictions that must not be forgotten** — no merges/deploys/sends without
    founder approval of the exact action; external write tools prohibited (D5);
    Logto M1 unauthorised; no score wiring (O1); secrets only via Secure Vault;
    never present mocked/demo behaviour as real functionality.
