[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{40}$')][string]$ExpectedCommit,
    [ValidateSet('Ordinary','All')][string]$Mode='Ordinary',
    [string]$EvidenceDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
if([string]::IsNullOrWhiteSpace($EvidenceDirectory)){$EvidenceDirectory=Join-Path $repo 'target\win11p-evidence'}
$module=Join-Path $PSScriptRoot 'Win11p-MavenMatrix.psm1'
Import-Module $module -Force
$config=Get-Win11pMatrixConfig
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit){throw 'WIN11P STOP: exact source SHA mismatch'}
$dirty=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($dirty|Where-Object{$_.Trim()}).Count -gt 0){throw 'WIN11P STOP: tracked source is dirty'}
if($Mode -eq 'All'){
    throw 'WIN11P BLOCKED: all-lane mode requires the independently reviewed QA Central plan to pin this exact SHA and the authorized lane-specific disposable environment and genuine operator acknowledgement. No lane was started.'
}
$runId=[guid]::NewGuid().ToString('N')
$evidence=(Resolve-Path -LiteralPath (New-Item -ItemType Directory -Force -Path $EvidenceDirectory)).Path
$backend=Join-Path $repo 'backend'
$mvn=(Get-Command mvn.cmd -ErrorAction Stop).Source
$inventoryPath=Join-Path $backend 'target\win11p-test-method-inventory.json'
$inventoryReport=Join-Path $backend ('target\win11p-reports\'+$runId+'\discovery')
$ordinaryReport=Join-Path $backend ('target\win11p-reports\'+$runId+'\ordinary')
$laneResults=@()
$commands=@(
    "& '$mvn' -B -ntp -Pwin11p-discovery '-Dwin11p.run.id=$runId' '-Dwin11p.lane=discovery' '-Dwin11p.source.sha=$sha' test",
    "& '$mvn' -B -ntp -Pwin11p-ordinary '-Dwin11p.run.id=$runId' '-Dwin11p.lane=ordinary' '-Dwin11p.source.sha=$sha' verify"
)
$child1=Invoke-Win11pChildProcess -PowerShellCommand $commands[0] -WorkingDirectory $backend -LogPath (Join-Path $evidence 'discovery.log') -Environment @{}
$discovery=ConvertFrom-Win11pSurefireReports -ReportDirectory $inventoryReport -Lane discovery
if($child1.exit_code -ne 0 -or $discovery.failures -or $discovery.errors -or $discovery.skipped){throw 'WIN11P STOP: test inventory discovery failed'}
if(-not(Test-Path -LiteralPath $inventoryPath -PathType Leaf)){throw 'WIN11P STOP: inventory artifact missing'}
$inventoryDoc=Get-Content -LiteralPath $inventoryPath -Raw|ConvertFrom-Json
if([string]$inventoryDoc.source_sha -ne $sha){throw 'WIN11P STOP: inventory SHA does not match source'}
$inventory=@($inventoryDoc.methods|ForEach-Object{[pscustomobject]@{id=[string]$_.id;tags=@($_.tags)}})
$assignments=@(Get-Win11pTestAssignment -Inventory $inventory)
$ordinaryExpected=@($assignments|Where-Object{$_.lane -eq 'ordinary'})
$child2=Invoke-Win11pChildProcess -PowerShellCommand $commands[1] -WorkingDirectory $backend -LogPath (Join-Path $evidence 'ordinary.log') -Environment @{}
$ordinary=ConvertFrom-Win11pSurefireReports -ReportDirectory $ordinaryReport -Lane ordinary
$executed=@($discovery.results)+@($ordinary.results)
$notRun=@($assignments|Where-Object{$_.lane -ne 'ordinary' -and $_.lane -ne 'discovery'}|ForEach-Object{[pscustomobject]@{id=$_.id;outcome='NOT_RUN';lane=$_.lane}})
$decision=Compare-Win11pTestMatrix -Inventory $inventory -Results @($executed+$notRun)
$manifest=[ordered]@{
    schema_version=1;run_id=$runId;source_sha=$sha;mode=$Mode
    inventory_methods=$inventory.Count;ordinary_expected_methods=$ordinaryExpected.Count;guarded_methods_not_run=$notRun.Count
    ordinary_testcases=$ordinary.tests;ordinary_failures=$ordinary.failures;ordinary_errors=$ordinary.errors;ordinary_skipped=$ordinary.skipped
    discovery_testcases=$discovery.tests;child_process_ids=@($child1.process_id,$child2.process_id)
    required_tests_not_run=$notRun;matrix_overall=$decision.overall;matrix_issues=$decision.issues
    ordinary_exit_code=$child2.exit_code;central_qa='NOT_RUN';win11b='NOT_READY'
}
$manifestPath=Join-Path $evidence 'matrix-results.json'
$manifest|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $manifestPath -Encoding utf8
Write-Output ('WIN11P ordinary lane: '+$ordinary.tests+' tests; failures='+$ordinary.failures+' errors='+$ordinary.errors+' skipped='+$ordinary.skipped)
Write-Output ('WIN11P inventory methods: '+$inventory.Count+'; ordinary expected='+$ordinaryExpected.Count+'; guarded NOT_RUN='+$notRun.Count)
Write-Output ('WIN11P matrix result: '+$decision.overall+'; evidence='+$manifestPath)
if($child2.exit_code -ne 0){exit 1}
if($decision.overall -ne 'PASS'){exit 2}
exit 0
