# Private local evidence collection only. No database actions, credentials or .env reads.
[CmdletBinding()]
param([string]$EvidenceRoot = (Join-Path $env:USERPROFILE 'AroNexa-Backups'))
$ErrorActionPreference = 'Stop'
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$destination = Join-Path $EvidenceRoot ("P01-T06-Closure-Archive-" + $stamp + "-" + [guid]::NewGuid().ToString('N').Substring(0,8))
[void](New-Item -ItemType Directory -Path $destination)
$names = @(
 'AroNexa-P01-Windows-Preflight-20261104-001458.txt',
 'AroNexa-P01-Windows-Database-20261104-002804.txt',
 'AroNexa-P01-Windows-Verify-20261104-003736.txt',
 'AroNexa-P01-Windows-Tests-20261104-004233.txt'
)
$missing = New-Object 'System.Collections.Generic.List[string]'
foreach ($name in $names) {
 $source = Join-Path $env:TEMP $name
 if (Test-Path -LiteralPath $source -PathType Leaf) {
  Copy-Item -LiteralPath $source -Destination (Join-Path $destination $name)
 } else { $missing.Add($name) }
}
$rehearsalName = 'P01-T06-V7-Disposable-Rehearsal-20261103_232608.txt'
$rehearsal = Join-Path (Join-Path $EvidenceRoot 'P01-T06-Evidence') $rehearsalName
if (Test-Path -LiteralPath $rehearsal -PathType Leaf) {
 Copy-Item -LiteralPath $rehearsal -Destination (Join-Path $destination $rehearsalName)
} else { $missing.Add($rehearsalName) }
@(
 'Private P01-T06 evidence archive. Review every file before sharing.',
 'No .env, database dump, role passwords or unsanitized application logs collected.',
 'Original screenshot and detailed test XML are not collected by this script.',
 'This script does not establish owner approval or alter the database.',
 ('Missing expected reports: ' + $missing.Count)
) + @($missing) | Set-Content -LiteralPath (Join-Path $destination 'ARCHIVE_NOTES.txt') -Encoding UTF8
Get-ChildItem -LiteralPath $destination -File | Sort-Object Name | ForEach-Object {
 $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
 "$hash  $($_.Name)"
} | Set-Content -LiteralPath (Join-Path $destination 'SHA256SUMS.txt') -Encoding ASCII
$zip = $destination + '.zip'
Compress-Archive -LiteralPath $destination -DestinationPath $zip
Write-Output ("Archive: " + $zip)
Write-Output ("Archive SHA256: " + (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash)
Write-Output ("Missing expected reports: " + $missing.Count)
Write-Output 'Owner closure approval remains pending. Keep archive private until reviewed.'
