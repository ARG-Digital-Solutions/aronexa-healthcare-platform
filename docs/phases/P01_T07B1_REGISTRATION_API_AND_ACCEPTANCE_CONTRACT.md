# P01-T07-B1 — Staff registration API, identity-matching and acceptance contract

**2026-11-06 IST | DESIGN ONLY | no APIs enabled or tests executed**
Companion: [Patient authorization and threat model](../security/P01_T07B1_PATIENT_AUTHORIZATION_AND_THREAT_MODEL.md).
Scope: staff-assisted synthetic patient registration first. The patient/guardian self-service channel is a separate B5 boundary and is NOT reachable during B2/B3/B4.

## 1. Contract version and route release gates

Proposed base namespace: `/api/v1/branches/{branchId}/patient-registrations`.

*No patient routes may be opened yet.* The currently deployed `AuthSecurityConfiguration` rejects all unknown routes. Before any matcher changes, implement exact method+route allowlist, require authenticated `AuthPrincipal`, exact permission, password state, module entitlement, verified tenant, branch assignment, target-object policy and audit. Do NOT use `anyRequest().authenticated()`, wildcard controller exemptions, or client tenant selectors. Require TLS on any eventual externally exposed deployment.

**Proposed staff APIs, not yet implemented**

| Operation | Verb and route | Permission | Result |
|---|---|---|---|
| Evaluate potential existing records | `POST /api/v1/branches/{branchId}/patient-registrations/candidates` | `PATIENT_CANDIDATE_SEARCH` | Up to a fixed small number of masked, authorized candidate descriptors + opaque decision reference |
| Create new patient and branch registration | `POST /api/v1/branches/{branchId}/patient-registrations` | `PATIENT_REGISTER` | 201 with patient UUID, opaque tenant MRN and registration UUID; no extra contact/guardian disclosures |
| Explicitly register an authorized existing patient at another assigned branch | `POST /api/v1/branches/{branchId}/patient-registrations/links` | `PATIENT_LINK_EXISTING` | 201 for new link; requires object access + reviewed candidate reference |
| Read one branch registration | `GET /api/v1/branches/{branchId}/patient-registrations/{registrationId}` | `PATIENT_REGISTRATION_READ` | 200 only when record belongs to assigned branch and verified tenant |

A successful staff bearer authentication does not imply registration authorization. No API responds with cross-tenant candidates, tenant-wide patient counts, raw contact data, or patient records not independently accessible to the actor. The base URLs and permission names are proposals requiring review before source implementation.

## 2. Proposed request/response schemas (illustrative, not live OpenAPI)

**Candidate search request** — normalized server-side after field validation:

```json
{
  "displayName": "Synthetic Person",
  "birthDatePrecision": "UNKNOWN",
  "dateOfBirth": null,
  "mobile": null
}
```

Require a meaningful combination of fields and a permitted purpose. Never permit broad empty, prefix-only or mass-export search. Deduplicate within allowed patient objects; do not leak match counts beyond a small bounded list. Compare names with normalized locale-safe methods; use DOB only when provided with compatible precision. Mobile matching may be shared by family and must not independently imply the same patient.

**Candidate response** — shape for review, no raw PII for unentitled objects:

```json
{
  "reviewRequired": true,
  "candidates": [
    {
      "candidateRef": "opaque-short-lived-reference",
      "maskedName": "S******* P*****",
      "maskedMrn": "***4821",
      "signals": ["SIMILAR_NAME", "DOB_MATCH"]
    }
  ],
  "reviewToken": "opaque-actor-branch-query-bound-token"
}
```

Candidate references MUST be server generated, short-lived, single-use where applicable and bound to actor membership, tenant, branch, search purpose and request fingerprint. Only reveal a candidate if actor has sufficient preexisting object access; others contribute at most a non-identifying review/escalation requirement, never details. Avoid deterministic identifiers, patient IDs, exact DOB or unmasked phone in candidate responses.

**New registration request** — names and dates intentionally minimal:

```json
{
  "displayName": "Synthetic Person",
  "birthDatePrecision": "UNKNOWN",
  "dateOfBirth": null,
  "contacts": [],
  "duplicateReview": {"decision": "CREATE_NEW", "reviewToken": "opaque-review-token"}
}
```

