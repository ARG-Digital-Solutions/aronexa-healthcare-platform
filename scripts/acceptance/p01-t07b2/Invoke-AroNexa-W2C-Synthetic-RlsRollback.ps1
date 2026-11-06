# P01-T07-B2 W2C: synthetic SQL policy scenarios in restored disposable V9.
# One test transaction ROLLBACK, no original DB write. Windows PowerShell 5.1.
# Review locally before execution. SQL runs only against verified W2 container.
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
function Stop-Gate([string]$message) { throw ('STOP: '+$message) }
function Exec([string]$program,[string[]]$argv) {
  $output=& $program @argv
  if ($LASTEXITCODE -ne 0) { Stop-Gate ('Read-only precheck failed: '+$program) }
  return (($output | Out-String).Trim())
}
function Q([string]$container,[string]$sql) {
  return (Exec 'docker.exe' @('exec',$container,'psql','-X','-At',
    '-v','ON_ERROR_STOP=1','-U','aronexa_dev','-d','aronexa_dev','-c',$sql))
}
function Equal([string]$label,[string]$actual,[string]$expected) {
  if ($actual -cne $expected) { Stop-Gate ($label+': expected '+$expected+', observed '+$actual) }
}
if (-not $ConfirmSyntheticDisposableOnly) { Stop-Gate 'Synthetic disposable confirmation required' }
if ($RehearsalContainer -notmatch '^aronexa-w2-pg17-(\d{8}_\d{6}_[0-9a-fA-F]{8})$') {
  Stop-Gate 'Not an approved disposable name'
}
$runId=$Matches[1]
Equal 'W2C network pair' $RehearsalNetwork ('aronexa-w2-v9-'+$runId)
$repo=Exec 'git.exe' @('-C',$RepoPath,'rev-parse','--show-toplevel')
Equal 'Git branch' (Exec 'git.exe' @('-C',$repo,'branch','--show-current')) 'feature/p01-t07b-patient-onboarding'
if (Exec 'git.exe' @('-C',$repo,'status','--porcelain')) { Stop-Gate 'Dirty source checkout' }
Equal 'Reviewed migration Git blob' (Exec 'git.exe' @('-C',$repo,'rev-parse','HEAD:backend/src/main/resources/db/migration/V9__patient_registration_read_boundary.sql')) '3a1e2b717576ccd84f0de058bb0a83786c517d32'
$sqlPath=Join-Path $repo 'scripts\acceptance\p01-t07b2\W2C_Disposable_Patient_RLS_Transaction_Scenarios.sql'
$postCheck=Join-Path $repo 'scripts\acceptance\p01-t07b2\Invoke-AroNexa-W2B-ReadOnly-V9Acceptance.ps1'
if (-not (Test-Path -LiteralPath $sqlPath -PathType Leaf)) { Stop-Gate 'W2C SQL not found' }
if (-not (Test-Path -LiteralPath $postCheck -PathType Leaf)) { Stop-Gate 'W2B verifier not found' }
if (-not (Test-Path -LiteralPath $BackupFile -PathType Leaf)) { Stop-Gate 'Verified backup missing' }
if (-not (Test-Path -LiteralPath $CountsFile -PathType Leaf)) { Stop-Gate 'Original 25-table inventory missing' }
Equal 'Original backup bytes' ([string](Get-Item -LiteralPath $BackupFile).Length) '92567'
Equal 'Original backup SHA256' (Get-FileHash -LiteralPath $BackupFile -Algorithm SHA256).Hash.ToUpperInvariant() '153842CCC811A64729F2DBA0743515A6C24E5A20AFB1D054AF054FECB36C3CAB'
if (@(Import-Csv -LiteralPath $CountsFile).Count -ne 25) { Stop-Gate 'Baseline table inventory count differs' }
$ids=Exec 'docker.exe' @('ps','--filter','label=com.docker.compose.project=aronexa-dev',
  '--filter','label=com.docker.compose.service=postgres','--format','{{.ID}}')
