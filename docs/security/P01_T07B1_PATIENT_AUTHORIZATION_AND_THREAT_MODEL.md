# P01-T07-B1 — Patient registration authorization and threat model

**Status:** DESIGN PROPOSAL FOR SECURITY/OWNER REVIEW; not an implemented control or approved migration.
**Date:** 2026-11-06 IST. **Scope:** synthetic development only; P01-T07-B1 design.
**Tracking:** issue #29 / draft PR #30. Governance deviation: issue #28 (PR #27 merged contrary to recorded no-merge instruction).
**Release status:** No patient API enabled; no database privileges added; no patient, guardian, customer or production access authorized.

## 1. Inspected source of truth

- `AuthPrincipal` contains server-verified `userId`, `tenantId`, `membershipId`, `roles`, `permissions`, `mustChangePassword`.
- `TenantAuthorization` requires exact permission and trusted authentication; no platform-admin override.
- `TenantSqlScope.bindVerifiedTenant()` binds transaction-local tenant only through the verified app principal on the **application datasource**, not a second datasource.
- `OrganizationAccessService` uses `ORG_READ` and `org_membership_branch_scope` with exact membership/branch join. It has no public HTTP route.
- `AuthSecurityConfiguration` denies every unknown HTTP route, including all future patient routes.
- `ModuleAccessService.requireEnabled(code)` requires implemented+released+entitled+activated (+clinical authorization if required). V7 has **no patient-registration module catalog entry**; OPD entitlement must NOT be borrowed to enable all patient onboarding.
- V8 has `patient`, `patient_identifier`, `patient_contact`, `patient_relationship`, `patient_registration`, `patient_identity_audit`. Five data tables have forced tenant RLS for `aronexa_app_runtime`; neither runtime role has table privileges. The identity audit currently lacks an actor field and is not sufficient for actor-attributed operational audit.
- Authentication datasource `aronexa_auth_runtime` has known broad `app_user`/session privileges; this is a production blocker. V8 `patient_contact.contact_value` is plaintext; there is no approved patient-contact encryption/search-index scheme.

## 2. Protected objects and trust boundaries

- **Identity:** shared organization/tenant patient UUID; one tenant MRN, multiple branch registration records.
- **Registration:** tenant+branch registration with audited responsible staff or a separately verified self-service identity; branch must be database-resolved.
- **Contact:** may be shared among relatives; contact ownership or OTP receipt NEVER establishes legal guardian/clinical-record authority.
- **Patient records:** tenant isolation is necessary but not enough for authorization; a staff user needs exact permission AND assigned branch AND object linkage/policy. Medical/clinical record access is outside B1.
- **No client-selected tenant:** reject/ignore any tenantId parameter/header; use validated `AuthPrincipal` only.
- **No inferred staff role:** role names label assignments; an exact grant is required and an admin/doctor label is not a bypass.

## 3. Proposed permission vocabulary (NOT currently migrated/granted)

| Permission | Purpose | Additional object constraint |
|---|---|---|
| `PATIENT_REGISTER` | New receptionist-assisted registration | Explicit assigned branch; allowed tenant; module active |
| `PATIENT_CANDIDATE_SEARCH` | Minimal candidate discovery | Same tenant and authorized search/branch policy |
| `PATIENT_REGISTRATION_READ` | Read a branch registration | Existing registration at assigned branch |
| `PATIENT_LINK_EXISTING` | Add a branch registration for an existing patient | Separately authorized patient-object linkage; cross-branch identity must not be inferred from a UUID |
| `PATIENT_CONTACT_VERIFY` | Staff-assisted verification orchestration | Explicit object access and verified evidence |
| `PATIENT_GUARDIAN_REVIEW` | Approve guardianship after proof | Designated specially privileged workflow, never implied by contact |

Propose a new `PATIENT_REGISTRATION` module registry entry (requires a reviewed, **new forward-only** migration). Until that entry is implemented, released, entitled and activated **in a synthetic test tenant only**, deny all registration HTTP routes. A phase registry flag must never substitute for permission or object access.

### Access matrix — default deny

| Actor | Search candidate | Register new | Link prior patient | Read registration | Verify contact / guardian |
|---|---|---|---|---|---|
| Receptionist | Only `PATIENT_CANDIDATE_SEARCH` + assigned branch | Only `PATIENT_REGISTER` + assigned branch | Only with `PATIENT_LINK_EXISTING` + object policy | Only `PATIENT_REGISTRATION_READ` + record's assigned branch | Not automatic; separate permission/process |
| Doctor/nurse | **Denied by default** pending clinician assignment model | Denied by default | Denied | Denied by default | Denied |
| Clinic/hospital admin | **No role-name bypass**; same permission/object rules | Same | Same | Same | Requires separate reviewer role/permission |
| Platform/super admin | **Denied patient content by default** | Denied | Denied | Denied | Denied |
| Unauthenticated visitor | Denied | Denied | Denied | Denied | Denied |
| Patient (self-service later) | No staff search | Separate pending intake only, NEVER staff API | Denied | Own validated identity only after dedicated verified subject binding | Own contact, not guardian authority |
| Guardian (later) | No staff search | Controlled pending intake | Denied until proof | Only specifically authorized dependent and purpose | Proof/revocation tracked independently |

All staff routes require a valid unrevoked staff bearer token, non-forced-password state, `ORG_READ` where branch discovery relies on `OrganizationAccessService`, permission above, branch assignment, active module gate and tenant/record ownership. For unknown vs forbidden record identifiers return non-enumerating status/body; do not reveal existence through timing, counts or error detail.

### Existing-patient link policy

