# API 契约

> 定位：前后端契约的**唯一权威来源**。形状以 `server/host/src/shared/ApiServer.java`
> 及 `routes/` 模块为准；本文只写代码当前会返回什么。行号仅供定位。
> 配套：[三端架构](./architecture.md)、[前端手册](./frontend.md)、[TV 接入](./tv-integration.md)。

---

## 目录

- [第零章 使用方式](#第零章-使用方式)
- [第一章 通用约定](#第一章-通用约定)
- [第二章 系统与配置](#第二章-系统与配置)
- [第三章 内容接口](#第三章-内容接口)
- [第四章 播放与解析](#第四章-播放与解析)
- [第五章 代理与弹幕](#第五章-代理与弹幕)
- [第六章 状态](#第六章-状态)
- [第七章 运维与本地能力](#第七章-运维与本地能力)
- [第八章 错误与降级](#第八章-错误与降级)
- [第九章 版本兼容](#第九章-版本兼容)
- [附录 A 端点全表](#附录-a-端点全表)

---

## 第零章 使用方式

### 0.1 这份文档解决什么

前端按本文写请求与解析，后端按本文输出。样例是**形状**不是固定值；
字段只增不改，前端必须容忍未知字段。

### 0.2 读取响应的四条规则

1. **先看 HTTP 状态码，再看 body。** 4xx/5xx 的错误信封固定为
   `{"code":-1,"msg":"…"}`；`code` 恒为 `-1`，没有符号化错误码。
2. **200 也可能是业务失败。** `/api/parse`、`/api/live/playlist`、`/api/lives`
   靠 `ok:false` + `msg` 区分；`/api/{site}/{action}` 返回**裸 CatVod JSON**，没有 `ok` 包装。
3. **时间单位不统一：** 毫秒是 `position` / `skips[].start,end` / `at` / `ep_at`；
   秒是 `pos` / `dur`。
4. **站点不存在不是 404。** 返回 200 + `{"code":-1,"msg":"该源在当前构建不可用…"}`。

---

## 第一章 通用约定

### 1.1 Base URL

| 形态 | 地址 |
|---|---|
| JVM 宿主 | `http://127.0.0.1:9979`（`MEOW_PORT` / `MEOW_BIND` 可覆盖） |
| TV 内嵌网关 | `http://127.0.0.1:9978`（jar 的 `Proxy` 从 9978 扫到 9999） |
| Web 开发 | Vite 代理 `/api`，base 留空 |
| PC 桌面 | 页面是 `file://` / `tauri.localhost`，用绝对地址；端口由插件协商 |

前端 `api/index.ts` 的顺序：桌面端 `meow.api`（仅 `scope=desktop`）+ `backend_base`；
Web 端 `meow.api`（设置页写入并带 scope，或 TV 壳引导）+ `VITE_API_BASE`；兜底同源空串。
**桌面端没拿到确定地址时报错，绝不退化成同源请求**（否则会显示另一个后端的数据）；
仍是单后端 + 手填覆盖，没有多后端列表。

**站点 key 里的 `#` 必须百分号编码**：多实例 key 形如 `Bili#2`，直接拼 URL 会被当
fragment 截掉。后端按 `/` 切段后 `URLDecoder.decode`，传 `Bili%232`。

### 1.2 请求头、鉴权、限流

```
Content-Type: application/json      （POST 必带）
X-Meow-Token: <token>               （可选；写操作）
```

- 令牌来源：`MEOW_AUTH_TOKEN` 优先，其次 `policy.authToken`；两处都空 = 关闭。
- **GET/HEAD/OPTIONS 永不拦**；写端点（state、config/import、lives、policy、webdav、
  library、cache、health 写、transcode、logs、posters、scrape、search/cache、
  llm/relay、cast）需要令牌，也接受 `?token=`。
  缺失/不匹配 → `401 {"code":-1,"msg":"unauthorized: X-Meow-Token required"}`。
- **跨源写一律 403**（`cross-origin write blocked`），与是否配 token 无关；curl / 本机脚本
  不带 Origin，放行。
- 固定窗口限流（默认 10s）：`/api/parse` 30 次/窗口，`/api/proxy/*` 240 次/窗口；
  `policy.rateLimit` 可调，设 0 关闭。超限 → `429 {"code":-1,"msg":"too many requests"}`。

### 1.3 响应头

```
Content-Type: application/json; charset=utf-8
Access-Control-Allow-Origin: <请求的 Origin>      （无 Origin 时回 *）
Access-Control-Allow-Methods: GET,POST,DELETE,OPTIONS
Access-Control-Allow-Headers: Content-Type,Range,X-Meow-Token
Access-Control-Expose-Headers: Content-Length,Content-Range,Accept-Ranges,X-Danmaku-*
Cache-Control: no-store, no-cache, must-revalidate
```

- **CORS 默认放行任何来源**（`MEOW_CORS` 未设置或 `*`）；设逗号分隔白名单才收紧，
  `none` 只放行本机与各端壳。下发**请求的 Origin**，不是 `*`；非 `*` 时带 `Vary: Origin`。
- `no-store` 由 `ApiServer.sendText` 统一加；`routes/` 模块里用 `Api.sendJson` 的响应
  **不带**这个头（`/api/state/*` 等）。
- `Access-Control-Allow-Methods` 必须含 `DELETE`：浏览器对 DELETE 先发 OPTIONS，
  漏了会让收藏删除卡在预检。

### 1.4 错误信封

形如 `{"code":-1,"msg":"site and vod_id required"}`。
只有 `code` 与 `msg`；没有 `error`/`detail`/符号化 `code`。`msg` 可能含内部异常
（如 `cannot load spider X: ClassNotFoundException`），前端不要直接当用户文案。
例外：`/api/llm/relay` 用 `{"ok":false,"error":"…","hint":"…"}`（§7）。

---

## 第二章 系统与配置

### 2.1 `GET /api/health`

`ok` 的判据是 `(spiderJar || nativeSources > 0) && configLoaded`——只有原生源也能为真。
响应字段（`healthJson()`）：

| 字段 | 语义 |
|---|---|
| `version` / `sites` / `ext` / `configLoaded` / `spiderJar` / `nativeSources` | 版本、可见站点数、有 ext 的站点数、站点表非空、jar 可加载、原生源数 |
| `configuredSites` / `implementedSites` | 配置里的站点条数 / 当前真正可用的条数；差距大时看 `jars` 与 `/api/sites/pending` |
| `jars` / `jarFailedCount` / `jarEncrypted` | 每条外部 jar 的 `{spec,name,ok,md5Ok,bytes,encrypted,error?,encryptedReason?,md5Expected?,md5Actual?}` / 装载失败条数 / 加密 Guard 壳原因 |
| `capabilities` | 固定 `danmaku/search/proxy/webdav/dns/state/parse`，配外部弹幕时加 `danmakuExternal`。**没有** `live`/`multiDepot`/`progress`/`favorites` |
| `degraded` / `unsupported` / `disabled` | 降级 / 环境不支持（缺类、运行时未接）/ 手动停用 的 key 列表（各带 `*Count`） |
| `now` | 服务器毫秒。一批源用本机时间生成签名，偏差过大会「搜索静默为空」 |
| `authRequired` / `authSource` | 是否要令牌；`env`/`policy`/`none`（**绝不回显令牌**） |
| `danmakuApi` | `configured` 或 `builtin` |
| `dataDir` / `uptime` / `heap*` / `jsRuntime*` | 运维诊断：数据目录、运行秒数、堆、drpy 熔断 |
| `searchConcurrency` / `searchConcurrencyCapped` | 实际并发 / 是否被人为压低 |

**能力位只能按实际名字判断**：Web 目前只消费 `state`（`hasStateCapability()`）。

### 2.2 `GET /api/sites`

```json
{"sites":[{"key":"Bili#2","name":"哔哩2","api":"csp_Bili","ext":"…",
           "health":{"ok":3,"fail":0,"degraded":false,"lastCostMs":880}}]}
```

- 只有 `key`/`name`/`api`/`ext?`/`health?`；**没有** `searchable`/`timeout`/`categories`。
- 默认只列「有实现 + 被配置承认」的站。`?includePending=1` 时追加配置里有、本构建
  加载不了的站：多带 `pending:true` 与 `reason`（加密壳 / jar 未装载 / 缺类）。
  **TV 端导入用的是默认形态**，不要给它塞 pending 项（会变成点开即失败的幽灵站）。
- `ext` 是**已转义的 JSON 字符串**（双重编码），前端 `tryParse` 一次。
- `health` 仅在有过记录时出现，字段：`ok/fail/degraded/disabled/unsupported/
  unsupportedReason/lastCount/lastCostMs/slowEmpty/timeoutStreak/lastTimeoutAt/
  lastOkAt/lastFailAt/skippedTimeout/skippedFailed/lastError`。
- 已按「有实现 + 被配置承认」过滤（`siteVisible`），没有实现的基线名不会出现。
- 有 `health.unsupported` 的站也会出现在这里（类在、但实例化/初始化失败），
  同时会进 `/api/sites/pending`（见 2.2.1）。

### 2.2.1 `GET /api/sites/pending`

`[]` 或 `[{"key":"GuardOne","name":"加密一","api":"csp_GuardOne","reason":"加密接口…"}]`。
数据 = 配置里 `siteVisible` 为假、或运行期实例化失败过的站；原因由后端统一给，
前端不要自己猜。**不影响 `/api/sites` 的既有语义**。

### 2.3 `GET /api/config`

`{"sites":[{"key":"Douban","name":"豆瓣","api":"csp_Douban","type":3}],"lives":[…]}`。
仅 `{sites,lives?}`；`type` 固定 3、`api` 固定 `csp_<key>`。
**这是旧兼容端点，不是 CatVod 配置**；CatVod 客户端用 `/config.json`。

### 2.4 `GET /config.json`（CatVod 出口）

```json
{"spider":"http://<host>:9979/spider-latest.jar;md5;<实际下发文件的 md5>",
 "sites":[{"key":"干饭","name":"干饭","type":3,"api":"csp_AppDrama2",
           "ext":"com.juechufsh.android.xpg1","jar":"./spider-latest.jar"}],
 "parses":[],"lives":[],"rules":[],"ads":[],"proxy":[],"hosts":[],"doh":[],"headers":[]}
```

- `sites` 来自数据目录的 `config.sites.json`，**不是** `/api/sites` 的表；没有配置文件时为空数组。
- `type` 原样透传。`type=1` 的 api 是 http 地址，写成 3 会让客户端按 `csp_` 找类，整站消失。
- `ext` 可能是字符串或对象，**不能转 String**（会破坏 `{"json":…}`）。
- `csp_` 站点没写 `jar` 时补 `"./spider-latest.jar"`（相对路径，客户端按配置地址补全）。
- 只下发 **8 张数组表**；`wallpaper`/`logo`/`danmaku` 三个字符串字段**不下发**。
- `spider` 的 md5 必须是**这份响应实际给出的 jar** 的 md5；取不到 jar 时不写 `;md5;` 段。
- Base URL 由 `Host`/`Origin`/`Referer`/`X-Forwarded-Proto` 推导，并拼 `X-Forwarded-Prefix`；
  反代子路径部署（如 `/app/meow`）依赖它。

### 2.5 `GET /spider-latest.jar`

优先发 **dex 形态**（jar 内有 `classes.dex`），没有才回落 class 版。`ETag` = 文件 md5
（带引号），`If-None-Match` 命中 → `304`；不存在 → `404 {"code":-1,"msg":"spider jar not found"}`。

### 2.6 `GET/POST /api/policy`

- `GET` 返回 policy 全量但**摘掉 `authToken`**、补 `authRequired`；`POST` 浅合并
  （只覆盖传入的键），返回完整 policy（**含 `authToken`**）。坏 JSON → 400。
常用键：`authToken`、`corsOrigins`、`rateLimit{parse,proxy,windowSec}`、`filterAds`、
`safeDns`、`dnsProvider`、`jsRuntime`、`pyRuntimeAddr`、`danmakuApi`、`trustProxy`、`cacheQuotaGb`、
`cacheEvictMode`、`cacheDir`、`jarStrictMd5`、`llmRelay.enabled`。
`jarStrictMd5=true` 时 jar 的 `;md5;` 不一致按致命错误处理（默认 `false`：
不匹配仍挂载 + WARN，避免配置作者忘了改 hash 就整站消失）。

### 2.7 `GET/POST /api/config/import`

```json
{"url":"http://host/config.json","mode":"replace|merge","onConflict":"keep|replace|both",
 "dryRun":false,"replaceSpider":false,"proxies":["http://127.0.0.1:7890"]}
```

| 字段 | 默认 | 说明 |
|---|---|---|
| `mode` | `replace` | `merge` 保留现有站点再并入 |
| `onConflict` | `keep` | 仅 merge 生效；`both` 另起 `key#N` |
| `dryRun` | `false` | 只算 diff，不写盘 |
| `replaceSpider` | `false` | 用导入配置的顶层 `spider` 覆盖本地；装源包时必须 true |
| `proxies` | — | 拉配置时依次尝试的 HTTP 代理 |

成功响应：`{"ok":true,"imported":true,"url":"…","mode":"replace","sites":40,
"configured":45,"totalSites":56,"skipped":[{"api":"csp_X","reason":"…"}],
"pendingSites":[{"key":"X","name":"…","api":"csp_X","reason":"…"}],
"jars":[{"spec":"…","ok":true,"md5Ok":true,"encrypted":false}],
"hint":"已写入 45 站，当前只有 56 站可用（…）","depot":[],"dex2jvm":{},
"diff":{"toAdd":[],"toUpdate":[],"conflicts":[],"unchanged":0,"removed":[],
"total":40,"healthLost":[],"mode":"replace","onConflict":""},
"backup":{"exists":true,"at":0,"bytes":0,"sites":0}}`。

- `sites` 是**可用**站数；`configured` 是写进站点表的条数（含不可实现的）。
- `pendingSites`（字段名不能叫 `pending`：`GET` 的同名字段是后台任务布尔）列出
  未生效站与原因；`jars` 给每条外部 jar 的装载结果；`hint` 在「导入成功但一个站
  都用不了」时给一句可行动的结论。

`GET` 回最近一次摘要；从未导入 → `{"ok":true,"imported":false}`。
坏 JSON / 缺 url / 非 http(s) → 400；拉取失败 / 非 JSON → 502。

### 2.8 `POST /api/config/depot`（多仓探测）与 `GET`（缓存）

```json
POST {"url":"https://…/tvboxmuti.json"}
→ {"ok":true,"index":"…","active":"","probedAt":1732…,
   "entries":[{"name":"仓1","url":"…","ok":true,"sites":151,"csp":96,
               "inJar":96,"direct":0,"hint":"可用 96 站"}]}

GET  → {"ok":true,"cached":true,"index":"…","active":"…","probedAt":…,
        "stale":false,"entries":[…]}
```

- `POST` 探测：只读 `config.sites.json`、不重建站点表；单仓地址也接受（回一条
  `name:"当前地址"`）。`inJar + direct` 才是「导入后真能用几站」，排序按它。
- 探测结果落盘 `config.depot.json`（`{index,probedAt,active,entries}`），
  `GET` 不传 url 直接回缓存；探测超过 24h 只标 `stale`（不删）。
- `entries[].ok` 可能缺失：由“直接导入索引”生成的缓存条目录有 `name/url`，
  `ok` 未知（显示「未探测」）。
- `POST` 非 http(s) → 400；拉取失败 → 502。`GET` 无缓存 → `cached:false, entries:[]`。
- 导入接口（2.7）成功时额外回 `active`（当前生效的仓），并更新这份缓存。

### 2.9 `GET/POST/DELETE /api/config/backup`

`GET` → `{"ok":true,"backup":{"exists":bool,"at":ms,"bytes":N,"sites":N}}`；
`POST` 用备份覆盖并重建站点表（没有备份 → 404）；`DELETE` 删除备份。
全新安装不产生空备份（否则「恢复」按钮看起来可用、点了却清空一切）；
恢复前先解析一次 JSON，坏备份不写回。

### 2.10 `GET/DELETE /api/logs`

`GET` 参数 `level`/`tag`/`since`/`limit`（默认 500，上限 2000）。
响应 `{"ok":true,"stats":{…,"capacity":2000},"items":[…]}`；
`DELETE` → `{"ok":true}`。高频路径（`/api/health`、`/api/logs`、`/api/proxy/*`、
`/api/cache/*`、`/api/library/stream`、`/api/transcode/*`）不写入缓冲。

---

## 第三章 内容接口

### 3.1 统一入口

```
POST /api/{site}/{action}
```

- `{site}` 是站点 **key**（多实例含 `#N`，需百分号编码），不是类名。
- `{action}`：`home|category|detail|search|play|live|action`。
- 响应是 spider 的 CatVod JSON **原样透传**（`play` 经 `normalizePlay`），没有 `ok` 包装。
- 站点不存在 / 类加载失败 → **200** + `{"code":-1,"msg":"该源在当前构建不可用（缺少 X）"}`；
  未知 action → 500（`IllegalArgumentException` 冒到最外层）。

**空结果兜底**（`emptyFallback`）：`home` → `{"class":[],"filters":{}}`；
`category`/`detail`/`search` → `{"list":[]}`；
`play` → `{"parse":0,"url":"","msg":"该源未提供播放地址"}`；`action` → 空 body。

### 3.2 `home`

请求里的 `filter` 被忽略：后端恒调 `homeContent(true)`。

```json
{"class":[{"type_id":"tv_hot","type_name":"热播剧集"}],
 "list":[{"vod_id":"1","vod_name":"…","vod_pic":"…","vod_remarks":"…"}],
 "filters":{"tv_hot":[{"key":"class","name":"类型","value":[{"n":"全部","v":"全部"}]}]}}
```

`filters` 是 `{type_id:[{key,name,value:[{n,v}]}]}`；筛选值用 `n`/`v`，不是 `name`/`value`。

### 3.3 `category`

`{"id":"tv_hot","page":1,"filters":{"class":"喜剧"}}`；`page` 可为数字或字符串。
响应字段完全由 spider 决定：`{"page":1,"pagecount":11,"limit":20,"total":219,"list":[…]}`。
**后端不做分页推算**：spider 不给 `pagecount`，响应里就没有；前端不能靠它以外的字段猜下一页。

### 3.4 `detail`

`{"id":"1292052"}`。`id` 以 `{`/`[` 开头时**整串传给 spider**，不按逗号拆——SP360 的 id
本身是 JSON；其余按逗号拆成 `List<String>` 批量详情。线路字段（前端侧）：`from` 用 `$$$`
分线路；`url` 线路间 `$$$`、集间 `#`、每集 `名称$地址`（用**最后一个** `$` 分割）。
豆瓣原生源的 `vod_play_from` 恒为空串（只有元数据没有线路），这是有意行为。

### 3.5 `search`

`{"wd":"兰香如故","pg":"1","quick":false}`；`quick` 被忽略。响应由 spider 决定，通常是
`{"list":[…],"page":1,"pagecount":1}`；空结果统一成 `{"list":[]}`，不会 200 + 空 body。
单站不聚合；聚合走 `POST /api/search`。

### 3.6 `live`

`POST /api/{site}/live` body `{"url":"…"}`，调 `liveContent(url)`，经 `normalizeLive` 归一
为数组（非数组当空）：`[{"name":"央视","channel":[{"name":"CCTV1","urls":["…"]}]}]`。
`urls` 里的 `$备用名` 原样保留，前端按 `$` 拆成同频道多线路。

### 3.7 `POST /api/search`（聚合）

请求 `{"wd":"…","threads":12,"sites":["A","B"]}`。

- `threads` 夹在 1–32；低内存设备按堆再压一层，`searchConcurrencyCapped` 说明被压低了。
- `sites` 省略 = 全站；跳过手动停用、环境不支持、连续超时、已失效的源，
  并保留至少 `MIN_ALIVE_SITES` 个源保底。
- 响应：`{"groups":[{"vod_name":"片名","vod_pic":"…","vod_remarks":"…",
  "sources":[{"site":"AppRJ","site_name":"潮流","vod_id":"123","remarks":"…"}]}],
  "skippedDegraded":3}`。
- **不按片名合并**：每源一条，否则详情页会丢线路；同名分组由前端做。
  噪音词命中时条目带 `"noise":true` 或被剔除。

**异步**：body 含 `"async":true` → 立即回 `{"taskId":"…","done":false,"returned":0,
"total":0,"noiseFiltered":0,"noiseDowngraded":0,"wd":"…"}`。
轮询 `GET /api/search/task/{id}`，`done:true` 时追加 `groups` 与 `count`；
任务保留最近 3 个、至少 60 秒。

**缓存**：`GET /api/search/cache` → `{size,max,fresh,stale,ttlHitSec,ttlMissSec}`；
`DELETE` → `{ok,cleared}`。默认命中 TTL 2 小时，空结果 180 秒。

---

## 第四章 播放与解析

### 4.1 `POST /api/{site}/play`

请求 `{"flag":"线路1","id":"http://a"}`（后端只读 `flag`/`id`，其余 body 键忽略）；响应是
spider 对象经 `normalizePlay` 的结果，**不是** `{ok,data}` 包装：

```json
{"parse":0,"url":"https://cdn.example/hls/index.m3u8",
 "header":"{\"Referer\":\"https://site/\",\"User-Agent\":\"…\"}",
 "qualities":[{"name":"1080P","url":"https://…/1080.m3u8"}],
 "subs":[{"url":"https://…/zh.srt","name":"简体","lang":"zh","format":"srt"}],
 "skips":[{"type":"opening","start":0,"end":95000},{"type":"ending","start":1380000}],
 "position":120000,
 "danmaku":"http://127.0.0.1:9979/api/proxy/danmaku?name=…&episode=1","jx":0}
```

| `url` 输入 | 处理 |
|---|---|
| 字符串 `"http://…"` | 原样 |
| 成对数组 `["标清","http://a","高清","http://b"]` | 取第一对地址，`qualities` 带标签 |
| 对象 `{"values":[{"n":"1080P","v":"http://h"}],"position":0}` | 取 `position` 指向项，`qualities` 全量 |

- 成对判定要求「奇数位是 http **且** 偶数位不是 http」；否则纯地址数组
  `["http://a","http://b"]` 会被误读成「标签=http://a」。纯地址数组按无标签处理，取第一个。
- `header` 统一成 **JSON 字符串**（spider 给对象时 `toString()`）。
- `position` / `skips` 单位**毫秒**；`skips[].type` 是 `opening|middle|ending`。
- `playerContent` 抛异常时返回 `{"parse":1,"url":"","msg":"该线路暂不可用：<Exception>"}`，
  不让整个请求 500。

### 4.2 `POST /api/parse`

请求 `{"url":"https://www.iqiyi.com/v_x.html","flag":"线路1","name":"可选"}`。
成功 `{"ok":true,"url":"https://cdn.example/parsed.m3u8",
"header":"{\"Referer\":\"https://jx.example/\"}","from":"解析器名"}`；
失败 `{"ok":false,"msg":"所有解析器都没返回可用地址","tried":4}`。

- `url` 先过**严格档 SSRF**（拒私网/回环），否则这端点就是「读内网 JSON」的探测口。
- 候选来自 `parses` 表；`flag` 是**优先适配**不是白名单：过滤后为空会退回全部可用解析器。
- 服务端支持 type 0/1/2/3，并发跑、先成功者胜（即 type 4 语义），
  总超时 `PARSE_TIMEOUT_MS = 15s`。
  - type 0 是**近似**：抓 HTML 按 `player_aaaa` / JSON 里的 m3u8 找地址；纯 JS 计算的拿不到。
  - type 2/3 反射 jar 里的 `parser.JsonXxx` / `parser.MixXxx`。
- `from` 是命中的解析器名，必须回传。`header` 合并解析器响应里的 `ua`/`referer`/`cookie`
  与配置头。解析器返回 `jx` 时原样带回，表示「还需再解析一次」。
- **没有 `skippedBrowser` 字段**：type 0 现在也会尝试。
- URL 是字符串直拼（`{parse.url}` + 播放页地址），不是 URL 编码——与上游一致。

---

## 第五章 代理与弹幕

### 5.1 `GET /proxy`（jar 端口握手）

不是 `/api/proxy`，是 jar 内 `com.github.catvod.spider.Proxy` 的探测端点：

- `?do=ck` → 纯文本 `ok`；其他 `do` → 反射 `Proxy.proxy(Map)`，把
  `(status, contentType, InputStream)` 原样回传；反射失败 / 无该类 → `501`，
  jar 内部抛异常 / 无 body → `502`。必须排在 `/api/proxy/` 兜底之前。

**为什么必须回 `ok`**：`Proxy` 的端口初值是 `-1`，首次 `getPort()` 从 9978 扫到 9999
请求 `{origin}/proxy?do=ck`，正文为 `ok` 才记住；否则 Hxq/Bili 会产出
`http://127.0.0.1:-1/proxy?...`。

### 5.2 `GET /api/proxy/stream`

```
/api/proxy/stream?url=<urlencoded>&referer=<urlencoded>&ua=<urlencoded>&headers=<JSON>
```

| 参数 | 说明 |
|---|---|
| `url` | 必填 |
| `referer` / `ua` | 留空时回退 `headers` 表；UA 最终兜底 `Mozilla/5.0` |
| `headers` | 额外请求头，JSON 对象；**最多 24 个**、序列化 **≤2048 字符**，超限整体丢弃 |

**禁用头**：`host`、`connection`、`content-length`、`content-type`、`transfer-encoding`、
`range`、`accept-encoding`、`te`、`upgrade`、`proxy-authorization`、`proxy-connection`。
值含 `\r`/`\n` 的条目丢弃（防注入）。分片 URL 继承同一份 `headers`，否则首片拿到、
后续分片又 403。

**行为：** SSRF 入口宽松档（允许局域网，拒回环/链路本地），重定向逐跳严格档、最多 5 跳
（超限 502）；Range 回 `206` + `Content-Range` + `Accept-Ranges: bytes`；同时最多 8 个分片
代理，超限 `503 {"code":-1,"busy":true,"msg":"分片代理繁忙，请重试"}`。m3u8 重写：
`/` 开头 → `origin + uri`，相对路径 → 当前清单目录 + uri，分片基准用**最终** URL。
`ads`/`rules` 默认关闭，`policy.filterAds=true` 才生效：`ads` 命中 host 丢弃；`rules`
按条目删时长或正则替换；护栏是「分片清零或删掉超过 50% 时长 → 回退原清单」。

### 5.3 `GET /api/proxy/image`

`/api/proxy/image?url=<urlencoded>[&referer=…][&ua=…]`。

- `url` 支持 `图片地址@Referer=…@User-Agent=…` 内嵌尾巴，也接受独立 `referer`/`ua`。
- 入口宽松档 SSRF；回环与云元数据仍拒绝。
- **先查磁盘海报缓存**：命中零网络请求，`Cache-Control: public, max-age=604800`，
  `X-Meow-Poster: disk`。
- 未命中：按魔数判真实格式，不信任 `Content-Type`；能识别就按 `jpeg/png/gif/webp` 下发，
  识别不出退回上游类型。**AVIF/HEIC 一律 415**（老 TV 内核解不了）。响应
  `Cache-Control: public, max-age=86400`，并顺手落盘。

### 5.4 `GET /api/proxy/danmaku`

```
/api/proxy/danmaku?name=片名&episode=1[&fresh=1]
/api/proxy/danmaku?url=<urlencoded>
```

- `name` 模式：多个弹幕源**并发**抓取、先命中先用（不再按顺序串行）。源集合与顺序由
  `policy.danmakuOrder` 配置，缺省 `iqiyi,tencent,aggregator`；配了自建 danmu_api 时
  默认顺序变为 `aggregator,iqiyi,tencent`（aggregator 桶 = `policy.danmakuApi` + 内置镜像）。
  30 分钟 TTL 缓存；`fresh=1` 跳过缓存强制重抓。`url` 模式：自家 `/xml?id=…` 就地解析、
  不做 HTTP 自调用，否则直接抓取。
- 响应 `text/xml`：`<i><d p="时间,模式,字号,颜色,时间戳,池,用户ID,弹幕ID">文本</d></i>`。
- 空结果也返回合法 XML `<?xml version="1.0"?><i></i>`，**不返回空 body**。
- 响应头：`X-Danmaku-Source`（命中的源）、`X-Danmaku-Count`（`<d ` 数量）、
  `X-Danmaku-Empty: 1`（这集没有弹幕）。

### 5.5 弹幕候选与两段式

**`GET /api/proxy/danmaku/search?name=…`**

```json
{"name":"片名","fixed":["爱爱","腾疼","悠悠","哔哔","…"],
 "sources":[{"label":"腾疼","count":197,"direct":130,"aggregator":67}],
 "items":[{"name":"片名 第1集","url":"/api/proxy/danmaku/xml?id=tencent:xxx:1",
           "source":"tencent","label":"腾疼","episode":1,"via":"direct"}]}
```

`name` 空 → `{"items":[],"sources":[],"fixed":[]}`（200）。上限 3000 条；
`sources[].count` 统计**截断后**的条数，保证「胶囊数字 == 能翻到的条数」。
排序：`episode` 升序 → `via`（direct 先）→ 源优先级 → name。

**`/list`** → 裸数组 `[{"name":"… 第N集","url":"/api/proxy/danmaku?name=…&episode=N"}]`，
无候选回 `[]`。**`/xml?id=源:资源ID:集数`** → `text/xml`，未知/空 id 也返回空 XML；
id 由我们自己的 `/list` 或 `/search` 生成，不依赖上游临时地址。
**`/prefetch`**（POST）立即返回 `{"ok":true,"queued":true|cached:true|disabled:true}`，
后台抓取；走与主请求**同一条降级链**（含用户配的 danmu_api，其默认限流 3 次/分钟——
预取一集只用 1 次额度，打不满）。开关：`policy.danmakuPrefetch`，默认开。

### 5.6 直播

**`POST /api/live`**（不校验 method，GET 空 body 也回 200）：
`{"groups":[{"site":"Xxx","site_name":"…","group":"央视",
"channels":[{"name":"CCTV1","urls":["…"]}]}],"tried":55,"failed":12,
"playlists":[{"name":"源名","url":"http://…/live.m3u"}]}`。总预算 12s；`urls` 可能带
`$备用名`，原样透传；`playlists` 只列直连地址，**不解析内容**。

**`POST /api/live/playlist`** body `{"url":"…"}` → `{"ok":true,"groups":[…],
"channels":42,"updated":"<Last-Modified>"}`；失败始终 200
`{"ok":false,"msg":"url required"|"url not allowed"|"HTTP 404"|"parse failed: …"}`。
认 `group-title` 与 `#genre#`；BOM/CRLF 会被清掉。

**`GET/POST/DELETE /api/lives`**：

- `GET` → `{ok,lives:[{name,url}],builtin:[url…]}`；`builtin` 是尚未被删的内置源 URL。
- `POST {name,url,verify?}` → 同 url 已存在只更新名字并回 `duplicate:true`；`verify:true`
  真拉一遍，失败回 `{ok:false,verified:false,msg}`（不写盘）。
- `DELETE {url}` → 删该 url 全部条目；不存在 → 404；缺 url → 400。删内置源时写
  `builtinLiveOff` 逐条禁用标记，否则下次读表又会补回默认值。

---

## 第六章 状态

存储 `state-progress.json` / `state-favorites.json` / `state-prefs.json`；
写入用 `STATE_LOCK` 串行化；文件缺失/损坏一律当空，不 500。

### 6.1 `GET/POST /api/state/progress`

**GET 单条** `?site=…&vodId=…`（`vod_id` 也认）→ `{"ok":true,"item":{"site":"AppRJ",
"vod_id":"123","pos":1421,"dur":2700,"flag":"线路1","ep":"第3集","ep_url":"…",
"ep_at":1792550000000}}`。无记录 → `{"ok":true,"item":null}`；只带一个键 → 400。
**GET 全量**（一个键都不带）→ `{"ok":true,"items":[…最多 500 条，按 ep_at 倒序…]}`。

**POST**（并集写）：`{"site":"AppRJ","vod_id":"123","ep":"第3集","pos":1421,
"dur":2700,"ep_at":1792550000000}` → `{"ok":true,"merged":true}`。

| 约束 | 说明 |
|---|---|
| `pos`/`dur` 单位 | **秒** |
| 合并键 | `site + vod_id`；只有 `ep_at` **严格大于**旧值才覆盖，否则 `merged:false` |
| `ep_at` / `pos` | `ep_at` 缺省或 ≤0 填服务端时间；`pos` 非有限数或 <0 → 400，`0` 合法 |
| 字段沿用 | 未带 `flag`/`ep`/`ep_url` 沿用旧值（显式空串可清空）；`dur=0` 不覆盖已知时长 |
| 容量 | 2000 条，超限按 `ep_at` 静默淘汰最旧 |

### 6.2 `GET/POST/PUT/DELETE /api/state/favorites`

**GET** → `{"ok":true,"items":[{site,vod_id,vod_name,vod_pic?,at}]}`，按 `at` 倒序，上限 5000。

**POST/PUT** `{"site":"AppRJ","vod_id":"123","vod_name":"片名"}` → `{"ok":true,"count":1}`。
幂等：同 `site+vod_id` 只更新元信息、**保留首次 `at`**；缺 `site`/`vod_id` → 400。
**DELETE** `?site=…&vodId=…` → `{"ok":true,"count":0}`；不存在也回 200（幂等），
且**不写盘**。其它方法 → 405。

### 6.3 `GET/POST/PUT /api/state/prefs`

**GET** → `{"ok":true,"at":1792737523880,"prefs":{"homeCols":8,"themeMode":"light"}}`。
从未写过 → `at=0`、`prefs={}`；前端据此判断「没有远端设置」，**不能拿空对象覆盖本地**。

**POST/PUT** 两种写法都收：`{"prefs":{…}}` 或字段摊在顶层。
响应 `{"ok":true,"applied":N,"prefs":{…合并后全量…},"at":…}`。

| 行为 | 说明 |
|---|---|
| 白名单才落盘 | 只接受 `STATE_PREFS_SYNC` 的 19 个键，未知键静默丢弃 |
| `applied:0` / 空 body | 全是不可同步字段 → 200 不写盘；空 body 或空 `prefs` → 400 |
| 增量合并 / 方法 | 只更新提交的键（`null` 删除）；只接受 GET/POST/PUT，其余 405 |

**可同步（19 项）**：`homeCols`、`searchThreads`、`searchLayout`、`cacheDays`、
`historyLimit`、`danmaku*`（6 项）、`autoNext*`（2 项）、`skip*`（4 项）、
`themeStyle`、`themeMode`。**不同步**：`volume`/`muted`/`rate`/`videoFit`、`wallpaper*`、
`webdavUrl`/`webdavPass`/`webdavUser`（**凭据绝不上传**）。

**没有 `/api/state/history`**：观看历史由 progress 派生。

---

## 第七章 运维与本地能力

按域分组的骨架；字段细节以代码为准，只列调用方必须知道的形状与约束。

| 端点 | 作用 | 关键约束 |
|---|---|---|
| `GET/POST /api/library`、`GET /api/library/stream` | 片库配置、列目录、流 | `path` 是**相对库根**的路径，服务端拼接；`stream` 必须透传 Range |
| `POST /api/webdav` | WebDAV 转发（PUT/GET/DELETE/MKCOL/PROPFIND） | **始终 200** + `{ok,status,body}`；传输失败 `ok:false,status:0,msg`；`PROPFIND` 默认 `Depth: 1`；body 截断 200KB |
| `/api/cache*`（10 条） | 离线缓存：批次、状态、续传、导出 | `GET /api/cache` 带 `cacheDirReloadRequired`；导出格式由 `X-Meow-Export-Format`（mp4/ts）说明；`export-to` 仅回环可用 |
| `POST/GET/DELETE /api/transcode` | MKV/HEVC 现转 HLS | 不带 `src` 回 `{available,bytes}`（探测 ffmpeg）；首次 `state=running` 且清单为空**不能判失败**；状态 JSON 含 `duration`（源真实总时长，秒）/ `readySeconds`（已转出，含 `startOffset`，按清单 EXTINF 求和）/ `startOffset`；`start=N` 从源第 N 秒起转（参与幂等键，status/DELETE 必须带同一个值） |
| `GET/POST/DELETE /api/scrape` | 刮削 | 缺 `groups` / `title` → 400 |
| `GET/POST /api/cast` | DLNA 发现 / 控制 | POST 需 `device{controlUrl,…}`；投的 url 走宽松档 SSRF |
| `GET /api/dns/resolve?host=&provider=` | DoH A 记录 | provider 默认 `ali`，缓存 5 分钟；失败也 200，`ip:""` + `msg` |
| `GET/DELETE /api/posters` | 海报缓存统计 / 清空 | 清空回 `{ok:<删除数>}` |
| `POST /api/llm/relay` | 纯 Web 环境转发模型请求 | 默认 **403**，需 `policy.llmRelay.enabled=true`；body `{baseUrl,apiKey,body}` |

---

## 第八章 错误与降级

### 8.1 HTTP 状态码

| 状态 | 什么时候 | body |
|---|---|---|
| `200` | 正常；也可能业务失败 | 领域 JSON / `{"code":-1,"msg"}` / `{"ok":false,…}` |
| `400` | 坏 JSON、缺必填参数、参数超范围 | `{"code":-1,"msg":"…"}` |
| `401` | 写操作缺令牌 / 不匹配 | `unauthorized: X-Meow-Token required` |
| `403` | SSRF 拒绝、跨源写、`export-to` 非回环、relay 未启用 | `{"code":-1,"msg":"…"}` 或 `{ok:false,error}` |
| `404` | 未知端点、任务/备份/缓存导出/jar 不存在 | `{"code":-1,"msg":"…"}` |
| `405` | 方法不允许 | `{"code":-1,"msg":"…"}` |
| `415` | 图片是 AVIF/HEIC | `upstream image format not supported: …` |
| `429` | 限流 | `too many requests` |
| `500` | 未知 action、未捕获异常、配置/状态写失败 | `{"code":-1,"msg":"<异常>"}` |
| `501` | `/proxy` 无 jar 能力 / 未知 `/api/proxy/*` | `{"code":-1,"msg":"…"}` |
| `502` | 上游拉取失败、重定向过多、代理无 body | `{"code":-1,"msg":"…"}` |
| `503` | 分片代理并发满 | `{"code":-1,"busy":true,"msg":"分片代理繁忙，请重试"}` |
| `304` | jar 的 `If-None-Match` 命中 | 无 body |

**站点不可用不是错误状态**：`/api/{site}/{action}` 类加载失败仍回 200 +
`{"code":-1,"msg":"该源在当前构建不可用（缺少 X）"}`。前端按 `code:-1` 处理。

### 8.2 业务失败的形状

| 场景 | 形状 |
|---|---|
| 解析失败 | 200 `{"ok":false,"msg":"…","tried":N}` |
| 线路不可用 | 200 `{"parse":1,"url":"","msg":"该线路暂不可用：…"}` |
| 站点返回空 | 200 `{"list":[]}` / `{"class":[],"filters":{}}` / play 兜底 |
| 搜索无词 | 200 `{"groups":[]}` |
| 直播列表失败 | 200 `{"ok":false,"msg":"…"}` |
| 片库单库连不上 | 200 `{"ok":false,"msg":"…"}`（其它库仍渲染） |
| WebDAV 传输失败 | 200 `{"ok":false,"status":0,"msg":"…"}` |
| 缓存导出无分片 | 404 `{"code":-1,"msg":"这一集还没有缓存分片；先下载完成再导出"}` |

### 8.3 降级

`capabilities` 是**声明**不是承诺：后端只报实际可用的能力。前端不应根据 `version`
猜能力（版本号不带语义）。当前 Web 只消费 `state` 一位。

---

## 第九章 版本兼容

1. **只增字段**：新增字段必须有默认值；删除/改名字段 = 破坏性变更。
2. **站点接口的顶层字段保留**：`home`/`category`/`detail`/`search` 直接透传
   `class`/`list`/`filters`/`page`，不额外包 `ok`。
3. **`/api/policy` 与 `/api/config` 保留**：新状态端点、`/config.json` 与它们并存。
4. 前端判断能力只看 `/api/health.capabilities`（缺失按空集合处理），不比较 `version`。

---

## 附录 A 端点全表

`equals(path)` 显式路由 57 条 + 少量前缀兜底。路径即契约。

| 域 | 方法 | 路径 |
|---|---|---|
| 系统 | GET | `/api/health`、`/api/sites`、`/api/config`、`/config.json`、`/spider-latest.jar` |
| 系统 | GET/POST | `/api/policy`、`/api/config/import` |
| 系统 | POST | `/api/config/depot` |
| 系统 | GET/POST/DELETE | `/api/config/backup` |
| 系统 | GET/DELETE | `/api/logs`、`/api/posters` |
| 内容 | POST | `/api/{site}/{home\|category\|detail\|search\|play\|live\|action}`、`/api/search` |
| 内容 | GET | `/api/search/task/{id}` |
| 内容 | GET/DELETE | `/api/search/cache` |
| 内容 | GET/POST/DELETE | `/api/lives` |
| 内容 | POST | `/api/live`、`/api/live/playlist` |
| 解析 | POST | `/api/parse` |
| 代理 | GET | `/proxy`、`/api/proxy/stream`、`/api/proxy/image`、`/api/proxy/danmaku`、`/api/proxy/danmaku/search`、`/api/proxy/danmaku/list`、`/api/proxy/danmaku/xml` |
| 代理 | POST | `/api/proxy/danmaku/prefetch` |
| 状态 | GET/POST | `/api/state/progress` |
| 状态 | GET/POST/PUT/DELETE | `/api/state/favorites` |
| 状态 | GET/POST/PUT | `/api/state/prefs` |
| 健康 | POST | `/api/health/probe`、`/api/health/degrade`、`/api/health/reset` |
| 健康 | GET | `/api/health/state` |
| 本地 | GET/POST | `/api/library`、`/api/cast` |
| 投屏 | GET/POST | `/api/cast/health`、`/api/cast/base`、`/api/cast/receiver`、`/api/cast/receiver/pending`、`/api/cast/session`、`/api/cast/command`、`/api/cast/progress` |
| 本地 | GET | `/api/library/stream`、`/api/dns/resolve`、`/api/transcode/status`、`/api/transcode/{id}/index.m3u8`、`/api/transcode/{id}/{seg}` |
| 本地 | POST | `/api/webdav`、`/api/llm/relay` |
| 本地 | GET/POST/DELETE | `/api/cache`、`/api/transcode`、`/api/scrape` |
| 本地 | POST/GET | `/api/cache/batch`、`/api/cache/status`、`/api/cache/tasks`、`/api/cache/resume`、`/api/cache/control`、`/api/cache/grouped`、`/api/cache/poster` |
| 本地 | GET/POST | `/api/cache/export`、`/api/cache/export-to` |

**兜底：** `/api/search/task/*`、`/api/library/*`、`/api/cache/*`、`/api/state/*`、
`/api/health/*` 未知子路径 → 404；`/api/proxy/*` 未知 → 501 `proxy not implemented`；
其余 `/api/*` 按 `{site}/{action}` 分派，段数不足 → 404。
