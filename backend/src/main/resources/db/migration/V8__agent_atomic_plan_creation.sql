-- V8: Atomic plan initialization for agent runs + lease-duration validation.
--
-- Follow-up to the PR #3 review of the V7 concurrency work. The race it closes:
-- createRun committed the run in one transaction, the engine then persisted
-- the plan through separate addTask transactions. A duplicate creator using
-- the same idempotency key received the run with was_created=false and went
-- straight to execution; if it arrived before the winner had persisted any
-- task, it saw an empty task list — and an empty list trivially satisfies
-- "all tasks settled", so it could mark the run SUCCEEDED before the plan
-- existed. The winner then persisted tasks into a terminal run that never
-- executed them.
--
-- The fix is a lifecycle change, not another lock:
-- 1. agent_runs gains plan_ready. A run is only executable/settles only when
--    its complete plan is committed. Readiness is explicit state, never
--    inferred from task count — so a legitimately empty plan (plan_ready =
--    true, zero tasks) is distinguishable from an unfinished one.
-- 2. create_agent_run_with_plan persists the run AND its complete task plan
--    in a single function call — one transaction. A duplicate caller can only
--    ever observe a run whose plan is already committed, because the plan is
--    part of the same commit that makes the run visible. A planner failure
--    happens before this call (the engine plans first), so it leaves no run
--    at all; a failure inside the call rolls the whole thing back.
-- 3. The legacy create_agent_run leaves plan_ready = false: a run created
--    without an atomic plan is never executable and can never be marked
--    SUCCEEDED by the engine. Pre-V8 runs are backfilled to ready — their
--    creation windows closed before this migration ran.
-- 4. claim_agent_task validates its lease duration: positive and bounded
--    (1..3600 seconds). An unbounded or non-positive lease would silently
--    break the takeover protocol.
--
-- V5/V6/V7 are not edited; all functions keep the V6 fail-closed tenant guard.

ALTER TABLE public.agent_runs ADD COLUMN plan_ready boolean NOT NULL DEFAULT false;
UPDATE public.agent_runs SET plan_ready = true;

-- Run reads also return plan readiness -----------------------------------------
DROP FUNCTION public.get_agent_run(uuid, uuid);
CREATE FUNCTION public.get_agent_run(p_tenant_id uuid, p_run_id uuid)
RETURNS TABLE (id uuid, agent_key text, objective text, mode text, state text,
               result jsonb, error text, initiated_by uuid,
               created_at timestamptz, updated_at timestamptz, plan_ready boolean)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
        SELECT r.id, d.agent_key, r.objective, r.mode, r.state, r.result, r.error,
               r.initiated_by, r.created_at, r.updated_at, r.plan_ready
        FROM public.agent_runs r
        JOIN public.agent_definitions d ON d.id = r.agent_id
        WHERE r.tenant_id = p_tenant_id AND r.id = p_run_id;
END $$;
REVOKE ALL ON FUNCTION public.get_agent_run(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_agent_run(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.get_agent_run(uuid, uuid) OWNER TO validexia_agents_owner;

DROP FUNCTION public.list_agent_runs(uuid);
CREATE FUNCTION public.list_agent_runs(p_tenant_id uuid)
RETURNS TABLE (id uuid, agent_key text, objective text, mode text, state text,
               result jsonb, error text, initiated_by uuid,
               created_at timestamptz, updated_at timestamptz, plan_ready boolean)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
        SELECT r.id, d.agent_key, r.objective, r.mode, r.state, r.result, r.error,
               r.initiated_by, r.created_at, r.updated_at, r.plan_ready
        FROM public.agent_runs r
        JOIN public.agent_definitions d ON d.id = r.agent_id
        WHERE r.tenant_id = p_tenant_id
        ORDER BY r.created_at DESC;
END $$;
REVOKE ALL ON FUNCTION public.list_agent_runs(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_agent_runs(uuid) TO validexia_app;
ALTER FUNCTION public.list_agent_runs(uuid) OWNER TO validexia_agents_owner;

-- Lease-duration validation ------------------------------------------------------
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
    IF p_lease_seconds IS NULL OR p_lease_seconds < 1 OR p_lease_seconds > 3600 THEN
        RAISE EXCEPTION 'lease seconds out of range (1..3600): %', p_lease_seconds;
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

-- Atomic run + plan creation -------------------------------------------------------
-- p_tasks is a JSON array of {"toolKey": ..., "riskClass": ..., "input": {...}}
-- in plan order. Task sequence numbers and idempotency keys are derived here
-- from the new run's identity (run_id || ':' || seq) — stable across attempts
-- and takeovers, never per-execution. The whole creation is one transaction:
-- either the run and its complete plan commit together, or nothing does.
CREATE FUNCTION public.create_agent_run_with_plan(
    p_tenant_id uuid, p_agent_key text, p_objective text, p_mode text,
    p_initiated_by uuid, p_idempotency_key text, p_context jsonb, p_tasks jsonb)
RETURNS TABLE (run_id uuid, was_created boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_agent_id uuid; v_run_id uuid; v_task jsonb; v_seq int;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF p_tasks IS NULL OR pg_catalog.jsonb_typeof(p_tasks) <> 'array' THEN
        RAISE EXCEPTION 'plan must be a JSON array of tasks';
    END IF;
    IF p_idempotency_key IS NOT NULL THEN
        SELECT r.id INTO v_run_id FROM public.agent_runs r
        WHERE r.tenant_id = p_tenant_id AND r.idempotency_key = p_idempotency_key;
        IF v_run_id IS NOT NULL THEN
            -- The existing run's plan committed atomically with it; a duplicate
            -- caller can never observe a partially-initialized plan.
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
            (tenant_id, agent_id, objective, mode, initiated_by, idempotency_key,
             context, plan_ready)
        VALUES (p_tenant_id, v_agent_id, p_objective, p_mode, p_initiated_by,
                p_idempotency_key, COALESCE(p_context, '{}'::jsonb), true)
        RETURNING id INTO v_run_id;
        v_seq := 0;
        FOR v_task IN SELECT * FROM pg_catalog.jsonb_array_elements(p_tasks) LOOP
            v_seq := v_seq + 1;
            INSERT INTO public.agent_tasks
                (tenant_id, run_id, seq, tool_key, risk_class, input, idempotency_key)
            VALUES (p_tenant_id, v_run_id, v_seq,
                    v_task ->> 'toolKey',
                    v_task ->> 'riskClass',
                    COALESCE(v_task -> 'input', '{}'::jsonb),
                    v_run_id::text || ':' || v_seq);
        END LOOP;
    EXCEPTION WHEN unique_violation THEN
        -- A concurrent creator won the race for this idempotency key. Our run
        -- and any tasks we inserted roll back with the block; converge on the
        -- winner, whose plan is already fully committed.
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
REVOKE ALL ON FUNCTION public.create_agent_run_with_plan(uuid, text, text, text, uuid, text, jsonb, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_agent_run_with_plan(uuid, text, text, text, uuid, text, jsonb, jsonb) TO validexia_app;
ALTER FUNCTION public.create_agent_run_with_plan(uuid, text, text, text, uuid, text, jsonb, jsonb) OWNER TO validexia_agents_owner;
