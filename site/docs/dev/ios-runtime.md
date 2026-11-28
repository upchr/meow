# iOS 运行时（js / py 源内置）

> 目标：iOS（IPA）上能直接跑 `.js` 与 `.py` 源，不依赖外部机器。
> 形态与 `feat/loader-probe` 的 pyrt 契约**完全一致**：端内一个 loopback 地址同时服务
> js + py，Java 侧零改动。**已实现**：Node 跑 drpy 载荷 + CPython 跑 pyrt（均随包），
> 端内端口 danmu 19321 / drpy 19330 / pyrt 19331。

> ★ 2026-11-28 运行时分层后，宿主多认一个 `policy.pyRuntimeAddr`（`.py` 优先、未配回落
> `jsRuntime`）；iOS 继续只写 `jsRuntime` 单地址，零改动兼容。本文其余内容按设计稿保留作背景。

---

## 0. 结论

| 项 | 结论 |
|---|---|
| 架构 | App 进程内 **Node（已有）跑 drpy** + **CPython（新增）跑 py**，一个 loopback 地址同时服务 js+py |
| 分期 | **A 远端 pyrt（+0B，今天可上）→ B 内嵌 CPython（+15~25MB，主线）→ C Pyodide（不做）** |
| 与 loader-probe 的关系 | iOS 侧只是"再实现一份 pyrt 的运行时端"，协议/健康检查/分流规则全部复用，不新增 policy 键 |
| 端内约束 | nodejs-mobile 一进程只能 `node_start` 一次 → drpy 必须并进现有唯一入口脚本 |

---

## 1. 现状（实测）

### 1.1 各端的 js / py 现状

| 端 | js 源 | py 源 | 机制 |
|---|---|---|---|
| Web / PC / Docker / fpk | ✅ | ✅ | 分层路由：`.js` → drpy 运行时（插件 / 容器），`.py` → pyrt（`pyRuntimeAddr`） |
| TV(Android) | ✅ | ✅ | 端内 QuickJS（`QuickJsRuntimeServer`）+ Chaquopy 独立模块进程（`PythonService`） |
| **iOS** | ✅ | ✅ | 端内 Node 跑 drpy 载荷 + 端内 CPython 跑 pyrt（均随包），同一路由地址 |

### 1.2 后端契约（源码位置）

| 事实 | 位置 |
|---|---|
| 运行时健康探测：`GET {base}/health` 返回 200 才算可用，结果缓存 15s | `ApiServer.java` 的 `scriptRuntimeUsable()` |
| 脚本调用：`POST {base}/{site}/{action}`，头 `X-DRPY-Api`（URL 编码的脚本地址）、可选 `X-DRPY-Ext`，body 为 JSON | `ApiServer.java` 的 `callScriptRuntime()` |
| 超时：正常 8s，熔断期 1s | 同上（`SCRIPT_RUNTIME_TIMEOUT_MS` / `SCRIPT_RUNTIME_FAST_TIMEOUT_MS`） |
| 地址来源：`.js` 用 `policy.json` 的 `jsRuntime`（空则回落 `MEOW_JS_RUNTIME`）；`.py` 优先 `pyRuntimeAddr`（空则回落 `MEOW_PY_RUNTIME`，再回落 `jsRuntime`） | `ApiServer.java` 的 `loadPolicy()` / `pyRuntimeAddr()` / `callScriptRuntime()` |
| 策略文件位置：`DataPaths.file("policy.json")` → iOS 上是 `Documents/gateway/policy.json` | `ApiServer.java` 的 `policyFile()` |
| iOS 壳按真实运行时活体合并写 `Documents/gateway/policy.json`（只动 `jsRuntime` 一个键） | `Bootstrap.swift` |

**iOS 壳会自己写 policy.json**：启动与回前台按真实运行时活体对齐 `jsRuntime`（指向端内 drpy）；
设置页的手填地址仍然生效（保存后壳重建注入脚本再 reload，见 `Bootstrap.swift`）。

### 1.3 端内 Node 的硬约束

- nodejs-mobile 以**库**形式跑在 App 进程里，**一个进程只能 `node_start` 一次**（`NodeBridge.swift` 的 `node_start` 声明处注释："在后台起 node；只生效一次"）。
  → 想再跑 drpy，**不能起第二个 node 进程**，必须并进现有入口脚本 `meow-entry.cjs`。
- nodejs-mobile 的 V8 **启动即关 JIT** → `danmu_api` 依赖的 undici 在 JIT-less V8 上会抛 `ReferenceError`
  → **健康探测只能用 `/health`，不要用真实搜索**（这条已写进 `NodeBridge.swift` 注释）。

---

## 2. 目标架构

