<script setup lang="ts">
/**
 * 设置页「运行时」区块（各端共用一套展示，不负责安装）。
 *
 * <p>为什么放在设置页而不是「搜索与源」里：运行时是**执行环境**，不是源。
 * 用户的问题是「.js/.py 源为什么跑不起来」，答案在运行时；TV 的「运行时模块」、
 * PC 的「本机插件」、fpk 的 java/node/python、iOS 的端内 drpy/CPython 本质是
 * 同一个问题在不同端的形态。
 *
 * <p>各端形态：
 *   - PC：插件型（drpy / pyrt），由「本机插件」安装与启停
 *   - fpk：meowd 探测到的 java/node/python 可执行文件
 *   - iOS：端内 nodejs-mobile（drpy）+ 内嵌 CPython；启动状态写在 policy.pyRuntime
 *
 * <p>★ TV 不再走这里（2026-11-28）：TV 的 Node 运行时 / Python 运行时 / 弹幕组件
 * 三块在设置页里各带安装与检查按钮，同一份状态在面板里再画一次会重复，
 * 所以设置页对 TV 不渲染本组件（见 `v-if="!tvModule"`）。
 *
 * <p>★ 只展示 + 引导，不做安装：fpk 装依赖要网关权限（5666），
 * PC 插件要走插件管理器，TV 模块要下载 60MB APK —— 跨端口/跨层的安装动作
 * 一律交给各自的既有入口。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { rescanFpkDeps, shellInfo, type FpkDepInfo, type FpkDeps } from '@/api/fpkUpgrade'
import { isDesktopShell, isIosShell, isTvShell } from '@/compat/shell'
import { fetchPolicy, request, type PluginInfo } from '@/api'

const props = defineProps<{
  /** PC：由设置页传进来的插件列表（boot_info），用于显示 drpy/pyrt 状态 */
  plugins?: PluginInfo[]
}>()
const emit = defineEmits<{ (e: 'goto', selector: string): void }>()

const loading = ref(false)
const error = ref('')
const deps = ref<FpkDeps | null>(null)
/** 'fpk' = 飞牛（java/node/python 三件套）；'ios' = IPA（端内 CPython + drpy）；
 *  'desktop' = PC 插件型运行时；null = 不显示（TV 由设置页自己的块负责） */
const mode = ref<'fpk' | 'ios' | 'desktop' | null>(null)
/** iOS：运行时地址 + 宿主探测 + 端内 Python 启动状态（policy.pyRuntime） */
interface PyRuntimeState {
  state?: string
  port?: number
  error?: string
  at?: number
}
interface IoRuntimeInfo {
  url: string
  ok: boolean
  pyCap: string
  breaker: string
  py: PyRuntimeState | null
  /** 进程内存（iOS 壳才有；-1 = 未知） */
  rssMB: number
  availMB: number
}
const iosInfo = ref<IoRuntimeInfo | null>(null)

const iosKind = computed(() => {
  const u = iosInfo.value?.url || ''
  if (!u) return '未配置'
  if (u.includes('127.0.0.1:19331')) return '端内 CPython + drpy'
  if (u.includes('127.0.0.1:19330')) return '端内 Node（仅 JS）'
  return '远端运行时'
})
/** Node（drpy）是否可用：/api/runtime/status 的活体探测，不再拿熔断状态冒充 */
const iosJsOk = computed(() => !!iosInfo.value?.ok)
const iosPyState = computed(() => iosInfo.value?.py?.state || '')
const iosPy = computed(() => {
  if (iosPyState.value === 'running') return 'yes'
  if (iosPyState.value === 'failed' || iosPyState.value === 'unavailable') return 'no'
  // 没有 pyRuntime 记录时退回宿主 caps（首次调用 .py 后才有）
  if (iosInfo.value?.pyCap === 'yes') return 'yes'
  if (iosInfo.value?.pyCap === 'no') return 'no'
  return 'unknown'
})
const iosPyError = computed(() => (iosInfo.value?.py?.error || '').split('\n').slice(-3).join('\n'))

