# TV 运行时模块

TV 端的 `.js` 源与弹幕聚合需要一个 Node 运行时；`.py` 源需要 Python 运行时。
它们都**不是**塞进主包的 `.so`，而是独立安装的 APK，由宿主动态加载。

::: details 为什么必须是独立 APK（开发者）

Android 10+ 禁止从可写目录 `dlopen`。装成 APK 后，`.so` 位于只读的
`/data/app/.../base.apk!/lib/<abi>/`，动态链接器才肯加载。

宿主的做法：

```java
new DexClassLoader(moduleApk, odexDir, moduleApk + "!/lib/" + abi, hostLoader)
```

模块里的 `System.loadLibrary("meow_node_bridge")` 用这个 searchPath，
于是桥 `.so`、它依赖的 `libnode.so`、`libc++_shared.so` 一起被解析。

这也是为什么模块不需要与主应用同签名：`DexClassLoader` 不校验签名，
但安装包本身要走系统安装流程。

:::

## 模块提供什么

| 模块 | 作用 | 依赖 |
|---|---|---|
| Node 运行时 | 给 drpy 的 `.js` 源提供执行环境 | 无 |
| Python 运行时 | 给 `.py` 源提供执行环境（CPython 3.12 + pyrt 载荷，Chaquopy 打包） | 无 |
| 弹幕聚合 | 弹幕源从 1 个变 20+ | Node 运行时 |

模块跑在单独的 `:node` 进程里。宿主通过 `NodeRuntimeApi` 接口调用它，
接口只增不改：新增方法给默认实现，或走能力探测（`instanceof` 子接口）。

## 安装

1. 设置 →「运行时模块」
2. 选择与设备 ABI 匹配的条目下载
3. 校验 SHA256 后调系统安装器
4. 安装完成，应用自动 stop 再 start，加载新模块

ABI 对应关系：

| 设备 | ABI | 资产名 |
|---|---|---|
| 主流电视盒子 / 手机 | `arm64-v8a` | `tv-node-arm64-v8a-<node版本>.apk` |
| 老设备 / 部分投影仪 | `armeabi-v7a` | `tv-node-armeabi-v7a-<node版本>.apk` |
| 模拟器 / x86 盒子 | `x86_64` | `tv-node-x86_64-<node版本>.apk` |

Python 运行时资产同名规则：`tv-python-<abi>-<版本>.apk`（`tv-python-arm64-v8a` /
`tv-python-armeabi-v7a` / `tv-python-x86_64`，三个 ABI 与 Node 模块同构）。

装错架构的模块会装不上，或装上后加载失败。

## 清单字段

模块条目在 `packages.json` 里，与插件同构：

```json
"tv-node-arm64-v8a": {
  "display_name": "Node 运行时（模块）",
  "version": "18.20.4.300",
  "platform": ["android-arm64-v8a"],
  "url": "tv-node-arm64-v8a-18.20.4.300.apk",
  "sha256": "……",
  "size": 52116312
}
```

| 字段 | 说明 |
|---|---|
| `min_app_version` | 已定义：第一个支持运行时模块的壳版本（当前 `1.1.0`）；当前官方清单未输出该字段，下载前预检不会生效 |
| `min_sdk` | 已定义：模块 APK 的 minSdk（当前 26，Android 8.0）；当前官方清单未输出该字段，下载前预检不会生效 |
| `platform` | ABI 标识，客户端按设备 ABI 匹配 |

`min_app_version` 与 `min_sdk` 字段已定义，但当前官方清单未输出，客户端的下载前预检
不会生效；自建清单带上这两个字段时，客户端会在**下载前**比对，不满足直接禁用按钮并说明原因。

★ **模块版本随 App 版本单调**（1.3.0 起，release plan 算好传参）：
`tv-python = 0.<App 次版本>.<App 修订号>`（1.3.0 → `0.3.0`）、
`tv-node = <node 版本>.<次版本×100 + 修订号>`（1.3.0 → `18.20.4.300`）。
旧式 `0.1.<壳补丁>` 在 minor 升级时会倒退（1.3.0 → `0.1.0`），已废弃；
守门在 `deploy/scripts/check-plugin-versions.ps1`。

## 版本兼容契约

| 变更 | 要不要重出模块 | 要不要更新壳 |
|---|---|---|
| 改载荷 / 桥 / node 版本 | ✅ 三个 ABI 都重出 | 不用，壳会重新解包载荷 |
| 改 `NodeRuntimeApi` 签名 | ✅ 主版本 +1 | ✅ 新壳要兼容老模块，或抬高 `min_app_version` |

真出现不兼容（接口改了却没抬版本），`NodeService` 会把
`AbstractMethodError` / `NoSuchMethodError` 翻成
「模块与当前应用不兼容（接口已变更），请更新运行时模块」。

## 排障

| 症状 | 原因 |
|---|---|
| 模块装了但应用认不出 | Android 11+ 需要 `<queries>` 声明，否则 AppsFilter 会 BLOCKED |
| 加载失败 | ABI 不匹配；或 `.so` 不在 APK 里而在可写目录 |
| 装完没重启 | 需要先 stop 再 start（应用会自动做，失败时手动重开） |
| 弹幕还是单源 | 模块已装但未启用，或 `policy.json` 里 `danmakuApi` 被手填地址占着 |

::: details 构建步骤（开发者）

```bash
# 1. 放预编译 libnode.so（nodejs-mobile 18.20.4）
#    bin/<abi>/libnode.so → src/main/jniLibs/<abi>/
# 2. 编 JNI 桥（不走 CMake）
./build-bridge.sh
# 3. 出 APK
gradle :runtime-node:assembleRelease
```

三个 ABI 都要出包；`abiFilters` 只留一个时，其他架构的设备会装不上。

:::

## 相关文档

- 模块与壳的完整契约：开发文档 [TV 接入与 Fongmi](/dev/tv-integration)
- 插件机制（PC 侧）：[插件与安装](/ecosystem/plugins)
