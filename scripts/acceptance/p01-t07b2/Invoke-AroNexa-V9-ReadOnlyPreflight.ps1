# P01-T07-B2-MIG: read-only Windows PowerShell 5.1 preflight, not Apply.
# NO backups, DDL, role provisioning, original migration or Docker container changes.
[CmdletBinding()]
param(
    [string]$RepoPath = (Get-Location).Path,
    [switch]$ConfirmSyntheticDevelopmentOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Stop-Gate([string]$Message) {
    throw ("STOP: " + $Message)
}
function Invoke-External([string]$FileName, [string[]]$Arguments) {
    $output = & $FileName @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        Stop-Gate ("Command failed; review locally: " + $FileName)
    }
    return (($output | Out-String).Trim())
}
if (-not $ConfirmSyntheticDevelopmentOnly) {
    Stop-Gate 'Supply -ConfirmSyntheticDevelopmentOnly. NEVER target real patient/customer data.'
}
$root = Invoke-External 'git.exe' @('-C', $RepoPath, 'rev-parse', '--show-toplevel')
if ([string]::IsNullOrWhiteSpace($root) -or -not (Test-Path -LiteralPath $root -PathType Container)) {
    Stop-Gate 'Not an accessible Git repository.'
}
$branch = Invoke-External 'git.exe' @('-C', $root, 'branch', '--show-current')
if ($branch -ne 'feature/p01-t07b-patient-onboarding') {
    Stop-Gate 'Wrong branch; do not switch or update repository automatically.'
}
$dirty = Invoke-External 'git.exe' @('-C', $root, 'status', '--porcelain')
if (-not [string]::IsNullOrWhiteSpace($dirty)) {
    Stop-Gate 'Working tree is not clean. Do not reset/discard changes.'
}
$commit = Invoke-External 'git.exe' @('-C', $root, 'rev-parse', 'HEAD')
$migration = Join-Path $root 'backend\src\main\resources\db\migration\V9__patient_registration_read_boundary.sql'
if (-not (Test-Path -LiteralPath $migration -PathType Leaf)) {
    Stop-Gate 'Exact V9 source file is missing.'
}
$blob = Invoke-External 'git.exe' @('-C', $root, 'rev-parse', 'HEAD:backend/src/main/resources/db/migration/V9__patient_registration_read_boundary.sql')
if ($blob -ne '3a1e2b717576ccd84f0de058bb0a83786c517d32') {
    Stop-Gate 'V9 content differs from reviewed migration blob. Stop for code review.'
}
$migrationSha = (Get-FileHash -LiteralPath $migration -Algorithm SHA256).Hash

# Inspect only the existing Compose source container. No implicit compose up,
# docker pull, database startup, Flyway, drop, clean or migration.
$raw = Invoke-External 'docker.exe' @(
    'ps', '--filter', 'label=com.docker.compose.project=aronexa-dev',
    '--filter', 'label=com.docker.compose.service=postgres', '--format', '{{.ID}}'
)
$containers = @($raw -split '\r?\n' | Where-Object { $_ -match '^[0-9a-f]{12,64}$' })
if ($containers.Count -ne 1) {
    Stop-Gate 'Expected exactly one running aronexa-dev PostgreSQL container.'
}
$sourceId = $containers[0]
function Read-Db([string]$Sql) {
    return (Invoke-External 'docker.exe' @(
        'exec', $sourceId, 'psql', '-X', '-At', '-v', 'ON_ERROR_STOP=1',
        '-U', 'aronexa_dev', '-d', 'aronexa_dev', '-c', $Sql
    ))
}
$name = Read-Db 'SELECT current_database()'
if ($name -ne 'aronexa_dev') { Stop-Gate 'Unexpected database name.' }
$version = Read-Db 'SHOW server_version'
if (-not $version.StartsWith('17.')) { Stop-Gate 'Expected PostgreSQL major version 17.' }
$flyway = Read-Db 'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1'
if ($flyway -ne '8') {
    Stop-Gate 'Original database is not exactly Flyway V8. Do not attempt V9.'
}
$failures = Read-Db 'SELECT count(*) FROM public.flyway_schema_history WHERE NOT success'
if ($failures -ne '0') { Stop-Gate 'Unsuccessful Flyway history rows detected.' }
$roleCount = Read-Db "SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname IN ('aronexa_app_runtime','aronexa_auth_runtime') AND rolcanlogin AND NOT rolsuper AND NOT rolbypassrls"
if ($roleCount -ne '2') { Stop-Gate 'Existing app/auth roles are missing or privileged.' }
$patientRole = Read-Db "SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname='aronexa_patient_runtime'"
if ($patientRole -ne '0') {
    Stop-Gate 'Patient runtime already exists in original V8 DB; inspect its provenance before migration.'
}
$names = @('patient','patient_identifier','patient_contact','patient_relationship','patient_registration','patient_identity_audit')
foreach ($table in $names) {
    $rows = Read-Db ('SELECT count(*) FROM public.' + $table)
    if ($rows -ne '0') {
        Stop-Gate ('Nonempty patient identity table (' + $table + '). Stop to verify synthetic-only status and privacy.')
    }
}
Write-Host ('REPO_SHA: ' + $commit)
Write-Host ('V9_GIT_BLOB_SHA: ' + $blob)
Write-Host ('V9_SHA256: ' + $migrationSha)
Write-Host ('SOURCE_CONTAINER_ID: ' + $sourceId)
Write-Host ('POSTGRES_VERSION: ' + $version)
Write-Host 'FLYWAY_CURRENT_VERSION: 8'
Write-Host 'PATIENT_IDENTITY_TABLES_EMPTY: 6/6'
Write-Host 'EXISTING_RESTRICTED_ROLES: PASS'
Write-Host 'PATIENT_ROLE_PRESENT_IN_ORIGINAL: NO'
Write-Host 'BACKUP_VERIFIED: NO'
Write-Host 'REHEARSAL_EXECUTED: NO'
Write-Host 'ORIGINAL_DATABASE_MODIFIED: NO'
Write-Host 'FINAL_STATUS: READ_ONLY_V8_PREFLIGHT_PASSED; BACKUP_AND_REHEARSAL_STILL_REQUIRED'
