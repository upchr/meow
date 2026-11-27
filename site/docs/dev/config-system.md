# 配置系统

一份 `config.json` 从导入到「某个站点能被搜索」，中间经过四步：**解析 → 落盘 → 加载 jar → 分派站点**。
这一页把这条链路的每一段说清楚，改源类型或加载逻辑时按图索骥。

## 一、链路总览

```
用户导入（URL / 文件 / 粘贴）
      ↓  POST /api/config/import
解析 + 探测 + 预览
      ↓  确认后落盘
data/config.sites.json          ← 站点表、parses、lives、rules…
      ↓  启动 / 导入后触发
Jars.reload()（启动，JVM 专用）/ Jars.prepareForImport()（导入期，配合 Dex2Jvm）
                                ← 读 spider / spiderJvm，下载 + md5 校验 + 缓存
                                ← 挂载前 Dex2Jvm.convertLocal() 把站点级 dex 转 class
      ↓
Bridge.installClassLoaderIfAbsent()   ← 装 URLClassLoader（JVM）或 DexClassLoader（TV）
      ↓
站点分派（csp_ → jar / .js → drpy / http → CMS 直连）
```

## 二、导入：三种模式与三个开关

`POST /api/config/import` 的请求体：

| 字段 | 取值 | 默认 | 说明 |
|---|---|---|---|
| `mode` | `replace` / `merge` | **`replace`** | 整表替换，或追加 |
| `onConflict` | `keep` / `replace` / `both` | `keep` | merge 时同名 key 怎么处理 |
| `replaceSpider` | 布尔 | `false` | 用导入配置的顶层 `spider` 覆盖本地 |

::: warning 接口默认是 replace
前端下拉默认选的是「追加」，但**接口不传 `mode` 时按 `replace` 处理**。
脚本调用必须显式写 `mode`，否则会清空现有站点。
:::

`replaceSpider` 是迁移专用：旧安装的 `spider` 指向已失效的 jar，不勾选就继续用旧地址，
表现为「站点全红 + ClassNotFound」。

导入流程本身分两段：**探测**（拿配置、解析站点数、检测可用性）和**确认**（写入）。
预览阶段不落盘。

## 三、落盘：数据目录

```
data/
  config.sites.json     站点表（核心）
  policy.json           用户策略（弹幕 API、DNS、代理…）
  state-*.json          收藏 / 进度
  cache/                转码与 TTL 缓存（可丢）
  jars/                 spider jar 缓存
  posters/              刮削封面
```

数据根目录的解析优先级（`server/host/src/shared/core/DataPaths.java`）：

```
-Dmeow.data  >  MEOW_DATA  >  --data-dir=...  >  ./data
```

四端实际位置见 [数据与状态](./data-state.md)。

## 四、jar 加载：`spider` 与 `spiderJvm`

配置里的两个字段：

```json
{
  "spider": "https://example.com/spider-dex.jar;md5;<hex>",
  "spiderJvm": "https://example.com/spider-class.jar;md5;<hex>"
}
```

读取顺序（`core/Jars.java`）：

1. `spiderJvm` 非空 → 用它（JVM 端）
2. 否则回退 `spider`

TV 端只读 `spider`，Fongmi 也一样——它们不认 `spiderJvm`。

### 加载时做了什么

| 步骤 | 行为 |
|---|---|
| 下载 | 按 URL 拉取；已有同 md5 缓存则跳过 |
| 校验 | md5 不匹配 → 默认**仍挂载 + WARN**（`policy.jarStrictMd5=true` 才拒绝加载）；下载失败但本地已有缓存时沿用缓存；同 URL 失败后 10 分钟冷却 |
| 缓存 | 落在 `data/jars/` |
| 转换 | JVM 读 dex 时先 `Dex2Jvm` 转 class（导入期 + 挂载前各一次），缓存键带 `-fix1` |
| 装载 | JVM：`URLClassLoader`；TV：`DexClassLoader` |

### 转换后的字节码修补

`Dex2Jvm` 转完会跑一遍 `core.DexJvmFix`。原因是 `d2j-dex2jar 2.4` 的一个翻译缺陷：
dex 里合法的 `new-instance C` + `invoke-direct {v}, Object.<init>()V` 会被翻成
`new java/lang/Object`，实例运行时类型错成 `Object`，之后调用 `C.f()` 或塞进 `C[]`
就抛 `ArrayStoreException: java.lang.Object`。

症状是**搜索、首页都正常，只有详情/播放报错，且只在 JVM 端**（TV 吃 dex 不受影响）。
实测肥猫那版 jar 的 `AppDrama`（干饭/一碗/再来/行动）就是这样：能搜不能播。

