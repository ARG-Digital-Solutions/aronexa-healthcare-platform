# P01-T07-B2 W2A: restore verified V8 dump into a NEW isolated disposable PG17.
# STOP before V9 / Flyway. NEVER connect to source to write.
# Operator must review this script before running. Windows PowerShell 5.1.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$BackupFile,
    [Parameter(Mandatory=$true)][string]$CountsFile,
    [Parameter(Mandatory=$true)][string]$SourceDump,
    [string]$RepoPath = (Get-Location).Path,
    [switch]$ConfirmSyntheticDevelopmentOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
function Stop-Gate([string]$message) { throw "STOP: $message" }
function Exec([string]$cmd,[string[]]$argv) {
    # Do not merge native stderr with PowerShell error records or print passwords.
    $r = & $cmd @argv
    if ($LASTEXITCODE -ne 0) { Stop-Gate ("Native command failed: " + $cmd) }
    return (($r | Out-String).Trim())
}
if (-not $ConfirmSyntheticDevelopmentOnly) { Stop-Gate 'Confirm synthetic development data only' }
$repo = Exec 'git.exe' @('-C',$RepoPath,'rev-parse','--show-toplevel')
$branch = Exec 'git.exe' @('-C',$repo,'branch','--show-current')
if ($branch -ne 'feature/p01-t07b-patient-onboarding') { Stop-Gate 'Wrong Git branch' }
$dirty = Exec 'git.exe' @('-C',$repo,'status','--porcelain')
if ($dirty) { Stop-Gate 'Uncommitted or untracked files; use a clean review checkout' }
$sha = Exec 'git.exe' @('-C',$repo,'rev-parse','HEAD')
$v9blob = Exec 'git.exe' @('-C',$repo,'rev-parse','HEAD:backend/src/main/resources/db/migration/V9__patient_registration_read_boundary.sql')
if ($v9blob -ne '3a1e2b717576ccd84f0de058bb0a83786c517d32') {
    Stop-Gate 'V9 source differs from approved B2 design'
}
if ($SourceDump -notmatch '^/tmp/p01_t07b2_v8_\d{8}_\d{6}_[0-9a-fA-F]{8}\.dump$') {
    Stop-Gate 'Unrecognized source dump name'
}
if (-not (Test-Path -LiteralPath $BackupFile -PathType Leaf)) { Stop-Gate 'Private backup missing' }
if (-not (Test-Path -LiteralPath $CountsFile -PathType Leaf)) { Stop-Gate '25-table evidence missing' }
$bytes=(Get-Item -LiteralPath $BackupFile).Length
if ($bytes -le 0) { Stop-Gate 'Backup file empty' }
$backupHash=(Get-FileHash -LiteralPath $BackupFile -Algorithm SHA256).Hash.ToUpperInvariant()
$rows=@(Import-Csv -LiteralPath $CountsFile)
if ($rows.Count -ne 25) { Stop-Gate ('Expected 25 source table rows, found '+$rows.Count) }
$seen=@{}
foreach ($item in $rows) {
    $t=$item.Table; $n=$item.Count
    if (-not $t -or $t -notmatch '^[a-z_][a-z0-9_]*$' -or $seen.ContainsKey($t)) { Stop-Gate 'Invalid or duplicate table in CSV' }
    if ($null -eq $n -or $n -notmatch '^\d+$') { Stop-Gate ('Invalid count for '+$t) }
    $seen[$t]=[long]$n
}
$sourceIds=Exec 'docker.exe' @('ps','--filter','label=com.docker.compose.project=aronexa-dev',
    '--filter','label=com.docker.compose.service=postgres','--format','{{.ID}}')
$sourceContainers=@($sourceIds -split '\r?\n' | Where-Object { $_ -match '^[0-9a-f]{12,64}$' })
if ($sourceContainers.Count -ne 1) { Stop-Gate 'Original source PostgreSQL container is not unique' }
$src=$sourceContainers[0]
$srcVersion=Exec 'docker.exe' @('exec',$src,'psql','-X','-At','-v','ON_ERROR_STOP=1',
    '-U','aronexa_dev','-d','aronexa_dev','-c',
    'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1')
if ($srcVersion -ne '8') { Stop-Gate 'Original source not V8' }
$sourceSize=Exec 'docker.exe' @('exec',$src,'stat','-c','%s','--',$SourceDump)
if ($sourceSize -notmatch '^\d+$' -or [long]$sourceSize -ne $bytes) {
    Stop-Gate 'Backup source copy bytes differ'
}
$sourceHashLine=Exec 'docker.exe' @('exec',$src,'sha256sum','--',$SourceDump)
if ($sourceHashLine -notmatch '^([a-fA-F0-9]{64})\s') { Stop-Gate 'Source SHA not recognized' }
if ($Matches[1].ToUpperInvariant() -ne $backupHash) { Stop-Gate 'Docker and Windows SHA256 differ' }
# Verify local source archive content through source container's PG17 client; read only.
[void](Exec 'docker.exe' @('exec',$src,'pg_restore','--list',$SourceDump))

$id=(Get-Date -Format 'yyyyMMdd_HHmmss')+'_'+[guid]::NewGuid().ToString('N').Substring(0,8)
$network='aronexa-w2-v9-'+$id
$rehearsal='aronexa-w2-pg17-'+$id
$label='aronexa.w2.id='+$id

# These exact commands create new resources only; no Compose command or source mounts.
[void](Exec 'docker.exe' @('network','create','--internal','--label',$label,$network))
# A fresh, random, disposable credential stays inside Docker; never logged.
$pass=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
[void](Exec 'docker.exe' @('run','--detach','--name',$rehearsal,
    '--label',$label,'--network',$network,'--network-alias','w2-db',
    '--tmpfs','/var/lib/postgresql/data:rw,size=536870912',
    '--env','POSTGRES_DB=aronexa_dev','--env','POSTGRES_USER=aronexa_dev',
    '--env',('POSTGRES_PASSWORD='+$pass),'postgres:17-alpine'))
