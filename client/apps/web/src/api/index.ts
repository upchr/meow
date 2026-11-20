import type {
  CategoryResult,
  DetailResult,
  HomeResult,
  PlayResult,
  SearchGroup,
  SearchResult,
  Site,
} from './types'
// 只借类型：HistoryItem 是进度状态的真源形状，另定义一份会漂。
import type { HistoryItem } from '../stores/history'
import { isLoopbackUrl } from '@/utils/loopback'
import { getPrefs } from '../stores/prefs'
import { enabledProxyPrefixes } from './gitAccel'
import { isDesktopShell, isTvShell } from '@/compat/shell'

export { isLoopbackUrl }

/**
 * 后端 API 客户端。
 *
 * - Web 开发：Vite 代理把 /api 转到后端，base 留空即可
 * - Web 生产：同源部署在 Nginx 后面，base 留空
 * - Tauri 桌面：页面是 file://，必须用绝对地址。**端口由插件管理器协商得出**
 *   （9979 被占用时会顺延到 9980/9981…），所以不能再写死 9979。
 *
 * 取值优先级：
 *   1. localStorage['meow.api']   —— 用户在设置页手填的地址，最高优先
 *   2. Tauri boot_info 返回的 backend_base —— 插件实际监听的端口
 *   3. VITE_API_BASE               —— 构建期注入
 *   4. 空串（同源）
 */
/**
 * 桌面端后端地址，由 `initBackend()` 从 Tauri 插件管理器拿。
 *
 * 之所以是模块级可变变量而不是 const：插件是**异步启动**的（JVM 要几秒），
 * 模块加载时还没 READY，拿不到端口。启动流程见 `initBackend()`。
 */
let desktopBase = ''

function resolveBase(): string {
  // 桌面端的地址由插件协商决定，**不能被历史遗留的手填地址劫持**。
  //
  // 实测踩坑：开发期在 Web 端调试时往 localStorage 写过
  // `meow.api = http://127.0.0.1:30011`，桌面版 WebView 复用了同一份
  // localStorage，导致视频流走了那个旧后端（0.3.3），而不是插件起的 9980。
  // 桌面端要手动指定地址，应走「设置 → 后端地址」，那条路径会同时写
  // localStorage 并打上 scope 标记（见 setRuntimeBase）。
  if (isDesktopShell()) {
    const manual = readManualForDesktop()
    if (!manual) return desktopBase
    /*
     * 手填的是回环地址时，插件协商出来的地址更权威。
     *
     * 典型场景：9979 被占用 → 插件顺延到 9980，而用户早先存的手填地址
     * 还钉在 9979。继续优先手填值会把「后端已启动」误判成「后端未连接」。
     * 如果用户真的想让 PC 连另一个本机服务（如 Docker 的 30011），
     * 正确做法是把本地后端插件关掉；关掉后 desktopBase 为空，这里仍用
     * 手填地址。
     */
    if (isLoopbackUrl(manual) && desktopBase && manual !== desktopBase) {
      return desktopBase
    }
    return manual
  }

  const runtime =
    typeof localStorage !== 'undefined' ? localStorage.getItem('meow.api') : null
  const scope =
    typeof localStorage !== 'undefined' ? localStorage.getItem('meow.api.scope') : null
  // 裸 `meow.api` 只有两种来源可信：
  //   1) 设置页保存过（同时写了 `meow.api.scope`）；
  //   2) TV 壳的自动引导（`window.meowTv.bootstrapLocal`，历史行为不带 scope）。
  // 其余一律忽略：同一浏览器里 Web 调试留下的旧地址会把 fnOS 子路径入口
  // （/app/meow）的请求劫持到外网后端。
  if (runtime && (scope || isTvShell())) return runtime.replace(/\/$/, '')
  if (import.meta.env.VITE_API_BASE) return String(import.meta.env.VITE_API_BASE).replace(/\/$/, '')
  // 兜底：其他非 http(s) 的自定义协议环境
  if (typeof location !== 'undefined' && !/^https?:$/.test(location.protocol)) {
    return desktopBase
  }
  return ''
}

/**
 * 桌面端的手填地址。
 *
 * 只有用户在**桌面端**设置页保存过（写过 `meow.api.scope = 'desktop'`）
 * 才算数；否则一律忽略——避免 Web 调试期的残留把桌面端指到错误端口。
 */
function readManualForDesktop(): string {
  if (typeof localStorage === 'undefined') return ''
  const scope = localStorage.getItem('meow.api.scope')
  if (scope !== 'desktop') return ''
  const v = localStorage.getItem('meow.api')
  return v ? v.replace(/\/$/, '') : ''
}

/** base 现在是动态的：每次请求都重算，因为桌面端端口可能晚于首次渲染才就绪。 */
function base(): string {
  return resolveBase()
}

/**
 * 桌面端后端地址是否已确定。
 *
 * ★ 这个判断不能省，否则会出现"幽灵后端"：
 *   桌面端开发模式下页面是从 `http://localhost:5173` 加载的（devUrl），
 *   `__TAURI_INTERNALS__` 已经注入 → isDesktopShell() 为真 → 但插件还没就绪时
 *   desktopBase 是空串 → `fetch('' + '/api/sites')` 变成**相对地址**，
 *   于是请求打到 5173，被 Vite 的 `/api` 代理转发到 127.0.0.1:9979 …
 *   结果是：应用显示"已连接 · 39 源"，而那其实是另一个独立跑着的后端，
 *   用户完全不知道数据从哪来（实测踩过，截图里就是这个现象）。
 *
 * 所以桌面端只要还没拿到确定地址，就必须报错，绝不退化成同源请求。
 */
function backendUnavailable(): boolean {
  return isDesktopShell() && !resolveBase()
}

// ---------------------------------------------------------------- 启动引导

export interface PluginInfo {
  id: string
  name: string
  required: boolean
  fatal: boolean
  phase: 'stopped' | 'starting' | 'ready' | 'failed'
  port: number
  base: string
  error?: string | null
  installed: boolean
  version: string
  /** 用户是否希望它随应用启动（存 enabled.json，不是 manifest 的 autostart） */
  enabled: boolean
  /** 工具型插件（只提供文件，不作为进程运行，如 node 运行时） */
  tool: boolean
}

export interface BootInfo {
  backend_base: string | null
  plugins: PluginInfo[]
  ok: boolean
  fatal_reason: string | null
  /**
   * 桌面应用的真实版本（来自 Rust 的 CARGO_PKG_VERSION）。
   *
   * 插件安装要用它校验 `min_app_version`。不能用构建期注入的
   * `__APP_VERSION__`——那个读的是 Web 应用自己的 package.json（0.2.x），
   * 与桌面端版本号是两套，会导致"需要 0.6.0 或更高（当前 0.2.0）"的误判。
   */
  app_version?: string
}

/**
 * 桌面端启动时调用：向 Tauri 要插件状态，把实际端口记进 `desktopBase`。
 *
 * 插件是异步启动的，这里会**轮询等待** required 插件就绪（最多 ~50s），
 * 期间前端应显示"正在启动后端"而不是直接报错。
 *
 * Web 端调用是无害的空操作。
 */
/**
 * 直接调用 Tauri 的 invoke，不依赖 `@tauri-apps/api` 包。
 *
 * 为什么不用官方包：web 应用是独立的 pnpm workspace，装 `@tauri-apps/api`
 * 会让 Web 构建多一个只有桌面端才需要的依赖；而 Tauri v2 注入的
 * `window.__TAURI_INTERNALS__.invoke` 正是官方包的底层实现。
 */
export async function tauriInvoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  const w = window as unknown as {
    __TAURI_INTERNALS__?: { invoke?: (c: string, a?: unknown) => Promise<unknown> }
    __TAURI__?: { core?: { invoke?: (c: string, a?: unknown) => Promise<unknown> } }
  }
  const fn = w.__TAURI_INTERNALS__?.invoke || w.__TAURI__?.core?.invoke
  if (!fn) throw new Error('Tauri invoke 不可用')
  return (await fn(cmd, args)) as T
}

