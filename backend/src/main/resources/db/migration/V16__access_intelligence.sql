-- V16: Access Intelligence Core (M1) — internal systems inventory and vendor
-- access grants, per the approved U1 Signature & Contract Freeze v1.4.1.
--
-- Two separate models, never merged (Reconciliation v1.2 §1): the internal
-- systems a tenant runs, and the access vendors hold into systems. The V12
-- vendor_dependent_systems table is a dependency record and is NOT touched,
-- repurposed or extended by this migration.
--
-- The slice follows the platform's established tenant-isolation pattern
-- (V5/V6/V12):
--
-- - A NOLOGIN owner role (validexia_access_owner) holds the tables; the
--   application role has NO direct table access — every read and write goes
--   through SECURITY DEFINER functions owned by that role.
-- - Every function carries the V6 fail-closed tenant guard: the
--   transaction-local app.tenant_id must exactly match the tenant argument.
-- - All three tables are under FORCE ROW LEVEL SECURITY with a tenant policy
--   bound to the same predicate, so even the function owner can only touch
--   the calling tenant's rows.
-- - Every mutating function additionally binds its actor argument to the
--   transaction's app.user_id and re-verifies ACTIVE membership in the
--   tenant (freeze §7); confirm/reject are gated to owner/admin members.
--   This in-function actor layer is new in M1 — no earlier module reads
--   app.user_id — and complements, never replaces, the request filter's
--   account-level checks (freeze §8).
-- - Transition functions evaluate their guards under the target row's
--   FOR UPDATE lock (the V11 lock-then-check pattern), held until the
--   caller's transaction ends, so a verdict and any in-transaction
--   read-back describe the same row version (freeze §§1, 9). The vendor
--   condition in create/reinstate is folded into the deciding statement
--   itself, because the access owner holds SELECT only on vendors and
--   cannot lock that row (freeze §1).
--
-- Discovery is an assertion, not an authorization: a discovered grant is
-- born Revoked (freeze §3.4). Revocation is terminal; reauthorization is a
-- new row. Rejected verification moves only the verification axis (§3.5).
--
-- ROLLBACK: forward-only, like every migration in this codebase. Undoing
-- means a NEW forward migration dropping the functions, then the tables,
-- then the role — lossless only while no access rows exist.

DO $$
BEGIN
    CREATE ROLE validexia_access_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
EXCEPTION WHEN duplicate_object THEN
    NULL;
END $$;

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------

CREATE TABLE public.internal_systems (
    id uuid PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    name text NOT NULL,
    description text,
    owner_user_id uuid REFERENCES public.users(id),
    status text NOT NULL DEFAULT 'Active',
    retired_by_user_id uuid REFERENCES public.users(id),
    retired_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    updated_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    UNIQUE (tenant_id, id),
    CONSTRAINT chk_internal_systems_status
        CHECK (status IN ('Active', 'Retired')),
    CONSTRAINT chk_internal_systems_name_nonempty
        CHECK (pg_catalog.char_length(pg_catalog.btrim(name)) > 0),
    CONSTRAINT chk_internal_systems_retired_metadata
        CHECK ((status = 'Retired' AND retired_at IS NOT NULL AND retired_by_user_id IS NOT NULL)
            OR (status = 'Active' AND retired_at IS NULL AND retired_by_user_id IS NULL)),
    CONSTRAINT chk_internal_systems_updated_after_created
        CHECK (updated_at >= created_at)
);

CREATE TABLE public.access_grants (
    id uuid PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    vendor_id uuid NOT NULL,
    internal_system_id uuid NOT NULL,
    privilege_level text NOT NULL,
    access_method text NOT NULL,
    source_kind text NOT NULL,
    lifecycle_status text NOT NULL DEFAULT 'Active',
    verification_status text NOT NULL DEFAULT 'Unverified',
    created_by_user_id uuid NOT NULL REFERENCES public.users(id),
    verified_by_user_id uuid REFERENCES public.users(id),
    verified_at timestamptz,
    rejection_reason text,
    suspended_by_user_id uuid REFERENCES public.users(id),
    suspended_at timestamptz,
    suspension_reason text,
    reinstated_by_user_id uuid REFERENCES public.users(id),
    reinstated_at timestamptz,
    revoked_by_user_id uuid REFERENCES public.users(id),
    revoked_at timestamptz,
    revocation_reason text,
    discovery_note text,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    updated_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    UNIQUE (tenant_id, id),
    -- Composite FKs: a grant can only attach to a vendor and a system of the
    -- SAME tenant, RESTRICT (never CASCADE) so history cannot be deleted away.
    FOREIGN KEY (tenant_id, vendor_id) REFERENCES public.vendors (tenant_id, id)
        ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, internal_system_id) REFERENCES public.internal_systems (tenant_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_access_grants_privilege_level
        CHECK (privilege_level IN ('Read', 'Write', 'Admin')),
    CONSTRAINT chk_access_grants_access_method
        CHECK (access_method IN ('Direct Login', 'API', 'Network', 'File Exchange')),
    CONSTRAINT chk_access_grants_source_kind
        CHECK (source_kind IN ('Customer Entered', 'Vendor Provided', 'Unknown')),
    CONSTRAINT chk_access_grants_lifecycle_status
        CHECK (lifecycle_status IN ('Active', 'Suspended', 'Revoked')),
    CONSTRAINT chk_access_grants_verification_status
        CHECK (verification_status IN ('Unverified', 'Confirmed', 'Rejected')),
    CONSTRAINT chk_access_grants_revocation_reason_nonempty
        CHECK (revocation_reason IS NULL OR pg_catalog.char_length(pg_catalog.btrim(revocation_reason)) > 0),
    CONSTRAINT chk_access_grants_discovery_note_nonempty
        CHECK (discovery_note IS NULL OR pg_catalog.char_length(pg_catalog.btrim(discovery_note)) > 0),
    CONSTRAINT chk_access_grants_revoked_metadata
        CHECK ((lifecycle_status = 'Revoked' AND revoked_at IS NOT NULL AND revoked_by_user_id IS NOT NULL)
            OR (lifecycle_status <> 'Revoked' AND revoked_at IS NULL AND revoked_by_user_id IS NULL)),
    CONSTRAINT chk_access_grants_revocation_source_xor
        CHECK ((lifecycle_status = 'Revoked' AND (revocation_reason IS NOT NULL) <> (discovery_note IS NOT NULL))
            OR (lifecycle_status <> 'Revoked' AND revocation_reason IS NULL AND discovery_note IS NULL)),
    CONSTRAINT chk_access_grants_verification_metadata
        CHECK ((verification_status = 'Unverified' AND verified_by_user_id IS NULL AND verified_at IS NULL AND rejection_reason IS NULL)
            OR (verification_status = 'Confirmed' AND verified_by_user_id IS NOT NULL AND verified_at IS NOT NULL AND rejection_reason IS NULL)
            OR (verification_status = 'Rejected' AND verified_by_user_id IS NOT NULL AND verified_at IS NOT NULL AND rejection_reason IS NOT NULL AND pg_catalog.char_length(pg_catalog.btrim(rejection_reason)) > 0)),
    CONSTRAINT chk_access_grants_suspension_metadata
        CHECK ((suspended_by_user_id IS NULL) = (suspended_at IS NULL)
            AND (suspended_at IS NULL) = (suspension_reason IS NULL)
            AND (suspension_reason IS NULL OR pg_catalog.char_length(pg_catalog.btrim(suspension_reason)) > 0)),
    CONSTRAINT chk_access_grants_suspended_requires_metadata
        CHECK (lifecycle_status <> 'Suspended' OR suspended_at IS NOT NULL),
    CONSTRAINT chk_access_grants_reinstate_metadata
        CHECK ((reinstated_at IS NULL) = (reinstated_by_user_id IS NULL)
            AND (reinstated_at IS NULL OR suspended_at IS NOT NULL)),
    CONSTRAINT chk_access_grants_temporal_order
        CHECK ((revoked_at IS NULL OR revoked_at >= created_at)
            AND (verified_at IS NULL OR verified_at >= created_at)
            AND (suspended_at IS NULL OR suspended_at >= created_at)
            AND (reinstated_at IS NULL OR suspended_at IS NULL OR reinstated_at >= suspended_at)),
    CONSTRAINT chk_access_grants_updated_after_created
        CHECK (updated_at >= created_at)
);

