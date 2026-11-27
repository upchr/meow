# 发布前守卫：改了插件/模块的源，但版本号没动 → 直接红。
#
# 为什么需要它（2026-11-13）：
#   danmu-api / drpy / ffmpeg 的版本是手写在 manifest 里的（1.0.0 / 0.1.0 / 9.0.2），
#   tv-node 的补丁号又依赖本地 .deps 状态（CI 冷启动恒为 .1）。只要改了载荷却忘了
#   升版本，清单里就是「同版本、不同内容」—— 客户端只看版本号，判定"已是最新"，
#   新内容永远发不出去，而 CI 全绿、没人发现（1.2.4/1.2.5 的 tv-node 已经发生过）。
#
# ★ 判据是**源码有没有变**，不是 zip 的 sha256：
#   zip 里带时间戳，同版本重跑一次 sha 就不同 —— 拿 sha 当判据会在每次重跑时误报。
#
# 用法：
#   ./deploy/scripts/check-plugin-versions.ps1 -ChangedIds drpy,tv-node
#   ./deploy/scripts/check-plugin-versions.ps1 -ChangedIds ''      # 没有源改动
#
# -ChangedIds：自上一个已发布 tag 起，**源码有改动**的单元 id（逗号分隔，由 CI 用
#              git diff 算出来）。为空 = 没有源改动，直接通过。
# -Version：当前壳版本（插件版本若等于它，说明随壳发布，不再判）。
param(
    [string]$ChangedIds = '',
    [string]$Version = '',
    [string]$UploadDir = '',
    [string]$BaselinePath = ''
)

$ErrorActionPreference = 'Stop'

if (-not $ChangedIds.Trim()) {
    Write-Host '没有插件源改动（ChangedIds 为空），守卫跳过'
    exit 0
}

if (-not $Version) {
    if ($UploadDir -and (Test-Path $UploadDir)) {
        $z = Get-ChildItem $UploadDir -Filter 'apiserver-*.zip' | Select-Object -First 1
        if ($z -and $z.Name -match 'apiserver-(\d+\.\d+\.\d+)\.zip') { $Version = $Matches[1] }
    }
    if (-not $Version) { Write-Host '::warning::不知道当前壳版本，守卫跳过'; exit 0 }
}

if (-not $BaselinePath -or -not (Test-Path $BaselinePath)) {
    Write-Host '::warning::没有基线 packages.json，守卫跳过（首次发版）'
    exit 0
}

# ★ 必须按 UTF-8 显式读：PS 5.1 的 Get-Content -Raw 按 ANSI(GBK) 解，
#   packages.json 里的中文 desc 会被拆成非法字节，ConvertFrom-Json 直接报错。
$baseline = [IO.File]::ReadAllText($BaselinePath, [Text.Encoding]::UTF8) | ConvertFrom-Json
if (-not $baseline.packages) {
    Write-Host '::warning::基线 packages.json 没有 packages 字段，守卫跳过'
    exit 0
}

$changed = $ChangedIds.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
$bad = @()