export async function initBackend(
  onProgress?: (msg: string) => void,
): Promise<BootInfo | null> {
  if (!isDesktopShell()) return null

  const deadline = Date.now() + 60_000
  let last: BootInfo | null = null
  // 用户手填了地址就走远端，不必等本地 sidecar（可能用户压根不想启本地后端）
  const manual = readManualForDesktop()
  while (Date.now() < deadline) {
    try {
      const info = await tauriInvoke<BootInfo>('boot_info')
      last = info
      if (info.backend_base) {
        desktopBase = info.backend_base.replace(/\/$/, '')
        return info
      }
      /*
       * 只有**远端**手填地址才值得立刻返回（用户可能压根不想启本地后端）。
       * 回环地址（127.0.0.1 / localhost）很可能就是本地插件自己的地址，
       * 必须继续等它 bind —— 否则页面会在插件就绪前先 ping 一次，
       * 闪出「后端未连接」（2026-11-09 用户报「要点两次重试」）。
       */
      if (manual && !isLoopbackUrl(manual)) return info
      // 桌面端只有「用户在桌面端设置页保存过」的地址才算数（scope=desktop）。
      // 不能用历史残留的 bare meow.api —— 那会把 desktopBase 一直留空，
      // 结果是首页显示"后端未连接"（实测踩过）。
      const nowManual = readManualForDesktop()
      if (nowManual && !isLoopbackUrl(nowManual)) return info
      const backend = info.plugins.find((p) => p.required)
      // ★ 完全没装后端插件：立刻返回。
      //   否则会在这里死等 60 秒（界面卡在"正在启动后端…"），
      //   而用户真正该做的是去设置页下载插件。
      if (!backend) return info
      // 用户主动关掉了本地后端：立刻返回，不要傻等
      if (!backend.enabled) return info
      // 启动失败也立刻返回，让 UI 显示错误
      if (backend.phase === 'failed') return info
      onProgress?.(
        `正在启动${backend.name}…（${backend.port || '准备中'}）`,
      )
    } catch (e) {
      last = { backend_base: null, plugins: [], ok: false, fatal_reason: String(e) }
    }
    await new Promise((r) => setTimeout(r, 600))
  }
  return last
}

/** 供设置页展示/调试 */
export function currentBase(): string {
  return resolveBase()
}

/**
 * 重新从插件管理器取一次后端地址（**不轮询**，取到就用）。
 *
 * <p><b>为什么需要它</b>：`initBackend()` 只在启动时跑一次，而且会"提前放弃"——
 * 插件没装、被禁用、或启动失败时它立刻返回，`desktopBase` 就一直是空串。
 * 之后用户在设置页把插件装上/启动起来，页面**不会自己知道**：
 * `store.boot()` 仍然拿空 base 去 ping，首页继续 `Failed to fetch`，
 * 只能靠手动刷新页面才恢复（实测踩过：升级插件后首页空白）。
 *
 * <p>所以在"插件装好/启动完"这类动作之后调一次本函数，再 boot。
 * 与 `initBackend()` 的分工：那边负责**等**（最长 60 秒、带进度），
 * 这里只负责**取**（一次 boot_info，失败保持原值）。
 */
export async function refreshDesktopBase(): Promise<string> {
  if (!isDesktopShell()) return desktopBase
  try {
    const info = await tauriInvoke<BootInfo>('boot_info')
    const b = info?.backend_base
    if (b) desktopBase = String(b).replace(/\/$/, '')
  } catch {
    /* 取不到就沿用旧值：调用方接着 boot() 会把失败原因显示出来 */
  }
  return desktopBase
}

/**
 * 取一次 boot_info（不等待插件就绪，纯读取当前状态）。
 *
 * 设置页用它拿真实的应用版本号——见 BootInfo.app_version 的说明。
 * Web 端返回 null。
 */
export async function bootInfo(): Promise<BootInfo | null> {
  if (!isDesktopShell()) return null
  try {
    return await tauriInvoke<BootInfo>('boot_info')
  } catch {
    return null
  }
}

/**
 * 用户手填的后端地址（仅当来源与当前端匹配时返回）。
 *
 * 设置页要据此区分「插件托管」和「手连远端」两种状态；
 * 桌面端只认 scope=desktop 的值，避免 Web 调试残留被误判成用户设置。
 */
export function readManualApi(): string {
  if (typeof localStorage === 'undefined') return ''
  if (isDesktopShell()) return readManualForDesktop()
  return localStorage.getItem('meow.api') || ''
}

/** 设置页改了地址后调用，让 base 立即生效 */
export function setRuntimeBase(url: string | null) {
  if (typeof localStorage === 'undefined') return
  if (url && url.trim()) {
    localStorage.setItem('meow.api', url.trim())
    // 打上来源标记：只有桌面端主动保存的地址才在桌面端生效，
    // 避免 Web 调试期的残留污染桌面版（见 resolveBase 的注释）
    localStorage.setItem('meow.api.scope', isDesktopShell() ? 'desktop' : 'web')
  } else {
    localStorage.removeItem('meow.api')
    localStorage.removeItem('meow.api.scope')
  }
  /*
   * 广播一次：清空地址时 App 层要尝试「零配置自启」（TV 用内置 jar 起网关），
   * 否则用户清掉地址后只会看到「后端未连接」，得再去设置页点三个按钮。
   * 用自定义事件而不是 storage 事件：storage 只在**其他标签页**触发。
   */
  try {
    window.dispatchEvent(new CustomEvent('meow:apibase', { detail: url }))
  } catch {
    /* 无 window 环境（SSR/测试）忽略 */
  }
}

/**
 * `/api/health` 的 8 秒合并缓存。
 *
 * 判据：一次打开设置页至少打两次（顶栏连接状态 + 「连接」分区 + 启动时的
 * 能力探测），而健康状态本来就是秒级变化，8 秒内重复真的不会看到差别。
 * 合并的是**同一份数据**，不是"跳过刷新"：过期后照常重新请求。
 */
let healthCache: { at: number; data: unknown } | null = null
let healthInflight: Promise<unknown> | null = null

const HEALTH_TTL_MS = 8_000

/**
 * 轻量 HTTP 封装。
 *
 * 不用 ofetch：它在 Tauri 的 `tauri://localhost` 协议下会访问
 * `location[0]` 而抛 `Cannot read properties of undefined (reading '0')`。
 * 直接用原生 fetch，行为在 Web/桌面一致。
 */
async function request<T>(
  path: string,
  options: { method?: string; body?: unknown; timeout?: number; keepalive?: boolean } = {},
): Promise<T> {
  const { method = 'GET', body, timeout = 60_000, keepalive } = options
  const isHealth = method === 'GET' && path === '/api/health'
  if (isHealth) {
    const hit = healthCache
    if (hit && Date.now() - hit.at < HEALTH_TTL_MS) return hit.data as T
    if (healthInflight) return (await healthInflight) as T
  }
  const p = requestUncached<T>(path, { method, body, timeout, keepalive })
  if (!isHealth) return p
  healthInflight = p.then(
    (data) => {
      healthCache = { at: Date.now(), data }
      healthInflight = null
      return data
    },
    (e) => {
      // 失败不缓存：否则一次抖动会让"已连接"卡住 8 秒
      healthInflight = null
      throw e
    },
  )
  return (await healthInflight) as T
}

/** 401 的统一出口：由 auth store 注册，跳登录页；避免 api 层 import router。 */
let unauthorizedHandler: ((reason: string) => void) | null = null

export function setUnauthorizedHandler(fn: ((reason: string) => void) | null): void {
  unauthorizedHandler = fn
}

/**
 * 分体部署的跨源 Cookie 会话开关（auth v2）。
 *
 * <p>web 前端在 A 域、API 直连 B:30011 时，登录后的会话 Cookie 存在 API 域上，
 * 所有 `request()` 都要 `credentials:'include'` 才能带上；后端必须在
 * `MEOW_CORS` 里显式白名单 web 的 Origin（会回 Allow-Credentials）。
 * 由 auth store 在拿到状态后设置 —— api 层不 import store（会循环）。
 *
 * <p>默认 false：同源部署 / 令牌模式零变化（`*` + credentials 被浏览器禁止）。
 */
let crossOriginCredentials = false

export function setCrossOriginCredentials(v: boolean): void {
  crossOriginCredentials = !!v
}