CREATE TABLE public.access_grant_evidence (
    id uuid PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    grant_id uuid NOT NULL,
    document_id uuid NOT NULL,
    linked_by_user_id uuid NOT NULL REFERENCES public.users(id),
    unlinked_by_user_id uuid REFERENCES public.users(id),
    unlinked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    FOREIGN KEY (tenant_id, grant_id) REFERENCES public.access_grants (tenant_id, id)
        ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, document_id) REFERENCES public.documents (tenant_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_access_grant_evidence_unlink_metadata
        CHECK ((unlinked_at IS NULL) = (unlinked_by_user_id IS NULL)),
    CONSTRAINT chk_access_grant_evidence_unlink_after_link
        CHECK (unlinked_at IS NULL OR unlinked_at >= created_at)
);

ALTER TABLE public.internal_systems OWNER TO validexia_access_owner;
ALTER TABLE public.access_grants OWNER TO validexia_access_owner;
ALTER TABLE public.access_grant_evidence OWNER TO validexia_access_owner;

-- Cross-module reads, granted to the owner role only, never to the
-- application role (freeze §10): memberships for the §7 actor checks and the
-- §1.1 owner-membership check; documents for the §4 evidence rule; vendors
-- for the create/discovery/reinstate vendor-state reads. Each of those
-- tables sits under its own FORCE RLS tenant policy, so inside an M1
-- transaction (app.tenant_id set) these grants expose exactly the calling
-- tenant's rows. No grant on public.users is needed: no M1 function reads it.
GRANT SELECT ON public.memberships TO validexia_access_owner;
GRANT SELECT ON public.documents TO validexia_access_owner;
GRANT SELECT ON public.vendors TO validexia_access_owner;

ALTER TABLE public.internal_systems ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.internal_systems FORCE ROW LEVEL SECURITY;
ALTER TABLE public.access_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.access_grants FORCE ROW LEVEL SECURITY;
ALTER TABLE public.access_grant_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.access_grant_evidence FORCE ROW LEVEL SECURITY;

-- Policy form matches V5/V12 (no TO clause; FOR ALL with USING/WITH CHECK).
CREATE POLICY internal_systems_owner_tenant ON public.internal_systems
    FOR ALL
    USING (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true));

CREATE POLICY access_grants_owner_tenant ON public.access_grants
    FOR ALL
    USING (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true));

CREATE POLICY access_grant_evidence_owner_tenant ON public.access_grant_evidence
    FOR ALL
    USING (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true));

-- Indexes (freeze §10): active-name uniqueness per tenant; grant lookups by
-- vendor and by system; one active evidence link per (grant, document).
CREATE UNIQUE INDEX internal_systems_active_name_key
    ON public.internal_systems (tenant_id, name) WHERE status = 'Active';
CREATE INDEX access_grants_tenant_vendor_idx
    ON public.access_grants (tenant_id, vendor_id);
CREATE INDEX access_grants_tenant_system_idx
    ON public.access_grants (tenant_id, internal_system_id);
CREATE UNIQUE INDEX access_grant_evidence_active_link_key
    ON public.access_grant_evidence (tenant_id, grant_id, document_id) WHERE unlinked_at IS NULL;

-- ---------------------------------------------------------------------------
-- Functions. All SECURITY DEFINER with pinned search_path, all fail-closed.
-- Every mutation runs, in order: the tenant guard; the §7 actor binding
-- (actor argument = app.user_id), active-membership check, and — for
-- confirm/reject only — the owner/admin role gate; then target resolution
-- under the row lock; then the transition guard.
-- ---------------------------------------------------------------------------

