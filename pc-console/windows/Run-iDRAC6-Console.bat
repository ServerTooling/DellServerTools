@echo off
REM Double-click to run the iDRAC6 Virtual Console launcher.
REM It runs the PowerShell script next to this file.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0idrac6-console.ps1" %*
echo.
pause
