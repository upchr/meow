<script setup lang="ts">
import { onMounted, onBeforeUnmount, computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { isTvShell } from '@/compat/shell'
import { getPrefs } from '@/stores/prefs'
import {
  installThemeWatcher,
  setForceDark,
  wallpaperSrc,
  type Wallpaper,
} from '@/stores/theme'
import AppHeader from '@/components/layout/AppHeader.vue'
import DesktopTopChrome from '@/components/layout/DesktopTopChrome.vue'
import ResizeHandles from '@/components/layout/ResizeHandles.vue'
import MobileTabBar from '@/components/layout/MobileTabBar.vue'
import AiPanel from '@/components/ai/AiPanel.vue'
import { isLightWallpaper } from '@/stores/wallpapers'
import TvActionSheet from '@/components/common/TvActionSheet.vue'
import UpdateDialog from '@/components/common/UpdateDialog.vue'
import TrayHintDialog from '@/components/common/TrayHintDialog.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { confirmDialog, settleDialog } from '@/composables/useConfirm'
import { installTvMenuBridge } from '@/stores/tvMenu'
import {
  closeDialog as closeUpdateDialog,
  dialogOpen as updateDialogOpen,
  found as updFound,
  openDialog as openUpdateDialog,
  probeCapability,
  promptPending,
  runCheck as runUpdateCheck,
} from '@/stores/appUpdate'
import { fpkMarkPrompted, fpkPromptDue } from '@/api/fpkUpgrade'
import { isDesktop, listPlugins, tauriInvoke } from '@/api'
import { markChecked, runBackgroundChecks } from '@/stores/updateCenter'

const store = useAppStore()
const route = useRoute()
const router = useRouter()
const desktop = isDesktop()

/**
 * 外观：主题风格 + 背景图。
 *
 * 主题走 `html[data-style]`（见 tokens.css / stores/theme.ts），这里只管背景。
 * 背景是 fixed 铺满的一层，位于内容之下；播放页会自己盖一层 bg-black，
 * 所以不用特殊处理。
 */
const prefs = ref(getPrefs())
let offTheme: (() => void) | undefined
let offTvMenu: (() => void) | undefined

/**
 * 首次关闭提示。
 *
 * Rust 在首次 CloseRequested 里 `window.eval('window.__meowTrayHint?.()')`，
 * 与现有 `window.__meowTool`（AI 工具桥）同一套路：壳只调全局函数，
 * 具体 UI 留在前端，避免 Rust 里拼 HTML。
 */
const trayHintOpen = ref(false)

function showTrayHint() {
  trayHintOpen.value = true
}

/**
 * 启动自动检查的延时。
 *
 * 5 秒 = 让首屏封面和 TV 本机网关先起来（见 scheduleAutoUpdateCheck）。
 * 太短会跟首屏抢连接池，太长用户已经点进详情页了，弹框会打断浏览。
 */
const AUTO_CHECK_DELAY_MS = 5000
let autoUpdateTimer: number | undefined
let bgUpdateTimer: number | undefined

const bgSrc = computed(() => {
  const p = prefs.value
  return p.wallpaper ? wallpaperSrc(p.wallpaper) : ''
})

/**
 * 遮罩（scrim）。
 *
 * ★ 语义已从「压暗」改成「壁纸可见度」（prefs.wallpaperOpacity）：
 *   可见度越高，卡片越透，壁纸越清楚。
 *   旧字段 wallpaperDim 的换算在 prefs.getPrefs 里（1 - d）。
 *
 * ★ 遮罩不能跟着可见度一起变淡到 0。
 *   实测：wallpaper 是暗色照片时，卡片放到 0.64 alpha 后
 *   提示文字的对比度跌到 1.98:1（AA 要求 4.5）。
 *   原因不是卡片太透，而是**卡片背后是原图，明暗不均**——
 *   同一段文字落在亮区可读、落在暗区就糊。
 *   所以遮罩保留一个下限，把壁纸先压成"均匀的基调"，
 *   卡片透出来的是基调而不是细节。可见度只调卡片 alpha，两者分工。
 *
 * ★ 2026-10-31：按**壁纸自身明暗**分档，不能只看当前主题。
 *   原来浅色主题一律用 55%~82% 的白纱（理由是"暗壁纸会让黑字失去依靠"）。
 *   但新增的浅色壁纸（YAVG 224–247）本身就比底色还亮，再盖 68% 白纱
 *   等于把图糊成纯色 —— 实测把「淡彩山水」盖成了 #f4f6fa 一片。
 *   现在：壁纸是浅色的 → 蒙层只压 8%~26%（防高光刺眼即可）；
 *   壁纸是深色但主题是浅色的 → 仍用原来那档厚纱。
 */
const lightWallpaper = computed(() => isLightWallpaper(prefs.value.wallpaper))
/**
 * 当前主题底色的 RGB 三元组（如 `244, 246, 250`）。
 *
 * 为什么用 ref + 事件而不是在 computed 里直接读：`getComputedStyle` 不是
 * 响应式的，主题切换时 computed 不会重算，会拿着旧主题的底色去蒙新主题。
 * 由 `meow:theme` 事件驱动刷新（与 lightMode 同一时机）。
 */
const veilRgb = ref('0, 0, 0')
function syncVeil() {
  const hex = getComputedStyle(document.documentElement)
    .getPropertyValue('--color-base')
    .trim()
  let h = hex.replace('#', '')
  if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2]
  if (h.length !== 6) {
    veilRgb.value = '0, 0, 0'
    return
  }
  const r = parseInt(h.slice(0, 2), 16)
  const g = parseInt(h.slice(2, 4), 16)
  const b = parseInt(h.slice(4, 6), 16)
  veilRgb.value = `${r}, ${g}, ${b}`
}

const scrim = computed(() => {
  if (!lightMode.value) {
    // 深色主题：可见度 0~1 → 遮罩 0.78~0.46（收窄区间，保证下限）
    const k = 0.78 - prefs.value.wallpaperOpacity * 0.32
    return `rgba(0, 0, 0, ${k.toFixed(3)})`
  }
  /*
   * ★ 2026-10-31：这里原来写 `color-mix(in oklab, var(--color-base) N%, transparent)`，
   *   实测算出的 background-color 是 rgba(0,0,0,0) —— **完全透明**，
   *   于是"浅色主题 + 深色壁纸"下壁纸一点没被压，整页卡片标题看不清。
   *   与 .app-header 是同一个根因：color-mix 内联 var() 在这条链路上拿不到
   *   正确值。改用主题实测底色 + rgba() 直接拼 alpha。
   */
  const veil = veilRgb.value
  if (lightWallpaper.value) {
    // 浅色壁纸 + 浅色主题：26%~8%，几乎不盖，让图完整透出来
    const a = (26 - prefs.value.wallpaperOpacity * 18) / 100
    return `rgba(${veil}, ${a.toFixed(3)})`
  }
  // 浅色主题 + 深色壁纸：保持原来的厚纱（55%~82%），否则黑字没依靠
  const a = (82 - prefs.value.wallpaperOpacity * 27) / 100
  return `rgba(${veil}, ${a.toFixed(3)})`
})