Use an `Idempotency-Key` header with high-entropy caller-generated value; never put it in logs. Require a prior candidate review or strictly defined exception for a validated no-candidate case. Server resolves tenant from principal, branch from trusted DB, source=`RECEPTION`, staff membership from principal; ignore/reject client-supplied tenant, MRN, createdAt, actor or role. `dateOfBirth` must be null exactly when `UNKNOWN`; other precision values require a valid nonfuture date. Do not store real phone numbers until encryption policy is completed.

**Registration created response:**

```json
{
  "patientId": "synthetic-uuid",
  "mrn": "PAT-OPAQUEID",
  "registrationId": "synthetic-registration-uuid",
  "branchId": "synthetic-branch-uuid",
  "status": "REGISTERED"
}
```

Responses marked `synthetic-*` above are illustrative, not valid UUID strings or production payloads.

## 3. Recommended MRN and concurrency behavior

- Format proposal: `PAT-` + 12 characters of cryptographically secure Crockford Base32, uppercase. Human-readable, nonsequential, never derived from PII or tenant/branch code. This fits V8 identifier normalization and 48-character limit. Final length and UX still under review.
- Unique `(tenant_id, identifier_type, identifier_value)` already enforced in V8. Generate in the **same** transaction as patient and branch registration; no visible patient without an MRN.
- Detect rare unique-index collisions and retry with safe transaction/savepoint semantics. Never continue a PostgreSQL aborted transaction after a duplicate-key exception. Bound retries, return safe failure if exhausted; no orphan patient or MRN.
- One shared identity and MRN across multiple facility branches inside one tenant; explicit branch registration entries only when authorized.

## 4. Idempotency and duplicate review

- HMAC-SHA256 with server-held secret for the canonical composite key (tenant + actor or principal type + operation + branch + validated external idempotency key). V8 `idempotency_sha256` can hold the digest, but alone does **not** store request fingerprint, response mapping, or in-progress states.
- Proposed additive V9+ idempotency ledger: unique scoped key digest, separate canonical payload fingerprint, state, result patient/registration IDs, expiry/retention fields, createdBy membership, tenant, operation and branch. Do not store request body.
- Same key + same payload after completion returns the same safe registration result (200 replay); different payload under the same key returns generic 409 idempotency conflict. In-progress concurrent identical request must wait/resolve safely or return bounded 409 retry state; never create duplicate patients.
- Candidate review is not a hard identity assertion. Do NOT merge patient masters automatically even if all names, DOB and phones match; a staff user must choose a permitted existing patient or provide a recorded reason for a distinct identity. Cross-branch/tenant unauthorized candidates remain undisclosed.
- Search signals are suggestive only, not medical or legal identity verification.

## 5. Error behavior and data minimization

| Condition | Proposed status | Notes |
|---|---|---|
| Missing/expired bearer | 401 | Cache-Control: no-store |
| Authenticated actor lacking route permission/module | 403 | No patient existence hints |
| Unknown or inaccessible registration/patient/branch object | 404 | Uniform envelope/timing as feasible |
| Malformed/invalid input | 400 or 422 | Field errors; no raw SQL/PII echo |
| Duplicate requiring explicit review, replay mismatch | 409 | Distinct machine-readable error codes, not patient details |
| Resource contention or rate-limit exceedance | 429 / bounded retry response | Retry-After if safe; no enumeration |
| Unexpected DB failure | 500 generic | Correlation ID; redact PII/tokens/SQL details |

Sensitive JSON, contacts, candidate tokens, idempotency keys, OTPs, full query strings and patient ID mappings must not be logged. Cache-Control: no-store; prohibit client caches for private registration responses. Use CSP/referrer safeguards on frontend, and suppress sensitive analytics/telemetry.

## 6. Self-service/guardian future boundary (B5 ONLY)

- New patient-subject proof/identity distinct from `AuthPrincipal` staff membership; no public self-registration HTTP permission inherited from staff roles.
- Pending-intake records may exist before verified binding but cannot grant access to a patient record. Every identity-to-patient link must be verified and reviewable.
- OTP proof of contact ownership is NOT guardianship. Guardian needs an independent lawful verification, consent/purpose scope, expiry and revocation plus dependent-object access check. Shared family phone is a routine valid input.
- Proposed OTP policy for security review: 6+ random digits or stronger provider credential; short expiry (e.g., five minutes), single-use, bounded attempts and resend quotas, abuse/rate controls, hashing at rest, no OTP in logs. Final values and provider dependencies must be approved before implementation.
- No public patient portal, appointment, messaging, clinical document access or guardian record reads under this B1 contract.

