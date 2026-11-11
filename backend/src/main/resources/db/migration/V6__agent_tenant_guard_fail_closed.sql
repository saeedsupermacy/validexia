-- V6: Agent tenant guards fail closed + FORCE ROW LEVEL SECURITY.
--
-- Correction to V5 (security review, 2026-11-11). V5 is already merged and may
-- already be applied somewhere, so it is NOT edited; this forward migration
-- replaces its functions and hardens its tables.
--
-- The defect: V5's mutation functions rejected a mismatched tenant context
-- only when app.tenant_id was non-NULL and non-empty. A missing or empty
-- tenant context was allowed to proceed, and the five read functions
-- explicitly permitted an empty context. A caller whose transaction failed
-- to establish its tenant (a future background worker, a misconfigured
-- service, a hand-rolled script) could therefore act on any tenant by
-- simply passing its id.
--
-- The rule now, for every agent function: the transaction's app.tenant_id
-- must be EXACTLY the tenant the function is asked to act on. Missing,
-- empty, or different — execution is rejected. The comparison is NULL-safe
-- (IS DISTINCT FROM), so no context value can slip through SQL
-- three-valued logic.
--
-- In the running application the tenant transaction manager sets
-- app.tenant_id transaction-locally on every transaction (the verified
-- tenant, or '' when there is none), so legitimate flows always carry an
-- exact context; only context-less execution changes behaviour (it now
-- fails instead of silently succeeding).
--
-- Additionally the four agent tables get FORCE ROW LEVEL SECURITY (the
-- same treatment V2 gave audit_events): the dedicated owner role
-- validexia_agents_owner, whose identity the SECURITY DEFINER functions
-- run under, becomes subject to the tenant policies itself. Defence in
-- depth — even a future function that forgot its WHERE clause could not
-- see or write another tenant's rows.

-- The guard, inlined in every function below:
--   IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
--       RAISE EXCEPTION 'tenant context mismatch';
--   END IF;

