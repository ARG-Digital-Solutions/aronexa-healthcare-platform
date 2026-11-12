# R1-01: test-only V9 -> proposed V10 rehearsal on disposable Testcontainers PG17.
# NEVER applies review-only SQL to customer, Windows or persistent development DB.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$ExpectedCommit
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$pom = Join-Path $repo "backend\pom.xml"
$head = (& git -C $repo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $head -ne $ExpectedCommit) {
    throw "Wrong Git revision. Check out the exact R1-01 source commit."
}
if (-not (Test-Path -LiteralPath $pom)) { throw "Missing AroNexa backend/pom.xml." }
foreach ($tool in @("mvn.cmd","docker","java")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Missing local prerequisite: $tool"
    }
}
$root = Join-Path $env:USERPROFILE "AroNexa-Local-QA"
$folder = Join-Path $root ((Get-Date -Format 'yyyyMMdd-HHmmss') + "-r101-" +
    $head.Substring(0,10))
New-Item -ItemType Directory -Path $folder -Force | Out-Null
Write-Host "R1-01 synthetic V9 -> V10 rehearsal only" -ForegroundColor Cyan
Write-Host "Git SHA: $head"
Write-Host "Evidence: $folder"

function Run-Gate {
    param([string]$Gate,[string[]]$Args)
    $log = Join-Path $folder ($Gate + ".log")
    Write-Host "Running: $Gate" -ForegroundColor Cyan
    $old = $ErrorActionPreference
    try {
        # Warnings on native stderr must not masquerade as failed Maven exits.
        $ErrorActionPreference = "Continue"
        Push-Location $repo
        try {
            & mvn.cmd @Args *> $log
            $exit = $LASTEXITCODE
        } finally {
            Pop-Location
        }
    } finally { $ErrorActionPreference = $old }
    if ($exit -ne 0) {
        Write-Host "FAILED: $Gate, exit $exit" -ForegroundColor Red
        Write-Host "Log: $log"
        Get-Content -LiteralPath $log -Tail 65
        throw "R1-01 verification failed. No approval or database promotion permitted."
    }
    Write-Host "PASSED: $Gate" -ForegroundColor Green
}

Run-Gate "v10-disposable-upgrade-and-restore" @(
    "-B","-f",$pom,
    "-Dtest=RegistrationV10DisposableUpgradeRehearsalTests","test"
)
Run-Gate "full-backend-clean-verify" @("-B","-f",$pom,"clean","verify")

$reports = Join-Path $repo "backend\target\surefire-reports"
$files = @(Get-ChildItem -Path $reports -Filter "TEST-*.xml" -ErrorAction Stop)
if ($files.Count -eq 0) { throw "No fresh Surefire XML after clean verify." }
$total = 0; $failed = 0; $errors = 0; $skipped = 0
foreach ($file in $files) {
    [xml]$doc = Get-Content -LiteralPath $file.FullName -Raw
    $suite = $doc.DocumentElement
    if ($null -eq $suite -or $suite.LocalName -ne "testsuite") {
        throw "Unexpected test XML format."
    }
    $total += [int]$suite.GetAttribute("tests")
    $failed += [int]$suite.GetAttribute("failures")
    $errors += [int]$suite.GetAttribute("errors")
    $skipped += [int]$suite.GetAttribute("skipped")
}
$summary = @(
    "SHA=$head",
    "Tests=$total",
    "Failures=$failed",
    "Errors=$errors",
    "Skipped=$skipped",
    "Scope=Disposable PostgreSQL 17 only; NO PRODUCTION APPROVAL",
    "EvidenceFolder=$folder"
) -join [Environment]::NewLine
$summaryPath = Join-Path $folder "SUMMARY.txt"
[System.IO.File]::WriteAllText($summaryPath,$summary)
Write-Host $summary
if ($total -eq 0 -or $failed -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
    throw "R1-01 full backend results do not meet acceptance (no skips allowed)."
}
Write-Host "R1-01 DISPOSABLE MIGRATION REHEARSAL PASSED" -ForegroundColor Green
Write-Host "Not approved for production migration or customer data."
Write-Host "Evidence: $summaryPath"
