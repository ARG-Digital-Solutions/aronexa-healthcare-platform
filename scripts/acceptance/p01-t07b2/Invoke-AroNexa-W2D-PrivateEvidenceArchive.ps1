# P01-T07-B2 W2D: Create a private, sanitized evidence ZIP outside the repository.
# The ZIP contains ONLY summary, 25 table COUNTS, and SHA256 file manifest.
# The original V8 backup, SQL dumps, passwords, .env, logs and credentials
# are NEVER added to the archive. Windows PowerShell 5.1.
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string]$BackupFile,
  [Parameter(Mandatory=$true)][string]$CountsFile,
  [Parameter(Mandatory=$true)][string]$RehearsalContainer,
  [Parameter(Mandatory=$true)][string]$RehearsalNetwork,
  [string]$RepoPath=(Get-Location).Path,
  [string]$PrivateEvidenceRoot=(Join-Path $env:USERPROFILE 'AroNexa-Backups'),
  [switch]$ConfirmW2BPassOwnerReported,
  [switch]$ConfirmW2CPassOwnerReported,
  [switch]$ConfirmSyntheticDisposableOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
function Stop-Gate([string]$msg) { throw ('STOP: '+$msg) }
function Native([string]$exe,[string[]]$argv) {
  $output=& $exe @argv
  if ($LASTEXITCODE -ne 0) { Stop-Gate ('Read-only native command failed: '+$exe) }
  return (($output | Out-String).Trim())
}
function Query([string]$container,[string]$sql) {
  return (Native 'docker.exe' @('exec',$container,'psql','-X','-At',
    '-v','ON_ERROR_STOP=1','-U','aronexa_dev','-d','aronexa_dev','-c',$sql))
}
function Equal([string]$name,[string]$actual,[string]$expected) {
  if ($actual -cne $expected) { Stop-Gate ($name+' expected '+$expected+' but observed '+$actual) }
}
if (-not ($ConfirmW2BPassOwnerReported -and $ConfirmW2CPassOwnerReported -and $ConfirmSyntheticDisposableOnly)) {
  Stop-Gate 'Explicit owner-reported W2B/W2C PASS and synthetic-only flags required'
}
if ($RehearsalContainer -notmatch '^aronexa-w2-pg17-(\d{8}_\d{6}_[0-9a-fA-F]{8})$') {
  Stop-Gate 'Unexpected disposable container'
}
$runId=$Matches[1]
Equal 'Disposable network ID' $RehearsalNetwork ('aronexa-w2-v9-'+$runId)
$repo=Native 'git.exe' @('-C',$RepoPath,'rev-parse','--show-toplevel')
Equal 'Branch' (Native 'git.exe' @('-C',$repo,'branch','--show-current')) 'feature/p01-t07b-patient-onboarding'
if (Native 'git.exe' @('-C',$repo,'status','--porcelain')) { Stop-Gate 'Git checkout is not clean' }
$commit=Native 'git.exe' @('-C',$repo,'rev-parse','HEAD')
$migrationRelative='backend/src/main/resources/db/migration/V9__patient_registration_read_boundary.sql'
Equal 'V9 reviewed Git blob' (Native 'git.exe' @('-C',$repo,'rev-parse',('HEAD:'+$migrationRelative))) '3a1e2b717576ccd84f0de058bb0a83786c517d32'
$v9Path=Join-Path $repo 'backend\src\main\resources\db\migration\V9__patient_registration_read_boundary.sql'
if (-not (Test-Path -LiteralPath $v9Path -PathType Leaf)) { Stop-Gate 'Reviewed V9 file not found' }
if (-not (Test-Path -LiteralPath $BackupFile -PathType Leaf)) { Stop-Gate 'Private backup missing' }
if (-not (Test-Path -LiteralPath $CountsFile -PathType Leaf)) { Stop-Gate 'Original counts missing' }
$bytes=(Get-Item -LiteralPath $BackupFile).Length
Equal 'V8 private backup bytes' ([string]$bytes) '92567'
$backupSha=(Get-FileHash -LiteralPath $BackupFile -Algorithm SHA256).Hash.ToUpperInvariant()
Equal 'V8 private backup SHA256' $backupSha '153842CCC811A64729F2DBA0743515A6C24E5A20AFB1D054AF054FECB36C3CAB'
$rows=@(Import-Csv -LiteralPath $CountsFile)
if ($rows.Count -ne 25) { Stop-Gate 'Expected 25 original table count records' }
$seen=@{}
$normalized=@()
foreach ($row in $rows) {
  $table=$row.Table; $count=$row.Count
  if (-not $table -or $table -notmatch '^[a-z_][a-z0-9_]*$' -or $seen.ContainsKey($table) -or
      $null -eq $count -or $count -notmatch '^[0-9]+$') {
    Stop-Gate 'Unsafe/duplicate table count evidence'
  }
  $seen[$table]=$true
  $normalized += [pscustomobject][ordered]@{Table=$table;Count=[long]$count}
}
$originalIds=Native 'docker.exe' @('ps','--filter','label=com.docker.compose.project=aronexa-dev',
  '--filter','label=com.docker.compose.service=postgres','--format','{{.ID}}')
