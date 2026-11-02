# P01-T05-A — Verified tenant authorization context

**Status:** READY FOR OWNER PR REVIEW — not merged, not deployed.
**Branch:** `feature/p01-t05a-tenant-policy`
**Draft PR:** https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/14
**CI evidence:** GitHub Actions [run 41419674753](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/41419674753) (2026-11-02 UTC), **4/4 jobs successful**.
**Classification:** INTERNAL_FOUNDATION / NOT READY for clinical or customer release.

## Approved scope and changes
- Added `TenantAuthorization` to read the verified tenant, account and membership from Spring SecurityContext, never untrusted X-Tenant-Id or other request selectors.
- `requireTenant`, `requirePermission` and `requireTenantPermission` reject missing/anonymous authentication, cross-tenant IDs, missing permissions, forced password change and role-name overrides.
- Existing `GET /api/v1/auth/me` now obtains the identity through `TenantAuthorization.currentPrincipal()` while retaining its response contract.
- HTTP security config explicitly allows only the four existing authentication endpoints, denying all unregistered routes by default even if the caller has an authenticated and password-updated token.
- No new DB migration, external dependencies, clinical feature, new API, RLS or deployment.

## Executed verification
| Command / test | Result and evidence |
|---|---|
| GitHub Actions documentation validation | PASS |
| PR scope/dangerous files check | PASS |
| Limited tracked-secret patterns scan | PASS (not full secrets audit) |
| `docker compose -f deploy/dev/compose.yml config --quiet` | PASS in CI |
| `docker info` | PASS in CI |
| `mvn -B -f backend/pom.xml verify` | **PASS — 34 Java tests, 0 failures, 0 errors, 0 skipped** |
| `TenantAuthorizationTests` | PASS — 8 tenant/permission negative and positive checks |
| `AuthenticationIntegrationTests` | PASS — 12 PostgreSQL/MockMvc HTTP cases, including forged tenant header and deny-unregistered-route after password change |
| `OrganizationPersistenceTests` | PASS — 9 existing DB hierarchy and tenant-aware FK tests |
| `AronexaApplicationTests` | PASS — 4 existing Flyway/context tests, schema V3 |
| `ArchitectureTests` | PASS — 1 Spring Modulith boundary test |
| `npm ci --no-audit --no-fund && npm run build` | PASS — Vite/TypeScript build completed |

## Explicit security boundaries and known gaps
- This establishes **application-layer verified tenant/permission prerequisites** and fail-closed route registration; it is NOT a complete tenant isolation implementation.
- No PostgreSQL row-level security, runtime/migration DB role separation, transactional SET LOCAL tenant context or pooled-connection leak tests — required in **P01-T05-B**.
- No branch, doctor or patient/encounter IDOR authorization, audit of protected clinical access, controlled emergency support or protected clinical endpoints — required in **P01-T05-C/D**.
- No real patient data, production/client deployment or customer database migration.
- Auth token loading still depends on existing P01-T04 session validation and is not equivalent to comprehensive privacy/compliance review. A no-PHI superadmin policy remains mandatory.
- Existing GitHub private-organization ruleset does not enforce checks on current plan; owner must review latest CI and full PR diff before merge.

## Release / approval
V3 product and AI requirements unchanged. Back-end auth HTTP security now denies future controllers until explicit registration and service policies are approved. No patient-facing UI was added. Not production-ready. Review and merge only with explicit owner approval; do **not** begin P01-T05-B/C/D without approval.
