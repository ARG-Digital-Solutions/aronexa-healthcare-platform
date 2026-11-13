# R1-PILOT-PREVIEW: build existing fictional UI surfaces, never customer software.
# NO database, installer, service, migration, license, backend or patient data.
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)]
  [ValidatePattern('^[0-9a-fA-F]{40}$')]
  [string]$ExpectedCommit
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$actual=(& git -C $root rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $actual -ne $ExpectedCommit.ToLowerInvariant()){
  throw 'PILOT PREVIEW STOP: exact source SHA mismatch.'
}
$modified=@(& git -C $root status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($modified | Where-Object {$_.Trim()}).Count -gt 0){
  throw 'PILOT PREVIEW STOP: tracked source differs from committed SHA.'
}
$frontend=Join-Path $root 'frontend'
$manifest=Join-Path $frontend 'package.json'
if(-not(Test-Path -LiteralPath $manifest -PathType Leaf)){
  throw 'PILOT PREVIEW STOP: frontend/package.json missing.'
}
$pkg=Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
foreach($command in @('build:connected:test','build:connected:doctor:test','build:connected:billing:test')){
  if(-not($pkg.scripts.PSObject.Properties.Name -contains $command)){
    throw "PILOT PREVIEW STOP: missing npm script $command."
  }
}
$npm=Get-Command npm.cmd -ErrorAction SilentlyContinue
if($null -eq $npm){throw 'PILOT PREVIEW STOP: npm.cmd missing from PATH.'}
$builds=@(
  [pscustomobject]@{command='build:connected:test';folder='dist-connected-reception-test';html='connected-reception-preview.html'},
  [pscustomobject]@{command='build:connected:doctor:test';folder='dist-connected-doctor-test';html='connected-doctor-preview.html'},
  [pscustomobject]@{command='build:connected:billing:test';folder='dist-connected-billing-test';html='connected-billing-preview.html'}
)
# Resolve output folders against the Vite configuration, not this script's guesses.
$expectedConfigs=@(
  'vite.connected-reception.config.ts',
  'vite.connected-doctor.config.ts',
  'vite.connected-billing.config.ts'
)
foreach($config in $expectedConfigs){
  if(-not(Test-Path -LiteralPath (Join-Path $frontend $config))){
    throw "PILOT PREVIEW STOP: missing $config"
  }
}
Push-Location $frontend
try{
  & $npm.Source ci
  if($LASTEXITCODE -ne 0){throw 'PILOT PREVIEW FAIL: npm ci.'}
  foreach($build in $builds){
    Write-Host ("Building existing test-only UI: "+$build.command)
    & $npm.Source run $build.command
    if($LASTEXITCODE -ne 0){
      throw ("PILOT PREVIEW FAIL: "+$build.command)
    }
  }
}finally{
  Pop-Location
}
Write-Host 'PILOT PREVIEW BUILD PASS: existing three independent UI surfaces.' -ForegroundColor Green
Write-Host "SourceCommit=$actual"
Write-Host 'This does NOT provide connected end-to-end persistence, installer, SQL, clinical authorization or Windows release GO.'
Write-Host 'To view a surface, run ONE existing Vite dev command at a time in frontend:'
Write-Host '  npm run dev:connected:test'
Write-Host '  npm run dev:connected:doctor:test'
Write-Host '  npm run dev:connected:billing:test'
Write-Host 'Do not enter real patient data or promote preview endpoints to production.'
