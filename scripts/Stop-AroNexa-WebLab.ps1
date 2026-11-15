[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$RunDirectory,[Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$ExpectedCommit)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'WebLab.psm1') -Force
if(-not $env:LOCALAPPDATA){throw 'Windows operator lab root unavailable'}
Request-WebLabStop -RunDirectory $RunDirectory -ExpectedCommit $ExpectedCommit -AllowedRoot (Join-Path $env:LOCALAPPDATA 'ARG-Digital/AroNexa-Pilot09-Disposable')
