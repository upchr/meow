# AGENTS.md

给在本仓库里改代码的 AI 代理 / 贡献者看的**操作手册**。
产品介绍与安装见 [README.md](README.md)；部署发版见 [deploy/README.md](deploy/README.md)。

> **本文件只写两种东西**：①不看就会做错的判据与禁区；②改完怎么验。
> 长解释、设计理由、历史沿革在 `site/docs/dev/`，这里只给指针。
> 判断标准：这一段删掉后，代理会不会踩坑？不会 → 删掉。
>
> 本机或私有部署若存在 `AGENTS.private.md`（不入公开库），其中的环境、设备与私有源约定优先。

---

## 指令优先级

1. 维护者当次指令 > 本文件 > 仓库里的其他文档
2. 本文件与 `site/` 文档冲突 → **以 `site/` 文档为准**
3. 本文件与代码冲突 → **以代码为准**，然后顺手修正本文件

任何数字、行号、命令，三秒能验证的先验证再写。

---

## 〇、仓库长什么样

| 目录 | 是什么 | 能不能改 |
|---|---|---|
| `client/apps/web/` | Vue 3 + Vite 前端（Web / PC / TV 共用） | ✅ 主战场 |
| `client/apps/desktop/` | Tauri 2 壳 + 插件管理 + 内置 MCP | ✅ |
| `client/apps/tv/` | Android WebView 壳 + 端上 ExoPlayer + 本地 jar 执行层 | ✅ |
| `client/apps/fnos/` | 飞牛 fnOS 套件（`meowd` + fpk 打包）；说明见该目录 `README.md` | ✅ |
| `server/host/` | JVM 宿主（`ApiServer.java` + `routes/`）+ `android.*` stub | ✅ |
| `server/drpy/` | drpy 外挂 Node 运行时（打成插件用） | ✅ |
| `server/host/libs/` | 公开依赖 jar。`spider.jar` / 真实站点配置 **不入库**，由源包导入 | ⚠️ 只读 |
| `deploy/` | Docker（3 Dockerfile + 4 compose，含「不 clone 直接下」的 `docker-compose.standalone.yml`）+ 发版脚本 + `units.json`（构建单元唯一真源） | ✅ |
| `docs/` | 索引 + 阶段性快照/专项文档；入口是 `docs/00-目录索引.md`。技术文档正文在 `site/docs/dev/` | ✅ |
| `site/` | **文档站源码（VitePress）**：`guide/` 用户指南、`ecosystem/` 源包与插件、`reference/` 参考、`dev/` 技术文档。**文档单一真源在这里**，维护清单见 `site/README.md` | ✅ |
| `smoke/` | 三端冒烟脚本；验收流程见 [site/docs/dev/testing.md](site/docs/dev/testing.md) | ✅ 长期保留 |
| `.agents/skills/` | **仓库内置 AI 技能**（控制/排障、发版），项目内自动发现；索引见该目录 `README.md` | ✅ |
| `research/upstream/` | 上游参考源码（**gitignore**） | ❌ 只读对照 |
| `reverse/` | 反编译产物与工具链（`toolbin/` 不入库） | ✅ |
| `release/` `tmp/` | 发版产物 / 临时文件，**都 gitignore** | ✅ 可随时删 |

### 0.1 产物与临时文件放哪（★ 不要写到工作区外）

| 你要做什么 | 放哪 | git |
|---|---|---|
| 发版产物（安装包、插件 zip、`packages.json`） | `release/vX.Y.Z/` | 忽略 |
| 中间产物、一次性脚本、抓包、调试截图 | `tmp/` | 忽略 |
| 有长期价值的分析结论 | 写进 `docs/` 现有文档，**不要新建** | 入库 |
| 某模块的长期说明 | 该模块目录下的 `README.md` | 入库 |
| 反编译/逆向产物 | `reverse/decompiled/`、`reverse/artifacts/` | 部分忽略 |

不要写 `../xxx`、不要写同级目录、不要用系统临时目录（`/tmp`、`%TEMP%`）——
换个会话就找不到了。**判断标准：下次还要用它吗？** 要 → 归到对应目录并入库；不要 → `tmp/`。

**`release/` 用版本号分目录**（`release/vX.Y.Z/`），避免多轮构建互相覆盖。

**`tmp/` 的归集规则**（唯一可执行的命名要求）：

```
tmp/<YYYY-MM-DD>-<主题>/     例：tmp/2026-10-27-文档收缩/
```

一次性脚本、抓包、截图都放进当天的主题目录，**不要直接扔 `tmp/` 根下**。
旧的不追溯；**新增的一律进子目录**。

**★ worktree 的 `tmp/` 不共享**（每个工作树各一份物理目录，gitignore 不随分支走）：

- 验证产物、`--data-dir`、抓包、日志一律写主工作区**绝对路径**
  `P:/workspace/ai-projects/meow/tmp/<日期>-<主题>/`，**不要写相对 `tmp/`** ——
  cwd 决定落点，落在工作树里，收尾删工作树就一起没了（实测：
  `--data-dir=../../tmp/...` 的测试实例，数据落在该 worktree，换 cwd 重启即找不到）。
- worktree 自己的 `tmp/` 只放**退出即弃**的中间物（编译产物、临时截图）。
- 跨会话语义的数据（请求样本、日志、结论）放主工作区 `tmp/` 或 `docs/`。

### 0.2 分支 / worktree 卫生（★ 2026-11-24 更新）

**单会话默认不建 worktree；多会话并行必须一会话一分支一工作树**：
单会话没被要求时在主工作区直接干，**不要自己建**；同时有 ≥2 个会话改同一仓库时，开工就建
`.worktree/<主题>` + `feat/<主题>-<会话短id>`，prompt 里写死"只改这个 worktree/branch"。
用户主动开了 wt，收尾责任就跟着这活走（见下）。

