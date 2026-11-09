-- V2: append-only, hash-chained audit events.
-- Ordering is DATABASE-enforced: a BEFORE INSERT trigger (SECURITY DEFINER, owned
-- by validexia_audit_owner) serializes appends per tenant on the chain-head row
-- lock, assigns seq/prev_hash, and computes event_hash. Callers cannot forge
-- ordering: the trigger overwrites NEW.seq / NEW.prev_hash / NEW.event_hash.
-- validexia_app holds INSERT+SELECT on audit_events only, and NO grant of any
-- kind on audit_chain_heads. Tamper-EVIDENT, not tamper-proof (M02-1 v2.6).

CREATE TABLE public.audit_chain_heads (
    tenant_key text PRIMARY KEY,
    last_seq   bigint NOT NULL,
    last_hash  bytea  NOT NULL
);

CREATE TABLE public.audit_events (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_key  text NOT NULL,
    seq         bigint NOT NULL,
    actor_type  text NOT NULL,
    actor_id    text,
    action      text NOT NULL,
    entity_type text NOT NULL,
    entity_id   text,
    details     jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL DEFAULT now(),
    prev_hash   bytea NOT NULL,
    event_hash  bytea NOT NULL,
    UNIQUE (tenant_key, seq),
    UNIQUE (tenant_key, prev_hash)
);

ALTER TABLE public.audit_chain_heads OWNER TO validexia_audit_owner;
ALTER TABLE public.audit_events      OWNER TO validexia_audit_owner;

ALTER TABLE public.audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_events FORCE ROW LEVEL SECURITY;

-- App role: only its own tenant's events; SYSTEM-bucket inserts allowed only when
-- no tenant context is set (pre-tenant authentication events). A never-set custom
-- GUC reads back as NULL from current_setting(..., true) (verified on PG 16), so
-- the unset test must coalesce; NULL never equals a tenant key: fail closed.
CREATE POLICY audit_select_app ON public.audit_events
    FOR SELECT TO validexia_app
    USING (tenant_key = pg_catalog.current_setting('app.tenant_id', true));

CREATE POLICY audit_insert_app ON public.audit_events
    FOR INSERT TO validexia_app
    WITH CHECK (tenant_key = pg_catalog.current_setting('app.tenant_id', true)
                OR (tenant_key = 'SYSTEM'
                    AND (pg_catalog.current_setting('app.tenant_id', true) IS NULL
                         OR pg_catalog.current_setting('app.tenant_id', true) = '')));

-- The audit owner role reaches rows only through its definer functions.
CREATE POLICY audit_owner_all ON public.audit_events
    FOR ALL TO validexia_audit_owner
    USING (true) WITH CHECK (true);

GRANT SELECT, INSERT ON public.audit_events TO validexia_app;

-- Canonical hash serialization (M02-1 v2.5, normative):
--   SHA-256 over: prev_hash (raw 32 bytes)
--     || enc(tenant_key) || seq (8-byte BE) || enc(actor_type) || enc(actor_id)
--     || enc(action) || enc(entity_type) || enc(entity_id) || enc(details::text)
--     || created_at (8-byte BE, epoch microseconds UTC)
--   enc(text) = 0x00 if NULL, else 0x01 || 4-byte BE length || UTF-8 bytes.
--   tenant_key is 'SYSTEM' or a canonical lowercase hyphenated UUID string.
CREATE OR REPLACE FUNCTION public.audit_encode_text(p_value text)
RETURNS bytea
LANGUAGE plpgsql IMMUTABLE SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF p_value IS NULL THEN
        RETURN '\x00'::bytea;
    END IF;
    RETURN '\x01'::bytea
        || pg_catalog.decode(pg_catalog.lpad(pg_catalog.to_hex(pg_catalog.octet_length(pg_catalog.convert_to(p_value, 'UTF8'))), 8, '0'), 'hex')
        || pg_catalog.convert_to(p_value, 'UTF8');
END $$;
REVOKE ALL ON FUNCTION public.audit_encode_text(text) FROM PUBLIC;
ALTER FUNCTION public.audit_encode_text(text) OWNER TO validexia_audit_owner;

CREATE OR REPLACE FUNCTION public.audit_event_hash(
    p_prev_hash bytea, p_tenant_key text, p_seq bigint, p_actor_type text,
    p_actor_id text, p_action text, p_entity_type text, p_entity_id text,
    p_details jsonb, p_created_at timestamptz)
