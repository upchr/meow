# 公网部署与访问控制（v2：应用内账号体系）

> 状态：**方案，待实施**（2026-11-19 定稿方向）。
> 目标场景：公网 Docker 服务器 / 公网 fnOS NAS；浏览器直接打开 web 端口在线观看，
> TV / PC 壳直连后端。**不要求前置反代、不要求 fnOS 网关**。
>
> v1（纯 token + nginx `?key=`）保留在 git 历史里；本文取代它。
> 旧结论「必须只暴露 web 容器 443」不再是前提——应用自己就是边界。

## 结论先行

| 判断 | 内容 |
|---|---|
| Web 直连 | **账号 + 密码登录** → `HttpOnly` 会话 Cookie；`<video>`/HLS 分片同源自动带 Cookie |
| 后端直连（TV/PC） | **设备令牌**（登录后生成，或首启生成）→ `X-Meow-Token` |
| 媒体流 | 浏览器走 Cookie；TV/PC 走短时媒体票 `?mt=`（URL 自带授权，`<video>`/ExoPlayer 都能用） |
| CatVod 出口 | `/config.json`、`/spider-latest.jar` 用独立 `configKey`（`?key=`） |
| 首次启动 | setup 令牌只打印到服务端日志；远程无法抢先设置密码 |
| TLS | **不是"前置服务器"**：Docker 用 web 容器自带 nginx 终止 TLS；fnOS 可用 meowd 的 rustls。纯 HTTP 模式只允许令牌、不允许密码登录 |
| 多用户 | 不做；单管理员 + 多设备令牌 |
| 前置反代 | 可选（TLS/限流/2FA），不是方案成立的必要条件 |

## 一、为什么不需要前置反代

认证放在应用里有三个好处：

1. Web 端会话 Cookie 让媒体请求天然带凭据，不用给每个分片签 URL；
2. TV/PC 继续用 header token，和现有前端「每个请求都带 `X-Meow-Token`」的实现一致；
3. Fongmi/TVBox 只需要一个 `?key=` 的 `config.json`，不依赖任何代理行为。

**前提**：密码登录必须跑在 TLS（或 VPN）上；纯 HTTP 会让密码/会话在链路上明文。
TLS 可以放在：

- Docker：`web` 容器里已有的 nginx（现在就在代理 `/api`，加 `listen 443 ssl` 即可，不是新增服务）；
- fnOS：meowd（Rust）用 `rustls` 终止 TLS；
- 两者都没有：退化为「HTTP + 仅令牌」模式（见 §九）。

> 如果选择 §10.6 的开源 IdP（Authelia/Authentik/Casdoor），则**确实需要**一个前置反代做 forward-auth/OIDC；
> 这是可选项，用于注册/找回/2FA/多用户，不是应用内账号模式的前提。

## 二、威胁模型与信任边界

未配置任何认证时（现状），公网暴露的实际后果：

| 能力 | 入口 | 现状 |
|---|---|---|
| 拿所有站点 `ext`（API key/cookie） | `GET /config.json`、`GET /api/config` | 匿名明文 |
| 看历史/收藏 | `GET /api/state/*` | 匿名 |
| 任意方法 SSRF + 回显 | `GET/POST /api/webdav` | 匿名，method 在 body |
| 盲 SSRF | `GET /api/proxy/danmaku?url=` | 无 URL 校验 |
| 正向代理 | `GET /api/proxy/stream?url=` | 有 `isSafeMediaUrl`，但公网 URL 可白嫖带宽 |
| 改策略/导配置/导入 jar | `POST /api/policy`、`/api/config/*` | 默认无 token 时全放行 |
| drpy 执行请求头 JS | `0.0.0.0:30013` | 默认公网可达 |

**信任边界（v2）**：

- 公网路径不可信：TLS 必须存在，否则只允许预共享令牌；
- 本机回环 / TV 端内网关 / PC 本机插件可信：它们不配认证，保持开放；
- 单管理员：拿到管理员密码或任一设备令牌 = 管理员，不做权限细分。

## 三、凭证模型（四类，职责分离）

| 凭证 | 谁用 | 传输 | 存储 | 生命周期 |
|---|---|---|---|---|
| **管理员密码** | Web 登录 | 仅 TLS 下 POST body；PBKDF2 哈希比对 | `policy.json` 的 `auth.passwordHash`（带盐） | 长期；可改 |
| **会话 Cookie** | 浏览器 | `HttpOnly; SameSite=Lax; Secure`（TLS 时） | 服务端会话表（内存/落盘） | 7–30 天，可退出 |
| **设备令牌** | TV / PC / 脚本 | `X-Meow-Token` header | `policy.json` 里存 SHA-256，原文只显示一次 | 长期，可单设备撤销 |
| **媒体票 `mt`** | 浏览器/TV 的媒体 URL | `?mt=` | 服务端 TTL 表 | 30 分钟–6 小时 |
| **configKey** | Fongmi/TVBox | `?key=` | `policy.json` 存 SHA-256，原文只显示一次 | 长期，可轮换 |

> 原则：**管理员令牌永远不进 URL**。URL 里只允许短时 `mt` 和 CatVod 的 `configKey`。

## 四、认证流程

### 4.1 首次启动（防远程抢注）

1. 检测到没有密码：生成 **setup 令牌**（32 字节 base64url），只打印到服务端日志/控制台；
2. 浏览器打开 `/setup`，输入 setup 令牌 + 新密码；
3. 成功后写入 `passwordHash`，setup 令牌作废；
4. 同时生成第一个设备令牌（名字 `initial`），只打印一次，供 TV 直接填。

**为什么不把 setup 页做成匿名可提交**：公网部署时，任何先访问的人都能设密码。setup 令牌只存在于服务端日志，远程拿不到。

> **HTTP 模式的例外**：纯 HTTP（无 TLS）下，浏览器提交 setup 令牌/密码会在链路上明文暴露。
> 因此 HTTP 模式**不开放网页 setup**，只能在服务器本机执行 `apiserver --set-password`
> （仅回环/控制台）写入密码；或者干脆只用设备令牌，不启用密码登录。
> setup 接口同样限流（5 次/分钟/IP），失败计数与登录共用。

### 4.2 Web 登录

- `POST /api/auth/login` `{username,password}` → 校验 PBKDF2 → 新建会话 → `Set-Cookie: meow_sid=...`
- Cookie：`HttpOnly`、`SameSite=Lax`、`Path=/`、TLS 时 `Secure`；不设 `Domain`
- 密码策略：至少 10 位；不强制大小写符号（避免用户写便签），但拒绝等于用户名/常见弱口令
- 登录成功/失败都做 Origin 校验（无 Origin 的 curl 放行），防登录 CSRF
- 失败限流：同 IP 5 次/分钟；连续失败指数退避；**不做全局锁定**（防攻击者锁死管理员）
- 响应不区分「用户不存在/密码错」
- 会话存在服务端内存表：**apiserver 重启会让所有浏览器会话失效**（设备令牌不受影响），这是有意的取舍；过期会话定时清理，单管理员最多保留 32 个会话（超出踢最旧）
- 反向代理终止 TLS 时，只有来自回环/受信代理的 `X-Forwarded-Proto: https` 才被采信（`MEOW_TRUST_PROXY=127.0.0.1`）；否则攻击者伪造该头可让 Cookie 被标成 Secure 或绕过 HTTP 限制
- 同理，登录限流与 `MEOW_ALLOW_CIDR` 的客户端 IP 只在受信代理下取 `X-Forwarded-For` 最后一跳，否则直接用 socket 对端地址

### 4.3 设备令牌

- `POST /api/auth/devices`（需会话）`{name}` → 返回一次明文令牌；
- `GET /api/auth/devices`：列表只返回 id/name/created/lastUsed，不返回令牌；
- `DELETE /api/auth/devices/{id}`：立即撤销；
- TV/PC 在设置页填这个令牌；旧版 `meow.authToken` 的 localStorage 继续可用（迁移为一个 `legacy` 设备令牌）；
- `MEOW_AUTH_TOKEN` 环境变量若存在，作为 `env` 设备令牌导入（哈希后存 policy），不再参与密码逻辑；
- 设备令牌最多 20 个（防 policy 膨胀）；`lastUsed` 每小时最多落盘一次；
- 令牌校验用常量时间比较。

### 4.4 改密码 / 找回

- 改密码：`POST /api/auth/password`（需会话）→ 重新哈希 → **递增 sessionEpoch**，所有旧会话失效；可选同时撤销全部设备令牌；
- 忘记密码：没有邮件/密保。只能在服务器本机执行 `apiserver --reset-password`（仅回环/控制台），或直接删除 `policy.json` 里的 `auth` 段再走 setup；
- 不提供"网页找回密码"，避免邮箱/SMS 依赖和新的攻击面。

## 五、授权矩阵

