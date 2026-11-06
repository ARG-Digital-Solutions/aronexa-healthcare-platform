# P01-T07-B2 W2B: Apply only V9 to an independently restored disposable V8 database.
# DO NOT use this script for the original Aronexa development database.
# Requires successful W2A count/history/ACL evidence and owner authorization.
# Windows PowerShell 5.1; review before running. No secret printed.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RehearsalContainer,
    [Parameter(Mandatory=$true)][string]$RehearsalNetwork,
    [Parameter(Mandatory=$true)][string]$BackupFile,
    [Parameter(Mandatory=$true)][string]$CountsFile,
    [string]$RepoPath=(Get-Location).Path,
    [switch]$ConfirmSyntheticDisposableOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
function Stop-Gate([string]$msg) { throw "STOP: $msg" }
function External([string]$exe,[string[]]$args) {
    $result=& $exe @args
    if ($LASTEXITCODE -ne 0) { Stop-Gate ("Native operation failed: " + $exe) }
    return (($result | Out-String).Trim())
}
function Query([string]$sql) {
    return External 'docker.exe' @('exec',$RehearsalContainer,'psql','-X','-At',
        '-v','ON_ERROR_STOP=1','-U','aronexa_dev','-d','aronexa_dev','-c',$sql)
}
if (-not $ConfirmSyntheticDisposableOnly) { Stop-Gate 'Explicit synthetic/disposable confirmation missing' }
if ($RehearsalContainer -notmatch '^aronexa-w2-pg17-(\d{8}_\d{6}_[a-fA-F0-9]{8})$') {
    Stop-Gate 'Incorrect disposable container naming convention'
}
$runId=$Matches[1]
if ($RehearsalNetwork -ne ('aronexa-w2-v9-'+$runId)) {
    Stop-Gate 'Disposable container/network IDs do not match'
}
$repo=External 'git.exe' @('-C',$RepoPath,'rev-parse','--show-toplevel')
$branch=External 'git.exe' @('-C',$repo,'branch','--show-current')
if ($branch -ne 'feature/p01-t07b-patient-onboarding') { Stop-Gate 'Wrong branch' }
$dirty=External 'git.exe' @('-C',$repo,'status','--porcelain')
if ($dirty) { Stop-Gate 'Working tree not clean' }
$sha=External 'git.exe' @('-C',$repo,'rev-parse','HEAD')
$v9Path='backend/src/main/resources/db/migration/V9__patient_registration_read_boundary.sql'
$v9blob=External 'git.exe' @('-C',$repo,'rev-parse',('HEAD:'+$v9Path))
if ($v9blob -ne '3a1e2b717576ccd84f0de058bb0a83786c517d32') {
    Stop-Gate 'V9 source differs from reviewed security migration'
}
$migrationDir=Join-Path $repo 'backend\src\main\resources\db\migration'
if (-not (Test-Path -LiteralPath $migrationDir -PathType Container)) {
    Stop-Gate 'Flyway migrations directory missing'
}
if (-not (Test-Path -LiteralPath $BackupFile -PathType Leaf)) { Stop-Gate 'Backup missing' }
if (-not (Test-Path -LiteralPath $CountsFile -PathType Leaf)) { Stop-Gate 'Count evidence missing' }
$bytes=(Get-Item -LiteralPath $BackupFile).Length
if ($bytes -le 0) { Stop-Gate 'Backup is empty' }
$backupHash=(Get-FileHash -LiteralPath $BackupFile -Algorithm SHA256).Hash.ToUpperInvariant()
$rows=@(Import-Csv -LiteralPath $CountsFile)
if ($rows.Count -ne 25) { Stop-Gate 'Expected 25 original V8 tables' }
$expected=@{}
foreach ($row in $rows) {
    $t=$row.Table; $n=$row.Count
    if (-not $t -or $t -notmatch '^[a-z_][a-z0-9_]*$' -or $expected.ContainsKey($t)) {
        Stop-Gate 'CSV has invalid or duplicate table name'
    }
    if ($null -eq $n -or $n -notmatch '^\d+$') { Stop-Gate 'CSV invalid count' }
    $expected[$t]=[long]$n
}
if (-not $expected.ContainsKey('module_registry') -or -not $expected.ContainsKey('auth_permission')) {
    Stop-Gate 'Original count inventory missing V9 catalog tables'
}

