# COMM-V1-T10: Windows app image containing actual Spring Boot/React build outputs.
# Does NOT start backend, database, serve patient API, create installer or deploy.
[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$RepoPath)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'Run only on disposable Windows CI or new disposable Windows VM.'
}
$repo = (Resolve-Path -LiteralPath $RepoPath).Path
foreach ($required in @('backend\pom.xml','frontend\package-lock.json',
                         'scripts\windows-clinic\ClinicBundleVerifier.java')) {
    if (-not (Test-Path -LiteralPath (Join-Path $repo $required) -PathType Leaf)) {
        throw "Expected source input missing: $required"
    }
}
foreach ($tool in @('mvn','java','javac','jar','jpackage','node','npm.cmd')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Disposable build VM missing development tool: $tool"
    }
}
$javaVersion = (& java --version | Select-Object -First 1).ToString()
if ($javaVersion -notmatch '(^|\s)21\.') {
    throw "JDK21 required on build VM: $javaVersion"
}
$workspace = Join-Path ([IO.Path]::GetTempPath()) (
    'AroNexa-Clinic-Package-Proof-' + [Guid]::NewGuid().ToString('N'))
$stage = Join-Path $workspace 'input'
$compiled = Join-Path $workspace 'classes'
$inputFrontend = Join-Path $stage 'frontend'
$launcherName = 'AroNexaClinicBundleProof'
New-Item -ItemType Directory -Path $stage,$compiled | Out-Null

Write-Host 'SYNTHETIC/BUILD-ONLY: pack application artifacts, no clinic runtime or DB.'
Write-Host "Ephemeral packaging workspace: $workspace"
Push-Location -LiteralPath $repo
try {
    & mvn -B -f (Join-Path $repo 'backend\pom.xml') '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'Backend Maven package failed' }
    Push-Location -LiteralPath (Join-Path $repo 'frontend')
    try {
        & npm.cmd ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Frontend npm clean install failed' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend TypeScript/Vite build failed' }
    } finally {
        Pop-Location
    }
    $backend = Join-Path $repo 'backend\target\aronexa-backend-0.1.0-SNAPSHOT.jar'
    $dist = Join-Path $repo 'frontend\dist'
    if (-not (Test-Path -LiteralPath $backend -PathType Leaf) -or
        -not (Test-Path -LiteralPath (Join-Path $dist 'index.html') -PathType Leaf)) {
        throw 'Spring Boot/React production artifacts not generated'
    }
    Copy-Item -LiteralPath $backend -Destination (Join-Path $stage 'aronexa-backend.jar')
    Copy-Item -LiteralPath $dist -Destination $inputFrontend -Recurse
    $javacArgs = @('--release','21','-encoding','UTF-8','-d',$compiled,
        (Join-Path $repo 'scripts\windows-clinic\ClinicBundleVerifier.java'))
    & javac @javacArgs
    if ($LASTEXITCODE -ne 0) { throw 'Bundle verifier Java compile failed' }
    $verifyJar = Join-Path $stage 'aronexa-clinic-bundle-verifier.jar'
    $jarArgs = @('--create','--file',$verifyJar,'--main-class',
        'ClinicBundleVerifier','-C',$compiled,'ClinicBundleVerifier.class')
    & jar @jarArgs
    if ($LASTEXITCODE -ne 0) { throw 'Verifier JAR build failed' }
    & java -jar $verifyJar
    if ($LASTEXITCODE -ne 0) { throw 'Staged artifact verifier failed' }
    $packageArgs = @('--type','app-image','--name',$launcherName,
        '--input',$stage,'--main-jar','aronexa-clinic-bundle-verifier.jar',
        '--main-class','ClinicBundleVerifier','--dest',$workspace,
        '--app-version','0.0.1','--win-console')
    & jpackage @packageArgs
    if ($LASTEXITCODE -ne 0) { throw 'Windows app-image packaging failed' }
    $launcher = Join-Path $workspace "$launcherName\$launcherName.exe"
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
        throw 'Packaged Windows launcher missing'
    }
    & $launcher
    if ($LASTEXITCODE -ne 0) { throw 'Bundled-runtime Windows executable self-test failed' }
    $hash = (Get-FileHash -LiteralPath $launcher -Algorithm SHA256).Hash
    Write-Host "PACKAGED CLINIC BUILD ARTIFACTS: PASS, Windows EXE SHA256 $hash"
    Write-Host 'No Spring Boot server started, no clinic Postgres, real patient or installer.'
    Write-Host 'Actual clinic UI on main is a foundation scaffold, NOT working OPD.'
    Write-Host 'THIS IS AN APP-IMAGE PROOF, NOT A CUSTOMER INSTALLER.'
} finally {
    Pop-Location
}
