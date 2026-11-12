# R1-06 — Assigned doctor consultation draft, save and secure recovery

**Status: implementation committed; exact-SHA Windows acceptance NOT YET RUN.**
**Clinical release disposition: NO-GO — synthetic/disposable TEST ONLY.**

## Dependency and scope
- Stacked on Development R1-05 PR #112, owner-accepted head
  799bca7a4ea1fad36b46d661eda88c7b27f46fea.
- R1-05 owner-reported: 264/264 Maven tests, full frontend + connected
  build/integration PASS, QA Central #33 Chromium 4/4 PASS.
- Existing immutable Flyway V1–V9 unchanged. V10 registration and real
  production appointment migration remain approval-gated.
- Reuses existing EncounterDraftContract to enforce CHECKED_IN ->
  CONSULTATION_DRAFT stage and revision rules.
- Smallest test slice: assigned doctor opens an existing CHECKED_IN visit,
  saves a fictional consultation note, signs out, signs in again, reopens
  the draft and saves revision two.
- Not in scope: demographics, vital signs, reports, Rx, billing, signatures,
  finalization, stylus drawing, actual Windows installer or clinical use.

## Durable HTTP + disposable PG17 proof
- New backend/src/test/resources/db/test/appointment-candidate-r106.sql
  augments T14/R1-04/R1-05 synthetic proof with immutable
  appointment_proof.consultation_revision and consultation_read_audit tables.
- Composite FK binds the exact appointment, tenant, branch, patient and
  assigned doctor. A saved revision is inserted, never updated or deleted.
  Prior fictional note revisions remain available. This is NOT a signed EMR
  or an approved clinical retention policy.
- GET consultation-context and GET/POST consultation-draft at /api/v1/branches/{branchId}/appointments/{appointmentId}/...
  exist only in the nested controller under src/test/java, active under
  test & appointment-candidate & !dev & !prod. Ordinary app/dev/prod deny all.
- Synthetic doctor is granted OPD_DRAFT_WRITE by fixture only. On every
  read/save, backend rechecks live authenticated bearer/session, user and
  membership, branch scope, module entitlement, permission, DOCTOR role and
  that the doctor is the checked-in appointment's assigned member.
- Initial consultation-context GET independently reads the checked-in appointment's
  canonical patient UUID (assigned doctor only). Client must compare subsequent
  consultation draft patient ID to this reference, including when a forged
  patient ID is another syntactically valid UUID. This is not proof against a
  fully compromised backend; transactional authorization remains mandatory.
- Initial draft GET returns CHECKED_IN/revision 0/blank note; after save, GET
  returns CONSULTATION_DRAFT/latest revision and fictional note, with
  separate append-only read access audit. POST performs revision comparison
  while holding the appointment row lock, then atomically inserts a new
  immutable revision. Stale revisions => 409.
- Note is 1–2,000 characters and must not appear in audit metadata.
  No clinical text in logs and no-store HTTP responses only.

## Isolated doctor UI
- frontend/connected-doctor-preview.html and
  frontend/src/connected-doctor/DoctorConsultationPreview.tsx, Vite port 5177.
- Doctor sign-in, checked-in UUID lookup, editable fictional note, new
  draft revision, signout and relogin; Reception never receives note editing.
- New doctorConsultationCandidateClient.ts makes two authorized no-store
  GET requests (appointment context first, then clinical draft), validates
  doctor membership and exact patient/appointment/tenant/branch references,
  uses memory-only no-store
  requests and discards stale draft after uncertain save or signout.
- No localStorage, cookies, patient notes persisted to browser or fake
  fallback. Never enter real patient information.

## Exact-SHA acceptance (not yet run)
1. Real HTTP+PostgreSQL 17 candidate class adds 4 doctor-only JUnit methods:
   durable revisions after fresh login; stale/invalid notes; denied booked,
   cancelled, foreign tenant, wrong branch and reception; live permission/
   module revocation. All existing appointment tests preserved.
2. Full R1-05 candidate+full regression script: frontend npm/build/unit,
   connected Reception preview, registration Node HTTP wire, backend Maven.
3. New doctor preview build: npm.cmd run build:connected:doctor:test.
4. New 8-case doctor client Node test suite, including a valid wrong-patient
   UUID rejected against independently checked-in context, included in npm test.
5. Independent QA Central opt-in R1-06 Chromium 5-case MOCKED API suite.
   Mocked UI is not evidence of actual PostgreSQL persistence.

Full owner-local acceptance in isolated exact-commit R1-06 worktree:

    powershell.exe -NoProfile -File .\scripts\Test-Opd-R1-06-Consultation-Increment.ps1 -ExpectedCommit EXACT_R106_SHA

Independent browser QA: first start R1-06 doctor frontend:

    npm.cmd run dev:connected:doctor:test

Then in separate QA Central R1-06 worktree:

    npm.cmd ci
    $env:ARONEXA_DOCTOR_BASE_URL="http://127.0.0.1:5177/connected-doctor-preview.html"
    npx.cmd playwright test -c playwright.r1-06.config.mjs --project chromium-desktop --reporter=list

Record exact SHA and new test counts; do not infer acceptance from R1-05.

## Commercial deployment blockers
- Existing adapter uses a privileged migrator DB connection only in an
  automatically destroyed Testcontainers database; production requires a
  separately reviewed restricted clinical writer, RLS/session validation,
  reviewed migration, full clinical encounter and retention/signing policies.
- Also requires approved V10 registration, real appointments, staff/doctor
  provisioning, consent/privacy controls, Windows encrypted backups/restores,
  independent accessibility/clinical QA and deployment authorization.
- Keep stacked PRs DRAFT and UNMERGED; no live module activation or PHI.