**用 wt 开的活，收尾三件事**：合入 main 当天做完，不要留给"下次"：

```bash
git worktree remove .worktree/<主题>
git branch -d feat/<主题>
git worktree prune
```

**四条判据**：

| 判据 | 为什么 |
|---|---|
| **备份用 tag，不用分支**：`git tag backup/<说明>` | 分支会污染 `git branch` 列表；没有 tag 覆盖的备份分支，删了就真没了 |
| **`git branch -d` 拒绝 ≠ 分支还有用** | 历史重写/无共同祖先时 `git branch --merged` 永远判 false。删前查 `git tag --contains <分支>`，有 tag 收口才可 `-D` |
| **worktree 脏时不要 `-f`** | 先把活提交到分支（WIP 提交也行）再删。`-f` 会把"没保存的改动"和分支一起抹掉 |
| **脚本里建 worktree 必须 try/finally** | Windows 上 `worktree add` 会撞 `locked: initializing`；要改别的分支优先用临时 index（`GIT_INDEX_FILE` 写法） |

**不手删**：`.zcode/worktrees/*` 是 ZCode 宿主的会话回滚点，删掉会让历史会话的 rewind 失效。
不使用 ZCode 的贡献者可忽略这一条。

**分支状态三分（别靠记忆判断"合没合"）**：

```bash
git rev-list --left-right --count main...<branch>   # 左=main 独有，右=分支独有
git cherry main <branch>                            # + 独有提交；- main 已有等价提交
```

| 分支独有数 | 判定 | 动作 |
|---|---|---|
| 0 | **已合入** | 直接收尾（tag → `worktree remove` → `branch -d`），当天做完 |
| >0，且两点 diff 只显示"main 侧更完整" | **已被取代** | 给维护者看 `git diff --stat main <branch>`，同意后 `git tag backup/<说明>` + `-D` |
| >0，正常演进 | **开发中 / 待合** | 进合并队列；工作树脏是正常状态，**不列为卡点** |

`git worktree list` 里分支名前的 `+` 只表示"该分支被某个工作树检出"，**不是"未合并"**。

**巡检（发版前跑一次，30 秒）**：

```bash
git worktree list                 # 有没有该收没收的
git branch -vv                    # 既不在 main、也不对应 worktree 的可疑分支
git branch --merged main          # 可安全删的
git worktree prune --dry-run      # 目录已没了的孤儿登记
```

**本机已知工作树（2026-11-25 快照，动手前重跑巡检，不要照表当现状）**：

| 工作树 | 分支 / 状态 |
|---|---|
| 主工作区 `P:/workspace/ai-projects/meow` | `main`，集成者占位 |
| `.worktree/ios-jvm` | `feat/ios-jvm`，**已合入 main（0 独有）**，待收尾 tag + 删 |
| `.worktree/private-source-pack` | `private-main`，远程 `private/main`，私有线，不参与公开 main |
| `P:/workspace/ai-projects/meow-auth` | `feat/public-auth`，开发中，**挂起不旁问** |
| 仅分支无工作树：`ipa-smoke` | 已被 main 的 `client/apps/ios`（含 4 个 `_ios*.yml` + `vendor/dex2jar`）取代，待 tag + 删 |
| 仅分支无工作树：`backup/pre-scope-fix` | 历史备份分支，按"备份用 tag"转 tag 后删 |
| `.zcode/worktrees/{90df,ea1f}` | ZCode 会话回滚点，detached，**不手删** |

`.worktree/main-tv` 已并入主工作区；`feat/muse-sources` / `feat/fongmi-loaders`
已用 tag `backup/muse-sources-20261124` / `backup/fongmi-loaders-20261124` 留底后删除。

### 0.2.1 并行会话 / 合并纪律（★ 2026-11-24 定，2026-11-25 修订）

> 2026-11-24 差点把一条与 main 提交等价的旧分支整条合回 main，同时 76 条未提交
> 混着 3 个会话；下面 12 条就是这次事故的收口，第 11、12 条是 2026-11-25 集成实测补的。

1. **一会话一分支一工作树（并行时强制）**：同时有 ≥2 个会话改同一仓库时，开工就建
   `feat/<主题>-<会话短id>` + `.worktree/<主题>`；prompt 里写死"只改这个 worktree/branch"。
   单会话按 §0.2 开头，没被要求不主动建。
2. **main 只做集成**：功能会话不许 commit/push main；只留一个集成者合 main。合并窗口内
   宣布 main 冻结，其他会话停写。
3. **提交边界**：一个功能做完就提交（WIP 也提交），不要留几十个文件跨天。会话结束必须
   `git status` 干净，或明确列出残留文件与原因。
4. **状态卡**：每个会话开始/结束各报一次：
   `worktree / branch / HEAD / dirty 数 / 改了哪些文件 / 跑了哪些测试 / ignored 里有什么要留`。
