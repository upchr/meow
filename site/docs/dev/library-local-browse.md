# 本地目录片库：目录树选择与授权路径接入

> 状态：**已实现**（2026-11-20，分支 `feat/public-auth`）。落地记录与偏差见文末 §十。
> 目标：把"添加本地目录片库"从**手打绝对路径**改成**在应用里点目录树**，
> 并让 fpk / PC / Web-Docker / TV 四端各自只用"它真有权限的目录"。
>
> 相关：[片库与离线缓存](./library-cache.md)、
> fnOS 套件说明（仓库 `client/apps/fnos/README.md`）、
> [飞牛网关身份接入](./fnos-gateway-auth.md)。

## 结论先行

| 端 | 目录从哪来 | 选择方式 | 现状 |
|---|---|---|---|
| fpk（fnOS 套件） | 用户在 **fnOS 应用设置 → 访问权限** 里授权的文件夹（`TRIM_DATA_ACCESSIBLE_PATHS`）+ 应用共享目录（`TRIM_DATA_SHARE_PATHS`） | 应用内目录树 | ❌ 只能手填 |
| PC（Tauri） | 本机文件系统 | 系统文件夹选择器（已有）+ 应用内目录树 | 🟡 只有系统对话框 |
| Web / Docker | 容器内的挂载点（环境变量声明） | 应用内目录树 | ❌ 只能手填，且容易填成宿主机路径 |
| TV（Android） | 端内 `meowTv.listDirs`（SAF / 所有文件访问权限） | 内置目录浏览器（已有） | 🟡 只有 TV 有 |

一句话：**后端已经在用的 `/api/library?action=ls` 能力，要从"已保存库的子目录"扩成"任何已授权根的目录树"**，
前端只加一个统一的「选择目录」弹窗，四端共用。

## 一、现状与缺口

### 1.1 已有的能力（不是从零开始）

| 能力 | 位置 | 说明 |
|---|---|---|
| 本地目录列举 + 穿越防护 | `Library.java` 的 `listLocal()` / `resolveLocal()` | `base+root` 为根，`toRealPath()` 归一化后必须仍在根内；`../` 与符号链接逃逸都拒绝 |
| 库列表 / 测试连接 | `LibraryRoutes.java` 的 `handleLibrary()`（`GET /api/library`、`POST /api/library?action=test`） | 不带 `lib` 时会对每个已保存库列出顶层 `dirs` |
| TV 目录浏览器 | `LibraryAddDialog.vue` 的 `tvDirBridge()` / `loadDirs()` | 桥方法 `meowTv.listDirs(path)` 返回目录 JSON；先要「所有文件访问权限」 |
| PC 目录选择 | `LibraryAddDialog.vue` 的 `pickNativeDir()` | Tauri `plugin:dialog\|open` |

### 1.2 缺的那一环

`GET /api/library?lib=<id>&path=<rel>` **只对已保存的库生效**。添加弹框在保存前没有 `lib`，
所以 Web / Docker / fpk 三条路径只能手打 `base`。

而手打带来的具体问题（论坛反馈 + 实际排障）：

1. 用户不知道 **该填哪台机器上的路径**（Web/Docker 下是容器内路径，`LibraryAddDialog` 的提示只有一句 `D:/影视 或 /mnt/media`）；
2. 不知道共享名/目录层级（SMB 同理，见 #5）；
3. fpk 用户已经在 fnOS 里授权了文件夹，**应用里却看不见**（`TRIM_DATA_ACCESSIBLE_PATHS` 全仓零命中）。

## 二、fnOS 的授权目录机制（fpk 唯一的正确来源）

### 2.1 用户侧流程（与飞牛影视同一个入口）

```
fnOS 桌面 → 应用中心 → 猫窝 Meow → 应用设置
  └─ 访问权限 → 允许访问以下文件夹 → 「+ 添加」
       └─ 选择文件夹（我的文件 / 团队文件 / 存储空间N / 外接存储 / 远程挂载）
       └─ 权限：读写 / 只读
```

我们 manifest 里 `disable_authorization_path=false`，所以这个入口**本来就有**，
只是应用侧没消费授权结果。

### 2.2 应用侧拿到什么

