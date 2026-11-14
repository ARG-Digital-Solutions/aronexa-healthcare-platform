# WIN11: start/stop TWO different Windows native EXE processes.
# Tests static localhost shell and deny-by-default clinical APIs only.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit,
 [string]$AppImagePath=''
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT){
 throw 'WIN11 STOP: Windows build host required'
}
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'WIN11 STOP: expected source SHA differs'
}
$dirty=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($dirty | Where-Object {$_.Trim()}).Count -ne 0){
 throw 'WIN11 STOP: tracked sources dirty'
}
if(-not $AppImagePath){
 $AppImagePath=Join-Path (Join-Path $env:USERPROFILE 'Downloads') ('AroNexaClinicShell-'+$sha.Substring(0,12))
}
$image=(Resolve-Path -LiteralPath $AppImagePath -ErrorAction Stop).Path
$exe=Join-Path $image 'AroNexaClinicShell.exe'
$marker=Join-Path $image 'app\WIN11_SOURCE_SHA.txt'
if(-not(Test-Path -LiteralPath $exe -PathType Leaf) -or
   -not(Test-Path -LiteralPath $marker -PathType Leaf)){
 throw 'WIN11 STOP: native EXE or source SHA marker absent'
}
if((Get-Content -LiteralPath $marker -Raw).Trim() -ne $sha){
 throw 'WIN11 STOP: app-image source differs'
}
foreach($key in @('SPRING_DATASOURCE_URL','SPRING_PROFILES_ACTIVE',
 'ARONEXA_DB_URL','ARONEXA_RUNTIME_DB_PASSWORD')){
 if([Environment]::GetEnvironmentVariable($key)){
  throw ('WIN11 STOP: inherited unsafe database/profile variable '+$key)
 }
}
& $exe --selftest
if($LASTEXITCODE -ne 0){throw 'WIN11 FAIL: app-image selftest'}
Add-Type -AssemblyName System.Net.Http
$handler=[System.Net.Http.HttpClientHandler]::new()
$handler.UseProxy=$false
$client=[System.Net.Http.HttpClient]::new($handler)
$client.Timeout=[TimeSpan]::FromSeconds(3)
$process=$null
$pids=[System.Collections.Generic.List[int]]::new()
$temp=Join-Path $env:TEMP ('aronexa-win11-restart-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($temp)
try{
 $portFree=$true
 $socket=[System.Net.Sockets.TcpClient]::new()
 try{
  $attempt=$socket.BeginConnect('127.0.0.1',8766,$null,$null)
  if($attempt.AsyncWaitHandle.WaitOne(500)){
   try{$socket.EndConnect($attempt);$portFree=$false}catch{}
  }
 }finally{$socket.Dispose()}
 if(-not $portFree){throw 'WIN11 STOP: port 8766 occupied, do not touch another process'}
 for($i=1;$i -le 2;$i++){
  $stdout=Join-Path $temp ('stdout-'+$i+'.log')
  $stderr=Join-Path $temp ('stderr-'+$i+'.log')
  $process=Start-Process -FilePath $exe -ArgumentList '--serve' -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
  if($null -eq $process){throw 'WIN11 FAIL: shell process failed to launch'}
  [void]$pids.Add([int]$process.Id)
  $ready=$false
  for($j=0;$j -lt 35;$j++){
   if($process.HasExited){throw 'WIN11 FAIL: launched EXE exited early'}
   try{
    $health=$client.GetAsync('http://127.0.0.1:8766/healthz').GetAwaiter().GetResult()
    $body=$health.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if([int]$health.StatusCode -eq 200 -and
       $body.Contains('WINDOWS_STATIC_SHELL_ONLY') -and
       $body.Contains('"clinicalReady":false')){
     $ready=$true;break
    }
   }catch{}
   Start-Sleep -Milliseconds 350
  }
  if(-not $ready){throw 'WIN11 FAIL: no secure loopback health response'}
  foreach($case in @(
   @{Path='/';Code=200},
   @{Path='/connected-reception-preview.html';Code=200},
   @{Path='/api/v1/auth/login';Code=503},
   @{Path='/api/v1/patients';Code=503},
   @{Path='/.env';Code=404},
   @{Path='/aronexa-backend.jar';Code=404}
  )){
   $res=$client.GetAsync('http://127.0.0.1:8766'+$case.Path).GetAwaiter().GetResult()
   if([int]$res.StatusCode -ne $case.Code){
    throw ('WIN11 FAIL: incorrect HTTP code '+$case.Path)
   }
   $cache=@($res.Headers.GetValues('Cache-Control')) -join ','
   if(-not $cache.Contains('no-store')){
    throw 'WIN11 FAIL: response missing no-store'
   }
  }
  $running=Get-Process -Id $process.Id -ErrorAction Stop
  if(-not [IO.Path]::GetFullPath($running.Path).Equals(
       [IO.Path]::GetFullPath($exe),[StringComparison]::OrdinalIgnoreCase)){
   throw 'WIN11 STOP: wrong process path'
  }
  Stop-Process -Id $process.Id -Force -ErrorAction Stop
  $null=$process.WaitForExit(12000)
  if(-not $process.HasExited){throw 'WIN11 FAIL: process did not exit'}
  $process=$null
 }
 if($pids.Count -ne 2 -or $pids[0] -eq $pids[1]){
  throw 'WIN11 FAIL: distinct OS PIDs missing'
 }
 Write-Host 'WIN11 NATIVE APPIMAGE TWO-PROCESS RESTART / API DENIAL QA PASSED' -ForegroundColor Green
 Write-Host ('FirstProcessPID='+$pids[0])
 Write-Host ('SecondProcessPID='+$pids[1])
 Write-Host ('SourceCommit='+$sha)
 Write-Host 'Static shell only: no clinic DB, clinical runtime or Windows Service acceptance.'
}finally{
 if($null -ne $process -and -not $process.HasExited){
  $other=Get-Process -Id $process.Id -ErrorAction SilentlyContinue
  if($null -ne $other -and
     [IO.Path]::GetFullPath($other.Path).Equals(
         [IO.Path]::GetFullPath($exe),[StringComparison]::OrdinalIgnoreCase)){
   Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
  }
 }
 $client.Dispose()
 if(Test-Path -LiteralPath $temp){
  Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
 }
}
