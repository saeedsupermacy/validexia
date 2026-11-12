-- V7: Agent task execution leases with fencing + convergent run creation.
--
-- Implements the concurrency-safety corrections from the AgentEngine audit
-- (backend/docs/agents/AGENT-ENGINE-CONCURRENCY-AUDIT.md, findings 1, 2, 3, 5).
-- V5/V6 are not edited; this is a forward migration. All functions keep the
-- fail-closed tenant guard from V6.
--
-- What changes:
-- 1. agent_tasks gains a lease: claimed_by (the executor instance holding the
--    task) and claim_expires_at. A task may be claimed from PENDING, or from
--    RUNNING once its lease has expired (the previous executor is presumed
--    dead). A RUNNING task with a live lease cannot be taken over, so a slow
--    executor is no longer executed twice.
-- 2. attempts becomes the fencing token: claiming increments it, and the
--    completion/parking functions require the caller's (claimed_by, attempts)
--    pair to still match. A stale executor that lost its lease cannot commit
--    state afterwards — its writes simply match zero rows.
-- 3. Parking for approval happens under a claim (park_agent_task_for_approval),
--    so two executors cannot race a task into duplicate approvals or errors.
-- 4. create_agent_run converges under concurrency: a unique-violation on the
--    idempotency index is resolved by returning the run that won the race
--    instead of surfacing an error to the caller.
-- 5. list_agent_tasks also returns the task's stored idempotency key, so the
--    engine can hand tools the stable key derived from run/task identity.

ALTER TABLE public.agent_tasks ADD COLUMN claimed_by text;
ALTER TABLE public.agent_tasks ADD COLUMN claim_expires_at timestamptz;

-- Task claiming -------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.claim_agent_task(
    p_tenant_id uuid, p_run_id uuid, p_task_id uuid,
    p_executor_id text, p_lease_seconds int)
