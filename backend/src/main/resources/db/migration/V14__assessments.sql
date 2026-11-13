-- V14: Assessments + findings — the questionnaire/evidence slice of the platform
-- data layer (docs/ai/AI-FOUNDATION-DESIGN.md, increment 3).
--
-- SCOPE. An assessment belongs to a tenant and targets a vendor. It holds the
-- questionnaire claims extracted from the vendor's answers (assessment_claims)
-- and the findings produced by comparing those claims against document evidence
-- (assessment_findings). A finding carries a deterministic severity, an optional
-- soft reference to the claim it was derived from, and the evidence citation it
-- rests on. Findings are never born approved: they are created Draft or Pending
-- Approval and can only reach Approved/Rejected through decide_finding, which is
-- a human decision recorded with actor, reason and timestamp.
--
-- NO-FK DECISION (cross-slice contract). The vendors slice (V12) and the
-- documents slice are separate, still-open branches. This migration therefore
-- does NOT reference them:
--   * assessments.vendor_id is a plain uuid. A follow-up migration adds
--     FOREIGN KEY (tenant_id, vendor_id) REFERENCES public.vendors (tenant_id, id)
--     once the vendors slice is merged.
--   * assessment_findings.evidence_record_id is a plain uuid, and
--     evidence_page_number / evidence_quote / evidence_quote_sha256 are plain
--     values copied from the documents slice's evidence_records row. A follow-up
--     migration adds the composite FK to public.evidence_records (tenant_id, id)
--     once that slice is merged.
--   * assessment_findings.claim_id is also a soft reference, deliberately: a
--     finding must survive replace_assessment_claims (the claim set is replaced
--     as a whole when extraction re-runs), so a hard FK with RESTRICT would make
--     re-extraction impossible and CASCADE/SET NULL would silently destroy the
--     finding-to-claim link. create_finding verifies at write time that a supplied
--     claim belongs to the same assessment and tenant, so the soft reference
--     cannot dangle on insert.
--
-- Termination is a status flip, never a delete: assessment history must survive
-- for audit. The only writes are the functions below.
--
-- ROLLBACK: forward-only, like every migration in this codebase. Undoing it
-- after merge means a NEW forward migration that drops the functions, then the
-- tables, then the owner role (in that order). That rollback is lossless only
-- while no assessment data exists; once real assessments and findings have been
-- created, dropping the tables destroys that data by definition.

DO $$
BEGIN
    CREATE ROLE validexia_assessments_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
EXCEPTION WHEN duplicate_object THEN
    NULL;
END $$;

-- Tables ------------------------------------------------------------------------

CREATE TABLE public.assessments (
    id uuid PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    -- Plain uuid: no FK to the vendors slice (see NO-FK DECISION above).
    vendor_id uuid NOT NULL,
    status text NOT NULL DEFAULT 'Draft'
        CHECK (status IN ('Draft', 'In Review', 'Approved', 'Rejected')),
    created_by_user_id uuid NOT NULL REFERENCES public.users(id),
    created_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    updated_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    -- Needed by the composite FKs from claims/findings, and by every index below.
    UNIQUE (tenant_id, id)
);

CREATE TABLE public.assessment_claims (
    id uuid PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id uuid NOT NULL,
    assessment_id uuid NOT NULL,
    question_text text NOT NULL,
    answer_text text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    -- Composite FK: a claim can only attach to an assessment of the SAME tenant,
    -- and RESTRICT (never CASCADE) so assessment history cannot be deleted away.
    FOREIGN KEY (tenant_id, assessment_id) REFERENCES public.assessments (tenant_id, id)
        ON DELETE RESTRICT
);
CREATE INDEX assessment_claims_assessment_idx
    ON public.assessment_claims (tenant_id, assessment_id);

