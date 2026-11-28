# 端、设备与尺寸适配

> 这一篇是**适配判据的单一清单**：应用在哪些端/设备/尺寸下有差异、靠什么识别、
> 差异点在哪、哪些写法已经混乱到必须收口。
>
> 状态：2026-11-17 首次审计；**同日完成 A~E 整改与 D-1~D-5 修复**（见 §6），
> 判据已收口到 `compat/`。审计对象是 `client/apps/web/src`（Web/PC/TV/fnOS
> 共用同一份前端）+ 两个壳的注入层（`client/apps/tv` 的 `tv-nav.js`、
> `client/apps/desktop` 的 `tauriInvoke`）。
>
> **改任何"按端/按尺寸显示不同"的代码前先读第 5 节规范**；第 4 节是整改前的
> 混乱点记录（保留作背景），**不要按它再新增判据**。

## 0. 一句话现状

**整改前**：11 套身份判据（A1~A11，其中三组重名/重复实现）× 4 类输入判据 ×
22 条尺寸/方向判据 × 9 个 `html[data-*]` 全局开关 × 38 个注入桥交叉判断，
同一台设备在不同功能里会被当成不同的端。

**整改后（2026-11-17）**：业务代码只读四维档案，判据实现在 `compat/`：

| 维度 | 唯一出口 | 取值 |
|---|---|---|
| 壳身份 | `html[data-shell]` / `compat/shell.ts` | `web` / `tauri` / `tv` / `fpk` |
| 形态 | `html[data-form]` / `compat/deviceProfile.ts` | `phone` / `tablet` / `desktop` / `tv-large` |
| 输入 | `html[data-input]` / `compat/inputModality.ts` | `touch` / `key` |
| 方向 | `html[data-orient]` / `compat/deviceProfile.ts` | `portrait` / `landscape` |

能力（音量/播放器/更新通道）仍按桥方法探测，见 §1.4。`tests/device-profile.test.ts`
把「业务代码不得再读 UA / 裸视口 / 指针媒体查询」变成机器门禁。

统计口径（`src/` 内，整改前记录保留作对照）：

```bash
# 整改前：TV 身份相关散判
grep -rn "isTvEnv\|data-tv\|MeowTV\|meowTv\." --include=*.ts --include=*.vue . | wc -l   # 211
# 断点使用（模板里"手机档"是绝对主流）
grep -ro "max-sm:" --include=*.vue --include=*.ts . | wc -l   # 224（sm: 302 / md: 74 / lg: 20 / wide: 21）
# 整改后：散判信号只在 compat/
cd client/apps/web && npx vitest run tests/device-profile.test.ts
```

## 1. 判据全景

### 1.1 身份：你在哪个壳里

**整改后只剩一个身份出口**（`compat/shell.ts`），业务代码通过 `data-shell` 或
`isTvShell()/isDesktopShell()` 读取：

| 出口 | 判据 | 定义位置 | 谁在用 |
|---|---|---|---|
| `data-shell='tv'` | `window.meowTv` 桥存在，或 UA `MeowTV`（仅首屏兜底） | `compat/shell.ts` `isTvShell()`；写入在 `compat/deviceProfile.ts` | 菜单键、返回键、设置页端分区、更新中心 fpk 排除 |
| `hasTvModule()` | `meowTv.moduleCheck` 是函数（**能力，不是身份**） | `compat/shell.ts` | 更新中心「Node / Python 运行时 / 弹幕组件」行、设置页运行时分区 |
| `data-shell='ios'` | `window.meowIos` 桥存在（**只看桥，不看 UA**） | `compat/shell.ts` `isIosShell()`；写入在 `compat/deviceProfile.ts` | 设置页「后端地址」改成只读（iOS 的后端是 App 进程内的 JVM 网关，改地址 = 把 App 指向别人的后端） |
| `data-shell='tauri'` | `__TAURI_INTERNALS__` / `__TAURI__` | `compat/shell.ts` `isDesktopShell()`（`api/index.ts` 的 `isDesktop()` 只是别名） | base 解析、插件管理、桌面行为 |
| `currentPlatform()` | 桌面 → `meowTv.appUpdaterPlatform()` → web（**会话内缓存**） | `api/appUpdate.ts` | 更新包匹配（windows-x64 / android-arm64 / x86_64 / armv7 / web） |
| `capability` | `shell`（同步）/ `fpk`（异步探测 `/api/_shell/*` + `/app-center/*`）/ `none` | `stores/appUpdate.ts` `probeCapability()`（**单点**） | 更新通道、弹窗、FpkUpgradePanel |
| `data-form` | 形态（见 §1.3，**只影响布局密度**） | `compat/deviceProfile.ts` `formOf()` | 列数、双栏/胶囊、返回键形态判断 |

**兼容/别名**：

- `html[data-tv='1']`（A1/A2）保留给 CSS 与调试（`?tv=1`），**不再做身份判据**；
  JS 侧 `isTvEnv()` 只读它，由 `compat/shell.ts` 统一导出。
- `platformName()`（原 A10）只剩「设备名/日志」用途，**不再参与端分区**。
- 原 A3/A4 同名两版合并为 `isTvShell()`（身份）+ `hasTvModule()`（能力）；
  原 A5/A6/A11 合并为 `isDesktopShell()`；原 A9 收口为 `resolveForm()` 的
  `tv-large` 形态。整改前的对照表见 §4。

### 1.2 输入方式

| ID | 判据 | 位置 | 用途 |
|---|---|---|---|
| B1 | `html[data-input='touch'\|'key']`（横屏默认 `key`；竖屏**主指针 coarse** 默认 `touch`；按键/触摸事件覆盖） | `compat/inputModality.ts` | 焦点环 `meow-tv-focus` 显隐 |
| B2 | `(pointer: coarse)` / `(pointer: fine)` / `(any-pointer: fine)` | `compat/inputModality.ts` / `compat/shell.ts` `hasFinePointer()` / `compat/deviceProfile.ts` `hasAnyFinePointer()` | 手势是否可用、光标隐藏、形态判定 |
| B4 | `'ontouchstart' in window` | VideoPlayer `isTouch`（仅作为**设备兜底**） | 双击/长按的默认分流；**鼠标事件优先按 `pointerType`** |

原 B3 `navigator.maxTouchPoints` **已删除**：桌面 Chromium 无触摸也报 **10**，
用它判触屏会让 PC 竖窗口的焦点环永久失效（D-1，2026-11-17 实测）。

历史教训：`(hover: none)` **不能**当输入方式判据（模拟器/带鼠标的盒子会翻转，
曾出现"同一点法有时弹框有时直接删"）——已从 History/Favorites 移除，不要再加回来。

### 1.3 尺寸与方向

| 阈值 | 用途 | 位置 |
|---|---|---|
| 短边 **480** | 横屏形态分界（<480 tablet / ≥480 且无任何精确指针 → tv-large） | `compat/deviceProfile.ts` `resolveForm()` |
| **640**（`sm`） | 手机 / 非手机分界（`max-sm:` 224 处、`sm:` 302 处）；也决定 `phone` 形态 | Tailwind + `MobileTabBar` 的 `@media (max-width:639px)` |
| **768**（`md`） | 顶栏导航档位；搜索页源栏/投屏面板走 `isWideLayout()`（由 `data-form` 推导，读不到时退回 768） | Tailwind；`compat/shell.ts` `isWideLayout()` |
| **1000** | 卡片目标宽 104 / 112 分档 | `useCardCols.ts` |
| **1024** | 触屏小屏隐藏底栏重播/刷新 + 顶栏导航文字图标化（**同一判据**，原 899/1023 已删除） | `tokens.css`（`max-width: 1024px` + `pointer: coarse`） |
| **1280**（`xl`） | 顶栏同步徽标文字 | `AppHeader.vue` |
| **520** | 海报行距收紧 / `compact`（CSS `max-height` 与 JS `isShortViewport()` **同源**，原 560 已删除） | `tokens.css` / `compat/deviceProfile.ts` |
| **600**（`max-height`，投影仪/短屏压缩档） | TV 顶栏 4.5→3.5rem、分类条/hero 收紧、隐藏年份行与 hero 侧栏 | `tokens.css`（grep `max-height: 600px`） |
| **≥1024** 或 **(`pointer: coarse` + 横屏)** | 自定义变体 `wide`（设置页双栏）；960–1023 无鼠标电视 → 单列胶囊 | `tokens.css` `@custom-variant` |
| 卡宽 76 / 104 / 112 + 矮屏加列 | 海报列数算法（76 仍只在 TV 视口 ≥640 时生效） | `resolveCardCols()` |
| `prefs.homeCols` | 列数上限（用户设置） | 设置页「外观」 |
| `--header-h` | 默认 4rem / TV 4.5rem / ≤600px TV 3.5rem / 手机 `3.5rem + safe-area` | `tokens.css` |
| 方向 | **唯一出口 `html[data-orient]`**（`compat/deviceProfile.ts`）；CSS 的 `wide` 是样式变体，不是 JS 判据 | `compat/deviceProfile.ts` / `tokens.css` |
| `visualViewport` 键盘 | 布局视口高 − visualViewport 高 **> 120** → 键盘弹起（隐藏底部 tabbar） | `MobileTabBar.vue` |
| **359**（超窄屏） | 设置页 `.set-grid` 变单列（Galaxy Fold 外屏 / iPhone SE 一类） | `tokens.css` |
| **430**（`max-width`） | 详情页片名收字号（1.375rem） | `tokens.css`（grep `max-width: 430px`） |
| TV 字号阶梯 **1280 / 1920 / 2400** | `html[data-tv]` 根字号 16 → 18 → 20 → **24px**（所有 rem 一起放大） | `tokens.css`（`tests/tv-font-scale.test.ts` 锁住） |

