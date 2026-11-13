# R1-10B1: exact-SHA safe registration handoff + appointment schema candidate.
# Tests REVIEW-ONLY schema exclusively inside disposable Testcontainers PG17.
# Does not expose a clinic booking endpoint or activate production migrations.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[a-fA-F0-9]{40}$')]
 [string]$ExpectedCommit
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'R1-10B1 exact SHA mismatch: STOP before running tests.'
}
$mustExist=@(
 'backend\src\main\java\com\argdigital\aronexa\opd\OpdRegistrationHandoffService.java',
 'backend\src\main\java\com\argdigital\aronexa\opd\OpdVisitContract.java',
 'backend\src\test\java\com\argdigital\aronexa\patient\RegistrationCandidateHttpAcceptanceTests.java',
 'backend\src\test\java\com\argdigital\aronexa\opd\OpdAppointmentSchemaCandidateTests.java',
 'deploy\opd\review-only\V11__opd_appointment_schema_candidate.sql'
)
foreach($item in $mustExist){
 if(-not(Test-Path -LiteralPath (Join-Path $repo $item) -PathType Leaf)){
  throw "R1-10B1 missing source contract or PG17 proof: $item"
 }
}
$defaultFlyway=Join-Path $repo 'backend\src\main\resources\db\migration'
if(Test-Path -LiteralPath (Join-Path $defaultFlyway 'V11__opd_appointment_schema_candidate.sql')){
 throw 'STOP: review-only V11 appointment schema was copied into active production Flyway.'
}
$maxV=@(Get-ChildItem -LiteralPath $defaultFlyway -Filter 'V*__*.sql' -File |
 ForEach-Object {if($_.Name -match '^V(\d+)__'){[int]$Matches[1]}} |
 Measure-Object -Maximum)
if([int]($maxV[0].Maximum) -ne 9){
 throw 'STOP: production Flyway V9 baseline changed without authorization.'
}
$policy=Get-Content -LiteralPath (Join-Path $repo 'backend\src\main\java\com\argdigital\aronexa\auth\AuthSecurityConfiguration.java') -Raw
if($policy -notmatch 'test & appointment-candidate & !dev & !prod' -or
   $policy -notmatch 'test & registration-candidate & !dev & !prod' -or
   $policy -notmatch '\.anyRequest\(\)\.denyAll\(\)'){
 throw 'STOP: candidate HTTP profile or default-deny security regressed.'
}
$review=Get-Content -LiteralPath (Join-Path $repo 'deploy\opd\review-only\V11__opd_appointment_schema_candidate.sql') -Raw
if($review -notmatch 'FORCE ROW LEVEL SECURITY' -or
   $review -notmatch 'uq_opd_candidate_live_doctor_slot' -or
   $review -notmatch 'fk_opd_candidate_registered_patient' -or
   $review -notmatch 'appointment_event'){
 throw 'STOP: reviewed SQL candidate lost required structural constraints.'
}
Write-Host 'R1-10B1 SOURCE GATES PASS: V9, review-only SQL, no booking API activation' -ForegroundColor Green

# One full Maven and frontend pass covers BOTH reconciled R1-10A branches,
# all existing R1-04..08 HTTP+disposable PG17 and six new SQL structural tests.
$previous=Join-Path $repo 'scripts\Test-Opd-R1-10A-Integration-Foundation.ps1'
& powershell.exe -NoProfile -File $previous -ExpectedCommit $sha
if($LASTEXITCODE -ne 0){throw 'R1-10B1 backend/frontend/disposable PG17 regression FAILED.'}

# Keep the release hard NO_GO gate alive after all source/test increments.
$preflight=Join-Path $repo 'scripts\release\Test-AroNexa-Windows-Pilot-Inspector.ps1'
& powershell.exe -NoProfile -File $preflight -ExpectedCommit $sha
if($LASTEXITCODE -ne 0){throw 'R1-10B1 Windows pilot NO_GO gate selftest FAILED.'}

Write-Host 'R1-10B1 INTEGRATION CANDIDATE ACCEPTANCE PASSED (TEST ONLY)' -ForegroundColor Green
Write-Host "SourceCommit=$sha"
Write-Host 'No production Flyway V10/V11, clinical booking endpoint, installer or release GO.'
