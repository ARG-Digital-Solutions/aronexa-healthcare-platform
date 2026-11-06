# P01-T07-A — Curated V8 evidence index (2026-11-06 IST)

**Provenance:** Owner-supplied Windows PowerShell/SQL outputs transcribed for reviewer convenience. Neither original Windows reports nor database backup were independently fetched or re-executed by the assistant. No confidential materials are included in this Git document.

## E01 — Git baseline
- PR #25 merged: https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/pull/25
- Owner-verified main SHA: `4f5926a883506437bf7131b792ee39fd8c7579da`.
- Schema-only P01-T07-A. No authority to start P01-T07-B.

## E02 — Pre-V8 backup
- Basename: `aronexa_dev_pre_v8.dump`
- Size: 58,043 bytes (reported).
- SHA-256: `E2B7CD2E6CA1C5198AD43536B0EA53884FCC2F491F2202D274B2CDB9C6D58A3B` (reported).
- Original backup remains private; no binary/DB backups committed.

## E03 — Prepare, original Apply, evidence-only finalization
- Prepare reported PASS: checksum/backup, disposable V7 restore, V8 rehearsal, 18 earlier table counts, RLS and no runtime patient grants.
- Original Apply reported Flyway version 8, zero failed migrations, V8 success, 6 patient tables, preserved counts; **migration succeeded**.
- Historical Apply script then exited on a missing `applied_at` property during manifest serialization, **not a migration failure**. Apply was not rerun.
- Separately authorized evidence-only finalizer reported PASS; `DATABASE_MIGRATION_EXECUTED: NO`, `ORIGINAL_BACKUP_MODIFIED: NO`; evidence finalization report basename `EVIDENCE_ONLY_FINALIZATION_REPORT_20261106_001741.txt`.
- Earlier manifest retained privately as a pre-finalization archive.

## E04 — Windows Verify history
1. Original Verify was blocked by Mockito inline-mock warning as PowerShell 5.1 native stderr error.
2. First scoped Maven wrapper correction: `BUILD SUCCESS`; Verify then blocked on `npm warn install-scripts 1 package has install scripts not yet covered by allowScripts:`.
3. Second scoped npm wrapper correction passed creation/syntax/scope checks; preserves genuine nonzero exit-code blocking, restores error preference; does not approve npm install scripts.
4. Owner's final successful read-only Verify used the exact locally retained `Invoke-AroNexa-V8-Acceptance.VerifyNpmFixed.ps1`. This script is now included in the draft PR as `scripts/acceptance/p01-t07a/Invoke-AroNexa-V8-Acceptance.ps1` with uploaded SHA-256 `2BCA93D6A83ACAB8F4071541E51B3990F277B103FECF5A5F47B042B49AEEB933`; exact GitHub text was compared against the uploaded file.
5. Old BLOCKED lines appear in append-only `VERIFY_REPORT.txt` and must not be mistaken for the final attempt.

