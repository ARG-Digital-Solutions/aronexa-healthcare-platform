# P01-T07-B2 W2B: READ-ONLY post-V9 acceptance (no Flyway migrate or role changes).
# Owner must review before local Windows PowerShell 5.1 execution.
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
  if ($LASTEXITCODE -ne 0) { Stop-Gate ('Read-only command failed: '+$program) }
  return (($output | Out-String).Trim())
}
function Q([string]$container,[string]$sql) {
  return (Exec 'docker.exe' @('exec',$container,'psql','-X','-At','-v','ON_ERROR_STOP=1',
    '-U','aronexa_dev','-d','aronexa_dev','-c',$sql))
}
function Equal([string]$label,[string]$actual,[string]$expected) {
  if ($actual -cne $expected) { Stop-Gate ($label+': expected '+$expected+', observed '+$actual) }
}
if (-not $ConfirmSyntheticDisposableOnly) { Stop-Gate 'Synthetic/disposable confirmation required' }
if ($RehearsalContainer -notmatch '^aronexa-w2-pg17-(\d{8}_\d{6}_[a-fA-F0-9]{8})$') {
  Stop-Gate 'Unexpected disposable container'
}
$runId=$Matches[1]
Equal 'Disposable network pair' $RehearsalNetwork ('aronexa-w2-v9-'+$runId)
$repo=Exec 'git.exe' @('-C',$RepoPath,'rev-parse','--show-toplevel')
Equal 'Branch' (Exec 'git.exe' @('-C',$repo,'branch','--show-current')) 'feature/p01-t07b-patient-onboarding'
if (Exec 'git.exe' @('-C',$repo,'status','--porcelain')) { Stop-Gate 'Git working tree is dirty' }
Equal 'V9 source blob' (Exec 'git.exe' @('-C',$repo,'rev-parse',
  'HEAD:backend/src/main/resources/db/migration/V9__patient_registration_read_boundary.sql')) '3a1e2b717576ccd84f0de058bb0a83786c517d32'
if (-not (Test-Path -LiteralPath $BackupFile -PathType Leaf)) { Stop-Gate 'W1 backup missing' }
if (-not (Test-Path -LiteralPath $CountsFile -PathType Leaf)) { Stop-Gate 'W1 table count inventory missing' }
if ((Get-Item -LiteralPath $BackupFile).Length -ne 92567) { Stop-Gate 'Backup size changed' }
$hash=(Get-FileHash -LiteralPath $BackupFile -Algorithm SHA256).Hash.ToUpperInvariant()
Equal 'Backup checksum' $hash '153842CCC811A64729F2DBA0743515A6C24E5A20AFB1D054AF054FECB36C3CAB'
$rows=@(Import-Csv -LiteralPath $CountsFile)
if ($rows.Count -ne 25) { Stop-Gate 'Expected 25 original V8 tables' }
$expected=@{}
foreach ($row in $rows) {
  $t=$row.Table
  if (-not $t -or $t -notmatch '^[a-z_][a-z0-9_]*$' -or $expected.ContainsKey($t)) { Stop-Gate 'Invalid table name in CSV' }
  if ($null -eq $row.Count -or $row.Count -notmatch '^\d+$') { Stop-Gate ('Invalid baseline count for '+$t) }
  $expected[$t]=[long]$row.Count
}
foreach ($t in @('flyway_schema_history','auth_permission','module_registry','module_configuration_audit')) {
  if (-not $expected.ContainsKey($t)) { Stop-Gate ('Required original table missing: '+$t) }
}
$ids=Exec 'docker.exe' @('ps','--filter','label=com.docker.compose.project=aronexa-dev',
  '--filter','label=com.docker.compose.service=postgres','--format','{{.ID}}')
