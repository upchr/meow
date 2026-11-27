# 三端统一架构

> 定位：**当前架构与决策记录**。写代码里已经成立的结构、边界与约束，不写改造过程。
> 「当前」陈述以 `server/host/src/shared/` 与 `client/apps/web/src/api/index.ts` 为准；
> 生态占比一类外部事实来自早期抽样，本仓库无法复核，标「待确认」；行号只是定位辅助，按符号名 grep。
> 相关：[API 契约](./api-contract.md)、[配置系统](./config-system.md)、[TV 接入](./tv-integration.md)、[播放](./playback.md)、[测试](./testing.md)。

---

## 目录

- [第零章 结论先行](#第零章-结论先行)
- [第一章 分层与目录](#第一章-分层与目录)
- [第二章 站点注册与分派](#第二章-站点注册与分派)
- [第三章 三个出口](#第三章-三个出口)
- [第四章 运行时与解析](#第四章-运行时与解析)
- [第五章 三端协作](#第五章-三端协作)
- [第六章 安全与运维](#第六章-安全与运维)
- [第七章 已知坑与约束](#第七章-已知坑与约束)
- [第八章 决策记录](#第八章-决策记录)
- [附录 A 关键数据结构](#附录-a-关键数据结构)
- [附录 B 术语表](#附录-b-术语表)

---

## 第零章 结论先行

### 0.1 一句话架构

> **一个内核（JVM 宿主）+ 一份配置（CatVod 语义）+ 一套状态（后端持久化）+ 三个渲染出口（TV/PC/Web）。**

内核只有一份实现：`shared/ApiServer.java`（约 1.3 万行）+ `routes/` 7 个、`core/` 15 个、
`sources/` 4 个模块。TV 壳把同一份 `shared/` 编译进去，两端分叉压在 `platform/` 五个类里。

### 0.2 四条判断

**判断一：统一的是配置与内核，不是客户端代码。**

TV 是 Fongmi 派生代码（`com/github/catvod/`）加自有壳（`com.meow.tv`），Web/PC 是自研
Vue + 我们的宿主。两端共享的是**同一份 spider jar + 同一份 config 语义 + 同一套状态后端**，
不是同一份 UI 代码——让 Fongmi 改走 REST 等于重写 catvod 模块并引入 GPL 传染，收益为负。

**判断二：我们的生态位是「上游」，不是「替代品」。**

Fongmi（客户端内 DexClassLoader）与 zyfun（客户端内解释适配器）都需要一个
「能跑 jar 又能跨端」的服务——我们在这里。zyfun 的 `T4_CATVOD` 本来就是为
「外部 CatVod 服务」设计的：指向我们的 `/{site}/{action}` 即可。零成本分发，
入口要选对（「影视配置」加站点，不是「数据导入」，见 §5.4）。

**判断三：源是单位，运行时是插件，配置是唯一接口。**

生态里一个适配器覆盖几十到上百个源（早期抽样：`XBPQ` 覆盖 94 个、`drpy2.min.js` 覆盖
221 个，**待确认**）。所以抽象层落在「源」上：站点以 `key` 寻址、`ext` 是每站参数、
实现可以是 jar 类、drpy 脚本、苹果CMS 直连或宿主原生源——调用方只看 `/api/{site}/{action}`，不关心背后是谁。

**判断四：同一份宿主源码，两端编译。**

`client/apps/tv/app/build.gradle` 只往 `java.srcDirs` 加 `server/host/src/shared` 一个目录，
不需要任何 `exclude`。代价是平台差异（HTTP 传输、类加载、Context、日志）必须全部收进
`platform/` 注入点，见 §1.4。

### 0.3 四层骨架

```
┌─────────────────────────────────────────────────────────────────────┐
│  L4 渲染层（三套，不统一）                                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │ TV/遥控器     │  │ PC (Tauri 2) │  │ Web (Vue)    │              │
│  │ ExoPlayer    │  │ WebView2     │  │ hls.js       │              │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘              │
└─────────┼─────────────────┼─────────────────┼──────────────────────┘
          │ CatVod config   │ REST            │ REST
          │ + dex jar       │                 │
┌─────────┼─────────────────┼─────────────────┼──────────────────────┐
│  L3 出口层（三种形状，一个来源）                                      │
│  /config.json + /spider-latest.jar │ /api/{site}/… │ /api/state/…   │
└─────────┼─────────────────┼─────────────────┼──────────────────────┘
┌─────────┴─────────────────┴─────────────────┴──────────────────────┐
│  L2 内核层（唯一真相来源）                                            │
│  ApiServer：站点表 + 分派 + 归一化 + 代理/弹幕/离线缓存               │
│  routes/ 7 模块 · sources/ 4 模块 · core/ 15 模块                    │
└───────────────────────────┬───────────────────────────────────────┘
┌───────────────────────────┴───────────────────────────────────────┐
│  L1 执行层（按 api 形态分派，顺序敏感）                                │
│  Java jar(csp_*) │ drpy(.js/.py) │ 苹果CMS 直连 │ 宿主原生源          │
└───────────────────────────────────────────────────────────────────┘
```

**⚠️ L2 里没有 `SiteRegistry` / `ParseEngine` / `ConfigTables` / `ProxyRouter` 这四个类。**
实际落地的是「一个 `ApiServer` + 路由/核心/源三个包」，不要按老名字找；
对应能力见 §2.1、§2.3、§3.2、§4.4。

---

## 第一章 分层与目录

### 1.1 分层职责（严格边界）

| 层 | 职责 | 实际实现 | 禁止 |
|---|---|---|---|
| **L1 执行层** | 把源站数据转成 CatVod JSON | jar 类（`csp_*`）、drpy（外部 Node）、`sources/CmsSource`、`sources/NativeSource` | 不碰 UI、不碰配置解析 |
| **L2 内核层** | 注册、分派、归一化、代理路由 | `ApiServer` + `routes/` + `core/` + `sources/` | 不直接拼 UI 形状 |
| **L3 出口层** | 按消费者习惯转换形状 | `/config.json`、`/api/*`、`/api/state/*` | 不含业务逻辑 |
| **L4 渲染层** | UI、播放器、交互 | TV / PC / Web 三套 | 不直连源站 |

**硬规则：L2 只依赖接口，不依赖具体运行时。** `call()` 按 `api` 形态决定交给原生源、
CMS 直连、脚本运行时还是 jar；具体类加载走 `platform.Bridge.loadClass`，
内核里不出现 `DexClassLoader`。

### 1.2 目录实况

```
server/host/src/
├── shared/                      ← TV 也编译这一份
│   ├── ApiServer.java           入口 + 路由表 + 分派 + 归一化（约 1.3 万行）
│   ├── platform/                5 个类：HttpServer / HttpExchange / Headers /
│   │                            ServerProvider / Bridge（两端差异的唯一注入点）
│   ├── core/                    15 个：DataPaths / ConfigText / Doh / UrlSafety /
│   │                            Jars / Dex2Jvm / Library / PosterStore / Scrape /
│   │                            Transcode / VideoCache / Cast / CastLan / CastReceiver /
│   │                            LogBuffer
│   ├── routes/                  7 个：Api / StateRoutes / LibraryRoutes / CacheRoutes /
│   │                            HealthRoutes / LogRoutes / LlmRelay
│   └── sources/                 4 个：CmsSource / DoubanSource / NativeSource(s)
├── jvm/                         JvmHttpServer（包装 com.sun.net.httpserver）
├── android/                     52 个 android.* stub（给 jar 内代码编译用）
├── com/github/catvod/           bean / crawler / net 等编译桩
├── Main.java / Test.java / SpiderHost.java
```

| 组件 | 位置 | 规模（实测） |
|---|---|---|
| **Web 前端** | `client/apps/web/` | 120 个 `.vue`/`.ts`，15 条路由（含投屏接收页 `/ext-play`） |
| **PC 壳** | `client/apps/desktop/` | Tauri 2，`productName`「猫窝」 |
| **TV 壳** | `client/apps/tv/` | 73 个 java 文件，`minSdk 26`，Media3 ExoPlayer 1.4.1 |

### 1.3 启动序列

```
1. DataPaths：-Dmeow.data > MEOW_DATA > --data-dir=... > ./data
2. boot()
   snapshotBaseline()    ← 必须在 loadMultiInstances 之前拍
   loadMultiInstances()  ← 读 data/config.sites.json，叠加多实例
   Jars.reload()         ← 外部 spider jar
   载入健康表 / 搜索缓存 / 弹幕磁盘清单
3. listen(bind, port, threads)
   platform.ServerProvider.autoDetect() → jvm.JvmHttpServer / SocketHttpServer
   JVM：0.0.0.0:9979，16 线程（MEOW_PORT / MEOW_BIND 可覆盖）
   TV ：127.0.0.1:9978，24 线程（见 §5.3）
```

**四个约束：**

1. **基线必须在 `loadMultiInstances()` 之前拍。** 它会把多实例塞进 `SITES`，晚拍就会把
   「配置导入的站」当内置基线，替换式导入后永远删不掉。
2. **站点懒加载。** 首次访问才建实例（按站点加锁）；全量初始化会拖慢启动并刷一屏错误。
3. **端口先开、预热异步。** TV 真机实测：同步预热 94 秒且期间端口不监听，预热也不避免崩溃。
4. **jar 缺失不是「服务没起来」。** `health.ok` 的判据是 `(spiderJar || nativeSources > 0) && configLoaded`；
   只有原生源时也能出榜单/详情，`ok` 必须为真，否则前端会把整个后端判成离线。

### 1.4 平台差异（改这块之前必须知道）

| 差异 | 为什么要绕 | 怎么绕 |
|---|---|---|
| `com.sun.net.httpserver` | Android 没有；JVM 又不许自带同名包（javac 报「程序包已存在于另一个模块中: jdk.httpserver」） | 两端都向 `platform.HttpServer` / `HttpExchange` 适配：JVM 用 `JvmHttpServer`，Android 用 `SocketHttpServer`（ServerSocket + 手写报文解析） |
| `java.net.http` | `android.jar` 里 0 条 | 全仓 OkHttp（后端 `okhttp-3.14.9.jar`，TV 4.12.0） |
| `Class.forName` / `DexClassLoader` | JVM 从类路径找，ART 只认 `classes.dex` | `platform.Bridge.installClassLoader` 注入 |
| `android.content.Context` | 真实 `Context` 是抽象类、构造器 protected，反射 `newInstance()` 抛 `InstantiationException` | `platform.Bridge.ContextProvider` 注入；仅 JVM 退化成 stub |
| `java.nio.file.*` | 需要 API 26，desugaring 覆盖不到 | TV `minSdk 26`（实测设备：极米 API 28 / MuMu API 30+） |

**数据目录**：TV 的 `--data-dir` 指向 `filesDir/gateway`；`config.sites.json` 由
`GatewayServer.syncConfigFile()` 落盘，后端直接读它——不写就是空表。

**日志**：JVM 写 stdout，Android 走 logcat；两边同时进 `core.LogBuffer` 的 2000 条环形缓冲。
TV 另有 `crash.log`（只留最近 64KB），因为部分电视读不到 logcat。

---

## 第二章 站点注册与分派

### 2.1 三张表与真源

```
SITES : key → 展示名     API : key → api（csp_Xxx / xxx.js / http 地址）     EXT : key → 每站参数
```

**真源是数据目录的 `config.sites.json`，不是源码静态表。** 静态表只提供基线
（`grep -o 'SITES.put("' server/host/src/shared/ApiServer.java | wc -l` = 44 处、
`grep -o 'EXT.put("' server/host/src/shared/ApiServer.java | wc -l` = 8 处，
不含注释与运行时 `put` / `putAll`），启动时先 `snapshotBaseline()`
再 `loadMultiInstances()` 叠加配置。

**站点身份 = `key + api`：**

- 同 key 同 api = 重复条目，跳过；同 key 不同 api = 并列，后者变成 `key#2`、`key#3`……；
- `csp_` 站点的实例类名 = key 去掉 `#N`：`AppDrama2#2` → `com.github.catvod.spider.AppDrama2`，
  ext 用 `EXT.get("AppDrama2#2")`。这就是「一个类服务多站点」的落地方式。

**可见性有两道闸**（`siteImplemented` / `siteVisible`，`ApiServer.java:12851`）：

1. 有实现：原生源、`spiderClassPresent(key)`、或 api 不是 `csp_`；
2. 被配置承认：配置里已有站点时，基线里没进配置的条目不再下发。

第 2 条必需：`rebuildSites()` 每次都把基线灌回 `SITES`，否则替换式导入重启后又会冒出
跑不起来的基线站，用户删不掉、列表里永远有 500。

**约束：改站点不要加硬编码。** 新增内置站走原生源或配置基线；能用 `ext` 解决的不要改代码。

### 2.2 分派顺序（顺序即正确性）

`call(site, action, body)`（`ApiServer.java:2229`）按以下顺序判定，**先后不能换**：

| 序 | 判据 | 交给谁 | 为什么必须在这个位置 |
|---|---|---|---|
| 1 | 站点名在 `NativeSources` | `NativeSources.invoke` | 同名站只能有一个实现者。放后面会被 `csp_Douban` 抢走（它没有 `detailContent`），详情页永远空 |
| 2 | `API.get(site)` 以 `http` 开头 | `sources.CmsSource` | 苹果CMS 直连；放后面会撞 `ClassNotFoundException`，每站烧满超时还污染健康分 |
| 3 | `isScriptApi`：`.js` / `js:` / `node:` / `.py` / `py:` | 外部 Node 运行时 | 放 `instance()` 前，否则先撞类找不到 |
| 4 | 其余 | jar 实例（`Bridge.loadClass`） | 默认路径 |

原生源返回 `null` 表示「本源不实现这个 action」（目前只有 search）：有 jar 就继续往下，
没有就给空结构，**不能变成 500**——否则搜索结果里这个站显示「出错」而不是「没结果」。

**action 映射：**

| action | jar 方法 | 备注 |
|---|---|---|
| `home` | `homeContent(true)` | |
| `category` | `categoryContent(tid, pg, true, filters)` | filters 来自 body |
| `detail` | `detailContent(List ids)` | id 像 JSON（`{`/`[` 开头）就整串传，不按逗号拆——SP360 的 id 本身含逗号 |
| `search` | `searchContent` 系 | 见 `invokeSearch` |
| `play` | `playerContent(flag, id, [])` | 抛异常时降级成 `{parse:1,url:"",msg:"该线路暂不可用：…"}`，不让整个请求 500 |
| `live` | `liveContent(url)` | 结果经 `normalizeLive` 归一 |
| `action` | `action(String)` | |

### 2.3 配置表（8 张数组 + 3 个字符串）

CatVod 语义：`parses / lives / rules / ads / proxy / hosts / doh / headers`，
另有 `wallpaper / logo / danmaku` 三个字符串字段。

**关键事实：`/config.json` 只下发 8 张数组表，不下发三个字符串字段。**
表由 `loadConfigSites()` 每次读文件，带 5 秒快照（`CFG_SNAPSHOT`），
导入/写入时 `invalidateConfigTables()` 失效。

| 表 | 宿主消费点 | 实际行为 |
|---|---|---|
| `parses` | `handleParse` | 解析器候选集；type 0–3 都能在服务端跑（§4.4） |
| `lives` | `/api/live`、`/api/lives` | 内置源 + 用户 IPTV 列表，聚合成频道 |
| `rules` | 页面嗅探 + 播放清单改写 | 嗅探用 `hosts/regex/exclude/click`；删分片**只在 `policy.filterAds=true` 时生效** |
| `ads` | `buildProxiedPlaylist` / `isAdHost` | **默认关闭**。开启后按 host 子串或 `/正则/` 丢弃分片 |
| `proxy` | **仅透传** `/config.json` | 宿主代理决策不看这张表；白/黑名单靠 `UrlSafety` + CORS |
| `hosts` | `mapHost` | `a.com=b.com`，**只对 http 生效**；https 换 IP 会破坏 SNI |
| `doh` | `dohProviderFromTable` | 取第一个 http(s) 的 DoH 地址，优先于硬编码公共 DoH |
| `headers` | `defaultHeader` | 全局兜底请求头；spider 自带的 Referer/UA 优先 |

**`ads` / `rules` 的两条护栏**：一个分片都不剩、或删掉超过一半时长 → 回退原清单。
没有护栏时用户看到的是 hls.js「没有可播档位」，根本不会想到是去广告规则干的。

### 2.4 导入与备份

导入是**整表替换或合并**，不是追加。`planSites` 的冲突键是 `key + api`，
`onConflict` 三选一：`keep` / `replace` / `both`（`both` 另起 `key#N`）。

- 预览结果只留最近一次、60 秒过期；导入前把旧配置写到 `CONFIG_BACKUP`，
  `/api/config/backup` 支持查、恢复（POST）、丢弃（DELETE），恢复失败不覆盖现有配置。

---

## 第三章 三个出口

### 3.1 出口 A：`/config.json`（给 CatVod 客户端）

```json
{ "spider": "http://<host>:9979/spider-latest.jar;md5;<实际下发文件的 md5>",
  "sites": [{ "key": "...", "name": "...", "type": 3, "api": "csp_Xxx",
              "ext": "...", "jar": "./spider-latest.jar" }],
  "parses": [], "lives": [], "rules": [], "ads": [],
  "proxy": [], "hosts": [], "doh": [], "headers": [] }
```

**实现要点（`catVodConfigJson()`，`ApiServer.java:11540`）：**

- `sites` 来自 `config.sites.json`，**不是** `SITES` 静态表；没有配置文件时是空数组。
- `type` 用配置原值。`type=1` 的 api 是 http 地址，写成 3 会让客户端按 `csp_` 找类，整站消失。
- `ext` 原样透传（可能是字符串也可能是对象），转 String 会破坏 `{"json":...}` 结构。
- `csp_` 站点没写 `jar` 时补 `"./spider-latest.jar"`（相对路径，客户端按配置地址补全）；
  不补的话 TV 的 `LocalSites.importFrom` 会跳过这些站（「填了地址什么都不加载」）。
- 8 张表整表透传，空表也发 `[]`。

**`spider` 的 MD5 语义**是 `"<url>;md5;<md5-of-that-exact-file>"`：

- md5 是**我们实际下发的那份 jar** 的 md5（`spiderJarMd5()` 按文件路径 + mtime 缓存）；
- 取不到 jar 时不写 md5 段——Fongmi 对空 md5 会「总是下载」，比写错稳；
- `ETag` 用同一 md5 并加引号（RFC 7232），配合 `If-None-Match` 回 304；
- 优先发 **dex 形态**（判据：jar 里有 `classes.dex`），没有才回落 class 版；
  启动日志会打印是哪种形态。

**`/api/config` 不是 CatVod 出口。** 它给自家前端做旧兼容，只输出 `{sites, lives?}`，
api 一律拼成 `csp_<key>`、type 固定 3。CatVod 客户端必须用 `/config.json`。

### 3.2 出口 B：`/api/*`（给 PC/Web/TV）

路由是**逐条 `equals` + 少量前缀兜底**（显式 57 条，`grep -c 'equals(path)'`）。
顺序是正确性的一部分：所有 `/api/xxx/yyy` 形式的专用端点必须排在 `/api/{site}/{action}`
兜底之前，否则会被当成 `site=xxx, action=yyy` 去加载不存在的类。

| 类别 | 路径 |
|---|---|
| 系统 | `GET /api/health`、`GET /api/sites`、`GET /api/config`、`GET /config.json`、`GET /spider-latest.jar` |
| 站点 | `POST /api/{site}/{home\|category\|detail\|search\|play\|action}` |
| 搜索 | `POST /api/search`（`async:true` 建任务）、`GET /api/search/task/{id}`、`GET/DELETE /api/search/cache` |
| 解析 | `POST /api/parse` |
| 直播 | `POST /api/live`、`GET/POST/DELETE /api/lives`、`POST /api/live/playlist` |
| 代理 | `GET /api/proxy/stream`、`/api/proxy/image`、`/proxy`（jar 端口探测与站点代理，不是 `/api/proxy`） |
| 弹幕 | `GET /api/proxy/danmaku`、`/search`、`/list`、`/xml`、`/prefetch` |
| 数据 | `POST /api/webdav`、`GET/POST /api/policy`、`GET /api/dns/resolve`、`/api/library*`、`/api/cache*`、`/api/state/*`、`GET/DELETE /api/posters` |
| 配置 | `POST /api/config/import`、`GET/POST/DELETE /api/config/backup`、`POST /api/config/depot` |
| AI | `POST /api/llm/relay`（LLM 中继，`policy.llmRelay.enabled` 显式打开才生效） |
| 投屏 | `GET /api/cast/health`、`/api/cast/base`、`/api/cast/receiver/pending`；`GET/POST /api/cast/receiver`；`GET/POST /api/cast/session`；`POST /api/cast/command`、`/api/cast/progress` |
| 运维 | `GET /api/logs`、`/api/health/{probe,degrade,state,reset}`、`/api/scrape`、`/api/cast`、`/api/transcode` |

**`/proxy` 是 jar 的端口握手，不是普通代理**：`Proxy` 的端口初值是 `-1`，首次 `getPort()`
从 9978 扫到 9999 请求 `/proxy?do=ck`，正文为 `ok` 才记住。所以 `do=ck` 必须回 `ok`，
其余原样反射给 `Proxy.proxy(Map)`；否则 Hxq/Bili 会产出 `http://127.0.0.1:-1/proxy?...`。

**所有 API JSON 响应一律 `Cache-Control: no-store`**（`sendText` 统一加）。踩过：TV WebView
用 `LOAD_CACHE_ELSE_NETWORK` 把 `/api/...` 的 JSON 也缓存住，弹幕搜索永远返回第一次那一条。
中间层缓存防不住，只能从源头声明（图片代理与分片流不走这个函数）。

### 3.3 `capabilities` 协商

`/api/health` 当前返回（`healthJson()`，`ApiServer.java:1643`）：
`ok, version, capabilities, sites, ext, spiderJar, nativeSources, configLoaded, danmakuApi,
authRequired, authSource, disabled, disabledCount, degraded, unsupported, dataDir,
now, uptime, heapMaxMB, dalvikHeapLimitMB, searchConcurrency, heapUsedMB, heapPeakMB,
searchConcurrencyCapped, jsRuntimeBreaker, jsRuntimeFails`。

`capabilities` 取值：`danmaku`、`search`、`proxy`、`webdav`、`dns`、`state`、`parse`，
配置了外部弹幕 API 时多一个 `danmakuExternal`。**刻意不含 `live` / `multiDepot`**
（代码注释写明「尚未实现 / 别虚报」），设计意图是让前端据此隐藏 UI。

`now` 不是装饰字段：一批源（Jpys / Gulu / AppRJ / Gz360 / AppDrama2 / MiSou）用
`System.currentTimeMillis()` 生成签名，服务端会拒掉偏差过大的请求，表现是
「搜索静默返回空、不报错」。设备时间不准时，`/api/health.now` 与前端时钟相减即可定位。

**前端消费现状**：Web 只用它判断 `/api/state/*` 是否可用（`hasStateCapability()`，
`api/index.ts:850`），另在 AI 工具里透传能力列表；其余能力位没有驱动 UI——
后端发、前端不全用，不要按「已协商」写文档。

### 3.4 出口 C：`/api/state/*`（三端共享）

| 端点 | 存储文件 | 语义 |
|---|---|---|
| `GET/POST /api/state/progress` | `state-progress.json` | 每剧每集播放位置；上限 2000，pull 上限 500 |
| `GET/POST /api/state/favorites` | `state-favorites.json` | 收藏；上限 5000 |
| `GET/POST /api/state/prefs` | `state-prefs.json` | account 级设置（白名单键） |

**没有 `/api/state/history`。** 观看历史由 `progress` 派生。

**两条容易改错的语义**（`StateRoutes.java:24`）：裁剪 progress 用 `ep_at`（最近观看），
不是 `at`（创建时间），用错会删掉「早收藏、最近在看」的进度；同步 prefs 时丢弃
`STATE_PREFS_SKIP`（音量/倍速/比例、壁纸、WebDAV 凭据），本机专属值推到别的机器只会制造故障。

**凭据是硬约束：WebDAV 地址/密码只存各端本地，绝不上传、不进备份。**
并发写用 `STATE_LOCK` 串行化；读文件失败/损坏一律当空列表——首次启动本来就没有文件，
断电写坏也不该让前端整片 500。

---

## 第四章 运行时与解析

### 4.1 Java jar（默认路径）

`core/Jars` 负责外部 jar：配置里的 `spider` / 站点级 `jar` 会被下载、校验、缓存，
再由 `platform.Bridge.loadClass` 加载。TV 侧是 `DexClassLoader`，JVM 侧是 `Class.forName`。

两种形态：**dex**（jar 内有 `classes.dex`，Android/Fongmi 的 `DexClassLoader` 只认它）与
**class**（只有 `.class`，JVM 类路径用）。

JVM 在**导入配置时**由 `core/Dex2Jvm` 把只有 `classes.dex` 的 jar 转成 class jar，
写回 `spiderJvm` / 站点 `jar`（实验性，需 `MEOW_DEX2JAR`；失败保留原配置并返回原因）。
**不要把 okhttp 打进 dex**（重复类 + 方法数爆）；**不要用 `d8` 覆盖同名类**（必须走 smali）。

### 4.2 脚本运行时（JS / PY 两个引擎，分开路由）

`api` 以 `.js` / `js:` / `node:` / `.py` / `py:` 开头时转发到外部脚本服务。
**2026-11-28 起两层引擎分键**（中期分层）：

| 源的类型 | 目标地址 | 缺省行为 |
|---|---|---|
| `.js` / `js:` / `node:` | `policy.jsRuntime`（或 `MEOW_JS_RUNTIME`）→ drpy / QuickJS | 无回落 |
| `.py` / `py:` | `policy.pyRuntimeAddr`（或 `MEOW_PY_RUNTIME`）→ pyrt | 未配置时回落到 `jsRuntime`（老部署：pyrt 同时服务 .py 并把 .js 转发给 drpy；iOS 端内也是这个形态） |

独立 Python 引擎连接被拒时**再回落一次 `jsRuntime`**，两条都不可达才报错 ——
所以"卸载/停掉 pyrt"只会让 `.py` 报错，绝不会把 `.js` 带死。

> ★ 键名是 `pyRuntimeAddr` 不是 `pyRuntime`：iOS 壳用 `policy.pyRuntime`
> 存 Python 启动状态对象（`{state,error,at}`，见 `client/apps/ios/Meow/PythonRuntime.swift`）。
> 地址与状态必须分键，合并 iOS 分支时不要改回 `pyRuntime`。

请求协议（两个引擎相同）：

```
POST {base}/{urlencode(site)}/{urlencode(action)}
X-DRPY-Api: urlencode(api)     X-DRPY-Ext: urlencode(ext)（有才带）     body: 原样的 action body
```

**读超时按运行时健康度动态给（不写死）：**

| 状态 | 读超时 | 理由 |
|---|---|---|
| 正常 | 8s（`SCRIPT_RUNTIME_TIMEOUT_MS`） | 多数源 1–2s 返回，8s 不误杀 |
| 熔断期 / 该站慢 | 3s（`SCRIPT_RUNTIME_FAST_TIMEOUT_MS`） | 容器挂了时快速失败，12 个并发槽也能扫完 200+ 源 |

连续失败打开熔断（`jsRuntimeBreaker`），`/api/health` 直接暴露开合状态；
单站慢进 `SCRIPT_SLOW_TTL_MS`（10 分钟）。

**为什么必须开 worker**：drpy 的 `req()` 走 `sync-fetch`，用
`SharedArrayBuffer + Atomics.wait` 阻塞线程。在主线程跑会把整个事件循环钉死，连
`/health` 都超时（容器报 unhealthy 但 CPU 只有 2.3%）。每个源请求在 `worker_threads`
里跑，阻塞只影响那个 worker。

**当前吞吐瓶颈（`server/drpy/host.js`，未修）：** `fetchScript(api)` 与 `fetchText(ext)`
都没有磁盘缓存，每次重新拉引擎和规则文件（`fetchText` 另有 3 次重试 + 15s 超时）。
国内直连 GitHub 单站冷启动可达 80 秒，远超 8s 读超时 → 后端必然掐断。运行时侧已有三处
必要修正（转义说明符解码、`evalModule` 过 ESM 转换、vendor `gbk.js`），但只保证「能跑」。
要真用 200+ 个 drpy 站，必须加带 TTL 的磁盘缓存并自托管常用引擎。

**两个中性现象：** `spiders:0` 是正常值（站点按 `X-DRPY-Api` 惰性加载）；改过
`server/drpy/` 后不重建镜像等于没改。

### 4.3 苹果CMS 直连

`api` 是 http 地址时走 `sources/CmsSource`，纯 HTTP + JSON，不需要任何运行时。
**实际请求全部用 `ac=detail`，不是文档常写的 `ac=videolist`**：

```
列表：GET {api}?ac=detail&pg={pg}（带探测出的分类参数）
详情：GET {api}?ac=detail&ids={id}
搜索：GET {api}?ac=detail&wd={keyword}&pg={pg}
分类：GET {api}?ac=list&pg=1   ← 只用于取 class，不用于列表
```

两个实测坑决定了这个形状：`ac=list` 的条目**没有 `vod_pic`**，卡片是空色块，
列表只能用 `ac=detail`；分类过滤参数**各站不统一**（`type_id`/`t` 认不认因站而异），
所以启动时先探一次「这个站认哪个参数」，结果缓存 10 分钟（失败只缓存 1 分钟），认不了分类的站只给「最新更新」一个 tab。

`vod_play_url`（`线路$$$线路`、`集名$地址#集名$地址`）与 CatVod 天然同构，
不发协议转换，原样透传。这是覆盖率/成本比最高的一条路径。

### 4.4 解析器（type 0–4）

`/api/parse` 请求 `{url, flag?, name?}`，响应 `{ok, url, header, from, msg?, tried?}`。
`url` 先过严格档 SSRF 校验，不允许指向内网。

| type | 语义 | 服务端实现 |
|---|---|---|
| 0 | WebView 嗅探 | **近似实现**：抓 HTML，按 `player_aaaa` / JSON 里的 m3u8 找地址；纯 JS 计算的地址拿不到 |
| 1 | JSON 直连 | `GET {entry.url}{webUrl}`，读顶层 `url` 或 `data.url`，带回 ua/referer/cookie |
| 2 | JSON 聚合 | 反射 jar 里的 `parser.JsonXxx` |
| 3 | JSON 混合 | 反射 jar 里的 `parser.MixXxx` |

**type 4（超级解析）不是服务端条目**：`serverSideParse()` 只收 0–3，配置里
type 4 的解析器不进候选池。Fongmi 客户端的 type 4 语义是「并行跑 type 0/1 先成功者胜」；
服务端把这条语义吃进了候选池本身——所有候选一视同仁并发（独立小池，最多 4 线程，
不占 `POOL` 的 12 线程，否则一次解析就能把浏览请求挤住），先成功者胜，总 deadline `PARSE_TIMEOUT_MS = 15s`。

**flag 过滤是「优先适配」不是白名单**：按 `ext.flag` 过滤后为空时退回全部可用解析器
（与 Fongmi `getParses(type, flag)` 一致）。不加过滤会把腾讯的源送给爱奇艺解析器；
过滤成硬白名单会让一条没配 flag 的线路全废。

**type 0 的边界**：拿到的可能只是播放器初始化参数（下一层播放页或 base64）。不校验就
`ok:true`，播放器会看到「manifest 解析失败」且**永远等不到换线**。所以只多跟一层
`resolveSniffedMedia`，再深就是拿别人的站当爬虫代理。

---

## 第五章 三端协作

### 5.1 三种接入方式

| 端 | 接入对象 | 协议 | 谁执行 spider |
|---|---|---|---|
| **TV（Fongmi / 派生）** | `/config.json` + `/spider-latest.jar` | CatVod config | **客户端自己**（`DexClassLoader`） |
| **PC（Tauri）** | 本地/远端 REST | HTTP | 我们的后端 |
| **Web（Vue）** | 同源或手填 REST | HTTP | 我们的后端 |

**关键：TV 不经过我们的 REST，PC/Web 不加载 jar。**

### 5.2 Web/PC 的后端地址

`client/apps/web/src/api/index.ts` 的解析顺序：

1. 桌面端：`localStorage['meow.api']`（仅 `scope === 'desktop'`）+ 插件协商的 `backend_base`；
   回环手填地址在插件就绪后让位给协商地址。
2. Web：`meow.api`（必须由设置页写入并带 scope，或 TV 壳自动引导）+ `VITE_API_BASE`。
3. 兜底同源空串。

**桌面端没有确定地址时必须报错，绝不退化成同源请求**（`backendUnavailable()`）。
踩过：开发模式下页面从 `localhost:5173` 加载，插件未就绪时 `fetch('' + '/api/sites')`
打到 Vite 代理，应用显示「已连接 · 39 源」——那其实是另一个独立后端。仍是单后端 + 手填覆盖，没有多后端列表。

### 5.3 TV 内嵌网关

TV 直接编译 `shared/` 的 `ApiServer`，`LocalGateway` 已删除。原来那份是手抄的第二份实现
（8 条路由，后端 50 条）；代价不是重复劳动，是**修一处漏一处**——m3u8 的 `EXT-X-KEY`
改写后端早就有，TV 那份没有，表现就是同一个源在 Web 能播、在 TV 黑屏。

| 项 | JVM / PC | TV |
|---|---|---|
| 监听 | `0.0.0.0:9979` | `127.0.0.1:9978`（jar 的 `Proxy` 从 9978 扫到 9999） |
| 连接线程 | 16 | **24**（12 个时连续两次全量搜索就能把端口打死） |
| HTTP 传输 | `JvmHttpServer` | `SocketHttpServer` |
| 类加载 | `Class.forName` | `DexClassLoader` |

**TV 的三种形态：**

| 形态 | 站点表来源 | jar 来源 | 状态 |
|---|---|---|---|
| A 连自建后端 | 后端 `/api/sites` | 后端加载 | 可用 |
| B 导入自建后端配置 | `/config.json` | `DexClassLoader` | 可用；只收 `csp_` 且配了 jar 的站 |
| C 导入肥猫配置 | 肥猫 `/tv` | 肥猫 `spider.php`（dex） | 可用 |

B/C 共用内嵌网关，路由实现与后端同一份。

**弹幕**：内置聚合站实测不可用（404/402/000/503），`GatewayServer.ensureDanmakuApi()` 启动时
把 `policy.danmakuApi` 指向用户配的后端；没配后端时不注入（纯本机模式接受没有弹幕）。
**做不到的**：`/api/transcode` 依赖 ffmpeg 可执行文件，Android 起不了；TV 走
`CacheDownloader` + SAF 另一条路。

### 5.4 与 zyfun 的兼容性

- **zyfun → 我们后端**：在「影视配置」里加站点、类型选 `T4_CATVOD`（`type: 8`），
  指向 `/{site}/{action}`。**不是**「数据导入」。
- **zyfun 导入我们的 `/config.json`**：设计不兼容。它的 `tvboxToStandard` 只保留
  `type 0/1/4`，或 `type 3` 且 `api` 是 `csp_XBPQ`/`csp_XYQHiker`/`.js`/`.py`；
  我们的站是 `type 3 + csp_Xxx`，会被全部过滤成空数组，报 `Dirty data to import`。
  另外它要 `config.video.sites`，CatVod 标准是 `config.sites`。

**结论**：zyfun 没有 `DexClassLoader`，天生跑不了 jar；要用我们的源，只有「把整个后端
当一个 CatVod 服务」这条路。

### 5.5 TV 的进度回传

Fongmi 读 `position`（毫秒）作为起播点，读 `subs` 挂字幕轨，读 `skips` 做片头尾跳过（上游源码结论，见 §8.4）。

**关键约束：`position` 取 `Math.max`，只会往后推、不会往前提**，做不到回退；本地历史
更靠后时本地值赢。**opening 段必须 `start == 0` 才会在起播时被自动跳过**；`start != 0`
只会在播放中被 `activeSegment()` 命中，且要求用户没手动设过片头时长。

---

## 第六章 安全与运维

### 6.1 安全清单（以代码为准）

**前提：后端不直接对公网暴露。** 本节 S2–S4 都是「内网可信」前提下的纵深防御，
不是公网对抗；要上公网必须先补一层入口控制，见 [公网部署与访问控制](/dev/public-deployment)。

| # | 项 | 现状 |
|---|---|---|
| S1 | **SSRF 防护** | 两档校验，见 §6.2 |
| S2 | **写操作鉴权** | **可选** token：`MEOW_AUTH_TOKEN` 优先，其次 `policy.authToken`；未配 = 关闭。GET/HEAD 永不拦（公网部署要改成白名单式，见 [公网部署](/dev/public-deployment)） |
| S3 | **CORS** | **默认 `*`，不拦截**（用户明确要求）。`MEOW_CORS` 设为逗号分隔白名单才收紧；`none` 只放行本机与各端壳 |
| S4 | **CSRF / 跨源写** | 非 GET/HEAD/OPTIONS 校验 Origin；**但默认 `MEOW_CORS=*` 时 `originAllowed()` 直接放行**（`ApiServer.java:1139-1140`），即默认不生效，收紧 CORS 后才起作用 |
| S5 | **路径遍历** | 片库只访问配置声明的根，路径由服务端拼接、只接受相对路径；`normalize + toRealPath` 白名单 |
| S6 | **限流** | 固定窗口。`/api/parse` 默认 30 次/窗口，`/api/proxy/*` 默认 240 次，窗口默认 10s；`policy.rateLimit` 可调/可关 |
| S7 | **DNS rebinding** | 部分：连接前校验，校验与 connect 之间仍有窗口（刻意取舍，见下） |

**CORS 与鉴权的分工**：CORS 只防「浏览器里的第三方页面」；能直连端口的人本来就能用 curl 写，
真正的访问控制是网络边界 + token。**但默认 CORS 为 `*` 时 S4 并不生效**——
原文「S4 与 token 无关、默认部署也生效」是错的（2026-11-16 核代码更正）：
`corsAllowList()` 返回 `null` 时 `originAllowed()` 直接 `return true`。
局域网自用无妨；公网部署必须显式设 `MEOW_CORS`，见 [公网部署](/dev/public-deployment)。

### 6.2 SSRF：两档，不是一档

`core/UrlSafety.java` 的 `isSafeMediaUrl`（宽松）与 `isSafeHttpUrl`（严格）：

| 档 | 允许 | 拒绝 | 用在哪 |
|---|---|---|---|
| 宽松 | 局域网 `10/8` `172.16/12` `192.168/16` | 回环 / 链路本地（含 `169.254.169.254`）/ `0.0.0.0` / 非 http(s) | `handleStream` / `handleImage` **入口** |
| 严格 | 仅公网 | 宽松档全部 + 私网 + CGNAT `100.64/10` + benchmark `198.18/15` + NAT64 `64:ff9b::/96` | **每一跳重定向目标**、`/api/parse` 目标、播放页抓取 |

**入口为什么宽松**：自建片源、本地 NAS、内网 Jellyfin 都是真实场景，封了就等于
「自己的片子看不了」。**重定向为什么严格**：公网主机没有任何理由 302 到内网——
SSRF 最经典的打点（`302 → 169.254.169.254` 读云凭据），容忍它换不来任何功能。

配套实现：`handleStream` 关自动重定向 + 手动循环，最多 `MAX_PROXY_REDIRECTS = 5` 跳，
每跳重校验；m3u8 分片基准改用**最终** URL（否则经过跳转的清单会把分片解析到错误的域）；`handleImage` 单跳、不跟随。

**残留风险（刻意）**：校验完到 `connect()` 之间仍有 DNS rebinding 窗口。彻底封死要把 IP
钉进连接，但会毁掉依赖 SNI 的 CDN（实测 `421` 就是这个原因，`handleStream` 有 safeDns 退回逻辑）。
**留窗口而不是留故障**，是这里的取舍。

**并发闸**：`/api/proxy/stream` 有 8 个信号量（`STREAM_SLOTS`）。一个慢分片能占住线程最长
45 秒（connect 15s + read 30s）；超限直接 503，让播放器重试，而不是让整台后端排队。

### 6.3 源健康状态机

`routes/HealthRoutes` 维护每站的窗口计数与降档状态，`/api/sites` 与 `/api/health` 都从这里取状态：

- 连续 `HEALTH_DEGRADE_STREAK = 3` 次失败 → 降级；任意成功立即复亮；
- **超时跳过**：连续 2 次超时（`TIMEOUT_SKIP_STREAK`）→ 搜索跳过，TTL 10 分钟；
- **失败跳过**：累计失败 ≥ 5 且最近 30 分钟没有成功 → 跳过；
- 手动禁用（`disabled`）与「环境不支持」（`unsupported`）单独标记，不算源站故障。

**判据必须区分「慢且失败」和「成功但没内容」**：drpy 超时返回空 body，`emptyFallback`
会兜成 `{"list":[]}`，结构合法——用 `isErrorPayload` 判会把「慢且失败」记成成功，
永不降档、每轮照样烧满超时。所以用 `hasRealPayload(action, out)` 判「有没有真的拿到条目」。

**探活超时** `PROBE_SITE_TIMEOUT_MS = 8000`。`/api/health/reset` 清掉跳过/降档/降级标记，
排查时不用重启后端（跳过 TTL 是 10 分钟，用户修好源也看不到效果）。

### 6.4 日志与诊断

`core.LogBuffer` 是 2000 条环形缓冲，`/api/logs` 可读，正文经 `scrub()` 对 query 里
`url`/`token`/`authToken`/`password`/`pwd`/`pass` 这几个参数打码（其余原样保留，可读性优先）。
`/api/health` 带堆诊断（`heapMaxMB`/`heapUsedMB`/`heapPeakMB`/`searchConcurrencyCapped`），
排查「同配置同版本，投影仪必崩、模拟器正常」时唯一能看出差别的就是这几个量。

---

## 第七章 已知坑与约束

### 7.1 平台与协议

| # | 坑 | 依据 |
|---|---|---|
| 1 | **`HttpURLConnection` 不支持 PROPFIND/MKCOL**（抛 `ProtocolException`）；WebDAV 必须用支持自定义方法的客户端，当前是 OkHttp | `LibraryRoutes` |
| 2 | **m3u8 相对 URI**：以 `/` 开头要 `origin + uri`，不能 `base + uri` | `resolveUrl` |
| 3 | **ofetch 在 Tauri 下会挂**（`location[0]` 抛异常），用原生 fetch | `api/index.ts` |
| 4 | **Tauri 页面是 `http://tauri.localhost`**，不是 `tauri://`；判断桌面端用 `'__TAURI_INTERNALS__' in window` | `api/index.ts` |
| 5 | **`android-json` 的 `JSONObject.put` 抛受检异常**，新方法要 `throws Exception` | 全仓 |
| 6 | **`com.sun.net.httpserver` 不能自带同名包**，共享代码只能经 `platform.*` 抽象 | `GatewayServer` |
| 7 | **`sun.net.http.allowRestrictedHeaders` 必须在 HttpURLConnection 首次初始化前设**，否则 safeDns 的 Host 头被静默丢弃（豆瓣 0 条） | `ApiServer.boot()` |
| 8 | **vue-router 会自动编码 query**，不要手动 `encodeURIComponent`（会变 `%253A`） | Web |
| 9 | **`d8` 不能覆盖同名类**（走 smali）；**不要把 okhttp 打进 dex**（重复类 + 方法数爆） | 打包脚本 |

### 7.2 数据与协议

| # | 约束 |
|---|---|
| 10 | `config.sites.json` 是站点真源，`/config.json` 的 `sites` 也来自它；只改 `SITES.put` 不会出现在任何出口，没有配置文件时是空数组 |
| 11 | `type` 必须原样透传；`ext` 可能是对象，不能 `String.valueOf` 后写回配置 |
| 12 | 站点 class = key 去掉 `#N`。给多实例改 key 名会连带改到类名，站点直接加载失败 |
| 13 | `rules`/`ads` 默认关闭（`policy.filterAds`）。网上来的正则很容易删空整集，不要默认打开 |
| 14 | `hosts` 只对 http 生效；https 换 IP 会破坏 SNI，多数 CDN 直接 421/SSL 错误 |
| 15 | 所有 API JSON 响应 `no-store`；不要给 `/api/*` 加缓存头，踩过弹幕「只有 1 条」 |
| 16 | 请求线程池 12（TV 24 个连接线程）。同步搜索一次占 2–20 秒，12 个并发就能占满 |
| 17 | 搜索线程上限 32，低内存设备按堆再压一层（`searchConcurrencyCapped`）；弹幕缓存 TTL 30 分钟，磁盘另有 TTL |

---

## 第八章 决策记录

### 8.1 已定决策（ADR）

| # | 决策 | 理由 | 日期 |
|---|---|---|---|
| D1 | **TV 不自研，用 Fongmi** | 焦点管理/遥控器/D-pad 打磨成本 1–2 月；GPL 自用无影响 | 2026-10-20 |
| D2 | **Web/PC 自建** | 必须摆脱 GPL；且 zyfun 是 AGPL 更严 | 2026-10-20 |
| D3 | **不在 TV 上走我们的 REST** | 要改 Fongmi 的 catvod 模块 + GPL 传染 | 2026-10-21 |
| D4 | **不内置 jar，运行时拉取** | 用户明确要求（内置违规） | 2026-10-20 |
| D5 | **不 fork zyfun** | AGPL；且它是我们的下游消费者，不是竞品 | 2026-10-21 |
| D6 | **`spider` 的 md5 写我们自己的** | 重打包产物与上游不同；用户已否定逐字节对齐 | 2026-10-19 |
| D7 | **配置驱动而非硬编码** | 改源不发版；这是「能持续维护」的根本 | 2026-10-21 |
| D8 | **源是单位，不是类** | 一个适配器覆盖几十到上百个源 | 2026-10-21 |
| D9 | **私有逆向类降优先级** | 私有类在公共多仓里一个都没有，无社区维护 | 2026-10-21 |
| D10 | **设置分 account/device 两类** | 用户明确要求按数据语义分层 | 2026-10-21 |

### 8.2 D1 的修订：两条线并存

D1 的「不自研」被收窄了适用范围，理由与结论主体仍成立。当前 TV 壳是**基于
`motao123-webtv`（Fongmi 的 GPL-3.0 二开分支）二开**，不是从零自研：
`client/apps/tv/app/src/main/java/com/github/catvod/` 是继承的上游代码，`com.meow.tv`
是我们的壳。焦点管理/遥控器/D-pad 的成本依然被规避。

| 线 | 来源 | 我们改什么 |
|---|---|---|
| **上游兼容线** | 官方 Fongmi / 任意 Fongmi 分支 | **不改**。靠 `/config.json` 兼容 |
| **我们的发行版** | `motao123-webtv` 二开 | 样式与交互；仍须吃同一份 `/config.json` |

**兼容承诺不因发行版而取消**：任何新能力优先落在 `/config.json` 与 play 响应字段
（`position` / `subs` / `skips`）上，让两条线共用同一后端，不必为发行版单独维护服务端分支。

「不做：fork Fongmi 改代码」约束的是「随意 fork 官方主线再维护两份」；`motao123-webtv`
是既有分支、改动集中在该分支内，属于**单一发行版**，代价是自行维护相对上游的 diff。

### 8.3 不做清单

| 不做 | 理由 |
|---|---|
| **fork Fongmi 官方主线改代码** | GPL + 双份维护 |
| **抄 LunaTV/MoonTV 代码** | CC BY-NC-SA，禁商用 |
| **支持 PNG 尾附加配置** | 技术上几行可解，但这是绕过平台审查，不适合进产品 |
| **追 Hex 自定义加密源** | 通用解法只覆盖 `2423` 一种；其余必须内嵌具体算法，收益极低 |
| **WebHome 的 DOM 注入体系** | 自有 Vue 前端无此场景；只可移植 `fm.stat()` 协议 |
| **自动片头尾识别** | 误判率高；用 spider 给的 `skips` |
| **投屏（DLNA）** | 浏览器无 UPnP；Web 端不可行（PC/TV 走各自实现） |
| **多用户/账号体系** | 单用户自用；多用户是 P3 之后 |
| **自动弹幕/字幕识别** | 依赖人工词表，ROI 差 |
| **Python 运行时** | 占比低、成本高于 JS；`.py` 当前与 `.js` 同走 Node 运行时 |

### 8.4 待确认

| # | 问题 | 影响 | 状态 |
|---|---|---|---|
| Q1 | Fongmi 是否读 `position` | 决定 TV 进度同步方案 | **已确认：读**，单位毫秒，取 `Math.max` |
| Q2 | Fongmi 是否支持 `subs` / `skips` | 决定 TV 端能否用这些字段 | **已确认：两者都支持**；opening 需 `start == 0` |
| Q3 | `liveContent` 在 Fongmi 里走 spider 还是 config `lives` | 决定是否必须实现 liveContent | 待确认（jar 内无实现，实际直播靠 `lives` 列表） |
| Q4 | drpy 脚本的最低兼容子集 | 决定 JS 运行时工作量 | 待确认 |
| Q5 | Fongmi 的 Backup 格式 | 决定备份互导可行性 | 待确认 |

Q1/Q2 来自读上游源码（`VodSkipPolicy` / `bean.Result` / `MediaItemFactory`），
不是本仓库可复现的测试；升级 Fongmi 后需重新核对。

---

## 附录 A 关键数据结构

### A.1 Site（配置侧，按需字段）

```ts
// api: csp_Xxx | xxx.js | xxx.py | node:xxx | https://...api.php/provide/vod
interface Site {
  key: string; name: string; api: string; ext?: string | object; jar?: string
  type?: number; searchable?: 0|1|2; filterable?: 0|1; changeable?: 0|1|2
  playerType?: number; timeout?: number; style?: string
}
```

`/api/sites` 实际只回 `key` / `name` / `api` / `ext?` / `health?`，不要按 Fongmi 的完整 Site 字段写前端。

### A.2 PlayResult（`normalizePlay` 之后）

```ts
interface PlayResult {   // position / skips 单位：毫秒
  parse?: number; url?: string; header?: string; format?: string; position?: number
  danmaku?: string; msg?: string
  subs?: Array<{ url: string; name: string; lang?: string; format?: string }>
  skips?: Array<{ type?: string; start: number; end?: number | null }>
  qualities?: Array<{ name: string; url: string }>
}
```

`normalizePlay` 折三种 url 形状：`{values, position}` 对象、标签/地址成对数组、纯地址数组。
成对判定必须同时要求「奇数位是 http」且「偶数位不是 http」，否则
`["http://a","http://b"]` 会被误读成「标签=http://a，地址=http://b」。

---

## 附录 B 术语表

| 术语 | 含义 |
|---|---|
| **spider / jar** | 爬虫代码（猫影视/CatVod 规范）及其打包产物 `spider-latest.jar`（dex 或 class 形态） |
| **宿主** | 加载并运行 spider 的环境（Fongmi / 我们的 JVM 宿主 / TV 内嵌网关） |
| **config** | 声明式配置 JSON（sites/parses/lives/rules/…） |
| **depot / 多仓** | 配置的列表容器（`{"urls":[...]}`），本质是收藏夹 + 切换器 |
| **parse=1** | spider 表示「这是播放页地址，需要解析出真实地址」 |
| **ext / ext.flag** | 站点参数（通常是域名或 `域名\|密钥`）/ 解析器优先适配的线路标识（qq/qiyi/…），不是白名单 |
| **T4_CATVOD** | zyfun 的适配器类型，通过 HTTP 访问外部 CatVod 服务 |
| **XBPQ** | 苹果CMS → CatVod 的通用适配器 |
| **drpy** | 声明式 JS 爬虫脚本，由外部 Node 运行时执行 |
| **account / device** | 设置分类：需同步 / 仅本地 |
| **capabilities** | 后端能力声明，前端据此降级 |
