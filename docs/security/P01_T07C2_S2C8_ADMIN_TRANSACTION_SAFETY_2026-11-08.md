# P01-T07-C2-S2-C8 — Synthetic Administrative Transaction Safety

**Date:** 2026-11-08 IST  
**Authorization:** [Issue #51](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/51)  
**Implementation:** [Draft PR #38](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/38)  
**Classification:** ISOLATED POSTGRESQL 17 TESTCONTAINERS ONLY — NOT AUTHORIZED FOR PATIENT / CUSTOMER USE.

## Problem and constrained design

The C7 scoped locks allow unrelated tenant registrations to proceed concurrently, but a deliberate test showed PostgreSQL deadlock `40P01` when privileged multi-tenant administrators update tenant A then B versus B then A. That test is retained as an **adversarial risk reproducer**. It did not establish a usable safe admin workflow.

C8 adds **`SyntheticAdminBatchExecutor` exclusively in `backend/src/test/java`**, not a production endpoint or production Java service. It is a narrowly defined experiment: deactivate the `PATIENT_REGISTRATION` entitlement for a finite list of disposable synthetic tenants using a connection factory supplied by a PostgreSQL 17 Testcontainers test. It cannot provision, promote, edit or merge user, doctor, patient or real clinical identity data.

## Order and rollback contract

1. Require non-null, nonempty tenant UUIDs. Deduplicate and sort them using a deterministic Java UUID order. Inputs are UUID parameters, never SQL identifiers or interpolated tenant names.
2. Create a **new isolated PostgreSQL connection** for each attempt. Set an explicit transaction (`autoCommit=false`), `lock_timeout=2500ms` and `statement_timeout=8000ms`.
3. Acquire all target tenant transaction advisory locks using the **same key namespace and hash construction as the test-only C7 guard**, in the same total sorted UUID order, **before** updating any authorization row.
4. Update only tenant `PATIENT_REGISTRATION` rows whose `activated=true`, via parameterized SQL. A second idempotent batch sees zero rows to change. Existing module audit triggers record only committed changes.
5. On success, commit once; return the attempt number and changed row count.
6. On PostgreSQL `40P01`, fully roll back the transaction, close the connection, open a **new connection/transaction** and replay the same sorted and idempotent operation, at most **three attempts total**.
7. On any other `SQLException`, including uniqueness, privilege violations and lock timeout, **do not retry**. Require successful rollback; if rollback itself fails, abort rather than replaying uncertain writes. Return only a generic synthetic failure, not the raw SQL text.
8. **Never share a JDBC Connection across concurrent worker threads.** The concurrency test uses one fresh connection per batch.

This does **not** implement arbitrary admin transaction orchestration, compensating actions across external systems, distributed job deduplication, an administrative API, production-grade alerts or operator permissions.

## C8 isolated verification matrix

| Gate | Expected evidence | Status before final CI |
|---|---|---|
| C8-A01 | Reversed and duplicate tenant lists produce one committed change and one audit per effective row | PENDING |
| C8-A02 | Four concurrent overlapping batches with inverse input order complete without deadlock, only effective changes audited | PENDING |
| C8-A03 | Database-raised `40P01` on first attempt causes full rollback and **fresh connection** on second attempt; committed audit rows exactly once | PENDING |
| C8-A04 | Three consecutive `40P01` failures result in generic error, exactly three attempts, zero updated flags/audits | PENDING |
| C8-A05 | Database-raised nonretryable `23505` is not retried; zero committed changes/audits | PENDING |
| C8-A06 | Original uncontrolled reverse-order admin test still reproduces PostgreSQL deadlock; this remains a known risk outside C8 helper | PENDING |
| C8-A07 | Existing signed proof, role/branch/module/session ordering, MRN/nonce/idempotency, HTTP limits, frontend build and fresh normal dev regressions | PENDING |
| C8-A08 | Independent architectural, SQL privilege, throughput and admin-workflow assessment | **NOT DONE** |

## Remaining production security and scalability decisions

- **Lock ordering everywhere:** real admin workflows can combine module, tenant and user changes, multiple affected tenants, `auth_session` updates and privileged tools. They must all acquire the documented resource locks in a consistent order before row locks. This test helper only handles a sorted set of tenant-module changes.
- **Deadlock retry is not general error recovery:** rollback on `40P01` and replay is suitable only for confirmed idempotent database-only operations. Retry must not duplicate emails, patient notifications, signed clinical actions or external payments. Lock timeout (`55P03`), serialization failures, unknown commit results and network breaks need separate explicit policies.
- **Audit and privacy:** module configuration audits are not substitutes for administrator identity attribution, tamper evidence, protected logs, clinical record retention and authorization of the actor performing the change.
- **Performance:** a four-worker synthetic collision test is neither a service-level benchmark nor evidence of acceptable p95/p99 latency under hospital load. Measure throughput, deadlocks, wait queues and tenant fairness under realistic concurrency before adopting the C7/C8 protocol.
- **Privilege and risk:** the example uses a **disposable test migrator** connection. Do not give that credential, broad SQL UPDATE grants, or the helper to a clinic receptionist. A production admin service needs least-privilege procedures and separately approved human/tenant authorization.
- **Other S2 blockers:** independent reviewer unassigned; key custody/rotation, multi-replica throttling, SQL parameter and PHI logging, JWT/bearer theft, backup/restore, privacy, Windows acceptance, C3 integration and release governance unresolved.

## Hard stops

- No modification to V1–V9; test-resource-only proposed V10 remains outside normal Flyway.
- No original Windows PostgreSQL database, W3-E/W4 migration, real patient data, customer pilot, production Redis/secret store or UI integration.
- [Issue #31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31) original database HOLD; PRs #32, #36 and #38 remain DRAFT/UNMERGED.
- [Independent reviewer assignment #45](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/45) still required. This document and green CI do **not** constitute external security signoff.

**Overall security disposition: INTERNAL_SYNTHETIC_ONLY / S2 OPEN / PRODUCTION NO-GO.**