# P01-T07-C2-S1 — Signed authorization proof, SQL-role spoofing tests, and release gate

**Date:** 2026-11-07 IST
**Owner instruction:** "Next priority: secure the workflow before UI integration."
**Tracking:** [issue #39](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/39), [draft PR #38](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/38).

## Scope of this change

The existing proposed V10 proof-of-concept let `aronexa_patient_runtime` call a NOLOGIN-owned `SECURITY DEFINER` function using transaction-scoped `aronexa.tenant_id` and `aronexa.membership_id`. PostgreSQL custom session settings are **not authenticated**. A thief with the runtime SQL login could forge the settings, supply the UUIDs of an otherwise permitted receptionist, and attempt to insert records despite HTTP authentication.

This security slice adds a **separately signed short-lived, single-use request proof** checked inside the SQL function *before* idempotency, duplicate checking or patient creation. The proof's MAC binds:
- Versioned operation constant `ARONEXA-C2-PROOF-V1`
- Verified tenant, staff membership and requested branch UUIDs
- Full normalized name (UTF-8 hexadecimal), DOB precision and exact date/null representation
- Scoped idempotency digest and separate canonical payload fingerprint
- Generated random MRN
- Random UUID nonce and expiry epoch-seconds

Canonical serialization is a fixed sequence of fields joined with LF (PostgreSQL `chr(10)` / Java `"\n"`). MAC is HMAC-SHA256, hex lowercase, with a separate 32-byte-plus proof key from the idempotency key. Proofs are made server-side from the **authenticated** `AuthPrincipal` and are never accepted as public-client input. The function checks current actor/tenant GUCs **match signed UUIDs**, expiry is not older than now and no more than 60 seconds in the future, verifies HMAC with its protected key, and re-checks current staff membership, permission, branch assignment and module status. A nonce is claimed once, transactionally, before writes. Each HTTP retry receives a fresh nonce while the idempotency ledger ensures one registration.

The verifier-key table is initially **EMPTY** and readable only by the separate NOLOGIN writer; the patient LOGIN, auth LOGIN and application LOGIN must never SELECT it. Testcontainers explicitly seeds a disposable nonproduction key *after* migrating V10. No proof key/password is shipped by the migration. The SQL grants remain narrow: patient LOGIN can EXECUTE the restricted procedure but cannot write directly to patient identity, idempotency or proof-nonce tables. The `SECURITY DEFINER` search path is `pg_catalog, public, pg_temp` and the function body explicitly qualifies sensitive tables.

## Acceptance checklist

| Gate | Required evidence | Status until verified |
|---|---|---|
| S1-A01 | Stolen patient SQL LOGIN with spoofed valid staff GUCs but invalid proof denied; zero patient writes | PENDING CI |
| S1-A02 | Signed proof tampering (name/MRN/etc.) rejected | PENDING CI |
| S1-A03 | Expired valid proof rejected; committed nonce reuse rejected | PENDING CI |
| S1-A04 | SQL LOGIN cannot read verifier key or directly write proof nonce | PENDING CI |
| S1-A05 | Revoked/disabled staff permission/module/branch denied even if proof valid | PENDING CI |
| S1-A06 | Parallel same-key replay and different-key duplicate remain safe | PENDING CI |
| S1-A07 | Existing tests, frontend build, fresh dev runtime, docs/scope/secrets CI | PENDING CI |
| S1-A08 | Independent architecture/security review before any production plan | NOT DONE |

These tests run in **fresh disposable PostgreSQL 17 Testcontainers only**. The V10 candidate remains at `backend/src/test/resources/db/proposed/V10__c2_synthetic_staff_registration.sql` and is **not** part of production Spring Flyway discovery. V1–V9 are unchanged.

## Residual risks / hard stops

1. **Compromise of application signing key** (or owner/migrator/superuser privileges), not only stolen SQL LOGIN, can defeat this proof. Review runtime secret isolation, rotation, distribution, revocation, backup confidentiality and access controls before release. This is an isolated proof-of-concept, **not a certified production authentication system**.
2. A valid, not-yet-used proof can be misused by an attacker who separately intercepts it; nonce limits replay only after successful commit. Prevent SQL query argument logging, APM export, crash dump exposure, proxy logging, credential interception and insecure network transport.
3. PostgreSQL `SECURITY DEFINER`, RLS, schema CREATE, extension permissions, SQL GUC spoofing and credential abuse require independent adversarial review; explicitly test against a compromised DB login.
4. Rate limiting, anomaly detection, physical/cyber security, effective branch rules, secret rotation, production MRN collision retry, secure handling of personal contacts, operational restore, and deployment review are NOT complete.
5. The C1 receptionist UI in PR #36 must not connect to this API yet. No real-patient use, no production-scanned V10, no original Windows database migration, no customer release, and **no PR merge**.

**Binding governance:** [Issue #31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31) W3-E NO-GO/HOLD; W4 original V8→V9 Apply BLOCKED; PR #32, #36 and #38 DRAFT/UNMERGED.

## References
- PostgreSQL 17 pgcrypto `hmac()`: https://www.postgresql.org/docs/17/pgcrypto.html
- PostgreSQL `SECURITY DEFINER` function hardening: https://www.postgresql.org/docs/17/sql-createfunction.html

**Classification:** INTERNAL SYNTHETIC SECURITY RESEARCH / NOT READY FOR CUSTOMER DELIVERY.
