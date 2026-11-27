# 部署与发版（唯一操作手册）

> **本文是部署、发版、CI 的唯一操作入口。**
> 细节参考：[docs/36](../site/docs/dev/docker.md)（Docker 深入排障）、
> [docs/46](../site/docs/dev/tv-signing.md)（TV 签名与密钥备份）、
> [docs/33](../site/docs/dev/plugin-system.md)（插件系统设计）。
>
> 本文与上述文档冲突时，**本文的操作步骤优先**。

---

## AI / 新人接管速查（先读这一节）

**第一次接触这个仓库，按这 5 步走：**

> **★ 调用 PS 脚本必须带解释器**：Git Bash / PowerShell 里都要写
> `powershell -NoProfile -ExecutionPolicy Bypass -File <脚本> [参数]`。
> 直接 `./xxx.ps1` 在 Git Bash 下会被当 shell 脚本执行而报
> `$'ï»¿#': command not found`（实测）。
> 下面为了可读性写成 `./deploy/scripts/x.ps1`，实际调用请套上前缀。

```powershell
# 1. 确认环境自检通过（2 秒，不产包）
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/scripts/verify.ps1
#    8/8 通过 = 脚本路径、语法、版本号、compose、文档引用与目录全部一致。
#    有 FAIL 就先修 FAIL，别急着构建。

# 2. 想知道"有哪些产物、各自用哪个脚本"
cat deploy/units.json          # 构建单元唯一真源

# 3. 想全量发版（本地，不收额度）
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/scripts/bump-version.ps1 -Version 0.9.17
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/scripts/release.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/scripts/release.ps1 -Plan

# 4. 只想做某一个产物 → 见 §3.2「单链路」

# 5. 想用 CI 发版 → ★ 必须先问用户（见 §零），然后看 §四
```

**六个最常问的答案：**

| 问题 | 答案 |
|---|---|
| 打包入口在哪 | 本地：`deploy/scripts/release.ps1`；CI：`git tag release-v*` |
| 有哪些产物 | `deploy/units.json`（PC / TV / Docker / 4 个插件 / 清单） |
| 加一个插件怎么改 | 只改 `deploy/units.json` 加一条，然后跑 `verify.ps1` |
| 改了脚本会不会漏改文档 | 跑 `verify.ps1`，第 3 项和第 7 项就是查这个 |
| 版本号怎么改 | `./deploy/scripts/bump-version.ps1 -Version x.y.z`（10 处一次改齐） |
| TV 签名会不会丢 | keystore 未入库，**必须异地备份**，见 [docs/46](../site/docs/dev/tv-signing.md) |

**三条硬约束**（违反会出事）：
1. **不要主动用 CI 发版**——每次都要用户同意，本地才是默认路径
2. **不要改 `units.json` 以外的地方去加构建单元**——清单是唯一真源
3. **`client/apps/tv/meow-release.jks` 与 `keystore.properties` 永不入库**

---

## 零、一条红线

> ## 🚫 不要主动用 GitHub Actions 发版。每次都要用户明确同意。
> ## ✅ 本地发版不受限制，随时可跑。

| | 本地 `deploy/scripts/release.ps1` | GitHub Actions |
|---|---|---|
| 消耗 | 本机 CPU/磁盘，**无额度** | 私有仓 2000 分钟/月，**Windows runner ×2** |
| 单次成本 | 全量约 30–40 分钟（本机） | 全量 **16 分钟墙钟 / 约 45 分钟额度** |
| 要盯 | 不需要 | **要盯**（首跑连环失败 8 次才全绿） |
| 授权 | **不受限制** | **每次都要用户同意** |

本地跑的是同一批脚本，产物完全一样还不花钱。**除非要验证 CI 本身，否则没有理由上 CI。**

---

## 一、目录结构

```
deploy/
├── README.md                    ← 本文
├── docker/
│   ├── docker-compose.yml       ← 主栈启动（不含 sources）
│   ├── docker-compose.build.yml ← 主栈构建 + 启动（不含 sources）
│   ├── docker-compose.with-sources.yml ← 主栈 + sources 一键启动
│   ├── docker-compose.sources.yml ← 只启动源服务
│   ├── Dockerfile.web           ← nginx + 静态文件
│   ├── Dockerfile.apiserver     ← JRE + classes + libs + ffmpeg
│   ├── Dockerfile.drpy          ← Node 运行时
│   ├── nginx.conf.template      ← web 容器反代配置
│   └── .env.example             ← 变量模板（复制成 .env 用）
├── source-pack/
│   ├── README.md                ← ★源包规范（meow-source-pack/1）
│   └── validate.py              ← 源包校验（dex/md5/manifest/Fongmi 兼容）
├── units.json                   ← ★构建单元唯一真源（见下）
└── scripts/
    ├── verify.ps1               ← ★自检（改完脚本/文档必跑）
    ├── release.ps1              ← 本地全量发版编排
    ├── build-plugins.ps1        ← 只构建插件（CI 与本地共用）
    ├── bump-version.ps1         ← 版本号 10 处一次改齐
    └── build-push-docker.ps1    ← 三镜像构建并推送
```

> **源包服务（2026-11-11 拆分）**：主栈和 build compose 都不含 sources。
> `docker-compose.with-sources.yml` = 主栈 + sources；
> `docker-compose.sources.yml` = 只启动源服务（Docker Hub 镜像 `1.2.0`）。
> 镜像内置主爬虫 + Jable + 54 站默认源；挂载自己的源包会覆盖默认源。
> `Dockerfile.sources`、`nginx.sources.conf`、`entrypoint.sh`、
> `default-sources` 属于构建资源，已 gitignore，只在私仓
> `upchr/meow-private` 维护；源包规范见 [`source-pack/`](source-pack/README.md)。

### 1.1 ★ 构建单元的唯一真源：`deploy/units.json`

**加/删构建单元只改这一个文件**，不要再改脚本数组、workflow 步骤、文档枚举。

```jsonc
{
  "chains":  { "pc": {...}, "tv": {...}, "docker": {...} },   // 三条链路的入口脚本
  "plugins": [ { "id": "apiserver", "script": "...", "required": true }, ... ],
  "index":   { "script": "server/build-release.ps1" },        // 打 zip + packages
  "version": { "script": "deploy/scripts/bump-version.ps1" }
}
```

- `required: true` → 失败让发版红；`false` → 失败只警告（danmu 缺上游源码、ffmpeg 下载失败）
- `needs` → 前置文件（danmu 需要不入库的 （私有开发资料））
- **订单顺序即执行顺序**（apiserver 最慢放最前）

消费方：`release.ps1`、`build-plugins.ps1`、CI 的 `_plugins.yml`（Windows）与 `_pc_shell.yml`（Windows）。
**不在 deploy/ 下的脚本仍在原目录**（`client/apps/tv/build-apk.ps1`、
`client/apps/desktop/build-gnu.ps1`、`server/build-plugin*.ps1`）——
它们依赖同目录的 gradle/Cargo/plugin-manifests 工程，搬走要重写路径。

### 1.2 ★ 自检：改完必跑

