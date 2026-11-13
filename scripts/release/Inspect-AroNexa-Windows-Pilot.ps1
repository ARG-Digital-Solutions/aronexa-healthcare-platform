# R1-09 READ-ONLY Windows pilot readiness inspection. Never installs,
# migrates, provisions, packages, runs PostgreSQL, signs licenses or deploys.
# A successful source inspection is NOT authorization for real patient use.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit,
 [switch]$ReportOnly,
 [switch]$AsJson
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$actual=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $actual -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'RELEASE PREFLIGHT STOP: exact Git commit mismatch.'
}
$tracked=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0){throw 'RELEASE PREFLIGHT STOP: Git status failed.'}
$clean=(@($tracked | Where-Object {$_.Trim().Length -gt 0}).Count -eq 0)

$mainJava=Join-Path $repo 'backend\src\main\java\com\argdigital\aronexa'
$migrations=Join-Path $repo 'backend\src\main\resources\db\migration'
$testSql=Join-Path $repo 'backend\src\test\resources\db\test'
$versions=@(Get-ChildItem -LiteralPath $migrations -Filter 'V*__*.sql' -File |
 ForEach-Object {if($_.Name -match '^V(\d+)__'){[int]$Matches[1]}})
$maxVersion=if($versions.Count -gt 0){
 [int](($versions | Measure-Object -Maximum).Maximum)
}else{0}
$testProofs=@(4..8 | ForEach-Object {
 $n=$_
 [pscustomobject]@{
  increment=('R1-{0:d2}' -f $n)
  present=(Test-Path -LiteralPath (Join-Path $testSql ('appointment-candidate-r1{0:d2}.sql' -f $n)) -PathType Leaf)
 }
})

$checks=[System.Collections.Generic.List[object]]::new()
function Add-Gate([string]$id,[string]$title,[string]$status,[string]$evidence,[string]$next) {
 [void]$checks.Add([pscustomobject]@{
  id=$id;title=$title;status=$status;evidence=$evidence;nextAction=$next
 })
}
Add-Gate 'SRC-01' 'Exact source and clean tracked tree' $(if($clean){'SOURCE_OBSERVED'}else{'BLOCKED'}) "HEAD=$actual, trackedClean=$clean" 'Use clean exact-SHA branch for release evidence; preserve other working trees.'
Add-Gate 'INT-01' 'End-to-end real OPD runtime' 'BLOCKED' ('Production source folders appointment={0}, prescription={1}, billing={2}. Test candidate handlers live in src/test.' -f (Test-Path -LiteralPath (Join-Path $mainJava 'appointment') -PathType Container),(Test-Path -LiteralPath (Join-Path $mainJava 'prescription') -PathType Container),(Test-Path -LiteralPath (Join-Path $mainJava 'billing') -PathType Container)) 'Integrate approved registration, appointment, encounter, Rx and finance endpoints with real restart persistence.'
Add-Gate 'DB-01' 'Approved production schema and restricted SQL writer' 'BLOCKED' ("Current Flyway maximum V$maxVersion. Isolated R1-04..08 SQL present: {0}/5." -f @($testProofs | Where-Object {$_.present}).Count) 'Approve forward migrations, scoped RLS, least-privileged writer and cross-tenant adversarial tests.'
Add-Gate 'SEC-01' 'Independent clinical and finance/security assessment' 'BLOCKED' 'No independent security or clinical production authorization can be inferred from repository code.' 'Obtain actual independent review of session, tenant, clinical order and financial record integrity.'
Add-Gate 'WIN-01' 'Signed Windows installer and first run' 'BLOCKED' ('Current branch windows-clinic builder present: {0}. Separate app-image proofs are not customer installers.' -f (Test-Path -LiteralPath (Join-Path $repo 'scripts\windows-clinic') -PathType Container)) 'Reconcile existing Windows app-image prototypes and prove signed install, first-run wizard and local service on clean Windows VM.'
Add-Gate 'WIN-02' 'Encrypted nonempty PostgreSQL backup and restore' 'BLOCKED' 'Separate crypto proof does not certify backed-up clinic PostgreSQL can be restored.' 'Implement consistent encrypted DB backup, key custody, retention and second-PC restore identity/audit checks.'
Add-Gate 'WIN-03' 'Offline signed license and care continuity' 'BLOCKED' 'Standalone Ed25519 example is not production license issuance, trust custody or runtime continuity.' 'Prove issuer trust, clock manipulation defense, signed renewal and no lockout of historical clinical records.'
Add-Gate 'WIN-04' 'Upgrade, service restart and recovery rehearsal' 'BLOCKED' 'No executed installed-device power-loss/failed-upgrade/disaster-recovery report verified.' 'Rehearse PostgreSQL service restart, low disk, failed upgrade, rollback and data-preserving uninstall in isolated Windows VM.'
Add-Gate 'UAT-01' 'Independent clinical usability and accessibility acceptance' 'BLOCKED' 'Synthetic browser tests with mocked APIs are not clinician signoff or patient workflow UAT.' 'Run receptionist/doctor end-to-end fictional records, role checks and keyboard/NVDA checks on installed candidate.'
Add-Gate 'REL-01' 'Customer-specific owner release GO' 'BLOCKED' 'No customer install, financial settlement, external clinical authorization or owner release GO performed.' 'Only after all gates, record explicit owner decision, artifact hash, known issues and support/recovery procedures.'

$blocked=@($checks | Where-Object {$_.status -eq 'BLOCKED'})
$report=[ordered]@{
 schemaVersion=1
 product='AroNexa Clinic Edition - Windows offline pilot'
 sourceCommit=$actual
 inspection='READ_ONLY_SOURCE_INVENTORY'
 candidateEvidence='R1-08 owner-reported 277/277 backend and 10/10 mocked browser PASS on accepted prior commit; not real-world release evidence.'
 trackedTreeClean=$clean
 migrationMaximum=$maxVersion
 testOnlyPg17Proofs=$testProofs
 releaseDecision='NO_GO'
 blockedCount=$blocked.Count
 gates=@($checks)
 limitations=@(
  'No database connection, clinical data, secrets, deployment or installer execution.',
  'Source presence alone does not establish security, restoration, signed packaging or UAT.',
  'Test-only HTTP/SQL candidates cannot be promoted directly to production.'
 )
}
if($AsJson){
 $report | ConvertTo-Json -Depth 7
}else{
 Write-Host ("AroNexa Windows pilot NO-GO: {0} blocked gates" -f $blocked.Count) -ForegroundColor Yellow
 Write-Host "Source SHA: $actual"
 foreach($g in $checks){
  Write-Host ("{0}: {1} - {2}" -f $g.id,$g.status,$g.title)
  if($g.status -eq 'BLOCKED'){Write-Host ("    Next: " + $g.nextAction)}
 }
 Write-Host 'Read-only inspection finished. No clinical deployment authorized.'
}
if(-not $ReportOnly){exit 3}
