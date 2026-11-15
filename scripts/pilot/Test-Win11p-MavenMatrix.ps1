[CmdletBinding()]
param([ValidateSet('All','Parser','Resources','Child','Coordinator')][string]$TestSection='All')
$ErrorActionPreference = 'Stop'
$modulePath = Join-Path $PSScriptRoot 'Win11p-MavenMatrix.psm1'
if (-not (Test-Path -LiteralPath $modulePath)) {
    Write-Output 'RED: coordinator module and reconciliation contract are not implemented'
    exit 1
}
Import-Module $modulePath -Force -DisableNameChecking

function Assert-Condition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

if($TestSection -eq 'All'){
$config = Get-Win11pMatrixConfig
$inventory = @([pscustomobject]@{ id='sample.CoreTests#ordinaryCase'; tags=@() }) +
    @($config.guarded_tests | ForEach-Object {
        [pscustomobject]@{ id=[string]$_.id; tags=@([string]$_.tag) }
    })
$assignments = @(Get-Win11pTestAssignment -Inventory $inventory)
$complete = @($assignments | ForEach-Object {
    [pscustomobject]@{ id=$_.id; outcome='PASS'; lane=$_.lane }
})

function Assert-Blocked {
    param([string]$Name, [object[]]$Cases, [object[]]$Results)
    $decision = Compare-Win11pTestMatrix -Inventory $Cases -Results $Results
    Assert-Condition ($decision.overall -ne 'PASS') ($Name + ' incorrectly allowed PASS')
}
$decision = Compare-Win11pTestMatrix -Inventory $inventory -Results $complete
Assert-Condition ($decision.overall -eq 'PASS') 'complete unique inventory should PASS'

Assert-Blocked 'missing method' $inventory @($complete | Select-Object -Skip 1)
Assert-Blocked 'duplicate method' $inventory @($complete + $complete[0])
Assert-Blocked 'failed method' $inventory @($complete | ForEach-Object {
    if ($_.lane -eq 'pilot05-phase-a') {
        [pscustomobject]@{ id=$_.id; outcome='FAIL'; lane=$_.lane }
    } else { $_ }
})
Assert-Blocked 'errored method' $inventory @($complete | ForEach-Object {
    if ($_.lane -eq 'pilot05-phase-a') {
        [pscustomobject]@{ id=$_.id; outcome='ERROR'; lane=$_.lane }
    } else { $_ }
})
Assert-Blocked 'skipped method' $inventory @($complete | ForEach-Object {
    if ($_.lane -eq 'pilot05-phase-a') {
        [pscustomobject]@{ id=$_.id; outcome='SKIPPED'; lane=$_.lane }
    } else { $_ }
})
Assert-Blocked 'NOT_RUN operator lane' $inventory @($complete | Where-Object {$_.lane -ne 'pilot06-browser'})
Assert-Blocked 'method in wrong lane' $inventory @($complete | ForEach-Object {
    if ($_.lane -eq 'pilot05-phase-a') {
        [pscustomobject]@{ id=$_.id; outcome='PASS'; lane='ordinary' }
    } else { $_ }
})
Assert-Blocked 'duplicate discovery' @($inventory + $inventory[1]) $complete
$unknownTag = @($inventory | Where-Object {$_.lane -ne 'pilot05-phase-a'}) +
    @([pscustomobject]@{id='sample.X#y';tags=@('win11p-unknown')})
Assert-Blocked 'unknown tag' $unknownTag $complete

}