```bash
./deploy/scripts/verify.ps1          # 全检（约 2 秒）
./deploy/scripts/verify.ps1 -Fast    # 跳过 docker config（无 Docker 时）
```

八项检查（任何一项 FAIL 都会打印明细并以非零退出）：

| # | 检查 | 抓什么 |
|---|---|---|
| 1 | units.json 可读 | 文件缺失 / JSON 坏了 |
| 2 | 构建单元路径存在 | 脚本被移动/删除但清单没改 |
| 3 | **全库脚本已被清单覆盖** | **新增脚本没登记**（这就是"文档记录不及时"的机器化检查） |
| 4 | 打包脚本语法 | PS 解析失败（BOM 缺失等） |
| 5 | 版本号一致 10 处 | 漏改（含 Cargo.lock 滞后） |
| 6 | compose 配置有效 | context/dockerfile 路径漂移 |
| 7 | **文档里的脚本路径** | **文档过时**（引用了不存在的脚本；扫 43 份活文档 + workflow） |
| 8 | **文档里的部署文件路径** | **命令在错误目录执行**（如根目录跑 `-f docker-compose.yml`，实测会 file not found） |

开发辅助脚本（`start-web.bat` 等）不需要登记，白名单在 `verify.ps1` 的 `$devHelpers`。

> **第 7、8 项的由来**：2026-10-26 实测发现 docs/36 里
> `docker compose -f docker-compose.build.yml` 在仓库根目录跑会报
> `The system cannot find the file specified`——文件早迁到 `deploy/docker/`，
> 但当时只检查 `deploy/README.md`，自检照样报「全部通过」，等于没检查。
> 现在扫全部活文档（归档除外），并且能识别引用块里的 `> ```bash` 代码块。

**为什么 Docker 文件在 `deploy/docker/` 而不是根目录**：根目录只留产品代码入口
（`client/` `server/`）和两个说明文件。Docker 的 `context: ../..` 指回仓库根，
所以 Dockerfile 里的 `COPY client/...` 等路径不用改。

---

## 二、部署（Docker 四容器）

### 2.1 快速开始

```bash
cd deploy/docker
cp .env.example .env          # 首次
docker compose up -d          # 拉 Registry 镜像启动
# 浏览器打开 http://localhost:30010
```

改源码后重建（两个 compose 二选一，**不是叠加**）：

```bash
cd deploy/docker
docker compose -f docker-compose.build.yml up -d --build
```

### 2.2 四个容器

| 容器 | 内容 | 端口 |
|---|---|---|
| `web` | nginx + 静态文件（**无 JVM**） | 30010 |
| `apiserver` | JRE + classes + libs + **ffmpeg**（**无 nginx**） | 30011（默认绑 127.0.0.1） |
| `drpy` | Node 运行时（跑 `.js`/drpy 源） | 30013 → 容器内 9330 |
| `danmu_api` | 官方镜像 `logvar/danmu-api:1.21.6` | 30012（默认绑 127.0.0.1） |

`drpy` 不能省：没有它，配置里 200+ 个 drpy 站会被判"不可用"。
`apiserver` 内置 ffmpeg（镜像 1.33GB）：Web 端播 MKV/H.265 靠它转码。

### 2.3 常用操作

```bash
cd deploy/docker

docker compose ps                    # 状态
docker compose logs -f apiserver     # 跟日志
docker compose restart apiserver     # 重启单个

# ★ 停单个服务用 stop/start，不要用 down <服务名>
#   down 会删掉共享的 meow 网络，让其他容器失去网络
docker compose stop web && docker compose start web
docker compose rm -sf web            # 删单个容器

# 升级
docker compose pull && docker compose up -d
```

### 2.4 变量（`.env`）

只列最常改的，全表见 `.env.example` 注释与 [docs/36 §4.4](../site/docs/dev/docker.md)。

| 变量 | 默认 | 说明 |
|---|---|---|
| `MEOW_VERSION` | `latest` | 镜像 tag；默认跟随最新发布，要固定版本才填（`bump-version.ps1` 不再维护） |
| `WEB_PORT` / `APISERVER_PORT` / `DRPY_PORT` / `DANMU_API_PORT` | 30010/30011/30013/30012 | 端口冲突时改 |
| `APISERVER_BIND` | `127.0.0.1` | 给 TV / 局域网直连改 `0.0.0.0` |
| `MEOW_APISERVER` | `http://apiserver:9979` | web 连外部后端时改绝对地址 |
| `MEOW_JS_RUNTIME` | `http://drpy:9330` | 不启动 drpy 时置空 |
| `MEOW_DANMAKU_API` | `http://danmu_api:9321` | 不启动弹幕聚合时置空 |

**两个坑**：
1. **`${VAR-默认}` 不要写成 `${VAR:-默认}`**——后者把空串也当"未设置"，
   `.env` 里写 `MEOW_DANMAKU_API=`（禁用）会失效。compose 里已按此写。
2. **没有 `depends_on`** 是刻意的：加了之后 `up -d web` 会把后端一起拉起来，
   就失去"只起前端连远端后端"的能力。

---

## 三、本地发版

### 3.1 标准流程

```bash
# 0. 改版本号（10 处一次改齐，含 TV versionCode 与 build.yml 兜底）
./deploy/scripts/bump-version.ps1 -Version 0.9.17
git diff                                # 人工过目
# （自行 commit —— 提交节奏由人控制）

# 1. 全量构建（前端 dist → PC → TV → 插件 → Docker → 汇总 + SHA256SUMS）
./deploy/scripts/release.ps1

# 2. 人工定稿 release/v0.9.17/RELEASE-NOTES.md（脚本只出草稿）

# 3. 校验
cd release/v0.9.17 && sha256sum -c SHA256SUMS.txt

# 4. 打标记（★ 无前缀 tag 不触发 CI，零额度）
git tag v0.9.17 && git push origin main --tags
```

### 3.1.1 ★ 本地发版 + 直接发到 GitHub（`-Publish`，零额度）

CI 发版要烧约 45 分钟额度且要盯十几分钟。**日常发版不必上 CI**：

```powershell
./deploy/scripts/release.ps1 -Publish                    # 构建 + 建 Release + 传清单
./deploy/scripts/release.ps1 -Publish -PublishTag v0.9.39-rc1   # 自定义 tag 名
./deploy/scripts/release.ps1 -Publish -Plan              # 只看会做什么
```

它做了 CI 里那套「publish + sync-packages」：

```
建 tag（无前缀 v<版本>）→ 推送 → 建 draft Release
  → 传 exe/apk/zip → 调 sync-packages.ps1 生成并上传 packages.json
```

**三条硬约束**（脚本会校验前两条，第三条要人做）：

| # | 约束 | 为什么 |
|---|---|---|
| 1 | tag **必须无前缀** | `release-v*` 会触发 CI 全量（45 分钟额度），与"本地不烧额度"相反。带前缀会被**构建前**拦下 |
| 2 | 必须有 GitHub 令牌 | `gh auth login` 或 `GITHUB_TOKEN`；缺了在参数校验阶段就报错，不会白构建 30 分钟 |
| 3 | **建完是 draft，要去网页点 Publish** | draft 的资产对 API 不可见，客户端看不到更新。脚本结尾会打印链接提醒 |

