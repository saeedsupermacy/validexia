-- V12: Vendors — the first domain data slice of the platform data layer
-- (docs/ai/AI-FOUNDATION-DESIGN.md, increment 2).
--
-- Until now every TPRM entity existed only in the React frontend as
-- TypeScript fixtures and localStorage. This migration gives vendors a
-- server-side home behind the platform's established tenant-isolation
-- pattern, copied from the agent foundation (V5/V6):
--
-- - A NOLOGIN owner role holds the tables; the application role has NO
--   direct table access — every read and write goes through SECURITY
--   DEFINER functions owned by that role.
-- - Every function carries the V6 fail-closed tenant guard: the
--   transaction-local app.tenant_id must exactly match the tenant
--   argument, or the call raises.
-- - Both tables are under FORCE ROW LEVEL SECURITY with a tenant policy
--   bound to the owner role, so even the function owner can only touch
--   the calling tenant's rows.
--
-- SCOPE, deliberately narrow: this table owns vendor identity and
-- lifecycle (name, code, category, domain, criticality, owner, contract
-- dates, status). It does NOT store risk scores, risk levels, evidence
-- status, assessment status, open-issue counts or monitoring state —
-- those belong to the assessments/evidence/monitoring slices and to the
-- deterministic scoring design (whose direction remains a founder
-- decision). Nothing here pre-empts that decision.
--
-- Termination is a status flip, never a delete: vendor history must
-- survive for audit and for the dependency records that reference it.

DO $$
BEGIN
    CREATE ROLE validexia_vendors_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
EXCEPTION WHEN duplicate_object THEN
    NULL;
END $$;

CREATE TABLE public.vendors (
    id uuid PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    name text NOT NULL,
    legal_name text,
    lei text CHECK (lei IS NULL OR pg_catalog.char_length(lei) = 20),
    jurisdiction text,
    code text NOT NULL,
    category text,
    domain text,
    website text,
    description text,
    criticality text NOT NULL DEFAULT 'Tier 3'
        CHECK (criticality IN ('Tier 1', 'Tier 2', 'Tier 3')),
    owner_user_id uuid REFERENCES public.users(id),
    status text NOT NULL DEFAULT 'Active'
        CHECK (status IN ('Active', 'Terminated')),
    contract_start date,
    contract_end date,
    tiering_complete boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    updated_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    UNIQUE (tenant_id, code),
    CHECK (contract_end IS NULL OR contract_start IS NULL OR contract_end >= contract_start)
);

CREATE TABLE public.vendor_dependent_systems (
    id uuid PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    vendor_id uuid NOT NULL REFERENCES public.vendors(id) ON DELETE CASCADE,
    name text NOT NULL,
    data_handled text,
    residency text,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.now()
);
CREATE INDEX vendor_dependent_systems_vendor_idx
    ON public.vendor_dependent_systems (tenant_id, vendor_id);

ALTER TABLE public.vendors OWNER TO validexia_vendors_owner;
ALTER TABLE public.vendor_dependent_systems OWNER TO validexia_vendors_owner;

ALTER TABLE public.vendors ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.vendors FORCE ROW LEVEL SECURITY;
ALTER TABLE public.vendor_dependent_systems ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.vendor_dependent_systems FORCE ROW LEVEL SECURITY;

CREATE POLICY vendors_owner_tenant ON public.vendors
    FOR ALL TO validexia_vendors_owner
    USING (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true));

CREATE POLICY vendor_systems_owner_tenant ON public.vendor_dependent_systems
    FOR ALL TO validexia_vendors_owner
    USING (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true));

-- ---------------------------------------------------------------------------
-- Functions. All SECURITY DEFINER with pinned search_path, all fail-closed.
-- ---------------------------------------------------------------------------

