-- Local-dev bootstrap ONLY. Pre-creates the roles the Flyway migrations manage
-- (the migrations' role blocks are idempotent and will skip these), so the
-- application role can be given its dev password before the first app boot.
-- Production role/password provisioning is a DBA task (see README).
CREATE ROLE validexia_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
    NOBYPASSRLS PASSWORD 'validexia-app-dev';
CREATE ROLE validexia_directory_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS;
CREATE ROLE validexia_audit_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS;
-- The migrator must be a member of the owner roles to transfer object ownership
-- to them during migrations.
GRANT validexia_directory_owner TO validexia_migrator;
GRANT validexia_audit_owner TO validexia_migrator;
