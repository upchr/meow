# iOS 端接入（IPA 形态与发版）

::: warning 现状（2026-11-25）：iOS 工程**已合入 main**，真机已跑通本地网关
`client/apps/ios/` 出未签名 IPA，**已随 1.2.22 作为 `Meow-unsigned-1.2.22.ipa` 发布**；
不上架 App Store，用户用 Apple ID 自签安装（见 [iPhone / iPad](/guide/ios)）。
真机（LiveContainer 侧载）验证到：JVM 启动、`/api/health` ok、豆瓣首页出数据、源导入能写盘。
本文的「落地契约」部分仍然有效；下面 §0 是与契约不一致的**已实现部分**，
与契约冲突时以 §0 为准（其余章节保留原契约口径，标【待实现】= 代码还没写；
标【待实测】= 没有 iPhone，无法验证）。
:::

## 零、已实现（feat/ios-jvm，实验性）

iOS 壳不再只是「连远端后端」：**进程内跑着与 PC/Docker 同一份 Java 宿主**
（`ApiServer`），源站、jar 爬虫、弹幕内置源都在手机本机执行。

| 能力 | 怎么做的 | 验证到哪一步 |
|---|---|---|
| 本地网关 | 静态链接 **OpenJDK 9 Zero**（`libjvm.a`，无 JIT）进壳，`JNI_CreateJavaVM` 起 `ApiServer` 监听 `127.0.0.1:9978`；WKWebView 加载本地静态服务 `9979` | **真机已跑通**（LiveContainer 侧载）：`/api/health` ok、豆瓣首页出数据；Static JVM 的 `__TEXT,__const` 必须 `-rename_section` 到 `__DATA,__const`（否则 dyld 初始化写只读页 → SIGBUS） |
| 源导入 | 与 PC 同一套 `POST /api/config/import`（URL / 覆盖 / dryRun / `replaceSpider`）；**确认导入走后台线程 + 轮询**（见 §0.2） | 肥猫 `http://肥猫.net/tv` → 41 站；OECT 源服务 `config.all.json` → 54 站；真机踩过「转换太久被客户端超时」 |
| dex 源包 | iOS 没有 fork/exec → **端内 dex2jar**（`core.Dex2JvmInProcess`，dex2jar 2.4 子集进 `jvm/libs`），`Dex2Jvm` 在 `-Dmeow.dex2jar.inprocess=true` 时反射调用它 | 肥猫顶层 dex → 893 个类；OECT 的 Jable（站点级 dex）→ 搜索 24 条 |
| JVM 源包（class jar） | `URLClassLoader` 直接加载；**必须**带 `-XX:-BytecodeVerificationRemote`（外部 jar 的 stackmap 常不完整，否则 `VerifyError`） | OECT `spider.jar`：AppRJ 搜索 2 条（不开校验就是 VerifyError） |
| 壳身份 | `window.meowIos` 桥（`version` / `apiBase` / `runtime`）→ `data-shell='ios'`；设置页「后端地址」可配（默认端内；保存后壳重建注入脚本再 reload） | 前端门禁 `device-profile` / `mobile-ux-polish` / `legacy-compat` 全绿 |
| CI | `_ios.yml`（未签名 IPA）、`_ios_ui.yml`（headless Chrome 快速截图）、`_ios_verify.yml`（runner 端到端，用的是 **IPA 里那份 host.jar**） | 三条流水线绿 |

**仍然缺**（与 TV 的差距，逐条都有原因）：

- **转码 / 冷门格式**：端上无 ffmpeg，转码路不可用；MKV / HEVC 由端内 VLCKit 原生播放
  （`canUseNative` 含 iOS，点播与片库都能进端内播放器），FLV 等仍播不了。
- **本地片库**：进程内没有可浏览的系统目录（`LibraryRoots` 只在 PC 插件模式追加盘符），
  实用路径是 WebDAV / SMB。
- **局域网网关 / 手机推设置**：iOS 壳只绑回环，不给别的设备当后端。
- **签名与上架**：OpenJDK 9 Zero 是 GPL-2.0+CE → 只能侧载，不能进 App Store。
- **后台挂起 / 内存**：启动与导入已真机验证过；后台被系统挂起后 JVM 线程能否恢复还没测。

### 0.2 真机第一轮暴露的四个问题（2026-11-24）

首包能进首页之后用户报的四个问题。全都不是崩溃，日志里能对上证据：

| 现象 | 真因 | 修法 |
|---|---|---|
| 刘海挡住顶栏 | 页面没带 `viewport-fit=cover`，`env(safe-area-inset-*)` 恒为 0；WebView 又被缩进安全区（第一版把整块内容框进去，看起来是上下两条黑边，用户否了） | WebView **铺满全屏**（`.ignoresSafeArea()`），`index.html` 的 iOS 分支补 `viewport-fit=cover`；`tokens.css` 按 `data-shell='ios'` 给 `.app-header` / `.has-tabbar` 加 `env(safe-area-inset-*)` —— 背景铺满、顶栏与底栏各自让开刘海/Home 条（主流 App 的做法） |
| 点输入框整页放大、键盘收起不还原 | WebKit 对字号 <16px 的输入控件聚焦时自动 zoom | `index.html` 首帧脚本认出 `window.meowIos` → `maximum-scale=1`；`tokens.css` 把 `html[data-shell='ios']` 下的输入控件抬到 16px |
| 导入报「读取失败」，服务端日志却是 `Broken pipe` | `dryRun` 也跑 `Dex2Jvm.rewrite`（下载 jar + dex2jar），Zero 上几十秒；客户端先断 → 响应写不出去 → 配置一个字节都没写 | ① 预览不再转换 jar（回 `dex2jvm: {skipped:"dryRun"}`）；② 确认导入改后台线程，`GET /api/config/import` 轮询 `state/pending`，界面显示已用秒数 |
| 导入成功后回首页「连不上后端」，设置页还显示已连接 | 两个叠加：① `resolveBase()` 的后端地址来自 `localStorage`，一旦那份缓存缺失，非桌面壳退回**同源** —— iOS 的同源是静态服务 9979（只做 GET/HEAD，POST 回 405 → 浏览器报 `Load failed`，后端日志里一行都没有）；② `spiderClassPresent()` 不缓存**未命中**，未命中要逐 jar 扫 zip —— 导入 41 站后基线里 44 个名字全变未命中，`/api/health`、`/api/sites` 每个站问一次，Zero 上把网关拖成「像挂了」 | ① 地址改成只认宿主注入的 `window.meowIos.apiBase`（桥每次文档加载都在），localStorage 只当缓存，拿不到就报明确错误；② 类名查找按 `core.Jars.generation()` 缓存（`rebuildSites()` 也失效）；`siteVisible()` 先判「基线幽灵」再查 jar；③ 诊断：服务端 ≥3s 的请求打 `[slow]`，前端网络级失败通过新增的 `meowLog` 桥写进 `launch.log` |

附带的兼容：粘站点首页（`http://肥猫.net`）时，后端检测到内容是 HTML 且 URL 没有路径，
自动补 `/tv` 再拉一次 —— 用户不需要知道配置文件的真实路径。

### 0.1 Node 运行时 / danmu_api：结论是「有，但要换实现」

TV 的运行时模块 = **node 二进制 + `danmu_api` 载荷**，node 起在 `:node` 进程，
再通过 `platform.Bridge.installDanmakuHost(...)` 把弹幕地址注入给网关里的 `ApiServer`。
iOS 要对齐这条，拆成两问：

| 问 | 结论 | 证据 |
|---|---|---|
| iOS 能跑 Node 吗 | **能，但不是官方 Node**。`nodejs-mobile` 有 iOS 预编译包（`v18.20.4-ios.zip`，49MB），它把 V8 **配成启动即关 JIT**（interpreter only），因此不需要 iOS 禁止的可执行内存 | `doc_mobile/BUILDING.md`：「using the v8 engine configured to start with JIT disabled」；runner 上已下载成功并解包（`_ios_node.yml`） |
| 包长什么样 | 是 **XCFramework**：`NodeMobile.xcframework/ios-arm64/NodeMobile.framework`（真机 arm64，**二进制 52MB**）+ `ios-arm64_x86_64-simulator`。对外只有一个 C 符号：`int node_start(int argc, char *argv[])`（头文件 `Headers/NodeMobile.h`） | `client/apps/ios/build/node/probe.txt`（CI 产物） |
| danmu_api 能在上面跑吗 | **能**。它是纯 ESM + Node 内置模块 + 纯 JS 依赖（`https-proxy-agent` / `dotenv` / `node-fetch` / `@dan-uni/dan-any`），没有运行期 native 模块；唯一的硬编码是**代理端口 5321**（TV 的载荷已改成可配、0=关） | 本机实测：`node danmu_api/server.js`（`DANMU_API_PORT=18401`、`DANMU_API_PROXY_PORT=0`）→ `/api/v2/fongmi/danmaku?name=庆余年&episode=1` 返回 200、33.8KB 弹幕、3.3s |
| 别的 JS 引擎行不行 | **不行（就现载荷而言）**：JavaScriptCore / QuickJS-ng / Hermes 都没有 ESM 与 Node 内置模块，而 danmu_api 是 ESM + `node:` 前缀导入 + `zlib`/`fs`/`http` | 这些引擎反而更适合「自己重写一份弹幕接口」而不是「跑现成载荷」 |

