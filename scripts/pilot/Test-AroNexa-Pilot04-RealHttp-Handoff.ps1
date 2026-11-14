# R1-PILOT-04 — source-locked, disposable PG17, test-only HTTP acceptance.
# NEVER uses a persistent clinic database or activates production routes.
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
 throw 'PILOT-04 STOP: source commit mismatch.'
}
$modified=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($modified | Where-Object {$_.Trim()}).Count -ne 0){
 throw 'PILOT-04 STOP: tracked source is not clean.'
}
# Reject inherited application datasource credentials to avoid accidentally
# targeting a shared instance from a developer's PowerShell environment.
foreach($key in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
                  'SPRING_DATASOURCE_PASSWORD')){
 if([Environment]::GetEnvironmentVariable($key)){
  throw ('PILOT-04 STOP: external '+$key+' is set; use a clean QA shell.')
 }
}
$docker=Get-Command docker -ErrorAction SilentlyContinue
if($null -eq $docker){throw 'PILOT-04 STOP: Docker unavailable.'}
& $docker.Source info '--format' '{{.ServerVersion}}'
if($LASTEXITCODE -ne 0){throw 'PILOT-04 STOP: Docker Engine unavailable.'}
$mvn=Get-Command mvn.cmd -ErrorAction SilentlyContinue
if($null -eq $mvn){throw 'PILOT-04 STOP: Maven (mvn.cmd) unavailable.'}
$npm=Get-Command npm.cmd -ErrorAction SilentlyContinue
if($null -eq $npm){throw 'PILOT-04 STOP: npm.cmd unavailable.'}
Push-Location (Join-Path $repo 'backend')
try {
 Write-Host 'Running: disposable PostgreSQL 17 real HTTP Reception to Doctor handoff'
 & $mvn.Source '-Dtest=AppointmentCandidateHttpAcceptanceTests#receptionToDoctorHandoffAcrossRevokedReceptionAndFreshDoctorSession' '-Dsurefire.failIfNoSpecifiedTests=true' test
 if($LASTEXITCODE -ne 0){throw 'PILOT-04 FAIL: focused HTTP backend acceptance.'}
} finally { Pop-Location }
Push-Location (Join-Path $repo 'frontend')
try {
 & $npm.Source ci
 if($LASTEXITCODE -ne 0){throw 'PILOT-04 FAIL: npm ci.'}
 & $npm.Source test
 if($LASTEXITCODE -ne 0){throw 'PILOT-04 FAIL: frontend unit tests.'}
 & $npm.Source run 'build:connected:test'
 if($LASTEXITCODE -ne 0){throw 'PILOT-04 FAIL: connected reception Vite build.'}
 & $npm.Source run 'build:connected:doctor:test'
 if($LASTEXITCODE -ne 0){throw 'PILOT-04 FAIL: connected doctor Vite build.'}
} finally { Pop-Location }
Write-Host 'PILOT-04 TEST-ONLY HTTP / FRONTEND QA PASSED' -ForegroundColor Green
Write-Host ('SourceCommit='+$sha)
Write-Host 'Evidence: real loopback HTTP, disposable Testcontainers PG17, fresh staff sessions, saved draft read-back.'
Write-Host 'NOT verified: browser interaction, different JVM restart, Windows installer, production authorization, clinic release.'