```
┌──────────────┐   HTTP    ┌─────────────────────────────────────────┐
│  WKWebView   │◄─────────►│  Java 宿主（OpenJDK 9 Zero，端口 9978）  │
└──────────────┘            │  policy.json: jsRuntime =               │
                            │    http://127.0.0.1:<pyrt 端口>          │
                            └───────────────┬─────────────────────────┘
                                            │ loopback HTTP/1.1
                            ┌───────────────▼─────────────────────────┐
                            │  iOS App 进程                            │
                            │  ├ Node（nodejs-mobile，唯一实例）        │
                            │  │   ├ danmu_api  :19xxx  （现有）        │
                            │  │   └ drpy       :19xxx  （新增）        │
                            │  └ CPython 线程 → pyrt :19xxx（新增）     │
                            └─────────────────────────────────────────┘
```

| 源类型 | 由谁执行 | 依据 |
|---|---|---|
| `.js` / `.mjs` | 端内 Node 里的 drpy | `server/drpy/`（`server.js`/`host.js`/`worker.js`/`lib`）源码已在仓库 |
| `.py` | 端内 CPython 里的 pyrt | 复用 `server/pyrt/pyrt.py` 的路由/分流/执行逻辑 |

**为什么 iOS 用真 Node 而不是 TV 那套 QuickJS 重写层**：TV 的 `QuickJsSpider.java`（370 行）是兼容层，drpy 的 Node 语义（`require`、npm 依赖、Buffer、定时器）会有偏差；iOS 本来就有真 Node，直接跑仓库里的 drpy 更接近源站作者预期。

**已知待解决**：drpy 的 npm 依赖（`server/drpy/package.json`）**未入库**（仓库只有 `package-lock.json` 与 `lib/`），
需要先量出依赖体积再决定是否随包。

---

## 3. 分期

### A. 远端 pyrt（+0B）—— 今天就能上

iOS 不改代码，只用现成能力：

1. 在 PC / NAS / Docker 上跑 `server/pyrt`（端口 19331，`PYRT_DRPY` 指向同机 drpy）
2. iOS 设置页 → 运行时 → `jsRuntime` 填 `http://<局域网 IP>:19331`
3. iOS 侧 Java 宿主立刻能用 js + py 源（协议与 PC/Docker 完全相同）

| 优点 | 缺点 |
|---|---|
| 零包体、零 iOS 代码、兼容度与内嵌 CPython **等价**（同一个 CPython + 真实 wheels） | 要一台常开机器；iOS 默认回环（需在维护 → 调试里开远程调试/局域网）；手机换网络要改地址 |

**定位**：既是"立刻可用"的落地，也是 B 阶段的对照组（B 上线后 A 退成可选远端）。

### B. 内嵌 CPython（+15~25MB）—— 主线

#### B1. 依赖与来源

| 组件 | 来源 | 备注 |
|---|---|---|
| CPython 解释器 | **Python-Apple-support**（BeeWare）预编译产物 | arm64 版 `libpython3.x.dylib` + 头文件；**只有 .so，不含纯 Python 标准库** |
| 纯 Python 标准库 | python.org macOS framework（版本与解释器对齐） | **必须随包**（App Store 2.5.2 不许运行时下载可执行代码） |
| TLS | **OpenSSL** 静态编译，目标 `ios64-arm` | CPython 的 `_ssl` 需要它，iOS 系统没有；OpenSSL 官方支持该 target |
| lxml / pycryptodome | 源码交叉编译 arm64 | lxml 还需 libxml2/libxslt 静态库 |

#### B2. CI 工具链（新增脚本）

| 步骤 | 产物 | 门禁 |
|---|---|---|
| `client/apps/ios/scripts/prepare-python-runtime.sh` | `client/apps/ios/build/python/`（解释器 + stdlib + site-packages + certifi） | 缺 `libpython3.x.dylib` → exit 1 |
| 静态 OpenSSL | `build/python/lib/libssl.a` / `libcrypto.a` | 编失败 → exit 1 |
| lxml / pycryptodome | `build/python/site-packages/` | import 冒烟失败 → exit 1 |
| `client/apps/ios/project.yml` | resources 增 `- path: build/python` | —— |
| `.github/workflows/_ios.yml` | 新增缓存键 `ios-python-<hashFiles>`、体积门禁 | 超过 §B4 预算 → exit 1 |

> 与现有 `prepare-node-runtime.sh` / `prepare-danmu-payload.sh` 同构：都是"CI 备料 → 落 `build/` → 进 app bundle"。

#### B3. 运行期（iOS 原生 + 脚本）

