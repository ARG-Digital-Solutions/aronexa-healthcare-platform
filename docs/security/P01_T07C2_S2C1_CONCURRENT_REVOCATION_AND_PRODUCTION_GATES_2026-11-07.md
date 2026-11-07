# P01-T07-C2-S2-C1 — Concurrent Session Revocation & Production Security Decision Register

**Date:** 2026-11-07 IST  
**Authorization:** [Issue #43](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/43)  
**Code review:** [Draft PR #38](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/38)  
**Classification:** INTERNAL SYNTHETIC SECURITY TESTING / NOT READY FOR CUSTOMER OR PATIENT USE.

## Why this increment exists

The previous S2-B2 test checked that a *previously committed* session revocation or expiration is denied when C2 registration executes. It did **not** prove the ordering of logout and registration when the two arrive concurrently on different PostgreSQL SQL runtime connections. C1 addresses this with real cross-role transactions and PostgreSQL row-lock timeouts on a new disposable PostgreSQL 17 Testcontainer.

### New bounded adversarial tests

1. **Registration enters first:** the patient runtime calls the restricted registration function with an authentic, server-signed proof, obtains `FOR SHARE` on `auth_session` and deliberately keeps its transaction open. The auth runtime issues `UPDATE auth_session SET revoked_at` with a small `lock_timeout` and gets a lock-timeout rather than revoking mid-registration. Once the registration transaction commits, the auth runtime can revoke; a subsequent registration with that session is denied. Exactly the original registered patient and audit survive.
2. **Revocation enters first:** the auth runtime holds an uncommitted `UPDATE auth_session` row lock. A registration attempt with a valid signed proof cannot pass that lock (`lock_timeout`), and its transaction rolls back. Once auth commits the revocation, a new proof for that same session is denied by SQL with `C2_SESSION_REVOKED`. No patient, identifier, branch registration, audit, idempotency ledger or proof nonce is created.

These tests are **not** equivalent to verification against a production system under load. A committed registration may legitimately precede a concurrently requested logout. Session expiration is wall-clock time: a session valid at the protected SQL check could become expired before the registration transaction commits. The final production policy must explicitly define the authorized linearization point, token-expiration semantics and in-flight work cancellation/timeout requirements. A strong interpretation of “logout instantly cancels in-flight registration” is **not implemented**.

## Acceptance record

| Gate | Expected evidence | Status |
|---|---|---|
| C1-A01 | Real SQL auth/patient runtime lock-order tests pass | Pending exact-head CI |
| C1-A02 | Revoke-first path leaves no patient, identifier, audit, nonce or idempotency | Pending exact-head CI |
| C1-A03 | Registration-first ordering and post-logout denial | Pending exact-head CI |
| C1-A04 | Existing 122-test baseline, full backend/frontend, fresh dev and secrets/scope docs gates | Pending exact-head CI |
| C1-A05 | Independent security analysis and owner deployment decision | NOT AUTHORIZED |

## Production architecture decision register — requirements, not implementation

### A. Distributed admission and abuse control

**Do not deploy the single-JVM in-memory S2-A limiter across multiple replicas as though quotas are global.** A production design must use a centrally consistent admission decision with bounded, atomic counters or token bucket keyed to tenant plus authenticated staff/membership; separate pre-authentication identity/IP/device controls. A Redis-based limiter is an option **only after** a separate design gate for encrypted connection/authentication, atomic Lua or equivalent operations, outage fail-closed policy, clock behavior, tenant isolation, bounded keys, observability, anonymized metrics, maintenance and retry semantics. Explicitly test 2+ replicas, simultaneous overload, failover and Redis outage; no Redis provisioning or live secrets in this PR.

### B. Signing key and proof lifecycle

Keep the signing key outside source control, runtime database login, SQL/HTTP logs and normal application configuration dumps; use a separately approved managed secret store or HSM/KMS-backed solution with least privilege, version IDs, rotation activation/retirement, emergency revocation and tested rollback. The current ephemeral key and versioned key table demonstrate cryptographic checks only; **they do not constitute a secure production key custody system**. A stolen app signer/key plus a stolen SQL login is outside the tested attacker model. Define signer-key compromise recovery and event triage before deployment.

### C. SQL proof/PHI logging

The PostgreSQL function arguments include a patient display name and a signed authorization proof, even though the endpoint does not return them. Restrict `log_statement`, `log_min_error_statement`, JDBC tracing, APM spans, SQL proxy query capture and exception/error payloads; scrub crash dumps and telemetry and encrypt transport. Keep generated MRNs and patient names out of generic audit logs. Verify actual configurations, replay test traces and protected-field redaction in staging before customer use.

### D. Session revocation, patient safety and audit

Define session expiration/commit policy, logout racing registration and password/role/branch revocation under lock, account disable during transaction, and tenant-module disable. Consider transaction-boundary checks for roles/assignments and lock ordering to prevent privilege revocation races; `FOR SHARE` covers `auth_session` only. Test deadlocks and consistent lock ordering, interrupted clients, timeouts and pooled connection reuse. Preserve append-only actor-attributed audits and avoid false clinical readiness.

### E. MRN and recovery

Only the approved PostgreSQL unique MRN constraint is retried. Production requires collision alerts with no raw patient data, operational verification of idempotency, transactional rollback, random generation entropy, issuance rate, scoped MRN design and restore behavior. Test backup/restore, post-restore idempotency consistency and secret rotation; do not infer readiness from a successful synthetic retry.

## Hard stops

- **No** production Redis/secret manager, signing key provisioning or original Windows database actions.
- The proposed V10 remains in `backend/src/test/resources/db/proposed/` only. **No** V1–V9 change or Flyway W4 original database Apply.
- **No** PR #32/#36/#38 merge, C1 receptionist UI integration, real patient data, clinic pilot or deployment.
- Issue #31 W3-E NO-GO/W4 original Apply BLOCKED remains binding.
- Independent security review and explicit owner approval are still required before any merge, persistent migration, UI integration or release.

**S2 overall remains OPEN.**
