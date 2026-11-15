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
    $results=[System.Collections.Generic.List[object]]::new()
    $issues=[System.Collections.Generic.List[string]]::new()
    $seen=[System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $javaHomes=[System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $files=@()
    if(-not(Test-Path -LiteralPath $ReportDirectory -PathType Container)){$issues.Add('report directory missing')}
    else{
        $files=@(Get-ChildItem -LiteralPath $ReportDirectory -Filter 'TEST-*.xml' -File|Sort-Object Name)
        if($files.Count -eq 0){$issues.Add('Surefire XML missing')}
    }
    foreach($file in $files){
        if($file.Length -gt 5242880){$issues.Add('oversized Surefire XML: '+$file.Name);continue}
        $reader=$null
        try{
            $settings=New-Object Xml.XmlReaderSettings
            $settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit
            $settings.XmlResolver=$null
            $settings.MaxCharactersInDocument=5242880
            $reader=[Xml.XmlReader]::Create($file.FullName,$settings)
            $doc=New-Object Xml.XmlDocument
            $doc.XmlResolver=$null
            $doc.Load($reader)
        }catch{$issues.Add('malformed or prohibited Surefire XML: '+$file.Name);continue}
        finally{if($null -ne $reader){$reader.Dispose()}}
        $root=$doc.DocumentElement
        if($null -eq $root -or $root.NamespaceURI -ne '' -or $root.Name -notin @('testsuite','testsuites')){
            $issues.Add('non-suite Surefire XML: '+$file.Name);continue
        }
        $suites=if($root.Name -eq 'testsuite'){@($root)}else{@($root.SelectNodes('./testsuite'))}
        if($suites.Count -eq 0){$issues.Add('empty Surefire suites: '+$file.Name)}
        if($root.Name -eq 'testsuites' -and @($root.SelectNodes('./*')).Count -ne $suites.Count){
            $issues.Add('unexpected Surefire suite nesting: '+$file.Name)
        }
        $fileStart=$results.Count
        foreach($suite in $suites){
            $homes=@($suite.SelectNodes('./properties/property[@name="java.home"]'))
            if($homes.Count -gt 1){$issues.Add('duplicate Surefire java.home property: '+$file.Name)}
            foreach($home in $homes){
                $homeValue=[string]$home.GetAttribute('value')
                if([string]::IsNullOrWhiteSpace($homeValue) -or $homeValue.Length -gt 1024 -or $homeValue -match '[\x00-\x1f]'){$issues.Add('invalid Surefire java.home property: '+$file.Name)}
                else{[void]$javaHomes.Add($homeValue)}
            }
            $cases=@($suite.SelectNodes('./testcase'))
            if(@($suite.SelectNodes('.//testcase')).Count -ne $cases.Count){$issues.Add('nested Surefire testcase: '+$file.Name)}
            $counts=@{tests=$cases.Count;failures=0;errors=0;skipped=0}
            foreach($case in $cases){
                $className=[string]$case.GetAttribute('classname')
                $caseName=[string]$case.GetAttribute('name')
                $method=$caseName -replace '\[.*$','' -replace '\(.*$',''
                $id=if($className -match '^[A-Za-z_$][A-Za-z0-9_$.]*$' -and $method -match '^[A-Za-z_$][A-Za-z0-9_$]*$'){$className+'#'+$method}else{''}
                if(-not $id){$issues.Add('empty or invalid Surefire identity: '+$file.Name)}
                elseif(-not $seen.Add($id)){$issues.Add('duplicate Surefire identity: '+$id)}
                $outcomes=@($case.SelectNodes('./error|./failure|./skipped'))
                if($outcomes.Count -gt 1){$issues.Add('contradictory Surefire outcome: '+$file.Name)}
                $outcome='PASS'
                if($null -ne $case.SelectSingleNode('./error')){$outcome='ERROR';$counts.errors++}
                elseif($null -ne $case.SelectSingleNode('./failure')){$outcome='FAIL';$counts.failures++}
                elseif($null -ne $case.SelectSingleNode('./skipped')){$outcome='SKIPPED';$counts.skipped++}
                $results.Add([pscustomobject]@{id=$id;outcome=$outcome;lane=$Lane;report=$file.Name})
            }
            foreach($counter in @('tests','failures','errors','skipped')){
                $value=0L;$raw=[string]$suite.GetAttribute($counter)
                if($raw -notmatch '^[0-9]+$' -or -not [long]::TryParse($raw,[ref]$value) -or $value -ne $counts[$counter]){
                    $issues.Add('Surefire counter/case mismatch '+$counter+': '+$file.Name)
                }
            }
        }
        if($root.Name -eq 'testsuites'){
            $fileCases=@($results.ToArray()|Select-Object -Skip $fileStart)
            foreach($counter in @('tests','failures','errors','skipped')){
                if(-not $root.HasAttribute($counter)){continue}
                $actual=switch($counter){
                    'tests'{$fileCases.Count}
                    'failures'{@($fileCases|Where-Object outcome -eq FAIL).Count}
                    'errors'{@($fileCases|Where-Object outcome -eq ERROR).Count}
                    'skipped'{@($fileCases|Where-Object outcome -eq SKIPPED).Count}
                }
                $value=0L;$raw=[string]$root.GetAttribute($counter)
                if($raw -notmatch '^[0-9]+$' -or -not [long]::TryParse($raw,[ref]$value) -or $value -ne $actual){$issues.Add('Surefire aggregate counter/case mismatch '+$counter+': '+$file.Name)}
            }
        }
    }
    $all=$results.ToArray()
    return [pscustomobject]@{java_homes=@($javaHomes|Sort-Object);results=$all;tests=$results.Count;failures=@($all|Where-Object outcome -eq FAIL).Count;errors=@($all|Where-Object outcome -eq ERROR).Count;skipped=@($all|Where-Object outcome -eq SKIPPED).Count;issues=$issues.ToArray()}
}

function Compare-Win11pTestMatrix {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][object[]]$Inventory,
        [Parameter(Mandatory=$true)][AllowEmptyCollection()][object[]]$Results,
        [AllowEmptyCollection()][string[]]$ReportIssues=@()
    )
    $issues=[System.Collections.Generic.List[string]]::new()
    foreach($issue in $ReportIssues){$issues.Add('report issue: '+$issue)}
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
    return [pscustomobject]@{overall=$overall;inventory_count=$expected.Count;result_count=$Results.Count;issues=$issues.ToArray()}
}

