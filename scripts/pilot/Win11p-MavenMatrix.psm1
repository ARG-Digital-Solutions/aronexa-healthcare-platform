Set-StrictMode -Version Latest

function Get-Win11pMatrixConfig {
    [CmdletBinding()]
    param([string]$Path = (Join-Path $PSScriptRoot 'win11p-maven-matrix.json'))
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'WIN11P matrix config is missing' }
    $config = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    if ([int]$config.schema_version -ne 1 -or -not $config.default_maven_is_unfiltered -or
        -not $config.source_plan_update_required_before_central_execution) {
        throw 'WIN11P matrix config contract is invalid'
    }
    $laneIds=@{}
    foreach($lane in $config.lanes){
        if($laneIds.ContainsKey([string]$lane.id)){throw ('Duplicate matrix lane: '+$lane.id)}
        $laneIds[[string]$lane.id]=$true
    }
    $guardedIds=@{}
    foreach($test in $config.guarded_tests){
        if($guardedIds.ContainsKey([string]$test.id)){throw ('Duplicate guarded test: '+$test.id)}
        if(-not $laneIds.ContainsKey([string]$test.lane)){throw ('Unknown lane for guarded test: '+$test.id)}
        $guardedIds[[string]$test.id]=$true
    }
    return $config
}

function Get-Win11pTestAssignment {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][object[]]$Inventory,
        [string]$ConfigPath = (Join-Path $PSScriptRoot 'win11p-maven-matrix.json')
    )
    $config=Get-Win11pMatrixConfig -Path $ConfigPath
    $expected=@{}
    foreach($entry in $config.guarded_tests){$expected[[string]$entry.id]=$entry}
    $seen=@{}
    $assignments=@()
    foreach($test in $Inventory){
        $id=[string]$test.id
        if([string]::IsNullOrWhiteSpace($id)){throw 'Discovered test identity is empty'}
        if($seen.ContainsKey($id)){throw ('Duplicate discovered test: '+$id)}
        $seen[$id]=$true
        $tags=@($test.tags|Where-Object{-not [string]::IsNullOrWhiteSpace([string]$_)})
        if($tags.Count -gt 1){throw ('Test has multiple lifecycle tags: '+$id)}
        if($expected.ContainsKey($id)){
            $entry=$expected[$id]
            if($tags.Count -ne 1 -or [string]$tags[0] -ne [string]$entry.tag){
                throw ('Guarded test tag differs from reviewed matrix: '+$id)
            }
            $assignments += [pscustomobject]@{id=$id;lane=[string]$entry.lane;tag=[string]$entry.tag}
        }elseif($tags.Count -eq 0){
            $assignments += [pscustomobject]@{id=$id;lane='ordinary';tag=$null}
        }else{
            throw ('Tagged test is absent from guarded method inventory: '+$id)
        }
    }
    $missing=@($expected.Keys|Where-Object{-not $seen.ContainsKey($_)})
    if($missing.Count -gt 0){throw ('Expected guarded tests were not discovered: '+($missing -join ', '))}
    return @($assignments|Sort-Object id)
}

function ConvertFrom-Win11pSurefireReports {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][string]$ReportDirectory,
        [Parameter(Mandatory=$true)][string]$Lane
    )
    if(-not(Test-Path -LiteralPath $ReportDirectory -PathType Container)){
        return [pscustomobject]@{results=@();tests=0;failures=0;errors=0;skipped=0;issues=@('report directory missing')}
    }
    $files=@(Get-ChildItem -LiteralPath $ReportDirectory -Filter 'TEST-*.xml' -File)
    if($files.Count -eq 0){
        return [pscustomobject]@{results=@();tests=0;failures=0;errors=0;skipped=0;issues=@('Surefire XML missing')}
    }
    $results=[System.Collections.Generic.List[object]]::new()
    $issues=[System.Collections.Generic.List[string]]::new()
    foreach($file in $files){
        try{$doc=[xml](Get-Content -LiteralPath $file.FullName -Raw)}catch{
            $issues.Add('malformed Surefire XML: '+$file.Name);continue
        }
        $cases=$doc.SelectNodes('//testcase')
        foreach($case in $cases){
            $className=[string]$case.GetAttribute('classname')
            $caseName=[string]$case.GetAttribute('name')
            $method=$caseName -replace '\[.*$','' -replace '\(.*$',''
            $id=if($className -and $method){$className+'#'+$method}else{''}
            $outcome='PASS'
            if($null -ne $case.SelectSingleNode('./error')){$outcome='ERROR'}
            elseif($null -ne $case.SelectSingleNode('./failure')){$outcome='FAIL'}
            elseif($null -ne $case.SelectSingleNode('./skipped')){$outcome='SKIPPED'}
            $results.Add([pscustomobject]@{id=$id;outcome=$outcome;lane=$Lane;report=$file.Name})
        }
    }
    $failures=@($results|Where-Object{$_.outcome -eq 'FAIL'}).Count
    $errors=@($results|Where-Object{$_.outcome -eq 'ERROR'}).Count
    $skipped=@($results|Where-Object{$_.outcome -eq 'SKIPPED'}).Count
    return [pscustomobject]@{results=@($results);tests=$results.Count;failures=$failures;errors=$errors;skipped=$skipped;issues=@($issues)}
}

