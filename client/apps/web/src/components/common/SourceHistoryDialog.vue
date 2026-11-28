<script setup lang="ts">
/**
 * 「导入历史」弹框：最近成功导入过的配置地址，点一条回填到地址框。
 *
 * <p>为什么需要：输入框里只留"最后粘贴的那一条"，多仓切仓 / 换源之后想回到
 * 上一条地址就得重新找（用户 2026-11-28 提的口径：导入源这里加个历史图标，
 * 弹框里能回溯之前用过的 url）。
 *
 * <p>三个硬约定照搬 AboutDialog（设置页弹层的既有规矩）：
 *  1. **Teleport to body** —— 设置页 `<main>` 是 `relative z-10`，弹框留在里面
 *     会被 sticky z-30 的顶栏盖住；
 *  2. **`data-tv-scope`** —— tv-nav 据此把方向键收窄到弹框内；
 *  3. **列表区 `data-tv-scroll` + `tabindex="0"`** —— 缺一个遥控器就滚不动。
 */
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ConfigHistoryEntry } from '@/api'

const props = defineProps<{
  open: boolean
  entries: ConfigHistoryEntry[]
  /** 当前地址框里的值：同一条打「已填」标记，避免用户重复点 */
  current?: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'pick', url: string): void
}>()

const scroller = ref<HTMLElement>()
const firstItem = ref<HTMLElement>()
const closeBtn = ref<HTMLElement>()

function focusEl(el: HTMLElement | undefined | null, pin = true): void {
  if (!el) return
  const w = window as unknown as {
    __meowTvNav?: unknown
    __meowTvFocus?: (t: Element, pin?: boolean) => boolean
  }
  if (typeof w.__meowTvNav === 'function' && typeof w.__meowTvFocus === 'function') {
    w.__meowTvFocus(el, pin)
    return
  }
  el.focus()
}

/** 一条历史的时间：本地 MM-DD HH:mm（跨年才带年份，行里不塞长串） */
function when(at?: number): string {
  if (!at) return ''
  const d = new Date(at)
  const p = (n: number) => String(n).padStart(2, '0')
  const sameYear = d.getFullYear() === new Date().getFullYear()
  return `${sameYear ? '' : d.getFullYear() + '-'}${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(
    d.getHours(),
  )}:${p(d.getMinutes())}`
}

/** 地址太长时中段省略，保留主机与末段（能认出是哪个仓） */
function shortUrl(u: string): string {
  if (u.length <= 64) return u
  return `${u.slice(0, 34)}…${u.slice(-24)}`
}

function onKey(e: KeyboardEvent) {
  if (!props.open) return
  if (e.key === 'Escape' || e.key === 'Backspace') {
    e.preventDefault()
    e.stopPropagation()
    emit('close')
  }
}

/** 第一条的 DOM 引用（`:ref` 在 v-for 里只能给函数） */
function setFirst(el: unknown): void {
  if (el instanceof HTMLElement) firstItem.value = el
}

watch(
  () => props.open,
  async (v) => {
    if (!v) return
    await nextTick()
    // 有列表 → 焦点落第一条（遥控器按 OK 直接回填）；空列表 → 落「关闭」，别悬空
    focusEl(firstItem.value || closeBtn.value)
  },
)

onMounted(() => window.addEventListener('keydown', onKey, true))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey, true))
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      data-tv-scope
      class="fixed inset-0 z-[130] flex items-center justify-center bg-black/70 p-4 sm:p-6"
      @click.self="emit('close')"
    >
      <div
        class="flex max-h-full w-full max-w-lg flex-col overflow-hidden rounded-xl border border-line bg-base"
        style="box-shadow: 0 24px 60px -12px rgba(0, 0, 0, 0.75)"
        role="dialog"
        aria-modal="true"
        aria-label="导入历史"
      >
        <div class="flex shrink-0 items-center gap-3 border-b border-line px-4 py-3">
          <div class="min-w-0 flex-1">
            <div class="text-sm font-medium text-ink">导入历史</div>
            <div class="mt-0.5 text-[11px] text-ink-3">
              最近导入过的配置地址，点一条回填到地址框
            </div>
          </div>
          <button
            ref="closeBtn"
            type="button"
            class="rounded-md bg-card px-2.5 py-1 text-[11px] text-ink transition hover:bg-card-hover"
            @click="emit('close')"
          >
            关闭
          </button>
        </div>

        <div
          ref="scroller"
          data-tv-scroll
          tabindex="0"
          class="min-h-0 flex-1 overflow-y-auto px-2 py-2 outline-none"
        >
          <p v-if="!entries.length" class="py-10 text-center text-xs text-ink-3">
            还没有导入记录。导入一次配置后，地址会留在这里。
          </p>
          <button
            v-for="(e, i) in entries"
            :key="e.url"
            :ref="i === 0 ? setFirst : undefined"
            type="button"
            :data-tv-first="i === 0 ? true : undefined"
            class="flex w-full flex-col gap-0.5 rounded-md px-3 py-2 text-left transition hover:bg-card-hover"
            :class="e.url === current ? 'bg-accent/10' : ''"
            :title="e.url"
            @click="emit('pick', e.url)"
          >
            <span class="flex items-center gap-2">
              <span class="min-w-0 flex-1 truncate font-mono text-[12px] text-ink">
                {{ shortUrl(e.url) }}
              </span>
              <span
                v-if="e.url === current"
                class="shrink-0 rounded-full bg-accent/20 px-1.5 py-0.5 text-[10px] text-accent"
              >
                当前
              </span>
            </span>
            <span class="flex flex-wrap items-center gap-x-2 text-[11px] text-ink-3">
              <span v-if="when(e.at)" class="tabular-nums">{{ when(e.at) }}</span>
              <span v-if="e.sites != null">可用 {{ e.sites }} 站</span>
              <span v-if="e.total != null && e.total > (e.sites || 0)">
                共 {{ e.total }}
              </span>
              <span v-if="e.mode">{{ e.mode === 'replace' ? '替换' : '追加' }}</span>
            </span>
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
