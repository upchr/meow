<script setup lang="ts">
/**
 * 更新中心：壳 / TV 运行时模块 / PC 插件 / fnOS fpk 的统一入口。
 *
 * <p>只聚合“检查 + 状态 + 跳转”，不合并安装器：
 * 每项仍然走自己的安装通道，避免一键安装把壳、模块、插件、fpk 混在
 * 同一个失败域里。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import {
  currentVersion,
} from '@/api/appUpdate'
import { isDesktop } from '@/api'
import { getPrefs, savePrefs } from '@/stores/prefs'
import {
  checked as updChecked,
  checking as updChecking,
  error as updError,
  found as updFound,
  openDialog as openUpdDialog,
  platform as updPlatform,
  runCheck as runUpdCheck,
} from '@/stores/appUpdate'
import { isVersionSkipped } from '@/api/appUpdate'
import {
  checkFpk,
  checkModule,
  checkPlugins,
  checking,
  errors,
  fpkChecked,
  fpkCurrent,
  fpkLatest,
  fpkVisible,
  hydrateModuleState,
  isTvShell,
  lastCheckedAt,
  markChecked,
  moduleChecked,
  payloadChecked,
  payloadCurrent,
  payloadInstalled,
  payloadLatest,
  payloadUpdateChecked,
  checkPayload,
  moduleCurrent,
  moduleInstalled,
  moduleLatest,
  moduleUpdateChecked,
  pluginChecked,
  pluginConfigured,
  pluginInstalledCount,
  pluginUpdates,
} from '@/stores/updateCenter'

/** 跨区跳转交给宿主页面（见 scrollTo 的注释） */
const emit = defineEmits<{ (e: 'goto', selector: string): void }>()

const appVersion = currentVersion()
const busyAll = ref(false)
const auto = reactive({
  app: getPrefs().autoCheckApp,
  module: getPrefs().autoCheckModule,
  plugins: getPrefs().autoCheckPlugins,
  fpk: getPrefs().autoCheckFpk,
})

const desktop = isDesktop()
const tv = isTvShell()
const appFound = computed(() => !!updFound.value)
const moduleFound = computed(() => !!moduleLatest.value)
const pluginCount = computed(() => pluginUpdates.value.length)
const payloadLabel = computed(() => {
  if (checking.payload) return '检查中…'
  if (!payloadInstalled.value) return '未安装'
  if (payloadLatest.value) return `发现 v${payloadLatest.value.version}`
  if (!payloadUpdateChecked.value) return '未检查'
  return '已是最新'
})

const fpkFound = computed(() => !!fpkLatest.value)

const appLabel = computed(() => {
  if (updChecking.value) return '检查中…'
  if (appFound.value) {
    const v = updFound.value?.version || ''
    // 「稍后」过的版本要如实说，别冒充「已是最新」
    return isVersionSkipped(v) ? `已跳过 v${v}` : `发现 v${v}`
  }
  if (updChecked.value) return '已是最新'
  return '未检查'
})
const moduleLabel = computed(() => {
  if (checking.module) return '检查中…'
  if (!moduleChecked.value) return '未检查'
  if (!moduleInstalled.value) return '未安装'
  if (!moduleUpdateChecked.value) return '未检查'
  if (moduleLatest.value) return `发现 v${moduleLatest.value.version}`
  return '已是最新'
})
const pluginLabel = computed(() => {
  if (checking.plugins) return '检查中…'
  if (!desktop) return '仅 PC'
  if (!pluginChecked.value) return '未检查'
  if (!pluginInstalledCount.value) return '未安装插件'
  if (!pluginConfigured.value) return '未配置源'
  return pluginCount.value ? `${pluginCount.value} 个可更新` : '已是最新'
})
const fpkLabel = computed(() => {
  if (checking.fpk) return '检查中…'
  if (!fpkChecked.value) return '未检查'
  if (!fpkVisible.value) return '未检测到 fpk 壳'
  if (fpkLatest.value) return `发现 v${fpkLatest.value.version}`
  return '已是最新'
})