# 当前源码里的版本（2026-11-24 补）。
#
# 原实现只拿**基线版本**跟壳版本比，于是「插件源一改就必红」——它根本读不到
# 这次到底有没有升版本（drpy 0.1.0→0.1.1 也照样拦，1.2.22 发版实测）。
# 判据改成：当前版本 ≠ 基线版本 = 已升版本，通过。
function Get-CurrentUnitVersion([string]$id) {
    $root = Join-Path $PSScriptRoot '..\..'
    switch ($id) {
        'drpy' {
            $p = Join-Path $root 'server\plugin-manifests\drpy.manifest.json'
            if (Test-Path $p) {
                return [string](([IO.File]::ReadAllText($p, [Text.Encoding]::UTF8) | ConvertFrom-Json).version)
            }
        }
        'danmu-api' {
            $p = Join-Path $root 'server\plugin-manifests\danmu-api.manifest.json'
            if (Test-Path $p) {
                return [string](([IO.File]::ReadAllText($p, [Text.Encoding]::UTF8) | ConvertFrom-Json).version)
            }
        }
        'ffmpeg' {
            $p = Join-Path $root 'server\build-plugin-ffmpeg.ps1'
            if (Test-Path $p) {
                $m = [regex]::Match([IO.File]::ReadAllText($p, [Text.Encoding]::UTF8), '"version"\s*:\s*"([^"]+)"')
                if ($m.Success) { return $m.Groups[1].Value }
            }
        }
        'pyrt' {
            # 2026-11-26 补：pyrt 首次进 Release 后才有基线条目，之后改 pyrt 必须升版本
            $p = Join-Path $root 'server\plugin-manifests\pyrt.manifest.json'
            if (Test-Path $p) {
                return [string](([IO.File]::ReadAllText($p, [Text.Encoding]::UTF8) | ConvertFrom-Json).version)
            }
        }
        'tv-node' {
            # v1.3.0 起随 app 版本单调：18.20.4.<次段*100+修订>（1.3.0 → 18.20.4.300）
            # 大版本进位（2.0.0）时 node 基座会升级，连基座 bump 即恢复单调
            $p = $Version.Split('.')
            if ($p.Count -ge 3) { return "18.20.4.$([int]$p[1] * 100 + [int]$p[2])" }
        }
        'tv-python' {
            # v1.3.0 起：0.<app次段>.<app修订>（1.3.0 → 0.3.0）
            $p = $Version.Split('.')
            if ($p.Count -ge 3) { return "0.$([int]$p[1]).$([int]$p[2])" }
        }
    }
    return ''
}

foreach ($id in $changed) {
    # tv-node 的三个 ABI 在清单里是 tv-node-<abi>，按前缀聚合成一个单元判
    $ids = if ($id -eq 'tv-node') {
        @($baseline.packages.PSObject.Properties.Name | Where-Object { $_ -like 'tv-node-*' })
    } elseif ($id -eq 'danmu-api') {
        # 载荷与 PC 插件同源同版本：载荷条目（电视用）也必须跟着升
        @('danmu-api', 'danmu-payload')
    } else { @($id) }

    foreach ($k in $ids) {
        $e = $baseline.packages.$k
        if (-not $e) { Write-Host "  · $k：基线无此条目（首次），跳过" -ForegroundColor DarkGray; continue }
        $oldVer = [string]$e.version
        # 版本等于壳版本 = 随壳发布（如 apiserver），内容本来就会变，不判
        if ($oldVer -eq $Version) {
            Write-Host "  · $k：版本跟壳走（$oldVer），跳过" -ForegroundColor DarkGray
            continue
        }
        # 当前源码里的版本已不同于基线 = 这次确实升了版本
        $newVer = Get-CurrentUnitVersion $id
        if ($newVer -and $newVer -ne $oldVer) {
            Write-Host "  · $k：$oldVer → $newVer（已升版本，通过）" -ForegroundColor DarkGray
            continue
        }
        $bad += [pscustomobject]@{ Id = $k; Version = $oldVer }
    }
}

if ($bad.Count -eq 0) {
    Write-Host '守卫通过：改动过的单元都已升版本（或版本跟壳走）' -ForegroundColor Green
    exit 0
}

Write-Host ''
foreach ($b in $bad) {
    Write-Host "::error::$($b.Id) 的源码这次有改动，但清单里的版本仍是 $($b.Version) —— 请先升版本号" -ForegroundColor Red
}
Write-Host ''
Write-Host '规则：客户端只按版本号判断更新；改了内容不升版本 = 用户永远收不到这份更新。' -ForegroundColor Yellow
Write-Host '修法：' -ForegroundColor Yellow
Write-Host '  · danmu-api → server/plugin-manifests/danmu-api.manifest.json 的 version' -ForegroundColor Yellow
Write-Host '  · drpy      → server/plugin-manifests/drpy.manifest.json 的 version' -ForegroundColor Yellow
Write-Host '  · ffmpeg    → server/build-plugin-ffmpeg.ps1 里的 version' -ForegroundColor Yellow
Write-Host '  · tv-node   → 打 tv-plugins-v<node>.<N+1>（补丁号必须涨）' -ForegroundColor Yellow
exit 1
