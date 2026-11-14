# PROD-INT-01 protected production-source OPD registration intake read.
# Only synthetic data and disposable Testcontainers PG17; NEVER original DB.
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
 throw 'PRODINT01 STOP: exact commit mismatch.'
}
$changed=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($changed | Where-Object {$_.Trim()}).Count -ne 0){
 throw 'PRODINT01 STOP: tracked source modified.'
}
foreach($name in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE',
    'ARONEXA_PATIENT_DATASOURCE_URL','ARONEXA_AUTH_DATASOURCE_URL',
    'ARONEXA_OPD_DATASOURCE_URL')){
 if([Environment]::GetEnvironmentVariable($name)){
  throw ('PRODINT01 STOP: inherited datasource/profile variable '+$name)
 }
}
$docker=Get-Command docker.exe -ErrorAction SilentlyContinue
$mvn=Get-Command mvn.cmd -ErrorAction SilentlyContinue
$npm=Get-Command npm.cmd -ErrorAction SilentlyContinue
if($null -eq $docker -or $null -eq $mvn -or $null -eq $npm){
 throw 'PRODINT01 STOP: Docker Engine, Maven and npm required.'
}
& $docker.Source info '--format' '{{.ServerVersion}}'
if($LASTEXITCODE -ne 0){throw 'PRODINT01 STOP: Docker unavailable.'}
Push-Location (Join-Path $repo 'frontend')
try {
 & $npm.Source ci --no-audit --no-fund
 if($LASTEXITCODE -ne 0){throw 'PRODINT01 FAIL: npm ci'}
 & $npm.Source test
 if($LASTEXITCODE -ne 0){throw 'PRODINT01 FAIL: frontend regression'}
 & $npm.Source run 'build:connected:test'
 if($LASTEXITCODE -ne 0){throw 'PRODINT01 FAIL: TypeScript or connected UI build'}
} finally { Pop-Location }
Push-Location (Join-Path $repo 'backend')
try {
 & $mvn.Source '-Dtest=OpdIntakeProductionHttpAcceptanceTests' '-Dsurefire.failIfNoSpecifiedTests=true' test
 if($LASTEXITCODE -ne 0){throw 'PRODINT01 FAIL: real HTTP + PG17 authorization acceptance'}
 $file=Join-Path (Get-Location) 'target\surefire-reports\TEST-com.argdigital.aronexa.opd.OpdIntakeProductionHttpAcceptanceTests.xml'
 if(-not(Test-Path -LiteralPath $file -PathType Leaf)){
  throw 'PRODINT01 FAIL: Surefire XML missing'
 }
 [xml]$xml=Get-Content -LiteralPath $file -Raw
 if([int]$xml.testsuite.tests -ne 4 -or
    [int]$xml.testsuite.failures -ne 0 -or
    [int]$xml.testsuite.errors -ne 0 -or
    [int]$xml.testsuite.skipped -ne 0){
  throw 'PRODINT01 FAIL: Surefire 4/4 expected without skips'
 }
} finally { Pop-Location }
Write-Host 'PRODINT01 PRODUCTION-SOURCE INTAKE HTTP / DISPOSABLE PG17 QA PASSED' -ForegroundColor Green
Write-Host ('SourceCommit='+$sha)
Write-Host 'Intake reads only. No booking writes, original DB, release authorization or customer install.'