$pass=$null

# Confirm this container is NOT the source and owns no original volume mount.
$restoredId=Exec 'docker.exe' @('inspect','--format','{{.Id}}',$rehearsal)
$sourceFullId=Exec 'docker.exe' @('inspect','--format','{{.Id}}',$src)
if ($restoredId -eq $sourceFullId) { Stop-Gate 'Refusing source container' }
$mounts=Exec 'docker.exe' @('inspect','--format','{{json .Mounts}}',$rehearsal)
if ($mounts -match 'aronexa_dev_postgres_data') { Stop-Gate 'Disposable container attached original volume' }
$ready=$false
for ($i=0;$i -lt 60;$i++) {
    & docker.exe exec $rehearsal pg_isready -U aronexa_dev -d aronexa_dev *> $null
    if ($LASTEXITCODE -eq 0) { $ready=$true; break }
    Start-Sleep -Seconds 2
}
if (-not $ready) { Stop-Gate 'Disposable PostgreSQL did not become ready' }
$pgVersion=Exec 'docker.exe' @('exec',$rehearsal,'psql','-X','-At','-U','aronexa_dev',
    '-d','aronexa_dev','-c','SHOW server_version')
if (-not $pgVersion.StartsWith('17.')) { Stop-Gate 'Disposable database not PostgreSQL 17' }

# Create existing V8 application/authentication role NAMES, with distinct random
# passwords held only by the ephemeral database. The dump contains ACLs for them.
$roleBootstrap=@'
DO $bootstrap$
BEGIN
EXECUTE format(
  'CREATE ROLE aronexa_app_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD %L',
  gen_random_uuid()::text || gen_random_uuid()::text
);
EXECUTE format(
  'CREATE ROLE aronexa_auth_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD %L',
  gen_random_uuid()::text || gen_random_uuid()::text
);
END
$bootstrap$;
'@
$roleBootstrap | & docker.exe exec -i $rehearsal psql -X -v ON_ERROR_STOP=1 -U aronexa_dev -d aronexa_dev
if ($LASTEXITCODE -ne 0) { Stop-Gate 'Disposable V8 role bootstrap failed' }

[void](Exec 'docker.exe' @('cp',$BackupFile,($rehearsal+':/tmp/source-v8.dump')))
[void](Exec 'docker.exe' @('exec',$rehearsal,'pg_restore','--exit-on-error',
    '-U','aronexa_dev','-d','aronexa_dev','/tmp/source-v8.dump'))

function Query([string]$sql) {
    return (Exec 'docker.exe' @('exec',$rehearsal,'psql','-X','-At',
      '-v','ON_ERROR_STOP=1','-U','aronexa_dev','-d','aronexa_dev','-c',$sql))
}
$v8=Query 'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1'
if ($v8 -ne '8') { Stop-Gate 'Restored database is not Flyway V8' }
$bad=Query 'SELECT count(*) FROM public.flyway_schema_history WHERE NOT success'
if ($bad -ne '0') { Stop-Gate 'Restored database has Flyway failures' }
$restoredTables=Query "SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname='public'"
if ($restoredTables -ne '25') { Stop-Gate ('Restored table inventory differs: '+$restoredTables) }
foreach ($table in ($seen.Keys | Sort-Object)) {
    $restored=Query ('SELECT count(*) FROM public.'+$table)
    if ($restored -notmatch '^\d+$' -or [long]$restored -ne $seen[$table]) {
        Stop-Gate ('V8 restored row count mismatch for '+$table+
            ' expected='+$seen[$table]+' actual='+$restored)
    }
}
$roles=Query "SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname IN ('aronexa_app_runtime','aronexa_auth_runtime') AND rolcanlogin AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole AND NOT rolcreatedb"
if ($roles -ne '2') { Stop-Gate 'Disposable restricted roles invalid' }
$sourceHistory=Exec 'docker.exe' @('exec',$src,'psql','-X','-At','-v','ON_ERROR_STOP=1',
   '-U','aronexa_dev','-d','aronexa_dev','-c',
   "SELECT version||':'||checksum FROM public.flyway_schema_history WHERE success ORDER BY installed_rank")
$targetHistory=Query "SELECT version||':'||checksum FROM public.flyway_schema_history WHERE success ORDER BY installed_rank"
if ($sourceHistory -ne $targetHistory) { Stop-Gate 'V8 Flyway history/checksum differs from original' }

Write-Host ('REPO_SHA: '+$sha)
Write-Host ('BACKUP_SHA256: '+$backupHash)
Write-Host ('BACKUP_BYTES: '+$bytes)
Write-Host ('W2_DISPOSABLE_CONTAINER: '+$rehearsal)
Write-Host ('W2_DISPOSABLE_NETWORK: '+$network)
Write-Host ('W2_RESTORED_FLYWAY: '+$v8)
Write-Host 'W2_RESTORED_25_TABLE_COUNTS: PASS'
Write-Host 'W2_V8_HISTORY_CHECKSUMS: PASS'
Write-Host 'W2_RESTRICTED_ROLE_BOOTSTRAP: PASS'
Write-Host 'ORIGINAL_FLYWAY_VERSION: 8'
Write-Host 'ORIGINAL_DATABASE_MODIFIED: NO'
Write-Host 'V9_APPLIED: NO'
Write-Host 'FINAL_STATUS: W2A_DISPOSABLE_V8_RESTORE_AND_COUNTS_PASS'
Write-Host 'STOP: Retain disposable container and network for the separately reviewed W2B V9 rehearsal; do not use docker compose down -v.'
