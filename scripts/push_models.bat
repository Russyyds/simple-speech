@echo off
setlocal enabledelayedexpansion

set "ADB=%~1"
if "%ADB%"=="" set "ADB=D:\platform-tools-latest-windows\platform-tools\adb.exe"

set "LOCAL_MODEL_ROOT=%~2"
if "%LOCAL_MODEL_ROOT%"=="" set "LOCAL_MODEL_ROOT=E:\tencent"

set "PACKAGE=%~3"
if "%PACKAGE%"=="" set "PACKAGE=com.tencent.simplespeech"

set "DEVICE_ROOT=/sdcard/Android/data/%PACKAGE%/files/models"
call "%ADB%" shell "mkdir -p '%DEVICE_ROOT%'"
if errorlevel 1 exit /b 1

call :push_model "Hy-MT1.5-1.8B-1.25bit" "Hy-MT1.5-1.8B-1.25bit-GGUF" "*1.25bit*.gguf"
call :push_model "Hy-MT1.5-1.8B-4bit" "Hy-MT1.5-1.8B-1.25bit-GGUF" "*4bit*.gguf"
call :push_model "Hy-MT1.5-1.8B-8bit" "Hy-MT1.5-1.8B-1.25bit-GGUF" "*8bit*.gguf"
call :push_model "Hy-MT1.5-1.8B-2bit" "Hy-MT1.5-1.8B-2bit-GGUF" "*2bit*.gguf"
call :push_model "Fun-ASR-Nano-0.8B" "Fun-ASR-Nano-0.8B" "*.gguf"
call :push_model "Fun-CosyVoice3-0.5B" "Fun-CosyVoice3-0.5B" "*.gguf"
exit /b 0

:push_model
set "DEVICE_NAME=%~1"
set "LOCAL_NAME=%~2"
set "PATTERN=%~3"
set "SRC=%LOCAL_MODEL_ROOT%\%LOCAL_NAME%"

if not exist "%SRC%" exit /b 0

call "%ADB%" shell "mkdir -p '%DEVICE_ROOT%/%DEVICE_NAME%'"
if errorlevel 1 exit /b 1

for %%F in ("%SRC%\%PATTERN%") do (
    if exist "%%~fF" (
        call "%ADB%" push "%%~fF" "%DEVICE_ROOT%/%DEVICE_NAME%/"
        if errorlevel 1 exit /b 1
        call "%ADB%" shell "run-as %PACKAGE% sh -c 'mkdir -p files/models/%DEVICE_NAME% && cp \"%DEVICE_ROOT%/%DEVICE_NAME%/%%~nxF\" \"files/models/%DEVICE_NAME%/\"'" >nul 2>nul
        cmd /c exit /b 0
    )
    if errorlevel 1 exit /b 1
)
exit /b 0
