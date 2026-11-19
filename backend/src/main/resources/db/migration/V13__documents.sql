-- V13: Documents + Evidence — the second domain data slice of the platform data layer.
--
-- SCOPE: a tenant-scoped document register with per-page extracted text and the
-- evidence citations drawn from those pages. This slice covers upload metadata,
-- page-text persistence and evidence records. It does NOT cover OCR, chunking,
-- embeddings/vector search, evidence approval workflows, assessments, or any
-- scoring — those belong to later slices and must not be pre-empted here.
--
-- WHY vendor_id HAS NO FOREIGN KEY: the vendors table is introduced by the
-- vendors slice (migration V12), which is not part of this branch. A hard FK
-- would make this migration depend on an unmerged table and would break every
-- database that has not applied V12. vendor_id is therefore an unenforced
-- reference: the application writes it and the list index supports the
-- "documents of a vendor" read, while the (future) vendors slice owns its
-- referential integrity. When V12 lands, a NEW forward migration can add the FK
-- without rewriting this one (D12: migrations are immutable once merged).
--
-- DATA IS DATA (D8): document text and evidence quotes are untrusted content
-- supplied by users. They are stored, hashed and returned as opaque data only.
-- Nothing in this schema (or the Java slice over it) ever interprets document
-- text as instructions: there is no prompt, agent or tool path that consumes
-- these columns, so a malicious PDF cannot steer model behaviour.
--
-- Tenant isolation follows the house pattern (D3), copied from V5/V6/V12: a
-- NOLOGIN owner role holds the tables; the application role has NO direct table
-- access — every read and write goes through the SECURITY DEFINER functions
-- below, owned by that role; every function carries the V6 fail-closed tenant
-- guard (the transaction-local app.tenant_id must EXACTLY match the tenant
-- argument, or the call raises); and all three tables are under FORCE ROW LEVEL
-- SECURITY with a tenant policy bound to the owner role, so even the function
-- owner can only touch the calling tenant's rows.
--
-- page_id on evidence_records intentionally has NO foreign key: page rows are
-- replaced as a set whenever a document is re-extracted, so a hard reference
-- would either dangle or force a destructive cascade. Evidence keeps
-- page_number and quote_text as the durable citation; page_id is a pointer to
-- the page row of the current extraction.
--
-- ROLLBACK: forward-only, like every migration in this codebase. Undoing it
-- after merge means a NEW forward migration that drops the functions, then the
-- tables, then the role (in that order). That rollback is lossless only while
-- no document/page/evidence rows exist: once real documents have been uploaded,
-- dropping the tables destroys that data by definition.

DO $$
BEGIN
    CREATE ROLE validexia_documents_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
EXCEPTION WHEN duplicate_object THEN
    NULL;
END $$;

-- Tables ------------------------------------------------------------------------

CREATE TABLE public.documents (
    id uuid PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    -- No FK: see the header. The vendors table is not part of this branch.
    vendor_id uuid,
    file_name text NOT NULL,
    file_type text NOT NULL,
    file_size bigint NOT NULL CHECK (file_size >= 0),
    sha256 text NOT NULL,
    storage_key text NOT NULL,
    processing_status text NOT NULL DEFAULT 'Pending'
        CHECK (processing_status IN ('Pending', 'Processing', 'Ready', 'Failed')),
    page_count int NOT NULL DEFAULT 0 CHECK (page_count >= 0),
    -- The uploader is always an authenticated, active member of the tenant: the
    -- authentication filter re-verifies membership on every request, so the FK is
    -- safe and no extra cross-module read is needed in the functions.
    uploaded_by_user_id uuid REFERENCES public.users(id),
    created_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    updated_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    -- Composite key target for the document_pages / evidence_records FKs.
    UNIQUE (tenant_id, id)
);

