<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import VideoPlayer from '@/components/player/VideoPlayer.vue'
import { pickVodName } from '@/utils/introText'
import {
  apiBase,
  castBase,
  fetchDetail,
  fetchParse,
  fetchPlay,
  fetchPolicy,
  fetchStateProgress,
  parsePlayLines,
  prefetchDanmaku,
  probeLine,
  proxyDanmaku,
  proxyStream,
  sanitizeCastHeader,
} from '@/api'
import {
  cacheIdFor,
  cachedPlaylist,
  cancelTranscode,
  cancelTranscodeOnLeave,
  fetchMediaProbe,
  isStrmFile,
  libraryStream,
  peekMediaProbe,
  resolveLibraryStrm,
  startTranscode,
  transcodeAvailable,
  transcodeErrorMessage,
  transcodePhaseText,
  waitLibraryTranscode,
  waitTranscode,
} from '@/api/library'
import { skipWindowFrom, startAtFrom, subTracksFrom, subtitleFormat } from '@/api/playExtras'
import { isHi10p, resolvePlaybackRoute, webCanDecodeVideo, webviewAudioCodecs } from '@/utils/playRoute'
import { detectCodecCaps } from '@/utils/codecCaps'
import { transcodeBadge, transcodeMediaInfo, type MediaSourceInfo } from '@/utils/mediaInfo'
import { fmtSeekClock, transcodeRestartStart } from '@/utils/transcodeSeek'
import type { MediaProbeResult } from '@/api/library'
import { embedPlay, hasEmbedPlayer, hasIosNativePlayer, hasNativePlayer, isIosNativeLayer, iosNativeOpen, nativeCodecCaps, onIosNativeClosed, openIosNative, openNative } from '@/api/nativeBridge'
import { meowDiagnose } from '@/ai/tools'
import { explainDiagnosis } from '@/ai/llm'
import { CAUSE_LABEL, type Diagnosis } from '@/ai/diagnose'
import { isPlayErrorPayload, shouldTranscode, type PlayErrorKind, type PlayErrorPayload } from '@/components/player/playError'
import type { PlayResult } from '@/api/types'
import { getPrefs, setPref } from '@/stores/prefs'
import { getDmOffset } from '@/stores/dmOffset'
import { isIosShell, isTvEnv, isTvShell, isWideLayout } from '@/compat/shell'
import { isAndroid, isFirefox } from '@/compat/ua'
import { getLinePref, setLinePref } from '@/stores/linePref'
import { lineBudget, noteLineFail, noteLineOk, sortLinesByHealth } from '@/stores/lineHealth'
import {
  clearProgress,
  episodeNumber,
  findHistoryByTitle,
  getProgress,
  pushHistory,
  saveProgress,
} from '@/stores/history'
import {
  consumeNewEpisodes,
  markSeen,
  sessionNewEpisodes,
  type NewEpisodesRange,
} from '@/stores/favUpdates'
import { useAppStore } from '@/stores/app'
import { addWatchTime } from '@/stores/watchStats'
import {
  notePrefetch,
  notePrefetchProgress,
  prefetchDiag,
  prefetchKey,
  putPlayPrefetch,
  takePlayPrefetch,
} from '@/utils/playPrefetch'

/** 原生播放器是否已经兜底过一次（避免来回弹 Activity / 无限重试） */
let nativeTried = false
/** 转码兜底只试一次：同一集反复起 ffmpeg 会拖垮机器 */
let transcodeTried = false
/** 片库文件编码探测结果（后端 ffprobe + 内存缓存）；null = 未探测/老后端 */
const mediaProbe = ref<MediaProbeResult | null>(null)
/** 「音频零解码」出路提示条；带原生按钮时不清计时 */
const silentHint = ref('')
const silentHintNative = ref(false)
/**
 * 提示条上的「转码播放」按钮（2026-11-23 实报）：设备没有 Dolby 解码
 * （模拟器 / 无授权的盒子）时原生播放也没音，转码（AAC）是唯一有声路径 ——
 * 不能只给「原生播放」一条死路。
 */
const silentHintTranscode = ref(false)
let silentHintTimer: number | undefined
/** WebView 解码能力表（分配决策的输入；按端差异收敛在这里） */
const WEBVIEW_VIDEO_CODECS_TV = ['h264', 'hevc']
const WEBVIEW_VIDEO_CODECS_DESKTOP = ['h264']
/**
 * 实测能力（2026-11-24 降画质实报）：只在 Chromium/Android WebView 里探，
 * 探测不到（Safari/SSR/老内核）就退回上面的硬编码表 —— 不回归。
 */
const codecCaps = detectCodecCaps()
function audioCodecsNow(): string[] {
  return codecCaps.probed ? codecCaps.audio : webviewAudioCodecs()
}
function videoCodecsNow(): string[] {
  if (codecCaps.probed) return codecCaps.video
  return isAndroid() ? WEBVIEW_VIDEO_CODECS_TV : WEBVIEW_VIDEO_CODECS_DESKTOP
}
/** 组一次路由输入，避免四处调用各写一遍 */
function routeInput(probe: Parameters<typeof resolvePlaybackRoute>[0]['probe']) {
  return {
    probe,
    audioCodecs: audioCodecsNow(),
    videoCodecs: videoCodecsNow(),
    hasNative: hasNativePlayer(),
    preferNativeOnSilent: getPrefs().nativeOnSilent === true,
    transcodeAvailable: transcodeOk.value,
    caps: codecCaps,
    // 原生播放器实测能力：WebView 解不了的 HEVC Main10 交给 ExoPlayer 原画播放（#12）
    nativeCaps: nativeCodecCaps(),
    // remux 只对片库开：在线源 copy 成 HLS 的时间戳风险大、4K copy 带宽也高
    allowRemux: isLibraryPlay.value,
  }
}
/** 客户端能否解 HEVC：repair 的输出编码门禁（低端 TV 只出 H.264） */
function clientCanHevc(): boolean {
  if (!codecCaps.probed) return false
  return codecCaps.video.includes('hevc') || codecCaps.highBitVideo.includes('hevc')
}

/** 「已转码播放」提示：用户要知道画面为什么晚了几秒才出 */
const transcodeHint = ref('')

/** 上一次进度回调的位置，用来算"播放器实际推进了多少秒" */
let lastTickPos = 0
/*
 * 高丢帧自愈的重试预算（2026-11-24 实报「还在卡」）：
 * 第一次 repair 可能因为上游探测超时/限流失败（code=probe_failed），
 * 旧实现一次失败就永久放弃 → 用户整集都在卡。现在最多试 3 次、间隔 15 秒。
 */
let healAttempts = 0
let healRetryAt = 0
let healPayload: {
  dropped: number
  total: number
  ratio: number
  levelUrl?: string
  height?: number
} | null = null
/** 播放器上报的累计观看秒数（每集从 0 重计），用于取增量 */
let lastWatched = 0

const route = useRoute()
const router = useRouter()
const store = useAppStore()

const site = route.params.site as string
/**
 * 片库直链模式：地址由片库页放在 query 里。
 *
 * **必须绕开 fetchPlay 与 proxyStream**——片库没有 spider 的 play 契约；
 * 而 proxyStream 那条端点用 isSafeMediaUrl 校验、目标是"任意 URL"，
 * 片库流是独立端点 `/api/library/stream`（只认配置里的库 + 相对路径）。
 *
 * ★ 必须是 computed，不能是 `const x = route.query.direct`（2026-11-02 修）。
 *   App.vue 的 key 是 `route.path`，而片库播放页的 path 恒为
 *   `/play/__library__` —— 在片库页连点两集时组件**不会重建**，
 *   读一次的快照会一直指着上一集，表现是"点了 B 还在播 A"。
 */
const libraryDirect = computed(() => (route.query.direct as string) || '')
const isLibraryPlay = computed(() => !!libraryDirect.value)
/** TEMP 诊断（localStorage meow.debug=1 打开）：记录分配层关键决策。定案后删。 */
const dbg = (...a: unknown[]) => {
  try {
    if (localStorage.getItem('meow.debug') !== '1') return
    const w = window as unknown as { __dbg?: string[] }
    if (!w.__dbg) w.__dbg = []
    w.__dbg.push(a.map(String).join(' '))
  } catch {
    /* 忽略 */
  }
}
/**
 * 片库播放的原始坐标（lib id + 相对路径）。
 *
 * <p>转码兜底要用它们而不是流地址：流地址在 TV 本地网关上是回环地址
 * （SSRF 防线会拒），而 SMB 里的文件 ffmpeg 也读不到，只能让后端
 * 按配置自己取。
 */
const libraryLib = computed(() => (route.query.library as string) || '')
const libraryFile = computed(() => (route.query.file as string) || '')
const flag = ref((route.query.flag as string) || '')
/**
 * 剧集 id。
 *
 * 源站经常把整条 URL 塞进 id（含 ? & |），详情页做了 encodeURIComponent
 * 以避免 query 被截断；vue-router 读取时只解一层，所以这里再解一次兜底，
 * 对没编码过的普通 id 是无害的。
 */
const rawId = ref(safeDecode((route.query.id as string) || ''))
/**
 * 剧名。
 *
 * <p>兜底洗一次 `pickVodName`（2026-11-27）：DetailView 已经在入口洗过一遍，
 * 但**旧历史记录 / 收藏**里存的是当时的 `vod_name`，可能是
 * `id$$$片名$$$海报$$$备注` 的打包串。这个值同时用于顶栏显示和弹幕接口的
 * `name` 参数，不洗就会「标题显示一串 id、弹幕永远搜不到」。
 */
const title = pickVodName(route.query.name as string)
/** 年份：详情页透传，仅供弹幕后端挑候选（同名不同年的剧） */
const titleYear = (route.query.year as string) || ''
const epName = ref((route.query.ep as string) || '')
/** 影片 id：从历史页直达播放时没有 list，靠它把剧集表补回来 */
const vodId = (route.query.vod as string) || ''
/**
 * 当前线路的看门狗预算（#16）：未验证线路用短预算，死链更快跳走。
 * 用 ref 而不是 computed —— 线路健康存在 localStorage，不是响应式数据；
 * 成功/失败时手动刷新。
 */
const lineWatch = ref(lineBudget(site, flag.value))
/** 本集是否已经给这条线路记过一次成功（每次换集/换线重置） */
let lineOkNoted = false
function refreshLineWatch() {
  lineWatch.value = lineBudget(site, flag.value)
}

function safeDecode(s: string): string {
  if (!s.includes('%')) return s
  try {
    return decodeURIComponent(s)
  } catch {
    return s
  }
}

/** 同线路的剧集列表，由详情页带过来（JSON），用于自动连播 */
const playlist = ref<Array<{ name: string; url: string }>>(
  parsePlaylist(route.query.list as string | undefined),
)

/** 所有线路（含剧集），用于播放页内直接换源 */
const lines = ref<Array<{ name: string; episodes: Array<{ name: string; url: string }> }>>([])
/** 补剧集表时记录当前线路的剧集，切线路后要替换 playlist */
let linesLoaded = false

function parsePlaylist(raw?: string): Array<{ name: string; url: string }> {
  if (!raw) return []
  try {
    const arr = JSON.parse(raw)
    if (!Array.isArray(arr)) return []
    return arr
      .filter((x) => x && typeof x.url === 'string')
      .map((x) => ({ name: String(x.name ?? ''), url: safeDecode(x.url) }))
  } catch {
    return []
  }
}

const play = ref<PlayResult | null>(null)
const loading = ref(true)
const error = ref('')
/**
 * 错误来源。
 *  - fetch：根本没拿到播放地址（可能是 parse=1 / 线路挂了）
 *  - play：拿到了地址但播不出来（解码失败 / CDN 拒绝）
 *
 * 两者给用户的提示不一样，混在一起会误导（实测踩过：
 * 解码失败却提示"部分源需要解析器"）。
 */
const errorSource = ref<'fetch' | 'play'>('fetch')
const isFetchError = computed(() => errorSource.value === 'fetch')
/** 拿到了地址但播不出来，且所有线路都试过了 */
const allLinesFailed = computed(
  () => errorSource.value === 'play' && lines.value.length > 1,
)

// ---- 诊断（docs/47 §2.7：主入口就是这张错误页，不是悬浮按钮）----
//
// 规则层零 LLM 依赖、100ms 出结论、断网也能跑；explanation 由模型补，
// 拿不到就是 null，此时只显示 suggestion.text，UI 不做任何区分
// （§2.6：用户不该看到"你没配模型所以只有半套功能"）。
const diagnosis = ref<Diagnosis | null>(null)
const explainText = ref('')
const explainBusy = ref(false)

/**
 * 错误页出现后跑一次诊断。
 *
 * 为什么延后一拍：hls.js 的错误是异步到达的，错误页刚显示时
 * `__playerDebug.lastError` 可能还没写进去，立刻诊断会得到"未发现故障特征"。
 */
async function runDiagnosis() {
  diagnosis.value = null
  explainText.value = ''
  await new Promise((r) => setTimeout(r, 260))
  try {
    const r = await meowDiagnose({
      line: flag.value,
      lines: lines.value.map((l) => l.name),
      watchdogFired:
        !!error.value &&
        errorSource.value === 'play' &&
        (lastPlayKind.value === 'timeout' || lastPlayKind.value === 'stall'),
      parse: play.value?.parse,
      // 「我们这一跳」的原因：诊断靠它把"没有 ffmpeg"和"网络超时"分开
      hopError: hopError.value,
    })
    if (r.ok && r.data) {
      diagnosis.value = r.data
      // 有人话解释就补上；没有就保持空（不提示"未配置"）
      void (async () => {
        explainBusy.value = true
        try {
          const t = await explainDiagnosis(r.data!)
          if (t) explainText.value = t
        } finally {
          explainBusy.value = false
        }
      })()
    }
  } catch {
    // 诊断失败不能影响错误页本身——用户至少还能看到原始错误与换线按钮
  }
}

/**
 * 执行诊断建议。
 *
 * 只认契约里的 8 个 action（§2.8），未知值一律当 none 处理，不猜。
 */
function applySuggestion() {
  const a = diagnosis.value?.suggestion?.action
  switch (a) {
    case 'switch_line':
      switchToNextLine()
      break
    case 'switch_source':
      switchToNextLine()
      break
    case 'retry':
      retryFromError()
      break
    case 'transcode': {
      const raw = play.value?.url
      const u = typeof raw === 'string' ? raw : ''
      if (u) void tryTranscode(u)
      break
    }
    case 'open_logs':
      void router.push({ name: 'logs' })
      break
    default:
      // none / refresh_danmaku / disable_source：这里没有对应入口，只展示
      break
  }
}
const prefs = getPrefs()
const player = ref<InstanceType<typeof VideoPlayer>>()

/** 加载态文案：解析比普通取地址慢，需要告诉用户现在在干什么 */
const loadingText = ref('正在获取播放地址…')
/**
 * 转码等待是 120~600 秒的长任务。每次 load/切集自增；旧等待回来时先比对，
 * 不是当前这一集就只取消后端任务，绝不回写 play.value/error/loadingText
 * （否则切集后旧集的清单会把新集顶掉——2026-11-20 复核发现）。
 */
let playEpoch = 0
/** 当前播放源的编码信息（转码/原生回传），面板与提示条用 */
const mediaInfo = ref<MediaSourceInfo | null>(null)
/** token 过期换新地址后续播的位置（秒）；播放器应用后清零 */
const refreshSeekAt = ref(0)
let staleRefreshBusy = false

/**
 * 两台设备同时要 4K 硬转：后端只允许一路，第二台会得到 hw_busy。
 * 不再直接丢错误页，而是给用户两个真实选择：
 *   · 排队：每 15 秒重试一次（最多 5 分钟），前一台结束就自动接上；
 *   · 换 1080p：切到同片的第一条非 4K 线路（保留当前集）。
 */
const busyHint = ref(false)
const busyRetries = ref(0)
const BUSY_RETRY_MS = 15_000
const BUSY_MAX_RETRIES = 20
const busyRetrying = ref(false)
let busyTimer: number | undefined
let busyKind: 'vod' | 'lib' = 'vod'
let busySrc = ''
let busyLib = ''
let busyFile = ''
/** 排队重试要沿用原任务的模式（copy/repair 的幂等键不同，丢了会起成另一个任务） */
let busyCopy = false
let busyRepair = false
/** 排队重试同样要沿用起点与 HEVC 能力（否则会从 0 起转 / 输出错编码） */
let busyStart = 0
let busyHevc = true

/**
 * 源地址中途过期（403）→ 重新调 play 拿新 token，在原位置续播。
 *
 * 只在"已经成功播过分片后遇到 403"时由播放器触发（见 VideoPlayer 的 stale-src），
 * 每条源一次；刷新失败就落回原来的换线/报错流程。
 */
async function onStaleSrc(p: { at: number; http: number }) {
  if (staleRefreshBusy || !site || !rawId.value) return
  staleRefreshBusy = true
  try {
    const r = await fetchPlay(site, flag.value, rawId.value)
    if (!r || typeof r.url !== 'string' || !r.url) throw new Error('empty url')
    refreshSeekAt.value = p.at > 1 ? p.at : 0
    // 换源交给播放器（它监听 src 变化重建 hls；onLoaded 时按 seekAt 回到原位置）
    play.value = r
    autoRetryHint.value = '播放地址已刷新，继续播放'
    window.setTimeout(() => {
      if (autoRetryHint.value === '播放地址已刷新，继续播放') autoRetryHint.value = ''
    }, 6000)
  } catch {
    /* 刷新失败：不打断，交给原错误流程换线 */
  } finally {
    window.setTimeout(() => {
      staleRefreshBusy = false
    }, 10_000)
  }
}
/**
 * 正在等的转码任务坐标。copy / repair 必须跟着一起记：
 * 后端幂等键含模式，取消时不带模式会停错任务（2026-11-24）。
 */
let pendingTranscode: {
  src?: string
  lib?: string
  path?: string
  copy?: boolean
  repair?: boolean
  /** 起点秒数（起点参与后端幂等键，取消/轮询必须带同一个值） */
  start?: number
} | null = null
/**
 * **已经在播、但后端仍在边转边看**的任务（#15）。
 *
 * <p>`pendingTranscode` 只覆盖"等首片"那段；拿到 playlist 后它就被清了，
 * 而 ffmpeg 其实还在把整部片转完 —— 用户退出播放页/关 TV 后 CPU 继续 100%
 * 直到空闲看门狗（2 分钟）。这里记住坐标，离页/切集/换线时主动 DELETE。
 */
let activeTranscode: {
  src?: string
  lib?: string
  path?: string
  copy?: boolean
  repair?: boolean
  /** 起点秒数（拖动 seek 超界后从目标点重转的任务） */
  start?: number
  /** 源站请求头（点播线路常要 UA/Referer；重转时必须带上） */
  header?: string
} | null = null

/** 主动停掉"还在边转边看"的任务（切集/重试前调用）。 */
function dropActiveTranscode() {
  const t = activeTranscode
  activeTranscode = null
  if (t) void cancelTranscode(t)
}

/** 离页/关闭页面：keepalive 版，请求能在卸载后继续发出。 */
function dropActiveTranscodeOnLeave() {
  const t = activeTranscode
  activeTranscode = null
  if (t) cancelTranscodeOnLeave(t)
}

/** 正在等转码：加载遮罩上给一个「停止等待」入口 */
const transcodeWaiting = ref(false)
/** 本次成功解析的解析器名（来自 /api/parse 的 from） */
const parseFrom = ref('')

/** 续播提示 */
const resumeTip = ref<{ at: number; label?: string } | null>(null)
let resumeApplied = false
/**
 * 拖动 seek 超界 → 从目标点重转后，新清单要在起播时跳回这个位置（秒）。
 * 用 seek-at prop 传递：它由播放器在 loadedmetadata 时应用，比定时器可靠。
 */
let pendingSeekAfterRestart = 0

function applySeekAfterRestart() {
  if (pendingSeekAfterRestart <= 0) return
  refreshSeekAt.value = Math.floor(pendingSeekAfterRestart)
  pendingSeekAfterRestart = 0
}

/**
 * 进度回调是否可以落盘。
 *
 * <p><b>为什么需要这个门禁</b>（2026-11-03 实测：切集后直接跳到 36 分钟）：
 * 切集是「先 flush 旧进度 → router.replace → load() 换地址」三步，
 * 而播放器的 `props.src` 监听会在地址变化时**再补发一次 flushProgress**，
 * 那次携带的是**旧片**的 `currentTime`，但 PlayView 的 `rawId`/`epName`
 * 此时已经换成新集 —— 于是历史里被写成「新集 + 旧集的位置」，
 * 新片 `loaded` 之后 `getProgress` 命中，一开场就 seek 到旧集看了多久的位置
 * （实测第 4 集看到 36:34 点下一集，第 5 集直接从 36:34 开播）。
 *
 * <p>窗口期 = 路由变化 → 新片 `loaded`。这期间旧片的位置已经在
 * `switchEpisode` / `switchLine` 里显式 flush 过了，这里丢掉不会丢数据。
 */
let progressArmed = false

/**
 * 播放地址（走后端代理，解决 Referer/CORS）。
 *
 * 注意：不是所有 source 都返回可直链的 http 地址。实测有源返回
 * `Ksvideo-xxxx` 这类内部加密串，需要平台自己的解析器，
 * 这种情况必须明确提示而不是静默留空（否则页面一片黑，看不出原因）。
 */
/** 这一集是从本地缓存播的（顶栏/提示条据此显示「离线播放」） */
const offlinePlay = ref(false)
/**
 * 片库播放已经放弃直连（预判容器不支持，或直连真的报错了）。
 * 为真时 playableSrc 取 play.value.url（转码产物），不再回落到 query.direct。
 */
const libraryNoDirect = ref(false)
/**
 * `.strm` 直链解析结果（#6）。非空时这一集按**在线播放**处理：
 * playableSrc 包代理、失败走 tryTranscode（不是片库 ffprobe 转码）。
 */
const libraryStrmUrl = ref('')
/**
 * 这一集是 `.strm` 直链（#6）。
 *
 * <p>存在的意义不只是判断：`.strm` 还没解析出目标地址之前，
 * **不能把任何地址交给播放器**（直链文件本身不是媒体，播它必然 404，
 * 错误页还会把后面的正常流程盖掉）。
 */
const libraryIsStrm = computed(
  () => isLibraryPlay.value && !!libraryFile.value && isStrmFile(libraryFile.value),
)
/**
 * 这个片库文件当前内核根本解不了（MKV/AVI…）：**从一开始**就别把直连地址交给
 * `<video>`。
 *
 * <p>2026-11-17 实测：只在 tryLibraryTranscode 里把 libraryNoDirect 置真还是晚了一步——
 * 组件首次渲染时 src 已经是直连地址，`<video>` 挂上 3 秒就报错，错误页盖住整页，
 * 转码就算正常也永远看不到画面（用户看到的就是"播几秒就卡/直接失败"）。
 */
const libraryNeedsTranscode = computed(
  () =>
    isLibraryPlay.value &&
    cantPlayContainerDirectly() &&
    !!libraryLib.value &&
    !!libraryFile.value &&
    unsupportedContainer(libraryFile.value),
)
/**
 * 片库转码「已经转出多少秒」。续播位置超过它就只能从已转好的地方开始。
 *
 * <p>不夹的话：历史里存着 11:30，而清单里只有 74 秒 → hls.js 去等一个
 * 永远不会来的分片，用户看到的就是「播几秒就卡死」（2026-11-17 实测）。
 */
const libraryReadyCap = ref(0)
/**
 * 片库转码的「真实总时长」与「已转出秒数」，直接交给播放器：
 * 转码没转完时 hls 清单只有已转出的分片，`video.duration` 会从 12 秒一路涨到
 * 整集时长（用户看到进度条总长自己在变），有了这两个数就能一次显示对。
 */
const libraryTotalDuration = ref(0)
const libraryAhead = ref(0)
/** 当前清单真实内容的起点（起点重转时 >0；前段是黑场填充，拖回去要重转） */
const libraryStart = ref(0)
/** 转码未完成时每 5 秒刷新一次「已转出」，进度条与可拖动上界跟着走 */
let libraryPollTimer: number | undefined

function stopLibraryPoll() {
  if (libraryPollTimer) {
    window.clearInterval(libraryPollTimer)
    libraryPollTimer = undefined
  }
}

function pollTranscodeProgress(params: Record<string, string>) {
  stopLibraryPoll()
  libraryPollTimer = window.setInterval(async () => {
    try {
      const q = new URLSearchParams({ ...params, mode: 'status' })
      const r = await fetch(`${apiBase()}/api/transcode?${q}`)
      if (!r.ok) return
      const j = (await r.json()) as {
        state?: string
        readySeconds?: number
        duration?: number
        startOffset?: number
      }
      if (j.readySeconds) {
        libraryAhead.value = j.readySeconds
        libraryReadyCap.value = j.readySeconds
      }
      if (typeof j.startOffset === 'number') libraryStart.value = j.startOffset
      if (j.duration) libraryTotalDuration.value = j.duration
      if (j.state === 'ready' || j.state === 'failed' || j.state === 'cancelled') stopLibraryPoll()
    } catch {
      /* 单次失败继续 */
    }
  }, 5000)
}

