/**
 * 更新中心：壳 / TV 运行时模块 / PC 插件 / fnOS fpk 的统一检查状态。
 *
 * <p>这里只做「检查 + 展示状态」，**不合并安装器**：
 * 壳走 exe/apk 安装器、模块走系统 APK 安装器、插件走目录切换、
 * fpk 走 meowd + 应用中心。任何一项失败都不能阻塞其他项。
 *
 * <p>状态放 store 而不是 SettingsView：
 *  - 启动后台检查在 App.vue 里跑，和设置页是两棵组件树；
 *  - 更新中心与旧的分区共用同一份结果，避免两处显示不一致。
 */

import { reactive, ref } from 'vue'
import { isDesktop } from '@/api'
import {
  checkAppUpdate,
  downloadCandidates,
  fetchTextViaNativeJob,
  getBackupUpdateSource,
  getUpdateSource,
  getUpdateToken,
  isNewer,
  isReleaseEndpoint,
} from '@/api/appUpdate'
import {
  addPluginSource,
  fetchInstalledPlugins,
  fetchPluginIndex,
  fetchPluginSources,
  selectPluginSource,
  updatePluginSource,
} from '@/api'
import {
  checkFpkUpdate,
  type FpkLatest,
} from '@/api/fpkUpgrade'
import { getPrefs } from '@/stores/prefs'
import { fpkInfo, fpkInstalled, probeCapability } from '@/stores/appUpdate'
import { hasTvModule, isTvShell } from '@/compat/shell'

export { hasTvModule, isTvShell }

/*
 * python = TV 的 Python 运行时模块（com.meow.runtime.python）。
 *
 * ★ 2026-11-28 补（用户实测）：node 模块与 Python 模块是**两条独立版本线**，
 *   但以前只有 node 那条进了更新中心 —— TV 的「检查全部」查不到 Python 模块，
 *   用户必须先摸到「设置 → 运行时」里单独点，与「一处看全」的定位不符。
 */
export type UpdateKind = 'app' | 'module' | 'python' | 'payload' | 'plugins' | 'fpk'

export interface ModuleLatest {
  version: string
  size: number
  abi: string
  sha256: string
  url: string
}

export interface PluginLatest {
  id: string
  name: string
  current: string
  latest: string
  size: number
}

const LAST_KEY = 'meow.update.lastCheck'
const DAY = 24 * 60 * 60 * 1000

/** 每个渠道的检查中状态 */
export const checking = reactive<Record<UpdateKind, boolean>>({
  app: false,
  module: false,
  python: false,
  payload: false,
  plugins: false,
  fpk: false,
})

/** 每个渠道最后一次错误（成功时清空） */
export const errors = reactive<Record<UpdateKind, string>>({
  app: '',
  module: '',
  python: '',
  payload: '',
  plugins: '',
  fpk: '',
})

/** 每个渠道最后一次检查时间（毫秒） */
export const lastCheckedAt = reactive<Record<UpdateKind, number>>({
  app: 0,
  module: 0,
  python: 0,
  payload: 0,
  plugins: 0,
  fpk: 0,
})

export const moduleLatest = ref<ModuleLatest | null>(null)
export const moduleCurrent = ref('')
export const moduleInstalled = ref(false)
/** 是否读过模块安装状态（本地桥，无网络；区分“没装”和“没查过”） */
export const moduleChecked = ref(false)
/**
 * 本次会话是否真的比对过模块版本。
 *
 * <p>和 moduleChecked 分开：读安装状态是本地桥调用，页面一打开就能做；
 * 比对版本要走网络。两者合用一个标志会出现「已是最新」其实根本没查过，
 * 或「未安装 · 未检查」两条状态叠在一起。
 */
export const moduleUpdateChecked = ref(false)

