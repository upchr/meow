# 从 GitHub Release 生成/更新仓库根目录的 packages.json。
#
# ## 为什么要有它
#
# packages.json 是应用读的**唯一清单**：它同时控制插件版本和应用自身（PC/TV）。
# 手工维护意味着每次发版都要：查资产名、算 sha256、拼 JSON、提交。
# 这个脚本把这几步自动化，且**幂等**——重复跑结果一致。
#
# ## 为什么不用下载后算 sha256
#
# GitHub Release 的资产元数据自带 `digest: "sha256:<hex>"`（实测可用），
# 直接读它就不用下载 11MB 的 apk 再算一遍。慢且没必要。
#
# ## 为什么按 tag 前缀只更新一部分条目
#
# 与 `.github/workflows/release.yml` 的 scope 判定一致：
# `tv-v*` 只发 TV，`pc-v*` 只发 PC，`plugins-v*` 只发插件，`release-v*` 全量。
# 若每次都全量重建，只发 TV 时会把 PC 条目删掉（因为那个 release 里没有 exe），
# 用户就会以为"PC 没有更新"。所以是**合并更新**，不是覆盖。
#
# ## 用法
#
#   # 自动取 latest release（不含 draft）
#   ./deploy/scripts/sync-packages.ps1
#
#   # 指定 tag
#   ./deploy/scripts/sync-packages.ps1 -Tag v0.9.30
#
#   # 只看结果不写文件
#   ./deploy/scripts/sync-packages.ps1 -DryRun
#
# 需要：gh 已登录（`gh auth status` 通过），或设置 GITHUB_TOKEN。

param(
    # Release tag；空 = 取 latest（GitHub 会跳过 draft 与 prerelease）
    [string]$Tag = '',
    [string]$Repo = 'upchr/meow',
    # 输出路径（默认仓库根目录下的临时文件；清单不再进 git）
    [string]$OutFile = '',
    # 只打印不写盘
    [switch]$DryRun,
    # 只生成不上传（本地想看内容时用）
    [switch]$SkipUpload,
    # 不把继承的插件资产复制到本次 release（省空间，但要接受
    # "删旧 release 就等于删插件"这条运维规则）
    [switch]$NoCopyAssets
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# 清单在 Release 里的资产名。客户端按这个名去找（见 api/appUpdate.ts 的
# MANIFEST_ASSET_NAME）——**两处必须一致**，改名等于让所有客户端看不到更新。
$ManifestAssetName = 'packages.json'

# 默认写在临时目录：清单是**发布产物**，不是源码，不该在仓库里留一份
# （留在根目录容易被误提交，而那正是我们要避免的摩擦）。
# ★ 文件名必须就是资产名——上传时 `?name=` 用的是 $ManifestAssetName，
#   但 curl 读的是本地文件名，两者对不上会让服务端资产名和内容不符。
if (-not $OutFile) { $OutFile = Join-Path ([IO.Path]::GetTempPath()) $ManifestAssetName }

<#
.SYNOPSIS 跨平台的 curl 可执行文件名。
.DESCRIPTION
    Windows 上叫 `curl.exe`（避开 PS 的 curl 别名），Linux/macOS 上只有 `curl`。

    ★ GitHub Actions 的 ubuntu runner 上**没有 `curl.exe`** —— 直接写死会让
    整个 sync-packages job 在 CI 上必挂，而本地（Windows）永远正常：

        The term 'curl.exe' is not recognized as a name of a cmdlet...

    2026-10-30 实测：`release-v1.0.0` 的 Release 已经成功建出来（三条构建链全绿），
    但发布后的 sync-packages 一跑就红，清单迟迟不生成，客户端收不到更新。
    这类"只有 CI 挂"的问题在本地跑多少遍都发现不了。

    判据用 $IsWindows 而不是"试执行一次"：后者会在 stderr 留噪音，
    而本脚本大量用 `2>$null` 吞输出，试执行失败和正常失败看不出区别。
#>
$CurlBin = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'curl.exe' } else { 'curl' }

function Get-GhApi([string]$path) {
    # 不走 gh CLI 而是直接 curl：gh 在本机会 TLS 握手超时（实测连续两次），
    # curl 有重试且能明确区分超时/404。
    $token = $env:GITHUB_TOKEN
    if (-not $token) {
        try { $token = (& gh auth token 2>$null).Trim() } catch { }
    }
    $headers = @()
    if ($token) { $headers += @('-H', "Authorization: Bearer $token") }
    $headers += @('-H', 'Accept: application/vnd.github+json')
    $headers += @('-H', 'X-GitHub-Api-Version: 2022-11-28')

    $url = "https://api.github.com$path"
    # ★ 落盘再用 .NET 按 UTF-8 读，而不是直接捕获 curl 的 stdout。
    #
    # 踩过：release 的 body 里有中文（我们自己写的说明），
    # `$raw = & curl.exe ...` 在 PS 5.1 里会按**控制台代码页(GBK)** 解码
    # 字节，于是 JSON 里的中文变成乱码、遇到被截断的多字节序列还会让
    # ConvertFrom-Json 抛 ArgumentException（症状是"API 请求失败"但
    # 实际上是解析失败，极具误导性）。
    # 落盘 + [Text.UTF8Encoding] 读则完全不经过代码页转换。
    $tmp = [IO.Path]::GetTempFileName()
    try {
        # 重试 6 次、退避 1/2/3/4/5 秒。
        # 实测本机到 api.github.com 的 TLS 握手经常抖动
        # （`curl: (35)` / `gh: net/http: TLS handshake timeout`），
        # 4 次×1s 不够用；退避比等长间隔更容易躲过瞬时拥塞。
        for ($i = 1; $i -le 6; $i++) {
            & $CurlBin -s -L --fail --max-time 45 -o $tmp @headers $url 2>$null
            if ($LASTEXITCODE -eq 0 -and (Test-Path $tmp) -and (Get-Item $tmp).Length -gt 0) {
                $json = [IO.File]::ReadAllText($tmp, [Text.UTF8Encoding]::new($false))
                return $json | ConvertFrom-Json
            }
            if ($i -lt 6) {
                Write-Host "  · API 请求失败（第 $i 次，curl=$LASTEXITCODE），$i s 后重试…" -ForegroundColor DarkYellow
                Start-Sleep -Seconds $i
            }
        }
    } finally {
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
    throw "GitHub API 请求失败（重试 6 次）：$url"
}

# ---------------------------------------------------------------- 取 release
Write-Host "仓库：$Repo" -ForegroundColor Cyan
if ($Tag) {
    Write-Host "tag：$Tag" -ForegroundColor Cyan
    $rel = Get-GhApi "/repos/$Repo/releases/tags/$Tag"
} else {
    Write-Host "tag：（latest）" -ForegroundColor Cyan
    $rel = Get-GhApi "/repos/$Repo/releases/latest"
}
if ($rel.draft) { throw "该 release 还是 draft，Publish 之后才有资产可读" }

$tag = $rel.tag_name
Write-Host "解析到：$tag（资产 $($rel.assets.Count) 个）" -ForegroundColor Green

# ★ 本次 release 的上传端点必须在这里就快照下来。
#   下面的「清单自检」循环会把 $rel 逐条覆盖成"被检查条目自己的 release"
#   （:558），最后一条往往是沿用旧 release 的条目 —— 若复制循环再用
#   $rel.upload_url，就会把继承资产传回**旧 release**（同名资产返回
#   already_exists 还被判成功），url 却改写成新 tag，用户下载必 404。
#   2026-11-12 release-v1.1.7 的 tv-node 三件套就是这么丢的。
$curUploadBase = ([string]$rel.upload_url) -replace '\{.*\}$', ''

# tag 前缀 → 本次要更新哪些条目（与 release.yml 的 scope 一致）
$scope = switch -Regex ($tag) {
    '^docker-v[0-9]'    { @() }
    '^tv-v[0-9]'        { @('meow-tv') }
    # 只发 TV 侧插件：目前只有模块，只更新 tv-node-* 三条，不动壳条目
    '^tv-plugins-v[0-9]' { @('tv-module') }
    '^pc-v[0-9]'        { @('meow-pc', 'plugins') }  # pc-v* 同时构建 apiserver；其余插件无资产时保留旧条目
    '^plugins-v[0-9]'   { @('plugins') }
    '^release-v[0-9]'   { @('meow-pc', 'meow-tv', 'plugins') }
    # fpk 走独立 workflow/主仓镜像；即使误触发也不改应用/插件清单
    '^fpk-v[0-9]'       { @() }
    default {
        # 无前缀的 v0.9.30（只是版本标记，CI 不触发）或其它：按资产实际内容推断
        Write-Host "  ! tag 无已知前缀，按资产内容推断范围" -ForegroundColor DarkYellow
        @('meow-pc', 'meow-tv', 'plugins')
    }
}
Write-Host "范围：$($scope -join ', ')" -ForegroundColor Cyan

# ---------------------------------------------------------------- 读现有清单
#
# ★★ 基线必须来自**线上现有的清单**，不能读本地临时文件。
#
# 踩过（2026-10-29，用户点出）：原来读 `$OutFile`（默认 $env:TEMP/packages.json）。
# 本地反复跑时那个文件里有上次的残留，看起来"能保留旧条目"；
# 但在**全新 CI runner 上它是空的** → $existing=null → 只保留本次 release
# 里的资产 → 插件条目全被丢掉。
#
# 后果很隐蔽：只发 PC 的那次 release 会把 drpy/apiserver 等条目从清单里删掉，
# 用户端表现为"插件不见了"，而一切看起来都"成功"。
#
# 所以按这个顺序取基线：
#   ① 本次 release 里已有的 packages.json（同一个 Release 复议）
#   ② 上一个 release 里的 packages.json
#   ③ 本地 $OutFile（仅本地调试有用，CI 上必然没有）
$existing = $null
$baselineFrom = '(none)'

function Try-ParseManifest([string]$text) {
    if (-not $text) { return $null }
    try { return ($text | ConvertFrom-Json) } catch { return $null }
}

# ① 本 release 的 packages.json 资产
$ownManifest = ($rel.assets | Where-Object { $_.name -eq $ManifestAssetName } | Select-Object -First 1)
if ($ownManifest) {
    $tok0 = $env:GITHUB_TOKEN
    if (-not $tok0) { try { $tok0 = (& gh auth token 2>$null).Trim() } catch { } }
    $tmpb = [IO.Path]::GetTempFileName()
    try {
        & $CurlBin -s -L --fail --max-time 45 -o $tmpb `
            -H "Authorization: Bearer $tok0" `
            -H 'Accept: application/octet-stream' `
            "https://api.github.com/repos/$Repo/releases/assets/$($ownManifest.id)" 2>$null
        if ($LASTEXITCODE -eq 0) {
            $existing = Try-ParseManifest ([IO.File]::ReadAllText($tmpb, [Text.UTF8Encoding]::new($false)))
            if ($existing) { $baselineFrom = "本 release 的 $ManifestAssetName" }
        }
    } finally { Remove-Item $tmpb -Force -ErrorAction SilentlyContinue }
}

# ② 上一个（非 draft 的）release 里的 packages.json
if (-not $existing) {
    try {
        $all = Get-GhApi "/repos/$Repo/releases?per_page=20"
        foreach ($r2 in @($all)) {
            if ($r2.draft) { continue }
            if ($r2.tag_name -eq $tag) { continue }
            $am = ($r2.assets | Where-Object { $_.name -eq $ManifestAssetName } | Select-Object -First 1)
            if (-not $am) { continue }
            $tok0 = $env:GITHUB_TOKEN
            if (-not $tok0) { try { $tok0 = (& gh auth token 2>$null).Trim() } catch { } }
            $tmpb = [IO.Path]::GetTempFileName()
            try {
                & $CurlBin -s -L --fail --max-time 45 -o $tmpb `
                    -H "Authorization: Bearer $tok0" `
                    -H 'Accept: application/octet-stream' `
                    "https://api.github.com/repos/$Repo/releases/assets/$($am.id)" 2>$null
                if ($LASTEXITCODE -eq 0) {
                    $existing = Try-ParseManifest ([IO.File]::ReadAllText($tmpb, [Text.UTF8Encoding]::new($false)))
                    if ($existing) { $baselineFrom = "release $($r2.tag_name) 的 $ManifestAssetName"; break }
                }
            } finally { Remove-Item $tmpb -Force -ErrorAction SilentlyContinue }
        }
    } catch {
        Write-Host "  ! 读历史 release 清单失败：$($_.Exception.Message)" -ForegroundColor DarkYellow
    }
}

