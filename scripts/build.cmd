@echo off
setlocal
rem ---------------------------------------------------------------------------
rem  One-click wrapper around build.ps1.
rem  Windows PowerShell blocks unsigned .ps1 files by default on this machine
rem  (execution policy = Restricted), so this .cmd bypasses the policy for the
rem  single PowerShell process only. It does NOT change the machine policy.
rem
rem  Usage:
rem    scripts\build.cmd                            (assembleDebug)
rem    scripts\build.cmd -Task assembleRelease
rem    scripts\build.cmd -Task clean,assembleDebug -ProjectRoot .toolchain\probe
rem ---------------------------------------------------------------------------
set "SCRIPT_DIR=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%build.ps1" %*
exit /b %ERRORLEVEL%
