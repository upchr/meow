# 构建与发版

**唯一操作手册是 [`deploy/README.md`](https://github.com/upchr/meow/blob/main/deploy/README.md)**；
这一页只说明它的结构、易错机制与 CI 关系。

## 一、唯一真源：`deploy/units.json`

所有构建单元、链路入口、产物清单都在 `deploy/units.json`；加一个产物 = 只改这一个文件。

它分四段：`chains` 三条链路入口（pc/tv/docker，各带 `runner` 与理由）；`plugins` 五个插件
（`apiserver`/`drpy` 为 required，其余失败不阻断；每个单元用 `chain` 声明归属：
`plugins` 链建 4 个 PC 插件，`tv-node` 归 `tv-plugins` 链）；`index` 指向
`server/build-release.ps1`（打 zip 并生成带 sha256 的 `packages.json`）；`version` 指向
`bump-version.ps1`。

**插件数组的顺序就是执行顺序**（`foreach` 按数组走）：`apiserver` 最慢，放最前。

改完跑自检（十余项，含 `docker compose config` 校验）：
`powershell -NoProfile -ExecutionPolicy Bypass -File deploy/scripts/verify.ps1`。

## 二、版本号：脚本清单里的 14 个位置

`powershell -NoProfile -ExecutionPolicy Bypass -File deploy/scripts/bump-version.ps1 -Version 1.2.0`

覆盖 14 个位置（`deploy/docker/.env` 存在时 15 个）：三个 `package.json`、`tauri.conf.json`、
桌面 `Cargo.toml` 与 `Cargo.lock`、`apiserver.manifest.json`、TV
`build.gradle`、`.env.example`、**四个 compose 的兜底**（`docker-compose.yml` /
`.build.yml` / `.with-sources.yml` / `.standalone.yml`）、fnOS 的 `fpk/manifest`。
`standalone.yml` 是站点「下载即用」那份，靠这里自动跟进；
`with-sources.yml` 曾因不在清单里残留过旧版本，现已纳入。
`ApiServer.java` 与 `meowd` 的 Cargo 文件**故意不在清单里**：前后端版本已改为运行期读
`VERSION` 文件（见 §六），往源码写死版本会让按源码 hash 的构建缓存每版失效。

**硬规则**：必须三段数字；**禁止倒退**（`versionCode` 只能增大，镜像 tag 倒退会让人以为
拉到了新包）；`versionCode = major*1000 + minor*100 + patch`（`1.1.6` → `1106`）。写盘后
会重跑 `-Check` 回读校验。

`-Check` 在三个 workflow 都会跑；`Cargo.lock` 未同步时，报错会给出 `cargo update` 的修法。

::: danger 版本线不统一
壳和 `apiserver` 插件跟 `client/package.json`；`drpy`（`0.1.0`）、`danmu-api`（`1.0.0`）、
TV 模块（形如 `18.20.4.x`）是**独立版本线**。`release.yml` 只对壳类 tag 校验“tag 后缀 ==
package.json”，`plugins-v*` / `tv-plugins-v*` 不校验；不要拿壳版本去推模块 tag。
:::

## 三、tag 与链路的对应

| tag | 构建什么 | 建 Release |
|---|---|---|
| `release-v*` | Web + TV + PC + apiserver 插件 + Docker 三镜像 + **fnOS fpk ×2** | ✅ draft |
| `tv-v*` | TV APK（不带运行时模块） | ✅ draft |
| `pc-v*` | PC 壳 + apiserver 插件 | ✅ draft |
| `plugins-v*` | PC 插件 ×4（`chain: plugins`；tv-node 归 `tv-plugins-v*`） | ✅ draft |
| `tv-plugins-v*` | TV 运行时模块 ×3 ABI | ✅ draft |
| `fpk-v*` | 另一 workflow：x86/arm 套件（补包用）；全量 `release-v*` 也调它构建，但包进主 Release | ✅ draft（仅单链） |

**产物体积（1.2.22 实测）**：`apiserver-*.zip` ≈46MB；`meow_*_x86.fpk` ≈38MB、`arm` ≈37MB。
体积比 1.2.21 之前那版小，不是漏打依赖 —— `server/host/guard-libs` 源目录 34MB（unidbg/
unicorn/keystone 等），构建期按目标 CPU 平台裁剪到 15~16MB，规则与断言在
`server/tools/guard/trim-guard-libs.sh|ps1`，裁剪后由 `jvm.GuardNativeProbe` 验证本平台
原生库可加载（见该目录 README）。**不要把裁剪改回去**：加密源在四端仍完整可用。

**`docker-v*` 单链只推镜像、不建 Release**（2026-11-12 补回）：重发镜像也可以走
`workflow_dispatch`（勾 docker）或 `build-push-docker.ps1`；dispatch 不建 Release
（`publish` 要求 push）。

**`release.yml` 的手动 dispatch** 按六条链勾选（`tv` / `pc` / `ios` / `plugins` /
`docker` / `tv_plugins`，默认全 true；全不勾会被 `plan` 直接判错）。它**不**提供
`fpk`（走 `fpk.yml` 自己的 dispatch）和「建 Release」（只允许 tag 触发）。
各子 CI 的 `version` 输入统一为「留空读 `client/package.json`」；`_ios` 传了 version
时会覆盖 Info.plist 的 `CFBundleShortVersionString`。

两条规则：**前缀后必须紧跟数字**（`tv-v[0-9]*`），`tv-verify-1.1.6` 不触发；**壳类 tag
后缀必须等于 `client/package.json` 版本**，否则 `plan` 失败（`plugins-v*` / `tv-plugins-v*`
例外）。

`publish` 的判据是“选中的链没有 failure/cancelled”，**skipped 视为通过**——只打 `tv-v*`
也会建 Release，里面只有 TV 产物。

## 四、Release 与在线更新清单

```
CI 建 draft Release → 人工 Publish → release:published
  → sync-packages 生成并上传 packages.json → 同时 force-push updates 分支 → 客户端读清单
  → index-after-publish（fpk）写 FnDepot fnpack.json + 合并写 fpk-updates 分支
```

- **updates 分支有两个文件**：`packages.json`（更新检查）和 `history.json`
  （设置 → 关于 → 版本历史，由 `deploy/scripts/gen-history.ps1` 从 `CHANGELOG.md`
  生成）。两者在同一个 orphan 提交里 force-push；`history.json` 生成失败只告警、
  不阻塞发版。
- **draft 不产生任何用户可见清单**，必须点 Publish：`sync-packages` 与 fpk 的
  `index-after-publish` 都只在 `release: published` 跑（前者跳过 `fpk-v*`）。
  四条链同构——tag → 构建 → draft → Publish → 写索引，**草稿阶段删掉不会留下索引**。
- **清单只有 `packages.json`**：`SHA256SUMS.txt` 由 `publish` 给 Release
  资产算，`index.json` 只是本地产物的别名，不在 Release 流程里。
- **资产名不能改**：客户端按常量 `packages.json` 找清单，改名等于让所有客户端看不到更新。
- **全量 Release 会复制继承的插件资产**（`release-v*`）；单链发布不复制，url 仍指向旧
  Release——**不要删旧 Release**，否则那条插件 url 失效。同一 tag 重跑幂等（合并、重传、
  回读校验）。
- 客户端**内置默认源是资产形态**：`github.com/upchr/meow/releases/latest/download/packages.json`
  —— GitHub 的「最新已发布 Release」指针，直接 302 到那条 Release 的清单。
  选它而不是 `api.github.com` 的原因：部分网络对 api 端点的 HTTPS 有干扰（实测
  TCP 通、请求被 reset），而资产地址与 GitHub 加速代理兼容（`api` 只有 `gh-proxy` 支持）。
- **两个代价，发版侧要守住**：① 它按发布时间取，补发旧版 hotfix 会让「最新」指回旧版本
  （客户端只不提示、不降级）；② latest 那条若没有 `packages.json` 就直接 404，
  不像 API 形态能跳过。所以 **`fpk-v*` 单链 Release 不要设为 Latest**（它被
  `sync-packages` 排除，没有清单）。
- 用户手填 `api.github.com/repos/upchr/meow/releases/latest` 时走**加固路径**：拉
  `/releases` 列表，过滤 draft/prerelease/模块与插件 tag（`tv-plugins|plugins|docker|fpk-v`
  开头的不要），要求有 `packages.json` 资产，再按 tag 版本号取最大（补发旧版不会降级）。
  两种形态都算「官方源」，失败时都会再试 `updates` 分支的静态清单兜底。

### 各端读什么、怎么校验

| 端 | 壳 | 模块 / 载荷 | 插件 | 读清单的路径 |
|---|---|---|---|---|
| PC（Tauri） | `meow-pc`（exe） | — | 插件源（`packages.json`/`index.json`） | JS 算候选 → Rust 单次 GET |
| TV（Android） | `meow-tv`（apk） | `tv-node-<abi>` / `danmu-payload` | — | JS 算候选 → 原生 pipe 并发竞速 |
| Web / Docker | 无自更新（更新中心隐藏该行） | — | — | — |
| fnOS | fpk（FnDepot `fnpack.json` 主 + 主仓 `fpk-updates` 镜像） | — | — | `fpkUpgrade.ts` → meowd RPC |

- **源优先级**：手填 `Prefs.updateSource` > 内置默认 > 插件源（`getPluginSource()`，仅 Tauri）。
  备用源只在主源失败后试，**不自动回落官方源**（自定义源可能是 fork/内网，静默换源会
  提示错误版本）。TV 的模块/载荷用同一份源与令牌。
- **校验**：两端原生都要求 `sha256` 非空并校验，不匹配不落盘；只接受 `http(s)`。
  清单缺 `sha256` 时直接拒绝下载。
- **令牌**：`Prefs.updateToken` 只进原生请求头（不进 URL/日志）；带 token 的请求不挂代理
  （私有仓必须直连）。

### GitHub 加速：一套候选，四处消费

候选生成只有一个真源 `client/apps/web/src/api/gitAccel.ts`（纯函数，Prefs 由调用方传入）：

- **只代理公开 GitHub**：`raw`/`gist` 主机、`api.github.com`（仅无 token 时）、
  `github.com/.../releases/.../download/...`。自建服务器与仓库网页一律直连。
- **只有 raw 地址能换 jsDelivr**（索引/清单或 <20MB）；jsDelivr 地址会先翻成 raw 再
  生成候选，否则相对 `download_url` 解析不到可加速的宿主。
- **顺序**：直连（`directFirst`）→ 内置前缀（`gh-proxy` / `ghfast` / `ghproxy.net`，
  `gh.llkk` 默认停用）→ jsDelivr；失败的前缀进 10 分钟冷却，成功的清除。
- **消费方**：① 前端拉清单/下载壳（候选列表传给原生）；② PC 插件安装器
  （设置页写 `git-proxies.json`，Rust 侧 `expand_public_candidates`）；③ TV 原生
  （JS 把候选拼成 `|` 管道，`fetchManifestAny` **并发竞速**）；④ JVM/TV 下载 spider jar
  （`Jars.setAccelProxies` / `LocalSpiderHost`）；⑤ fnOS 应用内升级（`fpkUpgrade.ts`）。
- **代理会漂移**（实测同一天 `ghproxy.net` 从 200 变超时、`gh-proxy` 正常），所以
  候选是「多个 + 冷却」而不是写死一个。



## 五、本地发版 vs CI 发版

本地入口 `deploy/scripts/release.ps1`，产物落 `release/v<版本>/`；CI 入口是打 tag，
`-Plan` 只列计划不执行。

`-Publish` 会建**无前缀** `v<版本>` tag（带前缀会触发 CI）、推 draft Release 并调
`sync-packages`；前缀在构建前就硬校验。它不代提交源码，产物与 tag 对应的源码可能不一致。

## 六、几条硬约束

- **不要主动用 CI 发版**，每次都要显式确认；本地是默认路径
- **不要改 `units.json` 以外的地方加构建单元**
- **TV keystore 永不入库**（见 [TV 接入 · 签名](./tv-integration.md#七五签名与分发-密钥备份)）
- **公开 Release 不允许出现源包文件**：`spider*.jar`、`config.sites.json`、
  `node-payload-danmu.zip` 会被 `publish` 拦下
- 打 fpk 后**必须回读包内版本串**：CI 解包比对 `manifest`、`app/bin/VERSION`、
  `app/seed/apiserver/VERSION` 与 `install_dep_apps`（manifest 与 Cargo 漂移过一次）
- **前后端版本号都是运行时的**：meowd 读 `bin/VERSION`、apiserver 读
  `seed/apiserver/VERSION`（Docker 是 `/app/VERSION`、PC 插件是插件目录下的 `VERSION`），
  `ApiServer.java` / Cargo 里都不再写死版本。这样按源码 hash 的构建缓存才能跨版本复用；
  改了这套机制必须同步四条打包路径的「VERSION == manifest version」断言
- **fpk 的 pid/log 必须落在 `$TRIM_PKGVAR`**：`meowd` 在没有 `TRIM_PKGVAR` 时（手工启动）
  把数据根退回 `--data` 的父目录；`cmd/main start` 启动前先探端口，同版本实例补写 pid
  文件收养、旧版本先停再起。这两层是「stop 杀不掉旧进程 → 新实例 bind 失败 →
  应用中心启动一下又停用」的兜底，别简化回去（2026-11-16 OECT 实测）
- **dex2jar 只带 13 个 jar**（Dex2jarCmd 可达集）：上游把 ANTLR 工具链 + icu4j 一起发，
  整包 19.7MB → 裁后 1.5MB。白名单在 `deploy/scripts/fetch-dex2jar.{sh,ps1}`，另需同步
  `deploy/docker/Dockerfile.apiserver` 与 `build-fpk.sh` 的 jar 数断言；`ci.yml` 有转换冒烟
- **FnDepot 只保留最近 5 版**（`keep` 输入，2026-11-18 由 3 上调）：客户端只取"大于当前的
  最大值"，中间版本无功能作用；留的余量是回退兜底。**2026-11-27 起 meow 包体不再进
  FnDepot**：`download_url` 写主仓 Release 绝对直链
  （`https://github.com/upchr/meow/releases/download/release-v<版本>/meow_<版本>_<架构>.fpk`），
  仓库只存索引与图标，体积恒定 KB 级。官方 V2 协议允许绝对 URL；代价是 gitee 镜像只能
  镜像索引，包体仍走 GitHub。`toolsplus` 不受此限（包体小，仍在仓库里）。

## 七、相关

- 完整命令与排障：[deploy/README.md](https://github.com/upchr/meow/blob/main/deploy/README.md)
- 版本号来源：[版本兼容矩阵](/reference/compatibility)；签名：[TV 接入](./tv-integration.md)