| 文件 | 职责 |
|---|---|
| `client/apps/ios/Meow/PythonRuntime.swift`（新增） | `dlopen` libpython → `Py_Initialize` → 在后台线程跑 pyrt 脚本；健康后把地址写进 `policy.json` |
| `client/apps/ios/Meow/NodeBridge.swift`（改） | 现有唯一 node 入口 `meow-entry.cjs` 里**并入 drpy**（同进程第二个 HTTP 监听） |
| `client/apps/ios/Meow/PolicyBridge.swift`（新增，或并入 Bootstrap） | 合并写 `Documents/gateway/policy.json` 的 `jsRuntime`（**只改这一个键，不覆盖其它**） |
| pyrt 脚本 | 复用 `server/pyrt/pyrt.py` 的路由与分流；删掉多进程 / venv / `PYRT_DRPY` 转发分支（iOS 上 .js 由 Node 服务提供） |

**端口**：与 danmu 现状同策略（NodeBridge 的端口段 + 回环），启动失败不阻断 App。

#### B4. 体积预算

| 件 | 估 | 说明 |
|---|---|---|
| libpython3.x（arm64） | 3–5 MB | 只 arm64，不做 universal |
| 裁剪 stdlib | 8–15 MB | 未压缩口径；可 zip 化后更小 |
| 静态 OpenSSL | 4–6 MB | |
| lxml / pycryptodome / certifi | 3–8 MB | lxml 带 libxml2/libxslt |
| pyrt 脚本 | 0.05 MB | |
| **合计** | **+15~25 MB** | IPA 基线 84.7 MB → 约 **100–110 MB** |

> 数字为估算（未做 iOS 构建实测）；早前仓库实测过 GraalPy 253 MB / GraalJS 30 MB / libnode 46 MB / QuickJS 2.3 MB per abi，可作对照。

### C. Pyodide —— 不做（记理由）

