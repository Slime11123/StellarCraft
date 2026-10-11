@echo off
REM ---- Build version.dll proxy (early-injection DX12 overlay hook) ----
REM Loaded by Windows at process start, BEFORE the engine creates its swap chain, so the
REM CreateSwapChain* hooks can capture the game's PRESENT queue. All real version.dll
REM exports are forwarded to the system copy via linker /EXPORT forwarders.

setlocal
set SRC=%~dp0src
set OUTDIR=%~dp0build\manual
set OBJDIR=%~dp0build\manual\obj_version

if not exist "%OUTDIR%" mkdir "%OUTDIR%"
if not exist "%OBJDIR%" mkdir "%OBJDIR%"

REM ---- Locate VS build tools (same as 编译shm.bat) ----
set "CL_EXE="
for /f "usebackq delims=" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath 2^>nul`) do (
    if exist "%%i\VC\Tools\MSVC" (
        for /d %%j in ("%%i\VC\Tools\MSVC\*") do set "CL_EXE=%%j\bin\Hostx64\x64\cl.exe"
    )
)
if "%CL_EXE%"=="" (
    echo     VS build tools not found
    exit /b 1
)
for %%i in ("%CL_EXE%") do set "VSDIR=%%~dpi.."
set "LINK_EXE=%VSDIR%\bin\Hostx64\x64\link.exe"

echo Building version.dll proxy...

"%CL_EXE%" -c -nologo -MD -O2 -std:c++17 -EHsc -utf-8 -DNDEBUG -D_CRT_SECURE_NO_WARNINGS -Fo"%OBJDIR%\version_proxy.obj" "%SRC%\version_proxy.cpp"
if errorlevel 1 ( echo     PROXY BUILD FAILED & exit /b 1 )

"%CL_EXE%" -c -nologo -MD -O2 -std:c++17 -EHsc -utf-8 -DNDEBUG -D_CRT_SECURE_NO_WARNINGS -Fo"%OBJDIR%\dx12_hook.obj" "%SRC%\dx12_hook.cpp"
if errorlevel 1 ( echo     DX12 HOOK BUILD FAILED & exit /b 1 )

echo     Linking version.dll ...
"%LINK_EXE%" -nologo -DLL -OUT:"%OUTDIR%\version.dll" "%OBJDIR%\version_proxy.obj" "%OBJDIR%\dx12_hook.obj" ^
  kernel32.lib user32.lib d3d12.lib dxgi.lib d3dcompiler.lib ^
  /EXPORT:GetFileVersionInfoA=C:\Windows\System32\version.GetFileVersionInfoA ^
  /EXPORT:GetFileVersionInfoW=C:\Windows\System32\version.GetFileVersionInfoW ^
  /EXPORT:GetFileVersionInfoExA=C:\Windows\System32\version.GetFileVersionInfoExA ^
  /EXPORT:GetFileVersionInfoExW=C:\Windows\System32\version.GetFileVersionInfoExW ^
  /EXPORT:GetFileVersionInfoSizeA=C:\Windows\System32\version.GetFileVersionInfoSizeA ^
  /EXPORT:GetFileVersionInfoSizeExA=C:\Windows\System32\version.GetFileVersionInfoSizeExA ^
  /EXPORT:GetFileVersionInfoSizeExW=C:\Windows\System32\version.GetFileVersionInfoSizeExW ^
  /EXPORT:GetFileVersionInfoSizeW=C:\Windows\System32\version.GetFileVersionInfoSizeW ^
  /EXPORT:GetFileVersionInfoByHandle=C:\Windows\System32\version.GetFileVersionInfoByHandle ^
  /EXPORT:VerQueryValueA=C:\Windows\System32\version.VerQueryValueA ^
  /EXPORT:VerQueryValueW=C:\Windows\System32\version.VerQueryValueW ^
  /EXPORT:VerLanguageNameA=C:\Windows\System32\version.VerLanguageNameA ^
  /EXPORT:VerLanguageNameW=C:\Windows\System32\version.VerLanguageNameW
if errorlevel 1 ( echo     LINK FAILED & exit /b 1 )

echo.
echo === BUILD SUCCESS ===
echo Output: %OUTDIR%\version.dll
endlocal
