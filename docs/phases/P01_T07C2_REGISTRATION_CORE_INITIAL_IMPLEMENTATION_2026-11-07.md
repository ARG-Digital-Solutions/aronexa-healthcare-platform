# P01-T07-C2 — Protected registration backend: initial domain slice

**Date:** 2026-11-07 IST. **Owner authorization:** [issue #37](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/37).

## Current bounded implementation
This PR is **C2 part 1 of several**, not a working patient registration API.
- Immutable `PatientRegistrationRules` helpers for minimal patient intake validation, known/unknown birth-date rules, explainable possible-duplicate review flag, cryptographically random MRN and scoped HMAC idempotency digest plus separate payload fingerprint.
- JUnit 5 acceptance tests for allowed/rejected cases, MRN shape, actor/tenant/branch scoping and changed-payload fingerprint.
- No frontend integration, no patient HTTP controller, no SQL rights/migrations, no contact storage, no external patient request.
- No CareNexus reuse.

## Why the persistence portion is held as a separate reviewed step
Current V8 identity objects have FORCE RLS and V9 intentionally denies patient writes from all three runtime accounts. A new real registration flow must implement **transactional, least-privileged** writes with actor-attributed audit, duplicate review, race-safe idempotency and verified post-commit response semantics. A simplistic grant or SECURITY DEFINER function owned by an unrestricted migration user would create privilege-escalation and RLS risks. Current server-bound PostgreSQL transaction GUCs can be spoofed with stolen SQL credentials; this must be threat-modelled before exposing records.

Because the original Windows synthetic V8 database is subject to the documented W3-E **NO-GO/HOLD**, this task introduces **no discoverable Flyway V10 migration or automatic startup schema upgrade** at this stage. Original Windows V8->V9 W4 remains BLOCKED. A reviewed implementation and tests on a **fresh throwaway PostgreSQL** are the next C2 slice, not an implicit effect of merging this PR.

## C2 acceptance matrix — actual status must be attached
| Gate | Status from code alone | Requirement before C2 close |
|---|---|---|
| C2-A01 Fresh V1–V10 migration and validate | NOT RUN / NOT IMPLEMENTED | Isolated disposable DB with reviewed V10 |
| C2-A02 Runtime nonowner SQL table privileges | V9 preserved; new candidate NOT IMPLEMENTED | Tested direct role attacks on candidate |
| C2-A03 Atomic patient + MRN + branch registration + actor audit | NOT IMPLEMENTED | Transaction/recovery Testcontainers |
| C2-A04 Tenant, branch, permission, inactive membership and module denial | NOT IMPLEMENTED | Negative DB and API tests |
| C2-A05 Idempotency/replay conflict | POLICY CORE ONLY | Ledger + concurrent E2E tests |
| C2-A06 Duplicate manual review and concurrent controls | POLICY CORE ONLY | Secure candidate exposure and concurrency tests |
| C2-A07 Build and existing regression | CI PENDING | Link passing run + verify exact diff |
| C2-A08 Independent security review and owner approval | PENDING | No merge until explicit signoff |
| C2-A09 #31 original W3/W4 hold / #32 and #36 draft | PRESERVED | Verify immediately before changes |

## Safety
Release classification **INTERNAL_FOUNDATION / NOT_READY**. Real patient data, patient or guardian self-service, registration API, customer installation, backend startup on original V8, Flyway changes, deployment, manual merge and full Phase 01 closure are excluded.

## Development commands (safe, no DB startup)
```powershell
mvn -f backend/pom.xml -Dtest=PatientRegistrationRulesTests test
```
Project CI also runs the existing PostgreSQL Testcontainers regression suite against a newly disposable PostgreSQL container. Do not use the current original Windows instance. 

## C2 part 2 (2026-11-07) — disposable transactional candidate, pending CI and security review

This draft PR now also includes:
- `backend/src/test/resources/db/proposed/V10__c2_synthetic_staff_registration.sql`: a **TEST-resource-only** proposed V10 that default application Flyway **never scans**, and is applied explicitly by C2 integration tests to a disposable PostgreSQL 17 Testcontainer after V1–V9. The test bootstrap provisions a NOLOGIN `aronexa_registration_writer`; function ownership is transferred under transient schema-CREATE privilege, immediately revoked.
- The LOGIN patient role retains **NO direct patient-table INSERT/UPDATE/DELETE or identity SELECT grants**. Only EXECUTE on the scoped SECURITY DEFINER registration function is granted. The function uses the restricted NOLOGIN role and FORCE-RLS policies for insert/read, verifies the active membership, role permission, staff branch assignment and enabled/entitled/clinically authorized module in PostgreSQL, and creates patient + MRN + branch registration + actor audit atomically.
- New idempotency ledger binds HMAC-derived scope to payload fingerprint and output; repeated identical requests return the same result, changed payload is a conflict. Same name with compatible DOB in the same branch triggers manual review, **not merging or leaking candidates**. Name matching is deliberately basic and not identity assurance.
- `SyntheticRegistrationService` and `SyntheticRegistrationController` are restricted to explicit `c2-synthetic` Spring profile. Default `dev`/production profiles do not expose the endpoint. A 32+-byte, base64-encoded ephemeral HMAC secret is required, or profile startup fails.
- The exact POST security matcher is denied unless `c2-synthetic` is active and the bearer is authenticated; the service still independently requires `PATIENT_REGISTER`, verified branch/module and SQL grants.
- `SyntheticRegistrationIntegrationTests` runs only with `test,c2-synthetic`, uses fresh Testcontainers, installs proposed V10 explicitly, provisions synthetic fixtures and tests transaction/audit, replay conflict, duplicates, permission/branch/module denials and runtime SQL privilege restrictions.

**Remaining before accepting C2:** CI must demonstrate compiling, successful V10 disposable migration, tests and regression; independent database security review (SECURITY DEFINER owner/role/FORCE RLS, SQL GUC spoofing, tx/locking, MRN collision strategy), concurrency tests, HTTP 401/403/409 acceptance, rate limits, manual UI review and appropriate recovery. A passing CI run is **not** production release or permission to merge. The proposed V10 must not be relocated to default Flyway migrations absent separate written approval and controlled migration procedure.