5. **合并前四件事**：
   - main 工作树未提交文件 ∩ 待合分支触及文件必须为空（**不是要求 `git status` 全干净**：
     merge 只拒绝会被改写的本地修改；打 tag、`worktree remove`、`branch -d` 这类纯引用
     操作不写工作树，不受此限）：

     ```bash
     B=feat/<主题>
     comm -12 \
       <(git status --porcelain | sed -E 's/^.. //; s/.* -> //' | sort -u) \
       <(git diff --name-only main..."$B" | sort -u)
     ```

     三点 diff 只取**分支侧**改动：main 侧改动已在 HEAD 里，merge 不会重写，算进交叠
     只会放大噪声。**空 = 可合；命中 = 先处置命中项再合**。工作区在会话中会被别的写者
     改动（实测一轮会话内从 18 个脏文件涨到 24 个），**合并前重跑这条**，不要用开局快照。
   - `git merge-tree --write-tree --name-only main <branch>` 预演冲突；
   - 查重复提交：`git show <commit> | git patch-id --stable` 对比，或 `git cherry main <branch>`
     （`-` 开头 = 已有等价提交）；
   - 3-way diff（`git merge-file`）判断工作区是"旧版本"还是"独有改动"，**不要整文件拷**。
6. **不要 `git add -A`**：共享工作区只用 `git add <明确路径>` 或 `git add -p`。
7. **行尾检查**：合并前 `git ls-files --eol <file>` 看 `i/crlf`；有 CRLF 先规范化（写 LF 再
   `git update-index --cacheinfo`），否则测试假红、diff 爆炸。
8. **worktree 生命周期**：合并后 `git worktree remove` → `git branch -d` →
   `git worktree prune`；删前先 `git tag backup/<说明>`（备份用 tag，不用长期分支）。
   脏 worktree 不要 `-f` 直接删：先归档 `tmp/`、提交 WIP 或打 patch。
9. **ignored 内容按会话归档**：截图/日志/测试视频放 `tmp/<日期>-<主题>/`；解密产物、私有源包、
   keystore、node_modules/dist/target 永不进 git。删 worktree 前把要留的 `tmp/` 拷到主工作区。
   **收尾前必查三条**（`tmp/` 不跨工作树共享，只有绝对路径能引用）：

   ```bash
   ls <worktree>/tmp/                                                  # 里面有什么
   grep -rn "<worktree 路径>" --include=*.py --include=*.sh \
        --include=*.ps1 --include=*.json .                             # 有没有人引用
   git -C <worktree> status --porcelain --ignored | grep '^!!'         # ignored 里要留什么
   ```

   三条都过了再 `worktree remove`：引用 0 命中、要留的已归档、其余归为一次性。
10. **发版/推送单独授权**：push main、打 tag、跑 CI 都要单独确认；本地合并和远端发布分开。
11. **集成窗口内主工作区写权归集成者**：只读（冒烟、读日志、看代码）随便进；写操作
    （改文件、跑生成脚本、`checkout`/`stash`）会让两个会话同时改一个工作树，且
    `checkout` 会把别人的未提交改动带到别的分支上。要并行就开 `.worktree/<主题>`，
    这不违反第 1 条的"单会话不建"——那条针对的是无并行的场景。
12. **脏工作区先 WIP 提交，再谈合并**：按第 3 条 WIP 提交，或归档到 `tmp/<日期>-<主题>/`，
    解除污染后再动合并。**不要用 `stash` 跨会话传递未提交状态**——`refs/stash` 是仓库级
    共享的，另一个会话 `stash pop` 就抢走了；`git checkout` 同理会把未提交改动带走。

---

## 一、开工前

### 1.1 必读的文档

| 文档 | 读它解决什么 |
|---|---|
| [site/docs/dev/architecture.md](site/docs/dev/architecture.md) | 四层骨架与 ADR（**不要自己重新发明分层**） |
| [site/docs/dev/frontend.md](site/docs/dev/frontend.md) | 不可回退决策（**违反会退回已修 bug**） |
| [site/docs/dev/device-adaptive.md](site/docs/dev/device-adaptive.md) | **端/设备/尺寸判据的唯一真源（四层：身份/形态/输入/方向）**；改任何 `isTv*`、`innerWidth`、`pointer`、返回键/焦点/布局显隐前必读 §1 与 §5 |
| [site/docs/dev/index.md](site/docs/dev/index.md) | 技术文档总索引；用户向问题看站点 `guide/` |

冲突裁决：接口契约 > 架构 > 前端/渲染 > 部署 > 背景。

> ★ **端/设备判据禁令**：不要新增 `innerWidth < N`、UA 判断、`platform === 'tv'`
> 这类临时判据。四层已收口在 `compat/{shell,deviceProfile,inputModality,deviceForm}.ts`，
> 新增判据先看 [device-adaptive.md §5](site/docs/dev/device-adaptive.md) 与 §8.5 负面清单。

### 1.2 环境自检

```bash
node -v                  # 需要 22.x
pnpm -v                  # 需要 11.x
java -version            # 需要 17
python --version         # reverse/tooling 用
echo $ANDROID_HOME       # 改 spider 才需要
```

逆向工具链首次要先拉依赖（`toolbin/` 不入库）：
`python reverse/tooling/fetch_tools.py`

---

## 二、改代码的约定

### 2.1 代码风格（沿用现状，不要引入新工具）

- **前端**：单引号、**不写分号**、2 空格缩进、`<script setup lang="ts">`、组合式 API
- **后端**：4 空格缩进、大括号同行、`private static` 工具方法、常量全大写
- **Python 工具**：4 空格、`snake_case`
- **命名**：Vue 组件 PascalCase（`PlayView.vue`），工具模块 camelCase（`danmaku.ts`）
- **仓库没有 ESLint / Prettier**。不要引入，也不要顺手格式化未改动的文件——会污染 diff

### 2.2 注释只写"为什么"

```java
// ✅ 解释非显然的约束
// 静态块早于 main()，此时 --data-dir 还没解析，会静默落到 cwd/data。
// 所以多实例展开必须放在 initAfterArgs() 里。

// ❌ 复述代码
// 遍历 sites 数组
```