-- S1 -----------------------------------------------------------------------
CREATE FUNCTION public.create_internal_system(
    p_tenant_id uuid, p_name text, p_description text, p_owner_user_id uuid, p_actor_id uuid)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_actor uuid;
    v_id uuid;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF pg_catalog.current_setting('app.user_id', true) IS NULL
        OR pg_catalog.btrim(pg_catalog.current_setting('app.user_id', true)) = ''
        OR NOT pg_catalog.pg_input_is_valid(pg_catalog.current_setting('app.user_id', true), 'uuid') THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    v_actor := pg_catalog.current_setting('app.user_id', true)::uuid;
    IF p_actor_id IS DISTINCT FROM v_actor THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.memberships m
                   WHERE m.user_id = v_actor AND m.tenant_id = p_tenant_id
                     AND m.status = 'active') THEN
        RAISE EXCEPTION 'actor is not an active member of the tenant';
    END IF;
    -- Owner, when named, must hold a membership row in the tenant (ANY
    -- status — freeze §1.1; deliberately weaker than the actor rule above).
    IF p_owner_user_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM public.memberships m
            WHERE m.user_id = p_owner_user_id AND m.tenant_id = p_tenant_id) THEN
        RAISE EXCEPTION 'owner user is not a member of the tenant';
    END IF;
    IF EXISTS (SELECT 1 FROM public.internal_systems s
               WHERE s.tenant_id = p_tenant_id AND s.status = 'Active' AND s.name = p_name) THEN
        RAISE EXCEPTION 'internal system name already in use';
    END IF;
    INSERT INTO public.internal_systems (tenant_id, name, description, owner_user_id, status)
    VALUES (p_tenant_id, p_name, p_description, p_owner_user_id, 'Active')
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;
REVOKE ALL ON FUNCTION public.create_internal_system(uuid, text, text, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_internal_system(uuid, text, text, uuid, uuid) TO validexia_app;
ALTER FUNCTION public.create_internal_system(uuid, text, text, uuid, uuid) OWNER TO validexia_access_owner;

-- S2 -----------------------------------------------------------------------
-- Full replace of name/description/owner. Returns the PRE-update row (the
-- before-image for the audit event), captured from the locked read inside
-- this function; a refused update (Retired system) returns zero rows
-- (freeze §1.1, v1.2 return-type change).
CREATE FUNCTION public.update_internal_system(
    p_tenant_id uuid, p_system_id uuid, p_name text, p_description text,
    p_owner_user_id uuid, p_actor_id uuid)
RETURNS TABLE (
    id uuid, name text, description text, owner_user_id uuid, status text,
    retired_by_user_id uuid, retired_at timestamptz,
    created_at timestamptz, updated_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_actor uuid;
    v_before public.internal_systems%ROWTYPE;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF pg_catalog.current_setting('app.user_id', true) IS NULL
        OR pg_catalog.btrim(pg_catalog.current_setting('app.user_id', true)) = ''
        OR NOT pg_catalog.pg_input_is_valid(pg_catalog.current_setting('app.user_id', true), 'uuid') THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    v_actor := pg_catalog.current_setting('app.user_id', true)::uuid;
    IF p_actor_id IS DISTINCT FROM v_actor THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.memberships m
                   WHERE m.user_id = v_actor AND m.tenant_id = p_tenant_id
                     AND m.status = 'active') THEN
        RAISE EXCEPTION 'actor is not an active member of the tenant';
    END IF;
    SELECT * INTO v_before
    FROM public.internal_systems s
    WHERE s.id = p_system_id AND s.tenant_id = p_tenant_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'internal system not found in tenant';
    END IF;
    IF v_before.status = 'Retired' THEN
        RETURN;
    END IF;
    IF p_owner_user_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM public.memberships m
            WHERE m.user_id = p_owner_user_id AND m.tenant_id = p_tenant_id) THEN
        RAISE EXCEPTION 'owner user is not a member of the tenant';
    END IF;
    IF EXISTS (SELECT 1 FROM public.internal_systems s
               WHERE s.tenant_id = p_tenant_id AND s.status = 'Active'
                 AND s.name = p_name AND s.id <> p_system_id) THEN
        RAISE EXCEPTION 'internal system name already in use';
    END IF;
    UPDATE public.internal_systems s
    SET name = p_name, description = p_description, owner_user_id = p_owner_user_id,
        updated_at = pg_catalog.now()
    WHERE s.id = p_system_id AND s.tenant_id = p_tenant_id;
    id := v_before.id;
    name := v_before.name;
    description := v_before.description;
    owner_user_id := v_before.owner_user_id;
    status := v_before.status;
    retired_by_user_id := v_before.retired_by_user_id;
    retired_at := v_before.retired_at;
    created_at := v_before.created_at;
    updated_at := v_before.updated_at;
    RETURN NEXT;
    RETURN;
END $$;
REVOKE ALL ON FUNCTION public.update_internal_system(uuid, uuid, text, text, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.update_internal_system(uuid, uuid, text, text, uuid, uuid) TO validexia_app;
ALTER FUNCTION public.update_internal_system(uuid, uuid, text, text, uuid, uuid) OWNER TO validexia_access_owner;

-- S3 -----------------------------------------------------------------------
CREATE FUNCTION public.retire_internal_system(
    p_tenant_id uuid, p_system_id uuid, p_actor_id uuid)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_actor uuid;
    v_status text;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF pg_catalog.current_setting('app.user_id', true) IS NULL
        OR pg_catalog.btrim(pg_catalog.current_setting('app.user_id', true)) = ''
        OR NOT pg_catalog.pg_input_is_valid(pg_catalog.current_setting('app.user_id', true), 'uuid') THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    v_actor := pg_catalog.current_setting('app.user_id', true)::uuid;
    IF p_actor_id IS DISTINCT FROM v_actor THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.memberships m
                   WHERE m.user_id = v_actor AND m.tenant_id = p_tenant_id
                     AND m.status = 'active') THEN
        RAISE EXCEPTION 'actor is not an active member of the tenant';
    END IF;
    SELECT s.status INTO v_status
    FROM public.internal_systems s
    WHERE s.id = p_system_id AND s.tenant_id = p_tenant_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'internal system not found in tenant';
    END IF;
    IF v_status = 'Retired' THEN
        RETURN false;
    END IF;
    -- Retirement never blocks on, and never modifies, the system's grants
    -- (freeze §3.3); it succeeds regardless of grant state.
    UPDATE public.internal_systems s
    SET status = 'Retired', retired_by_user_id = v_actor,
        retired_at = pg_catalog.now(), updated_at = pg_catalog.now()
    WHERE s.id = p_system_id AND s.tenant_id = p_tenant_id;
    RETURN true;
