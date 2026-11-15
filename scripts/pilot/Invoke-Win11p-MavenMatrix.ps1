[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{40}$')][string]$ExpectedCommit,
    [ValidateSet('Ordinary','All')][string]$Mode='Ordinary',
    [string]$EvidenceDirectory,
    [string]$AuthorizationPath,
    [ValidatePattern('^[0-9a-f]{64}$')][string]$AuthorizationSha256
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
$authorization=$null
if($Mode -eq 'All'){
    if(-not $AuthorizationPath -or -not $AuthorizationSha256){throw 'WIN11P STOP: reviewed execution authorization and its separately supplied digest are required'}
    if((Get-FileHash -LiteralPath $AuthorizationPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $AuthorizationSha256){throw 'WIN11P STOP: authorization artifact identity mismatch'}
    $authorization=Get-Content -LiteralPath $AuthorizationPath -Raw|ConvertFrom-Json
    if($authorization.source_sha -ne $sha -or $authorization.plan_id -ne $config.plan_id -or
       $authorization.disposition -ne 'APPROVED_FOR_EXECUTION' -or -not $authorization.reviewer -or
       $authorization.qa_sha -notmatch '^[0-9a-f]{40}$' -or $authorization.review_url -notmatch '^https://github.com/ARG-Digital-Solutions/aronexa-qa-central/'){
        throw 'WIN11P STOP: reviewed exact-source plan authorization is incomplete'
    }
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
$guarded=@($assignments|Where-Object{$_.lane -ne 'ordinary' -and $_.lane -ne 'discovery'})
$laneEvidence=@()
if($Mode -eq 'All' -and $child2.exit_code -eq 0){
    foreach($test in $guarded){
        $inputs=@($authorization.lanes|Where-Object{$_.id -eq $test.lane})
        if($inputs.Count -eq 0){continue}
        if($inputs.Count -ne 1){throw 'WIN11P STOP: duplicate lane authorization'}
        $input=$inputs[0]
        $laneConfig=@($config.lanes|Where-Object{$_.id -eq $test.lane})[0]
        $environment=@{}
        $allowedEnvironment=@{
            'pilot05-phase-a'=@('ARONEXA_PILOT05_PORT','ARONEXA_PILOT05_RUN_ID','ARONEXA_PILOT05_CONFIRM','ARONEXA_PILOT05_PHASE')
            'pilot05-phase-b'=@('ARONEXA_PILOT05_PORT','ARONEXA_PILOT05_RUN_ID','ARONEXA_PILOT05_CONFIRM','ARONEXA_PILOT05_PHASE')
            'pilot06-browser'=@('ARONEXA_PILOT06_BROWSER','ARONEXA_PILOT06_QA_ROOT')
            'pilot07-recovery'=@('ARONEXA_PILOT07_RECOVERY_ACK')
            'pilot07-operator'=@('ARONEXA_PILOT07_ACK','ARONEXA_PILOT07_RUN_DIR')
        }
        $rc03Names=@('ARONEXA_PILOT05_PORT','ARONEXA_PILOT05_RUN_ID','ARONEXA_PILOT05_CONFIRM','ARONEXA_PILOT05_PHASE','ARONEXA_PILOT08_RC03_CONFIRM','ARONEXA_PILOT08_RC03_PREVIEW_PORT','ARONEXA_PILOT06_QA_ROOT')
        $allowedEnvironment['pilot05-rc03-phase-a']=$rc03Names
        $allowedEnvironment['pilot05-rc03-phase-b']=$rc03Names
        $allowedEnvironment['pilot09-operator']=$rc03Names+@('ARONEXA_PILOT09_INTERACTIVE_ACK','ARONEXA_PILOT09_RUN_DIR')
        foreach($property in $input.environment.PSObject.Properties){
            if($property.Name -notin $allowedEnvironment[$test.lane]){throw 'WIN11P STOP: unauthorized cross-lane environment override'}
            $environment[$property.Name]=[string]$property.Value
        }
        if($laneConfig.PSObject.Properties['ack_env']){
            if(-not $input.operator -or -not $input.acknowledged_at -or -not $input.authorization_url -or
               $environment[$laneConfig.ack_env] -ne $laneConfig.ack_value){
                throw ('WIN11P STOP: genuine lane authorization missing: '+$test.lane)
            }
        }
        # Java retains all localhost, PG17 identity, marker, role/RLS and human-receipt assertions.
        # Pilot05 pairs receive the same operator-provisioned marked DB; distinct Maven invocations
        # guarantee separate forks. The Java phase-B assertion verifies recorded JVM identities.
        if($test.lane -like 'pilot05-*'){
            $phase=if($test.lane.EndsWith('phase-a')){'A'}else{'B'}
            if($environment['ARONEXA_PILOT05_PHASE'] -ne $phase){throw 'WIN11P STOP: Pilot05 phase mismatch'}
            $pairKey=if($test.lane -like '*rc03*'){'rc03'}else{'legacy'}
            $identity=$environment['ARONEXA_PILOT05_RUN_ID']+'|'+$environment['ARONEXA_PILOT05_PORT']
            if($phase -eq 'A') { Set-Variable -Name ('pair_'+$pairKey) -Value $identity }
            elseif((Get-Variable -Name ('pair_'+$pairKey) -ValueOnly -ErrorAction Stop) -ne $identity){throw 'WIN11P STOP: Pilot05 phases must share one marked database'}
        }
        $lane=$test.lane
        $command="& '$mvn' -B -ntp -Pwin11p-method '-Dwin11p.run.id=$runId' '-Dwin11p.lane=$lane' '-Dwin11p.test.tag=$($test.tag)' '-Dtest=$($test.id)' test"
        $child=Invoke-Win11pChildProcess -PowerShellCommand $command -WorkingDirectory $backend -LogPath (Join-Path $evidence ($lane+'.log')) -Environment $environment
        $parsed=ConvertFrom-Win11pSurefireReports -ReportDirectory (Join-Path $backend ('target\win11p-reports\'+$runId+'\'+$lane)) -Lane $lane
        $executed+=@($parsed.results)
        $laneEvidence+=@([pscustomobject]@{lane=$lane;process_id=$child.process_id;exit_code=$child.exit_code;tests=$parsed.tests;failures=$parsed.failures;errors=$parsed.errors;skipped=$parsed.skipped})
        if($child.exit_code -ne 0 -or $parsed.tests -ne 1 -or $parsed.failures -or $parsed.errors -or $parsed.skipped){break}
    }
}
$executedIds=@($executed|ForEach-Object{$_.id})
$notRun=@($guarded|Where-Object{$_.id -notin $executedIds}|ForEach-Object{[pscustomobject]@{id=$_.id;outcome='NOT_RUN';lane=$_.lane}})
$decision=Compare-Win11pTestMatrix -Inventory $inventory -Results @($executed+$notRun)
$manifest=[ordered]@{
    schema_version=1;run_id=$runId;source_sha=$sha;mode=$Mode
    inventory_methods=$inventory.Count;ordinary_expected_methods=$ordinaryExpected.Count;guarded_methods_not_run=$notRun.Count
    ordinary_testcases=$ordinary.tests;ordinary_failures=$ordinary.failures;ordinary_errors=$ordinary.errors;ordinary_skipped=$ordinary.skipped
    discovery_testcases=$discovery.tests;child_process_ids=@($child1.process_id,$child2.process_id)
    required_tests_not_run=$notRun;guarded_lane_results=$laneEvidence;matrix_overall=$decision.overall;matrix_issues=$decision.issues
    encrypted_recovery='NOT_IMPLEMENTED_IN_EXISTING_TEST';full_verification='NOT_PASS'
    ordinary_exit_code=$child2.exit_code;central_qa='NOT_RUN';win11b='NOT_READY'
}
$manifestPath=Join-Path $evidence 'matrix-results.json'
$manifest|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $manifestPath -Encoding utf8
Write-Output ('WIN11P ordinary lane: '+$ordinary.tests+' tests; failures='+$ordinary.failures+' errors='+$ordinary.errors+' skipped='+$ordinary.skipped)
Write-Output ('WIN11P inventory methods: '+$inventory.Count+'; ordinary expected='+$ordinaryExpected.Count+'; guarded NOT_RUN='+$notRun.Count)
Write-Output ('WIN11P matrix result: '+$decision.overall+'; evidence='+$manifestPath)
if($child2.exit_code -ne 0){exit 1}
if($decision.overall -ne 'PASS'){exit 2}
# The existing Pilot07 method explicitly proves UNENCRYPTED restore only.
# Test lifecycle authorization cannot silently turn that into encrypted recovery.
exit 2
