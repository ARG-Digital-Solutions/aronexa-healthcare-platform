# AroNexa Clinic Edition: assisted Windows pilot

Owner direction accepted on 12 November 2026: deliver one assisted Windows
installation to one pilot clinic, using the existing Java 21, React and PostgreSQL
stack. The owner has not decided whether reception and doctor will share one PC
or use two computers. Keep the core deployment independent of that decision;
confirm it before configuring network access and packaging the customer build.

Current classification: INTERNAL_FOUNDATION / NOT_READY. This document is an
implementation plan, not a claim that an installer or complete clinical product
already exists. No installation or real patient use is performed by this change.

## Delivery scope

Patient registration, appointments, doctor consultation, digital prescription,
basic billing, daily reports, role-based access, backup and restore. IPD, pharmacy
inventory, IVF/IUI, AI clinical assistance, native mobile/macOS and cloud sync are
deferred. No framework replacement or separate application fork is planned.

## Delivery sequence and observable acceptance

| Increment | Customer-visible acceptance | Current position |
|---|---|---|
| Installation and registration | Staff sign in, register and find a patient; record survives restart | Registration HTTP/SQL proof passed on earlier baseline; installer, provisioning orchestration, branch search and live UI pending |
| Appointments and consultation | Reception books/checks in; doctor opens the same patient and saves notes | Appointment test schema and tests added; runtime APIs and UI pending |
| Prescription, billing, reports | Doctor finalizes/prints Rx; reception bills; daily total reconciles | Separate previews/contracts exist; durable integration pending |
| Windows packaging and recovery | Clean install, restart, update and restore into a second installation succeed | Separate feasibility PRs exist; complete delivery package pending |
| Assisted clinic acceptance | Receptionist/doctor complete the agreed workflow using fictional records | Not scheduled; clinic and device arrangement to be confirmed |
| Controlled pilot | Owner and clinician accept the tested build and support/recovery procedure | Pending preceding increments |

Develop a complete vertical workflow on the integration branch. Reuse previous
work after review. Do not create more unrelated previews as a substitute for
live persistence or call a closed PR an installed feature.

## Single-developer execution

Use AI technical review, relevant automated tests and owner acceptance for routine
increments. Record actual results on the tested commit. Keep QA Central for
reusable suites/reporting; missing central coverage is recorded as NOT TESTED.
Workflow/protection changes remain explicit, reviewed changes. No fabricated pass,
automatic security-gate bypass or false independent-human approval is permitted.

A documented clean-VM fallback can supply build/test evidence during hosted CI
unavailability: record exact Git SHA, clean checkout, tool versions, test reports,
artifact checksums and installation/recovery results. This does not silently alter
GitHub-required checks. Product defects and runner availability are separate facts.

## Customer package and operations

- Versioned Windows installer, bundled runtime/application, managed PostgreSQL
  setup, desktop shortcut and assisted first-run clinic/user setup.
- App binaries separate from persistent data and protected configuration. Updates
  preserve data and keys. Uninstall must not silently delete patient records.
- No customer requirement for Git, Maven, Node or developer commands.
- Default local-only access. If two computers are selected, specify authenticated
  clinic LAN access, HTTPS, firewall rules and the one database host before release.
  Never expose PostgreSQL publicly or assume synchronization exists.
- Automatic encrypted backup, visible last-success status, backup outside the
  application drive, and restore rehearsal using a separate installation.
- Controlled updates with backup and a rehearsed recovery plan. A destructive
  schema downgrade is not assumed to be a valid rollback.
- Individual staff accounts, signed offline licence handling with continuity
  behavior defined before pilot, brief training and agreed support hours.
- No promised launch date until the integrated workflow and clean Windows install
  have been demonstrated. Confirm the pilot clinic and PC arrangement before
  customer-specific provisioning.

## First implementation: database provisioning primitive

`RegistrationDatabaseProvisioner` is an explicit installer utility, not a Spring
bean, startup hook or HTTP endpoint. It accepts a privileged connection in an
explicit transaction. It creates or validates the fixed NOLOGIN registration
writer, rejects unsafe flags and memberships in either direction, and installs a
32-byte verification key in an already migrated database. Identical active-key
reruns are harmless. Changed, disabled or expired keys are rejected without
replacement. Caller controls commit/rollback; errors do not contain SQL bind data.

The caller must restrict this operation to the intended installation, verify the
schema, use bounded connection/lock timeouts, and securely retain the proof key
and separate idempotency HMAC secret. Durable secret storage, recovery, key rotation,
schema migration orchestration and the installer invocation are still pending.
No secrets are generated, written to customer files or installed by this PR update.

Nine PostgreSQL tests cover creation in fixture setup, safe rerun, transaction
requirement, unsafe role, role memberships, identical/conflicting/inactive keys,
invalid key material and rollback. Existing real HTTP acceptance now provisions
its ephemeral key through the same utility, so a successful run validates use by
the registration writer as well as isolated setup behavior.

## Verification log

- Editing environment lacks the `javac` command, Maven and Docker. The compiler
  module is available: `java com.sun.tools.javac.Main -d /tmp/aronexa-provision-classes backend/src/main/java/com/argdigital/aronexa/patient/RegistrationDatabaseProvisioner.java`
  completed successfully on Java 21.
- `npm --prefix frontend test`: 131 passed, zero failures/skips.
- New PostgreSQL provisioning tests: pending execution. New-head CI results must
  be read after push. Earlier 204 backend results are not proof of this change.
- Windows installer, clinician acceptance, browser workflow and restore rehearsal:
  pending. No customer-ready claim.
