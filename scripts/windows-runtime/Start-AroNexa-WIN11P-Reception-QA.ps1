# Internal integration harness. Uses fresh labelled PG17, never an existing DB.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit,
 [Parameter(Mandatory=$true)][string]$PortableDirectory,
 [Parameter(Mandatory=$true)][string]$QaCentralPath,
 [switch]$ConfirmFictionalDisposableLab,
 [switch]$VerifyOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if(-not $ConfirmFictionalDisposableLab){throw 'WIN11P STOP: fictional disposable QA confirmation required'}
if([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT){throw 'WIN11P STOP: Windows required'}
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit.ToLowerInvariant()){throw 'WIN11P STOP: exact source SHA mismatch'}
$dirty=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($dirty | Where-Object {$_.Trim()}).Count){throw 'WIN11P STOP: tracked source must be clean'}
# Do not hide inherited overrides or route the test backend to an existing DB.
foreach($entry in @(Get-ChildItem Env:)){
 if($entry.Name -match '^(SPRING_|ARONEXA_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|MAVEN_OPTS$|MAVEN_ARGS$|_JAVA_OPTIONS$)' -and $entry.Value){
  throw ('WIN11P STOP: use a clean PowerShell environment; inherited override '+$entry.Name)
 }
}
foreach($tool in @('git.exe','mvn.cmd','java.exe','node.exe','npm.cmd','docker.exe','powershell.exe')){
 if(-not(Get-Command $tool -ErrorAction SilentlyContinue)){throw ('WIN11P STOP: missing '+$tool)}
}
if(-not $env:JAVA_HOME -or -not(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))){
 throw 'WIN11P STOP: full build JDK 21 required in JAVA_HOME; portable runtime is used by test JVMs only'
}
$jdkVersion=@(& (Join-Path $env:JAVA_HOME 'bin\javac.exe') --version)
if($LASTEXITCODE -ne 0 -or ($jdkVersion -join ' ') -notmatch '^javac 21\.'){throw 'WIN11P STOP: build JDK must be 21'}
$portable=(Resolve-Path -LiteralPath $PortableDirectory).Path
$qa=(Resolve-Path -LiteralPath $QaCentralPath).Path
if(-not(Test-Path -LiteralPath (Join-Path $qa 'node_modules\playwright\index.mjs'))){
 throw 'WIN11P STOP: install QA Central dependencies and Playwright Chromium first'
}
& powershell.exe -NoProfile -File (Join-Path $PSScriptRoot 'Test-AroNexa-WIN11P-Preflight.ps1') -PortableDirectory $portable
if($LASTEXITCODE -ne 0){throw 'WIN11P STOP: portable preflight failed'}
$runtime=(Resolve-Path -LiteralPath (Join-Path $portable 'runtime')).Path
$java=Join-Path $runtime 'bin\java.exe'
if($java.Contains('"')){throw 'WIN11P STOP: invalid runtime path'}
$evidence=Join-Path (Join-Path $env:USERPROFILE 'AroNexa-Local-QA') ('WIN11P-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($evidence)
$summary=Join-Path $evidence 'SUMMARY.txt'
$result=@(
 'Classification=NOT_READY',('SourceCommit='+$sha),
 'Mode=SOURCE_TEST_HARNESS_USING_PORTABLE_JAVA21',
 'PortableBackendJarExecuted=false',
 'ExistingDatabaseTouched=false',
 'Database=NEW_LABELLED_DISPOSABLE_PG17_ONLY',
 'FullMavenQA=UNRESOLVED_NOT_REPLACED_BY_FOCUSED_QA',
 'IndependentSecurityReview=PENDING','WIN14CustomerApproval=PENDING',
 'CustomerRelease=NO_GO',
 'OperatorSessionPersistence=ONLY_UNTIL_LAB_STOP',
 'RestartEvidence=AUTOMATED_RC03_SESSION_SEPARATE_FROM_OPERATOR_LAB'
)
$result | Set-Content -LiteralPath $summary -Encoding UTF8
$transcriptStarted=$false
try {
 Start-Transcript -LiteralPath (Join-Path $evidence 'runner.log') | Out-Null
 $transcriptStarted=$true
 # Regression includes Mockito suites needing full JDK tooling. Keep these
 # on the build JDK; use bundled Java only for the explicit runtime journey.
 Write-Host 'WIN11P: security and connected-browser regression on the build JDK'
 & powershell.exe -NoProfile -File (Join-Path $repo 'scripts\pilot\Test-AroNexa-Pilot08-RC02B-PatientSearch.ps1') -ExpectedCommit $sha -QaCentralPath $qa
 if($LASTEXITCODE -ne 0){throw 'WIN11P FAIL: parent regression; portable journey not started'}
 # Maven still uses JAVA_HOME to compile. Surefire selects this JVM only
 # for the guarded RC03 phases and subsequent operator lab.
 $env:MAVEN_OPTS='-Djvm="'+$java+'"'
 $env:ARONEXA_WIN11P_JAVA_HOME=$runtime
 Write-Host 'WIN11P: browser-created records and separate-process restart on portable Java'
 & powershell.exe -NoProfile -File (Join-Path $repo 'scripts\pilot\Test-AroNexa-Pilot05-DistinctJvm-Restart.ps1') -ExpectedCommit $sha -Rc03 -QaCentralPath $qa
 if($LASTEXITCODE -ne 0){throw 'WIN11P FAIL: portable JVM restart; operator lab not started'}
 Add-Content -LiteralPath $summary -Value 'FocusedRegression=PASS' -Encoding UTF8
 if(-not $VerifyOnly){
  Write-Host 'WIN11P: opening a NEW fictional operator lab; automated restart records belong to the previous test run'
  & powershell.exe -NoProfile -File (Join-Path $repo 'scripts\pilot\Start-AroNexa-WindowsAssistedPilot.ps1') -ExpectedCommit $sha -ConfirmFictionalDisposableLab
  if($LASTEXITCODE -ne 0){throw 'WIN11P FAIL: operator session'}
  Add-Content -LiteralPath $summary -Value 'OperatorSession=ENDED_NOT_UAT_APPROVAL' -Encoding UTF8
 }
} catch {
 Add-Content -LiteralPath $summary -Value 'Runner=FAILED_OR_INTERRUPTED' -Encoding UTF8
 throw
} finally {
 [Environment]::SetEnvironmentVariable('MAVEN_OPTS',$null,'Process')
 [Environment]::SetEnvironmentVariable('ARONEXA_WIN11P_JAVA_HOME',$null,'Process')
 if($transcriptStarted){Stop-Transcript | Out-Null}
 Write-Host ('WIN11P evidence: '+$evidence)
 Write-Host 'NOT_READY: no customer release; full Maven failures and independent review remain open.'
}
