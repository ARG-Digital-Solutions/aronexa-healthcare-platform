# PILOT-08 RC02B — Nonowner patient MRN/name search, test-only

**Milestone:** Integrated Reception Pilot Candidate [#135](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/135).  
**Parent:** RC02A Draft PR #137, owner-reported technical PASS at exact SHA `462f2b94bd18b8b7e6dd604b561a35d0d59b6c5e`.  
**Current classification:** INTERNAL_FOUNDATION / NOT READY FOR CUSTOMER USE.  
**New source QA:** NOT RUN at current RC02B SHA.

## Actual customer-usable problem being addressed

Reception should find a previously registered patient by a familiar MRN or a small name-prefix search **even if that patient has no appointment in today's queue**, then open the exact patient registration without copying UUIDs. This is a standalone test-only search increment; it is NOT a live clinic patient directory, a medical record portal, a new patient registration procedure or an identity merge.

## Production data protection is deliberately unchanged

Flyway V8 created `patient`, `patient_identifier`, `patient_registration` and clinical identity audit tables. V9 intentionally denied `aronexa_patient_runtime` direct `SELECT` on patient names, MRNs, contact information, DOB, relationships and identity audit. **This branch does not change V1–V9, create production V10/V11, provision real-role grants, or touch the original Windows database (hold #31).**

Instead, `backend/src/test/resources/db/test/pilot08-rc02b-patient-search.sql` is injected by disposable Testcontainers PostgreSQL 17 acceptance only. It creates:

- A **review-only prototype** `SECURITY DEFINER` function `pilot08_find_patient_registration(branch,mode,term)` where the existing patient SQL role receives only `EXECUTE`, not direct patient/identifier table access.
- Explicit tenant, enabled staff session membership, assigned branch, both `PATIENT_CANDIDATE_SEARCH` and `PATIENT_REGISTRATION_READ`, enabled patient-registration module state and exact branch predicates *inside the SQL function*, regardless of the Java handler.
- A small output of at most ten *registered*, active patients (tenant, branch, registration UUID, patient UUID, display name and MRN). Exact uppercase MRN and safe 3–60-character name-prefix matching only. No DOB, contact, notes, diagnosis, prescriptions or unrelated branches.
- A separate, append-only audit record of each permitted search containing tenant, staff membership, branch, mode and result count, **not the raw name/MRN query**. Zero-result searches are audited. SQL role is denied SELECT/UPDATE/DELETE on that audit.
- A constrained function search path and explicit revocation from PUBLIC. This definer is **not** a production security-reviewed substitute for patient RLS and must not be installed on a clinic DB. It requires independent function-owner, privilege-escalation, malicious-GUC, row-security, concurrency, enumeration, rate-limit and audit-retention review before production adoption.

MRN and name-prefix search criteria are sent in a small authenticated **POST JSON body**, never in a URL query string, to avoid accidental exposure in browser history or request-line access logs. It is semantically read-only despite the HTTP method. Request bodies, logs, caches and audit retention still need separate privacy review.

The source-owned Java `PatientCandidateSearchService` and `PatientCandidateSearchController` are present in `src/main` but gated independently via Spring Security **and** `@ConditionalOnProperty(name="aronexa.patient.search.reviewed-activation",havingValue="true",matchIfMissing=false)`. No production config enables them; production schema lacks the function. When explicitly activated only for synthetic Testcontainers, Java also binds the trusted current tenant+membership in a patient-role JDBC transaction, rechecks both live permissions, the patient module, assigned branch and every returned tenant/branch. Search has no write or identity-linking endpoint.

## Reception UX

The isolated connected Reception test page shows **Find patient** for staff holding both patient-search and registration-read permissions. Staff can use Exact MRN or Beginning of patient name, see a maximum of ten minimal identity candidates, and select one. Selection is **not permission to edit, prescribe or book**: the page separately calls the existing production-source registration GET through dedicated nonowner PostgreSQL RLS, checks tenant/branch/patient/registration identity continuity and only then enters `REFERENCE_FOUND`. No patient search state or bearer is written to browser local/session storage; sign-out clears results. Error responses and network faults never return fallback/demo identities.

Name results are **synthetic test-only** and are not real-patient-consent authorization. Future patient privacy UX must review masking, repeated/common names, guardianship, consent, ABHA practices and duplicate-reconciliation clinical risk.

## Local real tests and handoff

- `PatientCandidateSearchHttpAcceptanceTests`: a separate, automatically disposed PG17 database (never the original clinic DB). Real authenticated loopback HTTP validates exact MRN/name-prefix lookups, 10-result bound, append-only audit (including empty results), wrong tenant/branch, disabled module, missing either permission, password-change required, revoked bearer/branch, malformed wildcard/injection strings and that the **nonowner patient SQL login never gains direct SELECT** on patient/identifier/contact/audit tables.
- `PatientRegistrationReadHttpAcceptanceTests#pilot08Rc02bSearchRemainsDisabledInNormalRuntime`: production/default source is denied with HTTP 403 even when a registration-read-authorized staff member logs in, and it cannot leak identity.
- `patient-candidate-search-client.test.mjs`: malformed search, spoofed tenant/branch/registration/patient IDs, duplicate rows, oversized set, non-200/network failure and token handling.
- Updated Pilot06 **real Chromium** test fixture adds a completely fictional existing branch registration with MRN and **NO appointment**. The browser searches by MRN in Reception, reads the returned name, clicks the result to force a *second* server-authenticated registration GET, then proceeds to the previous queue→doctor-selection→check-in→Doctor/Rx→Billing flow. All records are contained in disposable PG17.
- The full existing PILOT-08 RC02A parent runner is executed again at **exactly the new source SHA**, including production-source restricted PG17 booking security, Chromium, and two distinct Java JVMs recovering the same fictional registration, check-in and daily queue.

The guarded local Windows runner is:

```powershell
powershell.exe -NoProfile -File .\scripts\pilot\Test-AroNexa-Pilot08-RC02B-PatientSearch.ps1 -ExpectedCommit <EXACT_RC02B_DRAFT_PR_HEAD> -QaCentralPath <EXISTING_LOCAL_QA_CENTRAL>
if ($LASTEXITCODE -ne 0) { throw 'RC02B acceptance failed' }
```

Expected only after all focused tests, frontend tests/build, browser and two-process regressions pass:

`PILOT08 RC02B MRN NAME SEARCH + DOCTOR PICKER + RESTART QA PASSED`

**No new SHA PASS is claimed until the owner actually runs it.** Attach only synthetic/non-sensitive logs, not bearer values, private staff credentials or real patient contact information.

## Still open in parent milestone #135

- RC03: one **browser-created** fictional new patient → duplicate review → selection of actual server doctor → booking/check-in → daily queue → *fresh JVM* re-read. Existing separate-JVM registration proof uses SQL-seeded registration, not browser registration.
- Patient search production approval: privacy/consent and staff identity-role scope, distributed rate limiting against enumeration, reviewed least-privilege function ownership and production forward migration, SQL attack/concurrency proof, retention policy, usable duplicate resolution. No production activation yet.
- Independent security and race/revocation review #45/#123/#125; original Windows V8/V9 database hold #31; supervised Windows installer, offline license, encryption-backed nonempty backup/restore to another device, clinical/accessibility human UAT and explicit owner release GO.

**No automatic merge, retarget, original DB migration, customer installation, patient information, real prescription or commercial release.**

## 16-field acceptance handoff

1. Phase: PILOT-08 RC02B existing-patient search.
2. Parent: accepted owner-reported RC02A SHA above, source Draft/unmerged.
3. User story: MRN/name search to open an authorized existing registration without appointment or UUID copying.
4. DB: test-only privileged function on disposable PG17, EXECUTE-only runtime, append-only metadata audit.
5. API: production-source but *default-off* POST `POST /api/v1/branches/{branchId}/patient-search` with JSON body `{"mode":"MRN|NAME_PREFIX","term":"..."}`.
6. Security: verified bearer, two permissions, branch+tenant, patient module, Java & SQL checks, no direct PII table grants.
7. Frontend: synthetic-only Reception patient search, <=10 hits, fresh registration read before navigation.
8. Positive QA: HTTP MRN/name, zero-result audit and unbooked search, Chromium verification; NOT RUN new SHA.
9. Negative QA: mismatched tenant/branch, stale token/role/module, wildcard, malformed, denied SQL table access; NOT RUN new SHA.
10. Regression: full RC02A→RC01→PROD-INT-02 same source acceptance; NOT RUN new SHA.
11. Accessibility: browser keyboard/NVDA independent manual checks pending.
12. Deployment: no real patient use or activation, no production role/function provisioning.
13. Persistence: synthetic SQL-backed search and previous distinct JVM regression; not browser-created registration cross-JVM yet.
14. Financial/clinical: no invoice finalization or prescription issuance.
15. Classification: INTERNAL_FOUNDATION / NOT_READY, commercial NO-GO.
16. Next milestone: RC03 full browser registration→booking/check-in→recovery, subject to independent reviews and holds.