END $$;
REVOKE ALL ON FUNCTION public.retire_internal_system(uuid, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.retire_internal_system(uuid, uuid, uuid) TO validexia_app;
ALTER FUNCTION public.retire_internal_system(uuid, uuid, uuid) OWNER TO validexia_access_owner;

-- S4 -----------------------------------------------------------------------
CREATE FUNCTION public.restore_internal_system(
    p_tenant_id uuid, p_system_id uuid, p_actor_id uuid)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_actor uuid;
    v_row public.internal_systems%ROWTYPE;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF pg_catalog.current_setting('app.user_id', true) IS NULL
        OR pg_catalog.btrim(pg_catalog.current_setting('app.user_id', true)) = ''
        OR NOT pg_catalog.pg_input_is_valid(pg_catalog.current_setting('app.user_id', true), 'uuid') THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    v_actor := pg_catalog.current_setting('app.user_id', true)::uuid;
    IF p_actor_id IS DISTINCT FROM v_actor THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.memberships m
                   WHERE m.user_id = v_actor AND m.tenant_id = p_tenant_id
                     AND m.status = 'active') THEN
        RAISE EXCEPTION 'actor is not an active member of the tenant';
    END IF;
    SELECT * INTO v_row
    FROM public.internal_systems s
    WHERE s.id = p_system_id AND s.tenant_id = p_tenant_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'internal system not found in tenant';
    END IF;
    IF v_row.status = 'Active' THEN
        RETURN false;
    END IF;
    IF EXISTS (SELECT 1 FROM public.internal_systems s
               WHERE s.tenant_id = p_tenant_id AND s.status = 'Active'
                 AND s.name = v_row.name AND s.id <> p_system_id) THEN
        RAISE EXCEPTION 'internal system name already in use';
    END IF;
    UPDATE public.internal_systems s
    SET status = 'Active', retired_by_user_id = NULL, retired_at = NULL,
        updated_at = pg_catalog.now()
    WHERE s.id = p_system_id AND s.tenant_id = p_tenant_id;
    RETURN true;
