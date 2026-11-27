<#
.SYNOPSIS
  构建 TV 的 Python 运行时模块（Chaquopy），按 ABI 出 APK。

.DESCRIPTION
  与 tv-node 的差别：这里没有"上游二进制要下载"这一步 —— CPython、标准库与
  wheel 全由 Chaquopy 插件在构建期处理（pip 会按 ABI 取 Android wheel）。
  所以脚本只做三件事：调 gradle、收集产物、改名成 tv-python-<abi>-<ver>.apk。

  ★ 依赖：构建机要有一个与 target 匹配的 Python（3.10）。
    Windows 默认取 pyenv 的 3.10.6；其他平台用 python3，CI 要用
    actions/setup-python 钉 3.10，否则 Chaquopy 直接拒绝。
  ★ 网络：Chaquopy 的 pip 要连 pypi 与它自己的 Android wheel 仓库。
    国内环境建议在 pip 块里加 --extra-index-url 镜像（build.gradle 已配）。

.EXAMPLE
  ./build-runtime-module.ps1
  ./build-runtime-module.ps1 -Flavors arm64 -OutDir ../../release/runtime-python-0.1.0
#>
[CmdletBinding()]
param(
    [string[]]$Flavors = @('arm64', 'v7a', 'x86_64'),
    [string]$OutDir,
    # ★ 显式补丁号（2026-11-26）：CI 全量发版传壳补丁号（1.2.23 → 23），
    #   版本变成 0.1.23，客户端才会按版本号更新；留空时保持 0.1.0。
    [string]$PatchVersion = '',
    # ★ 完整版本名（v1.3.0 起）：CI 传 0.<app次段>.<app修订>（1.3.0 → 0.3.0）。
    #   旧方案 0.1.<壳补丁> 在 minor 进位时会倒退（1.3.0 → 0.1.0 < 0.1.24），
    #   客户端按版本号判定"已是最新"就永远升不上去。
    [string]$ModuleVersion = ''
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$tvRoot = Split-Path -Parent $here
$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $tvRoot))
$versionName = if ($ModuleVersion -match '^\d+\.\d+\.\d+$') { $ModuleVersion }
              elseif ($PatchVersion -match '^\d+$') { "0.1.$PatchVersion" }
              else { '0.1.0' }
if (-not $OutDir) { $OutDir = Join-Path $repoRoot "release\runtime-python-$versionName" }

$gradle = if (Test-Path 'C:\Android\gradle-8.9\bin\gradle.bat') {
    'C:\Android\gradle-8.9\bin\gradle.bat'
} else { 'gradle' }

# ★ pyrt.py 只有一份真源（server/pyrt/pyrt.py），每次构建前覆盖过来。
#   两份各自维护必然漂：2026-11-25 实测 TV 副本落后 162 行（缺"URL 脚本落盘"、
#   缺 tempfile/URLError 修复），而 APK 里跑的就是那份旧的 —— "PC 上好了、
#   TV 上没好"这类问题排查起来极费时间。
$pyrtSrc = Join-Path $repoRoot 'server\pyrt\pyrt.py'
$pyrtDst = Join-Path $here 'src\main\python\pyrt.py'
if (-not (Test-Path $pyrtSrc)) { throw "找不到 pyrt 真源：$pyrtSrc" }
Copy-Item $pyrtSrc $pyrtDst -Force
Write-Host "同步 pyrt.py <- server/pyrt/pyrt.py" -ForegroundColor DarkCyan

foreach ($f in $Flavors) {
    $task = ":runtime-python:assemble$($f.Substring(0,1).ToUpper() + $f.Substring(1))Release"
    Write-Host "== $f -> $task ==" -ForegroundColor Cyan
    Push-Location $tvRoot
    try {
        & $gradle $task "-PmeowVersionName=$versionName"
        if ($LASTEXITCODE -ne 0) { throw "gradle $task 失败（exit $LASTEXITCODE）" }
    } finally {
        Pop-Location
    }
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$found = Get-ChildItem -Path (Join-Path $here 'build\outputs\apk') -Recurse -Filter 'tv-python-*.apk'
if (-not $found) { throw "没有产出任何 tv-python-*.apk（看 gradle 输出）" }
foreach ($f in $found) {
    Copy-Item $f.FullName (Join-Path $OutDir $f.Name) -Force
    $mb = [math]::Round($f.Length / 1MB, 2)
    Write-Host "  $($f.Name)  ${mb}MB" -ForegroundColor Green
}
Write-Host "产物目录：$OutDir"
