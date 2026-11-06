#requires -Version 5.1
<#
P01-T07-A: Windows dev V7 -> V8 controlled migration.
Prepare: verified custom-format backup, isolated temporary restore, V8 Flyway rehearsal, no source DB upgrade.
Apply: explicit two-part approval, recheck unchanged V7 DB, migrate original, validate V8.
Verify: read-only V8 checks and (optional) local build tests.
No use on customer, production, or clinical databases. No passwords/backups in Git.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('Prepare','Apply','Verify')][string]$Stage,
    [string]$RepoPath = (Get-Location).Path,
    [string]$EvidenceRoot = (Join-Path $env:USERPROFILE 'AroNexa-Backups'),
    [string]$ManifestPath,
    [string]$ApprovalPhrase,
    [string]$ExpectedBackupSha256,
    [switch]$ConfirmSyntheticDevelopmentDataOnly,
    [switch]$RunLocalBuildTests
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$script:ExpectedRepo = 'ARG-Digital-Solutions/aronexa-healthcare-platform'
$script:MergeSha = '4f5926a883506437bf7131b792ee39fd8c7579da'
$script:Images = @('postgres:17-alpine','flyway/flyway:13.12.0')
$script:SourceContainer = $null
$script:ReportPath = $null
$script:Phase = $Stage
$script:MigrationDir = $null
$script:V8File = $null