END $$;
REVOKE ALL ON FUNCTION public.restore_internal_system(uuid, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.restore_internal_system(uuid, uuid, uuid) TO validexia_app;
ALTER FUNCTION public.restore_internal_system(uuid, uuid, uuid) OWNER TO validexia_access_owner;

-- S5 -----------------------------------------------------------------------
CREATE FUNCTION public.get_internal_system(p_tenant_id uuid, p_system_id uuid)
RETURNS TABLE (
    id uuid, name text, description text, owner_user_id uuid, status text,
    retired_by_user_id uuid, retired_at timestamptz,
    created_at timestamptz, updated_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
    SELECT s.id, s.name, s.description, s.owner_user_id, s.status,
           s.retired_by_user_id, s.retired_at, s.created_at, s.updated_at
    FROM public.internal_systems s
    WHERE s.id = p_system_id AND s.tenant_id = p_tenant_id;
END $$;
REVOKE ALL ON FUNCTION public.get_internal_system(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_internal_system(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.get_internal_system(uuid, uuid) OWNER TO validexia_access_owner;

-- S6 -----------------------------------------------------------------------
CREATE FUNCTION public.list_internal_systems(p_tenant_id uuid)
RETURNS TABLE (
    id uuid, name text, description text, owner_user_id uuid, status text,
    retired_by_user_id uuid, retired_at timestamptz,
    created_at timestamptz, updated_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
    SELECT s.id, s.name, s.description, s.owner_user_id, s.status,
           s.retired_by_user_id, s.retired_at, s.created_at, s.updated_at
    FROM public.internal_systems s
    WHERE s.tenant_id = p_tenant_id
    ORDER BY s.name, s.id;
END $$;
REVOKE ALL ON FUNCTION public.list_internal_systems(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_internal_systems(uuid) TO validexia_app;
ALTER FUNCTION public.list_internal_systems(uuid) OWNER TO validexia_access_owner;

-- G1 -----------------------------------------------------------------------
-- Signature recovered verbatim from Specification v12 §4.
CREATE FUNCTION public.create_access_grant(
    p_tenant_id uuid, p_vendor_id uuid, p_internal_system_id uuid,
    p_privilege_level text, p_access_method text, p_source_kind text, p_actor_id uuid)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_actor uuid;
    v_vendor_status text;
    v_system_status text;
    v_id uuid;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF pg_catalog.current_setting('app.user_id', true) IS NULL
        OR pg_catalog.btrim(pg_catalog.current_setting('app.user_id', true)) = ''
        OR NOT pg_catalog.pg_input_is_valid(pg_catalog.current_setting('app.user_id', true), 'uuid') THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    v_actor := pg_catalog.current_setting('app.user_id', true)::uuid;
    IF p_actor_id IS DISTINCT FROM v_actor THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.memberships m
                   WHERE m.user_id = v_actor AND m.tenant_id = p_tenant_id
                     AND m.status = 'active') THEN
        RAISE EXCEPTION 'actor is not an active member of the tenant';
    END IF;
    -- Existence (freeze §1.2 step 1): rows are never deleted anywhere in the
    -- system, so existence cannot race.
    SELECT v.status INTO v_vendor_status
    FROM public.vendors v
    WHERE v.id = p_vendor_id AND v.tenant_id = p_tenant_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'vendor not found in tenant';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.internal_systems s
                   WHERE s.id = p_internal_system_id AND s.tenant_id = p_tenant_id) THEN
        RAISE EXCEPTION 'internal system not found in tenant';
    END IF;
    -- Vendor pre-read (step 2): termination is one-way, so a Terminated
    -- status observed here holds for the rest of the call.
    IF v_vendor_status = 'Terminated' THEN
        RAISE EXCEPTION 'vendor is terminated';
    END IF;
    -- System row locked (step 3); status read from the locked row.
    SELECT s.status INTO v_system_status
    FROM public.internal_systems s
    WHERE s.id = p_internal_system_id AND s.tenant_id = p_tenant_id
    FOR UPDATE;
    IF v_system_status = 'Retired' THEN
        RAISE EXCEPTION 'internal system is retired';
    END IF;
    -- Deciding INSERT (step 4): the vendor condition is repeated inside the
    -- statement itself, so the write and its vendor guard share one
    -- statement snapshot (freeze §1). Zero rows inserted means the vendor
    -- terminated between the pre-read and here.
    INSERT INTO public.access_grants (
        tenant_id, vendor_id, internal_system_id, privilege_level, access_method,
        source_kind, lifecycle_status, verification_status, created_by_user_id)
    SELECT p_tenant_id, p_vendor_id, p_internal_system_id, p_privilege_level,
           p_access_method, p_source_kind, 'Active', 'Unverified', v_actor
    WHERE EXISTS (SELECT 1 FROM public.vendors v
                  WHERE v.id = p_vendor_id AND v.tenant_id = p_tenant_id
                    AND v.status <> 'Terminated')
    RETURNING id INTO v_id;
    IF v_id IS NULL THEN
        RAISE EXCEPTION 'vendor is terminated';
    END IF;
    RETURN v_id;
END $$;
REVOKE ALL ON FUNCTION public.create_access_grant(uuid, uuid, uuid, text, text, text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_access_grant(uuid, uuid, uuid, text, text, text, uuid) TO validexia_app;
ALTER FUNCTION public.create_access_grant(uuid, uuid, uuid, text, text, text, uuid) OWNER TO validexia_access_owner;

-- G2 -----------------------------------------------------------------------
-- Signature recovered verbatim from Specification v12 §4. A discovered grant
-- is born Revoked (freeze §3.4): recording history is always allowed —
-- including against Terminated vendors and Retired systems — authorizing is
-- not.
CREATE FUNCTION public.record_discovered_access_grant(
    p_tenant_id uuid, p_vendor_id uuid, p_internal_system_id uuid,
    p_privilege_level text, p_access_method text, p_source_kind text,
    p_discovery_note text, p_actor_id uuid)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_actor uuid;
    v_id uuid;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF pg_catalog.current_setting('app.user_id', true) IS NULL
        OR pg_catalog.btrim(pg_catalog.current_setting('app.user_id', true)) = ''
        OR NOT pg_catalog.pg_input_is_valid(pg_catalog.current_setting('app.user_id', true), 'uuid') THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    v_actor := pg_catalog.current_setting('app.user_id', true)::uuid;
    IF p_actor_id IS DISTINCT FROM v_actor THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.memberships m
                   WHERE m.user_id = v_actor AND m.tenant_id = p_tenant_id
                     AND m.status = 'active') THEN
        RAISE EXCEPTION 'actor is not an active member of the tenant';
    END IF;
    IF p_discovery_note IS NULL OR pg_catalog.btrim(p_discovery_note) = '' THEN
        RAISE EXCEPTION 'discovery note is required' USING ERRCODE = '22023';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.vendors v
                   WHERE v.id = p_vendor_id AND v.tenant_id = p_tenant_id) THEN
        RAISE EXCEPTION 'vendor not found in tenant';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.internal_systems s
                   WHERE s.id = p_internal_system_id AND s.tenant_id = p_tenant_id) THEN
        RAISE EXCEPTION 'internal system not found in tenant';
    END IF;
    INSERT INTO public.access_grants (
        tenant_id, vendor_id, internal_system_id, privilege_level, access_method,
        source_kind, lifecycle_status, verification_status, created_by_user_id,
        revoked_by_user_id, revoked_at, discovery_note)
    VALUES (p_tenant_id, p_vendor_id, p_internal_system_id, p_privilege_level,
            p_access_method, p_source_kind, 'Revoked', 'Unverified', v_actor,
            v_actor, pg_catalog.now(), p_discovery_note)
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;
REVOKE ALL ON FUNCTION public.record_discovered_access_grant(uuid, uuid, uuid, text, text, text, text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.record_discovered_access_grant(uuid, uuid, uuid, text, text, text, text, uuid) TO validexia_app;
ALTER FUNCTION public.record_discovered_access_grant(uuid, uuid, uuid, text, text, text, text, uuid) OWNER TO validexia_access_owner;

-- G3 -----------------------------------------------------------------------
CREATE FUNCTION public.confirm_access_grant(
    p_tenant_id uuid, p_grant_id uuid, p_actor_id uuid)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_actor uuid;
    v_verification text;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF pg_catalog.current_setting('app.user_id', true) IS NULL
        OR pg_catalog.btrim(pg_catalog.current_setting('app.user_id', true)) = ''
        OR NOT pg_catalog.pg_input_is_valid(pg_catalog.current_setting('app.user_id', true), 'uuid') THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    v_actor := pg_catalog.current_setting('app.user_id', true)::uuid;
    IF p_actor_id IS DISTINCT FROM v_actor THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.memberships m
                   WHERE m.user_id = v_actor AND m.tenant_id = p_tenant_id
                     AND m.status = 'active') THEN
        RAISE EXCEPTION 'actor is not an active member of the tenant';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.memberships m
                   WHERE m.user_id = v_actor AND m.tenant_id = p_tenant_id
                     AND m.status = 'active' AND m.role IN ('owner', 'admin')) THEN
        RAISE EXCEPTION 'actor is not permitted to confirm or reject access grants';
    END IF;
    SELECT g.verification_status INTO v_verification
    FROM public.access_grants g
    WHERE g.id = p_grant_id AND g.tenant_id = p_tenant_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'access grant not found in tenant';
    END IF;
    IF v_verification = 'Confirmed' THEN
        RETURN false;
    END IF;
    UPDATE public.access_grants g
    SET verification_status = 'Confirmed', verified_by_user_id = v_actor,
        verified_at = pg_catalog.now(), rejection_reason = NULL,
        updated_at = pg_catalog.now()
    WHERE g.id = p_grant_id AND g.tenant_id = p_tenant_id;
    RETURN true;
END $$;
REVOKE ALL ON FUNCTION public.confirm_access_grant(uuid, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.confirm_access_grant(uuid, uuid, uuid) TO validexia_app;
ALTER FUNCTION public.confirm_access_grant(uuid, uuid, uuid) OWNER TO validexia_access_owner;

-- G4 -----------------------------------------------------------------------
CREATE FUNCTION public.reject_access_grant(
    p_tenant_id uuid, p_grant_id uuid, p_rejection_reason text, p_actor_id uuid)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_actor uuid;
    v_verification text;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF pg_catalog.current_setting('app.user_id', true) IS NULL
        OR pg_catalog.btrim(pg_catalog.current_setting('app.user_id', true)) = ''
        OR NOT pg_catalog.pg_input_is_valid(pg_catalog.current_setting('app.user_id', true), 'uuid') THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    v_actor := pg_catalog.current_setting('app.user_id', true)::uuid;
    IF p_actor_id IS DISTINCT FROM v_actor THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.memberships m
                   WHERE m.user_id = v_actor AND m.tenant_id = p_tenant_id
                     AND m.status = 'active') THEN
        RAISE EXCEPTION 'actor is not an active member of the tenant';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.memberships m
                   WHERE m.user_id = v_actor AND m.tenant_id = p_tenant_id
                     AND m.status = 'active' AND m.role IN ('owner', 'admin')) THEN
        RAISE EXCEPTION 'actor is not permitted to confirm or reject access grants';
    END IF;
    IF p_rejection_reason IS NULL OR pg_catalog.btrim(p_rejection_reason) = '' THEN
        RAISE EXCEPTION 'rejection reason is required' USING ERRCODE = '22023';
    END IF;
    SELECT g.verification_status INTO v_verification
    FROM public.access_grants g
    WHERE g.id = p_grant_id AND g.tenant_id = p_tenant_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'access grant not found in tenant';
    END IF;
    IF v_verification = 'Rejected' THEN
        RETURN false;
    END IF;
    UPDATE public.access_grants g
    SET verification_status = 'Rejected', verified_by_user_id = v_actor,
        verified_at = pg_catalog.now(), rejection_reason = p_rejection_reason,
        updated_at = pg_catalog.now()
    WHERE g.id = p_grant_id AND g.tenant_id = p_tenant_id;
    RETURN true;
