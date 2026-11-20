// ★ 必须排在最前：Chromium 66（Android 9 电视/投影仪）缺 Promise.allSettled /
//   Object.fromEntries / flatMap / matchAll / at —— 这些是运行时 API，
//   esbuild 的 target 只转语法不补 API。业务模块评估前必须先装好。
import './compat/legacyPolyfills'
// 设备档案（壳/形态/方向）→ `html[data-*]`。必须早于业务模块：
// 首屏样式与 isTvEnv() 都依赖 data-tv / data-form 已就位。
import './compat/deviceProfile'
// 输入方式（触摸 / 键盘）→ 决定画不画焦点环。同样要在首帧前装好，
// 否则手机首屏会先闪一下环再消失。
import './compat/inputModality'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles/tokens.css'
// 老内核（Chrome < 88）的降级规则。必须排在 tokens.css 之后：
// 它靠"写在 @layer 之外"压过 Tailwind 的 utilities 层，顺序反了会失效。
// legacy-generated.css 由 scripts/gen-legacy-css.mjs 从构建产物自动生成，
// 覆盖所有用 padding-inline / margin-inline / gap 的类（手写会漏档位）。
import './styles/legacy-generated.css'
import './styles/legacy-css.css'
import { detectLegacyCss } from './styles/legacyCss'
import {
  addStateFavorite,
  apiBase,
  fetchStateFavorites,
  fetchStatePrefs,
  fetchStateProgress,
  clearSearchCache,
  fetchPolicy,
  savePolicy,
  hasStateCapability,
  initBackend,
  isDesktop,
  pushStatePrefs,
  pushStateProgress,
  removeStateFavorite,
  stateCapabilitySettled,
  SYNCED_PREF_KEYS,
  tauriInvoke,
} from './api'
// 副作用导入：注册主题 → 原生的推送（TV 配置页色板 / PC 窗口底色）。
// 不能等某个页面按需 import —— 首帧主题已经生效，晚加载会错过第一次 meow:theme。
import './api/nativeBridge'
import { installDiagnostics } from './diagnostics'
import { installToolBridge } from './ai/bridge'
import { installMediaExclusivity } from './utils/mediaExclusive'
import { autoPullOnStart, cancelPending, flushUpload, scheduleUpload } from './stores/sync'
import { applyRemoteHistory, listHistory } from './stores/history'
import {
  applyRemoteFavorites,
  listFavorites,
  type FavoriteChange,
} from './stores/favorites'
import { getPrefs, getWebdav, savePrefs, saveWebdav, type Prefs } from './stores/prefs'
import { resetHealth } from './api/health'
import { setBackupUpdateSource, setUpdateSource, setUpdateToken } from './api/appUpdate'
import { refreshAuth } from './stores/auth'
import { ensureProxyTicket, refreshMediaTickets } from './api/mediaTicket'

// TV 壳靠 UA 标记识别；PC 浏览器想模拟电视布局用 `?tv=1`。
// 必须挂在业务代码之前：列数在组件挂载时就要读到这个标记。
if (new URLSearchParams(location.search).get('tv') === '1') {
  document.documentElement.dataset.tv = '1'
}

// 诊断钩子必须在任何业务代码之前装：要捕获启动期的错误与请求
installDiagnostics()
// 外部 MCP 的工具桥（docs/47 §1.8）：把 9 个 meow_* 挂到 window，
// 壳（PC 的 pc_eval / TV 的 tv_eval）注入脚本即可调用，业务逻辑不必在壳里重写。
installToolBridge()
// 全局媒体互斥：任何 <video>/<audio> 开始播放就停掉其它在播的。
// 这是"旧片音轨 + 新片画面重叠"的结构性保险，见 utils/mediaExclusive.ts
installMediaExclusivity()

