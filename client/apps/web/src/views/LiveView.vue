<script lang="ts">
/*
 * ★ 真正的模块级状态必须放这里，不能放 <script setup>：
 *   setup 顶层变量是**每个组件实例一份**——退出直播页组件卸载，
 *   二次进入 setup 重新执行，cache 重新为 null，"会话缓存"永远命不中
 *   （实测 MuMu 76：首次看完写入，二次进入仍全屏转圈 12 秒）。
 *   普通 <script> 与 <script setup> 编译后同一模块作用域，这里的变量
 *   跨挂载存活。
 */
/** 会话缓存：二次进入直播页秒开（stale-while-revalidate，见 load()） */
export interface LiveSessionCache {
  groups: LiveGroup[]
  playlists: Array<{ name: string; url: string }>
  stats: { tried: number; failed: number }
}
let cache: LiveSessionCache | null = null

/** m3u 解析结果缓存（按 url）：切换/重进播放列表时不再重复拉 100KB。
 *  形状与 fetchLivePlaylist 的 groups 一致（组装成 LiveGroup 在 openPlaylist 里做） */
type ParsedGroups = Array<{ group: string; channels: LiveChannel[] }>
const parsedCache = new Map<string, { groups: ParsedGroups; at: number }>()
/** m3u 每天更新，10 分钟内直接复用解析结果 */
const PARSE_TTL = 10 * 60_000

/** 上次看的频道（跨会话，localStorage）：电视机开机还是上次那个台 */
const LAST_KEY = 'meow.live.last'

/** 首次起播是否已处理（自动进全屏只做一次） */
let firstPlayDone = false
</script>

<script setup lang="ts">
import { isBigScreenLandscape } from '@/compat/deviceForm'
/**
 * 直播页。
 *
 * 数据来自后端 `/api/live`（并发问所有站点要 `liveContent`，12 秒预算），
 * 所以这里是**一个聚合视图**，不是"逐站浏览"——用户打开就该看到频道，
 * 而不是先选源。
 *
 * 播放用独立的最小播放器（hls.js + video），不复用 `VideoPlayer`：
 * 直播没有进度/续播/片头尾/选集，套上那套只会多出一堆永不触发的分支。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useOverlayHost } from '@/composables/useOverlayHost'
import { useRouter } from 'vue-router'
import Hls from 'hls.js'
import { addLiveSource, fetchLive, fetchLivePlaylist, proxyStream, type LiveChannel, type LiveGroup } from '@/api'
import { isTvEnv } from '@/composables/useCardCols'
import {
  setIosNavGesture,
  setIosVolumeHud,
  setSystemBrightness,
  setSystemVolume,
  systemBrightness,
  systemVolume,
} from '@/api/nativeBridge'
import { getPrefs, savePrefs } from '@/stores/prefs'
import { isLiveBad, noteLiveFail, noteLiveOk, sortLiveByHealth } from '@/stores/liveHealth'
import { describeSources, mergeSources, sourceCountOf, type ParsedSource } from '@/api/liveMerge'

const groups = ref<LiveGroup[]>([])
/** config.lives 的直连播放列表；点开才解析，避免打开页面就拉十几条 m3u */
const playlists = ref<Array<{ name: string; url: string }>>([])
const playlistBusy = ref('')
/**
 * ★ 初始 false 而不是 true：有会话缓存时 load() 走秒开路径（数据立刻
 *   可用、直接起播），转圈只在"真没数据"的无缓存路径显示。
 *   原来初始 true + 秒开路径没有复位 —— 全屏转圈把已起播的 stage 盖住，
 *   直到后台聚合 12 秒返回才露出画面（实测 MuMu 76：二次进入白等 12 秒，
 *   缓存明明命中了）。
 */
const loading = ref(false)
/** 聚合已等待秒数：/api/live 要 10-12 秒才回，只给个转圈用户分不清"在干活"还是"卡死了" */
const loadingSeconds = ref(0)
let loadTimer: number | undefined
const stats = ref<{ tried: number; failed: number } | null>(null)
const activeKey = ref('')

/**
 * 页内添加直播源。
 *
 * 后端 `/api/lives` 已经能独立增删了，但用户走到直播页发现是空的，
 * 再让他去设置页找是绕路 —— 就在空态给一个输入框，加完直接打开。
 */
const addUrl = ref('')
const addBusy = ref(false)
const addMsg = ref('')

/** 本页安装的返回链，以及被它包住的上一任（卸载时判等还原） */
let ownConsumeBack: (() => boolean) | null = null
let prevConsumeBack: (() => boolean) | null = null

/** 当前选中的频道（含多线路） */
const current = ref<{
  name: string
  urls: Array<{ url: string; label: string }>
  /** 在 channels 里的下标。**不能用名字定位**：IPTV 列表里同一频道
   *  会有很多条同名（CCTV1 × 200），按名字 findIndex 永远命中第一条，
   *  换台就变成"换到自己"（实测踩过）。 */
  idx: number
} | null>(null)
const activeUrl = ref('')

const router = useRouter()

/**
 * 离开直播页。
 *
 * <p>空态/错误态唯一的退出入口（直播页是路由级满屏，顶栏按 isLive 隐藏，
 * 没有台在播时屏幕上没有任何可点的出口 —— 手机没有物理返回键就卡住了）。
 *
 * <p>有历史就退一步（保住从搜索/详情进来的上下文），深链直接进来的回首页。
 */
function leaveLive() {
  const before = router.currentRoute.value.fullPath
  if (window.history.length > 1) router.back()
  else void router.push('/')
  /*
   * Android WebView 的 history.back() 可能是空操作（点播页 2026-11-05 踩过：
   * popstate/hashchange 都不触发，hash 不变）。这里补同样的兜底：260ms 后
   * 路由没动就 replace 到首页。否则用户点返回"没反应"（面板还留在屏上）。
   */
  window.setTimeout(() => {
    if (router.currentRoute.value.fullPath !== before) return
    void router.replace('/')
  }, 260)
}

/**
 * ★ 方向键归属跟着"有没有台在播"走。
 *
 * <p>空态/错误态时页面自己不处理方向键（onKeydown 只做换台/切线路/面板），
 * 如果还霸着 `__meowTvKeyOwner`，tv-nav 就永远不接管 —— 遥控器用户在
 * 「还没有可用的直播源」这一屏上按方向键毫无反应，"添加 / 重试 / 返回"
 * 一个都选不中（只能看到按钮点不到）。
 *
 * <p>有台在播时才独占（换台的 ↑↓ 语义）。挂载即求值一次：current 初始
 * 为 null，所以冷启动进页面时方向键立刻归 tv-nav。
 */
watch(
  current,
  (c) => {
    ;(window as unknown as { __meowTvKeyOwner?: boolean }).__meowTvKeyOwner = !!c
  },
  { immediate: true },
)
const error = ref('')

const video = ref<HTMLVideoElement | null>(null)
const hls = shallowRef<Hls | null>(null)
/** 播放器容器（全屏对象是它，不是 video —— 控制栏要一起进全屏） */
const stage = ref<HTMLElement | null>(null)
/** 初值读一次 document：可能进这一页时全屏已经在（与 VideoPlayer 同一个坑） */
const fullscreen = ref(typeof document !== 'undefined' && !!document.fullscreenElement)
/** 真全屏 API 不可用时的兜底：容器撑满视口 */
const pseudoFs = ref(false)
/**
 * 横竖屏分形态（用户定调 2026-11-06）：
 *   横屏 TV（电视/投影）→ **不渲染底栏**。↑↓ 换台、←→ 切线路、OK 呼出
 *   「分类-台」面板就是全部操作——底栏只要存在，唤出后的 5 秒窗口内
 *   方向键会被 tv-nav 抢去做焦点导航，换台就失灵。
 *   竖屏手机（TV APK 竖屏形态）→ 正常显示底栏，靠触屏操作。
 *
 * ★ 判据用视口方向而不是 isTvEnv()：TV APK 也可能装在手机上（竖屏），
 *   模拟器横屏也该走横屏行为。旋转时跟随（resize 监听）。
 */
const landscape = ref(
  typeof window !== 'undefined' ? window.innerWidth > window.innerHeight : true,
)
function syncLandscape() {
  landscape.value = window.innerWidth > window.innerHeight
}
/** 触屏设备判定（与点播播放器 VideoPlayer 同款） */
const isTouch = ref(
  typeof window !== 'undefined' &&
    (window.matchMedia?.('(pointer: coarse)').matches ||
      'ontouchstart' in window),
)

// ---------------------------------------------------------------- 横竖屏锁

/**
 * 进直播页 / 进全屏时把界面转横屏，退出时还原。
 *
 * <p>**完整对齐点播播放器**（VideoPlayer 的 tryLandscape / syncShellOrientation）。
 * 直播是"进页面即全屏播放"，与点播同一个形态，旋转语义没理由不一样 ——
 * 用户在手机上点进直播，该看到横屏铺满，而不是竖屏里一条 16:9。
 *
 * <p>三条路径，缺一条就出症状：
 *   ① 进页面（触屏 + 竖屏）→ 转横屏，记 `pageLandscape`
 *   ② 进全屏（竖屏）→ 转横屏，记 `rotatedByFs`；退出全屏 → 还原竖屏
 *   ③ 离开直播页 → 只要转过就还原竖屏（否则整个应用留在横屏）
 *
 * <p>为什么不只靠 `screen.orientation.lock()`：WebView 里没有真全屏上下文，
 * `lock()` 必然抛错（点播那边实测过「竖屏提示点着没反应」），必须走壳的
 * `meowTv.setOrientation`。网页端没有这个桥 → 静默失败 → 靠竖屏提示引导
 * 用户手动旋转。
 */
function shellSetOrientation(mode: 'landscape' | 'portrait') {
  const b = (window as unknown as { meowTv?: { setOrientation?: (m: string) => void } }).meowTv
  if (b && typeof b.setOrientation === 'function') b.setOrientation(mode)
  /*
   * iOS 壳（2026-11-25 补）：原来只认 meowTv，iOS 上直播进页面/点全屏都转不了
   * 横屏（WKWebView 没有 orientation lock）。点播一直有这条桥，直播漏了。
   */
  const ios = (window as unknown as { meowIos?: { setOrientation?: (m: string) => void } }).meowIos
  if (ios && typeof ios.setOrientation === 'function') ios.setOrientation(mode)
}

/** 全屏自己转的横屏（退出全屏要还原） */
let rotatedByFs = false
/** 进页面时自动转的横屏（离开页面要还原） */
let pageLandscape = false

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
     * 踩过（点播那边 MuMu 实测）：进全屏时系统把 user_rotation 改成 1，
     * 退出时 unspecified 是"跟随系统"，系统以为自己该横屏 → 画面回不来。
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

async function tryLandscape() {
  if (!isTouch.value) return
  if (window.innerHeight > window.innerWidth) {
    pageLandscape = true
    shellSetOrientation('landscape')
  }
  await lockLandscapeQuietly()
}

/**
 * 竖屏提示上的「全屏观看」：一步到位（真全屏 + 锁横屏），与点播播放器同款。
 *
 * <p>原来这里只调 `tryLandscape()`，在支持元素全屏的 WebView（飞牛 App 的
 * flutter_inappwebview）里只转了方向、App 自绘的标题栏还在，用户点完仍觉得
 * "没全屏"（2026-11-26 MuMu 实测 `fullscreenEnabled === true`）。已在伪全屏时
 * 保留原来的转方向行为。
 */
async function enterFullscreenFromHint() {
  dismissPortraitHint()
  if (document.fullscreenElement || pseudoFs.value) {
    await tryLandscape()
    return
  }
  await toggleFullscreen()
}

/** 竖屏提示（横屏锁定失败时的兜底，与点播同文案） */
const portraitHint = ref(false)
let portraitHintDismissed = false

function checkOrientation() {
  if (!isTouch.value) return
  const portrait = window.innerHeight > window.innerWidth
  // 真全屏时浏览器自己处理方向；伪全屏拿不到方向锁，仍要提示
  portraitHint.value =
    portrait && !portraitHintDismissed && (!document.fullscreenElement || pseudoFs.value)
}

function dismissPortraitHint() {
  portraitHintDismissed = true
  portraitHint.value = false
}

/** 旋转 / 窗口变化：底栏形态跟随，竖屏提示也重算 */
function onWindowResize() {
  syncLandscape()
  syncCompactViewport()
  checkOrientation()
}

/**
 * 紧凑视口判定（与点播播放器 VideoPlayer 同款）。
 *
 * 电视的 CSS 视口至少 540 高（960×540），手机横过来也只有 360 左右 —— 用
 * **最小边 < 480** 就能把"真电视"和"手机（含 TV 壳跑在手机上）"分开。
 */
const compactViewport = ref(false)
function syncCompactViewport() {
  compactViewport.value = Math.min(window.innerWidth, window.innerHeight) < 480
}

/**
 * 全屏时左上角的「退出全屏」按钮（手机上唯一的出口）。
 *
 * <p>判据直接复用点播播放器那套（VideoPlayer 的 showBackButton；浮层返回键已按用户要求删除），
 * 用户口径：**大屏 TV 不显示，竖屏设备切换成横屏的正常显示**。
 *
 *   真电视（TV 壳 + 非紧凑视口）→ 不给：遥控器有返回键，而且左上角按钮在
 *     几何选焦里会被最先命中，按 OK 就误触退出（点播那边踩过）。
 *   手机（含 TV 壳跑在紧凑视口上）→ 给：全屏后按横屏形态底栏不渲染，
 *     屏幕上没有第二个出口。
 *
 * ★ 判据里**不能**用 landscape（视口方向）：手机点全屏后会转成横屏，
 *   用方向判会把刚转过来的按钮又藏掉 —— 正是用户说的"竖屏设备切换横屏后
 *   要正常显示"那个场景。所以用 isTouch + compactViewport。
 *
 * ★ 控制栏唤出时隐藏（与点播同款）：否则和底栏左上角按钮重叠。
 */
const showExitFsButton = computed(
  () =>
    (fullscreen.value || pseudoFs.value) &&
    !showCtrl.value &&
    // 面板打开时让位：面板自带返回键，两个圆形箭头在同一位置会叠在一起
    !showGuide.value &&
    isTouch.value &&
    (!isTvEnv() || compactViewport.value),
)

/**
 * 「分类-台」面板打开时左上角的返回按钮。
 *
 * <p><b>2026-11-12 用户口径</b>：不常驻——和面板**同时显示/消失**即可。
 * 面板已挪到右侧，宽屏上按钮在左、面板在右，互不遮挡；只有窄屏（手机竖屏）
 * 面板占 86% 宽时才需要 top-14 给按钮让位。面板关掉后按钮一起收走。
 *
 * <p>真电视仍然不给：遥控器有返回键，而且左上角按钮会被几何选焦最先命中。
 */
const showLiveBack = computed(
  () => !!current.value && showGuide.value && !isBigScreenLandscape(),
)
/** 换台提示（"央视一套 → 央视二套"），必须出现在画面中央 */
const zapHint = ref('')
let zapTimer: number | undefined
/** 控制栏自动隐藏 */
const showCtrl = ref(true)
let hideTimer: number | undefined
/**
 * 频道指南（OK 呼出的「分类-台」遮罩）。
 *
 * <p>为什么需要它：换台只有一条 2 秒的提示，用户按了 ↑↓ 之后不知道自己
 * 在哪个分类、这一组有多少台 —— 经典 IPTV 机顶盒的答案是"呼出一块遮罩，
 * 左边分类右边台"。
 *
 * ★ 导航交给 TV 壳的 tv-nav 焦点系统，不自己写光标状态机：面板根带
 *   `data-tv-scope` 后，tv-nav 的候选集自动限定在面板内（items() 里
 *   `var root = scope || document`），几何选焦天然满足"↑↓ 在本列、
 *   ←→ 跨列"；打开时它还会按 `[data-tv-current]` 把焦点搬到当前台。
 *   自己写状态机踩过：面板打开后 tv-nav 已把焦点框画在「刷新」上，
 *   `if (!markedEl())` 不成立 → 方向键压根不派发给页面，状态机全程收不到键，
 *   用户看到的是"↑↓ 直接跳到左侧分类列表"。
 */
const showGuide = ref(false)
/** 频道指南挂哪儿：默认 body，真全屏时换成全屏元素（否则面板画不出来） */
const guideOverlayHost = useOverlayHost()
/** 面板光标：左列分类下标（面板导航由页面接管，见 openGuide 注释） */
const guideGroup = ref(0)
/** 面板光标：右列台下标（在去重后的 channels 里） */
const guideChannel = ref(0)
/** 面板光标：顶部「换源」行下标（多播放列表时才存在） */
const guideSource = ref(0)
/**
 * 光标在哪一列：↑↓ 只作用于当前列（列内夹停），←→ 切列。
 * 不用几何选焦就是因为没有这个"列"概念（见 openGuide 注释）。
 */
const guideColumn = ref<'source' | 'group' | 'channel'>('channel')

/**
 * 「分类-台」面板的宽度调节（2026-11-12 用户要求）。
 *
 * <p>固定 `w-1/3` + 左列 42% 时，长分类名（「公共直播源（每日更新）」）和
 * 台名会被截成「公共直播…」「CCTV…」。两条都做：自适应 + 可拖拽。
 *   1. 左列默认按内容自适应（`w-max`，上限 46% 面板宽）；
 *   2. 面板右边缘、左右两列之间各给一个拖拽条，拖完记 localStorage，
 *      下次打开沿用（0 = 没拖过，走默认）。
 *
 * <p>TV 上没有指针设备，拖拽条永远不会被触发，默认宽度也不变 ——
 * 遥控器那套 ↑↓/←→/OK 完全不受影响。
 */
const GUIDE_W_KEY = 'meow.live.guideW'
const GUIDE_SPLIT_KEY = 'meow.live.guideSplitW'
const guidePanelW = ref(0)
const guideSplitW = ref(0)
try {
  const w = Number(localStorage.getItem(GUIDE_W_KEY) || 0)
  const sp = Number(localStorage.getItem(GUIDE_SPLIT_KEY) || 0)
  if (Number.isFinite(w) && w > 0) guidePanelW.value = Math.round(w)
  if (Number.isFinite(sp) && sp > 0) guideSplitW.value = Math.round(sp)
} catch {
  /* 隐私模式下 localStorage 会抛，忽略 */
}
const guidePanelStyle = computed(() =>
  guidePanelW.value > 0
    ? { width: guidePanelW.value + 'px', minWidth: '0px', maxWidth: 'none' }
    : undefined,
)
const guideSideStyle = computed(() =>
  guideSplitW.value > 0
    ? { width: guideSplitW.value + 'px', minWidth: '0px', maxWidth: 'none' }
    : undefined,
)
function saveGuideSize(key: string, v: number) {
  try {
    localStorage.setItem(key, String(Math.round(v)))
  } catch {
    /* 忽略 */
  }
}
/**
 * 拖面板**左边缘**：240px ~ 62% 视口宽。
 *
 * <p>面板贴右缘（用户 2026-11-12 要求改到右侧），右边缘固定在屏幕上，
 * 只能拖左边；向右拖是变窄，所以位移要取反，否则指针和边缘反向跑。
 */