/* ── TV 的 Python 运行时模块（另一条版本线，桥方法 pyModule*） ── */
export const pythonLatest = ref<ModuleLatest | null>(null)
export const pythonCurrent = ref('')
export const pythonInstalled = ref(false)
/** 是否读过 Python 模块安装状态（本地桥，无网络） */
export const pythonChecked = ref(false)
/** 本次会话是否真的比对过 Python 模块版本（与 pythonChecked 分开，理由同模块） */
export const pythonUpdateChecked = ref(false)
export const payloadLatest = ref<{
  version: string
  size: number
  sha256: string
  url: string
} | null>(null)
export const payloadCurrent = ref('')
export const payloadInstalled = ref(false)
/** 是否读过载荷安装状态（本地桥，无网络） */
export const payloadChecked = ref(false)
/** 本次会话是否真的比对过载荷版本（与 payloadChecked 分开，理由同模块） */
export const payloadUpdateChecked = ref(false)

export const pluginUpdates = ref<PluginLatest[]>([])
/** 是否已配置插件源（用于 UI 区分“没源”和“没更新”） */
export const pluginConfigured = ref(false)
/** 已安装插件数（0 = 未安装，不是“没更新”） */
export const pluginInstalledCount = ref(0)
/** 是否做过一次插件检查（区分“未检查”和“未安装”） */
export const pluginChecked = ref(false)
export const fpkLatest = ref<FpkLatest | null>(null)
export const fpkCurrent = ref('')
export const fpkVisible = ref(false)
/** 是否做过一次 fpk 探测（区分“未检查”和“不是 fpk 壳”） */
export const fpkChecked = ref(false)

function readLast(): Record<string, number> {
  try {
    const raw = JSON.parse(localStorage.getItem(LAST_KEY) || '{}') as Record<string, unknown>
    const out: Record<string, number> = {}
    for (const k of Object.keys(raw)) {
      const n = Number(raw[k])
      if (Number.isFinite(n) && n > 0) out[k] = n
    }
    return out
  } catch {
    return {}
  }
}

function initLast(): void {
  const raw = readLast()
  for (const k of Object.keys(lastCheckedAt) as UpdateKind[]) {
    lastCheckedAt[k] = raw[k] || 0
  }
}
initLast()

function mark(kind: UpdateKind): void {
  lastCheckedAt[kind] = Date.now()
  try {
    localStorage.setItem(LAST_KEY, JSON.stringify(lastCheckedAt))
  } catch {
    /* 配额满不影响检查结果 */
  }
}

/** 壳检查由 appUpdate store 负责，检查完在这里记一次时间 */
export function markChecked(kind: UpdateKind): void {
  mark(kind)
}

function setError(kind: UpdateKind, e: unknown, silent: boolean): void {
  const msg = e instanceof Error ? e.message : String(e || '未知错误')
  errors[kind] = msg
  if (!silent) console.warn(`[update] ${kind} 检查失败：`, msg)
}

/** 自动检查是否到期（默认 24h；手动检查不经过这里） */
export function autoCheckDue(kind: UpdateKind, maxAgeMs = DAY): boolean {
  const p = getPrefs()
  /*
   * python 复用 autoCheckModule 开关：UI 上只有一个「运行时」勾选框，
   * 两个模块共用一条 24h 节流会少打一次网络，也符合"模块一起查"的直觉。
   */
  const enabled =
    kind === 'app'
      ? p.autoCheckApp
      : kind === 'module' || kind === 'python'
        ? p.autoCheckModule
        : kind === 'plugins'
          ? p.autoCheckPlugins
          : p.autoCheckFpk
  if (!enabled) return false
  // 平台不支持时不算“到期”，否则每次启动都白跑一次（且永远不会 mark）
  if ((kind === 'module' || kind === 'python') && !hasTvModule()) return false
  if (kind === 'plugins' && !isDesktop()) return false
  if (kind === 'fpk' && (isDesktop() || isTvShell())) return false
  return Date.now() - (lastCheckedAt[kind] || 0) >= maxAgeMs
}

/**
 * TV 原生桥（老壳可能没有 moduleCheck）。
 *
 * ★ 身份与能力分开（D-2）：`isTvShell()` 判断「是不是 TV 壳」，
 *   `hasTvModule()` 判断「有没有 moduleCheck」。两者都由 `compat/shell.ts`
 *   唯一定义，这里只 re-export，方便更新中心的组件按需选用。
 */