| 端点 | 匿名 | 会话 Cookie | 设备令牌 | `mt` | `configKey` |
|---|---|---|---|---|---|
| `/api/health`、`/healthz` | ✅ | — | — | — | — |
| `/api/auth/setup` | setup 令牌 | — | — | — | — |
| `/api/auth/login` | ✅（限流） | — | — | — | — |
| `/api/auth/logout`、`/password`、`/devices*` | — | ✅ | — | — | — |
| `/api/*` 其余 | — | ✅ | ✅ | — | — |
| `/api/proxy/stream`、`/api/library/stream` | — | ✅ | ✅ | ✅ | — |
| `/config.json`、`/spider-latest.jar` | — | ✅ | — | — | ✅ |
| 静态 `/` | ✅ | — | — | — | — |

规则：

- 配了密码/令牌才启用鉴权；什么都没配 = 旧行为（局域网自用不受影响）；
- `OPTIONS` 一律放行；
- 所有 `/api` 请求（含 GET）都要凭证；**只有** `/api/health`、`/healthz`、`/api/auth/login`、`/api/auth/setup` 例外；
- 写请求继续走现有 `Origin` 白名单（`MEOW_CORS`）；Cookie 场景再加 SameSite=Lax 兜底；
- `/api/health` 的 `authRequired` 语义要更新为：**配了密码或任一设备令牌即 true**；另返回 `authMode`（`none` / `password` / `token-only`），前端据此决定跳登录页还是令牌页；
- 前端必须审计所有绕过统一封装的请求（裸 `fetch`、`EventSource`、WebSocket、`<video src>`）：前两者没有带凭据就补，后者只能走 `mt`；
- 服务端判定顺序：**会话 Cookie → `X-Meow-Token` → `mt`（仅 stream）→ `configKey`（仅 config/jar）**，命中即停；
- `scope=read` 的设备令牌只允许安全 GET（搜索/详情/历史/播放地址），任何写操作 403；
- `/api/auth/devices*` 等管理接口**只认会话**，设备令牌即使 scope=full 也不能管理令牌（防一台设备提权）。

## 六、媒体授权（关键约束）

浏览器/PC 的 `<video>`、TV 的 ExoPlayer/WebView 都无法可靠携带自定义 header；TV 页面还是跨源（`http://meow.local`），拿不到后端 Cookie。所以：

### 6.1 两条通道

- **浏览器（同源）**：会话 Cookie 自动带 → `/api/proxy/stream`、`/api/library/stream` 直接放行；
- **TV/PC（跨源或原生播放器）**：先调 `POST /api/stream/ticket`（带设备令牌），拿 `{mt,exp}`，媒体 URL 追加 `?mt=`。

### 6.2 媒体票的作用域（防变成开放代理）

`mt` 不是"万能通行证"，服务端记录：

- `kind=library`：只允许指定 `lib` + 路径前缀；
- `kind=proxy`：只允许指定**源 host 集合**（最多 8 个），且每个 URL 仍过 `UrlSafety.isSafeMediaUrl`；
- 可选绑客户端 IP（公网 HTTP 模式默认开启）；**IP 取 socket 对端，受信代理下才取 XFF**；
- TTL 默认 30 分钟（TLS 下可 6 小时），过期由播放器重取；
- 票表定时清理（每分钟一次），单票最多 64 个 host；`mt` 在访问日志里做掩码（只留前 6 位）。

### 6.3 HLS

- 代理 m3u8 时，把分片 URI 重写为带同一个 `mt` 的代理地址；
- 分片 host 必须在 ticket 的 host 集合内，否则拒绝；
- 播放器不感知票的存在，照常请求；
- **实现注意（代码核对）**：`proxyUrl()`（`ApiServer.java`）目前只拼 `url/referer/ua/headers`，
  不传任何凭证；`EXT-X-KEY`/`EXT-X-MAP`/分片三处重写都走它。必须把 `mt`（以及投屏的 `ct`）
  作为参数贯穿到 `proxyUrl` 和所有调用点，否则"清单能取、分片 401"。

## 七、CatVod 出口

- `configKey` 与管理员密码/设备令牌**完全独立**，首启生成、只打印一次；
- `GET /config.json?key=`、`GET /spider-latest.jar?key=` 校验哈希；
- 无 key / 空 key → 403；无 key 时**不输出 `ext`**；
- key 会进 URL/浏览器历史/访问日志：nginx 对这两个路径 `access_log off`，应用日志 scrub；
- 轮换：`POST /api/auth/config-key/rotate`（需会话）→ 返回新 key 一次。

## 八、存储与加密

`policy.json` 新增段（**绝不通过 `/api/policy`、`/api/config`、备份导出返回**）：

```json
{
  "auth": {
    "username": "admin",
    "passwordHash": "<base64>",
    "passwordSalt": "<base64>",
    "iterations": 210000,
    "sessionEpoch": 1
  },
  "deviceTokens": [
    {"id": "...", "name": "initial", "hash": "<sha256>", "created": "...", "lastUsed": "..."}
  ],
  "configKeyHash": "<sha256>"
}
```

- 密码：`PBKDF2WithHmacSHA256`（JDK 自带，不引第三方），210k 次、16 字节盐、32 字节输出；
- 设备令牌/configKey：`SecureRandom` 32 字节 base64url；存 SHA-256；
- 会话：服务端 `ConcurrentHashMap<sid,{epoch,exp,created,ip?}>`；`sessionEpoch` 变化让全部旧会话失效；
- 所有比较用常量时间；
- 配置导出/WebDAV 同步必须排除 `auth`、`deviceTokens`、`configKeyHash`。

## 九、网络形态与密码可用范围（三档）

判据只有一个函数：`Auth.passwordAllowed()`。它不看"是不是 TLS"，看**这一跳网络的信任范围**：

| 访问方式 | 密码登录 | 依据 |
|---|---|---|
| HTTPS（TLS 终结在 nginx / meowd，或反代声明 `X-Forwarded-Proto: https`） | ✅ | `isSecure()` |
| 本机回环（`127.0.0.1` / `::1`） | ✅ | 运维调试，不经网络 |
| 局域网（`10/8`、`172.16/12`、`192.168/16`、CGNAT `100.64/10`、IPv6 ULA / 链路本地） | ✅ | 同网段本来就是可信域 |
| 公网地址 + HTTP | ❌ | 密码明文过公网 = 公开；改用 TLS 或设备令牌 |
| `MEOW_ALLOW_INSECURE_PASSWORD=1` | ✅ | 逃生口，仅测试 |

> **2026-11-20 调整**：之前只放行"回环"，于是家里 NAS / Docker 用局域网 IP 打开时
> **根本没有密码框** —— 用户以为这个产品没有密码体系。真正的边界是公网，不是"非回环"。
> 局域网内明文密码的风险边界就是同一个网段（可被嗅探）；要更强保证就上 TLS 或用令牌。

### 9.1 TLS 模式（推荐，公网唯一选项）

- Docker：`web` 容器 nginx 增加 `listen 443 ssl` + 证书挂载；HTTP 301 到 HTTPS；
- fnOS：meowd 增加 `--tls-cert/--tls-key`（rustls）；或同机一个反向代理容器；
- Cookie 带 `Secure`；密码登录可用；媒体票可放宽到 6 小时；
- nginx 必须设 `X-Forwarded-Proto $scheme`；应用只在 `MEOW_TRUST_PROXY` 指定的回环地址上采信它；
- 证书续期不在范围内：Docker 用 certbot `--deploy-hook` reload nginx；fnOS 自签/域名证书需手工续，文档写明。

### 9.2 局域网 HTTP（家用默认形态）

- 密码可用（判据见上表）；Cookie 不带 `Secure`，媒体票 TTL 压到 30 分钟并绑 IP；
- 浏览器走会话 Cookie（同源），TV / PC 仍走设备令牌或配对码；
- 建议叠加 `MEOW_ALLOW_CIDR`（来源白名单）；**回环永远放行**（否则 fnOS 网关 / 本机反代会被自己挡掉）；
- 风险说清：同网段可嗅探。它防的是访客 / 室友 / 误闯扫描，不防链路级攻击。

### 9.3 公网 HTTP（不推荐）

- 密码登录**自动关闭**，登录页直接写清三条出路（配 HTTPS / 填设备令牌 / 回局域网访问）；
- 只剩设备令牌，而令牌在链路上同样是明文 —— 只能防误闯 / 扫描，防不了中间人；
- 真要公网 HTTP，唯一正确做法是 VPN（Tailscale / WireGuard），隧道内当局域网用。

> 分体部署（web 在前、后端在内网）属于 9.2 或 9.1：后端看到的是反代地址，
> 必须设 `MEOW_TRUST_PROXY=1` 并让前置注入 `X-Forwarded-For`，否则会被当成回环。

## 十、部署形态

### 10.0 部署形态矩阵（先认清"谁和谁是一体"）