**接口已经现成**：宿主侧 `ApiServer` 认 `meow.danmaku.bundled` 系统属性
（`bundledDanmakuHost()`，与 TV 的 `Bridge.installDanmakuHost` 等价），
值形如 `http://127.0.0.1:<port>/api/v2/fongmi/danmaku?name={name}&episode={episode}`。
所以 iOS 侧只要：起 node → 探到端口 → 设这个属性（JNI 里一句
`System.setProperty` 即可），宿主立刻改用端内弹幕源。

**已落地 / 待落地**：

- 已落地：`client/apps/ios/scripts/prepare-node-runtime.sh`（下载 + 挑真机/模拟器
  两份 framework + 报告）、`.github/workflows/_ios_node.yml`（macOS runner 上
  核对 `node_start` 导出符号并做一次模拟器链接冒烟）。
- 待落地：framework 进 IPA 链接、`NodeBridge`（起 node / 探端口 / 设属性）、
  弹幕载荷（`node_modules` 113MB，**必须**像 TV 那样首启下载，不能打进包里）。

### 0.3 第二轮真机（2026-11-24 晚）：网关在导入完成后消失 + 开屏 + 全屏

第二轮日志（`launch.log` 里的 `[web] fetch ... failed after 2ms: Load failed`）说明
**不是请求慢，是端口没了**：`[import:merge] ... table now 59` 之后 jvm.log 再没有一行
请求，之后每个请求 2~6ms 就 `Load failed`（连接被拒），而 App 还活着。

| 问题 | 处理 |
|---|---|
| 端内 JVM 可能在导入完成后**整机退出**（`ApiServer.main` 起完监听就返回；JNI attach 的调用线程是 daemon，JDK 的 HTTP 分发线程从调用线程继承 daemon 属性） | `listen()` 起一条 **非 daemon 的守夜线程**（`meow-host-keeper`，只 sleep）：本进程活着期间 VM 不许退。桌面/Docker 行为不变 |
| 万一是分发线程被异常带走（VM 还活着但不再 accept） | 看门狗（daemon）每 **5s** 真发一次 `GET /api/_hb`；**连接被拒**（监听 socket 不在了）时 1.2s 后复核，仍不通就 `stop()` 旧监听器并**在原端口重绑**；正常时每 60s 打一行 `[hb] ok`（带时间戳，便于定位"哪一刻断的"）。「连上但没响应」= 线程池满，只记 `[busy]`，**不重绑**（会掐断在途视频流） |
| 导入到底卡在哪一步看不出来 | 后台导入多了 `phase`：`读取配置` → `下载并转换源 jar` → `写入配置`；结果里 `dex2jvm.ms` 给出转换耗时。端内转换顺带改成 **STORED 不压缩**（Zero 下 deflate 4MB 要十几秒），产物变大但更快 |

同时按真机反馈改的两处体验：

- **开屏页**：启动过程不再显示「正在启动 Java 宿主 / 等待 API 就绪」这类工程化文案，
  换成 TV 同款品牌屏（`#0B2220` 底 + 居中图标 + 轻微呼吸）；只有真的起不来
  （缺 web 资源 / 静态服务失败 / 网页加载失败）才显示一行原因 + 「重试」。
- **播放页全屏**：iPhone 的 WKWebView 拿不到元素全屏（`requestFullscreen` 要么被拒、
  要么"成功"但画面不变），所以 iOS 壳**直接走伪全屏**（容器 CSS 撑满）+ 通过新增的
  `meowIos.setOrientation` 桥让原生转横屏（`ShellOrientation.swift`，iOS 16+ 用
  `requestGeometryUpdate`）。进播放页的自动横屏沿用原有 `tryLandscape()`。

### 0.4 导入提速 + 弹幕运行时落地（2026-11-24 晚）

**导入为什么要动**：端内 dex2jar 在 Zero 解释执行下是桌面的 ~25 倍
（桌面实测 6.0s / 1.15MB jar → 端上 ~150s）。三条都上了：

| 做法 | 效果 | 备注 |
|---|---|---|
| **延迟转换** | 导入先落站点表、立刻回成功；jar 转换在同一后台线程里继续（`state=converting`，界面显示「源 jar 后台转换中…已 N 秒」） | 转换窗口内站点**不能从列表消失**（`importJarPending` → `siteImplemented` 放行配置里声明过的站）；App 被杀后启动会 `resumePendingJarConversion` 接着做 |
| **原生下载** | jar 走壳的 NSURLSession（JNI `RegisterNatives` → `platform.IosNative.download`），失败自动回退 JVM 的 HttpURLConnection | 只影响"新 jar"；下载只是搬运，没有平台语义 |
| **STORED 不压缩** | 端内 dex2jar 输出不压缩，Zero 下省掉 deflate 的十几秒 | 产物 1.82MB（原来 1.76MB 压缩版），读的时候也省 inflate |

**弹幕运行时（对齐 TV 的「运行时模块 + 载荷」）**：
- **运行时**：nodejs-mobile 的 `NodeMobile.framework`（真机 arm64、52MB 动态库）
  以 `embed` 方式进 IPA；对外只有 `node_start(argc, argv)` 一个 C 符号。
- **载荷**：TV/PC 用的同一个 `danmu-api-1.0.0.zip`（构建脚本
  `prepare-danmu-payload.sh` 解开成 `build/danmu-api` 打进 IPA，~40MB），
  首次启动拷到 Documents 再跑。
- **接法**：node 起在 `127.0.0.1:19321`（`DANMU_API_PORT`/`DANMU_API_BIND`/
  `DANMU_API_PROXY_PORT=0`/`SOURCE_ORDER` 与 TV manifest 一致）；探活通过后把
  `http://127.0.0.1:19321/api/v2/fongmi/danmaku?name={name}&episode={episode}`
  写进 `gateway/danmaku-host.txt`，宿主 `bundledDanmakuHost()` 每次请求重读这个文件。
  **为什么走文件而不是 JNI 设系统属性**：node 比 JVM 晚起，属性只能设一次、
  异常难兜底；文件是壳与宿主之间唯一不依赖 JNI 的通道。
- **失败兜底**：载荷没打进包 / node 起不来 / 端口不通 → 只写日志，宿主继续用
  **内置弹幕源**（弹弹play 官方 + 6 个镜像）。弹幕功能不受影响。

### 0.5 第三轮真机（2026-11-25）：断连横幅与前台唤醒

用户报两条：①顶部「后端连接中断」横幅压在顶栏上、看不清；②后端会短暂掉一下再自愈。

| 问题 | 真因 | 修法 |
|---|---|---|
| 横幅与顶栏/搜索栏叠字 | 横幅是 `Teleport` 到 body 的 `fixed`，`top` 只让开安全区、没让开顶栏；底色用的是 `--color-card`（深色下只有 **5.5% 白**），等于没有底 | 改成 **AppHeader 之后的流内元素**：自己占一行不压内容，滚动时 `sticky` 在顶栏下沿（`top: calc(var(--header-h) + 0.5rem)`）；底色换不透明 `--color-elevated`；文案压成「标题 + 自动重连」两行 |
| 后端掉了又"自己好了" | 两种都会命中同一组症状（fetch 全失败、图片全碎）：WKWebView 网络进程被回收；或端内网关短暂不可用。前端分不出来，旧文案却断言"系统回收了内核网络进程" | 文案改成中性「后端连接中断」；**横幅出现后每 2s 探 `/api/_hb`，通了自动收起**，不再要求用户点重载；出现前压 1.5s 复核，瞬时抖动不弹 |
| 断连窗口太长 | 看门狗 15s 一轮、连续 2 次才重绑 → 最多 30s；iOS 回前台时线程刚解冻，还要等下一轮 | 看门狗改 **5s**；连接被拒时 1.2s 复核后立即重绑；壳在 `scenePhase == .active` 时经 JNI 调 `ApiServer.kickWatchdog()` 立刻探活（`JvmBridge.kickWatchdog`） |
| 下次怎么判断是哪一种 | `launch.log` 只有零散的失败行 | `noteNetFailure()` 命中时额外写一行 `net-unhealthy: 5 次网络级失败/10s base=...`，与前后请求的错误原文一起看；网关侧对应 `[watchdog] connect failed / listener rebound` |

**仍然没有证据的部分**：用户这次没有提供 `launch.log` / `jvm.log`，所以「后端为什么掉」
尚未定位到具体成因。上面的改动是**把两种可能都缩短到可见窗口内并自动恢复**，
不是"已找到根因"。下次复现要取 `Documents/launch.log` 与 `Documents/jvm.log`。

### 0.6 第三轮真机（续）：点导航没反应 + 远程调试开关

用户补充：顶栏搜索 / AI / 直播、详情页「去搜索」、剧集点击全部没反应，
但同一页的纯前端按钮（切播放源胶囊）正常，设置页也进得去。

**分界就是判据**：不经过 router 的按钮正常、所有 `router.push` 都失败 =
懒加载路由 chunk 拉不到（不是 click 被挡）。iOS 的 web 资源由壳内 9979
静态服务提供；它在切后台/系统回收后可能不再 accept。Java 网关（9978）有看门狗
会自愈，静态服务此前没有 —— 所以 API 正常、页面里所有"要去新页面"的按钮全点不动。

修法：

- `main.ts` 的 `warmLazyRoutes()`：首屏后把 router 的全部懒加载 chunk 预热进内存，
  导航不再依赖 9979；
- `StaticServer.ensureServing()` + `Bootstrap.startStaticWatchdog()`：每 15s 探活
  `/index.html`，失效就在原端口重建；`scenePhase == active` 时也补一次。

