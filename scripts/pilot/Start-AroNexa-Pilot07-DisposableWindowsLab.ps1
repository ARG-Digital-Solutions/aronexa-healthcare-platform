# R1-PILOT-07: assisted Windows UI session with DISPOSABLE test-only PG17.
# No production controllers, migration, original database or customer deployment.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit,
 [switch]$ConfirmFictionalDisposableLab
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if(-not $ConfirmFictionalDisposableLab){
 throw 'PILOT07 STOP: requires -ConfirmFictionalDisposableLab explicitly.'
}
if([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT){
 throw 'PILOT07 STOP: this operator lab is for isolated Windows QA only.'
}
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'PILOT07 STOP: Git source SHA mismatch.'
}
$dirty=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($dirty | Where-Object {$_.Trim()}).Count -ne 0){
 throw 'PILOT07 STOP: tracked source is not clean.'
}
foreach($name in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE',
    'ARONEXA_PILOT07_ACK','ARONEXA_PILOT07_RUN_DIR',
    'ARONEXA_PILOT06_API_PORT')){
 if([Environment]::GetEnvironmentVariable($name)){
  throw ('PILOT07 STOP: inherited dangerous datasource/profile variable '+$name)
 }
}
$docker=Get-Command docker.exe -ErrorAction SilentlyContinue
$mvn=Get-Command mvn.cmd -ErrorAction SilentlyContinue
$npm=Get-Command npm.cmd -ErrorAction SilentlyContinue
$node=Get-Command node.exe -ErrorAction SilentlyContinue
$java=Get-Command java.exe -ErrorAction SilentlyContinue
if($null -eq $docker -or $null -eq $mvn -or
   $null -eq $npm -or $null -eq $node -or $null -eq $java){
 throw 'PILOT07 STOP: requires local Java 21, Maven, Docker Engine, Node and npm.'
}
$version=(& $java.Source -version 2>&1 | Select-Object -First 1).ToString()
if($version -notmatch '(?:^|[\s"])(?:21)\.'){
 throw 'PILOT07 STOP: Java 21 required.'
}
& $docker.Source info '--format' '{{.ServerVersion}}'
if($LASTEXITCODE -ne 0){throw 'PILOT07 STOP: Docker Engine unavailable.'}
$root=Join-Path $env:LOCALAPPDATA 'ARG-Digital\AroNexa-Pilot07-Disposable'
$run=Join-Path $root ('pilot07-'+[guid]::NewGuid().ToString('N'))
[void][System.IO.Directory]::CreateDirectory($run)
$marker=Join-Path $run 'LAB-TEST-ONLY.marker'
Set-Content -LiteralPath $marker -Encoding ASCII -Value ('DISPOSABLE_ONLY SOURCE_SHA='+$sha)
$stop=Join-Path $run 'STOP'
Write-Host 'R1-PILOT-07 FICTIONAL TEST LAB - NOT CLINIC SOFTWARE' -ForegroundColor Yellow
Write-Host ('RunFolder='+$run)
Write-Host 'Clinic preview URL (when READY): http://127.0.0.1:5176/connected-reception-preview.html'
Write-Host ('Fictional login sheet (created after Spring starts): '+(Join-Path $run 'FICTIONAL-STAFF-ONLY.txt'))
Write-Host 'To end session early, open a SECOND PowerShell and run:'
Write-Host ("New-Item -ItemType File -Path '"+$stop+"'")
Write-Host 'Lab auto-expires after 25 minutes. It destroys its disposable database.'
Push-Location (Join-Path $repo 'frontend')
try{
 & $npm.Source ci --no-audit --no-fund
 if($LASTEXITCODE -ne 0){throw 'PILOT07 FAIL: npm ci'}
 & $npm.Source run 'build:connected:test'
 if($LASTEXITCODE -ne 0){throw 'PILOT07 FAIL: connected React test build'}
} finally { Pop-Location }
$env:ARONEXA_PILOT07_ACK='DISPOSABLE_FICTIONAL_LAB_ONLY'
$env:ARONEXA_PILOT07_RUN_DIR=$run
try{
 Push-Location (Join-Path $repo 'backend')
 try{
  & $mvn.Source '-Dtest=AppointmentCandidateHttpAcceptanceTests#pilot07OperatorInteractiveDisposableWindowsClinicLab' '-Dsurefire.failIfNoSpecifiedTests=true' test
  if($LASTEXITCODE -ne 0){throw 'PILOT07 FAIL: interactive disposable backend lab'}
 } finally { Pop-Location }
 Write-Host 'PILOT07 DISPOSABLE INTERACTIVE LAB ENDED SUCCESSFULLY' -ForegroundColor Green
 Write-Host 'This proves only the technical session lifecycle, not user acceptance or commercial readiness.'
} finally {
 [Environment]::SetEnvironmentVariable('ARONEXA_PILOT07_ACK',$null,'Process')
 [Environment]::SetEnvironmentVariable('ARONEXA_PILOT07_RUN_DIR',$null,'Process')
 # Synthetic credentials are deleted by the JUnit method; remove leftover
 # synthetic credentials even if Maven was interrupted or browser launch failed.
 $synthetic=Join-Path $run 'FICTIONAL-STAFF-ONLY.txt'
 if(Test-Path -LiteralPath $synthetic){
  Remove-Item -LiteralPath $synthetic -Force -ErrorAction SilentlyContinue
 }
 Write-Host ('Disposable run evidence folder: '+$run)
 Write-Host 'Do not distribute saved screenshots or logs containing credentials.'
}