> **★ 仍需 `git commit` 源码**：`-Publish` 只建 tag 指向当前 HEAD。
> 工作区若有未提交改动，产物与 tag 对应的源码会不一致（脚本会警告）。

> **★ 内置 curl 重试**：本机到 `api.github.com` 的 TLS 握手抖动频繁
> （实测 3 次里 2 次失败 `schannel: failed to receive handshake`），
> 脚本对传输层失败重试 5 次（1+2+3+4 秒退避）；HTTP 4xx 不重试（业务错误）。

**与 CI 的分工**：

| | 本地 `-Publish` | CI `release-v*` |
|---|---|---|
| 耗时 | 30–40 分钟（本机） | 16 分钟墙钟 |
| 额度 | **零** | 约 45 分钟 |
| 要盯 | 不用 | 要盯 |
| 产物 | 完全相同 | 完全相同 |
| 清单 | ✅ | ✅（点 Publish 后） |

### 3.2 脚本参数

**`bump-version.ps1`**

```powershell
./deploy/scripts/bump-version.ps1 -Check              # 只校验 10 处是否一致
./deploy/scripts/bump-version.ps1 -Version 0.9.17     # 提升
./deploy/scripts/bump-version.ps1 -Version 0.9.17 -DryRun   # 预演不写盘
```

管辖 10 处：3×package.json、tauri.conf、Cargo.toml、**Cargo.lock（仅 meow-player 条目）**、
apiserver.manifest、TV build.gradle（versionName + versionCode）、
`docker-compose.build.yml` 的 `${MEOW_VERSION:-x}`、fnOS fpk manifest。
拉取型 compose 与 `.env.example` 用 `latest`，不随版本走。

`versionCode` = `major*1000 + minor*100 + patch`（0.9.17 → 917）。**拒绝降版本**。

> **★ Cargo.lock 是 cargo 生成的**，只改 Cargo.toml 不会自动同步。
> 版本校验失败时会提示：`cargo update -p meow-player --offline` 或手动改。

**`release.ps1`**

```powershell
./deploy/scripts/release.ps1                    # 全量
./deploy/scripts/release.ps1 -SkipDocker        # 不推镜像
./deploy/scripts/release.ps1 -SkipPc -SkipTv    # 只出插件 + Docker
./deploy/scripts/release.ps1 -DockerDryRun      # 镜像只构建不推送
./deploy/scripts/release.ps1 -Plan              # 只看会做什么
./deploy/scripts/release.ps1 -Force             # 清空 release/v<版本>/ 重放
./deploy/scripts/release.ps1 -Publish           # 构建完直接发 GitHub Release（见 §3.1.1）
./deploy/scripts/release.ps1 -Publish -PublishTag v0.9.39-rc1   # 自定义 tag 名
```

**单链路**（只重做一个产物时用）

逐条完整命令（可直接复制）：

```powershell
# 只推镜像
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/scripts/build-push-docker.ps1 -User chrplus

# 只出 TV APK（产物落 release/v<版本>/；-NoCopy 只构建不落位）
powershell -NoProfile -ExecutionPolicy Bypass -File client/apps/tv/build-apk.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File client/apps/tv/build-apk.ps1 -Variant debug -NoCopy

# 只出 PC 包（不带 --bundles nsis 只出裸 exe，不出安装包）
powershell -NoProfile -ExecutionPolicy Bypass -File client/apps/desktop/build-gnu.ps1 --bundles nsis

# 只出插件（★ 用 build-plugins.ps1：它读 units.json，自动跳过缺前置的 danmu、
#   容忍 ffmpeg 下载失败，并生成 packages 清单）
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/scripts/build-plugins.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/scripts/build-plugins.ps1 -Only drpy,ffmpeg
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/scripts/build-plugins.ps1 -OutDir tmp/plugins
```

> **不要**再手敲 `./server/build-plugin.ps1 && ./server/build-release.ps1`——
> 那会漏掉 drpy/ffmpeg，也不会按 units.json 的可选/必需规则处理失败。

### 3.3 产物命名：本地中文、CI 英文

| 产物 | 本地 | CI（Release 附件） |
|---|---|---|
| TV | `猫窝-TV-0.9.17.apk` | `meow-TV-0.9.17.apk` |
| PC 安装包 | `猫窝_0.9.17_x64-setup.exe` | `meow-setup-0.9.17.exe` |
| PC 裸 exe | `猫窝-裸exe-0.9.17.exe` | `meow-bare-0.9.17.exe` |
| iOS（未签名） | —（CI 专属） | `Meow-unsigned-<版本>.ipa` |
| 插件 | `apiserver-0.9.17.zip` 等 | 同左 |

**为什么不一样**：GitHub 把非 ASCII 附件名转义成 `-`/`_`（实测显示成
`-_0.9.17_x64-setup.exe`，用户认不出），所以 CI 侧统一 ASCII；
本地文件系统没这限制，保留中文更友好。

---

## 四、CI 发版（Git 工作流）

### 4.1 ★ 触发契约：tag 前缀决定构建范围

| 触发 | 实际跑的 job | 建 Release | 发 Publish 后的 sync scope | 实测 |
|---|---|---|---|---|
| **`v1.0.10`**（无前缀） | **什么都不跑**——纯版本标记 | ❌ | — | 0 |
| `release-v1.0.10` | docker + tv + pc_shell + **ios（未签名 IPA）** + plugins（**4 个 PC 插件**；tv-node 归 `tv-plugins-v*`）+ **fpk ×2（x86/arm，Publish 后写 FnDepot + fpk-updates）** | ✅ draft | `meow-pc` `meow-tv` `plugins` + fpk 两条索引 | **9m58s**（1.2.4 实测；iOS 另加约 2–13 min，macOS runner 2× 计费） |
| `tv-v1.0.10` | tv（**不含模块**） | ✅ draft | `meow-tv` | 2.7 min |
| `tv-plugins-v18.20.4.1`（示例） | tv_plugins（模块 ×3 ABI）；tag 用模块版本，不是壳版本 | ✅ draft | `tv-module`（只模块三条） | 163 s |
| `pc-v1.0.10` | pc_shell + plugins（**仅 apiserver**） | ✅ draft | `meow-pc` `plugins` | 13 min（Windows ×2） |
| `plugins-v1.0.10`（示例） | plugins（**4 个 PC 插件**，Windows runner，无壳；tv-node 不在此链）；tag 可用插件批次版本 | ✅ draft | `plugins` | 5 min+ |
| `docker-v1.0.10` | docker（三镜像推 Hub） | ❌ | — | 6 min |
| `fpk-v1.0.10` | 另一 workflow：fpk plan + build(x86/arm) + **本 tag 的 draft Release**（补包用；全量 `release-v*` 会调它构建但包进主 Release） | ✅ draft（仅单链） | 不走 sync-packages；**Publish 后**写 FnDepot + fpk-updates | ~3m |
| `workflow_dispatch`（release.yml） | **按勾选**：tv / pc / ios / plugins / docker / tv_plugins（默认全 true；全不勾会被 plan 判错）。fpk 走自己的 dispatch，建 Release 只允许 tag | ❌ | — | 按勾选 |
| `tv-verify-1.0.10` | 什么都不跑——前缀后不是数字 | ❌ | — | 0 |

