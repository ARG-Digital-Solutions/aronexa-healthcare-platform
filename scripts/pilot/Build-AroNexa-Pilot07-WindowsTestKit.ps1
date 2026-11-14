# R1-PILOT-07: produce a private DEVELOPER-QA ZIP containing source,
# built test-only UI, guarded launcher and checksums. NOT an installer.
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
 throw 'PILOT07 BUILD STOP: Windows build host required.'
}
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'PILOT07 BUILD STOP: exact source SHA mismatch.'
}
$dirty=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($dirty | Where-Object {$_.Trim()}).Count -ne 0){
 throw 'PILOT07 BUILD STOP: tracked source not clean.'
}
foreach($name in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
   'SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE')){
 if([Environment]::GetEnvironmentVariable($name)){
  throw ('PILOT07 BUILD STOP: external datasource variable '+$name)
 }
}
$npm=Get-Command npm.cmd -ErrorAction SilentlyContinue
if($null -eq $npm){throw 'PILOT07 BUILD STOP: npm.cmd missing.'}
if(-not(Test-Path -LiteralPath $DestinationDirectory -PathType Container)){
 throw 'PILOT07 BUILD STOP: create the destination directory first.'
}
$dest=(Resolve-Path -LiteralPath $DestinationDirectory).Path
if($dest.StartsWith($repo,[StringComparison]::OrdinalIgnoreCase)){
 throw 'PILOT07 BUILD STOP: output must be outside the source repository.'
}
$stageRoot=Join-Path ([IO.Path]::GetTempPath()) ('pilot07-kit-'+[guid]::NewGuid().ToString('N'))
$stage=Join-Path $stageRoot 'bundle'
[void][IO.Directory]::CreateDirectory($stage)
try {
 Write-Host 'Building test-only connected frontend; no clinic services or migrations'
 Push-Location (Join-Path $repo 'frontend')
 try {
  & $npm.Source ci --no-audit --no-fund
  if($LASTEXITCODE -ne 0){throw 'PILOT07 BUILD FAIL: npm ci'}
  & $npm.Source run 'build:connected:test'
  if($LASTEXITCODE -ne 0){throw 'PILOT07 BUILD FAIL: connected browser UI build'}
 } finally {Pop-Location}
 $dist=Join-Path $repo 'frontend\dist-connected-registration-test'
 if(-not(Test-Path -LiteralPath (Join-Path $dist 'connected-reception-preview.html') -PathType Leaf)){
  throw 'PILOT07 BUILD FAIL: integrated browser HTML missing.'
 }
 # Checkout only tracked source at exact SHA. NEVER include .git, local
 # .env, node_modules, private credentials, DB volumes or saved patient data.
 $gitZip=Join-Path $stageRoot 'source.zip'
 & git -C $repo archive '--format=zip' ('--output='+$gitZip) $sha
 if($LASTEXITCODE -ne 0){throw 'PILOT07 BUILD FAIL: git archive'}
 Expand-Archive -LiteralPath $gitZip -DestinationPath $stage
 Copy-Item -LiteralPath $dist -Destination (Join-Path $stage 'frontend') -Recurse
 Set-Content -LiteralPath (Join-Path $stage 'PILOT07_SOURCE_SHA.txt') -Encoding ASCII -Value $sha
 $readme=@'
ARONEXA CLINIC OPD TEST LAB - OWNER-OPERATED, INTERNAL QA ONLY

THIS ZIP CONTAINS PRIVATE DEVELOPMENT SOURCE. DO NOT SEND TO CLIENTS.
NO COMMERCIAL INSTALLER, SIGNATURE, OFFLINE LICENSE OR DATA RESTORE.
Do not use on a clinic workstation or enter any real patient information.
Uses Java 21, Maven, npm and Docker to run a temporary test backend.

1) Independently verify SHA256 of this ZIP against the builder output.
2) Extract ZIP into a NEW empty directory on a disposable Windows VM.
3) Install Java 21, Maven, Node/npm, Docker Engine first.
4) From the extracted root, run this example after substituting SHA:
powershell.exe -NoProfile -File .\scripts\pilot\Start-AroNexa-Pilot07-DisposableWindowsLab.ps1 -ExpectedCommit YOUR_SHA -ConfirmFictionalDisposableLab -FromVerifiedArchive
5) The PowerShell console prints a local URL, fictional staff login file
   and STOP-file command. Open in the same VM; test Reception/Doctor/Billing.
6) Write the STOP file in a second PowerShell to shut down safely.
7) Run the nonempty PG17 restore rehearsal separately from a clean Git
   worktree. It is not encrypted recovery or customer disaster recovery.

The embedded manifest detects accidental file changes, but is NOT a
vendor digital signature. Source SHA, ZIP SHA256 and independent reviewer
must be recorded. No real clinical application is installed by this kit.
'@
 Set-Content -LiteralPath (Join-Path $stage 'READ-ME-PILOT07-TEST-ONLY.txt') -Value $readme -Encoding ASCII

 $records=[System.Collections.Generic.List[string]]::new()
 $all=@(Get-ChildItem -LiteralPath $stage -File -Recurse | Sort-Object FullName)
 foreach($item in $all){
  $rel=$item.FullName.Substring($stage.Length+1).Replace('\','/')
  if($rel -eq 'PILOT07_SOURCE_SHA256.tsv'){continue}
  $hash=(Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash
  [void]$records.Add($hash+([char]9)+$rel)
 }
 $manifest=Join-Path $stage 'PILOT07_SOURCE_SHA256.tsv'
 [IO.File]::WriteAllLines($manifest,[string[]]$records,
     [Text.UTF8Encoding]::new($false))
 & powershell.exe -NoProfile -File (Join-Path $stage 'scripts\pilot\Test-AroNexa-Pilot07-Archive.ps1') -ExpectedCommit $sha
 if($LASTEXITCODE -ne 0){throw 'PILOT07 BUILD FAIL: staged ZIP source integrity'}
 $zip=Join-Path $dest ('AroNexa-Clinic-OPD-TestLab-'+$sha.Substring(0,12)+'.zip')
 if(Test-Path -LiteralPath $zip){throw 'PILOT07 BUILD STOP: target ZIP already exists'}
 # ZipFile.CreateFromDirectory includes dot-prefixed repository files;
 # Compress-Archive can omit hidden files on some Windows configurations.
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 [IO.Compression.ZipFile]::CreateFromDirectory(
    $stage,$zip,[IO.Compression.CompressionLevel]::Optimal,$false)
 $digest=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
 Write-Host 'PILOT07 WINDOWS TEST-LAB SOURCE ZIP BUILT' -ForegroundColor Green
 Write-Host ('Artifact='+$zip)
 Write-Host ('ZIP_SHA256='+$digest)
 Write-Host ('SourceCommit='+$sha)
 Write-Host 'Classification: private operator QA bundle; NOT signed installer or customer software.'
} finally {
 if(Test-Path -LiteralPath $stageRoot){
  Remove-Item -LiteralPath $stageRoot -Recurse -Force -ErrorAction SilentlyContinue
 }
}