function ConvertTo-Win11pPowerShellLiteral {
    param([Parameter(Mandatory=$true)][string]$Value)
    return "'" + $Value.Replace("'","''") + "'"
}

function Initialize-Win11pProcessOwnership {
    if($null -ne ('Win11pOwnedProcessNative' -as [type])){return}
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class Win11pOwnedProcessNative {
 [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] public static extern IntPtr CreateJobObject(IntPtr attr, string name);
 [DllImport("kernel32.dll", SetLastError=true)] public static extern bool SetInformationJobObject(IntPtr job, int kind, IntPtr info, uint length);
 [DllImport("kernel32.dll", SetLastError=true)] public static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
 [DllImport("kernel32.dll", SetLastError=true)] public static extern bool CloseHandle(IntPtr handle);
 [DllImport("libc", SetLastError=true)] public static extern int getpgid(int pid);
 [DllImport("libc", SetLastError=true)] public static extern int kill(int pid, int signal);
 [StructLayout(LayoutKind.Sequential)] public struct BasicLimit { public long ProcessTime; public long JobTime; public uint Flags; public UIntPtr MinWorking; public UIntPtr MaxWorking; public uint ActiveProcesses; public UIntPtr Affinity; public uint Priority; public uint Scheduling; }
 [StructLayout(LayoutKind.Sequential)] public struct IoCounters { public ulong ReadOps,WriteOps,OtherOps,ReadBytes,WriteBytes,OtherBytes; }
 [StructLayout(LayoutKind.Sequential)] public struct ExtendedLimit { public BasicLimit Basic; public IoCounters Io; public UIntPtr ProcessMemory,JobMemory,PeakProcess,PeakJob; }
 public static IntPtr CreateKillOnCloseJob() {
   IntPtr job=CreateJobObject(IntPtr.Zero,null);
   if(job==IntPtr.Zero) throw new System.ComponentModel.Win32Exception();
   ExtendedLimit limits=new ExtendedLimit(); limits.Basic.Flags=0x2000;
   int size=Marshal.SizeOf(typeof(ExtendedLimit)); IntPtr data=Marshal.AllocHGlobal(size);
   try { Marshal.StructureToPtr(limits,data,false); if(!SetInformationJobObject(job,9,data,(uint)size)) throw new System.ComponentModel.Win32Exception(); }
   catch {CloseHandle(job);throw;} finally {Marshal.FreeHGlobal(data);} return job;
 }
}
'@
}

