# R1-07 — Doctor-controlled structured prescription draft

**Status:** Source implementation committed; R1-07 exact-SHA acceptance NOT RUN.
**Classification:** SYNTHETIC TEST-ONLY, NOT A VALID PRESCRIPTION.
**Release decision:** NO-GO for real patient/clinical use or production deployment.

## Baseline and design reuse
- Stacked on Development R1-06 PR #113, exact owner-accepted commit
  b2d15bbbd453026872bcc6fdc948eadaee23db3e.
- R1-06 owner-reported baseline: 268/268 Maven tests and focused
  real HTTP + disposable PostgreSQL pass; dedicated doctor Vite build PASS;
  QA Central PR #34 Chromium 5/5 PASS.
- Reuses the existing Phase 02 Smart Rx synthetic MedicineEntry/normalizeMedicine
  field limits (COMM_V1_T03_SMART_RX_SYNTHETIC.md) instead of inventing
  a second set of unrelated medicine entry rules.
- Patient/doctor/tenant/branch context is obtained from R1-06
  authenticated consultation-context and consultation-draft reads.
- No independent patient registration, clinician directory, or patient
  reference supplied in the prescription POST body.

## Implementation in the isolated candidate
1. New backend/src/test/resources/db/test/appointment-candidate-r107.sql
   installs Rx revision headers, ordered structured medicine lines and read
   audits ONLY in disposable PostgreSQL 17 Testcontainers. It does not
   modify any Flyway V1–V9 migration or a customer DB.
2. A prescription revision records the source consultation revision and
   references the exact appointment/tenant/branch/patient/doctor and immutable
   consultation revision using composite foreign keys. Each save appends a
   full new snapshot (up to 30 medicine lines). Existing snapshots and their
   medicine lines are immutable; there is NO UPDATE/DELETE API.
3. Candidate-only GET/POST path:
   /api/v1/branches/{branchId}/appointments/{appointmentId}/prescription-draft.
   Both operations require OPD_RX_DRAFT_WRITE plus live staff session,
   enabled user/membership, authorized branch scope, DOCTOR role, current
   OPD entitlement, the exact assigned doctor, checked-in appointment and
   a saved consultation draft. POST requires expectedRxRevision and
   consultationRevision, validates the structured medicine fields and
   atomically appends a new snapshot under an appointment row lock.
   NOTE: field actually named expectedRevision in the request payload.
4. GET returns EMPTY_DRAFT/revision 0 before the first save; later returns
   RX_DRAFT/revision 1+. A consultation update after any Rx draft causes
   a conflict; no medication is automatically carried to a changed
   consultation. A future reviewed reconciliation/amendment workflow is
   required before clinical use.
5. Client reuses independent R1-06 context/consultation identity verification,
   then verifies exact patient and consultation revision in every Rx response.
   Entire structured medicine snapshot is revalidated server-side. On 409,
   401, 403, network uncertainty, forged response or failed comparison, the
   client discards its active Rx and requires a fresh doctor-authorized read.
6. Doctor UI: from saved consultation choose "Open prescription draft".
   Manually add structured fictional medicine name, strength, form, dose,
   route, frequency, duration, quantity (1–10000), instructions, remove
   draft lines, and explicitly save an immutable snapshot. Mobile-friendly
   fields and keyboard labels. No automatic medicine recommendations.
   Every workspace visibly says DEMO ONLY — INVALID AS PRESCRIPTION.
7. Existing Reception, normal frontend, R1-06 consultation save and
   doctor sign-in remain separate. No browser storage, cookies, cached
   clinical data, hidden fallback or real patient records.

## Safety scope and limitations
- No automated dosing, allergy/interaction checks, drug formulary,
  contraindication detection, controlled-substance checks, clinician
  signature, finalized version, PDF, patient sharing, pharmacy connection,
  printing, ink persistence or actual prescription issuance.
- The older standalone smart-rx-preview.html is still a disconnected
  synthetic teaching/demo screen, NOT a clinical order editor.
- Only fictional text; never enter real patient medicine orders or PHI.
- Exact role/session/permission + DB constraints are exercised in tests,
  but test adapter still uses a privileged migrator connection ONLY in
  disposable PG17. This cannot be deployed for clinical use.

## Exact-SHA acceptance — PENDING
- Existing R1-04/R1-05/R1-06 HTTP+disposable PG17 class extended with
  FOUR new Rx integration methods: immutable versioning and fresh login;
  saved consult/checked-in and doctor-only permission gates; invalid lines,
  stale Rx and changed consultation; live Rx permission/module revocation.
- NINE new frontend client tests for independent context, forged patient,
  invalid medicine, full snapshot and fail-closed stale updates.
- Full frontend npm ci/build/unit, Reception preview build, candidate
  Node HTTP wire, full Maven verification, doctor preview Vite build.
- Independent QA Central R1-07 seven-case Chromium suite uses MOCKED API
  responses and MUST NOT be treated as PostgreSQL persistence evidence.

Windows from clean exact-commit detached R1-07 worktree with Docker available:

    powershell.exe -NoProfile -File .\scripts\Test-Opd-R1-07-Prescription-Increment.ps1 -ExpectedCommit EXACT_R107_SHA

Separate independent browser QA:
Start R1-07 doctor preview (port 5177):

    npm.cmd run dev:connected:doctor:test

Then QA Central R1-07 exact SHA:

    npm.cmd ci
    $env:ARONEXA_DOCTOR_BASE_URL="http://127.0.0.1:5177/connected-doctor-preview.html"
    npx.cmd playwright test -c playwright.r1-07.config.mjs --project chromium-desktop --reporter=list

All passes must be tied to immutable commit SHA and local evidence. Do not
infer R1-07 PASS from the R1-06 results or mocked browser alone.

## Remaining production and clinical gates
- Independently reviewed least-privilege clinical SQL writer and RLS/session
  isolation; approved forward-only registration, appointment, encounter and
  prescription migrations; real doctor/branch onboarding and privacy/consent.
- Medication safety, allergy, interaction, prescription format, signing and
  amendment policies require qualified clinician and legal/regulatory review.
- Windows encrypted backups and tested restore, offline recovery,
  accessibility and full pilot UAT remain outstanding.
- Keep Development/QA PRs DRAFT and UNMERGED. No production clinical
  controller, route, module entitlement or feature activation is authorized.