function tvBridge(): any {
  try {
    return (window as unknown as { meowTv?: any }).meowTv || null
  } catch {
    return null
  }
}

/**
 * 用桥方法取清单（模块 / 载荷共用）。
 *
 * <p><b>为什么候选要一次交给原生</b>：`AppUpdater.fetchManifestAny` 是**并发竞速**
 * （总耗时 ≈ 最慢的那条）。JS 侧串行逐个试时，直连被墙要等满 15s 才轮到下一个，
 * 实测一次「检查」要 30–40 秒 —— 而且是同步桥，整页冻住。
 *
 * <p><b>为什么优先走 netJob</b>：`moduleCheckCandidates` 是**同步**桥，网络差时
 * 整页（包括「检查中 0s」的计时）都会停住，用户看到的就是「卡住」。
 * 新壳有 `netJobStart`（非阻塞）时先用它拿清单原文，再交给
 * `moduleCheckFromText` / `payloadCheckFromText` 转成 pick 结果。
 *
 * <p><b>为什么 release 端点形态仍走逐条</b>：带 pipe 的通道只认正文含 `"packages"`
 * 的响应（代理的 HTML 错误页会 200），会把 release 元数据 JSON 当失败丢掉；
 * 单地址通道才会再解析一层资产。用户手填 `api.github.com/.../releases/latest`
 * 时必须保留那条路径。
 */
async function callBridgeManifest(
  b: any,
  methods: { pipe: string; fromText: string },
  src: string,
  token: string,
  cands: string[],
): Promise<Record<string, unknown>> {
  const parse = (s: unknown): Record<string, unknown> => {
    try {
      return JSON.parse(String(s || '{}')) as Record<string, unknown>
    } catch {
      return { ok: false, error: '清单解析失败' }
    }
  }
  const single = (): Record<string, unknown> => {
    let out: Record<string, unknown> = { ok: false }
    for (const c of cands) {
      out = parse(b[methods.pipe](c, token, ''))
      if (out.ok !== false) break
    }
    return out
  }

  if (!isReleaseEndpoint(src)) {
    // ① 非阻塞：并发竞速拿原文（不冻页面）
    if (b.netJobStart && b.netJobPoll && typeof b[methods.fromText] === 'function') {
      try {
        // ★ 检查只给 25 秒：候选是**并发**的，正常网络 1-5 秒就出结果；
        //   给 150 秒（下载用的默认值）等于网络全挂时干等两分半，
        //   用户看到的就是「检查更新卡住」。
        const text = await fetchTextViaNativeJob('manifest-any', cands.join('|'), token, 25_000)
        const one = parse(b[methods.fromText](text))
        if (one.ok !== false) return one
        return one
      } catch (e) {
        // ★ 走了 netJob 就**不再退同步**：原生已经把所有候选并发试过一遍，
        //   再串行试一轮只会把等待时间翻倍（旧行为就是"卡住"的来源）。
        return { ok: false, error: e instanceof Error ? e.message : String(e) }
      }
    }
    // ② 老壳：同步 pipe（原生内部并发），再退回逐条
    if (typeof b[methods.pipe] === 'function' && cands.length > 1) {
      const one = parse(b[methods.pipe](src, token, cands.join('|')))
      if (one.ok !== false) return one
    }
  }
  return single()
}

/**
 * 只读一次模块安装状态（本地桥，不发请求）。
 *
 * <p>为什么要单独做：更新中心原先把「版本未知」显示成「未安装」，
 * 再配一句「未检查」—— 用户看到「未安装 未检查」两条并列状态，
 * 实际上模块可能正跑着，只是页面还没查过。打开设置页时先读状态，
 * 就能只显示一条准确结论：未安装，或版本号 + 未检查。
 */