RETURNS TABLE (claimed boolean, attempt int, task_state text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_attempt int; v_state text;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    UPDATE public.agent_tasks t
    SET state = 'RUNNING',
        attempts = t.attempts + 1,
        claimed_by = p_executor_id,
        claim_expires_at = pg_catalog.now()
            + pg_catalog.make_interval(secs => p_lease_seconds::double precision),
        updated_at = pg_catalog.now()
    WHERE t.id = p_task_id AND t.tenant_id = p_tenant_id AND t.run_id = p_run_id
      AND (t.state = 'PENDING'
           OR (t.state = 'RUNNING'
               AND (t.claim_expires_at IS NULL OR t.claim_expires_at < pg_catalog.now())))
    RETURNING t.attempts INTO v_attempt;
    IF FOUND THEN
        RETURN QUERY SELECT true, v_attempt, 'RUNNING';
        RETURN;
    END IF;
    SELECT t.attempts, t.state INTO v_attempt, v_state
    FROM public.agent_tasks t
    WHERE t.id = p_task_id AND t.tenant_id = p_tenant_id AND t.run_id = p_run_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'task not found in tenant';
    END IF;
    RETURN QUERY SELECT false, v_attempt, v_state;
END $$;
REVOKE ALL ON FUNCTION public.claim_agent_task(uuid, uuid, uuid, text, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.claim_agent_task(uuid, uuid, uuid, text, int) TO validexia_app;
ALTER FUNCTION public.claim_agent_task(uuid, uuid, uuid, text, int) OWNER TO validexia_agents_owner;

-- Fenced completion -----------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.complete_agent_task(
    p_tenant_id uuid, p_task_id uuid, p_executor_id text, p_attempt int,
    p_target text, p_output jsonb, p_error text)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_rows int;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF p_target NOT IN ('SUCCEEDED', 'FAILED') THEN
        RAISE EXCEPTION 'target must be SUCCEEDED or FAILED';
    END IF;
    UPDATE public.agent_tasks
    SET state = p_target,
        output = COALESCE(p_output, output),
        error = COALESCE(p_error, error),
        claimed_by = NULL,
        claim_expires_at = NULL,
        updated_at = pg_catalog.now()
    WHERE id = p_task_id AND tenant_id = p_tenant_id AND state = 'RUNNING'
      AND claimed_by = p_executor_id AND attempts = p_attempt;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RETURN v_rows = 1;
END $$;
REVOKE ALL ON FUNCTION public.complete_agent_task(uuid, uuid, text, int, text, jsonb, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.complete_agent_task(uuid, uuid, text, int, text, jsonb, text) TO validexia_app;
ALTER FUNCTION public.complete_agent_task(uuid, uuid, text, int, text, jsonb, text) OWNER TO validexia_agents_owner;

-- Parking under a claim --------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.park_agent_task_for_approval(
    p_tenant_id uuid, p_task_id uuid, p_executor_id text, p_attempt int,
    p_reason text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_run_id uuid; v_approval_id uuid; v_rows int;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    UPDATE public.agent_tasks
    SET state = 'WAITING_APPROVAL', claimed_by = NULL, claim_expires_at = NULL,
        updated_at = pg_catalog.now()
    WHERE id = p_task_id AND tenant_id = p_tenant_id AND state = 'RUNNING'
      AND claimed_by = p_executor_id AND attempts = p_attempt
    RETURNING run_id INTO v_run_id;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    IF v_rows <> 1 THEN
        RAISE EXCEPTION 'task claim lost';
    END IF;
    INSERT INTO public.agent_approvals (tenant_id, run_id, task_id, reason)
    VALUES (p_tenant_id, v_run_id, p_task_id, COALESCE(p_reason, ''))
    RETURNING id INTO v_approval_id;
    RETURN v_approval_id;
END $$;
REVOKE ALL ON FUNCTION public.park_agent_task_for_approval(uuid, uuid, text, int, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.park_agent_task_for_approval(uuid, uuid, text, int, text) TO validexia_app;
ALTER FUNCTION public.park_agent_task_for_approval(uuid, uuid, text, int, text) OWNER TO validexia_agents_owner;

-- Convergent run creation --------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.create_agent_run(
    p_tenant_id uuid, p_agent_key text, p_objective text, p_mode text,
    p_initiated_by uuid, p_idempotency_key text, p_context jsonb)
RETURNS TABLE (run_id uuid, was_created boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_agent_id uuid; v_run_id uuid;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF p_idempotency_key IS NOT NULL THEN
        SELECT r.id INTO v_run_id FROM public.agent_runs r
        WHERE r.tenant_id = p_tenant_id AND r.idempotency_key = p_idempotency_key;
        IF v_run_id IS NOT NULL THEN
            RETURN QUERY SELECT v_run_id, false;
            RETURN;
        END IF;
    END IF;
    SELECT d.id INTO v_agent_id FROM public.agent_definitions d
    WHERE d.tenant_id = p_tenant_id AND d.agent_key = p_agent_key AND d.status = 'active';
    IF v_agent_id IS NULL THEN
        RAISE EXCEPTION 'unknown or inactive agent definition: %', p_agent_key;
    END IF;
    BEGIN
        INSERT INTO public.agent_runs
            (tenant_id, agent_id, objective, mode, initiated_by, idempotency_key, context)
        VALUES (p_tenant_id, v_agent_id, p_objective, p_mode, p_initiated_by,
                p_idempotency_key, COALESCE(p_context, '{}'::jsonb))
        RETURNING id INTO v_run_id;
    EXCEPTION WHEN unique_violation THEN
        -- A concurrent creator won the race for this idempotency key between
        -- our SELECT and INSERT. Converge on the winner instead of erroring.
        SELECT r.id INTO v_run_id FROM public.agent_runs r
        WHERE r.tenant_id = p_tenant_id AND r.idempotency_key = p_idempotency_key;
        IF v_run_id IS NULL THEN
            RAISE; -- a different constraint was violated; do not swallow it
        END IF;
        RETURN QUERY SELECT v_run_id, false;
        RETURN;
    END;
    RETURN QUERY SELECT v_run_id, true;
END $$;
REVOKE ALL ON FUNCTION public.create_agent_run(uuid, text, text, text, uuid, text, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_agent_run(uuid, text, text, text, uuid, text, jsonb) TO validexia_app;
ALTER FUNCTION public.create_agent_run(uuid, text, text, text, uuid, text, jsonb) OWNER TO validexia_agents_owner;

-- Task listing also returns the stored idempotency key --------------------------------
DROP FUNCTION public.list_agent_tasks(uuid, uuid);
CREATE FUNCTION public.list_agent_tasks(p_tenant_id uuid, p_run_id uuid)
RETURNS TABLE (id uuid, seq int, tool_key text, risk_class text, state text,
               input jsonb, output jsonb, error text, attempts int,
               idempotency_key text)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
        SELECT t.id, t.seq, t.tool_key, t.risk_class, t.state, t.input, t.output,
               t.error, t.attempts, t.idempotency_key
        FROM public.agent_tasks t
        WHERE t.tenant_id = p_tenant_id AND t.run_id = p_run_id
        ORDER BY t.seq;
END $$;
REVOKE ALL ON FUNCTION public.list_agent_tasks(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_agent_tasks(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.list_agent_tasks(uuid, uuid) OWNER TO validexia_agents_owner;