CREATE TABLE public.document_pages (
    id uuid PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    document_id uuid NOT NULL,
    page_number int NOT NULL CHECK (page_number > 0),
    -- Blank/image-only pages legitimately extract to the empty string.
    text_content text NOT NULL DEFAULT '',
    text_sha256 text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    -- Composite FK: a page can only attach to a document of the SAME tenant, and
    -- RESTRICT (never CASCADE) so document history cannot be deleted away.
    FOREIGN KEY (tenant_id, document_id) REFERENCES public.documents (tenant_id, id)
        ON DELETE RESTRICT,
    UNIQUE (tenant_id, document_id, page_number)
);

CREATE TABLE public.evidence_records (
    id uuid PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    document_id uuid NOT NULL,
    -- No FK: page rows are replaced as a set on re-extraction (see the header).
    page_id uuid,
    page_number int,
    quote_text text NOT NULL,
    quote_sha256 text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    FOREIGN KEY (tenant_id, document_id) REFERENCES public.documents (tenant_id, id)
        ON DELETE RESTRICT
);

-- Indexes (the two required shapes, plus list ordering) -----------------------
CREATE INDEX documents_tenant_vendor_idx
    ON public.documents (tenant_id, vendor_id);
CREATE INDEX documents_tenant_created_idx
    ON public.documents (tenant_id, created_at DESC);
CREATE INDEX document_pages_document_idx
    ON public.document_pages (tenant_id, document_id);
CREATE INDEX evidence_records_document_idx
    ON public.evidence_records (tenant_id, document_id);

ALTER TABLE public.documents OWNER TO validexia_documents_owner;
ALTER TABLE public.document_pages OWNER TO validexia_documents_owner;
ALTER TABLE public.evidence_records OWNER TO validexia_documents_owner;

ALTER TABLE public.documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.documents FORCE ROW LEVEL SECURITY;
ALTER TABLE public.document_pages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.document_pages FORCE ROW LEVEL SECURITY;
ALTER TABLE public.evidence_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.evidence_records FORCE ROW LEVEL SECURITY;

-- Policy form matches V5/V6/V12 (no TO clause; FOR ALL with USING/WITH CHECK).
CREATE POLICY documents_owner_tenant ON public.documents
    FOR ALL
    USING (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true));

CREATE POLICY document_pages_owner_tenant ON public.document_pages
    FOR ALL
    USING (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true));

CREATE POLICY evidence_records_owner_tenant ON public.evidence_records
    FOR ALL
    USING (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true));

-- ---------------------------------------------------------------------------
-- Functions. All SECURITY DEFINER with pinned search_path, all fail-closed.
-- ---------------------------------------------------------------------------