const blurPx = computed(() => `${prefs.value.wallpaperBlur}px`)

/**
 * 当前是否浅色（响应式）。
 *
 * 浅色主题下，同一个压暗值会让页面显得"灰蒙蒙压在彩图上"——
 * 因为浅色 UI 的卡片是黑玻璃，本来就靠底色反差。所以浅色时
 * 压暗减半，让背景更像"壁纸"而不是"蒙了一层灰"。
 */
const lightMode = ref(false)
function syncLightMode() {
  lightMode.value = document.documentElement.getAttribute('data-mode') === 'light'
  // 主题底色跟着变，scrim 必须重算（getComputedStyle 不是响应式的）
  syncVeil()
}
/** applyTheme 切完会派发，浅色/深色的 scrim 取向靠它纠正 */
window.addEventListener('meow:theme', syncLightMode)

/** 后端插件是否已安装（未装时引导去设置页下载，而不是让用户反复点重试） */
const backendInstalled = ref(true)

/**
 * 桌面端启动宽限期（2026-11-09）。
 *
 * <p>插件 autostart 与页面首轮 ping 之间有 ~1–2 秒竞态：这段时间里
 * `store.error` 可能已经是 `Failed to fetch`，但后端其实马上就好。
 * 宽限期内只显示「正在启动后端…」的轻提示，不弹「后端未连接」大红卡片。
 */
const bootGraceOver = ref(!desktop)
let bootGraceTimer: number | undefined

/**
 * 后端掉线时定时重试。
 *
 * 为什么需要：用户在设置页装完插件/点「启动」后，首页不会自己变好，
 * 得手动刷新才看得到数据——这会让"装完了但没生效"的错觉非常真实。
 * 用 watch 盯 error：一出现就每 5s 轻量 ping 一次，恢复即自动渲染。
 * 只 ping 不拉数据，代价可忽略。
 */
let retryTimer: number | undefined

/**
 * 后端已经配好才自动重试。
 *
 * 踩过（2026-11-04）：TV 壳里没配后端（localStorage 没有 meow.api）时，
 * error 恒为真 → 每 5 秒 store.boot() 一次 → 首页每 5 秒闪一下
 * （"未连接"卡片消失又出现），看起来像页面在不停刷新。
 * 没地址时重试永远不会成功，必须等用户去设置里配。
 */
function backendConfigured(): boolean {
  try {
    const saved = (localStorage.getItem('meow.api') || '').trim()
    if (saved) return true
    // 浏览器 / 桌面端：同源空串是合法后端（Docker 编排），值得重试
    const w = window as unknown as { meowTv?: unknown }
    return !w.meowTv
  } catch {
    return true
  }
}

watch(
  () => store.error,
  (err) => {
    if (err && !retryTimer && backendConfigured()) {
      retryTimer = window.setInterval(() => {
        void store.boot()
      }, 5000)
    } else if (!err && retryTimer) {
      window.clearInterval(retryTimer)
      retryTimer = undefined
    }
  },
  { immediate: true },
)

async function refreshInstallState() {
  if (!desktop) return
  try {
    const list = await listPlugins()
    const backend = list.find((p) => p.required)
    backendInstalled.value = !!backend?.installed
  } catch {
    // 拿不到就按"已安装"处理，避免误导
    backendInstalled.value = true
  }
}

/**
 * 把「是否有壁纸」+「可见度档位」同步到 <html>。
 *
 * 这不是给这个组件用的 —— 是给 tokens.css 里的覆盖规则用的：
 * 有壁纸时半透明玻璃要按下限转成不透明面板，可见度再决定透多少。
 *
 * 可见度是连续值（0~1），但 CSS 侧只认 4 档（data-wp 0..3）——
 * 改根元素自定义属性不会让已渲染元素的 background-color 重算
 * （见 stores/theme.ts 顶部那段实测），只有属性/选择器变化才会。
 */
function wpLevel(v: number): string {
  if (v < 0.25) return '0'
  if (v < 0.5) return '1'
  if (v < 0.75) return '2'
  return '3'
}

function syncWallpaperAttr(on: boolean) {
  const root = document.documentElement
  root.setAttribute('data-wallpaper', on ? '1' : '0')
  root.setAttribute('data-wp', wpLevel(prefs.value.wallpaperOpacity))
}

onMounted(async () => {
  // 首次关闭提示：壳只负责 eval 这个全局函数，弹层在本组件里
  if (desktop) {
    ;(window as unknown as { __meowTrayHint?: () => void }).__meowTrayHint = showTrayHint
    // 启动宽限期：后端在 bind 之前先别弹「后端未连接」大红卡片
    bootGraceTimer = window.setTimeout(() => {
      bootGraceOver.value = true
    }, 4000)
  }
  // 主题：先应用一次（防闪烁脚本已在 head 里做过，这里兜住 SPA 内跳转
  // 与「恢复默认设置」这类批量写入），再挂监听
  offTheme = installThemeWatcher()
  // 菜单键桥：壳只转成一个 window 函数，页面回答"当前卡片能做什么"
  offTvMenu = installTvMenuBridge()
  syncLightMode()
  syncWallpaperAttr(!!bgSrc.value)
  await refreshInstallState()
  // TV 零配置自启：必须在 boot() 之前，否则第一轮请求打到一个不存在的后端
  bootstrapTvLocal()
  await store.boot()
  // 全屏状态：真全屏有标准事件，伪全屏靠 LiveView 广播（见 syncFullscreen）
  syncFullscreen()
  document.addEventListener('fullscreenchange', syncFullscreen)
  window.addEventListener('meow:pseudofs', onPseudoFs)
  /*
   * 路由级返回无条件安装（2026-11-04）：
   * 它是壳在"没人接管"时的兜底（深链冷启动、任意页面按返回），
   * 与 `__meowTvConsumeBack` 归谁无关，不能跟着一起被跳过。
   */
  if (isTvShell()) installTvRouteBack()
  // 返回键归属：首页要"两次退出"，任何页面有模态层时要"先关模态"（见 syncTvBackOwnership）
  // 走 sync 而不是直接 install：它会先判"是不是电视壳"，浏览器/PC 不需要这套
  syncTvBackOwnership()
  startBackOwnerObserver()
  // 启动自动检查更新：延时且不 await，不能挡住首屏
  scheduleAutoUpdateCheck()
})

/**
 * 启动后延时静默检查更新。
 *
 * ## 为什么要延时
 *
 * `store.boot()` 刚回来时首页正要发第一批封面请求、TV 上还要起本机网关。
 * 这时再叠一条网络请求（GitHub 上要拉 release 列表 + 清单，实测 1–3 秒）
 * 会挤在同一个连接池里，首屏明显变慢。5 秒是实测"首屏画完"之后的空档。
 *
 * ## 为什么不能 await
 *
 * 放到 `onMounted` 的 await 链里会把后面 `syncFullscreen()` /
 * `installTvBackHandler()` 一起推迟 5 秒——那两条都是首屏要用的。
 *
 * ## 静默的含义
 *
 * 出错不写界面（用户没在等结果），日志留在 console。用户主动点设置页的
 * 「检查更新」时才是非静默，那时错误必须显示出来。
 */
