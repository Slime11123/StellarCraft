param([string]$JdkDirectory='C:\Users\StickySlime\jdk-21.0.12.1+1')
$ErrorActionPreference='Stop'
$modRoot=Split-Path -Parent $PSScriptRoot
$patch=Get-ChildItem -LiteralPath (Join-Path $modRoot 'build') -Directory -Filter 'gameplay-patch-*' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (!$patch) { throw 'Build the gameplay JAR first' }
$cache=Join-Path $env:USERPROFILE '.gradle\caches'
$mcJar=Join-Path $cache 'fabric-loom\minecraftMaven\net\minecraft\minecraft-merged\1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2\minecraft-merged-1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2.jar'
$groups=@('net.fabricmc','org.ow2.asm','org.lwjgl','org.spongepowered','org.joml','org.jetbrains','com.google.guava','com.google.code.gson','com.mojang','it.unimi.dsi','org.slf4j','commons-io','io.netty')
$dependencies=@($groups | ForEach-Object { $dir=Join-Path $cache "modules-2\files-2.1\$_"; if(Test-Path -LiteralPath $dir) {Get-ChildItem -LiteralPath $dir -Recurse -File -Filter '*.jar' | Where-Object {$_.Name -notmatch 'sources|javadoc|natives'} | ForEach-Object FullName} })
$dependencies+=Get-ChildItem -LiteralPath (Join-Path $modRoot 'build\loom-cache\remapped_working') -Filter '*.jar' -File | Where-Object {$_.Name -notmatch 'sources|javadoc'} | ForEach-Object FullName
$classPath=(@((Join-Path $patch.FullName 'classes'),(Join-Path $modRoot 'build\classes\java\main'),$mcJar)+$dependencies)-join ';'
$output=Join-Path $modRoot 'build\hit-context-test'
New-Item -ItemType Directory -Path $output -Force | Out-Null
& (Join-Path $JdkDirectory 'bin\javac.exe') -proc:none -cp $classPath -d $output (Join-Path $PSScriptRoot 'HitContextPacketTest.java')
if($LASTEXITCODE -ne 0) {throw 'Packet test compile failed'}
& (Join-Path $JdkDirectory 'bin\java.exe') -cp "$output;$classPath" HitContextPacketTest
if($LASTEXITCODE -ne 0) {throw 'Packet test failed'}
& (Join-Path $JdkDirectory 'bin\javac.exe') -proc:none -cp $classPath -d $output (Join-Path $PSScriptRoot 'NativeJumpAttackTest.java')
if($LASTEXITCODE -ne 0) {throw 'Jump attack test compile failed'}
& (Join-Path $JdkDirectory 'bin\java.exe') -cp "$output;$classPath" NativeJumpAttackTest
if($LASTEXITCODE -ne 0) {throw 'Jump attack test failed'}
