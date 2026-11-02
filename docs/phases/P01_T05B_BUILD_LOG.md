# P01-T05-B — PostgreSQL RLS and least-privileged runtime roles

**Status:** READY FOR OWNER REVIEW — not merged or deployed.
**Branch:** `feature/p01-t05b-postgres-rls`
**Draft PR:** https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/15
**CI evidence:** [GitHub Actions run 41421909650](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/41421909650), on commit `8c8e770`, completed SUCCESS on 2026-11-02.
**Release classification:** INTERNAL_FOUNDATION / NOT READY for clinical/customer usage.

## Approved scope and change summary
- Flyway **V4** adds tenant, facility, branch, department RLS policies and least-privileged runtime access grants. Earlier V1–V3 untouched.
- `aronexa_app_runtime` role created before migrations in the isolated test database / fresh local dev volume: LOGIN, non-owner, non-superuser, `NOBYPASSRLS`.
- Spring `dev` uses runtime datasource credentials separately from Flyway migration credentials. Missing bootstrap role fails closed; existing dev volume must never be reset to force creation.
- Parameterized `set_config('aronexa.tenant_id', verifiedTenant, true)` in existing transaction via `TenantSqlScope`; no caller-supplied tenant header context.
- A narrow `SECURITY DEFINER` tenant-code lookup supports authentication bootstrap without globally revealing tenant directory rows.
- Real restricted-role SQL tests cover unscoped denial, correct/mismatched tenant, DML restrictions, pool context cleanup, malformed settings, actual login/logout SQL and transactional service binder.
- Existing front-end and clinical feature sets unchanged. No production database, tenant/customer data or new patient/clinical APIs.

## Executed verification
| Command / validation | Result |
|---|---|
| GitHub Actions `Documentation validation` | PASS |
| GitHub Actions `PR scope and dangerous-file checks` | PASS |
| GitHub Actions `Tracked secret patterns` | PASS (limited regex check only) |
| `docker compose -f deploy/dev/compose.yml config --quiet` with synthetic CI env | PASS |
| `docker info` | PASS |
| `mvn -B -f backend/pom.xml verify` with PostgreSQL/Testcontainers | **PASS: 42 tests, zero failures/errors/skips** |
| `PostgresTenantRlsTests` | **PASS: 8 tests** including restricted role, cross-tenant DML, no-context, pooled connection and runtime authentication |
| `AuthenticationIntegrationTests` | PASS: 12 |
| `TenantAuthorizationTests` | PASS: 8 |
| `OrganizationPersistenceTests` | PASS: 9 |
| `AronexaApplicationTests` | PASS: 4 |
| `ArchitectureTests` | PASS: 1 |
| Flyway V1–V4 migrations | PASS: schema at V4 in fresh containers |
| `npm ci --no-audit --no-fund && npm run build` | PASS: TypeScript/Vite production build |

## Historical failure and correction
- Initial PR CI failed in Docker Compose syntax preflight because the workflow lacked the newly required `ARONEXA_RUNTIME_DB_PASSWORD` variable. Corrected to a synthetic CI placeholder; no credential stored or released.
- Subsequent runtime-role tests and frontend build passed; no failure carried into the verified revision.

## Explicit outstanding security limitations
- **Authentication schema is NOT RLS protected.** Existing runtime role still has SELECT/UPDATE privileges needed by the pre-login auth implementation, and this presents a material cross-tenant SQL access risk if server code or DB connection is compromised. Do not handle live patient records or claim full cross-tenant isolation until the auth privilege boundary and its tests are separately approved and hardened.
- Custom PostgreSQL GUCs can be manipulated by arbitrary SQL with the same role; this implementation relies on trusted server identity verification and safe parameterized queries, not arbitrary SQL execution.
- Full branch, doctor, patient and encounter policies, direct-object IDOR tests and adversarial concurrent service/HTTP isolation tests remain P01-T05-C/D.
- Dev role bootstrap init script only executes on a fresh Docker volume; existing local database migration rollout is not exercised. No production-specific migration-role provisioning, restore rehearsal, or customer deployment.
- GitHub private organization ruleset is saved but not technically enforced under current plan; owner must manually inspect latest checks and PR diff before merge.

## V3.0 16-field review snapshot
1. Phase/version: P01-T05-B internal foundation.
2. Existing code: reused P01-T01–T05-A approved modular monolith.
3. Added feature: tenant RLS and runtime role boundary; no clinical functionality.
4. DB/API: additive Flyway V4; no new public APIs.
5. UI/stylus: unchanged.
6. Security/clinical: organization-table RLS tested; auth schema exposure outstanding; no PHI.
7. Commands/outcomes: workflow 41421909650, Maven 42 tests and frontend build PASS.
8. Regression: preexisting authentication, organization, Spring Modulith, Flyway PASS.
9. Compliance: design-only; clinical/legal sign-off still required.
10. Modes: local dev/test isolation only; cloud/offline production adapters not approved.
11. Artifacts: draft PR #15, V4 SQL, role bootstrap scripts, RLS integration tests.
12. Issues: missing CI environment variable fixed; remaining auth access boundary documented.
13. Recovery: forward-only migration; no Flyway clean or destructive rollback tested.
14. Customer acceptance: NOT RUN.
15. Classification: INTERNAL_FOUNDATION / NOT READY.
16. Recommendation: OWNER REVIEW of PR #15; do not merge/deploy or start T05-C without express approval.