> **产物体积（1.2.22 实测；构建期按平台裁剪 guard-libs 后）**
> `apiserver-*.zip` ≈46MB；`meow_*_x86.fpk` ≈38MB、`arm` ≈37MB；Docker apiserver 镜像的
> 拉取体积比 1.2.21 少约 18MB。`server/host/guard-libs` 源目录 34MB（unidbg / unicorn /
> keystone 等），构建时只保留目标 CPU 平台的原生库 → 15~16MB，规则与断言见
> `server/tools/guard/trim-guard-libs.sh`（Windows 用同名 `.ps1`）。**不要因为这组数字
> 变回去就判定"漏打依赖"**：加密源仍完整可用，四端真解密都验过。

**预览验证期不推 latest**：仓库变量 `MEOW_PUSH_LATEST=false` 时，`docker` job 只推版本号镜像、
不更新 `docker:latest`（Release 本身是 draft，也不会成为 GitHub Latest）。
验证通过、正式发布前删掉该变量即可恢复默认（不设 = true）。

**三条铁律**

1. **单链也建 Release**（除 docker）——2026-11 起，`publish` 的门槛从"必须 `release-v*`"改成"任一条被选中且没有 failure/cancelled"；没选中的链是 `skipped`，视为通过。
2. **Release 永远是 draft**，必须人点 Publish；`sync-packages`（生成 packages.json）与 fpk 的 `index-after-publish`（FnDepot + fpk-updates）都只在 `release: published` 时跑。**不点 Publish = 客户端收不到更新、飞牛应用中心也拿不到新版。**
3. **模块（tv_plugins）只在 `tv-plugins-v*` 跑**。`tv-v*` 不带（最常用的只发壳入口）；
   `release-v*` 也不带（模块是独立版本线，内容没变时只是把 150MB 再搬一遍；
   全量 Release 的自包含由「复制继承资产」保证，不重发也会从模块自己的 release 复制过来）。

```bash
# ① 只打版本标记（本地发完版常用）—— 零额度
git tag v0.9.17 && git push origin v0.9.17

# ② 要 CI 构建，必须带前缀（先对齐 10 处版本号）
git tag release-v0.9.17 && git push origin release-v0.9.17   # 全量
git tag docker-v0.9.17  && git push origin docker-v0.9.17    # 只镜像
```