**`prefs` 里与端相关的字段**（用户设置层，不是判据）：

| 字段 | 端相关性 | 说明 |
|---|---|---|
| `homeCols` | 全端 | 海报列数**上限**（默认 6），实际列数由卡宽算法算 |
| `closeToTray` | PC 专属 | 关闭窗口收托盘（Web/TV 无此语义） |
| `autoCheckApp` / `autoCheckModule` / `autoCheckPlugins` / `autoCheckFpk` | 按 `capability` 生效 | 四条自动检查分别对应 shell / TV 模块 / PC 插件 / fnOS |
| `searchLayout` / `videoFit` / `danmaku*` | 无 | 纯偏好，不做端差异 |

### 1.4 能力探测（**这是唯一推荐做法**）

判断"能不能用"只探能力，不猜身份：

| 能力 | 探测方式 | 位置 |
|---|---|---|
| 系统音量/亮度 | `meowTv.getVolume/setVolume/getBrightness/setBrightness` 或 `meowIos.setVolume` 存在 | `api/nativeBridge.ts` |
| 原生播放器兜底 | `meowTv.nativePlayerAvailable()` | `nativeBridge.ts` / `VideoPlayer.vue` |
| 原生输入框（遥控器打字） | `meowTv.inputText` 存在 | `nativeBridge.ts` |
| 原生 HLS | `video.canPlayType('application/vnd.apple.mpegurl')` | `player/mediaKind.ts` `hasNativeHls()` |
| 画中画 | `document.pictureInPictureEnabled` | VideoPlayer |
| 系统休眠 | `hasSystemSleep()` | nativeBridge |
| 更新安装 | `capability`：`shell` / `fpk` / `none` | `stores/appUpdate.ts`（单点；`FpkUpgradePanel` 仍绕过，见 D-3） |
| iOS 壳能力 | `window.meowIos` 桥（`version` / `apiBase` / `runtime` 只读字段） | `compat/shell.ts` `iosShellInfo()`；注入在 `client/apps/ios/Meow/Bootstrap.swift` |

### 1.5 全局样式开关（`html[data-*]`）

| 属性 | 含义 | 设置者 | 影响面 |
|---|---|---|---|
| `data-tv='1'` | TV 壳身份 | TV 壳 / `?tv=1` | 61 处选择器：字号档、`--header-h`、`tv-hide`… |
| `data-input='touch'\|'key'` | 当前输入方式 | `compat/inputModality.ts` | 焦点环 |
| `data-legacy-css='1'` | 老内核 CSS 降级 | `styles/legacyCss.ts`（**实测探针**：flex gap / aspect-ratio / padding-inline / color-mix） | `legacy-generated.css` **255 条规则** |
| `data-style` / `data-mode` | 主题风格 / 明暗 | `stores/theme.ts` | 全站配色 |
| `data-wallpaper` / `data-wp` | 壁纸 | 壁纸设置 | 背景 |
| `data-tv-ime` | TV 输入法提示 | 壳 | TV 输入 |
| `data-no-color-mix` | 探针结果（不支持 color-mix） | legacyCss | 主题色降级 |

调试入口：`?tv=1`（强制 TV 布局）、`?legacycss=0|1`（强制关闭/开启降级）。

**其它环境判据（不属"端"，但同样改变显示）**：

| 判据 | 影响 | 位置 |
|---|---|---|
| `prefers-color-scheme` | 首次进入时的明暗主题（用户手动切过则以 prefs 为准） | `index.html` 首帧脚本 / `stores/theme.ts` |
| 老内核 API 补丁 | Chromium 66（Android 9 电视/投影）缺 `Promise.allSettled` / `flatMap` / `fromEntries` / `matchAll` / `.at` / `hasOwn`，`main.ts` **第一行**引入补丁；不能挪位置 | `compat/legacyPolyfills.ts` |
| `visualViewport` | 键盘弹起（差值 >120）时隐藏底部 tabbar | `MobileTabBar.vue` |

## 2. 端 × 功能矩阵（当前实际行为）

| 功能 | Web/PC 浏览器 | 手机竖屏 | 手机横屏 | TV 壳 | PC 壳（Tauri） | fnOS（fpk） |
|---|---|---|---|---|---|---|
| 顶栏导航文字 | **≤1024 图标化**，>1024 显示 | 多数不渲染（<640） | **图标化**（≤1024） | **≤1024 图标化，>1024 显示** | 同 Web | 同 Web |
| 底部 tabbar | `max-width:639px` 时显示 | 显示 | 不显示 | TV 竖屏（MuMu 360）会显示 | 不显示 | 不显示 |
| 设置页布局 | ≥1024 双栏，<1024 胶囊 | 竖屏胶囊 | **双栏**（coarse+横屏） | **≥1024 双栏，≤1023 单列胶囊** | 同 Web | 同 Web |
| 海报列数 | 卡宽算法 + `homeCols` | 3 列 | **6 列**（矮屏加列） | **`form=tv-large` → 目标卡宽 76；TV 插鼠标（form=desktop）按 104/112** | 同 Web | 同 Web |
| 播放页返回键 | 显示 | 显示 | 显示 | **大屏横屏隐藏**（A9） | 显示 | 显示 |
| 焦点环 | 横屏 `key` / 竖屏触屏 `touch` / **PC 竖窗 `key`** | touch | key | `data-input` | key | 同 Web |
| 直播页 | 视口方向引导 | 同 | 同 | 方向键归属 `__meowTvKeyOwner` + 锁横屏 + 大屏隐藏引导 | 同 | 同 |
| 双击画面 | 全屏 | 分左/中/右区 | 分区 | 分区 | 全屏 | 全屏 |
| 竖滑亮度/音量 | 无（鼠标） | 有 | 有 | 有（**系统桥**真调） | 无 | 无 |
| 系统音量/亮度 | — | **iPhone 真机桥（MPVolumeView）** | 同 | 真机桥 | — | — |
| 原生播放器兜底 | 无 | 无 | 无 | **ExoPlayer** | 无 | 无 |
| 投屏 | 有（同源 base） | 有 | 有 | 有（LAN 监听） | 有（LAN 监听） | 有（同源） |
| 在线更新 | 无安装包 | 无 | 无 | APK 清单（A3/A4/A7） | setup/插件（A5/A7） | **fpk 应用中心**（A8） |
| 托盘 / 关闭行为 | — | — | — | — | **有** | — |
| 焦点环样式 | `meow-tv-focus` | 默认不画 | 画 | 画 | 画 | 画 |

**iOS 现状**：没有壳（`client/apps` 只有 desktop/fnos/tv/web），iPhone 走 Safari / 主屏 PWA，
上面的「手机竖/横屏」两列覆盖它；IPA 壳的形态与能力契约见
[ios-integration](./ios-integration.md)（未实现）。

## 3. 混合态：为什么"划分混乱"（四个真实案例）

> 以下描述**整改前**的表现；2026-11-17 后 1~3 已由四维档案收口（§5），
> 案例 4 的能力探测是正确示范，保持不动。

**案例 1：MuMu 模拟器竖屏（360×640）**
`data-tv='1'` → 当时被当成 TV 身份；`max-sm` 命中 → 手机竖屏布局；
`html[data-tv]` 字号档 → 电视字号；`maxTouchPoints>0` → 输入 touch。
结果是「TV 身份 + 手机布局 + 电视字号」，卡宽算法还要额外补丁。
**整改后**：身份 `tv`、形态 `phone`、输入 `touch` 各自独立；卡宽按
`form='tv-large'` 才给 TV 目标宽（竖屏 360 → 手机卡宽），补丁条件保留但来源统一。

**案例 2：iPad 竖屏（实机 Air 4 是 820×1180）**
搜索页按 `innerWidth>=768` 给左右分栏；设置页按 `orientation` 给胶囊；
顶栏按 `<900` 给文字；卡片按卡宽算列。**同一页面四个功能四种答案。**
**整改后**：统一读 `form='tablet'` + `orient='portrait'`，搜索页用
`isWideLayout()`，顶栏阈值与 `.touch-hide` 统一为 1024。

