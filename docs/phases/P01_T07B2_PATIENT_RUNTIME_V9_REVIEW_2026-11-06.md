# P01-T07-B2 — Dedicated Patient Runtime, Flyway V9 and Review Gates

**Date:** 2026-11-06 IST
**Release classification:** INTERNAL_FOUNDATION / DRAFT PR #30 / not released
**Owner directive:** proceed with P01-T07-B2 secure authorization and persistence boundary.
**Explicit distinction:** staging a forward-only V9 migration in source and applying it to disposable CI/Testcontainers **does not authorize applying V9 to the owner's persistent Windows V8 database**.

## Implementation inventory

- `V9__patient_registration_read_boundary.sql`: requires existing `aronexa_patient_runtime` role (nonowner, NOBYPASSRLS), inserts disabled `PATIENT_REGISTRATION` module and ungranted staff permission vocabulary, creates patient-specific tenant/member context, RLS policies, constrained successful-read audit, and exact SQL grants. No patient identity/contact writes.
- `deploy/dev/initdb/10-create-runtime-role.sh`, `deploy/dev/compose.yml`, `.env.example`: new distinct patient role credential generated only on a fresh dev volume; initdb **does not execute** again on existing volumes.
- `AuthPersistenceConfiguration`, `application-dev.yml`: dedicated patient JDBC datasource and transaction manager, separate from app and authentication pools.
- `PatientSqlScope`: verifies an authenticated staff principal and sets tenant+membership with transaction-local `set_config` on the **same patient connection** used for patient SQL.
- `PatientAccessService`: internal-only exact permission/active-module/branch authorization; registration IDs scoped to membership and branch; successful reads insert actor-attributed immutable audit in the same transaction.
- `PatientAccessServiceIntegrationTests`, `PatientRuntimeBoundaryTests`: synthetic Testcontainers tests for grants, RLS, branch membership, authentication state, module denial and audit immutability.
- CI configuration: dedicated fourth credential, fresh V9 migration and grant assertions; no customer data or existing-volume operation.

## Least-privilege grants proposed in V9

| Runtime DB role | Allowed from V9 | Explicitly denied |
|---|---|---|
| `aronexa_patient_runtime` | SELECT on `patient_registration`, `org_membership_branch_scope`, `module_registry`, `tenant_module_state`; INSERT on `patient_registration_access_audit` only | Patient master, identifiers, contacts, relationships, V8 identity audit, auth user/session, registration INSERT/UPDATE/DELETE, admin provisioning |
| `aronexa_app_runtime` | Existing organization and module access unchanged | Every V8 patient identity/registration table, new access-audit table |
| `aronexa_auth_runtime` | Existing authentication operations unchanged | All patient tables, new access-audit table, organization metadata |

**Defense in depth:** V9 registration-read RLS checks tenant AND assigned membership+branch; patient module/branch data also scoped by RLS. The access-audit table has composite tenant/branch/registration FKs and a dedicated INSERT RLS policy. The server uses exact membership permissions and module activation in the same patient JDBC transaction.

**Important risk:** PostgreSQL application GUCs (`aronexa.tenant_id`, `aronexa.membership_id`) are **not cryptographically authenticated**. An attacker with direct unrestricted SQL access to the patient role could spoof them. Application-layer privilege validation, query surface isolation, network/credential hardening, monitoring and independent penetration review are mandatory before any real patient data. This design is an internal foundation, **not** a complete PHI authorization guarantee.

## Current functional limits

1. Staff registration HTTP endpoints remain denied by `AuthSecurityConfiguration`.
2. `PATIENT_REGISTRATION` is seeded `implemented=FALSE`, `released=FALSE`, without entitlement. Unit/integration tests enable it only in disposable synthetic fixtures.
3. No patient INSERT, MRN creation or write audit yet: these are B3 deliverables requiring a separately reviewed additional forward-only migration and security checks.
4. V8 stores plaintext `patient_contact.contact_value` and B2 grants NO patient-contact access; encryption/blind index/retention/key lifecycle still need a separate accepted design.
5. New audit stores actor membership and successful registration-ID **reads** (no PII). It does not solve broader write-actor attribution or security event monitoring of rejected/anonymous HTTP requests.
6. Existing broad authentication SQL privileges, MFA/recovery, abuse throttling and administrative provisioning remain security blockers.
7. No patient/guardian self-service, duplicates, clinical integrations, production backup/restore or independent security review is accepted.
8. No patient data, persistent developer V8 migration, Windows Apply, customer release, deployment, merge or full Phase 01 closure is authorized by this implementation.

## CI and acceptance evidence

- Fresh dev-profile disposable CI workflow: [run 42152043183](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/42152043183). PASS reported for V9 migration, four-role startup and privilege boundaries.
- PR quality workflow: [run 42152043201](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/actions/runs/42152043201); final result must be verified separately. **Do not claim pass until completed.**
- Tests must use nonowner patient JDBC for authorized registration reads, not only owner fixtures. Assert direct role negative reads and writes, missing/wrong member, pooled connection reset, immutable audit and app/auth SQL isolation.
- Review against change set on [draft PR #30](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/30); record final run links/SHA and independent reviewer decision.

## Persistent Windows V8 upgrade: HARD STOP (separate operation)

**DO NOT run V9 or start the new V9 branch against the original named development database yet.** A persistent V8 volume lacks the new patient role because initdb does not rerun. Do not attempt an ad-hoc SQL role change, `down -v`, Flyway `repair`, `clean`, V8 Apply rerun, or automatic startup migration.

Before any separately authorized persistent upgrade:
1. Confirm the target V8 database, Flyway history, table counts, current roles, available disk/storage and all uncommitted local work read-only.
2. Review V9 security model, SQL privilege diff and archive the CI evidence with exact SHA.
3. Create/verify an offline backup and SHA-256, restore to an isolated **disposable** container and check record preservation.
4. Rehearse *separate* patient role provisioning with a unique strong secret, then run V9 on the isolated restored copy and validate migrations, RLS, audit, app/auth privilege non-grants and existing-volume migration behavior.
5. Review reconciliation evidence and obtain explicit owner approval before modifying the original V8 database. Original backup must remain retained. Prefer a dedicated reviewed script over the old V8 Apply helper with its known manifest bug.
6. After authorized migration, rerun Windows backend/frontend and all security acceptance using **synthetic** data only, collect sanitized evidence, and seek separate formal closure review.

## Gate summary

| Gate | Current evidence/status |
|---|---|
| V9 authored with disabled module and least-privilege policy | Committed to draft |
| Separate patient pool and verified transaction context | Implemented |
| Successful-registration-read actor audit | Implemented, tests required |
| Fresh isolated dev V9 startup and role check | PASS reported by GitHub Actions |
| Full PR Quality CI / Testcontainers | Refer to run 42152043201 for latest completed result |
| Existing Windows V8 backup/rehearsal/migration | NOT RUN / NOT AUTHORIZED |
| Independent security review | PENDING |
| P01-T07-B2 owner closure, PR merge | PENDING / NOT AUTHORIZED |
| Patient creation, real-patient use, deployment and Phase 01 closure | BLOCKED |
