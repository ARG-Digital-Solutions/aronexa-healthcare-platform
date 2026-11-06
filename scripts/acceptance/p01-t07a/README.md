# P01-T07-A — Canonical Windows V8 acceptance script archival

**Status: OWNER-UPLOADED FINAL SCRIPT COMMITTED IN DRAFT PR #26.** The owner-uploaded final VerifyNpmFixed source has been stored here, and its GitHub text was compared to the original uploaded text without modifications. SHA-256 of uploaded script: `2BCA93D6A83ACAB8F4071541E51B3990F277B103FECF5A5F47B042B49AEEB933`. The following copy instructions are retained as a reproducible alternative but are no longer required for this draft PR.

## Canonical script and provenance

Source (owner's Windows system):

`%USERPROFILE%\Downloads\AroNexa-P01-T07A-V8-Windows-Acceptance-Kit\Invoke-AroNexa-V8-Acceptance.VerifyNpmFixed.ps1`

Desired canonical repository path:

`scripts/acceptance/p01-t07a/Invoke-AroNexa-V8-Acceptance.ps1`

Import **the exact successfully executed script**, retaining its Prepare/Apply/Verify stage behavior, backup/manifest guards, migration protections, scoped Maven and npm stderr handling, genuine nonzero exit-code checks and preference restoration. No fresh reconstruction, installation policy bypass or script refactor is authorized.

## Optional local archival verification (do not execute migrations)

**The canonical script already exists on this branch. Do not run the copy block below against this branch: it intentionally stops when the destination exists.** The block is preserved for future use only when importing to a new clean repository checkout where the target is not yet present. To confirm identity today, compare `Get-FileHash -Algorithm SHA256` of the locally executed final script and the tracked canonical file; expect `2BCA93D6A83ACAB8F4071541E51B3990F277B103FECF5A5F47B042B49AEEB933` when the same file bytes are used.

```powershell
$ErrorActionPreference = 'Stop'
$repo = (git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0 -or -not $repo) { throw 'STOP: NOT_IN_GIT_REPOSITORY' }
$branch = (git branch --show-current).Trim()
if ($branch -ne 'docs/p01-t07a-v8-closure-evidence-20261106') { throw 'STOP: WRONG_BRANCH' }
if ((git status --porcelain).Count -gt 0) { throw 'STOP: WORKTREE_NOT_CLEAN' }
$source = Join-Path $env:USERPROFILE 'Downloads\AroNexa-P01-T07A-V8-Windows-Acceptance-Kit\Invoke-AroNexa-V8-Acceptance.VerifyNpmFixed.ps1'
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'STOP: VERIFIED_FINAL_SCRIPT_NOT_FOUND' }
$targetDir = Join-Path $repo 'scripts\acceptance\p01-t07a'
$target = Join-Path $targetDir 'Invoke-AroNexa-V8-Acceptance.ps1'
if (Test-Path -LiteralPath $target) { throw 'STOP: TARGET_ALREADY_EXISTS' }
New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
Copy-Item -LiteralPath $source -Destination $target -ErrorAction Stop
$srcHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
$dstHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
if ($srcHash -ne $dstHash) { throw 'STOP: COPY_HASH_MISMATCH' }
$tokens = $null
$parseErrors = $null
$null = [System.Management.Automation.Language.Parser]::ParseFile($target, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) { throw 'STOP: FINAL_SCRIPT_SYNTAX_INVALID' }
Write-Host "CANONICAL_SCRIPT_SHA256: $dstHash"
Write-Host 'FINAL_STATUS: EXACT_FINAL_SCRIPT_COPIED_FOR_REVIEW'
```

This performs **no execution of the acceptance script itself**, no Maven/npm calls and no database operations. It requires an existing local checkout on the draft PR branch; do not force-switch, reset or overwrite anything.

## Review before staging / committing

1. Inspect every line for credentials, secrets, token values, private endpoints, machine-specific absolute paths, patient identifiers and data. If unsafe, STOP rather than commit the file.
2. Check that the expected safety gates remain: full stage routing, mandatory separately authorized Apply gates, original database backup check, evidence/manifest validation, PowerShell 5.1 compatibility, genuine nonzero Maven/npm exit checks, restored error-action settings. A mere syntax pass does not prove all safety gates.
3. Use `git diff --no-index -- /dev/null scripts/acceptance/p01-t07a/Invoke-AroNexa-V8-Acceptance.ps1` only where supported; alternatively inspect the file in a text editor. Check repo secret scan and `git diff --check` after staging.
4. Stage **only** the intended canonical `.ps1` and appropriate documentation, commit to the draft branch, push, and review changed files in the PR. Record the actual SHA-256 in the PR/evidence index (without the full local path).
5. Keep database dumps, manifest values, full private logs, credentials, environment files and customer/patient data OUT OF GIT.

## Review / release limits

- `-Stage Verify` was already reported PASS by the owner using the locally executed final script; **do not rerun Verify, Apply or Flyway merely to archive files**.
- No broad npm install-script approvals, no Mockito-agent dependency change, no production settings or application source changes are authorized.
- Documentation PR must remain DRAFT and unmerged until code review of the committed exact final script (including historical Apply manifest-property bug), private evidence archive inventory and SHA confirmed, and owner explicitly approves task closure and PR merge.
- This is INTERNAL_FOUNDATION only. P01-T07-B, full Phase 01 approval, customer use and deployment are NOT authorized.

See [closure review](../../../docs/phases/P01_T07A_CLOSURE_REVIEW_2026-11-06.md) and [evidence index](../../../docs/phases/evidence/P01_T07A_20261106/EVIDENCE.md).