CREATE TABLE public.assessment_findings (
    id uuid PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id uuid NOT NULL,
    assessment_id uuid NOT NULL,
    -- Soft reference to assessment_claims (see NO-FK DECISION above).
    claim_id uuid,
    finding_type text NOT NULL
        CHECK (finding_type IN ('Supported', 'Unsupported', 'Contradictory', 'Missing')),
    -- Deterministic code owns this value; the model never supplies it.
    severity text NOT NULL
        CHECK (severity IN ('Critical', 'High', 'Medium', 'Low')),
    title text NOT NULL,
    explanation text NOT NULL,
    -- Evidence citation, copied from the documents slice (plain values, no FK).
    evidence_record_id uuid,
    evidence_page_number int,
    evidence_quote text,
    evidence_quote_sha256 text,
    status text NOT NULL DEFAULT 'Draft'
        CHECK (status IN ('Draft', 'Pending Approval', 'Approved', 'Rejected')),
    decided_by_user_id uuid,
    decided_at timestamptz,
    decision_reason text,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    updated_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    FOREIGN KEY (tenant_id, assessment_id) REFERENCES public.assessments (tenant_id, id)
        ON DELETE RESTRICT,
    -- A citation is stored whole or not at all: a quote without its page number
    -- and hash is not a citation, it is a future verification failure.
    CHECK (evidence_quote IS NULL
           OR (evidence_page_number IS NOT NULL AND evidence_quote_sha256 IS NOT NULL))
);
CREATE INDEX assessment_findings_assessment_idx
    ON public.assessment_findings (tenant_id, assessment_id);

ALTER TABLE public.assessments       OWNER TO validexia_assessments_owner;
ALTER TABLE public.assessment_claims  OWNER TO validexia_assessments_owner;
ALTER TABLE public.assessment_findings OWNER TO validexia_assessments_owner;

-- The creator FK's integrity check reads public.users as the referencing table's
-- owner; granted to that owner only, never to the application role.
GRANT SELECT ON public.users TO validexia_assessments_owner;

ALTER TABLE public.assessments       ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.assessments       FORCE ROW LEVEL SECURITY;
ALTER TABLE public.assessment_claims  ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.assessment_claims  FORCE ROW LEVEL SECURITY;
ALTER TABLE public.assessment_findings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.assessment_findings FORCE ROW LEVEL SECURITY;

-- Policy form matches V5/V6/V12 (no TO clause; FOR ALL with USING/WITH CHECK).
CREATE POLICY assessments_owner_tenant ON public.assessments
    FOR ALL
    USING (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true));

CREATE POLICY assessment_claims_owner_tenant ON public.assessment_claims
    FOR ALL
    USING (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true));

CREATE POLICY assessment_findings_owner_tenant ON public.assessment_findings
    FOR ALL
    USING (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = pg_catalog.current_setting('app.tenant_id', true));

-- ---------------------------------------------------------------------------
-- Functions. All SECURITY DEFINER with pinned search_path, all fail-closed:
-- the transaction's app.tenant_id must EXACTLY equal the tenant argument
-- (missing, empty or different all raise), the V6 rule.
-- ---------------------------------------------------------------------------

CREATE FUNCTION public.create_assessment(
    p_tenant_id uuid, p_vendor_id uuid, p_created_by_user_id uuid)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_id uuid;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    INSERT INTO public.assessments (tenant_id, vendor_id, created_by_user_id)
    VALUES (p_tenant_id, p_vendor_id, p_created_by_user_id)
    RETURNING id INTO v_id;
    RETURN v_id;
