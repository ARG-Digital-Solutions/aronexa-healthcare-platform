[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{40}$')][string]$ExpectedCommit,
    [ValidateSet('Ordinary','All')][string]$Mode='Ordinary',
    [string]$EvidenceDirectory,
    [string]$AuthorizationPath,
    [ValidatePattern('^[0-9a-f]{64}$')][string]$AuthorizationSha256,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$')][string]$RequestId,
    [string]$StateDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
Import-Module (Join-Path $PSScriptRoot 'Win11p-MavenMatrix.psm1') -Force
$config=Get-Win11pMatrixConfig
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit){throw 'WIN11P STOP: exact source SHA mismatch'}
$dirty=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($dirty|Where-Object{$_.Trim()}).Count -gt 0){throw 'WIN11P STOP: tracked source is dirty'}

function Read-Win11pField {
    param($Object,[string]$Name,$Default=$null)
    if($null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name]){return $Object.$Name}
    return $Default
}
function Quote-Win11pArgument {
    param([string]$Value)
    return "'"+$Value.Replace("'","''")+"'"
}

# These verifier pins are supplied by the protected QA control plane. They must
# never be derived here from an authorization document or its declared reviewer.
$authorization=$null
$authorizedLanes=@{}
if($Mode -eq 'All'){
    if(-not $RequestId -or -not $AuthorizationPath -or -not $AuthorizationSha256){
        throw 'WIN11P STOP: reviewed execution authorization, request identity and separately supplied digest are required'
    }
    if($env:WIN11P_AUTHORIZATION_VERIFIED_SHA256 -cne $AuthorizationSha256 -or
       $env:WIN11P_AUTHORIZATION_REQUEST_ID -cne $RequestId){
        throw 'WIN11P STOP: reviewed execution authorization lacks protected control-plane verification'
    }
    if((Get-FileHash -LiteralPath $AuthorizationPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $AuthorizationSha256){
        throw 'WIN11P STOP: authorization artifact identity mismatch'
    }
    $authorization=Get-Content -LiteralPath $AuthorizationPath -Raw|ConvertFrom-Json
    if((Read-Win11pField $authorization 'source_sha') -cne $sha -or
       (Read-Win11pField $authorization 'request_id') -cne $RequestId -or
       (Read-Win11pField $authorization 'plan_id') -cne $config.plan_id -or
       (Read-Win11pField $authorization 'disposition') -cne 'APPROVED_FOR_EXECUTION' -or
       -not (Read-Win11pField $authorization 'reviewer') -or
       (Read-Win11pField $authorization 'qa_sha' '') -cnotmatch '^[0-9a-f]{40}$' -or
       (Read-Win11pField $authorization 'review_url' '') -cnotmatch '^https://github.com/ARG-Digital-Solutions/aronexa-qa-central/'){
        throw 'WIN11P STOP: reviewed exact-source plan authorization is incomplete'
    }
    foreach($inputLane in @(Read-Win11pField $authorization 'lanes' @())){
        $id=[string](Read-Win11pField $inputLane 'id' '')
        if($id -notin @($config.lanes.id) -or $id -in @('discovery','ordinary') -or $authorizedLanes.ContainsKey($id)){
            throw 'WIN11P STOP: duplicate or unknown guarded lane authorization'
        }
        $authorizedLanes[$id]=$inputLane
    }
}
$runId=[guid]::NewGuid().ToString('N')
if(-not $RequestId){$RequestId=$runId}
if([string]::IsNullOrWhiteSpace($EvidenceDirectory)){$EvidenceDirectory=Join-Path $repo 'target\win11p-evidence'}
$evidence=(Resolve-Path -LiteralPath (New-Item -ItemType Directory -Force -Path $EvidenceDirectory)).Path
if([string]::IsNullOrWhiteSpace($StateDirectory)){$StateDirectory=Join-Path $evidence 'owned-db-state'}
$state=(Resolve-Path -LiteralPath (New-Item -ItemType Directory -Force -Path $StateDirectory)).Path
$backend=Join-Path $repo 'backend'
$inventoryPath=Join-Path $backend 'target\win11p-test-method-inventory.json'
$methodResults=[System.Collections.Generic.List[object]]::new()
$matrixIssues=[System.Collections.Generic.List[string]]::new()
$ownedDatabases=[System.Collections.Generic.List[object]]::new()
$resourceObservations=[System.Collections.Generic.List[object]]::new()
$cleanupObservations=[System.Collections.Generic.List[object]]::new()
$inventory=@()
$assignments=@()
$lanes=@{}
$laneResults=[System.Collections.Generic.List[object]]::new()
$cleanupStatus='PASS'
$schedule=@('discovery','ordinary','pilot05-phase-a','pilot05-phase-b',
    'pilot05-rc03-phase-a','pilot05-rc03-phase-b','pilot06-browser',
    'pilot07-recovery','pilot07-operator','pilot09-operator')
if(@($config.lanes).Count -ne 10 -or @($schedule|Where-Object{$_ -notin @($config.lanes.id)}).Count -gt 0){
    throw 'WIN11P STOP: ten-lane scheduling contract differs from reviewed config'
}
foreach($lane in $schedule){
    $expected=if($lane -eq 'ordinary'){343}else{1}
    $row=[pscustomobject][ordered]@{
        lane=$lane;status='NOT_RUN';exit_code=$null;tests=0;failures=0;errors=0;skipped=0;not_run=$expected
        expected_methods=$expected;parser_issues=@();powershell_process_id=$null;timed_out=$false;java_home=$null
        observed_java_homes=@();cleanup_complete=$null
        report_directory=(Join-Path $backend ('target\win11p-reports\'+$runId+'\'+$lane))
        resource_run_id=$null;preview_port=$null;operator_run_directory=$null
        jvm_provenance=[pscustomobject]@{status='NOT_OBSERVED';phase_a_pid=$null;phase_b_pid=$null;source=$null}
        reason='NOT_REQUESTED'
    }
    $lanes[$lane]=$row
    $laneResults.Add($row)
}

function Get-Win11pJavaSetting {
    param($InputLane)
    $trusted=[Environment]::GetEnvironmentVariable('WIN11P_PORTABLE_JAVA_HOME','Process')
    if([string]::IsNullOrWhiteSpace($trusted)){
        if($Mode -eq 'All'){throw 'Trusted portable Java home is required for guarded execution'}
        return $null
    }
    $trusted=(Resolve-Path -LiteralPath $trusted).Path.TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)
    $requested=[string](Read-Win11pField $InputLane 'java_home' $trusted)
    $javaHome=(Resolve-Path -LiteralPath $requested).Path.TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)
    if($javaHome -cne $trusted -and -not $javaHome.StartsWith($trusted+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){
        throw 'Lane Java home is outside the trusted portable runtime'
    }
    $java=Join-Path $javaHome 'bin\java.exe'
    if(-not(Test-Path -LiteralPath $java -PathType Leaf)){$java=Join-Path $javaHome 'bin/java'}
    if(-not(Test-Path -LiteralPath $java -PathType Leaf) -or
       -not(Test-Path -LiteralPath (Join-Path $javaHome 'release') -PathType Leaf) -or
       (Get-Content -LiteralPath (Join-Path $javaHome 'release') -Raw) -notmatch '(?m)^JAVA_VERSION="21(?:[.\-+]|\")'){
        throw 'Trusted runtime is not a portable Java 21 home'
    }
    return [pscustomobject]@{home=$javaHome;java=$java}
}

function Get-Win11pLaneEnvironment {
    param([string]$Lane,$InputLane,$Database)
    $environment=@{}
    $allowed=@{
        'pilot05-phase-a'=@();'pilot05-phase-b'=@()
        'pilot05-rc03-phase-a'=@('ARONEXA_PILOT06_QA_ROOT','ARONEXA_PILOT08_RC03_PREVIEW_PORT')
        'pilot05-rc03-phase-b'=@('ARONEXA_PILOT06_QA_ROOT','ARONEXA_PILOT08_RC03_PREVIEW_PORT')
        'pilot06-browser'=@('ARONEXA_PILOT06_BROWSER','ARONEXA_PILOT06_QA_ROOT')
        'pilot07-recovery'=@('ARONEXA_PILOT07_RECOVERY_ACK')
        'pilot07-operator'=@('ARONEXA_PILOT07_ACK','ARONEXA_PILOT07_RUN_DIR')
        'pilot09-operator'=@('ARONEXA_PILOT06_QA_ROOT','ARONEXA_PILOT08_RC03_PREVIEW_PORT','ARONEXA_PILOT09_INTERACTIVE_ACK','ARONEXA_PILOT09_RUN_DIR')
    }
    $supplied=Read-Win11pField $InputLane 'environment'
    if($null -ne $supplied){
        foreach($property in $supplied.PSObject.Properties){
            if($property.Name -notin $allowed[$Lane]){throw 'Unauthorized cross-lane environment override'}
            $environment[$property.Name]=[string]$property.Value
        }
    }
    if($Lane -in @('pilot05-rc03-phase-a','pilot05-rc03-phase-b','pilot06-browser','pilot09-operator')){
        $trustedQa=[Environment]::GetEnvironmentVariable('WIN11P_QA_CENTRAL_PATH','Process')
        if($trustedQa){
            $trustedQa=(Resolve-Path -LiteralPath $trustedQa).Path
            if($environment.ContainsKey('ARONEXA_PILOT06_QA_ROOT') -and
               (Resolve-Path -LiteralPath $environment['ARONEXA_PILOT06_QA_ROOT']).Path -cne $trustedQa){
                throw 'Lane QA root differs from verified QA archive'
            }
            if($Mode -eq 'All' -and $env:WIN11P_QA_SHA -cne $authorization.qa_sha){throw 'Verified QA archive source mismatch'}
            $environment['ARONEXA_PILOT06_QA_ROOT']=$trustedQa
        }
    }
    $laneConfig=@($config.lanes|Where-Object{$_.id -eq $Lane})[0]
    $ackName=[string](Read-Win11pField $laneConfig 'ack_env' '')
    if($ackName){
        $acknowledged=[datetimeoffset]::MinValue
        if(-not (Read-Win11pField $InputLane 'operator') -or
           -not [datetimeoffset]::TryParse([string](Read-Win11pField $InputLane 'acknowledged_at' ''),[ref]$acknowledged) -or
           (Read-Win11pField $InputLane 'authorization_url' '') -cnotmatch '^https://github.com/ARG-Digital-Solutions/aronexa-qa-central/' -or
           -not $environment.ContainsKey($ackName) -or $environment[$ackName] -cne $laneConfig.ack_value){
            throw 'Genuine reviewed lane acknowledgement is missing'
        }
    }
    if($Lane -like '*rc03*' -or $Lane -eq 'pilot09-operator'){
        if(-not $environment.ContainsKey('ARONEXA_PILOT06_QA_ROOT') -or
           -not(Test-Path -LiteralPath $environment['ARONEXA_PILOT06_QA_ROOT'] -PathType Container)){
            throw 'Reviewed RC03 QA checkout is missing'
        }
        # This is a disposable fixture scope flag, never an operator acknowledgement.
        $environment['ARONEXA_PILOT08_RC03_CONFIRM']='RC03_DISPOSABLE_BROWSER_ONLY'
        if(-not $environment.ContainsKey('ARONEXA_PILOT08_RC03_PREVIEW_PORT')){$environment['ARONEXA_PILOT08_RC03_PREVIEW_PORT']='5186'}
        if($environment['ARONEXA_PILOT08_RC03_PREVIEW_PORT'] -cne '5186'){throw 'RC03 requires the approved preview port'}
        $lanes[$Lane].preview_port=5186
    }
    if($Lane -in @('pilot07-operator','pilot09-operator')){
        $number=if($Lane -eq 'pilot09-operator'){'09'}else{'07'}
        $key='ARONEXA_PILOT'+$number+'_RUN_DIR'
        if(-not $env:LOCALAPPDATA -or -not $environment.ContainsKey($key)){throw 'Reviewed operator run directory is missing'}
        $run=(Resolve-Path -LiteralPath $environment[$key]).Path
        $allowedRoot=(Join-Path $env:LOCALAPPDATA ('ARG-Digital\AroNexa-Pilot'+$number+'-Disposable'))
        $marker=Join-Path $run 'LAB-TEST-ONLY.marker'
        if((Split-Path $run -Parent) -cne $allowedRoot -or
           (Split-Path $run -Leaf) -cnotmatch ('^pilot'+$number+'-[0-9a-f]{32}$') -or
           -not(Test-Path -LiteralPath $marker -PathType Leaf) -or
           (Get-Content -LiteralPath $marker -Raw).Trim() -cne ('FICTIONAL_DISPOSABLE_ONLY_SOURCE_SHA='+$sha) -or
           (Test-Path -LiteralPath (Join-Path $run 'STOP'))){throw 'Operator-created disposable marker is invalid or stale'}
        $lanes[$Lane].operator_run_directory=$run
    }
    if($null -ne $Database){
        $environment['ARONEXA_PILOT05_PORT']=[string]$Database.port
        $environment['ARONEXA_PILOT05_RUN_ID']=[string]$Database.run_id
        $environment['ARONEXA_PILOT05_CONFIRM']='TEST_ONLY_DISPOSABLE'
        $environment['ARONEXA_PILOT05_PHASE']=if($Lane.EndsWith('phase-b')){'B'}else{'A'}
        $lanes[$Lane].resource_run_id=[string]$Database.run_id
    }
    return $environment
}

function Invoke-Win11pLane {
    param([string]$Lane,$InputLane,$Database)
    $row=$lanes[$Lane]
    $row.reason='EXECUTED'
    try{
        $environment=if($Lane -in @('discovery','ordinary')){@{}}else{Get-Win11pLaneEnvironment -Lane $Lane -InputLane $InputLane -Database $Database}
        $runtime=Get-Win11pJavaSetting -InputLane $InputLane
        $args=@('-B','-ntp',('-Dwin11p.run.id='+$runId),('-Dwin11p.lane='+$Lane),('-Dwin11p.source.sha='+$sha))
        if($null -ne $runtime){
            $row.java_home=$runtime.home
            # Maven compiles with the runner's JDK; Surefire forks the trusted
            # portable runtime, which may intentionally omit javac.
            $args+=('-Djvm='+$runtime.java)
        }
        if($Lane -eq 'discovery'){$args+=@('-Pwin11p-discovery','test')}
        elseif($Lane -eq 'ordinary'){$args+=@('-Pwin11p-ordinary','verify')}
        else{
            $selected=@($assignments|Where-Object{$_.lane -eq $Lane})
            if($selected.Count -ne 1){throw 'Guarded lane requires exactly one assigned method'}
            $args+=@('-Pwin11p-method',('-Dwin11p.test.tag='+$selected[0].tag),('-Dtest='+$selected[0].id),'test')
        }
        $command='& '+(Quote-Win11pArgument $mvn)+' '+(($args|ForEach-Object{Quote-Win11pArgument $_}) -join ' ')+'; exit $LASTEXITCODE'
        $logPath=Join-Path $evidence ($Lane+'.log')
        $child=Invoke-Win11pChildProcess -PowerShellCommand $command -WorkingDirectory $backend -LogPath $logPath -Environment $environment
        $row.exit_code=[int]$child.exit_code
        $row.powershell_process_id=[int]$child.process_id
        $row.timed_out=[bool](Read-Win11pField $child 'timed_out' $false)
        $row.cleanup_complete=[bool](Read-Win11pField $child 'cleanup_complete' $false)
        $parsed=ConvertFrom-Win11pSurefireReports -ReportDirectory $row.report_directory -Lane $Lane
        $row.tests=[int]$parsed.tests;$row.failures=[int]$parsed.failures;$row.errors=[int]$parsed.errors;$row.skipped=[int]$parsed.skipped
        $row.parser_issues=@($parsed.issues)
        $row.observed_java_homes=@(Read-Win11pField $parsed 'java_homes' @())
        if($null -ne $runtime -and ($row.observed_java_homes.Count -ne 1 -or
           [string]$row.observed_java_homes[0] -cne $runtime.home)){
            $row.parser_issues+=@('portable fork Java home was not uniquely observed')
        }
        foreach($case in @($parsed.results)){
            $methodResults.Add([pscustomobject]@{id=[string]$case.id;lane=[string]$case.lane;outcome=[string]$case.outcome})
        }
        $expectedIds=if($Lane -eq 'discovery'){
            @($config.guarded_tests|Where-Object{$_.lane -eq 'discovery'}|ForEach-Object{[string]$_.id})
        }else{@($assignments|Where-Object{$_.lane -eq $Lane}|ForEach-Object{[string]$_.id})}
        $observedIds=@($parsed.results|ForEach-Object{[string]$_.id})
        if(@($expectedIds|Where-Object{$_ -notin $observedIds}).Count -gt 0 -or
           @($observedIds|Where-Object{$_ -notin $expectedIds}).Count -gt 0 -or
           @($observedIds|Select-Object -Unique).Count -ne $observedIds.Count -or
           @($parsed.results|Where-Object{$_.lane -cne $Lane}).Count -gt 0){
            $row.parser_issues+=@('lane method identities do not match the exact assigned source methods')
        }
        if($row.exit_code -eq 0 -and -not $row.timed_out -and $row.cleanup_complete -and $row.parser_issues.Count -eq 0 -and
           $row.tests -eq $row.expected_methods -and $row.failures -eq 0 -and $row.errors -eq 0 -and $row.skipped -eq 0){$row.status='PASS'}
        else{$row.status='NOT_PASS'}
        $patterns=@{
            'pilot05-phase-a'='(?m)^PILOT05_PHASE_A_PASS JVM_PID=(\d+)\s*$'
            'pilot05-phase-b'='(?m)^PILOT05_PHASE_B_PASS JVM_PID=(\d+)\s*$'
            'pilot05-rc03-phase-a'='(?m)^PILOT08_RC03_PHASE_A_PID=(\d+)\s*$'
            'pilot05-rc03-phase-b'='(?m)^PILOT08_RC03_PHASE_B_PID=(\d+)\s*$'
        }
        if($patterns.ContainsKey($Lane)){
            $markers=[regex]::Matches([IO.File]::ReadAllText($logPath),$patterns[$Lane])
            if($markers.Count -eq 1 -and [long]$markers[0].Groups[1].Value -gt 0){
                $pidValue=[long]$markers[0].Groups[1].Value
                $row.jvm_provenance.status='OBSERVED';$row.jvm_provenance.source='JAVA_STDOUT_MARKER'
                if($Lane.EndsWith('phase-a')){$row.jvm_provenance.phase_a_pid=$pidValue}
                else{
                    $a=$lanes[$Lane.Substring(0,$Lane.Length-1)+'a'].jvm_provenance.phase_a_pid
                    $row.jvm_provenance.phase_a_pid=$a;$row.jvm_provenance.phase_b_pid=$pidValue
                    if($null -eq $a -or $a -eq $pidValue){$row.status='NOT_PASS';$row.parser_issues+=@('distinct Java process identity was not observed')}
                }
            }else{$row.status='NOT_PASS';$row.parser_issues+=@('unique Java process marker missing')}
        }
    }catch{
        $row.status='NOT_PASS';$row.reason='EXECUTION_ERROR'
        if($null -eq $row.exit_code){$row.exit_code=1}
        $row.parser_issues+=@('lane execution or prerequisite failed')
    }
}

function New-Win11pMatrixDatabase {
    param([ValidateSet('legacy','rc03','operator')][string]$Purpose)
    $database=New-Win11pOwnedDatabase -SourceSha $sha -RequestId $RequestId -StateDirectory $state -Purpose $Purpose
    $ownedDatabases.Add($database)
    if([int]$database.server_version_num -lt 170000 -or [int]$database.server_version_num -ge 180000 -or
       -not [bool]$database.loopback){throw 'Owned database did not establish PostgreSQL 17 on loopback'}
    $resourceObservations.Add([pscustomobject][ordered]@{
        purpose=$Purpose;run_id=[string]$database.run_id;port=[int]$database.port
        container_id=[string]$database.container_id;container_name=[string]$database.container_name
        image_id=[string]$database.image_id
        database_name=[string]$database.database_name;server_version=[int]$database.server_version_num
        loopback=[bool]$database.loopback;cleanup_status='PENDING'
    })
    return $database
}

$startupRecovered=$false
try{
    # Recovery only uses this request's identity journals. Conflicts fail closed.
    foreach($observation in @(Recover-Win11pOwnedDatabases -StateDirectory $state -RequestId $RequestId)){
        $cleanupObservations.Add([pscustomobject]@{phase='recovery_before';purpose=[string]$observation.purpose;run_id=[string]$observation.run_id;status=[string]$observation.status})
        if($observation.status -ne 'PASS'){throw 'Owned database recovery failed'}
    }
    $startupRecovered=$true
    $mavenCommand=Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if($null -eq $mavenCommand){$mavenCommand=Get-Command mvn -ErrorAction Stop}
    $mvn=$mavenCommand.Source
    Invoke-Win11pLane -Lane discovery -InputLane $null -Database $null
    if($lanes.discovery.status -eq 'PASS'){
        if(-not(Test-Path -LiteralPath $inventoryPath -PathType Leaf)){throw 'Inventory artifact missing'}
        $inventoryDocument=Get-Content -LiteralPath $inventoryPath -Raw|ConvertFrom-Json
        if([string]$inventoryDocument.source_sha -cne $sha){throw 'Inventory SHA differs from source'}
        $inventory=@($inventoryDocument.methods|ForEach-Object{[pscustomobject]@{id=[string]$_.id;tags=@($_.tags|ForEach-Object{[string]$_})}})
        if($inventory.Count -ne 352){throw 'Exact source inventory must contain 352 methods'}
        $assignments=@(Get-Win11pTestAssignment -Inventory $inventory)
        foreach($row in $laneResults){
            $row.expected_methods=@($assignments|Where-Object{$_.lane -eq $row.lane}).Count
            $row.not_run=$row.expected_methods
        }
        if($lanes.ordinary.expected_methods -ne 343){throw 'Ordinary source assignment must contain 343 methods'}
        Invoke-Win11pLane -Lane ordinary -InputLane $null -Database $null
        if($Mode -eq 'All' -and $lanes.ordinary.status -eq 'PASS'){
            $databases=@{}
            foreach($lane in @($schedule|Select-Object -Skip 2)){
                if(-not $authorizedLanes.ContainsKey($lane)){$lanes[$lane].reason='AUTHORIZATION_MISSING';continue}
                $purpose=if($lane -like '*rc03*'){'rc03'}elseif($lane -like 'pilot05-*'){'legacy'}elseif($lane -eq 'pilot09-operator'){'operator'}else{''}
                if($lane.EndsWith('phase-b')){
                    $aLane=$lane.Substring(0,$lane.Length-1)+'a'
                    if($lanes[$aLane].status -ne 'PASS'){$lanes[$lane].reason='PHASE_A_NOT_PASS';continue}
                }
                $database=$null
                try{
                    if($purpose){
                        # Validate reviewed controls before allocating a new database.
                        [void](Get-Win11pLaneEnvironment -Lane $lane -InputLane $authorizedLanes[$lane] -Database $null)
                        [void](Get-Win11pJavaSetting -InputLane $authorizedLanes[$lane])
                        if(-not $databases.ContainsKey($purpose)){$databases[$purpose]=New-Win11pMatrixDatabase -Purpose $purpose}
                        $database=$databases[$purpose]
                    }
                    Invoke-Win11pLane -Lane $lane -InputLane $authorizedLanes[$lane] -Database $database
                }catch{
                    $lanes[$lane].status='NOT_PASS';$lanes[$lane].exit_code=1;$lanes[$lane].reason='RESOURCE_OR_CONTROL_ERROR'
                    $lanes[$lane].parser_issues+=@('owned database or reviewed prerequisite failed')
                }
            }
        }
    }
}catch{
    $matrixIssues.Add('coordinator prerequisite or resource recovery failed')
    if(-not $startupRecovered){$cleanupStatus='NOT_PASS'}
}finally{
    foreach($database in $ownedDatabases){
        $status='NOT_PASS'
        try{
            $observation=Stop-Win11pOwnedDatabase -IdentityFile $database.identity_file
            if($observation.status -eq 'PASS'){$status='PASS'}
        }catch{ }
        if($status -ne 'PASS'){$cleanupStatus='NOT_PASS';$matrixIssues.Add('owned database teardown failed')}
        $purpose=''
        foreach($resource in $resourceObservations){
            if($resource.run_id -eq $database.run_id){$resource.cleanup_status=$status;$purpose=$resource.purpose}
        }
        $cleanupObservations.Add([pscustomobject]@{phase='finally';purpose=$purpose;run_id=[string]$database.run_id;status=$status})
    }
    try{
        foreach($observation in @(Recover-Win11pOwnedDatabases -StateDirectory $state -RequestId $RequestId)){
            $cleanupObservations.Add([pscustomobject]@{phase='recovery_after';purpose=[string]$observation.purpose;run_id=[string]$observation.run_id;status=[string]$observation.status})
            if($observation.status -ne 'PASS'){$cleanupStatus='NOT_PASS';$matrixIssues.Add('owned database journal recovery failed')}
        }
    }catch{$cleanupStatus='NOT_PASS';$matrixIssues.Add('owned database journal recovery failed')}
}

$executedIds=@($methodResults|ForEach-Object{$_.id})
foreach($test in $assignments){
    if($test.id -notin $executedIds){$methodResults.Add([pscustomobject]@{id=[string]$test.id;lane=[string]$test.lane;outcome='NOT_RUN'})}
}
foreach($row in $laneResults){
    $row.not_run=@($methodResults|Where-Object{$_.lane -eq $row.lane -and $_.outcome -eq 'NOT_RUN'}).Count
    if($inventory.Count -eq 0){$row.not_run=[Math]::Max(0,$row.expected_methods-$row.tests)}
    foreach($issue in @($row.parser_issues)){$matrixIssues.Add($row.lane+': '+[string]$issue)}
    if($row.status -ne 'PASS'){$matrixIssues.Add('lane '+$row.lane+' '+$row.status)}
    if($null -ne $row.exit_code -and $row.exit_code -ne 0){$matrixIssues.Add('nonzero Maven exit in '+$row.lane)}
}
if($inventory.Count -eq 0){
    $decision=[pscustomobject]@{overall='NOT_PASS';issues=@($matrixIssues.ToArray())+@('source method inventory missing')}
}else{
    $decision=Compare-Win11pTestMatrix -Inventory $inventory -Results @($methodResults.ToArray()) -ReportIssues @($matrixIssues.ToArray())
}
$matrixExitCode=if(@($laneResults|Where-Object{$null -ne $_.exit_code -and $_.exit_code -ne 0}).Count -gt 0){1}else{2}
$manifest=[ordered]@{
    schema_version=2;run_id=$runId;request_id=$RequestId;source_sha=$sha;mode=$Mode
    inventory=@($inventory);method_results=@($methodResults);lane_results=@($laneResults)
    inventory_methods=$inventory.Count;executed_methods=@($methodResults|Where-Object{$_.outcome -ne 'NOT_RUN'}).Count
    ordinary_expected_methods=$lanes.ordinary.expected_methods
    guarded_methods_not_run=($laneResults|Where-Object{$_.lane -notin @('ordinary','discovery')}|Measure-Object -Property not_run -Sum).Sum
    resource_observations=@($resourceObservations);cleanup_observations=@($cleanupObservations);cleanup_status=$cleanupStatus
    matrix_overall=$decision.overall;matrix_issues=@($decision.issues);matrix_exit_code=$matrixExitCode
    encrypted_recovery='NOT_IMPLEMENTED_IN_EXISTING_TEST';full_verification='NOT_PASS'
    central_qa='NOT_RUN';win11b='NOT_READY';rule27='HOLD'
}
$manifestPath=Join-Path $evidence 'matrix-results.json'
$manifest|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $manifestPath -Encoding utf8
Write-Output ('WIN11P inventory methods: '+$inventory.Count+'; executed='+$manifest.executed_methods+'; guarded NOT_RUN='+$manifest.guarded_methods_not_run)
Write-Output ('WIN11P method matrix: '+$decision.overall+'; full verification NOT_PASS; evidence='+$manifestPath)
# Existing Pilot07 proves unencrypted restore only. Lifecycle execution cannot
# turn that unmet encrypted recovery requirement into a complete verification.
exit $matrixExitCode
