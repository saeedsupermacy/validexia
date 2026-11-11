# AgentEngine concurrency audit — report only

**Date:** 2026-11-11 · **Scope:** `AgentEngine` run/task execution under concurrent
requests, crash recovery, and idempotency. **Status:** analysis only — no engine code
was changed for this audit. Tenant isolation is *not* implicated: every race below
plays out inside a single tenant's own run. The risk is duplicated side effects and
inconsistent run state once real (non-mock) tools exist, plus spurious request
failures today.

## How execution works today (the facts the analysis rests on)

- `execute()` loads the run, then walks its tasks in sequence order. Each repository
  call is its own transaction. Tool calls happen *between* transactions, in-process.
- Run entry transitions are compare-and-swap (`transition_agent_run` matches the
  expected current state). Task claims are also CAS (`transition_agent_task`).
- A task found in `RUNNING` is assumed to belong to a dead executor: any caller of
  `execute()` requeues it (`RUNNING → PENDING`) and executes it itself.
- `agent_tasks` has an `attempts` counter (incremented on each claim) but no owner,
  no lease/expiry, and no fencing check: transitions match **state only**.
- Tools receive only their input JSON (`ToolHandler.execute(String)`). The task's
  stored idempotency key (`runId:seq`) is never passed to the tool.

## Finding 1 — CRITICAL (for real tools): a slow executor's task is executed twice

The `RUNNING → PENDING` recovery cannot distinguish a *crashed* executor from a
*slow* one, and it fires immediately, with no staleness threshold.

Sequence:

1. Executor A claims task T (`PENDING → RUNNING`) and enters `tool.execute()`.
   The tool is slow (network call, file work) but healthy.
2. Any second `execute()` for the same run arrives — a user pressing "resume", a
   retrying client, `decideApproval` resuming the run, or a second app instance.
   It finds the run already `RUNNING` (no entry claim exists for that state — see
   Finding 3) and reaches T in its task walk.
3. Executor B requeues T (`RUNNING → PENDING` CAS succeeds — the state *is*
   RUNNING), reloads it, claims it (`PENDING → RUNNING`), and enters
   `tool.execute()` **concurrently with A**.
4. Both executors perform the tool's side effect. Whoever finishes first wins the
   `RUNNING → SUCCEEDED` CAS; the other's completion CAS fails but is **ignored**
   (Finding 4), and its output is silently discarded.

For a tool that sends an email, writes to an external API, or moves money, step 3
is a duplicated external effect. `ToolHandler`'s contract says implementations
"must be safe to call again with the same input", i.e. the engine is at-least-once
by design — but the handler is given no task id, run id, or idempotency key, so a
real tool has **no stable dedupe key** to offer the external system. At-least-once
delivery plus no dedupe key means duplicates are a matter of time once real tools
land.

Leasing and fencing (recommended fix items 1–3) bound what executors can commit
to the *database*; they cannot by themselves guarantee exactly-once *external*
execution. If a task's lease expires while its provider request is still in
flight, a second executor can legitimately claim the task and issue a second
provider request while the first is still executing — the database state stays
consistent, and the outside world still sees two effects. The only defences at
that boundary are provider-side: a stable idempotency key carried through the
tool invocation (item 4), passed to the provider's idempotency mechanism
wherever one exists, or used for equivalent deduplication. And where a provider
call's outcome is **ambiguous** — a timeout or dropped connection after the
request may already have been accepted — the correct response is reconciliation
(query the provider for what the idempotency key actually did), never a blind
retry, which turns an unknown outcome into a probable duplicate.

## Finding 2 — Approval parking races produce spurious HTTP 500s

In `executeTask`, the ModeGate check and `request_agent_approval` happen **before**
any claim on the task. Two executors reaching the same PENDING, gate-controlled
task both call `request_agent_approval`. The SQL function performs its own
`PENDING → WAITING_APPROVAL` CAS and *raises* when the task is no longer PENDING,
so exactly one executor parks the task and the other gets an exception that
propagates out of `execute()` — the API caller sees a 500 for what is a normal,
expected interleaving. (No duplicate approval can be created: the function's CAS
and `UNIQUE(task_id)` backstop it. The failure is availability/correctness of the
response, not data corruption.)

## Finding 3 — No run-level ownership: a RUNNING run admits unlimited executors

`execute()` claims the run only from `PENDING` (or `WAITING_APPROVAL`). If the run
is already `RUNNING`, the caller proceeds straight into the task walk. Combined
with Finding 1, any concurrent touch of an in-flight run — double-clicked resume,
client retry, approval decision racing a manual resume — becomes a second executor.

## Finding 4 — CAS results are ignored on completion paths; audit can duplicate

- `executeTask` ignores the boolean result of the `RUNNING → SUCCEEDED` (and
  `RUNNING → FAILED`) task transitions and appends `AGENT_TASK_EXECUTED`
  unconditionally after its own tool call returns. Under Finding 1's interleaving,
  one task yields **two** `AGENT_TASK_EXECUTED` audit events.
