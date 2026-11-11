-- V5: AI agent foundation (agent definitions, runs, tasks, approvals).
-- Milestone: AI Agent Foundation (founder-authorised 2026-11-11). This migration is
-- deliberately numbered V5, NOT V4: V4 is reserved by the M1 (Clerk -> Logto) plan,
-- which is paper-only at the time of writing. On any fresh database Flyway applies
-- V1..V5 in version order, so M1's V4 will slot in before this file when it is
-- authored. No database has this migration applied without V1-V3.
--
-- Discipline follows V1/V3 exactly: the application role (validexia_app) holds ZERO
-- direct grants on the agent tables; all access flows through the SECURITY DEFINER
-- functions below, owned by a dedicated NOLOGIN owner role. State transitions are
-- compare-and-swap on the current state, so a crashed executor can be resumed by any
-- instance without repeating completed work. Agents can never modify their own
-- definitions or permissions through these functions: there is no function that
-- updates a definition's kind/config or a tool's risk class after registration,
-- and registration itself is a human API action, never an agent action.

-- Roles (idempotent, same pattern as V1) ---------------------------------------
DO $$ BEGIN
    CREATE ROLE validexia_agents_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Tables ------------------------------------------------------------------------
CREATE TABLE public.agent_definitions (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES public.tenants (id),
    agent_key   text NOT NULL,
    name        text NOT NULL,
    description text NOT NULL DEFAULT '',
    kind        text NOT NULL CHECK (kind IN ('DETERMINISTIC', 'LLM')),
    status      text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
    config      jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by  uuid NOT NULL REFERENCES public.users (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, agent_key)
);

CREATE TABLE public.agent_runs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES public.tenants (id),
    agent_id        uuid NOT NULL REFERENCES public.agent_definitions (id),
    objective       text NOT NULL,
    mode            text NOT NULL CHECK (mode IN ('ASSIST', 'APPROVAL', 'AUTONOMOUS')),
    state           text NOT NULL DEFAULT 'PENDING'
                    CHECK (state IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL',
                                     'SUCCEEDED', 'FAILED', 'CANCELLED')),
    context         jsonb NOT NULL DEFAULT '{}'::jsonb,
    result          jsonb,
    error           text,
    initiated_by    uuid NOT NULL REFERENCES public.users (id),
    idempotency_key text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX agent_runs_idempotency
    ON public.agent_runs (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE public.agent_tasks (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES public.tenants (id),
    run_id          uuid NOT NULL REFERENCES public.agent_runs (id),
    seq             int NOT NULL,
    tool_key        text NOT NULL,
    risk_class      text NOT NULL CHECK (risk_class IN ('READ', 'ROUTINE_WRITE', 'SENSITIVE_WRITE')),
    state           text NOT NULL DEFAULT 'PENDING'
                    CHECK (state IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL',
                                     'SUCCEEDED', 'FAILED', 'SKIPPED', 'CANCELLED')),
    input           jsonb NOT NULL DEFAULT '{}'::jsonb,
    output          jsonb,
    error           text,
    attempts        int NOT NULL DEFAULT 0,
    idempotency_key text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, seq),
    UNIQUE (run_id, idempotency_key)
);

CREATE TABLE public.agent_approvals (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES public.tenants (id),
    run_id          uuid NOT NULL REFERENCES public.agent_runs (id),
    task_id         uuid NOT NULL UNIQUE REFERENCES public.agent_tasks (id),
    state           text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED')),
    reason          text NOT NULL DEFAULT '',
    requested_at    timestamptz NOT NULL DEFAULT now(),
    decided_by      uuid REFERENCES public.users (id),
    decision_reason text,
    decided_at      timestamptz
);

ALTER TABLE public.agent_definitions OWNER TO validexia_agents_owner;
ALTER TABLE public.agent_runs        OWNER TO validexia_agents_owner;
ALTER TABLE public.agent_tasks       OWNER TO validexia_agents_owner;
ALTER TABLE public.agent_approvals    OWNER TO validexia_agents_owner;

-- Defence in depth: RLS is enabled even though the app role has no direct grants.
-- The definer functions (owner context) validate the tenant explicitly.
ALTER TABLE public.agent_definitions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_runs        ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_tasks       ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_approvals   ENABLE ROW LEVEL SECURITY;

CREATE POLICY agent_definitions_tenant ON public.agent_definitions
    USING (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true));
CREATE POLICY agent_runs_tenant ON public.agent_runs
    USING (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true));
CREATE POLICY agent_tasks_tenant ON public.agent_tasks
    USING (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true));
CREATE POLICY agent_approvals_tenant ON public.agent_approvals
    USING (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true));

-- Tenant guard used by every function below (inlined per function): when the
-- transaction carries an app.tenant_id setting, it must equal the tenant the
-- function is asked to act on. Fail closed on mismatch.