$sources=@($originalIds -split '\r?\n' | Where-Object { $_ -match '^[0-9a-f]{12,64}$' })
if ($sources.Count -ne 1) { Stop-Gate 'Original PostgreSQL ambiguous' }
$source=$sources[0]
if ((Native 'docker.exe' @('inspect','--format','{{.Id}}',$source)) -eq
    (Native 'docker.exe' @('inspect','--format','{{.Id}}',$RehearsalContainer))) {
  Stop-Gate 'Refuse original target as rehearsal'
}
Equal 'Rehearsal running' (Native 'docker.exe' @('inspect','--format','{{.State.Running}}',$RehearsalContainer)) 'true'
Equal 'Rehearsal internal network' (Native 'docker.exe' @('network','inspect','--format','{{.Internal}}',$RehearsalNetwork)) 'true'
$ports=Native 'docker.exe' @('inspect','--format','{{json .HostConfig.PortBindings}}',$RehearsalContainer)
if ($ports -ne 'null' -and $ports -ne '{}') { Stop-Gate 'Rehearsal host port is published' }
$mounts=Native 'docker.exe' @('inspect','--format','{{json .Mounts}}',$RehearsalContainer)
if ($mounts -match 'aronexa_dev_postgres_data') { Stop-Gate 'Original named volume mounted in rehearsal' }
$netjson=Native 'docker.exe' @('inspect','--format','{{json .NetworkSettings.Networks}}',$RehearsalContainer)
$networkNames=@(($netjson | ConvertFrom-Json).PSObject.Properties.Name)
if ($networkNames.Count -ne 1 -or $networkNames[0] -ne $RehearsalNetwork) {
  Stop-Gate 'Unexpected rehearsal network attachment'
}
Equal 'Original Flyway' (Query $source 'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1') '8'
Equal 'Rehearsal Flyway' (Query $RehearsalContainer 'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1') '9'
Equal 'Rehearsal failed migrations' (Query $RehearsalContainer 'SELECT count(*) FROM public.flyway_schema_history WHERE NOT success') '0'
Equal 'Rehearsal records reset' (Query $RehearsalContainer 'SELECT count(*) FROM public.patient_registration') '0'
Equal 'Rehearsal access audits reset' (Query $RehearsalContainer 'SELECT count(*) FROM public.patient_registration_access_audit') '0'
Equal 'Registration remains disabled' (Query $RehearsalContainer "SELECT CASE WHEN count(*)=1 AND bool_and(NOT implemented AND NOT released) THEN 't' ELSE 'f' END FROM public.module_registry WHERE code='PATIENT_REGISTRATION'") 't'