/**
 * 设置读写的原生桥（TV 扫码推送用）。
 *
 * ## 为什么不能由壳直接写 localStorage
 *
 * `savePrefs()` 内部有一个模块级 `cache`，`getPrefs()` 优先读它。
 * 壳若绕过它直接 `localStorage.setItem`，缓存不会失效 —— 页面上下一次
 * `getPrefs()` 仍返回旧值，表现为"手机改了、电视没变"，而且刷新才恢复。
 *
 * 所以推送必须回到前端来做：壳只负责把 JSON 递进来。
 *
 * ## 为什么读也要走这里
 *
 * 手机页要用"电视当前的设置"预填表单。从 localStorage 直接读会拿到
 * 未经 `getPrefs()` 校验/兜底的原始数据（老版本可能缺字段、可能有
 * 非法枚举值），手机页据此渲染就会出现"电视上是密集、手机上显示标准"。
 *
 * 两个方法都返回 JSON 字符串：`evaluateJavascript` 拿不到对象，
 * 只能靠字符串回传。
 */
function installPrefsBridge() {
  const w = window as unknown as Record<string, unknown>
  /** 供 PushServer 读当前设置（含全部字段，未校验的由 getPrefs 兜底） */
  w.__meowPrefsJson = () => JSON.stringify(getPrefs())
  /**
   * 供 PushServer 写入手机推来的设置。
   *
   * `savePrefs` 会派发 `meow:prefs`，主题/列数/播放器都监听它 ——
   * 所以推完立即生效，不需要 reloadApp。
   *
   * 只转发**已知字段**：手机页和前端是两个独立的实现，字段名拼错时
   * 静默丢弃比写进 localStorage 变成一个永远不生效的垃圾键要好。
   */
  w.__meowPrefsApply = (json: string) => {
    try {
      const patch = JSON.parse(String(json || '{}'))
      if (!patch || typeof patch !== 'object') return 0
      const known: Record<string, unknown> = {}
      const keys = Object.keys(getPrefs()) as Array<keyof Prefs>
      for (const k of keys) {
        if (Object.prototype.hasOwnProperty.call(patch, k)) known[k] = patch[k]
      }
      const n = Object.keys(known).length
      if (n) savePrefs(known as Partial<Prefs>)
      return n
    } catch {
      return 0
    }
  }

  /**
   * WebDAV 配置（存在 `meow.webdav`，**不在** prefs 里）。
   *
   * 为什么单独一个桥：WebDAV 有密码，且有自己的开关组（历史/设置/搜索词/
   * 片库），混进 prefs 会让"设置同步"把密码也带走。前端 `saveWebdav()`
   * 会派发 `meow:webdav`，同步模块据此重排任务，所以同样不用重启。
   */
  w.__meowWebdavJson = () => JSON.stringify(getWebdav())
  w.__meowWebdavApply = (json: string) => {
    try {
      const patch = JSON.parse(String(json || '{}'))
      if (!patch || typeof patch !== 'object') return 0
      const base = getWebdav() as unknown as Record<string, unknown>
      const known: Record<string, unknown> = {}
      for (const k of Object.keys(base)) {
        if (Object.prototype.hasOwnProperty.call(patch, k)) known[k] = patch[k]
      }
      const n = Object.keys(known).length
      if (n) saveWebdav(known)
      return n
    } catch {
      return 0
    }
  }

  /**
   * 更新源与访问令牌（`meow.updateSource` / `meow.updateToken`）。
   *
   * 这两个 key 归 appUpdate.ts 管，直接写 localStorage 也能生效（那边没有
   * 模块级缓存），但仍走它的 setter：一是未来加了内存缓存不会失效，
   * 二是 writeLs 里有 try/catch，配额满时不会把异常抛到原生层。
   */
  w.__meowUpdateJson = () => {
    const p = getPrefs()
    return JSON.stringify({
      // 老版本只写过 legacy localStorage；Prefs 为空时仍要能预填
      source: p.updateSource || readLsRaw('meow.updateSource'),
      token: p.updateToken || readLsRaw('meow.updateToken'),
      backupSource: p.updateBackupSource,
      autoCheckApp: p.autoCheckApp,
      autoCheckModule: p.autoCheckModule,
      autoCheckPlugins: p.autoCheckPlugins,
      autoCheckFpk: p.autoCheckFpk,
    })
  }
  w.__meowUpdateApply = (json: string) => {
    try {
      const patch = JSON.parse(String(json || '{}'))
      if (!patch || typeof patch !== 'object') return 0
      let n = 0
      if (typeof patch.source === 'string') {
        setUpdateSource(patch.source)
        n++
      }
      if (typeof patch.token === 'string') {
        setUpdateToken(patch.token)
        n++
      }
      if (typeof patch.backupSource === 'string') {
        setBackupUpdateSource(patch.backupSource)
        n++
      }
      const bools = [
        'autoCheckApp',
        'autoCheckModule',
        'autoCheckPlugins',
        'autoCheckFpk',
      ] as const
      for (const k of bools) {
        if (typeof patch[k] === 'boolean') {
          savePrefs({ [k]: patch[k] } as Partial<Prefs>)
          n++
        }
      }
      return n
    } catch {
      return 0
    }
  }

  /**
   * 后端 policy（搜索缓存时长、JS 运行时、广告过滤、SafeDNS、弹幕 API、
   * 缓存配额/目录）—— 这些**不在浏览器里**，要么写后端 `policy.json`，
   * 要么（TV 本机执行时）写端内网关那份。所以是异步的。
   *
   * ★ 与 ai/bridge.ts 一样提供两条通道：
   *   · `__meowPolicyJson` / `__meowPolicyApply` —— 直接返回 Promise
   *     （PC 的 CDP 能 awaitPromise）
   *   · `__meowPolicyStart` / `__meowPolicyPoll` —— 投任务 + 轮询
   *     （TV 的 evaluateJavascript 是同步回调，拿不到 Promise 结果）
   * 壳侧只能用后者，见 MainActivity.pushPrefsHost。
   */
  w.__meowPolicyJson = async () => {
    try {
      return JSON.stringify(await fetchPolicy())
    } catch {
      return '{}'
    }
  }
  w.__meowPolicyStart = (id: string, json: string) => {
    gcPolicyPending()
    const key = String(id || '')
    if (!key) return false
    policyPending.set(key, { done: false, at: Date.now() })
    void (async () => {
      let n = 0
      try {
        const patch = JSON.parse(String(json || '{}'))
        if (patch && typeof patch === 'object') {
          n = Object.keys(patch).length
          if (n) await savePolicy(patch)
        }
      } catch {
        n = 0
      }
      policyPending.set(key, { done: true, result: n, at: Date.now() })
    })()
    return true
  }
  w.__meowPolicyReadStart = (id: string) => {
    gcPolicyPending()
    const key = String(id || '')
    if (!key) return false
    policyPending.set(key, { done: false, at: Date.now() })
    void (async () => {
      let s = '{}'
      try {
        s = JSON.stringify(await fetchPolicy())
      } catch {
        s = '{}'
      }
      policyPending.set(key, { done: true, json: s, at: Date.now() })
    })()
    return true
  }
  w.__meowPolicyPoll = (id: string) => {
    const p = policyPending.get(String(id || ''))
    if (!p) return { pending: false, missing: true }
    if (!p.done) return { pending: true }
    policyPending.delete(String(id || ''))
    return { pending: false, result: p.result, json: p.json }
  }

  /**
   * 命令类动作（不是设置项，是"现在做一件事"）。
   *
   * <p>手机页那两个勾选（清空搜索缓存 / 重置源健康）走这里，复用设置页
   * 已有的同一批 API —— 不在壳里重写一遍，否则两处行为迟早漂移。
   *
   * <p>同样用"投任务 + 轮询"：下面两个都是 HTTP 请求。
   */
  w.__meowActionStart = (id: string, name: string) => {
    gcPolicyPending()
    const key = String(id || '')
    if (!key) return false
    policyPending.set(key, { done: false, at: Date.now() })
    void (async () => {
      let n = 0
      try {
        if (name === 'clearSearchCache') {
          const r = await clearSearchCache()
          n = r?.cleared ?? 1
        } else if (name === 'resetHealth') {
          await resetHealth()
          n = 1
        }
      } catch {
        n = 0
      }
      policyPending.set(key, { done: true, result: n, at: Date.now() })
    })()
    return true
  }
}

