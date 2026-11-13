# R1-10B1 - Consolidated Registration-to-Booking Handoff and PG17 Schema

**Status:** Source implemented; Windows full acceptance NOT RUN.
**Classification:** INTERNAL_FOUNDATION / pilot NO-GO.
**Parent:** Accepted R1-10A integration domain PR #118 SHA
60f7f9e52b31febb53bd104f30b5290cdcba7e79, stacked on
readiness PR #116 and the accepted R1-08 candidate #115.
**Parallel source reconciled:** Draft R1-10A handoff PR #117,
source SHA 2c77d1665f41dfb81b6959485fabfa6a25d28b64.
Do not merge, close or retarget PR #117 or #118 without owner approval.

## Implemented and integrated on one source branch

1. Kept all of PR #118's immutable OPD VisitContract, six validation
   tests, full regression and R1-09 hard pilot NO_GO source controls.
2. Integrated PR #117's real internal Spring OpdRegistrationHandoffService
   using the current authenticated bearer principal, APPOINTMENT_BOOK,
   live OPD entitlement and the existing restricted patient SQL role
   for precise registration->patient linkage and audited reads.
3. Brought over PR #117's two real-PG17 acceptance suites: service
   integration and test-profile-only HTTP, including wrong tenant/
   branch/role, module disable, live permission revocation, logout and
   re-login durability checks. Kept the handoff GET test-profile only,
   default-deny security and no patient names, contacts or Rx content.
4. Added deploy/opd/review-only/V11__opd_appointment_schema_candidate.sql:
   a reviewed-source PROPOSAL, not an approved production migration.
   Composite patient+registration+branch+tenant foreign keys, unique
   tenant booking request, active doctor/day/slot uniqueness, safe
   state/revision checks, immutable automatic booking/check-in/
   cancellation/reschedule events, FORCE RLS and no runtime grants.
   It is deliberately OUTSIDE Spring Flyway migration paths; V1-V9
   production baseline is unchanged.
5. Added six tests in OpdAppointmentSchemaCandidateTests which actually
   install that staged schema into a fresh disposable Testcontainers
   PostgreSQL 17, seed fictional clinic data and assert:
   - separate SQL connection retrieves committed patient/registration;
   - forged tenant/branch/patient registration references fail FK;
   - duplicate doctor slot or idempotency request fails uniqueness;
   - check-in creates revision 2 audit, later mutation is rejected;
   - cancellation frees a slot; events cannot be updated or deleted;
   - both tables FORCE RLS, original patient-runtime role and public
     receive no appointment privileges.
   These structural tests use the temporary MIGRATOR to perform writes,
   not a reviewed clinical production writer.
6. Added an exact-SHA Windows runner that performs the source/migration
   gates, full inherited R1-10A/R1-08 Maven/Node/frontend/HTTP+
   disposable PG17 suite, plus the R1-09 NO_GO self-test.

## What is still NOT implemented

**Critical INT-01/DB-01:** This PR does NOT provide a production
appointment booking, cancellation, check-in or patient intake POST
endpoint, approved forward-only Flyway V10/V11, patient registration
runtime activation, dedicated appointment SQL writer, authenticated
session-bound database command, production OPD module enablement,
browser user journey through a running service, or restart rehearsal
on an installed Windows system.

Why not: The previously approved test-only appointment handler uses
a disposable MIGRATOR SQL connection. Making it a Spring production
controller or copying it into a customer Flyway would bypass restricted
role/credential separation, session revocation ordering, patient RLS,
the held original Windows migration #31 and unresolved independent
patient security review #45.

The staged V11 does NOT authenticate a PostgreSQL writer. FORCE RLS
and absence of grants intentionally deny clinic runtime access.
Review of the production writer's SECURITY DEFINER function, database
ownership, role grants, doctor assignment, live tenant/branch/OPD
permission and session revocation transaction is mandatory before
activating actual booking.

The current SQL proof tests demonstrate structural constraints with a
privileged synthetic migrator, NOT production-grant correctness or
end-to-end clinical booking authorization. An independent non-superuser
RLS/authorization acceptance test remains a hard gate.

## Next bounded R1-10B2 deliverable - true runtime booking/check-in

- Reconcile/review existing restricted registration V10 candidate,
  dedicated role provisioning and original V8->V9 HOLD before any
  live database change. Approve backward-compatible V10 then V11
  sequence only after clean disposable migration/restore rehearsal.
- Build a separate least-privileged appointment writer and sealed
  staff-session proof; never rely on browser tenant IDs or SQL GUCs
  without server-bound authentication.
- Recheck exact bearer token session, real tenant, membership, current
  roles, OPD entitlement, branch and assigned doctor INSIDE the same
  SQL transaction as booking/check-in and immutable actor audit.
- Implement server-owned appointment ID, branch-specific slot conflict,
  request-id replay, optimistic revision and read-only branch queue.
- Prove same fictional patient registration, booked appointment and
  check-in persist after service restart/new login, deny cross-tenant,
  wrong branch/doctor, revoked session, duplicate/double slot and
  concurrent check-ins under real HTTP + restricted PostgreSQL.
- Connect Reception UI to real APIs with no demo/mock fallback;
  then complete consultation/Rx/billing persistent integration.
- Pilot Windows installer, encrypted restore and real clinical/finance
  security and accessibility/UAT remain separate blocked tasks.

## Acceptance, evidence and operations

Run on a NEW CLEAN detached Windows worktree at exact R1-10B1 SHA,
with Docker Desktop and without Vite holding files in that checkout:

    powershell.exe -NoProfile -File .\scripts\Test-Opd-R1-10B1-Booking-Schema.ps1 -ExpectedCommit EXACT_SHA

The script fails closed if SHA is wrong, V9 production migration
baseline changed, staged V11 entered Flyway, test-only endpoint guards
or deny-all security changed. It then runs the complete previous
HTTP/PG17/frontend/Maven test pipeline, including all new tests.
**This command has NOT YET EXECUTED on the new source SHA.** Do not
reuse R1-10A acceptance counts as R1-10B1 acceptance. A test failure
must be fixed and the new SHA retested.

No customer DB, W3/W4 migration, real prescription, financial
transaction, installer, signed artifact, release GO, Git merge/retarget
or protected settings change authorized.
