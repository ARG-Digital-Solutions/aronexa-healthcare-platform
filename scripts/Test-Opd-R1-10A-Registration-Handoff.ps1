# R1-10A: SOURCE-OWNED read-only OPD registration to appointment handoff.
# Exact SHA + full existing R1-04..R1-08 disposable PostgreSQL regression
# and R1-09 Windows pilot hard NO-GO gate. No real appointment POST or
# production schema migration is allowed in this increment.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'

$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$actual=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $actual -ne $ExpectedCommit.ToLowerInvariant()) {
 throw 'R1-10A exact source SHA mismatch. STOP.'
}

$service=Join-Path $repo 'backend\src\main\java\com\argdigital\aronexa\opd\OpdRegistrationHandoffService.java'
$test=Join-Path $repo 'backend\src\test\java\com\argdigital\aronexa\patient\RegistrationCandidateHttpAcceptanceTests.java'
$test2=Join-Path $repo 'backend\src\test\java\com\argdigital\aronexa\patient\PatientAccessServiceIntegrationTests.java'
foreach($file in @($service,$test,$test2)){
 if(-not(Test-Path -LiteralPath $file -PathType Leaf)){
  throw 'R1-10A production-source service or acceptance test missing.'
 }
}

Write-Host 'R1-10A: Authorized registration to OPD booking handoff' -ForegroundColor Cyan
Write-Host "SourceCommit=$actual"
Write-Host 'Mode=Production-source internal READ ONLY service + test-only HTTP adapter'
Write-Host 'Database=Testcontainers disposable PostgreSQL 17 ONLY'
Write-Host 'Customer pilot=NO_GO'

$previous=Join-Path $repo 'scripts\Test-Opd-R1-08-Billing-Increment.ps1'
& powershell.exe -NoProfile -File $previous -ExpectedCommit $actual
if($LASTEXITCODE -ne 0){
 throw 'R1-10A full frontend/backend/real HTTP+PG17 acceptance FAILED.'
}

$readiness=Join-Path $repo 'scripts\release\Test-AroNexa-Windows-Pilot-Inspector.ps1'
& powershell.exe -NoProfile -File $readiness -ExpectedCommit $actual
if($LASTEXITCODE -ne 0){
 throw 'R1-10A pilot-release hard NO-GO inspector self-test FAILED.'
}

Write-Host 'R1-10A LOCAL CANDIDATE ACCEPTANCE PASSED (READ-ONLY, TEST DATA)' -ForegroundColor Green
Write-Host 'This is not a production booking API or customer installer.'