/** 读原始 localStorage 字符串（不经过模块缓存，用于只读展示）。 */
function readLsRaw(key: string): string {
  try {
    return localStorage.getItem(key) || ''
  } catch {
    return ''
  }
}

/**
 * policy 是异步的（要发 HTTP），而 `evaluateJavascript` 是同步回调 ——
 * 跟 `ai/bridge.ts` 的 `__meowToolStart/Poll` 用同一套"投任务 + 轮询"。
 */
const policyPending = new Map<
  string,
  { done: boolean; result?: number; json?: string; at: number }
>()
function gcPolicyPending() {
  const now = Date.now()
  for (const [k, v] of policyPending) {
    if (now - v.at > 60_000) policyPending.delete(k)
  }
}
installPrefsBridge()

/**
 * 桌面端：后端是 sidecar 进程，**异步启动**（JVM 要几秒）。
 *
 * 必须在 mount 之前等它就绪，否则首屏的 fetchSites 必然失败、
 * 用户看到"后端未连接"的误报。Web 端 initBackend 直接返回 null，无额外开销。
 */
async function bootstrap() {
  if (isDesktop()) {
    // 先用极简占位遮住白屏。
    // 用四条长属性而不是 `inset:0`：后者在 Chrome 66 上无效（见 danmaku.ts 同处注释）。
    // 这条路径只在桌面端走，但同类写法一律不写简写，免得日后复制到 TV 路径。
    const splash = document.createElement('div')
    // 底色/文字跟随当前主题：index.html 的首帧脚本已经把 data-style/data-mode
    // 落成属性，这里能直接读到生效值。写死 #12141d 会让浅色主题在启动时
    // 先闪一块深 navy。
    const cs = getComputedStyle(document.documentElement)
    const splashBg = (cs.getPropertyValue('--color-base') || '').trim() || '#12141d'
    const splashFg = (cs.getPropertyValue('--color-ink-2') || '').trim() || '#9aa4b2'
    const splashInk = (cs.getPropertyValue('--color-ink') || '').trim() || '#e6e8ee'
    splash.style.cssText =
      'position:fixed;top:0;right:0;bottom:0;left:0;display:flex;' +
      'flex-direction:column;align-items:center;justify-content:center;' +
      `background:${splashBg};color:${splashFg};font:14px/1.6 system-ui,sans-serif;z-index:9999`

    // 品牌开屏（PC）：Android 那边有 SplashActivity + 系统开屏，桌面端是
    // 「等 sidecar JVM 起来」的真实等待，同样给图标 + 应用名，别只留一行字。
    const logo = document.createElement('img')
    logo.src = `${import.meta.env.BASE_URL}icon-192.png`
    logo.alt = ''
    logo.style.cssText =
      'width:88px;height:88px;border-radius:20px;display:block;' +
      'box-shadow:0 10px 28px rgba(0,0,0,.35)'
    const appName = document.createElement('div')
    appName.textContent = '猫窝'
    appName.style.cssText =
      `margin-top:18px;font-size:20px;font-weight:600;letter-spacing:.08em;color:${splashInk}`
    const status = document.createElement('div')
    status.textContent = '正在启动后端…'
    status.style.cssText = 'margin-top:6px;font-size:13px'
    splash.append(logo, appName, status)
    document.body.appendChild(splash)
    try {
      const info = await initBackend((msg) => {
        status.textContent = msg
      })
      if (info && !info.ok) {
        // 失败原因交给 App.vue 展示（store.error）
        console.error('[boot] 后端未就绪:', info.fatal_reason)
      }
    } catch (e) {
      console.error('[boot] initBackend 失败:', e)
    } finally {
      splash.remove()
    }
  }

  // ★ 必须在 mount 之前：挂载后再降级会让首帧闪一下错乱布局
  // （投影仪实测：顶栏挤成一团 → 突然散开，观感很差）
  //
  // ★★ 必须 try 住：这个探测要动 DOM、读 computedStyle，而它跑在极老的
  // WebView 上（实测投影仪是 Chrome 66）。它一旦抛异常，下面的 mount()
  // 就永远不会执行 —— 用户看到的是"应用打不开"，而不是"样式没适配好"。
  // 降级是锦上添花，绝不能因为它让整个应用起不来。
  try {
    detectLegacyCss()
  } catch (e) {
    console.warn('[boot] 老内核探测失败，按新内核处理:', e)
  }
  installDesktopPrefsBridge()
  // 鉴权状态必须先拿到再挂载：否则首帧会先渲染出首页（发一堆必然 401 的请求）
  // 再被守卫弹回登录页，观感是"闪一下又跳走"。
  // 后端不可达时最多等 3 秒 —— 局域网没开鉴权的部署不该被这一步拖住启动。
  await Promise.race([
    refreshAuth(),
    new Promise<void>((resolve) => setTimeout(resolve, 3000)),
  ])
  createApp(App).use(createPinia()).use(router).mount('#app')
  startBackgroundJobs()
  /*
   * 切回前台补刷媒体票。
   *
   * 后端重启（升级/Docker 重建/NAS 重启）会作废所有票，而客户端缓存 15 分钟
   * —— 那段时间里海报 401 显示「点击重试」、弹幕空白，用户以为是网络问题。
   * 契约里票 30 分钟有效，这里只在距上次补刷超过 10 分钟时做一次，
   * 频繁切前台不会反复打接口。取票失败（未开鉴权）由 mediaTicket 内部抑制。
   */
  let lastTicketKick = 0
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible') return
    if (Date.now() - lastTicketKick < 10 * 60 * 1000) return
    lastTicketKick = Date.now()
    refreshMediaTickets()
    void ensureProxyTicket()
  })
}

