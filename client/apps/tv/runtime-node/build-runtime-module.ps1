<#
.SYNOPSIS

.DESCRIPTION
  对齐 PC 插件那套：上游二进制（libnode）不入库，构建时取并缓存；产物是
  `android-<abi>`，由 sync-packages.ps1 汇总，客户端按 platform 挑。

  ★ 内容没变就不重出包：inputs = node 版本 + 载荷 zip sha256 + JNI 桥源码 sha256
    + build.gradle sha256。与上次留下的 `.deps/<ver>/inputs.txt` 相同且产物齐全
    → 直接复用（打印 reuse），不跑 gradle。版本号 `18.20.4.<N>` 只在 inputs
    变化时 +1，这样"node 不升、载荷不改"的日常发版完全不碰它。

  libnode 来源优先级：
    1) jniLibs 里已有（-Force 时重取）
    2) -LibNodeSource（已解压的 nodejs-mobile 目录）
    3) .deps/<ver>/ 里的官方 zip（没有就下载）

.EXAMPLE
  ./build-runtime-module.ps1
  ./build-runtime-module.ps1 -Abis x86_64 -Force
#>
[CmdletBinding()]
param(
    [string[]]$Abis = @('arm64-v8a', 'armeabi-v7a', 'x86_64'),
    [string]$NodeVersion = '18.20.4',
    [string]$LibNodeSource,
    [switch]$NoFetch,
    [switch]$Force,
    [string]$OutDir,
    # ★ 显式补丁号（2026-11-26）：CI 全量发版传壳补丁号（1.2.23 → 23），
    #   版本变成 18.20.4.23，客户端才会按版本号更新；留空时沿用 .deps 自增（本地）。
    [string]$PatchVersion = '',
    # ★ 完整版本名（v1.3.0 起）：CI 传 18.20.4.<app次段*100+app修订>（1.3.0 →
    #   18.20.4.300 > 旧 18.20.4.28）。旧方案 0/倒退问题与 python 版相同。
    [string]$ModuleVersion = ''
)

$ErrorActionPreference = 'Stop'
if (-not $NodeVersion) { $NodeVersion = '18.20.4' }   # CI 传空串时兜底
if (-not $PSScriptRoot) { $PSScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path }
$modDir = $PSScriptRoot
$tvDir = Split-Path -Parent $modDir
$repoRoot = (Resolve-Path (Join-Path $tvDir '..\..\..')).Path
if (-not $OutDir) { $OutDir = Join-Path $repoRoot ('release\runtime-node-' + $NodeVersion) }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$depsDir = Join-Path $modDir ".deps\$NodeVersion"
New-Item -ItemType Directory -Force -Path $depsDir | Out-Null
$zipPath = Join-Path $depsDir "nodejs-mobile-v$NodeVersion-android.zip"
$zipUrl = "https://github.com/nodejs-mobile/nodejs-mobile/releases/download/v$NodeVersion/nodejs-mobile-v$NodeVersion-android.zip"
$inputsFile = Join-Path $depsDir 'inputs.txt'
$versionFile = Join-Path $depsDir 'version.txt'

# 本机用固定路径的 gradle 8.9；CI 上走 setup-gradle 装进 PATH 的 gradle。
# ☃ 不能用 Join-Path 'C:\Android' ...：Linux 上 Join-Path 遇到盘符会抛
#   "Cannot find drive. A drive with the name 'C' does not exist."（实测 CI 红在这行）
$gradle = 'gradle'
if ($IsWindows -or $env:OS -eq 'Windows_NT') {
    $winGradle = 'C:\Android\gradle-8.9\bin\gradle.bat'
    if (Test-Path $winGradle) { $gradle = $winGradle }
}
if (-not (Test-Path $gradle)) { $gradle = 'gradle' }

function Get-Sha([string]$path) { (Get-FileHash $path -Algorithm SHA256).Hash.ToLower() }

