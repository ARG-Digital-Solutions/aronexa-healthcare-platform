# P01-T05-D — Adversarial security and authentication-role boundary

**Target:** existing organization and authentication foundation. No clinician, patient, encounter or production endpoints exist.

## Threats tested
1. The organization runtime connection must not directly read, alter or inspect global auth accounts, roles, membership or sessions. The independent authentication runtime role receives only explicitly required grants.
2. Authentication role must not SELECT facility/branch/department and must not bypass PostgreSQL tenant policies.
3. A verified tenant identity must be set transaction-locally. Reused pooled connections must not retain a previous tenant context.
4. Concurrent read requests from different tenants and memberships must return only assigned resources. Same-tenant unassigned branches/department must be absent.
5. Forged tenant/branch headers, unknown object IDs, expired/revoked sessions, forced-password-change restrictions, and omitted permissions must fail closed. No default platform administrator access to clinical records.
6. Global authentication tables remain outside organization RLS because login precedes tenant verification; separating DB roles limits privileges but is **not** a full tenant-aware auth-data isolation solution. Do not claim otherwise.

## Migration and deployment constraints
- V6 is additive grants/revokes only, never edit V1–V5.
- New `aronexa_auth_runtime` role must be explicitly provisioned before V6. No Docker volume deletion to force role bootstrap.
- Local development needs distinct `ARONEXA_DB_PASSWORD`, `ARONEXA_RUNTIME_DB_PASSWORD` and `ARONEXA_AUTH_DB_PASSWORD`; only env placeholders are committed.
- A new `authDataSource` is used for authentication SQL and auth transactions in development. The organization datasource uses a separate restricted identity.
- Tests running with the `test` Spring profile continue to load synthetic fixtures through an ephemeral migration owner; separate integration tests **must** verify both restricted roles and unauthenticated access denial.

## Before clinical production
The global auth runtime still has broad account/session SQL privileges; harden using verified-entrypoint DB functions or separated identity service, reviewed emergency/support access, MFA, audited provisioning and account recovery, distributed throttling, full clinical IDOR and concurrency testing. This task cannot authorize production patient information.

## Acceptance
A draft PR must demonstrate CI success, migration V6, direct cross-role privilege denial, concurrent tenant isolation, auth login/logout still operating, and frontend regression. A security reviewer must explicitly accept residual risks before owner merge. No merge, deploy or advancement without owner's approval.
