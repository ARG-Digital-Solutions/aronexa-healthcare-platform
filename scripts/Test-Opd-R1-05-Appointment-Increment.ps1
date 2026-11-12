# R1-05 exact-SHA acceptance for real HTTP/PostgreSQL appointment management.
# Reuse the established R1-04 disposable Testcontainers test gate.
# No production migration, real clinic data, protected-branch changes or deploys.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[a-f0-9]{40}$')]
    [string]$ExpectedCommit
)
Set-StrictMode -Version Latest
$ErrorActionPreference="Stop"
$repo=(Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$sha=(& git -C $repo rev-parse HEAD).Trim()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit){
    throw "R1-05 wrong revision. Test only the exact owner-selected Git SHA."
}
$required=Join-Path $repo "backend\src\test\resources\db\test\appointment-candidate-r105.sql"
if(-not (Test-Path -LiteralPath $required)){
    throw "R1-05 isolated SQL proof missing; stop."
}
Write-Host "R1-05: Appointment reschedule, cancellation and daily queue" -ForegroundColor Cyan
Write-Host "SourceCommit=$sha"
Write-Host "PostgreSQL is automatically disposable only."
$delegate=Join-Path $repo "scripts\Test-Opd-R1-04-Appointment-Increment.ps1"
& powershell.exe -NoProfile -File $delegate -ExpectedCommit $sha
if($LASTEXITCODE -ne 0){
    throw "R1-05 focused real HTTP PG17 or full frontend/backend regression FAILED."
}
Write-Host "R1-05 LOCAL CANDIDATE ACCEPTANCE PASSED (TEST ONLY)" -ForegroundColor Green
Write-Host "Browser acceptance is a separate opt-in QA Central R1-05 check."
