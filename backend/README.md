# Validexia Backend

The new Validexia backend, built from scratch on **Java 21 + Spring Boot 4.1.2 + PostgreSQL**
(the stack named in the founder's YC application). The React frontend is a separate project and
is not touched by this repository. The legacy PHP/LT-VRM codebase is not used, extended, or
depended on.

This repository currently contains **Mission M02-1 (v2.6)**: the foundation only — project
scaffold, Clerk-based identity, tenant isolation via PostgreSQL Row-Level Security, and a
tamper-evident audit log. No product workspaces, vendors, scoring, or AI features live here yet.

## Security model (summary)

- **Authentication — Clerk, verified locally.** The frontend signs in with Clerk and sends the
  Clerk session token as a bearer token. This service verifies it per request against Clerk's
  cached JWKS (RS256 only, issuer checked, `sid` required, and a verifier-side lifetime ceiling
  of 120 s — Clerk's configurable defaults are never relied on). No passwords, no locally issued
  tokens, no refresh-token machinery.
- **Identity directory — functions only.** Users/tenants/memberships live in PostgreSQL. The
  application role (`validexia_app`) has **zero direct grants** on those tables; all access goes
  through `SECURITY DEFINER` functions with a pinned `search_path = pg_catalog, pg_temp`.
- **Tenant isolation — RLS.** A tenant-aware transaction manager issues
  `set_config('app.tenant_id', …, true)` + `set_config('app.user_id', …, true)` as the first
  statements of every transaction. Tenant selection is per-request (`X-Tenant-Id` header) and
  membership is re-verified on every request.
- **Lifecycle — payload-driven.** Clerk has no ban/lock webhook events; ban/lock/deprovisioned
  state arrives via `user.updated` payloads and is applied per-flag with timestamp ordering
  (independent flags, restrictive tie-breaks, an epoch column that kills pre-transition tokens).
  Clerk's temporary lockouts lapse silently, so a locked-only block triggers a throttled (60 s,
  atomically claimed), transaction-free just-in-time resync against the Clerk Backend API.
- **Audit — database-enforced hash chain.** `audit_events` is append-only for the app role; a
  `SECURITY DEFINER` trigger owned by a dedicated role assigns sequence numbers and hashes
  (canonical serialization, SHA-256). Tamper-**evident**, not tamper-proof.

## Prerequisites

- JDK 21 (Temurin recommended)
- Gradle 9.1.0 (or the wrapper, once generated — see below)
- PostgreSQL 17 for runtime; Docker only if you want to run the integration tests locally
  (CI runs them via Testcontainers)

## Gradle wrapper

`gradle/wrapper/gradle-wrapper.jar` and `gradle-wrapper.properties` are committed, but the
`gradlew` / `gradlew.bat` scripts are **not yet** (they could not be generated in the sandbox
where this mission was implemented). Generate and commit them from any machine with Gradle:

```bash
gradle wrapper --gradle-version 9.1.0
```

Until then, invoke the installed `gradle` directly (CI does exactly this).

## Configuration

All configuration is via environment variables (see `src/main/resources/application.yaml`):

| Variable | Purpose |
| --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Application datasource. `DB_USER` must be `validexia_app` outside tests. |
| `FLYWAY_USER`, `FLYWAY_PASSWORD` | Migrator credentials used by Flyway at boot. Must differ from the app role in production. |
| `CLERK_ISSUER` | The Clerk instance's Frontend API URL (the expected token issuer). |
| `CLERK_JWKS_URL` | The instance's JWKS endpoint (`<issuer>/.well-known/jwks.json`). |
| `CLERK_AZP` | Comma-separated origins allowed in the `azp` claim. |
| `CLERK_WEBHOOK_SIGNING_SECRET` | The webhook endpoint's `whsec_…` secret (Svix). |
| `CLERK_SECRET_KEY` | Clerk Backend API key. Enables session revocation at Clerk and the locked-only JIT resync; without it those paths are disabled (local logout denylist still applies). |

## Run locally

The compose stack reads both local passwords from the environment
(`DB_PASSWORD` for the application role, `FLYWAY_PASSWORD` for the migrator),
each with a throwaway local-only default, so exporting either once keeps the
database and the app in agreement.

```bash
docker compose up -d db        # postgres:17 + dev roles (docker/init/01-roles.sh)
DB_PASSWORD=${DB_PASSWORD:-validexia-app-dev} \
FLYWAY_USER=validexia_migrator FLYWAY_PASSWORD=${FLYWAY_PASSWORD:-validexia-migrator-dev} \
CLERK_ISSUER=<your instance> CLERK_JWKS_URL=<your instance>/.well-known/jwks.json \
gradle bootRun
curl localhost:8080/api/v1/health
```

## Tests

```bash
gradle clean build
```

Integration tests use **Testcontainers** (`postgres:17`) and execute as the restricted
`validexia_app` role, with Flyway run as the migrator first — mirroring deployment. For
environments without Docker, the same suite can run against an external, disposable database:

```bash
VALIDEXIA_TEST_DB_URL=jdbc:postgresql://localhost:5432/validexia_test_scratch \
VALIDEXIA_TEST_DB_USER=postgres VALIDEXIA_TEST_DB_PASSWORD=... \
gradle test
```

(The named database must be freshly created; the suite migrates it and drops nothing.)

## Production bootstrap notes

1. **Roles first.** A DBA creates `validexia_app` (LOGIN, password set out-of-band),
   `validexia_directory_owner`, and `validexia_audit_owner` (both NOLOGIN), and grants the
   migrator membership in both owner roles so migrations can transfer ownership. The migration
   scripts create any missing roles idempotently but never set passwords.
2. **Clerk instance.** Create the Clerk application (founder action), configure the webhook
   endpoint for `user.created`, `user.updated`, `user.deleted`, `user.merged`,
   `session.created`, `session.ended`, `session.removed`, `session.revoked`, and set the env
   vars above. **Empirical check required at setup:** ban and lock a test user in the Dashboard
   and confirm a `user.updated` delivery arrives carrying the flags — the design is
   payload-driven and does not change if it doesn't, but a periodic reconciliation job then
   becomes a required follow-up. Capture real payload fixtures from the Dashboard's webhook
   Testing tab for the test suite.
3. **No Docker requirement in production.** Testcontainers is a test-only concern.

## Endpoints (M02-1)

| Method & path | Auth | Purpose |
| --- | --- | --- |
| `GET /api/v1/health` | public | `{status, version}` |
| `GET /api/v1/session` | Clerk token | Caller identity + memberships (+ active tenant if selected) |
| `POST /api/v1/session/active-tenant` | Clerk token | Validate/select the active tenant (audited) |
| `POST /api/v1/auth/logout` | Clerk token | Denylist the session locally + revoke at Clerk |
| `POST /api/v1/webhooks/clerk` | Svix signature | Clerk lifecycle webhooks |