# ③ 本地文件（本地调试用）
if (-not $existing -and (Test-Path $OutFile)) {
    $existing = Try-ParseManifest ([IO.File]::ReadAllText($OutFile, [Text.UTF8Encoding]::new($false)))
    if ($existing) { $baselineFrom = '本地临时文件（CI 上不会有）' }
}

if ($existing -and $existing.packages) {
    # ★ 用 @() 包一层：PS 5.1 里单元素时 `.PSObject.Properties.Count` 会返回
    #   奇怪的值（实测显示 "1 1 个条目"），误导读日志的人。
    $cnt = @($existing.packages.PSObject.Properties).Count
    $names = (@($existing.packages.PSObject.Properties) | ForEach-Object { $_.Name }) -join ', '
    Write-Host "基线：$baselineFrom → $cnt 个条目（$names）" -ForegroundColor DarkGray
} else {
    Write-Host "基线：无（本次将只含本次 release 的资产）" -ForegroundColor DarkYellow
}

# 保留未被本次范围覆盖的条目（关键：只发 TV 时不能把 PC 条目删掉；
# 只发 PC 时不能把插件条目删掉 —— 插件不是每个 release 都发）
$packages = [ordered]@{}
if ($existing -and $existing.packages) {
    foreach ($p in $existing.packages.PSObject.Properties) {
        $packages[$p.Name] = $p.Value
    }
}
# 清掉所有旧 id（app / app-pc / app-tv）。
#
# ★ 为什么不给老前端留 app-pc 兼容条目（曾试过，已撤）：
#   0.9.29 及更早的前端是**页面 XHR 下载**（`new XMLHttpRequest()`），
#   而 GitHub Release 资产**没有 CORS 头**（实测最终 200 也不带
#   `Access-Control-Allow-Origin`）。所以老版本即使看到新版，
#   点「下载并安装」也**必然失败** —— 那是个必然坏掉的按钮，
#   不如不给：老用户手动装一次 0.9.30+，之后就能正常在线更新。
foreach ($legacy in @('app', 'app-pc', 'app-tv')) {
    if ($packages.Contains($legacy)) {
        Write-Host "  移除旧条目：$legacy" -ForegroundColor DarkYellow
        $packages.Remove($legacy)
    }
}

# ---------------------------------------------------------------- 版本号
# 应用版本：tag 形如 v0.9.30 / pc-v0.9.30 / release-v0.9.30 → 取 v 后面的部分
$appVersion = $null
if ($tag -match 'v(\d+\.\d+\.\d+)') { $appVersion = $Matches[1] }
Write-Host "版本：$appVersion" -ForegroundColor DarkGray

# ---------------------------------------------------------------- 更新日志
#
# 用户看到的 `desc` 有三个来源，按优先级：
#   ① CHANGELOG.md 里 `## <版本>` 那一节的正文      —— 人写的，最准确
#   ② git log <上个 tag>..<main 最新> 里的 feat/fix/perf —— 自动，不会漏改
#   ③ 模板串「猫窝 XX 版 X.Y.Z」                     —— 兜底，等于没有说明
#
# ★ 以前只有 ③，每次发版都用模板串覆盖 `desc`，用户在弹框里永远只看到
#   "猫窝 PC 版 0.9.36"，等于更新说明形同虚设（`chore(packages): 0.9.34 清单`
#   那些提交里就是这么盖掉的）。
#
# ★ ② 依赖 **完整 git 历史 + 本地有 tag**：
#   · CI 的 actions/checkout 默认 fetch-depth: 1（浅克隆），`git log A..B`
#     会报 `unknown revision`；release.yml 的 sync-packages job 必须写
#     `fetch-depth: 0`。
#   · 本机同样要先 `git fetch --tags` —— 我们的 release tag 是 gh 在远端建的，
#     本地 `git tag --list` 里没有（实测）。
#   两者失败时都退到 ③，不让发版失败（说明文字不值得阻断发布）。

$ChangeLogFile = Join-Path $repoRoot 'CHANGELOG.md'

<#
.SYNOPSIS 从 CHANGELOG.md 取某个版本的正文。
.DESCRIPTION
    节标题恰好是 `## 0.9.38`（不带 v）。返回 null 表示没有这一节。
#>
function Get-ChangelogSection([string]$Version) {
    if (-not $Version -or -not (Test-Path $ChangeLogFile)) { return $null }
    $lines = [IO.File]::ReadAllLines($ChangeLogFile, [Text.UTF8Encoding]::new($false))
    $out = New-Object System.Collections.Generic.List[string]
    $inside = $false
    foreach ($line in $lines) {
        if ($line -match '^##\s+(.+?)\s*$') {
            if ($inside) { break }  # 遇到下一个版本标题就结束
            $inside = ($Matches[1].Trim() -eq $Version)
            continue
        }
        if ($inside) { $out.Add($line) }
    }
    if (-not $inside -and $out.Count -eq 0) { return $null }
    # ★ 统一成「1. 2. 3.」编号短句（2026-11-04 用户要求）：
    #   作者按规范写就是编号的；漏写编号时这里补上，保证客户端弹框、
    #   Release 描述、清单 desc 三处永远是同一套有序列表。
    $num = New-Object System.Collections.Generic.List[string]
    $n = 0
    foreach ($line in $out) {
        $t = $line.Trim()
        if (-not $t) { continue }
        $n++
        if ($t -match '^\d+\s*[.、)]') { $num.Add($t) } else { $num.Add("$n. $t") }
    }
    $text = ($num -join "`n").Trim()
    return $(if ($text) { $text } else { $null })
}

<#
.SYNOPSIS 从 git log 生成更新说明。
.DESCRIPTION
    只取 feat/fix/perf 三类提交，剥掉 `feat(scope):` 的 scope 与 type 前缀。
    chore/docs/test/style 不进说明——那些是内部维护，用户不关心。

    范围 = **上一个发版 tag → main 最新**（用户 2026-11-19 口径）。
    终点优先级：`refs/remotes/origin/main` → `refs/heads/main` → `HEAD`。
    ★ 不直接写 HEAD：本地可能在特性分支/worktree 上跑，HEAD 不等于 main；
      CI 的 sync job 虽然 checkout 了 main，但显式取 origin/main 更稳。

    取不到（浅克隆 / 本地无 tag）时回 $null，由调用方退到模板串。