**案例 3：手机横屏（852×393）**
`max-sm` 不命中（按桌面样式渲染），但 `pointer:coarse`/`isTouch` 为真（触摸交互）；
控制栏 `1023` 收按钮、顶栏 `899` 收文字 —— 两个阈值不同。
**整改后**：两个类共用 `(pointer: coarse) and (max-width: 1024px)`，且 852 的
`form='tablet'` 与交互（触摸）由不同维度回答。

**案例 4：iPhone Safari / PWA**
无 `requestFullscreen`（平台限制）→ 走伪全屏兜底；`video.volume` 赋值被系统忽略
→ 音量手势改提示侧键；原生 HLS 可用 → 不需要 hls.js。
判据全部是**能力探测**（B 类 + `hasNativeHls`），这部分是正确示范。

> **既有决策（不要回退）**：全屏**不要**改调 `video.webkitEnterFullscreen()`。
> 它会把播放交给 iOS 原生播放器，DOM 全被盖住 —— 弹幕层和自定义控制栏一起消失，
> 等于换了个播放器（见 `VideoPlayer.vue` 伪全屏函数上方的注释）。
> iOS 上正确做法是「容器撑满视口」的伪全屏：弹幕与控件留在原地，代价只是
> 地址栏不隐藏。若要做原生播放器全屏，只能作为「更多」里的**可选入口**。

### 3.5 端专属控制清单（组件 / 事件 / 桥）

这些是"额外控制"的另一半：不在样式里，而是在事件与桥上。改动前先确认所属端。

| 控制 | 形态 | 端 | 位置 |
|---|---|---|---|
| 返回键接管 | `window.__meowTvConsumeBack`（链式，本页先消费，否则交上一任） | TV 壳 | `App.vue`、PlayView、ExternalPlayView、UpdateDialog |
| 方向键归属 | `window.__meowTvKeyOwner`（页面声明"方向键归我"，如播放页进度条） | TV 壳 | VideoPlayer / LiveView |
| 焦点搬运 | `window.__meowTvFocus(el, pin)`（`tv-nav.js` 注入） | TV 壳 | 各面板 |
| 菜单键 | `registerTvMenuHandler()`（详情/历史/设置页各自的菜单动作） | TV 壳 | `stores/tvMenu.ts` |
| 原生播放器兜底 | `meowTv.nativePlayerAvailable()` / `openNative(url,title,headers,pos)` | TV 壳 | `nativeBridge.ts` |
| 原生输入框 | `meowTv.inputText(title, initial, mask, theme)` | TV 壳 | 搜索/设置输入 |
| 主题推给原生页 | `meowTv.setUiTheme(json)`（TV 配置页没有 WebView） | TV 壳 | `nativeBridge.ts` |
| 系统音量/亮度 | TV：`get/setVolume`、`get/setBrightness`；iOS：`meowIos.setVolume`（MPVolumeView） | TV / iOS 壳 | nativeBridge |
| 伪全屏事件 | `window` 自定义事件 `meow:pseudofs`（detail=boolean） | 全端（iOS 触发） | VideoPlayer → App.vue |
| 托盘/关闭行为 | `tauriInvoke` 命令（`desktop_*`）；关窗提示由 Rust `window.__meowTrayHint?.()` 触发 `TrayHintDialog` | PC 壳 | `api/index.ts`、`App.vue`、`TrayHintDialog.vue` |
| 目录选择 | PC：Tauri dialog；TV：`MANAGE_EXTERNAL_STORAGE` + 内置浏览器；Web：目录选择器 | 三端不同 | `components/library/LibraryAddDialog.vue` / `api/library.ts` |
| 局域网后端扫描 | `scanLanBackends()`（HTTP 探测 30010/9979） | 全端同一实现 | `utils/lanScanWeb.ts` |
| 键盘弹起 | `visualViewport` 差值 >120 → 隐藏 tabbar | 移动端 | `MobileTabBar.vue` |
| 投屏接收页 | `/ext-play`（`mutedFallback` 静音起播兜底） | 全端（接收端） | `views/ExternalPlayView.vue` |
| 老内核降级 | `data-legacy-css='1'`（实测探针） | 老 Android WebView | `styles/legacyCss.ts` |
| TV 专用弹层 | `TvActionSheet` / `TileSelect` / `TilePanel` | TV 壳 | 设置/片库/壁纸 |
| TV 字号档 | `html[data-tv]` 下顶栏 17px / 次要 15px / 图标 22px | TV 壳 | `tokens.css` |
| 设置页端专属分区 | `desktopOnly`：本机插件 / 桌面端行为；`tvOnly`：本机执行 / 运行时模块 | PC / TV 壳 | `SettingsView.vue` `NAV_ITEMS` + `tvMode` |
| 远程调试 | TV：DebugServer 开关 + 令牌（每次重开更换）；PC：只读展示；Web：不显示 | TV / PC / Web | `SettingsView.vue`（维护分区） |
| 页面缩放锁 1 | TV 壳把 viewport `min/max-scale` 写 1（旋转后保持 100%） | TV 壳 | `index.html` 首帧脚本 |
| 安全区 | 顶栏 `--header-h`、`--tabbar-h`、`--tabbar-clear`、AI 面板 padding 读 `env(safe-area-inset-*)` | 手机（刘海/手势条） | `tokens.css`、`MobileTabBar.vue`、`AiPanel.vue` |
| 弹层焦点作用域 | `data-tv-scope`（方向键收窄到弹层内，全仓约 100 处） | TV 壳 | 几乎每个浮层（VideoPlayer 26 处最多） |
| 初始焦点 | `data-tv-first`（打开弹层后落点；优先级最高） | TV 壳 | AiPanel/DetailView/播放器面板 |
| 滚动停靠点 | `data-tv-scroll`（焦点跟随滚动） | TV 壳 | 长列表/设置页/日志 |
| 内缩焦点框 | `data-tv-inset-focus`（贴边按钮的焦点框内缩） | TV 壳 | `tv-nav.js` |
| MCP / 调试控制面 | PC 18 个 `pc_*`（应用内线程）；TV 17 个 `tv_*`（含 media / storage / pointer / focus 等）+ `X-Meow-Token`；Docker/fnOS 无服务端 MCP | PC / TV / 其他 | `client/apps/desktop`、`client/apps/tv` `DebugServer` |
| 投屏设备名 | TV：`meowTv.deviceName()`；PC：`isDesktop()` → `'PC'` | TV / PC | `api/library.ts` |
| 启动容错 | 后端连不上时的重试次数：`isDesktop()` → **4 次**，其他端 1 次 | PC | `stores/app.ts` |
| AI 面板开关 | `window.__meowAiOpen/__meowAiClose/__meowAiIsOpen`（壳/系统入口触发） | TV / PC | `AiPanel.vue` |
| 媒体互斥 | `window.__meowWebviewPause/__meowWebviewResume`（原生播放器抢占/交还音频） | TV 壳 | `utils/mediaExclusive.ts` |
| 屏幕方向锁定 | `meowTv.setOrientation('landscape'|'portrait'|'auto')`；网页端无此桥 → 静默失败，靠竖屏提示 | TV 壳 | `LiveView.vue`、`VideoPlayer.vue` |

### 3.6 TV 焦点体系（`tv-nav.js`，端差异最大的单块）

TV 的"显示差异"一半来自 CSS，另一半来自**注入脚本 `tv-nav.js`**（壳在页面加载后注入，
`client/apps/tv/app/src/main/assets/tv-nav.js`）。它读四个 data 属性做焦点管理：

| 属性 | 语义 | 优先级 |
|---|---|---|
| `data-tv-scope` | 把方向键收窄到这个容器内（弹层/面板） | 最近的祖先生效 |
| `data-tv-first` | 打开弹层后的初始焦点落点 | `data-tv-first` > 当前焦点 > 第一个 input > 第一个可聚焦元素 |
| `data-tv-scroll` | 滚动停靠点（焦点移动时容器跟随滚动） | — |
| `data-tv-inset-focus` | 贴边按钮的焦点框内缩（否则被 `overflow` 裁掉） | — |

按键语义（播放页）：左右 = 快退/快进（`ArrowLeft/Right`），上下 = 切换音量/静音
（`ArrowUp/Down`），OK = 播放/暂停，返回键两级（先暂停、2.5 秒内再按才退出）。
这些分支都在 `VideoPlayer.vue` 的 `isTvEnv()` 判断里 —— 改按键行为前先看这里。

**规范（5.5 的延伸）**：新增弹层时必须标 `data-tv-scope`；有明确初始焦点就标
`data-tv-first`；长列表标 `data-tv-scroll`。漏标的表现是"遥控器方向键跑到弹层外面"。

