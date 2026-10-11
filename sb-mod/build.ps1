# StellarCraft — 剑星侧 mod 编译脚本 (PowerShell)
# 直接用 MSVC cl.exe 编译,绕过 xmake 的注册表检测问题。

$ErrorActionPreference = "Stop"

# 路径
$VcVars = "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Src = Join-Path $Root "src"
$Protocol = Join-Path (Split-Path -Parent $Root) "protocol"
$OutDir = Join-Path $Root "build\manual"
$OutDll = Join-Path $OutDir "stellarcraft_sb.dll"

Write-Host "=== StellarCraft 剑星侧 mod 编译 ===" -ForegroundColor Cyan

# 1. 初始化 MSVC 环境
Write-Host "[1/3] 初始化 MSVC 环境..."
$envSetup = cmd /c "`"$VcVars`" >nul 2>&1 && set"
if ($LASTEXITCODE -ne 0) {
    Write-Host "错误: vcvars64.bat 执行失败" -ForegroundColor Red
    exit 1
}
# 把 cmd 的 set 输出解析成环境变量
foreach ($line in $envSetup) {
    if ($line -match "^([^=]+)=(.*)$") {
        [System.Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], "Process")
    }
}
Write-Host "    MSVC 环境已加载: $((Get-Command cl.exe).Source)"

# 2. 编译所有源文件
Write-Host "[2/3] 编译源文件..."
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$sources = Get-ChildItem -Path $Src -Filter "*.cpp" | ForEach-Object { $_.FullName }
$objFiles = @()

foreach ($src in $sources) {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($src)
    $obj = Join-Path $OutDir "$name.obj"
    Write-Host "    编译: $name.cpp"
    & cl.exe -c -nologo -MD -O2 -std:c++17 -EHsc -utf-8 `
        -I"$Src" -I"$Protocol" `
        -DNDEBUG -DSTELLARCRAFT_RELEASE=1 `
        -Fo"$obj" "$src" 2>&1 | Where-Object { $_ -match "error|warning C4" }
    if ($LASTEXITCODE -ne 0) {
        Write-Host "    编译失败: $name.cpp" -ForegroundColor Red
        exit 1
    }
    $objFiles += $obj
}

# 3. 链接成 DLL
Write-Host "[3/3] 链接 DLL..."
& link.exe -nologo -DLL -OUT:"$OutDll" $objFiles user32.lib kernel32.lib gdi32.lib 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "链接失败" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "=== 编译成功! ===" -ForegroundColor Green
Write-Host "产物: $OutDll"
Write-Host "大小: $((Get-Item $OutDll).Length) 字节"
