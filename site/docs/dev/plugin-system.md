# 插件系统

PC 端把「影视后端 / 弹幕聚合 / 脚本运行时」做成可独立启停的 sidecar 进程。
插件本体不进安装包，运行时从插件源下载、校验、解压到用户数据目录。
本文只写 PC（Tauri）端；fnOS 的 `meowd` 与 TV 运行时模块是另外的实现。

| 关注点 | 代码 |
|---|---|
| manifest、进程启停、端口协商、健康检查、孤儿清理 | `client/apps/desktop/src-tauri/src/plugins.rs` |
| 清单拉取、下载、校验、解压、原子替换、回滚、插件源与令牌 | `client/apps/desktop/src-tauri/src/installer.rs` |
| Tauri 命令与启动顺序 | `client/apps/desktop/src-tauri/src/lib.rs` |
| apiserver / danmu-api / drpy 的 manifest | `server/plugin-manifests/*.manifest.json` |
| 「本机插件」面板 | `client/apps/web/src/views/SettingsView.vue` |

`inject` 写进 `policy.json` 的键如何被后端消费，见 [配置系统](./config-system.md)；
弹幕链路见 [弹幕](./danmaku.md)。

---

## 一、整体模型

### 1.1 组件与职责

- `PluginManager`（`plugins.rs`）：扫描 `plugins/`、启停进程、协商端口、轮询健康、
  写 `enabled.json`、清孤儿。不下载、不校验包。
- `Installer`（`installer.rs`）：拉清单、解析源、下载、SHA256、解压、原子替换、
  回滚、记 `installed.json`。不决定进程怎么跑。
- 前端：展示插件清单与状态、发起安装、轮询进度、拼 API 基地址。插件清单不由页面读字节或下载。
- `plugin_install`（`lib.rs`）：停旧进程 → 装 → 启用并启动验证 → `finalize`。

**插件全部外置**：安装包不带 `plugins/`，默认首开是「后端未连接」。
插件根只有两个来源（`resolve_plugins_root`）：`MEOW_PLUGINS` 环境变量（开发，路径必须已存在），
或 `%APPDATA%/com.meow.player/plugins`（正式安装唯一位置）。
不保留 `resource_dir()/plugins`：dev 下那就是 `target/debug/`，
残留会让「清空 `%APPDATA%` 的裸安装」仍然"发现有插件"。
主位置必须是 `%APPDATA%`——`C:\Program Files` 不可写，下载完没法解压。

### 1.2 目录布局

```
%APPDATA%/com.meow.player/
├── plugins/<id>/          ← 生效目录（只读这里）
├── plugins.d/             ← installed.json / download-cache / <id>.tmp / <id>.bak
│                            / local-manifest-<ms>.json / import-<s>.tmp
├── enabled.json           ← {id: bool}
├── plugin-sources.json    ← {current, items:[{id,name,url}]}
├── plugin-token.txt       ← 私有仓令牌（独立文件，不进会回传前端的源列表）
├── git-proxies.json       ← 加速前缀
├── plugin-source.txt      ← 旧版遗留的单源地址，启动时仍兼容读一次
└── data/                  ← policy.json
```

### 1.3 改这块之前要知道

- **只有 Windows 实现完整**：下载用系统 `curl.exe`、解压用 `tar.exe`；
  Job Object 与 Toolhelp 孤儿清理都是 Windows API，非 Windows 下是空实现。
- **刻意零新增依赖**：健康检查是手写 HTTP/1.1 GET + `TcpStream`，进程用
  `std::process::Command`，校验用 `sha2`。引 `reqwest`/`tokio` 会拖进异步栈。
- **sidecar 的 stdout/stderr 被丢弃**（`Stdio::null`）。插件日志拿不到，
  排障只能照 manifest 的命令行手工跑一遍。
- **单实例必须先于 `PluginManager::new`**：`new` 会按插件目录清孤儿，第二实例
  先构造就会杀掉第一实例的后端。**控制台子进程一律走 `crate::proc::quiet`**——
  curl / tar / 插件都是控制台程序，漏一处就弹黑框（installer 与 appupdate 曾各漏过）。

---

## 二、普通用户视角

### 2.1 首次使用是两步

Rust 侧默认不预置插件源（`SourceList::default` 为空）——写死一个不存在的"官方源"
会让首开就 404 红条。但 PC 设置页默认勾选「使用应用更新源」（`pluginUseAppSource`
默认 true）：会把应用更新源（内置默认是 GitHub repo 端点 `…/releases/latest`）
落成一条名为「应用更新源」的插件源；取不到地址时才退回空列表 + 分步引导。

1. **配源**（通常可跳过）：已有「应用更新源」就能直接下载；也可「添加」自建源，
   或点「填入本机测试源」（`http://127.0.0.1:9000/packages.json`）。