function startGuidePanelResize(e: PointerEvent) {
  const panel = (e.currentTarget as HTMLElement).closest('[data-guide-panel]') as HTMLElement | null
  if (!panel) return
  const startX = e.clientX
  const startW = panel.getBoundingClientRect().width
  const maxW = Math.round(window.innerWidth * 0.62)
  const move = (ev: PointerEvent) => {
    const w = Math.round(Math.min(maxW, Math.max(240, startW + startX - ev.clientX)))
    guidePanelW.value = w
    // 面板被拖窄时，左列不能把它挤没
    const cap = Math.max(104, w - 168)
    if (guideSplitW.value > cap) guideSplitW.value = cap
  }
  const up = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
    window.removeEventListener('pointercancel', up)
    saveGuideSize(GUIDE_W_KEY, guidePanelW.value)
    if (guideSplitW.value > 0) saveGuideSize(GUIDE_SPLIT_KEY, guideSplitW.value)
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
  window.addEventListener('pointercancel', up)
}
/**
 * 拖两列之间的分界。
 *
 * <p>★ 面板改到右侧后语义反过来了：右边缘钉在屏幕上，分界线的 x =
 * 右边缘 − 右列宽。**只改左列宽的话分界线不会动**（左列往左长、右列
 * 宽度不变，分界线仍在原位），拖动会像"拖不动"。所以这里改成：
 * 左列宽度固定，拖动调**右列**宽度，面板跟着一起变宽/变窄。
 *
 * <p>「面板跟着变宽、另一列不被挤压」的原始诉求仍然成立，只是守住的那一列
 * 从右列换成了左列。
 */
function startGuideSplitResize(e: PointerEvent) {
  const side = (e.currentTarget as HTMLElement).closest('[data-guide-side]') as HTMLElement | null
  const panel = (e.currentTarget as HTMLElement).closest('[data-guide-panel]') as HTMLElement | null
  if (!side || !panel) return
  const startX = e.clientX
  const startW = side.getBoundingClientRect().width
  const startPanelW = panel.getBoundingClientRect().width
  // 左列守住起始宽度；右列随指针缩放
  const leftW = Math.max(104, Math.round(startW))
  const startRight = Math.max(168, Math.round(startPanelW - startW))
  const maxPanelW = Math.round(window.innerWidth * 0.62)
  const maxRight = Math.max(168, maxPanelW - leftW)
  const move = (ev: PointerEvent) => {
    const right = Math.round(Math.min(maxRight, Math.max(168, startRight - (ev.clientX - startX))))
    guideSplitW.value = leftW
    guidePanelW.value = leftW + right
  }
  const up = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
    window.removeEventListener('pointercancel', up)
    saveGuideSize(GUIDE_SPLIT_KEY, guideSplitW.value)
    saveGuideSize(GUIDE_W_KEY, guidePanelW.value)
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
  window.addEventListener('pointercancel', up)
}
/** 起播看门狗：3 秒没画面就判坏台 */
let badTimer: number | undefined
/**
 * 已经发起 `play()` 但还没等到 `playing`（#3 ③）。
 *
 * <p>移动端 autoplay 策略会 reject 无手势的 `play()`，之后 `v.paused === true`
 * 且没有任何事件 —— 缓冲看门狗原来 `if (v.paused) return` 直接跳过，
 * 坏台看门狗又把 readyState=2 当成功，两条兜底同时失效。
 * 这个标记让缓冲看门狗能区分"用户自己暂停"和"自动播放被拒"。
 */
let autoPlayPending = false
/** 连续跳过几个坏台了。一次换台操作里连跳超过 MAX_AUTO_SKIP 就停下等人 */
let autoSkipCount = 0
/**
 * 当前台已经试过几条线路（坏线路自动换线用）。
 *
 * 与 autoSkipCount 分开：换线不是"跳台"，混用会让整组频道很快被误判成全坏。
 */
let lineTryCount = 0
/** 一轮换台最多自动跳几次，防止整组全坏时疯狂刷源 */
const MAX_AUTO_SKIP = 5
/**
 * 起播判定窗口。
 *
 * <p>原来是 3 秒：直播 HLS 首屏普遍要 5–8 秒（MuMu 实测 CCTV-2 花了 6 秒），
 * 3 秒会把「慢但能播」的台判成坏台，一路跳到最后还报"全都放不出来"。
 * 放宽到 6 秒，代价是整组全坏时多等 3 秒。
 */
const BAD_CHANNEL_MS = 4500

// ---------------------------------------------------------------- 会话级缓存
//
// ★ 为什么需要：进直播页的固定成本是 `/api/live` 聚合（12 秒预算）+
//   m3u 拉取解析，原来每次退出再进都全来一遍。缓存后二次进入**秒开**
//   （直接用缓存起播上次频道），后台再静默刷新——像开电视机，不是开网页。
//
// 只缓存"成功拿到分组"的结果；聚合失败（groups 空）不缓存，否则坏一次
// 之后整个会话都坏。
/**
 * 把当前分组列表快照进会话缓存。
 *
 * ★ 快照时机不能只看 `/api/live` 的返回：我们 jar 里 72 个类零个实现
 *   liveContent，聚合结果 groups 恒为空——真正的频道是 openPlaylist 把
 *   IPTV m3u 解析后塞进去的。只按聚合结果判"有没有货"永远缓存不上。
 *   所以这里只认 groups.value 的实际内容（数组壳拷贝，元素引用共享——
 *   没人有原地改元素的操作，openPlaylist 是 filter 出新数组再 push）。
 */
/** 分组键 = 站点 + 组名，避免不同站同名分组互相覆盖 */
function keyOf(g: LiveGroup): string {
  return `${g.site}|${g.group}`
}

function snapshotCache() {
  if (!groups.value.length || !stats.value) return
  cache = {
    groups: groups.value.slice(),
    playlists: playlists.value.slice(),
    stats: stats.value,
  }
  saveDiskCache()
}

// ---------------------------------------------------------------- 持久化缓存

/**
 * 频道列表持久化到 localStorage（跨进程：冷启动 0 秒出画面）。
 *
 * <p>为什么内存缓存不够：`cache` 是模块级变量，杀进程就没了。用户每次
 * 重新打开电视都要等 6 秒（Guovin 那 208KB m3u 要下载 + 解析）。
 * 实测：Guovin 6.1s / iptv-org 0.5s，而 m3u 每天才更新一次 —— 6 小时 TTL
 * 足够，代价是 900 台约 120KB 存储（localStorage 5MB 上限）。
 *
 * <p>过期策略：TTL 内直接用，**同时后台静默刷新**（stale-while-revalidate）。
 * 过期则丢弃，走正常加载。刷新失败不影响已显示的缓存。
 */
const DISK_KEY = 'meow.live.disk.v1'
const DISK_TTL = 6 * 3600_000
/** 上次在用的源（单源切换时写入；合并模式用它决定线路优先顺序） */
const SRC_KEY = 'meow.live.source'

interface DiskCache {
  at: number
  groups: LiveGroup[]
  playlists: Array<{ name: string; url: string }>
  stats: { tried: number; failed: number }
}

/**
 * 写磁盘缓存（防抖 800ms）。
 *
 * <p>渐进合并时**每条源回来都会调一次** snapshotCache → saveDiskCache，
 * 而 491 个台的 JSON.stringify + localStorage.setItem 是 10~20ms 主线程工作。
 * 两源就是 40ms 卡顿（表现为面板滚动掉帧）。挪到"最后一次调用后 800ms"
 * 只写一次；卸载时 flush 一笔，避免刚合并完就退出导致缓存没落盘。
 */
let diskSaveTimer: number | undefined

function saveDiskCache() {
  if (diskSaveTimer) window.clearTimeout(diskSaveTimer)
  diskSaveTimer = window.setTimeout(doSaveDiskCache, 800)
}

function doSaveDiskCache() {
  diskSaveTimer = undefined
  try {
    localStorage.setItem(
      DISK_KEY,
      JSON.stringify({
        at: Date.now(),
        groups: groups.value,
        playlists: playlists.value,
        stats: stats.value || { tried: 0, failed: 0 },
      } satisfies DiskCache),
    )
  } catch {
    /* 配额满 / 隐私模式：内存缓存照常，只是冷启动不再秒开 */
  }
}

function readDiskCache(): boolean {
  try {
    const raw = localStorage.getItem(DISK_KEY)
    if (!raw) return false
    const d = JSON.parse(raw) as Partial<DiskCache>
    if (!d || !d.at || Date.now() - d.at > DISK_TTL) return false
    const gs = (d.groups || []) as LiveGroup[]
    if (!gs.length) return false
    groups.value = gs
    playlists.value = (d.playlists || []) as Array<{ name: string; url: string }>
    stats.value = (d.stats as { tried: number; failed: number }) || { tried: 0, failed: 0 }
    activeKey.value = keyOf(gs[0])
    return true
  } catch {
    return false
  }
}

function lastSourceUrl(): string {
  try {
    return localStorage.getItem(SRC_KEY) || ''
  } catch {
    return ''
  }
}

/**
 * 每条直播源的"最近表现"（本地持久化，7 天过期）。
 *
 * ★ 2026-11-26 真机：配置源 Gather「果」的台址大面积 Connection refused，
 *   但排序只看"上次选的" → 死源永远排第一，每个台先耗十几秒试死线路。
 *   这里按成功率给源打分，**成功过的源**（而不是点过的源）优先。
 */
const SRC_SCORE_KEY = 'meow.live.srcScore'
type SrcScoreMap = Record<string, { ok: number; fail: number; at: number }>
const SRC_SCORE_TTL = 7 * 24 * 3600_000

function readSrcScore(): SrcScoreMap {
  try {
    const raw = JSON.parse(localStorage.getItem(SRC_SCORE_KEY) || '{}') as SrcScoreMap
    const out: SrcScoreMap = {}
    const now = Date.now()
    for (const [k, v] of Object.entries(raw || {})) {
      if (v && now - (v.at || 0) < SRC_SCORE_TTL) out[k] = v
    }
    return out
  } catch {
    return {}
  }
}

function bumpSrcScore(name: string, delta: 1 | -1) {
  const key = String(name || '').trim()
  if (!key) return
  try {
    const map = readSrcScore()
    const cur = map[key] || { ok: 0, fail: 0, at: Date.now() }
    if (delta > 0) cur.ok++
    else cur.fail++
    cur.at = Date.now()
    map[key] = cur
    localStorage.setItem(SRC_SCORE_KEY, JSON.stringify(map))
  } catch {
    /* 隐私模式写不进就算了 */
  }
}

/** 源得分：成功按 2 分、失败按 1 分扣（一次成功抵两次失败） */
function srcScoreOf(name: string): number {
  const v = readSrcScore()[String(name || '').trim()]
  return v ? (v.ok || 0) * 2 - (v.fail || 0) : 0
}

/** 当前线路来自哪个源（label 形如「线路 3 · Guovin」） */
function currentLineSrc(): string {
  const cur = current.value
  if (!cur) return ''
  const label = cur.urls[currentUrlIndex.value]?.label || ''
  const at = label.lastIndexOf(' · ')
  return at >= 0 ? label.slice(at + 3).trim() : ''
}

function isBuiltinSource(p: { name: string; url: string }): boolean {
  return /公共直播源/.test(p.name) || /guovin\.github\.io/i.test(p.url)
}

/**
 * 源排序：有成功记录按得分排；没有记录时「上次选的 → 内置每日更新 → 其余」。
 *
 * ★ 2026-11-26 真机：死源排第一会让进去长时间转圈（每个台先试它 5~6 条死线路）。
 */
function orderedLives(list: Array<{ name: string; url: string }>) {
  const scores = readSrcScore()
  const haveOk = list.some((p) => (scores[p.name]?.ok || 0) > 0)
  if (haveOk) {
    return [...list].sort((a, b) => {
      const d = srcScoreOf(b.name) - srcScoreOf(a.name)
      if (d) return d
      return Number(isBuiltinSource(b)) - Number(isBuiltinSource(a))
    })
  }
  const pref = lastSourceUrl()
  const builtin = list.find(isBuiltinSource)
  const firstUrl = (pref && list.some((p) => p.url === pref) ? pref : builtin?.url) || ''
  if (!firstUrl) return list
  return [
    ...list.filter((p) => p.url === firstUrl),
    ...list.filter((p) => p.url !== firstUrl),
  ]
}

/** 上次用的那条源（没了就退回第一条；没记录时优先内置每日更新源） */
function preferPlaylist(): { name: string; url: string } {
  const last = lastSourceUrl()
  return (
    playlists.value.find((p) => p.url === last) ||
    orderedLives(playlists.value.slice())[0] ||
    playlists.value[0]
  )
}

// ---------------------------------------------------------------- 多源合并

/** 多源合并开关（设置页「直播源」可关；关掉就是"选一条用"的老行为） */
const mergeOn = ref(getPrefs().liveMerge !== false)
/** 还有几条源在拉（面板/调试可看） */
const mergeBusy = ref(0)

/**
 * 多源合并加载（渐进式）。
 *
 * <p>**不等所有源**：谁先回来就合并进去、立刻可见。实测两源
 * iptv-org 0.5s / Guovin 6.1s，用户 0.5 秒就看到 149 个台能点，
 * 6 秒后列表悄悄变成 471 个台（同台线路自动变多），画面不中断。
 *
 * <p>进度可见性：`mergeBusy` 递减到 0 才算完成；中途失败的单源只丢自己。
 */
async function refreshMerged() {
  // 先按"上次用的 / 内置每日更新"排序：合并时线路顺序 = 源的顺序
  const lives = orderedLives(playlists.value.slice())
  if (!lives.length) return
  const got = new Map<string, ParsedSource>()

  // 会话内解析缓存先顶上（同一进程里切回直播页不用重拉）
  for (const p of lives) {
    const hit = parsedCache.get(p.url)
    if (hit && Date.now() - hit.at < PARSE_TTL) {
      got.set(p.url, { name: p.name, url: p.url, groups: hit.groups })
    }
  }
  if (got.size) applyMerged(lives, got)

  const todo = lives.filter((p) => !got.has(p.url))
  if (!todo.length) return
  mergeBusy.value = todo.length
  await Promise.all(
    todo.map(async (p) => {
      try {
        const r = await fetchLivePlaylist(p.url)
        if (r.ok && r.groups?.length) {
          parsedCache.set(p.url, { groups: r.groups, at: Date.now() })
          got.set(p.url, { name: p.name, url: p.url, groups: r.groups })
          applyMerged(lives, got)
        }
      } catch {
        /* 单源失败不影响其它源 */
      } finally {
        mergeBusy.value = Math.max(0, mergeBusy.value - 1)
      }
    }),
  )
}

/**
 * 把已拿到的源合并进 `groups`。
 *
 * @param lives  配置里的源列表（**顺序就是分类归属顺序**）
 * @param got    已解析的源（按 URL 索引）
 * @param prefer 上次用的源 URL（只影响线路顺序）
 */
function applyMerged(
  lives: Array<{ name: string; url: string }>,
  got: Map<string, ParsedSource>,
  prefer?: string,
) {
  // ★ 必须按配置顺序组装：并行完成顺序不可靠，而"台归属哪个分类"由
  //   首次出现决定 —— 用完成顺序会让 CCTV 台今天在「央视频道」明天在「新闻」
  const ordered = lives.map((p) => got.get(p.url)).filter((x): x is ParsedSource => !!x)
  if (!ordered.length) return
  const merged = mergeSources(ordered, prefer)
  const siteName = ordered.length > 1 ? '多源合并' : ordered[0].name
  groups.value = merged.map((g) => ({
    site: 'playlist',
    site_name: siteName,
    group: g.group,
    channels: g.channels.map((c) => ({ name: c.name, urls: c.urls, srcs: c.srcs })),
  }))
  if (!groups.value.some((g) => keyOf(g) === activeKey.value)) {
    activeKey.value = groups.value.length ? keyOf(groups.value[0]) : ''
  }
  syncCurrentUrls()
  snapshotCache()
  if (!current.value) autoPlayFirst()
}

/**
 * 合并结果刷新后同步"正在播的台"的线路列表。
 *
 * <p>不打这个补丁的症状：合并前播的是 A 源的 CCTV-1（1 条线路），
 * 慢源回来后该台变成 6 条线路，但 `current.urls` 还是旧的 1 条 ——
 * ←→ 换不了新线路（看起来像"合并没生效"）。
 */
function syncCurrentUrls() {
  const cur = current.value
  if (!cur) return
  const hit = channels.value.find((c) => c.name === cur.name)
  if (!hit) return
  const parsed = parseUrls(hit.urls, hit.srcs)
  const playing = activeUrl.value
  if (playing && !parsed.some((u) => u.url === playing)) {
    // 正在播的线路不在新列表里（源被删/改）：保留它并插到最前 ——
    // 否则 currentUrlIndex 变 -1，换线路按钮直接失效
    const old = cur.urls.find((u) => u.url === playing)
    if (old) parsed.unshift(old)
  }
  cur.urls = parsed
}

const activeGroup = computed(() => groups.value.find((g) => keyOf(g) === activeKey.value))

/**
 * CatVod 的频道地址形如 `http://x.m3u8$备用名`。
 * `$` 后是同一频道的线路显示名，必须拆开——直接拿去播放会 404。
 */
function parseUrls(
  raw: string[] | undefined,
  srcs?: string[],
): Array<{ url: string; label: string }> {
  return (raw || [])
    .map((x, i) => {
      const s = String(x || '').trim()
      if (!s) return null
      // 多源合并时把来源带上：换线路的提示会显示"线路 3/5 · Guovin"
      const src = (srcs?.[i] || '').trim()
      const tail = src ? ` · ${src}` : ''
      const at = s.lastIndexOf('$')
      if (at > 0 && at < s.length - 1 && !s.slice(at + 1).includes('/')) {
        return { url: s.slice(0, at), label: (s.slice(at + 1) || `线路${i + 1}`) + tail }
      }
      return { url: s, label: `线路${i + 1}${tail}` }
    })
    .filter((x): x is { url: string; label: string } => !!x)
}

/**
 * 拉取直播数据。
 *
 * @param force true = 绕过缓存真拉（面板「刷新」/空态「重试」）；
 *              默认 stale-while-revalidate：有缓存先秒开，后台静默更新。
 *
 * ★ 有缓存时**不显示全屏转圈**：数据立刻可用，先起播再后台刷新——
 *   二次进入的等待是 0 秒。后台拉回的新数据同样落进缓存，
 *   正在播的流不受影响（current 自己持有地址，不依赖 groups 引用）。
 */
