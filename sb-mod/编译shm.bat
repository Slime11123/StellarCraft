@echo off
REM StellarCraft - shared-memory bridge DLL with Lua 5.4.7 bridge (Direction A).
REM Compiles shm_bridge.cpp + Lua 5.4.7 core -> stellarcraft_shm.dll.
setlocal enabledelayedexpansion

echo === StellarCraft SHM bridge build (Lua bridge) ===

set "VC=C:\Program Files\Microsoft Visual Studio\2022\Community\VC"
set "MSVC_VER=14.44.35207"
set "SDK_VER=10.0.22621.0"
set "KITS=C:\Program Files (x86)\Windows Kits\10"

set "MSVC_INC=%VC%\Tools\MSVC\%MSVC_VER%\include"
set "MSVC_LIB=%VC%\Tools\MSVC\%MSVC_VER%\lib\x64"
set "SDK_INC=%KITS%\Include\%SDK_VER%"
set "SDK_LIB=%KITS%\Lib\%SDK_VER%"

set "ROOT=%~dp0"
set "SRC=%ROOT%src"
set "LUASRC=%ROOT%lua-5.4.7\src"
set "OUTDIR=%ROOT%build\manual"
set "OBJDIR=%OUTDIR%\obj"

set "INCLUDE=%MSVC_INC%;%SDK_INC%\ucrt;%SDK_INC%\shared;%SDK_INC%\um;%SDK_INC%\winrt;%SDK_INC%\cppwinrt;%LUASRC%"
set "LIB=%MSVC_LIB%;%SDK_LIB%\ucrt\x64;%SDK_LIB%\um\x64"

set "CL_EXE=%VC%\Tools\MSVC\%MSVC_VER%\bin\HostX64\x64\cl.exe"
set "LINK_EXE=%VC%\Tools\MSVC\%MSVC_VER%\bin\HostX64\x64\link.exe"

if not exist "%OBJDIR%" mkdir "%OBJDIR%"

echo     Compiling Lua 5.4.7 core + shm_bridge.cpp ...

REM ---- Lua core (all .c except lua.c and luac.c standalone apps) ----
for %%f in (lapi lauxlib lbaselib lcode lcorolib lctype ldblib ldebug ldo ldump lfunc lgc linit liolib llex lmathlib lmem loadlib lobject lopcodes loslib lparser lstate lstring lstrlib ltable ltablib ltm lundump lutf8lib lvm lzio) do (
    "%CL_EXE%" -c -nologo -MD -O2 -utf-8 -DNDEBUG -D_CRT_SECURE_NO_WARNINGS -Fo"%OBJDIR%\%%f.obj" "%LUASRC%\%%f.c"
    if errorlevel 1 (
        echo     LUA BUILD FAILED on %%f.c
        exit /b 1
    )
)

REM ---- shm_bridge.cpp (C++ with lua bridge) ----
for %%f in (buffer hook trampoline hde\hde64) do (
    "%CL_EXE%" -c -nologo -MD -O2 -utf-8 -DNDEBUG -Fo"%OBJDIR%\mh-%%~nf.obj" "%ROOT%third_party\minhook\src\%%f.c"
    if errorlevel 1 exit /b 1
)
REM NOTE: the DX12 overlay hook was moved out of this DLL into the version.dll proxy
REM (编译version.bat) — the CreateSwapChain hooks must be installed at process start,
REM before the engine creates its swap chain, and UE4SS loads Lua mods too late.
"%CL_EXE%" -c -nologo -MD -O2 -std:c++17 -EHsc -utf-8 -DNDEBUG -D_CRT_SECURE_NO_WARNINGS -Fo"%OBJDIR%\shm_bridge.obj" "%SRC%\shm_bridge.cpp"
if errorlevel 1 (
    echo     BRIDGE BUILD FAILED
    exit /b 1
)

echo     Linking stellarcraft_shm.dll ...
"%LINK_EXE%" -nologo -DLL -OUT:"%OUTDIR%\stellarcraft_shm.dll" "%OBJDIR%\shm_bridge.obj" "%OBJDIR%\mh-buffer.obj" "%OBJDIR%\mh-hook.obj" "%OBJDIR%\mh-trampoline.obj" "%OBJDIR%\mh-hde64.obj" "%OBJDIR%\lapi.obj" "%OBJDIR%\lauxlib.obj" "%OBJDIR%\lbaselib.obj" "%OBJDIR%\lcode.obj" "%OBJDIR%\lcorolib.obj" "%OBJDIR%\lctype.obj" "%OBJDIR%\ldblib.obj" "%OBJDIR%\ldebug.obj" "%OBJDIR%\ldo.obj" "%OBJDIR%\ldump.obj" "%OBJDIR%\lfunc.obj" "%OBJDIR%\lgc.obj" "%OBJDIR%\linit.obj" "%OBJDIR%\liolib.obj" "%OBJDIR%\llex.obj" "%OBJDIR%\lmathlib.obj" "%OBJDIR%\lmem.obj" "%OBJDIR%\loadlib.obj" "%OBJDIR%\lobject.obj" "%OBJDIR%\lopcodes.obj" "%OBJDIR%\loslib.obj" "%OBJDIR%\lparser.obj" "%OBJDIR%\lstate.obj" "%OBJDIR%\lstring.obj" "%OBJDIR%\lstrlib.obj" "%OBJDIR%\ltable.obj" "%OBJDIR%\ltablib.obj" "%OBJDIR%\ltm.obj" "%OBJDIR%\lundump.obj" "%OBJDIR%\lutf8lib.obj" "%OBJDIR%\lvm.obj" "%OBJDIR%\lzio.obj" kernel32.lib
if errorlevel 1 (
    echo     LINK FAILED
    exit /b 1
)

echo.
echo === BUILD SUCCESS ===
echo Output: %OUTDIR%\stellarcraft_shm.dll
endlocal
