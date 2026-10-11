param(
    [string]$GameDirectory = 'D:\SteamLibrary\steamapps\common\StellarBlade\SB\Binaries\Win64',
    [string]$MinecraftModsDirectory = 'D:\Release 2.7.4\.minecraft\versions\1.20.1-Fabric 0.19.5\mods',
    [Parameter(Mandatory=$true)][string]$ReShadeRuntime
)
$ErrorActionPreference = 'Stop'
if (Get-Process 'SB-Win64-Shipping','overlay','java','javaw' -ErrorAction SilentlyContinue) {
    throw 'Close Stellar Blade and Minecraft before installing.'
}
$game = (Resolve-Path -LiteralPath $GameDirectory).Path
$mods = (Resolve-Path -LiteralPath $MinecraftModsDirectory).Path
$runtime = (Resolve-Path -LiteralPath $ReShadeRuntime).Path
$addon = Join-Path $PSScriptRoot 'build\StellarCraft.addon'
$shmDll = Join-Path $PSScriptRoot '..\build\manual\stellarcraft_shm.dll'
$jar = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\mc-mod\build\libs\stellarcraft-0.1.0-dx12.jar'))
if (!(Test-Path -LiteralPath $addon) -or !(Test-Path -LiteralPath $jar) -or !(Test-Path -LiteralPath $shmDll)) { throw 'Build outputs are missing.' }
$backup = Join-Path $PSScriptRoot ('deployment-backups\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
foreach ($name in @('overlay.exe','dxgi.dll','ReShade.ini','StellarCraft.ini','StellarCraft.addon')) {
    $target = Join-Path $game $name
    if (Test-Path -LiteralPath $target) { Copy-Item -LiteralPath $target -Destination (Join-Path $backup $name) }
}
$oldMods = @(Get-ChildItem -LiteralPath $mods -Filter 'stellarcraft*.jar' -File)
foreach ($old in $oldMods) { Copy-Item -LiteralPath $old.FullName -Destination (Join-Path $backup $old.Name) }
$shaderDir = Join-Path $game 'reshade-shaders\Shaders'
$luaDir = Join-Path $game 'ue4ss\Mods\StellarCraft\Scripts'
$shmDestination = Join-Path $game 'ue4ss\Mods\StellarCraft\stellarcraft_shm.dll'
if (Test-Path -LiteralPath $shmDestination) { Copy-Item -LiteralPath $shmDestination -Destination (Join-Path $backup 'stellarcraft_shm.dll') }
if (!(Test-Path -LiteralPath $luaDir)) { throw 'StellarCraft UE4SS mod is missing.' }
foreach ($name in @('main.lua','block_visuals.lua','native_world.lua')) {
    if (Test-Path -LiteralPath (Join-Path $luaDir $name)) {
        Copy-Item -LiteralPath (Join-Path $luaDir $name) -Destination (Join-Path $backup $name)
    }
}
if (Test-Path -LiteralPath (Join-Path $shaderDir 'StellarCraft.fx')) {
    Copy-Item -LiteralPath (Join-Path $shaderDir 'StellarCraft.fx') -Destination (Join-Path $backup 'StellarCraft.fx')
}
New-Item -ItemType Directory -Path $shaderDir -Force | Out-Null
$runtimeDestination = Join-Path $game 'dxgi.dll'
if (![StringComparer]::OrdinalIgnoreCase.Equals($runtime, $runtimeDestination)) {
    Copy-Item -LiteralPath $runtime -Destination $runtimeDestination -Force
}
Copy-Item -LiteralPath $addon -Destination (Join-Path $game 'StellarCraft.addon') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'StellarCraft.fx') -Destination (Join-Path $shaderDir 'StellarCraft.fx') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'ReShade.ini') -Destination (Join-Path $game 'ReShade.ini') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'StellarCraft.ini') -Destination (Join-Path $game 'StellarCraft.ini') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\lua\main.lua'),(Join-Path $PSScriptRoot '..\lua\block_visuals.lua'),(Join-Path $PSScriptRoot '..\lua\native_world.lua') -Destination $luaDir -Force
Copy-Item -LiteralPath $shmDll -Destination $shmDestination -Force
$assetDir = Join-Path $game 'ue4ss\Mods\StellarCraft\block-assets'
New-Item -ItemType Directory -Path $assetDir -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '..\lua\block-assets') -File | Copy-Item -Destination $assetDir -Force
foreach ($old in $oldMods) { Remove-Item -LiteralPath $old.FullName }
Copy-Item -LiteralPath $jar -Destination (Join-Path $mods 'stellarcraft-0.1.0.jar') -Force
$oldOverlay = Join-Path $game 'overlay.exe'
if (Test-Path -LiteralPath $oldOverlay) { Remove-Item -LiteralPath $oldOverlay }
[ordered]@{game=$game; minecraftMods=$mods; backup=$backup; installedAt=(Get-Date).ToString('o')} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $backup 'manifest.json') -Encoding UTF8
Write-Output "Installed StellarCraft DX12 add-on; backup: $backup"
