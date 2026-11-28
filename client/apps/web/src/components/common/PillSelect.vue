<script setup lang="ts">
/**
 * 胶囊样式的下拉选择（筛选条用）。
 *
 * <p>从哪来的：日志页筛「模块」原来是原生 `<select>`。原生 select 在 TV/遥控器上
 * **不可聚焦**（壳里没有系统弹窗，方向键根本进不去），用户实测「TV 无法聚焦」。
 * 顶栏的站点下拉是自绘的，遥控器可用 —— 这里把它的样式与选焦行为抽成一个
 * 小控件，筛选条里的下拉统一用它。
 *
 * <p>与 TileSelect 的分工：那个是设置页的整行磁贴（label + 当前值 + 色板），
 * 这个是筛选条里的一枚小胶囊，只显示当前值。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

interface Option {
  value: string | number
  label: string
}

const props = defineProps<{
  modelValue: string | number
  options: Option[]
  /** 无障碍名（读屏 / 读给用户听的东西），不给就用当前值 */
  ariaLabel?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: string | number): void
}>()

const open = ref(false)
const root = ref<HTMLElement>()
/** 菜单 Teleport 到 body，不在 root 内，点外判定必须单独算 */
const menu = ref<HTMLElement>()
const menuStyle = ref<Record<string, string>>({})

const current = computed(
  () => props.options.find((o) => o.value === props.modelValue) || props.options[0],
)

async function toggle() {
  open.value = !open.value
  if (!open.value) return
  // 靠近底部时向上弹，避免被窗口裁掉
  await nextTick()
  const r = root.value?.getBoundingClientRect()
  if (!r) return
  const est = Math.min(props.options.length * 34 + 12, 320)
  const below = window.innerHeight - r.bottom
  menuStyle.value = {
    width: `max(${r.width}px, 11rem)`,
    ...(below < est + 16
      ? { bottom: `${window.innerHeight - r.top + 6}px`, top: 'auto' }
      : { top: `${r.bottom + 6}px`, bottom: 'auto' }),
    left: `${Math.max(8, Math.min(r.left, window.innerWidth - r.width - 8))}px`,
  }
}

function pick(v: string | number) {
  emit('update:modelValue', v)
  open.value = false
}

function onDocClick(e: MouseEvent) {
  const t = e.target as Node
  // Teleport 到 body 的菜单不在 root 里。只判 root 会把点选项当成点击外部，
  // mousedown 先关菜单 → click 落到已卸载节点上，选项选不中。
  if (root.value?.contains(t) || menu.value?.contains(t)) return
  open.value = false
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') open.value = false
}

/**
 * 滚动时收起菜单（菜单位置只在打开那一刻算一次，滚动后会飘走）。
 * 捕获阶段：页面滚的是内层 overflow-y-auto 容器，scroll 不冒泡。
 */
function onAnyScroll(e: Event) {
  if (!open.value) return
  if (menu.value && e.target instanceof Node && menu.value.contains(e.target)) return
  open.value = false
}

onMounted(() => {
  document.addEventListener('mousedown', onDocClick)
  document.addEventListener('keydown', onKey)
  document.addEventListener('scroll', onAnyScroll, true)
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocClick)
  document.removeEventListener('keydown', onKey)
  document.removeEventListener('scroll', onAnyScroll, true)
})

watch(open, (v) => {
  // 关闭 → 焦点还给触发按钮（遥控器按返回后焦点不该落到背景卡片上）
  if (v) return
  const w = window as unknown as { __meowTvScopeClosed?: () => boolean }
  if (typeof w.__meowTvScopeClosed === 'function') w.__meowTvScopeClosed()
})
</script>

<template>
  <div ref="root" class="relative shrink-0">
    <button
      type="button"
      class="flex items-center gap-1 rounded-md border border-line bg-field px-2.5 py-1 text-[11px] text-ink-2 transition hover:bg-card-hover hover:text-ink"
      :aria-label="ariaLabel || current?.label"
      :aria-expanded="open"
      @click="toggle"
    >
      <span class="max-w-[9rem] truncate">{{ current?.label }}</span>
      <svg
        class="h-3 w-3 shrink-0 opacity-60 transition"
        :class="open ? 'rotate-180' : ''"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2.5"
      >
        <path d="m6 9 6 6 6-6" />
      </svg>
    </button>

    <Teleport to="body">
      <!--
        data-tv-scope：菜单脱离页面结构挂在 body 上，tv-nav 的作用域只认这个标记。
        不标的话方向键会回到背景里找邻居，选项既选不中也关不掉。
      -->
      <div
        v-if="open"
        ref="menu"
        data-tv-scope
        class="pill-menu fixed z-[100] overflow-y-auto rounded-lg border border-line p-1.5 shadow-2xl"
        :style="menuStyle"
      >
        <button
          v-for="o in options"
          :key="String(o.value)"
          type="button"
          :aria-selected="o.value === modelValue ? 'true' : undefined"
          :data-tv-first="o.value === modelValue ? true : undefined"
          class="flex w-full items-center gap-2 rounded-md px-3 py-2 text-left text-sm transition"
          :class="
            o.value === modelValue
              ? 'bg-accent font-medium text-on-accent'
              : 'text-ink-2 hover:bg-card-hover hover:text-ink'
          "
          @click="pick(o.value)"
        >
          <span class="flex-1 truncate">{{ o.label }}</span>
          <svg
            v-if="o.value === modelValue"
            class="h-3.5 w-3.5 shrink-0"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="3"
          >
            <path d="m5 13 4 4L19 7" />
          </svg>
        </button>
      </div>
    </Teleport>
  </div>
</template>

<!-- 菜单被 Teleport 到 body，scoped 样式够不到，所以这里是非 scoped 块 -->
<style>
@keyframes pill-menu-in {
  from {
    opacity: 0;
    transform: translateY(-4px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.pill-menu {
  animation: pill-menu-in 0.14s ease both;
  background-color: var(--color-elevated);
}
</style>