function Invoke-Win11pChildProcess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][string]$PowerShellCommand,
        [Parameter(Mandatory=$true)][string]$WorkingDirectory,
        [Parameter(Mandatory=$true)][string]$LogPath,
        [hashtable]$Environment=@{},
        [ValidateRange(1,7200)][int]$TimeoutSeconds=1800
    )
    Initialize-Win11pProcessOwnership
    $windows=[Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
    $runtime=(Get-Process -Id $PID).Path
    $control=Join-Path ([IO.Path]::GetTempPath()) ('win11p-child-gate-'+[guid]::NewGuid().ToString('N'))
    [void][IO.Directory]::CreateDirectory($control)
    $gate=Join-Path $control 'ready'
    # No supplied command runs until its new Windows Job or Unix session is
    # established. Windows closes the job even after its root exits; Unix kills
    # only the dedicated group created by setsid. Detached host-loss cleanup
    # remains the ephemeral runner's destruction responsibility.
    $bootstrap='$deadline=[DateTime]::UtcNow.AddSeconds(15);while(-not(Test-Path -LiteralPath '+(ConvertTo-Win11pPowerShellLiteral $gate)+')){if([DateTime]::UtcNow -gt $deadline){exit 125};Start-Sleep -Milliseconds 25};& {'+$PowerShellCommand+'}'
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($bootstrap))
    $psi=New-Object Diagnostics.ProcessStartInfo
    if($windows){$psi.FileName=$runtime;$psi.Arguments='-NoProfile -NonInteractive -EncodedCommand '+$encoded}
    else{
        $psi.FileName=(Get-Command setsid -CommandType Application -ErrorAction Stop|Select-Object -First 1).Source
        $psi.Arguments='"'+$runtime.Replace('"','\"')+'" -NoProfile -NonInteractive -EncodedCommand '+$encoded
    }
    $psi.WorkingDirectory=(Resolve-Path -LiteralPath $WorkingDirectory).Path
    $psi.UseShellExecute=$false;$psi.CreateNoWindow=$true
    $psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
    foreach($name in @($psi.EnvironmentVariables.Keys)){
        if($name -match '^(ARONEXA_PILOT|SPRING_DATASOURCE_|SPRING_PROFILES_ACTIVE|WIN11P_AUTHORIZATION_|WIN11P_EXECUTION_AUTHORIZATION_)' -or
            $name -in @('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','MAVEN_OPTS','MAVEN_ARGS','NODE_OPTIONS','GH_TOKEN','GITHUB_TOKEN','ARONEXA_SOURCE_READ_TOKEN','ARONEXA_DEV_STATUS_TOKEN')){$psi.EnvironmentVariables.Remove($name)}
    }
    foreach($name in $Environment.Keys){$psi.EnvironmentVariables[[string]$name]=[string]$Environment[$name]}
    $process=New-Object Diagnostics.Process;$process.StartInfo=$psi
    $started=$false;$timedOut=$false;$stdout='';$stderr='';$exitCode=124;$processId=0
    $job=[IntPtr]::Zero;$jobAssigned=$false;$ownedGroup=$false;$cleanupComplete=$false
    try{
        if($windows){$job=[Win11pOwnedProcessNative]::CreateKillOnCloseJob()}
        if(-not $process.Start()){throw 'WIN11P child process failed to start'}
        $started=$true;$processId=$process.Id
        $stdoutTask=$process.StandardOutput.ReadToEndAsync();$stderrTask=$process.StandardError.ReadToEndAsync()
        if($windows){
            if(-not [Win11pOwnedProcessNative]::AssignProcessToJobObject($job,$process.Handle)){throw 'WIN11P child Windows Job ownership could not be established'}
            $jobAssigned=$true
        }else{
            for($i=0;$i -lt 40;$i++){
                if([Win11pOwnedProcessNative]::getpgid($processId) -eq $processId){$ownedGroup=$true;break}
                Start-Sleep -Milliseconds 25
            }
            if(-not $ownedGroup){throw 'WIN11P child Unix session ownership could not be established'}
        }
        [IO.File]::WriteAllText($gate,'ready')
        if(-not $process.WaitForExit($TimeoutSeconds*1000)){$timedOut=$true}
        else{$exitCode=$process.ExitCode}
    }finally{
        if($windows -and $job -ne [IntPtr]::Zero){
            $cleanupComplete=[Win11pOwnedProcessNative]::CloseHandle($job)
        }elseif($ownedGroup){
            $killed=[Win11pOwnedProcessNative]::kill(-$processId,9)
            $cleanupComplete=($killed -eq 0 -or [Runtime.InteropServices.Marshal]::GetLastWin32Error() -eq 3)
        }
        if($started){
            # Ownership failure happens before opening the command gate; the
            # bootstrap has spawned no descendants and only its exact PID dies.
            if(-not $process.HasExited -and -not($ownedGroup -or $jobAssigned -and $cleanupComplete)){$process.Kill()}
            if(-not $process.WaitForExit(5000)){$cleanupComplete=$false}
            if($stdoutTask.Wait(2000)){$stdout=$stdoutTask.Result}else{$cleanupComplete=$false}
            if($stderrTask.Wait(2000)){$stderr=$stderrTask.Result}else{$cleanupComplete=$false}
        }
        $process.Dispose()
        Remove-Item -LiteralPath $control -Recurse -Force
        $parent=[IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($LogPath))
        if(-not(Test-Path -LiteralPath $parent)){[void][IO.Directory]::CreateDirectory($parent)}
        [IO.File]::WriteAllText($LogPath,$stdout+[Environment]::NewLine+$stderr,(New-Object Text.UTF8Encoding($false)))
    }
    if(-not $cleanupComplete){$exitCode=125}
    return [pscustomobject]@{exit_code=$exitCode;process_id=$processId;log_path=$LogPath;timed_out=$timedOut;cleanup_complete=$cleanupComplete}
}