END $$;
REVOKE ALL ON FUNCTION public.reject_access_grant(uuid, uuid, text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.reject_access_grant(uuid, uuid, text, uuid) TO validexia_app;
ALTER FUNCTION public.reject_access_grant(uuid, uuid, text, uuid) OWNER TO validexia_access_owner;

-- G5 -----------------------------------------------------------------------
CREATE FUNCTION public.suspend_access_grant(
    p_tenant_id uuid, p_grant_id uuid, p_reason text, p_actor_id uuid)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_actor uuid;
    v_lifecycle text;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF pg_catalog.current_setting('app.user_id', true) IS NULL
        OR pg_catalog.btrim(pg_catalog.current_setting('app.user_id', true)) = ''
        OR NOT pg_catalog.pg_input_is_valid(pg_catalog.current_setting('app.user_id', true), 'uuid') THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    v_actor := pg_catalog.current_setting('app.user_id', true)::uuid;
    IF p_actor_id IS DISTINCT FROM v_actor THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.memberships m
                   WHERE m.user_id = v_actor AND m.tenant_id = p_tenant_id
                     AND m.status = 'active') THEN
        RAISE EXCEPTION 'actor is not an active member of the tenant';
    END IF;
    IF p_reason IS NULL OR pg_catalog.btrim(p_reason) = '' THEN
        RAISE EXCEPTION 'suspension reason is required' USING ERRCODE = '22023';
    END IF;
    SELECT g.lifecycle_status INTO v_lifecycle
    FROM public.access_grants g
    WHERE g.id = p_grant_id AND g.tenant_id = p_tenant_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'access grant not found in tenant';
    END IF;
    IF v_lifecycle <> 'Active' THEN
        RETURN false;
    END IF;
    -- A new suspension closes the previous suspension/reinstatement cycle:
    -- the reinstatement pair is cleared before the new triple is written
    -- (freeze §3.6).
    UPDATE public.access_grants g
    SET lifecycle_status = 'Suspended', suspended_by_user_id = v_actor,
        suspended_at = pg_catalog.now(), suspension_reason = p_reason,
        reinstated_by_user_id = NULL, reinstated_at = NULL,
        updated_at = pg_catalog.now()
    WHERE g.id = p_grant_id AND g.tenant_id = p_tenant_id;
    RETURN true;
