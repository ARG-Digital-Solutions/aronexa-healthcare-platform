# R1-10B3B - Session-first Authorization, Revocation Races and Spring Rebuild

**State:** DRAFT source, local Windows acceptance NOT RUN.
**Parent:** R1-10B3A Development PR #121, accepted owner-reported SHA
8c9806e6fc332568b64aba01c0e02660471ca953, 315 backend tests
with 0 failures/errors/skips, test-only real HTTP to restricted PG17.
**Classification:** No production activation; Windows pilot release NO-GO.

## Security gap found

R1-10B2's restricted SQL function read staff membership, role,
permission, branch and OPD flags before acquiring its row-share lock
on auth_session. An overlapping administrative transaction can hold
a conflicting session lock and commit an authorization revocation
before the booking finishes. A stale pre-lock authorization read must
NOT allow the booking to proceed.

PostgreSQL 17 SELECT FOR SHARE requires UPDATE privilege on at least
one column. This candidate reuses the existing, column-limited
UPDATE(revoked_at) privilege on auth_session for NOLOGIN function
owner. It does NOT grant new UPDATE rights on clinical or
administrative tables.

## Implemented source-only hardening

- deploy/opd/review-only/R110B3B__ordered_session_gate_candidate.sql
  replaces require_live_staff to acquire current auth_session FOR
  SHARE BEFORE checking live membership, user, roles, permissions,
  branch and OPD entitlement. It retains existing function owner
  and grants. This is a review-only overlay NEVER put in Flyway.
- The existing OpdRestrictedBookingPostgresTests now runs that overlay
  only after staging R1-10B1 V11 and R1-10B2 restricted-writer SQL,
  all inside a fresh automatically disposable PG17 Testcontainer.
- Four new true concurrent PG17 tests: an owner-controlled admin
  transaction locks a fictional session row FOR UPDATE, queues a
  real restricted-role signed booking on another JDBC connection
  (verified via pg_blocking_pids), then revokes session, role BOOK
  permission, branch scope or OPD module before committing. The
  queued booking must fail and write NO appointment.
- Additional SQL effective-grant audit checks function owner/security
  definer, NOLOGIN/NOBYPASSRLS ownership, direct runtime denial to
  appointment/nonce/key/clinical/audit tables and helper EXECUTE,
  while the true HTTP/SQL integration tests remain inherited.
- Spring context restart rehearsal: one NEW loopback Spring Boot
  application context is launched with strictly disposable PG17
  connection details, a fictional appointment is booked over real
  bearer HTTP, the entire Spring context/web server/JDBC pools are
  closed, a SECOND NEW context and fresh bearer login reopen the
  same exact committed appointment and check it in. JDBC details
  are a separate test-only @TestConfiguration class with a strict
  disposable database name/login guard.
- The earlier R1-10B3A @SpringBootTest context remains a separate
  independent test harness; this rehearsal explicitly closes and
  rebuilds two extra full Spring application contexts.

## What this does NOT prove

1. **NOT full concurrent revocation linearization:** the new tests
   establish safe ordering when a coordinating administrative
   transaction acquires a conflicting session lock FIRST. An admin
   write that bypasses this protocol can still race with the later
   permission, module, doctor or branch reads. Real production
   implementation requires a controlled administration workflow,
   a reviewed global lock hierarchy or additional authorization-row
   locks, and negative stress for all concurrent changes.
2. **NOT a separate JVM/Windows service restart:** two genuine Spring
   application contexts and HTTP listeners are started/stopped in the
   same JUnit JVM against disposable PG17. A fresh process, app
   installer, PostgreSQL Windows service and second-PC encrypted
   restore are NOT exercised here.
3. No approved production V10/V11 Flyway migration, existing
   Windows V8->V9 HOLD #31 resolution, independent registration
   security review #45 or clinical authorization.
4. No production patient/appointment POST endpoint or production
   application secret, JDBC login, signing-key provision/rotation,
   clinician UI, clinical prescription, payment or customer data.
5. Tests are authored but NOT YET EXECUTED on the new exact HEAD.
   Do not copy R1-10B3A's 315 tests result to this SHA.
6. SECURITY DEFINER semantics, ALL ROLE GRANTS, transaction isolation,
   deadlocks, clock changes and HMAC operational key custody still
   require independent security review before any customer access.

## Windows exact-SHA acceptance (PENDING)

Run ONLY from a NEW clean detached exact-SHA worktree with Docker
Desktop and no Vite/Node server holding files in the checkout:

    powershell.exe -NoProfile -File .\scripts\Test-Opd-R1-10B3B-Revocation-Restart.ps1 -ExpectedCommit EXACT_NEW_HEAD_SHA

The runner guards exact SHA, clean tracked source, Flyway max V9,
test-only SQL location and isolated restart details, then calls the
full inherited R1-10B3A -> B2 -> B1 -> A -> R1-08 Maven/real HTTP,
disposable PG17 and frontend/Doctor/Billing suite, and the R1-09
hard NO_GO Windows release self-test.

Any failing test MUST be treated as evidence, fixed in a new commit
and retested. Never suppress errors, relax role/RLS policies, or
move SQL into production to obtain a green build.

## Next delivery gate - R1-10B3C

- Complete independent database permission/module/branch/doctor
  concurrent-revocation review and enforce the same lock protocol in
  admin/provisioning operations, including expiry while waiting.
- Verify explicit production SQL ownership, non-BYPASSRLS roles,
  no direct runtime DML, bounded proof-key custody and backups.
- Independently approve forward-only post-V9 V10/V11 migrations on
  disposable PG17 ONLY, preserving held original Windows DB.
- After approvals, implement real Spring production runtime booking
  under per-clinic authorization, true process restart recovery and
  receptionist workflow driven by that endpoint.
- Complete installer, signed licensing, encrypted nonempty database
  backup/restore, security, usability and customer owner release GO.

**Disposition:** INTERNAL TEST CANDIDATE. All nine release blockers
remain; R1-09 Windows pilot NO_GO is non-negotiable.
