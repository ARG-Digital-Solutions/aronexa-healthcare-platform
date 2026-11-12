# R1-04 — Registration-linked appointment booking and check-in

**Status: implemented in isolated branches, NOT YET ACCEPTED by local QA.**
**Release decision: NO-GO for production registration or appointment writes.**
**Windows assisted pilot remains the commercial target.**

## Verified baseline and reusable source

- Based on draft PR #110 head 1145c5f3b54081b7ca84d42306b26a2de3f68fd5.
- Owner-reported prior R1-03: frontend/build/real HTTP PostgreSQL and 256/0/0/0
  backend tests PASS, QA Central PR #31 Playwright browser 4/4 PASS.
- Main and integration branches are not merged through the dependent draft PR chain.
- Immutable production Flyway remains V1–V9. V10 registration writer candidate is
  outside main Flyway and is NOT approved for a real Windows/customer database.
- Reuses the T14 appointment-persistence proof:
  backend/src/test/resources/db/test/appointment-persistence-proof.sql
  and its existing appointment history, status/revision guard, doctor slot
  uniqueness, branch/role/entitlement checks, and append-only event audit.
- Reuses V8/V9 patient/MRN/registration identity, staff bearer authentication
  and SQL-backed sessions; no duplicate appointment demo model or live migration.

## Smallest real backend-connected slice (disposable PostgreSQL 17)

1. A receptionist with APPOINTMENT_BOOK, an assigned branch and valid
   SQL-backed bearer session identifies the existing server-issued patient
   registration and selects an assigned DOCTOR membership, a clinic-local date
   (today through 90 days ahead), and a 15-minute slot from 09:00–16:45.
2. POST /api/v1/branches/{branchId}/appointments stores a real database row
   atomically, linking exact tenant, branch, registration and active MRN patient.
   A request UUID enables same-actor same-payload retry; a conflicting request
   and an occupied doctor slot return 409 without a second appointment.
3. GET /api/v1/branches/{branchId}/appointments/{appointmentId} rechecks the
   current staff SQL session/role/branch/OPD entitlement in its own transaction,
   reads the actual saved row, and appends a non-demographic read audit.
4. POST /api/v1/branches/{branchId}/appointments/{appointmentId}/check-in
   requires APPOINTMENT_CHECK_IN, BOOKED revision and the exact clinic-local
   appointment date. Optimistic revision update and T14 audit trigger record
   CHECKED_IN; stale or repeated check-in conflicts.
5. The separately built connected Reception preview can recover a patient
   registration, open an appointment form, book, reopen from the server after
   a new login, and check in. No fake appointment is generated on denial.

The ordinary in-memory synthetic receptionist/appointment demo remains
unchanged. The preview requires a *disposable test backend* to perform
candidate appointment writes. No normal Spring controller or customer
installer exposes these routes.

## Security/isolation and migration design

- New SQL file is ONLY under backend/src/test/resources/db/test/
  appointment-candidate-r104.sql. It runs ONLY in the ephemeral R1-04
  Testcontainers PostgreSQL database after the pre-existing proof script.
- The candidate controller exists ONLY under backend/src/test/java and
  requires the active profile 'test & appointment-candidate & !dev & !prod'.
  AuthSecurityConfiguration retains default-deny for dev/prod/ordinary app.
- The candidate read/write transaction independently rechecks session UUID,
  revoked/expired bearer state, active account and membership, live role
  permission, branch membership and OPD entitlement. Doctor membership and
  exact V9 registration + active patient + MRN are rechecked.
- The database guards doctor-slot uniqueness, audit event sequencing, status
  transitions, immutable patient/branch appointment identity and FK linking to
  exact registration. No appointment data is written to ordinary runtime DB.
- API responses contain only UUID references and appointment stage/date/slot,
  never names, contact numbers, notes, token or a SQL exception.
- The client keeps one pending request UUID in memory on uncertain outcomes,
  uses same-origin no-store, verifies response scope, and discards patient
  context on logout and failed check-in.
- **IMPORTANT security gap for production:** the candidate controller uses
  the disposable Testcontainers privileged migrator JDBC pool to prove real
  HTTP/DB behavior. It is NOT a production-design approval: move the write
  operation to a restricted, reviewed SQL role with proven session binding,
  revocation ordering, RLS and independent security assessment before
  enabling production routes or Flyway migration.

## Automated checks (not yet run on R1-04 SHA)

- Frontend Node appointment-candidate-client tests: successful booking/get/
  check-in, stable request retry, conflicts, tenant spoof rejection, invalid
  inputs, permission checks, and logout invalidation.
- AppointmentCandidateHttpAcceptanceTests: real Spring Boot RANDOM_PORT +
  authenticated HTTP + disposable PG17; exactly one booking, logout + new
  session GET, read audit, check-in revision audit, idempotent replay,
  slot collision, invalid doctor/branch/registration/permission/date.
- Existing app unit, frontend builds, registration HTTP/Node wire,
  existing appointment-persistence proof and all other Maven regressions
  are kept by the combined full test command.
- QA Central opt-in R1-04 Chromium Playwright uses MOCKED API responses
  for the UI states (not database persistence evidence).

Run one-command acceptance in a clean R1-04 app worktree:

    powershell.exe -NoProfile -File .\scripts\Test-Opd-R1-04-Appointment-Increment.ps1 -ExpectedCommit EXACT_40_CHAR_R104_SHA

This focuses real appointment HTTP+PostgreSQL first and then invokes the
established R1-03 frontend + connected-preview + HTTP registration wire +
full clean Maven verification with fresh JUnit counts. Any nonzero exit is
a STOP; do not waive skips or promote review-only DDL.

Run independent browser tests from the separate QA R1-04 worktree, after
starting R1-04 frontend connected preview at port 5176:

    npm.cmd ci
    $env:ARONEXA_CONNECTED_BASE_URL = "http://127.0.0.1:5176/connected-reception-preview.html"
    npx.cmd playwright test -c playwright.r1-04.config.mjs --project chromium-desktop --reporter=list

The browser test uses only local mock responses. Do not point it at real
clinic services or interpret a browser green as proof of patient persistence.

## Release and usability gaps remaining

- Production V10 registration security/migration/role/proof-key and encrypted
  cross-machine Windows recovery are still approval-blocked.
- R1-04 appointment SQL requires an independent restricted-writer production
  design, migration rehearsal, user/doctor provisioning and security signoff.
- Real clinician directory, appointment search and reschedule/cancel require
  separate small follow-up increments; no fake demo data fallback.
- OPD encounter, prescription and billing APIs remain separately gated.
- Do not merge PRs, activate patient-related modules or deploy to a clinic
  on the strength of this candidate.