### 3.7 Native → JS 注入桥（PC CDP / TV evaluateJavascript）

页面通过 38 个 `window.__meow*` 全局函数被"外部"驱动：PC 的 MCP 走 CDP（能 await
Promise），TV 的壳走 `evaluateJavascript`（同步回调，拿不到 Promise）——所以同一功能
有**两条通道**：

| 通道 | 形态 | 谁在用 |
|---|---|---|
| Promise 版 | `__meowPolicyJson` / `__meowPolicyApply` / `__meowPrefsJson` / `__meowWebdavJson` / `__meowUpdateJson` … | PC（CDP awaitPromise） |
| 投任务 + 轮询 | `__meowPolicyStart` / `__meowPolicyReadStart` / `__meowPolicyPoll` / `__meowToolStart` / `__meowActionStart` | TV（`MainActivity.pushPrefsHost`） |

分组（改任何一组前先确认两端通道都覆盖）：

- **配置注入**：`__meowPolicy*`（后端 policy 读写）、`__meowPrefs*`、`__meowWebdav*`、`__meowUpdate*`、`__meowTool*`
- **动作**：`__meowActionStart`（清搜索缓存 / 重置源健康）
- **AI**：`__meowAiOpen/AiClose/AiIsOpen`
- **媒体**：`__meowPauseMedia`、`__meowWebviewPause/Resume`、`__meowActionStart` 的同族
- **导航/返回栈**：`__meowTvConsumeBack`（链式）、`__meowTvRouteBack`、`__meowTvPageBack`、`__meowOwn`（归属标记）、`__meowInner`（内层标记）
- **投屏**：`__meowCastConfirm`、`__meowCastCancel`
- **诊断**：`__meowDiag`、`__meowTvMenuHook`

**规范**：新增"外部可调"能力时，Promise 版与轮询版**必须同时提供**（否则 TV 端不可用），
并在本表登记；不要只在 PC 侧测通就宣布完成。

### 3.8 壳侧能力清单（分组，完整清单看源码）

**TV 壳：`TvBridge.java` 86 个 `@JavascriptInterface` 方法**（按功能分组）：

| 组 | 代表方法 | 用途 |
|---|---|---|
| 设备 | `platform` / `deviceName` / `appVersion` / `setOrientation` | 身份、投屏设备名、版本、锁方向 |
| 输入 | `inputText`(×2) / `showIme` / `setEditing` / `setUiTheme` | 遥控器打字、主题传给原生输入框 |
| 播放 | `nativePlayerAvailable` / `openNative` | ExoPlayer 兜底 |
| 投屏 | `castConfirmResult` | 首投确认回执 |
| 连接 | `connState` / `saveConn` / `reloadApp` | 后端地址持久化（真源在壳 prefs） |
| 本地网关 | `localGatewayRunning` / `startLocalGateway`(×2) / `startScan` / `scanRunning` / `scanResult` | 端内 9978 网关与局域网后端扫描 |
| 推送 | `pushRunning` / `togglePush` / `pushToken` | 推送/远程调试令牌 |
| 更新 | `appUpdaterPlatform` / `appUpdateManifest` / `appUpdateDownload` / `appUpdateState` / `appUpdateInstall` / `netJobStart` / `netJobPoll` … | APK 更新与网络任务（同步回调 + 轮询） |
| 模块 / Python 模块 | `moduleState` / `moduleSetEnabled` / `moduleCheckFromText`；`pyModuleState` / `pyModuleCheck` | 运行时模块（tv-node / tv-python） |
| 载荷 | `payloadCheck` / `payloadState` | 弹幕载荷 |
| 调试 | `debugState` / `setDebugEnabled` / `crashLog` / `clearCrashLog` | 远程调试与崩溃日志 |

**PC 壳：`client/apps/desktop/src-tauri` 43 个 `#[tauri::command]`**（含 `desktop_*` 9 个：
托盘/光标/单实例/唤醒）；**另有 MCP 18 个 `pc_*`，是独立的 JSON-RPC 工具，不是 Tauri
命令**（由应用内线程提供，见 `mcp.rs`）。

**规范**：这张表只做「分组导览」。新增壳能力时同步
`site/docs/dev/tv-integration.md`（TV）与 `plugin-system.md`（PC 插件），
不要在本篇堆方法签名。

## 4. 混乱点清单（按严重度）

> **整改状态（2026-11-17）**：P0-1 / P0-3 / P1-5 / P1-6 / P1-7 / P2-8 已解决
> （§6 A~E）；P0-2 已由四维档案拆开（身份/形态/输入/方向）；
> P2-9（端专属类名登记）与 P2-12（全屏三态）**仍未做**，见各自条目。
> 下面保留整改前的原始描述作为背景，不是现状清单。

### P0（会分叉，必须收口）

- **P0-1 同一概念五套判据。** 「是不是 TV」同时有 A1（data-tv/UA）、A3（`platform`
  方法存在）、A4（`moduleCheck` 方法存在）、A10（`platform()` 返回 `'tv'`）、A9（形态）。
  其中 A3/A4 **函数同名、判据不同**（老壳只有 platform 时：菜单键认为"是 TV"、
  更新中心认为"不是"）；A3 与 A10 也不同（存在性 vs 返回值——将来出手机 App 时
  `platform()` 返回 `'android'`，A3 仍判 TV、A10 判非 TV）。
- **P0-2 「TV」被当成四个含义用。** 身份 / 无鼠标 / 大屏 / 遥控输入四件事被一个
  `isTvEnv()` 承担；MuMu 竖屏同时满足前两个而违反后两个，已出现补丁叠补丁。
- **P0-3 PC 身份三份实现。** A5（`tauriInvoke`）、A6（全局变量）、A11（`isDesktop()`）
  语义相同、代码三份；Tauri dev 插件未就绪时可能不同结论（base 解析 vs 更新判定）。

### P1（用户可感知的"不一致"）

- **P1-4 一台 iPad 竖屏四种答案**（见案例 2）。根因：搜索/播放用宽度、设置用方向、
  顶栏用 900、卡片用卡宽，四套坐标系。
- **P1-5 「手机」两套语义。** CSS 按 `<640`（`max-sm:`），JS 按 `pointer:coarse` /
  `isTouch` / `innerHeight<560`。852 宽的手机横屏 = 桌面样式 + 触摸交互。
- **P1-6 方向判据四种写法**（`orientation` / `innerWidth>=innerHeight` / `>=768`）。
  1024 横屏平板、768 竖屏平板正好落在缝隙里。
- **P1-7 矮屏阈值两个**（CSS 520 / JS 560）：520–560 之间会出现"JS 只铺 2 行、
  CSS 行距没收紧"。

### P2（维护成本，会持续制造新的混乱）

- **P2-8 "是不是 fpk"仍有两处自探测**：`capability` 已单点在 `stores/appUpdate.ts`
  （`App.vue` 已改读 `probeCapability()`），但 `FpkUpgradePanel` 仍自调
  `detectFpkShell()` 判显隐（D-3），`updateCenter.checkFpk/probeFpkCurrent` 也各调一次
  （共用 `detectFpkShell` 缓存、不重复发请求）。历史已出过"两行状态不一致"。
- **P2-9 端专属类名在膨胀**：`tv-hide` / `tv-unstick` / `touch-hide` /
  `menu-replay` / `nav-label` / `has-tabbar`。没有登记处，本轮一次就新增 3 个。
- **P2-10 列数三层叠加**：`prefs.homeCols`（上限）→ `resolveCardCols`（卡宽算法）→
  TV 目标卡宽 76（第三层）。优先级只在注释里；本轮历史页漏了第二层（写死断点）。
- **P2-11 老内核降级是一整套并行样式表**：`data-legacy-css` 有 255 条规则，
  与 Tailwind 类名重叠。改动 Tailwind 间距类时要意识到老内核走的是另一份。
- **P2-12 全屏有三种实现 + 一个事件桥**：`requestFullscreen`（真全屏）/ 伪全屏
  （`pseudoFs` + `meow:pseudofs` 事件）/ iOS 铺满；状态分散在 `fullscreen`、
  `pseudoFs`、`fsIntent` 三份，判断在 VideoPlayer，副作用在 App.vue。
  改全屏逻辑时三处都要看（含"换集后恢复全屏"的 `rememberFsIntent`）。

## 5. 规范（目标模型，改代码前读这节）

> **已落地（2026-11-17）**：`compat/shell.ts`（身份/能力）、`compat/ua.ts`
> （唯一 UA 出口）、`compat/deviceProfile.ts`（四维档案）、
> `compat/inputModality.ts`（输入）。业务代码只读 `html[data-*]` 或
> `compat/` 导出；门禁 `tests/device-profile.test.ts` 锁住。

### 5.1 单一数据源：`compat/deviceProfile.ts`

模块评估即安装（`main.ts` 第一行 import），输出到 `<html>`，全应用只读：

