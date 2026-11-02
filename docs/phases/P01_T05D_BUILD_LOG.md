# P01-T05-D — Adversarial tenant isolation and authentication privilege boundary

**Status:** READY FOR OWNER SECURITY REVIEW — NOT MERGED / NOT DEPLOYED.
**Branch:** `security/p01-t05d-adversarial-auth-isolation`
**Draft PR:** https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/17
**CI evidence:** [GitHub Actions run 41431681968](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/41431681968), successful on commit `540644b`, 2026-11-02 UTC.
**Release classification:** INTERNAL SECURITY FOUNDATION; NOT clinically or production approved.

## Delivered
- Forward-only Flyway V6 revokes **all direct authentication-table grants** from `aronexa_app_runtime`, and transfers login tenant lookup EXECUTE from organization runtime to `aronexa_auth_runtime`.
- A separate, non-owner, non-BYPASSRLS authentication DB role with only existing auth-SQL-required table grants. This role has no direct SELECT on tenant/branch/facility/department or assignment tables.
- Separate Spring auth JDBC pool and dedicated auth transaction manager in the dev profile; organization JDBC pool and transaction manager remain separate. All three local passwords must be distinct and untracked.
- Explicit ephemeral Testcontainers datasource for `test` profile, plus direct non-owner SQL connections to **both** runtime roles for independent privilege assertions.
- Real PostgreSQL negative tests for cross-role SELECT/UPDATE denial, no RLS bypass or ownership, concurrent two-tenant pool reuse (2 workers × 5 transactions) and inherited auth/organization security regression.
- No HTTP routes or patient/doctor/encounter schemas introduced; application authentication contract unchanged.
- Existing V1–V5 migrations immutable. V6 GRANT/REVOKE migration fails closed unless both roles have been safely bootstrapped.

## Verified checks
| Check | Result |
|---|---|
| GitHub Actions documentation validation | PASS |
| PR scope and dangerous-file checks | PASS |
| Limited tracked-secret patterns | PASS |
| Docker Compose syntax and Docker runtime | PASS |
| `mvn -B -f backend/pom.xml verify` | **PASS: 53 Java tests, 0 failures/errors/skips** |
| `AuthRoleBoundaryTests` | PASS: 4 PostgreSQL tests (restricted SQL roles + concurrent tenants) |
| `PostgresTenantRlsTests` | PASS: 8 |
| `OrganizationAccessIntegrationTests` | PASS: 7 |
| `AuthenticationIntegrationTests` | PASS: 12 |
| `TenantAuthorizationTests` | PASS: 8 |
| `OrganizationPersistenceTests` | PASS: 9 |
| `AronexaApplicationTests` | PASS: 4 |
| `ArchitectureTests` | PASS: 1 |
| Flyway V1–V6 fresh migrations | PASS: schema version V6 |
| `npm ci --no-audit --no-fund && npm run build` | PASS |

## Historical red runs corrected
1. Initial CI failed when defining an auth datasource suppressed Spring Boot's implicit primary datasource. Fixed using explicit primary Testcontainers JDBC connection details in the `test` profile.
2. The existing RLS test expected `aronexa_app_runtime` to call the login-tenant function. After V6 that function correctly belongs only to the auth role. Updated test.
3. An old assertion expected the auth role to SELECT the tenant table. Correct requirement is that the SECURITY DEFINER lookup works but direct tenant table SELECT is DENIED. Updated test. The latest full run passed.

## Security risk accepted only for **foundation review**, not production
- **Global authentication tables are still readable or writable by the dedicated authentication role**, across tenants where SQL grants permit it. Split roles significantly reduce the organization connection's attack surface, but **do not fully isolate auth accounts/sessions per tenant or prevent SQL injection inside auth code**. A more restricted function-only or separate identity-service boundary, evaluated with independent security review, is mandatory before real patient data or production use.
- The old dev database volume will not re-execute container bootstrap scripts. Do not delete data volumes. Provision the new role through a controlled, approved, non-destructive process before applying V6.
- Dev profile startup with three real passwords and existing developer-volume V5→V6 migration has NOT been manually smoke-tested, only Compose syntax and ephemeral CI database tests; production backup/restore and customer migration not tested.
- Dedicated clinician, patient, encounter, doctor-to-branch access policies cannot be tested because those domain models and APIs do not yet exist; Phase T05-C clinical sub-scope remains outstanding.
- Distributed rate limiting, MFA, verified recovery, session hardening under token theft, emergency/support access, compliance/privacy sign-off, and additional concurrent HTTP authorization attacks remain required before production.

## Approval gate
This PR is a targeted **security improvement**, not a claim that P01-T05-D fulfills all original end-to-end privacy/security acceptance criteria. Owner must inspect changes and green CI; obtain security reviewer acceptance of residual privilege risks before merge. No auto-merge, deployment or advancement to next phase. Any changes to V6 after an actual application require a new forward-only migration, not editing an applied version.