| 形态 | 谁在跑 | Web 页面来自 | 后端 API 在哪 | 谁需要登录 |
|---|---|---|---|---|
| **Docker 一体** | web(nginx)+apiserver+drpy+danmu | 同一部署的 web | 同部署 apiserver | 浏览器登录；TV/PC 令牌；Fongmi key |
| **fnOS 一体（直连 30010）** | meowd+apiserver+web | meowd 静态目录 | meowd/内嵌 apiserver | 浏览器登录；TV/PC 令牌；Fongmi key |
| **fnOS（仅 5666 网关）** | 同上，入口走 fnOS 网关 | 网关代理的同一 web | 网关后的 meowd | 浏览器：fnOS 登录（启用应用账号时再加一次应用登录）；TV 远程不可用 |
| **TV 本地网关** | 端内 GatewayServer + 复用 ApiServer | TV 本地打包资源（`meow.local`） | 端内 127.0.0.1 | **不登录**（只在本机回环） |
| **PC 本机插件** | Tauri + 本机 apiserver 插件 | PC 本地打包资源（`tauri://`） | 本机 127.0.0.1 | **不登录** |
| **分体：web 在前、后端在后** | web 容器/反代 + 远端 apiserver | 前置 web | 远端 apiserver（只内网） | 浏览器登录；TV/PC 指向**前置 web** 用令牌 |
| **纯后端（无 web）** | 只跑 apiserver | 无 | 本机/内网 | 无 Web 登录；只有设备令牌 |

两条判据：

1. **页面与 API 是否同源**：同源且该部署启用密码 → 浏览器登录；页面来自本机壳（`tauri://`、`meow.local`）→ 永远不用 Web 登录。
2. **API 是否只在本机回环**：是 → 不需要认证（TV 本地网关、PC 本机插件）；否 → 令牌必填。

### 10.1 Docker 公网服务器

```
公网 30010（或 443，web 容器内 nginx）
 ├─ /api/* ... 应用内会话/令牌
 └─ /config.json?key=...
只发布 web；apiserver/drpy/danmu 全部 127.0.0.1
```

- `MEOW_AUTH_MODE=on`：首启生成 setup 令牌并打印；
- `APISERVER_BIND=127.0.0.1`、`DRPY_PORT=127.0.0.1:30013`、`DANMU_BIND=127.0.0.1`；
- `MEOW_CORS=https://域名,http://meow.local,http://tauri.localhost,tauri://localhost`；
- 若用 443：证书挂载到 web 容器；若坚持 HTTP：只开 30010，按 §9.2。

### 10.2 fnOS 公网 NAS

- **直接 30010**：meowd 保持 `--bind=0.0.0.0`，应用内账号体系生效；TV/浏览器都填 `http://IP:30010`；
  - 代价：应用内 fpk 更新不可用（`/app-center/*` 只有 5666 网关有），升级走应用中心手动装；
- **仅网关**：`MEOW_SHELL_BIND=127.0.0.1`，只暴露 5666；浏览器可用（fnOS 登录 + 应用内账号二次登录），
  **TV 远程不可用**（TV 页面 `http://meow.local` 跨源拿不到 fnOS Cookie）；
- **推荐给 TV 远程**：30010 + 应用内账号，或同机反向代理 443 → 127.0.0.1:30010。

### 10.3 客户端与认证方式

| 端 | 页面来源 | 认证方式 | 备注 |
|---|---|---|---|
| Web / iPhone PWA | 部署的 web 同源 | 账号密码 → 会话 Cookie | 支持改密；不支持注册/网页找回 |
| TV 壳（本地网关） | `http://meow.local` 本地资源 | **无** | API 只在本机 127.0.0.1，不经网络 |
| PC 壳（本机插件） | `tauri://localhost` 本地资源 | **无** | 同上 |
| TV 壳（连公网/局域网后端） | `http://meow.local` | **设备令牌** | 播放另用 `mt`；不用 Web 登录 |
| PC 壳（连远后端） | `tauri://localhost` | **设备令牌** | 同上 |
| Fongmi / TVBox | 第三方壳 | `configKey`（`?key=`） | 只读 `/config.json` 与 jar |
| 脚本 / CLI / AI 外部调用 | 无页面 | 设备令牌 | 建议发只读或短有效期令牌 |

### 10.4 哪些端会出现 Web 登录

| 场景 | 登录页 | 原因 |
|---|---|---|
| 浏览器打开 Docker 的 web 端口 | ✅ | 页面与 API 同源，服务端有密码 |
| 浏览器打开 fpk 的 30010 | ✅ | 同上 |
| 浏览器通过 fnOS 5666 打开 `/app/meow/` | ✅（fnOS 登录后还要应用登录） | 两次认证：网关 + 应用 |
| TV 壳用本机网关 | ❌ | 页面本地、API 回环 |
| PC 壳用本机插件 | ❌ | 同上 |
| TV/PC 连远端后端 | ❌ | 页面本地、跨源；只用设备令牌 |
| TV 浏览器手动打开公网 web 地址 | ✅ | 这时它就是普通浏览器 |

**结论**：Web 登录只属于"浏览器 + 同源 web"；所有壳的本地页面都走令牌，不会弹登录页。

### 10.5 设备令牌怎么按端自定义

令牌是"设备/脚本"的凭证，不是账号。可配置项：

| 字段 | 说明 | 默认 |
|---|---|---|
| `name` | 设备名（TV-客厅 / PC-办公室 / 脚本） | 必填 |
| `expiresAt` | 过期时间；临时分享用 7 天，自有设备可不填 | 不过期 |
| `scope` | `full`（读写）/ `read`（只读：搜索、历史、播放地址） | `full` |
| `bindIp` | 可选绑定出口 IP（公网 HTTP 模式建议开） | 关 |
| `revoked` | 单设备立即失效 | — |

发放方式：

1. **网页生成**：登录后在「设置 → 设备令牌」新建，明文只显示一次；
2. **配对码（推荐给 TV）**：TV 显示 6 位配对码并轮询 → 用户在已登录的网页输入配对码 → 服务端签发令牌给 TV，避免遥控器输入 43 位字符串；
   - 配对码 5 分钟有效、一次性、每 IP 限 5 次/分钟；
   - 流程：`POST /api/auth/pair/start`（TV，匿名，返回 code）→ `POST /api/auth/pair/approve`（Web，需会话，body=code）→ `GET /api/auth/pair/poll?code=`（TV，返回一次性 token）；
   - 服务端记录 code → 批准的设备名/IP；轮询成功即删除 code；
3. **首启令牌**：首次启动打印一个 `initial` 令牌，用于第一个 TV/脚本；之后可撤销；
4. **旧版迁移**：`meow.authToken` / `MEOW_AUTH_TOKEN` 自动变成 `legacy`/`env` 设备令牌。

约束：

- 设备令牌只走 `X-Meow-Token`，**永不进 URL**；
- 最多 20 个；列表不返回明文；`lastUsed` 每小时最多落盘一次；
- 撤销/轮换立即生效；"全部撤销"会踢掉所有 TV/PC，但浏览器会话不受影响。

### 10.6 账号生命周期：注册 / 改密 / 找回

**应用内账号（默认，单管理员）**：

| 能力 | 是否支持 | 做法 |
|---|---|---|
| 注册 | ❌ | 单管理员；首启 setup 设密码。多用户不做 |
| 改密码 | ✅ | Web 登录后「设置 → 账号」；旧会话全部失效，设备令牌可选保留/撤销 |
| 找回密码 | ❌（无邮件） | 服务器本机 `apiserver --reset-password`；删 `policy.json` 的 `auth` 段可重新 setup |
| 2FA | ❌ | 需要时走下面的开源 IdP 方案 |
| 审计 | 基础 | 登录成功/失败、令牌创建/撤销写日志（不含明文） |

**为什么要"套开源"而不是自己造注册/找回**：注册、邮箱验证、密码重置、2FA、防撞库是一整套用户生命周期，自己写必留漏洞。需要这些能力时，直接前置一个成熟 IdP：

| 方案 | 形态 | 注册/找回 | 2FA | 运维成本 | 适合 |
|---|---|---|---|---|---|
| **Authelia** | 单容器 + 文件/LLDAP 用户库 | 无自助注册；重置靠管理员 | TOTP/WebAuthn | 低 | 单管理员/小团队，只要 SSO+2FA |
| **Authentik** | Server+Worker+Postgres+Redis | ✅ 注册/找回/邀请 | ✅ | 中 | 需要完整用户生命周期 |
| **Casdoor** | 单二进制 + SQLite/MySQL | ✅ 注册/找回/OIDC | ✅ | 低-中 | 想要单二进制 + 完整功能 |
| Keycloak | Java + DB | ✅ | ✅ | 高 | 企业级，不推荐自建小应用 |