```ts
type Shell = 'web' | 'tauri' | 'tv' | 'fpk'        // 身份（壳）
type Form = 'phone' | 'tablet' | 'desktop' | 'tv-large'  // 形态（宽高方向）
type Input = 'touch' | 'key'                        // 输入
type Orient = 'portrait' | 'landscape'

html.dataset.shell = shell    // 替代 A1/A5/A6/A8 散判
html.dataset.form = form      // 替代 A9 + 尺寸阈值组合
html.dataset.orient = orient  // 替代四套方向判据
// + data-input / data-tv（data-tv 保留为 CSS/调试别名，不再做身份）
```

- `shell` 同步判断：`isTauriRuntime()` → TV（`window.meowTv` 或 UA `MeowTV`，
  首屏兜底）→ `web`；`fpk` 由 `probeCapability()` 异步探测后 `markShell('fpk')`
  补写（**只升级，不覆盖 tauri/tv**）。
- `form`（`resolveForm(width, height, anyFinePointer, tvShell)`）：竖屏且短边 <640 →
  phone；竖屏 → tablet；横屏且短边 <480 → tablet；横屏短边 ≥480 且无
  `any-pointer:fine` **且壳是 TV** → tv-large；其余 desktop。
  ★ `tvShell` 这一条不能省：iPad 横屏 1180×820 同样没有精确指针，但它是平板，
  按 tv-large 会连返回键一起隐藏（违反「形态只影响布局」红线）。
- resize/旋转只更新属性（debounce 100ms），不重建组件。
- **形态只影响布局密度**；返回键/更新/托盘/音量/原生播放器用身份或能力
  （§8.2 红线）。

### 5.2 身份只认"壳"

- `isTvShell()`（`compat/shell.ts`）是唯一身份实现；`stores/tvMenu.ts`、
  `stores/updateCenter.ts` 只做 re-export。`hasTvModule()` 是**能力**，
  更新中心用它决定「Node / Python 运行时 / 弹幕组件」行是否显示。
- `isDesktopShell()` 是唯一 PC 身份实现（`api/index.ts` 的 `isDesktop()` 是别名）。
- 删除 UA 推断作为**显示判据**：`navigator.userAgent` 只允许出现在
  `compat/ua.ts`（`isMeowTvUa` / `isIosPhone` / `isFirefox` / `isAndroid` /
  `uaText` / `chromeMajor`）。
- `?tv=1` / `?legacycss=1` 保留为调试覆盖，禁止业务代码读 query。

### 5.3 能力只认桥方法

保留第 1.4 节做法。新增端差异时先问"这是能力还是身份"：
- 能力（能不能用原生播放器、能不能调系统音量）→ 探桥方法，**永不读身份**。
- 身份（PC 要不要显示托盘、TV 要不要隐藏返回键）→ 读 `data-shell` / `data-form`。

> **iOS 壳（规划）**：壳身份只通过 `window.meowIos` 做能力入口，**不做样式判据** ——
> 触屏布局沿用现有 `(pointer: coarse)` + 竖屏断点。再加一套 iOS 身份判据就是第 12 套
> 历史债（详见 [ios-integration](./ios-integration.md)）。
>
> 现有正面样板：`LiveView.vue` 的紧凑布局判据写的是**视口方向**而不是 `isTvEnv()`
> （注释：TV APK 也可能装在手机上、即竖屏）——这就是「形态归形态、身份归身份」。

### 5.4 尺寸收敛到 3+1（已完成）

只保留：**640**（手机/平板）、**768**（导航密度、`isWideLayout` 的兜底）、
**1024**（触屏图标化 + 双栏）、矮屏统一 **520**。899 / 1023 / 560 已删除；
480 只存在于 `resolveForm()` 的形态单次计算里；`SearchView` / `PlayView` 的
`innerWidth>=768` 已改 `isWideLayout()`。

### 5.5 端专属样式改 `data-form` / `data-shell` 选择器（未做，下个版本）

> 现状：`tv-hide` / `tv-unstick` / `touch-hide` / `menu-replay` / `nav-label` /
> `has-tabbar` 六个类保留原写法（本轮只统一了它们的阈值与判据）。改名会同时
> 影响模板与 TV 注入脚本，单独一个 PR 做。

```css
/* 新写法（在 tokens.css 同一区块登记） */
html[data-shell='tv'] .hide-on-tv { display: none !important; }
html[data-form='phone'] .only-wide { display: none !important; }
```

现有 6 个类保留一个版本作为别名（`tv-hide ≡ hide-on-tv`），下个版本删除。
**新增"某端隐藏"必须先在这里登记**，不允许到处 `matchMedia`。

### 5.6 列数三层收敛为两层（已完成）

`prefs.homeCols`（上限）→ `resolveCardCols`（目标卡宽 + 矮屏加列）。
TV 目标卡宽 76 不再由 `isTvEnv()` 单独判，而是由 `form='tv-large'` 直接给：
电视插鼠标（form=desktop）按桌面卡宽走，与返回键判据同源。
所有海报列表（首页/历史/收藏/搜索/片库/分类/缓存）**必须**用
`colsStyle(useCardCols())`，禁止写死断点（门禁见 6.D）。

## 6. 整改路线（按 PR 拆分，可独立验收）

> **2026-11-17 已全部落地（阶段 0 + A~E）**，实施位置与验收结果如下。
> 原计划保留作对照；后续新增端差异按同样五步走。

**阶段 0 ✅ 8.3 的 5 个现存缺陷**：D-1 焦点环判据（`maxTouchPoints`→
`(pointer: coarse)`）、D-2 `isTvShell` 拆身份/能力、D-3 `FpkUpgradePanel`
改读 store、D-4 设置页判据统一、D-5 双击按事件来源。
验收：PC 900×1000 竖窗口 `data-input=key` 且按 ArrowDown 仍为 key（实测）；
老壳只有 `platform` 时 `isTvShell()=true` / `hasTvModule()=false`（单测）；
FpkUpgradePanel 不再 import `detectFpkShell`（源码门禁）。

- **A ✅ 身份去重**：`compat/shell.ts` 提供 `isTvShell` / `hasTvModule` /
  `isDesktopShell` / `isTauriRuntime`；`compat/ua.ts` 收掉全部 UA 读取；
  `currentPlatform()` 会话内缓存。`grep "export function isTvShell" src` = 1 处。
- **B ✅ 尺寸收口**：`nav-label` 与 `touch-hide` 统一为
  `(pointer: coarse) and (max-width: 1024px)`；矮屏 JS 改 520
  （`isShortViewport()`）；`SearchView` / `PlayView` 的 `innerWidth>=768`
  改 `isWideLayout()`。
- **C ✅ deviceProfile 落地**：`compat/deviceProfile.ts` 自安装，写
  `data-shell` / `data-form` / `data-orient`；`isTvEnv()` 变薄封装（读
  `data-tv`）；列数第三层改由 `form='tv-large'` 驱动。
  验收：多视口实测见 §9.1。
- **D ✅ 门禁**：`tests/device-profile.test.ts`（21 条）扫 `src/`，除
  `compat/` 外禁止 `MeowTV`、`matchMedia('(pointer`、`innerWidth >=`、
  `innerHeight <`、`navigator.userAgent`、`maxTouchPoints`、旧断点
  899/1023；并断言所有 `poster-grid` 同标签内用了 `colsStyle`。
- **E ✅ 更新能力收口**：`capability` 只在 `stores/appUpdate.ts` 算
  （`probeCapability(force)`）；`App.vue` / `UpdateCenter` /
  `FpkUpgradePanel` 只读；fpk 版本检查统一走 `checkFpkUpdate()`。

## 7. 验收断言（可执行）

```bash
# 1) 身份单一来源
grep -rn "export function isTvShell" client/apps/web/src        # 期望：compat/shell.ts 1 处
grep -rn "navigator.userAgent" client/apps/web/src --include=*.ts --include=*.vue | grep -v /compat/  # 期望空

# 2) 阈值收口
grep -rn "max-width: 899\|max-width: 1023\|innerHeight < 560" client/apps/web/src  # 期望空
grep -rn "innerWidth >= 768" client/apps/web/src --include=*.vue                    # 期望空

# 3) 海报列表统一
grep -rn "grid-cols-3 gap-x-4\|md:grid-cols-4 lg:grid-cols-6" client/apps/web/src/views  # 期望空

# 4) 门禁 + 前端回归
cd client/apps/web && npx vitest run tests/device-profile.test.ts
cd client/apps/web && npx vue-tsc --noEmit && npx vitest run
```

Playwright 断言矩阵（五项视口 × 第 2 节行）见 §9.1 的实测脚本
（`tmp/2026-11-17-device-profile/check_profile.py`）；断言的判据是 DOM 几何与
`html[data-*]`，不是截图肉眼。

## 8. 二次核查：判据精度与回归风险（2026-11-17）