function scheduleAutoUpdateCheck() {
  /*
   * 先探"这一端能装哪种更新"，再决定调度哪条通道：
   *   · PC / TV → shell（同步可判）；
   *   · fnOS 托管页面 → fpk（探测 `/api/_shell/*` + `/app-center/*`）；
   *   · 普通浏览器直连 30010 → none（不查、不弹）。
   *
   * 探测是异步的，所以整个调度放进 then 里 —— 不能再像以前那样用
   * 同步的 `canUpdate` 判，否则 fnOS 永远进不来。
   */
  void probeCapability().then((cap) => {
    // 壳：用户可在「更新中心」关掉；默认保留原来的 5 秒静默检查 + 弹框。
    if (cap === 'shell' && getPrefs().autoCheckApp) {
      autoUpdateTimer = window.setTimeout(() => {
        void runUpdateCheck({ silent: true }).then((up) => {
          markChecked('app')
          // 只有"真的发现新版本"才标记；没有更新时完全无感。
          // 这里只置待提示标记，**不直接开弹框**——可能正停在播放页，
          // 弹框会盖住画面。由 tryPromptUpdate 在合适的路由上再开。
          if (up) {
            promptPending.value = true
            tryPromptUpdate()
          }
        })
      }, AUTO_CHECK_DELAY_MS)
    }
    /*
     * fnOS fpk：与壳**同一套**弹框状态机（`runCheck` 内部按 kind 分派）。
     *
     * ★ 不套 `runBackgroundChecks` 的 24h 节流：那条是"只更新状态"的设计，
     *   套到弹框上会导致"检查过就不再提示"。
     * ★ 但要有自己的 24h 同版本冷却（用户点空白/返回关掉后，下次打开别又弹）；
     *   点「稍后」由 `fpkSkipVersion` 长期记住，语义与壳一致。
     */
    if (cap === 'fpk' && getPrefs().autoCheckFpk) {
      autoUpdateTimer = window.setTimeout(() => {
        void runUpdateCheck({ silent: true }).then((up) => {
          if (!up || !fpkPromptDue(up.version)) return
          fpkMarkPrompted(up.version)
          promptPending.value = true
          tryPromptUpdate()
        })
      }, AUTO_CHECK_DELAY_MS)
    }
    // 模块 / 插件 / fpk 状态：24h 节流、只更新状态，不弹框、不自动下载。
    // 30 秒后再跑，避开首屏和上面那条检查。
    //
    // ★ TV 上**不跑**：这三项在 TV 上要去 GitHub 拉模块清单/插件索引，
    //   而 TV 用户多半根本不用运行时模块（可选功能）。开机就为三个用不上的
    //   功能打外网，纯浪费带宽和时间。改成用户打开设置页/更新中心时再跑
    //   （见 SettingsView.onMounted 的 runBackgroundChecks）。
    if (!isTvShell()) {
      bgUpdateTimer = window.setTimeout(() => {
        void runBackgroundChecks()
      }, 30_000)
    }
  })
}

/**
 * 路由允许时把「待提示」变成真弹框。
 *
 * 播放页 / 直播页不弹：那里整屏是画面，四块浮层已经在抢位置，
 * 再盖一个模态会挡住正在看的内容。用户离开这两页后会再被提示。
 */
function tryPromptUpdate() {
  if (!promptPending.value || updateDialogOpen.value) return
  if (!isUpdateFriendlyRoute()) return
  openUpdateDialog()
}

/** 当前路由适不适合弹更新提示（播放/直播页排掉） */
function isUpdateFriendlyRoute(): boolean {
  const name = String(route.name || '')
  return name !== 'play' && name !== 'live'
}

// 路由变化：返回键归属交接 + 把「待提示的更新」在合适的页面弹出来
watch(
  () => route.path,
  (p) => {
    void p
    /*
     * ★ 立刻同步一次，再在下一拍补一次。
     *
     * 时序踩过：`watch(route.path)` 在 App 组件里，比目标页子组件的
     * `onMounted` **先执行**——那一刻 `__meowTvPageBack` 还没注册，
     * 判据算出 false，就把处理器摘了；等到子组件挂载、钩子就位时
     * 已经没人再判一次，返回键仍然走壳的 `goBack()`。
     * 表现就是"设置页按返回直接退到首页"。
     *
     * 下一拍（宏任务）时子组件已挂载完毕，补判一次即可；
     * 两次是幂等的（自己挂的才摘），重复调用无副作用。
     */
    syncTvBackOwnership()
    window.setTimeout(syncTvBackOwnership, 0)
    // 从播放页/直播页离开时补弹：启动检查在那些页面上只记了待提示
    tryPromptUpdate()
  },
)

/*
 * 模态层开合 → 重新判定返回键归属。
 *
 * 用 MutationObserver 而不是在各组件里手动通知：弹层有 8 种（LibraryAddDialog /
 * TileSelect / AiPanel / UpdateDialog / TvActionSheet / 播放页面板 …），
 * 让每个都记得"我打开时要告诉 App 接管返回键"必然会漏一个。
 * 观测 [data-tv-scope] 属性的增删 + 子树变化就够了。
 */
let backOwnerObserver: MutationObserver | null = null

function startBackOwnerObserver() {
  if (backOwnerObserver) return
  backOwnerObserver = new MutationObserver(() => {
    // 只在 TV 壳里有意义；Web/PC 没有返回键接管这回事
    const w = window as unknown as { meowTv?: unknown }
    if (!w.meowTv) return
    syncTvBackOwnership()
  })
  backOwnerObserver.observe(document.body, {
    childList: true,
    subtree: true,
    attributeFilter: ['data-tv-scope'],
  })
  /*
   * 页面注册/注销 `__meowTvPageBack` 时主动通知一次。
   *
   * 光靠 route.path 的 watch 不够：那个 watch 比子组件的 onMounted 早，
   * 判的时候钩子还没挂上（详见 watch 里的注释）。页面挂钩子时喊一声，
   * 这里立刻重判，不依赖任何"等几拍"的猜测。
   */
  window.addEventListener('meow:tv-pageback', syncTvBackOwnership)
}

/**
 * TV 没配后端地址时，自动用内置 jar 起本机执行。
 *
 * 为什么要在启动时做：用户装了 APK 没填后端，首页只会显示「后端未连接 +
 * 去设置」，得手动走「启用本机执行 → 填配置 → 导入」三步才看得到内容。
 * 而 APK 里已经带了 dex jar，这一步完全可以自动完成。
 *
 * 三个前提，缺一不可（否则会覆盖用户在设置页做的选择）：
 *   1. 在 TV 壳里（有原生桥）
 *   2. **用户没配后端地址**（localStorage 里没有 meow.api）
 *   3. 原生侧有这个能力（老版本没有 bootstrapLocal）
 */