function Compare-Win11pTestMatrix {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][object[]]$Inventory,
        [Parameter(Mandatory=$true)][object[]]$Results
    )
    $issues=[System.Collections.Generic.List[string]]::new()
    $expected=@{}
    foreach($test in $Inventory){
        $id=[string]$test.id
        if([string]::IsNullOrWhiteSpace($id)){$issues.Add('empty discovered test identity')}
        elseif($expected.ContainsKey($id)){$issues.Add('duplicate discovery: '+$id)}
        else{$expected[$id]=$test}
    }
    $assignments=@{}
    try{
        foreach($assignment in (Get-Win11pTestAssignment -Inventory @($Inventory))){
            $assignments[[string]$assignment.id]=$assignment
        }
    }catch{$issues.Add('invalid inventory assignment: '+$_.Exception.Message)}
    $actual=@{}
    foreach($result in $Results){
        $id=[string]$result.id
        if([string]::IsNullOrWhiteSpace($id)){$issues.Add('empty executed test identity');continue}
        if(-not $actual.ContainsKey($id)){$actual[$id]=[System.Collections.Generic.List[object]]::new()}
        $actual[$id].Add($result)
    }
    foreach($id in $expected.Keys){
        if(-not $actual.ContainsKey($id)){$issues.Add('missing execution: '+$id);continue}
        $cases=$actual[$id]
        if($cases.Count -ne 1){$issues.Add('duplicate execution: '+$id);continue}
        if(-not $assignments.ContainsKey($id)){continue}
        $result=$cases[0]
        if([string]$result.lane -ne [string]$assignments[$id].lane){$issues.Add('wrong execution lane: '+$id)}
        if([string]$result.outcome -ne 'PASS'){$issues.Add('non-pass outcome '+[string]$result.outcome+': '+$id)}
    }
    foreach($id in $actual.Keys){if(-not $expected.ContainsKey($id)){$issues.Add('unexpected execution: '+$id)}}
    $overall=if($issues.Count -eq 0 -and $expected.Count -gt 0){'PASS'}else{'NOT_PASS'}
    return [pscustomobject]@{overall=$overall;inventory_count=$expected.Count;result_count=$Results.Count;issues=@($issues)}
}

function ConvertTo-Win11pPowerShellLiteral {
    param([Parameter(Mandatory=$true)][string]$Value)
    return "'" + $Value.Replace("'","''") + "'"
}

function Invoke-Win11pChildProcess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][string]$PowerShellCommand,
        [Parameter(Mandatory=$true)][string]$WorkingDirectory,
        [Parameter(Mandatory=$true)][string]$LogPath,
        [hashtable]$Environment=@{}
    )
    $psi=New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName=(Get-Command powershell.exe -ErrorAction Stop).Source
    $psi.Arguments='-NoProfile -NonInteractive -EncodedCommand '+[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($PowerShellCommand))
    $psi.WorkingDirectory=(Resolve-Path -LiteralPath $WorkingDirectory).Path
    $psi.UseShellExecute=$false
    $psi.CreateNoWindow=$true
    $psi.RedirectStandardOutput=$true
    $psi.RedirectStandardError=$true
    $inherited=[Environment]::GetEnvironmentVariables('Process')
    # Keep the current Windows PowerShell runtime environment intact; strip only
    # prior lane acknowledgements and application datasource/profile overrides.
    foreach($name in @($psi.EnvironmentVariables.Keys)){
        if($name -match '^(ARONEXA_PILOT|SPRING_DATASOURCE_|SPRING_PROFILES_ACTIVE)'){$psi.EnvironmentVariables.Remove($name)}
    }
    foreach($name in $Environment.Keys){$psi.EnvironmentVariables[[string]$name]=[string]$Environment[$name]}
    $process=New-Object System.Diagnostics.Process
    $process.StartInfo=$psi
    if(-not $process.Start()){throw 'WIN11P child process failed to start'}
    $processId=$process.Id
    $stdoutTask=$process.StandardOutput.ReadToEndAsync()
    $stderrTask=$process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $exitCode=$process.ExitCode
    $stdout=$stdoutTask.Result
    $stderr=$stderrTask.Result
    $process.Dispose()
    $parent=[IO.Path]::GetDirectoryName($LogPath)
    if(-not(Test-Path -LiteralPath $parent)){[void][IO.Directory]::CreateDirectory($parent)}
    [IO.File]::WriteAllText($LogPath,$stdout+[Environment]::NewLine+$stderr,(New-Object Text.UTF8Encoding($false)))
    return [pscustomobject]@{exit_code=$exitCode;process_id=$processId;log_path=$LogPath}
}

Export-ModuleMember -Function Get-Win11pMatrixConfig, Get-Win11pTestAssignment, ConvertFrom-Win11pSurefireReports, Compare-Win11pTestMatrix, Invoke-Win11pChildProcess