2. **下载**：点「下载 <大小>」。流程固定为：停旧进程（仅当在运行）→ 清孤儿 →
   下载校验解压 → 原子替换 → `set_enabled(true)` → 启动 → 健康检查通过 `finalize`
   删备份，失败则回滚。

端口从 manifest 的 `port` 开始协商（apiserver 是 9979），占用就顺延。首页恢复
不用手动刷新：`initBackend()` 每 600ms 问一次 `boot_info`，最多 60 秒；后端未装、
被禁用或启动失败时立刻返回，错误态下 `App.vue` 每 5 秒重试。

### 2.2 依赖与工具型插件

- `requires` 递归安装（本地版本与远端不同就重装）；`hidden: true` 只在可安装
  列表隐藏，**不阻止**它作为依赖被装上。
- Node 运行时装成工具型（`tool: true`）：只提供文件，不常驻。列表显示「已就绪」，
  没有启停按钮，开关也禁用（4.6）。FFmpeg 转码器同理，且默认 `hidden`——
  插件 zip 约 37MB（解压后 `ffmpeg.exe` 约 100MB），装不装都不影响在线播放。

### 2.3 离线两条通道

| 通道 | 做什么 | 校验 |
|---|---|---|
| 粘贴 JSON | 清单存成 `plugins.d/local-manifest-<ms>.json`，源变成 `file://…` | 只要求 `schema_version == 1` |
| 导入本地包 | 直接选 zip 安装，不经过插件源 | 不校验远端 SHA256，把实际文件 hash 记进 `installed.json` |

**粘贴 JSON 后 `url` 写相对路径会从清单所在目录复制**（`resolve_download_url`
的 `file://` 分支 + `download` 的本地复制分支），zip 要和清单放同一目录，
否则写绝对 `http(s)` 地址。**导入本地包只要求包内有可解析的 `manifest.json`
且能读出 `id`**，`exec` 不校验，缺了要到扫描/启动阶段才报错。

### 2.4 改用远端后端与地址显示

| 场景 | 操作 | 界面 |
|---|---|---|
| 连别人跑好的服务 | 「后端地址」填 `http://192.0.2.10:9979` → 保存 | 可编辑，有「保存」+「用插件」 |
| 用本机插件 | 点「用插件」清掉手填地址 | 只读，显示协商端口，无「保存」 |

关掉本机后端（关开关或点「停止」）是合法形态，不算错误。

两个易误解点：**手填回环地址不压过插件**——插件顺延到 9980 时优先用插件地址，
要连本机另一个服务（如 Docker 的 30011）先把插件关掉；**未运行时列表里的端口
不可信**——`discover()` 回退显示 `manifest.port`，那只是首选值，不代表已监听。

---

## 三、插件源（索引清单）

### 3.1 源地址写法

插件源文件名是 **`packages.json`**（`index.json` 是早期别名，仍兼容）。

| 写法 | 行为 |
|---|---|
| `http://host:8099/` | 依次试 `packages.json`、`index.json`，取第一个能取到的 |
| `http://host:8099/packages.json` | 直接用 |
| `https://api.github.com/repos/<o>/<r>/releases/latest` | 取 release JSON，再取其中名为 `packages.json` 的资产 |
| `file:///…/local-manifest.json` | 直接读本地文件（「粘贴 JSON」生成） |

`normalize_source`：空地址、非 `http(s)` 且非 `file://` 拒绝；`.json` 结尾
**不补斜杠**（否则拼成 `xxx.json/index.json` 404）；release 端点**去掉尾斜杠**
（`/releases/latest/` 直接 404）；其余补 `/`。目录地址同时接受 `index.json`
是兼容旧源，新源一律写 `packages.json`。

### 3.2 清单结构

顶层 key 是插件 id，**不要用数组**：手写时一眼看出装了哪个，
数组漏个括号整份配置就废了（示例里的 `size` 数值仅示意）。

```json
{
  "schema_version": 1,
  "packages": {
    "apiserver": {
      "display_name": "影视后端", "version": "1.1.6", "platform": ["windows-x64"],
      "size": 1234567, "sha256": "0f8a60aa…",
      "url": "apiserver-1.1.6.zip", "requires": [], "hidden": false
    }
  }
}
```

### 3.3 字段：哪些真正生效

结构上所有字段都有 `#[serde(default)]`，缺字段不报解析错（`requires` 还把 `null`
容错成空数组——PowerShell 的 `ConvertTo-Json` 会把空数组写成 `null`）；
缺 `url` / `sha256` 会在下载或校验阶段失败。

