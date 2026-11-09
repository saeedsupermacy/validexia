-- V1: identity directory (tenants, users, memberships) + definer access functions.
-- Design: M02-1 v2.6. The application role (validexia_app) holds ZERO direct grants
-- on identity tables; all access flows through the SECURITY DEFINER functions below.
-- All definer functions pin search_path = pg_catalog, pg_temp and qualify every
-- object reference (PostgreSQL docs: "Writing SECURITY DEFINER Functions Safely").

-- Roles (idempotent so re-runs against a shared dev cluster do not fail) --------
DO $$ BEGIN
    CREATE ROLE validexia_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE ROLE validexia_directory_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE ROLE validexia_audit_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

GRANT USAGE ON SCHEMA public TO validexia_app;

-- Directory tables -------------------------------------------------------------
CREATE TABLE public.tenants (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text NOT NULL,
    slug       text NOT NULL UNIQUE,
    status     text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'closed')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.users (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    external_subject        text NOT NULL UNIQUE,
    email                   text,
    display_name            text,
    status                  text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'deleted', 'merged')),
    banned                  boolean NOT NULL DEFAULT false,
    banned_updated_at       timestamptz NOT NULL DEFAULT '-infinity',
    locked                  boolean NOT NULL DEFAULT false,
    locked_updated_at       timestamptz NOT NULL DEFAULT '-infinity',
    deprovisioned           boolean NOT NULL DEFAULT false,
    deprovisioned_updated_at timestamptz NOT NULL DEFAULT '-infinity',
    lifecycle_updated_at    timestamptz NOT NULL DEFAULT '-infinity',
    profile_updated_at      timestamptz NOT NULL DEFAULT '-infinity',
    tokens_valid_after      timestamptz NOT NULL DEFAULT '-infinity',
    resync_claimed_at       timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.memberships (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES public.users (id),
    tenant_id  uuid NOT NULL REFERENCES public.tenants (id),
    role       text NOT NULL CHECK (role IN ('owner', 'admin', 'member')),
    status     text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'invited', 'suspended')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, tenant_id)
);

ALTER TABLE public.tenants     OWNER TO validexia_directory_owner;
ALTER TABLE public.users       OWNER TO validexia_directory_owner;
ALTER TABLE public.memberships OWNER TO validexia_directory_owner;

