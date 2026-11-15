$ErrorActionPreference='Stop'
$module=Join-Path $PSScriptRoot '../WebLab.psm1'
if(-not(Test-Path $module)){throw 'RED: reusable web lab module missing'}
Import-Module $module -Force
function Assert($Condition,$Message){if(-not $Condition){throw $Message}}
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim()
Assert ((Get-WebLabSource -Repo $repo -ExpectedCommit $sha -AllowUncommitted) -eq $sha) 'exact source missing'
try{Get-WebLabSource -Repo $repo -ExpectedCommit ('0'*40) -AllowUncommitted;throw 'wrong sha accepted'}catch{Assert ($_.Exception.Message -match 'source SHA mismatch') 'wrong SHA failed outside guard'}
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('websource-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($fixture)
try{
 & git -C $fixture init --quiet
 [IO.File]::WriteAllText((Join-Path $fixture 'tracked.txt'),'before')
 & git -C $fixture add tracked.txt
 & git -C $fixture -c user.name=WebLabTest -c user.email=test@example.invalid commit --quiet -m fixture
 $fixed=(& git -C $fixture rev-parse HEAD).Trim()
 [IO.File]::WriteAllText((Join-Path $fixture 'tracked.txt'),'after')
 try{Get-WebLabSource -Repo $fixture -ExpectedCommit $fixed;throw 'dirty source accepted'}catch{Assert ($_.Exception.Message -match 'tracked changes|untracked implementation') 'dirty source failed outside guard'}
}finally{Remove-Item $fixture -Recurse -Force}
$prior=[Environment]::GetEnvironmentVariable('ARONEXA_DB_URL','Process')
try{
 [Environment]::SetEnvironmentVariable('ARONEXA_DB_URL','jdbc:postgresql://invalid.invalid/not-contacted','Process')
 try{Assert-WebLabCleanEnvironment;throw 'database override accepted'}catch{Assert ($_.Exception.Message -match 'inherited credential/database/runtime override refused') 'override failed outside guard'}
}finally{[Environment]::SetEnvironmentVariable('ARONEXA_DB_URL',$prior,'Process')}
$selection=Get-WebLabSelection -Repo $repo
Assert ($selection.frontend.Count -gt 0 -and $selection.classes.Count -eq 3) 'missing bounded Reception selection'
Assert ($selection.classes -contains 'AppointmentCandidateHttpAcceptanceTests') 'appointment selection absent'
Assert ($selection.full_acceptance -eq 'NOT_RUN') 'focused feedback promoted to acceptance'
$temp=Join-Path ([IO.Path]::GetTempPath()) ('weblab-'+[guid]::NewGuid().ToString('N'))
$root=Join-Path $temp 'AroNexa-Pilot09-Disposable'
$run=Join-Path $root ('pilot09-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($run)
$marker=Join-Path $run 'LAB-TEST-ONLY.marker'
[IO.File]::WriteAllText($marker,'FICTIONAL_DISPOSABLE_ONLY_SOURCE_SHA='+$sha)
try{
 Request-WebLabStop -RunDirectory $run -AllowedRoot $root -ExpectedCommit $sha
 Assert (Test-Path (Join-Path $run 'STOP')) 'STOP missing'
 Remove-Item (Join-Path $run 'STOP')
 foreach($bad in @($temp,(Join-Path $root 'pilot09-invalid'))){
  try{Request-WebLabStop -RunDirectory $bad -AllowedRoot $root -ExpectedCommit $sha;throw 'unsafe path accepted'}catch{Assert ($_.Exception.Message -match 'lab identity|does not exist') 'unsafe path wrong refusal'}
 }
 [IO.File]::WriteAllText($marker,'FICTIONAL_DISPOSABLE_ONLY_SOURCE_SHA='+('0'*40))
 try{Request-WebLabStop -RunDirectory $run -AllowedRoot $root -ExpectedCommit $sha;throw 'wrong marker accepted'}catch{Assert ($_.Exception.Message -match 'lab identity') 'wrong marker wrong refusal'}
 Assert (-not(Test-Path (Join-Path $run 'STOP'))) 'wrong lab touched'
 if([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT){
  [IO.File]::WriteAllText($marker,'FICTIONAL_DISPOSABLE_ONLY_SOURCE_SHA='+$sha)
  $link=Join-Path $root ('pilot09-'+[guid]::NewGuid().ToString('N'))
  New-Item -ItemType SymbolicLink -Path $link -Target $run | Out-Null
  try{Request-WebLabStop -RunDirectory $link -AllowedRoot $root -ExpectedCommit $sha;throw 'link accepted'}catch{Assert ($_.Exception.Message -match 'lab identity') 'link wrong refusal'}
 }
}finally{Remove-Item $temp -Recurse -Force}
Write-Output 'PASS: exact source, dirty refusal, bounded selection, owned STOP and path/marker/link denial'
$bad=Get-WebLabDiagnostics -Text "[ERROR] Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0)."
Assert ($bad.lifecycle -eq 'FAIL') 'fork shutdown failure hidden'
$warning=Get-WebLabDiagnostics -Text 'WARN com.zaxxer.hikari.pool.PoolBase - Failed to validate connection (This connection has been closed.)'
Assert ($warning.lifecycle -eq 'BLOCKED' -and $warning.hikari_closed_warnings -eq 1) 'Hikari diagnostic hidden'
Assert ((Get-WebLabDiagnostics -Text '[INFO] BUILD SUCCESS').lifecycle -eq 'PASS') 'clean log misclassified'
