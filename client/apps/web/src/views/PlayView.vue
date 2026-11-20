<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import VideoPlayer from '@/components/player/VideoPlayer.vue'
import {
  apiBase,
  castBase,
  fetchDetail,
  fetchParse,
  fetchPlay,
  fetchPolicy,
  parsePlayLines,
  prefetchDanmaku,
  proxyDanmaku,
  proxyStream,
  sanitizeCastHeader,
} from '@/api'
import {
  cacheIdFor,
  cachedPlaylist,
  startTranscode,
  transcodeAvailable,
  waitLibraryTranscode,
  waitTranscode,
} from '@/api/library'
import { skipWindowFrom, startAtFrom, subTracksFrom } from '@/api/playExtras'
import { ensureLibraryTicket, ensureProxyTicket } from '@/api/mediaTicket'
import { authRequired } from '@/stores/auth'
import { hasNativePlayer, openNative } from '@/api/nativeBridge'
import { meowDiagnose } from '@/ai/tools'
import { explainDiagnosis } from '@/ai/llm'
import { CAUSE_LABEL, type Diagnosis } from '@/ai/diagnose'
import { isPlayErrorPayload, shouldTranscode, type PlayErrorPayload } from '@/components/player/playError'
import type { PlayResult } from '@/api/types'
import { getPrefs } from '@/stores/prefs'
import { isTvEnv, isTvShell, isWideLayout } from '@/compat/shell'
import { isAndroid, isFirefox } from '@/compat/ua'
import { getLinePref, setLinePref } from '@/stores/linePref'
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
/** 「已转码播放」提示：用户要知道画面为什么晚了几秒才出 */
const transcodeHint = ref('')

/** 上一次进度回调的位置，用来算"播放器实际推进了多少秒" */
let lastTickPos = 0
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
const title = (route.query.name as string) || ''
const epName = ref((route.query.ep as string) || '')
/** 影片 id：从历史页直达播放时没有 list，靠它把剧集表补回来 */
const vodId = (route.query.vod as string) || ''

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
      watchdogFired: !!error.value && errorSource.value === 'play',
      parse: play.value?.parse,
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
/** 本次成功解析的解析器名（来自 /api/parse 的 from） */
const parseFrom = ref('')

/** 续播提示 */
const resumeTip = ref<{ at: number; label?: string } | null>(null)
let resumeApplied = false

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
      const j = (await r.json()) as { state?: string; readySeconds?: number; duration?: number }
      if (j.readySeconds) {
        libraryAhead.value = j.readySeconds
        libraryReadyCap.value = j.readySeconds
      }
      if (j.duration) libraryTotalDuration.value = j.duration
      if (j.state === 'ready' || j.state === 'failed' || j.state === 'cancelled') stopLibraryPoll()
    } catch {
      /* 单次失败继续 */
    }
  }, 5000)
}