**残留判定**：这次同样没拿到 `launch.log` 里的 `router:` 行，所以"静态服务失效"
是基于分界现象的推断。新包若再复现，先看「运行日志」有没有
`router: Failed to fetch dynamically imported module`：有 → 静态服务/chunk；
没有 → click 层（再查覆盖层与 pointer-events）。

**远程调试开关（对齐 TV）**：iOS 之前没有开关，且宿主默认绑 `0.0.0.0`（文档还写只绑回环）。
现在：

- 启动参数固定 `--bind=127.0.0.1`；设置页「维护 → 调试」可开关；
- 打开 → `policy.json` 写 `lanDebug=true` + 16 位 `lanToken`，宿主延迟 200ms
  重绑 `0.0.0.0`；关闭 → 清 token、重绑回环；
- 非回环请求（**含 GET**）全部要求 `X-Meow-Token`（或 `?token=`）；token 只在
  回环请求的 `GET /api/_debug/lan` 里下发，OPTIONS 放行给浏览器预检；
- 状态跨重启保留（`policy.json`）。接口：`GET/POST /api/_debug/lan`。

**播放页（2026-11-25 第三轮）**：

- **音量**：WKWebView 里 `video.volume = x` 静默无效。新增 `SystemVolume.swift`（MPVolumeView）
  写系统媒体音量，启动脚本注入 `meowIos.volume`（快照）与 `meowIos.setVolume`；
  底栏滑块、右侧竖滑、鼠标滚轮统一走 `applyVolumePct()`，不再出现"滑条动了声音不变"。
  读不到系统音量（-1）时保留「请用手机侧键」提示，且该浮层 1.2s 自动消失
  （旧版漏了定时器，浮层会常驻）。
- **点击语义**：iOS 强制按触屏处理（`isTouch` 加壳身份 + click 只信 pointerdown 来源），
  单击只呼出/收起控制栏，双击中区才暂停/播放，左右区快退/快进；不再出现"点一下画面就暂停"。
- **底栏高度**：渐变 padding 从 `pt-10/sm:pt-12` 压到 `pt-7/sm:pt-9`，下边距 `pb-2/sm:pb-2.5`。

**测试 IPA 要给侧载商店的链接**：Actions artifact 需要登录、且下载下来是 zip，
侧载商店用不了。`_ios.yml` 手动 dispatch 会把 IPA **直接上传**到独立的测试
Release `ipa-test`（固定文件名，每次覆盖），链接永远不变：

```
https://github.com/upchr/meow/releases/download/ipa-test/Meow-unsigned-test.ipa
```

- 上传用 workflow 的 `GITHUB_TOKEN`：它触发的事件不会再生成工作流（GitHub 防递归），
  所以**不会**误跑 sync-packages、不动 `packages.json`；
- 手动 dispatch 需显式勾选 **`publish_test`**（默认 false）；`nojvm` 变体不上传；
  `release-v*` 全量发版不碰这个通道，正式 Release 页也不会多出测试包；
- 本地**不需要**下载再上传（省 144MB 往返）：CI 的 bundle inventory 已核对
  条目数/体积/MeowBuild/关键库；需要深查再用 `gh run download`。
- 不要挂到 `release-v*` 正式 Release 的资产里。

---

::: warning 以下为「落地契约」原文（2026-11-17 口径）
写这份契约时仓库还没有 iOS 工程。**2026-11-24 起实现已进 main，以 §0 为准**；
下文保留契约原文，供对照「当初怎么定的/哪些被推翻」，不要再按「无 iOS 工程」理解。
:::

TV 端已经有完整的一套「壳」：`client/apps/tv` 是 Android WebView 壳，
内嵌同一份 Web 前端，另配端内网关与 dex 执行层。本文把同一套形态换算到 iOS，
并给出 IPA 的发版链路。**先读 [TV 接入](./tv-integration.md) 与
[构建与发版](./build-release.md)**，本文只写 iOS 的差量。

---

## 一、结论先行

1. **能对齐的**：同一份 Web 前端、同一个后端、同一个「壳 + JS 桥 + 设置页」形态，
   以及同一条 `tag → 构建 → draft Release → Publish → 清单` 发版节奏。
2. **对不齐的**：TV 的端内网关与 dex jar 执行在 iOS 上**曾经**没有对应物——没有 JVM、
   禁止动态加载可执行代码，iOS 壳只能连后端、不能端内跑源。
   ★ 这条已被 §0 推翻：`feat/ios-jvm` 用**静态链接的 OpenJDK 9 Zero**把同一份
   `ApiServer` 搬进了进程（无 JIT、不需要可执行内存），端内 dex→class 也用
   **端内 dex2jar 库**解决（不能 fork/exec）。TV 的 Node 模块是 Android APK，
   搬不了；`danmu_api` 那部分见 §3.7 与 §0 的「仍然缺」。
3. **真正的分水岭是签名**：没有 $99 账号，CI 只能产出**未签名 IPA**，用户必须用
   自己的 Apple ID 签（LiveContainer / SideStore / AltStore，免费账号 7 天有效）。
   所以 IPA **不是自更新通道**，`packages.json` 也不该收它。
4. **定位（2026-11-17 用户定，11-24 已落地）**：iOS 作为独立应用，
   **弹幕在包内跑 `danmu_api`，源站与播放在端内 `ApiServer` 执行**（见 §0）。

### 1.1 形态对齐的五个维度

> 下表是 2026-11-17 的初始契约；实际实现以 §0 为准（端内 JVM 与 Node 均已落地，
> 下表「无网关 / 无 dex / 无 Node」已过时）。

| 维度 | TV APK（现状） | iOS IPA（目标） |
|---|---|---|
| 载体 | 单 APK，`com.meow.tv` | 单 IPA，`com.meow.player.ios`（建议包名） |
| 内容 | 构建期把 `web/dist` 拷进 `assets/www`，WebView 加载本地页 | 构建期把 `web/dist` 拷进 `Meow/Web`，WKWebView 加载本地页 |
| 入口 | 后端地址（局域网扫描 / 扫码 / 手填）+ 可选端内自启 | 后端地址（手填 / 复用网页版局域网扫描）；**没有端内自启** |
| 能力 | 壳桥 `window.meowTv` + 端内网关 + dex + 原生播放器 | 壳桥 `window.meowIos`；**无网关、无 dex、无 Node**；播放走 WebKit / AVFoundation |
| 分发 | 固定 keystore 签名的 APK；应用内自更新 | Apple 证书签名的 IPA；用户侧签名安装，**无自更新** |

### 1.2 「独立 App」的三种定义（先对齐）

| 定义 | 现状能否 | 方案 |
|---|---|---|
| **独立安装**：一个 IPA，不装模块、不开浏览器 | ✅ | iOS 壳 + 远程后端 + 包内 `danmu_api` |
| **独立后端**：手机单机就能搜索/播放 | ❌ | 现有 Java/dex 后端搬不进来；要么用原生语言重写后端（月级，且丢 jar 源），要么放弃 |
| **独立于网络**：离线也能用 | ❌ | 源站/播放/弹幕都要网络；最多离线打开壳 |

**本项目采用第一种**：App 独立安装、独立启动；后端在 PC / NAS / Docker，
首次启动扫码或手填地址。这也是 Plex / Jellyfin / Emby 的 iOS 形态——
「独立 App」不等于「服务器在手机里」。

---

## 二、能力对照：哪些能对齐，哪些根本做不到

| 能力 | TV APK | iOS IPA（目标） | 判据 |
|---|---|---|---|
| 首页 / 搜索 / 详情 / 收藏 / 历史 | ✅ 同一份 Web 前端 | ✅ 同一份 Web 前端 | 前端不分端，只靠后端契约 |
| 播放 | WebView + ExoPlayer 原生兜底 | WKWebView `<video>`（HLS/MP4 原生）+ 可选 AVPlayer | iOS 的 HLS 由系统解，**不要引 hls.js** |
| 弹幕 | Web 覆盖层 + 端内 danmu_api | Web 覆盖层 + **包内 nodejs-mobile 跑 danmu_api**；后端弹幕作降级 | 见 §3.7；不装 Android 模块 |
| 端内跑源（spider jar / dex） | ✅ `LocalSpiderHost` | ❌ | iOS 无 JVM；且禁止动态加载可执行代码 |
| 端内网关（`ApiServer`） | ✅ `GatewayServer` 监听 9978 | ❌ | 同上；只能连局域网/公网后端 |
| Node 运行时模块（danmu_api） | ✅ `:node` 进程 + `tv-node` 模块 | ⭕ nodejs-mobile **进程内嵌 libnode** | 不走 APK/模块；见 §3.7 |
| 插件 / 模块安装 | PC 插件、TV 运行时模块（`tv-plugins-v*`） | ❌ | iOS 不装任何插件/模块；插件全部装在后端 |
| 播放地址嗅探 | ✅ `SniffActivity`（不可见 WebView 拦请求） | ❌ v1 不做 | 靠后端 `/api/parse` 或源直链；v2 再评估 WKWebView 嗅探 |
| 本地目录 / 缓存导出 | TV 内置浏览器 + SAF；PC Tauri dialog | ⚠️ v1 只支持后端片库（WebDAV/SMB） | iOS 本地目录要 `UIDocumentPicker`，v1 不做 |
| 跨端进度 / 收藏 | TV 自己的文件（与 Web 不互通） | 写后端，天然与 Web/PC 互通 | 连后端即得到跨端状态 |
| 局域网发现后端 | `LanScan`（原生 Socket） | 复用网页版 `lanScanWeb.ts`（fetch `/api/health`） | 后端 CORS 默认全放行，WebView 里可用 |
| 遥控 / 方向键导航 | 注入 `tv-nav.js` | 不需要；触摸 + 可选外接键盘 | 不设 `data-tv`，不注入 `tv-nav.js` |
| 远程调试 / MCP | `DebugServer` 9977 | ❌（不开放入站端口） | iOS 后台与网络权限都不适合做这个 |
| 应用自更新 | `AppUpdater` 下载 APK 交安装器 | ❌ 只能提示新版本并打开 Release 页 | iOS 不允许 App 自装 IPA |
| 全屏 | WebView + 系统全屏 | 壳本身无地址栏；横屏即全屏；`requestFullscreen` 在 iPhone 上不存在 | 不依赖 `webkitEnterFullscreen`（既有决策：会丢弹幕/控制栏） |
| 投屏 | DLNA 发送端 | 受限：iOS 的 SSDP 多播需要 Apple 特批 entitlement | 见 §3.5；失败要能手填 IP |
| 离线启动壳 | ✅ 前端在 APK 里 | ✅ 前端在 IPA 里；但数据仍要后端 | 只缓存静态资源，不缓存业务数据 |