#>
function Get-GitLogNotes([string]$Version) {
    if (-not $Version) { return $null }
    # 上一个 tag：按版本号降序找第一个小于本版本的 release tag 或版本标记
    $prev = $null
    try {
        $tags = & git tag --list 2>$null
        if (-not $tags) { return $null }
        $cand = @()
        foreach ($t in $tags) {
            if ($t -match 'v(\d+\.\d+\.\d+)$') {
                $tv = $Matches[1]
                if ($tv -ne $Version) { $cand += [pscustomobject]@{ Tag = $t; Ver = $tv } }
            }
        }
        if ($cand.Count -eq 0) { return $null }
        # 版本号数值降序，取第一个 < 本版本的
        $prev = ($cand | Sort-Object { [version]$_.Ver } -Descending |
            Where-Object { [version]$_.Ver -lt [version]$Version } |
            Select-Object -First 1)
    } catch { return $null }
    if (-not $prev) { return $null }

    # 终点 = main 最新：origin/main > 本地 main > HEAD（见函数头注释）
    $end = 'HEAD'
    foreach ($ref in @('refs/remotes/origin/main', 'refs/heads/main')) {
        $sha = & git rev-parse --verify --quiet $ref 2>$null
        if ($LASTEXITCODE -eq 0 -and $sha) { $end = $ref; break }
    }

    try {
        $range = "$($prev.Tag)..$end"
        $log = & git log --no-merges --pretty=format:'%s' $range 2>$null
        if (-not $log) { return $null }
    } catch { return $null }

    $out = New-Object System.Collections.Generic.List[string]
    foreach ($msg in ($log -split "`n")) {
        $m = $msg.Trim()
        if ($m -notmatch '^(feat|fix|perf)(\([^)]*\))?!?:\s*(.+)$') { continue }
        $line = $Matches[3].Trim()
        # 去掉 PR 号尾巴（`(#123)`），那对用户没意义
        $line = $line -replace '\s*\(#\d+\)\s*$', ''
        if ($line) { $out.Add($line) }
        if ($out.Count -ge 8) { break }  # 弹框只显示前 4 行，8 条已经够下文
    }
    if ($out.Count -eq 0) { return $null }
    return ($out -join "`n")
}

<#
.SYNOPSIS 取某个版本的更新说明（三条来源按优先级）。
#>
function Get-ReleaseNotes([string]$Version, [string]$Display) {
    $fromFile = Get-ChangelogSection $Version
    if ($fromFile) {
        Write-Host "  说明来源：CHANGELOG.md（$Version）" -ForegroundColor DarkGray
        return $fromFile
    }
    $fromGit = Get-GitLogNotes $Version
    if ($fromGit) {
        Write-Host "  说明来源：git log（$Version）" -ForegroundColor DarkGray
        return $fromGit
    }
    Write-Host "  ! 没有可用的更新说明，用模板串（写法见 CHANGELOG.md 顶部）" -ForegroundColor DarkYellow
    return "猫窝 $Display 版 $Version"
}

# ---------------------------------------------------------------- 按资产更新
function New-Entry {
    param(
        [Parameter(Mandatory)][object]$Asset,
        [string]$Version,
        [string[]]$Platforms,
        [string]$Display,
        # 覆盖说明文字；空 = 按 Get-ReleaseNotes 的三档优先级取
        [string]$Notes = ''
    )
    # 用资产自带的 digest（sha256:<hex>），不下载重算
    $digest = $Asset.digest
    if (-not $digest -or $digest -notmatch '^sha256:([0-9a-f]{64})$') {
        throw "资产 $($Asset.name) 没有 sha256 digest，无法写入清单（GitHub 可能未计算，重传一次即可）"
    }
    $sha = $Matches[1]
    $desc = if ($Notes) { $Notes } else { Get-ReleaseNotes $Version $Display }
    return [ordered]@{
        display_name = "$Display 安装包"
        version      = $Version
        platform     = $Platforms
        desc         = $desc
        size         = $Asset.size
        sha256       = $sha
        # ★ 写标准下载链接（人类可读）。私有仓由客户端自动转 API 端点，
        #   公开仓直接用——这样将来开源不用改清单。
        url          = $Asset.browser_download_url
        requires     = @()
        # ★ 必须是 true：否则会出现在「本机插件」的可安装列表里
        hidden       = $true
        lasttime     = (Get-Date).ToString('yyyy-MM-dd')
    }
}

# 自动生成的 desc 带**死版本号**（「猫窝插件 drpy 0.1.0」/「TV 运行时模块 arm64-v8a 18.20.4.1」），
# 沿用旧 desc 会让说明永远停在第一次生成时 —— 实测清单里 drpy 实际 0.1.5、
# desc 写着 0.1.0，用户看到的版本号对不上。只有人工写的说明（pyrt 那句）才沿用。
function Test-AutoDesc([string]$d) {
    if (-not $d) { return $true }
    return ($d -match '^(猫窝插件|猫窝弹幕组件|TV 运行时模块|TV Python 运行时)\s')
}

function Find-Asset([string]$pattern) {
    $hit = $rel.assets | Where-Object { $_.name -like $pattern } |
        Sort-Object size -Descending | Select-Object -First 1
    return $hit
}

if ($scope -contains 'meow-pc') {
    # NSIS 安装包：meow-setup-0.9.30.exe（CI 产物名，ASCII）
    # 也兼容中文名（早期本地构建是 `猫窝_0.9.30_x64-setup.exe`）
    $a = Find-Asset '*setup*.exe'
    if (-not $a) { $a = Find-Asset '*.exe' | Where-Object { $_.name -notlike '*bare*' } }
    if ($a) {
        $packages['meow-pc'] = New-Entry $a $appVersion @('windows-x64') 'PC'
        Write-Host "  meow-pc  <- $($a.name)  $([math]::Round($a.size/1MB,2))MB" -ForegroundColor Green
    } else {
        Write-Host "  ! release 里没有 PC 安装包，保留原 meow-pc 条目" -ForegroundColor DarkYellow
    }
}

