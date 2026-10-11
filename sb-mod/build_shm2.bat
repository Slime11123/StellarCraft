@echo off
setlocal
set "SB=%~dp0"
set "MSVC=C:/Program Files\Microsoft Visual Studio\2022\Community\VC\Tools\MSVC\14.44.35207"
set "SDK=C:/Program Files (x86)\Windows Kits\10"
set "SDKVER=10.0.22621.0"
set "OUTDIR=%SB%\build\manual"
set "OBJDIR=%OUTDIR%\obj"
set "INCLUDE=%MSVC%\include;%SDK%\Include\%SDKVER%\ucrt;%SDK%\Include\%SDKVER%\shared;%SDK%\Include\%SDKVER%\um;%SB%\lua-5.4.7\src"
set "LIB=%MSVC%\lib\x64;%SDK%\Lib\%SDKVER%\ucrt\x64;%SDK%\Lib\%SDKVER%\um\x64"
"%MSVC%\bin\HostX64\x64\cl.exe" -c -nologo -MD -O2 -std:c++17 -EHsc -utf-8 -DNDEBUG -D_CRT_SECURE_NO_WARNINGS -Fo"%OBJDIR%\shm_bridge.obj" "%SB%\src\shm_bridge.cpp"
if errorlevel 1 goto :fail
"%MSVC%\bin\HostX64\x64\link.exe" -nologo -DLL -OUT:"%OUTDIR%\stellarcraft_shm.dll" "%OBJDIR%\shm_bridge.obj" "%OBJDIR%\lapi.obj" "%OBJDIR%\lauxlib.obj" "%OBJDIR%\lbaselib.obj" "%OBJDIR%\lcode.obj" "%OBJDIR%\lcorolib.obj" "%OBJDIR%\lctype.obj" "%OBJDIR%\ldblib.obj" "%OBJDIR%\ldebug.obj" "%OBJDIR%\ldo.obj" "%OBJDIR%\ldump.obj" "%OBJDIR%\lfunc.obj" "%OBJDIR%\lgc.obj" "%OBJDIR%\linit.obj" "%OBJDIR%\liolib.obj" "%OBJDIR%\llex.obj" "%OBJDIR%\lmathlib.obj" "%OBJDIR%\lmem.obj" "%OBJDIR%\loadlib.obj" "%OBJDIR%\lobject.obj" "%OBJDIR%\lopcodes.obj" "%OBJDIR%\loslib.obj" "%OBJDIR%\lparser.obj" "%OBJDIR%\lstate.obj" "%OBJDIR%\lstring.obj" "%OBJDIR%\lstrlib.obj" "%OBJDIR%\ltable.obj" "%OBJDIR%\ltablib.obj" "%OBJDIR%\ltm.obj" "%OBJDIR%\lundump.obj" "%OBJDIR%\lutf8lib.obj" "%OBJDIR%\lvm.obj" "%OBJDIR%\lzio.obj" kernel32.lib
if errorlevel 1 goto :fail
echo === SHM DLL BUILD SUCCESS ===
exit /b 0
:fail
echo === SHM DLL BUILD FAILED ===
exit /b 1
