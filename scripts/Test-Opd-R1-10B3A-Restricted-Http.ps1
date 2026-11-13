# R1-10B3A exact-SHA disposable HTTP + restricted PG17 acceptance.
# New HTTP adapter is src/test only. No real clinical booking API, migration,
# production credential, original Windows DB, customer data or deployment.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$head=(& git -C $root rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $head -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'R1-10B3A exact Git SHA mismatch: STOP.'
}
$tracked=@(& git -C $root status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or
   @($tracked | Where-Object {$_.Trim().Length -gt 0}).Count -ne 0){
 throw 'R1-10B3A tracked tree is not clean: STOP.'
}
$test=Join-Path $root 'backend\src\test\java\com\argdigital\aronexa\opd\OpdRestrictedBookingPostgresTests.java'
$security=Join-Path $root 'backend\src\main\java\com\argdigital\aronexa\auth\AuthSecurityConfiguration.java'
$realController=Join-Path $root 'backend\src\main\java\com\argdigital\aronexa\opd\RestrictedHttpController.java'
if(-not(Test-Path -LiteralPath $test -PathType Leaf) -or
   -not(Test-Path -LiteralPath $security -PathType Leaf)){
 throw 'R1-10B3A necessary test-only HTTP source or security missing.'
}
if(Test-Path -LiteralPath $realController){
 throw 'R1-10B3A STOP: test-only controller copied into production source.'
}
$testCode=Get-Content -LiteralPath $test -Raw
$policy=Get-Content -LiteralPath $security -Raw
foreach($required in @('RANDOM_PORT','127.0.0.1',
  '@Import(OpdRestrictedBookingPostgresTests.RestrictedHttpController.class)',
  '@Profile("test & opd-restricted-candidate & !dev & !prod")',
  'realBearerHttpBooksChecksInAndReplaysAfterLogoutAndFreshLogin',
  'realHttpRejectsAnonymousForgedRegistrationCrossBranchAndRevokedToken',
  'realHttpRejectsRevokedBookingPermissionAndStaleCheckinRevision',
  'OpdRestrictedSqlAdapter','/opd-restricted/appointments')){
 if(-not $testCode.Contains($required)){
  throw "R1-10B3A missing explicit restricted HTTP evidence contract: $required"
 }
}
if(-not $policy.Contains('test & opd-restricted-candidate & !dev & !prod') -or
   -not $policy.Contains('.anyRequest().denyAll()')){
 throw 'R1-10B3A strict test-only route or deny-all policy changed.'
}
$max=@(Get-ChildItem (Join-Path $root 'backend\src\main\resources\db\migration') -Filter 'V*__*.sql' -File |
 ForEach-Object {if($_.Name -match '^V(\d+)__'){[int]$Matches[1]}})
if($max.Count -eq 0 -or [int](($max | Measure-Object -Maximum).Maximum) -ne 9){
 throw 'R1-10B3A: active Flyway baseline has changed; owner approval required.'
}
Write-Host 'R1-10B3A SOURCE GUARD PASS: test-only actual bearer HTTP, loopback, restricted PostgreSQL 17, production V9'
Write-Host "SourceCommit=$head"
$prior=Join-Path $root 'scripts\Test-Opd-R1-10B2-Restricted-Booking.ps1'
& powershell.exe -NoProfile -File $prior -ExpectedCommit $head
if($LASTEXITCODE -ne 0){
 throw 'R1-10B3A full real HTTP, restricted PG17 and frontend regression FAILED.'
}
Write-Host 'R1-10B3A RESTRICTED HTTP CANDIDATE ACCEPTANCE PASSED (TEST ONLY)' -ForegroundColor Green
Write-Host 'Production booking endpoint, restart UAT, V10/V11 migrations and Windows pilot remain NO_GO.'