export function hydrateModuleState(): void {
  if (!hasTvModule()) return
  try {
    const b = tvBridge()
    const raw = b.moduleState?.()
    if (!raw) return
    const state = JSON.parse(String(raw))
    moduleChecked.value = true
    moduleInstalled.value = !!state.installed
    moduleCurrent.value = String(state.version || '')
  } catch {
    /* 桥返回异常就保持“未检查”，不猜安装状态 */
  }
}

/**
 * 读 TV Python 模块的安装状态（本地桥，无网络）。
 *
 * <p>老壳没有 `pyModuleState` 时整行不显示（与 node 模块同一套能力门禁）；
 * 桥异常就保持“未检查”，不猜安装状态。
 */
export function hydratePythonState(): void {
  if (!hasTvModule()) return
  try {
    const b = tvBridge()
    if (typeof b.pyModuleState !== 'function') return
    const raw = b.pyModuleState()
    if (!raw) return
    const state = JSON.parse(String(raw))
    pythonChecked.value = true
    pythonInstalled.value = !!state.installed
    pythonCurrent.value = String(state.version || '')
  } catch {
    /* 桥返回异常就保持“未检查” */
  }
}

/** 应用壳更新（PC/TV）。复用 appUpdate store 的结果，避免两份状态 */
export async function checkApp(silent = false): Promise<boolean> {
  if (checking.app) return false
  checking.app = true
  errors.app = ''
  try {
    const up = await checkAppUpdate()
    // appUpdate store 会在设置页/弹框里自己显示 found；这里只记时间
    mark('app')
    return !!up
  } catch (e) {
    setError('app', e, silent)
    return false
  } finally {
    checking.app = false
  }
}

/** TV 运行时模块；未安装时只报告状态，不产生“可更新” */
export async function checkModule(silent = false): Promise<boolean> {
  if (!hasTvModule() || checking.module) return false
  const b = tvBridge()
  checking.module = true
  errors.module = ''
  // 新一轮检查开始：上一次的“已是最新”结论先作废，失败时不能继续冒充
  moduleUpdateChecked.value = false
  try {
    const state = JSON.parse(String(b.moduleState?.() || '{}'))
    moduleChecked.value = true
    moduleInstalled.value = !!state.installed
    moduleCurrent.value = String(state.version || '')
    if (!state.installed) {
      moduleLatest.value = null
      return false
    }
    const src = (await getUpdateSource()) || ''
    const tok = getUpdateToken()
    // ★ 清单也走全局加速（2026-11-12）：模块清单在 GitHub 上，被墙用户直连
    //   必失败。候选由 downloadCandidates 按「GitHub 加速」算好。
    // ★ 桥方法名带 Candidates：WebView 桥不支持同名重载；老壳回退两参版。
    const cands = downloadCandidates(src)
    const METHODS = { pipe: 'moduleCheckCandidates', fromText: 'moduleCheckFromText' }
    let chk: Record<string, unknown>
    if (typeof b.moduleCheckCandidates === 'function') {
      chk = await callBridgeManifest(b, METHODS, src, tok, cands)
    } else {
      try {
        chk = JSON.parse(String(b.moduleCheck(src, tok) || '{}')) as Record<string, unknown>
      } catch {
        chk = { ok: false, error: '检查失败' }
      }
    }
    // 显式备用源：主源失败才试（与应用更新同一份 Prefs）
    const backup = getBackupUpdateSource()
    if (chk.ok === false && backup && backup !== src) {
      if (typeof b.moduleCheckCandidates === 'function') {
        chk = await callBridgeManifest(b, METHODS, backup, tok, downloadCandidates(backup))
      } else {
        try {
          chk = JSON.parse(String(b.moduleCheck(backup, tok) || '{}')) as Record<string, unknown>
        } catch {
          chk = { ok: false, error: '检查失败' }
        }
      }
    }
    if (chk.ok === false) throw new Error(String(chk.error || '检查失败'))
    const latestVersion = String(chk.version || '')
    if (!latestVersion) throw new Error('清单里没有模块版本')
    const has = isNewer(latestVersion, moduleCurrent.value)
    moduleLatest.value = has
      ? {
          version: latestVersion,
          size: Number(chk.size) || 0,
          abi: String(chk.abi || state.abi || ''),
          sha256: String(chk.sha256 || ''),
          url: String(chk.url || ''),
        }
      : null
    moduleUpdateChecked.value = true
    mark('module')
    return has
  } catch (e) {
    setError('module', e, silent)
    moduleLatest.value = null
    return false
  } finally {
    checking.module = false
  }
}