CREATE FUNCTION public.create_vendor(
    p_tenant_id uuid, p_name text, p_legal_name text, p_lei text, p_jurisdiction text,
    p_code text, p_category text, p_domain text,
    p_website text, p_description text, p_criticality text, p_owner_user_id uuid,
    p_contract_start date, p_contract_end date, p_tiering_complete boolean)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_id uuid;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    INSERT INTO public.vendors (
        tenant_id, name, legal_name, lei, jurisdiction, code, category, domain,
        website, description,
        criticality, owner_user_id, contract_start, contract_end, tiering_complete)
    VALUES (
        p_tenant_id, p_name, p_legal_name, p_lei, p_jurisdiction, p_code, p_category,
        p_domain, p_website, p_description,
        COALESCE(p_criticality, 'Tier 3'), p_owner_user_id,
        p_contract_start, p_contract_end, COALESCE(p_tiering_complete, false))
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;
REVOKE ALL ON FUNCTION public.create_vendor(uuid, text, text, text, text, text, text, text, text, text, text, text, uuid, date, date, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_vendor(uuid, text, text, text, text, text, text, text, text, text, text, text, uuid, date, date, boolean) TO validexia_app;
ALTER FUNCTION public.create_vendor(uuid, text, text, text, text, text, text, text, text, text, text, text, uuid, date, date, boolean) OWNER TO validexia_vendors_owner;

CREATE FUNCTION public.update_vendor(
    p_tenant_id uuid, p_vendor_id uuid, p_name text, p_legal_name text, p_lei text,
    p_jurisdiction text, p_category text, p_domain text,
    p_website text, p_description text, p_criticality text, p_owner_user_id uuid,
    p_contract_start date, p_contract_end date, p_tiering_complete boolean)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_rows int;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    UPDATE public.vendors
    SET name = p_name, legal_name = p_legal_name, lei = p_lei,
        jurisdiction = p_jurisdiction,
        category = p_category, domain = p_domain, website = p_website,
        description = p_description, criticality = p_criticality,
        owner_user_id = p_owner_user_id, contract_start = p_contract_start,
        contract_end = p_contract_end, tiering_complete = p_tiering_complete,
        updated_at = pg_catalog.now()
    WHERE id = p_vendor_id AND tenant_id = p_tenant_id;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RETURN v_rows = 1;
END $$;
REVOKE ALL ON FUNCTION public.update_vendor(uuid, uuid, text, text, text, text, text, text, text, text, text, uuid, date, date, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.update_vendor(uuid, uuid, text, text, text, text, text, text, text, text, text, uuid, date, date, boolean) TO validexia_app;
ALTER FUNCTION public.update_vendor(uuid, uuid, text, text, text, text, text, text, text, text, text, uuid, date, date, boolean) OWNER TO validexia_vendors_owner;

CREATE FUNCTION public.terminate_vendor(p_tenant_id uuid, p_vendor_id uuid)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_rows int;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    UPDATE public.vendors
    SET status = 'Terminated', updated_at = pg_catalog.now()
    WHERE id = p_vendor_id AND tenant_id = p_tenant_id AND status = 'Active';
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RETURN v_rows = 1;
END $$;
REVOKE ALL ON FUNCTION public.terminate_vendor(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.terminate_vendor(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.terminate_vendor(uuid, uuid) OWNER TO validexia_vendors_owner;

CREATE FUNCTION public.get_vendor(p_tenant_id uuid, p_vendor_id uuid)
RETURNS TABLE (
    id uuid, name text, legal_name text, lei text, jurisdiction text,
    code text, category text, domain text, website text,
    description text, criticality text, owner_user_id uuid, status text,
    contract_start date, contract_end date, tiering_complete boolean,
    created_at timestamptz, updated_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
    SELECT v.id, v.name, v.legal_name, v.lei, v.jurisdiction,
           v.code, v.category, v.domain, v.website, v.description,
           v.criticality, v.owner_user_id, v.status, v.contract_start, v.contract_end,
           v.tiering_complete, v.created_at, v.updated_at
    FROM public.vendors v
    WHERE v.id = p_vendor_id AND v.tenant_id = p_tenant_id;
END $$;
REVOKE ALL ON FUNCTION public.get_vendor(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_vendor(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.get_vendor(uuid, uuid) OWNER TO validexia_vendors_owner;

CREATE FUNCTION public.list_vendors(p_tenant_id uuid)
RETURNS TABLE (
    id uuid, name text, legal_name text, lei text, jurisdiction text,
    code text, category text, domain text, website text,
    description text, criticality text, owner_user_id uuid, status text,
    contract_start date, contract_end date, tiering_complete boolean,
    created_at timestamptz, updated_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
    SELECT v.id, v.name, v.legal_name, v.lei, v.jurisdiction,
           v.code, v.category, v.domain, v.website, v.description,
           v.criticality, v.owner_user_id, v.status, v.contract_start, v.contract_end,
           v.tiering_complete, v.created_at, v.updated_at
    FROM public.vendors v
    WHERE v.tenant_id = p_tenant_id
    ORDER BY v.name;
END $$;
REVOKE ALL ON FUNCTION public.list_vendors(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_vendors(uuid) TO validexia_app;
ALTER FUNCTION public.list_vendors(uuid) OWNER TO validexia_vendors_owner;

-- Dependent systems are replaced as a set: the repository clears and
-- re-adds them inside one transaction, so a partial replacement is never
-- observable. Both functions refuse (false) when the vendor does not
-- belong to the calling tenant.
CREATE FUNCTION public.clear_vendor_dependent_systems(
    p_tenant_id uuid, p_vendor_id uuid)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.vendors v
        WHERE v.id = p_vendor_id AND v.tenant_id = p_tenant_id) THEN
        RETURN false;
    END IF;
    DELETE FROM public.vendor_dependent_systems
    WHERE vendor_id = p_vendor_id AND tenant_id = p_tenant_id;
    RETURN true;
END $$;
REVOKE ALL ON FUNCTION public.clear_vendor_dependent_systems(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.clear_vendor_dependent_systems(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.clear_vendor_dependent_systems(uuid, uuid) OWNER TO validexia_vendors_owner;

CREATE FUNCTION public.add_vendor_dependent_system(
    p_tenant_id uuid, p_vendor_id uuid, p_name text, p_data_handled text, p_residency text)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.vendors v
        WHERE v.id = p_vendor_id AND v.tenant_id = p_tenant_id) THEN
        RETURN false;
    END IF;
    INSERT INTO public.vendor_dependent_systems (tenant_id, vendor_id, name, data_handled, residency)
    VALUES (p_tenant_id, p_vendor_id, p_name, p_data_handled, p_residency);
    RETURN true;
END $$;
REVOKE ALL ON FUNCTION public.add_vendor_dependent_system(uuid, uuid, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.add_vendor_dependent_system(uuid, uuid, text, text, text) TO validexia_app;
ALTER FUNCTION public.add_vendor_dependent_system(uuid, uuid, text, text, text) OWNER TO validexia_vendors_owner;

CREATE FUNCTION public.list_vendor_dependent_systems(p_tenant_id uuid, p_vendor_id uuid)
RETURNS TABLE (id uuid, name text, data_handled text, residency text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
    SELECT s.id, s.name, s.data_handled, s.residency
    FROM public.vendor_dependent_systems s
    WHERE s.vendor_id = p_vendor_id AND s.tenant_id = p_tenant_id
    ORDER BY s.name;
END $$;
REVOKE ALL ON FUNCTION public.list_vendor_dependent_systems(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_vendor_dependent_systems(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.list_vendor_dependent_systems(uuid, uuid) OWNER TO validexia_vendors_owner;