if ($scope -contains 'meow-tv') {
    # 只认壳 APK。tv-node-* 是同 release 里的运行时模块，不是壳；
    # 2026-11-09 踩过：原来写 '*.apk' 且 Find-Asset 按体积降序，
    # 54MB 的 tv-node-x86_64 会把 21MB 的 meow-TV 顶掉，清单把 TV 壳指到模块上。
    $a = Find-Asset 'meow-TV-*.apk'
    if (-not $a) { $a = Find-Asset '*-TV-*.apk' | Where-Object { $_.name -notlike 'tv-node-*' } }
    if ($a) {
        $packages['meow-tv'] = New-Entry $a $appVersion `
            @('android-arm64', 'android-armv7', 'android-x86_64') 'TV'
        Write-Host "  meow-tv  <- $($a.name)  $([math]::Round($a.size/1MB,2))MB" -ForegroundColor Green
    } else {
        Write-Host "  ! release 里没有 APK，保留原 meow-tv 条目" -ForegroundColor DarkYellow
    }
}

if ($scope -contains 'plugins') {
    # 插件 zip：apiserver-<ver>.zip / drpy-0.1.0.zip / ...
    # 它们的版本号各自独立（drpy 0.1.0、danmu 1.0.0），不能套用应用版本，
    # 所以从 zip 资产里按名字前缀匹配，版本从现有条目沿用（首次则从 manifest 读）。
    #
    # 运行时依赖（2026-11-28）：从 deploy/units.json 的 `requiresPlugins` 读。
    #   PC 安装器本来就支持递归装依赖（installer.rs → for dep in entry.requires），
    #   但清单里 requires 从来没被填过 → 装 drpy 不会带 Node，启动直接报
    #   「找不到可执行文件：node（也没有可用的 fallback）」。声明放 units.json
    #   （构建单元唯一真源），这里只做「构建 id → 包 id」的映射。
    $unitsDeps = @{}
    $unitsPath = Join-Path $repoRoot 'deploy/units.json'
    if (Test-Path $unitsPath) {
        $unitsObj = [IO.File]::ReadAllText($unitsPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
        foreach ($u in $unitsObj.plugins) {
            if (-not $u.requiresPlugins) { continue }
            $pkgId = if ($u.id -eq 'danmu') { 'danmu-api' } else { $u.id }
            $unitsDeps[$pkgId] = @($u.requiresPlugins)
        }
    }

    $pluginIds = @('apiserver', 'danmu-api', 'drpy', 'ffmpeg', 'node', 'pyrt')
    # 展示名（用户可见）：更新中心「本机插件」行、插件更新列表读的就是 display_name。
    # New-Entry 默认生成「<id> 安装包」，而旧值一直被沿用 —— 界面里常年显示
    # "apiserver 安装包 / drpy 安装包"。统一成「引擎叫 X 运行时，业务包叫 X 组件」。
    $pluginDisplay = @{
        'apiserver' = '影视后端'
        'danmu-api' = '弹幕组件'
        'drpy'      = 'drpy 运行时'
        'ffmpeg'    = 'FFmpeg 转码器'
        'node'      = 'Node 运行时'
        'pyrt'      = 'Python 运行时'
    }
    foreach ($pid2 in $pluginIds) {
        $a = Find-Asset "$pid2-*.zip"
        if (-not $a) {
            Write-Host "  · $pid2：本次无资产，保留原条目" -ForegroundColor DarkGray
            continue
        }
        $pver = if ($a.name -match "$([regex]::Escape($pid2))-(\d+\.\d+\.\d+)\.zip") {
            $Matches[1]
        } elseif ($existing -and $existing.packages.$pid2) {
            $existing.packages.$pid2.version
        } else { '0.0.0' }

        # 插件不走 CHANGELOG / git log：它们的版本号各自独立（drpy 0.1.0），
        # 而更新说明是写给"装包的用户"看的应用改动。这里传显式 Notes，
        # 避免每个插件都去打一次 git log（会刷出 5 条"没有可用说明"的警告）。
        $entry = New-Entry $a $pver @('windows-x64') $pid2 -Notes "猫窝插件 $pid2 $pver"
        # ★ 插件条目必须可见（2026-11-12 修）：
        #   New-Entry 默认 hidden=true 是给**应用安装包**（meow-pc/meow-tv）用的——
        #   它们不是插件，出现在「本机插件」列表里会被插件安装器错误解包。
        #   而 apiserver / drpy / danmu-api / node 恰恰是要让用户装的，
        #   之前"沿用旧值"让它们从 1.1.x 起全是 hidden=true，
        #   全新 PC 的「本机插件」列表因此是空的（找不到影视后端）。
        $entry.hidden = $false
        # 展示名统一（用户可见）：覆盖 New-Entry 的「<id> 安装包」
        if ($pluginDisplay.ContainsKey($pid2)) { $entry.display_name = $pluginDisplay[$pid2] }
        # pyrt 的说明要写清楚：它**不自带** python（Windows 版内置；其它平台靠系统
        # 的 execFallback 依次试 python3 / python.exe），装了没 python 会静默起不来。
        if ($pid2 -eq 'pyrt') {
            $entry.desc = '让 .py 源在本机执行（需系统已装 Python 3.10+）；.js 源由 drpy 运行时执行'
        }
        # 依赖：声明式（units.json），优先级高于沿用旧值
        if ($unitsDeps.ContainsKey($pid2)) { $entry.requires = @($unitsDeps[$pid2]) }
        # 其余字段沿用原值（display_name / desc 是人工维护的）
        if ($existing -and $existing.packages.$pid2) {
            $old = $existing.packages.$pid2
            if (-not $unitsDeps.ContainsKey($pid2) -and $old.requires) { $entry.requires = $old.requires }
            # 显示名单里有的，一律用新名字（旧值是「<id> 安装包」，沿用就是让它永远难看）
            if ($old.display_name -and -not $pluginDisplay.ContainsKey($pid2)) {
                $entry.display_name = $old.display_name
            }
            # ★ desc 只沿用**人工写的**说明（pyrt 那句）。脚本自动生成的
            #   「猫窝插件 <id> <ver>」不能沿用 —— 它会永远停在第一次生成时的版本号，
            #   实测清单里 drpy 实际 0.1.5、desc 还写着「猫窝插件 drpy 0.1.0」，
            #   用户看到的版本号对不上。
            if (-not (Test-AutoDesc $old.desc)) { $entry.desc = $old.desc }
        }
        $packages[$pid2] = $entry
        Write-Host "  $pid2  <- $($a.name)" -ForegroundColor Green
    }

    # ── TV 弹幕载荷（2026-11-13）────────────────────────────────────────
    # 与 danmu-api 插件**同一个 zip**：TV 端只用里面的 danmu_api/ 与 package.json
    # （多一个 manifest.json 无副作用），所以不新增构建、Release 里不重复 10.9MB。
    # 单独一条 id + platform=android 的原因：
    #   · PC 插件列表按 windows-x64 过滤 → 不会把这条显示成本机插件
    #   · TV 壳按 id=danmu-payload 取用，版本与 PC 侧天然一致（同一次构建）
    $dmAsset = Find-Asset 'danmu-api-*.zip'
    if ($dmAsset) {
        $pver = if ($dmAsset.name -match 'danmu-api-(\d+\.\d+\.\d+)\.zip') { $Matches[1] } else { '' }
        if (-not $pver) { throw "无法从 $($dmAsset.name) 解析版本号" }
        $pe = New-Entry $dmAsset $pver @('android') '弹幕组件' -Notes "猫窝弹幕组件 $pver"
        $pe.hidden = $false            # TV 更新中心/运行时分区要能看到
        $pe.display_name = '弹幕组件'
        $packages['danmu-payload'] = $pe
        Write-Host "  danmu-payload <- $($dmAsset.name)（TV 弹幕载荷）" -ForegroundColor Green
    }
}

# 模块属于 TV 链（跟 _tv_plugins 同进同退），所以判据用 meow-tv 而不是 plugins ——
# tv-v* 的 scope 只有 @('meow-tv')，挂在 plugins 下会导致 TV 单链发版时模块条目被跳过。
if (($scope -contains 'meow-tv') -or ($scope -contains 'tv-module')) {
    # ---------------------------------------------------------------- TV 运行时模块
    #
    # 资产名 tv-node-<abi>-<ver>.apk（版本在名字里，和插件同构，不用读远端内容）。
    # 每个 ABI 一条，platform 写 android-<abi> —— 客户端按 Build.SUPPORTED_ABIS
    # 硬匹配，缺 platform 一律不放行（PC 踩过"缺失就放行 → 下错平台包"）。
    #
    # ★ 为什么单独一段而不是塞进 $pluginIds：插件的 platform 是 windows-x64，
    #   模块是 android-<abi> 且一个 id 拆成三条，混在一起会让"按 id 找资产"的
    #   命名约定失效。
    foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86_64')) {
        $id = "tv-node-$abi"
        $a = Find-Asset "tv-node-$abi-*.apk"
        if (-not $a) {
            Write-Host "  · $id：本次无资产，保留原条目" -ForegroundColor DarkGray
            continue
        }
        $v = if ($a.name -match "tv-node-$([regex]::Escape($abi))-(\d+\.\d+\.\d+\.\d+)\.apk") {
            $Matches[1]
        } elseif ($existing -and $existing.packages.$id) {
            $existing.packages.$id.version
        } else { '0.0.0' }

        $entry = New-Entry $a $v @("android-$abi") $id -Notes "TV 运行时模块 $abi $v"
        $entry.display_name = 'Node 运行时（模块）'
        # ★ 版本门槛（2026-11）：模块是壳用 DexClassLoader 加载、并实现壳里那份
        #   plugin-api 接口的 —— 老壳装新模块会 AbstractMethodError。
        #   min_app_version 取"第一个支持运行时模块的壳版本"，固定不改：
        #   写成当前版本会让每次发版都抬高门槛，把还能用的老壳一起挡掉。
        #   min_sdk = 26（模块 APK 的 minSdk，低于它装不上）。
        $entry.min_app_version = '1.1.0'
        $entry.min_sdk = 26
        if ($existing -and $existing.packages.$id) {
            $old = $existing.packages.$id
            if ($null -ne $old.hidden) { $entry.hidden = $old.hidden }
            # display_name 由上面的固定映射决定，不沿用旧值（旧值是「<id> 安装包」）
            if (-not (Test-AutoDesc $old.desc)) { $entry.desc = $old.desc }
        }
        $packages[$id] = $entry
        Write-Host "  $id  <- $($a.name)" -ForegroundColor Green
    }

    # ---------------------------------------------------------------- TV Python 模块
    #
    # 资产名 tv-python-<abi>-<ver>.apk（2026-11-26 起与 tv-node 同一套命名；
    # 旧版是 Chaquopy 变体名 arm64 / v7a，保留回退以免读旧 Release 时断）。
    # platform 必须写完整 ABI（android-arm64-v8a / android-armeabi-v7a /
    # android-x86_64）—— 客户端按 Build.SUPPORTED_ABIS 硬匹配，flavor 名一条都选不中。
    #
    # x86_64（2026-11-26 补）：Chaquopy 支持该 ABI；模拟器（MuMu/AVD）都是 x86_64，
    # 之前只有两个 flavor，那些设备装不上 Python 模块。
    #
    # 版本是三段 semver（0.1.x），与 tv-node 的四段不同，正则分开写。
    foreach ($pair in @(@('arm64-v8a', 'arm64'), @('armeabi-v7a', 'v7a'), @('x86_64', 'x86_64'))) {
        $abi = $pair[0]
        $flavor = $pair[1]
        $id = "tv-python-$abi"
        $a = Find-Asset "tv-python-$abi-*.apk"
        if (-not $a) { $a = Find-Asset "tv-python-$flavor-*.apk" }
        if (-not $a) {
            Write-Host "  · $id：本次无资产，保留原条目" -ForegroundColor DarkGray
            continue
        }
        $pat = "tv-python-(?:$([regex]::Escape($abi))|$([regex]::Escape($flavor)))-(\d+\.\d+\.\d+)\.apk"
        $v = if ($a.name -match $pat) {
            $Matches[1]
        } elseif ($existing -and $existing.packages.$id) {
            $existing.packages.$id.version
        } else { '0.0.0' }

        $entry = New-Entry $a $v @("android-$abi") $id -Notes "TV Python 运行时 $abi $v"
        $entry.display_name = 'Python 运行时'
        $entry.min_app_version = '1.1.0'
        $entry.min_sdk = 26
        # 只有真需要 .py 源的人会装它（~20MB），默认在设置页折叠区可见但不强推
        $entry.hidden = $false
        if ($existing -and $existing.packages.$id) {
            $old = $existing.packages.$id
            if ($null -ne $old.hidden) { $entry.hidden = $old.hidden }
            # display_name 由上面的固定映射决定，不沿用旧值（旧值是「<id> 安装包」）
            if (-not (Test-AutoDesc $old.desc)) { $entry.desc = $old.desc }
        }
        $packages[$id] = $entry
        Write-Host "  $id  <- $($a.name)" -ForegroundColor Green
    }
}

# ---------------------------------------------------------------- 清单自检
#
# 每条 url 必须能解析到真实资产（2026-11 加）。
#
# 背景：单链发布后，未变插件的 url 仍指向旧 release（见上面的复制策略）。
# "不要删仍在被引用的 release" 这条运维规则原先没有任何机器校验 —— 删错了的
# 表现是用户点安装报 404，而清单看起来完全正常。这里在写盘前逐条查：目标 tag
# 与资产名都要存在，否则直接失败，把线上事故变成构建红灯。
foreach ($k in @($packages.Keys)) {
    $u = [string]$packages[$k].url
    if (-not $u) { continue }
    $m3 = [regex]::Match($u, '/releases/download/([^/]+)/(.+)$')
    if (-not $m3.Success) { continue }   # 自建镜像等非 release 地址，跳过
    $uTag = $m3.Groups[1].Value
    $fname = [uri]::UnescapeDataString($m3.Groups[2].Value)
    try {
        $rel = Get-GhApi "/repos/$Repo/releases/tags/$uTag"
        $hit = @($rel.assets | Where-Object { $_.name -eq $fname }).Count -gt 0
        if (-not $hit) { throw "release $uTag 里没有资产 $fname" }
    }
    catch {
        Write-Host "  X $k 的下载地址已失效：$u" -ForegroundColor Red
        throw "清单自检失败：$k -> $($_.Exception.Message)（该 release 被删了？）"
    }
}
Write-Host "  清单自检通过（$(@($packages.Keys).Count) 条 url 可解析）" -ForegroundColor Green

if ($packages.Count -eq 0) {
    throw "没有任何可写条目（release 里没有匹配资产，范围 $($scope -join ',')）"
}

# ---------------------------------------------------------------- 继承资产并入本次 release
#
# ★★ 加固（2026-10-29）：插件的 url 可能指向**旧 release**（本次没发它就继承过来）。
#   那有两个问题：
#     ① 旧 release 一删，插件就装不上了（url 404，而清单看起来完全正常）
#     ② 私有仓的标准链接本来就 404，全靠客户端转 API 端点，多一层失败点
#
#   所以：把继承条目的资产**复制一份到本次 release**，然后 url 改成指向本次。
#   每个 release 自包含 —— 删任何旧 release 都不影响。
#
#   代价：多占空间。apiserver 37MB + ffmpeg 37MB 这类大插件确实可观，
#   但它们本来也不是每次发版都变（见 §9.2.1）。真要省空间就跳过复制、
#   接受"别删旧 release"这条运维规则（加 -NoCopyAssets 开关）。
# ---------------------------------------------------------------- 复制策略（2026-11-06 定）
#
# 只在**全量** release 时复制继承资产，日常单链不复制：
#   · 全量（release-v*）：自包含归档，删任何旧 release 都不影响
#   · 单链（tv/pc/tv-plugins/plugins…）：不复制 —— 省下载+上传时间与存储，
#     资产目录只留本次真正产出的文件（清晰）
#
# 代价：单链发布后，未变插件的 url 仍指向旧 release。运维规则：
#   **不要删仍在被清单引用的 release**。下面的清单会把这些条目打出来。
$archival = $tag -match '^release-v[0-9]'

# 先把"仍指向旧 release"的条目列出来（不复制时这就是风险面）
$inherited = @()
foreach ($k in @($packages.Keys)) {
    $u = [string]$packages[$k].url
    if (-not $u) { continue }
    $m0 = [regex]::Match($u, '/releases/download/([^/]+)/(.+)$')
    if ($m0.Success -and $m0.Groups[1].Value -ne $tag) {
        $inherited += "$k → $($m0.Groups[1].Value)"
    }
}
if ($inherited.Count -gt 0) {
    Write-Host "  沿用旧 release 的条目（删了对应的旧 release 就会失效）：" -ForegroundColor DarkYellow
    $inherited | ForEach-Object { Write-Host "    · $_" -ForegroundColor DarkGray }
}

$copied = @()
if ((-not $NoCopyAssets) -and (-not $archival)) {
    Write-Host "  单链发布：不复制继承资产（省时间/存储；旧 release 请保留）" -ForegroundColor DarkGray
}
if ((-not $NoCopyAssets) -and $archival) {
    $tokA = $env:GITHUB_TOKEN
    if (-not $tokA) { try { $tokA = (& gh auth token 2>$null).Trim() } catch { } }

    foreach ($k in @($packages.Keys)) {
        $u = [string]$packages[$k].url
        if (-not $u) { continue }
        if ($u -notmatch '/releases/download/') { continue }
        $m2 = [regex]::Match($u, '/releases/download/([^/]+)/(.+)$')
        if (-not $m2.Success) { continue }
        $uTag = $m2.Groups[1].Value
        $fname = $m2.Groups[2].Value
        if ($uTag -eq $tag) { continue }   # 本次的资产，不用复制

        Write-Host "  复制继承资产：$k（$uTag → $tag）" -ForegroundColor DarkGray
        try {
            $srcRel = Get-GhApi "/repos/$Repo/releases/tags/$uTag"
            $srcAsset = ($srcRel.assets | Where-Object { $_.name -eq $fname } | Select-Object -First 1)
            if (-not $srcAsset) {
                Write-Host "    ! 源 release $uTag 里没有 $fname，保留原 url（该插件在旧 release 删除后会失效）" -ForegroundColor Red
                continue
            }
            # 下载源资产（必须带 octet-stream，否则拿到的是资产描述 JSON）
            $tmpA = [IO.Path]::GetTempFileName()
            try {
                & $CurlBin -s -L --fail --max-time 300 `
                    -H "Authorization: Bearer $tokA" `
                    -H 'Accept: application/octet-stream' `
                    -o $tmpA "https://api.github.com/repos/$Repo/releases/assets/$($srcAsset.id)" 2>$null
                if ($LASTEXITCODE -ne 0 -or -not (Test-Path $tmpA)) { throw "下载源资产失败" }

                # 上传到本次 release（用开头快照的端点；$rel 此时已被自检循环覆盖）
                $upA = "${curUploadBase}?name=$fname"
                $okA = $false
                for ($i = 1; $i -le 4; $i++) {
                    $respA = & $CurlBin -s -X POST --max-time 300 `
                        -H "Authorization: Bearer $tokA" `
                        -H 'Accept: application/vnd.github+json' `
                        -H 'Content-Type: application/octet-stream' `
                        --data-binary "@$tmpA" `
                        $upA 2>&1 | Out-String
                    if ($respA -match '"state"\s*:\s*"uploaded"') { $okA = $true; break }
                    # 同名已存在也算成功（重复跑脚本时）
                    if ($respA -match 'already_exists') { $okA = $true; break }
                    Start-Sleep -Seconds 2
                }
                if (-not $okA) { throw "上传到 $tag 失败" }

                # url 改指本次 release，sha256/size 不变（内容一模一样）
                $packages[$k].url = "https://github.com/$Repo/releases/download/$tag/$fname"
                $copied += $k
            } finally {
                Remove-Item $tmpA -Force -ErrorAction SilentlyContinue
            }
        } catch {
            Write-Host "    ! 复制失败（$($_.Exception.Message)），保留原 url" -ForegroundColor Red
        }
    }
    if ($copied.Count -gt 0) {
        Write-Host "  已并入本次 release：$($copied -join ', ')" -ForegroundColor Green
    }

    # ---------------------------------------------------------------- fpk 资产并入
    #
    # 2026-11-13 起全量链自己就构建 fpk 并直接进主 Release（fpk.yml 以
    # workflow_call 调用），正常情况下这里**什么都不用做**（同名资产会被跳过）。
    # 保留这段是为了兼容两种历史形态：
    #   · 老版本的全量 Release（fpk 还没并进主链，包在 fpk-v* 的独立 Release 里）
    #   · 先打过 fpk-v<ver> 单链、之后才发全量（两处都有同一份产物）
    # 单链 fpk 现在是 draft：draft 不出现在 /releases/tags 里 → 取不到就跳过，
    # 不影响发布（FnDepot 与 fpk-updates 都由 fpk.yml 的 index-after-publish 写）。
    $fpkTag = "fpk-v$appVersion"
    $fpkRel = $null
    try {
        $fpkRel = Get-GhApi "/repos/$Repo/releases/tags/$fpkTag"
    } catch {
        Write-Host "  · 没有 $fpkTag，跳过 fpk 资产并入" -ForegroundColor DarkGray
    }
    if ($fpkRel) {
        $tokF = $env:GITHUB_TOKEN
        if (-not $tokF) { try { $tokF = (& gh auth token 2>$null).Trim() } catch { } }
        $haveNames = @()
        try { $haveNames = @((Get-GhApi "/repos/$Repo/releases/tags/$tag").assets.name) } catch { }
        foreach ($fa in @($fpkRel.assets | Where-Object { $_.name -like '*.fpk' })) {
            if ($haveNames -contains $fa.name) {
                Write-Host "    · $($fa.name) 已存在" -ForegroundColor DarkGray
                continue
            }
            if (-not $tokF) { Write-Host "    ! 没有令牌，跳过 fpk 并入" -ForegroundColor Red; break }
            Write-Host "  并入 fpk：$($fa.name)" -ForegroundColor DarkGray
            $tmpF = [IO.Path]::GetTempFileName()
            try {
                & $CurlBin -s -L --fail --max-time 300 `
                    -H "Authorization: Bearer $tokF" `
                    -H 'Accept: application/octet-stream' `
                    -o $tmpF "https://api.github.com/repos/$Repo/releases/assets/$($fa.id)" 2>$null
                if ($LASTEXITCODE -ne 0 -or -not (Test-Path $tmpF)) { throw "下载源资产失败" }
                $okF = $false
                for ($i = 1; $i -le 4; $i++) {
                    $respF = & $CurlBin -s -X POST --max-time 300 `
                        -H "Authorization: Bearer $tokF" `
                        -H 'Accept: application/vnd.github+json' `
                        -H 'Content-Type: application/octet-stream' `
                        --data-binary "@$tmpF" `
                        "${curUploadBase}?name=$($fa.name)" 2>&1 | Out-String
                    if ($respF -match '"state"\s*:\s*"uploaded"') { $okF = $true; break }
                    if ($respF -match 'already_exists') { $okF = $true; break }
                    Start-Sleep -Seconds 2
                }
                if (-not $okF) { throw "上传到 $tag 失败" }
            } catch {
                Write-Host "    ! fpk 并入失败（$($_.Exception.Message)）" -ForegroundColor Red
            } finally {
                Remove-Item $tmpF -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

# ---------------------------------------------------------------- 本次 tag 的资产二次校验
#
# ★★ 2026-11-12 事故后加（release-v1.1.7 的 tv-node 三件套）：
#   复制步曾经因为 $rel 变量被自检循环覆盖，把继承资产传回**旧 release**
#   （同名资产 already_exists 还被判成功），url 却改写成了新 tag —— 而下面
#   「继承条目存活校验」当时对"指向本次 tag"的条目直接 continue（注释写着
#   "本次的资产，一定有"），于是 404 一路漏到用户：全新安装点模块下载必失败。
#
#   这里的判据不信任任何中间状态：复制之后**重新拉一次本次 release 的资产
#   列表**，逐条核对"指向本次 tag 的 url 必须真有资产"，缺一个就红。
$curAssets = @()
try {
    $curAssets = @((Get-GhApi "/repos/$Repo/releases/tags/$tag").assets)
} catch {
    Write-Host "  ! 取本次 release 资产列表失败（$($_.Exception.Message)），跳过二次校验" -ForegroundColor DarkYellow
}
if ($curAssets.Count -gt 0) {
    $missingCur = @()
    foreach ($k in @($packages.Keys)) {
        $u = [string]$packages[$k].url
        if (-not $u) { continue }
        $m4 = [regex]::Match($u, '/releases/download/([^/]+)/(.+)$')
        if (-not $m4.Success) { continue }
        if ($m4.Groups[1].Value -ne $tag) { continue }
        $fname = [uri]::UnescapeDataString($m4.Groups[2].Value)
        if (@($curAssets | Where-Object { $_.name -eq $fname }).Count -eq 0) {
            $missingCur += "$k（url 指向 $tag 的 $fname，但资产不存在）"
        }
    }
    if ($missingCur.Count -gt 0) {
        Write-Host "  X 这些条目指向本次 release 却没有对应资产：" -ForegroundColor Red
        $missingCur | ForEach-Object { Write-Host "    · $_" -ForegroundColor Red }
        throw "清单自检失败：本次 release 缺少被引用的资产（复制步没生效？见 2026-11-12 事故）"
    }
    Write-Host "  本次 tag 资产二次校验通过（$($curAssets.Count) 个资产）" -ForegroundColor Green
}

# ---------------------------------------------------------------- 继承条目的存活校验
#
# 复制失败（或用了 -NoCopyAssets）时，url 仍指向旧 release。这里兜底检查
# 那个资产是否还在——不在就响亮地警告（不阻断：可能是临时网络问题，
# 但必须让人看见，否则用户装插件时才失败）。
$stale = @()
foreach ($k in @($packages.Keys)) {
    $u = [string]$packages[$k].url
    if (-not $u) { continue }
    if ($u -notmatch '/releases/download/') { continue }
    $m2 = [regex]::Match($u, '/releases/download/([^/]+)/')
    if (-not $m2.Success) { continue }
    $uTag = $m2.Groups[1].Value
    if ($uTag -eq $tag) { continue }   # 本次 tag 的条目已在上面「二次校验」里核对过
    # 用 API 查该 tag 的资产列表，确认文件还在
    try {
        $r3 = Get-GhApi "/repos/$Repo/releases/tags/$uTag"
        $fname = ($u -split '/')[-1]
        $has = ($r3.assets | Where-Object { $_.name -eq $fname } | Measure-Object).Count -gt 0
        if (-not $has) { $stale += "$k（url 指向 $uTag 的 $fname，但该资产不存在）" }
    } catch {
        $stale += "$k（查不到 $uTag 这个 release：$($_.Exception.Message)）"
    }
}
if ($stale.Count -gt 0) {
    Write-Host "`n⚠ 以下继承条目的 url 可能失效：" -ForegroundColor Red
    $stale | ForEach-Object { Write-Host "    · $_" -ForegroundColor Red }
    Write-Host "  → 这些插件删掉对应旧 release 后就装不上了。" -ForegroundColor DarkYellow
    Write-Host "  → 建议：要么别删那个 release，要么这次重新上传该插件资产。" -ForegroundColor DarkYellow
}

# ---------------------------------------------------------------- 写文件
$out = [ordered]@{
    schema_version  = 1
    generated_at    = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    author          = [ordered]@{ name = 'meow'; url = "https://github.com/$Repo" }
    min_app_version = '0.6.0'
    source_tag      = $tag
    packages        = $packages
}

# 手拼 JSON 而不是 ConvertTo-Json：PS 5.1 会把中文转成 \uXXXX，
# 而且对空数组输出会变形（Rust 侧解析失败）。
$sb = New-Object System.Text.StringBuilder
function Esc([string]$s) {
    if ($null -eq $s) { return '""' }
    $s = $s.Replace('\', '\\').Replace('"', '\"')
    $s = $s.Replace("`r", '\r').Replace("`n", '\n').Replace("`t", '\t')
    return '"' + $s + '"'
}
[void]$sb.AppendLine('{')
[void]$sb.AppendLine('  "schema_version": 1,')
[void]$sb.AppendLine("  `"generated_at`": $(Esc $out.generated_at),")
[void]$sb.AppendLine("  `"author`": { `"name`": `"meow`", `"url`": $(Esc $out.author.url) },")
[void]$sb.AppendLine("  `"min_app_version`": $(Esc $out.min_app_version),")
[void]$sb.AppendLine("  `"source_tag`": $(Esc $out.source_tag),")
[void]$sb.AppendLine('  "packages": {')
$keys = @($packages.Keys)
for ($i = 0; $i -lt $keys.Count; $i++) {
    $k = $keys[$i]
    $p = $packages[$k]
    [void]$sb.AppendLine("    $(Esc $k): {")
    [void]$sb.AppendLine("      `"display_name`": $(Esc ([string]$p.display_name)),")
    [void]$sb.AppendLine("      `"version`": $(Esc ([string]$p.version)),")
    $platJson = '[' + (($p.platform | ForEach-Object { Esc ([string]$_) }) -join ',') + ']'
    [void]$sb.AppendLine("      `"platform`": $platJson,")
    [void]$sb.AppendLine("      `"desc`": $(Esc ([string]$p.desc)),")
    [void]$sb.AppendLine("      `"size`": $($p.size),")
    [void]$sb.AppendLine("      `"sha256`": $(Esc ([string]$p.sha256)),")
    [void]$sb.AppendLine("      `"url`": $(Esc ([string]$p.url)),")
    $reqJson = if ($p.requires -and @($p.requires).Count -gt 0) {
        '[' + ((@($p.requires) | ForEach-Object { Esc ([string]$_) }) -join ',') + ']'
    } else { '[]' }
    [void]$sb.AppendLine("      `"requires`": $reqJson,")
    $hidden = if ($p.hidden -eq $false) { 'false' } else { 'true' }
    [void]$sb.AppendLine("      `"hidden`": $hidden,")
    [void]$sb.AppendLine("      `"lasttime`": $(Esc ([string]$p.lasttime))")
    $comma = if ($i -lt $keys.Count - 1) { ',' } else { '' }
    [void]$sb.AppendLine("    }$comma")
}
[void]$sb.AppendLine('  }')
[void]$sb.AppendLine('}')

$text = $sb.ToString()
if ($DryRun) {
    Write-Host "`n=== 结果（DryRun，未写盘）===" -ForegroundColor Cyan
    Write-Host $text
} else {
    [System.IO.File]::WriteAllText($OutFile, $text, (New-Object System.Text.UTF8Encoding $false))
    Write-Host "`n已写入：$OutFile" -ForegroundColor Green
    Write-Host "条目：$($keys -join ', ')"
}

# ---------------------------------------------------------------- 挂到 Release
#
# ★ 清单作为 **Release 资产**，不进 git。
#
# 为什么（用户明确要求）：
#   清单进 git 的话每次发版都要往 main 提交一次，本地每次都得先 pull，
#   否则 push 被拒 —— "每次发版后要更新代码"是很烦的摩擦。
#
# 为什么客户端仍然只需配一次地址：
#   清单挂在 Release 上之后，客户端填的是 **repo 级**端点，地址里没有 asset id
#   也没有 tag，所以永远不用改。有两种形态：
#
#     · 资产：`github.com/<o>/<r>/releases/latest/download/packages.json`
#       —— 客户端现用这个（api 端点在部分网络被干扰，见 appUpdate.ts 的注释）
#     · API ：`api.github.com/repos/<o>/<r>/releases/latest`
#       —— 能按版本挑最高的那条、跳过没清单的 release；私有仓只能用它
#          （私有仓的资产地址实测 000）
#
#   ★ 资产形态的两个代价（发版侧要守住，否则用户收不到更新）：
#     ① 它按**发布时间**取，补发旧版 hotfix 会让「最新」指回旧版本
#        （客户端只会不提示，不会降级 —— isNewer 严格大于才提示）；
#     ② latest 那条若没带 packages.json 就直接 404（不像 API 形态能跳过）。
#        所以每次发版都必须上传清单，且「本次 tag 的资产二次校验」不能去掉。
if (-not $DryRun -and -not $SkipUpload) {
    Write-Host "`n上传清单到 Release $tag …" -ForegroundColor Cyan

    # ★ 用 curl 走 REST API，不用 `gh release upload`。
    #   踩过：gh 在本机频繁 `Post .../graphql: TLS handshake timeout`
    #   （本项目已经因为它改过一次上传路径）。curl 可以显式重试、
    #   且错误信息是 HTTP 码而不是一段 GraphQL 报错。
    #
    #   流程：① 查该 tag 里是否已有 packages.json → 有就删（要 --clobber 语义）
    #        ② POST 到 upload_url（`releases/<id>/assets?name=...`）
    $tok = $env:GITHUB_TOKEN
    if (-not $tok) { try { $tok = (& gh auth token 2>$null).Trim() } catch { } }
    if (-not $tok) { throw "没有 GitHub 令牌（gh auth login，或设 GITHUB_TOKEN）" }

    $rel = Get-GhApi "/repos/$Repo/releases/tags/$tag"
    $old = ($rel.assets | Where-Object { $_.name -eq $ManifestAssetName } | Select-Object -First 1)
    if ($old) {
        Write-Host "  删除旧清单资产（id=$($old.id)）…" -ForegroundColor DarkGray
        $deleted = $false
        for ($i = 1; $i -le 3; $i++) {
            # ★ 用 -w 拿 HTTP 码判断，不能只看退出码：
            #   踩过 —— `-o $null` 在 PS 里会把字面量 "null" 当文件名传下去，
            #   curl 可能仍退 0 但实际没删掉；随后 POST 会因同名资产冲突而失败，
            #   表现为"清单上传了但内容是旧的"（非常难查）。
            $code = & $CurlBin -s -X DELETE --max-time 30 -w '%{http_code}' `
                -H "Authorization: Bearer $tok" `
                -H 'Accept: application/vnd.github+json' `
                -o NUL "https://api.github.com/repos/$Repo/releases/assets/$($old.id)" 2>$null
            if ("$code".Trim() -in @('204', '200', '404')) { $deleted = $true; break }
            Write-Host "    删除失败（HTTP $code），重试…" -ForegroundColor DarkYellow
            Start-Sleep -Seconds 1
        }
        if (-not $deleted) {
            throw "无法删除旧清单资产（id=$($old.id)）。同名资产已存在时 POST 会失败，必须删掉再传。"
        }
    }

    # upload_url 里带 `{?name,label}` 模板，要替换掉
    $uploadBase = ($rel.upload_url -replace '\{.*\}$', '')
    $uploadUrl = "${uploadBase}?name=$ManifestAssetName"
    $ok = $false
    for ($i = 1; $i -le 5; $i++) {
        $resp = & $CurlBin -s -X POST --max-time 120 `
            -H "Authorization: Bearer $tok" `
            -H 'Accept: application/vnd.github+json' `
            -H 'Content-Type: application/json' `
            --data-binary "@$OutFile" `
            $uploadUrl 2>&1 | Out-String
        # 成功响应里有 "state": "uploaded"
        if ($resp -match '"state"\s*:\s*"uploaded"') { $ok = $true; break }
        Write-Host "  · 上传失败（第 $i 次）：$($resp.Substring(0, [Math]::Min(160, $resp.Length)))" -ForegroundColor DarkYellow
        Start-Sleep -Seconds 2
    }
    if (-not $ok) {
        throw "清单上传失败。清单必须挂到 Release，否则客户端看不到本次版本。"
    }

    # ★ 上传后**回读校验**。
    #   踩过：同名资产已存在时 POST 会被 GitHub 拒（或覆盖失败），
    #   而只判断响应文本很容易漏过去，结果"脚本说成功、Release 上还是旧清单"。
    #   这里直接再拉一次资产内容，比对条目数与版本号。
    Write-Host "  回读校验…" -ForegroundColor DarkGray
    $checkOk = $false
    for ($i = 1; $i -le 3; $i++) {
        Start-Sleep -Seconds 2
        $rel2 = Get-GhApi "/repos/$Repo/releases/tags/$tag"
        $a2 = ($rel2.assets | Where-Object { $_.name -eq $ManifestAssetName } | Select-Object -First 1)
        if (-not $a2) { continue }
        # 同 Get-GhApi 的理由：落盘再按 UTF-8 读，避免 PS 5.1 按 GBK 解码中文
        $tmp2 = [IO.Path]::GetTempFileName()
        try {
            & $CurlBin -s -L --fail --max-time 60 `
                -H "Authorization: Bearer $tok" `
                -H 'Accept: application/octet-stream' `
                -o $tmp2 "https://api.github.com/repos/$Repo/releases/assets/$($a2.id)" 2>$null
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $tmp2)) { continue }
            $j = [IO.File]::ReadAllText($tmp2, [Text.UTF8Encoding]::new($false)) | ConvertFrom-Json
        } finally {
            Remove-Item $tmp2 -Force -ErrorAction SilentlyContinue
        }
        $have = @($j.packages.PSObject.Properties.Name) | Sort-Object
        $want = @($keys) | Sort-Object
        if (($have -join ',') -eq ($want -join ',')) { $checkOk = $true; break }
        Write-Host "    回读条目不一致：Release=$($have -join ',') 期望=$($want -join ',')" -ForegroundColor DarkYellow
    }
    if (-not $checkOk) {
        throw "清单上传后回读校验失败：Release 上的 $ManifestAssetName 与本次生成的不一致（旧资产可能没删掉）。"
    }
    Write-Host "✅ 已上传 $ManifestAssetName 到 $tag（回读校验通过）" -ForegroundColor Green
}

# ---------------------------------------------------------------- 侧载商店源清单
#
# SideStore / LiveContainer / AltStore 读的是**每版一条**的 versions[] 数组：
# 每条指向该版本自己的 IPA（不是固定名），并带版本号、日期与 CHANGELOG 更新说明 ——
# 与 fpk 的 fnpack.json 同一个思路：发版后在 CI 里重建，用户刷新源即可看到新版本。
#
# 托管两处（与 packages.json 同构）：
#   · Release 资产 altstore-source.json → .../releases/latest/download/altstore-source.json
#   · updates 分支镜像 → raw.githubusercontent.com/<repo>/updates/altstore-source.json（兜底）
#
# 生成/上传失败都不阻塞 packages.json：侧载商店少一个源，不影响 PC/TV 更新。
$altstoreAssetName = 'altstore-source.json'
$altstoreFile = Join-Path ([IO.Path]::GetTempPath()) ("meow-altstore-" + [guid]::NewGuid().ToString('N') + '.json')
$altstoreOk = $false
try {
    Write-Host "`n生成侧载商店源清单 …" -ForegroundColor Cyan

    # 列出全部 release（分页；只保留生成源清单需要的字段，避免把 release 正文
    # 全部序列化到临时文件）。GitHub 单页上限 100。
    $allRels = New-Object System.Collections.ArrayList
    $page = 1
    while ($true) {
        $batch = @(Get-GhApi "/repos/$Repo/releases?per_page=100&page=$page")
        if ($batch.Count -eq 0) { break }
        foreach ($r in $batch) {
            [void]$allRels.Add([pscustomobject]@{
                tag_name     = $r.tag_name
                draft        = [bool]$r.draft
                prerelease   = [bool]$r.prerelease
                published_at = $r.published_at
                updated_at   = $r.updated_at
                assets       = @($r.assets | ForEach-Object {
                    [pscustomobject]@{ name = $_.name; size = $_.size; digest = $_.digest }
                })
            })
        }
        if ($batch.Count -lt 100) { break }
        $page++
        if ($page -gt 30) { break }   # 安全阀：3000 个 release
    }
    $relsTmp = Join-Path ([IO.Path]::GetTempPath()) ("meow-rels-" + [guid]::NewGuid().ToString('N') + '.json')
    [IO.File]::WriteAllText($relsTmp, (@($allRels) | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))

    # 测试包的构建 metadata（publish_test 上传的 Meow-unsigned-test.json）：
    # 给生成器提供测试 IPA 里真实的版本号，否则 SideStore 默认的版本校验会拦下。
    # 取不到就传空 —— 生成器宁可不输出 nightly，也不写一个装不上的条目。
    $testMetaPath = ''
    try {
        $testRel = Get-GhApi "/repos/$Repo/releases/tags/ipa-test"
        $testMetaAsset = @($testRel.assets) | Where-Object { $_.name -eq 'Meow-unsigned-test.json' } | Select-Object -First 1
        if ($testMetaAsset) {
            $testMetaPath = Join-Path ([IO.Path]::GetTempPath()) ("meow-testmeta-" + [guid]::NewGuid().ToString('N') + '.json')
            $hdrM = @('-H', 'Accept: application/octet-stream')
            $tokM = $env:GITHUB_TOKEN
            if (-not $tokM) { try { $tokM = (& gh auth token 2>$null).Trim() } catch { } }
            if ($tokM) { $hdrM = @('-H', "Authorization: Bearer $tokM") + $hdrM }
            & $CurlBin -s -L --fail --max-time 60 @hdrM -o $testMetaPath "https://api.github.com/repos/$Repo/releases/assets/$($testMetaAsset.id)" 2>$null
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $testMetaPath)) {
                Remove-Item $testMetaPath -Force -ErrorAction SilentlyContinue
                $testMetaPath = ''
            }
        }
    } catch {
        $testMetaPath = ''
    }

    & (Join-Path $PSScriptRoot 'gen-altstore-source.ps1') -ReleasesJson $relsTmp -OutFile $altstoreFile -Repo $Repo -RepoRoot $repoRoot -TestMetaJson $testMetaPath
    Remove-Item $relsTmp -Force -ErrorAction SilentlyContinue
    Remove-Item $testMetaPath -Force -ErrorAction SilentlyContinue
    $altstoreOk = $true
} catch {
    Write-Host "  ! 侧载商店源清单生成失败（不阻塞 packages.json）：$($_.Exception.Message)" -ForegroundColor DarkYellow
}

# 自己也是一个独立资产；上传失败同样不阻塞发版（updates 分支仍会带上）。
if (-not $DryRun -and -not $SkipUpload -and $altstoreOk) {
    Write-Host "上传 $altstoreAssetName 到 Release $tag …" -ForegroundColor Cyan
    $tokA = $env:GITHUB_TOKEN
    if (-not $tokA) { try { $tokA = (& gh auth token 2>$null).Trim() } catch { } }
    if (-not $tokA) {
        Write-Host "  ! 没有令牌，跳过上传（updates 分支仍会更新）" -ForegroundColor DarkYellow
    } else {
        try {
            $relA = Get-GhApi "/repos/$Repo/releases/tags/$tag"
            $oldA = ($relA.assets | Where-Object { $_.name -eq $altstoreAssetName } | Select-Object -First 1)
            if ($oldA) {
                $codeA = & $CurlBin -s -X DELETE --max-time 30 -w '%{http_code}' `
                    -H "Authorization: Bearer $tokA" `
                    -H 'Accept: application/vnd.github+json' `
                    -o NUL "https://api.github.com/repos/$Repo/releases/assets/$($oldA.id)" 2>$null
                if ("$codeA".Trim() -notin @('204', '200', '404')) { throw "删除旧资产失败（HTTP $codeA）" }
            }
            $upBaseA = ($relA.upload_url -replace '\{.*\}$', '')
            $okA = $false
            for ($i = 1; $i -le 5; $i++) {
                $respA = & $CurlBin -s -X POST --max-time 120 `
                    -H "Authorization: Bearer $tokA" `
                    -H 'Accept: application/vnd.github+json' `
                    -H 'Content-Type: application/json' `
                    --data-binary "@$altstoreFile" `
                    "${upBaseA}?name=$altstoreAssetName" 2>&1 | Out-String
                if ($respA -match '"state"\s*:\s*"uploaded"') { $okA = $true; break }
                Write-Host "  · 上传失败（第 $i 次）：$($respA.Substring(0, [Math]::Min(160, $respA.Length)))" -ForegroundColor DarkYellow
                Start-Sleep -Seconds 2
            }
            if (-not $okA) { throw '上传失败（重试 5 次）' }
            Write-Host "  ✅ 已上传 $altstoreAssetName" -ForegroundColor Green
        } catch {
            Write-Host "  ! $altstoreAssetName 上传失败（不阻塞发版）：$($_.Exception.Message)" -ForegroundColor DarkYellow
        }
    }
}

# ---------------------------------------------------------------- 静态清单分支
#
# 开源后客户端在 API 失败/限流时可拉：
#   https://raw.githubusercontent.com/<repo>/updates/packages.json
#   https://raw.githubusercontent.com/<repo>/updates/history.json
# 单文件、force push；不碰 main，也不在源码历史里留发版提交。
#
# history.json 是「设置 → 关于 → 版本历史」的数据源：packages.json 每个包只有最新
# 一条 desc，历史版本没人存，所以由 gen-history.ps1 从 CHANGELOG.md 生成。
if (-not $DryRun) {
    # 用临时 index 生成一个只含 packages.json + history.json 的 orphan 提交，再 force push
    # 到 updates 分支。不建 worktree（Windows 上会撞 locked: initializing），
    # 也不把 mktree 文本经 PowerShell 管道（会带 BOM，实测 input format error）。
    $idx = Join-Path ([IO.Path]::GetTempPath()) ("meow-index-" + [guid]::NewGuid().ToString('N'))
    $histFile = Join-Path ([IO.Path]::GetTempPath()) ("meow-history-" + [guid]::NewGuid().ToString('N') + '.json')
    $oldIndex = $env:GIT_INDEX_FILE
    $env:GIT_INDEX_FILE = $idx
    try {
        Write-Host "`n更新静态清单分支 updates …" -ForegroundColor Cyan
        & git -C $repoRoot read-tree --empty 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "read-tree 失败" }
        $blob = (& git -C $repoRoot hash-object -w $OutFile).Trim()
        if ($LASTEXITCODE -ne 0 -or -not $blob) { throw "hash-object 失败" }
        & git -C $repoRoot update-index --add --cacheinfo "100644,$blob,packages.json" 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "update-index 失败" }
        # 版本历史：失败不阻塞（没有它也只是一次"版本历史拉不到"）
        try {
            & (Join-Path $PSScriptRoot 'gen-history.ps1') -RepoRoot $repoRoot -OutFile $histFile | Out-Null
            $histBlob = (& git -C $repoRoot hash-object -w $histFile).Trim()
            if ($LASTEXITCODE -eq 0 -and $histBlob) {
                & git -C $repoRoot update-index --add --cacheinfo "100644,$histBlob,history.json" 2>&1 | Out-Null
            } else {
                Write-Host "  ! history.json 生成失败，本次只发 packages.json" -ForegroundColor DarkYellow
            }
        } catch {
            Write-Host "  ! history.json 生成失败（$($_.Exception.Message)），本次只发 packages.json" -ForegroundColor DarkYellow
        }
        # 侧载商店源清单：与 packages.json/history.json 同一个 orphan 提交。
        # 生成失败（$altstoreOk=false）时跳过，不阻塞前两个文件。
        if ($altstoreOk -and (Test-Path -LiteralPath $altstoreFile)) {
            try {
                $altBlob = (& git -C $repoRoot hash-object -w $altstoreFile).Trim()
                if ($LASTEXITCODE -eq 0 -and $altBlob) {
                    & git -C $repoRoot update-index --add --cacheinfo "100644,$altBlob,altstore-source.json" 2>&1 | Out-Null
                }
            } catch {
                Write-Host "  ! altstore-source.json 未并入 updates 分支：$($_.Exception.Message)" -ForegroundColor DarkYellow
            }
        }
        $tree = (& git -C $repoRoot write-tree).Trim()
        if ($LASTEXITCODE -ne 0 -or -not $tree) { throw "write-tree 失败" }
        $commit = (& git -C $repoRoot -c user.email="actions@github.com" -c user.name="github-actions" commit-tree $tree -m "chore(updates): packages.json + history.json + altstore-source.json for $tag").Trim()
        if ($LASTEXITCODE -ne 0 -or -not $commit) { throw "commit-tree 失败" }
        # PowerShell 会把 git 的 "To https://..." 当作 NativeCommandError；
        # 这里临时放宽 ErrorAction，真正结果只看 $LASTEXITCODE。
        $oldEap = $ErrorActionPreference
        $ErrorActionPreference = 'SilentlyContinue'
        & git -C $repoRoot push --force origin "${commit}:refs/heads/updates" 2>&1 | Out-Null
        $pushCode = $LASTEXITCODE
        $ErrorActionPreference = $oldEap
        if ($pushCode -ne 0) { throw "push 失败（exit=$pushCode）" }
        Write-Host "✅ updates 分支已更新（$tag）" -ForegroundColor Green
    } catch {
        Write-Host "  ! 静态清单推送失败（不影响本次 Release）：$($_.Exception.Message)" -ForegroundColor DarkYellow
    } finally {
        Remove-Item -LiteralPath $idx -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $histFile -Force -ErrorAction SilentlyContinue
        if ($null -eq $oldIndex) { Remove-Item Env:GIT_INDEX_FILE -ErrorAction SilentlyContinue }
        else { $env:GIT_INDEX_FILE = $oldIndex }
    }
}

if (-not $DryRun) {
    Write-Host "`n客户端「更新源」填（配一次，永不改）：" -ForegroundColor Cyan
    Write-Host "  https://api.github.com/repos/$Repo/releases/latest" -ForegroundColor Gray
    Write-Host "私有仓需在「访问令牌」填只读 PAT。" -ForegroundColor DarkGray
}

exit 0