# Only these disposable resource identities may be provisioned or removed. A
# request journal is written before Docker receives a create command so recovery
# never searches or stops arbitrary containers after an interrupted response.
function Invoke-Win11pDocker {
    param([Parameter(Mandatory=$true)][string[]]$DockerArguments,[switch]$AllowMissingContainer)
    $docker=Get-Command docker -CommandType Application -ErrorAction SilentlyContinue|Select-Object -First 1
    if($null -eq $docker){$docker=Get-Command docker.exe -CommandType Application -ErrorAction Stop|Select-Object -First 1}
    $psi=New-Object Diagnostics.ProcessStartInfo
    $psi.FileName=$docker.Source;$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true
    $psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
    if($null -ne $psi.GetType().GetProperty('ArgumentList')){
        foreach($argument in $DockerArguments){$psi.ArgumentList.Add($argument)}
    }else{
        $escaped=foreach($argument in $DockerArguments){
            '"'+([regex]::Replace([regex]::Replace($argument,'(\\*)"','$1$1\"'),'(\\+)$','$1$1'))+'"'
        }
        $psi.Arguments=$escaped -join ' '
    }
    $process=New-Object Diagnostics.Process;$process.StartInfo=$psi
    try{
        if(-not $process.Start()){throw 'WIN11P Docker client could not start'}
        $stdoutTask=$process.StandardOutput.ReadToEndAsync();$stderrTask=$process.StandardError.ReadToEndAsync()
        if(-not $process.WaitForExit(180000)){$process.Kill();throw 'WIN11P Docker client timed out; recover exact request journal'}
        if(-not $stdoutTask.Wait(5000) -or -not $stderrTask.Wait(5000)){throw 'WIN11P Docker client output drain failed'}
        if($process.ExitCode -ne 0){
            $target=$DockerArguments[$DockerArguments.Count-1]
            if($AllowMissingContainer -and $process.ExitCode -eq 1 -and $DockerArguments[0] -eq 'inspect' -and
                $target -match '^[0-9a-f]{64}$' -and $stderrTask.Result.Trim() -cin @(('Error: No such container: '+$target),('Error response from daemon: No such container: '+$target))){
                return [pscustomobject]@{exit_code=1;output=@();missing=$true}
            }
            throw ('WIN11P Docker command failed: '+$DockerArguments[0])
        }
        $output=if($stdoutTask.Result.Length){@($stdoutTask.Result.TrimEnd() -split '\r?\n')}else{@()}
        return [pscustomobject]@{exit_code=$process.ExitCode;output=$output}
    }finally{$process.Dispose()}
}

