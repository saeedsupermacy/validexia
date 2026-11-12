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

## Resolution — implemented (branch `feat/agent-engine-concurrency-safety`)

Status per finding, implemented after this audit was merged:

1. **Task leases with fencing (V7).** `agent_tasks` gains `claimed_by` and
   `claim_expires_at`. `claim_agent_task` claims from PENDING, or from RUNNING
   only once the lease has expired; claiming increments `attempts`, which is
   the fencing token. `complete_agent_task` and `park_agent_task_for_approval`
   require the caller's (executor, attempt) pair to still match — a stale
   executor's completion matches zero rows, and the engine discards its result
   without writing audit.
2. **Claim before gate.** The engine claims first, then parks under the claim:
   `park_agent_task_for_approval` moves the task to WAITING_APPROVAL and
   inserts the approval row atomically, fenced by the claim. Losing the claim
   between claim and park is absorbed quietly — no 500, no duplicate approval
   (`UNIQUE(task_id)` remains as backstop).
3. **Single-executor semantics.** A RUNNING run may be entered, but every task
   touch goes through the lease claim, so concurrent executors divide the work;
   an executor that finds a live claim held by someone else stops and reports
   the run as it stands.
4. **Every CAS honoured.** `AGENT_TASK_EXECUTED`, `AGENT_RUN_SUCCEEDED`, and
   `AGENT_RUN_FAILED` are appended only when the corresponding transition
   actually won.
5. **Stable idempotency keys.** `ToolInvocation` carries `runId`, `taskId`, the
   task's stored idempotency key (`runId:seq`, assigned once when the plan is
   persisted — identical across retries, requeues, and executors), and the
   fencing attempt (never part of the key). The `ToolHandler` contract
   documents at-least-once semantics, the provider-idempotency duty, and
   reconcile-don't-retry for ambiguous outcomes.
6. **Convergent creation.** `create_agent_run` catches the idempotency
   unique-violation and returns the winning run with `was_created = false`, so
   concurrent creators converge instead of erroring.

Covered by `AgentConcurrencyTest` (latch-driven, no sleeps): concurrent
executors share work without duplicating it; a lease-expiry takeover fences
the stale executor (its output never commits; audit stays singular); the
approval race parks exactly once with no errors; eight concurrent creators
with one key converge on one run with one `AGENT_RUN_CREATED` event; and a
simulated ambiguous provider outcome is deduplicated by the stable key on
takeover (two invocations, one effect).

**Known remaining limits.** There is no lease heartbeat: a tool running
longer than the 30-second lease can be taken over mid-flight. Fencing
prevents the stale owner from committing database state, and the stable
idempotency key is the provider-side defence, per the gate above — which
still stands for real tools. Background (out-of-request) execution remains a
separate future increment.

---

## Follow-up finding (PR #3 review, 2026-11-12) — RESOLVED by migration V8

**Finding 6 — a duplicate creator could settle a run before its plan existed.**
Creation was split across transactions: `create_agent_run` committed the run,
then the engine persisted the plan task-by-task. A duplicate creator with the
same idempotency key received the run with `was_created = false` and went
straight to execution; arriving before the winner's first `addTask`, it saw an
empty task list — which trivially satisfies "all settled" — and could mark
the run SUCCEEDED. The winner then persisted tasks into a terminal run that
never executed them.

**Resolution (V8 + engine changes, same PR).** Plan initialization is now a
lifecycle, not a sequence of calls:

1. The engine computes the plan **before** persisting anything; a planner
   failure leaves no run behind at all.
2. `create_agent_run_with_plan` persists the run and its complete plan in a
   **single transaction**, marking the run `plan_ready` in the same commit.
   A duplicate caller can only ever observe a fully-initialized run, because
   the plan is part of the commit that makes the run visible.
3. `plan_ready` is explicit state, never inferred from task count: a
   legitimately empty plan is ready with zero tasks, and `executeRow`
   refuses to execute or settle a run that is not ready (reachable only via
   the legacy split creation functions, which leave `plan_ready = false`).
