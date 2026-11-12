# R1-05 — Persistent appointment management and daily Reception queue

**Status: isolated candidate implementation; exact-commit local acceptance pending.**
**Release: NO-GO for customer data, clinical booking, production SQL or installer.**

## Parent baseline and governance
- Depends on Development PR #111 exact locally accepted R1-04 SHA
  e7f849739bcc0727f6813f5af2fa87f5f85b4a1b.
- R1-04 owner-local results: 260/260 backend tests; 4/4 focused
  real HTTP+disposable PostgreSQL tests; 3/3 QA Central browser tests.
- This is a stacked draft, not merged to integration/main.
- Production Flyway stays V1–V9; V10 registration review-only is not
  approved, and no production appointment migration exists.
- Appointment SQL from T14/R1-04 remains disposable Testcontainers only.
  R1-05 adds test resources db/test/appointment-candidate-r105.sql
  with queue index and separate permission definitions; no live grants.

## Smallest accepted-after-testing slice
1. Reception's daily active appointment queue: GET
   /api/v1/branches/{branchId}/appointments?visitDate=YYYY-MM-DD,
   with live SQL session, tenant, branch, APPOINTMENT_READ and OPD module
   checks. Returns only BOOKED and CHECKED_IN (not CANCELLED), ordered
   by slot and ID and capped at 100 with a hard conflict if more rows.
   The same SQL transaction appends an access audit for every row shown.
   Queue contains only technical identifiers, slot, date and status;
   no demographics, contacts or clinical notes.
2. POST /api/v1/branches/{branchId}/appointments/{id}/reschedule:
   APPOINTMENT_RESCHEDULE, active BOOKED, exact revision, approved
   assigned doctor at branch and date/15-minute slot validation.
   A row-level guarded update and SQL unique index prevent races and
   double booking; old slot is released and append-only RESCHEDULED
   event records the revision. Patient, registration and branch cannot
   change.
3. POST /api/v1/branches/{branchId}/appointments/{id}/cancel:
   APPOINTMENT_CANCEL, active BOOKED and expected revision. Records
   CANCELLED and preserves audit and UUID lookup. Releases slot for a
   replacement booking. A second cancellation or check-in is rejected.
4. Connected Reception test preview: daily queue, click-through to
   fresh authorized appointment lookup, reschedule form, and an explicit
   two-click cancellation confirmation. Denial clears stale UI state
   and requires re-reading the live queue.
5. The existing R1-04 booking/check-in/registration recovery paths are
   unchanged and must retain full regression acceptance.

## Security and usability controls
- Endpoints exist ONLY in a nested controller under src/test/java,
  active only with 'test & appointment-candidate & !dev & !prod';
  AuthSecurityConfiguration remains default-deny in dev/prod.
- Every candidate operation resolves authenticated staff principal,
  requires an explicit permission and independently re-checks
  session revocation, active account/membership, branch scope,
  role permission and OPD module entitlement within its SQL transaction.
- Revoked permission/disabled module, foreign branch/tenant,
  expired/revoked sessions, stale revisions, occupied slots and
  checked-in/cancelled transitions all fail closed.
- SQL audit and immutable original patient/branch identity remain
  enforced by the existing T14 triggers.
- Client uses same-origin, no-store requests and memory-only state.
  No browser token or patient persistence and no demo identity fallback.
- Staff/doctor provisioning, a friendly doctor selector, desktop
  installation and offline recovery are NOT part of this candidate.
- IMPORTANT: candidate controller still uses disposable Testcontainers
  privileged migrator JDBC; it is not a reviewed production writer.
  Production requires dedicated least-privileged SQL writer, session
  binding and RLS tests, independently assessed forward-only migration,
  V10 registration security gate and Windows encrypted restore proof.

## Acceptance: do not report green before execution
- Existing Java AppointmentCandidateHttpAcceptanceTests receives four
  additional real HTTP/PG17 tests for reschedule/old-slot release,
  immutable cancellation/queue exclusion, conflict/stale-revision
  behavior, wrong branch/tenant, live permission and module revocation.
- New frontend appointment-management-candidate.test.mjs has eight
  focused tests. Existing seven R1-04 appointment client tests and
  other frontend model tests must also pass.
- QA Central independent opt-in browser tests use mocked HTTP responses
  only; do NOT treat browser green as PostgreSQL persistence evidence.

Windows local exact-SHA candidate acceptance, separate worktree:

    powershell.exe -NoProfile -File .\scripts\Test-Opd-R1-05-Appointment-Increment.ps1 -ExpectedCommit <CURRENT_40_CHARACTER_SHA>

The script reuses previously accepted R1-04 candidate+full regression
script, now executing all R1-05 Java tests from the same fixture. It
writes fresh evidence under %USERPROFILE%\AroNexa-Local-QA.

Browser QA (separate QA Central R1-05 exact-commit worktree):

    npm.cmd ci
    $env:ARONEXA_CONNECTED_BASE_URL="http://127.0.0.1:5176/connected-reception-preview.html"
    npx.cmd playwright test -c playwright.r1-05.config.mjs --project chromium-desktop --reporter=list

Start R1-05 frontend with npm.cmd run dev:connected:test in a separate
terminal before Chromium QA. No customer URL or patient data.

## Remaining release blockers
- Independent security review and restricted appointment SQL writer,
  approved production forward-only registration/appointment migration,
  branch/doctor onboarding, Windows offline encrypted backup/restore,
  accessibility and recovery acceptance.
- Do not merge, retarget, enable a production endpoint or deploy solely
  on the strength of test-only results.