export class ApiHttpError extends Error {
  status: number
  /**
   * 401 的具体原因（后端 `unauthorizedJson` 给的枚举）：
   * `no_credentials` / `session_expired` / `password_changed` /
   * `token_invalid` / `token_expired` / `ip_mismatch`。
   * 登录页据此告诉用户"为什么被踢出来"，而不是一律"请重新登录"。
   */
  reason: string

  constructor(message: string, status: number, reason = '') {
    super(message)
    this.status = status
    this.reason = reason
  }
}

async function requestUncached<T>(
  path: string,
  options: { method?: string; body?: unknown; timeout?: number; keepalive?: boolean } = {},
): Promise<T> {
  const { method = 'GET', body, timeout = 60_000, keepalive } = options
  // 桌面端地址未定时不要发同源请求（会命中 dev 服务器代理，连到不该连的后端）
  if (backendUnavailable()) {
    throw new Error('后端尚未就绪：请稍候，或到「设置 → 本机插件」检查影视后端是否已安装并启动')
  }
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)
  try {
    // 写操作令牌：后端设了 authToken 才需要（见 policy.json）。
    // 每个请求都带——GET 上它被后端忽略，省掉"哪些请求要带"的分支。
    const token =
      typeof localStorage !== 'undefined' ? localStorage.getItem('meow.authToken') : null
    const headers: Record<string, string> = {}
    if (body) headers['Content-Type'] = 'application/json'
    if (token) headers['X-Meow-Token'] = token
    const init: RequestInit = {
      method,
      headers: Object.keys(headers).length ? headers : undefined,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
      // 分体部署（web 在 A 域、api 直连 B:30011）：密码模式的会话 Cookie 在
      // API 域上，跨源 fetch 必须 include 才能带上 —— 前提是后端 MEOW_CORS
      // 显式白名单了这个来源（此时后端会回 Allow-Credentials）。
      // 默认 same-origin：令牌模式 / 同源部署零变化（* + credentials 会被
      // 浏览器拒绝，不能全局 include）。
      credentials: crossOriginCredentials ? 'include' : 'same-origin',
      // keepalive：pagehide 时发的请求能活过页面卸载。
      // 没有它，关窗口前的最后一次进度推送会被浏览器直接砍掉（实测表现是
      // 下次打开还停在旧位置）。
      ...(keepalive ? { keepalive: true } : {}),
    }
    let res: Response
    try {
      res = await fetch(base() + path, init)
    } catch (e) {
      /*
       * 网络层失败重试一次。
       *
       * 踩过（2026-11-20 实测）：后端重启（升级 / Docker 重建 / NAS 重启）那几秒
       * 里打开首页，所有请求都是 Failed to fetch，页面停在「连不上后端，点刷新再试」
       * ——用户以为坏了。后端起来通常只需 1–3 秒，这里等 600ms 重试一次能吃掉
       * 大部分窗口；真正的断网最多多等 600ms 就报错。
       * 超时（AbortController）不重试：那是慢，不是断。
       */
      if (controller.signal.aborted) throw e
      await new Promise((r) => setTimeout(r, 600))
      res = await fetch(base() + path, init)
    }
    if (!res.ok) {
      const text = await res.text().catch(() => '')
      // 后端异常时可能返回非 JSON（空响应 / HTML 错误页），
      // 直接 res.json() 会抛 "Unexpected end of JSON input"，
      // 对用户毫无信息量，这里尽量把真实原因带出来
      let detail = text
      let reason = ''
      try {
        const j = JSON.parse(text)
        detail = j?.msg || j?.message || text
        reason = typeof j?.reason === 'string' ? j.reason : ''
      } catch {
        /* 不是 JSON 就原样展示 */
      }
      // /api/stream/ticket 在**未开鉴权**的部署上必然 401（端点存在但没有凭证体系）。
      // 玩家进播放页会无条件预取这张票，如果把它当成"会话过期"跳登录，
      // 所有局域网/TV 本机网关部署一播放就被弹到登录页（2026-11-19 复查发现）。
      /*
       * 静默的 auth 端点：它们本身就是"未登录"的常态，401 是预期结果。
       *
       * ★ `/api/auth/me` **不在静默名单里**（2026-11-20 实测）：设备令牌被撤销
       *   后，TV 界面靠本地缓存照样能打开首页，用户以为还能用，点什么都失败；
       *   而 me 的 401 被静默吞掉，永远不跳登录页。放到会触发跳转的那一侧，
       *   启动时的鉴权检查才能把"令牌已失效"变成登录页上的一句话。
       */
      const quietAuth =
        path.startsWith('/api/auth/status') ||
        path.startsWith('/api/auth/login') ||
        path.startsWith('/api/auth/setup') ||
        path.startsWith('/api/auth/logout') ||
        path.startsWith('/api/auth/pair/')
      if (res.status === 401 && !quietAuth && !path.startsWith('/api/stream/ticket')) {
        unauthorizedHandler?.(reason)
      }
      throw new ApiHttpError(
        `[${method}] ${path} → ${res.status} ${detail.slice(0, 200) || res.statusText}`,
        res.status,
        reason,
      )
    }
    const raw = await res.text()
    if (!raw) throw new Error(`[${method}] ${path} → 后端返回空响应（服务可能正在重启）`)
    try {
      return JSON.parse(raw) as T
    } catch {
      throw new Error(`[${method}] ${path} → 响应不是 JSON：${raw.slice(0, 160)}`)
    }
  } finally {
    clearTimeout(timer)
  }
}

export { request }

/** 后端返回的站点列表（含健康状态） */
export async function fetchSites(): Promise<Site[]> {
  const r = await request<{ sites: Site[] }>('/api/sites')
  return r.sites
}

export async function fetchHome(site: string): Promise<HomeResult> {
  return request<HomeResult>(`/api/${encodeURIComponent(site)}/home`, {
    method: 'POST',
    body: {},
  })
}

export async function fetchCategory(
  site: string,
  tid: string,
  page = 1,
  filters: Record<string, string> = {},
): Promise<CategoryResult> {
  return request<CategoryResult>(`/api/${encodeURIComponent(site)}/category`, {
    method: 'POST',
    body: { id: tid, page: String(page), filters },
  })
}

export async function fetchDetail(site: string, id: string): Promise<DetailResult> {
  return request<DetailResult>(`/api/${encodeURIComponent(site)}/detail`, {
    method: 'POST',
    body: { id },
  })
}

export async function fetchSearch(
  site: string,
  wd: string,
  page = 1,
): Promise<SearchResult> {
  return request<SearchResult>(`/api/${encodeURIComponent(site)}/search`, {
    method: 'POST',
    body: { wd, pg: String(page) },
  })
}

/** 多站聚合搜索。threads 控制后端并发数（默认 12）。 */
export async function fetchSearchAll(
  wd: string,
  sites?: string[],
  threads?: number,
  refresh = false,
): Promise<{ groups: SearchGroup[] }> {
  return request('/api/search', { method: 'POST', body: { wd, sites, threads, refresh } })
}

/**
 * 异步聚合搜索：启动任务，立刻拿 taskId。
 *
 * <p>为什么不直接等结果：请求线程池只有 12 个（后端 `POOL`），一次同步搜索
 * 要占住一个 2~20 秒。实测 12 个并发搜索就能把池占满，`/api/health`
 * 从毫秒级涨到 **9.1 秒** —— 用户看到的是"整个应用卡死"。
 *
 * <p>`refresh` = 绕过结果缓存、强制真跑全部源。搜索页的「重新搜索」
 * 按钮用它；普通搜索走缓存（同词第二次是毫秒级）。
 */
export async function startSearch(
  wd: string,
  threads?: number,
  refresh = false,
): Promise<{ taskId: string; total: number; done: boolean }> {
  return request('/api/search', {
    method: 'POST',
    body: { wd, threads, async: true, refresh },
  })
}

/** 搜索结果缓存统计（设置页展示用）。 */
export interface SearchCacheStats {
  size: number
  max: number
  fresh: number
  stale: number
  ttlHitSec: number
  ttlMissSec: number
  ttlFailSec: number
}

export async function fetchSearchCacheStats(): Promise<SearchCacheStats> {
  return request<SearchCacheStats>('/api/search/cache')
}

/** 清空搜索结果缓存，返回清掉的条数。 */
export async function clearSearchCache(): Promise<{ ok: boolean; cleared: number }> {
  return request('/api/search/cache', { method: 'DELETE' })
}

