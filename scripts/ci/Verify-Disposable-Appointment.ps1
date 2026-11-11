# COMM-V1-T14: optional disposable Windows VM acceptance when GitHub has no runners.
# Do NOT run on the owner's original Windows application/database.
[CmdletBinding()]
param([switch]$DisposableVMConfirmed, [string]$ExpectedCommit = '')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $DisposableVMConfirmed -or $env:ARONEXA_DISPOSABLE_QA_VM -ne 'YES') {
    throw 'REFUSED: requires fresh disposable QA VM and explicit confirmation.'
}
foreach ($key in @('ARONEXA_DB_URL','ARONEXA_AUTH_DB_URL',
    'ARONEXA_PATIENT_DB_URL','ARONEXA_DB_PASSWORD','DATABASE_URL','PGHOST')) {
    if ([Environment]::GetEnvironmentVariable($key)) {
        throw "REFUSED: preexisting database configuration $key."
    }
}
foreach ($tool in @('git','mvn','java','docker')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Disposable VM prerequisite missing: $tool"
    }
}
$repoRoot = (& git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0 -or -not $repoRoot) {
    throw 'Expected Git checkout on disposable VM.'
}
$branchName = (& git -C $repoRoot branch --show-current).Trim()
if ($LASTEXITCODE -ne 0 -or $branchName -ne 'integration/clinic-opd-v1-20261111') {
    throw 'Wrong branch: expected the integrated OPD V1 development branch.'
}
$commit = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') {
    throw 'Cannot determine exact source SHA.'
}
if ($ExpectedCommit -and $commit -ne $ExpectedCommit) {
    throw 'Checked-out source differs from the expected PR commit.'
}
$dirty = (& git -C $repoRoot status --porcelain)
if ($LASTEXITCODE -ne 0 -or $dirty) {
    throw 'Expected a clean checkout for exact-source provenance.'
}
$dockerEndpoint = (& docker context inspect --format '{{.Endpoints.docker.Host}}').Trim()
if ($LASTEXITCODE -ne 0 -or $dockerEndpoint -notmatch '^npipe://') {
    throw 'REFUSED: only the local Windows Docker named pipe is allowed; no remote engine.'
}
$javaVersion = (& java -version 2>&1 | Select-Object -First 1).ToString()
if ($javaVersion -notmatch '21[.]') { throw "JDK 21 required: $javaVersion" }
& docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Local disposable Docker engine not available.' }

Write-Host "Exact source: $commit"
Write-Host 'Testcontainers creates and removes NEW synthetic PostgreSQL instances.'
Write-Host 'No existing database, Windows migration or real patient information is used.'
$report = Join-Path $repoRoot 'backend\target\surefire-reports'
$mavenArgs = @('-B','-f',(Join-Path $repoRoot 'backend\pom.xml'),
               '-Dtest=AppointmentPersistenceProofTests',
               '-DfailIfNoTests=true','test')
& mvn @mavenArgs
if ($LASTEXITCODE -ne 0) {
    throw 'Appointment Testcontainers acceptance FAILED; inspect Maven logs.'
}
$files = @(Get-ChildItem -LiteralPath $report -Filter 'TEST-*AppointmentPersistenceProofTests.xml' -ErrorAction Stop)
if ($files.Count -ne 1) { throw 'Expected exactly one matching JUnit report.' }
[xml]$xml = Get-Content -LiteralPath $files[0].FullName -Raw
$suite = $xml.testsuite
if ($null -eq $suite) { throw 'JUnit report unavailable.' }
$tests = [int]$suite.tests
$errors = [int]$suite.errors
$failures = [int]$suite.failures
$skipped = [int]$suite.skipped
if ($tests -lt 18 -or $errors -ne 0 -or $failures -ne 0 -or $skipped -ne 0) {
    throw "Test suite not accepted: $tests tests / $errors errors / $failures failures / $skipped skipped."
}
Write-Host "DISPOSABLE-ONLY APPOINTMENT PASS: $tests tests; source $commit"
Write-Host 'Local QA does not replace GitHub CI, security review or release.'