-- Definitions -------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.register_agent_definition(
    p_tenant_id uuid, p_agent_key text, p_name text, p_description text,
    p_kind text, p_config jsonb, p_created_by uuid)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_id uuid;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    INSERT INTO public.agent_definitions
        (tenant_id, agent_key, name, description, kind, config, created_by)
    VALUES (p_tenant_id, p_agent_key, p_name, p_description, p_kind,
            COALESCE(p_config, '{}'::jsonb), p_created_by)
    ON CONFLICT (tenant_id, agent_key) DO UPDATE
        SET name = EXCLUDED.name, description = EXCLUDED.description,
            kind = EXCLUDED.kind, config = EXCLUDED.config,
            updated_at = pg_catalog.now()
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;
REVOKE ALL ON FUNCTION public.register_agent_definition(uuid, text, text, text, text, jsonb, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.register_agent_definition(uuid, text, text, text, text, jsonb, uuid) TO validexia_app;
ALTER FUNCTION public.register_agent_definition(uuid, text, text, text, text, jsonb, uuid) OWNER TO validexia_agents_owner;

CREATE OR REPLACE FUNCTION public.list_agent_definitions(p_tenant_id uuid)
RETURNS TABLE (id uuid, agent_key text, name text, description text, kind text,
               status text, config jsonb, created_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
        SELECT d.id, d.agent_key, d.name, d.description, d.kind, d.status, d.config, d.created_at
        FROM public.agent_definitions d
        WHERE d.tenant_id = p_tenant_id
        ORDER BY d.agent_key;
END $$;
REVOKE ALL ON FUNCTION public.list_agent_definitions(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_agent_definitions(uuid) TO validexia_app;
ALTER FUNCTION public.list_agent_definitions(uuid) OWNER TO validexia_agents_owner;

-- Runs --------------------------------------------------------------------------
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
    INSERT INTO public.agent_runs
        (tenant_id, agent_id, objective, mode, initiated_by, idempotency_key, context)
    VALUES (p_tenant_id, v_agent_id, p_objective, p_mode, p_initiated_by,
            p_idempotency_key, COALESCE(p_context, '{}'::jsonb))
    RETURNING id INTO v_run_id;
    RETURN QUERY SELECT v_run_id, true;
END $$;
REVOKE ALL ON FUNCTION public.create_agent_run(uuid, text, text, text, uuid, text, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_agent_run(uuid, text, text, text, uuid, text, jsonb) TO validexia_app;
ALTER FUNCTION public.create_agent_run(uuid, text, text, text, uuid, text, jsonb) OWNER TO validexia_agents_owner;

CREATE OR REPLACE FUNCTION public.get_agent_run(p_tenant_id uuid, p_run_id uuid)
RETURNS TABLE (id uuid, agent_key text, objective text, mode text, state text,
               result jsonb, error text, initiated_by uuid,
               created_at timestamptz, updated_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
        SELECT r.id, d.agent_key, r.objective, r.mode, r.state, r.result, r.error,
               r.initiated_by, r.created_at, r.updated_at
        FROM public.agent_runs r
        JOIN public.agent_definitions d ON d.id = r.agent_id
        WHERE r.tenant_id = p_tenant_id AND r.id = p_run_id;
END $$;
REVOKE ALL ON FUNCTION public.get_agent_run(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_agent_run(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.get_agent_run(uuid, uuid) OWNER TO validexia_agents_owner;

CREATE OR REPLACE FUNCTION public.list_agent_runs(p_tenant_id uuid)
RETURNS TABLE (id uuid, agent_key text, objective text, mode text, state text,
               result jsonb, error text, initiated_by uuid,
               created_at timestamptz, updated_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
        SELECT r.id, d.agent_key, r.objective, r.mode, r.state, r.result, r.error,
               r.initiated_by, r.created_at, r.updated_at
        FROM public.agent_runs r
        JOIN public.agent_definitions d ON d.id = r.agent_id
        WHERE r.tenant_id = p_tenant_id
        ORDER BY r.created_at DESC;
END $$;
REVOKE ALL ON FUNCTION public.list_agent_runs(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_agent_runs(uuid) TO validexia_app;
ALTER FUNCTION public.list_agent_runs(uuid) OWNER TO validexia_agents_owner;

CREATE OR REPLACE FUNCTION public.transition_agent_run(
    p_tenant_id uuid, p_run_id uuid, p_expected text, p_target text,
    p_result jsonb, p_error text)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_rows int;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    UPDATE public.agent_runs
    SET state = p_target,
        result = COALESCE(p_result, result),
        error = COALESCE(p_error, error),
        updated_at = pg_catalog.now()
    WHERE id = p_run_id AND tenant_id = p_tenant_id AND state = p_expected;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RETURN v_rows = 1;
END $$;
REVOKE ALL ON FUNCTION public.transition_agent_run(uuid, uuid, text, text, jsonb, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.transition_agent_run(uuid, uuid, text, text, jsonb, text) TO validexia_app;
ALTER FUNCTION public.transition_agent_run(uuid, uuid, text, text, jsonb, text) OWNER TO validexia_agents_owner;

-- Tasks -------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.add_agent_task(
    p_tenant_id uuid, p_run_id uuid, p_seq int, p_tool_key text,
    p_risk_class text, p_input jsonb, p_idempotency_key text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_id uuid;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.agent_runs
                   WHERE id = p_run_id AND tenant_id = p_tenant_id) THEN
        RAISE EXCEPTION 'run not found in tenant';
    END IF;
    INSERT INTO public.agent_tasks
        (tenant_id, run_id, seq, tool_key, risk_class, input, idempotency_key)
    VALUES (p_tenant_id, p_run_id, p_seq, p_tool_key, p_risk_class,
            COALESCE(p_input, '{}'::jsonb), p_idempotency_key)
    ON CONFLICT (run_id, idempotency_key) DO UPDATE SET tool_key = EXCLUDED.tool_key
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;
REVOKE ALL ON FUNCTION public.add_agent_task(uuid, uuid, int, text, text, jsonb, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.add_agent_task(uuid, uuid, int, text, text, jsonb, text) TO validexia_app;
ALTER FUNCTION public.add_agent_task(uuid, uuid, int, text, text, jsonb, text) OWNER TO validexia_agents_owner;

CREATE OR REPLACE FUNCTION public.list_agent_tasks(p_tenant_id uuid, p_run_id uuid)
RETURNS TABLE (id uuid, seq int, tool_key text, risk_class text, state text,
               input jsonb, output jsonb, error text, attempts int)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
        SELECT t.id, t.seq, t.tool_key, t.risk_class, t.state, t.input, t.output,
               t.error, t.attempts
        FROM public.agent_tasks t
        WHERE t.tenant_id = p_tenant_id AND t.run_id = p_run_id
        ORDER BY t.seq;
END $$;
REVOKE ALL ON FUNCTION public.list_agent_tasks(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_agent_tasks(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.list_agent_tasks(uuid, uuid) OWNER TO validexia_agents_owner;

CREATE OR REPLACE FUNCTION public.transition_agent_task(
    p_tenant_id uuid, p_task_id uuid, p_expected text, p_target text,
    p_output jsonb, p_error text)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_rows int;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    UPDATE public.agent_tasks
    SET state = p_target,
        output = COALESCE(p_output, output),
        error = COALESCE(p_error, error),
        attempts = attempts + CASE WHEN p_target = 'RUNNING' THEN 1 ELSE 0 END,
        updated_at = pg_catalog.now()
    WHERE id = p_task_id AND tenant_id = p_tenant_id AND state = p_expected;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RETURN v_rows = 1;
END $$;
REVOKE ALL ON FUNCTION public.transition_agent_task(uuid, uuid, text, text, jsonb, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.transition_agent_task(uuid, uuid, text, text, jsonb, text) TO validexia_app;
ALTER FUNCTION public.transition_agent_task(uuid, uuid, text, text, jsonb, text) OWNER TO validexia_agents_owner;

-- Approvals ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.request_agent_approval(
    p_tenant_id uuid, p_task_id uuid, p_reason text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_run_id uuid; v_approval_id uuid; v_rows int;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    SELECT run_id INTO v_run_id FROM public.agent_tasks
    WHERE id = p_task_id AND tenant_id = p_tenant_id;
    IF v_run_id IS NULL THEN
        RAISE EXCEPTION 'task not found in tenant';
    END IF;
    UPDATE public.agent_tasks
    SET state = 'WAITING_APPROVAL', updated_at = pg_catalog.now()
    WHERE id = p_task_id AND tenant_id = p_tenant_id AND state = 'PENDING';
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    IF v_rows <> 1 THEN
        RAISE EXCEPTION 'task is not PENDING; approval cannot be requested';
    END IF;
    INSERT INTO public.agent_approvals (tenant_id, run_id, task_id, reason)
    VALUES (p_tenant_id, v_run_id, p_task_id, COALESCE(p_reason, ''))
    RETURNING id INTO v_approval_id;
    RETURN v_approval_id;
END $$;
REVOKE ALL ON FUNCTION public.request_agent_approval(uuid, uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.request_agent_approval(uuid, uuid, text) TO validexia_app;
ALTER FUNCTION public.request_agent_approval(uuid, uuid, text) OWNER TO validexia_agents_owner;

CREATE OR REPLACE FUNCTION public.decide_agent_approval(
    p_tenant_id uuid, p_approval_id uuid, p_decision text,
    p_decided_by uuid, p_reason text)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_task_id uuid; v_rows int;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF p_decision NOT IN ('APPROVED', 'REJECTED') THEN
        RAISE EXCEPTION 'decision must be APPROVED or REJECTED';
    END IF;
    UPDATE public.agent_approvals
    SET state = p_decision, decided_by = p_decided_by,
        decision_reason = p_reason, decided_at = pg_catalog.now()
    WHERE id = p_approval_id AND tenant_id = p_tenant_id AND state = 'PENDING'
    RETURNING task_id INTO v_task_id;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    IF v_rows <> 1 THEN
        RETURN false;
    END IF;
    IF p_decision = 'APPROVED' THEN
        UPDATE public.agent_tasks
        SET state = 'PENDING', updated_at = pg_catalog.now()
        WHERE id = v_task_id AND tenant_id = p_tenant_id AND state = 'WAITING_APPROVAL';
    ELSE
        UPDATE public.agent_tasks
        SET state = 'FAILED', error = 'Approval rejected by a human reviewer',
            updated_at = pg_catalog.now()
        WHERE id = v_task_id AND tenant_id = p_tenant_id AND state = 'WAITING_APPROVAL';
    END IF;
    RETURN true;
END $$;
REVOKE ALL ON FUNCTION public.decide_agent_approval(uuid, uuid, text, uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.decide_agent_approval(uuid, uuid, text, uuid, text) TO validexia_app;
ALTER FUNCTION public.decide_agent_approval(uuid, uuid, text, uuid, text) OWNER TO validexia_agents_owner;

CREATE OR REPLACE FUNCTION public.list_agent_approvals(p_tenant_id uuid, p_state text)
RETURNS TABLE (id uuid, run_id uuid, task_id uuid, state text, reason text,
               requested_at timestamptz, decided_by uuid,
               decision_reason text, decided_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
        SELECT a.id, a.run_id, a.task_id, a.state, a.reason, a.requested_at,
               a.decided_by, a.decision_reason, a.decided_at
        FROM public.agent_approvals a
        WHERE a.tenant_id = p_tenant_id
          AND (p_state IS NULL OR a.state = p_state)
        ORDER BY a.requested_at;
END $$;
REVOKE ALL ON FUNCTION public.list_agent_approvals(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_agent_approvals(uuid, text) TO validexia_app;
ALTER FUNCTION public.list_agent_approvals(uuid, text) OWNER TO validexia_agents_owner;

-- FORCE ROW LEVEL SECURITY: the owner role is subject to the tenant policies
-- too, so definer functions get RLS as a second, independent barrier behind
-- their explicit guards and WHERE clauses. (Same treatment as audit_events
-- in V2. Superusers — migrations, test fixtures — are unaffected; the app
-- role has no direct grants either way.)
ALTER TABLE public.agent_definitions FORCE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runs        FORCE ROW LEVEL SECURITY;
ALTER TABLE public.agent_tasks       FORCE ROW LEVEL SECURITY;
ALTER TABLE public.agent_approvals   FORCE ROW LEVEL SECURITY;
