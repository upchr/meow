# 构建并推送多架构镜像到 Docker Hub
#
# 产出两个仓库的多架构清单（amd64 + arm64）：
#   <DOCKER_USER>/meow-web:<version>        前端（nginx）
#   <DOCKER_USER>/meow-apiserver:<version>  后端（JRE）
# 同时打 latest 标签（可用 -NoLatest 关掉）。
#
# 用法：
#   docker login                                  # 只需一次
#   ./build-push-docker.ps1 -User yourname -DryRun # 演练：只构建不推送
#   ./build-push-docker.ps1 -User yourname         # 真正推送
#   ./build-push-docker.ps1 -User yourname -Platforms linux/amd64   # 只一个架构（快）
#   ./build-push-docker.ps1 -User meow -RepoPrefix ""               # → meow/web
#
# 为什么必须 buildx：
#   普通 docker build 只产出**当前机器架构**的镜像；在 x64 上构建的镜像
#   到 arm64 机器报 "exec format error"。buildx 一次构建多架构并合成
#   manifest list，docker pull 时自动选对架构。

param(
    [Parameter(Mandatory = $true)]
    [string]$User,

    # 仓库名前缀，默认 "meow-" → meow-web / meow-apiserver
    [string]$RepoPrefix = "meow-",

    [string]$Platforms = "linux/amd64,linux/arm64",

    # 默认读 client/package.json 的 version
    [string]$Version,

    # drpy 运行时的 tag。**独立版本线**，默认读 .env.example 的 DRPY_VERSION。
    # 不跟 $Version 走：drpy 只在主版本发版时顺带重打，中间主版本号远端不存在，
    # 跟着走会让新机器 compose up 报 manifest unknown（详见 compose 里的注释）。
    [string]$DrpyVersion,

    # pyrt 运行时的 tag。同样**独立版本线**，默认读 .env.example 的 PYRT_VERSION。
    # 它只在 pyrt.py / 依赖变化时才需要重打，跟主版本号走会让每次发版都白构建一次。
    [string]$PyrtVersion,

    [switch]$NoLatest,
    [switch]$DryRun,
    [switch]$SkipBuild,

    # 用宿主已构建的 client/apps/web/dist 打 web 镜像，跳过容器内 pnpm build。
    # 适用于容器里拉不到 registry.npmjs.org 的情况（产物与架构无关）。
    [switch]$WebFromDist,
    # builder 名称。默认自建 meow-multiarch（docker-container 驱动）。
    # CI 里 setup-buildx-action 已建好一个并设为默认，传它的名字避免重复创建。
    [string]$Builder = 'meow-multiarch',

    # 构建缓存后端。CI 传 'registry'：缓存推到 <镜像>:buildcache（Docker Hub），
    # 不吃 GitHub 10GB 缓存配额、跨 ref/跨版本都能复用；旧的 'gha' 后端同样是
    # Actions 缓存，会被 tag 作用域和配额夹住（实测只存下 280MB）。
    # 本地留空——本机有常驻的 buildkitd 缓存。
    [string]$Cache = '',

    # drpy 镜像已存在时强制重建（默认跳过：drpy 走独立版本线，多数发版内容不变）
    [switch]$RebuildDrpy,

    # 同上；pyrt 也是独立版本线
    [switch]$RebuildPyrt
)

$ErrorActionPreference = 'Stop'
# 仓库根：脚本在 deploy/scripts/，退两层才是根（client/ server/ docs/ 所在处）
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# ── 版本号 ────────────────────────────────────────────────
if (-not $Version) {
    $pkg = Get-Content (Join-Path $root 'client\package.json') -Raw | ConvertFrom-Json
    $Version = $pkg.version
}
if (-not $Version) { throw "无法确定版本号，请用 -Version 指定" }

# drpy 独立版本线：默认从 .env.example 读，读不到退回写死的当前值。
# 这条**不能**跟 $Version，理由见 param 注释与 compose 里的说明。
if (-not $DrpyVersion) {
    $envEx = Join-Path $root 'deploy\docker\.env.example'
    if (Test-Path $envEx) {
        $line = (Get-Content $envEx | Where-Object { $_ -match '^\s*DRPY_VERSION\s*=' } | Select-Object -First 1)
        if ($line) { $DrpyVersion = ($line -split '=', 2)[1].Trim() }
    }
    if (-not $DrpyVersion) { $DrpyVersion = '0.9.23' }
}