| 字段 | 生效方式 |
|---|---|
| `schema_version` | 只认 `1`；拉取与导入两处都拒绝其他值 |
| `packages` / `author.name` | key = 插件 id / 显示在「测试」结果里 |
| 顶层 `min_app_version` | **解析但不参与判定**，安装只看条目级 |
| `version` / `url` / `sha256` / `size` | 比较「可升级」/ 地址 / 强校验 / 进度条总量（写错会让百分比失真） |
| `display_name` / `desc` / `hidden` | 展示名（缺省 id）/ 未安装条目说明 / 只隐藏列表，依赖照装 |
| `requires` | 递归安装，**无环检测**，清单不要写环 |
| `platform` / `api_version` / `lasttime` / `author` / `homepage` | 解析或有元数据，但不校验、不过滤；PC 插件列表不展示 |

条目级 `min_app_version` 在 `install_from_index` 校验：低于要求直接拒绝。
应用版本取 `boot_info.app_version`（Rust 编译期包版本），**不能用前端
`__APP_VERSION__`**——那是 Web 应用自己的版本号，两套号混用会把够版本误判成不够。

### 3.4 `url` 的四种写法

| 写法 | 解析（源 = `http://h:9000/plugins/packages.json`） |
|---|---|
| 相对同级（推荐） | `x.zip` → `http://h:9000/plugins/x.zip` |
| 子目录 / 上跳 | `sub/x.zip` → `…/plugins/sub/x.zip`；`../x.zip` → `…/plugins/../x.zip` |
| 绝对 / 协议相对 | `https://cdn.example.com/x.zip` 原样使用；`//cdn.example.com/x.zip` 补源的协议 |

拼接基准是**用户填的源地址去掉文件名后的目录**，不是重定向后的地址，
所以 GitHub Release 的 302 不影响解析。

**两个坑**：协议相对必须显式识别 `//`（否则拼成 `http://source///host/path`，
宽容的测试服务器 200、nginx / GitHub 404）；缓存文件名必须带下载地址哈希——
只用 `{id}-{文件名}` 时两个源的同名 zip 会互相命中，切到一个配置错误的源会拿
上个源的包"安装成功"。现在是 `{id}-{sha256(url)前8位}-{文件名}`。
私有仓的标准链接带 token 也是 404，下载前会用 token 查一次资产列表换成
`releases/assets/<id>`（9.4）。

### 3.5 版本比较与拉取行为

只比前三段数字（`1.2.10 > 1.2.9`）；`-` 后缀忽略（`1.2.3-test` 等于 `1.2.3`）；
Rust `parse_ver` 与前端 `compareVer` 一致。远端版本高于 `installed.json` 记录 →
显示「可升级」。应用、apiserver、danmu-api、drpy、Node 是**各自独立的版本线**，
不要为了整齐强行对齐——对齐意味着每次改前端都要重发大插件包。

- **索引缓存**：成功 60 秒、失败 20 秒。失败也缓存，否则「设置页 ↔ 插件页」
  来回点会反复起 curl 子进程（黑框连闪、界面卡顿）。
- **单地址重试 3 次**，退避 1s / 2s；外层已有候选地址与缓存两层保护，再加到
  5 次会让最坏情况变成 10 个子进程。
- 没配源时给可操作提示，不抛 404；测试源只拉清单回报包数与可见数。
  清单解析容忍 BOM（PowerShell 5.1 会写），不 strip 会报
  `expected value at line 1 column 1`。

---

## 三·五、故障排查：幽灵后端

**症状**：设置页显示「已连接 · N 源」，但「本机插件」里后端是「未安装」。
数据能看，却不知道从哪来。

**根因**：不是插件在跑，而是 HTTP 请求退化成了同源相对路径。

```
页面来自 http://localhost:5173；isTauri() 为真但 desktopBase 是空串
  → fetch('' + '/api/sites') 变成相对地址，打到 5173
  → Vite 的 /api 代理转发到 127.0.0.1:9979（手工启动的独立后端）
```

应用状态是绿的、插件列表却显示未安装；生产构建是 `tauri://localhost`，
没有 Vite 代理，**只在开发模式复现**。

修复在 `client/apps/web/src/api/index.ts`：桌面端只要还没拿到确定地址，
`request()` 直接抛错，绝不发同源请求（`backendUnavailable()` = `isTauri() && !resolveBase()`）。
**通用教训**：桌面端所有请求都必须带绝对地址；`base()` 返回空串在 Web 端是同源（正确），
在桌面端是"打到错误的地方"（危险）。排查时先确认端口归属：

```bash
netstat -ano | grep LISTENING | grep ":9979"
powershell -NoProfile -Command "(Get-CimInstance Win32_Process -Filter 'ProcessId=<PID>').CommandLine"
```

命令行是 `java … ApiServer 9979` → 独立后端，与应用无关；应用的插件后端
命令行里带 `plugins\apiserver` 路径。

---

## 四、插件作者：manifest 规范