# Critical target isolation: no published port, not source, matching internal-only network.
$sourceIds=External 'docker.exe' @('ps','--filter','label=com.docker.compose.project=aronexa-dev',
    '--filter','label=com.docker.compose.service=postgres','--format','{{.ID}}')
$sources=@($sourceIds -split '\r?\n' | Where-Object { $_ -match '^[0-9a-f]{12,64}$' })
if ($sources.Count -ne 1) { Stop-Gate 'Original DB container is missing or ambiguous' }
$sourceFull=External 'docker.exe' @('inspect','--format','{{.Id}}',$sources[0])
$targetFull=External 'docker.exe' @('inspect','--format','{{.Id}}',$RehearsalContainer)
if ($sourceFull -eq $targetFull) { Stop-Gate 'REFUSE to migrate original development DB' }
$running=External 'docker.exe' @('inspect','--format','{{.State.Running}}',$RehearsalContainer)
if ($running -ne 'true') { Stop-Gate 'Disposable container is not running' }
$internal=External 'docker.exe' @('network','inspect','--format','{{.Internal}}',$RehearsalNetwork)
if ($internal -ne 'true') { Stop-Gate 'Rehearsal network is not internal-only' }
$ports=External 'docker.exe' @('inspect','--format','{{json .HostConfig.PortBindings}}',$RehearsalContainer)
if ($ports -ne '{}' -and $ports -ne 'null') { Stop-Gate 'Rehearsal has published host ports' }
$mounts=External 'docker.exe' @('inspect','--format','{{json .Mounts}}',$RehearsalContainer)
if ($mounts -match 'aronexa_dev_postgres_data') { Stop-Gate 'Rehearsal has original data volume' }
$netMembership=External 'docker.exe' @('inspect','--format','{{json .NetworkSettings.Networks}}',$RehearsalContainer)
if (-not $netMembership.Contains($RehearsalNetwork) -or $netMembership.Contains('aronexa-dev')) {
    Stop-Gate 'Rehearsal network memberships unsafe'
}
$containerLabels=External 'docker.exe' @('inspect','--format','{{json .Config.Labels}}',$RehearsalContainer)
if (-not $containerLabels.Contains($runId)) { Stop-Gate 'Missing W2A disposable container label' }

$sourceVersion=External 'docker.exe' @('exec',$sources[0],'psql','-X','-At',
  '-v','ON_ERROR_STOP=1','-U','aronexa_dev','-d','aronexa_dev','-c',
  'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1')
if ($sourceVersion -ne '8') { Stop-Gate 'Original source unexpectedly changed from V8' }
$beforeVersion=Query 'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1'
if ($beforeVersion -ne '8') { Stop-Gate 'Disposable restore not at V8' }
$historyBefore=Query "SELECT version||':'||checksum::text FROM public.flyway_schema_history WHERE success ORDER BY installed_rank"
if (Query 'SELECT count(*) FROM public.flyway_schema_history WHERE NOT success' -ne '0') {
    Stop-Gate 'Failed Flyway history found'
}
if (Query "SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname='public'" -ne '25') {
    Stop-Gate 'Original V8 table count unexpected'
}
foreach ($t in ($expected.Keys | Sort-Object)) {
    $actual=Query ('SELECT count(*) FROM public.'+$t)
    if ($actual -notmatch '^\d+$' -or [long]$actual -ne $expected[$t]) {
        Stop-Gate ('Pre-V9 restore row count mismatch for '+$t)
    }
}
if (Query "SELECT count(*) FROM pg_roles WHERE rolname='aronexa_patient_runtime'" -ne '0') {
    Stop-Gate 'Patient runtime role already exists; do not rerun V9'
}

