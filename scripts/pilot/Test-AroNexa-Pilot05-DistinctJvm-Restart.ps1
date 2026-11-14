# R1-PILOT-05: distinct Maven/Surefire JVMs, one disposable PG17.
# Never uses an existing database, Docker volume, production endpoint or installer.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit,
 [switch]$Rc03,
 [string]$QaCentralPath=(Join-Path $env:USERPROFILE 'Application Development\AroNexa-QA-Central')
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$actual = (& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if ($LASTEXITCODE -ne 0 -or $actual -ne $ExpectedCommit.ToLowerInvariant()) {
 throw 'PILOT05 STOP: Git commit mismatch'
}
$changed = @(& git -C $repo status --porcelain --untracked-files=no)
if ($LASTEXITCODE -ne 0 -or @($changed | Where-Object {$_.Trim()}).Count -ne 0) {
 throw 'PILOT05 STOP: tracked source changes found'
}
foreach ($name in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
                    'SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE',
                    'ARONEXA_PILOT05_PORT','ARONEXA_PILOT05_RUN_ID',
                    'ARONEXA_PILOT08_RC03_CONFIRM','ARONEXA_PILOT06_QA_ROOT',
                    'ARONEXA_PILOT06_BROWSER','ARONEXA_PILOT06_API_PORT')) {
 if ([Environment]::GetEnvironmentVariable($name)) {
  throw ('PILOT05 STOP: inherited database/profile variable: ' + $name)
 }
}
$docker=Get-Command docker.exe -ErrorAction SilentlyContinue
$mvn=Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($null -eq $docker -or $null -eq $mvn) {
 throw 'PILOT05 STOP: Docker and Maven must be available'
}
& $docker.Source info '--format' '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) { throw 'PILOT05 STOP: Docker Engine unavailable' }

