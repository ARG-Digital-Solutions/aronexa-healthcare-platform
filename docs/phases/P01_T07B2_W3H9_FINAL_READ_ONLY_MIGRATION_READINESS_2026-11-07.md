# P01-T07-B2-MIG — W3-H9 Final Read-only Migration Readiness Assessment

**Review date:** 2026-11-07 IST  
**Authorization:** User instruction to proceed with the **W3-H9 final read-only migration readiness assessment** after W3-H8 governance review and owner conditional retention of accidental PR #27 and PR #30 merges.  
**Scope:** **Original synthetic-only Windows development database** (PostgreSQL 17; `aronexa_dev`; original Flyway V8), proposed forward-only V9; *not production, clinical deployment or real patient data*.  
**Source basis:** GitHub issues [#31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31) and [#33](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/33); PRs [#27](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/27), [#30](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/30), [#32](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/32); [W3-E owner HOLD record](P01_T07B2_W3E_OWNER_DECISION_RECORD_2026-11-06.md); [W3-H5](P01_T07B2_W3H5_HISTORICAL_EVIDENCE_EXCEPTION_REVIEW_2026-11-06.md), [W3-H6](P01_T07B2_W3H6_RESIDUAL_RISK_AND_VERIFICATION_DECISION_2026-11-06.md), [W3-H7 plan](P01_T07B2_W3H7_DISPOSABLE_W2C_EVIDENCE_CAPTURE_PLAN_2026-11-06.md), and recent owner-reported private Windows outputs recorded in issue #33.  
**Review method:** GitHub repository file/PR/CI/issue *read-only* retrieval and recorded sanitized operator output. The actual original Windows DB, Docker containers and private evidence files were **not independently accessed or rehashed in this review**.

## 1. Final recommendation

**W3-H9_REVIEW:** `REVIEW_COMPLETED_PENDING_GO_PREREQUISITES`.  
**W3-H9_GO_READINESS:** `NOT_YET_INDEPENDENTLY_ACCEPTED`.  
**Decision recommendation:** **KEEP THE OWNER-APPROVED W3-E NO-GO/HOLD** for original V8→V9 Apply.

Supporting technical checks are strong, especially the newly captured W3-H7 15-scenario re-attestation. However, the original backup/restore provenance and independent evidence sufficiency are not signed off; conditional merged-code retention still requires build-safety/merge-control review, and the mandatory **new, explicit owner W3 GO** has **not** been granted. Completion of W3-H9 is **not a GO**, does not automatically authorize W4, and does not close every historical exception.

**W3_E_OWNER_DECISION:** `NO_GO_HOLD_APPROVED_2026-11-06`  
**W3_OWNER_GO:** `NOT_GRANTED`  
**W4_ORIGINAL_APPLY:** `BLOCKED`  
**PR32_MERGE:** `HOLD` (draft/unmerged)  
**CUSTOMER_REAL_PATIENT_RELEASE:** `BLOCKED`

## 2. Source identity and GitHub/CI facts verified in GitHub

| Gate | Result / evidence | Remaining limitation |
|---|---|---|
| Original Git main | `main` currently at `35c583e05f3199c66f830ecabc4a38b16004f98b`, PR #30 merge | Git merge is **not** evidence original Windows DB was migrated |
| Reviewed V9 SQL identity | `backend/src/main/resources/db/migration/V9__patient_registration_read_boundary.sql` is identical on `main` and `feature/p01-t07b-patient-onboarding`, Git blob `3a1e2b717576ccd84f0de058bb0a83786c517d32` | A future decision must reverify no source drift |
| PR #27 | Merged; head `15e1e7e4bed3aeb12047507e80a31335342988f7` had two successful `PR Quality Gates` runs | Did not have separate prior merge authorization; owner **conditionally** approved retention, not future-build guarantee |
| PR #30 | Merged; head `6e268565609e3819b14b8232a94bd693b5e9e6c6` had two successful `PR Quality Gates` and one successful `Dev Runtime Acceptance (Fresh Database)` | CI success is at **PR head**; no independent original-Windows-V8 runtime acceptance or guarantee future environment works |
| PR #32 | OPEN, DRAFT, UNMERGED; head at H9 review `ddc3afc62caf6464ec0a70d0d9755c0beaad315c`; latest PR-head Quality Gates owner-reported/retrieved PASS | Must remain unmerged without distinct owner merge approval |
| Application configuration | `application-dev.yml` requires `ARONEXA_PATIENT_DB_PASSWORD` and `aronexa_patient_runtime`; V9 assumes new role exists. `deploy/dev/compose.yml` has 4 role credentials; initdb setup executes on **fresh volumes only** | **CRITICAL: do NOT launch latest Spring Boot dev profile pointed at original V8**. Startup may attempt auto-Flyway V9 before W3/W4 authorization, or fail on missing patient DB role/password |
| GitHub protection | Private org repo; owner previously observed current `Protect-main` ruleset not enforced on existing plan; PR template proposed on draft #32 branch but **not on main** | Effective `main` branch protection / bypass still **not independently retrievable/verified**. A manual approval checklist cannot act as a GitHub merge lock |

**Interpretation:** The accidental merges did not obviously break CI for fresh test environments. No guarantee of future Windows startup, live original-V8 compatibility or patient/clinical readiness is established. A separate controlled clean-checkout regression test may be warranted, but must be configured **without starting the newest application or Flyway against the original V8 database**; this review does not authorize writes to that database or new Docker resources.

## 3. Historical and current evidence sufficiency

| Exception | Present evidence and confidence | W3-H9 disposition |
|---|---|---|
| **EX-01 — Recovery/backup provenance** | W3-B 25-table baseline reconciled; retained W1 private PostgreSQL dump **92,567 bytes** and SHA-256 `153842CCC811A64729F2DBA0743515A6C24E5A20AFB1D054AF054FECB36C3CAB` checked in owner W3-H2; W3-H3 PG17 custom archive TOC has **25 TABLE** and **25 TABLE DATA**; W2A isolated restored-V8/25-table count PASS **owner-reported**, but contemporaneous container→host receipt and restore transcript missing | **PARTIALLY MITIGATED / OPEN.** An independent reviewer must examine current W1 source alignment and W2A restoration sufficiency or recommend a **separately authorized new disposable-only restore attestation**. `pg_restore --list` alone does **not** prove all rows/data blocks are restorable. No restore authorized by W3-H9. |
| **EX-02 — Original isolated V9 migration transcript missing** | Later W3-C read-only original V8 vs isolated V9 verification operator-reported PASS with private report SHA-256 `134A13AF651151391DF86F138C772ECF079FFDDE785A9679D721A7DDB3841A01`; W3-H1 ten retained markers PASS, W2B acceptance script verified in GitHub with V1–V8 checksum preservation and exact V9 deltas/RLS/grants checks | **RETROSPECTIVE CURRENT-STATE EVIDENCE STRONG / HISTORICAL EXCEPTION OPEN.** Independently inspect sanitized report/hash and exact checked script; no new Flyway migration necessary solely to replace old console output. Owner must explicitly accept historic limitation if reviewer concurs. |
| **EX-03 — Original W2C 15-scenario/rollback transcript missing** | Separate user-approved **one W3-H7 disposable-only** run completed with `W3H7_SCENARIO_MARKERS_FOUND: 15/15`, native `psql` exit zero, child exit zero, rollback and W2B postcheck PASS. **Attempt used 1/1**. Private raw log SHA `1AD6D72349631BF8A8EEA45D565D27C4B119022F68D51C1C8232D5188635825E`; manifest SHA `1D3EC89E88E3CDBDDDD293471257948816B92EB1F282DBAD6DE4AB6340F3EBD3` | **FRESH SYNTHETIC RE-ATTESTATION PASS, OWNER-REPORTED; PRIVATE EVIDENCE INDEPENDENT REVIEW PENDING.** The original W2C historical transcript remains not located. No rerun: **1/1 used**; future attempts require new explicit owner permission. |
| **EX-04 — Accidental merge/approvals/branch protection** | PR #27/#30 merged with no submitted GitHub PR reviews, despite explicit no-merge instructions. Owner subsequently **conditionally approved retaining existing merges** provided they do not cause code/future-build regressions. PR-head CI green. Effective private repo `main` enforcement unavailable in tools; owner screenshot warned plan not enforced | **CONDITIONAL RETAIN AUTHORIZED; BUILD-SAFETY AND ENFORCEMENT REMAIN OPEN**. Preserve both merges (do not auto-revert). Verify build compatibility in a non-original/test-only environment and choose effective branch controls or owner-acknowledged manual compensating workflow before any future merge. |
| **EX-05 — Clinical, security and real-patient release** | Known SQL GUC spoofing under direct patient SQL credential compromise, broad auth runtime role, MFA and contact encryption/retention concerns, incomplete independent security/clinical evidence | **RELEASE BLOCKER — OPEN**. Separate from narrow, synthetic-only development migration evidence. No patient/customer release is authorized by any hypothetical limited future W3 GO. |

**Evidence provenance:** Internal GitHub source/CI and owner-reported Windows outputs are not equivalent. Private raw evidence has **not been opened in this review**, and hashes do not independently prove log contents. No historical missing logs were recreated or backdated.

## 4. Precisely what remains before a new synthetic-only W3 GO decision

1. **EX-01 recovery confidence signoff:** independent reviewer assesses W1 integrity, relation to current original V8 source, missing original W2A restore logs, and current restore-readiness. They may recommend specific separately authorized isolated restore verification if not sufficient. **No SQL or Docker operations are permitted by this review.**
2. **EX-02/03 private evidence review:** on owner's Windows machine, independently rehash W3-C retained report and new W3-H7 raw log/manifest, read/redact **privately** for the exact scenario and postcheck markers, confirm raw/native exit codes and original V8/disposable V9, compare to the vetted Git scripts and reviewed V9 blob. Transfer only safe marker counts/hash results to issue #33. Make written findings `ACCEPT` or `OPEN` explicitly; original missing historical logs remain documented.
3. **EX-04 build and governance safeguard:** confirm a clean build or otherwise independent CI relevance for the **actual final branch/SHA** without any connection to original V8 and no unapproved Docker/Flyway creation; verify effective GitHub protections and bypass or explicitly accept the temporary manual control gap with owner signature. No normal new V9 startup against original V8, even for a smoke check.
4. **Final read-only decision-time preflight:** positively identify original Compose project `aronexa-dev`/PostgreSQL container/volume and `aronexa_dev`, prove **synthetic-only** data, verify original Flyway **V8** and patient role absent/expected, W1 backup freshness/size/hash and a tested recovery path, exact V9 blob/current code refs, disposable V9 and security/role constraints; confirm clean checkout and no unattended Flyway migration. W3-H7 previously reported preflight PASS but is not automatically an indefinite attestation of later state.
5. **Independent scoped acceptance:** independent reviewer concludes each of EX-01/02/03/04 is either sufficiently mitigated or a *narrow, explicitly accepted residual limitation* for **synthetic-only local development migration**. An unaccepted, safety-critical restore/control gap remains a blocker.
6. **Separate affirmative owner's NEW W3 GO or NO-GO** in issue #31, with exact source SHA, backup details, disposition and limited scope. The previous owner `NO_GO_HOLD_APPROVED_2026-11-06` remains binding until superseded explicitly.
7. **W4 is a distinct execution gate even after GO:** independently approve the exact reviewed original-only W4 procedure (preflight, dedicated patient role provision, forward-only V9, post-apply V9/Flyway validation, recovery/halt gates), then separately execute under authorized operational control. No W4 permission is created by this H9 review.

If the independent recovery reviewer concludes existing W2A operator-reported restore confidence is not adequate, **request a separate authorization for a NEW disposable-only restore rehearsal**. Do **not** assume the owner already authorized that action.

## 5. Recommended follow-on and report markers

**Next practical work item:** `W3-H9A READ-ONLY PRIVATE EVIDENCE SUFFICIENCY REVIEW` — confirm W1/W3-C/W3-H7 private evidence hashes and redacted PASS markers; record independent findings for EX-01/02/03 and preserve all evidence. Then `W3-H9B BUILD/PROTECTION READINESS` without original V8 startup or source mutation, and only after those, a **NEW** W3-E owner decision review. Avoid re-running W2C: its sole owner-approved attempt has been consumed.

**W3_H9_REVIEW:** `REVIEW_COMPLETED_PENDING_GO_PREREQUISITES`  
**W3_H9_READ_ONLY_GITHUB_SOURCE_REVIEW:** `PASS_IN_RETRIEVED_SCOPE`  
**W3_H9_PRIVATE_EVIDENCE_INDEPENDENTLY_INSPECTED:** `NO`  
**W3_H9_ORIGINAL_WINDOWS_DB_INDEPENDENTLY_QUERIED:** `NO`  
**W3_H9_EX01_RECOVERY_ACCEPTANCE:** `PENDING`  
**W3_H9_EX02_RETROSPECTIVE_EVIDENCE_ACCEPTANCE:** `PENDING`  
**W3_H9_EX03_NEW_W2C_REATTESTATION:** `OWNER_REPORTED_PASS_15_OF_15_SINGLE_ATTEMPT_CONSUMED`  
**W3_H9_EX04_RETENTION:** `OWNER_CONDITIONAL_RETAIN_PENDING_BUILD_AND_ENFORCEMENT`  
**W3_H9_GO_READINESS:** `NOT_YET_INDEPENDENTLY_ACCEPTED`  
**W3_E_OWNER_DECISION:** `NO_GO_HOLD_APPROVED_2026-11-06`  
**W3_OWNER_GO:** `NOT_GRANTED`  
**W4_ORIGINAL_APPLY:** `BLOCKED`  
**PR32_MERGE:** `HOLD`  
**CUSTOMER_REAL_PATIENT_RELEASE:** `BLOCKED`

**No original DB/Docker operations, Flyway command, synthetic SQL rerun, GitHub merge/revert/setting change or patient/customer release occurred as a result of producing this document.**