`manifest.json` 放在插件 zip 内。安装器允许 zip 多包**一层**目录
（`find_plugin_root` 只向下找一层），再深就找不到。
apiserver / danmu-api / drpy 的 manifest 在 `server/plugin-manifests/`，由各打包脚本（`server/build-plugin*.ps1`）复制成
插件目录里的 `manifest.json`；改字段改那里，不要直接改 `build/` 下的产物。
node 的 manifest 内联在 `server/build-release.ps1` 里手工拼（PS 5.1 的
`ConvertTo-Json` 会把中文写成乱码、嵌套空数组写成 `{}`）。

### 4.1 最小示例

```json
{ "id": "my-plugin", "name": "我的插件", "version": "1.0.0",
  "exec": "server.exe", "args": ["--port", "{port}"],
  "port": 9400, "health": "/health", "healthExpect": "ok" }
```

### 4.2 字段

| 字段 | 说明 |
|---|---|
| `id` / `name` / `version` | 唯一标识（也是目录名，包内必须一致）/ 展示名 / 可升级比较 |
| `exec` / `execFallback` | 可执行文件（Windows 自动补 `.exe/.bat/.cmd`）/ 备选数组，支持 `../node/node.exe` |
| `args` / `env` | 命令行参数 / 环境变量，值都支持占位符（4.3） |
| `port` / `portFallback` | 首选端口 / 占用时的候选；工具型可省略（0 = 不监听） |
| `health` / `healthExpect` / `health*Ms` | 健康路径 / 响应体需包含的串 / 超时默认 45000、间隔默认 500（下限 100）；各插件可在 manifest 覆盖 |
| `dataDir` / `cwd` | 数据目录（支持 `{appData}`，缺省 `{appData}/data`）/ 默认插件目录 |
| `capability` | `backend` / `danmaku` / `runtime`，见 4.6、4.7 |
| `required` / `fatal` | 事实标记：不禁止停止、不强制自启 / 透传前端，当前不参与判定 |
| `autostart` | 默认是否随应用启动；用户开关（`enabled.json`）优先 |
| `inject` / `tool` | 就绪后写配置（4.5）/ 工具型插件（4.6） |

Rust 只消费 `capability == "backend"`（`is_backend`）；没写时按 `id == "apiserver"`
兜底。`danmaku` / `runtime` 是描述性标记，真正接线靠 `inject` + 后端读 `policy.json`。

`required` 只表示"没有它应用就没有后端"这个事实：用户可以随时停掉 apiserver
改连远端地址，这是合法使用形态。所以它不禁止手动停止、也不强制 autostart；
前端只在 required 插件**本应运行却没起来**时提示降级。

### 4.3 占位符

`args` 与 `env` 的值里可用 `{port}`、`{dataDir}`、`{appData}`
（`%APPDATA%/com.meow.player`）。工具型插件的 `inject` 额外支持 `{pluginDir}`。

danmu-api 的端口走环境变量而不是命令行：`"env": { "DANMU_API_PORT": "{port}" }`。

`{script}` **不是**占位符，也不会被替换。它只是"这是运行时、需要别人指定脚本"
的标记，命中即按工具型处理（4.6），这类插件永远不会被真正启动。

### 4.4 可执行文件查找顺序

**先在插件目录里找，最后才退回 PATH**：

```
1. <插件目录>/<exec>（无扩展名时试 .exe/.bat/.cmd）
2. <插件目录>/<execFallback 每一项>（支持 ../ 跨插件引用）
3. PATH 里找 exec 与 fallback 的裸命令名
```

顺序反了会踩坑：danmu-api 的 `exec: "node"` 若先查 PATH 会命中系统 node，
随插件下载的 `node.exe` 永远用不上。另外 Windows 的 `PathBuf` 不会自动补 `.exe`；
直接 `is_file()` 永远 false，表现为插件永远 `installed=false`。

### 4.5 自动接线（inject）

```json
"inject": {
  "target": "{appData}/data/policy.json",
  "key": "danmakuApi",
  "value": "http://127.0.0.1:{port}"
}
```

语义是"让用户零配置"，不是"强制改成我这里"：

- 目标字段已有**非空且不等于本次值**的内容时跳过写入：用户配了 NAS 上的 danmu_api，
  插件启动不会把它改回 `127.0.0.1`。反过来说，**上次插件自己写的旧端口也算
  "已有值"**——端口从 9321 顺延到 9322 时不会自动更新，要跟随新端口得先清空该字段。
- 读-改-写保留其他字段；先写 `.tmp` 再 rename。写失败不算启动失败，
  只在状态里附 `已启动，但写配置失败: …`。

### 4.6 工具型插件

判据两个（满足其一）：`"tool": true`，或 `args` 里带 `{script}`（如 node 运行时
的 `{ "tool": true, "exec": "node.exe", "args": [], "autostart": false }`）。

