-- V10: the claim-renewal expiry check must use wall-clock time, not the
-- transaction-start timestamp (PR #3 review, Finding 11).
--
-- PostgreSQL now() is transaction_timestamp(): it is fixed at the moment the
-- caller's transaction began and does not advance inside it. V9's renewal
-- therefore had a stale-timestamp hole: a renewal whose transaction started
-- while the claim was still live, but whose UPDATE only executed after the
-- lease had expired in wall-clock time (for example while blocked on the
-- task row's lock), passed the claim_expires_at > now() check against the
-- stale start time and resurrected an expired claim, stamping the new expiry
-- from the same stale base. The (claimed_by, attempts) fence blunted the
-- worst case (a takeover in between changes attempts, so the stale renewal
-- matched zero rows), but the check itself was wrong.
--
-- clock_timestamp() is the actual wall clock and advances within a
-- transaction, so both the liveness check and the new expiry are evaluated
-- at execution time. The tenant guard, lease validation and fencing are
-- unchanged from V9. (The claim function in V7 also uses now(); there a
-- stale start time only ever shortens a new lease or refuses a takeover
-- that wall-clock time would have allowed — the conservative direction —
-- so it is deliberately left as is.)
--
-- V9 is not edited; CREATE OR REPLACE preserves the function's owner and
-- grants.

CREATE OR REPLACE FUNCTION public.renew_agent_task_claim(
    p_tenant_id uuid, p_task_id uuid, p_executor_id text, p_attempt int,
    p_lease_seconds int)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_rows int;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF p_lease_seconds IS NULL OR p_lease_seconds < 1 OR p_lease_seconds > 3600 THEN
        RAISE EXCEPTION 'lease seconds out of range (1..3600): %', p_lease_seconds;
    END IF;
    UPDATE public.agent_tasks
    SET claim_expires_at = pg_catalog.clock_timestamp()
            + pg_catalog.make_interval(secs => p_lease_seconds::double precision),
        updated_at = pg_catalog.clock_timestamp()
    WHERE id = p_task_id AND tenant_id = p_tenant_id AND state = 'RUNNING'
      AND claimed_by = p_executor_id AND attempts = p_attempt
      AND claim_expires_at IS NOT NULL AND claim_expires_at > pg_catalog.clock_timestamp();
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RETURN v_rows = 1;
END $$;