/**
 * TV Python 运行时模块（`com.meow.runtime.python`）：与 node 模块完全独立的版本线。
 *
 * <p>桥只有两参版本（`pyModuleCheck(src, tok)`，没有 Candidates 变体）——
 * 与「设置 → 运行时」里的安装按钮走同一条路；显式备用源照旧在主源失败时再试。
 */
export async function checkPython(silent = false): Promise<boolean> {
  if (!hasTvModule() || checking.python) return false
  const b = tvBridge()
  if (typeof b.pyModuleState !== 'function' || typeof b.pyModuleCheck !== 'function') return false
  checking.python = true
  errors.python = ''
  // 新一轮检查开始：上一次的「已是最新」结论先作废
  pythonUpdateChecked.value = false
  try {
    const state = JSON.parse(String(b.pyModuleState() || '{}'))
    pythonChecked.value = true
    pythonInstalled.value = !!state.installed
    pythonCurrent.value = String(state.version || '')
    if (!state.installed) {
      pythonLatest.value = null
      return false
    }
    const src = (await getUpdateSource()) || ''
    const tok = getUpdateToken()
    const run = (s: string): Record<string, unknown> => {
      try {
        return JSON.parse(String(b.pyModuleCheck(s, tok) || '{}')) as Record<string, unknown>
      } catch {
        return { ok: false, error: '检查失败' }
      }
    }
    let chk = run(src)
    const backup = getBackupUpdateSource()
    if (chk.ok === false && backup && backup !== src) chk = run(backup)
    if (chk.ok === false) throw new Error(String(chk.error || '检查失败'))
    const latestVersion = String(chk.version || '')
    if (!latestVersion) throw new Error('清单里没有 Python 模块版本')
    const has = isNewer(latestVersion, pythonCurrent.value)
    pythonLatest.value = has
      ? {
          version: latestVersion,
          size: Number(chk.size) || 0,
          abi: String(chk.abi || state.abi || ''),
          sha256: String(chk.sha256 || ''),
          url: String(chk.url || ''),
        }
      : null
    pythonUpdateChecked.value = true
    mark('python')
    return has
  } catch (e) {
    setError('python', e, silent)
    pythonLatest.value = null
    return false
  } finally {
    checking.python = false
  }
}

function verCmp(a: string, b: string): number {
  const pa = String(a || '').split('.').map((x) => parseInt(x, 10) || 0)
  const pb = String(b || '').split('.').map((x) => parseInt(x, 10) || 0)
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0)
    if (d !== 0) return d
  }
  return 0
}

/** 确保“跟随应用更新源”已经落成一条插件源（PC 插件安装器仍然独立） */
async function ensurePluginAppSource(): Promise<void> {
  if (!isDesktop() || !getPrefs().pluginUseAppSource) return
  const url = (await getUpdateSource()) || ''
  if (!url) return
  const list = await fetchPluginSources()
  if (!list) return
  const hit = list.items.find((s) => s.url === url)
  if (hit) {
    // 已有自定义源在用时不要抢当前选择；只在“没选源”时补上
    if (!list.current) await selectPluginSource(hit.id)
    return
  }
  const named = list.items.find((s) => s.name === '应用更新源')
  if (named) {
    await updatePluginSource(named.id, url, '应用更新源')
    if (list.current !== named.id) await selectPluginSource(named.id)
    return
  }
  // 只有用户还没配源时才自动加；已有自定义源不抢当前选择
  if (!list.items.length) await addPluginSource(url, '应用更新源')
}