实测踩过的坑写进注释并标 `实测` / `踩过`，让下一个人不敢乱改。
**不要写历史**（"原来是这样，后来改成那样"）——那属于 commit message。

### 2.3 写测试

放在 `client/apps/web/tests/*.test.ts`，`describe` 用中文描述行为，用真实形状的数据。

```bash
cd client/apps/web
npx vitest run                      # 全部
npx vitest run tests/prefs.test.ts  # 单文件
npx vue-tsc --noEmit                # 类型检查（必须 exit 0）
pnpm build                          # = vue-tsc --noEmit && vite build
```

**`vue-tsc` 不校验 SFC 标签配对**——批量改模板后它可能 exit 0，但页面已白屏。
改完 `<template>` 一定看一眼 dev server 日志里的 `Invalid end tag`。

### 2.4 改了就跑最小验证

```bash
# 后端：classes 是 javac 产物、不入库，改完源码必须重编译
cd server/host && ./build.bat

# 起临时实例（不占 9979）
java -Dfile.encoding=UTF-8 -cp "classes;libs/*" ApiServer --port=19979 --data-dir=.tmp/x
curl -s http://127.0.0.1:19979/api/health
```

`/api/health` 的 `ok` = HTTP 活着 **且** spider jar 可加载 **且** 站点表非空。
**单个源站失效不影响 `ok`**，别拿它判断"源挂了"。

---

## 三、绝对不要做

| 禁止 | 原因 |
|---|---|
| 代理不要自行 commit / tag / push | 提交节奏由维护者控制；**要提交等他说** |
| **不要用 GitHub Actions 发版**（打 tag 触发 CI） | 全量 CI 成本高且要盯数分钟；**每次都要维护者明确同意**。本地脚本不受限 |
| 不要擅自打包 PC 安装包 | 默认以 Web 验收，必须等维护者开口 |
| 不要杀端口上"看起来多余"的进程 | 可能是别的会话在测试。只停自己启动的，记 PID |
| 不要修改 `research/upstream/` | 只读对照 |
| 不要用 `reset --hard` 撤销 | 用 `git revert`——可能有并行会话的文件 |
| 不要把 GraalVM native-image 当构建方案 | `Class.forName` 动态加载大量类，AOT 穷举不了反射入口 |
| 不要在 `docs/` 新增文件不登记 | 索引是唯一入口；**正文上限 20 份** |
| 不要把产物/临时文件写到工作区外 | 见 §0.1 |
| 不要写回 `feimao` 旧名 | 品牌已统一 `meow`；包名 `com.meow.player` |
| **绝不把 TV keystore 提交到 git** | `client/apps/tv/*.jks` + `keystore.properties`。进了 git 就永久泄露签名能力；丢了则所有用户无法覆盖升级。见 [site/docs/dev/tv-integration.md](site/docs/dev/tv-integration.md) 签名小节 |
| **绝不把真实源包/配置/解密产物提交** | `spider.jar`、真实 `config.sites.json`、私钥与解密缓存都不进公开库 |

---

## 四、容易写错的硬事实

**改之前先验一遍。每条都给查法，不要凭记忆引用数字。**

### 4.1 版本号

**不要手动改，也不要逐个 grep 校验**（数字以 `bump-version.ps1` 的清单为准）：

```bash
./deploy/scripts/bump-version.ps1 -Check          # 校验一致性
./deploy/scripts/bump-version.ps1 -Version x.y.z  # 一次改齐全部位置
```

`ApiServer.java` 与 meowd 的 `Cargo.toml` **不在清单里**：前后端版本号都已改成运行期读
`VERSION` 文件（见 [构建与发版](site/docs/dev/build-release.md) 的硬约束），这样按源码
hash 的构建缓存才能跨版本复用。

含 TV `versionCode`。**拉取型 compose 的镜像 tag 默认 `latest`**（`docker-compose.yml` /
`.with-sources.yml` / `.standalone.yml` 与 `.env.example`）→ 新机器部署不依赖发版状态；
只有 `docker-compose.build.yml` 仍用 `${MEOW_VERSION:-x}` 给本地构建打 tag。
升级必须 `docker compose pull`（`up -d` 不会重新拉已有镜像）。

### 4.2 站点数：`grep -o` 不是 `grep -c`

```bash
grep -o 'SITES.put("' server/host/src/shared/ApiServer.java | wc -l   # 静态注册的站点
grep -o 'EXT.put("'   server/host/src/shared/ApiServer.java | wc -l   # 静态注册的 ext
curl -s localhost:30011/api/health                                    # 运行时真实值
```

`grep -c` 数的是**行数**，同一行写两个 `EXT.put` 会漏。
运行时真实数量看 `/api/health` 的 `sites`/`ext`——**它只从数据目录读配置**，
数据目录没有 `config.sites.json` 时退化成静态注册数；有配置时会多出一批
`Bili#2`…`Bili#6` 这类多实例站（`loadMultiInstances()` 动态补的）。

### 4.3 路由位置敏感（★ 顺序错了报错信息会误导人）

三层顺序不能动：

```
显式 equals(path) 路由          ← 新增固定端点必须写进这里
    ↓
/api/{site}/{action} 站点分派   ← 不写进上面就会被当成站点名调类
```

**两个必踩的坑**：

1. **`/proxy`（无 `/api` 前缀）必须排在 `/api/proxy/` 兜底之前**。它是 jar 内 `Proxy`
   类探测端口的握手点，正文恰为 `ok` 才把端口记成真实值；排错位置会让 Bili/Hxq
   永远拿到 `127.0.0.1:-1`。
2. **前缀兜底会把剩余段当路径参数**：`/api/health/` → 404、`/api/cache/` → 取本地文件。
   新端点不排在兜底前面就会得到 404 或"文件没缓存"。