- `start()` **不 spawn 进程**，直接返回 `phase = ready`、`port = 0`、`base = ""`。
- 若声明了 `inject`，此时顺手接线：`target` 展开 `{appData}`，`value` 展开
  `{pluginDir}` 与 `{port}`（后者替换成 `"0"`）。不这么做的话，没有进程的工具插件
  永远走不到"健康检查后 inject"那段，结果是"装好了但后端不知道路径"
  （FFmpeg 就是靠这个把 `{pluginDir}/ffmpeg.exe` 写进 `policy.json` 的 `ffmpegPath`）。
- 前端按 `tool` 隐藏启停按钮、禁用开关，状态标签显示「已就绪」。

### 4.7 drpy 运行时插件（capability: runtime）

把 `.js`（drpy）源接到 Node 里执行。manifest 事实来源是
`server/plugin-manifests/drpy.manifest.json`（节选，省略 `id` / `name` / `version` /
`autostart`）：

```json
"capability": "runtime", "exec": "node",
"execFallback": ["../node/node.exe", "../node/node", "node.exe"],
"args": ["server.js"], "port": 9330,
"health": "/health", "healthExpect": "\"ok\":true", "healthTimeoutMs": 20000,
"env": { "DRPY_PORT": "{port}", "DRPY_STRIP_REMOTE": "1" },
"inject": { "target": "{appData}/data/policy.json", "key": "jsRuntime",
            "value": "http://127.0.0.1:{port}", "force": true }
```

**两个运行时分两个键**（2026-11-28 中期分层）：drpy 独占 `jsRuntime`，pyrt 写
`pyRuntimeAddr`（`.py` 源直连它）。两边都带 `"force": true`，但 force 只在
"值指向另一个**本机**插件"时生效 —— 用户手配的局域网/NAS 地址永远不覆盖。
pyrt 的 inject 示例见 `server/plugin-manifests/pyrt.manifest.json`
（`"key": "pyRuntimeAddr"`）。

> ★ 键名是 `pyRuntimeAddr` 不是 `pyRuntime`：iOS 壳用 `policy.pyRuntime`
> 存 Python 启动状态对象。iOS 端内也只写状态、不写地址键。

danmu-api 用同一套机制，健康端点就是一个真实弹幕查询
（`/api/v2/fongmi/danmaku?name=test`），超时给到 90 秒（首次要拉聚合源）。

后端接线（`server/host/src/shared/ApiServer.java`）：
- 运行时地址：`.js` 取 `policy.json` 的 `jsRuntime`（容器 `MEOW_JS_RUNTIME` 兜底）；
  `.py` 优先 `pyRuntimeAddr`（容器 `MEOW_PY_RUNTIME`），未配才回落 `jsRuntime`。
- `.js` 站点的动作转发到 `POST {jsRuntime}/{site}/{action}`，请求体原样，另带
  `X-DRPY-Api`（脚本地址）与 `X-DRPY-Ext`（ext 的 URL 编码）。运行时不需要
  反查站点表。
- 健康探测 `/health`，结果缓存 15 秒；失败只影响本轮要不要提交脚本源，jar 源
  照常跑。超时按健康度给：正常 8 秒、熔断期 3 秒（试过 1 秒已回滚——drpy 源
  要多跳一层容器，压到 1 秒等于必然超时）。写死 30 秒会让 200 多个
  脚本源逐个耗尽预算，把 jar 源饿死。

**远程模块三档策略**（`server/drpy/host.js`）：`DRPY_STRIP_REMOTE=1` 当空模块跳过
（打包清单默认）；`DRPY_ALLOW_REMOTE=1` 才真正拉取执行（供应链风险）；
两者都不设则抛错拒绝执行。实测 `drpy2.min.js` 会纯副作用 import 三个第三方 CDN
上的混淆脚本，所以默认跳过。源码在 `server/drpy/`（可入库），`build/` 与
`plugins/` 都在 `.gitignore` 里。

---

## 五、安装、升级与回滚

### 5.1 安装顺序

`install_from_index` 的实际顺序：

1. 取条目并校验条目级 `min_app_version`；没有该 id 报"插件源里没有 X"。
2. 递归装 `requires`：本地没装、或本地版本与远端不同就重装。
3. 下载到 `download-cache`（命中缓存且 hash 对得上则跳过），校验 SHA256
   （不匹配删包并失败）。
4. 解压到 `plugins.d/<id>-<ver>.tmp/`。
5. `find_plugin_root` 找 manifest，`validate_manifest` 校验 id 一致且 `exec` 非空。
6. `preserve_plugin_dist` 把旧 `dist/` 里新包缺失的文件补进新包（5.4）。
7. 旧目录 rename 成 `plugins.d/<id>.bak/`（失败重试，仍失败报"文件被占用"），
   新目录 rename 到 `plugins/<id>/`（失败把 `.bak` 改回来）。
