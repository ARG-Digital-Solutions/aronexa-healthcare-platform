#requires -Version 5.1
[CmdletBinding()]
param([string]$RepoPath = '', [string]$ExpectedSha = '', [string]$EvidenceDirectory = '')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$checks = [ordered]@{ prerequisites='NOT_RUN'; clean_revision='NOT_RUN'; docs='NOT_RUN'; source_scope='NOT_RUN'; tracked_secrets='NOT_RUN'; docker_local='NOT_RUN'; compose_config='NOT_RUN'; backend_testcontainers='NOT_RUN'; frontend_build_and_tests='NOT_RUN'; npm_high_critical_audit='NOT_RUN'; release_gate_unit_tests='NOT_RUN' }
$started=[DateTimeOffset]::UtcNow.ToString('o')
$sha=''; $failure=''; $testCount=0; $testSkipped=0; $overall='FAIL'; $root=''; $evidence=''
function Invoke-Native([string]$Executable,[string[]]$Arguments) {
  & $Executable @Arguments
  if($LASTEXITCODE -ne 0){throw "$Executable failed (exit $LASTEXITCODE)"}
}
function Assert-Available([string]$Name){if(-not(Get-Command $Name -ErrorAction SilentlyContinue)){throw "Missing prerequisite: $Name"}}
function Invoke-Gate([string]$Name,[scriptblock]$Action){Write-Host "[CI] $Name"; & $Action; $checks[$Name]='PASS'}
function Get-GitOutput([string[]]$Arguments){$result=& git -C $root @Arguments; if($LASTEXITCODE -ne 0){throw 'git command failed'}; return $result}
try {
  if(-not $RepoPath){$RepoPath=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path}
  $root=(Resolve-Path -LiteralPath $RepoPath).Path
  if(-not(Test-Path (Join-Path $root 'backend\pom.xml')) -or -not(Test-Path (Join-Path $root 'frontend\package.json'))){throw 'AroNexa checkout required'}
  if($ExpectedSha -and $ExpectedSha -cnotmatch '^[0-9a-f]{40}$'){throw 'ExpectedSha must be full lowercase 40-character SHA'}
  $sha=[string](Get-GitOutput -Arguments @('rev-parse','HEAD'))
  if(-not $EvidenceDirectory){$EvidenceDirectory=Join-Path (Join-Path ([IO.Path]::GetTempPath()) 'aronexa-ci-evidence') ('aronexa-'+[DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+$sha.Substring(0,8))}
  $evidence=[IO.Path]::GetFullPath($EvidenceDirectory)
  if($evidence.Equals($root,[StringComparison]::OrdinalIgnoreCase) -or $evidence.StartsWith($root.TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Evidence folder must be outside checkout'}
  New-Item -ItemType Directory -Force -Path $evidence | Out-Null
  Invoke-Gate 'prerequisites' {
    if([Environment]::OSVersion.Platform -ne 'Win32NT'){throw 'Windows required'}
    foreach($command in @('git','java','javac','mvn.cmd','node','npm.cmd','python','docker')){Assert-Available $command}
    $javac=[string](& javac -version 2>&1); if($LASTEXITCODE -ne 0 -or $javac -notmatch '^javac 21(\.|$)'){throw "Java 21 required: $javac"}
    $nodeVer=[string](& node -p 'process.versions.node'); if($LASTEXITCODE -ne 0 -or [int]($nodeVer.Split('.')[0]) -lt 22){throw 'Node.js 22+ required'}
  }
  Invoke-Gate 'clean_revision' {
    if($ExpectedSha -and $sha -cne $ExpectedSha){throw "Wrong source SHA: $sha"}
    $dirty=@(Get-GitOutput -Arguments @('status','--porcelain','--untracked-files=normal'))
    if(($dirty -join '').Trim()){throw 'Source checkout dirty; use isolated clean worktree'}
  }
  Push-Location -LiteralPath $root
  try {
    Invoke-Gate 'docs' {Invoke-Native -Executable 'python' -Arguments @('scripts/ci/check_docs.py')}
    Invoke-Gate 'source_scope' {
      $file=Join-Path $evidence 'tracked-source-paths.txt'
      @(Get-GitOutput -Arguments @('ls-files')) | Set-Content -LiteralPath $file -Encoding UTF8
      Invoke-Native -Executable 'python' -Arguments @('scripts/ci/check_scope.py',$file)
    }
    Invoke-Gate 'tracked_secrets' {Invoke-Native -Executable 'python' -Arguments @('scripts/ci/check_secrets.py')}
    Invoke-Gate 'docker_local' {
      if($env:DOCKER_HOST -or $env:TESTCONTAINERS_HOST_OVERRIDE){throw 'Remote Docker overrides prohibited'}
      $context=[string](& docker context show)
      if($LASTEXITCODE -ne 0 -or $context -notin @('default','desktop-linux')){throw 'Unapproved Docker context'}
      $os=[string](& docker info --format '{{.OSType}}')
      if($LASTEXITCODE -ne 0 -or $os.Trim() -ne 'linux'){throw 'Local Linux Docker daemon required'}
    }
    Invoke-Gate 'compose_config' {
      $names=@('ARONEXA_DB_PASSWORD','ARONEXA_RUNTIME_DB_PASSWORD','ARONEXA_AUTH_DB_PASSWORD','ARONEXA_PATIENT_DB_PASSWORD')
      $saved=@{}; foreach($n in $names){$saved[$n]=[Environment]::GetEnvironmentVariable($n,'Process');[Environment]::SetEnvironmentVariable($n,'synthetic-compose-validation-only','Process')}
      try{Invoke-Native -Executable 'docker' -Arguments @('compose','-f','deploy/dev/compose.yml','config','--quiet')}
      finally{foreach($n in $names){[Environment]::SetEnvironmentVariable($n,$saved[$n],'Process')}}
    }
    Invoke-Gate 'backend_testcontainers' {
      Invoke-Native -Executable 'mvn.cmd' -Arguments @('-B','-f','backend/pom.xml','verify')
      $reports=@(Get-ChildItem -Path (Join-Path $root 'backend\target') -Recurse -File -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue | Where-Object {$_.FullName -match '(surefire-reports|failsafe-reports)'})
      if($reports.Count -eq 0){throw 'Missing JUnit XML'}
      $total=0; $failed=0; $skipped=0
      foreach($report in $reports){
        [xml]$xml=Get-Content -LiteralPath $report.FullName -Raw
        if($null -eq $xml.testsuite){throw 'Bad JUnit XML'}
        $total += [int]$xml.testsuite.tests
        $failed += [int]$xml.testsuite.failures + [int]$xml.testsuite.errors
        $skipped += [int]$xml.testsuite.skipped
      }
      if($total -lt 1 -or $failed -gt 0 -or $skipped -gt 0){throw "JUnit failed/skipped: tests=$total fail=$failed skipped=$skipped"}
      $script:testCount=$total; $script:testSkipped=$skipped
    }
    Invoke-Gate 'frontend_build_and_tests' {
      Push-Location -LiteralPath (Join-Path $root 'frontend')
      try{Invoke-Native -Executable 'npm.cmd' -Arguments @('ci','--no-audit','--no-fund');Invoke-Native -Executable 'npm.cmd' -Arguments @('run','build')}
      finally{Pop-Location}
    }
    Invoke-Gate 'npm_high_critical_audit' {
      Push-Location -LiteralPath (Join-Path $root 'frontend')
      try{Invoke-Native -Executable 'npm.cmd' -Arguments @('audit','--audit-level=high','--registry=https://registry.npmjs.org/')}
      finally{Pop-Location}
    }
    Invoke-Gate 'release_gate_unit_tests' {Invoke-Native -Executable 'python' -Arguments @('-m','unittest','discover','-s','scripts/ci','-p','test_release_gate.py','-v')}
  } finally{Pop-Location}
  $overall='PASS'
} catch {
  $failure=[string]$_.Exception.Message
  Write-Warning "AroNexa CI failed: $failure"
} finally {
  if(-not $evidence){$evidence=Join-Path ([IO.Path]::GetTempPath()) ('aronexa-ci-failure-'+[DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss'))}
  New-Item -ItemType Directory -Force -Path $evidence | Out-Null
  $manifest=[ordered]@{schema_version=1;kind='aronexa_application_local_ci';application_sha=$sha;started_utc=$started;completed_utc=[DateTimeOffset]::UtcNow.ToString('o');result=$overall;checks=$checks;junit_tests=$testCount;junit_skipped=$testSkipped;failure=$failure;customer_release_approved=$false;synthetic_only=$true}
  $path=Join-Path $evidence 'application-ci-evidence.json'
  $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $path -Encoding UTF8
  Write-Host "CI_RESULT=$overall; APP_SHA=$sha; EVIDENCE=$path"
}
if($overall -ne 'PASS'){exit 1}
exit 0
