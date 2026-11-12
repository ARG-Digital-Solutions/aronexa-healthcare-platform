# R1-04 isolated appointment booking/check-in acceptance.
# Only disposable Testcontainers PG17; NEVER installs on customer/Windows DB.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^[a-f0-9]{40}$')]
    [string]$ExpectedCommit
)
Set-StrictMode -Version Latest
$ErrorActionPreference="Stop"
$repo=(Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$sha=(& git -C $repo rev-parse HEAD).Trim()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit){
    throw "R1-04 source SHA mismatch: use an isolated exact-commit worktree."
}
$pom=Join-Path $repo "backend\pom.xml"
$logRoot=Join-Path $env:USERPROFILE "AroNexa-Local-QA"
$evidence=Join-Path $logRoot ((Get-Date -Format "yyyyMMdd-HHmmss")+
    "-r104-"+$sha.Substring(0,10))
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$focusedLog=Join-Path $evidence "r104-appointment-http-postgres.log"
Write-Host "R1-04 PostgreSQL test-only appointment booking/check-in" -ForegroundColor Cyan
Write-Host "SourceCommit=$sha"
Write-Host "Evidence=$evidence"
Push-Location $repo
try {
    $old=$ErrorActionPreference
    try {
        $ErrorActionPreference="Continue"
        & mvn.cmd -B -f $pom "-Dtest=AppointmentCandidateHttpAcceptanceTests" test *> $focusedLog
        $code=$LASTEXITCODE
    } finally {
        $ErrorActionPreference=$old
    }
    if($code -ne 0){
        Write-Host "FAILED: R1-04 real HTTP + disposable PostgreSQL" -ForegroundColor Red
        Get-Content -LiteralPath $focusedLog -Tail 70
        throw "R1-04 appointment acceptance not established."
    }
    Write-Host "PASSED: R1-04 real HTTP + disposable PostgreSQL" -ForegroundColor Green
    # Reuse the established exact-SHA frontend+Node wire+full backend gate.
    & powershell.exe -NoProfile -File (Join-Path $repo "scripts\Test-Opd-Registration-Increment.ps1") -ExpectedCommit $sha
    if($LASTEXITCODE -ne 0){
        throw "Full OPD registration/appointment regression failed."
    }
} finally {
    Pop-Location
}
Write-Host "R1-04 CANDIDATE ACCEPTANCE PASSED (TEST ONLY)" -ForegroundColor Green
Write-Host "Focused log: $focusedLog"
Write-Host "No production migration, merge, deployment or clinical authorization."