// ---------------------------------------------------------------- 桌面端偏好桥
//
// Rust 在 CloseRequested 里同步读 {appData}/desktop-prefs.json，而 WebView
// 的 localStorage 它看不见。所以前端在启动时和每次 `meow:prefs` 变更时
// 把 closeToTray 递过去——这样「关闭窗口时最小化到托盘」不是假开关。
//
// 默认 true 与 prefs.ts 的 DEFAULTS 保持一致；第一次同步即使用户没进过
// 设置页，也会把缺省值落到文件，避免 Rust 端读到"文件不存在"。
function installDesktopPrefsBridge() {
  if (!isDesktop()) return
  let last: boolean | undefined
  const sync = () => {
    const want = !!getPrefs().closeToTray
    if (want === last) return
    last = want
    void tauriInvoke('desktop_set_close_to_tray', { enabled: want }).catch((e) => {
      console.warn('[desktop] 同步 closeToTray 失败:', e)
      last = undefined // 下次 prefs 事件允许重试
    })
  }
  sync()
  window.addEventListener('meow:prefs', sync)
}

// ---------------------------------------------------------------- WebDAV 自动同步
//
// 挂在入口而不是某个页面里：用户可能在播放页看了很久、也可能直接关窗口，
// 同步逻辑要独立于当前路由活着。