## E05 — Backend tests
- `TEST_REPORT_FILES: 11`
- `TESTS: 71`
- `TEST_FAILURES: 0`
- `ERRORS: 0`
- `SKIPPED: 0`
- `REPORT_PARSE_ERRORS: 0`
- Maven private log timestamp (owner's local machine): 2026-11-06 01:16:10 IST.
- `MAVEN_BUILD_SUCCESS_MARKERS: 1` and `MAVEN_BUILD_FAILURE_MARKERS: 0`.
- CI on GitHub: not independently verified in this review.

## E06 — Latest final Verify excerpt

```text
PASS: clean AroNexa main includes PR #25; required Docker images present
PASS: development Compose database and restricted roles identified
PASS: pinned Git, migration SQL, original container, backup bytes/hash and DB version
PASS: Flyway V8, six patient tables, FORCE RLS, no runtime grants, no patient records
PASS: all 18 pre-existing table counts preserved (read-only original verification)
BEGIN: Maven verify against isolated Testcontainers (not original dev DB)
PASS: Windows Maven verify
PASS: Windows npm ci and frontend production build
FINAL_STATUS: READ_ONLY_V8_VERIFICATION_PASSED; OWNER_CLOSURE_APPROVAL_STILL_REQUIRED
```

## E07 — Open private archival inventory
Privately retain and hash (do NOT upload backups, credentials, identifiable records or raw logs to Git):
- `ACCEPTANCE_MANIFEST.json` and preserved pre-finalization manifest.
- `PREPARE_REPORT.txt`, `APPLY_REPORT.txt`, `VERIFY_REPORT.txt`.
- `EVIDENCE_ONLY_FINALIZATION_REPORT_20261106_001741.txt`.
- `maven-private.log`, `npm-ci-private.log`, `npm-build-private.log` after sensitive-content inspection.
- Original pre-V8 database dump, separately restricted.
- Actual final `Invoke-AroNexa-V8-Acceptance.VerifyNpmFixed.ps1`: RECEIVED and COMMITTED to **draft PR #27** under the canonical path; uploaded SHA-256 `2BCA93D6A83ACAB8F4071541E51B3990F277B103FECF5A5F47B042B49AEEB933`.
- **Owner local SHA corroboration (2026-11-06 IST):** owner supplied a `Get-FileHash -Algorithm SHA256` result for the final Windows `VerifyNpmFixed.ps1` with the same SHA-256 above. Independently computed upload SHA-256 and GitHub text comparison also match. The backup ZIP and private log checksums remain pending.
- Private closure ZIP inventory and SHA-256: **owner-confirmed** in E10 below; no independent ZIP content review.
- Known Apply PSCustomObject `$m.applied_at` assignment bug retained in archival script. No re-execution of Apply is permitted to address it; future repair needs separate authorization and regression review.

## E08 — Owner approval
Technical V8 verification: PASS from supplied evidence.
Formal task closure: **not yet approved**.
Customer pilot, production, clinical access, full Phase 01 completion and P01-T07-B: **not authorized**.

## E09 — Windows inventory confirmation (owner-reported, 2026-11-06 IST)

Owner ran the read-only private evidence inventory check and supplied:

```text
EXPECTED_FILES: 9
MISSING_FILES: 0
FINAL_STATUS: PRIVATE_EVIDENCE_INVENTORY_CHECKED
```

Inventory covers the manifest, Prepare/Apply/Verify reports, evidence-only finalization report, Maven/npm private logs and the original pre-V8 backup. **File existence is confirmed by owner output; completeness of contents and a new closure ZIP checksum remain OPEN.** The backup must stay private and must not enter Git.

## E10 — Private closure ZIP archive (owner-reported, 2026-11-06 IST)

The owner completed private evidence archive creation and provided the following exact results:

```text
ARCHIVE: P01-T07A-Closure-Evidence-20261106_072337.zip
ARCHIVE_SHA256: B593192A6BFEBBCD84CF243E47F48B22C90095FBEDE9CCEC5B5AA14E3DDFCE5E
ARCHIVED_EVIDENCE_FILES: 8
ZIP_TOTAL_ENTRIES: 10
MISSING_EXPECTED_REPORTS: 0
ORIGINAL_BACKUP_HASH: PASS
DATABASE_MODIFIED: NO
FINAL_STATUS: PRIVATE_CLOSURE_ARCHIVE_CREATED_AND_INVENTORY_VERIFIED
```

The 10 ZIP entries comprise 8 evidence files plus `SHA256SUMS.txt` and `ARCHIVE_NOTES.txt`. The ninth previously inventoried source file, the original `aronexa_dev_pre_v8.dump`, is intentionally **excluded from the ZIP** and remains separately preserved and hash-checked. The archive remains private on the owner's Windows workstation; neither the ZIP contents nor its SHA-256 were independently recomputed by the assistant. Do **not** commit the ZIP, database dump, .env, manifest contents, or raw private logs to Git.

**Administrative archive gate:** PASS from owner-reported file inventory, ZIP structure and checksum; independent content/privacy audit NOT RUN.

**Technical verification:** PASS (owner-supplied); **formal P01-T07-A owner closure approval:** still PENDING. Retained Apply-manifest defect, Mockito Java-agent warning and npm install-script policy warning remain disclosed.

## E11 — Explicit formal owner closure approval (2026-11-06 IST)

Owner explicitly approved **formal P01-T07-A closure for INTERNAL_FOUNDATION only**, acknowledging documented limitations and instructing that approval be recorded in GitHub PR #27 **without merging PR #27** or starting **P01-T07-B**. Decision recorded in [P01_T07A_OWNER_APPROVAL_2026-11-06.md](../../P01_T07A_OWNER_APPROVAL_2026-11-06.md). This E11 record supersedes the historical PENDING approval state in E08/E10 without changing technical evidence provenance.

**Task-level state: OWNER_ACCEPTED / CLOSED for INTERNAL_FOUNDATION.** PR #27 state: draft and unmerged, separate merge approval required. No Phase 01 overall closure, customer/clinical deployment or patient access authorized. No Apply or migration rerun.