CREATE FUNCTION public.create_document(
    p_tenant_id uuid,
    p_id uuid,
    p_vendor_id uuid,
    p_file_name text,
    p_file_type text,
    p_file_size bigint,
    p_sha256 text,
    p_storage_key text,
    p_processing_status text,
    p_page_count int,
    p_uploaded_by_user_id uuid)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_id uuid;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF p_file_name IS NULL OR pg_catalog.length(pg_catalog.btrim(p_file_name)) = 0 THEN
        RAISE EXCEPTION 'file name is required' USING ERRCODE = '22023';
    END IF;
    IF p_file_size IS NULL OR p_file_size < 0 THEN
        RAISE EXCEPTION 'file size must be zero or positive' USING ERRCODE = '22023';
    END IF;
    IF p_processing_status IS NULL
       OR p_processing_status NOT IN ('Pending', 'Processing', 'Ready', 'Failed') THEN
        RAISE EXCEPTION 'unknown processing status' USING ERRCODE = '22023';
    END IF;
    IF p_page_count IS NULL OR p_page_count < 0 THEN
        RAISE EXCEPTION 'page count must be zero or positive' USING ERRCODE = '22023';
    END IF;
    INSERT INTO public.documents (
        id, tenant_id, vendor_id, file_name, file_type, file_size, sha256,
        storage_key, processing_status, page_count, uploaded_by_user_id)
    VALUES (
        COALESCE(p_id, pg_catalog.gen_random_uuid()),
        p_tenant_id, p_vendor_id, p_file_name, p_file_type, p_file_size,
        COALESCE(p_sha256, ''), COALESCE(p_storage_key, ''),
        COALESCE(p_processing_status, 'Pending'), COALESCE(p_page_count, 0),
        p_uploaded_by_user_id)
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;
REVOKE ALL ON FUNCTION public.create_document(uuid, uuid, uuid, text, text, bigint, text, text, text, int, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_document(uuid, uuid, uuid, text, text, bigint, text, text, text, int, uuid) TO validexia_app;
ALTER FUNCTION public.create_document(uuid, uuid, uuid, text, text, bigint, text, text, text, int, uuid) OWNER TO validexia_documents_owner;

CREATE FUNCTION public.get_document(p_tenant_id uuid, p_document_id uuid)
RETURNS TABLE (
    id uuid, tenant_id uuid, vendor_id uuid, file_name text, file_type text,
    file_size bigint, sha256 text, storage_key text, processing_status text,
    page_count int, uploaded_by_user_id uuid, created_at timestamptz,
    updated_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
    SELECT d.id, d.tenant_id, d.vendor_id, d.file_name, d.file_type, d.file_size,
           d.sha256, d.storage_key, d.processing_status, d.page_count,
           d.uploaded_by_user_id, d.created_at, d.updated_at
    FROM public.documents d
    WHERE d.id = p_document_id AND d.tenant_id = p_tenant_id;
END $$;
REVOKE ALL ON FUNCTION public.get_document(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_document(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.get_document(uuid, uuid) OWNER TO validexia_documents_owner;

-- p_vendor_id NULL means "every document of the tenant"; a value filters to one
-- vendor. Ordering is newest first, then id, so paging is deterministic.
CREATE FUNCTION public.list_documents(p_tenant_id uuid, p_vendor_id uuid)
RETURNS TABLE (
    id uuid, tenant_id uuid, vendor_id uuid, file_name text, file_type text,
    file_size bigint, sha256 text, storage_key text, processing_status text,
    page_count int, uploaded_by_user_id uuid, created_at timestamptz,
    updated_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
    SELECT d.id, d.tenant_id, d.vendor_id, d.file_name, d.file_type, d.file_size,
           d.sha256, d.storage_key, d.processing_status, d.page_count,
           d.uploaded_by_user_id, d.created_at, d.updated_at
    FROM public.documents d
    WHERE d.tenant_id = p_tenant_id
      AND (p_vendor_id IS NULL OR d.vendor_id = p_vendor_id)
    ORDER BY d.created_at DESC, d.id;
END $$;
REVOKE ALL ON FUNCTION public.list_documents(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_documents(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.list_documents(uuid, uuid) OWNER TO validexia_documents_owner;

CREATE FUNCTION public.set_document_status(
    p_tenant_id uuid, p_document_id uuid, p_status text)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_rows int;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF p_status IS NULL OR p_status NOT IN ('Pending', 'Processing', 'Ready', 'Failed') THEN
        RAISE EXCEPTION 'unknown processing status' USING ERRCODE = '22023';
    END IF;
    UPDATE public.documents
    SET processing_status = p_status, updated_at = pg_catalog.now()
    WHERE id = p_document_id AND tenant_id = p_tenant_id;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RETURN v_rows = 1;
END $$;
REVOKE ALL ON FUNCTION public.set_document_status(uuid, uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.set_document_status(uuid, uuid, text) TO validexia_app;
ALTER FUNCTION public.set_document_status(uuid, uuid, text) OWNER TO validexia_documents_owner;

-- Pages are replaced as a set: the delete and the insert run inside this
-- function's single transaction, so a partial replacement is never observable.
-- p_pages is a jsonb array of {page_number, text_content, text_sha256}; hashes
-- are computed by the application (no pgcrypto dependency). Returns false when
-- the document does not belong to the calling tenant.
CREATE FUNCTION public.replace_document_pages(
    p_tenant_id uuid, p_document_id uuid, p_pages jsonb)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_count int;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.documents d
        WHERE d.id = p_document_id AND d.tenant_id = p_tenant_id) THEN
        RETURN false;
    END IF;
    DELETE FROM public.document_pages
    WHERE document_id = p_document_id AND tenant_id = p_tenant_id;
    INSERT INTO public.document_pages (
        tenant_id, document_id, page_number, text_content, text_sha256)
    SELECT p_tenant_id, p_document_id, r.page_number, r.text_content, r.text_sha256
    FROM pg_catalog.jsonb_to_recordset(COALESCE(p_pages, '[]'::jsonb))
        AS r(page_number int, text_content text, text_sha256 text);
    SELECT count(*) INTO v_count
    FROM public.document_pages
    WHERE document_id = p_document_id AND tenant_id = p_tenant_id;
    UPDATE public.documents
    SET page_count = v_count, updated_at = pg_catalog.now()
    WHERE id = p_document_id AND tenant_id = p_tenant_id;
    RETURN true;
END $$;
REVOKE ALL ON FUNCTION public.replace_document_pages(uuid, uuid, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.replace_document_pages(uuid, uuid, jsonb) TO validexia_app;
ALTER FUNCTION public.replace_document_pages(uuid, uuid, jsonb) OWNER TO validexia_documents_owner;

CREATE FUNCTION public.list_document_pages(p_tenant_id uuid, p_document_id uuid)
RETURNS TABLE (id uuid, page_number int, text_content text, text_sha256 text)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
    SELECT p.id, p.page_number, p.text_content, p.text_sha256
    FROM public.document_pages p
    WHERE p.document_id = p_document_id AND p.tenant_id = p_tenant_id
    ORDER BY p.page_number;
END $$;
REVOKE ALL ON FUNCTION public.list_document_pages(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_document_pages(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.list_document_pages(uuid, uuid) OWNER TO validexia_documents_owner;

-- Returns NULL when the document does not belong to the calling tenant (the
-- service maps that to 404); raises on a blank quote, which is a client error.
CREATE FUNCTION public.create_evidence(
    p_tenant_id uuid, p_document_id uuid, p_page_id uuid, p_page_number int,
    p_quote_text text, p_quote_sha256 text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_id uuid;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.documents d
        WHERE d.id = p_document_id AND d.tenant_id = p_tenant_id) THEN
        RETURN NULL;
    END IF;
    IF p_quote_text IS NULL OR pg_catalog.length(pg_catalog.btrim(p_quote_text)) = 0 THEN
        RAISE EXCEPTION 'quote text is required' USING ERRCODE = '22023';
    END IF;
    INSERT INTO public.evidence_records (
        tenant_id, document_id, page_id, page_number, quote_text, quote_sha256)
    VALUES (
        p_tenant_id, p_document_id, p_page_id, p_page_number, p_quote_text,
        COALESCE(p_quote_sha256, ''))
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;
REVOKE ALL ON FUNCTION public.create_evidence(uuid, uuid, uuid, int, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_evidence(uuid, uuid, uuid, int, text, text) TO validexia_app;
ALTER FUNCTION public.create_evidence(uuid, uuid, uuid, int, text, text) OWNER TO validexia_documents_owner;

CREATE FUNCTION public.list_evidence(p_tenant_id uuid, p_document_id uuid)
RETURNS TABLE (
    id uuid, document_id uuid, page_id uuid, page_number int, quote_text text,
    quote_sha256 text, created_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
    SELECT e.id, e.document_id, e.page_id, e.page_number, e.quote_text,
           e.quote_sha256, e.created_at
    FROM public.evidence_records e
    WHERE e.document_id = p_document_id AND e.tenant_id = p_tenant_id
    ORDER BY e.created_at, e.id;
END $$;
REVOKE ALL ON FUNCTION public.list_evidence(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_evidence(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.list_evidence(uuid, uuid) OWNER TO validexia_documents_owner;