| 变量 | 内容 | 来源 |
|---|---|---|
| `TRIM_DATA_ACCESSIBLE_PATHS` | 用户授权的路径，**冒号分隔** | [开发者文档 · 环境变量](https://developer.fnnas.com/docs/core-concepts/environment-variables/) |
| `TRIM_DATA_SHARE_PATHS` | `config/resource` 里 `data-share` 声明的共享目录 | 同上 |

两条都是 meowd 进程（由 `cmd/main` 拉起）能读到的环境变量；**Java 子进程读不到**——
`meowd` 的 `supervisor.rs` 目前只给 Java 传 `MEOW_BIND` 与 `MEOW_INSTANCE_ID`。

> ★ 待实测：fnOS 的"授权目录"是否真的把 ACL 授给了 **package 用户（`meow`）**。
> 官方应用影视是 `run-as: root` 所以不受限；我们必须在真机上 `sudo -u meow ls <授权路径>` 验一次，
> 再决定要不要 `join-groups`。验不过就只能降级为"提示用户改用 SMB/WebDAV"。

### 2.3 为什么不能学影视那样直接扫任意卷

影视的 `manifest` 是 `install_type="root"`、`privilege` 为 `run-as: root`，
所以它能把 `/vol02/1000-0-xxx/电视剧/` 直接写进 `dir_list`（见 `trimmedia.db` 的 `mediadb_config`）。

我们跑在 `package` 用户下，这是**不该改的红线**（一改整套安全模型作废）。
所以同一件事只能走两条路：用户授权目录（本节）或 SMB/WebDAV。这也是"目录树只能列已授权根"的由来。

## 三、目标交互

### 3.1 统一「选择目录」弹窗

形态对齐飞牛影视的选择文件夹弹窗（左栏分组 + 右栏树），但**左栏只出现本端真实可用的根**：

```
┌ 选择目录 ──────────────────────────────── ✕ ┐
│ ┌──────────────┬──────────────────────────┐ │
│ │ 应用共享      │  ▸ 电视剧                 │ │
│ │ 已授权目录    │  ▾ 电影                   │ │
│ │ 存储空间2     │     ▸ 2024               │ │
│ │ 外接存储      │     ▸ 2025               │ │
│ │ 远程挂载      │  ▸ 动漫                   │ │
│ └──────────────┴──────────────────────────┘ │
│ 当前：/vol02/.../电影/2025        [上一级]    │
│                            [取消]  [选中此目录] │
└──────────────────────────────────────────────┘
```

规则：

1. **只能选目录**，不能选文件（片库根必须是目录）；
2. 面包屑 + 「上一级」用**路径栈**而不是字符串截断（TV 已有实现，见 `pickerStack`）；
3. 空目录/无权限目录：显示一行灰字原因（"无权限"/"空目录"），不静默消失；
4. 弹窗打开后焦点落在第一项（TV 走 `data-tv-scope` + 首项 `data-tv-focus`，Web/DOM 原生 focus）；
5. 选中后回填 `base`，若库里要求"根路径"（`root`）则默认留空。

### 3.2 各端的"根"是什么

| 端 | 左栏分组 | 说明 |
|---|---|---|
| fpk | 应用共享 / 已授权目录 | `TRIM_DATA_SHARE_PATHS` + `TRIM_DATA_ACCESSIBLE_PATHS`，label 用目录名 |
| PC | 本机磁盘 / 家目录 / 已选过的路径 | 后端就是本机 JVM，可直接 `File.listRoots()` |
| Web / Docker | 已挂载目录 | 只列 `MEOW_LIBRARY_ROOTS` 声明的挂载点，**不列容器全盘** |
| TV | 存储 / 外接 / 已授权 | 优先端内 `meowTv.listDirs`；本机网关可用时也可走 `/api/library?action=roots` |

## 四、接口设计

### 4.1 三个端点

```
GET  /api/library?action=roots
     → { ok, roots: [ { id, label, path, source, readOnly, canBrowse } ] }
       source: "share" | "authorized" | "mount" | "drive"

GET  /api/library?action=ls&root=<id>&path=<相对路径>
     → { ok, dirs: [ { name, path, mtime } ], files: [...], truncated? }
       只用于目录树：默认不返回 files（`includeFiles=1` 才返回）

GET  /api/library?action=capabilities
     → { ok, canBrowse, canNativePicker, canTvBridge, hints: { localPathNote } }
```

要点：

- `roots` 的 `id` 是**稳定的短标识**（如 `share:0` / `auth:2`），`path` 只作为展示与回填；
  前端提交时用 `id` + 相对路径，避免路径注入。
- `ls` 的服务端校验复用 `resolveLocal()`：`rootPath.resolve(rel).normalize().toRealPath()` 必须仍在根内；
  拒绝 `..`、拒绝符号链接逃逸、拒绝跨根。
- 每次 `ls` 单层、最多返回 2000 条（`truncated=true` 提示"目录太大"），不做递归扫描。

### 4.2 未保存的库怎么测试连接

现有 `POST /api/library?action=test` 接收完整 `lib`（含 `pass`），已经够用，
**不要**为"浏览"再传一次密码。浏览只针对"已授权根/已挂载点"，与凭据无关；
SMB/WebDAV 的目录浏览另议（见 §7）。

## 五、去重规则（"一个目录只能添加根一次"）

在 `Library.saveConfig()` 里做，前端只做提示。

| 情形 | 判定 | 处理 |
|---|---|---|
| 同一 `type` + 同一归一化根 | 重复 | **拒绝**，返回 `{ok:false, code:"DUP_ROOT", msg:"已在库「电影」中"}` |
| A 的根是 B 的根的祖先/子孙 | 重叠 | **允许但警告**（会重复扫描）：`{warn:"与「动漫」重叠"}` |
| 同一 base 下不同 root | 正常 | 允许 |
| SMB/WebDAV | 规范化 URL（小写 scheme/host、去尾斜杠、解码 path） | 同上 |

归一化细节：

- 本地目录：`toRealPath()`（不存在则父目录 `toRealPath()` + 文件名，与 `resolveLocal` 同款兜底）；
- Windows：大小写不敏感比较；
- 末尾斜杠统一去掉再比。

## 六、各端落地

### 6.1 fpk

1. `cmd/main` 用 `nohup` 起 meowd，**继承 fnOS 注入的 `TRIM_*`**；`supervisor.rs` 起 Java 时补两行
   ```rust
   .env("MEOW_ALLOWED_PATHS", std::env::var("TRIM_DATA_ACCESSIBLE_PATHS").unwrap_or_default())
   .env("MEOW_SHARE_PATHS", std::env::var("TRIM_DATA_SHARE_PATHS").unwrap_or_default())
   ```
2. Java 的 `roots` 实现：两个变量都为空时返回空数组（不猜路径）；
3. 设置页显示"已授权 N 个目录"，未授权时给一句引导：
   "到 fnOS 应用设置 → 访问权限 → 添加文件夹，然后回到这里刷新"；
4. 应用共享目录 `meow/library` 自动作为一个根（顺带修掉 `wizard_library_mode` 这个死选项——
   选 `share` 时应直接用 `TRIM_DATA_SHARE_PATHS` 建库）。

### 6.2 PC

系统选择器保留；目录树用 `/api/library?action=roots` 返回盘符 + 家目录。
`hints.localPathNote = "这是本机路径"`。

### 6.3 Web / Docker

`deploy/docker/*.yml` 增加 `MEOW_LIBRARY_ROOTS`（例：`/media:/data`），
`hints.localPathNote = "容器内路径；宿主机目录需要先在 compose 里挂载"`。
未声明时 `roots` 为空 → 弹窗显示"未挂载任何目录"，避免用户去填 `/volume1/...` 白费功夫。

### 6.4 TV

保留 `meowTv.listDirs`（SAF 权限模型只有端内能处理）。
`capabilities.canTvBridge=true` 时前端优先用它；TV 本机网关的 `/api/library?action=roots` 作为补充。

## 七、SMB / WebDAV 的目录浏览（第二阶段）

同一套弹窗，`roots` 换成"连接参数"：填 `base` + 账号 → `测试连接` → 列顶层目录 → 点选。
服务端复用 `Library.list()`（`smb` / `webdav` 两条实现），不需要新协议。
这一条与 [issue #5](https://github.com/upchr/meow/issues/5) 的"失败归因分级"一起做才有意义
（连不上时要告诉用户是端口、共享名还是凭据）。

## 八、验收

- [ ] fpk：在 fnOS 应用设置里授权 `/vol02/.../影视`，回到猫窝 → 添加片库 → 左栏出现该目录 → 一路点进子目录 → 保存 → 片库能列出影片（**权限实测通过**）
- [ ] fpk：未授权任何目录时，弹窗给引导文案，不是空白
- [ ] Docker：compose 挂 `./media:/media` + `MEOW_LIBRARY_ROOTS=/media` → 弹窗只出现 `/media`
- [ ] 路径穿越：`action=ls&path=../../etc` 返回 400；符号链接指向根外 → 拒绝
- [ ] 去重：同一目录添加第二次被拒并指名冲突库；父子目录添加只警告
- [ ] TV：弹窗内方向键可走完左栏/右栏，返回键逐级退，焦点不丢
- [ ] Web/Docker 手填路径仍可用（不是强制替换）

## 九、风险与不做

| 项 | 说明 |
|---|---|
| ACL 未授给 package 用户 | 真机实测项；失败则 fpk 只能回退 SMB/WebDAV，文档要写清 |
| 远程挂载（FUSE） | 授权路径可能是 rclone/网盘挂载，Java 读目录可能极慢或返回 I/O 错误 → `ls` 加 3 秒超时并提示 |
| 不做"扫描整盘" | 只列根的单层；不做递归索引（那是扫描器的事） |
| 不做"应用内授权" | 授权入口归 fnOS，应用只消费结果；自己再实现一套会和系统设置打架 |

## 十、落地记录（2026-11-20）

| 项 | 落点 | 状态 |
|---|---|---|
| 授权路径透传 | `meowd/src/supervisor.rs`：`MEOW_ALLOWED_PATHS ← TRIM_DATA_ACCESSIBLE_PATHS`、`MEOW_SHARE_PATHS ← TRIM_DATA_SHARE_PATHS` | ✅ |
| 三个端点 | `LibraryRoutes.handleLibrary()` 的 GET 分支（**在 lib 分支之前**）：`action=roots` / `ls` / `capabilities` | ✅ |
| 根的来源 | `core/LibraryRoots.java`：share → authorized → mount（`MEOW_LIBRARY_ROOTS`）；本机模式额外给盘符+家目录 | ✅ |
| 穿越防护 | `LibraryRoots.resolve()`：`normalize` 后必须仍在根内，**再**用 `toRealPath` 复核（挡符号链接逃逸） | ✅ |
| 3 秒超时 | `LibraryRoutes.lsWithTimeout()`：守护线程池 + `Future.get(3s)`，超时回"目录读取超时" | ✅ |
| 根去重 | `Library.saveConfigEx()`：`DUP_ROOT` 拒绝 / 父子重叠 warn；`LibraryRoutes` 用 400/500 区分输入错误与写盘失败 | ✅ |
| 统一弹窗 | `LibraryAddDialog.vue`：`openPicker()` 先问后端 roots，拿不到再退 TV 端内桥；左栏根 + 右栏树 + 面包屑路径 | ✅ |
| wizard 死选项 | `fpk/cmd/install_callback`：`wizard_library_mode=share` 时用 share 目录建第一个库（只在该文件不存在时写） | ✅ |
| Docker 挂载点 | `MEOW_LIBRARY_ROOTS` 进 4 个 compose + `.env.example` | ✅ |

**实测**（NAS `.34`）：

```text
GET /api/library?action=roots        → share:0 → /vol1/@appshare/meow/library（canBrowse=true）
GET /api/library?action=capabilities → hint 指向「应用设置 → 访问权限」
GET /api/library?action=ls&root=share:0&path=           → 200（空目录）
GET /api/library?action=ls&root=share:0&path=../../etc  → 400 path escapes root
POST /api/library 同根两次                              → 400 {code:DUP_ROOT, msg:"已在库「电影A」中"}
POST /api/library 父目录+子目录                          → 200 {ok:true, warn:"与「根」重叠，会重复扫描"}
```

**ACL 实测**（§2.2 的待验项）：

```text
sudo -u meow ls /vol1/@appshare/meow/library   → 可读可写（drwx------ meow meow）
sudo -u meow ls /vol1/1000                     → Permission denied（未授权目录确实读不了）
$ meowd 进程 env: TRIM_DATA_SHARE_PATHS=/vol1/@appshare/meow/library
                  TRIM_DATA_ACCESSIBLE_PATHS=（空，用户尚未在应用设置里授权）
```

结论：**授权机制是真的**（未授权读不了），但"用户授权某个 `/vol02/...` 后 meow 能否读"
仍需要用户在 fnOS 界面里实际授权一次才能验 —— 这一步没法在命令行模拟。

**与设计的偏差**

1. **左栏在窄屏常显、不做折叠**：`w-28`（112px）固定宽度。TV 上就是靠它换存储卷，
   折起来等于藏掉唯一入口。
2. **`ls` 默认不返回 files**（规格也这么写），`includeFiles=1` 才给 —— 目录树只关心目录。
3. **去重键含 `root`**：同一 base 下不同 root 是两个库（规格 §5 表格里的"正常"），
   所以 `dupKey` 是 `type|归一化base|root`。

**已知限制**

1. **fpk 未授权时 roots 为空**（当前真机状态）：弹窗显示引导文案，不会列出 `/vol1` 这类猜测路径。
2. **远程挂载（FUSE/网盘）可能极慢**：3 秒超时会回"目录读取超时"，但超时后后台线程可能仍在跑
   （`cancel(true)` 对阻塞的 `listFiles()` 无效）——守护线程池保证它最多占一个线程，不会堆积。
3. **SMB/WebDAV 的目录浏览未做**（§7 第二阶段）：它们的弹窗仍是手填 base + 「测试连接」。

### 10.1 两个只有真机才暴露的坑（2026-11-20）

1. **中文目录点不进去 → `Malformed input or input contains unmappable characters`**
   - `java.nio.file.Paths.get("电影")` 用的是 **`sun.jnu.encoding`**，而 fnOS 的 `cmd/main` 不设
     locale（`LANG` 未定义 → POSIX/C），JVM 退到 `ANSI_X3.4-1968`，中文文件名无法映射。
   - 修法：`supervisor.rs` 起 Java 时同时给 `-Dsun.jnu.encoding=UTF-8` **和**
     `LANG/LC_ALL=C.UTF-8`（实测 Java 21 两者都要，只给 `-D` 不够）。
   - Docker 侧早就设了这一个（compose 的 `JAVA_OPTS`），fpk 漏了 —— 这也是为什么
     同一个后端在 Docker 里中文正常、在 fpk 上崩。

2. **左栏显示内部卷名（`1000-0-a4d36b4c`）而不是「视频」**
   - fnOS 的「远程挂载」在 `TRIM_DATA_ACCESSIBLE_PATHS` 里给的是**挂载点路径**，
     不是用户看到的共享名；`/proc/mounts` 里才有真名：`//192.168.1.19/视频 → /vol02/1000-0-a4d36b4c`。
   - 修法：`LibraryRoots.labelOf()` 先查 `mountLabel()`（只对 cifs 的 `//host/share`
     与 nfs 的 `host:/export` 取末段，本地块设备不取，否则整卷显示成 `sda1`），查不到再退回目录名。

**改造后的真机验收**（`.34` 与 `.20` 两台都过）：

```text
roots → share:0(library) + authorized:0(label=视频, path=/vol02/1000-0-a4d36b4c)
ls authorized:0                → 18NO / 其他 / 动漫 / 电影 / 电视剧
ls authorized:0 path=电影       → 教父 / 色戒
ls authorized:0 path=电影/教父&includeFiles=1 → 第1部.1972...mp4 (9.3GB) / 第2部.1974...mp4
POST /api/library 建 local 库   → ok:true
GET  /api/library              → tops 列出 教父 / 色戒
```

★ 注意 `.20`（OECT，aarch64）上的 `TRIM_DATA_ACCESSIBLE_PATHS` 与 `.34` 是**同一台**
SMB 共享（`//192.168.1.19/视频`），两台机器的授权各自独立——授权要按机器分别做。