const playableSrc = computed(() => {
  /*
   * `.strm` 的目标是**外部地址**：必须包代理（源站防盗链、302、CORS 都由
   * /api/proxy/stream 处理），不能像片库直连那样原样交给 <video>（#6）。
   *
   * ★ 直链还没解析出来前**不能给任何地址**：`.strm` 本身不是媒体，
   *   直接播它必然 404（实测：播放器先挂 libraryDirect → manifestLoadError，
   *   错误页把后面的正常流程盖掉）。这里返回空串，等 resolveLibraryStrm
   *   回来再给代理地址。
   */
  if (libraryIsStrm.value) {
    if (!libraryStrmUrl.value) return ''
    const cur = typeof play.value?.url === 'string' ? play.value.url : ''
    // 已经切到转码/修复产物：让它接管（否则会一直退回原直链）
    if (cur.includes('/api/transcode')) return cur
    return proxyStream(libraryStrmUrl.value)
  }
  // 片库直链：后端已给成本地可播地址，**不再包代理**。
  // 转码产物可能是相对路径 /api/transcode/...（TV 走同源代理时 apiBase() 为空），
  // 相对地址交给 <video> 会按当前页面域名解析，正好命中代理，不需要也不该加前缀。
  //
  // ★ 直连只是首选，不是唯一（2026-11-17 实测踩过）：一旦决定转码
  //   （预判命中或直连报错），就必须让位给转码产物。原来这里无条件返回
  //   libraryDirect → <video> 永远挂在直连地址上，转码把 289 个分片全转完了
  //   页面还停在「播放失败」，等于 4K MKV 永远播不出来。
  if (libraryDirect.value) {
    if (libraryNeedsTranscode.value || libraryNoDirect.value) {
      const u = play.value?.url
      return typeof u === 'string' ? u : ''
    }
    return libraryDirect.value
  }
  // 离线缓存：playlist.m3u8 是自家相对地址，直接交给播放器。
  // 走 proxyStream 会被当成"外部 URL"再包一层（实测变成
  // /api/proxy/stream?url=%2Fapi%2Fcache%2F... → 403），而分片是相对本目录的，
  // 套上代理后按代理地址解析必然 404。
  if (offlinePlay.value) {
    const u = play.value?.url
    return typeof u === 'string' ? u : ''
  }
  const raw = play.value?.url
  const url =
    typeof raw === 'string'
      ? raw
      : Array.isArray(raw)
        ? raw.find((x) => x.startsWith('http')) || ''
        : ''
  // 自家的 /api 路径（转码产物、缓存清单）已经是"本机可播地址"，再套 proxyStream
  // 只会得到一层 url= 编码，播放器解析不到相对分片。
  if (url.startsWith('/api/')) return url
  /*
   * 自家后端的**绝对**地址（转码产物 /api/transcode/...）也不能套代理：
   * 包一层会变成 /api/proxy/stream?url=http://127.0.0.1:9979/... → 回环地址
   * 被 SSRF 防线拒（实测：4K 自愈切到转封装后画面还停在旧源）。
   */
  const apiRoot = apiBase().replace(/\/+$/, '')
  if (apiRoot && url.startsWith(apiRoot + '/api/')) return url
  if (!url || !/^https?:/i.test(url)) return ''
  /*
   * iOS：媒体**不经过 JVM 代理**（2026-11-24 真机实测）。
   *
   * 端内 JVM 是 Zero 解释执行，TLS 解密 + 字节拷贝全在解释器里跑：一个 500KB
   * 分片要 4~6 秒（~100KB/s），HLS 永远起不来；更糟的是每个分片请求会**长时间
   * 占住网关线程**，十几路之后线程池满 → 图片、接口一起超时（用户看到的就是
   * "图片全碎 + 按钮没反应 + 所有线路都播不出来"）。
   * 源站不需要额外请求头时直连最快；确实要 Referer/UA 的（play.header 非空）
   * 才退回代理。
   */
  if (isIosShell() && !String(play.value?.header || '').trim()) return url
  return proxyStream(url, play.value?.header)
})

/**
 * 真正交给播放器的地址：**只前进、不回退**。
 *
 * <p><b>为什么不能直接绑 `playableSrc`</b>（2026-11-09，用户报「PC 全屏下
 * 自动下一集总是退出全屏」）：换集/换线时 `play.value` 会短暂变成还没解析
 * 的加密串（或直接为空），`playableSrc` 随之变空。模板上一旦 `src` 变空，
 * `VideoPlayer` 就会被卸载；全屏元素离开 DOM 后 WebView2/Tauri 会**立即退出
 * 窗口全屏**（标题栏/任务栏回来），而重建时没有用户手势，`requestFullscreen`
 * 必被拒，只能恢复「CSS 撑满」的伪全屏 —— 窗口却回不到全屏。
 *
 * <p>所以这里保留上一个可播地址，直到新的可播地址到达；换集前的
 * `player.pause()` + 播放器里的加载浮层负责这段时间的体验。只有出错时才
 * 清空（见下方 watch(error)），让重试从干净状态开始。
 */
const src = ref('')
watch(
  playableSrc,
  (v) => {
    if (v) src.value = v
  },
  { immediate: true },
)

/** 拿到了地址但不是 http，说明需要专用解析器 */
const needsParser = computed(() => {
  // 片库（含转码产物）与离线缓存的地址都是自家 /api 路径，不是"加密串"。
  // 判据要排除它们：转码产物是相对路径 /api/transcode/...，按"非 http 即需要解析器"
  // 会被误判成加密串 —— 实测 TV 上片库点播放直接落到「该线路返回的是加密串」错误页。
  if (isLibraryPlay.value || offlinePlay.value) return false
  const raw = play.value?.url
  const url = typeof raw === 'string' ? raw : ''
  if (url.startsWith('/api/')) return false
  return !!url && !/^https?:/i.test(url)
})

/**
 * 源站给的分段（片头/片尾）与起播点。
 *
 * 单位换算集中在 `api/playExtras`，这里只负责取出来——两处各算一次
 * 是这类字段最容易出错的地方（毫秒/秒混用，错一个数量级）。
 */
const spiderSkip = computed(() => skipWindowFrom(play.value?.skips))
const spiderStartAt = computed(() => startAtFrom(play.value?.position))
const playSubs = computed(() => subTracksFrom(play.value?.subs))
/**
 * 端内原生播放器能直接吃的字幕地址（2026-11-27）。
 *
 * <p>VLC 自带 srt/ass/vtt 解码，不需要网页那套「转 VTT + blob」；但地址必须是
 * **绝对 URL** 且经后端代理（源站常要求 Referer）。取第一份格式可用的即可 ——
 * 网页的字幕也是"有就用第一份"。
 */
function nativeSubUrl(): string {
  for (const s of playSubs.value) {
    if (!s.url) continue
    if (subtitleFormat(s) === 'other') continue
    return absoluteApi(proxyStream(s.url))
  }
  return ''
}

/**
 * 多画质清单（后端 `normalizePlay` 折出来的）。
 *
 * 实测只有极少数源给多档（绝大多数是单地址），所以**不做常驻选择器**，
 * 只在播放失败时给一个"换画质"的出口——这才是"降档提示"，
 * 而不是在主界面上多一个 90% 情况只有一项的下拉。
 */
const qualityList = computed(() => (play.value?.qualities || []).filter((q) => q.url))
/** 当前正在播的那一档（按 url 匹配，因为后端已把选中项写进 url） */
const currentQuality = computed(() => {
  const url = typeof play.value?.url === 'string' ? play.value.url : ''
  return qualityList.value.findIndex((q) => q.url === url)
})

async function switchQuality(url: string) {
  if (!play.value) return
  // 换画质不是换线路：剧集、进度、弹幕都保持不变，只替换地址
  play.value = { ...play.value, url }
  error.value = ''
  resumeApplied = true // 已经在看了，不要再跳回续播点
}

/** 弹幕屏蔽词（设置页配置，随 prefs 变化） */
const dmBlockWords = computed(() => getPrefs().danmakuBlock || '')

/** 弹幕地址：后端多源抓取，前端不用关心源站 */
const danmakuUrl = computed(() => {
  // 优先用 spider 自己给的 danmaku（可能是本地代理形式，PC 上不可用）
  // 这里统一走我们自己的代理，保证 Web/PC 都能用
  if (!title) return ''
  return proxyDanmaku(title, epName.value || '1', { year: titleYear })
})

/**
 * 补剧集表。
 *
 * 从历史页一键续播时 URL 里只有 `vod` 没有 `list`：整条线路的剧集 JSON
 * 动辄 5–15KB，30 条历史就会把 localStorage 和每次同步的 history.json
 * 撑爆。所以这里用一次 detail 请求把它补回来，换来选集面板和自动连播。
 *
 * `linesLoaded` 是"这一页曾经拉过"的标记，不动它；
 * 切集时由 `linesCache` 直接复用，避免每集都打一次 detail。
 */
let linesCache: Array<{ name: string; episodes: Array<{ name: string; url: string }> }> | null = null

async function ensurePlaylist() {
  // 片库的剧集表由 LibraryView 随 query.list 带过来，没有 spider 可补
  if (isLibraryPlay.value) return
  if (!vodId) return
  // 没显式指定线路时，优先用用户上次在这部片上选过的（找不到名字就退回第一条）
  const want = flag.value || getLinePref(site, vodId)
  // 同一部片在本次会话内只拉一次 detail
  if (linesCache) {
    linesCache = sortLinesByHealth(site, linesCache)
    lines.value = linesCache
    if (!playlist.value.length) {
      const line = linesCache.find((l) => l.name === want) || linesCache[0]
      playlist.value = line?.episodes || []
      if (!flag.value && line) flag.value = line.name
    }
    return
  }
  if (linesLoaded) return
  linesLoaded = true
  try {
    const r = await fetchDetail(site, vodId)
    const v = r.list?.[0]
    if (!v) return
    /*
     * 顺手把展示元数据留下来。
     *
     * 这次 detail 已经把封面/正式片名/备注都拿到了，而历史里那条记录
     * （从搜索页或历史页写进去的）往往只有 vod_id 当片名、封面是空。
     * 只取剧集表把响应丢掉，就等于每次播放都白拿了一次元数据。
     */
    if (v.vod_pic) metaPic.value = v.vod_pic
    if (v.vod_name) metaName.value = v.vod_name
    if (v.vod_remarks) metaRemarks.value = v.vod_remarks
    // 按历史成功率排序（#16）：稳定能播的排前，全失败的排后
    lines.value = sortLinesByHealth(site, parsePlayLines(v.vod_play_from || '', v.vod_play_url || ''))
    linesCache = lines.value
    computeNewEps(v.vod_remarks || '')
    if (playlist.value.length || !lines.value.length) return
    // 线路名可能随源站改过，找不到就退回第一条
    const line = lines.value.find((l) => l.name === want) || lines.value[0]
    playlist.value = line?.episodes || []
    if (!flag.value && line) flag.value = line.name
  } catch {
    /* 补不到就降级成"只能播当前集"，不影响播放 */
  }
}

/**
 * 这条线路是不是"只给了一个播放页、需要解析器"。
 *
 * 后端只在 playerContent 抛异常时才补 `parse:1`，正常返回的 `parse` 原样透传，
 * 所以两种信号都要看：显式 `parse===1`，或者 url 不是 http（加密串）。
 * 之前前端完全不读 `parse`，这类源表现为"解码失败"，把能力不足伪装成了坏地址。
 */
function needsParse(r: PlayResult): boolean {
  if (r.parse === 1) return true
  const url = typeof r.url === 'string' ? r.url : ''
  return !!url && !/^https?:/i.test(url)
}

/**
 * 当前这一集有没有已下好的本地缓存，有就返回其清单地址。
 *
 * <p>判据是**后端说 state=done**，不是"目录存在"：下载中可以边下边播，
 * 但用户点进播放页时若只下了一半，播到断点就停，看起来像"播到一半坏了"。
 * 所以宁可先走在线，等后台下完，下次进才是本地。
 *
 * <p>任何异常都返回空串——查缓存失败不该挡住正常播放。
 */
async function pickCachedPlaylist(): Promise<string> {
  if (!site || !vodId || !epName.value) return ''
  const id = cacheIdFor(site, vodId, epName.value)
  try {
    const r = await fetch(`${apiBase()}/api/cache?id=${encodeURIComponent(id)}`)
    if (!r.ok) return ''
    const j = (await r.json()) as { state?: string; playlist?: string }
    if (j.state === 'done' && j.playlist) return cachedPlaylist(id)
  } catch {
    /* 后端不可达：断网时它也可能已经挂了，此时只能靠在线路径报错 */
  }
  return ''
}

async function load() {
  playEpoch++
  /*
   * 换集/换线/重试 = 上一集的转码产物作废：让后端立刻停掉旧 ffmpeg，
   * 而不是等 120 秒空闲看门狗（#15）。必须在取新地址之前。
   */
  dropActiveTranscode()
  mediaInfo.value = null
  refreshSeekAt.value = 0
  dbg('load', location.hash.slice(0, 50))
  loading.value = true
  error.value = ''
  playError.value = ''
  hopError.value = ''
  lastPlayKind.value = ''
  errorSource.value = 'fetch'
  loadingText.value = '正在获取播放地址…'
  // 换线路/换集都是新的尝试，允许原生再兜一次
  nativeTried = false
  libraryStrmUrl.value = ''
  /*
   * `.strm`（#6）：片库里的一行直链文本。先让后端读出目标地址，
   * 之后按**在线播放**走（代理 + 运行时兜底 + 点播式转码），
   * 不能交给片库 ffprobe/转码 —— 那会把 .strm 当二进制媒体。
   */
  if (isLibraryPlay.value && libraryLib.value && isStrmFile(libraryFile.value)) {
    loadingText.value = '正在读取直链…'
    try {
      const r = await resolveLibraryStrm(libraryLib.value, libraryFile.value)
      if (!r.ok || !r.url) {
        error.value = r.msg || '直链文件读取失败（.strm 需要一行 http(s) 地址）'
        loading.value = false
        return
      }
      libraryStrmUrl.value = r.url
      play.value = { url: r.url } as PlayResult
      loading.value = false
      return
    } catch (e) {
      dbg('strm-err', String(e))
      error.value = e instanceof Error ? e.message : String(e)
      loading.value = false
      return
    }
  }  // 片库直链：没有 spider 的 play 可调，地址已经在 query 里。
  // 直接构造一个只有 url 的 PlayResult 交给播放器（它有 hls.js + 原生两条路）。
  if (isLibraryPlay.value) {
    /*
     * 端内原生渲染层（2026-11-27）：这里跑在透明控制层 WebView 里，画面由
     * VLCKit 出 —— **不要再做"这个内核能不能播"的判断**。
     *
     * 真机实报"选集/换线路点了没反应"的根因就在这：.mkv 会先命中下面的
     * 「容器不支持 → 转码」分支，而转码预检在 iOS 上必然失败（没有 ffmpeg），
     * `play.value` 永远不会被赋值 → 监听 `play.url` 的原生换片逻辑收不到地址。
     * 原生模式只要把地址交出去：换片由 watch(play.url) → iosNativeOpen()
     * 完成，同地址重复投递由原生侧吞掉（不会把进度打回 0）。
     */
    if (iosNativeLayer.value) {
      play.value = { url: libraryDirect.value } as PlayResult
      loading.value = false
      loadingText.value = ''
      return
    }
    /*
     * F5（2026-11-17）：不吃 MKV/AVI 等容器的内核（Safari 全平台、桌面
     * Chrome/Edge）直连必然失败，却要先白等播放器 25 秒 watchdog 才进转码兜底。
     * 带 lib+file 坐标的片库播放直接走转码；不带（外部拼的直链）只能试直连。
     */
    if (
      cantPlayContainerDirectly() &&
      /*
       * 判定容器要看**文件相对路径**（query.file），不能看 libraryDirect：
       * 后者是 `/api/library/stream?lib=…&path=…` 形态，扩展名在 query 里，
       * 取 URL 后缀永远拿不到 .mkv（2026-11-17 实测踩过，预判形同虚设）。
       */
      unsupportedContainer(libraryFile.value) &&
      libraryLib.value &&
      libraryFile.value
    ) {
      // 保持 loading 遮罩（还没有 src，遮罩就是唯一反馈）；成功/失败由
      // tryLibraryTranscode 内部收尾
      loadingText.value = '这个格式当前浏览器不支持，正在转码（可边转边看，4K 首次可能几分钟）…'
      void tryLibraryTranscode(libraryLib.value, libraryFile.value)
      return
    }
    dbg('library-direct-branch', 'precheck=' + cantPlayContainerDirectly())
    /*
     * 10-bit（Hi10P）直连必崩（实测 PIPELINE_ERROR_DECODE：video decoder
     * reinitialization failed）。探测结果已缓存时不再让 <video> 白试一遍，
     * 否则用户先看到错误页、再被自动转码。首次访问没有缓存，仍走
     * 直连→解码失败→自动转码兜底一次；之后每次都在这里拦下。
     */
    const known = peekMediaProbe(libraryLib.value!, libraryFile.value!)
    if (known?.ok) {
      /*
       * 2026-11-24 改：原来只要 pixfmt 是 10-bit 就拦下转码 —— 把 HEVC Main10
       * （4K HDR 的标准编码）也当成"只能转码"，PC/电视明明能解却看不到原画。
       * 现在只对 **H.264 Hi10P** 无条件拦；HEVC/AV1 按端侧实测能力判断。
       */
      const hi10p = isHi10p(known)
      const videoBad = !webCanDecodeVideo(known, routeInput(known))
      const ac = (known.acodec || '').toLowerCase()
      const audioBad = !!ac && !audioCodecsNow().includes(ac)
      const remembered = getPrefs().silentAction
      /*
       * 探测结果已知 + 用户已做过选择 → **不要先直连**（2026-11-23 实报：
       * 先播两秒无声再切转码，用户看到"闪一下才重新开始"）。直接按选择分流。
       * 只有「每次询问」(ask) 才保留直连 + 提示条的行为。
       */
      if (hi10p || videoBad || (audioBad && remembered !== 'ask')) {
        mediaProbe.value = known
        // 组件跨集复用：没有新 src 时旧片的 HLS 会继续在后台播，先停掉
        player.value?.stop?.()
        loadingText.value = hi10p
          ? '10-bit 片源，正在准备转码…'
          : videoBad
            ? '当前内核解不了这个编码，正在准备转码…'
            : '正在准备转码…'
        onNoAudio()
        return
      }
    }
    play.value = { url: libraryDirect.value } as PlayResult
    loading.value = false
    // 编码探测不阻塞起播：直连先跑（AAC 系零成本），probe 回来后命中
    // 超纲（AC3/DTS/H265）才按端分配（原生/转码），见 fetchProbeRoute
    void fetchProbeRoute()
    return
  }
  // 离线优先：这一集完整缓存过就直接播本地清单，不再打源站。
  // 断网时 fetchPlay 必然失败，而磁盘上的分片其实齐全——不先查本地的话
  // 用户看到的是"源站挂了"，而片子明明已经下好了。
  const cached = await pickCachedPlaylist()
  if (cached) {
    play.value = { url: cached } as PlayResult
    loading.value = false
    offlinePlay.value = true
    return
  }
  try {
    /*
     * 先看有没有预取好的地址（下集预取，见 utils/playPrefetch）。
     *
     * 命中就直接用，省掉一次 fetchPlay（实测 0.4–1.9 秒）；
     * 没命中照常请求，预取只是加速，不影响正确性。
     */
    const hit = takePlayPrefetch(prefetchKey(site, flag.value, rawId.value))
    notePrefetch(hit ? 'use:hit' : 'use:miss')
    const r = hit
      ? ({ url: hit.url, header: hit.header, parse: hit.parse ?? 0 } as PlayResult)
      : await fetchPlay(site, flag.value, rawId.value)
    play.value = r
    if (needsParse(r)) {
      const target = typeof r.url === 'string' ? r.url : ''
      if (!target) {
        error.value = '该线路未返回播放地址'
        return
      }
      loadingText.value = '正在解析该线路…'
      const p = await fetchParse(target, flag.value)
      if (p.ok && p.url) {
        // 解析出的地址要连同 header 一起替换：jx 接口给的 Referer 通常
        // 就是播放必需的那个，丢了会 403
        play.value = { ...r, parse: 0, url: p.url, header: p.header || r.header }
        parseFrom.value = p.from || ''
        if (parseFrom.value) {
          lineHint.value = `已通过「${parseFrom.value}」解析`
          clearTimeout(lineHintTimer)
          lineHintTimer = window.setTimeout(() => (lineHint.value = ''), 5000)
        }
      } else {
        addFailedLine(flag.value)
        const cand = nextLineCandidate.value
        if (cand) {
          error.value = ''
          autoRetryHint.value = `正在尝试线路「${cand.name}」…`
          showLineHint(cand.name)
          switchLine(cand.name, { auto: true })
          return
        }
        const extra = p.skippedBrowser ? `；另有 ${p.skippedBrowser} 个解析器需要浏览器，当前端不支持` : ''
        error.value = `${p.msg || '该线路需要解析，但没有可用解析器'}${extra}`
        return
      }
    }
    if (!play.value?.url) {
      // 取不到地址也算"这条线路不可用"，要走自动换线。
      // 之前只设 error 就收工，导致首线路为空的片（实测 Wwys、AppGet 的
      // 第一条线路）直接停在错误页，而它后面几条线路其实能播。
      addFailedLine(flag.value)
      const cand = nextLineCandidate.value
      if (cand) {
        error.value = ''
        autoRetryHint.value = `正在尝试线路「${cand.name}」…`
        showLineHint(cand.name)
        switchLine(cand.name, { auto: true })
        return
      }
      error.value = r.msg || '未获取到播放地址（可能该线路需要解析器 parse=1）'
    }
    /*
     * 起播前轻量预检（#16）：死链先 GET Range: bytes=0-1，2 秒判死。
     * 不预检 parse>0 的线路 —— 解析器给的地址不一定是最终流，结果不可信。
     * 预检 unknown（超时/老后端/私网）继续正常播放，不误杀。
     */
    const pu = typeof play.value?.url === 'string' ? play.value.url : ''
    if (pu && !needsParse(r) && /^https?:/i.test(pu)) {
      loadingText.value = '正在检查线路…'
      const pf = await probeLine(pu, play.value.header)
      if (pf === 'dead') {
        addFailedLine(flag.value)
        const cand = nextLineCandidate.value
        if (cand) {
          error.value = ''
          autoRetryHint.value = `线路「${flag.value}」不可用，正在尝试「${cand.name}」…`
          showLineHint(cand.name)
          switchLine(cand.name, { auto: true })
          return
        }
        error.value = '这条播放地址预检失败，且没有其它线路可换'
      }
    }
  } catch (e: any) {
    // 抛异常同样算"这条线路不可用"：后端在 id 不合法、或源站返回畸形数据时
    // 会抛（实测 AppGet 的 parse_api= 型 id 会触发 ArrayIndexOutOfBoundsException），
    // 只显示错误不换线的话，后面能播的线路就白白浪费了。
    const msg = e?.message || String(e)
    addFailedLine(flag.value)
    const cand = nextLineCandidate.value
    if (cand) {
      error.value = ''
      autoRetryHint.value = `正在尝试线路「${cand.name}」…`
      showLineHint(cand.name)
      switchLine(cand.name, { auto: true })
      return
    }
    error.value = msg
  } finally {
    loading.value = false
  }
}

/** 当前集在播放列表里的下标；找不到为 -1 */
const currentIndex = computed(() => {
  if (!playlist.value.length) return -1
  // 源站签名地址会变（从历史回来时基本对不上），按集名再试一次。
  // 片库的"源坐标"是相对文件路径（存在 query.file），rawId 恒为空。
  const cur = isLibraryPlay.value ? libraryFile.value : rawId.value
  const byUrl = playlist.value.findIndex((x) => x.url === cur)
  if (byUrl >= 0) return byUrl
  if (!epName.value) return -1
  return playlist.value.findIndex((x) => x.name === epName.value)
})