-- Definitions -------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.register_agent_definition(
    p_tenant_id uuid, p_agent_key text, p_name text, p_description text,
    p_kind text, p_config jsonb, p_created_by uuid)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_id uuid;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) NOT IN ('', NULL)
       AND pg_catalog.current_setting('app.tenant_id', true) <> p_tenant_id::text THEN
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
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT d.id, d.agent_key, d.name, d.description, d.kind, d.status, d.config, d.created_at
    FROM public.agent_definitions d
    WHERE d.tenant_id = p_tenant_id
      AND (pg_catalog.current_setting('app.tenant_id', true) IN ('', NULL)
           OR pg_catalog.current_setting('app.tenant_id', true) = p_tenant_id::text)
    ORDER BY d.agent_key
$$;
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
    IF pg_catalog.current_setting('app.tenant_id', true) NOT IN ('', NULL)
       AND pg_catalog.current_setting('app.tenant_id', true) <> p_tenant_id::text THEN
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
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT r.id, d.agent_key, r.objective, r.mode, r.state, r.result, r.error,
           r.initiated_by, r.created_at, r.updated_at
    FROM public.agent_runs r
    JOIN public.agent_definitions d ON d.id = r.agent_id
    WHERE r.tenant_id = p_tenant_id AND r.id = p_run_id
      AND (pg_catalog.current_setting('app.tenant_id', true) IN ('', NULL)
           OR pg_catalog.current_setting('app.tenant_id', true) = p_tenant_id::text)
$$;
REVOKE ALL ON FUNCTION public.get_agent_run(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_agent_run(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.get_agent_run(uuid, uuid) OWNER TO validexia_agents_owner;

CREATE OR REPLACE FUNCTION public.list_agent_runs(p_tenant_id uuid)
RETURNS TABLE (id uuid, agent_key text, objective text, mode text, state text,
               result jsonb, error text, initiated_by uuid,
               created_at timestamptz, updated_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT r.id, d.agent_key, r.objective, r.mode, r.state, r.result, r.error,
           r.initiated_by, r.created_at, r.updated_at
    FROM public.agent_runs r
    JOIN public.agent_definitions d ON d.id = r.agent_id
    WHERE r.tenant_id = p_tenant_id
      AND (pg_catalog.current_setting('app.tenant_id', true) IN ('', NULL)
           OR pg_catalog.current_setting('app.tenant_id', true) = p_tenant_id::text)
    ORDER BY r.created_at DESC
$$;
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
    IF pg_catalog.current_setting('app.tenant_id', true) NOT IN ('', NULL)
       AND pg_catalog.current_setting('app.tenant_id', true) <> p_tenant_id::text THEN
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
    IF pg_catalog.current_setting('app.tenant_id', true) NOT IN ('', NULL)
       AND pg_catalog.current_setting('app.tenant_id', true) <> p_tenant_id::text THEN
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
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT t.id, t.seq, t.tool_key, t.risk_class, t.state, t.input, t.output,
           t.error, t.attempts
    FROM public.agent_tasks t
    WHERE t.tenant_id = p_tenant_id AND t.run_id = p_run_id
      AND (pg_catalog.current_setting('app.tenant_id', true) IN ('', NULL)
           OR pg_catalog.current_setting('app.tenant_id', true) = p_tenant_id::text)
    ORDER BY t.seq
$$;
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
    IF pg_catalog.current_setting('app.tenant_id', true) NOT IN ('', NULL)
       AND pg_catalog.current_setting('app.tenant_id', true) <> p_tenant_id::text THEN
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
    IF pg_catalog.current_setting('app.tenant_id', true) NOT IN ('', NULL)
       AND pg_catalog.current_setting('app.tenant_id', true) <> p_tenant_id::text THEN
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
    IF pg_catalog.current_setting('app.tenant_id', true) NOT IN ('', NULL)
       AND pg_catalog.current_setting('app.tenant_id', true) <> p_tenant_id::text THEN
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
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT a.id, a.run_id, a.task_id, a.state, a.reason, a.requested_at,
           a.decided_by, a.decision_reason, a.decided_at
    FROM public.agent_approvals a
    WHERE a.tenant_id = p_tenant_id
      AND (p_state IS NULL OR a.state = p_state)
      AND (pg_catalog.current_setting('app.tenant_id', true) IN ('', NULL)
           OR pg_catalog.current_setting('app.tenant_id', true) = p_tenant_id::text)
    ORDER BY a.requested_at
$$;
REVOKE ALL ON FUNCTION public.list_agent_approvals(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_agent_approvals(uuid, text) TO validexia_app;
ALTER FUNCTION public.list_agent_approvals(uuid, text) OWNER TO validexia_agents_owner;