let disposed = false

interface Row {
  key: 'java' | 'node' | 'python'
  title: string
  /** 应用中心里的依赖包名（用户照着搜） */
  pkg: string
  /** 缺失时的后果 */
  impact: string
  /** 该运行时服务哪些源 */
  serves: string
}

const ROWS: Row[] = [
  {
    key: 'java',
    title: 'Java',
    pkg: 'java-21-openjdk',
    impact: '缺失时影视后端不启动，整个应用不可用',
    serves: '后端本体',
  },
  {
    key: 'node',
    title: 'Node.js',
    pkg: 'nodejs_v22',
    impact: '缺失时弹幕与 .js 源不可用',
    serves: '弹幕 · JS 源',
  },
  {
    key: 'python',
    title: 'Python',
    pkg: 'python312',
    impact: '缺失时 .py 源不可用',
    serves: 'Python 源',
  },
]

const missing = computed(() => ROWS.filter((r) => !deps.value?.[r.key]?.ok))

/** 应用中心入口。fpk 页面在 30010，应用中心在 5666；协议跟当前页一致最稳。 */
const appCenterUrl = computed(() => {
  try {
    return `https://${location.hostname}:5666/`
  } catch {
    return ''
  }
})

function info(key: Row['key']): FpkDepInfo | undefined {
  return deps.value?.[key]
}

function shortPath(p?: string | null): string {
  if (!p) return ''
  // 只留最后三段：/var/apps/java-21-openjdk/target/bin/java → java-21-openjdk/target/bin/java
  const parts = p.split('/').filter(Boolean)
  return parts.length <= 4 ? p : `…/${parts.slice(-4).join('/')}`
}

function sourceLabel(s?: string | null): string {
  if (!s) return ''
  if (s.startsWith('env:')) return '环境变量'
  if (s.startsWith('dep:')) return '同卷依赖'
  if (s.startsWith('varapp:')) return '/var/apps'
  if (s === 'path') return 'PATH'
  return s
}

/** PC：drpy / pyrt 两个插件行的状态文案 */
const desktopRows = computed(() => {
  const pick = (id: string) => (props.plugins || []).find((p) => p.id === id)
  return [
    { id: 'drpy', title: 'drpy', serves: 'JS 源（.js）', p: pick('drpy') },
    { id: 'pyrt', title: 'pyrt', serves: 'Python 源（.py）', p: pick('pyrt') },
  ]
})

function pluginText(p?: PluginInfo): string {
  if (!p || !p.installed) return '未安装'
  if (p.phase === 'ready') return `运行中 · ${p.base || `端口 ${p.port}`}`
  if (p.phase === 'starting') return '启动中…'
  if (p.phase === 'failed') return `启动失败：${p.error || '未知原因'}`
  return p.enabled ? '已启用，未运行' : '已安装，未启用'
}

