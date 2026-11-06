# P01-T07-B2-MIG — W3-H7 Controlled Disposable Verification and Evidence-Capture Plan

**Date:** 2026-11-06 IST  
**Scope:** Planning and owner-authorization preparation only.  
**Binding decision:** Owner [W3-E NO-GO/HOLD](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31#issuecomment-6690270572).  
**Tracking:** [Issue #33](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/33), [W3-H6 assessment](P01_T07B2_W3H6_RESIDUAL_RISK_AND_VERIFICATION_DECISION_2026-11-06.md).  
**Current outcome:** `W3_H7_PLAN_PREPARED` / `W2C_EXECUTION_NOT_AUTHORIZED` / `W4_BLOCKED`.

This document **does not authorize** any database access/write, W2C test execution, W2A restore, Flyway migration, role creation, Docker resource changes, PR merge/revert, patient data or deployment. All future operations require separate, specific owner approval and an independently reviewed execution procedure.

## 1. Preferred W3-H7 path

**Proposed:** One **newly dated**, separately authorized W2C synthetic RLS/transaction test against the **already-retained isolated disposable V9** PostgreSQL 17 environment, with a private full raw transcript under controlled storage and a separately redacted marker-only evidence summary. The new report must be described as **fresh W3-H7 re-attestation**, never as missing original W2C historical evidence.

**EX-01 restore decision:** **DEFER repeating W2A restore at this time.** Operator-reported W2A disposable V8 restore and matching counts/ACL, W3-B semantic reconciliation, W3-C read-only original V8/disposable V9 check, W3-H2 W1 SHA/size and W3-H3 PG17 custom backup TOC metadata provide supporting evidence. This is **not independent end-to-end restore proof**, so EX-01 remains OPEN. An independent reviewer must later determine whether these controls are sufficient for a synthetic-only W3 GO. If not, a **separate authorization** and distinct new disposable restore-validation plan would be required, not an unapproved rerun of the original V8→V9 migration.

**EX-02 decision:** No repeat Flyway migrate. The existing W3-C/H1 retrospective V9 read-only markers are adequate for planning; independently review the retained sanitized W3-C output and exact source blob before future W3 GO. Historical W2B migrate transcript remains not located.

## 2. Source reviewed for the proposed W2C (GitHub, not local runtime)

| Asset | Tracked Git blob |
|---|---|
| `scripts/acceptance/p01-t07b2/Invoke-AroNexa-W2C-Synthetic-RlsRollback.ps1` | `9403b9684bcca243a24ad6da786638a2e2f3bfc0` |
| `scripts/acceptance/p01-t07b2/W2C_Disposable_Patient_RLS_Transaction_Scenarios.sql` | `931d44d775dbe22bbc6ed5bcb8ddfc9faa9d70da` |
| `scripts/acceptance/p01-t07b2/Invoke-AroNexa-W2B-ReadOnly-V9Acceptance.ps1` | `5e030b911ba53310516b43411e3f8c7e263e19c3` |
| `backend/src/main/resources/db/migration/V9__patient_registration_read_boundary.sql` | `3a1e2b717576ccd84f0de058bb0a83786c517d32` |

The reviewed W2C wrapper uses required parameters `-RehearsalContainer`, `-RehearsalNetwork`, `-BackupFile`, `-CountsFile`, `-RepoPath` and `-ConfirmSyntheticDisposableOnly`; rejects wrong branch/dirty checkout/wrong V9 Git blob; checks W1 backup size/hash, Docker source and disposable identity/network, restricted roles and Flyway states, then streams SQL *only into the positively identified disposable V9 container*. After executing the SQL under `ON_ERROR_STOP`, it checks expected markers and invokes W2B read-only postcheck. The original container is only queried read-only by this script. **This is source design, not new execution evidence.**

**Previously reported disposable identifiers (must be revalidated; never trust names alone):**
- Target container: `aronexa-w2-pg17-20261106_100009_55d6f228`
- Target internal-only network: `aronexa-w2-v9-20261106_100009_55d6f228`
- Original: unique running Docker Compose `aronexa-dev` / `postgres`, database `aronexa_dev`, original volume `aronexa_dev_postgres_data`; never mount/share original volume into disposable.
- W1 backup: expected 92,567 bytes; SHA-256 `153842CCC811A64729F2DBA0743515A6C24E5A20AFB1D054AF054FECB36C3CAB`; baseline 25 rows.

## 3. Mandatory **pre-execution** controls if separately authorized

All items below must be checked, recorded and individually PASS **before** any SQL fixture is sent:

1. **Separate explicit owner W3-H7 W2C test authorization** in issue #33, identifying exact *disposable* container/network, test scope (temporary synthetic writes within rollback), branch/source hashes, allowed report output, abort conditions, and prohibition on original writes. Owner W3-E NO-GO/HOLD remains active.
2. Independently review **source and local working tree** (clean `feature/p01-t07b-patient-onboarding` checkout; exact Git blobs above; PowerShell 5.1 syntax; the SQL contains `BEGIN` and two `ROLLBACK` and **no `COMMIT`**; no unapproved code changes). If source diverges from reviewed hashes, STOP and re-review.
3. Confirm **unique original** Compose project/service container and its distinct full Docker container ID. Verify original database `aronexa_dev` is **Flyway V8, synthetic-only**, correct existing volume, and no original patient/customer data. Reject ambiguous or changed identity, inaccessible source or unexpected V9/role.
4. Confirm target PostgreSQL 17 **Flyway V9**, expected exact retained disposable container **ID**, W2 network is internal-only, target has no published host ports, **only the expected network**, no original DB volume/mount or source identity collision. No newly created resources unless separately authorized.
5. Confirm SHA-matched and readable private W1 backup and 25-table baseline; patient registration and patient access audit tables in disposable V9 are initially empty; zero failed Flyway history rows, patient runtime nonowner/non-superuser/no BYPASSRLS; disabled `PATIENT_REGISTRATION` and no tenant entitlement.
6. Independently verify enough private evidence storage space, restricted local ACL, a unique new report directory **outside Git**, stable executable versions and date/time, and absence of an already running W2C session.
7. Establish **fail-closed** exit-code semantics for native PowerShell/Docker commands. Do not mark PASS on marker text alone if `psql` exit code, script exit code or postcheck fails. Avoid the historical PowerShell merged-native-stderr terminating-error pitfall; review/correct *before* any run, not mid-flight.
8. No running application against original V8 with Flyway auto-migration enabled; no Flyway migrate, clean, repair, bootstrap, dispose/delete or original backup overwrite.

**Hard stop:** any missing owner authorization, dirty branch, blob drift, wrong target/volume, port publication, real-patient indicator, unexpected roles/Flyway state, nonempty initial fixtures, missing W1 backup, insufficient space, denied/allow mismatch, rollback uncertainty, failure/missing marker, stale/partial evidence or inconsistent exit code. Stop without automatic retry, preserve evidence and seek separate owner review.

## 4. Exact synthetic acceptance scenarios (all must be individually evidenced)

Source-reviewed SQL has **15** distinct scenario PASS notices:

1. `W2C_NO_CONTEXT_DENIED`
2. `W2C_TENANT_ONLY_DENIED`
3. `W2C_ASSIGNED_BRANCH_VISIBLE`
4. `W2C_OTHER_MEMBERSHIP_ISOLATED`
5. `W2C_UNASSIGNED_MEMBERSHIP_DENIED`
6. `W2C_CROSS_TENANT_DENIED`
7. `W2C_TENANT_B_ISOLATED`
8. `W2C_MASTER_PATIENT_READ_DENIED`
9. `W2C_REGISTRATION_INSERT_DENIED`
10. `W2C_REGISTRATION_UPDATE_DENIED`
11. `W2C_REGISTRATION_DELETE_DENIED`
12. `W2C_ACCESS_AUDIT_READ_DENIED`
13. `W2C_APP_RUNTIME_PATIENT_READ_DENIED`
14. `W2C_AUTH_RUNTIME_PATIENT_READ_DENIED`
15. `W2C_SAME_CONNECTION_NEXT_TRANSACTION_CONTEXT_CLEARED`

**SQL safety structure:** original scenario SQL performs synthetic inserts inside `BEGIN` followed by `ROLLBACK`, then begins a new transaction on the *same session* to verify transaction-local tenant/membership context is cleared; the second transaction also ends in `ROLLBACK`. `ON_ERROR_STOP=1` must be enforced; a failed connection should close and roll back uncommitted writes. **Do not interpret an emitted notice on its own as success**—the test and postcheck exit codes, exact 15 markers and no unexpected errors are mandatory.

**Required final W2C script markers:** `W2C_DISPOSABLE_ISOLATION: PASS`, `W2C_SYNTHETIC_SQL_NEGATIVE_SCENARIOS: PASS`, `W2C_ROLLBACK_AND_TRANSACTION_RESET: PASS`, `W2C_POST_ROLLBACK_V9_INTEGRITY: PASS`, `ORIGINAL_FLYWAY_VERSION: 8`, `W2_FLYWAY_VERSION: 9`, `ORIGINAL_DATABASE_MODIFIED_BY_W2C: NO`, `FINAL_STATUS: W2C_SYNTHETIC_ISOLATION_AND_ROLLBACK_PASS`.

**After run (only if separately authorized):** the reviewed W2B read-only acceptance must PASS, including original 25-table counts, Flyway V1–V8 checksums, exact V9 row/audit delta, registered module disabled, RLS and patient/app/auth privilege denials. Recheck disposable synthetic fixture count and registration access audit empty, original Flyway still V8. Any postcheck failure means FAIL/HOLD and investigation, not retry.

## 5. Evidence capture — fresh and non-retroactive

If owner separately authorizes the run, capture the full original stdout/stderr, exit codes, preflight results and postchecks privately in a new uniquely named directory such as `%USERPROFILE%\AroNexa-Backups\P01-T07B2-W3H7-<timestamp>-<runid>\`. **Do not echo raw stdout/stderr publicly**: it may include identifiers, infrastructure topology or credentials. Review source runner output handling first. Preserve the original W2D/W3-C evidence and W1 dump unchanged.

Proposed private evidence inventory:
- `01_PRECHECK_SANITIZED.txt`: source/target full-ID comparison PASS, source V8/target V9, network and volume separation, source blobs and W1 SHA, no patient data (no identifiers).
- `02_W2C_RAW_PRIVATE.log`: original, unedited source tool outputs and native exit codes, accessible **only in private storage** pending redaction; do not upload to GitHub.
- `03_W2C_15_MARKERS_SANITIZED.txt`: 15 exact PASS/MISSING marker flags with timestamp, no fixture IDs or SQL details.
- `04_ROLLBACK_AND_POSTCHECK_SANITIZED.txt`: ROLLBACK, next-transaction clean context, verification exit code, original V8/disposable V9 and no fixture retention PASS/MISSING.
- `05_SHA256_EVIDENCE_MANIFEST.csv`: SHA-256, bytes and relative names of every approved private evidence file; optionally separate sanitized ZIP built only after privacy inspection.
- `06_EXECUTION_SUMMARY.txt`: new run timestamp, new report SHA-256, source blobs, result PASS/FAIL, operator attestations and reviewer finding, explicitly `RETROSPECTIVE_W3H7_NEW_EXECUTION_NOT_ORIGINAL_W2C_LOG`.

**Never commit/upload** private dumps, credentials, raw SQL/psql logs, patient data, session tokens, .env, or unredacted archive contents. GitHub issue may receive only redacted marker counts, selected report SHA-256, scoped exit-code results and exception conclusion.

**Provenance control:** newly generated test logs must not be backdated or renamed as the original historical W2C transcript. EX-03 historical-original-log limitation remains recorded even after a new re-attestation PASS.

## 6. EX-01 restored-original V8 verification decision

**Current W3-H7 choice: DEFER another restore.** Confirm with an independent reviewer whether the previously operator-reported W2A successful restored-V8 run and later H2/H3/W3-B/W3-C read-only controls are sufficient for synthetic-only migration readiness. The original transfer/rehearsal logs remain unavailable within bounded searched directories.

If insufficient, require a **new specific authorization** for a distinct one-off *new disposable container* restore-validation plan (private hash-matched W1 dump, separate network/volume, no original DB modification, recorded original V8 versus restored V8 Flyway/25 counts/ACL and hashes, private evidence, fail-closed). Do **not** reuse the W2C authorization to create/restore disposable databases or start Flyway migrations; do not automatically delete the original retained W2 V9 environment. No such restore is approved by this H7 plan.

## 7. Decision register — explicit separate owner gates

| Decision | State after producing this plan |
|---|---|
| H7 plan prepared / source reviewed | **COMPLETE — PLANNING ONLY** |
| H7 W2C execution on retained disposable V9 | **NOT AUTHORIZED** |
| H7 new disposable V8 restore | **NOT AUTHORIZED; DEFER** |
| H7 source or evidence-wrapper code modifications | **NOT AUTHORIZED by this plan** |
| EX-01/02/03 residual historical evidence exceptions | **OPEN; NOT WAIVED** |
| EX-04 accidentally merged PR #30 final retain/correct owner decision | **PENDING** |
| EX-05 patient/clinical release | **BLOCKED** |
| W3-E owner decision | **NO-GO/HOLD APPROVED (binding)** |
| W4 original Windows V8→V9 migration | **BLOCKED** |
| PR #32 | **DRAFT / DO NOT MERGE** |

**Suggested next owner decision (not granted by H7):** Decide whether to authorize exactly one new **disposable-only W2C** run with contemporaneous private evidence capture and strict abort conditions, **or** pursue independently reviewed residual-risk acceptance without rerun. If authorization is issued, use a distinct owner instruction with exact target/scope; first perform a fresh independent *read-only precheck* and have the runner/evidence-capture procedure reviewed. Do not interpret an owner's general "proceed" or W3-H7 plan request as permission to modify either database.

**W3_H7_STATUS:** `PLAN_PREPARED_AWAITING_SEPARATE_EXECUTION_AUTHORIZATION`  
**W3_E_OWNER_DECISION:** `NO_GO_HOLD_APPROVED_2026-11-06`  
**W4_ORIGINAL_APPLY:** `BLOCKED`  
**PR32_MERGE:** `HOLD`

## 8. Subsequent explicit owner authorization — 2026-11-06 IST

The owner separately and explicitly stated:

> I explicitly authorize one W3-H7 W2C synthetic-only verification on the existing isolated disposable V9 environment, subject to the reviewed preflight and evidence-capture safeguards. Do not modify the original V8 database, rerun Flyway, create or delete Docker resources, merge PR #32, or authorize customer release.

The [owner authorization was recorded in Issue #33](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/33#issuecomment-6691516456). This subsequent narrow authorization **supersedes the planning-time NOT_AUTHORIZED field only for one controlled disposable W2C attempt after required safeguards pass**. It does **not** authorize any unreviewed change to Git-tracked scripts, the original V8 database, Flyway, Docker resources or release. Once the SQL fixture begins executing, one authorized attempt is consumed regardless of the outcome; no automatic rerun.

**Pre-execution evidence-capture blocker:** The current reviewed wrapper verifies all 15 individual SQL scenario notices **in memory** but does not preserve raw successful `psql` output to a private evidence file. Capturing only wrapper stdout does not meet the proposed full raw output requirement. Do not start the authorized attempt until an independently reviewed, private, external evidence-capture procedure is prepared and all read-only preflight checks pass. The raw private log must remain outside the Git repository. Any script modifications or other newly scoped actions require separate review and authorization.

**Updated authorization status:** `W3_H7_SINGLE_DISPOSABLE_W2C_ATTEMPT=OWNER_AUTHORIZED_CONDITIONAL`  
**Execution:** `NOT_STARTED`  
**Evidence capture readiness:** `PENDING_REVIEW`  
**W3-E owner NO-GO/HOLD:** `REMAINS_ACTIVE`  
**W4 original Apply:** `BLOCKED`  
**PR #32:** `DO_NOT_MERGE`
