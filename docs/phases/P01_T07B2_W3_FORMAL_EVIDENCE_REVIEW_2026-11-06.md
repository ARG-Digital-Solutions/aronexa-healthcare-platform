# P01-T07-B2-MIG — W3 Formal Evidence Review

**Reviewed:** 2026-11-06 (IST)  
**Evidence source:** GitHub issues #31, #28, #29; merged PR #30; draft PR #32; source runbook and limited B2 owner-acceptance/security review documents; operator-reported sanitized Windows command outputs.  
**Environment:** Original synthetic Windows PostgreSQL 17 `aronexa_dev` (Flyway V8), isolated disposable PostgreSQL 17 rehearsal (Flyway V9). No real-patient data.
**Reviewer recommendation:** **HOLD / NOT GO-READY**. This is a review recommendation, **NOT an owner W3 GO or NO-GO authorization**. Original W4 Apply remains BLOCKED.

## 1. Evidence classification

- **Repository-verified:** issue/PR state, runbook, source code and documented risks, GitHub workflow status.
- **Owner-reported local verification:** the original V8 and disposable V9 query outputs, backup size/hash, restored 25-table counts, W2B/W2C acceptance exit codes, ZIP outer hash and inner manifest checks.
- **Not independently inspected:** original local W0/W1/W2A/W2B/W2C execution transcripts, private PostgreSQL backup content, retained ZIP contents, precise source-vs-copy dump hash event, and live Windows DB state at this review instant.

A passing script-generated summary without original sanitized logs is not by itself independent end-to-end attestation.

## 2. Acceptance gate evaluation

| Gate | Reported result | Review assessment | Missing / limitations |
|---|---|---|---|
| W0 — original V8 preflight | `READ_ONLY_V8_PREFLIGHT_PASSED`, exit 0 | Owner-reported PASS | Independently attest sanitized preflight identity/roles/absence of patient data |
| W1 — original backup | 25 tables, 92,567 bytes, PostgreSQL backup SHA-256 `153842CCC811A64729F2DBA0743515A6C24E5A20AFB1D054AF054FECB36C3CAB` | Owner-reported PASS | Confirm original container-to-host hash correspondence, `pg_restore --list`, baseline capture timing and private retention from original reports |
| W2A — restored V8 | Exact disposable container/network, 25 baseline row counts, original history/checksums and restricted roles PASS | Owner-reported PASS | Independently inspect restore and ACL/count transcripts |
| W2B — V9 disposable migration | Flyway successfully migrated and validated 9 migrations. Read-only verifier `W2B_READ_ONLY_V9_ACCEPTANCE_PASS`, exit 0 | Owner-reported PASS | Independently inspect full migrate/validate and checks. The initial W2B scripts had SCRAM password and expected-history/audit-count verifier defects; corrected post-migration verifier was used |
| W2C — synthetic RLS/transaction | `W2C_SYNTHETIC_ISOLATION_AND_ROLLBACK_PASS`, exit 0 | Owner-reported PASS | Inspect each negative/positive marker, rollback/connection reset and postcheck transcript; database-scoped tests do not prove direct-SQL compromise resistance |
| W2D — private sanitized archive | 3 ZIP entries, 2 manifest-listed files verified, outer and manifest hashes PASS | Owner-reported integrity PASS | ZIP is summary + counts + manifest, **not** W0–W2C original transcripts |
| W3 — formal approval | No owner GO | **HOLD** | Evidence review and governance signoff missing |

**W2D ZIP SHA-256:** `9BEF04CBA2FC7E5DAF790E34521538558713B8A0B164979FE52ED7E011C9BE97`. Only the non-sensitive archive filename and hash may be referenced in GitHub; do not upload local backup/ZIP without separate review.

**Expected V9 data deltas:** +1 `module_registry` (disabled `PATIENT_REGISTRATION`), +6 ungranted `auth_permission`, +1 `flyway_schema_history`, +1 `module_configuration_audit` attributed to the registration-module INSERT trigger; 21 unchanged original table counts and one new empty `patient_registration_access_audit` table, 26 total. These were asserted by the corrected owner-executed read-only checker.

**Migration source Git blob:** `3a1e2b717576ccd84f0de058bb0a83786c517d32` for `V9__patient_registration_read_boundary.sql`.

## 3. CI and repository state checked

