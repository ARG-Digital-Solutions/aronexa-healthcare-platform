# ADDITIONAL local feedback; never substitutes required QA Central acceptance.
[CmdletBinding()]
param([ValidateSet('Reception')][string]$Flow='Reception',[ValidateSet('Developer','Feature')][string]$Level='Developer',
 [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$ExpectedCommit,
 [Parameter(Mandatory=$true)][string]$ReportDirectory)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'WebLab.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'pilot/Win11p-MavenMatrix.psm1') -Force -DisableNameChecking
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sha=Get-WebLabSource -Repo $repo -ExpectedCommit $ExpectedCommit
$selection=Get-WebLabSelection -Repo $repo
Assert-WebLabCleanEnvironment
if([IO.Path]::GetFullPath($ReportDirectory).StartsWith($repo+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Private reports must live outside the source worktree'}
if(Test-Path -LiteralPath $ReportDirectory){throw 'Use a new private report directory for each run'}
[void][IO.Directory]::CreateDirectory($ReportDirectory)
$reports=(Resolve-Path -LiteralPath $ReportDirectory).Path
$run=[guid]::NewGuid().ToString('N')
$summary=[ordered]@{source_sha=$sha;source_integrity='PASS';overall='NOT_RUN';result_scope='LOCAL_SELECTED_FEEDBACK_ONLY';run_id=$run;flow=$Flow;level=$Level;developer_feedback='NOT_RUN';feature_http='NOT_RUN';browser_journey='NOT_RUN';typecheck='NOT_RUN';central_qa='NOT_RUN';full_acceptance='NOT_RUN';classification='NOT_READY';node_tests=0;http_tests=0;http_assertions='NOT_RUN';lifecycle='NOT_RUN';surefire_shutdown_error=$false;hikari_closed_warnings=0;commands=@();duration_seconds=0}
$clock=[Diagnostics.Stopwatch]::StartNew();$failed=$false
try{
 $node=(Get-Command node -ErrorAction Stop).Source
 if((& $node --version) -notmatch '^v24\.'){throw 'Node24 required'}
 $args=@('--experimental-strip-types','--test')+@($selection.frontend)
 $command='& '+(Quote-WebLabArgument $node)+' '+(($args|ForEach-Object{Quote-WebLabArgument $_}) -join ' ')+'; exit $LASTEXITCODE'
 $summary.commands+=@{stage='frontend';selection=@($selection.frontend|ForEach-Object{Split-Path $_ -Leaf})}
 $child=Invoke-Win11pChildProcess -PowerShellCommand $command -WorkingDirectory $repo -LogPath (Join-Path $reports 'frontend-private.log') -Environment @{}
 $log=[IO.File]::ReadAllText((Join-Path $reports 'frontend-private.log'))
 $counts=@{}
 foreach($key in @('tests','pass','fail','cancelled','skipped')){
  $matches=[regex]::Matches($log,'(?m)^(?:[#\u2139]\s*)?'+$key+'\s+([0-9]+)\s*$')
  if($matches.Count -ne 1){throw 'Missing or ambiguous Node counters'}
  $counts[$key]=[int]$matches[0].Groups[1].Value
 }
 $summary.node_tests=$counts.tests
 if($child.exit_code -ne 0 -or $child.timed_out -or -not $child.cleanup_complete -or $counts.tests -le 0 -or $counts.pass -ne $counts.tests -or $counts.fail -or $counts.cancelled -or $counts.skipped){throw 'Selected frontend tests failed/incomplete'}
 $summary.developer_feedback='PASS'
 if($Level -eq 'Feature'){
  $mvn=Get-Command mvn.cmd -ErrorAction SilentlyContinue
  if(-not $mvn){$mvn=Get-Command mvn -ErrorAction Stop}
  $discoverArgs=@('-B','-ntp','-Pwin11p-discovery',('-Dwin11p.run.id='+$run),'-Dwin11p.lane=discovery',('-Dwin11p.source.sha='+$sha),'test')
  $discoverCommand='& '+(Quote-WebLabArgument $mvn.Source)+' '+(($discoverArgs|ForEach-Object{Quote-WebLabArgument $_}) -join ' ')+'; exit $LASTEXITCODE'
  $discovery=Invoke-Win11pChildProcess -PowerShellCommand $discoverCommand -WorkingDirectory (Join-Path $repo 'backend') -LogPath (Join-Path $reports 'discovery-private.log') -Environment @{}
  $discovered=ConvertFrom-Win11pSurefireReports -ReportDirectory (Join-Path $repo ('backend/target/win11p-reports/'+$run+'/discovery')) -Lane discovery
  if($discovery.exit_code -ne 0 -or -not $discovery.cleanup_complete -or $discovery.timed_out -or $discovered.tests -ne 1 -or $discovered.issues.Count -or $discovered.failures -or $discovered.errors -or $discovered.skipped){throw 'Inventory discovery incomplete'}
  $inventory=Get-Content -LiteralPath (Join-Path $repo 'backend/target/win11p-test-method-inventory.json') -Raw | ConvertFrom-Json
  if($inventory.source_sha -cne $sha){throw 'Discovery source identity mismatch'}
  $assignments=@(Get-Win11pTestAssignment -Inventory @($inventory.methods))
  $expected=@($assignments|Where-Object{$_.lane -eq 'ordinary' -and (($_.id -split '#')[0] -split '\.')[-1] -in $selection.classes}|ForEach-Object{$_.id})
  if($expected.Count -eq 0 -or @($expected|Select-Object -Unique).Count -ne $expected.Count){throw 'Selected inventory missing/duplicate'}
  $classes=$selection.classes -join ','
  $args=@('-B','-ntp','-Pwin11p-ordinary',('-Dtest='+$classes),'-Dsurefire.failIfNoSpecifiedTests=true',('-Dwin11p.run.id='+$run),'-Dwin11p.lane=ordinary',('-Dwin11p.source.sha='+$sha),'test')
  $summary.commands+=@{stage='http';profile='win11p-ordinary';classes=$selection.classes;arguments=$args}
  $command='& '+(Quote-WebLabArgument $mvn.Source)+' '+(($args|ForEach-Object{Quote-WebLabArgument $_}) -join ' ')+'; exit $LASTEXITCODE'
  $child=Invoke-Win11pChildProcess -PowerShellCommand $command -WorkingDirectory (Join-Path $repo 'backend') -LogPath (Join-Path $reports 'http-private.log') -Environment @{}
  $dir=Join-Path $repo ('backend/target/win11p-reports/'+$run+'/ordinary')
  $parsed=ConvertFrom-Win11pSurefireReports -ReportDirectory $dir -Lane ordinary
  $summary.http_tests=$parsed.tests
  $ids=@($parsed.results|ForEach-Object{$_.id})
  if(@($ids|Where-Object{$_ -notin $expected}).Count -or @($expected|Where-Object{$_ -notin $ids}).Count){throw 'Selected method inventory mismatch'}
  if($child.exit_code -ne 0 -or $child.timed_out -or -not $child.cleanup_complete -or $parsed.issues.Count -or $parsed.tests -le 0 -or $parsed.failures -or $parsed.errors -or $parsed.skipped -or @($ids|Select-Object -Unique).Count -ne $ids.Count){throw 'Selected HTTP/PG17 reports failed/incomplete'}
  foreach($class in $selection.classes){if(@($ids|Where-Object{$_ -match ('\.'+[regex]::Escape($class)+'#')}).Count -eq 0){throw 'Selected HTTP class missing'}}
  $summary.http_assertions='PASS'
  $diagnostics=Get-WebLabDiagnostics -Text ([IO.File]::ReadAllText((Join-Path $reports 'http-private.log')))
  $summary.lifecycle=$diagnostics.lifecycle
  $summary.surefire_shutdown_error=$diagnostics.surefire_shutdown_error
  $summary.hikari_closed_warnings=$diagnostics.hikari_closed_warnings
  if($diagnostics.lifecycle -ne 'PASS'){throw 'HTTP assertions passed but runtime lifecycle diagnostics remain unresolved'}
  $summary.feature_http='PASS'
 }
}catch{
 $failed=$true
 if($summary.developer_feedback -ne 'PASS'){$summary.developer_feedback='FAIL'}elseif($Level -eq 'Feature'){$summary.feature_http='FAIL'}
 Write-Warning 'Local feedback failed; inspect private logs. No acceptance PASS.'
}finally{
 try{$null=Get-WebLabSource -Repo $repo -ExpectedCommit $sha}catch{
  $failed=$true;$summary.source_integrity='FAIL';$summary.developer_feedback='FAIL'
  if($Level -eq 'Feature'){$summary.feature_http='FAIL'}
 }
 $summary.overall=if($failed){'FAIL'}else{'PASS'}
 $clock.Stop();$summary.duration_seconds=[Math]::Round($clock.Elapsed.TotalSeconds,3)
 [IO.File]::WriteAllText((Join-Path $reports 'feedback.json'),($summary|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
}
$summary|ConvertTo-Json -Depth 8
if($failed){exit 1}
