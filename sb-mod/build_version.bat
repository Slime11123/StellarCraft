@echo off
setlocal
set "SB=C:\Users\StickySlime\WorkBuddy\2026-10-07-12-54-48\stellarcraft\sb-mod"
set "MSVC=C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Tools\MSVC\14.44.35207"
set "SDK=C:\Program Files (x86)\Windows Kits\10"
set "SDKVER=10.0.22621.0"
set "CL_EXE=%MSVC%\bin\HostX64\x64\cl.exe"
set "LINK_EXE=%MSVC\bin\HostX64\x64\link.exe"
set "LINK_EXE=%MSVC%\bin\HostX64\x64\link.exe"
set "OUTDIR=%SB%\build\manual"
set "OBJDIR=%OUTDIR%\obj_version"
if not exist "%OBJDIR%" mkdir "%OBJDIR%"

set "INCLUDE=%MSVC%\include;%SDK%\Include\%SDKVER%\ucrt;%SDK%\Include\%SDKVER%\shared;%SDK%\Include\%SDKVER%\um"
set "LIB=%MSVC%\lib\x64;%SDK%\Lib\%SDKVER%\ucrt\x64;%SDK%\Lib\%SDKVER%\um\x64"

echo [1/2] Compiling...
"%CL_EXE%" -c -nologo -MD -O2 -std:c++17 -EHsc -utf-8 -DNDEBUG -D_CRT_SECURE_NO_WARNINGS -Fo"%OBJDIR%\version_proxy.obj" "%SB%\src\version_proxy.cpp"
if errorlevel 1 goto :fail
"%CL_EXE%" -c -nologo -MD -O2 -std:c++17 -EHsc -utf-8 -DNDEBUG -D_CRT_SECURE_NO_WARNINGS -Fo"%OBJDIR%\dx12_hook.obj" "%SB%\src\dx12_hook.cpp"
if errorlevel 1 goto :fail

echo [2/2] Linking version.dll ...
"%LINK_EXE%" -nologo -DLL -OUT:"%OUTDIR%\version.dll" "%OBJDIR%\version_proxy.obj" "%OBJDIR%\dx12_hook.obj" kernel32.lib user32.lib d3d12.lib dxgi.lib d3dcompiler.lib /EXPORT:GetFileVersionInfoA /EXPORT:GetFileVersionInfoW /EXPORT:GetFileVersionInfoExA /EXPORT:GetFileVersionInfoExW /EXPORT:GetFileVersionInfoSizeA /EXPORT:GetFileVersionInfoSizeW /EXPORT:GetFileVersionInfoSizeExA /EXPORT:GetFileVersionInfoSizeExW /EXPORT:GetFileVersionInfoByHandle /EXPORT:VerQueryValueA /EXPORT:VerQueryValueW /EXPORT:VerLanguageNameA /EXPORT:VerLanguageNameW
if errorlevel 1 goto :fail

echo === VERSION DLL BUILD SUCCESS ===
echo Output: %OUTDIR%\version.dll
exit /b 0

:fail
echo === VERSION DLL BUILD FAILED ===
exit /b 1
