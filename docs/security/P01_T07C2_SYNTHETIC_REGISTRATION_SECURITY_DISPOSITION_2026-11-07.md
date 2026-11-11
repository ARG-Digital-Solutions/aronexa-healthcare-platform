# P01-T07-C2 — Security disposition and HTTP acceptance gates
Date: 2026-11-07 IST | Source: draft PR #38; owner authorization: issue #37.

## Scope / facts
- Default `dev` and production profiles continue to deny patient registration. Controller and service require Spring profile expression `test & c2-synthetic & !dev & !prod`; the exact HTTP matcher checks the same condition. C2 code may only be exercised under ephemeral Testcontainers-backed test configuration.
- Proposed forward-only V10 exists **ONLY** under `backend/src/test/resources/db/proposed/` and is not scanned by default production Flyway classpath. No original Windows persistent volume/database was accessed or migrated.
- New NOLOGIN `aronexa_registration_writer` function owner and `aronexa_patient_runtime` have separate privilege scopes. LOGIN runtime gets no direct patient identity-table write privilege. Function verifies actor membership, permission, branch scope, module entitlement/state, duplicate check and idempotency. V8/V9 remain unchanged.
- Automated service, concurrency and HTTP tests use synthetic data and isolated PostgreSQL Testcontainers; CI PASS is not independent clinical or production security acceptance.

## Material security blocker: PostgreSQL runtime-credential compromise and GUC spoofing
The function obtains tenant and membership identity through `current_setting('aronexa.tenant_id')` and `current_setting('aronexa.membership_id')`. Java's `PatientSqlScope` uses a verified bearer principal under normal application operation. **However, an attacker with the patient SQL login can directly set these PostgreSQL custom session settings**. A SECURITY DEFINER function's DB-side checks using these settings would then assess the *spoofed* tenant and actor and could register records under another valid membership where applicable. A NOLOGIN function owner, RLS and narrow EXECUTE rights reduce blast radius but **do not authenticate the caller's claimed identity in this attack scenario**. This risk is NOT resolved by C2 and makes patient/customer use a strict NO-GO.

### Requirements for a future mitigation
1. Architect a non-spoofable caller-authorization proof bound to a verified staff session, exact tenant + membership + branch + operation, request digest, expiration and one-time nonce. The protected verifier must be unusable with the ordinary SQL login alone; secret and replay state isolation must be independently reviewed.
2. Deny use of the SECURITY DEFINER function on stale, absent, replayed, altered or cross-tenant proof, including with directly stolen SQL credentials.
3. Verify grants/ownership/search_path/SQL injection/connection-pool context under adversarial PostgreSQL role tests, and resource/object-level access and revocation behavior.
4. Rate-limit at authenticated HTTP boundary, control concurrent registration and MRN collision rollback/retry, audit without identifiable data, and validate backups/restore.
5. Conduct independent security review and explicit owner authorization before creating a production-scanned V10 or enabling any real patient route.

## HTTP acceptance (synthetic only)
`SyntheticRegistrationHttpAcceptanceTests` exercises genuine MockMvc security filter + opaque session lookup against real Testcontainers PostgreSQL. Intended assertions: 201 create, 200 same-key replay, 400 invalid/missing input, 401 absent/invalid/revoked bearer, 403 forced password change, missing permission, wrong branch and disabled module, 409 conflicting replay or duplicate review, no raw contact/name in response, no-store cache header, and actor metadata audit.
- Tests run under opt-in `test,c2-synthetic`; **normal dev profile must never host them.**
- The test fixture may toggle an otherwise unreleased module locally; V9 does not seed usable grants/entitlements.
- See the GitHub Actions run for the **exact latest status**. This document alone claims no passing test that has not executed.
- Manual accessible receptionist UI review in PR #36 remains a separate gate; C2 must not silently connect to it.

## Binding stops
W3-E NO_GO/HOLD and W4 original V8→V9 Apply BLOCKED (#31); PR #32 draft/unmerged; PR #36 draft/unmerged; PR #38 draft/unmerged, no merge, no original V8/V9 changes, no patient/customer data, no deployment, no Phase 01 closure.
Release classification **INTERNAL_FOUNDATION / NOT_READY**.
