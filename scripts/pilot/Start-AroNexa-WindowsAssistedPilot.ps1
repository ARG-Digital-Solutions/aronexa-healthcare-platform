# PILOT09: one-command fictional Windows assisted clinic test lab.
# INTERNAL developer/operator test only. Never real patient data.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[a-fA-F0-9]{40}$')]
 [string]$ExpectedCommit,
 [switch]$ConfirmFictionalDisposableLab,
 [switch]$FromVerifiedArchive
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if(-not $ConfirmFictionalDisposableLab){
 throw 'PILOT09 STOP: explicitly confirm fictional disposable data with -ConfirmFictionalDisposableLab'
}
if([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT){
 throw 'PILOT09 STOP: the assisted launcher supports Windows internal QA only'
}
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$sha=$ExpectedCommit.ToLowerInvariant()
if($FromVerifiedArchive){
 if(Test-Path -LiteralPath (Join-Path $repo '.git')){
  throw 'PILOT09 STOP: -FromVerifiedArchive is forbidden in a Git checkout'
 }
 & powershell.exe -NoProfile -File (Join-Path $PSScriptRoot 'Test-AroNexa-Pilot07-Archive.ps1') -ExpectedCommit $sha
 if($LASTEXITCODE -ne 0){throw 'PILOT09 STOP: bundle checksum verification failed'}
}else{
 $actual=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
 if($LASTEXITCODE -ne 0 -or $actual -ne $sha){
  throw 'PILOT09 STOP: source SHA does not match the requested test build'
 }
 $dirty=@(& git -C $repo status --porcelain --untracked-files=no)
 if($LASTEXITCODE -ne 0 -or @($dirty | Where-Object {$_.Trim()}).Count -ne 0){
  throw 'PILOT09 STOP: source tree contains tracked changes'
 }
}
foreach($key in @(
 'SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
 'SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE',
 'ARONEXA_PILOT05_PORT','ARONEXA_PILOT05_RUN_ID',
 'ARONEXA_PILOT05_CONFIRM','ARONEXA_PILOT05_PHASE',
 'ARONEXA_PILOT08_RC03_CONFIRM','ARONEXA_PILOT09_INTERACTIVE_ACK',
 'ARONEXA_PILOT09_RUN_DIR','ARONEXA_PILOT06_API_PORT',
 'ARONEXA_PATIENT_DATASOURCE_URL','ARONEXA_AUTH_DATASOURCE_URL',
 'ARONEXA_OPD_DATASOURCE_URL')){
 if([Environment]::GetEnvironmentVariable($key)){
  throw ('PILOT09 STOP: inherited unsafe datasource/profile value '+$key)
 }
}
$needs=@(
 @{Name='Java JDK 21';Cmd='java.exe'},
 @{Name='Maven';Cmd='mvn.cmd'},
 @{Name='Node';Cmd='node.exe'},
 @{Name='npm';Cmd='npm.cmd'},
 @{Name='Docker Engine';Cmd='docker.exe'}
)
$missing=@($needs | Where-Object {
 $null -eq (Get-Command $_.Cmd -ErrorAction SilentlyContinue)
} | ForEach-Object {$_.Name+' ['+$_.Cmd+']'})
if($missing.Count -gt 0){
 throw ('PILOT09 STOP: required local tools missing from PATH: '+($missing -join ', '))
}
$java=(Get-Command java.exe -ErrorAction Stop).Source
$version=(& $java --version | Select-Object -First 1).ToString()
if($version -notmatch '(?:^|[\s"])21\.'){
 throw ('PILOT09 STOP: Java 21 required, found '+$version)
}
$docker=(Get-Command docker.exe -ErrorAction Stop).Source
& $docker info '--format' '{{.ServerVersion}}'
if($LASTEXITCODE -ne 0){throw 'PILOT09 STOP: Docker Engine not running'}
$npm=(Get-Command npm.cmd -ErrorAction Stop).Source
$frontend=Join-Path $repo 'frontend'
$dist=Join-Path $frontend 'dist-connected-registration-test\connected-reception-preview.html'
Write-Host 'PILOT09: preparing the private connected clinic test interface' -ForegroundColor Yellow
Push-Location $frontend
try {
 & $npm ci --no-audit --no-fund
 if($LASTEXITCODE -ne 0){throw 'PILOT09 FAIL: npm ci'}
 if(-not $FromVerifiedArchive){
  & $npm run 'build:connected:test'
  if($LASTEXITCODE -ne 0){throw 'PILOT09 FAIL: frontend TypeScript/Vite build'}
 }
} finally {Pop-Location}
if(-not(Test-Path -LiteralPath $dist -PathType Leaf)){
 throw 'PILOT09 STOP: verified connected Reception HTML is missing'
}
$root=Join-Path $env:LOCALAPPDATA 'ARG-Digital\AroNexa-Pilot09-Disposable'
$run=Join-Path $root ('pilot09-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($run)
$marker=Join-Path $run 'LAB-TEST-ONLY.marker'
Set-Content -LiteralPath $marker -Encoding ASCII -Value ('FICTIONAL_DISPOSABLE_ONLY_SOURCE_SHA='+$sha)
$stop=Join-Path $run 'STOP'
Write-Host '---------------------------------------------------------------'
Write-Host 'ARONEXA CLINIC EDITION — WINDOWS ASSISTED TEST LAB' -ForegroundColor Yellow
Write-Host 'NOT customer software. Never enter real patient or clinical data.'
Write-Host ('Test URL when ready: http://127.0.0.1:5176/connected-reception-preview.html')
Write-Host ('Fictional logins: '+(Join-Path $run 'FICTIONAL-STAFF-ONLY.txt'))
Write-Host ('Session folder: '+$run)
Write-Host 'To STOP: open a second PowerShell and run this command:'
Write-Host ("New-Item -ItemType File -Path '"+$stop+"'")
Write-Host 'The operator test lab automatically ends after 45 minutes.'
Write-Host '---------------------------------------------------------------'
$runner=Join-Path $PSScriptRoot 'Test-AroNexa-Pilot09-DisposableOperator.ps1'
try {
 $arguments=@('-NoProfile','-File',$runner,'-ExpectedCommit',$sha,
   '-RunDirectory',$run)
 if($FromVerifiedArchive){$arguments+='-FromVerifiedArchive'}
 & powershell.exe @arguments
 if($LASTEXITCODE -ne 0){throw 'PILOT09 FAIL: guarded operator lab'}
 Write-Host 'PILOT09 WINDOWS ASSISTED FICTIONAL TEST LAB SESSION COMPLETED' -ForegroundColor Green
 Write-Host ('LocalSessionEvidence='+$run)
 Write-Host 'Clinic release remains NO-GO; no real Windows install or encrypted recovery has been proven.'
} finally {
 $sheet=Join-Path $run 'FICTIONAL-STAFF-ONLY.txt'
 if(Test-Path -LiteralPath $sheet){
  Remove-Item -LiteralPath $sheet -Force -ErrorAction SilentlyContinue
 }
}