4. `claim_agent_task` validates its lease duration in the database
   (1..3600 seconds).
5. The 30-second lease question is answered with a **tool timeout, not
   heartbeat**: tool invocations run under an enforced 20-second ceiling
   (10-second margin below the lease). An overrunning tool is interrupted
   and its task failed while the claim is still valid, so a timeout cannot
   strand a task for mid-flight takeover. Tools that cannot finish within
   the ceiling must not be registered until lease renewal exists; this is
   documented on the `ToolHandler` contract.

Covered by `AgentPlanLifecycleTest` (latch-driven): the review race replayed
with creator A paused before persistence (no run visible; B completes the
whole run; A converges; plan persisted exactly once; tool executed exactly
once); an unready run is never executed or settled; a planner failure leaves
no run and retries cleanly under the same key; a legitimately empty plan is
plan-ready and succeeds; lease durations of 0 and 3601 are rejected while a
valid claim still succeeds; and a hanging tool is interrupted and failed
within its lease.

**Gate status unchanged.** The external write-tool restriction stands:
stable keys are necessary but not sufficient — a provider's actual
idempotency semantics and retention window must be verified before any
external integration is enabled.

---

## Follow-up finding (PR #3 review, 2026-11-12) — Finding 7, RESOLVED by migration V9

**Finding 7 — `Future.cancel(true)` is not a termination mechanism.** The V8
timeout strategy requested interruption at 20s and immediately recorded the
task FAILED and released its claim. A tool ignoring interruption kept
running; at lease expiry (30s) a takeover could invoke the same operation a
second time, concurrently — and the unbounded cached thread pool could
accumulate such zombie threads indefinitely. Cancelling the future also made
the invocation's real outcome unobservable.

**Resolution — the execution lifecycle, not a stronger cancel:**

1. **Fenced heartbeat (V9).** While an invocation runs, its owner renews the
   claim every lease/3 via `renew_agent_task_claim`, fenced on
   (claimed_by, attempts) and only while the lease is still live (an expired
   claim cannot be resurrected). A live invocation's lease therefore never
   expires, so no takeover — and no second invocation — can occur while the
   operation is actually running.
2. **Outcomes only for ended invocations.** At the soft execution budget
   (20s), or when the heartbeat reports the claim lost, the engine interrupts
   the tool's own tracked thread directly and then waits until the invocation
   has **actually ended** before recording anything. A tool ignoring
   interruption keeps its task RUNNING under its heartbeat-held claim —
   visible as still running — instead of being falsely FAILED-and-released.
   If it never ends, the engine thread waits; that is the deliberate price of
   never lying about termination.
3. **Renewal failure semantics.** A renewal returning false means ownership
   is proven lost: interruption is requested and the eventual completion is
   fenced out by the existing CAS. A renewal *throwing* (database
   unavailable) proves nothing: the heartbeat retries on schedule and never
   declares loss on an error.
4. **Bounded capacity.** The cached pool is replaced by a fixed pool
   (4 threads, queue 16, abort on saturation). Saturation rejects a
   submission *before* the invocation starts — the only immediate failure,
   safe precisely because nothing ran. Zombie accumulation is thereby
   bounded by the pool size, and each zombie holds a live claim, so zombies
   cannot cause duplication.

Covered by `AgentPlanLifecycleTest` additions: an interruption-ignoring
tool is never invoked twice (heartbeat holds the claim past a full lease
duration; a second executor observes RUNNING; the real outcome lands
exactly once); renewal fencing (wrong executor/attempt rejected,
no resurrection after expiry, stale owner locked out after takeover);
heartbeat behaviour when the renewer throws (retries, no false loss) and
when it returns false (loss signalled); saturation rejection without
starting the invocation. The responsive-tool budget test is retained: a
tool that honours interruption ends, and only then is its task failed.

**Remaining limitations, stated plainly.**

