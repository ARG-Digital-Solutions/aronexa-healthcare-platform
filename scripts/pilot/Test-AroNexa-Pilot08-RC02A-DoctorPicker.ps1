# PILOT-08 RC02A — review-gated clinician directory + parent RC01 regression.
# Fictional only, no original Windows/customer DB. No production activation.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit,
 [string]$QaCentralPath=(Join-Path $env:USERPROFILE 'Application Development\AroNexa-QA-Central')
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'PILOT08 RC02A STOP: exact source commit mismatch.'
}
$dirty=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($dirty | Where-Object {$_.Trim()}).Count -ne 0){
 throw 'PILOT08 RC02A STOP: tracked source is not clean.'
}
foreach($key in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
 'SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE',
 'ARONEXA_OPD_DIRECTORY_REVIEWED_ACTIVATION',
 'ARONEXA_PILOT05_PORT','ARONEXA_PILOT05_RUN_ID',
 'ARONEXA_PILOT06_BROWSER','ARONEXA_PILOT06_QA_ROOT')){
 if([Environment]::GetEnvironmentVariable($key)){
  throw ('PILOT08 RC02A STOP: inherited datasource/profile/activation variable '+$key)
 }
}
$mvn=Get-Command mvn.cmd -ErrorAction SilentlyContinue
$docker=Get-Command docker.exe -ErrorAction SilentlyContinue
if($null -eq $mvn -or $null -eq $docker){
 throw 'PILOT08 RC02A STOP: Maven and Docker are required.'
}
& $docker.Source info '--format' '{{.ServerVersion}}'
if($LASTEXITCODE -ne 0){throw 'PILOT08 RC02A STOP: Docker unavailable.'}
Write-Host 'RC02A stage 1: real HTTP doctor directory with branch/module/role negative cases'
Push-Location (Join-Path $repo 'backend')
try {
 & $mvn.Source '-Dtest=AppointmentCandidateHttpAcceptanceTests#pilot08Rc02aDoctorDirectoryRequiresLiveBranchRoleAndModule' '-Dsurefire.failIfNoSpecifiedTests=true' test
 if($LASTEXITCODE -ne 0){throw 'PILOT08 RC02A FAIL: clinician directory real HTTP/PG17 QA'}
 $report=Join-Path (Get-Location) 'target\surefire-reports\TEST-com.argdigital.aronexa.appointment.AppointmentCandidateHttpAcceptanceTests.xml'
 if(-not(Test-Path -LiteralPath $report -PathType Leaf)){
  throw 'PILOT08 RC02A FAIL: targeted Surefire report missing.'
 }
 [xml]$xml=Get-Content -LiteralPath $report -Raw
 if([int]$xml.testsuite.tests -ne 1 -or
    [int]$xml.testsuite.failures -ne 0 -or
    [int]$xml.testsuite.errors -ne 0 -or
    [int]$xml.testsuite.skipped -ne 0 -or
    @($xml.testsuite.testcase | Where-Object {
      $_.name -eq 'pilot08Rc02aDoctorDirectoryRequiresLiveBranchRoleAndModule'
    }).Count -ne 1){
  throw 'PILOT08 RC02A FAIL: targeted doctor directory test did not pass 1/1.'
 }
} finally {Pop-Location}
Write-Host 'RC02A stage 2: entire RC01 security + Chromium no-UUID selector + separate-JVM restart'
$parent=Join-Path $PSScriptRoot 'Test-AroNexa-Pilot08-ReceptionContinuity.ps1'
& powershell.exe -NoProfile -File $parent -ExpectedCommit $sha -QaCentralPath $QaCentralPath
if($LASTEXITCODE -ne 0){throw 'PILOT08 RC02A FAIL: parent Reception continuity regression'}
Write-Host 'PILOT08 RC02A BRANCH DOCTOR SELECTOR + RECEPTION CONTINUITY QA PASSED' -ForegroundColor Green
Write-Host ('SourceCommit='+$sha)
Write-Host 'Internal fictional data only. Doctor directory default OFF in normal runtime.'
Write-Host 'Patient MRN/name search, full new-registration browser flow, security review and customer release still pending.'
