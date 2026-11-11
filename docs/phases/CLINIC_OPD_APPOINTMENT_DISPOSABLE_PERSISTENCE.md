# Clinic OPD V1 — Durable Appointment Model (Disposable Test Only)

**Milestone:** COMM-V1-T14, consolidated development PR #102
**Baseline:** PR #102 earlier head d3bc9804ed01110c5603aff2e92cd71c5c028aac
**Status:** INTERNAL_FOUNDATION / NOT READY FOR CUSTOMER OR CLINICAL USE

## Commercial goal and bounded implementation

The next persistent OPD dependency is a saved appointment linked to the existing tenant, branch, patient and current assigned doctor. This increment adds only a real *disposable PostgreSQL 17 test schema* and a JUnit/Testcontainers acceptance suite:

- backend/src/test/resources/db/test/appointment-persistence-proof.sql
- backend/src/test/java/com/argdigital/aronexa/appointment/AppointmentPersistenceProofTests.java

The SQL is applied by the JUnit fixture after the unchanged default V1–V9 migrations **only in a newly created disposable PostgreSQL instance**. No runtime Java service, HTTP route, production Flyway migration, patient registration or original Windows database was changed. The Reception UI is not yet connected to these rows.

## Tested model and negative gates

1. Appointment booking, revision, staff/doctor/branch/patient linkage and metadata-only audit survive a different SQL connection.
2. Appointments require a patient registered at that branch, an enabled branch-assigned DOCTOR membership and an enabled receptionist with explicit APPOINTMENT_BOOK permission.
3. OPD module must be implemented/released, tenant-entitled/activated and clinically authorized. Test fixture enables OPD only in a brand-new disposable container.
4. A partial UNIQUE PostgreSQL index disallows double booking across concurrent writers for the same tenant, branch, doctor, date and fifteen-minute slot. Cancellation frees a slot; check-in keeps it occupied. Successful rescheduling moves the slot, releases the original, creates a RESCHEDULED audit event and rejects stale revision or patient reassignment.
5. Revision guard and transitions reject stale writes, cancelled/checked-in edits and patient/branch identity changes. Failed writes must not append audit rows.
6. Composite foreign keys reject cross-tenant linking; unregistered patients, unassigned doctor, revoked membership or disabled OPD are denied by database constraints.
7. A restricted no-BYPASSRLS read-only test role sees appointments only when transaction-local tenant and currently assigned receptionist branch match, cannot view patient names or modify appointment rows, and sees no appointments without tenant scope.

## Security limitations — not production authorization

The write proof uses a privileged *disposable Testcontainers migration owner* to exercise constraints. The submitted staff actor membership is **not bound to an actual bearer token**. This SQL MUST NOT be deployed as a real clinic appointment API. A malicious SQL client could forge an actor or custom PostgreSQL session settings. Real protected operations need server-verified, unrevoked auth session and tenant/branch/role/doctor/patient permissions checked in the same restricted SQL transaction as the command and audit.

No real provider roster, staff leave calendars, duration overlaps beyond a single 15-minute slot, reschedule idempotency journal, clock/timezone policy, medically reviewed visit status or clinic queue is implemented. Nor is there a database-backed production REST API, linked React screen, immutable clinical prescription, billing transaction or Windows installer.

## Exact-source QA and release gates

**As of 2026-11-11:** T14 new test outcomes NOT RUN. All PR #102 GitHub Actions jobs have been failing BEFORE acquiring a runner or executing any steps, including one manual retry. Track [CI-OPS #105](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/105). Do not misclassify this as a failing JUnit or a successful test. The focused command on a freshly created disposable Java21 + Maven + Docker VM is:

    mvn -B -f backend/pom.xml -Dtest=AppointmentPersistenceProofTests test

The normal Maven verify includes these tests and the frontend build must remain green. Draft-deferred Central QA is NOT TESTED. The added SQL proof now has **18 focused JUnit methods** (including positive reschedule and immutable-patient negative cases). If CI remains unavailable, a **disposable Windows VM only** may run the guarded script `scripts/ci/Verify-Disposable-Appointment.ps1` with `ARONEXA_DISPOSABLE_QA_VM=YES` and `-DisposableVMConfirmed`, optionally `-ExpectedCommit <exact SHA>`. It rejects a dirty checkout, the wrong branch, original DB environment values and a remote Docker engine. The local result does **not** replace exact-head GitHub CI or independent QA.

Original Windows migration #31 W3-E/W4 HOLD, independent patient security review #45, controlled merge #63, and independent clinical/Windows release requirements remain unchanged. No original/customer data, live SQL grants, merge, production migration, deployment or customer release is authorized.

## Next practical integration

Implement a test-profile-only authenticated appointment HTTP boundary that uses this proven slot/data model and an authenticated server-derived actor; add duplicate-key/slot-conflict/revocation/restart acceptance. Connect the current Reception UI and test real browser+PostgreSQL persistence, then doctor consultation and Rx/billing transactions. The separate Windows service, licensed installer, encrypted full PostgreSQL restore and clinician UAT remain release gates.

**Disposition: INTERNAL_FOUNDATION / NOT READY.**
