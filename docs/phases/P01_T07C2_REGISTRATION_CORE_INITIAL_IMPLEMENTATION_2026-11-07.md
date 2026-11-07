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