async function load(force = false) {
  const hasCache = !!cache && cache.groups.length > 0
  if (hasCache && !force) {
    groups.value = cache!.groups
    playlists.value = cache!.playlists
    stats.value = cache!.stats
    activeKey.value = groups.value.length ? keyOf(groups.value[0]) : ''
    autoPlayFirst()
  } else if (!force && readDiskCache()) {
    /*
     * 跨进程缓存命中：0 秒出画面。
     * 下面照常拉 /api/live + 解析 m3u —— 那是**后台刷新**，新数据回来才替换，
     * 用户看到的是"打开就能看"，不是"转圈 6 秒"。
     */
    autoPlayFirst()
  } else {
    loading.value = true
    // 强制刷新 = 用户要"全部重拉"，m3u 解析缓存也一并作废
    if (force) parsedCache.clear()
  }
  loadingSeconds.value = 0
  error.value = ''
  // load 会被刷新/重试按钮重复触发，每次先清旧计时器再开新的，
  // 否则并发两个 interval 会让秒数一秒跳两下。
  clearInterval(loadTimer)
  loadTimer = window.setInterval(() => {
    loadingSeconds.value++
  }, 1000)
  // 本次聚合（/api/live）到底有没有给出分组。注意它在 try 里赋值、在后面用，
  // 所以必须提到外面声明 —— 直接引用 try 里的 `next` 会编译失败。
  let aggHasGroups = false
  try {
    const r = await fetchLive()
    const next = {
      groups: (r.groups || []) as LiveGroup[],
      playlists: r.playlists || [],
      stats: { tried: r.tried || 0, failed: r.failed || 0 },
    }
    aggHasGroups = next.groups.length > 0
    // 只缓存有分组的结果（见 cache 定义处的说明）
    if (next.groups.length) cache = next
    /*
     * ★ playlists / stats 必须**无条件**更新：它们与 groups 无关，清了也不会
     *   让画面卸载。踩过（2026-11-06）：把 playlists 的赋值也塞进
     *   `if (next.groups.length)` 里 —— 而我们 jar 里零个类实现 liveContent，
     *   聚合结果 groups 恒为空，于是 playlists 永远停在 []，自动展开
     *   openPlaylist 的条件不成立，直播页直接掉进"还没有可用的直播源"空态。
     */
    playlists.value = next.playlists
    stats.value = next.stats
    if (next.groups.length) {
      groups.value = next.groups
      if (!hasCache || force) {
        activeKey.value = groups.value.length ? keyOf(groups.value[0]) : ''
      }
    }
    /*
     * ★ 聚合结果为空时**绝不能清** groups：真正的频道是 openPlaylist 塞进来的。
     *   清了它，正在播的画面会因模板 v-if 链整个卸载（stage 消失、
     *   重新触发 openPlaylist 再恢复），表现为"看着看着黑一下"。
     *   本来就空（无缓存首进）时走下方自动展开兜底。
     */
  } finally {
    // fetchLive 自己吞掉异常（api/index.ts 的 catch），finally 只是给计时器兜底
    clearInterval(loadTimer)
    loadTimer = undefined
    loading.value = false
  }

  // 聚合全失败时自动展开第一个播放列表。
  //
  // 实测：spider jar 里没有一个类实现 liveContent（`/api/live` 出来
  // tried=52 failed=52 groups=[]），但 config.lives 里的 IPTV 地址完全可用
  // （同一地址解析出 85 组 7085 频道）。不自动展开的话，用户进页面
  // 只看到"当前配置里没有可用的直播源"，就走了——功能其实在，只是没接上。
  /*
   * ★ 判据必须是"**这次聚合**有没有分组"，不能是 groups.value 有没有内容。
   *
   * 踩过（2026-11-11 用户加了几个国家源，直播页里没有它们）：
   * 原来写的是 `!groups.value.length` —— 而进入页面时 groups 已经被
   * 磁盘缓存/会话缓存填满了，条件恒假 → 后台合并永远不跑。
   * 结果是"设置里加了源，直播页还是上次的老列表"，连面板的「刷新」
   * 都救不回来（force 只重拉 /api/live，不重拉 m3u）。
   *
   * 现在：只要 /api/live 没给分组、而配置里有播放列表，就一定要合并
   * （refreshMerged 是渐进式，缓存列表先留着，解析回来一条换一条，
   * 不影响正在播的流）。单源模式同理，重开一次 preferPlaylist()。
   */
  if (!aggHasGroups && playlists.value.length) {
    /*
     * 有播放列表但没有站点分组（我们的 jar 里零个类实现 liveContent，
     * 所以这是常态）。两种模式：
     *   · 多源合并（默认）→ 并行解析，谁先回来先显示，慢源后台补
     *   · 单源 → 打开上次用的那条（没有就第一条），行为和以前一致
     */
    if (mergeOn.value && playlists.value.length > 1) {
      void refreshMerged()
    } else {
      void openPlaylist(preferPlaylist())
    }
  } else if (!current.value) {
    // /api/live 直接给了分组（有源实现了 liveContent）：也要自动出画面。
    // 有缓存秒开的路径已在上面起播过，current 有值就不重复。
    autoPlayFirst()
  }
  snapshotCache()
}

/**
 * 进直播页直接进全屏。
 *
 * <p>理由：电视的使用场景是"打开就看"，不是"先看一个网页版播放器再点全屏"。
 * 原来进直播页是一个带顶栏的小播放器，用户还要自己找全屏按钮 ——
 * 遥控器上那个按钮在控制栏最右边，得按 8 次右键才摸得到。
 *
 * <p>必须有画面才进全屏：没源的时候进全屏是一片黑，比小窗更糟。
 */
function enterFullscreenIfPlaying() {
  if (!current.value) return
  // 等 DOM 里的 video 真正挂上再请求全屏，否则 requestFullscreen 会被拒
  void nextTick().then(() => {
    void toggleFullscreen()
  })
}

async function playChannel(ch: { name?: string; urls?: string[]; srcs?: string[] }) {
  const urls = parseUrls(ch.urls, ch.srcs)
  if (!urls.length) {
    error.value = '该频道没有可播放地址'
    return
  }
  // 换了台 → 台内换线计数归零（新台从头开始试）
  lineTryCount = 0
  // 下标优先从当前分组里反查（点网格时能拿到准确位置）；
  // 找不到就是 -1，换台会从头开始扫，不会卡住。
  const idx = channels.value.findIndex((c) => c === ch)
  current.value = { name: ch.name || '未命名频道', urls, idx }
  // <video> 是 v-if="current" 渲染的：不等这一拍，video.value 还是 null，
  // switchUrl 会静默什么都不做（表现为"点了没反应"）。
  await nextTick()
  switchUrl(urls[0].url)
  rememberLast()
  // 第一次起播时自动进全屏：电视的使用场景是"打开就看"。
  // 只做一次 —— 用户之后自己退出来再换台，不该又被弹回全屏。
  if (!firstPlayDone) {
    firstPlayDone = true
    enterFullscreenIfPlaying()
  }
}

/** 展开一条播放列表：解析成功后作为普通分组塞进列表 */
async function openPlaylist(p: { name: string; url: string }, force = false) {
  playlistBusy.value = p.url
  error.value = ''
  // 记住"上次用的源"：下次进来优先用它（合并模式下决定线路优先顺序）
  try {
    localStorage.setItem(SRC_KEY, p.url)
  } catch {
    /* 存不了就算了，退回第一条 */
  }
  try {
    // 解析结果缓存：重进页面/切回列表时不再重复拉 100KB m3u（见 parsedCache）
    const hit = parsedCache.get(p.url)
    const fresh = hit && Date.now() - hit.at < PARSE_TTL ? hit.groups : null
    let parsed: ParsedGroups
    if (fresh) {
      parsed = fresh
    } else {
      const r = await fetchLivePlaylist(p.url)
      if (!r.ok || !r.groups?.length) {
        error.value = r.msg || '该播放列表没有频道'
        return
      }
      parsed = r.groups
      parsedCache.set(p.url, { groups: parsed, at: Date.now() })
    }
    // 同一列表重复打开时先清掉旧的，避免分组重复堆叠
    groups.value = groups.value.filter((g) => !(g.site === 'playlist' && g.site_name === p.name))
    let firstKey = ''
    for (const g of parsed) {
      const next: LiveGroup = {
        site: 'playlist',
        site_name: p.name,
        group: g.group,
        channels: g.channels,
      }
      groups.value.push(next)
      // 选中键必须用 keyOf(g)，不能用"列表名"——侧栏按 site|group 匹配，
      // 用列表名会永远匹配不上，表现是"点开了但右边什么都没有"（实测踩过）
      if (!firstKey) firstKey = keyOf(next)
    }
    activeKey.value = firstKey
    // 换源成功才关面板：失败时留在面板里，错误就显示在信号源列表下方
    showGuide.value = false
  } finally {
    playlistBusy.value = ''
  }
  autoPlayFirst()
  snapshotCache()
}

/**
 * 有台但还没画面时，自动播当前组的第一个。
 *
 * <p>看电视的顺序是"开机就有画面"，不是"先选台再看"。原来进直播页
 * 是一片黑的占位，遥控器用户还得摸索着找第一个频道格。
 */
/**
 * 自动起播：**上次看的频道优先**，其次当前组第一个。
 *
 * 电视机开机还是上次那个台——直播的"默认台"就该这么记。
 * 频道列表每天更新（内置源每日刷新），所以按 groupKey + 频道名匹配，
 * 匹配不到（台改名/撤了）退到该组第一个，不硬绑下标。
 */
function autoPlayFirst() {
  if (current.value) return
  let last: { g: string; n: string } | null = null
  try {
    last = JSON.parse(localStorage.getItem(LAST_KEY) || 'null')
  } catch {
    last = null
  }
  const g =
    (last && groups.value.find((x) => keyOf(x) === last!.g)) || groups.value[0]
  if (!g) return
  activeKey.value = keyOf(g)
  // 用去重后的列表找（一个台一行，urls 已合并）：直接拿 g.channels 的原始条目
  // 只会用到该台的第一条线路，←→ 就切不了线（踩过）
  const list = channels.value
  const ch = (last && list.find((c) => c.name === last!.n)) || list[0]
  if (ch) void playChannel(ch)
}

/** 起播成功后记住这个台（见 autoPlayFirst 的说明） */
function rememberLast() {
  if (!current.value || !activeKey.value) return
  try {
    localStorage.setItem(
      LAST_KEY,
      JSON.stringify({ g: activeKey.value, n: current.value.name }),
    )
  } catch {
    /* 存不了就算了：下次进页面播第一个台 */
  }
}
/** 页内添加直播源：校验通过就落盘（config.lives），然后直接打开它。 */
async function doAddLive() {
  const url = addUrl.value.trim()
  if (!/^https?:\/\//i.test(url)) {
    addMsg.value = '请填 http(s) 开头的 m3u / txt 地址'
    return
  }
  addBusy.value = true
  addMsg.value = ''
  try {
    // verify：先真拉一遍解析。存一个打不开的地址只会让下次进来还是空的
    const r = await addLiveSource({ url, verify: true })
    if (!r.ok) {
      addMsg.value = r.msg || '这个地址解析不出频道'
      return
    }
    addUrl.value = ''
    await load()
    const added = (r.lives || []).find((l) => l.url === url)
    if (added) await openPlaylist(added)
    addMsg.value = r.duplicate ? '这个地址已经在了' : '已添加'
  } catch (e: any) {
    addMsg.value = e?.message || String(e)
  } finally {
    addBusy.value = false
  }
}

/** 当前分组里的频道数组（换台用） */
/** 去重后的频道条目：一个台一行，线路在 urls 里 */
interface ChannelItem {
  name: string
  urls: string[]
  /** 与 urls 一一对应的来源名（多源合并时才有） */
  srcs?: string[]
  /**
   * 不同源的个数（预计算：模板里别对每行 new Set，491 行 × 每次渲染
   * 都会重算；这里算一次随 item 走）。
   */
  srcCount?: number
}

/** 把一组的原始频道按台名去重（线路合并进 urls） */
function uniqueOf(g?: LiveGroup): ChannelItem[] {
  const out: ChannelItem[] = []
  const byName = new Map<string, ChannelItem>()
  for (const ch of g?.channels || []) {
    const name = (ch.name || '').trim() || '未命名频道'
    const urls = ch.urls || []
    const srcs = ch.srcs
    const hit = byName.get(name)
    if (hit) {
      for (let i = 0; i < urls.length; i++) {
        const u = urls[i]
        if (hit.urls.includes(u)) continue
        hit.urls.push(u)
        // srcs 与 urls 必须同长度，否则"线路 3/5 · 源名"会错位
        if (hit.srcs && srcs) hit.srcs.push(srcs[i] || '')
      }
      if (hit.srcs) hit.srcCount = new Set(hit.srcs).size
      continue
    }
    const item: ChannelItem = { name, urls: urls.slice() }
    if (srcs) {
      item.srcs = srcs.slice()
      item.srcCount = new Set(srcs).size
    }
    byName.set(name, item)
    out.push(item)
  }
  return out
}

/**
 * 当前分组的频道列表 —— **按台名去重，线路合并不占列表项**。
 *
 * ★ 用户口径（2026-11-06）：「为什么标亮多个同一台？为什么不做成每台只显示
 *   一个（线路切换不表现在台列表）」。
 *   m3u 里同一台有多条 URL（CCTV-2 出现 5 次），原来每条都当独立频道：
 *   台列表里 5 个「CCTV-2」、高亮按台名匹配会同时点亮 5 行、
 *   ↑↓ 换台会在同一个台的 5 条线路之间来回跳（扫台体验很差）。
 *   去重后：一个台一行，多条 URL 合并进 urls，←→ 切线路不改变列表。
 */
/**
 * 频道健康版本号（#1）：健康表在 localStorage，不是响应式数据；
 * 每次判定成功/失败 +1，列表 computed 读它才会重新排序/灰显。
 */
const healthRev = ref(0)
function markLiveFail(name: string) {
  noteLiveFail(name)
  healthRev.value++
}
function markLiveOk(name: string) {
  // 这个源刚播成功：加分，下次排前面（见 orderedLives）
  bumpSrcScore(currentLineSrc(), 1)
  noteLiveOk(name)
  healthRev.value++
}

const channels = computed<ChannelItem[]>(() => {
  void healthRev.value
  return sortLiveByHealth(uniqueOf(activeGroup.value))
})

/**
 * **面板右列**的台列表：跟着面板左列光标（guideGroup）走，不是跟 activeKey。
 *
 * ★ 语义是"预览 vs 确认"：左列移动光标只是看，右列立刻显示那一类的台；
 *   按 OK 才把 activeKey 切过去并播放。原来右列跟 activeKey，左列光标
 *   移到别的分类时右列还是旧分类 —— 而且 guideOk 里"分类取 guideGroup、
 *   台取 channels（旧分类列表）"会让**播的台和显示的列表对不上**
 *   （实测：光标停在经典剧场，播放的却是央视频道的台）。
 */
const guideChannels = computed<ChannelItem[]>(() => {
  void healthRev.value
  return sortLiveByHealth(uniqueOf(groups.value[guideGroup.value]))
})

/**
 * 面板底部的"这一台的线路来自哪几个源"。
 *
 * <p>多源合并后同一个台可能有 6 条线路（来自 2 个源），台行的「6 线」
 * 看不出分布；换线路时的提示虽然带源名，但那是按下之后才知道的。
 * 只有一个源时不显示（单源场景本来就很清楚，别占地方）。
 */
const guideSrcHint = computed(() => {
  const ch = guideChannels.value[guideChannel.value]
  if (!ch || !ch.srcs?.length) return ''
  if (sourceCountOf(ch) < 2) return ''
  return `${ch.name} · ${describeSources(ch)}`
})

/** 某一组去重后的台数（面板左列的计数用，和右列列表长度一致） */
function uniqueCount(g: LiveGroup): number {
  const seen = new Set<string>()
  for (const ch of g.channels || []) seen.add((ch.name || '').trim() || '未命名频道')
  return seen.size
}

/**
 * 当前频道在列表里的下标；找不到为 -1。
 *
 * <p>优先按台名反查而不是直接用 `current.idx`：健康表更新会触发
 * `channels` 重排（坏台挪到末尾、刚恢复的台挪回前面），存下来的旧下标
 * 会指向别的台，换台就跳错。去重后的列表里台名唯一，可以安全反查。
 */
const channelIndex = computed(() => {
  const cur = current.value
  if (!cur) return -1
  const i = channels.value.findIndex((c) => c.name === cur.name)
  return i >= 0 ? i : cur.idx
})

/** 当前用的线路下标（同一个频道有多条地址时） */
const currentUrlIndex = computed(() => {
  const urls = current.value?.urls || []
  const i = urls.findIndex((u) => u.url === activeUrl.value)
  return i < 0 ? 0 : i
})

/** 重播当前台：坏台时最快的自救（换线路重试） */
function replayCurrent() {
  const urls = current.value?.urls || []
  if (!urls.length) return
  switchUrl(urls[currentUrlIndex.value]?.url || urls[0].url)
  wake()
}

/**
 * 上/下换台。
 *
 * <p>为什么用「上=上一个、下=下一个」而不是反过来：IPTV 机顶盒和电视
 * 遥控器都是这个约定，用户的手已经记住了。
 *
 * <p>到边界**环绕**（最后一个按 ↓ 回到第一个）：直播场景下用户是在"扫台"，
 * 撞到头没反应会以为遥控器坏了。
 */
async function zap(dir: 1 | -1) {
  const list = channels.value
  if (list.length < 2) return
  const i = channelIndex.value
  const next = i < 0 ? (dir > 0 ? 0 : list.length - 1) : (i + dir + list.length) % list.length
  // 用户主动换台，允许重新连续跳坏台
  autoSkipCount = 0
  const ch = list[next]
  if (!ch) return
  await playChannel(ch)
  // 同名频道很常见（CCTV1 × 200），提示里带上序号用户才知道真的换了
  flashZap(`${dir > 0 ? '下一个' : '上一个'} · ${ch.name || '未命名'} (${next + 1}/${list.length})`)
}



/**
 * 在中上部闪一条换台提示——用户眼睛在画面中央，底栏提示会被忽略。
 *
 * <p>★ 不调 wake()：换台是连续操作，弹底栏会挡住画面并改变方向键语义。
 *   提示自己 2.2 秒后消失，够了。
 */
function flashZap(text: string) {
  zapHint.value = text
  clearTimeout(zapTimer)
  zapTimer = window.setTimeout(() => (zapHint.value = ''), 2200)
}

/** 左右切线路（同一频道的多地址） */
function cycleLine(dir: 1 | -1) {
  const urls = current.value?.urls || []
  if (urls.length < 2) return
  const i = urls.findIndex((u) => u.url === activeUrl.value)
  const next = (Math.max(0, i) + dir + urls.length) % urls.length
  switchUrl(urls[next].url)
  flashZap(`线路 ${urls[next].label}`)
}

// ---------------------------------------------------------------- 频道指南（分类-台）

