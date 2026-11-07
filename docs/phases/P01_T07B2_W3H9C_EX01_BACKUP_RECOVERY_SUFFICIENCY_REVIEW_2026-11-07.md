# P01-T07-B2-MIG — W3-H9C / EX-01 Backup Recovery Sufficiency Review

**Review date:** 2026-11-07 IST  
**Authorization:** Owner said "proceed" following W3-H9B, whose named next step was **EX-01 — Backup Recovery Sufficiency Review**. This is **read-only documentation and GitHub evidence review only**, not approval of a new restore/backup, Docker resources, Flyway, original DB changes or W3 GO.  
**Target:** Original synthetic Windows development PostgreSQL 17 `aronexa_dev` on Flyway V8; prospective forward-only V9 **ONLY AFTER NEW OWNER W3 GO AND SEPARATELY AUTHORIZED W4**.  
**Sources:** GitHub issue [#31](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31), [#33](https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/33), and read-only inspection of [W2A restore helper](../../scripts/acceptance/p01-t07b2/Invoke-AroNexa-V9-Disposable-RestoreCheck.ps1), [W2 runbook](P01_T07B2_WINDOWS_V8_TO_V9_CONTROLLED_MIGRATION_RUNBOOK.md), [W3-H5](P01_T07B2_W3H5_HISTORICAL_EVIDENCE_EXCEPTION_REVIEW_2026-11-06.md), [W3-H6](P01_T07B2_W3H6_RESIDUAL_RISK_AND_VERIFICATION_DECISION_2026-11-06.md), [W3-H9](P01_T07B2_W3H9_FINAL_READ_ONLY_MIGRATION_READINESS_2026-11-07.md). Private local bytes and the Windows original database were **not independently accessed** for this documentary review.

## Executive finding

**EX-01 is PARTIALLY MITIGATED with credible prior technical restore evidence, but is NOT independently accepted as complete recovery assurance.** The owner-reported W2A stage says the same SHA-verified W1 backup really restored to a separate PostgreSQL 17 V8 disposable instance and passed **all 25 restored table counts plus Flyway V1–V8 checksum/history reconciliation**. Importantly, this is far stronger than an archive TOC listing. Yet the original W2A full native-command transcript and source-to-host transfer receipt were not located. Current private hash/metadata checks identify the retained backup but **do not reconstruct what happened at W2A**, establish current backup freshness relative to the live original V8, or independently prove restoration into a new target today.

**Recommended conservative disposition for a future original-W4 migration:** retain W3-E `NO_GO/HOLD`; before recommending a new W3 GO, either **(A) separately authorize a fresh one-off disposable-only PostgreSQL V8 restore with contemporaneous private raw/redacted receipts** (preferred) or **(B) obtain an independent reviewer statement that existing W2A operator attestation, W1 integrity, current source alignment and recoverability plan are sufficient for *synthetic-only* development risk**, followed by **explicit owner acceptance of the residual historical-evidence risk**. Option B is not an implicit waiver, customer readiness or permission to restore over the original. No restore is authorized by this W3-H9C document.

## 1. Verified source design, not runtime execution

GitHub-tracked W2A PowerShell: `scripts/acceptance/p01-t07b2/Invoke-AroNexa-V9-Disposable-RestoreCheck.ps1`, blob `2b4b3c266bf2c1d230499f203ec3d0d4201a9494` at reviewed branch.

Review of the actual script establishes these **designed fail-closed checks**:
1. Clean approved feature branch and exact V9 Git blob `3a1e2b717576ccd84f0de058bb0a83786c517d32`, original Docker Compose `aronexa-dev` source uniqueness, Flyway V8.
2. Exactly 25 unique CSV table names/counts; compare Windows W1 backup bytes and SHA-256 **directly to the named source-container dump**, and `pg_restore --list` of source archive. It fails if copy differs.
3. Create a *distinct, random-named* internal-only Docker network and temporary PostgreSQL 17 container, with **tmpfs data**, no published host ports or original volume, and bootstrap only restricted disposable app/auth role names.
4. `docker cp` local W1 dump into that isolated container and execute **actual `pg_restore --exit-on-error`** against the disposable PostgreSQL database; fail on any native nonzero exit. This is a real restoration design, not just metadata inspection.
5. Require restored Flyway **V8**, no failed Flyway migrations, exactly **25 public tables**, exact count match for **all 25** tables to W1 CSV, restricted roles and matching original vs restored V1–V8 migration/checksum history.
6. Emit `FINAL_STATUS: W2A_DISPOSABLE_V8_RESTORE_AND_COUNTS_PASS` only after these checks. Script creates Docker resources **if executed**: it is NOT a read-only checker and must not be run as a side effect of this review.

Script review does **not** prove it was run exactly as reviewed, prove a private file is current/source-aligned now, prove every row's content checksum matched (counts alone cannot), or establish a tested original recovery/restore-overwrite procedure. The absence of original command logs limits independent reproducibility.

## 2. Concrete retained evidence, labelled by provenance

| Evidence | Value / result | Provenance / limitation |
|---|---|---|
| W1 retained Windows backup | 92,567 bytes; SHA-256 `153842CCC811A64729F2DBA0743515A6C24E5A20AFB1D054AF054FECB36C3CAB` | W3-H2 and W3-H9A owner-reported local hash/size PASS; private bytes were not uploaded/independently inspected |
| W1 table baseline | **25 unique tables**, W3-B earlier/later W1 and W2D CSV semantically match; W3-H9A owner reports `25/25` | Strong inventory consistency, not a bytewise data-content or timestamp identity proof |
| PostgreSQL 17 custom backup structure | W3-H3 corrected `pg_restore --list`: **25 TABLE** definitions plus **25 TABLE DATA** entries; archive hash PASS | Metadata TOC decodes; does not prove all data blocks can be decompressed/restored |
| W2A disposable restored-V8 event | GitHub #31 comment `6677040308`: `W2_RESTORED_FLYWAY: 8`, `W2_RESTORED_25_TABLE_COUNTS: PASS`, `W2_V8_HISTORY_CHECKSUMS: PASS`, `W2_RESTRICTED_ROLE_BOOTSTRAP: PASS`, `ORIGINAL_FLYWAY_VERSION: 8`, `ORIGINAL_DATABASE_MODIFIED: NO`, `V9_APPLIED: NO`; final `W2A_DISPOSABLE_V8_RESTORE_AND_COUNTS_PASS` | **Historical operator-reported PASS**, credible success of real `pg_restore`; complete original native execution/transfer transcript **not located** |
| W2A backup identity | W2A reports same SHA `153842...C3CAB`, exact 92,567 bytes, source repo `833ca774179778ebe1bc3452a6a269b667cc9785`, unique disposable `aronexa-w2-pg17-20261106_100009_55d6f228` | Matches retained W1 expected identity; confirms reported run identifiers, not independent raw transfer log |
| Later W3-C/W3-H7/W3-H9A | Original Flyway V8 vs disposable V9 read-only checks and 10/10 markers; fresh W3-H7 15/15 synthetic security re-attestation; W3-H9A verified retained private report/manifest hashes; attempt 1/1 consumed | Supports isolation and technical health; these are **not a fresh disposable-from-W1 V8 restore** and do not replace absent historical W2A log |
| Original migration source alignment **today** | Recent W3-H7 preflight still reported original Flyway V8 and backup SHA PASS | **Not a full current source content-to-backup comparison**; original data could have changed since W1 snapshot while Flyway stays at V8 |

**Do not confuse** `W3-H2/W3-H3/W3-H9A local hash PASS` with a new `pg_restore --exit-on-error` event. **No original W0/W1/W2A full execution transcript was recovered by W3-H4 bounded search.**

## 3. Recovery risks that affect future W3/W4 decisions

| Risk | Current state | Required mitigation / decision |
|---|---|---|
| **R1: Recovery actually works** | Earlier real W2A full restore/25-count PASS **owner-reported**, raw original transcript absent | **Preferred:** separately authorized fresh one-off disposable-only restore with private transcript; **alternative:** independent explicit technical sufficiency assessment and owner acceptance of missing original event provenance for synthetic-only scope |
| **R2: Current original equals retained W1 snapshot** | Original version reportedly still V8; backup hash stable, but a static hash does not prove latest original data equals W1 snapshot | Independently perform a **separately reviewed read-only decision-time inventory/source freshness check**, with synthetic-only dataset confirmation, exact source container/volume, table counts and relevant content/fingerprint where safe. If snapshot is stale, require **new separately authorized consistent backup** before original Apply; never overwrite existing W1 |
| **R3: Privileges/metadata and recovery procedure** | W2A script uses pg_restore with ACL/ownership and checks restricted roles/history, and passed per operator | Independent reviewer approves W4 rollback/recovery **stopping conditions** and ability to recover original safely. No restore-over-original as an ad hoc failure response; irreversible/disruptive recovery must be separately explicitly authorized |
| **R4: Historic transfer evidence absent** | W2A design compares source-to-host SHA; a matching event is logically entailed by the supplied final PASS *assuming reviewed script ran unchanged*, but no raw log retained | Classify `HISTORIC_TRANSFER_RECEIPT_NOT_LOCATED`. Never manufacture/backdate the event log. Owner can accept the historical limitation for synthetic-only development after independent review |
| **R5: Test scope vs patient release** | A small synthetic DB, not a production backup/DR program | EX-05 still blocks real patient/customer use: no restore-time objective, full security/clinical operational readiness or production-grade DR certification is established |

## 4. Preferred future EX-01 recovery proof — DESIGN ONLY, not authorized

If independent reviewer/owner chooses a new proof, prepare a **separately approved and fully reviewed** Windows PowerShell 5.1 procedure, distinct from the original W2A helper and uniquely named. Mandatory safeguards:

- **PREFLIGHT (nonmutating):** identify exact current original PostgreSQL17 `aronexa-dev`/database/volume and separate existing W2 V9 rehearsal, validate original V8, absence of real patient data, clean source commit and W1 92,567-byte hash, the intended 25-table baseline and sufficient private storage; stop for stale snapshot/role/source ambiguity.
- **ISOLATED CREATE/RESTORE (requires new explicit owner authorization):** create exactly one fresh named disposable PostgreSQL17 target on an **internal-only network, no host ports, tmpfs/temporary data, no original volume**, with distinct restricted role names; run `pg_restore --exit-on-error` with the exact W1 SHA-verified dump **once**. No Flyway or W2C test.
- **POST-RESTORE:** verify successful native exit, restored Flyway V8 with checksums, all 25 table counts and table structure, restricted role/ACL fidelity, original source still V8, and (where independently defensible) row-content fingerprints / restored relationships; fail closed if any count/hash differs.
- **EVIDENCE:** contemporaneous local PRIVATE raw exit/command-log and sanitized marker report, owner-verifiable SHA-256 manifest, private ACL and exact container/network IDs; preserve original W1 archive. Do not commit dump, raw logs, credentials, patient IDs or SQL contents.
- **STOP/CLEANUP:** if failure, preserve evidence and halt. Any deletion of the **newly created** disposable network/container (never the original or retained W2 V9) needs explicit reviewed cleanup scope. No automatic rerun; a subsequent attempt requires separate approval.
- **RESULT:** a fresh test provides *current recoverability evidence*, **not retroactive historical W2A provenance**.

The original W2A helper **creates Docker resources, writes SQL to a temporary database and is NOT safe to rerun under this read-only review**. This assessment does not approve preparing/executing that helper, a new restore, new backup, or resource cleanup.

## 5. Decision template for owner and independent reviewer

For a future **synthetic-only** original V8 migration review, owner may choose **after independent review**:

- **Option A (recommended):** `EX01_FRESH_DISPOSABLE_RESTORE_PROOF_REQUIRED`. Explicitly approve a separate reviewed one-shot procedure only for fresh isolated restore verification, capture private evidence, then have independent reviewer reassess recovery sufficiency. Retain W3-E HOLD meanwhile.
- **Option B:** `EX01_HISTORICAL_EVENT_LOG_MISSING_BUT_RESIDUAL_RISK_ACCEPTED_FOR_SYNTHETIC_ONLY`. Requires documented independent conclusion that earlier W2A owner-reported real restore + current W1 checks + current original data alignment + a practical recovery plan sufficiently mitigate loss risk. Owner **expressly accepts** absence of original transcript/transfer receipt; EX-01 historical provenance remains open, not fabricated/closed. The owner is **not** choosing Option B by saying "proceed" to this review.

**Reviewer finding:** `EX01_TECHNICAL_RESTORE_SUPPORT=STRONG_OWNER_REPORTED_W2A_PASS`; `EX01_CURRENT_W1_HASH_AND_ARCHIVE_TOC=PASS_OWNER_REPORTED`; `EX01_ORIGINAL_W2A_RAW_PROVENANCE=NOT_LOCATED`; `EX01_CURRENT_SOURCE_TO_W1_SNAPSHOT_ALIGNMENT=UNVERIFIED`; `EX01_RECOVERY_SIGNOFF=NOT_GRANTED`; `EX01_FRESH_RESTORE_AUTHORIZED=NO`.

**W3-H9C status:** `READ_ONLY_EX01_REVIEW_COMPLETED_WITH_OPEN_RECOVERY_ACCEPTANCE`.  
**W3-E owner decision:** `NO_GO_HOLD_APPROVED_2026-11-06`.  
**W3 owner GO:** `NOT_GRANTED`.  
**W4 original Apply:** `BLOCKED`.  
**PR #32:** `DRAFT/UNMERGED; NO MERGE`.  
**W2C re-execution:** `NOT_AUTHORIZED; PRIOR ONE-SHOT 1/1 USED`.  
**Customer/real-patient release:** `BLOCKED`.

No Docker, original/disposable DB, Flyway, backup or test execution occurred in the creation of this review. No code scripts, protected branch settings, PR merge state or patient/customer infrastructure were changed.