**一句话**：能对齐的是「壳 + 同一份前端 + 同一个后端」，不能对齐的是「端内执行」。
后者不是没做，是平台禁止——即便自签侧载，JavaScriptCore 也补不出 Node 的
`net` / `fs` / 子进程能力。

### 2.1 为什么 iOS 跑不了 JVM / dex（不是实现选择）

先把「独立」拆成两件事：**独立安装的 App**（可以）与**独立后端**（不行）。
iOS 能装一个自己的 App，但装不了 TV 那套「端内后端」：

| 端内能力 | 依赖 | iOS 为什么没有 |
|---|---|---|
| `ApiServer`（Java 宿主） | JVM 17、`java.nio`、`URLClassLoader` | iOS 没有 JVM；AOT 也处理不了运行时 `Class.forName` / 动态导入 jar |
| `spider.jar`（class 形态） | JVM + 运行时 `URLClassLoader` | 同上；且 App Store 2.5.2 禁止下载/执行改变功能的代码 |
| dex 形态 jar | Android ART / `DexClassLoader` | ART 是 Android 专有；iOS 没有 dex 运行时 |
| `tv-node` 模块 | Android APK + libnode | iOS 不能装 APK、不能 `exec`；**但可以进程内嵌 nodejs-mobile**（只用于 danmu_api，见 §3.7） |
| 前端 / 弹幕覆盖层 | WKWebView / JavaScriptCore | ✅ 有，且不需要 JIT |

「用别的办法把 JVM 搬上去」也走过一遍，结论都不成立：

- **GraalVM native-image**：没有 iOS 目标；而且宿主的动态加载是
  `Class.forName` + `URLClassLoader`（883 个类），AOT 穷举不了反射入口——
  AGENTS 里已经明确不把它当构建方案。
- **RoboVM / Multi-OS Engine**：AOT 把 Java 编成原生，**不能运行时导入用户 jar**；
  两个项目都已停止维护。
- **Avian 之类的嵌入式 JVM**：不是完整 JDK，没有 `java.nio` / `URLClassLoader`
  这层能力；即使能跑，导入源包仍属「下载并执行代码」，撞 App Store 2.5.2。
- **自己写一个 dex 解释器**：研究级工作量、性能不可接受，且同样绕不过动态代码规则。
- **浏览器里的 JVM（CheerpJ / DoppioJVM / TeaVM）**：要么是 AOT（不能运行时加载
  jar），要么没有真实 socket / 文件 / 线程——`ApiServer` 要监听端口、读写文件、
  跑并发，网页沙箱给不了。
- **在 iOS 上跑 Linux 虚拟机再装 JDK（UTM / iSH）**：解释执行或依赖 JIT 侧载，
  体积、性能、商店规则三输；不是可交付的产品路径。
- **越狱 / TrollStore**：不提供 JVM/ART；TrollStore 在 iOS 17 后已失效；
  即使装上也绕不过动态代码与性能问题。

所以 iOS 的「独立」只能到「独立 App + 连后端」；要做到「完全无后端」，
等于用 Swift 重写 `ApiServer` + 源站适配层，并**放弃 jar 生态**（现有大多数站点
是 jar 源）——那是另一个产品，不是打包问题。TV 能端内自足，是因为 Android
有 ART + `DexClassLoader`；PC/Docker 能端内自足，是因为桌面有 JVM。iOS 两者都没有。

---

## 三、iOS 壳的运行时形态

### 3.1 页面加载：本地静态服务，不要 `file://`

TV 用 `WebViewAssetLoader` 把 `assets/www` 暴露成 `http://meow.local`，
原因写在 `client/apps/tv/app/build.gradle`：`file://` 下 localStorage、相对路径 fetch、
ES module 都会受限。iOS 没有 `WebViewAssetLoader`，等价物有两个：

| 方案 | 结论 |
|---|---|
| `loadFileURL(_:allowingReadAccessTo:)` | ❌ Vite 产物是 `<script type="module">`，`file://` 下模块脚本按 CORS 拒绝；localStorage 行为也不稳 |
| `WKURLSchemeHandler` 自定义 scheme | ⚠️ 可用，但 origin 不是 http(s)，部分 Web API 与存储语义和浏览器不一致，排查成本高 |
| **进程内 HTTP 静态服务 + `http://127.0.0.1:<port>`** | ✅ **推荐**：与 TV 的 `meow.local` 同构，localStorage / fetch / ES module 都按正常网页处理 |

实现建议（【待实现】）：

- 用 `Network.framework` 的 `NWListener` 或 `GCDWebServer` 起一个只读静态服务，
  只绑 `127.0.0.1`，端口由系统分配即可（CORS 对 `127.0.0.1` 任意端口放行，见 §3.3）。
- 根目录是 `Meow/Web`（构建期从 `web/dist` 拷入，`.gitignore` 掉）。
- 只处理 GET/HEAD；路径做 `..` 归一化，拒绝逃出根目录的请求（与后端静态服务的
  路径穿越防护同一条判据）。
- 进程进入后台时服务随进程挂起，回前台自然恢复；不申请后台常驻。

### 3.2 JS 桥：`window.meowIos`

TV 的桥是 `window.meowTv`（`TvBridge`）。iOS 用同名风格的对象，但**只保留最小面**：

```js
// 注入到 document start，页面脚本执行前就存在
window.meowIos = {
  platform: () => 'ios',
  version: () => '1.2.14 (1214)',          // versionName + versionCode
  bootstrap: () => '{"ok":true,"backend":"http://192.168.1.2:30010"}',
  saveConn: (url, token) => '{"ok":true}', // 原生写 Keychain / UserDefaults
  openExternal: (url) => '{"ok":true}',    // 交给系统浏览器 / Release 页
  share: (text) => '{"ok":true}',
}
```

- 静态值（`platform` / `version` / `bootstrap` 的后端地址）用
  `WKUserScript(.atDocumentStart)` 注入；动作类（`saveConn` / `openExternal`）用
  `WKScriptMessageHandlerWithReply`（iOS 14+）回 Promise。
- 原生侧只认白名单方法名，不 eval 网页传来的任意 JS。
- **不要**为了 iOS 新增 `html[data-ios]` 身份：
  [端与尺寸适配](./device-adaptive.md) 的四层判据（身份/形态/输入/方向，见 §1 判据全景）
  已在 1.2.15（e993204，2026-11-17）收口，再加一套只会绕开收口，
  让同一台 iPad 同时命中更多互相矛盾的判据。
  iOS 的触屏布局沿用现有 `(pointer: coarse)` + 竖屏断点；壳身份只通过
  `window.meowIos` 做**能力入口**，不做样式判据。

Web 侧需要改的只有三处（【待实现】，都是一行级）：

| 位置 | 改法 |
|---|---|
| `client/apps/web/src/api/index.ts` `resolveBase()` | 增加 `isIosShell()`，与 `isTvShell()` 并列，让裸 `meow.api` 被信任 |
| `client/apps/web/src/App.vue` | 加 `bootstrapIos()`：没有 `meow.api` 时调 `meowIos.bootstrap()`，失败只告警 |
| `client/apps/web/index.html` | 给 viewport 加 `viewport-fit=cover`（壳内刘海/圆角要读 `env(safe-area-inset-*)`，现有 CSS 已经在用） |

### 3.3 后端连接：iOS 的硬前提

TV 可以完全不连后端（端内自启），iOS 不行。所以「后端地址」是 iOS 首启的必填项：

- **手填**：设置页输入 `http://192.168.1.2:30010`（Docker）或 `http://<PC>:9979`（PC 插件）。
- **局域网扫描**：直接复用 `client/apps/web/src/utils/lanScanWeb.ts`。它靠
  `fetch http://<ip>:30010/api/health` 探活，后端 `/api/health` 带全放行 CORS，
  所以 WebView 里能跑；不需要再写一份原生 Socket 扫描。
- **二维码**：可选（TV 有 `PushServer` 扫码推送；iOS 可以用相机扫同一份二维码）。
- **令牌**：`X-Meow-Token`。原生把令牌放 Keychain，页面加载时注入
  `localStorage['meow.authToken']`（前端现有读取位置是 `api/index.ts`）。
  不要写进 URL。改令牌后要 reload 一次页面，网页侧不会主动感知原生设置变化。

