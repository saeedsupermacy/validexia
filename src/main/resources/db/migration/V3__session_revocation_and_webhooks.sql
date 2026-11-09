-- V3: session revocation denylist + webhook idempotency store.
-- Neither table carries RLS (no tenant column); protection is grant-based and
-- total: validexia_app holds NO direct grants on either table. All access flows
-- through the definer functions below (M02-1 v2.4 §5).

CREATE TABLE public.revoked_sessions (
    sid           text PRIMARY KEY,
    user_subject  text NOT NULL,
    expires_at    timestamptz NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.webhook_events (
    svix_id      text PRIMARY KEY,
    event_type   text NOT NULL,
    received_at  timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    outcome      text
);

ALTER TABLE public.revoked_sessions OWNER TO validexia_directory_owner;
ALTER TABLE public.webhook_events   OWNER TO validexia_directory_owner;

CREATE OR REPLACE FUNCTION public.record_session_revocation(p_sid text, p_subject text, p_expires_at timestamptz)
RETURNS void
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    INSERT INTO public.revoked_sessions (sid, user_subject, expires_at)
    VALUES (p_sid, p_subject, p_expires_at)
    ON CONFLICT (sid) DO NOTHING
$$;
REVOKE ALL ON FUNCTION public.record_session_revocation(text, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.record_session_revocation(text, text, timestamptz) TO validexia_app;
ALTER FUNCTION public.record_session_revocation(text, text, timestamptz) OWNER TO validexia_directory_owner;

CREATE OR REPLACE FUNCTION public.check_session_revoked(p_sid text)
RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT EXISTS (SELECT 1 FROM public.revoked_sessions r
                   WHERE r.sid = p_sid AND r.expires_at > pg_catalog.now())
$$;
REVOKE ALL ON FUNCTION public.check_session_revoked(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.check_session_revoked(text) TO validexia_app;
ALTER FUNCTION public.check_session_revoked(text) OWNER TO validexia_directory_owner;

-- Insert-first idempotency: returns true only for the first delivery of an event.
CREATE OR REPLACE FUNCTION public.record_webhook_event(p_svix_id text, p_event_type text)
RETURNS boolean
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    INSERT INTO public.webhook_events (svix_id, event_type) VALUES (p_svix_id, p_event_type)
    ON CONFLICT (svix_id) DO NOTHING;
    RETURN FOUND;
END $$;
REVOKE ALL ON FUNCTION public.record_webhook_event(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.record_webhook_event(text, text) TO validexia_app;
ALTER FUNCTION public.record_webhook_event(text, text) OWNER TO validexia_directory_owner;

CREATE OR REPLACE FUNCTION public.mark_webhook_event(p_svix_id text, p_outcome text)
RETURNS void
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    UPDATE public.webhook_events
    SET processed_at = pg_catalog.now(), outcome = p_outcome
    WHERE svix_id = p_svix_id
$$;
REVOKE ALL ON FUNCTION public.mark_webhook_event(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mark_webhook_event(text, text) TO validexia_app;
ALTER FUNCTION public.mark_webhook_event(text, text) OWNER TO validexia_directory_owner;

-- Retention purge with hard-coded predicates: expired revocations, and webhook
-- events older than 30 days. The EXECUTE grant cannot be used to delete fresh rows.
CREATE OR REPLACE FUNCTION public.purge_expired_security_rows()
RETURNS void
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    DELETE FROM public.revoked_sessions WHERE expires_at < pg_catalog.now();
    DELETE FROM public.webhook_events WHERE received_at < pg_catalog.now() - interval '30 days'
$$;
REVOKE ALL ON FUNCTION public.purge_expired_security_rows() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.purge_expired_security_rows() TO validexia_app;
ALTER FUNCTION public.purge_expired_security_rows() OWNER TO validexia_directory_owner;
