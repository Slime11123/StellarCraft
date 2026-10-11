@echo off
setlocal
set "ROOT=%~dp0"
set "VSDEV=C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\Tools\VsDevCmd.bat"
if not exist "%VSDEV%" (
  echo Visual Studio 2022 Community was not found: %VSDEV%
  exit /b 1
)
call "%VSDEV%" -arch=x64 >nul
if errorlevel 1 exit /b 1
if not exist "%ROOT%build" mkdir "%ROOT%build"
cl /nologo /utf-8 /std:c++17 /EHsc /O2 /MD /LD /DRESHADE_ADDON=1 ^
  /I"%ROOT%third_party\reshade\include" ^
  /Fo:"%ROOT%build\StellarCraft.obj" ^
  "%ROOT%StellarCraft.cpp" ^
  /link /OUT:"%ROOT%build\StellarCraft.addon" /IMPLIB:"%ROOT%build\StellarCraft.lib" user32.lib kernel32.lib
if errorlevel 1 exit /b 1
echo Built %ROOT%build\StellarCraft.addon
