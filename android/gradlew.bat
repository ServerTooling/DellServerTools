@echo off
setlocal

set APP_HOME=%~dp0
set WRAPPER_PROPS=%APP_HOME%gradle\wrapper\gradle-wrapper.properties

if not exist "%WRAPPER_PROPS%" (
  echo Missing %WRAPPER_PROPS%
  exit /b 1
)

for /f "tokens=1,* delims==" %%A in (%WRAPPER_PROPS%) do (
  if "%%A"=="distributionUrl" set DISTRIBUTION_URL=%%B
)

if "%DISTRIBUTION_URL%"=="" (
  echo distributionUrl is not set in %WRAPPER_PROPS%
  exit /b 1
)

if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%APP_HOME%.gradle
set BOOTSTRAP_DIR=%GRADLE_USER_HOME%\bootstrap
if not exist "%BOOTSTRAP_DIR%" mkdir "%BOOTSTRAP_DIR%"

for %%F in ("%DISTRIBUTION_URL%") do set DIST_FILE=%BOOTSTRAP_DIR%\%%~nxF
for %%F in ("%DIST_FILE%") do set DIST_NAME=%%~nF
set DIST_DIR=%BOOTSTRAP_DIR%\%DIST_NAME%

if not exist "%DIST_FILE%" (
  powershell -Command "Invoke-WebRequest -Uri '%DISTRIBUTION_URL%' -OutFile '%DIST_FILE%'"
)

if not exist "%DIST_DIR%" (
  powershell -Command "Expand-Archive -Path '%DIST_FILE%' -DestinationPath '%BOOTSTRAP_DIR%' -Force"
)

call "%DIST_DIR%\bin\gradle.bat" -p "%APP_HOME%" %*
