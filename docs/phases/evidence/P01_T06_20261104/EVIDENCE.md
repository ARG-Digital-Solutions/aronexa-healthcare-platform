# P01-T06 evidence archive — 2026-11-04 (IST)

This is a curated transcription of results supplied by the owner in the current conversation, not raw log files retrieved from Windows. Source machine execution is owner-supplied; the assistant did not execute these runtime tests. No passwords, .env contents, backup archive, or patient records are included.

## E01 Backup and migration identity
Backup filename: aronexa_dev_pre_v7_20261103_010747_4e5633f1.dump
Size: 47501 bytes (owner Get-Item output).
SHA-256: CB427DA8FE217F00C3B144AAD44FF638FB4DBBED4231B255047D0928886386CE
V7 SQL SHA-256: E746F449D4D3CE57EA1FFBF9DD7EF9B36FCEA75C13D0460D5A933278A2630156
Original container: aronexa-dev-postgres-1
Original volume: aronexa-dev_aronexa_dev_postgres_data
Original database/user: aronexa_dev.
Fresh read-only history showed six successful V1-V6 entries before migration.

## E02 Disposable rehearsal
Owner's report: C:\Users\ganes\AroNexa-Backups\P01-T06-Evidence\P01-T06-V7-Disposable-Rehearsal-20261103_232608.txt
Earlier successful report: P01-T06-V7-Disposable-Rehearsal-20261103_063435.txt.
Supplied result lines:
```text
PASS: VERIFIED_ORIGINAL_BACKUP_SHA256_AND_SIZE
PASS: SOURCE_V6_SNAPSHOT_CAPTURED_READ_ONLY
PASS: ISOLATED_INTERNAL_DOCKER_NETWORK_CREATED
PASS: DISPOSABLE_RAM_BACKED_POSTGRES_CREATED_NO_HOST_PORTS
PASS: DISPOSABLE_ARCHIVE_CHECKSUM_AND_STRUCTURE_VALID
PASS: V6_RESTORED_AND_ALL_16_TABLE_COUNTS_MATCH
PASS: FLYWAY_V7_MIGRATE_DISPOSABLE_ONLY
PASS: FLYWAY_V1_TO_V7_VALIDATION_SUCCESS
PASS: ALL_15_PREEXISTING_FOUNDATION_TABLE_COUNTS_PRESERVED
PASS: SEVEN_MODULES_ALL_UNRELEASED_NO_TENANT_ENTITLEMENTS
PASS: MODULE_RLS_RESTRICTED_ROLE_GRANTS_AND_AUDIT_TRIGGERS
PASS: REAL_SQL_RLS_TWO_TENANTS_DEACTIVATION_HISTORY_AND_ROLLBACK
PASS: SOURCE_DATABASE_STILL_V6_UNCHANGED_AND_BACKUP_UNMODIFIED
PASS: DISPOSABLE_POSTGRES_CONTAINER_REMOVED
PASS: DISPOSABLE_INTERNAL_NETWORK_REMOVED
FINAL_STATUS: DISPOSABLE_V6_TO_V7_MIGRATION_REHEARSAL_PASSED
```
The rollback assertion concerns synthetic transaction fixtures. V6 restore into a disposable database is demonstrated; post-V7 operational cutover back to a restored database was NOT RUN.

## E03 Original database migration
Supplied Flyway 13.12.0 / PostgreSQL 17.11 output:
```text
Current version of schema "public": 6
Migrating schema "public" to version "7 - module registry and tenant entitlements"
Successfully applied 1 migration to schema "public", now at version v7
Successfully validated 7 migrations
PASS: ORIGINAL_DATABASE_V7_VALIDATED
PASS: ALL_15_FOUNDATION_TABLE_COUNTS_PRESERVED
PASS: SEVEN_MODULES_DISABLED_NO_ENTITLEMENTS
```
Counts alone do not establish row-by-row equality. No clinical-record preservation claim is made.

## E04 Windows preflight/database
Local sanitized reports:
- AroNexa-P01-Windows-Preflight-20261104-001458.txt
- AroNexa-P01-Windows-Database-20261104-002804.txt
Located under C:\Users\ganes\AppData\Local\Temp.
Reported main @ adc8e98, clean working tree, Java/Maven 21, Node v24.21.0.
```text
PASS | Credentials and Compose syntax | Three distinct local passwords supplied; values not reported
PASS | Runtime-role credential authentication | Both restricted database logins succeeded
INFO | Flyway version | 7
PASS | Flyway schema | V7 applied
PASS | Cross-role privilege denial | Organization cannot SELECT app_user; authentication cannot SELECT branch
```
Acceptance copy was updated for V7 and stdin-based login queries after Windows shell/output handling caused a false mismatch. Both direct TCP runtime logins separately returned expected identities and exit code 0. Credential rotation was performed by the owner; secrets are excluded.

## E05 Windows runtime
Local report: AroNexa-P01-Windows-Verify-20261104-003736.txt in the same Temp directory.
```text
PASS | Anonymous /auth/me | HTTP 401
PASS | Invalid synthetic login | HTTP 401
PASS | Vite frontend page | HTTP 200; visually inspect in browser for scaffold
```
Command: powershell.exe -NoProfile -ExecutionPolicy Bypass -File <V7-LoginFixed checker> -Stage Verify -RepoPath <repository root>.

## E06 Windows regression/build
Local report: AroNexa-P01-Windows-Tests-20261104-004233.txt in the same Temp directory.
Commands: mvn -B -f backend/pom.xml verify; npm.cmd ci --no-audit --no-fund; npm.cmd run build.
```text
BUILD SUCCESS
Total time: 50.327 s
Finished at: 2026-11-04T00:43:26+05:30
PASS | Maven verify | Command passed; inspect backend/target/surefire-reports for exact counts
> tsc --noEmit && vite build
vite v7.3.7 building client environment for production...
29 modules transformed.
PASS | npm ci / Vite production build | Both commands passed
```
Exact test totals and skipped-test counts were not supplied. A separate npm ci reported 0 vulnerabilities; no current full dependency/security scan is inferred. esbuild install-script approval warning remained non-blocking.

## E07 Desktop visual review
Owner supplied screenshot image(20261103-192020).png in this conversation; visual review found readable navy/teal scaffold at 127.0.0.1:5173 with no obvious desktop layout issue. Screenshot remains in the conversation; not copied into this repository archive.
Visible stale badge: "Phase 01 · Task 01 — Under review".
Tablet/accessibility/clinical workflows were NOT RUN.

## Archive completeness
This repository archives the curated evidence text and its SHA-256 manifest. Raw Windows reports, detailed test XML and original screenshot bytes have not been copied here or hashed by the assistant. Preserve originals privately with the collection script; attach only reviewed sanitized results to the closure review.