/** 切到指定集（上一集 / 下一集 / 选集面板共用） */
function switchEpisode(i: number) {
  lastTickPos = 0
  healAttempts = 0
  healRetryAt = 0
  healPayload = null
  lastWatched = 0
  const target = playlist.value[i]
  if (!target) return
  cancelCountdown()
  // 换集时播放器不再卸载（见 src 的注释），旧视频不会自己停。
  // 先暂停：加载新地址的 0.4–2 秒里不能继续出声/继续走时间
  // （进度写入已由 progressArmed 挡住，但声音挡不住）。
  //
  // 端内原生渲染例外：声音在 VLC 那边，pause() 打不到它；让旧片继续放到
  // 新地址到达、由原生 open 直接换片，比先黑屏等地址更连续。
  if (!iosNativeLayer.value) player.value?.pause()
  /*
   * ★ 换集要清掉"这一集试过的线路"（2026-10-31 修"偶现无法播放"）。
   *
   * 那份记录是 sessionStorage、跨集不清，判据本意只是"这一集内别在两条
   * 坏线路之间来回横跳"。但不清的后果是：第 1 集试遍了几条线路之后，
   * 记录里已经把整部片的线路都标成 failed —— 到第 2 集时
   * `nextLineCandidate` 找不到任何候选，自动换源直接失效，
   * 用户看到的就是"这集突然播不了，换线也没用"，而重开一次又好了
   * （新页面 sessionStorage 是新的）。
   */
  try {
    sessionStorage.removeItem(FAILED_KEY)
  } catch {
    /* 忽略 */
  }
  // 换集前把当前进度写进去（App.vue 用 fullPath 做 key，页面会整个重建）
  flushToHistory(true)
  /*
   * 片库：站点是 __library__，没有 spider 可调，换集不能复用 fetchPlay。
   * 集坐标是相对文件路径，直接重算流地址并把 name/ep/list 一起带过去，
   * 播放页的 load() 走 library 分支（isLibraryPlay = 有 direct）。
   */
  if (isLibraryPlay.value && libraryLib.value) {
    const file = target.url
    void router.replace({
      name: 'play',
      params: { site },
      query: {
        library: libraryLib.value,
        file,
        direct: file ? libraryStream(libraryLib.value, file) : undefined,
        name: title,
        ep: target.name,
        vod: vodId || undefined,
        id: file || undefined,
        pic: metaPic.value || undefined,
        list: playlist.value.length ? JSON.stringify(playlist.value) : undefined,
      },
    })
    return
  }
  router.replace({
    name: 'play',
    params: { site },
    query: {
      flag: flag.value,
      id: target.url,
      name: title,
      ep: target.name,
      vod: vodId || undefined,
      // 有 list 就继续带，避免每次切集都要重新拉 detail
      list: playlist.value.length ? JSON.stringify(playlist.value) : undefined,
    },
  })
}

/** 播完一集之后的行为（设置页三态） */
function onEnded() {
  // 看完了，清掉进度，下次进来从头开始
  if (vodId) clearProgress({ site, vod_id: vodId })

  const i = currentIndex.value
  const hasNextEp = i >= 0 && i + 1 < playlist.value.length

  switch (prefs.autoNextMode) {
    case 'off':
      return
    case 'immediate':
      if (hasNextEp) switchEpisode(i + 1)
      return
    case 'countdown':
    default:
      if (!hasNextEp) return
      startCountdown(i + 1)
      return
  }
}

// ---------------------------------------------------------------- 连播倒计时

/** 倒计时浮层状态；null = 不显示 */
const countdown = ref<{ next: number; left: number } | null>(null)
let countdownTimer: number | undefined

function startCountdown(nextIndex: number) {
  cancelCountdown()
  countdown.value = { next: nextIndex, left: prefs.autoNextDelay || 5 }
  countdownTimer = window.setInterval(() => {
    const c = countdown.value
    if (!c) return
    c.left -= 1
    if (c.left > 0) return
    cancelCountdown()
    switchEpisode(c.next)
  }, 1000)
}

function cancelCountdown() {
  clearInterval(countdownTimer)
  countdownTimer = undefined
  countdown.value = null
}

/** 倒计时浮层上的「立即播放」 */
function playNextNow() {
  const c = countdown.value
  if (!c) return
  const next = c.next
  cancelCountdown()
  switchEpisode(next)
}

const nextEpName = computed(() => {
  const c = countdown.value
  if (!c) return ''
  return playlist.value[c.next]?.name || `第 ${c.next + 1} 集`
})

// ---------------------------------------------------------------- 播放失败 / 自动换源

/**
 * 本次播放已经试过的线路名。
 *
 * 换线路时带上它，避免在两条坏线路之间来回横跳。
 * 存在 sessionStorage：`App.vue` 用 fullPath 做 key，换线路会重建整个页面。
 */
const FAILED_KEY = 'meow.play.failedLines'

function loadFailedLines(): string[] {
  try {
    const raw = sessionStorage.getItem(FAILED_KEY)
    const arr = raw ? JSON.parse(raw) : []
    return Array.isArray(arr) ? arr.filter((x) => typeof x === 'string') : []
  } catch {
    return []
  }
}

function addFailedLine(name: string) {
  const list = loadFailedLines()
  if (!list.includes(name)) list.push(name)
  try {
    sessionStorage.setItem(FAILED_KEY, JSON.stringify(list))
  } catch {
    /* 存不下就算了，只影响防横跳 */
  }
  // 跨会话的线路健康（#16）：下次进同站同线路直接排后 / 用短看门狗
  noteLineFail(site, name)
  if (name === flag.value) refreshLineWatch()
}

/** 还有没有没试过的线路 */
const nextLineCandidate = computed(() => {
  const tried = loadFailedLines()
  return lines.value.find((l) => l.name !== flag.value && !tried.includes(l.name)) || null
})

/** 正在自动换源时的提示文案 */
const autoRetryHint = ref('')

/*
 * 两级错误，错误页分两行显示：
 *   playError —— 播放器/源站给的原始原因（用户最该看到的）
 *   hopError  —— 我们这一跳失败的原因（转码没起来、代理 502…）
 *
 * 原来只有一个 error 字段，转码失败会把它覆盖成"找不到 ffmpeg"，
 * 用户看到一个跟影片无关的报错，永远不知道真实原因是网络抖动。
 */
const playError = ref('')
const hopError = ref('')
/**
 * 最近一次播放失败的类别（诊断用）。
 *
 * ★ 2026-11-27：`runDiagnosis` 原来把 `watchdogFired` 写成
 *   `!!error && errorSource==='play'` —— 任何播放失败都被当成"起播看门狗到期"，
 *   于是「后端没有 ffmpeg」也被判成 NETWORK_TIMEOUT（high），建议用户"换线路"。
 *   只有播放器真的报 timeout / stall 才算看门狗到期。
 */
const lastPlayKind = ref<PlayErrorKind | ''>('')
/** 后端有没有 ffmpeg；null = 还没探测过 */
const transcodeOk = ref<boolean | null>(null)
/** 高丢帧自愈要求的最小输出高度（0 = 不检查）；tryTranscode 读一次就清零 */
let healMinHeight = 0
/** 自愈前的播放地址：转封装失败/被降分辨率时原样恢复，不把用户丢到错误页 */
let healPrev: { url: string; header?: string } | null = null

async function ensureTranscodeProbe(): Promise<boolean> {
  /*
   * true 是稳定事实（装了就是装了）→ 短路，不再问。
   * false / null 都可能变（ffmpeg 插件刚装好、后端刚起来）→ 每次都重新问，
   * 由 api/library.ts 的 30 秒 TTL 挡掉重复请求。
   */
  if (transcodeOk.value === true) return true
  let v: boolean | null = null
  try {
    v = await transcodeAvailable()
  } catch {
    v = null
  }
  /*
   * null = 没问到（网络失败 / 老后端）。**按"可用"处理**，保持旧行为：
   * 真不能转的时候后端会回 502 + 人话 msg；把 PC/Docker 误判成"不支持"
   * 反而是新引入的伤害（2026-11-27 回归审查）。
   */
  if (v === null) return true
  transcodeOk.value = v
  return v
}

/**
 * 本端没有转码能力时的统一判定（错误页与诊断共用）。
 *
 * <p>后端 502 的中文 msg（「找不到 ffmpeg…」）和技术性原因（iOS 不能起子
 * 进程）对用户毫无意义，错误页要换成人话；原始 msg 留在 hopError 里给诊断用。
 */
function isTranscodeUnavailableText(s: string | null | undefined): boolean {
  return !!s && /找不到\s*ffmpeg|ffmpeg/i.test(s)
}

/** 本端没有转码能力的主文案（按端说人话；建议句由模板单独给） */
function transcodeUnavailableMain(): string {
  return isIosShell()
    ? '这个格式本机播不了：iOS 端没有转码能力'
    : '这个格式本机播不了：当前后端没有 ffmpeg'
}

/**
 * 能力预检失败时的统一收尾（2026-11-27）。
 *
 * <p>片库的扩展名预判、直连失败兜底、诊断建议三条路原来都直接去起转码，
 * iOS / 桌面 Safari / 没装插件的 PC 上必然 502，错误页显示「找不到 ffmpeg」——
 * 跟用户选的片子毫无关系（用户实报）。收在这里比逐点补守卫更彻底。
 *
 * @param silent true = 自愈/后台动作：恢复原样播放，不弹错误页
 */
function handleTranscodeUnavailable(silent: boolean): void {
  transcodeWaiting.value = false
  transcodeTried = false
  const pv = healPrev
  healPrev = null
  if (pv) {
    // 自愈切走前记了原地址：原样播回去，别把用户丢在错误页
    play.value = { url: pv.url, header: pv.header } as PlayResult
    return
  }
  if (silent) return
  libraryNoDirect.value = false
  loading.value = false
  loadingText.value = ''
  playError.value = ''
  // 原始技术原因留在 hopError：诊断靠它命中 TRANSCODE_UNAVAILABLE，UI 层再降级
  hopError.value = 'ffmpeg 不可用（本端没有转码能力）'
  error.value = transcodeUnavailableMain()
}

/**
 * 错误页首行文案。
 *
 * <p>后端 502 的 msg 与"iOS 不能起子进程"这类原因对用户没有信息量，还会
 * 让人以为是片子的问题。命中转码缺失就换成能力说明（2026-11-27 用户实报：
 * 错误页大字显示「找不到 ffmpeg（policy.json 或 PATH 均未配置）」）。
 */
const errorMainText = computed(() =>
  isTranscodeUnavailableText(error.value) || isTranscodeUnavailableText(hopError.value)
    ? transcodeUnavailableMain()
    : error.value,
)

/** 错误页第二行：命中转码缺失时给可执行的下一步，否则照旧显示技术原因 */
const errorSubText = computed(() => {
  if (isTranscodeUnavailableText(error.value) || isTranscodeUnavailableText(hopError.value)) {
    return '换 MP4/HLS 源，或用装了 ffmpeg 的端播放（PC 装插件 / Docker / TV 本机执行）'
  }
  return hopError.value
})

/** 播放器报错 */
/** 当前环境能用原生播放器（仅 TV 壳） */
const canUseNative = computed(() => {
  const raw = play.value?.url
  const u = typeof raw === 'string' ? raw : ''
  // TV 有 ExoPlayer 桥，iOS 有端内 VLCKit：两者都算"能用原生播"
  return (hasNativePlayer() || hasIosNativePlayer()) && !!u && /^https?:/i.test(u)
})

/** 当前应从哪里开始播：有续播记录就用它，否则从头 */
function resumePos(): number {
  if (!vodId) return 0
  const it = getProgress({ site, vod_id: vodId }, rawId.value, epName.value)
  return it && Number.isFinite(it.pos) ? it.pos : 0
}

/**
 * 播放器切换的交接位置（网页 ↔ 原生，2026-11-23 实报「进度没带过去」）。
 *
 * 只用 resumePos() 是错的：那是**历史里存的**续播点，边看边切时它停在
 * 上一次落盘的位置（常常是这一集开头）。优先取当前播放器的实时位置，
 * 拿不到（刚起播/换源中）才回退历史。
 */
function handoffPos(): number {
  const t = player.value?.getTime?.()
  if (typeof t === 'number' && Number.isFinite(t) && t > 1) return t
  return resumePos()
}

/** 原生播放的 meta（进度回传坐标 + 弹幕设置），openNative 与 embed 共用。 */
function buildNativeMeta(): string {
  try {
    return JSON.stringify({
      site,
      vod: vodId,
      ep: epName.value,
      flag: flag.value,
      epUrl: rawId.value,
      apiBase: apiBase(),
      // 外挂字幕（原生 VLC 直接加载；空串 = 本集没有）
      sub: nativeSubUrl(),
      dm: {
        opacity: prefs.danmakuOpacity,
        scale: prefs.danmakuScale,
        duration: prefs.danmakuDuration,
        area: prefs.danmakuArea,
        block: prefs.danmakuBlock,
        offset: getDmOffset(title),
      },
    })
  } catch {
    return ''
  }
}

/**
 * 「用原生播放」的统一入口（O3/O12）：TV 壳支持同页内嵌（embed 桥）时走
 * 同页接管 —— 视频由原生层渲染（设备解 AC3/DTS/H265），网页层被盖住、
 * 返回键收起回网页；老壳走 openNative（独立 Activity）。
 * 前置：调用方已停掉无声直连（libraryNoDirect/pause）。
 */
function switchToNativePlayer() {
  const raw = play.value?.url
  const u = typeof raw === 'string' ? raw : ''
  if (!u) return
  const meta = buildNativeMeta()
  if (hasEmbedPlayer()) {
    const ok = embedPlay(u, String(play.value?.header || ''), Math.round(handoffPos() * 1000), meta)
    if (ok) {
      showSilentHint('已切到原生解码播放（返回键回到网页）', false)
      return
    }
  }
  tryNative()
}

/** 错误页的「用原生播放器打开」：TV 交 ExoPlayer，iOS 交端内 VLCKit */
function tryNative() {
  /*
   * iOS 没有 ExoPlayer 桥：同一入口按能力分派到端内 VLCKit
   * （2026-11-27）。不先分派的话，iOS 上这个按钮会被 canUseNative 隐藏，
   * 用户就只剩"换线路/重试"两条路 —— 而 VLCKit 往往能直接播。
   */
  if (hasIosNativePlayer() && tryIosNative()) return
  const raw = play.value?.url
  const u = typeof raw === 'string' ? raw : ''
  if (!u) return
  /*
   * 第 6 参 meta：原生 Activity 靠它
   *  ① 每 12 秒回传 /api/state/progress（否则退出后网页历史停在进页位置）；
   *  ② 套用网页弹幕设置（透明度/字号/速度/区域/屏蔽词/校准）。
   * 老壳没有 openNativeFull 时逐级退回，不影响拉起。
   */
  const meta = buildNativeMeta()
  if (openNative(u, title, play.value?.header, handoffPos(), danmakuUrl.value, meta)) {
    error.value = ''
    autoRetryHint.value = '已改用原生播放器（ExoPlayer）'
  } else {
    autoRetryHint.value = '原生播放器拉起失败'
  }
}

/**
 * iOS 原生兜底（端内 VLCKit，2026-11-27）。
 *
 * <p>触发点：web 的 `<video>` 播不了（MKV/AVI/FLV/DTS/10-bit），而 iOS 既没有
 * 服务端 ffmpeg 也不能 fork —— 端内 VLCKit 是唯一能真播的路径。与 TV 的
 * `tryNative()` 同构：弹幕地址、进度交接、弹幕设置都走同一份 `buildNativeMeta()`。
 *
 * <p>返回 true = **消息已投递**（WKScriptMessageHandler 是单向通道，拿不到
 * 播放结果）。播失败由原生回传 `window.__meowVlcClosed` 让页面恢复进度并提示。
 */
/**
 * 本页是不是「原生渲染 + web 控制层」那一层。
 *
 * <p>NativeVlcPlayer 会开一个透明 WKWebView 加载同一路由并注入
 * `__meowNativeLayer = true`：画面由端内 VLCKit 渲染，控制层就是本页 DOM
 * （组件 NativeIosControlLayer）。这样图一那套控件/弹幕/选集/线路全部复用，
 * 不在 Swift 里复刻第二套 UI。
 */
const iosNativeLayer = ref(isIosNativeLayer())
/** 刚从端内播放器退出（父页面据此显示"重新打开"面板，而不是尝试 web 直连 MKV） */
const nativeExited = ref(false)


/*
 * 端内原生渲染模式：web 侧切集/换线后把新地址交给原生换片。
 *
 * 这样 VideoPlayer 里的「选集/换线路」面板完全不需要改 —— 它们照旧 emit
 * switch/line，父组件照旧更新 play.value，这里监听到地址变化就转发给原生。
 */
watch(
  () => (typeof play.value?.url === 'string' ? play.value.url : ''),
  (u) => {
    if (!iosNativeLayer.value || !u) return
    if (u.includes('/api/transcode')) return
    // 带上这一集的 header（换线路时防盗链头可能和上一条不同）与字幕
    iosNativeOpen(absoluteApi(u), play.value?.header, nativeSubUrl())
  },
)

/** 从"已退出"面板重新拉起端内播放器 */
function reopenNative() {
  nativeExited.value = false
  nativeJustClosed = false
  if (!tryIosNative()) showSilentHint('端内播放器没拉起来（地址或桥异常）')
}

/** "已退出"面板的「下一集」：关掉面板，按普通切集路径走（会再拉起原生） */
function nativePlayNext() {
  const i = currentIndex.value
  if (i < 0 || i >= playlist.value.length - 1) return
  nativeExited.value = false
  nativeJustClosed = false
  switchEpisode(i + 1)
}

function tryIosNative(): boolean {
  // 已经是原生控制层：再调 openNativeVlc 会无限套娃（一层套一层）
  if (iosNativeLayer.value) return false
  // 刚被用户从原生播放器返回：本帧不再自动拉起（否则看到的是黑屏死循环）
  if (nativeJustClosed) {
    nativeJustClosed = false
    return false
  }
  if (!hasIosNativePlayer()) return false
  /*
   * 片库播放时 play.value 还没建（预判就发生在起播前），地址只在
   * query.direct 里；它是相对路径（/api/library/stream?...），必须绝对化 ——
   * VLC 跑在原生层，拿不到页面的 location.origin。
   */
  const raw = play.value?.url || libraryDirect.value
  const u = typeof raw === 'string' ? raw : ''
  if (!u) return false
  const abs = absoluteApi(u)
  if (!/^https?:/i.test(abs)) return false
  const ok = openIosNative(
    abs,
    title,
    play.value?.header,
    handoffPos(),
    danmakuUrl.value,
    buildNativeMeta(),
    // 控制层是另一个 WKWebView，得把后端地址带过去（那边没有宿主注入的桥）
    apiBase(),
  )
  if (ok) {
    // 原生接管后 web 播放器必须停：否则两路同时出声音
    player.value?.stop?.()
    loading.value = false
    loadingText.value = ''
    error.value = ''
    hopError.value = ''
    showSilentHint('已用端内播放器打开（原生解码，MKV/DTS 也能播）')
    dbg('ios-native', abs.slice(0, 120))
  }
  return ok
}

/*
 * 选集 / 换线路在原生控制层里**不另写一套**（2026-11-27 修正）。
 *
 * <p>上一版在这里放了 nativeSelectEpisode/nativeSelectLine 两个"本层取地址再
 * 直接发 open"的函数，但模板从没调用过（白写）；而真正在用的路径是
 * switchEpisode/switchLine → router.replace → 路由 watcher → load()。
 *
 * <p>现在统一走那条既有路径：load() 在 iosNativeLayer 下有"只解析地址"的
 * 快速分支，地址落地后由 `watch(play.url) → iosNativeOpen()` 换片。
 * 好处是路由/进度/集名高亮只有一个真源，不会出现"面板里换了、历史还记着旧集"。
 */

/**
 * 让后端把这个源现转成 HLS（H.264）。
 *
 * 只对"浏览器解不了"这种失败有意义：换线路解决不了编码问题，
 * 因为同一集在不同源上编码基本一样。转码产物落在后端缓存目录，
 * **源文件不动**。
 *
 * 已转好的分片是增量的：前几个分片就绪即可起播，不用等整片转完。
 */async function tryTranscode(src: string, copy = false, repair = false, startAt = 0,
                            allowHevc = true) {
  /*
   * ★ 能力预检收在函数入口（2026-11-27）：调用点有 10+ 处（自愈、换线、
   *   诊断建议、排队重试…），逐点补守卫总会漏，而且以后新加的还会漏。
   *   放在这里既覆盖全部，也不改变"有 ffmpeg 的端"任何行为。
   */
  if (!(await ensureTranscodeProbe())) {
    /*
     * iOS 端内 VLCKit 能真播这些格式（MKV/AVI/FLV/DTS）：有它就先交给它，
     * 别直接给"本端没有转码能力"。自愈（repair）不切原生 —— 用户正在网页里
     * 正常看，突然跳原生页体验更差，宁可保持原样播。
     */
    if (!repair && tryIosNative()) return
    handleTranscodeUnavailable(repair)
    return
  }
  const minH = healMinHeight
  healMinHeight = 0
  const myEpoch = playEpoch
  pendingTranscode = { src, copy, repair, start: startAt }
  transcodeWaiting.value = true
  errorSource.value = 'play'
  hopError.value = ''
  loadingText.value = busyRetrying.value
    ? `排队中：另一台设备正在转 4K（第 ${busyRetries.value} 次重试）…`
    : repair
      ? '正在用显卡按原分辨率重编码（修复丢帧，分辨率不变）…'
      : '正在准备播放（这个格式需要转码，已转部分可边看边转）…'
  error.value = ''
  try {
    // 首次响应只代表"任务已排上"，要等分片落盘到能起播
    // 头必须带上：很多线路的 m3u8 校验 UA/Referer，转码由后端拉流（2026-11-23 咕噜4K 实测）
    const hdr = play.value?.header
    const headerStr = typeof hdr === 'string' ? hdr : hdr ? JSON.stringify(hdr) : ''
    const r = await waitTranscode(
      src,
      (sec, ph, stalled) => {
        if (myEpoch !== playEpoch) return
        loadingText.value = transcodePhaseText(sec, ph, stalled)
      },
      undefined,
      headerStr,
      copy,
      repair,
      startAt,
      allowHevc,
    )
    ;(window as unknown as { __meowLastTranscode?: unknown }).__meowLastTranscode = {
      ok: r.ok, state: r.state, code: r.code, error: r.error,
    }
    if (myEpoch !== playEpoch) {
      // 已经切集/换线：后端任务没人要了，停掉（仍在跑才停，转完的留着）
      if (r.state === 'running') void cancelTranscode({ src, copy, repair, start: startAt })
      return
    }
    pendingTranscode = null
    transcodeWaiting.value = false
    busyRetrying.value = false
    busyRetries.value = 0
    clearTimeout(busyTimer)
    busyHint.value = false
    /*
     * 转码失败**不能覆盖播放器给的原因**。
     *
     * 原来这里直接 `error.value = r.error`，于是 TV 上（网关没有 ffmpeg）
     * 一次网络抖动换来的是"找不到 ffmpeg。装一个才能播这个格式"——用户
     * 看到的是一个跟影片无关的报错，真实原因被盖掉了。
     * 现在：主文案保留播放器的原因，转码失败只作为第二行（按 code 说人话）。
     */
    if (!r.ok || r.state === 'failed' || !r.playlist) {
      /*
       * 自愈失败（探测不到头/源站限流/拿不到同一档变体）：**恢复原样播放**，
       * 不把用户丢到错误页 —— 卡顿总比"播放失败"好，而且用户还能自己换线路。
       */
      if (healPrev) {
        const pv = healPrev
        healPrev = null
        transcodeTried = false
        hopError.value = ''
        error.value = ''
        playError.value = ''
        loadingText.value = ''
        transcodeWaiting.value = false
        play.value = { url: pv.url, header: pv.header } as PlayResult
        showSilentHint(
          repair && r.code === 'no_repair_encoder'
            ? '这个源的时间轴特殊，当前后端没有硬件编码器，无法在不降画质的前提下修复'
            : '原分辨率修复不可用，继续原样播放',
          false,
          false,
        )
        // 探测超时/上游限流是暂时的：15 秒后自动再试（最多 3 次）
        if (repair && canRetryRepair(r.code) && healAttempts < 3) scheduleHealRetry()
        return
      }
      if (r.code === 'hw_busy') {
        // 不是源/编码问题：另一台设备在转 4K。给"排队/换 1080p"两个选择。
        hopError.value = ''
        error.value = ''
        loadingText.value = ''
        transcodeWaiting.value = false
        busyKind = 'vod'
        busySrc = src
        busyCopy = copy
        busyRepair = repair
        busyStart = startAt
        busyHevc = allowHevc
        // 清掉播放器的"解码失败"错误页，否则它会盖住下面的排队/换线选择
        player.value?.stop?.()
        ;(window as unknown as { __meowBusy?: unknown }).__meowBusy = {
          at: Date.now(), retrying: busyRetrying.value, code: r.code,
        }
        if (busyRetrying.value) scheduleBusyRetry()
        else busyHint.value = true
        return
      }
      // 排队中遇到别的失败（源挂/没 ffmpeg）：收起排队浮层，按原错误流程走
      busyHint.value = false
      busyRetrying.value = false
      clearTimeout(busyTimer)
      hopError.value = !r.playlist
        ? transcodeErrorMessage(r)
        : (r.error || '转码失败')
      error.value = playError.value || '播放失败'
      loadingText.value = ''
      return
    }
    libraryReadyCap.value = r.readySeconds || 0
    libraryAhead.value = r.readySeconds || 0
    libraryStart.value = r.startOffset || 0
    libraryTotalDuration.value = r.duration || 0
    if (r.duration && r.readySeconds && r.readySeconds < r.duration - 5) {
      pollTranscodeProgress({
        src,
        ...(repair ? { repair: '1' } : copy ? { copy: '1' } : {}),
      })
    }
    /*
     * ★ 原画转封装的硬门槛（2026-11-24）：请求了 copy 就必须真的是 copy，
     *   且输出高度不能低于当前画面。探测拿不到头/拿错变体时后端会退回
     *   软编 1080p —— 那种情况宁可不起播，也不能静默降画质。
     */
    if (copy) {
      // 源高度优先用触发时抓到的（此时播放器可能已停，videoHeight 会变 0）
      const curH = minH || document.querySelector('video')?.videoHeight || 0
      const outH = Number(r.outHeight || 0)
      if (r.copyVideo !== true || (curH > 0 && outH > 0 && outH < curH)) {
        void cancelTranscode({ src, copy })
        showSilentHint('该源无法原画转封装，已保持原样播放（避免降画质）', false, false)
        transcodeTried = false
        return
      }
    } else if (repair) {
      /*
       * 修复转码的硬门槛：**分辨率不许降**（2026-11-24 用户口径：宁可卡也不降画质）。
       * 后端 repair 分支不做任何缩放，这里再兜一层，防止将来改坏后静默降级。
       */
      const curH = minH || document.querySelector('video')?.videoHeight || 0
      const outH = Number(r.outHeight || 0)
      if (curH > 0 && outH > 0 && outH < curH) {
        void cancelTranscode({ src, repair })
        showSilentHint('修复输出被降了分辨率，已停止并保持原样播放', false, false)
        transcodeTried = false
        return
      }
    }
    // 已经在边转边看：记住坐标，离页/切集时主动撤（#15）
    activeTranscode = { src, copy, repair, start: startAt, header: headerStr }
    healPrev = null
    mediaInfo.value = transcodeMediaInfo(r)
    // 提示条压在单行里：HEVC 10-bit 4K → H.264 1080p（拿不到编码就退回原文案）
    const badge = transcodeBadge(mediaInfo.value)
    transcodeHint.value = r.partial
      ? `本次只转出 ${r.readySeconds || 0} 秒（另一台设备接手了转码），看到断点会停住`
      : r.copyVideo
        // remux：视频原样 copy（原画），只把音轨转成 AAC
        ? '已转封装播放（原画视频，仅音轨转 AAC）'
        : repair
          // 修复：分辨率不变、显卡重编（源头码流结构问题，copy 修不了）
          ? `已按原分辨率修复播放（${badge || '显卡重编码'}，分辨率不变）`
          : badge
            ? `已转码播放（${badge}）`
            : '已转码播放（源文件未改动）'
    clearTimeout(lineHintTimer)
    lineHintTimer = window.setTimeout(() => (transcodeHint.value = ''), 8000)
    // 播放器直接吃后端给的清单地址；hls.js 会按需拉分片
    play.value = { url: absoluteApi(r.playlist) } as PlayResult
    applySeekAfterRestart()
  } catch (e) {
    if (myEpoch !== playEpoch) return
    transcodeWaiting.value = false
    hopError.value = e instanceof Error ? e.message : String(e)
    error.value = playError.value || '播放失败'
    loadingText.value = ''
  }
}

