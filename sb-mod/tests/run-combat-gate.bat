@echo off
setlocal
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1
cl /nologo /MD /EHsc /std:c++17 /O2 /Fe"%~dp0..\build\manual\native-combat-gate.exe" /Fo"%~dp0..\build\manual\native-combat-gate.obj" "%~dp0native-combat-gate.cpp"
if errorlevel 1 exit /b 1
"%~dp0..\build\manual\native-combat-gate.exe" "D:\SteamLibrary\steamapps\common\StellarBlade\SB\Binaries\Win64\SB-Win64-Shipping.exe"
exit /b %errorlevel%
