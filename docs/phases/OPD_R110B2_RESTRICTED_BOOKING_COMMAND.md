# R1-10B2 - Restricted OPD Booking and Check-in Command Candidate

Classification: INTERNAL_FOUNDATION / DISPOSABLE TEST ONLY / PILOT NO-GO.
Status: source implemented; exact-SHA Windows/JUnit/Testcontainers acceptance NOT RUN.
Base: R1-10B1 Development PR #119, owner-accepted source SHA
d4d52a90a5f09c40735c0f0be1b008307d251309
(294 backend tests, zero failures/errors/skips, existing real HTTP PG17,
frontend and R1-09 hard NO_GO gate accepted). Preserve historical SHA
evidence: it does not automatically certify this new R1-10B2 head.

## Concrete implementation

- OpdBookingCommand (src/main/java): internally constructed, UNREGISTERED
  business command. Reads verified bearer staff identity and permissions,
  requires OPD module, resolves patient registration through real source
  OpdRegistrationHandoffService, validates slots/revisions and exact
  tenant/branch/patient/doctor/registration identities. A registered
  patient, never a client-supplied patient ID, is used for booking.
- OpdCommandProof (src/main/java): exact canonical HMAC-SHA256
  request attestation. Binding includes tenant, actor membership,
  current session ID, branch, patient, registration, doctor, day/slot,
  booking request and generated appointment ID; check-in binds exact
  appointment and expected revision. Nonce and 30-second window limit
  replay; invalid fields fail closed. No default key, credential file or
  externally reachable issuer exists.
- OpdRestrictedSqlAdapter (src/main/java): UNREGISTERED JDBC port
  implementation. Constructor verifies the SQL connection is the
  dedicated aronexa_opd_runtime LOGIN, not superuser or BYPASSRLS and
  WITHOUT direct SELECT/INSERT/UPDATE/DELETE appointment privileges.
  Server-side verified staff+registration handoff is required. It signs
  an exact command and calls guarded SQL as ONE atomic statement, maps
  only server-returned IDs/status, redacts database exception details.
  No application Spring bean or HTTP/production controller uses it yet.
- deploy/opd/review-only/R110B2__restricted_booking_writer_candidate.sql:
  staged OUTSIDE active Flyway. Uses NOLOGIN, NOINHERIT, NOBYPASSRLS
  function owner; separate runtime role that is NOLOGIN by default;
  a test-only fixture may temporarily enable LOGIN with a random
  disposable password. No reused password or real signing key is in
  SQL/source. FORCE RLS policies, scoped privileges, a private proof-key
  table, unique nonce consumption, SQL-signed appointment booking and
  optimistic check-in SECURITY DEFINER functions. Both re-check live
  auth_session, membership, roles, branch, doctor and OPD entitlement.
  All appointment writes use the NOLOGIN function-owner's restricted
  permissions; runtime has no direct appointment-table privileges.
  Database constraints provide exact linked identities, unique slots,
  per-tenant idempotency, revision checks and immutable audit.
  A check-in request table supports exact request replay without
  duplicating an event.

## Automated new test coverage (AUTHORED; execution pending)

Seven OpdBookingCommandTests validate server-derived patient scope,
session and module admission, slot validation, stale revision,
forged/missing SQL receipts, and rejection of cross-branch results.

Three OpdCommandProofTests assert session/revision/appointment HMAC
binding and absence/overflow rejection.

Eight OpdRestrictedBookingPostgresTests use a NEW, automatically
destroyed PostgreSQL 17 Testcontainer, run the reviewed V11 schema and
new R110B2 SQL, generate a unique random HMAC key and disposable SQL
role password, then exercise:
1. Truly restricted JDBC account creates and checks in a fictional
   patient appointment, exact SQL audit is persisted across connections.
2. The actual Java source adapter calls both restricted SQL functions.
3. Direct table SELECT/INSERT and private-key access are denied to
   runtime, including with forged PostgreSQL GUC.
4. Forged HMAC, mismatched patient/registration and nonce replay rejected.
5. Booking request idempotency and active doctor-slot uniqueness.
6. Session logout/revocation denies booking and check-in after commit.
7. Wrong SQL datasource and absent signing key reject adapter construction.
8. Disabled OPD module, removed staff branch scope and unassigned doctor
   are denied before appointment writes.

NOTE: The inherited R1-10B1 runtime appointment test fixtures still
use temporary migrator credentials. The NEW restricted writer tests
specifically use a separate dedicated SQL LOGIN, but this is still
disposable synthetic Testcontainers only.

## Crucial NOT DONE / NO-GO criteria

- No approved patient-registration production writer (V10), real
  persistent booking production Flyway V11, Windows V8->V9 HOLD #31
  resolution, or independent registration security review #45.
- No actual Spring bean/configuration, authenticated production HTTP
  booking/check-in endpoint, two-PC clinical networking or UI wire-up.
- NO real patient booking, issued prescription, clinical record,
  financial transaction, clinic installer or Windows pilot is possible.
- The SQL function's session-row share lock is not sufficient proof of
  full linearizability against simultaneous membership/role/branch/
  doctor/module revocation. Independently review/adapt the complete
  DB transaction and lock ordering BEFORE live activation; run negative
  concurrency stress, SQL role grants and pooled connection isolation.
- Test-only HMAC key is ephemeral. Operational secret generation, signed
  provisioning, key custody/rotation, recovery, least-privilege installer
  process, offline clock/expiry policy and log redaction need validation.
- SQL SECURITY DEFINER ownership, all RLS policies, pgcrypto placement,
  function EXECUTE grants, nonce handling, idempotency and permitted
  authenticated read of patient/doctor records require independent
  security review. Do not grant execution to a real clinic role yet.
- No full start/stop Spring application or Windows installer restore
  proof. R1-09 pilot preflight should remain NO_GO with nine blockers.
- No explicit owner approval to merge/retarget/close PRs, change security
  rules or touch customer/original Windows databases.

## Run in a fresh clean Windows worktree (not your dev/test directories)

Requires JDK 21, Maven, Node, Docker Desktop available for local tests.
Do not run Vite from the acceptance checkout while npm ci is executing.

    powershell.exe -NoProfile -File .\scripts\Test-Opd-R1-10B2-Restricted-Booking.ps1 -ExpectedCommit EXACT_R110B2_SHA

The runner checks exact commit, clean tracked tree, active Flyway max V9,
source classes still unregistered, unchanged test-only HTTP route guards,
review-only SQL without default password, then runs complete inherited
R1-10B1/R1-10A/R1-08 frontend/Maven/real HTTP/disposable PG17 regression
and the R1-09 hard pilot NO_GO self-test. New tests run by Maven verify.
Record actual counts only after execution; failing tests must be fixed
at a NEW exact SHA before any acceptance claim.

## Next critical R1-10B3

Resolve outstanding security issues and run independent SQL review.
With explicitly approved forward-only production migrations and separate
installer provisioning controls, implement a real Spring runtime
booking/check-in bean + authenticated API using this tested restricted
adapter (not a test migrator), then receptionist browser E2E backed by
real data. Verify the same fictional visit after Spring restart and a
new login; test simultaneous slot races, session/permission/branch/
module revocation, wrong tenant/doctor, idempotency and stale revision.
Only then begin consultation/prescription/billing persistent integration.

Release decision: STILL NO_GO for any actual clinic or real patient data.