function bootstrapTvLocal() {
  try {
    const w = window as unknown as { meowTv?: { bootstrapLocal?: () => string } }
    const b = w.meowTv
    if (!b || typeof b.bootstrapLocal !== 'function') return
    // 已有地址（用户手填或上次自启成功）：尊重它，不覆盖
    if (typeof localStorage !== 'undefined' && localStorage.getItem('meow.api')) return
    const raw = b.bootstrapLocal()
    const o = JSON.parse(raw) as { ok?: boolean; gateway?: string }
    if (o.ok && o.gateway) {
      // 让后续所有请求打到本机网关；不带 scope（这是自动值，不是用户手填）
      localStorage.setItem('meow.api', o.gateway)
    } else {
      // 自启失败是"用户会看到未连接页"的前兆，必须留一条可查的痕迹
      console.warn('[tv-boot] 自启未成功：', raw)
    }
  } catch (e) {
    // 自启失败就退回「后端未连接」提示，不影响其他功能
    console.warn('[tv-boot] 自启异常', e)
  }
}

/**
 * 用户清掉后端地址（回到「同源 /api」）后，也尝试自启。
 *
 * 设置页的「恢复插件托管 / 清空地址」会 removeItem，但那次操作在另一个
 * 组件里，这一层只能靠监听 storage 之外的事件——用 meow:apibase 广播。
 */
function onApiBaseCleared() {
  bootstrapTvLocal()
}
window.addEventListener('meow:apibase', onApiBaseCleared)

// 设置页改完外观会派发 meow:prefs，这里同步到本地 ref 触发重渲染
function onPrefsChanged() {
  prefs.value = getPrefs()
  syncLightMode()
  syncWallpaperAttr(!!bgSrc.value)
}
window.addEventListener('meow:prefs', onPrefsChanged)

// ---------------------------------------------------------------- TV 返回键（首页）
//
// 为什么要在 App 层接：壳的 onBackPressed 会先问 window.__meowTvConsumeBack，
// 只有播放页（VideoPlayer）和直播页（LiveView）注册了它。**首页没注册**，
// 于是壳走 fallbackBack() → 直接 startActivity(SetupActivity) 拉起配置页。
// 用户按一次返回就被丢进配置页，而他只是想退出应用。
//
// 这里按「两段返回」处理（与播放页同一套语义）：
//   第一次 → 提示「再按一次退出」；2.5 秒内再按 → 返回 false 让壳退出。
//
// ★ 不能直接覆盖已有注册：播放页/直播页也挂同一个全局函数，谁后挂谁生效。
//   所以只在「当前路由是首页」时才接管，并在卸载时**只清自己挂的那个**
//   （比对函数引用，避免把播放页刚挂上的清掉）。

/** 「再按一次退出」提示是否可见 */
const exitHint = ref(false)
let exitHintTimer: number | undefined
/** 上一次按返回的时间戳 */
let lastBackAt = 0
/** 2.5 秒内连按才算确认退出（与播放页的窗口一致） */
const EXIT_WINDOW_MS = 2500

/** 当前是否在首页（首页才接管返回键） */
function isHomeRoute(): boolean {
  const p = route.path
  return p === '/' || p === '/index.html' || p === ''
}

function onTvBack(): boolean {
  /*
   * 更新弹框打开时不该走到这里：弹框挂载时会**抢占**
   * `window.__meowTvConsumeBack`（见 UpdateDialog 的 takeOverBackKey），
   * 它比本函数更早拿到返回键。
   *
   * 留这一条兜底是为了"弹框某天改成不抢"的情况——那时至少不会把用户
   * 直接退出应用。关不掉（下载中）也要吃掉，同 UpdateDialog 的理由。
   */
  if (updateDialogOpen.value) {
    closeUpdateDialog()
    return true
  }

  /*
   * ★ 先看"屏幕上有没有模态层"，再看路由。
   *
   * 踩过（实测 v0.9.36，隔离构建逐个路由验）：
   *   首页打开 AI 面板 → back 返回 true（吃掉了，但面板没关）
   *   设置页打开 AI 面板 → back 返回 **false** → 壳 goBack() → **页面被退出**
   *   搜索页同上
   * 因为原来第一句是 `if (!isHomeRoute()) return false` —— 它只认路由，
   * 完全不看页面上有没有打开的东西。弹框、倍速菜单、站点下拉同理。
   *
   * 判据用 [data-tv-scope]（页面/组件声明"这是模态"）+ role="dialog"
   * （原生语义），与 tv-nav 的 activeScope() 同一组标记，不引入第二套约定。
   *
   * 关闭方式：派发一次 Escape。LibraryAddDialog / TileSelect / AiPanel 都已经
   * 监听 keydown 的 Escape（浏览器里实测有效），只是壳的 BACK 键既不派发
   * Escape 也不走 dispatchKeyEvent，所以那些分支在电视上永远触发不到。
   * 这里补上这一环，组件不用各自改。
   *
   * ★ 关掉之后把焦点还给打开它的元素 —— 那是 tv-nav 的职责
   *   （`__meowTvScopeClosed`，见 tv-nav.js 的 scopeReturnEl）。
   *   组件在 v-if 卸载时也会同步调一次，两条路径都指向同一处，重复调用无副作用。
   */
  const modals = document.querySelectorAll('[data-tv-scope], [role="dialog"]')
  for (let i = modals.length - 1; i >= 0; i--) {
    const m = modals[i] as HTMLElement
    // 只认真正可见的：有些组件用 v-show 或 display:none 留着 DOM
    if (!m.offsetParent && getComputedStyle(m).position !== 'fixed') continue
    const r = m.getBoundingClientRect()
    if (r.width < 2 || r.height < 2) continue
    // 让组件自己关：它们监听的是 document 上的 Escape
    document.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }),
    )
    return true
  }

  /*
   * 页面级返回拦截器。
   *
   * 有些页面需要"按返回时先做一件事，而不是离开页面"——典型是设置页：
   * 用户焦点在右栏表单里，按返回期望回到左导航，而不是退出设置页。
   * 这种语义纯靠路由判断做不出来（同一个路由内部有区域之分）。
   *
   * 约定：页面在 onMounted 里设置 `window.__meowTvPageBack`，
   * 返回 true 表示"这次返回我处理了，壳别动"。卸载时必须清掉，
   * 否则别的页面会继承上一个页面的行为。
   */
  const pageBack = (window as unknown as { __meowTvPageBack?: () => boolean })
    .__meowTvPageBack
  if (typeof pageBack === 'function') {
    try {
      if (pageBack()) return true
    } catch {
      /* 页面自己的拦截器出错不该挡住返回 */
    }
  }

  // 不在首页就不接管：交给播放页/直播页，或让壳自己 goBack/退出
  if (!isHomeRoute()) return false

  const now = Date.now()
  if (now - lastBackAt < EXIT_WINDOW_MS) {
    // 第二次：放行，壳会退出应用
    exitHint.value = false
    if (exitHintTimer) window.clearTimeout(exitHintTimer)
    lastBackAt = 0
    return false
  }
  // 第一次：显示提示，吃掉这次返回
  lastBackAt = now
  exitHint.value = true
  if (exitHintTimer) window.clearTimeout(exitHintTimer)
  exitHintTimer = window.setTimeout(() => {
    exitHint.value = false
  }, EXIT_WINDOW_MS)
  return true
}