/**
 * 打开面板。
 *
 * ★ 导航由页面接管（不交给 tv-nav 几何选焦）：几何选焦没有"列内夹紧"，
 *   右列到底再按 ↓ 会跳到左列同排（实测）；而且同台多线路时按台名高亮
 *   会同时点亮多行。
 *   页面接管的前提是 tv-nav 不介入：面板里**不能有原生可聚焦元素**
 *   （button/a/input 都会进它的候选集），所以面板项一律用
 *   `div role="button"`；候选集为空 → tv-nav 走"把键派发给页面"分支
 *   → 本页的 onKeydown 收得到键。
 */
function openGuide() {
  showGuide.value = true
  // 光标落在"当前分类 + 当前台"（从右列开始），按 OK 之前就知道自己在哪
  const gi = groups.value.findIndex((g) => keyOf(g) === activeKey.value)
  guideGroup.value = gi >= 0 ? gi : 0
  // 当前台在"该分类去重列表"里的下标（不是原始 channels 下标）
  const list = guideChannels.value
  const at = list.findIndex((c) => c.name === current.value?.name)
  guideChannel.value = at >= 0 ? at : 0
  guideColumn.value = 'channel'
  wake()
  /*
   * 打开时把"当前分类 + 当前台"滚到列表靠前位置。
   *
   * 只靠下面的 watch 不够：首次打开时 guideGroup/guideChannel 可能本来就是
   * 0（watch 不触发），或者值虽变但面板 DOM 还没挂好。用户实测"选中的分类
   * 在列表下方，还要自己滚才能找到"——这里等 Teleport 挂载后强制定位一次。
   * block:'start' = 选中项贴到列表顶部（配合 scroll-padding 16px），
   * 而不是 nearest 的"只保证露出来"（可能贴在底部看不见上下文）。
   */
  void nextTick(() => {
    requestAnimationFrame(() => {
      scrollGuideColIntoView('group', 'start')
      scrollGuideColIntoView('channel', 'start')
    })
  })
}

/**
 * 鼠标/触摸点分类。
 *
 * <p>点**当前分类**时不再把右列光标重置到第一个台：用户多半只是"看看这类里
 * 有什么"，一重置就把当前台的高亮与滚动位置丢了（2026-11-28 心智审计）。
 */
function pickGuideGroup(i: number) {
  if (i !== guideGroup.value) {
    guideGroup.value = i
    guideChannel.value = 0
  }
  guideColumn.value = 'channel'
}

/**
 * 鼠标/触摸点台。
 *
 * <p>点**正在播的那个台**只关面板，不重开流 —— 点击的心智是"选中并退出"，
 * 不是"重播"；重开会黑屏缓冲一次（2026-11-28 心智审计）。
 * 真需要重连时用底栏的「重播」。
 */
async function pickGuideChannel(i: number) {
  guideChannel.value = i
  const ch = guideChannels.value[i]
  const sameGroup = !groups.value[guideGroup.value]
    || keyOf(groups.value[guideGroup.value]) === activeKey.value
  if (ch && sameGroup && current.value && ch.name === current.value.name) {
    showGuide.value = false
    return
  }
  await guideOk()
}

/** 光标在右列上移/下移：**到边界停住**（不环绕，也不跳列） */
function guideMoveChannel(dir: number) {
  const n = guideChannels.value.length
  if (!n) return
  const next = guideChannel.value + dir
  guideChannel.value = Math.min(n - 1, Math.max(0, next))
}

/** 光标在左列分类上移/下移：同样到边界停住；切分类后右列光标归零 */
function guideMoveGroup(dir: number) {
  const n = groups.value.length
  if (!n) return
  if (dir < 0 && guideGroup.value === 0 && playlists.value.length > 1) {
    // 第一条分类再往上 = 顶部的「换源」行（视觉上就在分类上方）
    guideColumn.value = 'source'
    guideSource.value = playlists.value.length - 1
    return
  }
  const next = guideGroup.value + dir
  if (next < 0 || next >= n) return
  guideGroup.value = next
  guideChannel.value = 0
}

/** ↑↓ 在「换源」行里移动；最后一行再往下回到第一条分类 */
function guideMoveSource(dir: number) {
  const n = playlists.value.length
  if (!n) return
  const next = guideSource.value + dir
  if (next < 0) return
  if (next >= n) {
    guideColumn.value = 'group'
    guideGroup.value = 0
    guideChannel.value = 0
    return
  }
  guideSource.value = next
}

/** ←→ 切列：右列 → 左列 / 左列 → 右列（不改变各自列内的光标） */
function guideSwitchColumn(which: 'group' | 'channel') {
  guideColumn.value = which
}

/**
 * 光标移动后把它滚进视野。
 *
 * <p>★ 自己接管导航就得自己滚：tv-nav 的自动滚屏是它几何选焦的一部分，
 *   现在面板项对它不可见（div role=button），没有人替我们滚动 ——
 *   不滚的话按几下 ↓ 光标就走出可见区，用户以为"按键没反应"。
 *
 * <p>block:'nearest'：只在需要时滚，已经可见就不动（避免每按一下都跳动）。
 */
function scrollGuideColIntoView(
  col: 'source' | 'group' | 'channel',
  align: ScrollLogicalPosition = 'nearest',
) {
  const idx =
    col === 'source' ? guideSource.value : col === 'group' ? guideGroup.value : guideChannel.value
  const el = document.querySelector<HTMLElement>(
    `[data-guide-col="${col}"][data-guide-idx="${idx}"]`,
  )
  el?.scrollIntoView({ block: align })
}

function scrollGuideIntoView() {
  if (!showGuide.value) return
  void nextTick(() => {
    scrollGuideColIntoView(guideColumn.value)
  })
}
watch([guideSource, guideGroup, guideChannel, guideColumn], scrollGuideIntoView)

/**
 * OK 键：
 *   光标在左列 → 只是"进右列"（切分类不该顺手选台，用户还没看到台列表）
 *   光标在右列 → 选中该台：关面板 → 切分类 → 播放
 */
async function guideOk() {
  if (guideColumn.value === 'source') {
    const p = playlists.value[guideSource.value]
    if (p) {
      // 切源等于换了一套分组/台，面板里的旧坐标已经失效 → 直接收起
      showGuide.value = false
      await openPlaylist(p)
    }
    return
  }
  if (guideColumn.value === 'group') {
    // 左列按 OK：切到该分类并进右列，光标落在第一台
    const g = groups.value[guideGroup.value]
    if (g) activeKey.value = keyOf(g)
    guideChannel.value = 0
    guideColumn.value = 'channel'
    return
  }
  const g = groups.value[guideGroup.value]
  // 台从 **guideChannels**（光标所在分类）取，不能从 channels（activeKey 分类）——
  // 两个列表在"光标停在别的分类"时不是同一份（见 guideChannels 注释）
  const ch = guideChannels.value[guideChannel.value]
  if (!g || !ch) return
  activeKey.value = keyOf(g)
  showGuide.value = false
  await playChannel(ch)
  flashZap(`${g.group} · ${ch.name} (${guideChannel.value + 1}/${guideChannels.value.length})`)
}

// ---------------------------------------------------------------- 播放态（缓冲 / 手势 / 滚轮 / 调试 / 画中画）

/**
 * 缓冲/起播中。
 *
 * <p>点播那边靠它显示转圈（VideoPlayer 的 buffering）；直播这边**更需要**：
 * 换台后要等 m3u8 拉清单 + 首个分片，慢源 2–5 秒很正常，而原来这段时间
 * 画面全黑、只有一个 2.2 秒就消失的台名提示 —— 用户以为按了没反应。
 *
 * <p>置位靠 video 的 waiting/stalled，复位靠 playing/canplay（成对，缺一边
 * 就会永远转圈或永远不转）。
 */
const buffering = ref(false)
/** 卡住瞬间的 currentTime（timeupdate 自愈用） */
let bufStalledAt = 0
/** timeupdate 最后一次推进时的 currentTime / 时刻（看门狗用） */
let lastAdvanceCt = -1
let lastAdvanceAt = 0
/** 本次卡顿是否已经拉过 hls（避免每 2 秒狂调 startLoad） */
let bufRecovered = false
let bufWatchdogTimer: number | undefined

/** 起播耗时（切源 → 首个 playing），调试面板里看 */
const loadStartedAt = ref(0)
const firstFrameMs = ref<number | null>(null)

/**
 * 音量/亮度手势。
 *
 * <p>**整段对齐点播播放器**（VideoPlayer 的 onStagePointerMove / applySwipe）：
 * 遥控器音量键被旁路、页面上没有别的音量入口；亮度同理，CSS
 * `filter: brightness()` 只能变暗不能变亮，必须走系统。
 *
 * <p>分区与双击一致：左 1/3 亮度、右 1/3 音量、中间不响应（留给单击暂停）。
 */
const swipe = {
  active: false,
  kind: '' as '' | 'volume' | 'brightness',
  startX: 0,
  startY: 0,
  baseValue: 0,
  value: 0,
  reset(x: number) {
    this.active = false
    this.kind = ''
    this.startX = x
    this.startY = 0
  },
}
let pressStart = { x: 0, y: 0 }
/** 竖滑浮层（0–100）。null = 不显示 */
const swipeHint = ref<{ kind: 'volume' | 'brightness'; pct: number; system: boolean } | null>(null)
let swipeHintTimer: number | undefined
/** 无系统亮度能力时的兜底变暗层（0–1） */
const cssDim = ref(0)

function swipeRange(): number {
  const vh = window.innerHeight || 640
  return Math.max(180, vh * 0.55)
}

/**
 * 滑动之后补发的那次 click 不能当成"单击暂停"。
 *
 * <p>拖动手势结束时浏览器会补发 click，而直播的单击语义是播放/暂停 ——
 * 不抑制的话"调完音量画面停了"（点播那边踩过，同款处理）。
 */
let suppressClickUntil = 0
function suppressNextClick() {
  suppressClickUntil = Date.now() + 400
}

function onStagePointerDown(e: PointerEvent) {
  if (!isTouch.value || e.pointerType === 'mouse') return
  pressStart = { x: e.clientX, y: e.clientY }
  swipe.reset(e.clientX)
}

function onStagePointerMove(e: PointerEvent) {
  if (!isTouch.value || e.pointerType === 'mouse') return
  const dx = e.clientX - pressStart.x
  const dyRaw = e.clientY - pressStart.y

  if (!swipe.active) {
    // 判定为竖滑：竖直位移 > 16 且明显大于水平（1.2 倍），避免斜滑误判
    if (Math.abs(dyRaw) < 16 || Math.abs(dyRaw) < Math.abs(dx) * 1.2) return
    const w = window.innerWidth || 360
    const third = w / 3
    if (pressStart.x < third) swipe.kind = 'brightness'
    else if (pressStart.x > w - third) swipe.kind = 'volume'
    else return // 中间 1/3 不做竖滑：留给单击暂停
    swipe.active = true
    swipe.startY = pressStart.y
    swipe.baseValue =
      swipe.kind === 'volume'
        ? (systemVolume() ?? Math.round((video.value?.volume ?? 1) * 100))
        : (systemBrightness() ?? 50)
    /*
     * 每次手势都从"当前值"重新起算。
     * ★ 不重置会踩：判断"值没变就 return"用的是上一手的值，而有些端
     *   systemVolume() 拿不到、退回元素音量（恒为 1=100%），连划两下都
     *   算出 100 → 第二下开始既不调音量也不出提示（2026-11-28 .76 实测）。
     */
    swipe.value = swipe.baseValue
    // 这一下之后浏览器可能补发 click，不能当成"暂停"
    suppressNextClick()
  }

  const delta = ((swipe.startY - e.clientY) / swipeRange()) * 100
  const next = Math.max(0, Math.min(100, Math.round(swipe.baseValue + delta)))
  if (next === swipe.value) {
    // 已经顶到 0 / 100：不重复写值，但手势后仍要给一次提示，
    // 否则用户以为"滑了没反应"（值不变 ≠ 手势没生效）
    if (!swipeHint.value) applySwipe(swipe.kind as 'volume' | 'brightness', next)
    return
  }
  swipe.value = next
  applySwipe(swipe.kind as 'volume' | 'brightness', next)
  e.preventDefault()
}

/**
 * 音量手势期间临时接管音量 UI（见播放页同名函数：常驻挂会把系统音量条也压掉）。
 */
let volHudTimer: number | undefined
function pulseVolumeHud() {
  setIosVolumeHud(true)
  if (volHudTimer) clearTimeout(volHudTimer)
  volHudTimer = window.setTimeout(() => {
    volHudTimer = undefined
    setIosVolumeHud(false)
  }, 1200)
}

/** 把当前手势值写进系统（拿不到系统接口时退回元素音量 / 遮罩压暗） */
function applySwipe(kind: 'volume' | 'brightness', pct: number) {
  if (kind === 'volume') {
    pulseVolumeHud()
    const ok = setSystemVolume(pct)
    if (!ok) {
      // 浏览器/PC：退回元素音量（至少能调，只是不影响系统）
      const v = video.value
      if (v) {
        v.volume = pct / 100
        v.muted = pct === 0
      }
    }
    swipeHint.value = { kind, pct, system: ok }
  } else {
    const ok = setSystemBrightness(pct)
    // 拿不到系统亮度时，用一个遮罩把画面压暗（只能变暗，明说）
    cssDim.value = ok ? 0 : Math.max(0, (50 - pct) / 50)
    swipeHint.value = { kind, pct, system: ok }
  }
  if (swipeHintTimer) window.clearTimeout(swipeHintTimer)
  swipeHintTimer = window.setTimeout(() => (swipeHint.value = null), 900)
}

function endSwipe() {
  if (!swipe.active) return
  // 只在松手时落盘一次：localStorage 每帧写会卡顿
  if (swipe.kind === 'volume') {
    const v = video.value
    if (v) savePrefs({ volume: v.volume, muted: v.muted }, { silent: true })
  }
  swipe.active = false
  swipe.kind = ''
}

/**
 * 滚轮调音量（Web/PC；触屏没有 wheel，用竖滑手势）。
 *
 * <p>归一化与节流同点播：Chrome 是像素（100/格）、Firefox 是行（3/格）、
 * 触控板是小数 —— 不折算的话触控板轻划一下音量就归零。
 */
let wheelAccum = 0
let wheelThrottleAt = 0
let wheelSaveTimer: number | undefined

function onStageWheel(e: WheelEvent) {
  if (isTouch.value) return
  // 面板打开时滚轮留给它自己滚（分类/台列表要能翻页）
  if (showGuide.value) return
  if (error.value) return
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
  if (wheelSaveTimer) window.clearTimeout(wheelSaveTimer)
  wheelSaveTimer = window.setTimeout(() => {
    const v = video.value
    if (v) savePrefs({ volume: v.volume, muted: v.muted }, { silent: true })
  }, 400)
}

/**
 * 画中画。
 *
 * <p>直播切后台/切窗口时 WebView 会暂停播放，PiP 是唯一的"继续看"入口。
 * 部分浏览器/源不支持，静默失败即可（点播同款）。
 */
const pipOn = ref(false)
/** 浏览器/设备是否支持画中画（不支持就不渲染按钮，免得点了没反应） */
const pipSupported = computed(
  () =>
    typeof document !== 'undefined' &&
    !!(document as unknown as { pictureInPictureEnabled?: boolean }).pictureInPictureEnabled,
)

async function togglePiP() {
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
    /* 不支持，静默 */
  }
}

// ---- 调试面板 ----

/**
 * 播放信息调试面板（D 键）。
 *
 * <p>理由与点播一致（VideoPlayer 的调试面板）："黑屏/卡住/花屏"在用户眼里
 * 是同一件事，原因可能是源站慢、分片 404、解码失败、代理超时。面板把
 * 内核层 / HLS 层 / 业务层摊开，一眼看出该查哪层。
 *
 * <p>同时挂 `window.__playerDebug` 供 MCP 断言（pc_eval 直接读）。
 * 直播版去掉了弹幕/字幕/选集，多了"自动跳过坏台"的计数。
 */
const debugOn = ref(false)
const debugSnapshot = ref<Record<string, unknown> | null>(null)
let debugTimer: number | undefined
/** hls.js 分片加载历史（最近 8 条） */
const fragLog = ref<Array<{ t: number; ms: number; level: number; ok: boolean }>>([])
/** 最近一次 hls 错误 */
const lastHlsError = ref('')

function collectDebug() {
  const v = video.value
  if (!v) return null
  const q = (v as HTMLVideoElement & { getVideoPlaybackQuality?: () => VideoPlaybackQuality })
    .getVideoPlaybackQuality?.()
  const h = hls.value
  const levels = h?.levels || []
  const cur = h?.currentLevel ?? -1
  return {
    video: {
      resolution: v.videoWidth && v.videoHeight ? `${v.videoWidth}×${v.videoHeight}` : '—',
      readyState: v.readyState,
      networkState: v.networkState,
      currentTime: +v.currentTime.toFixed(1),
      paused: v.paused,
      muted: v.muted,
      volume: +v.volume.toFixed(2),
      dropped: q?.droppedVideoFrames ?? null,
      totalFrames: q?.totalVideoFrames ?? null,
    },
    hls: h
      ? {
          level: cur,
          levelCount: levels.length,
          bitrate: cur >= 0 && levels[cur] ? levels[cur].bitrate : null,
          levels: levels.slice(0, 6).map((l) => Math.round(l.bitrate / 1000)),
          bandwidthEstimate: h.bandwidthEstimate ? Math.round(h.bandwidthEstimate / 1000) : null,
          frags: fragLog.value.slice(-8),
          lastError: lastHlsError.value || null,
        }
      : null,
    biz: {
      channel: current.value?.name || null,
      line: current.value ? `${currentUrlIndex.value + 1}/${current.value.urls.length}` : null,
      lineSrc: current.value ? current.value.urls[currentUrlIndex.value]?.label || null : null,
      autoSkipCount,
      playlist: playlists.value.length,
      channels: channels.value.length,
      // 多源合并状态：排查"台怎么少了一半/慢源没合进来"时先看这两个
      merged: mergeOn.value,
      mergeBusy: mergeBusy.value,
      firstFrameMs: firstFrameMs.value,
    },
    state: {
      buffering: buffering.value,
      error: error.value || null,
      fullscreen: fullscreen.value || pseudoFs.value,
      landscape: landscape.value,
    },
    at: Date.now(),
  }
}

