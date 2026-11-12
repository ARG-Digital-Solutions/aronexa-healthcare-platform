# AroNexa Clinic Edition — persistent authorized OPD journey

**Scope:** Patient Registration → Appointments → Doctor Consultation → Prescription → Billing.
**Owner direction:** Windows offline first, one assisted pilot clinic; single-developer incremental delivery; data security and simple UX ahead of breadth.
**Status (2026-11-12):** READ-ONLY AUTHORIZED REFERENCE ENDPOINT + FRONTEND LINK CONTRACT IMPLEMENTED; REGISTRATION WRITES AND PERSISTENT END-TO-END JOURNEY **NOT IMPLEMENTED**. NO PATIENT DATA. NO DEPLOYMENT.

## Current source reality

- The reception/appointment interface is explicitly synthetic; it stores data only in browser memory.
- frontend/src/registrationClient.ts maps a narrow authorized registration request, validates the success receipt and retains one idempotency key for retry, but **is not invoked by the real receptionist UI**.
- SyntheticRegistrationController and SyntheticRegistrationService are enabled **only** with test & c2-synthetic & !dev & !prod. The reviewed proposed registration writer DDL is backend/src/test/resources/db/proposed/V10__c2_synthetic_staff_registration.sql, not a production Flyway migration.
- Default runtime SecurityFilterChain denies the registration route unless the synthetic test profile is active. Do NOT weaken that restriction or enable a real patient write on the basis of these tests.
- A new read-only GET /api/v1/branches/{branchId}/patient-registrations/{registrationId} uses PatientAccessService for exact permission, tenant/branch SQL RLS, module entitlement, live session and append-only access audit. Only tenantId, branchId, patientId and registrationId are returned; no MRN, demographic or clinical data. It remains non-functional until a legitimately provisioned account and an approved active module exist. It deliberately does NOT enable patient creation.
- frontend/src/registrationReferenceClient.ts verifies the authenticated read receipt with same-origin, no-store and no token persistence, and is NOT wired into the demo Reception UI yet.
- Registration UUIDs in the API path are pseudonymous patient-linked identifiers: redact URL paths from HTTP/access logs, tracing and telemetry before operational use; do not embed names, phones or clinical details in URLs.
- Real HTTP/Testcontainers acceptance tests for the read-only endpoint and frontend transport model tests are committed but NOT RUN in this tool session.
- Encounter persistence exists as a tested contract/proof, not a completed clinical HTTP workspace. Consultation, Smart Rx and Billing are stand-alone synthetic previews.
- Existing application CI and verified synthetic QA have been reported as passing locally by the owner, but these are not evidence of an integrated persistent workflow.

## Shared identity contract (new)

frontend/src/opdJourney.ts provides **in-memory only** UI reference continuity; frontend/test/opd-journey.test.mjs tests the safe shape and stage invariants.

| Stage | Required server identity | Additional server receipt |
|---|---|---|
| REGISTERED | tenantId, branchId, patientId, registrationId, MRN | Existing successful RegistrationReceipt from authorized registration |
| BOOKED | Same tenant/branch/patient/registration | appointmentId |
| CHECKED_IN | Same tenant/branch/patient/registration/appointment | Confirmed check-in, no new appointment |
| CONSULTED | Same earlier references | encounterId and positive encounterRevision |
| PRESCRIBED | Same earlier references | prescriptionId and positive prescriptionVersion; prior encounter revision |
| BILLED | Same earlier references | invoiceId; prior prescription and encounter revisions |

Only **server-issued UUIDs** may advance the journey; a synthetic DEMO-... ID must never be submitted as a real patient, appointment, encounter or invoice ID. A later receipt may not overwrite an earlier identity, introduce unrelated future IDs, or skip a stage. A refresh/reopen must rebuild this UI context from newly authorized server reads; do not store access tokens or patient/clinical payload in browser persistence.

**Critical trust boundary:** These client checks are NOT authentication, authorization or persistence proof. The backend must derive tenant and membership from the current nonrevoked session; enforce branch assignment, doctor/patient relationship, module entitlement, SQL role and RLS; audit each write transaction; verify idempotency and resource ownership; and reject conflicting/duplicate transactions. Never trust a client-provided tenantId, doctorId, appointmentId or encounterId in isolation.

