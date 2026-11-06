# W2B read-only SCRAM/credential diagnostic. No Flyway migrate, role creation or
# original database changes. Windows PowerShell 5.1, review before execution.
[CmdletBinding()]
param(
    [string]$RepoPath=(Get-Location).Path,
    [Parameter(Mandatory=$true)][string]$RehearsalContainer,
    [Parameter(Mandatory=$true)][string]$RehearsalNetwork,
    [switch]$ConfirmSyntheticDisposableOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
function Stop-Gate([string]$message) { throw ("STOP: "+$message) }
function Invoke-Native([string]$exe,[string[]]$arguments) {
    $result=& $exe @arguments
    if ($LASTEXITCODE -ne 0) { Stop-Gate ("Native command failed: "+$exe) }
    return (($result | Out-String).Trim())
}
if (-not $ConfirmSyntheticDisposableOnly) { Stop-Gate 'Explicit synthetic disposable confirmation missing' }
if ($RehearsalContainer -notmatch '^aronexa-w2-pg17-(\d{8}_\d{6}_[a-fA-F0-9]{8})$') {
    Stop-Gate 'Unexpected disposable container name'
}
$id=$Matches[1]
if ($RehearsalNetwork -ne ('aronexa-w2-v9-'+$id)) { Stop-Gate 'Container-network pair mismatch' }
$repo=Invoke-Native 'git.exe' @('-C',$RepoPath,'rev-parse','--show-toplevel')
if ((Invoke-Native 'git.exe' @('-C',$repo,'branch','--show-current')) -ne 'feature/p01-t07b-patient-onboarding') {
    Stop-Gate 'Incorrect reviewed branch'
}
if (Invoke-Native 'git.exe' @('-C',$repo,'status','--porcelain')) { Stop-Gate 'Working tree not clean' }
$v9=Invoke-Native 'git.exe' @('-C',$repo,'rev-parse','HEAD:backend/src/main/resources/db/migration/V9__patient_registration_read_boundary.sql')
if ($v9 -ne '3a1e2b717576ccd84f0de058bb0a83786c517d32') {
    Stop-Gate 'V9 blob differs from reviewed implementation'
}
$sourceIds=Invoke-Native 'docker.exe' @('ps',
    '--filter','label=com.docker.compose.project=aronexa-dev',
    '--filter','label=com.docker.compose.service=postgres','--format','{{.ID}}')
$sources=@($sourceIds -split '\r?\n' | Where-Object { $_ -match '^[0-9a-f]{12,64}$' })
if ($sources.Count -ne 1) { Stop-Gate 'Original PostgreSQL source ambiguous' }
$sourceFull=Invoke-Native 'docker.exe' @('inspect','--format','{{.Id}}',$sources[0])
$targetFull=Invoke-Native 'docker.exe' @('inspect','--format','{{.Id}}',$RehearsalContainer)
if ($sourceFull -eq $targetFull) { Stop-Gate 'Refuse original database target' }
if ((Invoke-Native 'docker.exe' @('inspect','--format','{{.State.Running}}',$RehearsalContainer)) -ne 'true') {
    Stop-Gate 'Disposable PostgreSQL not running'
}
if ((Invoke-Native 'docker.exe' @('network','inspect','--format','{{.Internal}}',$RehearsalNetwork)) -ne 'true') {
    Stop-Gate 'Network not isolated/internal'
}
$portBindings=Invoke-Native 'docker.exe' @('inspect','--format','{{json .HostConfig.PortBindings}}',$RehearsalContainer)
if ($portBindings -ne 'null' -and $portBindings -ne '{}') { Stop-Gate 'Rehearsal has published ports' }
$mounts=Invoke-Native 'docker.exe' @('inspect','--format','{{json .Mounts}}',$RehearsalContainer)
if ($mounts -match 'aronexa_dev_postgres_data') { Stop-Gate 'Original volume detected' }
$netJson=Invoke-Native 'docker.exe' @('inspect','--format','{{json .NetworkSettings.Networks}}',$RehearsalContainer)
$net=$netJson | ConvertFrom-Json
$names=@($net.PSObject.Properties.Name)
if ($names.Count -ne 1 -or $names[0] -ne $RehearsalNetwork) {
    Stop-Gate 'Rehearsal is attached to an unexpected network'
}
function Version([string]$container) {
    return (Invoke-Native 'docker.exe' @('exec',$container,'psql','-X','-At',
      '-v','ON_ERROR_STOP=1','-U','aronexa_dev','-d','aronexa_dev','-c',
      'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1'))
}
if ((Version $sources[0]) -ne '8') { Stop-Gate 'Original not V8' }
if ((Version $RehearsalContainer) -ne '8') { Stop-Gate 'Disposable not V8' }
$roleCount=Invoke-Native 'docker.exe' @('exec',$RehearsalContainer,'psql','-X','-At',
    '-v','ON_ERROR_STOP=1','-U','aronexa_dev','-d','aronexa_dev','-c',
    "SELECT count(*) FROM pg_roles WHERE rolname='aronexa_patient_runtime' AND rolcanlogin AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit")
if ($roleCount -ne '1') { Stop-Gate 'Disposable patient runtime role state invalid' }
$vars=Invoke-Native 'docker.exe' @('inspect','--format','{{range .Config.Env}}{{println .}}{{end}}',$RehearsalContainer)
$pwEntries=@($vars -split '\r?\n' | Where-Object { $_ -match '^POSTGRES_PASSWORD=' })
if ($pwEntries.Count -ne 1) { Stop-Gate 'Disposable password entry missing or duplicated' }
$pw=$pwEntries[0].Substring('POSTGRES_PASSWORD='.Length)
if ([string]::IsNullOrWhiteSpace($pw)) { Stop-Gate 'Disposable database password is empty' }
$dir=Join-Path $repo 'backend\src\main\resources\db\migration'
if (-not (Test-Path -LiteralPath $dir -PathType Container)) { Stop-Gate 'Migration folder missing' }

# Inherit environment variables from this *PowerShell subprocess*, not an
# intermediate .env file. Never echo the password or dump environment values.
$oldUrl=[Environment]::GetEnvironmentVariable('FLYWAY_URL','Process')
$oldUser=[Environment]::GetEnvironmentVariable('FLYWAY_USER','Process')
$oldPwd=[Environment]::GetEnvironmentVariable('FLYWAY_PASSWORD','Process')
$oldLocations=[Environment]::GetEnvironmentVariable('FLYWAY_LOCATIONS','Process')
try {
    $env:FLYWAY_URL='jdbc:postgresql://w2-db:5432/aronexa_dev'
    $env:FLYWAY_USER='aronexa_dev'
    $env:FLYWAY_PASSWORD=$pw
    $env:FLYWAY_LOCATIONS='filesystem:/flyway/sql'
    $pw=$null
    $vars=$null
    $pwEntries=$null
    $dockerArgs=@('run','--rm','--network',$RehearsalNetwork,
      '--env','FLYWAY_URL','--env','FLYWAY_USER','--env','FLYWAY_PASSWORD',
      '--env','FLYWAY_LOCATIONS',
      '--mount',('type=bind,source='+$dir+',target=/flyway/sql,readonly'),
      'flyway/flyway:13.12.0','info')
    # Flyway INFO does not migrate. Authentication failure stops with nonzero exit.
    [void](Invoke-Native 'docker.exe' $dockerArgs)
    Write-Host 'ORIGINAL_FLYWAY_VERSION: 8'
    Write-Host 'W2_FLYWAY_VERSION: 8'
    Write-Host 'W2_PATIENT_ROLE_COUNT: 1'
    Write-Host 'DISPOSABLE_FLYWAY_INFO_AUTH: PASS'
    Write-Host 'V9_APPLIED: NO'
    Write-Host 'FINAL_STATUS: W2B_FLYWAY_READ_ONLY_AUTH_CHECK_PASS'
}
finally {
    [Environment]::SetEnvironmentVariable('FLYWAY_URL',$oldUrl,'Process')
    [Environment]::SetEnvironmentVariable('FLYWAY_USER',$oldUser,'Process')
    [Environment]::SetEnvironmentVariable('FLYWAY_PASSWORD',$oldPwd,'Process')
    [Environment]::SetEnvironmentVariable('FLYWAY_LOCATIONS',$oldLocations,'Process')
    $pw=$null
}
