# PILOT09: private Windows fictional test package derived from exact source.
# This reuses the reviewed Pilot07 archive/checksum builder and adds a
# distinct filename. It is NOT a compiled EXE or commercial installer.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[a-fA-F0-9]{40}$')]
 [string]$ExpectedCommit,
 [string]$DestinationDirectory=(Join-Path $env:USERPROFILE 'Downloads')
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'PILOT09 BUILD STOP: Git SHA mismatch'
}
if(-not(Test-Path -LiteralPath $DestinationDirectory -PathType Container)){
 throw 'PILOT09 BUILD STOP: destination folder missing'
}
$older=Join-Path $DestinationDirectory ('AroNexa-Clinic-OPD-TestLab-'+$sha.Substring(0,12)+'.zip')
$pilot=Join-Path $DestinationDirectory ('AroNexa-ClinicEdition-Windows-AssistedPilot-'+$sha.Substring(0,12)+'-INTERNAL.zip')
if((Test-Path -LiteralPath $older) -or (Test-Path -LiteralPath $pilot)){
 throw 'PILOT09 BUILD STOP: output already exists, preserve prior evidence'
}
foreach($file in @(
 'scripts\pilot\Start-AroNexa-WindowsAssistedPilot.ps1',
 'scripts\pilot\Test-AroNexa-Pilot09-DisposableOperator.ps1',
 'RUN-PILOT09-TEST-ONLY.cmd',
 'docs\phases\OPD_WINDOWS_ASSISTED_PILOT09_README.md')){
 if(-not(Test-Path -LiteralPath (Join-Path $repo $file) -PathType Leaf)){
  throw ('PILOT09 BUILD STOP: source kit file missing '+$file)
 }
}
& powershell.exe -NoProfile -File (Join-Path $PSScriptRoot 'Build-AroNexa-Pilot07-WindowsTestKit.ps1') -ExpectedCommit $sha -DestinationDirectory $DestinationDirectory
if($LASTEXITCODE -ne 0){throw 'PILOT09 BUILD FAILED: existing source/React/checksum gate'}
if(-not(Test-Path -LiteralPath $older -PathType Leaf)){
 throw 'PILOT09 BUILD FAIL: source/React ZIP missing'
}
Move-Item -LiteralPath $older -Destination $pilot
$hash=(Get-FileHash -LiteralPath $pilot -Algorithm SHA256).Hash
Write-Host 'PILOT09 WINDOWS ASSISTED PRIVATE TEST KIT BUILT' -ForegroundColor Green
Write-Host ('Artifact='+$pilot)
Write-Host ('ZIP_SHA256='+$hash)
Write-Host ('SourceCommit='+$sha)
Write-Host 'Internal fictional source+prebuilt React ZIP; NO signed EXE, offline license, approved production SQL or customer installation.'