### 8.1 信号实测矩阵（Playwright，Chromium）

| 环境 | 视口 | pointer | any-pointer | maxTouchPoints | hover |
|---|---|---|---|---|---|
| PC 桌面（无触摸） | 1280×720 | fine | fine | **10** | hover |
| PC + hasTouch（模拟触摸屏） | 1280×720 | **coarse** | coarse | 1 | none |
| 手机竖屏 | 390×844 | coarse | coarse | 1 | none |
| 手机横屏 | 852×393 | coarse | coarse | 1 | none |
| 平板竖屏 | 768×1024 | coarse | coarse | 1 | none |
| 平板横屏 | 1024×768 | coarse | coarse | 1 | none |
| TV 仿真（无触摸） | 1138×640 | **fine** | fine | 10 | hover |
| TV 仿真（hasTouch） | 1138×640 | coarse | coarse | 1 | none |

**两个反直觉结论**：

1. **`maxTouchPoints` 不可靠**：桌面 Chromium 无触摸也报 **10**。用它当触屏判据，
   PC 竖窗口会被判成"竖屏触屏"。
2. **`pointer` 随触摸能力翻转**：给 PC 加触摸模拟就变 `coarse`。真 TV（无触摸无鼠标）
   的 `pointer` 可能是 **none**（既非 coarse 也非 fine）——任何"用 coarse 表示触摸"的
   规则在真 TV 上都不命中。这也是为什么 TV 一律靠 `data-tv` 排除、不靠 coarse。
3. Playwright **无法模拟 `any-pointer: fine + coarse` 双真**（触摸屏笔记本/接鼠标的盒子），
   这类设备只能真机验证。

### 8.2 可区分性边界（"精准识别"到什么程度）

| 组合 | 能否区分 | 手段 | 说明 |
|---|---|---|---|
| PC / TV / fnOS / Web | **能** | tauriInvoke / data-tv(UA) / detectFpkShell / 默认 | 四个信号互斥、可靠 |
| MuMu 模拟器 vs 真 TV | **能** | data-tv 身份 + `any-pointer: fine` 形态 | 模拟器有鼠标 → fine 为真 |
| 触摸屏笔记本 vs 手机 | 能 | `any-pointer: fine` + 视口 | 有精确指针即按 PC |
| iPad 带鼠标 vs Mac | **不能** | — | iPadOS 桌面 UA + fine；按 PC 处理（可接受） |
| 安卓平板 vs 大屏手机 | 只能按尺寸 | form | 768 边界存在歧义设备 |
| 真 TV vs 禁用鼠标的 PC | **不能** | — | 极少见；靠 data-tv 兜底 |
| 老内核（Chrome 66） | 能 | `legacyCss` 实测探针 | 探针一次性 |

**结论**：
- **壳身份可精准识别**（用于端专属入口：托盘、ExoPlayer、原生输入、更新通道）。
- **形态只能近似**（phone/tablet/desktop/tv-large）→ **形态只允许影响布局密度**
  （列数、双栏/胶囊、按钮显隐），**永远不许影响功能可用性**（返回键、托盘、更新、
  系统音量、原生播放器）——后者一律用身份或能力判断。
- **设备能力必须探测**（解码、音量、亮度、HLS、原生播放器）。

### 8.3 核查发现的 5 个现存缺陷（已全部修复，2026-11-17）

- **D-1 焦点环在 PC 竖窗口永久失效**（实测）：`inputModality.hasTouch()` 用
  `navigator.maxTouchPoints > 0` 判触屏，而桌面 Chromium 报 **10** →
  900×1000 的 PC 窗口 `data-input=touch`，按方向键也不会切回 `key`。
  ✅ 已改 `matchMedia('(pointer: coarse)')`；实测 900×1000 初始 key、按
  ArrowDown 仍 key。
- **D-2 `isTvShell()` 两份同名实现**（见 P0-1）：`tvMenu.ts` 看 `platform`，
  `updateCenter.ts` 看 `moduleCheck`。
  ✅ 已拆成**身份** `isTvShell()`（`compat/shell.ts`，任一桥存在）与**能力**
  `hasTvModule()`（`moduleCheck` 存在）；更新中心的模块/载荷行用能力，菜单键与
  fpk 排除用身份。单测覆盖"只有 platform 的老壳"。
- **D-3 `FpkUpgradePanel` 绕过 capability**：`capability` 已单点在
  `stores/appUpdate.ts`（`probeCapability`），但 `FpkUpgradePanel.vue` 自己又调
  `detectFpkShell()`。
  ✅ 已改为读 `capability` / `probeCapability(force)` + `checkFpkUpdate()`；
  `tests/device-profile.test.ts` 断言业务文件不再直接 import `detectFpkShell`。
- **D-4 设置页与菜单键的 TV 判据不是一回事**：`SettingsView` 用 `platformName() === 'tv'`
  （A10，桥方法**返回值**），`tvMenu`/`updateCenter` 用方法**存在性**（A3/A4）。
  ✅ `tvMode` 改 `isTvShell()`（身份），运行时模块分区另用 `tvModule =
  hasTvModule()`；`platformName()` 只剩设备名/日志用途。
- **D-5 双击语义按设备而非事件**（2026-11-17 用户确认口径后新增）：`isTouch` 是设备级
  判据（`pointer: coarse || ontouchstart`），触摸屏 PC 上恒 true → **鼠标双击也走分区快进**
  （PC 应为双击全屏）。
  ✅ 已按事件来源分流：沿用最近一次 `pointerdown` 的 `pointerType`（800ms 内），
  鼠标 → 全屏、手指/触控笔 → 分区；合成事件无 pointerdown 时回退设备判据。

### 8.4 整改的回归风险（逐项规避）

| 风险 | 触发场景 | 规避 |
|---|---|---|
| R1 身份合并误判能力 | 老壳有 `platform` 无 `moduleCheck` → 更新中心显示不可用行 | 拆成身份/能力两个函数（D-2） |
| R2 PC 身份合并破坏"插件未就绪"报错 | Tauri dev 注入早于插件就绪 | 保留 `backendUnavailable()` 现有语义，只合函数不改调用点顺序 |
| R3 `nav-label` 阈值 899→1024 | 900–1024 的触屏平板改为图标化（比旧版更早收文字） | ✅ 已用与 `.touch-hide` 完全相同的判据（≤1024 + coarse）；iPad Air 4 横屏 1180 不受影响 |
| R4 `touch-hide` 与控制栏 | 1024 横屏平板也收起重播/刷新 | ✅ 接受：两项在「更多」菜单里有互补入口（`.menu-replay`），不会失去功能 |
| R5 矮屏 520/560 统一 | 520–560 窗口的首屏行数/行距变化 | 统一到 520，跑三视口截图对比 |
| R6 返回键判据并入 form | PC 全屏/触摸屏笔记本丢返回键；或反之让插键鼠的 TV 隐藏返回键 | **红线**：必须保留 `any-pointer: fine` 子条件（用户已确认插鼠标的 TV 显示返回键是预期） |
| R7 deviceProfile 首屏 | `data-tv` 由 `index.html` 首帧脚本按 UA 写入（**不是壳注入**）；`?tv=1` 只设属性 | ✅ `main.ts` 第一行 import deviceProfile，同步写 `data-shell/form/orient`，shell=tv 时补写 `data-tv`；没有运行时变更源，未加 MutationObserver（以后真有壳注入再加） |
| R8 缓存 form 不更新 | 旋转/改窗口后列数不换 | resize + orientationchange 都触发重算（复用 inputModality 的写法） |
| R9 `isTvEnv()` 语义 | 现有 5 处调用每次读 dataset | 替换为读 profile 时保证"属性变化即生效"（getter 或 observer） |
| R10 门禁禁 `userAgent` 误伤 | 6 处合法用法：iOS 音量判定、legacyCss 诊断、LogsView、MeowTV 兜底×3 | 白名单 `compat/**`、`api/nativeBridge.ts`、`styles/legacyCss.ts`、`views/LogsView.vue` |
| R11 门禁禁 `innerWidth >=` 误伤 | 搜索页源栏 172px、投屏面板锚定等布局测量 | 规则改为"禁止在顶层做身份判断"；布局测量允许，但优先用容器宽度 |
| R12 capability 收口时序 | 弹窗打开时 store 尚未探测 | 未就绪时触发一次 `probeCapability()` |

### 8.5 不要引入的判据（负面清单）

- `navigator.userAgent`（除 MeowTV 首屏兜底、iPhone 音量、诊断日志）
- `navigator.maxTouchPoints`（桌面 Chromium 报 10，见 D-1）
- `(hover: none)`（带鼠标的盒子会翻转，历史已踩）
- `devicePixelRatio` / 物理分辨率（TV 的 CSS 视口只有物理的一半）
- 新增窗口宽度魔法数（先问"这是身份、形态还是布局测量"）