/**
 * 标题右侧的范围说明：只列当前端真正会检查的渠道。
 *
 * <p>原来固定写「壳 / 模块 / 插件 / fnOS 应用统一查看」，TV 上会让人以为
 * 插件和 fpk 也该有状态，实际那两行根本不显示。
 */
const scopeLabel = computed(() =>
  tv ? '壳 / 运行时模块 / 弹幕组件' : desktop ? '壳 / 本机插件' : '壳 / fnOS 应用',
)

/**
 * 最近检查时间：只列本次会话真的查过、且当前端可见的渠道。
 *
 * <p>踩过（2026-11-11 用户截图）：「模块 未检查」旁边却写「最近检查：
 * 模块 51 分钟前检查」—— 时间戳是 localStorage 里上次会话留下的，
 * 而结果状态是内存态。另外 TV 上还会出现「插件 未检查 · fpk 未检查」，
 * 这两个渠道在 TV 根本不存在。全部按会话标志过滤。
 */
const lastParts = computed(() => {
  const parts: string[] = []
  if (updPlatform.value !== 'web' && updChecked.value) parts.push(`壳 ${lastText('app')}`)
  if (tv && moduleUpdateChecked.value) parts.push(`模块 ${lastText('module')}`)
  if (desktop && pluginChecked.value) parts.push(`插件 ${lastText('plugins')}`)
  if (!desktop && !tv && fpkVisible.value && fpkChecked.value) {
    parts.push(`fpk ${lastText('fpk')}`)
  }
  return parts
})