集成方式（二选一）：

1. **Forward-auth**（Authelia 最常见）：反代先问 IdP，通过后注入 `Remote-User` 头；应用只信任来自回环的该头，不再自己做 Web 登录；
2. **OIDC**（Authentik/Casdoor）：应用做标准 OIDC 客户端，登录跳转 IdP，回调换会话。

**两种模式的重要边界**：

- IdP 只解决**浏览器**的登录/注册/找回/2FA；**TV/PC/Fongmi 仍然用应用自己的设备令牌与 configKey**——不要让 TV 走 OAuth；
- 走 IdP 时仍建议保留应用内"设备令牌"管理页（由 IdP 保护），否则 TV 无法接入；
- 走 IdP = 必须有反代；不用反代就保持应用内单管理员模式。

**选型建议**：

| 需求 | 选 |
|---|---|
| 只有自己/家人，单管理员 | **应用内账号**（Mode A）：不引入额外服务，改密用网页、找回用 CLI |
| 要自助注册/找回密码/2FA/多用户 | **Authentik**（完整生命周期）或 **Casdoor**（单二进制） |
| 只要 SSO + 2FA，不要自助注册 | **Authelia**（最轻） |

无论选哪种，**不要把"注册/找回/2FA"自己写**：那套东西的边界条件（枚举、限流、令牌吊销、邮件投递）比播放器还多，成熟 IdP 已经踩完了。

### 10.7 分体部署（web 与后端分离）

- **web 前置 + 远端 apiserver**：浏览器登录发生在 web 域（Cookie 同源）；web 的 nginx 把 `/api` 反代到远端 apiserver，并透传 `X-Meow-Token`/`mt`；
- **TV/PC 连分体部署**：后端地址填 **web 的地址**（不是 apiserver），用设备令牌；不要直接暴露 apiserver；
- **多个 web 前端共用一个后端**：会话 Cookie 按 origin 各自独立；设备令牌与 configKey 存在后端，天然共享；
- **纯后端（没有 web）**：没有 Web 登录；只发设备令牌 + configKey，适合只给 TV/脚本用。

## 十一、兼容与迁移

| 场景 | 行为 |
|---|---|
| 现有局域网部署（无密码/令牌） | 完全不变 |
| 现有 `meow.authToken` 用户 | 自动迁移为 `legacy` 设备令牌；localStorage 继续可用 |
| TV 本地网关 / PC 本机插件 | 不启用认证，零影响 |
| 开启认证后首次访问 | 浏览器跳 `/login`；未登录的 `/api` 返回 401 |
| 忘记密码 | 服务器本机 `--reset-password`，不提供网页找回 |

## 十二、改造清单

### A 后端（`server/host/src/shared`）

| # | 改动 | 判据 |
|---|---|---|
| A1 | `requiresAuth(method,path)`：会话或设备令牌；白名单见 §五 | 无凭证 `GET /api/config` → 401；`/api/health` → 200 |
| A2 | 首启 setup + PBKDF2 密码 + 登录/退出/改密 | setup 令牌错误 → 403；正确 → 会话 |
| A3 | 会话 Cookie（HttpOnly/SameSite/Secure）+ sessionEpoch | 改密后旧 Cookie 失效 |
| A4 | 设备令牌 CRUD（哈希存储、撤销、lastUsed） | 列表不出现明文 |
| A5 | 媒体票 `POST /api/stream/ticket` + 两个 stream 端点校验 + HLS 重写 | 无票 → 401；换库/换 host → 403 |
| A6 | `configKey` 校验 + 无 key 不返回 ext + 轮换接口 | `?key=` 错 → 403 |
| A7 | WebDAV SSRF 加固（目标校验/方法限制） | 打 169.254 → 拒 |
| A8 | danmaku `?url=` 加 `isSafeHttpUrl` | 打回环 → 拒 |
| A9 | 登录/令牌失败限流 + 日志 scrub | 登录 5 次/分钟 → 429；令牌 10 次/分钟 |
| A10 | `/api/policy`、`/api/config`、备份导出排除密钥字段（auth/deviceTokens/configKeyHash/LLM key/库密码） | 响应中搜不到 hash/key |
| A11 | 安全头：CSP（禁内联/远程脚本）、X-Content-Type-Options、Referrer-Policy；CSP 防 XSS 拿会话发请求 | DevTools 无 CSP 报错，内联脚本被拒 |
| A12 | 废弃 `?token=` 的管理员令牌入口，只保留 header；媒体只用 `mt` | `?token=` 不再通过鉴权 |
| A13 | 设备令牌属性：name/expiresAt/scope（read 或 full）/bindIp/revoke；配对码接口 | 过期/只读/撤销均生效 |
| A14 | 可选 IdP 模式：只信任回环来的 `Remote-User`，不自己解析 JWT（或做标准 OIDC） | 伪造头来自非回环 → 拒 |

### B 前端（`client/apps/web`）

| # | 改动 | 判据 |
|---|---|---|
| B1 | 登录页 + 401 拦截 + 退出 | 登录后首页可用；退出回登录 |
| B2 | 设置页：账号密码、设备令牌列表/新建/撤销 | 新建只显示一次 |
| B3 | `proxyStreamUrl`/`libraryStreamUrl` 先取 `mt` 再拼 URL | 播放请求带 `mt` |
| B4 | 播放器不把 admin token 拼进 URL | 抓包只有 `mt` |
| B5 | TV/PC 的 token 输入沿用现有设置项 | 远端后端可用 |
| B6 | 设备令牌管理页：列表/新建（name/expiry/scope）/撤销 + **配对码批准** | 新令牌只显示一次；配对码 5 分钟内可用 |
| B7 | 账号页：改密码、退出全部会话；HTTP 模式显示令牌输入而非登录 | 行为与 `authMode` 一致 |

### C 部署（`deploy/` + `client/apps/fnos`）

| # | 改动 | 判据 |
|---|---|---|
| C1 | `MEOW_AUTH_MODE`、`MEOW_CONFIG_KEY`、`MEOW_ALLOW_CIDR` 进 `.env.example` | 默认不改变旧部署 |
| C2 | drpy/danmu 端口收口；web 只发布一个入口 | `ss` 无 0.0.0.0:30013 |
| C3 | web 容器 nginx TLS 模板（可选 443）；限流**不覆盖** `/api/proxy/`、`/api/library/stream`（播放并发分片） | 证书可用；HTTP→HTTPS；播放不被 503 |
| C4 | fnOS：`MEOW_SHELL_BIND` 策略/向导（直连 vs 仅网关） | 两种模式都能启动 |
| C5 | fnOS：meowd rustls（可选 TLS） | 证书路径生效 |
| C6 | 文档：Docker/fnOS 两套部署步骤 + TV/Fongmi 配置 | 照文档可复现 |
| C7 | 核对 TV 本地网关 / PC 本机插件只绑 127.0.0.1 | 局域网不可达 |
| C8 | 可选 IdP 部署样例（Authelia/Authentik/Casdoor 任选其一） | 浏览器走 IdP；TV 仍走令牌 |

### D 测试与验收

| # | 改动 | 判据 |
|---|---|---|
| D1 | 后端单测：密码哈希、会话、令牌、票 scope、configKey | 全绿 |
| D2 | 前端单测：401→登录→恢复；票刷新 | 全绿 |
| D3 | 外网断言脚本（见 §十三） | 全过 |
| D4 | 三端真机：浏览器播放、TV 原生播放、PC 远程、Fongmi | 全过 |
| D5 | 回滚演练 | 1 分钟回旧行为 |

## 十三、验收断言

```bash
H=https://meow.example.com

# 未登录
curl -i $H/api/config                    # 401
curl -i $H/api/health                    # 200
curl -i $H/config.json                   # 403（无 key）
curl -i "$H/config.json?key=WRONG"       # 403
curl -i "$H/config.json?key=$K"          # 200 且含 ext

# 登录
curl -c c.jar -H 'Content-Type: application/json' \
     -d '{"username":"admin","password":"..."}' $H/api/auth/login   # 200 + Set-Cookie
curl -b c.jar $H/api/config              # 200

# 设备令牌
curl -b c.jar -H 'Content-Type: application/json' \
     -d '{"name":"tv"}' $H/api/auth/devices                        # 200 一次性明文
curl -H "X-Meow-Token: $T" $H/api/state/history                   # 200

# 媒体票
curl -b c.jar -H 'Content-Type: application/json' \
     -d '{"kind":"library","lib":"nas","path":"电影/01.mkv"}' \
     $H/api/stream/ticket                                          # 200 {mt}
curl -o /dev/null -w '%{http_code}\n' "$H/api/library/stream?lib=nas&path=电影/01.mkv&mt=$MT"  # 200
curl -o /dev/null -w '%{http_code}\n' "$H/api/library/stream?lib=other&path=x&mt=$MT"          # 403

# SSRF
curl -b c.jar -H 'Content-Type: application/json' \
     -d '{"url":"http://169.254.169.254/","method":"GET"}' $H/api/webdav  # 拒绝
curl -o /dev/null -w '%{http_code}\n' "$H/api/proxy/danmaku?url=http://127.0.0.1:9979/api/config" # 拒

# 端口
ss -lntp | grep -E ':(30010|30011|30012|30013|443)'   # 只有入口在 0.0.0.0
```