# pyrt 同 drpy：独立版本线。★ 它此前**从没被这条脚本构建过** —— Docker Hub 上
# meow-drpy / meow-apiserver 都有，只有 meow-pyrt 404，而 compose 的 jsRuntime
# 默认就指向它（2026-11-26 查实）。
#
# .env.example 里的 PYRT_VERSION 是给用户 **pull** 的（写的 latest）；构建 tag
# 不能是 latest —— 那样「已存在就跳过」永远命中，改了 pyrt.py 也发不出去。
# 所以读到 latest 时退回 manifest 里的具体版本。
if (-not $PyrtVersion) {
    $envEx = Join-Path $root 'deploy\docker\.env.example'
    if (Test-Path $envEx) {
        $line = (Get-Content $envEx | Where-Object { $_ -match '^\s*PYRT_VERSION\s*=' } | Select-Object -First 1)
        if ($line) { $PyrtVersion = ($line -split '=', 2)[1].Trim() }
    }
}
if (-not $PyrtVersion -or $PyrtVersion -eq 'latest') {
    # ★ 代码里的 pyrt 版本（插件 manifest）才是构建 tag 的真源。
    #   以前这里写死 '0.1.0' —— tag 永远停在 0.1.0，而 pyrt.py 一直在改，
    #   Docker 用户就拿不到修复（实测 Hub 上只有 0.1.0，2026-11-26 推，
    #   而 pyrt.py 在 11-27 修过「探测持锁堵死 .py 请求」）。
    $mf = Join-Path $root 'server\plugin-manifests\pyrt.manifest.json'
    if (Test-Path $mf) {
        $PyrtVersion = [string](([IO.File]::ReadAllText($mf, [Text.Encoding]::UTF8) | ConvertFrom-Json).version)
    }
}
if (-not $PyrtVersion) { $PyrtVersion = '0.1.0' }

$webImage = "$User/${RepoPrefix}web"
$apiImage = "$User/${RepoPrefix}apiserver"
$drpyImage = "$User/${RepoPrefix}drpy"
$pyrtImage = "$User/${RepoPrefix}pyrt"

Write-Host ""
Write-Host "镜像：" -ForegroundColor Cyan
Write-Host "  $webImage`:$Version"
Write-Host "  $apiImage`:$Version"
Write-Host "  $drpyImage`:$DrpyVersion  （独立版本线）"
Write-Host "  $pyrtImage`:$PyrtVersion  （独立版本线）"
Write-Host "架构：$Platforms"
Write-Host "模式：$(if ($DryRun) { '仅构建（不推送）' } else { '构建并推送' })"
Write-Host ""

# ── 检查 ──────────────────────────────────────────────────
Write-Host "[1/5] 检查环境..." -ForegroundColor Yellow

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "找不到 docker" }

docker buildx version *> $null
if ($LASTEXITCODE -ne 0) {
    throw "docker buildx 不可用。Docker Desktop 自带；Linux 需装 buildx 插件。"
}

# 跨架构构建需要 QEMU 注册
if ($Platforms -match 'arm64') {
    $binfmt = (docker run --rm --privileged tonistiigi/binfmt 2>&1 | Out-String)
    if ($binfmt -notmatch 'aarch64') {
        Write-Host "  注册 arm64 模拟器..." -ForegroundColor DarkYellow
        docker run --rm --privileged tonistiigi/binfmt --install arm64 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "注册 QEMU 失败（Linux 需 binfmt_misc 支持）" }
    }
    Write-Host "  arm64 模拟器可用" -ForegroundColor Green
}

# 只有推 Docker Hub 时才检查登录；自建 registry / GHCR 的凭据校验留给 docker 自己报错。
# 判断依据：Hub 用户名不含 "." ":" "/"；含任一个说明是 registry 主机名。
$isDockerHub = -not ($User -match '[.:/]')
if (-not $DryRun -and $isDockerHub) {
    # 跨平台：CI（ubuntu runner）没有 USERPROFILE，只有 HOME。
    $cfg = if ($env:USERPROFILE) { Join-Path $env:USERPROFILE '.docker/config.json' }
           else { Join-Path $env:HOME '.docker/config.json' }
    if (-not (Test-Path $cfg)) { throw "找不到 $cfg，请先 docker login" }
    $json = Get-Content $cfg -Raw | ConvertFrom-Json
    $authKeys = @()
    if ($json.auths) { $authKeys = $json.auths.PSObject.Properties.Name }
    if (-not ($authKeys -match 'docker\.io|index\.docker\.io')) {
        throw "未登录 Docker Hub，请先执行：docker login"
    }
    Write-Host "  已登录 Docker Hub" -ForegroundColor Green
}

