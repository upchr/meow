<script setup lang="ts">
/**
 * 运行日志页：把「客户端发生了什么」和「服务端怎么回的」放在同一屏。
 *
 * 为什么两源合并：用户报"点了没反应"时，只看服务端日志会得出"后端没问题"的
 * 错误结论——多数故障发生在请求发出之前（或根本没发出）。反过来只看客户端
 * 又看不到后端为什么 500。所以统一成一张表，用来源标签区分。
 *
 * 为什么是轮询不是 WebSocket：TV 的 Chrome 66 上长连接容易被固件杀，
 * 而 3 秒轮询对排查足够。轮询只在页面可见且有"实时"开关时进行。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  clearClientLogs,
  clearServerLogs,
  fetchServerLogs,
  logEvent,
  readClientLogs,
  readEvents,
  type LogItem,
  type LogLevel,
  type LogsFetchState,
} from '@/api/logs'
import { confirmDialog } from '@/composables/useConfirm'
import { uaText } from '@/compat/ua'
import PillSelect from '@/components/common/PillSelect.vue'

const route = useRoute()
const items = ref<LogItem[]>([])
/**
 * 服务端统计。
 *
 * {@code dropped} = 被环形挤掉的条数（必须显示：看不到它就会把"只剩这几条"
 * 当成"一共只有这几条"）；{@code file} = 落盘路径（重启后的现场去那里看）。
 */
const serverStats = ref({
  total: 0,
  errors: 0,
  warns: 0,
  capacity: 2000,
  dropped: 0,
  level: '',
  file: '',
})
const loading = ref(true)
const error = ref('')
/** 服务端日志的状态。区分「端点不存在」与「后端不可达」——两者的排查方向完全不同 */
const serverState = ref<LogsFetchState>('ok')
/** 状态原因（拿不到时直接展示，别让人猜） */
const serverDetail = ref('')
/** 便捷判据：只有 ok 才显示服务端统计与日志条数 */
const serverOk = computed(() => serverState.value === 'ok')
const lastId = ref(0)

type Tab = 'all' | LogLevel | 'net'
const tab = ref<Tab>('all')
const keyword = ref('')
const tagFilter = ref('')
/** 实时轮询开关（默认开，面板里可关掉方便看历史） */
const live = ref(true)
/**
 * 暂停刷新。
 *
 * ★ 2026-11-27 正名：这个开关原来叫「暂停滚动」，但 poll() 里 `if (!live || paused) return`
 *   —— 它实际暂停的是**轮询**，不是滚动（滚动已经有"贴底才跟随"的判据）。
 *   文案与行为不一致会让用户以为"暂停了滚动"，结果新日志根本不来了。
 */
const paused = ref(false)

/**
 * 渲染上限。
 *
 * ★ 为什么不能一次渲染全部：日志单条最长 4000 字（带堆栈），2000 条 × 多行
 *   在 4K/TV 上滚动会掉帧（实测见 tmp/2026-11-27-log-audit）。这里只渲染最近
 *   400 条，"加载更早"每次再放 400 —— DOM 数量与滚动成本都有上界，而**数据**
 *   仍然是全部（复制/导出/搜索都按 filtered 走，不受影响）。
 */
const ROWS_STEP = 400
const rowCap = ref(ROWS_STEP)

let timer: number | undefined

const LEVEL_LABEL: Record<LogLevel, string> = { info: 'INFO', warn: 'WARN', error: 'ERROR' }

function levelClass(l: LogLevel): string {
  if (l === 'error') return 'text-hot'
  if (l === 'warn') return 'text-warn'
  return 'text-ink-3'
}

function levelBg(l: LogLevel): string {
  if (l === 'error') return 'bg-hot/15 text-hot'
  if (l === 'warn') return 'bg-warn/15 text-warn'
  return 'bg-card-hover text-ink-3'
}