function startBackgroundJobs() {
  const base = apiBase()

  // 启动时拉一次远端并与本地合并（只在启用时）
  //
  // 状态通道排在它之后：两条通道都是"读本地 → 按 ep_at 合并 → 写回"，
  // 并行跑会互相覆盖（WebDAV 的 pullHistory 是直接 setLocalHistory，
  // 读到旧值的一方会把另一方刚合进来的条目写没）。串行没有性能代价——
  // 都是启动期的一次性动作，且用户此时还在看首屏。
  void autoPullOnStart(base).finally(() => {
    void startStateChannel()
  })

  // 历史变更 → 防抖上传
  window.addEventListener('meow:history-changed', () => {
    scheduleUpload(base, 5000)
  })

  // 设置变更 → 防抖上传（只看同步开关，不看具体改了哪项）
  window.addEventListener('meow:prefs', () => {
    scheduleUpload(base, 3000)
  })

  // 搜索关键词变更 → 防抖上传
  window.addEventListener('meow:search-history', () => {
    scheduleUpload(base, 4000)
  })

  // 手动片头/片尾标记变更 → 防抖上传。
  // 标记只随快照走（dedupe 已把它纳入比较），不监听的话"只标片头"的
  // 用户永远等不到一次上传。
  window.addEventListener('meow:skipmarks-changed', () => {
    scheduleUpload(base, 6000)
  })

  // 页面隐藏/关闭前把待发的推出去，避免丢掉最后一次观看
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') flushUpload(base)
  })
  window.addEventListener('pagehide', () => flushUpload(base))

  // 停用同步时清掉待发队列
  window.addEventListener('meow:webdav', () => {
    if (!getWebdav().enabled) cancelPending()
  })
}