function refreshDebug(immediate = false) {
  if (!debugOn.value) {
    if (debugTimer) clearInterval(debugTimer)
    debugTimer = undefined
    ;(window as any).__playerDebug = null
    return
  }
  /*
   * ★ 挂到 window 的必须是**原始副本**，不能直接挂 ref 里的对象。
   *
   * 踩过（MuMu 实测）：`debugSnapshot.value = snap` 之后 ref 会把它包成
   * 深度响应式 Proxy，MCP 那边 `JSON.stringify(window.__playerDebug)`
   * 读出来 biz/state 全是 `{}` —— 顶层标量正常，嵌套对象全丢，
   * 排查时以为"这些字段没采到"。
   */
  const snap = collectDebug()
  debugSnapshot.value = snap
  ;(window as any).__playerDebug = snap ? JSON.parse(JSON.stringify(snap)) : null
  if (debugTimer) return
  if (!immediate) return
  debugTimer = window.setInterval(() => {
    if (!debugOn.value) {
      if (debugTimer) clearInterval(debugTimer)
      debugTimer = undefined
      return
    }
    const s = collectDebug()
    debugSnapshot.value = s
    ;(window as any).__playerDebug = s ? JSON.parse(JSON.stringify(s)) : null
  }, 250)
}

function toggleDebug() {
  debugOn.value = !debugOn.value
  if (debugOn.value) refreshDebug(true)
  else refreshDebug()
}

// ---------------------------------------------------------------- 全屏

/**
 * 进/退全屏。
 *
 * <p>机制与点播播放器（VideoPlayer.toggleFullscreen）**保持一致**：
 * 容器级 requestFullscreen，被拒时降级成「容器 fixed 撑满」的伪全屏。
 * 不走 video.webkitEnterFullscreen() —— 那会把播放交给系统原生播放器，
 * DOM 全被盖住，指南遮罩和控制栏一起消失，等于换了个播放器。
 *
 * <p>复用机制而不复用组件：VideoPlayer 有 5900 行，弹幕/续播/片头尾/
 * 选集在直播场景里全是永不触发的死分支（见本文件开头的说明）。
 */
async function toggleFullscreen() {
  const el = stage.value
  if (!el) return
  if (pseudoFs.value || document.fullscreenElement) {
    // 退出：先把壳的方向还回去，再摘全屏（顺序反了会先看到横屏的桌面）
    syncShellOrientation(false)
    pseudoFs.value = false
    if (document.fullscreenElement) await document.exitFullscreen().catch(() => {})
    fullscreen.value = false
    checkOrientation()
    wake()
    return
  }
  try {
    await el.requestFullscreen()
    fullscreen.value = true
    // 只有进了真全屏规范才允许锁方向；失败也无所谓，壳里那条已经转过
    void lockLandscapeQuietly()
    syncShellOrientation(true)
  } catch {
    // 拿不到真全屏：至少让画面铺满（WebView 没实现 onShowCustomView 时必走这里）
    pseudoFs.value = true
    fullscreen.value = true
    // 伪全屏拿不到方向锁，但壳的 setOrientation 照样生效
    void lockLandscapeQuietly()
    syncShellOrientation(true)
  }
  checkOrientation()
  // 进全屏后主动起隐藏计时：鼠标不动时不会有 mousemove，
  // 不 wake 的话控制栏会一直挂在画面上
  wake()
}

/** 双击画面切全屏（与点播播放器一致） */
function onStageDblClick() {
  // 双击时撤掉单击的 220ms 定时器：否则"进全屏"会被紧接着的单击再 toggle 一次
  clearTimeout(clickTimer)
  void toggleFullscreen()
}

/**
 * 单击画面 = 播放/暂停（与点播播放器一致）。
 *
 * <p>点播那边是：单击画面切换播放状态，双击切全屏。直播沿用同一套 ——
 * 鼠标用户点一下暂停、再点一下继续，这是最省事的。
 *
 * <p>双击要等判定窗口（220ms，与点播同值），否则单击的暂停会被双击抢走。
 */
let clickTimer: number | undefined
function onStageClick() {
  // 竖滑手势结束后浏览器会补发一次 click —— 不能当成"暂停"
  if (Date.now() < suppressClickUntil) return
  clearTimeout(clickTimer)
  /*
   * 移动端还没全屏：单击画面 = 直接进全屏横屏（2026-11-25 用户要求）。
   * 直播暂停本来意义就有限（暂停就追不上了），主流直播 App 也是点画面进全屏。
   */
  if (isTouch.value && !isTvEnv() && !fullscreen.value && !pseudoFs.value) {
    clickTimer = window.setTimeout(() => void toggleFullscreen(), 220)
    return
  }
  /*
   * 单击语义按形态分：
   *   横屏 + 有底栏（web / PC） → 切换底栏显隐。
   *   横屏 TV（无底栏） → 播放/暂停。
   *   竖屏手机（已全屏） → 播放/暂停（触屏习惯，与点播一致）。
   * 双击在两种形态下都是切全屏（220ms 判定窗共用）。
   *
   * ★ 2026-11-28 用户口径：点屏幕**不再**呼出「分类-台」—— 那个面板只由
   *   底栏的频道按钮打开。原来横屏点一下就弹面板，底栏可见时更是"想收底栏
   *   却被弹出一个大面板"。遥控器 OK 键那条路径（onKeydown）保留，不经过这里。
   */
  clickTimer = window.setTimeout(() => {
    /*
     * 横屏（电视 / 投屏 / 手机横屏）→ 切换底栏显隐。
     *
     * ★ 2026-11-28 回归修正：先前把横屏 TV 改成"播放/暂停"，用户点一下画面
     *   直播就停了（"点击屏幕就暂停了。。不应该呼出控制栏？"）。直播暂停本就没有
     *   意义，点画面的唯一预期是**呼出/收起控制栏**。底栏因此在横屏也渲染
     *   （见模板注释），否则唤出等于没反应。
     *
     * 竖屏手机保留"点一下暂停"（触屏肌肉记忆，与点播一致）。
     */
    if (landscape.value) {
      if (showCtrl.value) hideCtrl()
      else wake()
      return
    }
    togglePlay()
  }, 220)
}

/** 缓冲态：waiting/stalled 置位，playing/canplay 复位（成对，缺一边就会一直转圈） */
function onVideoWaiting() {
  buffering.value = true
  // 记下卡住瞬间的时间点：timeupdate 里用它判断"画面真的往前走了"
  bufStalledAt = video.value?.currentTime ?? 0
}

/**
 * 缓冲提示的自愈（2026-11-12 投影仪实测）。
 *
 * Chromium 66（Android 9 电视/投影仪）上，MSE 直播从卡顿恢复时**不一定再派发
 * `playing` / `canplay`** —— 实测挂上事件计数器后一次都没来，而画面明明在播。
 * 于是 buffering 永久卡 true：「正在加载 XXX」大盖在正在播放的画面上。
 *
 * 判据改成"画面时间往前走了 50ms 就算缓冲结束"：timeupdate 只在真正推进时
 * 触发（实测 4Hz），卡住时不发，语义上正好等价于"不在缓冲"。
 * 与 VideoPlayer.vue 的弹幕冻结自愈同一套思路。
 */
function onVideoTimeUpdate() {
  const v = video.value
  if (!v) return
  lastAdvanceCt = v.currentTime
  lastAdvanceAt = performance.now()
  bufRecovered = false
  if (buffering.value && v.currentTime > bufStalledAt + 0.05) buffering.value = false
}

/**
 * 缓冲看门狗（2026-11-12 投影仪实测：这台内核的 MediaSource 媒体事件极不可靠）。
 *
 * 两个方向都靠不住：
 *   · 画面在播 → `playing`/`canplay` 不重发 → 提示永远不消失；
 *   · 画面卡死 → `waiting`/`stalled` 也不发 → 用户看到一张静止画面、没有任何提示。
 *
 * 唯一可靠的信号是 currentTime 有没有往前走（timeupdate 只在推进时发，4Hz）。
 * 每 2 秒对一次：3 秒没推进就进缓冲态；8 秒没推进就让 hls.js 重新 startLoad。
 */
function checkBufferWatchdog() {
  const v = video.value
  if (!v) return
  const now = performance.now()
  if (v.paused) {
    /*
     * 用户自己暂停 → 不管。
     * 但"自动播放被策略拒绝"也是 paused（且没有任何事件），#3 ③：
     * 起播 8 秒还没 playing、buffer 停着 → 按坏台换线/跳台，
     * 否则永久转圈（坏台看门狗要求 readyState>=3，这里补上另一条路）。
     */
    if (!autoPlayPending) return
    if (!loadStartedAt.value || now - loadStartedAt.value < 8000) return
    autoPlayPending = false
    if (v.readyState < 3 && v.currentTime <= 0) markBadAndAdvance(activeUrl.value)
    return
  }
  const stalledFor = now - lastAdvanceAt
  if (v.currentTime !== lastAdvanceCt || stalledFor <= 3000) return
  if (!buffering.value) {
    buffering.value = true
    bufStalledAt = v.currentTime
  }
  if (stalledFor > 8000 && !bufRecovered) {
    bufRecovered = true
    try {
      hls.value?.startLoad()
    } catch {
      /* hls 已销毁或未初始化，忽略 */
    }
  }
}

function onVideoPlaying() {
  buffering.value = false
  clearSkipError()
  autoPlayPending = false
  // 真的出画面了 → 清掉这个台的失败记录（#1）
  const name = current.value?.name || ''
  if (name) markLiveOk(name)
  // 首帧耗时（切源 → 第一次 playing）：慢源排查时比"感觉有点慢"有用
  if (firstFrameMs.value == null && loadStartedAt.value) {
    firstFrameMs.value = Date.now() - loadStartedAt.value
  }
}

/**
 * `<video>` 原生错误兜底（#3 ①）。
 *
 * <p>原来模板上根本没挂 `@error`：走到 `v.src = src` 原生 HLS 分支时，
 * MEDIA_ERR_SRC_NOT_SUPPORTED / 混合内容拦截 / HEVC 硬解失败都是**静默**的，
 * `buffering` 永远 true，用户看到永久转圈。现在有任何媒体错误都：
 * 写明原因 → 记一次频道失败 → 立刻按坏台流程换线路/跳台（不等 6 秒）。
 */
function onVideoError() {
  const v = video.value
  if (!v || !activeUrl.value) return
  const code = v.error?.code || 0
  error.value =
    code === 4
      ? '当前设备不支持这个频道的编码，正在换线路…'
      : '直播流加载失败，正在换线路…'
  buffering.value = false
  // markBadAndAdvance 内部会记失败（别在这里重复计一次）
  markBadAndAdvance(activeUrl.value)
}

function onStagePointerUp() {
  if (swipe.active) endSwipe()
}

/**
 * 伪全屏状态广播给 App。
 *
 * 没有标准 API 能查"容器是不是伪全屏"，而 AI 悬浮按钮的 z-[60] 高于
 * 这里的 z-50 —— 不藏就会被压在画面上（docs/47 §3.1）。
 *
 * 顶栏与侧栏的隐藏也在 App.vue 里跟着这个事件走（pseudoFsActive）。
 * ★ 不能在这里用 html 属性 + CSS 开关（data-live-fs）：toggleAttribute
 *   是 Chrome 69 才有的 API，投影仪 Chrome 66 上调用直接抛 TypeError，
 *   属性从没设置上 —— 全屏了顶栏还在（实测踩过）。
 *   Vue 的响应式绑定没有浏览器兼容性问题，统一走事件那条路。
 */
watch(pseudoFs, (v) => {
  window.dispatchEvent(new CustomEvent('meow:pseudofs', { detail: v }))
})

/**
 * 控制栏自动隐藏。
 *
 * ★ 计时规则与点播播放器（VideoPlayer.wake）一致：**从最后一次操作重新计时**，
 *   每次触发路径都调 wake()，wake() 里 clearTimeout 再重排。
 *
 * ★ 静默时长 5 秒（与点播一致；原来直播是 2.6 秒，太短 —— 遥控器用户
 *   按一下 OK 呼出面板，刚抬手就消失了）。
 *
 * ★ 面板开着时不自动收：用户正在里面上下选，5 秒一到把面板连同控制栏一起
 *   收走 = "还没选完就被收走"。面板退出交给返回键。
 */
const CTRL_HIDE_MS = 5000
/** 最后一次用户操作的时间戳（毫秒），控制栏续期判据用 */
let lastUserActionAt = 0

/**
 * 立刻收起底栏（点画面切换显隐用）。
 *
 * <p>不走 5 秒静默计时：用户是**主动**要收起，等到计时到点才收等于没响应。
 */
function hideCtrl() {
  clearTimeout(hideTimer)
  showCtrl.value = false
}

function wake() {
  const wasHidden = !showCtrl.value
  showCtrl.value = true
  lastUserActionAt = Date.now()
  clearTimeout(hideTimer)
  hideTimer = window.setTimeout(() => {
    // 面板开着：不动（用户正在里面操作）
    if (showGuide.value) return
    showCtrl.value = false
  }, CTRL_HIDE_MS)
  // 状态翻转时把焦点落到播放/暂停（与点播一致）：
  // 遥控器用户按一下 OK 出来控制栏，焦点就该在那儿，不用再按一次右键。
  if (wasHidden && isTvEnv()) {
    void nextTick(() => window.setTimeout(focusPlayPauseButton, 60))
  }
}

/**
 * 把焦点框落到播放/暂停按钮。
 *
 * 与点播播放器的 focusPlayPauseButton 同款：用 [data-tv-primary] 定位，
 * 不要走 [data-tv-scope]（点播那边踩过 —— scope 被摘掉后按 scope 找永远
 * 找不到，整个"聚焦播放键"是死代码）。
 */
function focusPlayPauseButton() {
  if (!isTvEnv()) return
  const btn = document.querySelector<HTMLElement>('[data-tv-primary]')
  if (!btn) return
  // 收起状态下按钮是 visibility:hidden，focus 会失败（也说明此刻不该聚焦）
  if (!showCtrl.value) return
  const cur = document.querySelector('.meow-tv-focus')
  if (cur === btn) return
  cur?.classList.remove('meow-tv-focus')
  btn.classList.add('meow-tv-focus')
  try {
    btn.focus({ preventScroll: true })
  } catch {
    /* 老内核不支持 focus 选项 */
  }
}

// ---------------------------------------------------------------- 按键

/**
 * 遥控器/键盘按键。
 *
 * <p>TV 壳把方向键转成 KeyboardEvent 派发（见 tv-nav.js 的 __meowTvKeyOwner），
 * 所以这里监听 keydown 就够了，Web/PC 上也同样生效。
 *
 * <p>★ 语义模型与点播播放器（VideoPlayer.onKeydown）**完全一致**：
 *
 * <pre>
 * 控制栏可见时  → 方向键归 tv-nav（在底栏按钮间移动焦点），播放器不抢
 * 控制栏隐藏时  → 方向键归播放器：↑↓ 换台、←→ 切线路
 * 分类-台面板开 → ←→ 换分类、↑↓ 选台、OK 播放、BACK 关闭
 * </pre>
 *
 * <p>这不是我发明的规则，是点播播放器在所有端都验证过的那套：控制栏是
 * 按钮行，方向键在按钮行里必须有焦点导航语义；控制栏收起后按钮不可见，
 * 方向键就该做"连续操作"（调进度/音量）。直播换成换台/切线路。
 */
function onKeydown(e: KeyboardEvent) {
  // 输入框里打字时不要抢键（顶栏有搜索框）
  const t = e.target as HTMLElement | null
  if (t && /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName)) return

  /*
   * 空态/错误态（没有台在播）：方向键归 tv-nav。
   *
   * 这时页面的方向键语义（换台/切线路）全是空操作，而 tv-nav 要靠方向键
   * 在「返回 / 添加 / 重试 / 去设置」之间移动焦点 —— 页面再拦一道，
   * 遥控器就一个按钮都选不中。`__meowTvKeyOwner` 也是按 current 动态设的，
   * 这里提前 return 与它保持一致。
   */
  if (isTvEnv() && !current.value) return

  /*
   * 面板开着时方向键归面板，语义和看电视一样：
   *   ←→ 换分类、↑↓ 在台里移动、OK 选定、BACK 关掉。
   * 不做 DOM 焦点：TV 壳把方向键交给了页面（__meowTvKeyOwner），
   * 再让焦点系统介入只会出现"按了两下才动"。
   */
  if (showGuide.value) {
    /*
     * 面板打开时**方向键与 OK 全由页面处理**（面板项都是 div role=button，
     * 不进 tv-nav 的候选集，所以它一定把键派发给页面 —— 见 openGuide 注释）。
     *
     * 语义（用户口径：左右两侧上下键只在自己列表滚动，左右键才去对方列表）：
     *   ↑↓ 当前列内移动，到边界停住（不环绕、不跳列）
     *   ←  切到分类列   →  切回台列
     *   OK 右列 = 选台播放；左列 = 切分类并进右列
     *   BACK/Esc 关闭
     */
    switch (e.key) {
      case 'ArrowUp':
        e.preventDefault()
        if (guideColumn.value === 'source') guideMoveSource(-1)
        else if (guideColumn.value === 'group') guideMoveGroup(-1)
        else guideMoveChannel(-1)
        break
      case 'ArrowDown':
        e.preventDefault()
        if (guideColumn.value === 'source') guideMoveSource(1)
        else if (guideColumn.value === 'group') guideMoveGroup(1)
        else guideMoveChannel(1)
        break
      case 'ArrowLeft':
        e.preventDefault()
        if (guideColumn.value === 'source') guideColumn.value = 'group'
        else guideSwitchColumn('group')
        break
      case 'ArrowRight':
        e.preventDefault()
        if (guideColumn.value === 'source') guideSwitchColumn('channel')
        else guideSwitchColumn('channel')
        break
      case 'Enter':
        e.preventDefault()
        void guideOk()
        break
      case 'Escape':
      case 'Backspace':
        e.preventDefault()
        showGuide.value = false
        break
      default:
        return
    }
    wake()
    return
  }

  /*
   * ★ 控制栏可见时，方向键放行给 tv-nav（在底栏按钮间移动焦点）。
   *
   * 与点播播放器同一套判据：控制栏可见 / 面板开着 / 焦点框还在可见元素上
   * —— 任一成立，方向键就不是"连续操作"而是"在按钮间移动"。
   *
   * ★★ 直播与点播的分工不同（这是实测踩出来的）：
   *   点播：↑↓ = 调进度，底栏要一直显示进度条，所以 ↓ 不能被当"唤出底栏"。
   *   直播：↑↓ = 换台（看电视的肌肉记忆），底栏没有进度条要显示。
   *
   *   所以直播的模型是：
   *     底栏隐藏 → ↑↓ 换台、←→ 切线路
   *     底栏可见 → 方向键全部归 tv-nav（在底栏按钮间移动焦点）
   *     底栏靠 **OK 键** 唤出（而不是 ↓）—— ↑↓ 留给换台
   *
   * ★ OK 键在底栏隐藏时 = 呼出「分类-台」（网络电视的惯例：OK 出菜单）。
   *   底栏可见时 = 点当前聚焦的按钮（归 tv-nav）。
   *
   * ★ 以上只适用于**竖屏**（手机形态，底栏存在）。横屏 TV 底栏根本
   *   不渲染（见 landscape 注释），方向键永远归直播，OK 永远呼出面板。
   */
  if (isTvEnv() && !landscape.value && showCtrl.value && !showGuide.value) {
    // OK 键：底栏可见且焦点在底栏内 → 归 tv-nav 点按钮；
    // 焦点还没落到底栏（刚被唤醒）→ 当作"呼出频道列表"
    if (e.key === 'Enter') {
      const f = document.querySelector('.meow-tv-focus')
      const inBar = f && document.querySelector('[data-ctrl-root]')?.contains(f)
      if (!inBar) {
        e.preventDefault()
        openGuide()
        return
      }
    }
    return
  }

  switch (e.key) {
    case 'ArrowUp':
      e.preventDefault()
      // 换台**不** wake：连着按 ↑↓ 扫台时底栏弹出来会挡画面，
      // 还会把方向键语义从"换台"变成"在按钮间移动"。
      zap(-1)
      return
    case 'ArrowDown':
      e.preventDefault()
      zap(1)
      return
    case 'ArrowLeft':
      e.preventDefault()
      cycleLine(-1)
      break
    case 'ArrowRight':
      e.preventDefault()
      cycleLine(1)
      break
    /*
     * 空格 = 播放/暂停，Enter = 呼出「分类-台」。
     *
     * 原来 Enter 是播放/暂停 —— 遥控器上那个 OK 键按下去画面就停了，
     * 而用户的肌肉记忆是"按 OK 出菜单"。播放/暂停让给空格（键盘用户习惯），
     * 遥控器上想看菜单就按 OK。
     */
    case ' ':
    case 'k':
      e.preventDefault()
      togglePlay()
      break
    case 'Enter':
      e.preventDefault()
      openGuide()
      break
    case 'f':
    case 'F':
      void toggleFullscreen()
      break
    // 调试面板（与点播播放器同键位）：HLS 档位/分片耗时/readyState 都在里面
    case 'd':
    case 'D':
      e.preventDefault()
      toggleDebug()
      break
    case 'Escape':
      /*
       * ESC 的优先级（web / PC 的通用直觉，2026-11-12 用户要求）：
       *   真全屏 → **不拦**，交给浏览器自己退（一拦原生退出就没了，而 WebView2 里 exitFullscreen() 在没有用户手势时可能被拒 → 卡在全屏）；
       *   伪全屏 → 自己退；
       *   调试面板开着 → 只关面板；
       *   其余 → 返回上一页（与左上角返回键同一个出口）。
       * 「分类-台」面板的 ESC 在上面 guide 分支里已经处理掉了。
       */
      if (document.fullscreenElement) break
      e.preventDefault()
      if (pseudoFs.value) {
        void toggleFullscreen()
        break
      }
      if (debugOn.value) {
        toggleDebug()
        break
      }
      leaveLive()
      break
    default:
      return
  }
  wake()
}

