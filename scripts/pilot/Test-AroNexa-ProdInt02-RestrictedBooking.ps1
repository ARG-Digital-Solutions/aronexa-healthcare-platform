# PROD-INT-02: exact-SHA Windows guard for real HTTP -> production-source
# booking/check-in -> restricted LOGIN -> disposable PostgreSQL 17.
# No original DB / real patient / production module activation.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$actual=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $actual -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'PRODINT02 STOP: exact Git SHA mismatch.'
}
$dirty=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($dirty | Where-Object {$_.Trim()}).Count -ne 0){
 throw 'PRODINT02 STOP: tracked source is not clean.'
}
foreach($variable in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
 'SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE',
 'ARONEXA_PATIENT_DATASOURCE_URL','ARONEXA_AUTH_DATASOURCE_URL',
 'ARONEXA_OPD_DATASOURCE_URL','ARONEXA_OPD_SIGNING_KEY',
 'ARONEXA_OPD_BOOKING_REVIEWED_ACTIVATION')){
 if([Environment]::GetEnvironmentVariable($variable)){
  throw ('PRODINT02 STOP: inherited database/activation variable '+$variable)
 }
}
# Report exactly which developer prerequisites are absent, rather than
# one ambiguous failure. The JDK jar command is not required here:
# inspect the built Spring Boot ZIP/JAR using .NET's read-only ZipArchive.
$requirements=@(
 @{Name='Docker Engine CLI'; Command='docker.exe'},
 @{Name='Maven'; Command='mvn.cmd'},
 @{Name='npm'; Command='npm.cmd'},
 @{Name='Java 21'; Command='java.exe'}
)
$missing=@($requirements | Where-Object {
 $null -eq (Get-Command $_.Command -ErrorAction SilentlyContinue)
} | ForEach-Object { $_.Name+' ('+$_.Command+')' })
if($missing.Count -gt 0){
 throw ('PRODINT02 STOP: missing prerequisite(s): '+($missing -join ', ')+
  '. Check PATH, tool installation and JAVA_HOME.')
}
$docker=Get-Command docker.exe -ErrorAction Stop
$mvn=Get-Command mvn.cmd -ErrorAction Stop
$npm=Get-Command npm.cmd -ErrorAction Stop
$java=Get-Command java.exe -ErrorAction Stop
$version=(& $java.Source --version | Select-Object -First 1).ToString()
if($version -notmatch '(^|[\s"])21\.'){throw 'PRODINT02 STOP: Java 21 required.'}
& $docker.Source info '--format' '{{.ServerVersion}}'
if($LASTEXITCODE -ne 0){throw 'PRODINT02 STOP: Docker Engine is unavailable.'}

Push-Location (Join-Path $repo 'frontend')
try{
 & $npm.Source ci --no-audit --no-fund
 if($LASTEXITCODE -ne 0){throw 'PRODINT02 FAIL: npm clean install'}
 & $npm.Source test
 if($LASTEXITCODE -ne 0){throw 'PRODINT02 FAIL: frontend regression'}
 & $npm.Source run 'build:connected:test'
 if($LASTEXITCODE -ne 0){throw 'PRODINT02 FAIL: TypeScript / Vite test-only build'}
} finally {Pop-Location}

Push-Location (Join-Path $repo 'backend')
try {
 # The first class exercises the REAL production-source controller against
 # independently restricted SQL. The second proves default activation OFF.
 # Both use Testcontainers PG17; no Windows/source/local database URL.
 & $mvn.Source -B '-Dtest=OpdRestrictedBookingPostgresTests,OpdIntakeProductionHttpAcceptanceTests' '-Dsurefire.failIfNoSpecifiedTests=true' test
 if($LASTEXITCODE -ne 0){
  throw 'PRODINT02 FAIL: real HTTP / nonowner PG17 security regression'
 }
 $reportDir=Join-Path (Get-Location) 'target\surefire-reports'
 $focused=@(
  @{Class='com.argdigital.aronexa.opd.OpdRestrictedBookingPostgresTests';
    Needed=@('prodInt02RealProductionSourceHttpBooksChecksInAndReplays',
      'prodInt02ProductionHttpDeniesWrongScopeRevokedRoleAndConflictingSlot')},
  @{Class='com.argdigital.aronexa.opd.OpdIntakeProductionHttpAcceptanceTests';
    Needed=@('prodInt02ProductionBookingRouteRemainsDisabledWithoutReviewFlag')}
 )
 foreach($suite in $focused){
  $xmlPath=Join-Path $reportDir ('TEST-'+$suite.Class+'.xml')
  if(-not(Test-Path -LiteralPath $xmlPath -PathType Leaf)){
   throw 'PRODINT02 FAIL: required Surefire report missing'
  }
  [xml]$report=Get-Content -LiteralPath $xmlPath -Raw
  if([int]$report.testsuite.tests -lt $suite.Needed.Count -or
     [int]$report.testsuite.failures -ne 0 -or
     [int]$report.testsuite.errors -ne 0 -or
     [int]$report.testsuite.skipped -ne 0){
   throw 'PRODINT02 FAIL: Surefire suite is not completely green'
  }
  $names=@($report.testsuite.testcase | ForEach-Object {$_.name})
  foreach($name in $suite.Needed){
   if($names -cnotcontains $name){
    throw ('PRODINT02 FAIL: required test case missing - '+$name)
   }
  }
 }
 # This is a compiled production JAR check, not a customer app/installer.
 & $mvn.Source -B '-DskipTests' package
 if($LASTEXITCODE -ne 0){throw 'PRODINT02 FAIL: Spring Boot package'}
 $appJar=Join-Path (Get-Location) 'target\aronexa-backend-0.1.0-SNAPSHOT.jar'
 if(-not(Test-Path -LiteralPath $appJar -PathType Leaf)){
  throw 'PRODINT02 FAIL: Spring Boot JAR missing'
 }
 # The archive is ZIP-compatible. Read its entries directly; no
 # jar.exe PATH requirement or extra process is necessary on Windows.
 Add-Type -AssemblyName System.IO.Compression
 $stream=[System.IO.File]::OpenRead($appJar)
 try {
  $archive=[System.IO.Compression.ZipArchive]::new(
      $stream,[System.IO.Compression.ZipArchiveMode]::Read,$false)
  try {
   $entries=@($archive.Entries | ForEach-Object {$_.FullName})
   if($entries -cnotcontains 'BOOT-INF/classes/com/argdigital/aronexa/opd/OpdBookingHttpController.class'){
    throw 'PRODINT02 FAIL: compiled production-source booking controller missing'
   }
   foreach($bad in @('OpdRestrictedBookingPostgresTests.class',
                     'AppointmentCandidateHttpAcceptanceTests.class')){
    if(@($entries | Where-Object {$_ -like ('*'+$bad)}).Count -gt 0){
     throw 'PRODINT02 FAIL: test-only controller was packaged'
    }
   }
  } finally { $archive.Dispose() }
 } finally { $stream.Dispose() }
} finally {Pop-Location}
Write-Host 'PRODINT02 REAL PRODUCTION-SOURCE HTTP / RESTRICTED PG17 QA PASSED' -ForegroundColor Green
Write-Host ('SourceCommit='+$actual)
Write-Host 'Default activation OFF; no production migrations, original DB or clinic release.'
