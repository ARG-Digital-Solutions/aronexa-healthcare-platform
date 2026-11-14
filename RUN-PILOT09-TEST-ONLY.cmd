@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"
if exist ".git" (
 echo This double-click launcher is ONLY for a private extracted verified source ZIP.
 echo In a Git checkout, run scripts\pilot\Start-AroNexa-WindowsAssistedPilot.ps1 instead.
 pause
 exit /b 1
)
if not exist "PILOT07_SOURCE_SHA.txt" (
 echo STOP: verified archive SHA marker missing.
 pause
 exit /b 1
)
echo AroNexa Clinic Edition - FICTIONAL Windows Test Lab
echo This is NOT an installer or a clinic application for real patients.
echo All created data must be imaginary and are destroyed when testing stops.
set /p ACK=Type FICTIONAL to confirm isolated synthetic testing:
if /I not "%ACK%"=="FICTIONAL" (
 echo No testing started.
 pause
 exit /b 1
)
set /p SOURCE_SHA=<PILOT07_SOURCE_SHA.txt
powershell.exe -NoProfile -File ".\scripts\pilot\Start-AroNexa-WindowsAssistedPilot.ps1" -ExpectedCommit "%SOURCE_SHA%" -ConfirmFictionalDisposableLab -FromVerifiedArchive
set RESULT=%ERRORLEVEL%
echo.
if not "%RESULT%"=="0" (
 echo PILOT09 test lab failed or was cancelled. Check the first printed error.
) else (
 echo PILOT09 fictional session ended normally. This is not commercial UAT.
)
pause
exit /b %RESULT%
