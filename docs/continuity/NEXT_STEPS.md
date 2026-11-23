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

1. **Merge follow-through**: after PR #5/#6 merge, re-verify main CI green, then
   present the Vendor Intelligence Brief as ONE clean PR against main (the
   integration branch already contains it: baseline merges + 11 feature commits +
   2 integration fixes; backend CI 198 green; independently reviewed). Founder
   merges. ◆
2. **First live run** (after the Anthropic key, founder action 2): run the brief
   end-to-end against the real provider locally, run the harness live, and verify
   the wired Vendors module + Workbench against the live Vercel app per the
   founder's standing instruction. Until then the capability is stub-proven only.
3. **Roadmap step 6**: evidence/documents slice design → implementation (V13+),
   provenance schema first (the brief's `source_kind` enum is the seam: evidence
   facts join as a second kind, never mixed with record facts).
4. **Vendor identity increment**: aliases capture + identity resolution (GLEIF),
   scheduled with roadmap step 10 preparation.
5. Keep this document set current: state changes land in PROJECT_STATE.md and
   IMPLEMENTATION_LOG.md in the same PR as the work they describe.

## Explicitly NOT next (parked, with reasons)

- **Foresight ingestion** — waits for vendors + evidence data and the scheduler
  (roadmap steps 6 and 9). Building it earlier would build it on air.
- **Any external write tool** — prohibited (decision D5), no exceptions.
- **Logto M1 code** — unauthorised (O2).
- **Score wiring of any kind** — undecided (O1).
- **Repository presentation items PR-B…PR-I** — per-item founder approval pending.