/** 搜索任务快照。`groups` 是**当前已完成**的部分结果，可以边搜边显示。 */
export interface SearchSnapshot {
  taskId: string
  done: boolean
  returned: number
  total: number
  count: number
  /** 本轮因命中噪音词被后端剔除的条目数（0 = 没开过滤或没命中） */
  noiseFiltered?: number
  /** 「仅排序降级」模式下被标记沉底的条数（filter 模式恒为 0） */
  noiseDowngraded?: number
  groups: SearchGroup[]
  error?: string
}

export async function fetchSearchSnapshot(taskId: string): Promise<SearchSnapshot> {
  return request<SearchSnapshot>(`/api/search/task/${encodeURIComponent(taskId)}`)
}

/**
 * 等一次聚合搜索跑完，返回完整结果。
 *
 * <p>给 AI 工具层用：它需要**完整**结果才能判断"哪部片是用户要的"，
 * 不能只拿首屏那几条。但它也不该走同步接口——同步搜索会占住一个请求线程
 * 5~8 秒，而请求池只有 12 个，AI 对话期间用户切到搜索页会互相拖慢。
 *
 * <p>所以是"起异步任务 + 轮询到 done"，与搜索页同一套机制，只是不等增量、
 * 只等最终态。超时兜底 45 秒（后端总预算 30 秒 + 余量）。
 */
export async function fetchSearchAllWait(
  wd: string,
  threads = 8,
  timeoutMs = 45_000,
): Promise<{ groups: SearchGroup[] }> {
  const task = await startSearch(wd, threads)
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const snap = await fetchSearchSnapshot(task.taskId)
    if (snap.done) {
      if (snap.error) throw new Error(snap.error)
      return { groups: snap.groups || [] }
    }
    await new Promise((r) => setTimeout(r, 400))
  }
  throw new Error('搜索超时（后端未在 45 秒内完成）')
}

export async function fetchPlay(
  site: string,
  flag: string,
  id: string,
): Promise<PlayResult> {
  return request<PlayResult>(`/api/${encodeURIComponent(site)}/play`, {
    method: 'POST',
    body: { flag, id },
  })
}

/** 直播频道：CatVod 的 `urls` 里可能带 `$备用名` 后缀，由前端拆 */
export interface LiveChannel {
  name?: string
  urls?: string[]
  /**
   * 与 urls 一一对应的来源名（多源合并时由 `liveMerge.ts` 填）。
   * 单源解析出来没有这个字段 —— 面板会用"线路 2/5"作为兜底显示。
   */
  srcs?: string[]
}
export interface LiveGroup {
  site: string
  site_name: string
  group: string
  channels: LiveChannel[]
}
export interface LiveResult {
  groups?: LiveGroup[]
  tried?: number
  failed?: number
  /** config.lives 里的直连 IPTV 播放列表（spider 型直播之外的另一种来源） */
  playlists?: Array<{ name: string; url: string }>
}

export interface LivePlaylistResult {
  ok?: boolean
  groups?: Array<{ group: string; channels: LiveChannel[] }>
  channels?: number
  msg?: string
  /**
   * 源的 Last-Modified（RFC1123，如 `Mon, 12 Oct 2026 03:26:19 GMT`）。
   * 用来显示「更新于 X 天前」—— 实测 Guovin 那份停在 10-12（25 天前），
   * 而名字还叫"每日更新"，界面上不标出来用户没法判断该不该换源。
   */
  updated?: string
}

/** 解析一条 IPTV 播放列表（m3u 的 group-title / txt 的 #genre# 都支持） */
export async function fetchLivePlaylist(url: string): Promise<LivePlaylistResult> {
  try {
    return await request<LivePlaylistResult>('/api/live/playlist', {
      method: 'POST',
      body: { url },
      timeout: 30_000,
    })
  } catch (e: any) {
    return { ok: false, msg: e?.message || '播放列表获取失败' }
  }
}

/**
 * 聚合所有站点的直播频道。
 *
 * 后端预算 12 秒，拿不到就返回已拿到的部分——所以这个接口"慢但一定有回音"，
 * 不会像逐站请求那样把页面卡死。
 */
export async function fetchLive(): Promise<LiveResult> {
  try {
    return await request<LiveResult>('/api/live', { method: 'POST', body: {}, timeout: 20_000 })
  } catch (e: any) {
    return { groups: [], tried: 0, failed: 0 }
  }
}

/** `/api/parse` 的结果。`from` 是解析器名，便于用户判断是谁解析的。 */
export interface ParseResponse {
  ok?: boolean
  url?: string
  header?: string
  from?: string
  msg?: string
  /** 本次实际尝试的解析器数量 */
  tried?: number
  /** 配置里需要浏览器、本次未尝试的数量 */
  skippedBrowser?: number
}

/**
 * 调后端解析器（type1 并发 / type4 fan-out）。
 *
 * 传的 `url` 是**播放页地址**，不是媒体地址——解析器返回的才是。
 * 老后端没有这个端点，404 时返回 `{ok:false}` 而不是抛，调用方据此降级。
 */
export async function fetchParse(
  url: string,
  flag = '',
  name = '',
): Promise<ParseResponse> {
  try {
    return await request<ParseResponse>('/api/parse', {
      method: 'POST',
      body: { url, flag, name },
    })
  } catch (e: any) {
    return { ok: false, msg: e?.message || '解析请求失败' }
  }
}

/**
 * 媒体票缓存（auth v2）。由 `api/mediaTicket.ts` 通过 /api/stream/ticket 刷新；
 * 这里只做同步读取，避免 proxyStream/libraryStream 变成 async（调用点多）。
 */
const MEDIA_TICKETS = new Map<string, string>()

export function setMediaTicket(kind: 'proxy' | 'library', mt: string, lib = ''): void {
  if (!mt) return
  MEDIA_TICKETS.set(`${kind}:${lib}`, mt)
}

export function mediaTicketFor(kind: 'proxy' | 'library', lib = ''): string {
  return MEDIA_TICKETS.get(`${kind}:${lib}`) || ''
}

/** 代理地址也要带上 base，否则桌面版会拼成 file:///api/... */
export function proxyStream(
  url: string,
  header?: Record<string, string> | string,
  opts?: { base?: string; token?: string },
): string {
  let h: Record<string, string> = {}
  if (typeof header === 'string') {
    try {
      h = JSON.parse(header)
    } catch {
      h = {}
    }
  } else if (header) {
    h = header
  }
  // 源站的鉴权头不止 Referer/UA：Cookie、Origin、自定义 token 都很常见。
  // 原生播放器（ExoPlayer）拿的是全部头，只转两个的话这类源会在 Web 端
  // 永久 403 —— 用户看到的是"同一条线路原生能播、我们播不了"。
  // 后端按白名单过滤并限制总长，这里只负责拼参数。
  const extra: Record<string, string> = {}
  for (const [k, v] of Object.entries(h)) {
    if (typeof v !== 'string' || !v) continue
    const lk = k.toLowerCase()
    if (lk === 'referer' || lk === 'user-agent') continue
    extra[k] = v
  }
  const params = new URLSearchParams({ url })
  if (h.Referer || h.referer) params.set('referer', h.Referer || h.referer)
  if (h['User-Agent'] || h['user-agent'])
    params.set('ua', h['User-Agent'] || h['user-agent'])
  if (Object.keys(extra).length) params.set('headers', JSON.stringify(extra))
  const ct = opts?.token ? `&ct=${encodeURIComponent(opts.token)}` : ''
  const mt = mediaTicketFor('proxy')
  const mtq = mt ? `&mt=${encodeURIComponent(mt)}` : ''
  return `${opts?.base || base()}/api/proxy/stream?${params.toString()}${ct}${mtq}`
}

/** 投屏旁路基地址 + 一次性令牌。 */
export interface CastBase {
  base: string
  token: string
}

/**
 * 投给第三方设备前剔除敏感头。
 *
 * <p>投屏 URL 会被电视和沿途日志看到；Cookie / Authorization 一旦进 query 就是泄露。
 * 需要这些头的源在投屏场景下会失败——这是有意的取舍，后续可改成服务端 ticket
 * （URL 里只带 ticket，头留在后端）。
 */