Link to a new branch only after authorization to **both** the destination branch and the existing patient object through a reviewed lookup/disclosure policy. A client-supplied patient UUID is not proof of authorization; never auto-link from a shared phone or fuzzy match. If the patient belongs to a branch the actor cannot access, escalate to independently authorized workflow without revealing identity.

## 4. Proposed SQL and RLS architecture — design, not migration

- **Preferred:** a dedicated non-owner, non-BYPASSRLS `aronexa_patient_runtime` database role/pool with only necessary privileges on patient tables or narrowly scoped approved database operations. No patient privileges to `aronexa_auth_runtime`, no migration-owner credentials in runtime, no broad blanket table grants to the existing `aronexa_app_runtime`.
- V8 RLS policies are targeted only to `aronexa_app_runtime`. A new role requires reviewed **additive V9+ policies**, explicit grants and a dedicated transaction-local verified-tenant binder on the **same connection as patient queries**. Reusing `TenantSqlScope` from another datasource would be incorrect.
- Design patient writes as a transactional service that validates server principal, permission, module state and membership/branch before using the patient connection. Prevent cross-tenant linking with V8 composite FKs. Enforce strong branch/object checks for every read and write even though V8 RLS guards only tenant boundaries.
- Do **not** claim that a client-settable SQL session variable proves actor identity or branch authority. If using `SET LOCAL` for an actor, it is application context and can be spoofed by a compromised SQL-capable runtime. Need threat review of direct SQL privileges, stored routines, views and audit trust boundary before granting anything.
- Review minimal grant choices for each table (e.g., new-patient INSERT, authorized status/contact operations, SELECT for scoped candidate search); do not grant DELETE and do not allow UPDATE to identifiers or registrations. Patient audit remains append-only, no runtime mutation privileges.
- Any new grants, module/permission catalog entries, encrypted contact scheme, actor attribution and idempotency-request fingerprint must be performed in forward-only V9+ migrations **after independent security review and approved migration rehearsal**. Do not edit V1–V8 or run the archived V8 Apply script.
- Keep all feature flags and module states disabled by default. No backdoor using migration-owner JDBC or `SECURITY DEFINER` functions callable without strict authorization.

## 5. Threat register / required mitigations

| Threat | Impact | Required mitigation before exposure |
|---|---|---|
| Forged tenant in route/body/header | Cross-tenant PHI exposure | Verified principal only + enforced tenant RLS + composite FKs + negative tests |
| Same-tenant different-branch IDOR | Unauthorized access or link | Exact branch membership and patient-object check on each call |
| Platform-admin privilege assumption | Broad clinical disclosure | No implicit role override; explicit grants + independent approval |
| Reused phone number | Wrong patient merged/guardian claim | Many-to-one phone support; candidate review; distinct proof of guardianship |
| Duplicate-search enumeration | Identity disclosure | Permission, rate limit, minimum query, capped masked results, non-enumerating errors |
| Replay/idempotency mismatch | Duplicate registrations | HMAC-scoped key, payload digest, atomic persisted response mapping, conflict for modified replay |
| MRN collision/concurrency | Lost or duplicated identity | CSPRNG MRN + tenant unique constraint; bounded safe retry with transaction strategy |
| Tampered actor/metadata | False audit trail | Trusted authenticated membership binding, immutable event log and DB actor provenance review |
| Unencrypted contacts / sensitive logs | Personal-data exposure | Encryption/key lifecycle and normalized blind-index design; metadata-only logs |
| Unverified guardian | Unauthorized dependent access | Verifiable proof, purpose/consent, expiry/revocation and separate access checks |
| Global auth SQL privileges | Cross-tenant account/session compromise | Independent auth datasource privilege closure; production release blocker |
| Unreviewed V9 migration/restore | Data loss | Disposable rehearsal, backup hash/restore, Flyway validation, no automatic Apply |
| Misconfigured module/catalog | Public patient endpoints | Deny-by-default HTTP + backend module gate + no default entitlement |

## 6. Assumptions and explicit open design decisions

1. **Registration module:** propose `PATIENT_REGISTRATION` separate from OPD; final code, release semantics and migration pending review.
2. **Actor audit:** V8 `patient_identity_audit` records object type/operation/time only. Propose an additive, tenant-bound immutable actor audit event (membership+patient+registration+purpose/outcome; no PII). Verify traceability and privilege boundary before shipping. Never rewrite or truncate existing audit.
3. **Contact protection:** V8 raw contact column is a privacy blocker. Choose an authenticated encryption + keyed blind index/normalization lifecycle, migration/storage strategy and key rotation after security review. Do not send real contacts to V8 before completed.
4. **Scope of registration:** receptionist-assisted first. No patient/guardian subject or OTP bypass in B2/B3.
5. **Access tests:** distinguish tenant RLS, application branch/object policy and database direct-SQL compromise; do not claim all three are equivalent.

## 7. B1 exit / B2 entry gate

- [x] Existing source inspected and security gaps documented.
- [x] Proposed role/permission+object matrix and trust boundaries specified.
- [x] New module, credential boundary and forward-only migration needs identified.
- [ ] Independent technical/security review of permission, RLS/pool design and actor audit.
- [ ] Decide module code, contact encryption and replay contract with owner/reviewer.
- [ ] Approve bounded B2 implementation, tests and any separate synthetic-only V9 migration.
- [ ] Confirm no patient grants, public HTTP routes, DB mutations or customer data introduced by B1.

**Outcome:** design authored for review; not owner-accepted as security implementation. Full Phase 01/customer/clinical readiness remains BLOCKED.
