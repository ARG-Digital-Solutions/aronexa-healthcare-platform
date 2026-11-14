# R1-PILOT-05: distinct Maven/Surefire JVMs, one disposable PG17.
# Never uses an existing database, Docker volume, production endpoint or installer.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit
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
                    'ARONEXA_PILOT05_PORT','ARONEXA_PILOT05_RUN_ID')) {
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
 $env:ARONEXA_PILOT05_PORT=$port
 $env:ARONEXA_PILOT05_RUN_ID=$runId
 $env:ARONEXA_PILOT05_CONFIRM='TEST_ONLY_DISPOSABLE'
 Push-Location (Join-Path $repo 'backend')
 try {
  $env:ARONEXA_PILOT05_PHASE='A'
  Write-Host 'PHASE A: first isolated Java JVM commits checked-in consultation'
  & $mvn.Source '-Dtest=Pilot05SeparateJvmHttpTests#phaseAWritesCheckedInConsultationAndStopsJvm' '-Dsurefire.failIfNoSpecifiedTests=true' test
  if ($LASTEXITCODE -ne 0) {throw 'PILOT05 FAIL: first JVM'}
  $env:ARONEXA_PILOT05_PHASE='B'
  Write-Host 'PHASE B: new Java JVM reopens same consultation from same PG17'
  & $mvn.Source '-Dtest=Pilot05SeparateJvmHttpTests#phaseBReopensSameConsultationInDifferentJvm' '-Dsurefire.failIfNoSpecifiedTests=true' test
  if ($LASTEXITCODE -ne 0) {throw 'PILOT05 FAIL: second JVM'}
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
 Write-Host 'PILOT05 DISTINCT JVM RESTART / SAME DISPOSABLE PG17 QA PASSED' -ForegroundColor Green
 Write-Host ('SourceCommit='+$actual)
 Write-Host 'Meaning: two test JVMs; one fictional consultation restored with new doctor session.'
 Write-Host 'Not customer UAT, installed Windows restart, production authorization or backup/restore proof.'
} finally {
 foreach($key in @('ARONEXA_PILOT05_PORT','ARONEXA_PILOT05_RUN_ID',
                    'ARONEXA_PILOT05_CONFIRM','ARONEXA_PILOT05_PHASE')) {
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