行号每次改动都会漂，**引用前先 grep**。

### 4.4 容器：改动前先读 site/docs/dev/docker.md §1.1

`web`(30010) / `apiserver`(30011) / `drpy`(30013→9330) / `pyrt`(30014→19331) / `danmu_api`(30012)。
**四条架构约束**（`depends_on` 为什么没有、nginx 上游为什么要用变量、
`stop` vs `down`、`${VAR-}` vs `${VAR:-}`）见
[site/docs/dev/docker.md §1.1](site/docs/dev/docker.md)——那里的理由写全了，别只看结论就改。

公开壳仓库与镜像**都不含** `spider.jar`；Docker/PC 的 jar 由用户在应用内导入源包
（JVM 用 class 形态、TV 用 dex 形态）。见 §4.12。

### 4.5 播放结果字段：单位是毫秒

`PlayResult` 的 `skips / subs / position / qualities` 由后端 `normalizePlay` 产出，
**单位是毫秒**（CatVod 契约原样透传）。换算成秒必须在 `api/playExtras.ts` 集中做
（`skipWindowFrom` / `startAtFrom` / `subTracksFrom`），**组件里不要再各算一遍**——
两处各算一次是这类字段最容易出错的地方。

`parse` 前端会读：`PlayView.needsParse()` 判 `parse===1` 或 url 不是 http，
命中先生调 `POST /api/parse`，失败按线路级失败处理（会走自动换线）。

### 4.6 后端地址不能写死端口

`resolveBase()`（`client/apps/web/src/api/index.ts`）：localStorage `meow.api`
（须带 `scope='desktop'`）→ Tauri `boot_info` → `VITE_API_BASE` → 同源空串。

桌面端 9979 被占会顺延 9980/9981。**Vite 的 `/api` 默认代理 9979，
旧后端在跑时会静默连错版本** → 靠 `/api/health` 的 `version` 比对才能发现。

### 4.7 静态块早于 main()

`ApiServer` 的静态块执行时 `--data-dir` 还没解析，依赖 `DataPaths` 的初始化
**必须放在 `initAfterArgs()` 里**，否则参数被静默忽略、落到 cwd。

**新增任何读配置的代码都走 `DataPaths`，不要写相对路径**。

### 4.8 换肤：三个 Chromium 坑（照直觉改会回退）

改主题前必读 [site/docs/dev/frontend.md](site/docs/dev/frontend.md)：**不要用 JS 写行内 CSS 变量换肤**
（已渲染元素背景不重算）、**`transition` 写 `background-color` 不写 `background`**、
**`prefers-reduced-motion: reduce` 下 CSS 动画时间轴不推进**。三条都是浏览器实测出来的，
`tokens.css` + `theme.ts` + `index.html` 首帧脚本三处必须一致。

### 4.8.1 模拟器/容器里的局域网发现（改之前先读这段）

`LanScan` 原来只扫本机网卡的 /24，而模拟器/容器的网卡是 **NAT 网段**
（MuMu/AVD `10.0.2.x`、应用宝 VM `172.28.x`），后端却在真实局域网
`192.168.1.x` → 永远扫不到，**尽管网络是通的**（实测 MuMu 里
`ping 192.168.1.10` 0% 丢包、`nc -z 30010` 成功）。

现在是**两阶段**，改的时候别把第二条门槛去掉：

1. 本机网段 + `/proc/net/route` 里默认网关所在段（NAT 下网关背后就是真实局域网）
2. **仅当阶段一零命中**才扫常见家用网段

去掉阶段二的门槛 → 每次开配置页多扫大量探测，"5 秒"变十几秒。
把 `SetupActivity` 的扫描预算调小 → 兜底阶段被砍断，等于没加。

### 4.8.2 大屏字号：px 不会自己变大

模板里有大量硬编码 px（`text-[11px]` 是重灾区）。px 是绝对单位，不随屏幕放大——
同样 11px，在 640px 高的 CSS 视口占 1.7%，到 4K 播放器只剩 0.5%，用户看到的就是"字变小了"。

★ **TV 的 CSS 视口 ≠ 屏幕分辨率**：实测 MuMu 物理 2560×1440、dpr 2.25，
拿到的 CSS 视口只有 1138×640。判断"大屏"要看 `innerWidth`，不是分辨率。

已做的两层（缺一条就回退，`tests/tv-font-scale.test.ts` 锁住）：
1. `html[data-tv='1']` 根字号随视口阶梯放大（断点 1280 / 1920 / 2400）
2. `[class~='text-[11px]']` 等按类名抬到 10-foot UI 下限

**新代码不要写 `text-[Npx]`**，用 `tokens.css` 里的 `--fs-*` 档位
（`--fs-mini` 11px / `--fs-base` 14px / `--fs-lg` 16px …）。

### 4.9 片库 / 缓存 / 转码：改之前读 [site/docs/dev/library-cache.md](site/docs/dev/library-cache.md)

六个维护判据（片库与代理为什么分离、Range、批量队列、断点续传判据、`cacheDir`
只解析一次、`degraded` vs `disabled`）见该文档。

**只需记住一条**：这些端点都在显式路由表里，不写进表会被站点分派当成
`site=library` 去调类。**转码不能拿首次响应判成败**（`state=running` 时 playlist 还空，
必须轮询等分片就绪）。

### 4.10 更新说明（CHANGELOG）：编号 + 一句话

每版必须写成 `1. 2. 3.` 编号、每条一句话（**≤60 字、≤6 条**）。
`sync-packages.ps1` 与 `release.yml` 会自动补序号，但**补不了长句**；
`verify.ps1` 有「CHANGELOG 更新说明格式」机器门禁。维护者明确要求保持这个格式。

