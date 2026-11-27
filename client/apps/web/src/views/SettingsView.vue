<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TilePanel from '@/components/common/TilePanel.vue'
import TileSelect from '@/components/common/TileSelect.vue'
import ToggleSwitch from '@/components/common/ToggleSwitch.vue'
import AboutDialog from '@/components/common/AboutDialog.vue'
import { useCardCols } from '@/composables/useCardCols'
import WallpaperPicker from '@/components/common/WallpaperPicker.vue'
import { THEME_MODES, THEME_STYLES, resolveMode } from '@/stores/theme'
// iOS 壳：后端是 App 进程内的 JVM 网关，地址由壳写死 —— 设置页要能认出它，
// 把「后端地址」那行换成只读展示（改了就等于把 App 指向别人的后端）。
import { currentShell, isIosShell, iosShellInfo } from '@/compat/shell'
import { uaText } from '@/compat/ua'
import { fetchServerLogs } from '@/api/logs'
import { detectFpkShell } from '@/api/fpkUpgrade'
import type { TileAction } from '@/components/common/tile'
import {
  apiBase,
  bootInfo,
  clearSearchCache,
  fetchPolicy,
  fetchSearchCacheStats,
  fetchLanDebug,
  isDesktop,
  isLoopbackUrl,
  pluginBase,
  readManualApi,
  resolveDns,
  savePolicy,
  setLanDebug,
  setRuntimeBase,
  type SearchCacheStats,
} from '@/api'
import type {
  InstalledInfo,
  InstallProgress,
  PluginIndex,
  PluginInfo,
  RemotePlugin,
  SourceItem,
  SourceList,
} from '@/api'
import {
  addPluginSource,
  cancelInstall,
  fetchInstallProgress,
  fetchInstalledPlugins,
  fetchPluginIndex,
  fetchPluginSources,
  importPluginManifest,
  importPluginZip,
  installPlugin,
  listPlugins,
  refreshDesktopBase,
  removePluginSource,
  selectPluginSource,
  setPluginEnabled,
  startPlugin as callPluginStart,
  stopPlugin as callPluginStop,
  testPluginSource,
  uninstallPlugin,
  updatePluginSource,
} from '@/api'
import { LLM_PRESETS, chat, llmConfig, saveLlmConfig } from '@/ai/llm'
import { scanLanBackends, type LanHit } from '@/utils/lanScanWeb'
import { useAppStore } from '@/stores/app'
import { listHistory } from '@/stores/history'
import {
  addLiveSource,
  discardConfigBackup,
  fetchLives,
  fetchLivePlaylist,
  getConfigBackup,
  getImportJobState,
  getCachedDepot,
  fetchPendingSites,
  getLastConfigImport,
  importConfig,
  probeConfigDepot,
  removeLiveSource,
  resetBuiltinLives,
  restoreConfigBackup,
  tauriInvoke,
  type ConfigBackupInfo,
  type ConfigImportDiff,
  type ConfigImportResult,
  type ConfigSkippedSite,
  type DepotEntry,
  type ImportConflict,
  type ImportMode,
  type LiveSource,
  type PendingSite,
} from '@/api'
// 应用自身的在线更新：复用插件清单（packages.meow-pc / meow-tv），不另造协议。
// 清单读取与下载都在原生做（GitHub Release 资产无 CORS 头，页面 XHR 必被拦）。
//
// ★ 检查/下载/进度状态全部来自 stores/appUpdate：启动弹框和这一页共用同一份，
//   各留一份会出现「弹框说发现新版本、这里仍显示已是最新」。
import {
  clearUpdateCache,
  cancelAppUpdateCheck,
  currentVersion as currentAppVersion,
  downloadCandidates,
  fetchTextViaNativeJob,
  getBackupUpdateSource,
  getUpdateSource,
  getUpdateToken,
  isNewer,
  setBackupUpdateSource,
  setUpdateSource,
  APP_PACKAGE_ID,
  APP_PC_ID,
  APP_TV_ID,
  formatNoteLines,
} from '@/api/appUpdate'
import {
  busy as updBusy,
  canUpdate as updCanUpdate,
  checked as updChecked,
  checking as updChecking,
  closeDialog as closeUpdDialog,
  dialogOpen as updDialogOpen,
  error as updError,
  found as updFound,
  openDialog as openUpdDialog,
  percent as updPercent,
  phase as updPhase,
  platform as updPlatform,
  ready as updReady,
  runCancel as runUpdCancel,
  runCheck as runUpdCheck,
  runDownloadAndInstall as runUpdInstall,
  stage as updStage,
} from '@/stores/appUpdate'
// TV 上开机不跑模块/插件/fpk 的后台检查（App.vue 的说明），改到这里：
// 用户真的进设置页/更新中心了再查一次（内部仍有 24h 节流）。
import {
  checkPayload,
  hasTvModule,
  isTvShell,
  payloadCurrent,
  payloadInstalled,
  payloadLatest,
  refreshPayloadState,
  runBackgroundChecks,
} from '@/stores/updateCenter'
// 片库的配置与增删改已挪到「片库」页本身（设置页只管应用行为，
// 不管内容来源）；这里只剩离线缓存的列表与删除。
import { controlCache, invalidateTranscodeProbe, listCache } from '@/api/library'
import { fetchHealthState, probeSites, resetHealth, setSitesDisabled } from '@/api/health'
import type { Site } from '@/api/types'
import { listFavorites } from '@/stores/favorites'
import {
  DEFAULTS,
  applyBackup,
  getPrefs,
  getWebdav,
  makeBackup,
  savePrefs,
  saveWebdav,
  setDanmakuDensity,
  setDanmakuTuning,
  setPref,
  webdavPing,
  type Backup,
  type Prefs,
  type WebdavConfig,
} from '@/stores/prefs'
import {
  createSnapshot,
  deleteSnapshot,
  getSyncStatus,
  listSnapshots,
  getLastPullError,
  onSyncStatus,
  pruneSnapshotsTo,
  pullHistory,
  pullSearchHistory,
  pushHistory,
  pushSearchHistory,
  restoreSnapshot,
  upload,
  withSyncStatus,
  type Snapshot,
  type SyncStatus,
} from '@/stores/sync'
import { clearSearchHistory, listSearchHistory } from '@/stores/searchHistory'
import { iosNotify, tvInlineInput } from '@/api/nativeBridge'
import { qrSvg } from '@/utils/qr'
import FpkUpgradePanel from '@/components/common/FpkUpgradePanel.vue'
import UpdateCenter from '@/components/common/UpdateCenter.vue'
import GitAccelPanel from '@/components/common/GitAccelPanel.vue'
import { alertDialog, confirmDialog } from '@/composables/useConfirm'

const store = useAppStore()
const router = useRouter()
const route = useRoute()
const prefs = ref<Prefs>(getPrefs())

function update<K extends keyof Prefs>(key: K, value: Prefs[K]) {
  prefs.value = setPref(key, value)
}

/**
 * 跟随全局偏好变更。
 *
 * 首次关闭弹层会写 `closeToTray`（隐藏/退出两种选择），此时设置页通常
 * 藏在托盘后面。不监听 `meow:prefs` 的话，重新显示后开关还停在旧值，
 * 看起来像"弹层的选择没生效"。
 */
function onPrefsChanged(e: Event) {
  const next = (e as CustomEvent<Prefs>).detail
  if (next) prefs.value = next
}

/** 「再次显示关闭提示」的操作反馈（与分区说明同一档字） */
const trayHintMsg = ref('')

/**
 * 「桌面端行为」区的重置动作。
 *
 * 只清 `tray-hint-seen`：`closeToTray` 是另一个开关，重置提示不该顺手改它——
 * 用户可能只是想再看一次提示，并不想改当前关闭行为。
 */

async function resetTrayHint() {
  try {
    await tauriInvoke('desktop_reset_tray_hint')
    trayHintMsg.value = '已重置：下次点 X 会重新弹出提示'
  } catch (e: unknown) {
    trayHintMsg.value = `重置失败：${e instanceof Error ? e.message : String(e)}`
  }
}

/**
 * 首页在本机实际会显示几列。
 * 设置项只是上限，窄屏会自动减少 —— 不把结果直接写出来，
 * 用户会以为设了 8 却只有 4 是坏了。
 */
const { cols: localCols, update: refreshCols } = useCardCols({
  max: () => prefs.value.homeCols,
})
watch(
  () => prefs.value.homeCols,
  () => refreshCols(),
)

/** 切密度档位：一次性写好时长 + 区域 */
function pickDensity(d: string | number) {
  if (d === 'custom') return // 自定义只作为状态显示，不可主动选择
  prefs.value = setDanmakuDensity(String(d) as Prefs['danmakuDensity'])
}

/** 手改速度/区域：档位自动降级为 custom，避免显示与实际不符 */
function tuneDanmaku(patch: { duration?: number; area?: number }) {
  prefs.value = setDanmakuTuning(patch)
}

// ---------------------------------------------------------------- 后端

/**
 * 桌面端的后端地址有两种来源，展示上必须区分开，否则用户会疑惑
 * "我明明装的是插件，为什么地址是空的 / 同源"：
 *   - 插件托管：地址由插件协商的端口决定（输入框显示实际值，可改）
 *   - 手填远端：用户显式保存过地址（scope=desktop）
 *
 * ★ 输入框**不能只读**（2026-11-20 修）。
 *   原来插件托管时设成 readonly 并隐藏「保存」，于是「关掉插件 → 扫描到局域网
 *   后端 → 点结果填入 → 没有保存按钮 → 重连仍用旧地址 → 无连接」整条路断掉。
 */
const manualApi = ref(readManualApi())
const apiUrl = ref(manualApi.value || apiBase() || '')
const apiPlaceholder = computed(() =>
  desktop && !manualApi.value ? '留空 = 由「影视后端」插件自动分配' : 'http://127.0.0.1:9979',
)
/** 桌面端没手填地址时是插件托管，改地址等于"改连远端"，需要明说 */
const apiManagedByPlugin = computed(() => desktop && !manualApi.value)
/** 保存/重连的结果提示（地址没变、不整页刷新时才有机会看到） */
const apiMsg = ref('')
const apiMsgOk = ref(true)
const apiBusy = ref(false)
/** 用户改过输入框但还没保存：此时不做自动同步，避免把正在敲的内容冲掉 */
const apiDirty = ref(false)

/** 把输入框同步成「当前真正在用的地址」：手填优先，否则插件协商值 */
function syncApiUrlFromRuntime() {
  manualApi.value = readManualApi()
  apiUrl.value = manualApi.value || apiBase() || ''
  apiDirty.value = false
}

/**
 * 插件晚于页面就绪时，把真实地址填进输入框。
 *
 * 只在**用户没在编辑**时同步：store.online 翻转时可能正好有人刚敲了半截地址，
 * 无条件同步会把它冲掉。
 */
watch(
  () => store.online,
  (on) => {
    if (on && desktop && !manualApi.value && !apiDirty.value) syncApiUrlFromRuntime()
  },
)

/**
 * 后端插件启动/启用后调用：清掉手填的外部地址。
 *
 * 不清理的后果是"看着连本机、实际连远端"：resolveBase 里非回环手填地址的
 * 优先级高于插件地址，用户点了「启动」以为切回本机，请求却仍打到之前填的 NAS。
 */
function clearManualApiForPlugin() {
  if (!readManualApi()) return
  setRuntimeBase(null)
  manualApi.value = ''
}

/** 探一下目标后端的 /api/health（4s 超时），用于保存前拦掉死地址 */
async function probeBackendHealth(url: string): Promise<boolean> {
  const ctrl = new AbortController()
  const timer = window.setTimeout(() => ctrl.abort(), 4000)
  try {
    const r = await fetch(url.replace(/\/$/, '') + '/api/health', {
      signal: ctrl.signal,
      cache: 'no-store',
    })
    if (!r.ok) return false
    const j = (await r.json()) as { ok?: boolean }
    return j?.ok === true
  } catch {
    return false
  } finally {
    window.clearTimeout(timer)
  }
}

/**
 * 保存地址并重连 —— **保存与重连合成一个按钮**（2026-11-20）。
 *
 * 原来「保存」在输入框右边、重连按钮在下面一行，两者互不相干：用户填完
 * 地址直接点重连，用的仍是旧 base，报「无连接」。现在点这个按钮一定
 * 先把当前输入写进去，再连。
 *
 * 地址真的变了才整页刷新：后端级设置（policy/缓存配额等）是 onMounted 从
 * 上一个后端读的，不刷新会出现"显示 A、改的是 B"（见 switchToGateway）。
 * 地址没变就只重连，避免白白丢页面状态；死地址在刷新前先探活，免得用户
 * 连扫描结果都一起丢掉。
 */
async function saveAndReconnect() {
  apiMsg.value = ''
  const v = apiUrl.value.trim()
  const url = v && v !== '同源 /api' ? v : ''
  /*
   * iOS（2026-11-27，对齐 TV）：地址真源是**壳**（UserDefaults），不是 localStorage。
   * resolveBase 在 iOS 只认桥注入的 meowIos.apiBase，写 localStorage 会被桥覆盖
   * （等于没改）。所以要壳落盘 + location.reload() 让桥重新注入。
   */
  if (iosBackendConfigurable) {
    const apply = (window as unknown as {
      meowIos?: { setApiBackend?: (u: string) => boolean }
    }).meowIos?.setApiBackend
    if (typeof apply !== 'function') return
    apiBusy.value = true
    try {
      if (url) {
        // 同页探活：死地址写进去以后刷新只会看到「后端未连接」
        if (!(await probeBackendHealth(url))) {
          apiMsgOk.value = false
          apiMsg.value = `连不上 ${url}：检查端口、防火墙，或确认那台机器上的后端已启动`
          return
        }
      }
      apply(url) // '' = 恢复端内后端
    } finally {
      apiBusy.value = false
    }
    location.reload()
    return
  }
  const unchanged = url === (manualApi.value || '')
  // 插件托管且没改：只重连，不把手填地址写进去（否则会变成"手连本机"）
  const keepPlugin = desktop && !manualApi.value && (!url || url === apiBase())

  /*
   * ★ TV 壳的后端地址**真源是 prefs[K_API]**，不是 localStorage。
   *
   * 壳每次启动都会走 restoreBackendMode() → injectApiBase(prefs[K_API])，
   * 把我们写在 localStorage 的值盖掉。实测（2026-11-13）：设置页改完地址
   * 当场连接正常，重启后又变回壳里存的旧地址 —— 用户看到的就是"改完白改"。
   *
   * 所以壳内必须同时写回 prefs（走 TvBridge.saveConn）。第二个参数是
   * CatVod 配置地址，这里不能传空串（会把手填的配置源清掉），要用现值。
   */
  if (url && tvMode) {
    const b = bridge()
    /*
     * 必须**两个方法都在**才走桥：
     *   saveConn 写 prefs；connState 读回当前的配置地址。
     * 老壳只有 saveConn 没有 connState 时，读不到现值就只能传空串——
     * 那会把用户手填的 CatVod 配置地址一起清掉。宁可退回旧行为
     * （只写 localStorage），也不能为了持久化地址去破坏配置。
     */
    if (b && typeof b.saveConn === 'function' && typeof b.connState === 'function') {
      try {
        const prev = JSON.parse(b.connState()) as { config?: string }
        const r = JSON.parse(b.saveConn(url, prev.config || '')) as { ok?: boolean; error?: string }
        if (!r.ok) {
          cfgMsgOk.value = false
          cfgMsg.value = r.error || '保存后端地址失败'
          return
        }
      } catch (e) {
        cfgMsgOk.value = false
        cfgMsg.value = '保存后端地址失败：' + String(e)
        return
      }
    }
  }

  if (!keepPlugin && !unchanged) {
    /*
     * 插件在跑时，回环地址会被插件协商的端口覆盖（resolveBase 的既有规则）。
     * 直接保存会让用户看到"地址换了、实际没换"，所以先说明并让他停插件。
     */
    if (desktop && url && isLoopbackUrl(url) && pluginBase() && pluginBase() !== url) {
      apiMsgOk.value = false
      apiMsg.value = `本机「影视后端」插件正在运行（${pluginBase()}），回环地址会被它接管；要连 ${url}，请先在「本机插件」里停止它`
      return
    }
    // 保存前先探活：死地址一旦刷新，用户刚扫描到的列表也一起没了
    if (url && !tvMode) {
      apiBusy.value = true
      try {
        if (!(await probeBackendHealth(url))) {
          apiMsgOk.value = false
          apiMsg.value = `连不上 ${url}：检查端口、防火墙，或确认那台机器上的猫窝后端已启动`
          return
        }
      } finally {
        apiBusy.value = false
      }
    }
    // 统一走 setRuntimeBase：它会顺带写 scope 标记，避免手填地址在
    // 桌面端/Web 端之间互相污染（实测被 30011 的残留劫持过）
    setRuntimeBase(url || null)
    manualApi.value = url
    apiDirty.value = false
    // 切后端必须整页刷新，让从旧后端读来的设置跟着换
    if (tvMode) {
      const b = bridge()
      if (b && typeof b.reloadApp === 'function') b.reloadApp()
      else location.reload()
    } else {
      location.reload()
    }
    return
  }

  // 地址没变：只重连（插件可能刚起来 / 刚重启过）
  apiBusy.value = true
  try {
    await refreshDesktopBase()
    await store.boot()
    apiDirty.value = false
    // 插件托管时把输入框回填成实际地址（用户刚清空过也能看到在用哪个）
    if (desktop && !manualApi.value) syncApiUrlFromRuntime()
    apiMsgOk.value = store.online
    apiMsg.value = store.online ? '已连接' : store.error || '未连接'
  } finally {
    apiBusy.value = false
  }
}

/** iOS：恢复端内后端（清壳里存的覆盖地址，reload 重新注入桥） */
function restoreEmbeddedBackend() {
  const apply = (window as unknown as {
    meowIos?: { setApiBackend?: (u: string) => boolean }
  }).meowIos?.setApiBackend
  if (typeof apply !== 'function') return
  apply('')
  location.reload()
}

/** 恢复"由插件托管"：清掉手填地址，重启后重新问插件要端口 */
function usePluginBackend() {
  setRuntimeBase(null)
  manualApi.value = ''
  // 换后端要重读设置，整页刷新（理由同 saveAndReconnect）
  location.reload()
}

// ---------------------------------------------------------------- TV 连接设置弹层
//
// 为什么要有它：TV 原来的配置是**独立 Activity 且是前置门** —— 没配好后端
// 就进不了系统，配错了也只能卸载重装。现在搬进设置页弹层，好处有三：
//   1. 进了系统之后随时能改（原来 openSetup 只在错误页调用）
//   2. 自动发现 / 扫码这些"遥控器不用打字"的能力跟着一起过来
//   3. 弹层是页面的一部分，不涉及 Activity 跳转，天然不会把用户困住
//
// 数据来源仍是原生层（LanScan / PushServer），只是在页面上呈现。
const connDialog = reactive({
  open: false,
  busy: false,
  scanning: false,
  /** 扫描到的候选 */
  found: [] as Array<{ url: string; label: string; version: string }>,
  msg: '',
  api: '',
  config: '',
})

/**
 * 扫码填写弹层（从「连接」里拆出来的独立入口）。
 *
 * 为什么不塞在连接弹层里：那个弹层的任务是"告诉我们后端在哪"，扫码又额外
 * 承担了"改一堆设置"，两者混在一起时，二维码旁边挤着地址输入框和扫描列表，
 * 用户看不出"扫这个码能干两件事"。
 *
 * 拆开后左边只放码和地址，右边列出这个码能改的全部条目 —— 用户扫之前
 * 就知道手机页面上会有什么，不用先扫进去才发现。
 */
const scanDialog = reactive({
  open: false,
  /** 手机页地址（已带 ?k=<令牌>）；空 = 推送服务没起来 */
  url: '',
  /** 单独的令牌，供手输 URL 时填写；空 = 未运行 */
  token: '',
  msg: '',
})

/** 手机页能改的条目（必须与 PushServer.PHONE_HTML 里的表单一致） */
const SCAN_PUSH_ITEMS: Array<{ group: string; items: string[] }> = [
  { group: '连接', items: ['后端地址'] },
  {
    group: '搜索与源',
    items: [
      '配置地址',
      '导入方式',
      '清空搜索缓存',
      '重置源健康',
      '结果缓存-命中',
      '结果缓存-空结果',
      '结果缓存-失败',
      '噪音过滤词',
      '命中后',
      'JS 运行时',
      '广告分片过滤',
      '搜索线程',
      '结果展示',
    ],
  },
  { group: '直播源', items: ['名称', '播放列表'] },
  {
    group: '播放',
    items: [
      '画面缩放',
      '自动连播',
      '连播倒计时',
      '跳过片头',
      '跳过片尾',
      '跳过前倒计时',
      '跳过中插广告',
      '右上角进度',
      '底部迷你进度条',
    ],
  },
  {
    group: '弹幕',
    items: ['显示弹幕', '密度', '字号', '透明度', '滚动时长', '显示区域', '屏蔽词', '弹幕 API', '腾讯自动抓取'],
  },
  { group: '外观', items: ['主题', '明暗', '首页列数', '首页续播卡', '壁纸', '背景模糊', '壁纸可见度'] },
  { group: '存储', items: ['缓存配额', '超限时', '缓存位置', '历史记录条数'] },
  {
    group: 'WebDAV 同步',
    items: [
      '启用',
      '地址',
      '用户名',
      '密码',
      '子目录',
      '同步观看历史',
      '同步设置备份',
      '同步搜索词',
      '备份片库配置',
    ],
  },
  { group: 'AI', items: ['启用', '接口地址', '模型', 'API Key', '上下文轮数', '超时', '最大输出'] },
  { group: '网络与加速', items: ['安全 DNS', 'DNS 服务商'] },
  { group: '应用更新', items: ['更新源', '备用源', '访问令牌', '自动检查壳', '自动检查模块', '自动检查插件', '自动检查 fnOS'] },
]

let connPoll: number | undefined

function openConnDialog() {
  const b = bridge()
  connDialog.open = true
  connDialog.msg = ''
  connDialog.found = []
  // 弹层一打开就把焦点挪进来。TV 的焦点是几何选焦（tv-nav.js），
  // 它靠 [data-tv-scope] 收窄范围，但当前焦点若还停在背景元素上，
  // 第一次按方向键的起点就是错的（实测：焦点停在背景的「保存」）。
  nextTick(() => {
    const el = document.querySelector<HTMLElement>(
      '[data-tv-scope] button, [data-tv-scope] input',
    )
    el?.focus()
  })
  if (!b || typeof b.connState !== 'function') {
    connDialog.msg = '当前不在 TV 壳内，无法自动发现'
    return
  }
  try {
    const s = JSON.parse(b.connState())
    connDialog.api = s.api || apiUrl.value || ''
    connDialog.config = s.config || (cfgUrlDirty.value ? cfgUrl.value : '')
  } catch {
    connDialog.api = apiUrl.value || ''
    connDialog.config = cfgUrlDirty.value ? cfgUrl.value : ''
  }
  void startConnScan()
}

/** 扫描是异步的：原生在后台线程跑，这里轮询取结果。 */
async function startConnScan() {
  const b = bridge()
  if (!b || typeof b.startScan !== 'function') return
  if (connDialog.scanning) return
  connDialog.scanning = true
  connDialog.found = []
  connDialog.msg = '正在扫描局域网（约 5–12 秒）…'
  let ok = false
  try {
    ok = b.startScan()
  } catch {
    ok = false
  }
  if (!ok) {
    connDialog.scanning = false
    connDialog.msg = '无法启动扫描'
    return
  }
  stopConnPoll()
  connPoll = window.setInterval(() => {
    try {
      if (!b.scanRunning()) {
        stopConnPoll()
        connDialog.scanning = false
        const arr = JSON.parse(b.scanResult() || '[]')
        connDialog.found = Array.isArray(arr) ? arr : []
        connDialog.msg = connDialog.found.length
          ? `找到 ${connDialog.found.length} 个，点一下就能填上`
          : '没找到。可以直接手填地址，或用手机扫码填写。'
      }
    } catch {
      stopConnPoll()
      connDialog.scanning = false
    }
  }, 600)
}

function stopConnPoll() {
  if (connPoll) {
    window.clearInterval(connPoll)
    connPoll = undefined
  }
}

/** 点扫描结果：填进输入框（不直接保存，让用户确认） */
function pickFound(f: { url: string; label?: string; version?: string }) {
  connDialog.api = f.url
  if (!connDialog.config.trim()) connDialog.config = f.url + '/config.json'
  connDialog.msg = '已选中 ' + f.url + '，点「保存并重连」'
}

// ---------------------------------------------------------------- Web/PC 局域网扫描
//
// TV 的「局域网自动发现」走原生 LanScan（socket 探测）；Web / PC 没有那个
// 桥，这里用页面 fetch 探各 IP 的 /api/health（端口 30010 / 9979，与
// LanScan 的常扫端口对齐）。
//
// ★ PC 也做成**弹框**（2026-11-20 用户要求，对齐 TV 的连接弹层）：
//   原来的结果胶囊直接铺在设置页里，扫描期间用户滚走了就再也看不见结果；
//   弹框里"扫描 → 选中 → 保存并重连"是一条完整的动作链，不会和页面其它
//   控件互相挤。
const webScan = reactive({
  open: false,
  scanning: false,
  found: [] as LanHit[],
  msg: '',
  /** 弹框里的地址输入框（默认带出当前地址） */
  api: '',
})

/** 打开 PC/Web 扫描弹框：带出当前地址并立即开扫 */
function openWebScanDialog() {
  webScan.open = true
  webScan.found = []
  webScan.msg = ''
  webScan.api = apiUrl.value || ''
  void startWebScan()
}

function closeWebScanDialog() {
  webScan.open = false
}

async function startWebScan() {
  if (webScan.scanning) return
  webScan.scanning = true
  webScan.found = []
  webScan.msg = '正在扫描局域网（约 10–20 秒）…'
  try {
    const r = await scanLanBackends()
    if (r.httpsBlocked) {
      webScan.msg = '当前是 https 页面，浏览器会拦掉所有 http 探测；请用 http 地址打开后再扫'
    } else if (r.hits.length) {
      webScan.found = r.hits
      webScan.msg = `找到 ${r.hits.length} 个后端，点一下填入地址，再点「保存并重连」`
    } else {
      webScan.msg = '没找到。可直接手填地址（如 http://192.168.1.10:30010）'
    }
  } catch (e) {
    webScan.msg = '扫描失败：' + (e instanceof Error ? e.message : String(e))
  } finally {
    webScan.scanning = false
  }
}

/** 点扫描结果：只填弹框里的地址（不直接保存，避免误连） */
function pickWebFound(f: LanHit) {
  webScan.api = f.url
  webScan.msg = '已选中 ' + f.url + '，点下方「保存并重连」'
}

/** 弹框里保存：把选中地址带到输入框并走统一的保存+重连 */
async function saveWebScan() {
  apiUrl.value = webScan.api.trim()
  await saveAndReconnect()
  // 地址变了会整页刷新（弹框自然消失）；没变时才走到这里
  if (apiMsg.value && apiMsgOk.value) webScan.open = false
  else if (apiMsg.value) webScan.msg = apiMsg.value
}

/**
 * 打开扫码填写弹层。
 *
 * ★ togglePush 是**幂等开关**，不是"确保开启"——已经开着时调它会关掉。
 *   所以必须先问 pushRunning()：
 *     · 已在跑：不能再 toggle（会把服务关掉，表现是"打开弹层却没有二维码"）
 *     · 没在跑：toggle 一次开启
 *   地址只有"开启那一刻"才返回，所以已在跑但地址丢了（换页/重载）时，
 *   只能先关再开拿地址 —— 这是唯一能拿到 IP 的口子。
 */
function openScanDialog() {
  scanDialog.msg = ''
  const b = bridge()
  if (!b || typeof b.togglePush !== 'function') {
    scanDialog.open = true
    scanDialog.msg = '当前不在 TV 壳内，扫码填写只在电视上用得上'
    return
  }
  try {
    const running = typeof b.pushRunning === 'function' && b.pushRunning()
    if (running && scanDialog.url) {
      // 已开着且有地址：什么都不用做
    } else if (running) {
      // 开着但地址丢了：先关再开，才能从返回值拿到 IP
      b.togglePush()
      const u = b.togglePush()
      scanDialog.url = u || ''
      scanDialog.token = u ? (b.pushToken?.() || '') : ''
      if (!u) scanDialog.msg = '启动推送失败（可能没连 WiFi）'
    } else {
      const u = b.togglePush()
      scanDialog.url = u || ''
      scanDialog.token = u ? (b.pushToken?.() || '') : ''
      if (!u) scanDialog.msg = '启动推送失败（可能没连 WiFi）'
    }
  } catch {
    scanDialog.msg = '启动推送失败（可能没连 WiFi）'
  }
  scanDialog.open = true
}

/** 关闭扫码弹层并停掉推送（开着 HTTP 服务没必要常驻） */
function closeScanDialog() {
  const b = bridge()
  try {
    if (b && typeof b.togglePush === 'function' && scanDialog.url) b.togglePush()
  } catch {
    /* 忽略 */
  }
  scanDialog.open = false
  scanDialog.url = ''
  scanDialog.token = ''
  scanDialog.msg = ''
}

/** 保存地址并让壳重新加载 */
function saveConnAndReload() {
  const b = bridge()
  if (!b || typeof b.saveConn !== 'function') {
    connDialog.msg = '当前不在 TV 壳内'
    return
  }
  const u = connDialog.api.trim()
  if (!u) {
    connDialog.msg = '请先填后端地址'
    return
  }
  try {
    const r = JSON.parse(b.saveConn(u, connDialog.config.trim()))
    if (!r.ok) {
      connDialog.msg = r.error || '保存失败'
      return
    }
    stopConnPoll()
    connDialog.open = false
    // 让壳重新注入 localStorage 并加载：否则页面还在用旧地址
    if (typeof b.reloadApp === 'function') b.reloadApp()
    else location.reload()
  } catch (e) {
    connDialog.msg = '保存失败：' + String(e)
  }
}

function closeConnDialog() {
  stopConnPoll()
  connDialog.open = false
}

/**
 * 写操作令牌（后端 policy.json 里设了 authToken 才需要）。
 *
 * 存在本设备 localStorage，**不进备份也不跨端同步**——它是凭据，跟着
 * WebDAV 同步等于把钥匙放进备份文件里。
 */
const authToken = ref(localStorage.getItem('meow.authToken') || '')
const authSaved = ref(false)
let authSavedTimer: number | undefined

function saveAuthToken() {
  const v = authToken.value.trim()
  if (v) localStorage.setItem('meow.authToken', v)
  else localStorage.removeItem('meow.authToken')
  authSaved.value = true
  clearTimeout(authSavedTimer)
  authSavedTimer = window.setTimeout(() => (authSaved.value = false), 2000)
}

// ---------------------------------------------------------------- 安全 DNS（后端策略）

const safeDns = ref(false)
const dnsProvider = ref('dnspub')
const dnsBusy = ref(false)
const dnsProbe = ref<{ ok: boolean; text: string } | null>(null)

/** 对齐影视仓：关闭 / 腾讯 / 阿里 / 360 / Google，另加 Cloudflare */
const dnsOptions = [
  { value: 'off', label: '关闭' },
  { value: 'dnspub', label: '腾讯 DoH' },
  { value: 'ali', label: '阿里 DoH' },
  { value: '360', label: '360 DoH' },
  { value: 'google', label: 'Google DoH' },
  { value: 'cloudflare', label: 'Cloudflare' },
]

const dnsChoice = computed({
  get: () => (safeDns.value ? dnsProvider.value : 'off'),
  set: (v: string) => {
    if (v === 'off') safeDns.value = false
    else {
      safeDns.value = true
      dnsProvider.value = v
    }
    dnsProbe.value = null
    void savePolicy({ safeDns: safeDns.value, dnsProvider: dnsProvider.value })
  },
})

async function loadPolicy() {
  try {
    const p = await fetchPolicy()
    safeDns.value = Boolean(p.safeDns)
    if (typeof p.dnsProvider === 'string' && p.dnsProvider) dnsProvider.value = p.dnsProvider
    if (typeof p.danmakuApi === 'string') danmakuApi.value = p.danmakuApi
    // 后端的缺省值也是 true（腾讯时间轴问题已修，2026-11-02），
    // 所以 undefined 要当成 true，而不是 false。
    danmakuTencent.value = p.danmakuTencent !== false
    if (Array.isArray(p.danmakuOrder) && p.danmakuOrder.length) {
      orderFirst.value = p.danmakuOrder[0] === 'aggregator' ? 'aggregator' : 'builtin'
    } else if (typeof p.danmakuApi === 'string' && p.danmakuApi.trim()) {
      /*
       * 没有显式顺序、但已经配置了 danmu_api：默认「弹幕 API 优先」。
       * 否则首播的并发抢占几乎总是内置直连先到（1–3s vs 7–10s），
       * 用户会以为 danmu_api 没生效（2026-11-09 与后端注释对齐）。
       */
      orderFirst.value = 'aggregator'
    }
    // 预取：缺省开（undefined 要当成 true）
    prefetchOn.value = p.danmakuPrefetch !== false
    if (typeof p.danmakuPrefetchLead === 'number' && p.danmakuPrefetchLead >= 10) {
      prefetchLead.value = p.danmakuPrefetchLead
    }
    // 弹幕缓存配额/淘汰（缺省 200MB + lru，与后端一致）
    if (typeof p.danmakuCacheQuotaMb === 'number') {
      danmakuQuotaMb.value = p.danmakuCacheQuotaMb
    }
    if (p.danmakuCacheEvictMode === 'off' || p.danmakuCacheEvictMode === 'lru') {
      danmakuEvictMode.value = p.danmakuCacheEvictMode
    }
  } catch {
    /* 后端未连上时保持默认 */
  }
}

/* ---------------------------------------------------------------- 弹幕源 */

/**
 * 自定义弹幕 API（danmu_api 或兼容站）。
 *
 * 填了之后后端会把它排在内置源之前优先尝试，多个地址用换行分隔。
 * 例：http://192.168.1.10:9321/87654321
 */
const danmakuApi = ref('')
const danmakuTencent = ref(true)

/**
 * 自动播放时先用谁。
 *
 * <p><b>为什么给个开关而不是写死</b>（2026-11-02 用户要求）：
 * 内置直连（爱奇艺/腾讯）拿的是官方原始弹幕，但只覆盖两个平台；
 * 自建 danmu_api 覆盖 20+ 平台，对同一部剧常有更全的分集。谁更准
 * 取决于用户装了什么、看的什么剧，写死任何一方都不对。
 *
 * <p>对应 policy.json 的 `danmakuOrder` 数组（后端按数组顺序降级）。
 * `builtin` → `["iqiyi","tencent","aggregator"]`；
 * `aggregator` → `["aggregator","iqiyi","tencent"]`。
 */
const orderFirst = ref<'builtin' | 'aggregator'>('builtin')
/**
 * 下集弹幕预取（2026-11-02 用户要求）。
 *
 * <p>触发公式由前端算：`min(片尾标记, 时长) - 提前量`。
 * 提前量给用户调，是因为它取决于用户的上游有多慢：
 *   · 只用内置源（1–3 秒）→ 60 秒就够
 *   · 用 danmu_api（7–12 秒）→ 180 秒（默认）留足余量
 */
const prefetchOn = ref(true)
const prefetchLead = ref(180)
const danmakuSaving = ref(false)
const danmakuMsg = ref('')

async function saveDanmakuApi() {
  danmakuSaving.value = true
  danmakuMsg.value = ''
  try {
    const order =
      orderFirst.value === 'aggregator'
        ? ['aggregator', 'iqiyi', 'tencent']
        : ['iqiyi', 'tencent', 'aggregator']
    await savePolicy({
      danmakuApi: danmakuApi.value.trim(),
      danmakuTencent: danmakuTencent.value,
      danmakuOrder: order,
      danmakuPrefetch: prefetchOn.value,
      danmakuPrefetchLead: prefetchLead.value,
    })
    danmakuMsg.value = '已保存'
  } catch (e: any) {
    danmakuMsg.value = e?.message || String(e)
  } finally {
    danmakuSaving.value = false
  }
}

async function probeDns() {
  if (!safeDns.value) {
    dnsProbe.value = { ok: false, text: '安全 DNS 已关闭，当前使用系统解析' }
    return
  }
  dnsBusy.value = true
  dnsProbe.value = null
  try {
    const r = await resolveDns('ppvod01.kqgfbs.com', dnsProvider.value)
    dnsProbe.value = r.ip
      ? { ok: true, text: `${r.host} → ${r.ip}${r.cached ? '（缓存）' : ''}` }
      : { ok: false, text: r.msg || '未解析到 A 记录' }
  } catch (e: any) {
    dnsProbe.value = { ok: false, text: e?.message || String(e) }
  } finally {
    dnsBusy.value = false
  }
}

// ---------------------------------------------------------------- 选项

// 10 列只在电视/宽屏能落地：手机竖屏按可用宽度只放得下 3 列
const colsOptions = [3, 4, 5, 6, 7, 8, 10].map((v) => ({
  value: v,
  label: v === 10 ? '10 列（电视）' : `${v} 列`,
}))
const onOff = [
  { value: 'on', label: '开' },
  { value: 'off', label: '关' },
]
const scaleOptions = [0.8, 1, 1.2, 1.4].map((v) => ({
  value: v,
  label: v === 1 ? '标准' : `${Math.round(v * 100)}%`,
}))
const opacityOptions = [1, 0.9, 0.75, 0.5].map((v) => ({
  value: v,
  label: v === 1 ? '不透明' : `${Math.round(v * 100)}%`,
}))
const durationOptions = [8, 10, 12, 15, 20].map((v) => ({ value: v, label: `${v} 秒` }))
const areaOptions = [0.4, 0.55, 0.7, 0.85, 1].map((v) => ({
  value: v,
  label: v === 1 ? '全屏' : `${Math.round(v * 100)}%`,
}))

/**
 * 弹幕密度档位。
 *
 * 这是「时长 + 区域」的预设组合——两者耦合，单看某一个数看不出
 * 结果，打包成档位用户才好选。数值是拿真实剧集数据反推的。
 * custom 表示用户手改过高级项，此时档位显示为"自定义"。
 */
const densityOptions = [
  { value: 'sparse', label: '稀疏', hint: '节奏舒缓，不挡画面' },
  { value: 'standard', label: '标准', hint: '默认，与以往一致' },
  { value: 'dense', label: '密集', hint: '尽量多显示，飞得更快' },
  { value: 'custom', label: '自定义', hint: '手动调过速度或区域' },
]
const fitOptions = [
  { value: 'contain', label: '适应' },
  { value: 'cover', label: '铺满' },
  { value: 'fill', label: '拉伸' },
]
const layoutOptions = [
  { value: 'grid', label: '缩略图' },
  { value: 'list', label: '列表' },
]
const threadOptions = [4, 8, 12, 16, 24].map((v) => ({ value: v, label: `${v} 线程` }))
// cacheDays 已移除：全仓只有设置页读写它，没有任何代码消费（死控件）。
// prefs.ts 里仍保留字段，只为兼容老数据，不再暴露到界面。
const historyOptions = [10, 30, 50, 100].map((v) => ({ value: v, label: `${v} 条` }))
/** 片头片尾跳过秒数，0 = 关闭 */
const skipOptions = [
  { value: 0, label: '不跳' },
  ...[15, 30, 45, 60, 90, 120].map((v) => ({ value: v, label: `${v} 秒` })),
]
/** 跳过提示的倒计时：0 = 点了立刻跳，其余是等待秒数 */
const skipCdOptions = [
  { value: 0, label: '立即跳' },
  ...[3, 5, 10, 15].map((v) => ({ value: v, label: `${v} 秒` })),
]
/**
 * 无声时的记忆选择（2026-11-24）。
 *
 * 播放页的提示条点过一次会记住（native/transcode），但以前**没有出口**，
 * 用户想改回"每次询问"只能清数据。这里补上设置项。
 */
const silentActionOptions = [
  { value: 'ask', label: '每次询问' },
  { value: 'native', label: '原生播放' },
  { value: 'transcode', label: '转码' },
]

// ---------------------------------------------------------------- 外观

/** 主题风格：九套，每套都有浅/深两档（见 stores/theme.ts） */
const themeOptions = THEME_STYLES.map((t) => ({
  value: t.value,
  label: t.label,
  hint: t.hint,
  swatch: t.swatch,
}))
/** 明暗：浅色 / 深色 / 跟随系统 */
const modeOptions = THEME_MODES.map((m) => ({ value: m.value, label: m.label }))
/** 背景模糊档位 */
const blurOptions = [
  { value: 0, label: '关' },
  { value: 2, label: '轻' },
  { value: 6, label: '中' },
  { value: 12, label: '强' },
]
/**
 * 壁纸可见度：越高背景越清楚，卡片越透。
 *
 * ★ 卡片透明度只有 4 档（CSS 的 data-wp 0..3，见 App.vue 的 wpLevel），
 *   所以 85% 与 100% 的**卡片**是同一档；区别在蒙层（App.vue 的 scrim）：
 *   它按可见度连续取值，100% 时浅色壁纸的蒙层只剩 8%，图基本是原样。
 */
const wallpaperOpacityOptions = [
  { value: 0.2, label: '25%' },
  { value: 0.4, label: '45%' },
  { value: 0.6, label: '65%' },
  { value: 0.85, label: '85%' },
  // 100%：卡片不压暗壁纸，蒙层也最薄 —— 想要"图能看清"就选这档
  { value: 1, label: '100%' },
]

const dmOnOff = computed({
  get: () => (prefs.value.danmaku ? 'on' : 'off'),
  set: (v: string) => update('danmaku', v === 'on'),
})
/** 自动播放效果：三态（立即 / 倒计时 / 不自动） */
const autoPlayOptions = [
  { value: 'immediate', label: '立即播放' },
  { value: 'countdown', label: '倒计时' },
  { value: 'off', label: '不自动' },
]
/** 倒计时秒数可选项 */
const autoDelayOptions = [3, 5, 8, 10, 15].map((v) => ({ value: v, label: `${v} 秒` }))

// ---------------------------------------------------------------- WebDAV

const dav = ref<WebdavConfig>(getWebdav())
const davBusy = ref(false)
const davMsg = ref<{ ok: boolean; text: string } | null>(null)

/**
 * 更新 WebDAV 配置。
 *
 * 必须基于内存里的 `dav.value` 合并后再整体落盘：
 * `saveWebdav` 内部是从 localStorage 读的，而输入框改的只是内存状态，
 * 直接用 patch 会被 localStorage 里的旧值覆盖（表现为输入框被清空）。
 */
function patchDav(patch: Partial<WebdavConfig>) {
  dav.value = { ...dav.value, ...patch }
  saveWebdav(dav.value)
}

async function testDav() {
  davBusy.value = true
  davMsg.value = null
  try {
    const r = await webdavPing(apiBase(), dav.value)
    // 直接用 webdavPing 的文案：成功时它数了目录里已有几个文件
    // （「连接成功（3 个文件）」），比裸的 HTTP 207 更有用；失败时的
    // 401/404/连不上也都是它区分好的。
    davMsg.value = { ok: r.ok, text: r.msg }
  } finally {
    davBusy.value = false
  }
}

/**
 * 切换自动同步。
 *
 * 开启时**先拉后推**：只拉的话，刚填好地址的用户要等下一次"历史/设置变更"
 * 才会第一次上传，远端一直是空的，看起来像没生效（2026-11-12 实测）。
 */
async function toggleSync() {
  const next = !dav.value.enabled
  patchDav({ enabled: next })
  davMsg.value = null
  if (!next || !dav.value.url) return
  davBusy.value = true
  try {
    const r = await pullHistory(apiBase())
    let uploaded = 0
    if (dav.value.syncHistory || dav.value.syncPrefs) {
      const u = await upload(apiBase())
      uploaded = u.files.length
    }
    const merged = r.added ? `合并 ${r.added} 条远端历史` : '历史已是最新'
    davMsg.value = {
      ok: true,
      text: `已开启，${merged}${uploaded ? `，已上传 ${uploaded} 项` : ''}`,
    }
  } catch (e: any) {
    davMsg.value = { ok: false, text: `已开启，但首次同步失败：${e?.message || e}` }
  } finally {
    davBusy.value = false
  }
}

// ---------------------------------------------------------------- 历史（简单并集）

async function pushHistoryNow() {
  davBusy.value = true
  davMsg.value = null
  try {
    const r = await withSyncStatus(
      () => pushHistory(apiBase()),
      (x) => `已上传 ${x.count} 条历史`,
    )
    davMsg.value = { ok: true, text: `已上传 ${r.count} 条历史` }
  } catch (e: any) {
    davMsg.value = { ok: false, text: e?.message || String(e) }
  } finally {
    davBusy.value = false
  }
}

async function pullHistoryNow() {
  davBusy.value = true
  davMsg.value = null
  try {
    const r = await withSyncStatus(
      () => pullHistory(apiBase()),
      (x) => (x.added ? `已合并 ${x.added} 条新记录（共 ${x.total} 条）` : '本地已是最新'),
    )
    davMsg.value = {
      ok: true,
      text: r.added ? `已合并 ${r.added} 条新记录（共 ${r.total} 条）` : '本地已是最新',
    }
  } catch (e: any) {
    davMsg.value = { ok: false, text: e?.message || String(e) }
  } finally {
    davBusy.value = false
  }
}

// ---------------------------------------------------------------- 搜索关键词

const searchWordCount = ref(listSearchHistory().length)

async function pushSearchWordsNow() {
  davBusy.value = true
  davMsg.value = null
  try {
    const r = await withSyncStatus(
      () => pushSearchHistory(apiBase()),
      (x) => `已上传 ${x.count} 个关键词`,
    )
    davMsg.value = { ok: true, text: `已上传 ${r.count} 个关键词` }
  } catch (e: any) {
    davMsg.value = { ok: false, text: e?.message || String(e) }
  } finally {
    davBusy.value = false
  }
}

async function pullSearchWordsNow() {
  davBusy.value = true
  davMsg.value = null
  try {
    const r = await withSyncStatus(
      () => pullSearchHistory(apiBase()),
      (x) => (x.added ? `已合并 ${x.added} 个新关键词（共 ${x.total} 个）` : '本机已是最新'),
    )
    searchWordCount.value = listSearchHistory().length
    davMsg.value = {
      ok: true,
      text: r.added ? `已合并 ${r.added} 个新关键词（共 ${r.total} 个）` : '本机已是最新',
    }
  } catch (e: any) {
    davMsg.value = { ok: false, text: e?.message || String(e) }
  } finally {
    davBusy.value = false
  }
}

async function clearWords() {
  if (!(await confirmDialog('清空本机的搜索关键词？（不影响云端）', { danger: true }))) return
  clearSearchHistory()
  searchWordCount.value = 0
  davMsg.value = { ok: true, text: '已清空本机关键词' }
}

// ---------------------------------------------------------------- 设置快照

const snapshots = ref<Snapshot[]>([])
const snapBusy = ref(false)
const snapMsg = ref<{ ok: boolean; text: string } | null>(null)

async function refreshSnapshots() {
  if (!dav.value.url) return
  snapBusy.value = true
  try {
    snapshots.value = await listSnapshots(apiBase())
    if (!snapshots.value.length) snapMsg.value = { ok: true, text: '还没有备份' }
    else snapMsg.value = null
  } catch (e: any) {
    snapMsg.value = { ok: false, text: e?.message || String(e) }
  } finally {
    snapBusy.value = false
  }
}

/** 立即备份当前设置 */
async function backupNow() {
  snapBusy.value = true
  snapMsg.value = null
  try {
    const r = await withSyncStatus(
      () => createSnapshot(apiBase(), { force: true }),
      (x) => `已创建备份 ${x.name}`,
    )
    snapMsg.value = { ok: true, text: `已创建备份 ${r.name}` }
    await refreshSnapshots()
  } catch (e: any) {
    snapMsg.value = { ok: false, text: e?.message || String(e) }
  } finally {
    snapBusy.value = false
  }
}

/** 只保留最近 3 份设置备份 */
async function cleanOldSnapshots() {
  if (!(await confirmDialog('只保留最近 3 份设置备份，其余全部删除？删除后不可恢复。', { danger: true })))
    return
  snapBusy.value = true
  snapMsg.value = null
  try {
    const r = await withSyncStatus(
      () => pruneSnapshotsTo(apiBase(), 3),
      (x) => `已清理 ${x.deleted} 份旧备份`,
    )
    snapMsg.value = { ok: true, text: `已清理 ${r.deleted} 份，保留 ${r.kept} 份` }
    await refreshSnapshots()
  } catch (e: any) {
    snapMsg.value = { ok: false, text: e?.message || String(e) }
  } finally {
    snapBusy.value = false
  }
}

/** 还原到选中的时间点 */
async function restoreSnapshotAt(s: Snapshot) {
  // 优先服务器时间：文件名用的是客户端时钟，多端不齐时会显示成过去
  const when = new Date(s.mtime ?? s.at).toLocaleString()
  if (
    !(await confirmDialog(
      `会把这个时间点的设置覆盖到本地（历史不受影响）：\n\n${when}\n${s.name}\n\n确定？`,
    ))
  )
    return
  snapBusy.value = true
  snapMsg.value = null
  try {
    await restoreSnapshot(apiBase(), s.name)
    snapMsg.value = { ok: true, text: '已还原，正在刷新…' }
    setTimeout(() => location.reload(), 700)
  } catch (e: any) {
    snapMsg.value = { ok: false, text: e?.message || String(e) }
  } finally {
    snapBusy.value = false
  }
}

async function removeSnapshotAt(s: Snapshot) {
  if (!(await confirmDialog(`删除备份 ${s.name}？`, { danger: true }))) return
  snapBusy.value = true
  try {
    await deleteSnapshot(apiBase(), s.name)
    await refreshSnapshots()
  } catch (e: any) {
    snapMsg.value = { ok: false, text: e?.message || String(e) }
  } finally {
    snapBusy.value = false
  }
}

// ---------------------------------------------------------------- 备份还原

const fileInput = ref<HTMLInputElement>()
const backupMsg = ref('')

function exportBackup() {
  const data = makeBackup(listHistory(), [], listFavorites())
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `meow-backup-${new Date().toISOString().slice(0, 10)}.json`
  a.click()
  URL.revokeObjectURL(a.href)
  backupMsg.value = `已导出（含 ${data.history.length} 条历史、${data.favorites?.length ?? 0} 条收藏）`
}

function pickImport() {
  fileInput.value?.click()
}

async function onImportFile(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (!f) return
  try {
    const b = JSON.parse(await f.text()) as Backup
    if (!applyBackup(b)) throw new Error('文件格式不对')
    backupMsg.value = '导入成功，正在刷新…'
    setTimeout(() => location.reload(), 600)
  } catch (err: any) {
    backupMsg.value = `导入失败：${err?.message || err}`
  } finally {
    ;(e.target as HTMLInputElement).value = ''
  }
}

async function resetPrefs() {
  if (!(await confirmDialog('恢复默认设置？观看历史不会删除。', { danger: true }))) return
  prefs.value = savePrefs(DEFAULTS)
  backupMsg.value = '已恢复默认'
}

// ---------------------------------------------------------------- 维护

/**
 * 搜索结果缓存的统计。
 *
 * 为什么要在设置页显示：用户看不到缓存就永远搞不懂"为什么第二次搜同一个词
 * 是毫秒级"、"为什么刚修好的源还是没结果"。把条数和 TTL 摆出来，
 * 这两类困惑都能自己回答。
 */
const searchCacheStats = ref<SearchCacheStats | null>(null)

async function loadSearchCacheStats() {
  try {
    searchCacheStats.value = await fetchSearchCacheStats()
  } catch {
    // 老版本后端没有这个端点：静默降级，按钮仍可用（DELETE 会 404 并报错）
    searchCacheStats.value = null
  }
}

/**
 * 缓存时长下拉项（三档）。
 *
 * 「失败」档故意不给大值：它是运行时故障的负缓存，调大等于让一次抖动
 * 锁死那批源很久（60 秒已经够挡同一轮的重复请求）。
 *
 * 结构与 TileSelect 的 Option 对齐（value/label），这样三档直接渲染成
 * 与「列数上限」「搜索线程」同款的磁贴，而不是原生 select。
 */
const cacheTtlFields = [
  {
    key: 'searchCacheHitSec' as const,
    label: '有结果缓存',
    options: [
      { value: 0, label: '不缓存' },
      { value: 600, label: '10 分钟' },
      { value: 1800, label: '30 分钟' },
      { value: 3600, label: '1 小时' },
      { value: 7200, label: '2 小时' },
    ],
  },
  {
    key: 'searchCacheMissSec' as const,
    label: '空结果缓存',
    options: [
      { value: 0, label: '不缓存' },
      { value: 60, label: '1 分钟' },
      { value: 180, label: '3 分钟' },
      { value: 600, label: '10 分钟' },
    ],
  },
  {
    key: 'searchCacheFailSec' as const,
    label: '失败缓存',
    options: [
      { value: 0, label: '不缓存' },
      { value: 30, label: '30 秒' },
      { value: 60, label: '1 分钟' },
      { value: 180, label: '3 分钟' },
    ],
  },
]

/** 当前生效的缓存秒数（优先用后端返回的真值，回落到统计里的 ttl*）。 */
function policyCacheSec(key: 'searchCacheHitSec' | 'searchCacheMissSec' | 'searchCacheFailSec'): number {
  const s = searchCacheStats.value
  if (!s) return key === 'searchCacheHitSec' ? 7200 : key === 'searchCacheMissSec' ? 180 : 60
  if (key === 'searchCacheHitSec') return s.ttlHitSec
  if (key === 'searchCacheMissSec') return s.ttlMissSec
  return s.ttlFailSec
}

function formatSec(sec: number): string {
  if (sec <= 0) return '不缓存'
  if (sec < 60) return `${sec} 秒`
  if (sec < 3600) return `${Math.round(sec / 60)} 分钟`
  return `${Math.round(sec / 3600)} 小时`
}

/**
 * 改缓存时长 → 写后端 policy.json。
 *
 * 后端 savePolicy 里会调 SearchCache.reloadTtl()，所以立刻生效。
 * 参数已从 Event 改成 TileSelect 直接给的秒数。
 */
async function onPolicyCacheChange(
  key: 'searchCacheHitSec' | 'searchCacheMissSec' | 'searchCacheFailSec',
  sec: number,
) {
  try {
    await savePolicy({ [key]: sec } as any)
    await loadSearchCacheStats()
    backupMsg.value = `缓存已更新（${formatSec(sec)}），立即生效`
  } catch (err: any) {
    backupMsg.value = `保存失败：${err?.message || err}`
  }
}

/** 「存储」区的本机缓存清理（HTTP 缓存 / sessionStorage，不动观看历史与设置） */
const localCacheMsg = ref('')
/**
 * 清掉**可再生的**那部分：Cache Storage（海报、脚本等 HTTP 缓存）
 * 与 sessionStorage（本标签页的临时状态）。
 *
 * ★ 绝不能碰 `meow.history.list` —— 那是观看历史，不是缓存。
 *   早先这行就在里面，于是「清空本地缓存」会静默删掉全部观看进度，
 *   而旁边「恢复默认设置」却写着"观看历史不会删除"，两个按钮的语义正好相反。
 *   （用户报「这些功能都有用吗」时查出来的。）
 */
function clearLocalCache() {
  try {
    sessionStorage.clear()
  } catch {
    /* 忽略 */
  }
  /*
   * ★ CacheStorage 只在安全上下文（https / localhost）存在。
   *   TV 壳里页面是 http://meow.local，`caches` 是 undefined ——
   *   原来写 `caches?.keys?.then(...)`，可选链让整行变成 undefined，
   *   但**提示照样说"已清空"**，用户以为清了其实什么都没动
   *   （点过的人会觉得这个按钮是假的）。
   *   这里区分两种情况，把真实结果告诉用户。
   */
  const hasCacheApi = typeof caches !== 'undefined' && typeof caches.keys === 'function'
  if (hasCacheApi) {
    void caches
      .keys()
      .then((ks) => Promise.all(ks.map((k) => caches.delete(k))))
      .catch(() => undefined)
      .then(() => {
        localCacheMsg.value = '本地缓存已清空（观看历史与设置都保留）'
      })
  } else {
    localCacheMsg.value = '已清空临时数据；HTTP 缓存由电视自己管理（此环境无 CacheStorage）'
  }
}

/** 「维护」区只保留重置/诊断/关于，不再混入连接与存储动作 */
const maintTiles = computed<TileAction[]>(() => [
  {
    key: 'resetprefs',
    label: '恢复默认设置',
    hint: '所有设置回默认值，历史与源保留',
    arrow: false,
    onClick: resetPrefs,
  },
  {
    key: 'logs',
    label: '运行日志',
    hint: '客户端 + 服务端日志',
    value: '诊断',
    onClick: () => void router.push({ name: 'logs' }),
  },
  {
    key: 'about',
    label: '关于',
    hint: '版本历史与项目主页',
    value: `v${appVersion.value}`,
    // 2026-11-18 用户口径：点「关于」先弹框（版本历史可滚 + GitHub 图标跳转），
    // 不再直接开浏览器 —— TV 上原来只能"复制链接"，等于没入口。
    onClick: () => (showAbout.value = true),
  },
])

/**
 * 打开项目主页。
 *
 * ★ TV 上**不能**用 `window.open`：TV 的 WebView 没开多窗口支持，
 *   `window.open(url, '_blank')` 会被 Chromium 直接丢弃，连 JS 都不报错 ——
 *   用户看到的就是"这个按钮点了没反应"（实测 MuMu 与投影仪都这样）。
 *
 * ★ TV 上也不该弹外部浏览器：多数电视没有浏览器；有的话会把用户弹到
 *   应用外，而遥控器没有任务切换入口，等于回不来。
 *
 * 所以按平台分流：
 *   · TV    → 复制链接 + 在按钮上提示（想让别人看就用手机）
 *   · PC/Web → 正常新标签打开
 */
function openProjectPage() {
  const url = 'https://github.com/upchr/meow'
  if (tvMode) {
    void copyText(url).then((ok) => {
      backupMsg.value = ok
        ? `已复制项目地址，可在手机上打开：${url}`
        : `项目地址：${url}`
    })
    return
  }
  window.open(url, '_blank', 'noopener')
}

/** 复制文本；剪贴板不可用（非 https / 老内核）时返回 false，由调用方兜底显示。 */
async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    /* 落到下面的兜底 */
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    // 必须在视口内且不可见，否则 iOS 上复制无效
    ta.style.cssText = 'position:fixed;left:-9999px;top:0;opacity:0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}

/**
 * 「站点明细与源健康」的修复动作。
 *
 * 只保留“源本身”的修复：重置健康记录。清空搜索缓存属于「结果缓存」，
 * 放在对应分组里，避免用户为了清缓存又滚回源管理区。
 */
const sourceHealthMsg = ref('')
async function resetSourceHealth() {
  if (
    !(await confirmDialog(
      '重置所有源的健康记录？\n\n会清掉"跳过/降档/降级"标记，下次搜索全部源重新参与（更慢但更全）。',
    ))
  )
    return
  try {
    await resetHealth()
    sourceHealthMsg.value = '已重置源健康，下次搜索全部源重新参与'
    await Promise.all([store.refreshSites(), loadHealthDetail()])
  } catch (e: any) {
    sourceHealthMsg.value = `重置失败：${e?.message || e}`
  }
}

/** 「结果缓存」分组的清空动作（与 TTL 输入放在一起） */
const searchCacheMsg = ref('')
async function clearSearchCacheNow() {
  try {
    const r = await clearSearchCache()
    searchCacheMsg.value = `已清空搜索缓存 ${r.cleared} 条`
    await loadSearchCacheStats()
  } catch (e: any) {
    searchCacheMsg.value = `清空失败：${e?.message || e}`
  }
}

const showSites = ref(false)
/** 「关于」弹框：版本历史 + 项目主页（版本历史是运行时拉的，见 api/changelog.ts） */
const showAbout = ref(false)
const extCount = computed(() => store.sites.filter((s) => s.ext).length)
/**
 * 统计"当前会被聚合跳过"的源。
 *
 * 只有两种：手动停用的、环境不支持的（待配置）。
 *
 * ★ **反复失败（degraded）不算在内**：它已经不再触发跳过了，
 *   搜索对所有源都会发请求，只是把它们排在后面。
 *   早先这里把 degraded 也算进"不可用"，配合后端把 229 个 drpy 源
 *   误判成降级，摘要显示成"229/270 不可用"——看起来像全线故障。
 */
const badCount = computed(
  () =>
    store.sites.filter((s) => s.health?.disabled || s.health?.unsupported).length,
)

/** "待配置"的源数量：单独报，让用户知道是配置问题不是故障 */
const unsupportedCount = computed(
  () => store.sites.filter((s) => s.health?.unsupported && !s.health?.disabled).length,
)

/**
 * 搜索时会被跳过的源数量（失效 + 连续超时）。
 *
 * ★ 判据必须与后端**同源**：后端 `/api/sites` 已经在 health 里下发
 * `skippedFailed` / `skippedTimeout`（直接来自 isFailedSkipped /
 * isTimeoutSkipped）。前端绝对不要自己按 fail 次数重新算一遍 ——
 * 两套判据一定会漂移，界面说的和后端做的就对不上了。
 *
 * 这两个是"临时跳过"（10 分钟后自动重试），与"永久不可用"（disabled /
 * unsupported）是不同概念，所以单独计数、不并入 badCount。
 */
const skippedCount = computed(
  () =>
    store.sites.filter(
      (s) => !s.health?.disabled && !s.health?.unsupported
        && (s.health?.skippedFailed || s.health?.skippedTimeout),
    ).length,
)

/** 其中"已失效"（累计失败多且 30 分钟无成功）的数量 */
const failedSkipCount = computed(
  () => store.sites.filter((s) => s.health?.skippedFailed && !s.health?.disabled).length,
)

/** 拉「未生效」列表（失败不打扰用户：这是诊断信息，不是主流程） */
async function refreshPendingSites() {
  try {
    pendingSites.value = await fetchPendingSites()
  } catch {
    pendingSites.value = []
  }
}

/**
 * 展开站点明细时重新拉一次：健康标记是"调用后才有"的，
 * 用启动时那份列表会显示成全部正常，看起来像功能没生效。
 */
async function toggleSites() {
  showSites.value = !showSites.value
  if (showSites.value) {
    await Promise.all([store.refreshSites(), loadHealthDetail(), refreshPendingSites()])
    await loadSearchCacheStats()
  } else {
    siteSel.clear()
  }
}

/**
 * 关闭站点明细弹层（点遮罩 / 返回按钮 / Escape）。
 *
 * 焦点交还两条路径：
 *   1. tv-nav 的 `__meowTvScopeClosed()` —— 遥控器按 OK 打开时它能记住
 *      "打开者"（`enterOpener`），自动还回去；
 *   2. 传一个兜底选择器 —— **鼠标点开**时不经过 enter()，`scopeReturnEl`
 *      是空的，实测 `hasReturnEl:false`，关闭后焦点落到左导航「01 连接」。
 *      这时用 `[data-tv-key="settings-sites"]` 把焦点拉回触发按钮。
 */
function closeSites() {
  showSites.value = false
  const w = window as unknown as {
    __meowTvScopeClosed?: (fallbackSelector?: string) => boolean
  }
  if (typeof w.__meowTvScopeClosed === 'function') {
    w.__meowTvScopeClosed('[data-tv-key="settings-sites"]')
  }
}

/**
 * 关闭「关于」弹框（点遮罩 / 返回按钮 / Escape 都走这里）。
 *
 * 焦点交还：tv-nav 的 `__meowTvScopeClosed()` 能记住遥控器打开时的触发元素；
 * 鼠标点开时没有 enterOpener，交还到左导航也算合理（弹框是临时查看）。
 */
function closeAbout() {
  showAbout.value = false
  const w = window as unknown as {
    __meowTvScopeClosed?: (fallbackSelector?: string) => boolean
  }
  if (typeof w.__meowTvScopeClosed === 'function') w.__meowTvScopeClosed()
}

/**
 * Escape 关弹层：App.vue 的 onTvBack 就是靠派发 Escape 让组件自己关的。
 *
 * ★ 连接弹层也必须在这里处理。
 * 2026-11-03 实测：弹框内连按两次 BACK，`scopes` 仍为 1，只有「返回」按钮能关。
 * 原因不是壳没派发 Escape，而是 ConnectionDialog 自己没监听（站点明细监听了，
 * 所以它正常）。同一入口统一收口，避免两套 keydown 监听漂移。
 */
function onSitesKey(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  // 扫码弹层（2026-11-04 补）：没有 Escape 接收端时 BACK 被 App 吞掉、弹层不关
  if (scanDialog.open) {
    scanDialog.open = false
    return
  }
  if (showAbout.value) {
    closeAbout()
    return
  }
  if (showSites.value) {
    closeSites()
    return
  }
  if (connDialog.open) closeConnDialog()
}

// ---------------------------------------------------------------- 站点弹框：筛选 / 批量

/**
 * 弹框内的筛选维度。
 *
 * 为什么这些维度而不是按字母/类型：用户打开这个弹框的目的只有两种 ——
 * 「把没用的源清理掉」和「看看某个源为什么没参与搜索」。两种都按**状态**找，
 * 不按名字找。实测 OECT 上 141 个失效源，靠滚动是找不到的。
 */
type SiteFilter = 'all' | 'skipped' | 'failed' | 'unsupported' | 'disabled' | 'ok' | 'pending'

const siteFilter = ref<SiteFilter>('all')
const siteKeyword = ref('')
/**
 * 「未生效」站点：配置里有、本构建加载不了（issue #9）。
 *
 * 它们不在 /api/sites 里（TV 端拿那张表导入执行层，塞进去会变成幽灵项），
 * 单独从 /api/sites/pending 取；原因由后端统一给（加密壳 / jar 未装载 / 缺类）。
 */
const pendingSites = ref<PendingSite[]>([])
const pendingFiltered = computed(() => {
  const kw = siteKeyword.value.trim().toLowerCase()
  if (!kw) return pendingSites.value
  return pendingSites.value.filter(
    (p) =>
      (p.name || '').toLowerCase().includes(kw) ||
      (p.key || '').toLowerCase().includes(kw) ||
      (p.api || '').toLowerCase().includes(kw) ||
      (p.reason || '').toLowerCase().includes(kw),
  )
})
/** 批量选择（key 集合）。用 Set 而不是数组：270 项下 includes 是 O(n²)。 */
const siteSel = reactive(new Set<string>())

const siteFilterTabs = computed(() => {
  const all = store.sites
  const cnt = (f: SiteFilter) => all.filter((s) => matchFilter(s, f)).length
  return [
    { id: 'all' as const, label: '全部', n: all.length },
    { id: 'skipped' as const, label: '已跳过', n: cnt('skipped') },
    { id: 'failed' as const, label: '失效', n: cnt('failed') },
    { id: 'unsupported' as const, label: '待配置', n: cnt('unsupported') },
    { id: 'disabled' as const, label: '已停用', n: cnt('disabled') },
    { id: 'ok' as const, label: '正常', n: cnt('ok') },
    // 配置里有、但本构建加载不了（jar 未装 / 加密壳 / 缺类）
    { id: 'pending' as const, label: '未生效', n: pendingSites.value.length },
  ]
})

function matchFilter(s: Site, f: SiteFilter): boolean {
  const h = s.health
  switch (f) {
    case 'skipped':
      return !!(h && (h.skippedFailed || h.skippedTimeout))
    case 'failed':
      return !!h?.skippedFailed
    case 'unsupported':
      return !!h?.unsupported && !h?.disabled
    case 'disabled':
      return !!h?.disabled
    case 'ok':
      return !h?.disabled && !h?.unsupported && !h?.skippedFailed && !h?.skippedTimeout
    default:
      return true
  }
}

/** 当前筛选 + 关键词之后实际展示的源。 */
const visibleSites = computed(() => {
  // 「未生效」是另一张表（pendingSites），不要拿 store.sites 冒充
  if (siteFilter.value === 'pending') return []
  const kw = siteKeyword.value.trim().toLowerCase()
  return store.sortedSites.filter((s) => {
    if (!matchFilter(s, siteFilter.value)) return false
    if (!kw) return true
    return (
      (s.name || '').toLowerCase().includes(kw) ||
      (s.key || '').toLowerCase().includes(kw) ||
      (s.api || '').toLowerCase().includes(kw)
    )
  })
})

/** 当前可见的是否全选中（用于表头全选态）。 */
const allVisibleSelected = computed(
  () => visibleSites.value.length > 0 && visibleSites.value.every((s) => siteSel.has(s.key)),
)
const selCount = computed(() => siteSel.size)

function toggleSel(key: string) {
  if (siteSel.has(key)) siteSel.delete(key)
  else siteSel.add(key)
}

function toggleSelAll() {
  if (allVisibleSelected.value) {
    visibleSites.value.forEach((s) => siteSel.delete(s.key))
  } else {
    visibleSites.value.forEach((s) => siteSel.add(s.key))
  }
}

function clearSel() {
  siteSel.clear()
}

/**
 * 批量停用 / 恢复。
 *
 * 走**一次** `setSitesDisabled(sites[], disabled)` 而不是循环调用：
 * 后端一次事务里改完并只落盘一次；循环调用在 100+ 源时会触发 100 次
 * `/api/sites` 重拉，界面卡死。
 */
async function batchSetDisabled(disabled: boolean) {
  const keys = [...siteSel]
  if (!keys.length) return
  healthBusy.value = '__batch__'
  healthMsg.value = ''
  try {
    const r = await setSitesDisabled(keys, disabled)
    const okN = (r.updated || []).length
    const unkN = (r.unknown || []).length
    healthMsgOk.value = unkN === 0
    healthMsg.value = disabled
      ? `已停用 ${okN} 个源${unkN ? `，${unkN} 个后端不认识` : ''}`
      : `已恢复 ${okN} 个源${unkN ? `，${unkN} 个后端不认识` : ''}`
    siteSel.clear()
    await Promise.all([store.refreshSites(), loadHealthDetail()])
  } catch (e: any) {
    healthMsgOk.value = false
    healthMsg.value = `批量操作失败：${e?.message || String(e)}`
  } finally {
    healthBusy.value = ''
  }
}

/**
 * 一键停用「当前筛选里全部失效源」。
 *
 * 这是这个弹框最有用的按钮：OECT 上 141 个失效源每次搜索各烧 3 秒，
 * 停用后立刻变成只跑能用的那些。比逐个点省几百次点击。
 */
async function disableAllVisible() {
  if (!visibleSites.value.length) return
  const label = siteFilterTabs.value.find((t) => t.id === siteFilter.value)?.label || '当前'
  if (
    !(await confirmDialog(
      `停用「${label}」里的全部 ${visibleSites.value.length} 个源？\n\n停用后搜索/直播/探活都会跳过它们，可在「已停用」里随时恢复。`,
    ))
  )
    return
  visibleSites.value.forEach((s) => siteSel.add(s.key))
  await batchSetDisabled(true)
}

/**
 * 探测选中源（不传 = 探测当前筛选里的全部）。
 *
 * 用途：批量停用之前先确认它们是不是真的坏了 —— 有些源只是上一轮
 * 网络抖动被误判成失效，探一次就能翻案。
 */
async function probeSelected() {
  const keys = siteSel.size ? [...siteSel] : visibleSites.value.map((s) => s.key)
  if (!keys.length) return
  probing.value = true
  healthMsg.value = ''
  try {
    const r = await probeSites(keys)
    const okN = (r.results || []).filter((x) => x.ok).length
    healthMsgOk.value = true
    healthMsg.value = `探测 ${r.probed} 个源：${okN} 个可用，${r.probed - okN} 个失败`
    await Promise.all([store.refreshSites(), loadHealthDetail()])
  } catch (e: any) {
    healthMsgOk.value = false
    healthMsg.value = `探测失败：${e?.message || String(e)}`
  } finally {
    probing.value = false
  }
}


// ---------------------------------------------------------------- 源健康（探活 / 停用）
//
// 判定只有后端 `isSiteSkipped()` 一处（搜索 / 直播 / 探活共用），前端不做二次判定：
// 在这里再算一次"失败几次算坏"就会变成两套规则，改一处另一处静默失效。

/** 正在切换停用状态的站点 key */
const healthBusy = ref('')
const healthMsg = ref('')
/** 消息语义：true=成功（绿），false=失败/需注意（黄） */
const healthMsgOk = ref(true)
const probing = ref(false)

/**
 * `/api/health/state` 的逐站明细。
 *
 * `/api/sites` 的 health 块只给 ok/fail/degraded/disabled/lastError；
 * streak（连续失败几次）与最近成功/失败时间只有 state 端点有。
 * 用它补 tooltip 里的细节，列表本身仍以 /api/sites 为准——
 * 站点表才是"有哪些源"的真源，state 只记"调过的源"。
 */
const healthDetailMap = ref<Record<string, { streak: number; lastOkAt: number; lastFailAt: number }>>({})

/** 时间戳转"几分钟前"，0 显示成"—" */
function ago(at: number): string {
  if (!at) return '—'
  const d = Math.max(0, Date.now() - at)
  const m = Math.floor(d / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return `${m} 分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} 小时前`
  return `${Math.floor(h / 24)} 天前`
}

async function loadHealthDetail() {
  try {
    const s = await fetchHealthState()
    const map: Record<string, { streak: number; lastOkAt: number; lastFailAt: number }> = {}
    for (const key of Object.keys(s.sites || {})) {
      const v = s.sites[key]
      map[key] = { streak: v.streak, lastOkAt: v.lastOkAt, lastFailAt: v.lastFailAt }
    }
    healthDetailMap.value = map
  } catch {
    /* state 拿不到不影响列表：/api/sites 那份已经够渲染 */
  }
}

/**
 * 状态文案。三种"不可用"必须分开显示，因为**该做的事完全不同**：
 *   · 已停用     —— 用户自己关的，点恢复
 *   · 待配置     —— 环境不支持（drpy 缺运行时、ext 为空），去修配置
 *   · 反复失败   —— 源站真挂了，等它自己恢复（不影响其他源）
 *
 * 混成一个"不可用"的后果：实测有 229 个 drpy 源被当成"降级"，
 * 用户看到 "229/270 降级" 会以为全线崩溃，实际一个都没坏。
 */
function healthLabel(s: Site): string {
  const h = s.health
  if (!h) return '未探测'
  if (h.disabled) return '已停用'
  if (h.unsupported) return '待配置'
  /*
   * ★ 跳过状态优先于"反复失败"显示，因为它才是**搜索时的真实行为**。
   *
   * 两者判据不同：degraded 是"连续失败 3 次"，而 skippedFailed 要求
   * "累计失败 ≥5 次且 30 分钟内无成功"。实测大量源显示 degraded=true
   * 但其实没被跳过（轮次间隔里成功过），用户会问"为什么它降级了还在搜"。
   * 反过来更糟：源已经被跳过，界面却只说"反复失败"，用户看不出它不参与。
   */
  if (h.skippedFailed) return '已跳过·失效'
  if (h.skippedTimeout) return '已跳过·超时'
  if (h.degraded) return '反复失败'
  if (h.fail > 0 && h.ok === 0) return '未成功'
  if (h.fail > 0) return '偶发失败'
  return '正常'
}

function healthClass(s: Site): string {
  const h = s.health
  if (!h || (h.ok === 0 && h.fail === 0 && !h.disabled)) return 'bg-card text-ink-3'
  if (h.disabled) return 'bg-hot/15 text-hot'
  // 待配置用中性色而不是警示色：它不是故障，是还没配好
  if (h.unsupported) return 'bg-card text-ink-3'
  // 被跳过 = 不参与搜索，与"偶发失败"必须视觉可分
  if (h.skippedFailed || h.skippedTimeout) return 'bg-hot/10 text-hot'
  if (h.degraded) return 'bg-warn/15 text-warn'
  if (h.fail > 0 && h.ok === 0) return 'bg-hot/10 text-hot'
  if (h.fail > 0) return 'bg-warn/10 text-warn'
  return 'bg-ok/15 text-ok'
}

/** 每站一行的补充信息：优先显示最近错误，没有错误就显示成败次数 */
function healthDetail(s: Site): string {
  const h = s.health
  if (!h) return '还没有调用过'
  const counts = `成功 ${h.ok} / 失败 ${h.fail}`
  if (h.lastError) return `${counts} · ${h.lastError}`
  return counts
}

/**
 * 连续失败次数与最近成功时间只有 `/api/health/state` 有（`/api/sites` 的
 * health 块不带这两个字段）。放进 tooltip 而不是主行：
 * 主行要留给"最近错误"——那才是用户判断"这个源是不是挂了"的直接证据。
 */
function detailTip(s: Site): string {
  const d = healthDetailMap.value[s.key]
  if (!d) return ''
  return `；连续失败 ${d.streak} 次，最近成功 ${ago(d.lastOkAt)}，最近失败 ${ago(d.lastFailAt)}`
}

/**
 * 停用 / 恢复。
 *
 * 恢复时后端会同时清零 streak 与 degraded，语义是"立刻再试一次"，
 * 而不是"解除锁定后再等 30 分钟窗口"——所以按钮文案写「恢复」而不是「启用」。
 */
async function toggleSiteDisabled(s: Site) {
  const next = !s.health?.disabled
  healthBusy.value = s.key
  healthMsg.value = ''
  try {
    const r = await setSitesDisabled([s.key], next)
    if (r.unknown && r.unknown.length) {
      healthMsgOk.value = false
      healthMsg.value = `后端不认识站点 ${r.unknown.join('、')}（站点表可能刚变过）`
    } else {
      healthMsgOk.value = true
      healthMsg.value = next
        ? `已停用 ${s.name}：搜索 / 直播 / 探活都会跳过它`
        : `已恢复 ${s.name}：自动降级状态一并清除，下次调用会立刻再试`
    }
    // 健康标记来自 /api/sites，改完必须重拉，否则徽标还是旧的
    await Promise.all([store.refreshSites(), loadHealthDetail()])
  } catch (e: any) {
    healthMsgOk.value = false
    healthMsg.value = `操作失败：${e?.message || String(e)}`
  } finally {
    healthBusy.value = ''
  }
}

// ---------------------------------------------------------------- 离线缓存
//
// 目标是**断网可看**而不是省流量：分片与清单都落在后端数据目录，
// 清单里的引用改成同目录相对文件名（断网时没有服务端能拼前缀）。
const cacheBytes = ref(0)
const danmakuBytes = ref<number | null>(null)
const danmakuCount = ref(0)
/** 配额（GB）。0 = 不限制。后端 policy.json 的 cacheQuotaGb */
const cacheQuotaGb = ref(8)
/** lru = 超了自动删最久没看的；off = 只提示不删 */
const cacheEvictMode = ref<'lru' | 'off'>('lru')
/**
 * 淘汰策略选项（给 TileSelect 用，与其他设置项同一套磁贴视觉）。
 *
 * ★ 文案必须 ≤ 5 字（2026-11-02 实测）：存储区的磁贴是 4 列网格，
 *   TV 视口（1138 CSS）下每块只有 **193px**，去掉面板内边距和箭头后，
 *   「超限时」这个标题只剩 ~110px 给值。原来写「自动删最久没看的」（9 字，
 *   实测 148px）会把标题挤成 0 宽（磁贴上只剩值，看不出选的是什么）。
 *   含义由下面那段说明补全："超出后按「最久没看过」淘汰…"。
 */
const evictModeOptions = [
  { value: 'lru', label: '自动删最旧' },
  { value: 'off', label: '只提示不删' },
]
/**
 * 弹幕缓存配额与淘汰（policy.json 的 danmakuCacheQuotaMb /
 * danmakuCacheEvictMode）。语义与视频缓存那对完全一致，只是单位用 MB
 * —— 弹幕一集才 ~1.4MB，用 GB 会全是小数。
 */
const danmakuQuotaMb = ref(200)
const danmakuEvictMode = ref<'lru' | 'off'>('lru')
const dmQuotaMsg = ref('')
/** 缓存实际落盘目录，用于告诉用户"存在哪" */
const cacheDir = ref('')
/**
 * policy.json 里 cacheDir 的原始值。
 *
 * 与 `cacheDir`（**已解析**的绝对路径）是两个东西：用户要编辑的是 policy 值，
 * 展示"实际落在哪"的是解析后的路径。混用会让输入框里出现后端拼好的绝对路径，
 * 一保存就把相对路径写成了死路径。
 */
const cacheDirPolicy = ref('')
/** policy 的 cacheDir 与进程内已解析根不一致 → 改了还没重启 */
const cacheDirReloadRequired = ref(false)
const cacheCfgMsg = ref('')
/** 缓存位置的保存结果单独一条：复用 cacheCfgMsg 会把它显示在配额那一行，用户找不到 */
const cacheDirMsg = ref('')

async function loadCache() {
  const r = await listCache()
  cacheBytes.value = r.totalBytes || 0
  danmakuBytes.value = typeof r.danmakuBytes === 'number' ? r.danmakuBytes : null
  danmakuCount.value = r.danmakuCount || 0
  if (typeof r.quotaBytes === 'number') {
    cacheQuotaGb.value = Math.round((r.quotaBytes / 1024 ** 3) * 10) / 10
  }
  if (r.evictMode === 'off' || r.evictMode === 'lru') cacheEvictMode.value = r.evictMode
  if (typeof r.cacheDir === 'string') cacheDir.value = r.cacheDir
  if (typeof r.cacheDirPolicy === 'string') cacheDirPolicy.value = r.cacheDirPolicy
  cacheDirReloadRequired.value = !!r.cacheDirReloadRequired
}

/**
 * 保存配额与淘汰模式。
 *
 * 写进 policy.json（后端 VideoCache.quotaBytes() / evictMode() 每次读它），
 * 所以要连同现有 policy 一起提交，不能只发这两个字段——会把别的设置冲掉。
 */
async function saveCacheConfig() {
  cacheCfgMsg.value = ''
  try {
    const cur = await fetchPolicy()
    await savePolicy({
      ...cur,
      cacheQuotaGb: Number(cacheQuotaGb.value) || 0,
      cacheEvictMode: cacheEvictMode.value,
    })
    cacheCfgMsg.value = '已保存'
    await loadCache()
  } catch (e) {
    cacheCfgMsg.value = e instanceof Error ? e.message : '保存失败'
  }
}

/**
 * 保存弹幕配额与淘汰模式（policy.json 的 danmakuCacheQuotaMb /
 * danmakuCacheEvictMode）。
 *
 * 与 saveCacheConfig 同样必须先读现有 policy 再合并写回 —— 只发这两个
 * 字段会把 danmakuApi / danmakuOrder / 搜索缓存时长等一起冲掉。
 */
async function saveDanmakuQuota() {
  dmQuotaMsg.value = ''
  try {
    const cur = await fetchPolicy()
    await savePolicy({
      ...cur,
      danmakuCacheQuotaMb: Number(danmakuQuotaMb.value) || 0,
      danmakuCacheEvictMode: danmakuEvictMode.value,
    })
    dmQuotaMsg.value = '已保存'
    await loadCache()
  } catch (e) {
    dmQuotaMsg.value = e instanceof Error ? e.message : '保存失败'
  }
}

/**
 * 保存缓存目录。
 *
 * 相对路径按**数据目录**解析（不是进程 cwd），所以填 `mnt/cache` 是可移植的写法；
 * 绝对路径（`D:\\meow-cache`）也能用，PC 端 C 盘小的时候是唯一出路。
 * 后端只在启动时解析一次根目录，保存后必须重启才生效——所以保存成功要
 * 明确告诉用户，并在 reloadRequired 为真时持续显示提示。
 */
async function saveCacheDir() {
  cacheDirMsg.value = ''
  try {
    const cur = await fetchPolicy()
    await savePolicy({ ...cur, cacheDir: cacheDirPolicy.value.trim() })
    cacheDirMsg.value = '已保存（需重启后端才生效）'
    // 重新拉一次：让 reloadRequired / policy 值都来自后端，而不是本地猜
    await loadCache()
  } catch (e) {
    cacheDirMsg.value = e instanceof Error ? e.message : '保存失败'
  }
}

/** 存储区的清理结果提示。不能叫 danmakuMsg：那一个属于「弹幕源」小节 */
const dmCacheMsg = ref('')

/**
 * 清弹幕缓存。
 *
 * <p>为什么要给入口：弹幕缓存原来只做"读过期不命中"，文件本身永不删除。
 * OECT 上实测攒到 23 个文件 / 22MB，用户既看不到也清不掉。
 *
 * <p>两个档位：过期（默认，30 天）与全部。默认给"只清过期"——
 * 清全部会把昨天刚缓存、今晚火车上要看的弹幕一起删掉。
 */
async function clearDanmakuCache(all: boolean) {
  if (all && !(await confirmDialog('清空全部弹幕缓存？离线播放时会重新联网抓取，断网则没有弹幕。', { danger: true })))
    return
  dmCacheMsg.value = ''
  try {
    const r = await controlCache('clearDanmaku', [], all ? 'all' : 'expired')
    dmCacheMsg.value = r.removed
      ? `已清除 ${r.removed} 个（剩 ${fmtBytes(r.bytes || 0)}）`
      : '没有可清理的弹幕'
    await loadCache()
  } catch (e) {
    dmCacheMsg.value = e instanceof Error ? e.message : '清理失败'
  }
}

function fmtBytes(n: number | null): string {
  // null = 后端没给这个字段（老版本），不能说成 0
  if (n === null) return '—'
  if (!n) return '0'
  const gb = n / 1024 / 1024 / 1024
  if (gb >= 1) return gb.toFixed(2) + ' GB'
  return Math.round(n / 1024 / 1024) + ' MB'
}

// ---------------------------------------------------------------- 弹幕屏蔽词
const dmBlockInput = ref(getPrefs().danmakuBlock || '')
const dmBlockMsg = ref('')

/**
 * 保存屏蔽词。
 *
 * <p>写进 prefs 而不是只存组件里：屏蔽词是跨集生效的偏好，
 * 存组件里换一集就回默认了（用户会以为"设了没用"）。
 */
function saveDmBlock() {
  const v = dmBlockInput.value.trim()
  if (v === (getPrefs().danmakuBlock || '')) return
  update('danmakuBlock', v)
  dmBlockMsg.value = '已保存'
  window.setTimeout(() => (dmBlockMsg.value = ''), 2000)
}

void loadCache()

// 本地弹幕（IndexedDB）：后端磁盘缓存在 Web 部署时够不着——手机断网连不上服务器。
// 这份落在浏览器里才是真离线。
const dmLocal = ref({ entries: 0, bytes: 0 })
const dmLocalMsg = ref('')

async function loadDmLocal() {
  const { localDanmakuStats } = await import('@/components/player/danmakuStore')
  dmLocal.value = await localDanmakuStats()
}

async function clearDmLocal() {
  const { clearLocalDanmaku } = await import('@/components/player/danmakuStore')
  const n = await clearLocalDanmaku()
  dmLocalMsg.value = `已清除 ${n} 集`
  await loadDmLocal()
}

void loadDmLocal()

// ---------------------------------------------------------------- 配置源
//
// 这是「导入别人的 CatVod 配置」的入口。没有它，只有 curl 能加源——
// 三个端（Web/PC/TV）都等于没有这个能力。
// 源导入输入框预填一个可用的示例地址；用户仍要自己点「预览/导入」。
// 连接设置弹层只在用户**改过**输入框时才沿用它，避免 TV 首连时被默认值带着走。
const cfgUrl = ref('http://肥猫.net')
const cfgUrlDirty = ref(false)

/**
 * 地址一改，之前的预览就不再对应当前地址。
 *
 * 原来只置 cfgUrlDirty（那是给连接弹框预填用的），预览面板会留着；而
 * 「确认导入」优先用 `cfgPreview.url`，于是用户改了地址、看着旧面板点确认，
 * 实际导入的还是旧地址。这里直接作废——宁可多点一次「读取」，也不能导错源。
 */
function onCfgUrlInput() {
  cfgUrlDirty.value = true
  const p = cfgPreview.value
  if (p && cfgUrl.value.trim() !== p.url) cfgPreview.value = null
}
const cfgBusy = ref(false)
const cfgMsg = ref('')
const cfgMsgOk = ref(false)
const cfgSkipped = ref<ConfigSkippedSite[]>([])
const cfgSummary = ref<{ sites: number; total: number; url: string } | null>(null)
/** 导入后的可行动结论（「已写入 N 站，当前只有 M 站可用」） */
const cfgHint = ref('')
/** 配置里有、本构建加载不了的站点（加密壳 / jar 未装载 / 缺类） */
const cfgPending = ref<PendingSite[]>([])
/** 每条外部 jar 的装载结果（md5 不匹配 / 加密壳 / 下载失败） */
const cfgJars = ref<NonNullable<ConfigImportResult['jars']>>([])

/**
 * 导入分两步：先预览（后端 dryRun，只算不写），看清会加什么/丢什么，再确认。
 *
 * 默认**追加**：替换会整表换掉，用户点错一个仓自己加的站就全没了，
 * 而回滚只有一层 .bak。破坏性动作必须主动选。
 */
const importMode = ref<ImportMode>('merge')
/** 追加模式下同 key 冲突：留现有的（保守）/ 用导入的 / 两个都要 */
const importConflict = ref<ImportConflict>('keep')
/**
 * 用导入配置的顶层 spider 覆盖本地。
 *
 * <p>默认关：本地手改过的 jar 地址不该被一次导入冲掉。
 * 但「从全量版迁移到纯壳」时必须开——老配置的 spider 指向已失效的上游
 * dex 包，不覆盖就永远 ClassNotFoundException（实测）。
 */
const cfgReplaceSpider = ref(false)
/** 给 TileSelect 用（设置页禁止原生 select，深色主题下它会弹白底系统菜单） */
const conflictOptions = [
  { value: 'keep', label: '保留现有的', hint: '导入的同名站被忽略' },
  { value: 'replace', label: '用导入的覆盖', hint: '现有同名站的 api 会被换掉' },
  { value: 'both', label: '两个都要', hint: '非 csp_ 站另起 key#N；csp_ 天然共存' },
]
/** 预览结果；非 null 时页面上出现确认卡片 */
const cfgPreview = ref<{
  url: string
  sites: number
  /** 本地站点表原始条数（含未实现的），与 diff.total 同口径 */
  existing: number
  /** 内置基线条数（替换删不掉的那批） */
  builtin: number
  skipped: ConfigSkippedSite[]
  /** 读取时的策略那份（老后端可能只有它） */
  diff: ConfigImportDiff
  /**
   * 四套 diff（merge×3 冲突策略 + replace）。有它才能「切策略不重新联网」，
   * 没有时（老后端）退化成 cfgPreview.diff。
   */
  plans?: ConfigImportResult['plans']
  /** 读取时用的策略；老后端没有 plans 时用来判断"切过之后数字就不对了" */
  readMode: ImportMode
  readConflict: ImportConflict
} | null>(null)

const EMPTY_IMPORT_DIFF: ConfigImportDiff = {
  toAdd: [],
  toUpdate: [],
  conflicts: [],
  unchanged: 0,
  removed: [],
  total: 0,
  healthLost: [],
}

/**
 * 当前策略对应的那份 diff。
 *
 * 后端一次算齐四套，切换 追加/替换/冲突策略 只是换索引——不重新请求、不清空面板。
 * 老后端没有 plans 时回退到读取时那一份。
 */
const cfgDiff = computed<ConfigImportDiff>(() => {
  const p = cfgPreview.value
  if (!p) return EMPTY_IMPORT_DIFF
  const fromPlans =
    importMode.value === 'merge'
      ? p.plans?.merge?.[importConflict.value]
      : p.plans?.replace
  return fromPlans || p.diff
})

/**
 * 后端没给 plans，且用户已经切过策略 → 面板上的数字不再对应所选策略。
 *
 * 只有这时才要求重新读取；新后端切多少次都是实时准确的，不该打扰用户。
 */
const cfgPlanStale = computed(() => {
  const p = cfgPreview.value
  if (!p || p.plans) return false
  if (p.readMode !== importMode.value) return true
  return importMode.value === 'merge' && p.readConflict !== importConflict.value
})

/**
 * 确认按钮上写清楚"这一下会按哪个策略执行"。
 *
 * 原来只有「确认导入」四个字：用户在面板里切过策略后，按钮不跟着变，
 * 得自己记住刚才选的是什么——写进按钮就不用了。
 */
const cfgApplyLabel = computed(() => {
  if (importMode.value === 'replace') return '替换整表'
  const c = conflictOptions.find((o) => o.value === importConflict.value)
  return `追加 · ${c?.label ?? '保留现有的'}`
})

// ---------------------------------------------------------------- 直播源
//
// 单独一张表（config.lives），和站点配置互不影响。原来是"想加一条直播源
// 只能导入整份配置"，代价是站点表被换掉；现在有独立端点就直接调。
const liveSources = ref<LiveSource[]>([])
/** 还没被删的内置源 URL（列表里这些条目打「内置」标签；可逐条删、删了不再来） */
const liveBuiltin = ref<string[]>([])
/**
 * 多源合并：配置了多条源时合成一个台列表（同名台合并成多线路）。
 * 关掉就是"列出多个源、一次只用一条"。
 */
const liveMerge = ref(getPrefs().liveMerge !== false)
function setLiveMerge(v: boolean) {
  liveMerge.value = v
  savePrefs({ liveMerge: v })
}
const liveBusy = ref('')
const liveMsg = ref('')
const liveName = ref('')
const liveUrlInput = ref('')

/** 正在编辑的源 url（空 = 没有在编辑） */
const liveEditing = ref('')
const liveEditName = ref('')
const liveEditUrl = ref('')
/** 每条源的测试结果：url → 文案（点「测试」才拉，不在打开页面时就拉） */
const liveTestMsg = ref<Record<string, string>>({})
const liveTestBusy = ref('')
/** 有内置源被删时才显示「恢复内置源」 */
const canResetBuiltin = computed(() => liveBuiltin.value.length < 2)

/** 多仓探测结果（null = 还没探过 / 该地址不是多仓） */
const depotEntries = ref<DepotEntry[]>([])
const depotBusy = ref(false)
const depotMsg = ref('')
/** 多仓索引地址 / 当前生效的仓 / 上次探测时间（来自后端缓存，重启后仍在） */
const depotIndex = ref('')
const depotActive = ref('')
const depotProbedAt = ref(0)
const depotStale = ref(false)
/** 导入前留下的旧配置快照 */
const cfgBackup = ref<ConfigBackupInfo>({ exists: false })
const restoreBusy = ref(false)

/**
 * 读多仓缓存（不探测）：重启后设置页直接列出已知仓，不用重新粘贴索引地址。
 *
 * <p>Fongmi 把每个仓存成一条 Config 记录，切仓时直接选；我们等价地把
 * 「索引 + 仓列表 + 当前仓」落在 config.depot.json，语义对齐。
 */
async function loadDepotCache() {
  try {
    const r = await getCachedDepot()
    if (!r.cached) return
    depotIndex.value = r.index || ''
    depotActive.value = r.active || ''
    depotProbedAt.value = r.probedAt || 0
    depotStale.value = r.stale === true
    if (r.entries?.length) {
      depotEntries.value = r.entries
      // jarKnown === false = 本机没有内置 jar，inJar 是未知而不是 0：不计入"能用"
      const usable = r.entries.filter(
        (e) => e.ok && e.jarKnown !== false && (e.inJar || 0) + (e.direct || 0) > 0,
      )
      depotMsg.value = depotProbedAt.value
        ? `共 ${r.entries.length} 个仓，其中 ${usable.length} 个我们有站点能加载${depotStale.value ? '（超过 24 小时，建议刷新）' : ''}`
        : `共 ${r.entries.length} 个仓（还没探测过，点「刷新探测」看每个仓能用几站）`
    }
  } catch {
    /* 缓存读不到不影响导入，静默 */
  }
}
void loadDepotCache()

/**
 * 探测多仓。
 *
 * <p>为什么不直接导入：多仓索引里十几个仓质量差很多（实测同一份索引里，
 * 有的仓 96 站全部能用，有的仓 298 站里只有 8 站能加载）。而导入是**覆盖式**的，
 * 用户随便挑一个就等于把现有配置冲掉。所以先探测、再让用户看着数据挑。
 *
 * <p>输入框为空时用缓存里的索引地址（「刷新探测」直接可用）。
 */
async function probeDepot() {
  const url = (cfgUrl.value.trim() || depotIndex.value).trim()
  if (!/^https?:\/\//i.test(url)) {
    depotMsg.value = '请先填 http(s) 开头的配置地址'
    return
  }
  depotBusy.value = true
  depotMsg.value = ''
  try {
    const r = await probeConfigDepot(url)
    if (!r.ok) {
      depotMsg.value = r.msg || '探测失败'
      return
    }
    depotEntries.value = r.entries || []
    depotIndex.value = r.index || url
    depotActive.value = r.active || ''
    depotProbedAt.value = r.probedAt || Date.now()
    depotStale.value = false
    const usable = depotEntries.value.filter(
      (e) => e.ok && e.jarKnown !== false && (e.inJar || 0) + (e.direct || 0) > 0,
    )
    depotMsg.value = depotEntries.value.length
      ? `共 ${depotEntries.value.length} 个仓，其中 ${usable.length} 个我们有站点能加载`
      : '这个地址里没有仓'
  } catch (e) {
    depotMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    depotBusy.value = false
  }
}

/**
 * 切到某个仓：URL 进输入框，走同一个预览→确认导入流程。
 *
 * <p>列表**不清空**：切错了/想切回来还要用，清掉就得重新探测一遍。
 */
async function importDepotEntry(e: DepotEntry) {
  // 缓存里"直接导入索引"生成的条目没有探测数据（ok=undefined），一样允许切
  if (e.ok === false) return
  cfgUrl.value = e.url
  await doPreviewImport()
}

/** 探测时间的展示文案（分钟/小时前）。 */
const depotAgo = computed(() => {
  const t = depotProbedAt.value
  if (!t) return ''
  const m = Math.max(0, Math.round((Date.now() - t) / 60000))
  if (m < 1) return '刚刚'
  if (m < 60) return `${m} 分钟前`
  return `${Math.round(m / 60)} 小时前`
})

async function loadCfgBackup() {
  const r = await getConfigBackup()
  cfgBackup.value = r.backup
}
void loadCfgBackup()

/** 撤销上一次导入：用备份覆盖回来。 */
async function restoreLastConfig() {
  if (!cfgBackup.value.exists) return
  if (
    !(await confirmDialog(
      `恢复导入前的配置（${cfgBackup.value.sites ?? '?'} 站）？\n当前的站点配置会被替换掉。`,
      { danger: true },
    ))
  )
    return
  restoreBusy.value = true
  try {
    const r = await restoreConfigBackup()
    if (!r.ok) {
      cfgMsgOk.value = false
      cfgMsg.value = r.msg || '恢复失败'
      return
    }
    cfgMsgOk.value = true
    cfgMsg.value = `已恢复，当前 ${r.sites ?? 0} 站`
    cfgSummary.value = null
    cfgSkipped.value = []
    await loadCfgBackup()
    // 站点表换了：不刷新的话界面上还是新导入那批
    await store.refreshSites()
    await store.boot()
  } finally {
    restoreBusy.value = false
  }
}

/** 确认新配置没问题，丢掉备份。 */
async function discardCfgBackup() {
  await discardConfigBackup()
  await loadCfgBackup()
}

/** 打开设置页时回显上次导入的摘要，避免"不知道当前配置哪来的" */
async function loadLastImport() {
  const r = await getLastConfigImport()
  if (r?.imported && r.sites != null) {
    cfgSummary.value = { sites: r.sites, total: r.totalSites || r.sites, url: r.url || '' }
    cfgUrl.value = r.url || cfgUrl.value
  }
}
void loadLastImport()

// ---------------------------------------------------------------- 运行时与过滤
//
// 这两项只影响后端行为，改完立即生效（policy.json 每次请求都读）。
// 放设置页是为了不再要求用户去编辑 JSON —— 之前它们只有文件入口。
const jsRuntime = ref('')
const filterAds = ref(false)
const advBusy = ref(false)
const advMsg = ref('')
let advTimer: number | undefined

async function loadAdvanced() {
  const p = await fetchPolicy()
  jsRuntime.value = String(p.jsRuntime || '')
  filterAds.value = p.filterAds === true
}
void loadAdvanced()

async function saveAdvanced() {
  advBusy.value = true
  try {
    await savePolicy({ jsRuntime: jsRuntime.value.trim(), filterAds: filterAds.value })
    advMsg.value = '已保存'
  } catch (e: any) {
    advMsg.value = '保存失败：' + (e?.message || String(e))
  } finally {
    advBusy.value = false
    clearTimeout(advTimer)
    advTimer = window.setTimeout(() => (advMsg.value = ''), 3000)
  }
}

// ---------------------------------------------------------------- 噪音过滤
//
// 词表存后端 policy.json 的 searchNoise（字符串，与弹幕屏蔽词同一套分隔符约定）。
// 为什么不存前端 prefs：过滤发生在**后端聚合**里，存前端只有当前设备生效，
// TV 上扫码改的词表到了 PC 就没了。
const noiseInput = ref('')
const noiseMsg = ref('')
/**
 * 词表的作用方式。
 *   filter —— 命中即隐藏（默认）
 *   rank   —— 保留但沉到最后
 * 两者的差别对用户是"要不要看到"，不是技术细节，所以放在编辑器上方而不是
 * 藏进高级选项。
 */
const noiseMode = ref<'filter' | 'rank'>('filter')
const noiseModeOptions = [
  { value: 'filter', label: '过滤隐藏' },
  { value: 'rank', label: '仅排序降级' },
]
/** 生效词数（单字被后端忽略，所以用它而不是直接数逗号） */
const noiseCount = computed(() => {
  return noiseInput.value
    .split(/[,，;；\r\n]+/)
    .map((s) => s.trim())
    .filter((s) => s.length >= 2).length
})
let noiseTimer: number | undefined

/**
 * 推荐词表。取自前端 searchRank.ts 的 UGC_RE，但只保留**明确是 UGC 搬运**的词：
 * 去掉「直播/回放/录播」—— 那可能是用户真想看的直播回放；也去掉「电视剧/电影」
 * 这种类型词 —— 那是正常分类而不是噪音。
 */
const NOISE_PRESET = [
  '解说',
  '讲解',
  '解读',
  '混剪',
  '剪辑',
  '合集',
  '一口气',
  '影评',
  '拆解',
  'reaction',
]

async function loadNoise() {
  try {
    const p = await fetchPolicy()
    noiseInput.value = String(p.searchNoise || '')
    noiseMode.value = p.searchNoiseMode === 'rank' ? 'rank' : 'filter'
  } catch {
    /* 读不到就留空，用户仍可填 */
  }
}
void loadNoise()

async function saveNoise() {
  try {
    await savePolicy({
      searchNoise: noiseInput.value.trim(),
      searchNoiseMode: noiseMode.value,
    })
    noiseMsg.value = noiseInput.value.trim() ? '已保存，下次搜索生效' : '已关闭噪音过滤'
  } catch (e: any) {
    noiseMsg.value = '保存失败：' + (e?.message || String(e))
  } finally {
    clearTimeout(noiseTimer)
    noiseTimer = window.setTimeout(() => (noiseMsg.value = ''), 3000)
  }
}

function fillNoisePreset() {
  noiseInput.value = NOISE_PRESET.join(', ')
  void saveNoise()
}

function clearNoise() {
  noiseInput.value = ''
  void saveNoise()
}

/**
 * 第一步：预览。后端 dryRun，只算不写。
 *
 * 为什么不让"导入"一键完成：导入是破坏性的，用户看不到自己会丢什么
 * 就点按钮，等于闭着眼换配置；而回滚只有一层 .bak，撤销机会只有一次。
 */
async function doPreviewImport() {
  const url = cfgUrl.value.trim()
  if (!/^https?:\/\//i.test(url)) {
    cfgMsgOk.value = false
    cfgMsg.value = '请填 http(s) 开头的配置地址'
    return
  }
  cfgBusy.value = true
  cfgMsg.value = ''
  cfgSkipped.value = []
  cfgPreview.value = null
  cfgHint.value = ''
  cfgPending.value = []
  cfgJars.value = []
  try {
    const r = await importConfig(url, {
      mode: importMode.value,
      onConflict: importConflict.value,
      dryRun: true,
      replaceSpider: cfgReplaceSpider.value,
    })
    if (!r.ok) {
      cfgMsgOk.value = false
      cfgMsg.value = r.msg || '读取配置失败'
      return
    }
    cfgMsgOk.value = true
    cfgSkipped.value = r.skipped || []
    cfgJars.value = r.jars || []
    cfgHint.value = ''
    cfgPending.value = []
    cfgPreview.value = {
      url,
      sites: r.sites || 0,
      existing: r.existingSites ?? r.totalSites ?? 0,
      builtin: r.builtinSites || 0,
      skipped: r.skipped || [],
      diff: r.diff || {
        toAdd: [],
        toUpdate: [],
        conflicts: [],
        unchanged: 0,
        removed: [],
        total: 0,
        healthLost: [],
      },
      plans: r.plans,
      readMode: importMode.value,
      readConflict: importConflict.value,
    }
    // 消息只说"读到了什么"，不再提某种策略——策略改成读完再选（见 cfgDiff）
    cfgMsg.value = `读到了 ${r.sites || 0} 个可用站点，选好并法再确认`
  } catch (e: any) {
    cfgMsgOk.value = false
    cfgMsg.value = e?.message || String(e)
  } finally {
    cfgBusy.value = false
  }
}

/**
 * 轮询后台导入，直到有结果（或超时）。
 *
 * 为什么要轮询而不是等那个 POST 响应：确认导入要下载源 jar + dex2jar 转换，
 * iOS 上（OpenJDK Zero 解释执行）一次 1~2 分钟很正常。同步等响应的话
 * WKWebView 的 fetch 会先断开，服务端写响应抛 Broken pipe，界面只能报
 * "导入失败"——而配置其实正在写（2026-11-24 真机实测）。
 *
 * 单次轮询失败不判死：后端正忙时 GET 也可能超时，下一轮继续。
 */
async function waitImportJob(): Promise<ConfigImportResult> {
  const started = Date.now()
  let lastErr = ''
  for (;;) {
    const elapsed = Date.now() - started
    // 真机上首次导入（dex2jar）实测能到 2~3 分钟，给足 10 分钟再判超时；
    // 超时也只提示"后台仍在跑"，绝不把还在干活的导入说成失败
    if (elapsed > 600_000) {
      throw new Error(
        `等了 ${Math.round(elapsed / 1000)} 秒还没结束（后台仍在跑，稍后看站点明细）${
          lastErr ? '：' + lastErr : ''
        }`,
      )
    }
    // 先查再等：Android/TV 上 dex2jvm 是空操作，导入毫秒级完成，
    // 先 sleep 一秒会让用户白等一下
    try {
      const s = await getImportJobState()
      lastErr = ''
      if (s.state === 'converting') {
        /*
         * 站点表已经写进去了（导入本身成功），只是源 jar 还在后台转。
         * 这一档要单独说清楚：用户能立刻用直连站/原生站，jar 站转完自动可用。
         */
        cfgMsg.value = `站点已导入；源 jar 后台转换中…已 ${Math.round(elapsed / 1000)} 秒（完成后自动可用）`
      } else if (s.pending || s.state === 'running') {
        // 后端报了阶段就用它（下载/转换/写入），比只报秒数更能说明"卡在哪"
        cfgMsg.value = s.phase
          ? `正在导入… ${s.phase}（已 ${Math.round(elapsed / 1000)} 秒）`
          : `正在导入…已 ${Math.round(elapsed / 1000)} 秒`
      } else {
        return s
      }
    } catch (e: any) {
      lastErr = e?.message || String(e)
    }
    await new Promise((r) => setTimeout(r, 900))
  }
}

/** 第二步：确认写入。按面板当前所选策略执行（不是读取时那份）。 */
async function doImportConfig() {
  const url = cfgPreview.value?.url || cfgUrl.value.trim()
  if (!/^https?:\/\//i.test(url)) {
    cfgMsgOk.value = false
    cfgMsg.value = '请填 http(s) 开头的配置地址'
    return
  }
  // 按钮已经 disabled，这里再挡一次：回车/脚本路径不能绕过"数字已过期"
  if (cfgPlanStale.value) {
    cfgMsgOk.value = false
    cfgMsg.value = '切换策略后数字没重算，请重新点「读取」'
    return
  }
  cfgBusy.value = true
  cfgMsg.value = '正在导入…'
  try {
    const started = await importConfig(url, {
      mode: importMode.value,
      onConflict: importConflict.value,
      replaceSpider: cfgReplaceSpider.value,
      background: true,
    })
    if (!started.ok) {
      cfgMsgOk.value = false
      cfgMsg.value = started.msg || '导入失败'
      iosNotify('导入失败', cfgMsg.value)
      return
    }
    // 新后端回 pending:true（后台线程在跑）；老后端直接给最终结果
    const r = started.pending ? await waitImportJob() : started
    if (!r.ok) {
      cfgMsgOk.value = false
      cfgMsg.value = r.msg || '导入失败'
      iosNotify('导入失败', cfgMsg.value)
      return
    }
    if (!r.imported) {
      cfgMsgOk.value = false
      cfgMsg.value = r.msg || '导入没有生效，请重试'
      iosNotify('导入失败', cfgMsg.value)
      return
    }
    cfgMsgOk.value = true
    cfgSummary.value = { sites: r.sites || 0, total: r.totalSites || 0, url }
    cfgSkipped.value = r.skipped || []
    cfgHint.value = r.hint || ''
    cfgPending.value = r.pendingSites || []
    cfgJars.value = r.jars || []
    cfgPreview.value = null
    // 多仓：后端已把"当前仓/索引/仓列表"落盘，这里同步本地状态（「当前」标记）
    if (r.active) depotActive.value = r.active
    void loadDepotCache()
    // 系统通知：导入几十秒，用户可能已经切走（2026-11-25 用户要求）
    iosNotify('导入完成', `已按「${cfgApplyLabel.value}」导入 ${r.sites}/${r.totalSites} 站`)
    cfgMsg.value = `已按「${cfgApplyLabel.value}」导入 ${r.sites}/${r.totalSites} 站${
      r.depot?.length ? `，另有 ${r.depot.length} 个多仓地址` : ''
    }`
    // 导入后旧配置已进备份，「恢复上一次」按钮要立刻可用
    cfgBackup.value = r.backup || { exists: false }
    // 站点表变了：立刻刷新，否则用户看到的还是旧列表
    await store.refreshSites()
    await store.boot()
  } catch (e: any) {
    cfgMsgOk.value = false
    cfgMsg.value = e?.message || String(e)
    iosNotify('导入失败', cfgMsg.value)
  } finally {
    cfgBusy.value = false
  }
}

// ---------------------------------------------------------------- 直播源操作

/** 读表失败的原因（空 = 正常）。别把它和"没有配置"混为一谈 */
const liveErr = ref('')

async function refreshLives() {
  try {
    const r = await fetchLives()
    liveSources.value = r.lives
    liveBuiltin.value = r.builtin
    liveErr.value = ''
  } catch (e: any) {
    /*
     * 读不到时**不能把列表画成"还没有配置直播源"**：冷启动阶段网关
     * 还没起来（TV 上预热十几秒），用户看到空列表会以为内置源没了，
     * 甚至去点「快捷添加」（2026-11-11 用户截图就是这个状态）。
     */
    liveErr.value = e?.message || '请求失败'
  }
}

/**
 * 直播源改成**进这一栏才拉**。
 *
 * 原来在 onMounted 里会直接拉一次 /api/live：只要打开设置页就打一次，
 * 而冷缓存时后端要现拉远端 m3u（实测有 6 秒量级的）。用户可能只想改个主题，
 * 不该为此付一次外网请求 + 大 JSON 解析。判据同「维护」分区：滚到/点到才拉。
 */
const livesLoaded = ref(false)

async function ensureLives() {
  if (livesLoaded.value) return
  livesLoaded.value = true
  await refreshLives()
}

async function doAddLive() {
  const url = liveUrlInput.value.trim()
  if (!/^https?:\/\//i.test(url)) {
    liveMsg.value = '请填 http(s) 开头的 m3u / txt 地址'
    return
  }
  liveBusy.value = 'add'
  liveMsg.value = ''
  try {
    const r = await addLiveSource({
      name: liveName.value.trim(),
      url,
      // 加源时先真拉一遍：解析不出频道就说明地址不是播放列表，
      // 存进去只会让直播页多一个打不开的源
      verify: true,
    })
    if (!r.ok) {
      liveMsg.value = r.msg || '这个地址解析不出频道，没保存'
      return
    }
    liveSources.value = r.lives || liveSources.value
    /*
     * POST 回来的 `lives` 可能只是**存储表**（老后端不带内置源，内置源是
     * GET 时虚拟补上的）→ 加完一条自定义源，列表里两条内置源会从界面上
     * 消失（用户以为是"被覆盖了"）。所以统一以 GET 的结果为准。
     */
    await refreshLives()
    liveName.value = ''
    liveUrlInput.value = ''
    liveMsg.value = r.duplicate
      ? '这个地址已经在了'
      : `已添加${r.channels ? `，解析出 ${r.channels} 个频道` : ''}`
  } catch (e: any) {
    liveMsg.value = e?.message || String(e)
  } finally {
    liveBusy.value = ''
  }
}

async function doRemoveLive(url: string) {
  liveBusy.value = url
  liveMsg.value = ''
  try {
    const r = await removeLiveSource(url)
    if (!r.ok) {
      liveMsg.value = r.msg || '删除失败'
      return
    }
    liveSources.value = r.lives || []
    // builtin 也要重读：删的正好是内置源时「内置」标签和「恢复」按钮都要跟着变
    await refreshLives()
    liveMsg.value = '已删除'
  } catch (e: any) {
    liveMsg.value = e?.message || String(e)
  } finally {
    liveBusy.value = ''
  }
}

/** 进入编辑态（改名 / 换地址） */
function startEditLive(l: LiveSource) {
  liveEditing.value = l.url
  liveEditName.value = l.name
  liveEditUrl.value = l.url
  liveTestMsg.value = { ...liveTestMsg.value, [l.url]: '' }
}

function cancelEditLive() {
  liveEditing.value = ''
  liveEditName.value = ''
  liveEditUrl.value = ''
}

/**
 * 保存编辑。
 *
 * <p>后端只有「POST 加/改名」和「DELETE 删」两个动作，所以：
 * <ul>
 *   <li>只改名字 → POST 同一个 url（后端发现重复会只更新 name）</li>
 *   <li>改了地址 → POST 新地址 + DELETE 旧地址</li>
 * </ul>
 * 先加后删：中间失败也不会先丢掉旧源。
 */
async function saveEditLive(oldUrl: string) {
  const name = liveEditName.value.trim()
  const url = liveEditUrl.value.trim()
  if (!/^https?:\/\//i.test(url)) {
    liveMsg.value = '地址要以 http(s) 开头'
    return
  }
  liveBusy.value = oldUrl
  liveMsg.value = ''
  try {
    // 改名不需要校验（没换地址）；换地址时先真拉一遍，解析不出频道就不换
    const changed = url !== oldUrl
    const r = await addLiveSource({
      name: name || '直播源',
      url,
      verify: changed,
    })
    if (!r.ok) {
      liveMsg.value = r.msg || '这个地址解析不出频道，没保存'
      return
    }
    if (changed) await removeLiveSource(oldUrl)
    cancelEditLive()
    await refreshLives()
    liveMsg.value = changed ? '已换地址' : '已改名'
  } catch (e: any) {
    liveMsg.value = e?.message || String(e)
  } finally {
    liveBusy.value = ''
  }
}

/**
 * 测试一条源：真拉一遍，报「几个台 + 更新于几天前」或具体错误。
 *
 * <p>为什么不在打开页面时自动测：一条 208KB（Guovin）要 6 秒，
 * 两条就是 12 秒，设置页会白等 —— 改成用户主动点。
 */
async function testLive(url: string) {
  liveTestBusy.value = url
  try {
    const r = await fetchLivePlaylist(url)
    if (r.ok) {
      let days: number | null = null
      if (r.updated) {
        const t = new Date(r.updated).getTime()
        if (Number.isFinite(t)) days = Math.floor((Date.now() - t) / 86400_000)
      }
      const stale = days != null && days >= 7
      liveTestMsg.value = {
        ...liveTestMsg.value,
        [url]:
          `可用 · ${r.channels || 0} 个台` +
          (days != null ? ` · ${days <= 0 ? '今天更新' : days + ' 天前更新'}` : '') +
          (stale ? '（已停更，考虑换源）' : ''),
      }
    } else {
      liveTestMsg.value = { ...liveTestMsg.value, [url]: `不可用：${r.msg || '解析失败'}` }
    }
  } catch (e: any) {
    liveTestMsg.value = { ...liveTestMsg.value, [url]: `不可用：${e?.message || '请求失败'}` }
  } finally {
    liveTestBusy.value = ''
  }
}

/** 恢复内置源：清掉逐条禁用标记（删错时不用手输 CDN 地址） */
async function doResetBuiltin() {
  liveBusy.value = '__reset__'
  liveMsg.value = ''
  try {
    const r = await resetBuiltinLives()
    if (!r.ok) {
      liveMsg.value = '恢复失败'
      return
    }
    await refreshLives()
    liveMsg.value = '已恢复内置源'
  } catch (e: any) {
    liveMsg.value = e?.message || String(e)
  } finally {
    liveBusy.value = ''
  }
}

/**
 * iptv-org 国家/地区快捷添加。
 *
 * <p>为什么做这个：iptv-org 按 **ISO 两位国家码** 分文件（`countries/<code>.m3u`），
 * 但用户在设置页无从知道码是什么，而且英国是 `uk` 不是 ISO 的 `gb`
 * （`gb.m3u` 404），手输还容易拼错。这里列实测可用的几条，点一下就加。
 *
 * <p>为什么不列港台：内置的 iptv-org 中文台用的是**语言包**
 * `languages/zho.m3u`，本来就把港澳台（和 CCTV-4 各洲版、CGTN 等海外
 * 中文台）一起收进来了，再加 `hk` / `tw` 只会在合并后多出一批重复线路。
 *
 * <p>`ch` 是 2026-11-10 实测台数，只用于按钮 tooltip；真实台数以添加时
 * 解析出的为准（iptv-org 每天在变）。
 */
const LIVE_COUNTRY_PRESETS = [
  { code: 'jp', label: '日本', ch: 9 },
  { code: 'kr', label: '韩国', ch: 72 },
  { code: 'th', label: '泰国', ch: 72 },
  { code: 'sg', label: '新加坡', ch: 9 },
  { code: 'us', label: '美国', ch: 1504 },
  { code: 'uk', label: '英国', ch: 309 },
  { code: 'de', label: '德国', ch: 305 },
  { code: 'fr', label: '法国', ch: 200 },
] as const

function countryPresetUrl(code: string) {
  return `https://iptv-org.github.io/iptv/countries/${code}.m3u`
}

/** 已加过的国家源（快捷行按钮置灰；网格里只淡化，仍可聚焦——见 addCountryLive 注释） */
function hasCountryPreset(code: string) {
  const url = countryPresetUrl(code)
  return liveSources.value.some((s) => s.url === url)
}

/**
 * 一键添加一个国家源。
 *
 * <p>★ 网格里的按钮**故意不设 disabled**（置灰靠 class），只在处理函数里
 * 挡重复/并发。原因：tv-nav 的 `usable()` 会把 disabled 元素踢出候选集，
 * 而网格是二维的 —— 从「爱尔兰」按 ↓ 时，若下方同列的「澳门」因为已添加
 * 而被 disabled，几何选焦会斜着跳到上一行的「澳大利亚」去
 * （2026-11-11 用户实测报障）。保持可聚焦，↓ 就永远落在同列下一格。
 */
async function addCountryLive(c: { code: string; label: string; ch?: number }) {
  const url = countryPresetUrl(c.code)
  if (liveBusy.value) return
  if (hasCountryPreset(c.code)) {
    liveMsg.value = `iptv-org ${c.label} 已经加过了`
    return
  }
  liveBusy.value = url
  liveMsg.value = ''
  try {
    // 和手输添加一样先真拉一遍：iptv-org 也有空文件/404 的国家
    const r = await addLiveSource({ name: `iptv-org ${c.label}`, url, verify: true })
    if (!r.ok) {
      liveMsg.value = r.msg || `iptv-org ${c.label} 解析不出频道，没保存`
      return
    }
    liveSources.value = r.lives || liveSources.value
    await refreshLives()
    liveMsg.value = `已添加 iptv-org ${c.label}${r.channels ? `，解析出 ${r.channels} 个频道` : ''}`
  } catch (e: any) {
    liveMsg.value = e?.message || String(e)
  } finally {
    liveBusy.value = ''
  }
}

/**
 * 「更多国家/地区」：iptv-org 有 250 个国家/地区文件，8 个快捷按钮不够用。
 *
 * <p>列表按需从 `iptv-org.github.io/api/countries.json`（19KB，CORS 开放）
 * 拉，不在打开设置页时就拉。这个接口的 code 直接用就能拼出
 * `countries/<code>.m3u` —— 注意它用的不是严格 ISO（英国是 `UK` 不是 `GB`，
 * 与 `uk.m3u` 实测一致），所以**别拿 ISO 表去校正**。
 *
 * <p>中文名只覆盖常用的一百多个；剩下的保留英文名 —— 为了一个按钮编造
 * 冷门国家的译名没有意义，用户看得懂 English name 就够。
 */
const COUNTRY_ZH: Record<string, string> = {
  AE: '阿联酋', AF: '阿富汗', AL: '阿尔巴尼亚', AM: '亚美尼亚', AO: '安哥拉',
  AR: '阿根廷', AT: '奥地利', AU: '澳大利亚', AZ: '阿塞拜疆', BA: '波黑',
  BD: '孟加拉国', BE: '比利时', BG: '保加利亚', BH: '巴林', BJ: '贝宁',
  BO: '玻利维亚', BR: '巴西', BY: '白俄罗斯', CA: '加拿大', CH: '瑞士',
  CI: '科特迪瓦', CL: '智利', CM: '喀麦隆', CN: '中国', CO: '哥伦比亚',
  CR: '哥斯达黎加', CU: '古巴', CY: '塞浦路斯', CZ: '捷克', DE: '德国',
  DK: '丹麦', DO: '多米尼加', DZ: '阿尔及利亚', EC: '厄瓜多尔', EE: '爱沙尼亚',
  EG: '埃及', ES: '西班牙', ET: '埃塞俄比亚', FI: '芬兰', FR: '法国',
  GB: '英国', GE: '格鲁吉亚', GH: '加纳', GR: '希腊', GT: '危地马拉',
  HK: '香港', HN: '洪都拉斯', HR: '克罗地亚', HU: '匈牙利', ID: '印度尼西亚',
  IE: '爱尔兰', IL: '以色列', IN: '印度', IQ: '伊拉克', IR: '伊朗',
  IS: '冰岛', IT: '意大利', JM: '牙买加', JO: '约旦', JP: '日本',
  KE: '肯尼亚', KG: '吉尔吉斯斯坦', KH: '柬埔寨', KP: '朝鲜', KR: '韩国',
  KW: '科威特', KZ: '哈萨克斯坦', LA: '老挝', LB: '黎巴嫩', LK: '斯里兰卡',
  LT: '立陶宛', LU: '卢森堡', LV: '拉脱维亚', LY: '利比亚', MA: '摩洛哥',
  MD: '摩尔多瓦', ME: '黑山', MK: '北马其顿', MM: '缅甸', MN: '蒙古',
  MO: '澳门', MT: '马耳他', MU: '毛里求斯', MV: '马尔代夫', MX: '墨西哥',
  MY: '马来西亚', MZ: '莫桑比克', NA: '纳米比亚', NG: '尼日利亚', NI: '尼加拉瓜',
  NL: '荷兰', NO: '挪威', NP: '尼泊尔', NZ: '新西兰', OM: '阿曼',
  PA: '巴拿马', PE: '秘鲁', PH: '菲律宾', PK: '巴基斯坦', PL: '波兰',
  PR: '波多黎各', PS: '巴勒斯坦', PT: '葡萄牙', PY: '巴拉圭', QA: '卡塔尔',
  RO: '罗马尼亚', RS: '塞尔维亚', RU: '俄罗斯', SA: '沙特阿拉伯', SD: '苏丹',
  SE: '瑞典', SG: '新加坡', SI: '斯洛文尼亚', SK: '斯洛伐克', SN: '塞内加尔',
  SO: '索马里', SY: '叙利亚', TH: '泰国', TJ: '塔吉克斯坦', TM: '土库曼斯坦',
  TN: '突尼斯', TR: '土耳其', TT: '特立尼达和多巴哥', TW: '台湾', TZ: '坦桑尼亚',
  UA: '乌克兰', UG: '乌干达', UK: '英国', US: '美国', UY: '乌拉圭',
  UZ: '乌兹别克斯坦', VE: '委内瑞拉', VN: '越南', YE: '也门', ZA: '南非',
  ZM: '赞比亚', ZW: '津巴布韦',
}

/** 全部国家/地区（按需加载；空 = 还没拉过） */
const allCountries = ref<{ code: string; label: string; flag: string }[]>([])
const countriesBusy = ref(false)
const countriesMsg = ref('')
const showAllCountries = ref(false)

async function toggleAllCountries() {
  showAllCountries.value = !showAllCountries.value
  if (!showAllCountries.value) return
  await loadAllCountries()
}

/** 只负责拉列表（失败后「重试」也走它）。拉过一次就复用 */
async function loadAllCountries() {
  if (allCountries.value.length || countriesBusy.value) return
  countriesBusy.value = true
  countriesMsg.value = ''
  // 网络不通时 fetch 可能一直挂着（国内直连 github.io 常见）→ 15 秒后主动放弃
  const ac = new AbortController()
  const timer = setTimeout(() => ac.abort(), 15000)
  try {
    const r = await fetch('https://iptv-org.github.io/api/countries.json', {
      cache: 'no-cache',
      signal: ac.signal,
    })
    if (!r.ok) throw new Error('HTTP ' + r.status)
    const arr = (await r.json()) as { code?: string; name?: string; flag?: string }[]
    const list = arr
      .filter((x) => x && typeof x.code === 'string')
      .map((x) => {
        const up = String(x.code).toUpperCase()
        return {
          code: up.toLowerCase(),
          label: COUNTRY_ZH[up] || x.name || up,
          flag: x.flag || '',
          zh: !!COUNTRY_ZH[up],
        }
      })
    // 有中文名的排前面（也就是用户最可能找的那批），组内按拼音/字母排
    list.sort((a, b) => (a.zh === b.zh ? a.label.localeCompare(b.label, 'zh-Hans-CN') : a.zh ? -1 : 1))
    allCountries.value = list
  } catch (e: any) {
    countriesMsg.value =
      (e?.name === 'AbortError' ? '拉取超时（15 秒）' : '国家列表拉取失败（' + (e?.message || '网络错误') + '）') +
      ' —— 可以直接手输 https://iptv-org.github.io/iptv/countries/<code>.m3u'
  } finally {
    clearTimeout(timer)
    countriesBusy.value = false
  }
}

/**
 * 应用版本号。maintTiles（更上面）会读它，所以必须在这里先声明。
 *
 * 桌面端用 Rust 的 boot_info.app_version（唯一权威来源），
 * Web 端没有 boot_info，退到构建期注入的 __APP_VERSION__。
 * 两者是不同版本线，绝不能拿 __APP_VERSION__ 做插件 min_app_version 校验。
 */
const appVersion = ref<string>(__APP_VERSION__)

// ---------------------------------------------------------------- AI 助手
//
// docs/47 §4.3：配置存客户端 prefs（与 meow.api 同层），不进后端 policy.json。
// 这里是**唯一入口**——没有它用户只能改 localStorage，等于功能不存在。
//
// ★ 未配置时诊断功能照常工作（规则层零 LLM 依赖），只是少了人话解释。
//   所以本分区不做成必填，也不在未填时禁用任何东西。
const llm = ref({ ...llmConfig() })
const llmMsg = ref('')
const llmMsgOk = ref(true)
/** 正在测连通性 */
const llmBusy = ref(false)

/** 拉一个预设（省去手抄地址；model 仍可自改） */
function pickPreset(id: string) {
  const p = LLM_PRESETS.find((x) => x.id === id)
  if (!p) return
  llm.value.baseUrl = p.baseUrl
  llm.value.model = p.model
  if (!llm.value.apiKey) llm.value.apiKey = ''
  persistLlm()
}

function persistLlm() {
  saveLlmConfig({
    enabled: llm.value.enabled,
    baseUrl: llm.value.baseUrl.trim(),
    apiKey: llm.value.apiKey.trim(),
    model: llm.value.model.trim(),
    maxTokens: llm.value.maxTokens,
    historyRounds: Math.max(0, Math.min(10, Number(llm.value.historyRounds) || 0)),
  })
  // ★ 开关一开就把后端的模型转发也打开（见 syncRelaySwitch 的注释）
  void syncRelaySwitch()
}

function setLlmEnabled(v: boolean) {
  llm.value.enabled = v
  persistLlm()
}

/**
 * 把「启用」这个开关同步到后端的 `llmRelay.enabled`。
 *
 * <p><b>为什么需要</b>（2026-10-30 用户反馈"已经启用了，怎么还有开关控制"）：
 * 原来有**两个**开关——设置页这个只是前端偏好（localStorage 的 llmEnabled），
 * 而 Web/TV 调模型必须经后端 `/api/llm/relay`，后者由 policy.json 的
 * llmRelay.enabled 控制且**默认 false**。于是用户把开关打开、填好 Key，
 * 点测试仍然提示"后端没开模型中转"，而界面上没有任何地方能改那个 policy。
 *
 * <p>现在以一个开关为准：前端这个打开 → 顺手把后端也打开；关掉 → 一并关掉。
 * policy 里的字段保留（自建实例仍可在配置文件里强制关闭），
 * 但用户不需要知道它的存在。
 *
 * <p>同步失败不报错：PC 端走壳、根本用不到 relay，那种情况下写不进去也无所谓。
 */
async function syncRelaySwitch() {
  try {
    const cur = await fetchPolicy()
    const relay = (cur as Record<string, unknown>).llmRelay as
      | { enabled?: boolean }
      | undefined
    if (!!relay?.enabled === !!llm.value.enabled) return
    await savePolicy({ ...cur, llmRelay: { ...(relay || {}), enabled: !!llm.value.enabled } })
  } catch {
    /* 后端不可达 / 老版本没有这个字段：保持现状，不打扰用户 */
  }
}

/** 真发一次最小请求，比"保存成功"有用得多 */
async function testLlm() {
  persistLlm()
  llmBusy.value = true
  llmMsg.value = ''
  try {
    const r = await chat([
      { role: 'user', content: '只回复两个字：可用' },
    ])
    if (r.ok) {
      llmMsgOk.value = true
      llmMsg.value = `连通（模型回复：${r.text.slice(0, 20)}）`
    } else {
      llmMsgOk.value = false
      llmMsg.value = r.error
    }
  } finally {
    llmBusy.value = false
  }
}


// ---------------------------------------------------------------- 本机插件（桌面端）
//
// 插件全部外置下载（v0.7.0 起），安装包里不带 plugins/。
// 这里负责：列出已装/可装、下载、更新、离线导入、启停。
/** TV 壳内（有原生桥）才有「本机执行」分区 */
// 身份统一：任何 TV 壳（老壳只有 platform 也算），不用 platform() 的返回值。
const tvMode = isTvShell()
// 运行时模块/弹幕载荷是能力（moduleCheck）：老壳没有时不显示对应分区。
const tvModule = hasTvModule()
const desktop = isDesktop()
// iOS 壳：ApiServer 默认跑在 App 进程内（JVM）；新壳（2026-11-27 起）也支持
// 在设置里改连外部后端（壳落 UserDefaults，reload 后由桥注入新 apiBase）。
const ios = isIosShell()
const iosInfo = iosShellInfo()
/** 新壳才有 setApiBackend：老壳保持"只读展示端内后端" */
const iosBackendConfigurable = computed(
  () => ios && typeof (iosInfo as { setApiBackend?: unknown }).setApiBackend === 'function',
)
/** 当前是否已指向外部后端（生效值不等于端内 JVM 的固定地址） */
const iosOverrideActive = computed(() => {
  const b = String(iosInfo.apiBackend || iosInfo.apiBase || '')
  return !!b && !/^https?:\/\/127\.0\.0\.1:9978\/?$/i.test(b)
})

/*
 * 「运行时」分区归位（2026-11-26 用户口径：运行时状态不该藏在「搜索与源」里）。
 *
 * 各端运行时形态不同，展示位置也按"用户会去哪找"放：
 *   - TV：模块是可独立安装的 APK → 独立分区（安装/启停/状态在一起）
 *   - fpk：java/node/python 是系统依赖 → 独立分区
 *   - iOS：端内 drpy/CPython 是壳能力 → 独立分区
 *   - PC：drpy/pyrt 就是插件 → 放在「本机插件」里，不另开分区（否则同一份
 *     插件状态在两个地方各显示一次）
 */
const fpkShell = ref(currentShell() === 'fpk')
/*
 * fpk 的身份是**异步探测**出来的（App 启动时 probeCapability 完成前
 * data-shell 还是 web）。设置页是运行时状态的入口，这里补一次探测；
 * shellInfo 自带缓存，Docker/Web 上就是复用启动时那次结果。
 */
onMounted(() => {
  if (fpkShell.value || currentShell() !== 'web') return
  void detectFpkShell()
    .then((r) => {
      if (r) fpkShell.value = true
    })
    .catch(() => {
      /* 不是 fpk 就当普通 Web，运行时分区不出现 */
    })
})
const showRuntimeNav = computed(() => (tvMode && tvModule) || ios || fpkShell.value)
const runtimeHint = computed(() => {
  if (ios) return '端内 drpy + CPython · .js/.py 源本机执行'
  if (tvMode) return 'Python 模块 · .js 由 QuickJS、.py 由模块执行'
  return 'meowd 探测到的本机 java / node / python'
})

// ---------------------------------------------------------------- 本机执行（TV）
//
// 电视上可以直接跑配置里的 jar（DexClassLoader），不必依赖后端。
// 这里只做三件事：启动端内网关、导入带 jar 的配置、看当前状态。
// 具体执行逻辑全在原生层（LocalGateway/LocalSpiderHost）。
const localGw = ref('')
const localSites = ref(0)
const localMsg = ref('')
const localBusy = ref(false)

function bridge() {
  try {
    return (window as unknown as { meowTv?: any }).meowTv || null
  } catch {
    return null
  }
}

function refreshLocalExec() {
  const b = bridge()
  if (!b) return
  try {
    localSites.value = b.localSiteCount()
    if (b.localGatewayRunning()) localGw.value = 'http://127.0.0.1:9978'
  } catch {
    /* 桥坏了就当没有 */
  }
}
if (tvMode) refreshLocalExec()

// ---------------------------------------------------------------- 运行时模块（TV）
//
// 模块 = 一个独立安装的 APK（com.meow.runtime.node），里面是 libnode + JNI 桥，
// 由宿主用 DexClassLoader 加载后跑 danmu_api。它跑在 :node 独立进程（node 崩了
// 不能带走主进程），状态通过 files/runtime-module.json 跨进程传递。
//
// 为什么分三段（检查 / 下载 / 安装）而不是一个按钮：包约 60MB，必须给进度；
// 安装还要交给系统安装器，下载完用户可能想先干别的。
const modState = ref<Record<string, any>>({})
const modMsg = ref('')
const modBusy = ref(false)

// ---------------------------------------------------------------- Python 模块（TV）
//
// 与 node 模块完全独立：另一个 APK（com.meow.runtime.python）、另一条版本线，
// 只有真需要 .py 源的人才装（约 20MB）。逻辑刻意比 node 版精简：不做候选线路
// 逐个试（Python 模块在国内也走同一份 GitHub 加速，失败会明确报错）。
const pyModState = ref<Record<string, any>>({})
const pyModMsg = ref('')
const pyModBusy = ref(false)

function refreshPyModule() {
  const b = bridge()
  if (!b || typeof (b as any).pyModuleState !== 'function') return
  try {
    pyModState.value = JSON.parse((b as any).pyModuleState())
  } catch {
    /* 桥坏了就当没有 */
  }
}
if (tvMode && tvModule) refreshPyModule()

/**
 * 检查 → 下载 → 交给系统安装器。
 *
 * 下载沿用 AppUpdater 的状态机（idle|downloading|verifying|ready|failed|cancelled），
 * 和 node 模块同一套轮询节奏：700ms 一次、最多约 3 分钟。
 */
async function pyModInstall() {
  const b = bridge() as any
  if (!b || pyModBusy.value || typeof b.pyModuleCheck !== 'function') return
  pyModBusy.value = true
  pyModMsg.value = '检查清单…'
  try {
    const src = (await getUpdateSource()) || ''
    const tok = getUpdateToken()
    let chk: Record<string, any> = {}
    try {
      chk = JSON.parse(String(b.pyModuleCheck(src, tok) || '{}'))
    } catch {
      chk = { ok: false, error: '检查失败' }
    }
    if (chk.ok === false) {
      pyModMsg.value = '检查失败：' + (chk.error || '未知错误')
      return
    }
    const curVer = String(pyModState.value.version || '')
    const latestVer = String(chk.version || '')
    if (curVer && latestVer && !isNewer(latestVer, curVer)) {
      pyModMsg.value = `已是最新（v${curVer}）`
      return
    }
    if (!chk.url) {
      pyModMsg.value = '检查失败：清单缺下载地址'
      return
    }
    if (typeof b.pyModuleDownloadCandidates !== 'function') {
      pyModMsg.value = '当前版本不支持安装 Python 模块'
      return
    }
    pyModMsg.value = `下载中（${fmtSize(Number(chk.size) || 0)}，${chk.abi}）…`
    const assetCands = downloadCandidates(String(chk.url)).join('|')
    b.pyModuleDownloadCandidates(src, tok, assetCands)
    for (let i = 0; i < 260; i++) {
      await new Promise((r) => setTimeout(r, 700))
      const st = JSON.parse(b.appUpdateState())
      if (st.state === 'ready') {
        pyModMsg.value = '下载完成，请在系统弹窗确认安装'
        return
      }
      if (st.state === 'failed' || st.state === 'cancelled') {
        pyModMsg.value = '下载失败：' + (st.error || st.state)
        return
      }
    }
    pyModMsg.value = '下载超时'
  } catch (e) {
    pyModMsg.value = '安装失败：' + String(e)
  } finally {
    pyModBusy.value = false
    setTimeout(refreshPyModule, 3000)
  }
}

function refreshModule() {
  const b = bridge()
  if (!b) return
  try {
    modState.value = JSON.parse(b.moduleState())
  } catch {
    /* 桥坏了就当没有 */
  }
}
if (tvMode && tvModule) refreshModule()

function modToggle() {
  const b = bridge()
  if (!b) return
  try {
    modState.value = JSON.parse(b.moduleSetEnabled(!modState.value.enabled))
    modMsg.value = modState.value.enabled ? '已启用' : '已关闭'
  } catch (e) {
    modMsg.value = '操作失败：' + String(e)
  }
}

function modRestart() {
  const b = bridge()
  if (!b) return
  try {
    modState.value = JSON.parse(b.moduleRestart())
    modMsg.value = '已重启（端口 ' + modState.value.port + '）'
  } catch (e) {
    modMsg.value = '重启失败：' + String(e)
  }
}

function modUninstall() {
  const b = bridge()
  if (!b) return
  try {
    b.moduleUninstall()
    modMsg.value = '已打开系统卸载确认，用遥控器确认'
  } catch (e) {
    modMsg.value = '卸载失败：' + String(e)
  }
}

/**
 * 检查 → 下载 → 交给系统安装器。
 *
 * 下载是原生的异步任务（AppUpdater），只能轮询 stateJson；这里 700ms 一次、
 * 最多约 3 分钟（60MB 在电视的弱网下确实可能很久）。
 */
async function modInstall() {
  const b = bridge()
  if (!b || modBusy.value) return
  modBusy.value = true
  modMsg.value = '检查清单…'
  try {
    // 与「在线更新」共用同一份源/令牌（都在 Prefs 里，手机推送也会带过来）
    const src = (await getUpdateSource()) || ''
    const tok = getUpdateToken()
    // ★ 清单也走全局加速（2026-11-12）：模块清单和 APK 都在 GitHub 上，
    //   被墙用户直连必失败。候选列表由 downloadCandidates 按「网络与加速 →
    //   GitHub 加速」算好（直连 + 代理），逐个试，第一个拿到清单的胜出。
    //   与 App 自更新（fetchRawTextCandidates）同一套语义。
    // ★ 新壳优先走**非阻塞**通道（netJobStart/netJobPoll）：同步桥会把网络跑在
    //   WebView 的 JS 线程上，一个"连上不回数据"的代理就能冻住整页（实测踩过）。
    const checkOne = (u: string) =>
      JSON.parse(
        String(
          (b.moduleCheckCandidates ? b.moduleCheckCandidates(u, tok, '') : b.moduleCheck(u, tok)) ||
            '{}',
        ),
      )
    const checkPipe = async (pipe: string): Promise<Record<string, unknown>> => {
      if (b.netJobStart && b.netJobPoll) {
        try {
          const text = await fetchTextViaNativeJob('manifest-any', pipe, tok)
          // ★ netJob 返回的是 packages.json **原文**，不是 pick 结果（1.2.5 真机踩过）：
          //   当初直接 `return JSON.parse(text)` → chk.url/version 全是 undefined →
          //   把清单地址当模块 APK 下载，15s 后报「已尝试 1 个地址」的 SocketTimeout。
          //   「文本 → pick」必须回原生做（ABI/platform 匹配只有一套实现）。
          if (typeof b.moduleCheckFromText === 'function') {
            return JSON.parse(String(b.moduleCheckFromText(text) || '{}')) as Record<
              string,
              unknown
            >
          }
          // 老壳没有 moduleCheckFromText：退回同步逐条（慢，但不会拿错数据）
          let legacy: Record<string, unknown> = { ok: false }
          for (const c of pipe.split('|')) {
            legacy = checkOne(c)
            if (legacy.ok !== false) break
          }
          return legacy
        } catch (e) {
          return { ok: false, error: e instanceof Error ? e.message : String(e) }
        }
      }
      let out: Record<string, unknown> = { ok: false }
      for (const c of pipe.split('|')) {
        out = checkOne(c)
        if (out.ok !== false) break
      }
      return out
    }
    let chk: Record<string, unknown> = await checkPipe(downloadCandidates(src).join('|'))
    const backup = getBackupUpdateSource()
    if (chk.ok === false && backup && backup !== src) {
      chk = await checkPipe(backup)
    }
    if (chk.ok === false) {
      modMsg.value = '检查失败：' + (chk.error || '未知错误')
      return
    }
    // 已是最新就别再下 50MB —— 按钮是「检查更新」，不是「重装」
    // （2026-11-13 真机实测：点一次就重新下载整个模块，白等也白耗流量）
    const curVer = String(modState.value.version || '')
    const latestVer = String(chk.version || '')
    if (curVer && latestVer && !isNewer(latestVer, curVer)) {
      modMsg.value = `已是最新（v${curVer}）`
      return
    }
    if (!chk.url) {
      modMsg.value = '检查失败：清单缺下载地址'
      return
    }
    modMsg.value = `下载中（${fmtSize(Number(chk.size) || 0)}，${chk.abi}）…`
    // APK 同理：把清单里 url 的候选线路一起传下去（原生逐个试、sha256 不变）
    const assetCands = chk.url ? downloadCandidates(String(chk.url)).join('|') : ''
    if (b.moduleDownloadCandidates) b.moduleDownloadCandidates(src, tok, assetCands)
    else b.moduleDownload(src, tok)
    let settled = false
    for (let i = 0; i < 260; i++) {
      await new Promise((r) => setTimeout(r, 700))
      const st = JSON.parse(b.appUpdateState())
      // ★ 原生状态机是 idle|downloading|verifying|ready|failed|cancelled
      //   （AppUpdater.java:52），**没有 'done'**。这里曾经写成 'done'，
      //   下载到 100% 后轮询永远不命中，3 分钟超时后安装器根本不拉起
      //   （2026-11-12 极米投影仪全新安装实测卡死）。与 stores/appUpdate.ts
      //   的 stage === 'ready' 保持同一套词表。
      if (st.state === 'ready') {
        modMsg.value = '下载完成，拉起安装器…'
        b.appUpdateInstall()
        settled = true
        break
      }
      if (st.state === 'failed') {
        modMsg.value = '下载失败：' + st.message
        settled = true
        break
      }
      modMsg.value =
        st.state === 'verifying'
          ? '校验中…'
          : `下载中 ${st.percent}%（${fmtSize(st.received || 0)} / ${fmtSize(st.total || 0)}）`
    }
    // 轮询超时也必须给结论，否则界面停在「下载中 100%」看起来像卡死
    if (!settled) modMsg.value = '安装超时：下载未完成（网络太慢或被拦截），可再点一次重试'
  } catch (e) {
    modMsg.value = '安装失败：' + String(e)
  } finally {
    modBusy.value = false
    refreshModule()
  }
}

// ---------------------------------------------------------------- 弹幕载荷（TV）
//
// 载荷 = danmu_api 的 JS 代码（~10MB），与 PC 的 danmu-api 插件是同一份 zip
// （清单里 id=danmu-payload、platform=["android"]）。和运行时模块分开装的理由：
// 模块是通用 node 运行时（~50MB，按 ABI），换弹幕逻辑不该重装它；壳的 APK
// 下一版也会把这 10MB 从 assets 里删掉（NodeService 保留 assets 兜底一版）。
const payBusy = ref(false)
const payMsg = ref('')

function refreshPayload() {
  const b = bridge()
  if (!b || typeof b.payloadState !== 'function') return
  try {
    refreshPayloadState()
    // 模块没装时载荷一定跑不起来，状态就别读了（避免显示一个装不上的版本）
  } catch {
    /* 桥坏了当没有 */
  }
}
if (tvMode && tvModule) refreshPayload()

/**
 * 下载并安装载荷：检查 → 下载 zip（原生 AppUpdater，sha256 强制）→ 解压落位 → 重启 :node。
 *
 * 与模块安装同一套状态机（idle|downloading|verifying|ready|failed|cancelled），
 * 到 ready 后调 payloadApply 而不是系统安装器 —— 载荷不需要装 APK。
 */
async function payloadInstall() {
  const b = bridge()
  if (!b || payBusy.value) return
  payBusy.value = true
  payMsg.value = '检查清单…'
  try {
    const src = (await getUpdateSource()) || ''
    const tok = getUpdateToken()
    const checkOne = (u: string) =>
      JSON.parse(String(b.payloadCheckCandidates(u, tok, '') || '{}')) as Record<string, unknown>
    let chk: Record<string, unknown> = { ok: false }
    for (const c of downloadCandidates(src)) {
      chk = checkOne(c)
      if (chk.ok !== false) break
    }
    const backup = getBackupUpdateSource()
    if (chk.ok === false && backup && backup !== src) chk = checkOne(backup)
    if (chk.ok === false) {
      payMsg.value = '检查失败：' + String(chk.error || '未知错误')
      return
    }
    // 与模块同款：已是最新就不再重复下 10MB（按钮是「检查更新」）
    const payCur = String(payloadCurrent.value || '')
    const payLatest = String(chk.version || '')
    if (payCur && payLatest && !isNewer(payLatest, payCur)) {
      payMsg.value = `已是最新（v${payCur}）`
      return
    }
    if (!chk.url) {
      payMsg.value = '检查失败：清单缺下载地址'
      return
    }
    payMsg.value = `下载中（${fmtSize(Number(chk.size) || 0)}）…`
    const assetCands = chk.url ? downloadCandidates(String(chk.url)).join('|') : ''
    // ★ AppUpdater.start 成功时返回的是**人话**（"已开始下载"），不是 JSON；
    //   失败时返回的也是人话（"已有下载在进行…"）。直接 JSON.parse 会抛，
    //   整个下载流程就此中断（2026-11-13 .77 端到端实测踩到：日志里 ready 了
    //   但 payloadApply 从没被调用，页面停在"安装失败：Unexpected token"）。
    const rawStart = String(b.payloadDownloadCandidates(src, tok, assetCands) || '')
    let started: Record<string, unknown> = { ok: true }
    try {
      const o = JSON.parse(rawStart) as Record<string, unknown>
      if (o && typeof o === 'object') started = o
    } catch {
      if (!/已开始下载/.test(rawStart)) started = { ok: false, error: rawStart || '下载未启动' }
    }
    if (started.ok === false) {
      payMsg.value = '下载失败：' + String(started.error || '未知错误')
      return
    }
    let settled = false
    for (let i = 0; i < 200; i++) {
      await new Promise((r) => setTimeout(r, 700))
      const st = JSON.parse(b.appUpdateState())
      if (st.state === 'ready') {
        payMsg.value = '校验通过，安装中…'
        const applied = JSON.parse(String(b.payloadApply() || '{}')) as Record<string, unknown>
        if (applied.ok === false) throw new Error(String(applied.error || '安装失败'))
        payMsg.value = '已装好 v' + String(applied.version || '')
        settled = true
        break
      }
      if (st.state === 'failed') {
        console.error('[payload] download failed', JSON.stringify(st))
        payMsg.value = '下载失败：' + st.message
        settled = true
        break
      }
      payMsg.value =
        st.state === 'verifying'
          ? '校验中…'
          : `下载中 ${st.percent}%（${fmtSize(st.received || 0)} / ${fmtSize(st.total || 0)}）`
    }
    if (!settled) payMsg.value = '下载超时（网络太慢或被拦截），可再点一次重试'
  } catch (e) {
    console.error('[payload] error', e instanceof Error ? e.stack || e.message : String(e))
    payMsg.value = '安装失败：' + String(e)
  } finally {
    payBusy.value = false
    refreshPayload()
    void checkPayload(true)
  }
}

// ---------------------------------------------------------------- 远程调试（TV / PC）
//
// 为什么要有这个入口：原来开关只在**配置页**，而配置页只在"还没配后端"时
// 出现——一旦进了主界面就再也回不去，想开个 MCP 让 PC 按键就变成必须重装
// 或清数据的死结。开关本身仍由原生层落实（TvBridge → DebugServer）。
//
// ★ 两端能力不一样，所以 UI 也不同（不是同一套逻辑硬套）：
//   TV  —— DebugServer 可以随时启停，令牌每次重开都换。所以有开关、有令牌。
//   PC  —— MCP 随应用启动、与主进程同生命周期。**不能给开关**：
//          关掉它等于把自己被控制的能力切断，而打开它的唯一办法是重启应用；
//          做成开关只会让用户以为"关掉还能再打开"。所以 PC 端只读展示状态。
//   Web —— 用不上：浏览器沙箱里起不了 TCP 监听，也没有可被控制的应用窗口。
const debugPlatform = computed<'tv' | 'pc' | 'ios' | null>(() => {
  if (tvMode) return 'tv'
  if (desktop) return 'pc'
  /*
   * iOS：端内网关只绑回环、也起不了 TV 那种 MCP 进程，给不了「开关」；
   * 但排障必须有一个入口 —— 只读展示运行信息 + 复制诊断 + 重载界面
   * （用户 2026-11-24 要求对齐 TV 的「维护 → 调试」）。
   */
  if (ios) return 'ios'
  return null
})

const dbg = reactive({
  enabled: false,
  running: false,
  token: '',
  url: '',
  hint: '',
  /** PC 端的探测区间，客户端按它找端口 */
  portRange: '' as string,
  /** 空闲自动关闭：总时长与剩余分钟（TV 才有） */
  idleMin: 0,
  expiresMin: 0,
  busy: false,
})

function readDebugState(s: {
  enabled?: boolean
  running?: boolean
  token?: string
  url?: string
  hint?: string
  idleTimeoutMs?: number
  expiresInMs?: number
}) {
  dbg.enabled = !!s.enabled
  dbg.running = !!s.running
  dbg.token = s.token || ''
  dbg.url = s.url || ''
  dbg.hint = s.hint || ''
  dbg.idleMin = s.idleTimeoutMs ? Math.round(s.idleTimeoutMs / 60000) : 0
  dbg.expiresMin = s.expiresInMs ? Math.ceil(s.expiresInMs / 60000) : 0
}

function refreshTvDebug() {
  const b = bridge()
  if (!b || typeof b.debugState !== 'function') return
  try {
    readDebugState(JSON.parse(b.debugState()))
  } catch {
    /* 桥坏了就当没有 */
  }
}

async function refreshPcDebug() {
  if (!desktop) return
  try {
    const s = await tauriInvoke<{ port: number; url: string; port_range: [number, number] }>(
      'mcp_status',
    )
    dbg.running = !!s.port
    dbg.enabled = true // PC 端 MCP 恒随应用启动，没有"开/关"概念
    dbg.url = s.url || ''
    dbg.portRange = s.port_range ? `${s.port_range[0]}–${s.port_range[1]}` : ''
    dbg.token = ''
    dbg.hint = s.port
      ? ''
      : 'MCP 未监听（端口区间被占满）。重启应用可恢复。'
  } catch {
    /* 老版本应用没有这个命令，静默降级成"未运行" */
    dbg.running = false
    dbg.hint = '当前应用版本不支持读取 MCP 状态，升级应用后可见。'
  }
}

/**
 * iOS 局域网调试状态。
 *
 * <p>默认关闭（网关只绑 127.0.0.1）；打开后宿主改绑 0.0.0.0 并生成 16 位令牌，
 * 所有非本机请求（含 GET）都要带 X-Meow-Token。令牌只在回环请求里下发，
 * 所以只有本机设置页看得到。
 */
async function refreshIosLan() {
  try {
    const s = await fetchLanDebug()
    dbg.enabled = !!s.enabled
    dbg.running = !!s.enabled
    dbg.url = s.url || ''
    dbg.token = s.token || ''
    dbg.hint = ''
  } catch (e) {
    dbg.enabled = false
    dbg.running = false
    dbg.url = ''
    dbg.token = ''
    dbg.hint = '读取远程调试状态失败：' + String((e as { message?: unknown })?.message ?? e)
  }
}

async function toggleIosDebug() {
  if (dbg.busy) return
  dbg.busy = true
  try {
    const s = await setLanDebug(!dbg.enabled)
    dbg.enabled = !!s.enabled
    dbg.running = !!s.enabled
    dbg.url = s.url || ''
    dbg.token = s.token || ''
    dbg.hint = ''
    // 调试期间保持亮屏（对齐 TV 的隐式行为；TV 没有这个开关，iOS 也不再单独给）。
    // 关闭调试就恢复自动锁屏，避免一直亮着耗电。
    if (keepAwake.value !== !!s.enabled) setKeepAwake(!!s.enabled)
  } catch (e) {
    dbg.hint = '切换失败：' + String((e as { message?: unknown })?.message ?? e)
  } finally {
    dbg.busy = false
  }
}

function refreshDebug() {
  if (debugPlatform.value === 'tv') refreshTvDebug()
  else if (debugPlatform.value === 'pc') void refreshPcDebug()
  else if (debugPlatform.value === 'ios') void refreshIosLan()
}
refreshDebug()

// ---------------------------------------------------------------- iOS 调试（排障用）
//
// iOS 给不了 TV 那种「开关远程调试」：端内网关虽然监听 0.0.0.0（同一 Wi-Fi 可直连 9978），
// 但 iOS 一切后台就挂起 App，监听套接字随之消失，没有可常驻的调试进程；
// 也没有 TV DebugServer 那套 MCP（19777）。所以这里给**排障三件套**：
// 运行信息（构建号/端口/运行时）、复制诊断（带最近日志，直接粘给开发）、重载界面。
// 「图片全碎 / 按钮没反应」这类问题多半是内核网络进程被系统回收，重载即恢复。

const diagMsg = ref('')
const iosDiag = computed(() => {
  const info = iosShellInfo()
  return {
    build: String(info.build || ''),
    version: String(info.version || ''),
    apiBase: String(info.apiBase || ''),
    runtime: String(info.runtime || ''),
  }
})

/**
 * 保持亮屏（2026-11-24 用户要求）。
 *
 * iOS 息屏 = App 被系统挂起 = 端内网关（9978）的监听套接字全部消失，
 * 远程调试立刻断。iOS 没有「后台常驻监听」的合法通道，所以唯一可靠的
 * 办法是别让屏幕自动锁；状态由壳持久化（UserDefaults），这里只读写。
 */
const keepAwake = ref(iosShellInfo().keepAwake !== false)
function setKeepAwake(v: boolean) {
  keepAwake.value = v
  try {
    // 桥不存在（模拟器/旧包）时静默：不影响其它功能
    ;(window as any).meowIos?.setKeepAwake?.(v)
  } catch {
    /* ignore */
  }
}

async function copyIosDiagnostics() {
  const lines: string[] = []
  lines.push('## 猫窝 iOS 诊断')
  lines.push(`时间: ${new Date().toISOString()}`)
  lines.push(`构建: ${iosDiag.value.build || '?'}  版本: ${iosDiag.value.version || '?'}`)
  lines.push(`网关: ${iosDiag.value.apiBase || '(空)'}  运行时: ${iosDiag.value.runtime || '?'}`)
  lines.push(`页面: ${location.href}  壳: ${document.documentElement.dataset.shell || '?'}`)
  lines.push(`UA: ${uaText()}`)
  try {
    const r = await fetchServerLogs({ limit: 120 })
    if (r.state === 'ok' && r.data) {
      lines.push('')
      lines.push(`## 服务端日志（最近 ${r.data.items.length} 条）`)
      for (const it of r.data.items) {
        lines.push(`${new Date(it.at).toISOString()} [${it.level}] ${it.tag}: ${it.text}`)
      }
    } else {
      lines.push(`## 服务端日志不可用：${r.state} ${r.detail || ''}`)
    }
  } catch (e: any) {
    lines.push(`## 服务端日志异常：${e?.message || e}`)
  }
  const text = lines.join('\n')
  try {
    await navigator.clipboard.writeText(text)
    diagMsg.value = `已复制 ${text.length} 字符，可直接粘贴发给开发`
  } catch {
    // WKWebView 里 clipboard 可能被拒：退化成 textarea + execCommand
    try {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
      diagMsg.value = `已复制 ${text.length} 字符（兼容模式）`
    } catch {
      diagMsg.value = '复制失败：请到「运行日志」页手动查看'
    }
  }
}

function reloadWebView() {
  location.reload()
}
/** 远程调试剩余时间每分钟刷新一次（只有 TV 有意义） */
let dbgTimer: number | undefined

function toggleTvDebug() {
  const b = bridge()
  if (!b || typeof b.setDebugEnabled !== 'function') {
    dbg.hint = '当前不在 TV 壳内，无法切换远程调试'
    return
  }
  try {
    readDebugState(JSON.parse(b.setDebugEnabled(!dbg.enabled)))
  } catch (e) {
    dbg.hint = '切换失败：' + String(e)
  }
}

/** 令牌按 4 位分组：投影仪拍屏幕时 16 位连写极易看错一位（1/l、0/O）。 */
function groupToken(t: string) {
  const s = (t || '').replace(/-/g, '')
  if (s.length !== 16) return t || ''
  return s.slice(0, 4) + '-' + s.slice(4, 8) + '-' + s.slice(8, 12) + '-' + s.slice(12)
}

/**
 * 启用/重启本机执行。
 *
 * 这个按钮要一次做完四件事，缺一件用户就看不到东西：
 *   1. 落内置 dex jar（APK 里带了 886 类的肥猫 jar，不落盘 DexClassLoader 加载不了）
 *   2. 起网关，并把当前后端地址同步过去（弹幕要走它，本机聚合站已全失效）
 *   3. 站点表为空时用内置 jar 装几个兜底站——否则"启用成功"却 0 个站，等于没用
 *   4. 把「后端地址」切到网关，否则网关卡在那儿没有任何请求会发过去
 *
 * ★ 第 3 步只在空表时做：已有配置导入的站点是用户的选择，不能被兜底站挤掉。
 */
function startLocalExec() {
  const b = bridge()
  if (!b) {
    localMsg.value = '当前不在 TV 壳内，无法启用本机执行'
    return
  }
  localBusy.value = true
  try {
    // 1) 内置 jar 落地（幂等，已落地会跳过）
    let jarMsg = ''
    if (typeof b.installBundledJar === 'function') {
      const p = b.installBundledJar()
      jarMsg = p ? '' : '（内置 jar 落地失败）'
    }

    // 2) 起网关 + 同步后端地址（弹幕依赖它）
    const base = apiBase()
    const url = typeof b.startLocalGatewayWith === 'function'
      ? b.startLocalGatewayWith(base)
      : b.startLocalGateway()
    if (!url) {
      localMsg.value = '启动失败：端口 9978 被占用'
      return
    }
    localGw.value = url

    // 3) 空表兜底
    let extra = ''
    if (localSites.value === 0 && typeof b.importBundledSites === 'function') {
      try {
        const o = JSON.parse(b.importBundledSites())
        if (o.ok) extra = '，已装 ' + o.imported + ' 个内置站点'
      } catch {
        /* 兜底失败不影响网关 */
      }
    }

    // 4) 切后端地址到网关
    switchToGateway()

    localMsg.value = '已启用' + jarMsg + extra + '。后端地址已切到 ' + url
    refreshLocalExec()
  } finally {
    localBusy.value = false
  }
}

/** 停用本机网关：只停网关，不改后端地址（避免自动切错，交给用户改） */
function stopLocalExec() {
  const b = bridge()
  if (!b) return
  try {
    if (typeof b.stopLocalGateway === 'function') b.stopLocalGateway()
  } catch {
    /* 桥坏了也继续把界面状态复位 */
  }
  localGw.value = ''
  localMsg.value = '已停用本机网关。后端地址未改动——若仍指向 127.0.0.1:9978，请到「后端地址」改回 NAS。'
  refreshLocalExec()
}

/** 把「后端地址」切到本机网关（用户在设置页看到的那一行要跟着变） */
function switchToGateway() {
  const gw = 'http://127.0.0.1:9978'
  setRuntimeBase(gw)
  apiUrl.value = gw
  void store.boot()
  /*
   * ★ 必须整页刷新（2026-11-16）。
   *
   * 页面上的后端级设置（安全DNS/弹幕API/缓存配额/噪音词/jsRuntime）都是
   * onMounted 里从**上一个后端**读的（loadPolicy）。不刷新的话，切到网关后
   * 显示的还是旧后端的值，而之后任何修改都 POST 到 9978（新后端）——
   * "看着是 A、改的是 B"，用户会以为设置丢了/改了没用。
   * 手填地址路径本来就 reload（见 saveAndReconnect），这里对齐，而不是逐个重拉
   * 十几个分区（漏一个就又是错位）。
   */
  location.reload()
}

/**
 * 导入配置到本机。
 *
 * 导入完必须顺带把网关重启一遍并把地址切过去——否则站点表变了但网关还跑着
 * 旧的 loader 缓存，用户看到"导入成功 37 站"却在首页什么都刷不出来。
 */
async function importLocalConfig() {
  const b = bridge()
  if (!b) return
  const url = cfgUrl.value.trim()
  if (!url) {
    localMsg.value = '请先填配置地址'
    return
  }
  localBusy.value = true
  try {
    const r = await b.importLocalConfig(url)
    const o = JSON.parse(r)
    if (o.ok && o.imported > 0) {
      // 导入成功才切：导入 0 站时切过去只会得到一片空白
      const gw = typeof b.startLocalGatewayWith === 'function'
        ? b.startLocalGatewayWith(apiBase())
        : b.startLocalGateway()
      if (gw) {
        localGw.value = gw
        switchToGateway()
        localMsg.value = `${o.message || '已导入'}。后端地址已切到 ${gw}`
      } else {
        localMsg.value = `${o.message || '已导入'}，但网关启动失败（端口 9978 被占）`
      }
    } else {
      localMsg.value = o.message || '导入失败'
    }
    refreshLocalExec()
  } catch (e: any) {
    localMsg.value = '导入失败：' + (e?.message || e)
  } finally {
    localBusy.value = false
  }
}
const plugins = ref<PluginInfo[]>([])
const busy = ref('')

/**
 * 本机后端插件（manifest 里 required=true 的那个）。
 *
 * 没有它就无法区分「没装插件」「装了但用户停了」「正在启动」三种状态，
 * 顶栏只能一律显示"等待插件启动"——用户关掉后端后仍以为在等它启动。
 */
const backendPlugin = computed(() => plugins.value.find((p) => p.required))

/** 顶栏「未连接」时到底在等什么：插件没装 / 被停 / 在启动 / 手填地址无响应 */
const offlineHint = computed(() => {
  if (!desktop) return '请填写后端地址'
  if (manualApi.value) return `手填地址无响应 · ${manualApi.value}`
  const p = backendPlugin.value
  if (!p || !p.installed) return '未安装「影视后端」插件'
  if (p.phase === 'stopped') return '本机后端已停止'
  if (p.phase === 'failed') return '本机后端启动失败'
  return '等待插件启动'
})

// 远端索引与已安装记录
const remoteIndex = ref<PluginIndex | null>(null)
const installedMap = ref<Record<string, InstalledInfo>>({})
const indexError = ref('')
const indexLoading = ref(false)

// ---------------------------------------------------------------- 应用在线更新
//
// 复用插件的清单（packages.json 的 packages.meow-pc / meow-tv 条目）。设计说明见
// `api/appUpdate.ts` 文件头：不另造一份更新协议，用户已有的插件源直接可用。
//
// ★ 清单读取与下载都在**原生**做，页面不经手字节：
//   GitHub Release 资产没有 CORS 头 → 页面 XHR 必被拦；
//   私有仓要带 Authorization → raw 的 CORS 预检不放行该头（实测 403）。
//
// 只在能装包的平台显示（PC / TV）。Web 端没这个能力，显示一个永远灰着的
// 按钮只会让用户困惑。

/**
 * 更新日志按行拆（`desc` 是多行文本）。
 *
 * <p>和 UpdateDialog 里的 noteLines 同一套规则：先 trim 再按行过滤空串，
 * 否则末尾的换行会渲染出一个空列表项。
 */
const updNoteLines = computed<string[]>(() => {
  return formatNoteLines(updFound.value?.notes || '')
})

/** 更新源输入框（留空 = 用内置默认源） */
const updSourceInput = ref<string>(getUpdateSourceSync())
/** 显式备用源（空 = 不启用；不会自动回落官方源） */
const updBackupInput = ref<string>(getBackupUpdateSource())
/**
 * 占位提示。
 *
 * ★ 原来写的是「留空则用插件源（仅 PC）」。加了内置默认源后这句会误导：
 *   留空现在是走 GitHub 那个 repo 端点，插件源只在没内置源时才兜底。
 */
const updSourcePlaceholder = '留空 = 内置默认源（GitHub Release）'

/** 同步读一次（避免异步初始化导致输入框闪一下空值） */
function getUpdateSourceSync(): string {
  try {
    // Prefs 优先（能被推送带过来），legacy localStorage 兜底
    return getPrefs().updateSource || localStorage.getItem('meow.updateSource') || ''
  } catch {
    return ''
  }
}

function saveUpdateSource(): void {
  setUpdateSource(updSourceInput.value)
  setBackupUpdateSource(updBackupInput.value)
  // 换了源就把上次的检查结果作废，避免显示旧版本号误导
  updFound.value = null
  updChecked.value = false
  updError.value = ''
  updReady.value = false
  // 插件源跟随应用更新源时，立即把新地址同步过去并重拉索引
  void syncPluginAppSource()
    .then(() => loadIndex(true))
    .catch(() => {})
}

function clearUpdateSource(): void {
  updSourceInput.value = ''
  saveUpdateSource()
}

/**
 * 手动检查（非静默：错误必须显示出来）。
 *
 * ★ 手动检查走**非静默**分支，所以哪怕这个版本用户之前点过「稍后」，
 *   这里也会照常显示——他主动问的，不该被自己的旧选择挡住。
 */
async function checkUpdateNow(): Promise<void> {
  const up = await runUpdCheck()
  // 有更新就顺手打开弹框：用户点「检查更新」就是想看新版信息
  if (up) openUpdDialog()
}

/**
 * 检查中的计时 + 取消。
 *
 * 为什么要有：候选线路最多 4 条，网络差时一次检查几十秒。以前只有一个
 * 「检查中…」，用户既不知道还要等多久，也没有"我不想等了"的出口。
 * 取消只停前端等待（原生线程会被下一次检查取代），不影响已经拿到的结果。
 */
const updSecs = ref(0)
let updSecTimer: number | undefined

watch(updChecking, (on: boolean) => {
  if (updSecTimer) {
    window.clearInterval(updSecTimer)
    updSecTimer = undefined
  }
  if (!on) return
  updSecs.value = 0
  updSecTimer = window.setInterval(() => (updSecs.value += 1), 1000)
})

function cancelUpdCheck() {
  cancelAppUpdateCheck()
  updSecs.value = 0
}

/**
 * TV 壳里点「更新源 / 备用源」→ **就地编辑**（聚焦 + 系统键盘），不弹对话框。
 *
 * <p>不接管的话这个输入框在 TV 上**根本点不进去**（WebView 不聚焦、不弹输入法，
 * 用户以为能填、填完还是空），表现成"更新源填了没用/取不到新版本"。
 * 做法与搜索页输入框完全一致（用户口径：「对齐搜索页面输入框，不要加弹框」）。
 */
function editUpdateSource(ev: MouseEvent) {
  tvInlineInput(ev)
}

function editBackupSource(ev: MouseEvent) {
  tvInlineInput(ev)
}

/**
 * 「更新」按钮 = 一步到位（2026-11-13）。
 *
 * 下载 + 校验完成后由 store 直接调起系统安装器，界面不再出现第二次
 * 「安装」按钮 —— 原来那一步正是"点了又回到进度条"的死循环源头。
 */
async function downloadAndInstall(): Promise<void> {
  await runUpdInstall()
}

/** 取消下载（TV 上没有鼠标指针，误点要能退出来） */
async function cancelAppUpdate(): Promise<void> {
  await runUpdCancel()
}

/** 手动清理下载缓存（安装后不再自动删，见 stores/appUpdate 的注释） */
const updCacheBusy = ref(false)
const updCacheMsg = ref('')

async function clearCacheNow(): Promise<void> {
  if (updCacheBusy.value) return
  updCacheBusy.value = true
  updCacheMsg.value = ''
  try {
    const freed = await clearUpdateCache()
    updCacheMsg.value = freed > 0 ? `已释放 ${fmtSize(freed)}` : '没有可清理的安装包'
  } catch (e: any) {
    updCacheMsg.value = e?.message || '清理失败'
  } finally {
    updCacheBusy.value = false
  }
}

// 插件源列表（多源可切换）
const sources = ref<SourceList>({ current: '', items: [] })
/** 是否正在编辑；editingId 为空串表示"新增" */
const sourceEditing = ref(false)
const editingId = ref('')
const sourceForm = ref({ name: '', url: '' })
const sourceTesting = ref(false)
const sourceTestMsg = ref('')
const sourceTestOk = ref(false)
/** 插件源是否自动跟随应用更新源（安装器仍独立） */
const pluginUseAppSource = ref(getPrefs().pluginUseAppSource)

/**
 * 把“应用更新源”落成一条插件源。
 *
 * 不覆盖用户已有选择：已有自定义源时只新增/更新这条，不抢当前源。
 * 应用更新源本身可能是 release 端点，插件安装器会解析出 packages.json。
 */
async function syncPluginAppSource(select = false): Promise<void> {
  if (!desktop || !pluginUseAppSource.value) return
  try {
    const url = (await getUpdateSource()) || ''
    if (!url) return
    const list = await fetchPluginSources()
    if (!list) return
    const hit = list.items.find((s) => s.url === url)
    if (hit) {
      if ((select || !list.current) && list.current !== hit.id) {
        sources.value = await selectPluginSource(hit.id)
      }
      return
    }
    const named = list.items.find((s) => s.name === '应用更新源')
    if (named) {
      const old = list.current
      sources.value = await updatePluginSource(named.id, url, '应用更新源')
      // 更新地址可能把当前源改成了它；非主动选择时恢复用户原来的选择
      if (!select && old && old !== named.id) {
        sources.value = await selectPluginSource(old)
      }
      return
    }
    if (!list.items.length) {
      sources.value = await addPluginSource(url, '应用更新源')
      return
    }
    // 已有自定义源：加进去；非主动选择时恢复原选择
    const old = list.current
    sources.value = await addPluginSource(url, '应用更新源')
    if (!select && old) sources.value = await selectPluginSource(old)
  } catch {
    /* 插件源同步失败不阻塞更新检查 */
  }
}

async function togglePluginAppSource(): Promise<void> {
  savePrefs({ pluginUseAppSource: pluginUseAppSource.value })
  if (pluginUseAppSource.value) {
    await syncPluginAppSource(true)
    await loadIndex(true)
  }
}
// 粘贴清单
const showManifestPaste = ref(false)
const manifestPaste = ref('')
const manifestName = ref('')
const manifestMsg = ref('')
const manifestOk = ref(false)

// 下载进度
const progress = ref<InstallProgress | null>(null)
let progressTimer: number | undefined

function phaseText(p: PluginInfo['phase']) {
  return { stopped: '未运行', starting: '启动中', ready: '运行中', failed: '失败' }[p]
}
function phaseClass(p: PluginInfo['phase']) {
  if (p === 'ready') return 'bg-ok/15 text-ok'
  if (p === 'failed') return 'bg-hot/15 text-hot'
  if (p === 'starting') return 'bg-accent/15 text-accent'
  return 'bg-card text-ink-3'
}

/* ---------------------------------------------------------------- 插件列表排序与展示名
 *
 * ★ 为什么在前端排（2026-11-27）：Rust 侧 `state_of()` 按插件目录的 readdir 顺序
 *   输出，不同机器/不同安装顺序看到的行序都不一样。用户要求固定成
 *   「业务服务 → 工具 → 运行时」，所以在 UI 侧按 id 排序 —— 不动 Rust、不动 manifest，
 *   已装用户升级后立刻生效。
 *
 * ★ 为什么显示名在前端映射：Node 插件的 name 存在 zip 内的 manifest.json 里
 *   （由 server/build-release.ps1 生成）。改那里会让 node 插件包哈希变化、
 *   触发版本守卫要求升版本 —— 而 node 的版本号来自 node.exe（22.23.5），
 *   不能随意改。改显示层零风险。
 */
const PLUGIN_ORDER = ['apiserver', 'danmu-api', 'ffmpeg', 'node', 'drpy', 'pyrt']
const PLUGIN_LABELS: Record<string, string> = { node: 'Node 环境' }
/** 运行时职责标识：谁服务 .js、谁服务 .py（pyrt 还负责把 .js 转发给 drpy） */
const RUNTIME_BADGES: Record<string, string> = { drpy: 'JS', pyrt: 'PY' }

const sortedPlugins = computed(() => {
  const rank = (id: string) => {
    const i = PLUGIN_ORDER.indexOf(id)
    return i < 0 ? PLUGIN_ORDER.length : i
  }
  return [...plugins.value].sort((a, b) => rank(a.id) - rank(b.id))
})
function pluginLabel(p: PluginInfo): string {
  return PLUGIN_LABELS[p.id] || p.name
}
function runtimeBadge(id: string): string {
  return RUNTIME_BADGES[id] || ''
}

/**
 * 刷新插件状态（首刷 + 每 3s 轮询）。
 *
 * ★ 为什么合并成一次 IPC：`listPlugins()` 和 `bootInfo()` 内部**都**调
 *   `boot_info` 命令（前者只是取 `info.plugins`）。两次往返拿同一份数据，
 *   3 秒一次纯浪费。
 *
 * ★ 为什么版本号只取一次：`boot_info.app_version` 是 `env!("CARGO_PKG_VERSION")`
 *   —— **编译期常量**，运行期永不变化。原来每次轮询都赋值一遍，没有意义。
 */
let versionFromNative = false
async function refreshPlugins() {
  if (!desktop) return
  try {
    const info = await bootInfo()
    plugins.value = info?.plugins || []
    if (!versionFromNative && info?.app_version) {
      appVersion.value = info.app_version
      versionFromNative = true
    }
  } catch {
    /* 插件接口不可用时不打扰用户 */
  }
}

// ---------------------------------------------------------------- 索引 / 安装

/** 把 { id: pkg } 摊平成数组，并补上 label */
const remoteList = computed<RemotePlugin[]>(() => {
  const idx = remoteIndex.value
  if (!idx?.packages) return []
  return Object.entries(idx.packages).map(([id, p]) => ({
    ...p,
    id,
    label: p.display_name || id,
  }))
})

/** 远端可装的插件（排除 hidden，比如 node 运行时只作为依赖） */
const availablePlugins = computed(() => {
  const have = new Set(plugins.value.filter((p) => p.installed).map((p) => p.id))
  return remoteList.value.filter(
    (e) =>
      !e.hidden &&
      !have.has(e.id) &&
      // 只列能在本机装的包：tv-node-* 是 Android 运行模块（platform=android-*），
      // 出现在 PC「本机插件」里点安装只会解压失败；清单是外部输入，这里挡一层。
      Array.isArray(e.platform) &&
      e.platform.includes('windows-x64') &&

      // 应用自身的安装包条目（meow-pc / meow-tv 及旧 id app-pc / app-tv / app）
      // 不是插件：走了插件安装器会解压找不到 manifest.json 而失败。
      // 清单里已经写了 hidden:true，这里再挡一层——清单可能是别人手写的，
      // 也可能是 0.9.29 那版遗留的 app-pc 条目（改名桥）。
      e.id !== APP_PC_ID &&
      e.id !== APP_TV_ID &&
      e.id !== APP_PACKAGE_ID,
  )
})

/** 已装插件是否有新版 */
function updateFor(id: string): RemotePlugin | null {
  const remote = remoteList.value.find((e) => e.id === id)
  const local = installedMap.value[id]
  if (!remote || !local) return null
  return compareVer(remote.version, local.version) > 0 ? remote : null
}

function compareVer(a: string, b: string): number {
  const pa = a.split('-')[0].split('.').map((x) => parseInt(x, 10) || 0)
  const pb = b.split('-')[0].split('.').map((x) => parseInt(x, 10) || 0)
  for (let i = 0; i < 3; i++) {
    const d = (pa[i] || 0) - (pb[i] || 0)
    if (d !== 0) return d
  }
  return 0
}

function fmtSize(bytes: number): string {
  if (!bytes) return ''
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)}KB`
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`
}

/**
 * 拉远端插件索引。
 *
 * @param force 用户主动点击/切换源时为 true，忽略"没配源就不拉"的跳过
 *
 * ★ 为什么要这个守卫（用户反馈："一进设置页都检查更新，导致命令行窗口
 *   弹出好几次检查，容易卡死"）：
 *   进设置页会无条件走这里，而拉索引在 PC 上是**起子进程**（curl.exe），
 *   一次失败的最坏路径是「2 个候选地址 × 每个重试 3 次」= 6 个进程。
 *   没配插件源的用户（大多数人）完全不需要这次请求 —— Rust 侧的
 *   `fetch_index` 虽然会直接 Err，但走到那里之前已经没有成本了，
 *   而 `fetchPluginIndex` 本身是一次 IPC 往返，纯浪费。
 */
async function loadIndex(force = false) {
  if (!desktop) return
  // 先读源列表：没配源就不发请求（省掉一次 IPC + 潜在的子进程）
  await reloadSources()
  if (!force && !sources.value.current) {
    remoteIndex.value = null
    indexError.value = ''
    return
  }
  // 已经开始拉了就不要再叠一次（用户连点导航项时会出现）
  if (indexLoading.value) return
  indexLoading.value = true
  indexError.value = ''
  try {
    remoteIndex.value = await fetchPluginIndex()
    installedMap.value = await fetchInstalledPlugins()
  } catch (e: any) {
    indexError.value = e?.message || String(e)
    remoteIndex.value = null
  } finally {
    indexLoading.value = false
  }
}

// ---------------------------------------------------------------- 插件源管理

async function reloadSources() {
  try {
    const list = await fetchPluginSources()
    if (list) sources.value = list
  } catch {
    /* 忽略 */
  }
}

/** 新增（传 undefined）或编辑某个源 */
function openSourceEditor(item?: SourceItem) {
  sourceEditing.value = true
  editingId.value = item?.id || ''
  sourceForm.value = { name: item?.name || '', url: item?.url || '' }
  sourceTestMsg.value = ''
}

async function selectSource(id: string) {
  try {
    sources.value = await selectPluginSource(id)
    // 用户刚切换源：必须真去拉（force 跳过"没配源"的守卫）
    await loadIndex(true)
  } catch (e: any) {
    await alertDialog(e?.message || String(e))
  }
}

async function deleteSource(id: string) {
  if (!(await confirmDialog('确定删除这个插件源？', { danger: true }))) return
  try {
    sources.value = await removePluginSource(id)
    // 删到没有当前源时不要 force：否则会弹「还没有配置插件源」当错误显示。
    // 非 force 路径会在 current 为空时静默清空列表。
    await loadIndex()
  } catch (e: any) {
    await alertDialog(e?.message || String(e))
  }
}

async function saveSourceForm() {
  const url = sourceForm.value.url.trim()
  if (!url) {
    await alertDialog('请填写地址')
    return
  }
  try {
    if (editingId.value) {
      sources.value = await updatePluginSource(editingId.value, url, sourceForm.value.name)
    } else {
      sources.value = await addPluginSource(url, sourceForm.value.name)
    }
    sourceEditing.value = false
    await loadIndex(true)
  } catch (e: any) {
    await alertDialog(e?.message || String(e))
  }
}

/** 空源引导里的一步操作：填本机文件服务器的测试源 */
const TEST_SOURCE_URL = 'http://127.0.0.1:9000/packages.json'

async function quickAddSource() {
  try {
    sources.value = await addPluginSource(TEST_SOURCE_URL, '本机测试源')
    await loadIndex(true)
  } catch (e: any) {
    // 填不进去时退回手动编辑器，并把地址预填好，用户只改端口即可
    sourceForm.value = { name: '本机测试源', url: TEST_SOURCE_URL }
    editingId.value = ''
    sourceEditing.value = true
    await alertDialog(e?.message || String(e))
  }
}

async function testSource() {
  const url = sourceForm.value.url.trim()
  if (!url) return
  sourceTesting.value = true
  sourceTestMsg.value = ''
  try {
    sourceTestMsg.value = await testPluginSource(url)
    sourceTestOk.value = true
  } catch (e: any) {
    sourceTestMsg.value = e?.message || String(e)
    sourceTestOk.value = false
  } finally {
    sourceTesting.value = false
  }
}

async function importManifest() {
  const text = manifestPaste.value.trim()
  if (!text) return
  manifestMsg.value = ''
  try {
    sources.value = await importPluginManifest(text, manifestName.value)
    manifestOk.value = true
    manifestMsg.value = '导入成功，已设为当前源'
    iosNotify('导入完成', '源清单已导入并设为当前源')
    manifestPaste.value = ''
    showManifestPaste.value = false
    await loadIndex(true)
  } catch (e: any) {
    manifestOk.value = false
    manifestMsg.value = e?.message || String(e)
  }
}

/**
 * 下载并安装。这是耗时操作（最大 37MB），
 * 用轮询进度而不是等 Promise，用户能看到百分比也能取消。
 */
async function install(id: string) {
  busy.value = id
  progress.value = { id, stage: 'downloading', percent: 0, received: 0, total: 0, message: '准备中…' }
  // 每 400ms 问一次进度
  progressTimer = window.setInterval(async () => {
    try {
      const p = await fetchInstallProgress()
      if (p && p.stage !== 'idle') progress.value = p
    } catch {
      /* ignore */
    }
  }, 400)
  try {
    await installPlugin(id, appVersion.value)
    await refreshPlugins()
    await loadIndex(true)
    // 装完会自动启动；如果装的是后端插件，「启用」同样要清掉手填的外部地址
    const justInstalled = plugins.value.find((p) => p.id === id)
    if (justInstalled?.required && justInstalled.phase === 'ready') clearManualApiForPlugin()
    // ★ 启动时 initBackend 可能已经"提前放弃"（插件没装/没启动），
    //   desktopBase 还是空串。这里不重新取一次，装完照样 Failed to fetch。
    await refreshDesktopBase()
    syncApiUrlFromRuntime()
    await store.boot()
    /*
     * 装的是 ffmpeg 插件时要让转码能力缓存立刻失效（2026-11-27）。
     * 否则播放页在装插件前探到的 available=false 会被 30 秒 TTL 沿用，
     * 用户装完回到播放页仍然看到"本端不支持转码"——明明已经装好了。
     */
    invalidateTranscodeProbe()
  } catch (e: any) {
    const msg = e?.message || String(e)
    // 取消不算错误
    if (!/取消/.test(msg)) await alertDialog(`安装失败：${msg}`)
  } finally {
    if (progressTimer) window.clearInterval(progressTimer)
    progressTimer = undefined
    progress.value = null
    busy.value = ''
  }
}

async function cancelDownload() {
  try {
    await cancelInstall()
  } catch {
    /* ignore */
  }
}

/** 离线导入：交给 Tauri 的原生文件选择器 */
async function pickAndImport() {
  try {
    const picked = await tauriPickFile()
    if (!picked) return
    busy.value = '__import__'
    const id = await importPluginZip(picked)
    await refreshPlugins()
    await loadIndex(true)
    const imported = plugins.value.find((p) => p.id === id)
    if (imported?.required && imported.phase === 'ready') clearManualApiForPlugin()
    await refreshDesktopBase()
    syncApiUrlFromRuntime()
    await store.boot()
    console.info('[plugin] imported', id)
  } catch (e: any) {
    await alertDialog(`导入失败：${e?.message || String(e)}`)
  } finally {
    busy.value = ''
  }
}

/** 用 Tauri 的对话框插件选文件；没装插件时退回手输 */
async function tauriPickFile(): Promise<string | null> {
  const w = window as unknown as {
    __TAURI_INTERNALS__?: { invoke?: (c: string, a?: unknown) => Promise<unknown> }
  }
  const invoke = w.__TAURI_INTERNALS__?.invoke
  if (!invoke) return null
  try {
    const r = (await invoke('plugin:dialog|open', {
      options: {
        multiple: false,
        title: '选择插件包（.zip）',
        filters: [{ name: '插件包', extensions: ['zip'] }],
      },
    })) as string | string[] | null
    if (!r) return null
    return Array.isArray(r) ? r[0] : r
  } catch {
    // 对话框插件不可用时让用户手填路径
    return prompt('输入插件包（.zip）的完整路径：')
  }
}

async function removePlugin(id: string) {
  if (!(await confirmDialog(`确定卸载「${id}」？只删除本机文件，不影响远端。`, { danger: true })))
    return
  busy.value = id
  try {
    await uninstallPlugin(id)
    await refreshPlugins()
    await loadIndex(true)
  } catch (e: any) {
    await alertDialog(e?.message || String(e))
  } finally {
    busy.value = ''
  }
}

async function startPlugin(p: PluginInfo) {
  busy.value = p.id
  try {
    /*
     * 「启动」=「启用」（2026-11-20）。
     *
     * Rust 的 state_of() 对运行中的插件恒返回 enabled=true，但那份标记只活在
     * 当前进程里：不写 enabled.json 的话，页面开关显示"开"、下次启动却不会
     * 自启，用户看到的就是"点了启动，重启又没了"。这里启动前先把开关落盘。
     */
    await setPluginEnabled(p.id, true)
    await callPluginStart(p.id)
    // 启用本机后端 = 用户要连本机：清掉之前手填的外部地址，否则仍连远端
    if (p.required) clearManualApiForPlugin()
    await refreshPlugins()
    // 启动时插件可能没起来（desktopBase 空），先取一次地址再重连
    await refreshDesktopBase()
    syncApiUrlFromRuntime()
    // 后端刚起来时站点表可能还是空的，重连一次
    await store.boot()
  } catch (e: any) {
    await alertDialog(e?.message || String(e))
  } finally {
    busy.value = ''
  }
}

async function stopPlugin(p: PluginInfo) {
  busy.value = p.id
  try {
    await callPluginStop(p.id)
    await refreshPlugins()
    // 停掉后 backend_base 变 null，refreshDesktopBase 会把陈旧端口清掉
    await refreshDesktopBase()
    syncApiUrlFromRuntime()
    await store.boot()
  } catch (e: any) {
    await alertDialog(e?.message || String(e))
  } finally {
    busy.value = ''
  }
}

/**
 * 切换"随应用启动"。
 *
 * 只改配置，同时把当前进程状态同步过去——用户点开关的直觉是"现在就要生效"，
 * 如果只存配置不改进程，会以为没反应。
 */
async function toggleEnabled(p: PluginInfo) {
  busy.value = p.id
  const next = !p.enabled
  try {
    await setPluginEnabled(p.id, next)
    if (next && p.phase !== 'ready' && p.installed) {
      await callPluginStart(p.id)
      // 开着开关就等于启用本机后端：清掉手填的外部地址（同 startPlugin）
      if (p.required) clearManualApiForPlugin()
    } else if (!next && p.phase === 'ready') {
      await callPluginStop(p.id)
    }
    await refreshPlugins()
    await refreshDesktopBase()
    syncApiUrlFromRuntime()
    await store.boot()
  } catch (e: any) {
    await alertDialog(e?.message || String(e))
  } finally {
    busy.value = ''
  }
}

let pluginTimer: number | undefined

const syncStatus = ref<SyncStatus>(getSyncStatus())
let offSync: (() => void) | undefined

/** 同步状态文案 */
/** 上次启动拉取失败的原因；成功或下一次拉取后清空 */
const pullErr = ref(getLastPullError())

const syncText = computed(() => {
  const s = syncStatus.value
  if (!dav.value.enabled) return '自动同步已关闭'
  switch (s.state) {
    case 'syncing':
      return '同步中…'
    case 'ok':
      return s.message || '已同步'
    case 'error':
      return `同步失败：${s.message || '未知错误'}`
    case 'off':
      return '自动同步已关闭'
    default:
      if (pullErr.value) return `上次拉取失败：${pullErr.value.message}`
      return dav.value.lastSyncAt
        ? `等待变更（上次 ${new Date(dav.value.lastSyncAt).toLocaleString()}）`
        : '等待变更'
  }
})

onMounted(async () => {
  offSync = onSyncStatus((s) => (syncStatus.value = s))
  // 自动备份会产生新快照，同步完成后刷新列表
  window.addEventListener('meow:synced', onSynced)
  window.addEventListener('meow:search-history', onWordsChanged)
  window.addEventListener('meow:sync-pull-error', onPullErrorChanged)
  searchWordCount.value = listSearchHistory().length
  await loadPolicy()
  // 直播源改到进入该分区时才拉（见 ensureLives 的注释）
  if (!store.sites.length && !store.loading) store.boot()
  // TV：开机不跑的模块/插件/fpk 后台检查，放到这里补（见 App.vue 的说明）
  if (isTvShell()) void runBackgroundChecks()
  if (dav.value.url) void refreshSnapshots()
  // 插件状态：首刷 + 每 3s 轮询（用户可能在别处手动启停）
  await refreshPlugins()
  if (desktop) {
    // 插件可能晚于页面就绪：把输入框对齐到真实在用的地址
    syncApiUrlFromRuntime()
    pluginTimer = window.setInterval(refreshPlugins, 3000)
    // 插件源跟随应用更新源（默认开；已有自定义源时不抢当前选择）
    void syncPluginAppSource().then(() => loadIndex())
    // 拉一次远端索引（失败不阻塞页面，用户仍可导入本地包）
    void loadIndex()
  }
})

function onPullErrorChanged() {
  pullErr.value = getLastPullError()
}

function onSynced() {
  // 同步完成会写 lastSyncAt，但 dav 是组件挂载时的快照——不重读就会一直
  // 显示"上次"的旧时间（实测：刚上传完仍显示几小时前）。
  dav.value = getWebdav()
  if (dav.value.url) void refreshSnapshots()
}

function onWordsChanged() {
  searchWordCount.value = listSearchHistory().length
}

// ---------------------------------------------------------------- 左侧分区导航
//
// 布局参考「左栏固定 + 右侧区块滚动」：
//   - 左栏 position:sticky，页面滚动时它不动，只有内容区在走
//   - 点导航项 = 滚到对应 <section data-sec="...">
//   - 滚动时反查「哪个区块顶部越过视口 1/3 处」来高亮，比 IntersectionObserver
//     更稳：后者在快速滚动/长区块下会同时命中多个。
//
// 分区是静态的（不随插件安装变化），所以直接用常量表，不用从 DOM 里扫。
// PC 端多一个「本机插件」，用 desktop 过滤。
//
// ★ 这张表的顺序必须与模板里 <section> 的先后顺序完全一致：
//   onScrollSpy() 是按本表顺序逐项判断"顶部越过探针"的，顺序错了高亮会
//   停在错误的分区（表现为"滚到 WebDAV 那块，左边亮的却是离线缓存"）。
//   label 也必须与 <h2 class="set-title"> 一字不差 —— 同一个分区有两个名字时
//   用户会在左栏找不到它。tests/settings-nav-sync.test.ts 锁住这两条。
//
// ★ 9 个分区的划分依据是「用户想做什么」，不是「技术上属于哪个模块」。
//
// 2026-10-30 重构（用户原话「过于分散，让人摸不到头脑」）：原来 10 个区
// 混用了三种划分维度 —— 弹幕/播放/外观按界面区域、网络/离线缓存/WebDAV
// 按技术模块、数据与维护按动作性质，导致同一个任务横跨三个区。
// 最典型的是「某部剧搜不到」要找三处：网络(配置源) → 浏览与搜索(线程/缓存)
// → 数据与维护(重置源健康)。
//
// 现在每个区对应一个用户问题：
//   ① 连接    —— 连不上后端怎么办
//   ② 搜索与源 —— 搜不到/源不对怎么办
//   ③ 播放    —— 画面、连播、片头片尾
//   ④ 弹幕    —— 弹幕长什么样、从哪来
//   ⑤ 外观    —— 换皮肤 / 换列表密度
//   ⑥ 存储    —— 空间不够（离线缓存 + 观看记录）
//   ⑦ WebDAV  —— 多端同步
//   ⑧ AI      —— AI 配置
//   ⑨ 维护    —— 备份 / 日志 / 重置 / 远程调试
const NAV_ITEMS = [
  { id: 'conn', label: '连接' },
  { id: 'plugin', label: '本机插件', desktopOnly: true },
  { id: 'localexec', label: '本机执行', tvOnly: true },
  { id: 'runtime', label: '运行时' },
  // 托盘/关闭行为只在 PC 上有意义；Web 起不了托盘，TV 壳也不是这个交互
  { id: 'desktop', label: '桌面端行为', desktopOnly: true },
  { id: 'source', label: '搜索与源' },
  { id: 'live', label: '直播源' },
  { id: 'play', label: '播放' },
  { id: 'danmaku', label: '弹幕' },
  { id: 'appearance', label: '外观' },
  { id: 'storage', label: '存储' },
  { id: 'sync', label: 'WebDAV' },
  { id: 'ai', label: 'AI' },
  { id: 'net', label: '网络与加速' },
  { id: 'update', label: '更新' },
  { id: 'maint', label: '维护' },
] as const

const navItems = computed(() =>
  NAV_ITEMS.filter((i) => {
    // 运行时分区按端决定（PC 在「本机插件」里，不单独出现）
    if (i.id === 'runtime') return showRuntimeNav.value
    if ('desktopOnly' in i && i.desktopOnly) return desktop
    if ('tvOnly' in i && i.tvOnly) return tvMode
    // 原生壳才有（PC 的 Tauri / TV 的 WebView 壳）；纯 Web 隐藏
    if ('nativeOnly' in i && i.nativeOnly) return desktop || tvMode
    return true
  }),
)
const activeSec = ref<string>('conn')

/**
 * 分区呈现：**全部立即渲染**（2026-11-13 用户口径「完全放开即可，TV 也不要」）。
 *
 * 曾经做过"到过才挂内容"的懒挂载（3e0c185）：14 个分区只挂当前一块，其余用
 * `min-h-[20rem]` 空占位，滚到才长出来。理由是 TV 的 tv-nav 用几何最近邻选焦、
 * 「搜索与源」几百行列表会建几千节点，方向键变钝。
 *
 * 实测体验代价更大：一屏只暴露当前分区，下面全是空卡片（用户「远没有改之前
 * 有体验」，PC/手机/TV 一致），所以彻底放开、不再有任何占位。
 *
 * 将来若 TV 上方向键真被节点数拖慢，**应改 tv-nav 的候选集裁剪**（例如方向键
 * 只在当前分区内选焦），而不是再把内容藏起来——藏内容是对用户可见的退化。
 *
 * `visitedSec` 恒 true，模板里的 `<template v-if="visitedSec('x')">` 包裹层
 * 因此等价于直通；保留它只是为了让那次改动可以原地回滚，不持有任何状态。
 */
function visitedSec(_id: string): boolean {
  return true
}
/**
 * 遥控器最近一次停留的左侧导航项（分区 id）。
 *
 * <p>用途只有一个：让右侧内容按左键时**精确回到进入时的那一项**。
 * 纯几何选焦只能找"同一行最近的"，而导航项与右侧控件高度不对齐，
 * 实测会落到相邻项上；这里把落点固定成用户来时的导航项。
 *
 * <p>为什么与 activeSec 分开：用户可能只在导航里上下移动、还没按 OK
 * （activeSec 仍是旧分区），但右键已经能直接进入当前聚焦项的分区。
 * 此时左键该回的是"当前聚焦的那一项"，不是旧高亮项。
 */
const lastNavSec = ref<string>('conn')
/** 手机横向胶囊条：滚动页面时把激活项带进可视区 */
const chipsEl = ref<HTMLElement | null>(null)

/**
 * 页面滚动改变激活分区时，让胶囊条跟着横向滚。
 *
 * <p>用户反馈（2026-11-05）：「滚动时选中的胶囊在右侧出界」——
 * 胶囊条自己不会滚，页面往下滚到第 7、8 区时，选中项早就跑到屏幕外，
 * 用户不知道当前在哪一段，也没法点回去。
 *
 * <p>目标位置是**把激活项居中**（不是只让它露出来）：两侧都留出上下文，
 * 才能看出"前后还有别的分区"。用 scrollLeft 而不是 scrollIntoView ——
 * 后者在部分内核里会连带滚动竖向容器（把页面拽回去）。
 */
watch(activeSec, (id) => {
  const box = chipsEl.value
  if (!box || !box.clientWidth) return
  const el = box.querySelector<HTMLElement>(`[data-chip="${id}"]`)
  if (!el) return
  const target = el.offsetLeft - (box.clientWidth - el.offsetWidth) / 2
  box.scrollTo({ left: Math.max(0, target), behavior: 'smooth' })
})

/** 手动点击后短暂锁住滚动监听，避免平滑滚动过程中高亮来回跳 */
let navLock = 0
/**
 * 遥控器移焦点引起的滚动，在此刻之前不算"用户在浏览"。
 *
 * 与 {@link navLock} 分开：navLock 是"点击左导航后等滚动落定"，
 * 这个是"焦点移动时不要改高亮"。两者来源不同，合成一个会互相提前释放。
 */
let focusScrollIgnoreUntil = 0

/**
 * 滚动容器：
 *   宽屏 → 右侧内容 div（页面本身锁定高度，不滚）
 *   窄屏 → window（整页滚动）
 * 两个模式下 scroll 事件的目标不同，所以统一用 ref 判定。
 */
const contentRef = ref<HTMLElement | null>(null)

/**
 * 滚到指定分区。
 *
 * ★ behavior:'smooth' 在滚动容器上不可靠 —— 实测某些 Chromium/WebView2 环境里
 *   对非 window 的容器调用 scrollTo({behavior:'smooth'}) 会**静默忽略**，
 *   表现为「点了导航高亮变了、但内容区纹丝不动」。
 *   所以这里先发 smooth，再用 rAF 检查是否真的开始移动；没动就直接赋值兜底。
 */
function scrollContainerTo(c: HTMLElement, top: number) {
  const target = Math.max(0, top)
  const before = c.scrollTop
  try {
    c.scrollTo({ top: target, behavior: 'smooth' })
  } catch {
    c.scrollTop = target
    return
  }
  // 两帧后仍未移动 = smooth 被忽略，立即降级（不要等动画"结束"）
  requestAnimationFrame(() =>
    requestAnimationFrame(() => {
      if (Math.abs(c.scrollTop - before) < 1 && Math.abs(target - before) > 2) {
        c.scrollTop = target
      }
    }),
  )
}

/**
 * window 版的同一层兜底。
 *
 * ★ 别把 `scrollTo(smooth)` 和 `scrollIntoView(smooth)` 混为一谈：Android
 *   WebView 上前者正常（2026-11-13 设备实测 400ms 走了 285/300px），
 *   **后者带 smooth 会被整次丢弃**（调用命中目标元素、scrollY 位移 0；
 *   同元素去掉 smooth 立刻跳到 -6388px）。如果哪天 smooth 也被吞，这里补跳。
 */
function scrollWindowTo(top: number) {
  const target = Math.max(0, top)
  const before = window.scrollY
  try {
    window.scrollTo({ top: target, behavior: 'smooth' })
  } catch {
    window.scrollTo(0, target)
    return
  }
  requestAnimationFrame(() =>
    requestAnimationFrame(() => {
      if (Math.abs(window.scrollY - before) < 1 && Math.abs(target - before) > 2) {
        window.scrollTo(0, target)
      }
    }),
  )
}

/**
 * 点了导航之后，让目标分区「闪几下」。
 *
 * 为什么需要：滚动是平滑的，落到目标后 8 个分区的视觉重量几乎一样，
 * 眼睛要在密集的磁贴格里重新找一遍刚才点的是哪块。
 * 闪烁给一次明确的落点确认。
 *
 * ★ 为什么用 JS 逐个切 class 而不是 CSS animation：
 *   实测动画对象能注册（playState=running、duration 420ms），
 *   但 currentTime 恒为 0、计算样式不推进 —— 系统的
 *   `prefers-reduced-motion: reduce`（Windows 常见默认）会让时间轴停住。
 *   于是出现"animationName 读得到、画面却完全不动"的假象。
 *   改成定时切 class 后，动效偏好怎么设都有确定的反馈；
 *   同时也天然支持"重复点同一项能重新闪"。
 */
const FLASH_TIMES = 3
const FLASH_ON = 200
const FLASH_OFF = 160
let flashTimer: number | undefined
/** 当前正在闪的分区。切换目标时必须把它身上的类摘掉，见 flashSection。 */
let flashingEl: HTMLElement | null = null

/**
 * 停掉闪烁并清掉所有残留的 `is-flashing`。
 *
 * <p>★ 只清定时器是不够的。`tick` 是"亮→灭→亮→灭"交替，被
 * `clearTimeout` 打断时**停在哪一帧就永久停在哪一帧**——若停在 ON 帧，
 * 那个分区会一直亮着；用户紧接着点了另一个分区，就出现两个高亮框
 * （截图实测过：先点「网络」再点「远程调试」，两个框同时存在）。
 *
 * <p>兜底的 `querySelectorAll` 是防"记录丢失"：组件重渲染、
 * HMR、tick 内部抛异常都会让 `flashingEl` 指向已脱离文档的节点，
 * 那时只有按类名扫一遍才清得干净。
 */
function clearFlash() {
  if (flashTimer) {
    window.clearTimeout(flashTimer)
    flashTimer = undefined
  }
  if (flashingEl) {
    flashingEl.classList.remove('is-flashing')
    flashingEl = null
  }
  document
    .querySelectorAll<HTMLElement>('.is-flashing')
    .forEach((e) => e.classList.remove('is-flashing'))
}

function flashSection(el: HTMLElement) {
  clearFlash()
  flashingEl = el
  let n = 0
  const tick = () => {
    if (n >= FLASH_TIMES * 2) {
      el.classList.remove('is-flashing')
      flashTimer = undefined
      if (flashingEl === el) flashingEl = null
      return
    }
    // 偶数次亮、奇数次灭；最后一次灭完即停
    const on = n % 2 === 0
    el.classList.toggle('is-flashing', on)
    n++
    flashTimer = window.setTimeout(tick, on ? FLASH_ON : FLASH_OFF)
  }
  el.classList.remove('is-flashing')
  // 强制 reflow，保证从"灭"开始（连续点同一项时能重新闪）
  void el.offsetWidth
  tick()
}

/**
 * 遥控器移动焦点时，锁住滚动高亮。
 *
 * 只关心"焦点在内容区"的情形：焦点在左导航/顶栏时不会引起内容滚动，
 * 锁了也没意义（反而可能挡住真的滚动）。
 */
function onTvFocus() {
  const el = document.querySelector('.meow-tv-focus')
  if (!el) return
  if (el.closest('[data-tv-settings-nav]')) {
    /*
     * 记下当前停留的导航项。遥控器在左栏上下移动时就会触发，
     * 右侧内容的左键出口（data-tv-left）据此精确回到这一项。
     */
    const navBtn = el.closest<HTMLElement>('[data-tv-settings-nav] [data-tv-sec]')
    if (navBtn?.dataset.tvSec) lastNavSec.value = navBtn.dataset.tvSec
    return
  }
  if (el.closest('.app-header')) return
  focusScrollIgnoreUntil = Date.now() + 260
  /*
   * 记下"开始忽略时的位置"，供 onScrollSpy 判定该不该继续忽略。
   *
   * ★ 只靠 260ms 时间窗不够（2026-10-30 投影仪实测）：TV 上连续按方向键时
   * 每次移焦都会重置这个窗口，用户一路往下滚、高亮却始终停在原分区
   * （实测 scrollTop 已经 3018，高亮还在「13 数据与维护」）。
   * 改成「时间窗 + 位移量」双判据：只有小幅滚动（焦点框带来的最小必要滚动）
   * 才忽略；一次滚过超过 FOCUS_SCROLL_TOLERANCE 就认为是用户主动滚动，
   * 立刻恢复跟随。
   */
  focusScrollBase = currentScrollTop()
}
/** 忽略窗口开始时的滚动位置；-1 表示没有生效中的忽略窗口。 */
let focusScrollBase = -1
/** 焦点滚动容差（px）。超过就当作主动滚动，不再忽略。 */
const FOCUS_SCROLL_TOLERANCE = 120

/** 当前实际滚动位置：宽屏是右侧容器，窄屏是 window。 */
function currentScrollTop(): number {
  const c = contentRef.value
  if (c && c.scrollHeight > c.clientHeight + 1) return c.scrollTop
  return window.scrollY
}

// 从顶栏同步徽标点进来时带 ?sec=sync：等首屏渲染后再滚过去。
// 已在设置页时再点徽标不会重挂载组件（onMounted 不触发），所以再 watch 一次。
onMounted(() => {
  const sec = String(route.query.sec || '')
  if (sec) setTimeout(() => scrollToSec(sec), 350)
})

watch(
  () => route.query.sec,
  (sec) => {
    if (sec) setTimeout(() => scrollToSec(String(sec)), 350)
  },
)

async function scrollToSec(id: string) {
  const el = document.querySelector<HTMLElement>(`section[data-sec="${id}"]`)
  if (!el) return
  activeSec.value = id
  lastNavSec.value = id
  // 所有分区都已挂载（不再懒挂载），只需等一帧让 DOM 稳定再算落点
  await nextTick()
  // 进"数据与维护"时刷新一次缓存统计：数字是文案的一部分，
  // 不刷新会一直显示上次打开时的旧值
  if (id === 'maint') void loadSearchCacheStats()
  // 直播源同理：点到这一栏才拉（见 ensureLives 的注释）
  if (id === 'live') void ensureLives()
  /*
   * ★ 锁到落点稳定（2026-11-26 真机：点「9 AI」先到 9，随后高亮自己跳到 7）。
   *   跳转已改成瞬时滚动，但仍要覆盖 260ms 的二次校正；spy 在锁内不写高亮。
   */
  navLock = Date.now() + 1200
  // 立刻闪：目标是"确认落点"，早一点开始用户才不会觉得"点了没反应"
  flashSection(el)
  jumpToEl(el)
  // 校正结束后把高亮/选中钉回目标（防止 spy 抢写）
  for (const at of [350, 800]) {
    window.setTimeout(() => {
      if (Date.now() < navLock) {
        activeSec.value = id
        lastNavSec.value = id
      }
    }, at)
  }
}

/**
 * 跳到任意元素（分区内的子块也算）。
 *
 * ★ 容器分支必须用 getBoundingClientRect 求相对距离，不能用 offsetTop。
 *
 * 踩过的坑：容器的 offsetParent 是 body，容器内的 section 也把 body 当
 * offsetParent，所以 `el.offsetTop - c.offsetTop` 恒等于 0，公式退化成
 * `scrollTop - 8` —— 表现为「滚到底后点"连接"只回退几个像素」。
 *
 * 正确关系：目标在容器内容坐标里的位置
 *   = 当前容器滚动量 + (目标视口位置 - 容器视口位置)
 */
function jumpToEl(el: HTMLElement) {
  const offset = stickyTopOffset()
  /*
   * ★ 用**瞬时滚动**，不用 smooth（2026-11-26 真机：点胶囊滚动到一半又被
   *   改到别的分区）。原因有两个，都只在竖屏出现：
   *   1) 平滑滚动耗时会超过原来的滚动锁，spy 中途解锁改写高亮；
   *   2) 480ms 的二次校正会打断尚未结束的 smooth 动画，动画重新计时/被
   *      后面的布局变化顶掉，最后停在半路。
   *   瞬时滚动没有"进行中"状态，落点一次到位；随后的两帧/260ms 校正只做
   *   微量修正（顶栏/胶囊条高度、异步内容把目标推移）。
   */
  const apply = () => {
    const c = contentRef.value
    const useContainer = !!c && c.scrollHeight > c.clientHeight + 1
    if (useContainer) {
      const target =
        c.scrollTop + (el.getBoundingClientRect().top - c.getBoundingClientRect().top) - 8
      c.scrollTop = Math.max(0, target)
    } else {
      const want = el.getBoundingClientRect().top + window.scrollY - offset
      try {
        window.scrollTo({ top: Math.max(0, want), behavior: 'auto' as ScrollBehavior })
      } catch {
        window.scrollTo(0, Math.max(0, want))
      }
    }
  }
  apply()
  requestAnimationFrame(apply)
  window.setTimeout(apply, 260)
  /*
   * 再补两次：分区内容第一次挂载（visitedSec）会把上方分区撑高，
   * 目标被顶到胶囊下面。只到 260ms 时部分异步内容（统计/列表）还没渲染完，
   * 落点会偏；这两次只做位置校正，不重新闪框。
   */
  window.setTimeout(apply, 700)
  window.setTimeout(apply, 1200)
}

/**
 * 竖屏/窄屏跳转要让出的高度 = 顶栏 + 吸顶分区胶囊。
 *
 * <p>原来写死 72（只算了顶栏），竖屏下胶囊条吸在顶栏下沿还有 ~44px，
 * 目标分区标题就被压在胶囊下面，看起来"跳不准"；iPad 横屏走容器分支
 * 不受影响（用户实测横屏正确）。
 *
 * <p>★ 2026-11-26 再修：不要再拿「--header-h + 胶囊高度」相加。
 * 胶囊条自己有上下 padding，安全区也叠在顶栏里，两项相加量出来的下沿
 * 仍比实际低 —— 真机上标题还是被压住。直接取**实际遮挡元素的下沿**
 * （元素在视口里的 rect.bottom），滚到哪算哪，多一层都不漏。
 */
function stickyTopOffset(): number {
  const headerEl = document.querySelector<HTMLElement>('.app-header')
  const hr = headerEl?.getBoundingClientRect()
  const headerBottom =
    hr && hr.height > 0
      ? Math.max(0, hr.bottom)
      : parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--header-h')) || 56

  const nav = chipsEl.value
  let navBottom = 0
  if (nav && getComputedStyle(nav).display !== 'none') {
    const nr = nav.getBoundingClientRect()
    // 只在胶囊条已经吸到顶栏下方（或与顶栏重叠）时才算遮挡：
    // 页面在顶部时它在文档流里，bottom 同样是"它占到的位置"，不会多扣。
    if (nr.height > 0) navBottom = nr.bottom
  }
  return Math.max(headerBottom, navBottom) + 24
}

/**
 * 更新中心的「去安装 / 去更新 / 去配置」—— 跨区跳到某个子块。
 *
 * ★ 原来 UpdateCenter 自己调 `el.scrollIntoView({behavior:'smooth'})`，
 *   在 Android WebView 上**整次调用被丢弃**（2026-11-13 竖屏设备实测：
 *   调用命中了 section[runtime]，scrollY 位移 0px；同元素去掉 smooth
 *   立刻跳到 -6388px）。竖屏走 window 滚动 → 表现为「点去安装，页面
 *   纹丝不动」；横屏走右侧容器分支，看起来正常。
 *   所以跨区跳转一律回到这套导航逻辑（容器/窗口判定 + 让出顶栏 +
 *   smooth 兜底 + 闪框），不要再自己写滚动。
 */
async function gotoSelector(sel: string) {
  const el = document.querySelector<HTMLElement>(sel)
  if (!el) return
  const sec = el.closest<HTMLElement>('section[data-sec]')
  const id = sec?.dataset.sec
  if (id) {
    activeSec.value = id
    lastNavSec.value = id
  }
  await nextTick()
  navLock = Date.now() + 1200
  flashSection(el)
  jumpToEl(el)
}

// 滚到「直播源」时补拉：点导航由 scrollToSec 触发，纯滚动由这里补
watch(activeSec, (id) => {
  if (id === 'live') void ensureLives()
})

function onScrollSpy() {
  if (Date.now() < navLock) return
  /*
   * ★ 遥控器移焦点引起的滚动，不该改高亮。
   *
   * 踩过（2026-10-30 实测「返回落点是 04 网络，而我点的是 06 播放」）：
   * tv-nav 的 `highlight()` 会给元素做"最小必要滚动"（`scrollIntoViewWithin`），
   * 焦点从「06 播放」分区往下移到某个磁贴时页面滚了几十像素，
   * scroll spy 据此把高亮改成了「04 网络」。于是：
   *   用户点的是「06 播放」→ 焦点下移改设置 → 高亮已经偷偷变成别的分区
   *   → 按返回"回到高亮项"就落到了他不是最后编辑的地方。
   *
   * 判据用 tv-nav 广播的 `meow:tv-focus`：它每次移焦都会发（见 highlight 的
   * 注释），收到后 260ms 内不认滚动 —— 那段时间的滚动都是移焦点带来的。
   *
   * ★ 光有时间窗不够：TV 上连续按方向键会不断重置它，用户一路滚下去、
   *   高亮却始终不动（2026-10-30 投影仪实测 scrollTop=3018 时仍亮着
   *   「13 数据与维护」）。所以再加一条位移判据：离开起点超过
   *   FOCUS_SCROLL_TOLERANCE 就认定是主动滚动，恢复跟随。
   */
  const c0 = contentRef.value
  const scrolledNow = (c0 && c0.scrollHeight > c0.clientHeight + 1)
    ? c0.scrollTop
    : window.scrollY
  const movedFromFocus = focusScrollBase >= 0
    ? Math.abs(scrolledNow - focusScrollBase)
    : Infinity
  if (Date.now() < focusScrollIgnoreUntil && movedFromFocus < FOCUS_SCROLL_TOLERANCE) return
  // 走出忽略窗口的位移范围就彻底结束这次忽略
  focusScrollBase = -1
  const c = contentRef.value
  // 宽屏下容器被显式赋了 lg:h-full，clientHeight 一定 > 0 且自身可滚；
  // 窄屏（单列）容器高度由内容撑开，clientHeight 也 > 0 但不可滚 —— 靠
  // scrollHeight 判定更准。两者都不成立时退回 window。
  const useContainer = !!c && c.scrollHeight > c.clientHeight + 1
  const scrolled = useContainer ? c!.scrollTop : window.scrollY

  // 滚到最顶部时必须强制选中第一项 —— 否则第一个区块顶部在探针之下，
  // 而第二个区块可能恰好落在探针之上，出现"在顶部、高亮却是第二个"（实测踩过）。
  if (scrolled <= 8) {
    activeSec.value = navItems.value[0]?.id ?? 'conn'
    return
  }
  // 探针位置。
  //
  // ★ 两点都踩过，改动前先读完：
  // 1) 必须是**视口坐标**才能和 getBoundingClientRect().top 比。宽屏下滚动容器
  //    自身顶部不在 y=0（上面还有顶栏和内边距，实测偏下 80px），直接拿
  //    clientHeight/3 当坐标会让探针整体上移。
  // 2) 取 1/2 而不是 1/3。页面末尾两个分区（WebDAV 同步、数据与维护）加起来
  //    才占一屏，在探针 1/3 处它们的顶部永远推不到线上 —— 实测滚到底时
  //    WebDAV 顶部还差 3px，表现为「左边点了 WebDAV、滚动后高亮又跳回离线缓存」。
  const probe = useContainer
    ? c!.getBoundingClientRect().top + c!.clientHeight / 2
    : window.innerHeight / 2
  // 触底时强制选中最后一项 —— 与上面"顶部强制第一项"对称。
  // 末尾分区可能整体不足一屏高，探针法永远追不上它们（探针法只能判断
  // "顶部越过探针"，而最后一块的顶部还没到探针就已经滚不动了）。
  const maxScroll = useContainer
    ? c!.scrollHeight - c!.clientHeight
    : document.documentElement.scrollHeight - window.innerHeight
  if (maxScroll > 0 && scrolled >= maxScroll - 8) {
    activeSec.value = navItems.value[navItems.value.length - 1]?.id ?? 'maint'
    return
  }
  let current = navItems.value[0]?.id ?? 'conn'
  for (const item of navItems.value) {
    const el = document.querySelector<HTMLElement>(`section[data-sec="${item.id}"]`)
    if (!el) continue
    if (el.getBoundingClientRect().top <= probe) current = item.id
  }
  activeSec.value = current
}

/**
 * 焦点现在是不是在"右侧内容区"。
 *
 * 判据：有焦点框，且它不在左导航里，也不在顶栏里。
 * 不在顶栏也算"内容区"是有意的 —— 顶栏有自己的上下键语义（滚页面/回内容区），
 * 那两个键在顶栏上已经有用，不该被这里抢走。
 */
function focusIsInContent(): boolean {
  const el = document.querySelector('.meow-tv-focus')
  if (!el) return false
  if (el.closest('[data-tv-settings-nav]')) return false
  if (el.closest('.app-header')) return false
  return true
}

/**
 * 把焦点送回左导航「当前分区」那一项。
 *
 * 用 tv-nav 暴露的 `__meowTvFocus`：它按元素自己画框、同步 current 索引，
 * 比在这里手动加 class 可靠（手动加会留下两份 class 或索引错位）。
 *
 * ★ 落点是**当前高亮的那一项**，不是第一项。
 *
 * 用户原话（2026-10-30 第二次纠正）：「设置页 配置区的返回，聚焦从第一位置
 * 调整到左侧菜单栏 竖条处（定位处）」。
 * 左栏每项左侧有一根 active 竖条（`aria-current="true"` 那一项）。
 * 用户从「播放设置」滚下来改东西，按返回却跳回最上面的「01 连接」，
 * 还得再往下按十几下才能回原位——所以这里取当前分区。
 *
 * 三级来源：
 *   1. `aria-current="true"` —— 组件本来就按滚动位置标了这个
 *   2. 数据里的 `activeSec` 对应的那项
 *   3. 实在找不到才退回第一项（保持"至少能回左栏"的兜底）
 */
function focusSettingsNavFirst(): boolean {
  const nav = document.querySelector('[data-tv-settings-nav]')
  if (!nav) return false
  const w = window as unknown as { __meowTvFocus?: (t: Element) => boolean }
  if (typeof w.__meowTvFocus !== 'function') return false

  const cur = nav.querySelector('button.group[aria-current="true"]') as HTMLElement | null
  if (cur && w.__meowTvFocus(cur)) return true

  // 传第一个导航项按钮，而不是 nav 本身 —— nav 里还有「置顶」按钮，
  // 传容器会取到它（用户要的是"分区项"）
  const first = nav.querySelector('button.group') || nav.querySelector('button')
  return first ? w.__meowTvFocus(first) : false
}

/**
 * 设置页的返回键：在右侧内容区按返回 → 回左导航首位（不退出页面）。
 *
 * 用户要求原话：「在右侧内容区按返回（其他位置同两次退出app）、菜单键时，
 * 聚焦到左侧菜单栏首位」。
 *
 * 所以只在"焦点在内容区"时吃掉这次返回；在左导航或顶栏时仍走原来的路径
 * （首页那套"再按一次退出"）。
 */
function handleTvBackHere(): boolean {
  if (!focusIsInContent()) return false
  return focusSettingsNavFirst()
}

/** 设置页的菜单键：同样语义 —— 从内容区回左导航首位。 */
function handleTvMenuHere(): boolean {
  if (!focusIsInContent()) return false
  return focusSettingsNavFirst()
}

onMounted(() => {
  /*
   * 挂两个页面级钩子：
   *   __meowTvPageBack —— App.vue 的 onTvBack 在判断路由前会先问它
   *   __meowTvMenuHook —— tvMenu.ts 的桥在找 provider 前会先问它
   *
   * 用钩子而不是各写一套 keydown 监听：壳的返回/菜单键都不经过
   * `dispatchKeyEvent` → 页面收不到 keydown，只能靠这条约定链。
   */
  ;(window as unknown as { __meowTvPageBack?: () => boolean }).__meowTvPageBack =
    handleTvBackHere
  ;(window as unknown as { __meowTvMenuHook?: () => boolean }).__meowTvMenuHook =
    handleTvMenuHere
  /*
   * 通知 App.vue 重新判定"返回键该不该由它接管"。
   *
   * 必须发：App 的 `watch(route.path)` 比本组件的 onMounted 早执行，
   * 那一刻这个钩子还不存在，判据会算出"不用接管"并把
   * `__meowTvConsumeBack` 摘掉 —— 结果按返回时壳直接 goBack()，
   * 整页退回首页（2026-10-30 实测就是这个症状）。
   */
  window.dispatchEvent(new Event('meow:tv-pageback'))

  // 远程调试的"剩余时间"每分钟刷新一次；离开设置页就停
  if (debugPlatform.value === 'tv') {
    dbgTimer = window.setInterval(refreshTvDebug, 60000)
  }

  // 容器滚动与整页滚动都直接挂在各自元素上：
  // 只挂 window 时，容器内滚动不会冒泡到 window（scroll 不冒泡），
  // 用 capture 虽然能收到，但事件 target 判断容易出错，直接挂更稳。
  const c = contentRef.value
  if (c) c.addEventListener('scroll', onScrollSpy, { passive: true })
  window.addEventListener('scroll', onScrollSpy, { passive: true })
  window.addEventListener('meow:tv-focus', onTvFocus)
  window.addEventListener('meow:prefs', onPrefsChanged)
  document.addEventListener('keydown', onSitesKey)
  nextTick(() => onScrollSpy())
})

onBeforeUnmount(() => {
  // 必须清：否则别的页面会继承"按返回回左导航"这个设置页专属行为
  const w = window as unknown as {
    __meowTvPageBack?: () => boolean
    __meowTvMenuHook?: () => boolean
  }
  if (w.__meowTvPageBack === handleTvBackHere) w.__meowTvPageBack = undefined
  if (w.__meowTvMenuHook === handleTvMenuHere) w.__meowTvMenuHook = undefined
  // 检查计时器：离页后不能再跳秒（否则回到设置页显示一个假的"检查中 999s"）
  if (updSecTimer) window.clearInterval(updSecTimer)
  if (dbgTimer) {
    window.clearInterval(dbgTimer)
    dbgTimer = undefined
  }

  window.removeEventListener('meow:tv-focus', onTvFocus)
  window.removeEventListener('meow:prefs', onPrefsChanged)
  document.removeEventListener('keydown', onSitesKey)
  offSync?.()
  if (pluginTimer) window.clearInterval(pluginTimer)
  // 用 clearFlash 而不是只清定时器：离开页面时若正停在 ON 帧，
  // 残留的 is-flashing 会让下次进来那个分区莫名带一圈高亮
  clearFlash()
  // 连接弹层的扫描轮询也必须停：不停会一直读桥，页面销毁后是空转
  stopConnPoll()
  window.removeEventListener('meow:synced', onSynced)
  window.removeEventListener('meow:search-history', onWordsChanged)
  window.removeEventListener('meow:sync-pull-error', onPullErrorChanged)
  window.removeEventListener('scroll', onScrollSpy)
  contentRef.value?.removeEventListener('scroll', onScrollSpy)
})
</script>

<template>
  <!--
    两栏布局（宽屏）：
      整个页面高度锁死为视口高度，**只有右侧内容区滚动**，左栏物理上不可能移动。
      之前的 sticky 方案在理论上不跟随滚动，但有两个实际观感问题：
        1. 首次滚动的瞬间它从 static 位置"吸附"到 top，有一次位移
        2. 左栏内容比视口高时会自己内部滚动，看起来像"栏在动"
      改成父级定高 + 子级 overflow 后，左栏彻底静止。

    窄屏（< lg）：不锁高度，整页滚动，导航变成顶部横向 chips。

    ★ 高度扣法必须把 py-4 算进去，否则左栏被裁。
    踩过（2026-10-30 投影仪 0.9.39 实测）：
      原来写 `lg:h-[calc(100vh-6rem)]` + `py-4` → 实测 wrap=612、aside=576，
      而 aside 内容需要 **597px**，「13 数据与维护」被裁掉半截。
      `6rem` 名义上是"顶栏 4rem + 上下留白 2rem"，但 `py-4` 的 2rem 是
      内边距，它已经吃掉了内容高度 —— 再在 calc 里扣一次等于扣两遍。
    现在：`100vh - 顶栏 - 上下 padding`，padding 用 2rem 与 py-4 对齐。
    `--header-h` 会随 TV 根字号放大（4rem → 72px），那是真实占位，必须扣。
  -->
    <!--
      高度 = **视口 - 顶栏**，不能再减 `2rem`：py-4 的上下 padding 已经算在
      border-box 高度里，多减一次会让容器比 main 矮 32px，横屏手机 393 高的
      视口底部固定空 48px（2026-11-17 用户报「设置页底部 padding 太大」）。

      ★ 不能用 h-full：main 的高度是 flex-1 拉伸出来的 auto，百分比高度
        不解析 → 容器塌成内容总高（实测 6335px，双栏变成长页）。
      ★ dvh 覆盖 vh：移动端浏览器地址栏收起/展开时 vh 与可视高度不一致；
        老内核（TV WebView）不认识 dvh 时整条声明失效，自动落回上面那条 vh。
    -->
    <div
      class="mx-auto max-w-[1500px] px-4 py-4 max-sm:px-3 max-sm:py-3 wide:flex wide:h-[calc(100vh-var(--header-h))] wide:h-[calc(100dvh-var(--header-h))] wide:flex-col wide:overflow-hidden wide:px-6"
  >
    <!--
      窄屏：横向分区胶囊（2026-11-05 用户要求「滚动到上方时固定到顶部」）。

      ★ 必须放在**页面容器**的直接子级：sticky 的可粘范围被最近的块级祖先
        （这里是页面容器，高度=整页）限制。原来它嵌在 <aside> 里，而 aside
        在窄屏只有胶囊这一行高 —— sticky 的可粘范围等于自身高度，滚一下就没了。
      ★ 底色用 --color-strip-veil + 背景模糊（并补 180% 饱和），和手机底部标签栏
        同一套玻璃：吸顶后紧贴顶栏下沿，视觉上是一条连续的面板；不吸顶时它就在
        顶栏下面，看起来也是顶栏的一部分。蒙层比标签栏再实一点点（用户口径
        「对齐 tabbar 透明度，略低一点点」）。
      ★ z-20 < 顶栏 z-30，滚动时不会盖住顶栏。
    -->
    <nav
      ref="chipsEl"
      data-sticky-top
      class="no-scrollbar sticky top-[var(--header-h)] z-20 -mx-4 flex gap-1.5 overflow-x-auto scroll-smooth border-b border-line/60 bg-[var(--color-strip-veil)] px-4 pb-2 pt-1 backdrop-blur-xl backdrop-saturate-[180%] max-sm:-mx-3 max-sm:gap-2 max-sm:px-3 wide:hidden"
      aria-label="设置分区"
    >
      <button
        v-for="(i, idx) in navItems"
        :key="i.id"
        :data-chip="i.id"
        type="button"
        class="shrink-0 rounded-full border px-3 py-1.5 text-xs transition max-sm:px-3.5 max-sm:py-2"
        :class="
          activeSec === i.id
            ? 'border-accent bg-accent font-medium text-on-accent'
            : 'border-line bg-card text-ink'
        "
        @click="scrollToSec(i.id)"
      >
        <span class="mr-1 tabular-nums opacity-50">{{ idx + 1 }}</span>{{ i.label }}
      </button>
    </nav>

    <!--
      ★ -m-2 p-2 是给「跳转闪框」留的出血位（2026-11-26 真机：聚焦分区时
      圆环左右被裁）。overflow-x-hidden 会按 padding box 裁剪，卡片正好铺满
      内容宽度时，outline（offset 2 + 2px）整条落在裁剪区外。负 margin +
      等量 padding 让盒子的裁剪边界外扩 8px，内容宽度与位置都不变。
    -->
    <div
      class="-m-2 flex min-w-0 flex-col gap-4 overflow-x-hidden p-2 max-sm:mt-3 wide:grid wide:min-h-0 wide:flex-1 wide:grid-cols-[200px_minmax(0,1fr)] wide:grid-rows-[minmax(0,1fr)] wide:gap-6"
    >
      <!-- ── 左侧导航（宽屏时定高不滚） ── -->
      <aside
        class="wide:h-full wide:min-h-0 wide:overflow-y-auto"
        aria-label="设置分区导航"
      >

        <!-- 宽屏：竖向列表。
             底色用 card-sunken（与右侧分区卡同一套）：浅色下是实心白，
             深色下比磁贴更淡，视觉上"侧栏是一块面板"而不是一层灰雾 -->
        <!--
          data-tv-settings-nav：返回键/菜单键要"把焦点送回这里"的锚点。
          见 handleTvBackHere / handleTvMenuHere。
        -->
        <nav
          data-tv-settings-nav
          class="hidden flex-col rounded-xl border border-line bg-card-sunken p-2 wide:flex"
          aria-label="设置分区"
        >
          <div class="mb-1.5 flex items-center justify-between px-2">
            <span class="text-[11px] font-medium tracking-wide text-ink-2">本页导航</span>
            <button
              type="button"
              class="rounded px-1 py-0.5 text-[11px] text-ink-3 transition hover:text-accent focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent/60"
              @click="scrollToSec(navItems[0].id)"
            >
              置顶
            </button>
          </div>
          <div class="flex flex-col gap-px">
            <!--
              ★ 项高要压住，否则 TV 上最后一项被裁。
              实测（2026-10-30 投影仪 720px 视口 / 根字号 18px）：
              14 项 × 41px = 597px，而 aside 只有 576px →
              「13 数据与维护」被裁掉半截（虽然能滚，但 TV 没有滚动条提示，
              用户以为内容丢了）。
              减到 py-1(8px 总) 后每项约 35px × 14 = 490px，装得下。
              TV 用方向键 + 焦点框定位，不需要鼠标级热区。
            -->
            <button
              v-for="(i, idx) in navItems"
              :key="i.id"
              :id="'set-nav-' + i.id"
              :data-tv-sec="i.id"
              :data-tv-right="'section[data-sec=' + i.id + ']'"
              type="button"
              class="group relative flex items-center gap-2.5 rounded-lg px-2.5 py-1 text-left transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent/60"
              :class="
                activeSec === i.id
                  ? 'bg-white/[0.08] text-ink'
                  : 'text-ink-2 hover:bg-white/[0.04] hover:text-ink'
              "
              :aria-current="activeSec === i.id ? 'true' : undefined"
              :data-tv-first="idx === 0 ? '' : undefined"
              @click="scrollToSec(i.id)"
            >
              <!-- 序号：给 8 个分区块一个可数的锚点，扫读时不必逐字读标题 -->
              <span
                class="w-4 shrink-0 text-[10px] tabular-nums transition-colors"
                :class="activeSec === i.id ? 'text-accent' : 'text-ink-3 group-hover:text-ink-2'"
              >
                {{ String(idx + 1).padStart(2, '0') }}
              </span>
              <span class="truncate text-[13px]" :class="activeSec === i.id ? 'font-medium' : ''">
                {{ i.label }}
              </span>
              <!-- 激活指示条 -->
              <span
                v-if="activeSec === i.id"
                class="absolute -left-2 top-1/2 h-4 w-[2px] -translate-y-1/2 rounded-r-full bg-accent"
              />
            </button>
          </div>
        </nav>
      </aside>

      <!-- ── 右侧内容：宽屏下这里才是唯一的滚动容器 ── -->
      <!--
        ★ 左右内边距必须对称（pl-1 + pr-1）。闪烁框是 outline + 2px offset，
        画在卡片盒子**外面**（约 4px）。滚动容器 overflow-y:auto 会把超出
        内容盒的部分裁掉：原来只有 pr-1，右边放得下、左边 padding 是 0，
        于是「左边那一竖被切掉，另外三边正常」。
        实测：card.left=248 与 scroller.left=248 完全重合，间隙 0px。
      -->
      <div
        ref="contentRef"
        :data-tv-left="'#set-nav-' + lastNavSec"
        class="flex min-w-0 flex-col gap-3 wide:-ml-1 wide:h-full wide:min-h-0 wide:overflow-y-auto wide:px-1"
      >
    <!-- ══════════ 连接 ══════════ -->
    <section data-sec="conn" class="set-card shrink-0">
      <template v-if="visitedSec('conn')">
      <div class="set-head">
        <h2 class="set-title">连接</h2>
        <span class="set-hint">后端地址 · 站点概览</span>
        <span class="set-actions flex flex-wrap items-center gap-2">
          <span
            class="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px]"
            :class="store.online ? 'bg-ok/15 text-ok' : 'bg-hot/15 text-hot'"
          >
            <span
              class="h-1.5 w-1.5 rounded-full"
              :class="store.online ? 'bg-ok' : 'bg-hot'"
            />
            {{ store.online ? '已连接' : '未连接' }}
          </span>
          <span class="text-[11px] text-ink-3">
            <template v-if="store.online">
              {{ store.sites.length }} 源 · {{ extCount }} 个扩展<template v-if="badCount">
                · {{ badCount }} 不可用</template><template v-if="unsupportedCount">
                · {{ unsupportedCount }} 待配置</template><template v-if="skippedCount">
                · {{ skippedCount }} 搜索时跳过</template>
            </template>
            <template v-else>{{ offlineHint }}</template>
          </span>
        </span>
      </div>

      <div v-if="!ios || iosBackendConfigurable" class="flex flex-wrap items-center gap-2.5">
        <span class="w-20 shrink-0 text-xs text-ink-3">后端地址</span>
        <!--
          输入框始终可编辑：插件托管时也允许改（改完点右边按钮就切到远端）。
          原来这里 readonly + 隐藏保存，扫描到的地址填进来却没法保存（2026-11-20 修）。
        -->
        <input
          v-model="apiUrl"
          :placeholder="apiPlaceholder"
          class="min-w-[16rem] max-sm:min-w-[10rem] flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs outline-none transition focus:border-accent"
          :title="
            apiManagedByPlugin
              ? '当前由「影视后端」插件自动分配；填入别的地址即切到远端'
              : '手填的后端地址；点右侧「用插件」可恢复自动'
          "
          @input="apiDirty = true"
          @keyup.enter="saveAndReconnect"
        />
        <!-- 保存与重连合成一个动作：只点「重连」不会用上新填的地址（旧行为） -->
        <button
          class="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-on-accent transition hover:bg-accent-dim disabled:opacity-40"
          :disabled="apiBusy"
          @click="saveAndReconnect"
        >
          {{ apiBusy ? '连接中…' : '保存并重连' }}
        </button>
        <button
          v-if="desktop && manualApi"
          class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent/60"
          title="清掉手填地址，改回由本机插件提供后端"
          @click="usePluginBackend"
        >
          用插件
        </button>
        <!-- iOS：恢复端内 JVM 后端（清掉壳里存的覆盖地址） -->
        <button
          v-if="iosBackendConfigurable && iosOverrideActive"
          class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover"
          title="清掉外部后端地址，改回 App 进程内的端内后端"
          @click="restoreEmbeddedBackend"
        >
          恢复端内后端
        </button>
      </div>
      <!--
        iOS 壳：后端是 App 进程内的 JVM 网关（ApiServer），端口与地址由壳决定。
        允许改地址 = 把整个 App 指向别人的后端（本地网关还在跑但没人用它），
        所以这里只读展示，并把运行时与版本一并给出。
      -->
      <div v-else class="flex flex-wrap items-center gap-2.5">
        <span class="w-20 shrink-0 text-xs text-ink-3">本地网关</span>
        <span
          class="min-w-[16rem] max-sm:min-w-[10rem] flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs text-ink-2"
        >
          {{ iosInfo.apiBase || 'http://127.0.0.1:9978' }}
        </span>
        <span class="text-[11px] text-ink-3">
          {{ iosInfo.runtime || 'OpenJDK 9 Zero' }} · v{{ iosInfo.version || '—' }}
          <template v-if="iosInfo.build"> · {{ iosInfo.build }}</template>
        </span>
      </div>
      <p
        v-if="apiMsg"
        class="mt-1.5 text-[11px]"
        :class="apiMsgOk ? 'text-ok' : 'text-hot'"
      >
        {{ apiMsg }}
      </p>
      <!--
        同时存在「本机插件在跑」与「手填远端」时，resolveBase 用手填值。
        这本身是合法形态（临时连 NAS 调试），但不说清楚用户会以为用的是插件。
      -->
      <p
        v-if="desktop && manualApi && backendPlugin && backendPlugin.phase === 'ready'"
        class="mt-1.5 text-[11px] text-ink-2"
      >
        手填地址优先：本机插件虽然在运行（{{ backendPlugin.base }}），请求仍走
        {{ manualApi }}。要改用本机，点「用插件」。
      </p>

      <div class="mt-2 flex flex-wrap items-center gap-2.5">
        <span class="w-20 shrink-0 text-xs text-ink-3">写操作令牌</span>
        <input
          v-model="authToken"
          type="password"
          autocomplete="off"
          placeholder="后端 policy.json 未设 authToken 时留空"
          class="min-w-[16rem] max-sm:min-w-[10rem] flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs outline-none transition focus:border-accent"
          title="对应后端数据目录 policy.json 的 authToken；只存本机，不参与跨端同步与备份"
          @keyup.enter="saveAuthToken"
        />
        <button
          class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent/60"
          @click="saveAuthToken"
        >
          {{ authSaved ? '已保存' : '保存' }}
        </button>
      </div>

      <!-- iOS：可连外部后端（对齐 TV）；地址由壳落盘，reload 后生效 -->
      <p v-if="iosBackendConfigurable" class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
        可填局域网 / Docker / NAS 后端（如
        <code class="rounded bg-field px-1">http://192.168.1.10:30010</code>），下面的
        「扫描局域网」能自动发现；地址留空则回到 App 端内后端。
      </p>
      <!-- Web 端：没有插件，必须填地址 —— 这条不能只靠 placeholder 暗示 -->
      <p v-if="!desktop && !ios" class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
        没有本机插件，需要填可访问的后端地址（Docker / NAS / 自建）；留空则用同源
        <code class="rounded bg-field px-1">/api</code>。
      </p>
      <!-- PC 端：地址由插件决定，强调不要写死 -->
      <p v-else-if="apiManagedByPlugin" class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
        留空时地址由「影视后端」插件自动分配，端口冲突会顺延；要连别的机器（NAS /
        Docker），扫描或直接填上面的地址再点「保存并重连」。
      </p>

      <!-- TV：遥控器打字太痛苦，给两个"不用打字"的入口 -->
      <div v-if="tvMode" class="mt-3 flex flex-wrap items-center gap-2">
        <button
          class="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-on-accent hover:bg-accent-dim"
          @click="openConnDialog()"
        >
          局域网自动发现
        </button>
        <!-- 扫码独立成按钮：它除了填后端地址，还能改搜索/弹幕/播放/外观，
             与"扫描局域网"是两件事，混在一个按钮里用户不会发现后者 -->
        <button
          class="rounded-md bg-card px-3 py-1.5 text-xs font-medium hover:bg-card-hover"
          @click="openScanDialog()"
        >
          扫码填写
        </button>
        <span class="text-[11px] text-ink-3">
          扫描局域网列出可连的后端；或用手机扫码一次性配好常用设置
        </span>
      </div>

      <!--
        Web / PC：没有原生 LanScan，用页面 fetch 探 /api/health（2026-11-15 修5，
        对齐 TV 的自动发现）。https 页面拦 http 探测，工具里会给出对应提示。

        ★ 改成弹框（2026-11-20）：结果不再铺在设置页里，避免"扫完滚走了就找不到"；
          弹框内是「扫描 → 选中 → 保存并重连」的完整动作链，与 TV 连接弹层一致。
      -->
      <div v-if="!tvMode" class="mt-3 flex flex-wrap items-center gap-2">
        <button
          class="rounded-md bg-card px-3 py-1.5 text-xs font-medium transition hover:bg-card-hover focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent/60"
          @click="openWebScanDialog()"
        >
          扫描局域网
        </button>
        <span class="text-[11px] text-ink-3">
          扫同网段 30010 / 9979 端口的后端，弹框里选中后一键保存并重连
        </span>
      </div>

      </template>
      </section>

    <!-- ══════════ 本机插件（仅桌面端）══════════ -->
    <section
      v-if="desktop"
      data-sec="plugin"
      class="set-card shrink-0"
    >
      <template v-if="visitedSec('plugin')">
      <div class="set-head">
        <h2 class="set-title">本机插件</h2>
        <span class="text-[11px] text-ink-3">
          按需下载 · 开关 = 是否随应用启动 · <span class="text-ink-2">JS</span> 跑 .js 源 ·
          <span class="text-ink-2">PY</span> 跑 .py 源（并把 .js 转发给 drpy）
        </span>
        <span class="flex-1" />
        <button
          class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover"
          :disabled="indexLoading"
          @click="loadIndex()"
        >
          {{ indexLoading ? '检查中…' : '检查更新' }}
        </button>
        <button
          class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover"
          :disabled="busy === '__import__'"
          @click="pickAndImport()"
        >
          导入本地包
        </button>
      </div>

      <!-- 运行时状态不再单开一块（2026-11-27）：drpy / pyrt 就在下面的插件列表里，
           状态胶囊右侧直接标 JS / PY，用户看一行就知道谁跑哪种源。 -->

      <!-- 插件源管理 -->
      <div class="mb-2 rounded-lg bg-white/[0.04] px-3 py-2" data-plugin-source>
        <div class="mb-1.5 flex items-center gap-2">
          <span class="text-[11px] font-medium text-ink-2">插件源</span>
          <span class="text-[11px] text-ink-3">可添加多个，切换即生效</span>
          <span class="flex-1" />
          <button
            class="rounded-md bg-card px-2 py-0.5 text-[11px] hover:bg-card-hover"
            @click="openSourceEditor()"
          >
            添加
          </button>
          <button
            class="rounded-md bg-card px-2 py-0.5 text-[11px] hover:bg-card-hover"
            @click="showManifestPaste = !showManifestPaste"
          >
            粘贴 JSON
          </button>
        </div>
        <p class="mb-1 text-[11px] text-ink-3">
          加速与访问令牌同「网络与加速 → GitHub 加速」，私有仓只填一次。
        </p>
        <label class="mb-1.5 flex items-center gap-1.5 text-[11px] text-ink-2">
          <input
            v-model="pluginUseAppSource"
            type="checkbox"
            @change="togglePluginAppSource()"
          />
          使用应用更新源（同一份 packages.json；插件安装器仍独立）
        </label>

        <div
          v-for="s in sources.items"
          :key="s.id"
          class="flex flex-wrap items-center gap-2 border-t border-line py-1 first:border-0"
        >
          <input
            type="radio"
            :checked="s.id === sources.current"
            class="h-3 w-3"
            @change="selectSource(s.id)"
          />
          <span class="w-16 shrink-0 truncate text-[11px]" :title="s.name">{{ s.name }}</span>
          <span class="min-w-0 flex-1 truncate font-mono text-[11px] text-ink-3" :title="s.url">
            {{ s.url }}
          </span>
          <span v-if="s.id === sources.current" class="text-[10px] text-accent">当前</span>
          <button
            class="rounded px-1.5 py-0.5 text-[11px] text-ink-3 hover:bg-card-hover hover:text-ink"
            @click="openSourceEditor(s)"
          >
            编辑
          </button>
          <button
            class="rounded px-1.5 py-0.5 text-[11px] text-ink-3 hover:bg-hot/20 hover:text-hot disabled:opacity-40"
            :disabled="sources.items.length <= 1"
            @click="deleteSource(s.id)"
          >
            删除
          </button>
        </div>

        <div
          v-if="sourceEditing"
          class="mt-2 flex flex-wrap items-center gap-2 border-t border-line pt-2"
        >
          <span class="w-16 shrink-0 text-[11px] text-ink-3">名称</span>
          <input
            v-model="sourceForm.name"
            class="w-28 rounded-md border border-line bg-field px-2 py-1 text-[11px] outline-none focus:border-accent"
            placeholder="可选"
          />
          <span class="shrink-0 text-[11px] text-ink-3">地址</span>
          <input
            v-model="sourceForm.url"
            class="min-w-[14rem] max-sm:min-w-[10rem] flex-1 rounded-md border border-line bg-field px-2 py-1 font-mono text-[11px] outline-none focus:border-accent"
            placeholder="http://127.0.0.1:8099/packages.json"
          />
          <button
            class="rounded-md bg-card px-2 py-1 text-[11px] hover:bg-card-hover disabled:opacity-40"
            :disabled="sourceTesting"
            @click="testSource()"
          >
            {{ sourceTesting ? '测试中…' : '测试' }}
          </button>
          <button
            class="rounded-md bg-accent px-2.5 py-1 text-[11px] text-on-accent"
            @click="saveSourceForm()"
          >
            保存
          </button>
          <button
            class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover"
            @click="sourceEditing = false"
          >
            取消
          </button>
          <div
            v-if="sourceTestMsg"
            class="w-full text-[11px]"
            :class="sourceTestOk ? 'text-ok' : 'text-hot'"
          >
            {{ sourceTestMsg }}
          </div>
        </div>

        <div v-if="showManifestPaste" class="mt-2 border-t border-line pt-2">
          <div class="mb-1 text-[11px] text-ink-3">
            直接粘贴 packages 清单内容。适用于没有服务器、或想手动固定一份清单的场景。
            注意：清单里的 url 要用绝对地址（本地清单没有服务器可拼相对路径）。
          </div>
          <textarea
            v-model="manifestPaste"
            rows="5"
            class="w-full rounded-md border border-line bg-field px-2 py-1 font-mono text-[11px] outline-none focus:border-accent"
            placeholder='{"schema_version":1,"packages":{...}}'
          />
          <div class="mt-1 flex flex-wrap items-center gap-2">
            <input
              v-model="manifestName"
              class="w-32 rounded-md border border-line bg-field px-2 py-1 text-[11px] outline-none focus:border-accent"
              placeholder="源名称（可选）"
            />
            <button
              class="rounded-md bg-accent px-2.5 py-1 text-[11px] text-on-accent"
              @click="importManifest()"
            >
              导入为插件源
            </button>
            <span
              v-if="manifestMsg"
              class="text-[11px]"
              :class="manifestOk ? 'text-ok' : 'text-hot'"
            >
              {{ manifestMsg }}
            </span>
          </div>
        </div>
      </div>

      <!--
        空源引导：这是首次使用最容易卡住的地方，所以给可点击的一步，
        而不是只说"点添加"。不预置任何源（预置一个不存在的地址会报 404，
        看起来像应用坏了）。
      -->
      <div v-if="!sources.items.length" class="mb-2 rounded-lg border border-line bg-card px-3 py-2">
        <p class="text-[11px] font-medium text-ink-2">第一步：配置插件源</p>
        <p class="mt-1 text-[11px] leading-relaxed text-ink-3">
          插件源是一份 JSON 清单，列出可下载的插件（id、版本、下载地址、校验值）。
          应用本身不含任何插件，必须先从插件源拉到清单才能下载安装。
        </p>
        <div class="mt-1.5 flex flex-wrap items-center gap-2">
          <button
            class="rounded-md bg-accent px-2.5 py-1 text-[11px] font-medium text-on-accent hover:bg-accent-dim"
            @click="quickAddSource()"
          >
            填入本机测试源
          </button>
          <span class="font-mono text-[11px] text-ink-3">http://127.0.0.1:9000/packages.json</span>
          <span class="text-[11px] text-ink-3">或点「添加」填你自己的地址</span>
        </div>
        <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
          没有网络 / 没有服务器也能测：点「粘贴 JSON」把清单内容贴进来，
          再点「导入本地包」选 zip 安装。
        </p>
      </div>
      <p v-else-if="indexError" class="mb-2 text-[11px] text-hot">
        拉取插件列表失败：{{ indexError }}
      </p>

      <!-- 下载/安装进度 -->
      <div v-if="progress" class="mb-2 rounded-lg bg-card px-3 py-2">
        <div class="flex items-center gap-2 text-[11px]">
          <span class="text-ink-2">{{ progress.message }}</span>
          <span v-if="progress.total > 0" class="text-ink-3">
            {{ fmtSize(progress.received) }} / {{ fmtSize(progress.total) }}
          </span>
          <span class="flex-1" />
          <button class="text-ink-3 hover:text-ink" @click="cancelDownload()">取消</button>
        </div>
        <div class="mt-1.5 h-1 overflow-hidden rounded-full bg-card-hover">
          <div
            class="h-full bg-accent transition-all"
            :style="{ width: `${Math.max(2, Math.min(100, progress.percent))}%` }"
          />
        </div>
      </div>

      <!-- 未安装任何插件：区分「还没配源」和「源已就绪只等下载」两种处境 -->
      <div v-if="!plugins.length && !availablePlugins.length" class="text-[11px] text-ink-3">
        <template v-if="!sources.items.length">
          第二步：上方配好插件源后，这里会出现可下载的插件。
        </template>
        <template v-else-if="indexLoading">正在读取插件清单…</template>
        <template v-else-if="indexError">
          清单拉取失败，可用「导入本地包」离线安装。
        </template>
        <template v-else>
          第二步：点下方插件的「下载」按钮安装，装完会自动启动。
        </template>
      </div>

      <div
        v-for="p in sortedPlugins"
        :key="p.id"
        class="flex flex-wrap items-center gap-2 border-b border-line py-1.5 last:border-0"
      >
        <button
          class="relative h-4 w-7 shrink-0 rounded-full transition"
          :class="p.enabled ? 'bg-accent' : 'bg-card-hover'"
          :disabled="!p.installed || busy === p.id || !!progress || p.tool"
          :title="
            p.tool
              ? '工具型插件由依赖它的插件自动使用'
              : p.installed
                ? p.enabled
                  ? '随应用启动：开（点击关闭）'
                  : '随应用启动：关（点击开启）'
                : '未安装'
          "
          @click="toggleEnabled(p)"
        >
          <span
            class="absolute top-0.5 h-3 w-3 rounded-full bg-surface ring-1 ring-black/10 transition-all"
            :class="p.enabled ? 'left-3.5' : 'left-0.5'"
          />
        </button>

        <span class="w-20 shrink-0 text-xs">{{ pluginLabel(p) }}</span>
        <span
          v-if="p.tool"
          class="rounded-full bg-card px-2 py-0.5 text-[11px] text-ink-3"
          title="工具型插件：只提供文件，不作为进程运行"
        >
          已就绪
        </span>
        <span v-else class="rounded-full px-2 py-0.5 text-[11px]" :class="phaseClass(p.phase)">
          {{ phaseText(p.phase) }}
        </span>
        <!-- 运行时职责标识（2026-11-27）：drpy 跑 .js、pyrt 跑 .py 并转发 .js。
             以前这块信息在列表上方另开一段显示，现在贴在状态胶囊右侧，一行看完。 -->
        <span
          v-if="runtimeBadge(p.id)"
          class="rounded px-1.5 py-0.5 text-[10px] font-medium"
          :class="p.phase === 'ready' || p.tool ? 'bg-ok/15 text-ok' : 'bg-card text-ink-3'"
          :title="
            runtimeBadge(p.id) === 'JS'
              ? '执行 .js（drpy 方言）源'
              : '执行 .py 源，并把 .js 转发给 drpy'
          "
        >
          {{ runtimeBadge(p.id) }}
        </span>
        <span class="font-mono text-[11px] text-ink-3">
          {{ p.phase === 'ready' && p.port ? `127.0.0.1:${p.port}` : '—' }}
        </span>
        <span class="text-[11px] text-ink-3">v{{ p.version || '?' }}</span>
        <span
          v-if="updateFor(p.id)"
          class="rounded bg-accent/20 px-1.5 py-0.5 text-[10px] text-accent"
        >
          可升级 v{{ updateFor(p.id)!.version }}
        </span>
        <span v-if="p.error && p.installed" class="text-[11px] text-hot">{{ p.error }}</span>
        <span class="flex-1" />

        <button
          v-if="updateFor(p.id)"
          class="rounded-md bg-accent px-2.5 py-1 text-[11px] text-on-accent disabled:opacity-40"
          :disabled="!!progress"
          @click="install(p.id)"
        >
          升级 {{ fmtSize(updateFor(p.id)!.size) }}
        </button>
        <!-- 工具型插件（如 node 运行时）不跑进程，只提供文件，所以没有启停按钮 -->
        <template v-if="!p.tool">
          <button
            v-if="p.phase === 'ready'"
            class="set-btn"
            :disabled="busy === p.id || !!progress"
            @click="stopPlugin(p)"
          >
            停止
          </button>
          <button
            v-else
            class="set-btn"
            :disabled="!p.installed || busy === p.id || !!progress"
            @click="startPlugin(p)"
          >
            {{ busy === p.id ? '启动中…' : '启动' }}
          </button>
        </template>
        <button
          class="rounded-md bg-card px-2 py-1 text-[11px] text-ink-3 hover:bg-hot/20 hover:text-hot disabled:opacity-40"
          :disabled="busy === p.id || !!progress"
          @click="removePlugin(p.id)"
        >
          卸载
        </button>
      </div>

      <!-- 可下载 -->
      <div
        v-for="e in availablePlugins"
        :key="e.id"
        class="flex flex-wrap items-center gap-2 border-b border-line py-1.5 last:border-0"
      >
        <span class="h-4 w-7 shrink-0" />
        <span class="w-20 shrink-0 text-xs text-ink-2">{{ e.label }}</span>
        <span class="rounded-full bg-card px-2 py-0.5 text-[11px] text-ink-3">未安装</span>
        <span class="text-[11px] text-ink-3">v{{ e.version }}</span>
        <span class="text-[11px] text-ink-3">{{ fmtSize(e.size) }}</span>
        <span v-if="e.desc" class="text-[11px] text-ink-3">{{ e.desc }}</span>
        <span class="flex-1" />
        <button
          class="rounded-md bg-accent px-3 py-1 text-[11px] font-medium text-on-accent disabled:opacity-40"
          :disabled="!!progress"
          @click="install(e.id)"
        >
          下载 {{ fmtSize(e.size) }}
        </button>
      </div>

      <p class="mt-2 text-[11px] text-ink-3">
        后端插件关掉后，可改用上方「后端地址」连远端服务。
        弹幕插件关掉时会自动降级为官方源（爱奇艺直连），播放不受影响。
        插件源可改成自建镜像地址。
      </p>
    
      </template>
      </section>

    <!-- ══════════ 本机执行（仅 TV）══════════ -->
    <section v-if="tvMode" data-sec="localexec" class="set-card shrink-0">
      <template v-if="visitedSec('localexec')">
      <div class="set-head">
        <h2 class="set-title">本机执行</h2>
        <span class="set-hint">TV 本机网关 · 不依赖后端</span>
        <span class="set-actions flex items-center text-[11px] text-ink-3">{{ localSites }} 个本机站点</span>
      </div>
      <!-- 未启用：只露一个动作，配置项收进折叠（避免和「搜索与源」的地址框混淆） -->
      <template v-if="!localGw">
        <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
          电视可脱离后端，直接运行配置里的 jar。点按钮一次完成：启动本机网关并切换后端地址。
        </p>
        <p class="mt-1 text-[11px] leading-relaxed text-ink-3">
          站点浏览、搜索（含聚合搜索）、详情、播放、弹幕、直播、片库与缓存都走本机；需要二次解析的源（parse=1）暂不支持。
        </p>
        <div class="mt-2 flex flex-wrap items-center gap-2">
          <button
            class="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-on-accent hover:bg-accent-dim disabled:opacity-40"
            :disabled="localBusy"
            @click="startLocalExec()"
          >
            {{ localBusy ? '启动中…' : '启用本机执行' }}
          </button>
          <button
            class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover"
            @click="refreshLocalExec()"
          >
            刷新状态
          </button>
        </div>
        <details class="mt-3">
          <summary class="cursor-pointer select-none text-[11px] text-ink-3">
            可选：改用自定义配置导入（会启动网关并切后端）
          </summary>
          <div class="mt-2 flex flex-wrap items-center gap-2">
            <span class="w-20 shrink-0 text-xs text-ink-3">配置地址</span>
            <input
              v-model="cfgUrl"
              class="min-w-[16rem] max-sm:min-w-[10rem] flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 text-xs outline-none transition focus:border-accent"
              placeholder="https://example.com/config.json"
              @input="onCfgUrlInput"
            />
            <button
              class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover disabled:opacity-40"
              :disabled="localBusy"
              @click="importLocalConfig()"
            >
              导入到本机
            </button>
          </div>
          <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
            只导入 <code class="rounded bg-field px-1">csp_</code> + jar 的站点；drpy / 直连 http 走后端。
            jar 需为 dex（含 <code class="rounded bg-field px-1">classes.dex</code>）。
          </p>
          <p class="mt-1 text-[11px] leading-relaxed text-ink-3">
            可填自建后端 <code class="rounded bg-field px-1">http://&lt;后端&gt;/config.json</code>，
            或肥猫 <code class="rounded bg-field px-1">http://xn--z7x900a.net/tv</code>；jar 地址跟随配置。
          </p>
        </details>
      </template>

      <!-- 已启用：网关状态 + 配置项常显 -->
      <template v-else>
        <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
          本机网关运行中：<code class="rounded bg-field px-1">{{ localGw }}</code>。
          站点浏览、搜索（含聚合搜索）、详情、播放、弹幕、直播、片库与缓存都走本机；需要二次解析的源（parse=1）暂不支持。
        </p>
        <div class="mt-2 flex flex-wrap items-center gap-2">
          <button
            class="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-on-accent hover:bg-accent-dim disabled:opacity-40"
            :disabled="localBusy"
            @click="startLocalExec()"
          >
            重启本机网关
          </button>
          <button
            class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover"
            @click="stopLocalExec()"
          >
            停用
          </button>
          <button
            class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover"
            @click="refreshLocalExec()"
          >
            刷新状态
          </button>
        </div>
        <div class="mt-3 flex flex-wrap items-center gap-2">
          <span class="w-20 shrink-0 text-xs text-ink-3">配置地址</span>
          <input
            v-model="cfgUrl"
            class="min-w-[16rem] max-sm:min-w-[10rem] flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 text-xs outline-none transition focus:border-accent"
            placeholder="https://example.com/config.json"
            @input="onCfgUrlInput"
          />
          <button
            class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover disabled:opacity-40"
            :disabled="localBusy"
            @click="importLocalConfig()"
          >
            导入到本机
          </button>
        </div>
        <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
          只导入 <code class="rounded bg-field px-1">csp_</code> + jar 的站点；drpy / 直连 http 走后端。
          jar 需为 dex（含 <code class="rounded bg-field px-1">classes.dex</code>）。
        </p>
      </template>

      <p v-if="localMsg" class="mt-2 text-[11px] text-accent">{{ localMsg }}</p>
    
      </template>
      </section>

    <!-- ══════════ 运行时（TV 模块 / fpk 依赖 / iOS 端内 / PC 插件在「本机插件」）══════════ -->
    <section v-if="showRuntimeNav" data-sec="runtime" data-runtime-module class="set-card shrink-0">
      <template v-if="visitedSec('runtime')">
      <div class="set-head">
        <h2 class="set-title">运行时</h2>
        <span class="set-hint">{{ runtimeHint }}</span>
        <span
          v-if="tvMode && tvModule"
          class="set-actions flex items-center text-[11px] text-ink-3"
        >
          {{
            modState.installed
              ? modState.enabled
                ? modState.running
                  ? '运行中'
                  : '已启用'
                : '已关闭'
              : '未安装'
          }}
        </span>
      </div>

      <!-- TV 的模块安装/启停/卸载（其它端没有这层） -->
      <template v-if="tvMode && tvModule">
      <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
        模块约 60MB，按 CPU 架构单独安装；下载源与令牌沿用「更新」设置。
      </p>
      <div class="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1.5">
        <label class="flex items-center gap-2 text-xs text-ink-3">
          <span>启用</span>
          <ToggleSwitch
            :model-value="modState.enabled"
            :disabled="!modState.installed || modBusy"
            label="启用运行时模块"
            @update:model-value="modToggle"
          />
        </label>
        <div class="text-[11px] text-ink-3">
          <span v-if="modState.installed" class="whitespace-nowrap">
            {{ modState.abi }} · 版本 {{ modState.version || '-' }} · 端口 {{ modState.port }}
          </span>
          <span v-else>本机架构 {{ modState.abi }}</span>
        </div>
      </div>
      <p v-if="modState.error" class="mt-1.5 text-[11px] text-ink-3">
        最近错误：{{ modState.error }}
      </p>
      <div class="mt-3 flex flex-wrap items-center gap-2">
        <button
          class="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-on-accent hover:bg-accent-dim"
          :disabled="modBusy"
          @click="modInstall()"
        >
          {{ modState.installed ? '检查更新' : '下载并安装' }}
        </button>
        <button
          v-if="modState.installed"
          class="rounded-md bg-card px-3 py-1.5 text-xs hover:bg-card-hover"
          :disabled="modBusy"
          @click="modRestart()"
        >
          重启
        </button>
        <button
          v-if="modState.installed"
          class="rounded-md bg-card px-3 py-1.5 text-xs hover:bg-card-hover"
          :disabled="modBusy"
          @click="modUninstall()"
        >
          卸载
        </button>
        <span v-if="modMsg" class="text-[11px] text-accent">{{ modMsg }}</span>
      </div>
      <p class="mt-2 text-[11px] leading-relaxed text-ink-3">
        关闭后弹幕改用内置聚合站（境外源可能不可用），播放不受影响。
      </p>

      <!-- Python 运行时（.py 源用）：独立 APK，与 node 模块互不影响 -->
      <div class="mt-3 border-t border-line pt-3" data-py-runtime>
        <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
          <span class="text-xs font-medium text-ink-2">Python 运行时</span>
          <span class="text-[11px] text-ink-3">
            {{
              pyModState.installed
                ? pyModState.running
                  ? `运行中 · 端口 ${pyModState.port}`
                  : `已安装 v${pyModState.version || '-'}`
                : '未安装'
            }}
          </span>
        </div>
        <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
          约 20MB。装它之前 <code>.py</code> 源会提示「需要 Python 运行时」；
          装好后本机网关会把它接管为脚本运行时（同时继续服务 <code>.js</code>）。
          仅 arm64 / armeabi-v7a 设备可用。
        </p>
        <p v-if="pyModState.error" class="mt-1.5 text-[11px] text-ink-3">
          最近错误：{{ pyModState.error }}
        </p>
        <div class="mt-2 flex flex-wrap items-center gap-2">
          <button
            class="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-on-accent hover:bg-accent-dim"
            :disabled="pyModBusy"
            @click="pyModInstall()"
          >
            {{ pyModState.installed ? '检查更新' : '下载并安装' }}
          </button>
          <span v-if="pyModMsg" class="text-[11px] text-accent">{{ pyModMsg }}</span>
        </div>
      </div>

      <!-- 弹幕载荷（danmu_api）：与运行时模块分开装、分开升版本 -->
      <div class="mt-3 border-t border-line pt-3" data-danmu-payload>
        <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
          <span class="text-xs font-medium text-ink-2">弹幕组件</span>
          <span class="text-[11px] text-ink-3">
            <template v-if="!modState.installed">需先安装运行时模块</template>
            <template v-else-if="payloadInstalled">v{{ payloadCurrent }}</template>
            <template v-else>未安装</template>
          </span>
          <span v-if="payloadInstalled && payloadLatest" class="text-[11px] text-accent">
            发现 v{{ payloadLatest.version }}
          </span>
        </div>
        <p class="mt-1 text-[11px] leading-relaxed text-ink-3">
          danmu_api 的业务载荷，约 10MB；与运行时模块分开更新，换载荷不用重装模块。
        </p>
        <div class="mt-2 flex flex-wrap items-center gap-2">
          <button
            class="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-on-accent hover:bg-accent-dim disabled:opacity-40"
            :disabled="!modState.installed || payBusy"
            @click="payloadInstall()"
          >
            {{ payloadLatest ? '更新' : payloadInstalled ? '检查更新' : '下载并安装' }}
          </button>
          <span v-if="payMsg" class="text-[11px] text-accent">{{ payMsg }}</span>
          <span v-else-if="!modState.installed" class="text-[11px] text-ink-3">
            模块装好后即可安装
          </span>
        </div>
      </div>
      </template><!-- /TV 模块安装 -->

      <!--
        各端运行时状态（同一组件按壳形态切换）：
          iOS → 端内 drpy + CPython（policy.pyRuntime 给启动结果）
          TV  → Python 模块是否就绪
          fpk → java/node/python 三个可执行文件
       -->
      <div :class="tvMode && tvModule ? 'mt-3 border-t border-line pt-3' : ''">
        <RuntimeDepsPanel :plugins="plugins" @goto="gotoSelector" />
      </div>

      </template>
      </section>

    <!-- ══════════ 桌面端行为（仅 PC）══════════ -->
    <section v-if="desktop" data-sec="desktop" class="set-card shrink-0">
      <template v-if="visitedSec('desktop')">
      <div class="set-head">
        <h2 class="set-title">桌面端行为</h2>
        <span class="set-hint">Windows 桌面版 · 托盘与退出</span>
      </div>
      <div class="set-grid set-grid-narrow">
        <TileSelect
          :model-value="prefs.closeToTray ? 'on' : 'off'"
          label="关闭窗口时最小化到托盘"
          :options="onOff"
          @update:model-value="(v) => update('closeToTray', v === 'on')"
        />
      </div>
      <div class="mt-2 flex flex-wrap items-center gap-2">
        <button
          class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover"
          @click="resetTrayHint"
        >
          再次显示关闭提示
        </button>
        <span class="text-[11px] text-ink-3">只清除「已提示」标记，不改关闭行为</span>
      </div>
      <p v-if="trayHintMsg" class="mt-1.5 set-hint">{{ trayHintMsg }}</p>
      <p class="mt-1.5 set-hint">
        默认开：点窗口 X 只是收进系统托盘，后端与播放不会中断；想彻底退出请用托盘右键菜单。
        关掉后点 X 会直接退出应用。
      </p>
    
      </template>
      </section>

    <!-- ══════════ 搜索与源 ══════════
      ★ 这一区在 2026-10-30 重构时合并了三处：
        · 「网络」区的配置源 + JS 运行时（它们与 DNS 无关，决定"有哪些源、怎么执行"）
        · 「浏览与搜索」区的列数/搜索线程/缓存三档
        · 「数据与维护」区的重置源健康 + 清空搜索缓存
      合并依据是用户问题：「某部剧搜不到」原来要找三个区，现在都在这里。
    -->
    <section data-sec="source" class="set-card shrink-0">
      <template v-if="visitedSec('source')">
      <div class="set-head">
        <h2 class="set-title">搜索与源</h2>
        <span class="set-hint">站点源 · 站点管理 · 搜索怎么跑</span>
      </div>

      <!-- ── 源配置 ── -->
      <div class="set-sub">
        <span class="font-medium text-ink-2">配置源</span>
        <span>导入别人维护的配置，一次拿到几百个站</span>
      </div>
      <div class="flex flex-wrap items-center gap-2.5">
        <span class="w-16 shrink-0 text-xs text-ink-3">配置地址</span>
        <input
          v-model="cfgUrl"
          placeholder="https://example.com/config.json"
          class="min-w-[16rem] max-sm:min-w-[10rem] flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs outline-none transition focus:border-accent"
          title="CatVod 形状的 JSON 配置地址；也接受多仓（含 urls 数组）"
          @input="onCfgUrlInput"
          @keyup.enter="doPreviewImport"
        />
        <button
          class="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-on-accent transition hover:bg-accent-dim disabled:opacity-40"
          :disabled="cfgBusy"
          @click="doPreviewImport"
        >
          {{ cfgBusy ? '读取中…' : '读取' }}
        </button>
      </div>

      <div class="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-ink-3">
        <span>GitHub 地址会自动尝试「网络与加速 → GitHub 加速」里的代理；其它站点直连。</span>
        <!--
          多仓索引（含 urls 数组的地址）直接点「读取」只会取第一个仓。
          探测要把每个仓都拉一遍才知道"导入后真正能用几站"，是个慢动作，
          所以降到次级入口当链接用，不和主按钮抢位（用户多数时候不需要它）。
        -->
        <button
          class="rounded px-1 py-0.5 underline decoration-dotted underline-offset-2 transition hover:bg-card-hover hover:text-ink disabled:opacity-40"
          :disabled="depotBusy"
          title="地址里有多个仓时，先逐个探活，看每个仓能用几站"
          @click="probeDepot"
        >
          {{ depotBusy ? '探测中…' : depotEntries.length ? '刷新探测' : '多仓探测' }}
        </button>
      </div>

      <p v-if="depotMsg" class="mt-1.5 text-[11px] text-ink-3">{{ depotMsg }}</p>

      <!--
        已保存的多仓列表（后端 config.depot.json）：重启后仍在这里，
        不用重新粘贴索引地址。「刷新探测」只刷新数据，不动配置。
      -->
      <div
        v-if="depotEntries.length && depotIndex"
        class="mt-2 rounded-md bg-field/50 px-2.5 py-1.5 text-[11px] text-ink-3"
      >
        <div class="flex flex-wrap items-center gap-x-2 gap-y-0.5">
          <span class="shrink-0">多仓索引</span>
          <span class="min-w-0 flex-1 truncate font-mono opacity-80" :title="depotIndex">
            {{ depotIndex }}
          </span>
          <span v-if="depotAgo" class="shrink-0">探测于 {{ depotAgo }}</span>
        </div>
        <div class="mt-0.5">
          当前仓：
          <span class="text-ink-2">
            {{ depotEntries.find((x) => x.url === depotActive)?.name || (depotActive || '未记录') }}
          </span>
          <span v-if="depotStale" class="ml-1 text-warn">列表超过 24 小时，建议刷新</span>
        </div>
      </div>

      <!--
        预览卡片：按用户此刻的问题排成三段，而不是把所有信息平铺。

          ⓵ 读到什么   ← 事实，不依赖任何策略
          ⓶ 这次怎么并 ← 决定（追加/替换、同名、jar）
          ⓷ 这样并会   ← 上面决定的后果，随选择实时变

        顺序不能倒：原来的「导入方式」摆在读取按钮上方，等于让用户在看结果之前
        先承诺怎么并。现在策略只在读完之后出现（见 cfgDiff：四套 diff 后端一次算齐，
        切换不重新联网）。
      -->
      <div
        v-if="cfgPreview"
        data-cfg-preview
        class="mt-2 rounded-lg border border-accent/30 bg-accent/5 p-3"
      >
        <!-- ⓵ 读到什么 -->
        <div class="flex flex-wrap items-baseline gap-x-3 gap-y-1 text-xs">
          <span class="font-medium text-ink">读到</span>
          <span class="text-ok">{{ cfgPreview.sites }} 个可用站</span>
          <span class="text-ink-3">本地已有 {{ cfgPreview.existing }} 个</span>
          <span v-if="cfgPreview.builtin" class="text-ink-3">
            内置 {{ cfgPreview.builtin }} 个（不在配置里，导入删不掉）
          </span>
        </div>
        <p class="mt-0.5 break-all text-[11px] text-ink-3">{{ cfgPreview.url }}</p>

        <!-- ⓶ 这次怎么并 -->
        <div class="mt-2 border-t border-line pt-2">
          <!-- 默认追加。替换是破坏性动作，必须用户自己选 -->
          <div class="flex flex-wrap items-center gap-2.5 text-xs">
            <span class="w-16 shrink-0 text-ink-3">导入方式</span>
            <div class="seg-group flex rounded-md border border-line">
              <button
                class="px-3 py-1 transition"
                :class="importMode === 'merge' ? 'bg-accent text-on-accent' : 'bg-field text-ink-2 hover:bg-card-hover'"
                @click="importMode = 'merge'"
              >
                追加（推荐）
              </button>
              <button
                class="px-3 py-1 transition"
                :class="importMode === 'replace' ? 'bg-warn text-on-warn' : 'bg-field text-ink-2 hover:bg-card-hover'"
                @click="importMode = 'replace'"
              >
                替换
              </button>
            </div>
            <span class="text-[11px] text-ink-3">
              {{
                importMode === 'merge'
                  ? '保留现有站点，只把新的并进来'
                  : '整表换掉，现有站点会全部消失（可从备份撤销一次）'
              }}
            </span>
          </div>
          <div
            v-if="importMode === 'merge'"
            class="mt-2 flex flex-wrap items-center gap-2.5 text-xs"
          >
            <span class="w-16 shrink-0 text-ink-3">同名站点</span>
            <div class="seg-group flex rounded-md border border-line">
              <button
                v-for="o in conflictOptions"
                :key="o.value"
                class="px-3 py-1 transition"
                :class="
                  importConflict === o.value
                    ? 'bg-accent text-on-accent'
                    : 'bg-field text-ink-2 hover:bg-card-hover'
                "
                :title="o.hint"
                @click="importConflict = o.value as ImportConflict"
              >
                {{ o.label }}
              </button>
            </div>
            <span class="text-[11px] text-ink-3">同一个 key 但 api 不同时怎么处理</span>
          </div>
          <!-- 源包迁移：老配置的顶层 spider 常指向已失效的上游 dex 包 -->
          <div class="mt-2 flex flex-wrap items-center gap-2.5 text-xs">
            <span class="w-16 shrink-0 text-ink-3">jar 地址</span>
            <label class="flex items-center gap-2 text-ink-2">
              <input
                v-model="cfgReplaceSpider"
                type="checkbox"
                class="h-3.5 w-3.5 accent-[var(--color-accent)]"
              />
              <span>用这份配置的顶层 spider 覆盖本地</span>
            </label>
            <span class="text-[11px] text-ink-3">
              从全量版迁移/装源包时勾选；平时不要勾，会冲掉你手改的 jar 地址
            </span>
          </div>
        </div>

        <!-- ⓷ 这样并会 -->
        <div class="mt-2 border-t border-line pt-2">
          <div class="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
            <span class="font-medium text-ink">这样并会</span>
            <span class="text-ok">新增 {{ cfgDiff.toAdd.length }}</span>
            <span class="text-ink-2">更新 {{ cfgDiff.toUpdate.length }}</span>
            <span v-if="cfgDiff.conflicts.length" class="text-warn">
              同名冲突 {{ cfgDiff.conflicts.length }}
            </span>
            <span class="text-ink-3">不变 {{ cfgDiff.unchanged }}</span>
            <span v-if="cfgDiff.removed.length" class="text-hot">
              会消失 {{ cfgDiff.removed.length }}
            </span>
          </div>
          <p class="mt-1 text-[11px] leading-relaxed text-ink-3">
            写入后站点表共 {{ cfgDiff.total }} 条。
          </p>
          <!-- 长 key / 长 url 必须 break-all：站点 key 动辄 drpy_js_xxx，会把卡片撑出容器 -->
          <p
            v-if="cfgDiff.removed.length"
            class="mt-1 break-all text-[11px] leading-relaxed text-hot"
          >
            会消失 {{ cfgDiff.removed.length }} 个：
            {{ cfgDiff.removed.slice(0, 6).join('、') }}
            <template v-if="cfgDiff.removed.length > 6">…</template>
          </p>
          <p
            v-if="cfgDiff.healthLost.length"
            class="mt-1 break-all text-[11px] leading-relaxed text-warn"
          >
            停用/健康状态会失效 {{ cfgDiff.healthLost.length }} 个：
            {{ cfgDiff.healthLost.slice(0, 6).join('、') }}
            <template v-if="cfgDiff.healthLost.length > 6">…</template>
          </p>
          <p
            v-if="cfgDiff.conflicts.length"
            class="mt-1 break-all text-[11px] leading-relaxed text-ink-3"
          >
            同名冲突 {{ cfgDiff.conflicts.length }} 个，例：{{ cfgDiff.conflicts[0].key }}
            （现有 {{ cfgDiff.conflicts[0].existing.api }} / 导入
            {{ cfgDiff.conflicts[0].incoming.api }}）
          </p>
          <!--
            只有旧后端才可能出现：它只回读取时那一份 diff，用户切了策略后数字
            就对不上了。这时宁可要求重新读取，也不能让「看着 A 的数字执行 B」。
          -->
          <p v-if="cfgPlanStale" class="mt-1.5 text-[11px] leading-relaxed text-hot">
            后端版本较旧，切换策略后数字没有重算。请再点一次「读取」。
          </p>
        </div>

        <div class="mt-2 flex flex-wrap items-center gap-2 border-t border-line pt-2">
          <button
            class="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-on-accent transition hover:bg-accent-dim disabled:opacity-40"
            :disabled="cfgBusy || cfgPlanStale"
            @click="doImportConfig"
          >
            {{ cfgBusy ? '写入中…' : '确认导入' }}
            <span v-if="!cfgBusy" class="opacity-75">（{{ cfgApplyLabel }}）</span>
          </button>
          <button
            class="rounded-md bg-card px-3 py-1.5 text-xs text-ink-2 transition hover:bg-card-hover"
            @click="cfgPreview = null"
          >
            取消
          </button>
        </div>
      </div>

      <!-- 多仓候选：按"能用几站"排序，可用的排前面 -->
      <div v-if="depotEntries.length" class="mt-2 space-y-1">
        <div
          v-for="e in depotEntries"
          :key="e.url"
          class="flex flex-wrap items-center gap-2 rounded-md px-2.5 py-1.5"
          :class="e.ok === false ? 'bg-field/50' : 'bg-field'"
        >
          <span
            class="min-w-0 flex-1 truncate text-xs"
            :class="e.ok === false ? 'text-ink-3' : 'text-ink'"
            :title="e.url"
          >
            {{ e.name }}
          </span>
          <!-- 当前生效的仓（导入成功时后端记录，重启后仍在） -->
          <span
            v-if="depotActive === e.url"
            class="shrink-0 rounded-full bg-accent/20 px-2 py-0.5 text-[10px] text-accent"
          >
            当前
          </span>
          <span
            v-if="e.ok"
            class="shrink-0 rounded-full px-2 py-0.5 text-[10px]"
            :class="
              e.jarKnown === false
                ? 'bg-field text-ink-3'
                : (e.inJar || 0) + (e.direct || 0) >= 5
                  ? 'bg-ok/15 text-ok'
                  : 'bg-warn/15 text-warn'
            "
            :title="
              e.jarKnown === false
                ? `总 ${e.sites} 站，其中带类的 ${e.csp} 个；本机没有内置 jar，无法判断能加载几站`
                : `总 ${e.sites} 站，其中带类的 ${e.csp} 个、我们 jar 里有 ${e.inJar} 个、直连 ${e.direct} 个`
            "
          >
            {{ e.hint }}
          </span>
          <span
            v-else-if="e.ok === false"
            class="shrink-0 text-[10px] text-ink-3"
          >{{ e.error || '不可用' }}</span>
          <!-- 缓存里"直接导入索引"生成的条目还没探测过：允许先切，数字等刷新探测 -->
          <span v-else class="shrink-0 text-[10px] text-ink-3">未探测</span>
          <button
            v-if="e.ok !== false && depotActive !== e.url"
            class="shrink-0 rounded-md bg-card-hover px-2.5 py-1 text-[11px] transition hover:bg-accent/20 hover:text-accent disabled:opacity-40"
            :disabled="cfgBusy"
            @click="importDepotEntry(e)"
          >
            切到这个仓
          </button>
        </div>
        <p class="text-[11px] leading-relaxed text-ink-3">
          只列出"结构上能用"的站数（我们的 jar 里有没有这个类），不代表今天一定搜得到。
          切仓会覆盖当前配置，旧配置会自动备份，可用下面的「恢复」撤回。
        </p>
      </div>
      <p
        v-if="cfgMsg"
        class="mt-1.5 text-[11px] leading-relaxed"
        :class="cfgMsgOk ? 'text-ok' : 'text-hot'"
      >
        {{ cfgMsg }}
      </p>
      <p v-else-if="cfgSummary" class="mt-1.5 text-[11px] text-ink-3">
        上次导入：{{ cfgSummary.sites }}/{{ cfgSummary.total }} 站
        <span v-if="cfgSummary.url" class="ml-1 break-all font-mono opacity-70">
          {{ cfgSummary.url }}
        </span>
      </p>
      <!--
        「导入成功但一个站都用不了」（issue #9）：后端给一句可行动的结论，
        不能只靠 sites/totalSites 两个数字让用户猜。
      -->
      <p
        v-if="cfgHint"
        class="mt-1.5 rounded-md bg-warn/10 px-2.5 py-1.5 text-[11px] leading-relaxed text-warn"
      >
        {{ cfgHint }}
      </p>
      <!-- 外部 jar 的状态（md5 不匹配 / 加密壳 / 下载失败，issue #14 / #10） -->
      <div v-if="cfgJars.length" class="mt-1.5 rounded-md bg-field px-2.5 py-1.5">
        <div class="text-[11px] text-ink-3">源 jar 状态</div>
        <ul class="mt-1 space-y-0.5">
          <li
            v-for="(j, i) in cfgJars.slice(0, 4)"
            :key="'jar-' + i"
            class="text-[11px] leading-relaxed"
            :class="j.ok && j.md5Ok && !j.encrypted ? 'text-ink-3' : 'text-warn'"
          >
            {{ j.name || j.spec }}
            <template v-if="j.encrypted">— 加密 Guard 壳：{{ j.encryptedReason || '本构建不支持' }}</template>
            <template v-else-if="!j.ok">— 未装载：{{ j.error || '下载/加载失败' }}</template>
            <template v-else-if="!j.md5Ok">— md5 不匹配（已按宽松模式挂载；policy.json 设 jarStrictMd5=true 可恢复严格）</template>
            <template v-else>— 已装载</template>
          </li>
        </ul>
      </div>
      <!-- 配置里有、但本构建加载不了的站点（加密壳 / jar 未装载 / 缺类） -->
      <div v-if="cfgPending.length" class="mt-1.5 rounded-md bg-field px-2.5 py-1.5">
        <div class="text-[11px] text-ink-3">
          未生效 {{ cfgPending.length }} 站（站点明细里可看全量）
        </div>
        <ul class="mt-1 space-y-0.5">
          <li
            v-for="(p, i) in cfgPending.slice(0, 5)"
            :key="'pend-' + i"
            class="truncate text-[11px] text-ink-2"
            :title="p.api + ' — ' + p.reason"
          >
            {{ p.name || p.key }} — {{ p.reason }}
          </li>
        </ul>
      </div>
      <div v-if="cfgSkipped.length" class="mt-1.5 rounded-md bg-field px-2.5 py-1.5">
        <div class="text-[11px] text-ink-3">跳过 {{ cfgSkipped.length }} 站（原因前 5 条）</div>
        <ul class="mt-1 space-y-0.5">
          <li
            v-for="(s, i) in cfgSkipped.slice(0, 5)"
            :key="i"
            class="truncate font-mono text-[11px] text-ink-2"
          >
            {{ s.key || s.api }} — {{ s.reason }}
          </li>
        </ul>
      </div>

      <!--
        导入前的配置快照：导入是覆盖式的，挑错仓就把现有的几百站冲掉了。
        有备份时这里必须可见，否则用户只能靠"再导入一次对的"来补救。
      -->
      <div
        v-if="cfgBackup.exists"
        class="mt-1.5 flex flex-wrap items-center gap-2 rounded-md bg-field px-2.5 py-1.5"
      >
        <span class="text-[11px] text-ink-2">
          导入前的配置已备份（{{ cfgBackup.sites ?? '?' }} 站）
        </span>
        <button
          class="rounded-md bg-card px-2.5 py-1 text-[11px] transition hover:bg-accent/20 hover:text-accent disabled:opacity-40"
          :disabled="restoreBusy"
          @click="restoreLastConfig"
        >
          {{ restoreBusy ? '恢复中…' : '恢复上一次配置' }}
        </button>
        <button
          class="rounded-md px-2 py-1 text-[11px] text-ink-3 transition hover:bg-card-hover hover:text-ink"
          @click="discardCfgBackup"
        >
          丢弃备份
        </button>
      </div>

      <!-- ── 站点明细与源健康 ── -->
      <div class="set-sub mt-3 border-t border-line pt-3">
        <span class="font-medium text-ink-2">站点明细与源健康</span>
        <span>看哪些源失效、停用它们，或让全部重新参与</span>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <!--
          data-tv-key：弹层关闭后把焦点还到这里。
          鼠标点开弹框时 tv-nav 记不到"是谁打开的"（不经过 enter()），
          关闭后只能靠这个选择器把焦点送回来（见 closeSites）。
        -->
        <button
          data-tv-key="settings-sites"
          class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover"
          @click="toggleSites"
        >
          {{ showSites ? '收起站点' : `站点明细（${store.sites.length}）` }}
        </button>
        <span class="text-[11px] text-ink-3">
          逐个看/停用源、批量处理失效源
        </span>
      </div>
      <div class="mt-2 flex flex-wrap items-center gap-2">
        <button
          class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover"
          @click="resetSourceHealth"
        >
          重置源健康
        </button>
        <span v-if="failedSkipCount" class="text-[11px] text-ink-3">
          {{ failedSkipCount }} 个已失效
        </span>
      </div>
      <p
        v-if="sourceHealthMsg"
        class="mt-1.5 text-[11px]"
        :class="sourceHealthMsg.startsWith('已') ? 'text-ok' : 'text-hot'"
      >
        {{ sourceHealthMsg }}
      </p>
      <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
        清掉跳过/降档标记，下次搜索全部源重新参与（更慢但更全）。
        「清空搜索缓存」在下面的「结果缓存」分组。
      </p>

      <!-- 运行时：drpy / pyrt 地址；广告过滤是可选的后端策略 -->
      <div class="set-sub mt-3">
        <span class="font-medium text-ink-2">运行时与过滤</span>
        <span>drpy / pyrt 地址（.js、.py 源都走它）；广告过滤是可选的后端策略</span>
      </div>
      <div data-jsruntime class="flex min-w-0 flex-wrap items-center gap-2.5">
        <span class="w-16 shrink-0 text-xs text-ink-3">JS 运行时</span>
        <input
          v-model="jsRuntime"
          placeholder="http://127.0.0.1:9330（留空 = 未装 drpy 插件）"
          class="min-w-[16rem] max-sm:min-w-[10rem] flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs outline-none transition focus:border-accent"
          title="drpy / pyrt 的地址；PC 插件启动时会自动写入，手动部署时填它的地址"
        />
        <button
          class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover disabled:opacity-40"
          :disabled="advBusy"
          @click="saveAdvanced"
        >
          保存
        </button>
      </div>
      <div class="set-grid mt-2">
        <!--
          ★ 必须自己保存（2026-10-30 审计）：原来只改本地 ref，
          落盘要靠同一行组里属于「JS 运行时」的那个「保存」按钮。
          用户拨完开关就走 = 白拨，且看不出为什么没生效。
        -->
        <TileSelect
          :model-value="filterAds ? 'on' : 'off'"
          label="广告分片过滤"
          :options="onOff"
          @update:model-value="(v) => { filterAds = v === 'on'; saveAdvanced() }"
        />
      </div>
      <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
        广告过滤按 <code class="rounded bg-field px-1">ads</code> 表丢弃 m3u8 里的分片；
        这项不是上游语义（上游只用于嗅探器防跳转），配置里若写了整条 CDN 会误杀，默认关闭。
      </p>
      <p v-if="advMsg" class="mt-1 text-[11px]" :class="advMsg.startsWith('已') ? 'text-ok' : 'text-hot'">
        {{ advMsg }}
      </p>

      <!-- 运行时状态已收口到独立「运行时」分区（运行时是执行环境，不是源）。
           这里只留「JS 运行时」地址本身，它决定 .js/.py 请求发到哪台机器。 -->

      <!-- ── 搜索行为 ──
           从原「浏览与搜索」区搬来。这些是"搜索怎么跑"的参数，
           与"有哪些源"是同一个问题的两面，所以同区。 -->
      <div class="set-sub">
        <span class="font-medium text-ink-2">搜索行为</span>
        <span>一次向多少个源发请求、结果怎么显示</span>
      </div>
      <div class="set-grid">
        <TileSelect
          :model-value="prefs.searchLayout"
          label="搜索展示"
          :options="layoutOptions"
          @update:model-value="(v) => update('searchLayout', v as Prefs['searchLayout'])"
        />
        <TileSelect
          :model-value="prefs.searchThreads"
          label="搜索线程"
          :options="threadOptions"
          @update:model-value="(v) => update('searchThreads', Number(v))"
        />
      </div>
      <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
        「搜索线程」是本机同时向多少个源发请求；源多、网快可以调大，反过来调小。
        结果按源站返回顺序边到边显示。
      </p>

      <!-- ── 噪音过滤 ──
           用户自行维护的词表，命中片名或备注就从聚合结果里剔除。

           ★ 为什么与 searchRank.ts 的 UGC_RE 并存：
             那套正则在前端**只降权**（"解说"排后面但仍可见），因为有人
             就是找解说合集；这里是**用户主动开启**的硬过滤。目的不同。

           ★ 为什么是"填词表"而不是"开关 + 固定词表"：
             该滤掉的词因剧而异（看国产剧要滤"解说"，看番要滤"AMV/MAD"），
             固定词表必然一边漏一边误伤。空表 = 不过滤，只有一个开关。 -->
      <div class="set-sub mt-3">
        <span class="font-medium text-ink-2">噪音过滤</span>
        <span>命中片名或备注就隐藏，避免搜剧时冒出解说/花絮</span>
      </div>
      <div class="rounded-md bg-field px-3 py-2.5">
        <!-- 模式放最前：它决定"填了词表之后到底发生什么"，
             先选力度再填词比反过来更符合直觉 -->
        <div class="set-grid mb-2">
          <TileSelect
            :model-value="noiseMode"
            label="命中后"
            :options="noiseModeOptions"
            @update:model-value="(v) => (noiseMode = v as 'filter' | 'rank')"
          />
        </div>
        <textarea
          v-model="noiseInput"
          rows="3"
          placeholder="留空 = 不过滤。例：解说, 混剪, 预告, 花絮, 影评, 片段, reaction"
          class="w-full resize-y rounded-md border border-line bg-card-sunken px-2.5 py-1.5 font-mono text-xs leading-relaxed text-ink outline-none transition placeholder:text-ink-3 focus:border-accent"
          title="逗号 / 分号 / 换行分隔，大小写不敏感；单字会被忽略（太容易误伤）"
          @keyup.ctrl.enter="saveNoise"
        />
        <div class="mt-2 flex flex-wrap items-center gap-2">
          <button
            class="rounded-md bg-card px-3 py-1 text-xs transition hover:bg-card-hover"
            @click="saveNoise"
          >
            保存
          </button>
          <button
            class="rounded-md bg-card px-3 py-1 text-xs transition hover:bg-card-hover"
            @click="fillNoisePreset"
          >
            填入推荐词
          </button>
          <button
            v-if="noiseInput.trim()"
            class="rounded-md px-2 py-1 text-xs text-ink-3 transition hover:bg-card-hover hover:text-hot"
            @click="clearNoise"
          >
            清空
          </button>
          <span v-if="noiseCount" class="text-[11px] text-ink-3">
            当前 {{ noiseCount }} 个词生效
          </span>
          <span v-if="noiseMsg" class="text-[11px] text-ok">{{ noiseMsg }}</span>
        </div>
        <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
          命中词不显示；「降级」只排到后面。逗号/换行分隔，不区分大小写，匹配片名与备注；
          单字忽略。仅影响聚合搜索。
        </p>
      </div>

      <!-- ── 结果缓存 ──
           写的是**后端** policy.json 的 searchCache*Sec，不是前端 prefs ——
           所以文案里点明「后端」，避免误以为只影响本机。
           用 TileSelect 而不是原生 select：本区其他项都是磁贴，
           混一个原生下拉会在高度、圆角、点击反馈上都不一致。 -->
      <div class="set-sub">
        <span class="font-medium text-ink-2">结果缓存</span>
        <span>同一关键词再次搜索时直接用这份，不等重跑</span>
      </div>
      <div class="set-grid">
        <TileSelect
          v-for="f in cacheTtlFields"
          :key="f.key"
          :model-value="policyCacheSec(f.key)"
          :label="f.label"
          :options="f.options"
          @update:model-value="(v) => onPolicyCacheChange(f.key, Number(v))"
        />
      </div>
      <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
        缓存时长改完立即生效，当前 {{ searchCacheStats?.size ?? 0 }} 条；
        「失败」别调大，否则修好的源会迟迟不回来。
      </p>
      <div class="mt-2 flex flex-wrap items-center gap-2">
        <button
          class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover"
          @click="clearSearchCacheNow"
        >
          清空搜索缓存
        </button>
        <span class="text-[11px] text-ink-3">立刻丢掉已缓存的结果，下次搜索重新跑</span>
      </div>
      <p
        v-if="searchCacheMsg"
        class="mt-1.5 text-[11px]"
        :class="searchCacheMsg.startsWith('已') ? 'text-ok' : 'text-hot'"
      >
        {{ searchCacheMsg }}
      </p>

    
      </template>
      </section>

    <!-- ══════════ 直播源 ══════════ -->
    <section data-sec="live" class="set-card shrink-0">
      <template v-if="visitedSec('live')">
      <div class="set-head">
        <h2 class="set-title">直播源</h2>
        <span class="set-hint">IPTV 播放列表 · 与站点配置互不影响</span>
      </div>
      <p class="text-[11px] leading-relaxed text-ink-3">
        直播只靠这里的播放列表。添加时会先拉一遍，解析不出频道就不保存。
      </p>
      <p class="mt-1 text-[11px] leading-relaxed text-ink-3">
        内置两条源：Guovin（836 频道）和 iptv-org 中文台（222 频道）。它们只是索引，
        源站不稳时可能打不开；可逐条删除换成自己的（删了不会自己回来）。
      </p>
      <!-- 多条源时的显示方式：合并（一个台一行、多线路）还是选一条用 -->
      <label class="mt-2 flex cursor-pointer items-center gap-1.5 text-xs text-ink-2">
        <input
          :checked="liveMerge"
          type="checkbox"
          class="accent-accent"
          @change="setLiveMerge(($event.target as HTMLInputElement).checked)"
        />
        多源合并显示（同一个台的线路合在一起，不重复占列表）
      </label>
      <div class="mt-2 space-y-1">
        <template v-for="l in liveSources" :key="l.url">
          <!-- 编辑态：改名 / 换地址（换地址时会先真拉一遍再保存） -->
          <div
            v-if="liveEditing === l.url"
            class="flex flex-wrap items-center gap-2 rounded-md bg-field px-2.5 py-1.5"
          >
            <input
              v-model="liveEditName"
              placeholder="名称（可留空）"
              class="min-w-[8rem] flex-1 rounded border border-line bg-card px-2 py-1 text-xs text-ink outline-none focus:border-accent"
            />
            <input
              v-model="liveEditUrl"
              placeholder="https://example.com/live.m3u"
              class="min-w-[16rem] flex-[2] rounded border border-line bg-card px-2 py-1 font-mono text-xs text-ink outline-none focus:border-accent"
            />
            <button
              class="set-btn set-btn--primary shrink-0"
              :disabled="!!liveBusy"
              @click="saveEditLive(l.url)"
            >
              {{ liveBusy === l.url ? '保存中…' : '保存' }}
            </button>
            <button
              class="set-btn set-btn--ghost shrink-0"
              @click="cancelEditLive"
            >
              取消
            </button>
          </div>
          <!-- 展示态 -->
          <div
            v-else
            class="flex flex-wrap items-center gap-2 rounded-md bg-field px-2.5 py-1.5"
          >
            <span class="min-w-0 flex-1 truncate text-xs text-ink">{{ l.name }}</span>
            <span
              v-if="liveBuiltin.includes(l.url)"
              class="set-tag shrink-0"
            >
              内置
            </span>
            <span class="max-w-[36%] truncate font-mono text-[11px] text-ink-3">{{ l.url }}</span>
            <!-- 测试：真拉一遍，报台数 + 更新于几天前（不在打开页面时自动拉，208KB 要 6 秒） -->
            <button
              class="set-btn set-btn--ghost shrink-0"
              :disabled="!!liveBusy || !!liveTestBusy"
              @click="testLive(l.url)"
            >
              {{ liveTestBusy === l.url ? '测试中…' : '测试' }}
            </button>
            <button
              class="set-btn set-btn--ghost shrink-0"
              :disabled="!!liveBusy"
              @click="startEditLive(l)"
            >
              编辑
            </button>
            <button
              class="set-btn set-btn--ghost set-btn--danger shrink-0"
              :disabled="!!liveBusy"
              @click="doRemoveLive(l.url)"
            >
              {{ liveBusy === l.url ? '删除中…' : '删除' }}
            </button>
          </div>
          <!-- 测试结果：可用时带「更新于 X 天前」，超 7 天提示已停更 -->
          <p
            v-if="liveTestMsg[l.url]"
            class="px-1 text-[11px]"
            :class="/^可用/.test(liveTestMsg[l.url]) && !/已停更/.test(liveTestMsg[l.url]) ? 'text-ok' : 'text-warn'"
          >
            {{ liveTestMsg[l.url] }}
          </p>
        </template>
        <p v-if="!liveSources.length && liveErr" class="text-[11px] text-warn">
          直播源读取失败（{{ liveErr }}）——后端可能还没起来。
          <button class="ml-1 underline" @click="refreshLives">重试</button>
        </p>
        <p v-else-if="!liveSources.length" class="text-[11px] text-ink-3">还没有配置直播源。</p>
        <!-- 删错内置源时的撤回入口：不用再去手输那些 CDN 地址 -->
        <button
          v-if="canResetBuiltin && liveSources.length"
          class="set-btn mt-1"
          :disabled="!!liveBusy"
          @click="doResetBuiltin"
        >
          {{ liveBusy === '__reset__' ? '恢复中…' : '恢复内置源' }}
        </button>
      </div>
      <div class="mt-2 flex flex-wrap items-center gap-2.5">
        <input
          v-model="liveName"
          placeholder="名字（可留空）"
          class="w-36 shrink-0 rounded-md border border-line bg-field px-2.5 py-1.5 text-xs outline-none transition focus:border-accent"
        />
        <input
          v-model="liveUrlInput"
          placeholder="https://example.com/live.m3u"
          class="min-w-[16rem] max-sm:min-w-[10rem] flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs outline-none transition focus:border-accent"
          title="支持 m3u 与「频道名,地址」形状的 txt；分组用 #genre# 或 group-title"
          @keyup.enter="doAddLive"
        />
        <button
          class="set-btn set-btn--primary"
          :disabled="liveBusy === 'add'"
          @click="doAddLive"
        >
          {{ liveBusy === 'add' ? '校验中…' : '添加' }}
        </button>
      </div>
      <!-- iptv-org 国家快捷添加：国家码不好记（英国是 uk 不是 gb），点一下就加 -->
      <!--
        ★ data-tv-col 不能去掉：tv-nav 的上下键有"只在同一纵向滚动区里找"
        的隔离（见 tv-nav.js 的 regionMode）。展开区的网格在自己的
        `overflow-y-auto` 容器里，是一个**不同的滚动区** → 从「更多国家」
        按 ↓ 会直接把网格整块跳过、掉到下面的「保存」上（2026-11-11 实测）。
        标成同一列后，上下键走列内几何选焦，能正常进网格、逐行上下走；
        走到最后一行再按 ↓ 会按"列外"规则离开这块区域。
      -->
      <div class="mt-2 flex flex-wrap items-center gap-1.5" data-tv-col="live-country">
        <span class="text-[11px] text-ink-3">快捷添加 iptv-org：</span>
        <button
          v-for="c in LIVE_COUNTRY_PRESETS"
          :key="c.code"
          class="set-chip"
          :disabled="!!liveBusy || hasCountryPreset(c.code)"
          :title="`${countryPresetUrl(c.code)}（实测 ${c.ch} 个台）`"
          @click="addCountryLive(c)"
        >
          {{ liveBusy === countryPresetUrl(c.code) ? '校验中…' : c.label }}
        </button>
        <button
          class="set-btn set-btn--ghost"
          @click="toggleAllCountries"
        >
          {{ showAllCountries ? '收起' : '更多国家/地区 ▾' }}
        </button>
      </div>
      <!-- 全部 250 个国家/地区：按需拉 countries.json，只在这个折叠区里渲染 -->
      <div v-if="showAllCountries" class="mt-1.5 rounded-md bg-field p-2" data-tv-col="live-country">
        <p v-if="countriesBusy" class="text-[11px] text-ink-3">正在拉国家列表…</p>
        <p v-else-if="countriesMsg" class="text-[11px] text-warn">
          {{ countriesMsg }}
          <button class="ml-1 underline" @click="loadAllCountries">重试</button>
        </p>
        <div
          v-else
          class="grid max-h-56 grid-cols-2 gap-1 overflow-y-auto p-2 sm:grid-cols-3 lg:grid-cols-5"
          data-tv-inset-focus
        >
          <button
            v-for="c in allCountries"
            :key="c.code"
            class="flex min-w-0 items-center gap-1 truncate rounded px-1.5 py-1 text-left text-[11px] text-ink-2 transition hover:bg-accent/20 hover:text-accent"
            :class="hasCountryPreset(c.code) ? 'opacity-40' : ''"
            :title="hasCountryPreset(c.code) ? '已添加' : countryPresetUrl(c.code)"
            @click="addCountryLive(c)"
          >
            <span class="shrink-0">{{ c.flag }}</span>
            <span class="truncate">{{ c.label }}</span>
          </button>
        </div>
        <p class="mt-1 px-0.5 text-[10px] leading-relaxed text-ink-3">
          港澳台与海外中文台已经在内置的「iptv-org 中文台」里，再加对应国家会多出重复线路。
        </p>
      </div>
      <p v-if="liveMsg" class="mt-1.5 text-[11px] text-ink-3">{{ liveMsg }}</p>

    
      </template>
      </section>

    <!-- ══════════ 播放 ══════════ -->
    <section data-sec="play" class="set-card shrink-0">
      <template v-if="visitedSec('play')">
      <div class="set-head">
        <h2 class="set-title">播放</h2>
        <span class="set-hint">画面适配 · 连播 · 片头片尾</span>
      </div>

      <div class="set-grid">
        <TileSelect
          :model-value="prefs.videoFit"
          label="画面缩放"
          :options="fitOptions"
          @update:model-value="(v) => update('videoFit', v as Prefs['videoFit'])"
        />
        <TileSelect
          :model-value="prefs.autoNextMode"
          label="自动连播"
          :options="autoPlayOptions"
          @update:model-value="(v) => update('autoNextMode', v as Prefs['autoNextMode'])"
        />
        <TileSelect
          v-if="prefs.autoNextMode === 'countdown'"
          :model-value="prefs.autoNextDelay"
          label="连播倒计时"
          :options="autoDelayOptions"
          @update:model-value="(v) => update('autoNextDelay', Number(v))"
        />
        <TileSelect
          :model-value="prefs.skipIntro"
          label="跳过片头"
          :options="skipOptions"
          @update:model-value="(v) => update('skipIntro', Number(v))"
        />
        <TileSelect
          :model-value="prefs.skipOutro"
          label="跳过片尾"
          :options="skipOptions"
          @update:model-value="(v) => update('skipOutro', Number(v))"
        />
        <TileSelect
          :model-value="prefs.skipCountdown"
          label="跳过前倒计时"
          :options="skipCdOptions"
          @update:model-value="(v) => update('skipCountdown', Number(v))"
        />
        <TileSelect
          :model-value="prefs.skipMiddle ? 'on' : 'off'"
          label="跳过中插广告"
          :options="onOff"
          @update:model-value="(v) => update('skipMiddle', v === 'on')"
        />
        <!--
          O3（2026-11-21）：AC3/DTS 音轨 WebView 解不了（Dolby 授权），
          片库直连会"有画面没声音"。TV 壳有原生播放器（设备 Dolby 解码），
          检测到无声时可以自动切过去；默认关——只提示、不自动打断看片。
        -->
        <TileSelect
          v-if="tvMode"
          :model-value="prefs.nativeOnSilent ? 'on' : 'off'"
          label="无声自动用原生播放"
          :options="onOff"
          @update:model-value="(v) => update('nativeOnSilent', v === 'on')"
        />
        <!--
          无声时的记忆选择（2026-11-24）：提示条点过一次会记住，
          必须给一个改回「每次询问」的出口，否则一次误点永久生效。
        -->
        <TileSelect
          :model-value="prefs.silentAction"
          label="音轨解不了时"
          :options="silentActionOptions"
          @update:model-value="(v) => update('silentAction', v as 'ask' | 'native' | 'transcode')"
        />
        <!--
          TV 上控制栏会自动隐藏，想知道播到哪只能按键唤出。
          这两个开关给"想一直看到位置"的人用，默认关（不挡画面）。
        -->
        <TileSelect
          :model-value="prefs.showTimeBadge ? 'on' : 'off'"
          label="右上角进度"
          :options="onOff"
          @update:model-value="(v) => update('showTimeBadge', v === 'on')"
        />
        <TileSelect
          :model-value="prefs.showMiniProgress ? 'on' : 'off'"
          label="底部迷你进度条"
          :options="onOff"
          @update:model-value="(v) => update('showMiniProgress', v === 'on')"
        />
      </div>
      <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
        「跳过片头/片尾」是没手动标记时的默认秒数；播放器里点「片头/片尾」按钮可按剧精确标记。
        倒计时选「立即跳」则不留反应时间。
      </p>
      <p class="mt-1 text-[11px] leading-relaxed text-ink-3">
        「跳过中插广告」只对源站给了 <code class="rounded bg-field px-1">skips</code>
        中插区间的片生效，默认关闭——中插在正片中间，区间标错会吞掉剧情。
      </p>
      <p class="mt-1 text-[11px] leading-relaxed text-ink-3">
        「右上角进度」「底部迷你进度条」是常驻显示，控制栏隐藏时也能看到位置。
        迷你进度条只在控制栏隐藏时出现，避免与底栏自带的进度条重复。
      </p>

    
      </template>
      </section>

    <!-- ══════════ 弹幕 ══════════ -->
    <section data-sec="danmaku" class="set-card shrink-0">
      <template v-if="visitedSec('danmaku')">
      <div class="set-head">
        <h2 class="set-title">弹幕</h2>
        <span class="set-hint">显示效果本地生效 · 源地址由后端使用</span>
      </div>

      <div class="set-sub">
        <span class="font-medium text-ink-2">弹幕显示</span>
        <span>只在播放页生效，不改源站数据</span>
      </div>
      <div class="set-grid">
        <TileSelect v-model="dmOnOff" label="弹幕" :options="onOff" />
        <TileSelect
          :model-value="prefs.danmakuScale"
          label="弹幕字号"
          :options="scaleOptions"
          @update:model-value="(v) => update('danmakuScale', Number(v))"
        />
        <TileSelect
          :model-value="prefs.danmakuOpacity"
          label="弹幕透明度"
          :options="opacityOptions"
          @update:model-value="(v) => update('danmakuOpacity', Number(v))"
        />
        <TileSelect
          :model-value="prefs.danmakuDensity"
          label="弹幕密度"
          :options="densityOptions"
          @update:model-value="pickDensity"
        />
        <TileSelect
          :model-value="prefs.danmakuDuration"
          label="弹幕速度"
          :options="durationOptions"
          @update:model-value="(v) => tuneDanmaku({ duration: Number(v) })"
        />
        <TileSelect
          :model-value="prefs.danmakuArea"
          label="弹幕区域"
          :options="areaOptions"
          @update:model-value="(v) => tuneDanmaku({ area: Number(v) })"
        />
      </div>
      <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
        密度是一组预设（滚动时长 + 显示区域），改下面两项任一项会自动变成「自定义」。
      </p>

      <!-- 屏蔽词：弹幕里最常见的是引战和刷屏，给用户一个自救手段 -->
      <div class="mt-3 border-t border-line pt-3">
        <div class="set-sub">
          <span class="font-medium text-ink-2">屏蔽词</span>
          <span>逗号或换行分隔，命中即不显示</span>
        </div>
        <textarea
          v-model="dmBlockInput"
          rows="2"
          placeholder="例如：引战词, 剧透, 广告"
          class="w-full resize-y rounded-md border border-line bg-field px-3 py-2 text-sm text-ink outline-none focus:border-accent/60"
          @blur="saveDmBlock"
        />
        <div class="mt-1.5 flex items-center gap-2">
          <button
            class="rounded-md bg-card px-3 py-1 text-xs transition hover:bg-card-hover"
            @click="saveDmBlock"
          >
            保存
          </button>
          <span class="text-[11px] text-ink-3">
            另外自动过滤纯符号和 1.5 秒内重复刷屏的弹幕
          </span>
          <span v-if="dmBlockMsg" class="text-[11px] text-ok">{{ dmBlockMsg }}</span>
        </div>
      </div>

      <!-- 源：这才是真正决定「弹幕从哪来」的配置，放在显示项之后 -->
      <div class="mt-3 border-t border-line pt-3">
        <div class="set-sub">
          <span class="font-medium text-ink-2">来源</span>
          <span>留空 = 内置直连（爱奇艺 + 腾讯）→ 兜底公共站</span>
        </div>
        <!-- 单行：输入框吃掉剩余宽度，右侧固定「腾讯」勾选 + 保存。
             地址是单行值，用 textarea 两行纯属浪费竖向空间，
             也让保存按钮与勾选飘到下一行、和别处按钮不在一条基线上。 -->
        <div class="flex flex-wrap items-center gap-2.5">
          <span class="w-16 shrink-0 text-xs text-ink-3">弹幕 API</span>
          <input
            v-model="danmakuApi"
            type="text"
            placeholder="留空则用内置源；装「弹幕聚合」插件后会自动填入"
            class="min-w-[16rem] max-sm:min-w-[10rem] flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs text-ink outline-none transition placeholder:text-ink-3 focus:border-accent"
          />
          <label class="flex shrink-0 cursor-pointer items-center gap-1.5 text-xs text-ink-2">
            <input v-model="danmakuTencent" type="checkbox" class="accent-accent" />
            腾讯自动抓取
          </label>
          <button
            class="shrink-0 rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent/60 disabled:opacity-40"
            :disabled="danmakuSaving"
            @click="saveDanmakuApi"
          >
            {{ danmakuSaving ? '保存中…' : '保存' }}
          </button>
          <span v-if="danmakuMsg" class="shrink-0 text-[11px] text-ok">{{ danmakuMsg }}</span>
        </div>

        <!--
          自动播放的降级顺序（policy.json 的 danmakuOrder）。
          用户原话：「danmu_api 出的爱奇艺、腾讯等弹幕更准确」——
          它覆盖 20+ 平台、分集映射更全；而内置直连是官方原始数据、
          但只覆盖两个平台。谁先谁后不该写死。
        -->
        <div class="mt-2 flex flex-wrap items-center gap-2">
          <span class="w-16 shrink-0 text-xs text-ink-3">自动抓取</span>
          <div class="flex shrink-0 gap-1.5">
            <button
              class="rounded-md px-2.5 py-1 text-xs transition"
              :class="
                orderFirst === 'builtin'
                  ? 'bg-accent font-medium text-on-accent'
                  : 'bg-white/8 text-ink-2 hover:bg-white/14'
              "
              @click="orderFirst = 'builtin'"
            >
              内置直连优先
            </button>
            <button
              class="rounded-md px-2.5 py-1 text-xs transition"
              :class="
                orderFirst === 'aggregator'
                  ? 'bg-accent font-medium text-on-accent'
                  : 'bg-white/8 text-ink-2 hover:bg-white/14'
              "
              @click="orderFirst = 'aggregator'"
            >
              弹幕 API 优先
            </button>
          </div>
        </div>

        <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
          「弹幕 API 优先」适合装了 danmu_api：覆盖 20+ 平台，分集更准；连不上会自动回落到内置源。
          关闭「腾讯自动抓取」后，腾讯只在手动搜索里出现。
        </p>
        <p
          v-if="danmakuApi.trim() && orderFirst === 'builtin'"
          class="mt-1.5 text-[11px] leading-relaxed text-warn"
        >
          已填 API，但当前是「内置直连优先」：首播仍先用内置源。想优先用 API 请切到「弹幕 API 优先」。
        </p>

        <!--
          下集弹幕预取（policy.json 的 danmakuPrefetch / danmakuPrefetchLead）。
          触发公式：min(片尾标记, 时长) - 提前量。
          用户原话：「下集预取可以在当前集片尾或标记片尾的前几分钟抓取」。
        -->
        <div class="mt-2.5 flex flex-wrap items-center gap-2">
          <span class="w-16 shrink-0 text-xs text-ink-3">下集预取</span>
          <label class="flex shrink-0 cursor-pointer items-center gap-1.5 text-xs text-ink-2">
            <input v-model="prefetchOn" type="checkbox" class="accent-accent" />
            片尾前预抓
          </label>
          <label
            class="flex shrink-0 items-center gap-1.5 text-xs"
            :class="prefetchOn ? 'text-ink-2' : 'text-ink-3/50'"
          >
            提前
            <input
              v-model.number="prefetchLead"
              type="number"
              min="10"
              max="3600"
              step="10"
              :disabled="!prefetchOn"
              class="w-16 rounded-md border border-line bg-field px-2 py-1 text-center font-mono text-xs text-ink outline-none transition focus:border-accent disabled:opacity-40"
            />
            秒
          </label>
        </div>
        <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
          片尾前提前抓下一集，切集不用等。内置源 60 秒够用；danmu_api 首次较慢，建议 180 秒。
        </p>
      </div>
      <!-- 本地弹幕：后端那份在 Web 部署时够不着，浏览器这份才是真离线 -->
      <div class="mt-3 rounded-md bg-field px-3 py-2.5">
        <div class="flex items-center gap-2">
          <span class="flex-1 text-xs text-ink-2">浏览器弹幕缓存</span>
          <span class="text-[11px] text-ink-3">
            {{ dmLocal.entries }} 集 · {{ fmtBytes(dmLocal.bytes) }}
          </span>
          <button
            class="rounded-md bg-card px-2.5 py-1 text-[11px] transition hover:bg-card-hover disabled:opacity-40"
            :disabled="!dmLocal.entries"
            @click="clearDmLocal"
          >
            清除
          </button>
        </div>
        <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
          播放过的弹幕会存在浏览器里，断网后仍能显示。后端那份（下面的「存储 → 后端弹幕缓存」）
          在 Web 部署时手机够不着，所以这份是断网看片的关键。
        </p>
        <p v-if="dmLocalMsg" class="mt-1 text-[11px] text-ok">{{ dmLocalMsg }}</p>
      </div>

    
      </template>
      </section>

    <!-- ══════════ 外观 ══════════ -->
    <section data-sec="appearance" class="set-card shrink-0">
      <template v-if="visitedSec('appearance')">
      <div class="set-head">
        <h2 class="set-title">外观</h2>
        <span class="set-hint">主题配色 · 列表密度 · 背景图</span>
      </div>

      <div class="set-grid">
        <TileSelect
          :model-value="prefs.themeStyle"
          label="主题"
          :options="themeOptions"
          @update:model-value="(v) => update('themeStyle', v as Prefs['themeStyle'])"
        />
        <TileSelect
          :model-value="prefs.themeMode"
          label="明暗"
          :options="modeOptions"
          @update:model-value="(v) => update('themeMode', v as Prefs['themeMode'])"
        />
        <TileSelect
          :model-value="prefs.wallpaperBlur"
          label="背景模糊"
          :options="blurOptions"
          @update:model-value="(v) => update('wallpaperBlur', Number(v))"
        />
        <TileSelect
          :model-value="prefs.wallpaperOpacity"
          label="壁纸可见度"
          :options="wallpaperOpacityOptions"
          @update:model-value="(v) => update('wallpaperOpacity', Number(v))"
        />
        <!-- 列数上限从原「浏览与搜索」区搬来：它是"首页长什么样"，属于外观 -->
        <TileSelect
          :model-value="prefs.homeCols"
          label="列数上限"
          :options="colsOptions"
          @update:model-value="(v) => update('homeCols', Number(v))"
        />
      </div>
      <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
        「列数上限」是上限不是结果：实际列数由屏幕宽度决定，窄屏会自动减少。
        上限 {{ prefs.homeCols }} 列 · 本机当前 {{ localCols }} 列。
      </p>

      <!-- 首页首屏形态：有续播才有卡，关掉则直接进片墙 -->
      <div class="set-sub mt-3">
        <span class="font-medium text-ink-2">首页续播卡</span>
        <span>顶部显示「继续观看」+ 剧情简介；没有观看记录时本来就不显示</span>
        <label class="ml-auto flex items-center gap-2 text-[11px] text-ink-3">
          <span>显示</span>
          <ToggleSwitch
            :model-value="prefs.homeHero"
            label="首页续播卡"
            @update:model-value="(v) => update('homeHero', v)"
          />
        </label>
      </div>

      <div class="set-sub mt-3">
        <span class="font-medium text-ink-2">背景图</span>
        <span>播放页不使用背景</span>
        <!-- 可见度调高时文字对比度确实会下降，明说比让用户自己发现好 -->
        <span v-if="prefs.wallpaperOpacity >= 0.6" class="text-warn">
          可见度较高，浅色主题下小字可能偏淡
        </span>
      </div>
      <WallpaperPicker
        :model-value="prefs.wallpaper"
        :blur="prefs.wallpaperBlur"
        :mode="resolveMode(prefs.themeMode)"
        @update:model-value="(v) => update('wallpaper', v)"
      />
    
      </template>
      </section>

    <!-- 旧版「播放与显示」已拆成 播放 / 弹幕 / 搜索与源 三块，此注释保留以免误合回 -->

    <!-- ══════════ 存储 ══════════
      ★ 2026-10-30 重构：原「离线缓存」区改名「存储」，WebDAV 与 DNS 各自独立出去
      （用户要求拆开：存储、WebDAV、维护各管一件事）。
      历史记录条数从原「浏览与搜索」搬来 —— 它是"留多久"，属于存储不属搜索。
    -->
    <section data-sec="storage" class="set-card shrink-0">
      <template v-if="visitedSec('storage')">
      <div class="set-head">
        <h2 class="set-title">存储</h2>
        <span class="set-hint">离线缓存 · 观看记录</span>
        <div class="set-actions flex items-center gap-2 text-[11px] text-ink-3">
          <span>共 {{ fmtBytes(cacheBytes) }}</span>
          <button
            class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover"
            @click="loadCache"
          >
            刷新
          </button>
        </div>
      </div>
      <div class="set-sub">
        <span class="font-medium text-ink-2">离线缓存</span>
        <span>断网也能看完一集；分片落在后端数据目录</span>
      </div>
      <p class="text-[11px] leading-relaxed text-ink-3">
        断网也能看完一集：分片与清单都在后端数据目录，地址改成同目录相对路径。
      </p>


      <!-- 配额与淘汰：后端早支持（policy.json 的 cacheQuotaGb / cacheEvictMode），
           但界面上一直没入口，用户被静默删片时完全不知道原因 -->
      <div class="mt-3 rounded-md bg-field px-3 py-2.5">
        <div class="set-grid max-sm:grid-cols-1">
          <!--
            配额用磁贴包裹：原来是"裸 input + GB 字样"挤在行内，
            与设置页其他控件（都是整块磁贴）在高度和点击区上都不一致。
          -->
          <div class="tile">
            <span class="truncate text-[15px] text-ink">缓存配额</span>
            <span class="ml-auto flex shrink-0 items-center gap-1.5">
              <input
                v-model.number="cacheQuotaGb"
                type="number"
                min="0"
                step="1"
                class="num-field w-14 rounded border border-line bg-card-sunken px-1.5 py-0.5 text-right text-[13px] text-ink outline-none focus:border-accent/60"
                title="0 表示不限制"
              />
              <span class="text-[13px] text-ink-2">GB</span>
            </span>
          </div>
          <TileSelect
            :model-value="cacheEvictMode"
            label="超限时"
            :options="evictModeOptions"
            @update:model-value="(v) => (cacheEvictMode = v as 'lru' | 'off')"
          />
        </div>
        <div class="mt-2 flex flex-wrap items-center gap-2">
          <button
            class="rounded-md bg-card px-3 py-1 text-xs transition hover:bg-card-hover"
            @click="saveCacheConfig"
          >
            保存
          </button>
          <span v-if="cacheCfgMsg" class="text-[11px] text-ok">{{ cacheCfgMsg }}</span>
        </div>
        <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
          配额填 <code class="rounded bg-card px-1">0</code> 表示不限制；超限时删最久没播放的，
          正在看的不会被删。
          <template v-if="cacheDir"><br />实际目录：<code class="text-ink-2 break-all">{{ cacheDir }}</code></template>
        </p>
      </div>

      <!-- 缓存位置：policy.json 的 cacheDir。
           后端只在启动时解析一次（允许中途换盘会让一集的分片散落两个目录），
           所以这里的保存按钮只写配置，「改后需重启后端生效」必须写在界面上 -->
      <div class="mt-2 rounded-md bg-field px-3 py-2.5">
        <div class="flex flex-wrap items-center gap-2">
          <span class="w-16 shrink-0 text-xs text-ink-3">缓存位置</span>
          <!--
            这里刻意**不**套磁贴：磁贴是"标签 + 值"的双栏结构，
            留给路径的宽度不够（实测同类磁贴值区约 45% 宽），
            而路径通常 20+ 字符，塞进去只能看到 `D:\meow-ca…`。
            统一的是配色与圆角，不是结构。
          -->
          <input
            v-model="cacheDirPolicy"
            placeholder="留空 = 数据目录下 cache/video"
            class="min-w-[16rem] max-sm:min-w-[10rem] flex-1 rounded-md border border-line bg-card-sunken px-2.5 py-1.5 font-mono text-xs text-ink outline-none transition placeholder:text-ink-3 focus:border-accent"
            title="相对路径按后端数据目录解析（如 mnt/cache）；也可以填绝对路径（如 D:\meow-cache）"
            @keyup.enter="saveCacheDir"
          />
          <button
            class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover"
            @click="saveCacheDir"
          >
            保存
          </button>
          <!-- 后端明确告知"改了但没生效"，这一刻必须可见，否则用户会对着"改了没变"猜 -->
          <span v-if="cacheDirReloadRequired" class="text-[11px] text-warn">需重启后端生效</span>
          <span v-if="cacheDirMsg" class="text-[11px] text-ok">{{ cacheDirMsg }}</span>
        </div>
        <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
          相对路径按后端数据目录解析，也可填绝对路径。改后需重启后端生效。
        </p>
      </div>

      <!--
        ★ 原来这里是逐集的缓存清单（「我不是大师 1 / 已就绪 / 366 MB / 删除」）。
        2026-10-30 用户要求删掉：「不应该显示清单明细吧，显示当前占用就行了吧」。
        理由成立——管理的入口已经在「我的缓存」页（海报墙 → 详情 → 选集），
        设置页再列一份是两套界面管一件事，而且一部长剧能把这一屏铺满。
        这里只保留**汇总**：视频缓存、弹幕缓存各占多少。
      -->
      <div class="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-2">
        <div class="tile">
          <span class="truncate text-[15px] text-ink">视频缓存</span>
          <span class="ml-auto shrink-0 text-[13px] tabular-nums text-ink-2">
            {{ fmtBytes(cacheBytes) }}
            <template v-if="cacheQuotaGb > 0" class="text-ink-3">
              / {{ cacheQuotaGb }} GB
            </template>
          </span>
        </div>
        <div class="tile">
          <span class="shrink-0 text-[15px] text-ink">后端弹幕缓存</span>
          <span class="ml-auto shrink-0 text-[13px] tabular-nums text-ink-2">
            {{ fmtBytes(danmakuBytes) }}<template v-if="danmakuCount" class="text-ink-3"> · {{ danmakuCount }} 集</template>
          </span>
          <template v-if="danmakuBytes !== null">
            <button
              class="ml-2 shrink-0 rounded px-1.5 py-0.5 text-[11px] text-ink-3 transition hover:bg-card-hover hover:text-ink"
              title="只删超过 30 天没动过的"
              @click="clearDanmakuCache(false)"
            >
              清过期
            </button>
            <button
              class="shrink-0 rounded px-1.5 py-0.5 text-[11px] text-ink-3 transition hover:bg-card-hover hover:text-hot"
              @click="clearDanmakuCache(true)"
            >
              全清
            </button>
          </template>
        </div>
      </div>

      <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
        <template v-if="danmakuBytes === null">
          当前后端版本还不汇报弹幕缓存占用（升级后端插件后可见）。
        </template>
        <template v-else>
          缓存一集会顺带存弹幕，断网也能看；弹幕缓存不占配额，30 天没播放会被清理。
        </template>
        <span v-if="dmCacheMsg" class="ml-1 text-ink-2">{{ dmCacheMsg }}</span>
      </p>

      <!--
        弹幕配额与淘汰：照搬上面视频缓存的那套（policy.json 的
        danmakuCacheQuotaMb / danmakuCacheEvictMode）。
        之前弹幕缓存只有 30 天 TTL，而 TTL 只在**读**时判断、文件永不删
        ——一季 30 集约 42MB，攒几个月没人清理。

        ★ 必须放在上面那个汇总网格**外面**（2026-11-02 踩过）：原来它被写在
          `grid sm:grid-cols-2` 内部，先占掉一列、再按自身的 4 列切分，
          每个磁贴只剩 ~95px —— 200MB 的数字被输入框裁掉、下拉框的
          "自动删最久没看的"直接溢出，看着像样式坏了。
      -->
      <div class="set-grid mt-2 max-sm:grid-cols-1">
        <div class="tile">
          <span class="truncate text-[15px] text-ink">弹幕配额</span>
          <span class="ml-auto flex shrink-0 items-center gap-1.5">
            <input
              v-model.number="danmakuQuotaMb"
              type="number"
              min="0"
              step="50"
              class="num-field w-14 rounded border border-line bg-card-sunken px-1.5 py-0.5 text-right text-[13px] text-ink outline-none focus:border-accent/60"
              title="0 表示不限制"
            />
            <span class="text-[13px] text-ink-2">MB</span>
          </span>
        </div>
        <TileSelect
          :model-value="danmakuEvictMode"
          label="超限时"
          :options="evictModeOptions"
          @update:model-value="(v) => (danmakuEvictMode = v as 'lru' | 'off')"
        />
      </div>
      <div class="mt-2 flex flex-wrap items-center gap-2">
        <button
          class="rounded-md bg-card px-3 py-1 text-xs transition hover:bg-card-hover"
          @click="saveDanmakuQuota"
        >
          保存
        </button>
        <span v-if="dmQuotaMsg" class="text-[11px] text-ok">{{ dmQuotaMsg }}</span>
      </div>
      <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
        弹幕配额填 <code class="rounded bg-card px-1">0</code> 表示不限制。
        超出后按「最久没看过」淘汰——每次命中缓存都会刷新访问时间，
        <b>正在播放的那一集永远不会被删</b>。
      </p>

      <!-- ── 本机缓存 ──
           海报/脚本等 HTTP 缓存与 sessionStorage，可再生；不动观看历史与设置。 -->
      <div class="set-sub mt-3 border-t border-line pt-3">
        <span class="font-medium text-ink-2">本机缓存</span>
        <span>海报/脚本等可再生数据；观看历史与设置都保留</span>
      </div>
      <div class="mt-2 flex flex-wrap items-center gap-2">
        <button
          class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover"
          @click="clearLocalCache"
        >
          清空本地缓存
        </button>
        <span class="text-[11px] text-ink-3">只清海报/脚本缓存，历史和设置都保留</span>
      </div>
      <p
        v-if="localCacheMsg"
        class="mt-1.5 text-[11px] text-ink-2"
      >
        {{ localCacheMsg }}
      </p>

      <!-- ── 观看记录 ──
           从原「浏览与搜索」搬来：这是"留多久"，属于存储不属搜索 -->
      <div class="set-sub mt-3">
        <span class="font-medium text-ink-2">观看记录</span>
        <span>保留多久、超了怎么裁</span>
      </div>
      <div class="set-grid">
        <TileSelect
          :model-value="prefs.historyLimit"
          label="历史记录条数"
          :options="historyOptions"
          @update:model-value="(v) => update('historyLimit', Number(v))"
        />
      </div>
      <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
        超出后最旧的记录会被丢弃；「片头片尾标记」单独存放，不受这个上限影响。
      </p>

    
      </template>
      </section>

    <!-- ══════════ WebDAV ══════════ -->
    <section data-sec="sync" class="set-card shrink-0">
      <template v-if="visitedSec('sync')">
      <div class="set-head">
        <h2 class="set-title">WebDAV</h2>
        <span class="set-hint">多端同步观看记录与设置快照</span>
        <label class="set-actions flex items-center gap-2 text-[11px] text-ink-3">
          <span>启用</span>
          <ToggleSwitch
            :model-value="dav.enabled"
            label="启用 WebDAV 同步"
            @update:model-value="toggleSync"
          />
        </label>
      </div>

      <div class="mt-2 flex flex-wrap items-center gap-2.5">
        <span class="w-20 shrink-0 text-xs text-ink-3">服务地址</span>
        <input
          v-model="dav.url"
          class="min-w-[16rem] max-sm:min-w-[10rem] flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs outline-none transition focus:border-accent"
          placeholder="http://192.168.1.10:5005/meow"
          @input="patchDav({ url: dav.url })"
        />
      </div>
      <div class="mt-2 grid grid-cols-1 gap-2.5 sm:grid-cols-3">
        <label class="flex items-center gap-2">
          <span class="w-12 shrink-0 text-xs text-ink-3">目录</span>
          <input
            v-model="dav.dir"
            class="min-w-0 flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs outline-none transition focus:border-accent"
            placeholder="meow"
            @input="patchDav({ dir: dav.dir })"
          />
        </label>
        <label class="flex items-center gap-2">
          <span class="w-14 shrink-0 text-xs text-ink-3">用户名</span>
          <input
            v-model="dav.user"
            class="min-w-0 flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 text-xs outline-none transition focus:border-accent"
            placeholder="可选"
            @input="patchDav({ user: dav.user })"
          />
        </label>
        <label class="flex items-center gap-2">
          <span class="w-12 shrink-0 text-xs text-ink-3">密码</span>
          <input
            v-model="dav.pass"
            type="password"
            class="min-w-0 flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 text-xs outline-none transition focus:border-accent"
            placeholder="可选"
            @input="patchDav({ pass: dav.pass })"
          />
        </label>
      </div>

      <div class="mt-2 flex flex-wrap items-center gap-2">
        <button
          class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover disabled:opacity-40"
          :disabled="davBusy || !dav.url"
          @click="testDav"
        >
          测试连接
        </button>
        <span
          class="text-[11px]"
          :class="
            syncStatus.state === 'error'
              ? 'text-warn'
              : syncStatus.state === 'ok'
                ? 'text-ok'
                : 'text-ink-3'
          "
        >
          {{ davMsg ? davMsg.text : syncText }}
        </span>
      </div>

      <!-- 历史：各端都写，并集读取 -->
      <div class="mt-3 rounded-lg border border-line bg-field p-2.5">
        <div class="mb-1.5 flex flex-wrap items-center gap-2">
          <span class="text-[11px] font-medium text-ink-2">观看历史</span>
          <span class="text-[10px] text-ink-3">各端都可写入，读取时按时间合并</span>
          <label class="ml-auto flex items-center gap-1.5 text-[11px] text-ink-3">
            <input
              type="checkbox"
              :checked="dav.syncHistory"
              @change="patchDav({ syncHistory: ($event.target as HTMLInputElement).checked })"
            />
            自动同步
          </label>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <button
            class="set-btn"
            :disabled="davBusy || !dav.url"
            @click="pushHistoryNow"
          >
            上传历史
          </button>
          <button
            class="set-btn"
            :disabled="davBusy || !dav.url"
            @click="pullHistoryNow"
          >
            拉取合并
          </button>
        </div>
      </div>

      <!-- 搜索关键词：同样并集，独立文件 -->
      <div class="mt-2 rounded-lg border border-line bg-field p-2.5">
        <div class="mb-1.5 flex flex-wrap items-center gap-2">
          <span class="text-[11px] font-medium text-ink-2">搜索关键词</span>
          <span class="text-[10px] text-ink-3">最近 {{ searchWordCount }} 个，各端合并去重</span>
          <label class="ml-auto flex items-center gap-1.5 text-[11px] text-ink-3">
            <input
              type="checkbox"
              :checked="dav.syncSearchHistory"
              @change="patchDav({ syncSearchHistory: ($event.target as HTMLInputElement).checked })"
            />
            自动同步
          </label>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <button
            class="set-btn"
            :disabled="davBusy || !dav.url"
            @click="pushSearchWordsNow"
          >
            上传关键词
          </button>
          <button
            class="set-btn"
            :disabled="davBusy || !dav.url"
            @click="pullSearchWordsNow"
          >
            拉取合并
          </button>
          <button
            class="set-btn set-btn--ghost set-btn--danger ml-auto"
            :disabled="!searchWordCount"
            @click="clearWords"
          >
            清空本机
          </button>
        </div>
      </div>

      <!-- 设置备份：多时间点快照 -->
      <div class="mt-2 rounded-lg border border-line bg-field p-2.5">
        <div class="mb-1.5 flex flex-wrap items-center gap-2">
          <span class="text-[11px] font-medium text-ink-2">设置备份</span>
          <span class="text-[10px] text-ink-3">每次一份快照，可回到任意时间点</span>
          <label class="ml-auto flex items-center gap-1.5 text-[11px] text-ink-3">
            <input
              type="checkbox"
              :checked="dav.syncPrefs"
              @change="patchDav({ syncPrefs: ($event.target as HTMLInputElement).checked })"
            />
            自动备份
          </label>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <button
            class="set-btn set-btn--primary"
            :disabled="snapBusy || !dav.url"
            @click="backupNow"
          >
            立即备份
          </button>
          <button
            class="set-btn"
            :disabled="snapBusy || !dav.url"
            @click="refreshSnapshots"
          >
            刷新列表
          </button>
          <button
            class="set-btn"
            :disabled="snapBusy || !snapshots.length"
            title="只保留最近 3 份，其余删除"
            @click="cleanOldSnapshots"
          >
            清理旧备份
          </button>
          <span v-if="snapBusy" class="h-3 w-3 animate-spin rounded-full border-2 border-line-hover border-t-accent" />
          <span
            v-if="snapMsg"
            class="text-[11px]"
            :class="snapMsg.ok ? 'text-ink-3' : 'text-warn'"
          >
            {{ snapMsg.text }}
          </span>
        </div>

        <!-- 时间点列表 -->
        <ul v-if="snapshots.length" class="mt-2 max-h-[9.5rem] divide-y divide-line overflow-y-auto rounded bg-field">
          <li
            v-for="s in snapshots"
            :key="s.name"
            class="flex items-center gap-2 px-2 py-1.5 text-[11px]"
          >
            <span class="min-w-0 flex-1 truncate text-ink-2">
              {{ new Date(s.mtime ?? s.at).toLocaleString() }}
            </span>
            <button
              class="shrink-0 rounded px-1.5 py-0.5 text-ink-3 transition hover:bg-accent/15 hover:text-accent"
              :disabled="snapBusy"
              @click="restoreSnapshotAt(s)"
            >
              还原
            </button>
            <button
              class="shrink-0 rounded px-1.5 py-0.5 text-ink-3 transition hover:bg-hot/15 hover:text-hot"
              :disabled="snapBusy"
              @click="removeSnapshotAt(s)"
            >
              删除
            </button>
          </li>
        </ul>
        <p v-else-if="!snapBusy && dav.url" class="mt-2 text-[11px] text-ink-3">
          还没有备份，点「立即备份」创建第一份
        </p>
      </div>

      <p class="mt-2 text-[11px] leading-relaxed text-ink-3">
        启用后自动上传历史，设置变更时建快照（保留最近 10 份）。启动只合并历史，不改设置；
        地址密码只存本机。
      </p>
    
      </template>
      </section>

    <!-- ══════════ AI ══════════ -->
    <section data-sec="ai" class="set-card flex shrink-0 flex-col">
      <template v-if="visitedSec('ai')">
      <div class="set-head">
        <h2 class="set-title">AI</h2>
        <span class="set-hint">用一句话解释播放失败的原因</span>
      </div>

      <div class="set-sub">
        <span class="font-medium text-ink-2">模型连接</span>
        <span>不填也能用：规则判断在本地完成，模型只负责把结论说成人话</span>
        <label class="ml-auto flex items-center gap-2 text-[11px] text-ink-3">
          <span>启用</span>
          <ToggleSwitch
            :model-value="llm.enabled"
            label="启用 AI 模型"
            @update:model-value="setLlmEnabled"
          />
        </label>
      </div>

      <p class="text-[11px] leading-relaxed text-ink-3">
        播放失败时，错误页会自动分析原因并给出下一步。
        <b>不填模型也能用</b>——规则判断在本地完成，模型只负责把结论说得更像人话。
      </p>

      <div class="mt-3 flex flex-wrap items-center gap-2">
        <span class="text-[11px] text-ink-3">快速填入</span>
        <button
          v-for="p in LLM_PRESETS"
          :key="p.id"
          class="set-chip"
          :title="p.note"
          @click="pickPreset(p.id)"
        >
          {{ p.label }}
        </button>
      </div>

      <div class="mt-3 space-y-2">
        <div class="flex flex-wrap items-center gap-2.5">
          <span class="w-16 shrink-0 text-xs text-ink-3">接口地址</span>
          <input
            v-model="llm.baseUrl"
            placeholder="http://host:8788/v1 （OpenAI 兼容端点）"
            class="min-w-[18rem] max-sm:min-w-[10rem] flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs outline-none transition focus:border-accent"
            @change="persistLlm"
          />
        </div>
        <div class="flex flex-wrap items-center gap-2.5">
          <span class="w-16 shrink-0 text-xs text-ink-3">模型</span>
          <input
            v-model="llm.model"
            placeholder="deepseek-v4.1-flash"
            class="min-w-[12rem] max-sm:min-w-[10rem] flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs outline-none transition focus:border-accent"
            @change="persistLlm"
          />
          <span class="text-[11px] text-ink-3">可自填，不限定厂商</span>
        </div>
        <div class="flex flex-wrap items-center gap-2.5">
          <span class="w-16 shrink-0 text-xs text-ink-3">API Key</span>
          <input
            v-model="llm.apiKey"
            type="password"
            placeholder="sk-... 或自建网关的 key"
            class="min-w-[18rem] max-sm:min-w-[10rem] flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs outline-none transition focus:border-accent"
            @change="persistLlm"
          />
        </div>
      </div>

      <div class="mt-3 flex flex-wrap items-center gap-2.5">
        <span class="w-16 shrink-0 text-xs text-ink-3">上下文</span>
        <input
          v-model.number="llm.historyRounds"
          type="number"
          min="0"
          max="10"
          class="w-20 rounded-md border border-line bg-field px-2.5 py-1.5 text-xs outline-none transition focus:border-accent"
          @change="persistLlm"
        />
        <span class="text-[11px] text-ink-3">
          保留最近几轮问答（0–10，0 = 不带历史）。
          带上文后「庆余年怎么样」→「那就第二季第一集」这种追问才接得上。
        </span>
      </div>

      <div class="mt-2.5 flex flex-wrap items-center gap-2">
        <button
          class="rounded-md bg-card px-3 py-1.5 text-xs transition hover:bg-card-hover disabled:opacity-40"
          :disabled="llmBusy || !llm.baseUrl || !llm.apiKey"
          @click="testLlm"
        >
          {{ llmBusy ? '测试中…' : '测试连接' }}
        </button>
        <span v-if="llmMsg" class="text-[11px]" :class="llmMsgOk ? 'text-ok' : 'text-hot'">
          {{ llmMsg }}
        </span>
      </div>

      <p class="mt-2 text-[11px] leading-relaxed text-ink-3">
        Key 只存本机，不进备份。推理模型会自动加大输出预算，避免思考过程挤掉回答。
      </p>
      <p v-if="!desktop" class="mt-1 text-[11px] leading-relaxed text-ink-3">
        Web / TV 通过后端转发；上面的「启用」是唯一开关。
      </p>
    
      </template>
      </section>

    <!-- ══════════ 网络与加速 ══════════ -->
    <section data-sec="net" class="set-card shrink-0">
      <template v-if="visitedSec('net')">
      <div class="set-head">
        <h2 class="set-title">网络与加速</h2>
        <span class="set-hint">DNS 解析 · GitHub 加速</span>
      </div>

      <!-- ── 网络（DNS）── -->
      <div class="set-sub">
        <span class="font-medium text-ink-2">网络</span>
        <span>安全 DNS 用 DoH 取真实 IP，绕过本机 TUN 的 fake-ip</span>
      </div>
      <!-- 只有两项：用 max-w 收窄，别让它们被 4 列网格拉成两大块留白 -->
      <div class="set-grid set-grid-narrow">
        <TileSelect v-model="dnsChoice" label="安全DNS" :options="dnsOptions" />
        <button
          type="button"
          class="tile group"
          :class="{ 'pointer-events-none opacity-40': dnsBusy }"
          @click="probeDns"
        >
          <span class="truncate text-[15px] text-ink">DNS 测试</span>
          <span class="ml-auto flex shrink-0 items-center gap-1.5">
            <span v-if="dnsProbe" class="text-xs" :class="dnsProbe.ok ? 'text-ok' : 'text-warn'">
              {{ dnsProbe.ok ? '成功' : '失败' }}
            </span>
            <span
              v-else-if="dnsBusy"
              class="h-3 w-3 animate-spin rounded-full border-2 border-line-hover border-t-accent"
            />
          </span>
        </button>
      </div>
      <p
        v-if="dnsProbe"
        class="mt-2 rounded-md bg-field px-2.5 py-1.5 font-mono text-[11px]"
        :class="dnsProbe.ok ? 'text-ok' : 'text-warn'"
      >
        {{ dnsProbe.text }}
      </p>

      <!-- 全局 GitHub 加速：只改传输层；业务源仍在各自区块 -->
      <GitAccelPanel />

    
      </template>
      </section>

    <!-- ══════════ 更新 ══════════ -->
    <section data-sec="update" class="set-card shrink-0">
      <template v-if="visitedSec('update')">
      <div class="set-head">
        <h2 class="set-title">更新</h2>
        <span class="set-hint">
          {{ tvModule ? '壳 / 运行时模块' : tvMode ? '壳' : desktop ? '壳 / 本机插件' : '壳 / fnOS 应用' }} · 源与备用源
        </span>
      </div>

      <!-- 更新中心：壳 / 模块 / 插件 / fpk 的统一检查入口
           「去安装 / 去更新」的跨区跳转交给本页导航逻辑（见 gotoSelector） -->
      <UpdateCenter @goto="gotoSelector" />

      <!-- ── 应用更新 ──
        只在有"安装包"概念的平台显示（PC / TV / iOS 侧载 IPA）。
        统一用平台身份判据 updPlatform !== 'web'：iOS 在 appUpdate.ts 的
        currentPlatform() 里是独立 'ios' 身份（与 compat/shell.ts 对齐），
        不再散落 `|| ios` 特例。Web 端没有安装包概念，显示灰按钮只会困惑。
        ★ 判据与导航项的 nativeOnly 必须同源，否则会出现"导航里有、点了没东西"。
      -->
      <template v-if="updPlatform !== 'web'">
      <div class="set-sub mt-3 border-t border-line pt-3">
        <span class="font-medium text-ink-2">应用更新源与高级设置</span>
        <span class="text-[11px] text-ink-3">
          <template v-if="ios">当前 v{{ currentAppVersion() }} · 检测侧载商店源里的新版本（iOS 只提示，需到 LiveContainer / SideStore 更新）</template>
          <template v-else>当前 v{{ appVersion }} · 清单里 {{ updPlatform === 'windows-x64' ? 'meow-pc' : 'meow-tv' }} 有更高版本时才提示</template>
        </span>
        <span class="flex-1" />
        <button
          class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover"
          :disabled="updChecking || updBusy"
          @click="checkUpdateNow()"
        >
          {{ updChecking ? `检查中… ${updSecs}s` : '用此源检查' }}
        </button>
        <!-- 长检查的出口：最多 4 条线路并发，网络差时几十秒 -->
        <button
          v-if="updChecking"
          class="rounded-md bg-card px-2.5 py-1 text-[11px] text-ink-3 hover:bg-card-hover"
          @click="cancelUpdCheck()"
        >
          取消
        </button>
      </div>
      <div class="pb-3 text-[11px]">
        <p v-if="updError" class="text-hot">{{ updError }}</p>
        <template v-else-if="updFound">
          <p class="text-ink">
            发现新版本
            <span class="font-medium text-accent">v{{ updFound.version }}</span>
            <span class="ml-2 text-ink-3">{{ fmtSize(updFound.size) }}</span>
            <span v-if="updFound.lasttime" class="ml-2 text-ink-3">{{ updFound.lasttime }} 发布</span>
          </p>
          <!--
            更新日志按行渲染：`desc` 里是多行文本，塞进单个 <p> 会把换行吃掉，
            用户只看到一整段糊在一起的字。最多显示 4 行，完整内容点「查看详情」。
          -->
          <ul v-if="updNoteLines.length" class="mt-1 space-y-0.5 text-ink-3">
            <li v-for="(line, i) in updNoteLines.slice(0, 4)" :key="i">{{ line }}</li>
            <li v-if="updNoteLines.length > 4" class="text-ink-3/70">
              … 还有 {{ updNoteLines.length - 4 }} 条，点「查看详情」
            </li>
          </ul>
          <div class="mt-2 flex items-center gap-2">
            <!--
              iOS 侧载：与应用更新弹框一致，点「更新」先弹通用的"发现新版本"，
              再在弹框内点「更新」叠出 iOS 升级指引（两条可复制链接）。
              所以这里 iOS 也走 openUpdDialog，而不是直接下载安装。
            -->
            <button
              class="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-on-accent hover:bg-accent-dim disabled:opacity-60"
              :disabled="updBusy"
              @click="ios ? openUpdDialog() : downloadAndInstall()"
            >
              更新
            </button>
            <button
              v-if="!updBusy"
              class="rounded-md bg-card px-2 py-1 text-[11px] text-ink-3 hover:bg-card-hover"
              @click="openUpdDialog()"
            >
              查看详情
            </button>
            <!-- 下载中才给取消：TV 上误按进下载没有鼠标能退出来；安装阶段不可取消 -->
            <button
              v-if="updPhase === 'downloading'"
              class="rounded-md bg-card px-2 py-1 text-[11px] text-ink-3 hover:bg-card-hover"
              @click="cancelAppUpdate()"
            >
              取消
            </button>
            <span v-if="updBusy" class="text-[11px] text-ink-3">
              {{ updStage }}<template v-if="updPhase === 'downloading'"> {{ updPercent }}%</template>
            </span>
          </div>
          <!-- 进度条：电视上没有鼠标指针，纯数字不够直观 -->
          <div v-if="updBusy" class="mt-2 h-1 w-full overflow-hidden rounded bg-card">
            <div class="h-full bg-accent transition-all" :style="{ width: updPercent + '%' }" />
          </div>
        </template>
        <p v-else-if="updChecked" class="text-ink-3">已是最新版本</p>
        <p v-else class="text-ink-3">点「用此源检查」查询新版本</p>

        <!--
          更新源：TV/Web 读不到插件源（那是 Tauri 命令），必须能在这里手填。
          留空 = 内置 GitHub Release 源；备用源只在主源失败时才试。
          ★ iOS 侧载走固定源（altstore-source.json），不提供手填。
        -->
        <div v-if="!ios" class="mt-3 flex items-center gap-2">
          <span class="shrink-0 text-xs text-ink-3">更新源</span>
          <input
            v-model="updSourceInput"
            class="min-w-0 flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs outline-none transition focus:border-accent"
            :placeholder="updSourcePlaceholder"
            spellcheck="false"
            data-tv-input-owner
            @click="editUpdateSource"
            @change="saveUpdateSource()"
          />
          <button
            v-if="updSourceInput"
            class="shrink-0 rounded-md bg-card px-2 py-1 text-[11px] hover:bg-card-hover"
            @click="clearUpdateSource()"
          >
            清除
          </button>
        </div>
        <div v-if="!ios" class="mt-1 flex items-center gap-2">
          <span class="shrink-0 text-xs text-ink-3">备用源</span>
          <input
            v-model="updBackupInput"
            class="min-w-0 flex-1 rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs outline-none transition focus:border-accent"
            placeholder="可选：主源失败时才试，留空=不启用"
            spellcheck="false"
            data-tv-input-owner
            @click="editBackupSource"
            @change="saveUpdateSource()"
          />
        </div>
        <p v-if="!ios" class="mt-1 text-[11px] text-ink-3">
          令牌与 GitHub 加速在「网络与加速 → GitHub 加速」统一设置。自定义源失败时只试这里的备用源，
          不回落官方源。
        </p>
        <p v-if="!ios" class="mt-1 text-[11px] text-ink-3">
          清单地址取其中 <span class="font-mono">{{ updPlatform === 'windows-x64' ? 'packages.meow-pc' : 'packages.meow-tv' }}</span>
          一条，需允许跨域读取。
        </p>
        <p v-if="ios" class="mt-1 text-[11px] text-ink-3">
          iOS 从固定的侧载商店源读取最新版本，检测到新版后给出 IPA 直链与源地址，复制后到 LiveContainer / SideStore 刷新即可。
        </p>
        <!--
          下载缓存：装完成后**不自动删**（安装器是异步读文件的，秒删可能让它读不到）。
          留个手动入口，用户想清就点这里。
          ★ 只清 cacheDir/updates/ —— 不碰设置、海报、片库、插件。
        -->
        <div class="mt-2 flex items-center gap-2">
          <button
            class="rounded-md bg-card px-2 py-1 text-[11px] text-ink-3 hover:bg-card-hover"
            :disabled="updCacheBusy"
            @click="clearCacheNow()"
          >
            {{ updCacheBusy ? '清理中…' : '清理下载缓存' }}
          </button>
          <span v-if="updCacheMsg" class="text-[11px] text-ink-3">{{ updCacheMsg }}</span>
        </div>
      </div>
      </template>

      <!-- ── 应用更新（fnOS fpk）──
        ★ 只在 fpk 入口出现。判据在组件内部：meowd 的 `/api/_shell/info` 能通
        **且** `/app-center/v1/app/installed` 能通；Web/PC/TV 直接不渲染。
        与上面 PC/TV 的更新块并列，不合并——两者的安装通道完全不同。
      -->
      <FpkUpgradePanel v-if="!desktop && !tvMode" />

    
      </template>
      </section>

    <!-- ══════════ 维护 ══════════ -->
    <section data-sec="maint" class="set-card flex shrink-0 flex-col">
      <template v-if="visitedSec('maint')">
      <div class="set-head">
        <h2 class="set-title">维护</h2>
        <span class="set-hint">备份 · 日志 · 重置 · 调试</span>
      </div>

      <!-- ── 远程调试（TV / PC）：原独立分区，归到维护 ── -->
      <template v-if="debugPlatform">
        <div class="set-sub mt-3">
          <span class="font-medium text-ink-2">调试</span>
          <span>
            {{
              debugPlatform === 'tv'
                ? '局域网 PC 可读取状态 / 截图 / 派发按键'
                : debugPlatform === 'ios'
                  ? '局域网 PC 可直连网关读取状态 / 调接口'
                  : '内置 MCP 服务，供外部工具读取状态 / 截图 / 派发按键'
            }}
          </span>
          <span class="flex-1" />
          <span
            class="text-[11px]"
            :class="dbg.running ? 'text-accent' : 'text-ink-3'"
          >
            {{ dbg.running ? '运行中' : dbg.enabled ? '待生效' : '已关闭' }}
          </span>
        </div>

        <!-- TV：可开关 -->
        <div v-if="debugPlatform === 'tv'" class="mt-2 flex flex-wrap items-center gap-2">
          <button
            class="rounded-md px-3 py-1.5 text-xs font-medium"
            :class="dbg.enabled
              ? 'bg-card hover:bg-card-hover'
              : 'bg-accent text-on-accent hover:bg-accent-dim'"
            @click="toggleTvDebug()"
          >
            {{ dbg.enabled ? '关闭远程调试' : '开启远程调试' }}
          </button>
          <span v-if="dbg.url" class="text-[11px] text-ink-3">{{ dbg.url }}</span>
        </div>

        <!-- PC：只读。地址 + 探测区间给客户端抄 -->
        <div v-else-if="debugPlatform === 'pc'" class="mt-2 space-y-1.5">
          <div>
            <span class="mb-1 block text-[11px] text-ink-3">服务地址</span>
            <code class="select-all rounded bg-field px-2 py-1 text-xs text-accent">
              {{ dbg.url || '未监听' }}
            </code>
          </div>
          <p v-if="dbg.portRange" class="text-[11px] text-ink-3">
            端口可能顺延，客户端在 {{ dbg.portRange }} 区间探测
            <code class="rounded bg-field px-1">/health</code>
          </p>
        </div>

        <!-- iOS：局域网调试开关（对齐 TV）+ 排障三件套 -->
        <div v-else class="mt-2 space-y-2">
          <div class="flex flex-wrap items-center gap-2">
            <button
              class="rounded-md px-3 py-1.5 text-xs font-medium"
              :class="dbg.enabled
                ? 'bg-card hover:bg-card-hover'
                : 'bg-accent text-on-accent hover:bg-accent-dim'"
              :disabled="dbg.busy"
              @click="toggleIosDebug()"
            >
              {{ dbg.busy ? '切换中…' : dbg.enabled ? '关闭远程调试' : '开启远程调试' }}
            </button>
            <span v-if="dbg.url" class="text-[11px] text-ink-3">{{ dbg.url }}</span>
          </div>
          <!-- 令牌只在回环请求里下发：局域网设备知道地址也拿不到 -->
          <div v-if="dbg.enabled && dbg.token" class="space-y-1">
            <span class="block text-[11px] text-ink-3">
              令牌（PC 侧放 X-Meow-Token 头；分隔符可省略）
            </span>
            <code class="select-all rounded bg-field px-2 py-1 text-xs tracking-wider text-accent">
              {{ groupToken(dbg.token) }}
            </code>
          </div>
        </div>

        <!-- 令牌要能被抄写：投影仪上没有 adb，验收方只能看屏幕。
             分隔符只是排版，服务端会去掉再比对（2026-11-18 起）。 -->
        <div v-if="debugPlatform === 'tv' && dbg.enabled && dbg.token" class="mt-2">
          <span class="mb-1 block text-[11px] text-ink-3">
            令牌（PC 侧放 X-Meow-Token 头；分隔符可省略）
          </span>
          <code class="select-all rounded bg-field px-2 py-1 text-xs tracking-wider text-accent">
            {{ groupToken(dbg.token) }}
          </code>
          <p v-if="dbg.running && dbg.expiresMin > 0" class="mt-1 text-[11px] text-ink-3">
            空闲 {{ dbg.idleMin }} 分钟自动关闭（剩约 {{ dbg.expiresMin }} 分）
          </p>
        </div>
        <p v-if="dbg.hint" class="mt-2 text-[11px] text-accent">{{ dbg.hint }}</p>
        <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
          <template v-if="debugPlatform === 'tv'">
            每次开启都会换新令牌，关闭后服务立即停止。
          </template>
          <template v-else-if="debugPlatform === 'ios'">
            开关关闭时网关只接受本机请求；打开后局域网 PC 用上面的地址直连（非本机请求
            都要带令牌），每次开启都会生成新令牌。App 切后台会被系统挂起，调试期间请保持前台。
            「图片全碎 / 按钮没反应」先试「备份与诊断」里的「重载界面」。
          </template>
          <template v-else>
            只监听 127.0.0.1，不对外网开放。
          </template>
        </p>
      </template>

      <!-- ── 数据与维护 ──
        ★ 展开站点明细时**不要**加 `min-h-0`。

        踩过（2026-10-30：「站点明细 3端看不见」）：当时写的是
        `showSites ? 'min-h-0 flex-1' : 'shrink-0'`。`min-h-0` 的用途是
        "让 flex 子项能被压到 0 以下"，加在这里的后果相反 ——
        父容器是 `flex flex-col`，`min-h-0` 会让它放弃内容撑高，
        在父级（右栏滚动容器，普通流）里被压成 30px，
        里面的列表容器算出来 clientHeight=0，整块不可见。
        展开时只需要"别被 shrink 掉"，用 `shrink-0` 就够；
        高度由列表的 min-h/max-h 自己决定。 -->
      <div class="set-sub mt-3 border-t border-line pt-3">
        <span class="font-medium text-ink-2">备份与诊断</span>
        <span>备份可搬走；日志与重置用来排障</span>
        <span class="flex-1" />
        <div class="flex items-center gap-1">
          <!-- iOS 排障：诊断信息与重载入口从「调试」区移到这里（TV 没有这两项，
               调试区保持两端一致：状态 / 开关 / 地址 / 令牌） -->
          <button
            v-if="ios"
            class="rounded-md px-2 py-1 text-[11px] text-ink-3 transition hover:bg-card-hover hover:text-ink"
            @click="copyIosDiagnostics()"
          >
            复制诊断
          </button>
          <button
            v-if="ios"
            class="rounded-md px-2 py-1 text-[11px] text-ink-3 transition hover:bg-card-hover hover:text-ink"
            @click="reloadWebView()"
          >
            重载界面
          </button>
          <button
            class="rounded-md px-2 py-1 text-[11px] text-ink-3 transition hover:bg-card-hover hover:text-ink"
            @click="exportBackup"
          >
            导出备份
          </button>
          <button
            class="rounded-md px-2 py-1 text-[11px] text-ink-3 transition hover:bg-card-hover hover:text-ink"
            @click="pickImport"
          >
            导入备份
          </button>
          <!-- 「站点明细」按钮已移到「搜索与源」区：它是"看/停用源"，
               和源配置是同一件事，留在维护区会让用户在两处找 -->
          <input ref="fileInput" type="file" accept=".json,application/json" class="hidden" @change="onImportFile" />
        </div>
      </div>

      <TilePanel :items="maintTiles" :cols="3" class="mt-2 shrink-0" />

      <p v-if="backupMsg" class="mt-2 text-[11px] text-ink-3">{{ backupMsg }}</p>
      <p v-if="diagMsg" class="mt-2 text-[11px] text-accent">{{ diagMsg }}</p>

    
      </template>
      </section>

      </div><!-- /右侧内容 -->
    </div><!-- /两栏网格 -->

    <!-- 「关于」：版本历史（运行时拉）+ 项目主页。Teleport 在组件内部，见 AboutDialog -->
    <AboutDialog
      v-if="showAbout"
      :version="appVersion"
      @close="closeAbout()"
      @open-project="openProjectPage()"
    />

    <!-- ══════════ 站点明细弹层 ══════════
      ★ 为什么用弹框而不是原地展开。

     用户原话（2026-10-30）：「要不使用弹框形式展示？」
     原地展开时 270 个站点把「数据与维护」这张卡撑到 4577px 高，
     整页被顶走，而且用户只是想"看一眼/停用某个源"，看完还要再收起。
     弹框是"临时查看"的正确形态：开→看/改→返回，页面布局不受影响。

     ★ 为什么要 Teleport 到 body（否则会被顶栏盖住）。

     用户反馈原话：「弹框会被顶栏遮挡啊。其他弹框也这样吗？」

     **其他弹框确实也有这个隐患**，只是它们的中央内容恰好没压到顶栏上，
     所以一直没人发现。机理是层叠上下文，不是 z-index 数字大小：

       · App.vue 里 `<main class="relative z-10 flex-1">` —— **position:relative
         + z-index:10 创建了一个层叠上下文**；
       · 弹框写在不 Teleport 的组件内 → 它是 main 的后代 →
         它的 `z-40` 只在 main 内部比较，对外 main 仍然是 z-10；
       · 顶栏是 `sticky z-30`，且**不在 main 里**（App.vue 里是 main 的兄弟）→
         30 > 10，顶栏整体压在弹框之上。实测顶栏 0–72px 完全盖住弹框标题行。

     所以"把 z 调大"永远修不好 —— 必须是 `z-10000` 也还在 main 内部。
     唯一解法是跳出这个上下文：`Teleport to="body"`。

     ★ 为什么这里 Teleport 是安全的（和 RateSelect 的约束不冲突）：
     `RateSelect` 不 Teleport 是因为**真全屏**（requestFullscreen）时浏览器
     只渲染全屏元素的后代，body 下的浮层会消失。而设置页永远不是全屏元素
     （全屏只在播放页/直播页发生），所以这里没有那个风险。
     将来若要把这套弹框复用到播放页，必须改回不 Teleport。
    -->
    <Teleport to="body">
    <div
      v-if="showSites"
      data-tv-scope
      class="fixed inset-0 z-[120] flex items-center justify-center bg-black/70 p-6"
      @click.self="closeSites()"
    >
      <div
        class="flex max-h-full w-full max-w-3xl flex-col overflow-hidden rounded-xl border border-line bg-base p-1.5"
        style="box-shadow: 0 24px 60px -12px rgba(0, 0, 0, 0.75)"
      >
        <div class="set-head shrink-0 px-2.5 pt-2.5">
          <h2 class="set-title">站点明细</h2>
          <span class="set-hint">{{ store.sites.length }} 个源</span>
          <span class="flex-1" />
          <button
            class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover"
            @click="probeSelected()"
            :disabled="probing"
            :title="siteSel.size ? `探测选中的 ${siteSel.size} 个源` : '探测当前筛选里的全部源'"
          >
            {{ probing ? '探活中…' : siteSel.size ? `探测选中(${siteSel.size})` : siteFilter === 'all' ? '检测全部' : '检测当前' }}
          </button>
          <button
            class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover"
            @click="closeSites()"
          >
            返回
          </button>
        </div>

        <!-- 筛选页签：按状态找源。270 个源靠滚动找不到的。 -->
        <div class="flex shrink-0 flex-wrap items-center gap-1 px-2.5 pt-2">
          <button
            v-for="t in siteFilterTabs"
            :key="t.id"
            class="rounded-md px-2 py-1 text-[11px] transition"
            :class="
              siteFilter === t.id
                ? 'bg-accent/20 text-accent'
                : 'text-ink-3 hover:bg-card-hover hover:text-ink'
            "
            @click="siteFilter = t.id; clearSel()"
          >
            {{ t.label }}<span class="ml-1 tabular-nums opacity-70">{{ t.n }}</span>
          </button>
          <input
            v-model="siteKeyword"
            placeholder="搜索名称 / api"
            class="ml-auto w-36 rounded-md border border-line bg-card-sunken px-2 py-1 text-[11px] text-ink-2 outline-none placeholder:text-ink-3/70"
          />
        </div>

        <!-- 批量操作条：只在有选中时出现，避免平时占一行 -->
        <div
          v-if="siteSel.size"
          class="mt-1.5 flex shrink-0 flex-wrap items-center gap-2 rounded-md bg-accent/10 px-2.5 py-1.5"
        >
          <span class="text-[11px] text-ink-2">已选 {{ selCount }} 个</span>
          <button
            class="rounded bg-hot/15 px-2 py-0.5 text-[11px] text-hot hover:bg-hot/25 disabled:opacity-40"
            :disabled="healthBusy === '__batch__'"
            @click="batchSetDisabled(true)"
          >
            批量停用
          </button>
          <button
            class="rounded bg-accent/15 px-2 py-0.5 text-[11px] text-accent hover:bg-accent/25 disabled:opacity-40"
            :disabled="healthBusy === '__batch__'"
            @click="batchSetDisabled(false)"
          >
            批量恢复
          </button>
          <button class="rounded px-2 py-0.5 text-[11px] text-ink-3 hover:bg-card-hover" @click="clearSel()">
            取消选择
          </button>
          <span class="flex-1" />
        </div>

        <!-- 一键处理当前筛选：这是最常用的入口 -->
        <div class="mt-1 flex shrink-0 flex-wrap items-center gap-2 px-2.5">
          <label class="flex cursor-pointer items-center gap-1.5 text-[11px] text-ink-3">
            <input
              type="checkbox"
              class="h-3 w-3 accent-accent"
              :checked="allVisibleSelected"
              @change="toggleSelAll()"
            />
            全选当前 {{ visibleSites.length }} 个
          </label>
          <button
            v-if="visibleSites.length"
            class="rounded px-2 py-0.5 text-[11px] text-hot hover:bg-hot/15"
            :disabled="healthBusy === '__batch__'"
            @click="disableAllVisible()"
          >
            停用当前全部
          </button>
          <span class="flex-1" />
          <button
            class="rounded px-2 py-0.5 text-[11px] text-ink-3 hover:bg-card-hover"
            @click="loadSearchCacheStats(); store.refreshSites(); loadHealthDetail()"
          >
            刷新状态
          </button>
        </div>

        <p class="shrink-0 px-2.5 pt-1.5 text-[11px] text-ink-3">
          停用后搜索 / 直播 / 探活都会跳过该源；恢复会同时清掉自动降级状态。
          「已跳过」是临时拉黑（10 分钟后自动重试），「已停用」才是永久生效。
        </p>
        <p
          v-if="healthMsg"
          class="shrink-0 px-2.5 pt-1 text-[11px] leading-relaxed"
          :class="healthMsgOk ? 'text-ok' : 'text-warn'"
        >
          {{ healthMsg }}
        </p>
        <div
          data-tv-scroll
          tabindex="0"
          class="mt-1.5 min-h-0 flex-1 overflow-y-auto rounded-lg bg-field px-3 outline-none"
        >
<!-- 「未生效」：配置里写了，但本构建加载不了（加密壳 / jar 未装载 / 缺类） -->
<ul v-if="siteFilter === 'pending'" class="divide-y divide-line text-[13px]">
          <li v-if="!pendingFiltered.length" class="py-6 text-center text-xs text-ink-3">
            没有未生效的源
          </li>
          <li
            v-for="p in pendingFiltered"
            :key="'pending-' + p.key"
            class="flex items-start gap-3 py-2"
          >
            <div class="flex min-w-0 flex-1 flex-col gap-0.5">
              <div class="flex min-w-0 items-center gap-2">
                <span class="shrink-0 text-ink">{{ p.name || p.key }}</span>
                <span class="shrink-0 rounded bg-warn/15 px-1.5 py-0.5 text-[10px] text-warn">
                  未生效
                </span>
                <span class="min-w-0 truncate text-[11px] text-ink-3" :title="p.reason">
                  {{ p.reason }}
                </span>
              </div>
              <div class="flex min-w-0 items-center gap-2 text-[10px] text-ink-3">
                <code class="min-w-0 max-w-[22rem] shrink truncate rounded bg-field px-1.5 py-0.5" :title="p.api">
                  {{ p.api }}
                </code>
              </div>
            </div>
          </li>
        </ul>
        <ul v-else class="divide-y divide-line text-[13px]">
          <li v-if="!visibleSites.length" class="py-6 text-center text-xs text-ink-3">
            没有符合条件的源
          </li>
          <li
            v-for="s in visibleSites"
            :key="s.key"
            class="flex items-center gap-3 py-2"
            :class="s.health?.degraded || s.health?.disabled ? 'opacity-70' : ''"
          >
            <!-- 复选框：批量操作的入口 -->
            <input
              type="checkbox"
              class="h-3.5 w-3.5 shrink-0 accent-accent"
              :checked="siteSel.has(s.key)"
              :title="'选择 ' + s.name"
              @change="toggleSel(s.key)"
            />
            <!--
              ★ 两行布局，不要挤成一行。

              踩过（2026-10-30 截图）：原来全塞在一行里（名称 / api / 状态 /
              计数 / ext），总宽超过弹框 → 出现横向滚动条，而且 api 被压成
              `https://raw.gl…` 完全看不出是哪个源。
              改成：第一行「名称 + 状态 + 计数」，第二行「api + ext」，
              每行都能各自截断，不再互相挤压。
            -->
            <div class="flex min-w-0 flex-1 flex-col gap-0.5">
              <div class="flex min-w-0 items-center gap-2">
                <span class="shrink-0 text-ink">{{ s.name }}</span>
                <span
                  class="shrink-0 rounded px-1.5 py-0.5 text-[10px]"
                  :class="healthClass(s)"
                >
                  {{ healthLabel(s) }}
                </span>
                <span
                  class="min-w-0 truncate text-[11px] text-ink-3"
                  :title="healthDetail(s) + detailTip(s)"
                >
                  {{ healthDetail(s) }}
                </span>
              </div>
              <div class="flex min-w-0 items-center gap-2 text-[10px] text-ink-3">
                <!-- api 可收缩 + 截断；title 保留全文 -->
                <code class="min-w-0 max-w-[22rem] shrink truncate rounded bg-field px-1.5 py-0.5" :title="s.api">
                  {{ s.api }}
                </code>
                <span v-if="s.ext" class="min-w-0 shrink truncate" :title="s.ext">{{ s.ext }}</span>
              </div>
            </div>
            <button
              class="shrink-0 rounded px-2 py-0.5 text-[11px] transition disabled:opacity-40"
              :class="
                s.health?.disabled
                  ? 'text-accent hover:bg-accent/15'
                  : 'text-ink-3 hover:bg-hot/15 hover:text-hot'
              "
              :disabled="healthBusy === s.key"
              :title="'调用 POST /api/health/degrade'"
              @click="toggleSiteDisabled(s)"
            >
              {{ healthBusy === s.key ? '…' : s.health?.disabled ? '恢复' : '停用' }}
            </button>
          </li>
        </ul>
        </div>
      </div>
    </div>
    </Teleport>

    <!-- ══════════ TV 连接设置弹层 ══════════
         用 fixed 遮罩 + 居中卡片，不加 Teleport：TV 全屏时只会渲染全屏元素的
         后代，Teleport 到 body 的浮层会看不见（RateSelect 踩过这个坑）。
         这里的祖先链没有全屏元素，留在组件内是安全的。

         ★ 但它有和「站点明细」同样的层叠问题：本弹层在 main（z-10）内部，
           顶栏（sticky z-30）在 main 外面 → 顶栏会盖住贴到 y<72 的部分。
           现在内容居中且上下留 24px，实测没压到顶栏，所以暂时不改；
           一旦发现标题被遮，按站点明细弹层那样加 Teleport 即可。 -->
    <div
      v-if="connDialog.open"
      data-tv-scope
      class="fixed inset-0 z-40 flex items-center justify-center bg-black/70 p-6"
      @click.self="closeConnDialog()"
    >
      <!-- ★ 面板必须用实心底色（bg-base）而不是 bg-card-sunken：
           后者是 rgba(255,255,255,0.03)，alpha 只有 0.03，弹层会把背后的
           设置页文字透上来（实测截图里"本机执行/远程调试"的字直接叠在
           弹层上，看起来像没有遮罩）。
           阴影用行内 style 而不是 shadow-2xl：实测 tailwind 的
           shadow-2xl + shadow-black/60 组合在这个项目里算出来是
           `0px 0px 0px 0`（不生效），直接写 box-shadow 最稳。 -->
      <div
        class="flex max-h-full w-full max-w-2xl flex-col overflow-hidden rounded-xl border border-line bg-base p-1.5"
        style="box-shadow: 0 24px 60px -12px rgba(0, 0, 0, 0.75)"
      >
        <div class="set-head shrink-0 px-2.5 pt-2.5">
          <h2 class="set-title">连接设置</h2>
          <span class="set-hint">遥控器不用打字</span>
          <span class="flex-1" />
          <button
            class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover"
            @click="closeConnDialog()"
          >
            返回
          </button>
        </div>

        <!--
          ★ 不标 data-tv-scroll / tabindex（2026-11-04 审计）。
            这块里有「重新扫描」和扫描到的设备按钮，容器再当停靠点会抢焦点：
            MuMu 实测「设备按钮 ↓」落到 DIV（画一根左竖条）上，再按才进下一个
            控件。滚动由 scrollIntoViewWithin 跟着焦点走；设备列表为空、
            整块没有可聚焦元素时，tv-nav 的 autoScrollAnchor 会自动补停靠点。

          ★ 高度按视口算（不写 max-h-full）：max-h-full 相对父容器 content-box
            算，而父级有 p-6；TV 视口只有 640px 高时留给二维码的空间仍不够，
            超出的部分滚不到（踩过："弹框撑开后显示不全被遮挡"）。
        -->
        <div
          class="min-h-0 flex-1 overflow-y-auto px-2.5 pb-2.5 outline-none"
          style="max-height: calc(100vh - 9rem)"
        >
          <!-- 局域网里的猫窝服务 -->
          <div class="flex items-center justify-between">
            <span class="text-xs text-ink-2">局域网里的猫窝服务</span>
            <button
              class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover"
              :disabled="connDialog.scanning"
              @click="startConnScan()"
            >
              {{ connDialog.scanning ? '扫描中…' : '重新扫描' }}
            </button>
          </div>
          <div class="mt-1.5 flex flex-col gap-1">
            <button
              v-for="f in connDialog.found"
              :key="f.url"
              class="w-full rounded-md border border-line bg-card px-3 py-2 text-left text-xs hover:border-accent/50 hover:bg-card-hover focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent"
              @click="pickFound(f)"
            >
              {{ f.label || f.url }}
              <span class="ml-2 text-[11px] text-ink-3">v{{ f.version }}</span>
            </button>
            <p v-if="connDialog.scanning" class="text-[11px] text-ink-3">扫描中，请稍候…</p>
          </div>

          <!-- 手动填写 -->
          <div class="mt-4">
            <span class="mb-1 block text-[11px] text-ink-3">后端地址</span>
            <input
              v-model="connDialog.api"
              class="w-full rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs outline-none transition focus:border-accent"
              placeholder="http://192.168.1.10:30010"
            />
          </div>
          <div class="mt-2">
            <span class="mb-1 block text-[11px] text-ink-3">CatVod 配置地址（可选）</span>
            <input
              v-model="connDialog.config"
              class="w-full rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs outline-none transition focus:border-accent"
              placeholder="http://192.168.1.10:30010/config.json"
            />
          </div>

          <!-- 手机扫码填写已拆成独立入口（连接区的「扫码填写」按钮）：
               它承担的不只是"填后端地址"，还包括改设置，混在这里会让
               二维码旁边挤着地址框和扫描列表，看不出它能干两件事。 -->

          <p v-if="connDialog.msg" class="mt-3 text-[11px] text-accent">{{ connDialog.msg }}</p>
        </div>

        <div class="shrink-0 border-t border-line px-4 py-3">
          <button
            class="w-full rounded-md bg-accent px-3 py-2 text-sm font-medium text-on-accent hover:bg-accent-dim"
            @click="saveConnAndReload()"
          >
            保存并重连
          </button>
        </div>
      </div>
    </div>

    <!-- ══════════ PC / Web 扫描局域网弹框 ══════════
      ★ 必须 Teleport 到 body：本弹层在 App.vue 的 `<main class="relative z-10">`
        内部，顶栏是 `sticky z-30` 且在外面，不跳出去会被顶栏压住
        （扫码弹层踩过，见下方注释）。

      ★ 与 TV 连接弹层同构：扫描列表 → 选中填入 → 「保存并重连」。
        区别只在探测通道（这里是页面 fetch，TV 是原生 socket）。
    -->
    <Teleport to="body">
      <div
        v-if="webScan.open"
        class="fixed inset-0 z-[120] flex items-center justify-center bg-black/70 p-6"
        @click.self="closeWebScanDialog()"
      >
        <div
          class="flex max-h-full w-full max-w-xl flex-col overflow-hidden rounded-xl border border-line bg-base p-1.5"
          style="box-shadow: 0 24px 60px -12px rgba(0, 0, 0, 0.75)"
        >
          <div class="set-head shrink-0 px-2.5 pt-2.5">
            <h2 class="set-title">连接后端</h2>
            <span class="set-hint">扫描局域网 · 手填地址</span>
            <span class="flex-1" />
            <button
              class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover"
              @click="closeWebScanDialog()"
            >
              关闭
            </button>
          </div>

          <div
            class="min-h-0 flex-1 overflow-y-auto px-2.5 pb-2.5"
            style="max-height: calc(100vh - 9rem)"
          >
            <div class="flex items-center justify-between">
              <span class="text-xs text-ink-2">局域网里的猫窝服务</span>
              <button
                class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover disabled:opacity-40"
                :disabled="webScan.scanning"
                @click="startWebScan()"
              >
                {{ webScan.scanning ? '扫描中…' : '重新扫描' }}
              </button>
            </div>

            <div class="mt-1.5 flex flex-col gap-1">
              <button
                v-for="f in webScan.found"
                :key="f.url"
                class="w-full rounded-md border border-line bg-card px-3 py-2 text-left text-xs transition hover:border-accent/50 hover:bg-card-hover focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent"
                :title="`版本 ${f.version} · ${f.sites} 个站点，点击填入地址`"
                @click="pickWebFound(f)"
              >
                <span class="font-mono">{{ f.url }}</span>
                <span class="ml-2 text-[11px] text-ink-3">
                  v{{ f.version }} · {{ f.sites }} 站点
                </span>
              </button>
              <p v-if="webScan.scanning" class="text-[11px] text-ink-3">扫描中，请稍候…</p>
              <p v-else-if="webScan.msg" class="text-[11px] text-ink-3">{{ webScan.msg }}</p>
            </div>

            <div class="mt-4">
              <span class="mb-1 block text-[11px] text-ink-3">后端地址</span>
              <input
                v-model="webScan.api"
                class="w-full rounded-md border border-line bg-field px-2.5 py-1.5 font-mono text-xs outline-none transition focus:border-accent"
                placeholder="http://192.168.1.10:30010"
                @keyup.enter="saveWebScan()"
              />
              <p class="mt-1.5 text-[11px] leading-relaxed text-ink-3">
                {{
                  desktop
                    ? '留空表示由「影视后端」插件自动分配；填了就切到那台后端。'
                    : '留空 = 同源 /api；填了就切到那台后端。'
                }}
              </p>
            </div>
          </div>

          <div class="shrink-0 border-t border-line px-4 py-3">
            <button
              class="w-full rounded-md bg-accent px-3 py-2 text-sm font-medium text-on-accent hover:bg-accent-dim disabled:opacity-40"
              :disabled="apiBusy"
              @click="saveWebScan()"
            >
              {{ apiBusy ? '连接中…' : '保存并重连' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ══════════ 扫码填写弹层 ══════════
      ★ 左码右清单的双栏布局。
        用户扫之前要能看到"这个码能改什么"——只给一个二维码，他得先扫进去
        才发现能改弹幕、主题，等于把发现成本推给了手机端。
        右栏直接列出与手机页一致的条目，扫码前就能判断值不值得扫。

      ★ 必须 Teleport 到 body，且**只靠 z-index 压过顶栏**，不下移。
        踩过（实测）：不 Teleport 时卡片量到 top=54.9，而顶栏底边是 72，
        标题行被盖住 17px。原因是层叠上下文，不是 z 数字大小：
        App.vue 的 `<main class="relative z-10">` 建了上下文，弹层作为它的
        后代，`z-[110]` 只在 main 内部比较，对外仍是 z-10；顶栏 `sticky z-30`
        在 main 外面，30 > 10 就压在弹层之上。所以调多大都没用 ——
        必须跳出 main（Teleport），跳出后才轮到 z-[120] 真的生效。
        不加 mt/padding 下移：用户要的是"居中"，不是"挪开"。 -->
    <Teleport to="body">
    <div
      v-if="scanDialog.open"
      data-tv-scope
      data-tv-scope-idle-close
      class="fixed inset-0 z-[120] flex items-center justify-center bg-black/70 p-6"
      @click.self="closeScanDialog()"
    >
      <div
        class="flex max-h-full w-full max-w-3xl flex-col overflow-hidden rounded-xl border border-line bg-base"
        style="box-shadow: 0 24px 60px -12px rgba(0, 0, 0, 0.75)"
      >
        <div class="set-head shrink-0 px-4 pt-3">
          <h2 class="set-title">手机扫码填写</h2>
          <span class="set-hint">电视上不用打字，改完立即生效</span>
          <span class="flex-1" />
          <button
            class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover"
            @click="closeScanDialog()"
          >
            返回
          </button>
        </div>

        <div
          class="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-4 pb-4 md:flex-row md:overflow-hidden"
        >
          <!-- 左：二维码 + 地址。宽屏下固定不滚：二维码和令牌是要边看边扫的，
               跟着右侧清单一起滚走等于每次都要滚回来找。 -->
          <div class="flex shrink-0 flex-col items-center gap-2 md:w-[15rem] md:self-start">
            <template v-if="scanDialog.url">
              <div class="rounded-lg bg-white p-2" v-html="qrSvg(scanDialog.url, 176)" />
              <code class="w-full break-all rounded-md bg-field px-2 py-1.5 text-center font-mono text-[11px] text-accent">
                {{ scanDialog.url }}
              </code>
              <!-- 令牌单独放大显示：手输 URL 时要用到它，而它藏在地址末尾
                   容易被看漏。每次开启推送都会换新的。 -->
              <div
                v-if="scanDialog.token"
                class="w-full rounded-md bg-field px-2 py-1.5 text-center"
              >
                <div class="text-[10px] text-ink-3">访问令牌（手输地址时填这个）</div>
                <div class="mt-0.5 select-all font-mono text-[length:var(--fs-lg)] tracking-[0.2em] text-accent">
                  {{ scanDialog.token }}
                </div>
              </div>
              <p class="text-center text-[11px] leading-relaxed text-ink-3">
                手机相机或微信扫上面二维码<br />（需与电视同一 WiFi）
              </p>
            </template>
            <template v-else>
              <div class="flex h-[11rem] w-[11rem] items-center justify-center rounded-lg border border-line bg-field">
                <span class="px-3 text-center text-[11px] text-ink-3">
                  {{ scanDialog.msg || '正在启动推送服务…' }}
                </span>
              </div>
            </template>
          </div>

          <!-- 右：这个码能改什么（与手机页表单逐项对应）。宽屏下自己滚，
               左边不动 —— 见左侧注释。 -->
          <div class="min-h-0 min-w-0 flex-1 md:overflow-y-auto md:pr-1">
            <p class="mb-2 text-[11px] leading-relaxed text-ink-3">
              手机页面上能改下面这些项。<b>只提交你改动过的</b>，
              没动的不会覆盖电视上的设置。
            </p>
            <div class="grid grid-cols-2 gap-x-4 gap-y-2.5">
              <div v-for="g in SCAN_PUSH_ITEMS" :key="g.group" class="min-w-0">
                <div class="text-[11px] font-medium text-ink-2">{{ g.group }}</div>
                <ul class="mt-1 space-y-0.5">
                  <li
                    v-for="it in g.items"
                    :key="it"
                    class="flex items-start gap-1.5 text-[11px] leading-relaxed text-ink-3"
                  >
                    <span class="mt-[0.35rem] h-1 w-1 shrink-0 rounded-full bg-accent/60" />
                    <span class="min-w-0">{{ it }}</span>
                  </li>
                </ul>
              </div>
            </div>
            <p class="mt-3 rounded-md bg-field px-2.5 py-1.5 text-[11px] leading-relaxed text-ink-3">
              手机页需要<b>访问令牌</b>才能读写；每次开启推送都会换新令牌，
              扫上面的码会自动带入，手输地址时填上面那 8 位。
            </p>
          </div>
        </div>
      </div>
    </div>
    </Teleport>
  </div>
</template>