function clock(at: number): string {
  const d = new Date(at)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 所有出现过的模块标签（筛选用，动态生成，不写死） */
const tags = computed(() => {
  const s = new Set<string>()
  for (const it of items.value) if (it.tag) s.add(it.tag)
  return [...s].sort()
})

/**
 * 模块下拉的选项。
 *
 * <p>用自绘胶囊（PillSelect）而不是原生 `<select>`：原生 select 在 TV 壳里
 * 不可聚焦（没有系统弹窗，方向键进不去），遥控器用户没法切模块。
 * 样式与顶栏「站点」下拉一致（选中项 accent 底色 + 勾）。
 */
const tagOptions = computed(() => [
  { value: '', label: '全部模块' },
  ...tags.value.map((t) => ({ value: t, label: t })),
])

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const lv = tab.value
  return items.value.filter((it) => {
    if (tagFilter.value && it.tag !== tagFilter.value) return false
    // net 是"客户端网络记录"，按 tag 归类，和三个级别并列成第四个页签
    if (lv === 'net') {
      if (it.tag !== 'net') return false
    } else if (lv !== 'all' && it.level !== lv) return false
    if (kw && !it.text.toLowerCase().includes(kw) && !it.tag.toLowerCase().includes(kw)) return false
    return true
  })
})

/** 实际渲染的行（最近 rowCap 条）。数据仍是全部，只是 DOM 有上界。 */
const shown = computed(() => {
  const list = filtered.value
  return list.length > rowCap.value ? list.slice(list.length - rowCap.value) : list
})
const hiddenCount = computed(() => Math.max(0, filtered.value.length - shown.value.length))

function showMore() {
  rowCap.value += ROWS_STEP
}

/** 展开的行 key：默认单行省略，点开看完整文本（堆栈） */
const expandedKey = ref('')
function toggleExpand(key: string) {
  expandedKey.value = expandedKey.value === key ? '' : key
}

const counts = computed(() => {
  let err = 0
  let warn = 0
  let net = 0
  for (const it of items.value) {
    if (it.level === 'error') err++
    else if (it.level === 'warn') warn++
    if (it.tag === 'net') net++
  }
  return { total: items.value.length, err, warn, net }
})

/** 合并：服务端 + 客户端（错误/网络）+ 客户端自定义事件，按时间升序 */
function merge(serverItems: LogItem[], keepFrom = 0): LogItem[] {
  const client = [...readClientLogs(), ...readEvents()]
  const merged = [...serverItems, ...client]
  merged.sort((a, b) => a.at - b.at)
  // 上限 2000：与服务端缓冲一致，避免长时间开着页面吃内存
  return merged.length > 2000 ? merged.slice(merged.length - 2000) : merged
}

async function refresh(quiet = false) {
  if (!quiet) loading.value = true
  const r = await fetchServerLogs({ limit: 500 })
  serverState.value = r.state
  serverDetail.value = r.detail || ''
  if (r.state === 'ok' && r.data) {
    const d = r.data
    serverStats.value = {
      total: d.stats.total,
      errors: d.stats.errors,
      warns: d.stats.warns,
      capacity: d.stats.capacity,
      dropped: d.stats.dropped || 0,
      level: d.stats.level || '',
      file: d.stats.file || '',
    }
    lastId.value = d.stats.lastId
    const server: LogItem[] = (d.items || []).map((x) => ({
      key: `svc-${x.id}`,
      origin: 'server' as const,
      at: x.at,
      level: (x.level === 'error' || x.level === 'warn' ? x.level : 'info') as LogLevel,
      tag: x.tag,
      text: x.text,
      count: x.count,
      lastAt: x.lastAt,
    }))
    items.value = merge(server)
    error.value = ''
  } else {
    // 拿不到服务端日志时仍显示客户端日志——这正是最需要它的时候。
    // 但**不能**把状态说成"后端不可达"：404 说明后端活着、只是没有这个端点。
    items.value = merge([])
    error.value = r.state === 'unsupported' ? '' : (r.detail || '')
    // 一旦换成 unsupported，就不再每 3 秒重试（旧后端不会突然长出端点）
    if (r.state === 'unsupported') live.value = false
  }
  loading.value = false
  schedule()
}