function Save-Win11pResourceIdentity {
    param([string]$Path,[object]$Identity)
    $temp=$Path+'.'+[guid]::NewGuid().ToString('N')+'.tmp'
    try{
        [IO.File]::WriteAllText($temp,($Identity|ConvertTo-Json -Depth 8),(New-Object Text.UTF8Encoding($false)))
        # A same-directory replace avoids a partially written recovery journal.
        if(Test-Path -LiteralPath $Path){
            $backup=$temp+'.bak';[IO.File]::Replace($temp,$Path,$backup)
            if(Test-Path -LiteralPath $backup){Remove-Item -LiteralPath $backup -Force}
        }
        else{[IO.File]::Move($temp,$Path)}
    }finally{if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp -Force}}
}

function Read-Win11pResourceIdentity {
    param([Parameter(Mandatory=$true)][string]$IdentityFile)
    $file=Get-Item -LiteralPath $IdentityFile -ErrorAction Stop
    if($file.PSIsContainer -or $file.Length -gt 16384 -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)){
        throw 'WIN11P recovery journal file is invalid'
    }
    $identity=Get-Content -LiteralPath $file.FullName -Raw|ConvertFrom-Json
    if($identity.schema_version -ne 1 -or $identity.request_id -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$' -or
        $identity.source_sha -notmatch '^[0-9a-f]{40}$' -or $identity.purpose -notin @('legacy','rc03','operator') -or
        $identity.run_id -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' -or
        $identity.database_name -cne 'aronexa_pilot05_disposable_only' -or
        $identity.database_user -cne 'aronexa_pilot05_temporary' -or
        $identity.container_name -cne ('aronexa-win11p-'+$identity.purpose+'-'+$identity.run_id.Replace('-','')) -or
        $identity.state -notin @('PREPARED','CREATED','READY','STOPPING','STOPPED') -or
        ([string]$identity.image_id -ne '' -and $identity.image_id -notmatch '^sha256:[0-9a-f]{64}$') -or
        ($identity.state -eq 'READY' -and $identity.image_id -notmatch '^sha256:[0-9a-f]{64}$') -or
        ([string]$identity.container_id -ne '' -and $identity.container_id -notmatch '^[0-9a-f]{64}$') -or
        ($identity.state -ne 'PREPARED' -and $identity.container_id -notmatch '^[0-9a-f]{64}$') -or
        $identity.port -isnot [ValueType] -or [int]$identity.port -lt 0 -or [int]$identity.port -gt 65535 -or [int]$identity.port -eq 5433){
        throw 'WIN11P recovery journal identity is invalid'
    }
    $expected=$identity.request_id+'--'+$identity.purpose+'--'+$identity.run_id+'.json'
    if($file.Name -cne $expected){throw 'WIN11P recovery journal filename differs from identity'}
    return $identity
}