- A renewal-path outage longer than the lease (database down while a tool
  runs) can still let a claim expire underneath a running invocation; a
  takeover could then invoke concurrently. The stale owner's *state* writes
  remain fenced out, but concurrent *invocation* in that window cannot be
  excluded in-JVM. This is a further reason the provider-idempotency and
  reconciliation gate stands for any external write tool.
- Hard termination of uncooperative code requires process isolation
  (a separate process/container per invocation that the OS can kill). That
  is the identified route if hard timeouts ever become a requirement; it is
  deliberately not claimed here.
- The earlier "no lease heartbeat" limit is superseded by this section.

---

## Follow-up findings (PR #3 review, 2026-11-12) — Findings 8 and 9, RESOLVED

**Finding 8 — caller-thread interruption closed the heartbeat.** `invokeTool`'s
`InterruptedException` handler interrupted the tool, restored the caller's
interrupt flag, and threw — closing the heartbeat while the tool could still
be running, so its claim could expire underneath it and a takeover could
invoke the task concurrently (the Finding 7 failure shape, via a different
door). **Resolution:** a caller interruption is now *absorbed* — the tool's
interruption is requested and observation continues with the heartbeat alive
for exactly as long as the invocation may be running. The interrupt throw
clears the caller's flag; the status is restored only after the invocation
has ended **and its outcome has been recorded**. That ordering matters and
was proven in CI, not just argued: restoring the flag before the outcome
writes let a contended JDBC connection pool (Hikari waits are interruptible)
fail the completion write itself, so restoration is deferred to
`executeTask`'s finally, after success/failure bookkeeping. Restoring the
flag makes the engine stop its walk after the current task — the run stays
RUNNING and resumable, which is the designed response to an interrupt.

**Finding 9 — a queued invocation could start after claim loss.** The old
nested `ToolRun` tracked only a runner thread reference: a cancellation
requested while the invocation sat in the queue did nothing, and startup vs
interruption was a check-then-act race. **Resolution:** `ToolRun` is now its
own class whose queued/running/cancelled lifecycle is coordinated under one
lock. If cancellation wins before start, `call()` throws `NotStartedException`
and the tool is NEVER invoked. If start wins, the runner thread is published
inside the same critical section, so a later interruption request reliably
reaches it. The runner reference is cleared under the lock at completion, so
a pooled thread can never be interrupted on behalf of an invocation it has
already left. The heartbeat now also starts *before* submission, so queued
invocations are renewed while they wait and rejected submissions close the
heartbeat with nothing running.

**Heartbeat infrastructure bound (reviewer-requested inspection).** The
heartbeat scheduler was a single thread: one blocked database renewal
stalled renewals for every other active task. It is now a bounded parallel
pool (4 threads). Renewal statements additionally carry a JDBC query timeout
of one renewal interval (lease/3, minimum 1s): a hung renewal — a locked row,
a stalled database — fails bounded as a transient error and retries on
schedule instead of occupying a heartbeat thread and a pooled connection
indefinitely; a hanging database call is never silently treated as a quick
transient. Renewals share the application's connection pool with everything
else by design: each renewal is a single-row UPDATE, and the concrete safe
bound is that the pool must supply a connection and the database must answer
within roughly one renewal interval per attempt, with three attempts per
lease window. (The integration suite runs against a pool of size 1 and
passes, which exercises exactly this contention.) A renewal-path outage
longer than the lease remains the documented residual case from Finding 7.

Covered by `AgentPlanLifecycleTest` additions: caller interruption with an
interruption-ignoring tool (heartbeat held past a full lease, exactly one
outcome, interrupt status restored, walk stopped and run settled by a later
execute); claim loss while queued and interruption while queued (the tool is
never invoked in either case); `ToolRun` unit tests (cancel-before-start,
interrupt-reaches-runner, a 100-round startup/interruption race hammer
asserting exactly one of the two legal outcomes per round); a blocked
heartbeat not stalling others on the production scheduler; and a renewal
freed by its query timeout while its row is locked by another transaction.

