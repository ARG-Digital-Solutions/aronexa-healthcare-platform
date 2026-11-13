# Read-only R1-09 gate self-test against exact source.
# Verifies report schema, hard NO_GO disposition and wrong-SHA rejection.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$inspector=Join-Path $PSScriptRoot 'Inspect-AroNexa-Windows-Pilot.ps1'
if(-not(Test-Path -LiteralPath $inspector -PathType Leaf)){
 throw 'Pilot inspector missing.'
}
# Windows PowerShell 5.1 decodes unsigned UTF-8 source using the system
# ANSI codepage. Reject non-ASCII bytes until all scripts have a tested
# BOM-aware packaging policy, avoiding invisible parser corruption.
$scriptBytes=[System.IO.File]::ReadAllBytes($inspector)
if(@($scriptBytes | Where-Object {$_ -gt 127}).Count -gt 0){
 throw 'Pilot inspector source contains non-ASCII bytes; unsupported by this Windows PowerShell 5.1 validation.'
}
# Parse using the actual PowerShell version used by this self-test. This
# catches syntax/encoding failures before any release-decision assertion.
$parseTokens=$null
$parseErrors=$null
$parsed=[System.Management.Automation.Language.Parser]::ParseFile(
 $inspector,[ref]$parseTokens,[ref]$parseErrors)
if($null -eq $parsed -or ($null -ne $parseErrors -and $parseErrors.Count -gt 0)){
 throw 'Pilot inspector PowerShell syntax is invalid.'
}
$reportLines=@(& powershell.exe -NoProfile -File $inspector -ExpectedCommit $ExpectedCommit -ReportOnly -AsJson)
if($LASTEXITCODE -ne 0){throw 'Read-only inspection report failed.'}
$report=($reportLines -join [Environment]::NewLine) | ConvertFrom-Json
if($report.schemaVersion -ne 1 -or
   $report.sourceCommit -ne $ExpectedCommit.ToLowerInvariant() -or
   $report.releaseDecision -ne 'NO_GO' -or
   $report.inspection -ne 'READ_ONLY_SOURCE_INVENTORY'){
 throw 'Release report unexpectedly changed its evidence contract.'
}
$gateIds=@($report.gates | ForEach-Object {$_.id})
foreach($required in @('SRC-01','INT-01','DB-01','SEC-01','WIN-01',
                        'WIN-02','WIN-03','WIN-04','UAT-01','REL-01')){
 if($gateIds -cnotcontains $required){
  throw "Missing mandatory release gate $required"
 }
}
if($report.blockedCount -lt 9){
 throw 'Synthetic candidate was unexpectedly allowed toward release.'
}
foreach($gate in @($report.gates | Where-Object {$_.id -ne 'SRC-01'})){
 if($gate.status -ne 'BLOCKED'){
  throw "Gate $($gate.id) must remain blocked without independently verified evidence."
 }
}
$wrong=if($ExpectedCommit -eq ('0'*40)){('1'*40)}else{('0'*40)}
# Windows PowerShell can promote native stderr to an exception when
# ErrorActionPreference=Stop. A wrong-SHA child intentionally throws, so
# capture only its exit code without letting expected stderr abort this test.
$savedPreference=$ErrorActionPreference
try {
 $ErrorActionPreference='Continue'
 & powershell.exe -NoProfile -File $inspector -ExpectedCommit $wrong -ReportOnly -AsJson *> $null
 $wrongShaExitCode=$LASTEXITCODE
} finally {
 $ErrorActionPreference=$savedPreference
}
if($wrongShaExitCode -eq 0){
 throw 'Wrong Git SHA was accepted by pilot gate.'
}
Write-Host 'R1-09 READ-ONLY NO-GO GATE SELF-TEST PASSED' -ForegroundColor Green
Write-Host "SourceCommit=$($report.sourceCommit)"
Write-Host "BlockedGates=$($report.blockedCount)"
Write-Host 'No installer, production DB, license or backup was accessed.'
