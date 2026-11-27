# 数据与状态

收藏、进度、历史、偏好、站点配置与策略都存在本地数据目录。项目**没有账号体系**，也不向项目自己的服务器上传；
只有你启用的功能会向第三方发请求（WebDAV 同步、豆瓣刮削、弹幕上游、LLM 中转、GitHub 更新检查）。

## 一、数据根目录怎么定

`server/host/src/shared/core/DataPaths.java` 的优先级（`DataPaths.java:58-77`）：

```
-Dmeow.data  >  MEOW_DATA  >  --data-dir=...  >  ./data
```

| 端 | 实际路径 | 怎么传 |
|---|---|---|
| Web / Docker | 容器内 `/data`，宿主 `deploy/docker/../../data` | compose 里 `MEOW_DATA=/data`（`docker-compose.yml:38-42`） |
| PC | `%APPDATA%\com.meow.player\data` | 插件清单 `--data-dir={dataDir}`，`dataDir={appData}/data`（`apiserver.manifest.json:18,34`；identifier 在 `tauri.conf.json:5`） |
| TV | `<filesDir>/gateway` | 端内网关 `--data-dir`（`GatewayServer.java:538-539`） |
| 飞牛 fnOS | `<pkg var>/data` | supervisor `--data-dir`（路径在 `config.rs` 的 `pkg_var.join("data")`，参数在 `supervisor.rs` 的 `spawn_api()` 里） |

::: danger 相对路径是历史坑
早期 `new File("policy.json")` 跟着工作目录走，Docker 里会写到 `/`、容器重建即丢。现在统一走
`DataPaths`：`write()` 是 `.tmp` + rename 原子写，`dir()` 自动建目录，读失败返回 `null`
（`DataPaths.java:85-130`）。**新增文件必须用它，不要自己拼路径。**

> `DataPaths.java:22-30` 的类注释还写着 `state/（阶段 3）` 与 `jar/`——那是旧设计；
> 实际布局是根下 `state-*.json` 与 `jars/` 目录（`Jars.java:207`）。以 §二 的表为准。
:::

## 二、目录里有什么

| 文件 / 目录 | 内容 | 丢了会怎样 | 能删吗 |
|---|---|---|---|
| `config.sites.json` | `sites` 表 + `parses` / `lives` / `rules` / `ads` / `proxy` / `hosts` / `doh` / `headers` / `flags` 表；导入前自动备份为 `.bak` | 要重新导入源包 | ❌ |
| `policy.json` | 运行时策略（见 §三） | 弹幕、DNS、缓存配额等设置丢失 | ❌ |
| `state-progress.json` / `state-favorites.json` / `state-prefs.json` | 跨端进度（写回上限 2000、拉取 500）/ 收藏（上限 5000）/ 偏好白名单（见 §四） | 其他端对应数据无法同步 | ❌ |
| `site-health.json` | 站点探活 / 降级 / 停用标记（`server/host/src/shared/routes/HealthRoutes.java`，grep `site-health.json`） | 重新探测即可 | ✅ |
| `library.json` | 片库配置（**含密码**） | 重新配片库 | ❌ |
| `scrape-cache.json` | 刮削结果（命中永久、未命中 10 分钟） | 重新刮 | ✅ |
| `posters/` / `jars/` | 海报落盘（500MB 配额）与 spider jar 缓存 | 重新拉 / 下载 | ✅ |
| `cache/{video,transcode,danmaku}/` | 离线分片、转码产物、弹幕缓存（30 天 TTL） | 重新下载 / 转码 / 拉取 | ✅ |
| `cache/tasks.json` | 未完成任务 / 批次索引 | 丢「继续下载」入口，分片还在 | ⚠️ |

> Docker 里的 `data/danmu/` 是 **danmu_api 容器的 `/app/config` 挂载**（`docker-compose.yml:111-113`），不是 JVM 弹幕缓存；JVM 的缓存在 `cache/danmaku/`。

## 三、`policy.json` 的字段

键很多，按用途分组即可；写入走 `POST /api/policy`，后端**按 key 合并**，不是整份替换（`handlePolicy`，grep `handlePolicy`）。

