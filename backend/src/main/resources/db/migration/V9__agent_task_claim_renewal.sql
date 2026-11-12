-- V9: Fenced lease renewal for agent task claims.
--
-- Follow-up to the PR #3 review of the V8 timeout strategy (Finding 7).
-- Future.cancel(true) only requests interruption; a tool that ignores it
-- keeps running after its task was marked FAILED and its claim released, so
-- once the lease expired a takeover could invoke the same operation a second
-- time, concurrently. The correction is a lifecycle change:
--
-- - While an invocation is running, its owner renews the claim (this
--   function), so a live invocation's lease never expires and no takeover —
--   and therefore no second invocation — can occur.
-- - Renewal is fenced on (claimed_by, attempts), exactly like completion:
--   a stale executor cannot renew another executor's claim.
-- - Renewal cannot resurrect an expired claim (claim_expires_at must still
--   be in the future): once a lease has lapsed, the claim is dead, whoever
--   held it. The engine renews at lease/3 intervals, so a renewal has
--   several chances to land before expiry.
-- - The engine records an outcome only when an invocation has actually
--   ended; an interruption request alone never releases a claim.
--
-- V5–V8 are not edited; the function keeps the V6 fail-closed tenant guard
-- and the V8 lease-duration validation.

CREATE FUNCTION public.renew_agent_task_claim(
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
    SET claim_expires_at = pg_catalog.now()
            + pg_catalog.make_interval(secs => p_lease_seconds::double precision),
        updated_at = pg_catalog.now()
    WHERE id = p_task_id AND tenant_id = p_tenant_id AND state = 'RUNNING'
      AND claimed_by = p_executor_id AND attempts = p_attempt
      AND claim_expires_at IS NOT NULL AND claim_expires_at > pg_catalog.now();
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RETURN v_rows = 1;
END $$;
REVOKE ALL ON FUNCTION public.renew_agent_task_claim(uuid, uuid, text, int, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.renew_agent_task_claim(uuid, uuid, text, int, int) TO validexia_app;
ALTER FUNCTION public.renew_agent_task_claim(uuid, uuid, text, int, int) OWNER TO validexia_agents_owner;
