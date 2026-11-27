<script setup lang="ts">
import { isBigScreenLandscape, isLandscapeViewport, showPlayerBackButton } from '@/compat/deviceForm'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Hls from 'hls.js'
import { DanmakuRenderer, parseDanmakuXml, type Danmu } from './danmaku'
import { getLocalDanmaku, putLocalDanmaku } from './danmakuStore'
import RateSelect from './RateSelect.vue'
import {
  setDanmakuDensity,
  setDanmakuTuning,
  getPrefs,
  savePrefs,
  type Prefs,
} from '@/stores/prefs'
import { getSkipMarks, setSkipMark, type SkipMarks } from '@/stores/skipMarks'
import { isTvEnv } from '@/composables/useCardCols'
import { isIosPhone } from '@/compat/ua'
import { hasFinePointer, isFnosAppWebView, isIosShell, isTvShell } from '@/compat/shell'
import { cleanDmTitle, epBadgeOf } from '@/utils/dmTitle'
import { seekOutsideProduced } from '@/utils/transcodeSeek'
import { panelMediaLine, type MediaSourceInfo } from '@/utils/mediaInfo'
import { reportPlayerEvent } from '@/api/playerEvents'
import { assToVtt, srtToVtt, subtitleFormat, type MiddleSegment, type SubTrack } from '@/api/playExtras'
import {
  desktopQuit,
  hasSystemVolume,
  iosNativeCmd,
  isIosNativeLayer,
  readIosNativeState,
  systemSleep,
  systemBrightness,
  systemVolume,
  setSystemBrightness,
  setSystemVolume,
  restoreSystemBrightness,
  setIosNavGesture,
  setIosVolumeHud,
  iosPowerTimer,
  tvCancelPowerTimer,
  tvPowerTimerState,
  tvProbeRoot,
  tvStartPowerTimer,
} from '@/api/nativeBridge'
import {
  POWER_ACTION_LABEL,
  POWER_TIMER_MINUTES,
  currentDefaultPowerAction,
  powerActionsFor,
  resolvePowerCaps,
  type PowerAction,
} from '@/utils/powerActions'
import { installPowerBridge, uninstallPowerBridge } from '@/utils/powerBridge'
import {
  proxyDanmakuByUrl,
  proxyStream,
  searchDanmaku,
  tauriInvoke,
  type DanmakuCandidate,
  type DanmakuSourceGroup,
} from '@/api'
import {
  formatDmOffset,
  getDmOffset,
  normalizeDmOffset,
  resetDmOffset,
  setDmOffset,
} from '@/stores/dmOffset'
import { HEAT_RGB, danmakuHeatData, danmakuHeatGradientFromCounts, danmakuHeatPath } from './dmHeat'
import { dmQualityStats, dmQualityWarn } from './dmQuality'
import { isDesktopShell } from '@/api/appUpdate'
import { beginWindowDrag, toggleMaximizeOnDblClick } from '@/composables/useWindowDrag'
import { createIdleCursor } from './idleCursor'
import type { PlayErrorKind, PlayErrorPayload } from './playError'
import { hasNativeHls, looksLikeHls } from './mediaKind'
import {
  dmPlatformOf,
  freshDanmakuUrl,
  isBetterDanmaku,
  isSafeDanmakuSwap,
  pickSameSourceCandidate,
  shouldSkipDanmakuBody,
} from './dmUpgrade'
import {
  capLevelForRate,
  hlsBufferProfile,
  shouldDropRate,
  shouldRestoreRate,
} from './playbackPolicy'

/** 倍速选项 */
const rateOptions = [
  { value: 0.5, label: '0.5x' },
  { value: 0.75, label: '0.75x' },
  { value: 1, label: '1x' },
  { value: 1.25, label: '1.25x' },
  { value: 1.5, label: '1.5x' },
  { value: 2, label: '2x' },
  { value: 2.5, label: '2.5x' },
  { value: 3, label: '3x' },
]

/**
 * 端内原生渲染模式（2026-11-27）。
 *
 * <p>画面由 iOS 原生 VLCKit 出，本组件只当**控制层**：不加载 src、状态来自
 * 原生推送（每 200ms）、操作转发给原生。
 *
 * <p>为什么不另写一个"长得像"的控制层：那等于两套 UI，样式永远差一点、改一
 * 处要做两遍。判断走全局标记（壳注入的 `__meowNativeLayer`），所以父组件不
 * 需要传参，普通播放路径一行都不受影响。
 */
const iosNative = isIosNativeLayer()
/** 端内原生模式的轮询句柄（200ms 拉一次原生推送的状态） */
let iosNativePoll = 0

const props = withDefaults(defineProps<{
  src: string
  /**
   * 真实总时长（秒）。只在「片库转码还没转完」时传：
   * 此时 HLS 清单只有已转出的分片，`video.duration` 只等于已转出时长
   * （12 秒），进度条总长会随转码一点点变长。传了它，进度条一次显示对的。
   * 0/不传 = 用 video.duration。
   */
  totalDuration?: number
  /** 已转出的秒数（可拖动上界）。0/不传 = 不限制。 */
  aheadSeconds?: number
  /**
   * 当前清单真实内容的起点（秒）。起点重转时前段是黑场填充，
   * 拖到它之前要重新起转，而不是让用户看黑屏。
   */
  aheadStartSeconds?: number
  poster?: string
  danmakuUrl?: string
  title?: string
  /**
   * 元数据就绪后是否自动开始播放（默认 **开**）。
   *
   * 进详情页 / 切集 / 连播的场景用户就是要看，不该还要按一次 ok。
   *
   * ★ 必须用 withDefaults 显式给 true：
   *   纯类型 `defineProps<{ autoPlay?: boolean }>()` 会编译出
   *   `{ type: Boolean }`，而 Vue 对 Boolean prop 的**缺省值是 false**
   *   （不是 undefined）。实测因此一直走跳过分支，play() 从不执行。
   */
  autoPlay?: boolean
  /**
   * 接收页专用：有声自动播放被浏览器策略拦下时，允许静音起播并提示「开启声音」。
   *
   * <p>普通播放页**不要开**：那里用户是主动进来的，被策略拦下时应停在
   * 播放按钮，而不是悄悄静音播放。
   */
  mutedFallback?: boolean
  /** 同线路剧集列表，用于上一集/下一集/选集 */
  episodes?: Array<{ name: string; url: string }>
  /** 当前集在 episodes 里的下标，-1 表示未知 */
  currentIndex?: number
  /**
   * "自上次看"新增集的起始序号（1 起，0 = 没有）。
   * 选集面板里下标 ≥ 它的格子带「新」红点。数据来自 favUpdates 快照对比，
   * 播放页算好传进来（播放器自己不知道详情页/收藏这些上下文）。
   */
  newFromEp?: number
  /** 所有线路（含剧集），用于播放页内直接换源 */
  lines?: Array<{ name: string; episodes: Array<{ name: string; url: string }> }>
  /** 当前线路名 */
  lineName?: string
  /** 是否有其他线路可换（决定错误页是否显示「换线路」） */
  canSwitchLine?: boolean
  /** 当前环境能用原生播放器（TV 壳才有）：决定控制栏是否显示「原生」按钮 */
  nativeAvailable?: boolean
  /** 父组件正在进行自动换源时显示的提示，例如「正在尝试线路 2/3」 */
  autoRetryHint?: string
  /**
   * 站点与影片 id：片头/片尾标记按这两个字段存取（见 stores/skipMarks）。
   * 缺省时不显示标记按钮，行为退回设置页的全局默认秒数。
   */
  site?: string
  vodId?: string
  /**
   * 当前源的编码信息（后端转码结果 / 原生播放器回传）。
   * 直连时为空 —— 面板改用清单里的 CODECS。
   */
  mediaInfo?: MediaSourceInfo | null
  /**
   * 外部要求起播后跳到的位置（秒）。用于"token 过期 → 父组件换新地址"，
   * 应用后 emit('seek-applied') 让父组件清零，避免下次换集又跳。
   */
  seekAt?: number
  /**
   * 源站给的片头结束秒数 / 片尾开始秒数（已由 `api/playExtras.skipWindowFrom`
   * 从毫秒换算好）。手动画的标记优先于它，它又优先于设置页的全局默认值。
   */
  spiderIntro?: number
  spiderOutroAt?: number
  /** 源站外挂字幕；浏览器只吃 WebVTT，SRT 由组件里转换一次 */
  subs?: SubTrack[]
  /** 源站给的中插广告区间（秒）；仅在 prefs.skipMiddle 打开时才生效 */
  middles?: MiddleSegment[]
  /** 这一集能不能下载到本机（片库直链、需解析器的源都不能） */
  canDownload?: boolean
  /** 本集缓存状态，决定「更多」菜单里那一项显示什么文案 */
  cacheState?: '' | 'running' | 'done' | 'failed'
  /** 能不能批量下载整季（需要剧集表 + 每集都能拿到地址） */
  canDownloadAll?: boolean
  /** 能不能投屏（片库直链/需解析器的源不行）；false 时整颗入口不渲染 */
  canCast?: boolean
  /** 当前是否正在投屏（右上角图标高亮用） */
  casting?: boolean
  /** 下载进度百分比 */
  cachePercent?: number
  /**
   * 弹幕屏蔽词（逗号/换行分隔，命中即丢）。
   *
   * <p>放在 props 而不是组件内读 prefs：屏蔽词是用户在设置页配的，
   * 播放器不该自己去 localStorage 抓——那会让"设置改了但正在播的这集不生效"。
   */
  dmBlockWords?: string
  /**
   * 直连源允许做「音频零解码」检测并在命中时 emit no-audio。
   *
   * <p>只给片库直连开：点播走 hls.js（MSE），音频是 AAC；片库 mkv 在
   * Android 上先直连，AC3/DTS 音轨会"有画面没声音"（2026-11-21 实报）。
   * 父组件收到 no-audio 后落转码兜底（见 PlayView.onNoAudio）。
   */
  noAudioFallback?: boolean
  /**
   * 起播看门狗预算（毫秒）。0/缺省 = 20s。
   *
   * <p>父组件按线路健康传短值（#16）：从没播成功过的线路用 10s，
   * 死链不用等满 20 秒；稳定成功的线路传 0 用默认值。
   */
  startupTimeoutMs?: number
  /** 假死看门狗预算（毫秒）。0/缺省 = 12s（同上，未验证线路传 8s）。 */
  stallTimeoutMs?: number
}>(),
  // ★ autoPlay 必须显式给 true：Boolean prop 的类型默认值是 false，
  //   不写这一行的话 `props.autoPlay !== false` 永远不成立（实测踩过）。
  { autoPlay: true, mutedFallback: false },
)

const emit = defineEmits<{
  (e: 'ended'): void
  (e: 'error', payload: PlayErrorPayload): void
  /**
   * 用户在错误页点了「换线路」，或在底栏线路面板里选了另一条线路。
   * 传线路名，由父组件负责真正的切换（涉及路由和剧集表重建）。
   */
  (e: 'retry', kind: 'line'): void
  /** 切线路：传目标线路名 */
  (e: 'line', name: string): void
  /** 用户点了控制栏「原生」按钮：由父组件调 openNative（有当前集 header/进度上下文） */
  (e: 'nativePlay'): void
  /** 切集：传目标集的下标 */
  (e: 'switch', index: number): void
  /**
   * 播放进度。`final` 表示这是关键节点（暂停/切集/卸载），
   * 消费方可据此决定是否触发跨端同步。
   * `watched` 是本集到目前为止**真正看了多少秒**（播放器内按墙钟累计，
   * 不含暂停/seek/换集），消费方据此记观看时长。
   */
  (e: 'progress', payload: { pos: number; dur: number; final: boolean; watched: number }): void
  /**
   * 高丢帧（2026-11-24）：某些 4K 源的切片时间轴有缝，Chromium 渲染器
   * 只能靠丢帧追赶媒体时钟。父组件据此切"原画转封装"。
   */
  (e: 'high-drop', payload: {
    dropped: number
    total: number
    ratio: number
    /** hls.js 当前选中的变体地址（可能是空：单码率清单） */
    levelUrl?: string
    /** 当前画面高度（判"转封装后不能更低"） */
    height?: number
  }): void
  /** 元数据就绪（可以安全 seek 了）。续播恢复挂在这里 */
  (e: 'loaded', payload: { duration: number }): void
  /** 分片中途 403/401（token 过期）：父组件应重新取 play 地址并在原位置续播 */
  (e: 'stale-src', payload: { at: number; http: number }): void
  /** seekAt 已应用，父组件可清零 */
  (e: 'seek-applied'): void
  /** 用户点了「下载到本机」，真正抓分片由父组件负责 */
  (e: 'download'): void
  /** 用户点了「下载整季」 */
  (e: 'download-all'): void
  /** 用户点了「投屏」；anchor 是触发按钮在播放器内的左偏移（宽屏面板锚定用） */
  (e: 'cast', anchor?: { left: number }): void
  /** 有声自动播放被浏览器拦下，已自动改静音起播（接收页据此提示「开启声音」） */
  (e: 'autoplay-muted'): void
  /**
   * 直连源起播后音频解码字节数持续为 0：内核没有这条音轨的解码器
   * （AC3/E-AC3/DTS…），画面正常但永远无声。父组件据此落转码兜底。
   */
  (e: 'no-audio'): void
  /**
   * 用户 seek 到「还没转出来」的位置（issue #17）。
   *
   * <p>播放器自己不能处理：清单里没有对应分片。父组件据此让后端从目标点
   * 重转（start=N），再把新清单换进来并 seek 到同一位置。
   */
  (e: 'seek-beyond', sec: number): void
}>()

/** 是否可切集（至少两集且知道当前位置） */
const canSwitch = computed(
  () => (props.episodes?.length ?? 0) > 1 && (props.currentIndex ?? -1) >= 0,
)
const hasPrev = computed(() => canSwitch.value && (props.currentIndex ?? 0) > 0)
const hasNext = computed(
  () => canSwitch.value && (props.currentIndex ?? 0) < (props.episodes?.length ?? 0) - 1,
)

function goPrev() {
  if (hasPrev.value) emit('switch', (props.currentIndex ?? 0) - 1)
}
function goNext() {
  if (hasNext.value) emit('switch', (props.currentIndex ?? 0) + 1)
}
function gotoEp(i: number) {
  flushProgress(true)
  emit('switch', i)
  showEpPanel.value = false
}

/** 下标 i（0 起）是不是"自上次看新增"的集（newFromEp 是 1 起的集号） */
function isNewEpIdx(i: number): boolean {
  const from = props.newFromEp ?? 0
  return from > 0 && i >= from - 1
}

/**
 * 选集格子的配色：当前集 > 普通集。
 *
 * <p>★ 新集**不改数字颜色**（2026-11-06 用户定调：数字不带色、角标带色）——
 * 一排里只有几集变红时反而像"坏了"；靠右上角的小点标记，安静且扫一眼就能看到。
 */
function epCellClass(i: number): string {
  if (i === props.currentIndex) return 'bg-player/25 font-medium text-player'
  return 'bg-white/8 text-ink-2 hover:bg-white/16 hover:text-ink'
}

/** 选集面板 */
const showEpPanel = ref(false)
/** 线路面板 */
const showLinePanel = ref(false)
/** 弹幕设置面板 */
const showDmPanel = ref(false)
/** 二级菜单（低频项：重播/刷新/弹幕搜索/字幕/画中画） */
const showMore = ref(false)
/** 快捷键说明面板。一堆快捷键在界面上没有任何入口，等于只对开发者可见 */
const showHelp = ref(false)
/**
 * 倒计时电源动作（关机 / 休眠 / 退出应用 / 仅暂停）。
 *
 * 方案见 site/docs/dev/power-actions.md。三个要点：
 *  - 能不能做走能力探测（resolvePowerCaps），默认项走身份 + 形态；
 *  - TV 的计时放原生层（WebView 退后台 setInterval 会被节流），JS 只轮询显示；
 *  - 关机失败（无 root）在选动作时当场降级为退出应用（到点没人点授权框）。
 */
const sleepAt = ref(0)
let sleepTimer: number | undefined
const sleepMenu = ref(false)
/** 分钟选项容器：展开后滚进可视区（2026-11-26 用户：需要自己上滑才看得到） */
const sleepMinEl = ref<HTMLElement | null>(null)

function toggleSleepMenu() {
  sleepMenu.value = !sleepMenu.value
  if (!sleepMenu.value) return
  void nextTick(() => {
    try {
      sleepMinEl.value?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    } catch {
      /* 老内核不支持 options 就退化成瞬时 */
      sleepMinEl.value?.scrollIntoView()
    }
  })
}

/** 剩余分钟（用于菜单里显示"还剩 X 分钟"） */
const sleepLeft = ref(0)

/** 当前端能做到的动作与预选项 */
const powerOptions = powerActionsFor(resolvePowerCaps())
/**
 * 预选动作必须落在 powerOptions 里（2026-11-26 真机：iOS 偶尔显示"倒计时 xx 退出"）。
 *
 * <p>`currentDefaultPowerAction()` 按形态给手机/平板选 `quit`，但 iOS 壳能力表里
 * quit=false —— 菜单只有「仅暂停」，预选值却是退出应用。用户不点动作直接点分钟，
 * 就带着 quit 走：浮层写"将在 N 分钟后退出应用"，到点 desktopQuit 是空操作，
 * 实际只暂停。
 */
const powerAction = ref<PowerAction>(
  powerOptions.includes(currentDefaultPowerAction()) ? currentDefaultPowerAction() : 'pause',
)

/** 本端到点执行（PC / Web；TV 由原生层回调 onPowerFire） */
function firePowerLocally(action: PowerAction) {
  const v = video.value
  if (v && !v.paused) v.pause()
  if (action === 'sleep') {
    systemSleep().then((ok) => {
      if (!ok) flashMarks('已暂停播放（系统睡眠不可用）')
    })
  } else if (action === 'quit') {
    void desktopQuit()
  } else {
    flashMarks('已暂停播放')
  }
}

/** TV 原生定时器到点回推（Java 侧的 evalJs 调这两个全局函数） */
function onPowerFire(action: string) {
  // 端内原生渲染：声音/画面都在 VLC 里，`<video>.pause()` 停不了它
  if (iosNative) {
    iosNativeCmd('pause')
    flashMarks(action === 'pause' ? '定时到，已暂停播放' : '定时到')
    return
  }
  const v = video.value
  if (v && !v.paused) v.pause()
  flashMarks(action === 'pause' ? '定时到，已暂停播放' : '定时到')
}

function onPowerResult(_action: string, result: { ok?: boolean; reason?: string } | null) {
  if (result && result.ok === false) {
    flashMarks(`关机失败：${result.reason || '未知原因'}`)
  }
}

// 原生层定时器回推走模块级桥：全局函数只装一次、跨页面常驻。
// 组件卸载只注销处理器，不删全局函数——否则到点 pause 回推没人接。
installPowerBridge(onPowerFire, onPowerResult)

/** 设置定时（分钟）。0 = 关闭；否则到点执行 action。 */
function setPower(action: PowerAction, minutes: number) {
  clearInterval(sleepTimer)
  sleepTimer = undefined
  // 重复设置时以新值为准：TV 的原生定时器也要清
  if (isTvShell()) tvCancelPowerTimer()
  if (minutes <= 0) {
    sleepAt.value = 0
    sleepLeft.value = 0
    // iOS：原生定时器也要撤
    if (isIosShell()) iosPowerTimer(0)
    flashMarks('已取消定时')
    return
  }

  let act = action
  // 能力表里没有的动作一律降级为"仅暂停"（见 powerAction 初始化处的真机说明）
  if (!powerOptions.includes(act)) act = 'pause'

  // TV：原生层计时。关机先探测 root，失败当场降级。
  if (isTvShell()) {
    if (act === 'shutdown') {
      const probe = tvProbeRoot()
      if (!probe.ok) {
        const why = probe.reason === 'NO_ROOT' ? '未检测到 root' : '未获得 root 授权'
        flashMarks(`${why}，本次改为退出应用`)
        act = 'quit'
      }
    }
    if (!tvStartPowerTimer(minutes * 60_000, act)) {
      flashMarks('定时器启动失败')
      return
    }
    powerAction.value = act
    sleepAt.value = Date.now() + minutes * 60_000
    sleepLeft.value = minutes
    // 只用于显示剩余时间；到点由原生层执行，节流不影响结果
    sleepTimer = window.setInterval(() => {
      const st = tvPowerTimerState()
      if (!st.active) {
        clearInterval(sleepTimer)
        sleepTimer = undefined
        sleepAt.value = 0
        sleepLeft.value = 0
        return
      }
      sleepLeft.value = Math.max(0, Math.ceil(st.remainingMs / 60_000))
    }, 5_000)
    flashMarks(`将在 ${minutes} 分钟后${POWER_ACTION_LABEL[act]}`)
    return
  }

  // iOS：原生计时（JS setInterval 切后台会被挂起，到点不会响）
  if (isIosShell()) {
    powerAction.value = act
    sleepAt.value = Date.now() + minutes * 60_000
    sleepLeft.value = minutes
    iosPowerTimer(minutes * 60)
    // 只用于刷新"还剩 X 分钟"；到点由原生 eval __meowPowerFire（暂停 + 释放音频）
    sleepTimer = window.setInterval(() => {
      const left = Math.round((sleepAt.value - Date.now()) / 60_000)
      sleepLeft.value = Math.max(0, left)
      if (Date.now() >= sleepAt.value) {
        clearInterval(sleepTimer)
        sleepTimer = undefined
        sleepAt.value = 0
        sleepLeft.value = 0
      }
    }, 10_000)
    flashMarks(`将在 ${minutes} 分钟后${POWER_ACTION_LABEL[act]}`)
    return
  }

  // PC / Web：前端计时
  powerAction.value = act
  sleepAt.value = Date.now() + minutes * 60_000
  sleepLeft.value = minutes
  sleepTimer = window.setInterval(() => {
    const left = Math.round((sleepAt.value - Date.now()) / 60_000)
    sleepLeft.value = Math.max(0, left)
    if (Date.now() >= sleepAt.value) {
      clearInterval(sleepTimer)
      sleepTimer = undefined
      sleepAt.value = 0
      sleepLeft.value = 0
      firePowerLocally(act)
    }
  }, 10_000)
  flashMarks(`将在 ${minutes} 分钟后${POWER_ACTION_LABEL[act]}`)
}

/**
 * TV：回播放页时从原生层恢复定时器显示（原生层是权威，跨页面仍在倒计时）。
 * 恢复后重启一个只用于刷剩余分钟的轮询；到点由原生层执行，不靠它。
 */
function restorePowerTimerDisplay() {
  const st = tvPowerTimerState()
  if (!st.active) {
    clearInterval(sleepTimer)
    sleepTimer = undefined
    sleepAt.value = 0
    sleepLeft.value = 0
    return
  }
  const a = (['shutdown', 'sleep', 'quit', 'pause'] as const).find((x) => x === st.action)
  powerAction.value = a || 'pause'
  sleepAt.value = Date.now() + st.remainingMs
  sleepLeft.value = Math.max(0, Math.ceil(st.remainingMs / 60_000))
  clearInterval(sleepTimer)
  sleepTimer = window.setInterval(() => {
    const cur = tvPowerTimerState()
    if (!cur.active) {
      clearInterval(sleepTimer)
      sleepTimer = undefined
      sleepAt.value = 0
      sleepLeft.value = 0
      return
    }
    sleepLeft.value = Math.max(0, Math.ceil(cur.remainingMs / 60_000))
  }, 5_000)
}

/** 可用于切换的线路（至少两条才显示入口） */
const lineList = computed(() => props.lines || [])
const canPickLine = computed(() => lineList.value.length > 1)

/** 点某条线路 */
function pickLine(name: string) {
  if (name === props.lineName) {
    openPanel(null)
    return
  }
  openPanel(null)
  emit('line', name)
}

/** 设置页的偏好，改了立刻生效 */
const prefs = ref<Prefs>(getPrefs())
function onPrefs(e: Event) {
  prefs.value = (e as CustomEvent<Prefs>).detail || getPrefs()
}

/** 画面缩放：contain 留黑边 / cover 裁边铺满 / fill 拉伸 */
const fitClass = computed(() => {
  const m = { contain: 'object-contain', cover: 'object-cover', fill: 'object-fill' }
  return m[prefs.value.videoFit] || 'object-contain'
})

const wrap = ref<HTMLDivElement>()
const video = ref<HTMLVideoElement>()
const stage = ref<HTMLDivElement>()

const playing = ref(false)

/**
 * 暂停浮层的显示延迟。
 *
 * 触屏单击画面是即时 toggle 的（见 onStageClick），双击时第一下会短暂暂停、
 * 第二下再恢复。浮层若立刻出现，双击全屏就会闪一下播放按钮。
 * 等 240ms 再显示：双击间隔通常小于这个值，图标根本来不及出现。
 */
const showPauseOverlay = ref(true)
let pauseOverlayTimer: number | undefined
watch(playing, (now) => {
  clearTimeout(pauseOverlayTimer)
  if (now) {
    showPauseOverlay.value = false
    return
  }
  // TV 上没有鼠标可以"点一下看状态"，暂停后画面就是静止的一帧，
  // 用户分不清"卡住了"还是"暂停了"。所以电视上不等 240ms 防抖，立刻显示。
  if (isTvEnv()) {
    showPauseOverlay.value = true
    return
  }
  pauseOverlayTimer = window.setTimeout(() => {
    if (!playing.value) showPauseOverlay.value = true
  }, 240)
})

const currentTime = ref(0)
const duration = ref(0)
/**
 * 进度条/时间显示该用的总长。
 *
 * <p>片库转码还没转完时 `video.duration` 只是"已转出多少"（用户看到 0:03 / 0:12，
 * 而整集 22 分钟）。有 totalDuration 且明显大于当前清单时长就用它。
 */
const shownDur = computed(() => {
  const t = props.totalDuration || 0
  return t > 0 && t > duration.value + 3 ? t : duration.value
})
const buffered = ref(0)
const volume = ref(getPrefs().volume ?? 1)
const muted = ref(getPrefs().muted ?? false)
const dmOn = ref(getPrefs().danmaku)
const dmCount = ref(0)
/** 原始解析条数（含超出片长的部分）——「可用/共 N」里的 N */
const dmTotal = ref(0)
/** 时间轴热度：每桶条数 / 峰值 / 桶宽（秒）；null=不渲染 */
const dmHeat = ref<number[] | null>(null)
const dmHeatRef = ref(1)
const dmHeatBucketSec = ref(0)
/**
 * 弹幕质量统计（播放信息面板用）：基于**过滤后可用列表**，
 * 与「弹幕 N 条」同口径；不能用 dmRawList（含超片长死数据，数字会对不上）。
 */
const dmQuality = ref<ReturnType<typeof dmQualityStats> | null>(null)
/** 后端是否因上限截断过（X-Danmaku-Total > XML 条数），显示时给 N 加 + */
const dmTruncated = ref(false)
/**
 * 本轮弹幕的原始列表。
 *
 * <p>弹幕可能**先于 metadata** 到达（此时 duration=0 没法过滤），
 * 等片长就绪（onLoaded/durationchange）要按片长重新过滤一次；
 * 原始列表留着才能重算，不能只存过滤后的。
 */
let dmRawList: Danmu[] = []
/** 上次应用片长时用的 duration（避免每次 durationchange 都重建渲染列表） */
let dmAppliedDur = -1
const dmLoading = ref(false)
/**
 * 这一集弹幕的来源（`iqiyi` / `tencent` / `danmu_api` / `aggregator`）。
 *
 * 显示它是为了回答用户最常问的那个问题：「这集弹幕怎么这么少」——
 * 是源本来就没弹幕，还是抓取失败了？空态还有 `dmEmpty` 兜底提示。
 */
const dmSource = ref('')
/** 所有源都没拿到弹幕（后端 X-Danmaku-Empty=1） */
const dmEmpty = ref(false)
/**
 * 重试过一次仍然空 —— 这一集**确实没有弹幕**（不是抓取失败）。
 *
 * <p>与 `dmEmpty` 分开是因为两者对用户的意义不同：
 * `dmEmpty` 是"失败，30 秒后再试"，`dmNoData` 是"没有，别等了"。
 * 混在一起就会出现「弹幕加载失败（重试中…）」永远挂着（2026-11-27 修）。
 */
const dmNoData = ref(false)
let dmRetryTimer: number | undefined
let dmRetried = false
const showCtrl = ref(true)
/** TV 返回键的两段提示（第一次只弹提示不暂停，2.5 秒内再按才真的退出） */
const backHint = ref('')
let backHintTimer: number | undefined
/*
 * ★ 初值必须读一次 document —— 本页可能是"详情页点播放时就已经进了全屏"
 * （飞牛 App 里 `DetailView.play()` 就是这么做的）。只写 `ref(false)` 的话，
 * 进来时全屏已经在 document 上，`fullscreenchange` 不会为这个实例再触发，
 * 于是按钮显示"进入全屏"、CSS 伪横屏（rotFs）也不生效 —— 2026-11-26 实测踩过。
 */
const fullscreen = ref(typeof document !== 'undefined' && !!document.fullscreenElement)
const errorMsg = ref('')
const rate = ref(getPrefs().rate || 1)
/** 首次载入中（用于避免一进来就显示"已暂停"的浮层） */
const loadingSrc = ref(true)
/** 缓冲中（waiting/seeking），用于中央转圈 */
const buffering = ref(false)
/**
 * seek 之前是否在播放。
 *
 * 部分内核（实测 Edge/Chromium + hls.js）在 seek 触发分片重载时会把
 * 播放状态打断成暂停，且不经过任何 `pause()` 调用。用户按 ←/→ 只是想
 * 跳几秒，结果画面停住会显得"按键把视频暂停了"。
 */
let resumeAfterSeek = false
/**
 * 弹幕引擎是否被"缓冲"冻住的（区别于用户暂停、切集）。
 *
 * <p>必须单独记一个来源：只有本标志为真时才允许由"画面重新走动"来自愈恢复，
 * 否则会把用户主动暂停的弹幕也一起放开。
 */
let dmFrozenForBuffer = false
/**
 * 冻结时的画面时间点：用来判断"画面是否真的又在走了"。
 *
 * <p>`waiting` 之后浏览器不一定会补一个 `playing`（实测 2026-11-04：
 * 派发一次 `stalled` 就足以把引擎冻到永久，之后只剩 sync 的 4Hz 重画）。
 * 所以恢复判据要落在时间上，而不是等某个事件。
 */
let dmFrozenAt = 0
/**
 * 自动起播还"欠"一次 play()（见 onLoaded 里的 800ms 补播）。
 * 任何一次 pause 事件都清掉它：那说明用户主动暂停，别再抢回来。
 */
let autoPlayPending = false
/**
 * 本源是否已经跑过自动起播（onLoaded 挂在 durationchange 上，
 * 该事件在一次播放里会因清单位移反复触发，自动起播只该做一次；
 * 换源时在 setupSource 里复位）。
 */
let didAutoStart = false
/**
 * 「刷新/重试」后要回到的位置（秒），0 = 不回跳。
 *
 * <p>用户口径（2026-11-18）：⟳ 刷新应当**续播**，不是从头。父组件的
 * `applyResume` 有 `resumeApplied` 一次性守卫（只在首次 loaded 生效），
 * 刷新触发的第二次 loaded 不会再去 seek —— 所以这里自己记住位置。
 */
let resumeAfterReload = 0
/** 拖动进度条时的临时位置（秒），null = 没在拖 */
const dragTime = ref<number | null>(null)
/** 进度条悬停位置（秒），null = 没悬停 */
const hoverTime = ref<number | null>(null)

/** 重播：回到开头继续播 */
function restart() {
  // 端内原生渲染：`<video>` 不参与播放，必须走原生（否则"从头开始"点了没反应）
  if (iosNative) {
    resumeAfterReload = 0
    iosNativeCmd('seek', 0)
    iosNativeCmd('play')
    clearResume()
    return
  }
  const v = video.value
  if (!v) return
  // 重播是明确的"从头"：别让上一次刷新挂起的续播位置捣乱
  resumeAfterReload = 0
  v.currentTime = 0
  // play() 在部分环境（jsdom / 旧浏览器）不返回 Promise，直接 .catch 会炸
  safePlay(v)
  clearResume()
}

/** 从"投屏回到本机"等处恢复播放（不重置进度）。 */
function play() {
  if (iosNative) {
    iosNativeCmd('play')
    userPausedAt = 0
    return
  }
  const v = video.value
  if (v) {
    userPausedAt = 0
    safePlay(v)
  }
}

/** 接收页「开启声音」：解除静音并记住偏好。 */
function unmute() {
  const v = video.value
  if (!v) return
  v.muted = false
  muted.value = false
  savePrefs({ muted: false, volume: v.volume }, { silent: true })
}

/** play() 的安全包装：失败静默（自动播放策略拦下来是常态） */
function safePlay(v: HTMLVideoElement) {
  const r = v.play() as Promise<void> | undefined
  if (!r || typeof r.catch !== 'function') return
  r.catch((e: unknown) => {
    /*
     * 只有接收页（mutedFallback=true）才静音兜底：它是别的设备投过来的，
     * 页面没有用户手势；普通播放页被策略拦下时应停在播放按钮，不能悄悄静音播。
     */
    const name = (e as { name?: string } | null)?.name
    if (name !== 'NotAllowedError' || v.muted || !props.mutedFallback) return
    v.muted = true
    muted.value = true
    emit('autoplay-muted')
    const r2 = v.play() as Promise<void> | undefined
    if (r2 && typeof r2.catch === 'function') r2.catch(() => {})
  })
}

/** 从外部跳到指定时间（续播恢复用） */
function seekTo(sec: number) {
  if (iosNative) {
    if (Number.isFinite(sec)) iosNativeCmd('seek', Math.max(0, sec))
    return
  }
  const v = video.value
  if (!v || !Number.isFinite(sec)) return
  /*
   * 片库转码中：只能跳到已经转出来的地方。跳过头会让 hls.js 等一个
   * 还不存在的分片，表现就是"卡住不动"（2026-11-17 实测）。
   */
  const ahead = props.aheadSeconds || 0
  let target = ahead > 0 ? Math.min(sec, ahead - 2) : sec
  const d = v.duration
  if (Number.isFinite(d) && d > 0) target = Math.min(target, d - 1)
  v.currentTime = Math.max(0, target)
  syncDm()
}

/**
 * 用户主动 seek（进度条/热度条/手势/键盘/跳转弹框）。
 *
 * <p>与 {@link seekTo} 的区别：目标是"用户想去的地方"，超出已转出范围时
 * **不静默夹回**（那是"拖了没反应/回弹"的根源），而是交给父组件从目标点
 * 重转。程序性跳转（续播恢复、seekAt）仍走 seekTo —— 它们必须夹住。
 */
function userSeek(sec: number) {
  if (iosNative) {
    if (Number.isFinite(sec)) iosNativeCmd('seek', Math.max(0, sec))
    return
  }
  const v = video.value
  if (!v || !Number.isFinite(sec)) return
  const d = v.duration
  const target = Math.max(0, Number.isFinite(d) && d > 0 ? Math.min(sec, d - 0.1) : sec)
  if (seekOutsideProduced(target, props.aheadSeconds || 0, props.aheadStartSeconds || 0)) {
    emit('seek-beyond', target)
    return
  }
  v.currentTime = target
  syncDm()
}

// ---------------------------------------------------------------- 跳到指定时间
/**
 * 底栏那行时间从"只显示"改成"可点跳转"（2026-11-12）。
 *
 * <p>注释里一直写着"点了跳到指定分钟"，但 span 上没有任何事件 ——
 * 长视频想跳 1 小时 20 分只能拖进度条，误差几十秒。手机上尤其难拖准。
 *
 * <p>数字键盘打不出冒号，所以 `parseClock` 额外吃纯数字：
 * 1234 = 12:34、834 = 8:34。这样不用切换输入法符号页。
 */
const seekDialog = ref(false)
const seekText = ref('')
const seekInputEl = ref<HTMLInputElement | null>(null)

function parseClock(raw: string): number | null {
  const s = raw.trim()
  if (!s) return null
  if (s.includes(':')) {
    const parts = s.split(':').map((x) => Number(x.trim()))
    if (parts.length > 3) return null
    if (parts.some((n) => !Number.isFinite(n) || n < 0)) return null
    return parts.reduce((acc, n) => acc * 60 + n, 0)
  }
  if (!/^\d+$/.test(s)) return null
  if (s.length <= 2) return Number(s)
  if (s.length === 3) return Number(s.slice(0, 1)) * 60 + Number(s.slice(1))
  if (s.length === 4) return Number(s.slice(0, 2)) * 60 + Number(s.slice(2))
  return Number(s)
}

function openSeekDialog() {
  seekText.value = fmt(currentTime.value)
  seekDialog.value = true
  void nextTick(() => {
    const el = seekInputEl.value
    if (!el) return
    try {
      el.focus({ preventScroll: true })
    } catch {
      el.focus()
    }
    el.select()
  })
}

function applySeekDialog() {
  const sec = parseClock(seekText.value)
  if (sec == null) return
  userSeek(sec)
  seekDialog.value = false
}

/** 供外部读取当前进度 */
function getTime(): number {
  /*
   * 端内原生渲染：`<video>.currentTime` 恒为 0（元素没在播），
   * 必须读原生每 200ms 灌进来的 ref —— 否则 handoffPos()（原生重开、
   * 投屏续播、进度回写）拿到的是"上次保存的位置"而不是正在看的位置。
   */
  if (iosNative) return currentTime.value
  return video.value?.currentTime ?? 0
}

/** 投屏接收页需要：本地是否暂停 / 总时长 */
function isPaused(): boolean {
  if (iosNative) return !playing.value
  const v = video.value
  return !v || v.paused
}

function getDuration(): number {
  if (iosNative) return duration.value || props.totalDuration || 0
  const d = video.value?.duration
  return typeof d === 'number' && Number.isFinite(d) ? d : 0
}

/** 刷新当前集：重新加载同一地址（源站卡住时用） */
function reloadSrc() {
  const v = video.value
  if (!v || !props.src) return
  // 刷新/重试都续播到当前位置（用户口径 2026-11-18）；从头请用「重播」
  const at = Number.isFinite(v.currentTime) ? v.currentTime : 0
  resumeAfterReload = at > 1 ? at : 0
  loadingSrc.value = true
  setupSource(props.src)
  v.load()
  if (props.danmakuUrl) loadDanmaku(props.danmakuUrl)
}

/**
 * 错误页的「重试」。
 *
 * 不复用 `reloadSrc`：那条路径会保留 errorMsg 之外的状态，
 * 而这里要先把错误清掉让覆盖层消失，否则用户点了没反应。
 */
function retryNow() {
  errorMsg.value = ''
  recoverAttempts = 0
  reloadSrc()
}

/** 跳转秒数（相对当前） */
function seekBy(sec: number) {
  const v = video.value
  if (!v) return
  const d = v.duration || 0
  v.currentTime = Math.max(0, Math.min(d || Infinity, v.currentTime + sec))
}

/**
 * 片头 / 片尾标记（按剧集记，换集保留）。
 *
 * 数据源是 `skipMarks` 存储（site+vod_id 为键），**不再读 prefs.skipIntro**：
 * prefs 里的那份是全片统一的固定秒数，对"每部剧片头不一样"这件事无能为力。
 * 设置页仍保留那两个项作为「没有手动标记时的默认值」。
 *
 * 语义：
 *   intro = 片头结束的绝对秒数（播放位置 < intro 时提示跳过）
 *   outro = 片尾开始的"距结尾秒数"（位置 >= duration - outro 时提示跳过）
 */
const marks = ref<SkipMarks>({ intro: 0, outro: 0, at: 0 })
/**
 * 播放位置是否已经可信（metadata 到手、刷新续播 seek 已落地）。
 *
 * <p>为什么需要：⟳ 刷新会回到 0 秒等 metadata，而片头标记在手 →
 * `inIntro` 立刻为真，右下角「跳过片头」会在加载阶段闪一下；等续播
 * seek 落到 20 分钟又突然消失（2026-11-23 实报）。片头提示只该在
 * 位置可信之后出现，否则看起来像"每刷新一次都从头开始"。
 */
const posReady = ref(false)

function reloadMarks() {
  if (!props.site || !props.vodId) {
    marks.value = { intro: 0, outro: 0, at: 0 }
    return
  }
  marks.value = getSkipMarks(props.site, props.vodId)
}

// 换集时 site/vodId 不变，但父组件可能重建实例；watch 兜住同实例内的变化
watch(() => [props.site, props.vodId] as const, reloadMarks, { immediate: true })

/**
 * 优先级：手动标记 > 源站 skips > 设置页全局默认。
 *
 * 源站给的是**绝对秒数**（opening 的 end / ending 的 start），手动画的片尾是
 * "距结尾多少秒"，两者口径不同，所以片尾统一换算成绝对秒数 `outroAt`
 * 再做判断——否则同一个 `duration - x` 会被算两次。
 */
const skipIntro = computed(
  () => marks.value.intro || props.spiderIntro || prefs.value.skipIntro || 0,
)
const outroAt = computed(() => {
  if (marks.value.outro > 0 && duration.value > 0) return duration.value - marks.value.outro
  if ((props.spiderOutroAt || 0) > 0) return props.spiderOutroAt as number
  const d = prefs.value.skipOutro || 0
  return d > 0 && duration.value > 0 ? duration.value - d : 0
})

/** 在片头区间内 */
const inIntro = computed(
  () => posReady.value && skipIntro.value > 0 && currentTime.value < skipIntro.value,
)
/** 进入片尾区间 */
const inOutro = computed(() => outroAt.value > 0 && currentTime.value >= outroAt.value)

/**
 * 中插广告段：源站 `skips` 里 type=middle 的区间。
 *
 * 默认不跳（`prefs.skipMiddle` 关）。自动跳中插和跳片头不是一回事——
 * 中插在正片中间，源站区间标错一点就会吞掉剧情，所以必须用户显式打开。
 */
const middleSegments = computed<MiddleSegment[]>(() =>
  prefs.value.skipMiddle ? props.middles || [] : [],
)

/**
 * 命中中插时返回该段，否则 null。
 *
 * 判定用「已进入区间但还没到终点」而不是相等：timeupdate 4 次/秒，
 * 用相等会漏掉整段（实测片头倒计时那次踩过同类问题）。
 */
const activeMiddle = computed<MiddleSegment | null>(() => {
  const t = currentTime.value
  for (const seg of middleSegments.value) {
    if (t >= seg.start && t < seg.end) return seg
  }
  return null
})

// ---------------------------------------------------------------- 外挂字幕
//
// 浏览器原生 `<track>` 只认 WebVTT，而国内源九成给 .srt，所以这里先经本代理
// 取回文本、必要时转成 VTT，再用 `blob:` URL 喂给 `<track>`。
//
// 为什么不用代理地址直接当 track src：跨源 `<track>` 要求 `<video crossorigin>`
// 一起打开，而一旦给 video 加 crossorigin，**所有媒体请求都会变成 CORS 模式**
// ——分片、密钥、Range 任一环节少一个头就整片播不了。换成 blob 就完全绕开。

const subUrl = ref('')
const subLabel = ref('')
let subBlobUrl = ''

function revokeSub() {
  if (subBlobUrl) URL.revokeObjectURL(subBlobUrl)
  subBlobUrl = ''
  subUrl.value = ''
}

/**
 * 取第一份**能渲染**的字幕：vtt 原样、srt/ass 各转一次；
 * 其它格式（sub/dvd/图形字幕）直接跳过——那需要完整字幕排版引擎。
 */
async function loadSubtitle() {
  revokeSub()
  for (const s of props.subs || []) {
    if (!s?.url) continue
    const fmt = subtitleFormat(s)
    if (fmt === 'other') continue
    try {
      const r = await fetch(proxyStream(s.url))
      if (!r.ok) continue
      const text = await r.text()
      if (!text.trim()) continue
      const vtt = fmt === 'srt' ? srtToVtt(text) : fmt === 'ass' ? assToVtt(text) : text
      // assToVtt 对"没有一条 Dialogue"的输入返回空串（可能是纯图形字幕的 sub），
      // 这种情况跳过，不要塞一份空 VTT 进去
      if (!vtt.trim()) continue
      const blob = new Blob([vtt], { type: 'text/vtt' })
      subBlobUrl = URL.createObjectURL(blob)
      subUrl.value = subBlobUrl
      subLabel.value = s.name || '外挂字幕'
      // 动态插入的 track 不一定自动显示，显式把最后一条置为 showing
      await nextTick()
      const tracks = video.value?.textTracks
      if (tracks && tracks.length) tracks[tracks.length - 1].mode = 'showing'
      return
    } catch {
      /* 单份字幕取不到就试下一份 */
    }
  }
}

watch(() => props.subs, () => void loadSubtitle(), { immediate: true })

/**
 * 跳过提示的倒计时（秒）。
 *
 * 语义：
 *   skipCountdown === 0 → 点了直接跳（不留倒计时，按钮行为与旧版一致）
 *   skipCountdown > 0   → 提示条上转圈，到点自动跳；用户可点「取消」放弃
 *
 * 只在真正「进入区间」的那一刻起算，一旦跳过或取消就不再重启 —— 否则
 * 用户点了取消，下一帧 currentTime 还在区间内又会被重新计时（死循环）。
 */
const skipCdTotal = computed(() => {
  const n = Number(prefs.value.skipCountdown)
  return Number.isFinite(n) && n > 0 ? n : 0
})

/** 剩余秒数（用于环形进度）；0 表示没有在倒计时 */
const introCd = ref(0)
const outroCd = ref(0)
/** 用户点了取消：区间内不再自动跳 */
const introDismissed = ref(false)
const outroDismissed = ref(false)
let introTimer: number | undefined
let outroTimer: number | undefined

function clearSkipTimer(which: 'intro' | 'outro') {
  if (which === 'intro') {
    if (introTimer) window.clearInterval(introTimer)
    introTimer = undefined
    introCd.value = 0
  } else {
    if (outroTimer) window.clearInterval(outroTimer)
    outroTimer = undefined
    outroCd.value = 0
  }
}

function startSkipCountdown(which: 'intro' | 'outro') {
  const total = skipCdTotal.value
  /*
   * ★ 0 = 立即跳，不是"不跳"（2026-11-15 修6）。
   *
   * 设置页「跳过前倒计时」的 0 档标签就是"立即跳"，但这里原来
   * `total <= 0` 直接 return——区间内只挂出跳过按钮、永不自动执行。
   * 症状正是用户报的：连播=立即播放时切下一集，右下角只有"跳过片头"
   * 按钮，得手点；把连播改成倒计时 3s（顺手把倒计时档调到非 0）才恢复。
   * 现在与标签语义对齐：进入区间且未取消 → 直接跳。
   */
  if (total <= 0) {
    if (which === 'intro') doSkipIntro()
    else doSkipOutro()
    return
  }
  clearSkipTimer(which)
  const cdRef = which === 'intro' ? introCd : outroCd
  const timerRef = which === 'intro' ? 'introTimer' : 'outroTimer'
  cdRef.value = total
  let left = total
  const t = window.setInterval(() => {
    left -= 1
    cdRef.value = Math.max(0, left)
    if (left <= 0) {
      window.clearInterval(t)
      if (which === 'intro') {
        introTimer = undefined
        doSkipIntro()
      } else {
        outroTimer = undefined
        doSkipOutro()
      }
    }
  }, 1000)
  if (timerRef === 'introTimer') introTimer = t
  else outroTimer = t
}

/**
 * 跳过提示条出现时，把焦点落到「取消」上（TV 专用）。
 *
 * <p><b>为什么要聚焦</b>（2026-10-31 用户要求）：提示条是 `bottom-24` 的
 * 右侧浮层，不在控制栏作用域内，tv-nav 的几何选焦不会主动走过去 ——
 * 遥控器上根本没有"够到它"的路径，用户只能干等倒计时结束。
 *
 * <p><b>为什么默认落「取消」而不是主体（跳过）</b>：倒计时本来就会自动跳，
 * 用户此刻唯一可能想做的就是"别跳"。让 ok 直接执行最常见的动作，
 * 按一下右键就到「跳过」。
 */
function focusSkipBar(which: 'intro' | 'outro') {
  if (!isTvEnv()) return
  // 等一帧：提示条是 v-if，同一帧里 DOM 还没出来
  void nextTick(() => {
    window.setTimeout(() => {
      const bar = document.querySelector(`[data-skip-bar="${which}"]`)
      if (!bar) return
      const btn = bar.querySelector<HTMLElement>(`[data-skip-cancel="${which}"]`)
        || bar.querySelector<HTMLElement>('button')
      if (!btn) return
      clearTvFocus()
      btn.classList.add('meow-tv-focus')
      try {
        btn.focus({ preventScroll: true })
      } catch {
        /* 老内核忽略 */
      }
    }, 80)
  })
}

function doSkipIntro() {
  clearSkipTimer('intro')
  skipIntroNow()
}
function doSkipOutro() {
  clearSkipTimer('outro')
  // 跳完片尾画面还停在片尾区间（没有 seek 到末尾），提示条要主动收掉，
  // 否则父组件的连播倒计时期间它还挂在右下角，像"点了没生效"。
  outroDismissed.value = true
  skipOutroNow()
}

/** 用户点「取消」：停表并记住本次区间不再自动跳 */
function dismissSkip(which: 'intro' | 'outro') {
  clearSkipTimer(which)
  if (which === 'intro') introDismissed.value = true
  else outroDismissed.value = true
}

// 进入区间 → 起倒计时；离开区间 → 复位（下次进还能再提示）
// ★ 必须 immediate（2026-11-15 修6）：片头标记按"剧"记（site+vod_id），
//   挂载那一刻 marks 已经在手、currentTime 还是 0 → inIntro 的初值就是
//   true 且之后不再变化，非 immediate 的 watch 永远等不到第一次触发 ——
//   这就是"只显示跳过按钮、自动跳永远不来"的另一半根因。
watch(
  inIntro,
  (v) => {
    if (v) {
      if (!introDismissed.value) startSkipCountdown('intro')
      focusSkipBar('intro')
    } else {
      clearSkipTimer('intro')
      introDismissed.value = false
    }
  },
  { immediate: true },
)
watch(
  inOutro,
  (v) => {
    if (v) {
      // 片尾自动跳只在播放中做，暂停时用户可能在拖进度
      if (!outroDismissed.value && playing.value) startSkipCountdown('outro')
      focusSkipBar('outro')
    } else {
      clearSkipTimer('outro')
      outroDismissed.value = false
    }
  },
  { immediate: true },
)
// 暂停时停表，恢复播放重新计时（避免暂停期间"跳过"悄悄发生）
watch(playing, (p) => {
  if (!p) {
    clearSkipTimer('intro')
    clearSkipTimer('outro')
  } else {
    if (inIntro.value && !introDismissed.value) startSkipCountdown('intro')
    if (inOutro.value && !outroDismissed.value) startSkipCountdown('outro')
  }
})

function skipIntroNow() {
  seekBy(skipIntro.value - currentTime.value)
}

// ---------------------------------------------------------------- 无声检测
//
// Android WebView（TV 壳）对片库文件是**先直连**：容器预判只看扩展名
// （unsupportedContainer），音轨编码不在预判里 —— AC3 / E-AC3 / DTS 这类
// Chromium 全家都不带解码器的音轨，画面（H264/H265 该硬解硬解）一切正常、
// 音频却**静默失败**：不报 MediaError、logcat 里连 AudioTrack 都不会出现
// （2026-11-21 投影仪实报《武林外传》：h264+AC3 直连，dumpsys audio 全程
// 没有 meow.tv 的播放器登记）。用户看到的正是"有画面没声音，原生播放有音"。
//
// 两条判据（都要求非静音 —— muted 时 Chromium 会跳过音频解码，0 是正常的）：
//   ① `webkitAudioDecodedByteCount`：视频字节在涨、音频计数恒 0 = 内核没有
//      为这条音轨建解码器。PC WebView2 实测有效（AC3 mp4 恒 0 / AAC 递增）。
//   ② 计数器缺席（部分 Android WebView，MuMu .76 实测 Chromium 110 无此
//      属性）→ WebAudio 采样：元素经 createMediaElementSource 接 analyser，
//      两拍 RMS 全 0 且画面在走 = 没有可解码的音频。**前提**是媒体以 CORS
//      模式加载 —— 所以 noAudioFallback 模式下给 <video> 设 crossorigin，
//      片库流后端带 Access-Control-Allow-Origin:*，跨源污染不存在；
//      若后端没带该头，媒体加载直接报错，走「直连失败 → 转码」旧兜底，
//      行为依旧收敛。
let noAudioStreak = 0
let noAudioFired = false
let noAudioTimer: number | undefined
/** WebAudio 通道的状态（计数器缺席的内核才建） */
let noAudioCtx: AudioContext | undefined
let noAudioTapEl: HTMLMediaElement | undefined
let noAudioAnalyser: AnalyserNode | undefined

function clearNoAudioWatch() {
  if (noAudioTimer) {
    window.clearTimeout(noAudioTimer)
    noAudioTimer = undefined
  }
  noAudioFired = false
  noAudioStreak = 0
}

/**
 * 直连源（片库）以 CORS 模式加载：WebAudio 探测（判据②）要求媒体不是
 * 跨源污染的，而片库流响应带 ACAO:*，crossorigin=anonymous 恰好成立。
 *
 * <p>只在这一个 prop 下设置 —— 点播走 hls.js（MSE blob 同源），设置了反而
 * 会让直连 mp4 的请求多出一步 CORS 校验，没有必要冒这个险。
 */
function applyNoAudioCrossOrigin() {
  const v = video.value
  if (!v) return
  if (props.noAudioFallback && !hls.value && !dash.value) {
    if (v.crossOrigin !== 'anonymous') v.crossOrigin = 'anonymous'
  } else if (v.crossOrigin) {
    v.crossOrigin = null
  }
}

/** 采样一拍 WebAudio 能量；返回 null = 通道不可用/元素不在播 */
function sampleNoAudioEnergy(): number | null {
  const v = video.value
  if (!v || !noAudioCtx || !noAudioAnalyser) return null
  if (v.paused || v.muted || v.volume <= 0) return null
  if (noAudioCtx.state === 'suspended') {
    // 用户点过「立即播放」就算 activation，resume 应该能成；兜底再试一次
    void noAudioCtx.resume()
  }
  const buf = new Float32Array(noAudioAnalyser.fftSize)
  noAudioAnalyser.getFloatTimeDomainData(buf)
  let peak = 0
  for (const x of buf) {
    const a = Math.abs(x)
    if (a > peak) peak = a
  }
  return peak
}

async function armWebAudioProbe() {
  const v = video.value
  if (!v) return
  // 同一个 <video> 只能绑一次 MediaElementSource；切集/换线路复用元素时
  // 直接沿用上一条通道。换集=页面重建（新元素）才会新建。
  if (noAudioCtx && noAudioTapEl === v) {
    void noAudioCtx.resume()
    startWebAudioSampling()
    return
  }
  try {
    const AC = window.AudioContext || (window as any).webkitAudioContext
    if (typeof AC !== 'function') {
      noAudioFired = true // 没有任何检测手段可用的极端内核：退化为旧行为
      return
    }
    const ctx = new AC()
    /*
     * ★ 绑定是不可撤销的：createMediaElementSource 之后元素音频**只能**
     *   走这条图。所以必须先确认 AudioContext 真的能跑（autoplay 政策/
     *   内核限制下可能永远 suspended），起不来就放弃探测 —— 否则会把
     *   本来有声的 AAC 直连也变成哑巴（比不修更糟，2026-11-21 自查）。
     */
    try {
      await ctx.resume()
    } catch {
      /* 某些内核 resume 返回 rejected，看 state 说话 */
    }
    if (ctx.state !== 'running' || video.value !== v) {
      noAudioFired = true // 起不来 / 元素已被换源：放弃探测，保持旧行为
      return
    }
    const src = ctx.createMediaElementSource(v)
    const analyser = ctx.createAnalyser()
    analyser.fftSize = 2048
    src.connect(analyser)
    // 必须连回 destination：绑定后元素音频只走这条图，不连就永久哑
    analyser.connect(ctx.destination)
    noAudioCtx = ctx
    noAudioAnalyser = analyser
    noAudioTapEl = v
  } catch {
    noAudioFired = true // 绑定失败（已绑过/内核差异）：不判，保持旧行为
    return
  }
  startWebAudioSampling()
}

/** WebAudio 通道的两拍采样（绑定成功后才跑）：全 0 且画面在走 = 无声 */
function startWebAudioSampling() {
  if (noAudioFired || noAudioTimer || !props.noAudioFallback) return
  const scheduleNoAudio = () => {
    if (noAudioFired || noAudioTimer || !props.noAudioFallback) return
    noAudioTimer = window.setTimeout(step, noAudioStreak === 0 ? 5000 : 2500)
  }
  const step = () => {
    noAudioTimer = undefined
    const el = video.value
    if (!el || !props.noAudioFallback || noAudioFired) return
    if (el.readyState < 3 || (el.currentTime || 0) < 3) {
      scheduleNoAudio()
      return
    }
    const peak = sampleNoAudioEnergy()
    if (peak === null) {
      scheduleNoAudio()
      return
    }
    if (peak > 0.001) {
      noAudioFired = true // 有声音能量，收工
      return
    }
    noAudioStreak += 1
    if (noAudioStreak >= 2) {
      noAudioFired = true
      emit('no-audio')
      return
    }
    scheduleNoAudio()
  }
  scheduleNoAudio()
}

/**
 * metadata 就绪后挂上检测（onLoaded 调）。只在原生渐进 src 上跑：
 * hls/dash 走 MSE，音频是后端转码出来的 AAC，不需要也不该检测。
 */
function armNoAudioWatch() {
  if (!props.noAudioFallback || noAudioFired || noAudioTimer) return
  if (hls.value || dash.value) return
  const scheduleNoAudio = () => {
    if (noAudioFired || noAudioTimer || !props.noAudioFallback) return
    noAudioTimer = window.setTimeout(step, noAudioStreak === 0 ? 4000 : 2500)
  }
  const step = () => {
    noAudioTimer = undefined
    const v = video.value
    if (!v || !props.noAudioFallback || noAudioFired) return
    const audio = (v as unknown as { webkitAudioDecodedByteCount?: number })
      .webkitAudioDecodedByteCount
    // 计数器缺席（部分 Android WebView）→ 换 WebAudio 通道（判据②）
    if (typeof audio !== 'number') {
      armWebAudioProbe()
      return
    }
    // 静音/零音量时 Chromium 会跳过音频解码，计数 0 是正常的，不能判
    if (v.muted || v.volume <= 0) {
      scheduleNoAudio()
      return
    }
    if (v.readyState < 3 || (v.currentTime || 0) < 3) {
      scheduleNoAudio()
      return
    }
    if (audio > 0) {
      noAudioFired = true // 音频解码正常，收工不再采样
      return
    }
    const videoBytes = (v as unknown as { webkitVideoDecodedByteCount?: number })
      .webkitVideoDecodedByteCount
    // 视频也没在解（卡住/网络慢）→ 样本无效，往后等，不急着判
    if (typeof videoBytes !== 'number' || videoBytes <= 0) {
      scheduleNoAudio()
      return
    }
    noAudioStreak += 1
    if (noAudioStreak >= 2) {
      noAudioFired = true
      emit('no-audio')
      return
    }
    scheduleNoAudio()
  }
  scheduleNoAudio()
}

/**
 * 跳过片尾 —— **直接当作本集播完**，而不是 seek 到 `duration - 1` 等它自然结束。
 *
 * <p><b>为什么必须这样</b>（2026-10-31 用户报的问题 4）：
 * 原来只把 `currentTime` 设到 `duration - 1`，指望播放器再跑 1 秒触发 `ended`。
 * 但实测这个"最后 1 秒"经常不触发 `ended`：
 *   · HLS 的最后一个分片时长与 `duration` 有几十毫秒偏差，seek 过去
 *     buffer 里可能已经越界，视频停在暂停态
 *   · 分片不完整/源站少给一片时，末尾那点根本没有数据
 *
 * 后果是 `onEnded` 永远不执行 → **"立即播放"模式下不连播**。
 * 而"倒计时"模式之所以看着正常，是因为用户多半没点跳过、视频走完了全程。
 *
 * <p>所以跳过片尾＝用户明确表示"这集我看完了"，直接发 ended，
 * 语义上也比"再等 1 秒"更准确。
 */
function skipOutroNow() {
  /*
   * ★ 不要把 currentTime 拉到 duration-1（2026-11-23 用户实报「跳片尾会先定位到
   *   最后一秒再跳」）。原来那样做是为了"画面停在末尾"，但用户看到的是一次
   *   突兀的跳转；而 ended 语义本来就不依赖真实播放位置。
   *
   * 现在：直接发 ended（父组件换集/倒计时），进度按"已看完"上报（pos=duration），
   * 父组件据此清掉续播点；画面停在用户点跳过的位置，不再先跳到最后一秒。
   */
  endedSent = true
  emit('ended')
  flushProgress(true, true, duration.value || video.value?.duration || 0)
}

/**
 * 原生 ended 的唯一入口（模板绑定它，而不是直接 emit）。
 *
 * <p>去重见 `endedSent`：跳过片尾手动发过一次之后，自然播完的那次必须吞掉，
 * 否则连播倒计时会被重置、immediate 模式会连跳两集。
 */
function onMediaEnded() {
  if (endedSent) return
  endedSent = true
  emit('ended')
  flushProgress(true)
}

/** 环形进度：剩余比例（1 = 刚开始，0 = 到点） */
function cdRatio(left: number): number {
  const total = skipCdTotal.value
  if (!total) return 0
  return Math.max(0, Math.min(1, left / total))
}

// ---------------------------------------------------------------- 打标记
//
// 「点一下即以当前时间为准」：
//   设片头 → 记录"片头在现在这里结束"，之后从头播会自动提示跳到这个点
//   设片尾 → 记录"片尾从现在这里开始"，换算成距结尾的秒数
// 再点一次（值相同）= 取消该标记，避免误点后无法撤销。

const marksTip = ref('')
let marksTipTimer: number | undefined

function flashMarks(msg: string) {
  marksTip.value = msg
  if (marksTipTimer) window.clearTimeout(marksTipTimer)
  marksTipTimer = window.setTimeout(() => (marksTip.value = ''), 2200)
}

function fmtClock(sec: number): string {
  const s = Math.max(0, Math.round(sec))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const r = s % 60
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m)
  return `${h > 0 ? h + ':' : ''}${mm}:${String(r).padStart(2, '0')}`
}

/**
 * 读播放器的实时位置。
 *
 * ★ 不能直接用响应式的 `currentTime` / `duration` ref：它们由 timeupdate
 * 事件驱动（约 4 次/秒），用户在 seek 之后、下一次 timeupdate 之前点按钮，
 * 读到的仍是**上一位置**。
 * 实测踩过：跳到 555s 立刻点「设片尾」，存成了 `635 - 120 = 515`，
 * 而正确值是 `635 - 555 = 80`。
 */
function liveTime(): { cur: number; dur: number } {
  const v = video.value
  return {
    cur: v ? v.currentTime : currentTime.value,
    dur: v && isFinite(v.duration) ? v.duration : duration.value,
  }
}

function toggleIntroMark() {
  if (!props.site || !props.vodId) return
  const { cur: raw } = liveTime()
  const cur = Math.round(raw)
  if (cur <= 0) return
  const same = Math.abs(marks.value.intro - cur) <= 2
  marks.value = setSkipMark(props.site, props.vodId, { intro: same ? 0 : cur })
  flashMarks(same ? '已取消片头标记' : `片头记为 ${fmtClock(cur)}`)
}

function toggleOutroMark() {
  if (!props.site || !props.vodId) return
  const { cur, dur } = liveTime()
  if (!dur) return
  const remaining = Math.round(dur - cur)
  if (remaining <= 0) return
  const same = Math.abs(marks.value.outro - remaining) <= 2
  marks.value = setSkipMark(props.site, props.vodId, { outro: same ? 0 : remaining })
  flashMarks(
    same ? '已取消片尾标记' : `片尾记为 ${fmtClock(currentTime.value)}（距结尾 ${fmtClock(remaining)}）`,
  )
}

const hls = shallowRef<Hls | null>(null)
/** dash.js 实例（DASH 源） */
const dash = shallowRef<any>(null)
const dm = shallowRef<DanmakuRenderer | null>(null)
let hideTimer: number | undefined
/** 高丢帧自愈：定时采样 + 每个源只报一次 */
let dropGuardTimer: number | undefined
let dropReported = false
/*
 * 低端设备收敛（2026-11-24）：
 *   · 起播 15 秒内不判（解码器初始化天然丢帧）；
 *   · 连续两个采样窗口都超阈值才报（单窗口抖动不算）；
 *   · 缓冲垫 <4 秒时归为网络/缓冲问题，不白转一次 4K。
 */
let dropBaselineTotal = -1
let dropBaselineDropped = -1
let dropBadStreak = 0
/** 当前选中的清晰度变体地址（高丢帧自愈转封装时用它，避免 ffmpeg 选到低码率变体） */
let activeLevelUrl = ''

// ---------------------------------------------------------------- 调试面板

/**
 * 播放信息调试面板。
 *
 * 存在的理由：卡片上"转圈 / 黑屏 / 卡住"在用户眼里是同一件事，
 * 但原因可能是源站慢、分片 404、解码失败、CORS、弹幕拖垮主线程……
 * 这个面板把三层信息摊开，一眼能判断该去哪一层查：
 *   · HLS 层：码率、档位、分片加载耗时 → 是源的问题吗
 *   · 内核层：readyState、缓冲秒数、丢帧 → 是解码/性能问题吗
 *   · 业务层：线路、重试次数、弹幕条数 → 是切换逻辑的问题吗
 *
 * 同时挂到 window.__playerDebug 供 MCP 自动化断言
 * （pc_eval 读它即可，不必截图肉眼看）。
 */
const debugOn = ref(false)
/** 播放信息面板的「关闭」按钮（TV 打开时聚焦它，见 toggleDebug） */
const debugCloseEl = ref<HTMLButtonElement | null>(null)
/** hls.js 的分片加载历史（最近 8 条，用于看哪个分片慢） */
const fragLog = ref<Array<{ t: number; ms: number; level: number; ok: boolean }>>([])
/** 距今多少毫秒开始准备（换源到 loadedmetadata） */
const loadStartedAt = ref(0)
const firstFrameMs = ref<number | null>(null)
/** 最近一次 hls 错误（非致命的也记，便于看抖动） */
const lastHlsError = ref('')

/** 带宽估算的展示格式（入参单位 kbps） */
function fmtBandwidth(kbps: number): string {
  // 本地/局域网下 hls.js 会估到 70 万 kbps 这种数字，
  // 直接显示反而干扰判断，超过 100M 就只给个量级
  if (kbps >= 100_000) return '>100Mbps'
  if (kbps >= 1000) return `${(kbps / 1000).toFixed(1)}Mbps`
  return `${kbps}kbps`
}

function toggleDebug() {
  debugOn.value = !debugOn.value
  if (debugOn.value) {
    refreshDebug(true)
    /*
     * TV：打开后把焦点落到「关闭」按钮（用户 2026-11-04 要求）。
     * 面板是 pointer-events-none 的，只有这个按钮可点；焦点落在面板外的话，
     * 按方向键会跑去背景，用户根本找不到怎么关。
     * 等一帧：面板是 v-if，同帧里按钮还没渲染。
     */
    if (isTvEnv()) {
      void nextTick(() => {
        const btn = debugCloseEl.value
        if (!btn) return
        const w = window as unknown as { __meowTvFocus?: (t: Element, pin?: boolean) => boolean }
        if (typeof w.__meowTvFocus === 'function') w.__meowTvFocus(btn, true)
      })
    }
  } else {
    // 关闭时必须走这里清定时器和全局快照，否则 MCP 会一直读到陈数据
    debugSnapshot.value = null
    refreshDebug()
  }
}

/** 采集一次快照（不依赖响应式，供面板和 MCP 共用） */
function collectDebug() {
  const v = video.value
  const h = hls.value
  const dmr = dm.value
  if (!v) return null

  // 缓冲：当前播放点之后连续可播的秒数（与欠载判定共用同一个函数）
  const bufferedAhead = bufferedAheadSec(v)

  // 丢帧（Chrome/Edge 有，Safari 无）
  const q = (v as HTMLVideoElement & { getVideoPlaybackQuality?: () => VideoPlaybackQuality })
    .getVideoPlaybackQuality?.()

  const levels = h?.levels || []
  const cur = h?.currentLevel ?? -1

  return {
    // ---- 内核层 ----
    video: {
      resolution: v.videoWidth && v.videoHeight ? `${v.videoWidth}×${v.videoHeight}` : '—',
      readyState: v.readyState, // 0..4，>=3 才能播
      networkState: v.networkState,
      bufferedAhead: +bufferedAhead.toFixed(1),
      currentTime: +v.currentTime.toFixed(1),
      duration: Number.isFinite(v.duration) ? +v.duration.toFixed(1) : 0,
      paused: v.paused,
      playbackRate: v.playbackRate,
      dropped: q?.droppedVideoFrames ?? null,
      totalFrames: q?.totalVideoFrames ?? null,
    },
    // ---- HLS 层 ----
    hls: h
      ? {
          level: cur,
          levelCount: levels.length,
          // 清单里的 CODECS（avc1.640028 / hvc1.2.4.L153…）：直连时浏览器唯一能拿到的编码
          codecs: (cur >= 0 ? ((levels[cur] as unknown as { attrs?: { CODECS?: string } })?.attrs?.CODECS || '') : ''),
          // 码率：当前档位实际比特率 / 各档列表
          bitrate: cur >= 0 && levels[cur] ? levels[cur].bitrate : null,
          // 倍速联动降档的生效值（-1=不限）——排查"1.25x 卡"时先看它
          capping: h.autoLevelCapping,
          abr: abrTrace.value,
          levels: levels.slice(0, 6).map((l) => Math.round(l.bitrate / 1000)),
          bandwidthEstimate: h.bandwidthEstimate ? Math.round(h.bandwidthEstimate / 1000) : null,
          frags: fragLog.value.slice(-8),
          lastError: lastHlsError.value || null,
        }
      : null,
    // ---- 业务层 ----
    biz: {
      line: props.lineName || '默认',
      // 源/输出编码（转码或原生回传；直连为空）
      media: props.mediaInfo ? panelMediaLine(props.mediaInfo) : '',
      autoRetryHint: props.autoRetryHint || null,
      recoverAttempts,
      maxRecoverAttempts: MAX_RECOVER_ATTEMPTS,
      danmaku: (() => {
        const q = dmQuality.value ?? dmQualityStats([], duration.value || shownDur.value)
        return {
          count: dmCount.value,
          onScreen: (dmr as any)?.active?.length ?? 0,
          lanes: (dmr as any)?.laneCount ?? 0,
          loading: dmLoading.value,
          density: getPrefs().danmakuDensity,
          source: dmSourceLabel.value || dmSource.value || '未知',
          scroll: q.scroll,
          top: q.top,
          bottom: q.bottom,
          cover: q.cover,
          warn: dmQualityWarn(q),
          offset: dmOffset.value,
        }
      })(),
      // 起播耗时：换源 → 首帧
      firstFrameMs: firstFrameMs.value,
    },
    // ---- 播放器状态 ----
    state: {
      buffering: buffering.value,
      loadingSrc: loadingSrc.value,
      error: errorMsg.value || null,
      fullscreen: fullscreen.value,
    },
    // 采集时刻，便于判断是否是陈数据
    at: Date.now(),
  }
}

/**
 * 刷新面板数据。
 *
 * 用 rAF 太耗（每秒 60 次重排 DOM 没必要），调试面板 4Hz 足够；
 * 而且只在打开时才跑，关掉零开销。
 */
const debugSnapshot = ref<ReturnType<typeof collectDebug>>(null)
let debugTimer: number | undefined

function refreshDebug(immediate = false) {
  if (!debugOn.value) {
    if (debugTimer) clearInterval(debugTimer)
    debugTimer = undefined
    ;(window as any).__playerDebug = null
    return
  }
  debugSnapshot.value = collectDebug()
  // MCP 直接读全局变量，不必截图
  ;(window as any).__playerDebug = debugSnapshot.value
  if (debugTimer) return
  if (!immediate) return
  debugTimer = window.setInterval(() => {
    if (!debugOn.value) {
      if (debugTimer) clearInterval(debugTimer)
      debugTimer = undefined
      return
    }
    debugSnapshot.value = collectDebug()
    ;(window as any).__playerDebug = debugSnapshot.value
  }, 250)
}

// ---------------------------------------------------------------- setup

/**
 * 起播看门狗句柄；null 表示没在等。
 *
 * ★ 声明必须在使用者（setupSource）之前：`let` 有暂时性死区，
 *   写在后面会让 setupSource 一执行就抛 "Cannot access before initialization"。
 */
let startupTimer: number | null = null
/**
 * 假死看门狗（12 秒无进展）。
 *
 * 与 {@link startupTimer} 分开是因为判据完全不同：
 *   · startupTimer（20s）：连 metadata 都没有 → 源站连不上 / TLS 挂住
 *   · stallTimer（12s）：**已有 metadata 但 currentTime 恒 0** → 地址可达、
 *     分片却拉不下来（实测 404 的 m3u8 就是这种：936ms 拿到 metadata 后假死）
 */
let stallTimer: number | null = null

/**
 * 本次换源是否已经"hls 判错 → 回退原生"过。
 *
 * <p>只在 hls.js 报 `manifestParsingError`（拿到的不是清单）时用一次：
 * 猜错方向（把单文件当清单）不该反复重建 MediaSource，也不能掩盖真坏源。
 */
let nativeSrcFallbackTried = false

function clearStartupWatchdog() {
  if (startupTimer !== null) {
    clearTimeout(startupTimer)
    startupTimer = null
  }
  if (stallTimer !== null) {
    clearTimeout(stallTimer)
    stallTimer = null
  }
  clearProgressWatchdog()
}

/** 起播预算是否已经因为"清单已到"放宽过一次 */
let startupExtended = false
/**
 * 看门狗总硬上限。
 *
 * <p>续期只解决"慢"，不能解决"死"：源站真挂了必须报错，
 * 否则界面永远转圈（比报错更糟）。45s ≈ 35s 起播 + 10s 余量，
 * 而实测 4K 冷起首帧 10~14s，正常源不会碰到这个上限。
 */
const WATCHDOG_MAX_MS = 45_000

/**
 * 起播预算（#16）：父组件按线路健康传值，0/缺省 = 历史默认 20s。
 * 下限 3s：再短会把"冷启动 4 秒的正常源"误杀。
 */
function startupBudgetMs(): number {
  const v = props.startupTimeoutMs
  return typeof v === 'number' && v >= 3_000 ? v : 20_000
}

/** 假死预算（#16）：0/缺省 = 历史默认 12s。 */
function stallBudgetMs(): number {
  const v = props.stallTimeoutMs
  return typeof v === 'number' && v >= 2_000 ? v : 12_000
}

/**
 * 清单已拿到（MANIFEST_PARSED）→ 起播预算放宽到 35s。
 *
 * <p>原来只有 20s 的固定预算：源站冷启动慢一点（4K 实测 10~14s）
 * 加上代理一跳，就会被判死；而同一时刻原生播放器等 30s 照样能起播——
 * 用户看到的就是"报错，但用原生播放器能放"。
 *
 * <p>#16 短预算线路也放宽：清单都打开了说明线路活着，剩下的首帧慢
 * 交给假死看门狗（短预算仍在），不在这里把慢源误杀。
 */
function extendStartupForManifest() {
  if (startupExtended) return
  const target = Math.max(35_000, startupBudgetMs())
  const rest = target - (performance.now() - loadStartedAt.value)
  if (rest <= 5_000) return
  startupExtended = true
  if (startupTimer !== null) clearTimeout(startupTimer)
  startupTimer = window.setTimeout(onStartupTimeout, rest)
}

/** 有分片进来 → 假死窗口重新计（默认 12s，短预算线路 8s）；总时长仍受 WATCHDOG_MAX_MS 约束 */
function extendStallForFragment() {
  const rest = Math.min(stallBudgetMs(), loadStartedAt.value + WATCHDOG_MAX_MS - performance.now())
  if (rest <= 2_000) return
  if (stallTimer !== null) clearTimeout(stallTimer)
  stallTimer = window.setTimeout(onStallTimeout, rest)
}

/** 源地址的 host（上报用，不带 query/token） */
function srcHost(): string {
  try {
    return new URL(props.src).host
  } catch {
    return ''
  }
}

/**
 * 播放点落进缓冲区空洞 → 跳到下一段可播范围。
 *
 * <p>为什么需要：hls.js 的 `maxBufferHole` 只处理 0.5s 以内的空隙；源分片
 * 时间戳不连续（变长分片、录制源、token 中途失效后重连）会留下更大的洞。
 * 播放点卡在洞里时画面永远不动，而 ERROR 是**非致命**的
 * `bufferStalledError` —— 以前只记录不处理，用户看到的就是
 * 「某一段之后一直缓冲，也不是网络抖动」。
 *
 * <p>只跳 ≤10 秒的洞：更大的洞说明流本身断了，交给换线。
 */
function tryNudgeOverHole(): boolean {
  const v = video.value
  if (!v) return false
  if (holeNudges >= 3) return false
  const b = v.buffered
  const t = v.currentTime
  let target = -1
  for (let i = 0; i < b.length; i++) {
    const s0 = b.start(i)
    const e0 = b.end(i)
    if (t >= s0 - 0.05 && t < e0 - 0.05) {
      // 在区间内：只有贴着末端才考虑下一段（否则是解码/性能问题，不是洞）
      if (e0 - t <= 0.3 && i + 1 < b.length) target = b.start(i + 1)
      break
    }
    if (s0 > t + 0.05) {
      target = s0
      break
    }
  }
  if (target < 0) return false
  const gap = target - t
  if (gap <= 0 || gap > 10) return false
  holeNudges++
  try {
    v.currentTime = target + 0.05
  } catch {
    return false
  }
  try {
    hls.value?.startLoad()
  } catch {
    /* 引擎销毁中 */
  }
  lastHlsError.value = `跳过缓冲空洞 ${gap.toFixed(1)}s（${holeNudges}/3）`
  reportPlayerEvent({
    kind: 'buffer_hole',
    site: props.site,
    vod: props.vodId,
    ep: props.title,
    pos: t,
    gap,
    host: srcHost(),
  })
  return true
}

/** 播放中进度看门狗：暂停/后台/未就绪时都不判。 */
function armProgressWatchdog() {
  clearProgressWatchdog()
  lastProgressAt = 0
  lastProgressTime = 0
  stallRetries = 0
  progressTimer = window.setInterval(() => {
    const v = video.value
    if (!v || v.paused || v.readyState < 2 || endedSent) return
    if (document.visibilityState === 'hidden') return
    const now = performance.now()
    if (Math.abs(v.currentTime - lastProgressTime) > 0.25) {
      lastProgressTime = v.currentTime
      lastProgressAt = now
      stallRetries = 0
      return
    }
    if (!lastProgressAt) {
      lastProgressAt = now
      return
    }
    if (now - lastProgressAt < 5_000) return
    if (tryNudgeOverHole()) {
      lastProgressAt = now
      return
    }
    // 没有可跳的洞：再拉一次取流，留一个 5 秒窗口；仍不动才报停滞。
    // 报 stall（不是 media）→ 父组件换线，绝不会拿它去触发转码。
    if (stallRetries < 2) {
      stallRetries++
      try {
        hls.value?.startLoad()
      } catch {
        /* 引擎销毁中 */
      }
      lastProgressAt = now
      return
    }
    reportPlayerEvent({
      kind: 'buffer_stall',
      site: props.site,
      vod: props.vodId,
      ep: props.title,
      pos: video.value?.currentTime || 0,
      msg: lastHlsError.value,
      host: srcHost(),
    })
    failPlay('播放卡住了：画面 5 秒没有前进（缓冲/分片断流），换一条线路试试', 'stall')
  }, 2_000)
}

function clearProgressWatchdog() {
  if (progressTimer !== null) {
    clearInterval(progressTimer)
    progressTimer = null
  }
}

/**
 * 起播超时：连可播画面都没拿到。
 *
 * 超时不是唯一形态：把播放器记录到的首个错误带出来，用户才知道是
 * 源站拒绝（403/418）、跨域、还是真的慢。只说"超时"会让人以为网络问题。
 */
function onStartupTimeout() {
  startupTimer = null
  const v = video.value
  if (!v) return
  if (v.readyState >= 2 || v.currentTime > 0) return
  const detail = lastHlsError.value
  if (detail) {
    failPlay(`无法播放：${detail}\n（该地址可能已失效，或源站拒绝本次请求）`,
      classifyHlsDetail(detail))
    return
  }
  // 没有播放器错误：基本就是慢或断
  failPlay('起播超时（清单/首帧一直没就绪）\n可能是源站太慢、地址已失效，或需要换线路',
    'timeout')
}

/** 假死超时：清单能开、分片一个都没进来（或原生直链拿到 metadata 但不出画面） */
function onStallTimeout() {  stallTimer = null
  const v = video.value
  if (!v) return
  if (v.currentTime > 0 || v.readyState >= 3) return
  const detail = lastHlsError.value
  const tail = detail ? `\n（${detail}）` : ''

  // HLS 路径：readyState 恒 0，只能看分片计数
  if (hls.value) {
    if (fragLoadedCount > 0) return
    failPlay(detail
      ? `无法播放：${detail}\n（清单能打开但分片全部失败，该线路可能已失效）`
      : '这条线路清单能打开，但 12 秒内一个分片都没加载成功',
    detail ? classifyHlsDetail(detail) : 'stall')
    return
  }
  // 原生路径：拿到 metadata 才算"假死"；连 metadata 都没有的交给起播那条
  if (v.readyState < 1) return
  failPlay(detail
    ? `无法播放：${detail}${tail}\n（地址可达但拿不到画面，该线路可能已失效）`
    : '这条线路拿到信息但一直没画面（12 秒无进展），换一条试试',
  detail ? classifyHlsDetail(detail) : 'stall')
}

/**
 * 挂两只看门狗。
 *
 * <p>抽成函数是因为它有两个调用点：正常换源，以及"hls.js 发现拿到的不是
 * 清单、回退原生播放"那一刻（那时 destroyHls 已经把定时器清了）。
 */
function armWatchdogs() {
  startupExtended = false
  if (startupTimer !== null) clearTimeout(startupTimer)
  if (stallTimer !== null) clearTimeout(stallTimer)
  startupTimer = window.setTimeout(onStartupTimeout, startupBudgetMs())
  stallTimer = window.setTimeout(onStallTimeout, stallBudgetMs())
  armProgressWatchdog()
}

function setupSource(url: string) {
  // 端内原生渲染（2026-11-27）：画面不经过 <video>，src 只是换片的坐标
  if (iosNative) return
  const v = video.value
  if (!v) return
  destroyHls()
  clearStartupWatchdog()
  // 每次换源都允许"hls 判错 → 回退原生"各一次
  nativeSrcFallbackTried = false
  // 换集/换线：上一集的播放位置绝不能带过来（否则切后台恢复会 seek 回前集）
  lastGoodPos = 0
  lastTickAt = 0
  lastTickPos = 0
  clearRestoreWindow()
  endedSent = false
  dropReported = false
  dropBaselineTotal = -1
  dropBaselineDropped = -1
  dropBadStreak = 0
  activeLevelUrl = ''
  // 新源要重新自动起播（见 onLoaded 里 didAutoStart 的注释）
  didAutoStart = false
  // 位置可信要等 onLoaded（metadata + 刷新续播 seek）：之前不弹片头提示
  posReady.value = false
  // 换源时缓冲冻结状态必须复位：否则上一集残留的"冻结中"会让新源起播后
  // 的自愈判据基于旧时间点，引擎要么白开一次、要么一直冻着
  dmFrozenForBuffer = false
  errorMsg.value = ''
  buffering.value = true
  // 起播耗时采集：从换源到第一帧真正可播
  loadStartedAt.value = performance.now()
  firstFrameMs.value = null
  fragLog.value = []
  lastHlsError.value = ''
  // 每次换源/重试都从零开始计次
  recoverAttempts = 0
  fragLoadedCount = 0
  holeNudges = 0
  stallRetries = 0
  staleTried = false
  // 观看时长也是每集重新计（父组件看到数值回落会把基准一起重置）
  watchedSec = 0
  watchedTickAt = 0
  lastSentWatched = 0
  /*
   * ★ 换源一定是同一个 <video> 元素（切线路），必须先把上一路掐断。
   *   只调 destroyHls 会出现"旧音轨 + 新画面"，见 stopMedia 的注释。
   *   掐断时临时静音了，这里立刻恢复用户的音量状态，否则新源也会是哑的。
   */
  stopMedia()
  /*
   * ★ 换源必须把「播放位置 / 时长」的响应式值一起清零（2026-11-20 修）。
   *
   * 切集时本组件是复用的：App.vue 对 /play/:site 只按 route.path 做 key，
   * 换集只改 query，PlayView 的 watch(route.fullPath) 重新 load() 后由
   * props.src 的 watch 进来。`stopMedia()` 的 `load()` 只把**元素上**的
   * currentTime 归零，这两个 ref 还停在上一集（实测 47:04 / 47:05）。
   *
   * 症状：新集 metadata 到达后 `inOutro` = 旧位置 >= 新时长 - 片尾长度
   * 恒为真（切集前就是真，watch 等不到 false→true 的变化）；起播的 play
   * 事件一进来 `watch(playing)` 命中片尾 → 立即跳 → 又 ended → 连跳
   * 9/10/11 直到最后一集，最后一集停在 duration-1 的黑屏（实测）。
   *
   * 跳过状态一起清：否则上一集的"用户已取消"会被带到新一集上。
   */
  currentTime.value = 0
  duration.value = 0
  lastMiddleEnd = -1
  introDismissed.value = false
  outroDismissed.value = false
  clearSkipTimer('intro')
  clearSkipTimer('outro')
  // 无声检测也跟着新源重来（见 armNoAudioWatch 的注释）
  clearNoAudioWatch()

  v.volume = volume.value
  v.muted = muted.value
  // 片库直连以 CORS 模式加载（WebAudio 无声探测的前提），见 applyNoAudioCrossOrigin
  applyNoAudioCrossOrigin()

  // 起播看门狗：有些源能返回地址但分片一直卡着（实测荐片 VIP线路 TLS 握手失败），
  // 播放器既不出 error 也不 readyState，界面就永远转圈。等够时间主动判死，
  // 让父组件去换线路。
  //
  // 判据/续期逻辑在 onStartupTimeout / onStallTimeout 里：清单已到会放宽到 35s，
  // 有分片进来会重置假死窗口（总上限 45s）。详见那两个函数的注释。
  armWatchdogs()

  const lower = url.toLowerCase()
  /*
   * 不能只看 `.m3u8`：很多源给的是 `wmm.php?...` / `api.php?...`，
   * 内容是清单但地址里没有扩展名。判据收口在 looksLikeHls（见那边的注释）：
   * Android 保持原生嗅探，PC 才按"无扩展名 → 当清单试"。
   */
  const nativeHls = hasNativeHls(v)
  const isM3u8 = looksLikeHls(url, nativeHls)
  // B 站等源返回 .mpd（DASH），必须用 dash.js，否则直接播会失败
  const isMpd = lower.includes('.mpd') || lower.includes('dash+xml')

  if (isMpd && typeof MediaSource !== 'undefined') {
    import('dashjs')
      .then((mod) => {
        const dashjs = (mod as any).default || mod
        if (!dashjs?.MediaPlayer) return
        const player = dashjs.MediaPlayer().create()
        player.initialize(v, url, false)
        dash.value = player
      })
      .catch(() => {
        // dash.js 加载失败时退回浏览器原生（Safari 支持原生 DASH）
        v.src = url
      })
    return
  }

  if (isM3u8 && Hls.isSupported()) {
    /*
     * 缓冲按设备分档（见 playbackPolicy.hlsBufferProfile）：
     * 原来写死 180/300 秒 / 120MB / 60 秒回看，4K 实测 renderer 469MB、
     * GPU 私 590MB；退出 30 秒仍留 244MB/176MB。真正压内存的是缓冲秒数，
     * 不是 maxBufferSize（它只参与反推目标长度）。
     *
     * 2026-11-03 的三档对照（47 分钟 VOD，2.1Mbps）：
     *   A 60/120/60MB → 领先 87s，堆内存 3MB
     *   B 180/300/120MB → 领先 188s，堆内存 15MB
     *   C 240/600/200MB → 领先 235s，堆内存 19MB
     * 现在按设备选 A/B 之间：TV/2GB 用 60/120，4GB 用 90/150，
     * 桌面用 120/180；回看统一 30 秒（回拖超过 30 秒才重新拉分片）。
     * 不做全片预缓冲：40 分钟 × 2Mbps ≈ 600MB，会把 MediaSource 撑爆
     * （bufferAppendError 直接挂播放器），那比偶尔卡一下更糟。
     */
    const buf = hlsBufferProfile(
      (navigator as unknown as { deviceMemory?: number }).deviceMemory,
      isTvShell(),
    )
    const h = new Hls({
      maxBufferLength: buf.maxBufferLength,
      maxMaxBufferLength: buf.maxMaxBufferLength,
      maxBufferSize: buf.maxBufferSize,
      backBufferLength: buf.backBufferLength,
      // 起播等 2 个分片，避免刚开播就 underrun
      maxBufferHole: 0.5,
      // 分片请求失败自动重试（源站经常瞬时 5xx）
      manifestLoadingMaxRetry: 4,
      levelLoadingMaxRetry: 4,
      fragLoadingMaxRetry: 6,
      fragLoadingRetryDelay: 800,
      // 带宽估算用滑动平均，弱网下不会一路降到最低码率
      abrEwmaDefaultEstimate: 1_000_000,
      startLevel: -1,
      enableWorker: true,
      lowLatencyMode: false,
    })
    // 清单已到手 → 起播预算放宽（20s → 35s）。注册在 loadSource 之前，
    // 不依赖事件时序（事件本身是异步的，但先挂上更稳）。
    h.on(Hls.Events.MANIFEST_PARSED, () => extendStartupForManifest())
    /*
     * 记录 hls.js 当前选中的变体地址（2026-11-24）。
     *
     * 母清单交给 ffmpeg 时它只取**第一个**变体（实测是 1080p），
     * 转封装出来就降了分辨率。把播放器正在用的这一档（4K）传过去，
     * 后端 copy 的才是用户实际在看的那一路。
     */
    h.on(Hls.Events.LEVEL_SWITCHED, (_e, data: { level: number }) => {
      const raw = h.levels?.[data.level]?.url as unknown
      const lu = Array.isArray(raw) ? String(raw[0] || '') : String(raw || '')
      if (!lu) return
      try {
        activeLevelUrl = new URL(lu, url).toString()
      } catch {
        activeLevelUrl = lu
      }
    })
    h.loadSource(url)
    h.attachMedia(v)
    // 分片加载耗时：定位"卡在哪个分片"最直接的数据。
    // 只留最近 8 条，避免长时间播放把内存撑大。
    h.on(Hls.Events.FRAG_LOADED, (_e, data: any) => {
      /*
       * ★ 成功加载一个分片 → 恢复计数归零（2026-10-31 修"偶现无法播放"）。
       *
       * 原来 recoverAttempts 只在 setupSource 时清零，之后**只增不减**。
       * 于是长片播放中零星几次网络抖动（地铁进隧道、Wi-Fi 漫游、
       * 源站偶发 5xx）会一点点累积，累计到 3 次就把整条线路判死 →
       * 自动换线或直接报错。用户看到的是"刚才还好好的，忽然就播不了"，
       * 而如果那一刻网络正好恢复，又显得"偶发"。
       *
       * 正确语义应该是"**连续**失败 3 次"，中间只要成功过一次就说明
       * 这条线路本身是通的。
       */
      recoverAttempts = 0
      fragLoadedCount++
      // 有分片进来 → 假死窗口续期（起播阶段 currentTime 还是 0）。
      // 必须写在下面的 `return` 之前：ms 取不到时也要续期。
      if (v.currentTime === 0) extendStallForFragment()
      // 带宽估计一直在变：1x 以上时每秒重算一次档位上限（节流，避免频繁换档）
      if (v.playbackRate > 1.01) {
        const at = performance.now()
        if (at - lastAbrAt > 1_000) {
          lastAbrAt = at
          applyAbrForRate(v.playbackRate)
        }
      }
      const ms = Math.round(data?.frag?.stats?.loading?.end - data?.frag?.stats?.loading?.start || 0)
      if (!Number.isFinite(ms) || ms <= 0) return
      fragLog.value.push({ t: +v.currentTime.toFixed(0), ms, level: h.currentLevel, ok: true })
      if (fragLog.value.length > 8) fragLog.value.shift()
    })
    h.on(Hls.Events.ERROR, (_e, data) => {
      // 致命与非致命都要记：诊断层要解释的正是**致命**那一次。
      // 只在 !data.fatal 时写会让 lastHlsError 永远停在"历史上某次抖动"，
      // 真正把播放打断的错误反而缺席（docs/47 §2.5 前置修复 1）。
      lastHlsError.value =
        `${data.details}` +
        (data.response?.code ? ` (${data.response.code})` : '') +
        (data.fatal ? ' [fatal]' : '')
      /*
       * 猜错方向了：这个地址不是清单（MP4/TS 等单文件），hls.js 解析不了。
       *
       * <p>来源是 looksLikeHls 的第三条规则（无扩展名 → 按清单试）。
       * 代理对非清单内容是原样透传的，所以回退到原生 <video> 就能播。
       * 只回退一次：真坏源不该在这里反复重建 MediaSource。
       * 起播/假死看门狗要重新挂——destroyHls 会把它们清掉。
       */
      if (data.fatal && data.details === 'manifestParsingError' && !nativeSrcFallbackTried) {
        nativeSrcFallbackTried = true
        destroyHls()
        lastHlsError.value = ''
        v.src = url
        v.load()
        armWatchdogs()
        safePlay(v)
        return
      }
      /*
       * ★ 4xx 快速判死，必须写在 `if (!data.fatal) return` **之前**
       *   （2026-11-02 真机实测踩过）。
       *
       * hls.js 的 404 是"先发非致命错误 + 指数退避重试"（800ms、1.6s、3.2s…
       * 共 6 次），等到它发 fatal 时已经过去 20 多秒。第一版把这个判断写在
       * fatal 之后，等于永远等不到 —— 实测 404 流仍然是 20 秒才报错。
       *
       * 两个条件缺一不可：
       *   · 4xx（除 403）：404/410 表示文件真不在，重试不会变；
       *     403 常见于 Referer/时效 token，换一轮可能带上新头，保留重试。
       *   · `fragLoadedCount === 0`：一个分片都没成功过 —— 说明整条流是死的。
       *     若已经播过若干分片，中途个别 404 可能只是空洞，交给 hls.js 跳过，
       *     否则会把"能播的流"误杀。
       */
      const httpCode = data.response?.code || 0
      /*
       * 403/401 且已经成功播过分片 = 中途 token 过期（不是地址写错）。
       * 让父组件重新调 play 拿新 token，并在原位置续播；每条源只试一次，
       * 刷新失败就落回原来的 4xx/网络失败流程（换线）。
       */
      if ((httpCode === 403 || httpCode === 401) && fragLoadedCount > 0 && !staleTried) {
        staleTried = true
        lastHlsError.value = '地址已过期（403），正在刷新播放地址…'
        const at = video.value?.currentTime || currentTime.value
        reportPlayerEvent({
          kind: 'token_stale',
          site: props.site,
          vod: props.vodId,
          ep: props.title,
          pos: at,
          host: srcHost(),
        })
        emit('stale-src', { at, http: httpCode })
        return
      }
      if (!data.fatal && httpCode >= 400 && httpCode < 500 && httpCode !== 403
          && fragLoadedCount === 0) {
        failPlay(`无法播放：${data.details} (${httpCode})\n（源站返回 ${httpCode}，地址可能已失效）`, 'http')
        return
      }
      if (!data.fatal) {
        /*
         * bufferStalledError 是非致命的：hls.js 只是"发现播放点不动了"，
         * 不会自己跳过较大的洞。这里主动跳；跳不了就等进度看门狗兜底。
         */
        if (data.details === 'bufferStalledError') tryNudgeOverHole()
        return
      }
      /*
       * 502 且由我们的代理发出 → 上游把视频分片换成了图片（"假流"）。
       *
       * 实测 AppRJ：清单完全合法，603 个 .ts 打开全是 PNG 占位图。
       * 不做这个判定时用户看到的是 hls.js 的解码错误（"bufferAppendError"
       * 之类），既看不懂也没法行动。后端现在会回 502 + fakeStream 标记
       * （见 ApiServer 的 looksVideoUrl 判定），这里直接说人话并让上层换线路。
       */
      if (data.response?.code === 502) {
        failPlay('这条线路是假的（源站把视频换成了占位图），换一条线路', 'fake')
        return
      }
      /*
       * ★ 4xx 是永久错误，不重试（2026-11-02 实测）。
       *
       * 404/410 表示文件真的不在，重试 6 次 × 3 轮 ≈ 20 秒纯属空等；
       * 实测"清单 200 + 分片全 404"的流要白等满 20 秒才报错，
       * 用户只看到转圈。4xx 直接判死，把 20 秒压到 1 秒内。
       *
       * 403 例外：源站常见的"Referer 缺失/时效 token 过期"表现为 403，
       * 换一轮（startLoad）有机会带上新头，所以仍走重试。
       */
      const code = data.response?.code || 0
      if (code >= 400 && code < 500 && code !== 403) {
        failPlay(`无法播放：${data.details} (${code})\n（源站返回 ${code}，地址可能已失效）`, 'http')
        return
      }
      // 网络/媒体错误先尝试恢复，但要计次：
      // 原来无条件 startLoad()，遇到持续 5xx 的源会无限重连，
      // 既打不开也停不下来（表现为一直转圈，永远不出错误）。
      if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
        if (++recoverAttempts <= MAX_RECOVER_ATTEMPTS) {
          h.startLoad()
          return
        }
        failPlay(`网络错误：${data.details}`, 'network')
        return
      }
      /*
       * 致命 bufferStalledError 仍然不是"解不了编码"：先跳洞；
       * 跳不了按 stall 报错 → 父组件换线（转码对空洞无效）。
       */
      if (data.details === 'bufferStalledError') {
        if (tryNudgeOverHole()) return
        failPlay('播放卡住了：缓冲区出现断层（不是网络抖动），换一条线路试试', 'stall')
        return
      }
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
        if (++recoverAttempts <= MAX_RECOVER_ATTEMPTS) {
          h.recoverMediaError()
          return
        }
        failPlay(`解码错误：${data.details}`, 'media')
        return
      }
      failPlay(`${data.type} / ${data.details}`, classifyHlsDetail(data.details))
    })
    hls.value = h
  } else {
    v.src = url
  }
}

/** 连续恢复多少次仍失败，就判定这条线路不可用 */
const MAX_RECOVER_ATTEMPTS = 3
/** 当前这次加载已经尝试恢复过几次 */
let recoverAttempts = 0
/**
 * 本次换源成功加载过几个分片。
 *
 * <p>给假死看门狗用：HLS 路径下 `video.readyState` 在分片全失败时**恒为 0**
 * （MediaSource 没数据就不进 HAVE_METADATA），所以"有没有拿到画面"
 * 只能看这个计数。换源时在 setupSource 归零。
 */
let fragLoadedCount = 0
/** 缓冲区空洞自动跳过次数（每条源最多 3 次，防止在坏源上空转） */
let holeNudges = 0
/** 中途 403 已请求父组件刷新地址（每条源只试一次） */
let staleTried = false
/** 停滞后已重拉取流的次数（跳洞跳不下去时的第二级兜底） */
let stallRetries = 0
/** 播放进度看门狗（2 秒一次）：播放中 currentTime 长期不动 → 跳洞/重拉/报停滞 */
let progressTimer: number | null = null
let lastProgressAt = 0
let lastProgressTime = 0

/**
 * 从 hls.js 的错误串里猜出失败类别。
 *
 * <p>hls.js 只给 detail 字符串（`manifestLoadError (403) [fatal]`），没有结构化的
 * kind。这里按关键字分类，用途只有一个：决定"换线还是转码"。宁可归成 network
 * （换线，最多几秒），也不要错归成 media（白等 90 秒转码，TV 上还会弹
 * "找不到 ffmpeg"）。
 */
function classifyHlsDetail(detail: string): PlayErrorKind {
  const s = (detail || '').toLowerCase()
  const m = s.match(/\((\d{3})\)/)
  if (m) {
    const code = Number(m[1])
    if (code >= 400 && code < 500) return 'http'
    if (code >= 500) return 'network'
  }
  if (/bufferstalled|stalled/.test(s)) return 'stall'
  if (/bufferappend|decode|remux|append/.test(s)) return 'media'
  return 'network'
}

/**
 * 播放彻底失败。
 *
 * 交给父组件决定下一步（换线路 / 显示错误 / 转码），播放器只负责
 * 停止缓冲动画并暴露一个明确的可操作状态。
 *
 * <p>必须带 `kind`：父组件据此决定"换线还是转码"。只传一句话的话，
 * 一次网络抖动也会被当成"浏览器解不了这个格式"送去转码。
 */
function failPlay(detail: string, kind: PlayErrorKind = 'unknown') {
  /*
   * ★ 已缓冲播放中，迟到的网络类错误不打断（2026-11-15 修3）。
   *
   * 用户实测：视频正在播、缓冲还有一大段，一次"线路不通/超时"的提醒
   * 直接把错误页盖上来，连已缓冲的时长都看不成了。这类错误只说明
   * "**继续取流**失败"，不代表当前缓冲也废了——只要画面还能往前走，
   * 就静默重连（hls.startLoad），缓冲耗尽、取流仍失败时 hls 会再报
   * fatal，那时已无缓冲可护，照常走失败流程。
   *
   * 只豁免网络类 kind：fake/http 是地址级判死，media 是解码失败，
   * 缓冲救不了它们，维持原行为。
   */
  const v = video.value
  if (
    v &&
    v.currentTime > 0 &&
    (kind === 'network' || kind === 'timeout' || kind === 'stall') &&
    bufferedAheadSec(v) > 1
  ) {
    if (hls.value) {
      try {
        hls.value.startLoad()
      } catch {
        /* 引擎已在销毁中就算了 */
      }
    }
    extendStallForFragment()
    return
  }
  /*
   * ★ 先撤看门狗再报错（2026-11-02）。
   *
   * 原来这里不清，于是"hls.js 3 秒报了 fatal → failPlay → 父组件换线/转码"
   * 之后，20 秒的定时器还活着；到点若新线路仍未出画面，会**再发一次 error**，
   * 父组件于是再 addFailedLine、再触发一次转码。
   * 与 destroyHls() 里的清理保持一致：失败路径本来就应该收掉等待。
   */
  clearStartupWatchdog()
  buffering.value = false
  errorMsg.value = `播放失败：${detail}`
  emit('error', { kind, msg: errorMsg.value })
}

function destroyHls() {
  clearStartupWatchdog()
  if (hls.value) {
    hls.value.destroy()
    hls.value = null
  }
  if (dash.value) {
    try {
      dash.value.reset()
    } catch {
      /* 忽略 */
    }
    dash.value = null
  }
}

/**
 * 真正把媒体停下来并丢掉当前源。
 *
 * ★ 为什么 `hls.destroy()` 不够（2026-11-02，用户报「切下一集/换线路/返回
 *   重播后旧片的声音还在响，新片画面已经出来了」）：
 *   destroy 只是解绑 MSE、不再喂分片，`<video>` 里已缓冲的那一段会继续
 *   播到缓冲末尾（Android WebView 尤其明显）。换源时新源挂在**同一个**
 *   元素上，内核里会短暂存在两套解码状态；而换集时元素会被整体销毁，
 *   规范要求"等到稳定状态再暂停"，WebView 经常等不到 —— 声音就留着。
 *
 * 三步缺一不可：muted（立刻听不到）→ 摘 src（丢弃 MSE 引用）→
 * load()（让解码管线收尾，只摘 src 不 load 仍可能继续出声）。
 */
/** 彻底停播：hls.js 实例也要销毁，否则它还会往旧 MediaSource 里塞分片。 */
function stopPlayback() {
  destroyHls()
  stopMedia()
  errorMsg.value = ''
}

function stopMedia() {
  const v = video.value
  if (!v) return
  try {
    v.muted = true
    v.pause()
  } catch {
    /* 元素已不可用就算了 */
  }
  try {
    v.removeAttribute('src')
    v.load()
  } catch {
    /* 同上 */
  }
}

// ---------------------------------------------------------------- playback

/**
 * 顶栏返回（2026-11-27）。
 *
 * <p>端内原生渲染时**不能 router.back()**：控制层是独立 WKWebView，历史里
 * 只有这一条，退出去会落到首页（真机实报：点返回跳到了首页）。
 * 这种情况要通知原生关掉播放器，由父页面恢复。
 */
function goBackFromPlayer() {
  if (iosNative) {
    /*
     * 用户按的是「返回」，不是「关闭播放器」：带 back 标记关原生，父页面据此
     * 直接 router.back() 回片库/详情，而不是弹"已退出端内播放器"面板
     * （2026-11-27 用户实报：左上角返回后不该出现那个面板）。
     */
    iosNativeCmd('back')
    return
  }
  router.back()
}

function toggle() {
  if (iosNative) {
    // 端内原生渲染：状态以原生推送为准，别用本地 playing 反推
    iosNativeCmd(playing.value ? 'pause' : 'play')
    return
  }
  const v = video.value
  if (!v) return
  /*
   * ★ 不能只看 v.paused（2026-11-25 真机：首次从后台切回，双击暂停无效）。
   * iOS 后台恢复后元素状态可能和实际出声不同步：paused=true 但 timeupdate
   * 仍在推进。此时按"暂停"处理，否则 toggle 会去 play()，用户看到的就是
   * "双击没反应"。
   */
  if (mediaLooksPlaying(v)) {
    // 用户主动暂停：取消还没执行的"补播"，否则 800ms 后会被自动播回去。
    autoPlayPending = false
    // ★ 回前台的续播重试同样要撤（2026-11-25 真机：切后台用系统播放条恢复，
    //   回前台后双击暂停会被 250ms/1.2s 的重试拉回播放）
    userPausedAt = Date.now()
    bgKeepPlaying = false
    cancelResumeTimers()
    // 用户自己动了进度/播放状态：回前台恢复窗口立刻失效，别跟他抢
    clearRestoreWindow()
    /*
     * ★ 暂停浮层中央的播放按钮在屏幕正中间，和双击落点重叠。
     * WKWebView 会把双击的 click 迟到 2s 以上再补发，正好砸在这个按钮上
     * 直接 toggle 回来（真机：双击暂停后 2s 自动又播）。给它一个静默期。
     */
    overlayGuardUntil = Date.now() + 2500
    bgLog(`toggle→pause paused=${v.paused} ct=${v.currentTime.toFixed(1)} vis=${document.visibilityState}`)
    v.pause()
  } else {
    /*
     * 刚暂停不到 0.9s 的"play 请求"几乎都是迟到 click 造成的重复动作
     * （真机：双击暂停后立刻被播回去）。真要恢复，再点一次即可。
     */
    if (userPausedAt && Date.now() - userPausedAt < 900) {
      bgLog('toggle→play ignored (pause <900ms ago)')
      return
    }
    userPausedAt = 0
    bgLog(`toggle→play paused=${v.paused} ct=${v.currentTime.toFixed(1)} vis=${document.visibilityState}`)
    safePlay(v)
  }
}

/**
 * 单击/双击区分。
 *
 * 浏览器每次双击也会先派发一次 click，直接绑 @click 会"暂停→播放"闪一下，
 * 所以单击延迟 ~220ms 再执行，期间收到第二次点击就取消并当双击处理。
 *
 * ★ 这个延迟带来一个副作用：只要 click 进来了，220ms 后一定会 toggle。
 * 于是有两条路径会"顺手暂停"：
 *   1. 点画面空白只是想关掉浮层 → 浮层关了，视频也暂停了
 *   2. 长按 2 倍速松手 → 浏览器补发 click → 暂停
 * 用 suppressClick 标记统一挡掉这两类"不是想暂停"的点击。
 */
let clickTimer: number | undefined
/** 这次 click 不该触发播放/暂停（关浮层、长按结束） */
let suppressClick = false
/** suppressClick 的自动复位定时器（长按后浏览器不一定补 click） */
let suppressTimer: number | undefined

/**
 * 最近一次 stage pointerdown 时的控制栏可见性（"点击前"快照）。
 *
 * ★ 为什么不能用 click 到达时的 showCtrl（2026-11-16 在 MuMu 实测踩过）：
 *   Chrome 在 pointerup 之后、click 之前会补发一次合成 mousemove，而根节点
 *   有 `@mousemove="wake"` —— 它先把控制栏打开。于是 click 里读到的永远是
 *   "已显示"，触屏单击反而走 `hideControlBar()`，表现是「点一下，控制栏
 *   闪 222ms 就没了」（实测 222/223ms，鼠标与触摸都复现）。
 *   pointerdown 一定早于这次合成 mousemove，所以快照只能在这里取。
 */
let pressVisible = false
/**
 * 最近一次 stage pointerdown 的指针类型与时间。
 *
 * ★ 双击语义必须按**事件来源**分流，不能按设备（2026-11-17 用户口径）：
 *   触摸屏 PC 的 `isTouch` 恒为 true（`pointer: coarse` 或 `ontouchstart`），
 *   但用户插着鼠标时双击应当全屏（PC 语义），不是左右分区快进。
 *   dblclick 的 MouseEvent 本身没有 pointerType，只能沿用两次 pointerdown 里
 *   最近的那次；超过有效期（合成事件）就回退到设备判据。
 */
let lastDownPointerType = ''
let lastDownAt = 0
/**
 * 上面快照的时间戳。
 *
 * 遥控器 tv-nav 的 `el.click()` 和脚本派发的 MouseEvent 都**没有 pointerdown**，
 * 沿用旧快照会把上一次点击的状态当成这次的，所以超过有效期就回退到当前值。
 */
let pressAt = 0
/** 快照有效期：正常点击 pointerdown→click 只有几十毫秒，留足慢速点击余量 */
const PRESS_SNAPSHOT_MS = 800

/**
 * 点击级双击识别（不依赖 dblclick）。
 *
 * ★ iOS WKWebView 对手指双击常常只派发两次 click、从不发 dblclick
 *   （2026-11-25 真机报「第一击显示控制栏、第二击又把它收起来」：
 *   旧逻辑里每个 click 各自在 220ms 后 toggle，双击就退化成亮栏→收栏）。
 *   这里在第二次 click 落点与上一次相近且间隔够短时直接执行双击动作，
 *   并压掉随后可能补发的原生 dblclick。
 *
 * ★ Android WebView / 桌面 Chrome 的顺序是 click1 → click2 → dblclick：
 *   click2 先由这里执行动作，原生 dblclick 用 syntheticDblAt 去重，
 *   不会快进 20 秒或暂停两次。
 */
let lastTapAt = 0
let lastTapX = 0
let lastTapY = 0
/** 两次 click 之间的最大间隔（再长就按两次独立单击处理） */
const DOUBLE_TAP_MS = 320
/** 两次点击的落点容差（手指有抖动；超过就不是同一位置的双击） */
const DOUBLE_TAP_SLOP = 48
/** 自识别双击刚执行的时刻：随后补发的原生 dblclick 要吞掉 */
let syntheticDblAt = 0

/*
 * ★ pointerdown 级双击（2026-11-25 真机日志）：切回前台后 WKWebView 的
 * click 合成会被系统/转屏动画拖慢或吞掉，日志里一次"双击"的两条 click
 * 间隔 968ms、1131ms，永远落不进 320ms 窗口 → 首次切回怎么点都不暂停。
 * pointerdown 是触摸原始事件，不受 click 合成影响；在第二下按下时就执行
 * 双击动作，click 侧用 pointerDblAt 全部吞掉。
 */
let lastPTapAt = 0
let lastPTapX = 0
let lastPTapY = 0
let pointerDblAt = 0
/**
 * 这一时刻之前的 click 一律不处理。
 *
 * <p>★ 2026-11-25 真机：pointer 双击执行后，WKWebView 还会把两次点击的
 * click 迟到几百毫秒再补发；有的 click 落在 700ms 吞掉窗口之外，click 侧的
 * 自识别双击又执行了一次 → 暂停后立刻被"播回去"（前两次双击都这样，
 * 第三次没有迟到 click 就正常）。这里给一个更宽的静默期。
 */
let ignoreClicksUntil = 0
/**
 * 暂停浮层中央播放按钮的静默期。
 *
 * <p>真机日志：pointer 双击暂停后约 2s，WKWebView 才补发上一次点击的 click；
 * 它落在屏幕正中的播放按钮上（`@click.stop="toggle"`），绕过 stage 的点击
 * 守卫直接把视频播回去。双击暂停后 2.5s 内这个按钮不响应。
 */
let overlayGuardUntil = 0

function onPauseOverlayClick() {
  if (Date.now() < overlayGuardUntil) {
    bgLog(`overlay play click ignored left=${overlayGuardUntil - Date.now()}ms`)
    return
  }
  toggle()
}
/** 从后台回到前台的时刻：用于确认"首击是否被系统吞掉"的诊断日志 */
let resumedAt = 0

function maybeTouchPointerDouble(e: PointerEvent) {
  if (!isMobileForm() || isTvShell()) return
  if (e.pointerType === 'mouse') return
  const now = Date.now()
  const near =
    lastPTapAt > 0 &&
    now - lastPTapAt <= 420 &&
    Math.abs(e.clientX - lastPTapX) <= DOUBLE_TAP_SLOP &&
    Math.abs(e.clientY - lastPTapY) <= DOUBLE_TAP_SLOP
  if (resumedAt && now - resumedAt < 6000) {
    bgLog(
      `EV pdown dt=${lastPTapAt ? now - lastPTapAt : -1} near=${near} ` +
      `x=${Math.round(e.clientX)} y=${Math.round(e.clientY)}`,
    )
  }
  if (!near) {
    lastPTapAt = now
    lastPTapX = e.clientX
    lastPTapY = e.clientY
    return
  }
  // 第二下按下 = 双击：立刻执行，别等 click（可能几百毫秒后才来或不来）
  lastPTapAt = 0
  pointerDblAt = now
  syntheticDblAt = now
  ignoreClicksUntil = now + 1500
  clearTimeout(clickTimer)
  suppressNextClick()
  bgLog(`EV pointer-dbl x=${Math.round(e.clientX)} y=${Math.round(e.clientY)}`)
  touchDoubleTapAction(e as unknown as MouseEvent)
}


/** 标记"接下来那一次 click 不算单击"，400ms 后自动失效 */
function suppressNextClick() {
  suppressClick = true
  clearTimeout(suppressTimer)
  suppressTimer = window.setTimeout(() => {
    suppressClick = false
  }, 400)
}

function onStageClick(e: MouseEvent) {
  clearTimeout(clickTimer)
  /*
   * 锁定状态：画面点击只负责闪出解锁按钮，不切控制栏、不改播放状态。
   * 放在 suppressClick 之前，锁定时任何"迟到的 click"也一律吞掉。
   */
  if (locked.value) {
    suppressClick = false
    clearTimeout(suppressTimer)
    lastTapAt = 0
    flashLockHint()
    return
  }
  /*
   * pointer 级双击已经执行过动作：它带来的迟到 click 全部吞掉。
   * ★ 必须放在 suppressClick 之前：suppressClick 是一次性的，
   *   可能已经被更早的迟到 click 消费掉，之后的双击 click 就会漏进来
   *   再执行一次 toggle（真机：暂停后立刻又被播回去）。
   */
  if (Date.now() < ignoreClicksUntil) {
    const now = Date.now()
    if (isMobileForm()) {
      bgLog(
        `EV click swallowed x=${Math.round(e.clientX)} y=${Math.round(e.clientY)} ` +
        `left=${ignoreClicksUntil - now}ms`,
      )
    }
    suppressClick = false
    clearTimeout(suppressTimer)
    lastTapAt = 0
    return
  }
  // 关浮层/长按产生的 click 不当作播放暂停
  if (suppressClick) {
    suppressClick = false
    clearTimeout(suppressTimer)
    return
  }
  // pointerdown 级双击已经执行过动作：兜底（ignoreClicksUntil 之外的极端迟到）
  if (pointerDblAt && Date.now() - pointerDblAt < 2000) {
    lastTapAt = 0
    return
  }
  /*
   * ★ 鼠标走桌面语义（2026-11-16，MuMu .77 验证）：单击 = 暂停/播放。
   *
   * 为什么必须按"指针类型"而不是"设备能力"分流：TV 壳/模拟器的 WebView 报
   * `pointer: coarse`，`isTouch` 恒为 true —— 但用户可能插着鼠标（MuMu 的
   * 系统鼠标、外接 USB 鼠标）。只看 isTouch 会让鼠标点画面变成"切控制栏"，
   * 与 PC 的习惯相反。
   *
   * ★ 判据必须是严格 `=== 'mouse'`，不能用 `!== 'touch'`：
   *   MuMu 实测遥控器 tv-nav 的 `el.click()` 是空串、`dispatchEvent(new
   *   MouseEvent('click'))` 是 undefined —— 用反向判据会把这两类也当鼠标，
   *   遥控器 OK 键就从"唤出控制栏/聚焦"变成"暂停"（属于回退）。
   */
  const recentDown =
    Date.now() - lastDownAt < PRESS_SNAPSHOT_MS ? lastDownPointerType : ''
  /*
   * iOS：click 是 WKWebView 合成的 MouseEvent，`pointerType` 可能是 'mouse'
   * （即使手指点的），只信 pointerdown 的真实来源。iPad 接触控板时
   * pointerdown 会报 'mouse'，仍走桌面语义；手指点是 'touch'。
   */
  const mouseClick = isIosShell()
    ? recentDown === 'mouse'
    : (e as PointerEvent).pointerType === 'mouse'
  if (isTouch.value && !mouseClick) {
    /*
     * ★ 自识别双击（2026-11-25 iOS 真机，见 lastTapAt 注释）：
     *   第二次 click 与上一次的落点、间隔都在阈值内 → 这就是双击，
     *   撤掉第一击排下的"切控制栏"定时器，直接执行双击动作。
     *   不这样做时 iOS 永远走不到 onStageDblClick（WKWebView 不发）。
     */
    const now = Date.now()
    if (isMobileForm()) {
      bgLog(
        `EV click touch x=${Math.round(e.clientX)} y=${Math.round(e.clientY)} ` +
        `sinceLastTap=${lastTapAt ? now - lastTapAt : -1} locked=${locked.value}`,
      )
    }
    if (
      lastTapAt > 0 &&
      now - lastTapAt <= DOUBLE_TAP_MS &&
      Math.abs(e.clientX - lastTapX) <= DOUBLE_TAP_SLOP &&
      Math.abs(e.clientY - lastTapY) <= DOUBLE_TAP_SLOP
    ) {
      lastTapAt = 0
      clearTimeout(clickTimer)
      syntheticDblAt = now
      // 双击后浏览器可能再补发一次 click（Android WebView 实测），吞掉它
      suppressNextClick()
      if (isMobileForm()) bgLog(`EV click-dbl(src=clicks) x=${Math.round(e.clientX)} y=${Math.round(e.clientY)}`)
      touchDoubleTapAction(e)
      return
    }
    lastTapAt = now
    lastTapX = e.clientX
    lastTapY = e.clientY
    /*
     * 触屏单击（2026-11-12 用户口径，三区统一）：
     *   单击 = 只呼出/收起控制栏；
     *   双击 = 左区 -10s / 右区 +10s / 中区 暂停播放；
     *   长按 = 左区 2× 快退 / 右区 2× 快进 / 中区无动作。
     * 单击仍然延迟 220ms，用来区分双击；双击事件里会 clearTimeout，
     * 所以不会出现"先切控制栏再暂停"。双击动作本身不再等待。
     *
     * ★ 不让单击暂停：左右两区占了屏幕 2/3，点哪都可能"手滑一下视频就停"。
     *   暂停留给双击（明确意图）和按钮（看得见的入口）。
     *
     * ★ 可见性必须用 pointerdown 快照，不能用 click 时的 showCtrl：
     *   浏览器在 pointerup 后、click 前补发的 mousemove 会先把栏打开，
     *   click 读到"已显示"就会把它收起（闪 222ms 的由来，见 pressVisible 注释）。
     */
    const wasVisible =
      Date.now() - pressAt < PRESS_SNAPSHOT_MS ? pressVisible : showCtrl.value
    clickTimer = window.setTimeout(() => {
      if (wasVisible) hideControlBar()
      else wake()
    }, 220)
    return
  }
  clickTimer = window.setTimeout(() => {
    toggle()
  }, 220)
}

/** 收起控制栏（触屏单击第二下）。不改播放状态，也不碰面板。 */
function hideControlBar() {
  clearTimeout(hideTimer)
  showCtrl.value = false
  if (isTvEnv()) clearTvFocus()
}

// ---------------------------------------------------------------- 全屏锁定（移动端）

/**
 * 全屏锁定：屏蔽画面上的全部触摸手势，只留一个解锁按钮。
 *
 * <p>对齐主流 App（B 站/爱奇艺）的「锁」：躺着看片时手滑点到画面，不该
 * 暂停、快进或把控制栏弹出来。锁定状态下：
 *   <ul>
 *     <li>单击 / 双击 / 长按 / 竖滑 / 横滑一律忽略；</li>
 *     <li>点屏幕只在左侧闪出解锁按钮（1.6s 后自己收），点它才解锁；</li>
 *     <li>退出全屏自动解锁，避免下次进播放页还是锁着的。</li>
 *   </ul>
 * 只在触屏 + 全屏（含 iOS 伪全屏）出现；TV 靠遥控器，没有误触问题。
 */
const locked = ref(false)
/** 锁定状态下点屏幕后短暂显示解锁按钮 */
const lockHint = ref(false)
let lockHintTimer: number | undefined

/**
 * 视口是否横屏。
 *
 * <p>移动端进播放页会自动转横屏（`tryLandscape` → `pageLandscape`），这时画面
 * 已经铺满整个屏幕，但 `pseudoFs` 仍是 false —— 锁定按钮不能只认全屏 API，
 * 否则「自动横屏播放」时整条锁入口消失（2026-11-25 用户真机报）。
 */
const landscapeViewport = ref(typeof window !== 'undefined' && isLandscapeViewport())
function syncLandscapeViewport() {
  landscapeViewport.value = isLandscapeViewport()
}

/** 任一浮层展开：锁按钮要让位（右侧中部会压到面板） */
const anyPanelOpen = computed(
  () =>
    showEpPanel.value ||
    showLinePanel.value ||
    showDmPanel.value ||
    showMore.value ||
    showDmSearch.value ||
    showHelp.value,
)

/**
 * 端内原生渲染：控制层 WebView 可以整个收起来了吗（2026-11-27）。
 *
 * <p>透明 WKWebView 一直叠在 VLC 画面之上，合成器和主线程都要为它留预算；
 * 收走后弹幕/画面回到纯原生渲染（真机上是"一顿一顿"的关键一环）。
 *
 * <p>★ 判据宁可保守：只有**这一个 WebView 上确实没有任何可交互/要显示的
 * 东西**时才收 —— 锁屏解锁按钮、跳过片头/片尾、任何面板都在 webview 里，
 * 收早了用户就再也点不到（锁定态尤其致命：点屏由原生转发回来，但
 * `wake()` 在 locked 下直接 return，解锁按钮永远回不来）。
 */
const nativeLayerIdle = computed(
  () =>
    !showCtrl.value &&
    !locked.value &&
    !anyPanelOpen.value &&
    !inIntro.value &&
    !inOutro.value &&
    !!iosNative,
)

if (iosNative) {
  watch(
    nativeLayerIdle,
    (idle) => iosNativeCmd('bar', idle ? 0 : 1),
    { immediate: true },
  )
}

const canLock = computed(
  () =>
    isTouch.value &&
    !isTvShell() &&
    // 只给移动形态（手机短边 <480 / iOS 壳）：Windows 触摸屏全屏不该冒出锁按钮
    (compactViewport.value || isIosShell()) &&
    // 手机横屏（自动转屏后画面已铺满）也算"全屏"
    (fullscreen.value || pseudoFs.value || landscapeViewport.value),
)

function flashLockHint() {
  lockHint.value = true
  clearTimeout(lockHintTimer)
  lockHintTimer = window.setTimeout(() => (lockHint.value = false), 1600)
}

function toggleLock() {
  locked.value = !locked.value
  if (locked.value) {
    // 锁上后必须立刻收栏：锁定态只留右侧那一个解锁按钮
    clearTimeout(clickTimer)
    openPanel(null)
    showCtrl.value = false
    clearTimeout(hideTimer)
    flashLockHint()
    return
  }
  lockHint.value = false
  clearTimeout(lockHintTimer)
  wake()
}

// ---------------------------------------------------------------- 触屏 / 手势

/**
 * 是否触屏设备。
 *
 * 双击的含义要按设备分：PC 上双击=全屏（鼠标没有"左中右三区"的习惯），
 * 手机上双击左右两侧=快退/快进（B站/YouTube 的老习惯）。
 * 不做这个分流，PC 用户双击想全屏却会快进 10 秒。
 */
const isTouch = ref(
  typeof window !== 'undefined' &&
    (window.matchMedia?.('(pointer: coarse)').matches ||
      'ontouchstart' in window ||
      /*
       * IPA：WKWebView 在个别 iOS 版本上 `pointer: coarse` 会报 false，
       * 只靠触屏事件判断会落到"鼠标单击=暂停"分支（2026-11-25 用户报
       * IPA 单击暂停）。壳身份是确定的，直接算触屏。
       */
      isIosShell()),
)

/** 双击位置落点：左 1/3 / 中 / 右 1/3 */
type Zone = 'left' | 'mid' | 'right'
function zoneOf(e: MouseEvent): Zone {
  const el = e.currentTarget as HTMLElement | null
  const r = el?.getBoundingClientRect()
  if (!r || !r.width) return 'mid'
  const x = e.clientX - r.left
  if (x < r.width / 3) return 'left'
  if (x > (r.width * 2) / 3) return 'right'
  return 'mid'
}

/** 触屏双击动作：左区 -10s / 右区 +10s / 中区暂停。自识别与原生 dblclick 共用 */
function touchDoubleTapAction(e: MouseEvent) {
  // 横滑 seek 刚结束：浏览器可能把"松手 + 再点一下"判成双击，不响应
  if (Date.now() - hseekEndedAt < 350) return
  /*
   * 2026-11-25 用户口径：双击**不再**按左右分区快退/快进，只保留"暂停/继续"。
   * 快进/快退交给长按（长按左右区 2× 扫描），避免"想暂停结果跳了 10 秒"。
   */
  toggle()
}

/** 双击：触屏左右分区快进/快退，其余全屏 */
function onStageDblClick(e: MouseEvent) {
  clearTimeout(clickTimer)
  // 锁定状态：双击不响应（含 iPad 触控板产生的原生 dblclick）
  if (locked.value) return
  /*
   * ★ 吞掉"迟到的 click"（2026-11-16 手势实测踩过）。
   *
   * adb/触屏双击的事件顺序不总是 click1→click2→dblclick：WebView（Chrome 110）
   * 实测会在 dblclick 之后**再补发一个 click**，它排进 220ms 的 toggle 队列，
   * 结果双击恢复播放后 220ms 又被暂停（栈：Ga=clickTimer 回调 → toggle → v.pause）。
   * suppressClick 会吞掉紧随其后的那一次 click；400ms 内的"单击"本来就属于
   * 双击的一部分，吞掉语义正确。
   */
  suppressNextClick()
  // 横滑 seek 刚结束：浏览器可能把"松手 + 再点一下"判成双击，不响应
  if (Date.now() - hseekEndedAt < 350) return
  /*
   * 双击语义按**事件来源**，不按设备（2026-11-17 用户确认口径）：
   *   · 鼠标（含触摸屏 PC 插着鼠标）→ PC 语义：双击 = 全屏；
   *   · 手指 / 触控笔 → 触屏语义：左区 -10s / 右区 +10s / 中区暂停。
   * dblclick 没有 pointerType，沿用最近一次 pointerdown 的类型（800ms 内有效）；
   * 合成事件没有 pointerdown 时回退到设备判据（与原行为一致）。
   */
  const recent = Date.now() - lastDownAt < PRESS_SNAPSHOT_MS ? lastDownPointerType : ''
  const pointerMouse =
    (e as PointerEvent).pointerType === 'mouse' || recent === 'mouse'
  if (isTouch.value && !pointerMouse) {
    /*
     * 自识别双击已经处理过这次双击（click2 先到、dblclick 后到）：
     * 再执行一次会快进 20 秒/暂停两次，这里直接吞掉。
     * 500ms 足够覆盖 click2→dblclick 的派发间隔，又短于两次独立双击。
     */
    // 迟到窗口可能到 1s 以上（真机 click 合成被转屏拖慢），放宽到 1.5s
    if (Date.now() - syntheticDblAt < 1500) return
    touchDoubleTapAction(e)
    return
  }
  toggleFullscreen()
}

// -------- 长按快退/快进（触屏） --------

/**
 * 长按左右区 = 2× 快退/快进（2026-11-12 用户口径）。
 *
 * <p>复用「按住 ←/→ 扫描」（`startKeyScan`）：快进直接用 playbackRate=2，
 * 快退按绝对目标位置逐帧回退。这比另写一套 touch 定时器划算 ——
 * 净速度、弹幕速率、松手还原、右上角「2x 快进中」提示全都是现成的。
 *
 * <p>中间区不响应：控制栏交给单击，不在这里塞动作，避免"想唤出控制栏
 * 结果按住不动开始快进"。
 */
/** 这次长按是否真的启动了扫描（松手要抑制补发的 click） */
let longPressScanned = false
let longPressTimer: number | undefined
/** 长按起点，用于判断是"长按"还是"滑动手势" */
let pressStart = { x: 0, y: 0 }

function onStagePointerDown(e: PointerEvent) {
  // 锁定状态：不记快照、不起长按/手势，点屏只闪解锁按钮
  if (locked.value) {
    flashLockHint()
    return
  }
  /*
   * ★ 这里**不能**清恢复窗口：用户切回来第一下往往是点屏幕想唤出控制栏，
   *   清掉的话"内核稍后回退到后台起点"就没人纠正（2026-11-25 真机日志：
   *   窗口正好被这一下点按关掉）。真正的用户 seek（进度条/横滑）才清。
   */
  maybeTouchPointerDouble(e)
  // ★ 先取"点击前"快照再做任何判断：onStageClick 用它决定单击是呼出还是收起。
  //   pointerdown 一定早于浏览器在 pointerup 后补发的合成 mousemove（见 pressVisible）。
  pressVisible = showCtrl.value
  pressAt = Date.now()
  // 双击按事件来源分流的依据（见 lastDownPointerType 注释）
  lastDownPointerType = e.pointerType || ''
  lastDownAt = pressAt
  // 手势起点对触屏与鼠标都记录：横滑 seek 两端共用
  pressStart = { x: e.clientX, y: e.clientY }
  swipe.reset(e.clientX)
  hseekReset(e)
  if (!isTouch.value || e.pointerType === 'mouse') return
  clearTimeout(longPressTimer)
  const zone = zoneOf(e)
  longPressTimer = window.setTimeout(() => {
    // 已经在竖滑（调亮度/音量）就不要启动长按扫描
    if (swipe.active) return
    if (zone === 'mid') return
    const v = video.value
    // 暂停时快进扫描没有意义（playbackRate 不推进画面），两侧保持一致：
    // 只有播放中才响应，避免一侧动一侧不动
    if (!v || v.paused) return
    longPressScanned = true
    startKeyScan(zone === 'left' ? -1 : 1)
  }, 450)
}

/**
 * 竖滑手势：左右分区调亮度/音量（2026-11-03 用户要求）。
 *
 * <p><b>为什么必须走系统级</b>（用户原话「亮度、音量都走系统级，因为 tv
 * 已经屏蔽了音量控制，只保留了静音」）：
 * <ul>
 *   <li>TV 端遥控器音量键被旁路，页面上只剩静音开关——不加手势就没有
 *       任何调音量的入口。</li>
 *   <li>`video.volume` 是元素音量，退出应用后系统音量没变，用户会觉得
 *       "调了没用"。所以音量写 AudioManager.STREAM_MUSIC。</li>
 *   <li>亮度同理：CSS `filter: brightness()` 只能变暗，调不亮。</li>
 * </ul>
 *
 * <p><b>分区规则与双击快进保持一致</b>：左 1/3 亮度、右 1/3 音量、中间不响应
 * （中间留给单击暂停，避免"想暂停却调了音量"）。
 *
 * <p><b>为什么在 move 里增量应用而不是松手才应用</b>：拖动手势必须实时反馈，
 * 松手才变的话用户不知道拖到哪个程度了。写 pref 仍然只在松手时做一次
 * （localStorage 每帧写会卡）。
 */
const swipe = {
  /** 本次手势已经是竖滑（一旦判定就锁定，避免中途改判） */
  active: false,
  /** 'volume' | 'brightness' | '' */
  kind: '' as '' | 'volume' | 'brightness',
  startX: 0,
  startY: 0,
  /** 手势开始时的值（0–100） */
  baseValue: 0,
  /** 当前值（0–100），显示在浮层上 */
  value: 0,
  reset(x: number) {
    this.active = false
    this.kind = ''
    this.startX = x
    this.startY = 0
  },
}

/** 竖滑浮层（0–100）。null = 不显示 */
const swipeHint = ref<{ kind: 'volume' | 'brightness'; pct: number; system: boolean; note?: string } | null>(null)
let swipeHintTimer: number | undefined

/** 手势灵敏度：滑动多少像素走完 0–100%（半屏高度比较跟手） */
function swipeRange(): number {
  const vh = window.innerHeight || 640
  return Math.max(180, vh * 0.55)
}

function onStagePointerMove(e: PointerEvent) {
  // 锁定状态：锁定时才开始的手势不存在（pointerdown 已拦），这里只是兜底
  if (locked.value) return
  const isMouse = e.pointerType === 'mouse'
  // 横滑 seek 进行中：只更新预览（鼠标拖动需保持左键按下）
  if (hseek.active) {
    if (isMouse && !(e.buttons & 1)) {
      endHseek(true)
      return
    }
    updateHseek(e.clientX - hseek.startX)
    e.preventDefault()
    return
  }
  // 鼠标只在按住左键时参与手势；触屏要求 touch 环境
  if (isMouse) {
    if (!(e.buttons & 1)) return
  } else if (!isTouch.value) {
    return
  }
  const dx = e.clientX - pressStart.x
  const dyRaw = e.clientY - pressStart.y

  /*
   * 横滑判定先行（先于竖滑）：明显横向 → seek 手势。
   * 画面任何位置都可发起（主流播放器如此），面板打开时让位。
   */
  if (
    !swipe.active &&
    Math.abs(dx) > 16 &&
    Math.abs(dx) > Math.abs(dyRaw) * 1.2 &&
    duration.value > 0 &&
    !errorMsg.value &&
    !showEpPanel.value &&
    !showLinePanel.value &&
    !showDmPanel.value &&
    !showMore.value &&
    !showDmSearch.value
  ) {
    hseekEngage(dx)
    // 捕获指针：拖出画面边界仍持续更新，松手在边界外也能提交
    try {
      ;(e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId)
    } catch {
      /* 老内核不支持就算了 */
    }
    e.preventDefault()
    return
  }

  if (isMouse) return // 竖滑（亮度/音量）只给触屏

  if (!swipe.active) {
    // 判定为竖滑：竖直位移 > 16 且明显大于水平（1.2 倍），避免斜滑误判
    if (Math.abs(dyRaw) < 16 || Math.abs(dyRaw) < Math.abs(dx) * 1.2) return
    const w = window.innerWidth || 360
    const third = w / 3
    if (pressStart.x < third) swipe.kind = 'brightness'
    else if (pressStart.x > w - third) swipe.kind = 'volume'
    else return // 中间 1/3 不做竖滑（留给单击切控制栏）
    // 已在竖滑 → 取消长按（否则滑到一半突然开始快进）
    endLongPress()
    swipe.active = true
    swipe.startY = pressStart.y
    /*
     * 起点 = 系统值 → 上次手势值 → 兜底（2026-11-17 用户报「每次都从 50% 左右
     * 重新开始」）。浏览器读不到系统亮度（iOS 连系统音量也禁网页控制），
     * 原来亮度分支裸 fallback 50 → 每次手势都从 50 起步。手势值跨手势/
     * 跨集记忆在 localStorage，松手时写回（见 rememberGesture）。
     *
     * ★ 音量的记忆值只给 iPhone 用（那里 video.volume 被系统忽略，不可信）：
     *   PC/安卓浏览器上 video.volume 是**当前真实值**（滚轮/滑条都写它），
     *   若被记忆值压过，滚轮调到 30% 后一上手势会跳回旧记忆值——那等于
     *   修好手机却弄坏 PC 的连续性。
     */
    swipe.baseValue =
      swipe.kind === 'volume'
        ? (systemVolume() ??
          (IPHONE_NO_VOLUME ? gestureMemo('volume') : null) ??
          Math.round((video.value?.volume ?? 1) * 100))
        : (systemBrightness() ?? gestureMemo('brightness') ?? 50)
    // 这一下之后浏览器可能补发 click，不能当成"暂停"
    suppressNextClick()
  }

  const delta = ((swipe.startY - e.clientY) / swipeRange()) * 100
  const next = Math.max(0, Math.min(100, Math.round(swipe.baseValue + delta)))
  if (next === swipe.value) return
  swipe.value = next
  applySwipe(swipe.kind as 'volume' | 'brightness', next)
  e.preventDefault()
}

/** 竖滑浮层自动消失；iPhone 的「请用侧键」提示给长一点，仍会自动收。 */
function scheduleSwipeHintHide(ms = 900) {
  if (swipeHintTimer) window.clearTimeout(swipeHintTimer)
  swipeHintTimer = window.setTimeout(() => (swipeHint.value = null), ms)
}

/** 把当前手势值写进系统（拿不到系统接口时退回元素音量 / 只提示） */
/**
 * 音量手势期间临时接管音量 UI。
 *
 * ★ 2026-11-26 真机第二轮：常驻挂 MPVolumeView 会连播放页的系统音量条也压掉，
 *   而 KVO 回传在真机上没触发（按侧键依然什么都没有）。改成只在竖滑/底栏滑条
 *   操作时挂 1.2s，随手势结束摘掉 —— 侧键/控制中心走系统原生 HUD，不再被抑制。
 */
let volHudTimer: number | undefined
function pulseVolumeHud() {
  if (!isIosShell()) return
  setIosVolumeHud(true)
  if (volHudTimer) clearTimeout(volHudTimer)
  volHudTimer = window.setTimeout(() => {
    volHudTimer = undefined
    setIosVolumeHud(false)
  }, 1200)
}

function applySwipe(kind: 'volume' | 'brightness', pct: number) {
  if (kind === 'volume') {
    pulseVolumeHud()
    const ok = hasSystemVolume()
    if (!ok && IPHONE_NO_VOLUME) {
      /*
       * 没有系统音量桥的 iPhone（旧包/桥初始化失败）：网页改不了音量，
       * 只给一次短提示。★ 之前这里直接 return，**没排隐藏定时器**，
       * 浮层会一直挂在画面上（2026-11-25 用户截图）。
       */
      swipeHint.value = { kind, pct, system: false, note: '请用手机侧键' }
      rememberGesture(kind, pct)
      scheduleSwipeHintHide(1200)
      return
    }
    // 与底栏滑块同一个入口：元素音量 + 系统音量 + muted/volume 状态一起更新
    applyVolumePct(pct, { save: false })
    swipeHint.value = { kind, pct, system: ok }
  } else {
    const ok = setSystemBrightness(pct)
    // 拿不到系统亮度时，用一个遮罩把画面压暗（只能变暗，明说）
    cssDim.value = ok ? 0 : Math.max(0, (50 - pct) / 50)
    swipeHint.value = { kind, pct, system: ok }
  }
  rememberGesture(kind, pct)
  scheduleSwipeHintHide()
}

/**
 * 上次竖滑手势的落点（localStorage，0–100）。
 *
 * 为什么按 kind 分 key：亮度与音量互不相关，且系统桥可用的端（TV）以系统
 * 值为准，这里只是浏览器端「读不到系统值」时的记忆起点。
 */
const GESTURE_KEY = 'meow.gesture.' as const

function gestureMemo(kind: 'volume' | 'brightness'): number | null {
  try {
    const v = Number(localStorage.getItem(GESTURE_KEY + kind))
    return Number.isFinite(v) && v >= 0 && v <= 100 ? Math.round(v) : null
  } catch {
    return null
  }
}

function rememberGesture(kind: 'volume' | 'brightness', pct: number) {
  try {
    localStorage.setItem(GESTURE_KEY + kind, String(Math.round(pct)))
  } catch {
    /* 隐私模式写不进就算了 */
  }
}

/** iPhone/iPod 上网页音量控制被系统禁用；iPad 的桌面 UA 不在此列。 */
const IPHONE_NO_VOLUME = isIosPhone()

/** 无系统亮度能力时的兜底变暗层（0–1，0 表示不变暗） */
const cssDim = ref(0)

/*
 * ---------------------------------------------------------------- 横滑调进度（2026-11-16）
 *
 * 主流播放器的标配手势：按住画面横向拖动 → 实时预览目标时间 → 松手才真正 seek。
 * 触屏与鼠标（按住左键拖）都支持，三端同一套。
 *
 * 复用既有手势框架的约定：
 *   · 横滑判定与竖滑同款阈值（|dx|>16 且 |dx|>|dy|×1.2），横滑先行；
 *   · 一旦锁定横滑，取消长按扫描（450ms 的 2× 快进）与竖滑——手势只属于一个；
 *   · 松手必须 suppressNextClick：浏览器总会补发 click，否则"拖完一松手
 *     控制栏又弹出来/视频被暂停"（长按、竖滑都踩过的同一个坑）；
 *   · 面板（选集/线路/弹幕/更多）打开时不抢手势。
 * 灵敏度按片长自适应：整屏宽扫完全片的 1/6（实测 300px ≈ 70s @45min 片），
 * 长片封顶 1.2s/px（45 分钟片拖一屏 ≈ 9 分钟，跟手）。
 */
const hseek = {
  active: false,
  startX: 0,
  /** 手势开始时的播放位置（秒），seek 语义是"从按下时的位置增量调" */
  baseT: 0,
  target: 0,
}
/** 拖动中的预览气泡数据；null = 不显示 */
const hseekHint = ref<{ from: number; to: number; delta: number } | null>(null)
/** 横滑刚结束的时间戳：350ms 内的双击不响应（拖完立刻再点不该触发全屏/快进） */
let hseekEndedAt = 0

function hseekSens(): number {
  const d = duration.value || 0
  const w = window.innerWidth || 800
  return Math.max(0.1, Math.min(0.5, d / (w * 6)))
}

function hseekReset(e: PointerEvent) {
  hseek.active = false
  hseek.startX = e.clientX
  const v = video.value
  hseek.baseT = v?.currentTime ?? 0
  hseek.target = hseek.baseT
}

function hseekEngage(dxRaw: number) {
  hseek.active = true
  hseek.startX = pressStart.x
  hseek.target = hseek.baseT
  // 横滑优先级最高：长按扫描/竖滑都不能再抢
  endLongPress()
  endSwipe()
  suppressNextClick()
  updateHseek(dxRaw)
}

function updateHseek(dxRaw: number) {
  const d = duration.value || 0
  const max = d > 0 ? d - 0.2 : Number.MAX_SAFE_INTEGER
  let t = hseek.baseT + dxRaw * hseekSens()
  t = Math.max(0, Math.min(max, t))
  hseek.target = t
  hseekHint.value = { from: hseek.baseT, to: t, delta: t - hseek.baseT }
}

/** 松手提交。commit=false 表示取消（pointercancel 等），画面不动。 */
function endHseek(commit: boolean) {
  if (!hseek.active) return
  hseek.active = false
  hseekEndedAt = Date.now()
  hseekHint.value = null
  if (!commit) return
  const delta = hseek.target - hseek.baseT
  // 长拖会超过 engage 时的 400ms 抑制窗，松手前必须再压一次 click
  suppressNextClick()
  if (Math.abs(delta) < 0.5) return
  // 用户横滑 seek 是明确的操作：回前台的自动恢复窗口失效
  clearRestoreWindow()
  // 走 userSeek 而不是 seekTo：转码窗口（#17）只改 start=N 重转，不夹回已转边界
  userSeek(hseek.target)
  // 与按键 seek 同款中央提示，松手后给一次"跳到了哪"的确认
  flashSeekHint(Math.round(delta))
}

/*
 * ---------------------------------------------------------------- 鼠标滚轮调音量（Web/PC）
 *
 * 用户要求（2026-11-03）：「web、pc 鼠标滚动时，也要操作音量」。
 *
 * ★ 为什么在处理器里 `preventDefault` 而不是模板写 `@wheel.prevent`：
 *   面板打开时（选集/线路/更多）滚轮要留给面板自己滚，`.prevent` 是无条件
 *   拦截，会把面板的翻页也一起吃掉。必须"先判条件，再决定拦不拦"。
 * ★ 每次滚动的幅度按 deltaMode 归一化：Chrome 是像素（100/格），
 *   Firefox 是行（3/格），触控板是小数。统一折成"每格 ±5%"，否则触控板
 *   轻轻一划就把音量拉到 0。
 * ★ 节流到 40ms：触控板一次惯性滑动会连发几十个 wheel，
 *   不节流会疯狂写 prefs。
 */
let wheelAccum = 0
let wheelThrottleAt = 0
let wheelSaveTimer: number | undefined

function onStageWheel(e: WheelEvent) {
  // 触屏设备没有 wheel；有竖滑手势就行
  if (isTouch.value) return
  // 面板/弹框打开时滚轮留给它们自己滚（选集列表要能翻页）
  if (showEpPanel.value || showLinePanel.value || showDmPanel.value || showMore.value || showDmSearch.value) return
  if (errorMsg.value) return
  e.preventDefault()
  const now = Date.now()
  if (now - wheelThrottleAt < 40) {
    wheelAccum += e.deltaY
    return
  }
  wheelThrottleAt = now
  // 归一化：像素模式 100px≈1 格，行模式 3 行≈1 格
  const unit = e.deltaMode === 1 ? 3 : e.deltaMode === 2 ? 1 : 100
  const notches = (e.deltaY + wheelAccum) / unit
  wheelAccum = 0
  if (!notches) return
  // 向下滚 = 调小（与系统音量滚轮方向一致）
  const step = notches > 0 ? -5 : 5
  const base = systemVolume() ?? Math.round((video.value?.volume ?? 1) * 100)
  const next = Math.max(0, Math.min(100, base + step))
  applySwipe('volume', next)
  // 落盘延后 400ms：一次连续滚动只写一次
  if (wheelSaveTimer) window.clearTimeout(wheelSaveTimer)
  wheelSaveTimer = window.setTimeout(() => {
    // 用状态值而不是 v.volume：iOS 上元素音量赋值被忽略，读回来还是 1
    savePrefs({ volume: volume.value, muted: muted.value }, { silent: true })
  }, 400)
}

function endSwipe() {
  if (!swipe.active) return
  // 只在松手时落盘一次：localStorage 每帧写会卡顿
  if (swipe.kind === 'volume') {
    // 同上：iOS 的 v.volume 不可信，用状态值
    savePrefs({ volume: volume.value, muted: muted.value }, { silent: true })
  }
  swipe.active = false
  swipe.kind = ''
}

function endLongPress() {
  clearTimeout(longPressTimer)
  if (!longPressScanned) return
  longPressScanned = false
  // ★ 长按结束后浏览器还会补发一次 click，那一下绝不能当成"切控制栏"。
  // 否则长按快进 → 松手 → 控制栏又弹出来（同类问题实测复现过）。
  suppressNextClick()
  stopKeyScan()
}

function onStagePointerUp(e: PointerEvent) {
  if (locked.value) {
    // 锁定时 pointerdown 没记起点，不能让这里用旧 pressStart 判"滑动"
    clearTimeout(longPressTimer)
    return
  }
  if (hseek.active) {
    endHseek(true)
    return
  }
  if (swipe.active) {
    endSwipe()
    return
  }
  // 手指移动超过阈值就当滑动，取消长按
  const dx = Math.abs(e.clientX - pressStart.x)
  const dy = Math.abs(e.clientY - pressStart.y)
  if (dx > 12 || dy > 12) {
    clearTimeout(longPressTimer)
    endLongPress()
    return
  }
  endLongPress()
}

/** 系统打断手势（pointercancel）：横滑回退预览，不提交 seek */
function onStagePointerCancel() {
  if (locked.value) return
  if (hseek.active) {
    endHseek(false)
    return
  }
  if (swipe.active) endSwipe()
  endLongPress()
}

// ---------------------------------------------------------------- 横屏

/**
 * 触屏进播放页时尝试锁横屏。
 *
 * `screen.orientation.lock()` 只在全屏状态下有效（规范限制），iOS Safari 则
 * 完全不支持。所以这里不在进页面时强求——那会把用户直接顶进全屏。
 *   1. 进页面只尝试一次静默锁定，失败就显示竖屏提示
 *   2. 用户自己点全屏时，进全屏成功后再补一次锁定（此时规范允许）
 *   3. 都不行就交给手动旋转手机 + 竖屏提示
 * 整个过程静默失败，不该用弹窗打扰。
 */
const portraitHint = ref(false)
/** 用户手动关掉竖屏提示后不再打扰 */
let portraitHintDismissed = false

async function tryLandscape() {
  if (!isTouch.value) return
  /*
   * 原生壳：直接把 Activity 转横屏（2026-11-05 用户报「竖屏提示点着没反应」）。
   *
   * 原来这里只调 `lockLandscapeQuietly()`（标准 Screen Orientation API），
   * 而 WebView 里没有真全屏上下文，`lock()` 必然抛错 → 按钮点了等于没点。
   *
   * ★ 这里**不走** syncShellOrientation：它会给"全屏旋转"打标记，
   *   之后退出全屏就会强制转回竖屏 —— 而本函数是"进播放页/点提示时转横屏"，
   *   不涉及全屏，用户想回竖屏自己转手机即可（壳里方向没被钉住）。
   */
  if (window.innerHeight > window.innerWidth) {
    pageLandscape = true
    shellSetOrientation('landscape')
  }
  await lockLandscapeQuietly()
}

/**
 * 竖屏提示上的「全屏观看」：一步到位（真全屏 + 锁横屏）。
 *
 * <p>原来这个按钮只调 `tryLandscape()`。在**支持元素全屏的 WebView**（飞牛 App
 * 的 flutter_inappwebview 就是）里那只转了方向，App 自绘的标题栏和状态栏还在，
 * 用户点完仍觉得"没全屏"（2026-11-26 MuMu 实测：`document.fullscreenEnabled === true`，
 * `requestFullscreen()` → RESOLVED / ENTERED，标题栏与状态栏一起消失）。
 * 所以这里直接走 `toggleFullscreen()` —— 它内部已经是"真全屏，失败降级伪全屏"，
 * 成功后还会补一次 `lockLandscapeQuietly()`（规范只允许在全屏上下文里锁方向）。
 */
async function enterFullscreenFromHint() {
  dismissPortraitHint()
  if (document.fullscreenElement || pseudoFs.value) {
    await tryLandscape()
    return
  }
  await toggleFullscreen()
}

/**
 * 原生壳里：全屏 → 把 Activity 转横屏；退出全屏 → 跟随设备。
 *
 * <p><b>为什么必须走原生</b>：WebView 默认没实现
 * `WebChromeClient.onShowCustomView`，`requestFullscreen()` 必然被拒 ——
 * 页面只能退化成"伪全屏"，而竖屏下 1920×800 的片子只显示中间一条。
 * 壳里加了 `meowTv.setOrientation` 才能真的转过去（2026-11-05）。
 *
 * <p>只在**竖屏时**转：电视/横屏设备调用是空操作，不会把电视转成竖屏。
 * 退出时用 `unspecified` 而不是 portrait —— 用户可能本来就横着拿。
 */
/** 全屏自己把界面转成了横屏（退出全屏时要还原） */
let rotatedByFs = false
/** 进播放页时自动转的横屏（离开播放页时要还原，退出全屏不归它管） */
let pageLandscape = false

function shellSetOrientation(mode: 'landscape' | 'portrait') {
  const b = (window as unknown as { meowTv?: { setOrientation?: (m: string) => void } }).meowTv
  if (b && typeof b.setOrientation === 'function') b.setOrientation(mode)
  /*
   * iOS 壳：WKWebView 里没有可用的 Screen Orientation lock（只在真全屏上下文
   * 有效，而 iPhone 拿不到元素全屏），方向只能由原生转 —— 见 ShellOrientation.swift。
   */
  const ios = (window as unknown as { meowIos?: { setOrientation?: (m: string) => void } }).meowIos
  if (ios && typeof ios.setOrientation === 'function') ios.setOrientation(mode)
}

function syncShellOrientation(full: boolean) {
  if (full) {
    if (window.innerHeight > window.innerWidth) {
      rotatedByFs = true
      shellSetOrientation('landscape')
    }
    return
  }
  if (rotatedByFs) {
    rotatedByFs = false
    /*
     * 必须显式还成 portrait，不能用 unspecified。
     * 踩过（MuMu 实测）：进全屏时系统会把 user_rotation 改成 1（横屏），
     * 退出时 unspecified 是"跟随系统"，于是系统以为自己该横屏 —— 画面卡在
     * 横屏回不来。而我们只在**进入前是竖屏**时才转过，所以还原成竖屏是对的。
     */
    shellSetOrientation('portrait')
  }
}

/** 尝试锁横屏；任何失败都静默（用户手动旋转永远可用） */
async function lockLandscapeQuietly() {
  const so = screen.orientation as (ScreenOrientation & { lock?: (o: string) => Promise<void> }) | undefined
  if (!so?.lock) return
  try {
    await so.lock('landscape')
  } catch {
    /* 未全屏 / 浏览器不支持，交给竖屏提示与手动旋转 */
  }
}

function checkOrientation() {
  // 方向可能由原生壳转过（个别 WKWebView 版本的 resize 事件不一定来），
  // 这里顺带刷一次，isFsUi / canLock 才能跟上「自动横屏 = 全屏」。
  syncLandscapeViewport()
  if (!isTouch.value) return
  const portrait = window.innerHeight > window.innerWidth
  // 真全屏时浏览器会自己处理方向，不用打扰；
  // 但伪全屏（iOS 常见）拿不到方向锁，竖屏铺满会留大黑边，这时仍要提示。
  portraitHint.value = portrait && !portraitHintDismissed && (!document.fullscreenElement || pseudoFs.value)
}

function dismissPortraitHint() {
  portraitHintDismissed = true
  portraitHint.value = false
}

// ---------------------------------------------------------------- 连按加速

/**
 * 方向键两种手势。
 *
 * ### 单击（含快速连点）
 * 单次跳 5 秒，500ms 内继续点则累加（5→10→15…），停止 500ms 后归零。
 * 这是影视仓/B站的手感：想跳 30 秒不用点 6 次。
 *
 * ### 按住（e.repeat）
 * **以 2 倍速持续播放**，松手恢复到原倍速。这是用户明确要的行为：
 * 长按 ←/→ = 用键盘做"2x 快进/回退"，而不是每 30ms 跳一次。
 *
 * 为什么不能沿用"每次 keydown 都 takeSeekStep"：
 * 系统按住时每 30~50ms 重复一次，远小于 500ms 的累加窗口，
 * 步长会一路涨到 5→15→30→50→75→105→140→180→225→275→330→390 —— 
 * 实测按住 0.6 秒就冲到片尾（全片 635 秒）。所以按住必须走
 * 独立的"倍速扫描"分支。
 *
 * ### 为什么连按要封顶
 * 累加是三角数增长：按 n 次共前进 5×n(n+1)/2 秒。实测
 *   按 3 次 = 30 秒（合理）
 *   按 10 次 = 275 秒 ≈ 4.6 分钟（**已失控**，用户会找不到要看的位置）
 *   按 20 次 = 900 秒，直接顶到片尾
 * 而快速连点 10 下是很自然的操作。既然"长距离跳转"已经有按住 2x 扫动
 * 这个更精确的手段，连按就该专注"微调几秒到半分钟"。
 * 所以步长封顶 SEEK_MAX_STEP，按到第 4 下（20 秒）后不再增长。
 */
const SEEK_BASE = 5
/** 单步封顶：连按再多次，一次也只跳这么多 */
const SEEK_MAX_STEP = 20
const SEEK_CHAIN_MS = 500
let seekChain = 0
let seekChainAt = 0

/** 当前按键扫描方向：0=未扫描，1=快进，-1=快退 */
const keyScan = ref(0)
/** 扫描前的倍速，松手还原 */
let rateBeforeScan = 1

/**
 * 开始按键扫描（按住 ←/→）。
 *
 * 目标：**净速度恒为 2 倍速**，方向由按键决定。
 *
 * 快进：直接把 playbackRate 提到 2 让内核自己跑。比"每帧改 currentTime"
 * 好——音画同步、弹幕插值、缓冲区都自动正确，慢源上也不会反复 seek
 * 打断分片加载。
 *
 * 快退：2x 播放没法往回，只能自己往回 seek（见 tickBackwardScan）。
 */
function startKeyScan(dir: 1 | -1) {
  const v = video.value
  if (!v) return
  if (keyScan.value === dir) return // 已在同向扫描
  if (keyScan.value !== 0) stopKeyScan() // 换方向先复位

  keyScan.value = dir
  rateBeforeScan = v.playbackRate

  if (dir === 1) {
    v.playbackRate = 2
    dm.value?.setRate(2)
    return
  }
  tickBackwardScan()
}

/**
 * 快退扫描：按**绝对目标位置**推进，不靠"抵消前进量"。
 *
 * 早先的做法是每次 tick 读 currentTime 再减一截，但要正确抵消内核在这段
 * 时间里的自然前进量，否则实际速度会变成 3 倍（实测）——而 seek 是异步的，
 * 既读不到准确当前位置，也算不准推进了多少。全是脆弱的间接推理。
 *
 * 改成：记录扫描起点的视频时间和墙钟时间，之后每帧直接令
 *   target = startPos - 2 * (now - startWall)
 * 并把 currentTime 设为 target。这是**绝对定位**，与内核读数无关，
 * 暂停/播放、倍率、调度抖动都不影响结果。
 */
let scanRaf = 0
function tickBackwardScan() {
  const v = video.value
  if (!v) return
  const startPos = v.currentTime
  const startWall = performance.now()
  const step = () => {
    if (keyScan.value !== -1) return
    const el = video.value
    if (!el) return
    const target = startPos - 2 * ((performance.now() - startWall) / 1000)
    if (target <= 0) {
      el.currentTime = 0
      currentTime.value = 0
      stopKeyScan()
      return
    }
    el.currentTime = target
    currentTime.value = target
    scanRaf = requestAnimationFrame(step)
  }
  scanRaf = requestAnimationFrame(step)
}

/** 结束按键扫描，恢复原倍速 */
function stopKeyScan() {
  if (scanRaf) {
    cancelAnimationFrame(scanRaf)
    scanRaf = 0
  }
  if (keyScan.value === 0) return
  const wasForward = keyScan.value === 1
  keyScan.value = 0
  const v = video.value
  if (v && wasForward) {
    v.playbackRate = rateBeforeScan
    dm.value?.setRate(rateBeforeScan)
  }
}

/** 本次按键应该跳多少秒（正负号由调用方给） */
function takeSeekStep(): number {
  const now = Date.now()
  if (now - seekChainAt > SEEK_CHAIN_MS) seekChain = 0
  seekChain += 1
  seekChainAt = now
  // 封顶：避免连按十几次时一步跳几分钟（见 SEEK_MAX_STEP 的注释）
  return Math.min(SEEK_MAX_STEP, SEEK_BASE * seekChain)
}

function syncDm() {
  const v = video.value
  if (v) dm.value?.sync(v.currentTime)
}

/** 进度落盘节流：普通帧 5 秒一次；final 立刻发 */
let lastSent = -1
let lastSentFinal = 0
/** 上一次发给父组件的 watchedSec，用于"位置没变但又看了几秒"时穿透去重 */
let lastSentWatched = 0
/** 上一次跳过的中插段结尾，防止 seek 落地后重复触发同一次跳过 */
let lastMiddleEnd = 0
/**
 * 本集"真正播了多少秒"，随 progress 一起发给父组件。
 *
 * ★ 为什么不能用电量差值（`pos - lastPos`）统计观看时长
 *   （2026-11-02，用户第三次报「历史页还是无法统计播放时长」的真因）：
 *   进度心跳本来就是 **5 秒一次**（见 onTimeUpdate 的 `>= 5`），
 *   于是两次 `pos` 的差恒为 5.0~5.3 秒；而父组件的门禁是 `delta <= 5`，
 *   几乎每条都被当成"seek 大跳"丢掉 —— 累计结果永远是 0。
 *   正确做法是在**播放器内部**按真实墙钟累积：timeupdate 每 ~250ms 一次，
 *   只要"没暂停、没在 seek"就把这段时间记上。seek 期间不计，
 *   暂停期间不计，倍速播放按墙钟计（用户看片花的时间就是这个）。
 */
let watchedSec = 0
let watchedTickAt = 0

/**
 * 这一份源是否已经发过 ended。
 *
 * <p><b>为什么需要</b>（2026-11-03 核实：点「跳过片尾」后连播倒计时会重置，
 * `immediate` 模式下可能连跳两集）：
 * `skipOutroNow()` 先把 currentTime 挪到 `duration-1` 再 `emit('ended')`，
 * 但播放器不会停 —— 约 1 秒后自然播完，`@ended` 又发一次。
 * 第二次 `onEnded` 会 `startCountdown()` 先取消再重建（倒计时从 4 跳回 5），
 * 或再切一集。
 *
 * <p>换源时由 `setupSource()` 复位。
 */
let endedSent = false

function flushProgress(final = false, force = false, posOverride?: number) {
  const v = video.value
  if (!v) return
  /*
   * 已经发过 ended（跳过片尾/自然播完）之后，5 秒心跳不能再写进度：
   * 父组件已在 onEnded 里清掉进度，心跳会把片尾位置又写回去，
   * 下次进来就从片尾续播。
   */
  if (endedSent && !final) return
  const pos = posOverride ?? v.currentTime
  const dur = v.duration
  if (!Number.isFinite(pos) || pos < 0) return
  /*
   * 去重：同一位置不重复发（final 也做去重，避免切集时连发两条）。
   *
   * ★ 但"又看了几秒"必须能穿过去重（2026-11-02）：缓存命中的本地播放、
   *   或用户拖动后又拖回原处时，pos 可能长时间几乎不动，而墙钟一直在累计。
   *   只按 pos 去重会把这段观看时长整段丢掉。
   */
  const watchedAdvanced = watchedSec - lastSentWatched >= 1
  if (
    !force &&
    Math.abs(pos - lastSent) < 0.5 &&
    !watchedAdvanced &&
    !(final && Date.now() - lastSentFinal > 1000)
  ) return
  lastSent = pos
  lastSentWatched = watchedSec
  if (final) lastSentFinal = Date.now()
  emit('progress', {
    pos,
    dur: Number.isFinite(dur) ? dur : 0,
    final,
    watched: watchedSec,
  })
}

function onTimeUpdate() {
  const v = video.value
  if (!v) return
  currentTime.value = v.currentTime
  // "事件说暂停但实际在走"的判据（见 mediaLooksPlaying）
  lastTickAt = Date.now()
  lastTickPos = v.currentTime
  // 切后台要回到的位置：只认播放中推进过的值，避免后台被重置后读到 0
  if (!v.paused && v.currentTime > lastGoodPos) lastGoodPos = v.currentTime
  // 回前台恢复窗口内持续校正进度（内核可能在我们判定"已到位"之后才把流重置）
  onRestoreTick()
  // 最后一次"确实在播"的时刻：切后台时 iOS 可能先暂停再发 visibilitychange，
  // 光看 v.paused 会把这种情况判成"用户自己暂停"，回来就不续播（2026-11-25）。
  if (!v.paused) lastPlayedAt = Date.now()
  if (v.buffered.length) {
    buffered.value = (v.buffered.end(v.buffered.length - 1) / (v.duration || 1)) * 100
  }
  /*
   * 观看时长按墙钟累计（见 watchedSec 的注释）。
   *
   * 三条判据缺一不可：
   *   · 没暂停 —— 暂停时用户没在看
   *   · 没在 seek —— 拖进度条期间 currentTime 跳变，但墙钟只走了几百毫秒
   *   · 单次间隔 < 2s —— 后台标签页 / 系统挂起回来时 timeupdate 会隔很久，
   *     那段不算观看
   */
  const now = performance.now()
  if (!v.paused && !v.seeking && watchedTickAt) {
    const dt = (now - watchedTickAt) / 1000
    if (dt > 0 && dt < 2) watchedSec += dt
  }
  watchedTickAt = now
  // 欠载降速后的自动恢复（缓冲垫够深 + 观察够久）
  maybeRestoreRate(v)
  /*
   * 缓冲冻结的自愈：画面时间往前走了，就说明缓冲已经过去。
   *
   * 不能等 `playing`/`canplay`：它们只在"停过又起"时才派发，而 MSE 上
   * `waiting` 常常是误报（垫子还在，画面没停），一个后续事件都不会来。
   * 那时引擎会一直冻着，tick 不推进，只剩这里 4Hz 的 sync 重画 ——
   * 用户看到的弹幕就是每 250ms 挪一下的"一顿一顿"（2026-11-04 实测踩过）。
   */
  if (dmFrozenForBuffer && !v.seeking && v.currentTime > dmFrozenAt + 0.05) {
    buffering.value = false
    thawDanmakuFromBuffer()
  }
  // 弹幕位置完全由视频时间驱动：暂停、倍速、拖动都会自动对齐
  dm.value?.sync(v.currentTime)
  // 进度：每 5 秒发一次非 final 的心跳（只落本地，不触发同步）。
  // ★ 观看时长也要能触发心跳：源卡顿导致 currentTime 长时间不动时，
  //   墙钟累计仍在走，不能因为"位置没变"就不上报（会在切集时整段丢失）。
  if (Math.abs(v.currentTime - lastSent) >= 5 || watchedSec - lastSentWatched >= 5) {
    flushProgress(false)
  }
  // 中插：命中就直接跳到该段结尾。放在 timeupdate 里而不是监听 seek，
  // 因为拖动也可能落进中插区间，两个入口都要覆盖。
  // 跳完把 lastMiddleEnd 记下来，防止 seek 落地后被再次判定（区间是左闭右开，
  // 跳到 end 正好等于 end 已不满足 t < seg.end，不会自激）。
  if (activeMiddle.value) {
    const end = activeMiddle.value.end
    if (Math.abs(end - lastMiddleEnd) > 0.01) {
      lastMiddleEnd = end
      flashMarks(`已跳过中插 ${fmtClock(end - activeMiddle.value.start)}`)
      seekTo(end)
    }
  }
}

function onLoaded() {
  const v = video.value
  if (!v) return
  /*
   * ★ 空源触发的 durationchange 要忽略。
   *
   * stopMedia() 里的 `load()` 会派发一次 emptied + durationchange（duration=NaN）。
   * 不挡的话这里会把 duration 归零并 emit('loaded', {duration: 0})，
   * 父组件的续播恢复会拿 0 当基准 —— 每集开头都会丢掉续播点。
   *
   * 判据只能用 duration 是否为 NaN：hls.js 走 MSE，`src` 属性始终是空的、
   * `currentSrc` 是 blob:，按 src 判会把正常起播也挡掉（踩过）。
   */
  if (Number.isNaN(v.duration)) return
  duration.value = v.duration || 0
  /*
   * 位置以引擎为准：`setupSource` 已经清过 ref，这里再按元素真实位置校准一次，
   * 兜住任何没走 setupSource 的换源路径（否则旧位置 + 新时长会被算进片尾区间）。
   */
  if (Number.isFinite(v.currentTime)) currentTime.value = v.currentTime
  loadingSrc.value = false
  buffering.value = false
  // 拿到时长只说明清单解析成功，分片仍可能一片都拉不下来
  // （实测荐片 VIP线路：dur=2526 但 readyState 一直 0）。
  // 所以这里不清看门狗，交给看门狗自己按 readyState 判断。
  // 起播耗时：只在第一次记录，后面的 durationchange 不覆盖
  if (firstFrameMs.value === null && loadStartedAt.value > 0) {
    firstFrameMs.value = Math.round(performance.now() - loadStartedAt.value)
  }
  // 恢复用户上次的倍速（切集后也要生效）
  if (Math.abs(v.playbackRate - rate.value) > 0.01) v.playbackRate = rate.value
  // 恢复用户上次的音量
  v.volume = volume.value
  v.muted = muted.value
  // 通知父组件可以安全 seek 了（续播恢复挂在这里）
  emit('loaded', { duration: duration.value })
  // 直连源的「音频零解码」检测挂在这里：metadata 已到、hls/dash 有没有走
  // 已确定，起播后再按采样点判（见 armNoAudioWatch 的注释）
  armNoAudioWatch()
  /*
   * 刷新/重试的续播（用户口径 2026-11-18：⟳ 应该续播）。
   * 父组件 applyResume 有一次性守卫（resumeApplied），第二次 loaded 不会 seek，
   * 所以这里自己回到刷新前的位置；cap 到 duration-1，避免落在片尾触发自动下一集。
   */
  if (resumeAfterReload > 0) {
    const at = resumeAfterReload
    resumeAfterReload = 0
    const cap = duration.value > 1 ? duration.value - 1 : at
    if (at <= cap) v.currentTime = at
  }
  /*
   * 父组件要求的外部跳转（token 过期换新地址后续播）：
   * resumeAfterReload 是"刷新按钮"自己的位置，两者不叠加。
   */
  if (props.seekAt && props.seekAt > 1 && resumeAfterReload <= 0) {
    const want = props.seekAt
    const cap = duration.value > 1 ? duration.value - 0.5 : want
    try {
      v.currentTime = Math.min(want, cap)
    } catch {
      /* seek 失败不影响播放 */
    }
    emit('seek-applied')
  }
  // 到这里位置才可信：刷新续播已经 seek 完，片头提示不会误闪
  posReady.value = true
  // ★ 自动起播。
  //
  //   原来完全没有这一步：<video> 上没写 autoplay，也没人调 play()，
  //   于是"点进详情页 / 切集"之后画面停在第一帧、要手动按 ok 才开始播
  //   （实测 MuMu：进播放页后 paused=true，用户以为卡住了）。
  //
  //   放在 loadedmetadata 之后而不是 mount 时：此时清单已解析、时长已知，
  //   续播的 seekTo 也接在 emit('loaded') 里跑完了，不会出现"先播 0.5 秒
  //   再跳回续播点"的抖动。
  //
  //   ★ 前提是"用户本来就想看这一集"。若父组件因为错误/未就绪而暂缓，
  //     它会在 ready 后自己 seek，这里失败也无副作用（safePlay 吞掉拒绝）。
  //   默认开：不传 autoPlay 时按 true 处理（进详情/切集就是要看）。
  //
  //   ★ 为什么还要 800ms 后再补一次：
  //     实测 MuMu（Chrome 110）在 loadedmetadata 那一刻调 play() 没报错，
  //     但紧接着父组件的 seekTo（续播恢复）触发 hls 重载分片，内核把播放
  //     状态打回 paused 且**没有恢复**——最终停在 paused=true、currentTime
  //     不动，用户看到的就是"进播放页卡在第一帧"。
  //     等到 seek 落定后再补一次 play() 即可，若用户已手动暂停则不碰
  //     （用 autoPlayPending 标志，任何 pause 事件都会把它清掉）。
  //
  //   ★ 只在**本源第一次**拿到时长时跑（didAutoStart，setupSource 复位）。
  //     原来每次 durationchange 都重跑——而拖进度条/横滑 seek 会触发 hls
  //     清单位移带来后续 durationchange，把用户刚暂停的视频强拉回播放
  //     （2026-11-16 手势实测踩过：拖完单击暂停，0.5s 后自己又响了）。
  if (props.autoPlay && !didAutoStart) {
    didAutoStart = true
    if (v.paused) safePlay(v)
    autoPlayPending = true
    window.setTimeout(() => {
      const el = video.value
      if (autoPlayPending && el && el.paused) safePlay(el)
      autoPlayPending = false
    }, 800)
  }
  // HLS 的内嵌字幕要等清单解析完才有
  setTimeout(refreshTextTracks, 500)
  /*
   * 片长就绪/变化后，按片长重新过滤一次弹幕（2026-11-16）。
   *
   * 弹幕可能先于 metadata 到达（duration=0 时没法过滤），此时第一批是整个
   * 原始列表；等这里拿到真实片长，把超出片长的部分剔掉再重载渲染列表。
   * durationchange 会多次触发（清单位移/码率切换），所以用 dmAppliedDur
   * 挡住"片长没实际变化"的重复重建（重建会闪一下弹幕）。
   */
  if (dmRawList.length) {
    const d = duration.value
    if (d > 0 && Math.abs(d - dmAppliedDur) > 1) {
      const el = video.value
      applyDmList(dmRawList, dmTotal.value, { keepActive: true, at: el?.currentTime ?? 0 })
    }
  }
}

// ---------------------------------------------------------------- 进度条

/** 进度条实际显示位置：拖动中优先用拖动值 */
const shownTime = computed(() => (dragTime.value ?? currentTime.value))

const shownPct = computed(() =>
  shownDur.value > 0 ? (shownTime.value / shownDur.value) * 100 : 0,
)

/** 悬停/拖动时显示的时间提示 */
const dmHeatInline = computed(() =>
  dmHeat.value?.length ? danmakuHeatGradientFromCounts(dmHeat.value, dmHeatRef.value) : '',
)
/** 悬停时间点的桶条数（并进现有时间气泡，不额外做浮层） */
const dmHeatTipCount = computed(() => {
  const t = tipTime.value
  const c = dmHeat.value
  if (t == null || !c?.length || !dmHeatBucketSec.value) return null
  const i = Math.min(c.length - 1, Math.floor(t / dmHeatBucketSec.value))
  return c[i] ?? 0
})
/** 峰谷面积路径（连续山形，对齐参考图；不是一根根竖条） */
const dmHeatPath = computed(() =>
  dmHeat.value?.length ? danmakuHeatPath(dmHeat.value, dmHeatRef.value) : '',
)
/** 面积填充色与内嵌渐变同源（HEAT_RGB） */
const dmHeatFill = computed(() => `rgba(${HEAT_RGB},0.9)`)
const tipTime = computed(() => dragTime.value ?? hoverTime.value)
const tipLeft = computed(() => {
  if (tipTime.value == null || !shownDur.value) return 0
  const pct = Math.max(0, Math.min(100, (tipTime.value / shownDur.value) * 100))
  return pct
})

function timeFromEvent(e: PointerEvent | MouseEvent): number | null {
  const el = e.currentTarget as HTMLElement
  const r = el.getBoundingClientRect()
  if (!r.width || !shownDur.value) return null
  const pct = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width))
  return pct * shownDur.value
}

function onBarMove(e: PointerEvent) {
  // 拖动中用 pointer 位置更新，松手前也只改视觉
  const t = timeFromEvent(e)
  if (t == null) return
  if (dragTime.value != null) dragTime.value = t
  else hoverTime.value = t
}

function onBarLeave() {
  if (dragTime.value == null) hoverTime.value = null
}

/** 按下即开始拖：只更新视觉位置，不 seek */
function onBarDown(e: PointerEvent) {
  if (!duration.value) return
  // 用户要手动拖进度：回前台的自动恢复窗口失效
  clearRestoreWindow()
  const bar = e.currentTarget as HTMLElement
  bar.setPointerCapture?.(e.pointerId)
  const t = timeFromEvent(e)
  if (t == null) return
  dragTime.value = t
  hoverTime.value = t
}

/**
 * 松手才真正 seek。
 *
 * 之前绑在 `input` 上，每移动一像素就改一次 currentTime，
 * hls.js 每次都 abort 当前分片再重起，弱网下拖一下要缓好几秒。
 */
function onBarUp(e: PointerEvent) {
  const t = dragTime.value ?? timeFromEvent(e)
  dragTime.value = null
  hoverTime.value = null
  if (t == null) return
  userSeek(t)
}

/**
 * 时间轴密度带：按下只记位置，松手才 seek。
 *
 * <p>和进度条同一策略——拖动中每像素改 currentTime 会让 hls.js 反复
 * abort 分片，弱网下要缓好几秒。点一下 = down+up 同一位置，等价于点跳。
 */
function onHeatDown(e: PointerEvent) {
  const t = timeFromEvent(e)
  if (t == null) return
  ;(e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId)
  dragTime.value = t
  hoverTime.value = t
}

function onHeatUp(e: PointerEvent) {
  const t = dragTime.value ?? timeFromEvent(e)
  dragTime.value = null
  hoverTime.value = null
  if (t != null) userSeek(t)
}

/**
 * 老内核 / Android WebView 的触摸可能只合成 click、不派发 pointerdown
 * （投影仪 Chromium 66 实测：点热度条没反应）。click 兜底点跳；
 * 拖动路径仍走 pointerup，click 时 dragTime 已清空，不会重复 seek。
 */
function onHeatClick(e: MouseEvent) {
  if (dragTime.value != null) return
  const t = timeFromEvent(e)
  if (t != null) userSeek(t)
}

/**
 * 统一的音量入口：底栏滑块、右侧竖滑、鼠标滚轮都走这里。
 *
 * <p>必须有系统桥时写系统音量（TV 的 AudioManager / iOS 的 MPVolumeView）：
 * iPhone 上 `video.volume = x` 是静默无效的，只改元素音量等于假控件。
 */
function applyVolumePct(pct: number, opts: { save?: boolean } = {}) {
  const p = Math.max(0, Math.min(100, Math.round(pct)))
  volume.value = p / 100
  const v = video.value
  const sys = hasSystemVolume()
  if (v) {
    /*
     * 有系统音量桥时元素音量固定 1：TV 的 AudioManager 音量与元素音量是**相乘**的，
     * 两边都写 pct 会让实际响度变成 pct²（50% 听着像 25%）。iOS 的元素音量本来
     * 就被系统忽略，写 1 也无害。浏览器没有系统桥，仍然用元素音量。
     */
    v.volume = sys ? 1 : volume.value
    v.muted = p === 0
  }
  muted.value = p === 0
  if (sys) setSystemVolume(p)
  /*
   * 端内原生渲染：`<video>` 不加载播放，唯一能出声的是原生 VLC —— 静音必须
   * 走原生（系统音量）。滑块拖到 0 等同静音，拖回来要解除静音。
   * 不同步这一包的话，原生侧会停在上一次的静音标记上（2026-11-27 真机实报
   * "静音按了没反应、拉音量才有用"）。
   */
  if (iosNative) iosNativeCmd('mute', p === 0 ? 1 : 0)
  if (opts.save !== false) {
    // 高频项：静默落盘，不广播事件（否则会刷屏 WebDAV 同步）
    savePrefs({ volume: volume.value, muted: muted.value }, { silent: true })
  }
  return p
}

function onVolumeInput(e: Event) {
  pulseVolumeHud()
  applyVolumePct(Number((e.target as HTMLInputElement).value))
}

function toggleMute() {
  /*
   * 端内原生渲染（2026-11-27 真机实报"静音按钮无效"）：
   * 这里没有在播的 <video>，改 `v.muted` 等于改一个不存在的元素。
   * 静音交给原生（系统音量置 0 / 还原），本地只维护 UI 状态与偏好；
   * **不动 volume.value**，否则"取消静音"就找不回原来的音量。
   */
  if (iosNative) {
    muted.value = !muted.value
    iosNativeCmd('mute', muted.value ? 1 : 0)
    savePrefs({ muted: muted.value, volume: volume.value }, { silent: true })
    return
  }
  const v = video.value
  if (!v) return
  if (v.muted || v.volume === 0) {
    // 取消静音：恢复原音量；原来就是 0 则给 50%
    applyVolumePct(volume.value > 0 ? volume.value * 100 : 50)
  } else {
    v.muted = true
    muted.value = true
    savePrefs({ muted: muted.value, volume: volume.value }, { silent: true })
  }
}

function setRate(r: number) {
  if (iosNative) {
    iosNativeCmd('rate', r)
    rate.value = r
    return
  }
  const v = video.value
  if (!v) return
  v.playbackRate = r
  // hls.js 在换码率 / 重新加载分片时可能把 playbackRate 重置回 1，
  // 记住用户选的值，下次 ratechange 发现不一致就纠正回来
  wantedRate = r
  rate.value = r
  // 用户显式换倍速 → 取消"欠载临时降速"状态，按新倍速重新评估档位
  tempRateDropped = false
  applyAbrForRate(r)
  dm.value?.setRate(r)
  savePrefs({ rate: r }, { silent: true })
}

/** 用户期望的倍速；hls 内部改动时用它纠正 */
let wantedRate = getPrefs().rate || 1

/** 当前播放点之后连续可播的秒数（诊断与欠载判定共用） */
function bufferedAheadSec(v: HTMLVideoElement): number {
  for (let i = 0; i < v.buffered.length; i++) {
    if (v.buffered.start(i) <= v.currentTime && v.currentTime <= v.buffered.end(i)) {
      return v.buffered.end(i) - v.currentTime
    }
  }
  return 0
}

/**
 * 倍速会改变实际需要的码率，而 hls.js 的 ABR **不知道** playbackRate。
 *
 * <p>它按 1x 选档：估算带宽 3Mbps 时会选 2.8Mbps 的档（0.95 系数刚好过线）。
 * 1.25x 实际需要 3.5Mbps，于是每片都欠一点，缓冲垫缓慢耗尽——用户看到的
 * 就是"1x 正常，1.25x 播一会儿卡一下"。
 *
 * <p>做法：按 `码率 × 倍速 ≤ 带宽 × 0.8` 反推最高档，设 autoLevelCapping。
 * 0.8 留给抖动和代理一跳。回到 1x 就解除（-1）。
 */
function applyAbrForRate(r: number) {
  const h = hls.value
  if (!h || !h.levels?.length) {
    abrTrace.value = 'skip:no-hls'
    return
  }
  const bw = h.bandwidthEstimate || 0
  if (bw <= 0) {
    abrTrace.value = 'skip:no-bw'
    return
  }
  // 判据收口在 playbackPolicy.capLevelForRate（纯函数，有单测）
  const cap = capLevelForRate(h.levels, bw, r)
  h.autoLevelCapping = cap
  abrTrace.value = `r=${r} bw=${bw} cap=${cap} n=${h.levels.length} now=${h.autoLevelCapping}`
}

/**
 * 欠载兜底：连续两次缓冲且垫子见底 → 临时降到 1x。
 *
 * <p>为什么必须"临时"：用户明确选了 1.25x，静默改掉是骗他。
 * 所以给 HUD 提示，并在缓冲恢复 8 秒后自动回到他选的倍速。
 */
/** 最近一次倍速降档计算的输入/输出（诊断面板可见） */
const abrTrace = ref('')

let tempRateDropped = false
let tempRateAt = 0
let waitingSince = 0
let waitingHits = 0
let lastAbrAt = 0
const RATE_DROP_WINDOW_MS = 20_000

function maybeDropRateForUnderrun(v: HTMLVideoElement) {
  if (wantedRate <= 1.01 || tempRateDropped) return
  const now = performance.now()
  waitingHits = now - waitingSince < RATE_DROP_WINDOW_MS ? waitingHits + 1 : 1
  waitingSince = now
  // 判据收口在 playbackPolicy.shouldDropRate（纯函数，有单测）
  if (!shouldDropRate({
    wantedRate,
    dropped: tempRateDropped,
    hits: waitingHits,
    bufferedAhead: bufferedAheadSec(v),
  })) return
  tempRateDropped = true
  tempRateAt = now
  v.playbackRate = 1
  rate.value = 1
  dm.value?.setRate(1)
  flashMarks('网络跟不上，已临时恢复 1x')
}

function maybeRestoreRate(v: HTMLVideoElement) {
  if (!shouldRestoreRate({
    dropped: tempRateDropped,
    sinceMs: performance.now() - tempRateAt,
    bufferedAhead: bufferedAheadSec(v),
  })) return
  tempRateDropped = false
  v.playbackRate = wantedRate
  rate.value = wantedRate
  dm.value?.setRate(wantedRate)
  applyAbrForRate(wantedRate)
  flashMarks(`已恢复 ${wantedRate}x`)
}

/**
 * 冻结弹幕引擎（画面真的停在缓冲上时）。
 *
 * <p>只由 `waiting` 触发；`stalled` 不代表画面停了，别接进来。
 */
function freezeDanmakuForBuffer() {
  if (dmFrozenForBuffer) return
  dmFrozenForBuffer = true
  dmFrozenAt = video.value ? video.value.currentTime : 0
  dm.value?.pause()
}

/**
 * 解冻弹幕引擎，并对齐到当前画面时间。
 *
 * <p>两处调用：`playing`/`canplay`，以及 timeupdate 里的自愈（判据是
 * 画面时间真的往前走了）。不重复恢复：标志为假时直接返回。
 */
function thawDanmakuFromBuffer() {
  if (!dmFrozenForBuffer) return
  dmFrozenForBuffer = false
  const vv = video.value
  if (!vv) return
  // 缓冲期间可能换过档（hls.js 会把 playbackRate 打回 1），先校正再对齐
  dm.value?.setRate(vv.playbackRate)
  dm.value?.start()
  dm.value?.sync(vv.currentTime)
}

/** 全屏失败时给一句明确提示，不再静默无反应 */
const fsHint = ref('')
let fsHintTimer: number | undefined
function flashFsHint(msg: string) {
  fsHint.value = msg
  clearTimeout(fsHintTimer)
  fsHintTimer = window.setTimeout(() => (fsHint.value = ''), 2600)
}

/**
 * 伪全屏（fallback fullscreen）。
 *
 * 为什么不直接用 `video.webkitEnterFullscreen()`：它会把播放交给 iOS **原生
 * 播放器**，DOM 全被盖住——弹幕层和我们的控制栏一起消失，等于换了个播放器。
 * 所以 iOS 上宁可用「容器撑满视口」的伪全屏：视频还是这个 <video>，
 * 弹幕和控件都留在原地，唯一的差别是没有隐藏浏览器地址栏。
 */
const pseudoFs = ref(false)

/**
 * 界面上算不算"全屏"。
 *
 * <p>iOS 壳进播放页会自动转横屏（`pageLandscape`），横屏就是铺满态，但
 * `pseudoFs` 仍是 false。只认 pseudoFs/fullscreen 的话图标永远是"进入全屏"，
 * 点它只会再叠一层伪全屏、视觉无变化 —— 用户看到的就是「全屏按钮不生效」。
 */
const isFsUi = computed(
  () =>
    fullscreen.value ||
    pseudoFs.value ||
    (isIosShell() && !isTvShell() && landscapeViewport.value),
)

/**
 * 竖屏视口里把播放器硬转 90°（CSS 伪横屏）。
 *
 * <p><b>为什么需要</b>：飞牛 App 的 `MainActivity` 被 App 自己锁成竖屏
 * （2026-11-26 `dumpsys activity` 读到 `requestedOrientation=SCREEN_ORIENTATION_PORTRAIT`），
 * 所以 `screen.orientation.lock('landscape')` 必然失败、用户转手机也不会转。
 * 全屏后画面只能在竖屏里居中留黑边，只能把容器转 90° 才铺满。
 *
 * <p><b>为什么是转元素而不是换算坐标</b>：浏览器按变换后的矩阵做命中测试，
 * 控件、拖动、点击全部自动正确；弹幕 canvas 用 `container.clientWidth`
 * （布局尺寸，不受 transform 影响）取宽高，转完拿到的就是"横过来的"尺寸，
 * 不需要改绘制代码。
 */
const rotFs = computed(
  () => isFnosAppWebView() && (fullscreen.value || pseudoFs.value) && !landscapeViewport.value,
)

/**
 * 底栏「弹幕设置」（≡）按钮是否渲染。
 *
 * <p><b>口径（2026-11-27 用户定）</b>：不按设备判，按**是否有横向空间**判 ——
 * 竖屏尺寸不够就不显示，横屏/全屏后就该显示（IPA 上一直是这样，因为它真的转横屏）。
 *
 * <p>为什么不能再用 `sm:grid` 断点：那个断点看的是**视口宽度**。手机竖屏 440px
 * 命中不了 640，而飞牛 App 里的"横屏"是 CSS `rotate(90deg)`（`rotFs`），
 * **视口仍是 440×880** → `sm:` 永远不命中，全屏后按钮也不出来。
 * 所以显式把「真横屏」(landscapeViewport) 和「CSS 伪横屏」(rotFs) 都算上。
 *
 * <p>PC 的 `isTouch` 为 false，走「更多」菜单，行为不变。
 */
const showDmSettingBtn = computed(
  () => isTouch.value && (landscapeViewport.value || rotFs.value),
)
const rotStyle = computed<Record<string, string> | undefined>(() =>
  rotFs.value
    ? {
        position: 'fixed',
        top: '0',
        left: '0',
        width: '100vh',
        height: '100vw',
        transformOrigin: 'top left',
        // 业界通用写法：先转 90°，再沿自身 Y 轴把盒子推回可视区
        transform: 'rotate(90deg) translateY(-100%)',
        zIndex: '100',
      }
    : undefined,
)

/**
 * 全屏播放的空闲光标：3 秒不动就隐藏，任何指针活动立刻恢复。
 *
 * <p>为什么不能只靠 CSS `cursor: none`：WebView2 的全屏合成场景里样式层
 * 的 cursor 指令不一定传给窗口系统，指针会残留；桌面端额外调 Tauri 的
 * `setCursorVisible`。网页端仍用 CSS class（浏览器内足够）。
 *
 * <p>canHide 用 `(pointer: fine)` 而不是 `!isTouch`：带触摸屏的笔记本
 * 也可能有鼠标，`ontouchstart` 为真会让光标永远不隐藏。
 */
const finePointer = hasFinePointer()
const cursorIdle = createIdleCursor({
  isFullscreen: () => fullscreen.value || pseudoFs.value,
  canHide: () => finePointer,
  apply: (hidden) => {
    if (typeof document !== 'undefined') {
      document.documentElement.classList.toggle('meow-cursor-none', hidden)
    }
    if (isDesktopShell()) {
      // 原生光标：Tauri 2 的窗口 API，Windows 上只在窗口内生效
      void tauriInvoke('desktop_set_cursor_visible', { visible: !hidden }).catch(() => {})
    }
  },
})

/** 指针活动：显示光标并重新计时（挂到播放器根节点的 pointer 事件） */
function noteCursorActivity() {
  cursorIdle.activity()
}

watch([fullscreen, pseudoFs], ([fs, pfs]) => {
  // 进入全屏先亮出光标再计时；退出全屏必须恢复，否则桌面端指针会一直藏着
  if (fs || pfs) cursorIdle.activity()
  else cursorIdle.stop()
  /*
   * 退出全屏自动解锁（2026-11-25）。
   * 锁是全屏防误触用的；退出后还锁着，用户会以为播放页坏了（点哪都没反应），
   * 而且解锁入口只在全屏里才显眼。宁可解锁，也不要留下一个无入口的状态。
   */
  if (!fs && !pfs && locked.value) {
    locked.value = false
    lockHint.value = false
    clearTimeout(lockHintTimer)
  }
})

/**
 * 手机形态视口（最短边 < 480）。
 *
 * <p>TV 壳也可能跑在手机模拟器上：UA 带 MeowTV，但视口是 384×811。
 * 真电视是 960×540，最短边 540 —— 用它把"手机形状的 TV 壳"和真电视分开。
 */
const compactViewport = ref(false)
function syncCompactViewport() {
  compactViewport.value = Math.min(window.innerWidth, window.innerHeight) < 480
}

/*
 * 锁定只在「可锁状态」下有意义。竖屏/退出全屏/窗口态时必须自动解开，
 * 否则锁按钮整个不渲染，用户会卡在一个点哪都没反应、又没有解锁入口的状态。
 *
 * ★ 必须放在 compactViewport 声明之后：watch 创建时会立刻读一次 canLock，
 *   提前注册会踩 `Cannot access 'isTouch'/'compactViewport' before initialization`
 *   （2026-11-25 vitest 实测）。
 */
watch(canLock, (ok) => {
  if (ok || !locked.value) return
  locked.value = false
  lockHint.value = false
  clearTimeout(lockHintTimer)
})

/**
 * 是否显示"返回"按钮。
 *
 * <p>真电视（960×540）**不给**：遥控器本身有返回键，而页头按钮按几何选焦
 * 会被最先命中、按 OK 就退出整集（见顶部标题栏的注释）。
 * <p>手机形态（含 TV 壳跑在手机模拟器上）**给**：手机可能没有物理返回键，
 * 播放页又是整页铺满、没有顶栏可退。
 *
 * ★ TV 壳里必须有"真的产生过 pointer 事件"才给（2026-11-18）：
 *   投影仪 WebView 没鼠标也报 `(any-pointer: fine)`，只看媒体查询会误显示。
 */
const pointerSeen = ref(false)
function notePointer(e: PointerEvent) {
  const t = e.pointerType
  if (t === 'mouse' || t === 'pen' || t === 'touch') pointerSeen.value = true
}
const showBackButton = computed(() =>
  showPlayerBackButton(isTvShell(), pointerSeen.value, isBigScreenLandscape()),
)

const route = useRoute()
const router = useRouter()

/**
 * 离开播放页。
 *
 * <p>优先 `router.back()`；但 **Android WebView 里 `history.back()` 可能是空操作**
 * （2026-11-05 实测 MuMu：调用后 popstate / hashchange 都不触发、hash 不变，
 * 而直接改 `location.hash` 能正常跳）。所以 260ms 后路径没变就用 replace 兜底，
 * 与 App.vue 的 `__meowTvRouteBack` 同一套判据。
 */
function leavePlayback() {
  // 端内原生渲染：控制层 WebView 的历史只有一条，router.back() 会落到首页/错误页
  // （真机实报「返回跳到缺依赖类页面」）。带 back 标记关原生，由父页面后退。
  if (iosNative) {
    iosNativeCmd('back')
    return
  }
  const before = route.fullPath
  router.back()
  window.setTimeout(() => {
    if (route.fullPath !== before) return
    const site = String(route.params.site || '')
    const id = String(route.query.vod || '')
    if (site && id) {
      router.replace({
        name: 'detail',
        params: { site, id },
        query: route.query.name ? { name: String(route.query.name) } : undefined,
      })
    } else {
      router.replace({ name: 'home' })
    }
  }, 260)
}

/*
 * ★ 2026-11-12 删除「控制栏收起时浮一个返回键」（用户要求）。
 *
 * 原设计：控制栏一收起，顶栏返回键跟着隐藏，触屏用户就没有出口，于是
 * 额外浮一个常驻返回键。用户实测后要求**返回键显隐与底部控制栏完全一致**：
 * 播放中（控制栏收起）左上角不该留按钮挡画面；想看返回键，点一下画面
 * 唤出控制栏即可。所以现在只有顶栏那一个返回键（随 showCtrl 显隐）。

// 伪全屏没有标准 API 可查，广播给 App 让它收起 AI 悬浮按钮
// （虽然播放页本身不显示 FAB，但这条通道要保持一致，避免以后接其他浮层时漏）。
watch(pseudoFs, (v) => {
  window.dispatchEvent(new CustomEvent('meow:pseudofs', { detail: v }))
})

/**
 * PC（Tauri/WebView2）的**窗口级**全屏。
 *
 * <p><b>为什么伪全屏之外还要这一步</b>（2026-11-09，用户报「PC 全屏下自动
 * 下一集总是退出全屏」）：WebView2 的 HTML 全屏由 Tauri 运行时翻译成
 * 「窗口进入全屏」；播放器元素一旦离开 DOM（旧模板用整页 loading 替换它），
 * 运行时就会把窗口退回窗口态。重建后 `requestFullscreen` 没有用户手势，
 * 必被拒，只能进伪全屏（CSS 撑满 WebView），**标题栏/任务栏补不回来**。
 * 窗口级 API 不需要手势，用它把真全屏恢复回来。
 *
 * <p>只在伪全屏路径调用：真全屏（`document.fullscreenElement`）由运行时
 * 自己管理，两边同时设置会互相打架（退出时容易留下一层全屏）。
 */
let winFsOn = false
async function setWindowFullscreen(on: boolean) {
  if (!isDesktopShell() || winFsOn === on) return
  try {
    await tauriInvoke('desktop_set_fullscreen', { enabled: on })
    winFsOn = on
  } catch {
    /* 老版本壳没有这条命令：保持伪全屏即可，不影响播放 */
  }
}

async function enterPseudoFs() {
  pseudoFs.value = true
  fullscreen.value = true
  // PC 上伪全屏只撑满 WebView，窗口本身仍是窗口态；补一次窗口全屏
  //（浏览器/TV/iOS 上这是 no-op）。
  void setWindowFullscreen(true)
  // 进全屏后要主动起隐藏计时：鼠标不动时不会有 mousemove，
  // 不在这里 wake() 的话控制栏会一直挂在画面上（实测）
  wake()
  // 撑满后先滚到顶部，避免 iOS 上地址栏展开导致首屏被裁
  window.scrollTo(0, 0)
  // 伪全屏拿不到方向锁（规范只允许真全屏锁），但部分 Android WebView 会放行，
  // 试一下没坏处；失败就靠下面的横屏提示引导用户自己转。
  void lockLandscapeQuietly()
  dm.value?.resize()
  checkOrientation()
}

async function exitPseudoFs() {
  // 先退窗口全屏（PC），再处理 HTML 全屏；顺序反了会先回到窗口态再触发
  // 一次 fullscreenchange，控制栏会闪一下。
  void setWindowFullscreen(false)
  syncShellOrientation(false)
  pseudoFs.value = false
  fullscreen.value = !!document.fullscreenElement
  if (document.fullscreenElement) await document.exitFullscreen().catch(() => {})
  dm.value?.resize()
  checkOrientation()
}

/**
 * 进/退全屏。
 *
 * 只走「容器全屏」这一条路：弹幕层、控制栏都是容器的子元素，会一起进入
 * 全屏上下文，所以全屏后仍然是我们的播放器。
 * 容器全屏被拒时降级到伪全屏（撑满视口），**不要**退到原生播放器。
 */
async function toggleFullscreen() {
  const el = wrap.value
  if (!el) return
  if (pseudoFs.value || document.fullscreenElement) {
    await exitPseudoFs()
    /*
     * iOS 壳退出全屏必须真的回到竖屏（2026-11-25 用户报「全屏/退出全屏不生效」）：
     * 进播放页会自动转横屏（pageLandscape），此时横屏本身就是铺满态；
     * 如果只把 pseudoFs 关掉，画面仍是横屏铺满 → 看起来什么都没发生。
     */
    if (isIosShell() && !isTvShell() && isLandscapeViewport()) {
      pageLandscape = false
      rotatedByFs = false
      shellSetOrientation('portrait')
    }
    rememberFsIntent(false)
    return
  }
  /*
   * iOS 壳里没有 pseudoFs、但已经是横屏（进播放页自动转的 / 用户自己转的）：
   * 这时用户点的是"退出全屏"——转回竖屏即可，不要再叠一层伪全屏。
   * ★ 「自动全屏」和「手动全屏」原来是两套机制（pageLandscape vs pseudoFs），
   *   所以自动横屏时点全屏/退出都不生效（2026-11-25 用户实测）。
   */
  if (isIosShell() && !isTvShell() && landscapeViewport.value) {
    pageLandscape = false
    rotatedByFs = false
    shellSetOrientation('portrait')
    rememberFsIntent(false)
    return
  }
  /*
   * iOS（WKWebView）不走 requestFullscreen。
   *
   * iPhone 上它要么直接抛 NotAllowedError，要么"成功"但画面一点不变
   * （document.fullscreenElement 有值 → 我们以为进了全屏，用户看到的却是没反应）。
   * 统一用伪全屏（容器 CSS 撑满 WebView）+ 请壳把界面转横屏 —— 这条路径
   * 在 WKWebView 里是确定有效的（2026-11-24 用户报「点了不会全屏」）。
   */
  if (isIosShell()) {
    await enterPseudoFs()
    syncShellOrientation(true)
    rememberFsIntent(true)
    return
  }
  try {
    await el.requestFullscreen()
    // 只有进了全屏，规范才允许锁方向；失败也无所谓，用户自己转手机即可
    void lockLandscapeQuietly()
    syncShellOrientation(true)
    // 同上：全屏后立刻起计时，否则「点完全屏不动鼠标」控制栏永不消失
    wake()
    rememberFsIntent(true)
  } catch {
    // 拿不到真全屏：至少让画面铺满，且保住弹幕与控件
    await enterPseudoFs()
    syncShellOrientation(true)
    flashFsHint('已切换为铺满模式（浏览器未授予全屏）')
    rememberFsIntent(true)
  }
}

/**
 * 记住"用户想全屏"这个意图，供换集 / 切后台回来时恢复。
 *
 * <p><b>为什么需要</b>（2026-10-31 用户报的问题 6）：PC 全屏播放时，
 * 一集结束切下一集会**退出全屏**。原因是换集走 `router.replace`，
 * 而 `App.vue` 以 `fullPath` 作 key 重建了整页 —— 新组件实例的
 * `pseudoFs`/`fullscreen` 都是初始值 false，全屏自然就没了。
 *
 * <p>★ 2026-11-25 改存 localStorage（原来 sessionStorage）：iOS 切后台几分钟
 * 被系统杀掉 WebView / App 后，sessionStorage 一起没了，回前台（或重新进播放页）
 * 全屏状态就丢了（用户真机报）。localStorage 带 12 小时有效期 + 离开播放页
 * 立即清除，所以「隔天再进播放页」不会莫名自动全屏。
 */
const FS_INTENT_KEY = 'meow.play.fsIntent'
/** 全屏意图有效期：超过这个时间视为过期（App 被杀后隔很久进来不自动全屏） */
const FS_INTENT_TTL_MS = 12 * 3600_000

/**
 * 移动端形态（短边 < 480 或 iOS 壳）。
 *
 * <p>全屏意图的持久化范围按形态分：**PC/TV 仍用 sessionStorage**（关掉应用
 * 再开不该自动全屏），只有手机用 localStorage（WebView/App 被系统杀掉后
 * 回来还要恢复全屏）。用短边而不是 isTouch：Windows 触摸屏笔记本的
 * isTouch 也是 true，不该跟着变成"重开就全屏"。
 */
function isMobileForm(): boolean {
  return isIosShell() || compactViewport.value
}
/**
 * 组件正在卸载（换集/换线路 → 整页重建）。
 *
 * <p>卸载时浏览器会退出全屏并触发 fullscreenchange，若不加这个标志，
 * onFullscreenChange 会把"想全屏"的意图清掉 —— 于是下一集永远恢复不了。
 */
let unmounting = false

function rememberFsIntent(on: boolean) {
  try {
    if (on) {
      /*
       * 移动端 → localStorage（带时间戳，供 App 被系统杀掉后恢复）；
       * PC/TV → sessionStorage（只在本次会话跨集有效，重开应用不自动全屏）。
       * 两边都清掉另一份，避免旧值串台。
       */
      if (isMobileForm()) {
        localStorage.setItem(FS_INTENT_KEY, String(Date.now()))
        sessionStorage.removeItem(FS_INTENT_KEY)
      } else {
        sessionStorage.setItem(FS_INTENT_KEY, '1')
        localStorage.removeItem(FS_INTENT_KEY)
      }
    } else {
      localStorage.removeItem(FS_INTENT_KEY)
      sessionStorage.removeItem(FS_INTENT_KEY)
    }
  } catch {
    /* 隐私模式下 storage 可能不可用，忽略 */
  }
}

/**
 * 换集 / 回前台恢复全屏。
 *
 * <p><b>为什么恢复成"伪全屏"而不是再调一次 `requestFullscreen`</b>：
 * 真全屏 API 要求**用户手势**，页面刚重建时没有任何手势，调用必然
 * 被浏览器拒绝（`NotAllowedError`）。伪全屏是把容器撑满视口，
 * 不需要手势，视觉上同样没有浏览器边框以外的差别 ——
 * 唯一代价是某些浏览器地址栏还在，但切集场景下这是唯一可行的做法。
 */
async function restoreFsIntent() {
  let want = false
  try {
    if (isMobileForm()) {
      const ts = Number(localStorage.getItem(FS_INTENT_KEY))
      want = Number.isFinite(ts) && ts > 0 && Date.now() - ts < FS_INTENT_TTL_MS
      // 过期 / 脏数据：顺手清掉，避免一直留着
      if (!want) localStorage.removeItem(FS_INTENT_KEY)
    } else {
      // PC/TV：沿用原会话内语义（跨集恢复，重开应用不恢复）
      want = sessionStorage.getItem(FS_INTENT_KEY) === '1'
    }
  } catch {
    return
  }
  if (!want || pseudoFs.value || document.fullscreenElement) return
  await enterPseudoFs()
}

// ---------------------------------------------------------------- 画中画 / 字幕

const pipOn = ref(false)
const hasPip = typeof document !== 'undefined' && 'pictureInPictureEnabled' in document

async function togglePip() {
  const v = video.value
  if (!v) return
  try {
    if (document.pictureInPictureElement) {
      await document.exitPictureInPicture()
      pipOn.value = false
    } else {
      await v.requestPictureInPicture()
      pipOn.value = true
    }
  } catch {
    /* 部分源/浏览器不支持，静默失败 */
  }
}

/** 字幕轨（HLS 内嵌的 subtitle track） */
const textTracks = ref<Array<{ index: number; label: string }>>([])
const activeTrack = ref(-1)

function refreshTextTracks() {
  const v = video.value
  if (!v) return
  const tracks: Array<{ index: number; label: string }> = []
  for (let i = 0; i < v.textTracks.length; i++) {
    const t = v.textTracks[i]
    if (t.kind !== 'subtitles' && t.kind !== 'captions') continue
    tracks.push({ index: i, label: t.label || `字幕 ${i + 1}` })
  }
  textTracks.value = tracks
}

function setTrack(index: number) {
  const v = video.value
  if (!v) return
  for (let i = 0; i < v.textTracks.length; i++) {
    v.textTracks[i].mode = i === index ? 'showing' : 'disabled'
  }
  activeTrack.value = index
}

/** 字幕菜单：点一下循环「关 → 第1条 → 第2条 → … → 关」 */
function cycleTrack() {
  if (!textTracks.value.length) return
  if (activeTrack.value < 0) {
    setTrack(textTracks.value[0].index)
    return
  }
  const cur = textTracks.value.findIndex((t) => t.index === activeTrack.value)
  const next = textTracks.value[cur + 1]
  if (!next) {
    setTrack(-1)
    return
  }
  setTrack(next.index)
}

function onFullscreenChange() {
  // 真全屏退出时，若伪全屏没开就把状态清掉；两者共用 fullscreen 这个布尔量，
  // 因为对用户来说「现在是铺满的」是同一件事，图标只需一个。
  fullscreen.value = !!document.fullscreenElement || pseudoFs.value
  /*
   * 用户按 Esc / 系统手势退出真全屏时，同步清掉"想全屏"的意图，
   * 否则下一集又会自动铺满——那是用户刚刚明确关掉的东西。
   *
   * 只在**真全屏退出且伪全屏也没开**时清：换集时新页面还没进全屏，
   * 这条事件不会触发（没有 fullscreenchange 可发）。
   */
  if (!unmounting && !document.fullscreenElement && !pseudoFs.value) rememberFsIntent(false)
  /*
   * 用户按 Esc / 系统手势退出真全屏时，还要把**壳的方向**还回去。
   * 进全屏时 syncShellOrientation(true) 把 Activity 钉成了横屏，不还原就会
   * "退出全屏回到列表，界面还是横的"（2026-11-12 审计发现）。
   * 伪全屏（壳里 requestFullscreen 被拒的降级路径）不在这里还 ——
   * exitPseudoFs 自己会调。
   */
  if (!document.fullscreenElement && !pseudoFs.value) syncShellOrientation(false)
  dm.value?.resize()
  // 退出全屏后如果还竖着，重新提示（但已手动关掉就不再打扰）
  checkOrientation()
}

function toggleDanmaku() {
  dmOn.value = !dmOn.value
  dm.value?.setEnabled(dmOn.value)
  // 端内原生渲染时弹幕由原生画，开关必须同时发过去（否则点了没反应）
  if (iosNative) iosNativeCmd('dm', dmOn.value ? 1 : 0)
}

// ---------------------------------------------------------------- 弹幕面板

const showDmSearch = ref(false)
/** 弹框里的搜索输入框（PC/Web 打开时聚焦它，见 openDmSearch） */
const dmInputEl = ref<HTMLInputElement | null>(null)
/** 弹框里的「搜索」按钮（TV 上打开时聚焦它，一次 OK 即搜） */
const dmSubmitEl = ref<HTMLButtonElement | null>(null)
/** 结果列表容器（定位"当前集"时要滚它） */
const dmListEl = ref<HTMLElement | null>(null)

/** 当前正在播第几集（解析不出来返回 0） */
function currentEpNo(): number {
  const eps = props.episodes || []
  const cur = props.currentIndex != null ? eps[props.currentIndex] : undefined
  if (!cur) return 0
  const m = /(\d+)/.exec(cur.name || '')
  return m ? Number(m[1]) : 0
}

/**
 * 从标题 `片名 · 第3集` 兜底解析集数。
 *
 * <p>切集时 `props.currentIndex` 可能还没更新（父级 computed 与子级 watcher
 * 的刷新顺序），`currentEpNo()` 会返回 0；用标题里的集数兜一下，避免
 * 「沿用此源」在切集瞬间找不到匹配。
 */
function episodeFromTitle(): number {
  const t = props.title || ''
  const m = /·\s*(?:第\s*)?(\d{1,4})\s*[集话期]?/.exec(t)
  return m ? Number(m[1]) : 0
}

/**
 * 把列表滚到（TV 上还会聚焦到）当前正在播的那一集。
 *
 * <p>用户原话（2026-11-04）：「是不是在胶囊选定后，直接聚焦定位到当前集（如有），
 * 这样不用来回滚动了」。1000+ 集的长番里，手动翻到第 900 集基本不可能。
 *
 * <p>找不到对应集（源站命名不规范、或该来源没有这集）就什么都不做 ——
 * 不要跳到列表顶部，那会打断用户刚滚到的位置。
 */
function focusDmCurrentEp() {
  const list = dmListEl.value
  const n = currentEpNo()
  if (!list || !n) return
  let row = list.querySelector<HTMLElement>(`button[data-ep="${n}"]`)
  /*
   * 没有精确命中时退到「最近的一集」（2026-11-04 实测需要）：
   * 后端可能只回了一部分（有上限/该来源缺集），167 集的片里第 158 集
   * 可能压根不在列表里。这时至少把用户送到离它最近的位置，
   * 而不是让他从第 1 集翻起。
   */
  if (!row) {
    let bestDiff = Infinity
    const all = list.querySelectorAll<HTMLElement>('button[data-ep]')
    for (let i = 0; i < all.length; i++) {
      const ep = Number(all[i].getAttribute('data-ep'))
      if (!ep) continue
      const d = Math.abs(ep - n)
      if (d < bestDiff) {
        bestDiff = d
        row = all[i]
      }
    }
  }
  if (!row) return
  row.scrollIntoView({ block: 'center' })
  if (isTvEnv()) {
    const w = window as unknown as { __meowTvFocus?: (t: Element, pin?: boolean) => boolean }
    if (typeof w.__meowTvFocus === 'function') w.__meowTvFocus(row, true)
  }
}

/**
 * 打开弹幕搜索弹框。
 *
 * <p>先关掉"更多"等面板：它们是同级浮层，同时开着时 tv-nav 的 scope 归属
 * 会打架（两个 `[data-tv-scope]`，焦点进的是最后那个 DOM，用户看到"框在
 * 更多面板上，但按方向键弹幕列表在动"）。
 */
function openDmSearch() {
  openPanel(null)
  showDmSearch.value = true
  // 兜底：片名是后到的（路由 query 更新）或之前为空时，打开面板即填好片名
  if (!dmKeyword.value.trim()) dmKeyword.value = showName.value
  /*
   * 已有上一次搜索结果时，打开面板直接定位到当前集（2026-11-09）。
   * 否则长番切集后打开面板仍停在上一集的滚动位置，用户还得自己翻。
   * 三次尝试与 runDmSearch 同一套节奏：v-for 刚重建时首帧可能还没几何。
   */
  if (dmResults.value.length) {
    void nextTick(focusDmCurrentEp)
    window.setTimeout(focusDmCurrentEp, 80)
    window.setTimeout(focusDmCurrentEp, 260)
  }
  /*
   * 打开后主动把焦点落到搜索框（用户明确要求）。
   *
   * 为什么不能只靠模板上的 data-tv-first：
   *   · 非 TV 环境（PC/Web）根本没有 tv-nav，`data-tv-first` 是死属性，
   *     鼠标用户打开弹框后还得点一下输入框才能打字；
   *   · TV 上 tv-nav 的 focusIntoScope 只在"scope 变化"时触发一次，
   *     而且有 1.2s 按键宽限期 —— 从控制栏点开这个弹框时正在宽限期内，
   *     它会直接跳过落点计算（那是另一个已修的 bug 的成因），
   *     结果焦点留在已隐藏的控制栏按钮上。
   * 所以两条路都走：模板标记给 tv-nav，这里再显式聚焦一次。
   *
   * 等一帧：弹框是 v-if，同一帧里 DOM 还没渲染出来（querySelector 拿到 null）。
   */
  void nextTick(() => {
    const el = dmInputEl.value
    if (!el) return
    /*
     * ★ 落点按端分：TV 落「搜索」按钮，PC/Web 落输入框（2026-11-04 用户要求）。
     *
     * TV：遥控器上把焦点放在按钮上，一次 OK 就能搜（关键词通常已经能沿用上一次），
     *     想改关键词按左键回输入框、再按 OK 弹原生输入法。
     *     原来落输入框，输入框的 `focus:border-accent/50` 和 tv-nav 的焦点环
     *     同时亮，看起来"搜索框和按钮都有焦点"（用户截图报的问题）。
     * PC/Web：没有遥控器，落输入框才能直接打字。
     */
    if (isTvEnv()) {
      const btn = dmSubmitEl.value
      if (btn) {
        const w = window as unknown as { __meowTvFocus?: (t: Element, pin?: boolean) => boolean }
        if (typeof w.__meowTvFocus === 'function') w.__meowTvFocus(btn, true)
        return
      }
    }
    try {
      el.focus({ preventScroll: true })
    } catch {
      /* 老内核不支持 focus 选项 */
    }
    // 光标放末尾：片名是从标题预填的，用户多半只想追加"第X集"
    if (typeof el.setSelectionRange === 'function') {
      const n = el.value.length
      try {
        el.setSelectionRange(n, n)
      } catch {
        /* 部分内核只允许在 focus 后设置 */
      }
    }
  })
}

/**
 * 关闭弹幕搜索弹框。
 *
 * <p>统一出口而不是各处直接写 `showDmSearch = false`：这个弹框现在带
 * `data-tv-scope`（tv-nav 用它收窄焦点范围），关的时候必须同时把焦点框
 * 撤掉并交还 scope，否则焦点会留在已移除的节点上 —— 下一次按键就落到
 * "一个看不见的焦点"上，表现为方向键没反应（同类 bug 在控制栏踩过）。
 */
function closeDmSearch() {
  showDmSearch.value = false
  ;(document.activeElement as HTMLElement | null)?.blur()
  if (isTvEnv()) clearTvFocus()
  const w = window as unknown as { __meowTvScopeClosed?: (sel?: string) => boolean }
  if (typeof w.__meowTvScopeClosed === 'function') w.__meowTvScopeClosed()
}

/**
 * 手动搜索弹幕的默认关键词 = 片名（`title` 是「片名 · 第N集」，去掉集名）。
 *
 * <p>片库这条以前是空串（进播放页没传 name），用户每次都要自己敲片名；
 * 现在片库也会带 name（见 LibraryView.play），这里跟着片名自动填。
 *
 * <p>只在"用户没改过"时跟随：`dmKeywordAuto` 记住上一次自动填的值，
 * 用户手敲过就不再覆盖，避免切集/切片把他输入的关键词冲掉。
 */
const showName = computed(() => (props.title || '').split(' · ')[0].trim())
const dmKeyword = ref(showName.value)
let dmKeywordAuto = dmKeyword.value
watch(showName, (n) => {
  if (!n) return
  const cur = dmKeyword.value.trim()
  if (!cur || cur === dmKeywordAuto) dmKeyword.value = n
  dmKeywordAuto = n
})
const dmResults = ref<DanmakuCandidate[]>([])
const dmSearching = ref(false)
const dmSearchError = ref('')
/** 用户手选的弹幕地址；非空时优先于自动抓取 */
const dmOverrideUrl = ref('')
/**
 * 手选时记住的来源 key（如 360 / tencent）。
 *
 * <p>切集时用它在前端已有的 `dmResults` 里找「同源同集」候选，实现
 * 「沿用此源」；找不到就回退自动（见 danmakuUrl watcher）。
 */
const dmManualSource = ref('')
/** 本次弹幕是否来自本地 IndexedDB（诊断用） */
const dmLocalCount = ref(0)
/**
 * 后台补全检测到的更全结果（等待安全点应用）。
 *
 * <p>为什么不是拿到就换：播放中途突然变密/位置跳动比少几千条更糟。
 * 只在 `currentTime < 15s` 或用户主动 seek 时应用。
 */
const dmPendingUpgrade = ref<{
  url: string
  xml: string
  count: number
  /** 后端 X-Danmaku-Total：截断前真实条数（升级后也要带着，否则 "N+" 会丢） */
  total: number
  source: string
} | null>(null)
/** 本集是否已经应用过后台补全（诊断用） */
const dmUpgradeApplied = ref(false)
/** 每集只做一次后台补全检查 */
let dmUpgradeChecked = false
/**
 * 后台补全的检查点（毫秒，相对首屏成功那一刻）。
 *
 * 为什么是这五个点：聚合源实测 7~14 秒才写完缓存，"写完 → 前端看到"还要
 * 等下一个检查点。原来只有 12/30/60 —— 12s 那次常赶在聚合写缓存之前
 * （MuMu 实测 +25s 才升级，就是被迫等 30s 那个点）。8s 起每 4~6 秒探一次，
 * 最早能在聚合写完后的 4 秒内替换，30s 兜住慢源。
 *
 * 成本：每个点只是一次后端内存缓存查询（命中即返回，微秒级），**不会重复
 * 打上游**——首屏失败时已写 30 秒负缓存，前四个点都会命中负缓存返回空。
 */
const DM_UPGRADE_CHECKPOINTS = [8_000, 12_000, 16_000, 22_000, 30_000]
let dmUpgradeTimers: number[] = []
/** 最近一次用户主动 seek 的时间（判断"刚 seek"） */
let dmLastSeekAt = 0
/** 弹幕轻提示（沿用此源 / 已切换到更全的来源） */
const dmHint = ref('')
let dmHintTimer: number | undefined
function showDmHint(msg: string) {
  dmHint.value = msg
  clearTimeout(dmHintTimer)
  dmHintTimer = window.setTimeout(() => (dmHint.value = ''), 3200)
}
/** 来源筛选：null = 全部；否则按 label + via 精确筛选 */
const dmSourceFilter = ref<{ label: string; via: 'direct' | 'aggregator' } | null>(null)
/** 后端返回的分类分组（已含中文 label 与 direct/aggregator 计数） */
const dmServerSources = ref<DanmakuSourceGroup[]>([])
/** 后端给的固定胶囊顺序（采集站/爱爱/腾疼/悠悠/哔哔/茫茫） */
const dmFixedLabels = ref<string[]>([])

/**
 * 候选的来源标签。
 *
 * 优先用后端给的 `label`（已映射成爱爱/腾疼/采集站…）；
 * 老后端没有该字段时，回退到从 name 里解析 `from xxx`。
 */
function sourceOf(item: { name: string; label?: string; source?: string }): string {
  if (item.label) return item.label
  const m = /from\s+([a-zA-Z0-9]+)/i.exec(item.name)
  if (m) return m[1].toLowerCase()
  const b = /【([^】]+)】\s*$/.exec(item.name)
  if (b) return b[1]
  return '其他'
}

/** API 直连（direct）还是 danmu_api 聚合（aggregator） */
function viaOf(item: { via?: string }): 'direct' | 'aggregator' {
  return item.via === 'direct' ? 'direct' : 'aggregator'
}

/**
 * 胶囊列表。
 *
 * 先铺后端给的固定 6 个（采集站/爱爱/腾疼/悠悠/哔哔/茫茫），
 * 再追加本次额外抓到的平台。每个胶囊带两个计数：
 *   direct     —— API 直连
 *   aggregator —— danmu_api / 聚合站
 * 两者用不同颜色，数据不混。
 */
const dmSources = computed(() => {
  const byLabel = new Map<string, { direct: number; aggregator: number }>()
  if (dmServerSources.value.length) {
    for (const g of dmServerSources.value) {
      byLabel.set(g.label, {
        direct: g.direct ?? 0,
        aggregator: g.aggregator ?? 0,
      })
    }
  } else {
    for (const r of dmResults.value) {
      const lb = sourceOf(r)
      const cur = byLabel.get(lb) || { direct: 0, aggregator: 0 }
      cur[viaOf(r)]++
      byLabel.set(lb, cur)
    }
  }

  const order: string[] = []
  for (const lb of dmFixedLabels.value) order.push(lb)
  for (const lb of byLabel.keys()) if (!order.includes(lb)) order.push(lb)

  return order.map((label) => {
    const c = byLabel.get(label) || { direct: 0, aggregator: 0 }
    return { label, direct: c.direct, aggregator: c.aggregator, total: c.direct + c.aggregator }
  })
})

/** 当前筛选后要展示的候选（后端已排好序：episode 升序 → direct 优先） */
const dmFiltered = computed(() => {
  const f = dmSourceFilter.value
  if (!f) return dmResults.value
  return dmResults.value.filter((r) => sourceOf(r) === f.label && viaOf(r) === f.via)
})

/** 把 host 压成短标签 */
function hostLabel(h?: string): string {
  if (!h) return '未知'
  const s = h.replace(/^https?:\/\//, '').replace(/^www\./, '')
  return s.split('.').slice(0, 2).join('.')
}

const dmOpts = computed(() => ({
  opacity: prefs.value.danmakuOpacity,
  scale: prefs.value.danmakuScale,
  duration: prefs.value.danmakuDuration,
  area: prefs.value.danmakuArea,
}))

/*
 * 胶囊切换后同样定位当前集：用户点「腾疼」是想看这一集在这个来源里的条目，
 * 而不是从头翻（2026-11-04 用户要求）。
 * 列表是 v-for 重渲染，必须等一帧再找行。
 */
watch(dmSourceFilter, () => {
  void nextTick(focusDmCurrentEp)
  window.setTimeout(focusDmCurrentEp, 80)
  window.setTimeout(focusDmCurrentEp, 260)
})

function patchDmOptions(patch: Partial<Prefs>) {
  prefs.value = savePrefs(patch)
  dm.value?.setOptions({
    opacity: prefs.value.danmakuOpacity,
    scale: prefs.value.danmakuScale,
    duration: prefs.value.danmakuDuration,
    area: prefs.value.danmakuArea,
  })
}

/**
 * 切弹幕密度档位（稀疏/标准/密集）。
 *
 * 一次写好 duration + area，两者耦合，单独调一个看不出效果。
 * 数值与设置页共用 DENSITY_PRESETS，保证两处口径一致。
 */
function pickDensity(d: Prefs['danmakuDensity']) {
  if (d === 'custom') return // 自定义只是状态显示，不主动选
  prefs.value = setDanmakuDensity(d)
  dm.value?.setOptions({
    opacity: prefs.value.danmakuOpacity,
    scale: prefs.value.danmakuScale,
    duration: prefs.value.danmakuDuration,
    area: prefs.value.danmakuArea,
  })
}

/** 手调速度/区域：档位自动降级 custom */
function tuneDm(patch: { duration?: number; area?: number }) {
  prefs.value = setDanmakuTuning(patch)
  dm.value?.setOptions({
    opacity: prefs.value.danmakuOpacity,
    scale: prefs.value.danmakuScale,
    duration: prefs.value.danmakuDuration,
    area: prefs.value.danmakuArea,
  })
}

/** 面板里的三档胶囊（custom 只作为状态显示，不在这里选） */
const heatButtons = [
  { value: 'bar' as const, label: '双条', hint: '进度条上方独立峰谷图（B 站高能条同款）' },
  { value: 'inline' as const, label: '内嵌', hint: '压进进度条内部当热力底色（单条）' },
  { value: 'off' as const, label: '关闭', hint: '不显示时间轴密度' },
]
const densityButtons = [
  { value: 'sparse' as const, label: '稀疏', hint: '18 秒 / 区域 55%：节奏舒缓，不挡画面' },
  { value: 'standard' as const, label: '标准', hint: '12 秒 / 区域 70%：默认，与以往一致' },
  { value: 'dense' as const, label: '密集', hint: '9 秒 / 区域 85%：尽量多显示，飞得更快' },
]

async function runDmSearch() {
  // 按钮不再 disabled（禁用会让焦点环被 tv-nav 搬走），这里防重入
  if (dmSearching.value) return
  const k = dmKeyword.value.trim()
  if (!k) {
    /*
     * 关键词为空：把焦点送回输入框，而不是静默 return。
     * TV 上默认焦点在「搜索」按钮（见 openDmSearch），空关键词按 OK 若什么都不发生，
     * 用户会以为按钮坏了 —— 这里顺手把焦点搬回输入框，再按 OK 就是原生输入法。
     */
    const el = dmInputEl.value
    if (el) {
      try {
        el.focus({ preventScroll: true })
      } catch {
        /* 忽略 */
      }
      if (isTvEnv()) {
        const w = window as unknown as { __meowTvFocus?: (t: Element, pin?: boolean) => boolean }
        if (typeof w.__meowTvFocus === 'function') w.__meowTvFocus(el, true)
      }
    }
    return
  }
  dmSearching.value = true
  dmSearchError.value = ''
  dmResults.value = []
  dmSourceFilter.value = null
  dmServerSources.value = []
  dmFixedLabels.value = []
  try {
    const r = await searchDanmaku(k)
    dmResults.value = r.items
    dmServerSources.value = r.sources
    dmFixedLabels.value = r.fixed
    if (!r.items.length) dmSearchError.value = '没有找到弹幕，换个名字或去掉季数试试'
    /*
     * 结果出来后直接定位到正在看的那一集（长番必需，见 focusDmCurrentEp）。
     * 三次尝试：Vue 渲染后 / 80ms / 260ms —— 列表是 v-for 重建的，
     * 首帧里行元素可能还没有可测几何（visible() 会判 false，__meowTvFocus 直接返回 false）。
     * 与 openPanel 里"弹层落地"用的是同一套重试节奏。
     */
    void nextTick(focusDmCurrentEp)
    window.setTimeout(focusDmCurrentEp, 80)
    window.setTimeout(focusDmCurrentEp, 260)
  } catch (e: any) {
    dmSearchError.value = e?.message || String(e)
  } finally {
    dmSearching.value = false
  }
}

/** 选中某一条：加载它的弹幕，并从当前进度接着放 */
async function pickDm(item: DanmakuCandidate) {
  dmOverrideUrl.value = item.url
  // 记住平台（360 候选取 platform，其余取 source），切集时用于同源沿用
  dmManualSource.value = dmPlatformOf(item)
  clearDmUpgrade()
  showDmSearch.value = false
  // ★ 手选必须 fresh：普通路径先读 IndexedDB（key=URL），若这个 URL 的缓存
  //   是上一部剧写入的（聚合源同集同 URL、或后端 cacheKey 撞名），读回来的
  //   就是旧剧的弹幕——用户看到"选了 A 剧显示 B 剧弹幕，清缓存才正常"。
  //   fresh 同时跳过本地与后端缓存，取所选源这一集的实时弹幕。
  //   pick=true 让结果**无条件生效**（fresh 本身是给后台补全设计的，
  //   带 dmOverrideUrl 门槛会把这次手选结果丢掉）；非空时写回本地缓存。
  await loadDanmaku(proxyDanmakuByUrl(item.url), { fresh: true, pick: true })
}

function clearDmOverride() {
  dmOverrideUrl.value = ''
  dmManualSource.value = ''
  clearDmUpgrade()
  if (props.danmakuUrl) loadDanmaku(props.danmakuUrl)
  else {
    dmCount.value = 0
    dmTotal.value = 0
    dmTruncated.value = false
    dmRawList = []
    dmHeat.value = null
    dmHeatRef.value = 1
    dmHeatBucketSec.value = 0
    dmQuality.value = null
    dm.value?.clear()
  }
}

/**
 * 四块浮层（选集 / 线路 / 弹幕设置 / 更多菜单）互斥。
 *
 * 它们都锚在控制栏上方，同时打开会完全叠在一起（实测过）。
 * 统一走这个入口，避免每加一个面板就要补一遍互相关闭。
 */
type Panel = 'ep' | 'line' | 'dm' | 'more' | 'help'
/**
 * 打开快捷键说明。
 *
 * <p>从「更多」菜单里点进来时要先把菜单收起来——否则说明面板会叠在菜单上，
 * 而两者都是 absolute 定位，视觉上像两个浮层打架。
 */
function toggleHelp(ev?: Event) {
  const next = !showHelp.value
  if (next) anchorTo('help', ev)
  openPanel(next ? 'help' : null)
}

function openPanel(target: Panel | null) {
  showEpPanel.value = target === 'ep'
  showLinePanel.value = target === 'line'
  showDmPanel.value = target === 'dm'
  showMore.value = target === 'more'
  showHelp.value = target === 'help'
  // TV：弹层打开后把焦点搬进去，否则遥控器还要先移半天（更糟：焦点可能
  // 停在背景按钮上，按 ok 就误触了）。等一帧让 v-if 渲染出元素再找。
  if (target) {
    // ★ PC / TV 都要：打开选集就把**当前集**滚进可视区并居中
    //   （2026-11-06 用户要求"播放集定位聚焦处显示"）。
    //   165 集的剧停在顶部、当前在第 150 集时，用户得自己滚 30 行才看得到。
    if (target === 'ep') {
      nextTick(() => {
        window.setTimeout(() => centerCurrentEp(), 60)
        window.setTimeout(() => centerCurrentEp(), 240)
      })
    }
    if (isTvEnv()) {
      // 两次尝试：第一次赶在 Vue 渲染后，第二次兜底（面板内容可能异步渲染，
      // 比如选集列表要等 detail 回来）。两次都是幂等的——只是重设焦点框。
      nextTick(() => {
        window.setTimeout(() => focusInPanel(target), 70)
        window.setTimeout(() => focusInPanel(target), 260)
      })
    }
  }
}

/**
 * 把"当前集"格子滚到选集面板中央（PC 与 TV 共用）。
 *
 * <p>只认 `bg-player` 高亮的那个按钮（当前集样式），找不到就什么都不做 ——
 * 不能拿"第一个按钮"凑数，那会把用户看到的起点又拽回第 1 集。
 */
function centerCurrentEp() {
  const p = document.querySelector('[data-panel="ep"]') as HTMLElement | null
  if (!p) return
  const cur = p.querySelector('[aria-current="true"]') as HTMLElement | null
  if (cur) scrollIntoViewInScroller(cur, true)
}

/**
 * TV：面板打开后把焦点落到该面板里"最该先选的那一项"。
 *
 *   选集 → 当前集（用户多半想换集，从当前位置开始数最省事）
 *   线路 → 当前线路（同理，先看到自己在哪条线上）
 *   其它 → 面板里第一个可聚焦元素
 *
 * 踩过的坑：不做这一步时焦点留在触发按钮上，而弹层是 fixed 居中的，
 * 按方向键会往背景跑（实测焦点停在背景的「置顶」上）。
 */
function focusInPanel(target: Panel) {
  const roots: Record<string, string> = {
    ep: '[data-tv-scope]',
    line: '[data-tv-scope]',
    dm: '[data-tv-scope]',
    more: '[data-tv-scope]',
    help: '[data-tv-scope]',
  }
  // 排除控制栏自己的 "ctrl" scope（那是给焦点导航收窄用的，不是面板）
  const allScopes = Array.prototype.slice.call(
    document.querySelectorAll(roots[target] || '[data-tv-scope]'),
  ) as HTMLElement[]
  const scopes = allScopes.filter((el) => el.getAttribute('data-tv-scope') !== 'ctrl')
  const scope = scopes.length ? scopes[scopes.length - 1] : null
  if (!scope) return
  const sel = 'a[href],button,input,select,textarea,[tabindex]:not([tabindex="-1"])'
  const all = Array.prototype.slice.call(scope.querySelectorAll(sel)) as HTMLElement[]
  const vis = all.filter((el) => {
    const r = el.getBoundingClientRect()
    if (r.width < 2 || r.height < 2) return false
    const s = getComputedStyle(el)
    return s.visibility !== 'hidden' && s.display !== 'none' && s.opacity !== '0'
  })
  if (!vis.length) return

  let pick = vis[0]
  if (target === 'ep' || target === 'line') {
    // 当前项：页面给选中项加了 aria-current，或用了 player/accent 高亮色。
    // ★ 正则不能用 \bbg-player\b：实际类名是 `bg-player/25`，
    //   斜杠不是单词边界，\b 在 "r/" 之间匹配失败——实测漏判，焦点落到第 1 集。
    const cur = vis.find((el) =>
      el.getAttribute('aria-current') === 'true'
      || /bg-player(\/|["\s]|$)|text-player|border-accent|bg-accent/.test(String(el.className || '')),
    )
    if (cur) pick = cur
  }
  const els = document.querySelectorAll('.meow-tv-focus')
  for (let i = 0; i < els.length; i++) els[i].classList.remove('meow-tv-focus')
  pick.classList.add('meow-tv-focus')
  try {
    pick.focus({ preventScroll: true })
  } catch {
    /* 老内核不支持选项，忽略 */
  }
  // ★ 必须把焦点框滚进可视区。
  //   选集 24 集时"当前集"可能在列表下方；只加 class 不滚动，
  //   用户看到的仍是面板顶部，以为没选中（实测）。
  //
  // ★ 滚动容器要**往上找真正能滚的那层**（2026-11-06 修）。
  //   选集面板本身是 `flex max-h ... flex-col`（overflow 可见），
  //   真正滚的是里面那个 `overflow-y-auto` 的 grid。原来直接拿
  //   `closest('[data-tv-scope]')`（=面板）判 `scrollHeight>clientHeight`
  //   永远为假 → 一次都没滚过，聚焦第 150 集时列表还停在顶部。
  scrollIntoViewInScroller(pick, false)
}

/**
 * 把元素滚进**最近的真正可滚祖先**的可视区。
 *
 * @param center true = 居中（打开面板时定位当前集），false = 只保证露出来（移焦点）
 */
function scrollIntoViewInScroller(el: HTMLElement, center: boolean) {
  let p = el.parentElement
  while (p) {
    const cs = getComputedStyle(p)
    const canScroll =
      (cs.overflowY === 'auto' || cs.overflowY === 'scroll') &&
      p.scrollHeight > p.clientHeight + 1
    if (canScroll) {
      const er = el.getBoundingClientRect()
      const sr = p.getBoundingClientRect()
      if (center) {
        p.scrollTop += er.top - sr.top - (sr.height - er.height) / 2
      } else if (er.top < sr.top || er.bottom > sr.bottom) {
        p.scrollTop += er.top - sr.top - (sr.height - er.height) / 2
      }
      return
    }
    p = p.parentElement
  }
}

/**
 * 浮层锚定位置（相对播放器容器）。
 *
 * 面板从"触发它的按钮"上方弹出：按钮在左，面板左边与按钮左边对齐；
 * 按钮靠右放不下时整体左移并按视口收边——固定左下角那种做法，
 * 按钮在中间时视线上要横跨半个屏幕，手感很差。
 *
 * `null` 表示还没算过，回退到旧的安全位置（避免首帧闪动）。
 */
const anchor = ref<Record<Panel, { left: number } | null>>({
  ep: null,
  line: null,
  dm: null,
  more: null,
  help: null,
})

/**
 * 面板宽度（**rem**，必须与模板里的宽度类一致）。
 *
 * ★ 原来这里是写死的 px（ep=352 / more=176 …），而模板用的是 rem
 *   （22rem 宽 / w-44）。TV 的根字号会放大到 18/20/24px
 *   （tokens.css 阶梯），22rem 在 4K 上就是 528px —— 而 clamp 还按 352 算，
 *   右侧就会冲出宿主容器（用户实测：投影仪「更多」弹框右边出界，其它端正常）。
 *   换算成 rem 后，宽度与浏览器实际渲染一致，clamp 才成立。
 */
const PANEL_REM: Record<Panel, number> = { ep: 22, line: 22, dm: 19, more: 11, help: 20 }

/** 根字号（px）——TV 阶梯放大后要跟着变 */
function rootFontSize(): number {
  try {
    return parseFloat(getComputedStyle(document.documentElement).fontSize) || 16
  } catch {
    return 16
  }
}

function anchorTo(target: Panel, ev?: Event) {
  const wrapEl = wrap.value
  const btn = (ev?.currentTarget as HTMLElement | null) || null
  if (!wrapEl) return
  const pad = 8
  const host = wrapEl.getBoundingClientRect()
  // 拿不到按钮（例如从「更多」菜单里间接打开弹幕设置）就退回右侧对齐
  const btnRect = btn?.getBoundingClientRect()
  const width = Math.min(PANEL_REM[target] * rootFontSize(), host.width - pad * 2)
  let left: number
  if (btnRect) {
    // 默认：面板左边缘对齐按钮左边缘
    left = btnRect.left - host.left
    // 右侧溢出就往左推
    if (left + width > host.width - pad) left = host.width - pad - width
  } else {
    left = host.width - pad - width
  }
  // 左侧兜底
  left = Math.max(pad, left)
  anchor.value[target] = { left }
}

function togglePanel(target: Panel, ev?: Event) {
  const isOpen =
    (target === 'ep' && showEpPanel.value) ||
    (target === 'line' && showLinePanel.value) ||
    (target === 'dm' && showDmPanel.value) ||
    (target === 'more' && showMore.value)
  if (isOpen) {
    openPanel(null)
    return
  }
  anchorTo(target, ev)
  openPanel(target)
}
function toggleEpPanel(ev?: Event) {
  togglePanel('ep', ev)
}
function toggleLinePanel(ev?: Event) {
  togglePanel('line', ev)
}
function toggleDmPanel(ev?: Event) {
  togglePanel('dm', ev)
}
function toggleMore(ev?: Event) {
  togglePanel('more', ev)
}

/** 投屏面板的锚点：当前触发入口是「更多」，取它的左边缘。 */
/** 右上角投屏图标的锚点：换算成播放器宿主的左偏移。 */
function castButtonAnchor(ev: Event): { left: number } | undefined {
  const wrapEl = wrap.value
  const btn = ev.currentTarget as HTMLElement | null
  if (!wrapEl || !btn) return undefined
  const host = wrapEl.getBoundingClientRect()
  const r = btn.getBoundingClientRect()
  return { left: r.left - host.left }
}

/** 面板样式：算过位置就锚定按钮，否则回退到左侧安全位 */
function panelStyle(target: Panel) {
  const a = anchor.value[target]
  return a ? { left: `${a.left}px` } : { left: '8px' }
}

/** 点面板外关闭（面板内部点击用 @click.stop 拦住） */
function onWrapClick() {
  if (showEpPanel.value || showLinePanel.value || showDmPanel.value || showMore.value) {
    openPanel(null)
    // 这次点击的意图是"关浮层"，不是"暂停"。
    // 事件先到 stage（已把 toggle 排进 220ms 队列）再冒泡到这里，
    // 所以撤掉队列即可 —— 不要设 suppressClick，那会误伤下一次点击。
    clearTimeout(clickTimer)
  }
  wake()
}

// 控制栏自动隐藏
//
// ★ 计时规则（用户明确要求）：**从最后一次按键/操作重新计时**。
//   每条触发路径都调 wake()，而 wake() 会 clearTimeout 再重排，
//   所以"按一下 → 走 4 秒 → 又按一下"会重新获得完整 5 秒，不会中途消失。
//
// ★ 面板打开时不自动收（原来这里 5 秒后会把面板也一起关掉）。
//   原因：用户点开「更多/选集/线路」后正要上下选，5 秒一到面板连同控制栏
//   一起消失，等于"还没选完就被收走"——实测反馈是"更多弹框无法上下选中"。
//   面板的退出交给返回键（见 __meowTvConsumeBack）或点空白。
/** 最后一次用户操作的时间戳（毫秒）。控制栏续期判据用。 */
let lastUserActionAt = 0
/** 播放器自己的返回链（卸载时判断是不是自己那一层，见 onBeforeUnmount） */
let ownConsumeBack: (() => boolean) | null = null
/** 被播放器包住的上一任返回链（通常是 App.vue 的兜底），卸载时还回去 */
let prevConsumeBack: (() => boolean) | null = null
/** 控制栏自动隐藏的静默时长 */
const CTRL_HIDE_MS = 5000

function wake() {
  // 锁定状态下任何"唤醒"都不该把控制栏放出来（点屏、合成 mousemove 都是）
  if (locked.value) return
  const wasHidden = !showCtrl.value
  showCtrl.value = true
  lastUserActionAt = Date.now()
  clearTimeout(hideTimer)
  /*
   * ★ 从"隐藏"变成"显示"的那一刻，把焦点落到播放/暂停上（2026-10-31）。
   *
   * 只在**状态翻转**时做，不是每次调用都做：
   *   wake() 在按住方向键调进度、拖动进度条、点画面时都会被调，
   *   每次都抢焦点会让用户刚在别的按钮上按两下就被拽回播放键。
   *
   * 等一帧再找：控制栏是 v-if，同一帧里 DOM 还没渲染出来，
   * 立刻 querySelector 拿到 null（实测踩过）。
   */
  if (wasHidden && isTvEnv()) {
    void nextTick(() => window.setTimeout(focusPlayPauseButton, 60))
  }
  hideTimer = window.setTimeout(() => {
    // 面板开着：不动。用户正在里面操作，收走等于打断他。
    const anyPanel =
      showEpPanel.value ||
      showLinePanel.value ||
      showDmPanel.value ||
      showMore.value ||
      // 快捷键说明也是弹层：开着时收起控制栏，它会被一起藏掉但状态还留着
      showHelp.value
    if (anyPanel) return
    /*
     * ★ 控制栏里还画着焦点框，且**刚才有过操作** → 续期而不是收起。
     *
     * 两道判据缺一不可：
     *   · 焦点框在控制栏作用域内：说明用户正在底栏按钮间移动
     *     （限定作用域很重要——用全局 .meow-tv-focus 会让面板关闭后
     *      残留在别处的焦点框把控制栏永远粘住）
     *   · lastUserActionAt 距今 < CTRL_HIDE_MS：说明是**刚刚**动过。
     *     只判焦点框会让控制栏无限续期，用户放下遥控器它也不收。
     *
     * 这道保险是为了兜住事件路径的漏网（例如 tv-nav 版本较老、
     * 没广播 meow:tv-focus），正常情况 onTvFocusMoved 已经续过期了。
     */
    if (isTvEnv() && Date.now() - lastUserActionAt < CTRL_HIDE_MS) {
      const ctrlScope = document.querySelector('[data-ctrl-root]')
      if (ctrlScope && ctrlScope.querySelector('.meow-tv-focus')) {
        wake()
        return
      }
    }
    // ★ 暂停时也隐藏（用户明确要求："播放栏隐藏控制，应该在最后一次按键后
    //   再定时隐藏"）。暂停状态本身由中央的播放按钮 + 时间显示，不需要底栏
    //   常驻；"暂停了就想选集"的用户再按一次下键即可唤出。
    showCtrl.value = false
    // TV：控制栏一收起，焦点框也要撤掉，否则屏幕上留一个黄框、
    // 而这时方向键已回到"改进度/音量"的语义，看着像按键失灵。
    if (isTvEnv()) clearTvFocus()
  }, CTRL_HIDE_MS)
}

/** 撤掉 tv-nav 画的焦点框（控制栏收起时用） */
function clearTvFocus() {
  const els = document.querySelectorAll('.meow-tv-focus')
  for (let i = 0; i < els.length; i++) els[i].classList.remove('meow-tv-focus')
  // ★ 控制栏收起时必须摘掉 scope，否则焦点范围永远被限在底栏里，
  //   用户按方向键再也走不到画面/面板之外（实测：控制栏隐藏后
  //   方向键仍只会在底栏按钮间打转）。
  const ctrl = document.querySelector('[data-tv-scope="ctrl"]')
  if (ctrl) ctrl.removeAttribute('data-tv-scope')
}

/**
 * 把焦点落到控制栏的「播放/暂停」按钮上（TV 专用）。
 *
 * <p><b>为什么要它</b>（2026-10-31 用户要求）：控制栏刚被唤出时，
 * 焦点要么还在控制栏外面（用户按 down 唤出来的），要么根本没焦点。
 * 这时按右键会去做几何选焦，落到哪个按钮全看布局——实测经常跳到
 * 「选集」甚至画面外。用户想要的是"控制栏一出来就停在播放/暂停上"，
 * 然后再左右移动。
 *
 * <p>用 aria-label 定位而不是索引：播放/暂停按钮的文案会随状态变
 * （播放↔暂停），按顺序取第一个会在不同状态下选到不同的按钮。
 */
function focusPlayPauseButton() {
  if (!isTvEnv()) return
  /*
   * ★ 用 `[data-tv-primary]` 直接定位，不要经过 `[data-tv-scope="ctrl"]`。
   *
   * 踩过（2026-11-02 复查）：控制栏曾经打过 `data-tv-scope="ctrl"`，后来因为
   * "焦点在两套候选集之间横跳"又摘掉了（见 focusFirstCtrlButton 末尾的注释），
   * 但这里和下面 hideTimer 的守卫还按 scope 找 —— 结果**永远找不到**，
   * "唤出控制栏后聚焦播放/暂停"整个功能是死代码。用户看到的是
   * "控制栏出来了，但焦点要按右才出现，还不是播放键"。
   */
  const btn = document.querySelector<HTMLElement>('[data-tv-primary]')
  if (!btn) return
  // 收起状态下按钮是 visibility:hidden，focus 会失败（也说明此刻不该聚焦）
  if (!showCtrl.value) return
  const cur = document.querySelector('.meow-tv-focus')
  // 焦点已经在这个按钮上就别重复设（每次 wake 都设会让焦点框闪）
  if (cur === btn) return
  clearTvFocus()
  btn.classList.add('meow-tv-focus')
  try {
    btn.focus({ preventScroll: true })
  } catch {
    /* 老内核不支持 focus 选项 */
  }
}

/**
 * 焦点移动也算"用户在操作"，要重置隐藏计时。
 *
 * <p><b>为什么单独订阅</b>：控制栏显示时存在 `.meow-tv-focus`，方向键会被
 * tv-nav 的 move() 直接消费去移动焦点，**根本不经过本组件的 keydown**
 * （见 tv-nav.js 里「有焦点框就移焦点，不再派发给播放器」那段）。
 * 于是 wake() 从来没被调用，5 秒定时器照原计划走完 → 用户正在左右选按钮，
 * 控制栏突然收走，还没来得及按到想去的位置。
 *
 * <p><b>为什么用 DOM 事件而不是让 tv-nav 直接调页面函数</b>：
 * tv-nav 是壳注入的独立脚本，不该反向依赖某个页面的内部函数
 * （播放页会卸载、直播页也有自己的逻辑）。由 tv-nav 广播一个"焦点变了"
 * 的中性事件，页面各自决定要不要理会——这是壳与页面之间代价最小的契约。
 */
function onTvFocusMoved() {
  // 只在控制栏可见时续期：收起后焦点框已撤、方向键已回到改进度语义，
  // 那时若还续期就会让控制栏再也收不回去。
  if (!showCtrl.value) return
  wake()
}

/** 离开播放页时要摘掉，否则监听会留在全局（切集是重建页面，会累积） */
function offTvFocusListener() {
  window.removeEventListener('meow:tv-focus', onTvFocusMoved)
}

/**
 * TV：把焦点落到控制栏第一个按钮上。
 *
 * 用户按 ok/返回/左右的时候，第一个可聚焦元素往往还是页头那个「返回」——
 * 那不是控制栏的按钮，按 ok 就退出了播放页（实测踩过）。
 * 所以唤出控制栏后必须**主动**把焦点搬到控制栏内部。
 */
function focusFirstCtrlButton() {
  if (!isTvEnv()) return
  // ★ 面板优先：面板开着时，"下键唤栏"的意图其实是"我要在面板里选"。
  //   原来一律找底栏按钮，于是面板开着按 down 会把焦点抢回底栏，
  //   面板里上下选不动（实测反馈"更多弹框无法上下选中"）。
  //   ★ 只认真正的面板（[data-tv-scope] 且不是控制栏自己的 "ctrl" 标记）。
  const allScopes = Array.prototype.slice.call(
    document.querySelectorAll('[data-tv-scope]'),
  ) as HTMLElement[]
  const panels = allScopes.filter((el) => el.getAttribute('data-tv-scope') !== 'ctrl')
  if (panels.length) {
    const cur = document.querySelector('.meow-tv-focus')
    // 焦点已经在面板里：什么都不做，让 tv-nav 正常上下移动
    if (cur && panels[panels.length - 1].contains(cur)) return
    focusInPanel(showMore.value ? 'more' : showEpPanel.value ? 'ep'
      : showLinePanel.value ? 'line' : showDmPanel.value ? 'dm'
        : showHelp.value ? 'help' : 'more')
    return
  }
  // 控制栏是底栏，取视口下半部分里可聚焦且可见的第一个
  const sel = 'a[href],button,input,select,textarea,[tabindex]:not([tabindex="-1"])'
  const all = document.querySelectorAll(sel)
  let first: HTMLElement | null = null
  for (let i = 0; i < all.length; i++) {
    const el = all[i] as HTMLElement
    const r = el.getBoundingClientRect()
    if (r.width < 2 || r.height < 2) continue
    const st = getComputedStyle(el)
    if (st.visibility === 'hidden' || st.display === 'none' || st.opacity === '0') continue
    // 底栏按钮：贴底 40% 区域内
    if (r.top < window.innerHeight * 0.6) continue
    first = el
    break
  }
  if (!first) return
  clearTvFocus()
  first.classList.add('meow-tv-focus')
  try {
    first.focus({ preventScroll: true })
  } catch {
    /* 老内核不支持 focus 选项，忽略 */
  }
  // ★ 不用 data-tv-scope 收窄控制栏 —— 试过，副作用太大。
  //
  //   实测：给底栏打上 data-tv-scope="ctrl" 后，tv-nav 的 activeScope()
  //   会把它当成"弹层"，于是 items() 只返回底栏按钮；但焦点框一消失
  //   （MutationObserver 纠正、或按钮文案变化导致索引错位）就又退回全文档，
  //   在两套候选集之间反复横跳 —— 表现是"按两下右键焦点就没了"。
  //
  //   底栏按钮本来就是横向一排，几何选焦用全文档也不会乱跳（实测
  //   right1→x181、right2→上一集 都是对的），真正的问题是上面那个
  //   MutationObserver 的宽限期，已在 tv-nav.js 里修掉。
}

/** 输入框 / 文本域里按键不应触发播放器快捷键 */
function isTyping(e: KeyboardEvent): boolean {
  const t = e.target as HTMLElement | null
  if (!t) return false
  const tag = t.tagName
  return (
    tag === 'INPUT' ||
    tag === 'TEXTAREA' ||
    tag === 'SELECT' ||
    t.isContentEditable ||
    // 自定义下拉（RateSelect）内部也可能有按钮，只拦真正可输入的元素
    (t as HTMLInputElement).type === 'text' ||
    (t as HTMLInputElement).type === 'search'
  )
}

// 键盘
function onKey(e: KeyboardEvent) {
  const v = video.value
  if (!v) return
  // ★ 控制栏可见时，方向键要交给 tv-nav 去移动焦点，播放器不能再抢。
  //
  //   为什么：TV 上控制栏一出来，用户下一步必然是"左右选按钮、ok 触发"。
  //   如果这时左右还在改进度，焦点永远卡在第一个按钮上——实测就是
  //   `焦点='返回'` 反复不动，选集/线路面板都打不开。
  //
  //   所以按键语义按"控制栏是否可见"分两套（这也是用户明确要的区分）：
  //     控制栏隐藏 → 左/右=±5s 进度，上/下=唤出控制栏，ok=暂停/播放
  //     控制栏显示 → 方向键=移动焦点，ok=触发该按钮（播放器全部放行）
  //   只对 TV 生效；PC/Web 没有焦点导航（tv-nav 不注入），保持原语义。
  //
  //   ★ 怎么回到"隐藏态"：控制栏自己没有收起按钮，靠 5s 无操作自动隐藏
  //     （wake() 的定时器）。tv-nav 移动焦点时不会再调 wake()，
  //     所以选完按钮停手就会自动隐藏，然后方向键恢复进度/音量语义。
  //
  //   ★ ArrowDown 不能在这里被放行：它是"唤出并聚焦第一钮"的入口，
  //     必须进 switch 走 ArrowDown 分支。实测漏了这条 → 按 down 控制栏出来了
  //     但焦点仍是 NONE，后续左右/ok 全部操作不到按钮。
  //
  //   ★ 面板开着时也必须放行（不看 showCtrl）：面板可能在控制栏已隐藏时
  //     仍打开着（比如控制栏超时收起但用户还在选集里翻），这时方向键若被
  //     播放器拿去改进度，面板里就选不动了。
  //
  //   ★ 有焦点框时同样放行：tv-nav 只在"页面上已经画出焦点框"时才会调用
  //     方向键移动逻辑；这时用户的意图明确是"在按钮间移动"，播放器再抢就是
  //     两套语义打架。实测症状：控制栏 5s 超时隐藏了，但焦点框还留着，
  //     按右键播放器先去 seek（preventDefault），tv-nav 以为播放器处理了
  //     就 return true，于是焦点**永远走不动**（right3 起变 NONE）。
  const panelOpen = showEpPanel.value || showLinePanel.value || showDmPanel.value
    || showMore.value || showHelp.value
  /*
   * ★ 焦点判据必须是"焦点框还在一个**真的可见**的元素上"（2026-10-31 修）。
   *
   * 原来只查 `document.querySelector('.meow-tv-focus')` —— 元素存在就算有焦点。
   * 但面板关闭/换集/控制栏自动收起之后，那个 class 会**残留**在已经被
   * `display:none` 或移出文档的节点上（tv-nav 的 MutationObserver 有宽限期，
   * 期间不清 class）。
   *
   * 后果（用户报的"偶尔按上/下键唤不出控制栏"）：
   *   残留 class → 这里判定 hasTvFocus=true → 除 ArrowDown 外所有键都被
   *   `return` 放行给 tv-nav；而 tv-nav 拿这个不可见的焦点当起点，
   *   几何选焦找不到邻居 → 什么都不做。于是**按键完全没反应**，
   *   只有 ok/返回（不在放行名单里）还能触发暂停、借暂停浮层把控制栏顶出来。
   *   这解释完全吻合"只能靠返回或 ok 引起的暂停呼出控制栏"。
   */
  const hasTvFocus = typeof document !== 'undefined' && (() => {
    const list = document.querySelectorAll('.meow-tv-focus')
    for (let i = 0; i < list.length; i++) {
      const el = list[i] as HTMLElement
      if (!el.isConnected) continue
      const st = getComputedStyle(el)
      // visibility 会继承：控制栏收起时整条栏 invisible，子孙的 computed 也是 hidden
      if (st.visibility === 'hidden' || st.display === 'none') continue
      if (!el.offsetParent && st.position !== 'fixed') continue
      const r = el.getBoundingClientRect()
      if (r.width < 2 || r.height < 2) continue
      return true
    }
    return false
  })()
  if (isTvEnv() && (showCtrl.value || panelOpen || hasTvFocus) && e.key !== 'ArrowDown'
      && !(e.key === 'Escape' || e.key === '?'
        || e.key === '/' || e.key === 'f' || e.key === 'm' || e.key === 'd')) {
    /*
     * ★ 但「控制栏隐藏 + 控件没有真正拿到焦点」时，ok 必须是暂停/播放。
     *
     * 用户明确要求：控制栏隐藏时 ok 键要明确控制暂停/播放，左右键改进度。
     * 原来这里一律 return（放行给 tv-nav），只有"完全没有焦点框"才会走到
     * 下面的 Enter 分支 —— 于是焦点框残留的那些时刻，ok 变成了"去点某个按钮"，
     * 用户按一下发现没暂停（或误触了别的按钮）。
     *
     * 判据：面板都没开 且 控制栏不可见 → 这是"看片"状态，ok 归播放器。
     */
    if (e.key === 'Enter' && isTvEnv() && !panelOpen && !showCtrl.value) {
      e.preventDefault()
      toggle()
      return
    }
    return
  }
  // 在输入框里打字时，空格/方向键属于输入法，不能抢。
  // 但 Esc 例外：搜索框里的 Esc 必须能关弹窗（见下面），
  // 否则用户点进输入框后就再也按不掉弹窗了。
  const typing = isTyping(e)
  if (typing && e.key !== 'Escape') return
  // 有弹窗开着时，Esc 关弹窗，其余快捷键不响应
  if (showDmSearch.value) {
    if (e.key === 'Escape') {
      e.preventDefault()
      closeDmSearch()
      wake()
    }
    return
  }
  // 带修饰键的组合（Ctrl/Cmd+F 查找、Ctrl+D 收藏…）交还给浏览器/输入法，
  // 否则会和全屏、调试面板撞在一起同时触发
  if (e.ctrlKey || e.metaKey || e.altKey) return
  switch (e.key) {
    case ' ':
    case 'k':
      e.preventDefault()
      toggle()
      break
    case 'ArrowLeft':
      e.preventDefault()
      if (e.repeat) {
        // 按住不放：进入 2x 持续扫动，而不是每次都跳
        startKeyScan(-1)
        break
      }
      stopKeyScan()
      {
        const step = takeSeekStep()
        v.currentTime = Math.max(0, v.currentTime - step)
        currentTime.value = v.currentTime
        flashSeekHint(-step)
      }
      break
    case 'ArrowRight':
      e.preventDefault()
      if (e.repeat) {
        startKeyScan(1)
        break
      }
      stopKeyScan()
      {
        const step = takeSeekStep()
        userSeek(v.currentTime + step)
        flashSeekHint(step)
      }
      break
    case 'ArrowUp':
      // TV：上键 = 静音开关（2026-11-03 用户定）。遥控器自带独立音量键，
      // 页面再调音量会和系统音量叠加；而「静音」遥控器上没有，是最常缺的那个。
      // PC/Web：保留原有调音量（键盘用户没有独立音量键）。
      e.preventDefault()
      if (isTvEnv()) {
        const vv = video.value
        if (!vv) break
        vv.muted = !vv.muted
        muted.value = vv.muted
        savePrefs({ muted: muted.value }, { silent: true })
        flashMarks(vv.muted ? '已静音' : '已取消静音')
        break
      }
      v.volume = Math.min(1, v.volume + 0.1)
      volume.value = v.volume
      muted.value = false
      /*
       * ★ 必须同时写 `v.muted`（2026-11-03 核实：静音后按 ↑ 依然没声音）。
       *   原来只改 `muted.value`（Vue 状态）和 `v.volume`，而 `<video>.muted`
       *   还是 true —— 音量调到 100% 也照样静音，用户会以为音量键坏了。
       */
      v.muted = false
      savePrefs({ volume: v.volume, muted: false }, { silent: true })
      break
    case 'ArrowDown':
      // TV：下键 = 唤出控制栏，并把焦点落到控制栏第一个按钮上。
      // 这是"两段式"里的第一段：控制栏显示后，方向键改为移动焦点、ok 触发按钮
      // （见 onKey 开头的分流）。5s 无操作自动隐藏后恢复进度/音量语义。
      e.preventDefault()
      if (isTvEnv()) {
        wake()
        // 等 Vue 把控制栏渲染出来再找按钮（同一帧里 layout 还没算）
        nextTick(() => {
          window.setTimeout(focusFirstCtrlButton, 60)
        })
        break
      }
      v.volume = Math.max(0, v.volume - 0.1)
      volume.value = v.volume
      muted.value = false
      // 同上：静音状态下按 ↓ 也要真的出声，否则"按了没反应"
      v.muted = false
      savePrefs({ volume: v.volume, muted: false }, { silent: true })
      break
    case 'Enter':
      // ★ ok 键的语义按 TV 通用习惯 = 暂停/播放，必须自己吃掉。
      //   不吃会穿透到 tv-nav 的 enter() → 命中某个按钮；实测投影仪上
      //   播放中按 ok 直接退出了播放页（命中底栏第一个按钮）。
      //   PC/Web 不拦（Enter 留给按钮激活）。
      if (isTvEnv()) {
        e.preventDefault()
        toggle()
      }
      break
    case '?':
    case '/':
      // 一堆快捷键没有任何界面入口，`?` 是通用约定
      e.preventDefault()
      openPanel(showHelp.value ? null : 'help')
      break
    case 'f':
      toggleFullscreen()
      break
    case 'm':
      toggleMute()
      break
    case 'd':
      // 播放信息调试面板（developer）。不设修饰键，方便随时按。
      toggleDebug()
      break
    case 'Escape':
      // 伪全屏时 Esc 要先退出铺满；真全屏由浏览器自己处理
      if (pseudoFs.value) {
        void exitPseudoFs()
        break
      }
      openPanel(null)
      break
  }
  // ★ TV 的左右键不唤控制栏。
  //
  //   原来这里无条件 wake()，于是"调进度"顺带把控制栏也叫了出来——
  //   下次再按左右时控制栏已经可见，按键就被 tv-nav 拿去移焦点了，
  //   用户看到的是"按一下右键跳到 5 秒后，再按一下焦点却在按钮上跑"。
  //   左右键的反馈已经由 flashSeekHint 的中央提示给出，不需要控制栏。
  //   PC/Web 保留原行为（没有焦点导航，不存在这个冲突）。
  const tvSeekOnly = isTvEnv() && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')
  if (!tvSeekOnly) wake()
}

/** 松开方向键：结束 2x 扫描，恢复原倍速 */
function onKeyUp(e: KeyboardEvent) {
  if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') stopKeyScan()
}

// ---------------------------------------------------------------- danmaku

/**
 * 弹幕来源的中文名。与后端 DANMAKU_PRIORITY 的取值一一对应，
 * 另加 unknown（老缓存没记来源）与 none（全部失败）。
 */
const dmSourceLabel = computed(() => {
  const map: Record<string, string> = {
    iqiyi: '爱奇艺',
    tencent: '腾讯',
    '360': '360',
    danmu_api: '弹幕聚合',
    aggregator: '第三方站',
    unknown: '未知来源',
    none: '所有源',
  }
  return map[dmSource.value] || ''
})

/**
 * 时间校准的持久化键：播放器拿到的 title 是「片名 · 集」，天然按片/集区分。
 * 不用 site/vod_id —— 偏移取决于视频版本，换线路只要是同一版就该继续生效。
 */
const dmOffsetKey = computed(() => (props.title || '').trim() || props.danmakuUrl || '')
const dmOffset = ref(getDmOffset(dmOffsetKey.value))
const dmOffsetLabel = computed(() => formatDmOffset(dmOffset.value))

/** 改校准值：落库 + 立即重锚（清屏回看 0.5 秒，避免旧位置残留）。 */
function applyDmOffset(sec: number) {
  const v = normalizeDmOffset(sec)
  dmOffset.value = setDmOffset(dmOffsetKey.value, v)
  dm.value?.setOffset(dmOffset.value)
  const el = video.value
  if (el) dm.value?.sync(el.currentTime, { seek: true })
}

function bumpDmOffset(delta: number) {
  applyDmOffset(dmOffset.value + delta)
}

function clearDmOffset() {
  resetDmOffset(dmOffsetKey.value)
  dmOffset.value = 0
  dm.value?.setOffset(0)
  const el = video.value
  if (el) dm.value?.sync(el.currentTime, { seek: true })
}

/**
 * 把弹幕设置推给原生 overlay（端内原生渲染模式，2026-11-27）。
 *
 * <p>原生画弹幕时，透明度/字号/速度/区域/屏蔽词/校准全都只存在 web 侧；
 * 原生只拿得到拉起那一刻的 metaJson。不实时推的话，用户在原生页里打开
 * 「弹幕设置」怎么调都没反应（真机实报：弹幕挤、显示区域对不上）。
 */
function pushDmOptionsToNative() {
  if (!iosNative) return
  const p = prefs.value
  iosNativeCmd('dmOptions', {
    opacity: p.danmakuOpacity,
    scale: p.danmakuScale,
    duration: p.danmakuDuration,
    area: p.danmakuArea,
    block: props.dmBlockWords || '',
    offset: dmOffset.value,
  })
}

if (iosNative) {
  // 面板改设置会替换 prefs 对象；屏蔽词/校准走各自的 ref。任一变化都推一次
  watch(
    () => {
      const p = prefs.value
      return [
        p.danmakuOpacity,
        p.danmakuScale,
        p.danmakuDuration,
        p.danmakuArea,
        props.dmBlockWords || '',
        dmOffset.value,
      ].join('|')
    },
    () => pushDmOptionsToNative(),
  )
  /*
   * 换集后的弹幕地址（2026-11-27）：原生只拿到拉起那一刻的 danmakuUrl，
   * 不推的话切到第 2 集还在放第 1 集的弹幕。空值不发（弹幕还在解析/没有）。
   */
  watch(
    () => props.danmakuUrl,
    (u) => {
      if (u) iosNativeCmd('dmUrl', u)
    },
  )
}

/**
 * 弹幕屏蔽。
 *
 * <p>三条规则：屏蔽词命中、纯符号刷屏、同屏重复（同一条文本在极短时间内
 * 连发多次——引战和刷屏的主要形态）。
 *
 * <p>为什么在**加载时**过滤而不是渲染时：渲染每帧都在跑，逐条做字符串
 * includes 会白吃 CPU；加载时一次性过滤，之后每帧只处理剩下的。
 */
function filterDanmaku(list: Danmu[]): Danmu[] {
  const words = String(props.dmBlockWords || '')
    .split(/[,，\n\r]+/)
    .map((w) => w.trim())
    .filter(Boolean)
  if (!words.length && !list.length) return list

  // 纯符号/纯空白（去掉常见标点后什么都不剩）
  const pureSymbol = (t: string) => !/[\p{L}\p{N}]/u.test(t)

  const out: Danmu[] = []
  // 同文本的最近出现时间：1.5 秒内重复视为刷屏，只留第一条
  const lastAt = new Map<string, number>()
  for (const d of list) {
    if (!d.text) continue
    if (pureSymbol(d.text)) continue
    if (words.some((w) => d.text.includes(w))) continue
    const prev = lastAt.get(d.text)
    if (prev !== undefined && Math.abs(d.time - prev) < 1.5) continue
    lastAt.set(d.text, d.time)
    out.push(d)
  }
  return out
}

/**
 * 丢弃时间轴超出本集片长的弹幕（2026-11-16 用户报「弹幕数虚高」）。
 *
 * <p>为什么源侧会给出超片长的弹幕：弹幕源与播放源的**版本时长不一致**。
 * 实测《庆余年第二季》第 01 集：播放源 45:24，腾讯该 cid 的弹幕时间轴到
 * 3983s（66 分钟版本）——31.4%（12549 条）永远不可能被画出来，白占
 * 带宽/缓存/渲染队列内存（32 位 TV 上尤其明显）。
 *
 * <p>为什么留 60 秒容差而不是 0：片源与弹幕版本的片头/片尾常有几十秒偏差，
 * 卡死 0 会把"本应显示"的片尾弹幕误删。
 *
 * <p>duration 未就绪（0/NaN，例如 HLS 清单还没解析完就先把弹幕显示出来）
 * 时**不过滤**——否则会把全部弹幕当超长丢掉，回到"进播放页没弹幕"。
 */
const DM_TAIL_MARGIN_SEC = 60

function filterByDuration(list: Danmu[]): Danmu[] {
  const d = duration.value
  if (!(d > 0) || !Number.isFinite(d)) return list
  const max = d + DM_TAIL_MARGIN_SEC
  return list.filter((x) => x.time <= max)
}

/**
 * 应用一批弹幕（唯一的入口）：记录原始列表 → 按片长过滤 → 交给渲染器 →
 * 更新「可用/共」计数。本地缓存、网络、后台补全三处都走这里，保证口径一致。
 *
 * @param totalHint 后端 X-Danmaku-Total（截断前真实条数），没传则用 list 长度
 */
function applyDmList(
  raw: Danmu[],
  totalHint = 0,
  opts: { keepActive?: boolean; at?: number } = {},
) {
  dmRawList = raw
  dmAppliedDur = duration.value
  dmTotal.value = Math.max(raw.length, totalHint)
  dmTruncated.value = totalHint > 0 && totalHint > raw.length
  const usable = filterByDuration(raw)
  dmCount.value = usable.length
  dmQuality.value = dmQualityStats(usable, duration.value)
  /*
   * 150 桶而不是 300：45 分钟片 = 18 秒/桶，单桶约 180 条。
   * 300 桶（9 秒/桶）会把分布平均掉，峰谷几乎等高（用户 2026-11-20 反馈）。
   */
  const heat = danmakuHeatData(usable, duration.value, 150)
  dmHeat.value = heat?.counts ?? null
  dmHeatRef.value = heat?.ref ?? 1
  dmHeatBucketSec.value = heat?.bucketSec ?? 0
  const filtered = filterDanmaku(usable)
  /*
   * keepActive：后台补全/片长修正只换 queue，不清屏。
   * 走 load() 会把 lastSync 置 -1，紧接着的 sync 必然判成跳转，
   * 用户看到“弹幕消失再重打”。replaceQueue 保留在场弹幕，新弹幕从
   * 当前时间接上。
   */
  if (opts.keepActive && dm.value) {
    dm.value.replaceQueue(filtered, opts.at ?? video.value?.currentTime ?? 0)
  } else {
    dm.value?.load(filtered)
  }
}

async function loadDanmaku(
  url: string,
  opts: { fresh?: boolean; serverOnly?: boolean; pick?: boolean } = {},
) {
  /*
   * 端内原生渲染：**每一次**真正发生的弹幕加载都要同步给原生（2026-11-27）。
   *
   * 之前只在 props.danmakuUrl 变化时推，漏了两条同样会换源的路径：
   *   ① 30 秒自动重试（同 URL 重跑一次，上一次失败时原生那边还是空的）；
   *   ② 面板手选弹幕源（走 dmOverrideUrl，不改 props）。
   * 同 URL 是否重拉由原生侧判断（danmakuXml 为空才重拉），不会重复请求。
   */
  if (iosNative && url) iosNativeCmd('dmUrl', url)
  const fresh = opts.fresh === true
  /**
   * 只看后端（跳过 IndexedDB 读，网络请求**不带** fresh）。
   *
   * 后台补全检查用：先读后端 30 分钟缓存——聚合源的补全结果此时多半已写完，
   * 而 fresh=1 会绕过缓存重抓（重新"先到先用"，又拿回直连的小份）。
   */
  const serverOnly = opts.serverOnly === true
  /**
   * 用户手选了一条源（`pickDm`）。
   *
   * 手选也带 fresh（必须绕过缓存，否则同 URL 的旧剧副本会串片），但它的
   * **结果必须无条件采用**——不能走下面「后台补全」那套门槛：
   * 那里要求「新结果 ≥1.2 倍才算更好」且「dmOverrideUrl 为空」，而 pickDm
   * 刚把 dmOverrideUrl 设成所选 URL，于是手选结果永远被丢弃，表现为
   * 「选中后弹幕没反应、条数不变」（2026-11-16 定因；2026-11-15 的
   * d3d30b4 把 pickDm 从普通路径改成 fresh 时引入，普通路径是直接 apply 的）。
   */
  const pick = opts.pick === true
  if ((!fresh && !serverOnly) || pick) {
    dmLoading.value = true
    clearDmRetry()
  }
  try {
    // 本地优先：断网时后端根本连不上，IndexedDB 里那份是唯一能出的弹幕。
    // 联网时也先用本地——省一次网络往返，且弹幕内容不会因为源站抖动而变。
    //
    // ★ fresh 检查必须绕过本地：否则 30 天的旧副本会永远挡住后台补全
    //   （2026-11-09 实锤：首屏 8000 条写本地后，服务端补全的 12000 永远取不到）。
    if (!fresh && !serverOnly) {
      const local = await getLocalDanmaku(url)
      if (local) {
        const cached: Danmu[] = parseDanmakuXml(local.xml)
        if (cached.length) {
          dmSource.value = local.source || 'local'
          dmLocalCount.value = cached.length
          applyDmList(cached)
          const cv = video.value
          if (cv) dm.value?.sync(cv.currentTime)
          dmLoading.value = false
          dmEmpty.value = false
          scheduleDmUpgrade(url)
          return
        }
      }
    }
    // fresh 必须同时绕过客户端 IndexedDB 和后端 30 分钟缓存：
    // 只加 cache:'no-store' 只绕过浏览器 HTTP 缓存，后端仍会回同一份。
    const res = await fetch(
      fresh ? freshDanmakuUrl(url) : url,
      fresh ? { cache: 'no-store' } : undefined,
    )
    /*
     * 后台补全检查先用 X-Danmaku-Count 短路，不读 body、不解析。
     *
     * 5 个检查点（8/12/16/22/30 秒）过去每次都把 4MB XML 完整下载 +
     * DOMParser 重建一遍，而绝大多数时候缓存里还是同一份（实测 6 次
     * 4.19MB GET、每次 DOM +23.7MB / 128ms）。原始条数都不到当前可用
     * 条数的 1.2 倍时，过滤后的结果不可能更好，直接 cancel 即可。
     *
     * ★ header 缺失时 count=0，shouldSkipDanmakuBody 会返回 false，
     *   继续走原解析路径 —— 不能把缺失当“0 条更少”。
     */
    if (!pick && (fresh || serverOnly)) {
      const countHeader = Number(res.headers.get('X-Danmaku-Count') || 0) || 0
      if (shouldSkipDanmakuBody(countHeader, dmCount.value, dmCount.value === 0)) {
        try {
          await res.body?.cancel()
        } catch {
          /* body 已被消费/不可取消：忽略，下一次检查点还会再判断 */
        }
        return
      }
    }
    const xml = await res.text()
    // 后端用响应头说明"这份弹幕来自哪个源、共几条、是不是空的"。
    // 读头而不解析 body 判断：XML 的 <i></i> 空壳在两种情况下都长一样，
    // 分不清"这集本来没弹幕"和"所有源都失败了"。
    const src = res.headers.get('X-Danmaku-Source') || ''
    const empty = res.headers.get('X-Danmaku-Empty') === '1'
    // 后端裁到上限时给出截断前的真实条数（X-Danmaku-Total），用于显示 "N+"
    const totalHeader = Number(res.headers.get('X-Danmaku-Total') || 0) || 0
    const list: Danmu[] = parseDanmakuXml(xml)

    /*
     * 手选：用户明确点了这条源，直接整份采用。
     * 不看条数多少（选了就得用）、不等安全点（点击本身就是用户的意图）、
     * 也不受 dmOverrideUrl 门槛（它就是这次手选设的）。
     */
    if (pick) {
      dmLocalCount.value = 0
      // 手选走的是后端 url 直取分支，没有 X-Danmaku-Source 头；
      // 用 pickDm 记住的来源 key 兜底，面板才不显示"未知来源"。
      dmSource.value = (src && src !== 'none' ? src : '') || dmManualSource.value
      applyDmList(list, totalHeader)
      dmEmpty.value = list.length === 0 || empty
      dmRetried = false
      // 与普通路径一致：非空才存，空结果多是上游抖动，存了下次也读到空
      if (list.length && !empty) {
        void putLocalDanmaku(url, xml, list.length, src)
      }
      const pv = video.value
      if (pv) dm.value?.sync(pv.currentTime)
      return
    }

    /*
     * 后台补全检查：首屏之后按检查点再问一次，如果新结果明显更全（≥1.2 倍）就
     * 在安全点整份替换，而不是只写缓存。
     *
     * 安全点：暂停时任意位置、开头 60 秒，或用户刚主动 seek。播放中途无提示地
     * 换会让弹幕突然变密/位置跳动，比少几千条更糟。
     *
     * ★ 比较口径是**按片长过滤后的可用条数**（2026-11-16）：聚合源与直连源
     *   的原始条数可能只差 5%（42293 vs 40000 过不了 1.2 门槛），但聚合源的
     *   时间轴与片长对齐（可用 42292 vs 28046 = 1.51 倍）——按总条数比较会把
     *   真正更全的合并结果丢掉。
     */
    if (fresh || serverOnly) {
      const usable = filterByDuration(list).length
      /*
       * 从 0 条补上：首屏全源失败（上游抖动/后端 30 秒负缓存）时 dmCount 是 0，
       * isBetterDanmaku 对 current<=0 一律返回 false —— 后台补全拿到 4 万条也
       * 应用不了，用户整集看不到弹幕。这种"从无到有"直接应用：
       * 不会让已在场的弹幕跳动，用户只会觉得"终于有了"。
       */
      const fillEmpty = dmCount.value === 0 && usable > 0
      const better = isBetterDanmaku(dmCount.value, usable)
      if (!empty && (fillEmpty || better) && !dmOverrideUrl.value) {
        const up = { url, xml, count: list.length, total: totalHeader, source: src }
        const v = video.value
        const safe = isSafeDanmakuSwap(v?.currentTime || 0, dmLastSeekAt, Date.now(), v?.paused === true)
        if (fillEmpty || safe) applyDmUpgrade(up)
        else dmPendingUpgrade.value = up
      }
      return
    }

    dmLocalCount.value = 0
    dmSource.value = src && src !== 'none' ? src : ''
    applyDmList(list, totalHeader)
    // 非空才存：空结果多半是上游抖动，存下来会让下次也读到空
    if (list.length && !empty) {
      void putLocalDanmaku(url, xml, list.length, src)
    }
    /*
     * 空结果**也要**安排检查点（2026-11-16 复查）：原来只在非空时排，
     * 首屏空 → 8~30 秒的后台补全永远取不到，只能等 30 秒那一次重试；
     * 重试也空就整集没弹幕。现在交给检查点 + fillEmpty 兜住。
     */
    scheduleDmUpgrade(url)
    // 手动换源或切换剧集后，从当前进度继续显示，不从头开始
    const v = video.value
    if (v) dm.value?.sync(v.currentTime)
    // 所有源都没拿到（多半是上游接口抖动，实测爱奇艺偶发返回空）。
    // 后端只把失败缓存 30 秒，所以 30 秒后重试有较大概率拿到。
    // 只重试一次，避免用户看到无限请求。
    //
    // ★ 第二次仍然空时**必须复位**（2026-11-27 修）：原来写成
    //   `if (empty && !dmRetried) {...} else if (!empty) {...}`，
    //   第二次落空时两个分支都不进 → dmEmpty 永远为 true → 顶栏一直挂
    //   「弹幕加载失败（重试中…）」，可后端早就用 X-Danmaku-Empty:1
    //   说过"这一集没有弹幕"，用户看到的是持续失败而不是"没有弹幕"。
    //   现在区分三态：加载中 / 首次失败（会重试）/ 确实没有弹幕。
    if (!empty) {
      dmRetried = false
      dmEmpty.value = false
      dmNoData.value = false
    } else if (!dmRetried) {
      dmRetried = true
      dmEmpty.value = true
      dmNoData.value = false
      dmRetryTimer = window.setTimeout(() => {
        if (props.danmakuUrl === url) loadDanmaku(url)
      }, 30_000)
    } else {
      // 重试过还是空：如实说"没有弹幕"，别再挂着"重试中"骗用户
      dmEmpty.value = false
      dmNoData.value = true
      dmRetryTimer = undefined
    }
  } catch {
    if (fresh) {
      /*
       * 手选失败要说话：面板已经关了，静默 return 用户会以为"点了没反应"。
       * 保留当前在场的弹幕（可能是自动源那份），只提示这次没取到。
       */
      if (pick) showDmHint('这个弹幕源暂时取不到，换个源试试')
      return
    }
    // 网络彻底失败（断网/后端挂了）：再试一次本地。
    // 前面已经查过，但那次可能因为 IndexedDB 还没打开而返回 null。
    const fallback = await getLocalDanmaku(url)
    if (fallback) {
      const cached: Danmu[] = parseDanmakuXml(fallback.xml)
      dmSource.value = (fallback.source || 'local') + '（离线）'
      dmCount.value = cached.length
      dmLocalCount.value = cached.length
      dm.value?.load(filterDanmaku(cached))
      const fv = video.value
      if (fv) dm.value?.sync(fv.currentTime)
    } else {
      dmCount.value = 0
    dmTotal.value = 0
    dmTruncated.value = false
    dmRawList = []
    dmHeat.value = null
    dmHeatRef.value = 1
    dmHeatBucketSec.value = 0
    dmQuality.value = null
      dmSource.value = ''
    }
  } finally {
    // pick 虽然带 fresh（绕过缓存），但它是用户点出来的请求，必须收掉 loading
    if (!fresh || pick) dmLoading.value = false
  }
}

/**
 * 清掉后台补全状态（切集/手选源/恢复自动时调用）。
 *
 * <p>每集独立：上一集挂起的"更全结果"绝不能带到下一集。
 */
function clearDmUpgrade() {
  for (const t of dmUpgradeTimers) window.clearTimeout(t)
  dmUpgradeTimers = []
  dmUpgradeChecked = false
  dmPendingUpgrade.value = null
  dmUpgradeApplied.value = false
}

/** 首屏成功后按 {@link DM_UPGRADE_CHECKPOINTS} 安排后台补全检查（每集一次）。 */
function scheduleDmUpgrade(url: string) {
  if (dmUpgradeChecked || dmOverrideUrl.value) return
  dmUpgradeChecked = true
  /*
   * 每个检查点**只读后端缓存**（serverOnly），不碰 IndexedDB、不强制重抓。
   *
   * 为什么不用 fresh：fresh 绕过缓存重新并发抓，又落回"直连先到"的小份，
   * 拿不到后台补全的聚合结果（2026-11-16 实测）；而且 fresh 结果不覆盖
   * 已有缓存（防止把聚合覆盖回腾讯），对"缓存已空"才有意义。
   *
   * 为什么是五个点：见 DM_UPGRADE_CHECKPOINTS 的注释。命中更全的就走
   * 安全点替换（暂停时任意位置 / 开头 60 秒 / 刚 seek）。
   *
   * ★ 不能因 dmUpgradeApplied 短路（2026-11-16 复查）：聚合源 7~14 秒才写完
   *   缓存，8 秒那一轮可能只拿到不完整的一份；一旦置位就永久跳过后面四点，
   *   用户会锁死在偏小的结果上。现在每轮都问，是否替换交给 loadDanmaku 的
   *   1.2 倍门槛（42218 → 42293 过不了门槛，不会重复换、不会来回跳）。
   */
  for (const at of DM_UPGRADE_CHECKPOINTS) {
    dmUpgradeTimers.push(
      window.setTimeout(() => {
        if (props.danmakuUrl !== url || dmOverrideUrl.value) return
        void loadDanmaku(url, { serverOnly: true })
      }, at),
    )
  }
}

/** 应用后台补全：整份替换 + 更新本地缓存 + 轻提示。 */
function applyDmUpgrade(up: {
  url: string
  xml: string
  count: number
  total: number
  source: string
}) {
  const list = parseDanmakuXml(up.xml)
  if (!list.length) return
  dmSource.value = up.source
  dmLocalCount.value = 0
  dmUpgradeApplied.value = true
  dmPendingUpgrade.value = null
  /*
   * 清掉「空弹幕 30 秒重试」。升级成功后它已经没用了；不清的话 30 秒后
   * 还会再走一遍普通路径，白闪一下（而且 dmEmpty 也会被它重新写回）。
   * clearDmRetry 同时把 dmEmpty 置 false —— 从 0 条补上 4 万条时，
   * 空态文案必须一起消失，否则「有弹幕却显示来源不可用」。
   */
  clearDmRetry()
  // 升级后的这份也可能触顶：带上 total，否则 "N+" 会悄悄退化成 "N"
  // ★ 不 load + sync：那会清屏并把过去 3 秒重放一遍；只换 queue，保留在场弹幕
  const v = video.value
  applyDmList(list, up.total, { keepActive: true, at: v?.currentTime ?? 0 })
  void putLocalDanmaku(up.url, up.xml, list.length, up.source)
  showDmHint(`已切换到更全的弹幕源（${filterByDuration(list).length} 条可用）`)
}

/** 用户主动 seek：记时间；有挂起的补全就趁这个安全点应用。 */
function onDanmakuSeeked() {
  dmLastSeekAt = Date.now()
  const pending = dmPendingUpgrade.value
  if (pending && !dmOverrideUrl.value) applyDmUpgrade(pending)
}

/** 清掉待执行的空弹幕重试（换集/换源时必须清，否则会给旧集发请求） */
function clearDmRetry() {
  if (dmRetryTimer) {
    window.clearTimeout(dmRetryTimer)
    dmRetryTimer = undefined
  }
  dmRetried = false
  dmEmpty.value = false
}

// ---------------------------------------------------------------- lifecycle

/**
 * 高丢帧检测（2026-11-24）。
 *
 * 只检测、不处理：连续采样里丢帧率 >15% 且已播过 12 秒，就通知父组件。
 * 判据来自实测：某 4K 源 60 秒窗口丢 431/1500 = 28.7%，而同一 App 的
 * 1080p 线路丢 0；不是解码能力/网络/弹幕问题，是源的分片时间轴有缝。
 */
function checkVideoDrops() {
  const v = video.value
  if (!v || v.paused || dropReported) return
  // 起播阶段（解码器初始化/首片填充）天然丢帧，15 秒内不判
  if (v.currentTime < 15) return
  const q = typeof v.getVideoPlaybackQuality === 'function'
    ? v.getVideoPlaybackQuality()
    : null
  if (!q) return
  /*
   * 第一次采样只建基线：totalVideoFrames 是**累计值**，直接算会把 0~15 秒
   * 起播期的丢帧也算进来（低端设备上很容易被误判成整片丢帧）。
   */
  if (dropBaselineTotal < 0) {
    dropBaselineTotal = q.totalVideoFrames
    dropBaselineDropped = q.droppedVideoFrames
    return
  }
  const total = q.totalVideoFrames - dropBaselineTotal
  const dropped = q.droppedVideoFrames - dropBaselineDropped
  dropBaselineTotal = q.totalVideoFrames
  dropBaselineDropped = q.droppedVideoFrames
  // 窗口太短（标签页被节流/刚恢复）不判
  if (total < 40) return
  const winRatio = dropped / total
  const cumRatio = q.totalVideoFrames > 0
    ? q.droppedVideoFrames / q.totalVideoFrames
    : 0
  let ahead = 0
  try {
    const b = v.buffered
    if (b.length > 0) ahead = Math.max(0, b.end(b.length - 1) - v.currentTime)
  } catch {
    /* buffered 读不到就不做缓冲门禁 */
  }
  // 调试可见（pc_eval 直接读；不影响播放）
  ;(window as unknown as { __dropGuard?: unknown }).__dropGuard = {
    t: +v.currentTime.toFixed(1),
    dropped: q.droppedVideoFrames,
    total: q.totalVideoFrames,
    ratio: +cumRatio.toFixed(3),
    winRatio: +winRatio.toFixed(3),
    ahead: +ahead.toFixed(1),
    streak: dropBadStreak,
    reported: dropReported,
  }
  /*
   * 缓冲垫不足先不判：这更像网络/分片问题（`bufferStalledError` 那条链路负责），
   * 4K 转码解决不了，还会在低端后端白烧一次编码。
   */
  if (ahead < 4) {
    dropBadStreak = 0
    return
  }
  if (winRatio > 0.15) dropBadStreak++
  else dropBadStreak = 0
  if (dropBadStreak >= 2) {
    dropReported = true
    emit('high-drop', {
      dropped: q.droppedVideoFrames,
      total: q.totalVideoFrames,
      ratio: cumRatio,
      levelUrl: activeLevelUrl,
      height: v.videoHeight,
    })
  }
}

onMounted(() => {
  // 换集后恢复上一次的全屏状态（伪全屏，不需要用户手势）
  void restoreFsIntent()
  /*
   * 端内原生渲染（2026-11-27）：把原生的 200ms 快照灌进本组件自己的状态 ref。
   * 所有控件读的都是 currentTime/duration/playing —— 控制条、浮层、进度条、
   * 弹幕密度条全部照常工作，不需要另写一套 UI。
   *
   * 放在 restoreFsIntent 之后：原有初始化顺序（全屏意图、丢帧守卫、TV 定时器）
   * 必须保持不变，改顺序会踩到那几处对时序敏感的回归。
   */
  if (iosNative) {
    iosNativePoll = window.setInterval(() => {
      const st = readIosNativeState()
      if (!st) return
      currentTime.value = st.t
      duration.value = st.d
      playing.value = st.p
      if (rate.value !== 1) iosNativeCmd('rate', rate.value)
    }, 200)
    /*
     * 进控制层就把当前弹幕设置与静音状态同步给原生（2026-11-27）。
     * 原生只拿得到拉起那一刻的 metaJson；此后一切以这里推送为准。
     */
    pushDmOptionsToNative()
    if (props.danmakuUrl) iosNativeCmd('dmUrl', props.danmakuUrl)
    // 播放器偏好里弹幕是关的：原生 overlay 默认开着，必须显式同步一次
    if (!dmOn.value) iosNativeCmd('dm', 0)
    if (muted.value) iosNativeCmd('mute', 1)
    /*
     * 控制栏隐藏期间原生会把整个 WebView 收起来（bar=0）；点屏唤回时由
     * NativeVlcPlayer 调这个钩子。没有它的话，WebView 虽然被原生放出来了，
     * 但页面自己的 showCtrl 还是 false —— 屏幕上一片空白，用户以为卡死。
     */
    ;(window as unknown as { __meowVlcShowBar?: () => void }).__meowVlcShowBar = () => {
      wake()
    }
    /*
     * 把「视频所在容器链」的背景清成透明（2026-11-27 真机实报「只有声音、
     * 没画面也没弹幕」）。
     *
     * 为什么按祖先链清而不是写死选择器：页面骨架有几层、每层用什么类名，
     * 随路由和主题变；但在 native 模式下 `<video>` 元素一定存在（只是不加载
     * src），从它往上走到 body 就是完整的那条链。用内联 style + important，
     * 优先级高于任何 Tailwind/CSS 类，且**不会碰到控制条**（控制条是兄弟节点，
     * 不在链条上）。
     */
    void nextTick(() => {
      let el: HTMLElement | null = video.value ?? null
      let guard = 0
      while (el && guard < 16) {
        el.style.setProperty('background', 'transparent', 'important')
        el.style.setProperty('background-color', 'transparent', 'important')
        el.style.setProperty('background-image', 'none', 'important')
        if (el === document.body) break
        el = el.parentElement
        guard += 1
      }
      document.documentElement.style.setProperty('background', 'transparent', 'important')
      document.body.style.setProperty('background', 'transparent', 'important')
    })
  }
  // 3 秒一窗：15 秒后连续两窗命中 ≈ 18~21 秒触发，比 5 秒窗更早但不牺牲稳定性
  dropGuardTimer = window.setInterval(checkVideoDrops, 3000)
  // TV：从原生层恢复定时器显示（定时器在原生层，跨页面仍在跑）。
  // 关键：卸载时全局回推函数常驻，所以这里把处理器重新注册回来。
  if (isTvShell()) {
    installPowerBridge(onPowerFire, onPowerResult)
    void restorePowerTimerDisplay()
  }
  const v = video.value
  const s = stage.value
  if (s) {
    const p = prefs.value
    dm.value = new DanmakuRenderer(s, {
      opacity: p.danmakuOpacity,
      scale: p.danmakuScale,
      duration: p.danmakuDuration,
      area: p.danmakuArea,
    })
    // 端内原生渲染时弹幕由原生 VlcDanmakuOverlay 画：web 引擎必须关掉，
    // 否则两层重复、而且透明 WebView 上的 canvas 会明显掉帧（2026-11-27 实测）
    dm.value.setEnabled(iosNative ? false : dmOn.value)
    dm.value.setRate(v?.playbackRate || 1)
    dm.value.setOffset(dmOffset.value)
    // 暴露给桌面端诊断（src-tauri/src/mcp.rs 的 pc_danmaku 读取）。
    // 弹幕画在 canvas 上，DOM 里读不到任何文本；不暴露这个引用，
    // 自动化就只能靠截图肉眼看，无法断言"第 30 秒应该出现哪几条"。
    ;(window as any).__meowDm = dm.value
    // 尺寸跟踪由 DanmakuRenderer 内部自己挂 ResizeObserver（它盯的是
    // 自己的容器，比这里更准），所以这里不再重复观察。
  }
  if (props.src) setupSource(props.src)
  if (props.danmakuUrl) loadDanmaku(props.danmakuUrl)
  if (v) {
    // 先把上次的音量/静音/倍速应用上（元数据到之前就能设）
    v.volume = volume.value
    v.muted = muted.value
    if (Math.abs(v.playbackRate - rate.value) > 0.01) v.playbackRate = rate.value
  }
  v?.addEventListener('play', () => {
    bgLog(
      `EV play ct=${v.currentTime.toFixed(1)} vis=${document.visibilityState} ` +
      `userPausedAgo=${userPausedAt ? Date.now() - userPausedAt : -1} bgWant=${bgKeepPlaying}`,
    )
    /*
     * 用户刚按下暂停的 1.2s 内，任何来源的"又播起来"都立刻压回去：
     * 系统后台恢复、续播重试、内核异步回放都会派 play。首次切回后台的媒体
     * 状态最乱（2026-11-25 真机：首次切回双击暂停无效，后续正常），
     * 这一道兜在事件层，不依赖具体是哪条路径拉起的。
     */
    if (userPausedAt && Date.now() - userPausedAt < 2500) {
      bgLog('EV play suppressed (user just paused)')
      try {
        v.pause()
      } catch {
        /* 元素已销毁就算了 */
      }
      return
    }
    playing.value = true
    userPausedAt = 0
    // 原生侧记"现在在播"：切后台时只有这个状态为真才激活 AVAudioSession
    notifyIosPlaying(true)
    dm.value?.start()
    dm.value?.sync(v.currentTime)
    wake()
    // WebAudio 探测通道建立后元素音频走这条图：后台节流可能把 ctx 打回
    // suspended（表现就是整页哑），每次起播兜底拉一次
    void noAudioCtx?.resume()
  })
  v?.addEventListener('pause', () => {
    bgLog(
      `EV pause ct=${v.currentTime.toFixed(1)} vis=${document.visibilityState} ` +
      `userPausedAgo=${userPausedAt ? Date.now() - userPausedAt : -1} bgWant=${bgKeepPlaying}`,
    )
    // 暂停这一刻的位置最可信：记下来，切后台即使元素被重置也还能回到这里
    if (v.currentTime > 0) lastGoodPos = v.currentTime
    playing.value = false
    dm.value?.pause()
    /*
     * 暂停是「无感替换」的最佳时机（2026-11-16 复查）：播放中途拿到的更全
     * 结果会挂起等 seek，但用户一暂停画面就静止了，此时整份换上不会造成
     * "弹幕突然变密/位置跳动"。上方注释一直写"暂停或拖动后自动生效"，
     * 实际只实现了拖动 —— 这里补上暂停这条。
     */
    const dmPend = dmPendingUpgrade.value
    if (dmPend && !dmOverrideUrl.value) applyDmUpgrade(dmPend)
    // ★ 用户主动暂停 → 撤销"seek 后续播"的意图。seeked 事件在 hls 拉
    //   分片时可能迟到几百毫秒，期间用户按了暂停的话，resumeAfterSeek
    //   还挂着就会把视频强拉回播放（2026-11-16 横滑手势实测踩过：
    //   拖完单击暂停，0.5s 后自己又响了）。
    resumeAfterSeek = false
    // ★ 同理清掉"起播后 800ms 补播"的欠账。注释一直说 pause 会清，
    //   实际代码没有清——起播 800ms 内用户暂停时，定时器到点会把它
    //   拉回播放（偶发，取决于 hls 时序）。
    autoPlayPending = false
    /*
     * iOS 切后台的一瞬，WebKit 会先派一次 pause（不是用户点的）。
     * 只要"继续播"的意图还在，就把媒体拉回来（见 maybeKeepPlayingInBackground）；
     * 前台暂停才告诉原生"现在没在播"，避免回后台时白激活一次音频会话。
     */
    if (document.visibilityState !== 'hidden') notifyIosPlaying(false)
    else handleBackgroundPause()
    // 暂停时保持控制栏可见（用户暂停多半是要选集/换线）。
    // 收起的时机交给"最后一次按键后 5 秒"的定时器 —— 见 wake()。
    // ★ 这里不能只 clearTimeout 不重排：原来就是清掉计时器后什么也不做，
    //   于是暂停一次之后控制栏再也不自动隐藏了（实测）。
    wake()
    // 暂停是关键节点：进度立刻落盘并允许触发同步
    flushProgress(true)
  })
  // 倍速 / 拖动后按当前时间重算弹幕位置，保证与画面同步
  v?.addEventListener('ratechange', () => {
    // 按住方向键扫描 / 长按快进期间跳过纠正：那 2x 是我们自己临时设的，
    // 被 wantedRate 纠回去就等于手势失效
    if (keyScan.value !== 0) {
      dm.value?.setRate(v.playbackRate)
      dm.value?.sync(v.currentTime)
      return
    }
    /*
     * 欠载临时降速期间不要纠正回 wantedRate：
     * 那是我们自己为了让缓冲追上而改的，纠回去等于把兜底关掉。
     */
    if (tempRateDropped) {
      rate.value = v.playbackRate
      dm.value?.setRate(v.playbackRate)
      dm.value?.sync(v.currentTime)
      return
    }
    // hls.js 换码率时会把 playbackRate 打回 1，这里纠回用户选的值
    if (wantedRate !== 1 && Math.abs(v.playbackRate - wantedRate) > 0.01) {
      v.playbackRate = wantedRate
      return
    }
    rate.value = v.playbackRate
    // 倍速变了 → 需要的码率也变了，重新评估档位上限
    applyAbrForRate(v.playbackRate)
    // 弹幕的 rAF 插值要按同样的倍速推进，否则会和画面脱节
    dm.value?.setRate(v.playbackRate)
    dm.value?.sync(v.currentTime)
  })
  v?.addEventListener('seeked', () => {
    // 主动 seek 是允许应用后台补全的安全点（用户预期画面会跳）
    onDanmakuSeeked()
    // 用户拖到/跳到哪，哪就是"最后可信位置"（暂停时拖回去不会被旧值带回）
    if (v.currentTime > 0) lastGoodPos = v.currentTime
    // 显式 seek 标志：不靠时间差猜，拖动多少都该清屏重建
    dm.value?.sync(v.currentTime, { seek: true })
    buffering.value = false
    // hls.js 重新加载分片期间，部分内核会把播放状态打断成暂停。
    // 只有在"seek 前是播放中"且"seek 后确实停了"时才续播，
    // 避免把用户主动的暂停也一起恢复掉。
    if (resumeAfterSeek && v.paused) {
      resumeAfterSeek = false
      safePlay(v)
    }
    resumeAfterSeek = false
  })
  v?.addEventListener('seeking', () => {
    dm.value?.sync(v.currentTime)
    buffering.value = true
    // 用播放态标志而不是 v.paused：内核可能在派发 seeking 之前
    // 就已经把 paused 置成 true，那时读 v.paused 会得到假值，
    // 守卫就永远不触发（实测踩过：按键后画面停住不恢复）。
    resumeAfterSeek = playing.value
  })
  /*
   * 缓冲期间必须**冻结弹幕引擎**。
   *
   * 不冻结的话：画面时间冻结，rAF 插值仍按墙钟往前推（最多 0.4s），
   * 恢复时时间"倒退"回去，就被 sync 的跳转判据当成用户拖动 → 清屏重发。
   * 而且画面都卡住了弹幕还在飞，本身就不对。
   *
   * ★ 只有 `waiting` 才算"画面真的停了"。`stalled` 是**网络层**事件：
   *   它只说明这一刻没收到网络数据，画面往往还在用已缓冲的垫子正常播。
   *   2026-11-04 实测踩过：把 `stalled` 也接进来后，引擎被冻住却没有任何
   *   事件把它叫醒（画面没停 → 不会有 playing/canplay），tick 停止推进，
   *   只剩 sync 的 4Hz 重画 —— 弹幕变成每 250ms 挪一下的"一顿一顿"。
   *   同一个坑还有第二层：`stalled` 顺带把转圈浮层点亮后再也关不掉。
   */
  const onBufferStart = () => {
    buffering.value = true
    freezeDanmakuForBuffer()
    const vv = video.value
    // 欠载兜底要看"连续两次 + 垫子见底"，判据在 maybeDropRateForUnderrun
    if (vv) maybeDropRateForUnderrun(vv)
  }
  const onBufferEnd = () => {
    buffering.value = false
    thawDanmakuFromBuffer()
  }
  v?.addEventListener('waiting', onBufferStart)
  v?.addEventListener('playing', () => {
    onBufferEnd()
    // 真正出画面了，起播看门狗可以收工
    clearStartupWatchdog()
  })
  v?.addEventListener('canplay', onBufferEnd)
  /*
   * 回前台恢复窗口的触发点：timeupdate 在 onTimeUpdate 里调；这里补两个
   * "元数据刚回来"的时机 —— 切回前台时 WebView 可能正在重建 source，
   * 此时写 currentTime 会被丢掉，等这两个事件才是可靠的 seek 点。
   */
  v?.addEventListener('loadedmetadata', onRestoreTick)
  v?.addEventListener('canplay', onRestoreTick)
  // 原生错误（非 hls 路径）：直链 404 / 编码不支持 / CORS 被拦
  v?.addEventListener('error', () => {
    // 换源瞬间 src 清空也会触发，忽略没有 src 的情况
    if (!v.currentSrc && !v.src) return
    if (errorMsg.value) return
    const code = v.error?.code
    try {
      if (localStorage.getItem('meow.debug') === '1') {
        const w = window as unknown as { __dbg?: string[] }
        ;(w.__dbg || (w.__dbg = [])).push(
          'mediaError code=' + code + ' net=' + v.networkState + ' ready=' + v.readyState +
          ' co=' + String(v.crossOrigin) + ' src=' + v.currentSrc.slice(0, 70) + ' err=' + String(v.error && v.error.message).slice(0, 60),
        )
      }
    } catch {
      /* 忽略 */
    }
    /*
     * ★ 没有 MediaError 的 error 事件是伪错误（2026-11-23 实测踩过）：
     *   给 <video> 改 crossOrigin / 从 MediaSource 回退直连时会补发一个
     *   error 事件，此时 v.error 仍为 null。原来当成失败 → 片库直连一加载
     *   就"播放失败：媒体错误 未知" → 自动转码（用户看到"一进就报错/自己转码"）。
     *   真正的失败（网络/解码/格式）都带 MediaError，交给下面按 code 分类。
     */
    if (code === undefined || code === null) return
    const map: Record<number, string> = {
      1: '加载被中断',
      2: '网络错误',
      3: '解码失败',
      4: '格式不支持或地址已失效',
    }
    // MediaError: 1 abort / 2 network / 3 decode / 4 src-not-supported
    const kind: PlayErrorKind = code === 2 ? 'network' : code === 3 ? 'media' : code === 4 ? 'src' : 'unknown'
    failPlay(map[code ?? 0] || `媒体错误 ${code ?? '未知'}`, kind)
  })
  window.addEventListener('keydown', onKey)
  // TV 壳把遥控器方向键全拦下来交给 tv-nav 做焦点移动，播放器就再也收不到
  // ArrowLeft/Right（实测：电视上按左右只移动焦点、视频纹丝不动）。
  // 播放页声明独占方向键，tv-nav 会把按键改派成 DOM keydown 送回来。
  ;(window as unknown as { __meowTvKeyOwner?: boolean }).__meowTvKeyOwner = true
  // TV 壳的 BACK 键会先问这里。**按嵌套深度从内往外退**：
  // 全屏 → 面板 → 都关掉才把返回交还给壳（退出播放页）。
  // 不注册的话壳直接 goBack()，面板开着按返回会退掉整集（实测踩过）。
  /*
   * ★ 包裹而不是覆盖（2026-11-04 与 App.vue 的包裹改造配套）。
   *
   * 原来这里直接 `__meowTvConsumeBack = () => {...}`，把上一任（App.vue 的
   * 「关模态 / 问 pageBack / 两次退出」）**顶掉**了 —— 播放页上那两个分支
   * 永远不可达，每加一个页面级浮层都得自己再抢一次。
   * 现在：先把上一任存起来，自己的链条处理不了时才交回去。
   * 顺序 = 播放器内部层（弹幕面板/全屏/控制栏…）→ App 的兜底。
   */
  prevConsumeBack = (window as any).__meowTvConsumeBack as (() => boolean) | null
  ownConsumeBack = () => {
    /*
     * 错误页：返回键一次就离开本集（与页面上那个「返回」按钮一致）。
     * 原来这里要先收控制栏、再走"再按一次退出"，错误页上用户根本没在播，
     * 那两段确认纯属多余（2026-11-04 审计）。
     * return false = 不接管，交给壳 router.back() 回详情页。
     */
    if (errorMsg.value) return false
    /*
     * ★ 弹幕搜索弹框是**最顶层**（z-50，盖在全屏之上），必须最先退
     *   （2026-11-02 用户明确要求：「弹框打开时按返回应关闭弹框，
     *   此时的返回不要击穿到播放器、控制暂停」）。
     *
     * 原来全屏判断在它前面：全屏状态下按返回先退全屏、弹框还开着；
     * 再按一次才轮到弹框 —— 用户视角是"按了返回，弹框没关，反而全屏没了"。
     * 分层顺序必须与 z-index 一致：谁盖在最上面，谁先退。
     *
     * 这条分支**只关弹框就返回 true**，绝不往下走：
     * 下面的分支里有"暂停 + 再按一次退出"，一旦穿透，
     * 用户想关弹框却把视频暂停了。
     */
    if (showDmSearch.value) {
      closeDmSearch()
      return true
    }
    /*
     * 播放信息面板（2026-11-04 用户要求：返回键要能关它）。
     *
     * 它没有走「更多」那套 anyPanel（`openPanel` 管的是 ep/line/dm/more/help），
     * 而是自己的 `debugOn`，所以原来返回键完全够不到它 —— 按下去直接把控制栏
     * 收了、再按一次退出整集，面板还挂在屏幕上。
     * 层级：z-[35]，低于弹幕搜索弹框（z-50）、高于伪全屏里的控制栏。
     */
    if (debugOn.value) {
      toggleDebug()
      return true
    }
    if (pseudoFs.value || document.fullscreenElement) {
      void exitPseudoFs()
      return true
    }
    // ★ 返回键的分层语义（从最"临时"的一层往外退）：
    //   弹框 → 全屏 → 面板 → 控制栏 → 播放（暂停/退出）
    //
    //   用户的原话：「播放栏、弹框显示时按返回，应该隐藏控制栏、弹框」。
    //   原来只关了弹框，控制栏还留在屏幕上，于是要连按三次返回才轮得到
    //   "暂停+提示"，而中途很容易误退出整集。
    //
    //   ★ 弹框与全屏的分支已提到函数最开头（与 z-index 对齐），
    //     这里不再重复判断，否则顺序又会被人改回去。
    const anyPanel =
      showEpPanel.value ||
      showLinePanel.value ||
      showDmPanel.value ||
      showMore.value ||
      // ★ 原来漏了帮助面板：外接键盘按 ? 打开后返回键关不掉（2026-11-03 核实）
      showHelp.value
    if (anyPanel) {
      openPanel(null)
      // 弹框关了但控制栏还在：一并收起，否则下一次返回又要多按一次。
      showCtrl.value = false
      if (isTvEnv()) clearTvFocus()
      return true
    }
    if (showCtrl.value) {
      showCtrl.value = false
      if (isTvEnv()) clearTvFocus()
      return true
    }
    // ★ 片头/片尾跳过倒计时进行中：返回键 = 立即跳过。
    //   这段时间屏幕上正显示"跳过片头 3s…"的圆环，用户最容易做的动作就是
    //   按返回（不想等）。此时若按原来"暂停+提示"的逻辑处理，他会连续按两次
    //   直接退出整集——比等 5 秒更糟。所以只要倒计时在跑，返回优先当"跳过"。
    if (introCd.value > 0) {
      doSkipIntro()
      return true
    }
    if (outroCd.value > 0) {
      doSkipOutro()
      return true
    }
    // ★ TV 返回键的两段语义（2026-11-03 用户调整）：
    //   第一次 → 只弹"再按一次返回退出播放"提示，**不暂停、不唤出控制栏**；
    //   提示还在屏上的 2.5 秒内再按 → 交给壳退出播放页。
    //
    //   原来的"第一次 = 暂停 + 提示"去掉了：暂停这个动作和"想退出"无关，
    //   用户看到的是"按了返回画面却定格"，还得再按一次播放键才能继续看。
    //   判据用"提示是否还在屏上"而不是计数：提示自己 2.5 秒后消失，
    //   过了时间窗再按返回就重新提示，永远不会因为按慢了而误退出。
    if (isTvEnv()) {
      const el = video.value
      if (!el) return false
      if (backHint.value) {
        /*
         * 第二次返回：壳马上就要 goBack() 退出整集。
         *
         * ★ 必须在放手之前把媒体彻底停掉（2026-11-02 音轨重叠的第三条路径）。
         *   "返回后重新播放"时旧页面会被销毁、新的播放页重新挂载一个 <video>。
         *   只 pause() 的话，Chromium 的媒体元素在"页面即将卸载"时可能
         *   来不及完成暂停流程（规范要求等稳定状态），旧音轨就留在后台，
         *   和新页面叠在一起响。stopMedia 会静音 + 摘源 + load()，一步到位。
         */
        stopMedia()
        return false
      }
      backHint.value = '再按一次返回退出播放'
      clearTimeout(backHintTimer)
      backHintTimer = window.setTimeout(() => { backHint.value = '' }, 2500)
      return true
    }
    return false
  }
  {
    const wrappedConsumeBack = () =>
      (ownConsumeBack ? ownConsumeBack() : false) ||
      (prevConsumeBack ? prevConsumeBack() : false)
    // 打标记：卸载时据此判断"当前挂的还是不是我这一层"，再决定要不要还原
    ;(wrappedConsumeBack as any).__meowOwn = ownConsumeBack
    ;(window as any).__meowTvConsumeBack = wrappedConsumeBack
  }
  // 按住方向键的"松手"信号。keyup 只绑 keydown 不够——
  // 切窗口/失焦时 keyup 可能永远不来，所以 blur 也兜一刀。
  window.addEventListener('keyup', onKeyUp)
  // tv-nav 移焦点后广播：左右选按钮时控制栏不能提前收（见 onTvFocusMoved）
  window.addEventListener('meow:tv-focus', onTvFocusMoved)
  window.addEventListener('blur', stopKeyScan)
  window.addEventListener('meow:prefs', onPrefs)
  /*
   * iOS：播放页关掉原生边缘返回手势（左边缘右滑会跟横滑 seek/全屏手势打架），
   * 离开时在 onUnmounted 里恢复。
   */
  setIosNavGesture(false)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  window.addEventListener('resize', () => {
    dm.value?.resize()
    checkOrientation()
    syncCompactViewport()
    syncLandscapeViewport()
  })
  syncCompactViewport()
  syncLandscapeViewport()
  // 关页面/切走前补一次，避免最后几秒丢掉
  window.addEventListener('pagehide', onPageHide)
  // 隐藏到托盘/最小化不会触发 pagehide，音频会继续播；用 visibilitychange 兜住
  document.addEventListener('visibilitychange', onVisibilityChange)
  // iOS 壳回前台的可靠信号（原生 scenePhase == .active 时 eval）
  window.addEventListener('meow:ios-active', onIosActive)
  ;(window as unknown as { __meowIosActive?: () => void }).__meowIosActive = iosActiveBridge
  // TV 壳从原生播放器（ExoPlayer）返回时的恢复广播（mediaExclusive.ts 发出）
  window.addEventListener('meow:webview-resume', onWebviewResume)
  // ★ 实测 WebView2 在窗口被 Tauri hide() 后仍报 visibilityState='visible'，
  // 单靠上面的 visibilitychange 不会触发。Rust 隐藏窗口前会 eval 这个钩子。
  ;(window as unknown as { __meowPauseMedia?: () => void }).__meowPauseMedia = pauseMediaOnHide
  // 触屏设备进播放页先尝试横屏；不支持就显示竖屏提示
  void tryLandscape().then(() => {
    checkOrientation()
    // 锁横屏成功后方向已变，再确认一次提示状态
    window.setTimeout(checkOrientation, 600)
  })
  // 返回键判据：真的动过鼠标/触摸才在 TV 壳里显示（见 showPlayerBackButton）
  window.addEventListener('pointerdown', notePointer, { capture: true, passive: true })
  window.addEventListener('pointermove', notePointer, { capture: true, passive: true })
  wake()
})

/** 偏好变化时同步到渲染器（透明度 / 字号 / 时长 / 区域） */
watch(prefs, (p) => {
  dm.value?.setOptions({
    opacity: p.danmakuOpacity,
    scale: p.danmakuScale,
    duration: p.danmakuDuration,
    area: p.danmakuArea,
  })
  dmOn.value = p.danmaku
  dm.value?.setEnabled(p.danmaku)
  // 端内原生渲染：开关也要给原生（否则设置页改了开关，原生层照旧显示）
  if (iosNative) iosNativeCmd('dm', p.danmaku ? 1 : 0)
}, { deep: true })

onBeforeUnmount(() => {
  if (dropGuardTimer) clearInterval(dropGuardTimer)
  // 卸载后 onFullscreenChange 可能已经收不到（监听已摘），先按住这个标志，
  // 避免浏览器在元素移除时触发的 fullscreenchange 再把状态写乱。
  unmounting = true
  if (iosNativePoll) {
    window.clearInterval(iosNativePoll)
    iosNativePoll = 0
  }
  // 离开播放页/错误页时把 PC 的窗口全屏退掉；真全屏由 WebView2 在元素
  // 移除时自动退出，这里只处理我们通过 desktop_set_fullscreen 设置的那层。
  // 换集不再卸载本组件（见 PlayView 的 src 注释），所以不会误退。
  void setWindowFullscreen(false)
  // 走到卸载就是真的离开播放页（或进了错误页）——换集已经不会重建了。
  // 清掉「想全屏」的意图，否则下次进播放页会莫名自动全屏；错误页本身
  // 就是整页重来，重试时保持窗口态也更符合直觉。
  rememberFsIntent(false)
  /*
   * 离开播放器时把壳的方向还原成竖屏。
   *
   * 三条路径都要还原，缺一条就会"退出播放页后整个应用还是横的"：
   *   ① 全屏自己转的横屏（rotatedByFs）
   *   ② 进播放页自动转的横屏（pageLandscape）
   * 换集时本组件也会卸载重建，但那是同一个播放页、马上会再转一次横屏，
   * 所以先还原再转一次没有副作用（间隔极短，肉眼不可见）。
   */
  if (rotatedByFs || pageLandscape) {
    rotatedByFs = false
    pageLandscape = false
    shellSetOrientation('portrait')
  }
  // 卸载前补一次（切集时 App.vue 会重建整页，这是最后一次机会）
  flushProgress(true)
  clearStartupWatchdog()
  clearNoAudioWatch()
  // 锁定提示的 1.6s 定时器跟着组件走，别在卸载后再写 ref
  clearTimeout(lockHintTimer)
  // WebAudio 探测通道随组件销毁（不关的话每进一次播放页泄漏一个 AudioContext）
  void noAudioCtx?.close()
  noAudioCtx = undefined
  noAudioTapEl = undefined
  noAudioAnalyser = undefined
  destroyHls()
  /*
   * ★ 必须显式停掉媒体元素，不能只靠"节点被移除"
   *   （2026-11-02，用户报「切下一集/换线路/返回重播后，原视频声音还在响，
   *   同时新视频已经开始播」）。
   *
   * 播放页的模板第一条是 `v-if="loading"`，换集/换线路时 `loading=true`
   * 会把 VideoPlayer 整个卸载掉。Chromium 对**带 MediaSource 的媒体元素**
   * 有个已知行为：元素从文档里摘掉后不一定立刻停播（规范要求"等待稳定状态
   * 再暂停"，Android WebView 上经常等不到）。于是旧元素继续出声音，
   * 新页面已经渲染出新的 `<video>` —— 两路音轨叠加。
   */
  stopMedia()
  dm.value?.destroy()
  dm.value = null
  // 片头片尾提示的定时器
  if (marksTipTimer) window.clearTimeout(marksTipTimer)
  // 暂停浮层的延迟显示定时器
  if (pauseOverlayTimer) window.clearTimeout(pauseOverlayTimer)
  // 跳过倒计时的定时器（漏了会在销毁后继续改 ref）
  if (introTimer) window.clearInterval(introTimer)
  if (outroTimer) window.clearInterval(outroTimer)
  // 空弹幕的 30 秒重试：不清的话切集后还会给上一集发请求
  clearDmRetry()
  revokeSub()
  // 清掉诊断引用：否则 pc_danmaku 会读到已销毁的实例，报错信息很误导
  ;(window as any).__meowDm = null
  // 调试面板的轮询定时器和全局快照也要清，否则切集后仍在跑
  if (debugTimer) clearInterval(debugTimer)
  debugTimer = undefined
  ;(window as any).__playerDebug = null
  // 电源定时器：PC/Web 的前端计时必须清；TV 的原生定时器**保留**（跨页面语义，
  // 用户在播放页设的定时不该因为切页就消失）。全局回推函数也保留，只注销处理器。
  if (sleepTimer) window.clearInterval(sleepTimer)
  sleepTimer = undefined
  uninstallPowerBridge()
  // 离开播放页必须交还方向键，否则详情/设置页上按左右会被派发成
  // 播放器的 seek 事件（页面已卸载，白按）
  ;(window as any).__meowTvKeyOwner = false
  /*
   * 只撤自己那一层：把上一任还回去（App 的兜底 handler）。
   * 直接清 null 会把 App 的「关模态 / pageBack」也一起抹掉。
   */
  {
    const w = window as any
    if (w.__meowTvConsumeBack && (w.__meowTvConsumeBack as any).__meowOwn === ownConsumeBack) {
      w.__meowTvConsumeBack = prevConsumeBack
    }
  }
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('keyup', onKeyUp)
  // 监听留在全局会在每次切集（重建页面）后累积，一次按键续期 N 次
  offTvFocusListener()
  window.removeEventListener('blur', stopKeyScan)
  stopKeyScan()
  window.removeEventListener('meow:prefs', onPrefs)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  window.removeEventListener('pagehide', onPageHide)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  window.removeEventListener('meow:ios-active', onIosActive)
  // 组件销毁后补播定时器仍会触发 → 用户已经离开播放页还被拉起来
  cancelResumeTimers()
  notifyIosPlaying(false)
  // iOS 竖滑改的是真实屏幕亮度：离开播放页还原，别把用户手机亮度留在那
  restoreSystemBrightness()
  // 恢复原生边缘返回手势（播放页里是关掉的）
  setIosNavGesture(true)
  // 摘掉 MPVolumeView：把系统音量 HUD 还给其它页面
  if (volHudTimer) clearTimeout(volHudTimer)
  setIosVolumeHud(false)
  {
    const w = window as unknown as { __meowIosActive?: () => void }
    if (w.__meowIosActive === iosActiveBridge) w.__meowIosActive = undefined
  }
  window.removeEventListener('meow:webview-resume', onWebviewResume)
  if (iosNative) {
    // 卸载时摘掉原生点屏回调：否则原生再调它就会碰到已销毁的组件状态
    delete (window as unknown as { __meowVlcShowBar?: () => void }).__meowVlcShowBar
  }
  const w = window as unknown as { __meowPauseMedia?: () => void }
  if (w.__meowPauseMedia === pauseMediaOnHide) w.__meowPauseMedia = undefined
  // 所有一次性定时器都要清：组件销毁后它们仍会触发 ref 写入
  cursorIdle.stop()
  clearTimeout(hideTimer)
  clearTimeout(seekHintTimer)
  clearTimeout(backHintTimer)
  clearTimeout(clickTimer)
  clearTimeout(longPressTimer)
  clearTimeout(suppressTimer)
  clearTimeout(fsHintTimer)
  clearTimeout(dmHintTimer)
  for (const t of dmUpgradeTimers) clearTimeout(t)
  dmUpgradeTimers = []
  window.removeEventListener('pointerdown', notePointer, { capture: true } as EventListenerOptions)
  window.removeEventListener('pointermove', notePointer, { capture: true } as EventListenerOptions)
})

function onPageHide() {
  flushProgress(true)
}

/**
 * 页面隐藏前是否正在播（hidden 时由我们自动暂停）。
 *
 * 窗口被隐藏（关闭到托盘 / 最小化 / 切到别的应用）时暂停媒体。
 * 只挂 pagehide 不够：隐藏到托盘不会触发 pagehide，Chromium 也不会自动
 * 停掉正在出声的 <video>，用户会听到"窗口没了但还在播"。
 *
 * TV 场景的恢复判据：原生播放器盖上来 → visibilityState=hidden →
 * pauseMediaOnHide 先停了视频；壳 onResume 的 __meowWebviewResume 又因为
 * "发现全都已暂停"而不恢复（mediaExclusive 的 pausedBySystem=false）——
 * 两道闸互相拆台，返回播放页就停在暂停态（2026-11-15 实测 .76）。
 * 只有这个标志能证明"暂停是我们干的"，恢复可见时据此续播。
 * PC 最小化仍保持"回来是暂停态"（用户主动离开，更可控）。
 */
let playingBeforeHide = false
/** 最后一次"确实在播"的时间戳（timeupdate 刷新，约 4Hz） */
let lastPlayedAt = 0
/**
 * 切后台那一刻的播放位置。
 *
 * <p>回前台如果发现 video 被重建/流被重置（currentTime 明显小于它），
 * 就 seek 回这里 —— 用户报「切回前台进度带入不了」的兜底。
 * 若后台一直在播（currentTime 已经更大），这里不会倒退。
 */
let posBeforeHide = 0
/**
 * 最近一次"播放中"的有效位置（timeupdate 刷新）。
 *
 * <p>★ 为什么不能只读切后台那一刻的 `currentTime`：iOS 可能在派发
 * visibilitychange 之前就把媒体管线重置了，那一刻读到的是 0 —— 于是
 * "回前台恢复"根本不知道该回哪（2026-11-25 真机：进度始终带不回来）。
 * 这里保留最后一次确认过的播放位置，切后台时取两者最大值。
 */
let lastGoodPos = 0
/** 最近一次 timeupdate 的时间与位置：判断"事件说暂停但实际还在走" */
let lastTickAt = 0
let lastTickPos = 0

/**
 * 元素是不是"看起来在播"。
 *
 * <p>不能只信 `v.paused`：iOS 从后台恢复后，元素可能报 paused=true，
 * 但 timeupdate 仍在推进（系统媒体会话自己恢复的）。此时用户双击要的是暂停，
 * 按 paused 判断会去 play()，表现就是"双击没反应"（2026-11-25 真机首切回）。
 */
function mediaLooksPlaying(v: HTMLVideoElement): boolean {
  if (!v.paused) return true
  return Date.now() - lastTickAt < 800 && v.currentTime > lastTickPos + 0.03
}

/**
 * 播放器状态流水（只记"状态迁移"，不记每帧）。
 *
 * <p>走 WKWebView 的 meowLog 通道写进 launch.log；同时留在
 * `window.__meowBgLog` 里，PC/远程调试可以直接读。切前后台这一串系统事件
 * 顺序不固定，没有流水只能靠猜（2026-11-25 用户要求加全量状态日志）。
 */
const bgLogRing: string[] = []
function bgLog(msg: string) {
  const line = `[player] ${msg}`
  bgLogRing.push(`${new Date().toISOString().slice(11, 23)} ${msg}`)
  if (bgLogRing.length > 40) bgLogRing.shift()
  ;(window as unknown as { __meowBgLog?: string[] }).__meowBgLog = bgLogRing
  try {
    const bridge = (window as unknown as {
      webkit?: { messageHandlers?: { meowLog?: { postMessage?: (s: string) => void } } }
    }).webkit?.messageHandlers?.meowLog
    if (bridge && typeof bridge.postMessage === 'function') bridge.postMessage(line.slice(0, 380))
  } catch {
    /* 日志失败不影响业务 */
  }
}

/**
 * 用户最近一次**主动**暂停的时间戳（毫秒）。
 *
 * <p>为什么需要：iOS 切后台时 WebKit 自己也会派 `pause`，和"用户点了暂停"
 * 完全同形。用时间窗区分——`toggle()` / 对外 `pause()` 里写这个值，后台补播
 * （`maybeKeepPlayingInBackground`）与回前台续播都要放过它，否则用户刚暂停
 * 就被自动拉回播放（2026-11-25 真机：回前台双击暂停被重试拉回，表现成"双击
 * 不能暂停"）。
 */
let userPausedAt = 0
/** 移动端切后台时"用户希望继续播"（后台保播的意图位，回前台清除） */
let bgKeepPlaying = false
/** 进入 hidden 的时间戳：系统暂停只发生在切后台的头几秒 */
let hiddenAt = 0
/** resumeAfterVisibility 排的补播定时器（用户暂停时一次性撤掉） */
const resumeTimers: number[] = []

function cancelResumeTimers() {
  for (const id of resumeTimers.splice(0)) window.clearTimeout(id)
}

/**
 * 后台期间的 pause：区分"系统切后台时那一停"和"用户后来用锁屏/播放条暂停"。
 *
 * <p>3s 窗口内 = 系统停的，补播；窗口外 = 用户主动停的，尊重它并把续播意图清掉
 * （否则回前台会把他刚暂停的视频又播起来）。
 */
function handleBackgroundPause() {
  if (!isMobileForm() || isTvShell()) return
  if (bgKeepPlaying && hiddenAt && Date.now() - hiddenAt < 3000) {
    bgLog('bg-pause → replay (system pause at hide)')
    maybeKeepPlayingInBackground()
    return
  }
  bgLog(`bg-pause → respect user pause (hiddenAgo=${hiddenAt ? Date.now() - hiddenAt : -1})`)
  bgKeepPlaying = false
  playingBeforeHide = false
  notifyIosPlaying(false)
}

/** 告诉 iOS 壳"页面现在是否在播"（只有 true 时切后台才激活音频会话）。 */
function notifyIosPlaying(on: boolean) {
  const ios = (window as unknown as { meowIos?: { setPlaying?: (v: boolean) => void } }).meowIos
  if (!ios || typeof ios.setPlaying !== 'function') return
  bgLog(`native setPlaying(${on})`)
  try {
    ios.setPlaying(on)
  } catch {
    /* 桥不存在/壳版本旧：忽略 */
  }
}

/**
 * iOS 切后台瞬间系统会派 `pause`（此时 visibilityState 已经是 hidden）。
 *
 * <p>只要有"继续播"的意图（切后台那一刻确实在播、且不是用户点的暂停），
 * 就立刻把媒体拉回来。WKWebView 不会替我们这么做：声明了
 * `UIBackgroundModes=audio` 只是允许后台出声，媒体被暂停后需要宿主动作；
 * 原生侧在 scenePhase=.background 时激活 AVAudioSession（AudioSession.swift），
 * 这里负责"重新 play"。两次（0/600ms）是因为会话激活与内核挂起恢复有先后。
 */
function maybeKeepPlayingInBackground() {
  if (!isMobileForm() || isTvShell()) return
  if (!bgKeepPlaying) return
  // 只处理切后台头 3s 的系统暂停；之后用户用锁屏/播放条暂停的不要拉回
  if (!hiddenAt || Date.now() - hiddenAt > 3000) return
  if (userPausedAt && Date.now() - userPausedAt < 2500) return
  const replay = (delay: number) => {
    const id = window.setTimeout(() => {
      const el = video.value
      if (!el || el.ended || !bgKeepPlaying) return
      if (document.visibilityState !== 'hidden') return
      if (!el.paused) return
      /*
       * 后台被系统重启流时 currentTime 会回到 0：先把位置补回去再播，
       * 否则会在后台从头播放（用户回来发现进度丢了）。
       */
      if (posBeforeHide > 5 && el.currentTime < posBeforeHide - 5 && el.readyState >= 1) {
        try {
          el.currentTime = posBeforeHide
          bgLog(`bg replay seek ${el.currentTime.toFixed(1)}→${posBeforeHide.toFixed(1)}`)
        } catch {
          /* seek 不了就照常播 */
        }
      }
      bgLog(`bg replay(${delay}) ct=${el.currentTime.toFixed(1)} rd=${el.readyState}`)
      safePlay(el)
    }, delay)
    resumeTimers.push(id)
  }
  replay(0)
  replay(600)
}

/**
 * 回前台把进度拉回切后台时的位置。
 *
 * <p>★ 为什么不是"检查一次就完事"（2026-11-25 真机第三轮：进度确实没带回来）：
 * iOS 回前台时媒体内核是**异步恢复**的，currentTime 可能先保持旧值、过一两秒
 * 才被重置成 0/片段起点。只检查一次会正好落在"还没重置"的时刻，判定通过就
 * 再也不管了。这里开一个 15s 的恢复窗口，由 timeupdate / canplay 持续校正。
 *
 * <p>判据只用"明显更小"（>5s）：后台一直在播时 currentTime 只会更大，不会倒退。
 * 不要求 playingBeforeHide —— 用户在后台用系统播放条暂停过，进度同样不该丢。
 */
let restorePos = 0
let restoreUntil = 0

function clearRestoreWindow() {
  if (restorePos) bgLog(`restore window closed pos=${restorePos.toFixed(1)}`)
  restorePos = 0
  restoreUntil = 0
}

/** 回前台（或原生 active 信号）时开恢复窗口 */
function beginRestoreWindow(reason: string) {
  if (!isMobileForm() || isTvShell()) return
  if (posBeforeHide <= 5) return
  const el = video.value
  /*
   * ★ 目标取各处见过的最大位置（2026-11-25 日志实锤）：
   *   hidden 时 122.6 → 回前台 visible 时已播到 129.1 → ios-active 时内核
   *   把 currentTime 回退到 122.6。只按 posBeforeHide 设目标，正好等于回退后
   *   的位置，"恢复"等于没恢复。这里把可见那一刻的 currentTime / lastGoodPos
   *   也算进去；后续再 open 只会抬高，不会降低。
   */
  restorePos = Math.max(restorePos, posBeforeHide, lastGoodPos, el?.currentTime || 0)
  restoreUntil = Date.now() + 15_000
  bgLog(`restore window open(${reason}) pos=${restorePos.toFixed(1)} ct=${(el?.currentTime || 0).toFixed(1)}`)
  if (el) applyRestoreSeek(el)
}

/**
 * 窗口内每次触发都很便宜。
 *
 * <p>★ 位置已经"正常"时**不能立刻收窗**（2026-11-25 真机两轮都没修好的根因）：
 * 回前台那一刻 currentTime 常常还是旧值，内核一两秒后才重置成 0。
 * 这里提前 return 把窗口关掉，后面的重置就没人管了。窗口只按时间过期，
 * 或者用户自己动手（clearRestoreWindow）/播放结束才关。
 */
function applyRestoreSeek(el: HTMLVideoElement) {
  if (!restorePos) return
  if (Date.now() > restoreUntil || el.ended) {
    clearRestoreWindow()
    return
  }
  /*
   * 容差从 5s 收到 2s（2026-11-25 真机「偶发进度没带回」）：
   * 内核回退量可能只有 3～4 秒，5s 容差会把它当成"正常"放过。
   */
  if (el.currentTime >= restorePos - 2) return
  /*
   * 元数据还没到（切回前台瞬间 WebView 可能正在重建 source）：
   * 此时写 currentTime 会被内核直接丢掉，等 loadedmetadata/canplay 再来。
   */
  if (el.readyState < 1) {
    bgLog(`restore waiting metadata rd=${el.readyState} ct=${el.currentTime.toFixed(1)} want=${restorePos.toFixed(1)}`)
    return
  }
  bgLog(`restore seek ct=${el.currentTime.toFixed(1)} → ${restorePos.toFixed(1)} rd=${el.readyState}`)
  try {
    el.currentTime = restorePos
  } catch {
    /* 流不允许 seek 就算了，至少别停在 0 */
  }
}

/** timeupdate / loadedmetadata / canplay 的统一入口 */
function onRestoreTick() {
  const el = video.value
  if (el) applyRestoreSeek(el)
}

function pauseMediaOnHide() {
  const v = video.value
  playingBeforeHide = !!(v && !v.paused && !v.ended)
  posBeforeHide = Math.max(v?.currentTime || 0, lastGoodPos)
  bgLog(`hide(pause) paused=${v?.paused} ct=${(v?.currentTime || 0).toFixed(1)} pos=${posBeforeHide.toFixed(1)}`)
  flushProgress(true)
  pause()
}

function onVisibilityChange() {
  if (document.visibilityState !== 'hidden') {
    const vv = video.value
    bgLog(
      `visible paused=${vv?.paused} ct=${(vv?.currentTime || 0).toFixed(1)} rd=${vv?.readyState} ` +
      `posBeforeHide=${posBeforeHide.toFixed(1)} playingBeforeHide=${playingBeforeHide} ` +
      `userPausedAgo=${userPausedAt ? Date.now() - userPausedAt : -1}`,
    )
    bgKeepPlaying = false
    resumedAt = Date.now()
    resumeAfterVisibility()
    /*
     * 回前台补一次全屏恢复（2026-11-25 用户报「切后台回来丢全屏」）：
     * ① 全屏意图还在就重新铺满（WKWebView 被系统回收/重建时 pseudoFs 会复位）；
     * ② iOS 壳的方向会被系统复位成竖屏，这里补回横屏。
     * ★ 只给移动端：PC 上 Alt+Tab 回来不该突然被塞进伪全屏。
     */
    if (isMobileForm()) {
      void restoreFsIntent()
      reapplyMobileOrientation()
    }
    return
  }
  /*
   * 移动端（iOS / 手机 WebView）：后台继续播（2026-11-25 用户要求
   * 「切后台还能播」）。Info.plist 已有 UIBackgroundModes=audio，壳里再配
   * AVAudioSession(.playback)；这里主动 pause 就白配了。
   *
   * ★ v.paused 不能单独当判据：iOS 有时**先暂停媒体再发** visibilitychange，
   *   那一刻读到 paused=true 就会记成"不是我们停的"，回来不续播。用
   *   lastPlayedAt（timeupdate 刷新）兜住 3 秒内的这种情况。
   *
   * ★ TV 壳不能跳过：hidden 也可能是原生 ExoPlayer 盖上来了，不暂停会
   *   双重出声（mediaExclusive 依赖「是不是我们停的」这个标志）。
   */
  if (isMobileForm() && !isTvShell()) {
    const v = video.value
    playingBeforeHide = !!(
      v &&
      !v.ended &&
      (!v.paused || Date.now() - lastPlayedAt < 3000)
    )
    /*
     * ★ 取两者最大值：这时 currentTime 可能已经被内核重置成 0（iOS 先重置
     * 再发 visibilitychange），只看元素会丢掉真实进度。
     */
    posBeforeHide = Math.max(v?.currentTime || 0, lastGoodPos)
    // 后台保播意图：只有"确实在播"才置位，用户自己暂停进后台的不要拉回来
    bgKeepPlaying = playingBeforeHide
    hiddenAt = Date.now()
    bgLog(
      `hidden paused=${v?.paused} ct=${(v?.currentTime || 0).toFixed(1)} ` +
      `lastGood=${lastGoodPos.toFixed(1)} pos=${posBeforeHide.toFixed(1)} ` +
      `rd=${v?.readyState} wantPlay=${bgKeepPlaying} userPausedAgo=${userPausedAt ? Date.now() - userPausedAt : -1}`,
    )
    if (bgKeepPlaying) {
      notifyIosPlaying(true)
      /*
       * 有的系统版本**先暂停媒体再发 visibilitychange**：pause 事件已经错过，
       * 这里补一次（此时 paused=true 也不能当"用户暂停"，用 userPausedAt 区分）。
       */
      if (v?.paused) maybeKeepPlayingInBackground()
    }
    flushProgress(true)
    return
  }
  pauseMediaOnHide()
}

/**
 * iOS 壳回前台：补回横屏（系统会把 App 方向复位成竖屏）。
 *
 * 只在"之前就是沉浸态"时补：伪全屏 / 进播放页自动转的横屏 / 全屏自己转的横屏。
 * 用户主动退出全屏或竖屏观看时 pageLandscape/rotatedByFs 都是 false，不会误转。
 */
function reapplyMobileOrientation() {
  if (!isIosShell() || isTvShell()) return
  // 已经是横屏就不用补
  if (isLandscapeViewport()) return
  if (!(pseudoFs.value || pageLandscape || rotatedByFs)) return
  // 伪全屏状态下补转，退出全屏时才知道要转回来
  if (pseudoFs.value) rotatedByFs = true
  shellSetOrientation('landscape')
}

/**
 * iOS 壳「真正回到前台」的原生信号。
 *
 * <p>`MeowApp` 在 `scenePhase == .active` 时 eval `window.__meowIosActive()`。
 * 比 `visibilitychange` 可靠：个别场景后者会早于/晚于 scene 真正 active，
 * 续播与横屏恢复都会漏（2026-11-25 用户实测「偶尔不自动播」）。
 * 两次调用都幂等：`playingBeforeHide` 已复位、pseudoFs 已为真时直接返回。
 */
function onIosActive() {
  const vv = video.value
  bgLog(
    `ios-active paused=${vv?.paused} ct=${(vv?.currentTime || 0).toFixed(1)} ` +
    `playingBeforeHide=${playingBeforeHide} userPausedAgo=${userPausedAt ? Date.now() - userPausedAt : -1}`,
  )
  bgKeepPlaying = false
  resumedAt = Date.now()
  resumeAfterVisibility('ios-active')
  if (!isMobileForm()) return
  reapplyMobileOrientation()
  void restoreFsIntent()
}

/** 挂到 window 供原生调用；卸载时按引用比较摘掉 */
function iosActiveBridge() {
  window.dispatchEvent(new Event('meow:ios-active'))
}

/** 重新可见：把隐藏前正在播的续上（移动端后台保播 / TV 原生播放器返回） */
function resumeAfterVisibility(reason = 'visible') {
  const mobile = isMobileForm() && !isTvShell()
  if (!isTvEnv() && !mobile) return
  const v0 = video.value
  /*
   * 位置兜底先做，且不依赖 playingBeforeHide：
   * 用户可能在后台用系统播放条暂停/恢复过，回前台进度同样不能丢
   *（2026-11-25 真机「进度无法带回」）。恢复窗口由 timeupdate 持续校正。
   */
  if (v0 && !v0.ended) beginRestoreWindow(reason)
  if (!playingBeforeHide) return
  playingBeforeHide = false
  // 用户刚点过暂停：不要再替他播
  if (userPausedAt && Date.now() - userPausedAt < 2500) {
    bgLog('resume skipped: user paused recently')
    return
  }
  const v = video.value
  if (!v || v.ended) return
  // 系统暂停期间取流管线可能停在半路：先推 hls，再补 play
  if (hls.value) {
    try {
      hls.value.startLoad()
    } catch {
      /* 引擎已在销毁中就算了 */
    }
  }
  const retry = (delay: number) => {
    const id = window.setTimeout(() => {
      if (userPausedAt && Date.now() - userPausedAt < 2500) {
        bgLog(`resume retry(${delay}) skipped: user paused recently`)
        return
      }
      const el = video.value
      if (!el || el.ended) return
      /*
       * 进度兜底（移动端）：WebView 被回收/流被重置后 currentTime 会从 0 开始，
       * 这时拉回切后台时的位置；后台一直播的话 currentTime 已经更大，不会倒退。
       */
      if (isMobileForm() && !isTvShell()) applyRestoreSeek(el)
      bgLog(`resume retry(${delay}) paused=${el.paused} ct=${el.currentTime.toFixed(1)} rd=${el.readyState}`)
      if (el.paused) safePlay(el)
    }, delay)
    resumeTimers.push(id)
  }
  // 250ms 一次不够：锁屏/系统状态栏切回来时 WebKit 接管媒体要更久，
  // 补一次 1.2s 的重试（仍是 paused 才播，不会打断已恢复的播放）。
  retry(250)
  retry(1200)
}

/**
 * TV 壳从原生播放器（ExoPlayer）回到本页的恢复（2026-11-15 修2）。
 *
 * mediaExclusive 的 `__meowWebviewResume` 只负责"把该响的响起来"，取流
 * 管线它碰不到：WebView 被系统暂停期间 hls.js 的 pending 分片请求被掐、
 * MSE 不再追加，表现为回页后一直缓冲/定格，退出重进才恢复（实测）。
 * 这里推进两步：引擎侧 startLoad，元素侧补一次 play（延迟 300ms，
 * 等内核从系统暂停里完全醒过来）。用户手动暂停的（resume 钩子里
 * pausedBySystem=false）不会走到这条广播，不会被我们强行续播。
 */
function onWebviewResume() {
  const v = video.value
  if (!v || v.ended) return
  if (hls.value) {
    try {
      hls.value.startLoad()
    } catch {
      /* 引擎已在销毁中就算了 */
    }
  }
  window.setTimeout(() => {
    const el = video.value
    if (!el || el.ended) return
    if (el.paused) safePlay(el)
  }, 300)
}

watch(
  () => props.src,
  (v) => {
    if (!v) return
    // 换源（切线路）也算关键节点
    flushProgress(true)
    loadingSrc.value = true
    lastSent = -1
    setupSource(v)
  },
)

watch(
  () => dmOffsetKey.value,
  () => {
    // 切集：校准值按新「片名 · 集」重新取；没记录就是 0
    dmOffset.value = getDmOffset(dmOffsetKey.value)
    dm.value?.setOffset(dmOffset.value)
  },
)

/**
 * 把上一集手选的平台透传给自动抓取接口（prefer=iqiyi/tencent）。
 *
 * <p>只对自家自动接口生效；手选候选是完整 URL，不走这里。
 * 厂商平台之外的 youku/mango 不传——内置没有对应 handler，
 * 传了也不会改变顺序，交给聚合源覆盖。
 */
function withDmPrefer(url: string, prefer: string): string {
  if (!prefer || !url.includes('/api/proxy/danmaku?')) return url
  const p = dmPlatformOf({ source: prefer })
  if (p !== 'iqiyi' && p !== 'tencent') return url
  if (/[?&]prefer=/.test(url)) return url
  return url + '&prefer=' + encodeURIComponent(p)
}

watch(
  () => props.danmakuUrl,
  (v) => {
    clearDmUpgrade()
    dmLocalCount.value = 0
    /*
     * 手选源只对当前集有效（2026-11-09）：
     *   1. 先尝试在新一集的候选列表里找「同源同集」→ 找到就沿用；
     *   2. 找不到 → 记住平台，自动抓取时把它排到最前（prefer），
     *      并清掉手选 URL（否则面板显示手选、实际加载自动结果）。
     *
     * 不能直接 clear 了事：旧实现切集后 dmOverrideUrl 还挂着，
     * 面板显示「已手动指定弹幕源」但实际加载的是自动结果。
     */
    const prefer = dmManualSource.value
    let autoUrl = v
    if (dmOverrideUrl.value) {
      const ep = currentEpNo() || episodeFromTitle()
      const same = pickSameSourceCandidate(dmResults.value, dmManualSource.value, ep)
      if (same) {
        dmOverrideUrl.value = same.url
        showDmHint(`已沿用弹幕源 ${same.label || same.source || ''}`)
        if (v) void loadDanmaku(proxyDanmakuByUrl(same.url))
        return
      }
      dmOverrideUrl.value = ''
      dmManualSource.value = ''
      autoUrl = withDmPrefer(v || '', prefer)
      showDmHint(prefer ? `已沿用弹幕平台 ${prefer}，其余回退自动` : '手动弹幕源仅对当前集有效，已恢复自动')
    }
    if (autoUrl) void loadDanmaku(autoUrl)
    else {
      dmCount.value = 0
    dmTotal.value = 0
    dmTruncated.value = false
    dmRawList = []
    dmHeat.value = null
    dmHeatRef.value = 1
    dmHeatBucketSec.value = 0
    dmQuality.value = null
      dm.value?.clear()
    }
  },
)

// ---------------------------------------------------------------- format

function fmt(s: number): string {
  if (!isFinite(s) || s < 0) s = 0
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = Math.floor(s % 60)
  return h > 0
    ? `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
    : `${m}:${String(sec).padStart(2, '0')}`
}

/** 0–1 的比例显示成百分比；未知（0）显示 —，别把「未加载」说成 0%。 */
function pct(v: number): string {
  return v > 0 ? Math.round(v * 100) + '%' : '—'
}

/**
 * 中央时间提示（暂停时常驻 / 按键 seek 时短暂显示）。
 *
 * 显示"当前时间 / 总时长"，与底栏同一格式，方便对照。
 * 连按方向键时额外显示本次跳了多少秒（`+10s`），
 * 这样累加手感才有反馈，否则用户不知道第二次按跳了 10 秒。
 */
const seekHint = ref(0)
/** 本次跳转增量（秒），0 表示不显示 */
const seekDelta = ref(0)
let seekHintTimer: number | undefined

/** 按键 seek 后闪一下中央时间，让用户知道跳到了哪里 */
function flashSeekHint(delta = 0) {
  seekHint.value++
  seekDelta.value = delta
  clearTimeout(seekHintTimer)
  seekHintTimer = window.setTimeout(() => {
    seekHint.value = 0
    seekDelta.value = 0
  }, 900)
}

function clearResume() {
  // 由父组件决定是否清进度；这里只确保 UI 状态复位
  hoverTime.value = null
  dragTime.value = null
}

/** 暂停播放。投屏后本节要停，否则两边同时出声。 */
function pause() {
  // 外部（父组件/投屏）主动暂停：撤掉回前台续播的欠账，避免自己又播起来
  userPausedAt = Date.now()
  bgKeepPlaying = false
  cancelResumeTimers()
  clearRestoreWindow()
  /*
   * 端内原生渲染：`<video>.pause()` 打不到 VLC —— 投屏成功后本机不停的话
   * 会和电视同时出声（2026-11-27 审计）。交给原生 pause 指令。
   */
  if (iosNative) {
    iosNativeCmd('pause')
    return
  }
  const v = video.value
  if (v && !v.paused) v.pause()
}

defineExpose({
  restart,
  seekTo,
  getTime,
  pause,
  play,
  isPaused,
  getDuration,
  unmute,
  /** 停掉并清空当前源（片库决定不走直连时用，避免旧片在后台继续播） */
  stop: stopPlayback,
})
</script>

<template>
  <div
    ref="wrap"
    class="meow-player-root group relative h-full w-full select-none overflow-hidden bg-black"
    :class="pseudoFs ? 'fixed inset-0 z-[100] h-full w-full' : ''"
    :style="rotStyle"
    @mousemove="wake"
    @pointermove.passive="noteCursorActivity"
    @pointerdown.passive="noteCursorActivity"
    @click="onWrapClick"
  >
    <!-- 视频 + 弹幕层 -->
    <div
      ref="stage"
      class="absolute inset-0"
      style="touch-action: none"
      @click="onStageClick"
      @dblclick="onStageDblClick"
      @pointerdown="onStagePointerDown"
      @pointermove="onStagePointerMove"
      @wheel="onStageWheel"
      @pointerup="onStagePointerUp"
      @pointercancel="onStagePointerCancel"
      @pointerleave="onStagePointerUp"
    >
      <video
        ref="video"
        class="h-full w-full bg-black"
        :class="fitClass"
        :poster="poster"
        playsinline
        @timeupdate="onTimeUpdate"
        @loadedmetadata="onLoaded"
        @durationchange="onLoaded"
        @ended="onMediaEnded"
      >
        <track
          v-if="subUrl"
          default
          kind="subtitles"
          :src="subUrl"
          :label="subLabel"
          srclang="zh"
        />
      </video>
    </div>

    <!-- 播放失败：给出可操作出口，而不是只显示一行红字 -->
    <div
      v-if="errorMsg"
      class="absolute inset-0 z-30 grid place-items-center bg-black/80 px-6 text-center"
    >
      <div class="max-w-md">
        <div class="text-3xl opacity-40">⚠️</div>
        <div class="mt-3 text-sm text-hot">{{ errorMsg }}</div>
        <div class="mt-1.5 text-xs text-ink-3">该线路可能已失效</div>

        <div class="mt-5 flex flex-wrap items-center justify-center gap-2">
          <button
            class="rounded-md bg-accent px-4 py-2 text-sm font-medium text-on-accent transition hover:bg-accent-dim"
            @click.stop="retryNow"
          >
            重试
          </button>
          <button
            v-if="canSwitchLine"
            class="rounded-md bg-white/12 px-4 py-2 text-sm text-ink transition hover:bg-white/20"
            @click.stop="emit('retry', 'line')"
          >
            换线路
          </button>
          <button
            class="rounded-md bg-white/8 px-4 py-2 text-sm text-ink-2 transition hover:bg-white/14"
            @click.stop="goBackFromPlayer()"
          >
            返回
          </button>
        </div>

        <p v-if="autoRetryHint" class="mt-3 text-[11px] text-ink-3">{{ autoRetryHint }}</p>
      </div>
    </div>

    <!-- 顶部标题栏 -->
    <div
      class="meow-ctrl-bar meow-ctrl-mask-top absolute inset-x-0 top-0 z-30 flex items-center gap-3 pb-10 pr-4 pt-3 sm:pr-5 sm:pt-4"
      :class="[
        showCtrl ? 'opacity-100' : 'pointer-events-none invisible opacity-0',
        isDesktopShell() ? 'pl-24' : 'pl-4 sm:pl-5',
      ]"
      :style="isTouch ? { paddingTop: 'calc(env(safe-area-inset-top) + 0.75rem)' } : undefined"
    >
      <!--
        ★ TV 上不给返回按钮（用户要求，2026-11-02）。

        原因不是"多余"，是它**抢焦点**：tv-nav 按几何选焦，控制栏一出来最先
        命中的往往就是这个页头按钮。用户想选播放/选集，按 ok 却退出了整集。
        遥控器本身有返回键（`__meowTvConsumeBack` 两段语义），页头按钮在电视上
        没有额外价值。
      -->
      <button
        v-if="showBackButton"
        class="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-white/10 transition hover:bg-white/20"
        aria-label="返回"
        @click="leavePlayback()"
      >
        <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
          <path d="m15 18-6-6 6-6" />
        </svg>
      </button>
      <div
        class="min-w-0 flex-1"
        @pointerdown="beginWindowDrag"
        @dblclick="toggleMaximizeOnDblClick"
      >
        <div class="truncate text-[14px] font-medium sm:text-[15px]">{{ title }}</div>
        <!--
          副标题提到 ink-2（白 72%）而不是 ink-3（白 55%）：顶栏这行压在
          视频画面上，亮场景下 55% 白算出来只有 ≈1.9:1（2026-11-24 实报
          「弹幕 0 条 看不见」）。遮罩已改成带平台段的 .meow-ctrl-mask-top，
          两边一起才把对比度拉回 ≈4:1。
        -->
        <div class="text-[11px] text-ink-2">
          <template v-if="dmEmpty && !dmLoading">
            <span class="text-amber-400/90">弹幕加载失败（30 秒后重试）</span>
          </template>
          <template v-else-if="dmNoData && !dmLoading">
            <span class="text-ink-3">本集暂无弹幕</span>
          </template>
          <template v-else>
            弹幕 {{ dmCount }}<template v-if="dmTotal > dmCount"> / {{ dmTotal }}{{ dmTruncated ? '+' : '' }}</template> 条<template v-if="dmLoading"> · 加载中…</template>
            <template v-else-if="dmSourceLabel"> · {{ dmSourceLabel }}</template>
          </template>
        </div>
      </div>
      <!-- 投屏：右上角一级入口（与左上返回同一顶栏；不挤底栏）
           ★ 不可用时不渲染（2026-11-20 用户定）：留一个灰按钮只会成为
             tv-nav 初始落点的第一个可聚焦元素 —— 遥控器按 OK 落在一颗
             按了只弹"不能投屏"的按钮上，和当年返回键的坑一模一样。 -->
      <button
        v-if="canCast"
        class="relative grid h-9 w-9 shrink-0 place-items-center rounded-full bg-white/10 transition hover:bg-white/20"
        :class="casting ? 'text-accent' : ''"
        :aria-label="casting ? '正在投屏' : '投屏'"
        :title="casting ? '正在投屏' : '投屏到电视'"
        @click.stop="$emit('cast', castButtonAnchor($event))"
      >
        <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="2" y="6" width="14" height="10" rx="1.5" />
          <path d="M6 20h8" />
          <path d="M10 16v4" />
          <path d="M18 10.5a2.5 2.5 0 0 1 2.5 2.5" />
          <path d="M18 7a6 6 0 0 1 6 6" />
        </svg>
        <span
          v-if="casting"
          class="absolute right-1 top-1 h-1.5 w-1.5 rounded-full bg-accent ring-2 ring-black/50"
        />
      </button>
    </div>


    <!-- 缓冲中：只转圈，不挡点击 -->
    <div
      v-if="buffering && !errorMsg && !loadingSrc"
      class="pointer-events-none absolute inset-0 z-20 grid place-items-center"
    >
      <div class="h-11 w-11 animate-spin rounded-full border-2 border-white/25 border-t-player" />
    </div>

    <!-- 暂停浮层：点一下继续播放。中央显示 当前时间 / 总时长
         锁定状态下整个隐藏：那个播放按钮是 pointer-events-auto 的误触入口 -->
    <div
      v-if="showPauseOverlay && !locked && !playing && !errorMsg && duration > 0 && !loadingSrc && !buffering"
      class="pointer-events-none absolute inset-0 z-20 grid place-items-center"
      @dblclick="onStageDblClick"
    >
      <div class="flex flex-col items-center gap-2">
        <button
          class="pointer-events-auto grid h-16 w-16 place-items-center rounded-full bg-accent text-on-accent shadow-2xl transition hover:scale-105 hover:bg-accent-dim sm:h-20 sm:w-20"
          :aria-label="playing ? '暂停' : '播放'"
          @click.stop="onPauseOverlayClick"
          @dblclick.stop="onStageDblClick"
        >
          <svg class="ml-1 h-7 w-7 sm:h-8 sm:w-8" viewBox="0 0 24 24" fill="currentColor">
            <path d="M8 5v14l11-7z" />
          </svg>
        </button>
        <div class="pointer-events-auto rounded-full bg-black/55 px-3 py-1 font-mono text-xs text-ink backdrop-blur">
          {{ fmt(currentTime) }} / {{ fmt(duration) }}
        </div>
      </div>
    </div>

    <!--
      右上角常驻时间（设置项 showTimeBadge）。
      控制栏 5 秒后自动隐藏，想知道播到哪就得按键唤出；
      开了这个开关就能随时读到「当前 / 总时长」。
      pointer-events-none：不干扰点击与电视焦点。
    -->
    <div
      v-if="prefs.showTimeBadge && duration > 0 && !errorMsg"
      class="pointer-events-none absolute right-3 top-3 z-20 rounded-md bg-black/60 px-2 py-1 font-mono text-xs text-ink backdrop-blur"
    >
      {{ fmt(currentTime) }} / {{ fmt(duration) }}
    </div>

    <!-- 跳到指定时间：点底栏时间打开（数字键盘可用 1234 = 12:34） -->
    <div
      v-if="seekDialog"
      class="absolute inset-0 z-50 grid place-items-center bg-black/45 px-6"
      @click.self="seekDialog = false"
    >
      <div
        class="w-full max-w-[18rem] rounded-xl border border-white/10 bg-black/90 p-4 text-ink shadow-2xl"
      >
        <p class="text-xs text-white/60">跳到指定时间</p>
        <input
          ref="seekInputEl"
          v-model="seekText"
          enterkeyhint="go"
          class="mt-2 w-full rounded-lg border border-white/15 bg-white/10 px-3 py-2.5 text-center font-mono text-lg text-white outline-none focus:border-player"
          placeholder="12:34"
          @keyup.enter="applySeekDialog"
        />
        <p class="mt-1.5 text-[11px] leading-relaxed text-white/45">
          支持 12:34 / 1:02:03；只用数字键时 1234 = 12:34
        </p>
        <div class="mt-3 flex gap-2">
          <button
            type="button"
            class="h-11 flex-1 rounded-lg bg-white/10 text-sm text-white/80"
            @click="seekDialog = false"
          >
            取消
          </button>
          <button
            type="button"
            class="h-11 flex-1 rounded-lg bg-accent text-sm font-medium text-on-accent"
            @click="applySeekDialog"
          >
            跳转
          </button>
        </div>
      </div>
    </div>

    <!--
      最底部迷你进度条（设置项 showMiniProgress）。
      不唤控制栏也能看到播放位置；只在控制栏隐藏时显示，
      否则会和底栏自带的进度条重复。
    -->
    <div
      v-if="prefs.showMiniProgress && !locked && duration > 0 && !showCtrl && !errorMsg"
      class="pointer-events-none absolute inset-x-0 bottom-0 z-20 h-[3px] bg-white/15"
    >
      <div class="h-full bg-player transition-[width] duration-300" :style="{ width: shownPct + '%' }" />
    </div>

    <!--
      按键 seek 时的中央时间提示。
      播放中按 ←/→ 时底栏可能已隐藏，没有反馈会不知道跳没跳；
      暂停时暂停浮层本身就在显示时间，所以这里只在播放中叠加，
      闪 0.9 秒后自动消失。连按时额外显示本次跳了多少秒。
    -->
    <div
      v-if="seekHint > 0 && playing && !errorMsg"
      class="pointer-events-none absolute inset-0 z-20 grid place-items-center"
    >
      <div class="flex items-center gap-2 rounded-lg bg-black/70 px-4 py-2 shadow-2xl backdrop-blur">
        <span class="font-mono text-[length:var(--fs-lg)] text-ink">{{ fmt(currentTime) }} / {{ fmt(duration) }}</span>
        <span v-if="seekDelta" class="font-mono text-sm text-player">
          {{ seekDelta > 0 ? '+' : '' }}{{ seekDelta }}s
        </span>
      </div>
    </div>

    <!--
      竖滑手势浮层：左区调亮度、右区调音量（2026-11-03）。

      ★ 放在**画面右侧竖条**而不是正中：手指正在屏幕右半边滑动，
        提示条出现在手指附近才不会被自己挡住。亮度在左区则靠左。
      ★ 拿不到系统能力时标注「仅画面」——否则用户在浏览器里滑了发现
        系统亮度没变，会以为手势没生效。
      ★ 指示器做成**竖向柱子**（2026-11-03 用户要求）：手势是上下滑，
        横向进度条的方向和动作对不上；柱子从底部往上涨，一眼就知道
        "往上滑 = 调大"。
    -->
    <!-- 横滑 seek 预览：按住画面左右拖时显示目标位置与增量，松手才真正跳 -->
    <div
      v-if="hseekHint"
      class="pointer-events-none absolute left-1/2 top-1/2 z-20 -translate-x-1/2 -translate-y-1/2"
    >
      <div
        class="flex flex-col items-center gap-1 rounded-xl bg-black/75 px-4 py-2.5 shadow-2xl backdrop-blur"
        role="slider"
        :aria-valuenow="Math.round(hseekHint.to)"
        aria-valuemin="0"
      >
        <span class="font-mono text-[length:var(--fs-lg)] tabular-nums text-ink">
          {{ fmt(hseekHint.from) }} → {{ fmt(hseekHint.to) }}
        </span>
        <span
          class="font-mono text-xs tabular-nums"
          :class="hseekHint.delta > 0 ? 'text-player' : 'text-amber-300'"
        >
          {{ hseekHint.delta > 0 ? '快进' : '快退' }} {{ Math.abs(Math.round(hseekHint.delta)) }} 秒
        </span>
      </div>
    </div>
    <div
      v-if="swipeHint"
      class="pointer-events-none absolute inset-y-0 z-20 flex w-1/3 items-center justify-center"
      :class="swipeHint.kind === 'brightness' ? 'left-0' : 'right-0'"
    >
      <div
        class="flex flex-col items-center gap-2 rounded-2xl bg-black/60 px-3 py-3 shadow-2xl backdrop-blur"
        role="progressbar"
        :aria-label="swipeHint.kind === 'brightness' ? '亮度' : '音量'"
        aria-valuemin="0"
        aria-valuemax="100"
        :aria-valuenow="swipeHint.pct"
      >
        <svg
          v-if="swipeHint.kind === 'brightness'"
          class="h-5 w-5 text-amber-300"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
        >
          <circle cx="12" cy="12" r="4" />
          <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
        </svg>
        <svg
          v-else
          class="h-5 w-5 text-ink"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
        >
          <path d="M11 5 6 9H2v6h4l5 4V5z" />
          <path d="M15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14" />
        </svg>
        <!-- 轨道：填充从底部往上长，与"上滑 = 调大"同向 -->
        <div class="relative h-36 w-3 overflow-hidden rounded-full bg-white/25">
          <div
            class="absolute inset-x-0 bottom-0 rounded-full transition-[height] duration-75"
            :class="swipeHint.kind === 'brightness' ? 'bg-amber-300' : 'bg-player'"
            :style="{ height: swipeHint.pct + '%' }"
          />
        </div>
        <span class="font-mono text-sm leading-none text-ink">{{ swipeHint.pct }}%</span>
        <!--
          兜底说明：亮度只能压画面、音量只能改本播放器时要说清楚，
          否则用户以为调的是系统。两种兜底的语义不同，不能共用一句话
          （2026-11-03 实测：音量分支也跟着显示"仅画面"，读起来像笔误）。
        -->
        <span v-if="!swipeHint.system" class="text-xs leading-none text-ink-3">
          {{ swipeHint.note || (swipeHint.kind === 'brightness' ? '仅画面' : '仅播放器') }}
        </span>
      </div>
    </div>

    <!-- 无系统亮度能力时的兜底变暗层（只能变暗，不能让画面更亮） -->
    <div
      v-if="cssDim > 0"
      class="pointer-events-none absolute inset-0 z-10 bg-black"
      :style="{ opacity: cssDim }"
    />

    <!--
      TV 返回键的两段提示。
      遥控器最容易误触的就是返回，一下退出整集太粗暴；第一次按只暂停并提示
      "再按一次返回退出"，2.5 秒内再按才真的退出（见 __meowTvConsumeBack）。
      必须画在画面中央：底栏此时可能已隐藏，只改底栏等于没有反馈。
    -->
    <div
      v-if="backHint"
      class="pointer-events-none absolute inset-0 z-20 grid place-items-center"
    >
      <div class="rounded-lg bg-black/75 px-5 py-2.5 text-[length:var(--fs-lg)] text-ink shadow-2xl backdrop-blur">
        {{ backHint }}
      </div>
    </div>

    <!-- 全屏失败提示：点了没反应是最糟的体验，至少要说清原因 -->
    <div
      v-if="fsHint"
      class="pointer-events-none absolute inset-x-0 top-1/2 z-40 flex -translate-y-1/2 justify-center px-6"
    >
      <div class="rounded-lg bg-black/85 px-4 py-2 text-center text-xs text-ink shadow-2xl backdrop-blur">
        {{ fsHint }}
      </div>
    </div>

    <!-- 弹幕源提示（沿用此源 / 已切换到更全的来源），不挡操作 -->
    <div
      v-if="dmHint"
      class="pointer-events-none absolute inset-x-0 top-16 z-40 flex justify-center px-6"
    >
      <div class="rounded-lg bg-black/85 px-4 py-2 text-center text-xs text-ink shadow-2xl backdrop-blur">
        {{ dmHint }}
      </div>
    </div>

    <!--
      播放信息（调试）。
      中央偏上、半透明、pointer-events 透传 —— 打开时不能挡住
      播放/暂停和进度条，否则"看信息"反而没法操作。
      手机上窄，三层用单列；sm 以上并排两列，信息密度更高。
    -->
    <div
      v-if="debugOn && debugSnapshot"
      data-tv-scope
      class="pointer-events-none absolute inset-x-2 top-14 z-[35] mx-auto max-w-3xl rounded-lg bg-black/80 p-2.5 font-mono text-[10px] leading-relaxed text-ink shadow-2xl backdrop-blur sm:inset-x-4 sm:top-16 sm:p-3 sm:text-[11px]"
    >
      <!--
        ★ 关闭按钮（2026-11-04 用户要求：打开后聚焦关闭按钮，返回键可关）。
          面板整体仍是 pointer-events-none（不能挡住播放/进度条），
          只给按钮 pointer-events-auto。
      -->
      <div class="mb-1.5 flex items-center justify-between">
        <span class="font-sans text-[11px] font-medium text-player">播放信息</span>
        <button
          ref="debugCloseEl"
          data-tv-first
          class="pointer-events-auto rounded px-2 py-0.5 font-sans text-[11px] text-ink-3 transition hover:bg-white/10 hover:text-ink"
          @click="toggleDebug()"
        >
          关闭
        </button>
      </div>

      <div class="grid grid-cols-1 gap-x-4 gap-y-1 sm:grid-cols-2">
        <!-- 内核层 -->
        <div>
          <div class="text-[10px] text-ink-3">内核</div>
          <div class="flex flex-wrap gap-x-3">
            <span>解析 {{ debugSnapshot.video.resolution }}</span>
            <span v-if="debugSnapshot.hls?.codecs">编码 {{ debugSnapshot.hls.codecs }}</span>
            <span
              :class="debugSnapshot.video.readyState >= 3 ? 'text-ok' : 'text-warn'"
            >rs={{ debugSnapshot.video.readyState }}</span>
            <span
              :class="debugSnapshot.video.bufferedAhead < 2 ? 'text-warn' : ''"
            >缓冲 {{ debugSnapshot.video.bufferedAhead }}s</span>
            <span v-if="debugSnapshot.video.dropped !== null">
              丢帧 {{ debugSnapshot.video.dropped }}/{{ debugSnapshot.video.totalFrames }}
            </span>
            <span>{{ debugSnapshot.video.playbackRate }}x</span>
          </div>
        </div>

        <!-- HLS 层 -->
        <div v-if="debugSnapshot.hls">
          <div class="text-[10px] text-ink-3">HLS</div>
          <div class="flex flex-wrap gap-x-3">
            <span>
              档位 {{ debugSnapshot.hls.level + 1 }}/{{ debugSnapshot.hls.levelCount }}
              <template v-if="debugSnapshot.hls.bitrate">
                · {{ Math.round(debugSnapshot.hls.bitrate / 1000) }}kbps
              </template>
            </span>
            <span v-if="debugSnapshot.hls.bandwidthEstimate">
              估速 {{ fmtBandwidth(debugSnapshot.hls.bandwidthEstimate) }}
            </span>
          </div>
          <!-- 各档码率：判断"ABR 为什么选这档"直接看这个 -->
          <div v-if="debugSnapshot.hls.levels.length > 1" class="mt-0.5 text-ink-3">
            档位码率 {{ debugSnapshot.hls.levels.join(' / ') }} kbps
          </div>
          <div v-if="debugSnapshot.hls.frags.length" class="mt-0.5 text-ink-3">
            分片
            <span
              v-for="(f, i) in debugSnapshot.hls.frags"
              :key="i"
              :class="f.ms > 3000 ? 'text-warn' : ''"
              class="ml-1"
            >{{ f.ms }}ms</span>
          </div>
          <div v-if="debugSnapshot.hls.lastError" class="mt-0.5 text-hot">
            错误 {{ debugSnapshot.hls.lastError }}
          </div>
        </div>

        <!-- 业务层 -->
        <div>
          <div class="text-[10px] text-ink-3">业务</div>
          <div class="flex flex-wrap gap-x-3">
            <span>线路 {{ debugSnapshot.biz.line }}</span>
            <span>起播 {{ debugSnapshot.biz.firstFrameMs ?? '—' }}ms</span>
            <span v-if="debugSnapshot.biz.media">{{ debugSnapshot.biz.media }}</span>
            <span
              :class="debugSnapshot.biz.recoverAttempts > 0 ? 'text-warn' : ''"
            >重试 {{ debugSnapshot.biz.recoverAttempts }}/{{ debugSnapshot.biz.maxRecoverAttempts }}</span>
          </div>
          <div class="mt-0.5 flex flex-wrap gap-x-3 text-ink-3">
            <span>
              弹幕 {{ debugSnapshot.biz.danmaku.count }} 条 ·
              在场 {{ debugSnapshot.biz.danmaku.onScreen }}/{{ debugSnapshot.biz.danmaku.lanes }}轨
            </span>
            <span>{{ debugSnapshot.biz.danmaku.density }}</span>
          </div>
          <!--
            弹幕质量（2026-11-20）：来源 / 模式分布 / 覆盖片长 / 时间轴校准。
            聚合源时间轴错位时，这里会显示「覆盖不足」或「分布集中」，
            不用再抓包猜是源问题还是渲染问题。
          -->
          <div class="mt-0.5 flex flex-wrap gap-x-3 text-ink-3">
            <span>滚 {{ debugSnapshot.biz.danmaku.scroll }} / 顶 {{ debugSnapshot.biz.danmaku.top }} / 底 {{ debugSnapshot.biz.danmaku.bottom }}</span>
            <span>来源 {{ debugSnapshot.biz.danmaku.source }}</span>
            <span :class="debugSnapshot.biz.danmaku.cover > 0 && debugSnapshot.biz.danmaku.cover < 0.8 ? 'text-warn' : ''">
              覆盖 {{ pct(debugSnapshot.biz.danmaku.cover) }}
            </span>
            <span v-if="debugSnapshot.biz.danmaku.warn" class="text-warn">⚠ {{ debugSnapshot.biz.danmaku.warn }}</span>
            <span>校准 {{ debugSnapshot.biz.danmaku.offset > 0 ? '+' : '' }}{{ debugSnapshot.biz.danmaku.offset.toFixed(1) }}s</span>
          </div>
        </div>

        <!-- 播放器状态 -->
        <div>
          <div class="text-[10px] text-ink-3">状态</div>
          <div class="flex flex-wrap gap-x-3">
            <span :class="debugSnapshot.state.buffering ? 'text-warn' : ''">
              {{ debugSnapshot.state.buffering ? '缓冲中' : '就绪' }}
            </span>
            <span>{{ debugSnapshot.video.paused ? '暂停' : '播放' }}</span>
            <span>{{ debugSnapshot.video.currentTime }}/{{ debugSnapshot.video.duration }}</span>
            <span v-if="debugSnapshot.state.fullscreen" class="text-player">全屏</span>
          </div>
          <div v-if="debugSnapshot.state.error" class="mt-0.5 text-hot">
            {{ debugSnapshot.state.error }}
          </div>
        </div>
      </div>
    </div>

    <!-- 长按快退/快进 或 按住方向键扫描中：右上角提示，避免用户以为卡了 -->
    <div
      v-if="keyScan !== 0"
      class="pointer-events-none absolute right-4 top-16 z-30 flex items-center gap-1.5 rounded-full bg-black/80 px-3 py-1.5 text-xs text-ink shadow-2xl backdrop-blur"
    >
      <svg
        class="h-3.5 w-3.5 text-player"
        :class="keyScan === -1 ? 'rotate-180' : ''"
        viewBox="0 0 24 24"
        fill="currentColor"
      >
        <path d="M4 5v14l8-7zM13 5v14l8-7z" />
      </svg>
      {{ keyScan === -1 ? '2x 快退中' : '2x 快进中' }}
    </div>

    <!--
      竖屏提示：横屏锁定失败时的兜底（iOS Safari 无法锁横屏）。
      只占中间一块，不能铺满——铺满会盖住底栏，导致全屏/选集按钮点不动。
    -->
    <div
      v-if="portraitHint"
      class="pointer-events-none absolute inset-0 z-40 grid place-items-center px-6 text-center"
    >
      <div class="pointer-events-auto max-w-sm rounded-xl bg-black/85 p-4 shadow-2xl backdrop-blur">
        <div class="mx-auto mb-4 h-12 w-12 rotate-90 text-player">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <rect x="4" y="2" width="16" height="20" rx="2.5" />
            <path d="M10 19h4" />
          </svg>
        </div>
        <p class="text-sm text-ink">建议横屏观看</p>
        <p class="mt-1.5 text-xs text-ink-3">
          旋转手机后将自动铺满。浏览器不支持自动锁定横屏时需手动旋转。
        </p>
        <div class="mt-5 flex items-center justify-center gap-2">
          <button
            class="rounded-md bg-accent px-4 py-2 text-sm font-medium text-on-accent transition hover:bg-accent-dim"
            @click="enterFullscreenFromHint"
          >
            全屏观看
          </button>
          <button
            class="rounded-md bg-white/10 px-4 py-2 text-sm text-ink-2 transition hover:bg-white/16"
            @click="dismissPortraitHint"
          >
            竖屏继续
          </button>
        </div>
      </div>
    </div>

    <!--
      锁定 / 解锁（移动端全屏，右侧边界上下居中，2026-11-25 用户口径）。
      · 未锁定：随控制栏显隐（主流 App 的「锁」都在播放层上，不占底栏）；
      · 锁定后：控制栏收起，点屏幕任意处在**同一位置**闪出解锁按钮；
      · 浮层展开时让位（右侧中部会压到面板）。
    -->
    <button
      v-if="canLock && !anyPanelOpen && ((!locked && showCtrl) || (locked && lockHint))"
      type="button"
      class="absolute top-1/2 z-40 grid h-10 w-10 -translate-y-1/2 place-items-center rounded-full bg-black/60 text-ink shadow-xl ring-1 ring-white/20"
      style="right: max(0.75rem, env(safe-area-inset-right))"
      :aria-label="locked ? '解锁画面' : '锁定画面'"
      :title="locked ? '解锁' : '锁定画面（防误触）'"
      @click.stop="toggleLock"
    >
      <!-- 已锁定：闭锁（高亮，一眼看出当前状态） -->
      <svg
        v-if="locked"
        class="h-5 w-5 text-player"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
      >
        <rect x="4.5" y="10.5" width="15" height="10" rx="2" />
        <path d="M8 10.5V7a4 4 0 0 1 8 0v3.5" />
      </svg>
      <!-- 未锁定：开锁（提示"点我锁屏"） -->
      <svg
        v-else
        class="h-5 w-5"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
      >
        <rect x="4.5" y="10.5" width="15" height="10" rx="2" />
        <path d="M8 10.5V7a4 4 0 0 1 7.5-1.9" />
      </svg>
    </button>

    <!-- 底部控制栏 -->
    <div
      class="meow-ctrl-bar meow-ctrl-mask absolute inset-x-0 bottom-0 z-30 px-3 pb-2 pt-7 sm:px-5 sm:pb-2.5 sm:pt-9"
      :class="showCtrl ? 'opacity-100' : 'pointer-events-none invisible opacity-0'"
      data-ctrl-root
    >
      <!-- 进度条：悬停显示时间，按下拖动只改视觉，松手才 seek -->
      <div
        class="group/bar relative mb-1.5 cursor-pointer pb-2 pt-5"
        @pointerdown="onBarDown"
        @pointermove="onBarMove"
        @pointerup="onBarUp"
        @pointercancel="onBarUp"
        @pointerleave="onBarLeave"
      >
        <!--
          时间轴弹幕密度：双条模式 = 进度条上方的独立峰谷图（B 站高能条同款）。
          点击/拖动跳到对应时间；悬停条数并进下面的时间气泡。
        -->
        <div
          v-if="showCtrl && prefs.danmakuHeat === 'bar' && dmHeat"
          class="absolute inset-x-0 top-0 h-5 cursor-pointer overflow-hidden"
          title="点击跳到该时间"
          aria-hidden="true"
          @pointerdown.stop="onHeatDown"
          @pointerup.stop="onHeatUp"
          @pointercancel.stop="onHeatUp"
          @click.stop="onHeatClick"
        >
          <!-- 连续山形面积（参考图同款）：柱状竖条会读成横脉冲 -->
          <svg
            class="absolute inset-0 h-full w-full"
            viewBox="0 0 100 100"
            preserveAspectRatio="none"
            aria-hidden="true"
          >
            <rect width="100" height="100" fill="rgba(0,0,0,0.25)" />
            <path :d="dmHeatPath" :fill="dmHeatFill" />
          </svg>
        </div>
        <!--
          固定 8px 高度槽：悬停时只有里面的视觉条 h-1.5 → h-2 变粗。
          直接把 h-1.5/h-2 挂在流内元素上会让整条包裹层高 2px 变化，
          控制栏贴底布局（absolute bottom-0）于是把上方的密度峰谷图
          顶上去又落下来 —— 鼠标在进度条上移动时"上下抖动"（2026-11-20 修）。
        -->
        <div class="flex h-2 w-full items-center">
          <div class="relative h-1.5 w-full rounded-full bg-white/20 transition-all group-hover/bar:h-2">
            <!-- 单条模式：热度压进进度条内部当底色（播放/缓冲仍在上层） -->
            <div
              v-if="showCtrl && prefs.danmakuHeat === 'inline' && dmHeatInline"
              class="absolute inset-0 rounded-full opacity-80"
              :style="{ backgroundImage: dmHeatInline }"
              aria-hidden="true"
            />
            <!-- 已缓冲 -->
            <div class="absolute inset-y-0 left-0 rounded-full bg-white/25" :style="{ width: buffered + '%' }" />
            <!-- 已播放 -->
            <div class="absolute inset-y-0 left-0 rounded-full bg-player" :style="{ width: shownPct + '%' }" />
            <!-- 拖动手柄 -->
            <div
              class="absolute top-1/2 h-3 w-3 -translate-x-1/2 -translate-y-1/2 rounded-full bg-player opacity-0 shadow transition-opacity group-hover/bar:opacity-100"
              :class="dragTime != null ? 'opacity-100' : ''"
              :style="{ left: shownPct + '%' }"
            />
          </div>
        </div>
        <!-- 时间气泡 -->
        <div
          v-if="tipTime != null"
          class="pointer-events-none absolute bottom-full z-10 mb-1 -translate-x-1/2 rounded bg-black/85 px-1.5 py-0.5 font-mono text-[11px] text-ink shadow"
          :style="{ left: tipLeft + '%' }"
        >
          {{ fmt(tipTime) }}<template v-if="dmHeatTipCount != null"> · {{ dmHeatTipCount }} 条</template>
        </div>
      </div>

      <!-- 第一层：高频项（永远显示） -->
      <div class="flex items-center gap-1.5 sm:gap-2">
        <!-- 播放/暂停
             ★ data-tv-first（2026-11-20 加）：进播放页时 tv-nav 的落点优先
               认它。此前播放页没有任何 data-tv-first，`firstContentItem()` 又
               找不到 main/.poster-grid/[data-sec]，于是退化到「全文档第一个
               可聚焦元素」= 顶栏右上角的投屏按钮（返回键在 TV 大屏横屏下
               不渲染）。焦点环落在投屏上，按 OK 只会弹"不能投屏"。 -->
        <button
          class="hit-44 grid h-9 w-9 shrink-0 place-items-center rounded-full transition hover:bg-white/10"
          :aria-label="playing ? '暂停' : '播放'"
          data-tv-primary
          data-tv-first
          @click="toggle"
        >
          <svg v-if="playing" class="h-5 w-5" viewBox="0 0 24 24" fill="currentColor">
            <path d="M6 4h4v16H6zM14 4h4v16h-4z" />
          </svg>
          <svg v-else class="ml-0.5 h-5 w-5" viewBox="0 0 24 24" fill="currentColor">
            <path d="M8 5v14l11-7z" />
          </svg>
        </button>

        <!-- 时间：可点，打开「跳到指定时间」（长视频用；注释与实现必须一致） -->
        <button
          type="button"
          class="hit-44 shrink-0 font-mono text-[11px] text-ink-2 sm:text-xs"
          aria-label="跳到指定时间"
          @click="openSeekDialog"
        >
          {{ fmt(shownTime) }} / {{ fmt(shownDur) }}
        </button>

        <!-- 音量：手机有实体音量键，竖屏底栏让位给全屏/更多 -->
        <div class="hidden shrink-0 items-center gap-1.5 sm:flex">
          <button
            class="grid h-9 w-9 place-items-center rounded-full transition hover:bg-white/10"
            :aria-label="muted || volume === 0 ? '取消静音' : '静音'"
            @click="toggleMute"
          >
            <svg v-if="muted || volume === 0" class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M11 5 6 9H2v6h4l5 4V5z" />
              <path d="m23 9-6 6M17 9l6 6" />
            </svg>
            <svg v-else class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M11 5 6 9H2v6h4l5 4V5z" />
              <path d="M15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14" />
            </svg>
          </button>
          <!--
            音量条常驻可见：触屏没有 hover，桌面靠 focus 展开也被同优先级的
            sm:w-0 覆盖掉（实测 focus 后宽度仍为 0），所以不做折叠。
          -->
          <input
            type="range"
            min="0"
            max="100"
            aria-label="音量"
            class="hidden h-1 w-20 shrink-0 cursor-pointer appearance-none rounded-full bg-white/25 sm:block tv-hide"
            :value="muted ? 0 : volume * 100"
            @input="onVolumeInput"
          />
        </div>

        <!-- 上一集 / 下一集 / 选集（单集或未知位置时不显示）。窄屏由选集面板代替 -->
        <div v-if="canSwitch" class="hidden shrink-0 items-center gap-0.5 md:flex">
          <button
            class="grid h-9 w-9 place-items-center rounded-full transition enabled:hover:bg-white/10 disabled:opacity-30"
            :disabled="!hasPrev"
            title="上一集"
            @click="goPrev"
          >
            <svg class="h-[18px] w-[18px]" viewBox="0 0 24 24" fill="currentColor">
              <path d="M7 5h2v14H7zM19 5v14L9 12z" />
            </svg>
          </button>
          <button
            class="grid h-9 w-9 place-items-center rounded-full transition enabled:hover:bg-white/10 disabled:opacity-30"
            :disabled="!hasNext"
            title="下一集"
            @click="goNext"
          >
            <svg class="h-[18px] w-[18px]" viewBox="0 0 24 24" fill="currentColor">
              <path d="M15 5h2v14h-2zM5 5v14l10-7z" />
            </svg>
          </button>
          <button
            class="flex items-center gap-1 rounded-md bg-white/10 px-2 py-1 text-xs text-ink transition hover:bg-white/20"
            :class="showEpPanel ? 'bg-white/20' : ''"
            title="选集"
            @click.stop="toggleEpPanel($event)"
          >
            <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="3" y="4" width="7" height="7" rx="1.5" />
              <rect x="14" y="4" width="7" height="7" rx="1.5" />
              <rect x="3" y="13" width="7" height="7" rx="1.5" />
              <rect x="14" y="13" width="7" height="7" rx="1.5" />
            </svg>
            <span class="hidden sm:inline">选集</span>
          </button>
        </div>

        <!-- 线路（紧跟选集右侧；与选集独立，单集时也能换线路） -->
        <button
          v-if="canPickLine"
          class="hidden shrink-0 items-center gap-1 rounded-md bg-white/10 px-2 py-1 text-xs text-ink transition hover:bg-white/20 sm:flex"
          :class="showLinePanel ? 'bg-white/20' : ''"
          title="切换线路"
          @click.stop="toggleLinePanel($event)"
        >
          <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M4 7h11a3 3 0 0 1 0 6H8a3 3 0 0 0 0 6h12" />
            <path d="m17 4 3 3-3 3" />
          </svg>
          <span class="hidden max-w-[7rem] truncate sm:inline">{{ lineName || '线路' }}</span>
        </button>

        <!--
          原生播放（TV 壳）：紧跟线路按钮右侧，主动把当前集交给 ExoPlayer。
          触发父组件的 tryNative（带 header / 续播进度）；Web / PC 无桥不显示。
        -->
        <button
          v-if="nativeAvailable"
          class="hidden shrink-0 items-center gap-1 rounded-md bg-white/10 px-2 py-1 text-xs text-ink transition hover:bg-white/20 sm:flex"
          title="用原生播放器播放"
          @click.stop="emit('nativePlay')"
        >
          <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="2.5" y="4.5" width="19" height="13" rx="2" />
            <path d="M10 8.5v5l4.5-2.5z" fill="currentColor" stroke="none" />
            <path d="M8 20.5h8" />
          </svg>
          <span class="hidden sm:inline">原生</span>
        </button>

        <!--
          片头 / 片尾标记：放在左侧按键组的最右（选集、线路之后，弹性间隙之前）。
          「点一下即以当前时间为准」，点了有 toast；再点一次（时间几乎相同）= 取消。
          只在这一集拿到 site+vodId 时显示，否则存不了。
        -->
        <template v-if="site && vodId">
          <button
            class="hidden h-7 shrink-0 items-center gap-1 rounded-md bg-white/10 px-2 text-[11px] transition hover:bg-white/20 lg:flex"
            :class="marks.intro ? 'text-accent' : 'text-ink-3 hover:text-ink'"
            :title="
              marks.intro
                ? `片头结束于 ${fmtClock(marks.intro)}，点击取消`
                : '把当前位置设为片头结束点（下次从开头播会自动提示跳过）'
            "
            @click.stop="toggleIntroMark"
          >
            <svg class="h-3.5 w-3.5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M5 4v16M5 5h11l-1.5 3L16 11H5" />
            </svg>
            <span class="tabular-nums">{{ marks.intro ? fmtClock(marks.intro) : '片头' }}</span>
          </button>
          <button
            class="hidden h-7 shrink-0 items-center gap-1 rounded-md bg-white/10 px-2 text-[11px] transition hover:bg-white/20 lg:flex"
            :class="marks.outro ? 'text-accent' : 'text-ink-3 hover:text-ink'"
            :title="
              marks.outro
                ? `片尾始于 ${fmtClock(duration - marks.outro)}，点击取消`
                : '把当前位置设为片尾开始点'
            "
            @click.stop="toggleOutroMark"
          >
            <svg class="h-3.5 w-3.5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M19 4v16M19 5H8l1.5 3L8 11h11" />
            </svg>
            <span class="tabular-nums">
              {{ marks.outro ? fmtClock(duration - marks.outro) : '片尾' }}
            </span>
          </button>
        </template>

        <div class="min-w-0 flex-1" />

        <!-- 弹幕开关（高频，常驻） -->
        <!--
          控制栏里的按钮一律**不挂 backdrop-blur**（2026-11-24 用户实报）：
          老内核（投影仪 Chromium 66）的 legacy 层会把 `.backdrop-blur` 的
          背景改写成实色 --color-elevated，于是「弹 / 搜索 / 1x」变成一块块
          深蓝，跟开着的青色「弹」和白色图标对不上。去掉后各按钮保留自己的
          bg-white/10、bg-player/20，与现代内核一致。
          另外它们本来就压在 0.63~0.72 的深色遮罩上，10% 白已经够，模糊
          在这层纱上贡献≈0，去掉还省掉播放中每帧的 backdrop-filter 开销。
        -->
        <button
          class="hit-44 shrink-0 rounded-md px-2 py-1.5 text-xs font-medium transition"
          :class="dmOn ? 'bg-player/20 text-player' : 'bg-white/10 text-ink-3'"
          :title="dmOn ? '关闭弹幕' : '开启弹幕'"
          @click="toggleDanmaku"
        >
          弹
        </button>

        <!-- 弹幕搜索：自动抓不到时的手动兜底，紧邻开关 -->
        <button
          class="hidden h-7 w-7 shrink-0 place-items-center rounded-md bg-white/10 transition hover:bg-white/20 sm:grid"
          title="搜索弹幕"
          @click.stop="openDmSearch()"
        >
          <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
          </svg>
        </button>

        <!--
          弹幕设置（移动端）：紧贴搜索图标右侧，直接开面板。
          以前只有「更多」菜单里有入口，手机全屏要看设置得先点更多（2026-11-25 用户要求）。
          PC/TV 仍在「更多」菜单（本按钮 isTouch 才渲染）。

          可见性见 `showDmSettingBtn`（竖屏不显示、横屏/伪横屏显示）。
          ★ 2026-11-27 修：原来写的是 `hidden … sm:grid` —— `sm:` 断点是 640px，
          而手机上的 CSS 视口只有 440px（飞牛 App 实测 viewport 440×880 dpr=3），
          于是这个按钮**在手机上根本不渲染**。但也不能简单地常显：
          用户口径是「竖屏尺寸不够就不显示，横屏后应该显示」（IPA 真横屏时正常）。
          所以改成 `showDmSettingBtn` 显式判「真横屏 or CSS 伪横屏」，
          不按设备、也不靠断点。PC 的 isTouch 为 false，走「更多」菜单不变。
        -->
        <button
          v-if="showDmSettingBtn"
          class="grid h-7 w-7 shrink-0 place-items-center rounded-md bg-white/10 transition hover:bg-white/20"
          title="弹幕设置"
          aria-label="弹幕设置"
          @click.stop="toggleDmPanel($event)"
        >
          <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M4 6h16M4 12h10M4 18h7" />
          </svg>
        </button>

        <!-- 倍速 -->
        <RateSelect
          class="shrink-0"
          :parent-visible="showCtrl"
          :model-value="rate"
          :options="rateOptions"
          @update:model-value="setRate(Number($event))"
        />

        <!-- 重播 / 刷新：TV 上要能从底栏直接够到（原来只在「更多」里）。
             TV 视口没有 hover，藏进更多等于多按两次；这两项又是排障最常用的。
             触屏手机（竖屏/横屏）空间不够，会把「更多」挤出可视区（2026-11-17
             用户报），所以小屏触屏隐藏、收进「更多」——菜单里这两项一直都在。 -->
        <button
          class="touch-hide hit-44 grid h-9 w-9 shrink-0 place-items-center rounded-full transition hover:bg-white/10"
          title="重播"
          @click="restart()"
        >
          <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M3 12a9 9 0 1 0 3-6.7" />
            <path d="M3 4v5h5" />
          </svg>
        </button>
        <button
          class="touch-hide hit-44 grid h-9 w-9 shrink-0 place-items-center rounded-full transition hover:bg-white/10"
          title="刷新"
          @click="reloadSrc()"
        >
          <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 12a9 9 0 1 1-3-6.7" />
            <path d="M21 4v5h-5" />
          </svg>
        </button>

        <!-- 全屏放在「更多」左侧：底部右侧的锚是更多，全屏是最常用的高频项，紧挨着它 -->
        <button
          class="hit-44 grid h-9 w-9 shrink-0 place-items-center rounded-full transition hover:bg-white/10 tv-hide"
          :title="isFsUi ? '退出全屏' : '全屏'"
          :aria-label="isFsUi ? '退出全屏' : '全屏'"
          @click="toggleFullscreen"
        >
          <svg v-if="!isFsUi" class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3" />
          </svg>
          <svg v-else class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M8 3v3a2 2 0 0 1-2 2H3m18 0h-3a2 2 0 0 1-2-2V3m0 18v-3a2 2 0 0 1 2-2h3M3 16h3a2 2 0 0 1 2 2v3" />
          </svg>
        </button>

        <!-- 更多：低频项收进二级菜单 -->
        <div class="relative shrink-0">
          <button
            class="grid h-9 w-9 place-items-center rounded-full transition hover:bg-white/10"
            :class="showMore ? 'bg-white/15' : ''"
            title="更多"
            @click.stop="toggleMore($event)"
          >
            <svg class="h-5 w-5" viewBox="0 0 24 24" fill="currentColor">
              <circle cx="5" cy="12" r="1.8" />
              <circle cx="12" cy="12" r="1.8" />
              <circle cx="19" cy="12" r="1.8" />
            </svg>
          </button>
        </div>
      </div>
    </div>

    <!-- 快捷键说明。layout 用两列：左键位、右说明，扫一眼就能找到 -->
    <div
      v-if="showHelp"
      data-panel="help"
      class="absolute bottom-16 z-40 max-h-[calc(100%-5rem)] w-[20rem] overflow-y-auto rounded-xl border border-white/10 bg-black/92 p-3 text-ink shadow-2xl"
      :style="panelStyle('help')"
      data-tv-scope
      @click.stop
    >
      <div class="mb-2 flex items-center gap-2">
        <span class="flex-1 text-sm font-medium">快捷键</span>
        <button class="rounded px-2 py-0.5 text-[11px] text-ink-3 hover:bg-white/10" @click="openPanel(null)">
          关闭
        </button>
      </div>
      <dl class="grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-1.5 text-xs">
        <dt class="kbd">空格 / K</dt><dd>播放 / 暂停</dd>
        <dt class="kbd">← →</dt><dd>快退 / 快进 5 秒，连按 10 / 15 / 20 秒（按住 = 2 倍速扫动）</dd>
        <dt class="kbd">↑ ↓</dt><dd>电脑：音量 ±10%；电视：↑ 静音切换、↓ 唤出控制栏</dd>
        <dt class="kbd">F</dt><dd>全屏 / 退出全屏</dd>
        <dt class="kbd">M</dt><dd>静音切换</dd>
        <dt class="kbd">? 或 /</dt><dd>显示本说明</dd>
        <dt class="kbd">D</dt><dd>播放信息（码率 / 缓冲 / 线路）</dd>
        <dt class="kbd">Esc</dt><dd>退出全屏 → 关面板</dd>
        <dt class="kbd">双击画面</dt><dd>全屏（触屏上双击中间区域）</dd>
        <dt class="kbd">双击左/右半</dt><dd>快退 / 快进 10 秒（触屏）</dd>
        <dt class="kbd">长按画面</dt><dd>2 倍速（触屏）</dd>
        <dt class="kbd">左右竖滑</dt><dd>左三分之一调亮度、右三分之一调音量（触屏）</dd>
      </dl>
      <p class="mt-2.5 text-[11px] leading-relaxed text-ink-3">
        电视遥控器：确认键播放 / 暂停，上键静音，下键唤出控制栏，左右键快退 / 快进，
        返回键先关面板、再收控制栏、最后退出本集。音量用遥控器自带的音量键（走系统）。
      </p>
    </div>

    <!--
      二级菜单：重播 / 刷新 / 弹幕搜索 / 弹幕设置 / 字幕 / 画中画

      ★ 播放器里的浮层一律**不挂 backdrop-blur-xl**（2026-11-24）：底色已经是
        bg-black/90~92，模糊本身看不出差别；但老内核（投影仪 Chromium 66）的
        legacy 层会把 `.backdrop-blur*` 的背景改写成实色 --color-elevated，
        结果弹层从"近黑"变成"深蓝"，与现代内核明显不同色（用户实报）。
        同理，控制栏里的按钮也都不挂 backdrop-blur。
    -->
    <div
      v-if="showMore"
      data-panel="more"
      class="absolute bottom-16 z-40 max-h-[calc(100%-5rem)] w-44 overflow-y-auto rounded-xl border border-white/10 bg-black/90 p-1 text-ink shadow-2xl"
      :style="panelStyle('more')"
      data-tv-scope
      @click.stop
    >
      <!--
        分组顺序（2026-11-04 用户要求）：播放控制 → 播放选项 → 下载/投屏 → 工具。
        窄屏专属项（选集/线路/片头片尾/搜索弹幕）包了一层 div：
        `.menu-item` 的 `display:flex` 是无层样式，会盖掉 Tailwind 的 `hidden`，
        直接写在按钮上等于没写（实测 TV 上底栏已有的项全跑到这里重复一遍）。
      -->
      <div class="md:hidden">
        <!-- 窄屏底栏放不下选集/线路，在这里补入口（sm 以上走底栏按钮） -->
        <!-- 上/下一集：底栏那对按钮是 md:flex（<768 隐藏），手机没有直接切集，
             只能开选集网格。这里补两个直连按钮（2026-11-12 指南核验发现） -->
        <button
          v-if="canSwitch"
          class="menu-item"
          :disabled="!hasPrev"
          @click="goPrev"
        >
          <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="currentColor">
            <path d="M7 5h2v14H7zM19 5v14L9 12z" />
          </svg>
          上一集
        </button>
        <button
          v-if="canSwitch"
          class="menu-item"
          :disabled="!hasNext"
          @click="goNext"
        >
          <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="currentColor">
            <path d="M15 5h2v14h-2zM5 5v14l10-7z" />
          </svg>
          下一集
        </button>
        <button
          v-if="canSwitch"
          class="menu-item"
          @click="toggleEpPanel($event)"
        >
          <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="4" width="7" height="7" rx="1.5" />
            <rect x="14" y="4" width="7" height="7" rx="1.5" />
            <rect x="3" y="13" width="7" height="7" rx="1.5" />
            <rect x="14" y="13" width="7" height="7" rx="1.5" />
          </svg>
          选集
        </button>
      </div>
      <div class="sm:hidden">
        <button
          v-if="canPickLine"
          class="menu-item"
          @click="toggleLinePanel($event)"
        >
          <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M4 7h11a3 3 0 0 1 0 6H8a3 3 0 0 0 0 6h12" />
            <path d="m17 4 3 3-3 3" />
          </svg>
          线路：{{ lineName || '默认' }}
        </button>
      </div>
      <div class="lg:hidden">
        <button
          v-if="site && vodId"
          class="menu-item"
          :class="marks.intro ? 'text-accent' : ''"
          @click="toggleIntroMark(); openPanel(null)"
        >
          <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M5 4v16M5 5h11l-1.5 3L16 11H5" />
          </svg>
          {{ marks.intro ? `片头：${fmtClock(marks.intro)}（点此取消）` : '片头标记' }}
        </button>
      </div>
      <div class="lg:hidden">
        <button
          v-if="site && vodId"
          class="menu-item"
          :class="marks.outro ? 'text-accent' : ''"
          @click="toggleOutroMark(); openPanel(null)"
        >
          <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M19 4v16M19 5H8l1.5 3L8 11h11" />
          </svg>
          {{ marks.outro ? `片尾：${fmtClock(duration - marks.outro)}（点此取消）` : '片尾标记' }}
        </button>
      </div>
      <button
        v-if="hasPip"
        class="menu-item"
        :class="pipOn ? 'text-player' : ''"
        @click="togglePip(); openPanel(null)"
      >
        <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="2" y="4" width="20" height="16" rx="2" />
          <rect x="12" y="11" width="8" height="7" rx="1" fill="currentColor" stroke="none" />
        </svg>
        画中画
      </button>
      <button
        v-if="textTracks.length"
        class="menu-item"
        :class="activeTrack >= 0 ? 'text-player' : ''"
        @click="cycleTrack"
      >
        <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="3" y="5" width="18" height="14" rx="2" />
          <path d="M7 15h4M13 15h4" />
        </svg>
        字幕{{ activeTrack >= 0 ? '：' + (textTracks.find((t) => t.index === activeTrack)?.label || '开') : '：关' }}
      </button>
      <!--
        重播 / 刷新：只在底栏被 touch-hide 收走的环境（触屏小屏）显示。
        TV/PC 底栏这两项常驻，菜单里再放一遍会重复（见 .menu-replay 的
        媒体查询：与 .touch-hide 正好互补）。
      -->
      <button class="menu-item menu-replay" @click="restart()">
        <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M3 12a9 9 0 1 0 3-6.7" />
          <path d="M3 4v5h5" />
        </svg>
        重播
      </button>
      <button class="menu-item menu-replay" @click="reloadSrc()">
        <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M21 12a9 9 0 1 1-3-6.7" />
          <path d="M21 4v5h-5" />
        </svg>
        刷新
      </button>
      <!--
        弹幕设置：移动端横屏走底栏（搜索图标右侧）；竖屏 <sm 底栏放不下，
        保留在菜单里（同一入口两处互补，和「搜索弹幕」的 `sm:hidden` 同款）。
        PC/TV 始终走菜单。
      -->
      <!--
        ★ @click.stop 是必须的（2026-11-27 修）：这个按钮在「更多」面板**里面**，
        点它会打开弹幕设置面板；事件继续冒泡到 wrap → onWrapClick() 看到
        showDmPanel 已经是 true → openPanel(null) → 面板开了又被同一次点击关掉，
        表现为"点了没反应"。
      -->
      <button class="menu-item" :class="isTouch ? 'sm:hidden' : ''" @click.stop="toggleDmPanel($event)">
        <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M4 6h16M4 12h10M4 18h7" />
        </svg>
        弹幕设置
      </button>
      <div class="sm:hidden">
        <!--
          窄屏底栏容不下音量/弹幕搜索/片头片尾，统一收到这里。
          只在 sm 以下出现：桌面上这些按钮本来就在底栏，重复一份会让人以为是两个开关。
        -->
        <button class="menu-item sm:hidden" @click="openDmSearch()">
          <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
          </svg>
          搜索弹幕
        </button>
      </div>
      <!-- 播放信息：排障用，开着时中央显示码率/缓冲/丢帧/弹幕等 -->
      <button
        class="menu-item"
        :class="debugOn ? 'text-player' : ''"
        @click="toggleDebug(); openPanel(null)"
      >
        <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="9" />
          <path d="M12 8h.01M11 12h1v5h1" />
        </svg>
        播放信息{{ debugOn ? '（开）' : '' }}
      </button>
      <div class="my-1 h-px bg-white/10" />
      <!-- 下载到本机：下完断网也能看这一集。状态由播放页持有，这里只负责触发 -->
      <button
        v-if="canDownload"
        class="menu-item"
        :class="cacheState === 'done' ? 'text-ok' : ''"
        :disabled="cacheState === 'running'"
        @click="$emit('download'); openPanel(null)"
      >
        <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M12 3v12" />
          <path d="m7 11 5 5 5-5" />
          <path d="M4 20h16" />
        </svg>
        <template v-if="cacheState === 'running'">缓存中 {{ cachePercent }}%</template>
        <template v-else-if="cacheState === 'done'">已可离线观看</template>
        <template v-else-if="cacheState === 'failed'">缓存失败（重试）</template>
        <template v-else>下载到本机</template>
      </button>
      <!-- 整季下载：一集一集点太累，整季 12 集就是 12 次 -->
      <button
        v-if="canDownloadAll"
        class="menu-item"
        @click="$emit('download-all'); openPanel(null)"
      >
        <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z" />
        </svg>
        下载整季
      </button>
      <div class="my-1 h-px bg-white/10" />
      <!-- 定时电源动作：关机 / 休眠 / 退出应用 / 仅暂停 -->
      <button class="menu-item" @click.stop="toggleSleepMenu">
        <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
        </svg>
        {{ sleepAt ? `定时${POWER_ACTION_LABEL[powerAction]}·剩 ${sleepLeft} 分` : '定时电源动作' }}
      </button>
      <div v-if="sleepMenu" class="mb-1 rounded-md bg-white/5 p-1" @click.stop>
        <div class="px-2 py-1 text-[10px] text-ink-3">到点做什么</div>
        <button
          v-for="a in powerOptions"
          :key="a"
          class="block w-full rounded px-2 py-1 text-left text-[11px] transition hover:bg-white/10"
          :class="a === powerAction ? 'text-accent' : 'text-ink-2'"
          @click="powerAction = a"
        >
          {{ POWER_ACTION_LABEL[a] }}{{ a === 'shutdown' ? '（需要 root）' : '' }}
        </button>
        <div class="my-1 h-px bg-white/10" />
        <!-- 分钟选项：展开后自动滚进可视区（见 toggleSleepMenu） -->
        <div ref="sleepMinEl">
          <button
            v-for="m in POWER_TIMER_MINUTES"
            :key="m"
            class="block w-full rounded px-2 py-1 text-left text-[11px] transition hover:bg-white/10"
            :class="(m === 0 && !sleepAt) || (m > 0 && sleepLeft === m) ? 'text-accent' : 'text-ink-2'"
            @click="setPower(powerAction, m); sleepMenu = false"
          >
            {{ m === 0 ? '关闭定时' : `${m} 分钟后${POWER_ACTION_LABEL[powerAction]}` }}
          </button>
        </div>
      </div>

      <!-- 快捷键说明：十几条快捷键此前没有任何入口 -->
      <button class="menu-item" @click="toggleHelp($event)">
        <svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="9" />
          <path d="M9.5 9a2.5 2.5 0 1 1 3.5 2.3c-.7.4-1 .9-1 1.7" />
          <path d="M12 16.5h.01" />
        </svg>
        快捷键说明
      </button>
    </div>

    <!--
      跳过片头 / 片尾提示。
      倒计时（设置页可配，默认 5s）：环形进度 + 秒数，到点自动跳；
      用户可点「取消」放弃本次，或点主体立刻跳。
      skipCountdown = 0 时不显示环形与秒数，点了直接跳（旧行为）。
    -->
    <div
      v-if="inIntro && !introDismissed"
      data-skip-bar="intro"
      class="absolute bottom-24 right-4 z-30 flex items-center gap-1 rounded-md bg-black/70 py-1 pl-2.5 pr-1 text-xs text-ink backdrop-blur sm:right-5"
    >
      <button
        class="flex items-center gap-2 py-0.5 transition hover:text-accent"
        :title="introCd > 0 ? `${introCd} 秒后自动跳过，点此立即跳` : '立即跳过片头'"
        @click.stop="doSkipIntro"
      >
        <!-- 环形倒计时：剩余时间顺时针收完 -->
        <svg
          v-if="introCd > 0"
          class="h-3.5 w-3.5 shrink-0 -rotate-90"
          viewBox="0 0 24 24"
          fill="none"
          stroke-width="3"
        >
          <circle cx="12" cy="12" r="9" class="stroke-white/20" />
          <circle
            cx="12"
            cy="12"
            r="9"
            class="stroke-accent transition-[stroke-dashoffset] duration-1000 ease-linear"
            :stroke-dasharray="2 * Math.PI * 9"
            :stroke-dashoffset="2 * Math.PI * 9 * (1 - cdRatio(introCd))"
          />
        </svg>
        <span class="tabular-nums">
          跳过片头 → {{ fmtClock(skipIntro) }}
          <span v-if="introCd > 0" class="text-accent">· {{ introCd }}s</span>
        </span>
      </button>
      <button
        v-if="introCd > 0"
        data-skip-cancel="intro"
        class="rounded px-1.5 py-0.5 text-[11px] text-ink-3 transition hover:bg-white/10 hover:text-ink"
        title="取消本次跳过"
        @click.stop="dismissSkip('intro')"
      >
        取消
      </button>
    </div>

    <div
      v-if="inOutro && playing && !outroDismissed"
      data-skip-bar="outro"
      class="absolute bottom-24 right-4 z-30 flex items-center gap-1 rounded-md bg-black/70 py-1 pl-2.5 pr-1 text-xs text-ink backdrop-blur sm:right-5"
    >
      <button
        class="flex items-center gap-2 py-0.5 transition hover:text-accent"
        :title="outroCd > 0 ? `${outroCd} 秒后自动跳过，点此立即跳` : '立即跳过片尾'"
        @click.stop="doSkipOutro"
      >
        <svg
          v-if="outroCd > 0"
          class="h-3.5 w-3.5 shrink-0 -rotate-90"
          viewBox="0 0 24 24"
          fill="none"
          stroke-width="3"
        >
          <circle cx="12" cy="12" r="9" class="stroke-white/20" />
          <circle
            cx="12"
            cy="12"
            r="9"
            class="stroke-accent transition-[stroke-dashoffset] duration-1000 ease-linear"
            :stroke-dasharray="2 * Math.PI * 9"
            :stroke-dashoffset="2 * Math.PI * 9 * (1 - cdRatio(outroCd))"
          />
        </svg>
        <span class="tabular-nums">
          跳过片尾 → {{ fmtClock(outroAt) }}
          <span v-if="outroCd > 0" class="text-accent">· {{ outroCd }}s</span>
        </span>
      </button>
      <button
        v-if="outroCd > 0"
        data-skip-cancel="outro"
        class="rounded px-1.5 py-0.5 text-[11px] text-ink-3 transition hover:bg-white/10 hover:text-ink"
        title="取消本次跳过"
        @click.stop="dismissSkip('outro')"
      >
        取消
      </button>
    </div>

    <!-- 标记结果提示 -->
    <div
      v-if="marksTip"
      class="pointer-events-none absolute left-1/2 top-6 z-40 -translate-x-1/2 rounded-md bg-black/80 px-3 py-1.5 text-xs text-ink backdrop-blur"
    >
      {{ marksTip }}
    </div>

    <!--
      线路面板：与选集面板同一位置、同一宽度，左下角对齐。
      这两个按钮都在底栏左半侧，面板也从左下弹出，视线不用横跨整屏。
    -->
    <div
      v-if="showLinePanel"
      data-panel="line"
      class="absolute bottom-20 z-40 flex max-h-[calc(100%-6rem)] w-[22rem] max-w-[calc(100%-1rem)] flex-col rounded-xl border border-white/10 bg-black/90 p-3 text-ink"
      :style="panelStyle('line')"
      data-tv-scope
      @click.stop
    >
      <div class="mb-2.5 flex items-center justify-between">
        <span class="text-sm font-medium">切换线路</span>
        <span class="text-[11px] text-ink-3">共 {{ lineList.length }} 条</span>
        <button class="text-ink-3 hover:text-ink" @click="showLinePanel = false">✕</button>
      </div>
      <div class="min-h-0 flex-1 space-y-1 overflow-y-auto">
        <button
          v-for="l in lineList"
          :key="l.name"
          class="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-[13px] transition"
          :class="
            l.name === lineName
              ? 'bg-player/25 font-medium text-player'
              : 'bg-white/8 text-ink-2 hover:bg-white/16 hover:text-ink'
          "
          :title="l.name"
          @click="pickLine(l.name)"
        >
          <span class="min-w-0 flex-1 truncate">{{ l.name }}</span>
          <span class="shrink-0 text-[10px] text-ink-3">{{ l.episodes.length }} 集</span>
        </button>
      </div>
      <!--
        ★ 这段文案原来写的是「换线路会从该线路第 1 集开始，当前集的播放进度
          不保留」——**与代码行为相反**（2026-11-02 用户指出的）。

          实际行为：切线路走 `sameEpisodeIn()`，按**集名精确匹配**停在同一集，
          进度由 `flushToHistory` 落盘、新页面 `applyResume()` 续播。
          switchLine 里的注释甚至记录了「不能 clearProgress」这个踩过的坑。

          真正会退回第 1 集的情况只有两种：① 各线路命名规则不同
          （`第01集` / `01` / `EP01`）导致集名匹配失败；② 新线路集数更少。
          文案改成如实描述这两点。
      -->
      <p class="mt-2 text-[11px] leading-relaxed text-ink-3">
        换线路会尽量停在同一集（按集名匹配）并保留播放进度；
        若该线路集名不同或集数不足，会从第 1 集开始。
      </p>
    </div>

    <!-- 选集面板：与线路面板同位同宽，左下角弹出 -->
    <div
      v-if="showEpPanel"
      data-panel="ep"
      class="absolute bottom-20 z-40 flex max-h-[calc(100%-6rem)] w-[22rem] max-w-[calc(100%-1rem)] flex-col rounded-xl border border-white/10 bg-black/90 p-3 text-ink"
      :style="panelStyle('ep')"
      data-tv-scope
      @click.stop
    >
      <div class="mb-2.5 flex items-center justify-between">
        <span class="text-sm font-medium">选集</span>
        <span class="text-[11px] text-ink-3">
          共 {{ episodes?.length ?? 0 }} 集 · 当前第 {{ (currentIndex ?? 0) + 1 }}
        </span>
        <button class="hit-44 grid h-9 w-9 place-items-center text-ink-3 hover:text-ink" @click="showEpPanel = false">✕</button>
      </div>
      <div
        class="grid min-h-0 flex-1 auto-rows-min grid-cols-5 gap-1.5 overflow-y-auto"
      >
        <button
          v-for="(ep, i) in episodes"
          :key="i"
          class="relative min-h-11 truncate rounded-md px-1 text-[12px] transition"
          :class="epCellClass(i)"
          :aria-current="i === currentIndex ? 'true' : undefined"
          :title="isNewEpIdx(i) ? `${ep.name} · 自上次看新增` : ep.name"
          @click="gotoEp(i)"
        >
          {{ ep.name }}
          <!--
            新集红点：对齐主题色（--color-accent 走 token，换肤跟着变）。
            不用 bg-hot —— hot 是写死的告警红，"有新集"不是错误，
            且换肤后 hot 不跟随（docs/27 §5.1 焦点环同款教训）。
          -->
          <span
            v-if="isNewEpIdx(i) && i !== currentIndex"
            class="absolute right-0.5 top-0.5 h-1.5 w-1.5 rounded-full bg-accent"
          />
        </button>
      </div>
    </div>

    <!--
      弹幕设置面板：从右下弹出。
      触发它的按钮（弹幕开关 / 更多）都在底栏右半侧，所以这里跟右侧对齐；
      选集和线路的按钮在左半侧，面板走左下（见上两处）。
    -->
    <div
      v-if="showDmPanel"
      data-panel="dm"
      class="absolute bottom-20 z-40 max-h-[calc(100%-6rem)] w-[19rem] max-w-[calc(100%-1rem)] overflow-y-auto rounded-xl border border-white/10 bg-black/90 p-3.5 text-ink"
      :style="panelStyle('dm')"
      data-tv-scope
      @click.stop
    >
      <div class="mb-3 flex items-center justify-between">
        <span class="text-sm font-medium">弹幕设置</span>
        <button class="text-ink-3 hover:text-ink" @click="showDmPanel = false">✕</button>
      </div>

      <label class="mb-2.5 flex items-center justify-between text-xs text-ink-2">
        <span>显示弹幕</span>
        <button
          class="h-5 w-9 rounded-full transition"
          :class="dmOn ? 'bg-player' : 'bg-white/20'"
          @click="toggleDanmaku(); patchDmOptions({ danmaku: dmOn })"
        >
          <span
            class="block h-4 w-4 rounded-full bg-white transition"
            :class="dmOn ? 'translate-x-4' : 'translate-x-0.5'"
          />
        </button>
      </label>

      <!--
        弹幕密度：档位是「时长 + 区域」的预设组合。
        两者耦合，单看某个数看不出结果，打包成三档才好选。
        面板很窄，用胶囊按钮比下拉更快。
      -->
      <div class="mb-2.5">
        <div class="mb-1 flex items-center justify-between text-xs text-ink-2">
          <span>弹幕密度</span>
          <span v-if="prefs.danmakuDensity === 'custom'" class="text-[10px] text-ink-3">
            自定义
          </span>
        </div>
        <div class="grid grid-cols-3 gap-1">
          <button
            v-for="d in densityButtons"
            :key="d.value"
            class="rounded-md py-1.5 text-[11px] transition"
            :class="
              prefs.danmakuDensity === d.value
                ? 'bg-player/25 font-medium text-player'
                : 'bg-white/8 text-ink-2 hover:bg-white/14'
            "
            :title="d.hint"
            @click="pickDensity(d.value)"
          >
            {{ d.label }}
          </button>
        </div>
      </div>

      <div class="mb-2.5">
        <div class="mb-1 text-xs text-ink-2">时间轴密度条</div>
        <div class="grid grid-cols-3 gap-1">
          <button
            v-for="h in heatButtons"
            :key="h.value"
            class="rounded-md py-1.5 text-[11px] transition"
            :class="
              prefs.danmakuHeat === h.value
                ? 'bg-player/25 font-medium text-player'
                : 'bg-white/8 text-ink-2 hover:bg-white/14'
            "
            :title="h.hint"
            @click="patchDmOptions({ danmakuHeat: h.value })"
          >
            {{ h.label }}
          </button>
        </div>
      </div>

      <label class="mb-2.5 block text-xs text-ink-2">
        <span class="mb-1 flex justify-between">
          <span>透明度</span>
          <span class="text-ink-3">{{ Math.round(dmOpts.opacity * 100) }}%</span>
        </span>
        <input
          type="range"
          min="20"
          max="100"
          step="5"
          class="w-full"
          :value="dmOpts.opacity * 100"
          @input="patchDmOptions({ danmakuOpacity: Number(($event.target as HTMLInputElement).value) / 100 })"
        />
      </label>

      <label class="mb-2.5 block text-xs text-ink-2">
        <span class="mb-1 flex justify-between">
          <span>字号</span>
          <span class="text-ink-3">{{ Math.round(dmOpts.scale * 100) }}%</span>
        </span>
        <input
          type="range"
          min="70"
          max="160"
          step="5"
          class="w-full"
          :value="dmOpts.scale * 100"
          @input="patchDmOptions({ danmakuScale: Number(($event.target as HTMLInputElement).value) / 100 })"
        />
      </label>

      <label class="mb-2.5 block text-xs text-ink-2">
        <span class="mb-1 flex justify-between">
          <span>滚动时长</span>
          <span class="text-ink-3">{{ dmOpts.duration }}s</span>
        </span>
        <input
          type="range"
          min="6"
          max="20"
          step="1"
          class="w-full"
          :value="dmOpts.duration"
          @input="tuneDm({ duration: Number(($event.target as HTMLInputElement).value) })"
        />
      </label>

      <label class="block text-xs text-ink-2">
        <span class="mb-1 flex justify-between">
          <span>显示区域</span>
          <span class="text-ink-3">{{ Math.round(dmOpts.area * 100) }}%</span>
        </span>
        <input
          type="range"
          min="30"
          max="100"
          step="5"
          class="w-full"
          :value="dmOpts.area * 100"
          @input="tuneDm({ area: Number(($event.target as HTMLInputElement).value) / 100 })"
        />
      </label>

      <!--
        时间校准：±30 秒、0.5 秒一档，按「片名 · 集」持久化。
        弹幕源和播放源版本不同（片头/回顾/片尾）时，整段会偏；这里让用户
        自己把时间轴推回去，而不是换源碰运气。
      -->
      <div class="mt-2.5 mb-2.5">
        <div class="mb-1 flex items-center justify-between text-xs text-ink-2">
          <span>时间校准</span>
          <span class="text-ink-3">{{ dmOffsetLabel }}</span>
        </div>
        <div class="grid grid-cols-3 gap-1">
          <button
            class="rounded-md bg-white/8 py-1.5 text-[11px] text-ink-2 transition hover:bg-white/14"
            title="弹幕偏晚：提前 0.5 秒"
            @click="bumpDmOffset(-0.5)"
          >
            −0.5s
          </button>
          <button
            class="rounded-md bg-white/8 py-1.5 text-[11px] text-ink-2 transition hover:bg-white/14"
            title="恢复 0 偏移"
            @click="clearDmOffset()"
          >
            归零
          </button>
          <button
            class="rounded-md bg-white/8 py-1.5 text-[11px] text-ink-2 transition hover:bg-white/14"
            title="弹幕偏早：延后 0.5 秒"
            @click="bumpDmOffset(0.5)"
          >
            +0.5s
          </button>
        </div>
        <p class="mt-1 text-[10px] leading-snug text-ink-3">
          弹幕偏早点 +（延后），偏晚点 −（提前）；按片/集记住
        </p>
      </div>

      <div class="mt-3 flex items-center justify-between border-t border-white/8 pt-2.5">
        <span class="text-[11px] text-ink-3">
          <template v-if="dmOverrideUrl">已手动指定弹幕源 · {{ dmCount }} 条</template>
          <template v-else-if="dmEmpty && !dmLoading">当前 0 条（来源暂不可用）</template>
          <template v-else>
            当前 {{ dmCount }}<template v-if="dmTotal > dmCount"> / {{ dmTotal }}{{ dmTruncated ? '+' : '' }}</template> 条<template v-if="dmSourceLabel"> · {{ dmSourceLabel }}</template>
          </template>
        </span>
        <button
          v-if="dmOverrideUrl"
          class="text-[11px] text-accent hover:underline"
          @click="clearDmOverride"
        >
          恢复自动
        </button>
      </div>
      <!-- 弹幕诊断（2026-11-09）：来源 / 本地缓存 / 后台补全状态，排障不用猜 -->
      <div
        v-if="dmCount || dmLocalCount || dmPendingUpgrade || dmUpgradeApplied"
        class="mt-1.5 text-[10px] leading-relaxed text-ink-3/70"
      >
        来源 {{ dmSourceLabel || dmSource || '未知' }} · {{ dmCount }}<template v-if="dmTotal > dmCount"> / {{ dmTotal }}{{ dmTruncated ? '+' : '' }}</template> 条
        <template v-if="dmLocalCount"> · 本地缓存 {{ dmLocalCount }}</template>
        <template v-if="dmPendingUpgrade">
          · 更全的 {{ dmPendingUpgrade?.count }} 条待应用（seek 后生效）
        </template>
        <template v-else-if="dmUpgradeApplied"> · 已应用更全来源</template>
      </div>
    </div>

    <!-- 弹幕搜索弹框 -->
    <div
      v-if="showDmSearch"
      class="absolute inset-0 z-50 grid place-items-center bg-black/45 px-4 py-4 sm:px-6"
      data-tv-scope
      @click.self="closeDmSearch"
    >
      <!--
        ★ 弹框必须自己限高 + 内部滚动（2026-11-02，用户报「手动搜索弹幕弹框
          滚动面板有误，方向键上下按无法显示完全内容，出界了」）。

        原来这里只有 `p-4`，没有任何高度约束：标题 + 输入框 + 来源胶囊（可能
        换行两三行）+ 列表（max-h-42vh）+ 底部说明叠起来，在 TV 的 640px
        CSS 视口里必然超出屏幕 —— 底部说明和列表末尾被切掉，而且**整块不能滚**。

        现在：max-h 用视口百分比（不写死 px，TV 视口比 PC 小得多），
        flex 纵向 + 列表 `min-h-0 flex-1` 才能在父级限高时正确收缩并内部滚动。

        ★ 2026-11-09 用户报「弹框覆盖全屏，看不到后面的视频」：把遮罩从
          bg-black/70 + backdrop-blur 降到 bg-black/45（去掉模糊），面板从
          max-w-lg/86vh 收到 max-w-md/72vh。遮罩仍能聚焦，但视频可见。
      -->
      <div
        class="flex max-h-[72vh] w-full max-w-md flex-col rounded-xl border border-white/10 bg-elevated p-3.5 meow-light-card"
        @click.stop
      >
        <div class="mb-3 flex shrink-0 items-center justify-between">
          <h3 class="text-sm font-medium">搜索弹幕</h3>
          <button class="text-ink-3 hover:text-ink" @click="closeDmSearch">✕</button>
        </div>

        <form class="flex shrink-0 gap-2" @submit.prevent="runDmSearch">
          <!--
            ★ data-tv-first：打开弹框就把焦点放到搜索框（用户要求）。

            tv-nav 的落点优先级是 `[data-tv-first]` > 当前值 > 第一个 input >
            第一个可聚焦元素。原来列表上也标了 data-tv-first，两个会打架
            （querySelector 取 DOM 顺序第一个，结果取决于模板顺序，很脆）。
            现在只保留这一个：用户打开「搜索弹幕」的第一意图就是输入片名。

            球标在输入框上还有一个好处：tv-nav 对文本输入框的 OK 会弹
            **原生输入框**（遥控器打字的唯一方式），少按一次下键才能开始搜。
          -->
          <input
            ref="dmInputEl"
            v-model="dmKeyword"
            placeholder="片名，例如 庆余年"
            class="min-w-0 flex-1 rounded-md border border-white/10 bg-black/40 px-3 py-1.5 text-sm outline-none focus:border-accent/50"
          />
          <button
            ref="dmSubmitEl"
            type="submit"
            data-tv-first
            class="shrink-0 rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-on-accent disabled:opacity-50"
          >
            {{ dmSearching ? '搜索中…' : '搜索' }}
          </button>
        </form>

        <p v-if="dmSearchError" class="mt-2 text-[11px] text-warn">{{ dmSearchError }}</p>

        <!-- 来源筛选胶囊：固定 6 个 + 额外抓到的；直连=青色，danmu_api=紫色 -->
        <div v-if="dmSources.length" class="mt-2.5 shrink-0 flex flex-wrap gap-1.5">
          <button
            class="rounded-full px-2.5 py-1 text-[11px] transition"
            :class="
              dmSourceFilter === null
                ? 'border border-transparent bg-accent font-medium text-on-accent'
                : 'border border-transparent bg-white/8 text-ink-2 hover:bg-white/14'
            "
            @click="dmSourceFilter = null"
          >
            全部 {{ dmResults.length }}
          </button>
          <template v-for="s in dmSources" :key="s.label">
            <!--
              胶囊选中态统一用「实心 + 描边 + 左上角勾」三重信号（2026-10-31 修）：
              原来选中只是换个底色（accent → 更亮的 accent，violet → 更亮的 violet），
              深色背景上几乎看不出区别，用户报"非直连胶囊选中样式有误"。
              现在未选中是低透明度底 + 细边框，选中是实心 + 2px 高亮描边 + 勾号。
            -->
            <!-- API 直连：青色 -->
            <button
              v-if="s.direct > 0"
              class="relative rounded-full px-2.5 py-1 text-[11px] transition"
              :class="
                dmSourceFilter?.label === s.label && dmSourceFilter?.via === 'direct'
                  ? 'border border-transparent bg-accent font-semibold text-on-accent ring-2 ring-accent ring-offset-1 ring-offset-black/40'
                  : 'border border-accent/40 bg-accent/12 text-accent hover:bg-accent/20'
              "
              :title="`${s.label} · API 直连`"
              @click="dmSourceFilter = { label: s.label, via: 'direct' }"
            >
              <span
                v-if="dmSourceFilter?.label === s.label && dmSourceFilter?.via === 'direct'"
                class="mr-0.5"
              >✓</span>{{ s.label }} {{ s.direct }}
            </button>
            <!-- danmu_api：紫色 -->
            <button
              v-if="s.aggregator > 0"
              class="relative rounded-full px-2.5 py-1 text-[11px] transition"
              :class="
                dmSourceFilter?.label === s.label && dmSourceFilter?.via === 'aggregator'
                  ? 'border border-transparent bg-violet-400 font-semibold text-black ring-2 ring-violet-300 ring-offset-1 ring-offset-black/40'
                  : 'border border-violet-400/40 bg-violet-400/12 text-violet-300 hover:bg-violet-400/20'
              "
              :title="`${s.label} · danmu_api`"
              @click="dmSourceFilter = { label: s.label, via: 'aggregator' }"
            >
              <span
                v-if="dmSourceFilter?.label === s.label && dmSourceFilter?.via === 'aggregator'"
                class="mr-0.5"
              >✓</span>{{ s.label }} {{ s.aggregator }}
            </button>
            <!-- 固定胶囊但本次无数据：灰显 -->
            <span
              v-if="s.direct === 0 && s.aggregator === 0"
              class="rounded-full border border-transparent px-2.5 py-1 text-[11px] text-ink-3/50"
              :title="`${s.label} · 本次无结果`"
            >
              {{ s.label }} 0
            </span>
          </template>
        </div>

        <!--
          结果列表：唯一可滚区（父级已 flex 限高，这里 min-h-0 才能收缩）。
          ★ 不标 data-tv-scroll / tabindex（2026-11-04 审计）：
            列表里每个结果都是可聚焦的 <button>，容器再当停靠点会**抢焦点**——
            容器矩形包住所有结果，几何上 primary=0，上下键先落到容器
            （画一根左竖条），再按才进列表。滚动由 scrollIntoViewWithin
            跟着焦点走；列表为空时 tv-nav 的 autoScrollAnchor 会自动补停靠点。
          ★ px-2/-mx-2：给焦点框留出空间，否则被 overflow 裁掉左边。
        -->
        <div
          ref="dmListEl"
          class="mt-3 -mx-2 min-h-0 flex-1 overflow-y-auto px-2 pb-2"
        >
          <button
            v-for="(r, i) in dmFiltered"
            :key="i"
            :data-ep="r.episode || ''"
            class="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-[13px] text-ink-2 transition hover:bg-white/8 hover:text-ink"
            :title="r.name"
            @click="pickDm(r)"
          >
            <span
              class="shrink-0 rounded px-1 py-0.5 text-[9px] font-medium"
              :class="
                viaOf(r) === 'direct'
                  ? 'bg-accent/15 text-accent'
                  : 'bg-violet-400/15 text-violet-300'
              "
            >
              {{ viaOf(r) === 'direct' ? '直连' : 'danmu_api' }}
            </span>
            <!--
              标题：原来整条 `{{ r.name }}` 一把 truncate，而 danmu_api 返回的
              name 是「我不是大师(2026)【国产剧】from iqiyi...」这种长串，
              截断后**完全看不出是第几集**（用户截图里的问题）。
              现在拆两段：左侧先给集数徽标（能从 name 里解析出来就显示），
              右侧标题去掉季/年份/来源后缀后再截断。
            -->
            <span
              v-if="epBadgeOf(r)"
              class="shrink-0 rounded bg-white/10 px-1 py-0.5 font-mono text-[10px] text-ink-2"
            >
              {{ epBadgeOf(r) }}
            </span>
            <span class="min-w-0 flex-1 truncate">{{ cleanDmTitle(r.name) }}</span>
            <span class="shrink-0 text-[10px] text-ink-3">{{ hostLabel(r.host) }}</span>
          </button>
          <p
            v-if="!dmResults.length && !dmSearching && !dmSearchError"
            class="py-6 text-center text-xs text-ink-3"
          >
            输入片名后点搜索
          </p>
        </div>

        <p class="mt-3 shrink-0 text-[11px] leading-relaxed text-ink-3">
          自动抓不到弹幕时用这里手动指定。选中后会从当前播放进度继续显示。
        </p>
      </div>
    </div>

    <!--
      父级浮层插槽（2026-11-03，修「全屏下连播倒计时点不到」）。

      ★ 为什么必须放在播放器子树里：真全屏走 `requestFullscreen(playerRoot)`，
        浏览器只渲染全屏元素**及其后代** —— 原来倒计时和投屏面板挂在 PlayView
        下（播放器的兄弟节点），全屏时被整个藏掉，点不到也看不见；
        假全屏（TV）下播放器是 `z-[100]`，父级浮层 `z-40` 同样被压住。

      ★ 外层 `pointer-events-none` + 内层按钮各自 `pointer-events-auto`：
        插槽铺满整个画面，不能把点击/双击/滑动吞掉（会破坏暂停与手势）。
    -->
    <div class="pointer-events-none absolute inset-0 z-[130]">
      <slot name="overlay" />
    </div>
  </div>
</template>

<style scoped>
/*
 * 控制栏淡入淡出。
 *
 * ★ 为什么收起时必须是 `visibility:hidden`，不能只靠 `opacity:0`
 *   （2026-11-02，用户报「TV 偶尔按向下键唤不出控制栏」的真因）：
 *   opacity 只是"看不见"，元素仍有完整几何尺寸，tv-nav 的 items() 会把它
 *   里面的每个按钮当成焦点候选。一旦焦点框落进这排隐形按钮：
 *     · 再按方向键 → 壳认为"已有焦点"，直接吃掉按键去移焦点，
 *       播放器永远收不到 ArrowDown → 控制栏再也唤不出来
 *     · 按 ok → 点中的正是那个看不见的按钮（多半是播放/暂停）
 *   用户看到的就是"只能靠 ok/返回 引起的暂停把控制栏顶出来"。
 *   visibility 会继承，子孙元素的 computed style 也是 hidden，
 *   tv-nav 的 visible() 一眼就能排除。
 *
 * 延迟 0s/280ms：收起时先播完淡出动画再真正隐藏，展开时立刻可见。
 */
.meow-ctrl-bar {
  transition: opacity 280ms ease, visibility 0s linear 280ms;
}
.meow-ctrl-bar:not(.invisible) {
  transition: opacity 280ms ease, visibility 0s;
}

input[type='range']::-webkit-slider-thumb {
  appearance: none;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--color-player);
  cursor: pointer;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  border-radius: 0.5rem;
  padding: 0.5rem 0.625rem;
  text-align: left;
  font-size: 0.75rem;
  color: var(--color-ink-2);
  transition: background-color 0.15s ease, color 0.15s ease;
}
.menu-item:hover {
  background: rgb(255 255 255 / 0.1);
  color: var(--color-ink);
}

  .kbd {
    display: inline-block;
    min-width: 2.5rem;
    padding: 0.1rem 0.4rem;
    border-radius: 0.25rem;
    border: 1px solid rgb(255 255 255 / 0.18);
    background: rgb(255 255 255 / 0.06);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 0.68rem;
    text-align: center;
    white-space: nowrap;
  }
</style>