export function sanitizeCastHeader(
  header?: Record<string, string> | string,
): Record<string, string> | undefined {
  let h: Record<string, string> = {}
  if (typeof header === 'string') {
    try {
      h = JSON.parse(header)
    } catch {
      h = {}
    }
  } else if (header) {
    h = header
  }
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(h)) {
    if (typeof v !== 'string' || !v) continue
    const lk = k.toLowerCase()
    if (
      lk === 'cookie' ||
      lk === 'authorization' ||
      lk === 'proxy-authorization' ||
      lk === 'x-api-key' ||
      lk === 'token'
    )
      continue
    out[k] = v
  }
  return Object.keys(out).length ? out : undefined
}

let castBaseCache: CastBase | null = null

/** 页面本身就是从局域网 http 打开的？ */
function isLanPage(): boolean {
  if (typeof location === 'undefined') return false
  if (!/^https?:$/.test(location.protocol)) return false
  const h = (location.hostname || '').toLowerCase()
  if (!h) return false
  if (h === 'localhost' || h.startsWith('127.') || h === '[::1]') return false
  // tauri.localhost / TV 的 meow.local 都不是接收端能访问的地址
  if (h.endsWith('.localhost') || h.endsWith('.local')) return false
  return true
}

/**
 * 取"接收端能访问"的投屏基地址。
 *
 * <p>三种形态：
 * <ul>
 *   <li>页面本身是局域网 http（Docker / fnOS / 手动填 LAN 后端）→ 用 `location.origin`；
 *       同源与子路径部署都成立，不需要令牌</li>
 *   <li>PC（tauri.localhost）/ TV（meow.local）/ localhost 开发页 → 问后端要
 *       `/api/cast/base`，它在 0.0.0.0 起一个白名单+令牌的旁路监听</li>
 *   <li>拿不到（没有局域网地址 / 监听起不来）→ 返回 null，调用方提示用户</li>
 * </ul>
 *
 * @param peerHost 接收端 IP：多网卡时后端优先选与它同 /24 的网卡
 */
export async function castBase(peerHost = '', force = false): Promise<CastBase | null> {
  if (isLanPage()) return { base: location.origin, token: '' }
  if (castBaseCache && !force && !peerHost) return castBaseCache
  try {
    const q = peerHost ? `?peer=${encodeURIComponent(peerHost)}` : ''
    const r = await fetch(`${base()}/api/cast/base${q}`)
    if (!r.ok) return null
    const j = (await r.json()) as { ok?: boolean; base?: string; token?: string }
    if (!j.ok || !j.base) return null
    const out: CastBase = { base: j.base, token: j.token || '' }
    if (!peerHost) castBaseCache = out
    return out
  } catch {
    return null
  }
}

/** 弹幕地址（后端统一抓取 + 转 XML）。 */
export function proxyDanmaku(
  name: string,
  episode = '1',
  opts?: { base?: string; token?: string },
): string {
  const params = new URLSearchParams({ name, episode })
  const ct = opts?.token ? `&ct=${encodeURIComponent(opts.token)}` : ''
  // 弹幕 XML 由 hls.js / <track> 直接取，带不了 header：鉴权开启时用代理票
  const mt = mediaTicketFor('proxy')
  const m = mt ? `&mt=${encodeURIComponent(mt)}` : ''
  return `${opts?.base || base()}/api/proxy/danmaku?${params.toString()}${ct}${m}`
}

/** 直接指定弹幕源地址（用户在弹幕搜索里手选之后用） */
export function proxyDanmakuByUrl(url: string): string {
  const mt = mediaTicketFor('proxy')
  const m = mt ? `&mt=${encodeURIComponent(mt)}` : ''
  return `${base()}/api/proxy/danmaku?url=${encodeURIComponent(url)}${m}`
}

/**
 * 预取某集弹幕（后台抓，不阻塞调用方）。
 *
 * <p>后端立刻回 `{queued}` 或 `{cached}`，真正的抓取在 daemon 线程里跑。
 * 纯优化：**任何失败都必须静默**，调用方不需要 catch 也不需要反馈。
 *
 * <p>两个调用点：详情页（第 1 集，最常被点）、播放页片尾前（下一集）。
 * 集中在这里是为了让两处用同一份 URL 与 POST 语义——预取有副作用，
 * 用 POST 才不会被中间层或浏览器预取器当成可缓存 GET。
 */
export function prefetchDanmaku(name: string, episode: string): void {
  if (!name || !episode) return
  const params = new URLSearchParams({ name, episode })
  const mt = mediaTicketFor('proxy')
  if (mt) params.set('mt', mt)
  void fetch(`${base()}/api/proxy/danmaku/prefetch?${params.toString()}`, {
    method: 'POST',
  }).catch(() => {})
}

export interface DanmakuCandidate {
  name: string
  url: string
  host?: string
  /** 平台标识：iqiyi / tencent / 360 …（后端已解析） */
  source?: string
  /** 中文显示名：爱爱 / 腾疼 / 采集站 …（后端已映射） */
  label?: string
  /** 集数，用于数值排序 */
  episode?: number
  /** 稳定 id，形如 iqiyi:tvId:1 */
  id?: string
  /** 抓取路径：direct=API 直连，aggregator=danmu_api/聚合站 */
  via?: 'direct' | 'aggregator'
}

/** 来源分组（后端按 label 聚合） */
export interface DanmakuSourceGroup {
  label: string
  /** 合计 */
  count: number
  /** API 直连数量 */
  direct?: number
  /** danmu_api / 聚合站数量 */
  aggregator?: number
}

/**
 * 搜索弹幕源。自动抓取失败时的兜底：用户手动选一条。
 *
 * 后端已按 episode 数值排序，并给出中文分类 + 固定胶囊顺序。
 */
export async function searchDanmaku(
  name: string,
): Promise<{
  items: DanmakuCandidate[]
  sources: DanmakuSourceGroup[]
  fixed: string[]
}> {
  const q = new URLSearchParams({ name })
  const r = await request<{
    items: DanmakuCandidate[]
    sources?: DanmakuSourceGroup[]
    fixed?: string[]
  }>(`/api/proxy/danmaku/search?${q.toString()}`)
  return { items: r.items || [], sources: r.sources || [], fixed: r.fixed || [] }
}

/**
 * 图片代理。CatVod 的 vod_pic 常在 URL 后挂
 * `@Referer=xxx@User-Agent=yyy`，浏览器直接请求会被防盗链拦。
 */
export function proxyImage(url?: string): string {
  if (!url) return ''
  // <img> 带不了 header：开启鉴权时靠短时票 mt（与视频同一个代理票）
  const mt = mediaTicketFor('proxy')
  const m = mt ? `&mt=${encodeURIComponent(mt)}` : ''
  return `${base()}/api/proxy/image?url=${encodeURIComponent(url)}${m}`
}

// ---------------------------------------------------------------- policy / dns

export interface Policy {
  safeDns?: boolean
  dnsProvider?: string
  /** 自定义弹幕 API（danmu_api / 兼容站），多个用换行或逗号分隔 */
  danmakuApi?: string
  /** 是否让腾讯参与自动抓取（默认 false，时间轴有问题） */
  danmakuTencent?: boolean
  /** drpy 运行时地址（如 http://127.0.0.1:9330）；空 = 未配置，js 源会判不可用 */
  jsRuntime?: string
  /** 是否按 ads 表丢弃 m3u8 广告分片（默认 false，非上游语义） */
  filterAds?: boolean
  /** 写操作令牌；设置后写端点要求 X-Meow-Token */
  authToken?: string
  /** CORS 额外白名单（局域网分端口部署时用） */
  corsOrigins?: string[]
  [k: string]: unknown
}

/** 读取后端偏好（目前存安全 DNS） */
export async function fetchPolicy(): Promise<Policy> {
  return request<Policy>('/api/policy')
}

/** 合并写入后端偏好 */
export async function savePolicy(patch: Policy): Promise<Policy> {
  return request<Policy>('/api/policy', { method: 'POST', body: patch })
}

export interface DnsResult {
  host: string
  ip: string
  provider?: string
  cached?: boolean
  msg?: string
}

/** 用 DoH 解析域名（安全 DNS 的探针，也用于设置页自检） */
export async function resolveDns(host: string, provider = 'ali'): Promise<DnsResult> {
  const q = new URLSearchParams({ host, provider })
  return request<DnsResult>(`/api/dns/resolve?${q.toString()}`)
}

