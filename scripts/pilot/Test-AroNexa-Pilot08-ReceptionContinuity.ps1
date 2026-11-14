# PILOT-08 / Issue #135 RC01: one-session queue -> registration
# without copying IDs, plus separate-JVM receptionist restart proof.
# BOTH are test-only, disposable PostgreSQL 17. NO clinic DB or deployment.
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
 throw 'PILOT08 STOP: exact source commit mismatch'
}
$changes=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($changes | Where-Object {$_.Trim()}).Count -ne 0){
 throw 'PILOT08 STOP: tracked source must be clean'
}
foreach($name in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
 'SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE',
 'ARONEXA_PILOT05_PORT','ARONEXA_PILOT05_RUN_ID',
 'ARONEXA_PILOT05_PHASE','ARONEXA_PILOT05_CONFIRM',
 'ARONEXA_PILOT06_BROWSER','ARONEXA_PILOT06_QA_ROOT',
 'ARONEXA_PILOT06_API_PORT')){
 if([Environment]::GetEnvironmentVariable($name)){
  throw ('PILOT08 STOP: inherited unsafe datasource/test setting '+$name)
 }
}
$browser=Join-Path $PSScriptRoot 'Test-AroNexa-Pilot06-IntegratedBrowser.ps1'
$restart=Join-Path $PSScriptRoot 'Test-AroNexa-Pilot05-DistinctJvm-Restart.ps1'
if(-not(Test-Path -LiteralPath $browser -PathType Leaf) -or
   -not(Test-Path -LiteralPath $restart -PathType Leaf)){
 throw 'PILOT08 STOP: required verified browser or distinct-JVM runner absent'
}
$root=Join-Path $env:USERPROFILE 'AroNexa-Local-QA'
[void][System.IO.Directory]::CreateDirectory($root)
$evidenceDir=Join-Path $root ('PILOT08-'+$sha.Substring(0,12)+'-'+[guid]::NewGuid().ToString('N'))
[void][System.IO.Directory]::CreateDirectory($evidenceDir)
$shot=Join-Path $evidenceDir ('PILOT08-'+$sha.Substring(0,12)+'-queue-registration.png')
$env:ARONEXA_PILOT08_SCREENSHOT=$shot
try {
 Write-Host 'PILOT08 STAGE 1: real Chromium queue -> freshly authorized registration -> doctor/Rx/billing'
 & powershell.exe -NoProfile -File $browser -ExpectedCommit $sha -QaCentralPath $QaCentralPath
 if($LASTEXITCODE -ne 0){throw 'PILOT08 FAIL: one-tab Chromium real HTTP/PG17 stage'}
} finally {
 [Environment]::SetEnvironmentVariable('ARONEXA_PILOT08_SCREENSHOT',$null,'Process')
}
if(-not(Test-Path -LiteralPath $shot -PathType Leaf)){
 throw 'PILOT08 FAIL: synthetic authorized registration screenshot missing'
}
Write-Host 'PILOT08 STAGE 2: two different Java JVMs on the SAME marked disposable PostgreSQL 17'
& powershell.exe -NoProfile -File $restart -ExpectedCommit $sha
if($LASTEXITCODE -ne 0){throw 'PILOT08 FAIL: distinct process registration/appointment/queue recovery'}
$evidence=Join-Path $evidenceDir 'SUMMARY.txt'
if(Test-Path -LiteralPath $evidence){
 throw 'PILOT08 STOP: report already exists; preserve independent runs'
}
@(
 ('SourceCommit='+$sha),
 'Milestone=Integrated_Reception_Pilot_Candidate_Issue_135_RC01',
 'Browser=OWNER_RUNNER_EXIT_0_REAL_CHROMIUM_HTTP_PG17',
 'QueueRegistration=FRESH_AUTHORIZED_PATIENT_ROLE_READ_NO_ID_COPY',
 'DifferentJVMs=OWNER_RUNNER_EXIT_0_SAME_MARKED_DISPOSABLE_PG17',
 'Recovered=SQL_SEEDED_REGISTRATION_BOOKING_CHECKIN_DAILY_QUEUE_CONSULTATION',
 ('FictionalQueueScreenshot='+$shot),
 'BrowserNewPatientRegistration=NOT_TESTED_BY_THIS_SLICE',
 'StandaloneNameMRNSearch=NOT_IMPLEMENTED',
 'DoctorSelectionWithoutUUID=NOT_IMPLEMENTED',
 'ProductionBookingActivation=OFF',
 'ActualWindowsServiceRestart=NOT_PROVEN',
 'OriginalDatabase=NOT_TOUCHED',
 'ClinicRelease=NO_GO'
) | Set-Content -LiteralPath $evidence -Encoding ASCII
Write-Host 'PILOT08 RC01 RECEPTION QUEUE + DISTINCT JVM RECOVERY QA PASSED' -ForegroundColor Green
Write-Host ('SourceCommit='+$sha)
Write-Host ('Evidence='+$evidence)
Write-Host 'Scope: fictional test-profile UI + SQL-seeded registration; NOT customer release or all Issue #135 acceptance.'
