# P01-T05-B — Database roles, tenant RLS and transaction scoping

**Security classification:** internal foundation, not production multi-tenant certification. V3.0 and Phase 00 rules apply.

## Role boundary and startup
- PostgreSQL migrations run as a schema owner / migration role. Local dev continues to use the existing `aronexa_dev` bootstrap account for migration only; **never** for app runtime. Production provisioning must use an independently authorized migration role, not the local superuser model.
- `aronexa_app_runtime` is a separate LOGIN role with `NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT`; it does not own tenant tables. Its password must be different from migration credentials.
- A new, isolated container is bootstrapped using `deploy/dev/initdb/10-create-runtime-role.sh`, sourcing `ARONEXA_RUNTIME_DB_PASSWORD` from an untracked .env; the SQL migration never stores passwords. Testcontainers bootstrap uses *ephemeral synthetic test credentials*.
- An existing Docker volume will NOT rerun Postgres init scripts. Do **not** delete its volume to fix missing roles. Provision the runtime role safely and explicitly with owner approval; rerun validation, then Flyway. A missing role causes V4 GRANTs to fail closed.
- Spring `dev` sets separate `spring.datasource` (runtime) and `spring.flyway` (migration) credentials. Spring test profile remains a privileged ephemeral migrator solely for test seeding; dedicated tests open a separate connection as the actual runtime role.

## Protected tables and fail-closed policies
RLS enabled on **tenant, facility, branch, department**. Tenant role sees its tenant row only; facility/branch/department policies filter `tenant_id` on ALL operations and enforce WITH CHECK for writes. Child tables FORCE RLS even for an accidental owner connection; the migration owner can bypass RLS on tenant for a specifically scoped login lookup. Runtime role may not bypass RLS or own these tables.

The helper `aronexa_current_tenant_id()` reads a transaction-local GUC; missing/empty yields NULL and matches no rows, malformed UUID causes SQL failure. No session-wide `SET` should be used. `TenantSqlScope.bindVerifiedTenant()` requires an active Spring transaction and derives the UUID only from the verified Spring Security `AuthPrincipal`. It calls parameterized `set_config('aronexa.tenant_id', ?, true)`. All business queries must execute in the same transaction/connection.

### Pre-login authentication exception (material outstanding security work)
Login occurs before a verified tenant context. A restricted SECURITY DEFINER function `aronexa_login_tenant_id(text)` resolves an exact tenant code to UUID **only** and is owned by the migration/table owner. This avoids making the tenant directory globally queryable to the non-owner role; the function owner can bypass tenant RLS intentionally. Its EXECUTE privilege is only granted to the runtime role.

Existing P01-T04 authentication tables (`app_user`, `auth_session`, memberships/roles/permissions, audit) are **not yet covered by tenant RLS**, because the authentication bootstrap query needs controlled cross-tenant lookups. The current runtime role retains SQL privileges on these tables to preserve existing authentication; a compromised database-capable component could potentially read or modify other tenants' authentication data. **Do not consider complete multi-tenant data isolation or production-readiness established by this PR.** Separate authentication privilege boundaries/SECURITY DEFINER APIs must be designed, verified and approved before production and included in T05-D/owner review.

## Verified test contract
With a real non-owner PostgreSQL role: verify `rolsuper=false`, `rolbypassrls=false`, not owner, policies enabled; unscoped read zero/write rejected; tenant A cannot list/update/delete/insert tenant B's organization rows even if SQL omits tenant WHERE filter; malformed UUID fails; transaction commit/rollback on a one-connection Hikari pool clears tenant context; privileged login-code lookup does not expose tenant SELECT.

Existing HTTP authentication/organization tests must pass and Flyway V1–V4 must apply in a fresh Testcontainer. These are RLS table-level tests and do not replace future concurrent HTTP, branch/doctor/patient authorization tests.

## Design risks / next approval gate
- PostgreSQL custom settings are writable by SQL-capable sessions. This mechanism relies on trusted server authorization, parameterized SQL, and no arbitrary SQL execution; SQL injection could forge a GUC. RLS is not a replacement for server authorization.
- Generic JdbcTemplate calls made outside TenantSqlScope will be denied for protected organization tables under the runtime role. Before any organization business API is exposed, wrap such operations in an explicit authenticated transaction and assert tenant/permission checks.
- Passwords, user provisioning, auth data privileges and recovery require further hardening; no clinical data yet.
- No direct production/tenant migration, customer data, backup, deploy or destructive cleanup authorized.
- P01-T05-C/D require separate owner authorization after this PR is reviewed and merged.