function Get-Win11pVerifiedOwnedContainer {
    param([Parameter(Mandatory=$true)][object]$Identity,[switch]$AllowAbsent)
    $target=if($Identity.container_id){$Identity.container_id}else{$Identity.container_name}
    $reply=Invoke-Win11pDocker -DockerArguments @('inspect','--type','container',$target) -AllowMissingContainer:$AllowAbsent
    if($AllowAbsent -and $null -ne $reply.PSObject.Properties['missing'] -and $reply.missing){return $null}
    $all=@(ConvertFrom-Json -InputObject ($reply.output -join [Environment]::NewLine))
    if($all.Count -ne 1){throw 'WIN11P container identity is ambiguous'}
    $item=$all[0]
    if($item.Id -notmatch '^[0-9a-f]{64}$' -or
        ($Identity.container_id -and $item.Id -cne $Identity.container_id) -or
        $item.Image -notmatch '^sha256:[0-9a-f]{64}$' -or
        ($Identity.image_id -and $item.Image -cne $Identity.image_id) -or
        $item.Name -cne ('/'+$Identity.container_name) -or $item.Config.Image -cne 'postgres:17-alpine'){
        throw 'WIN11P container full ID/name/image mismatch'
    }
    $labels=@{
        'com.argdigital.aronexa.pilot05.run'=$Identity.run_id
        'com.argdigital.aronexa.win11p.request'=$Identity.request_id
        'com.argdigital.aronexa.win11p.source'=$Identity.source_sha
        'com.argdigital.aronexa.win11p.purpose'=$Identity.purpose
    }
    foreach($name in $labels.Keys){
        $label=$item.Config.Labels.PSObject.Properties[$name]
        if($null -eq $label -or [string]$label.Value -cne [string]$labels[$name]){throw 'WIN11P container ownership label mismatch'}
    }
    foreach($entry in @(('POSTGRES_DB='+$Identity.database_name),('POSTGRES_USER='+$Identity.database_user))){
        if(@($item.Config.Env|Where-Object {$_ -ceq $entry}).Count -ne 1){throw 'WIN11P container disposable database identity mismatch'}
    }
    $mountSpec=$item.HostConfig.PSObject.Properties['Mounts']
    $externalMounts=if($null -ne $mountSpec){@($mountSpec.Value|Where-Object {$_}).Count}else{0}
    if(@($item.HostConfig.Binds|Where-Object {$_}).Count -gt 0 -or $externalMounts -gt 0){
        throw 'WIN11P container has externally supplied mounts'
    }
    foreach($mount in @($item.Mounts)){
        if($null -eq $mount){continue}
        if($mount.Type -cne 'volume' -or $mount.Name -notmatch '^[0-9a-f]{64}$' -or $mount.Destination -cne '/var/lib/postgresql/data'){
            throw 'WIN11P container mount ownership is not established'
        }
    }
    $configured=$item.HostConfig.PortBindings.PSObject.Properties['5432/tcp']
    if($null -eq $configured -or @($configured.Value).Count -ne 1 -or $configured.Value[0].HostIp -cne '127.0.0.1'){
        throw 'WIN11P container configured binding is not loopback'
    }
    $binding=$item.NetworkSettings.Ports.PSObject.Properties['5432/tcp']
    if($null -ne $binding -and $null -ne $binding.Value){
        if(@($binding.Value).Count -ne 1 -or $binding.Value[0].HostIp -cne '127.0.0.1' -or
            [string]$binding.Value[0].HostPort -notmatch '^[0-9]{4,5}$' -or
            [int]$binding.Value[0].HostPort -gt 65535 -or [int]$binding.Value[0].HostPort -eq 5433 -or
            ($Identity.port -gt 0 -and [int]$binding.Value[0].HostPort -ne $Identity.port)){
            throw 'WIN11P container observed loopback port mismatch'
        }
    }elseif($Identity.port -gt 0){throw 'WIN11P container observed binding is missing'}
    return $item
}

