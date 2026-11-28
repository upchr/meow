# 应用计划

这一页说明各个端与能力的**当前状态**和**接下来要做什么**。

::: warning 计划不是承诺
排序看两件事：公开 issue 里的实际反馈量，以及我们有没有设备能实测。
没有官方验证条件的能力不会进发布计划：iOS 因此停在实验分支（只有自签环境能验），macOS 则直接不做。
:::

## 状态怎么读

| 标记 | 含义 |
|---|---|
| ✅ 已发布 | 官方包 / 官方镜像里就有，文档按它写 |
| 🧪 实验 | 有可跑的产物，但在分支或自签通道里，未进主线、未发官方资产 |
| 🚧 处理中 | 已有公开 issue，正在改 |
| 📋 计划中 | 方向定了，还没排期 |
| ⛔ 暂不做 | 明确不做，原因写在下文或 [已知限制](/reference/limits) |

## 端

| 端 | 状态 | 说明 |
|---|---|---|
| Windows 10 / 11 | ✅ | NSIS 安装版 / 免安装版 |
| Android TV / 盒子 / 投影仪 | ✅ | APK，Android 8.0+ |
| Android 手机 / 平板 | ✅ | 同一个 APK，竖屏布局 |
| 飞牛 fnOS | ✅ | 应用中心套件 |
| NAS / 服务器 / Mac | ✅ | Docker 镜像 + 浏览器 |
| iPhone / iPad | ✅ | 未签名 IPA（自签安装）；也可 Safari |
| 电视自带系统（Tizen / webOS） | ⛔ | 不提供，见 [已知限制](/reference/limits) |

## iPhone / iPad：IPA 已发布（未签名）

**现在有两种用法**：

- **IPA**（推荐）：随 1.2.22 发布 `Meow-unsigned-1.2.22.ipa`，端内自带 JVM 后端与弹幕聚合，
  不连外部后端也能导源、搜索、播放。装法见 [iPhone / iPad](/guide/ios)。
- **Safari**：不装 App，Safari 打开后端地址 →「分享 → 添加到主屏幕」。

边界先讲清楚，免得期待错位：

| 事项 | 结论 |
|---|---|
| 签名 | 官方不发签名包：用 Apple ID 自签，免费账号 **7 天**、付费开发者 1 年；不上架 App Store |
| 端内跑源 | class 与 dex 都能导入（dex 由端内 dex2jar 转换）；带 `.so` 的 Guard 源加载不了 |
| 转码 | ⛔ 端内没有 ffmpeg，片库转码不可用（MKV 已由端内 VLCKit 直解，不再依赖它） |
| 格式 | MKV / HEVC 端内直解；FLV / 个别编码仍播不了 |
| 本地片库 | 没有系统目录可选，用 WebDAV / SMB |
| 更新 | 不进 `packages.json`：应用内检查更新弹框给 IPA 直链与侧载商店源，安装仍要重签 |

**接下来**：把端内 JVM 的性能与兼容收口（解释器比 JIT 慢，搜索扇出多时明显），
并补齐真机矩阵验证。相关条目见下方「接下来（按主题）」表。

## 接下来（按主题）

| 主题 | 状态 | 进度 / 讨论 |
|---|---|---|
| 片库画质：4K HDR 与 10-bit 不再一律转码 | 🚧 | [#12](https://github.com/upchr/meow/issues/12) |
| 起播与线路：坏线路秒切、按成功率排序 | 🚧 | [#16](https://github.com/upchr/meow/issues/16) |
| 转码资源回收：退出播放后不再占 CPU | 🚧 | [#15](https://github.com/upchr/meow/issues/15) |
| 源兼容：drpy 站点、jar md5 不匹配、加密 jar 预判 | 🚧 | [#13](https://github.com/upchr/meow/issues/13)、[#14](https://github.com/upchr/meow/issues/14)、[#10](https://github.com/upchr/meow/issues/10) |
| 片库直链（`.strm` / 302 签名 URL）与盘搜定位 | 📋 | [#6](https://github.com/upchr/meow/issues/6) |
| 片库添加门槛：目录点选与失败归因 | 📋 | [#5](https://github.com/upchr/meow/issues/5) |
| TV / 手机外网访问与多用户登录 | 🧪 | [#4](https://github.com/upchr/meow/issues/4)、[公网部署与访问控制](/dev/public-deployment) |
| Jellyfin 片库接入 | 📋 | [#8](https://github.com/upchr/meow/issues/8)（已回复确认要做） |
| 飞牛网关统一身份 | 🧪 | [设计](/dev/fnos-gateway-auth) |

## 计划怎么定

1. **同一个问题被反复提**，优先级往前——issue 里 +1 比私信有用。
2. **能实测才排期**：没有设备验证的能力，不写进发布计划。
3. **不做的写清原因**：见 [已知限制](/reference/limits)（电视自带系统、商业授权等）。
4. **不定日期**：只给状态，不给「几月几号」。给了日期做不到，比不说更糟。

## 想要某个功能

- 先搜 [已有 issue](https://github.com/upchr/meow/issues?q=is%3Aissue)，避免重复。
- 没有就按 [反馈与贡献 · 提 Issue](/reference/contributing#提-issue) 选模板提：
  写清要解决什么问题、现在怎么绕，比只写「加个 XX 功能」有用得多。
- 源站失效不属于客户端计划：那是上游，换源或等上游恢复。
