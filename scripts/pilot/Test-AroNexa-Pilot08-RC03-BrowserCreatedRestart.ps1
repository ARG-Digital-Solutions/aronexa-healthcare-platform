# PILOT-08 RC03 — Browser CREATED registration->booking->check-in,
# followed by an actual distinct JVM re-read against the same marked PG17.
# All records fictional; no production app/role/migration or original DB.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit,
 [Parameter(Mandatory=$true)]
 [string]$QaCentralPath
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'PILOT08 RC03 STOP: exact Git SHA mismatch'
}
$tracked=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($tracked | Where-Object {$_.Trim()}).Count -ne 0){
 throw 'PILOT08 RC03 STOP: tracked source is not clean'
}
foreach($name in @(
 'SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
 'SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE',
 'ARONEXA_PATIENT_DATASOURCE_URL','ARONEXA_AUTH_DATASOURCE_URL',
 'ARONEXA_OPD_DATASOURCE_URL',
 'ARONEXA_PILOT05_PORT','ARONEXA_PILOT05_RUN_ID',
 'ARONEXA_PILOT05_CONFIRM','ARONEXA_PILOT05_PHASE',
 'ARONEXA_PILOT08_RC03_CONFIRM','ARONEXA_PILOT06_QA_ROOT',
 'ARONEXA_PILOT06_BROWSER','ARONEXA_PILOT06_API_PORT')){
 if([Environment]::GetEnvironmentVariable($name)){
  throw ('PILOT08 RC03 STOP: inherited datasource/profile/activation variable: '+$name)
 }
}
$qa=(Resolve-Path -LiteralPath $QaCentralPath -ErrorAction Stop).Path
if(-not(Test-Path -LiteralPath (Join-Path $qa 'node_modules\playwright\index.mjs') -PathType Leaf)){
 throw 'PILOT08 RC03 STOP: QA Central Playwright not installed'
}
$parent=Join-Path $PSScriptRoot 'Test-AroNexa-Pilot08-RC02B-PatientSearch.ps1'
$restart=Join-Path $PSScriptRoot 'Test-AroNexa-Pilot05-DistinctJvm-Restart.ps1'
foreach($path in @($parent,$restart)){
 if(-not(Test-Path -LiteralPath $path -PathType Leaf)){
  throw 'PILOT08 RC03 STOP: required guarded runner missing'
 }
}
Write-Host 'RC03 stage 1/2: full RC02B->RC02A->RC01->PROD-INT-02 regression at this EXACT SHA'
& powershell.exe -NoProfile -File $parent -ExpectedCommit $sha -QaCentralPath $qa
if($LASTEXITCODE -ne 0){
 throw 'PILOT08 RC03 FAIL: parent search/doctor/browser/security/JVM regression'
}
Write-Host 'RC03 stage 2/2: new Chromium-created patient + duplicate review + book/check-in + distinct JVM'
& powershell.exe -NoProfile -File $restart -ExpectedCommit $sha -Rc03 -QaCentralPath $qa
if($LASTEXITCODE -ne 0){
 throw 'PILOT08 RC03 FAIL: browser-created identity distinct-JVM persistence'
}
Write-Host 'PILOT08 RC03 BROWSER-CREATED RECEPTION JOURNEY / DISTINCT JVM QA PASSED' -ForegroundColor Green
Write-Host ('SourceCommit='+$sha)
Write-Host 'Internal-fictional test milestone only; not installed Windows restart, DB release, security approval or clinic readiness.'
