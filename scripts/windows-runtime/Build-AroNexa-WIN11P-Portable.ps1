# WIN-11P — portable Java bootstrap. INTERNAL QA ONLY; no customer release.
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)]
  [ValidatePattern('^[0-9a-fA-F]{40}$')]
  [string]$ExpectedCommit,
  [string]$DestinationDirectory=(Join-Path $env:USERPROFILE 'Downloads'),
  [switch]$PackageOnlyUnverified
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
  throw 'WIN11P STOP: Windows build host required'
}
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$sha = (& git -C $repo rev-parse HEAD)
if ($LASTEXITCODE -ne 0 -or -not $sha) {
  throw 'WIN11P STOP: cannot resolve source commit'
}
$sha = ([string]$sha).Trim().ToLowerInvariant()
if ($sha -ne $ExpectedCommit.ToLowerInvariant()) {
  throw 'WIN11P STOP: exact source commit mismatch'
}
$dirty = @(& git -C $repo status --porcelain --untracked-files=no)
if ($LASTEXITCODE -ne 0 -or @($dirty | Where-Object { $_ -and $_.Trim() }).Count -ne 0) {
  throw 'WIN11P STOP: tracked source files must be clean'
}
if (-not (Test-Path -LiteralPath $DestinationDirectory -PathType Container)) {
  throw 'WIN11P STOP: destination directory must exist'
}
$out = (Resolve-Path -LiteralPath $DestinationDirectory).Path
$repoPrefix = $repo.TrimEnd('\') + '\'
if ($out.Equals($repo,[StringComparison]::OrdinalIgnoreCase) -or
    $out.StartsWith($repoPrefix,[StringComparison]::OrdinalIgnoreCase)) {
  throw 'WIN11P STOP: build output must be outside the Git repository'
}
if (-not $env:JAVA_HOME) {
  throw 'WIN11P STOP: set JAVA_HOME to a full JDK 21 installation'
}
$jdk = (Resolve-Path -LiteralPath $env:JAVA_HOME -ErrorAction Stop).Path
$java = Join-Path $jdk 'bin\java.exe'
$jlink = Join-Path $jdk 'bin\jlink.exe'
foreach ($binary in @($java,$jlink)) {
  if (-not (Test-Path -LiteralPath $binary -PathType Leaf)) {
    throw ('WIN11P STOP: missing JDK tool ' + $binary)
  }
}
# Use stdout-producing --version; Java -version writes stderr and can throw with ErrorActionPreference=Stop.
$javaVersion = @(& $java --version)
if ($LASTEXITCODE -ne 0 -or ([string]($javaVersion -join ' ')) -notmatch 'version "21\.|openjdk 21\.') {
  throw 'WIN11P STOP: JAVA_HOME must reference Java 21'
}
$maven = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
if (-not $maven) {
  throw 'WIN11P STOP: mvn.cmd is required for the source build'
}
$target = Join-Path $out ('AroNexa-Clinic-Portable-Java21-' + $sha.Substring(0,12))
if (Test-Path -LiteralPath $target) {
  throw ('WIN11P STOP: output already exists; do not overwrite ' + $target)
}
$staging = Join-Path $out ('.AroNexa-WIN11P-STAGING-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($staging)
try {
  if ($PackageOnlyUnverified) {
    Write-Warning 'WIN11P PACKAGE-ONLY: Maven tests are skipped. Prior test failures remain unresolved. NOT ACCEPTED / NOT FOR RELEASE.'
    & $maven.Source -B -f (Join-Path $repo 'backend\pom.xml') -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'WIN11P FAIL: package-only Maven compilation' }
  } else {
    Write-Host 'WIN11P: compile backend and execute its configured Maven tests'
    & $maven.Source -B -f (Join-Path $repo 'backend\pom.xml') clean verify
    if ($LASTEXITCODE -ne 0) { throw 'WIN11P FAIL: backend Maven verification' }
  }

  $jarFiles = @(Get-ChildItem -LiteralPath (Join-Path $repo 'backend\target') -File -Filter '*.jar' |
    Where-Object { $_.Name -notlike 'original-*' -and $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' })
  if ($jarFiles.Count -ne 1) {
    throw ('WIN11P STOP: expected exactly one runnable backend JAR, found ' + $jarFiles.Count)
  }

  $runtime = Join-Path $staging 'runtime'
  & $jlink --add-modules 'java.se,jdk.crypto.ec,jdk.unsupported,jdk.naming.dns' --strip-debug --no-header-files --no-man-pages --output $runtime
  if ($LASTEXITCODE -ne 0) {
    throw 'WIN11P FAIL: jlink bundled runtime'
  }
  & (Join-Path $runtime 'bin\java.exe') --version
  if ($LASTEXITCODE -ne 0) {
    throw 'WIN11P FAIL: bundled Java runtime startup'
  }

  [void][IO.Directory]::CreateDirectory((Join-Path $staging 'app'))
  Copy-Item -LiteralPath $jarFiles[0].FullName -Destination (Join-Path $staging 'app\aronexa-backend.jar')

  $launcher = @'
@echo off
setlocal
cd /d "%~dp0"
echo AroNexa Clinic Edition - INTERNAL QA ONLY - NOT FOR CLINIC USE
if not exist "%~dp0runtime\bin\java.exe" (
  echo ERROR: bundled Java runtime missing.
  exit /b 1
)
if not exist "%~dp0app\aronexa-backend.jar" (
  echo ERROR: application JAR missing.
  exit /b 1
)
if not defined SPRING_DATASOURCE_URL (
  echo ERROR: SPRING_DATASOURCE_URL not configured. No default clinical database allowed.
  exit /b 1
)
if not defined SPRING_DATASOURCE_USERNAME (
  echo ERROR: SPRING_DATASOURCE_USERNAME not configured.
  exit /b 1
)
if not defined SPRING_DATASOURCE_PASSWORD (
  echo ERROR: SPRING_DATASOURCE_PASSWORD not configured.
  exit /b 1
)
"%~dp0runtime\bin\java.exe" -jar "%~dp0app\aronexa-backend.jar" --server.address=127.0.0.1
exit /b %ERRORLEVEL%
'@
  Set-Content -LiteralPath (Join-Path $staging 'Start-AroNexa.cmd') -Value $launcher -Encoding Ascii

  $manifest = @(
    'WIN-11P PORTABLE JAVA BOOTSTRAP - NOT FOR CLINIC RELEASE',
    ('SourceCommit=' + $sha),
    ('GeneratedUTC=' + [DateTime]::UtcNow.ToString('o')),
    'Requires=Explicitly configured isolated PostgreSQL and authorized synthetic data',
    'No embedded PostgreSQL, migration rehearsal, full UI integration, licensing, or customer acceptance claimed',
    'Classification=NOT_READY',
    ('MavenGate=' + $(if ($PackageOnlyUnverified) {'SKIPPED_FAILED_QA_UNVERIFIED'} else {'VERIFY_ATTEMPTED_PASS'}))
  )
  Set-Content -LiteralPath (Join-Path $staging 'NOT-FOR-RELEASE.txt') -Value $manifest -Encoding UTF8
  Get-FileHash -LiteralPath (Join-Path $staging 'app\aronexa-backend.jar') -Algorithm SHA256 |
    Select-Object Algorithm,Hash,Path | Format-List | Out-String |
    Set-Content -LiteralPath (Join-Path $staging 'Backend-JAR-SHA256.txt') -Encoding UTF8

  Move-Item -LiteralPath $staging -Destination $target
  Write-Host ('WIN11P BOOTSTRAP CREATED (NOT RELEASE READY): ' + $target) -ForegroundColor Yellow
  if ($PackageOnlyUnverified) { Write-Warning 'NO TEST ACCEPTANCE: this output is a build-only diagnostic, not an approved pilot' }
  Write-Host ('SourceCommit=' + $sha)
} finally {
  if (Test-Path -LiteralPath $staging) {
    Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
  }
}
