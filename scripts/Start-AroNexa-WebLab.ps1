# Convenience entrypoint for the existing connected Pilot09 synthetic lab.
[CmdletBinding()]
param([Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$ExpectedCommit,[switch]$CheckOnly,[switch]$ConfirmFictionalDisposableLab)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'WebLab.psm1') -Force
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sha=Get-WebLabSource -Repo $repo -ExpectedCommit $ExpectedCommit
$windows=[Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$commands=@('git','java','javac','node','docker')
$missing=@($commands|Where-Object{-not(Get-Command $_ -ErrorAction SilentlyContinue)})
foreach($pair in @(@('npm.cmd','npm'),@('mvn.cmd','mvn'))){if(-not(Get-Command $pair[0] -ErrorAction SilentlyContinue) -and -not(Get-Command $pair[1] -ErrorAction SilentlyContinue)){$missing+=($pair -join '/') }}
$state=if($windows -and $missing.Count -eq 0){'TOOLS_FOUND_VERSIONS_CHECKED_ON_START'}else{'BLOCKED'}
[pscustomobject]@{source_sha=$sha;status=$state;windows=$windows;missing_tools=$missing;url='http://127.0.0.1:5176/connected-reception-preview.html';backend='REAL_HTTP_TEST_CONTROLLERS_AND_RESTRICTED_PG17_PROOFS';production_reception='BLOCKED';full_acceptance='NOT_RUN';classification='NOT_READY';launcher='scripts/pilot/Start-AroNexa-WindowsAssistedPilot.ps1'}|ConvertTo-Json -Depth 4
if($CheckOnly){if($state -eq 'BLOCKED'){exit 2};exit 0}
if(-not $ConfirmFictionalDisposableLab){throw 'Explicit fictional disposable lab confirmation is required; no operator acknowledgement is inferred'}
if(-not $windows){throw 'Existing interactive lab requires Windows; no replacement demo or platform bypass is started'}
if($missing.Count){throw 'Required local tools missing; review CheckOnly output'}
Assert-WebLabCleanEnvironment
if((& java --version | Select-Object -First 1) -notmatch '\b21\.' -or (& javac -version) -notmatch '\b21\.' -or (& node --version) -notmatch '^v24\.'){throw 'Java21 full JDK and Node24 required'}
if((& docker info --format '{{.OSType}}') -ne 'linux' -or $LASTEXITCODE -ne 0){throw 'A reachable Linux Docker engine is required; never reuse an original database'}
# Existing launcher owns exact-source checks, real versions, fresh PG17,
# loopback guards, genuine private operator inputs and bounded cleanup.
& (Join-Path $PSScriptRoot 'pilot/Start-AroNexa-WindowsAssistedPilot.ps1') -ExpectedCommit $sha -ConfirmFictionalDisposableLab
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
Write-Output 'Owner-local synthetic lab ended; NOT_READY, no independent or customer acceptance'