## 9. 实际设备与验收优先级（2026-11-17 用户确认）

**设备矩阵不再按"理想组合"推演，以下为真实在用的设备**：

| 设备 | 视口（CSS） | 命中哪些判据 | 备注 |
|---|---|---|---|
| **Windows PC（触摸屏，主用端）** | 1280×720 起 | `data-shell=tauri`（PC 壳）/ `web`（浏览器）；`data-form=desktop`；`data-input=key` | **D-1 已修**：竖窗口 900×1000 也是 key（旧版 maxTouchPoints=10 会误判 touch） |
| iPhone（Safari + 主屏 PWA） | 390×844 / 852×393 | coarse；`form=phone`（竖）/ `tablet`（横）；无 requestFullscreen | 全屏方案**先不做**（保留弹幕，横屏铺满）；无壳，IPA 规划见 ios-integration |
| **iPad Air 4（10.9 寸）** | **820×1180 / 1180×820** | coarse；`form=tablet`（竖）/ `desktop`（横）；nav-label 阈值 1024 | 竖 820 → 图标化；横 1180 → 保留文字；横屏不是 tv-large（web 壳），返回键保留 |
| TV / 盒子 / 投影 | MuMu 1138×640、极米 Android 9 | `data-shell=tv`；无鼠标 → `form=tv-large`；插鼠标 → `desktop` | **插鼠标/键盘时 `any-pointer: fine` 为真**，返回键显示（红线）；投影仪用内置输入法（`showIme`/`inputText` 桥） |

### 9.1 多视口实测（2026-11-17，Playwright Chromium）

脚本：`tmp/2026-11-17-device-profile/check_profile.py`（headless、每档一个 context）。
注意：Playwright 的 `has_touch` 只翻转 pointer 媒体查询，**不能模拟「无任何指针」**，
所以 TV 真值（tv-large）用单测 `resolveForm(1138, 640, false, true)` 覆盖；
`resolveForm(1180, 820, false, false)`（iPad 横屏）必须是 desktop。

| 视口 | shell | form | orient | input | nav-label |
|---|---|---|---|---|---|
| 390×844（手机竖） | web | phone | portrait | touch | none |
| 852×393（手机横） | web | tablet | landscape | key | none |
| 820×1180（iPad 竖） | web | tablet | portrait | touch | none |
| 1180×820（iPad 横） | web | desktop | landscape | key | flex |
| 1280×720（PC） | web | desktop | landscape | key | flex |
| 900×1000（PC 竖窗） | web | tablet | portrait | **key** | flex |
| 1138×640（TV 仿真，桌面内核） | web | desktop | landscape | key | flex |
| `?tv=1` 调试（1280×720） | **web** | desktop | landscape | key | flex |

最后一行是身份/外观分离的验收点：`?tv=1` 只设 `data-tv'1'`，**不改 `data-shell`**
（否则浏览器调试会被当成 TV 壳，露出 TV 专属功能）。

壳身份与端专属 UI 用注入脚本模拟验证（`tmp/2026-11-17-device-profile/`）：

| 模拟 | 结果 |
|---|---|
| Web / TV 老壳（只有 `platform`）/ TV 新壳（有 `moduleCheck`）/ Tauri / fnOS fpk | `data-shell` = web/tv/tv/tauri/fpk；老壳 `hasTvModule=false`、新壳 true |
| 设置页旧壳 | 本机执行分区在、运行时模块/弹幕组件分区与行**不在** |
| 设置页新壳 | 运行时模块分区 + 两行都在 |
| fnOS fpk（mock `/api/_shell/info` + `/app-center` + 索引） | fpk 行与面板可见，文案「当前 v1.2.14 · 发现 v1.2.15」 |
| 双击（组件测试） | `pointerdown(mouse)+dblclick` → 全屏；`pointerdown(touch)+中区 dblclick` → 播放/暂停 |

### 9.2 真机验证（2026-11-17，本轮改动）

| 目标 | 环境 | `data-*` | 端专属 UI | 结论 |
|---|---|---|---|---|
| PC | Windows，源码构建的 Tauri 1.2.14（测试后关闭） | `tauri / desktop / landscape / key` | 更新行 app+plugins，module/payload/fpk 均 0；本机执行/运行时分区 0 | ✅ D-1 真机确认：`maxTouchPoints=10` 但 `(pointer: coarse)=false` → input=key |
| TV 横屏 | MuMu `.76`，Android 15，CSS 1138×640 级 | `tv / tv-large / landscape / key / data-tv=1` | 更新行 app+module+payload=1，plugins/fpk=0；本机执行+运行时分区均在 | ✅ 无鼠标 → tv-large；模块行是能力（moduleCheck） |
| TV 竖屏 | MuMu `.78`，Android 15 | `tv / phone / portrait / touch / data-tv=1` | 同上 | ✅ 混合态：TV 身份 + 手机形态 + 触摸输入三个维度互不干扰 |
| fnOS fpk | `.34`，网关 `:5666/app/meow/`（后端 1.2.11） | `fpk / desktop / landscape / key` | fpk 行=1，module/plugins=0；面板可见「当前 v1.2.11 · 发现 v1.2.14」 | ✅ 能力探测走真实 `/api/_shell/info` + `/app-center`；0 pageerror |

说明：

- PC 用源码 `build-gnu.ps1` 出的 `meow-player.exe`（1.2.14），验证完已退出；未覆盖安装用户版本。
- TV 用 `build-apk.ps1 -FreshDist` 重打 release APK，装到两台 MuMu（同为 1.2.14、保留数据）。
  验证期间临时出过 debug 签名包以打开 DebugServer，验证后已装回 release 并关闭调试口。
- fpk 是**热替换** `/vol1/@appcenter/meow/web/`（先备份，按 `--base=/app/meow/` +
  `VITE_API_BASE=/app/meow` 构建），验证完已还原 1.2.11 原文件；没有改后端/数据。
- 未覆盖：真投影仪遥控焦点、真实触摸屏的鼠标/手指双击（D-5 已有组件级事件测试）、
  fnOS 的 fpk **安装/升级动作**（只验证了能力探测与面板展示）。

### 9.3 2026-11-18 回归复核（1.2.11 → 1.2.15 用户反馈）

两个用户报的 bug + 复核中又抓到的两处缝隙，均已修复并在投影仪/`MuMu .76` 真机验证：

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | TV 顶栏源下拉折叠成「… ▾」（投影仪 1280 CSS / 960 电视） | 搜索按钮在 flex 里抢宽 + `[data-site-menu]` 的 `min-w-0` 被压；960 下更严重 | 搜索/设置包进同一个 `md:absolute` **浮动组**（不参与顶栏 flex 挤压）；搜索框 7rem；TV 源下拉 `flex-shrink:0`；≤1024 导航项图标化 |
| 2 | TV 设置页方向键无法滚动（投影仪 Android 9 / Chromium 66） | `wide:grid` 的隐式行高 = 内容高（8818px），父容器 `overflow-hidden` 裁掉后右栏 **没有可滚空间**；新内核恰好看不出来 | grid 容器加 `wide:grid-rows-[minmax(0,1fr)]`；右栏恢复 603px 高、真实 8215px 滚动 |
| 3 | （复核发现）PC 窄窗 800–1024 浮动搜索压住导航文字 | 图标化只对 `pointer: coarse` 生效，PC 精确指针不命中 | 导航图标化条件改为**宽度 ≤1024**（不限指针） |
| 4 | （复核发现）640–767 竖屏搜索页卡片被多扣 172px | `isWideLayout()` 用 `form !== 'phone'` 推导，和 CSS `md:`(768) 缝隙 | `isWideLayout()` 回到内宽 ≥768 |
| 5 | 修复后搜索框与设置**不在顶栏同一行** | 搜索按钮自身 `md:absolute` + `md:-translate-y-1/2`、设置在 `nav` 另一层各自定位；且 Tailwind v4 把这个 translate 编译成 `translate:` 属性，Chromium 66 不认 → 纵向下沉半行 | 改成一个浮动 wrapper 统一 `md:flex md:items-center` 纵向居中，子元素不再各自 absolute / translate |
| 6 | 投影仪播放页「搜索弹幕」按钮放大镜偏左（其他端正常） | 按钮是 `hidden … sm:grid`；旧 Blink 对 `button` 的 `place-items:center` 只对纵向生效，而兜底选择器 `[class~='grid']` 匹配不到 `sm:grid` 这个完整类名 | `legacy-css.css` 补 `sm:grid` / `max-sm:grid` 两条变体兜底（各包自己的断点），`md:-translate-*` 同样补变体兜底 |

验证矩阵（真实后端数据，1.2.11 / 1.2.13 / 修复后三份 bundle）：

