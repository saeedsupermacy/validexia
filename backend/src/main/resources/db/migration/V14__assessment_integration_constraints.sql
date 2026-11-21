-- V14: forward-only constraints connecting the documents/evidence slice (V13) to the
-- vendors slice (V12), so the assessments slice (V15) can reference both with real
-- composite tenant-scoped foreign keys.
--
-- This migration does NOT modify V12 or V13. It only ADDS constraints. Both are
-- forward-only, like every migration in this codebase.
--
-- WHY NOW: V13 was written before V12 existed and therefore left two references
-- unenforced (see the V13 header, "WHY vendor_id HAS NO FOREIGN KEY" and the
-- evidence_records.page_id note). V12 and V13 are both merged and the platform is
-- pre-launch, so the references can now be enforced without migrating real data.
--
-- 1. evidence_records (tenant_id, id) UNIQUE
--    V15 needs a composite foreign key from assessment_findings.evidence_record_id
--    to evidence_records. PostgreSQL requires the referenced column list to carry a
--    unique constraint; evidence_records has only PRIMARY KEY (id). (tenant_id, id)
--    is unique by construction because id alone is a primary key.
--
-- 2. documents.vendor_id -> vendors (tenant_id, id)
--    The column is NULLABLE, so a document may still be unattached to a vendor; a
--    NULL passes the foreign key unchecked (default MATCH SIMPLE). A non-null value
--    must now name a vendor of the SAME tenant.
--
--    Behavioural consequence, deliberate: attaching a document to a vendor that does
--    not exist in the tenant is now rejected instead of being stored as a dangling
--    reference. No rows are deleted, rewritten or fabricated to satisfy this
--    constraint; if any non-null vendor_id ever lacked a matching vendor, the
--    statement below would fail loudly rather than silently dropping the link.
--
-- SECURITY: these are integrity constraints only. They grant no new table access.
-- validexia_app still holds no direct grants on documents, evidence_records or
-- vendors; every read and write continues to go through the SECURITY DEFINER
-- functions. Referential-integrity checks are performed by PostgreSQL as the
-- referencing table's owner and are not a data path.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_constraint
        WHERE conname = 'evidence_records_tenant_id_key'
          AND conrelid = 'public.evidence_records'::regclass) THEN
        ALTER TABLE public.evidence_records
            ADD CONSTRAINT evidence_records_tenant_id_key UNIQUE (tenant_id, id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_constraint
        WHERE conname = 'documents_vendor_fk'
          AND conrelid = 'public.documents'::pg_catalog.regclass) THEN
        ALTER TABLE public.documents
            ADD CONSTRAINT documents_vendor_fk
            FOREIGN KEY (tenant_id, vendor_id)
            REFERENCES public.vendors (tenant_id, id)
            ON DELETE RESTRICT;
    END IF;
END $$;

-- ROLLBACK: forward-only. Undoing this means a NEW forward migration that drops the
-- two constraints (in that order). It is lossless: dropping a constraint removes no
-- data. The only cost is that the references become unenforced again.