/**
 * 用户拖到「还没转出来」的位置（issue #17）。
 *
 * <p>旧行为：夹到"已转出 - 2 秒"再跳 —— 用户看到的是拖了没反应/回弹。
 * 现在从目标点重转一条（后端 `start=N` + 重复黑场分片保持绝对时间轴），
 * 转出首片后自动跳回用户要的位置。
 *
 * <p>为什么必须停掉旧任务：两个 ffmpeg 写两份缓存事小，抢同一个硬件编码器
 * 会互相把对方挤成 Generic error（见 stopOtherJobs 的实测注释）。
 */
async function onSeekBeyond(target: number) {
  const t = activeTranscode
  if (!t) return
  const start = transcodeRestartStart(target)
  // 已经在从更早的位置转：目标多半没超出那份任务的覆盖范围，别来回重启
  if ((t.start || 0) >= 2 && start >= (t.start || 0) && start - (t.start || 0) < 8) return
  pendingSeekAfterRestart = Math.max(0, Math.floor(target))
  showSilentHint(`正在从 ${fmtSeekClock(start)} 开始转码…`, false)
  const old = { ...t }
  // 先保留旧坐标：新任务失败时用户还能再拖一次重试；成功会由 try* 覆盖。
  // （旧任务已经取消，重复取消是幂等的）
  void cancelTranscode(old)
  // play.value 此刻是上一次的转码清单，header 已经不在里面：从任务坐标补回去，
  // 否则需要 UA/Referer 的线路会在重转时 404（2026-11-23 咕噜4K 同类问题）
  if (t.header) play.value = { ...(play.value as PlayResult), header: t.header }
  /*
   * copy（原画转封装）不能用于起点重转：`-ss` + `-c:v copy` 实测不裁切，
   * 清单会从 0 重放。这里改走普通转码（重编码、分辨率按后端规则），
   * 提示文案已说明"正在从 xx 开始转码"。repair 本来就是重编码，保持。
   */
  if (t.lib && t.path) {
    await tryLibraryTranscode(t.lib, t.path, false, !!t.repair, start)
  } else if (t.src) {
    await tryTranscode(t.src, false, !!t.repair, start)
  }
}

/**
 * 用户主动放弃等待：作废本次等待、停掉后端 ffmpeg、回到错误页（可重试/换线）。
 * 与切集不同，这里明确把停止原因写给用户看，不能静默回首页。
 */
function cancelTranscodeWait() {
  const t = pendingTranscode
  playEpoch++
  pendingTranscode = null
  transcodeWaiting.value = false
  if (t) void cancelTranscode(t)
  loading.value = false
  loadingText.value = ''
  errorSource.value = 'play'
  hopError.value = '已停止等待，后端转码任务已取消'
  error.value = playError.value || '已取消转码等待'
}

/** 后端返回的是 `/api/...` 相对路径；PC 端 base 是绝对地址，要拼上。 */
function absoluteApi(p: string): string {
  if (/^https?:/i.test(p)) return p
  const b = apiBase()
  return b ? b.replace(/\/$/, '') + p : p
}

/**
 * 这个内核能不能播 MKV/AVI 这类容器（不能就直接转码，别先白等 25 秒 watchdog）。
 *
 * <p>2026-11-17 实测的边界：
 * <ul>
 *   <li>**桌面 Chrome / Edge / Chromium、全平台 Safari**：不支持 Matroska 容器
 *       → 直连必失败（Chrome 上还会先黑等 25 秒）→ 直接转码；</li>
 *   <li>**Android WebView / Android Chrome**：带 Matroska demuxer，能播大多数
 *       MKV/H265（TV 就靠它）→ 保持先直连；</li>
 *   <li>**Firefox**：自带 ffmpeg 后端，能解 mkv → 保持先直连。</li>
 * </ul>
 * 判错的最坏结果是多转一次码（慢一点但能播），不会变成播不了。
 */
function cantPlayContainerDirectly(): boolean {
  if (isFirefox()) return false
  if (isAndroid()) return false
  return true
}

/**
 * 当前内核肯定播不了的容器（F5 预判，2026-11-17）。
 *
 * <p>只列"确定不行"的：这些内核从不支持 Matroska/AVI/WebM/FLV 等容器，
 * 裸 .ts 文件也播不了（HLS 分片是另一条链路，不经过这里）。
 * 误判的最坏结果是多转一次码（慢一点但能播），不会变成播不了。
 */
function unsupportedContainer(url: string): boolean {
  const p = (url.split('?')[0].split('#')[0] || '').toLowerCase()
  return /\.(?:mkv|avi|webm|flv|rmvb|rm|wmv|m2ts|ts|vob)$/.test(p)
}

/**
 * 片库文件直连失败后的转码兜底。
 *
 * <p><b>为什么必须用 lib + path 而不是流地址</b>：片库流是后端自己按
 * 配置读出来的（SMB / 本地目录）。把 `http://127.0.0.1:.../api/library/stream?...`
 * 交给 ffmpeg 有两个问题：① 回环地址会被 SSRF 防线拒；② ffmpeg 根本不认
 * SMB 协议。所以这里传原始 lib + path，让后端自己取文件。
 *
 * <p><b>为什么兜底放在播放页</b>（2026-11-02 改）：原来在片库页按扩展名
 * 预判"MKV/H265 必须转码"，而 TV 本地网关没有 ffmpeg → 直接失败。
 * 实测 WebView 110 能播大多数 MKV/H265，先直连才是对的；
 * 只有真的报错了才需要转码。
 */
async function tryLibraryTranscode(lib: string, file: string, copy = false, repair = false,
                                  startAt = 0, allowHevc = true) {
  dbg('startTranscode', file)
  /*
   * ★ 能力预检（2026-11-27 用户实报）：片库这条原来只有 4 个调用点自己做了
   *   预检，**扩展名预判**（起播前）与**直连失败兜底**两条漏了 —— iOS /
   *   桌面 Safari / 没装插件的 PC 上必然撞 502，错误页显示「找不到 ffmpeg」，
   *   跟用户选的片子毫无关系。收在函数入口，一处覆盖全部。
   */
  if (!(await ensureTranscodeProbe())) {
    /*
     * iOS 端内 VLCKit 能真播这些格式（MKV/AVI/FLV/DTS）：有它就先交给它，
     * 别直接给"本端没有转码能力"。自愈（repair）不切原生 —— 用户正在网页里
     * 正常看，突然跳原生页体验更差，宁可保持原样播。
     */
    if (!repair && tryIosNative()) return
    handleTranscodeUnavailable(repair)
    return
  }
  // 转码一旦开始，之前那条错误页要收掉（否则错误页盖着、用户在等转码却看着报错）
  playError.value = ''
  const myEpoch = playEpoch
  pendingTranscode = { lib, path: file, copy, repair, start: startAt }
  transcodeWaiting.value = true
  errorSource.value = 'play'
  // 从这一刻起不再把 query.direct 交给播放器（否则 <video> 会一直重试直连，
  // 转码好了也播不到；见 playableSrc 里的注释）
  libraryNoDirect.value = true
  loadingText.value = busyRetrying.value
    ? `排队中：另一台设备正在转 4K（第 ${busyRetries.value} 次重试）…`
    : repair
      ? '正在用显卡按原分辨率重编码（修复丢帧，分辨率不变）…'
      : '直连播不了，正在启动转码（可边转边看）…'
  error.value = ''
  try {
    const r = await waitLibraryTranscode(lib, file, (sec, ph, stalled) => {
      if (myEpoch !== playEpoch) return
      loadingText.value = transcodePhaseText(sec, ph, stalled)
    }, undefined, copy, repair, startAt, allowHevc)
    if (myEpoch !== playEpoch) {
      if (r.state === 'running') void cancelTranscode({ lib, path: file, copy, repair, start: startAt })
      return
    }
    pendingTranscode = null
    transcodeWaiting.value = false
    if (r.state === 'failed' || !r.playlist) {
      // 高丢帧自愈失败：恢复原样播放（不把用户丢到错误页，卡顿也比重来好）
      if (healPrev) {
        const pv = healPrev
        healPrev = null
        transcodeTried = false
        libraryNoDirect.value = false
        error.value = ''
        playError.value = ''
        loadingText.value = ''
        transcodeWaiting.value = false
        play.value = { url: pv.url, header: pv.header } as PlayResult
        showSilentHint(
          repair && r.code === 'no_repair_encoder'
            ? '这个文件的时间轴特殊，当前后端没有硬件编码器，无法在不降画质的前提下修复'
            : '原分辨率修复不可用，继续原样播放',
          false,
          false,
        )
        if (repair && canRetryRepair(r.code) && healAttempts < 3) scheduleHealRetry()
        return
      }
      if (r.code === 'hw_busy') {
        error.value = ''
        loadingText.value = ''
        busyKind = 'lib'
        busyLib = lib
        busyFile = file
        busyCopy = copy
        busyRepair = repair
        busyStart = startAt
        busyHevc = allowHevc
        if (busyRetrying.value) scheduleBusyRetry()
        else busyHint.value = true
        return
      }
      busyHint.value = false
      busyRetrying.value = false
      clearTimeout(busyTimer)
      // 按后端 code 说人话（slow_4k / no_ffmpeg / source_timeout…）
      error.value = transcodeErrorMessage(r)
      // 收掉整页加载遮罩：否则 error 分支会被「loading && !src」遮住，
      // 用户只看到一直转圈（F5 预判路径进这里时 loading 还是 true）
      loading.value = false
      return
    }
    libraryReadyCap.value = r.readySeconds || 0
    libraryAhead.value = r.readySeconds || 0
    libraryStart.value = r.startOffset || 0
    libraryTotalDuration.value = r.duration || 0
    if (r.duration && r.readySeconds && r.readySeconds < r.duration - 5) {
      pollTranscodeProgress({
        lib,
        path: file,
        ...(repair ? { repair: '1' } : copy ? { copy: '1' } : {}),
      })
    }
    /*
     * 片库 remux 同样有"必须真 copy、且不能降分辨率"的硬门槛：
     * 探测失败时后端会退回软编 1080p，那种情况宁可保持原样也不静默降画质。
     */
    if (copy) {
      const curH = document.querySelector('video')?.videoHeight || 0
      const outH = Number(r.outHeight || 0)
      if (r.copyVideo !== true || (curH > 0 && outH > 0 && outH < curH)) {
        void cancelTranscode({ lib, path: file, copy })
        showSilentHint('该文件无法原画转封装，已保持原样播放（避免降画质）', false, false)
        transcodeTried = false
        return
      }
    } else if (repair) {
      // 修复转码同样不许降分辨率（片库 4K 源）
      const curH = healMinHeight || document.querySelector('video')?.videoHeight || 0
      const outH = Number(r.outHeight || 0)
      if (curH > 0 && outH > 0 && outH < curH) {
        void cancelTranscode({ lib, path: file, repair })
        showSilentHint('修复输出被降了分辨率，已停止并保持原样播放', false, false)
        transcodeTried = false
        return
      }
    }
    // 已经在边转边看：记住坐标，离页/切集时主动撤（#15）
    activeTranscode = { lib, path: file, copy, repair, start: startAt }
    healPrev = null
    mediaInfo.value = transcodeMediaInfo(r)
    // 提示条压在单行里：HEVC 10-bit 4K → H.264 1080p（拿不到编码就退回原文案）
    const badge = transcodeBadge(mediaInfo.value)
    transcodeHint.value = r.partial
      ? `本次只转出 ${r.readySeconds || 0} 秒（另一台设备接手了转码），看到断点会停住`
      : r.copyVideo
        // remux：视频原样 copy（原画），只把音轨转成 AAC
        ? '已转封装播放（原画视频，仅音轨转 AAC）'
        : repair
          ? `已按原分辨率修复播放（${badge || '显卡重编码'}，分辨率不变）`
          : badge
            ? `已转码播放（${badge}）`
            : '已转码播放（源文件未改动）'
    clearTimeout(lineHintTimer)
    lineHintTimer = window.setTimeout(() => (transcodeHint.value = ''), 8000)
    play.value = { url: absoluteApi(r.playlist) } as PlayResult
    applySeekAfterRestart()
    loading.value = false
  } catch (e) {
    if (myEpoch !== playEpoch) return
    transcodeWaiting.value = false
    error.value = e instanceof Error ? e.message : String(e)
    loading.value = false
  }
}

/**
 * 直连「有画面、零音频」——音轨编码是内核没有解码器的（AC3/E-AC3/DTS）。
 *
 * <p>错误兜底接不到这种情况：起播全程无 MediaError，播放器一切正常，
 * 用户只是听不到（2026-11-21 投影仪实报《武林外传》h264+AC3：TV WebView
 * 直连，画面正常、dumpsys 里连播放器登记都没有）。这类文件的唯一出路
 * 与直连失败一样：转码。
 *
 * <p>只在片库直连启用（no-audio-fallback 只传了 isLibraryPlay）：
 * 点播走 hls.js/MSE，音频本来就是 AAC，不存在这条路径。
 */
/**
 * 「音频零解码」的统一出口：运行时检测（no-audio 事件）与编码预判
 * （probe 命中超纲）都落到这里，由 {@link resolvePlaybackRoute} 分配：
 *   · TV 壳 + 用户开了「无声自动用原生」→ 直接切原生播放器（设备解 AC3/DTS）
 *   · TV 壳（默认提示式）→ 提示条给「原生播放」按钮，不自动打断
 *   · 其余（PC/网页）→ 后端转码（能力预检后），预检失败也落到原生提示
 */
function onNoAudio(zeroAudio = false) {
  dbg('onNoAudio', 'tt=' + transcodeTried, 'nd=' + libraryNoDirect.value, 'native=' + hasNativePlayer())
  if (transcodeTried || libraryNoDirect.value) {
    /*
     * 已经在转/已决定不转：只补一条提示。
     * ★ 但**不能覆盖已有的按钮提示**（2026-11-23 实测踩过）：检测是周期性的，
     *   第二次回调会把「原生/转码」按钮条换成无按钮文本条，8 秒后自清 ——
     *   用户刚看到按钮就没了，点不到。已有提示时直接忽略。
     */
    if (!libraryNoDirect.value && !silentHint.value) {
      showSilentHint('该文件音轨网页解不了（AC3/DTS），建议用原生播放')
    }
    return
  }
  if (!isLibraryPlay.value) {
    /*
     * 点播直链音轨超纲（站点给 mp4+AC3 直链的场景）：与错误兜底同链路，
     * 走 src 式转码（能力预检后），没有直链就只提示换线路。
     */
    transcodeTried = true
    const u = typeof play.value?.url === 'string' ? play.value.url : ''
    void ensureTranscodeProbe().then((ok) => {
      if (!ok || !/^https?:/i.test(u)) {
        showSilentHint('该线路音轨网页解不了（AC3/DTS），建议换一条线路', hasNativePlayer())
        return
      }
      void tryTranscode(u)
    })
    return
  }
  if (!libraryLib.value || !libraryFile.value) return
  /*
   * ★ 运行时已确认「音频零解码」（这是比 probe 更硬的证据）：决策输入必须
   *   伪造为超纲 —— probe=null 时 resolvePlaybackRoute 会返回 direct（未探测
   *   按"交给运行时兜底"），而此刻兜底已经响了，不能再回 direct（实测：
   *   .76 上老后端无 probe 端点 → 检测触发却什么都不发生）。
   */
  const route = resolvePlaybackRoute(
    routeInput(
      mediaProbe.value && mediaProbe.value.ok
        ? {
            ...mediaProbe.value,
            // 运行时"零解码"是比 probe 更硬的证据：probe 说 AAC 也要按超纲处理
            acodec: zeroAudio ? 'ac3' : mediaProbe.value.acodec,
          }
        : { acodec: 'ac3' },
    ),
  )
  /*
   * 记忆化选择（2026-11-23 实报「每次进都弹提示」）：用户点过一次提示条上的
   * 按钮就按记忆直接执行，不再每集重复询问 —— 设备能力不会变。
   */
  const remembered = getPrefs().silentAction
  const effective: typeof route =
    route === 'native-hint' && remembered === 'native'
      ? 'native'
      : route === 'native-hint' && remembered === 'transcode'
        ? 'transcode'
        : route
  if (effective === 'native') {
    transcodeTried = true
    player.value?.pause()
    switchToNativePlayer()
    return
  }
  dbg('route', route, 'eff=' + effective, 'remembered=' + String(remembered))
  /*
   * remux：视频能解、只有音频超纲 → 让后端把视频原样 copy、音频转 AAC。
   * 画面零损失（4K 不再被砍成 1080p），代价是带宽（4K copy 较高，片库局域网可承受）。
   */
  if (effective === 'remux') {
    transcodeTried = true
    void ensureTranscodeProbe().then((ok) => {
      if (!ok) {
        showSilentHint('后端不支持转码，建议用原生播放', hasNativePlayer())
        return
      }
      // `.strm` 的目标是外部 URL，走点播式 remux（视频 copy + 音频 AAC）
      if (libraryStrmUrl.value) {
        void tryTranscode(libraryStrmUrl.value, true)
        return
      }
      if (!libraryLib.value || !libraryFile.value) return
      void tryLibraryTranscode(libraryLib.value, libraryFile.value, true)
    })
    return
  }
  if (effective === 'native-hint') {
    transcodeTried = true
    showSilentHint(
      `该文件音轨（${(mediaProbe.value?.acodec || 'AC3').toUpperCase()}）网页解不了，建议用原生播放`,
      true,
      true,
    )
    return
  }
  /*
   * H.264 Hi10P：转码是唯一出路（WebView 与电视硬解的共同盲区），但 RK3566
   * 软解+软编实测 0.34x，播 1 秒要转 3 秒 —— **边转边看必然卡死**。
   * HEVC Main10/AV1 10-bit 不在这里：它们要么直连、要么原生，轮不到这条提示。
   */
  if (effective === 'transcode') {
    const hi10p = isHi10p(mediaProbe.value || {})
    // 10-bit 是"这集文件"的属性，不是一次性选择：每次都提示（按钮仍可强行转码）
    if (hi10p) {
      transcodeTried = true
      // 起播前拦截的路径不会有播放器加载遮罩收尾，这里手动收掉
      loading.value = false
      error.value = ''
      playError.value = ''
      // ★ 直连必崩（PIPELINE_ERROR_DECODE）：立刻停掉 <video>，否则解码错误
      //   会把错误页盖在提示条下面（2026-11-23 实测：冷探测第一次进必现）
      libraryNoDirect.value = true
      player.value?.stop?.()
      showSilentHint('10-bit 片源，转码约 0.3 倍速，建议换 8-bit 源', false, true)
      return
    }
  }
  // transcode：能力预检后再起转（TV 本机执行模式没有 ffmpeg，直接给原生引导）
  transcodeTried = true
  void ensureTranscodeProbe().then((ok) => {
    if (!ok) {
      showSilentHint('后端不支持转码，建议用原生播放', hasNativePlayer())
      return
    }
    // `.strm`：转码源是它指向的外部地址，不是库里的文本文件（#6）
    if (libraryStrmUrl.value) {
      void tryTranscode(libraryStrmUrl.value)
      return
    }
    /*
     * ② WebDAV 片库的直链是**外网 src**（handleLibraryTranscode 对 WebDAV
     *   直接 400，只认 lib+path），lib+path 那条会失败 —— 判据：direct 不是
     *   自家 /api/library/stream 形态时改走点播式 src 转码（同一后端能力）。
     */
    const direct = libraryDirect.value
    if (/^https?:/i.test(direct) && !direct.includes('/api/library/stream')) {
      void tryTranscode(direct)
      return
    }
    void tryLibraryTranscode(libraryLib.value, libraryFile.value)
  })
}

/**
 * TV 遥控：把焦点打到提示条的主按钮上（2026-11-23 实报：不聚焦就得先按方向键找过去）。
 * tv-nav 的焦点模型是 `.meow-tv-focus` 类（不是 DOM activeElement），
 * 所以这里调壳注入的 __meowTvFocus(el, pin=true)。Vue 重渲染会重写 className，
 * 连打三拍（nextTick / 150ms / 400ms）保证最终生效。
 */
function focusSilentHint() {
  dbg('focusHint', 'tv=' + isTvEnv(), 'fn=' + typeof (window as unknown as { __meowTvFocus?: unknown }).__meowTvFocus)
  if (!isTvEnv()) return
  const apply = () => {
    const el = document.querySelector('[data-silent-hint] button[data-tv-first]') as HTMLElement | null
    dbg('focusApply', 'el=' + !!el)
    if (!el) return
    const focus = (window as unknown as { __meowTvFocus?: (e: HTMLElement, pin?: boolean) => void }).__meowTvFocus
    focus?.(el, true)
  }
  void nextTick(apply)
  window.setTimeout(apply, 150)
  window.setTimeout(apply, 400)
}

/**
 * 常驻性最短的出路提示条（top 圆角位，8 秒自清；带按钮时不清）。
 *
 * @param withNative    带「用原生播放」（TV 壳且桥可用）
 * @param withTranscode 带「转码播放」（片库 + 后端有 ffmpeg；设备没有
 *                      Dolby 解码时这是唯一有声路径，2026-11-23 实报）
 */
function showSilentHint(text: string, withNative = false, withTranscode = false) {
  silentHint.value = text
  silentHintNative.value = withNative && hasNativePlayer()
  silentHintTranscode.value =
    withTranscode && isLibraryPlay.value && !!libraryLib.value && !!libraryFile.value
  clearTimeout(silentHintTimer)
  if (!silentHintNative.value && !silentHintTranscode.value) {
    silentHintTimer = window.setTimeout(() => (silentHint.value = ''), 8000)
  } else {
    // 带按钮的提示：TV 上直接把焦点送过去，遥控器不用先找
    focusSilentHint()
  }
}

/** 提示条上的「原生播放」：先把无声直连停掉，避免两边同时响 */
function useNativeFromHint() {
  clearTimeout(silentHintTimer)
  silentHint.value = ''
  silentHintNative.value = false
  silentHintTranscode.value = false
  // 记住选择：下集不再重复问（设备能力不会变）
  setPref('silentAction', 'native')
  if (isLibraryPlay.value) libraryNoDirect.value = true
  player.value?.pause()
  switchToNativePlayer()
}

/**
 * 提示条上的「转码播放」（2026-11-23）：
 * 设备没有 Dolby 解码时原生也没音，转码出 AAC 才有声。与 onNoAudio 的
 * transcode 分支同一链路：能力预检 → WebDAV 直链走 src 式、其余走 lib+path。
 */
