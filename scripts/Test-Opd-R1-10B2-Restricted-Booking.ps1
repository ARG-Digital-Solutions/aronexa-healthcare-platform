# R1-10B2: exact-SHA restricted OPD writer and booking/check-in evidence.
# No production Flyway, clinic DB, online deployment, merge or installer actions.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[a-fA-F0-9]{40}$')]
 [string]$ExpectedCommit
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sha=(& git -C $root rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'R1-10B2: exact Git SHA mismatch. STOP.'
}
$changed=@(& git -C $root status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($changed | Where-Object {$_.Trim().Length -gt 0}).Count -gt 0){
 throw 'R1-10B2: tracked source tree is not clean. STOP.'
}

$sql=Join-Path $root 'deploy\opd\review-only\R110B2__restricted_booking_writer_candidate.sql'
$command=Join-Path $root 'backend\src\main\java\com\argdigital\aronexa\opd\OpdBookingCommand.java'
$proof=Join-Path $root 'backend\src\main\java\com\argdigital\aronexa\opd\OpdCommandProof.java'
$adapter=Join-Path $root 'backend\src\main\java\com\argdigital\aronexa\opd\OpdRestrictedSqlAdapter.java'
$tests=Join-Path $root 'backend\src\test\java\com\argdigital\aronexa\opd\OpdRestrictedBookingPostgresTests.java'
foreach($file in @($sql,$command,$proof,$adapter,$tests)){
 if(-not(Test-Path -LiteralPath $file -PathType Leaf)){
  throw "R1-10B2: missing required source: $file"
 }
}
$active=Join-Path $root 'backend\src\main\resources\db\migration'
$versionNumbers=@(Get-ChildItem -LiteralPath $active -Filter 'V*__*.sql' -File |
 ForEach-Object {if($_.Name -match '^V(\d+)__'){[int]$Matches[1]}})
if($versionNumbers.Count -eq 0 -or
   [int](($versionNumbers | Measure-Object -Maximum).Maximum) -ne 9){
 throw 'R1-10B2: production Flyway baseline changed from V9 without approval.'
}
if(Test-Path -LiteralPath (Join-Path $active 'R110B2__restricted_booking_writer_candidate.sql')){
 throw 'R1-10B2: SQL candidate was copied into active Flyway.'
}
foreach($java in @($command,$proof,$adapter)){
 $code=Get-Content -LiteralPath $java -Raw
 if($code -match '@RestController|@Controller|@Service|@Component|@Configuration|@Bean|@RequestMapping|@PostMapping'){
  throw 'R1-10B2: an internal command was accidentally activated as a Spring endpoint or bean.'
 }
}
$guard=Get-Content -LiteralPath (Join-Path $root 'backend\src\main\java\com\argdigital\aronexa\auth\AuthSecurityConfiguration.java') -Raw
if($guard -notmatch 'test & appointment-candidate & !dev & !prod' -or
   $guard -notmatch 'test & registration-candidate & !dev & !prod' -or
   $guard -notmatch '\.anyRequest\(\)\.denyAll\(\)'){
 throw 'R1-10B2: missing test-only route guard or default-deny policy.'
}
$ddl=Get-Content -LiteralPath $sql -Raw
foreach($fragment in @('NOLOGIN','NOBYPASSRLS','SECURITY DEFINER',
                       'OPD_PROOF_DENIED','OPD_SESSION_REVOKED',
                       'aronexa_opd_runtime','command_nonce',
                       'book_guarded','checkin_guarded')){
 if(-not $ddl.Contains($fragment)){
  throw "R1-10B2: missing SQL authorization contract: $fragment"
 }
}
if($ddl -match 'CREATE ROLE aronexa_opd_runtime LOGIN' -or
   $ddl -match "PASSWORD\s+'"){
 throw 'R1-10B2: a hardcoded SQL runtime credential or login role was staged.'
}
Write-Host 'R1-10B2 SOURCE GATES PASS: V9, disabled routes, no runtime grants or production credential' -ForegroundColor Green
Write-Host "SourceCommit=$sha"
Write-Host 'Database=Disposable Testcontainers PostgreSQL 17 only'
Write-Host 'New evidence=Restricted JDBC role, signed booking/check-in, revocation, replay, denied direct table DML'

# Inherited R1-10B1 runner performs full Maven clean verify, real HTTP +
# disposable PG17 suites, npm ci/frontend unit tests and Doctor/Billing builds.
# Its Maven verify automatically includes the new R1-10B2 JUnit test classes.
$previous=Join-Path $root 'scripts\Test-Opd-R1-10B1-Booking-Schema.ps1'
& powershell.exe -NoProfile -File $previous -ExpectedCommit $sha
if($LASTEXITCODE -ne 0){
 throw 'R1-10B2: full frontend/backend/disposable PG17 regression failed.'
}
Write-Host 'R1-10B2 RESTRICTED BOOKING CANDIDATE ACCEPTANCE PASSED (TEST ONLY)' -ForegroundColor Green
Write-Host 'NO production appointment endpoint, approved V10/V11, customer install or release GO.'