## 十四、回滚

1. 关闭认证：`MEOW_AUTH_MODE=off` 或删除 `policy.json` 的 `auth/deviceTokens/configKeyHash` → 重启；
2. 行为立即回到 v1.2.18 的「无鉴权」模式；
3. `MEOW_CORS` 若收紧过，一起回滚，否则写请求会被 CORS 挡。

## 十五、残留风险与明确不做

| 项 | 结论 |
|---|---|
| 多用户/权限分级 | 应用内不做；需要注册/多用户/找回/2FA 时前置 IdP（§10.6），应用只信任 IdP 的用户头 |
| 网页找回密码 | 不做；只能本机 CLI |
| 管理员令牌进 URL | 禁止；只有 `mt`/`configKey` |
| HTTP 下密码登录 | 默认禁止；只允许令牌 |
| 拿到管理员密码/令牌 | 等于管理员（可导入 jar，等于 RCE 能力） |
| `/api/health` 泄露 dataDir | 接受；可选脱敏 |
| 静态首页公开 | 接受 |
| 纯 HTTP 公网 | 只防误闯/扫描，不防链路；建议 VPN |

## 十六、方案自查（本次 review 发现并已修正）

| # | 发现 | 修正 |
|---|---|---|
| 1 | setup 页若匿名可提交，公网可被抢先设密码 | 改为 setup 令牌只打印到服务端日志 |
| 2 | 媒体 URL 若直接用 admin token，会进日志/历史 | 改为短时 `mt`，admin token 只走 header |
| 3 | `mt` 若不限 scope，等于开放代理 | 绑库/路径前缀或 host 集合 + `isSafeMediaUrl` |
| 4 | HLS 分片相对路径会丢 `mt` | 代理 m3u8 时重写分片 URI 并带票 |
| 5 | TV 跨源拿不到 Cookie | TV 走设备令牌 + `mt`，不依赖 Cookie |
| 6 | 纯 HTTP 下密码登录反而更危险 | HTTP 模式禁用密码登录，只允许令牌 |
| 7 | 登录失败全局锁定可被用来锁死管理员 | 改为按 IP 退避，不做全局锁 |
| 8 | 会话无法撤销 | 服务端会话表 + `sessionEpoch` |
| 9 | 备份/WebDAV 同步可能带走密钥 | 导出排除 `auth/deviceTokens/configKeyHash` |
| 10 | `configKey` 进访问日志 | 这两个路径关 access log + 应用日志 scrub |
| 11 | 现有 `meow.authToken` 用户升级会断 | 自动迁移为 `legacy` 设备令牌 |
| 12 | fnOS 直连 30010 时应用内更新不可用 | 文档明确：升级走应用中心 |
| 13 | drpy 仍绑 0.0.0.0 | 部署清单强制收口 |
| 14 | 没有前置反代时如何 TLS | web 容器 nginx / meowd rustls 两个内置选项 |
| 15 | 密码哈希引入第三方依赖 | 用 JDK 自带 PBKDF2-HMAC-SHA256 |
| 16 | 反向代理终止 TLS 时，应用看不到 HTTPS | 只在受信代理（`MEOW_TRUST_PROXY` 回环）采信 `X-Forwarded-Proto` |
| 17 | 限流/白名单在代理后会把所有客户端当成 127.0.0.1 | 仅在受信代理下取 `X-Forwarded-For` 最后一跳 |
| 18 | 纯 HTTP 下网页 setup 会把密码/令牌送上网 | HTTP 模式只允许本机 `--set-password`，不开放网页 setup |
| 19 | 会话存内存，重启即失效 | 明示为设计取舍；设备令牌不受影响 |
| 20 | 会话/票表无限增长 | 会话上限 32、票表每分钟清理、单票 host 上限 64 |
| 21 | `authRequired` 仍按旧 `authToken` 计算 | 改为「有密码或任一设备令牌即 true」，并返回 `authMode` |
| 22 | 裸 fetch / EventSource / WebSocket 可能绕过统一封装 | 前端全量审计；`<video src>` 只能走 `mt` |
| 23 | `mt` 可能进访问日志 | 日志掩码（前 6 位）；configKey 路径关 access log |
| 24 | 设备令牌可能无限增长/写放大 | 上限 20 个；`lastUsed` 每小时落盘一次 |
| 25 | 证书续期无人负责 | Docker 用 certbot hook；fnOS 文档写明手工续期 |
| 26 | **代码核对**：`proxyUrl()` 不传任何凭证，HLS 分片/EXT-X-KEY 重写后不带 `mt` | 实现时把 `mt`（和投屏 `ct`）贯穿到 `proxyUrl` 及全部调用点 |
| 27 | `MEOW_ALLOW_CIDR` 若不含回环会挡掉 fnOS 网关/本机反代 | 回环永远放行 |
| 28 | `/api/policy` 除 auth 外还可能有 LLM key、库密码 | 脱敏范围扩到所有密钥字段；CSP 防 XSS |
| 29 | fnOS 网关是否透传应用 `Set-Cookie` / `Authorization` 未验证 | 实施前必须先实测；不通就只走直连 30010 或同机反代 |
| 30 | TV 本地网关若绑 0.0.0.0 会变成局域网无认证后端 | 核对只绑 127.0.0.1；文档写明"本地网关只允许回环" |
| 31 | 遥控器输入 43 位令牌不现实 | 增加 6 位配对码流程（TV 显示、网页批准） |
| 32 | 引入 IdP 后容易误以为 TV 也能 SSO | 明确 IdP 只保护浏览器；TV/Fongmi 仍用设备令牌/configKey |
| 33 | 分体部署时 TV 可能被指到裸 apiserver | 文档要求 TV 填 web 前置地址；apiserver 不对外 |

## 十七、工时

| 阶段 | 内容 | 工时 |
|---|---|---|
| 1 | A1–A4（鉴权核心：setup/登录/会话/设备令牌） | 2d |
| 2 | A5（媒体票 + HLS）+ B3/B4 | 1d |
| 3 | A6–A10（configKey/SSRF/限流/脱敏） | 1d |
| 4 | A13（令牌属性/配对码）+ B1/B2/B5/B6/B7 | 1.5d |
| 5 | C（Docker/fnOS/TLS/端口）+ C7 + D1–D3 | 1.5d |
| 6 | D4/D5（三端真机 + 回滚） | 1d |
| 7（可选） | A14 + C8（IdP：Authelia/Authentik/Casdoor 任选） | 1–2d |

合计约 **8–9 人日**（不含可选 IdP）；阶段 1+2 是最小可验证单元。

## 十八、落地记录（2026-11-19）

### 18.1 实际落点

| 模块 | 文件 |
|---|---|
| 鉴权核心 | `server/host/src/shared/core/Auth.java`（`auth.json`，POSIX 0600；旧 `policy.json authToken` / `MEOW_AUTH_TOKEN` 自动导入为设备令牌） |
| 路由与总闸 | `server/host/src/shared/ApiServer.java`：`handleAuthRoutes()` / `mediaAuthorized()` / `handleStreamTicket()` / `ipAllowed` 总闸 / configKey / HLS `mt`+`ct` 透传 |
| SSRF | `routes/LibraryRoutes.java` WebDAV 方法白名单；`core.UrlSafety` 校验 WebDAV 与直连弹幕 |
| 前端 API | `src/api/auth.ts`、`src/api/mediaTicket.ts`、`src/api/index.ts`（401 处理器、票缓存、图片/弹幕带票）、`src/api/library.ts` |
| 登录与守卫 | `src/views/LoginView.vue`（setup / 密码 / 令牌 / 配对码）、`src/stores/auth.ts`、`src/router/index.ts`、`src/main.ts` |
| 账号管理 | `src/components/AccountPanel.vue`，入口在设置页「账号与安全」（仅鉴权开启时出现） |
| 部署 | 四个 compose 的 auth 环境变量、`deploy/docker/nginx.conf.template`（query 透传）、新增 `docker-compose.tls.yml` + `nginx-tls.conf.template`、`.env.example` |

### 18.2 与本文早期的偏差（以实现为准）

