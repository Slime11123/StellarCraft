param([string]$JdkDirectory='C:\Users\StickySlime\jdk-21.0.12.1+1')
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$patch=Get-ChildItem -LiteralPath (Join-Path $project 'build') -Directory -Filter 'gameplay-patch-*' | Sort-Object Name -Descending | Select-Object -First 1
if(!$patch){throw 'Run build-dx12-jar.ps1 first.'}
$classes=Join-Path $patch.FullName 'classes'
$out=Join-Path $patch.FullName 'memory-regression'
New-Item -ItemType Directory -Path $out -Force | Out-Null
& (Join-Path $JdkDirectory 'bin\javac.exe') -cp $classes -d $out (Join-Path $PSScriptRoot 'SharedMemoryRecoveryRegression.java')
if($LASTEXITCODE -ne 0){throw 'Regression compilation failed'}
& (Join-Path $JdkDirectory 'bin\java.exe') -cp ($out+';'+$classes) SharedMemoryRecoveryRegression
if($LASTEXITCODE -ne 0){throw 'Regression failed'}