## Development sequence — one persistent vertical slice at a time

1. **Registration production readiness (BLOCKED):** preserve the new read-only reference endpoint and independently review a new forward-only migration/provisioning plan derived from the synthetic V10 proof. Preflight fresh database roles, scoped NOLOGIN writer, secret/key lifecycle, Flyway upgrade/rollback strategy, audit, idempotency, duplicate-review process, and tenant/branch/role/session checks. Implement authenticated clinical UI login and safe account provisioning. Only then introduce a real authenticated route and connect registrationClient; keep demo mode visibly separate.
2. **Appointments (PENDING):** implement transactional PostgreSQL appointment/slot records referencing existing tenant+branch+patient+registration, real doctor assignment, database-enforced slot conflict exclusion, and audited book/reschedule/cancel/check-in. Prove two simultaneous booking attempts cannot win one slot.
3. **Consultation (PENDING):** link checked-in appointment to an authorized doctor-owned encounter, record immutable audit, optimistic revision checks, server-computed timestamps and persistence across app restart. Return the encounterId with the SAME patientId.
4. **Smart Prescription (PENDING):** link medicines, note and ink assets to the same encounter; store validated drafts with revision checks and immutable finalization/version history. Clinician signs; no issued prescription from demo previews.
5. **Billing + Daily Reports (PENDING):** invoice the same encounter, store amounts in integer paise, unique bill/payment idempotency, authorized reception/billing access, audit and reversal—not overwriting paid entries. Daily totals come from database transactions.
6. **Windows assisted pilot (PENDING):** offline installer, unique tenant/clinic provisioning, startup recovery, yearly license, encrypted backup/restore and re-import, clinic acceptance with synthetic data, negative authorization tests and explicit go/no-go.

Do not create a hidden fallback to demo if a real API fails. Use an explicit error and preserve the same idempotency key for a user's requested retry. Never create the next stage merely from local state.

## Required real end-to-end acceptance

Using synthetic patients and an isolated PostgreSQL Testcontainers instance:

- Authenticated receptionist registers a fictional patient and gets stable patientId, registrationId and MRN. Retry after losing HTTP response returns the same committed record, not a second patient.
- Wrong tenant, branch, role, expired/revoked session, module-disabled, forced-password-change, duplicate candidate and SQL runtime role all deny writes without leaking patient data.
- A second receptionist cannot double-book the same doctor/branch/date/time even under concurrent transactions.
- Receptionist checks in that appointment; only an authorized doctor can save/revise its encounter. Wrong patient, wrong encounter, stale revision and interrupted transaction reject with no partial writes.
- Prescription and invoice link to that same encounter and patient, with immutable finalized prescription versions, no payment duplication, auditable changes and branch-restricted reports.
- Reload page, restart Spring Boot, and re-open the test database: the same registration → appointment → encounter → finalized prescription → invoice are still present and correctly authorized.
- Confirm no patient information is included in QA logs, test artifacts, URLs, Git history, secrets, browser localStorage or demo fixture exports.

The sequence above is an acceptance contract, NOT a claim that any listed end-to-end test currently runs or passes.

## Run only the newly added model tests

From an isolated worktree on the current feature branch with Node 22+:

    cd frontend
    node --experimental-strip-types --test test/opd-journey.test.mjs

Run the frontend reference client tests and backend HTTP acceptance tests as well:

    node --experimental-strip-types --test test/registration-reference-client.test.mjs
    cd ..
    mvn.cmd -B -f backend/pom.xml -Dtest=PatientRegistrationReadHttpAcceptanceTests test

Then run npm ci && npm run build to include all existing frontend model tests. Maven/Testcontainers and independent browser QA must also run on the accepted integrated SHA before any release determination. GitHub Actions billing failure is not permission to skip review or required security gates.

**Merge:** Draft review against integration/clinic-opd-v1-20261111; do not merge into main, change protected rulesets, activate production migrations, deploy or handle real patient data on the basis of this contract alone.