**四条规则**：
1. **无前缀 `v*` 不触发 CI**——它只是标记。这是刻意的（本地发完打个记号不该烧额度）。
2. **前缀后面必须紧跟数字**。过滤规则是 `tv-v[0-9]*` 而不是 `tv-v*`：
   后者的 `*` 能匹配任意非斜杠字符，所以 `tv-verify-0.9.15`、`tv-version-test`
   这类名字会被一起吞进来（实测 2026-10-26 因此白跑了两次 TV 全链）。
   想临时验证请走 [§4.2 手动触发](#42-手动触发不动-git-历史)，不要另造 tag 名。
3. **壳版本号永远取 `client/package.json`**，与 tag 里的数字无关。
   > 踩过：用 tag 名推版本，导致找 `0.9.15-test3` 的安装包而产物叫 `0.9.15`。
   > 例外：`tv-plugins-v*` / `plugins-v*` 是独立版本线，清单按资产名逐条取版本。
4. **带前缀的构建 tag 都会建 draft Release**（`release-v*` / `tv-v*` / `pc-v*` /
   `plugins-v*` / `tv-plugins-v*` / `fpk-v*`），**需人工 Publish**；`docker-v*` 只推镜像不建 Release；
   `fpk-v*` 是独立 workflow，同样只建 **draft**（主仓镜像），**Publish 之后**才写 FnDepot
   与 `fpk-updates`，不参与 `packages.json`；
   **全量 `release-v*` 会以 workflow_call 调同一个 fpk.yml**（2026-11-13 起：全量必须带
   fnOS 包，不再需要手动补推 `fpk-v*`），fpk 包进主 Release，两条索引同样等 Publish。
   **手动 dispatch 一律不建 Release**——它的 ref 是分支名，拿来当 `tag_name`
   会建出叫 `main` 的 Release（2026-10-26 就因此手工清理过）。要出正式版必须打 tag。

**`pcplugins-v*` 已删除**（2026-11-06）。现在：
- `pc-v*` = PC 壳 + apiserver（`plugins_only=apiserver`），不会构建 drpy/danmu/ffmpeg/node；
- 要 PC 全插件：单独打 `plugins-v*`（或 dispatch 勾选插件），不要指望 `pc-v*` 全带；
- 插件链已拆到 `_plugins.yml`（Windows runner，2× 计费），PC 壳在 `_pc_shell.yml`
  （Windows runner，2× 计费）；两者独立运行，互不等对方。

**`publish` 在本次选中的链没有 failure 时建 Release**：
`publish` 显式检查 docker / tv / tv_plugins / plugins / pc_shell 的 `result`，
**skipped 通过、failure/cancelled 跳过**；任一选中链失败则由 `diagnose` job 标红并说明原因。
它不再要求“三条链全绿”（单链 tag 只跑一条链，其余 skipped）。

### 4.2 手动触发（不动 git 历史）

Actions → **release** → Run workflow，勾选框任意组合：

```
[ ] Docker 三镜像    [ ] TV APK    [ ] PC 安装包    [ ] 插件（频率低）
[ ] Docker 更新 latest
```

适合：重推镜像、补一个 TV 包、只验某条链。

**没有「建 Release」选项**：手动触发不建 Release，产物只在 artifacts（90 天）。
要出正式版请打 `release-v*` tag（§4.1）。

### 4.3 工作流结构

```
release.yml             主入口：plan 判定范围 → 按需 call → publish
├── _docker.yml         Docker 三镜像（可复用）
├── _tv.yml             TV APK（可复用）
├── _tv_plugins.yml     TV 运行时模块 APK ×3 ABI（可复用）
├── _plugins.yml        插件包（Windows runner，可复用）
└── _pc_shell.yml       PC 安装包（Windows runner，可复用）

fpk.yml                 fnOS fpk（tag fpk-v* / dispatch / release-v* 全量链以 workflow_call 调用；
                        release:published → index-after-publish 写 FnDepot + fpk-updates）

ci.yml                  质量检查（PR / 手动 dispatch，约 1 分钟：构建+类型+单测+javac+drpy）
                        ★不监听 push 到 main——那几项本地必然已跑过，云端重放没有新信息
```

**为什么拆文件**：改 Docker 构建不会碰坏 TV 的步骤；单链路重发时日志短。
**为什么是薄封装**：调的是同一批 PS 脚本，本地与 CI 永远一条路径，不会双份维护。

**为什么 `ci.yml` 不跟 push**：实测 2026-10-26 一天 push 触发 28 次、28/28 全绿、
0 次拦下问题，且私有仓不支持分支保护，红了也已经推上去了，起不到门禁作用。
它跑的三件事（`pnpm build` 已含 `vue-tsc`、`build.bat` 是 javac、drpy `npm test`）
本地都能跑且版本一致。质量门禁实际在 `release.yml`——那边抓到过 5 次失败。

### 4.4 已配好的 Secrets

| Secret | 级别 | 用途 |
|---|---|---|
| `DOCKER_HUB_TOKEN` | **Environment「dockerhub」** | 推三镜像（用户名 `chrplus` 写在 workflow 里） |
| `TV_KEYSTORE_B64` | 仓库级 | TV keystore 的 base64 |
| `TV_STORE_PASSWORD` / `TV_KEY_ALIAS` / `TV_KEY_PASSWORD` | 仓库级 | TV 签名密码 |

> **★ Environment 级 secret 只有 job 声明 `environment: dockerhub` 才可见**，
> 漏了会报 `Password required`（实测踩过）。
> TV 签名的备份红线见 [docs/46](../site/docs/dev/tv-signing.md)。

### 4.5 平台分配（别改）

| job | runner | 为什么 |
|---|---|---|
| plan / publish | ubuntu | 只是读版本、搬附件 |
| docker | ubuntu | buildx + QEMU 出双架构 |
| tv | ubuntu | JDK17 + Android SDK + gradle |
| **PC + 插件** | **windows** | ★ 插件强绑 Windows，见下 |

**插件为什么搬不上 ubuntu**（五处硬绑定）：
1. `build-release.ps1` 用 `$env:SystemRoot\System32\tar.exe` 打 zip
2. `build-plugin.ps1` 用 jlink 裁 **宿主机平台**的 JRE
3. `build-plugin-ffmpeg.ps1` 下载 Windows 静态版 ffmpeg
4. `build-plugin-danmu.ps1` 内嵌 `node.exe`
5. manifest 硬编码 `"platform": ["windows-x64"]`

### 4.6 额度

- 私有仓 **2000 分钟/月**；**Windows runner ×2 计费**
- 全量一次实测（2026-11-13，1.2.4）：墙钟 **9m58s**，折算额度 **≈38 分钟**（PC 17.3 + 插件 9.7 是 Windows 那两份）
- 已加 `timeout-minutes`（Docker 30 / TV 25 / PC 45 / fpk 30），防卡死烧满 6 小时默认上限
- 缓存（2026-11-13 重整，**key 全部不再吃 app 版本号**）：
  · PC `target/` + cargo registry：key 用「剔除 meow-player `version` 行」的 lock 指纹。
    以前直接用 `Swatinem/rust-cache` 绑 `Cargo.lock`，而 `bump-version.ps1` 每版都改那行
    → **缓存从未命中**（恢复日志 `No cache found.`），226 个 crate 全量重编 5m01s
  · PC MinGW 16.2.0 整目录（choco 装一次，省 53s；约 1.2GB，占缓存额度要留意）
  · 插件链：ffmpeg 包与 danmu 上游按 ISO 周刷新（下载地址是滚动的，不能钉死）
  · tv-node 不在插件链构建（版本线 `18.20.4.<N>` 与壳无关，归 `tv-plugins-v*`）
  · 其余：gradle、buildx（`type=gha, mode=max`）、fpk 的 cargo
- ★ 插件产物缓存的 key **必须带 `plugins_only`**：`pc-v*`/`tv-v*` 只建 apiserver，
  曾与全量共用 key → 全量命中后跳过构建、Release 少 4 个插件（2026-11-13 修）

### 4.7 ★ 清单与在线更新（`packages.json` + `CHANGELOG.md`）

**这是让客户端"收到更新提示"的唯一路径**，不走这一步 = 用户永远看到"已是最新版本"。

#### 一图看清链路

```
git tag release-v* / tv-v* / pc-v* / plugins-v* / tv-plugins-v*
                    →  对应构建链  →  publish job 建【draft】Release
                                              │
                                              ▼  ★ 必须去 GitHub 点 Publish
                                        release: published 事件
                                              │
                                              ▼
                                    sync-packages job
                                    ├─ checkout fetch-depth: 0（★ 见下）
                                    ├─ 按 tag 前缀决定更新哪些条目
                                    ├─ 生成 packages.json（含 desc = 更新说明）
                                    └─ 上传到该 Release（覆盖同名资产）
                                              │
                                              ▼
                                    客户端拉到清单 → 比对版本 → 弹框
```

#### 只发一次就必须记住的四件事

| # | 事项 | 不做会怎样 |
|---|---|---|
| 1 | **除 `docker-v*` 外，带前缀的构建 tag 都会建 Release**（`release-v*` / `tv-v*` / `pc-v*` / `plugins-v*` / `tv-plugins-v*` / `fpk-v*` 全部建 draft）；无前缀 `v*` 不建 | 单链 tag 也能补包；`docker-v*` 只推镜像；`fpk-v*` 在 Publish 后写 FnDepot 与 `fpk-updates` |
| 2 | **建出来是 draft，必须去网页点 Publish** | draft 的资产对 API 不可见，`pickManifestRelease` 会跳过它；fpk 的两条索引也不会写 |
| 3 | **`sync-packages` / `index-after-publish` 只在 `release: published` 时跑** | 忘了点 Publish = 额度白烧，且没有任何报错 |
| 4 | **Publish 后还会把 `packages.json` force push 到 `updates` 分支** | 开源后客户端在 API 失败/限流时用 raw 静态清单兜底；不推只是少一条退路 |

#### 清单里的三条独立版本线（2026-11-13）

| 组件 | 清单 id | 版本来源 | 谁升 |
|---|---|---|---|
| 壳（PC/TV） | `meow-pc` / `meow-tv` | `client/package.json` | `bump-version.ps1` 自动 |
| 运行时模块（TV） | `tv-node-<abi>` | `18.20.4.<N>`，N 来自 `tv-plugins-v*` tag | 打 tag 时人定 |
| 弹幕载荷（TV） | `danmu-payload` | 与 PC 的 `danmu-api` **同一个 zip**，版本跟它 | 改 `danmu-api.manifest.json` |

- 载荷条目 `platform: ["android"]`、`hidden: false`；PC 插件列表按 `windows-x64` 过滤，
  不会把这条显示成本机插件
- 改源码没升版本 → 发布 job 的「插件版本守卫」直接红（`check-plugin-versions.ps1`）

#### 更新说明从哪来：`CHANGELOG.md`

客户端弹框里显示的正文来自清单条目的 `desc`，三档来源按优先级：

1. **`CHANGELOG.md` 里 `## <版本>` 那一节**（人写的，最准）
2. `git log <上个 tag>..<main 最新>` 只取 `feat:` / `fix:` / `perf:`（自动兜底；
   终点优先 `origin/main`，其次本地 `main`，都没有才退回 `HEAD`）
3. 模板串 `猫窝 XX 版 x.y.z`（等于没有说明）

**写法**（详见仓库根 `CHANGELOG.md` 顶部）：

```markdown
## 0.9.39

1. 启动后自动检查更新，有新版就弹框
2. 内置默认更新源，装上就能用
3. 更新说明改成读 CHANGELOG，不再用提交信息
```

- 标题恰好 `## 0.9.39`（三位数字，**不带 v 前缀**）
- **按 `1. 2. 3.` 编号，每条一句话（≤60 字）**。漏写编号时
  `sync-packages.ps1` 与 `release.yml` 会自动补，但**长句补不了**
- **最多 6 条**——弹框只显示前 4 条，剩下的点「查看详情」
- 面向用户写：说"哪个按钮现在好用了"，不写类名、不写 `fix(xxx):`
- `verify.ps1` 的「CHANGELOG 更新说明格式」会挡住不编号/超长/超条数的小节

> **★ 忘了写会怎样**：退到 `git log`。`chore:` / `docs:` / `test:` 不进去，
> 所以低价值提交不会露给用户；但 `feat:` 的描述是给开发看的，用户可能看不懂。
> 发版前花一分钟写这一节，比事后解释省事。

#### `sync-packages.ps1` 的 scope 规则（增量合并，不是覆盖）

脚本按 tag 前缀决定**更新哪些条目**，其余从线上现有清单继承：

| tag | 更新的条目 |
|---|---|
| `release-v*` | `meow-pc` + `meow-tv` + `plugins`（tv-node 从上一版 Release 继承，不再重建） |
| `pc-v*` | `meow-pc` + `plugins`（实际只有 apiserver 资产；其余插件保留旧条目） |
| `tv-v*` | `meow-tv`（有模块资产时一并更新 `tv-node-*`，没有则保留） |
| `tv-plugins-v*` | `tv-node-*` |
| `plugins-v*` | `plugins` |
| `docker-v*` | 无（不建 Release） |
| `fpk-v*` | 不走本脚本；fnOS 包与 FnDepot 索引见 [构建与发版 §fnOS fpk](../site/docs/dev/build-release.md) |

**只发 TV 不会让 PC 条目消失**——脚本先读线上清单做基线，只覆盖本次涉及的那几条。

基线优先级：① 本次 release 的 `packages.json` → ② **上一个**非 draft release 的
→ ③ 本地 `$TEMP/packages.json`（仅本地调试；CI 全新 runner 上必然没有）。

#### ★ `fetch-depth: 0` 不能删

`sync-packages` job 的 checkout 显式写了 `fetch-depth: 0`。默认的 `fetch-depth: 1`
是浅克隆，`git log <上个 tag>..<main 最新>` 会报 `unknown revision or path`，
于是每条产物的 `desc` 都静默退化成模板串——**不报错，只是更新说明永远失效**。

> **本地也要先 `git fetch --tags`**：我们的 release tag 是 `gh` 在远端建的，
> 本地 `git tag --list` 里可能没有（实测踩过）。

#### 手动跑（CI 出问题时排查用）

```powershell
./deploy/scripts/sync-packages.ps1                              # 自动取最新非 draft release
./deploy/scripts/sync-packages.ps1 -Tag release-v0.9.39         # 指定 tag
./deploy/scripts/sync-packages.ps1 -Tag release-v0.9.39 -DryRun # 只看结果不写盘
./deploy/scripts/sync-packages.ps1 -Tag release-v0.9.39 -SkipUpload  # 只生成不上传
```

脚本用 **curl 走 REST API**（不用 `gh release upload`——本机 `gh` 频繁
`TLS handshake timeout`）。上传后会**回读校验**：重新拉一次 Release 上的
`packages.json`，确认内容与刚生成的一致。

#### GitHub 加速与静态清单回退（2026-11-09）

- **全局设置**：设置 → 网络与加速 →「GitHub 加速」，可自定义有序列表、逐项测试、启停、恢复默认；应用更新 / 插件源 / fpk / 配置导入共用。
- **只加速公开地址**：`raw.githubusercontent.com`、`github.com/.../releases/download/`、公开 `api.github.com`。私有仓 token 请求**直连**，公共代理不转发 Authorization。
- **默认顺序**（实测定，前端可调）：直连 → `gh-proxy.com` → `ghfast.top` → `ghproxy.net`；`gh.llkk.cc` 预设保留但默认停用；jsDelivr 仅用于索引或 <20MB 文件。
- **资产选择**：清单里没有 token 时客户端优先 `browser_download_url`（标准 release 链接，代理才生效）；有 token（私有仓）才转 `api.github.com/.../releases/assets/<id>`。
- **静态清单**：`sync-packages.ps1` 在 Publish 后把 `packages.json` force push 到 **`updates` 分支**，客户端在官方 API 失败/限流时可回退：
  `https://raw.githubusercontent.com/upchr/meow/updates/packages.json`
  （分支在下一次 release 后才会出现；开源前私有仓匿名不可读，属于预期）。
- **更新中心（2026-11-11）**：设置 → 维护 →「更新中心」聚合壳 / TV 运行时模块 / PC 插件 / fpk 的检查与状态；只合并入口，安装仍走各自通道。壳启动时自动检查；模块/插件/fpk 默认 24h 一次，可在面板逐项关闭。
- **显式备用源**：应用更新支持填写「备用源」；主源失败才试，不会自动回落官方源。官方源失败另有 `updates` 分支静态清单兜底。
- **插件源跟随应用源**：PC 插件源面板有「使用应用更新源」开关，默认把同一条 Release 的 `packages.json` 加为插件源；插件安装器仍独立（下载 zip / 解压 / 回滚）。
- **fpk 双通道（2026-11-13 对齐 TV/PC）**：
  · **全量 `release-v*`**：fpk 包由 `publish` 收进**主 Release**（和 apk/exe 同级，`publish` 的 `needs` 含 `fpk`），**不出 `fpk-v*` prerelease**。
  · **单链 `fpk-v*`**：补包时建本 tag 的 **draft** 托管两个 fpk（和 tv-v*/pc-v* 同形）。
  · **两条索引都等 Publish**：`index-after-publish`（`release: published`）从已发布的 Release 里
    `gh release download` 两个 fpk、重算 sha256 后写 FnDepot 与 `fpk-updates`。草稿阶段一条都不写，
    删掉草稿也不会留下用户可见的索引。
  · FnDepot 是飞牛应用中心正式源（2026-11-27 起**只存索引与图标**，`download_url` 是主仓 Release 绝对直链，包体不再入库）；`fpk-updates` 分支是主仓镜像索引，`download_url` 指向上面那个 Release 的资产，**合并写入**（补发旧版本不会顶掉新版本）。客户端先读 FnDepot，不可达/更旧时读镜像：
  `https://raw.githubusercontent.com/upchr/meow/fpk-updates/fpk.json`
- **版本线**：壳（`release/tv/pc`）与 fpk tag 必须等于 `client/package.json`；`tv-plugins-v*`（模块）与 `plugins-v*`（插件批次）是独立版本线，tag 后缀只要是数字即可，清单按资产名逐条取版本。

#### 排查「客户端说没有更新」

按这个顺序查，**每一步都能排除一类原因**：

| 现象 | 查什么 | 判据 |
|---|---|---|
| 客户端报"该 release 里没有 packages.json 资产" | ① Release 是不是 draft ② 资产在不在 | `gh release view <tag>`；draft 看不到 |
| 清单在，但客户端没反应 | 清单里的 `version` 是否 ≤ 当前版本 | `isNewer()` 严格大于才提示 |
| 清单在，版本也对，但没弹 | 是不是点了「稍后」 | `localStorage['meow.update.skipVersion']` |
| 一直"已是最新" | 更新源填的是什么 | 空 = 内置 repo 端点；填错域名则拉不到 |
| 平台不匹配被跳过 | 清单条目的 `platform` 数组 | 必须含当前平台（MuMu 是 `android-x86_64`） |

**★ 平台校验有三层**（`entryFitsPlatform`），任一层否决就跳过：
`id`（`-pc`/`-tv` 后缀）→ `platform` 数组 → 扩展名（`.exe`/`.msi` vs `.apk`）。
清单里写错 id 或 url，客户端会明确拒绝下载并给出可读原因。

---

## 五、发版检查清单

### 5.1 构建前

- [ ] `./deploy/scripts/bump-version.ps1 -Check` 通过（10 处一致）
- [ ] **`CHANGELOG.md` 里有本次版本的 `## x.y.z` 小节**（否则客户端弹框
      显示的是 git log 原始提交信息或模板串，见 §4.7）
- [ ] 工作区没有未提交的意外改动（`git status`）
- [ ] TV 签名文件在位：`client/apps/tv/keystore.properties` + `meow-release.jks`
      （缺失会静默退 debug 签名，用户装不上正式版）
- [ ] 磁盘空间够（`release/v<版本>/` 约 110MB，中间产物另需数 GB）

### 5.2 构建后

- [ ] `release/v<版本>/` 齐全：TV apk、PC setup.exe、裸 exe、插件目录
- [ ] `sha256sum -c SHA256SUMS.txt` 全 OK
- [ ] 插件 `index.json` 里的 sha256 与 zip 实际值一致
- [ ] 体积合理（PC 安装包约 4.5MB；**apiserver 插件约 46MB**、fpk x86 ≈38MB / arm ≈37MB ——
      1.2.22 起 guard-libs 按平台裁剪，34MB → 15~16MB；低于这组太多才要怀疑漏打依赖）

### 5.3 冒烟（不能只看"构建成功"）

- [ ] 起容器或装包，`/api/health` 的 `version` 等于新版本
- [ ] 导入外部配置，站点数 ≥ 235（少了说明配置没读到）
- [ ] 播一部片，能出画面 + 弹幕有内容
- [ ] TV 包装进模拟器/盒子，首页能出海报、能进详情
- [ ] TV 包签名验过：`apksigner verify --print-certs`，指纹与 docs/46 一致

### 5.4 收尾

- [ ] 定稿 `RELEASE-NOTES.md`（**不能用脚本草稿直接发**——提交语言 ≠ 用户语言）
- [ ] **走 CI 发版的话：去 GitHub 把 draft Release 点 Publish**
      （不点 = 客户端收不到更新；`sync-packages` 只在 `release: published` 时跑）
- [ ] Publish 后确认 Release 上多了 `packages.json` 资产，且 `desc` 是
      你写进 CHANGELOG 的那段（不是模板串「猫窝 XX 版 x.y.z」）
- [ ] 改了插件载荷（`server/drpy`、`node-payload-danmu.zip`、ffmpeg 脚本、`runtime-node`）
      就要升对应版本号 —— 发布 job 的「插件版本守卫」会按源码 diff 拦（同版本不同内容 = 用户收不到）
- [ ] 含 fnOS 包时，确认 fpk 的 `index-after-publish` 绿了：FnDepot 的 `fnpack.json`
      出现该版本、`fpk-updates` 分支的 `fpk.json` 也合并进去（它和 `sync-packages`
      同属"Publish 之后"的动作，忘了点 Publish 两条索引都不动）
- [ ] 清理测试 tag / draft Release（见 §七）
- [ ] 需要对外分发时把 `release/v<版本>/` 放到分发渠道

---

## 六、什么改动该重发什么

| 你改了什么 | 打哪个 tag | 会构建 | 不会构建 |
|---|---|---|---|
| 前端任意代码（样式/bug） | `tv-v1.0.10` **+** `pc-v1.0.10` | TV APK、PC exe、apiserver | 插件其余三个、Docker、模块 |
| TV 本地网关（`ApiServer`/`LocalSites`/routes） | `tv-v1.0.10` | TV APK | 其余全部 |
| TV 侧插件（node 模块：载荷/桥/node 版本） | `tv-plugins-v18.20.4.1`（示例） | 模块 ×3 ABI | 壳、PC、Docker |
| PC 壳 Rust（`src-tauri/`） | `pc-v1.0.10` | PC exe + apiserver | TV、Docker、模块 |
| 后端 Java（`server/host/`） | `release-v1.0.10` 或 `docker-v*` + `plugins-v*` | 镜像 + apiserver 插件 | — |
| drpy / danmu-api / ffmpeg 插件 | `plugins-v1.0.10` | 插件 4 个（无壳） | 其余全部 |
| fnOS 壳/资源 | `release-v1.0.10`（或单链 `fpk-v1.0.10`） | fpk ×2（进主 Release）+ FnDepot / fpk-updates 索引 | 其余全部 |
| 只有 `docs/`、`README` | 不用发版 | — | — |

### 6.1 资产策略（2026-11 定）

`sync-packages` 决定每个 Release 的资产构成：

| 发布方式 | 继承资产（本次没重发的插件/模块） | 结果 |
|---|---|---|
| **单链**（`tv-*` / `pc-*` / `plugins-*` / `tv-plugins-*`） | **不复制**，清单里 url 继续指向旧 Release | 资产目录只留本次真正产出的文件；sync 更快、不占存储 |
| **全量**（`release-*`） | 复制到本次 Release，url 改写成本次 | 自包含归档，删任何旧 Release 都不影响 |

**运维规则（单链发布后必须遵守）**：不要删仍在被清单引用的 Release。`sync-packages` 会把这些条目打出来：

```
沿用旧 release 的条目（删了对应的旧 release 就会失效）：
  · apiserver → tv-v1.0.10
  · drpy → plugins-v1.0.10
```

### 6.2 版本号

- **唯一源** `client/package.json` 的 `version`；`bump-version.ps1` 一次改齐 **10 处**（含 TV `versionName`/`versionCode`、`docker-compose.build.yml` 的 tag）
- `versionCode = major*1000 + minor*100 + patch`（1.0.9 → 1009），Android 只增
- **一次 bump 可以打多条 tag**（都指同一个 commit），版本号天然对齐
- `release.yml` 已加 tag 版本校验：壳 tag（release/tv/pc/fpk）必须等于 `client/package.json`；
  `tv-plugins-v*` / `plugins-v*` 是独立版本线，tag 后缀不要求等于壳版本（但仍必须是数字）

### 6.3 耗时与额度（实测）

| 场景 | 墙钟 | 额度（含 Windows ×2） |
|---|---|---|
| `tv-v*` | 2.7 min | 2.7 |
| `tv-plugins-v*` | 163 s | 2.7 |
| `release-v*` | 13 min | **≈35 min**（PC 那条约 26） |

**额度大头是 PC 链**（Windows runner 2× 计费），不是 TV。日常只要不动 PC，单次成本是 3 分钟左右。

### 6.4 已拍板：不另建发行仓（2026-11-09）

现状：产物挂在本仓库的 Release 上，私有仓意味着客户端必须带 token 才能下载；
`.so`（62MB）也曾被迫入库过。

可选：另开一个**公开**仓库（如 `upchr/MeowRelease`）专做发行源，结构与 `upchr/FnDepot` 同一思路。

| 收益 | 代价 |
|---|---|
| 客户端可匿名下载（不用把 PAT 打进任何地方） | 多一个仓库要维护 |
| 主仓可以不开源 / 不动 git 历史 | 发布要跨仓推送（`FNDEPOT_TOKEN` 已有先例，可复用模式） |
| 资产与清单集中，`packages.json` 只此一份 | 版本号要在两处对齐（主仓 bump 后发行仓同步） |
| 删旧 Release 的风险集中、可审计 | 需迁移已有清单与资产 |

**结论（2026-11-09 已拍板）**：**不另建发行仓**。主仓后续直接开源，开源后 public releases 就是资产镜像；`packages.json` 由 `sync-packages.ps1` force push 到 `updates` 分支做静态清单兜底。闭源期不做镜像，必要时才临时放 FnDepot。

## 七、清理测试 tag / draft Release

CI 试跑会留东西，**发正式版前清干净**：

```bash
# 逐个删——批量删时若一个已不存在，整条命令会失败
for T in docker-v0.9.17b tv-v0.9.17; do
  git tag -d "$T"
  git push origin --delete "$T"
done

# 删误建的 draft Release（带前缀的构建 tag 都会建；确认损坏后再删，见 §4.1）
gh release delete v0.9.17-test --yes

# 确认干净
git tag -l | grep -E "test|docker-v|tv-v"
gh release list
```

---

## 八、坑索引（全部实测踩过）

### 8.1 本地 / 脚本

| 现象 | 根因 | 修法 |
|---|---|---|
| `.ps1` 整脚本解析失败 | UTF-8 **无 BOM**，PS 5.1 按 GBK 读中文注释 | 存成 UTF-8 with BOM |
| `Set-Content -Encoding utf8` 生成的校验文件 `sha256sum -c` 报错 | PS5.1 写的 UTF-8 带 BOM | 用 `UTF8Encoding($false)` 直写 |
| 同进程 `&` 调用拿不到 exit 码 | `& other.ps1` 后 `$LASTEXITCODE` 不可靠，对方 `exit` 会带崩会话 | 跨脚本编排用 `powershell -NoProfile -File` |
| PC 包装好但内容是旧的 | cargo 缓存不更新嵌入资源 | `touch src-tauri/src/main.rs` 强制重编 |
| PC 构建报文件被占用 | 旧版应用在运行 | 先退出应用 |
| 只出裸 exe 没出安装包 | 没传 `--bundles nsis` | 传参；判成功看 `bundle/nsis` 里有当前版本号的文件 |
| **打了 tag 但发版链全 skipped** | `prepare` 的版本校验失败，后续因 `needs` 全跳过 | 看「版本一致性」那步的 ✗ 清单；本地先跑 `bump-version.ps1 -Check` |
| **`Cargo.lock 找不到 X`** | 它是 cargo 生成物，只改 Cargo.toml 不同步 | `cargo update -p meow-player --offline` 或手动改 |

### 8.2 Docker

| 现象 | 根因 | 修法 |
|---|---|---|
| `docker compose up -d` 拉到旧镜像 | 镜像 tag 默认 `latest`，但 `up -d` 不会重新拉已有镜像 | `docker compose pull && docker compose up -d`；要可复现就在 `.env` 钉具体版本 |
| `Password required`（CI 登录） | `DOCKER_HUB_TOKEN` 是 Environment 级，job 未声明 `environment` | 加 `environment: dockerhub` |
| `failed to find instance "builder"` | `setup-buildx-action` 的 builder 名不固定 | 不传 `-Builder`，让脚本自建 |
| `pnpm` 在容器里中止 | 无 TTY | `Dockerfile.web` 里补 `ENV CI=true` |
| 停一个服务后其他容器失联 | `down <服务名>` 删了共享网络 | 用 `stop`/`start`，或 `rm -sf` |
| web 容器启动即崩 | nginx 写死上游地址，启动时解析不到 apiserver | 上游用变量 + `resolver 127.0.0.11` |

### 8.3 CI

| 现象 | 根因 | 修法 |
|---|---|---|
| `A positional parameter cannot be found ... 'linux/amd64,linux/arm64'` | pwsh 把含逗号的数组元素拆成两个参数 | 用 hashtable splatting |
| `Failed to find package tools` | `setup-android` 的 packages **默认值**含已废弃的 `tools` | 显式 `packages: 'platform-tools'` |
| `Cannot find path server/build/dist` | 该目录是 gitignore 的本地产物 | 脚本加 `Test-Path` 分支 |
| `MinGW install ok but gcc not found` | choco 装到 `ProgramData\mingw64\mingw64\bin` | 三处候选 + 递归兜底 |
| `Keystore file '.../app/meow-release.jks' not found` | `storeFile` 相对 **`app/`** 解析，CI 漏了 `../` | 写 `../meow-release.jks` |
| Release body 显示 `$(date ...)` 字面量 | YAML 块不执行 shell | 删掉，用静态文案 |
| Release 附件名变 `-_xxx.exe` | GitHub 转义非 ASCII | CI 侧用 ASCII 名 |
| Release 建到错误的 tag | 用了拼出来的 tag 名 | 用 `github.ref_name` |
| 手写 body 被吞 | `generate_release_notes: true` 与 `body` 冲突 | 关掉自动生成 |

### 8.4 首次配置 GitHub Actions

需要 `workflow` scope 才能推 `.github/workflows/*`：

```bash
gh auth refresh -h github.com -s workflow
# 或网页端直接新建文件（不走本地 token，最稳）
```

---

## 九、已知限制

| 项 | 状态 |
|---|---|
| 缓存命中后耗时 | 2026-11-13 全量实测（1.2.4，墙钟 9m58s）：PC 519s / 插件 292s / Docker 214s / TV 100s / fpk 93+97s / Web 45s / 建 draft 18s。2026-11-09 旧值：docker 366s / tv 123s / tv_plugins 20s / pc_shell 821s / plugins 29s / fpk 4m09s |
| 单链档位 | `pc-v*` / `plugins-v*` / `tv-plugins-v*` 均已单独跑过，耗时见 §4.1 表 |
| CI 失败通知 | 无（单人使用价值不高） |
| 产物大小校验 | 无（zips 变 0 字节也算成功） |
| arm64 镜像 | 靠 QEMU（6m36s 里约 4 分钟在模拟），未在真 arm64 设备验证 |
| Windows 代码签名 | 无证书，SmartScreen 会提示"未知发布者" |