/** 增量：只拉 id 更大的服务端条目，并顺带取队尾快照刷新重复计数 */
async function poll() {
  if (!live.value || paused.value) return
  {
    const resp = await fetchServerLogs({ since: lastId.value, limit: 200, tail: 40 })
    serverState.value = resp.state
    if (resp.state !== 'ok' || !resp.data) {
      serverDetail.value = resp.detail || ''
      if (resp.state === 'unsupported') live.value = false
      schedule()
      return
    }
    const r = resp.data
    const mapItem = (x: NonNullable<typeof r.items>[number]): LogItem => ({
      key: `svc-${x.id}`,
      origin: 'server' as const,
      at: x.at,
      level: (x.level === 'error' || x.level === 'warn' ? x.level : 'info') as LogLevel,
      tag: x.tag,
      text: x.text,
      count: x.count,
      lastAt: x.lastAt,
    })
    // 统计每次都刷新：dropped/total 变了但没新条目（相邻重复合并）时也要跟上
    serverStats.value = {
      total: r.stats.total,
      errors: r.stats.errors,
      warns: r.stats.warns,
      capacity: r.stats.capacity,
      dropped: r.stats.dropped || 0,
      level: r.stats.level || '',
      file: r.stats.file || '',
    }
    if (r.stats.lastId > lastId.value) {
      lastId.value = r.stats.lastId
      const add = (r.items || []).map(mapItem)
      if (add.length) items.value = merge([...items.value.filter((i) => i.origin === 'server'), ...add])
    }
    /*
     * 队尾按 key 替换：合并只改 count/at、不推进 id，靠 since 永远收不到更新。
     * 只替换已存在的行（新的会从 items 里来），避免同一行出现两次。
     */
    if (r.tail?.length) {
      const byKey = new Map(r.tail.map((x) => [`svc-${x.id}`, mapItem(x)]))
      let changed = false
      const next = items.value.map((it) => {
        const t = byKey.get(it.key)
        if (!t) return it
        if (t.at === it.at && t.count === it.count && t.text === it.text && t.lastAt === it.lastAt) return it
        changed = true
        return t
      })
      if (changed) items.value = next
    }
    // 客户端错误只有读快照时才更新
    const clientOnly = [...readClientLogs(), ...readEvents()]
    const haveClient = new Set(items.value.filter((i) => i.origin === 'client').map((i) => i.key))
    const fresh = clientOnly.filter((c) => !haveClient.has(c.key))
    if (fresh.length) {
      items.value = merge([
        ...items.value.filter((i) => i.origin === 'server'),
        ...fresh,
      ])
    }
  }
  schedule()
}

function schedule() {
  clearTimeout(timer)
  if (!live.value || paused.value) return
  timer = window.setTimeout(poll, 3000)
}

function toggleLive() {
  live.value = !live.value
  if (live.value) schedule()
  else clearTimeout(timer)
}

async function doClear() {
  if (!(await confirmDialog('清空日志？（只清面板缓冲，不影响后端 stdout）', { danger: true })))
    return
  clearClientLogs()
  if (serverOk.value) await clearServerLogs().catch(() => false)
  items.value = []
  lastId.value = 0
  serverStats.value = {
    total: 0,
    errors: 0,
    warns: 0,
    capacity: 2000,
    dropped: 0,
    level: '',
    file: '',
  }
  logEvent('info', 'ui', '日志已清空')
}

/** 复制可见条目到剪贴板（排查时贴给我/贴 issue 用） */
async function doCopy() {
  const text = filtered.value
    .map((i) => `${clock(i.at)} ${LEVEL_LABEL[i.level].padEnd(5)} [${i.origin === 'server' ? 'srv' : 'cli'}:${i.tag}] ${i.text}`)
    .join('\n')
  try {
    await navigator.clipboard.writeText(text)
    logEvent('info', 'ui', `已复制 ${filtered.value.length} 条日志到剪贴板`)
  } catch {
    // 老内核（TV Chrome 66）没有 clipboard API，退回选中提示
    logEvent('warn', 'ui', '当前环境不支持剪贴板，请手动选中复制')
  }
}

/** 导出为 .txt（排查时给文件比贴文本更好） */
function doExport() {
  const text = filtered.value
    .map((i) => `${new Date(i.at).toISOString()} ${LEVEL_LABEL[i.level]} [${i.origin}:${i.tag}] ${i.text}`)
    .join('\n')
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `meow-logs-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '')}.txt`
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 5000)
}

