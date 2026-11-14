# Owner-controlled one-command sequence. Emits PASS only after real
# recovery, ZIP packaging and the intentionally interactive lab all exit 0.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit,
 [switch]$ConfirmFictionalDisposableLab,
 [string]$DestinationDirectory=(Join-Path $env:USERPROFILE 'Downloads')
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if(-not $ConfirmFictionalDisposableLab){
 throw 'PILOT07 STOP: explicit -ConfirmFictionalDisposableLab required.'
}
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'PILOT07 STOP: exact source commit mismatch.'
}
$dirty=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($dirty | Where-Object {$_.Trim()}).Count -ne 0){
 throw 'PILOT07 STOP: tracked source not clean.'
}
$restore=Join-Path $PSScriptRoot 'Test-AroNexa-Pilot07-DisposableRestore.ps1'
$build=Join-Path $PSScriptRoot 'Build-AroNexa-Pilot07-WindowsTestKit.ps1'
$lab=Join-Path $PSScriptRoot 'Start-AroNexa-Pilot07-DisposableWindowsLab.ps1'
Write-Host 'R1-PILOT-07 TECHNICAL QA - NO PATIENT DATA / NO COMMERCIAL INSTALLER' -ForegroundColor Yellow
Write-Host 'Stage 1/3: nonempty disposable PostgreSQL backup/restore rehearsal'
& powershell.exe -NoProfile -File $restore -ExpectedCommit $sha
if($LASTEXITCODE -ne 0){throw 'PILOT07 QA FAILED: disposable restore rehearsal'}
Write-Host 'Stage 2/3: private source ZIP and built connected preview'
& powershell.exe -NoProfile -File $build -ExpectedCommit $sha -DestinationDirectory $DestinationDirectory
if($LASTEXITCODE -ne 0){throw 'PILOT07 QA FAILED: test-lab ZIP packaging'}
$artifact=Join-Path $DestinationDirectory ('AroNexa-Clinic-OPD-TestLab-'+$sha.Substring(0,12)+'.zip')
if(-not(Test-Path -LiteralPath $artifact -PathType Leaf)){
 throw 'PILOT07 QA FAILED: test-lab ZIP artifact missing'
}
$zipSha=(Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash
Write-Host 'Stage 3/3: interactive synthetic Reception / Doctor / Billing lab'
Write-Host 'NOTE: Open the UI and test the workflow; create the printed STOP file in a second shell to end.'
& powershell.exe -NoProfile -File $lab -ExpectedCommit $sha -ConfirmFictionalDisposableLab
if($LASTEXITCODE -ne 0){throw 'PILOT07 QA FAILED: interactive lab did not end cleanly'}
$evidence=Join-Path $env:LOCALAPPDATA 'ARG-Digital\AroNexa-Pilot07-Disposable'
[void][IO.Directory]::CreateDirectory($evidence)
$report=[ordered]@{
 classification='INTERNAL_FOUNDATION_NOT_CUSTOMER_RELEASE'
 sourceCommit=$sha
 zipName=[IO.Path]::GetFileName($artifact)
 zipSHA256=$zipSha
 nonemptyDisposablePgRestore='OWNER_RUNNER_EXIT_0'
 privateTestKitPackaging='OWNER_RUNNER_EXIT_0'
 interactiveLabLifecycle='OWNER_RUNNER_EXIT_0'
 manualClinicalUAT='NOT_EVIDENCED'
 encryptedDatabaseBackup='NOT_IMPLEMENTED'
 offlineSignedLicense='NOT_IMPLEMENTED'
 commercialRelease='NO_GO'
}
$reportPath=Join-Path $evidence ('PILOT07-TECHNICAL-QA-'+$sha.Substring(0,12)+'.json')
($report | ConvertTo-Json -Depth 4) | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host 'PILOT07 TECHNICAL WINDOWS TEST-LAB QA PASSED' -ForegroundColor Green
Write-Host ('Artifact='+$artifact)
Write-Host ('ZIP_SHA256='+$zipSha)
Write-Host ('Report='+$reportPath)
Write-Host 'Does NOT prove customer installation, license, encryption, clinician UAT or release readiness.'
