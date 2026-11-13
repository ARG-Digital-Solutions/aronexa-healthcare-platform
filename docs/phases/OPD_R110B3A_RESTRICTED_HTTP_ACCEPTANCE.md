# R1-10B3A - Real HTTP to Restricted PostgreSQL 17 Candidate

**Status:** DRAFT source implemented; exact-SHA Windows acceptance NOT RUN.
**Classification:** TEST-ONLY INTEGRATION / NO CUSTOMER ACTIVATION / Windows pilot NO-GO.
**Parent:** R1-10B2 Development PR #120, exact owner-accepted SHA
d47d24b5e6867b8bfcb8a89f3e5bb55921188f0b. Owner reported 312
backend tests, zero failures/errors/skips, disposable PostgreSQL 17
and Windows NO_GO gate (9 release blockers).

## Reason and source change

R1-04..R1-08 HTTP acceptance used temporary MIGRATOR SQL for
booking. R1-10B2 proved a separately restricted Java+SQL command
without HTTP. B3A joins them in disposable Testcontainers and
DOES NOT activate a customer booking endpoint.

1. AuthSecurityConfiguration adds POST matchers for distinct
   /api/v1/branches/{branchId}/opd-restricted/appointments and
   /api/v1/branches/{branchId}/opd-restricted/appointments/{appointmentId}/check-in.
   Access requires authenticated AuthPrincipal plus profile expression
   test & opd-restricted-candidate & !dev & !prod. Existing legacy
   appointment candidate guards and catch-all deny-all are unchanged.
2. OpdRestrictedBookingPostgresTests runs the application on a
   loopback-only RANDOM_PORT with the dedicated two-test-profile setup.
   It explicitly imports and checks a real nested test-only POST
   RestController in backend/src/test; there is NO src/main controller.
3. The disposable PG17 fixture installs reviewed V11 and R110B2
   schema/functions OUTSIDE production Flyway, creates random ephemeral
   proof key and SQL password, then uses the ACTUAL Java
   OpdRestrictedSqlAdapter, rejecting privileged/owner SQL connections.
4. Fictional staff log in through real /api/v1/auth/login using a
   BCrypt hash, then /api/v1/auth/me verifies a DB-backed token.
   The real OpdRegistrationHandoffService resolves the registered
   patient with PATIENT_REGISTRATION_READ, active OPD and registration
   module and dedicated restricted patient SQL role. No browser patient
   ID can authorize a booking.
5. Three new real HTTP test cases cover:
   - Book then check in, logout and fresh login, replay exact request
     to recover SAME patient/registration/appointment/revision with
     no duplicate audit and no access/session/token data disclosed.
   - Anonymous bearer, forged registration, wrong branch and revoked
     bearer denied without any appointment write.
   - Revoked APPOINTMENT_BOOK role permission denied; stale check-in
     revision returns generic conflict without changes or extra audit.
6. All inherited restricted-role PG17 tests continue to exercise
   signed HMAC, nonce uniqueness, role grants, direct SQL privilege
   denial, permission/module/branch/doctor revocation and checked
   idempotency. No privileged fallback accepted.

## Critically NOT established

- NO real production OPD route: controller lives ONLY under
  backend/src/test/java and isolated test profile; main Java command
  classes and SQL adapter are not Spring beans.
- NO approved forward-only V10/V11 production migration, customer SQL
  grants, runtime credentials or signing-key custody. Original Windows
  V8->V9 migration hold #31 and independent registration review #45
  are unchanged.
- Fresh login and separate committed SQL connection are NOT a full
  Spring process restart, Windows service restart or encrypted restore.
- Row lock on auth_session alone does NOT linearize concurrent
  membership, role, doctor, branch, or module revocation. Independent
  SECURITY DEFINER / RLS / race / audit / signing key review is needed
  before any real clinic activation.
- No clinician-facing booking, clinical prescription, statutory
  billing, customer install, real patient data or release GO.
- R1-09 Windows pilot release gate MUST continue to show NO_GO.

## Exact-SHA Windows acceptance — NOT YET RUN

Use a clean detached worktree and Docker Desktop with no Vite process
holding frontend files in the checkout:

    powershell.exe -NoProfile -File .\scripts\Test-Opd-R1-10B3A-Restricted-Http.ps1 -ExpectedCommit EXACT_HEAD_SHA

The runner fails on wrong/dirty SHA, missing strict test-only
authorization, test adapter/source placement or changed production V9.
It delegates to full inherited R1-10B2/R1-10B1/R1-08 HTTP, Maven,
Node, frontend, Doctor/Billing and disposable PG17 regression plus
R1-09 hard NO_GO self-test. Actual PASS counts are pending; do not
reuse the previous 312 as evidence for this new SHA.

## Next R1-10B3B gate

1. Independently review SQL role/RLS/SECUITY DEFINER implementation,
   HMAC issuer/key custody, session and parallel membership/module/
   doctor/branch revocation race ordering, slot idempotency.
2. Approve safe post-V9 production V10/V11 migration and restricted
   SQL role setup only after original Windows HOLD is resolved.
3. Add real authenticated Spring production endpoints, disabled until
   the security and per-clinic authorization gates pass.
4. Run full end-to-end fictional OPD booking/check-in across actual
   Spring STOP/RESTART and fresh login, with independent negative RLS
   and permission-revocation tests, then Reception UX acceptance.
5. Windows installer, license, encrypted real backup + second-machine
   restore, clinical/UAT reviews and explicit owner GO remain separate.

**Release decision: NO_GO for real clinical or financial use.**