### 4.11 站点不一定来自 jar：原生源层与三个判据

`server/host/src/shared/sources/` 是宿主自己实现的源，**不需要 jar**。

- **路由优先级**：`call()` 里先查 `sources.NativeSources.get(site)`，**早于** js / jar
  两个分支。放后面 → 同名站点先撞 `csp_Xxx`（详情页永远空）。
- **`spiderJarPresent()` 恒为 true，不能用它判"装没装 jar"**：
  它是给 JVM 编译用的空实现桩。判"某站点有没有实现"用 `spiderClassPresent(site)`
  （按 `com.github.catvod.spider.<site>` 加载，多实例 key 先去掉 `#n`）。
- **原生源可以"只实现一部分"**：`NativeSource.search` 返回 `null` 表示不实现 →
  有 jar 回退给 jar，没 jar 回空结构，**不能抛 500**。

新增一个原生源：实现 `NativeSource` → 在 `NativeSources.MAP` 注册 → 站点名**必须**
与 `SITES` 里的 key 一致。别在 `healthJson()` 里把 `ok` 写回"必须有 jar"。

### 4.12 纯壳与源包

公开壳仓库**不含** `spider.jar` / dex jar / 真实 `config.sites.json`；构建和 Docker 首启用
`config.sites.example.json`，jar 站由用户导入源包恢复。规则：

- **统一入口 `config.all.json`**：顶层 `spider` 指向 **dex** jar（URL 带 `;md5;`）；
  可选 `spiderJvm` 存 class 形态，只给 JVM 做优化/旧版兼容。
- **JVM（Web / PC / Docker）**：导入期 `Dex2Jvm` 自动把 dex 转 class 并缓存，不需要两套 jar。
  **站点级 dex jar 在挂载阶段也会自动转**（`Dex2Jvm.convertLocal`）——不只是导入流程：
  直接改 `config.sites.json` 或用源服务下发的 dex 同样会转，不转就是 `URLClassLoader`
  加载 dex 失败 →「该源在当前构建不可用」。
- **★ JVM 端必须关 jar 内的字节码校验**（三处启动参数，**删了就全挂**）：
  `-XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote`
  - PC：`server/plugin-manifests/apiserver.manifest.json` 的 `args`
  - Docker：`deploy/docker/Dockerfile.apiserver` 的 ENTRYPOINT
  - fpk：`client/apps/fnos/meowd/src/supervisor.rs`

  原因：外部源包的类多是 dex2jar 转出来的，stackmap 不完整，默认校验直接
  `VerifyError: Expecting a stackmap frame at branch target`。只关"远程类"校验，
  JDK 自带类仍校验。
- **★ Spider 基类 ABI 与 android stub 是 JVM 加载外部 jar 的前提**：
  `crawler/Spider` 必须有 `static Dns safeDns()`（返回非 null，用 `Dns.SYSTEM`）、
  `getActivity()`、`getProxyUrl()`×2、`proxy(Map)`、`siteKey` 字段；
  `android.content.Context` 必须有 `getFilesDir/getCacheDir/getExternalFilesDir`。
  缺任一项会 `IncompatibleClassChangeError` / `NoSuchMethodError` / 静默不可用。
  `Init.context()` 还要在**实例化之前** seed（`ApiServer.seedSpiderInit`）。
- **★ dex2jar 的 `new-instance` 翻译缺陷由 `core.DexJvmFix` 自动修补**：
  d2j 2.4 把 dex 里 `new-instance C` + `invoke-direct {v}, Object.<init>()V` 翻成
  `new java/lang/Object` → JVM 端实例类型错成 Object，调用 `C.f()`/塞进 `C[]` 抛
  `ArrayStoreException: java.lang.Object`。症状是**能搜不能播、且只在 JVM 端**
  （TV 吃 dex 正常）。`Dex2Jvm` 转完会调它，缓存键带 `-fix1`；**改转换链时别把这步删掉**。
- **TV / Fongmi**：`DexClassLoader` 直接吃 dex；站点不写 `jar` 时回退顶层 `spider`，
  写了也必须是 dex；本机执行只导入 `csp_` + jar 的站点。
- **迁移**：导入时传 `replaceSpider=true`（设置页有复选框）。否则本地旧的 `spider`
  优先，jar 站仍然 `ClassNotFoundException`。
- 判"某站有没有实现"用 `spiderClassPresent(site)`，**不要**用 `spiderJarPresent()`
  （stub 恒 true）。
- 源包文件已进 `.gitignore`；不要用 `git add -f` 把它们塞回公开库。

### 4.13 fpk 的两处端口（改之前先读 `client/apps/fnos/README.md`）

1. **网关会丢端口**：fnOS 用 `Host: <NAS>`（无 `:5666`）转发，`baseUrlFor()` 必须优先用
   `X-Forwarded-Host`/`Origin`/`Referer` 的 authority；直接拿 Host 拼 m3u8 分片会打到 80
   端口（登录页 302），播放永远起不来。
2. **jar 的 `Proxy.a()` 只扫 9978–9999**：fpk 的 Java 在 30011，不 pin 就返回
   `http://127.0.0.1:-1/proxy?...`。`Jars.pinProxyPort(port)` 在启动与导入后各调一次。

### 4.14 插件源清单名：`packages.json`

PC 插件源清单**叫 `packages.json`**（`index.json` 是兼容别名），不是 `fnpack.json`
（后者是飞牛应用中心的索引名，`schema_version: 2` + `apps`，两套 schema 不能互喂）。
判据看 `SettingsView.vue` 与 `installer.rs` 的寻址顺序。

