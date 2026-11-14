# R1-PILOT-06. Runs Chromium clicks against a random-port test-only
# Spring Boot controller and automatically disposed PostgreSQL 17 container.
# NO production DB, clinic deployment, persistent profile or patient data.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[a-fA-F0-9]{40}$')]
 [string]$ExpectedCommit,
 [string]$QaCentralPath = (Join-Path $env:USERPROFILE 'Application Development\AroNexa-QA-Central')
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit.ToLowerInvariant()) {
 throw 'PILOT06 STOP: exact source SHA mismatch'
}
$changed=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($changed | Where-Object {$_.Trim()}).Count -ne 0) {
 throw 'PILOT06 STOP: uncommitted tracked source files'
}
foreach($name in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
  'SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE',
  'ARONEXA_PILOT06_BROWSER','ARONEXA_PILOT06_QA_ROOT',
  'ARONEXA_PILOT06_API_PORT')) {
 if([Environment]::GetEnvironmentVariable($name)){
  throw ('PILOT06 STOP: inherited test or datasource variable '+$name)
 }
}
$docker=Get-Command docker.exe -ErrorAction SilentlyContinue
$mvn=Get-Command mvn.cmd -ErrorAction SilentlyContinue
$npm=Get-Command npm.cmd -ErrorAction SilentlyContinue
$node=Get-Command node.exe -ErrorAction SilentlyContinue
if($null -eq $docker -or $null -eq $mvn -or
  $null -eq $npm -or $null -eq $node) {
 throw 'PILOT06 STOP: Java/Maven, Node/npm and Docker tools must be installed'
}
& $docker.Source info '--format' '{{.ServerVersion}}'
if($LASTEXITCODE -ne 0){throw 'PILOT06 STOP: Docker is unavailable'}
if(-not (Test-Path -LiteralPath $QaCentralPath -PathType Container)){
 throw 'PILOT06 STOP: QA Central checkout not found, pass -QaCentralPath'
}
$qa=(Resolve-Path -LiteralPath $QaCentralPath).Path
if(-not(Test-Path -LiteralPath (Join-Path $qa 'node_modules\playwright\index.mjs') -PathType Leaf)){
 throw 'PILOT06 STOP: QA Central Playwright is not installed (npm ci in QA Central)'
}
$env:ARONEXA_PILOT06_QA_ROOT=$qa
$env:ARONEXA_PILOT06_BROWSER='SYNTHETIC_ONLY'
try {
 Push-Location (Join-Path $repo 'frontend')
 try{
  & $npm.Source ci
  if($LASTEXITCODE -ne 0){throw 'PILOT06 FAIL: npm ci'}
  & $npm.Source test
  if($LASTEXITCODE -ne 0){throw 'PILOT06 FAIL: frontend unit tests'}
  foreach($task in @('build:connected:test',
                     'build:connected:doctor:test','build:connected:billing:test')){
   & $npm.Source run $task
   if($LASTEXITCODE -ne 0){throw ('PILOT06 FAIL: '+$task)}
  }
 } finally {Pop-Location}
 Push-Location (Join-Path $repo 'backend')
 try{
  & $mvn.Source '-Dtest=AppointmentCandidateHttpAcceptanceTests#pilot06BrowserIntegratedRealHttpAcrossThreeStaffRoles' '-Dsurefire.failIfNoSpecifiedTests=true' test
  if($LASTEXITCODE -ne 0){throw 'PILOT06 FAIL: integrated real browser HTTP acceptance'}
  $report=Join-Path (Get-Location) 'target\surefire-reports\TEST-com.argdigital.aronexa.appointment.AppointmentCandidateHttpAcceptanceTests.xml'
  if(-not(Test-Path -LiteralPath $report -PathType Leaf)){
   throw 'PILOT06 FAIL: missing Surefire XML'
  }
  [xml]$suite=Get-Content -LiteralPath $report -Raw
  if([int]$suite.testsuite.tests -ne 1 -or
    [int]$suite.testsuite.failures -ne 0 -or
    [int]$suite.testsuite.errors -ne 0 -or
    [int]$suite.testsuite.skipped -ne 0){
   throw 'PILOT06 FAIL: Surefire XML does not prove 1/1 passed'
  }
 } finally {Pop-Location}
 Write-Host 'PILOT06 INTEGRATED BROWSER REAL HTTP QA PASSED' -ForegroundColor Green
 Write-Host ('SourceCommit='+$sha)
 Write-Host 'Test-only: Chromium -> Vite loopback -> Spring random HTTP -> disposable PG17.'
 Write-Host 'No real patient, clinical issuance, installed Windows or production release GO.'
} finally {
 foreach($name in @('ARONEXA_PILOT06_QA_ROOT','ARONEXA_PILOT06_BROWSER')){
  [Environment]::SetEnvironmentVariable($name,$null,'Process')
 }
}