END $$;
REVOKE ALL ON FUNCTION public.suspend_access_grant(uuid, uuid, text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.suspend_access_grant(uuid, uuid, text, uuid) TO validexia_app;
ALTER FUNCTION public.suspend_access_grant(uuid, uuid, text, uuid) OWNER TO validexia_access_owner;

-- G6 -----------------------------------------------------------------------
CREATE FUNCTION public.reinstate_access_grant(
    p_tenant_id uuid, p_grant_id uuid, p_actor_id uuid)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_actor uuid;
    v_lifecycle text;
    v_rows int;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF pg_catalog.current_setting('app.user_id', true) IS NULL
        OR pg_catalog.btrim(pg_catalog.current_setting('app.user_id', true)) = ''
        OR NOT pg_catalog.pg_input_is_valid(pg_catalog.current_setting('app.user_id', true), 'uuid') THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    v_actor := pg_catalog.current_setting('app.user_id', true)::uuid;
    IF p_actor_id IS DISTINCT FROM v_actor THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.memberships m
                   WHERE m.user_id = v_actor AND m.tenant_id = p_tenant_id
                     AND m.status = 'active') THEN
        RAISE EXCEPTION 'actor is not an active member of the tenant';
    END IF;
    SELECT g.lifecycle_status INTO v_lifecycle
    FROM public.access_grants g
    WHERE g.id = p_grant_id AND g.tenant_id = p_tenant_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'access grant not found in tenant';
    END IF;
    IF v_lifecycle <> 'Suspended' THEN
        RETURN false;
    END IF;
    -- Deciding UPDATE: the vendor condition is folded into this statement,
    -- so the write and its vendor guard share one statement snapshot
    -- (freeze §1). A Suspended grant whose vendor is Terminated matches no
    -- row here and the function returns false — a terminated vendor's grant
    -- can never re-enter Active (freeze §3.2).
    UPDATE public.access_grants g
    SET lifecycle_status = 'Active', reinstated_by_user_id = v_actor,
        reinstated_at = pg_catalog.now(), updated_at = pg_catalog.now()
    WHERE g.id = p_grant_id AND g.tenant_id = p_tenant_id
      AND g.lifecycle_status = 'Suspended'
      AND EXISTS (SELECT 1 FROM public.vendors v
                  WHERE v.id = g.vendor_id AND v.tenant_id = g.tenant_id
                    AND v.status <> 'Terminated');
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RETURN v_rows = 1;
END $$;
REVOKE ALL ON FUNCTION public.reinstate_access_grant(uuid, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.reinstate_access_grant(uuid, uuid, uuid) TO validexia_app;
ALTER FUNCTION public.reinstate_access_grant(uuid, uuid, uuid) OWNER TO validexia_access_owner;

-- G7 -----------------------------------------------------------------------
CREATE FUNCTION public.revoke_access_grant(
    p_tenant_id uuid, p_grant_id uuid, p_reason text, p_actor_id uuid)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_actor uuid;
    v_lifecycle text;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF pg_catalog.current_setting('app.user_id', true) IS NULL
        OR pg_catalog.btrim(pg_catalog.current_setting('app.user_id', true)) = ''
        OR NOT pg_catalog.pg_input_is_valid(pg_catalog.current_setting('app.user_id', true), 'uuid') THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    v_actor := pg_catalog.current_setting('app.user_id', true)::uuid;
    IF p_actor_id IS DISTINCT FROM v_actor THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.memberships m
                   WHERE m.user_id = v_actor AND m.tenant_id = p_tenant_id
                     AND m.status = 'active') THEN
        RAISE EXCEPTION 'actor is not an active member of the tenant';
    END IF;
    IF p_reason IS NULL OR pg_catalog.btrim(p_reason) = '' THEN
        RAISE EXCEPTION 'revocation reason is required' USING ERRCODE = '22023';
    END IF;
    SELECT g.lifecycle_status INTO v_lifecycle
    FROM public.access_grants g
    WHERE g.id = p_grant_id AND g.tenant_id = p_tenant_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'access grant not found in tenant';
    END IF;
    IF v_lifecycle = 'Revoked' THEN
        RETURN false;
    END IF;
    UPDATE public.access_grants g
    SET lifecycle_status = 'Revoked', revoked_by_user_id = v_actor,
        revoked_at = pg_catalog.now(), revocation_reason = p_reason,
        updated_at = pg_catalog.now()
    WHERE g.id = p_grant_id AND g.tenant_id = p_tenant_id;
    RETURN true;