| 用途 | 字段 |
|---|---|
| 弹幕 | `danmakuApi`、`danmakuTencent`、`danmakuOrder`、`danmakuPrefetch`、`danmakuPrefetchLead` |
| DNS / 源 / 搜索 | `safeDns`、`dnsProvider`、`jsRuntime`、`pyRuntimeAddr`、`filterAds`、`searchNoise`、`searchNoiseMode` |
| 缓存 | `cacheDir`、`cacheQuotaGb`、`cacheEvictMode`、`danmakuCacheQuotaMb`、`danmakuCacheEvictMode`、`ffmpegPath` |
| 安全 / 网络 | `authToken`、`corsOrigins`、`rateLimit`（`parse`/`proxy`/`windowSec`）、`trustProxy` |
| 模型转发 | `llmRelay.enabled`（`LlmRelay.java:67-70`） |

- `authToken` / `corsOrigins` 都是**环境变量优先**（`MEOW_AUTH_TOKEN` / `MEOW_CORS`，
  `ApiServer.java` 的 `authTokenExpected()` / `corsAllowList()`）；`GET /api/policy`
  回包摘掉 `authToken`、只回 `authRequired`（`handlePolicy`，grep `p.remove("authToken")`）。
- `proxy` / `hosts` / `doh` 是 **`config.sites.json` 里的表**，不是 policy 字段。

::: warning 插件 `inject` 不覆盖手填值
PC / fnOS 的插件宿主在插件就绪后把地址写进 `policy.json` 的指定 key
（manifest 里 `inject.target` / `key` / `value` 带 `{appData}`、`{port}` 占位）。
**目标键已有非空值且不等于本插件地址时会跳过**——PC 在 `plugins.rs:929-947`
（desktop），fnOS 在 `plugins.rs:820-848`（多带一个「自己上轮写的值可更新」参数）。
「装了插件却还在用旧地址」时先看 policy 里是不是被手填值占着。Web / TV 不走这条自动接线。
:::

## 四、跨端通道

| 通道 | 机制 | 覆盖范围 |
|---|---|---|
| 后端 state 目录 | `/api/state/{progress,favorites,prefs}` | 进度、收藏、偏好白名单；连同一后端时秒级（进度 8s / 收藏 1s / 偏好 4s 防抖，`main.ts:690-706`） |
| WebDAV 快照 | `history.json` 并集、`search-history.json` 并集、`backups/prefs-*.json`、可选 `library.json` | 启动拉历史/搜索词；变更后防抖上传（history 5s / prefs 3s / 搜索词 4s，`main.ts:414-424`） |
| 本地存储 | localStorage | 单端权威；后端/WebDAV 不可用时照常工作 |

- **进度**：后端只在 `ep_at` **严格更新**时覆盖，避免「手机打开一下把 PC 的 20:00 冲成 0」；同集允许小幅回退，拒绝切集后旧集的迟到写入（`StateRoutes.java:187-220`、`history.ts:167-215`）。
- **收藏**：localStorage 权威（本地上限 500，`favorites.ts:24`），后端只是跨端通道（上限 5000），后端挂了照样能加能删。
- **偏好**：只同步 `STATE_PREFS_SYNC` 白名单里的 19 个键（`StateRoutes.java:47-55`）。`STATE_PREFS_SKIP` 只有声明、全仓无读取点——**不要拿它当防护网**，白名单才是。
- **WebDAV 历史是并集**，不是整份覆盖：元信息按 `at`、进度按 `ep_at` 字段级合并（`sync.ts:141-185`）。设置是**时间点快照**：文件名带时间戳、保留最近 10 份（`MAX_SNAPSHOTS`，`sync.ts:34`）、内容相同则跳过；还原只还原设置（`prefsOnly`），且快照里的 prefs 已剥掉 `llmApiKey`（`prefs.ts:621-648`、`sync.ts:315-360`）。
- **片库配置**可选同步，且**不含密码**——上传时明确只带 name/type/base/user/root/domain（`sync.ts:534-557`）。

