$ErrorActionPreference = 'Stop'
$modulePath = Join-Path $PSScriptRoot 'Win11p-MavenMatrix.psm1'
if (-not (Test-Path -LiteralPath $modulePath)) {
    Write-Output 'RED: coordinator module and reconciliation contract are not implemented'
    exit 1
}
Import-Module $modulePath -Force

function Assert-Condition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

$config = Get-Win11pMatrixConfig
$inventory = @([pscustomobject]@{ id='sample.CoreTests#ordinaryCase'; tags=@() }) +
    @($config.guarded_tests | ForEach-Object {
        [pscustomobject]@{ id=[string]$_.id; tags=@([string]$_.tag) }
    })
$assignments = @(Get-Win11pTestAssignment -Inventory $inventory)
$complete = @($assignments | ForEach-Object {
    [pscustomobject]@{ id=$_.id; outcome='PASS'; lane=$_.lane }
})

function Assert-Blocked {
    param([string]$Name, [object[]]$Cases, [object[]]$Results)
    $decision = Compare-Win11pTestMatrix -Inventory $Cases -Results $Results
    Assert-Condition ($decision.overall -ne 'PASS') ($Name + ' incorrectly allowed PASS')
}
$decision = Compare-Win11pTestMatrix -Inventory $inventory -Results $complete
Assert-Condition ($decision.overall -eq 'PASS') 'complete unique inventory should PASS'

Assert-Blocked 'missing method' $inventory @($complete | Select-Object -Skip 1)
Assert-Blocked 'duplicate method' $inventory @($complete + $complete[0])
Assert-Blocked 'failed method' $inventory @($complete | ForEach-Object {
    if ($_.lane -eq 'pilot05-phase-a') {
        [pscustomobject]@{ id=$_.id; outcome='FAIL'; lane=$_.lane }
    } else { $_ }
})
Assert-Blocked 'errored method' $inventory @($complete | ForEach-Object {
    if ($_.lane -eq 'pilot05-phase-a') {
        [pscustomobject]@{ id=$_.id; outcome='ERROR'; lane=$_.lane }
    } else { $_ }
})
Assert-Blocked 'skipped method' $inventory @($complete | ForEach-Object {
    if ($_.lane -eq 'pilot05-phase-a') {
        [pscustomobject]@{ id=$_.id; outcome='SKIPPED'; lane=$_.lane }
    } else { $_ }
})
Assert-Blocked 'NOT_RUN operator lane' $inventory @($complete | Where-Object {$_.lane -ne 'pilot06-browser'})
Assert-Blocked 'method in wrong lane' $inventory @($complete | ForEach-Object {
    if ($_.lane -eq 'pilot05-phase-a') {
        [pscustomobject]@{ id=$_.id; outcome='PASS'; lane='ordinary' }
    } else { $_ }
})
Assert-Blocked 'duplicate discovery' @($inventory + $inventory[1]) $complete
$unknownTag = @($inventory | Where-Object {$_.lane -ne 'pilot05-phase-a'}) +
    @([pscustomobject]@{id='sample.X#y';tags=@('win11p-unknown')})
Assert-Blocked 'unknown tag' $unknownTag $complete

$reportRoot=Join-Path ([IO.Path]::GetTempPath()) ('win11p-surefire-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($reportRoot)
try {
    $xml=@'
<testsuite tests="3" failures="1" errors="0" skipped="1">
 <testcase classname="sample.ReportTests" name="passes" />
 <testcase classname="sample.ReportTests" name="fails"><failure message="synthetic" /></testcase>
 <testcase classname="sample.ReportTests" name="skips"><skipped /></testcase>
</testsuite>
'@
    [IO.File]::WriteAllText((Join-Path $reportRoot 'TEST-synthetic.xml'),$xml)
    $parsed=ConvertFrom-Win11pSurefireReports -ReportDirectory $reportRoot -Lane ordinary
    Assert-Condition ($parsed.tests -eq 3 -and $parsed.failures -eq 1 -and $parsed.errors -eq 0 -and $parsed.skipped -eq 1) 'Surefire outcome parsing counts mismatch'
} finally { Remove-Item -LiteralPath $reportRoot -Recurse -Force }

$parentBefore=[Environment]::GetEnvironmentVariable('ARONEXA_MATRIX_CHILD_SCOPE','Process')
$childTemp=Join-Path ([IO.Path]::GetTempPath()) ('win11p-child-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($childTemp)
try {
    $childLog=Join-Path $childTemp 'child.log'
    $child=Invoke-Win11pChildProcess -PowerShellCommand "Write-Output `$env:ARONEXA_MATRIX_CHILD_SCOPE" -WorkingDirectory $childTemp -LogPath $childLog -Environment @{ARONEXA_MATRIX_CHILD_SCOPE='CHILD_ONLY'}
    Assert-Condition ($child.exit_code -eq 0) 'child process exit code was not preserved'
    Assert-Condition (([IO.File]::ReadAllText($childLog)).StartsWith("CHILD_ONLY`r`n")) 'child did not receive scoped environment override'
    Assert-Condition ([Environment]::GetEnvironmentVariable('ARONEXA_MATRIX_CHILD_SCOPE','Process') -eq $parentBefore) 'child environment override leaked to parent'
} finally { Remove-Item -LiteralPath $childTemp -Recurse -Force }

$coordinator=Join-Path $PSScriptRoot 'Invoke-Win11p-MavenMatrix.ps1'
$parseTokens=$null;$parseErrors=$null
[void][System.Management.Automation.Language.Parser]::ParseFile($coordinator,[ref]$parseTokens,[ref]$parseErrors)
Assert-Condition ($parseErrors.Count -eq 0) 'coordinator syntax failed'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim()
$priorPreference=$ErrorActionPreference
$ErrorActionPreference='Continue'
$guardOutput=& powershell.exe -NoProfile -File $coordinator -ExpectedCommit $sha -Mode All 2>&1
$guardExit=$LASTEXITCODE
$ErrorActionPreference=$priorPreference
Assert-Condition ($guardExit -ne 0) 'unreviewed All mode incorrectly allowed execution'
Assert-Condition (($guardOutput -join "`n") -match 'reviewed execution authorization|tracked source is dirty') 'All mode failed outside its expected authorization/clean-source guard'
Write-Output 'Win11p matrix reconciliation tests PASS: 14'