/** 我们挂上去的那个函数引用（卸载时只清自己） */
let mountedBackHandler: (() => boolean) | null = null

/**
 * 路由级返回：壳在 WebView 历史退不动时会问它。
 *
 * <p>★ 独立于 `installTvBackHandler` 安装（2026-11-04 改）。
 *
 * 原来它写在 `installTvBackHandler()` 里，而那个函数在「`__meowTvConsumeBack`
 * 已被播放页占用」时会**直接 return** —— 于是 `__meowTvRouteBack` 也跟着没了。
 * 冷启动直接落在深链（外部打开详情页）又没模态时，壳拿不到它，
 * 只能走 `web.canGoBack()` / finish，行为不可预期（可能直接退到桌面）。
 * 它只依赖 router，没有任何"被别人占用"的概念，本来就该无条件存在。
 */
function installTvRouteBack() {
  /*
   * 路由级返回：壳在 WebView 历史退不动时会问它。
   *
   * 踩过（实测 MuMu，详情页按返回直接把应用退到桌面）：Vue Router 是
   * hash + pushState，同一次文档加载里 push 再多路由，WebView 的
   * `canGoBack()` 仍可能是 false —— 壳据此判定"到根了"就 finish()。
   *
   * 这里用 router 自己的历史状态回答：
   *   · 不在首页 → back() 并返回 true（让壳别退出）
   *   · 在首页   → 返回 false（壳继续走"再按一次退出"两段语义）
   */
  const W = window as unknown as {
    __meowTvRouteBack?: () => boolean
  }
  W.__meowTvRouteBack = () => {
    try {
      if (isHomeRoute()) return false
      /*
       * 先试 back()，再兜底 push('/')。
       *
       * 实测（MuMu，详情页按返回停在原地）：Vue Router 的**初始导航**用
       * replaceState 落位，所以"从首页点海报进详情"之后，历史里常常只有
       * 详情页这一条 —— back() 无处可退，静默什么都不做。
       * 而壳那边已经因为我们返回 true 而不再 goBack()，于是用户卡在详情页。
       *
       * ★ 兜底判据必须是"路由有没有变"，不能是"到没到首页"。
       *
       * 踩过（用户反馈「电视剧榜单 进入详情页后，返回直接回首页了」）：
       * 原来写的是 `if (!isHomeRoute()) router.push('/')`。
       * 分类页 → 详情页 → 返回时，back() **成功退到了分类页**
       * （`/category/xxx`，它不是首页），于是 260ms 后这个判断成立，
       * 又强推一次首页 —— 用户看到的就是"从详情页直接跳回首页，
       * 中间那一层分类页被跳过了"，而且分类页上的胶囊选中状态也没了。
       *
       * 正确判据：记下 back() 之前的路径，260ms 后**只有路径没变**
       * 才说明 back() 无处可退，这时才兜底回首页。
       */
      const before = route.fullPath
      router.back()
      window.setTimeout(() => {
        try {
          // 路由变了 = back() 生效了，不要再插手
          if (route.fullPath !== before) return
          if (isHomeRoute()) return
          router.push({ name: 'home' })
        } catch {
          /* 回首页失败也不再做别的：壳已经决定不退出应用 */
        }
      }, 260)
      return true
    } catch {
      return false
    }
  }

}

/**
 * 接管 `__meowTvConsumeBack`。
 *
 * <p>★ 改成「包裹上一任」而不是「有人占就跳过」（2026-11-04）。
 *
 * <p>原来这里是 `if (w.__meowTvConsumeBack) return`：播放页/直播页自己挂了
 * 处理器，App 的「先关模态 → 再问 `__meowTvPageBack`」整套就**完全不安装**。
 * 实测代价：播放页的投屏面板、播放信息面板按返回都没人管（要么没反应，
 * 要么直接退集），每加一个浮层都得让页面自己抢一次 consume，漏一个就是一个坑。
 *
 * <p>现在的顺序是「先页面、后 App、最后路由」：
 *   1. `prev()` —— 页面自己的层级（弹幕面板/全屏/控制栏/选集…）优先；
 *   2. 关模态（`[data-tv-scope]` / `[role=dialog]`）；
 *   3. 问 `__meowTvPageBack`（页面级"回上一层"）；
 *   4. 首页"再按一次退出"；
 *   5. 都不管 → 返回 false，交给壳 / `__meowTvRouteBack`。
 */
function installTvBackHandler() {
  const w = window as unknown as { __meowTvConsumeBack?: (() => boolean) | null }
  // 已经是自己装的：幂等返回，避免重复包裹把栈越套越深
  if (mountedBackHandler && w.__meowTvConsumeBack === mountedBackHandler) return
  const prev = w.__meowTvConsumeBack ?? null
  // 上一任若也是我们包的（热更新/重复安装），拆到最里层的页面处理器
  const inner = prev && (prev as { __meowInner?: (() => boolean) | null }).__meowInner !== undefined
    ? ((prev as { __meowInner?: (() => boolean) | null }).__meowInner ?? null)
    : prev
  const wrapped = () => {
    if (inner) {
      try {
        if (inner()) return true
      } catch {
        /* 页面自己的处理器出错不该挡住 App 的兜底 */
      }
    }
    return onTvBack()
  }
  ;(wrapped as { __meowInner?: (() => boolean) | null }).__meowInner = inner
  mountedBackHandler = wrapped
  w.__meowTvConsumeBack = wrapped
}

/**
 * 只在真的用得着的时候接管返回键。
 *
 * ★ 为什么不能只在首页接管。
 *
 * 踩过（MuMu 实测，2026-10-30）：设置页打开「列数上限」下拉，按返回键
 * 期望"关下拉"，实际**整页退回首页**。原因是这里原来只给首页挂处理器
 * （`watch(route.path)` 里 `home ? install : uninstall`），设置页拿到的是
 * `null` —— 壳的 `onBackPressed` 问 `__meowTvConsumeBack` 得到 0，
 * 直接 `goBack()` 把页面退了。弹层开在非首页时，用户永远关不掉它。
 *
 * 判据改成三件事的组合，任一成立就接管：
 *   1. 首页 —— 需要"两次返回退出"的提示；
 *   2. 任何页面上有模态层（`[data-tv-scope]` / `[role=dialog]`）—— 返回先关它；
 *   3. **页面注册了 `__meowTvPageBack`** —— 页面有比"离开"更优先的返回语义。
 * 其它情况仍交还给壳（播放页/直播页自己接管，普通详情页该退回上一页）。
 *
 * ★ 第 3 条不能省（这是我自己引入过的回归）。
 *
 * 2026-10-30 实测：加上第 1、2 条之后，设置页按返回**又直接退回首页**了。
 * 因为设置页的语义是"焦点在右栏表单里 → 回左导航"，它靠的是
 * `window.__meowTvPageBack`；而那一刻页面上没有弹层、也不在首页，
 * 判据算出 false → 摘掉 `__meowTvConsumeBack` → 壳的 `onBackPressed`
 * 拿到 0 → `goBack()`。**页面级钩子根本没机会执行**。
 *
 * 判据必须和"onTvBack 里会读什么"对齐：onTvBack 读 `__meowTvPageBack`，
 * 那"有页面注册了它"就必须是接管条件之一。两者写在两处，很容易只改一边。
 *
 * 代价是挂/摘比原来频繁（每次 DOM 变动都要判一次），所以只在
 * MutationObserver 里做，不挂定时器。
 */