**CORS 不是障碍（已核对实现）**：`ApiServer.corsAllowedOrigin()` 对
`localhost` / `127.0.0.1` / `[::1]` / `meow.local` 以及 `file://` 的 `null` 来源
**永远放行**，注释里写明了原因（TV 内置页与 `meow.local` 没法枚举进白名单）。
所以即使按 [公网部署](./public-deployment.md) 把 `MEOW_CORS` 收紧成具体域名或
`none`，壳从 `http://127.0.0.1:<port>` 发的请求仍然放行——**本地静态服务
（§3.1 推荐方案）在 CORS 上是最稳的**。自定义 scheme 的 origin 通常不在放行
列表里，收紧 CORS 后会被拦；`file://` 的 `null` 虽然被放行，但它本身加载不了
Vite 的模块脚本（§3.1），所以不是可选项。

### 3.4 播放

- `<video>` 在 WKWebView 里能原生吃 HLS（`hasNativeHls()` 已存在，
  `client/apps/web/src/components/player/mediaKind.ts`），MP4 也没问题。
- **不要引 hls.js 兜底**：iPhone 上 MSE 支持不完整，hls.js 只会把「能播」变成
  「黑屏 + 控制台报错」。HLS 交给系统，非 HLS 的 MKV/AV1 由后端转码解决
  （与 [播放与转码](./playback.md) 的转码决策一致）。
- iPhone 没有 `requestFullscreen`（既有结论）。壳本身没有地址栏，横屏后已经是
  全屏观感；全屏按钮在 iOS 壳里应隐藏或降级为「锁定横屏」，不要调
  `webkitEnterFullscreen`——它会切到系统播放器，弹幕/控制栏全丢。
- 原生 AVPlayer 只作为 **v2 兜底**（WebKit 解不了的编码）；一旦用原生播放器，
  弹幕覆盖层要重做，成本远高于收益，v1 不做。

### 3.5 局域网、投屏与权限

- 连局域网后端会触发 iOS 14+ 的「本地网络」权限弹窗，Info.plist 必须写
  `NSLocalNetworkUsageDescription`，否则请求静默失败。用户拒绝后要给可读提示，
  不能只显示「后端未连接」。
- ATS：壳内加载 `http://127.0.0.1` 通常不受限；连 `http://192.168.x.x` 属于明文
  HTTP，需要 `NSAllowsLocalNetworking` 或按域名配例外。**【待实测】** 具体哪一档
  够用，必须在真机上验；公网后端应要求 HTTPS，不要长期靠 `NSAllowsArbitraryLoads`。
- DLNA 投屏：iOS 对 SSDP 的 **多播/广播**要求
  `com.apple.developer.networking.multicast` entitlement，需要向 Apple 申请
  （不是普通开发者账号自带）。没有它，设备发现大概率收不到 M-SEARCH 响应。
  v1 的投屏入口应允许**手填设备 IP**，并把「发现不到」写成明确提示，
  不要让用户以为局域网里没有电视。
- Bonjour：后端目前不广播 mDNS，所以 iOS 侧也不需要声明 `NSBonjourServices`；
  等后端真的加了服务发现再补。

### 3.6 更新

iOS 不能自装 IPA。壳内「检查更新」只能做两件事：

1. 读 GitHub 最新 Release 的 tag（`releases/latest`，必要时走
   `gitAccel.ts` 的加速候选）比较版本号；**不要读 `packages.json` 的
   `meow-pc`/`meow-tv` 条目**——那是别的端的版本线，且清单里没有 iOS 条目；
2. 有新版本时调 `openExternal` 打开 Release 页面，提示用户按自己的签名方式重装。

**不要把 IPA 写进 `packages.json`**：那份清单是给 PC/TV 的自更新器用的，
iOS 没有对应的安装执行器；写进去只会让客户端尝试下载一个装不上的包。

### 3.7 弹幕与 Node/danmu_api：包内内置（已定）

先厘清 TV 的弹幕模块到底是什么：

- `client/apps/tv/runtime-node` 产出的是 **Android APK**（`tv-node-<abi>`），
  内部是 nodejs-mobile（libnode）+ JNI 桥，跑在 `:node` 进程；
  `danmu_api` 是随包分发的 Node 服务（构建目录 47MB，发布 zip 11.7MB，
  9000+ 个纯 JS 文件，manifest 里 `exec: node`）。
- 它之所以是「单装模块」，是因为 Android 允许装 APK、允许把 libnode 当 native 库
  加载；而且 TV 可能完全没有后端（端内网关自足）。
- **iOS 没有这条路径**：装不了 Android APK，也不能像桌面那样 `exec` 一个 node
  二进制（iOS 不允许 fork/exec 未签名的可执行文件，App 内也没有「装模块」这个概念）。

iOS 能选的三条路：

| 方案 | 做法 | 结论 |
|---|---|---|
| **A 后端** | 壳只调 `{后端}/api/proxy/danmaku*`；后端内置 iqiyi / tencent 直连，配了 `danmu-api` 插件就用它 | ⭕ **降级路径（保留）**：内置 Node 起不来时回退到这里 |
| **B 内嵌 nodejs-mobile + danmu_api 载荷（已选）** | 把 libnode 作为 framework 嵌进 IPA，随包带 47MB 载荷（zip 11.7MB） | ✅ **v1 采用**（2026-11-17 用户定）：下载包预计 +20–35MB、安装后 +70–90MB（估算）；无 JIT；载荷不能热更新；播放/源仍要后端 |
| C 原生 Swift 聚合器 | 只实现 iqiyi / tencent 等固定源的搜索与 XML 解析，不加载动态代码 | ⭕ v2 可选：体积小、可随 App 更新；但等于把后端已有的聚合逻辑再写一份，长期会漂移 |

B 还有两个非技术成本：① `danmu_api` 的 `LICENSE` 是 **AGPL-3.0**
（`package.json` 写的是 ISC，口径不一致），随 IPA 分发要单独做许可评估；
② 公开库**不含** `danmu_api` 源码（`research/upstream/` 是 gitignore 的上游参考），
公开构建里 `danmu-api` 插件本来就是跳过的，塞进 IPA 还要先解决源码分发问题。
这两条都不作为否决理由，只是「内置」的真实价格。

**决策（2026-11-17 用户定）：iOS 作为独立应用，v1 就内置 `danmu_api`——
用 nodejs-mobile 进程内嵌 libnode 跑它；不装 Android 模块，也不要求后端装
`danmu-api` 插件。后端弹幕保留为降级路径。**

- 后端本来就有降级链：`DANMAKU_PRIORITY = {"iqiyi", "tencent", "danmu_api", "aggregator"}`
  （`ApiServer.java`）；`server/build-plugin-danmu.ps1` 顶部写明「没装时 apiserver
  自动降级为官方源（爱奇艺直连）」。内置 Node 启动失败时，必须自动回退到这里。
- 内置只解决「弹幕」；**源站与播放仍然要后端**（搜索/详情/play 都在 ApiServer +
  spider jar 里，iOS 跑不了 JVM/dex）。如果「独立应用」指「完全不需要后端」，
  那不是打包距离问题，是要重写整个后端——当前架构下不做。
- 不要照搬 TV 的 `tv-node` / `danmu-payload`：TV 的模块版本线由
  `tv-plugins-v*` 和 `packages.json` 管理；iOS 把运行时和载荷作为 App 的一部分，
  没有「装模块」这一步，也不进 `packages.json`。

如果以后确实要做 C，先在文档里补两样再动手：**源适配器清单**（只做哪些站、
各自接口与签名方式）和**与后端聚合结果的一致性验收**；不要直接把 `danmu_api`
的 JS 搬进壳里跑。

#### 内置路径（已选）：四件事与三个硬点

技术上**不是不行**，但唯一可行的形态是 **进程内嵌 libnode（nodejs-mobile 的
iOS framework）**，不是把 TV 的 `tv-node` APK / `libnode.so` 放进 IPA
（Android 的 `.so` 与 iOS 的 framework 不是一回事，iOS 也不允许 `exec` 子进程）。

要做成，得同时完成四件事：

1. 在 macOS runner 上取/编 nodejs-mobile 的 iOS framework（arm64，V8 关 JIT），
   链接进 App；在专用线程调 `node::Start(argc, argv)` 跑 `danmu_api/server.js`。
2. 把 `danmu_api` 载荷（47MB / 9000+ 文件）作为 bundle resource 打进 IPA，
   首次启动定位/解压到 App 容器；Node 的 `fs` 只碰容器内路径。
3. 本地服务只绑 `127.0.0.1`（随机端口或 9321），加随机 token，
   只允许 App 自己的 WebView 调。
4. **契约适配（成本大头）**：前端调的是后端 `/api/proxy/danmaku*`
   （`search` / `list` / `xml` / `prefetch`），不是 danmu_api 的
   `/api/v2/fongmi/danmaku`。要么改前端支持一个独立的 `danmakuBase` 并适配响应，
   要么在本地写一层 `/api/proxy/danmaku*` 适配——后者还要把其余 `/api/*`
   转发给后端，等于半个本地网关。

三个同时存在的硬点：

| 硬点 | 事实 | 影响 |
|---|---|---|
| 无 JIT | iOS 第三方 App 不能 JIT，V8 只能解释执行 | 聚合解析变慢；不再是 TV 上 5ms 级 |
| 不能热更新 | 载荷在签名包里，改一个源就要重签 IPA | 免费账号 7 天重签；后端插件却可随时更新 |
| 分发/许可 | `danmu_api` 的 `LICENSE` 是 AGPL-3.0（`package.json` 写 ISC）；公开库不含源码 | App Store 分发有冲突风险；公开构建没有源码可打 |

