# AroNexa OPD local acceptance: registration + connected Reception (synthetic only).
# Never deploys, migrates persistent databases, changes branches or installs keys.
[CmdletBinding()]
param([string]$ExpectedCommit = "")

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$backendPom = Join-Path $repo "backend\pom.xml"
$frontend = Join-Path $repo "frontend"
if (-not (Test-Path $backendPom) -or -not (Test-Path (Join-Path $frontend "package.json"))) {
    throw "Run this script from an existing AroNexa repository worktree."
}

$head = (& git -C $repo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $head -notmatch "^[a-f0-9]{40}$") {
    throw "Could not verify source Git HEAD."
}
if ($ExpectedCommit -and $head -ne $ExpectedCommit) {
    throw "Wrong source revision. Checkout the approved exact SHA."
}
foreach ($name in @("git","node","npm.cmd","mvn.cmd","docker")) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "Required command missing: $name"
    }
}
$logRoot = Join-Path $env:USERPROFILE "AroNexa-Local-QA"
New-Item -Path $logRoot -ItemType Directory -Force | Out-Null
$tag = (Get-Date -Format "yyyyMMdd-HHmmss") + "-" + $head.Substring(0,10)
$runFolder = Join-Path $logRoot $tag
New-Item -Path $runFolder -ItemType Directory -Force | Out-Null
Write-Host "AroNexa OPD candidate QA, commit $head" -ForegroundColor Cyan
Write-Host "Logs: $runFolder"

function Invoke-LocalGate {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Directory,
        [Parameter(Mandatory=$true)][string]$Command,
        [Parameter(Mandatory=$true)][string[]]$Arguments
    )
    $log = Join-Path $runFolder (($Name -replace '[^A-Za-z0-9_-]','_') + ".log")
    Write-Host ""
    Write-Host "Running: $Name" -ForegroundColor Cyan
    Push-Location $Directory
    $previous = $ErrorActionPreference
    try {
        # Windows PowerShell may treat native stderr warnings as error
        # records. Preserve all output and let the actual process exit code
        # decide whether the gate passed.
        $ErrorActionPreference = "Continue"
        & $Command @Arguments *> $log
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
        Pop-Location
    }
    if ($exitCode -ne 0) {
        Write-Host "FAILED: $Name (exit $exitCode)" -ForegroundColor Red
        Write-Host "Log: $log"
        Get-Content $log -Tail 60
        throw "AroNexa local QA stopped at $Name"
    }
    Write-Host "PASSED: $Name" -ForegroundColor Green
}

Invoke-LocalGate -Name "frontend-npm-ci" -Directory $frontend -Command "npm.cmd" -Arguments @("ci","--no-audit","--no-fund")
Invoke-LocalGate -Name "frontend-build-and-unit-tests" -Directory $frontend -Command "npm.cmd" -Arguments @("run","build")
Invoke-LocalGate -Name "connected-reception-preview-build" -Directory $frontend -Command "npm.cmd" -Arguments @("run","build:connected:test")
Invoke-LocalGate -Name "candidate-http-node-wire" -Directory $repo -Command "mvn.cmd" -Arguments @("-B","-f",$backendPom,"-Dtest=RegistrationCandidateHttpAcceptanceTests","test")
Invoke-LocalGate -Name "full-backend-clean-verify" -Directory $repo -Command "mvn.cmd" -Arguments @("-B","-f",$backendPom,"clean","verify")

# The clean verify command removes stale earlier reports before producing XML.
$reports = Join-Path $repo "backend\target\surefire-reports"
$reportFiles = @(Get-ChildItem -Path $reports -Filter "TEST-*.xml" -ErrorAction Stop)
if ($reportFiles.Count -eq 0) {
    throw "Maven exited zero, but no Surefire XML was generated."
}
$tests = 0; $failures = 0; $errors = 0; $skipped = 0
foreach ($report in $reportFiles) {
    [xml]$document = Get-Content -LiteralPath $report.FullName -Raw
    $suite = $document.DocumentElement
    if ($null -eq $suite -or $suite.LocalName -ne "testsuite") {
        throw "Unexpected Surefire XML root in report $($report.Name)"
    }
    $tests += [int]$suite.GetAttribute("tests")
    $failures += [int]$suite.GetAttribute("failures")
    $errors += [int]$suite.GetAttribute("errors")
    $skipped += [int]$suite.GetAttribute("skipped")
}
$summaryFile = Join-Path $runFolder "SUMMARY.txt"
$summary = "SourceCommit=$head" + [Environment]::NewLine +
    "Tests=$tests" + [Environment]::NewLine +
    "Failures=$failures" + [Environment]::NewLine +
    "Errors=$errors" + [Environment]::NewLine +
    "Skipped=$skipped" + [Environment]::NewLine +
    "EvidenceFolder=$runFolder"
[System.IO.File]::WriteAllText($summaryFile,$summary)
Write-Host $summary
if ($tests -eq 0 -or $failures -gt 0 -or $errors -gt 0) {
    throw "Full backend JUnit XML contains failures/errors; see $summaryFile"
}
if ($skipped -gt 0) {
    Write-Warning "Some tests were skipped; confirm why before a release."
}
Write-Host "ARONEXA OPD REGISTRATION INCREMENT ACCEPTANCE PASSED" -ForegroundColor Green
Write-Host "Evidence: $summaryFile"
