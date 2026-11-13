# R1-10A: exact-SHA integration foundation acceptance.
# Full existing R1-04..R1-08 synthetic real HTTP/Testcontainers PG17 suite,
# six new production-domain pure tests, plus a non-activation source guard.
# NO production migrations, deployment, customer data or clinic DB access.
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)]
  [ValidatePattern('^[a-fA-F0-9]{40}$')]
  [string]$ExpectedCommit
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'R1-10A exact source SHA mismatch. STOP.'
}
$controller=Join-Path $repo 'backend\src\main\java\com\argdigital\aronexa\opd\OpdVisitContract.java'
$unit=Join-Path $repo 'backend\src\test\java\com\argdigital\aronexa\opd\OpdVisitContractTests.java'
$security=Join-Path $repo 'backend\src\main\java\com\argdigital\aronexa\auth\AuthSecurityConfiguration.java'
foreach($path in @($controller,$unit,$security)){
 if(-not(Test-Path -LiteralPath $path -PathType Leaf)){
  throw 'Missing required R1-10A source/test/security file.'
 }
}
$code=Get-Content -LiteralPath $controller -Raw
if($code -match '@RestController|@Controller|@Service|@Repository|@Component|@Bean|@Transactional|@RequestMapping|@PostMapping|@GetMapping'){
 throw 'R1-10A OPD contract was accidentally activated as a runtime endpoint/component.'
}
if($code -notmatch 'verifyStableScope' -or $code -notmatch 'expectedRevision'){
 throw 'R1-10A identity/revision safety contract missing.'
}
$guard=Get-Content -LiteralPath $security -Raw
if($guard -notmatch 'test & appointment-candidate & !dev & !prod' -or
   $guard -notmatch '\.anyRequest\(\)\.denyAll\(\)'){
 throw 'R1-10A stop: candidate route restrictions or deny-all changed.'
}
$migrationPath=Join-Path $repo 'backend\src\main\resources\db\migration'
$versionNumbers=@(Get-ChildItem -LiteralPath $migrationPath -Filter 'V*__*.sql' -File |
 ForEach-Object {if($_.Name -match '^V(\d+)__'){[int]$Matches[1]}})
$highest=if($versionNumbers.Count -gt 0){
 [int](($versionNumbers|Measure-Object -Maximum).Maximum)
}else{0}
if($highest -ne 9){
 throw "R1-10A stop: production migration baseline changed to V$highest; independent approval required."
}
Write-Host "R1-10A source guard PASS: no runtime activation, V9 baseline, candidate security unchanged" -ForegroundColor Green
$prior=Join-Path $repo 'scripts\Test-Opd-R1-08-Billing-Increment.ps1'
& powershell.exe -NoProfile -File $prior -ExpectedCommit $sha
if($LASTEXITCODE -ne 0){
 throw 'R1-10A full disposable PG17 and frontend regression FAILED.'
}
Write-Host 'R1-10A INTEGRATION FOUNDATION ACCEPTANCE PASSED (TEST-ONLY, NO CUSTOMER ACTIVATION)' -ForegroundColor Green
Write-Host "SourceCommit=$sha"
Write-Host 'Real runtime integration and restricted production SQL authorization remain NOT IMPLEMENTED.'