function useTranscodeFromHint() {
  dbg('clickTranscode')
  clearTimeout(silentHintTimer)
  silentHint.value = ''
  silentHintNative.value = false
  silentHintTranscode.value = false
  // ★ 不能用 transcodeTried 当守卫（2026-11-23 实测踩过）：提示条由
  //   onNoAudio 的 native-hint 分支弹出，那个分支已经把 transcodeTried
  //   置真（防重复分配），于是按钮点了直接 return —— 表现就是"点了没反应"。
  //   按钮是用户显式选择，必须放行。
  if (!isLibraryPlay.value) return
  if (!libraryLib.value || !libraryFile.value) return
  transcodeTried = true
  // 记住选择：下集直接转码，不再弹提示（设备没有 Dolby 时这就是最优路径）
  setPref('silentAction', 'transcode')
  libraryNoDirect.value = true
  player.value?.pause()
  void ensureTranscodeProbe().then((ok) => {
    if (!ok) {
      transcodeTried = false
      showSilentHint('后端不支持转码；该文件音轨网页解不了，建议用原生播放', hasNativePlayer())
      return
    }
    const direct = libraryDirect.value
    if (/^https?:/i.test(direct) && !direct.includes('/api/library/stream')) {
      void tryTranscode(direct)
      return
    }
    void tryLibraryTranscode(libraryLib.value, libraryFile.value)
  })
}

/**
 * 编码探测（后端 ffprobe 一次并缓存）。片库起播后异步拉，拿到就预判：
 * 命中超纲不等运行时检测，直接进分配（TV 原生 / 转码）。
 */
async function fetchProbeRoute() {
  // `.strm` 不是媒体文件，ffprobe 读不出编码（#6）；交给运行时检测
  if (libraryStrmUrl.value) return
  if (!isLibraryPlay.value || !libraryLib.value || !libraryFile.value) return
  // ★ 用「还是同一个 lib+file」判定结果是否作废，不用 epoch（2026-11-23 实测：
  //   挂载期的重复 load 会抬 epoch，把首个 probe 结果丢掉 → 预判形同虚设，
  //   只能等 ~10 秒后的运行时检测，用户看到的就是"提示条姗姗来迟"）
  const lib = libraryLib.value
  const file = libraryFile.value
  const p = await fetchMediaProbe(lib, file)
  if (!p?.ok) return
  if (!isLibraryPlay.value || libraryLib.value !== lib || libraryFile.value !== file) return
  mediaProbe.value = p
  dbg('probe', JSON.stringify({ a: p.acodec, v: p.vcodec, pf: p.pixfmt }))
  if (transcodeTried || libraryNoDirect.value) return
  const route = resolvePlaybackRoute(routeInput(p))
  if (route === 'direct') return
  // 预判命中：与运行时检测同一出口（onNoAudio 内部会按 route 再分配一次，
  // 这里先把 probe 塞进去即可）
  onNoAudio()
}

/**
 * 高丢帧自愈（2026-11-24）。
 *
 * 某些 4K 源的分片时间轴有缝（实测每片边界丢 1 帧、整集 874 片），Chromium
 * 渲染器只能靠丢帧追赶媒体时钟 → 25fps 只出 18fps，肉眼"一顿一顿"。
 * 这里切到后端"原画转封装"：视频 copy（画面零损失），由 ffmpeg 重新切分片、
 * 重排时间轴；从当前位置续播。
 *
 * 只对点播直链生效：片库已走 remux/直连策略；转码产物本身不再自愈。
 */
/**
 * 把后端的代理地址还原成源站真实地址。
 *
 * 为什么需要：hls.js 的 `levels[i].url` 可能是 `/api/proxy/stream?url=...`
 * （后端把变体清单也代理了），把它交给转码会被 SSRF 防线拒掉（回环地址），
 * 自愈就会失败并把用户丢到错误页。取 url 参数里的真实变体地址才对。
 */
function unwrapProxyUrl(u: string): string {
  try {
    const x = new URL(u, location.origin)
    if (x.pathname === '/api/proxy/stream') {
      const real = x.searchParams.get('url') || ''
      if (/^https?:/i.test(real)) return real
    }
  } catch {
    /* 解析失败就用原值 */
  }
  return u
}

/**
 * 这些失败是"暂时性"的（上游探测超时/限流/分片抖动），值得 15 秒后重试；
 * no_repair_encoder 是硬条件，重试也没用。
 */
function canRetryRepair(code?: string): boolean {
  return code === 'probe_failed' || code === 'source_timeout' || code === 'timeout'
    || code === 'stalled' || code === 'short_output'
}

/** 安排一次自愈重试（最多 3 次，间隔 15 秒） */
function scheduleHealRetry() {
  const ep = playEpoch
  healRetryAt = Date.now() + 15_000
  transcodeTried = false
  showSilentHint('修复暂时失败（片源探测超时/上游限流），15 秒后自动重试', false, false)
  window.setTimeout(() => {
    if (ep !== playEpoch) return
    if (healPayload) void onHighDrop(healPayload)
  }, 15_500)
}

async function onHighDrop(p: {
  dropped: number
  total: number
  ratio: number
  levelUrl?: string
  height?: number
}) {
  if (transcodeTried) return
  if (healAttempts >= 3 || Date.now() < healRetryAt) return
  const libMode = isLibraryPlay.value
  const u = typeof play.value?.url === 'string' ? play.value.url : ''
  if (libMode) {
    if (!libraryLib.value || !libraryFile.value) return
  } else if (!u || !/^https?:/i.test(u) || u.includes('/api/transcode')) {
    // 已经是转码产物/转码请求（query 或 path 两种形态）→ 不再套娃
    return
  }
  /*
   * ★ transcodeOk 初值是 null（还没探过后端），不能直接 `!transcodeOk.value`
   *   判断 —— 那会在第一次卡顿时直接 return（实测踩过：自愈一次都没触发）。
   */
  const ok = await ensureTranscodeProbe()
  ;(window as unknown as { __highDrop?: unknown }).__highDrop = {
    p, u, ok, transcodeTried, lib: libMode,
  }
  if (!ok || transcodeTried) return
  dbg('highDrop', JSON.stringify(p))
  transcodeTried = true
  healAttempts++
  healPayload = p
  healMinHeight = p.height && p.height > 0 ? p.height : 0
  healPrev = { url: u, header: typeof play.value?.header === 'string' ? play.value.header : undefined }
  /*
   * ★ 原画转封装（copy）修不了这种丢帧（2026-11-24 实测）：
   *   某 4K 源整段 GOP 全是 B 帧（has_b_frames=5），Chromium 解得出但渲染
   *   稳定丢 28% 帧；copy 保留码流结构，重封装后照样丢。只有**保持原分辨率
   *   用显卡重编**（NVENC，4K 仍是 4K）实测丢帧 0。所以自愈改走 repair。
   */
  /*
   * 从当前位置起修（2026-11-24 实报「还在卡」）：用户往往看到第 10 分钟才丢帧，
   * 从头转要等转码追上播放点。后端用 -ss 快进 + 黑场填充把时间轴垫到当前位置，
   * 前端时间/进度/弹幕/字幕都还是绝对时间，不需要偏移换算。
   */
  /*
   * 用播放器**实时**位置（getTime）而不是 lastTickPos：进度回调 5 秒一次，
   * 用户刚拖动过时 lastTickPos 还停在旧位置（实测 seek 到 20s 后自愈仍从 0 起转）。
   */
  const nowPos = Math.max(lastTickPos || 0, Number(player.value?.getTime?.() || 0))
  const startAt = Math.max(0, Math.floor(nowPos) - 8)
  // 低端设备（解不了 HEVC）让后端 repair 只出 H.264，避免"修完更卡/黑屏"
  const allowHevc = clientCanHevc()
  /*
   * 修复流的可播范围是 [startAt, 已转出]：切换后不能直接 seek 回当前点
   * （转码刚起步，那个位置还没转出来，会卡住）。从 startAt+2 起播，
   * 只回退 6 秒左右，随后正常往前播。
   */
  refreshSeekAt.value = startAt + 2
  showSilentHint('检测到画面丢帧，正在用显卡从当前位置按原分辨率修复（分辨率不变）…', false, false)
  /*
   * 用播放器当前选中的那一档变体（4K），别把母清单交给 ffmpeg（它会选 1080p）。
   * hls.js 的 level url 常被后端代理成 /api/proxy/stream?url=...，要还原成源站地址。
   */
  const level = p.levelUrl && /^https?:/i.test(p.levelUrl) ? unwrapProxyUrl(p.levelUrl) : ''
  if (libraryStrmUrl.value) {
    // `.strm` 与在线源同构：repair 的 src 用目标地址（level 已在上面还原）
    void tryTranscode(level || libraryStrmUrl.value, false, true, startAt, allowHevc)
    return
  }
  if (libMode) {
    void tryLibraryTranscode(libraryLib.value, libraryFile.value, false, true, startAt, allowHevc)
    return
  }
  void tryTranscode(level || u, false, true, startAt, allowHevc)
}

async function onPlayerError(payload: PlayErrorPayload | string) {
  dbg('playerError', JSON.stringify(payload).slice(0, 120))
  /*
   * 10-bit（Hi10P）直连必然解码失败（PIPELINE_ERROR_DECODE），这是**预期**：
   * 提示条已经给出「转码播放」，不能再把错误页盖上去（2026-11-23 实测：
   * 冷探测时 probe 先到、错误后到，先清 playError 会被它重新写回来）。
   */
  if (
    isLibraryPlay.value &&
    (libraryNoDirect.value || isHi10p(mediaProbe.value || {}))
  ) {
    // 已经决定不走直连（转码/原生/10-bit）：直连元素残余的解码错误不应再弹错误页
    playError.value = ''
    return
  }
  autoRetryHint.value = ''
  // 已有失败记录说明这次换过来也没成，先把当前线路也记上
  addFailedLine(flag.value)

  // ★ 不再自动切到原生播放器（ExoPlayer）。
  //
  //   原来这里"TV 一出错就 openNative()"，理由是 ExoPlayer 能解 HEVC。
  //   实测后果：任何一次起播超时 / 流抖动都会把用户**整页切走**到另一个
  //   Activity——用户看到的就是"偶尔自己弹出内置播放器"，而且回来时
  //   播放进度、弹幕、控制栏全没了。错误处理里做页面级跳转，代价太大。
  //
  //   现在保留入口但不自动触发：错误页上有「用原生播放器打开」按钮
  //   （见 useNativePlayer），用户想试才点。换线路 / 转码兜底不变。
  const kind = isPlayErrorPayload(payload) ? payload.kind : 'unknown'
  const msg = isPlayErrorPayload(payload) ? payload.msg : String(payload)
  // 诊断用：只有 timeout/stall 才算"起播看门狗到期"（见 lastPlayKind 注释）
  lastPlayKind.value = kind
  /*
   * `.strm` 的地址通常是**时效链接**（网盘签名几十分钟就过期）。
   * 打不开时要说清"是直链过期了、不是播放器坏了"，否则用户只会一直重试。
   */
  if (libraryIsStrm.value && (kind === 'http' || kind === 'network' || kind === 'stall')) {
    playError.value = `直链打不开（.strm 里的地址多半已过期，需要重新生成直链）\n${msg}`
    hopError.value = ''
    return
  }
  playError.value = msg
  hopError.value = ''

  // 浏览器解不了的格式（MKV 容器 / HEVC / DTS 音轨）换线路也没用：
  // 同一集在不同源上编码基本一样。这里让后端现转 HLS 兜一次——
  // 实测 11 倍速、源文件不动，H.265 4K 的 MKV 在浏览器里能播且能拖。
  //
  // ★ 片库播放走另一条兜底（2026-11-02）：直链失败时按 lib+path 让后端
  //   自己读文件转码。片库没有"换线路"的概念，转码是唯一出路。
  //
  // ★ 但只对**格式类失败**生效（2026-11-23 实测修正）：原来任何错误
  //   （包括一次网络抖动、以及播放器换源时的无 MediaError 伪错误）都会
  //   把整集送去转码，用户看到的就是"一进去就自己转码 / 弹转码失败"。
  //   网络类交给播放器自身重连和错误页的「重试」。
  if (!transcodeTried && isLibraryPlay.value && shouldTranscode(kind)) {
    transcodeTried = true
    // `.strm` 是外部地址，转码要按点播 src 传，不能按片库文件（#6）
    if (libraryStrmUrl.value) {
      void tryTranscode(libraryStrmUrl.value)
      return
    }
    if (libraryLib.value && libraryFile.value) {
      void tryLibraryTranscode(libraryLib.value, libraryFile.value)
      return
    }
  }
  /*
   * 只有**解码/容器类**失败才转码。
   *
   * 网络抖动、超时、403 换线就有用，送去转码只会白等（PC 最多 90 秒），
   * TV 网关更是没有 ffmpeg，直接弹一句"找不到 ffmpeg"——用户看到的是
   * 一个跟影片无关的报错。先探测一次后端能力，没有 ffmpeg 就不走这条路。
   */
  if (!transcodeTried && shouldTranscode(kind)) {
    const raw = play.value?.url
    const u = typeof raw === 'string' ? raw : ''
    if (u && /^https?:/i.test(u) && (await ensureTranscodeProbe())) {
      transcodeTried = true
      void tryTranscode(u)
      return
    }
  }

  const cand = nextLineCandidate.value
  if (!cand) {
    // 全试完了：停在错误页，让用户自己决定
    errorSource.value = 'play'
    error.value = msg
    return
  }

  autoRetryHint.value = `正在尝试线路「${cand.name}」…`
  showLineHint(cand.name)
  switchLine(cand.name, { auto: true })
}

/** 错误页的「重试」：清掉失败记录，重新走一遍（含自动换源的机会） */
function retryFromError() {
  error.value = ''
  playError.value = ''
  hopError.value = ''
  errorSource.value = 'fetch'
  try {
    sessionStorage.removeItem(FAILED_KEY)
  } catch {
    /* 忽略 */
  }
  void load()
}

// ---------------------------------------------------------------- 投屏

/** 当前能不能投：要有可直接推的 http 地址（解析器出来的地址常带时效，不适合推） */
const canCastCurrent = computed(() => {
  if (isLibraryPlay.value) return false
  // 需要专用解析器的源投出去，电视端只会拿到一串加密串；组件 props 注释也是这么约定的
  if (needsParser.value) return false
  if (play.value && needsParse(play.value)) return false
  const raw = play.value?.url
  const u = typeof raw === 'string' ? raw : ''
  return !!u && /^https?:/i.test(u)
})

/** 投屏设备：DLNA 标准渲染器，或装了我们的设备（kind=meow）。 */
type CastDev = {
  name: string
  host: string
  location: string
  controlUrl: string
  connControlUrl?: string
  udn?: string
  type?: string
  kind?: 'dlna' | 'meow'
  base?: string
  token?: string
}

const castDevices = ref<CastDev[]>([])
const castBusy = ref(false)
const castSearching = ref(false)
const castMsg = ref('')
/** 成功提示画在播放器 overlay 里（全屏也看得见），面板 900ms 后自动收起 */
const castToast = ref('')
let castToastTimer: number | undefined
/** 面板打开序号：成功自动收起时避免把用户刚重开的面板关掉 */
let castPanelSeq = 0
function showCastToast(text: string, ms = 2200) {
  castToast.value = text
  window.clearTimeout(castToastTimer)
  castToastTimer = window.setTimeout(() => (castToast.value = ''), ms)
}

/**
 * 不能投的原因。
 *
 * <p>★ 2026-11-20 起播放器右上角入口在不可投时**整颗不渲染**（用户定），
 * 所以这里不再给按钮当 tooltip，只留给 `openCast` 的兜底提示 —— 入口虽然
 * 没了，但代码路径（以及将来别的入口）仍要能说清楚为什么不能投。
 */
const castReason = computed(() => {
  if (isLibraryPlay.value) return '片库直连播放不能投屏'
  if (needsParser.value) return '该源需要专用解析器，投出去电视无法播放'
  if (play.value && needsParse(play.value)) return '这条线路需要解析，不能直接投屏'
  return '当前没有可投屏的播放地址'
})

/** 设备分组：猫窝（带弹幕/跳片头）优先，其它 DLNA 电视在后 */
const castGroups = computed(() =>
  [
    { kind: 'meow', title: '猫窝设备', items: castDevices.value.filter((d) => d.kind === 'meow') },
    { kind: 'dlna', title: 'DLNA 电视', items: castDevices.value.filter((d) => d.kind !== 'meow') },
  ].filter((g) => g.items.length > 0),
)

/** 宽屏（非 TV 壳）把面板锚到触发按钮；手机/TV 仍居中 */
const castAnchorLeft = ref<number | null>(null)
const castPanelAnchored = computed(() => {
  if (castAnchorLeft.value == null) return false
  if (isTvShell()) return false
  return isWideLayout()
})
const castPanelStyle = computed(() => {
  if (!castPanelAnchored.value || castAnchorLeft.value == null) return {}
  const root = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16
  const width = 22 * root
  const left = Math.max(8, Math.min(castAnchorLeft.value, window.innerWidth - width - 8))
  // 图标在右上角：面板挂在它下面；给底栏留出 5.75rem（4.25 + 5.75 合计 10rem）
  return { left: `${left}px`, top: '4.25rem', maxHeight: 'calc(100dvh - 10rem)' }
})

/** 已投的会话。null = 当前没在投屏。 */
interface CastSession {
  device: CastDev
  title: string
  /** B 类会话 id（DLNA 没有）。 */
  sessionId?: string
}

const castSession = ref<CastSession | null>(null)
const castState = ref('')
const castPosition = ref(0)
const castDuration = ref(0)
/** 连续 5 次轮询失败 → 判定接收端消失 */
const castLost = ref(false)
/** A 类设备"回了 playing 但 position/duration 一直是 0"的看门狗（V9 这类占位 DMR） */
const castStall = ref(false)
const castSeekMax = computed(() => Math.max(1, Math.round(castDuration.value || 0)))
let castPollTimer: number | undefined
let castFailCount = 0
let castLastFlush = 0
let castEndedHandled = false
let castStallCount = 0

const CAST_STORE_KEY = 'meow.cast.session'

/** UPnPError → 人话。不同 code 处置完全不同。 */
const CAST_HINT: Record<string, string> = {
  '701': '电视状态不对，稍后重试',
  '402': '电视不接受这个地址',
  '602': '电视不支持这个操作',
  '710': '电视不支持这种拖动',
  '711': '拖动位置不合法',
}

function saveCastSession() {
  try {
    if (castSession.value) sessionStorage.setItem(CAST_STORE_KEY, JSON.stringify(castSession.value))
    else sessionStorage.removeItem(CAST_STORE_KEY)
  } catch {
    /* 忽略 */
  }
}

/** 离开播放页再回来时恢复"正在电视上播放"的状态（电视本身一直在放）。 */
function restoreCastSession() {
  try {
    const raw = sessionStorage.getItem(CAST_STORE_KEY)
    if (!raw) return
    const s = JSON.parse(raw) as CastSession
    if (!s?.device?.controlUrl) return
    castSession.value = s
    startCastPoll()
  } catch {
    /* 忽略 */
  }
}

function stopCastPoll() {
  if (castPollTimer) window.clearInterval(castPollTimer)
  castPollTimer = undefined
}

function startCastPoll() {
  stopCastPoll()
  castFailCount = 0
  castStallCount = 0
  castLost.value = false
  castStall.value = false
  castPollTimer = window.setInterval(() => void pollCast(), 1500)
  void pollCast()
}

async function pollCast() {
  const s = castSession.value
  if (!s) return
  const { castStatus, castSessionStatus } = await import('@/api/library')
  const st = s.device.kind === 'meow'
    ? await castSessionStatus(s.device, s.sessionId || '')
    : await castStatus(s.device)
  if (!st.ok) {
    castFailCount += 1
    if (castFailCount >= 5) {
      castLost.value = true
      stopCastPoll()
    }
    return
  }
  castFailCount = 0
  castState.value = st.state || ''
  castPosition.value = st.position || 0
  castDuration.value = st.duration || 0
  /*
   * A 类里有"只回 PLAYING、不取流"的占位 DMR（投影仪的 投屏电视V9：Set 后
   * NrTracks=0、position/duration 永远 0）。9 秒还这样就在遥控条上明说，
   * 别让用户以为在缓冲。B 类不参与（页面起播前几秒 duration=0 是正常的）。
   */
  if (
    s.device.kind !== 'meow' &&
    castState.value === 'playing' &&
    castDuration.value === 0 &&
    castPosition.value === 0
  ) {
    castStallCount += 1
    if (castStallCount >= 6) castStall.value = true
  } else {
    castStallCount = 0
    castStall.value = false
  }
  // 投屏期间历史要写"电视的位置"，否则下次续播会跳回本机停在 0 的旧点
  const now = Date.now()
  if (castDuration.value > 0 && now - castLastFlush > 10000) {
    castLastFlush = now
    flushToHistory(false, { pos: castPosition.value, dur: castDuration.value })
  }
  // 播完：本地 onEnded 永远不会触发，由接收端状态驱动
  if (
    !castEndedHandled &&
    castDuration.value > 0 &&
    castPosition.value >= castDuration.value - 5 &&
    (castState.value === 'stopped' || castState.value === 'paused')
  ) {
    castEndedHandled = true
    onEnded()
  }
}

/**
 * 把当前媒体重推到电视（切集 / 切线路 / 首次投屏共用）。
 *
 * 顺序：SetAVTransportURI + Play，再等设备进播放态后 Seek 到原位置
 * （Set 会把位置重置为 0；不等就 Seek 会被 701 拒）。
 */
async function pushCast(mediaUrl: string, position: number, titleText?: string): Promise<boolean> {
  const s = castSession.value
  if (!s) return false
  const cb = await castBase(s.device.host)
  if (!cb) {
    castMsg.value = '没有可用的局域网地址，无法投屏'
    return false
  }
  const proxied = proxyStream(mediaUrl, sanitizeCastHeader(play.value?.header), {
    base: cb.base,
    token: cb.token,
  })
  const text = titleText || `${title}${epName.value ? ' · ' + epName.value : ''}`
  const { castControl, castProtocols, castSessionPlay } = await import('@/api/library')

  if (s.device.kind === 'meow') {
    // B 类：推会话（带弹幕地址），接收端自己播放并汇报状态
    const danmakuUrl = proxyDanmaku(title, epName.value || '1', {
      base: cb.base,
      token: cb.token,
      year: titleYear,
    })
    const r = await castSessionPlay(
      s.device,
      {
        url: proxied,
        title: text,
        danmakuUrl,
        site,
        vodId: vodId || undefined,
        ep: epName.value || undefined,
        position: Math.round(position),
      },
    )
    if (!r.ok) {
      castMsg.value = r.msg || '投屏失败'
      return false
    }
    s.sessionId = r.sessionId || ''
    s.title = text
    saveCastSession()
    castState.value = r.state || 'playing'
    castPosition.value = position
    castEndedHandled = false
    castLastFlush = Date.now()
    if (!castPollTimer) startCastPoll()
    return true
  }

  /*
   * A 类（DLNA）能力预判：Sink 列表里完全没有 video/* 的（典型是小爱音箱这类音频渲染器）
   * 直接拒，别让用户等了半天只看到"设备没有响应"。
   * 列表为空表示设备没实现 GetProtocolInfo，只能放行（很多电视确实不报）。
   */
  const sink = await castProtocols(s.device)
  // `http-get:*:*:*` 是"什么都收"的通用声明（GUPnP 常见），不能当成不支持视频
  const videoish = (p: string) =>
    /video\//i.test(p) || /mpegurl/i.test(p) || /http-get:\*:\*:\*/i.test(p)
  if (sink.length && !sink.some(videoish)) {
    castMsg.value = '这台设备只支持音频，不能投视频'
    return false
  }
  const r = await castControl('play', s.device, { url: proxied, title: text })
  if (!r.ok) {
    const hint = r.code ? CAST_HINT[r.code] || '' : ''
    castMsg.value = `${r.msg || '投屏更新失败，电视仍在播上一集'}${hint ? '（' + hint + '）' : ''}`
    return false
  }
  s.title = text
  saveCastSession()
  castState.value = 'playing'
  castPosition.value = position
  castEndedHandled = false
  castLastFlush = Date.now()
  if (position > 2) {
    window.setTimeout(() => {
      void castControl('seek', s.device, { seconds: Math.round(position) })
    }, 1200)
  }
  if (!castPollTimer) startCastPoll()
  return true
}

/** 投屏中切集/切线路：新地址到达后自动重推，本地保持暂停（否则两边同时出声）。 */
watch(src, () => {
  if (!castSession.value) return
  const raw = play.value?.url
  const u = typeof raw === 'string' ? raw : ''
  if (!u || !/^https?:/i.test(u)) return
  void pushCast(u, castPosition.value)
})

async function castToggle() {
  const s = castSession.value
  if (!s) return
  const { castControl, castSessionCommand } = await import('@/api/library')
  const pause = castState.value === 'playing'
  const action = pause ? 'pause' : 'play'
  const r = s.device.kind === 'meow'
    ? await castSessionCommand(s.device, s.sessionId || '', action)
    : await castControl(action, s.device)
  if (r.ok) castState.value = pause ? 'paused' : 'playing'
}

async function onCastSeek(e: Event) {
  const v = Number((e.target as HTMLInputElement).value)
  if (!Number.isFinite(v)) return
  castPosition.value = v
  const s = castSession.value
  if (!s) return
  const { castControl, castSessionCommand } = await import('@/api/library')
  if (s.device.kind === 'meow') {
    await castSessionCommand(s.device, s.sessionId || '', 'seek', Math.round(v))
  } else {
    await castControl('seek', s.device, { seconds: Math.round(v) })
  }
}