/** PC 插件更新（仅已安装的插件逐条比较） */
/**
 * 检查弹幕载荷（danmu_api）是否有新版本。
 *
 * <p>载荷与运行时模块分开装：模块是通用 node 运行时（按 ABI，~50MB），
 * 载荷是可替换的业务代码（~10MB，与 PC 的 danmu-api 插件同一个 zip）。
 * 只有模块已安装时才有意义 —— 没模块时载荷根本跑不起来。
 */
export function refreshPayloadState(): void {
  const b = tvBridge()
  if (!b || typeof b.payloadState !== 'function') return
  try {
    const st = JSON.parse(String(b.payloadState() || '{}')) as Record<string, unknown>
    payloadChecked.value = true
    payloadInstalled.value = !!st.installed
    payloadCurrent.value = String(st.version || '')
  } catch {
    /* 桥坏了就当没有 */
  }
}

export async function checkPayload(silent = false): Promise<boolean> {
  if (!hasTvModule() || checking.payload) return false
  const b = tvBridge()
  if (!b || typeof b.payloadCheckCandidates !== 'function') return false
  checking.payload = true
  errors.payload = ''
  payloadUpdateChecked.value = false
  try {
    refreshPayloadState()
    if (!payloadInstalled.value) {
      payloadLatest.value = null
      return false
    }
    const src = (await getUpdateSource()) || ''
    const tok = getUpdateToken()
    // 与 checkModule 同款：候选一次交给原生并发竞速（见 callBridgeManifest 的注释）
    const cands = downloadCandidates(src)
    const METHODS = { pipe: 'payloadCheckCandidates', fromText: 'payloadCheckFromText' }
    let chk: Record<string, unknown> = await callBridgeManifest(b, METHODS, src, tok, cands)
    // 显式备用源：主源失败才试（与应用更新同一份 Prefs）
    const backup = getBackupUpdateSource()
    if (chk.ok === false && backup && backup !== src) {
      chk = await callBridgeManifest(b, METHODS, backup, tok, downloadCandidates(backup))
    }
    if (chk.ok === false) throw new Error(String(chk.error || '检查失败'))
    const latest = String(chk.version || '')
    if (!latest) throw new Error('清单里没有弹幕组件版本')
    const has = isNewer(latest, payloadCurrent.value)
    payloadLatest.value = has
      ? {
          version: latest,
          size: Number(chk.size) || 0,
          sha256: String(chk.sha256 || ''),
          url: String(chk.url || ''),
        }
      : null
    payloadUpdateChecked.value = true
    mark('payload')
    return has
  } catch (e) {
    setError('payload', e, silent)
    payloadLatest.value = null
    return false
  } finally {
    checking.payload = false
  }
}

export async function checkPlugins(silent = false): Promise<boolean> {
  if (!isDesktop() || checking.plugins) return false
  checking.plugins = true
  errors.plugins = ''
  pluginConfigured.value = false
  try {
    const installed = await fetchInstalledPlugins()
    pluginChecked.value = true
    pluginInstalledCount.value = Object.keys(installed || {}).length
    if (!installed || !Object.keys(installed).length) {
      // 没装插件就不拉远端索引（设置页启动时的黑框/网络都省掉）
      pluginUpdates.value = []
      mark('plugins')
      return false
    }
    await ensurePluginAppSource()
    const list = await fetchPluginSources()
    if (!list || !list.current) {
      pluginUpdates.value = []
      return false
    }
    pluginConfigured.value = true
    const idx = await fetchPluginIndex()
    const out: PluginLatest[] = []
    for (const [id, info] of Object.entries(installed || {})) {
      const remote = idx?.packages?.[id]
      if (!remote || remote.hidden) continue
      if (verCmp(String(remote.version || ''), String(info.version || '')) > 0) {
        out.push({
          id,
          name: remote.display_name || id,
          current: String(info.version || ''),
          latest: String(remote.version || ''),
          size: Number(remote.size) || 0,
        })
      }
    }
    pluginUpdates.value = out
    mark('plugins')
    return out.length > 0
  } catch (e) {
    setError('plugins', e, silent)
    pluginUpdates.value = []
    return false
  } finally {
    checking.plugins = false
  }
}