async function refresh(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    // iOS：端内运行时 + 宿主探测（地址来自 policy，活体/能力来自 runtime status）
    if (isIosShell() && !isTvShell()) {
      mode.value = 'ios'
      const [p, h, st] = await Promise.all([
        fetchPolicy().catch(() => ({ jsRuntime: '' } as { jsRuntime?: string })),
        request<{
          jsRuntimeBreaker?: string
          jsRuntimePy?: string
          rssMB?: number
          memAvailMB?: number
        }>('/api/health', { timeout: 8000 }).catch(() => null),
        /*
         * `?probe=1`：让宿主跳过 15 秒探活缓存（2026-11-27 真机）。
         * iOS 切后台时 node 监听会被系统回收，缓存里可能留着那期间测出的 false；
         * 不加这个参数，切回端内后端后本面板和「重新检测」都会一直显示不可用。
         */
        request<{ url?: string; ok?: boolean; py?: string; breaker?: string }>(
          '/api/runtime/status?probe=1',
          { timeout: 8000 },
        ).catch(() => null),
      ])
      const pr = (p as { pyRuntime?: PyRuntimeState }).pyRuntime || null
      iosInfo.value = {
        url: String(st?.url || p.jsRuntime || ''),
        ok: st ? !!st.ok : h?.jsRuntimeBreaker === 'closed',
        pyCap: String(st?.py || h?.jsRuntimePy || 'unknown'),
        breaker: String(st?.breaker || h?.jsRuntimeBreaker || 'unknown'),
        py: pr,
        rssMB: Number(h?.rssMB ?? -1),
        availMB: Number(h?.memAvailMB ?? -1),
      }
      return
    }
    // PC：运行时就是插件，状态由设置页的 boot_info 提供（不重复发请求）
    if (isDesktopShell()) {
      mode.value = 'desktop'
      return
    }
    /*
     * fpk：状态只依赖 meowd 的 /api/_shell/info（它由 meowd 自己答，
     * 不需要飞牛网关）。网关只在"安装更新"时才必需 —— 那是 FpkUpgradePanel 的事。
     * 判据放错位置的后果：用户直接访问 http://<NAS>:30010/ 时这个区块整块消失，
     * 而 deps 明明拿得到。
     */
    const shell = await shellInfo(true)
    if (disposed) return
    if (!shell || !shell.deps) {
      mode.value = null
      return
    }
    mode.value = 'fpk'
    deps.value = shell.deps
  } catch (e) {
    error.value = (e as Error)?.message || '探测失败'
  } finally {
    loading.value = false
  }
}

async function rescan(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    if (mode.value === 'ios' || mode.value === 'desktop') {
      await refresh()
      return
    }
    deps.value = await rescanFpkDeps()
  } catch (e) {
    error.value = (e as Error)?.message || '重新检测失败'
  } finally {
    loading.value = false
  }
}

function copyMissing(): void {
  const names = missing.value.map((r) => r.pkg).join(' ')
  void navigator.clipboard?.writeText(names)
}

onMounted(() => {
  void refresh()
})
onBeforeUnmount(() => {
  disposed = true
})
</script>

