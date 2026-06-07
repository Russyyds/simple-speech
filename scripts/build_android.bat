@echo off
setlocal

set "GRADLE_BAT=%~1"
if "%GRADLE_BAT%"=="" set "GRADLE_BAT=%~dp0..\gradlew.bat"

set "NINJA_DIR=%~2"
if "%NINJA_DIR%"=="" set "NINJA_DIR=D:\ninja-win"

set "JAVA_HOME=C:\Users\Tom\.jdks\corretto-11.0.20"
set "PATH=%NINJA_DIR%;%JAVA_HOME%\bin;%PATH%"

pushd "%~dp0.."
if errorlevel 1 exit /b 1

if not exist "%NINJA_DIR%\ninja.exe" (
    echo ninja.exe not found at "%NINJA_DIR%\ninja.exe"
    popd
    exit /b 1
)

if not exist "ninja.exe" copy /Y "%NINJA_DIR%\ninja.exe" "ninja.exe" >nul

call "%GRADLE_BAT%" --stop >nul
call "%GRADLE_BAT%" --no-daemon assembleDebug
set "RESULT=%ERRORLEVEL%"

if exist "ninja.exe" del /F /Q "ninja.exe" >nul
popd
exit /b %RESULT%