/**
 * fnOS fpk 更新（FnDepot 优先，主仓镜像兜底）。
 *
 * ★ 能力只认 `capability`（E 阶段）：不再自己 `detectFpkShell()` 判显隐，
 *   版本检查复用 `checkFpkUpdate()` —— 与启动弹框状态机是同一条路径
 *   （`api/fpkUpgrade.ts` 注释：「供弹框状态机复用，与 FpkUpgradePanel 同一路径」）。
 */
export async function checkFpk(silent = false): Promise<boolean> {
  if (isDesktop() || isTvShell() || checking.fpk) return false
  checking.fpk = true
  errors.fpk = ''
  fpkVisible.value = false
  try {
    const cap = await probeCapability()
    fpkChecked.value = true
    if (cap !== 'fpk') {
      fpkLatest.value = null
      return false
    }
    const res = await checkFpkUpdate()
    if (!res) {
      fpkLatest.value = null
      return false
    }
    fpkVisible.value = true
    fpkCurrent.value = res.current
    fpkLatest.value = res.latest
    mark('fpk')
    return !!res.latest
  } catch (e) {
    setError('fpk', e, silent)
    fpkLatest.value = null
    return false
  } finally {
    checking.fpk = false
  }
}

/**
 * 只填「当前版本」的轻探测（不判 checked、不标记最近检查）。
 *
 * <p>目的：设置页刚打开时，「fnOS 应用」那行就有版本号，不再出现
 * 「— 未检查」和下面「当前 v1.2.7」互相打架（2026-11-16 用户截图）。
 * 探测走 `probeCapability()`（会话内缓存），与 `checkFpk` 共用结果，
 * 不额外发第二次 `/api/_shell/info`。
 */
export async function probeFpkCurrent(): Promise<void> {
  if (isDesktop() || isTvShell() || fpkCurrent.value) return
  try {
    const cap = await probeCapability()
    if (cap !== 'fpk' || !fpkInfo.value) return
    fpkVisible.value = true
    fpkCurrent.value = fpkInstalled.value?.version || fpkInfo.value.version
  } catch {
    /* 探测失败保持"未检查"，不写错误 —— 用户没主动问 */
  }
}

/**
 * 把「应用更新」区块已经查到的一次结果写回更新中心那一行。
 *
 * <p>两个组件各自挂载都会查一次（索引有 60s 缓存，网络只有一次），
 * 但状态是两套；不写回就会出现同一页面上下两行版本号不一致。
 */
export function seedFpkState(current: string, latest: FpkLatest | null): void {
  if (current) fpkCurrent.value = current
  fpkVisible.value = true
  fpkLatest.value = latest
}

/**
 * 后台自动检查（App.vue 启动后调用）。
 *
 * 壳仍走原来的 5 秒静默检查 + 弹框；模块/插件/fpk 只更新状态和角标，
 * 24 小时一次，且必须满足“已安装/有源/平台支持”。
 */
export async function runBackgroundChecks(): Promise<void> {
  const jobs: Array<Promise<unknown>> = []
  if (autoCheckDue('module')) jobs.push(checkModule(true))
  if (autoCheckDue('python')) jobs.push(checkPython(true))
  if (autoCheckDue('plugins')) jobs.push(checkPlugins(true))
  if (autoCheckDue('fpk')) jobs.push(checkFpk(true))
  await Promise.allSettled(jobs)
}

/** 手动“检查全部”：手动不受 24h 节流，也不看自动开关 */
export async function checkAll(): Promise<void> {
  await Promise.allSettled([
    checkModule(false),
    checkPython(false),
    checkPayload(false),
    checkPlugins(false),
    checkFpk(false),
  ])
}

/** 设置页改了自动检查开关后立即生效（只落 prefs，无需重启） */
export function autoCheckEnabled(kind: UpdateKind): boolean {
  const p = getPrefs()
  return kind === 'app'
    ? p.autoCheckApp
    : kind === 'module' || kind === 'python'
      ? p.autoCheckModule
      : kind === 'plugins'
        ? p.autoCheckPlugins
        : p.autoCheckFpk
}