function togglePlay() {
  const v = video.value
  if (!v) return
  if (v.paused) void v.play().catch(() => {})
  else v.pause()
  wake()
}

function switchUrl(url: string) {
  activeUrl.value = url
  error.value = ''
  // 起播等待态：慢源要 2–5 秒，期间必须有转圈，否则画面全黑像"点了没反应"
  buffering.value = true
  loadStartedAt.value = Date.now()
  firstFrameMs.value = null
  // 看门狗基线：新流从 currentTime=0 起算，起播即卡死也能在 3 秒后出提示
  lastAdvanceCt = 0
  lastAdvanceAt = performance.now()
  bufRecovered = false
  clearTimeout(badTimer)
  const v = video.value
  if (!v) return
  // 直播流同样要走代理：源站普遍校验 Referer，且不允许跨源拉流
  const src = proxyStream(url)
  destroyHls()
  if (/\.m3u8(\?|$)/i.test(url) && Hls.isSupported()) {
    const h = new Hls({ lowLatencyMode: true, maxBufferLength: 20 })
    h.on(Hls.Events.ERROR, (_e, data) => {
      if (data.fatal) {
        error.value = '直播流加载失败，换条线路试试'
        buffering.value = false
        // 记录频道健康并立即换线/跳台（#1/#3：不必再等满 6 秒看门狗）
        markBadAndAdvance(activeUrl.value)
      }
      lastHlsError.value = `${data.type}/${data.details}${data.frag ? ' @' + Math.round(data.frag.start) : ''}`
      // 分片级错误也记一条：能看出"哪个分片在 404"（hls.js 没有独立的 FRAG_LOAD_ERROR 事件，
      // 分片错误是 ERROR 事件 + details='fragLoadError'）
      if (data.frag) {
        fragLog.value.push({ t: Date.now(), ms: -1, level: data.frag.level ?? -1, ok: false })
        if (fragLog.value.length > 8) fragLog.value.shift()
      }
    })
    // 分片耗时进调试面板：判断"卡"是源站慢还是解码慢，看这里最直接
    h.on(Hls.Events.FRAG_LOADED, (_e, data) => {
      const st = data.frag?.stats?.loading
      const ms = st && st.end && st.start ? Math.round(st.end - st.start) : -1
      fragLog.value.push({ t: Date.now(), ms, level: data.frag?.level ?? -1, ok: true })
      if (fragLog.value.length > 8) fragLog.value.shift()
    })
    h.loadSource(src)
    h.attachMedia(v)
    hls.value = h
  } else {
    v.src = src
  }
  /*
   * 恢复用户音量。
   *
   * stopMedia() 停播时静音了（防切台残留出声），换源后不补回来就会
   * "每次换台都变静音，要手动调一下"（点播那边踩过同款）。
   */
  const p = getPrefs()
  v.volume = Math.max(0, Math.min(1, p.volume))
  v.muted = p.muted
  // 这里是点击触发的，属于"用户手势"，可以带声音自动播；
  // 失败（部分浏览器仍拦）就退回静音重试，并把控制权交给用户。
  // autoPlayPending 让缓冲看门狗能识别"被策略拒绝"（#3 ③）。
  autoPlayPending = true
  void v.play().catch(() => {
    v.muted = true
    void v.play().catch(() => {
      /* 仍然失败：缓冲看门狗会在 8 秒后按坏台处理 */
    })
  })
  armBadChannelWatchdog()
}

/**
 * 坏台自动跳：3 秒内没画面就换下一台。
 *
 * <p><b>为什么需要</b>：IPTV 播放列表里死链比例很高（实测一份 920 频道的列表
 * 里大量地址已失效）。用户按 ↓ 换台，撞到坏台就是一片黑，得再按一次——
 * 连续撞几个就会以为"直播坏了"。
 *
 * <p><b>判据是 readyState 而不是 error</b>：死链的表现是"既不报错也不出画面"
 * （hls.js 在重试、或地址返回 200 但不是流），只看 error 永远不触发。
 *
 * <p><b>为什么限制连跳次数</b>：整组频道全坏时会一路往下刷源，
 * 既费流量又让用户看不懂在干什么。跳满 MAX_AUTO_SKIP 就停下报错，
 * 把决定权交回用户。
 */
function armBadChannelWatchdog() {
  clearTimeout(badTimer)
  const myUrl = activeUrl.value
  badTimer = window.setTimeout(() => {
    // 这期间用户已经换到别的台了，别再按旧频道判断
    if (myUrl !== activeUrl.value) return
    const v = video.value
    /*
     * ★ #3 ②：`readyState >= 2` 只代表"拿到了当前帧数据"，**不等于有画面**。
     * 移动端常见形态：分片拉到了、readyState=2、但解码不出画面（canplay 是 3、
     * playing 没来）—— 旧判据直接判"这个台好的"，永远不跳台也不报错。
     * 现在要求 `readyState >= 3`（CAN_FUTURE_DATA）或时间真的往前走了。
     */
    if (!v || v.readyState >= 3 || v.currentTime > 0) {
      autoSkipCount = 0
      lineTryCount = 0
      clearSkipError()
      const name = current.value?.name || ''
      if (name) markLiveOk(name)
      return
    }
    markBadAndAdvance(myUrl)
  }, BAD_CHANNEL_MS)
}

/**
 * 判坏一个台并立即换线/跳台（看门狗超时、`video.error`、自动播放被拒共用）。
 *
 * <p>`myUrl` 是判定时的地址：期间用户已换台就作废，避免把新台一起带跳。
 * 失败计数落盘到 `liveHealth`（#1），列表下次直接灰显 + 排后。
 */
function markBadAndAdvance(myUrl: string) {
  if (myUrl !== activeUrl.value) return
  /*
   * ★ 先在同台内换线路，全试完才换台（2026-11-06）。
   *
   * 多源合并后一个台常有 4~6 条线路（CCTV-1 实测 6 条，来自 2 个源）。
   * 原来第一条线路 3 秒不出画面就直接跳到下一个台 —— 用户得手动
   * 按 ← 回头切线路，多源合并"提高可播率"的收益完全没吃到。
   *
   * 计数分开：`lineTryCount` 管台内换线（每台最多 urls.length-1 次），
   * `autoSkipCount` 管换台（MAX_AUTO_SKIP 次）。混用一个会让"换线尝试"
   * 被算成"跳了 N 个台"，整组频道很快就误报"全都放不出来"。
   */
  const cur = current.value
  if (cur) markLiveFail(cur.name)
  // 这条线路刚判失败：给来源扣分
  bumpSrcScore(currentLineSrc(), -1)
  if (cur && cur.urls.length > 1 && lineTryCount < cur.urls.length - 1) {
    lineTryCount++
    const nextLine = (currentUrlIndex.value + 1) % cur.urls.length
    flashZap(`换线路 ${nextLine + 1}/${cur.urls.length} · ${cur.name}`)
    switchUrl(cur.urls[nextLine].url)
    armBadChannelWatchdog()
    return
  }
  lineTryCount = 0
  if (autoSkipCount >= MAX_AUTO_SKIP) {
    error.value = `连续 ${MAX_AUTO_SKIP} 个频道都放不出来，先停在这里。可能是网络或播放列表过期了。`
    autoSkipCount = 0
    return
  }
  autoSkipCount++
  const list = channels.value
  if (list.length < 2) {
    error.value = '这个频道放不出来'
    return
  }
  const i = channelIndex.value
  const next = i < 0 ? 0 : (i + 1) % list.length
  const ch = list[next]
  if (!ch) return
  flashZap(`跳过坏台 · ${ch.name || '未命名'} (${next + 1}/${list.length})`)
  void playChannel(ch)
}

/**
 * 画面真的出来了，就把"连续跳台"那条提示收掉。
 *
 * <p>踩过（2026-11-05 MuMu 实测）：CCTV-1 五条线路都不通，自动跳到 CCTV-2，
 * 而 CCTV-2 首屏花了 6 秒 —— 3 秒的看门狗已经写过"全都放不出来"，
 * 画面随后才出来，用户看到的就是「正在播 + 红字说全挂了」。
 * 只清这一条，别的错误（流加载失败等）由各自的路径负责。
 */
function clearSkipError() {
  if (error.value.startsWith('连续 ')) error.value = ''
}

function destroyHls() {
  hls.value?.destroy()
  hls.value = null
}

/**
 * 显式停掉媒体元素。
 *
 * <p>★ 不能只靠"节点被移除"：Chromium 对带 MediaSource 的媒体元素有已知
 *   行为——从文档摘掉后不一定立刻停播（规范要求"等待稳定状态再暂停"，
 *   Android WebView 上经常等不到）。点播切集时踩过"旧视频声音还在响"，
 *   直播离开页面是同一类场景（组件卸载、video 摘除）。
 */
function stopMedia() {
  const v = video.value
  if (!v) return
  try {
    v.muted = true
    v.pause()
    v.removeAttribute('src')
    v.load()
  } catch {
    /* 老内核不支持某些调用就跳过，暂停已经做过了 */
  }
}

/** 全屏状态跟着浏览器走（用户按 Esc 或系统手势退出时也要同步） */
function onFsChange() {
  if (!document.fullscreenElement) {
    fullscreen.value = pseudoFs.value
  }
}

/**
 * 遥控器「菜单键」→ 唤出/收起控制栏。
 *
 * <p>★ 为什么用菜单键而不是键盘 m：TV 壳的 KEYCODE_MENU 走
 *   `window.__meowTvMenu()`（见 MainActivity.dispatchKeyEvent），
 *   **不经过 dispatchKeyEvent → 页面收不到 keydown**。菜单键是电视
 *   遥控器上真实存在的那个"菜单"键，比凭空造一个 m 键靠谱。
 *
 * <p>★ 为什么直播需要它：点播的 ↓ 是"唤出控制栏"，直播的 ↓ 是
 *   "换下一个台"（看电视的肌肉记忆），两者冲突 —— 直播不能把 ↓ 拿来
 *   唤出底栏。底栏又没有进度条要显示，↑↓ 全给了换台之后，
 *   就必须另给一个入口。菜单键正好是那个空位。
 *
 * <p>挂成 `__meowTvMenuHook`：tvMenu.ts 的桥会先问页面级钩子，
 *   问不到才走自己的兜底（打开 AI 助手）。
 */
function handleTvMenuHere(): boolean {
  if (showGuide.value) {
    showGuide.value = false
    return true
  }
  if (showCtrl.value) {
    showCtrl.value = false
    return true
  }
  /*
   * 横屏（电视/投影）没有底栏，"唤起控制栏"没有视觉意义 —— 菜单键留给
   * 调试面板。遥控器上只有方向/OK/菜单/返回四个键，而"播放信息"在排查
   * "黑屏/卡住"时是唯一的现场证据（D 键电视上按不到）。
   */
  if (landscape.value) {
    toggleDebug()
    return true
  }
  wake()
  return true
}

onMounted(() => {
  void load()
  // iOS：直播页关掉原生边缘返回手势（横滑/全屏手势会抢），离开时恢复
  setIosNavGesture(false)
  // ★ 挂载就把「5 秒后收起控制栏」的计时排上（2026-11-12）：
  //   原来只有 wake() 被调用过才计时，冷启动进直播页 showCtrl 一直是 true，
  //   底栏和左上角返回键都常驻在画面上。
  wake()
  syncCompactViewport()
  bufWatchdogTimer = window.setInterval(checkBufferWatchdog, 2000)
  window.addEventListener('keydown', onKeydown)
  document.addEventListener('fullscreenchange', onFsChange)
  // 横竖屏跟随（手机旋转时底栏出现/消失，横屏 TV 不受影响）
  window.addEventListener('resize', onWindowResize)
  // 触屏进直播页先尝试横屏（与点播播放器同款：失败就出竖屏提示）
  void tryLandscape().then(() => {
    checkOrientation()
    // 锁横屏成功后方向已变，再确认一次提示状态
    window.setTimeout(checkOrientation, 600)
  })
  // 方向键归属（__meowTvKeyOwner）由上面 watch(current) 维护，这里不再硬设：
  // 空态时必须有台在播才独占，否则遥控器选不中「返回/添加/重试」。
  // 菜单键 → 控制栏（直播没有 ↓ 唤出底栏这条路了）
  ;(window as unknown as { __meowTvMenuHook?: () => boolean }).__meowTvMenuHook =
    handleTvMenuHere
  // TV 壳 BACK 键先问这里：指南 → 全屏 → 才交还给壳
  prevConsumeBack = (window as any).__meowTvConsumeBack ?? null
  ownConsumeBack = () => {
    if (showGuide.value) {
      showGuide.value = false
      return true
    }
    if (pseudoFs.value || document.fullscreenElement) {
      void toggleFullscreen()
      return true
    }
    return false
  }
  // __meowOwn 标记：卸载时据此判断"槽位还是不是自己的"，只撤自己那层
  ;(ownConsumeBack as any).__meowOwn = ownConsumeBack
  ;(window as any).__meowTvConsumeBack = ownConsumeBack
})

onBeforeUnmount(() => {
  // 恢复原生边缘返回手势（直播页里是关掉的）
  setIosNavGesture(true)
  if (volHudTimer) clearTimeout(volHudTimer)
  setIosVolumeHud(false)
  // 先停媒体再拆 hls（见 stopMedia 的说明）
  if (bufWatchdogTimer) { clearInterval(bufWatchdogTimer); bufWatchdogTimer = undefined }
  stopMedia()
  destroyHls()
  // 磁盘缓存是防抖写的：退出时补一笔，否则刚合并完就离开会丢掉这次结果
  if (diskSaveTimer) {
    clearTimeout(diskSaveTimer)
    doSaveDiskCache()
  }
  // 定时器/全局句柄都要收干净：调试面板的 4Hz 轮询在组件销毁后还会跑
  if (debugTimer) clearInterval(debugTimer)
  debugTimer = undefined
  if (wheelSaveTimer) clearTimeout(wheelSaveTimer)
  if (swipeHintTimer) clearTimeout(swipeHintTimer)
  ;(window as any).__playerDebug = null
  /*
   * ★ 卸载时必须复位伪全屏状态：App 的 pseudoFsActive 靠 meow:pseudofs
   *   事件维护，本组件销毁后没人再发事件 → 它会永远停在 true →
   *   首页的 AI 悬浮球（hidden=inFullscreen）一直不显示（实测 76：
   *   从直播页退回首页，FAB 消失了）。
   */
  if (pseudoFs.value) {
    pseudoFs.value = false
    window.dispatchEvent(new CustomEvent('meow:pseudofs', { detail: false }))
  }
  window.removeEventListener('keydown', onKeydown)
  document.removeEventListener('fullscreenchange', onFsChange)
  window.removeEventListener('resize', onWindowResize)
  /*
   * 还原壳的方向：全屏转的（rotatedByFs）和进页面转的（pageLandscape）都要还，
   * 缺一条就会"退出直播页后整个应用还是横的"（点播那边踩过同样的）。
   */
  if (rotatedByFs || pageLandscape) {
    rotatedByFs = false
    pageLandscape = false
    shellSetOrientation('portrait')
  }
  ;(window as unknown as { __meowTvKeyOwner?: boolean }).__meowTvKeyOwner = false
  // 必须清：否则别的页面会继承"菜单键 = 呼出控制栏"这个直播专属行为
  const w = window as unknown as { __meowTvConsumeBack?: any; __meowTvMenuHook?: () => boolean }
  if (w.__meowTvMenuHook === handleTvMenuHere) w.__meowTvMenuHook = undefined
  /*
   * ★ 只撤自己那一层：把上一任（通常是 App.vue 的兜底 handler）还回去。
   *   直接置 null 会把 App 的返回兜底一起抹掉 —— 离开直播页后返回键
   *   就没人接了（点播 VideoPlayer 卸载时也是这么处理的）。
   */
  if (w.__meowTvConsumeBack && w.__meowTvConsumeBack.__meowOwn === ownConsumeBack) {
    w.__meowTvConsumeBack = prevConsumeBack
  }
  clearTimeout(zapTimer)
  clearTimeout(hideTimer)
  clearTimeout(badTimer)
  clearInterval(loadTimer)
})
</script>

