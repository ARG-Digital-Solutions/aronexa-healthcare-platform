# P01-T07-B2-MIG — W3-H9E Option B recovery evidence decision review

**Date:** 2026-11-07 IST  
**Owner direction:** "proceed" after recommendation to **review W3-H9D Option B first**, while separately authorized feature development stays on isolated synthetic data. **Authorization scope:** read-only comparison and decision preparation; **not** execution of original migration or acceptance of residual recovery risk on the owner's behalf.

## 1. Provisional finding

**OPTION B can be reasonable for the narrowly scoped synthetic-only development database**, avoiding a second disposable restore, if current original-to-W1 alignment and a practical recovery plan are independently reviewed and the owner separately accepts the missing W2A historical transcript.

**But Option B is NOT ready for final acceptance from current evidence alone**. The reviewed W2B script compares the 25 V9 disposable database table counts with the W1 baseline; it verifies original Flyway V8 and V1–V8 checksum history but does **not compare all current original V8 table counts with W1**, nor row-content fingerprints. Thus `W3C 10/10 PASS` and `W3H9A 25/25 PASS` are not conclusive evidence of original-V8 backup **freshness**. No evidence supports an assertion that the original source row contents did not change after W1.

No original DB access or local test was performed through GitHub in this review.

## 2. Already available evidence, precisely scoped

| Gate | Present evidence | Limit |
|---|---|---|
| Original W2A real restore | Owner reported `W2A_DISPOSABLE_V8_RESTORE_AND_COUNTS_PASS`; script ran `pg_restore --exit-on-error` in a separate PG17 tmpfs database with 25/25 counts, V1–V8 history and restricted roles | Native complete W2A and transfer receipts not located; not independently witnessed |
| Private W1 dump | 92,567 bytes, SHA-256 `153842CCC811A64729F2DBA0743515A6C24E5A20AFB1D054AF054FECB36C3CAB`; W3-H9A owner report verified hash, baseline CSV 25/25 | Current file integrity; cannot show source has not changed |
| PG17 archive TOC | Owner W3-H3 reported 25 TABLE and 25 TABLE DATA with `pg_restore --list` PASS | Metadata, not a fresh full restore |
| W3-C/H1 integrity | 10/10 retrospective read-only markers, original V8/disposable V9, grant/RLS/Flyway checks | Disposable counts compared with W1; current original counts/content not comprehensively compared to W1 |
| W3-H7/W3-H9A | Newly dated 15/15 synthetic W2C and private hash/manifest verification PASS as owner reported | Not a new W2A restore or substitute historical transcript |
| CI code/build | Existing PR head quality/fresh PG runs PASS | Does not certify original Windows backup recovery or current source freshness |

## 3. Targeted original V8 freshness verification — designed, NOT YET RUN

Provided as a **separate Windows PowerShell 5.1 script outside Git**, `Verify-AroNexa-W3H9D-OptionB-SourceFreshness.ps1`, local distribution SHA-256 **`8380d00de090837dee27a13f4229a9327aec9a6faa4aaa140eaaf7187e22f74b`**. Script statically reviewed; not executed on owner's Windows in this chat.

When manually invoked with explicit confirmed-synthetic-only switch, exact retained W1 `-BackupFile`, W1 `-CountsFile`, and clean feature checkout `-RepoPath`, the script does the following **read-only only**:
1. Validate exact feature branch, clean Git source, V9 source blob `3a1e2b717576ccd84f0de058bb0a83786c517d32`.
2. Recheck W1 **92,567 bytes** and exact SHA-256; import 25 unique valid table-name/baseline count pairs.
3. Identify exactly one `aronexa-dev` Compose PostgreSQL source; inspect expected named original volume.
4. Via `docker exec ... psql -c SELECT` **only**, check PostgreSQL 17, original Flyway V8, no failed migrations, 25 public tables, absence of new patient-runtime role, and query **all 25 original current table row counts**; require exact equality to saved W1 baseline.
5. Print fixed PASS/STOP/count markers, **not patient data, passwords, SQL rows, raw logs or private pathnames**. The helper performs no Docker create/start/stop/delete, SQL DML/DDL, Flyway, backup or restore, and makes no GitHub write.

**IMPORTANT LIMIT:** A `25/25` PASS proves **count alignment only**. Equal-count row edits/deletes/inserts or timestamps are not detected. Do not claim cryptographic current-original-to-W1 *data-content* identity. Confirm with the owner whether any writes, fixture changes or test resets occurred since W1; if unclear, require separately reviewed content-fingerprint method or a new authorized consistent backup, not immediate acceptance.

**Abort if:** unknown data provenance, patient data present, different original volume, original not V8, backup/CSV mismatch, any table count drift, unexpected patient role, unclean/wrong Git checkout. Never run latest V9 Spring Boot against original V8 under HOLD.

## 4. Option B final acceptance criteria (future explicit owner decision)

**All of the following must be true:**
- `W3H9D_OPTIONB_CURRENT_V8_COUNTS_MATCH_W1: 25/25` and hash/identity gates pass on owner's Windows at the decision time, with independently reviewed sanitized output.
- No material row changes since W1 established through reviewed source/snapshot provenance or additional independently accepted read-only fingerprints; if not demonstrated, the owner must either accept that exact *synthetic-only* recovery risk with a documented rationale or request a **fresh consistent backup under distinct authorization**.
- Earlier W2A real-restore operator attestation and native script's source-to-host SHA guard judged sufficient for **synthetic-only** use despite original transcript not located; retain `W2A_HISTORICAL_EXECUTION_RECEIPT_NOT_LOCATED` explicitly.
- Practical W4 failure/stop/recovery plan and operator responsibilities are independently reviewed; no ad hoc restore-over-original.
- Owner explicitly decides `EX01_OPTION_B_LIMITED_RISK_ACCEPTED` (or rejects) with full disclosure of limitations, before even considering a new and separate W3 GO.

**W3-H9E current disposition:** `OPTION_B_TECHNICAL_REVIEW=COMPLETE`; `CURRENT_SOURCE_TO_W1_TABLE_COUNT_VERIFICATION=AWAITING_OPERATOR_RUN`; `CURRENT_SOURCE_ROW_CONTENT_ALIGNMENT=NOT_PROVEN`; `EX01_RESIDUAL_RISK_ACCEPTANCE=NOT_GRANTED`.

## 5. Development efficiency and safety

Do not rerun successful W2C (single attempt used), W3-H9A evidence checksum scans or unrelated governance reviews absent a new defect or source drift. After this one bounded source check and Option B decision, prioritize **independently authorized patient registration/duplicate detection API + receptionist UI + tests and demonstrable increment** using **separate synthetic data/CI**, not the protected original V8. W3-E HOLD is a restraint on the original V8 and customer/patient release, not a ban on independent feature work.

**Binding:** `W3_E_OWNER_DECISION=NO_GO_HOLD_APPROVED_2026-11-06`; `W3_OWNER_GO=NOT_GRANTED`; `W4_ORIGINAL_APPLY=BLOCKED`; `PR32_MERGE=HOLD`; `CUSTOMER_RELEASE=BLOCKED`; `OPTION_A_FRESH_RESTORE=NOT_AUTHORIZED`; `OPTION_B_RESIDUAL_RISK_ACCEPTANCE=NOT_GRANTED`.

No Docker, Flyway, SQL, original DB or patient data changes performed by creating this review. The local script has not been run.
