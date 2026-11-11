# P01-T07-C2-S2-C5 — Tenant Authorization Revocation Ordering

**Date:** 2026-11-08 IST  
**Authorization:** [Issue #48](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/48)  
**Scope:** synthetic PostgreSQL 17 Testcontainers only  
**Review branch:** [Draft PR #38](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/38)  
**Classification:** INTERNAL SECURITY PROTOTYPE — NOT CUSTOMER-READY

## Prior confirmed gap

[Issue #47](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/47), code head `e42cac328493ec0ced02fe991e712075213ad281`, demonstrated that a registration transaction could finish its authorization check, a privileged operator could commit ROLE, BRANCH or MODULE revocation, and then registration could commit after that revocation. Its 127 passing tests characterized **unsafe behavior**; they were not acceptance of that behavior.

## C5 controlled fix — test-only proposed V10

The protected `aronexa_c2_register_patient` function now obtains a **transaction-scoped tenant authorization advisory lock** *after verifying the signed proof, but before reading the session or authorization tables*. The lock is held until the patient registration transaction commits or rolls back.

The same transaction advisory lock is taken by **BEFORE UPDATE/DELETE row triggers** on:

- `auth_membership_role` — staff role assignment
- `auth_role_permission` — permission assigned to role
- `org_membership_branch_scope` — assigned branch
- `tenant_module_state` — tenant module entitlements/activation
- `user_tenant_membership` — membership enable/disable (trigger installed, targeted test pending)

**Order semantics, synthetic-only:** (1) if registration takes lock first, a competing covered revocation cannot complete until registration commits; therefore the registration is recorded *before* the revocation. (2) If revocation takes lock first, the incoming registration must wait; once revocation commits, the function checks the new authorization state and denies. Only the **transaction commit order** is constrained, not arbitrary wall-clock request arrival order.

The advisory lock key is derived from a distinct namespace plus tenant UUID, using PostgreSQL `hashtextextended`; unrelated tenants do not intentionally share a lock (hash collisions may cause conservative extra contention). The low-level function is callable by SQL roles, so malicious/privileged callers can potentially cause tenant-level denial of service by holding locks. This is not an authorization secret.

**No broad UPDATE privilege was added** to the NOLOGIN function writer. Test-only migration V10 stays under `backend/src/test/resources/db/proposed/`, and normal Flyway V1–V9 are unchanged.

## C5 acceptance checks

| ID | Expected observed result | Evidence state |
|---|---|---|
| C5-01 | Registration-first ROLE revocation blocks until registration commits; later operation denied | CI pending |
| C5-02 | Same for BRANCH revocation | CI pending |
| C5-03 | Same for MODULE deactivation | CI pending |
| C5-04 | Same for role PERMISSION removal | CI pending |
| C5-05 | Revocation-first registration blocks; then returns `C2_ACCESS_DENIED`, no patient, identifier, registration, audit, idempotency or nonce | CI pending |
| C5-06 | No regression to signed-proof, session locking, duplicate/transaction, MRN retries, HTTP throttling | CI pending |
| C5-07 | Fresh normal dev runtime, frontend reproducible build, scope/secrets/docs checks | CI pending |
| C5-08 | Independent review of production lock protocol and complete mutation-path inventory | OPEN |

**How to interpret a green run:** these tests are now assertions of a protective **synchronization rule**, unlike the exploratory C4 tests, which asserted the gap. However, CI passing is not independent clinical security approval.

## Residual risks and mandatory next design gates

1. **Incomplete mutation-path coverage:** `app_user.enabled` and `must_change_password` changes, the global `module_registry`, and any direct SQL/DDL or bulk `TRUNCATE` that bypasses row triggers are not covered by this lock scheme. An independent reviewer must inventory every path that can revoke rights, including admin APIs and service workers.
2. **Mixed locking order and throughput:** one tenant lock serializes all covered changes and all patient registrations in that tenant; stress-test 2+ nodes, long-running requests, deadlock risk (row locks acquired before BEFORE triggers), and malicious lock-holder denial of service. Locking alone is not a distributed rate limiter.
3. **Session expiry/role-version policy:** session `FOR SHARE` is a separate lock; transaction commit-time expiry and permission state expectations require explicit policy. Account disable is not fully covered by the listed table triggers.
4. **RLS and database owner:** independent testing must verify SQL role permissions, `SECURITY DEFINER` ownership, RLS enforcement, trigger enablement/disablement, PostgreSQL restore and migrations.
5. **Production controls remain unimplemented:** signing-key custody and emergency rotation, global anti-abuse, sensitive SQL parameter redaction, patient privacy, operational recovery/restore, customer Windows acceptance and external penetration test.

## Hard stops

No original Windows database contact or W4 Apply; no V1–V9 mutation, production-scanned V10, customer PHI, C3 receptionist UI integration, production activation, PR #32/#36/#38 merge, or deployment. [Issue #31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31) W3-E HOLD/W4 BLOCKED stays in effect. Independent security reviewer [Issue #45](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/45) remains unassigned. Overall C2-S2 remains OPEN until separately authorized and independently approved.