8. 写 `installed.json`（install 结束）。随后 `plugin_install` 调 `set_enabled(true)`
   → `start()` → `finalize(started)`；启动失败会把 `.bak` 改回正式目录并报"已回滚"。

**为什么用 rename 不用复制**：同盘 rename 原子，中断不会留半截目录；
复制中途断电会留下损坏插件，下次启动直接加载坏包。

**文件被占用的重试**：Windows 下 kill 进程后句柄不是瞬间释放，旧目录改名最多试
6 次（首次失败等 800ms，之后每次 300ms）。仍失败时把 `os error 32` 翻译成
可操作提示（先停止或重启应用），原始错误附在括号里。

### 5.2 回滚

`finalize(id, true)` 删 `.bak`；`finalize(id, false)` 删新目录、把 `.bak` rename
回 `plugins/<id>` 并返回"新版本启动失败，已回滚到上一版本"。安装中途失败时，
`lib.rs` 若发现旧版本来在运行，会重新 `start` 旧版，避免"升个级反而没后端"。

### 5.3 升级前必须先停进程

安装前做两件事：若插件当前 `ready`，`stop()` 并等 800ms 释放句柄；
无论是否在跑，都 `kill_orphans(id)` 按插件目录扫一遍。

只做第一步不够：`stop()` 只能停本实例托管的子进程，而升级失败最常见的原因是
**不是自己起的**旧进程（应用被安装器替换、上一轮异常退出）还占着文件。

### 5.4 纯壳插件升级保留 `dist/`

新包不再带 `spider.jar` 时，整目录替换会让用户原来能用的 jar 站全部
`ClassNotFoundException`。安装前把旧包 `dist/` 里**新包缺失**的文件复制进新包
（新包已有的优先，不覆盖）；失败静默，最坏结果是让用户重新导入源包。

### 5.5 导入本地包与卸载

**导入**：解压 → 找 manifest → 读 id/version → 旧目录进 `.bak` → 新目录落位 →
写 `installed.json`，成功返回 id 并立刻 `start`（同样保留 `dist/`）。
**卸载**：先 `stop()` 再删 `plugins/<id>/`、清 `installed.json`；`data/` 与
`enabled.json` 里的条目不动。

---

## 六、进程管理

### 6.1 状态机与扫描

`Phase`：`stopped → starting → ready | failed`。

`discover()` 扫描 `plugins/` 下所有含 `manifest.json` 的目录，跳过 `.bak` /
`.tmp` / `.old` 结尾、`.` 开头、以及 `download-cache`。判断必须在"是不是目录"
之后、"有没有 manifest"之前：早期版本把 `.bak` 放在 `plugins/` 根下，残留里
也有完整 manifest，于是列表出现两个同名插件。解析失败仍产出一行 `failed`
并带错误；排序为已安装 → `required` → id。

### 6.2 端口协商

`pick_port` 依次 `bind("127.0.0.1", port)`：首选空闲就用，否则按 `portFallback`
取第一个空闲；全占满报"端口 X 及备用端口全部被占用"。

`discover()` 对没启动的插件返回 `manifest.port` 作显示值——**那不是预留**；
真正的端口只用 `start()` 的返回值，不能用循环里的旧状态。

### 6.3 健康检查

- `health` 为空：固定睡 1500ms，视为就绪；否则轮询 `GET {health}`
  （单次超时 1200ms），间隔 `max(healthIntervalMs, 100)`，直到 `healthTimeoutMs`。
- `healthExpect` 为空 → 有响应即通过；否则响应体必须包含该子串（apiserver 用
  `"ok":true`，比裸 `ok` 更不容易误判）。超时标记 `failed`、写"启动超时：
  {port} {timeout}ms 内未通过健康检查"并 kill 子进程。

### 6.4 `enabled.json` 与启动

- 是否随应用启动：`enabled.json` 的显式值 > manifest 的 `autostart`（原子写）。
- `plugin_set_enabled` 要求目录里已有 `manifest.json`，否则报"未安装"；启动线程
  只处理 `installed && enabled`，工具型也进入 `start()` 但不 spawn。
- **设置页「启动」= 启用**：点「启动」时前端先 `plugin_set_enabled(true)` 再
  `plugin_start`，所以下次启动会自启；「停止」只停本次进程、不改 `enabled.json`
  （要它永不自启，把行首的"随应用启动"开关关掉）。
  <br>为什么这样定：Rust 的 `state_of()` 对运行中的插件恒返回 `enabled: true`，
  只反映当前进程；不落盘的话开关显示"开"、下次启动却不会起（2026-11-20 用户报）。
- 桌面端**启动后端插件时会清掉手填的后端地址**（`meow.api` / `meow.api.scope`）：
  否则 `resolveBase()` 里非回环手填地址优先于插件地址，用户以为切回了本机、
  实际仍连之前填的 NAS。