修补用 ASM 做数据流：给每个 `new Object` 打标记，顺着栈/局部变量追到第一次把它当
接收者的 `invokevirtual`，owner 就是真实类；只改 `NEW` 的类型，保留原
`Object.<init>` super 调用。**尽力而为** —— 任何异常都保留原产物，不让补丁把本来
能用的 dex 源弄挂。换 jar 后若怀疑还有残留，按 `deploy/source-pack/README.md`
§10.1 的 `javap` 命令核。

### ClassLoader 只装一次

`Bridge.installClassLoaderIfAbsent()` 名字里的 `IfAbsent` 是关键：**已经装过就不再覆盖**。
TV 端有 DexClassLoader 钩子，重复安装会把钩子顶掉，导致 jar 站全部失效。

## 五、站点分派：四条路，顺序即正确性

配置里每个站点的 `api` 决定走哪条路：

| `api` 形态 | 走哪 | 实现位置 |
|---|---|---|
| 站点名在 `NativeSources` | 内置实现（如豆瓣） | `NativeSources` |
| `http(s)://...` 开头 | 苹果 CMS 直连（**不看 `type`**） | `CmsSource` |
| 以 `.js` 结尾、`js:`/`node:` 开头 | 脚本运行时（JS） | 转发到 `policy.json` 的 `jsRuntime` |
| 以 `.py` 结尾、`py:` 开头 | 脚本运行时（PY） | 优先 `policy.json` 的 `pyRuntimeAddr`，未配才回落 `jsRuntime` |
| 其余（`csp_XXX`） | spider jar（反射 `crawler.Spider`） | `call()` 的 jar 分支 |

分派顺序是**原生源 → CMS → drpy → jar**，前一个能处理就不看后面的（`call()`，
`ApiServer.java`，grep `private static String call(`；三个分支分别搜
`NativeSources.get(site)` / `CmsSource.invoke` / `isScriptApi(jsApi)`）。

::: tip 为什么原生源排最前
公开壳没有内置 jar，如果原生源排在 jar 后面，没有 jar 时整个后端会被判成「不可用」。
原生源存在的意义就是「没 jar 也能出内容」。
:::

## 六、站点级 `jar`（TV 专用）

TV 端逐个站点构造 `DexClassLoader`（`LocalSpiderHost`），所以**每个 `csp_` 站点要有
`jar` 字段**（或顶层 `spider` 可用）。
JVM 端 `Jars.collect()` 同时收顶层字段（`spiderJvm` 优先、回退 `spider`）与**每个站点的
`jar` 字段**，各自挂独立 `URLClassLoader`——有就挂，没有就跳过（`Jars.java`，
grep `private static LinkedHashSet<String> collect()`）。

这也是一份配置能同时给两端用的前提：顶层 `spider`（dex）给 TV，`spiderJvm`（class）给
JVM，站点级 `jar` 在多 jar 场景按站点写。

## 七、多仓与探测

多仓索引（`{"urls": [...]}`）与单仓地址走同一个入口，两段行为不同：

- **探测**（`POST /api/config/depot`）：**并发**探测每个子仓，逐仓回
  `{name,url,ok,sites,csp,inJar,direct,hint}`；单个仓失败不阻断其他仓。
- **导入**（`POST /api/config/import`）：多仓索引**只取第一个子仓**，其余子仓作为
  可选列表回给前端（`depot` 字段），用户挑中哪个再以该地址重新导入。
  实际导入的那个仓拉取失败 → `502`，原配置不动。
- **仓库列表落盘**（`config.depot.json`）：探测或导入索引时写
  `{index, probedAt, active, entries}`；`GET /api/config/depot` 不传 url 直接回缓存
  （超 24h 标 `stale`，不自动删）。导入成功时把导入的仓记成 `active`。
  设置页启动即读这份缓存，所以重启后仍能一键切仓（对齐 Fongmi 把每个仓存成
  Config 行的做法；我们不合并多仓，同一时刻只有一个仓生效）。

探测**不写** `site-health.json`：健康分来自真实调用（`HealthRoutes.record`），
探测只判断"结构上能不能加载"，不代表今天搜得到。

## 八、改这块之前

| 要改什么 | 先看 |
|---|---|
| 加一种源类型 | `call()` 的分派顺序 + `CmsSource` / drpy 转发 + 前端站点形态判断 |
| 改导入语义 | `ApiServer` 的 import 处理 + 前端设置页三处文案（模式/冲突/jar 覆盖） |
| 改 jar 加载 | `Jars.java` + `Bridge.installClassLoaderIfAbsent`（别改成无条件覆盖） |
| 改数据目录 | `DataPaths` 的优先级链，四端启动参数都要跟着改 |

## 相关

- 字段逐个说明：[源包与配置格式](/ecosystem/source-format)
- 导入操作：[导入源包](/guide/source-pack)
- 插件与 jar 的关系：[插件系统](./plugin-system.md)
- TV 端消费路径：[TV 接入](./tv-integration.md)