if($Rc03){
 $qa=Resolve-Path -LiteralPath $QaCentralPath -ErrorAction Stop
 $qaPath=$qa.Path
 if(-not(Test-Path -LiteralPath (Join-Path $qaPath 'node_modules\playwright\index.mjs'))){
  throw 'RC03 STOP: verified QA Central Playwright installation missing'
 }
 $html=Join-Path $repo 'frontend\dist-connected-registration-test\connected-reception-preview.html'
 if(-not(Test-Path -LiteralPath $html -PathType Leaf)){
  throw 'RC03 STOP: tested connected-Reception frontend build missing'
 }
}
$runId=[guid]::NewGuid().ToString('D')
$containerName='aronexa-pilot05-' + $runId.Substring(0,12)
$labelName='com.argdigital.aronexa.pilot05.run'
$labelValue=$labelName+'='+$runId
$db='aronexa_pilot05_disposable_only'
$dbUser='aronexa_pilot05_temporary'
$dbPassword='pilot05-disposable-password-only'
$containerId=''
$started=$false
function Get-PilotContainer {
 param([string]$Id)
 $raw=@(& $docker.Source inspect $Id 2>$null)
 if ($LASTEXITCODE -ne 0 -or $raw.Count -eq 0) {
  throw 'PILOT05 STOP: container inspect failed'
 }
 $all=ConvertFrom-Json -InputObject ($raw -join [Environment]::NewLine)
 if (@($all).Count -ne 1) {throw 'PILOT05 STOP: ambiguous container'}
 $value=@($all)[0]
 $label=$value.Config.Labels.PSObject.Properties[$labelName]
 if ($null -eq $label -or $label.Value -ne $runId -or
     $value.Name -ne ('/'+$containerName)) {
  throw 'PILOT05 STOP: container label or name mismatch'
 }
 return $value
}
try {
 Write-Host 'Creating one isolated auto-remove PostgreSQL 17 container'
 $dockerRunArgs=@('run','--detach','--rm','--name',$containerName,
   '--label',$labelValue,'--publish','127.0.0.1::5432',
   '--env',('POSTGRES_DB='+$db),'--env',('POSTGRES_USER='+$dbUser),
   '--env',('POSTGRES_PASSWORD='+$dbPassword),'postgres:17-alpine')
 $idLines=@(& $docker.Source @dockerRunArgs)
 if ($LASTEXITCODE -ne 0) {throw 'PILOT05 STOP: docker run failed'}
 $containerId=($idLines -join '').Trim()
 if ($containerId -notmatch '^[0-9a-f]{64}$') {
  throw 'PILOT05 STOP: container id not verified'
 }
 $started=$true
 $inspection=Get-PilotContainer -Id $containerId
 $binding=$inspection.NetworkSettings.Ports.PSObject.Properties['5432/tcp'].Value
 if (@($binding).Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1') {
  throw 'PILOT05 STOP: container is not bound to loopback'
 }
 $port=[string]$binding[0].HostPort
 if ($port -notmatch '^[0-9]{4,5}$' -or $port -eq '5433') {
  throw 'PILOT05 STOP: unexpected host PostgreSQL port'
 }
 $ready=$false
 for($i=0;$i -lt 40;$i++) {
  & $docker.Source exec $containerId pg_isready '-U' $dbUser '-d' $db *> $null
  if ($LASTEXITCODE -eq 0) {$ready=$true;break}
  Start-Sleep -Seconds 2
 }
 if (-not $ready) {throw 'PILOT05 STOP: disposable PostgreSQL did not become ready'}
 & $docker.Source cp (Join-Path $repo 'backend\src\test\resources\db\test\role-bootstrap.sql') ($containerId+':/tmp/role-bootstrap.sql')
 if ($LASTEXITCODE -ne 0) {throw 'PILOT05 STOP: isolated role bootstrap copy failed'}
 $rolesArgs=@('exec',$containerId,'psql','-X','-v','ON_ERROR_STOP=1',
    '-U',$dbUser,'-d',$db,'-f','/tmp/role-bootstrap.sql')
 & $docker.Source @rolesArgs
 if ($LASTEXITCODE -ne 0) {throw 'PILOT05 STOP: isolated role bootstrap failed'}
 $markerSql=@"
CREATE SCHEMA qa_guard;
CREATE TABLE qa_guard.pilot05_run_marker (
 run_id uuid PRIMARY KEY, database_name text NOT NULL, phase text NOT NULL,
 tenant_id uuid, branch_id uuid, patient_id uuid, appointment_id uuid,
 doctor_id uuid, process_a bigint, process_b bigint
);
INSERT INTO qa_guard.pilot05_run_marker(run_id,database_name,phase)
VALUES ('$runId','$db','READY');
"@
 $markerArgs=@('exec',$containerId,'psql','-X','-v','ON_ERROR_STOP=1',
    '-U',$dbUser,'-d',$db,'-c',$markerSql)
 & $docker.Source @markerArgs
 if ($LASTEXITCODE -ne 0) {throw 'PILOT05 STOP: isolated run marker failed'}
 $evidence=Join-Path (Join-Path $env:USERPROFILE 'AroNexa-Local-QA') ('PILOT05-'+$runId)
 [void][System.IO.Directory]::CreateDirectory($evidence)
 $reportPath=Join-Path $repo 'backend\target\surefire-reports\TEST-com.argdigital.aronexa.appointment.Pilot05SeparateJvmHttpTests.xml'
 function Save-PhaseReport {
  param([string]$Phase)
  if(-not(Test-Path -LiteralPath $reportPath -PathType Leaf)){
   throw ('PILOT05 FAIL: missing Surefire XML for phase '+$Phase)
  }
  [xml]$xml=Get-Content -LiteralPath $reportPath -Raw
  if([int]$xml.testsuite.tests -ne 1 -or
     [int]$xml.testsuite.failures -ne 0 -or
     [int]$xml.testsuite.errors -ne 0 -or
     [int]$xml.testsuite.skipped -ne 0){
   throw ('PILOT05 FAIL: Surefire XML did not establish a clean phase '+$Phase)
  }
  Copy-Item -LiteralPath $reportPath -Destination (Join-Path $evidence ('phase'+$Phase+'-surefire.xml'))
  Write-Host ('Verified Surefire XML phase '+$Phase+': 1 test, 0 failures/errors/skipped')
 }
 $env:ARONEXA_PILOT05_PORT=$port
 $env:ARONEXA_PILOT05_RUN_ID=$runId
 $env:ARONEXA_PILOT05_CONFIRM='TEST_ONLY_DISPOSABLE'
 Push-Location (Join-Path $repo 'backend')
 try {
  if($Rc03){
   $env:ARONEXA_PILOT08_RC03_CONFIRM='RC03_DISPOSABLE_BROWSER_ONLY'
   $env:ARONEXA_PILOT06_QA_ROOT=$qaPath
  }
  $testA=if($Rc03){
   'Pilot05SeparateJvmHttpTests#phaseARc03BrowserCreatesRegistrationBookingAndCheckIn'
  }else{'Pilot05SeparateJvmHttpTests#phaseAWritesCheckedInConsultationAndStopsJvm'}
  $testB=if($Rc03){
   'Pilot05SeparateJvmHttpTests#phaseBRc03RecoversBrowserCreatedPatientAndCheckedInQueue'
  }else{'Pilot05SeparateJvmHttpTests#phaseBReopensSameConsultationInDifferentJvm'}
  $env:ARONEXA_PILOT05_PHASE='A'
  Write-Host 'PHASE A: first isolated JVM commits ' $(if($Rc03){'CHROMIUM-created registration and check-in'}else{'checked-in consultation'})
  & $mvn.Source ('-Dtest='+$testA) '-Dsurefire.failIfNoSpecifiedTests=true' test
  if ($LASTEXITCODE -ne 0) {throw 'PILOT05 FAIL: first JVM'}
  Save-PhaseReport -Phase 'A'
  $env:ARONEXA_PILOT05_PHASE='B'
  Write-Host 'PHASE B: a DIFFERENT JVM reopens the same server-created records'
  & $mvn.Source ('-Dtest='+$testB) '-Dsurefire.failIfNoSpecifiedTests=true' test
  if ($LASTEXITCODE -ne 0) {throw 'PILOT05 FAIL: second JVM'}
  Save-PhaseReport -Phase 'B'
 } finally {
  Pop-Location
 }
 $verifyArgs=@('exec',$containerId,'psql','-X','-v','ON_ERROR_STOP=1',
   '-U',$dbUser,'-d',$db,'-t','-A','-c',
   ("SELECT phase,process_a<>process_b FROM qa_guard.pilot05_run_marker WHERE run_id='"+$runId+"'"))
 $asserted=@(& $docker.Source @verifyArgs)
 if ($LASTEXITCODE -ne 0 -or ($asserted -join '').Trim() -ne 'PASS_B|t') {
  throw 'PILOT05 FAIL: distinct-JVM source marker was not confirmed'
 }
 $summary=@(
  ('SourceCommit='+$actual),
  ('RunId='+$runId),
  'PhaseA=PASS (different JVM and session, committed fictional consultation)',
  'PhaseB=PASS (same disposable PG17, fresh JVM and doctor, authorized read-back)',
  'SurefireTests=1+1 Failures=0 Errors=0 Skipped=0',
  'Scope=TEST_ONLY_NO_CUSTOMER_RELEASE',
  ('ContainerAutoRemovedAfterRun='+$containerId)
 )
 if($Rc03){
  $summary+=@(
   'Rc03BrowserCreatedRegistration=true',
   'Rc03BrowserCreatedBookingAndCheckIn=true',
   'Rc03DuplicateRegistrationDenied=true',
   'Rc03SameMarkedPG17RecoveredDifferentJvm=true',
   'Rc03RealInstalledWindowsServiceRestart=false'
  )
 }
 $summary | Set-Content -LiteralPath (Join-Path $evidence 'SUMMARY.txt') -Encoding ASCII
 Write-Host ('EvidenceFolder='+$evidence)
 if($Rc03){
  Write-Host 'PILOT08 RC03 BROWSER-CREATED REGISTRATION / DISTINCT JVM RECOVERY QA PASSED' -ForegroundColor Green
 }else{
  Write-Host 'PILOT05 DISTINCT JVM RESTART / SAME DISPOSABLE PG17 QA PASSED' -ForegroundColor Green
 }
 Write-Host ('SourceCommit='+$actual)
 Write-Host 'Meaning: two test JVMs reopened the same marked disposable PostgreSQL 17 records.'
 Write-Host 'Not customer UAT, installed Windows restart, production authorization or backup/restore proof.'
} finally {
 foreach($key in @('ARONEXA_PILOT05_PORT','ARONEXA_PILOT05_RUN_ID',
                    'ARONEXA_PILOT05_CONFIRM','ARONEXA_PILOT05_PHASE',
                    'ARONEXA_PILOT08_RC03_CONFIRM','ARONEXA_PILOT06_QA_ROOT')) {
  [Environment]::SetEnvironmentVariable($key,$null,'Process')
 }
 if ($started -and $containerId -match '^[0-9a-f]{64}$') {
  try {
   $inspection=Get-PilotContainer -Id $containerId
   Write-Host 'Stopping ONLY the verified disposable Pilot05 container'
   & $docker.Source stop $containerId | Out-Null
   if ($LASTEXITCODE -ne 0) {Write-Warning 'Pilot05 cleanup failed; inspect exact container ID'}
  } catch {
   Write-Warning 'Cleanup refused: exact disposable container identity not verified'
   Write-Warning ('Container ID requiring manual review: '+$containerId)
  }
 }
}
