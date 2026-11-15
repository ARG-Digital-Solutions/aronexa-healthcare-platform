Set-StrictMode -Version Latest
function Get-WebLabSource {
 param([string]$Repo,[ValidatePattern('^[0-9a-fA-F]{40}$')][string]$ExpectedCommit,[switch]$AllowUncommitted)
 $actual=(& git -C $Repo rev-parse HEAD).Trim().ToLowerInvariant()
 if($LASTEXITCODE -ne 0 -or $actual -cne $ExpectedCommit.ToLowerInvariant()){throw 'WebLab source SHA mismatch'}
 $dirty=@(& git -C $Repo status --porcelain --untracked-files=all)
 if($LASTEXITCODE -ne 0){throw 'Cannot inspect source state'}
 if(-not $AllowUncommitted -and $dirty.Count){throw 'WebLab refuses tracked changes or untracked implementation; commit the bounded source first'}
 return $actual
}
function Get-WebLabSelection {
 param([string]$Repo)
 $names=@('reception-demo','staff-session-client','registration-client','connected-registration-flow','opd-journey','patient-candidate-search-client','appointment-candidate-client','queue-registration-continuity','reception-queue-summary')
 $files=@($names|ForEach-Object{
  $file=Join-Path $Repo ('frontend/test/'+$_+'.test.mjs')
  if(-not(Test-Path -LiteralPath $file -PathType Leaf)){throw 'Reception test source missing'}
  $file
 })
 return [pscustomobject]@{frontend=$files;classes=@('RegistrationCandidateHttpAcceptanceTests','PatientCandidateSearchHttpAcceptanceTests','AppointmentCandidateHttpAcceptanceTests');full_acceptance='NOT_RUN';browser_journey='NOT_RUN';runtime_kind='EXISTING_SYNTHETIC_TEST_LAB'}
}
function Request-WebLabStop {
 param([string]$RunDirectory,[string]$AllowedRoot,[ValidatePattern('^[0-9a-fA-F]{40}$')][string]$ExpectedCommit)
 $run=Get-Item -LiteralPath $RunDirectory -ErrorAction Stop
 $root=[IO.Path]::GetFullPath($AllowedRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)
 if(-not $run.PSIsContainer -or $run.Parent.FullName -cne $root -or $run.Name -cnotmatch '^pilot09-[0-9a-f]{32}$'){throw 'WebLab lab identity mismatch'}
 for($item=$run;$null -ne $item;$item=$item.Parent){
  if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'WebLab lab identity refuses reparse points'}
 }
 $marker=Get-Item -LiteralPath (Join-Path $run.FullName 'LAB-TEST-ONLY.marker') -ErrorAction Stop
 if($marker.PSIsContainer -or $marker.Length -gt 512 -or ($marker.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
    ([IO.File]::ReadAllText($marker.FullName).TrimEnd("`r","`n") -cne ('FICTIONAL_DISPOSABLE_ONLY_SOURCE_SHA='+$ExpectedCommit.ToLowerInvariant()))){throw 'WebLab lab identity source marker mismatch'}
 $stop=Join-Path $run.FullName 'STOP'
 if(Test-Path -LiteralPath $stop){throw 'STOP already exists; inspect the owning lab instead of overwriting it'}
 $stream=[IO.File]::Open($stop,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
 $stream.Dispose()
 Write-Output 'STOP_REQUESTED: owning harness must still verify process/database teardown; no process was killed here'
}
function Quote-WebLabArgument {
 param([string]$Value)
 return "'"+$Value.Replace("'","''")+"'"
}
Export-ModuleMember -Function Get-WebLabSource,Get-WebLabSelection,Request-WebLabStop,Quote-WebLabArgument
function Assert-WebLabCleanEnvironment {
 foreach($entry in @(Get-ChildItem Env:)){
  if($entry.Name -match '^(GH_TOKEN|GITHUB_TOKEN|ARONEXA_.*|SPRING_.*|PGHOST|PGPORT|PGDATABASE|PGUSER|PGPASSWORD|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|MAVEN_OPTS|MAVEN_ARGS|NODE_OPTIONS|NODE_PATH)$' -and $entry.Value){throw ('WebLab inherited credential/database/runtime override refused: '+$entry.Name)}
 }
}
Export-ModuleMember -Function Assert-WebLabCleanEnvironment