- 首页海报列数/卡宽/顶栏：960×540、1138×640、1280×720 **三版本一致**（6 列 / 139–189px）。
- 搜索页源栏与列数、直播页、设置页滚动容器：三版本一致；设置页仅高度 +32px（去掉旧 `-2rem`）。
- 顶栏几何：390/800/852/820/900/960/1024/1138/1280/1920 全部无溢出、无重叠；搜索/设置**同一行**（`sameLine:true`），源下拉宽 68px 可见。
- 老内核变体兜底：投影仪顶栏搜索/设置同一行（截图 `tmp/2026-11-18-tv-regression/hdr-fixed.jpg`）；播放页弹幕按钮 `display:flex`、图标相对按钮 `dx=0 dy=0`。
- 门禁：`tests/legacy-compat.test.ts` 新增两条（`md:-translate-*` 变体、`sm:grid`/`max-sm:grid` 变体），全量 **1855 passed**，`vue-tsc` exit 0。
- 真机：投影仪（1280×720、Chromium 66）设置页 `shell=603 / rightSpan=8215`，真实 ↓ 滚动 `0→116→171→342…`；MuMu .76（1138×640）`0→78→359…`。

### 9.4 2026-11-18 用户四项反馈（发布后第二轮）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 顶栏搜索框太窄，「搜索影片…」被截成「搜索影…」 | 7rem（112px）减去图标/间距/内边距后正文只有约 62px；占位文字 72–90px（随字号阶梯） | 8.5rem（实测 134/151/168px，五档视口全部 `fits:true`、顶栏无溢出） |
| 2 | 顶栏 logo 聚焦框是方形的 | tv-nav 焦点框用 `box-shadow`（自动跟随元素圆角），但 `<a>` 本身没有圆角——圆角只在 `<img>` 上 | 圆角加到 RouterLink（`rounded-[9px] max-sm:rounded-lg`），与图标一致 |
| 3 | AI 面板右上「设置」停在设置页顶部 | 链接只写了 `/settings`，没用已有的 `?sec=` 深链接 | `:to="{ path:'/settings', query:{ sec:'ai' } }"` → 落在「11 AI」并闪一下 |
| 4 | 添加片库像"跳页面"，关掉后片库闪刷新；打开时第一项只闪一下焦点 | 添加/编辑是独立路由（`/library/add`），返回时 LibraryView 卸载重建（`loading=true` → 骨架）；且页面用原生 `focus()`，与 tv-nav 的 class 焦点框并存 | 改成片库页内原位弹框 `LibraryAddDialog`（`Teleport to="body"` + `data-tv-scope`，不占路由，保存后只换 `overview` 不动 `loading`）；TV 初始焦点交给 tv-nav（`data-tv-first`，无原生 focus）；关闭时显式 `__meowTvFocus(打开按钮, true)` 归还焦点。★ Teleport 是必须的：`main` 的层叠上下文会让顶栏压在遮罩上（不暗、可穿透点击） |

验证（`tmp/2026-11-18-tv-polish/verify.py`，真实后端 + tv-nav 注入）：
搜索五档 `fits:true`；logo `<a>` 半径 9px、103×36（图标 36×36）；
AI → `#/settings?sec=ai`、AI 分区 top=96、左导航 `aria-current` = 「11 AI」；
片库弹框 `hash` 保持 `#/library`、打开即 `meow-tv-focus` 在 Webdav、↓ 一次到 SMB 且 Webdav 无原生 focus、
Escape 关闭后承载节点仍在（未重挂载）、焦点回到「添加片库」。

真机（MuMu .76 + 投影仪 Chromium 66，release 1.2.15 装回）：顶栏「搜索影片…」完整显示；
logo 聚焦为**圆角**双环；AI→设置直接落在「11 AI」；弹框在原页打开（背后海报不动）、
开框即 Webdav 有框、↓ 到 SMB、BACK 关闭后仍停在片库且焦点回到「添加片库」。
截图在 `tmp/2026-11-18-tv-polish/`（`76-logo.png` `xg-logo.png` `76-dialog-open.png` `xg-dialog.png` `76-close-restore.png` `xg-ai-sec.png`）。
门禁全量 **1855 passed**，`vue-tsc` exit 0。

**验收优先级**：PC 为主用端 → **先验 PC**（D-1 焦点环、身份合并、更新能力），
再验 iPad Air 4（820/1180 两档），iPhone 第三，TV/投影最后（真机最慢）。

**已确认的产品口径**：

1. **TV/盒子插鼠标时，播放页显示左上返回键 = 符合预期**（有指针就不算大屏电视）。
   → 整改时 **`any-pointer: fine` 这个子条件不能删**（R6 从风险升级为红线）。
2. **触摸屏 PC 上按事件来源切**：手指触摸 → 触屏语义（竖滑调亮度/音量、双击分区）；
   鼠标 → PC 语义（双击全屏、滚轮调音量）。
   ✅ 已实现（D-5）：双击沿用最近一次 `pointerdown` 的 `pointerType` 分流，
   不再用设备级 `isTouch` 判鼠标。
3. **iPhone 全屏先不做**：保持"铺满 + 横屏提示"，不引入 `webkitEnterFullscreen`
   （会丢弹幕与控制栏，见第 3 节案例 4）。

**混合输入设备的结论（与 8.2 对照）**：本机组合（触摸屏 PC + 插键鼠的 TV + 纯触屏 iPhone
+ 触屏 iPad）正好覆盖三类"设备级判据会误判"的场景 → **判据必须按事件来源或按能力，
不能按设备**。这一条已写进 D-5 与 5.3。

### 9.5 2026-11-18 补充：返回键与刷新语义

| # | 口径 | 修法 |
|---|---|---|
| 1 | 投影仪左上角又出现返回按钮（之前已去除） | 统一判据用了 `(any-pointer: fine)`，而这台投影仪（Android 9 / Chromium 66）**没接鼠标也报 fine** → 被算成 desktop。改为：TV 壳 + 大屏横屏 + **没真的产生过 pointer 事件** → 不显示；真接鼠标/触摸仍显示（R6 保留） |
| 2 | 竖屏 TV 要显示返回按钮 | 竖屏没有遥控器横向选焦问题、页面也没有顶栏可退 → `!isLargeLandscapeViewport()` 时一律给 |
| 3 | 播放页 ⟳ 刷新应从原位置续播 | `reloadSrc()` 记下 `v.currentTime`，`onLoaded` 里 seek 回去（父组件 `applyResume` 有一次性守卫，第二次 loaded 不会 seek）；⟺ 重播仍 `currentTime=0` 并清掉挂起位置 |

验证矩阵（本地 Chromium + dist，真实后端片库流）：

| 场景 | 返回键 |
|---|---|
| TV 横屏 1280×720（投影仪） | 0（隐藏） |
| TV 横屏 1138×640（MuMu 横向、无鼠标） | 0（隐藏） |
| TV 竖屏 390×844 | 1（显示） |
| 手机 Web 390×844 | 1 |
| PC Web 1366×720 | 1 |

刷新续播实测：`currentTime` 30s → 点 ⟳ → 重新 loaded 后 34s（继续播放，不是 0）。
投影仪真机：返回键 DOM 数量 0、弹幕按钮图标 `dx=dy=0`、弹框打开即 Webdav 有框。

## 10. 相关文档

- 前端不可回退决策：[frontend](./frontend.md)
- 播放器端差异（HLS/ExoPlayer/手势）：[playback](./playback.md)
- TV 壳注入与遥控协议：[tv-integration](./tv-integration.md)
- iOS 壳（IPA，规划中）：[ios-integration](./ios-integration.md)
- 投屏的设备矩阵与可达性：[casting](./casting.md)
- 测试与验收清单：[testing](./testing.md)

## 11. 硬事实去哪核对

| 事实 | 核对位置 |
|---|---|
| 壳身份 / 模块能力 | `compat/shell.ts` `isTvShell` / `hasTvModule` / `isDesktopShell` |
| 四维档案写入 | `compat/deviceProfile.ts` `installDeviceProfile` / `resolveForm` |
| UA 字面量（唯一出口） | `compat/ua.ts` |
| TV 外观别名 `data-tv` | `index.html` 首帧脚本 + `compat/shell.ts` `isTvEnv` |
| 大屏形态判据 | `compat/deviceForm.ts` `isBigScreenLandscape` → `resolveForm` |
| 输入方式 | `compat/inputModality.ts`（`data-input`） |
| 老内核降级阈值 | `styles/legacyCss.ts` 的四个探针 |
| 断点与阈值 | `styles/tokens.css`（`@custom-variant wide`、`@media` 块） |
| 列数算法 | `composables/useCardCols.ts` `resolveCardCols`（`tvLarge` 由 `form` 给） |
| 更新能力 | `stores/appUpdate.ts` `capability` / `probeCapability`、`api/appUpdate.ts` `currentPlatform` |
| 判据门禁 | `tests/device-profile.test.ts` |
| 顶栏高度 | `styles/tokens.css` `--header-h` 三档 |