### 4.15 端/设备/尺寸：四层判据（★ 改 UI 前先看这里）

**唯一真源**：[site/docs/dev/device-adaptive.md](site/docs/dev/device-adaptive.md)
（§1 判据全景、§5 规范、§8.5 负面清单）。

| 层 | 落点 | 取值 | 代码 |
|---|---|---|---|
| 身份 | `html[data-shell]` | `web` / `tauri` / `tv` / `fpk` | `compat/shell.ts` |
| 形态 | `html[data-form]` | `phone` / `tablet` / `desktop` / `tv-large` | `compat/deviceProfile.ts` `resolveForm` |
| 输入 | `html[data-input]` | `touch` / `key` | `compat/inputModality.ts` |
| 方向 | `html[data-orient]` | `portrait` / `landscape` | `compat/deviceProfile.ts` |

**四条红线**（违反会退回已修 bug）：

1. **形态只影响布局密度**（列数、双栏/胶囊、按钮显隐）；返回键、托盘、更新、系统音量、
   原生播放器一律用**身份或能力**判断（`isTvShell()` / 桥方法存在性）。
2. **`(any-pointer: fine)` 不可独信**：投影仪 Android 9 / Chromium 66 **没接鼠标也报 fine**。
   返回键规则 = TV 壳 + 大屏横屏 + **真的产生过 pointer 事件**才显示；**竖屏 TV 必须显示**
   （`compat/deviceForm.ts` `showPlayerBackButton`，门禁 `tests/mobile-ux-polish.test.ts`）。
3. **不要在组件里自造 `window.innerWidth < N` / `platform === 'tv'` / UA 判断**；
   先用 `formOf()` / `isWideLayout()` / `isBigScreenLandscape()`，样式用 `data-*` 选择器或 `wide:` 变体。
4. **焦点/弹层归 TV 协议**：弹框带 `data-tv-scope`、首项 `data-tv-first`；TV 初始焦点
   必须在**渲染落地后**调 `__meowTvFocus(el, true)`（Vue 重渲染会重写 className，抹掉焦点框）。
   新弹层要 `Teleport to="body"`，否则被 `sticky z-30` 顶栏压住。

新增判据/改这四层后必须跑：`tests/device-profile.test.ts`、`tests/mobile-ux-polish.test.ts`、
`tests/legacy-compat.test.ts`（老内核兜底），并补真机矩阵（投影仪 Chromium 66 + 模拟器 + 手机/PC）。

---

## 五、验收流程

### 5.0 发版：本地随便跑，CI 要先问（★ 红线）

**默认走本地**（`./deploy/scripts/release.ps1` 等四个脚本，不受限、不烧额度）。
CI 一次全量约 **45 分钟额度**（Windows runner 2× 计费），且可能连环失败。

- **不要主动打 tag 触发 CI**，哪怕只是"验证一下能不能过"
- 维护者说"跑一次 CI / 发个版"时才做，做前先说明预计消耗
- 想验流程但不烧额度：用 Actions 页面的 **Run workflow**
- tag 前缀**后必须紧跟数字**（`release-v*` 全量 / `tv-v*` / `pc-v*` / `plugins-v*` /
  `tv-plugins-v*` / `fpk-v*`；**没有 `docker-v*`**，重发镜像走 dispatch）；
  无前缀 `v*` 只是版本标记，零额度；**不要另造 `tv-verify-*` 这类名字**（不触发）

完整契约见 [deploy/README.md](deploy/README.md)。

### 5.1 Web 场景

```bash
cd deploy/docker && docker compose -f docker-compose.build.yml up -d --build  # 改了源码
cd deploy/docker && docker compose up -d                                      # 只验已发布镜像
curl -s localhost:30010/api/health | head -c 200
```

**必须自己跑过再汇报。** 不要把"代码改好了"当结论；实测过的和没实测的分开说。

### 5.2 浏览器实测（涉及播放/弹幕时必做）

```bash
cd client/apps/web && npx vite --port 5181 --strictPort
```

- **不要杀 5173/5174 上的进程**——别的会话可能在用
- 大截图会撑爆上下文，改用 DOM 文本/类名读取，或压成 JPEG
- 用真实鼠标事件验证点击类问题；合成 `click` 常打不到正确 target
- 验证"按住键"类 bug：合成 `KeyboardEvent` 设 `repeat:true`，每 50ms 一次
- 弹幕状态读 `window.__meowDm`；播放诊断 `window.__playerDebug`（播放页按 `D`）
- **起了浏览器必须能收尾**：`--user-data-dir` 指到 `tmp/` 空目录，加
  `--disable-extensions --disable-component-extensions-with-background-pages`，
  一次启动多个 page，收尾 `taskkill /PID <pid> /T /F`。
  Python 脚本用 `smoke/edge_session.py` 的 `EdgeSession`（with 块进出，自动 `/T` 收尾并删 profile）——
  并行会话用不同 prefix，它只杀自己的 PID 树。
  **不要往系统里注册计划任务/服务**——收尾责任在启动浏览器的那个脚本。

### 5.3 PC / TV 冒烟

```bash
python smoke/meow_mcp.py probe
python smoke/meow_mcp.py call pc_eval --target pc --args '{"expression":"location.hash"}'
python smoke/meow_mcp.py call tv_health --target tv --host 127.0.0.1 --port 19777 --token <token>
```

- **两端同一套协议**：PC `pc_*` + TV `tv_*`，都走 `POST /mcp` 的 JSON-RPC。
  TV 多一层 `X-Meow-Token`（外层统一校验，MCP 不能绕过）。