// ---------------------------------------------------------------- 状态通道（出口 C）
//
// 后端把观看进度/收藏存在数据目录里，三端共用。与 WebDAV 是**两条独立通道**：
// WebDAV 要用户配地址，这条不用，所以有就试、没有就跳过，互相不影响。
//
// 这一节的函数全部吞掉网络错误返回安全默认值：状态同步失败不能打断播放/浏览，
// 调用方也不需要写 try/catch。

export interface FavoriteItem {
  site: string
  vod_id: string
  vod_name: string
  vod_pic?: string
  vod_remarks?: string
  flag?: string
  at: number
}

/**
 * 后端是否支持 /api/state/*。
 *
 * 只要 /api/health 答复了就缓存结论（含 false）：老版本后端不认识这些端点，
 * 不缓存的话每次播放都要白探一次。**请求本身失败时不缓存**——桌面端的
 * 后端可能还在启动，下一轮再探一次就对了。
 *
 * false 会在整个会话内生效，符合"健康检查里没 state 就整段跳过"的约定。
 */
let stateCap: boolean | null = null

/**
 * 探测是否已经有结论（不论 true/false）。
 *
 * 调用方据此决定要不要重试：`false` 且已 settled 说明后端明确不支持，
 * 重试多少次都一样；还没 settled 说明是网络失败（后端可能刚起）。
 */
export function stateCapabilitySettled(): boolean {
  return stateCap !== null
}

export async function hasStateCapability(): Promise<boolean> {
  if (stateCap !== null) return stateCap
  try {
    const h = await request<{ capabilities?: unknown }>('/api/health', { timeout: 8000 })
    const caps = Array.isArray(h?.capabilities) ? (h.capabilities as unknown[]) : []
    stateCap = caps.includes('state')
    return stateCap
  } catch {
    return false
  }
}

/**
 * 拉全量进度。
 *
 * 注意后端每项只回 8 个键（site/vod_id/pos/dur/flag/ep/ep_url/ep_at），
 * **没有片名和封面**——合并时要靠本地那条补，见 history.applyRemoteHistory。
 */
export async function fetchStateProgress(): Promise<HistoryItem[]> {
  if (!(await hasStateCapability())) return []
  try {
    const r = await request<{ items?: HistoryItem[] }>('/api/state/progress')
    return Array.isArray(r.items) ? r.items : []
  } catch {
    return []
  }
}

/**
 * 推一条进度。返回后端是否真的采用（`merged`）。
 *
 * `merged:false` 不算失败：后端按 `ep_at` 判新旧，另一台设备更新过就会拒收，
 * 此时本地那份才是该被覆盖的。
 *
 * `keepalive` 用于 pagehide——普通 fetch 会随页面卸载被浏览器砍掉，
 * 于是关窗口前的最后几秒进度永远到不了后端。
 */
export async function pushStateProgress(
  item: HistoryItem,
  opts: { keepalive?: boolean } = {},
): Promise<boolean> {
  const pos = Number(item?.pos)
  // 没有进度的条目不归这条通道管（后端对 pos 缺失直接 400）。
  if (!Number.isFinite(pos) || pos < 0) return false
  // 必须带上 ep_at：缺省时后端会用「当前时间」打戳（ApiServer.handleStateProgress），
  // 那条旧进度就会凭时间戳压掉别的设备上真正较新的进度。
  const epAt = Number(item.ep_at)
  if (!Number.isFinite(epAt) || epAt <= 0) return false
  if (!(await hasStateCapability())) return false
  try {
    const r = await request<{ merged?: boolean }>('/api/state/progress', {
      method: 'POST',
      body: {
        site: item.site,
        vod_id: item.vod_id,
        pos,
        dur: item.dur ?? 0,
        // undefined 会被 JSON.stringify 丢掉，后端据此沿用已存值——
        // 正是我们要的：只改进度时不要把 flag/ep 清空
        flag: item.flag,
        ep: item.ep,
        ep_url: item.ep_url,
        ep_at: epAt,
      },
      timeout: 15_000,
      keepalive: opts.keepalive,
    })
    return !!r.merged
  } catch {
    return false
  }
}

export async function fetchStateFavorites(): Promise<FavoriteItem[]> {
  if (!(await hasStateCapability())) return []
  try {
    const r = await request<{ items?: FavoriteItem[] }>('/api/state/favorites')
    return Array.isArray(r.items) ? r.items : []
  } catch {
    return []
  }
}

/**
 * 可跨端同步的设置项。
 *
 * 与后端 `STATE_PREFS_SYNC` 白名单**必须一致**：后端会丢弃不在名单里的键，
 * 前端这里少写一个就表现为"改了没同步"。两边都留一份是为了各自的类型/可读性，
 * 代价是改动时要同时改两处——已在 docs/26 §8.3 写明。
 */
export type SyncedPrefs = Partial<{
  homeCols: number
  searchThreads: number
  searchLayout: string
  cacheDays: number
  historyLimit: number
  danmaku: boolean
  danmakuDensity: string
  danmakuOpacity: number
  danmakuScale: number
  danmakuDuration: number
  danmakuArea: number
  autoNextMode: string
  autoNextDelay: number
  skipIntro: number
  skipOutro: number
  skipCountdown: number
  themeStyle: string
  themeMode: string
}>

/** 可跨端同步的键。与后端 `STATE_PREFS_SYNC` 白名单逐项对应，改一处要改两处。 */
export const SYNCED_PREF_KEYS = [
  'homeCols',
  'searchThreads',
  'searchLayout',
  'cacheDays',
  'historyLimit',
  'danmaku',
  'danmakuDensity',
  'danmakuOpacity',
  'danmakuScale',
  'danmakuDuration',
  'danmakuArea',
  'autoNextMode',
  'autoNextDelay',
  'skipIntro',
  'skipOutro',
  'skipMiddle',
  'skipCountdown',
  'themeStyle',
  'themeMode',
] as const

/** 拉远端设置。返回 null 表示"没有/拉不到"，与"空设置"区分开。 */
export async function fetchStatePrefs(): Promise<{ prefs: SyncedPrefs; at: number } | null> {
  if (!(await hasStateCapability())) return null
  try {
    const r = await request<{ prefs?: SyncedPrefs; at?: number }>('/api/state/prefs')
    const at = Number(r.at) || 0
    // at === 0 说明服务端从没被写过，别拿空设置去覆盖本地
    if (!at) return null
    return { prefs: r.prefs && typeof r.prefs === 'object' ? r.prefs : {}, at }
  } catch {
    return null
  }
}

/**
 * 推设置（增量）。返回后端采纳的键数与服务端时间戳；失败返回 null。
 *
 * 时间戳要带回来：调用方用它更新本地的 `syncedAt`，否则下次启动会认为
 * 远端"更新"而把自己刚推上去的设置再拉一遍。
 */
export async function pushStatePrefs(
  prefs: SyncedPrefs,
): Promise<{ applied: number; at: number } | null> {
  if (!prefs || !Object.keys(prefs).length) return { applied: 0, at: 0 }
  if (!(await hasStateCapability())) return null
  try {
    const r = await request<{ applied?: number; at?: number }>('/api/state/prefs', {
      method: 'POST',
      body: { prefs },
    })
    return { applied: Number(r.applied) || 0, at: Number(r.at) || 0 }
  } catch {
    return null
  }
}

export async function addStateFavorite(item: FavoriteItem): Promise<boolean> {
  if (!item?.site || !item?.vod_id) return false
  if (!(await hasStateCapability())) return false
  try {
    const r = await request<{ ok?: boolean }>('/api/state/favorites', {
      method: 'POST',
      body: {
        site: item.site,
        vod_id: item.vod_id,
        vod_name: item.vod_name,
        vod_pic: item.vod_pic,
        vod_remarks: item.vod_remarks,
        flag: item.flag,
        at: item.at,
      },
      timeout: 15_000,
    })
    return !!r.ok
  } catch {
    return false
  }
}