估算：Node 嵌入 + 载荷打包 + 契约适配 + 许可评估 ≈ **1–2 周**（不含审核）。
这是 v1 的必做项，不是 v2；排期时放在「壳能连后端」之后、「发版 CI」之前，
并先做一次 1–2 天的最小验证（nodejs-mobile + danmu_api 本地跑通 + WKWebView fetch）。
内置 Node 起不来时必须回退到后端弹幕，不能把播放拖死。

### 3.8 TV 配套组件的逐项处置（齐全性检查）

TV 的组件清单以 [TV 接入](./tv-integration.md) 的 §7「端上配套组件」为准，
逐项给 iOS 结论：

| TV 组件 | iOS 处置 | 说明 |
|---|---|---|
| `SetupActivity`（连接设置） | ✅ 原生设置页 | 后端地址 / 令牌 / 扫描 / 关于 |
| `LanScan`（原生 Socket 扫描） | ✅ 复用 `lanScanWeb.ts` | WebView 里 fetch `/api/health`，不写原生扫描 |
| `PushServer`（手机扫码推送） | ⭕ v2 可选 | 相机扫码 + 同一份二维码 |
| `DebugServer` / MCP | ❌ | 不开放入站端口 |
| `NativePlayerActivity`（ExoPlayer） | ⭕ v2 兜底 | 先走 WKWebView 原生 HLS |
| `SniffActivity`（播放页嗅探） | ❌ v1 | 靠后端 `/api/parse` 或源直链 |
| `MetaMirror`（元数据/海报本地副本） | ❌ v1 | 海报走后端代理，断网时接受空白 |
| `CacheDownloader`（缓存导出 U 盘） | ❌ | iOS 无 U 盘/SMB 直写；缓存留在后端 |
| `UrlCheck`（`/api/proxy/*` 目标校验） | ❌ | 没有本地代理，校验在后端 |
| `AppUpdater`（APK 下载/安装） | ❌ | 只提示版本，打开 Release 页 |
| `ModuleInstallReceiver` / `RuntimeModuleHost` / `NodeService` | ❌ 模块安装；✅ 进程内 Node | 没有「装模块」；`danmu_api` 的 libnode 编译进 App，见 §3.7 |
| `LocalSpiderHost` / `LocalSites` / `LocalDns` | ❌ | 端内跑源与本地 DNS 都不做 |
| `GatewayServer`（端内 ApiServer） | ❌ | 只能连后端 |
| `TvBridge`（`window.meowTv`） | ✅ 对应 `window.meowIos` | 只保留最小桥面 |
| `tv-nav.js`（遥控导航） | ❌ 不注入 | iOS 用触摸；外接键盘走系统默认 |

这张表是「齐全性」的判据：**凡是依赖 Android 进程模型（APK 模块、libnode、
dex、SAF）的组件，iOS 一律不做**；凡是纯 Web 能力（搜索/播放/弹幕/片库/设置），
都通过同一份前端 + 后端获得。

---

## 四、工程结构（目标，【待实现】）

```text
client/apps/ios/
  Meow.xcodeproj
  Meow/
    App/                 # AppDelegate / SceneDelegate（或 SwiftUI App）
    Bridge/              # MeowBridge.swift（WKScriptMessageHandlerWithReply）
    Server/              # LocalWebServer.swift（127.0.0.1 静态服务）
    Settings/            # 后端地址、令牌、局域网扫描、关于
    Danmaku/             # NodeRuntime.swift + 内置 danmu_api 载荷（构建期注入，.gitignore）
    Web/                 # 构建期从 web/dist 拷入，.gitignore
    Resources/
      Info.plist
      Assets.xcassets
  Config/
    Version.xcconfig     # MARKETING_VERSION / CURRENT_PROJECT_VERSION
  Vendor/                # nodejs-mobile iOS framework（构建期取，.gitignore）
  exportOptions.plist    # 仅签名链需要（method=development|ad-hoc）
  README.md
```

约定：

- Bundle ID `com.meow.player.ios`（与 Android 的 `com.meow.tv`、PC 的
  `com.meow.player` 并列；**不要**复用 `com.meow.tv`）。
- 最低系统建议 iOS 15.0（`WKScriptMessageHandlerWithReply` 需要 iOS 14+；
  再低会增加没有收益的兼容分支）。落地时按目标用户拍板。
- `Meow/Web/` 与 `build/` 进 `.gitignore`；**不要**把前端产物提交进库
  （TV 的 `assets/www` 也是构建期同步，同一判据）。本地首次构建前要先
  `pnpm --filter @meow/web build` 并把 `dist` 同步到 `Meow/Web`（脚本待写）。
- `Info.plist` 必填：`NSLocalNetworkUsageDescription`、
  `UISupportedInterfaceOrientations`（竖屏 + 横屏）、
  `CFBundleShortVersionString=$(MARKETING_VERSION)`、
  `CFBundleVersion=$(CURRENT_PROJECT_VERSION)`、
  `ITSAppUsesNonExemptEncryption=false`（没有自定义加密）。
- `UIBackgroundModes` 只在**真的做了后台音频**时才声明；声明了没实现会被审核问，
  也会让系统误判后台行为。
- 不做入站服务：没有 DebugServer、没有 MCP；验收靠 Xcode 控制台 / `os_log`。

---

## 五、版本号与产物命名

沿用 TV 的两条硬规则（见 [构建与发版](./build-release.md)）：

1. **版本号只有一处真源**：`client/package.json`；
2. **构建号只能增大**：`versionCode = major*1000 + minor*100 + patch`
   （`1.2.14` → `1214`）。iOS 的 `CURRENT_PROJECT_VERSION` 用同一公式，
   这样三端构建号可以直接对比。

落地时把 `client/apps/ios/Config/Version.xcconfig` 加进
`deploy/scripts/bump-version.ps1` 的清单（`-Check` 也要能回读校验）；
在此之前不要手改版本，否则 `-Check` 会在 CI 的 `plan` 阶段失败。

产物命名固定：

| 形态 | 文件名 |
|---|---|
| 未签名（默认，无账号可出） | `meow-iOS-<version>-unsigned.ipa` |
| 已签名（$99 账号） | `meow-iOS-<version>.ipa` |

**`-unsigned` 必须留在文件名里**：用户下载时就要知道它还装不上，
不能靠文档解释。

**UI 版本与后端版本解耦**：IPA 内嵌的 `web/dist` 与壳同版本。后端升级不会
更新壳内前端（TV 同样如此）；iOS 用户要拿到新 UI 只能重装一次壳。
这是内嵌前端的固有代价，不是 bug——用户反馈「网页版有新功能、App 没有」时，
先比对壳版本与 `web-dist` 的构建 commit。

---

## 六、IPA 发版 CI（目标 `_ios.yml`）

### 6.1 和 TV 链的同构关系

| 环节 | TV（`_tv.yml`） | iOS（`_ios.yml`，目标） |
|---|---|---|
| runner | `ubuntu-latest` + JDK17 + Android SDK + Gradle | `macos-15` + 预装 Xcode |
| 取前端 | `download-artifact: web-dist` → `client/apps/web/dist` | 同一份 `web-dist` → `client/apps/ios/Meow/Web` |
| 签名材料 | `TV_KEYSTORE_B64` 等 4 个 secret | 未签名模式不需要；签名模式用 `IOS_CERT_P12_B64` / `IOS_PROFILE_B64` / `IOS_TEAM_ID` |
| 构建 | `gradle assembleRelease` | `xcodebuild -sdk iphoneos`（未签名）/ `archive + exportArchive`（签名） |
| 自检 | 包内 `assets/spider.jar` 可选、纯壳合法 | 包内 `Payload/Meow.app/Info.plist` 版本串、无源包文件 |
| 产物 | `meow-TV-<ver>.apk` | `meow-iOS-<ver>-unsigned.ipa` / `meow-iOS-<ver>.ipa` |
| 上传 | `actions/upload-artifact` | 同上 |

### 6.2 未签名链（默认，先落地这一条）