/**
 * 只有在**电视壳**里才需要这一整条返回键链。
 *
 * ★ 为什么要显式判 TV，而不是"挂了也没人调"。
 *
 * 2026-10-30 在无头 Edge 里实测发现：Web 端 `__meowTvRouteBack` 也被挂上了
 * （`typeof` 是 `function`）。虽然浏览器永远不会调它（返回键走的是原生
 * history），本身无害，但：
 *   · 语义上它是"TV 壳的钩子"，出现在 Web 上会误导后来人；
 *   · `installTvBackHandler` 还会连带注册"两次退出"提示等 TV 专属行为。
 *
 * 判据统一走 `compat/shell.ts`（唯一实现），与 `bootstrapTvLocal` /
 * `backOwnerObserver` 用的是同一个标记。
 */

function shouldOwnBackKey(): boolean {
  if (!isTvShell()) return false
  if (isHomeRoute()) return true
  const modals = document.querySelectorAll('[data-tv-scope], [role="dialog"]')
  for (let i = 0; i < modals.length; i++) {
    const m = modals[i] as HTMLElement
    if (!m.offsetParent && getComputedStyle(m).position !== 'fixed') continue
    const r = m.getBoundingClientRect()
    if (r.width < 2 || r.height < 2) continue
    return true
  }
  // 页面级返回拦截器：页面自己说"这次返回我来处理"
  const pb = (window as unknown as { __meowTvPageBack?: unknown }).__meowTvPageBack
  if (typeof pb === 'function') return true
  return false
}

/** 按下述判据决定挂/摘；重复调用无副作用（自己挂的才摘）。 */
function syncTvBackOwnership() {
  if (shouldOwnBackKey()) installTvBackHandler()
  else uninstallTvBackHandler()
}

function uninstallTvBackHandler() {
  const w = window as unknown as { __meowTvConsumeBack?: (() => boolean) | null }
  if (mountedBackHandler && w.__meowTvConsumeBack === mountedBackHandler) {
    // 归还给被我们包住的那一任（播放页/直播页），而不是一律清成 null：
    // 清 null 会让播放页自己的返回链一起消失（2026-11-04 包裹改造的配套）
    const inner = (mountedBackHandler as { __meowInner?: (() => boolean) | null }).__meowInner
    w.__meowTvConsumeBack = inner ?? null
  }
  mountedBackHandler = null
  if (exitHintTimer) window.clearTimeout(exitHintTimer)
  exitHint.value = false
}


onBeforeUnmount(() => {
  // 只清自己挂的钩子，避免覆盖别的会话/组件设置的函数
  const w = window as unknown as { __meowTrayHint?: () => void }
  if (desktop && w.__meowTrayHint === showTrayHint) w.__meowTrayHint = undefined
  if (retryTimer) window.clearInterval(retryTimer)
  if (bootGraceTimer !== undefined) window.clearTimeout(bootGraceTimer)
  // 卸载前取消待执行的自动检查：它 5 秒后才跑，期间 App 可能已被替换
  if (autoUpdateTimer !== undefined) window.clearTimeout(autoUpdateTimer)
  if (bgUpdateTimer !== undefined) window.clearTimeout(bgUpdateTimer)
  offTheme?.()
  offTvMenu?.()
  backOwnerObserver?.disconnect()
  backOwnerObserver = null
  uninstallTvBackHandler()
  window.removeEventListener('meow:prefs', onPrefsChanged)
  window.removeEventListener('meow:theme', syncLightMode)
  window.removeEventListener('meow:apibase', onApiBaseCleared)
  document.removeEventListener('fullscreenchange', syncFullscreen)
  window.removeEventListener('meow:pseudofs', onPseudoFs)
})

const isPlay = computed(() => route.name === 'play' || route.name === 'extPlay')
/** 直播页：与播放页同构的路由级满屏页面（顶栏不渲染、底栏不显示） */
const isLive = computed(() => route.name === 'live')
/**
 * 登录页：没有任何全局导航。
 *
 * <p>公网部署的登录页要是还顶着站点下拉、搜索、设置和底栏，用户会以为
 * "没登录也能用"，点进去又被守卫弹回来（2026-11-19 NAS 实测观感）。
 * 这里按路由名隐藏，不做成"未登录就全站隐藏"——局域网部署根本没有登录页。
 */
const isLogin = computed(() => route.name === 'login')

/*
 * 全屏播放 / 直播页强制暗色：底色是视频画面，浅色主题的浅面板压上去就是白斑。
 * watch 默认 pre-flush，在渲染前改 data-mode，不会闪一下浅色。
 */
watch(
  [isPlay, isLive],
  ([p, l]) => setForceDark(p || l),
  { immediate: true },
)

/**
 * 手机底部标签栏（首页 / 直播 / AI / 设置 / 我的）。
 *
 * <p>只有播放页不显示：那是整屏点播画面，底栏会盖住进度条与控制栏。
 * 直播页保留底栏 —— 它是一级入口，藏掉的话"直播"这格永远不亮，
 * 用户也回不到其它入口；直播自己的控制栏与频道面板用 --tabbar-clear 让位。
 * <p>只在 <640px 出现（组件内部 CSS 控制），桌面/电视不受影响。
 */
const showTabBar = computed(() => !isPlay.value && !isLogin.value)

/**
 * 是否有元素处于全屏（真全屏或伪全屏）。
 *
 * AI 悬浮按钮在伪全屏时必须一并隐藏：直播页的伪全屏是 z-50，低于 FAB 的
 * z-[60]，不藏就会被压在上面（docs/47 §3.1）。真全屏时浏览器只渲染全屏
 * 元素及其后代，FAB 自然不可见，所以两种都统一按"别显示"处理。
 */
const pseudoFsActive = ref(false)
const inFullscreen = ref(false)

function syncFullscreen() {
  inFullscreen.value = !!document.fullscreenElement || pseudoFsActive.value
}

function onPseudoFs(e: Event) {
  pseudoFsActive.value = !!(e as CustomEvent).detail
  syncFullscreen()
}

/**
 * 桌面端启动宽限期内的轻提示（替代大红卡片）。
 *
 * <p>仅首页；播放页/设置页不显示，避免打扰。
 */
const showStartupTip = computed(() => {
  if (!desktop || bootGraceOver.value || !store.error) return false
  if (isPlay.value) return false
  return route.name === 'home' || !route.name
})

/**
 * 是否显示「后端未连接」大卡片。
 *
 * 只在**首页**且确实拿不到数据时显示。其他页面（设置/历史/搜索）必须能正常进入——
 * 用户去设置页就是为了装插件，如果卡片把路由顶掉，按钮看起来就是坏的。
 *
 * 另外：设置页自己知道后端状态，不需要这层提示。
 */
