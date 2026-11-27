/**
 * TV 端原生能力桥。
 *
 * ## 为什么要有这一层
 *
 * 电视上的 WebView 解码能力受盒子固件限制：HEVC/AV1 常不解、Dolby 音轨无声、
 * 4K 掉帧。同一台机器上 ExoPlayer 走 MediaCodec 往往能播。
 *
 * 但"能不能播"只有**试了才知道**——设备能力表不可靠（某些盒子谎报支持）。
 * 所以策略是：WebView 先播，失败了再让原生兜底，而不是一上来就猜。
 *
 * ## 探测方式
 *
 * TV 壳通过 `addJavascriptInterface` 注入 `window.meowTv`。
 * **必须用 typeof 判断而不是 `if (window.meowTv)`**：
 * 旧版本 WebView 在没有该接口时访问 window.meowTv 会抛异常。
 */

import { isDesktop, tauriInvoke } from './index'
import { isTvEnv } from '@/compat/shell'
import type { NativeCodecCaps } from '@/utils/codecCaps'

interface MeowTvBridge {
  nativePlayerAvailable(): boolean
  /**
   * 1.2.22+：原生解码能力（MediaCodecList 实测，JSON 串）。
   * 老壳没有这个方法 → 返回空串，调用方按"未知"处理。
   */
  nativeCaps?(): string
  openNative(url: string, title: string, headers: string, positionMs: number): boolean
  /** 1.2.18+：带弹幕地址的原生播放；老壳没有这个方法 */
  openNativeWithDanmaku?(
    url: string,
    title: string,
    headers: string,
    positionMs: number,
    danmakuUrl: string,
  ): boolean
  /** 1.2.19+：带 meta（进度回传坐标 + 弹幕设置）的原生播放 */
  openNativeFull?(
    url: string,
    title: string,
    headers: string,
    positionMs: number,
    danmakuUrl: string,
    metaJson: string,
  ): boolean
  platform(): string
  appVersion(): string
  /**
   * 弹原生输入框（遥控器打字的唯一方式）。取消返回 null。
   * 第四参 theme 是 JSON（见 nativeInputText），老壳会忽略多余参数。
   */
  inputText?(title: string, initial: string, mask: boolean, theme?: string): string | null
  /**
   * 把当前主题推给壳，供「原生页面」使用（TV 配置页没有 WebView，读不到 CSS）。
   * 老壳没有这个方法，调用处静默跳过。
   */
  setUiTheme?(json: string): void
}

/**
 * 把当前主题的配色打包给原生输入框。
 *
 * 为什么要传：原生 AlertDialog 默认是系统灰底（深色主题下也是一块浅灰），
 * 和页面/播放器放在一起像另一个应用。Java 侧读不到 Web 的 CSS 变量，
 * 所以由页面把实际生效的颜色读出来传过去。
 *
 * 读的是 `getComputedStyle(document.documentElement)`——它拿到的是**当前
 * 生效值**（主题切换靠 html 上的 data-style/data-mode 选择器，变量值会随之
 * 变化），不是 tokens.css 里那份默认值。九套主题（meow/claude/… × dark/light）
 * 全都自动跟随，不用在 Java 里再抄一份色板。
 */
function themePayload(): string {
  try {
    const cs = getComputedStyle(document.documentElement)
    const pick = (name: string, fallback: string) => (cs.getPropertyValue(name) || '').trim() || fallback
    return JSON.stringify({
      base: pick('--color-base', '#12141d'),
      elevated: pick('--color-elevated', '#242838'),
      panel: pick('--color-panel', '#2e3346'),
      accent: pick('--color-accent', '#03dac5'),
      onAccent: pick('--color-on-accent', '#0a0b11'),
      ink: pick('--color-ink', '#ffffff'),
      ink2: pick('--color-ink-2', '#b8bdcc'),
      ink3: pick('--color-ink-3', '#7a8095'),
      warn: pick('--color-warn', '#ff5f00'),
      hot: pick('--color-hot', '#ff0057'),
      line: pick('--color-line-hover', 'rgba(255,255,255,0.12)'),
      field: pick('--color-field', 'rgba(0,0,0,0.3)'),
      radius: pick('--radius-lg', '14px'),
      light: document.documentElement.getAttribute('data-mode') === 'light',
    })
  } catch {
    return ''
  }
}

/** 当前是否跑在 TV 壳里（有原生桥） */
/**
 * O12（2026-11-21）：TV 壳是否支持「同页内嵌原生播放」（embed 桥）。
 * 老壳没有这三个方法 → 回退 openNative（独立 Activity）。
 */
export function hasEmbedPlayer(): boolean {
  return hasNativePlayer() && embedBridge() != null
}

function embedBridge(): { embedPlay(url: unknown, h: unknown, pos: unknown, meta: unknown): boolean; embedClose(): boolean } | null {
  // 桥对象名是 window.meowTv（addJavascriptInterface 注入）——__meowTv 不存在
  const b = (window as unknown as { meowTv?: Record<string, unknown> }).meowTv
  if (!b || typeof b !== 'object') return null
  if (typeof b.embedPlay !== 'function' || typeof b.embedClose !== 'function') return null
  return b as never
}

/** 同页内嵌：原生层接管视频；成功后网页 video 应 pause、UI 被原生层盖住。 */
export function embedPlay(
  url: string,
  headers: string,
  posMs: number,
  metaJson: string,
): boolean {
  const b = embedBridge()
  if (!b) return false
  return !!b.embedPlay(url, headers, posMs, metaJson)
}

