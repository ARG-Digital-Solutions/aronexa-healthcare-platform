# P01-T07-C2-S2-C7 — Scoped Authorization Locks and Deadlock Findings

**Date:** 2026-11-08 IST  
**Owner authorization:** [Issue #50](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/50)  
**Implementation:** [Draft PR #38](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/38)  
**Classification:** INTERNAL SYNTHETIC EXPERIMENT — NOT FOR PATIENT / CUSTOMER / PRODUCTION USE

## Intent and prior evidence

C6 proved safe ordering for synthetic account and module authorization changes using a single global transaction advisory lock. It also proved **unacceptable cross-tenant blocking**: one hospital's uncommitted registration prevented an unrelated hospital from registering. See [C6 Issue #49](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/49).

C7 replaces that global lock in **test-resource-only proposed V10** with narrowly scoped transaction advisory locks while retaining covered C5/C6 security checks.

## Resource-scoped strategy (synthetic only)

| Resource | Lock on registration | Lock on mutation | Affects |
|---|---|---|---|
| `ARONEXA-C2-AUTHZ-MODULE:PATIENT_REGISTRATION` | **SHARED** transaction lock | **EXCLUSIVE** module-code lock on `module_registry` UPDATE/DELETE | All tenants using that same module for a release/status change, not for concurrent registration |
| `ARONEXA-C2-AUTHZ-TENANT:<tenant-uuid>` | EXCLUSIVE transaction lock | EXCLUSIVE tenant lock on C5 role assignment, role permission, branch, membership and tenant-module UPDATE/DELETE | Registrations and revocations within one tenant |
| `ARONEXA-C2-AUTHZ-USER:<user-uuid>` | EXCLUSIVE transaction lock on user's `app_user.id` | EXCLUSIVE user lock on `app_user` enabled/password-required UPDATE/DELETE | A staff user across memberships and tenants |

**Acquisition order inside the registration function:** module SHARED → tenant EXCLUSIVE → resolve `user_tenant_membership.user_id` → user EXCLUSIVE → session/role/branch/module authorization reads. Reading the user after the tenant lock avoids basing the user lock on an older membership row while a protected membership update is active. SQL `SECURITY DEFINER` ownership and caller grants are unchanged.

**Mutation trigger actions:** C5 triggers acquire tenant lock only; `app_user` triggers acquire user lock only; `module_registry` triggers acquire EXCLUSIVE module lock only. The trigger `UPDATE OF` column set restricts irrelevant account maintenance. A privileged multi-row/multi-resource administrative transaction can still acquire locks in a different order and **deadlock**. This is not fixed by a registration-only canonical order.

### What this protects in the tested scope

- Existing C5 role assignment, role permission, branch scope, tenant-module and membership mutation ordering (test-only).
- C6 account `enabled`, `must_change_password` and catalog `implemented`/`released`/`requires_clinical_authorization` update ordering (test-only).
- Disjoint tenant + user registrations can proceed concurrently while a shared module read lock is held by both.

### What this does not protect

- Admin operations bypassing row-level triggers, TRUNCATE, DDL/trigger disable, INSERT paths and direct privileged writes outside a controlled transaction policy.
- Cross-tenant multi-resource updates in inconsistent order; account changes spanning several tenants that also edit memberships; pre-authentication abuse and compromised SQL credentials causing lock-holder denial of service.
- Signing key custody/rotation under a real secret store, session expiry at COMMIT, proof/identity data SQL telemetry redaction, multi-node load/latency service-level objectives, operational recovery, privacy or clinical acceptance.

## New C7 acceptance matrix

| ID | Synthetic expected behavior | Evidence status |
|---|---|---|
| C7-A01 | Two unrelated tenants can create and commit independent registrations while the other's transaction remains uncommitted | Pending CI |
| C7-A02 | The previous C6 account disabled and password-required revocation tests still pass both orders | Pending CI |
| C7-A03 | The previous C6 global module release revoked with both lock orders still passes | Pending CI |
| C7-A04 | C5 role/permission/branch/tenant-module revocation, session locks, HMAC proof, idempotency, MRN retries and HTTP quotas remain green | Pending CI |
| C7-A05 | Opposite-order two-tenant admin updates form an actual PostgreSQL deadlock; exactly one transaction aborts with SQLSTATE `40P01`, the other succeeds after rollback | Pending CI; this is a **known hazard characterization**, not success of admin retry logic |
| C7-A06 | Fresh dev runtime, frontend build, documentation, secrets and scope checks pass | Pending CI |
| C7-A07 | Independent reviewer accepts all production-administration mutations and deadlock prevention/retry protocol | **NOT DONE** |

**Deadlock resolution requirement:** production administrative services must impose a consistent total order for module, tenant and user lock acquisition (and for multiple tenant IDs, stable UUID ordering), keep transactions short, handle SQLSTATE 40P01/lock timeout through safe bounded retry with idempotency, and avoid authorization writes outside reviewed workflows. This document does **not** claim such a service exists today.

**Performance measurement limitation:** the two-tenant acceptance case proves *lack of specific cross-tenant lock blocking* within a 1.5-second test bound; it is **not** a throughput, latency percentile, geographic scaling or load/chaos benchmark. Separate measurement under realistic concurrency and different deployment modes is required.

## Review and governance

The proposed V10 remains only under `backend/src/test/resources/db/proposed/`, never in normal Flyway. No production application or V1–V9 migration was modified, no persistent PostgreSQL or original Windows database touched. [Issue #31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31) W3-E NO-GO/W4 original Apply BLOCKED, PRs #32/#36/#38 draft/unmerged, no C3 receptionist API connection, patient PHI, customer pilot or deployment.

[Independent reviewer Issue #45](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/45) remains unassigned and independent security approval is still absent. **Overall S2 status: OPEN / PRODUCTION NO-GO.**
