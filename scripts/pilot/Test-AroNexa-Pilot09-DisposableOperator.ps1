# PILOT09 ASSISTED WINDOWS - human-operated ephemeral fictional clinic test lab.
# Mode is separate from original Pilot05 and RC03 regression; NEVER production.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit,
 [Parameter(Mandatory=$true)]
 [string]$RunDirectory,
 [switch]$FromVerifiedArchive
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path

if($FromVerifiedArchive){
 if(Test-Path -LiteralPath (Join-Path $repo '.git')){throw 'PILOT09 STOP: cannot use archive mode in a Git checkout'}
 & powershell.exe -NoProfile -File (Join-Path $PSScriptRoot 'Test-AroNexa-Pilot07-Archive.ps1') -ExpectedCommit $ExpectedCommit
 if($LASTEXITCODE -ne 0){throw 'PILOT09 STOP: verified archive manifest failure'}
 $actual=$ExpectedCommit.ToLowerInvariant()
}else{
 $actual = (& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
 if ($LASTEXITCODE -ne 0 -or $actual -ne $ExpectedCommit.ToLowerInvariant()) {
  throw 'PILOT09 STOP: Git commit mismatch'
 }
 $changed = @(& git -C $repo status --porcelain --untracked-files=no)
 if ($LASTEXITCODE -ne 0 -or @($changed | Where-Object {$_.Trim()}).Count -ne 0) {
  throw 'PILOT09 STOP: tracked source changes found'
 }
}
foreach ($name in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
                    'SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE',
                    'ARONEXA_PILOT05_PORT','ARONEXA_PILOT05_RUN_ID',
                    'ARONEXA_PILOT08_RC03_CONFIRM','ARONEXA_PILOT06_QA_ROOT',
                    'ARONEXA_PILOT06_BROWSER','ARONEXA_PILOT06_API_PORT',
                    'ARONEXA_PILOT09_INTERACTIVE_ACK',
                    'ARONEXA_PILOT09_RUN_DIR')) {
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


$run=(Resolve-Path -LiteralPath $RunDirectory -ErrorAction Stop).Path
$allowed=Join-Path $env:LOCALAPPDATA 'ARG-Digital\AroNexa-Pilot09-Disposable'
if((Split-Path $run -Parent) -ne $allowed -or
   (Split-Path $run -Leaf) -notmatch '^pilot09-[a-f0-9]{32}$' -or
   -not(Test-Path -LiteralPath (Join-Path $run 'LAB-TEST-ONLY.marker') -PathType Leaf)){
 throw 'PILOT09 STOP: marker, run path or fictional consent invalid'
}
$sourceMarker=(Get-Content -LiteralPath (Join-Path $run 'LAB-TEST-ONLY.marker') -Raw).Trim()
if($sourceMarker -ne ('FICTIONAL_DISPOSABLE_ONLY_SOURCE_SHA='+$actual)){
 throw 'PILOT09 STOP: run marker/source SHA mismatch'
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

  $env:ARONEXA_PILOT08_RC03_CONFIRM='RC03_DISPOSABLE_BROWSER_ONLY'
  $env:ARONEXA_PILOT09_INTERACTIVE_ACK='DISPOSABLE_OPERATOR_ONLY'
  $env:ARONEXA_PILOT09_RUN_DIR=$run
  $env:ARONEXA_PILOT05_PHASE='A'
  Write-Host 'PILOT09 OPERATOR LAB: use fictional patients only; the database self-destructs on stop.'
  & $mvn.Source '-Dtest=Pilot05SeparateJvmHttpTests#pilot09OperatorAssistedWindowsFictionalLab' '-Dsurefire.failIfNoSpecifiedTests=true' test
  if ($LASTEXITCODE -ne 0) {throw 'PILOT09 FAIL: fictional operator test JVM'}
  Save-PhaseReport -Phase 'A'
 } finally {
  Pop-Location
 }

 $verifyArgs=@('exec',$containerId,'psql','-X','-v','ON_ERROR_STOP=1',
   '-U',$dbUser,'-d',$db,'-t','-A','-c',
   ("SELECT phase FROM qa_guard.pilot05_run_marker WHERE run_id='"+$runId+"'"))
 $asserted=@(& $docker.Source @verifyArgs)
 if ($LASTEXITCODE -ne 0 -or ($asserted -join '').Trim() -ne 'OPERATOR_ENDED') {
  throw 'PILOT09 FAIL: verified operator session did not close cleanly'
 }
 $summary=@(
   ('SourceCommit='+$actual),
   ('RunId='+$runId),
   'Mode=FICTIONAL_WINDOWS_OPERATOR_SESSION',
   'JUnitSingleProcess=PASS',
   'Database=DISPOSABLE_LOOPBACK_POSTGRES17',
   'PatientRecords=FICTIONAL_ONLY',
   'SeparateJvmRestart=NOT_TESTED_BY_INTERACTIVE_MODE',
   'ClinicalUAT=NOT_EVIDENCED',
   'SignedInstaller=NOT_DELIVERED',
   'CustomerRelease=NO_GO',
   ('ContainerAutoRemovedAfterSession='+$containerId)
 )
 $summary | Set-Content -LiteralPath (Join-Path $evidence 'SUMMARY.txt') -Encoding ASCII
 Write-Host 'PILOT09 WINDOWS FICTIONAL OPERATOR LAB ENDED SUCCESSFULLY' -ForegroundColor Green
 Write-Host ('EvidenceFolder='+$evidence)
 Write-Host ('SourceCommit='+$actual)
 Write-Host 'No customer release, clinic data, production database or UAT acceptance.'
} finally {
 foreach($key in @('ARONEXA_PILOT05_PORT','ARONEXA_PILOT05_RUN_ID',
                    'ARONEXA_PILOT05_CONFIRM','ARONEXA_PILOT05_PHASE',
                    'ARONEXA_PILOT08_RC03_CONFIRM','ARONEXA_PILOT06_QA_ROOT',
                    'ARONEXA_PILOT09_INTERACTIVE_ACK','ARONEXA_PILOT09_RUN_DIR')) {
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