| 设计原案 | 实际实现 | 原因 |
|---|---|---|
| `auth` 存 `policy.json` | 独立 `auth.json` | `policy.json` 会被 `/api/policy`、备份/同步读取；密钥材料必须与可导出配置隔离 |
| 代理票按 URL host 白名单 | `hosts` 为空 = 任何通过 `isSafeMediaUrl` 的公网 URL；仍绑 IP + TTL | 同一路 HLS 会跨多个 CDN 域，逐域名签票会误杀；票本身已短时且只放行安全 URL |
| setup 只在 HTTP 本机开放 | 增加「setup 未完成 = 全部 `/api/*` 401」 | 否则首启窗口内公网可直接使用实例；日志里的 setup 令牌仍是唯一入口 |
| 未提 `MEOW_SETUP_TOKEN` | 支持环境变量固定 setup 令牌（填了不打印） | 容器编排无法方便地从日志抄 |
| `MEOW_ALLOW_CIDR` 仅列在清单 | 已实现：逗号分隔 CIDR，回环永远放行 | 与 §六 一致 |
| 媒体票只覆盖视频/片库 | 同一张 proxy 票同时覆盖 `/api/proxy/image` 与 `/api/proxy/danmaku` | 海报 `<img>`、弹幕 `<track>`/fetch 都无法带 header；实测 TV 首屏海报全变「点击重试」 |
| 未提登录页壳层 | `/login` 隐藏顶栏/底栏/AI/更新弹框 | 未登录还能点站点下拉与设置，观感像"没登录也能用" |

### 18.3 已验证（2026-11-19）

| 环境 | 形态 | 结果 |
|---|---|---|
| Docker（本地镜像） | HTTP 直连 `web:30010`，`MEOW_AUTH_MODE=on` | setup → 登录 → 设置页账号面板；无会话 `/api/config` 401、`/config.json` 403、带 `?key=` 200 |
| Docker + `docker-compose.tls.yml` | 自签证书，443→8443→8080 | HTTPS 登录成功；`Set-Cookie: ... Secure`；反向代理头下密码登录可用 |
| fnOS `.34`（**临时替换 jar/web 验证后已还原 1.2.18 原状**） | `/app/meow` 微应用前缀 | 登录页/账号面板正常；`config.json?key=` 经 meowd 200 |
| MuMu `.77`（TV 1.2.18 新包） | 远程后端 + 设备令牌 / 配对码 | 未登录被守卫拦到登录页；配对码批准后自动进首页；海报/封面正常；HLS 分片带 `mt` 200 播放；弹幕请求 200 且渲染器 `emitted>0` |

未覆盖：PC 壳连公网鉴权后端（本机插件模式不受影响）、Authentik/Authelia/Casdoor 实测、HTTP 公网纯令牌模式的长期稳定性。

### 18.4 已知取舍

- 会话存内存：后端重启后网页要重新登录；设备令牌不受影响。
- 媒体票也存内存：**后端重启会让客户端手里的旧票立即失效**（前端缓存 20 分钟）。表现为短时间内海报/弹幕 401，重启客户端或等票过期后恢复；若做"票失效自动重取"，需要让 `<img>` 也走带重试的组件（未做）。
- `/api/cache`（缓存状态/清单）等少量端点仍是裸 `fetch`：Web 有 Cookie 不受影响；TV 连接鉴权后端时这些辅助功能可能 401，核心播放/弹幕不受影响。
- fnOS 应用内更新仍依赖应用中心（直连 30010 时 meowd 没有 TLS 客户端）。

### 18.5 升级兼容与回归复查（2026-11-19 晚）

实现完成后又按"老部署升级"复核了一遍，抓到并修了两处会伤到存量用户的问题：

1. **未开鉴权的部署一播放就被弹到登录页**
   - 原因：播放页无条件预取媒体票 → 后端无凭证体系时返回 401 → 前端全局 401 处理器把它当"会话过期"跳 `/login`。
   - 修正：`/api/stream/ticket` 的 401 不触发全局跳转；播放页/直播/片库只在 `authRequired=true` 时才取票。
   - 验证：无鉴权后端打开首页 → 进播放路由，hash 保持 `#/play/...`、无 ticket 请求（2026-11-19 实测）。
2. **legacy `authToken` 升级后 `/config.json` 突然要 `?key=`**
   - 原因：`configKey` 的生成条件是"已有任意凭证"，老部署导入 legacy token 就会自动生成 key，Fongmi/TVBox 旧地址全部 403。
   - 修正：只有 `MEOW_AUTH_MODE=on` 或显式 `MEOW_CONFIG_KEY` 才生成；legacy token-only 升级保持 `/config.json`、`/spider-latest.jar` 匿名可读。
   - 验证：`policy.json authToken` + `MEOW_AUTH_TOKEN` 启动 → `configKeyRequired=false`、`/config.json` 无 key 200；`/api/config` 无 token 401、带 token 200。

**升级行为变化（需要告知用户）**：
- legacy token 现在会作为设备令牌**同时保护读接口**（原来只保护写）。旧版 TV/PC 壳升级到新后端后，如果本机没存设备令牌，会读不到数据；新版前端会显示"设备令牌"登录页。Fongmi 仍只需 `config.json`（本次已保持匿名）。
- WebDAV 目标继续允许局域网（`isSafeMediaUrl`），但**拒绝回环/链路本地**；把 WebDAV 服务跑在同一台机器并用 `127.0.0.1` 填地址的用法不再可用（自建 NAS 用局域网 IP 不受影响）。
- 直连弹幕 URL 仍走严格档（仅公网），局域网自建弹幕源会被拒。
- 启动时为拿鉴权态最多等 3 秒（后端完全不可达时）；局域网正常时是一次本地请求，感知不到。

**仍需补的测试**：老版本 TV/PC 壳连新鉴权后端的升级路径（设备令牌手填）、WebDAV 指向回环地址的用户是否有真实占比、IdP 模式。

**反向代理下的客户端 IP（复查补充）**：
- 主 compose / build / with-sources 现在默认 `MEOW_TRUST_PROXY=1`（apiserver 只绑回环，唯一入口是 web 容器）。不设的话后端只看到 nginx 的 IP：登录限流变成全站共享（攻击者连错 10 次会锁所有人 10 分钟），设备令牌的 `bindIp` 也永远对不上。
- `docker-compose.standalone.yml` 默认仍为空：它是"后端直连"形态，信任 `X-Forwarded-For` 可被伪造。
- 若 `APISERVER_BIND=0.0.0.0` 让 TV 直连后端，必须显式把 `MEOW_TRUST_PROXY` 置空。
- 多级代理（CDN → nginx）时 `clientIp()` 取 XFF 最后一跳，会把 CDN 边缘 IP 当成客户端；限流/绑定按边缘 IP 分桶。需要按真实客户端 IP 时，用 Cloudflare Tunnel/Access 或让最外层改写 `X-Real-IP` 并只信任该跳。

**同一实例同时有 `MEOW_AUTH_TOKEN` 和账号体系（复查补充）**：
- 旧写保护（`writeRequiresAuth` + policy authToken 精确比对）在账号体系启用时会误杀网页会话和 UI 生成的设备令牌。现在 `core.Auth.isApiAuthEnabled()` 为真时直接跳过旧写保护，统一由总闸按 scope 判定。实测：cookie / env 令牌 / UI 设备令牌三种写请求均 200。
- `MEOW_AUTH_TOKEN` 轮换会**替换**旧的 env 设备（旧令牌立即 401）；把变量删掉会清掉自动导入的 env 设备（避免"变量删了还在要令牌"）。
- `policy.json` 的 `authToken` 导入为 `legacy` 设备后是**单向迁移**：之后删 policy 里的值不会关闭鉴权，要关闭请删除 `data/auth.json`（或撤销全部设备）再重启。

### 18.6 UI 二轮调整（2026-11-20）

按用户反馈改了两处交互，并顺带修了一个 TV 真缺口：

1. **登录页垂直居中**。根容器原来写 `min-h-full`，在 flex 分配高度的 main 里解析为 0
   （百分比在 auto 父级下无效），卡片贴顶。改为 `min-h-[100vh] min-h-[100dvh]` 双写
   （老内核 vh 兜底、移动端 dvh 修正），几何实测卡片中心与视口中心偏差 0px
   （桌面 1280×800 与 MuMu .77 的 1138×640 都是 0）。
2. **设置页不再有独立的「账号与安全」分区**。入口收进「连接」区头部按钮
   （仅 `authRequired && authMode==='password'` 时显示），点开 `AccountDialog`
   弹框：Teleport to body（useOverlayHost）、`role="dialog"`、`data-tv-scope`、
   内容区 `data-tv-scroll + tabindex`，Esc/BACK 关闭并留在设置页。
   纯令牌部署不给按钮（改密/设备/配对/轮换都要会话，点开全是 401），
   那种部署用连接区已有的「设备令牌」输入框。
