# R1-01 — V9 to review-only V10 registration migration rehearsal

**Status: test-only implementation proposed; QA not executed on this branch.**
**Scope: one assisted Windows clinic pilot; no patient or persistent developer data.**
**Strict rule: the proposed registration SQL is NOT a production Flyway migration.**

## Starting point

- Based on passing OPD registration synthetic integration candidate
  commit e1885ce6b2256aa0a5dd641358655f1b550b7fae.
- That owner-reported run passed 256 backend tests with 0 errors/failures/skips.
- PR #108 is still draft/unmerged, and this R1-01 branch is dependent on it.
- V1–V9 Flyway migrations are the only production migration resources.
- deploy/registration/review-only/V10__approved_clinic_registration_writer.sql
  remains explicitly NOT approved for installation on a persistent database.
- Production registration POST is still denied.
- The NOLOGIN registration writer and its proof-key provisioning are
  separate from ordinary application/runtime database roles.

## What the new test actually proves if it passes

The new Java Testcontainers test
RegistrationV10DisposableUpgradeRehearsalTests:

1. Starts its own automatically destroyed PostgreSQL 17 Alpine container,
   creates test-only roles with the existing test bootstrap and applies the
   real immutable V1–V9 Flyway migrations.
2. Seeds an existing fictional tenant, staff membership, facility, branch,
   patient identity, MRN and branch registration. Verifies nonempty V8
   append-only identity audit evidence.
3. Writes a custom-format V9 pg_dump snapshot INSIDE that same disposable
   container, and validates its archive inventory with pg_restore --list.
4. Executes the entire review-only candidate using PostgreSQL JDBC within a
   single explicit transaction and rolls that transaction back. Verifies
   the registration writer tables are still absent, V9 history is unchanged,
   and every pre-existing patient/registration/audit row remains intact.
5. Executes the same candidate again in a disposable transaction and
   commits it. Verifies pre-existing patient, registration, MRN and identity
   audit records survive, without any V10 Flyway version being registered.
6. Confirms the preflight STILL blocks production: there is NO approved
   Flyway migration record and NO provisioned verification proof key.
7. Creates a second disposable PostgreSQL database, restores the original
   custom V9 dump using pg_restore, and rechecks the original patient data,
   version 9, FORCE RLS, writer NOLOGIN status and blocked preflight.

**This is an upgrade / rollback / restoration simulation only.** It is
not an operational backup/restore sign-off for Windows or production. Since
both databases live inside the same disposable container, this does not
establish cross-machine recovery, encryption, key backup, downtime, installer
compatibility or performance with real data volumes.

## Local validation

Use Docker Desktop, Java 21 and Maven in a clean worktree on this R1-01
branch (not the old PR #108 worktree). Do not run a SQL script manually.

    mvn.cmd -B -f backend/pom.xml "-Dtest=RegistrationV10DisposableUpgradeRehearsalTests" test

Then full regression on exact same head:

    mvn.cmd -B -f backend/pom.xml clean verify

A PASS requires the focused test, the existing 256-test regression and all
fresh Surefire results to have zero failures/errors/skips. A test that cannot
start Docker, dump or restore a database is a **FAIL**, never a waived pass.

Test output/log must not contain credentials, real patient details or
registration proof secret; any appearance blocks sign-off.

## Fail-closed release gates (NOT satisfied by R1-01)

- An independent reviewer approves forward-only production V10 DDL,
  security-definer ownership/search path, runtime SQL grants, RLS, locks,
  rate limiting and adversarial HTTP security acceptance; see issue #45.
- A real Windows V9 migration plan includes maintenance window, verified
  encrypted offline backup, independent decryption/restore exercise,
  version manifest, upgrade forward plan and no destructive rollback of
  real clinical writes. Database recovery replaces speculative downmigrations.
- Protect user and installation secrets and proof keys separately from
  encrypted clinical backup. Provide a supported recovery strategy before
  issuing real MRNs; do not embed one global key in installer or repository.
- Explicit acceptance for clinic/branch provisioning, role changes, audit,
  duplicate review, and no exposure through logs or unsupported APIs.
- Real persistent appointment, consultation, prescription and billing
  remain separately gated follow-on milestones.

## STOP conditions

Stop release if new or existing records are lost, audit count changes,
a direct runtime identity write appears, a production role becomes superuser
or BYPASSRLS, the registered Flyway history changes unexpectedly, restored
snapshot fails to validate, or a missing proof key/preflight is bypassed.

**No merge, deployment, production DB modification or runtime module
activation is authorized by this rehearsal.**
