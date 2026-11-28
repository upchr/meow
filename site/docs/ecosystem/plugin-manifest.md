# 插件 manifest 规范

一个插件就是一个 zip，根目录放 `manifest.json` 和可执行文件。
宿主读 manifest 决定怎么启动、怎么判活、怎么接线。

## 最小示例

```json
{
  "id": "my-plugin",
  "name": "我的插件",
  "version": "1.0.0",
  "exec": "server.exe",
  "args": ["--port", "{port}"],
  "port": 9400,
  "health": "/health",
  "healthExpect": "ok"
}
```

目录结构：

```
my-plugin.zip
├── manifest.json
└── server.exe
```

`manifest.json` 必须在 zip 根层级，不能包一层目录。

## 完整字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | ✅ | 唯一标识，也是安装目录名 |
| `name` | ✅ | 展示名 |
| `version` | ✅ | 版本（语义化） |
| `exec` | ✅ | 可执行文件，相对插件目录；Windows 自动补 `.exe` |
| `execFallback` | | 备选可执行文件数组，如 `["../node/node.exe", "node"]` |
| `args` | | 命令行参数，支持占位符 |
| `port` | | 首选端口；工具型插件可不填 |
| `portFallback` | | 端口被占用时的备选端口数组 |
| `health` | | 健康检查路径，如 `/api/health` |
| `healthExpect` | | 期望响应里包含的字符串，如 `"ok":true` |
| `healthTimeoutMs` | | 启动超时，默认 45000 |
| `healthIntervalMs` | | 轮询间隔，默认 500 |
| `dataDir` | | 数据目录，支持 `{appData}` |
| `cwd` | | 工作目录，默认插件目录 |
| `env` | | 环境变量，值也支持占位符 |
| `capability` | | `backend`（后端 API）/ `danmaku`（弹幕）/ `runtime`（运行时，如 drpy）；应用只自动识别 `backend` 与 `danmaku`，其余值可装可启停但不会自动接线 |
| `tool` | | `true` = 工具型（只提供文件，不常驻） |
| `autostart` | | 默认是否随应用启动（用户可在设置页覆盖） |
| `required` | | `true` = 没有它就没有后端（apiserver）；不禁止停止，只在本应运行却没起来时提示降级 |
| `fatal` | | `true` = 启动失败视为致命，透传前端（`boot_info.fatal_reason`） |
| `inject` | | 启动后自动写配置 |

`capability` 的取值以官方 manifest 为准：`apiserver` 用 `backend`，`danmu-api` 用 `danmaku`，
`drpy` / `pyrt` 用 `runtime`。`required` / `fatal` 的完整行为见开发文档 [插件系统](/dev/plugin-system)。

## 占位符

`args` 与 `env` 的值里可用：

| 占位 | 含义 |
|---|---|
| `{port}` | 端口协商后的实际端口 |
| `{dataDir}` | 数据目录绝对路径 |
| `{appData}` | `%APPDATA%/com.meow.player` |

不是所有程序都支持命令行传端口。`danmu-api` 读的是环境变量：

```json
"env": { "DANMU_API_PORT": "{port}", "DANMU_API_BIND": "127.0.0.1" }
```

## 可执行文件查找顺序

```
1. <插件目录>/<exec>                  自动补 .exe / .bat / .cmd
2. <插件目录>/<execFallback 每一项>    支持 ../ 跨插件引用
3. PATH 里的裸命令名                   只对 "java" 这类无路径命令生效
```

::: warning 顺序是有原因的
`danmu-api` 的 `exec` 写 `"node"`。如果先查 PATH，会命中用户系统装的 Node，
我们下载的 `plugins/node/node.exe` 永远用不上：没装 Node 的机器直接失败，
装了的机器「看起来正常」但走了错误的解释器。
:::

跨插件引用的写法：

```json
"exec": "node",
"execFallback": ["../node/node.exe", "../node/node", "node.exe"]
```

## 自动接线（inject）

插件启动成功、健康检查通过后，把实际地址写进指定配置文件：

```json
"inject": {
  "target": "{appData}/data/policy.json",
  "key": "danmakuApi",
  "value": "http://127.0.0.1:{port}"
}
```

规则：

- 目标字段**已有非空值且不等于本插件地址**时跳过写入（尊重用户手填）
- 目标文件不存在时创建
- `key` 是顶层键，当前支持 `danmakuApi` 与 `jsRuntime`

## 工具型插件

只提供文件、不需要常驻进程的：

```json
{
  "id": "node",
  "name": "Node 运行时",
  "version": "x.y.z",
  "tool": true,
  "exec": "node.exe",
  "autostart": false
}
```

UI 上显示「已就绪」而不是「运行中」，没有启停按钮，状态保持 Stopped 但不算失败。

## 打包检查清单

- [ ] `manifest.json` 在 zip 根层级
- [ ] 所有可执行文件与依赖都在 zip 里（别依赖用户机器已装运行时）
- [ ] 端口用 `{port}`，不要写死
- [ ] 提供 `/health` 或等价探针，`healthExpect` 填实际会出现的字符串
- [ ] 数据写 `{dataDir}`，不要写插件目录（升级会替换整个目录）
- [ ] 不需要常驻的用 `tool: true`
- [ ] 版本号与 `packages.json` 里的 `version` 一致

## 发布

manifest 写好之后，把 zip 的 SHA256 填进插件源清单：

```json
"my-plugin": {
  "version": "1.0.0",
  "url": "my-plugin-1.0.0.zip",
  "sha256": "……",
  "size": 1234567
}
```

清单的完整格式与托管方式见 [自建插件源](/ecosystem/plugin-source)。
