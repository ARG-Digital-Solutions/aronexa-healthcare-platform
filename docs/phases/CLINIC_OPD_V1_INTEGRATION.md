# Clinic Edition OPD V1 integration baseline

## Owner direction and scope
On 2026-11-11 the owner approved proceeding with a single-developer release process and an integrated OPD baseline. V1 comprises patient registration, appointments, doctor consultation, digital prescription, basic billing, daily reports, role-based access, and backup/restore. Windows offline is the first delivery target. IPD, pharmacy inventory, IVF/IUI, AI clinical features, native Android/macOS, and advanced analytics are deferred.

## Current increment
This branch consolidates existing reception/appointment UI, consultation, prescription and billing previews, registration backend tests, encounter contracts, and the real-HTTP denial fix. Source manifests below pin every imported increment. The runtime data paths still require persistent integration. Classification is INTERNAL_FOUNDATION / NOT_READY.

| Source PR | Immutable head |
|---|---|
| #64 | `7f1fb76ce80a9fb838df06fc616e14f169bc2dd4` |
| #61 | `52809efbc077cd6abb6860d870cc487cc8eddc42` |
| #75 | `72638f1aa78e6f43c1043526c910c3656e81ebce` |
| #78 | `8b935abfb23ef818e248c2104e8fff55be5d6b52` |
| #79 | `c95b50d261ef457e336abf22faab30b720202b28` |
| #87 | `fd811aa8d79e32dac4300f354ac51a29c53d90ad` |
| #101 | `97f2d6e45edce1945f06d5b32ebcdcc19219d5ab` |

Base main is `b4d9e1ea9b5c6fdc3d2c29b561eba7d919a40e6f`. #64 includes #38 registration and its contract tests. #61 includes #36 reception. #87 includes #85 encounter contracts. Existing default database migrations V1–V9 are preserved. The proposed registration V10 remains under test resources.

## Reconciliation
- Combine #64's test-profile registration route guard with #101's explicit 401/403 handlers. Preserve forced-password-change rejection and default route denial.
- Preserve main's Spring Boot 4.1.2 and Vite 7.3.7 dependency versions.
- The standard frontend build now executes every included model suite and compiles consultation, Smart Rx and billing standalone entrypoints in addition to reception/appointments.
- Preserve source-coupled registration SQL, authorization, concurrency, redaction and regression tests alongside the encounter tests.
- Archived source documents retain their historical decisions and test results. This document governs the new integration work's process.

## Single-developer delivery process
The owner is the engineering decision-maker. Codex implements, reviews, runs appropriate tests, fixes failures and maintains a concrete PR. Routine increments use owner acceptance plus AI technical review and automated evidence. A different GitHub account operated by the same person is recorded as owner review. Specialist review is targeted to unresolved security or clinical questions. Claims of independent human assessment require an actual human assessment.

Within this approved milestone, continue implementation and correction on the integration branch using the existing product CI. QA Central acceptance is recorded when the relevant plan has actually executed. Missing QA evidence remains pending and is not a reason to create unrelated preview increments. Protected workflow/settings changes and customer release decisions remain explicit actions.

## Execution plan
1. Consolidate the existing source and run the combined Maven/Testcontainers and frontend build. Resolve integration regressions on this branch.
2. Deliver persistent registration first. Reuse the existing patient identity schema and guarded writer design. Complete authenticated search/duplicate review, branch context, request idempotency, durable registration and a reception-to-API browser acceptance test. Review and implement production migration/key provisioning explicitly.
3. Add persistent appointment booking, cancellation and check-in with database-enforced slot conflict prevention and patient/branch/doctor validation.
4. Connect consultation to the authenticated encounter service. Enforce session revocation ordering, revision conflict handling and mandatory transactional audit. Prove persistence after application restart.
5. Connect structured prescription finalization/versioning and billing/report transactions to the same encounter, then complete Windows installation and real PostgreSQL recovery.

## Milestone acceptance
One receptionist registers a fictional patient, books and checks in an appointment, and a doctor saves a consultation under the same patient/encounter. Reload and application restart retain the records. Wrong tenant/branch/role, revoked session, duplicate retry, double booking and stale revision are rejected without partial writes. Acceptance requires actual API/database and browser evidence on the integrated commit.

## Verification and remaining work
Combined CI is pending at branch creation. Existing per-branch test passes are historical evidence. The integrated frontend is a set of existing previews until the API wiring steps above are complete. No persistent appointment API or live consultation route is claimed by this increment. The original Windows migration issue #31 applies to the original database, while development tests use disposable instances.

## Build and review
- Backend: `mvn -B -f backend/pom.xml verify`.
- Frontend: `cd frontend && npm ci && npm run build`.
- Reception/appointments: `npm run dev`, root URL.
- Existing previews: `/consultation-preview.html`, `/smart-rx-preview.html`, `/billing-preview.html`.
- Integration ownership remains one branch and one PR. Resolve differences there before retiring the source PRs.