# Windows 上是 curl.exe（避开 PS 的 curl 别名），Linux/macOS 只有 curl。
# 写死 curl.exe 会让 CI 上必挂、本地永远正常 —— sync-packages 踩过同型的坑。
$CurlBin = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'curl.exe' } else { 'curl' }

function Ensure-Libnode([string]$abi) {
    $dst = Join-Path $modDir "src\main\jniLibs\$abi"
    New-Item -ItemType Directory -Force -Path $dst | Out-Null
    $target = Join-Path $dst 'libnode.so'
    if ((Test-Path $target) -and -not $Force) { return }

    if ($LibNodeSource) {
        foreach ($cand in @((Join-Path $LibNodeSource "$abi\libnode.so"),
                            (Join-Path $LibNodeSource "bin\$abi\libnode.so"))) {
            if (Test-Path $cand) { Copy-Item $cand $target -Force; Write-Host "  $abi <- LibNodeSource"; return }
        }
    }

    if (-not (Test-Path $zipPath)) {
        if ($NoFetch) { throw "缺 $abi\libnode.so 且指定了 -NoFetch（先准备 .deps 或传 -LibNodeSource）" }
        Write-Host "  下载 nodejs-mobile v$NodeVersion …"
        & $CurlBin -sL -m 900 -o $zipPath $zipUrl
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $zipPath)) { throw "下载失败: $zipUrl" }
        # TOFU：首次记录哈希，之后按它校验（上游换了字节就报错，而不是静默换掉）
        (Get-Sha $zipPath) | Set-Content (Join-Path $depsDir 'SHA256SUMS.zip') -Encoding ASCII
    }
    else {
        $sumFile = Join-Path $depsDir 'SHA256SUMS.zip'
        if (Test-Path $sumFile) {
            $want = (Get-Content $sumFile -Raw).Trim().ToLower()
            $got = Get-Sha $zipPath
            if ($want -and $want -ne $got) { throw "libnode zip 哈希不符（缓存被改？）：期望 $want，实际 $got" }
        }
    }

    $stage = Join-Path $depsDir "extract\$abi"
    if (-not (Test-Path (Join-Path $stage 'libnode.so'))) {
        New-Item -ItemType Directory -Force -Path $stage | Out-Null
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $za = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
        try {
            $entry = $za.Entries | Where-Object { $_.FullName -eq "bin/$abi/libnode.so" }
            if (-not $entry) { throw "zip 里没有 bin/$abi/libnode.so" }
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile(
                $entry, (Join-Path $stage 'libnode.so'), $true)
        }
        finally { $za.Dispose() }
    }
    Copy-Item (Join-Path $stage 'libnode.so') $target -Force
    Write-Host "  $abi <- nodejs-mobile zip"
}

# ---------------------------------------------------------------- inputs / 版本
$payload = Join-Path $tvDir 'app\src\main\assets\node-payload-danmu.zip'
$bridge = Join-Path $modDir 'src\main\cpp\node_bridge.cpp'
$gradleFile = Join-Path $modDir 'build.gradle'
$inputs = (@(
    "node=$NodeVersion"
    "payload=$(Get-Sha $payload)"
    "bridge=$(Get-Sha $bridge)"
    "gradle=$(Get-Sha $gradleFile)"
) -join "`n")
$sha256 = [System.Security.Cryptography.SHA256]::Create()
$inputsHash = -join ($sha256.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($inputs)) |
    ForEach-Object { $_.ToString('x2') })

$prevHash = if (Test-Path $inputsFile) { (Get-Content $inputsFile -Raw).Trim() } else { '' }
# 显式 PatchVersion 优先（CI 全量）；否则按 .deps 自增（本地）
$useExplicit = ($PatchVersion -match '^\d+$') -or ($ModuleVersion -match '^\d+(\.\d+){1,3}$')
$patch = if ($useExplicit) { [int]$PatchVersion }
         elseif (Test-Path $versionFile) { [int](Get-Content $versionFile -Raw).Trim() }
         else { 1 }