// ---------------------------------------------------------------- 后端状态通道
//
// `/api/state/progress` 是后端自己存的三端共享进度（data 目录里的文件），
// 和 WebDAV 是**两条独立通道**：WebDAV 要用户配地址，这条不要，所以永远是
// "能连上就顺手同步一份"，不影响 WebDAV 原有的行为。
//
// 后端不支持时（老版本没有 "state" 能力）连监听都不注册——否则每次播放都
// 白打一个必然失败的请求，控制台会一直刷错误。

let statePushTimer: number | undefined
let prefsPushTimer: number | undefined
let favPushTimer: number | undefined

/** 清空收藏时最多逐条删多少个远端项；超过就只清本地，避免一次打出几百个请求。 */
const FAV_CLEAR_REMOTE_MAX = 200

/**
 * 把一次收藏变更同步到后端。
 *
 * 后端只有"加一条/删一条"，没有批量替换，所以必须知道**改了什么**——
 * 这也是 favorites store 的事件带 detail 的原因。用"本地列表 vs 上次快照"
 * 做 diff 也能实现，但每端都得存一份快照，反而更容易出错。
 */
async function pushFavoriteChange(change: FavoriteChange): Promise<void> {
  try {
    if (change.action === 'add' && change.item) {
      await addStateFavorite(change.item)
      return
    }
    if (change.action === 'remove' && change.site && change.vod_id) {
      await removeStateFavorite(change.site, change.vod_id)
      return
    }
    if (change.action === 'clear') {
      const remote = await fetchStateFavorites()
      if (remote.length > FAV_CLEAR_REMOTE_MAX) {
        console.warn(
          `[favorites] 远端 ${remote.length} 条超过单次清理上限 ${FAV_CLEAR_REMOTE_MAX}，只清了本地`,
        )
        return
      }
      // 串行删：并发打同一份 state-favorites.json 会撞固定名 .tmp（后端已加锁，
      // 但串行能少一次抢锁，几百条也就几秒）
      for (const x of remote) await removeStateFavorite(x.site, x.vod_id)
      return
    }
    if (change.action === 'restore') {
      // 还原备份是覆盖语义：后端那份必须整体重建，否则下次启动的并集合并
      // 会把刚被覆盖掉的旧收藏又拉回来，用户会以为还原没生效。
      const wanted = listFavorites()
      const remote = await fetchStateFavorites()
      const keep = new Set(wanted.map((x) => `${x.site}|${x.vod_id}`))
      for (const x of remote) {
        if (!keep.has(`${x.site}|${x.vod_id}`)) await removeStateFavorite(x.site, x.vod_id)
      }
      // 再补上本地有、远端没有的（后端只有"加"，重复 add 是幂等的）
      const have = new Set(remote.map((x) => `${x.site}|${x.vod_id}`))
      for (const x of wanted) {
        if (!have.has(`${x.site}|${x.vod_id}`)) await addStateFavorite(x)
      }
    }
  } catch {
    /* 同步失败不影响本地收藏 */
  }
}

/**
 * 正在把远端设置应用到本地。
 *
 * 应用时会派发 `meow:prefs`（否则主题/列数不刷新），而那个事件的监听器
 * 会把"设置变了"当成用户操作再推回后端——拉取立刻变成上传，两台设备
 * 同时启动就会互相覆盖。这个标记只在同步派发期间为真，监听器据此跳过。
 */
let applyingRemotePrefs = false