- `boot_info.fatal_reason` 只在 `required && enabled && phase == failed` 时给出。
  用户主动关掉（`enabled=false`）或手动停止（`stopped`）都不算故障。

### 6.5 Job Object 与孤儿进程

`RunEvent::ExitRequested` 只在正常退出时触发；任务管理器强杀、崩溃、
`Stop-Process -Force` 都不触发，sidecar 会残留。所以：

- **Job Object**：`JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`。主进程无论怎么死，
  句柄关闭时内核杀掉整个 job。spawn 后立刻 `AssignProcessToJobObject`。
- **按路径清孤儿**：`kill_orphans_in` 用 Toolhelp 快照取 exe 路径，在插件目录下
  就终止。判据必须带**分隔符边界**：裸 `starts_with` 会让 `plugins\api` 命中
  `plugins\apiserver\jre\bin\java.exe`，升级 apiserver 时误杀 api 插件。
- **启动与停止都扫**：`PluginManager::new` 清所有插件目录；`start()` 在没有本实例
  托管子进程时扫一次（不清的话孤儿占着 9979，`pick_port` 顺延到 9980，新旧两个
  后端同时跑、端口一轮轮往上漂）；`stop()` 在 kill 直接子进程后再扫一次
  （manifest 若改成包装器启动，服务进程会留下）。
- **退出与重启**：`ExitRequested` 调 `shutdown_all()`，所以"退出应用"必须走
  `app.exit(0)`。`restart_backend` 顺序固定 discover → stop → 等 500ms → start，
  按 `capability=backend` 找插件；start 的健康检查可长达数十秒，必须放独立线程。

---

## 七、常见问题

**插件源连不上？** 先点「测试」看具体错误：地址写错、服务器不通、
JSON 格式错、`schema_version` 不是 1。

**弹幕装了还是没多源？** 看 `policy.json` 的 `danmakuApi` 是否被手填地址占着——
非空值优先，插件不会覆盖（4.5）。清空后重启插件即可。

**能手工导入吗？** 能。「粘贴 JSON」建本地清单，「导入本地包」直接装 zip。
前者生成的清单没有服务器，zip 要和清单同目录，否则 `url` 写绝对地址。

**显示「已连接」但插件未安装？** 见「三·五、幽灵后端」，只在开发模式出现。

---

## 八、已知限制

1. **仅 Windows 10 1803+**：下载依赖系统 `curl.exe`、解压依赖 `tar.exe`，
   两者缺任一则在线安装和「导入本地包」都不可用。
2. **SHA256 是唯一内容校验**：清单没有签名，只校验 schema（且允许 http 源）。
   源站被入侵时可以同时改清单和 zip，SHA256 拦不住。
3. **平台与更新粒度**：`platform` 不参与过滤、不拒绝安装；每次升级下载完整
   zip，没有增量更新。
4. **有些字段只解析不生效**：顶层 `min_app_version`、`api_version`、
   包级 `author` / `homepage`。
5. **插件日志被丢弃**（`Stdio::null`）；**`requires` 没有环检测**。

---

## 九、清单：插件与应用条目共用

### 9.1 为什么清单挂 Release 资产

一份 `packages.json` 同时管插件条目和应用自身（PC / TV）的条目，作为 Release
资产发布、**不进 git**：否则每次发版都要往 `main` 提交并处理 pull/push 冲突
（发版侧由 `sync-packages` 上传资产，见 [构建与发版](./build-release.md)）。

release 端点返回的是描述 release 的 JSON，清单是其中名为 `packages.json` 的
资产，要再取一层。**解析必须两边一致**，否则症状是"插件源填对了却拉不到清单"
（把 `.../releases/latest` 当目录拼 `.../latest/packages.json`，必然 404）：

- **插件安装器**（`installer.rs`）：`is_github_release_endpoint` + `manifest_asset_from`，
  拉该端点的 release JSON 直接取资产，有单元测试钉住判定规则。
- **应用更新**（前端 `appUpdate.ts`）：`isReleaseEndpoint` + `pickManifestRelease`，
  解析统一收口在前端——原生两层解析会把已取回的清单再当 release JSON 解析一遍
  而报错；`appupdate.rs` 留有同规则的 Rust 参考实现（`pick_release_with_manifest`），
  当前 `#[allow(dead_code)]` 故意保留。

### 9.2 清单结构（含应用条目）

与 3.2 同构，只是多出应用条目：

```json
"packages": {
  "meow-pc": {
    "display_name": "PC 安装包", "version": "1.2.3", "platform": ["windows-x64"],
    "size": 1234567, "sha256": "4e834009…",
    "url": "https://github.com/upchr/meow/releases/download/release-v1.2.3/meow-setup-1.2.3.exe",
    "hidden": true
  },
  "apiserver": { "version": "1.1.6", "url": "apiserver-1.1.6.zip", "hidden": false }
}
```