$sources=@($ids -split '\r?\n' | Where-Object { $_ -match '^[a-f0-9]{12,64}$' })
if ($sources.Count -ne 1) { Stop-Gate 'Original source ambiguous' }
$source=$sources[0]
if ((Exec 'docker.exe' @('inspect','--format','{{.Id}}',$source)) -eq
    (Exec 'docker.exe' @('inspect','--format','{{.Id}}',$RehearsalContainer))) {
  Stop-Gate 'Disposable container is the original source'
}
Equal 'Disposable running' (Exec 'docker.exe' @('inspect','--format','{{.State.Running}}',$RehearsalContainer)) 'true'
Equal 'Internal-only network' (Exec 'docker.exe' @('network','inspect','--format','{{.Internal}}',$RehearsalNetwork)) 'true'
$ports=Exec 'docker.exe' @('inspect','--format','{{json .HostConfig.PortBindings}}',$RehearsalContainer)
if ($ports -ne 'null' -and $ports -ne '{}') { Stop-Gate 'Unexpected host port bindings' }
$mounts=Exec 'docker.exe' @('inspect','--format','{{json .Mounts}}',$RehearsalContainer)
if ($mounts -match 'aronexa_dev_postgres_data') { Stop-Gate 'Original volume mounted in disposable container' }
$net=Exec 'docker.exe' @('inspect','--format','{{json .NetworkSettings.Networks}}',$RehearsalContainer)
$networkKeys=@(($net | ConvertFrom-Json).PSObject.Properties.Name)
if ($networkKeys.Count -ne 1 -or $networkKeys[0] -ne $RehearsalNetwork) { Stop-Gate 'Unexpected target networks' }