- PC 的 MCP 端口不写在 `~/.zcode/meow-mcp.json` 里，要在 9339–9346 探测 `/health`
  看 `server == "meow-player"`。该文件只留 pid / version / cdp_port，
  而 **pid 是上次运行的，不能据此判断"还活着"**。
- 旧版 `/health` 没有 `server` 字段，客户端要兼容 `cdp_ready` + `plugins` 组合判据。
- PC MCP 是应用内的线程：**打开应用才有，没有独立进程可重启**。
- TV 的接口在 `DebugServer`，只在主界面（有 WebView）时随 `onResume` 启动；
  配置页没有 WebView 所以不起服务；release 包要人工点「远程调试」并读屏幕令牌。
- 模拟器/投影仪没有可路由 IP 时走 `adb forward tcp:19777 tcp:9977`；
  模拟器开「桥接模式」后会拿到局域网 IP，直接连即可。
- **★「检查更新」转圈/失败的先查出口**：PC 走的是 OpenClash 那台网关（fake-ip
  `198.18.x`），MuMu 桥接后走 DHCP 给的普通网关 → 解析出真实 IP、443 被墙。
  对齐网关与 DNS 即可：`./smoke/mumu-net.ps1`（**模拟器重启后要重跑**）；
  判据是设备上 `ping github.com` 得到 `198.18.x`。
- **每步断言前置状态**；`pc_wait` / `tv_wait` 超时不中断脚本。
- `readyState: 0` 是"刚创建"**不等于失败**，`≥3` 才就绪。
- CDP 会假死，`cdp_ready:false` 就重启应用。

**本机常用测试设备**：

| 设备 | 用途 |
|---|---|
| `.76` | MuMu TV，点播/片库回归 |
| `.77` | MuMu ARM64，aiwex Guard 源验证 |
| `.78` | Android 15 MuMu，电源动作/原生播放；**无 root** |

汇报分三档：✅ 正常 / ⚠️ 源问题 / ❌ 应用问题。

---

## 六、这个用户要什么

来自真实纠正，违反会被打回：

1. **建议必须编号**——他按编号点单（"1、3、4、7 实现一下"），没点的留在"仍未做"
2. **感官问题要量化**——不说"优化了"，说 fps / 长帧数 / 冷热耗时
3. **反馈要出现在用户正在看的地方**——改播放位置要在画面中央提示，不能只在底栏
4. **浮层几何锚定触发按钮**——不是固定角落；"左边按钮左边弹"要的是贴合
5. **控件要么有真实行为要么删掉**——留个开关却没代码读它，会被一眼看穿
6. **要"够不够用"的判断，不要工具清单**——分"够用/不够用"给结论和证据
7. **他报 bug 时给的假设是待验证假设**——他的渲染直觉经常是对的，逐条给结论
8. **"昨天还好用"意味着先查自己**——先怀疑自己改坏了（启动类/编译产物/参数）
9. **断言"不存在"前搜遍全部同类目录**——只查一个子包就下结论是错的（踩过）
10. **协议/许可证不作为否决理由**——标风险即可，由他决定
11. **接入方式要并存不要替他选**——两种都要留
12. **先给数据再开写**——涉及包体/架构先量体积给方案，不要直接动手

---

## 七、文档地图（单一真源 = 站点 `site/`）

**技术文档正文在 [site/docs/dev/](site/docs/dev/)**，用户操作在
`site/docs/guide|ecosystem|reference/`；`docs/00-目录索引.md` 只是指路文件。
**改文档只改 `site/` 一处**，不要另抄一份。

> **引用代码的写法**：`site/docs/dev/` 引用代码一律写「文件 + 符号名」
> （如 `ApiServer.java` 的 `call()`），行号最多作括号内的辅助提示；**新增或修改引用不要写裸行号**。
> 行号是绝对坐标，代码一改就漂；需要定位就给 `grep` 命令。详见 [site/README.md](site/README.md)「引用代码的写法」。

| 想干什么 | 看哪份 |
|---|---|
| 技术文档总索引 | [site/docs/dev/index.md](site/docs/dev/index.md) |
| **端/设备/尺寸判据（四层 + 红线）** | [site/docs/dev/device-adaptive.md](site/docs/dev/device-adaptive.md)（改 UI/返回键/焦点/布局显隐必读） |
| 理解架构 / 改接口 / 改前端 / 接 TV | `site/docs/dev/{architecture,api-contract,frontend,tv-integration}.md` |
| iOS 壳 / IPA 发版（形态、签名、CI） | `site/docs/dev/ios-integration.md` |
| 配置/源包格式 / 插件系统 / 数据状态 | `site/docs/dev/{config-system,plugin-system,data-state}.md` |
| Docker 部署 / 构建发版 / 测试验收 | `site/docs/dev/{docker,build-release,testing}.md` + [deploy/README.md](deploy/README.md) |
| 播放转码 / 弹幕 / 直播 / 片库缓存 / AI | `site/docs/dev/{playback,danmaku,live,library-cache,ai-assistant}.md` |
| **★ 部署与发版（唯一操作手册）** | [deploy/README.md](deploy/README.md) |
| **★ 让客户端收到更新** | [deploy/README.md](deploy/README.md)：清单 + `CHANGELOG.md` + 必须点 Publish |
| TV 签名（红线） | `site/docs/dev/tv-integration.md` 的签名小节 |
| 改功能要同步哪些站点页 | `site/README.md` 的两张表（同步清单 + 硬事实核对） |

> 维护口径：操作层单一真源 = 站点 `guide/`；设计/实测层 = `site/docs/dev/`；
> 技术文档**不写死「当前最新版本号」**（要写版本就写兼容性门槛），默认值指向 compose / package.json 真源。