const playableSrc = computed(() => {
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
  if (!url || !/^https?:/i.test(url)) return ''
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
  return proxyDanmaku(title, epName.value || '1')
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
  if (!vodId) return
  // 没显式指定线路时，优先用用户上次在这部片上选过的（找不到名字就退回第一条）
  const want = flag.value || getLinePref(site, vodId)
  // 同一部片在本次会话内只拉一次 detail
  if (linesCache) {
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
    lines.value = parsePlayLines(v.vod_play_from || '', v.vod_play_url || '')
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
  loading.value = true
  error.value = ''
  playError.value = ''
  hopError.value = ''
  errorSource.value = 'fetch'
  loadingText.value = '正在获取播放地址…'
  // 媒体票：鉴权开启时 <video>/hls.js 的地址必须带 ?mt=。
  // 未开鉴权的部署不请求（端点会 401，纯噪音）。
  if (authRequired.value) {
    if (isLibraryPlay.value && libraryLib.value) await ensureLibraryTicket(libraryLib.value)
    else await ensureProxyTicket()
  }
  // 换线路/换集都是新的尝试，允许原生再兜一次
  nativeTried = false
  // 片库直链：没有 spider 的 play 可调，地址已经在 query 里。
  // 直接构造一个只有 url 的 PlayResult 交给播放器（它有 hls.js + 原生两条路）。
  if (isLibraryPlay.value) {
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
    play.value = { url: libraryDirect.value } as PlayResult
    loading.value = false
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
  // 源站签名地址会变（从历史回来时基本对不上），按集名再试一次
  const byUrl = playlist.value.findIndex((x) => x.url === rawId.value)
  if (byUrl >= 0) return byUrl
  if (!epName.value) return -1
  return playlist.value.findIndex((x) => x.name === epName.value)
})

/** 切到指定集（上一集 / 下一集 / 选集面板共用） */
function switchEpisode(i: number) {
  lastTickPos = 0
  lastWatched = 0
  const target = playlist.value[i]
  if (!target) return
  cancelCountdown()
  // 换集时播放器不再卸载（见 src 的注释），旧视频不会自己停。
  // 先暂停：加载新地址的 0.4–2 秒里不能继续出声/继续走时间
  // （进度写入已由 progressArmed 挡住，但声音挡不住）。
  player.value?.pause()
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
/** 后端有没有 ffmpeg；null = 还没探测过 */
const transcodeOk = ref<boolean | null>(null)

async function ensureTranscodeProbe(): Promise<boolean> {
  if (transcodeOk.value !== null) return transcodeOk.value
  try {
    transcodeOk.value = await transcodeAvailable()
  } catch {
    transcodeOk.value = false
  }
  return transcodeOk.value
}

/** 播放器报错 */
/** 当前环境能用原生播放器（仅 TV 壳） */
const canUseNative = computed(() => {
  const raw = play.value?.url
  const u = typeof raw === 'string' ? raw : ''
  return hasNativePlayer() && !!u && /^https?:/i.test(u)
})

/** 当前应从哪里开始播：有续播记录就用它，否则从头 */
function resumePos(): number {
  if (!vodId) return 0
  const it = getProgress({ site, vod_id: vodId }, rawId.value, epName.value)
  return it && Number.isFinite(it.pos) ? it.pos : 0
}

/** 错误页的「用原生播放器打开」：把当前地址交给 ExoPlayer 再试一次 */
function tryNative() {
  const raw = play.value?.url
  const u = typeof raw === 'string' ? raw : ''
  if (!u) return
  if (openNative(u, title, play.value?.header, resumePos())) {
    error.value = ''
    autoRetryHint.value = '已改用原生播放器（ExoPlayer）'
  } else {
    autoRetryHint.value = '原生播放器拉起失败'
  }
}

/**
 * 让后端把这个源现转成 HLS（H.264）。
 *
 * 只对"浏览器解不了"这种失败有意义：换线路解决不了编码问题，
 * 因为同一集在不同源上编码基本一样。转码产物落在后端缓存目录，
 * **源文件不动**。
 *
 * 已转好的分片是增量的：前几个分片就绪即可起播，不用等整片转完。
 */
async function tryTranscode(src: string) {
  errorSource.value = 'play'
  hopError.value = ''
  loadingText.value = '正在准备播放（这个格式需要转码，已转部分可边看边转）…'
  error.value = ''
  try {
    // 首次响应只代表"任务已排上"，要等分片落盘到能起播
    const r = await waitTranscode(src, (sec) => {
      loadingText.value = `正在转码（已就绪 ${sec} 秒，可边转边看）…`
    })
    /*
     * 转码失败**不能覆盖播放器给的原因**。
     *
     * 原来这里直接 `error.value = r.error`，于是 TV 上（网关没有 ffmpeg）
     * 一次网络抖动换来的是"找不到 ffmpeg。装一个才能播这个格式"——用户
     * 看到的是一个跟影片无关的报错，真实原因被盖掉了。
     * 现在：主文案保留播放器的原因，转码失败只作为第二行。
     */
    if (!r.ok || r.state === 'failed' || !r.playlist) {
      hopError.value = !r.playlist
        ? '这个格式浏览器不支持，转码也没能产出可播内容'
        : (r.error || '转码失败')
      error.value = playError.value || '播放失败'
      loadingText.value = ''
      return
    }
    libraryReadyCap.value = r.readySeconds || 0
    libraryAhead.value = r.readySeconds || 0
    libraryTotalDuration.value = r.duration || 0
    if (r.duration && r.readySeconds && r.readySeconds < r.duration - 5) {
      pollTranscodeProgress({ src })
    }
    transcodeHint.value = '已转码播放（源文件未改动）'
    clearTimeout(lineHintTimer)
    lineHintTimer = window.setTimeout(() => (transcodeHint.value = ''), 8000)
    // 播放器直接吃后端给的清单地址；hls.js 会按需拉分片
    play.value = { url: absoluteApi(r.playlist) } as PlayResult
  } catch (e) {
    hopError.value = e instanceof Error ? e.message : String(e)
    error.value = playError.value || '播放失败'
    loadingText.value = ''
  }
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
async function tryLibraryTranscode(lib: string, file: string) {
  errorSource.value = 'play'
  // 从这一刻起不再把 query.direct 交给播放器（否则 <video> 会一直重试直连，
  // 转码好了也播不到；见 playableSrc 里的注释）
  libraryNoDirect.value = true
  loadingText.value = '直连播不了，正在启动转码（可边转边看）…'
  error.value = ''
  try {
    const r = await waitLibraryTranscode(lib, file, (sec) => {
      loadingText.value = `正在转码（已就绪 ${sec} 秒，可边转边看）…`
    })
    if (r.state === 'failed' || !r.playlist) {
      const why = r.error || '转码失败'
      // 「找不到 ffmpeg」是 TV 本地网关的常态（PC 插件 / Docker 镜像里才有）。
      // 光说"找不到 ffmpeg"用户不知道怎么办，补一句可执行的建议。
      error.value = /ffmpeg/i.test(why)
        ? `${why}\n\n这台设备上的后端没有 ffmpeg：可以在设置里改用 PC / OECT 后端播放，或点下面「用原生播放器打开」试试 ExoPlayer。`
        : why
      // 收掉整页加载遮罩：否则 error 分支会被「loading && !src」遮住，
      // 用户只看到一直转圈（F5 预判路径进这里时 loading 还是 true）
      loading.value = false
      return
    }
    libraryReadyCap.value = r.readySeconds || 0
    libraryAhead.value = r.readySeconds || 0
    libraryTotalDuration.value = r.duration || 0
    if (r.duration && r.readySeconds && r.readySeconds < r.duration - 5) {
      pollTranscodeProgress({ lib, path: file })
    }
    transcodeHint.value = '已转码播放（源文件未改动）'
    clearTimeout(lineHintTimer)
    lineHintTimer = window.setTimeout(() => (transcodeHint.value = ''), 8000)
    play.value = { url: absoluteApi(r.playlist) } as PlayResult
    loading.value = false
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    loading.value = false
  }
}

async function onPlayerError(payload: PlayErrorPayload | string) {
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
  playError.value = msg
  hopError.value = ''

  // 浏览器解不了的格式（MKV 容器 / HEVC / DTS 音轨）换线路也没用：
  // 同一集在不同源上编码基本一样。这里让后端现转 HLS 兜一次——
  // 实测 11 倍速、源文件不动，H.265 4K 的 MKV 在浏览器里能播且能拖。
  //
  // ★ 片库播放走另一条兜底（2026-11-02）：直链失败时按 lib+path 让后端
  //   自己读文件转码。片库没有"换线路"的概念，转码是唯一出路。
  if (!transcodeTried && isLibraryPlay.value) {
    transcodeTried = true
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

/** 不能投时给菜单一个禁用原因（不再直接把入口藏掉） */
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
      siteName: store.currentSite?.name || site,
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
const metaPic = ref('')
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
  if (!Number.isFinite(pos) || pos < PLAY_PREFETCH_AFTER_SEC) return
  // 已触发过就不再进（进度心跳 5 秒一次，否则会把诊断记录刷满）
  if (prefetchArmed) return
  prefetchArmed = true
  notePrefetch(`armed@${pos.toFixed(0)}s`)
  void prefetchNextPlay()
}

function maybePrefetchNextDanmaku(pos: number, dur: number) {
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
    : dur
  const triggerAt = Math.min(mark, dur) - prefetchLead.value
  // 短片（时长 < 提前量）不预取：triggerAt 可能是负数，一进来就抓，没意义
  if (triggerAt <= 0 || pos < triggerAt) return

  prefetchedEp.add(key)
  // fire-and-forget（POST，带副作用；失败静默）。实现见 api.prefetchDanmaku ——
  // 详情页预取第 1 集走同一个函数，避免两处 URL/方法漂移。
  prefetchDanmaku(displayTitle(), next.name)
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
  player.value?.pause()
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

watch(
  () => route.fullPath,
  () => {
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
    // 新片的进度回调要等它 loaded 之后才算数（见 progressArmed）
    progressArmed = false
    // 切集后缓存状态要重置，否则上一集的「已缓存」会显示在这一集上
    cacheState.value = ''
    cachePercent.value = 0
    offlinePlay.value = false
    // 换集/换片要重新允许直连：上一集可能因为容器不支持走了转码
    libraryNoDirect.value = false
    libraryReadyCap.value = 0
    libraryTotalDuration.value = 0
    libraryAhead.value = 0
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
onBeforeUnmount(() => {
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
    <div v-if="loading && !src" class="grid h-full place-items-center">
      <div class="text-center">
        <div class="mx-auto h-10 w-10 animate-spin rounded-full border-2 border-white/20 border-t-accent" />
        <p class="mt-4 text-sm text-ink-3">{{ loadingText }}</p>
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

    <div v-else-if="error" class="grid h-full place-items-center px-6">
      <div class="max-w-md text-center">
        <div class="text-4xl opacity-40">🎬</div>
        <p class="mt-4 text-sm text-ink-2">{{ error }}</p>
        <!--
          我们这一跳（转码/代理）的失败原因单独一行。
          不能合并进上面那句：真实原因往往只是网络抖动，而转码失败
          会把它盖成"找不到 ffmpeg"，用户看到的报错跟影片毫无关系。
        -->
        <p v-if="hopError" class="mt-1.5 text-xs text-ink-3">（{{ hopError }}）</p>
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

    <VideoPlayer
      v-else-if="src"
      ref="player"
      :src="src"
      :total-duration="libraryTotalDuration"
      :ahead-seconds="libraryAhead"
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
      :auto-retry-hint="autoRetryHint"
      :site="site"
      :vod-id="vodId"
      :spider-intro="spiderSkip.openingEnd"
      :spider-outro-at="spiderSkip.endingStart"
      :middles="spiderSkip.middles"
      :subs="playSubs"
      :dm-block-words="dmBlockWords"
      :can-download="canDownloadCurrent"
      :can-download-all="canDownloadAll"
      :can-cast="canCastCurrent"
      :cast-reason="castReason"
      :casting="!!castSession"
      :cache-state="cacheState"
      :cache-percent="cachePercent"
      @loaded="applyResume"
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
      class="absolute left-1/2 top-24 z-40 -translate-x-1/2 rounded-full bg-black/85 px-4 py-1.5 text-xs text-ink shadow-2xl backdrop-blur"
    >
      {{ cacheHint }}
    </div>

    <!-- 自动换源的轻提示 -->
    <div
      v-if="lineHint"
      class="absolute left-1/2 top-16 z-40 -translate-x-1/2 rounded-full bg-black/85 px-4 py-1.5 text-xs text-ink shadow-2xl backdrop-blur"
    >
      {{ lineHint }}
    </div>

    <!-- 转码提示：用户要知道"为什么画面晚了几秒才出来"以及源文件没被动过 -->
    <div
      v-if="transcodeHint"
      class="absolute left-1/2 top-24 z-40 -translate-x-1/2 rounded-full bg-black/85 px-4 py-1.5 text-xs text-ink shadow-2xl backdrop-blur"
    >
      {{ transcodeHint }}
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
