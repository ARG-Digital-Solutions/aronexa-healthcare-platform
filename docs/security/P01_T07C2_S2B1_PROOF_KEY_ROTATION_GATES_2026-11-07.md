# P01-T07-C2-S2-B1 — Versioned Synthetic Registration Proof Keys

**Date:** 2026-11-07 IST  
**Authorization:** [Issue #41](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/41)  
**Implementation:** [Draft PR #38](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/38)  
**Classification:** INTERNAL SYNTHETIC ONLY — NOT_READY for patient, clinical, customer, UI or production use.

## Purpose

Permit an isolated security reviewer to exercise a *controlled proof signing-key rollover* without broadening SQL permissions or allowing unbound proof key-ID substitution. This is a demonstration of core cryptographic boundaries, **not** a live key-rotation service or secret-manager integration.

## Implemented in proposed test-only V10 and synthetic Spring profile

- `public.patient_registration_proof_key` supports positive key IDs, enabled/disabled state, inclusive `valid_from` and exclusive `valid_until`; invalid windows are CHECK-rejected. No verification key is seeded by the migration. `aronexa_registration_writer` NOLOGIN only SELECTs this table. Patient, authentication and application runtime logins have no key table access.
- `SyntheticRegistrationProof` binds key ID as a separate field in an incremented, domain-separated canonical `ARONEXA-C2-PROOF-V2` HMAC. This intentionally breaks V1 candidate proofs: old unsigned-key-ID proofs must be denied.
- `aronexa_c2_register_patient` requires a positive `p_key_id` in its argument list, verifies its enabled/time-active key, and recomputes HMAC including the ID. A disabled, unknown, future-dated, or expired key has no verifier entry and returns `C2_PROOF_DENIED` **before patient writes**. The original nonce/expiry, actor/tenant/branch/permission/module, audit, idempotency and duplicate protection still run.
- The `test & c2-synthetic & !dev & !prod` service selects `aronexa.c2.synthetic-proof-key-id` (default 1 for existing disposable tests). It never consumes a client-supplied key ID or signing key. A missing or invalid configured key fails closed.
- Synthetic Testcontainers fixture explicitly seeds ephemeral key 1. An adversarial integration test provisions a distinct random key 2, verifies one successful signed registration, then attempts tampered key-ID, disabled-key, future-key and expired-key operations, asserting denial and unchanged patient/nonce counts. Unit tests establish key ID binding and reject ID 0.

## S2-B1 acceptance gates

| Gate | Expected | Evidence |
|---|---|---|
| B1-A01 | Key ID included in MAC and SQL argument, unsigned substitution fails | CI tests pending final verification |
| B1-A02 | Active secondary proof key succeeds; disabled/future/expired rejected, no orphan writes | CI tests pending final verification |
| B1-A03 | Existing S1/S2-A proof, HTTP, throttling, concurrency, tenant isolation pass | CI tests pending final verification |
| B1-A04 | Key table unselectable/unwritable by three restricted LOGIN roles | CI tests pending final verification |
| B1-A05 | Fresh runtime in normal dev unchanged; proposed V10 only in test resources | CI tests pending final verification |
| B1-A06 | Owner review and independent production security acceptance | OPEN / NOT APPROVED |

## Security constraints and residual work

- The key database table is **not a production key-management solution**. The test-only migrator can provision/disable keys, while the NOLOGIN function owner can read verification keys. A production system requires a dedicated key store, key access separation, secret distribution, emergency revocation, secure log and memory handling, and tested rotation runbooks.
- A cached application signer can still sign with a disabled key; the database intentionally **rejects** the proof instead of silently falling back. Deployment must coordinate signer configuration and verifier overlap to avoid failures.
- Compromised app signer, database owner/superuser, SQL query logging of proofs, HTTP bearer session revocation race, MRN collisions, distributed throttling, operational backup/restore and independent adversarial review remain OPEN.
- No release, original Windows V8/V9 W4 Apply, migration of production V10, UI integration, PHI ingestion, customer access or PR merge has been authorized.
- Issue #31 W3-E NO-GO and W4 original migration HOLD; PRs #32, #36 and #38 stay DRAFT/UNMERGED.

**Next separately verified increment inside S2**: session-revocation-at-transaction-boundary and MRN collision recovery design and Testcontainers tests. Do not interpret this document as authorization for customer use.