- PR #30 was **MERGED** on 2026-11-06 at 08:36:25 UTC, merge commit `35c583e05f3199c66f830ecabc4a38b16004f98b`. Its body and `P01_T07B2_OWNER_ACCEPTANCE_2026-11-06.md` expressly said **do not merge without a separate owner authorization**, which has not been established in the records reviewed. GitHub returned no submitted reviews for PR #30.
- PR #32 at the time of this review was **OPEN / DRAFT / UNMERGED**, head `447f119026f83c581a65a63bd8d72a728ffb8b68`. PR Quality Gates for that head completed SUCCESS. GitHub returned no submitted reviews for PR #32. Do **not** merge automatically.
- PR #30's final reviewed head `6e268565609e3819b14b8232a94bd693b5e9e6c6` has successful GitHub PR Quality and Fresh Dev Runtime workflows; these are fresh-database CI and **not** independent restored-original Windows migration evidence.
- Prior unauthorized PR #27 merge discrepancy is separately open in issue #28.
- **Stale document correction needed:** PR #30 limited owner-acceptance and B2 review documents describe PR #30 as draft/unmerged, now inconsistent with GitHub.

## 4. Open risks and interpretation

- `aronexa.tenant_id` / `aronexa.membership_id` transaction context is **not cryptographically authenticated** to PostgreSQL. Compromise of the patient runtime SQL credential may permit context spoofing. W2C synthetic RLS tests do not close that threat.
- Other known blockers include broad authentication runtime privileges, MFA/recovery/abuse protections, encryption/retention of real contact data, comprehensive clinical/security review and end-to-end patient registration.
- These risks do not alone disprove the **synthetic-only** V8-to-V9 migration. They **do** prohibit treating W3 as security certification or authorization for real patient data, customer use or general Phase 01 closure.
- Original Windows database was owner-reported Flyway V8 at W2D; refresh read-only identity and V8/version/role/count evidence **at the moment of any W3 owner decision**, and again through a separately reviewed W4 preflight after explicit GO.

## 5. W3 decision checklist — outstanding

- [ ] Independent reviewer inspects/redacts and attests original W0, W1, W2A, W2B and W2C sanitized execution evidence, including failures and recoveries.
- [ ] Verify exact matching source-container and host backup hashes, source baseline timing, `pg_restore --list` and W2A restore provenance with all 25 counts/ACLs.
- [ ] Confirm V1–V8 Flyway history checksums and exact V9 Git blob/sha at decision time; independently inspect V9 role/tenant/membership/branch policy checks, audit event and SQL privilege denials.
- [ ] Confirm the W2C per-scenario PASS markers, negative checks and ROLLBACK, and no synthetic fixture persistence.
- [x] Owner reports ZIP outer SHA and internal manifest/size SHA verified. Independently inspect original sanitized transcripts separately.
- [ ] Reconcile PR #30 merged-without-recorded-authorization, identify merge actor/time/method, correct stale docs, define protections for future privileged merges; review issue #28, PR #32, and branch review controls.
- [ ] Verify original database remains V8, remains synthetic-only and does not contain unexpected real records.
- [ ] Independent reviewer issues the evidence assessment, with any residual risk accepted explicitly.
- [ ] **Owner issues a new, explicit W3 GO or NO-GO referencing this record and current evidence.** No implicit authorization.

## 6. Current recommendation and prohibitions

**W3_REVIEW_RECOMMENDATION: HOLD — INSUFFICIENT INDEPENDENT EVIDENCE AND UNRESOLVED GOVERNANCE.**

This is **not** a migration failure and does not revoke the limited P01-T07-B2 INTERNAL_FOUNDATION task acceptance. It is a decision to **not advance to W4 yet**.

Until a separate explicit owner W3 GO:
- **Do not apply Flyway V9** or bootstrap patient runtime on the original Windows V8 database.
- Do not start the V9 application branch connected to original V8 with automatic Flyway migration enabled.
- Do not run Flyway clean/repair, replay V8 Apply, restore over original, delete original volume, or dispose of W2 evidence.
- Do not merge PR #32, deploy/customer-release or use real-patient data.
- Retain original W1 backup, isolated disposable V9 rehearsal, GitHub issue #31 as audit record.

**Owner W3 decision:** NOT GRANTED.  
**W4 original migration:** BLOCKED.  
**W5 original acceptance:** NOT STARTED.
