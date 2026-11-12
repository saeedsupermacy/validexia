-- V11: the renewal's fence and expiry checks must be evaluated AFTER the
-- task row's lock is held — not as WHERE predicates (PR #3 review,
-- Finding 11, second layer; found by the V10 regression test failing in CI).
--
-- V10 replaced now() with clock_timestamp(), which fixed the timestamp
-- SOURCE. It was not sufficient: in READ COMMITTED, an UPDATE's WHERE
-- predicates are evaluated when the row is first examined — BEFORE the
-- statement waits on the row's lock. If the locking transaction then
-- ABORTS, the blocked update proceeds against the original row version on
-- the strength of that early evaluation (predicate re-evaluation only
-- happens when the concurrent transaction COMMITTED a newer version). So
-- a renewal that reached the row while its claim was live, then waited on
-- a lock past the wall-clock expiry, still renewed the dead claim — with
-- now() and with clock_timestamp() alike. Time-based predicates are the
-- exposed case: the passage of time during the wait changes no data, so
-- nothing ever triggers a fresh evaluation.
--
-- This version locks first and checks second, in procedural code:
--
-- - SELECT ... FOR UPDATE takes the row lock (waiting out any concurrent
--   writer) and returns the newest committed version of the row.
-- - The fence (state, claimed_by, attempts) and the expiry check are then
--   evaluated in PL/pgSQL, at that moment, with clock_timestamp(), while
--   the lock is held — no state and no clock reading can move between the
--   check and the update that follows it.
--
-- The claim function (V7) does not need the same treatment: its expiry
-- predicate runs in the safe direction — time passing during a lock wait
-- only makes an expired claim more expired, an aborting blocker leaves
-- exactly the state that was evaluated, and a committing blocker forces
-- predicate re-evaluation against the new version.
--
-- V10 is not edited; CREATE OR REPLACE preserves the function's owner and
-- grants. The tenant guard and lease validation are unchanged.

CREATE OR REPLACE FUNCTION public.renew_agent_task_claim(
    p_tenant_id uuid, p_task_id uuid, p_executor_id text, p_attempt int,
    p_lease_seconds int)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_state text;
    v_claimed_by text;
    v_attempts int;
    v_expires timestamptz;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF p_lease_seconds IS NULL OR p_lease_seconds < 1 OR p_lease_seconds > 3600 THEN
        RAISE EXCEPTION 'lease seconds out of range (1..3600): %', p_lease_seconds;
    END IF;
    SELECT t.state, t.claimed_by, t.attempts, t.claim_expires_at
      INTO v_state, v_claimed_by, v_attempts, v_expires
      FROM public.agent_tasks t
     WHERE t.id = p_task_id AND t.tenant_id = p_tenant_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RETURN false;
    END IF;
    IF v_state <> 'RUNNING'
        OR v_claimed_by IS DISTINCT FROM p_executor_id
        OR v_attempts <> p_attempt
        OR v_expires IS NULL
        OR v_expires <= pg_catalog.clock_timestamp() THEN
        RETURN false;
    END IF;
    UPDATE public.agent_tasks
    SET claim_expires_at = pg_catalog.clock_timestamp()
            + pg_catalog.make_interval(secs => p_lease_seconds::double precision),
        updated_at = pg_catalog.clock_timestamp()
    WHERE id = p_task_id;
    RETURN true;
END $$;