CPython→WASM（[pyodide/pyodide](https://github.com/pyodide/pyodide)）理论上零原生成本、还能 Web/TV/iOS 共用，
但**对我们这批源最不友好**：无 lxml / pycryptodome 的原生 wheel、无原生 `ssl`（urllib 走 XHR shim）、
冷启动 1~2s、内存 60MB+。只适合当纯 python 简单源的兜底通道，**本轮不投入**。

---

## 4. 坑清单

| # | 坑 | 处置 |
|---|---|---|
| 1 | nodejs-mobile 一进程一次 `node_start` | drpy 并进现有入口脚本，不起第二个 node |
| 2 | JIT-less V8 下 undici 抛 `ReferenceError` | 健康探测只打 `/health`，不用真实搜索 |
| 3 | iOS 不能 fork/exec | pyrt 不起子进程；用户源里的 `execjs` 靠端内 Node 满足 |
| 4 | iOS 没有 OpenSSL，`_ssl` 不工作 | 静态编 OpenSSL；`SSL_CERT_FILE` 指向包内 certifi |
| 5 | stdlib 不能运行时下载 | 随包裁剪后打进 IPA |
| 6 | dylib 要随 App 一起签名 | 我们本来就是 ad-hoc 未签名侧载包，重签成本可忽略 |
| 7 | 写 policy.json 会覆盖其它键 | **合并写**单个键，不整文件覆盖 |
| 8 | drpy 的 node_modules 未入库 | 先量体积再决定随包 / 精简 / 走远端 |
| 9 | 端内端口冲突（danmu / drpy / pyrt 三个服务） | 端口段规划 + 冲突回退（同 danmu 的做法） |
| 10 | 健康探测频繁打爆端内服务 | 后端已有 15s 缓存，端内不要额外主动探测 |

---

## 5. stdlib 裁剪清单（建议）

**保留**：`os` `sys` `json` `re` `time` `datetime` `hashlib` `base64` `urllib` `http` `ssl` `socket` `email` `xml` `encodings` `codecs` `string` `random` `collections` `itertools` `functools` `subprocess`(禁 exec 时留但不用) `logging` `warnings` `sqlite3` `zlib` `gzip` `bz2` `lzma` `ctypes`(部分) `importlib` `zipimport` `pkgutil` `platform` `uuid` `copy` `pickle` `struct` `math` `decimal` `statistics` `textwrap` `unicodedata`

**删除**：`tkinter` `test` `unittest`(可选) `idlelib` `distutils` `ensurepip` `lib2to3` `pydoc_data` `config` `site`(精简) `venv` `multiprocessing`(部分) `asyncio`(可选，requests 不用) `xmlrpc` `pydoc`

**site-packages**：`requests` `urllib3` `certifi` `charset_normalizer` `idna` `beautifulsoup4` `soupsieve` `pyquery` `lxml` `pycryptodome`

---

## 6. 验收

1. **功能**：装 IPA → 设置页「运行时」看到内嵌运行时就绪 → 选一个 `.js` 源和一个 `.py` 源搜索 → 各播一集
2. **回退**：把 `jsRuntime` 指向远端 pyrt → 应同样可用（验证两条路互不干扰）
3. **日志**：`LaunchLog` 的 `[python]` / `[node]` 段；`/api/logs` 的 `scriptRuntime` 探测与熔断行
4. **体积**：IPA 资产大小对比基线 84.7 MB，超预算即视为回归
5. **离线**：飞行模式下 py 源仍可用（内嵌 CPython 的核心价值）

---

## 7. 执行清单（合并后按序做）

| # | 步骤 | 落点 |
|---|---|---|
| 0 | 前置：合并 `feat/loader-probe`（pyrt 契约来源）到 main | —— |
| 1 | ✅ 量 drpy 依赖并裁定随包（实测见 §7.1） | `server/drpy/package.json` |
| 2 | CI 备料脚本：Python-Apple-support + stdlib + 静态 OpenSSL + lxml/pycryptodome | `client/apps/ios/scripts/prepare-python-runtime.sh`（新增） |
| 3 | XcodeGen 资源 + CI 步骤 + 体积门禁 | `client/apps/ios/project.yml`、`.github/workflows/_ios.yml` |
| 4 | 原生：CPython 线程 + 端口 + 健康检查 | `client/apps/ios/Meow/PythonRuntime.swift`（新增） |
| 5 | 原生：drpy 并进现有 node 入口 | `client/apps/ios/Meow/NodeBridge.swift`、`meow-entry.cjs` |
| 6 | 原生：合并写 policy.json 的 `jsRuntime` | `client/apps/ios/Meow/`（新文件或并入 Bootstrap） |
| 7 | pyrt 脚本裁剪（去多进程/venv/转发分支） | `server/pyrt/pyrt.py`（iOS 专用薄壳） |
| 8 | 真机验收（§6 五项） | —— |
| 9 | 出包 + 体积对比 + 冒烟 | `_ios.yml` |

### 7.1 实测（2026-11-25，第一刀已落地）

| 项 | 实测 |
|---|---|
| `npm ci --omit=dev` 后 node_modules | **19MB**（32 包；大头是 web-streams-polyfill 8.8MB + undici 2.1MB） |
| undici 来源 | **cheerio@1.2** 的依赖（不是 drpy 直接用）→ iOS 上必须桩掉：JIT-less V8 加载真实 undici 抛 `WebAssembly is not defined` 会带走 App |
| 裁剪后载荷 | **7.4MB**（undici 桩 + 删 `*.map` + 删 web-streams-polyfill 的 es6/es2018/ponyfill 分发 + 删 tests/docs） |
| 本地冒烟 | 载荷 `node server.js` → `/health` `{"ok":true,...,"workers":12}`；`npm test` 8/8（脚本加载/home/category/search/req/cheerio.jinja2/vod_id） |
| 合并入口冒烟 | 包装层 `require(MEOW_DRPY_DIR + '/server.js')` → drpy 起在 19330，`/health` ok（与弹幕同进程可行） |
| 随包裁定 | **随包**（7.4MB）；`jsRuntime` 只在 key 为空时写 `http://127.0.0.1:19330`，不覆盖用户手配的远端 |

已落地文件：`client/apps/ios/scripts/prepare-drpy-payload.sh`、`project.yml` 的 `build/drpy` 资源、
`NodeBridge.swift` 的 `installDrpyIfNeeded()` / `writeJsRuntimeIfAbsent()` / 入口包装拉起 drpy、
`_ios.yml` 的缓存键与体积门禁（26MB 上限）。

仍未验证（要真机）：nodejs-mobile 对 `worker_threads` + `SharedArrayBuffer/Atomics` 的支持
（drpy 的 sync-fetch 与 worker 池依赖它）——不通过时降级方案是把 POOL_SIZE 设为进程内串行。

---

## 8. 前置条件与分支关系

- **必须先合 `feat/loader-probe`**：pyrt 契约（协议、`/health`、分流规则）只在那里，main 上没有 `server/pyrt/`。
- **冲突面**：loader-probe 与 iOS 分支交叠 `client/apps/web/src/api/nativeBridge.ts`、`client/apps/web/src/views/SettingsView.vue`；
  iOS 侧新增文件（`PythonRuntime.swift`、`prepare-python-runtime.sh`、pyrt 薄壳）不与任何分支交叠。
- **不碰**：`origin/updates`、`origin/fpk-updates`（CI 写的数据分支）、`private-main`、`feat/public-auth`（另开窗口）。