function New-Win11pOwnedDatabase {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$SourceSha,
        [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$')][string]$RequestId,
        [Parameter(Mandatory=$true)][string]$StateDirectory,
        [Parameter(Mandatory=$true)][ValidateSet('legacy','rc03','operator')][string]$Purpose
    )
    [void][IO.Directory]::CreateDirectory($StateDirectory)
    $directory=Get-Item -LiteralPath $StateDirectory
    if($directory.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'WIN11P resource state directory cannot be a link'}
    $runId=[guid]::NewGuid().ToString('D')
    $identity=[pscustomobject]@{
        schema_version=1;request_id=$RequestId;source_sha=$SourceSha.ToLowerInvariant();purpose=$Purpose
        run_id=$runId;image_id='';container_name=('aronexa-win11p-'+$Purpose+'-'+$runId.Replace('-',''));container_id=''
        database_name='aronexa_pilot05_disposable_only';database_user='aronexa_pilot05_temporary';port=0
        server_version='';server_version_num=0;state='PREPARED'
    }
    $path=Join-Path $directory.FullName ($RequestId+'--'+$Purpose+'--'+$runId+'.json')
    Save-Win11pResourceIdentity -Path $path -Identity $identity
    try{
        $args=@('create','--name',$identity.container_name,
            '--label',('com.argdigital.aronexa.pilot05.run='+$runId),
            '--label',('com.argdigital.aronexa.win11p.request='+$RequestId),
            '--label',('com.argdigital.aronexa.win11p.source='+$identity.source_sha),
            '--label',('com.argdigital.aronexa.win11p.purpose='+$Purpose),
            '--publish','127.0.0.1::5432','--env',('POSTGRES_DB='+$identity.database_name),
            '--env',('POSTGRES_USER='+$identity.database_user),'--env','POSTGRES_PASSWORD=pilot05-disposable-password-only','postgres:17-alpine')
        $created=Invoke-Win11pDocker -DockerArguments $args
        $identity.container_id=($created.output -join '').Trim()
        if($identity.container_id -notmatch '^[0-9a-f]{64}$'){throw 'WIN11P created container full ID is invalid'}
        $identity.state='CREATED';Save-Win11pResourceIdentity -Path $path -Identity $identity
        $createdInspection=Get-Win11pVerifiedOwnedContainer -Identity $identity
        $identity.image_id=$createdInspection.Image;Save-Win11pResourceIdentity -Path $path -Identity $identity
        [void](Invoke-Win11pDocker -DockerArguments @('start',$identity.container_id))
        $inspected=Get-Win11pVerifiedOwnedContainer -Identity $identity
        $binding=$inspected.NetworkSettings.Ports.PSObject.Properties['5432/tcp'].Value
        $identity.port=[int]$binding[0].HostPort
        if($identity.port -lt 1024){throw 'WIN11P random loopback port was not established'}
        Save-Win11pResourceIdentity -Path $path -Identity $identity
        $ready=$false
        for($i=0;$i -lt 40;$i++){
            try{[void](Invoke-Win11pDocker -DockerArguments @('exec',$identity.container_id,'pg_isready','-U',$identity.database_user,'-d',$identity.database_name));$ready=$true;break}
            catch{if($i -lt 39){Start-Sleep -Milliseconds 500}}
        }
        if(-not $ready){throw 'WIN11P disposable PostgreSQL did not become ready'}
        $psql=@('exec',$identity.container_id,'psql','-X','-v','ON_ERROR_STOP=1','-U',$identity.database_user,'-d',$identity.database_name)
        $major=Invoke-Win11pDocker -DockerArguments ($psql+@('-At','-c','SHOW server_version_num'))
        if(($major.output -join '').Trim() -notmatch '^17[0-9]{4}$'){throw 'WIN11P observed PostgreSQL server major version is not 17'}
        $identity.server_version_num=[int](($major.output -join '').Trim())
        $observed=Invoke-Win11pDocker -DockerArguments ($psql+@('-At','-c',"SELECT current_database(), current_setting('server_version')"))
        $parts=(($observed.output -join '').Trim()).Split('|')
        if($parts.Count -ne 2 -or $parts[0] -cne $identity.database_name -or $parts[1] -notmatch '^17([.]|$)'){throw 'WIN11P observed database/server identity mismatch'}
        $identity.server_version=$parts[1]
        $repo=Join-Path (Join-Path $PSScriptRoot '..') '..'
        $bootstrap=Join-Path $repo 'backend/src/test/resources/db/test/role-bootstrap.sql'
        [void](Invoke-Win11pDocker -DockerArguments @('cp',$bootstrap,($identity.container_id+':/tmp/role-bootstrap.sql')))
        [void](Invoke-Win11pDocker -DockerArguments ($psql+@('-f','/tmp/role-bootstrap.sql')))
        $marker=@"
CREATE SCHEMA qa_guard;
CREATE TABLE qa_guard.pilot05_run_marker (
 run_id uuid PRIMARY KEY, database_name text NOT NULL, phase text NOT NULL,
 tenant_id uuid, branch_id uuid, patient_id uuid, appointment_id uuid,
 doctor_id uuid, process_a bigint, process_b bigint
);
INSERT INTO qa_guard.pilot05_run_marker(run_id,database_name,phase)
VALUES ('$runId','aronexa_pilot05_disposable_only','READY');
"@
        [void](Invoke-Win11pDocker -DockerArguments ($psql+@('-c',$marker)))
        $identity.state='READY';Save-Win11pResourceIdentity -Path $path -Identity $identity
        return [pscustomobject]@{run_id=$runId;port=$identity.port;container_id=$identity.container_id;container_name=$identity.container_name;database_name=$identity.database_name;identity_file=$path;server_version=$identity.server_version;server_version_num=$identity.server_version_num;image_id=$identity.image_id;loopback=$true;purpose=$Purpose}
    }catch{
        $failure=$_
        # This also handles create succeeding while its response is interrupted:
        # PREPARED recovery targets only the journalled exact name and labels.
        try{[void](Stop-Win11pOwnedDatabase -IdentityFile $path)}catch{
            throw ('WIN11P provisioning failed ('+$failure.Exception.Message+'); identity-bound cleanup refused or failed ('+$_.Exception.Message+'); recover journal '+$path)
        }
        throw $failure
    }
}