-- Read functions ---------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.find_user_by_subject(p_subject text)
RETURNS TABLE (user_id uuid, status text, banned boolean, locked boolean,
               deprovisioned boolean, tokens_valid_after timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT u.id, u.status, u.banned, u.locked, u.deprovisioned, u.tokens_valid_after
    FROM public.users u
    WHERE u.external_subject = p_subject
$$;
REVOKE ALL ON FUNCTION public.find_user_by_subject(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.find_user_by_subject(text) TO validexia_app;
ALTER FUNCTION public.find_user_by_subject(text) OWNER TO validexia_directory_owner;

CREATE OR REPLACE FUNCTION public.list_memberships(p_subject text)
RETURNS TABLE (tenant_id uuid, tenant_name text, role text, membership_status text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT m.tenant_id, t.name, m.role, m.status
    FROM public.users u
    JOIN public.memberships m ON m.user_id = u.id
    JOIN public.tenants t ON t.id = m.tenant_id
    WHERE u.external_subject = p_subject
$$;
REVOKE ALL ON FUNCTION public.list_memberships(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_memberships(text) TO validexia_app;
ALTER FUNCTION public.list_memberships(text) OWNER TO validexia_directory_owner;

-- Provisioning: idempotent insert-if-absent. NEVER modifies status or flags,
-- so it cannot resurrect a deleted/banned/merged user.
CREATE OR REPLACE FUNCTION public.provision_user(p_subject text, p_email text, p_display_name text)
RETURNS uuid
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_id uuid;
BEGIN
    INSERT INTO public.users (external_subject, email, display_name)
    VALUES (p_subject, p_email, p_display_name)
    ON CONFLICT (external_subject) DO NOTHING;
    SELECT u.id INTO v_id FROM public.users u WHERE u.external_subject = p_subject;
    RETURN v_id;
END $$;
REVOKE ALL ON FUNCTION public.provision_user(text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.provision_user(text, text, text) TO validexia_app;
ALTER FUNCTION public.provision_user(text, text, text) OWNER TO validexia_directory_owner;

-- Profile sync (webhook-driven): applies only at or after the stored profile ts.
CREATE OR REPLACE FUNCTION public.sync_user_profile(p_subject text, p_email text, p_display_name text, p_event_ts timestamptz)
RETURNS void
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    PERFORM public.provision_user(p_subject, p_email, p_display_name);
    UPDATE public.users u
    SET email = p_email, display_name = p_display_name,
        profile_updated_at = p_event_ts, updated_at = pg_catalog.now()
    WHERE u.external_subject = p_subject AND p_event_ts >= u.profile_updated_at;
END $$;
REVOKE ALL ON FUNCTION public.sync_user_profile(text, text, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.sync_user_profile(text, text, text, timestamptz) TO validexia_app;
ALTER FUNCTION public.sync_user_profile(text, text, text, timestamptz) OWNER TO validexia_directory_owner;

-- Lifecycle applier: convergent, per-field timestamp ordered, ties break toward
-- the more restrictive value (deletion wins status ties; "true" wins flag ties).
-- A NULL component means "this event says nothing about that field".
-- Also creates a tombstone row for an unknown subject so out-of-order delivery
-- (e.g. user.deleted before user.created) converges instead of resurrecting.
CREATE OR REPLACE FUNCTION public.apply_user_lifecycle(
    p_subject text, p_status text, p_banned boolean, p_locked boolean,
    p_deprovisioned boolean, p_event_ts timestamptz)
RETURNS void
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    u public.users%ROWTYPE;
    v_severity_new int;
    v_severity_old int;
BEGIN
    INSERT INTO public.users (external_subject) VALUES (p_subject)
    ON CONFLICT (external_subject) DO NOTHING;

    SELECT * INTO u FROM public.users WHERE external_subject = p_subject FOR UPDATE;

    -- Status component.
    IF p_status IS NOT NULL THEN
        v_severity_new := CASE p_status WHEN 'deleted' THEN 2 WHEN 'merged' THEN 1 ELSE 0 END;
        v_severity_old := CASE u.status WHEN 'deleted' THEN 2 WHEN 'merged' THEN 1 ELSE 0 END;
        IF p_event_ts > u.lifecycle_updated_at
           OR (p_event_ts = u.lifecycle_updated_at AND v_severity_new > v_severity_old) THEN
            IF p_status IN ('deleted', 'merged') AND u.status NOT IN ('deleted', 'merged') THEN
                u.tokens_valid_after := greatest(u.tokens_valid_after, p_event_ts);
            END IF;
            u.status := p_status;
            u.lifecycle_updated_at := p_event_ts;
        END IF;
    END IF;

    -- Banned flag component.
    IF p_banned IS NOT NULL
       AND (p_event_ts > u.banned_updated_at
            OR (p_event_ts = u.banned_updated_at AND p_banned AND NOT u.banned)) THEN
        IF p_banned AND NOT u.banned THEN
            u.tokens_valid_after := greatest(u.tokens_valid_after, p_event_ts);
        END IF;
        u.banned := p_banned;
        u.banned_updated_at := p_event_ts;
    END IF;

    -- Locked flag component.
    IF p_locked IS NOT NULL
       AND (p_event_ts > u.locked_updated_at
            OR (p_event_ts = u.locked_updated_at AND p_locked AND NOT u.locked)) THEN
        IF p_locked AND NOT u.locked THEN
            u.tokens_valid_after := greatest(u.tokens_valid_after, p_event_ts);
        END IF;
        u.locked := p_locked;
        u.locked_updated_at := p_event_ts;
    END IF;

    -- Deprovisioned flag component.
    IF p_deprovisioned IS NOT NULL
       AND (p_event_ts > u.deprovisioned_updated_at
            OR (p_event_ts = u.deprovisioned_updated_at AND p_deprovisioned AND NOT u.deprovisioned)) THEN
        IF p_deprovisioned AND NOT u.deprovisioned THEN
            u.tokens_valid_after := greatest(u.tokens_valid_after, p_event_ts);
        END IF;
        u.deprovisioned := p_deprovisioned;
        u.deprovisioned_updated_at := p_event_ts;
    END IF;

    UPDATE public.users SET
        status = u.status, banned = u.banned, banned_updated_at = u.banned_updated_at,
        locked = u.locked, locked_updated_at = u.locked_updated_at,
        deprovisioned = u.deprovisioned, deprovisioned_updated_at = u.deprovisioned_updated_at,
        lifecycle_updated_at = u.lifecycle_updated_at,
        tokens_valid_after = u.tokens_valid_after,
        updated_at = pg_catalog.now()
    WHERE id = u.id;
END $$;
REVOKE ALL ON FUNCTION public.apply_user_lifecycle(text, text, boolean, boolean, boolean, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.apply_user_lifecycle(text, text, boolean, boolean, boolean, timestamptz) TO validexia_app;
ALTER FUNCTION public.apply_user_lifecycle(text, text, boolean, boolean, boolean, timestamptz) OWNER TO validexia_directory_owner;

-- Snapshot applier for the JIT resync: identical flag rules, never touches status,
-- so a delayed Clerk API response cannot overwrite newer webhook state or
-- resurrect a deleted/merged user.
CREATE OR REPLACE FUNCTION public.apply_user_lifecycle_snapshot(
    p_subject text, p_banned boolean, p_locked boolean, p_deprovisioned boolean, p_updated_at timestamptz)
RETURNS void
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT public.apply_user_lifecycle(p_subject, NULL, p_banned, p_locked, p_deprovisioned, p_updated_at)
$$;
REVOKE ALL ON FUNCTION public.apply_user_lifecycle_snapshot(text, boolean, boolean, boolean, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.apply_user_lifecycle_snapshot(text, boolean, boolean, boolean, timestamptz) TO validexia_app;
ALTER FUNCTION public.apply_user_lifecycle_snapshot(text, boolean, boolean, boolean, timestamptz) OWNER TO validexia_directory_owner;

-- Merge: re-point the removed user's memberships to the survivor (the survivor's
-- own membership/role wins on conflict), then mark the removed user merged.
CREATE OR REPLACE FUNCTION public.merge_users(p_surviving_subject text, p_deleted_subject text, p_event_ts timestamptz)
RETURNS void
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_survivor uuid; v_deleted uuid;
BEGIN
    PERFORM public.provision_user(p_surviving_subject, NULL, NULL);
    PERFORM public.provision_user(p_deleted_subject, NULL, NULL);
    SELECT id INTO v_survivor FROM public.users WHERE external_subject = p_surviving_subject;
    SELECT id INTO v_deleted  FROM public.users WHERE external_subject = p_deleted_subject;
    IF v_survivor IS NULL OR v_deleted IS NULL OR v_survivor = v_deleted THEN
        RETURN;
    END IF;
    INSERT INTO public.memberships (user_id, tenant_id, role, status)
    SELECT v_survivor, m.tenant_id, m.role, m.status
    FROM public.memberships m WHERE m.user_id = v_deleted
    ON CONFLICT (user_id, tenant_id) DO NOTHING;
    DELETE FROM public.memberships WHERE user_id = v_deleted;
    PERFORM public.apply_user_lifecycle(p_deleted_subject, 'merged', NULL, NULL, NULL, p_event_ts);
END $$;
REVOKE ALL ON FUNCTION public.merge_users(text, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.merge_users(text, text, timestamptz) TO validexia_app;
ALTER FUNCTION public.merge_users(text, text, timestamptz) OWNER TO validexia_directory_owner;

-- JIT resync claim: ONE atomic conditional UPDATE. Exactly one concurrent caller
-- (across all app instances — the claim lives in the database) wins the 60-second
-- window; the database clock is the only clock consulted. Evaluated with FOUND.
CREATE OR REPLACE FUNCTION public.claim_resync(p_subject text)
RETURNS boolean
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    UPDATE public.users u
    SET resync_claimed_at = pg_catalog.now()
    WHERE u.external_subject = p_subject
      AND (u.resync_claimed_at IS NULL
           OR u.resync_claimed_at < pg_catalog.now() - interval '60 seconds');
    RETURN FOUND;
END $$;
REVOKE ALL ON FUNCTION public.claim_resync(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.claim_resync(text) TO validexia_app;
ALTER FUNCTION public.claim_resync(text) OWNER TO validexia_directory_owner;