3. **TV 壳的登录页一律走令牌/配对形态**。TV 页面在 `http://meow.local`，连远程
   后端是跨源，fetch 不带 Cookie —— 密码表单在 TV 上是死路（登录 200 后
   `/me` 不带 Cookie 401，守卫弹回，死循环，实测）。`LoginView` 的 mode 在
   `isTvShell()` 时强制 `token`；配对码入口的显示条件从「登录形态」改成
   `authMode==='password'`（TV 的 token 形态也要能看到配对码）。
4. **账号管理端点接受 full 设备令牌**（`requireAccountAdmin`：会话或 full 令牌，
   read 令牌 403）。改密/设备增删/配对批准/configKey 轮换五处端点从
   `sessionAuth` 换过来 —— TV 跨源没有会话，配对拿到 full 令牌后打开账号
   弹框原来设备列表 401（实测）。full 令牌等于管理员能力（能导入源包就能
   改配置），让它管理账号是同一语义；read 令牌维持只读。

验证：vitest 1934 passed；桌面浏览器几何断言（居中 0px、Teleport 落 body、
弹框内生成令牌、Esc 关闭）+ MuMu .77 全链路（配对 → 首页 → 设置页按钮 →
弹框居中 → 设备列表加载 → TV 真实 BACK 键关闭，截图 `tmp/2026-11-20-ui/`）。

### 18.7 端口独立映射外网 + 安全补全（2026-11-20）

用户明确场景：web 30010、后端 30011、弹幕 API 30012 **各自独立映射公网**——不是所有
壳子都经 web 的 nginx 转发。逐端口的安全要求与本轮补全：

| 端口 | 直接映射公网的要求 | 本轮改动 |
|---|---|---|
| web 30010 | 照旧：nginx 转发 /api，`MEOW_PUBLIC_TLS=1`（或用 docker-compose.tls.yml）；`MEOW_TRUST_PROXY` 默认 1 | — |
| apiserver 30011 | `APISERVER_BIND=0.0.0.0` + `MEOW_AUTH_MODE=on`；TV/PC 壳用设备令牌（header 不受跨源限制）；**web 前端跨源直连时**，后端 `MEOW_CORS` 必须显式白名单 web 的 Origin | **CORS 三态**（见下）；前端 `request()` 密码模式跨源时 `credentials:'include'` |
| 弹幕 API 30012 | **不建议映射公网**：第三方镜像自身无鉴权。必须暴露时前面加反代 + basic auth | `.env.example` 写明；`DANMU_BIND` 默认 127.0.0.1 不变 |
| drpy 30013 | **禁止映射公网**：js 源执行引擎，暴露=交出 RCE 面 | **`DRPY_BIND` 默认 127.0.0.1**（原来裸绑 0.0.0.0！），四个 compose 同步 |

**CORS 三态**（`corsTrust()`）：显式白名单命中 / 各端壳固定来源（tauri、meow.local、
androidplatform、localhost）→ 下发 `ACAO + Access-Control-Allow-Credentials: true`；
默认反射（MEOW_CORS 未设，`*`）→ 只反射 ACAO **不带 credentials**（浏览器禁止
反射+credentials 组合，配了等于全站关 CORS）；其余 → 无 ACAO。分体部署的密码会话
靠"显式白名单 + 前端 include"打通，令牌模式与同源部署零变化。

**分体部署验证**（web 5196 / api 21995 两个独立源）：守卫跨源拦截 → 密码登录
（Cookie credentials include）→ `/api/auth/me` 带 Cookie 200 full → 设置页弹框跨源
加载设备列表，全链路通过。

**本机运维 CLI**（不再需要手工删 auth.json）：

```bash
java -cp "classes;libs/*" ApiServer --auth-reset-password=<新密码> --data-dir=<数据目录>
java -cp "classes;libs/*" ApiServer --auth-off --data-dir=<数据目录>
```

改完立即退出不监听；前者全会话失效（设备令牌/configKey 不动），后者落一份空
auth.json 回到关闭态。Windows 权限位异常已有兜底（save 里 try/catch）。

**同实例的 `MEOW_AUTH_TOKEN` 轮换语义**：轮换=替换旧 env 设备（旧令牌立即 401）；
删变量=清掉 env 设备；policy authToken 是单向迁移（删 policy 值不关鉴权，要关用
`--auth-off` 或删 auth.json）。

### 18.8 P1/P2 体验与安全补全（2026-11-20 复核后）

按"安全 + 好用 + 不让用户摸不着头脑"复核 18.1–18.7 的落地结果，补了七项：

| # | 问题 | 处理 |
|---|---|---|
| 1 | **后端重启后所有网页端要重新登录**（会话只在内存） | 会话表落盘到 `auth.json` 的 `sessions`，**只存 sid 的 SHA-256**；TTL / 上限 32 / `sessionEpoch`（改密失效）语义不变。实测：重启 NAS 后端后同一 Cookie 仍 200 |
| 2 | **后端重启后海报/弹幕全 401**（票在客户端缓存 20 分钟，票本身 30 分钟有效） | 三层自愈：缓存降到 15 分钟；`<img>` 首次失败自动刷票重试一次（`PosterImage`）；切回前台 10 分钟补刷（`main.ts` visibilitychange）。取票请求按 key 合并，20 张海报同时 401 只发一次 |
| 3 | **改密/撤销设备后对方只看到"请重新登录"** | 401 响应带 `reason`：`no_credentials` / `session_expired` / `password_changed` / `token_invalid` / `token_expired` / `ip_mismatch`；路由守卫带进 `?reason=`，登录页顶部一条红框说明+处置建议。改密时**不再清会话表**（清了就只剩"过期"，拿不到 `password_changed`） |
| 4 | **setup 完成后不知道怎么给 TV/PC 配令牌** | setup 表单下直接写清路径：电视点「用配对码登录」→ 网页「设置 → 连接 → 账号与安全 → TV 配对」批准 |
| 5 | **configKey 轮换后旧客户端 403 无解释** | 轮换后显示"只显示这一次 + 旧地址立即 403 + 去哪替换" |
| 6 | **纯 HTTP 部署没有密码框，用户以为账号坏了** | token 形态下明确提示"纯 HTTP 关掉了密码登录（密码明文过网），要用密码请配 HTTPS" |
| 7 | **设备数到 20 上限按钮变灰没说明** | 达到上限时补一行"先撤销不用的设备再生成" |

另外加了一个**默认关闭**的 P2 逃生口：`MEOW_ALLOW_LOOPBACK_MEDIA=1` 允许媒体代理
访问回环地址（同机另一个服务提供片源的真实场景）。链路本地（含云元数据地址）
无论如何拒绝。

**401 原因只在客户端"已经在用凭据"时才有意义**：没有令牌/没有 Cookie 的首次访问
也会带 `no_credentials`，登录页对这种情况不显示红框（`reasonText` 为空）。

**验证**（本机 + NAS 各一轮）：登录 → 重启后端 → 旧 Cookie `/api/auth/me` 200；
改密 → 旧会话 401 `password_changed`；无凭据 → `no_credentials`；坏令牌 →
`token_invalid`。登录页渲染 `?reason=password_changed` / `token_invalid` 两种文案。

### 18.9 可用性收口：13 项（2026-11-20 末轮）

§18.8 之后按"安全 + 好用 + 不让用户摸不着头脑"再过一遍，逐条补齐。
分组：**看得见**（账号面板）→ **摸得着**（登录与恢复）→ **按得动**（TV 操作）→ **不断线**（部署与网络）。

