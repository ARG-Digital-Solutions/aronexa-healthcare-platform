# R1-06 local doctor consultation acceptance on exact isolated Git SHA.
# Runs only automatically destroyed Testcontainers PG17 and test-only API.
# Never deploys, migrates customer DBs, enables a production route or uploads notes.
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
    throw "R1-06 exact-SHA mismatch. STOP."
}
$proof=Join-Path $repo "backend\src\test\resources\db\test\appointment-candidate-r106.sql"
if(-not (Test-Path -LiteralPath $proof)){
    throw "R1-06 disposable consultation SQL proof missing."
}
Write-Host "R1-06: Doctor consultation draft and revision recovery" -ForegroundColor Cyan
Write-Host "SourceCommit=$head"
Write-Host "Database=Disposable Testcontainers PG17 ONLY"

$delegate=Join-Path $repo "scripts\Test-Opd-R1-05-Appointment-Increment.ps1"
& powershell.exe -NoProfile -File $delegate -ExpectedCommit $head
if($LASTEXITCODE -ne 0){throw "R1-06 focused HTTP/PG17 or full regression failed."}

Push-Location (Join-Path $repo "frontend")
try{
    & npm.cmd run build:connected:doctor:test
    if($LASTEXITCODE -ne 0){throw "R1-06 doctor preview TypeScript/Vite build failed."}
}finally{Pop-Location}

Write-Host "R1-06 LOCAL CANDIDATE ACCEPTANCE PASSED (TEST ONLY)" -ForegroundColor Green
Write-Host "Independent mocked Chromium QA remains separately required."