export async function removeStateFavorite(site: string, vodId: string): Promise<boolean> {
  if (!site || !vodId) return false
  if (!(await hasStateCapability())) return false
  try {
    // query 用 vodId（后端 stateQueryId 两种拼法都收，驼峰是 docs/25 的契约）
    const q = new URLSearchParams({ site, vodId })
    const r = await request<{ ok?: boolean }>(`/api/state/favorites?${q.toString()}`, {
      method: 'DELETE',
      timeout: 15_000,
    })
    return !!r.ok
  } catch {
    return false
  }
}

/** 后端地址给外部展示用 */
export function apiBase(): string {
  return base()
}

/** 是否运行在 Tauri 桌面壳里（main.ts 判断要不要等 sidecar 就绪） */
export function isDesktop(): boolean {
  return isDesktopShell()
}

// ---------------------------------------------------------------- 插件管理

/** 列出本机插件状态。Web 端返回空数组。 */
export async function listPlugins(): Promise<PluginInfo[]> {
  if (!isDesktopShell()) return []
  const info = await tauriInvoke<BootInfo>('boot_info')
  return info.plugins || []
}

/** 手动启动一个插件（幂等）。 */
export async function startPlugin(id: string): Promise<PluginInfo> {
  return tauriInvoke<PluginInfo>('plugin_start', { id })
}

/** 手动停止一个插件。 */
export async function stopPlugin(id: string): Promise<void> {
  await tauriInvoke<void>('plugin_stop', { id })
}

/**
 * 切换某个插件"是否随应用启动"。持久化在 {appData}/enabled.json。
 *
 * 只改配置、不动当前进程：想让改动立刻生效，前端在切换后自己调用
 * startPlugin / stopPlugin（见设置页）。
 */
export async function setPluginEnabled(id: string, enabled: boolean): Promise<boolean> {
  return tauriInvoke<boolean>('plugin_set_enabled', { id, enabled })
}

// ---------------------------------------------------------------- 插件下载 / 更新

/**
 * 插件源清单（packages.json）里的单个包。
 *
 * 顶层 key 就是插件 id，不在结构里重复（见 build-release.ps1 注释）。
 */
export interface PackageInfo {
  display_name?: string
  version: string
  platform?: string[]
  desc?: string
  author?: string
  homepage?: string
  size: number
  sha256: string
  url: string
  requires?: string[]
  hidden?: boolean
  lasttime?: string
  api_version?: number
  min_app_version?: string
}

/** 插件源清单 */
export interface PluginIndex {
  schema_version: number
  generated_at?: string
  author?: { name?: string; url?: string }
  min_app_version?: string
  /** id → 包信息 */
  packages: Record<string, PackageInfo>
}

/** 供列表展示的扁平结构（前端模板用） */
export interface RemotePlugin extends PackageInfo {
  id: string
  label: string
}

export interface InstallProgress {
  id: string
  /** idle|downloading|verifying|extracting|installing|done|failed|cancelled */
  stage: string
  /** 0-100，未知总量时可能不准 */
  percent: number
  received: number
  total: number
  message: string
}

export interface InstalledInfo {
  version: string
  sha256: string
  installedAt?: string
}

/** 当前插件源地址 */
export async function getPluginSource(): Promise<string> {
  if (!isDesktopShell()) return ''
  return tauriInvoke<string>('plugin_source')
}

/** 一个插件源 */
export interface SourceItem {
  id: string
  name: string
  url: string
}

/** 插件源列表 + 当前选中 */
export interface SourceList {
  current: string
  items: SourceItem[]
}

export async function fetchPluginSources(): Promise<SourceList | null> {
  if (!isDesktopShell()) return null
  return tauriInvoke<SourceList>('plugin_sources')
}

export async function addPluginSource(url: string, name = ''): Promise<SourceList> {
  return tauriInvoke<SourceList>('plugin_add_source', { url, name })
}

export async function updatePluginSource(
  id: string,
  url: string,
  name = '',
): Promise<SourceList> {
  return tauriInvoke<SourceList>('plugin_update_source', { id, url, name })
}

export async function removePluginSource(id: string): Promise<SourceList> {
  return tauriInvoke<SourceList>('plugin_remove_source', { id })
}

export async function selectPluginSource(id: string): Promise<SourceList> {
  return tauriInvoke<SourceList>('plugin_select_source', { id })
}

/** 测试源是否可达，返回可读描述（含插件数量） */
export async function testPluginSource(url: string): Promise<string> {
  return tauriInvoke<string>('plugin_test_source', { url })
}

/** 直接导入一份清单 JSON 文本（没有服务器也能用） */
export async function importPluginManifest(text: string, name = ''): Promise<SourceList> {
  return tauriInvoke<SourceList>('plugin_import_manifest', { text, name })
}

/** 改插件源（兼容旧接口，等价于"新增或切换") */
export async function setPluginSource(url: string): Promise<void> {
  await tauriInvoke<void>('plugin_set_source', { url })
}

/** 拉远端索引。失败时抛错（网络 / 格式 / 版本不兼容）。 */
export async function fetchPluginIndex(): Promise<PluginIndex> {
  return tauriInvoke<PluginIndex>('plugin_index')
}

/** 已安装记录：{ [id]: { version, sha256, installedAt } } */
export async function fetchInstalledPlugins(): Promise<Record<string, InstalledInfo>> {
  if (!isDesktopShell()) return {}
  return tauriInvoke<Record<string, InstalledInfo>>('plugin_installed')
}

/** 下载并安装/升级。会阻塞到装完，期间用 fetchInstallProgress 轮询。 */
export async function installPlugin(id: string, appVersion: string): Promise<InstalledInfo> {
  return tauriInvoke<InstalledInfo>('plugin_install', { id, appVersion })
}

export async function fetchInstallProgress(): Promise<InstallProgress | null> {
  if (!isDesktopShell()) return null
  return tauriInvoke<InstallProgress>('plugin_progress')
}

export async function cancelInstall(): Promise<void> {
  await tauriInvoke<void>('plugin_cancel')
}

/** 从本地 zip 导入（离线安装）。返回插件 id。 */
export async function importPluginZip(path: string): Promise<string> {
  return tauriInvoke<string>('plugin_import', { path })
}

/** 卸载插件（先停进程再删目录）。 */
export async function uninstallPlugin(id: string): Promise<void> {
  await tauriInvoke<void>('plugin_uninstall', { id })
}

/** 从后端 health 判断是否可用 */
export async function ping(): Promise<boolean> {
  try {
    await request('/api/health')
    return true
  } catch {
    return false
  }
}

/**
 * 导入外部 CatVod 配置（`POST /api/config/import`）。
 *
 * 超时给到 120s：那份配置本身要下载、里面 200+ 站点还要逐个判定
 * （csp_ 类在不在本地 jar、js 源是否可接受），实测 300 站的配置要 10–30s。
 */
export interface ConfigSkippedSite {
  api?: string
  key?: string
  reason?: string
}

/**
 * 导入策略。
 *
 * `replace` 整表换掉（原有行为）；`merge` 保留现有站点、把新的并进来。
 * 为什么要有后者：导入是破坏性的，用户想"再补一份配置"时原来只能整表换，
 * 自己手加的站和另一份配置独有的源全部消失，而且只有一次 .bak 可撤。
 */
export type ImportMode = 'replace' | 'merge'
/** merge 模式下的同 key 冲突处理：留现有的 / 用导入的 / 两个都要 */
export type ImportConflict = 'keep' | 'replace' | 'both'

/** diff 里展示用的最小站点形状（后端只回 key/name/api） */
export interface ConfigSiteLite {
  key: string
  name?: string
  api?: string
}

export interface ConfigImportDiff {
  toAdd: ConfigSiteLite[]
  toUpdate: ConfigSiteLite[]
  conflicts: Array<{ key: string; existing: ConfigSiteLite; incoming: ConfigSiteLite }>
  unchanged: number
  /** replace 模式下会消失的站点 key */
  removed: string[]
  total: number
  /** 导入后健康/停用状态会失效的站点 key（只在 site-health.json 里有记录时才算） */
  healthLost: string[]
  mode?: ImportMode
  onConflict?: string
}