| # | 条目 | 处理 | 落点 |
|---|---|---|---|
| A1 | 设备列表只有名字和时间，分不清"这行是不是我正在用的设备" | 加「最近 IP」列；当前会话对应的设备标「本机」。IP 由后端按 1 小时节流写入 `lastIp`（避免每次请求都落盘） | `AccountPanel.vue` / `Auth.authenticate` |
| A2 | 手机丢了要逐个撤设备，改密又会把网页端一起踢掉 | 一键「退出所有设备（保留当前）」：撤销全部设备令牌 + 下线其它浏览器会话，**当前会话保留**。返回实际撤销数 | `POST /api/auth/logout-others` |
| A3 | 登录成功/失败/撤销/改 key 都没有记录，出事查不了 | 内存环形日志（`MAX_EVENTS=200`，重启清零），记录 login_ok / login_fail / setup / password_changed / device_created / device_revoked / pair_approved / config_key_rotated，带时间、IP、细节；面板里折叠展示 | `Auth.event()/events()` + `GET /api/auth/events` |
| A4 | 改密时看不见自己输的密码，也看不出强度 | 密码强度条（长度+字符类）+ 「显示密码」开关 | `AccountPanel.vue` |
| B5 | 每次登录都要重打用户名 | 登录成功后存 `meow.authUser`，下次只输密码；换账号时会被覆盖 | `LoginView.vue` |
| B6 | 忘记密码只能去翻文档 | 登录页内嵌 `<details>`：给出 `--auth-reset-password` 与 `docker exec` 两条完整命令，并说明"改密会让所有会话失效" | `LoginView.vue` |
| B7 | 撤销设备 / 轮换 configKey / 退出所有设备一点就生效，误触代价大 | 三个都走 `confirmDialog` 二次确认，标题写清动作、正文写清后果（"该设备的令牌立即失效，正在播放的会中断"） | `AccountPanel.vue` |
| B8 | 改密前不知道会影响几个浏览器 | `/api/auth/me` 带 `devices` / `sessions` / `deviceId`，改密与退出所有设备前把影响面写在确认框里 | `ApiServer` + `AccountPanel.vue` |
| B9 | configKey 只给一个地址，不知道哪个是 JVM / 哪个是 TV | 两条完整 URL（JVM / TV-dex）各自带复制按钮；轮换后提示"只显示这一次" | `AccountPanel.vue` |
| B10 | **TV 上账号弹框方向键几乎不可用**：焦点环不渲染、初始焦点落在容器、20 秒被自动关、下拉是原生 select、`data-tv-scroll` 用错 | 四处一起改：①焦点环改 inline `box-shadow ... !important`（`<input>` 上 box-shadow 会被 `outline:none !important` 吃掉，必须 inline 的 `!important` 才赢）；②初始焦点两拍（40ms + 320ms）落到第一个输入框，`pin=false`；③`data-tv-idle-keep` 豁免空闲自动关；④去掉内容区的 `data-tv-scroll`（它只适用于"内部没有可聚焦元素"的滚动容器）；⑤设备令牌下拉改设置页同款 `TileSelect`，菜单 `z-140` 高于弹窗 `z-120` | `tv-nav.js` / `AccountDialog.vue` / `TileSelect.vue` |
| C11 | TLS 部署没有 HSTS | TLS 模板加 `Strict-Transport-Security: max-age=31536000; always`（注释说明"临时验证请注释掉"） | `nginx-tls.conf.template` |
| C12 | 后端重启窗口内前端请求直接失败，页面显示空白/报错 | `requestUncached` 对 **fetch 抛出的网络错误** 600ms 后重试一次；`AbortController` 超时不重试（否则等于把超时翻倍） | `api/index.ts` |
| C13 | 同机另一个服务提供片源时媒体代理会拒（`isSafeMediaUrl` 拒绝回环） | `MEOW_ALLOW_LOOPBACK_MEDIA=1` 默认关闭的逃生口；链路本地（含 `169.254.169.254` 云元数据）**无论如何拒绝** | `UrlSafety.java` + 4 个 compose + `.env.example` |

**验证**（本机 21986 + NAS `.34` 各一轮，TV `.77` 实机）：

- `vitest run` **1944 passed / 3 skipped**；`vue-tsc --noEmit` exit 0。
- 会话落盘：登录 → 重启后端 → 同一 Cookie `/api/auth/me` 仍 200（重启免登）。
- 改密 → 旧会话 401 `password_changed`；坏令牌 → `token_invalid`；两者在登录页顶部显示对应红框。
- `logout-others` 实测撤销 5 个设备令牌 + 2 个其它会话，当前会话继续可用。
- `/api/auth/events` 返回登录/撤销/轮换记录，带 IP。
- TV：撤销 `tv-mumu-77e` → 重启 TV → 登录页显示"设备令牌无效（可能已在网页端被撤销），请重新配对或生成新令牌"；配对码 `523908` 在网页端批准后回到首页，历史/收藏正常加载。
- HSTS 与回环开关在测试里做文本断言（未在真实 TLS 域名上抓包验证）。

**已知限制**（无状态媒体票的固有代价）：

1. **撤销设备后，已签发的媒体票在有效期内（≤30 分钟）仍可播**。票是 HMAC 自证、服务端不存表，撤设备只能让"取新票"失败，收不回已发出的票。要彻底即时生效只能把票做成有状态（每张票落盘/查表），代价是每个分片请求多一次存储访问 —— 当前不做。
2. 安全事件是**内存**的，后端重启清零；需要长期审计要落到文件/外部日志（HSM 场景才值得）。
3. `lastIp` 是"最近一次成功请求的 IP"，按 1 小时节流，可能滞后；反向代理后取的是 `X-Forwarded-For` 首段，代理没配好会全是内网地址。

## 十九、fnOS / 飞牛套件运维（2026-11-20 补）

### 19.1 目录与端口

| 路径 | 是什么 |
|---|---|
| `/vol1/@appcenter/meow/` | 程序目录：`bin/meowd`、`web/`（静态站）、`seed/apiserver/apiserver.jar`、`plugins/`、`ui/` |
| `/vol1/@appdata/meow/data/` | **数据目录**：`auth.json`、`policy.json`、片库/缓存/日志 |
| `/var/apps/meow/etc/meow.conf` | 启动环境变量（被 `cmd/main` source 进来） |
| `/var/apps/meow/cmd/main` | 启动脚本（`start` / `stop` / `status`） |
| `/vol1/@appcenter/meow/meow.sock` | fnOS 网关（5666）用的 unix socket |

端口：**30010 = meowd（web + 反代）**，**30011 = apiserver（只听 `127.0.0.1`）**。
浏览器一律访问 30010；直连 30011 只在 NAS 本机排查时用。

### 19.2 开鉴权

`/var/apps/meow/etc/meow.conf`：

```bash
export MEOW_AUTH_MODE=on
# 可选：固定 setup 令牌（不设则随机生成并打印到日志）
export MEOW_SETUP_TOKEN=换成一个你自己的随机串
```

> ★ 设置页保存会走 `cmd/config_init`。**1.2.19 及更早它整体覆盖这个文件**，
> 于是 `export MEOW_AUTH_MODE=on` 被抹掉、鉴权静默关闭（实测踩过）。
> 现在 `config_init` 只替换端口两行、其余原样保留。

### 19.3 拿 setup 令牌

```bash
# 自己设过 MEOW_SETUP_TOKEN 就用它；否则看启动日志
grep -i -A3 "setup" /vol1/@appdata/meow/logs/meowd.log | tail -8
```

### 19.4 重置密码 / 关闭鉴权

```bash
# 重置（改完立即退出，不启动服务；密码 ≥10 位）
su meow -s /bin/sh -c 'java -jar /vol1/@appcenter/meow/seed/apiserver/apiserver.jar \
  --auth-reset-password=新密码 --data-dir=/vol1/@appdata/meow/data'

# 彻底关掉（清空密码 / 设备令牌 / configKey）
su meow -s /bin/sh -c 'java -jar /vol1/@appcenter/meow/seed/apiserver/apiserver.jar \
  --auth-off --data-dir=/vol1/@appdata/meow/data'
```

★ 必须用 `meow` 用户跑：`auth.json` 是 0600 且属主为 meow（uid **993**，不是 1000）。
★ 先 `appcenter-cli stop meow` 再改，否则跑着的实例会用旧内存值覆盖回去。

### 19.5 重启应用（改 conf / 换 jar 之后）

```bash
appcenter-cli stop meow && sleep 2 && appcenter-cli start meow
```

不要手工 `nohup ./cmd/main start`：`TRIM_PKGVAR` 等变量只在 appcenter-cli 的环境里存在，
手工跑会报 `TRIM_PKGVAR: unbound variable`。

### 19.6 来源 IP：两个入口，能力不同

| 入口 | 后端看到的来源 IP | 后果 |
|---|---|---|
| 直连 `http://<NAS>:30010/` | **真实 IP** —— meowd 用 socket 对端**覆盖**注入 `X-Forwarded-For`（`meowd/src/http.rs` 的 `set_peer_addr`，2026-11-20 起） | 限流、设备 `lastIp`/`bindIp`、媒体票 IP 绑定、局域网/公网密码判定全部正常 |
| 走 fnOS 网关（`https://<NAS>:5666` → unix socket） | **恒为 `127.0.0.1`** | 上述四项全部退化；`passwordAllowed` 只能靠"像本机"放行 |

真因：fnOS 的 micro_app 网关不转发 `X-Forwarded-For`（实测：连客户端自带的伪造值都会被
清洗掉），meowd 只能看到 unix socket 的对端。**这层 meowd 修不了**。

由此三条红线：

1. 要安全 → 用 **30010 直连 + 自己的 TLS 反代**（能拿真实 IP，公网密码会被正确拒绝）；
2. 只用 5666 网关 → **别把 5666 直接映射到公网**（链路是否加密由网关决定，应用侧判断不了）；
3. 需要"公网 + 密码" → 走 VPN（Tailscale / WireGuard），隧道内按局域网处理。

### 19.7 升级时必须一起换的两样

`meowd` 二进制与 `apiserver.jar` **要同批替换**：新 jar 依赖 meowd 注入的可信 XFF，
旧 meowd 不注入 → 开了 `MEOW_TRUST_PROXY` 之后来源 IP 仍是 127.0.0.1（不会更糟，但也修不好）。
