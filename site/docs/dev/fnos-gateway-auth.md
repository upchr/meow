# fnOS 网关身份接入：统一认证怎么接、Web 与后端对外怎么分工

> 状态：**已实现**（2026-11-20，分支 `feat/public-auth`）。落地记录与偏差见文末 §九。
> 定位：这是 [公网部署与访问控制](./public-deployment.md) 的 **fnOS 专章**——
> 那份文档讲"应用内账号体系"，本文补"经 fnOS 网关进来时，身份从哪来、要不要二次登录"。
>
> 相关：[公网部署与访问控制](./public-deployment.md)、[本地目录片库](./library-local-browse.md)、
> fnOS 套件说明（仓库 `client/apps/fnos/README.md`）。

## 结论先行

| 入口 | 谁在验身份 | 应用拿到什么 | 还要不要 meow 登录 |
|---|---|---|---|
| **fnOS 网关**（`/app/meow`，同机 5666） | fnOS（已登录才能进） | `X-Trim-Userid` / `X-Trim-Username` / `X-Trim-Isadmin` | **不要**（网关身份直接换本地会话） |
| **直连 30010**（fpk 的 web 端口；30011 已由 `cmd/main` 钉成 `127.0.0.1`） | 没人 | 无身份头 | **要**（账号密码 / 设备令牌） |
| **公网反代**（Docker / fnOS 443） | meow 自己（TLS） | 客户端可伪造任何头 | **要**，且**永不信任** `X-Trim-*` |
| **TV / PC 连远程后端** | meow 自己 | `X-Meow-Token` | 设备令牌（配对码发放） |
| **Fongmi / TVBox** | meow 自己 | `?key=` | configKey |

一条红线：**网关只保证"这个人登录了 fnOS"，不保证"他能动 meow 的什么"。**
业务权限、数据归属、管理接口，全部由 meow 自己判。

## 一、公开契约（唯一该依赖的东西）

