# R1-PILOT-07: nonempty PostgreSQL backup/restore rehearsal in
# AUTOMATICALLY DISPOSABLE Testcontainers PG17, NOT an encrypted clinic backup.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'PILOT07 RESTORE STOP: exact source SHA mismatch.'
}
$dirty=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($dirty | Where-Object {$_.Trim()}).Count -ne 0){
 throw 'PILOT07 RESTORE STOP: tracked source is not clean.'
}
foreach($name in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE',
    'ARONEXA_PILOT07_RECOVERY_ACK')){
 if([Environment]::GetEnvironmentVariable($name)){
  throw ('PILOT07 RESTORE STOP: inherited datasource/profile variable '+$name)
 }
}
$mvn=Get-Command mvn.cmd -ErrorAction SilentlyContinue
$docker=Get-Command docker.exe -ErrorAction SilentlyContinue
if($null -eq $mvn -or $null -eq $docker){
 throw 'PILOT07 RESTORE STOP: Docker Engine and Maven required.'
}
& $docker.Source info '--format' '{{.ServerVersion}}'
if($LASTEXITCODE -ne 0){throw 'PILOT07 RESTORE STOP: Docker Engine unavailable.'}
$env:ARONEXA_PILOT07_RECOVERY_ACK='DISPOSABLE_SYNTHETIC_RECOVERY_ONLY'
try{
 Push-Location (Join-Path $repo 'backend')
 try{
  & $mvn.Source '-Dtest=AppointmentCandidateHttpAcceptanceTests#pilot07NonemptyDisposablePostgresSnapshotRestoresToFreshDatabase' '-Dsurefire.failIfNoSpecifiedTests=true' test
  if($LASTEXITCODE -ne 0){throw 'PILOT07 RESTORE FAILED: nonempty disposable pg_dump/pg_restore'}
  $report=Join-Path (Get-Location) 'target\surefire-reports\TEST-com.argdigital.aronexa.appointment.AppointmentCandidateHttpAcceptanceTests.xml'
  if(-not(Test-Path -LiteralPath $report -PathType Leaf)){
   throw 'PILOT07 RESTORE FAILED: missing Surefire XML'
  }
  [xml]$xml=Get-Content -LiteralPath $report -Raw
  if([int]$xml.testsuite.tests -ne 1 -or
      [int]$xml.testsuite.failures -ne 0 -or
      [int]$xml.testsuite.errors -ne 0 -or
      [int]$xml.testsuite.skipped -ne 0){
   throw 'PILOT07 RESTORE FAILED: exact test did not pass 1/1'
  }
 } finally {Pop-Location}
 Write-Host 'PILOT07 DISPOSABLE NONEMPTY PG17 RESTORE QA PASSED' -ForegroundColor Green
 Write-Host ('SourceCommit='+$sha)
 Write-Host 'Proof: pg_dump and pg_restore to fresh DB in ONE automatically removed Docker PG17.'
 Write-Host 'NOT encrypted backup, independent PC, permanent disk recovery or production release.'
} finally {
 [Environment]::SetEnvironmentVariable('ARONEXA_PILOT07_RECOVERY_ACK',$null,'Process')
}
