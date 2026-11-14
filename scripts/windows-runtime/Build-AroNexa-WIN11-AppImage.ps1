# WIN11 Windows app-image proof; clinical backend intentionally NOT RUN.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit,
 [string]$DestinationDirectory=(Join-Path $env:USERPROFILE 'Downloads')
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT){
 throw 'WIN11 STOP: Windows build host required'
}
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'WIN11 STOP: exact source SHA mismatch'
}
$dirty=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($dirty | Where-Object {$_.Trim()}).Count -ne 0){
 throw 'WIN11 STOP: tracked source tree is dirty'
}
foreach($key in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
 'SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE','ARONEXA_DB_URL',
 'ARONEXA_DB_PASSWORD','ARONEXA_AUTH_DB_PASSWORD','ARONEXA_PATIENT_DB_PASSWORD')){
 if([Environment]::GetEnvironmentVariable($key)){
  throw ('WIN11 STOP: inherited clinical datasource/profile setting '+$key)
 }
}
if(-not(Test-Path -LiteralPath $DestinationDirectory -PathType Container)){
 throw 'WIN11 STOP: output directory missing'
}
$out=(Resolve-Path -LiteralPath $DestinationDirectory).Path
if($out.StartsWith($repo,[StringComparison]::OrdinalIgnoreCase)){
 throw 'WIN11 STOP: output must be outside source repo'
}
# Resolve a complete JDK 21 atomically. Windows commonly has java.exe
# on PATH (or a vendor shim) while jar.exe/jpackage.exe are missing.
# Never mix java, javac, jar and jpackage from different installations.
$jdkCandidates=[System.Collections.Generic.List[string]]::new()
if($env:JAVA_HOME){[void]$jdkCandidates.Add($env:JAVA_HOME)}
foreach($name in @('javac.exe','java.exe')){
 $command=Get-Command $name -ErrorAction SilentlyContinue
 if($null -ne $command){
  $bin=Split-Path -Path $command.Source -Parent
  [void]$jdkCandidates.Add((Split-Path -Path $bin -Parent))
 }
}
foreach($base in @(
 (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
 (Join-Path $env:ProgramFiles 'Microsoft'),
 (Join-Path $env:ProgramFiles 'Java'),
 (Join-Path $env:ProgramFiles 'Amazon Corretto'),
 (Join-Path $env:ProgramFiles 'Zulu'),
 (Join-Path $env:LOCALAPPDATA 'Programs'),
 (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium'),
 (Join-Path $env:LOCALAPPDATA 'Programs\Microsoft'),
 (Join-Path $env:USERPROFILE '.jdks')
)){
 if(Test-Path -LiteralPath $base -PathType Container){
  foreach($directory in @(Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue)){
   [void]$jdkCandidates.Add($directory.FullName)
  }
 }
}
# Also account for the user-scoped Temurin MSI layout under
# %LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-21...; earlier
# discovery considered the outer Programs folder but only one child level.
$jdkHome=$null
$jdkBin=$null
foreach($candidate in $jdkCandidates){
 if(-not $candidate){continue}
 $possibleBin=Join-Path $candidate 'bin'
 $complete=$true
 foreach($binary in @('java.exe','javac.exe','jar.exe','jpackage.exe')){
  if(-not(Test-Path -LiteralPath (Join-Path $possibleBin $binary) -PathType Leaf)){
   $complete=$false;break
  }
 }
 if(-not $complete){continue}
 $version=@(& (Join-Path $possibleBin 'java.exe') --version 2>&1 |
  Select-Object -First 1)
 $compiler=@(& (Join-Path $possibleBin 'javac.exe') --version 2>&1 |
  Select-Object -First 1)
 if($LASTEXITCODE -ne 0 -or @($version).Count -eq 0 -or
    @($compiler).Count -eq 0){continue}
 if(([string]$version[0]) -notmatch '(?:^|[\s"])21\.' -or
    ([string]$compiler[0]) -notmatch '^javac 21\.'){continue}
 $jdkHome=(Resolve-Path -LiteralPath $candidate).Path
 $jdkBin=Join-Path $jdkHome 'bin'
 break
}
if(-not $jdkHome){
 throw ('WIN11 STOP: full JDK 21 with java.exe, javac.exe, jar.exe and '+
  'jpackage.exe not found. Install a complete JDK 21, set JAVA_HOME '+
  'to its installation root (NOT the bin folder), then reopen PowerShell. '+
  'A Java runtime alone is insufficient.')
}
$tools=@{}
foreach($binary in @('java.exe','javac.exe','jar.exe','jpackage.exe')){
 $tools[$binary]=Join-Path $jdkBin $binary
}
foreach($binary in @('mvn.cmd','npm.cmd')){
 $found=Get-Command $binary -ErrorAction SilentlyContinue
 if($null -eq $found){
  throw ('WIN11 STOP: '+$binary+' is not installed or available on PATH')
 }
 $tools[$binary]=$found.Source
}
# Child Maven and jpackage processes must use the SAME complete JDK 21.
$env:JAVA_HOME=$jdkHome
$env:PATH=$jdkBin+[IO.Path]::PathSeparator+$env:PATH
Write-Host ('WIN11 Java toolchain: '+$jdkHome)
Write-Host 'WIN11 validated: java.exe, javac.exe, jar.exe and jpackage.exe (JDK 21)'
$name='AroNexaClinicShell'
$output=Join-Path $out ($name+'-'+$sha.Substring(0,12))
if(Test-Path -LiteralPath $output){
 throw 'WIN11 STOP: artifact already exists, do not overwrite evidence'
}
$temp=Join-Path $env:TEMP ('aronexa-win11-'+[guid]::NewGuid().ToString('N'))
$stage=Join-Path $temp 'input'
$classes=Join-Path $temp 'classes'
[void][IO.Directory]::CreateDirectory($stage)
[void][IO.Directory]::CreateDirectory($classes)
try{
 Write-Host 'WIN11 Step 1: compile production-source Spring Boot JAR (not started)'
 & $tools['mvn.cmd'] -B -f (Join-Path $repo 'backend\pom.xml') '-DskipTests' package
 if($LASTEXITCODE -ne 0){throw 'WIN11 FAIL: Spring Boot build'}
 $backend=Join-Path $repo 'backend\target\aronexa-backend-0.1.0-SNAPSHOT.jar'
 if(-not(Test-Path -LiteralPath $backend -PathType Leaf)){
  throw 'WIN11 FAIL: backend JAR missing'
 }
 Write-Host 'WIN11 Step 2: frontend Node tests, TypeScript and connected React build'
 Push-Location (Join-Path $repo 'frontend')
 try{
  & $tools['npm.cmd'] ci --no-audit --no-fund
  if($LASTEXITCODE -ne 0){throw 'WIN11 FAIL: npm ci'}
  & $tools['npm.cmd'] test
  if($LASTEXITCODE -ne 0){throw 'WIN11 FAIL: frontend unit tests'}
  & $tools['npm.cmd'] run 'build:connected:test'
  if($LASTEXITCODE -ne 0){throw 'WIN11 FAIL: connected frontend build'}
 } finally {Pop-Location}
 $dist=Join-Path $repo 'frontend\dist-connected-registration-test'
 if(-not(Test-Path -LiteralPath (Join-Path $dist 'connected-reception-preview.html') -PathType Leaf)){
  throw 'WIN11 FAIL: connected Reception HTML missing'
 }
 Set-Content -LiteralPath (Join-Path $stage 'WIN11_SOURCE_SHA.txt') -Encoding ASCII -Value $sha
 Copy-Item -LiteralPath $backend -Destination (Join-Path $stage 'aronexa-backend.jar')
 Copy-Item -LiteralPath (Join-Path $dist 'connected-reception-preview.html') -Destination (Join-Path $stage 'connected-reception-preview.html')
 Copy-Item -LiteralPath (Join-Path $dist 'assets') -Destination (Join-Path $stage 'assets') -Recurse
 $index=@'
<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AroNexa Clinic Edition — Windows Runtime Shell</title>
<style>body{font:16px/1.6 system-ui,sans-serif;margin:7vh auto;padding:0 24px;max-width:730px;color:#17314b}h1{line-height:1.2}aside{background:#f1f5fa;border-left:4px solid #175b85;padding:18px;margin:24px 0}a{color:#14618b}</style>
<h1>AroNexa Clinic Edition — Windows Runtime Shell</h1>
<aside><strong>Internal packaging proof. Clinical APIs are disabled.</strong>
<p>This is not the approved clinic application. No database, patient registration, appointment booking, prescription, billing or patient records are available here.</p></aside>
<p>Java and compiled React are bundled offline. The clinical backend must pass security, SQL, clinical and release reviews before enabling its functions.</p>
<p><a href="/connected-reception-preview.html">View compiled Reception interface (nonfunctional without approved backend)</a></p>
<p>Health: <a href="/healthz">/healthz</a></p>
'@
 [IO.File]::WriteAllText((Join-Path $stage 'index.html'),$index,[Text.UTF8Encoding]::new($false))
 Write-Host 'WIN11 Step 3: compile localhost-only native shell and stage file hashes'
 $source=Join-Path $PSScriptRoot 'ClinicWindowsShell.java'
 & $tools['javac.exe'] --release 21 --add-modules jdk.httpserver -encoding UTF-8 -d $classes $source
 if($LASTEXITCODE -ne 0){throw 'WIN11 FAIL: Java shell compilation'}
 $shellJar=Join-Path $stage 'aronexa-clinic-shell.jar'
 & $tools['jar.exe'] --create --file $shellJar --main-class ClinicWindowsShell -C $classes ClinicWindowsShell.class
 if($LASTEXITCODE -ne 0){throw 'WIN11 FAIL: Java launcher JAR'}
 $manifest=Join-Path $stage 'WIN11-SHA256.tsv'
 $entries=@(Get-ChildItem -LiteralPath $stage -File -Recurse | Sort-Object FullName)
 $lines=@($entries | ForEach-Object {
  (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash+([char]9)+
  $_.FullName.Substring($stage.Length+1).Replace('\','/')
 })
 if($lines.Count -lt 4){throw 'WIN11 FAIL: manifest too short'}
 [IO.File]::WriteAllLines($manifest,[string[]]$lines,[Text.UTF8Encoding]::new($false))
 & $tools['java.exe'] --add-modules jdk.httpserver -cp $shellJar ClinicWindowsShell --selftest
 if($LASTEXITCODE -ne 0){throw 'WIN11 FAIL: staged HTTP/clinical API denial selftest'}
 Write-Host 'WIN11 Step 4: jpackage Windows app-image with bundled Java runtime'
 $destination=Join-Path $temp 'package'
 [void][IO.Directory]::CreateDirectory($destination)
 $args=@('--type','app-image','--name',$name,'--input',$stage,
   '--main-jar','aronexa-clinic-shell.jar','--main-class','ClinicWindowsShell',
   '--dest',$destination,'--app-version','0.11.0','--win-console',
   '--add-modules','java.net.http,jdk.httpserver')
 & $tools['jpackage.exe'] @args
 if($LASTEXITCODE -ne 0){throw 'WIN11 FAIL: jpackage Windows app-image'}
 $image=Join-Path $destination $name
 $exe=Join-Path $image ($name+'.exe')
 if(-not(Test-Path -LiteralPath $exe -PathType Leaf)){
  throw 'WIN11 FAIL: native EXE missing'
 }
 & $exe --selftest
 if($LASTEXITCODE -ne 0){throw 'WIN11 FAIL: packaged EXE HTTP/API isolation selftest'}
 Move-Item -LiteralPath $image -Destination $output
 $launcher=Join-Path $output ($name+'.exe')
 $hash=(Get-FileHash -LiteralPath $launcher -Algorithm SHA256).Hash
 Write-Host 'WIN11 NATIVE APPIMAGE SELFTEST PASSED' -ForegroundColor Green
 Write-Host ('WindowsAppImage='+$output)
 Write-Host ('WindowsExe='+$launcher)
 Write-Host ('WindowsExeSha256='+$hash)
 Write-Host ('SourceCommit='+$sha)
 Write-Host 'The EXE serves STATIC UI only; all clinical APIs return 503. No customer install or GO.'
} finally {
 if(Test-Path -LiteralPath $temp){
  Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
 }
}
