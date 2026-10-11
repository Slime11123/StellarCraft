param([string]$JdkDirectory = 'C:\Users\StickySlime\jdk-21.0.12.1+1')
$ErrorActionPreference = 'Stop'
$cacheRoot = Join-Path $env:USERPROFILE '.gradle\caches'
$javac = Join-Path $JdkDirectory 'bin\javac.exe'
$javaTool = Join-Path $JdkDirectory 'bin\java.exe'
$jarTool = Join-Path $JdkDirectory 'bin\jar.exe'
$baseline = Join-Path $PSScriptRoot 'build\libs\stellarcraft-0.1.0.jar'
$existing = Join-Path $PSScriptRoot 'build\classes\java\main'
$patchRoot = Join-Path $PSScriptRoot ('build\gameplay-patch-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$classes = Join-Path $patchRoot 'classes'
$result = Join-Path $PSScriptRoot 'build\libs\stellarcraft-0.1.0-dx12.jar'
$mcJar = Join-Path $cacheRoot 'fabric-loom\minecraftMaven\net\minecraft\minecraft-merged\1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2\minecraft-merged-1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2.jar'
$mappings = Join-Path $cacheRoot 'fabric-loom\1.20.1\loom.mappings.1_20_1.layered+hash.2198-v2\mappings.tiny'
$moduleRoot = Join-Path $cacheRoot 'modules-2\files-2.1'
$groups = @('net.fabricmc','org.ow2.asm','org.lwjgl','org.spongepowered','org.joml','org.jetbrains','com.google.guava','com.google.code.gson','com.mojang','it.unimi.dsi','org.slf4j','commons-io','io.netty')
$dependencies = @($groups | ForEach-Object { $dir = Join-Path $moduleRoot $_; if (Test-Path -LiteralPath $dir) { Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' -File | Where-Object { $_.Name -notmatch 'sources|javadoc|natives' } | ForEach-Object FullName } })
$dependencies += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'build\loom-cache\remapped_working') -Filter '*.jar' -File | Where-Object { $_.Name -notmatch 'sources|javadoc' } | ForEach-Object FullName
$remapper = @($dependencies | Where-Object { $_ -match '\\tiny-remapper\\0\.10\.3\\' })[0]
foreach ($path in @($javac,$javaTool,$jarTool,$baseline,$existing,$mcJar,$mappings,$remapper)) { if (!$path -or !(Test-Path -LiteralPath $path)) { throw "Missing build input: $path" } }
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$classpath = (@($existing,$mcJar) + $dependencies) -join ';'
$sources = @('dev\stellarcraft\link\Proto.java','dev\stellarcraft\link\SharedMemory.java','dev\stellarcraft\SBAutoLauncher.java','dev\stellarcraft\client\InputBridge.java','dev\stellarcraft\world\BlockBridge.java','dev\stellarcraft\world\BlockVisuals.java','dev\stellarcraft\world\mixin\ClientLevelMixin.java','dev\stellarcraft\render\NativePixels.java','dev\stellarcraft\render\HandMeshBridge.java','dev\stellarcraft\world\mixin\GameRendererMixin.java')
$sources += @('dev\stellarcraft\client\NativeDisplay.java','dev\stellarcraft\client\NativeMovement.java','dev\stellarcraft\client\ClientBridge.java','dev\stellarcraft\world\mixin\MouseMixin.java','dev\stellarcraft\world\mixin\EntityCollisionMixin.java','dev\stellarcraft\world\mixin\WorldLayerMixin.java')
$sources += @('dev\stellarcraft\client\ElytraBridge.java')
$sources += @('dev\stellarcraft\world\mixin\NativeFireworkMixin.java')
$sources += @('dev\stellarcraft\render\FrameExporter.java')
$sources += @('dev\stellarcraft\world\PlacedBlocks.java','dev\stellarcraft\world\mixin\TerrainTargetMixin.java')
$sources += @('dev\stellarcraft\StellarCraftMod.java')
$sources += @('dev\stellarcraft\world\VirtualTerrain.java','dev\stellarcraft\render\BodyMeshBridge.java','dev\stellarcraft\render\NativePoseWriter.java')
$sources += @('dev\stellarcraft\render\NativeRigidParts.java')
$sources += @('dev\stellarcraft\world\mixin\NativePlayerMixin.java','dev\stellarcraft\world\mixin\NativeLocalPlayerMixin.java','dev\stellarcraft\world\mixin\NativeServerMovementMixin.java','dev\stellarcraft\world\mixin\NativeClientPositionMixin.java')
$sources += @('dev\stellarcraft\world\mixin\NativeBlockUseMixin.java','dev\stellarcraft\world\mixin\NativeBlockItemMixin.java','dev\stellarcraft\world\mixin\GuiAlphaMixin.java','dev\stellarcraft\world\DamageBridge.java')
$sources += @('dev\stellarcraft\world\mixin\NativeHudMixin.java')
$sources += @('dev\stellarcraft\world\NativeEntities.java','dev\stellarcraft\world\NativeWorldEvents.java','dev\stellarcraft\render\EntityMeshBridge.java','dev\stellarcraft\world\mixin\NativeProjectileMixin.java')
$sources += @('dev\stellarcraft\world\mixin\NativeBoatMixin.java')
$sources += @('dev\stellarcraft\world\NativeQa.java')
$sources += @('dev\stellarcraft\world\mixin\NativeEntityDamageMixin.java')
$sources += @('dev\stellarcraft\world\mixin\NativeLightSupportMixin.java')
# TinyRemapper must see our LivingEntity subclass to resolve inherited calls made
# through ActorProxy (isRemoved/discard/setPos, etc.) to intermediary names.
# A Minecraft-only remap classpath cannot infer the hierarchy of a mod class.
$sources += @('dev\stellarcraft\world\ActorProxy.java')
$compileArgs = @('--enable-preview','--release','21','-encoding','UTF-8','-proc:none','-classpath',('"' + $classpath.Replace('\','/') + '"'),'-d',('"' + $classes.Replace('\','/') + '"'))
$compileArgs += $sources | ForEach-Object { '"' + (Join-Path $PSScriptRoot ('src\main\java\' + $_)).Replace('\','/') + '"' }
$argfile = Join-Path $patchRoot 'javac.args'
[IO.File]::WriteAllLines($argfile,$compileArgs,[Text.UTF8Encoding]::new($false))
& $javac ('@' + $argfile)
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
$inputJar = Join-Path $patchRoot 'patch-named.jar'
$remappedJar = Join-Path $patchRoot 'patch-intermediary.jar'
& $jarTool --create --file $inputJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Patch JAR creation failed' }
$remapArgs = @('-classpath',('"' + (($dependencies -join ';').Replace('\','/')) + '"'),'net.fabricmc.tinyremapper.Main',('"' + $inputJar.Replace('\','/') + '"'),('"' + $remappedJar.Replace('\','/') + '"'),('"' + $mappings.Replace('\','/') + '"'),'named','intermediary',('"' + $mcJar.Replace('\','/') + '"'))
$remapArgs += $dependencies | ForEach-Object { '"' + $_.Replace('\','/') + '"' }
$remapFile = Join-Path $patchRoot 'remap.args'
[IO.File]::WriteAllLines($remapFile,$remapArgs,[Text.UTF8Encoding]::new($false))
& $javaTool ('@' + $remapFile)
if ($LASTEXITCODE -ne 0) { throw 'Fabric remapping failed' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$remappedClasses = Join-Path $patchRoot 'remapped'
[IO.Compression.ZipFile]::ExtractToDirectory($remappedJar,$remappedClasses)
Copy-Item -LiteralPath $baseline -Destination $result -Force
& $jarTool --update --file $result -C $remappedClasses dev
if ($LASTEXITCODE -ne 0) { throw 'JAR update failed' }
& $jarTool --update --file $result -C (Join-Path $PSScriptRoot 'src\main\resources') stellarcraft.mixins.json
if ($LASTEXITCODE -ne 0) { throw 'Mixin configuration update failed' }
# Fail the build before deployment if inherited proxy calls retain named symbols.
$javap = Join-Path $JdkDirectory 'bin\javap.exe'
$bridgeBytecode = (& $javap -classpath $result -p -c dev.stellarcraft.client.ClientBridge) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Proxy bytecode inspection failed' }
if ($bridgeBytecode -match 'ActorProxy\.(isRemoved|discard):') {
    throw 'Inherited ActorProxy calls were not remapped; do not deploy this JAR'
}
if ($bridgeBytecode -notmatch 'ActorProxy\.method_31481:\(\)Z' -or
    $bridgeBytecode -notmatch 'ActorProxy\.method_31472:\(\)V') {
    throw 'Expected 1.20.1 intermediary proxy lifecycle calls are missing'
}
Write-Output 'PASS: ActorProxy inherited lifecycle calls use intermediary names'
Write-Output $result
