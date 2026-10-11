@echo off
setlocal
set "MSVC=C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Tools\MSVC\14.44.35207"
set "SDK=C:\Program Files (x86)\Windows Kits\10"
set "SDKVER=10.0.22621.0"
set "INCLUDE=%MSVC%\include;%SDK%\Include\%SDKVER%\ucrt;%SDK%\Include\%SDKVER%\shared;%SDK%\Include\%SDKVER%\um"
set "LIB=%MSVC%\lib\x64;%SDK%\Lib\%SDKVER%\ucrt\x64;%SDK%\Lib\%SDKVER%\um\x64"
"%MSVC%\bin\HostX64\x64\cl.exe" /nologo /MD /EHsc /std:c++17 /O2 /Fe"%~dp0..\build\manual\ground-ring-test.exe" /Fo"%~dp0..\build\manual\ground-ring-test.obj" "%~dp0ground-ring-regression.cpp"
if not "%errorlevel%"=="0" exit /b 1
"%~dp0..\build\manual\ground-ring-test.exe"
if not "%errorlevel%"=="0" exit /b 1
"%~dp0..\build\manual\lua.exe" "%~dp0native-movement-regression.lua" "%~dp0..\lua\main.lua"
if not "%errorlevel%"=="0" exit /b 1
"%~dp0..\build\manual\lua.exe" "%~dp0native-step-regression.lua" "%~dp0..\lua\main.lua"
if not "%errorlevel%"=="0" exit /b 1
"%~dp0..\build\manual\lua.exe" "%~dp0body-identity-regression.lua" "%~dp0..\lua\block_visuals.lua"
if not "%errorlevel%"=="0" exit /b 1
"%~dp0..\build\manual\lua.exe" "%~dp0native-world-regression.lua" "%~dp0..\lua\native_world.lua"
exit /b %errorlevel%