export function embedClose(): boolean {
  const b = embedBridge()
  return !!b && !!b.embedClose()
}

export function hasNativePlayer(): boolean {
  try {
    const b = (window as unknown as { meowTv?: MeowTvBridge }).meowTv
    return !!b && typeof b.openNative === 'function' && b.nativePlayerAvailable()
  } catch {
    return false
  }
}

let nativeCapsCache: NativeCodecCaps | null = null

/**
 * 原生播放器的解码能力（TV 壳 `nativeCaps()` 一次探测并缓存）。
 *
 * <p>为什么不能只看 WebView：Android WebView 的 MSE 层经常不报 HEVC Main10，
 * 但同一台机器的 ExoPlayer 走 MediaCodec 能解。分配层拿到这份能力表，
 * 才会"WebView 解不了就自动交给原生"，而不是先转码把 4K 砍成 1080p（#12）。
 *
 * <p>老壳没有 `nativeCaps` / 返回空 / JSON 坏了 → `probed=false`，调用方
 * 按"未知"处理（保持旧行为）。失败也缓存：同一次会话里桥不会中途长出来。
 */
export function nativeCodecCaps(): NativeCodecCaps {
  if (nativeCapsCache) return nativeCapsCache
  const unknown: NativeCodecCaps = { probed: false, video: [], highBitVideo: [], audio: [] }
  try {
    const b = (window as unknown as { meowTv?: MeowTvBridge }).meowTv
    if (!b || typeof b.nativeCaps !== 'function') {
      nativeCapsCache = unknown
      return nativeCapsCache
    }
    const raw = b.nativeCaps()
    if (!raw) {
      nativeCapsCache = unknown
      return nativeCapsCache
    }
    const o = JSON.parse(raw) as Partial<NativeCodecCaps>
    const arr = (v: unknown): string[] =>
      Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string').map((x) => x.toLowerCase()) : []
    nativeCapsCache = {
      probed: true,
      video: arr(o.video),
      highBitVideo: arr(o.highBitVideo),
      audio: arr(o.audio),
    }
    return nativeCapsCache
  } catch {
    nativeCapsCache = unknown
    return nativeCapsCache
  }
}

/** 只用于测试/诊断：清缓存。 */
export function __resetNativeCodecCaps(): void {
  nativeCapsCache = null
}

/** 是否支持原生输入框（TV 壳 0.9.24+；老壳没有这个方法） */
export function hasNativeInput(): boolean {
  try {
    const b = (window as unknown as { meowTv?: MeowTvBridge }).meowTv
    return !!b && typeof b.inputText === 'function'
  } catch {
    return false
  }
}

/**
 * 弹原生输入框，返回用户输入的文本；取消或不可用返回 null。
 *
 * TV 上没有键盘：页面里的 `<input>` 拿到的是 tv-nav 的 `click()`，
 * 而 WebView 只对**真实触摸**弹输入法 —— 所以必须借原生对话框。
 * PC/Web 不走这里（它们的 input 本来就能打字）。
 *
 * 同步阻塞：原生侧用 CountDownLatch 等用户输完，这里直接拿到结果。
 */
export function nativeInputText(title: string, initial = '', mask = false): string | null {
  try {
    const b = (window as unknown as { meowTv?: MeowTvBridge }).meowTv
    if (!b || typeof b.inputText !== 'function') return null
    const r = b.inputText(title, initial, mask, themePayload())
    return typeof r === 'string' ? r : null
  } catch {
    return null
  }
}

/**
 * 这个输入框会不会被软键盘遮住 → 该不该弹原生输入框。
 *
 * 判据是几何位置，与 tv-nav 里 `needsNativeInput` 完全一致（两处必须同源，
 * 否则同一个输入框在"页面接管"和"tv-nav 接管"两条路径上行为不同）：
 * 输入法从底部推起，约占视口下 40%；输入框**底部**越过中线就算可能被遮。
 *
 * 页面侧要用这个而不是硬编码：搜索页的框在上方（bottom≈162 / vh=720），
 * 原生框纯属多一步；AI 面板的框贴在屏幕最底部，必须弹原生框。
 */
export function shouldUseNativeInput(el: Element | null): boolean {
  if (!el) return true
  if (el.hasAttribute('data-tv-native-input')) return true
  const vh = window.innerHeight || document.documentElement.clientHeight || 0
  if (!vh) return true
  return el.getBoundingClientRect().bottom > vh * 0.5
}

/**
 * TV 壳里点普通文本输入框 → **就地编辑**（聚焦 + 弹系统键盘），不弹对话框。
 *
 * <p><b>为什么要这个</b>：TV 壳的 WebView 里，页面上的普通 `<input>` 点上去
 * 拿不到焦点、也不弹输入法（实测：设置页「更新源」adb 点 + `input text`
 * 全都不进，栏位一直是 placeholder）。搜索页能用，是因为它自己调了
 * `beginInlineInput(el)`（focus + 让原生补一次 showIme）。
 *
 * <p><b>为什么不要原生对话框</b>：用户口径（2026-11-13）「对齐搜索页输入框，
 * 不要给我加弹框」——对话框要多点一次「确定」，而且看不到原输入框的
 * 清除按钮/校验。就地输入与搜索页完全一致。
 *
 * <p>非 TV / 没有原生桥 → 返回 false，页面按普通输入框继续。
 *
 * @param ev 输入框的 click 事件（取 currentTarget）
 * @returns 是否已经由 TV 分支处理
 */