# Bootstrap the new role only in the disposable DB. Uses server-generated
# random synthetic password, never printed or reused for original volume.
$patientSql=@'
DO $patient_bootstrap$
BEGIN
    EXECUTE format(
      'CREATE ROLE aronexa_patient_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD %L',
      gen_random_uuid()::text || gen_random_uuid()::text
    );
END
$patient_bootstrap$;
'@
$patientSql | & docker.exe exec -i $RehearsalContainer psql -X -v ON_ERROR_STOP=1 -U aronexa_dev -d aronexa_dev
if ($LASTEXITCODE -ne 0) { Stop-Gate 'Disposable patient role provisioning failed' }
$validRole=Query "SELECT count(*) FROM pg_roles WHERE rolname='aronexa_patient_runtime' AND rolcanlogin AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit"
if ($validRole -ne '1') { Stop-Gate 'Patient role privileges not least-privileged' }

# Obtain only the random W2A disposable PostgreSQL password. No source DB credential.
$environment=External 'docker.exe' @('inspect','--format',
    '{{range .Config.Env}}{{println .}}{{end}}',$RehearsalContainer)
$passwordEntries=@($environment -split '\r?\n' | Where-Object { $_ -match '^POSTGRES_PASSWORD=' })
if ($passwordEntries.Count -ne 1) { Stop-Gate 'Disposable database password not found' }
$disposablePassword=$passwordEntries[0].Substring('POSTGRES_PASSWORD='.Length)
if ([string]::IsNullOrWhiteSpace($disposablePassword)) { Stop-Gate 'Empty disposable password' }