## 7. Integration and security acceptance test inventory (all NOT RUN)

Tests must use PostgreSQL Testcontainers with real `aronexa_app_runtime`, proposed patient runtime, and `aronexa_auth_runtime` **separate from migration-owner**; synthetic records only.

| ID | Test case | Expected behavior |
|---|---|---|
| B1-A01 | Anonymous/invalid/revoked/forced-password staff request | 401/403, no patient SQL |
| B1-A02 | Permission missing despite authorized branch | 403 |
| B1-A03 | Super/platform admin role without explicit permission | Denied |
| B1-A04 | Tenant header/body/route forgery | Verified tenant used; forged tenant ignored/rejected |
| B1-A05 | Staff of same tenant but other membership | Cannot borrow another membership's branch scope |
| B1-A06 | Assigned vs unassigned branch | Allow/deny by stored branch assignment |
| B1-A07 | Known vs unknown vs forbidden patient ID | Non-enumerating responses; no object leakage |
| B1-A08 | Disabled/missing patient module/tenant entitlement | No registration accessible |
| B1-A09 | Auth runtime patient-table SELECT/INSERT | Denied by SQL privileges |
| B1-A10 | Missing/wrong transaction-local tenant on patient datasource | Denied/no rows |
| B1-A11 | Pool reuse after transaction tenant A then tenant B | No cross-tenant carryover |
| B1-A12 | Cross-tenant composite FK linkage | SQL rejects |
| B1-A13 | Same patient linked twice across valid branches | One patient master + one MRN; bounded link behavior |
| B1-A14 | Shared family mobile, similar name, different patient | No forced merge or guardian authorization |
| B1-A15 | 2–100 concurrent synthetic registrations | No MRN collision/orphans, no failed transaction reuse |
| B1-A16 | Simultaneous identical idempotency key+payload | One registration, stable replay response |
| B1-A17 | Same idempotency key, changed payload | 409; unchanged record |
| B1-A18 | Candidate search with blank/prefix-only query | Reject/limit; no bulk enumeration |
| B1-A19 | Candidate of forbidden branch | No patient details in result or error |
| B1-A20 | Contact field and secret redaction | No PII/keys in logs, errors, audit event |
| B1-A21 | Actor attribution for each mutation | Verified membership, correct tenant/branch in immutable event |
| B1-A22 | Attempts to change patient ID/tenant/MRN or delete registration | Denied; V8 immutability preserved |
| B1-A23 | Branch scope revoked between candidate search and create | Creation denied; no stale review-token bypass |
| B1-A24 | Staff bearer vs patient/guardian credential mix-up | No crossing subject types or permissions |
| B1-A25 | Guardian with same phone, no proof | Cannot view or modify dependent record |
| B1-A26 | Unauthorized self-service/OTP routes before B5 | 404/403 deny-by-default |
| B1-A27 | Frontend invalid input and duplicate decision | Accessible validation, never silently auto-merge |
| B1-A28 | Fresh CI and Windows synthetic acceptance | Explicit evidence links + actual results before accepting |
| B1-A29 | Disposable migration rehearsal, backup hash and recovery | Pass before applying any V9+ to persistent DB |
| B1-A30 | Post-change regression to auth, organization, module registry | Existing tests remain green |

**No assertion of PASS:** the table is a future implementation/acceptance contract, not executed evidence.

## 8. Exit criteria and blocking dependency register

**B1 deliverable:** reviewable authorization, API and security-test design only. Not a working feature.

B2/B3 engineering may write code after threat and datasource design review, but patient access must remain blocked until a reviewed migration, least privilege/RLS integration and negative tests pass. Additional forward-only V9+ migration will be needed for registration module/permissions, identity audit actor, idempotency fingerprint/result and contact encryption/search strategy. Exact schema must be reviewed rather than guessed from this document.

**Open blockers:** broad auth-runtime SQL privileges; tenant+branch+object policy enforcement; patient-contact encryption/retention/key lifecycle; audit actor trust; provisioning and entitlement; self-service/guardian proof; production security and compliance; customer UAT; operational backup/restore and rollback.

**No authorization** to merge PR #30, change original developer V8 DB, rerun Apply, deploy, close Phase 01 or use real patient records.
