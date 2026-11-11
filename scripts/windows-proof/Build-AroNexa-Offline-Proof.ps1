# COMM-V1-T09 — SYNTHETIC-ONLY Windows app-image packaging proof.
# NOT a commercial installer or clinical application. Never touches patient DB.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$RepoPath
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'This jpackage app-image probe must run on a disposable Windows machine.'
}
$repo = (Resolve-Path -LiteralPath $RepoPath).Path
$pom = Join-Path $repo 'backend\pom.xml'
$classes = Join-Path $repo 'backend\target\classes'
if (-not (Test-Path -LiteralPath $pom -PathType Leaf)) {
    throw 'Not an AroNexa checkout: backend\pom.xml is missing.'
}
foreach ($tool in @('java','mvn','jar','jpackage')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "JDK 21 and Maven 3.9+ are required on the BUILD machine; missing $tool."
    }
}
$javaVersion = (& java --version | Select-Object -First 1).ToString()
if ($javaVersion -notmatch '"21\.') {
    throw "This proof requires JDK 21, received: $javaVersion"
}

$work = Join-Path ([IO.Path]::GetTempPath()) (
    'AroNexaOfflineProof-' + [Guid]::NewGuid().ToString('N'))
$inputFolder = Join-Path $work 'input'
New-Item -ItemType Directory -Path $inputFolder -Force | Out-Null
$jarPath = Join-Path $inputFolder 'aronexa-offline-proof.jar'
$packageName = 'AroNexaOfflineProof'

Write-Host 'SYNTHETIC-ONLY offline app-image proof. No database or patient data.'
Write-Host "Temporary build location: $work"
Push-Location -LiteralPath $repo
try {
    & mvn -B -f $pom '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw "Maven source compile failed: $LASTEXITCODE" }
    if (-not (Test-Path -LiteralPath (Join-Path $classes 'com\argdigital\aronexa\offlineproof\OfflineProofDemo.class'))) {
        throw 'Offline proof entrypoint was not compiled.'
    }
    # Include ONLY pure JDK proof classes. No Spring, DB, secrets or issuer private key.
    $jarArgs = @('--create', '--file', $jarPath, '--main-class',
        'com.argdigital.aronexa.offlineproof.OfflineProofDemo',
        '-C', $classes, 'com/argdigital/aronexa/offlineproof')
    & jar @jarArgs
    if ($LASTEXITCODE -ne 0) { throw "JAR creation failed: $LASTEXITCODE" }

    & java -jar $jarPath
    if ($LASTEXITCODE -ne 0) { throw "Synthetic proof command failed: $LASTEXITCODE" }

    # App-image bundles a Java runtime and launcher, NOT a Windows installer.
    $packageArgs = @('--type', 'app-image', '--name', $packageName,
        '--input', $inputFolder, '--main-jar', 'aronexa-offline-proof.jar',
        '--main-class', 'com.argdigital.aronexa.offlineproof.OfflineProofDemo',
        '--dest', $work, '--app-version', '0.0.1', '--win-console')
    & jpackage @packageArgs
    if ($LASTEXITCODE -ne 0) { throw "jpackage app-image failed: $LASTEXITCODE" }
    $launcher = Join-Path $work "$packageName\$packageName.exe"
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
        throw "Missing generated proof launcher: $launcher"
    }
    Write-Host "APP IMAGE GENERATED, LAUNCHER: $launcher"
    Write-Host 'NEXT: run this launcher on an offline DISPOSABLE Windows VM.'
    Write-Host 'NOT IMPLEMENTED: installer, local PG, DB recovery, entitlement enforcement, clinic wizard or signing.'
    Write-Host 'The original Windows database was not accessed.'
} finally {
    Pop-Location
}