# Fail unless destination is outside the Git checkout and not under the private W1 backup folder.
$evidenceRoot=[IO.Path]::GetFullPath($PrivateEvidenceRoot).TrimEnd('\','/')
$repoRoot=[IO.Path]::GetFullPath($repo).TrimEnd('\','/')
$backupFolder=[IO.Path]::GetFullPath((Split-Path -Parent $BackupFile)).TrimEnd('\','/')
if ($evidenceRoot.StartsWith($repoRoot+'\',[StringComparison]::OrdinalIgnoreCase) -or
    $evidenceRoot -eq $repoRoot) { Stop-Gate 'Evidence root must be outside checkout' }
if ($evidenceRoot.StartsWith($backupFolder+'\',[StringComparison]::OrdinalIgnoreCase)) {
  Stop-Gate 'Do not create archive within private W1 backup folder'
}
if (-not (Test-Path -LiteralPath $evidenceRoot -PathType Container)) {
  New-Item -ItemType Directory -Path $evidenceRoot -ErrorAction Stop | Out-Null
}
$marker=(Get-Date -Format 'yyyyMMdd_HHmmss')+'_'+[guid]::NewGuid().ToString('N').Substring(0,8)
$folder=Join-Path $evidenceRoot ('P01-T07B2-W2D-Sanitized-'+$marker)
$zipPath=$folder+'.zip'
if (Test-Path -LiteralPath $folder) { Stop-Gate 'Evidence folder already exists' }
if (Test-Path -LiteralPath $zipPath) { Stop-Gate 'Evidence archive already exists' }
New-Item -ItemType Directory -Path $folder -ErrorAction Stop | Out-Null

$summary=Join-Path $folder '01_W2D_SANITIZED_SUMMARY.txt'
$counts=Join-Path $folder '02_V8_TABLE_COUNTS_SANITIZED.csv'
$manifest=Join-Path $folder '03_SHA256_EVIDENCE_MANIFEST.csv'
$v9Sha=(Get-FileHash -LiteralPath $v9Path -Algorithm SHA256).Hash.ToUpperInvariant()
$report=@(
  'P01-T07B2 W2D PRIVATE SANITIZED EVIDENCE SUMMARY',
  'Classification: INTERNAL_FOUNDATION / SYNTHETIC DEVELOPMENT ONLY',
  'Prepared_At_IST: '+(Get-Date -Format 'yyyy-MM-ddTHH:mm:ss'),
  'Git_Commit: '+$commit,
  'V9_Migration_GitBlob: 3a1e2b717576ccd84f0de058bb0a83786c517d32',
  'V9_Migration_SHA256: '+$v9Sha,
  'W1_Backup_SHA256: '+$backupSha,
  'W1_Backup_Bytes: '+$bytes,
  'W1_Backup_Stored_Separately: YES - NOT ARCHIVED',
  'W1_Baseline_Table_Counts: 25',
  'Original_Flyway_Version_Current: 8 - LIVE_READ_ONLY_CHECK',
  'Disposable_Flyway_Version_Current: 9 - LIVE_READ_ONLY_CHECK',
  'Disposable_Container_Name: '+$RehearsalContainer,
  'Disposable_Network_Name: '+$RehearsalNetwork,
  'W0_Preflight: OWNER-REPORTED_PASS (not independently re-attested here)',
  'W1_Backup: HASH_AND_SIZE_REVERIFIED; earlier restore PASS owner-reported',
  'W2A_Restored_V8: OWNER-REPORTED_PASS',
  'W2B_V9_ReadOnly_Acceptance: OWNER-REPORTED_PASS',
  'W2C_Synthetic_Isolation_Rollback: OWNER-REPORTED_PASS',
  'W2C_Transaction_Scenarios: OUTPUT_NOT_IN_THIS_ARCHIVE; see Issue 31 owner attestation',
  'W2D_Archive: SANITIZED_REPORTS_ONLY; no dump, env, token, log, SQL data',
  'W3_Owner_GO: NOT_GRANTED',
  'W4_Original_Apply: BLOCKED',
  'PR30_Merge_Approval_Provenance: UNRESOLVED - requires owner review',
  'PR32_Merge: NOT_AUTHORIZED',
  'Original_Database_Modified_By_W2D: NO'
)
[IO.File]::WriteAllLines($summary,[string[]]$report,[Text.Encoding]::UTF8)
$normalized | Sort-Object Table | Export-Csv -LiteralPath $counts -NoTypeInformation -Encoding UTF8
$entries=@()
foreach ($file in @($summary,$counts)) {
  $entries += [pscustomobject][ordered]@{
    Filename=[IO.Path]::GetFileName($file)
    Bytes=[long](Get-Item -LiteralPath $file).Length
    SHA256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToUpperInvariant()
  }
}
$entries | Export-Csv -LiteralPath $manifest -NoTypeInformation -Encoding UTF8
Compress-Archive -LiteralPath @($summary,$counts,$manifest) -DestinationPath $zipPath -CompressionLevel Optimal -ErrorAction Stop

# Check ZIP has exactly the intended three sanitized artifacts.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($zipPath)
try {
  $archiveFiles=@($archive.Entries | Where-Object { $_.Name -ne '' })
  $names=@($archiveFiles | ForEach-Object { $_.Name } | Sort-Object)
  $expectedNames=@('01_W2D_SANITIZED_SUMMARY.txt','02_V8_TABLE_COUNTS_SANITIZED.csv','03_SHA256_EVIDENCE_MANIFEST.csv')
  if ($archiveFiles.Count -ne 3) { Stop-Gate 'Unexpected ZIP entries' }
  if (($names -join '|') -cne ($expectedNames -join '|')) { Stop-Gate 'ZIP includes an unapproved file' }
  foreach ($e in $archiveFiles) {
    if ($e.Length -le 0) { Stop-Gate ('Empty evidence entry: '+$e.Name) }
  }
}
finally {
  $archive.Dispose()
}
$zipSha=(Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToUpperInvariant()
Write-Host ('W2D_ARCHIVE_PATH: '+$zipPath)
Write-Host ('W2D_ARCHIVE_SHA256: '+$zipSha)
Write-Host 'W2D_ARCHIVED_FILES: 3'
Write-Host 'W2D_DUMP_IN_ARCHIVE: NO'
Write-Host 'W2D_PRIVATE_W1_BACKUP_RETAINED: YES'
Write-Host 'W2D_SANITIZED_MANIFEST: PASS'
Write-Host 'ORIGINAL_FLYWAY_VERSION: 8'
Write-Host 'W2_FLYWAY_VERSION: 9'
Write-Host 'W3_OWNER_GO: NOT_GRANTED'
Write-Host 'W4_ORIGINAL_MIGRATION: BLOCKED'
Write-Host 'FINAL_STATUS: W2D_PRIVATE_SANITIZED_ARCHIVE_CREATED'
