# Exact-SHA R1-07 structured Smart Rx DRAFT candidate acceptance.
# Disposable PG17 Testcontainers only; never real patient/production database.
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)]
  [ValidatePattern('^[a-f0-9]{40}$')]
  [string]$ExpectedCommit
)
Set-StrictMode -Version Latest
$ErrorActionPreference="Stop"
$repo=(Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$head=(& git -C $repo rev-parse HEAD).Trim()
if($LASTEXITCODE -ne 0 -or $head -ne $ExpectedCommit) {
  throw "R1-07 exact-commit mismatch. STOP."
}
$proof=Join-Path $repo "backend\src\test\resources\db\test\appointment-candidate-r107.sql"
if(-not(Test-Path -LiteralPath $proof)) {
  throw "R1-07 disposable PostgreSQL proof missing."
}
Write-Host "R1-07: Smart Rx structured prescription draft, no finalization" -ForegroundColor Cyan
Write-Host "SourceCommit=$head"
Write-Host "Database=Disposable Testcontainers PG17 ONLY"
$delegate=Join-Path $repo "scripts\Test-Opd-R1-06-Consultation-Increment.ps1"
& powershell.exe -NoProfile -File $delegate -ExpectedCommit $head
if($LASTEXITCODE -ne 0) {
  throw "R1-07 real HTTP/PG17, frontend, doctor preview or full regression FAILED."
}
Write-Host "R1-07 LOCAL CANDIDATE ACCEPTANCE PASSED (TEST ONLY)" -ForegroundColor Green
Write-Host "Independent QA Central R1-07 Chromium verification remains separately required."
