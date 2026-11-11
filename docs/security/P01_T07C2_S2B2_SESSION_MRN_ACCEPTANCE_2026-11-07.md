# P01-T07-C2-S2-B2 — Session-bound registration and MRN collision recovery

**Owner authorization:** [Issue #42](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/42), 2026-11-07 IST  
**Implementation:** [Draft PR #38](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/38)  
**Classification:** INTERNAL SYNTHETIC SECURITY FOUNDATION / NOT_READY for clinical or production use.

## Change summary

### Session revocation check at SQL registration boundary
- Authentication's `AuthRepository.lookupSession()` retrieves the **opaque database auth_session UUID** associated with the verified bearer token and stores it in the server `AuthPrincipal`, never taking a session identifier from a browser header or registration body. `GET /api/v1/auth/me` now returns the **same established public fields through a DTO**, so the internal session UUID is **not serialized**.
- HMAC canonical proof version V3 now binds tenant, membership, authenticated session UUID, branch, exact patient fields, idempotency and payload digests, randomly generated MRN, proof key ID, nonce and expiry. V2 signatures cannot satisfy the V3 SQL verifier.
- **Test-only proposed V10** function checks that exact session UUID matches tenant and membership, is not revoked and has not expired at SQL execution time, using `SELECT ... FOR SHARE`. This holds a row lock for the registration transaction and serializes with the auth runtime's logout/password-change `UPDATE auth_session SET revoked_at`; if a revocation committed before the lock is acquired, registration rejects. Where registration wins the lock first, logout waits until that registration commits. This ordering must be independently reviewed and chaos-tested; no blanket production race-proof claim is made.
- To support the row lock, ONLY the restricted NOLOGIN registration function owner gets column-limited SELECT and UPDATE(revoked_at) on `auth_session`. The patient SQL LOGIN still has **no** auth_session read/write grant; caller never obtains these grants directly. The function never updates the session.
- Synthetic service rejects a principal without server-validated session UUID even if it has a role/permission; re-checks are inside the SQL transaction as well as the HTTP bearer filter.

### Bounded retry for one MRN uniqueness constraint
- The protected SQL function labels only `uq_patient_identifier_value` as `C2_MRN_COLLISION`, propagating all unrelated unique constraints unchanged.
- `SyntheticRegistrationService` now opens a **fresh patient transaction per attempt** using a Spring `TransactionTemplate`, generating a new random MRN, proof nonce, expiry and HMAC each time, with the same scoped idempotency digest. Only this specific MRN collision is retried, a maximum of **3 attempts**; all failed attempts roll back patient, identifier, registration, audit, idempotency and proof nonce.
- After three failures it returns a generic `REGISTRATION_UNAVAILABLE` via the existing synthetic 503 response handler; no raw MRN, patient name, SQL message or internal identifier leaks. Permission/session/duplicate/idempotency conflicts are **never** retried.
- A package-scoped, non-HTTP synthetic test hook supplies deterministic MRNs solely to exercise a true PostgreSQL uniqueness collision. Neither user request nor receptionist UI can choose MRN.

## Acceptance checks

| Gate | Evidence required | Status until CI verified |
|---|---|---|
| B2-A01 | Authenticated session ID bound by V3 proof and not exposed on /auth/me | PENDING |
| B2-A02 | Post-auth revoked and expired session denied at SQL transaction boundary; zero writes | PENDING |
| B2-A03 | SQL FOR SHARE serialization with logout/password session update | Code reviewed, independent concurrent stress PENDING |
| B2-A04 | Forced MRN collision transaction rolls back, second attempt gets fresh MRN, success | PENDING |
| B2-A05 | Three collisions -> generic failure, no orphan rows / nonce / ledger | PENDING |
| B2-A06 | Prior S1/S2-A/B1 proof, tenant, role, throttle, concurrent and HTTP regressions | PENDING |
| B2-A07 | Fresh PostgreSQL 17, unchanged V1–V9, default dev, frontend CI, diff/secrets/docs | PENDING |
| B2-A08 | Independent security/operations review and owner release GO | NOT AUTHORIZED |

## Safety and continuing blockers
The session guard reduces the **after-bearer-validation revocation window** for synthetic calls, but it is not a replacement for a formal concurrency correctness proof, network controls, logging safeguards or distributed authentication architecture. Long-running transactions may outlive expiry after the check; token theft/secret compromise, direct DB role compromise, superuser/migration owner, multi-instance throttling, backup and recovery, audit retention and clinical privacy all need separate review and testing.

No V1–V9 file was changed. Proposed V10 stays in `backend/src/test/resources/db/proposed/` and is executed **only** by isolated Testcontainers. No original Windows database migration, customer data, persistent DB apply, UI integration or production enablement. PRs #32/#36/#38 remain **draft/unmerged**; issue #31 W3-E NO-GO and W4 original Apply BLOCKED remain in force.

**Approval boundary:** owner approval for B2 synthetic development is already granted. A **new separate approval** is mandatory before merge, original Windows database operation, pilot, production or UI/customer integration.