END $$;
REVOKE ALL ON FUNCTION public.create_assessment(uuid, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_assessment(uuid, uuid, uuid) TO validexia_app;
ALTER FUNCTION public.create_assessment(uuid, uuid, uuid) OWNER TO validexia_assessments_owner;

CREATE FUNCTION public.get_assessment(p_tenant_id uuid, p_assessment_id uuid)
RETURNS TABLE (
    id uuid, tenant_id uuid, vendor_id uuid, status text, created_by_user_id uuid,
    created_at timestamptz, updated_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
    SELECT a.id, a.tenant_id, a.vendor_id, a.status, a.created_by_user_id,
           a.created_at, a.updated_at
    FROM public.assessments a
    WHERE a.tenant_id = p_tenant_id AND a.id = p_assessment_id;
END $$;
REVOKE ALL ON FUNCTION public.get_assessment(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_assessment(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.get_assessment(uuid, uuid) OWNER TO validexia_assessments_owner;

-- p_vendor_id NULL means "every vendor in the tenant".
CREATE FUNCTION public.list_assessments(p_tenant_id uuid, p_vendor_id uuid)
RETURNS TABLE (
    id uuid, tenant_id uuid, vendor_id uuid, status text, created_by_user_id uuid,
    created_at timestamptz, updated_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
    SELECT a.id, a.tenant_id, a.vendor_id, a.status, a.created_by_user_id,
           a.created_at, a.updated_at
    FROM public.assessments a
    WHERE a.tenant_id = p_tenant_id
      AND (p_vendor_id IS NULL OR a.vendor_id = p_vendor_id)
    ORDER BY a.created_at DESC;
END $$;
REVOKE ALL ON FUNCTION public.list_assessments(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_assessments(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.list_assessments(uuid, uuid) OWNER TO validexia_assessments_owner;

-- Status transitions are deterministic and enforced here, not by the caller:
-- Draft -> In Review, In Review -> Approved/Rejected, Draft -> Rejected.
-- Approved and Rejected are terminal. Returns false when nothing matched.
CREATE FUNCTION public.set_assessment_status(
    p_tenant_id uuid, p_assessment_id uuid, p_status text)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF p_status NOT IN ('Draft', 'In Review', 'Approved', 'Rejected') THEN
        RAISE EXCEPTION 'unknown assessment status: %', p_status;
    END IF;
    UPDATE public.assessments
    SET status = p_status, updated_at = pg_catalog.now()
    WHERE id = p_assessment_id AND tenant_id = p_tenant_id
      AND status <> p_status
      AND status NOT IN ('Approved', 'Rejected');
    RETURN FOUND;
END $$;
REVOKE ALL ON FUNCTION public.set_assessment_status(uuid, uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.set_assessment_status(uuid, uuid, text) TO validexia_app;
ALTER FUNCTION public.set_assessment_status(uuid, uuid, text) OWNER TO validexia_assessments_owner;

-- The claim set is replaced as a whole, in the caller's transaction: a partial
-- replacement is never observable. p_claims is a jsonb array of
-- {"question_text": ..., "answer_text": ...}. A missing key is a NOT NULL
-- violation on purpose: a claim without its question or answer is malformed.
CREATE FUNCTION public.replace_assessment_claims(
    p_tenant_id uuid, p_assessment_id uuid, p_claims jsonb)
RETURNS int
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_count int;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.assessments a
                   WHERE a.id = p_assessment_id AND a.tenant_id = p_tenant_id) THEN
        RAISE EXCEPTION 'assessment not found in tenant';
    END IF;
    DELETE FROM public.assessment_claims
    WHERE tenant_id = p_tenant_id AND assessment_id = p_assessment_id;
    INSERT INTO public.assessment_claims (tenant_id, assessment_id, question_text, answer_text)
    SELECT p_tenant_id, p_assessment_id, c->>'question_text', c->>'answer_text'
    FROM pg_catalog.jsonb_array_elements(COALESCE(p_claims, '[]'::jsonb)) AS c;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    UPDATE public.assessments
    SET updated_at = pg_catalog.now()
    WHERE id = p_assessment_id AND tenant_id = p_tenant_id;
    RETURN v_count;
END $$;
REVOKE ALL ON FUNCTION public.replace_assessment_claims(uuid, uuid, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.replace_assessment_claims(uuid, uuid, jsonb) TO validexia_app;
ALTER FUNCTION public.replace_assessment_claims(uuid, uuid, jsonb) OWNER TO validexia_assessments_owner;

CREATE FUNCTION public.list_assessment_claims(
    p_tenant_id uuid, p_assessment_id uuid)
RETURNS TABLE (id uuid, question_text text, answer_text text, created_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
    SELECT c.id, c.question_text, c.answer_text, c.created_at
    FROM public.assessment_claims c
    WHERE c.tenant_id = p_tenant_id AND c.assessment_id = p_assessment_id
    ORDER BY c.created_at;
END $$;
REVOKE ALL ON FUNCTION public.list_assessment_claims(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_assessment_claims(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.list_assessment_claims(uuid, uuid) OWNER TO validexia_assessments_owner;

-- A finding is never created decided. p_status may only be Draft or
-- Pending Approval: approval is a human decision recorded by decide_finding,
-- with actor, reason and timestamp. The claim soft reference is verified to
-- belong to this assessment and tenant at write time.
CREATE FUNCTION public.create_finding(
    p_tenant_id uuid, p_assessment_id uuid, p_claim_id uuid,
    p_finding_type text, p_severity text, p_title text, p_explanation text,
    p_evidence_record_id uuid, p_evidence_page_number int,
    p_evidence_quote text, p_evidence_quote_sha256 text, p_status text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_id uuid;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.assessments a
                   WHERE a.id = p_assessment_id AND a.tenant_id = p_tenant_id) THEN
        RAISE EXCEPTION 'assessment not found in tenant';
    END IF;
    IF p_finding_type NOT IN ('Supported', 'Unsupported', 'Contradictory', 'Missing') THEN
        RAISE EXCEPTION 'unknown finding type: %', p_finding_type;
    END IF;
    IF p_severity NOT IN ('Critical', 'High', 'Medium', 'Low') THEN
        RAISE EXCEPTION 'unknown severity: %', p_severity;
    END IF;
    IF p_status NOT IN ('Draft', 'Pending Approval') THEN
        RAISE EXCEPTION 'a finding cannot be created as %; approval requires a human decision',
            p_status;
    END IF;
    IF p_claim_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM public.assessment_claims c
                       WHERE c.id = p_claim_id AND c.tenant_id = p_tenant_id
                         AND c.assessment_id = p_assessment_id) THEN
        RAISE EXCEPTION 'claim not found in assessment';
    END IF;
    INSERT INTO public.assessment_findings (
        tenant_id, assessment_id, claim_id, finding_type, severity, title, explanation,
        evidence_record_id, evidence_page_number, evidence_quote, evidence_quote_sha256,
        status)
    VALUES (
        p_tenant_id, p_assessment_id, p_claim_id, p_finding_type, p_severity, p_title,
        p_explanation, p_evidence_record_id, p_evidence_page_number, p_evidence_quote,
        p_evidence_quote_sha256, p_status)
    RETURNING id INTO v_id;
    UPDATE public.assessments
    SET updated_at = pg_catalog.now()
    WHERE id = p_assessment_id AND tenant_id = p_tenant_id;
    RETURN v_id;
END $$;
REVOKE ALL ON FUNCTION public.create_finding(uuid, uuid, uuid, text, text, text, text, uuid, int, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_finding(uuid, uuid, uuid, text, text, text, text, uuid, int, text, text, text) TO validexia_app;
ALTER FUNCTION public.create_finding(uuid, uuid, uuid, text, text, text, text, uuid, int, text, text, text) OWNER TO validexia_assessments_owner;

CREATE FUNCTION public.list_findings(p_tenant_id uuid, p_assessment_id uuid)
RETURNS TABLE (
    id uuid, tenant_id uuid, assessment_id uuid, claim_id uuid, finding_type text,
    severity text, title text, explanation text, evidence_record_id uuid,
    evidence_page_number int, evidence_quote text, evidence_quote_sha256 text,
    status text, decided_by_user_id uuid, decided_at timestamptz,
    decision_reason text, created_at timestamptz, updated_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    RETURN QUERY
    SELECT f.id, f.tenant_id, f.assessment_id, f.claim_id, f.finding_type, f.severity,
           f.title, f.explanation, f.evidence_record_id, f.evidence_page_number,
           f.evidence_quote, f.evidence_quote_sha256, f.status, f.decided_by_user_id,
           f.decided_at, f.decision_reason, f.created_at, f.updated_at
    FROM public.assessment_findings f
    WHERE f.tenant_id = p_tenant_id AND f.assessment_id = p_assessment_id
    ORDER BY f.created_at;
END $$;
REVOKE ALL ON FUNCTION public.list_findings(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.list_findings(uuid, uuid) TO validexia_app;
ALTER FUNCTION public.list_findings(uuid, uuid) OWNER TO validexia_assessments_owner;

-- The only path to Approved/Rejected. A decided finding is terminal: a second
-- decision returns false rather than overwriting the first. Returns false when
-- the finding is not in this tenant or is not awaiting a decision.
CREATE FUNCTION public.decide_finding(
    p_tenant_id uuid, p_finding_id uuid, p_decision text,
    p_decided_by uuid, p_reason text)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE v_rows int;
BEGIN
    IF pg_catalog.current_setting('app.tenant_id', true) IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'tenant context mismatch';
    END IF;
    IF p_decision NOT IN ('Approved', 'Rejected') THEN
        RAISE EXCEPTION 'decision must be Approved or Rejected';
    END IF;
    UPDATE public.assessment_findings
    SET status = p_decision,
        decided_by_user_id = p_decided_by,
        decided_at = pg_catalog.now(),
        decision_reason = p_reason,
        updated_at = pg_catalog.now()
    WHERE id = p_finding_id AND tenant_id = p_tenant_id
      AND status IN ('Draft', 'Pending Approval');
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RETURN v_rows = 1;
END $$;
REVOKE ALL ON FUNCTION public.decide_finding(uuid, uuid, text, uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.decide_finding(uuid, uuid, text, uuid, text) TO validexia_app;
ALTER FUNCTION public.decide_finding(uuid, uuid, text, uuid, text) OWNER TO validexia_assessments_owner;
