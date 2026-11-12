# R1-03 — Authorized existing-registration recovery

**Status:** Isolated implementation; QA pending on this branch. No patient-facing
release, PR merge or migration deployment is authorized by this increment.

## Why this is the next smallest useful OPD slice

Reception already has a verified synthetic-only new-registration path in PR
#108. Instead of creating another registration table, controller or duplicate
service, this increment uses the existing production-code read endpoint:

GET /api/v1/branches/{branchId}/patient-registrations/{registrationId}

It allows staff to sign out, sign in again, enter a registration UUID and
retrieve its verified tenant/branch/patient UUID mapping from PostgreSQL. This
is the first durable reference handoff for downstream authorized appointments.
It is not a clinical chart, patient search by name, new registration write,
or a consultation permission.

## Reused contracts and migrations

- Existing immutable Flyway V8: patient, MRN, patient_registration, audit.
- Existing immutable Flyway V9: PATIENT_REGISTRATION_READ permission,
  branch/tenant RLS, module entitlement and append-only read audit.
- Existing PatientRegistrationReadController, PatientAccessService and
  registrationReferenceClient; no second API or SQL function is added.
- Existing staffSessionClient and login/logout flow.
- Existing RegistrationCandidateHttpAcceptanceTests and disposable V10 writer
  from PR #108 for synthetic creation before a real HTTP read-back.

**Database migration decision:** NO new migration. V9 already contains the
necessary auditable read schema and restricted privileges, and adding a
second or renamed V10 now would conflict with the separate unapproved V10
candidate in PR #108/#109. This increment MUST NOT move candidate V10 into
production Flyway or enable its POST controller.

## User workflow (isolated connected Reception QA preview)

1. Staff signs in with tenant code, username, password and a branch UUID.
2. /auth/me validates tenant membership, session, role and permission.
3. A receptionist with PATIENT_REGISTRATION_READ can choose "Find existing
   registration"; read-only staff also enter the recovery screen directly.
4. Enter the existing registration UUID, press Find registration.
5. The server re-checks live session, assignment, permission, module
   activation and patient SQL RLS; on a successful read it commits an
   append-only audit event.
6. UI shows the verified patient UUID, registration UUID and branch UUID.
   MRN, names, DOB, phone, notes and other clinical data are neither fetched
   nor displayed; access does not authorize consultation or appointment.
7. Sign-out immediately discards the in-memory result and invalidates any
   in-flight lookup. Browser refresh also clears the session.

The standard frontend/src/App.tsx is still in-memory demo only. The
connected preview is separately opted into via
npm.cmd run dev:connected:test. Production registration POST is still denied.

## New tests and what counts as acceptable evidence

- Node registration-recovery-flow.test.mjs: successful read, no sensitive
  fields, invalid IDs, no permission, 401/403/404, cross-tenant/branch or
  forged patient ID, concurrent lookup deduplication, and logout race.
- Existing Java RegistrationCandidateHttpAcceptanceTests integration now
  grants PATIENT_REGISTRATION_READ ONLY to its disposable fictional fixture,
  runs frontend Node through actual Spring Boot loopback, creates exactly
  one patient, logs out, signs in again and GETs the same PostgreSQL
  registration. Java independently checks registration read audit and both
  revoked SQL-backed sessions.
- Browser UI Playwright acceptance can be prepared in the independent
  AroNexa QA Central repo. Mocked browser endpoints verify UI interaction;
  they must NOT be confused with PostgreSQL persistence evidence.

Run on the exact current R1-03 branch SHA using the preexisting Windows
acceptance script:

    powershell.exe -NoProfile -File .\scripts\Test-Opd-Registration-Increment.ps1 -ExpectedCommit FULL_R1_03_COMMIT_SHA

It runs npm ci, frontend build and unit tests, connected preview build,
real HTTP + PostgreSQL integration and full Maven clean verify. Save local
SUMMARY.txt and report every failure (and skipped test), not just BUILD SUCCESS.

## Explicit remaining customer-pilot blockers

- Real POST migration V10 is still review-only; independent SQL security
  review, proof-key backup and role provisioning are required.
- Clinical privacy/consent, verified patient identity, duplicate-candidate
  review and usable patient search are not implemented for live records.
- Actual staff/branch provisioning and password-change UX remain to be
  productized; entering a raw UUID is a QA-only interaction.
- Persistent authorized appointment booking/check-in, consultation,
  prescription, billing and Windows encrypted recovery are still pending.
- GitHub Actions hosted CI and independent security review are separate
  external gates; this document does not grant them.

**This is a functional, backend-backed read path, but it is not yet a
customer-usable commercial workflow until the production registration and
operational controls are approved.**