/**
 * 上次与**某个后端**对齐的设置时间戳；远端不比它新就不覆盖本地。
 *
 * <p><b>为什么时间戳要按后端分开存</b>（2026-10-31 用户报的问题 1）：
 * 原来是全局单值 `meow.prefs.syncedAt`，于是切后端时——
 * 只要目标后端的 `state-prefs.json` 里 `at` 比这个值新，就会**整份覆盖**
 * 本机的 themeStyle / themeMode / homeCols / 弹幕样式 / 连播 / 跳过 等 19 项。
 * 用户看到的是"换个后端主题自己变了"，而且取决于目标后端历史上是谁、什么时候
 * 写过设置，完全无法预期。
 *
 * <p>按后端标识分开之后：连 A 后端时的对齐进度不会影响 B 后端。
 * 首次连一个新后端仍然会接受它的设置（本地没有这个 key 时按 0 处理）——
 * 这是想要的（换设备时能拿到那边的配置），但不会再"随机"发生。
 */
const PREFS_SYNCED_AT = 'meow.prefs.syncedAt'

/**
 * 当前后端标识（用于给同步时间戳分桶）。
 *
 * <p>用 host:port 而不是完整 URL：同一台机器的 http/https、带不带尾斜杠
 * 应当算同一个后端，否则每次换写法都会当成新后端、重新接受一次远端设置。
 */
function prefsSyncScope(): string {
  try {
    const base = apiBase()
    if (!base) return 'local'
    const u = new URL(base)
    return u.host || base
  } catch {
    return 'local'
  }
}

function syncedAtKey(): string {
  return `${PREFS_SYNCED_AT}.${prefsSyncScope()}`
}

/** 只取可同步的键。整份 Prefs 推上去后端也会过滤，但少传些无用字段。 */
function pickSyncedPrefs(p: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const k of SYNCED_PREF_KEYS) {
    if (p[k] !== undefined) out[k] = p[k]
  }
  return out
}

async function pushLocalPrefs(): Promise<void> {
  const r = await pushStatePrefs(pickSyncedPrefs(getPrefs() as unknown as Record<string, unknown>))
  if (!r || !r.at) return
  // 记下服务端时间戳：下次启动若远端 at 不大于它，就不会反过来覆盖本地
  try {
    localStorage.setItem(syncedAtKey(), String(r.at))
  } catch {
    /* 配额满时忽略，最多下次启动多重拉一次 */
  }
}

/**
 * 要推哪一条：事件没有 detail（history.ts 的 persist 派发的是裸事件）。
 *
 * 首选 `listHistory()[0]`——pushHistory 会把刚改的那条挪到 0 位，播放页每次
 * 落盘都是先 pushHistory 再 saveProgress，所以正常情况 0 位就是正在看的那条。
 *
 * 但"只点了封面"也会把新片顶到 0 位（HomeView 的 pushHistory 带通知），那条
 * 没有 pos、推不了；此时退回到 `ep_at` 最新的那条，否则用户在别处看到一半
 * 再点一下封面，最后那段进度就永远上不去。
 */
function latestProgressItem() {
  const list = listHistory()
  if (!list.length) return undefined
  const head = list[0]
  if (Number(head?.pos) >= 0 && Number(head?.ep_at) > 0) return head
  let best = head
  for (const x of list) {
    if (Number(x?.ep_at) > Number(best?.ep_at)) best = x
  }
  return best
}

function pushLatestItem(opts: { keepalive?: boolean } = {}) {
  const item = latestProgressItem()
  if (!item) return
  // fire-and-forget：状态同步失败不能打断播放/浏览
  void pushStateProgress(item, opts)
}

/**
 * 关标签页时 `visibilitychange`(hidden) 和 `pagehide` 会**连着**触发，
 * 按 ep_at 记一下：同一条只推一次，否则每次关窗都白发一个必然被后端
 * 判为 merged:false 的重复 POST。
 */
let lastHiddenPush = ''

function pushOnHide() {
  clearTimeout(statePushTimer)
  statePushTimer = undefined
  const item = latestProgressItem()
  if (!item) return
  const key = `${item.site}|${item.vod_id}|${item.ep_at ?? ''}`
  if (key === lastHiddenPush) return
  lastHiddenPush = key
  // keepalive：普通 fetch 会随页面卸载被砍掉，最后一次进度就到不了后端
  void pushStateProgress(item, { keepalive: true })
}

