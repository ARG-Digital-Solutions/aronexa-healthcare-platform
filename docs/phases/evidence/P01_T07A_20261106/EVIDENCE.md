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
- Archive ZIP file SHA-256 and inventory status (still unconfirmed).
- Known Apply PSCustomObject `$m.applied_at` assignment bug retained in archival script. No re-execution of Apply is permitted to address it; future repair needs separate authorization and regression review.

## E08 — Owner approval
Technical V8 verification: PASS from supplied evidence.
Formal task closure: **not yet approved**.
Customer pilot, production, clinical access, full Phase 01 completion and P01-T07-B: **not authorized**.