const showOfflineCard = computed(() => {
  if (isPlay.value) return false
  if (!store.error) return false
  // 启动宽限期内先显示轻提示，别把「正在 bind」误报成「后端未连接」
  if (showStartupTip.value) return false
  // 只有首页（以及没有 name 的兜底路由）才让大卡片占位
  return route.name === 'home' || !route.name
})

/** 跳设置页。用 push 而不是 replace，用户能返回 */
function goSettings() {
  void router.push({ name: 'settings' })
}

/*
 * 投屏接收：别的设备投到本机时自动打开接收页。
 *
 * 只在用户开过投屏面板后启用（`meow.cast.receiver=1`），不默认监听；
 * 会话/令牌都在接收端后端，这里只负责"看到 pending 会话就进页面"。
 */
/*
 * 首投确认：TV 壳的接收端不再弹原生 AlertDialog，而是调用这里的全局函数，
 * 复用应用内 confirmDialog（主题一致、遥控方向键可用）。原生只等
 * `meowTv.castConfirmResult`；超时会调 `__meowCastCancel` 把框收掉。
 *
 * 注册放在 onMounted：ConfirmDialog 已经挂载，watch(open) 才会执行
 * “焦点落在取消 + 300ms 防误触”那套逻辑。
 */
type CastConfirmWindow = Window & {
  __meowCastConfirm?: (sender: string, id: string) => void
  __meowCastCancel?: (id: string) => void
  meowTv?: { castConfirmResult?: (id: string, allow: boolean) => void }
}
const castWin = window as CastConfirmWindow
let activeCastConfirmId = ''

let castPollTimer: number | undefined
onMounted(() => {
  castWin.__meowCastConfirm = async (sender, id) => {
    activeCastConfirmId = id
    const ok = await confirmDialog(`${sender || '局域网设备'} 想投到这台设备`, {
      title: '投屏请求',
      okText: '允许',
      cancelText: '拒绝',
    })
    if (activeCastConfirmId === id) activeCastConfirmId = ''
    castWin.meowTv?.castConfirmResult?.(id, ok)
  }
  castWin.__meowCastCancel = (id) => {
    if (activeCastConfirmId !== id) return
    activeCastConfirmId = ''
    settleDialog(false)
  }
  castPollTimer = window.setInterval(async () => {
    if (localStorage.getItem('meow.cast.receiver') !== '1') return
    if (route.name === 'extPlay') return
    const { receiverPending } = await import('@/api/library')
    const p = await receiverPending()
    if (!p?.sessionId || !p.media?.url || p.state === 'idle') return
    void router.push({
      name: 'extPlay',
      query: {
        sessionId: p.sessionId,
        url: p.media.url,
        title: p.media.title || '投屏播放',
        danmakuUrl: p.media.danmakuUrl || '',
        position: String(p.position || 0),
      },
    })
  }, 4000)
})
onBeforeUnmount(() => {
  if (castPollTimer) window.clearInterval(castPollTimer)
  delete castWin.__meowCastConfirm
  delete castWin.__meowCastCancel
})

/**
 * PC 快捷键（B5，2026-11-18）。
 *
 * - Ctrl/Cmd+F：去搜索页。**只在 Tauri 壳生效** —— Web/Docker 上这是
 *   浏览器的「页内查找」，拦截会破坏原生功能。
 * - F11：窗口全屏（自绘标题栏后没有系统菜单入口）。
 * - Ctrl/Cmd+0：WebView2 缩放复位。
 * - Esc：返回上一页。输入框内、有 dialog、播放/直播页、首页都不处理。
 */
let stopDesktopShortcuts: (() => void) | undefined
onMounted(() => {
  if (!desktop) return
  const onKey = (e: KeyboardEvent) => {
    const t = e.target as HTMLElement | null
    const inField =
      !!t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)
    const mod = e.ctrlKey || e.metaKey

    if (mod && !e.shiftKey && !e.altKey && (e.key === 'f' || e.key === 'F')) {
      if (inField) return
      e.preventDefault()
      if (route.name !== 'search') void router.push({ name: 'search' })
      return
    }
    if (mod && !e.shiftKey && !e.altKey && e.key === '0') {
      e.preventDefault()
      tauriInvoke('desktop_window_reset_zoom').catch(() => {})
      return
    }
    if (e.key === 'F11') {
      e.preventDefault()
      tauriInvoke('desktop_window_toggle_fullscreen').catch(() => {})
      return
    }
    if (e.key === 'Escape' && !mod && !e.altKey) {
      if (inField) return
      // 弹框自己处理 Esc；这里再关一次会连页面一起退掉
      if (document.querySelector('[role="dialog"]')) return
      if (isPlay.value || isLive.value) return
      if (route.name === 'home') return
      e.preventDefault()
      router.back()
    }
  }
  window.addEventListener('keydown', onKey)
  stopDesktopShortcuts = () => window.removeEventListener('keydown', onKey)
})
onBeforeUnmount(() => stopDesktopShortcuts?.())
</script>