onMounted(() => {
  // 详情页「查看日志」带 ?kw=<站点名>：直接筛到那个源，用户不用自己猜关键词
  const kw = typeof route.query.kw === 'string' ? route.query.kw.trim() : ''
  if (kw) keyword.value = kw
  logEvent('info', 'ui', `日志页打开 · ${uaText().slice(0, 60)}`)
  void refresh()
})

/*
 * 自动滚到最新一条。
 *
 * 用户的抱怨是「怎么不是滚动到最新的在最下面，而是聚焦到首行了」——
 * 列表按时间升序（最新在底部），但容器没有滚动逻辑，所以打开就停在顶部，
 * 看到的是最旧的日志；TV 上按上下键把焦点给到容器时也会从顶部开始。
 *
 * ★ 只在「用户本来就在底部」时才跟随。
 *   否则正在往上翻找某条错误时来一条新日志，视口就被猛地拽回底部 ——
 *   排查过程中这是最烦人的行为。判据是距底部 40px 内算"在底部"。
 * ★ 用 nextTick 而不是 setTimeout：要在 DOM 插入新行之后、浏览器绘制之前滚，
 *   否则滚动位置会按旧高度算，差一条就是没到底。
 */
const listEl = ref<HTMLElement | null>(null)
/** 用户是否停在底部（用于决定要不要跟随新日志） */
let stickBottom = true

function onListScroll() {
  const el = listEl.value
  if (!el) return
  stickBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40
}

function scrollToLatest() {
  const el = listEl.value
  if (!el) return
  el.scrollTop = el.scrollHeight
}

watch(
  () => filtered.value.length,
  async () => {
    if (!stickBottom) return
    await nextTick()
    scrollToLatest()
  },
)

watch(tab, async () => {
  // 换页签等于换内容：强制回到最新（哪怕之前用户翻到了中间）
  stickBottom = true
  rowCap.value = ROWS_STEP
  await nextTick()
  scrollToLatest()
})

// 换筛选条件也要回到"只看最近 400 条"：否则上一次"加载更早"的展开会带到新结果里
watch([keyword, tagFilter], () => {
  rowCap.value = ROWS_STEP
  stickBottom = true
})

onBeforeUnmount(() => {
  clearTimeout(timer)
  timer = undefined
})
</script>

