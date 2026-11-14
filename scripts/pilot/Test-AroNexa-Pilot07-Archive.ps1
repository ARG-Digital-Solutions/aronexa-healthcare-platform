# Pilot07 source-bundle integrity, not a publisher signature or installer.
# An owner independently verifies the ZIP SHA256 shown by the builder.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[0-9a-fA-F]{40}$')]
 [string]$ExpectedCommit
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$shaPath=Join-Path $repo 'PILOT07_SOURCE_SHA.txt'
$manifest=Join-Path $repo 'PILOT07_SOURCE_SHA256.tsv'
if(-not(Test-Path -LiteralPath $shaPath -PathType Leaf) -or
   -not(Test-Path -LiteralPath $manifest -PathType Leaf)){
 throw 'PILOT07 BUNDLE STOP: source provenance or checksum manifest missing.'
}
$sha=(Get-Content -LiteralPath $shaPath -Raw).Trim().ToLowerInvariant()
if($sha -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'PILOT07 BUNDLE STOP: source SHA marker mismatch.'
}
$entries=Get-Content -LiteralPath $manifest
$found=[System.Collections.Generic.HashSet[string]]::new(
 [StringComparer]::OrdinalIgnoreCase)
$count=0
foreach($line in $entries){
 if([string]::IsNullOrWhiteSpace($line)){continue}
 $parts=$line.Split([char]9)
 if($parts.Count -ne 2 -or $parts[0] -notmatch '^[A-Fa-f0-9]{64}$'){
  throw 'PILOT07 BUNDLE STOP: invalid checksum record.'
 }
 $relative=$parts[1]
 if([string]::IsNullOrWhiteSpace($relative) -or
    $relative.StartsWith('/') -or $relative.Contains('\') -or
    $relative.Contains(':') -or $relative.Split('/') -contains '..'){
  throw 'PILOT07 BUNDLE STOP: unsafe source-relative path.'
 }
 if(-not $found.Add($relative)){
  throw 'PILOT07 BUNDLE STOP: duplicate source-relative path.'
 }
 $file=Join-Path $repo ($relative.Replace('/','\'))
 if(-not(Test-Path -LiteralPath $file -PathType Leaf)){
  throw 'PILOT07 BUNDLE STOP: packaged source file missing.'
 }
 $actual=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
 if($actual -ne $parts[0].ToUpperInvariant()){
  throw 'PILOT07 BUNDLE STOP: packaged source file hash mismatch.'
 }
 $count++
 if($count -gt 4000){throw 'PILOT07 BUNDLE STOP: oversized file inventory.'}
}
if($count -lt 100){
 throw 'PILOT07 BUNDLE STOP: incomplete archive source inventory.'
}
$unlisted=@(Get-ChildItem -LiteralPath $repo -File -Recurse -Force |
 Where-Object {
  $rel=$_.FullName.Substring($repo.Length+1).Replace('\','/')
  ($rel -notin @('PILOT07_SOURCE_SHA256.tsv')) -and
  $rel -notmatch '(^|/)(node_modules|target|\.git)(/|$)' -and
  -not $found.Contains($rel)
})
if($unlisted.Count -gt 0){
 throw 'PILOT07 BUNDLE STOP: unmanifested source/content present.'
}
Write-Host 'PILOT07 ARCHIVED SOURCE SHA256 MANIFEST PASS' -ForegroundColor Green
Write-Host ('SourceCommit='+$sha)
Write-Host ('VerifiedFiles='+$count)
Write-Host 'Checksum inventory is not a signed/publisher-authenticated artifact.'