Equal 'Original Flyway' (Q $source 'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1') '8'
Equal 'Disposable Flyway' (Q $RehearsalContainer 'SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1') '9'
Equal 'Failed Flyway migrations' (Q $RehearsalContainer 'SELECT count(*) FROM public.flyway_schema_history WHERE NOT success') '0'
$oldHistory=Q $source "SELECT version||':'||checksum::text FROM public.flyway_schema_history WHERE success ORDER BY installed_rank"
$newHistory=Q $RehearsalContainer "SELECT version||':'||checksum::text FROM public.flyway_schema_history WHERE success AND version<>'9' ORDER BY installed_rank"
Equal 'V1-V8 checksums preserved' $newHistory $oldHistory
Equal 'Post-V9 table inventory' (Q $RehearsalContainer "SELECT count(*) FROM pg_tables WHERE schemaname='public'") '26'
foreach ($t in ($expected.Keys | Sort-Object)) {
  $delta=0
  if ($t -eq 'module_registry') { $delta=1 }
  if ($t -eq 'auth_permission') { $delta=6 }
  if ($t -eq 'flyway_schema_history') { $delta=1 }
  if ($t -eq 'module_configuration_audit') { $delta=1 }
  $value=Q $RehearsalContainer ('SELECT count(*) FROM public.'+$t)
  if ($value -notmatch '^\d+$' -or [long]$value -ne ($expected[$t]+$delta)) {
    Stop-Gate ('Unexpected V9 count for '+$t)
  }
}
# Require exactly one audit event attributable to the V9 module INSERT.
# Also require every pre-existing audit entry to match the V8 source snapshot.
$triggerSql = "SELECT CASE WHEN count(*)=1 THEN 't' ELSE 'f' END FROM pg_trigger WHERE tgrelid='public.module_registry'::regclass AND tgname='module_registry_audit_change' AND NOT tgisinternal AND (tgtype & 4)<>0 AND position('module_configuration_audit' in lower(pg_get_functiondef(tgfoid)))>0"
Equal 'Module registry INSERT audit trigger verified' (Q $RehearsalContainer $triggerSql) 't'
$addedAuditSql = "SELECT count(*) FROM public.module_configuration_audit a WHERE to_jsonb(a)::text ILIKE '%PATIENT_REGISTRATION%'"
$beforeMatch = Q $source $addedAuditSql
$afterMatch = Q $RehearsalContainer $addedAuditSql
if ($beforeMatch -notmatch '^\\d+
Equal 'Restricted patient role' (Q $RehearsalContainer "SELECT count(*) FROM pg_roles WHERE rolname='aronexa_patient_runtime' AND rolcanlogin AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit") '1'
Equal 'Registration module disabled' (Q $RehearsalContainer "SELECT CASE WHEN count(*)=1 AND bool_and(NOT implemented AND NOT released) THEN 't' ELSE 'f' END FROM public.module_registry WHERE code='PATIENT_REGISTRATION'") 't'
Equal 'No patient entitlement' (Q $RehearsalContainer "SELECT count(*) FROM public.tenant_module_state WHERE module_code='PATIENT_REGISTRATION'") '0'
$codes="('PATIENT_REGISTER','PATIENT_REGISTRATION_READ','PATIENT_CANDIDATE_SEARCH','PATIENT_LINK_EXISTING','PATIENT_CONTACT_VERIFY','PATIENT_GUARDIAN_REVIEW')"
Equal 'Six ungranted patient permission definitions' (Q $RehearsalContainer ("SELECT count(*) FROM public.auth_permission WHERE code IN "+$codes)) '6'
Equal 'No staff patient permission grants' (Q $RehearsalContainer ("SELECT count(*) FROM public.auth_role_permission WHERE permission_code IN "+$codes)) '0'
Equal 'Four RLS-enabled patient tables' (Q $RehearsalContainer "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('patient_registration','org_membership_branch_scope','tenant_module_state','patient_registration_access_audit') AND c.relrowsecurity") '4'
Equal 'Four required patient policies' (Q $RehearsalContainer "SELECT count(*) FROM pg_policies WHERE schemaname='public' AND policyname IN ('patient_runtime_branch_membership_scope','patient_runtime_entitlement_scope','patient_runtime_registration_read','patient_runtime_registration_access_log')") '4'
Equal 'Audit FORCE RLS' (Q $RehearsalContainer "SELECT relforcerowsecurity FROM pg_class WHERE oid='public.patient_registration_access_audit'::regclass") 't'
Equal 'Audit immutable trigger' (Q $RehearsalContainer "SELECT count(*) FROM pg_trigger WHERE tgrelid='public.patient_registration_access_audit'::regclass AND tgname='patient_registration_access_audit_immutable' AND NOT tgisinternal") '1'
$allow=@(
  @('patient_registration','SELECT'),
  @('org_membership_branch_scope','SELECT'),
  @('module_registry','SELECT'),
  @('tenant_module_state','SELECT'),
  @('patient_registration_access_audit','INSERT')
)
foreach ($pair in $allow) {
  Equal ('Allowed patient role privilege '+($pair -join '/')) (Q $RehearsalContainer ("SELECT has_table_privilege('aronexa_patient_runtime','public."+$pair[0]+"','"+$pair[1]+"')")) 't'
}
$protected=@('patient','patient_identifier','patient_contact','patient_relationship','patient_identity_audit')
foreach ($t in $protected) {
  Equal ('Protected identity read '+$t) (Q $RehearsalContainer ("SELECT has_table_privilege('aronexa_patient_runtime','public."+$t+"','SELECT')")) 'f'
}
foreach ($priv in @('INSERT','UPDATE','DELETE')) {
  Equal ('Patient registration write blocked '+$priv) (Q $RehearsalContainer ("SELECT has_table_privilege('aronexa_patient_runtime','public.patient_registration','"+$priv+"')")) 'f'
}
foreach ($priv in @('SELECT','UPDATE','DELETE','TRUNCATE')) {
  Equal ('Audit mutation/read blocked '+$priv) (Q $RehearsalContainer ("SELECT has_table_privilege('aronexa_patient_runtime','public.patient_registration_access_audit','"+$priv+"')")) 'f'
}
foreach ($role in @('aronexa_app_runtime','aronexa_auth_runtime')) {
  foreach ($t in (@('patient_registration','patient_registration_access_audit')+$protected)) {
    Equal ('App/auth patient SELECT denied '+$role+'/'+$t) (Q $RehearsalContainer ("SELECT has_table_privilege('"+$role+"','public."+$t+"','SELECT')")) 'f'
  }
}
Write-Host ('BACKUP_SHA256: '+$hash)
Write-Host 'W2_ORIGINAL_25_TABLE_COUNTS: PASS'
Write-Host 'W2_EXPECTED_V9_ROW_DELTAS: PASS (+1 module, +6 permissions, +1 Flyway history, +1 module audit)'
Write-Host 'W2_ORIGINAL_V1_TO_V8_CHECKSUMS: PASS'
Write-Host 'W2_PATIENT_ROLE_RLS_MODULE_AUDIT_GRANTS: PASS'
Write-Host 'ORIGINAL_FLYWAY_VERSION: 8'
Write-Host 'W2_FLYWAY_VERSION: 9'
Write-Host 'ORIGINAL_DATABASE_MODIFIED_BY_VERIFICATION: NO'
Write-Host 'FINAL_STATUS: W2B_READ_ONLY_V9_ACCEPTANCE_PASS'
 -or $afterMatch -notmatch '^\\d+
Equal 'Restricted patient role' (Q $RehearsalContainer "SELECT count(*) FROM pg_roles WHERE rolname='aronexa_patient_runtime' AND rolcanlogin AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit") '1'
Equal 'Registration module disabled' (Q $RehearsalContainer "SELECT CASE WHEN count(*)=1 AND bool_and(NOT implemented AND NOT released) THEN 't' ELSE 'f' END FROM public.module_registry WHERE code='PATIENT_REGISTRATION'") 't'
Equal 'No patient entitlement' (Q $RehearsalContainer "SELECT count(*) FROM public.tenant_module_state WHERE module_code='PATIENT_REGISTRATION'") '0'
$codes="('PATIENT_REGISTER','PATIENT_REGISTRATION_READ','PATIENT_CANDIDATE_SEARCH','PATIENT_LINK_EXISTING','PATIENT_CONTACT_VERIFY','PATIENT_GUARDIAN_REVIEW')"
Equal 'Six ungranted patient permission definitions' (Q $RehearsalContainer ("SELECT count(*) FROM public.auth_permission WHERE code IN "+$codes)) '6'
Equal 'No staff patient permission grants' (Q $RehearsalContainer ("SELECT count(*) FROM public.auth_role_permission WHERE permission_code IN "+$codes)) '0'
Equal 'Four RLS-enabled patient tables' (Q $RehearsalContainer "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('patient_registration','org_membership_branch_scope','tenant_module_state','patient_registration_access_audit') AND c.relrowsecurity") '4'
Equal 'Four required patient policies' (Q $RehearsalContainer "SELECT count(*) FROM pg_policies WHERE schemaname='public' AND policyname IN ('patient_runtime_branch_membership_scope','patient_runtime_entitlement_scope','patient_runtime_registration_read','patient_runtime_registration_access_log')") '4'
Equal 'Audit FORCE RLS' (Q $RehearsalContainer "SELECT relforcerowsecurity FROM pg_class WHERE oid='public.patient_registration_access_audit'::regclass") 't'
Equal 'Audit immutable trigger' (Q $RehearsalContainer "SELECT count(*) FROM pg_trigger WHERE tgrelid='public.patient_registration_access_audit'::regclass AND tgname='patient_registration_access_audit_immutable' AND NOT tgisinternal") '1'
$allow=@(
  @('patient_registration','SELECT'),
  @('org_membership_branch_scope','SELECT'),
  @('module_registry','SELECT'),
  @('tenant_module_state','SELECT'),
  @('patient_registration_access_audit','INSERT')
)
foreach ($pair in $allow) {
  Equal ('Allowed patient role privilege '+($pair -join '/')) (Q $RehearsalContainer ("SELECT has_table_privilege('aronexa_patient_runtime','public."+$pair[0]+"','"+$pair[1]+"')")) 't'
}
$protected=@('patient','patient_identifier','patient_contact','patient_relationship','patient_identity_audit')
foreach ($t in $protected) {
  Equal ('Protected identity read '+$t) (Q $RehearsalContainer ("SELECT has_table_privilege('aronexa_patient_runtime','public."+$t+"','SELECT')")) 'f'
}
foreach ($priv in @('INSERT','UPDATE','DELETE')) {
  Equal ('Patient registration write blocked '+$priv) (Q $RehearsalContainer ("SELECT has_table_privilege('aronexa_patient_runtime','public.patient_registration','"+$priv+"')")) 'f'
}
foreach ($priv in @('SELECT','UPDATE','DELETE','TRUNCATE')) {
  Equal ('Audit mutation/read blocked '+$priv) (Q $RehearsalContainer ("SELECT has_table_privilege('aronexa_patient_runtime','public.patient_registration_access_audit','"+$priv+"')")) 'f'
}
foreach ($role in @('aronexa_app_runtime','aronexa_auth_runtime')) {
  foreach ($t in (@('patient_registration','patient_registration_access_audit')+$protected)) {
    Equal ('App/auth patient SELECT denied '+$role+'/'+$t) (Q $RehearsalContainer ("SELECT has_table_privilege('"+$role+"','public."+$t+"','SELECT')")) 'f'
  }
}
Write-Host ('BACKUP_SHA256: '+$hash)
Write-Host 'W2_ORIGINAL_25_TABLE_COUNTS: PASS'
Write-Host 'W2_EXPECTED_V9_ROW_DELTAS: PASS (+1 module, +6 permissions, +1 Flyway history)'
Write-Host 'W2_ORIGINAL_V1_TO_V8_CHECKSUMS: PASS'
Write-Host 'W2_PATIENT_ROLE_RLS_MODULE_AUDIT_GRANTS: PASS'
Write-Host 'ORIGINAL_FLYWAY_VERSION: 8'
Write-Host 'W2_FLYWAY_VERSION: 9'
Write-Host 'ORIGINAL_DATABASE_MODIFIED_BY_VERIFICATION: NO'
Write-Host 'FINAL_STATUS: W2B_READ_ONLY_V9_ACCEPTANCE_PASS'
) { Stop-Gate 'Audit attribution counts invalid' }
if (([long]$afterMatch - [long]$beforeMatch) -ne 1) {
  Stop-Gate 'V9 module audit row cannot be attributed to PATIENT_REGISTRATION'
}
# Do not emit audit data or identifiers. Compare existing audit rows by
# deterministic JSON fingerprints; refuse unexpected mutation/loss.
$fullAuditFingerprint = "SELECT md5(COALESCE(string_agg(to_jsonb(a)::text, '|' ORDER BY to_jsonb(a)::text),'')) FROM public.module_configuration_audit a"
$oldAuditFingerprint = Q $source $fullAuditFingerprint
$newAuditFingerprintSql = "SELECT md5(COALESCE(string_agg(to_jsonb(a)::text, '|' ORDER BY to_jsonb(a)::text),'')) FROM public.module_configuration_audit a WHERE to_jsonb(a)::text NOT ILIKE '%PATIENT_REGISTRATION%'"
$newAuditFingerprint = Q $RehearsalContainer $newAuditFingerprintSql
Equal 'V8 module audit entries preserved' $newAuditFingerprint $oldAuditFingerprint
Write-Host 'W2_MODULE_REGISTRY_AUDIT_DELTA: PASS (+1 attributed PATIENT_REGISTRATION row)'
Write-Host 'W2_MODULE_AUDIT_EXISTING_ROWS_PRESERVED: PASS'