# Ephemeral env file is PRIVATE / outside Git, deleted in finally.
# Never echo or upload this file. Docker Flyway command will not print the secret.
$secretPath=Join-Path (Split-Path -Parent $BackupFile) ('W2B_TEMP_FLYWAY_'+$runId+'.env')
if (Test-Path -LiteralPath $secretPath) { Stop-Gate 'Temporary envfile already exists' }
$flywayArgs=@('run','--rm','--network',$RehearsalNetwork,
  '--env-file',$secretPath,
  '--mount',('type=bind,source='+$migrationDir+',target=/flyway/sql,readonly'),
  'flyway/flyway:13.12.0'
)
function Flyway([string]$operation) {
    return (External 'docker.exe' @($flywayArgs+@($operation)))
}
try {
    $lines=@('FLYWAY_URL=jdbc:postgresql://w2-db:5432/aronexa_dev',
      'FLYWAY_USER=aronexa_dev',
      'FLYWAY_PASSWORD='+$disposablePassword,
      'FLYWAY_LOCATIONS=filesystem:/flyway/sql',
      'FLYWAY_CONNECT_RETRIES=5')
    [System.IO.File]::WriteAllLines($secretPath,$lines,[System.Text.Encoding]::ASCII)
    $disposablePassword=$null
    $lines=$null

    # Flyway validate with pending V9 may reject the pending version; rely on
    # Flyway default validateOnMigrate (true), then explicit post-V9 validate.
    [void](Flyway 'info')
    $migrateResult=Flyway 'migrate'
    Write-Host $migrateResult
    $validated=Flyway 'validate'
    Write-Host $validated

    $afterVersion=Query 'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1'
    if ($afterVersion -ne '9') { Stop-Gate 'Disposable did not reach Flyway V9' }
    $failures=Query 'SELECT count(*) FROM public.flyway_schema_history WHERE NOT success'
    if ($failures -ne '0') { Stop-Gate 'Failed Flyway migrations' }
    $historyAfter=Query "SELECT version||':'||checksum::text FROM public.flyway_schema_history WHERE success AND version <> '9' ORDER BY installed_rank"
    if ($historyBefore -ne $historyAfter) { Stop-Gate 'V1-V8 Flyway history changed' }
    if (Query "SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname='public'" -ne '26') {
        Stop-Gate 'Expected 26 tables after new V9 access audit'
    }
    foreach ($t in ($expected.Keys | Sort-Object)) {
        $delta=[long]0
        if ($t -eq 'module_registry') { $delta=1 }
        if ($t -eq 'auth_permission') { $delta=6 }
        $actual=Query ('SELECT count(*) FROM public.'+$t)
        if ($actual -notmatch '^\d+$' -or [long]$actual -ne ($expected[$t]+$delta)) {
            Stop-Gate ('Unexpected V9 table-count change for '+$t)
        }
    }
    if (Query 'SELECT count(*) FROM public.patient_registration_access_audit' -ne '0') {
        Stop-Gate 'New registration-access audit not empty'
    }
    $module=Query "SELECT CASE WHEN count(*)=1 AND bool_and(NOT implemented AND NOT released) THEN 't' ELSE 'f' END FROM public.module_registry WHERE code='PATIENT_REGISTRATION'"
    if ($module -ne 't') { Stop-Gate 'Patient registration module enabled or missing' }
    $authPermissionCount=Query "SELECT count(*) FROM public.auth_permission WHERE code IN ('PATIENT_REGISTER','PATIENT_REGISTRATION_READ','PATIENT_CANDIDATE_SEARCH','PATIENT_LINK_EXISTING','PATIENT_CONTACT_VERIFY','PATIENT_GUARDIAN_REVIEW')"
    if ($authPermissionCount -ne '6') { Stop-Gate 'Six B2 permission codes missing' }
    $grantedCodes=Query "SELECT count(*) FROM public.auth_role_permission arp JOIN public.auth_permission p ON p.id = arp.permission_id WHERE p.code IN ('PATIENT_REGISTER','PATIENT_REGISTRATION_READ','PATIENT_CANDIDATE_SEARCH','PATIENT_LINK_EXISTING','PATIENT_CONTACT_VERIFY','PATIENT_GUARDIAN_REVIEW')"
    if ($grantedCodes -ne '0') { Stop-Gate 'Premature patient staff permissions detected' }

    $rls=Query "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('patient_registration','org_membership_branch_scope','tenant_module_state','patient_registration_access_audit') AND c.relrowsecurity"
    if ($rls -ne '4') { Stop-Gate 'Missing required patient RLS policies/table flags' }
    $forceAudit=Query "SELECT relforcerowsecurity FROM pg_class WHERE oid='public.patient_registration_access_audit'::regclass"
    if ($forceAudit -ne 't') { Stop-Gate 'New audit table missing FORCE RLS' }
    $policyCount=Query "SELECT count(*) FROM pg_policies WHERE schemaname='public' AND policyname IN ('patient_runtime_branch_membership_scope','patient_runtime_entitlement_scope','patient_runtime_registration_read','patient_runtime_registration_access_log')"
    if ($policyCount -ne '4') { Stop-Gate 'Missing V9 row-level security policy' }
    $auditTrigger=Query "SELECT count(*) FROM pg_trigger WHERE tgrelid='public.patient_registration_access_audit'::regclass AND tgname='patient_registration_access_audit_immutable' AND NOT tgisinternal"
    if ($auditTrigger -ne '1') { Stop-Gate 'Immutable audit trigger missing' }

    # Verify exact allow/deny table grants. Does not read real patient content.
    $allowed=@(
      @('aronexa_patient_runtime','patient_registration','SELECT'),
      @('aronexa_patient_runtime','org_membership_branch_scope','SELECT'),
      @('aronexa_patient_runtime','module_registry','SELECT'),
      @('aronexa_patient_runtime','tenant_module_state','SELECT'),
      @('aronexa_patient_runtime','patient_registration_access_audit','INSERT')
    )
    foreach ($entry in $allowed) {
        $yes=Query ("SELECT has_table_privilege('"+$entry[0]+"','public."+$entry[1]+"','"+$entry[2]+"')")
        if ($yes -ne 't') { Stop-Gate ('Required grant missing: '+($entry -join ':')) }
    }
    $restricted=@('patient','patient_identifier','patient_contact','patient_relationship','patient_identity_audit')
    foreach ($table in $restricted) {
        if (Query ("SELECT has_table_privilege('aronexa_patient_runtime','public."+$table+"','SELECT')") -ne 'f') {
            Stop-Gate ('Patient role has forbidden identity read: '+$table)
        }
    }
    foreach ($privilege in @('INSERT','UPDATE','DELETE')) {
        if (Query ("SELECT has_table_privilege('aronexa_patient_runtime','public.patient_registration','"+$privilege+"')") -ne 'f') {
            Stop-Gate ('Patient role has forbidden registration mutation: '+$privilege)
        }
    }
    foreach ($privilege in @('SELECT','UPDATE','DELETE','TRUNCATE')) {
        if (Query ("SELECT has_table_privilege('aronexa_patient_runtime','public.patient_registration_access_audit','"+$privilege+"')") -ne 'f') {
            Stop-Gate ('Patient role has forbidden audit privilege: '+$privilege)
        }
    }
    foreach ($role in @('aronexa_app_runtime','aronexa_auth_runtime')) {
        foreach ($table in (@('patient_registration','patient_registration_access_audit')+$restricted)) {
            if (Query ("SELECT has_table_privilege('"+$role+"','public."+$table+"','SELECT')") -ne 'f') {
                Stop-Gate ('App/auth role leaked patient read access: '+$role+'/'+$table)
            }
        }
    }
    # No-context read must return no registration IDs under restricted patient SQL role.
    $noContext=Query "BEGIN; SET LOCAL ROLE aronexa_patient_runtime; SELECT count(*) FROM public.patient_registration; ROLLBACK;"
    if (($noContext -split '\r?\n' | Where-Object { $_ -eq '0' }).Count -lt 1) {
        Stop-Gate 'No-context patient RLS read did not deny rows'
    }
    $finalSourceVersion=External 'docker.exe' @('exec',$sources[0],'psql','-X','-At',
      '-v','ON_ERROR_STOP=1','-U','aronexa_dev','-d','aronexa_dev','-c',
      'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1')
    if ($finalSourceVersion -ne '8') { Stop-Gate 'Original source version unexpectedly changed' }

    Write-Host ('REPO_SHA: '+$sha)
    Write-Host ('BACKUP_SHA256: '+$backupHash)
    Write-Host ('W2_DISPOSABLE_CONTAINER: '+$RehearsalContainer)
    Write-Host 'W2_V8_RESTORE_COUNTS: PASS'
    Write-Host 'W2_V9_FLYWAY_MIGRATE_VALIDATE: PASS'
    Write-Host 'W2_V9_EXPECTED_CATALOG_DELTAS: PASS (+1 module, +6 permissions)'
    Write-Host 'W2_V9_OTHER_V8_TABLE_COUNTS_PRESERVED: PASS'
    Write-Host 'W2_V9_PATIENT_ROLE_GRANTS_RLS_AUDIT: PASS'
    Write-Host ('ORIGINAL_FLYWAY_VERSION: '+$finalSourceVersion)
    Write-Host 'ORIGINAL_DATABASE_MODIFIED: NO'
    Write-Host 'FINAL_STATUS: W2B_DISPOSABLE_V9_MIGRATION_AND_SECURITY_CHECKS_PASS'
}
finally {
    $disposablePassword=$null
    if (Test-Path -LiteralPath $secretPath -PathType Leaf) {
        Remove-Item -LiteralPath $secretPath -Force
    }
}