export function tvInlineInput(ev: MouseEvent): boolean {
  if (!isTvEnv()) return false
  if (!hasNativeInput()) return false
  beginInlineInput(ev.currentTarget as HTMLElement | null)
  // 阻止浏览器默认聚焦：那一拍会和原生 showIme() 的时序打架（见 beginInlineInput 注释）
  ev.preventDefault()
  return true
}

/**
 * 就地输入：聚焦页面上的输入框 + 让原生补一次系统输入法。
 * * 为什么必须由原生补：WebView 只对**真实触摸**弹输入法，遥控器 OK 不会。
 * 少了这一步，用户看到的是"有焦点框但打不了字"（这正是把输入框
 * 做成原生对话框的起因，但上方输入框不该付那个代价）。
 */
export function beginInlineInput(el: HTMLElement | null): void {
  /*
   * data-tv-ime 是给原生侧找目标用的标记。
   *
   * 为什么需要：原生 showIme() 要先让 WebView 拿到窗口焦点、再重新
   * focus 一次页面元素（顺序反了就只是"输入法弹出来但打不进字"）。
   * 那一秒里 page 侧的 activeElement 可能已经变了（实测成了 <a>），
   * 所以给个稳定标记，让原生按标记找，而不是依赖 activeElement。
   */
  if (el) {
    try {
      const prev = document.querySelector('[data-tv-ime]')
      if (prev && prev !== el) prev.removeAttribute('data-tv-ime')
      el.setAttribute('data-tv-ime', '1')
    } catch {
      /* 属性设置失败不影响后续 */
    }
  }
  try {
    el?.focus()
  } catch {
    /* 不可聚焦就算了 */
  }
  try {
    const b = (window as unknown as { meowTv?: { showIme?: () => boolean } }).meowTv
    if (b && typeof b.showIme === 'function') b.showIme()
  } catch {
    /* 老壳没有这个方法：退化成只有聚焦 */
  }
}

/** 当前平台标识（web / tv）；非 TV 返回 'web' */
export function platformName(): string {
  try {
    const b = (window as unknown as { meowTv?: MeowTvBridge }).meowTv
    return b && typeof b.platform === 'function' ? b.platform() : 'web'
  } catch {
    return 'web'
  }
}