/** 停止投屏：读最后位置 → 本机从该位置续播。 */
async function stopCast(resume = true) {
  const s = castSession.value
  if (!s) return
  const { castControl, castSessionCommand } = await import('@/api/library')
  const pos = castPosition.value
  const dur = castDuration.value
  if (s.device.kind === 'meow') {
    await castSessionCommand(s.device, s.sessionId || '', 'stop')
  } else {
    await castControl('stop', s.device)
  }
  stopCastPoll()
  castSession.value = null
  castState.value = ''
  castLost.value = false
  castStall.value = false
  castEndedHandled = false
  saveCastSession()
  castMsg.value = ''
  if (pos > 1) flushToHistory(true, { pos, dur })
  if (!resume) return
  window.setTimeout(() => {
    if (pos > 1) player.value?.seekTo?.(pos)
    player.value?.play?.()
  }, 300)
}

function fmtSec(sec: number) {
  const s = Math.max(0, Math.floor(sec || 0))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = String(s % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

// 离开播放页不停止投屏（电视继续放）；只停本地轮询。
onMounted(restoreCastSession)

/*
 * iOS 端内播放器（VLCKit）关闭时把位置写回历史（2026-11-27）。
 *
 * 原生层播的是同一集，但 currentTime 在原生进程里 —— 不写回的话，用户从
 * 原生回网页后历史还停在进页位置（TV 的 metaJson 回传解决的是同一个问题）。
 */
let offIosNativeClosed: (() => void) | null = null
/**
 * 刚从原生播放器回来（2026-11-27 真机实报「返回后黑屏」）。
 *
 * <p>原生 dismiss 后父页面恢复可见，但如果原地址是 MKV，这一帧会立刻再走
 * 一次「转码预检 → 拉起原生」，形成「返回→又进原生」的死循环，视觉上就是
 * 黑屏。标记只挡一次，用户手动点重试/换集后照常。
 */
let nativeJustClosed = false
onMounted(() => {
  offIosNativeClosed = onIosNativeClosed((r) => {
    if (r.positionSec > 0) {
      saveProgress(
        { site, vod_id: vodId },
        {
          pos: r.positionSec,
          dur: r.durationSec,
          flag: flag.value,
          ep: epName.value,
          epUrl: rawId.value,
        },
        { notify: r.ended },
      )
    }
    /*
     * 左上角「返回」（2026-11-27 用户要求）：直接回上一页，**不弹**"已退出
     * 端内播放器"面板。控制层是独立 WebView，历史里只有它自己，所以后退必须
     * 由父页面代劳；没有历史可退时（深链进入）兜底回片库/首页。
     */
    if (r.nav === '__back__') {
      nativeExited.value = false
      nativeJustClosed = true
      const before = route.fullPath
      router.back()
      window.setTimeout(() => {
        if (route.fullPath !== before) return
        void router.replace(isLibraryPlay.value ? { name: 'library' } : { name: 'home' })
      }, 300)
      return
    }
    /*
     * 搜索/设置这类入口：关原生后直接路由过去（控制层在独立 WebView 里，
     * 自己跳不动父页面，只能由这里代劳）。
     */
    if (r.nav) {
      void router.push(r.nav)
      return
    }
    /*
     * 播完了：按连播设置直接进下一集（2026-11-27）。
     *
     * 原生页结束时已经把播放器 dismiss 了，父页面是可见的；这里如果不接手，
     * 用户看到的就是"一集播完停在已退出面板"。倒计时分支在父页面里画不出来
     * （倒计时浮层挂在 VideoPlayer 的 #overlay 里，而父页面没挂播放器），
     * 所以在原生链路里 countdown/immediate 一律按"立即下一集"处理。
     */
    if (
      r.ended &&
      prefs.autoNextMode !== 'off' &&
      currentIndex.value >= 0 &&
      currentIndex.value + 1 < playlist.value.length
    ) {
      // 这次是"要继续播"：不能让 nativeJustClosed 把重新拉起原生的那次吞掉
      nativeJustClosed = false
      nativeExited.value = false
      switchEpisode(currentIndex.value + 1)
      return
    }
    /*
     * 回到 web 后必须把上一轮的错误/遮罩清干净：不清的话父页面停在
     * 「本端没有转码能力」那张错误页上（黑底 + 文字），用户看到的就是黑屏。
     */
    nativeJustClosed = true
    error.value = ''
    hopError.value = ''
    playError.value = ''
    loading.value = false
    loadingText.value = ''
    transcodeWaiting.value = false
    libraryNoDirect.value = false
    /*
     * 明确给一个"已退出"的界面：父页面若去尝试 web 直连 MKV 必然失败，
     * 用户看到的是黑屏 + 一个很小的错误页（2026-11-27 真机实报「返回黑屏」）。
     */
    nativeExited.value = true
    dbg('ios-native-closed', `${r.positionSec}/${r.durationSec} ended=${r.ended}`)
  })
})
onBeforeUnmount(() => {
  offIosNativeClosed?.()
  offIosNativeClosed = null
})

/**
 * 原生播放器退出时回传选中轨道编码（桥 meowTv.nativeTracks(key)）。
 *
 * 原生解的是真 4K/HEVC，网页侧拿不到这些信息：面板要能区分
 * 「原生直出 4K」和「后端转码 1080p」，否则用户会把两者混为一谈。
 * 老壳没有这个桥 → 静默跳过，不影响播放。
 */
function syncNativeTracks() {
  try {
    const w = window as unknown as {
      meowTv?: { nativeTracks?: (key: string) => string }
    }
    const bridge = w.meowTv
    if (!bridge || typeof bridge.nativeTracks !== 'function') return
    const key = [site, vodId, epName.value].filter(Boolean).join('|')
    const raw = bridge.nativeTracks(key)
    if (!raw) return
    const j = JSON.parse(raw) as {
      vcodec?: string
      acodec?: string
      pixfmt?: string
      width?: number
      height?: number
    }
    mediaInfo.value = {
      via: 'native',
      srcVcodec: j.vcodec || '',
      srcAcodec: j.acodec || '',
      srcPixfmt: j.pixfmt || '',
      srcWidth: j.width || 0,
      srcHeight: j.height || 0,
    }
  } catch {
    /* 桥返回坏 JSON / 老壳没有：都不影响播放 */
  }
}

/** 同片里第一条非 4K 线路（用于"自动降 1080p"） */
function lowerLineName(): string {
  const ls = (lines.value || []).filter(
    (l) => l.name !== flag.value && !/4k|2160|uhd|原盘/i.test(l.name),
  )
  return ls[0]?.name || ''
}

const lowerLine = computed(() => lowerLineName())

function scheduleBusyRetry() {
  clearTimeout(busyTimer)
  busyTimer = window.setTimeout(() => {
    if (!busyRetrying.value) return
    busyRetries.value++
    if (busyRetries.value > BUSY_MAX_RETRIES) {
      busyRetrying.value = false
      transcodeWaiting.value = false
      loadingText.value = ''
      error.value = '排队超时：另一台设备仍在转码，稍后再试或换 1080p 线路'
      return
    }
    loadingText.value = `排队中：另一台设备正在转 4K（第 ${busyRetries.value} 次重试）…`
    transcodeWaiting.value = true
    if (busyKind === 'lib') {
      void tryLibraryTranscode(busyLib, busyFile, busyCopy, busyRepair, busyStart, busyHevc)
    } else {
      void tryTranscode(busySrc, busyCopy, busyRepair, busyStart, busyHevc)
    }
  }, BUSY_RETRY_MS)
}

function startBusyQueue() {
  // 浮层保持可见、文案切到"排队中"：不依赖加载遮罩（直连失败后 src 还在，
  // loading 遮罩不显示，用户会以为点了没反应）
  busyHint.value = true
  busyRetrying.value = true
  busyRetries.value = 0
  scheduleBusyRetry()
}

function cancelBusyQueue() {
  busyRetrying.value = false
  clearTimeout(busyTimer)
  busyHint.value = false
}

function switchToLowerLine() {
  const n = lowerLineName()
  if (!n) {
    showSilentHint('没有找到非 4K 线路，可以排队等前一台结束', false, false)
    return
  }
  cancelBusyQueue()
  switchLine(n)
}

/**
 * 从原生层回到网页时把进度带回来（2026-11-23 实报「退回来进度不带回」）。
 *
 * 原生播放器每 12 秒 + 退出时把进度 POST 到 /api/state/progress；网页层
 * 还停在切换前的旧位置。这里拉一次后端进度，取「本站点 + 本片」最新一条，
 * 若属于当前这一集就 seek 过去并继续播（用户退出原生时本来就在播）。
 * 失败静默：同步不上最多是回到旧位置，不能因此打断观看。
 */
async function syncProgressFromNative() {
  syncNativeTracks()
  if (!vodId || !site) return
  try {
    const items = await fetchStateProgress()
    const mine = items.filter((x) => x.site === site && String(x.vod_id) === String(vodId))
    if (!mine.length) return
    const latest = mine.reduce((a, b) => ((a.ep_at || 0) >= (b.ep_at || 0) ? a : b))
    // 后端进度是"这一片最新一集"的：集名对不上说明看的是别的集，别乱跳
    if (latest.ep && epName.value && latest.ep !== epName.value) return
    const pos = Number(latest.pos)
    if (!Number.isFinite(pos) || pos <= 0) return
    const cur = player.value?.getTime?.() ?? 0
    if (Math.abs(pos - cur) < 3) return
    player.value?.seekTo(pos)
    player.value?.play()
  } catch {
    /* 进度同步失败不影响观看 */
  }
}

/** 原生内嵌层关闭：壳会调这个钩子 —— 回网页后同步原生播放到的位置 */
const onEmbedClosed = () => {
  showSilentHint('已回到网页播放（该文件音轨网页解不了，仍无声）', false)
  void syncProgressFromNative()
}
window.addEventListener('meow-embed-closed', onEmbedClosed)
onBeforeUnmount(() => window.removeEventListener('meow-embed-closed', onEmbedClosed))
// 独立原生播放页（openNative 整页）返回时不会有 embed 钩子：页面重新可见就同步
const onVisibilityChange = () => {
  if (document.visibilityState === 'visible') void syncProgressFromNative()
}
document.addEventListener('visibilitychange', onVisibilityChange)
onBeforeUnmount(() => document.removeEventListener('visibilitychange', onVisibilityChange))
onBeforeUnmount(stopCastPoll)

/** 打开投屏面板并开始发现设备。anchor 是触发按钮在播放器内的左偏移（宽屏锚定用）。 */
async function openCast(anchor?: { left?: number }) {
  if (!canCastCurrent.value) {
    showCastToast(castReason.value)
    return
  }
  castAnchorLeft.value = typeof anchor?.left === 'number' ? anchor.left : null
  castPanelSeq += 1
  castSearching.value = true
  castMsg.value = ''
  castDevices.value = []
  // 打开投屏面板 = 接受被投：同时把本机接收端开起来（只监听，不弹窗）
  try {
    const { setReceiver } = await import('@/api/library')
    localStorage.setItem('meow.cast.receiver', '1')
    void setReceiver(true)
  } catch {
    /* 忽略：接收端起不来不影响当发送端 */
  }
  try {
    const { castDiscover } = await import('@/api/library')
    const list = await castDiscover(3000)
    // 我们的设备排前面（B 类体验更好：带弹幕/跳片头）；其他电视在后
    castDevices.value = list.sort(
      (a, b) => (a.kind === 'meow' ? 0 : 1) - (b.kind === 'meow' ? 0 : 1),
    )
    if (!castDevices.value.length) {
      castMsg.value = '没找到可投屏的设备。确认电视和这台设备在同一个局域网，且电视开了投屏/DLNA。'
    }
  } finally {
    castSearching.value = false
  }
}

/** 关掉投屏面板（返回键 / 面板上的关闭按钮共用） */
function closeCast() {
  castDevices.value = []
  castMsg.value = ''
  castSearching.value = false
  castAnchorLeft.value = null
}

/** 推给选中的设备 */
async function castTo(dev: CastDev) {
  const raw = play.value?.url
  const u = typeof raw === 'string' ? raw : ''
  if (!u) return
  castBusy.value = true
  try {
    const text = `${title}${epName.value ? ' · ' + epName.value : ''}`
    // 先建会话再推：pushCast 负责取局域网旁路地址、加令牌、抓描述失败回滚
    castSession.value = { device: dev, title: text }
    const ok = await pushCast(u, 0, text)
    if (ok) {
      // 投上去了本地就暂停，避免两边同时放
      player.value?.pause?.()
      castMsg.value = ''
      showCastToast(`已投到「${dev.name}」`)
      // 成功后面板自动收起，别继续盖着画面；遥控条仍在左上角
      const seq = castPanelSeq
      window.setTimeout(() => {
        if (seq === castPanelSeq) closeCast()
      }, 900)
    } else {
      castMsg.value = castMsg.value || '投屏失败'
      castSession.value = null
      saveCastSession()
      stopCastPoll()
    }
  } finally {
    castBusy.value = false
  }
}

/** 自动换源时给个轻提示（不打断播放） */
const lineHint = ref('')
let lineHintTimer: number | undefined
function showLineHint(name: string) {
  lineHint.value = `线路「${flag.value}」不可用，已切到「${name}」`
  clearTimeout(lineHintTimer)
  lineHintTimer = window.setTimeout(() => (lineHint.value = ''), 6000)
}

// ---------------------------------------------------------------- 离线缓存

/** 本集缓存状态：'' 未开始 / 'running' 下载中 / 'done' 已可离线 / 'failed' */
const cacheState = ref<'' | 'running' | 'done' | 'failed'>('')
const cachePercent = ref(0)
const cacheHint = ref('')
let cachePullTimer: number | undefined
let cacheHintTimer: number | undefined

function flashCacheHint(text: string, ms = 6000) {
  cacheHint.value = text
  clearTimeout(cacheHintTimer)
  cacheHintTimer = window.setTimeout(() => (cacheHint.value = ''), ms)
}

/** 进页面时同步一次本集缓存状态，已下好的显示「已缓存」而不是「下载」 */
async function syncCacheState() {
  if (!site || !vodId || !epName.value) return
  const id = cacheIdFor(site, vodId, epName.value)
  try {
    const r = await fetch(`${apiBase()}/api/cache?id=${encodeURIComponent(id)}`)
    if (!r.ok) return
    const j = (await r.json()) as { state?: string; percent?: number }
    if (j.state === 'done') cacheState.value = 'done'
    else if (j.state === 'running') {
      cacheState.value = 'running'
      cachePercent.value = j.percent || 0
      pollCache(id)
    }
  } catch {
    /* 后端不可达就不显示状态，不影响播放 */
  }
}

/** 这一集能不能下载到本机（片库直链、需解析器的源都不能） */
const canDownloadCurrent = computed(() => {
  if (isLibraryPlay.value) return false
  if (!site || !vodId || !epName.value) return false
  const raw = play.value?.url
  const u = typeof raw === 'string' ? raw : ''
  return !!u && /^https?:/i.test(u)
})

/** 能不能批量下载整季：要有多集，且当前线路的地址是可直接抓的 http */
const canDownloadAll = computed(() => {
  if (isLibraryPlay.value) return false
  if (!site || !vodId) return false
  if (playlist.value.length < 2) return false
  const raw = play.value?.url
  const u = typeof raw === 'string' ? raw : ''
  return !!u && /^https?:/i.test(u)
})

/**
 * 下载整季。
 *
 * <p><b>为什么不用当前集的 url 套到每一集</b>：每集的地址都不同（源站给的
 * 是带签名的独立地址），拿当前集的地址批量请求只会下出同一个文件。
 * 所以必须逐集走一次播放接口取地址——这也是为什么这个操作要先确认：
 * 12 集就是 12 次 play 请求。
 */
async function downloadAll() {
  if (!site || !vodId) return
  const eps = playlist.value
  if (!eps.length) return
  cacheHint.value = `正在获取 ${eps.length} 集的地址…`
  const items: Array<{ site: string; vodId: string; ep: string; name: string; url: string }> = []
  for (const ep of eps) {
    try {
      const r = await fetchPlay(site, flag.value, ep.url)
      const u = typeof r.url === 'string' ? r.url : ''
      // 需要解析器的集跳过：解析出的地址通常带时效，存下来很快就失效
      if (!u || !/^https?:/i.test(u)) continue
      items.push({ site: site, vodId, ep: ep.name, name: title || '', url: u })
    } catch {
      /* 单集取不到就跳过，不要把整季拖死 */
    }
  }
  if (!items.length) {
    flashCacheHint('没有可下载的集（可能需要解析器）')
    return
  }
  try {
    const { cacheBatch } = await import('@/api/library')
    const r = await cacheBatch(items)
    flashCacheHint(`已加入 ${r.startedCount} 集到下载队列` + (r.skipped.length ? `，跳过 ${r.skipped.length} 集` : ''), 8000)
    cacheState.value = 'running'
    syncCacheState()
  } catch (e) {
    flashCacheHint(e instanceof Error ? e.message : '批量下载失败')
  }
}

/** 下载本集到后端缓存目录，供断网观看。
 *
 * <p>要的是**当前线路当前的播放地址**：缓存是按 URL 抓分片的，
 * 拿不到 url 就没法下（比如走了解析器、地址是加密串的情况）。
 */
async function downloadCurrent() {
  if (cacheState.value === 'running') return
  const raw = play.value?.url
  const url = typeof raw === 'string' ? raw : ''
  if (!url || !/^https?:/i.test(url)) {
    flashCacheHint('这一集没有可直接下载的地址（需要解析器的源不支持缓存）')
    return
  }
  if (!site || !vodId || !epName.value) return
  cacheState.value = 'running'
  cachePercent.value = 0
  try {
    const { cacheEpisode } = await import('@/api/library')
    const t = await cacheEpisode({
      site: site,
      vodId,
      ep: epName.value,
      name: title || '',
      url,
      /*
       * 展示元数据一并存下：离线时「我的缓存」靠它出海报和片名。
       * 播放页只能拿到历史里记过的封面（详情页会补），拿不到就给空 ——
       * 后端对空值不覆盖已有值，之后从详情页发起缓存时会补上。
       */
      poster: posterPic(),
      // title 可能带"第N集"后缀，聚合时后端会再 stripEpSuffix 一次
      vodName: title || '',
    })
    cachePercent.value = t.percent || 0
    if (t.state === 'done') cacheState.value = 'done'
    else pollCache(t.id)
  } catch (e) {
    cacheState.value = 'failed'
    flashCacheHint(e instanceof Error ? e.message : '下载失败')
  }
}

/** 轮询下载进度。3 秒一次——分片是 4 路并发拉的，更密没意义 */
function pollCache(id: string) {
  clearTimeout(cachePullTimer)
  cachePullTimer = window.setTimeout(async () => {
    try {
      const r = await fetch(`${apiBase()}/api/cache?id=${encodeURIComponent(id)}`)
      if (!r.ok) return
      const j = (await r.json()) as { state?: string; percent?: number; error?: string }
      cachePercent.value = j.percent || 0
      if (j.state === 'done') {
        cacheState.value = 'done'
        cacheHint.value = '已缓存，断网也能看这一集'
      } else if (j.state === 'failed') {
        cacheState.value = 'failed'
        flashCacheHint(j.error || '下载失败')
      } else {
        pollCache(id)
      }
    } catch {
      /* 单次失败继续等 */
      pollCache(id)
    }
  }, 3000)
}

function onPlayerRetry() {
  const cand = nextLineCandidate.value || lines.value.find((l) => l.name !== flag.value)
  if (cand) switchLine(cand.name)
}

// ---------------------------------------------------------------- 进度

/** 进度落盘。`final` 表示关键节点，允许触发 WebDAV 同步 */
function flushToHistory(final: boolean, info?: { pos: number; dur: number }) {
  if (!vodId) return
  const pos = info?.pos ?? player.value?.getTime() ?? 0
  const dur = info?.dur ?? 0
  if (!Number.isFinite(pos) || pos < 1) return
  // 快看完了就别记了，否则下次进来直接跳到片尾
  if (dur > 0 && pos > dur - 10) {
    clearProgress({ site, vod_id: vodId }, { notify: final })
    return
  }
  // 顺带把元信息补齐，历史页才能显示出片名/封面
  pushHistory(
    {
      site,
      siteName: isLibraryPlay.value ? '片库' : (store.currentSite?.name || site),
      vod_id: vodId,
      vod_name: displayTitle(),
      vod_pic: posterPic(),
      vod_remarks: metaRemarks.value || undefined,
      at: Date.now(),
    },
    { notify: false },
  )
  saveProgress(
    { site, vod_id: vodId },
    { pos, dur, flag: flag.value, ep: epName.value, epUrl: rawId.value },
    { notify: final },
  )
}

/** 历史封面：详情页没传过来，取本地已有的那条 */
/**
 * 从详情补回来的展示元数据（封面 / 正式片名 / 备注）。
 *
 * <p><b>为什么需要</b>：从历史页或搜索页直达播放时，URL 里只有 `name`
 * （可能是空的、也可能就是 vod_id 这种数字），封面完全没有。
 * `ensurePlaylist()` 明明已经拉到了 detail（有 `vod_pic`、`vod_name`），
 * 但原来只取了剧集表就把响应扔了 —— 于是历史里那条记录的海报
 * 永远是空的（实测：10 条历史 10 条 vod_pic 为空）。
 */
const metaPic = ref((route.query.pic as string) || '')
const metaName = ref('')
const metaRemarks = ref('')

/** 写历史时用的片名：详情补回来的优先，它比 URL 里的 name 可靠。 */
function displayTitle(): string {
  return metaName.value || title || ''
}

// ---------------------------------------------------------------- 自上次看的新集数
//
// 与 DetailView 同一套快照（favUpdates.seen）。详情页先打开时范围已经在
// 会话里（consumeNewEpisodes 推过基线），这里只读；从历史页一键续播
// 直接进播放页时才在这里自己算。零额外请求 —— detail 是本来就拉的。

/** 自上次看新增的集数范围；from 是 1 起的序号，选集面板按下标标新 */
const newEps = ref<NewEpisodesRange | null>(null)

/** 正在播的是不是"新出的集"（看新集 = 消费了这次更新提醒） */
function playingNewEp(): boolean {
  const r = newEps.value
  return !!r && currentIndex.value >= r.from - 1
}

/**
 * 对比快照算新增范围。
 *
 * <p>会话里已有（详情页刚算过）就复用；否则自己算 —— 直接从历史页
 * 续播进来的场景根本没经过详情页。
 */
function computeNewEps(currentRemarks: string) {
  const total = lines.value.reduce((m, l) => Math.max(m, l.episodes.length), 0)
  newEps.value =
    sessionNewEpisodes(site, vodId) || consumeNewEpisodes(site, vodId, currentRemarks, total)
}

/** 本次页面生命周期里是否已经消费过（progress 心跳 5s 一次，不能每次都写盘） */
let updateConsumed = false

/**
 * 看到新集 = 消费了更新提醒：基线不动（打开页面时已推过），
 * 只把未读标记（hits）清掉 → 顶栏红点 / 收藏角标灭。
 *
 * <p>★ 只在 metaRemarks 非空时调：detail 挂了 remarks 是空串，
 * 把基线清空会让下次检查把"老更新"再报一遍。
 */
function consumeUpdateIfNew() {
  if (updateConsumed || !newEps.value || !playingNewEp()) return
  updateConsumed = true
  if (metaRemarks.value) markSeen(site, vodId, metaRemarks.value)
}

function posterPic(): string {
  // 详情已补 → 用它（最可靠）；否则读历史里已有的那条
  if (metaPic.value) return metaPic.value
  try {
    const raw = localStorage.getItem('meow.history.list')
    const arr = raw ? JSON.parse(raw) : []
    const hit = Array.isArray(arr)
      ? arr.find((x: any) => x?.site === site && x?.vod_id === vodId)
      : null
    return hit?.vod_pic || ''
  } catch {
    return ''
  }
}

function onProgress(p: { pos: number; dur: number; final: boolean; watched?: number }) {
  notePrefetchProgress(p.pos)
  /*
   * 线路真的播起来了（progress 只在播放推进时发）：给这条线路记一次成功，
   * 下次同站直接排前面、用正常看门狗预算（#16）。
   * 片库没有"线路"概念，跳过。
   */
  if (!lineOkNoted && p.pos > 3 && !isLibraryPlay.value && flag.value) {
    lineOkNoted = true
    noteLineOk(site, flag.value)
    refreshLineWatch()
  }
  // 真的在播新集 → 消费掉这次"有更新"提醒（顶栏红点/收藏角标灭）。
  // 放在 progress 回调而不是起播处：到这里才确定"真的播起来了"，
  // 起播失败/秒退不算看过。
  consumeUpdateIfNew()
  // 见 progressArmed 的注释：路由刚变、新片还没 loaded 时的回调属于旧片，
  // 只丢掉"写历史"这一步，观看时长照样累计（那是真实看掉的时间）
  if (progressArmed) flushToHistory(p.final, p)
  /*
   * 累计观看时长。
   *
   * ★ 不能用 `pos` 的增量（2026-11-02 第三次修）：进度心跳是 **5 秒一次**
   *   （VideoPlayer.onTimeUpdate 里 `>= 5` 才发），所以 `pos` 差恒为 5.0~5.3，
   *   而当时这里的门禁是 `delta <= 5` —— 几乎每条都被当成 seek 丢掉，
   *   统计永远是 0。播放器现在自己按墙钟累计"真正播了多久"（`watched`），
   *   这里只做增量与基准管理。
   *
   * 数值回落 = 播放器换了新实例/换了集（每集重新从 0 计），把基准一起重置。
   */
  if (typeof p.watched === 'number') {
    if (p.watched < lastWatched) lastWatched = 0
    const delta = p.watched - lastWatched
    // 上界 90s：心跳 5s、卸载时补一条，正常不会超；超了说明时钟异常，丢掉
    if (delta > 0 && delta <= 90) addWatchTime(delta)
    lastWatched = p.watched
  } else if (p.pos > 0) {
    // 兼容：没有 watched 字段的旧播放器（理论上不存在，留一层兜底）
    const delta = p.pos - lastTickPos
    if (delta > 0 && delta <= 5) addWatchTime(delta)
    lastTickPos = p.pos
  }
  maybePrefetchNextDanmaku(p.pos, p.dur)
  maybePrefetchNextPlay(p.pos)
}

// ---------------------------------------------------------------- 下集弹幕预取

/** 每集只预取一次（key = 集名），切集后自然换 key */
const prefetchedEp = new Set<string>()
/**
 * 预取提前量（秒）。
 *
 * <p><b>公式</b>（2026-11-02 用户定）：`min(片尾标记, 时长) - 提前量`。
 *
 * <p>为什么这样写而不是"到片尾标记才抓"：
 *   · 用户点「跳过片尾」时会立刻切集（`skipOutroNow` 直接 emit ended），
 *     那一刻才抓根本来不及；
 *   · 很多源压根没有片尾标记（spiderSkip 为空），只有"时长"可用；
 *   · 有标记时用标记（更精确），没标记时退回时长 —— 取 min 保证两者都覆盖。
 *
 * <p>默认 180 秒的依据：danmu_api 首次抓一集实测 7–12 秒、内置源 1–3 秒，
 * 3 分钟给足余量（连最慢的聚合都抓得完），又不会太早。
 * 可在设置页调（policy.json 的 `danmakuPrefetchLead`）。
 */
const prefetchLead = ref(180)
/** 预取开关（设置页可关，policy.json 的 danmakuPrefetch） */
const prefetchOn = ref(true)

// ---------------------------------------------------------------- 下集地址预取

/**
 * 已经预取过的集（key = 集名）。
 *
 * <p>去重而不是"每次都拉"：一次 `timeupdate` 有 4 次/秒，不去重会打爆源站。
 */
const prefetchedPlayEp = new Set<string>()
/**
 * 本集是否已经触发过预取。
 *
 * <p>进度心跳 5 秒一次，播过 30 秒后会一直命中 `maybePrefetchNextPlay`。
 * 靠 `prefetchedPlayEp` 去重也能挡住重复请求，但每 5 秒仍会白白走一遍
 * 下标查找和 JSON 键拼接；更重要的是诊断记录会被刷满。
 * 换集时（route 变化）重置。
 */
let prefetchArmed = false
/**
 * 预取提前量（秒）：播过这么久之后才开始预取下一集地址。
 *
 * <p>为什么等一会儿而不是一进页面就取：
 *   · 起播那 10 秒要抢带宽拉首屏分片，再并发一个 play 请求会拖慢起播；
 *   · 用户可能只看一眼就退，太早取等于白打一次源站（有的源有频率限制）。
 * 30 秒足够覆盖"片头广告/片头曲"，也远早于片尾。
 */
const PLAY_PREFETCH_AFTER_SEC = 30

/**
 * 预取下一集的播放地址（**方案 B**：只解析地址，不预拉分片）。
 *
 * <p>为什么不预拉分片：Web 端两个 `<video>` 的 MediaSource 不共享数据，
 * 预拉的分片用不上；而在投影仪（armeabi-v7a）上开第二个解码器正是
 * 踩过的 ART 崩溃路径。真正耗时的是**地址解析**（detail 0.4s + play 1.9s），
 * 分片本身在源站正常时只要 0.2s —— 预取这一步就够把"转圈"变成"直接播"。
 *
 * <p>预取失败静默：真正的加载路径还会照常请求一次，不会因为预取挂了播不了。
 */
async function prefetchNextPlay() {
  const i = currentIndex.value
  if (i < 0) {
    notePrefetch('skip:no-index')
    return
  }
  if (i + 1 >= playlist.value.length) {
    notePrefetch(`skip:last-ep(${i}/${playlist.value.length})`)
    return
  }
  const next = playlist.value[i + 1]
  if (!next?.url) {
    notePrefetch('skip:no-url')
    return
  }
  /*
   * ★ 键里的地址必须与 `load()` 查表时用的形态**完全一致**（2026-11-03 实测踩过）。
   *
   * `load()` 用 `rawId.value`，它是 `safeDecode(route.query.id)` —— 路由会把
   * 地址里的 `%` 解一次，`safeDecode` 再解一次。而 playlist 里的 `next.url`
   * 是**未解码**的原始串。两边直接拼键就永远不相等，预取白做
   * （实测：`hasPlayPrefetch(key)` 恒 false，一次都没命中）。
   * 所以这里也用 safeDecode 归一，与 load() 同源。
   */
  const epUrl = safeDecode(next.url)
  const key = prefetchKey(site, flag.value, epUrl)
  if (prefetchedPlayEp.has(key)) {
    notePrefetch('skip:dup')
    return
  }
  prefetchedPlayEp.add(key)
  notePrefetch(`start:idx=${i + 1}`)
  try {
    // 预取和真实播放走同一个接口，保证拿到的地址形态完全一致
    const r = await fetchPlay(site, flag.value, epUrl)
    if (!r?.url) {
      notePrefetch('fail:empty')
      return
    }
    /*
     * 需要解析的线路不预取解析结果。
     *
     * `parse=1` 的地址要点第三方解析器，那一步可能要 3–10 秒、且很多解析器
     * 有频率限制；预取期间把配额用掉，用户真点下一集时反而可能失败。
     * 这类线路保持"点到时才解析"。
     */
    if (needsParse(r)) {
      notePrefetch('skip:needs-parse')
      return
    }
    putPlayPrefetch(key, { url: r.url, header: r.header, parse: r.parse })
    notePrefetch(`ok:${Array.isArray(r.url) ? 'arr' : 'str'}`)
  } catch (e) {
    notePrefetch(`fail:${(e as Error)?.message?.slice(0, 40) || 'unknown'}`)
  }
}

/** 播到一定进度后触发下集地址预取（每集只触发一次） */
function maybePrefetchNextPlay(pos: number) {
  // 片库没有 /api/{site}/play 可取，下一集地址就是本地文件，不需要预取
  if (isLibraryPlay.value) return
  if (!Number.isFinite(pos) || pos < PLAY_PREFETCH_AFTER_SEC) return
  // 已触发过就不再进（进度心跳 5 秒一次，否则会把诊断记录刷满）
  if (prefetchArmed) return
  prefetchArmed = true
  notePrefetch(`armed@${pos.toFixed(0)}s`)
  void prefetchNextPlay()
}

function maybePrefetchNextDanmaku(pos: number, dur: number) {
  // 诊断通道（同 __meowDm 口径）：MCP/面板可读，判断"为什么没预取"
  /*
   * ★ 总时长要用"真实时长"：转码中的 HLS 清单只到已转出的边缘，
   *   video.duration 是边缘值（例如 106 秒），拿它算触发点会永远不到，
   *   下一集弹幕预取等于失效（2026-11-23 片库 10-bit/AC3 实报）。
   *   libraryTotalDuration 由转码结果/真实 duration 提供。
   */
  const total = libraryTotalDuration.value > 0 ? libraryTotalDuration.value : dur
  ;(window as unknown as { __meowPrefetch?: unknown }).__meowPrefetch = {
    on: prefetchOn.value,
    lead: prefetchLead.value,
    idx: currentIndex.value,
    len: playlist.value.length,
    pos: Math.round(pos),
    dur: Math.round(dur),
    total: Math.round(total),
    next: playlist.value[currentIndex.value + 1]?.name || '',
  }
  if (!prefetchOn.value) return
  if (!Number.isFinite(pos) || !Number.isFinite(dur) || dur <= 0) return
  const i = currentIndex.value
  if (i < 0 || i + 1 >= playlist.value.length) return
  const next = playlist.value[i + 1]
  if (!next?.name) return
  const key = next.name
  if (prefetchedEp.has(key)) return

  /*
   * triggerAt = min(片尾标记, 时长) - 提前量
   *
   * 片尾标记是"该集开始出片尾字幕的秒数"（源站给），时长是整集长度。
   * 有标记时它一定 ≤ 时长，所以 min 实际取的是标记；没标记时退化成时长。
   */
  const mark = spiderSkip.value.endingStart > 0
    ? spiderSkip.value.endingStart
    : total
  const triggerAt = Math.min(mark, total) - prefetchLead.value
  // 短片（时长 < 提前量）不预取：triggerAt 可能是负数，一进来就抓，没意义
  if (triggerAt <= 0 || pos < triggerAt) return

  prefetchedEp.add(key)
  // fire-and-forget（POST，带副作用；失败静默）。实现见 api.prefetchDanmaku ——
  // 详情页预取第 1 集走同一个函数，避免两处 URL/方法漂移。
  prefetchDanmaku(displayTitle(), next.name, titleYear)
}

/** 读预取配置（policy.json）；失败保持默认，不该挡住播放 */
async function loadPrefetchPolicy() {
  try {
    const p = await fetchPolicy()
    if (typeof p.danmakuPrefetch === 'boolean') prefetchOn.value = p.danmakuPrefetch
    if (typeof p.danmakuPrefetchLead === 'number' && p.danmakuPrefetchLead >= 10) {
      prefetchLead.value = p.danmakuPrefetchLead
    }
  } catch {
    /* 后端未连上：用默认值（开 + 180 秒） */
  }
}
void loadPrefetchPolicy()

/**
 * 跨源兜底命中的那条历史记录（`site` + `vod_id`）。
 *
 * 「从头开始」要把它一起清掉，否则退出再进来又会被同一条记录续播回原位——
 * 表现为"点了从头开始，下次还是跳到 45 分钟"（自查出来的死循环）。
 */
let resumedFromOther: { site: string; vod_id: string } | null = null

/** 恢复上次进度（`loaded` 事件带当前集的真实时长） */
function applyResume(ev?: { duration?: number }) {
  // 新片已 loaded：从这里开始收到的进度才属于当前这一集（见 progressArmed）
  progressArmed = true
  if (resumeApplied) return
  resumeApplied = true
  let it = vodId ? getProgress({ site, vod_id: vodId }, rawId.value, epName.value) : null
  let from = ''
  /*
   * 跨源兜底（2026-11-16）：A 源失效换 B 源后 `site`/`vod_id` 全变，
   * getProgress 按 `site+vod_id` 必然查不到，用户只能重新找集、拖进度。
   *
   * 判据与详情页一致：片名归一化后完全相等（挡掉解说版/粤语版/第一季），
   * 再用**集号**对齐（三个真实源实测集名是 第01集 / 01 [1080P] / 1，
   * 集名比较必然失败）。集号解析不出来或对不上就不兜底。
   */
  if (!it && vodId) {
    const want = episodeNumber(epName.value)
    if (want) {
      for (const h of findHistoryByTitle(title)) {
        if (h.site === site && h.vod_id === vodId) continue
        if (typeof h.pos !== 'number' || h.pos < 1) continue
        if (episodeNumber(h.ep) !== want) continue
        it = { pos: h.pos, dur: h.dur || 0, at: h.ep_at || 0 }
        from = h.siteName || h.site
        resumedFromOther = { site: h.site, vod_id: h.vod_id }
        break
      }
    }
  }
  /*
   * 时长校验要用**当前这集真实的 duration**，不能只看历史里存的 dur：
   * 跨源兜底时 `it.dur` 是 A 源的（48 分钟），B 源这集可能只有 30 分钟，
   * 照 `pos=40min` 跳过去会直接落到片尾（播完自动下一集）。
   */
  const realDur = Number.isFinite(ev?.duration) && (ev?.duration || 0) > 0 ? ev!.duration! : 0
  let target = 0
  let label = ''
  if (
    it &&
    it.pos > 5 &&
    !(it.dur > 0 && it.pos > it.dur - 10) &&
    !(realDur > 0 && it.pos > realDur - 10)
  ) {
    target = it.pos
    label = from ? `继续播放（来自 ${from}）` : '继续播放'
  }
  // 源站给的起播点（毫秒→秒）也参与比较，取较大的那个。
  // 与 Fongmi 的 VodSkipPolicy 一致：片头跳过本质上也是"从某个位置开始"，
  // 两个都想跳时当然该跳到更靠后的那个。
  if (spiderStartAt.value > target) {
    target = spiderStartAt.value
    label = '开始播放'
  }
  if (target <= 0) return
  /*
   * 片库转码还没转完时，续播位置可能落在"还没转出来"的地方 —— 直接跳过去
   * 就是干等一个不存在的分片（表现为起播即卡）。夹到已转出范围的安全边界。
   */
  if (libraryReadyCap.value > 0 && target > libraryReadyCap.value - 2) {
    target = Math.max(0, libraryReadyCap.value - 3)
    label = '转码进度还没到上次位置'
  }
  if (target <= 0) return
  player.value?.seekTo(target)
  resumeTip.value = { at: target, label }
  window.setTimeout(() => (resumeTip.value = null), 8000)
  // TV：把焦点落到提示条上，否则遥控器够不到「从头开始」。
  // 提示条是 bottom-28 的悬浮层，不在底栏区域内，tv-nav 的几何选焦
  // 不会主动走过去；这里直接给类名 + focus，与 tv-nav 用的是同一套标记。
  if (isTvEnv()) {
    window.setTimeout(() => {
      const btn = document.querySelector('[data-resume-bar] button')
      if (!btn) return
      document.querySelectorAll('.meow-tv-focus').forEach((e) => e.classList.remove('meow-tv-focus'))
      btn.classList.add('meow-tv-focus')
      try {
        ;(btn as HTMLElement).focus({ preventScroll: true })
      } catch {
        /* 老内核忽略 */
      }
    }, 120)
  }
}

function restartFromTop() {
  player.value?.restart()
  resumeTip.value = null
  // 同时清掉已存的进度，否则退出再进来还会"续播"
  if (vodId) clearProgress({ site, vod_id: vodId })
  /*
   * 跨源兜底来的那条也要清：否则"从头开始 → 退出 → 再进来"又被它
   * 续播回原来的位置，用户看到的是"从头开始不生效"（死循环）。
   * A 源已经失效，留着这条进度只会反复干扰。
   */
  if (resumedFromOther) {
    clearProgress(resumedFromOther)
    resumedFromOther = null
  }
}

// ---------------------------------------------------------------- 线路切换

/**
 * 在新线路里找"同名的集"。
 *
 * 不同线路的集名一般一致（「第03集」），但地址必然不同（签名/域名各异），
 * 所以只能按名字对；名字对不上时退回同下标，再不行才回第一集。
 *
 * 实测必须做这层映射：换线路回第一集等于把用户看到的第 12 集丢掉，
 * 而且进度也没法接着用（进度是"这一集看到第几秒"）。
 */
function sameEpisodeIn(line: { episodes: Array<{ name: string; url: string }> }) {
  const eps = line.episodes
  if (!eps.length) return null
  if (epName.value) {
    const byName = eps.find((e) => e.name === epName.value)
    if (byName) return byName
  }
  const i = currentIndex.value
  if (i >= 0 && i < eps.length) return eps[i]
  return eps[0]
}

/**
 * 换一条线路，停在当前这一集。
 *
 * `auto` 为 true 表示这是播放失败后的自动换源：不记线路偏好
 * （用户没选，是程序挑的），也不重置已试过的线路记录，只带一个轻提示。
 */
function switchLine(name: string, opts: { auto?: boolean } = {}) {
  if (name === flag.value) return
  const line = lines.value.find((l) => l.name === name)
  const ep = line ? sameEpisodeIn(line) : null
  if (!line || !ep) return
  // 与换集同因：播放器保持挂载，换线期间必须先把旧视频停下来。
  // 端内原生渲染例外（声音在 VLC 那边，pause() 打不到它），交给 open 换片。
  if (!iosNativeLayer.value) player.value?.pause()
  if (!opts.auto) {
    // 手动换线路：用户主动选择，清掉自动换源的失败记录重新计
    try {
      sessionStorage.removeItem(FAILED_KEY)
    } catch {
      /* 忽略 */
    }
    // 记住这次选择，下次同片直接用它（切集/重进详情页/历史续播都生效）
    if (vodId) setLinePref(site, vodId, name)
  }
  // 换线前落盘当前进度。**不能 clearProgress**——上一版在这里清了一次，
  // 紧接着的 flushToHistory 又被它自己抹掉，表现成"换过线路就没了进度"。
  flushToHistory(true)
  router.replace({
    name: 'play',
    params: { site },
    query: {
      flag: name,
      id: ep.url,
      name: title,
      ep: ep.name,
      vod: vodId || undefined,
      list: JSON.stringify(line.episodes),
    },
  })
}

/**
 * 错误页的「换个线路」：直接切到下一条线路。
 *
 * 线路面板已经收进播放器底栏，错误页这里不再弹面板——
 * 页面本身就是"没播起来"的状态，再弹一个列表反而更绕。
 */
function switchToNextLine() {
  const cand =
    nextLineCandidate.value || lines.value.find((l) => l.name !== flag.value)
  if (cand) switchLine(cand.name)
}

// ---------------------------------------------------------------- 生命周期

function onHide() {
  flushToHistory(true)
  /*
   * pagehide：页面真的要走（关 TV 端/刷新/退出 WebView）。用 keepalive 发 DELETE，
   * 否则普通 fetch 会随文档卸载被取消，后端 ffmpeg 继续空转（#15）。
   */
  dropActiveTranscodeOnLeave()
}

/**
 * TV 壳在 Activity 关闭/退后台时调（Java 侧 evaluateJavascript）。
 * 只撤"还在跑"的转码；没有活动任务时是空操作。
 */
function cancelTranscodeFromShell() {
  dropActiveTranscodeOnLeave()
}

/**
 * 错误页一出现就跑诊断。
 *
 * 放在 watch 而不是 onPlayerError 里：错误可能来自三条路径
 * （取地址失败 / 播放失败 / 换线后仍失败），三处各自调一次容易漏；
 * 以 error 变成非空为准，一处覆盖全部。
 */
watch(error, (v) => {
  if (v) {
    // 出错后播放器会整页卸载（错误页），sticky src 必须一起清掉：
    // 否则「重试」会先用失败的那条地址挂载播放器，可能在新的 load()
    // 完成前又触发一次播放错误，把重试链路搅乱。
    src.value = ''
    void runDiagnosis()
  } else {
    diagnosis.value = null
    explainText.value = ''
  }
})

/**
 * hopError 晚到时重跑诊断（2026-11-27）。
 *
 * <p>踩过：错误页的诊断卡说「网络超时 / 换一条线路」，主文案却写着
 * 「当前后端没有 ffmpeg」——两个结论互相矛盾。根因是诊断在 error 落地的
 * 那一刻就跑，而那时 hopError（"我们这一跳"失败的真实原因）还没写进去，
 * 规则层只能按"看门狗到期"倒推。hopError 一到就重跑，结论才对得上。
 */
watch(hopError, (v) => {
  if (v && error.value) void runDiagnosis()
})

watch(
  () => route.fullPath,
  () => {
    // 立即作废旧集的转码等待：ensurePlaylist→load 之间还有网络往返，
    // 不能等 load() 才失效（旧等待可能在这几秒里回写）。
    playEpoch++
    // 同一组件内切集：fullPath 变了说明父级会重建组件，
    // 但这里仍然重新解一次参数，避免复用组件时拿到旧值
    flag.value = (route.query.flag as string) || flag.value
    rawId.value = safeDecode((route.query.id as string) || '')
    epName.value = (route.query.ep as string) || ''
    const l = route.query.list as string | undefined
    if (l) playlist.value = parsePlaylist(l)
    resumeApplied = false
    resumeTip.value = null
    // 新的一集要重新计"播过 30 秒"，否则换集后再也不预取下一集
    prefetchArmed = false
    // 换线/换集：线路成功标记与看门狗预算重算（#16）
    lineOkNoted = false
    refreshLineWatch()
    // 新片的进度回调要等它 loaded 之后才算数（见 progressArmed）
    progressArmed = false
    // 切集后缓存状态要重置，否则上一集的「已缓存」会显示在这一集上
    cacheState.value = ''
    cachePercent.value = 0
    offlinePlay.value = false
    // 换集/换片要重新允许直连：上一集可能因为容器不支持走了转码
    libraryNoDirect.value = false
    // 编码探测/出路提示都是"这一集"的：切集清掉，新一集重新探测
    mediaProbe.value = null
    silentHint.value = ''
    silentHintNative.value = false
    silentHintTranscode.value = false
    clearTimeout(silentHintTimer)
    // 转码机会也按集重置：上一集转过/无声检测触发过，下一集是另一个文件，
    // 音轨编码可能完全不同（AAC 直连有声、AC3 无声），不能沿用旧结论
    transcodeTried = false
    libraryReadyCap.value = 0
    libraryTotalDuration.value = 0
    libraryAhead.value = 0
    libraryStart.value = 0
    stopLibraryPoll()
    // 必须**先**把线路表加载出来，再取播放地址：
    // load() 里失败时要靠 lines 找下一条候选线路，lines 还空着就只能停在错误页。
    // 实测 AppGet（首线路空 url）、Jianpian（首线路 SSL 失败）都因此卡死。
    void ensurePlaylist()
      .then(() => load())
      .then(() => syncCacheState())
  },
  { immediate: true },
)

window.addEventListener('pagehide', onHide)
/*
 * 投屏面板打开时抢占播放器的返回链（2026-11-04 审计补）。
 *
 * ★ 为什么不能在 PlayView 里监听 Escape：播放页的返回键被 VideoPlayer 的
 *   `__meowTvConsumeBack` 独占（App.vue 看到它就直接 return，连自己的
 *   Escape 派发都不跑），所以 PlayView 挂在 document 上的 Escape 永远收不到。
 * ★ 用轮换而不是常驻：面板关掉立刻还原原 handler，否则播放器的
 *   弹幕/全屏/控制栏那套逐层退出就全失效了（CachePanel 同一个模式）。
 */
const castPanelOpen = computed(() => castSearching.value || castDevices.value.length > 0 || !!castMsg.value)
watch(castPanelOpen, (open) => {
  const w = window as unknown as { __meowTvConsumeBack?: (() => boolean) | null }
  if (open) {
    if (w.__meowTvConsumeBack === __castBack) return
    const prev = w.__meowTvConsumeBack
    __castBackPrev = prev ?? null
    __castBack = () => {
      closeCast()
      w.__meowTvConsumeBack = __castBackPrev
      __castBack = null
      return true
    }
    w.__meowTvConsumeBack = __castBack
  } else if (w.__meowTvConsumeBack === __castBack) {
    w.__meowTvConsumeBack = __castBackPrev
    __castBack = null
  }
})
/** 当前抢占用的返回处理（null = 没抢占） */
let __castBack: (() => boolean) | null = null
let __castBackPrev: (() => boolean) | null = null
/*
 * 返回键关投屏面板（2026-11-03）。
 *
 * ★ 为什么是 document 上的 Escape：TV 壳的返回键由 App.vue 统一处理，它只把
 *   Escape 派发给 `[data-tv-scope]` / `[role=dialog]` 里的元素；播放器自己的
 *   返回链在 VideoPlayer 内部，够不到挂在 PlayView 下的面板。
 *   面板加了 data-tv-scope，这里收事件即可。
 */
function onDocEscape(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  if (castSearching.value || castDevices.value.length || castMsg.value) closeCast()
}
document.addEventListener('keydown', onDocEscape)
/*
 * 预取诊断入口（只读）。
 *
 * <p>预取的所有失败分支都是静默的，`playPrefetchSize()` 只能告诉你"没命中"，
 * 分不出是"没触发 / 位置没到 / 下标找不到 / 接口返回空 / 需要解析"。
 * 排查时在控制台读 `__playPrefetch()` 就能看到进度回调次数与最近的事件。
 */
;(window as any).__playPrefetch = prefetchDiag
/*
 * TV 壳关闭页面/退后台时的撤销入口。Java 侧在 onDestroy/onStop 里
 * evaluateJavascript 调它 —— WebView 销毁前 pagehide 不保证触发（#15）。
 */
;(window as any).__meowCancelTranscode = cancelTranscodeFromShell
onBeforeUnmount(() => {
  clearTimeout(silentHintTimer)
  // 离开播放页：还在等的转码没人要了，停掉后端 ffmpeg（别让它空转到看门狗）
  playEpoch++
  transcodeWaiting.value = false
  if (pendingTranscode) {
    void cancelTranscode(pendingTranscode)
    pendingTranscode = null
  }
  // 已经在边转边看的那份也要撤（#15）；SPA 内跳转时普通 DELETE 就够了
  dropActiveTranscodeOnLeave()
  ;(window as any).__meowCancelTranscode = null
  flushToHistory(true)
  cancelCountdown()
  clearTimeout(lineHintTimer)
  clearTimeout(cachePullTimer)
  clearTimeout(cacheHintTimer)
  clearTimeout(castToastTimer)
  window.removeEventListener('pagehide', onHide)
  document.removeEventListener('keydown', onDocEscape)
  // 投屏面板若还占着返回链，卸载前必须还原，否则下一页会继承这个 handler
  const w0 = window as unknown as { __meowTvConsumeBack?: (() => boolean) | null }
  if (__castBack && w0.__meowTvConsumeBack === __castBack) w0.__meowTvConsumeBack = __castBackPrev
  ;(window as any).__playPrefetch = null
})
</script>

<template>
  <div class="fixed inset-0 bg-black">
    <!--
      整页加载态只在「还没有任何可播地址」时出现（首次进页面 / 出错后重试）。
      换集/换线时 src 保留上一集地址、播放器继续挂载，加载提示改成播放器里的
      浮层（见 #overlay）—— 整页替换会卸载 <video>，PC 上直接丢掉窗口全屏。
    -->
    <div v-if="loading && !src && !iosNativeLayer" class="grid h-full place-items-center">
      <div class="text-center">
        <div class="mx-auto h-10 w-10 animate-spin rounded-full border-2 border-white/20 border-t-accent" />
        <p class="mt-4 text-sm text-ink-3">{{ loadingText }}</p>
        <button
          v-if="transcodeWaiting"
          :data-tv-first="transcodeWaiting ? '' : null"
          class="mt-5 rounded-full border border-white/25 px-4 py-2 text-sm text-ink-2 transition hover:border-white/40 hover:text-ink"
          @click="cancelTranscodeWait"
        >
          停止等待并取消转码
        </button>
      </div>
    </div>

    <!-- 拿到地址但不是 http，且还没有可播地址（首次进页面）：需要平台专用解析器 -->
    <div v-else-if="needsParser && !src" class="grid h-full place-items-center px-6">
      <div class="max-w-md text-center">
        <div class="text-4xl opacity-40">🔐</div>
        <p class="mt-4 text-sm text-ink-2">该线路返回的是加密串，需要专用解析器</p>
        <p class="mt-2 text-xs text-ink-3">
          源站返回的不是直链（形如 <code>Ksvideo-…</code>），Web 端无法直接播放。
        </p>
        <!-- 多画质：只在失败页出现。绝大多数源只有单地址，常驻选择器是噪音 -->
        <div v-if="qualityList.length > 1" class="mt-4">
          <div class="text-[11px] text-ink-3">换个画质试试</div>
          <div class="mt-2 flex flex-wrap items-center justify-center gap-2">
            <button
              v-for="(q, i) in qualityList"
              :key="q.url"
              class="rounded-md px-3 py-1.5 text-xs transition"
              :class="
                i === currentQuality
                  ? 'bg-white/8 text-ink-3'
                  : 'bg-white/12 text-ink hover:bg-white/20'
              "
              :disabled="i === currentQuality"
              @click="switchQuality(q.url!)"
            >
              {{ q.name || `画质 ${i + 1}` }}{{ i === currentQuality ? '（当前）' : '' }}
            </button>
          </div>
        </div>

        <div class="mt-5 flex flex-wrap items-center justify-center gap-2">
          <button
            v-if="lines.length > 1"
            class="rounded-md bg-accent px-4 py-2 text-sm font-medium text-on-accent hover:bg-accent-dim"
            @click="switchToNextLine"
          >
            换个线路
          </button>
          <RouterLink
            to="/"
            class="inline-block rounded-md bg-white/10 px-4 py-2 text-sm transition hover:bg-white/16"
          >
            回首页
          </RouterLink>
        </div>
      </div>
    </div>

    <div v-else-if="error && !iosNativeLayer" class="grid h-full place-items-center px-6">
      <div class="max-w-md text-center">
        <div class="text-4xl opacity-40">🎬</div>
        <p class="mt-4 text-sm text-ink-2">{{ errorMainText }}</p>
        <!--
          我们这一跳（转码/代理）的失败原因单独一行。
          不能合并进上面那句：真实原因往往只是网络抖动，而转码失败
          会把它盖成"找不到 ffmpeg"，用户看到的报错跟影片毫无关系。
          ★ 2026-11-27：命中"本端没有转码能力"时这一行换成可执行的下一步
          （errorSubText），原始技术原因只进诊断证据。
        -->
        <p v-if="errorSubText" class="mt-1.5 text-xs text-ink-3">（{{ errorSubText }}）</p>
        <!-- 只有"拿不到地址"才给这句；播放中途失败是另一回事 -->
        <p v-if="isFetchError" class="mt-2 text-xs text-ink-3">
          提示：这条线路没能取到播放地址（源站可能临时不可用），换个线路或换个源试试
        </p>
        <p v-else-if="allLinesFailed" class="mt-2 text-xs text-ink-3">
          已尝试 {{ lines.length }} 条线路均失败，通常是源站整体不可用
        </p>

        <!--
          诊断结论（docs/47 §2.7）。主入口就是这里，不是悬浮按钮。
          ★ 未配置模型时 explanation 为 null —— 只少这一句，UI 不做任何区分。
        -->
        <div
          v-if="diagnosis && (diagnosis.cause || diagnosis.suggestion.action !== 'none')"
          class="mt-4 rounded-lg border border-white/10 bg-white/[0.04] px-3.5 py-3 text-left"
        >
          <div class="flex flex-wrap items-center gap-2 text-[11px] text-ink-3">
            <span class="rounded bg-accent/15 px-1.5 py-0.5 font-medium text-accent">
              {{ diagnosis.cause ? CAUSE_LABEL[diagnosis.cause.code] || diagnosis.cause.code : '未发现故障特征' }}
            </span>
            <span v-if="diagnosis.cause">置信度 {{ diagnosis.cause.confidence }}</span>
            <span v-if="explainBusy" class="opacity-60">AI 正在解释…</span>
          </div>
          <p class="mt-1.5 text-xs leading-relaxed text-ink-2">
            {{ explainText || diagnosis.suggestion.text }}
          </p>
          <button
            v-if="diagnosis.suggestion.action !== 'none' && diagnosis.suggestion.action !== 'refresh_danmaku'"
            class="mt-2 rounded bg-white/10 px-2.5 py-1 text-[11px] text-ink-2 transition hover:bg-white/16 hover:text-ink"
            @click="applySuggestion"
          >
            按建议处理
          </button>
        </div>
        <div class="mt-5 flex flex-wrap items-center justify-center gap-2">
          <!-- 电视上才有：WebView 解不了这条流时，用 ExoPlayer 再试一次同一地址 -->
          <button
            v-if="canUseNative"
            class="rounded-md bg-accent px-4 py-2 text-sm font-medium text-on-accent hover:bg-accent-dim"
            @click="tryNative"
          >
            用原生播放器打开
          </button>
          <button
            v-if="lines.length > 1"
            class="rounded-md bg-accent px-4 py-2 text-sm font-medium text-on-accent hover:bg-accent-dim"
            @click="switchToNextLine"
          >
            换个线路
          </button>
          <button
            class="rounded-md bg-white/10 px-4 py-2 text-sm transition hover:bg-white/16"
            @click="retryFromError"
          >
            重试
          </button>
          <button
            class="rounded-md bg-white/8 px-4 py-2 text-sm text-ink-2 transition hover:bg-white/14"
            @click="router.back()"
          >
            返回
          </button>
        </div>
      </div>
    </div>

    <!--
      iOS 原生渲染模式（2026-11-27）：画面由端内 VLCKit 出，这里渲染的是
      **同一个 VideoPlayer**（只是不加载 src、状态来自原生推送）——
      控制条/浮层/弹幕密度条全部复用同一套模板与样式，不再另写一个"长得像"的。
    -->
    <VideoPlayer
      v-else-if="iosNativeLayer || src"
      ref="player"
      :src="iosNativeLayer ? '' : src"
      :total-duration="libraryTotalDuration"
      :ahead-seconds="libraryAhead"
      :ahead-start-seconds="libraryStart"
      :auto-play="!castSession"
      :danmaku-url="danmakuUrl"
      :title="`${title}${epName ? ' · ' + epName : ''}`"
      :episodes="playlist"
      :current-index="currentIndex"
      :new-from-ep="newEps ? newEps.from : 0"
      :lines="lines"
      :line-name="flag"
      :can-switch-line="lines.length > 1"
      :native-available="canUseNative"
      :startup-timeout-ms="lineWatch.startup"
      :stall-timeout-ms="lineWatch.stall"
      :auto-retry-hint="autoRetryHint"
      :site="site"
      :vod-id="vodId"
      :media-info="mediaInfo"
      :seek-at="refreshSeekAt"
      :spider-intro="spiderSkip.openingEnd"
      :spider-outro-at="spiderSkip.endingStart"
      :middles="spiderSkip.middles"
      :subs="playSubs"
      :dm-block-words="dmBlockWords"
      :no-audio-fallback="isLibraryPlay"
      @no-audio="onNoAudio(true)"
      @high-drop="onHighDrop"
      :can-download="canDownloadCurrent"
      :can-download-all="canDownloadAll"
      :can-cast="canCastCurrent"
      :casting="!!castSession"
      :cache-state="cacheState"
      :cache-percent="cachePercent"
      @loaded="applyResume"
      @seek-applied="refreshSeekAt = 0"
      @seek-beyond="onSeekBeyond"
      @stale-src="onStaleSrc"
      @ended="onEnded"
      @progress="onProgress"
      @error="onPlayerError"
      @retry="onPlayerRetry"
      @line="switchLine"
      @native-play="tryNative"
      @switch="switchEpisode"
      @download="downloadCurrent"
      @download-all="downloadAll"
      @cast="openCast"
    >
      <!--
        #overlay（2026-11-03）：连播倒计时与投屏面板必须画在播放器子树里。
        真全屏只渲染全屏元素及其后代，挂在外面（原来的位置）会被整个藏掉，
        TV 假全屏下也会被播放器的 z-[100] 压住 —— 表现就是"倒计时点不到"。
      -->
      <template #overlay>
        <!--
          换集/换线/解析中的加载浮层。

          ★ 必须放在播放器子树里：真全屏只渲染全屏元素及其后代，浮层挂在
            PlayView 下（播放器的兄弟节点）会被整个藏掉 —— 表现就是切集后
            画面还停在上一集、没有任何提示。
          ★ 不能用整页 v-if 替换播放器：那会卸载全屏元素，PC(WebView2)
            立刻退出窗口全屏，重建后只能恢复伪全屏（标题栏回不来）。
        -->
        <div
          v-if="loading"
          class="pointer-events-auto absolute inset-0 grid place-items-center bg-black/70 backdrop-blur-sm"
        >
          <div class="text-center">
            <div class="mx-auto h-10 w-10 animate-spin rounded-full border-2 border-white/20 border-t-accent" />
            <p class="mt-4 text-sm text-ink-3">{{ loadingText }}</p>
            <button
              v-if="transcodeWaiting"
              :data-tv-first="transcodeWaiting ? '' : null"
              class="mt-5 rounded-full border border-white/25 px-4 py-2 text-sm text-ink-2 transition hover:border-white/40 hover:text-ink"
              @click="cancelTranscodeWait"
            >
              停止等待并取消转码
            </button>
            <!--
              O2（2026-11-21）：弱后端转不动 AC3/DTS 时用户不该干等。
              TV 壳有原生播放桥（设备 Dolby 解码、零转码），等待中随时可走。
            -->
            <button
              v-if="transcodeWaiting && canUseNative"
              class="mt-2.5 rounded-full border border-accent/60 px-4 py-2 text-sm text-accent transition hover:bg-accent/10"
              @click="cancelTranscodeWait(); tryNative()"
            >
              用原生播放
            </button>
          </div>
        </div>

        <!-- 投屏中：遥控条（进度 / 暂停 / 停止）。离开播放页不停止投屏。 -->
        <div
          v-if="castSession"
          class="pointer-events-auto absolute left-3 top-3 z-40 w-[min(22rem,calc(100%-1.5rem))] rounded-xl border border-white/10 bg-black/80 px-3 py-2 text-white shadow-2xl backdrop-blur-xl"
        >
          <div class="flex items-center gap-2 text-xs">
            <span class="min-w-0 flex-1 truncate">
              正在「{{ castSession.device.name }}」上播放
            </span>
            <span class="shrink-0 text-white/60">
              {{ fmtSec(castPosition) }} / {{ fmtSec(castDuration) }}
            </span>
          </div>
          <div class="mt-2 flex items-center gap-2">
            <button
              class="rounded-md bg-white/10 px-2 py-1 text-[11px] transition hover:bg-white/20"
              @click="castToggle"
            >
              {{ castState === 'playing' ? '暂停' : '继续' }}
            </button>
            <input
              class="h-1 min-w-0 flex-1 cursor-pointer"
              style="accent-color: var(--color-accent)"
              type="range"
              min="0"
              :max="castSeekMax"
              :value="Math.round(castPosition)"
              @change="onCastSeek"
            />
            <button
              class="rounded-md bg-white/10 px-2 py-1 text-[11px] transition hover:bg-white/20"
              @click="stopCast()"
            >
              停止
            </button>
          </div>
          <p v-if="castLost" class="mt-1 text-[11px] text-hot">
            电视已断开
            <button class="ml-1 underline" @click="stopCast(false)">关闭</button>
          </p>
          <p v-else-if="castStall" class="mt-1 text-[11px] text-hot">
            这台设备没有开始播放（可能只接受它自家 App 投屏）。换一台电视试试。
          </p>
        </div>

        <!-- 连播倒计时：播完到点自动下一集，可取消 -->
        <div
          v-if="countdown"
          class="pointer-events-auto absolute bottom-24 left-1/2 flex -translate-x-1/2 items-center gap-3 rounded-xl border border-white/10 bg-black/88 px-4 py-3 text-ink shadow-2xl backdrop-blur-xl"
        >
          <div class="text-sm">
            <span class="text-ink-3">{{ countdown.left }} 秒后播放</span>
            <span class="ml-1 font-medium">{{ nextEpName }}</span>
          </div>
          <button
            class="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-on-accent transition hover:bg-accent-dim"
            @click="playNextNow"
          >
            立即播放
          </button>
          <button
            class="rounded-md bg-white/12 px-3 py-1.5 text-xs text-ink-2 transition hover:bg-white/20 hover:text-ink"
            @click="cancelCountdown"
          >
            取消
          </button>
        </div>

        <!-- 投屏面板：设备列表 + 状态。宽屏（非 TV）锚到触发按钮，手机/TV 居中 -->
        <div
          v-if="castSearching || castDevices.length || castMsg"
          data-tv-scope
          class="pointer-events-auto absolute z-[135] flex max-h-[calc(100dvh-2rem)] w-[22rem] max-w-[calc(100%-2rem)] flex-col rounded-xl border border-white/10 bg-black/92 p-4 text-ink shadow-2xl backdrop-blur-xl"
          :class="castPanelAnchored ? '' : 'left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2'"
          :style="castPanelStyle"
        >
          <div class="mb-2 flex shrink-0 items-center gap-2">
            <span class="flex-1 text-sm font-medium">投屏到电视</span>
            <button class="rounded px-2 py-0.5 text-xs text-ink-3 hover:bg-white/10" @click="closeCast">
              关闭
            </button>
          </div>
          <div class="min-h-0 flex-1 overflow-y-auto pr-1">
            <p v-if="castSearching" class="py-4 text-center text-xs text-ink-3">正在搜索局域网设备…</p>
            <div v-else-if="castGroups.length" class="space-y-2">
              <div v-for="g in castGroups" :key="g.kind">
                <p class="px-2 pb-1 text-[10px] text-ink-3">{{ g.title }}</p>
                <div class="space-y-1">
                  <button
                    v-for="(d, i) in g.items"
                    :key="d.controlUrl"
                    class="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm transition hover:bg-white/10 disabled:opacity-50"
                    :disabled="castBusy"
                    :data-tv-first="g === castGroups[0] && i === 0 ? true : undefined"
                    @click="castTo(d)"
                  >
                    <svg class="h-4 w-4 shrink-0 text-accent" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <rect x="2" y="6" width="20" height="12" rx="2" />
                      <path d="M8 20h8" />
                    </svg>
                    <span class="min-w-0 flex-1 truncate">{{ d.name }}</span>
                    <span
                      v-if="d.kind === 'meow'"
                      class="shrink-0 rounded bg-accent/20 px-1 py-0.5 text-[10px] text-accent"
                    >
                      猫窝
                    </span>
                    <span class="text-[11px] text-ink-3">{{ d.host }}</span>
                  </button>
                </div>
              </div>
            </div>
            <p v-if="castMsg" class="mt-2 text-xs" :class="castMsg.startsWith('已投') ? 'text-ok' : 'text-hot'">
              {{ castMsg }}
            </p>
          </div>
          <p v-if="!castSearching" class="mt-2 shrink-0 text-[11px] leading-relaxed text-ink-3">
            电视需支持 DLNA 投屏。片源会经后端代理转发，避免电视直连源站被 Referer 校验拦掉。
            打开这个面板后本机也会开启接收（可被别的设备投到）；Windows 首次可能弹防火墙，请允许。
          </p>
        </div>

        <!-- 投屏成功提示：画在 overlay 里，全屏也看得见；面板随后自动收起 -->
        <div
          v-if="castToast"
          class="pointer-events-none absolute left-1/2 top-4 z-[140] -translate-x-1/2 rounded-full bg-black/85 px-4 py-1.5 text-xs text-ink shadow-2xl backdrop-blur"
        >
          {{ castToast }}
        </div>
      </template>
    </VideoPlayer>

    <!--
      端内原生播放器已退出（2026-11-27）。
      父页面此时没有 src、也没有 error —— 不画这块会落到"纯黑屏、没有任何入口"
      （真机实报：从原生返回后只能强退）。三个出口：继续用原生（同一集）、
      下一集、返回上一页。
    -->
    <div
      v-if="nativeExited"
      class="absolute inset-0 z-[140] grid place-items-center bg-black/85 px-6"
    >
      <div class="max-w-sm text-center">
        <p class="text-sm text-ink-2">已退出端内播放器</p>
        <p class="mt-1.5 text-xs text-ink-3">原生解码页已关闭，可以从这里继续。</p>
        <div class="mt-5 flex flex-wrap items-center justify-center gap-2">
          <button
            class="rounded-md bg-accent px-4 py-2 text-sm font-medium text-on-accent transition hover:bg-accent-dim"
            @click="reopenNative"
          >
            继续用端内播放器
          </button>
          <button
            v-if="currentIndex >= 0 && currentIndex < playlist.length - 1"
            class="rounded-md bg-white/12 px-4 py-2 text-sm text-ink-2 transition hover:bg-white/20"
            @click="nativePlayNext"
          >
            下一集
          </button>
          <button
            class="rounded-md bg-white/8 px-4 py-2 text-sm text-ink-2 transition hover:bg-white/14"
            @click="router.back()"
          >
            返回
          </button>
        </div>
      </div>
    </div>


    <!-- 离线播放：明确告诉用户"这不是源站，是本机缓存"，否则会误以为在线的 -->
    <div
      v-if="offlinePlay"
      class="absolute left-1/2 top-16 z-40 flex -translate-x-1/2 items-center gap-1.5 rounded-full bg-ok/20 px-4 py-1.5 text-xs text-ok shadow-2xl backdrop-blur"
    >
      <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
        <path d="M20 6 9 17l-5-5" />
      </svg>
      离线播放（本机缓存）
    </div>

    <!-- 缓存进度/结果提示 -->
    <div
      v-if="cacheHint"
      class="absolute bottom-28 left-1/2 z-40 -translate-x-1/2 rounded-full bg-black/85 px-4 py-1.5 text-xs text-ink shadow-2xl backdrop-blur"
    >
      {{ cacheHint }}
    </div>

    <!-- 自动换源的轻提示 -->
    <div
      v-if="lineHint"
      class="absolute bottom-28 left-1/2 z-40 -translate-x-1/2 rounded-full bg-black/85 px-4 py-1.5 text-xs text-ink shadow-2xl backdrop-blur"
    >
      {{ lineHint }}
    </div>

    <!-- 转码提示：用户要知道"为什么画面晚了几秒才出来"以及源文件没被动过 -->
    <div
      v-if="transcodeHint"
      class="absolute bottom-28 left-1/2 z-40 -translate-x-1/2 rounded-full bg-black/85 px-4 py-1.5 text-xs text-ink shadow-2xl backdrop-blur"
    >
      {{ transcodeHint }}
    </div>

    <!--
      「音频零解码」出路提示（2026-11-21）：AC3/DTS 音轨在 WebView 里
      静默无声，这条条给用户两条真实出路 —— TV 用原生播放（设备解码），
      其它端等自动转码。带按钮时不自动消失（用户要看见它）。
    -->
    <div
      v-if="silentHint"
      data-silent-hint
      class="absolute bottom-28 left-1/2 z-40 flex max-w-[94%] -translate-x-1/2 items-center gap-3 whitespace-nowrap rounded-xl border border-white/10 bg-black/88 px-4 py-2.5 text-sm text-ink shadow-2xl backdrop-blur-xl"
    >
      <span class="min-w-0 text-ink-2">{{ silentHint }}</span>
      <button
        v-if="silentHintNative"
        class="shrink-0 rounded-md bg-accent px-3 py-1.5 text-xs font-medium whitespace-nowrap text-on-accent transition hover:bg-accent-dim"
        data-tv-first
        @click="useNativeFromHint"
      >
        用原生播放
      </button>
      <button
        v-if="silentHintTranscode"
        class="shrink-0 rounded-md bg-accent/15 px-3 py-1.5 text-xs font-medium whitespace-nowrap text-accent transition hover:bg-accent/25"
        data-tv-first
        @click="useTranscodeFromHint"
      >
        转码播放
      </button>
      <button
        class="shrink-0 text-xs text-ink-3 transition hover:text-ink"
        @click="silentHint = ''; silentHintNative = false; silentHintTranscode = false"
      >
        ✕
      </button>
    </div>

    <!--
      两台设备抢 4K 硬转（2026-11-23）：后端只放行一路，第二路会回 hw_busy。
      直接丢错误页等于逼用户换片；这里给"排队等"和"降 1080p"两个真实选择。
    -->
    <div
      v-if="busyHint"
      data-busy-hint
      class="absolute bottom-28 left-1/2 z-40 flex max-w-[94%] -translate-x-1/2 items-center gap-3 whitespace-nowrap rounded-xl border border-white/10 bg-black/88 px-4 py-2.5 text-sm text-ink shadow-2xl backdrop-blur-xl"
    >
      <span class="min-w-0 text-ink-2">
        {{ busyRetrying
          ? `排队中：另一台设备正在转 4K（已重试 ${busyRetries} 次，每 15 秒一次）`
          : '另一台设备正在转 4K' }}
      </span>
      <button
        v-if="!busyRetrying"
        class="shrink-0 rounded-md bg-accent px-3 py-1.5 text-xs font-medium whitespace-nowrap text-on-accent transition hover:bg-accent-dim"
        data-tv-first
        @click="startBusyQueue"
      >
        排队重试
      </button>
      <button
        v-else
        class="shrink-0 rounded-md bg-white/10 px-3 py-1.5 text-xs font-medium whitespace-nowrap text-ink-2 transition hover:bg-white/20"
        @click="cancelBusyQueue"
      >
        取消排队
      </button>
      <button
        v-if="lowerLine"
        class="shrink-0 rounded-md bg-accent/15 px-3 py-1.5 text-xs font-medium whitespace-nowrap text-accent transition hover:bg-accent/25"
        data-tv-first
        @click="switchToLowerLine"
      >
        换 {{ lowerLine }}
      </button>
      <button class="shrink-0 text-xs text-ink-3 transition hover:text-ink" @click="cancelBusyQueue">
        ✕
      </button>
    </div>

    <!--
      续播提示 / 起播选择。
      ★ 不加 data-tv-scope：那会把整个焦点范围收窄到这条提示里，
        控制栏就选不到了。改为 applyResume 后主动把焦点落到「从头开始」上
        （见 applyResume 里的 focusResumeBar），用户想改就按 ok，
        不想要就按方向键离开——提示 8 秒后自己消失。
    -->
    <div
      v-if="resumeTip"
      data-resume-bar
      class="absolute bottom-28 left-1/2 z-40 flex -translate-x-1/2 items-center gap-3 rounded-full bg-black/85 px-4 py-2 text-xs text-ink meow-on-dark shadow-2xl backdrop-blur"
    >
      <span>已{{ resumeTip.label === '开始播放' ? '跳到' : '从' }} {{ Math.floor(resumeTip.at / 60) }}:{{
        String(Math.floor(resumeTip.at % 60)).padStart(2, '0')
      }} {{ resumeTip.label || '继续播放' }}</span>
      <button class="rounded bg-white/15 px-2 py-0.5 hover:bg-white/25" @click="restartFromTop">
        从头开始
      </button>
      <button class="text-ink-3 hover:text-ink" @click="resumeTip = null">✕</button>
    </div>
  </div>
</template>
