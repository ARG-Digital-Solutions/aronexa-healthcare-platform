# OPD V1 — Staff Reception connected registration QA preview

**Status: INTERNAL / SYNTHETIC / DISPOSABLE TEST ENVIRONMENT ONLY.**
**Not approved for patient care or production. No change to the commercial application's registration POST availability.**

## What is now connected in source

An independent Reception integration preview combines:

1. staffSessionClient.ts — real POST /api/v1/auth/login then GET /api/v1/auth/me handshake, validated member/tenant/role data, bearer in active memory only, no cookie, browser storage, automatic login retry, credential logging or password persistence.
2. connectedRegistrationFlow.ts — explicit review of the proposed identity; verified staff PATIENT_REGISTER capability precheck (not a grant); one frozen registrationClient.ts submission with a stable idempotency key; no direct demo registration or fallback; no new key after an uncertain POST.
3. registrationClient.ts — existing authenticated POST with exact branch UUID and opaque bearer; rejects unsupported phone data and non-200/201 responses; only server-issued patient UUID, registration UUID and MRN accepted.
4. opdJourney.ts — keep the server-issued patient, tenant, branch, registration and MRN references in one immutable in-memory REGISTERED stage.
5. Optional GET /api/v1/branches/{branchId}/patient-registrations/{registrationId} re-read for staff with PATIENT_REGISTRATION_READ; a mismatch is a stop signal.
6. A distinct React screen connected-reception-preview.html, built only via the explicit build:connected:test script, not imported by the standard Reception App.tsx. UI states: staff sign-in → fictional patient details → explicit review → server acknowledgement OR unresolved retry of the same immutable submission. No demo seed patients or fake appointment IDs are mixed with server IDs.

**The normal Reception application remains SYNTHETIC App.tsx.**
Its patients, queue, appointments and visit screens are not backend-connected.
The separate test preview has no doctor assignment, appointments, consultation,
prescription or billing implementation.

## Security and release restrictions

- Normal Spring Boot dev/prod registration POST route remains denied; the candidate write adapter exists exclusively in backend/src/test/java and in the disposable test + registration-candidate (not dev/prod) context.
- Production SQL writer candidate remains in deploy/registration/review-only and is NOT part of main Flyway locations. Never manually install it on customer, Windows or persistent developer databases.
- The separate Vite dev proxy targets 127.0.0.1:8080 only for local test setup. It does not start or provision the synthetic backend, test accounts or candidate writer; a normal backend refuses POST.
- The preview is test-only. It does not collect clinical consent, resolve family contacts or establish a real patient identity. Use fictional patients only.
- The client never stores tokens in localStorage, sessionStorage, cookies or URLs. An uncertain response blocks a new attempt until the same request/key is resolved; closing the tab loses that pending key. Commercial intake requires an approved operator recovery procedure.
- Server RLS, role, module entitlement, session revocation, duplicate handling, idempotency and audit remain authoritative; UI validation cannot replace them.
- Backend staff sessions expire in about 30 minutes; explicit sign out revokes the bearer while clearing UI memory first.

## Verification — exact current commit

Use a clean review worktree with Docker Desktop and Node 24:

    cd frontend
    npm.cmd ci --no-audit --no-fund
    npm.cmd test
    npm.cmd run build
    npm.cmd run build:connected:test

    cd ..
    mvn.cmd -B -f backend/pom.xml "-Dtest=RegistrationCandidateHttpAcceptanceTests" test
    mvn.cmd -B -f backend/pom.xml verify

The Java candidate HTTP acceptance suite now invokes
frontend/test/integration/connected-reception-wire.mjs via Node, passing a
fictional test account over stdin. It uses real staff login, GET /auth/me,
explicitly reviewed registration, durable receipt and logout. Java separately
asserts one committed row each in patient, patient_identifier,
patient_registration, idempotency and write audit.

This cross-layer test is NOT run by the normal Vite build or npm test alone.

**Evidence status:** new code/tests committed to draft PR #108; NOT RUN here.
The earlier owner-reported full Maven BUILD SUCCESS is from a prior PR head
and does not validate these new additions. Collect fresh Java, TypeScript,
Node, browser, keyboard and independent security evidence before merging.

## Next commercial milestones

1. Independently approve forward-only V10, NOLOGIN role/procedure, proof key provisioning and encrypted backup/restore rehearsal on both empty and existing Windows V9 databases.
2. Secure staff onboarding/password-change UX, server-provided branch choices, consent and duplicate-review procedures; remove manual branch UUID entry from any customer UI.
3. Review the standard runtime POST endpoint with rate limiting, byte limits, SQL authorization, privacy logging and negative HTTP/Testcontainers acceptance.
4. Wire real Reception UI to the persistent path only after steps 1–3.
5. Implement persistent authorized Appointments/check-in → Consultation/Encounter → immutable Smart Rx → Billing and daily reports. Verify offline installer/licensing.

**Merge gate:** exact-SHA tests PASS plus required independent review and
migration/restore evidence. No production clinical writes or customer deployment.