> 多端同时写 `history.json` 时以后写者为准，下次拉取会并集补回；快照按时间戳分文件，不会互相覆盖。离线缓存与片库**文件**不在任何通道里。

## 五、状态写入的时机

| 数据 | 写入时机 | 注意 |
|---|---|---|
| 播放进度 | 播放器每 5 秒心跳；切集 / 换线 / `pagehide` / 卸载各补一次 | 同集只前进；换线路 `ep_url` 变即允许写入 |
| 历史 | 点封面就建条目；播放页首次有效进度（`pos >= 1`）时补进度与元信息 | 快看完（`pos > dur - 10`）清掉进度，避免下次直接跳片尾（`PlayView.vue:1191-1192`） |
| 收藏 | 操作时立即写 localStorage，再异步推后端 | 本地权威，推失败不回滚 |
| 观看统计 | 随心跳累加播放器给的 `watched` 墙钟增量 | **不能用 `pos` 差**：心跳 5 秒，差值恒为 5，会被当成 seek 丢掉 |
| 搜索词 | 搜索时写入，最多 20 条（`searchHistory.ts:12`） | WebDAV 并集同步 |

5 秒心跳在 `VideoPlayer.onTimeUpdate`（`>= 5` 才发，`VideoPlayer.vue:2082`）；进度节流落盘时 `notify:false`，
不触发 WebDAV 上传（`history.ts:135-163`），否则播放全程每 5 秒就 PUT 一次整份历史。

**片库没有 VOD 的 `site/vod_id`**，用固定键让同一套 `saveProgress/applyResume` 直接复用：

| 字段 | 片库取值 | 例 |
|---|---|---|
| `site` / 历史 `siteName` | `__library__` / 「片库」 | — |
| `vod_id` | `<库id>\|<剧目录>`（`LibraryView.play()` 写） | `libmwpo31pz\|武林外传` |
| `ep_url` | 相对库根的**文件**路径 | `武林外传/01.mp4` |
| 续播入口 | `HistoryView.open()` 按 `site==='__library__'` 特判，重建 `#/play/__library__?library=…&file=…` | — |

切集时 `vod/id` 必须带进 query（`PlayView.switchEpisode` 的片库分支），否则换集后进度落到
另一条 key 上；历史卡片的「详情」则跳回片库目录 `#/library?lib=…&path=<剧目录>`。

## 六、什么能安全删除

```bash
# 可以删，会自动重建（建议先停后端，避免任务跑到一半）
rm -rf data/cache data/posters data/jars
rm -f data/site-health.json data/scrape-cache.json
# 不能删
# data/config.sites.json      data/policy.json        data/library.json
# data/state-progress.json    data/state-favorites.json  data/state-prefs.json
```

`cache/tasks.json` 删了不会丢分片，但「未下完」列表会空；想保留续传入口就别删。

设置页没有「一键清 `cache/video`」：离线缓存在「我的缓存」逐条删；「存储 → 清空本地缓存」只清浏览器
Cache Storage 与 sessionStorage，**不碰** `data/posters` 与 `data/cache`（`SettingsView.vue:1126-1175`）。
后端有 `DELETE /api/posters`（统计/清空），但设置页未接入。

## 七、改这块之前

| 要改什么 | 先看 |
|---|---|
| 新增一个状态文件 | `DataPaths`（勿自拼路径）+ 备份页 + 三条通道各自是否要带 |
| 改进度写入频率 | 播放器心跳、`saveProgress` 节流、后端 `ep_at` 合并三处；顺带检查观看统计的 `watched` 口径 |
| 加可跨端字段 | 本地 store + `STATE_PREFS_SYNC` 白名单 + WebDAV 合并；本机专属字段别进白名单 |
| 改 `policy.json` 键名 | 后端读取、设置页、插件 manifest 的 `inject.key` 三方同步 |
| 加可删缓存 | 维护清理入口 + 文档；别让「清缓存」碰到历史与收藏 |

## 相关

- 备份与恢复：[备份与恢复](/guide/backup)
- 数据目录结构（代码）：`server/host/src/shared/core/DataPaths.java`
- 跨端同步的用户说明：[播放与弹幕](/guide/playback)
