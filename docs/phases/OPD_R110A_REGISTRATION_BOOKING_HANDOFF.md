# R1-10A - Integrated OPD Runtime: Registration-to-Appointment Handoff

**Status:** Source committed, exact-SHA R1-10A Windows tests NOT RUN.
**Release classification:** INTERNAL_FOUNDATION / PILOT NO-GO.
**Owner priority:** Keep one simple, secure Windows OPD workflow; do not
promote R1-04 through R1-08 test candidate tables and controllers unchanged.

## Source ancestry and accepted evidence

Parent Development PR #116 at SHA
4a380c7094fbd82e81faea7c03fcb842cb1ae03f is a child of
R1-08 Development #115 accepted candidate SHA
965acbdbe0144e669942d5c30d8a5ce185129950.
The historical integration/clinic-opd-v1-20261111 SHA
249819d043503c20c3f2863894547fd98870db21 is a Git
ancestor of the parent branch (147 commits behind at inspection);
do not independently merge an obsolete integration branch or assume its
old acceptance counts apply to this new head.

Owner-reported earlier evidence:
- R1-08: 277 backend tests / 0 failures/errors/skips, real authenticated
  HTTP + disposable PostgreSQL 17, frontend Node/build, Reception/Doctor/
  Billing preview builds PASS; QA Central #36 mocked Chromium 10/10 PASS.
- R1-09: source-only Windows inspector self-test PASS on exact head,
  production Flyway V9 and 5/5 candidate-only PG17 schemas found;
  nine customer-release gates BLOCKED, releaseDecision NO_GO.
These are HISTORICAL evidence on earlier immutable SHAs; do not claim
that R1-10A has passed until its acceptance runs.

## First bounded production-source increment

New main-source service:
backend/src/main/java/com/argdigital/aronexa/opd/OpdRegistrationHandoffService.java

- Uses the real TenantAuthorization current verified server principal.
- Requires APPOINTMENT_BOOK (no platform-admin implicit override), a
  nonempty session ID, and canonical nonzero branch/registration UUIDs.
- Independently requires OPD module implemented/released and active for
  the authenticated tenant via ModuleAccessService.
- Calls PatientAccessService.findReadableRegistration, which requires
  PATIENT_REGISTRATION_READ, released + active PATIENT_REGISTRATION module,
  current authorized tenant/member/assigned branch, and SELECT through
  dedicated aronexa_patient_runtime PostgreSQL RLS under a transaction.
- Returns only an immutable internal exact tenant/branch/registration/
  patient/actor/session mapping. It NEVER takes a patientId from a browser,
  exposes demographics or provides an opaque authorization credential.
- Successful reads are written to the existing immutable registration
  access audit; invalid/wrong-tenant/branch/registration IDs are denied.
- On each new handoff, live branch and module restrictions are rechecked;
  no prior resolved mapping may be reused as permission for future writes.
- This is a READ-ONLY service and adds no appointment booking endpoint,
  checkout permission, production schema, role grants or CLI installer.

## Test-only real HTTP adapter

Added ONLY to nested RegistrationCandidateHttpAcceptanceTests:
GET /api/v1/branches/{branchId}/opd/registration-handoff/{registrationId}

- The adapter is in src/test/java and under the profile:
  test & registration-candidate & !dev & !prod.
- AuthSecurityConfiguration allows this GET only for that exact
  disposable candidate profile and a verified bearer principal.
- It returns tenant/branch/registration/patient IDs with Cache-Control:
  no-store; it deliberately omits actor membership, session UUID,
  patient names, age, contacts, notes, medications and financial data.
- A refused scope returns generic 403; missing or revoked bearer is 401.
- No matching application production endpoint or dev/prod security
  route is authorized. Real Windows clinic cannot call this route.

## Acceptance tests authored (NOT EXECUTED yet)

New tests in PatientAccessServiceIntegrationTests against automatically
discarded Testcontainers PostgreSQL 17 using restricted patient SQL role:
1. Exact registration -> patient mapping with immutable read audit.
2. Cross-tenant, cross-branch, forged ID, zero/null, absent booking
   permission, absent registration-read permission, forced password
   change and missing session all deny.
3. Membership branch revoke, OPD module disable and Patient Registration
   module disable deny without reusing earlier authorization.

New real-HTTP tests in RegistrationCandidateHttpAcceptanceTests:
4. Fictional registered patient is resolved by exact patient ID via
   database-backed bearer token and restricted patient SQL, denies
   wrong branch or forged registration; logout invalidates old session,
   new login reopens exact durable patient and audit is preserved.
5. Live removal of booking/read permission, branch scope, OPD module and
   Patient Registration module fails closed. Test fixtures grant these
   permissions ONLY within their discarded PG17 container.

Production module catalog remains unreleased by default. Tests enable
OPD and registration ONLY in disposable setup to observe real PostgreSQL
RLS and policy checks. These tests do not prove production appointment
writes, slot races, legal Rx, invoice issuance or one-PC installation.

## Run exact-head local acceptance

From CLEAN detached Windows R1-10A source checkout with Docker running
and no Vite dev server open in that same worktree:

    powershell.exe -NoProfile -File .\scripts\Test-Opd-R1-10A-Registration-Handoff.ps1 -ExpectedCommit EXACT_R110A_SHA

The runner chains the previously proven R1-08:
- existing R1-04 to R1-08 real HTTP/disposable PostgreSQL regression,
  npm ci, frontend model tests, Vite builds, full Maven clean verify;
- all 5 new R1-10A service/HTTP acceptance methods, because they are
  included in the full Maven verify;
- existing R1-09 hard NO-GO self-test at the SAME new source SHA.

Expected final success text only if every local step finishes:
R1-10A LOCAL CANDIDATE ACCEPTANCE PASSED (READ-ONLY, TEST DATA)
No count or PASS claimed until the owner supplies exact-head logs.

## R1-10B critical path (NOT implemented in this PR)

1. Review V9 scope, special original Windows V8->V9 migration HOLD #31
   and independent patient registration review #45. Do NOT execute
   forward migration on the original Windows database.
2. Design new forward-only production Flyway migrations only after
   approval: appointment/slot uniqueness, encounter/note immutable
   revisions, structured Rx draft and billing events with composite
   tenant/branch/patient/doctor FKs, module states and audit.
3. Provision a dedicated restricted appointment/clinical/billing writer
   separate from migrator/owner credentials. No BYPASSRLS, superuser,
   direct DDL, cross-tenant session sharing or unchecked stale sessions.
4. In one transaction, recheck bearer auth_session and live permission/
   branch/OPD flags, derive registration->patient mapping from DB,
   enforce slot conflict and idempotency. Do NOT authorize based solely
   on an earlier R1-10A VerifiedVisitSubject record.
5. Implement production booking/check-in and read API using server-
   created appointment IDs and explicit 401/403/404/409 outcomes.
6. Prove fictional Reception -> Doctor -> Billing through one durable
   database, application stop/start, fresh sessions and independent
   negative-test/role/tenant/branch/revocation browser QA. Treat
   final prescriptions and actual financial transactions as separate
   clinician/finance/security gates.

## Release controls

No protected-branch merge, QA workflow activation, customer DB migration,
installer, real cash, signed Rx, clinical record, PHI, payment settlement
or release GO authorized by this increment. Preserve R1-08 and R1-09
draft PRs. R1-09 nine hard pilot NO-GO gates remain binding.