<template>
  <!--
    背景层：fixed 铺满，z-0，在内容之下。

    ★ 根容器**不能有不透明底色**。之前这里写死 bg-base，把 z-0 的背景层
      整块盖住了（实测：背景层尺寸/层级都对，就是看不见）。
      没有背景图时改由 body 的 --color-base 托底（tokens.css 里已有），
      所以不需要在这个容器上再铺一次。

    ★ 不能改成 -z-10：负 z 子层会画到父级背景之后，同样看不见。

    ★ 有壁纸时整站的「面」会换一套值（tokens.css 的
      `html[data-wallpaper='1']` 覆盖块）：玻璃层转不透明，
      正文不再直接压在图片上。这里只需要挂上 data 属性 + 这层 scrim。
  -->
  <div class="relative flex min-h-full flex-col">
    <div
      v-if="bgSrc"
      class="pointer-events-none fixed inset-0 z-0 bg-cover bg-center bg-no-repeat"
      :style="{ backgroundImage: `url(${bgSrc})`, filter: `blur(${blurPx})` }"
      aria-hidden="true"
    >
      <div class="absolute inset-0" :style="{ background: scrim }" />
    </div>

    <!--
      直播页与播放页同构：路由级满屏页面，顶栏不渲染。
      ★ 原来直播靠 pseudoFsActive 动态藏顶栏 —— 那是在给"页面里嵌预览播放器
        再切全屏"的错误结构打补丁，而且投影仪 Chrome 66 上这条链路死过
        （toggleAttribute 是 Chrome 69+ API）。现在直播页本身就像 /play 一样
        生下来就是满屏播放器，不存在"切全屏后顶栏还在"这个问题。
    -->
    <!-- B4：无边框窗口的缩放热区（仅 Tauri 壳） -->
    <ResizeHandles v-if="desktop" />
    <!-- 播放/直播页没有 AppHeader：给一个隐形拖拽条 + 悬停红黄绿 -->
    <DesktopTopChrome v-if="desktop && (isPlay || isLive)" />

    <AppHeader v-if="!isPlay && !isLive && !isLogin" class="relative z-20" />

    <!--
      AI 悬浮入口（docs/47 §三）。放在 App 级别而不是各页面：
      三端同一份，页面不用各自挂。播放页内部会自己隐藏（那里有四块浮层
      在抢位，再放一个只会互相遮挡），伪全屏时也隐藏。

      /ai 独立页同样要隐藏：那一页自己渲染了 inline 模式的同一组件，
      不藏就会同时出现"浮层 + 页面主体"两份对话。
    -->
    <AiPanel :hidden="inFullscreen || route.name === 'ai' || isLogin" />

    <!-- 菜单键上下文面板：卡片上的 hover 操作在遥控器上够不到，统一走这里 -->
    <TvActionSheet />

    <!--
      应用更新弹框（启动自动检查 + 设置页手动检查共用同一份 store 状态）。

      挂在 App 级而不是某个页面：启动检查是全局行为，用户在首页、搜索页、
      设置页都可能被通知到。播放页/直播页由 tryPromptUpdate 推迟，
      不在这两页弹（整屏是画面，模态会挡住内容）。
    -->
    <UpdateDialog v-if="updateDialogOpen && !isLogin" @close="closeUpdateDialog()" />

    <!-- 首次点 X 的一次性提示：Rust 的 window.__meowTrayHint 打开它 -->
    <TrayHintDialog v-if="trayHintOpen" @close="trayHintOpen = false" />

    <!--
      TV 首页「再按一次退出」提示。

      为什么放画面中央而不是边角：遥控器用户按返回时视线在画面中间，
      底栏/角落的提示很容易错过（AGENTS 第六节第 3 条：反馈要出现在
      用户正在看的地方）。播放页的同类提示也是居中的，保持一致。

      用 pointer-events-none：它只是个提示，不能挡住下面的卡片点击。
    -->
    <div
      v-if="exitHint"
      class="pointer-events-none fixed inset-x-0 bottom-24 z-[70] flex justify-center"
    >
      <div
        class="rounded-full bg-black/75 px-6 py-3 text-[length:var(--fs-lg)] font-medium text-white shadow-lg backdrop-blur-sm"
      >
        再按一次返回退出应用
      </div>
    </div>

    <main
      class="relative z-10 flex-1"
      :class="showTabBar ? 'has-tabbar' : ''"
    >
      <!--
        后端离线提示只在「首页 + 确实没数据」这个组合下占据主区域。
        
        之前的写法是 <div v-if="store.error"> … <main v-else>，导致 error 为真时
        RouterView 整个不渲染 —— 点「设置」URL 变了但页面还是这块红框，
        用户会以为按钮坏了。路由是应用的骨架，不能被状态提示替换掉。
      -->
      <!--
        桌面端启动宽限期：后端还在 bind，只给一条轻提示。
        实测冷启动 ~0.7 秒，但插件 autostart 与首轮 ping 有竞态；
        这段时间弹「后端未连接」大红卡片会让人以为坏了（2026-11-09）。
      -->
      <div v-if="showStartupTip" class="mx-auto mt-16 max-w-md text-center">
        <div class="mx-auto h-6 w-6 animate-spin rounded-full border-2 border-white/20 border-t-accent" />
        <p class="mt-3 text-sm text-ink-3">正在启动后端…</p>
      </div>

      <!-- 有壁纸时用实底：hot/10 只有 10% 红，压在图片上几乎看不出边界，
           而且正文会直接落在壁纸上 -->
      <div
        v-if="showOfflineCard"
        class="mx-auto mt-16 max-w-lg rounded-lg border border-hot/30 px-6 py-5 text-center"
        :class="bgSrc ? 'bg-card-sunken' : 'bg-hot/10'"
      >
        <div class="text-lg font-semibold text-hot">后端未连接</div>
        <p class="mt-2 text-sm text-ink-2">{{ store.error }}</p>

        <!-- 桌面端且没装后端插件：首次使用最常见的情况，直接给下载入口 -->
        <template v-if="desktop && !backendInstalled">
          <p class="mt-3 text-xs text-ink-3">
            应用本身不含后端，需要先装「影视后端」插件（约 37MB）。步骤：
            设置页 → 配插件源 → 点「下载」；装完会自动启动，不用重启应用。
          </p>
          <button
            class="mt-4 rounded-md bg-accent px-4 py-2 text-sm font-medium text-on-accent hover:bg-accent-dim"
            @click="goSettings"
          >
            去设置页安装
          </button>
        </template>

        <template v-else>
          <p v-if="desktop" class="mt-3 text-xs text-ink-3">
            桌面版后端是随应用启动的本机服务，正常情况下不需要手动操作。
            如果反复失败，请到「设置 → 本机插件」查看后端状态。
          </p>
          <p v-else class="mt-3 text-xs text-ink-3">
            请在设置页填写后端地址，或确认服务已启动。
          </p>
          <button
            class="mt-4 rounded-md bg-accent px-4 py-2 text-sm font-medium text-on-accent hover:bg-accent-dim"
            @click="store.boot()"
          >
            重试
          </button>
          <!-- ★ Web/TV 都要有「去设置」：只给「重试」时用户不知道去哪儿改地址。
               TV 尤其重要——遥控器用户没有别的路可走，而设置页里
               有「局域网自动发现 / 扫码填写」的弹层。 -->
          <button
            v-if="!desktop || !backendInstalled"
            class="ml-2 mt-4 rounded-md bg-card px-4 py-2 text-sm hover:bg-card-hover"
            @click="goSettings"
          >
            去设置页填写
          </button>
          <button
            v-if="desktop"
            class="ml-2 mt-4 rounded-md bg-card px-4 py-2 text-sm hover:bg-card-hover"
            @click="goSettings"
          >
            检查插件
          </button>
        </template>
      </div>

      <!-- 路由始终渲染：设置/历史/搜索等页面在无后端时也要能进 -->
      <!--
        key 的选择直接决定"切集会不会重建页面"。

        原来用 `route.fullPath`：任何 query 变化（换集、换线路、搜索改词）
        都会把整个页面组件销毁重建。播放页的后果最严重 ——
        `<video>` 元素被销毁 → **全屏状态丢失**，于是"看完一集自动下一集
        时退出全屏"（PC 端实测）。页面重建还会让封面闪一下、进度条归零重来。

        改用 `route.path`：同一路由下只变 query 时组件复用，
        PlayView 内部已经有 `watch(route.fullPath)` 重新解析 id/ep 并 load()，
        所以换集逻辑照常走，只是不再重建 DOM。

        跨路由（首页↔详情↔播放）仍然重建，这是对的 —— 那些是不同页面。
      -->
      <RouterView v-slot="{ Component }">
        <!--
          ext-play 是个"无状态接收页"：换会话时 query 会整组换掉，key 必须带
          fullPath 才会重建。只按 path 的话组件被复用，sessionId/url/title
          还是上一场的，连投会一直播旧片（实测）。
        -->
        <component :is="Component" :key="route.name === 'extPlay' ? route.fullPath : route.path" />
      </RouterView>
    </main>

    <!-- 手机底部一级入口（首页 / AI / 设置）。桌面与电视不显示，见组件内 CSS。 -->
    <MobileTabBar v-if="showTabBar" />

    <!-- 全局确认 / 提示弹框：替代原生 confirm/alert（见 composables/useConfirm.ts） -->
    <ConfirmDialog />
  </div>
</template>
