#!/bin/bash
# Local-dev bootstrap ONLY. Pre-creates the roles the Flyway migrations manage
# (the migrations' role blocks are idempotent and will skip these), so the
# application role can be given its dev password before the first app boot.
# Production role/password provisioning is a DBA task (see README).
#
# The application role's password comes from DB_PASSWORD (passed into the
# container by docker-compose; the same variable the app itself uses on the
# host), with a throwaway local-only default — never a production credential.
# It reaches the SQL as a psql variable and is substituted with
# :'app_password', which quotes it as a SQL literal (single quotes are
# escaped by psql), so a password containing quotes or dollar signs cannot
# break out of the literal. The heredoc delimiter is quoted, so the shell
# performs no expansion of its own on the SQL text.
set -e

APP_PASSWORD="${DB_PASSWORD:-validexia-app-dev}"

psql -v ON_ERROR_STOP=1 -v app_password="$APP_PASSWORD" \
    --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-'EOSQL'
    CREATE ROLE validexia_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
        NOBYPASSRLS PASSWORD :'app_password';
    CREATE ROLE validexia_directory_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
        NOREPLICATION NOBYPASSRLS;
    CREATE ROLE validexia_audit_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
        NOREPLICATION NOBYPASSRLS;
    -- The migrator must be a member of the owner roles to transfer object ownership
    -- to them during migrations.
    GRANT validexia_directory_owner TO validexia_migrator;
    GRANT validexia_audit_owner TO validexia_migrator;
EOSQL
