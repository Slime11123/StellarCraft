@echo off
REM StellarCraft - SB side mod build script
REM Direct MSVC cl.exe compile with MANUALLY set INCLUDE/LIB paths.
REM This avoids vcvars' registry detection (reg.exe) entirely, so it works
REM even when reg.exe is blocked.
setlocal enabledelayedexpansion

echo === StellarCraft SB mod build ===

REM ---- Manual MSVC + Windows SDK environment (no reg.exe needed) ----
set "VS=2022"
set "VC=C:\Program Files\Microsoft Visual Studio\2022\Community\VC"
set "MSVC_VER=14.44.35207"
set "SDK_VER=10.0.22621.0"
set "KITS=C:\Program Files (x86)\Windows Kits\10"

set "MSVC_INC=%VC%\Tools\MSVC\%MSVC_VER%\include"
set "MSVC_LIB=%VC%\Tools\MSVC\%MSVC_VER%\lib\x64"
set "SDK_INC=%KITS%\Include\%SDK_VER%"
set "SDK_LIB=%KITS%\Lib\%SDK_VER%"

set "INCLUDE=%MSVC_INC%;%SDK_INC%\ucrt;%SDK_INC%\shared;%SDK_INC%\um;%SDK_INC%\winrt;%SDK_INC%\cppwinrt"
set "LIB=%MSVC_LIB%;%SDK_LIB%\ucrt\x64;%SDK_LIB%\um\x64"

set "CL_EXE=%VC%\Tools\MSVC\%MSVC_VER%\bin\HostX64\x64\cl.exe"
set "LINK_EXE=%VC%\Tools\MSVC\%MSVC_VER%\bin\HostX64\x64\link.exe"

echo     cl.exe: %CL_EXE%

REM ---- Paths ----
set "ROOT=%~dp0"
set "SRC=%ROOT%src"
set "PROTOCOL=%ROOT%..\protocol"
set "OUTDIR=%ROOT%build\manual"
if not exist "%OUTDIR%" mkdir "%OUTDIR%"

echo [Step 2/3] Compile sources...

set "OBJS="
for %%f in ("%SRC%\*.cpp") do (
    echo     Compile: %%~nxf
    "%CL_EXE%" -c -nologo -MD -O2 -std:c++17 -EHsc -utf-8 -I"%SRC%" -I"%PROTOCOL%" -DNDEBUG -DSTELLARCRAFT_RELEASE=1 -Fo"%OUTDIR%\%%~nf.obj" "%%f"
    if errorlevel 1 (
        echo     BUILD FAILED: %%~nxf
        exit /b 1
    )
    set "OBJS=!OBJS! "%OUTDIR%\%%~nf.obj""
)

echo [Step 3/3] Link DLL...
"%LINK_EXE%" -nologo -DLL -OUT:"%OUTDIR%\stellarcraft_sb.dll" %OBJS% user32.lib kernel32.lib gdi32.lib
if errorlevel 1 (
    echo LINK FAILED
    exit /b 1
)

echo.
echo === BUILD SUCCESS ===
echo Output: %OUTDIR%\stellarcraft_sb.dll
endlocal
