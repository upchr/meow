# Docker 构建与部署手册

> **面向**：自己部署 Meow 后端、换机器重建、排障的人。**代码位置**：`deploy/docker/`
> 下的三个 Dockerfile、五个 compose 文件、`nginx.conf.template` 和 `.env.example`。
> 弹幕用官方镜像，没有自己的 Dockerfile；源服务镜像只在私仓构建。
> **公开壳不带 spider.jar**：镜像播种 `config.sites.example.json`（`sites` / `lives` /
> `parses` 都是空数组），jar 站由用户导入源包恢复。统一入口 `config.all.json`，dex
> 为主；Fongmi/TV 直接加载，JVM 由 `Dex2Jvm` 转换（导入期 `rewrite` + 挂载前
> `convertLocal`）。规则见
> [`deploy/source-pack/`](https://github.com/upchr/meow/blob/main/deploy/source-pack/README.md)，
> 部署见 [`SOURCE_PACK.md`](https://github.com/upchr/meow/blob/main/deploy/SOURCE_PACK.md)。
> **源服务**不在主 compose：`.yml` / `.build.yml` 不含 sources，`.with-sources.yml`
> 是主栈 + sources，`.sources.yml` 只起源服务。镜像 `chrplus/meow-sources:latest` 自带
> 主爬虫 + Jable + 54 站默认源，`Dockerfile.sources` 等只在私仓维护。

> **★ 所有 `docker compose` 命令都要先在 `deploy/docker/` 下执行**：`context: ../..`
> 与 `volumes: ../../data` 都相对该目录，在仓库根跑会报
> `open ...\docker-compose.yml: The system cannot find the file specified`。

---

## 一、部署形态：五个容器

```
   浏览器 ──30010──▶ web（nginx + 静态文件，反代 /api）
                        │ http://apiserver:9979
   PC / TV ──30011──▶ apiserver（JRE + spider + ffmpeg，/data 持久化）
                        ├── http://drpy:9330        drpy（Node 运行时，.js）
                        ├── http://pyrt:19331       pyrt（Python 运行时，.py）
                        └── http://danmu_api:9321   danmu_api（官方镜像）
```

| 容器 | 来源 | 内容 | 宿主端口（默认） |
|---|---|---|---|
| `web` | `Dockerfile.web` 本地构建 | nginx 1.27 + 前端构建产物 | `0.0.0.0:30010` |
| `apiserver` | `Dockerfile.apiserver` 本地构建 | JRE 17 + `classes/` + `libs/` + ffmpeg（+ curl） | `127.0.0.1:30011` |
| `drpy` | `Dockerfile.drpy` 本地构建 | Node 22，跑 `.js` / drpy 源 | `0.0.0.0:30013` → 9330 |
| `pyrt` | `chrplus/meow-pyrt` 拉取 | CPython 3.12 + pyrt，跑 `.py` 源（独立版本线） | `0.0.0.0:30014` → 19331 |
| `danmu_api` | `logvar/danmu-api:1.21.6` | 上游 Node 服务 | `127.0.0.1:30012` |

带源形态多第六个容器 `meow-sources`（nginx，宿主 `18080`，自带主爬虫 + Jable +
54 站默认源），见 §3.3。

**`drpy` 为什么不能省**：配置里 200+ 个源是 drpy/`.js`，没有这个运行时它们会被判
“不可用”（`apiserver` 通过 `MEOW_JS_RUNTIME` 指向它）。`DRPY_STRIP_REMOTE=1` 默认开：
drpy2 会纯副作用地 import 第三方 CDN 脚本，直接执行等于引入供应链风险。

**`pyrt` 为什么存在**：`.py` 源由它执行。1.3.0 起分层路由：`.js` → `MEOW_JS_RUNTIME`
（drpy），`.py` → `MEOW_PY_RUNTIME`（pyrt；未配回落 jsRuntime，兼容老的单地址部署）。
pyrt 在全部四个主体 compose（含 `standalone` / `with-sources`）里都有。

**`apiserver` 为什么带 ffmpeg**：浏览器内核没有 HEVC 解码器，NAS 上的 MKV（H.265）
在 Web 端放不了；带 ffmpeg 才能按需转 HLS，代价是镜像大一个量级。`Transcode.ffmpegPath()`
的查找顺序是系统属性 > `MEOW_FFMPEG` > `policy.json` > `PATH`，镜像里显式设了
`MEOW_FFMPEG=/usr/bin/ffmpeg`。

拆开的理由：可只跑后端（给 TV / 别的 PC 用）、可只跑前端（`MEOW_APISERVER` 指向远端）、
互不拖累（后端崩了前端不 unhealthy）。弹幕用官方镜像是因为上游源码不在本仓库
（`research/` 不入 git），自建会让使用者 clone 后起不来；版本固定 `1.21.6` 而非
`latest`，升级只改 `DANMU_API_IMAGE`。

### 1.1 四条不能改的架构约束（改了就坏）

**① `web` 与 `apiserver` 之间刻意没有 `depends_on`**：加了之后
`docker compose up -d web` 会把后端一起拉起来，就失去“只起前端连远端后端”的能力；
后端缺席时 web 也能起来正是想要的。

**② nginx 上游必须用变量，不能写死地址**：

```nginx
set $meow_backend "${MEOW_APISERVER}";
resolver 127.0.0.11 valid=5s ipv6=off;
proxy_pass http://$meow_backend;
```

写死字面地址时 nginx 在**启动时**解析上游，apiserver 不在就整个 web 容器起不来。
用变量则延迟到请求时，后端缺席只让 `/api` 返 503；`resolver` 的 5 秒 TTL 让后端
重启后自动恢复，不必重启 web。

**③ 停单个服务用 `stop/start`，不要用 `down <服务名>`**：`down` 是“拆整个项目”语义，
会删掉共享的 `meow` 网络，让其他活着的容器失去网络（§6.1）。

**④ 可置空的变量必须用 `${VAR-默认}`，不能写成 `${VAR:-默认}`**：后者把空串也当
“未设置”，所以 `MEOW_DANMAKU_API=` 禁用弹幕会失效。`MEOW_JS_RUNTIME`、
`MEOW_PY_RUNTIME`、`MEOW_DANMAKU_API`、`MEOW_AUTH_TOKEN`、`MEOW_CORS` 五项用 `-`；
端口、镜像 tag 仍用 `:-`。

---

## 二、前置条件

| 项 | 要求 | 检查 |
|---|---|---|
| Docker Engine | ≥ 20.10（要 BuildKit） | `docker version` |
| Docker Compose | **v2**（`docker compose`） | `docker compose version` |
| 磁盘 | 镜像含 JRE、ffmpeg 和两个 Node 运行时，留数 GB | `docker system df` |
| 内存 | JVM 上限 512MB，另有两个 Node 进程 | `docker info \| grep Memory` |

Windows 用 Docker Desktop 即可；**宿主机不需要 JDK / Node**，编译与前端构建都在镜像里完成。

| 路径 | 说明 | 入库 |
|---|---|---|
| `server/host/src/` | 后端 Java 源码，镜像里编译 | ✅ |
| `server/host/libs/` | **10 个公开依赖**：`android-json`、`asn-one-0.6.0`、`bcprov`、`gson-2.10.1`、`jsoup-1.15.3`、`mbassador-1.3.0`、`okhttp-3.14.9`、`okio-1.17.2`、`slf4j-api-1.7.36`、`smbj-0.13.0`。`spider.jar` **不入库** | ✅ |
| `server/host/config.sites.example.json` | 公开壳 seed，空站点表 | ✅ |
| `server/build/dist/.gitkeep` | 保证 `COPY server/build/dist/` 的源目录永远存在 | ✅ |
| `client/apps/web/` | 前端源码 | ✅ |

---

## 三、编排与构建

| 文件（都在 `deploy/docker/`） | 形态 | 容器数 | 镜像来源 |
|---|---|---|---|
| `docker-compose.yml` | **主体直接启动**（发布镜像） | 5 | 从 Registry 拉 |
| `docker-compose.build.yml` | **主体构建**（本地源码） | 5 | 本地构建 |
| `docker-compose.with-sources.yml` | **带源主体启动**：主栈 + 源服务 | 6 | 从 Registry 拉 |
| `docker-compose.sources.yml` | **源服务独立启动** | 1 | 从 Registry 拉 |
| `docker-compose.standalone.yml` | **独立编排（下载即用，不 clone 仓库）** | 5 | 从 Registry 拉（用法见 [Web / Docker](/guide/web-docker)） |

前两个**完整且独立，二选一，不是叠加**：端口、环境变量、卷、网络、`name: meow`、
`container_name` 完全一致，唯一差别是后者多了 `build:` 段，切换时接管同一组容器。
drpy 的 tag 来源不同——`docker-compose.yml` 用 `${DRPY_VERSION:-0.9.26}`，`.build.yml`；
pyrt 拉取型默认 `${PYRT_VERSION:-latest}`（build.yml 钉 `0.1.4`）。
默认用 `${MEOW_VERSION}` 给本地产物打 tag；要跨两个文件复用同一个 drpy 镜像，在
`.env` 里显式设 `DRPY_IMAGE`。

### 3.1 用发布镜像 / 升级 / 离线升级

首次部署：`cd deploy/docker && cp .env.example .env && docker compose up -d`。

默认镜像 `chrplus/meow-{web,apiserver}:${MEOW_VERSION:-latest}`（amd64 + arm64 双架构）。
**拉取型 compose 的默认 tag 是 `latest`**：新机器 `up -d` 直接拿到最新发布，不依赖发版状态；
要可复现/回滚就在 `.env` 钉具体版本。只有 `docker-compose.build.yml` 的兜底 tag 仍与
`client/package.json` 同步（本地构建产物打 tag），由 `deploy/scripts/bump-version.ps1` 维护。

**升级**必须显式 pull，否则 `up -d` 发现本地已有镜像就不再拉，静默跑旧版：

```bash
docker compose pull web apiserver && docker compose up -d
curl -s localhost:30011/api/health | grep -o '"version":"[^"]*"'
```

在 `.env` 钉了具体版本的，先把 `MEOW_VERSION` 改成新 tag 再 pull。

**判断升级成功只看 `/api/health` 的 `version`**，不要看镜像 tag。**离线升级**（NAS 拉
不动 Docker Hub，报 `short read: expected N bytes but got M`）：在有网机器上
`docker buildx build --platform linux/arm64` 构建目标架构，`docker save` + `gzip` 后
`scp` 到目标机 `docker load`。**★ 离线机要先把 `.env` 的 `MEOW_VERSION` 钉成你 load 进去的 tag**（默认 `latest` 在离线机上不会自己更新）；`docker save` 的 tag 要与它一致，漏打表现为“拉取失败、容器起不来”。

### 3.1.1 ★ 三个“服务在跑但搜不到”的环境坑

| 症状 | 根因 | 修法 |
|---|---|---|
| danmu UI 局域网访问不通，容器内 `127.0.0.1:30012` 正常 | `DANMU_BIND=127.0.0.1` 只绑本机 | 改 `DANMU_BIND=0.0.0.0` 后重建 |
| 脚本源大面积失败或消失，搜索第 2 次起全空 | `meow-drpy` 不可用，但 `MEOW_JS_RUNTIME` 仍指向它 | `docker restart meow-drpy`；确认容器能访问规则托管站 |
| 单站正常，一聚合就 `UnknownHostException: Temporary failure in name resolution` | 并发解析压垮 docker 内嵌 DNS（`127.0.0.11` 转发排队超时） | 偶发可忽略；持续失败给 apiserver 加 `dns:` 指向公共 DNS |

第二个最容易误判：`/api/health` 返回 `{"ok":true}`（容器只是忙），但搜索全卡住；
判据与恢复机制见 §8.4。

### 3.2 从源码构建与单服务重建

```bash
cd deploy/docker && docker compose -f docker-compose.build.yml up -d --build
docker compose -f docker-compose.build.yml build web && docker compose up -d web    # 单个
docker compose -f docker-compose.build.yml build --no-cache                         # 彻底重建
```

产物按 image 名打 tag，之后改用 `docker-compose.yml` 启动会命中本地镜像。

> **★ 预编译 class 目录 `deploy/docker/prebuilt-classes/`**：CI 会从 apiserver job
> 下载 class artifact 填进去，镜像构建阶段优先复用（`.class` 与 CPU 架构无关，省掉
> arm64 在 QEMU 里再跑一遍 `javac`）；目录为空（本地构建 / 单独 dispatch）时回退
> 镜像内编译。**本地构建前若该目录有残留 class 会被优先复用，需先清空**——构建日志
> 只回声「使用预编译 class：N 个」，不比对时间戳。

> **★ `Dockerfile.web` 的多阶段构建必须把“容器内构建”放最后**：不给 `--target` 时
> Docker 取**最后一个 stage**。把“用宿主 dist”的 stage 写在最后，`docker compose
> build` 会静默走那条路，修改前端源码不生效。

网络受限、容器拉不到 `registry.npmjs.org` 时用宿主产物（与架构无关）：先
`cd client && pnpm install && pnpm --filter @meow/web build`，再
`docker build -f Dockerfile.web --target runtime-dist ../..`。

> `--build-arg INSTALL_TOOLS=false` 会连 `curl` 一起跳过，而 apiserver 的
> `HEALTHCHECK` 用 curl；只有换用自带 curl 的 `RUNTIME_BASE` 时才关得掉。

context 都是**仓库根目录**，靠 `.dockerignore` 裁剪：**`**/node_modules/` 必须排除**
（否则 `COPY` 用本地依赖覆盖容器里刚装好的，报
`ERR_MODULE_NOT_FOUND: @vitejs/plugin-vue`）；**`server/build/*` 整目录排除，再单独
放行 `dist/` 与 `dist/*.jar`**——写成 `server/build/`（排除目录本身）会让 Docker 不再
向下遍历，`!server/build/dist/` 全部失效，镜像里永远没有 dex jar；也别放行整个
`dist/`，里面有几十 MB 中间产物；**排除 `data/` 与 `.env`**。

compose 不做 manifest list，要发布多架构镜像用 `build-push-docker.ps1`（§七）。

### 3.3 源服务：带源主体与独立源服务

两种源形态共用同一镜像 `chrplus/meow-sources:latest`（**独立版本线**，不跟
`MEOW_VERSION` 走）：自带主爬虫 + Jable 扩展 + 54 站默认源，**不挂载即可用**；
`MEOW_SOURCES_DIR` 指到自己的完整源包目录时整目录只读覆盖镜像自带的源（挂到
`/mounted-sources`）。默认 `./sources` 是空目录——空目录 = 用镜像自带的源。

**起容器 ≠ 导入源**：没有任何首启自动导入。容器起来后要在设置页手动填
`http://<宿主IP>:18080/config.all.json`（TV / Fongmi 也在配置地址填这个）。

**★ `MEOW_SOURCES_BASE` 默认是占位值，必须按导入方改**：内置源生成的 jar 下载
绝对地址由它拼出，默认 `http://127.0.0.1:18080` 只在「导入方与源服务同主机」时
成立——apiserver 容器去拉时 127.0.0.1 指向容器自己，症状是「站点导进来但 jar
下载失败」：

| 谁导入 | `MEOW_SOURCES_BASE` 填 |
|---|---|
| 只有 Docker 主栈的 apiserver（同 `meow` 网络） | `http://meow-sources:18080`（容器名可解析） |
| TV / Fongmi / 局域网其他机器也要导入 | 宿主局域网地址，如 `http://192.168.1.20:18080` |

两种形态的边界：

| 形态 | 启动 | 特点 |
|---|---|---|
| 带源主体启动 | `docker compose -f docker-compose.with-sources.yml up -d` | 五容器同在 `meow` 网络，`name: meow` 与主栈同项目——共享 `.env` 与 `data/` |
| 源服务独立启动 | `docker compose -f docker-compose.sources.yml up -d` | 单容器、**独立项目 `meow-sources`**，不在 `meow` 网络里，主栈容器解析不到它；给局域网设备当源分发点（默认绑 `0.0.0.0:18080`） |

源镜像的构建（`Dockerfile.sources` 等）在私仓维护，公开库只有 compose 与镜像；
详见 [`SOURCE_PACK.md`](https://github.com/upchr/meow/blob/main/deploy/SOURCE_PACK.md)。

---

## 四、配置：`.env` 与 compose 的分工

### 4.1 谁读什么

| 谁 | 实际读什么 |
|---|---|
| `docker compose` | **`deploy/docker/.env`**（compose 文件所在目录的同名 `.env`） |
| 后端 Java | **系统环境变量**（不解析 .env 文件） |
| danmu_api | 容器内 `/app/config/.env`（宿主 `data/danmu/.env`） |
| 前端 | 编译期 `VITE_API_BASE`（容器部署不用，nginx 反代同源） |

这个 `.env` 不是“应用的配置文件”，是 **compose 的变量覆盖层**；它不入库
（`.gitignore` 的 `.env` 规则），模板是同目录的 `.env.example`。默认值都在 compose
里（`${VAR:-默认}`），`.env` 只写要改的项；查生效值用 `docker compose config`。

> **语法坑**：`${VAR-默认}` 与 `${VAR:-默认}` 不同，后者把空串也当“未设置”，所以
> “置空禁用”会失效。本项目只对四个可置空变量用 `-`（§1.1 ④）。

### 4.2 变量全表

| 变量 | 默认 | 组 | 什么时候改 |
|---|---|---|---|
| `MEOW_VERSION` | `latest` | A | 默认跟随最新发布；要固定版本才填（`build.yml` 的兜底 tag 仍随发版） |
| `DRPY_VERSION` | compose 兜底值 | A | drpy tag，**独立版本线** |
| `WEB_IMAGE` / `APISERVER_IMAGE` | `chrplus/meow-*:${MEOW_VERSION:-latest}` | A | 换自己的仓库 |
| `DRPY_IMAGE` | `chrplus/meow-drpy:${DRPY_VERSION}` | A | 跨两个 compose 共用时钉死 |
| `DANMU_API_IMAGE` | `logvar/danmu-api:1.21.6` | A | 升级弹幕服务 |
| `WEB_PORT` / `APISERVER_PORT` / `DANMU_API_PORT` / `DRPY_PORT` | `30010` / `30011` / `30012` / `30013` | A | 端口冲突 |
| `APISERVER_BIND` / `DANMU_BIND` | `127.0.0.1` | A | 给 TV / 局域网直连改 `0.0.0.0`；danmu 同理 |
| `MEOW_APISERVER` | `http://apiserver:9979` | B | web 连外部后端时改绝对地址 |
| `MEOW_JS_RUNTIME` / `MEOW_PY_RUNTIME` / `MEOW_DANMAKU_API` | `http://drpy:9330` / `http://pyrt:19331` / `http://danmu_api:9321` | B | 不启动对应服务时置空 |
| `MEOW_AUTH_TOKEN` | 空（关闭鉴权） | B | 后端不只监听本机时设 |
| `MEOW_CORS` | `*` | B | 公网暴露时收紧（§9.1） |
| `DANMU_SOURCE_ORDER` | 8 个来源 | B | 不配只有 3 个来源 |
| `APISERVER_JAVA_OPTS` | `-Xms64m -Xmx512m …` | B | 小内存机器降 `-Xmx256m`；**别删 ENTRYPOINT 内置的关校验参数**（外部源包 stackmap 不完整，缺了全站 `VerifyError`，见 `deploy/SOURCE_PACK.md`） |
| `MEOW_SOURCES_IMAGE` | `chrplus/meow-sources:latest` | A | 源服务镜像 tag，**独立版本线**；仅 sources / with-sources 读 |
| `MEOW_SOURCES_PORT` / `MEOW_SOURCES_BIND` | `18080` / `0.0.0.0` | A | 端口冲突 / 收回本机 |
| `MEOW_SOURCES_DIR` | `./sources`（空目录） | A | 指到完整源包目录时覆盖镜像自带的源 |
| `MEOW_SOURCES_BASE` | `http://127.0.0.1:18080`（占位） | B | **按导入方改**，见 §3.3 |

A 组只在宿主生效（镜像 tag、端口、绑定地址），B 组会传进容器。**★ `DRPY_VERSION` 不能
跟 `MEOW_VERSION` 走**：drpy 只在主版本发版时顺带重打，中间的主版本号在远端不存在。跟着
走会让**新机器 / 清过镜像的机器** `up` 时报 `manifest unknown`；老机器因本地已有旧镜像
照常能跑，开发机上基本发现不了。默认值必须是远端真实存在的 tag；升级 drpy 要同时改
compose 兜底与 `.env.example`。容器内的 `MEOW_DATA=/data`、`MEOW_PORT=9979`、
`DANMU_API_PORT=9321`、`DRPY_PORT=9330` 是固定值，不暴露给 `.env`。

### 4.3 四种典型配置

**A 默认**：`up -d` 后开 `http://localhost:30010`。**B 局域网**：`APISERVER_BIND=0.0.0.0`
后 `up -d apiserver`（**必须重建**，`restart` 不重读环境变量）。**C 只后端**：`up -d
apiserver danmu_api`。**D 只前端**：`MEOW_APISERVER` 指向远端后 `up -d web`
（`host.docker.internal:30011` 也可）。

### 4.4 数据目录

| 宿主路径 | 容器路径 | 内容 | 删了会怎样 |
|---|---|---|---|
| `./data/` | `/data` | `policy.json`（弹幕/DNS/WebDAV/令牌）、`config.sites.json`、`jars/converted/`、缓存 | 设置全丢；配置由 ENTRYPOINT 重新播种成空壳 |
| `./data/danmu/` | `/app/config` | `.env`（首次由上游镜像生成） | 回到默认配置，UI 令牌重置 |

两者都是 **bind mount**，容器重建不影响。备份 `tar czf meow-data-$(date +%F).tar.gz data/`；
恢复先 `docker compose down`，解包后再 `up -d`。

### 4.5 导入源包 / 换 spider jar

公开壳**不含** `spider.jar`，推荐在应用内导入：

1. 源包放在容器**可达**的地址（NAS / 宿主 LAN IP / `host.docker.internal`）。
   **不能写 `127.0.0.1`**：容器里的回环是容器自己，会报 `Connection refused`。
2. 设置 →「配置源」填 **`config.all.json`**（顶层 `spider` 是 dex jar，带 `;md5;`），
   勾选「用这份配置的顶层 spider 覆盖本地」（API 传 `replaceSpider:true`）。
3. JVM 没有 `spiderJvm` 时，`Dex2Jvm` 在导入期把 dex 转成 class 并缓存到
   `/data/jars/converted/`。升级后旧配置里的 dex 站点需**重新导入一次**触发转换。

私有全量构建要内置 class jar：放到 `server/host/libs/spider.jar` 再 build，或用
compose 里那段注释掉的 bind mount。**文件必须存在**——Docker 遇到不存在的 bind 源
会创建空目录，`/app/libs/spider.jar` 变目录 → `ClassNotFoundException`、容器 unhealthy。

### 4.6 写操作令牌（`MEOW_AUTH_TOKEN`）

**保护所有改状态的端点**，GET/HEAD 永不拦——搜索、详情、播放、首页不受影响。受保护：
`/api/state/*`、`/api/policy`、`/api/webdav`、`/api/config/import`、`/api/library*`、
`/api/cache*`、`/api/transcode*`、`/api/health/*`、`/api/lives`，以及清理与代发类
`/api/logs`、`/api/posters`、`/api/scrape`、`/api/search/cache`、`/api/llm/relay`、
`/api/cast`。

**什么时候需要设**：后端不只监听 `127.0.0.1` 时。典型是把本机当家庭共享后端
（`APISERVER_BIND=0.0.0.0`），同一 WiFi 下任何人都能访问 30011——不设令牌，别人能
清空收藏、删缓存、通过片库删 NAS 文件。**只监听 127.0.0.1 的单机自用不需要设**。

```bash
# .env
MEOW_AUTH_TOKEN=换成你自己的长随机串
docker compose up -d --force-recreate apiserver
```

**生效判据**（不回显令牌）：`curl -s http://127.0.0.1:30011/api/health | grep auth`
应看到 `"authRequired": true`、`"authSource": "env|policy|none"`；`authSource` 能区分
“环境变量生效了”和“还在读 policy.json”。**客户端要填同一个值**：设置 → 连接 → 写操作
令牌，只存本机 localStorage（`meow.authToken`），不参与跨端同步、不进备份。**读取
优先级**：`MEOW_AUTH_TOKEN` → `policy.json` 的 `authToken`，两处都空才关闭。忘了令牌：
改 `.env` 重启，或 `docker exec` 把 `/data/policy.json` 写成 `{"authToken":""}` 再
`restart`。

> **★ 开了鉴权后 `/api/policy` 自己也在保护列表里**，改策略要带令牌，容易把自己锁在
> 门外——`authSource` 就是为排查这个加的。

---

## 五、启动与验证

`cd deploy/docker && docker compose up -d && docker compose ps`，期望四个容器都是
`Up (healthy)`；`start_period` 不同，刚起时 `starting` 正常：`web` 10s（`/healthz`）、
`apiserver` 40s（`/api/health` 含 `"ok":true`）、`drpy` 10s（`/health`）、
`danmu_api` 20s（`/`）。少了 `meow-drpy` 未必立刻报错——`/api/health` 的 `ok` 不看它，
表现是 `.js`/drpy 源静默消失（§8.4）。

| # | 命令 | 期望 |
|---|---|---|
| 1 | `curl -s localhost:30010/healthz` | `{"ok":true,"role":"web"}` |
| 2 | `curl -s localhost:30010/api/health`（及直连 30011） | `"ok":true`、`"version"` 与预期一致，两处一致 |
| 3 | 浏览器开 `http://localhost:30010` 并搜索任意片名 | 首页出海报，搜索出结果并带源标签 |
| 4 | `curl -s "localhost:30012/87654321/api/config"` | JSON，`SOURCE_ORDER` 8 项 |

`capabilities` 固定 7 项（`danmaku`/`search`/`proxy`/`webdav`/`dns`/`state`/`parse`），
配了聚合弹幕时前面多一项 `danmakuExternal`。**公开壳的 `sites` 会很小**：
`siteImplemented()` 会过滤掉“有名字但没有实现”的站——没有 spider.jar 时只有编译进
后端的原生源（当前是豆瓣）算实现；源码静态基线（`SITES.put(...)` 几十条）是历史
内置源名，不是对外可见数量。**确认版本**只看 `/api/health` 的 `version`（本机还跑着旧后端时 Vite/nginx
会静默连过去）；经 web 与直连 30011 两个值必须一致。

---

## 六、日常操作

### 6.1 启停（★ 别用 `down <服务名>`）与日志

```bash
docker compose stop web       && docker compose start web
docker compose stop apiserver && docker compose start apiserver
docker compose logs -f apiserver
```

**不要用 `docker compose down web`**：`down` 是“拆整个项目”语义，会把共享的 `meow`
网络一起删掉，另外两个活着的容器立即失去网络
（`failed to set up container networking: network ... not found`）。之后 `up` 也起不来，
必须整体 `down` 重建。删单个容器用 `docker compose rm -sf web`。

启动成功的关键日志：`[api] multi-instance sites: N`、`[api] <version> listening on
http://127.0.0.1:9979`、`[api] sites: N | ext: M`、`[api] data: /data`、
`[api] danmaku api: configured|not configured`、`[api] spider jar: ... | NOT FOUND`。
`multi-instance sites` 是 `SITES` 的**原始条数**（含静态基线几十条），`sites` 是
`siteImplemented()` 过滤后的对外数量；两个数不一样正常，排查“站点变少”看后者。
公开壳打 `spider jar: NOT FOUND` 是预期行为，不代表 unhealthy。

### 6.2 升级 / 回滚 / 卸载

```bash
docker compose build web       && docker compose up -d web       # 改了前端
docker compose build apiserver && docker compose up -d apiserver # 改了后端
docker compose up -d            # 回滚：.env 钉回旧 tag，镜像还在时不重建
docker compose down             # 卸载（data/ 保留；rm -rf data/ 才删配置）
```

旧镜像会留着。清理：`docker image prune -f`、`docker rmi chrplus/meow-web:<旧版本>`、`docker builder prune -f`。

---

## 七、ARM 架构与多架构镜像发布

| 镜像 | 基础镜像 | arm64 |
|---|---|---|
| `web` | `node:22-alpine` + `nginx:1.27-alpine` | ✅ |
| `apiserver` | `eclipse-temurin:17-jdk` / `17-jre` | ✅ |
| `drpy` | `node:22-alpine` | ✅ |
| `danmu_api` | `logvar/danmu-api` | 官方原生双架构 |

前端唯一的原生模块是 `sharp`，只用于图标生成（`client/brand/build-icons.mjs`），不在
Web 构建链路上。普通 `docker build` 只产出**当前机器架构**的镜像，拷到树莓派 / M 系列
Mac 上报 `exec format error`；要让一条 `docker pull` 两种架构都能用，必须构建**多架构
清单**（manifest list / OCI image index）。

```powershell
docker login
# 演练：只构建不推送；去掉 -DryRun 即推送 web / apiserver / drpy 三个镜像
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/scripts/build-push-docker.ps1 `
  -User yourname -DryRun
```

| 参数 | 作用 |
|---|---|
| `-User` | **必填**，Docker Hub 用户名（或 `registry.example.com/ns`） |
| `-RepoPrefix` / `-Platforms` | 默认 `meow-`（→ `meow-web`）/ `linux/amd64,linux/arm64` |
| `-Version` / `-DrpyVersion` | 默认读 `client/package.json` / `.env.example` 的 `DRPY_VERSION` |
| `-NoLatest` / `-DryRun` / `-SkipBuild` | 不打 latest / 只构建 / 直接重推 |
| `-WebFromDist` / `-Builder` / `-Cache` | 用宿主 dist / buildx builder 名 / 缓存后端（CI 传 `gha`） |

前置：`docker buildx`、QEMU 模拟器（缺失时脚本自动 `tonistiigi/binfmt --install arm64`）、
Docker Hub 账号，并在 Hub 上手动创建三个仓库。用别人发布的镜像：`.env` 设
`WEB_IMAGE` / `APISERVER_IMAGE` 后 `docker compose up -d`（不带 `--build`）。
`docker buildx imagetools inspect yourname/meow-web:<版本>` 应看到两行 `Platform:`
（`linux/amd64`、`linux/arm64`）；只有一行说明没用 buildx。

---

## 八、排障

### 8.1 `/api` 返 503，首页正常

**这不是故障**，是“后端没起”或“后端地址不对”。返回体是刻意设计的中文提示：
`{"code":-1,"msg":"apiserver 未连接：请启动 apiserver 容器，或把 MEOW_APISERVER 指向可用的后端地址"}`。
先 `docker compose ps` 看 apiserver 是否 healthy，再 `docker compose logs apiserver`、
直连 `curl -s localhost:30011/api/health`，最后查 `MEOW_APISERVER`。

> Git Bash 会把 `/etc/nginx/...` 改写成 Windows 路径，查容器内文件必须加
> `MSYS_NO_PATHCONV=1`；PowerShell / CMD 无此问题。

### 8.2 apiserver 一直 `starting` / `unhealthy`

`/api/health` 的 `ok` 要求：**(spiderJar 为真 或 nativeSources > 0) 且 configLoaded**，
即“有可用的 spider 实现 + 站点表非空”。

| 症状 | 原因 | 处理 |
|---|---|---|
| `spiderJar:false` | 公开壳没有内置 jar，**正常** | 不用处理，`ok` 由原生源抬起；私有构建才查 `libs/spider.jar` 是否被空目录覆盖 |
| `nativeSources:0` / `configLoaded:false` | 原生源没注册 / 站点表为空 | 检查 `NativeSources`、`SITES` 静态块与 `config.sites.json` |
| 连不上 | 端口被占 / JVM 起不来 | `docker compose logs apiserver` |

### 8.3 drpy 源全部 403 / idle 高 CPU / 跨容器连不上

`server.js` 的 `authorized()` 对**每个** `POST /{site}/{action}` 生效：设了
`DRPY_TOKEN`（或 `MEOW_AUTH_TOKEN`）→ 必须带 `X-DRPY-Token`；没设 → 只允许**回环来源**。
而 Java 侧只发 `X-DRPY-Api` / `X-DRPY-Ext`，**从不发 `X-DRPY-Token`**，compose 也没给
drpy 注入 `DRPY_TOKEN`；容器间走网桥，来源不是回环 → 按代码判据，**容器化默认拓扑下
drpy 调用会全部 403**，不只是 `/testjs`。判据：
`curl -s -X POST http://<host>:30013/<站点>/search -H 'X-DRPY-Api: <脚本地址>' -d '{}'`
回 `{"code":-1,"msg":"drpy: unauthorized"}`。

**⚠️ 方案未定，改前先拍板**：要么 Java 透传令牌且 drpy 侧同步注入 `DRPY_TOKEN`；
要么把跨容器调用明确排除在校验外（只保留 `/testjs` 的门）。是否已有部署绕过这一点，
**待确认**（仓库里没有对应配置）。

**idle 高 CPU**：`server.js` 用 `new Worker(path.join(__dirname,'worker.js'))` 起线程池。
Dockerfile 只 `COPY server.js host.js` 时 worker 报 `CANNOT FIND MODULE` → retire 再补位 →
无限重启空转，idle 370%+ CPU（约 3.8 核），而 `/health` 仍是 ok。判据
`docker exec meow-drpy ls -l /app/worker.js`；修法就是现在的 Dockerfile
（`COPY server.js host.js worker.js ./`），改的时候别把这行拆开。

**跨容器连不上**：`server.js` 默认绑回环，`30013→9330` 与 `drpy:9330` 都跨命名空间。
判据 `docker exec meow-apiserver wget -qO- http://drpy:9330/health` 超时；修法是
`ENV DRPY_BIND=0.0.0.0`（已在 Dockerfile）。绑 `0.0.0.0` 后**不要暴露公网**——
`/testjs` 能执行任意脚本。

### 8.4 drpy 容器 unhealthy → 搜索静默变空

`apiserver` 每 15 秒探一次 `drpy:9330/health`（800ms 上限），探不通就把**全部脚本源**
从本轮剔除（共用同一容器，属同一故障域），表现为“200+ 源只剩 jar 源”；容器恢复后
探测缓存到期自动放行，不需要重启后端。

### 8.5 拉不到 danmu 镜像 / `network ... not found` / 端口占用

- **danmu 拉不到**（`error from registry: denied` 或长时间 `Pulling`）：镜像名写错
  （官方仓库是 `logvar/danmu-api`，版本见
  [Docker Hub](https://hub.docker.com/r/logvar/danmu-api/tags)）；或网络拉不动（配加速，
  或先 `docker pull logvar/danmu-api:1.21.6`，之后 `compose up -d` 命中缓存）；或不想用
  （`up -d web apiserver` 并置空 `MEOW_DANMAKU_API`）。
- **`network ... not found`**：你执行了 `docker compose down <服务名>`；`down` 后重新
  `up -d`，以后停服务用 `stop`（§6.1）。
- **端口占用**（`Ports are not available`）：改 `.env` 的 `*_PORT`，或先查占用者
  （Windows `netstat -ano | findstr :30010`；Linux `ss -ltnp | grep 30010`）；不要猜进程直接 kill。

### 8.6 中文乱码 / 弹幕慢 / 连到旧后端

**浏览器乱码**是响应缺 `charset`：503 JSON 在 nginx 模板里用
`default_type "application/json; charset=utf-8"` 显式声明（`charset` 指令不会作用到
这个自造的错误页）。**只有终端乱码**是 Git Bash 按 GBK 渲染 UTF-8，用 `curl -s ... | xxd | head`
看字节。**弹幕每次多等几秒**说明 `MEOW_DANMAKU_API` 指向了没启动的 danmu_api（不用就
置空，要用就 `up -d danmu_api` 并等 healthy）。**连到旧后端**看 `version`：通常是本机还
跑着旧的 9979 实例，或 localStorage 存了旧地址（`meow.api`）。

### 8.7 手工替换 jar 后行为没变 / `pnpm install` 退出码 1

镜像启动命令是 `java -cp 'classes:libs/*' ApiServer`（工作目录 `/app`），**加载
`/app/classes/*.class`，不加载 `/app/apiserver.jar`**。连续多次 `docker cp apiserver.jar`
全部无效，因为 jar 不在类路径上。临时验证改动要覆盖 `classes/`：
`tar czf /tmp/classes.tgz -C server/host/classes .`，`docker cp` 进去后在 **`/app`** 下
解压并 `docker restart`（`-C /` 会落到 `/classes`，照样跑旧代码）。`docker cp` 是临时的，
容器重建就没了；正式发版必须重建镜像。

构建 web 镜像时 `pnpm install` 只报 `exit code: 1`，真实原因是容器里没有 TTY，pnpm 判定
要重建 `node_modules` 就中止（`ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY`）。**已在
`Dockerfile.web` 里加 `ENV CI=true` 修掉**。不要在宿主机上直接
`docker run -v .../client:/b ... pnpm install` 复现：容器会把 Linux 版可选依赖装进本地
`node_modules`，回来 `vue-tsc` 报 `ERR_PACKAGE_PATH_NOT_EXPORTED: estree-walker`。

---

## 九、安全边界（部署到公网前必读）

| 风险 | 现在的状态 | 建议 |
|---|---|---|
| **`/api/proxy/stream` 转发外部 URL** | 有 SSRF 过滤（拒回环、链路本地与云元数据 `169.254.169.254`，媒体档允许局域网），分片代理 8 个并发槽、proxy 限速默认 240/窗口；但**无鉴权** | 不要把 apiserver 直接暴露公网；内网也保持 `APISERVER_BIND=127.0.0.1` |
| apiserver 读写端点 | 读端点公开；写端点可用 `MEOW_AUTH_TOKEN` 保护 | 公网访问时前面套 Basic Auth + HTTPS |
| web 反代同源 | 无 CORS 问题 | 对外只暴露 `WEB_PORT`，nginx 做唯一入口 |
| danmu_api 自带 UI | 上游镜像自己的令牌 | 用就改 `data/danmu/.env` 的 `TOKEN` |
| **CORS 来源** | 默认 `*`（不拦） | 公网时设 `MEOW_CORS=none` 或列白名单（§9.1） |

### 9.1 CORS 策略：默认不拦，按需收紧

| 值 | 行为 | 用在 |
|---|---|---|
| `*`（默认） | 任意 Origin 放行并回显 | 本机、局域网、Tailscale/ZeroTier、frp/CF 反代 |
| `none` | 只放行 `localhost`/`127.0.0.1`/`[::1]`/`meow.local`/TV 内置页/`tauri://`/`Origin: null` | 公网暴露且不想维护白名单 |
| `a,b,c` | 只放行列出的 Origin（**完整**写法，含协议与端口） | 固定域名的公网部署 |

**为什么默认不拦**（踩过的坑，别改回去）：旧实现用 `isPrivateHost()` 猜网段
（10/8、192.168/16、172.16/12…），而 Tailscale 的 `100.x` 属于 CGNAT `100.64/10`、
不在 RFC1918 里 → 用 Tailscale 访问时 GET 拿不到 `Access-Control-Allow-Origin`，写请求
直接 `403 cross-origin write blocked`，症状是「部署在服务器上的应用全被 cors 了，首页
空白」——首页第一个请求 `/api/Douban/home` 就是 POST。部署形态枚举不完，网络边界该由
**部署者**声明，不该由代码猜。

**判据必须单一真源**：`originAllowed()`（写请求门禁）与 `corsAllowedOrigin()`（下发响应
头）共用同一套逻辑；历史上两份分开手抄必然漂移——旧版门禁连 192.168 都没放行，局域网
分端口部署时自家前端都改不了收藏。**CORS 不是访问控制**：只防“浏览器里的第三方页面”，
能直连端口的人本来就能 `curl`；真正的入口控制是 `APISERVER_BIND` + 防火墙 +
（可选）`MEOW_AUTH_TOKEN`。

---

## 十、与其他端的关系

三种形态共用同一份 spider jar，客户端都通过 HTTP `/api` 连后端，只是后端跑在哪不同。

- **PC**：设置页填 `http://<IP>:30011` 即可用容器后端；也可以自己起 sidecar，两者并存
- **TV**：`/config.json` 与 `/spider-latest.jar` 由 nginx 反代到后端，首配页填
  `http://<IP>:30010` 即可（详见 [TV 接入与 Fongmi](./tv-integration.md)）
- **Docker 不替代 PC sidecar**：三种部署形态并存，不是替代关系

### 10.1 fnOS 原生包（fpk）：与 Docker 的三处不同

fpk 是另一条分发形态（`meowd` 是 Rust 壳，内含同一份前端并直连同机 Java），打包与发版见
[构建与发版](./build-release.md)。易混淆点：`/api/_shell/*` 只在 `meowd` 上存在，Docker
没有；fpk 的 30010 来自向导写入的 `wizard_port`，与 `WEB_PORT` 无关；fpk 由浏览器下载
安装包交给壳落盘（壳的 `reqwest` 没开 TLS），Docker 是 `pull` + `up`。

---

## 附：命令速查

```bash
# 构建 + 启动 / 用发布镜像 / 只起前端 / 只起后端
cd deploy/docker && docker compose -f docker-compose.build.yml up -d --build
cd deploy/docker && docker compose up -d
cd deploy/docker && docker compose up -d web
cd deploy/docker && docker compose up -d apiserver danmu_api

# 主栈 + 源服务 / 只起源服务（起完到设置页手动导入源，见 §3.3）
cd deploy/docker && docker compose -f docker-compose.with-sources.yml up -d
cd deploy/docker && docker compose -f docker-compose.sources.yml up -d
# 导入地址 http://<宿主IP>:18080/config.all.json；MEOW_SOURCES_BASE 默认 127.0.0.1 是占位

# 状态 / 日志 / 验证 / 停单个（不要用 down <服务名>）
cd deploy/docker && docker compose ps && docker compose logs -f apiserver
curl -s localhost:30010/healthz && curl -s localhost:30010/api/health
cd deploy/docker && docker compose stop web && docker compose start web
cd deploy/docker && docker compose rm -sf web

# 整体拆除 / 备份恢复
cd deploy/docker && docker compose down && rm -rf data/   # 连配置一起删
cd deploy/docker && tar czf meow-data-$(date +%F).tar.gz data/
cd deploy/docker && tar xzf meow-data-<日期>.tar.gz
```

---

**相关文档**：[`插件系统`](./plugin-system.md)、
[`接口契约`](./api-contract.md)（`/api/health` 字段语义）、
[`TV 接入与 Fongmi`](./tv-integration.md)、
[`构建与发版`](./build-release.md)。