<template>
  <!--
    路由级满屏播放器 —— 与点播 /play 完全同构。

    ★ 为什么不再有"页面 + 全屏切换"：App.vue 对 isLive 路由不渲染顶栏
      （与 isPlay 同款），这个页面**生下来就是全屏的**。以前是"页面里嵌
      预览播放器、再切伪全屏"，于是顶栏/侧栏/频道条都要在切换时藏一遍 ——
      每一处都是投影仪 Chrome 66 上的兼容性地雷（toggleAttribute、
      padding-inline 全在那条链路上死过）。没有切换，就没有这些雷。

    ★ 选台入口只剩「分类-台」面板（OK / 控制栏「频道」按钮呼出）：
      经典网络电视就是全屏画面 + 呼出式频道列表，常驻侧栏抢画面空间
      还引入第二套焦点语义。
  -->
  <!--
    ★ 根容器必须 fixed inset-0，不能用 h-full：
      App 根是 min-h-full（min-height 撑高）+ main 是 flex 拉伸——这条链上
      拉伸后的高度对本 WebView 的百分比子级不是 definite，h-full 解析成 0
      （实测 MuMu 76：main h=640 而直播根 h=0，画面 0 高只有声音）。
      点播全屏态正是 fixed inset-0 才躲开这条链，这里照抄。
  -->
  <div class="fixed inset-0 z-10 overflow-hidden bg-black">
    <!--
      空态 / 错误态 / 聚合中的返回入口。
      ★ 没有台在播时屏幕上原本没有任何可点的出口（顶栏被 isLive 隐藏），
        手机没有物理返回键就卡在这一屏。有台在播时不显示（全屏画面里另有
        悬浮退出按钮，见 showExitFsButton）。
    -->
    <button
      v-if="!current && !showGuide"
      class="absolute z-30 grid h-9 w-9 place-items-center rounded-full bg-white/10 text-white/90 backdrop-blur transition hover:bg-white/20 active:scale-95"
      style="left: calc(0.75rem + env(safe-area-inset-left)); top: calc(0.75rem + env(safe-area-inset-top))"
      title="返回"
      aria-label="返回"
      @click="leaveLive"
    >
      <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
        <path d="m15 18-6-6 6-6" />
      </svg>
    </button>

    <!-- 加载态：转圈 + 已等待秒数（/api/live 要 10-12 秒，只有转圈用户分不清在干活还是卡死） -->
    <div v-if="loading" class="absolute inset-0 grid place-items-center">
      <div class="text-center">
        <div class="mx-auto h-9 w-9 animate-spin rounded-full border-2 border-white/20 border-t-accent" />
        <p class="mt-3 text-xs text-ink-3">正在聚合直播源，约需 10 秒…</p>
        <p class="mt-1 text-[11px] text-ink-3">已等待 {{ loadingSeconds }} 秒</p>
      </div>
    </div>

    <div
      v-else-if="!groups.length && !playlists.length"
      class="absolute inset-0 grid place-items-center px-6 text-center text-sm text-ink-3"
    >
      <div>
        还没有可用的直播源。
      <p class="mt-2 text-xs leading-relaxed">
        spider jar 里没有一个类实现
        <code class="rounded bg-field px-1">liveContent</code>，所以直播只能靠 IPTV 播放列表。<br />
        填一个 m3u / txt 地址就能看，保存后会一直留在这里（设置页里也能管理）。
      </p>
      <!-- 输入框就放在空态上：用户走到这里发现是空的，让他再去设置页找是绕路 -->
      <div class="mx-auto mt-4 flex max-w-xl flex-wrap items-center justify-center gap-2">
        <input
          v-model="addUrl"
          placeholder="https://example.com/live.m3u"
          class="min-w-[16rem] max-sm:min-w-[10rem] flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs text-ink outline-none transition focus:border-accent/50"
          @keyup.enter="doAddLive"
        />
        <button
          class="rounded-md bg-accent px-4 py-1.5 text-xs font-medium text-on-accent transition hover:bg-accent-dim disabled:opacity-50"
          :disabled="addBusy"
          @click="doAddLive"
        >
          {{ addBusy ? '校验中…' : '添加' }}
        </button>
      </div>
      <p v-if="addMsg" class="mt-2 text-[11px]" :class="addMsg === '已添加' ? 'text-ok' : 'text-warn'">
        {{ addMsg }}
      </p>
      <div class="mt-4 flex flex-wrap items-center justify-center gap-2">
        <button
          class="rounded-md bg-card px-4 py-1.5 text-sm text-ink transition hover:bg-card-hover"
          @click="load(true)"
        >
          重试
        </button>
        <!-- 空态也放设置入口：文案提到"设置页里也能管理"，那就得能一键过去 -->
        <button
          class="rounded-md bg-card px-4 py-1.5 text-sm text-ink transition hover:bg-card-hover"
          @click="void router.push('/settings')"
        >
          去设置
        </button>
      </div>
      </div>
    </div>

    <!--
      groups 空但配置里有播放列表：自动展开要么在进行中（别把"没结果"说成"没有结果"），
      要么失败了。两种情况都要有话，否则画面区永远空白，用户以为直播坏了。
    -->
    <div v-else-if="!groups.length" class="absolute inset-0 grid place-items-center px-6">
      <div class="max-w-md text-center">
        <template v-if="playlistBusy">
          <div class="mx-auto h-7 w-7 animate-spin rounded-full border-2 border-white/20 border-t-accent" />
          <p class="mt-3 text-xs text-ink-3">源站没有返回频道，正在打开播放列表…</p>
        </template>
        <template v-else>
          <p class="text-sm text-ink-2">配置里的直播源打不开</p>
          <!--
            这里必须把「哪个地址、什么错」摆出来：原来只说"可能也拉不下来了"，
            用户（实测 2026-10-30）面对的是一个早就关掉的测试地址，
            界面上完全看不出该改哪里。
          -->
          <div v-if="playlists.length" class="mt-3 space-y-1 text-left">
            <div
              v-for="p in playlists"
              :key="p.url"
              class="rounded-md bg-field px-2.5 py-1.5 text-[11px]"
            >
              <div class="truncate text-ink-2">{{ p.name }}</div>
              <div class="truncate font-mono text-ink-3">{{ p.url }}</div>
            </div>
          </div>
          <p v-if="error" class="mt-2 text-xs leading-relaxed text-warn">{{ error }}</p>
          <p class="mt-2 text-xs leading-relaxed text-ink-3">
            到设置页「直播源」里换一个地址，或者删掉这条重加。
            spider jar 里没有任何类实现
            <code class="rounded bg-field px-1">liveContent</code>，
            直播只能靠 IPTV 列表，所以这个地址必须是能打开的 m3u / txt。
          </p>
          <div class="mt-4 flex flex-wrap items-center justify-center gap-2">
            <button
              class="rounded-md bg-card px-4 py-1.5 text-sm text-ink transition hover:bg-card-hover"
              data-tv-primary
              @click="load(true)"
            >
              重试
            </button>
            <button
              v-if="playlists.length"
              class="rounded-md bg-accent px-4 py-1.5 text-sm font-semibold text-on-accent transition hover:bg-accent-dim"
              @click="openPlaylist(playlists[0])"
            >
              再试一次打开
            </button>
            <!--
              ★ 文案说"到设置页「直播源」里换一个地址"，那就必须有个能点的入口。
                原来只有重试 —— 而设置按钮在顶栏里，顶栏被 isLive 隐藏了，
                用户根本看不到"设置"在哪，等于指了条看不见的路。
            -->
            <button
              class="rounded-md bg-card px-4 py-1.5 text-sm text-ink transition hover:bg-card-hover"
              @click="void router.push('/settings')"
            >
              去设置
            </button>
          </div>
        </template>
      </div>
    </div>

    <template v-else>
      <!--
        播放舞台。真全屏对象仍是它（requestFullscreen 锁横屏/藏系统栏）；
        失败也不需要降级布局 —— 页面本身就是满屏的，pseudoFs 只剩
        "通知 App 藏 AI 悬浮球"一个职责。
      -->
      <!--
        舞台必须 `touch-action: none`（与点播 VideoPlayer 同款）。
        ★ 没有它时竖滑调音量/亮度是"看运气"的：内核会把按下当成页面滚动，
          派发 pointercancel，pointermove 被取消，手势永远起不来
          （2026-11-28 .76 实测：同一条 adb 滑动手势，有时出音量条、有时不出；
          `guide-resize` 早就因为同一个原因加过同一条规则）。
      -->
      <div
        v-if="current"
        ref="stage"
        style="touch-action: none"
        class="group absolute inset-0 overflow-hidden bg-black"
        @mousemove="wake"
        @click="onStageClick"
        @dblclick="onStageDblClick"
        @pointerdown="onStagePointerDown"
        @pointermove="onStagePointerMove"
        @pointerup="onStagePointerUp"
        @pointercancel="onStagePointerUp"
        @wheel="onStageWheel"
      >
          <video
            ref="video"
            class="h-full w-full bg-black object-contain"
            playsinline
            @contextmenu.prevent
            @error="onVideoError"
            @playing="onVideoPlaying"
            @waiting="onVideoWaiting"
            @stalled="onVideoWaiting"
            @canplay="buffering = false"
            @timeupdate="onVideoTimeUpdate"
            @enterpictureinpicture="pipOn = true"
            @leavepictureinpicture="pipOn = false"
          />

          <!-- 换台提示：画面中上部，眼睛在这儿 -->
          <div
            v-if="zapHint"
            class="pointer-events-none absolute left-1/2 top-[18%] z-20 -translate-x-1/2 rounded-lg bg-black/80 px-5 py-2 text-[length:var(--fs-lg)] font-medium text-white shadow-2xl backdrop-blur"
          >
            {{ zapHint }}
          </div>

          <!--
            缓冲/起播中：换台后要等清单 + 首个分片，慢源 2–5 秒很正常。
            没有这个提示时画面全黑，用户以为按了没反应（2026-11-06 用户反馈）。
          -->
          <div
            v-if="buffering && !error"
            class="pointer-events-none absolute inset-0 z-20 grid place-items-center"
          >
            <div class="flex flex-col items-center gap-3">
              <div class="h-11 w-11 animate-spin rounded-full border-2 border-white/25 border-t-player" />
              <p class="text-xs text-white/70">正在加载{{ current ? ' ' + current.name : '' }}</p>
            </div>
          </div>

          <!--
            竖滑浮层：调音量/亮度时的柱状提示（与点播同款，上滑=变大）。
            数字与兜底说明必须显示 —— 用户要知道"调的是系统还是只有这个播放器"。
          -->
          <div
            v-if="swipeHint"
            data-swipe-hint
            class="pointer-events-none absolute left-1/2 top-1/2 z-30 -translate-x-1/2 -translate-y-1/2"
          >
            <div class="flex flex-col items-center gap-2 rounded-xl bg-black/75 px-5 py-4 text-white shadow-2xl backdrop-blur">
              <svg class="h-6 w-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                <path d="M11 5 6 9H2v6h4l5 4V5z" />
                <path d="M15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14" />
              </svg>
              <div class="relative h-36 w-3 overflow-hidden rounded-full bg-white/25">
                <div
                  class="absolute inset-x-0 bottom-0 rounded-full transition-[height] duration-75"
                  :class="swipeHint.kind === 'brightness' ? 'bg-amber-300' : 'bg-player'"
                  :style="{ height: swipeHint.pct + '%' }"
                />
              </div>
              <span class="font-mono text-sm leading-none">{{ swipeHint.pct }}%</span>
              <span v-if="!swipeHint.system" class="text-xs leading-none text-white/60">
                {{ swipeHint.kind === 'brightness' ? '仅画面' : '仅播放器' }}
              </span>
            </div>
          </div>

          <!-- 无系统亮度能力时的兜底变暗层（CSS 只能变暗、不能变亮） -->
          <div
            v-if="cssDim > 0"
            class="pointer-events-none absolute inset-0 z-10 bg-black"
            :style="{ opacity: cssDim }"
          />

          <!-- 错误提示 -->
          <div
            v-if="error"
            class="pointer-events-none absolute left-1/2 top-[30%] z-20 -translate-x-1/2 rounded-lg bg-hot/85 px-4 py-2 text-sm text-on-hot shadow-xl"
          >
            {{ error }}
          </div>

          <!--
            全屏时的「退出全屏」（手机上唯一的出口）。
            横屏形态底栏不渲染，一旦进了全屏屏幕上就没有按钮可点；
            大屏电视不给（遥控器有返回键，且几何选焦会最先命中它）。
          -->
          <button
            v-if="showExitFsButton"
            class="absolute z-40 grid h-10 w-10 place-items-center rounded-full bg-black/45 text-white shadow-lg backdrop-blur-sm transition active:scale-95 active:opacity-70"
            style="left: calc(0.75rem + env(safe-area-inset-left)); top: calc(0.75rem + env(safe-area-inset-top))"
            aria-label="退出全屏"
            title="退出全屏"
            @click.stop="toggleFullscreen"
          >
            <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <path d="m15 18-6-6 6-6" />
            </svg>
          </button>

          <!--
            播放信息调试面板（D 键 / 底栏「信息」）。
            内核层 / HLS 层 / 业务层三层摊开，"黑屏卡住"才能判断该查哪一层；
            同时挂 window.__playerDebug 供 MCP 直接断言，不必截图。

            ★ 面板挂在舞台上（舞台上有竖滑调音量/亮度的 pointer 处理器），
              事件会冒泡上去 —— 实测在面板里上下滑，音量跟着变（用户反馈）。
              「分类-台」面板不穿透是因为它是舞台的兄弟节点；这里保持 DOM 位置
              （要覆盖在画面上），改用 `.stop` 把交互全部截住，语义对齐。
          -->
          <div
            v-if="debugOn && debugSnapshot"
            data-debug-panel
            class="absolute inset-y-0 right-0 z-50 w-[22rem] max-w-full overflow-y-auto bg-black/88 p-3 font-mono text-[11px] leading-relaxed text-white/85 backdrop-blur"
            @click.stop
            @dblclick.stop
            @pointerdown.stop
            @pointermove.stop
            @wheel.stop
          >
            <div class="mb-2 flex items-center justify-between">
              <span class="text-xs font-semibold text-white">播放信息</span>
              <button
                class="rounded bg-white/10 px-2 py-0.5 text-[11px] transition hover:bg-white/20"
                @click.stop="toggleDebug"
              >
                关闭
              </button>
            </div>
            <pre class="whitespace-pre-wrap break-all">{{ JSON.stringify(debugSnapshot, null, 1) }}</pre>
          </div>

          <!--
            竖屏提示：横屏锁定失败时的兜底（iOS Safari / 网页端不支持锁横屏）。
            文案与点播播放器一致；只占中间一块不能铺满 —— 铺满会盖住底栏，
            手机上连换台/全屏按钮都点不动。
          -->
          <div
            v-if="portraitHint"
            class="pointer-events-none absolute inset-0 z-40 grid place-items-center px-6 text-center"
          >
            <div class="pointer-events-auto max-w-sm rounded-xl bg-black/85 p-4 shadow-2xl backdrop-blur">
              <div class="mx-auto mb-4 h-12 w-12 rotate-90 text-accent">
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
            底部控制栏 —— 竖屏与横屏都渲染。
            ★ 2026-11-28 回归修正：原来横屏 TV 不渲染（`!landscape || !isTvEnv()`），
              点画面"唤出控制栏"就没有任何可见反馈，只能被迫改成暂停（用户：
              「点击屏幕就暂停了。。不应该呼出控制栏？」）。底栏现在横屏也有，
              点画面 = 显隐它；「频道」按钮是打开「分类-台」面板的唯一入口。
            ★ 方向键不会被底栏抢走：播放中页面自己持有 `__meowTvKeyOwner`
              （见 watch(current)），tv-nav 不接管，↑↓ 换台照旧。
            ★ 结构与视觉对齐点播播放器（VideoPlayer 的 meow-ctrl-bar）：
              同样的渐变背景、同样的按钮尺寸（h-9 w-9 圆形）、同样的
              hover 反馈（hover:bg-white/10）、同样的 data-tv-primary 标记
              （TV 焦点落点）。直播没有进度条/音量/倍速，其余按直播语义排。
          -->
          <div
            v-if="!showGuide"
            class="meow-ctrl-bar absolute inset-x-0 bottom-0 z-30 bg-gradient-to-t from-black/85 via-black/50 to-transparent px-3 pb-[calc(0.625rem+var(--tabbar-clear))] pt-12 sm:px-5 sm:pb-4 sm:pt-14"
            :class="showCtrl ? 'opacity-100' : 'pointer-events-none invisible opacity-0'"
            data-ctrl-root
          >
            <div class="flex items-center gap-1.5 sm:gap-2">
              <!-- 播放/暂停：data-tv-primary 是 TV 焦点落点 -->
              <button
                class="grid h-9 w-9 shrink-0 place-items-center rounded-full transition hover:bg-white/10"
                :aria-label="video?.paused ? '播放' : '暂停'"
                data-tv-primary
                @click="togglePlay"
              >
                <svg v-if="video?.paused" class="ml-0.5 h-5 w-5" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M8 5v14l11-7z" />
                </svg>
                <svg v-else class="h-5 w-5" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M6 4h4v16H6zM14 4h4v16h-4z" />
                </svg>
              </button>

              <!-- 台名 + 计数 -->
              <span class="min-w-0 truncate text-xs text-ink sm:text-sm">{{ current.name }}</span>
              <span v-if="channelIndex >= 0" class="shrink-0 font-mono text-[11px] text-ink-3">
                {{ channelIndex + 1 }}/{{ channels.length }}
              </span>

              <!-- 上一个 / 下一个台 -->
              <div class="flex shrink-0 items-center gap-0.5">
                <button
                  class="grid h-9 w-9 place-items-center rounded-full transition enabled:hover:bg-white/10"
                  title="上一个频道"
                  @click="zap(-1)"
                >
                  <svg class="h-[18px] w-[18px]" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M7 5h2v14H7zM19 5v14L9 12z" />
                  </svg>
                </button>
                <button
                  class="grid h-9 w-9 place-items-center rounded-full transition enabled:hover:bg-white/10"
                  title="下一个频道"
                  @click="zap(1)"
                >
                  <svg class="h-[18px] w-[18px]" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M15 5h2v14h-2zM5 5v14l10-7z" />
                  </svg>
                </button>
              </div>

              <!-- 线路（多地址时才有） -->
              <button
                v-if="current.urls.length > 1"
                class="flex shrink-0 items-center gap-1 rounded-md bg-white/10 px-2 py-1 text-xs text-ink transition hover:bg-white/20 backdrop-blur"
                title="切换线路"
                @click.stop="cycleLine(1)"
              >
                <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M4 7h11a3 3 0 0 1 0 6H8a3 3 0 0 0 0 6h12" />
                  <path d="m17 4 3 3-3 3" />
                </svg>
                <span class="hidden max-w-[7rem] truncate sm:inline">
                  线路 {{ currentUrlIndex + 1 }}/{{ current.urls.length }}
                </span>
              </button>

              <div class="min-w-0 flex-1" />

              <!-- 频道列表（分类-台）：遥控器 OK 的效果 -->
              <button
                class="flex shrink-0 items-center gap-1 rounded-md bg-white/10 px-2 py-1.5 text-xs text-ink transition hover:bg-white/20 backdrop-blur"
                :class="showGuide ? 'bg-white/20' : ''"
                title="频道列表"
                @click.stop="openGuide"
              >
                <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <rect x="3" y="4" width="7" height="7" rx="1.5" />
                  <rect x="14" y="4" width="7" height="7" rx="1.5" />
                  <rect x="3" y="13" width="7" height="7" rx="1.5" />
                  <rect x="14" y="13" width="7" height="7" rx="1.5" />
                </svg>
                <span class="hidden sm:inline">频道</span>
              </button>

              <!-- 重播：坏台时最常用（换一条线路重试） -->
              <button
                class="grid h-9 w-9 shrink-0 place-items-center rounded-full transition hover:bg-white/10"
                title="重播"
                @click="replayCurrent"
              >
                <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M3 12a9 9 0 1 0 3-6.7" />
                  <path d="M3 4v5h5" />
                </svg>
              </button>

              <!-- 画中画：切后台/切窗口后还能继续看。不支持就不渲染（点了没反应更差） -->
              <button
                v-if="pipSupported"
                class="grid h-9 w-9 shrink-0 place-items-center rounded-full transition hover:bg-white/10"
                :title="pipOn ? '退出画中画' : '画中画'"
                @click.stop="togglePiP"
              >
                <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <rect x="3" y="5" width="18" height="14" rx="2" />
                  <rect x="12" y="12" width="7" height="5" rx="1" fill="currentColor" />
                </svg>
              </button>

              <!-- 播放信息（调试面板）：电视横屏用菜单键，手机点这里 -->
              <button
                class="grid h-9 w-9 shrink-0 place-items-center rounded-full transition hover:bg-white/10"
                :class="debugOn ? 'bg-white/15' : ''"
                title="播放信息"
                @click.stop="toggleDebug"
              >
                <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="9" />
                  <path d="M12 11v5M12 8h.01" />
                </svg>
              </button>

              <!-- 全屏 -->
              <button
                class="grid h-9 w-9 shrink-0 place-items-center rounded-full transition hover:bg-white/10"
                :title="fullscreen ? '退出全屏' : '全屏'"
                @click.stop="toggleFullscreen"
              >
                <svg v-if="!fullscreen" class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3" />
                </svg>
                <svg v-else class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M8 3v3a2 2 0 0 1-2 2H3m18 0h-3a2 2 0 0 1-2-2V3m0 18v-3a2 2 0 0 1 2-2h3M3 16h3a2 2 0 0 1 2 2v3" />
                </svg>
              </button>
            </div>
          </div>
        </div>

        <!-- 没选台：给个明确出口，别让画面区一片黑 -->
        <div
          v-else
          class="absolute inset-0 grid place-items-center bg-black text-sm text-ink-3"
        >
          <div class="text-center">
            <p>{{ activeGroup ? '选一个频道开始看' : '频道列表还没就绪' }}</p>
            <p class="mt-1 text-[11px] text-ink-3">遥控器按 OK 呼出「分类-台」</p>
          </div>
        </div>
    </template>

    <!--
      频道指南：右侧滑出的分类-台面板（2026-11-12 用户要求从左侧改到右侧）。

      ★ 结构：**左右两块**，左边分类、右边台列表，各自独立滚动。
        之前做成了上下两块（分类在上、台在下），在 1138 宽的视口里
        分类区被压到只剩 10 个分类可见、要在小窗口里挤着滚 —— 分类多时
        根本翻不完。左右分栏才符合"先选分类、再选台"的直觉。

      ★ 位置是**右侧 1/3**，不是居中弹窗：面板贴右缘、左侧画面继续播着，
        用户选台时还能看到画面；左上角的返回按钮也不再被面板压住。

      ★ 用 Teleport 挂到 body：留在本组件内会被外层容器的定位上下文
        影响（顶栏 sticky / 播放器 fixed 全屏都靠它），实测遮罩盖不住
        顶栏、画面透出来。Teleport 到 body 才是真正的"盖住整个视口"。
        ★ 真全屏时 body 上的浮层不渲染，所以目标由 useOverlayHost 动态切换。
    -->
    <Teleport :to="guideOverlayHost">
      <!--
        ★ role="dialog" 而不是 data-tv-scope。

        两者都能让 App.vue 的 onTvBack 把它当模态（先关它、不退出页面），
        但 data-tv-scope 还会触发 tv-nav 的 armScopeIdle：面板开着 **20 秒
        不按键就自动派发 Escape 关掉**（看电视找台时很正常）。这里只要
        "返回键归我"，不要那个自动关闭，所以用原生语义的 role。

        同理也不给面板项加 data-tv-scope 之外的聚焦能力：方向键由页面
        状态机接管（见 onKeydown 的 showGuide 分支）。
      -->
      <div
        v-if="showGuide"
        class="fixed inset-0 z-[999]"
        role="dialog"
        @click="showGuide = false"
      >
        <!--
          播放中的「返回」：放在遮罩内部而不是页面里。
          ★ 踩过：按钮原来在页面根（z-10 的 stacking context）里，即使自己
            z-[1000] 也压不过 body 上的 z-[999] 遮罩 —— 点击先被遮罩的
            @click 接走，表现是"点返回没反应，面板关了但没退出直播页"。
          放在遮罩内部 + @click.stop，才能真正点到它。
        -->
        <!-- div role=button：面板/遮罩里不能有原生可聚焦按钮，
             否则 tv-nav 会把方向键抢去移焦点，页面状态机收不到 -->
        <div
          v-if="showLiveBack"
          role="button"
          class="absolute z-30 grid h-9 w-9 cursor-pointer place-items-center rounded-full bg-white/10 text-white backdrop-blur transition hover:bg-white/20 active:scale-95"
          style="left: calc(0.75rem + env(safe-area-inset-left)); top: calc(0.75rem + env(safe-area-inset-top))"
          aria-label="返回"
          title="返回"
          @click.stop="leaveLive"
        >
          <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <path d="m15 18-6-6 6-6" />
          </svg>
        </div>
        <!-- 右侧 1/3 透明面板：左右两块，各自滚动 -->
        <div
          class="absolute right-3 flex w-1/3 max-sm:w-[86%] min-w-[16rem] max-w-[34rem] overflow-hidden rounded-xl border border-white/10 bg-black/75 shadow-2xl backdrop-blur-md"
          :class="
            landscape
              ? 'top-3 bottom-3'
              : 'top-14 bottom-[calc(0.75rem+env(safe-area-inset-bottom))]'
          "
          data-guide-panel
          :style="guidePanelStyle"
          @click.stop
        >
          <!-- 面板右边缘：拖它调整个面板宽度（手机/桌面；TV 不触发） -->
          <div
            class="guide-resize guide-resize--edge"
            role="separator"
            aria-orientation="vertical"
            aria-label="调整面板宽度"
            @pointerdown.stop.prevent="startGuidePanelResize"
            @click.stop
          />
          <!-- 左块：播放列表（多源时） + 分类 -->
          <div
            class="relative flex w-max min-w-[7.5rem] max-w-[46%] flex-col border-r border-white/10"
            data-guide-side
            :style="guideSideStyle"
          >
            <div class="flex shrink-0 items-center justify-between px-2.5 pb-1 pt-2 text-[11px] text-white/50">
              <span>分类 {{ Math.max(0, groups.findIndex((g) => keyOf(g) === activeKey)) + 1 }}/{{ groups.length }}</span>
              <!-- 刷新：原顶栏按钮移到这里（页面级顶栏已随 isLive 移除） -->
              <div
                role="button"
                class="cursor-pointer rounded px-1.5 py-0.5 text-white/60 transition hover:bg-white/10 hover:text-white"
                title="重新拉取直播源"
                @click="load(true)"
              >
                刷新
              </div>
            </div>
            <!-- 多源时给切换入口（原来在常驻侧栏里） -->
            <div v-if="playlists.length > 1" class="shrink-0 border-b border-white/10 px-4 pb-4">
              <!-- 标清这里是"信号源列表"：只写「换源」两个字时用户不知道
                   它和下面分类/台是两套东西（2026-11-12 用户提问） -->
              <div class="px-2 pb-0.5 text-[10px] text-white/40">信号源</div>
              <div
                v-for="(p, pi) in playlists"
                :key="p.url"
                role="button"
                data-guide-col="source"
                :data-guide-idx="pi"
                :title="`切换到 ${p.name}`"
                class="mb-0.5 flex w-full cursor-pointer items-center justify-between gap-1 rounded px-2 py-1 text-left text-[11px] transition"
                :class="[
                  playlistBusy === p.url ? 'text-white/40' : 'text-white/70 hover:bg-white/10',
                  guideColumn === 'source' && guideSource === pi ? 'guide-cursor' : '',
                ]"
                @click="openPlaylist(p)"
              >
                <span class="truncate">{{ p.name }}</span>
                <span class="shrink-0 opacity-60">{{ playlistBusy === p.url ? '…' : '换源' }}</span>
              </div>
              <!-- 换源失败要在面板里看得到：面板一关，页面级错误只会在没台可播时才显示 -->
              <p v-if="error" class="mx-2 mt-1 rounded bg-warn/15 px-2 py-1 text-[10px] leading-snug text-warn">
                {{ error }}
              </p>
            </div>
            <div class="guide-scroll min-h-0 flex-1 overflow-y-auto px-4 py-4">
              <div
                v-for="(g, gi) in groups"
                :key="keyOf(g)"
                role="button"
                data-guide-col="group"
                :data-guide-idx="gi"
                class="mb-0.5 flex w-full cursor-pointer items-center justify-between gap-1 rounded px-2 py-1.5 text-left text-xs transition"
                :class="[
                  keyOf(g) === activeKey ? 'bg-accent text-on-accent' : 'text-white/80 hover:bg-white/10',
                  guideColumn === 'group' && gi === guideGroup ? 'guide-cursor' : '',
                ]"
                @click="pickGuideGroup(gi)"
              >
                <span class="truncate" :title="g.group">{{ g.group }}</span>
                <span class="shrink-0 text-[10px] opacity-70">{{ uniqueCount(g) }}</span>
              </div>
            </div>
            <!-- 两列之间的拖拽条（鼠标/触屏可调；TV 没有指针事件，天然不触发） -->
            <div
              class="guide-resize guide-resize--split"
              role="separator"
              aria-orientation="vertical"
              aria-label="调整台列表宽度"
              @pointerdown.stop.prevent="startGuideSplitResize"
              @click.stop
            />
          </div>

          <!-- 右块：台列表 -->
          <div class="flex min-h-0 min-w-0 flex-1 flex-col">
            <div class="flex shrink-0 items-baseline gap-2 px-2.5 pb-1 pt-2">
              <span class="truncate text-xs text-white/85">{{ groups[guideGroup]?.group }}</span>
              <span class="shrink-0 text-[11px] text-white/45">{{ guideChannels.length }} 台</span>
            </div>
            <div class="guide-scroll min-h-0 flex-1 overflow-y-auto px-4 py-4">
              <div
                v-for="(ch, i) in guideChannels"
                :key="ch.name"
                role="button"
                data-guide-col="channel"
                :data-guide-idx="i"
                class="mb-0.5 flex w-full cursor-pointer items-center gap-2 rounded px-2 py-1.5 text-left text-xs transition"
                :class="[
                  ch.name === current?.name ? 'bg-accent text-on-accent' : 'text-white/80 hover:bg-white/10',
                  isLiveBad(ch.name) && ch.name !== current?.name ? 'opacity-45' : '',
                  guideColumn === 'channel' && i === guideChannel ? 'guide-cursor' : '',
                ]"
                :title="isLiveBad(ch.name) ? '上次播放失败（24 小时内连续 2 次）' : ch.name"
                @click="pickGuideChannel(i)"
              >
                <span class="w-9 shrink-0 text-[10px] opacity-60">{{ i + 1 }}</span>
                <span class="truncate" :title="ch.name">{{ ch.name }}</span>
                <!-- #1：运行时判过的坏台落盘到 liveHealth，列表直接呈现 -->
                <span
                  v-if="isLiveBad(ch.name)"
                  class="shrink-0 rounded bg-white/10 px-1 text-[10px] text-white/60"
                >上次不可播</span>
                <!--
                  线路数：跨源时用主题色，同源多线用灰色。
                  ★ 不再拼「· M 源」——面板右列只有约 216px，台名一长
                  标记就被挤出可视区（实测截图）；"哪几个源"在底部
                  提示行里有完整分布（`CCTV-1 · iptv-org 2 · Guovin 4`）。
                -->
                <span
                  v-if="ch.urls.length > 1"
                  class="shrink-0 text-[10px]"
                  :class="(ch.srcCount || 1) > 1 ? 'text-accent' : 'opacity-50'"
                >
                  {{ ch.urls.length }} 线
                </span>
              </div>
            </div>
          </div>
        </div>

        <!--
          底部操作提示 + 源聚合统计（原顶栏信息移到这里）。
          ★ 触屏设备整条不渲染（2026-11-12 用户要求）：手机上既没有方向键，
            这条提示只会压在「分类-台」面板的台列表上。源统计是给电视/PC 排查用的。
        -->
        <div
          v-if="!isTouch"
          class="pointer-events-none absolute bottom-4 left-4 max-w-[62%] rounded bg-black/60 px-3 py-1 text-left text-[11px] leading-snug text-white/70"
        >
          <!--
            #1：这两个数是**源级**统计（多少站点返回了频道），完全不碰频道 URL。
            原来写「已试 N 源 · 无直播 M」，用户以为"源健康 = 每个台都能播"。
            频道级可用性看台列表里的「上次不可播」标记。
          -->
          <span v-if="stats" class="mr-2 text-white/45">
            源级：试 {{ stats.tried }} · 空 {{ stats.failed }}
          </span>
          <!-- 多源合并时把"这一台的线路都来自哪几个源"摊开：用户才知道
               「6 线」是哪来的，换线路换的是哪个源 -->
          <span v-if="guideSrcHint" class="mr-2 text-white/60">{{ guideSrcHint }}</span>
          <!--
            方向键说明只给电视/PC：手机上根本没有方向键，占着一行字还挡画面
            （2026-11-12 用户要求竖屏设备不显示这条提示栏）。源统计仍然保留。
          -->
          <span v-if="!isTouch">←→ 换列 · ↑↓ 本列移动 · 分类首行再↑选信号源 · OK 选择 · BACK 关闭</span>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