/** 请求头 → 原生侧要的 `k=v&k=v`（值 URL 编码，避免值里的 & 破坏分隔） */
function encodeHeaders(header: unknown): string {
  let obj: Record<string, string> = {}
  if (typeof header === 'string' && header.trim()) {
    try {
      const parsed = JSON.parse(header)
      if (parsed && typeof parsed === 'object') obj = parsed as Record<string, string>
    } catch {
      /* 坏 JSON 就当没有头 */
    }
  } else if (header && typeof header === 'object') {
    obj = header as Record<string, string>
  }
  const pairs: string[] = []
  for (const [k, v] of Object.entries(obj)) {
    if (typeof v !== 'string' || !v) continue
    // 头名里不会有 & =，值可能有（如 Cookie），所以值必须编码
    pairs.push(`${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
  }
  return pairs.join('&')
}

/**
 * 用原生播放器打开。返回 false 表示没拉起来（不是 TV、桥坏了、参数非法），
 * 调用方应退回 WebView 播放。
 */
export function openNative(
  url: string,
  title: string,
  header?: unknown,
  positionSec = 0,
  danmakuUrl?: string,
  metaJson?: string,
): boolean {
  if (!url || !/^https?:/i.test(url)) return false
  try {
    const b = (window as unknown as { meowTv?: MeowTvBridge }).meowTv
    if (!b || typeof b.openNative !== 'function') return false
    const headers = encodeHeaders(header)
    const posMs = Math.max(0, Math.round(positionSec * 1000))
    // 1.2.19+ 才能收 meta（进度回传 + 弹幕设置）；没有就逐级退回
    if (metaJson && typeof b.openNativeFull === 'function') {
      return b.openNativeFull(url, title || '', headers, posMs, danmakuUrl || '', metaJson)
    }
    if (danmakuUrl && typeof b.openNativeWithDanmaku === 'function') {
      return b.openNativeWithDanmaku(url, title || '', headers, posMs, danmakuUrl)
    }
    return b.openNative(url, title || '', headers, posMs)
  } catch {
    return false
  }
}

// ---------------------------------------------------------------- iOS 原生兜底（VLCKit）

/** iOS 壳的原生兜底桥（Bootstrap.swift 注入的 window.meowIos）。 */
interface MeowIosNativeBridge {
  nativeVlc?: boolean
  openNativeVlc?: (o: Record<string, unknown>) => boolean
}

function iosBridge(): MeowIosNativeBridge | null {
  try {
    const b = (window as unknown as { meowIos?: MeowIosNativeBridge }).meowIos
    return b && typeof b === 'object' ? b : null
  } catch {
    return null
  }
}

/**
 * 当前端有没有原生兜底播放器（iOS 的 VLCKit，见 NativeVlcPlayer.swift）。
 *
 * <p>判据是壳注入的 `nativeVlc` 字段，**不是 UA**：同一台 iPhone 用 Safari
 * 打开网页版仍然是 web 壳，不能给原生入口。老壳没有这个字段 → false，
 * 调用方自然退回原来的提示路径。
 */
export function hasIosNativePlayer(): boolean {
  const b = iosBridge()
  return !!b && b.nativeVlc === true && typeof b.openNativeVlc === 'function'
}

/**
 * 交给 iOS 原生播放器（VLCKit）。返回 false = 桥不可用/参数非法，调用方
 * 应继续走当前 web 路径。
 *
 * <p>⚠️ postMessage 是单向通道：返回 true 只代表**消息已投递**，不代表播放
 * 成功。失败由原生侧回传 `window.__meowVlcClosed`（见 onIosNativeClosed），
 * 页面据此恢复并提示，而不是永远卡在 loading。
 */
export function openIosNative(
  url: string,
  title: string,
  header?: unknown,
  positionSec = 0,
  danmakuUrl?: string,
  metaJson?: string,
  apiBaseUrl?: string,
): boolean {
  if (!url || !/^https?:/i.test(url)) return false
  const b = iosBridge()
  if (!b || b.nativeVlc !== true || typeof b.openNativeVlc !== 'function') return false
  try {
    return !!b.openNativeVlc({
      url,
      title: title || '',
      headers: encodeHeaders(header),
      positionMs: Math.max(0, Math.round(positionSec * 1000)),
      danmakuUrl: danmakuUrl || '',
      metaJson: metaJson || '',
      // 控制层要另开一个 WKWebView，那边没有宿主注入的 apiBase ——
      // 不带过去的话控制层页面调后端会全失败（2026-11-27）。
      apiBase: apiBaseUrl || '',
    })
  } catch {
    return false
  }
}

/** 原生播放器关闭时回传的进度。 */
export interface IosNativeClosed {
  positionSec: number
  durationSec: number
  ended: boolean
  /** 关掉原生后要跳去的路由（搜索/设置这类入口），空 = 不跳 */
  nav?: string
}

/**
 * 订阅原生播放器关闭（进度回传）。返回取消订阅函数。
 *
 * <p>同一页面只会有一个原生播放器，所以直接覆盖全局回调而不做多播。
 */
export function onIosNativeClosed(cb: (r: IosNativeClosed) => void): () => void {
  const w = window as unknown as { __meowVlcClosed?: (r: unknown) => void }
  w.__meowVlcClosed = (raw: unknown) => {
    const o = (raw || {}) as Record<string, unknown>
    cb({
      positionSec: Number(o.positionSec) || 0,
      durationSec: Number(o.durationSec) || 0,
      ended: o.ended === true,
      nav: typeof o.nav === 'string' ? o.nav : '',
    })
  }
  return () => {
    if (w.__meowVlcClosed) delete w.__meowVlcClosed
  }
}

// ---------------------------------------------------------------- 原生渲染 + web 控制层
//
// 2026-11-27：iOS 的原生播放器改成「VLC 只渲染画面，控制层用透明 WKWebView
// 加载播放页（native=1）」。这样图一那套控件/弹幕/选集/线路全部复用现有组件，
// 不必在 Swift 里复刻一遍（复刻等于永久维护两套 UI）。

/** 当前页面是不是原生渲染的控制层（NativeVlcPlayer 注入）。 */
export function isIosNativeLayer(): boolean {
  try {
    return (window as unknown as { __meowNativeLayer?: boolean }).__meowNativeLayer === true
  } catch {
    return false
  }
}

/** 原生每 250ms 推来的播放状态。 */
export interface IosNativeState {
  /** 当前位置（秒） */
  t: number
  /** 总时长（秒），0 = 还没拿到 */
  d: number
  /** 是否在播 */
  p: boolean
  /** 视频实际显示区域（aspect-fit 后，像素）——弹幕要按它裁剪，否则飘黑边 */
  vx: number
  vy: number
  vw: number
  vh: number
}

/**
 * 读最近一次原生状态。没收到过返回 null（调用方据此显示"加载中"）。
 *
 * <p>为什么用全局对象而不是事件：推送频率 4Hz，事件订阅要处理解绑；
 * 控制层本来就每帧渲染，直接读快照最省。
 */
export function readIosNativeState(): IosNativeState | null {
  try {
    const raw = (window as unknown as { __meowNativeState?: Record<string, unknown> })
      .__meowNativeState
    if (!raw || typeof raw !== 'object') return null
    return {
      t: Number(raw.t) || 0,
      d: Number(raw.d) || 0,
      p: raw.p === true,
      vx: Number(raw.vx) || 0,
      vy: Number(raw.vy) || 0,
      vw: Number(raw.vw) || 0,
      vh: Number(raw.vh) || 0,
    }
  } catch {
    return null
  }
}

export type IosNativeOp =
  | 'play'
  | 'pause'
  | 'seek'
  | 'rate'
  | 'close'
  /** 控制栏显隐：隐藏时把整个 WebView 收起来，视频回到纯原生渲染 */
  | 'bar'
  /** 弹幕开关（弹幕由原生 VlcDanmakuOverlay 画） */
  | 'dm'
  /** 换集后的新弹幕地址（原生只拿得到拉起那一刻的 danmakuUrl） */
  | 'dmUrl'
  /**
   * 弹幕设置（透明度/字号/速度/区域/屏蔽词/校准）。value 是一个对象，
   * 原生按新参数立即重排 —— 只发开关的话，进了原生页再调面板不生效。
   */
  | 'dmOptions'
  /** 静音/取消静音（原生渲染时 `<video>` 不存在，只能让原生改系统音量） */
  | 'mute'
  /** 关原生后让父页面跳到该路由（搜索/设置） */
  | 'nav'
  /** 换地址继续原生播（选集/换线用，value 是第二参） */
  | 'open'
  | 'orientation'

/**
 * 向原生播放器发指令。返回 false = 桥不在（非原生模式）。
 *
 * <p>postMessage 单向，返回 true 只代表消息已投递。
 */
export function iosNativeCmd(
  op: IosNativeOp,
  value?: number | string | Record<string, unknown>,
): boolean {
  try {
    const b = (window as unknown as {
      meowIos?: { nativeCmd?: (op: string, value: unknown) => boolean }
    }).meowIos
    if (!b || typeof b.nativeCmd !== 'function') return false
    return !!b.nativeCmd(op, value ?? 0)
  } catch {
    return false
  }
}

/**
 * 让原生换片（选集 / 换线路）。
 *
 * <p>与裸 `iosNativeCmd('open', url)` 的区别：把**这一集**的 header 一起带过去。
 * 换线路时防盗链头可能和上一条不同，只换 URL 会 403（真机上表现为"换线路后
 * 一直黑屏"）。原生侧对同一 URL 会忽略（不把进度打回 0）。
 */
export function iosNativeOpen(url: string, header?: unknown): boolean {
  return iosNativeCmd('open', { url, headers: encodeHeaders(header) })
}

// ---------------------------------------------------------------- 系统睡眠

/**
 * 当前环境是否可能支持"让系统睡眠"。
 *
 * 只有 Tauri 桌面端有这条命令；Web / TV 一律返回 false（TV 的 DEVICE_POWER
 * 要系统签名，普通应用拿不到，所以不尝试）。
 *
 * 这里只能判断环境，**判断不了命令是否真的存在**——比桌面端更老的安装包
 * 没有 system_sleep，invoke 会 reject，由 systemSleep() 吞掉返回 false。
 */
export function hasSystemSleep(): boolean {
  if (!isDesktop()) return false
  try {
    const w = window as unknown as {
      __TAURI_INTERNALS__?: { invoke?: unknown }
      __TAURI__?: { core?: { invoke?: unknown } }
    }
    return typeof w.__TAURI_INTERNALS__?.invoke === 'function'
      || typeof w.__TAURI__?.core?.invoke === 'function'
  } catch {
    return false
  }
}

/**
 * 请求系统进入睡眠（桌面端）。返回 false 表示当前端不支持或调用失败。
 *
 * 失败是正常形态：Web/TV 没有这条命令；Windows 开了休眠的系统会改成休眠
 * 而不是睡眠（powrprof 的固有行为，见 docs/38）。调用方不应把它当致命错误。
 */
export async function systemSleep(): Promise<boolean> {
  if (!hasSystemSleep()) return false
  try {
    await tauriInvoke<void>('system_sleep')
    return true
  } catch {
    return false
  }
}

// ---------------------------------------------------------------- 电源动作（关机 / 退出应用）
//
// 见 site/docs/dev/power-actions.md。TV 侧由原生层计时（WebView 退后台会节流），
// PC 侧沿用前端计时 + Tauri 命令。

/** PC：桌面端退出应用命令是否存在（只判运行时；老包调用时 reject）。 */
export function hasDesktopQuit(): boolean {
  // 与 hasSystemSleep 同一套运行时判据，分开命名是为了调用点语义清楚
  return hasSystemSleep()
}

/** PC：退出应用。命令会 `app.exit(0)`，进程随即退出，promise 可能不 resolve。 */
export async function desktopQuit(): Promise<boolean> {
  if (!hasDesktopQuit()) return false
  try {
    await tauriInvoke<void>('desktop_quit')
    return true
  } catch {
    return false
  }
}

/** TV 桥的电源能力。 */
export interface TvPowerCaps {
  shutdown: 'probe' | 'no'
  quit: boolean
}

function tvPower(): Record<string, any> | null {
  try {
    const b = (window as unknown as { meowTv?: Record<string, any> }).meowTv
    return b && typeof b === 'object' ? b : null
  } catch {
    return null
  }
}

export function tvPowerCaps(): TvPowerCaps {
  try {
    const b = tvPower()
    if (!b || typeof b.powerCaps !== 'function') return { shutdown: 'no', quit: false }
    const v = JSON.parse(String(b.powerCaps())) as TvPowerCaps
    return { shutdown: v.shutdown === 'probe' ? 'probe' : 'no', quit: !!v.quit }
  } catch {
    return { shutdown: 'no', quit: false }
  }
}

/** 探测 root 授权（选「关机」时调；首次会弹授权框）。 */
export function tvProbeRoot(): { ok: boolean; reason?: string } {
  try {
    const b = tvPower()
    if (!b || typeof b.powerProbeRoot !== 'function') return { ok: false, reason: 'NO_BRIDGE' }
    return JSON.parse(String(b.powerProbeRoot()))
  } catch {
    return { ok: false, reason: 'FAILED' }
  }
}

// 到点的 shutdown / quit 由原生层定时器直接执行（TvBridge.powerRunnable），
// 不在 JS 侧封装 tvShutdown / tvQuit：多一条 JS 到点路径就多一个"WebView 被节流
// 导致定时不准"的隐患。JS 只负责设置定时与显示状态。

export function tvStartPowerTimer(delayMs: number, action: string): boolean {
  try {
    const b = tvPower()
    return !!b && typeof b.startPowerTimer === 'function' && !!b.startPowerTimer(delayMs, action)
  } catch {
    return false
  }
}

export function tvCancelPowerTimer(): void {
  try {
    const b = tvPower()
    if (b && typeof b.cancelPowerTimer === 'function') b.cancelPowerTimer()
  } catch {
    /* 忽略 */
  }
}

export interface TvPowerTimerState {
  active: boolean
  action: string
  remainingMs: number
}

export function tvPowerTimerState(): TvPowerTimerState {
  try {
    const b = tvPower()
    if (!b || typeof b.powerTimerState !== 'function') {
      return { active: false, action: '', remainingMs: 0 }
    }
    return JSON.parse(String(b.powerTimerState())) as TvPowerTimerState
  } catch {
    return { active: false, action: '', remainingMs: 0 }
  }
}

// ---------------------------------------------------------------- 片库本地副本（TV）
//
// 片库数据全来自后端（NAS）。断网或 NAS 重启时海报墙会变灰，而"用 NAS 库"
// 的目的恰恰是局域网脱网访问。TV 壳提供了私有目录（`files/library/`）做只读副本：
// 后端给出的数据顺手存一份，后端不可达时拿出来用。
//
// ★ 只读副本，不是独立数据源：永远后端优先，这些方法只在拉不到时兜底。

interface MeowTvMirror {
  saveMetaMirror?(json: string): boolean
  loadMetaMirror?(): string
  cachePoster?(url: string): string
  posterMirrorBase?(): string
  metaMirrorStats?(): string
  clearMetaMirror?(): boolean
}

function mirrorBridge(): MeowTvMirror | null {
  try {
    const b = (window as unknown as { meowTv?: MeowTvMirror }).meowTv
    return b && typeof b.saveMetaMirror === 'function' ? b : null
  } catch {
    return null
  }
}

/** 当前端是否支持片库本地副本（TV 壳 0.9.26+） */
export function hasMetaMirror(): boolean {
  return mirrorBridge() !== null
}

/** 存一份元数据快照（后端返回的原始 JSON 文本） */
export function saveMetaMirror(json: string): boolean {
  const b = mirrorBridge()
  if (!b?.saveMetaMirror) return false
  try {
    return !!b.saveMetaMirror(json)
  } catch {
    return false
  }
}

/** 读本地元数据快照；没有返回空串 */
export function loadMetaMirror(): string {
  const b = mirrorBridge()
  if (!b?.loadMetaMirror) return ''
  try {
    return b.loadMetaMirror() || ''
  } catch {
    return ''
  }
}

/** 让原生把一张海报存到本地，返回文件名（失败空串） */
export function cachePoster(url: string): string {
  const b = mirrorBridge()
  if (!b?.cachePoster) return ''
  try {
    return b.cachePoster(url) || ''
  } catch {
    return ''
  }
}

/** 本地副本的访问前缀，如 `http://meow.local/mirror/poster/` */
export function posterMirrorBase(): string {
  const b = mirrorBridge()
  if (!b?.posterMirrorBase) return ''
  try {
    return b.posterMirrorBase() || ''
  } catch {
    return ''
  }
}

/** 清空本地副本（设置页用） */
export function clearMetaMirror(): boolean {
  const b = mirrorBridge()
  if (!b?.clearMetaMirror) return false
  try {
    return !!b.clearMetaMirror()
  } catch {
    return false
  }
}

// ---------------------------------------------------------------- 系统音量 / 亮度（2026-11-03）

/**
 * 系统级音量与亮度。
 *
 * <p><b>为什么必须走原生</b>（用户明确要求"亮度、音量都走系统级"）：
 * <ul>
 *   <li><b>亮度</b>：Web 只有 CSS `filter: brightness()`，**只能变暗**（0–1），
 *       调不亮；而手机上用户最常做的就是"太暗了调亮"。真正的系统亮度
 *       要 `WindowManager.LayoutParams.screenBrightness`（Android）/
 *       `UIScreen.main.brightness`（iOS），浏览器两者都没有。</li>
 *   <li><b>音量</b>：TV 端已经把遥控器音量键旁路掉了（只保留静音），
 *       页面上再没有别的入口；而 `video.volume` 是**元素音量**，
 *       用户调完退出应用会发现"系统音量没变"。要靠
 *       `AudioManager.STREAM_MUSIC`。</li>
 * </ul>
 *
 * <p><b>接口设计成"读当前值 + 设当前值"而不是"加/减一步"</b>：
 * 手势是连续的（拖动多少像素就变多少），如果只给 step 接口，页面要自己
 * 记绝对值，一旦系统侧被别的应用改了就会漂。读回来再写回去才能对齐。
 */
interface MeowTvSystemCtl {
  /** 系统媒体音量 0–100；读不到返回 -1 */
  getVolume?(): number
  /** 设系统媒体音量 0–100；成功返回 true */
  setVolume?(v: number): boolean
  /** 屏幕亮度 0–100；-1 = 自动/读不到 */
  getBrightness?(): number
  /** 设屏幕亮度 0–100（100 及以上转成"跟随系统自动"）；成功返回 true */
  setBrightness?(v: number): boolean
}

function systemCtl(): MeowTvSystemCtl | null {
  try {
    const b = (window as unknown as { meowTv?: MeowTvSystemCtl }).meowTv
    if (!b || typeof b.setVolume !== 'function') return null
    return b
  } catch {
    return null
  }
}

/**
 * iOS 壳的系统音量桥（IPA）。
 *
 * <p>WKWebView 里 `video.volume = x` 静默无效，壳通过 MPVolumeView 写系统音量：
 * `meowIos.volume` 是启动快照（0–100，-1 = 读不到），`setVolume(v)` 单向设置。
 * 读不到（-1）就当没有桥，播放器退回「请用手机侧键」提示，不假装能调。
 */
interface MeowIosSysCtl {
  volume?: number
  setVolume?(v: number): void
  /** 启动快照（0–100）；-1 = 读不到 */
  brightness?: number
  getBrightness?(): number
  /** 传 -1 还原进入播放页前的亮度 */
  setBrightness?(v: number): void
}

function iosCtl(): MeowIosSysCtl | null {
  try {
    const b = (window as unknown as { meowIos?: MeowIosSysCtl }).meowIos
    if (!b || typeof b.setVolume !== 'function') return null
    return b
  } catch {
    return null
  }
}

/** 只要 setBrightness 存在就算有亮度桥（iOS 壳） */
function iosBrightnessCtl(): MeowIosSysCtl | null {
  try {
    const b = (window as unknown as { meowIos?: MeowIosSysCtl }).meowIos
    return b && typeof b.setBrightness === 'function' ? b : null
  } catch {
    return null
  }
}

/** 这个环境能不能调系统音量（TV 壳 / iOS 壳；浏览器/PC 都返回 false） */
export function hasSystemVolume(): boolean {
  const b = systemCtl()
  if (b && typeof b.getVolume === 'function' && typeof b.setVolume === 'function') return true
  // iOS：setVolume 存在就认（volume 快照可能是 -1，只影响手势起点，不影响能不能写）
  return !!iosCtl()
}

export function hasSystemBrightness(): boolean {
  const b = systemCtl()
  if (b && typeof b.getBrightness === 'function' && typeof b.setBrightness === 'function') return true
  return !!iosBrightnessCtl()
}

/** 读系统音量（0–100）；不可用返回 null */
export function systemVolume(): number | null {
  const b = systemCtl()
  if (b?.getVolume) {
    try {
      const v = Number(b.getVolume())
      if (Number.isFinite(v) && v >= 0) return Math.min(100, Math.round(v))
    } catch {
      /* 落 iOS / 兜底 */
    }
  }
  const ios = iosCtl()
  const iv = Number(ios?.volume)
  return Number.isFinite(iv) && iv >= 0 ? Math.min(100, Math.round(iv)) : null
}

/** 设系统音量（0–100）；不可用或失败返回 false */
export function setSystemVolume(v: number): boolean {
  const pct = Math.max(0, Math.min(100, Math.round(v)))
  const b = systemCtl()
  if (b?.setVolume) {
    try {
      return !!b.setVolume(pct)
    } catch {
      /* 落 iOS / 兜底 */
    }
  }
  const ios = iosCtl()
  if (ios?.setVolume) {
    try {
      ios.setVolume(pct)
      // 同步本地快照：下次 systemVolume() 读到的就是刚设的值（原生异步回读没必要）
      if (Number.isFinite(Number(ios.volume))) ios.volume = pct
      return true
    } catch {
      return false
    }
  }
  return false
}

/** 读屏幕亮度（0–100）；-1（自动）或不可用都返回 null */
export function systemBrightness(): number | null {
  const b = systemCtl()
  if (b?.getBrightness) {
    try {
      const v = Number(b.getBrightness())
      if (Number.isFinite(v) && v >= 0) return Math.min(100, Math.round(v))
    } catch {
      /* 落 iOS / 兜底 */
    }
  }
  const ios = iosBrightnessCtl()
  if (ios) {
    try {
      const v = Number(ios.getBrightness ? ios.getBrightness() : ios.brightness)
      if (Number.isFinite(v) && v >= 0) return Math.min(100, Math.round(v))
    } catch {
      /* 读不到就当没有 */
    }
  }
  return null
}

/** 设屏幕亮度（0–100）；不可用或失败返回 false。iOS 传 -1 表示还原 */
export function setSystemBrightness(v: number): boolean {
  const pct = Math.round(v)
  const b = systemCtl()
  if (b?.setBrightness) {
    try {
      return !!b.setBrightness(Math.max(0, Math.min(100, pct)))
    } catch {
      /* 落 iOS / 兜底 */
    }
  }
  const ios = iosBrightnessCtl()
  if (ios?.setBrightness) {
    try {
      ios.setBrightness(pct < 0 ? -1 : Math.max(0, Math.min(100, pct)))
      if (pct >= 0 && Number.isFinite(Number(ios.brightness))) ios.brightness = pct
      return true
    } catch {
      return false
    }
  }
  return false
}

/** 还原进入播放页前的亮度（iOS 壳；其它端 no-op） */
export function restoreSystemBrightness(): void {
  const ios = iosBrightnessCtl()
  if (!ios?.setBrightness) return
  try {
    ios.setBrightness(-1)
  } catch {
    /* 忽略 */
  }
}

/**
 * iOS 边缘返回手势开关。
 *
 * <p>播放页/直播页要关掉：左边缘右滑会被原生当成"返回上一页"，
 * 和横滑调进度、全屏手势打架。其它页面保持开启（用户要的系统返回手势）。
 */
export function setIosNavGesture(on: boolean): void {
  try {
    const b = (window as unknown as { meowIos?: { setNavGesture?: (v: boolean) => void } }).meowIos
    if (b && typeof b.setNavGesture === 'function') b.setNavGesture(on)
  } catch {
    /* 旧壳没有这个桥：忽略 */
  }
}

/**
 * iOS 音量 HUD 接管开关。
 *
 * <p>true = 当前页接管（挂 MPVolumeView，系统音量 HUD 被抑制，页面自己弹浮层）；
 * false = 摘掉，让系统音量条/系统悬浮层恢复（2026-11-26 用户要求：
 * 任意页面按音量键都要能看到系统音量条）。
 */
export function setIosVolumeHud(on: boolean): void {
  try {
    const b = (window as unknown as { meowIos?: { volumeHud?: (v: boolean) => void } }).meowIos
    if (b && typeof b.volumeHud === 'function') b.volumeHud(on)
  } catch {
    /* 旧壳没有这个桥：忽略 */
  }
}

/** iOS 原生倒计时（秒；0 = 取消）。返回是否交给了原生层。 */
export function iosPowerTimer(seconds: number): boolean {
  try {
    const b = (window as unknown as { meowIos?: { powerTimer?: (s: number) => void } }).meowIos
    if (b && typeof b.powerTimer === 'function') {
      b.powerTimer(Math.max(0, Math.round(seconds)))
      return true
    }
  } catch {
    /* 忽略 */
  }
  return false
}

/** iOS 系统通知（导入完成等）。其它端 no-op。 */
export function iosNotify(title: string, body: string): void {
  try {
    const b = (window as unknown as {
      meowIos?: { notify?: (t: string, b: string) => void }
    }).meowIos
    if (b && typeof b.notify === 'function') b.notify(title, body)
  } catch {
    /* 忽略 */
  }
}

/* ------------------------------------------------------------------ 输入状态同步 */

/**
 * 把「页面里是否有输入框正在聚焦编辑」推给 TV 壳。
 *
 * <p><b>为什么必须由页面推</b>（2026-11-03，修「输入法打字时方向键被壳吃掉」）：
 * 壳的 `dispatchKeyEvent` 是同步的，`evaluateJavascript` 是异步的，
 * 它没办法在按键那一刻知道 activeElement 是不是输入框。不判断的后果：
 * 用户在搜索框里输入时，方向键被壳拿去搬焦点（焦点跳到历史胶囊），
 * 再按 OK 就点到背景；输入法里也没法用方向键选词。
 *
 * <p>推的时机只有焦点变化，开销可忽略；壳侧读一个 volatile 字段。
 * 没有 `setEditing`（Web/PC/TV 旧包）时整段静默跳过。
 */
function syncEditingToShell(): void {
  const b = (window as unknown as { meowTv?: { setEditing?: (v: boolean) => void } }).meowTv
  if (!b || typeof b.setEditing !== 'function') return
  const editable = (el: Element | null): boolean => {
    if (!el) return false
    const tag = el.tagName
    return tag === 'INPUT' || tag === 'TEXTAREA' || (el as HTMLElement).isContentEditable
  }
  const push = (v: boolean) => {
    try {
      b.setEditing?.(v)
    } catch {
      /* 桥调用失败不该影响页面 */
    }
  }
  document.addEventListener('focusin', (e) => push(editable(e.target as Element)), true)
  document.addEventListener(
    'focusout',
    () => {
      // focusout 派发时 activeElement 还没切换，下一帧再读
      window.setTimeout(() => push(editable(document.activeElement)), 0)
    },
    true,
  )
  // 首帧可能已经有焦点（例如页面恢复时输入框是 activeElement）
  push(editable(document.activeElement))
}

if (typeof document !== 'undefined') syncEditingToShell()

/**
 * 把当前主题推给原生壳。
 *
 * <p><b>两个消费者</b>：
 * <ul>
 *   <li>TV：`setUiTheme(json)` 存进 SharedPreferences，配置页（没有 WebView，
 *       读不到 CSS 变量）下次打开时用这份色板，不再写死 #0e0f12/#14bfa8；</li>
 *   <li>PC：`desktop_set_window_bg` 改 Tauri 窗口底色。窗口在 WebView
 *       首帧之前/缩放时会露出这层底色，写死在 tauri.conf.json 里会在浅色
 *       主题下闪一块深 navy。</li>
 * </ul>
 * 两个都是可选能力：老壳 / Web 端静默跳过。
 */
function pushThemeToShell(): void {
  const json = themePayload()
  if (!json) return
  try {
    const b = (window as unknown as { meowTv?: { setUiTheme?: (v: string) => void } }).meowTv
    if (b && typeof b.setUiTheme === 'function') b.setUiTheme(json)
  } catch {
    /* 桥调用失败不该影响页面 */
  }

  if (!isDesktop()) return
  try {
    const base = (getComputedStyle(document.documentElement)
      .getPropertyValue('--color-base') || '').trim()
    if (/^#[0-9a-f]{6}$/i.test(base)) {
      // Tauri 命令可能不存在（旧版本壳），失败就算了
      void Promise.resolve(tauriInvoke('desktop_set_window_bg', { color: base })).catch(() => {})
    }
  } catch {
    /* 忽略 */
  }
}

if (typeof window !== 'undefined') {
  window.addEventListener('meow:theme', pushThemeToShell)
  // 首帧补推一次：applyTheme 可能在模块加载前已经跑过（installThemeWatcher
  // 在 main.ts 很早的位置），那时的事件本模块收不到。
  pushThemeToShell()
}

/**
 * TV 壳的 Python 运行时模块状态（`py-runtime.json` 原文解析）。
 *
 * <p>没装模块 / 老壳没有 `pyRuntime` 方法 → 返回 null（UI 上不渲染这一行，
 * 而不是显示"未安装"误导用户去装一个他可能根本不需要的东西）。
 *
 * <p>字段：`{running, port, version, error, pid}`。`running:true` 表示 Python
 * 的 pyrt 已经在 `port` 上监听。
 */
export function tvPyRuntime(): Record<string, unknown> | null {
  try {
    const b = (window as unknown as { meowTv?: Record<string, unknown> }).meowTv
    if (!b || typeof b.pyRuntime !== 'function') return null
    const raw = (b.pyRuntime as () => unknown)()
    if (typeof raw !== 'string' || !raw.trim().startsWith('{')) return null
    const obj = JSON.parse(raw) as Record<string, unknown>
    // 空对象 = 还没跑过（文件不存在），按"无状态"处理
    return Object.keys(obj).length > 0 ? obj : null
  } catch {
    return null
  }
}