来源：[统一网关](https://developer.fnnas.com/docs/core-concepts/gateway-registration/)。

### 1.1 注册

`app/ui/config`（我们已有）：

```json
{ ".url": { "meow.main": {
  "title": "猫窝 Meow", "type": "iframe",
  "url": "/app/meow",
  "gatewaySocket": "meow.sock",
  "gatewayPrefix": "/app/meow",
  "allUsers": true
} } }
```

- fnOS 把 `/app/meow/**` 校验会话后，转发到 `/var/apps/meow/target/meow.sock`；
- `meowd` 已经监听这个 socket，并与 TCP 共用同一个 axum Router（`meowd/src/main.rs` 的 `serve_gateway`）。

### 1.2 身份头

| 头 | 例 | 说明 |
|---|---|---|
| `X-Trim-Userid` | `1000` | fnOS 用户 UID |
| `X-Trim-Username` | `admin` | 用户名 |
| `X-Trim-Isadmin` | `true` / `false` | 是否管理员 |

- **WebSocket 建连时也有**；建连后应把连接绑定到 `X-Trim-Userid`；
- 文档明确：**不要信任客户端传来的用户 ID**，用网关给的；
- 文档也明确：网关**不负责业务权限**。

### 1.3 官方影视走的是另一套，不要抄

拆 `trim-media` 的包（`tmp/2026-11-04-oect-影视fpk/`）得到的实测结论：

| 项 | 影视（官方） | 我们 |
|---|---|---|
| 入口 | `ui/config` 是 `type=url` + `service_port=8005`，**不用网关 socket** | 已用 `gatewaySocket` |
| 身份 | 二进制里 `X-Trim-Userid/Username/Isadmin` **0 命中** | 也 0 命中（待补） |
| 账号 | 私有服务 `/var/run/trim.accountsrv.sock` + 登录码（`logincode/generate`、`logincode/bind`） | 自家 auth v2 + 配对码 |
| 授权目录 | 私有 socket `com.trim.app.center.sock` 的 `GetAppAuthorizedDir` | 公开变量 `TRIM_DATA_ACCESSIBLE_PATHS` |
| 运行身份 | `run-as: root` | `package`（不改） |

结论：**官方那两套私有 socket 是"官方应用特权"，第三方 fpk 拿不到稳定契约**。
我们能依赖的只有公开文档里的 `X-Trim-*`，其余照抄等于把版本升级当赌注。

## 二、现状差距（都验证过）

| 项 | 状态 | 证据 |
|---|---|---|
| 网关注册 | ✅ | `fpk/app/ui/config` 的 `gatewaySocket/gatewayPrefix` |
| 监听网关 socket | ✅ | `meowd/src/main.rs` 的 `bind_gateway_socket()` / `serve_gateway()` |
| 读 `X-Trim-*` | ❌ | `meowd` 全仓 `grep -i "x-trim"` 零命中 |
| 网关是唯一入口 | ❌ | `cmd/main` 的 `BIND=${MEOW_SHELL_BIND:-0.0.0.0}`，30010 对局域网开放 |
| 头清洗 | ⚠️ 隐患 | `meowd/src/proxy.rs` 的 `forward()` 原样转发所有非 hop-by-hop 头 |
| Java 侧可辨识用户 | ❌ | 无 uid 概念；`/api/state/*` 是全局单份 |

**推论**：现在 fpk 的"统一认证"只是"能进门"，进门之后所有人共用一份数据、没有管理员/普通用户之分。

## 三、身份模型：把 fnOS 用户映射成 meow 账号

### 3.1 最小可用（推荐先做）

1. 请求带 `X-Trim-Userid` 且**来自 unix socket** → 视为可信；
2. 服务端查"fnOS 身份表"（`policy.json` 里 `auth.fnos[uid] = {name, isAdmin, meowUserId, firstSeen}`）：
   - 有 → 直接建会话；
   - 没有 → 首次自动建号（不设密码，`source=fnos`），并发给 `HttpOnly` 会话 Cookie；
3. 会话建立后，后续请求按 Cookie 走现有鉴权（与 auth v2 完全一致）；
4. `X-Trim-Isadmin=true` 只用于**授予管理能力**，不等于"读所有用户数据"。

这样：

- **不引入第二套登录**（auth v2 的账号体系照旧存在，只多一个"免密来源"）；
- 浏览器经网关进来**不再弹登录页**（现在 §10.4 写的是"二次登录"，本文替换这一结论）；
- 数据库里能区分是谁在看（历史/进度按 uid 分命名空间，见 §3.3）。

### 3.2 与 auth v2 的关系（别混）

| 凭证 | 解决什么 | 网关来了还要吗 |
|---|---|---|
| fnOS 网关身份 | "同机浏览器已经登录过 fnOS" | 有它就不用密码 |
| 管理员密码 / 会话 Cookie | "meow 自己证明你是谁"（Docker、公网、直连） | 网关场景由会话替代 |
| 设备令牌 `X-Meow-Token` | "这台 TV/PC/脚本长期可信"（跨网、无 Cookie） | **要**，网关头到不了壳里 |
| 媒体票 `?mt=` | "URL 自带授权"（`<video>`、ExoPlayer） | 要 |
| configKey `?key=` | Fongmi/TVBox 只读配置 | 要 |

**判据**：能带 Cookie 的（浏览器）用网关身份；带不了 Cookie 的（TV/PC/脚本）用令牌。

### 3.3 多用户：先"分命名空间"，不做"多管理员"

`public-deployment.md` 现在写的是"单管理员，不做多用户"。接入网关头后建议改成：

- **数据按 `X-Trim-Userid` 分目录**：`<data>/users/<uid>/state.json`（历史/收藏/进度），默认库与配置仍全局；
- 权限仍**单管理员**（`isAdmin` 决定能不能改策略/导入源/改片库）；
- 不做"用户 A 看不到用户 B 的片库"——那是影视的 `item_user` 模型，规模不一样，先不做。

## 四、信任边界（这一节是红线）

### 4.1 `X-Trim-*` 只在 unix socket 上可信

```
fnOS 网关 ──unix socket──▶ meowd ──HTTP──▶ Java(ApiServer)
                            │
浏览器 ──TCP 30010──────────┘   ← 这里必须剥离 X-Trim-*
```

实现要求：

1. `meowd` 的两个 listener 分别挂不同中间件：
   - socket：保留 `X-Trim-*`，并加 `X-Meow-Ingress: fnos-gateway`；
   - TCP：**删除**客户端带来的 `X-Trim-Userid/Username/Isadmin`，加 `X-Meow-Ingress: tcp`；
2. Java 只认"`X-Meow-Ingress: fnos-gateway` 且带 `X-Trim-Userid`"的组合；
3. 日志里记录 `uid`（审计），但**不记录** Cookie/令牌。

### 4.2 30010 默认收回回环

现状 `MEOW_SHELL_BIND` 默认 `0.0.0.0`，等于给网关开了后门。

> Java 侧不是缺口：`cmd/main` 起 meowd 时就传了 `--api-bind=127.0.0.1`
> （`supervisor.rs` 再用 `MEOW_BIND` 传给 Java），30011 本来就只在回环。
> 所以"绕过网关"只发生在 **30010** 这一个端口上。

建议：

| 场景 | `MEOW_SHELL_BIND` | 结果 |
|---|---|---|
| fpk 默认（推荐） | `127.0.0.1` | 只能走网关；TV 远程靠令牌 + 反代 |
| 明确要直连 | `0.0.0.0` + `MEOW_AUTH_MODE=on` | 走账号密码 |
| 开发/调试 | `0.0.0.0` + 无 auth | **打警告日志**：网关身份已失效 |

> 这条与 [issue #4](https://github.com/upchr/meow/issues/4) 是同一件事的两面：
> 外网访问的前置不是"加登录页"，而是"先关掉绕过网关的入口"。

### 4.3 头注入的连带风险

`baseUrlFor()` 用 `Host` / `X-Forwarded-Proto` / `Origin` / `Referer` 拼对外地址
（fnOS 网关会丢端口，必须这么拼）。这些头是**客户端可控**的：

- 现有实现只在"主机名一致"时才采用 `Origin/Referer` 的 authority（`externalOrigin()`），已经比裸用 `Host` 好；
- 建议再加一个 `MEOW_PUBLIC_BASE`（显式声明对外地址）作为最高优先级；
- 公网部署时，反代必须覆盖 `Host` / `X-Forwarded-*`，不能透传客户端的值。

## 五、三条链路的完整时序

### 5.1 浏览器经 fnOS 网关（同机）

```
浏览器 → fnOS 5666 /app/meow/  （fnOS 已登录）
       → 网关注入 X-Trim-Userid/Username/Isadmin
       → meowd(meow.sock) 保留头，加 X-Meow-Ingress: fnos-gateway
       → Java：查 fnos 身份表 → 建会话 Cookie → 302 回原地址
       → 之后同源请求走 Cookie（媒体流同源自动带）
```

要点：**一次跳转**完成免密；不做"静默 401 再登录"的两段。

### 5.2 浏览器直连 30010（局域网 / 公网）

没有网关头 → 走 auth v2：账号密码 → 会话 Cookie（TLS 下 `Secure`）。
直连场景**永远不读** `X-Trim-*`（即使客户端伪造）。

### 5.3 后端对外（非浏览器）

| 调用方 | 凭证 | 备注 |
|---|---|---|
| TV 壳 / PC 壳（连远后端） | `X-Meow-Token` 设备令牌 | 配对码发放；播放另用 `mt` |
| Fongmi / TVBox | `configKey` | 只读 `/config.json`、`/spider-latest.jar` |
| 脚本 / AI / 第三方 | 设备令牌（建议只读 + 过期） | 不给 Cookie 会话 |
| 网关内的 WebView（fnOS 移动端套壳） | 同 5.1 | 只要它走网关 socket 就带得上头 |

**没有"后端对外用网关身份"这条路**——网关身份是浏览器 + 同机网关的产物，
离开那条链路就不存在。

## 六、接入顺序（编号，供点单）

1. `meowd` 双 listener 分权 + 剥离 `X-Trim-*`（安全地基，先做）
2. Java 读 `X-Meow-Ingress` + `X-Trim-Userid`，实现 fnOS 身份表与会话自动建立
3. `MEOW_SHELL_BIND` 默认改 `127.0.0.1`，并提供"直连需显式开启"的开关
4. 数据按 uid 分命名空间（历史/收藏/进度）
5. `MEOW_PUBLIC_BASE`（对外地址钉死）
6. 更新 `public-deployment.md` §10.2 / §10.4：把"网关后还要二次登录"改成"网关免密 + 会话"

## 七、验收断言

```bash
# 1) 走网关：不登录 meow，直接能进（fnOS 已登录）
curl -s -D- http://<nas>:5666/app/meow/api/health | grep -i set-cookie   # 应有会话

# 2) 伪造网关头走 TCP：必须不生效
curl -s -H 'X-Trim-Userid: 1000' -H 'X-Trim-Isadmin: true' \
     http://<nas>:30010/api/state/history                                 # 未登录应 401

# 3) 直连 + 账号密码（MEOW_AUTH_MODE=on）
curl -s -c /tmp/c.txt -X POST -d '{"user":"admin","pass":"..."}' \
     http://<nas>:30010/api/auth/login

# 4) 设备令牌
curl -s -H 'X-Meow-Token: <token>' http://<nas>:30010/api/state/history
```

- [ ] 网关进来后，`/api/state/*` 按 uid 隔离（两个 fnOS 用户互不可见）
- [ ] `X-Trim-Isadmin=false` 的用户调 `POST /api/policy` 返回 403
- [ ] 30010 默认不再对局域网开放（`ss -lntp` 只有 127.0.0.1）
- [ ] 日志里能看到 `uid`，但看不到 Cookie / 令牌原文

## 八、明确不做

| 不做 | 原因 |
|---|---|
| 抄影视的 `trim.accountsrv.sock` / `GetAppAuthorizedDir` | 私有契约，无公开文档，版本一变就断 |
| 把 fpk 改成 `run-as: root` | 一改整套安全模型作废；授权目录 + ACL 已经够用 |
| 用网关身份替代设备令牌 | TV/PC 拿不到网关头 |
| 多管理员 / 按库授权用户 | 规模不匹配；先做 uid 命名空间 |

## 九、落地记录（2026-11-20）

按 §六 的顺序全部落地，分支 `feat/public-auth`。逐条对应：

| 项 | 落点 | 状态 |
|---|---|---|
| 双 listener 分权 | `meowd/src/http.rs`：`set_peer_addr`（TCP：删 `X-Trim-*` + 覆盖 `X-Meow-Ingress: tcp`）、`mark_gateway_ingress`（socket：保留头 + `fnos-gateway`） | ✅ |
| Java 只认组合 | `core/FnosIdentity.java` 的 `from()`：`ingress=fnos-gateway` **且** `uid` 纯数字（≤12 位），否则 null | ✅ |
| 免密会话 | `Auth.sessionForFnos()`：按 uid 复用同一个 sid（复用前同步 name/admin），`ApiServer.authenticateOrFnos()` 只在 `no_credentials` 时兜底 | ✅ |
| 权限分级 | `Auth.isAdminContext()` + `ApiServer.adminOnlyWrite()`：读写都放行，改策略/片库/导源/直播源/缓存/插件/LLM 要管理员 | ✅ |
| state 按 uid 分 | `Auth.stateScope()` → `StateRoutes.f()`：`users/<uid>/state-*.json` | ✅ |
| 30010 默认回环 | `fpk/cmd/main`：`MEOW_SHELL_BIND` 默认改 `127.0.0.1`，非回环且未开鉴权时启动日志打 warn | ✅ |
| `MEOW_PUBLIC_BASE` | `ApiServer.baseUrlFor()` 最高优先级，设了就完全不读请求头 | ✅ |

**实测**（NAS `.34`，新 meowd + 新 jar）：

```text
伪造 X-Trim-Userid + 伪造 X-Meow-Ingress 走 TCP 30010     → 401
网关身份（ingress+uid）GET /api/auth/me                   → 200 + Set-Cookie，user=fnosuser
同上但缺 ingress 头                                        → 401
非管理员 POST /api/policy                                  → 403
非管理员 GET /api/library                                  → 200
非管理员 POST /api/state/progress                          → 200
管理员 POST /api/policy                                    → 200
uid=1002 写进度 → uid=1003 读                              → 空（落盘 users/1002/state-progress.json）
```

**与设计的偏差**

1. **`stateScope` 无身份时用全局文件，不是"拒绝"**。§3.3 写的是"无身份时拒绝"，但那样会让
   Docker / PC / TV 的既有历史全部读不到（它们没有 uid）。改成"有 uid 就分目录、没有就沿用全局"，
   兼容与隔离同时成立。
2. **`X-Meow-Ingress` 由 meowd 覆盖而非追加**。客户端自带的值一律作废 —— 否则伪造 ingress
   配合伪造 uid 就能冒充网关。
3. **管理类写操作清单是白名单**（`adminOnlyWrite`），不用"所有写操作"：`/api/state/*` 必须放行，
   否则普通用户连自己的续播都写不进去。

**已知限制**

1. **fnOS 网关不转发 `X-Forwarded-For`**（实测：连客户端自带的伪造值都被清洗），所以走 5666
   网关时后端看到的来源 IP 恒为 `127.0.0.1` —— 限流、`lastIp`/`bindIp`、媒体票 IP 绑定在这一形态下
   退化。要真实 IP 就用 30010 直连 + 自己的 TLS 反代（见 `public-deployment.md` §19.6）。
2. **socket 权限是信任前提**：`meow.sock` 在应用目录下（`srwxr-xr-x`），同机其它用户理论上能连并
   伪造 `X-Trim-*`。fpk 的部署模型里同机用户已经是管理员级信任，但这一点成立与否取决于
   fnOS 目录权限，**未做进一步收紧**（收紧可能断掉网关注入）。
3. **免密会话不随 fnOS 登出立即失效**：会话 TTL 30 天，`fnos_login` 事件会记录 uid。用户从
   fnOS 登出后 meow 侧仍能用到会话过期 —— 网关形态下"登出 fnOS"通常等于离开页面，风险可接受。