$sources=@($ids -split '\r?\n' | Where-Object { $_ -match '^[0-9a-f]{12,64}$' })
if ($sources.Count -ne 1) { Stop-Gate 'Original PostgreSQL container not uniquely identified' }
$source=$sources[0]
if ((Exec 'docker.exe' @('inspect','--format','{{.Id}}',$source)) -eq
    (Exec 'docker.exe' @('inspect','--format','{{.Id}}',$RehearsalContainer))) {
  Stop-Gate 'Refuse original DB target'
}
Equal 'Disposable running' (Exec 'docker.exe' @('inspect','--format','{{.State.Running}}',$RehearsalContainer)) 'true'
Equal 'Private network internal' (Exec 'docker.exe' @('network','inspect','--format','{{.Internal}}',$RehearsalNetwork)) 'true'
$ports=Exec 'docker.exe' @('inspect','--format','{{json .HostConfig.PortBindings}}',$RehearsalContainer)
if ($ports -ne 'null' -and $ports -ne '{}') { Stop-Gate 'Unexpected host port bindings' }
$mounts=Exec 'docker.exe' @('inspect','--format','{{json .Mounts}}',$RehearsalContainer)
if ($mounts -match 'aronexa_dev_postgres_data') { Stop-Gate 'Original DB volume detected' }
$net=Exec 'docker.exe' @('inspect','--format','{{json .NetworkSettings.Networks}}',$RehearsalContainer)
$networkKeys=@(($net | ConvertFrom-Json).PSObject.Properties.Name)
if ($networkKeys.Count -ne 1 -or $networkKeys[0] -ne $RehearsalNetwork) { Stop-Gate 'Unexpected network attachment' }
$labels=Exec 'docker.exe' @('inspect','--format','{{json .Config.Labels}}',$RehearsalContainer)
if (-not $labels.Contains($runId)) { Stop-Gate 'Missing W2A disposable label' }
Equal 'Original Flyway before W2C' (Q $source 'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1') '8'
Equal 'Disposable Flyway before W2C' (Q $RehearsalContainer 'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1') '9'
Equal 'No existing registration fixtures' (Q $RehearsalContainer 'SELECT count(*) FROM public.patient_registration') '0'
Equal 'Empty access audit' (Q $RehearsalContainer 'SELECT count(*) FROM public.patient_registration_access_audit') '0'
Equal 'No failed migrations' (Q $RehearsalContainer 'SELECT count(*) FROM public.flyway_schema_history WHERE NOT success') '0'
Equal 'Patient role restricted' (Q $RehearsalContainer "SELECT count(*) FROM pg_roles WHERE rolname='aronexa_patient_runtime' AND rolcanlogin AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit") '1'
Write-Host 'W2C_DISPOSABLE_ISOLATION: PASS'

# The SQL fixture is the ONLY write and must end with ROLLBACK.
# On unexpected SQL failure, ON_ERROR_STOP closes the connection, also rolling back.
$sql=Get-Content -LiteralPath $sqlPath -Raw -Encoding UTF8
$priorErrorAction=$ErrorActionPreference
$ErrorActionPreference='Continue'
try {
  $out=@($sql | & docker.exe exec -i $RehearsalContainer psql -q -X -v ON_ERROR_STOP=1 -U aronexa_dev -d aronexa_dev -f - 2>&1)
  $exitCode=$LASTEXITCODE
}
finally {
  $ErrorActionPreference=$priorErrorAction
  $sql=$null
}
$text=(($out | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine)
if ($exitCode -ne 0) {
  Write-Host ($text -split '\r?\n' | Select-Object -Last 12 | Out-String)
  Stop-Gate ('W2C synthetic test failed exit '+$exitCode+'; preserve state, do not blindly rerun')
}
$markers=@(
  'PASS: W2C_NO_CONTEXT_DENIED',
  'PASS: W2C_TENANT_ONLY_DENIED',
  'PASS: W2C_ASSIGNED_BRANCH_VISIBLE',
  'PASS: W2C_OTHER_MEMBERSHIP_ISOLATED',
  'PASS: W2C_UNASSIGNED_MEMBERSHIP_DENIED',
  'PASS: W2C_CROSS_TENANT_DENIED',
  'PASS: W2C_TENANT_B_ISOLATED',
  'PASS: W2C_MASTER_PATIENT_READ_DENIED',
  'PASS: W2C_REGISTRATION_INSERT_DENIED',
  'PASS: W2C_REGISTRATION_UPDATE_DENIED',
  'PASS: W2C_REGISTRATION_DELETE_DENIED',
  'PASS: W2C_ACCESS_AUDIT_READ_DENIED',
  'PASS: W2C_SAME_CONNECTION_NEXT_TRANSACTION_CONTEXT_CLEARED',
  'FINAL_STATUS: W2C_SYNTHETIC_POLICY_TRANSACTION_ROLLBACK_PASS'
)
foreach ($marker in $markers) {
  if (-not $text.Contains($marker)) { Stop-Gate ('Missing W2C scenario evidence: '+$marker) }
}
Write-Host 'W2C_SYNTHETIC_SQL_NEGATIVE_SCENARIOS: PASS'
Write-Host 'W2C_ROLLBACK_AND_TRANSACTION_RESET: PASS'

# Verify that all original rows, RLS grants, audit rows and schema remained intact
# using the already-reviewed read-only W2B acceptance script.
$verifyArgs=@('-NoProfile','-ExecutionPolicy','Bypass','-File',$postCheck,
  '-RehearsalContainer',$RehearsalContainer,
  '-RehearsalNetwork',$RehearsalNetwork,
  '-BackupFile',$BackupFile,'-CountsFile',$CountsFile,'-RepoPath',$repo,
  '-ConfirmSyntheticDisposableOnly')
& powershell.exe @verifyArgs
$postExitCode=$LASTEXITCODE
if ($postExitCode -ne 0) { Stop-Gate ('W2B read-only postcheck failed after W2C: '+$postExitCode) }
Equal 'Original Flyway after W2C' (Q $source 'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1') '8'
Equal 'Disposable Flyway after W2C' (Q $RehearsalContainer 'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1') '9'
Write-Host 'W2C_POST_ROLLBACK_V9_INTEGRITY: PASS'
Write-Host 'ORIGINAL_FLYWAY_VERSION: 8'
Write-Host 'W2_FLYWAY_VERSION: 9'
Write-Host 'ORIGINAL_DATABASE_MODIFIED_BY_W2C: NO'
Write-Host 'FINAL_STATUS: W2C_SYNTHETIC_ISOLATION_AND_ROLLBACK_PASS'
