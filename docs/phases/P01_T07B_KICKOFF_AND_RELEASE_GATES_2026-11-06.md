# P01-T07-B — Authorized kickoff and implementation gates

Date: 2026-11-06 IST
Owner instruction: "P01-T07-B, but it has not been started or authorized. Full Phase 01 closure, customer deployment and real-patient use - proceed"

**Decision:** P01-T07-B engineering work may begin. This document does NOT certify full Phase 01 closure or authorize use of real patient data. Customer release remains BLOCKED pending release-specific acceptance and owner signoff.

Tracking: [P01-T07-B issue #29](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/29). Separate process deviation: [issue #28](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/28) (PR #27 unexpectedly merged despite owner's explicit hold).

## Baseline reviewed
- P01-T07-A owner-accepted INTERNAL_FOUNDATION: schema-only Flyway V8 and owner-reported 71 successful tests with Windows read-only verification.
- Six new V8 tables: patient, patient_identifier, patient_contact, patient_relationship, patient_registration, patient_identity_audit. Existing migrations V1–V8 immutable.
- No MRN creation service, patient API, registration UI, duplicate matching, OTP, self-service or guardian verification.
- No runtime application or auth role access to V8 patient tables intentionally granted.
- Known blockers include broad auth SQL privileges, MFA/recovery, throttling, consent, actor audit and branch/object access checks.
- Archived V8 acceptance script contains historical Apply manifest-property defect. Do not rerun Apply.

## Required incremental sequence
### B1 — design and threat analysis
- Tenant and branch object permission matrix for receptionist, permitted clinical users, patient, guardian and admin, with explicit default-deny rules.
- Separate staff registration from self-service verification; do not treat shared phone as unique or proof of guardianship.
- Define patient MRN allocation (organization-wide), bounded idempotency replay, safe search and duplicate match candidate scoring.
- Audit actor, privacy-preserving duplicate suggestions and data minimization.
- Approval gate: design and security review before introducing patient runtime database grants.

### B2 — staff authorization and persistence boundary
- Least-privilege SQL grants ONLY for the patient app workflow needed. No privileges to auth role, no generic unrestricted service bypass.
- Authenticate trusted server-side principal and verified transaction tenant, branch assignment, module entitlement and patient object authorization.
- Actor-attributed metadata-only audit and protected contact handling.
- Negative tests: forged tenant/branch/patient identifiers, unauthorized roles, feature-off, concurrent requests, SQL/RLS bypass attempts.

### B3 — receptionist registration core
- Atomic patient + MRN + branch registration; idempotency and concurrency.
- Validation for name, DOB precision, contacts and multi-branch patient linkage.
- Avoid duplicate patient creation by reviewing permitted candidates; no automatic merge.
- Synthetic integration tests for rollback, retries, multi-branch linking and shared phone numbers.

### B4 — duplicate candidates
- Explainable matching rules, capped response, least information disclosure and manual resolution.
- No cross-tenant lookup, no bulk enumeration or auto merge. Log decision metadata.

### B5 — patient/guardian self-service
- Verify contact ownership with expiring, one-time OTP (rate limiting, anti-enumeration, no OTP in logs).
- Consent and guardian authorization as separate verifiable states. Prevent unauthorized access from shared numbers.
- Keep disabled until security and privacy gates pass.

### B6 — receptionist UI and acceptance
- Professional medical navy/blue, efficient low-click and role-specific reception workflow.
- Accessible form validation, patient candidate review and clear verification status.
- CI backend Testcontainers, frontend build, restricted role grants, Windows synthetic acceptance, backup/recovery, evidence inventory and manual owner gate.

## Prohibited without separate release evidence and approval
- Real patient information, customer rollout, public self-service, clinical workflows, SaaS production or Windows customer installation.
- Any Flyway clean/repair, downgrade, old V1–V8 edits, original V8 Apply rerun or touching an existing persistent DB.
- Automatic PR merges and full Phase 01 closure.

## Release readiness gates, not yet passed
1. P01-T07-B implementation and test evidence accepted
2. Auth SQL isolation and RBAC/branch/object/tenant authorization independently validated
3. MFA/recovery, anti-abuse, encryption, privacy and consent/guardian rules reviewed
4. Clinical safety/accuracy and statutory obligations assessed for the intended deployment
5. Production hardening, monitoring, backup **restore drill**, incident/rollback processes verified
6. Dedicated customer UAT with synthetic data and signed written production go/no-go

**Current classification:** DEVELOPMENT KICKOFF / NOT CUSTOMER READY / NO REAL PATIENT DATA.
