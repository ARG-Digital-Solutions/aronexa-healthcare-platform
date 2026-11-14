# PILOT-08 RC02B — exact-SHA fictional patient search / nonowner SQL QA.
# Never install search SQL on original Windows/production databases.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit,
 [string]$QaCentralPath=(Join-Path $env:USERPROFILE 'Application Development\AroNexa-QA-Central')
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'PILOT08 RC02B STOP: source HEAD does not match expected exact SHA'
}
$changes=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($changes | Where-Object {$_.Trim()}).Count -ne 0){
 throw 'PILOT08 RC02B STOP: tracked source is dirty'
}
foreach($name in @(
 'SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
 'SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE',
 'ARONEXA_PATIENT_DATASOURCE_URL',
 'ARONEXA_PATIENT_SEARCH_REVIEWED_ACTIVATION',
 'ARONEXA_OPD_DIRECTORY_REVIEWED_ACTIVATION',
 'ARONEXA_PILOT05_PORT','ARONEXA_PILOT05_RUN_ID',
 'ARONEXA_PILOT06_BROWSER','ARONEXA_PILOT06_QA_ROOT')){
 if([Environment]::GetEnvironmentVariable($name)){
  throw ('PILOT08 RC02B STOP: inherited sensitive/profile variable '+$name)
 }
}
$mvn=Get-Command mvn.cmd -ErrorAction SilentlyContinue
$docker=Get-Command docker.exe -ErrorAction SilentlyContinue
if($null -eq $mvn -or $null -eq $docker){
 throw 'PILOT08 RC02B STOP: Maven and Docker required'
}
& $docker.Source info '--format' '{{.ServerVersion}}'
if($LASTEXITCODE -ne 0){throw 'PILOT08 RC02B STOP: Docker unavailable'}
Write-Host 'RC02B stage 1/2: production-default deny + disposable PostgreSQL 17 patient search'
Push-Location (Join-Path $repo 'backend')
try {
 & $mvn.Source '-Dtest=PatientCandidateSearchHttpAcceptanceTests,PatientRegistrationReadHttpAcceptanceTests#pilot08Rc02bSearchRemainsDisabledInNormalRuntime' '-Dsurefire.failIfNoSpecifiedTests=true' test
 if($LASTEXITCODE -ne 0){throw 'PILOT08 RC02B FAIL: real HTTP/PG17 search security'}
 $dir=Join-Path (Get-Location) 'target\surefire-reports'
 $suites=@(
  @{Name='com.argdigital.aronexa.patient.PatientCandidateSearchHttpAcceptanceTests';
    Minimum=5;
    Cases=@('realHttpExactMrnAndNamePrefixReturnOnlyBranchRegistrationAndAudit',
      'anonymousWrongBranchTenantAndRevokedBranchDoNotExposePatients',
      'bothPermissionsModuleAndForcedPasswordAreIndependentlyRequired',
      'unsafeWildcardShortOrMalformedQueriesRejectBeforeSearch',
      'patientRoleHasFunctionExecuteButNeverDirectIdentitySelect')},
  @{Name='com.argdigital.aronexa.patient.PatientRegistrationReadHttpAcceptanceTests';
    Minimum=1;
    Cases=@('pilot08Rc02bSearchRemainsDisabledInNormalRuntime')}
 )
 foreach($item in $suites){
  $file=Join-Path $dir ('TEST-'+$item.Name+'.xml')
  if(-not(Test-Path -LiteralPath $file -PathType Leaf)){
   throw ('PILOT08 RC02B FAIL: missing Surefire report '+$item.Name)
  }
  [xml]$xml=Get-Content -LiteralPath $file -Raw
  if([int]$xml.testsuite.tests -lt $item.Minimum -or
     [int]$xml.testsuite.failures -ne 0 -or
     [int]$xml.testsuite.errors -ne 0 -or
     [int]$xml.testsuite.skipped -ne 0){
   throw ('PILOT08 RC02B FAIL: failed or skipped Surefire suite '+$item.Name)
  }
  $found=@($xml.testsuite.testcase | ForEach-Object {$_.name})
  foreach($case in $item.Cases){
   if($found -cnotcontains $case){
    throw ('PILOT08 RC02B FAIL: required test case missing - '+$case)
   }
  }
 }
} finally { Pop-Location }
Write-Host 'RC02B stage 2/2: RC02A/RC01 real browser, role/SQL security and separate-JVM recovery'
$parent=Join-Path $PSScriptRoot 'Test-AroNexa-Pilot08-RC02A-DoctorPicker.ps1'
if(-not(Test-Path -LiteralPath $parent -PathType Leaf)){
 throw 'PILOT08 RC02B STOP: parent guarded RC02A acceptance script missing'
}
& powershell.exe -NoProfile -File $parent -ExpectedCommit $sha -QaCentralPath $QaCentralPath
if($LASTEXITCODE -ne 0){throw 'PILOT08 RC02B FAIL: parent Chromium/PG17/JVM acceptance'}
Write-Host 'PILOT08 RC02B MRN NAME SEARCH + DOCTOR PICKER + RESTART QA PASSED' -ForegroundColor Green
Write-Host ('SourceCommit='+$sha)
Write-Host 'Fictional-only SQL function, no production patient/table grants, clinic activation, merge or release.'