function Stop-Win11pOwnedDatabase {
    [CmdletBinding()]
    param([Parameter(Mandatory=$true)][string]$IdentityFile)
    $identity=Read-Win11pResourceIdentity -IdentityFile $IdentityFile
    if($identity.state -eq 'STOPPED'){
        return [pscustomobject]@{status='PASS';removed=$false;run_id=$identity.run_id;purpose=$identity.purpose;identity_file=$IdentityFile}
    }
    $item=Get-Win11pVerifiedOwnedContainer -Identity $identity -AllowAbsent:($identity.state -eq 'STOPPING')
    if($null -eq $item){
        $identity.state='STOPPED';Save-Win11pResourceIdentity -Path $IdentityFile -Identity $identity
        return [pscustomobject]@{status='PASS';removed=$false;run_id=$identity.run_id;purpose=$identity.purpose;identity_file=$IdentityFile}
    }
    if(-not $identity.container_id){
        $identity.container_id=$item.Id;$identity.image_id=$item.Image;$identity.state='CREATED'
        Save-Win11pResourceIdentity -Path $IdentityFile -Identity $identity
    }
    # Persist removal intent before Docker receives the destructive command. An
    # interrupted response can reconcile only an explicit exact-ID absence.
    $identity.state='STOPPING';Save-Win11pResourceIdentity -Path $IdentityFile -Identity $identity
    # Docker resolves a complete immutable ID. No name/pattern based removal.
    [void](Invoke-Win11pDocker -DockerArguments @('rm','--force','--volumes',$identity.container_id))
    $identity.state='STOPPED';Save-Win11pResourceIdentity -Path $IdentityFile -Identity $identity
    return [pscustomobject]@{status='PASS';removed=$true;run_id=$identity.run_id;purpose=$identity.purpose;identity_file=$IdentityFile}
}

function Recover-Win11pOwnedDatabases {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][string]$StateDirectory,
        [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$')][string]$RequestId
    )
    if(-not(Test-Path -LiteralPath $StateDirectory -PathType Container)){return @()}
    $directory=Get-Item -LiteralPath $StateDirectory
    if($directory.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'WIN11P resource recovery directory cannot be a link'}
    $results=[System.Collections.Generic.List[object]]::new()
    $failures=0
    foreach($file in @(Get-ChildItem -LiteralPath $directory.FullName -File -Filter ($RequestId+'--*.json')|Sort-Object Name)){
        try{
            $identity=Read-Win11pResourceIdentity -IdentityFile $file.FullName
            if($identity.request_id -cne $RequestId){throw 'WIN11P resource recovery request identity mismatch'}
            $results.Add((Stop-Win11pOwnedDatabase -IdentityFile $file.FullName))
        }catch{$failures++}
    }
    if($failures -gt 0){throw ('WIN11P request recovery refused or failed for '+$failures+' journal(s)')}
    return $results.ToArray()
}

Export-ModuleMember -Function Get-Win11pMatrixConfig, Get-Win11pTestAssignment, ConvertFrom-Win11pSurefireReports, Compare-Win11pTestMatrix, Invoke-Win11pChildProcess, New-Win11pOwnedDatabase, Stop-Win11pOwnedDatabase, Recover-Win11pOwnedDatabases
