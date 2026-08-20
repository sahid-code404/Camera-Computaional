@echo off
setlocal enabledelayedexpansion
set GRADLE_VERSION=9.5.0
set GRADLE_SHA256=553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746
set ROOT=%~dp0
set CACHE=%ROOT%.gradle-bootstrap
set DIST=%CACHE%\gradle-%GRADLE_VERSION%
set ZIP=%CACHE%\gradle-%GRADLE_VERSION%-bin.zip

where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
  gradle %*
  exit /b %ERRORLEVEL%
)

if not exist "%CACHE%" mkdir "%CACHE%"
if not exist "%DIST%\bin\gradle.bat" (
  if not exist "%ZIP%" (
    echo Downloading Gradle %GRADLE_VERSION%...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP%'"
    if errorlevel 1 exit /b 1
  )
  for /f %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%ZIP%').Hash.ToLower()"') do set ACTUAL=%%H
  if /I not "!ACTUAL!"=="%GRADLE_SHA256%" (
    echo Gradle ZIP checksum mismatch.
    del /q "%ZIP%"
    exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ZIP%' '%CACHE%'"
  if errorlevel 1 exit /b 1
)
call "%DIST%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