# ── builder ───────────────────────────────────────────────
$builder = $Builder
if ((docker buildx ls 2>&1 | Out-String) -notmatch $builder) {
    Write-Host "  创建 builder: $builder" -ForegroundColor DarkGray
    docker buildx create --name $builder --driver docker-container --bootstrap | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "创建 builder 失败" }
}
docker buildx use $builder | Out-Null

function Build-Push {
    param([string]$Dockerfile, [string]$Image, [string]$Label, [string]$Target,
          # 单独指定 tag。drpy 走独立版本线（$DrpyVersion），不跟主版本 $Version。
          [string]$Tag)

    if (-not $Tag) { $Tag = $Version }
    Write-Host "[$Label] 构建 $Image`:$Tag" -ForegroundColor Yellow

    $buildArgs = @(
        'buildx', 'build',
        '--builder', $builder,
        '--platform', $Platforms,
        '-f', $Dockerfile,
        '-t', "$Image`:$Tag"
    )
    # web 的 runtime-dist 路线：直接用 client/apps/web/dist，跳过容器内 pnpm build
    # （容器里拉不到 registry.npmjs.org 时用）
    if ($Target) { $buildArgs += @('--target', $Target) }
    if (-not $NoLatest) { $buildArgs += @('-t', "$Image`:latest") }

    # 后端镜像要把版本写进 /app/VERSION（ApiServer 运行时读，见 resolveVersion）
    if ($Label -eq 'apiserver') { $buildArgs += @('--build-arg', "MEOW_VERSION=$Version") }

    # 关掉 provenance/SBOM：否则多架构清单里会多出两条 unknown/unknown
    # 的 attestation 记录，看着像构建异常。
    $buildArgs += @('--provenance=false', '--sbom=false')

    # 缓存：gha 后端把层缓存在 Actions 的 cache 服务里，key 带 $Label，
    # 三个镜像互不干扰。mode=max 才缓存中间层（默认 min 只缓存最终层）。
    # ★ gha 后端就是 Actions 缓存 → 同样受 ref 作用域 + 10GB 配额约束，实测只存下
    #   280MB（三个多架构镜像远不止），跨版本基本用不上。CI 传 'registry' 走
    #   Docker Hub 的 :buildcache tag：不吃 GitHub 配额、跨 ref 天然可复用。
    if ($Cache -eq 'registry') {
        $buildArgs += @('--cache-from', "type=registry,ref=$Image`:buildcache")
        $buildArgs += @('--cache-to', "type=registry,ref=$Image`:buildcache,mode=max")
    }
    elseif ($Cache -eq 'gha') {
        $buildArgs += @('--cache-from', "type=gha,scope=$Label")
        $buildArgs += @('--cache-to', "type=gha,mode=max,scope=$Label")
    }
    elseif ($Cache) {
        $buildArgs += @('--cache-from', $Cache)
    }

    # manifest list 不能 --load 到本地，所以 DryRun 导出 OCI tar
    if ($DryRun) {
        $tar = Join-Path $root (Join-Path ".tmp" "$Label-oci.tar")
        New-Item -ItemType Directory -Force -Path (Split-Path $tar) | Out-Null
        $buildArgs += "--output=type=oci,dest=$tar"
    }
    else {
        $buildArgs += '--push'
    }
    $buildArgs += $root

    & docker @buildArgs
    if ($LASTEXITCODE -ne 0) { throw "$Label 构建失败" }
}

