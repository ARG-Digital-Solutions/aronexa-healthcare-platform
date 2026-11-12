# R1-08 exact-SHA Billing + Daily Collections candidate acceptance.
# Automatically destroyed Testcontainers PostgreSQL 17 ONLY.
# No live patient, money movement, tax invoice, refund or production migration.
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
if($LASTEXITCODE -ne 0 -or $head -ne $ExpectedCommit){
  throw "R1-08 source SHA mismatch. STOP."
}
$proof=Join-Path $repo "backend\src\test\resources\db\test\appointment-candidate-r108.sql"
if(-not(Test-Path -LiteralPath $proof)){
  throw "R1-08 disposable billing SQL proof not found."
}
Write-Host "R1-08: Basic OPD bill, fictional cash and daily collection report" -ForegroundColor Cyan
Write-Host "SourceCommit=$head"
Write-Host "Database=Automatically disposable Testcontainers PG17 ONLY"

$delegate=Join-Path $repo "scripts\Test-Opd-R1-07-Prescription-Increment.ps1"
& powershell.exe -NoProfile -File $delegate -ExpectedCommit $head
if($LASTEXITCODE -ne 0){
  throw "R1-08 HTTP/PG17, R1-07 predecessor or full app regression FAILED."
}

Push-Location (Join-Path $repo "frontend")
try{
  & npm.cmd run build:connected:billing:test
  if($LASTEXITCODE -ne 0){
    throw "R1-08 billing preview TypeScript/Vite build FAILED."
  }
}finally{Pop-Location}

Write-Host "R1-08 LOCAL CANDIDATE ACCEPTANCE PASSED (TEST ONLY)" -ForegroundColor Green
Write-Host "Independent QA Central R1-08 Chromium acceptance is a separate gate."