RETURNS bytea
LANGUAGE plpgsql IMMUTABLE SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_bytes bytea;
BEGIN
    v_bytes := p_prev_hash
        || public.audit_encode_text(p_tenant_key)
        || pg_catalog.decode(pg_catalog.lpad(pg_catalog.to_hex(p_seq), 16, '0'), 'hex')
        || public.audit_encode_text(p_actor_type)
        || public.audit_encode_text(p_actor_id)
        || public.audit_encode_text(p_action)
        || public.audit_encode_text(p_entity_type)
        || public.audit_encode_text(p_entity_id)
        || public.audit_encode_text(p_details::text)
        || pg_catalog.decode(pg_catalog.lpad(pg_catalog.to_hex((extract(epoch FROM p_created_at) * 1000000)::bigint), 16, '0'), 'hex');
    RETURN pg_catalog.sha256(v_bytes);
END $$;
REVOKE ALL ON FUNCTION public.audit_event_hash(bytea, text, bigint, text, text, text, text, text, jsonb, timestamptz) FROM PUBLIC;
ALTER FUNCTION public.audit_event_hash(bytea, text, bigint, text, text, text, text, text, jsonb, timestamptz) OWNER TO validexia_audit_owner;

-- Chain trigger: SECURITY DEFINER as validexia_audit_owner (so it alone can move
-- the chain head). EXECUTE is revoked from PUBLIC and never granted to
-- validexia_app: PostgreSQL's trigger manager invokes it without the inserting
-- role needing EXECUTE. The function aborts if invoked outside a trigger.
CREATE OR REPLACE FUNCTION public.audit_events_chain_trigger()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_last_seq bigint; v_last_hash bytea;
BEGIN
    IF TG_OP <> 'INSERT' OR TG_TABLE_NAME <> 'audit_events' THEN
        RAISE EXCEPTION 'audit chain trigger invoked out of context';
    END IF;
    INSERT INTO public.audit_chain_heads (tenant_key, last_seq, last_hash)
    VALUES (NEW.tenant_key, 0, pg_catalog.decode(pg_catalog.repeat('00', 32), 'hex'))
    ON CONFLICT (tenant_key) DO NOTHING;

    SELECT h.last_seq, h.last_hash INTO v_last_seq, v_last_hash
    FROM public.audit_chain_heads h
    WHERE h.tenant_key = NEW.tenant_key
    FOR UPDATE;

    NEW.seq := v_last_seq + 1;
    NEW.prev_hash := v_last_hash;
    NEW.event_hash := public.audit_event_hash(
        NEW.prev_hash, NEW.tenant_key, NEW.seq, NEW.actor_type, NEW.actor_id,
        NEW.action, NEW.entity_type, NEW.entity_id, NEW.details, NEW.created_at);

    UPDATE public.audit_chain_heads
    SET last_seq = NEW.seq, last_hash = NEW.event_hash
    WHERE tenant_key = NEW.tenant_key;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION public.audit_events_chain_trigger() FROM PUBLIC;
ALTER FUNCTION public.audit_events_chain_trigger() OWNER TO validexia_audit_owner;

CREATE TRIGGER audit_events_chain
    BEFORE INSERT ON public.audit_events
    FOR EACH ROW EXECUTE FUNCTION public.audit_events_chain_trigger();

-- Chain verification: the app role's only window into chain state.
CREATE OR REPLACE FUNCTION public.audit_verify_chain(p_tenant_key text)
RETURNS TABLE (ok boolean, first_bad_seq bigint)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    r record;
    v_expected_prev bytea := pg_catalog.decode(pg_catalog.repeat('00', 32), 'hex');
    v_expected_seq bigint := 0;
    v_hash bytea;
BEGIN
    FOR r IN
        SELECT * FROM public.audit_events e WHERE e.tenant_key = p_tenant_key ORDER BY e.seq
    LOOP
        v_expected_seq := v_expected_seq + 1;
        IF r.seq <> v_expected_seq OR r.prev_hash <> v_expected_prev THEN
            RETURN QUERY SELECT false, r.seq;
            RETURN;
        END IF;
        v_hash := public.audit_event_hash(r.prev_hash, r.tenant_key, r.seq, r.actor_type,
                    r.actor_id, r.action, r.entity_type, r.entity_id, r.details, r.created_at);
        IF r.event_hash <> v_hash THEN
            RETURN QUERY SELECT false, r.seq;
            RETURN;
        END IF;
        v_expected_prev := r.event_hash;
    END LOOP;
    RETURN QUERY SELECT true, NULL::bigint;
END $$;
REVOKE ALL ON FUNCTION public.audit_verify_chain(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.audit_verify_chain(text) TO validexia_app;
ALTER FUNCTION public.audit_verify_chain(text) OWNER TO validexia_audit_owner;
