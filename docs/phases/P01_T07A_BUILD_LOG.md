# P01-T07-A — Shared Patient Identity and Registration Schema Foundation

Status: IMPLEMENTED ON FEATURE BRANCH; ACCEPTANCE NOT YET VERIFIED.
Authorization: owner instruction "proceed" after explicit P01-T07-A scope explanation, 2026-11-05 IST.
Branch: feature/p01-t07a-patient-identity-schema.
Release classification: INTERNAL_FOUNDATION (candidate). No real patient data, customer release, clinical access, merge, deployment or next subtask is authorized.

## 1. Phase and requirement scope
P01-T07-A only: database model for tenant-level patient identity and facility/branch registrations.
Traceability: PAT-01, CORE-01, CORE-03, SEC-01, SEC-03; UX-01/02 deferred to later UI work.
All prior V1-V7 migrations and their checksums remain unchanged. No CareNexus source used.

## 2. Repository audit and reused foundations
Verified main at 51a4208f12988d19bae0f3eb663a46e0b1b6be24.
Reuse V2 tenant/facility/branch, V3 verified user memberships, V4 tenant context/RLS, V5 membership/branch assignments, V6 restricted authentication DB role, V7 fail-closed module gating.
Existing org read policy does NOT authorize patient operations.

## 3. Implemented features
Forward-only V8 tables: patient, patient_identifier, patient_contact, patient_relationship, patient_registration, patient_identity_audit.
Patient master has immutable UUID + tenant ownership, simple name/optional DOB, no specialty-specific columns.
MRN is a tenant-unique identifier. Patient registration references branch and derives facility from branch, allowing many registrations under one patient without per-specialty copies.
Contacts allow shared family phone numbers; verified contact is not authorization to a clinical record.
Relations support related registered patient or named unregistered person, but do not imply guardian consent/authorization.
Registration source and optional hashed idempotency key are stored; creation service, request replay responses and transactional generation are deferred.
Audits are append-only metadata-only rows; detailed actor attribution must be developed and tested before runtime writes.

## 4. Database/API changes
V8 only. No controllers, authentication changes, staff provisioning or runtime write/read endpoints.
Both aronexa_app_runtime and aronexa_auth_runtime intentionally receive NO privileges on patient tables. Existing runtime must remain functional because no patient query is made by the present application. RLS is enabled/forced on the five patient-owned non-audit tables and uses existing transaction-local tenant context.
Audit table has SELECT-only tenant RLS policy prepared but still no runtime SELECT grant; audit trigger writes with migration-owner definer privileges.
No ABHA/Aadhaar requirement; no hard-coded patient demographic fixture.

## 5. UI/UX and stylus
No UI or stylus changes. Reception/verification/duplicate UI belongs to subsequent independently authorized subtask(s).

## 6. Security and clinical impact
Composite (tenant_id, patient_id)/(tenant_id, branch_id) foreign keys prevent cross-tenant linkage.
Restrict patient access by default. Database grants must NOT be added until service layer can enforce explicit role/permission, branch, object and patient verification policies.
No partial patient registration UI should accept real patient data.
Important limitations:
- V8 is a schema scaffold, not registration service, guardian proof or patient verification.
- Registry does not yet mark patient functionality as released/entitled. No patient UI/API exists.
- Existing global auth-role table grants, missing MFA/recovery/distributed throttling and other Phase 01 security blockers remain.
- Further review is needed on identifier normalization, MRN allocation/concurrency, contact encryption, DOB precision validation, immutable-field repair procedure, data retention, consent/guardian validation, actor-attributed audit and deletion/export.
- No public patient APIs until a reviewed onboarding authorization model exists.
- Runtime grants are intentionally absent, so this task is not a complete deployable registration increment.

## 7. Tests and actual results
Source changes include PatientIdentitySchemaTests (real PostgreSQL Testcontainers) for one identity used by multiple branches, tenant-unique MRN, shared phone, composite cross-tenant FK denial, idempotency-key uniqueness, identity/audit immutability, and table permissions/RLS.
Updated AronexaApplicationTests to expect latest V8 and verify six new empty tables.
Updated fresh-dev CI script to expect V8 and ensure both runtime identities have NO SELECT privileges on all new patient tables.
**Local commands NOT RUN by assistant.** No CI result claimed as PASS until a GitHub runner provides evidence.

Required CI:
- `mvn -B -f backend/pom.xml verify`
- `cd frontend && npm ci --no-audit --no-fund && npm run build`
- `bash scripts/ci/dev_runtime_acceptance.sh` (GitHub Actions ephemeral CI only)
- PR documentation, tracked secret and changed-file scope gates
- `git diff --check`

## 8. Regression
Existing auth/organization/module/architecture tests should remain green. Await PR CI evidence; any failure prevents merge.

## 9. Compliance
Development-only synthetic data. Privacy, clinical governance and Indian legal applicability need separate review; no assertion of compliance or production-safe onboarding.

## 10. Supported modes
Source is shared backend schema, not an independently deployed SaaS/Windows-offline/dedicated/macOS/Android feature. No deployment-mode acceptance executed.

## 11. Release artifacts
V8 migration, PostgreSQL test suite, aligned migration assertions/CI smoke, this scoped build log and review PR. No installer/release generated.

## 12. Known issues
- No patient API or authorization; no frontend registration; no OTP; no duplicate detection implementation; no consent; no MRN allocator.
- V8 migration has not yet been independently validated on an upgraded persistent V7 developer database.
- Additive test schema migration and recovery require evidence. Existing production/clinical blockers remain.
- App-level audit actor and granular access policy are prerequisites of operational workflows.

## 13. Backup and recovery
No commands run against existing development or customer database. Prior to any Windows persistent V7->V8 migration, follow P01-T06 safe preflight: verified backup+checksum, isolated disposable V7 restore, V8 rehearsal+validate, table counts and role/grant tests, then separately approved original migration. Never clean/repair/drop volumes or edit already-applied V1-V7. No downgrade scripts; use preserved backup and controlled recovery process after approval.

## 14. Customer acceptance
NOT RUN; NOT READY for customer pilot.

## 15. Classification
INTERNAL_FOUNDATION candidate only; acceptance blocked pending actual CI and V8 migration evidence. No production release.

## 16. Next decision
Review draft PR and executed CI. Require explicit owner approval before merge, persistent-database migration, customer access or P01-T07-B. No automatic progression.
