# R1-10B3B: exact-SHA revocation races + Spring context restart proof.
# REVIEW-ONLY schema overlay runs ONLY in automatically disposable PG17.
# No production Flyway, Windows/original database, new clinic HTTP bean,
# financial transaction, patient contact, deploy, installer or merge.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$actual=(& git -C $root rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $actual -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'R1-10B3B exact Git SHA mismatch; STOP.'
}
$changes=@(& git -C $root status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or
   @($changes | Where-Object {$_.Trim().Length -gt 0}).Count -gt 0){
 throw 'R1-10B3B tracked tree not clean; STOP.'
}
$overlay=Join-Path $root 'deploy\opd\review-only\R110B3B__ordered_session_gate_candidate.sql'
$tests=Join-Path $root 'backend\src\test\java\com\argdigital\aronexa\opd\OpdRestrictedBookingPostgresTests.java'
$restart=Join-Path $root 'backend\src\test\java\com\argdigital\aronexa\opd\OpdDisposableRestartDatabaseConfiguration.java'
$active=Join-Path $root 'backend\src\main\resources\db\migration'
foreach($file in @($overlay,$tests,$restart)){
 if(-not(Test-Path -LiteralPath $file -PathType Leaf)){
  throw "R1-10B3B required test/overlay missing: $file"
 }
}
$versions=@(Get-ChildItem -LiteralPath $active -Filter 'V*__*.sql' -File |
 ForEach-Object {if($_.Name -match '^V(\d+)__'){[int]$Matches[1]}})
if($versions.Count -eq 0 -or
   [int](($versions | Measure-Object -Maximum).Maximum) -ne 9){
 throw 'R1-10B3B production migration baseline no longer V9; STOP.'
}
$prodOpd=Join-Path $root 'backend\src\main\java\com\argdigital\aronexa\opd'
if(Test-Path -LiteralPath (Join-Path $prodOpd 'RestrictedHttpController.java')){
 throw 'R1-10B3B test HTTP route copied into production source; STOP.'
}
$sql=Get-Content -LiteralPath $overlay -Raw
$java=Get-Content -LiteralPath $tests -Raw
$testConfig=Get-Content -LiteralPath $restart -Raw
foreach($require in @('CREATE OR REPLACE FUNCTION opd_candidate.require_live_staff',
                      'FOR SHARE','OPD_SESSION_REVOKED','OPD_ACCESS_DENIED')){
 if(-not $sql.Contains($require)){
  throw "R1-10B3B missing session-first SQL hardening: $require"
 }
}
foreach($require in @('R110B3B__ordered_session_gate_candidate.sql',
                      'pg_blocking_pids','assertQueuedBookingRejected',
                      'concurrentSessionLogoutWinsBeforeQueuedBookingCanAuthorize',
                      'concurrentRoleRemovalWinsBeforeQueuedBookingCanAuthorize',
                      'concurrentBranchRemovalWinsBeforeQueuedBookingCanAuthorize',
                      'concurrentModuleDisableWinsBeforeQueuedBookingCanAuthorize',
                      'auditEffectiveSqlRolesFunctionOwnersAndNoDirectRuntimeDataAccess',
                      'separateSpringContextStopAndRebuildKeepsAppointmentInRestrictedPg17',
                      'OpdDisposableRestartDatabaseConfiguration.class')){
 if(-not $java.Contains($require)){
  throw "R1-10B3B missing authorization/restart evidence source: $require"
 }
}
if(-not $testConfig.Contains('aronexa_r110b2_disposable_only') -or
   -not $testConfig.Contains('aronexa_r110b2_temporary') -or
   -not $testConfig.Contains('JdbcConnectionDetails')){
 throw 'R1-10B3B restart configuration is not pinned to disposable PG17.'
}
Write-Host 'R1-10B3B SOURCE GUARD PASS: V9 production, test-only session-first SQL, limited PG17 restart'
Write-Host "SourceCommit=$actual"
Write-Host 'Scope=Four queued booking/revocation cases, effective SQL privileges, two fresh Spring contexts'

# Full inherited regression includes all new tests with Maven clean verify:
# authenticated HTTP, restricted PostgreSQL17, frontend/node/doctor/billing
# and the hard read-only R1-09 Windows release NO_GO self-test.
$prior=Join-Path $root 'scripts\Test-Opd-R1-10B3A-Restricted-Http.ps1'
& powershell.exe -NoProfile -File $prior -ExpectedCommit $actual
if($LASTEXITCODE -ne 0){
 throw 'R1-10B3B disposable PG17/auth/restart/full regression FAILED.'
}
Write-Host 'R1-10B3B SESSION-ORDER AND CONTEXT-RESTART CANDIDATE PASSED (TEST ONLY)' -ForegroundColor Green
Write-Host 'Arbitrary concurrent admin revocation and full JVM/Windows restart are NOT certified.'
Write-Host 'Production SQL, patient use, Windows installer and commercial pilot remain NO_GO.'