/*
 * 直播底栏的过渡：与点播播放器（VideoPlayer 的 .meow-ctrl-bar）同款。
 *
 * 为什么直播页要自己写一遍：点播那两条规则在 VideoPlayer 的
 * `<style scoped>` 里，scoped 会给它加属性选择器，直播页的元素匹配不到。
 * 抄一份而不是改 VideoPlayer 改成全局 —— 全局化会让点播那边多一个
 * 跨组件的耦合，收益为零。
 *
 * 收起时先播完淡出（visibility 延迟 280ms），展开时立刻可见 ——
 * 否则底栏会"啪"地消失，没有过渡感。
 */
.meow-ctrl-bar {
  transition: opacity 280ms ease, visibility 0s linear 280ms;
}
.meow-ctrl-bar:not(.invisible) {
  transition: opacity 280ms ease, visibility 0s;
}

/*
 * 「分类-台」面板的光标环。
 *
 * 为什么不直接复用 `.meow-tv-focus`：那个类归 tv-nav 的 highlight() 管，
 * 它每次移动都会扫全文档清掉旧标记。面板项是**故意不可聚焦**的 div
 * （进了 tv-nav 候选集就会被抢键，见模板里的注释），所以只能自己画一份，
 * 但规格必须和 tv-nav 一模一样。
 *
 * ★ 2026-11-06 用户报「分类列表、台列表怎么没有聚焦样式了」——真因是
 *   这里原来写的是纯 inset（1px 白 + 3px accent），而面板打开时光标
 *   默认落在**正在播的台**上，那一行是 `bg-accent` 实心主题色：
 *   accent 内环叠在同色底上 = 看不见，只剩 1px 半透明白线。
 *
 *   tv-nav 在全站用的是「元素内沿 1px 白 + 外面 2px accent + 12px 辉光」，
 *   外环落在元素**外面**的深色背景上——这正是实心按钮上也能看见的原因，
 *   也是 docs/27 定稿的焦点环规格。这里照抄（含 1px 内白线压边）。
 *
 * 代价是外环 + 辉光会伸出元素边界，所以两个列表容器的内边距要 ≥ 14px
 * （这里用 16px：px-4 + py-4，留 2px 余量）：滚动容器是 overflow-y-auto，伸出部分会被裁，
 * 裁掉就变成"焦点框缺一条边"（tv-nav 的注释里也记着这个坑）。
 * 纵向还要 `scroll-padding-block: 16px`——scrollIntoView 默认把元素贴到
 * 滚动端口边缘，会把底部那 14px 内边距"滚掉"，首尾行的环照样被切。
 *
 * `position: relative; z-index: 1` 是给外辉光抢绘制层级：同一个滚动容器里
 * 后面的兄弟元素会盖住前面元素的外阴影，辉光就只剩上半圈。
 */
.guide-cursor {
  position: relative;
  z-index: 1;
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.65),
    0 0 0 2px var(--color-accent, #03dac5),
    0 0 12px 2px var(--color-accent, #03dac5);
}

/*
 * 「分类-台」面板的拖拽条（2026-11-12）。
 *
 * 10px 宽的命中区 + 中间一条 2px 细线：看得见、又好拖（2px 本身太细）。
 * `touch-action: none` 必须给，否则手机上按下会被当成页面滚动，pointermove
 * 直接被内核取消（表现为「拖不动」）。
 */
.guide-resize {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 10px;
  z-index: 5;
  cursor: col-resize;
  touch-action: none;
  /* 拖的时候别选中底下的分类名/台名 */
  user-select: none;
}
.guide-resize::after {
  content: '';
  position: absolute;
  top: 10px;
  bottom: 10px;
  left: 4px;
  width: 2px;
  border-radius: 1px;
  background: rgba(255, 255, 255, 0.16);
  transition: background 0.15s;
}
.guide-resize:hover::after,
.guide-resize:active::after {
  background: var(--color-accent, #03dac5);
}
/*
 * 面板现在 overflow-hidden + 圆角：命中区不能再往外伸（会被裁掉），
 * 改成贴着内沿 10px —— 视觉上仍压在边线上，拖动命中不受影响。
 */
.guide-resize--split {
  right: 0;
}
/* 面板贴右缘：可拖的是左边缘（右边缘固定） */
.guide-resize--edge {
  left: 0;
}

/*
 * 列表滚动区：预留滚动对齐边距，让首尾行的 2px 外环 + 12px 辉光完整可见。
 * 只给纵向（横向由 px-3.5=14px 兜住）。
 */
.guide-scroll {
  scroll-padding-block: 16px;
}
</style>
