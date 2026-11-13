# NEXT STEPS — the exact queue

Last updated: 2026-11-13. One queue, in order. Founder actions are marked ◆.

## Founder actions (nothing here can be done by anyone else)

1. ◆ **Review PR #5** (AI model layer) and **PR #6** (vendors data slice) — merge in
   either order; both are CI-green and independent.
2. ◆ **Anthropic API key via Secure Vault** — gates the first live model call and
   the live evaluation run (roadmap step 5's live half). Nothing else waits on it.
3. ◆ When ready (no engineering is blocked today): score-direction decision (O1),
   Logto M1 authorisation + Logto-spec D12 (O2), ZDR applications (O4).

## Engineering queue (in roadmap order)

1. **Merge follow-through**: after PR #5/#6 merge, re-verify main CI green.
2. **Frontend Vendors wiring** (completes roadmap step 4): the Vendors module reads
   and writes the real `/api/v1/vendors` API; fixtures remain only as an explicit
   offline/demo fallback, labelled as such. Verify against the live Vercel app per
   the founder's standing instruction.
3. **Roadmap step 5**: first model-backed READ capability — vendor-summary tool +
   specialist agent definition + Workbench on real engine runs; stub-provider
   evaluation harness in CI (the 20-case suite from the model evaluation, adapted).
4. **Roadmap step 6**: evidence/documents slice design → implementation (V13+),
   provenance schema first.
5. Keep this document set current: state changes land in PROJECT_STATE.md and
   IMPLEMENTATION_LOG.md in the same PR as the work they describe.

## Explicitly NOT next (parked, with reasons)

- **Foresight ingestion** — waits for vendors + evidence data and the scheduler
  (roadmap steps 6 and 9). Building it earlier would build it on air.
- **Any external write tool** — prohibited (decision D5), no exceptions.
- **Logto M1 code** — unauthorised (O2).
- **Score wiring of any kind** — undecided (O1).
- **Repository presentation items PR-B…PR-I** — per-item founder approval pending.