<template>
  <!-- ── iOS：端内运行时（drpy + CPython），状态与能力引导 ── -->
  <template v-if="mode === 'ios' && iosInfo">
    <div class="set-sub" data-runtime-deps>
      <span class="font-medium text-ink-2">本机运行时</span>
      <span class="text-[length:var(--fs-mini)] text-ink-3">
        {{ iosKind }}
      </span>
      <span class="flex-1" />
      <button
        class="rounded-md bg-card px-2.5 py-1 text-[length:var(--fs-mini)] hover:bg-card-hover"
        :disabled="loading"
        @click="rescan()"
      >
        {{ loading ? '检测中…' : '重新检测' }}
      </button>
    </div>
    <div class="pb-3 text-[length:var(--fs-xs)]" data-runtime-deps>
      <div class="flex flex-wrap items-baseline gap-x-2">
        <span class="inline-block h-1.5 w-1.5 shrink-0 rounded-full"
              :class="iosJsOk ? 'bg-ok' : 'bg-hot'" />
        <span class="font-medium text-ink">Node（drpy）</span>
        <span :class="iosJsOk ? 'text-ok' : 'text-hot'">
          {{ iosInfo.ok ? '可用' : iosInfo.breaker === 'open' ? '不可用（熔断）' : '不可用' }}
        </span>
        <span class="text-ink-3/70">· 弹幕 · .js 源</span>
      </div>
      <p class="ml-3.5 mt-0.5 break-all text-[length:var(--fs-mini)] text-ink-3">
        端内 nodejs-mobile（V8 关闭 JIT）；地址
        <code class="select-all">{{ iosInfo.url || '未配置' }}</code>
      </p>
      <!--
        端内运行时没响应时的处置说明（2026-11-27 用户实测）。
        node 跟 App 同进程、node_start 只能调一次 —— 切后台被系统回收后
        没有"重启运行时"这条路，只能重开 App；不写清楚用户会以为是配置坏了。
      -->
      <p
        v-if="!iosJsOk && /^http:\/\/127\.0\.0\.1:1933\d$/.test(iosInfo.url)"
        class="ml-3.5 mt-1 rounded-md bg-field px-2.5 py-1.5 text-[length:var(--fs-mini)] leading-relaxed text-ink-3"
      >
        端内 Node 没有响应（iOS 切后台可能回收了它的监听）。先点右上「重新检测」；
        仍不可用时重启 App —— 它和 App 同进程，一次启动只能拉起一次。
      </p>

      <div class="mt-2 flex flex-wrap items-baseline gap-x-2">
        <span class="inline-block h-1.5 w-1.5 shrink-0 rounded-full"
              :class="iosPy === 'yes' ? 'bg-ok' : iosPy === 'no' ? 'bg-hot' : 'bg-white/30'" />
        <span class="font-medium text-ink">CPython 3.12</span>
        <span :class="iosPy === 'yes' ? 'text-ok' : iosPy === 'no' ? 'text-hot' : 'text-ink-3'">
          {{
            iosPy === 'yes'
              ? '可用'
              : iosPy === 'no'
                ? '不可用'
                : iosPyState === 'pending'
                  ? '等待前台启动…'
                  : iosInfo.url.includes('19330')
                    ? '未启用（回退 Node）'
                    : '未知'
          }}
        </span>
        <span class="text-ink-3/70">· .py 源</span>
      </div>
      <p
        v-if="iosPyError"
        class="ml-3.5 mt-0.5 whitespace-pre-wrap break-all text-[length:var(--fs-mini)]"
        :class="iosPyState === 'pending' ? 'text-ink-3' : 'text-hot/90'"
      >
        {{ iosPyError }}
      </p>
      <p v-if="iosInfo.rssMB >= 0" class="ml-3.5 mt-0.5 text-[length:var(--fs-mini)] text-ink-3">
        进程内存 RSS {{ iosInfo.rssMB }} MB · 系统余量
        {{ iosInfo.availMB >= 0 ? `${iosInfo.availMB} MB` : '未知' }}
      </p>
      <p class="ml-3.5 mt-0.5 text-[length:var(--fs-mini)] text-ink-3">
        端内裁剪版：含 requests/bs4/pyaes、AES-ECB/CBC/GCM、ARC4 与常用 Hash（另有 cryptography 的 AES/Fernet 兼容层）；<span class="text-hot">不含 lxml/pyquery/PIL/RSA</span>。
        启动日志在「文件」App 的 猫窝/Documents/py.log。
      </p>

      <div class="mt-2 rounded-md bg-white/5 px-2.5 py-2 text-[length:var(--fs-mini)] text-ink-3">
        需要 lxml/pyquery 或完整 pycryptodome 时：在「搜索与源」把 JS 运行时地址填成局域网 pyrt
        （如 <code class="select-all">http://192.168.1.x:19331</code>）保存后回来重新检测；
        端内运行时会让位给远端，`.js/.py` 都走那台机器。
        <button class="ml-1 underline hover:text-ink" @click="emit('goto', '[data-jsruntime]')">
          去配置
        </button>
      </div>
    </div>
  </template>

  <!-- ── PC：插件型运行时（drpy / pyrt） ── -->
  <template v-if="mode === 'desktop'">
    <div class="set-sub" data-runtime-deps>
      <span class="font-medium text-ink-2">脚本运行时</span>
      <span class="text-[length:var(--fs-mini)] text-ink-3">由本机插件提供 · 安装与启停在下方插件列表</span>
      <span class="flex-1" />
      <button
        class="rounded-md bg-card px-2.5 py-1 text-[length:var(--fs-mini)] hover:bg-card-hover"
        :disabled="loading"
        @click="rescan()"
      >
        {{ loading ? '检测中…' : '刷新状态' }}
      </button>
    </div>
    <div class="pb-3 text-[length:var(--fs-xs)]" data-runtime-deps>
      <div v-for="row in desktopRows" :key="row.id" class="mt-1 first:mt-0">
        <div class="flex flex-wrap items-baseline gap-x-2">
          <span class="inline-block h-1.5 w-1.5 shrink-0 rounded-full"
                :class="row.p?.phase === 'ready' ? 'bg-ok' : row.p?.installed ? 'bg-white/40' : 'bg-hot'" />
          <span class="font-medium text-ink">{{ row.title }}</span>
          <span :class="row.p?.phase === 'ready' ? 'text-ok' : row.p?.phase === 'failed' ? 'text-hot' : 'text-ink-3'">
            {{ pluginText(row.p) }}
          </span>
          <span class="text-ink-3/70">· {{ row.serves }}</span>
        </div>
      </div>
      <p class="mt-1.5 text-[length:var(--fs-mini)] text-ink-3/70">
        缺哪个就在下方「插件列表」里下载安装；装好后插件管理器会自动拉起。
      </p>
    </div>
  </template>

  <div v-if="mode === 'fpk'" class="set-sub" data-runtime-deps>
    <span class="font-medium text-ink-2">系统依赖</span>
    <span class="text-[length:var(--fs-mini)] text-ink-3">
      <template v-if="loading">检测中…</template>
      <template v-else-if="missing.length">{{ missing.length }} 项未安装</template>
      <template v-else>全部就绪</template>
    </span>
    <span class="flex-1" />
    <button
      class="rounded-md bg-card px-2.5 py-1 text-[length:var(--fs-mini)] hover:bg-card-hover"
      :disabled="loading"
      @click="rescan()"
    >
      {{ loading ? '检测中…' : '重新检测' }}
    </button>
  </div>

  <div v-if="mode === 'fpk'" class="pb-3 text-[length:var(--fs-xs)]">
    <p v-if="error" class="text-hot">{{ error }}</p>

    <div v-for="row in ROWS" :key="row.key" class="mt-2 first:mt-0">
      <div class="flex flex-wrap items-baseline gap-x-2">
        <span
          class="inline-block h-1.5 w-1.5 shrink-0 rounded-full"
          :class="info(row.key)?.ok ? 'bg-ok' : 'bg-hot'"
        />
        <span class="font-medium text-ink">{{ row.title }}</span>
        <span v-if="info(row.key)?.ok" class="text-ink-3">
          v{{ info(row.key)?.version || '?' }}
        </span>
        <span v-else class="text-hot">未安装</span>
        <span class="text-ink-3/70">· {{ row.serves }}</span>
      </div>

      <p v-if="info(row.key)?.ok" class="ml-3.5 mt-0.5 break-all text-[length:var(--fs-mini)] text-ink-3">
        <code class="select-all">{{ shortPath(info(row.key)?.path) }}</code>
        <span v-if="sourceLabel(info(row.key)?.source)" class="ml-1 opacity-70">
          （{{ sourceLabel(info(row.key)?.source) }}）
        </span>
      </p>
      <p v-else class="ml-3.5 mt-0.5 text-[length:var(--fs-mini)] text-ink-3">
        {{ row.impact }}。到应用中心安装
        <code class="select-all text-accent">{{ row.pkg }}</code>
        后回来点「重新检测」。
      </p>
    </div>

    <div
      v-if="missing.length"
      class="mt-2 flex flex-wrap items-center gap-2 border-t border-line pt-2"
    >
      <a
        v-if="appCenterUrl"
        :href="appCenterUrl"
        target="_blank"
        rel="noopener"
        class="rounded-md bg-accent px-2.5 py-1 text-[length:var(--fs-mini)] font-medium text-on-accent hover:bg-accent-dim"
      >
        打开应用中心
      </a>
      <button
        class="rounded-md bg-card px-2.5 py-1 text-[length:var(--fs-mini)] hover:bg-card-hover"
        @click="copyMissing()"
      >
        复制缺失的包名
      </button>
      <span class="text-[length:var(--fs-mini)] text-ink-3/70">
        装完不用重启应用，点「重新检测」即可
      </span>
    </div>
  </div>
</template>