function Stop-Gate([string]$Reason) { throw "STOP: $Reason" }
function Say([string]$Message) {
    Write-Host $Message
    if ($script:ReportPath) { Add-Content -LiteralPath $script:ReportPath -Value $Message -Encoding UTF8 }
}
function Run([string]$Executable, [string[]]$ArgList) {
    # No output includes .env secrets; callers must not pass passwords as explicit arguments.
    # Native tools may write harmless progress to stderr; use exit code as the gate.
    $ErrorActionPreference = 'Continue'
    $lines = @(& $Executable @ArgList 2>&1 | ForEach-Object { "$_" })
    $exit = $LASTEXITCODE
    if ($exit -ne 0) {
        # Deliberately do not embed native process output: PostgreSQL/Flyway may expose details.
        Stop-Gate "External command failed (exit $exit). Inspect your private terminal/logs; do not publish credentials."
    }
    return ($lines -join "`n").Trim()
}
function Docker([string[]]$Arguments) { return Run 'docker.exe' $Arguments }
function Git([string[]]$Arguments) { return Run 'git.exe' $Arguments }
function Psql([string]$Container, [string]$Sql) {
    return (Docker @('exec', $Container, 'psql', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1',
        '-U', 'aronexa_dev', '-d', 'aronexa_dev', '-c', $Sql)).Trim()
}
function Get-Version([string]$Container) {
    return Psql $Container 'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;'
}
function Get-TableCounts([string]$Container) {
    $names = @( (Psql $Container "SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename;") -split "\r?\n" | Where-Object { $_ -ne '' } )
    if ($names.Count -lt 15) { Stop-Gate "Unexpectedly small table inventory: $($names.Count)." }
    $counts = [ordered]@{}
    foreach ($name in $names) {
        if ($name -cnotmatch '^[a-z][a-z0-9_]{0,62}$') { Stop-Gate 'Unsafe SQL table identifier in DB catalog.' }
        $value = Psql $Container "SELECT count(*) FROM public.$name;"
        if ($value -notmatch '^\d+$') { Stop-Gate "Unparseable count for $name" }
        $counts[$name] = [long]::Parse($value)
    }
    return $counts
}
function Assert-Counts($Before, $After, [string]$Description) {
    foreach ($name in $Before.Keys) {
        if (-not $After.Contains($name) -or [long]$Before[$name] -ne [long]$After[$name]) {
            Stop-Gate "Table preservation mismatch ($Description): $name"
        }
    }
    Say "PASS: all $($Before.Keys.Count) pre-existing table counts preserved ($Description)"
}
function Assert-ContainerIdentity {
    $ids = @( (Docker @('ps','--filter','label=com.docker.compose.project=aronexa-dev',
        '--filter','label=com.docker.compose.service=postgres','--format','{{.ID}}')) -split "\r?\n" |
        Where-Object { $_ -ne '' } )
    if ($ids.Count -ne 1) { Stop-Gate 'Expected exactly one running aronexa-dev Compose postgres container; do not auto-start or touch another DB.' }
    $script:SourceContainer = $ids[0]
    $database = Psql $script:SourceContainer 'SELECT current_database();'
    if ($database -ne 'aronexa_dev') { Stop-Gate 'Connected database is not aronexa_dev.' }
    $roleCount = Psql $script:SourceContainer @'
SELECT count(*) FROM pg_roles WHERE rolname IN ('aronexa_app_runtime','aronexa_auth_runtime') AND NOT rolsuper AND NOT rolbypassrls;
'@
    if ($roleCount -ne '2') { Stop-Gate 'Runtime roles missing or overly privileged.' }
    Say 'PASS: development Compose database and restricted roles identified'
}
function Assert-GitAndImages {
    if (-not (Test-Path -LiteralPath (Join-Path $RepoPath '.git'))) { Stop-Gate 'RepoPath is not a Git checkout root.' }
    $root = Git @('-C',$RepoPath,'rev-parse','--show-toplevel')
    if (-not [IO.Path]::GetFullPath($root).TrimEnd('\','/').Equals([IO.Path]::GetFullPath($RepoPath).TrimEnd('\','/'),[StringComparison]::OrdinalIgnoreCase)) {
        Stop-Gate 'Run from the actual AroNexa repository root.'
    }
    $remote = Git @('-C',$RepoPath,'remote','get-url','origin')
    if ($remote -notmatch '(?i)(ARG-Digital-Solutions/aronexa-healthcare-platform)(\.git)?$') { Stop-Gate 'Unexpected repository origin.' }
    if ((Git @('-C',$RepoPath,'branch','--show-current')) -ne 'main') { Stop-Gate 'Checkout the reviewed main branch before proceeding.' }
    if ((Git @('-C',$RepoPath,'status','--porcelain')) -ne '') { Stop-Gate 'Working tree has uncommitted changes.' }
    [void](Git @('-C',$RepoPath,'merge-base','--is-ancestor',$script:MergeSha,'HEAD'))
    $script:MigrationDir = Join-Path $RepoPath 'backend\src\main\resources\db\migration'
    $script:V8File = Join-Path $script:MigrationDir 'V8__shared_patient_identity_foundation.sql'
    if (-not (Test-Path -LiteralPath $script:V8File -PathType Leaf)) { Stop-Gate 'V8 migration file missing.' }
    foreach ($image in $script:Images) { [void](Docker @('image','inspect',$image,'--format','{{.Id}}')) }
    Say 'PASS: clean AroNexa main includes PR #25; required Docker images present'
}
function Assert-DatabaseNoWriters {
    $other = Psql $script:SourceContainer @'
SELECT count(*) FROM pg_stat_activity
WHERE datname='aronexa_dev' AND pid <> pg_backend_pid() AND backend_type='client backend';
'@
    if ($other -ne '0') { Stop-Gate "Original dev DB has $other other client connection(s). Stop backend/tools and retry." }
    Say 'PASS: no other database client sessions (write freeze preflight)'
}
function Assert-NoFailedFlyway([string]$Container) {
    $failed = Psql $Container 'SELECT count(*) FROM public.flyway_schema_history WHERE success=false;'
    if ($failed -ne '0') { Stop-Gate 'Unsuccessful Flyway history entries detected.' }
}
function Assert-V8([string]$Container) {
    Assert-NoFailedFlyway $Container
    if ((Get-Version $Container) -ne '8') { Stop-Gate 'Database did not reach Flyway V8.' }
    $tables = Psql $Container @'
SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname='public'
AND tablename IN ('patient','patient_identifier','patient_contact',
'patient_relationship','patient_registration','patient_identity_audit');
'@
    if ($tables -ne '6') { Stop-Gate 'V8 patient table inventory incomplete.' }
    $rls = Psql $Container @'
SELECT count(*) FROM pg_class WHERE relname IN
('patient','patient_identifier','patient_contact','patient_relationship','patient_registration')
AND relrowsecurity AND relforcerowsecurity;
'@
    if ($rls -ne '5') { Stop-Gate 'Patient table FORCE RLS flags incomplete.' }
    $grants = Psql $Container @'
SELECT count(*) FROM pg_catalog.pg_tables
CROSS JOIN (VALUES ('aronexa_app_runtime'),('aronexa_auth_runtime')) AS roles(r)
WHERE schemaname='public' AND tablename IN
('patient','patient_identifier','patient_contact',
 'patient_relationship','patient_registration','patient_identity_audit')
AND (has_table_privilege(roles.r,format('public.%I',tablename),'SELECT')
 OR has_table_privilege(roles.r,format('public.%I',tablename),'INSERT')
 OR has_table_privilege(roles.r,format('public.%I',tablename),'UPDATE')
 OR has_table_privilege(roles.r,format('public.%I',tablename),'DELETE'));
'@
    if ($grants -ne '0') { Stop-Gate 'Premature patient table privileges on runtime roles.' }
    $patients = Psql $Container 'SELECT count(*) FROM public.patient;'
    if ($patients -ne '0') { Stop-Gate 'Patient table is not empty in this development foundation stage.' }
    Say 'PASS: Flyway V8, six patient tables, FORCE RLS, no runtime grants, no patient records'
}
function Password-FromEnvFile {
    $file = Join-Path $RepoPath 'deploy\dev\.env'
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { Stop-Gate 'Local untracked deploy/dev/.env missing; no credentials read.' }
    $matches = @(Get-Content -LiteralPath $file | Where-Object { $_ -match '^\s*ARONEXA_DB_PASSWORD=' })
    if ($matches.Count -ne 1) { Stop-Gate 'Expected one ARONEXA_DB_PASSWORD line in local .env.' }
    $password = ($matches[0] -split '=',2)[1].Trim().Trim('"',"'")
    if ($password.Length -lt 16 -or $password.StartsWith('replace-with')) { Stop-Gate 'Weak or placeholder dev migrator password.' }
    return $password
}
function Flyway([string]$DatabaseContainer, [string]$Password, [string]$Command) {
    $prior = [Environment]::GetEnvironmentVariable('FLYWAY_PASSWORD','Process')
    try {
        [Environment]::SetEnvironmentVariable('FLYWAY_PASSWORD',$Password,'Process')
        # Container namespace shares only the target PostgreSQL network; no host port mapping.
        [void](Docker @('run','--rm','--network',"container:$DatabaseContainer",
            '--mount',"type=bind,source=$script:MigrationDir,destination=/flyway/sql,readonly",
            '-e','FLYWAY_URL=jdbc:postgresql://127.0.0.1:5432/aronexa_dev',
            '-e','FLYWAY_USER=aronexa_dev','-e','FLYWAY_PASSWORD',
            'flyway/flyway:13.12.0','-locations=filesystem:/flyway/sql',$Command))
    } finally {
        [Environment]::SetEnvironmentVariable('FLYWAY_PASSWORD',$prior,'Process')
    }
}
function New-PrivateFolder {
    [void](New-Item -ItemType Directory -Path $EvidenceRoot -Force)
    $folder = Join-Path $EvidenceRoot ('P01-T07A-V8-' + (Get-Date -Format 'yyyyMMdd_HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
    [void](New-Item -ItemType Directory -Path $folder -ErrorAction Stop)
    return $folder
}
function Assert-SyntheticApproval {
    if (-not $ConfirmSyntheticDevelopmentDataOnly) {
        Stop-Gate 'You must affirm this is a synthetic-only local dev DB using -ConfirmSyntheticDevelopmentDataOnly.'
    }
}
function Prepare {
    Assert-SyntheticApproval
    Assert-GitAndImages
    Assert-ContainerIdentity
    Assert-DatabaseNoWriters
    Assert-NoFailedFlyway $script:SourceContainer
    if ((Get-Version $script:SourceContainer) -ne '7') { Stop-Gate 'Expected untouched V7 source; do not migrate an existing V8 or older source.' }
    Say 'PASS: original dev database is V7 and has no failed migrations'
    $pre = Get-TableCounts $script:SourceContainer
    $dir = New-PrivateFolder
    $script:ReportPath = Join-Path $dir 'PREPARE_REPORT.txt'
    $backup = Join-Path $dir 'aronexa_dev_pre_v8.dump'
    $dbBackup = '/tmp/p01_t07a_v7_' + [Guid]::NewGuid().ToString('N') + '.dump'
    # pg_dump is read-only with respect to DB; file is temporary in SOURCE container.
    try {
        [void](Docker @('exec',$script:SourceContainer,'pg_dump','-U','aronexa_dev','-d','aronexa_dev',
            '-Fc','--file',$dbBackup))
        [void](Docker @('cp',"$($script:SourceContainer):$dbBackup",$backup))
    } finally {
        try { [void](Docker @('exec',$script:SourceContainer,'rm','-f',$dbBackup)) } catch { Say 'WARNING: temporary dump file cleanup failed; inspect source container /tmp.' }
    }
    if (-not (Test-Path -LiteralPath $backup) -or (Get-Item -LiteralPath $backup).Length -lt 10000) {
        Stop-Gate 'Custom-format backup absent or unexpectedly small.'
    }
    $hash=(Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash
    $bytes=(Get-Item -LiteralPath $backup).Length
    Say "PASS: backup copied to local private folder; size=$bytes bytes; SHA256=$hash"
    # Reread source; prevent accepting a live-write DB whose snapshot drifted.
    Assert-DatabaseNoWriters
    $postDump = Get-TableCounts $script:SourceContainer
    Assert-Counts $pre $postDump 'source unchanged during backup'
    $rehearsal = 'aronexa-v8-disposable-' + [Guid]::NewGuid().ToString('N').Substring(0,10)
    $random = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($random) } finally { $rng.Dispose() }
    $tempPwd = [Convert]::ToBase64String($random)
    $oldPwd=[Environment]::GetEnvironmentVariable('POSTGRES_PASSWORD','Process')
    try {
        [Environment]::SetEnvironmentVariable('POSTGRES_PASSWORD',$tempPwd,'Process')
        [void](Docker @('run','--detach','--name',$rehearsal,'--network','none',
            '--tmpfs','/var/lib/postgresql/data:rw,size=512m',
            '-e','POSTGRES_DB=aronexa_dev','-e','POSTGRES_USER=aronexa_dev',
            '-e','POSTGRES_PASSWORD','postgres:17-alpine'))
        $ready=$false
        for ($i=0; $i -lt 50; $i++) {
            & docker.exe exec $rehearsal pg_isready -U aronexa_dev -d aronexa_dev *> $null
            if ($LASTEXITCODE -eq 0) { $ready=$true;break }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { Stop-Gate 'Isolated disposable PostgreSQL did not become ready.' }
        Say 'PASS: isolated temporary PostgreSQL (no network, no published ports, RAM-backed data)'
        # Role names must exist BEFORE ACL-bearing pg_restore.
        [void](Psql $rehearsal 'CREATE ROLE aronexa_app_runtime NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT;')
        [void](Psql $rehearsal 'CREATE ROLE aronexa_auth_runtime NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT;')
        [void](Docker @('cp',$backup,"${rehearsal}:/tmp/v7.dump"))
        [void](Docker @('exec',$rehearsal,'pg_restore','--list','/tmp/v7.dump'))
        [void](Docker @('exec',$rehearsal,'pg_restore','--exit-on-error','-U','aronexa_dev',
            '-d','aronexa_dev','/tmp/v7.dump'))
        if ((Get-Version $rehearsal) -ne '7') { Stop-Gate 'Disposable database was not restored at V7.' }
        $restored=Get-TableCounts $rehearsal
        Assert-Counts $pre $restored 'original V7 vs disposable restore'
        Say 'PASS: custom-format archive restored into isolated V7 database'
        Flyway $rehearsal $tempPwd 'migrate'
        Flyway $rehearsal $tempPwd 'validate'
        Assert-V8 $rehearsal
        $after=Get-TableCounts $rehearsal
        Assert-Counts $pre $after 'disposable V7 to V8'
        Say 'PASS: isolated V7-to-V8 rehearsal and Flyway validate completed'
    } finally {
        [Environment]::SetEnvironmentVariable('POSTGRES_PASSWORD',$oldPwd,'Process')
        try { [void](Docker @('rm','-f',$rehearsal)) } catch { Say "WARNING: disposable container cleanup failed: $rehearsal" }
    }
    if ((Get-Version $script:SourceContainer) -ne '7') { Stop-Gate 'Original DB version changed during rehearsal.' }
    Assert-Counts $pre (Get-TableCounts $script:SourceContainer) 'source still V7 after rehearsal'
    $manifest=[ordered]@{
        task='P01-T07-A'; classification='INTERNAL_FOUNDATION'; state='PREPARED_REHEARSED'
        prepared_at=(Get-Date).ToString('o'); repo_path=$RepoPath
        git_sha=(Git @('-C',$RepoPath,'rev-parse','HEAD'))
        v8_sql_sha256=(Get-FileHash -LiteralPath $script:V8File -Algorithm SHA256).Hash
        source_container=$script:SourceContainer; source_schema_version='7'
        backup_path=$backup; backup_bytes=$bytes; backup_sha256=$hash
        v7_table_counts=$pre; original_applied=$false; original_verified=$false
        caveat='PRIVATE LOCAL DEV ONLY; no customer data; no real patient release; backup ZIP excluded from report.'
    }
    $manifestPath=Join-Path $dir 'ACCEPTANCE_MANIFEST.json'
    $manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    Say 'FINAL_STATUS: PREPARED_AND_DISPOSABLE_REHEARSAL_PASSED; ORIGINAL_STILL_V7'
    Write-Host "Manifest: $manifestPath"
    Write-Host "Backup: $backup"
    Write-Host "Backup SHA256: $hash"
    Write-Host 'STOP: review evidence before explicitly invoking -Stage Apply.'
}
function Get-Manifest {
    if (-not $ManifestPath -or -not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
        Stop-Gate 'Provide the exact private -ManifestPath generated by Prepare.'
    }
    $m=Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
    if ($m.task -ne 'P01-T07-A' -or $m.classification -ne 'INTERNAL_FOUNDATION') {
        Stop-Gate 'Unexpected acceptance manifest task/classification.'
    }
    $countDict = [ordered]@{}
    foreach ($prop in $m.v7_table_counts.PSObject.Properties) { $countDict[$prop.Name] = [long]$prop.Value }
    $m.v7_table_counts = $countDict
    $script:ReportPath = Join-Path (Split-Path -Parent $ManifestPath) ("$($Stage.ToUpperInvariant())_REPORT.txt")
    return $m
}
function Assert-ManifestUnchanged($m, [string]$ExpectedVersion) {
    Assert-GitAndImages
    Assert-ContainerIdentity
    Assert-NoFailedFlyway $script:SourceContainer
    if ($script:SourceContainer -ne $m.source_container) { Stop-Gate 'Source container changed since backup/rehearsal.' }
    if ((Git @('-C',$RepoPath,'rev-parse','HEAD')) -ne $m.git_sha) { Stop-Gate 'Git checkout changed since rehearsal.' }
    if ((Get-FileHash -LiteralPath $script:V8File -Algorithm SHA256).Hash -ne $m.v8_sql_sha256) {
        Stop-Gate 'Migration SQL changed since rehearsal.'
    }
    if ((Get-Version $script:SourceContainer) -ne $ExpectedVersion) { Stop-Gate "Expected source DB V$ExpectedVersion." }
    if (-not (Test-Path -LiteralPath $m.backup_path -PathType Leaf)) { Stop-Gate 'Private source backup missing.' }
    $h=(Get-FileHash -LiteralPath $m.backup_path -Algorithm SHA256).Hash
    if ($h -ne $m.backup_sha256 -or (Get-Item -LiteralPath $m.backup_path).Length -ne $m.backup_bytes) {
        Stop-Gate 'Backup SHA256/length mismatch; abort migration.'
    }
    if ($ExpectedBackupSha256 -and $h -ne $ExpectedBackupSha256) {
        Stop-Gate 'Operator-confirmed backup SHA256 does not match.'
    }
    Say 'PASS: pinned Git, migration SQL, original container, backup bytes/hash and DB version'
}
function Apply {
    Assert-SyntheticApproval
    if ($ApprovalPhrase -cne 'MIGRATE_ARONEXA_DEV_V7_TO_V8') {
        Stop-Gate 'Explicit -ApprovalPhrase MIGRATE_ARONEXA_DEV_V7_TO_V8 required.'
    }
    if (-not $ExpectedBackupSha256 -or $ExpectedBackupSha256 -notmatch '^[A-Fa-f0-9]{64}$') {
        Stop-Gate 'Supply exact -ExpectedBackupSha256 from verified Prepare output.'
    }
    $m=Get-Manifest
    if ($m.state -ne 'PREPARED_REHEARSED' -or $m.original_applied) { Stop-Gate 'Manifest is not an unused, successful rehearsal.' }
    Assert-ManifestUnchanged $m '7'
    Assert-DatabaseNoWriters
    Assert-Counts $m.v7_table_counts (Get-TableCounts $script:SourceContainer) 'source vs rehearsal manifest before migration'
    Say 'PASS: original dev database unchanged since rehearse, no other clients'
    $pw = Password-FromEnvFile
    # THIS IS THE ONLY ACTION THAT MIGRATES THE ORIGINAL DB.
    Say 'BEGIN: controlled Flyway migrate of original synthetic-only development DB V7 -> V8'
    Flyway $script:SourceContainer $pw 'migrate'
    Flyway $script:SourceContainer $pw 'validate'
    $pw=$null
    Assert-V8 $script:SourceContainer
    Assert-Counts $m.v7_table_counts (Get-TableCounts $script:SourceContainer) 'original V7 -> V8'
    $m.state='ORIGINAL_V8_VERIFIED'
    $m.original_applied=$true
    $m.original_verified=$true
    $m.applied_at=(Get-Date).ToString('o')
    $m | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $ManifestPath -Encoding UTF8
    Say 'FINAL_STATUS: ORIGINAL_DEV_V8_MIGRATED_AND_VERIFIED; NO_CUSTOMER_RELEASE'
    Say 'Next: -Stage Verify -ManifestPath <path> -RunLocalBuildTests and archive private sanitized evidence.'
}
function Verify {
    $m=Get-Manifest
    if ($m.state -ne 'ORIGINAL_V8_VERIFIED') { Stop-Gate 'Original migration/verification not recorded in manifest.' }
    Assert-ManifestUnchanged $m '8'
    Assert-V8 $script:SourceContainer
    Assert-Counts $m.v7_table_counts (Get-TableCounts $script:SourceContainer) 'read-only original verification'
    if ($RunLocalBuildTests) {
        $folder=Split-Path -Parent $ManifestPath
        Say 'BEGIN: Maven verify against isolated Testcontainers (not original dev DB)'
        Push-Location $RepoPath
        try {
            if (-not (Get-Command mvn.cmd -ErrorAction SilentlyContinue)) {
                Stop-Gate 'Maven executable not found.'
            }
            $previousPreference = $ErrorActionPreference
            $mavenExit = $null
            try {
                $ErrorActionPreference = 'Continue'
                $global:LASTEXITCODE = $null
                & mvn.cmd -B -f backend/pom.xml verify *> (Join-Path $folder 'maven-private.log')
                $mavenExit = $LASTEXITCODE
            } finally {
                $ErrorActionPreference = $previousPreference
            }
            if ($null -eq $mavenExit -or $mavenExit -ne 0) {
                Stop-Gate 'Maven verify failed; inspect private log.'
            }
            Say 'PASS: Windows Maven verify'
            Push-Location (Join-Path $RepoPath 'frontend')
            try {
                if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
                    Stop-Gate 'npm executable not found.'
                }
                $previousNpmPreference = $ErrorActionPreference
                $npmCiExit = $null
                try {
                    $ErrorActionPreference = 'Continue'
                    $global:LASTEXITCODE = $null
                    & npm.cmd ci --no-audit --no-fund *> (Join-Path $folder 'npm-ci-private.log')
                    $npmCiExit = $LASTEXITCODE
                } finally {
                    $ErrorActionPreference = $previousNpmPreference
                }
                if ($null -eq $npmCiExit -or $npmCiExit -ne 0) {
                    Stop-Gate 'npm ci failed; inspect private log.'
                }
                $npmBuildExit = $null
                try {
                    $ErrorActionPreference = 'Continue'
                    $global:LASTEXITCODE = $null
                    & npm.cmd run build *> (Join-Path $folder 'npm-build-private.log')
                    $npmBuildExit = $LASTEXITCODE
                } finally {
                    $ErrorActionPreference = $previousNpmPreference
                }
                if ($null -eq $npmBuildExit -or $npmBuildExit -ne 0) {
                    Stop-Gate 'frontend build failed; inspect private log.'
                }
                Say 'PASS: Windows npm ci and frontend production build'
            } finally { Pop-Location }
        } finally { Pop-Location }
    } else {
        Say 'NOT RUN: Windows Maven/frontend build (use -RunLocalBuildTests to execute)'
    }
    Say 'FINAL_STATUS: READ_ONLY_V8_VERIFICATION_PASSED; OWNER_CLOSURE_APPROVAL_STILL_REQUIRED'
}

try {
    if (-not (Test-Path -LiteralPath $RepoPath -PathType Container)) { Stop-Gate 'RepoPath does not exist.' }
    $RepoPath=(Resolve-Path -LiteralPath $RepoPath).Path
    switch($Stage) {
        'Prepare' { Prepare }
        'Apply'   { Apply }
        'Verify'  { Verify }
    }
} catch {
    $errorMsg = $_.Exception.Message
    # Do not print native command strings, password values or database rows.
    if ($errorMsg -notlike 'STOP:*') { $errorMsg = 'STOP: script error: ' + $errorMsg }
    Say "FINAL_STATUS: BLOCKED ($Stage). $errorMsg"
    exit 1
}
