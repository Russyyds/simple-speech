@echo off
setlocal

set "ADB=%~1"
if "%ADB%"=="" set "ADB=D:\platform-tools-latest-windows\platform-tools\adb.exe"

set "APK=%~2"
if "%APK%"=="" set "APK=%~dp0..\app\build\outputs\apk\debug\app-debug.apk"

call "%ADB%" install -r "%APK%"
exit /b %ERRORLEVEL%