（数值仅示意。）`desc` 是给用户看的更新说明，客户端弹框按行渲染。

### 9.3 两条硬规则

1. **应用条目必须 `hidden: true`**。否则会出现在「本机插件」的可安装列表
   （那边只过滤 `hidden`），用户点了会走插件安装器、解压找不到 `manifest.json`。
   前端 `availablePlugins` 另外再挡一层旧 id（`app` / `app-pc` / `app-tv`）。
2. **id 用品牌名**：`meow-pc` / `meow-tv`，与 `apiserver` / `danmu-api` / `drpy`
   一致。旧 id 仍能读，是新清单的升级路径，不要再写。

### 9.4 版本判定与私有仓

- **不用 GitHub 的 `/releases/latest` 语义**：`latest` 按发布时间挑，给旧版补
  hotfix 会让 latest 回退，draft 也不参与计算。实际是拉 `/releases?per_page=30`
  列表，过滤非 draft / 非 prerelease / 带 `packages.json` 的条目，按 tag 的数字段
  取最大版本（前端 `pickManifestRelease`；Rust 参考实现 `pick_release_with_manifest`）。
  写成 `releases/latest` 只是入口，补发旧版 hotfix 也不会让客户端降级。
- **私有仓的标准下载链接带 token 也是 404**（实测），唯一可用路径是
  `api.github.com/repos/.../releases/assets/<id>`；清单里**始终写标准链接**，
  客户端有 token 时自己换；token 为空时优先 `browser_download_url`，代理才生效。
- **API 资产端点必须带 `Accept: application/octet-stream`**：不加的话拿到的是
  描述资产的 JSON，下载"成功"但 sha256 对不上，缓存里还留个假 zip。

---

## 十、源与 GitHub 加速

### 10.1 一条原则：加速统一，业务源分置

| 对象 | 处理 | 位置 |
|---|---|---|
| GitHub 加速列表 / 访问令牌 | 全局唯一；加速可增删改、排序、启停、测试、恢复默认 | 网络与加速 → GitHub 加速 |
| PC 插件源 | 多源列表，可切换；默认跟随应用更新源（可关） | 本机插件 → 插件源 |
| PC 应用更新源 | 单字段（留空 = 内置 GitHub Release） | 维护 → 应用更新 |

不合并成一个输入框：两条清单 schema 不同，且填错一个会同时打断多条链路。

### 10.2 候选生成（以 PC 插件安装器为准）

`installer.rs` 的 `expand_public_candidates`：**有 token，或地址不是公开 GitHub
地址 → 只直连**；公开地址且无 token → `直连 + git-proxies.json` 里的前缀（按文件
顺序，只收 `https://` 前缀或 `{url}` 模板）。原生侧**没有冷却**、不区分 `useFor`、
也不读 `directFirst`；`useFor` 分链路开关、冷却与 jsDelivr 都只在前端
（`gitAccel.ts` 的 `gitAccelCandidates`，索引或 `size < 20MB` 才启用 jsDelivr）。
内置预设当前 `gh-proxy.com`、`ghfast.top`、`ghproxy.net` 默认启用；
`gh.llkk.cc` 实测 12 秒超时，默认停用；jsDelivr 只对 raw 地址生效，单文件上限
20MB。两条硬规则：**只对公开 GitHub 地址加代理**（私有仓不拼代理，代理不转发
`Authorization`）；**SHA256 不匹配不换线路重试**——内容错了换地址还是错，
而且可能是投毒。

### 10.3 安全边界

- 代理只拼公开 GitHub 主机：`raw.githubusercontent.com`、
  `gist.githubusercontent.com`、`github.com/releases/download|latest/download`、
  `api.github.com/.../releases/`；不把任意 URL 塞进代理（开放代理 / SSRF）。
- **token 只发给 GitHub 自己的域名**：`api.github.com`、
  `raw.githubusercontent.com`、`codeload.github.com`、
  `objects.githubusercontent.com`、`github.com`。源换成自建服务器后，
  无条件的 `Authorization` 会把 PAT 明文送过去，不少静态服务器还会直接 401。
- token 只进请求头，**不进 URL、日志、`plugin-sources.json`**（后者会整体回传
  前端），所以单独存 `plugin-token.txt`。清单只校验 schema（`schema_version == 1`）、
  没有签名；包用 SHA256 强校验。源地址允许 `http`，是否走 HTTPS 由源决定。

---

## 相关

- 清单与发版流程：[构建与发版](./build-release.md)
- `policy.json` 与 `jsRuntime` / `danmakuApi` 的消费方：[配置系统](./config-system.md)
- 弹幕降级与外部 API：[弹幕](./danmaku.md)
- TV 运行时模块（另一套实现）：[TV 接入](./tv-integration.md)
