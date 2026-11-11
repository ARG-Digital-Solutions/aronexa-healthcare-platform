# P01-T07-C2-S2-C6 — Account and Global Module Authorization Concurrency

**Date:** 2026-11-08 IST  
**Authorization:** [Issue #49](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/49)  
**Code:** [Draft PR #38](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/38)  
**Scope:** isolated PostgreSQL 17 Testcontainers and test-resource-only proposed V10  
**Disposition:** SYNTHETIC EXPERIMENT / NO PRODUCTION SECURITY APPROVAL

## Previously uncovered authorization mutation paths

The C5 tenant advisory lock coordinated role assignments, role permissions, branch scopes, tenant module state and membership updates with registration commits. It did **not** coordinate account-level `app_user.enabled` / `must_change_password` changes or global `module_registry.implemented`, `released`, `requires_clinical_authorization` changes that also control whether staff registration is allowed.

C6 adds a deliberately conservative **global transaction advisory lock**. The protected registration function acquires this GLOBAL lock **before** its existing tenant authorization lock. The existing C5 tenant-level UPDATE/DELETE triggers acquire the GLOBAL lock before their tenant lock. New BEFORE UPDATE/DELETE triggers acquire the GLOBAL lock on:
- `app_user`, only updates to `enabled` or `must_change_password` (other account maintenance such as failed-login counts does not intentionally acquire it).
- `module_registry`, updates to `implemented`, `released`, or `requires_clinical_authorization`.

The global lock key is a namespaced `hashtextextended` advisory key; it is a synchronization mechanism **not a secret**. The lock remains held until transaction COMMIT/ROLLBACK; a protected registration cannot commit *after* an account-authorization or module-release change has already committed if the change uses these covered paths. An earlier registration may commit before a later revocation, which is the selected synthetic linearization rule.

## Explicit production NO-GO: deliberately poor multi-tenant throughput

**The global lock serializes all patient registrations across unrelated tenants.** This is a proof of ordering, not a proposed cloud or large-hospital production architecture. One slow registration, lock holder or maintenance operation can block unrelated hospitals, cause timeouts and facilitate denial of service. The new cross-tenant Testcontainers acceptance case intentionally demonstrates this limitation.

Production requires a lower-contention, independently reviewed protocol, e.g. consistent lock hierarchy with per-account/per-module authorization epochs or scoped advisory keys, plus no deadlocks across all admin workflows. Do not merge, deploy or create a production-scanned V10 based on this prototype. No production throughput benchmark or SLO was run.

## Acceptance matrix

| ID | Expected case | Evidence |
|---|---|---|
| C6-01 | Account disabled with registration-first / revoke-first locks correctly | Pending exact-head CI |
| C6-02 | Forced password-change flag set with both orderings | Pending exact-head CI |
| C6-03 | Global registration-module release disabled with both orderings | Pending exact-head CI |
| C6-04 | No incomplete patient / MRN / registration / audit / idempotency / nonce on revocation-first | Pending exact-head CI |
| C6-05 | Unrelated tenant registration is blocked while first tenant holds GLOBAL lock | Pending exact-head CI — documents performance problem |
| C6-06 | Existing S1/S2-A/B1/B2/C1/C5, HTTP, signed proof, idempotency and concurrency regressions | Pending exact-head CI |
| C6-07 | Normal development startup, frontend build, scope/secrets/docs gates | Pending exact-head CI |
| C6-08 | Independent DB security review and production protocol decision | **NOT DONE** |

## Important remaining gaps, even if tests pass

- **Privilege-changing INSERT/other mutation paths:** triggers cover UPDATE/DELETE on listed tables, not INSERT or all ALTER/DDL/TRUNCATE or privileged trigger disables. INSERT can confer a new right or affect policy; its ordering and tenant implications require inventory.
- **Cross-tenant account state:** an app_user can have memberships in multiple tenants. The global guard coordinates synthetic registration and account flag changes but at excessive cost; no fine-grained per-user lock scheme is approved.
- **Deadlock and operator risk:** PostgreSQL row locks may be acquired before BEFORE ROW triggers, so multi-table administrative transactions can deadlock with one another or with registrations. Add multi-order admin workflows, lock_timeout and high-concurrency/chaos tests before design approval. Locks can be abused by a compromised SQL credential for denial of service.
- **Other authority sources:** global module registry INSERT, active auth-session expiry at commit, login rate limiting, key custody/rotation, SQL logs/proof leakage, privacy, backup restore and clinical governance are still open.
- **One clinic/offline vs hosted cloud:** make explicit decisions by topology. A test-only global gate is not sufficient for a scalable service and cannot be assumed appropriate for either deployment.

## Unchanged hard holds

[Issue #31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31) W3-E NO_GO / original Windows W4 Apply BLOCKED; PR #32/#36/#38 DRAFT / UNMERGED; no original Windows database, persistent Flyway, production environment, real PHI, C3 receptionist UI integration, customer deployment or external reviewer access. [Independent reviewer Issue #45](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/45) remains unassigned.

**Passing tests demonstrate only the covered synthetic ordering, not readiness for release.**