# 判"产物是否齐全"必须按**带版本的文件名**找：资产名改成 tv-node-<abi>-<ver>.apk
# 之后，还按 tv-node-<abi>.apk 判断就永远为 false —— reuse 分支形同不存在
# （2026-11-06 实测：CI 第二次跑仍然重建了三个包，sha 都变了）。
$allPresent = $true
foreach ($a in $Abis) {
    $found = @(Get-ChildItem -Path (Join-Path $OutDir "tv-node-$a-*.apk") -ErrorAction SilentlyContinue).Count
    if ($found -lt 1) { $allPresent = $false }
}

# 显式版本不复用：产物名带版本，复用旧版本会让 Release 文件名/版本对不上
if (-not $Force -and -not $useExplicit -and $prevHash -eq $inputsHash -and $allPresent) {
    Write-Host "reuse $NodeVersion.$patch（inputs 未变，跳过 gradle）"
    Write-Host "产物: $OutDir"
    exit 0
}
if (-not $useExplicit -and ($Force -or $prevHash -ne $inputsHash)) {
    if ($prevHash -and $prevHash -ne $inputsHash -and (Test-Path $versionFile)) { $patch++ }
    $inputsHash | Set-Content $inputsFile -Encoding ASCII
    $patch | Set-Content $versionFile -Encoding ASCII
}
# 显式 ModuleVersion（CI）> NodeVersion.patch 组合；本地无显式时按 .deps 自增
$fullVersion = if ($ModuleVersion -match '^\d+(\.\d+){1,3}$') { $ModuleVersion }
               else { "$NodeVersion.$patch" }

# ---------------------------------------------------------------- 逐 ABI 出包
foreach ($abi in $Abis) {
    Write-Host "== $abi =="
    Ensure-Libnode $abi

    if ($Force -or -not (Test-Path (Join-Path $modDir "src\main\jniLibs\$abi\libmeow_node_bridge.so"))) {
        Write-Host "  编 JNI 桥…"
        Push-Location $modDir
        try { & bash 'build-bridge.sh' } finally { Pop-Location }
    }
    if (-not (Test-Path (Join-Path $modDir "src\main\jniLibs\$abi\libmeow_node_bridge.so"))) {
        throw "缺 $abi\libmeow_node_bridge.so（需要 ANDROID_NDK + bash）"
    }

    Push-Location $tvDir
    try {
        # 有 keystore.properties（CI / 正式发版）就出 release 包并固定签名；
        # 本地没配 keystore 时退回 debug，方便自测（debug 签名不能用于发布）
        $task = if (Test-Path (Join-Path $tvDir 'keystore.properties')) {
            ':runtime-node:assembleRelease'
        } else { ':runtime-node:assembleDebug' }
        & $gradle $task "-PmeowAbi=$abi" "-PmeowVersionName=$fullVersion" --console=plain
        if ($LASTEXITCODE -ne 0) { throw "gradle 失败（$abi）" }
    }
    finally { Pop-Location }

    $variant = if ($task -like '*Release') { 'release' } else { 'debug' }
    $built = Join-Path $modDir "build\outputs\apk\$variant\runtime-node-$variant.apk"
    if (-not (Test-Path $built)) { throw "没找到产物: $built" }
    # 资产名带版本：sync-packages 像插件一样从文件名解析版本，不用读远端内容
    $apk = Join-Path $OutDir "tv-node-$abi-$fullVersion.apk"
    Copy-Item $built $apk -Force

    $sha = Get-Sha $apk
    $size = (Get-Item $apk).Length
    Write-Host ("  -> tv-node-{0}-{1}.apk ({2:N1} MB) sha={3}" -f $abi, $fullVersion, ($size / 1MB), $sha.Substring(0, 12))
}

Write-Host "产物目录: $OutDir"