function fmtSize(n: number): string {
  if (!n || n <= 0) return ''
  const units = ['B', 'KB', 'MB', 'GB']
  let v = n
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v < 10 && i > 0 ? v.toFixed(1) : Math.round(v)} ${units[i]}`
}

function lastText(kind: 'app' | 'module' | 'plugins' | 'fpk'): string {
  const t = lastCheckedAt[kind]
  if (!t) return '未检查'
  const mins = Math.max(0, Math.round((Date.now() - t) / 60000))
  if (mins < 1) return '刚刚检查'
  if (mins < 60) return `${mins} 分钟前检查`
  return `${Math.round(mins / 60)} 小时前检查`
}

/**
 * 跨区跳转只上报目标，滚动由设置页导航统一做。
 *
 * ★ 这里不要自己 scrollIntoView：Android WebView 会把带 smooth 的
 *   scrollIntoView 整次丢掉（2026-11-13 竖屏设备实测 scrollY 位移 0px），
 *   而且它绕过了「容器还是 window 在滚」「顶栏要让出多少」「滚完闪烁确认」
 *   这些只有设置页知道的细节。见 SettingsView 的 gotoSelector。
 */
function scrollTo(sel: string): void {
  emit('goto', sel)
}

async function checkAppNow(): Promise<void> {
  await runUpdCheck()
  markChecked('app')
}

async function refreshAll(): Promise<void> {
  if (busyAll.value) return
  busyAll.value = true
  try {
    await Promise.allSettled([checkAppNow(), checkModule(false), checkPlugins(false), checkFpk(false)])
  } finally {
    busyAll.value = false
  }
}

function toggleAuto(key: keyof typeof auto, value: boolean): void {
  auto[key] = value
  const field =
    key === 'app'
      ? 'autoCheckApp'
      : key === 'module'
        ? 'autoCheckModule'
        : key === 'plugins'
          ? 'autoCheckPlugins'
          : 'autoCheckFpk'
  savePrefs({ [field]: value } as any)
}

onMounted(() => {
  // 不主动发网络请求：设置页经常被打开，自动检查由 App.vue 的
  // 24h 后台任务负责；这里只展示已有状态。
  //
  // 模块安装状态是本地桥调用（不发请求），先读一次：
  // 否则「版本未知」会被显示成「未安装」，再叠一句「未检查」。
  hydrateModuleState()
})
</script>

<template>
  <div class="mt-3 border-t border-line pt-3 text-[11px] text-ink-2" data-update-center>
    <div class="flex flex-wrap items-center gap-2">
      <span class="font-medium text-ink-2">更新中心</span>
      <span class="text-[11px] text-ink-3">{{ scopeLabel }} · 统一查看</span>
      <span class="flex-1" />
      <button
        class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover disabled:opacity-40"
        :disabled="busyAll"
        @click="refreshAll()"
      >
        {{ busyAll ? '检查中…' : '检查全部' }}
      </button>
    </div>

    <!-- 壳 -->
    <div v-if="updPlatform !== 'web'" class="mt-2 flex flex-wrap items-center gap-2 border-t border-line pt-2" data-update-row="app">
      <span class="w-24 shrink-0 text-[11px] text-ink-2">猫窝 {{ updPlatform === 'windows-x64' ? 'PC' : 'TV' }}</span>
      <span class="font-mono text-[11px] text-ink-3">{{ appVersion || '—' }}</span>
      <span class="text-[11px] text-ink-3">{{ appLabel }}</span>
      <span v-if="updFound" class="text-[11px] text-ink-3">{{ fmtSize(updFound.size) }}</span>
      <span class="flex-1" />
      <button
        v-if="updFound"
        class="rounded-md bg-accent px-2.5 py-1 text-[11px] text-on-accent"
        @click="openUpdDialog()"
      >
        查看
      </button>
      <button
        v-else-if="!updChecked || updError"
        class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover disabled:opacity-40"
        :disabled="updChecking"
        @click="checkAppNow()"
      >
        检查
      </button>
      <span v-if="updError || errors.app" class="text-[10px] text-hot">{{ updError || errors.app }}</span>
    </div>

    <!-- TV 运行时模块 -->
    <div v-if="tv" class="mt-2 flex flex-wrap items-center gap-2 border-t border-line pt-2" data-update-row="module">
      <span class="w-24 shrink-0 text-[11px] text-ink-2">运行时模块</span>
      <span class="font-mono text-[11px] text-ink-3">{{ moduleCurrent || '—' }}</span>
      <span class="text-[11px] text-ink-3">{{ moduleLabel }}</span>
      <span v-if="moduleLatest" class="text-[11px] text-ink-3">{{ fmtSize(moduleLatest.size) }}</span>
      <span class="flex-1" />
      <button
        v-if="moduleFound"
        class="rounded-md bg-accent px-2.5 py-1 text-[11px] text-on-accent"
        @click="scrollTo('[data-runtime-module]')"
      >
        去更新
      </button>
      <button
        v-else-if="moduleChecked && !moduleInstalled"
        class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover"
        @click="scrollTo('[data-runtime-module]')"
      >
        去安装
      </button>
      <button
        v-else-if="!moduleUpdateChecked || errors.module"
        class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover disabled:opacity-40"
        :disabled="checking.module"
        @click="checkModule(false)"
      >
        检查
      </button>
      <span v-if="errors.module" class="text-[10px] text-hot">{{ errors.module }}</span>
    </div>

    <!-- TV 弹幕组件（载荷）：与运行时模块分开装、分开升版本 -->
    <div v-if="tv" class="mt-2 flex flex-wrap items-center gap-2 border-t border-line pt-2" data-update-row="payload">
      <span class="w-24 shrink-0 text-[11px] text-ink-2">弹幕组件</span>
      <span class="font-mono text-[11px] text-ink-3">{{ payloadInstalled ? payloadCurrent : '—' }}</span>
      <span class="text-[11px] text-ink-3">{{ payloadLabel }}</span>
      <span class="flex-1" />
      <button
        v-if="payloadLatest"
        class="rounded-md bg-accent px-2.5 py-1 text-[11px] text-on-accent"
        @click="scrollTo('[data-danmu-payload]')"
      >
        去更新
      </button>
      <button
        v-else-if="payloadChecked && !payloadInstalled"
        class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover"
        @click="scrollTo('[data-danmu-payload]')"
      >
        去安装
      </button>
      <button
        v-else-if="(!payloadUpdateChecked && payloadInstalled) || errors.payload"
        class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover disabled:opacity-40"
        :disabled="checking.payload"
        @click="checkPayload(false)"
      >
        检查
      </button>
      <span v-if="errors.payload" class="text-[10px] text-hot">{{ errors.payload }}</span>
    </div>

    <!-- PC 插件 -->
    <div v-if="desktop" class="mt-2 flex flex-wrap items-center gap-2 border-t border-line pt-2" data-update-row="plugins">
      <span class="w-24 shrink-0 text-[11px] text-ink-2">本机插件</span>
      <span class="text-[11px] text-ink-3">{{ pluginLabel }}</span>
      <span class="flex-1" />
      <button
        v-if="pluginCount"
        class="rounded-md bg-accent px-2.5 py-1 text-[11px] text-on-accent"
        @click="scrollTo('[data-plugin-source]')"
      >
        去更新
      </button>
      <button
        v-else-if="pluginChecked && !pluginInstalledCount"
        class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover"
        @click="scrollTo('[data-sec=plugin]')"
      >
        去安装
      </button>
      <button
        v-else-if="pluginChecked && pluginInstalledCount && !pluginConfigured"
        class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover"
        @click="scrollTo('[data-plugin-source]')"
      >
        去配置
      </button>
      <button
        v-else-if="!pluginChecked || errors.plugins"
        class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover disabled:opacity-40"
        :disabled="checking.plugins"
        @click="checkPlugins(false)"
      >
        检查
      </button>
      <span v-if="errors.plugins" class="text-[10px] text-hot">{{ errors.plugins }}</span>
    </div>

    <!-- fpk -->
    <div v-if="!desktop && !tv" class="mt-2 flex flex-wrap items-center gap-2 border-t border-line pt-2" data-update-row="fpk">
      <span class="w-24 shrink-0 text-[11px] text-ink-2">fnOS 应用</span>
      <span class="font-mono text-[11px] text-ink-3">{{ fpkCurrent || '—' }}</span>
      <span class="text-[11px] text-ink-3">{{ fpkLabel }}</span>
      <span v-if="fpkLatest" class="text-[11px] text-ink-3">{{ fmtSize(fpkLatest.pkg.size) }}</span>
      <span class="flex-1" />
      <button
        v-if="fpkFound"
        class="rounded-md bg-accent px-2.5 py-1 text-[11px] text-on-accent"
        @click="scrollTo('[data-fpk-upgrade]')"
      >
        去更新
      </button>
      <button
        v-else-if="!fpkChecked || errors.fpk"
        class="rounded-md bg-card px-2.5 py-1 text-[11px] hover:bg-card-hover disabled:opacity-40"
        :disabled="checking.fpk"
        @click="checkFpk(false)"
      >
        检查
      </button>
      <span v-if="errors.fpk" class="text-[10px] text-hot">{{ errors.fpk }}</span>
    </div>

    <p class="mt-2 text-[11px] text-ink-3">
      检查只更新状态，不会自动安装；升级仍走各自入口。
      <template v-if="lastParts.length">最近检查：{{ lastParts.join(' · ') }}</template>
    </p>

    <div class="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-line pt-2 text-[11px]">
      <span class="text-ink-3">启动自动检查：</span>
      <label class="flex items-center gap-1 text-ink-2">
        <input type="checkbox" :checked="auto.app" @change="toggleAuto('app', ($event.target as HTMLInputElement).checked)" />
        壳
      </label>
      <label v-if="tv" class="flex items-center gap-1 text-ink-2">
        <input type="checkbox" :checked="auto.module" @change="toggleAuto('module', ($event.target as HTMLInputElement).checked)" />
        模块
      </label>
      <label v-if="desktop" class="flex items-center gap-1 text-ink-2">
        <input type="checkbox" :checked="auto.plugins" @change="toggleAuto('plugins', ($event.target as HTMLInputElement).checked)" />
        插件
      </label>
      <label v-if="!desktop && !tv" class="flex items-center gap-1 text-ink-2">
        <input type="checkbox" :checked="auto.fpk" @change="toggleAuto('fpk', ($event.target as HTMLInputElement).checked)" />
        fnOS 应用
      </label>
      <span class="text-[10px] text-ink-3">模块/插件/fpk 最多 24h 一次，只更新状态，不弹框、不自动下载</span>
    </div>
  </div>
</template>