# ── 构建 ──────────────────────────────────────────────────
if (-not $SkipBuild) {
    Write-Host "[2/5] 前端镜像..." -ForegroundColor Yellow
    $webTarget = if ($WebFromDist) { 'runtime-dist' } else { '' }
    if ($WebFromDist) { Write-Host "  使用宿主预构建 dist（--target runtime-dist）" -ForegroundColor DarkGray }
    Build-Push -Dockerfile (Join-Path $root 'deploy/docker/Dockerfile.web') -Image $webImage -Label 'web' -Target $webTarget

    Write-Host "[3/5] 后端镜像..." -ForegroundColor Yellow
    Build-Push -Dockerfile (Join-Path $root 'deploy/docker/Dockerfile.apiserver') -Image $apiImage -Label 'apiserver'

    # drpy 运行时：没有它，配置里 200+ 个 .js 源在容器部署下全判不可用
    # ★ drpy 走独立版本线（$DrpyVersion），与主版本无关 → 大多数发版它内容不变。
    #   已存在的 tag 直接跳过：省掉 1/3 的镜像构建时间。改了 Dockerfile.drpy 或
    #   drpy 源码时要**升 DrpyVersion**（这是本仓库既有约定），否则会被这里跳过。
    Write-Host "[4/5] drpy 镜像..." -ForegroundColor Yellow
    $drpyTag = "$drpyImage`:$DrpyVersion"
    $drpyExists = $false
    if (-not $RebuildDrpy) {
        & docker buildx imagetools inspect $drpyTag *> $null
        $drpyExists = ($LASTEXITCODE -eq 0)
    }
    if ($drpyExists) {
        Write-Host "  $drpyTag 已存在，跳过构建（要强制重建加 -RebuildDrpy）" -ForegroundColor DarkGray
    }
    else {
        Build-Push -Dockerfile (Join-Path $root 'deploy/docker/Dockerfile.drpy') -Image $drpyImage -Label 'drpy' -Tag $DrpyVersion
    }

    # pyrt 运行时：.py 源本地执行 + .js 转发给 drpy。没有它 apiserver 的
    # jsRuntime 指向一个不存在的容器，两种脚本源一律 Connection refused。
    # 与 drpy 同样走独立版本线 + 已存在则跳过（升 PyrtVersion 才重打）。
    Write-Host "[5/5] pyrt 镜像..." -ForegroundColor Yellow
    $pyrtTag = "$pyrtImage`:$PyrtVersion"
    $pyrtExists = $false
    if (-not $RebuildPyrt) {
        & docker buildx imagetools inspect $pyrtTag *> $null
        $pyrtExists = ($LASTEXITCODE -eq 0)
    }
    if ($pyrtExists) {
        Write-Host "  $pyrtTag 已存在，跳过构建（要强制重建加 -RebuildPyrt）" -ForegroundColor DarkGray
    }
    else {
        Build-Push -Dockerfile (Join-Path $root 'deploy/docker/Dockerfile.pyrt') -Image $pyrtImage -Label 'pyrt' -Tag $PyrtVersion
    }
}

# ── 结果 ──────────────────────────────────────────────────
Write-Host "[5/5] 完成" -ForegroundColor Yellow

if ($DryRun) {
    Write-Host ""
    Write-Host "演练完成，产物在 .tmp/*-oci.tar" -ForegroundColor Cyan
    Write-Host "确认无误后去掉 -DryRun 重跑即可推送。" -ForegroundColor DarkGray
    exit 0
}

Write-Host ""
Write-Host "已推送：" -ForegroundColor Green
# 展示清单时临时放宽错误处理：docker 的 stderr 在 $ErrorActionPreference='Stop'
# 下会直接抛异常，而这里只想知道"能不能读到"。
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
foreach ($pair in @(@($webImage, $Version), @($apiImage, $Version), @($drpyImage, $DrpyVersion), @($pyrtImage, $PyrtVersion))) {
    $img = $pair[0]; $tag = $pair[1]
    Write-Host "  $img`:$tag"
    $inspect = docker buildx imagetools inspect "$img`:$tag" 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0) {
        $inspect -split "`n" | Select-String 'Platform:' |
            ForEach-Object { Write-Host "    $($_.Line.Trim())" }
    }
    else {
        Write-Host "    （清单读取失败，稍后可手动 imagetools inspect 确认）" -ForegroundColor DarkYellow
    }
}
$ErrorActionPreference = $prevEap
$global:LASTEXITCODE = 0

Write-Host ""
Write-Host "别人拉取（Docker 自动选架构）：" -ForegroundColor Cyan
Write-Host "  docker pull $webImage`:$Version"
Write-Host "  docker pull $apiImage`:$Version"
