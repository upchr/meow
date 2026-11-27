# 下载与版本

所有安装包都在 GitHub Releases。**文件名里的版本号随发布变化**（下文的 `<版本>` 就是它），
以 Releases 页实际资产为准。

## 下哪个

| 你要装在哪 | 下这个文件 | 怎么装 |
|---|---|---|
| Windows 电脑 | `meow-setup-<版本>.exe`（安装版）<br>`meow-bare-<版本>.exe`（免安装） | [PC](/guide/pc) |
| 安卓电视 / 投影仪 / 手机 | `meow-TV-<版本>.apk` | [TV](/guide/tv) |
| 飞牛 fnOS | `meow_<版本>_x86.fpk` / `meow_<版本>_arm.fpk` | [飞牛](/guide/fnos) |
| iPhone / iPad | `Meow-unsigned.ipa`（固定名，始终最新；未签名，要自己签） | [iPhone / iPad](/guide/ios) |
| NAS / 服务器 / Mac | **不用下载**：Docker 起后端，浏览器打开 | [Docker](/guide/web-docker) |

- 不确定该走哪条路：先看 [快速开始 · 路线选择](/guide/quick-start#路线选择)。
- 已经装过、只是想升级：看 [升级与迁移](/guide/upgrade)，不用重新下。
- iPhone / iPad 的 IPA **没有签名**：要用自己的 Apple ID 自签一次（7 天有效），步骤见 [iPhone / iPad](/guide/ios)；不想签名就用 Safari。

## 去哪里下

- **最新发布**（一般点这个）：<https://github.com/upchr/meow/releases/latest>
- 全部版本与历史资产：<https://github.com/upchr/meow/releases>

打不开 GitHub 时：飞牛可以改走 [FnDepot 源](/guide/fnos)（Gitee 镜像只同步到旧版，
新版本以 GitHub 源为准），Docker 直接拉镜像；Windows、TV 与 iOS 目前只有 GitHub 一个下载点，换网络或代理再试。

## 资产清单

| 文件 | 平台 | 说明 |
|---|---|---|
| `meow-setup-<版本>.exe` | Windows | PC 安装版（NSIS），带开始菜单与卸载 |
| `meow-bare-<版本>.exe` | Windows | PC 免安装版，解压即用 |
| `meow-TV-<版本>.apk` | Android TV / 投影仪 / 安卓手机 | TV 壳，release 签名 |
| `Meow-unsigned.ipa`（固定名）/ `Meow-unsigned-*.ipa`（版本化） | iPhone / iPad（iOS 15+） | 未签名，需 Apple ID 自签侧载；免费账号 7 天有效 |
| `meow_<版本>_x86.fpk` | 飞牛 fnOS x86_64 | 原生套件 |
| `meow_<版本>_arm.fpk` | 飞牛 fnOS arm64 | 原生套件 |
| `tv-node-arm64-v8a-<node版本>.apk` | arm64-v8a | TV 运行时模块 |
| `tv-node-armeabi-v7a-<node版本>.apk` | armeabi-v7a | TV 运行时模块（老设备） |
| `tv-node-x86_64-<node版本>.apk` | x86_64 | TV 运行时模块（模拟器 / x86 盒子） |
| `packages.json` | — | 客户端「检查更新」读的清单，**不用手动下** |
| `SHA256SUMS.txt` | — | 所有资产的校验和 |

运行时模块不是壳：只有要在电视上跑 Node / 弹幕插件时才装，
装错架构会在安装阶段被拒。见 [TV 运行时模块](/ecosystem/runtime-module)。

插件包（PC 用；应用内「本机插件」会自己装，**只有离线安装才手动下**）：

| 文件 | 说明 |
|---|---|
| `apiserver-<版本>.zip` | 后端插件；也可在「本机插件」里手动导入 |
| `drpy-<版本>.zip` | JS 源运行时 |
| `node-<版本>.zip` | Node 运行时；装弹幕聚合前先装它 |
| `danmu-api-<版本>.zip` | 弹幕聚合 |
| `ffmpeg-<版本>.zip` | 转码（片库 / 播放转码才需要） |

::: warning 公开 Release 不含源包
没有 spider jar、没有站点配置、没有弹幕载荷（TV 运行时模块除外，它是执行环境）。
**Docker 镜像同样不含 spider jar**。装完是空壳，需要自己导入源包，见 [导入源包](/guide/source-pack)。
:::

## Docker 镜像

Web 形态不下载安装包，直接拉镜像：

| 镜像 | 内容 |
|---|---|
| `chrplus/meow-web` | nginx + 静态前端 |
| `chrplus/meow-apiserver` | JVM + ffmpeg（**不含 spider jar**，需在应用内导入源包） |
| `chrplus/meow-drpy` | JS 源运行时（**独立版本线**，不跟应用版本） |
| `logvar/danmu-api` | 弹幕聚合（上游镜像，独立版本线） |
| `chrplus/meow-sources` | 源服务：内置默认源集（**独立版本线**），见 [源包与配置格式](/ecosystem/source-format) |

- 默认拉 `latest`，跟随最新发布；要固定版本就在 `.env` 里把 `MEOW_VERSION` 设成具体版本。
  `chrplus/meow-web` / `meow-apiserver` / `meow-drpy` 都含 amd64 + arm64，Docker 按本机架构自动选。
- **升级要 `docker compose pull`**：`up -d` 看到本地已有同名镜像就不会重新拉，版本会一直停在旧的。
- `logvar/danmu-api` 与 `chrplus/meow-sources` 是独立版本线，不受 `MEOW_VERSION` 影响。

## 校验

下载 `SHA256SUMS.txt`，与安装包放同一目录，然后：

```bash
sha256sum -c SHA256SUMS.txt
```

Windows PowerShell 单个文件：

```powershell
Get-FileHash .\meow-setup-<版本>.exe -Algorithm SHA256
```

值与 `SHA256SUMS.txt` 中对应行一致即可。

## 版本号怎么读

- 安装包名里的是**应用版本**（壳 + 前端）。插件、源包格式各有自己的版本线，
  **数字对不上是正常的**，完整对照见 [版本兼容矩阵](/reference/compatibility#三个版本号)。
- 文档站显示的 <VersionBadge /> 是**源码版本**：站点按源码构建，可能领先于最近一次发布。
- 客户端「检查更新」读的是 `packages.json`，以它为准，不看文档站。

## 系统要求

| 端 | 要求 |
|---|---|
| Web | Docker 24+ 与 Compose 插件；2 GB 可用内存（转码另算，机器弱时 4K 会卡） |
| PC | Windows 10 1809 及以上 |
| TV | Android 8.0（API 26）及以上；壳与运行时模块的 minSdk 相同 |
| iOS | iOS 15 及以上；未签名 IPA，用 Apple ID 自签安装 |
| fnOS | 飞牛系统；x86_64 或 arm64 |

## 历史版本

Releases 页可以展开旧版本。应用内也能看：设置 →「维护」→「关于」→ 版本历史
（数据源为 updates 分支的 `history.json`）。
跨大版本升级前建议先备份数据目录：

- Web：`MEOW_DATA` 挂载目录
- PC：`%APPDATA%\com.meow.player\data`
- fnOS：应用数据目录

## 相关

- 每版改了什么：[更新日志](/reference/changelog)
- 我的设备支持哪些能力：[版本兼容矩阵](/reference/compatibility)
- 装不上、起不来：[诊断手册](/reference/diagnostics)
- 装完下一步：[导入源包](/guide/source-pack)
