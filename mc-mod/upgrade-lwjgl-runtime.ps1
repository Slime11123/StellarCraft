param([string]$VersionDirectory = 'D:\Release 2.7.4\.minecraft\versions\1.20.1-Fabric 0.19.5')
$ErrorActionPreference = 'Stop'
if (Get-Process java,javaw -ErrorAction SilentlyContinue) { throw 'Close Minecraft first.' }
$versionPath = Join-Path $VersionDirectory ((Split-Path $VersionDirectory -Leaf) + '.json')
$libraryRoot = [IO.Path]::GetFullPath((Join-Path $VersionDirectory '..\..\libraries'))
$version = Get-Content -Raw -LiteralPath $versionPath | ConvertFrom-Json
$libraries = @()
foreach ($library in $version.libraries) {
    if ($library.name -match '^org\.lwjgl:.*:3\.3\.1(?:$|:)') {
        # This instance is Windows-only; retain its existing architecture rules.
        if ($library.name -match ':natives-(linux|macos)') { continue }
        $artifact = $library.downloads.artifact
        $nextPath = $artifact.path.Replace('3.3.1','3.3.3')
        $file = Join-Path $libraryRoot $nextPath
        if (!(Test-Path -LiteralPath $file)) { throw "Missing cached runtime artifact: $file" }
        $library.name = $library.name.Replace('3.3.1','3.3.3')
        $artifact.path = $nextPath
        $artifact.url = $artifact.url.Replace('3.3.1','3.3.3')
        $artifact.sha1 = (Get-FileHash -LiteralPath $file -Algorithm SHA1).Hash.ToLowerInvariant()
        $artifact.size = (Get-Item -LiteralPath $file).Length
    }
    $libraries += $library
}
$version.libraries = $libraries
$backup = $versionPath + '.' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.backup'
Copy-Item -LiteralPath $versionPath -Destination $backup
[IO.File]::WriteAllText($versionPath, ($version | ConvertTo-Json -Depth 100), [Text.UTF8Encoding]::new($false))
Write-Output "LWJGL 3.3.3 installed for Java 21; manifest backup: $backup"