export interface ConfigImportResult {
  ok?: boolean
  imported?: boolean
  /** true = 这是预览（dryRun），没写盘 */
  dryRun?: boolean
  mode?: ImportMode
  sites?: number
  totalSites?: number
  /** 本地站点表原始条数（含未实现的），与 diff.total 同口径 */
  existingSites?: number
  /**
   * 内置基线条数（静态注册的站）。
   *
   * 这些站不在配置文件里，**替换导入也删不掉** —— 预览时要说出来，
   * 否则用户会以为「替换 = 清空」。
   */
  builtinSites?: number
  skipped?: ConfigSkippedSite[]
  depot?: Array<{ url?: string; name?: string }>
  url?: string
  msg?: string
  diff?: ConfigImportDiff
  /**
   * 四种并法各会怎样（dryRun 一次算齐）。
   *
   * 有它才能做到「读完再选策略」：切换 追加/替换/冲突策略 都从这份里取，
   * 不再重新请求（dryRun 每次都会重新抓配置，切一次就等一轮网络）。
   * 老后端没有这个字段，调用方要回退到 `diff`。
   *
   * 注意 `merge.replace` 是**冲突策略**（用导入的覆盖），与 `replace` 模式不是一回事。
   */
  plans?: {
    merge?: Partial<Record<ImportConflict, ConfigImportDiff>>
    replace?: ConfigImportDiff
  }
  /** 导入后留下的旧配置快照（供「恢复上一次配置」） */
  backup?: ConfigBackupInfo
}

/** 导入前的配置快照。`exists:false` 表示没有可回滚的版本。 */
export interface ConfigBackupInfo {
  exists: boolean
  /** 备份时间（毫秒） */
  at?: number
  bytes?: number
  /** 备份里有多少站 */
  sites?: number
}

/** 多仓索引里的一个仓，含"我们能不能用"的判断结果。 */
export interface DepotEntry {
  name: string
  url: string
  /** 拉取 + 解析成功 */
  ok: boolean
  error?: string
  /** 配置里的站点总数 */
  sites?: number
  /** 其中 csp_ 类的数量 */
  csp?: number
  /** csp_ 里我们的 jar 有对应类的数量 —— 决定"导入后真能用几站" */
  inJar?: number
  /** 苹果CMS 直连站（type=1），不需要 jar */
  direct?: number
  /** 给人看的一句话结论 */
  hint?: string
  /** 该仓自己也是多仓索引时，内含几个子仓 */
  nested?: number
}

export interface ConfigDepotResult {
  ok?: boolean
  entries?: DepotEntry[]
  msg?: string
}

/** 配置导入的 GitHub 加速前缀（仅 configImport 渠道启用时非空） */
function configProxies(): string[] {
  try {
    return enabledProxyPrefixes(getPrefs().gitAccel, 'configImport')
  } catch {
    return []
  }
}

export async function importConfig(
  url: string,
  opts: {
    mode?: ImportMode
    onConflict?: ImportConflict
    dryRun?: boolean
    /** 用导入配置的顶层 spider 覆盖本地（源包迁移时必须为 true） */
    replaceSpider?: boolean
  } = {},
): Promise<ConfigImportResult> {
  return request<ConfigImportResult>('/api/config/import', {
    method: 'POST',
    body: {
      url,
      mode: opts.mode || 'replace',
      onConflict: opts.onConflict || 'keep',
      dryRun: !!opts.dryRun,
      proxies: configProxies(),
      replaceSpider: !!opts.replaceSpider,
    },
    timeout: 120_000,
  })
}

// ---------------------------------------------------------------- 直播源（config.lives）

/** config.lives 里的一条直播源。 */
export interface LiveSource {
  name: string
  url: string
}

/**
 * 直播源的增删查（`/api/lives`）。
 *
 * 独立于站点配置：直播源的生命周期和点播站点完全不同（m3u 每天变），
 * 原来只能靠"导入整份配置"才能改它，代价是站点表被整张换掉。
 */
export async function fetchLives(): Promise<{
  ok?: boolean
  lives: LiveSource[]
  /**
   * 还没被删的内置源 URL 列表（设置页据此给条目打「内置」标签）。
   *
   * ★ 早期后端返回 boolean（那时只有一条内置源）；现在是数组 ——
   *   内置源有两条（Guovin / iptv-org），用户可能只删其中一条。
   *   旧后端返回 true 时按"没有可用内置源"处理（只是不显示标签，
   *   功能本身不受影响）。
   */
  builtin: string[]
}> {
  const r = await request<{ ok?: boolean; lives?: LiveSource[]; builtin?: unknown }>(
    '/api/lives',
    { timeout: 15_000 },
  )
  const b = r?.builtin
  return {
    ok: r?.ok,
    lives: r?.lives || [],
    builtin: Array.isArray(b) ? (b as string[]) : [],
  }
}

/**
 * 恢复内置直播源：清掉逐条禁用标记，被删的内置源重新补回列表。
 *
 * 内置源可以逐条删（删了不复活），但删错时不该逼用户去记
 * `guovin.github.io/iptv-api/result.m3u` 这种地址再手输一遍。
 */
export async function resetBuiltinLives(): Promise<{ ok?: boolean; builtin: string[] }> {
  const r = await request<{ ok?: boolean; builtin?: unknown }>('/api/lives', {
    method: 'POST',
    body: { resetBuiltin: true },
    timeout: 15_000,
  })
  return { ok: r?.ok, builtin: Array.isArray(r?.builtin) ? (r!.builtin as string[]) : [] }
}

export async function addLiveSource(input: {
  name?: string
  url: string
  /** true = 先真拉一遍解析，不出频道就不保存 */
  verify?: boolean
}): Promise<{
  ok?: boolean
  lives?: LiveSource[]
  msg?: string
  duplicate?: boolean
  verified?: boolean
  channels?: number
}> {
  return request('/api/lives', { method: 'POST', body: input, timeout: 45_000 })
}

export async function removeLiveSource(url: string): Promise<{
  ok?: boolean
  lives?: LiveSource[]
  removed?: number
  msg?: string
}> {
  return request('/api/lives', { method: 'DELETE', body: { url }, timeout: 20_000 })
}

/**
 * 探测多仓索引：列出每个仓能否使用、导入后真正能加载多少站。
 *
 * ★ 只读：不写配置文件、不动运行时站点表。用户挑好之后才调 importConfig。
 *   这也是它存在的理由之一——不能为了"看看有哪些仓"就把现有配置冲掉。
 */
export async function probeConfigDepot(url: string): Promise<ConfigDepotResult> {
  return request<ConfigDepotResult>('/api/config/depot', {
    method: 'POST',
    body: { url, proxies: configProxies() },
    timeout: 150_000,
  })
}

/** 查导入前的配置快照。 */
export async function getConfigBackup(): Promise<{ backup: ConfigBackupInfo }> {
  try {
    return await request<{ backup: ConfigBackupInfo }>('/api/config/backup', { timeout: 15_000 })
  } catch {
    return { backup: { exists: false } }
  }
}

/** 用备份覆盖回当前配置（撤销上一次导入）。 */
export async function restoreConfigBackup(): Promise<{
  ok?: boolean
  sites?: number
  backup?: ConfigBackupInfo
  msg?: string
}> {
  return request('/api/config/backup', { method: 'POST', body: {}, timeout: 60_000 })
}

/** 丢弃备份（确认新配置没问题了）。 */
export async function discardConfigBackup(): Promise<{ ok?: boolean; removed?: boolean }> {
  return request('/api/config/backup', { method: 'DELETE', timeout: 20_000 })
}

/** 最近一次导入的摘要（`GET` 同一路径）；没导入过时 `imported:false`。 */
export async function getLastConfigImport(): Promise<ConfigImportResult> {
  try {
    return await request<ConfigImportResult>('/api/config/import', { timeout: 15_000 })
  } catch {
    return { imported: false }
  }
}

/** 解析 vod_play_url / vod_play_from 为线路结构 */
export function parsePlayLines(
  from: string,
  url: string,
): Array<{ name: string; episodes: Array<{ name: string; url: string }> }> {
  if (!from || !url) return []
  const froms = from.split('$$$')
  const groups = url.split('$$$')
  return froms.map((name, i) => {
    const raw = groups[i] || ''
    const episodes = raw
      .split('#')
      .filter(Boolean)
      .map((item) => {
        const idx = item.indexOf('$')
        if (idx < 0) return { name: item, url: item }
        return { name: item.slice(0, idx), url: item.slice(idx + 1) }
      })
    return { name, episodes }
  })
}
