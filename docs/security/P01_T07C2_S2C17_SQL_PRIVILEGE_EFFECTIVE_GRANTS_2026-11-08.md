# P01-T07-C2-S2-C17 — Effective PostgreSQL Privilege Boundary Acceptance

**Date:** 2026-11-08 IST  
**Authority:** [C17 Issue #60](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/60)  
**Review:** [DRAFT PR #38](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/38)  
**Classification:** INTERNAL SYNTHETIC-ONLY SQL SECURITY CHECK; NOT INDEPENDENT SIGN-OFF.

## Goal and exposure

C16's independent-review matrix defines REV-04: verify role/schema/function privileges in the actual database. A proposed V10 grant/revoke SQL script alone is not enough to prove that PostgreSQL effective privileges match intended least-privilege behavior.

C17 adds three Testcontainers acceptance tests to **`backend/src/test/java/com/argdigital/aronexa/patient/SyntheticRegistrationIntegrationTests.java`**. They use a PostgreSQL 17 disposable synthetic database migrated from normal V1–V9 and the separately staged **test-resource-only** proposed V10, with no original Windows instance.

## C17 acceptance matrix

| Gate | Actual catalog or JDBC evidence | Status pending exact-head CI |
|---|---|---|
| C17-01 | Exactly one `public.aronexa_c2_register_patient` database function definition | PENDING |
| C17-02 | `prosecdef=true`, definer owner `aronexa_registration_writer`, owner NOLOGIN/NOSUPERUSER/NOBYPASSRLS/NOCREATEDB/NOCREATEROLE | PENDING |
| C17-03 | Function has fixed `search_path=pg_catalog,public,pg_temp` | PENDING |
| C17-04 | `EXECUTE` only effective for dedicated `aronexa_patient_runtime`, not authentication/app runtime roles or unrestricted PUBLIC ACL | PENDING |
| C17-05 | Writer and all three login runtime roles cannot CREATE objects in `public` | PENDING |
| C17-06 | Patient login cannot SELECT proof key or INSERT patients directly, or EXECUTE C7 authorization-change trigger functions | PENDING |
| C17-07 | Real patient-login JDBC SQL `CREATE TABLE` and proof-key `SELECT` attempts fail with PostgreSQL `42501`; no resulting table | PENDING |
| C17-08 | All prior 149 backend tests and frontend/SAST/SCA/npm/secret/scope/fresh-development checks pass at same SHA | PENDING |

The test asserts effective PostgreSQL catalog permissions with `pg_proc`, `pg_roles`, `has_function_privilege`, `has_table_privilege`, `has_schema_privilege` and `aclexplode`. The real SQL negative probes use only the disposable PostgreSQL login `aronexa_patient_runtime`, and roll back both attempted transactions. The tests do not need production credentials and do not print secrets.

## What the test does NOT establish

- A privileged function owner, superuser, DBA, migrator or compromised signing key is not a user runtime role. This test cannot demonstrate safety after **owner-level or dual key/database compromise** (REV-11, REV-12).
- The function's `search_path` check is not a complete audit of schema resolution or dangerous dynamic SQL; an independent PostgreSQL security engineer should inspect the **complete function body**, triggers, RLS policies, role graph, schema ownership and privileged operational tools.
- The check samples the current *synthetic V10* catalog, not a future persistent migration, original Windows V8/V9 database, cloud PostgreSQL instance or backup/restore.
- PostgreSQL table and function grants are only one security layer; application role authorization, C4–C8 revocation ordering, key custody, tenant isolation, privilege escalation through future migrations and account/session management require external review.

## Hard stops

[Issue #31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31) original Windows W3-E NO-GO / W4 Apply BLOCKED. PR #32/#36/#38 remain DRAFT/UNMERGED. Synthetic V10 test resources only; no real patient data, C3 receptionist UI integration, customer pilot, production merge or deployment. Independent reviewer [Issue #45](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/45) UNASSIGNED; no access granted.

**Overall S2 OPEN / PRODUCTION NO-GO.**