END $$;
REVOKE ALL ON FUNCTION public.revoke_access_grant(uuid, uuid, text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.revoke_access_grant(uuid, uuid, text, uuid) TO validexia_app;
ALTER FUNCTION public.revoke_access_grant(uuid, uuid, text, uuid) OWNER TO validexia_access_owner;

-- G8 -----------------------------------------------------------------------
CREATE FUNCTION public.get_access_grant(p_tenant_id uuid, p_grant_id uuid)
RETURNS TABLE (
    id uuid, vendor_id uuid, internal_system_id uuid, privilege_level text,
    access_method text, source_kind text, lifecycle_status text, verification_status text,
    created_by_user_id uuid, verified_by_user_id uuid, verified_at timestamptz,
    rejection_reason text, suspended_by_user_id uuid, suspended_at timestamptz,
    suspension_reason text, reinstated_by_user_id uuid, reinstated_at timestamptz,
    revoked_by_user_id uuid, revoked_at timestamptz, revocation_reason text,
    discovery_note text, created_at timestamptz, updated_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
    SELECT g.id, g.vendor_id, g.internal_system_id, g.privilege_level,
           g.access_method, g.source_kind, g.lifecycle_status, g.verification_status,
           g.created_by_user_id, g.verified_by_user_id, g.verified_at,
           g.rejection_reason, g.suspended_by_user_id, g.suspended_at,
           g.suspension_reason, g.reinstated_by_user_id, g.reinstated_at,
           g.revoked_by_user_id, g.revoked_at, g.revocation_reason,
           g.discovery_note, g.created_at, g.updated_at
    FROM public.access_grants g
    WHERE g.id = p_grant_id AND g.tenant_id = p_tenant_id;
END $$;
REVOKE ALL ON FUNCTION public.get_access_grant(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_access_grant(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.get_access_grant(uuid, uuid) OWNER TO validexia_access_owner;

-- G9 -----------------------------------------------------------------------
CREATE FUNCTION public.list_access_grants(
    p_tenant_id uuid, p_vendor_id uuid, p_internal_system_id uuid)
RETURNS TABLE (
    id uuid, vendor_id uuid, internal_system_id uuid, privilege_level text,
    access_method text, source_kind text, lifecycle_status text, verification_status text,
    created_by_user_id uuid, verified_by_user_id uuid, verified_at timestamptz,
    rejection_reason text, suspended_by_user_id uuid, suspended_at timestamptz,
    suspension_reason text, reinstated_by_user_id uuid, reinstated_at timestamptz,
    revoked_by_user_id uuid, revoked_at timestamptz, revocation_reason text,
    discovery_note text, created_at timestamptz, updated_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
    SELECT g.id, g.vendor_id, g.internal_system_id, g.privilege_level,
           g.access_method, g.source_kind, g.lifecycle_status, g.verification_status,
           g.created_by_user_id, g.verified_by_user_id, g.verified_at,
           g.rejection_reason, g.suspended_by_user_id, g.suspended_at,
           g.suspension_reason, g.reinstated_by_user_id, g.reinstated_at,
           g.revoked_by_user_id, g.revoked_at, g.revocation_reason,
           g.discovery_note, g.created_at, g.updated_at
    FROM public.access_grants g
    WHERE g.tenant_id = p_tenant_id
      AND (p_vendor_id IS NULL OR g.vendor_id = p_vendor_id)
      AND (p_internal_system_id IS NULL OR g.internal_system_id = p_internal_system_id)
    ORDER BY g.created_at, g.id;
END $$;
REVOKE ALL ON FUNCTION public.list_access_grants(uuid, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_access_grants(uuid, uuid, uuid) TO validexia_app;
ALTER FUNCTION public.list_access_grants(uuid, uuid, uuid) OWNER TO validexia_access_owner;

-- E1 -----------------------------------------------------------------------
CREATE FUNCTION public.link_access_grant_evidence(
    p_tenant_id uuid, p_grant_id uuid, p_document_id uuid, p_actor_id uuid)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_actor uuid;
    v_grant_vendor uuid;
    v_doc_status text;
    v_doc_vendor uuid;
    v_id uuid;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF pg_catalog.current_setting('app.user_id', true) IS NULL
        OR pg_catalog.btrim(pg_catalog.current_setting('app.user_id', true)) = ''
        OR NOT pg_catalog.pg_input_is_valid(pg_catalog.current_setting('app.user_id', true), 'uuid') THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    v_actor := pg_catalog.current_setting('app.user_id', true)::uuid;
    IF p_actor_id IS DISTINCT FROM v_actor THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.memberships m
                   WHERE m.user_id = v_actor AND m.tenant_id = p_tenant_id
                     AND m.status = 'active') THEN
        RAISE EXCEPTION 'actor is not an active member of the tenant';
    END IF;
    -- Check order frozen in freeze §1.3.
    SELECT g.vendor_id INTO v_grant_vendor
    FROM public.access_grants g
    WHERE g.id = p_grant_id AND g.tenant_id = p_tenant_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'access grant not found in tenant';
    END IF;
    SELECT d.processing_status, d.vendor_id INTO v_doc_status, v_doc_vendor
    FROM public.documents d
    WHERE d.id = p_document_id AND d.tenant_id = p_tenant_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'document not found in tenant';
    END IF;
    IF v_doc_status <> 'Ready' THEN
        RAISE EXCEPTION 'document is not Ready';
    END IF;
    IF v_doc_vendor IS NOT NULL AND v_doc_vendor IS DISTINCT FROM v_grant_vendor THEN
        RAISE EXCEPTION 'document belongs to a different vendor';
    END IF;
    IF EXISTS (SELECT 1 FROM public.access_grant_evidence e
               WHERE e.tenant_id = p_tenant_id AND e.grant_id = p_grant_id
                 AND e.document_id = p_document_id AND e.unlinked_at IS NULL) THEN
        RAISE EXCEPTION 'evidence is already linked to this grant';
    END IF;
    INSERT INTO public.access_grant_evidence (tenant_id, grant_id, document_id, linked_by_user_id)
    VALUES (p_tenant_id, p_grant_id, p_document_id, v_actor)
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;
REVOKE ALL ON FUNCTION public.link_access_grant_evidence(uuid, uuid, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.link_access_grant_evidence(uuid, uuid, uuid, uuid) TO validexia_app;
ALTER FUNCTION public.link_access_grant_evidence(uuid, uuid, uuid, uuid) OWNER TO validexia_access_owner;

-- E2 -----------------------------------------------------------------------
CREATE FUNCTION public.unlink_access_grant_evidence(
    p_tenant_id uuid, p_link_id uuid, p_actor_id uuid)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_actor uuid;
    v_unlinked_at timestamptz;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF pg_catalog.current_setting('app.user_id', true) IS NULL
        OR pg_catalog.btrim(pg_catalog.current_setting('app.user_id', true)) = ''
        OR NOT pg_catalog.pg_input_is_valid(pg_catalog.current_setting('app.user_id', true), 'uuid') THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    v_actor := pg_catalog.current_setting('app.user_id', true)::uuid;
    IF p_actor_id IS DISTINCT FROM v_actor THEN
        RAISE EXCEPTION 'actor context mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.memberships m
                   WHERE m.user_id = v_actor AND m.tenant_id = p_tenant_id
                     AND m.status = 'active') THEN
        RAISE EXCEPTION 'actor is not an active member of the tenant';
    END IF;
    SELECT e.unlinked_at INTO v_unlinked_at
    FROM public.access_grant_evidence e
    WHERE e.id = p_link_id AND e.tenant_id = p_tenant_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'evidence link not found in tenant';
    END IF;
    IF v_unlinked_at IS NOT NULL THEN
        RETURN false;
    END IF;
    UPDATE public.access_grant_evidence e
    SET unlinked_by_user_id = v_actor, unlinked_at = pg_catalog.now()
    WHERE e.id = p_link_id AND e.tenant_id = p_tenant_id;
    RETURN true;
END $$;
REVOKE ALL ON FUNCTION public.unlink_access_grant_evidence(uuid, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.unlink_access_grant_evidence(uuid, uuid, uuid) TO validexia_app;
ALTER FUNCTION public.unlink_access_grant_evidence(uuid, uuid, uuid) OWNER TO validexia_access_owner;

-- E3 -----------------------------------------------------------------------
CREATE FUNCTION public.list_access_grant_evidence(p_tenant_id uuid, p_grant_id uuid)
RETURNS TABLE (
    id uuid, grant_id uuid, document_id uuid, linked_by_user_id uuid,
    created_at timestamptz, unlinked_by_user_id uuid, unlinked_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
    SELECT e.id, e.grant_id, e.document_id, e.linked_by_user_id,
           e.created_at, e.unlinked_by_user_id, e.unlinked_at
    FROM public.access_grant_evidence e
    WHERE e.tenant_id = p_tenant_id AND e.grant_id = p_grant_id
    ORDER BY e.created_at, e.id;
END $$;
REVOKE ALL ON FUNCTION public.list_access_grant_evidence(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_access_grant_evidence(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.list_access_grant_evidence(uuid, uuid) OWNER TO validexia_access_owner;