/**
 * 关页面/切后台时把待发的设置推出去。
 *
 * 不用 keepalive：请求体是整份可同步设置，比单条进度大，keepalive 有 64KB
 * 上限且部分浏览器会直接拒绝；设置丢了下次启动会重拉，代价比进度小得多。
 */
function flushPrefsOnHide() {
  if (!prefsPushTimer) return
  clearTimeout(prefsPushTimer)
  prefsPushTimer = undefined
  void pushLocalPrefs()
}

async function startStateChannel() {
  // 能力探测：hasStateCapability 只在 /api/health **答复了**之后才缓存结果
  // （含 false——老版本后端确实不支持，重试多少次都一样）。请求本身失败时它
  // 不缓存，所以重试几轮能跨过"后端刚起还没监听"的窗口，而不是整场会话都
  // 关掉状态通道。
  let supported = false
  for (let i = 0; i < 5; i++) {
    if (await hasStateCapability()) {
      supported = true
      break
    }
    // 后端明确答了"不支持"就别再等了
    if (stateCapabilitySettled()) break
    await new Promise((r) => setTimeout(r, 1500))
  }
  // 没有 "state" 能力就整段跳过：连监听都不注册，免得每次播放都白打
  // 一个必然失败的请求、控制台一直刷错误。
  if (!supported) return

  // 启动拉一次并合并。applyRemoteHistory 刻意不派发 'meow:history-changed'，
  // 否则"拉取 → 触发上传 → 下次启动又拉取"会变成自激循环。
  const remote = await fetchStateProgress()
  if (remote.length) applyRemoteHistory(remote)

  // 收藏：并集合并（同 site+vod_id 取 at 较新的），不派发事件
  const remoteFavs = await fetchStateFavorites()
  if (remoteFavs.length) applyRemoteFavorites(remoteFavs)

  // 设置：只有远端比本地记录的新才覆盖。
  // 判断依据是后端写入时打的 at，不是本地时间——两台设备的时钟不一定一致。
  const remotePrefs = await fetchStatePrefs()
  if (remotePrefs) {
    let lastAt = 0
    try {
      lastAt = Number(localStorage.getItem(syncedAtKey()) || 0) || 0
    } catch {
      /* 读不到就当 0，最多多重拉一次 */
    }
    if (remotePrefs.at > lastAt) {
      applyingRemotePrefs = true
      try {
        // 不 silent：主题/列数/弹幕样式要靠这个事件刷新 UI
        savePrefs(remotePrefs.prefs as Partial<Prefs>)
      } finally {
        applyingRemotePrefs = false
      }
      try {
        localStorage.setItem(syncedAtKey(), String(remotePrefs.at))
      } catch {
        /* 忽略 */
      }
    }
  }

  window.addEventListener('meow:history-changed', () => {
    // 8 秒防抖：暂停/拖动/切集会在几秒内连出多次事件，逐次 POST 没意义。
    // 比 WebDAV 的 5 秒长，是因为这条通道只推一条，代价在请求数而不是体积。
    clearTimeout(statePushTimer)
    statePushTimer = window.setTimeout(() => pushLatestItem(), 8000)
  })

  window.addEventListener('meow:prefs', () => {
    // 应用远端设置时也会派发这个事件，那不是用户改动，不能推回去
    if (applyingRemotePrefs) return
    // 设置改动更零碎（拖滑块、切下拉），防抖比进度更长一些
    clearTimeout(prefsPushTimer)
    prefsPushTimer = window.setTimeout(() => void pushLocalPrefs(), 4000)
  })

  window.addEventListener('meow:favorites-changed', (ev) => {
    const change = (ev as CustomEvent<FavoriteChange>).detail
    if (!change) return
    // 收藏是明确的一次操作（点一下按钮），不需要长防抖；1 秒只为合并连点
    clearTimeout(favPushTimer)
    favPushTimer = window.setTimeout(() => void pushFavoriteChange(change), 1000)
  })

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
      pushOnHide()
      flushPrefsOnHide()
    }
  })
  window.addEventListener('pagehide', () => {
    pushOnHide()
    flushPrefsOnHide()
  })
}

void bootstrap()