if($TestSection -in @('All','Parser')){
$reportRoot=Join-Path ([IO.Path]::GetTempPath()) ('win11p-surefire-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($reportRoot)
try {
    $xml=@'
<testsuite name="sample.ReportTests" LocalName="unrelated" NamespaceURI="urn:attribute-value" tests="3" failures="1" errors="0" skipped="1">
 <properties><property name="java.home" value="/synthetic/portable-java"/></properties>
 <testcase classname="sample.ReportTests" name="passes" />
 <testcase classname="sample.ReportTests" name="fails"><failure message="synthetic" /></testcase>
 <testcase classname="sample.ReportTests" name="skips"><skipped /></testcase>
</testsuite>
'@
    [IO.File]::WriteAllText((Join-Path $reportRoot 'TEST-synthetic.xml'),$xml)
    $parsed=ConvertFrom-Win11pSurefireReports -ReportDirectory $reportRoot -Lane ordinary
    Assert-Condition ($parsed.tests -eq 3 -and $parsed.failures -eq 1 -and $parsed.errors -eq 0 -and $parsed.skipped -eq 1) 'Surefire outcome parsing counts mismatch'

    Assert-Condition ($parsed.issues.Count -eq 0) 'valid XML unexpectedly has parser issues'
    Assert-Condition (@($parsed.java_homes).Count -eq 1 -and $parsed.java_homes[0] -eq '/synthetic/portable-java') 'observed suite Java home missing'
    $reportFile=Join-Path $reportRoot 'TEST-synthetic.xml'
    [IO.File]::WriteAllText($reportFile,'<testsuites name="suite wrapper" LocalName="unrelated" NamespaceURI="urn:attribute" tests="1" failures="0" errors="0" skipped="0"><testsuite name="sample.Wrapped" tests="1" failures="0" errors="0" skipped="0"><testcase classname="sample.Wrapped" name="runs"/></testsuite></testsuites>')
    $wrapped=ConvertFrom-Win11pSurefireReports -ReportDirectory $reportRoot -Lane ordinary
    Assert-Condition ($wrapped.tests -eq 1 -and $wrapped.issues.Count -eq 0 -and $wrapped.results[0].id -eq 'sample.Wrapped#runs') 'attribute-shaped suite wrapper or testcase identity was misread'

    $invalidReports=@{
        'counter mismatch'='<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase classname="sample.X" name="one"/></testsuite>'
        'negative counter'='<testsuite tests="-1" failures="0" errors="0" skipped="0"/>'
        'missing counter'='<testsuite><testcase classname="sample.X" name="one"/></testsuite>'
        'non integer counter'='<testsuite tests="1.0" failures="0" errors="0" skipped="0"><testcase classname="sample.X" name="one"/></testsuite>'
        'outcome counter mismatch'='<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="sample.X" name="one"><failure/></testcase></testsuite>'
        'duplicate identities'='<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase classname="sample.X" name="one"/><testcase classname="sample.X" name="one[2]"/></testsuite>'
        'empty identity'='<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="one"/></testsuite>'
        'contradictory outcome'='<testsuite tests="1" failures="1" errors="0" skipped="0"><testcase classname="sample.X" name="one"><failure/><skipped/></testcase></testsuite>'
        'malformed XML'='<testsuite'
        'name attribute masquerading as suite'='<unrelated name="testsuite" tests="1" failures="0" errors="0" skipped="0"><testcase classname="sample.X" name="one"/></unrelated>'
        'namespace attribute masquerading as unqualified suite'='<testsuite xmlns="urn:foreign" NamespaceURI="" name="testsuite" tests="0" failures="0" errors="0" skipped="0"/>'
        'non suite XML'='<unrelated><testcase classname="sample.X" name="one"/></unrelated>'
        'nested test case'='<testsuite tests="1" failures="0" errors="0" skipped="0"><other><testcase classname="sample.X" name="one"/></other></testsuite>'
        'DTD'='<!DOCTYPE testsuite [<!ENTITY x "one">]><testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="sample.X" name="&x;"/></testsuite>'
    }
    foreach($name in $invalidReports.Keys){
        [IO.File]::WriteAllText($reportFile,$invalidReports[$name])
        $bad=ConvertFrom-Win11pSurefireReports -ReportDirectory $reportRoot -Lane ordinary
        Assert-Condition ($bad.issues.Count -gt 0) ($name+' XML was not rejected')
    }
    [IO.File]::WriteAllText($reportFile,('<testsuite tests="0" failures="0" errors="0" skipped="0">'+(' '*5242881)+'</testsuite>'))
    $oversized=ConvertFrom-Win11pSurefireReports -ReportDirectory $reportRoot -Lane ordinary
    Assert-Condition ($oversized.issues.Count -gt 0 -and $oversized.tests -eq 0) 'oversized XML was parsed'
    [IO.File]::WriteAllText($reportFile,'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="sample.X" name="one"/></testsuite>')
    [IO.File]::WriteAllText((Join-Path $reportRoot 'TEST-second.xml'),'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="sample.X" name="one"/></testsuite>')
    $duplicates=ConvertFrom-Win11pSurefireReports -ReportDirectory $reportRoot -Lane ordinary
    Assert-Condition ($duplicates.issues.Count -gt 0 -and $duplicates.tests -eq 2) 'cross-report duplicates were hidden'
    Remove-Item -LiteralPath (Join-Path $reportRoot 'TEST-second.xml')
    [IO.File]::WriteAllText((Join-Path $reportRoot 'TEST-broken.xml'),'<broken')
    $mixed=ConvertFrom-Win11pSurefireReports -ReportDirectory $reportRoot -Lane ordinary
    Assert-Condition ($mixed.tests -eq 1 -and $mixed.issues.Count -gt 0) 'malformed extra report was hidden'
    $oneInventory=@([pscustomobject]@{id='sample.X#one';tags=@()})
    $aggregate=Compare-Win11pTestMatrix -Inventory $oneInventory -Results $mixed.results -ReportIssues $mixed.issues
    Assert-Condition ($aggregate.overall -eq 'NOT_PASS' -and $aggregate.issues.Count -gt 0) 'aggregate hid report issues'
    Remove-Item -LiteralPath $reportFile,(Join-Path $reportRoot 'TEST-broken.xml')
    $missing=ConvertFrom-Win11pSurefireReports -ReportDirectory $reportRoot -Lane ordinary
    Assert-Condition ($missing.issues.Count -gt 0 -and $missing.tests -eq 0) 'missing report allowed success'
    $noDirectory=ConvertFrom-Win11pSurefireReports -ReportDirectory (Join-Path $reportRoot absent) -Lane ordinary
    Assert-Condition ($noDirectory.issues.Count -gt 0 -and $noDirectory.tests -eq 0) 'missing report directory allowed success'
    Write-Output 'Parser checks PASS: Surefire name attributes, CLR property shadows, 20 invalid or missing report cases and outcome counts'
} finally { Remove-Item -LiteralPath $reportRoot -Recurse -Force }

}

if($TestSection -in @('All','Child')){
$parentBefore=[Environment]::GetEnvironmentVariable('ARONEXA_MATRIX_CHILD_SCOPE','Process')
$childTemp=Join-Path ([IO.Path]::GetTempPath()) ('win11p-child-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($childTemp)
try {
    $childLog=Join-Path $childTemp 'child.log'
    $child=Invoke-Win11pChildProcess -PowerShellCommand "Write-Output `$env:ARONEXA_MATRIX_CHILD_SCOPE" -WorkingDirectory $childTemp -LogPath $childLog -Environment @{ARONEXA_MATRIX_CHILD_SCOPE='CHILD_ONLY'}
    Assert-Condition ($child.exit_code -eq 0) 'child process exit code was not preserved'
    Assert-Condition (([IO.File]::ReadAllText($childLog)).Trim().Equals('CHILD_ONLY')) 'child did not receive scoped environment override'
    Assert-Condition ([Environment]::GetEnvironmentVariable('ARONEXA_MATRIX_CHILD_SCOPE','Process') -eq $parentBefore) 'child environment override leaked to parent'
    $timeout=Invoke-Win11pChildProcess -PowerShellCommand 'Start-Sleep -Seconds 30' -WorkingDirectory $childTemp -LogPath (Join-Path $childTemp 'timeout.log') -TimeoutSeconds 1
    Assert-Condition ($timeout.timed_out -and $timeout.exit_code -eq 124) 'child did not enforce bounded timeout'
    Assert-Condition ($null -eq (Get-Process -Id $timeout.process_id -ErrorAction SilentlyContinue)) 'timed-out owned child survived cleanup'
    $exit=Invoke-Win11pChildProcess -PowerShellCommand 'exit 7' -WorkingDirectory $childTemp -LogPath (Join-Path $childTemp 'nonpass.log')
    Assert-Condition ($exit.exit_code -eq 7 -and -not $exit.timed_out) 'non-pass child exit was hidden'
    $blockedNames=@('GH_TOKEN','GITHUB_TOKEN','ARONEXA_SOURCE_READ_TOKEN','ARONEXA_DEV_STATUS_TOKEN','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','MAVEN_OPTS','MAVEN_ARGS','NODE_OPTIONS','WIN11P_AUTHORIZATION_VERIFIED_SHA256','WIN11P_EXECUTION_AUTHORIZATION_PATH')
    $oldVariables=@{}
    try{
        foreach($name in $blockedNames){$oldVariables[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,'synthetic-must-not-inherit','Process')}
        $command='$names=@('+(($blockedNames|ForEach-Object {"'"+$_+"'"}) -join ',')+');foreach($name in $names){if([Environment]::GetEnvironmentVariable($name)){Write-Output $name}}'
        $scoped=Invoke-Win11pChildProcess -PowerShellCommand $command -WorkingDirectory $childTemp -LogPath (Join-Path $childTemp 'credentials.log')
        Assert-Condition ($scoped.exit_code -eq 0 -and [string]::IsNullOrWhiteSpace([IO.File]::ReadAllText($scoped.log_path))) 'inherited credentials or runtime injections reached child'
    }finally{foreach($name in $blockedNames){[Environment]::SetEnvironmentVariable($name,$oldVariables[$name],'Process')}}
    $sentinel=Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList @('-NoProfile','-NonInteractive','-Command','Start-Sleep -Seconds 30') -PassThru
    try{
        foreach($rootAction in @('exit 0','Start-Sleep -Seconds 30')){
            $descendantFile=Join-Path $childTemp ([guid]::NewGuid().ToString('N')+'.pid')
            $command='$descendant=Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList @(''-NoProfile'',''-NonInteractive'',''-Command'',''Start-Sleep -Seconds 30'') -PassThru;[IO.File]::WriteAllText('''+$descendantFile.Replace("'","''")+''',[string]$descendant.Id);'+$rootAction
            $tree=Invoke-Win11pChildProcess -PowerShellCommand $command -WorkingDirectory $childTemp -LogPath (Join-Path $childTemp 'tree.log') -TimeoutSeconds 2
            Assert-Condition (Test-Path -LiteralPath $descendantFile) 'tree cleanup fixture did not create a descendant'
            $descendant=Get-Process -Id ([int][IO.File]::ReadAllText($descendantFile)) -ErrorAction SilentlyContinue
            if($null -ne $descendant){
                $terminated=$descendant.WaitForExit(1000)
                $procStat='/proc/'+$descendant.Id+'/stat'
                if(-not $terminated -and (Test-Path -LiteralPath $procStat)){$terminated=([IO.File]::ReadAllText($procStat) -match '^\d+ \(.*\) Z ')}
                Assert-Condition $terminated ('owned descendant survived root action '+$rootAction)
                $descendant.Dispose()
            }
            Assert-Condition (-not $sentinel.HasExited) 'owned-tree cleanup killed an unrelated process'
        }
    }finally{if(-not $sentinel.HasExited){$sentinel.Kill();$sentinel.WaitForExit()};$sentinel.Dispose()}
    Write-Output 'Child checks PASS: scoped environment, bounded timeout, cleanup and non-pass exit'

} finally { Remove-Item -LiteralPath $childTemp -Recurse -Force }

}


if($TestSection -in @('All','Resources')){
    $resourceRoot=Join-Path ([IO.Path]::GetTempPath()) ('win11p-resource-'+[guid]::NewGuid().ToString('N'))
    [void][IO.Directory]::CreateDirectory($resourceRoot)
    $matrixModule=Get-Module Win11p-MavenMatrix
    # Docker is intentionally simulated: these tests exercise identity decisions and
    # persisted interruption recovery without assuming a Docker daemon exists.
    & $matrixModule {
        $script:dockerCalls=[System.Collections.Generic.List[object]]::new()
        $script:fixture=$null
        $script:failCommand=''
        $script:missingContainer=$false
        $script:observedVersionNum='170006'
        function script:Invoke-Win11pDocker {
            param([string[]]$DockerArguments,[switch]$AllowMissingContainer)
            $script:dockerCalls.Add(@($DockerArguments))
            if($DockerArguments[0] -eq $script:failCommand){throw 'synthetic Docker interruption'}
            $output=''
            switch($DockerArguments[0]){
                'create' {
                    $getArg={param($flag) $DockerArguments[[Array]::IndexOf($DockerArguments,$flag)+1]}
                    $labels=@{}
                    for($i=0;$i -lt $DockerArguments.Count;$i++){
                        if($DockerArguments[$i] -eq '--label'){
                            $parts=$DockerArguments[$i+1].Split('=',2);$labels[$parts[0]]=$parts[1]
                        }
                    }
                    $script:fixture=[pscustomobject]@{
                        Id=('a'*64);Image=('sha256:'+('c'*64));Name=('/'+(& $getArg '--name'))
                        Config=[pscustomobject]@{Image='postgres:17-alpine';Labels=[pscustomobject]$labels;Env=@('POSTGRES_DB=aronexa_pilot05_disposable_only','POSTGRES_USER=aronexa_pilot05_temporary')}
                        HostConfig=[pscustomobject]@{PortBindings=[pscustomobject]@{'5432/tcp'=@([pscustomobject]@{HostIp='127.0.0.1';HostPort=''})};Binds=@();Mounts=@()}
                        NetworkSettings=[pscustomobject]@{Ports=[pscustomobject]@{'5432/tcp'=@([pscustomobject]@{HostIp='127.0.0.1';HostPort='15432'})}}
                        Mounts=@()
                    }
                    $output=$script:fixture.Id
                }
                'inspect' {if($script:missingContainer){return [pscustomobject]@{exit_code=1;output=@();missing=$true}};$output=ConvertTo-Json -InputObject @($script:fixture) -Depth 12 -Compress}
                'exec' {
                    if(($DockerArguments -join ' ') -match 'SHOW server_version_num'){$output=$script:observedVersionNum}
                    elseif(($DockerArguments -join ' ') -match 'SELECT current_database'){$output='aronexa_pilot05_disposable_only|17.6'}
                }
            }
            return [pscustomobject]@{exit_code=0;output=@($output)}
        }
    }
    try {
        $owned=New-Win11pOwnedDatabase -SourceSha ('1'*40) -RequestId 'focused-request' -StateDirectory $resourceRoot -Purpose legacy
        Assert-Condition ($owned.port -eq 15432 -and $owned.run_id -match '^[0-9a-f-]{36}$' -and $owned.container_id -eq ('a'*64)) 'owned database identity is incomplete'
        Assert-Condition ($owned.server_version_num -eq 170006 -and $owned.loopback -and $owned.image_id -eq ('sha256:'+('c'*64))) 'observed numeric version/image/loopback evidence missing'
        Assert-Condition ($owned.server_version -eq '17.6') 'server version must be observed through PostgreSQL'
        $journal=Get-Content -LiteralPath $owned.identity_file -Raw | ConvertFrom-Json
        Assert-Condition ($journal.state -eq 'READY' -and $journal.request_id -eq 'focused-request') 'provisioning did not journal READY for request'
        $calls=& $matrixModule {$script:dockerCalls.ToArray()}
        Assert-Condition (@($calls | Where-Object {$_[0] -eq 'create'}).Count -eq 1) 'provisioning reused an existing database'
        Assert-Condition (($calls | Where-Object {$_[0] -eq 'create'}) -contains '127.0.0.1::5432') 'database did not request a random loopback port'
        Assert-Condition (@(($calls | ForEach-Object {$_ -join ' '}) -match 'role-bootstrap.sql').Count -gt 0) 'restricted role bootstrap was omitted'
        Assert-Condition (@(($calls | ForEach-Object {$_ -join ' '}) -match 'qa_guard.pilot05_run_marker').Count -gt 0) 'run marker bootstrap was omitted'
        $savedFixture=& $matrixModule {$script:fixture | ConvertTo-Json -Depth 12}
        $mutations=@{
            full_id={ $script:fixture.Id='b'*64 }
            name={ $script:fixture.Name='/shared-postgres' }
            run_label={ $script:fixture.Config.Labels.'com.argdigital.aronexa.pilot05.run'='00000000-0000-0000-0000-000000000000' }
            request_label={ $script:fixture.Config.Labels.'com.argdigital.aronexa.win11p.request'='other-request' }
            source_label={ $script:fixture.Config.Labels.'com.argdigital.aronexa.win11p.source'='2'*40 }
            database={ $script:fixture.Config.Env=@('POSTGRES_DB=original','POSTGRES_USER=aronexa_pilot05_temporary') }
            loopback={ $script:fixture.NetworkSettings.Ports.'5432/tcp'[0].HostIp='0.0.0.0' }
            port={ $script:fixture.NetworkSettings.Ports.'5432/tcp'[0].HostPort='5433' }
            image={ $script:fixture.Config.Image='postgres:16-alpine' }
            resolved_image={ $script:fixture.Image='sha256:'+('d'*64) }
            bind_mount={ $script:fixture.HostConfig.Binds=@('/original:/var/lib/postgresql/data') }
        }
        foreach($name in $mutations.Keys){
            & $matrixModule {param($json) $script:fixture=$json|ConvertFrom-Json} $savedFixture
            & $matrixModule $mutations[$name]
            $refused=$false
            try{Stop-Win11pOwnedDatabase -IdentityFile $owned.identity_file | Out-Null}catch{$refused=$true}
            Assert-Condition $refused ('cleanup allowed '+$name+' mismatch')
            $destructive=& $matrixModule {@($script:dockerCalls|Where-Object {$_[0] -in @('rm','stop','kill')}).Count}
            Assert-Condition ($destructive -eq 0) ('cleanup touched a resource after '+$name+' mismatch')
        }
        & $matrixModule {param($json) $script:fixture=$json|ConvertFrom-Json} $savedFixture
        $wrongRequest=$false
        try{Recover-Win11pOwnedDatabases -StateDirectory $resourceRoot -RequestId 'another-request'|Out-Null}catch{$wrongRequest=$true}
        $destructive=& $matrixModule {@($script:dockerCalls|Where-Object {$_[0] -eq 'rm'}).Count}
        Assert-Condition ($destructive -eq 0) 'recovery used another request journal'
        $recovered=@(Recover-Win11pOwnedDatabases -StateDirectory $resourceRoot -RequestId 'focused-request')
        Assert-Condition ($recovered.Count -eq 1) 'matching request recovery omitted journal'
        Assert-Condition ((Get-Content $owned.identity_file -Raw|ConvertFrom-Json).state -eq 'STOPPED') 'cleanup did not journal STOPPED'
        Stop-Win11pOwnedDatabase -IdentityFile $owned.identity_file|Out-Null
        $destructive=& $matrixModule {@($script:dockerCalls|Where-Object {$_[0] -eq 'rm'}).Count}
        Assert-Condition ($destructive -eq 1) 'idempotent cleanup repeated destructive action'
        # Interruption after create but before ID is persisted: PREPARED journal has
        # an exact requested name and full label identity; never discover by listing.
        $journal.state='PREPARED';$journal.container_id='';$journal.port=0
        [IO.File]::WriteAllText($owned.identity_file,($journal|ConvertTo-Json -Depth 8))
        [void](Recover-Win11pOwnedDatabases -StateDirectory $resourceRoot -RequestId 'focused-request')
        $destructive=& $matrixModule {@($script:dockerCalls|Where-Object {$_[0] -eq 'rm'}).Count}
        Assert-Condition ($destructive -eq 2) 'interrupted create could not recover exact labelled identity'
        $journal.state='STOPPING';$journal.container_id='a'*64;$journal.port=15432
        [IO.File]::WriteAllText($owned.identity_file,($journal|ConvertTo-Json -Depth 8))
        & $matrixModule {$script:missingContainer=$true}
        [void](Recover-Win11pOwnedDatabases -StateDirectory $resourceRoot -RequestId 'focused-request')
        Assert-Condition ((Get-Content $owned.identity_file -Raw|ConvertFrom-Json).state -eq 'STOPPED') 'interrupted removal did not reconcile exact-ID absence'
        & $matrixModule {$script:missingContainer=$false;$script:observedVersionNum='160009'}
        $versionRefused=$false
        try{New-Win11pOwnedDatabase -SourceSha ('1'*40) -RequestId 'wrong-version' -StateDirectory $resourceRoot -Purpose rc03|Out-Null}catch{$versionRefused=$_.Exception.Message -match 'major version'}
        Assert-Condition $versionRefused 'PostgreSQL16 unexpectedly accepted'
        $wrongVersion=@(Get-ChildItem $resourceRoot -Filter 'wrong-version--*.json')
        Assert-Condition ($wrongVersion.Count -eq 1 -and (Get-Content $wrongVersion[0].FullName -Raw|ConvertFrom-Json).state -eq 'STOPPED') 'wrong version provision did not clean its own journal resource'
        $broad=& $matrixModule {@($script:dockerCalls|Where-Object {$_[0] -in @('ps','prune','system')}).Count}
        Assert-Condition ($broad -eq 0) 'recovery used broad resource discovery or cleanup'
        Write-Output 'Owned resource checks PASS: journal/bootstrap, 11 identity refusals, request recovery and interruption recovery'
    } finally {
        Remove-Item -LiteralPath $resourceRoot -Recurse -Force
        Import-Module $modulePath -Force -DisableNameChecking
    }
}

if($TestSection -eq 'All'){
$coordinator=Join-Path $PSScriptRoot 'Invoke-Win11p-MavenMatrix.ps1'
$parseTokens=$null;$parseErrors=$null
[void][System.Management.Automation.Language.Parser]::ParseFile($coordinator,[ref]$parseTokens,[ref]$parseErrors)
Assert-Condition ($parseErrors.Count -eq 0) 'coordinator syntax failed'
$repo=(Resolve-Path (Join-Path (Join-Path $PSScriptRoot '..') '..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim()
$priorPreference=$ErrorActionPreference
$ErrorActionPreference='Continue'
$guardOutput=& (Get-Process -Id $PID).Path -NoProfile -File $coordinator -ExpectedCommit $sha -Mode All 2>&1
$guardExit=$LASTEXITCODE
$ErrorActionPreference=$priorPreference
Assert-Condition ($guardExit -ne 0) 'unreviewed All mode incorrectly allowed execution'
Assert-Condition (($guardOutput -join "`n") -match 'reviewed execution authorization|tracked source is dirty') 'All mode failed outside its expected authorization/clean-source guard'
Write-Output 'Win11p matrix reconciliation tests PASS: 14'

}


if($TestSection -in @('All','Coordinator')){
$config=Get-Win11pMatrixConfig
# Coordinator integration uses an isolated copied repository and mocked children,
# containers and control fixtures. No Maven, database or human session is run.
$coordinatorMockRoot=Join-Path ([IO.Path]::GetTempPath()) ('win11p-coordinator-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($coordinatorMockRoot)
$coordinatorMockSource=Join-Path $PSScriptRoot 'Invoke-Win11p-MavenMatrix.ps1'
$coordinatorMockModule=Get-Content -LiteralPath $modulePath -Raw
$coordinatorMockFunctions=@'

function Add-MockEvent {param($Event) $Event|ConvertTo-Json -Compress|Add-Content -LiteralPath $env:MOCK_EVENTS}
function Invoke-Win11pChildProcess {
 param([string]$PowerShellCommand,[string]$WorkingDirectory,[string]$LogPath,[hashtable]$Environment=@{})
 $lane=[regex]::Match($PowerShellCommand,"-Dwin11p.lane=([^']+)").Groups[1].Value
 $source=[regex]::Match($PowerShellCommand,"-Dwin11p.source.sha=([^']+)").Groups[1].Value
 $run=[regex]::Match($PowerShellCommand,"-Dwin11p.run.id=([^']+)").Groups[1].Value
 Add-MockEvent @{kind='child';lane=$lane;db_run=$Environment['ARONEXA_PILOT05_RUN_ID'];port=$Environment['ARONEXA_PILOT05_PORT']}
 $config=Get-Win11pMatrixConfig
 $inventory=@(0..342|ForEach-Object{[pscustomobject]@{id=('mock.Core#case'+$_);tags=@()}})+@($config.guarded_tests|ForEach-Object{[pscustomobject]@{id=$_.id;tags=@($_.tag)}})
 if($lane -eq 'discovery'){
  [void][IO.Directory]::CreateDirectory((Join-Path $WorkingDirectory 'target'))
  @{source_sha=$source;methods=$inventory}|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $WorkingDirectory 'target/win11p-test-method-inventory.json')
 }
 $assignment=@(Get-Win11pTestAssignment -Inventory $inventory|Where-Object{$_.lane -eq $lane})
 $report=Join-Path $WorkingDirectory ('target/win11p-reports/'+$run+'/'+$lane)
 [void][IO.Directory]::CreateDirectory($report)
 $cases=@($assignment|ForEach-Object{
   $parts=$_.id.Split('#')
   '<testcase classname="'+$parts[0]+'" name="'+$parts[1]+'" />'
 }) -join "`n"
 $xml='<testsuite tests="'+$assignment.Count+'" failures="0" errors="0" skipped="0"><properties><property name="java.home" value="'+$env:WIN11P_PORTABLE_JAVA_HOME+'"/><property name="win11p.run.id" value="'+$run+'"/><property name="win11p.source.sha" value="'+$source+'"/><property name="win11p.lane" value="'+$lane+'"/></properties>'+$cases+'</testsuite>'
 if($env:MOCK_SCENARIO -eq 'counter_mismatch' -and $lane -eq 'ordinary'){$xml=$xml.Replace('tests="343"','tests="344"')}
 if($env:MOCK_SCENARIO -eq 'suite_failure' -and $lane -eq 'ordinary'){$xml=$xml.Replace('failures="0"','failures="1"').Replace('name="case0" />','name="case0"><failure/></testcase>')}
 [IO.File]::WriteAllText((Join-Path $report 'TEST-mock.xml'),$xml)
 if($env:MOCK_SCENARIO -eq 'parser_issue' -and $lane -eq 'ordinary'){[IO.File]::WriteAllText((Join-Path $report 'TEST-broken.xml'),'<bad')}
 $content=switch($lane){
  'pilot05-phase-a' {'PILOT05_PHASE_A_PASS JVM_PID=101'}
  'pilot05-phase-b' {'PILOT05_PHASE_B_PASS JVM_PID=102'}
  'pilot05-rc03-phase-a' {'PILOT08_RC03_PHASE_A_PID=201'}
  'pilot05-rc03-phase-b' {'PILOT08_RC03_PHASE_B_PID=202'}
  default {''}
 }
 if($env:MOCK_SCENARIO -eq 'same_pid' -and $lane -eq 'pilot05-phase-b'){$content='PILOT05_PHASE_B_PASS JVM_PID=101'}
 [IO.File]::WriteAllText($LogPath,$content+[Environment]::NewLine)
 $exitCode=if(($env:MOCK_SCENARIO -eq 'phase_a_exit' -and $lane -eq 'pilot05-phase-a') -or ($env:MOCK_SCENARIO -eq 'ordinary_exit' -and $lane -eq 'ordinary')){7}else{0}
 return [pscustomobject]@{exit_code=$exitCode;process_id=900;timed_out=$false;cleanup_complete=$true}
}
function New-Win11pOwnedDatabase {
 param($SourceSha,$RequestId,$StateDirectory,$Purpose)
 $rid=[guid]::NewGuid().ToString('D')
 Add-MockEvent @{kind='new';purpose=$Purpose;run_id=$rid}
 [pscustomobject]@{run_id=$rid;purpose=$Purpose;port=55000;container_id=('a'*64);container_name=('mock-'+$Purpose);database_name='aronexa_pilot05_disposable_only';identity_file=(Join-Path $StateDirectory ($Purpose+'.json'));server_version_num=170006;server_version='17.6';loopback=$true;image_id=('sha256:'+('a'*64))}
}
function Stop-Win11pOwnedDatabase {
 param($IdentityFile)
 Add-MockEvent @{kind='stop';identity_file=$IdentityFile}
 if($env:MOCK_SCENARIO -eq 'cleanup_failure'){throw 'mock cleanup exception'}
 [pscustomobject]@{status='PASS';removed=$true}
}
function Recover-Win11pOwnedDatabases {param($StateDirectory,$RequestId) Add-MockEvent @{kind='recover';request=$RequestId};return @()}
Export-ModuleMember -Function Get-Win11pMatrixConfig,Get-Win11pTestAssignment,ConvertFrom-Win11pSurefireReports,Compare-Win11pTestMatrix,Invoke-Win11pChildProcess,New-Win11pOwnedDatabase,Stop-Win11pOwnedDatabase,Recover-Win11pOwnedDatabases

'@
$mockEnvironmentNames=@('MOCK_EVENTS','MOCK_SCENARIO','LOCALAPPDATA','WIN11P_QA_CENTRAL_PATH','WIN11P_QA_SHA','WIN11P_PORTABLE_JAVA_HOME','WIN11P_AUTHORIZATION_VERIFIED_SHA256','WIN11P_AUTHORIZATION_REQUEST_ID','PATH')
$mockEnvironmentBefore=@{}
foreach($name in $mockEnvironmentNames){$mockEnvironmentBefore[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$coordinatorMockCount=0
try{
 foreach($scenario in @('all_pass','ordinary','parser_issue','counter_mismatch','suite_failure','ordinary_exit','phase_a_exit','missing_authorization','same_pid','cleanup_failure','auth_unverified')){
  $fixture=Join-Path $coordinatorMockRoot $scenario
  $mockRepo=Join-Path $fixture 'product'
  $mockScripts=Join-Path $mockRepo 'scripts/pilot'
  [void][IO.Directory]::CreateDirectory($mockScripts)
  [void][IO.Directory]::CreateDirectory((Join-Path $mockRepo 'backend'))
  Copy-Item -LiteralPath $coordinatorMockSource -Destination (Join-Path $mockScripts 'Invoke-Win11p-MavenMatrix.ps1')
  Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'win11p-maven-matrix.json') -Destination (Join-Path $mockScripts 'win11p-maven-matrix.json')
  [IO.File]::WriteAllText((Join-Path $mockScripts 'Win11p-MavenMatrix.psm1'),$coordinatorMockModule+$coordinatorMockFunctions)
  & git -C $mockRepo init -q
  & git -C $mockRepo add .
  & git -C $mockRepo -c user.name='Synthetic coordinator unit harness' -c user.email='unit@example.invalid' commit -qm 'Isolated synthetic fixture'
  Assert-Condition ($LASTEXITCODE -eq 0) 'coordinator fixture commit failed'
  $mockSha=(& git -C $mockRepo rev-parse HEAD).Trim()
  $mockQa=Join-Path $fixture 'qa'
  $mockLocal=Join-Path $fixture 'local'
  $mockJava=Join-Path $fixture 'java'
  $mockBin=Join-Path $fixture 'bin'
  foreach($directory in @($mockQa,$mockLocal,(Join-Path $mockJava 'bin'),$mockBin)){[void][IO.Directory]::CreateDirectory($directory)}
  [IO.File]::WriteAllText((Join-Path $mockJava 'release'),'JAVA_VERSION="21.0.1"')
  [IO.File]::WriteAllText((Join-Path $mockJava 'bin/java.exe'),'mocked; never executed')
  if([Environment]::OSVersion.Platform -eq 'Win32NT'){
   [IO.File]::WriteAllText((Join-Path $mockBin 'mvn.cmd'),'@exit /b 90')
  }else{
   [IO.File]::WriteAllText((Join-Path $mockBin 'mvn'),"#!/bin/sh`nexit 90`n")
   & chmod +x (Join-Path $mockBin 'mvn')
  }
  $mockAuth=[ordered]@{source_sha=$mockSha;qa_sha=('b'*40);request_id='mock-request';plan_id=$config.plan_id;reviewer='unit-control-fixture';review_url='https://github.com/ARG-Digital-Solutions/aronexa-qa-central/issues/1';disposition='APPROVED_FOR_EXECUTION';lanes=@()}
  foreach($laneConfig in @($config.lanes|Select-Object -Skip 2)){
   $laneEnvironment=@{}
   if($laneConfig.PSObject.Properties['ack_env']){$laneEnvironment[$laneConfig.ack_env]=$laneConfig.ack_value}
   if($laneConfig.id -in @('pilot07-operator','pilot09-operator')){
    $number=if($laneConfig.id -eq 'pilot09-operator'){'09'}else{'07'}
    $lab=Join-Path $mockLocal ('ARG-Digital/AroNexa-Pilot'+$number+'-Disposable/pilot'+$number+'-'+('c'*32))
    [void][IO.Directory]::CreateDirectory($lab)
    [IO.File]::WriteAllText((Join-Path $lab 'LAB-TEST-ONLY.marker'),('FICTIONAL_DISPOSABLE_ONLY_SOURCE_SHA='+$mockSha))
    $laneEnvironment['ARONEXA_PILOT'+$number+'_RUN_DIR']=$lab
   }
   $mockAuth.lanes+=@([pscustomobject]@{id=$laneConfig.id;operator='SYNTHETIC_UNIT_CONTROL_ONLY';acknowledged_at='2026-11-15T00:00:00Z';authorization_url=$mockAuth.review_url;environment=$laneEnvironment})
  }
  if($scenario -eq 'missing_authorization'){$mockAuth.lanes=@($mockAuth.lanes|Where-Object id -ne 'pilot05-phase-a')}
  $mockAuthPath=Join-Path $fixture 'auth.json'
  $mockAuth|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $mockAuthPath -Encoding utf8
  $mockDigest=(Get-FileHash -LiteralPath $mockAuthPath -Algorithm SHA256).Hash.ToLowerInvariant()
  $mockEvents=Join-Path $fixture 'events.jsonl'
  $mockSettings=@{MOCK_EVENTS=$mockEvents;MOCK_SCENARIO=$scenario;LOCALAPPDATA=$mockLocal;WIN11P_QA_CENTRAL_PATH=$mockQa;WIN11P_QA_SHA=('b'*40);WIN11P_PORTABLE_JAVA_HOME=$mockJava;WIN11P_AUTHORIZATION_VERIFIED_SHA256=$mockDigest;WIN11P_AUTHORIZATION_REQUEST_ID='mock-request';PATH=($mockBin+[IO.Path]::PathSeparator+$mockEnvironmentBefore.PATH)}
  if($scenario -eq 'auth_unverified'){$mockSettings.WIN11P_AUTHORIZATION_VERIFIED_SHA256=$null}
  foreach($name in $mockSettings.Keys){[Environment]::SetEnvironmentVariable($name,$mockSettings[$name],'Process')}
  $mockMode=if($scenario -eq 'ordinary'){'Ordinary'}else{'All'}
  $mockEvidence=Join-Path $fixture 'evidence'
  $mockArguments=@('-NoProfile','-File',(Join-Path $mockScripts 'Invoke-Win11p-MavenMatrix.ps1'),'-ExpectedCommit',$mockSha,'-Mode',$mockMode,'-EvidenceDirectory',$mockEvidence,'-RequestId','mock-request')
  if($mockMode -eq 'All'){$mockArguments+=@('-AuthorizationPath',$mockAuthPath,'-AuthorizationSha256',$mockDigest)}
  $mockPowerShell=(Get-Process -Id $PID).Path
  $priorMockPreference=$ErrorActionPreference
  $ErrorActionPreference='Continue'
  $mockOutput=@(& $mockPowerShell @mockArguments 2>&1)
  $mockExit=$LASTEXITCODE
  $ErrorActionPreference=$priorMockPreference
  Assert-Condition ($mockExit -ne 0) ('coordinator incorrectly allowed full PASS: '+$scenario)
  if($scenario -eq 'auth_unverified'){
   Assert-Condition ((($mockOutput -join ' ') -replace '\s+',' ') -match 'protected.*control-plane verification') 'unverified control did not fail before execution'
   Assert-Condition (-not(Test-Path -LiteralPath $mockEvents)) 'unverified control started a mocked child/resource'
   $coordinatorMockCount++;continue
  }
  $mockResult=Get-Content -LiteralPath (Join-Path $mockEvidence 'matrix-results.json') -Raw|ConvertFrom-Json
  $events=@(Get-Content -LiteralPath $mockEvents|ForEach-Object{$_|ConvertFrom-Json})
  $children=@($events|Where-Object kind -eq child)
  $childNames=@($children.lane)
  $mockLane=@{}
  foreach($row in $mockResult.lane_results){$mockLane[$row.lane]=$row}
  Assert-Condition ($mockResult.schema_version -eq 2 -and @($mockResult.inventory).Count -eq 352 -and @($mockResult.method_results).Count -eq 352) ('complete raw arrays missing: '+$scenario)
  Assert-Condition ($mockResult.full_verification -eq 'NOT_PASS' -and $mockResult.encrypted_recovery -eq 'NOT_IMPLEMENTED_IN_EXISTING_TEST') 'encrypted recovery blocker was weakened'
  Assert-Condition (@($mockResult.lane_results).Count -eq 10 -and $mockResult.request_id -eq 'mock-request') 'ten-lane/request contract changed'
  switch($scenario){
   all_pass {
    Assert-Condition ($mockResult.matrix_overall -eq 'PASS' -and $mockExit -eq 2 -and $children.Count -eq 10) 'complete method matrix did not retain blocking full exit2'
    Assert-Condition (@($events|Where-Object kind -eq new).Count -eq 3 -and @($events|Where-Object kind -eq stop).Count -eq 3) 'three owned resources were not finally stopped'
    Assert-Condition ($children[2].db_run -eq $children[3].db_run -and $children[4].db_run -eq $children[5].db_run) 'A/B did not share one database'
    Assert-Condition (@($children[2].db_run,$children[4].db_run,$children[9].db_run|Select-Object -Unique).Count -eq 3) 'legacy/RC03/Pilot09 databases were not distinct'
   }
   ordinary {Assert-Condition ($children.Count -eq 2 -and $mockResult.guarded_methods_not_run -eq 8) 'Ordinary ran guarded methods'}
   {$_ -in @('parser_issue','counter_mismatch','suite_failure','ordinary_exit')} {
    Assert-Condition ($children.Count -eq 2 -and $mockLane.ordinary.status -eq 'NOT_PASS' -and $mockResult.matrix_overall -eq 'NOT_PASS') ('ordinary parser/suite/exit defect did not stop guarded lanes: '+$scenario)
   }
   {$_ -in @('phase_a_exit','missing_authorization')} {
    Assert-Condition ('pilot05-phase-b' -notin $childNames -and $mockLane['pilot05-phase-b'].status -eq 'NOT_RUN' -and 'pilot05-rc03-phase-b' -in $childNames -and $mockResult.matrix_overall -eq 'NOT_PASS') 'failed/missing A did not abort its B while retaining independent lanes'
   }
   same_pid {Assert-Condition ($mockLane['pilot05-phase-b'].status -eq 'NOT_PASS' -and $mockResult.matrix_overall -eq 'NOT_PASS') 'same Java PID passed restart gate'}
   cleanup_failure {Assert-Condition ($mockResult.cleanup_status -eq 'NOT_PASS' -and $mockResult.matrix_overall -eq 'NOT_PASS') 'cleanup failure allowed matrix PASS'}
  }
  $coordinatorMockCount++
 }
}finally{
 foreach($name in $mockEnvironmentNames){[Environment]::SetEnvironmentVariable($name,$mockEnvironmentBefore[$name],'Process')}
 Remove-Item -LiteralPath $coordinatorMockRoot -Recurse -Force
}
Write-Output ('Win11p mocked coordinator cases PASS: '+$coordinatorMockCount)

}