- The final `RUNNING → SUCCEEDED` run transition's result is likewise ignored and
  `AGENT_RUN_SUCCEEDED` is appended unconditionally; two executors reaching the
  end of the walk can both append it. (The audit hash chain stays internally
  consistent — the events are false duplicates, not corruption.)
- The failure path can interleave into inconsistent terminal states: executor A
  fails task T and marks the run FAILED while executor B (which requeued and
  re-ran T) marks T SUCCEEDED. Task and run end in contradictory states.

## Finding 5 — create_agent_run is check-then-act; concurrent creators can 500

`create_agent_run` implements idempotency as SELECT-then-INSERT. Two concurrent
`createAndStart` calls with the same `(tenant, idempotency_key)` can both miss
the SELECT and both INSERT; the partial unique index correctly saves the data
(exactly one run exists) but the losing caller receives a unique-violation error
(HTTP 500) instead of the existing run's id. Idempotency that errors under
concurrency will train clients not to retry — the opposite of its purpose.
(`add_agent_task` does this correctly with `ON CONFLICT ... DO UPDATE`.)

## What is sound today

- Single-executor flows (everything CI exercises) are correct.
- The `PENDING → RUNNING` task claim CAS does prevent two executors from
  *simultaneously claiming the same PENDING task* through the normal path; the
  holes are the recovery path, the pre-claim parking path, and the ignored
  completion CAS results.
- `decide_agent_approval` is properly CAS-guarded: a second decision returns
  false and surfaces as a validation error, not a double decision.
- All races are intra-tenant and intra-run; no cross-tenant exposure.

## Reproduction test proposals (deterministic — latches, no sleeps)

1. **Duplicate execution (Finding 1).** Register a test `ToolHandler` whose
   `execute()` increments an entry counter and blocks on a `CountDownLatch`.
   Thread 1: `createAndStart` an AUTONOMOUS run using that tool; wait until the
   tool is entered. Main thread: call `execute()` again (the "resume"). Assert
   the tool entry count reaches 2 for a single task. Release the latch; let both
   threads finish; assert two `AGENT_TASK_EXECUTED` audit rows exist for the task.
   Both assertions fail on the fixed engine (count stays 1 until the claim
   expires; exactly one audit event).
2. **Double-park 500 (Finding 2).** An APPROVAL-mode run whose next task is
   gate-controlled; two threads call `execute()` simultaneously. Assert both
   calls return the parked run (today one throws).
3. **Concurrent create (Finding 5).** N threads call `createAndStart` with the
   same idempotency key. Assert all return the same run id, exactly one run row
   exists, and the plan's tasks exist exactly once (today some callers throw).

## Recommended fix (future increment — not applied here)

1. **Task leasing with fencing.** Add `claimed_by` and `claim_expires_at` to
   `agent_tasks` (new forward migration). Claiming becomes one CAS update that
   sets owner + expiry and succeeds only from `PENDING`, or from `RUNNING` when
   the existing claim has **expired**. Completion/failure transitions must match
   `claimed_by` (and the claim's `attempts` value as a fencing token), so a
   zombie executor can no longer write after losing its claim.
2. **Claim before gate.** Move the claim ahead of the ModeGate/approval logic in
   `executeTask`, so parking a task is also single-executor work (the claim owner
   transitions it to `WAITING_APPROVAL`).
3. **Honour every CAS result.** Append audit events only for transitions the
   executor actually won; on a lost CAS, re-read and return the current state.
4. **Give tools their dedupe keys — a hard gate, not an enhancement.** Extend
   the tool invocation context with a stable idempotency key derived from the
   run/task identity — identical across retries, requeues, and executors, never
   per-attempt — plus `runId` and `taskId`. Tools performing external writes
   must pass that key to the provider's idempotency mechanism wherever one is
   supported, or dedupe against it equivalently, and must resolve ambiguous
   provider outcomes by reconciliation rather than blind retries (see
   Finding 1). Document the engine's at-least-once semantics where tool authors
   will see them.
5. **Make run creation converge.** In `create_agent_run`, use
   `INSERT ... ON CONFLICT (tenant_id, idempotency_key) DO NOTHING` followed by
   a re-SELECT (or catch `unique_violation` and re-select), so concurrent
   creators all return the one run.
6. Optional, with leasing in place: an executor that finds a run `RUNNING` with
   no expired claims can return the current state immediately instead of walking
   tasks it cannot claim.

**Gate recommendation:** items 1–4 must **all** land before any tool with an
external side effect (email, payments, third-party writes) is registered.
Items 1–3 make executor behaviour safe inside the database; item 4 is what
makes retries safe at the provider boundary, and exactly-once external
execution cannot be guaranteed merely through database leases. Read-only and
in-memory tools are safe under the current engine.