```yaml
name: _ios

on:
  workflow_call:
    inputs:
      version:
        required: true
        type: string
    outputs:
      artifact:
        description: 产出的 artifact 名
        value: ios-ipa

jobs:
  ios:
    name: iOS IPA
    runs-on: macos-15
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4

      - name: 取前端 dist
        uses: actions/download-artifact@v4
        with:
          name: web-dist
          path: client/apps/ios/Meow/Web

      - name: 准备内置 danmu_api 载荷
        shell: bash
        run: |
          set -euo pipefail
          # 公开库不含 danmu_api 源码；私有构建在 job 开始前注入这个目录
          test -f client/apps/ios/Danmaku/danmu_api/server.js
          test -d client/apps/ios/Danmaku/node_modules
          du -sh client/apps/ios/Danmaku

      - name: 选择 Xcode
        shell: bash
        run: |
          set -euo pipefail
          xcodebuild -version
          xcode-select -p

      - name: 计算构建号
        id: build
        shell: bash
        run: |
          set -euo pipefail
          V="${{ inputs.version }}"
          CODE=$(echo "$V" | awk -F. '{printf "%d", $1*1000+$2*100+$3}')
          echo "code=$CODE" >> "$GITHUB_OUTPUT"

      - name: 构建（未签名）
        shell: bash
        run: |
          set -euo pipefail
          cd client/apps/ios
          xcodebuild \
            -project Meow.xcodeproj \
            -scheme Meow \
            -configuration Release \
            -sdk iphoneos \
            -derivedDataPath build \
            MARKETING_VERSION="${{ inputs.version }}" \
            CURRENT_PROJECT_VERSION="${{ steps.build.outputs.code }}" \
            CODE_SIGNING_ALLOWED=NO \
            CODE_SIGNING_REQUIRED=NO \
            CODE_SIGN_IDENTITY="" \
            build

      - name: 打未签名 IPA
        shell: bash
        run: |
          set -euo pipefail
          APP=$(find client/apps/ios/build -type d -name 'Meow.app' -path '*Release-iphoneos*' | head -1)
          test -n "$APP"
          rm -rf out && mkdir -p out/Payload
          cp -R "$APP" out/Payload/
          (cd out && zip -qry "meow-iOS-${{ inputs.version }}-unsigned.ipa" Payload)
          ls -lh out/

      - name: 自检（版本串 + 无源包）
        shell: bash
        run: |
          set -euo pipefail
          IPA="out/meow-iOS-${{ inputs.version }}-unsigned.ipa"
          unzip -l "$IPA" | grep -q 'Payload/Meow.app/Info.plist'
          unzip -p "$IPA" 'Payload/Meow.app/Info.plist' > /tmp/Info.plist
          /usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' /tmp/Info.plist \
            | grep -qx "${{ inputs.version }}"
          ! unzip -l "$IPA" | grep -Eq 'spider.*\.jar|config\.sites\.json|node-payload-danmu\.zip'

      - uses: actions/upload-artifact@v4
        with:
          name: ios-ipa
          path: out/*.ipa
          if-no-files-found: error
```

关键点：

- `CODE_SIGNING_ALLOWED=NO` 只对**未签名构建**成立；产物是 `.app` 打的 zip，
  不是可安装的签名 IPA。
- 版本号通过 `xcodebuild` 的构建变量覆盖，不在仓库里写死；`Version.xcconfig`
  只提供默认值，`bump-version.ps1` 改它。
- 自检里的源包黑名单与 `release.yml` 的公开壳守卫同口径（`spider*.jar`、
  `config.sites.json`、`node-payload-danmu.zip`）。
- `Danmaku/` 载荷在公开仓库里不存在（源码在 `research/upstream/`，gitignore）；
  公开 CI 跑到「准备内置 danmu_api 载荷」会失败。要么**私有构建**，
  要么在 job 里从私有 artifact/submodule 注入，并把 `Danmaku/` 加进 `.gitignore`。

### 6.3 签名链（有 $99 账号才启用）

额外步骤：

1. `security create-keychain` / `import` 导入 `.p12`；
2. 把 `.mobileprovision` 放进 `~/Library/MobileDevice/Provisioning Profiles/`；
3. `xcodebuild archive`（带 `DEVELOPMENT_TEAM` / `PROVISIONING_PROFILE_SPECIFIER`）；
4. `xcodebuild -exportArchive -exportOptionsPlist exportOptions.plist`，
   `method` 取 `development` 或 `ad-hoc`（**不要**用 `app-store`，见 §7）；
5. 产物去掉 `-unsigned` 后缀。

`exportOptions.plist` 的 `method` 决定签名类型：`development` / `ad-hoc` 都要求
目标设备已注册（ad-hoc 每年每种设备类型 100 台）；`app-store` 需要 App Store
Connect 与审核。签名链不要把证书/描述文件写进仓库，只走 GitHub Secrets。

### 6.4 接进 `release.yml`（【待实现】的改动点）

TV 的接法在 `release.yml` 里是「`plan` 判定范围 → `tv` job 调 `_tv.yml` →
`publish` 收产物」。iOS 照抄，改这几处：

```diff
 on:
   push:
     tags:
       - 'release-v[0-9]*'
       - 'tv-v[0-9]*'
+      - 'ios-v[0-9]*'
       - 'pc-v[0-9]*'
@@
   plan:
     outputs:
+      ios: ${{ steps.scope.outputs.ios }}
@@
           case "$TAG" in
-            release-v[0-9]*)    set_out tv true;  set_out pc true;  set_out plugins true;  set_out docker true;  set_out tvplugins false; set_out release true;  set_out plugins_only '' ;;
+            release-v[0-9]*)    set_out tv true;  set_out pc true;  set_out plugins true;  set_out docker true;  set_out tvplugins false; set_out release true;  set_out ios true;  set_out plugins_only '' ;;
+            ios-v[0-9]*)        set_out tv false; set_out pc false; set_out plugins false; set_out docker false; set_out tvplugins false; set_out release true;  set_out ios true;  set_out plugins_only '' ;;
             tv-v[0-9]*)         set_out tv true;  set_out pc false; set_out plugins false; set_out docker false; set_out tvplugins false; set_out release true;  set_out plugins_only apiserver ;;
@@
   web:
-    if: ${{ needs.plan.outputs.tv == 'true' || needs.plan.outputs.pc == 'true' || needs.plan.outputs.docker == 'true' }}
+    if: ${{ needs.plan.outputs.tv == 'true' || needs.plan.outputs.pc == 'true' || needs.plan.outputs.docker == 'true' || needs.plan.outputs.ios == 'true' }}
@@
+  ios:
+    name: iOS IPA
+    needs: [plan, web]
+    if: ${{ needs.plan.outputs.ios == 'true' }}
+    uses: ./.github/workflows/_ios.yml
+    with:
+      version: ${{ needs.plan.outputs.version }}
@@
   publish:
-    needs: [plan, web, tv, pc, plugins, docker, tv_plugins, fpk]
+    needs: [plan, web, tv, pc, plugins, docker, tv_plugins, fpk, ios]
@@
               && needs.tv.result != 'failure' && needs.tv.result != 'cancelled'
+              && needs.ios.result != 'failure' && needs.ios.result != 'cancelled'
@@
-          find dist -type f \( -name '*.apk' -o -name '*.exe' -o -name '*.zip' -o -name '*.fpk' \) -exec cp {} upload/ \; || true
+          find dist -type f \( -name '*.apk' -o -name '*.exe' -o -name '*.zip' -o -name '*.fpk' -o -name '*.ipa' \) -exec cp {} upload/ \; || true
@@
             | `meow-TV-*.apk` | TV 空壳 |
+            | `meow-iOS-*-unsigned.ipa` | iOS 空壳（未签名，需自行签名安装） |
```

还要改一处：`deploy/scripts/sync-packages.ps1` 的 `$scope` 是 tag 前缀白名单，
新增 `ios-v*` 必须显式返回空数组，否则会落到
`default` 分支去推断「pc/tv/plugins」：

```diff
 $scope = switch -Regex ($tag) {
     '^docker-v[0-9]'    { @() }
+    '^ios-v[0-9]'       { @() }   # iOS 不进 packages.json（无自更新）
     '^tv-v[0-9]'        { @('meow-tv') }
```

其余 tag 的 case 建议一并补 `set_out ios false`。漏了不致命（空值不等于 `'true'`），
但 `plan` 的输出会少一个字段，排查 tag 范围时容易误判。

接法分两步：**先只接 `ios-v*` 单链**（`release-v*` 那行的 `set_out ios true`
等真机验收通过后再加），避免第一次就把 macOS 构建拖进所有端的全量发版；
`workflow_dispatch` 目前没有 ios 输入，要手动发就补一个布尔输入，或直接打
`ios-v*` tag。

另外两处可选同步：

- `deploy/units.json` 的 `chains` 增加 `ios`（`runner: macos`）——它是构建单元的
  文档真源；`release.ps1` 目前只显式读 `chains.pc` / `chains.tv`，加 key 不影响本地脚本。
- `deploy/README.md` 的 tag 表、secret 表、runner 计费表各加一行 iOS。

### 6.5 成本与时长（估算，未实测）

- **额度**：公开仓库的标准 GitHub-hosted runner（含 macOS）不计分钟；若仓库转私有，
  macOS runner 按 **10×** 分钟计费。本仓库当前是公开仓库，所以额度不是主要问题，
  主要成本是**墙钟时间**。
- **时长**：首次冷构建（Xcode 编译 + 归档 + 打包）约 6–10 分钟；命中缓存后约 2–4 分钟。
  数字【待实测】，不要写进用户文档。
- 缓存 `client/apps/ios/build` 或 `~/Library/Developer/Xcode/DerivedData` 时，
  key 要包含项目管理文件与 Swift 源码的 hash；不要把 `MARKETING_VERSION` 放进 key
  （每版都会变，缓存永远不命中——PC 的 Rust 缓存踩过同款坑，见 `_pc_shell.yml`
  的「计算 Rust 依赖指纹」注释）。

---

## 七、签名与分发矩阵（这是 iOS 最硬的一堵墙）

| 方式 | 无 $99 | CI 能出可安装包 | 用户怎么装 | 有效期 | 结论 |
|---|---|---|---|---|---|
| 未签名 IPA | ✅ | ❌（只能出 `.app` 的 zip） | 自己用 AltStore / Sideloadly 签 | 7 天（免费 Apple ID） | **默认路径**；必须写明“装不上” |
| 免费 Apple ID 自签 | ✅ | ❌（证书在用户电脑上） | 电脑跑 AltServer / SideStore | 7 天，最多 3 个自签 App | 能验证，不适合长期分发 |
| $99 开发者账号（development / ad-hoc） | ❌ | ✅ | 拖 IPA 到设备 / 第三方工具 | 证书 1 年；ad-hoc 设备需注册 | 可做小范围内测 |
| $99 + TestFlight | ❌ | ✅ | TestFlight App | 构建 90 天 | 内测最顺，但要走 App Store Connect |
| App Store | ❌ | ✅ | App Store | 长期 | **不做**：影视聚合类审核风险高（版权/内容来源） |
| 企业证书 $299 | ❌ | ✅ | 任意安装 | 1 年 | **不做**：公开分发必被吊销 |

