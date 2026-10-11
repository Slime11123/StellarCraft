@echo off
setlocal

rem ==== StellarCraft MC mod build script ====
rem Target: Minecraft 1.20.1 + Fabric (Loom 1.7.4) + Java 17 + Gradle 8.8

set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "C:\Users\StickySlime\WorkBuddy\2026-10-07-12-54-48\stellarcraft\mc-mod"

echo ==== Building StellarCraft MC mod ====
echo Target: Minecraft 1.20.1 / Fabric / Java 17 / Gradle 8.8
echo.

call gradlew.bat build --no-daemon

if errorlevel 1 (
    echo.
    echo ==== BUILD FAILED. See errors above. ====
    pause
    exit /b 1
)

echo.
echo ==== BUILD SUCCESS! Output ====
echo build\libs\stellarcraft-0.1.0.jar
echo.
echo Put this jar into your Minecraft mods folder.
pause

endlocal