---

## Follow-up finding (PR #3 review, 2026-11-12) — Finding 10, RESOLVED by lifecycle redesign + explicit pool bounds

**Finding 10A — heartbeat capacity did not cover the maximum claims.** With
claims taken at walk time, up to 20 claims could be live at once (4 running
invocations + 16 queued), each owed a renewal every 10s, serviced by 4
scheduler threads with a 10s statement timeout: a worst-case renewal batch
needed ~50 thread-seconds per 10s window. The guarantee did not exist.

**Resolution — shrink the claim population, not inflate the plane.** A
supervisor stage now sits in front of the tool pool. Assignments queue
UNCLAIMED; a supervisor thread claims a task only when it is about to invoke
it, and then owns the heartbeat, the budget observation and the outcome
recording for that invocation. Maximum simultaneous claims = tool threads
(4). The capacity arithmetic is then: 4 claims × 1 renewal per 10s interval;
4 parallel scheduler threads, so no renewal ever queues behind another
claim's renewal; one renewal's worst case (below) is ~11s, and because a
success extends the lease by a full 30s and scheduling is fixed-delay (no
pile-up), even a worst-case renewal cadence of ~21s per claim stays inside
the lease. Queued work costs no lease and no heartbeat, however long it
waits. ModeGate parking is unchanged: the gate is evaluated first, and a
task needing approval is claimed on the walk thread and parked under the
fence, exactly as before. Saturation rejects at the assignment queue,
claiming only to record the failure.

**Finding 10B — statement timeout did not bound connection acquisition.**
Inspection found NO Hikari configuration anywhere in the application: the
effective `connection-timeout` was the default 30,000ms — the lease exactly —
so a renewal could block for a whole lease before its statement started,
and `setQueryTimeout` never applied. **Resolution:** `application.yaml` now
pins `connection-timeout: 5000` and `maximum-pool-size: 10` with the
reasoning recorded in place. A renewal's complete operation is therefore
bounded: acquisition ≤ 5s + statement ≤ 5s (the repository caps its
statement timeout at min(lease/3, 5s)) + commit — ~11s worst case, twice
inside one 30s lease window at a 10s renewal interval. Renewals can hold at
most 4 of the 10 pooled connections (one per live claim), leaving the
majority for request and supervisor traffic.

**Semantics preserved and proven.** A renewal blocked on acquisition fails
as a transient error (at transaction begin it surfaces as
`CannotCreateTransactionException` caused by `SQLTransientConnectionException`),
which the heartbeat already treats as transient — never as claim loss; the
claim's fence is untouched, and the same renewal succeeds once a connection
is available. What happens if the outage exceeds the lease is unchanged and
remains the documented residual: the claim can expire under a still-running
invocation, the stale owner's state writes stay fenced out, concurrent
invocation in that window cannot be excluded in-JVM, and the
provider-idempotency gate stands.

**Fallout fixed along the way.** Restoration of a caller's interrupt status
now ends ALL interruptible infrastructure work on that thread: the walk's
interrupt stop previously re-read the run row from the database, which a
contended pool could refuse to a flagged thread (observed in CI as an
intermittent failure after the outcome had been safely recorded). The walk
returns the row already in hand, reloaded after its own transitions.

Covered by tests: queued assignments hold no claim (task stays PENDING
while queued; claimed fresh and executed exactly once when picked up);
interruption while queued does not cancel the assignment; a stress test
running the maximum 4 heartbeats at 600ms-per-renewal latency on the
production scheduler, all cycling without loss; pool exhaustion held
separately from row-lock contention (the only pooled connection held by the
test; renewal acquisition fails at ~5s with the transient exception chain,
then succeeds after release). The two Finding-9 queued tests were rewritten
to these invariants — the scenario they guarded (a claim lost while queued)
no longer exists by construction; ToolRun's cancel-before-start
coordination is retained and still unit-tested for the running phase.