三条必须写进文档/弹框的口径：

1. **iOS 没有“ad-hoc 自签名”这回事**。macOS 可以 `codesign -s -` 出实验性 dmg，
   iOS 不认自签证书；能装的签名一定来自 Apple（免费 Apple ID / 开发者账号）。
   把 macOS 的经验套到 iOS 上会得出“CI 能出安装包”的错误结论。
2. **7 天重签是产品问题，不是技术细节**。免费账号每 7 天要重新签一次，
   用户不做就闪退；这与“用户分不清，只会点读取”的既有判断直接冲突。
3. **不要承诺“下载即装”**。未签名 IPA 的文件名必须带 `-unsigned`；
   文档、Release 说明、设置页提示三处口径一致。

---

## 八、验收

### 8.1 CI 能验的（机器门禁）

| 断言 | 判据 |
|---|---|
| 编译通过 | `xcodebuild ... build` exit 0 |
| 包结构 | IPA 里有 `Payload/Meow.app/Info.plist` |
| 版本串 | `CFBundleShortVersionString` == `client/package.json` |
| 构建号 | `CFBundleVersion` == `major*1000+minor*100+patch` |
| 无源包 | 包内没有 `spider*.jar` / `config.sites.json` / `node-payload-danmu.zip` |
| 产物名 | `meow-iOS-<version>-unsigned.ipa`（或签名版去掉后缀） |
| 沙盒启动（可选） | `xcrun simctl boot / install / launch` 后进程存活、截图非纯白 |

模拟器能验证「App 起得来、WKWebView 不白屏、桥注入成功」，但**验不了**：
真机安装签名、本地网络权限、HLS 硬解、后台音频、刘海安全区、DLNA 多播。

### 8.2 必须真机验的清单（【待实测】）

| # | 场景 | 期望 |
|---|---|---|
| 1 | 首次启动，未配后端 | 显示「后端未连接 / 去设置」，不白屏、不闪退 |
| 2 | 手填局域网后端 | `/api/health` 返回 `ok=true`，首页出内容；杀进程重启仍保留地址 |
| 3 | 局域网扫描 | 能扫到后端；首次弹「本地网络」权限；拒绝后有可读提示 |
| 4 | 搜索 / 详情 / 分页 | 与同后端 Web 端结果一致 |
| 5 | HLS 播放 | 起播、暂停、seek、切集；横屏后控制栏/弹幕不被刘海遮挡 |
| 6 | 非 HLS（MKV） | 端内 VLCKit 直解能播；不能播时提示可读，不黑屏转圈 |
| 7 | 弹幕 | 开关、透明度、手选源、切集后重新拉取 |
| 8 | 收藏 / 历史 | 写后端；同账号 Web 端可见（跨端状态） |
| 9 | 后台 / 锁屏 | 记录实际行为（继续播 / 暂停），按结果决定是否声明 `UIBackgroundModes` |
| 10 | 更新提示 | 应用内弹框给 IPA 直链与侧载商店源链接（安装仍需重签） |
| 11 | 投屏 | DLNA 发现（受 entitlement 影响）；失败可手填 IP |
| 12 | 旋转 / 安全区 | 竖屏、横屏都无内容被裁；`viewport-fit=cover` 生效 |
| 13 | 内置弹幕 | 后端**不装** danmu-api 插件时仍出弹幕；人为让内置 Node 启动失败 → 自动回退后端弹幕，不阻塞播放 |
| 14 | 内置 Node 性能 | 记录冷启动耗时、聚合搜索耗时、常驻内存、发热；JIT-off 下可接受才保留 |

### 8.3 发版前用户文档的同步清单

一旦真的发布 iOS 包，以下页面必须同时改（现在不改，因为还没有包）：

- `site/docs/reference/compatibility.md` 的「端支持」矩阵：iOS 从「⭕ 浏览器」改为
  「✅ 侧载 IPA（未签名/自签）」；
- `site/docs/guide/quick-start.md` 路线表与 `guide/faq.md` 的「没有 iOS App」；
- `site/docs/guide/web-docker.md` 的「在 iPhone 和 iPad 上」；
- `site/docs/reference/downloads.md` 增加 IPA 资产与「需要自行签名」的说明；
- `site/docs/reference/limits.md` 增加 iOS 的签名/更新/播放边界。

否则会出现「用户文档说没有 App、下载页却挂着一个 IPA」的自相矛盾。

---

## 九、落地顺序（编号，等点单）

1. **工程骨架**：`client/apps/ios/` + Xcode 工程 + 本地静态服务 + `window.meowIos`
   最小桥 + 设置页（后端地址/令牌/扫描）（≈2–3 天）。
2. **Web 侧接入**：`resolveBase` 认 `meowIos`、`bootstrapIos()`、
   `viewport-fit=cover`、弹幕请求支持独立 `danmakuBase`（≈1 天，改完跑
   `vue-tsc` + 相关单测）。
3. **内置 danmu_api（v1 必做）**：nodejs-mobile iOS framework + 载荷打包 +
   本地服务 + `/api/proxy/danmaku*` 契约适配 + 后端降级（≈1–2 周；先做
   1–2 天最小验证，见 §3.7）。
4. **未签名 IPA 链**：`_ios.yml` + `release.yml` 的 `ios-v*` 分支 +
   `sync-packages.ps1` 的 `ios` 空范围（≈1 天；**先只发 `ios-v*`，全量的
   `release-v*` 分支等第 5 步通过后再加**）。
5. **真机验收**：一台 iPhone（或 iPad）+ 免费 Apple ID，按 §8.2 逐项过；
   没有真机就停在「未签名构建 + 模拟器冒烟」，**不要对外发布**。
6. **可选签名链**：有 $99 账号后再加 `_ios.yml` 的签名分支与 secrets；
   先出 development/ad-hoc 内测包，再评估 TestFlight。
7. **可选增强**：原生 AVPlayer 兜底、后台音频、DLNA entitlement、二维码推送、
   原生 Swift 弹幕聚合器（只在放弃内置 Node 时才考虑）。

每一步的退出判据：

| 步 | 退出判据 |
|---|---|
| 1 | 模拟器启动后首页能渲染，`window.meowIos.platform()` 返回 `ios` |
| 2 | `resolveBase()` 在壳里取到 `bootstrap` 给的后端；Web 端行为不变 |
| 3 | 后端**不装** danmu-api 插件时仍出弹幕；杀掉内置 Node → 自动回退后端弹幕；冷启动/内存有记录 |
| 4 | `ios-v1.2.14` 能产出 `meow-iOS-1.2.14-unsigned.ipa` 并进 draft Release |
| 5 | §8.2 的 1–13 全过；14 有数据 |
| 6 | 签名 IPA 在真机装得上、能覆盖安装同签名版本 |

---

## 十、红线

| 不要 | 原因 |
|---|---|
| 把 Android 的 `tv-node` / `libnode.so` 直接塞进 IPA | 格式/ABI 不兼容；iOS 只能进程内嵌 nodejs-mobile 的 iOS framework（§3.7） |
| 内置 Node 起不来却没有后端弹幕降级 | 弹幕会把播放页拖死；必须回退（§3.7） |
| 给 iOS 做「模块安装」入口 | iOS 没有 Android APK 模块体系；弹幕/插件都在后端 |
| 把 IPA 写进 `packages.json` | iOS 没有自更新执行器；下载了也装不上 |
| 在未签名产物上去掉 `-unsigned` | 用户会以为能直接安装 |
| 把证书/描述文件/`.p12` 提交进 git | 与 TV keystore 同级别的红线；泄露只能换证书 |
| 用 `file://` 加载 `web/dist` | ES module / localStorage / fetch 全受限（TV 的 build.gradle 已记录同款坑） |
| 新增 `html[data-ios]` 身份 | 判据已在 1.2.15（e993204）收口为四层（身份/形态/输入/方向，见 [端与尺寸适配](./device-adaptive.md) §1），新增会绕开收口 |
| 引 hls.js 兜底 iPhone | MSE 支持不完整，只会把可播变成黑屏 |
| 用 `webkitEnterFullscreen` 做全屏 | 既有决策：切系统播放器会丢弹幕/控制栏 |
| 承诺“支持 iOS”而不写真机验收结论 | 没有 iPhone 就验不了签名、本地网络、HLS、安全区 |
| 把 iOS 构建塞进全量 `release-v*` 作为第一步 | 先跑单链 `ios-v*`；macOS 墙钟时间长，全量链会拖慢所有端 |

---

## 相关

- TV 壳、网关、dex 加载与签名：[TV 接入](./tv-integration.md)
- tag / 构建单元 / Release 与清单：[构建与发版](./build-release.md)
- 端身份与尺寸判据（为什么不再加一套）：[端与尺寸适配](./device-adaptive.md)
- 后端鉴权与 CORS：[公网部署与访问控制](./public-deployment.md)
- 播放与转码（iOS 不做端内解码的兜底）：[播放与转码](./playback.md)