Equal 'Empty access audit' (Q $RehearsalContainer 'SELECT count(*) FROM public.patient_registration_access_audit') '0'
Equal 'Restricted patient role' (Q $RehearsalContainer "SELECT count(*) FROM pg_roles WHERE rolname='aronexa_patient_runtime' AND rolcanlogin AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit") '1'
Equal 'Registration module disabled' (Q $RehearsalContainer "SELECT CASE WHEN count(*)=1 AND bool_and(NOT implemented AND NOT released) THEN 't' ELSE 'f' END FROM public.module_registry WHERE code='PATIENT_REGISTRATION'") 't'
Equal 'No patient entitlement' (Q $RehearsalContainer "SELECT count(*) FROM public.tenant_module_state WHERE module_code='PATIENT_REGISTRATION'") '0'
$codes="('PATIENT_REGISTER','PATIENT_REGISTRATION_READ','PATIENT_CANDIDATE_SEARCH','PATIENT_LINK_EXISTING','PATIENT_CONTACT_VERIFY','PATIENT_GUARDIAN_REVIEW')"
Equal 'Six ungranted patient permission definitions' (Q $RehearsalContainer ("SELECT count(*) FROM public.auth_permission WHERE code IN "+$codes)) '6'
Equal 'No staff patient permission grants' (Q $RehearsalContainer ("SELECT count(*) FROM public.auth_role_permission WHERE permission_code IN "+$codes)) '0'
Equal 'Four RLS-enabled patient tables' (Q $RehearsalContainer "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('patient_registration','org_membership_branch_scope','tenant_module_state','patient_registration_access_audit') AND c.relrowsecurity") '4'
Equal 'Four required patient policies' (Q $RehearsalContainer "SELECT count(*) FROM pg_policies WHERE schemaname='public' AND policyname IN ('patient_runtime_branch_membership_scope','patient_runtime_entitlement_scope','patient_runtime_registration_read','patient_runtime_registration_access_log')") '4'
Equal 'Audit FORCE RLS' (Q $RehearsalContainer "SELECT relforcerowsecurity FROM pg_class WHERE oid='public.patient_registration_access_audit'::regclass") 't'
Equal 'Audit immutable trigger' (Q $RehearsalContainer "SELECT count(*) FROM pg_trigger WHERE tgrelid='public.patient_registration_access_audit'::regclass AND tgname='patient_registration_access_audit_immutable' AND NOT tgisinternal") '1'
$allow=@(
  @('patient_registration','SELECT'),
  @('org_membership_branch_scope','SELECT'),
  @('module_registry','SELECT'),
  @('tenant_module_state','SELECT'),
  @('patient_registration_access_audit','INSERT')
)
foreach ($pair in $allow) {
  Equal ('Allowed patient role privilege '+($pair -join '/')) (Q $RehearsalContainer ("SELECT has_table_privilege('aronexa_patient_runtime','public."+$pair[0]+"','"+$pair[1]+"')")) 't'
}
$protected=@('patient','patient_identifier','patient_contact','patient_relationship','patient_identity_audit')
foreach ($t in $protected) {
  Equal ('Protected identity read '+$t) (Q $RehearsalContainer ("SELECT has_table_privilege('aronexa_patient_runtime','public."+$t+"','SELECT')")) 'f'
}
foreach ($priv in @('INSERT','UPDATE','DELETE')) {
  Equal ('Patient registration write blocked '+$priv) (Q $RehearsalContainer ("SELECT has_table_privilege('aronexa_patient_runtime','public.patient_registration','"+$priv+"')")) 'f'
}
foreach ($priv in @('SELECT','UPDATE','DELETE','TRUNCATE')) {
  Equal ('Audit mutation/read blocked '+$priv) (Q $RehearsalContainer ("SELECT has_table_privilege('aronexa_patient_runtime','public.patient_registration_access_audit','"+$priv+"')")) 'f'
}
foreach ($role in @('aronexa_app_runtime','aronexa_auth_runtime')) {
  foreach ($t in (@('patient_registration','patient_registration_access_audit')+$protected)) {
    Equal ('App/auth patient SELECT denied '+$role+'/'+$t) (Q $RehearsalContainer ("SELECT has_table_privilege('"+$role+"','public."+$t+"','SELECT')")) 'f'
  }
}
Write-Host ('BACKUP_SHA256: '+$hash)
Write-Host 'W2_ORIGINAL_25_TABLE_COUNTS: PASS'
Write-Host 'W2_EXPECTED_V9_ROW_DELTAS: PASS (+1 module, +6 permissions, +1 Flyway history)'
Write-Host 'W2_ORIGINAL_V1_TO_V8_CHECKSUMS: PASS'
Write-Host 'W2_PATIENT_ROLE_RLS_MODULE_AUDIT_GRANTS: PASS'
Write-Host 'ORIGINAL_FLYWAY_VERSION: 8'
Write-Host 'W2_FLYWAY_VERSION: 9'
Write-Host 'ORIGINAL_DATABASE_MODIFIED_BY_VERIFICATION: NO'
Write-Host 'FINAL_STATUS: W2B_READ_ONLY_V9_ACCEPTANCE_PASS'