<template>
  <div class="mx-auto w-full max-w-[80rem] px-4 py-5">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <h1 class="text-lg font-medium">运行日志</h1>
      <span class="text-[11px] text-ink-3">
        共 {{ counts.total }} 条
        <template v-if="counts.err"> · <span class="text-hot">{{ counts.err }} 错误</span></template>
        <template v-if="counts.warn"> · <span class="text-warn">{{ counts.warn }} 警告</span></template>
        <!--
          丢弃提示：环形缓冲写满会挤掉最旧的。不显示它，用户会把"面板里只有这些"
          当成"一共只有这些"，排查时得出错误结论。
        -->
        <template v-if="serverOk && serverStats.dropped">
          · <span class="text-warn" title="环形缓冲写满后挤掉的最旧条数">已丢弃 {{ serverStats.dropped }} 条</span>
        </template>
      </span>
      <!-- 状态提示：不能说"后端不可达"就算完——404 是"后端活着但没这个端点"，
           排查方向完全不同（升级后端 vs 查网络） -->
      <span
        v-if="serverState === 'unsupported'"
        class="rounded-full bg-warn/15 px-2 py-0.5 text-[11px] text-warn"
        :title="serverDetail"
      >
        后端版本较旧（无 /api/logs），仅显示客户端日志
      </span>
      <span
        v-else-if="serverState === 'unreachable'"
        class="rounded-full bg-hot/15 px-2 py-0.5 text-[11px] text-hot"
        :title="serverDetail"
      >
        后端连不上，仅显示客户端日志
      </span>
      <span
        v-else-if="serverState === 'error'"
        class="rounded-full bg-hot/15 px-2 py-0.5 text-[11px] text-hot"
        :title="serverDetail"
      >
        日志接口报错（{{ serverDetail }}），仅显示客户端日志
      </span>

      <div class="ml-auto flex flex-wrap items-center gap-1.5">
        <button
          class="rounded-md px-2.5 py-1 text-[11px] transition"
          :class="live ? 'bg-accent/15 text-accent' : 'bg-card text-ink-3 hover:bg-card-hover'"
          @click="toggleLive"
        >
          {{ live ? '实时刷新中' : '已暂停刷新' }}
        </button>
        <button
          class="rounded-md bg-card px-2.5 py-1 text-[11px] text-ink-3 transition hover:bg-card-hover"
          @click="refresh()"
        >
          刷新
        </button>
        <button
          class="rounded-md bg-card px-2.5 py-1 text-[11px] text-ink-3 transition hover:bg-card-hover"
          @click="doCopy"
        >
          复制
        </button>
        <button
          class="rounded-md bg-card px-2.5 py-1 text-[11px] text-ink-3 transition hover:bg-card-hover"
          @click="doExport"
        >
          导出
        </button>
        <button
          class="rounded-md bg-card px-2.5 py-1 text-[11px] text-ink-3 transition hover:bg-hot/15 hover:text-hot"
          @click="doClear"
        >
          清空
        </button>
      </div>
    </div>

    <!-- 筛选：级别页签 + 模块 + 关键词 -->
    <div class="mb-3 flex flex-wrap items-center gap-1.5">
      <button
        v-for="t in (['all', 'info', 'warn', 'error', 'net'] as const)"
        :key="t"
        class="rounded-md px-2.5 py-1 text-[11px] transition"
        :class="tab === t ? 'bg-accent text-on-accent' : 'bg-card text-ink-3 hover:bg-card-hover'"
        @click="tab = t"
      >
        {{ t === 'all' ? '全部' : t === 'net' ? `网络(${counts.net})` : t.toUpperCase() }}
      </button>
      <PillSelect v-model="tagFilter" :options="tagOptions" aria-label="按模块筛选" />
      <input
        v-model="keyword"
        class="min-w-0 flex-1 rounded-md border border-line bg-field px-2 py-1 text-[11px] text-ink outline-none focus:border-accent"
        placeholder="搜索关键词（如 401、cache、失败）"
      />
      <label class="flex items-center gap-1 text-[11px] text-ink-3" title="暂停轮询（不再拉新日志），滚动仍可自由使用">
        <input v-model="paused" type="checkbox" @change="schedule()" />
        暂停刷新
      </label>
    </div>

    <p v-if="loading" class="py-10 text-center text-sm text-ink-3">正在读取…</p>

    <!-- 终端风格面板：等宽字体 + 深底，长行可横向滚动 -->
    <div
      v-else
      class="overflow-hidden rounded-xl border border-line bg-[#0b0d12]"
    >
      <div class="flex items-center gap-2 border-b border-line px-3 py-1.5 text-[11px] text-ink-3">
        <span class="h-2 w-2 rounded-full bg-hot/70" />
        <span class="h-2 w-2 rounded-full bg-warn/70" />
        <span class="h-2 w-2 rounded-full bg-ok/70" />
        <span class="ml-1">meow-console.log</span>
        <span class="ml-auto">
          <template v-if="serverOk">
            服务端 {{ serverStats.total }}/{{ serverStats.capacity }}
            <template v-if="serverStats.level"> · 级别 {{ serverStats.level }}</template>
            <template v-if="serverStats.dropped"> · 丢弃 {{ serverStats.dropped }}</template>
          </template>
          <template v-else-if="serverState === 'unsupported'">服务端：旧版无日志端点</template>
          <template v-else-if="serverState === 'unreachable'">服务端不可达</template>
          <template v-else>服务端：{{ serverDetail || '读取失败' }}</template>
          · 显示 {{ shown.length }}<template v-if="hiddenCount"> / {{ filtered.length }}</template> 条
        </span>
      </div>

      <div v-if="error && !filtered.length" class="p-6 text-center text-[12px] text-ink-3">
        {{ error }}
      </div>
      <div v-else-if="!filtered.length" class="p-10 text-center text-[12px] text-ink-3">
        没有符合条件的日志。
      </div>

      <!--
        data-tv-scroll + tabindex=0：无遥控器时这是一个普通滚动区，
        有遥控器时按 tv-nav 的约定成为"唯一焦点停靠点"，上下键翻页
        （几千行日志每行都做成可聚焦会让方向键按到崩溃）。
        滚到底后按键会交还给焦点移动，不会把人困在这里。
      -->
      <div
        v-else
        ref="listEl"
        data-tv-scroll
        tabindex="0"
        class="max-h-[60vh] overflow-auto outline-none"
        @scroll.passive="onListScroll"
      >
        <table class="w-full table-fixed border-collapse text-[11px]">
          <tbody>
            <!--
              只渲染最近 rowCap 条。日志单条最长 4000 字（带堆栈），一次铺 2000 行
              在 TV/4K 上滚动会掉帧；DOM 有上界，而复制/导出仍按全量 filtered 走。
            -->
            <tr v-if="hiddenCount" class="border-b border-white/5">
              <td colspan="4" class="px-2 py-1.5 text-center">
                <button
                  class="rounded-md bg-white/8 px-3 py-1 text-[11px] text-ink-2 transition hover:bg-white/14 hover:text-ink"
                  @click="showMore"
                >
                  加载更早的 {{ ROWS_STEP }} 条（还有 {{ hiddenCount }} 条未显示）
                </button>
              </td>
            </tr>
            <tr
              v-for="it in shown"
              :key="it.key"
              class="border-b border-white/5 align-top hover:bg-white/[0.03]"
            >
              <!--
                时间列宽 78px：8 个字符（12:44:42）在 11px 等宽下约 53px，
                再加左右各 8px 内边距 = 69px。原来写 62px，真机（2560×1440）
                上时间会压到 INFO 徽标上 —— 桌面端看不出，电视上很明显。
              -->
              <td class="w-[78px] shrink-0 whitespace-nowrap px-2 py-1 font-mono text-ink-3">
                {{ clock(it.at) }}
              </td>
              <td class="w-[52px] px-1 py-1">
                <span class="rounded px-1 py-0.5 font-mono text-[10px]" :class="levelBg(it.level)">
                  {{ LEVEL_LABEL[it.level] }}
                </span>
              </td>
              <td class="w-[70px] truncate px-1 py-1 font-mono text-[10px] text-ink-3" :title="`${it.origin === 'server' ? 'srv' : 'cli'}:${it.tag}`">
                {{ it.origin === 'server' ? 'srv' : 'cli' }}:{{ it.tag }}
              </td>
              <!--
                默认单行省略（滚动成本与行高都稳定），点一下展开完整文本（堆栈）。
                原来是无条件 `whitespace-pre-wrap`：一条 8 行堆栈就占 8 行高度，
                2000 条里有几百条堆栈时，滚动高度上万像素、每帧都在重排文本。
              -->
              <td class="px-2 py-1 font-mono" :class="levelClass(it.level)">
                <div class="flex items-start gap-2">
                  <span
                    class="min-w-0 flex-1 cursor-pointer"
                    :class="expandedKey === it.key ? 'whitespace-pre-wrap break-all' : 'block truncate'"
                    :title="expandedKey === it.key ? '' : it.text"
                    @click="toggleExpand(it.key)"
                  >{{ it.text }}</span>
                  <span
                    v-if="it.count && it.count > 1"
                    class="shrink-0 rounded bg-white/10 px-1 text-[10px] text-ink-3"
                    :title="`同一内容在 5 秒内重复 ${it.count} 次（已合并）`"
                  >×{{ it.count }}</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <p class="mt-2 text-[11px] leading-relaxed text-ink-3">
      数据保留最近 2000 条（服务端环形缓冲，含字节上限；挤满后显示「已丢弃」）；
      列表只渲染最近 {{ ROWS_STEP }} 条，点「加载更早」再放一屏，复制/导出始终按筛选后的全量。
      <code class="text-ink-2">srv</code> 是后端日志，<code class="text-ink-2">cli</code>
      是浏览器侧的错误与网络记录。健康检查、分片请求、进度同步等高频调用不记录，避免把有用的错误冲掉。
      <template v-if="serverStats.file">
        后端日志同时写入 <code class="text-ink-2">{{ serverStats.file }}</code>（2MB 轮转），
        重启后的现场去那里看。
      </template>
    </p>
  </div>
</template>
