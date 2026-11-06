# P01-T07-B2-MIG — Controlled Windows V8 to V9 development migration

**Separate authorization:** https://github.com/ARG-Digital-Solutions/aronexa-healthcare-platform/issues/31

**Status:** OWNER AUTHORIZED for W0 read-only preflight, W1 verified backup and W2 disposable rehearsal. Original Apply (W4) requires W3 evidence review and explicit owner GO. **No migration on the original Windows database has been run.**

Only the owner-confirmed local synthetic PostgreSQL 17 database named aronexa_dev, Docker Compose project aronexa-dev, currently at Flyway V8 is in scope. No customer, cloud, production or real-patient data.

## W0: read-only preflight (authorized)

From a reviewed clean checkout of feature/p01-t07b-patient-onboarding, in Windows PowerShell 5.1:

~~~powershell
$script = ".\scripts\acceptance\p01-t07b2\Invoke-AroNexa-V9-ReadOnlyPreflight.ps1"
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $script).Path,[ref]$tokens,[ref]$parseErrors)
if ($parseErrors.Count -ne 0) { throw 'STOP: Script syntax invalid' }
powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script -RepoPath (Get-Location).Path -ConfirmSyntheticDevelopmentOnly
~~~

Read the script before executing: it has **not** been independently Windows-tested. It must print READ_ONLY_V8_PREFLIGHT_PASSED before advancing. It checks the single source container, exact DB, PG17, Flyway V8, existing restricted roles, absence of the new patient role, six empty patient tables, clean working tree and exact reviewed V9 Git blob. It performs **no** role change, backup, restore, startup, Docker write or Flyway call.

STOP for any source/branch/blob mismatch, Flyway version other than V8, unexpected role, patient data, dirty worktree or unknown container. Record sanitized output and the reported V9 SHA256.

## W1: private verified backup (authorized, not executed)

A Windows operator must use the W0-identified source container only. Do not start the Spring application from the V9 branch (its Flyway auto-migrate would run against the original DB).

1. Record PostgreSQL version, Flyway history, SQL role flags and row counts for **all** V8 public application tables with read-only SQL. Capture only counts, no names, credentials or data.
2. Make a unique private backup directory OUTSIDE the repository. Check free space. Execute a consistent PostgreSQL 17 **custom-format pg_dump** of original aronexa_dev, retaining ownership/ACL metadata. Do not use --no-acl or --no-owner in the archival backup intended for faithful restore.
3. Copy the dump from source container to the private Windows directory without overwriting another backup. Verify nonzero byte size and matching SHA-256 for the source-container dump and copied Windows file.
4. Run PostgreSQL 17 pg_restore --list on the stored dump and require zero exit code. Record dump basename, bytes, both SHA-256 hashes, inventory status, Git commit, V9 file SHA256, table-count summary.
5. Retain the original backup separately from sanitized test/evidence ZIPs. Never commit/upload dumps, .env, credentials, session tokens, unredacted SQL logs or patient records.

This stage must not modify original SQL, roles, Flyway history or volumes. No down -v, restore into original, clean/repair, or replay of the known-defective V8 Apply script.

## W2: isolated disposable V8-to-V9 rehearsal (authorized, not executed)

1. Pull or verify pinned PostgreSQL 17 and Flyway 13.12.0 images in advance. Create an **internal-only, uniquely identified** Docker network and disposable PostgreSQL 17 container with sufficient temporary storage and **no host ports**. Never mount or join the original named development volume.
2. Bootstrap the two existing restricted SQL role names (aronexa_app_runtime and aronexa_auth_runtime) with **distinct disposable credentials** and the same nonowner/NOBYPASSRLS flags before restoring: V8 RLS policies and ACLs depend on those role names. The isolated migrator/database owner is aronexa_dev.
3. Restore the exact SHA-verified W1 dump into a fresh rehearsal database with pg_restore --exit-on-error, preserving ACLs and ownership. Verify original Flyway V8 history, preserved V1–V8 checksums, all pre-rehearsal row counts and source role privileges. Abort if any differ.
4. Bootstrap the **new** aronexa_patient_runtime role only in disposable rehearsal (restricted nonowner, non-superuser, non-BYPASSRLS login; unique ephemeral password). Ensure it has no unexpected inheritance or additional grants.
5. Use the reviewed V9 file and a pinned Flyway CLI against the isolated network. Run validate, migrate once (expected V8 -> V9), then validate. Require V9 current and no failed migration. Do not baseline, repair or clean.
6. Recheck all 25 pre-V9 table counts with the **explicit V9 seed changes**: `module_registry` must gain exactly **1** row (disabled PATIENT_REGISTRATION); `auth_permission` must gain exactly **6** rows (ungranted patient permission codes); `flyway_schema_history` must gain exactly **1** row for V9; the existing `module_configuration_audit` table must gain exactly **1** trigger-created row attributable to `PATIENT_REGISTRATION` with all previous audit rows preserved, and the other **21** original tables must preserve their exact counts. The new `patient_registration_access_audit` table must exist and be empty (26 tables in total). Confirm the patient module is unreleased/disabled, no tenant entitlements activated, patient runtime has only allowed read/audit-insert grants, app/auth roles still cannot read patient tables, immutable audit and FORCE RLS retained.
7. Test no-context, tenant-only, wrong membership, unassigned branch, other tenant, transaction pool reuse and disallowed patient write/read operations with synthetic data. Save sanitized evidence and a SHA-256 inventory.
8. Remove **only** named disposable rehearsal resources after verifying the target is not the original container, network or volume; retain the W1 backup.

A fresh empty-database CI PASS **does not replace** this restored-original V8 rehearsal.

## W3: formal evidence review and GO/NO-GO (not cleared)

Before original migration, attach sanitized results to issue #31 and confirm:

- [ ] W0 source/branch/Git blob/Flyway V8 checks PASS.
- [ ] W1 independent source and copied host SHA-256 match; backup size > 0; pg_restore --list PASS; verified private retention.
- [ ] W2 original V8 restore & ACLs/counts match; V9 validate/migrate/validate PASS; all new permission/RLS/audit/disabled-module checks PASS.
- [ ] Exact V9 file SHA, scripts, PostgreSQL/Flyway tool versions and source PR #30 state reviewed.
- [ ] Owner records explicit GO on #31 based on evidence. **Without GO, W4 is prohibited.**

## W4: original V8-to-V9 migration (conditional / NOT RUN)

Only after recorded W3 GO, a separately reviewed original-Apply procedure must stop writers, re-identify the original container, recheck V8 history, roles, row counts and backup SHA, then non-destructively provision the *new patient role* with a unique protected credential. Run exactly one forward-only V9 Flyway migration with verified source and validate to V9; repeat non-destructive counts, role/RLS and disabled module checks. No automatic retries, drop/restore, initdb reruns, V1–V8 edits, Flyway clean/repair or old V8 Apply helper.

This runbook deliberately does **not** contain an automatic original-database Apply command. Until W3 GO, original W4 writes remain blocked.

## W5: acceptance/closure (conditional / NOT RUN)

Perform read-only Flyway validation, Windows backend/frontend smoke and full regression with synthetic data, restored-count comparisons, secrets/permissions checks and private evidence inventory/hash. Obtain separate migration task closure approval; B3 patient creation, PR #30 merge, full Phase 01, customer deployment and real-patient use remain separately gated.

**Current outcome:** W0–W2 authorized but not executed here; W3 GO/NO-GO pending; W4 and W5 not executed. GitHub CI confirms **fresh** V9 only, not upgraded original Windows V8.
