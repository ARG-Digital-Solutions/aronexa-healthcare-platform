# WIN-11P package-only preflight. Never starts backend, Docker, or a database.
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)]
  [string]$PortableDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$portable = (Resolve-Path -LiteralPath $PortableDirectory -ErrorAction Stop).Path
$required = @('Start-AroNexa.cmd','app\aronexa-backend.jar','runtime\bin\java.exe',
              'NOT-FOR-RELEASE.txt','Backend-JAR-SHA256.txt')
foreach ($relative in $required) {
  $p = Join-Path $portable $relative
  if (-not (Test-Path -LiteralPath $p -PathType Leaf)) {
    throw ('WIN11P PREFLIGHT FAIL: missing ' + $relative)
  }
  Write-Host ('PASS: ' + $relative)
}
$manifest = @(Get-Content -LiteralPath (Join-Path $portable 'NOT-FOR-RELEASE.txt'))
if (-not ($manifest -contains 'Classification=NOT_READY')) {
  throw 'WIN11P PREFLIGHT FAIL: missing NOT_READY release classification'
}
if (-not (@($manifest | Where-Object { $_ -match '^MavenGate=(SKIPPED_FAILED_QA_UNVERIFIED|VERIFY_ATTEMPTED_PASS)$' }).Count -eq 1)) {
  throw 'WIN11P PREFLIGHT FAIL: missing/unexpected MavenGate marker'
}
$sha = @($manifest | Where-Object { $_ -match '^SourceCommit=[0-9a-fA-F]{40}$' })
if ($sha.Count -ne 1) { throw 'WIN11P PREFLIGHT FAIL: missing exact source commit' }

$recorded = Get-Content -LiteralPath (Join-Path $portable 'Backend-JAR-SHA256.txt') -Raw
$jar = Join-Path $portable 'app\aronexa-backend.jar'
$actual = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
if ($recorded -notmatch ('(?i)\b' + [regex]::Escape($actual) + '\b')) {
  throw 'WIN11P PREFLIGHT FAIL: executable JAR checksum mismatch'
}
Write-Host ('PASS: JAR SHA256=' + $actual)

Add-Type -AssemblyName System.IO.Compression
$archive = [IO.Compression.ZipFile]::OpenRead($jar)
try {
  $entries = @($archive.Entries | ForEach-Object { $_.FullName })
  foreach ($entry in @('org/springframework/boot/loader/launch/JarLauncher.class',
                       'BOOT-INF/classes/','BOOT-INF/lib/')) {
    if ($entry -eq 'org/springframework/boot/loader/launch/JarLauncher.class') {
      if (-not ($entries -contains $entry)) {
        throw ('WIN11P PREFLIGHT FAIL: boot launcher entry missing ' + $entry)
      }
    } else {
      if (-not @($entries | Where-Object { $_.StartsWith($entry,[StringComparison]::Ordinal) }).Count) {
        throw ('WIN11P PREFLIGHT FAIL: missing Spring Boot archive section ' + $entry)
      }
    }
  }
} finally {
  $archive.Dispose()
}
Write-Host 'PASS: Spring Boot executable JAR layout'

$versionOutput = @(& (Join-Path $portable 'runtime\bin\java.exe') --version)
if ($LASTEXITCODE -ne 0 -or ($versionOutput -join ' ') -notmatch '^openjdk 21\.|^java 21\.') {
  throw 'WIN11P PREFLIGHT FAIL: bundled runtime is not Java 21'
}
Write-Host ('PASS: Java 21 runtime (' + $versionOutput[0] + ')')
$launcher = Get-Content -LiteralPath (Join-Path $portable 'Start-AroNexa.cmd') -Raw
foreach ($phrase in @('runtime\bin\java.exe','app\aronexa-backend.jar',
                      'SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
                      'SPRING_DATASOURCE_PASSWORD','--server.address=127.0.0.1')) {
  if (-not $launcher.Contains($phrase)) {
    throw ('WIN11P PREFLIGHT FAIL: missing launcher contract ' + $phrase)
  }
}
Write-Host 'PASS: launcher has explicit database prerequisites and loopback binding'
Write-Warning 'PREFLIGHT ONLY: does not prove database isolation, backend startup, clinical functions, Maven tests or release readiness'
Write-Host ('Source: ' + $sha[0])
