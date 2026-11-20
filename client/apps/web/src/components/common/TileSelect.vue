<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

/**
 * 磁贴样式的下拉选择。
 *
 * 视觉上仍是一块磁贴（和周围对齐），点开后弹出选项列表。
 * 相比"点一下切下一个"，选项多时不用来回点。
 */
interface Option {
  value: string | number
  label: string
  hint?: string
  /**
   * 可选的色板预览（主题选择这类"看色挑"的选项用）。
   * 不传就不渲染，其他下拉的行为完全不变。
   */
  swatch?: string[]
}

const props = defineProps<{
  modelValue: string | number
  options: Option[]
  /** 左侧标题，例如「安全DNS」 */
  label: string
  /** 弹层宽度，默认和磁贴同宽 */
  menuWidth?: string
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
  const est = Math.min(props.options.length * 34 + 12, 300)
  const below = window.innerHeight - r.bottom
  menuStyle.value = {
    width: props.menuWidth || `${r.width}px`,
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
 * 滚动时收起菜单。
 *
 * 菜单 Teleport 到 body，位置只在打开那一刻算一次。页面一滚它就悬在
 * 错误的地方（用户看到的是"弹框飘在磁贴外面"）。
 * 重算位置也行，但滚动中菜单跟着磁贴跑会抖，直接收起更稳。
 *
 * ★ 必须用捕获阶段：设置页滚的不是 window/body，而是内层 overflow-y-auto
 *   的 div，scroll 事件不冒泡，不 capture 收不到。
 */
function onAnyScroll(e: Event) {
  if (!open.value) return
  // 菜单自身滚动（选项超过 300px 时）不算
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
  /*
   * 关闭 → 把焦点还给打开它的那个磁贴。
   *
   * 用户要求「返回键关闭弹框，同时聚焦到原位置」。tv-nav 自己也会在
   * scope 消失时还焦点，但它要等 MutationObserver（下一拍）；这里
   * 同步调一次，画面不会出现"焦点框悬在半空"的一瞬。
   * 非 TV 环境没有这个全局函数，用 typeof 判掉。
   */
  if (v) return
  const w = window as unknown as { __meowTvScopeClosed?: () => boolean }
  if (typeof w.__meowTvScopeClosed === 'function') w.__meowTvScopeClosed()
})
</script>

<template>
  <div ref="root" class="relative">
    <button
      type="button"
      class="tile group w-full"
      :class="{ 'tile--open': open }"
      @click="toggle"
    >
      <span class="truncate text-[15px] text-ink">{{ label }}</span>
      <span class="ml-auto flex shrink-0 items-center gap-1.5">
        <span v-if="current?.swatch?.length" class="flex shrink-0 items-center gap-1">
          <span
            v-for="(c, i) in current.swatch"
            :key="i"
            class="h-2.5 w-2.5 rounded-full ring-1 ring-inset ring-line-hover"
            :style="{ backgroundColor: c }"
          />
        </span>
        <span class="text-[13px] text-ink-2">{{ current?.label }}</span>
        <svg
          class="h-3.5 w-3.5 text-ink-3 transition group-hover:text-accent"
          :class="open ? 'rotate-180 text-accent' : ''"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
        >
          <path d="m6 9 6 6 6-6" />
        </svg>
      </span>
    </button>

    <Teleport to="body">
      <!--
        data-tv-scope：菜单 Teleport 到 body，脱离了原页面结构。
        tv-nav 的作用域机制只认 `[data-tv-scope]`，不标的话菜单打开后
        方向键会去背景的磁贴上找邻居（菜单里的选项既选不中也关不掉）。
        标上之后焦点被收窄到菜单内，上下键在选项间走、ok 选中。

        ★ z-index 必须高于所有弹窗（弹窗 120 / 全屏提示 130）。
        原来是 z-[100]，在账号与安全弹框（z-120）里点「权限」时菜单被整个
        盖住 —— 磁贴变成打开态、菜单看不见（2026-11-20 实测截图）。
      -->
      <div
        v-if="open"
        ref="menu"
        data-tv-scope
        class="tile-menu fixed z-[140] overflow-y-auto rounded-lg border border-line bg-elevated p-1.5 shadow-2xl"
        :style="menuStyle"
      >
        <button
          v-for="o in options"
          :key="o.value"
          type="button"
          :data-tv-current="o.value === modelValue ? '' : undefined"
          class="flex w-full items-center gap-2 rounded-md px-3 py-1.5 text-left text-[13px] transition"
          :class="
            o.value === modelValue
              ? 'bg-accent font-medium text-on-accent'
              : 'text-ink-2 hover:bg-card-hover hover:text-ink'
          "
          @click="pick(o.value)"
        >
          <span v-if="o.swatch?.length" class="flex shrink-0 items-center gap-1">
            <span
              v-for="(c, i) in o.swatch"
              :key="i"
              class="h-2.5 w-2.5 rounded-full ring-1 ring-inset ring-line-hover"
              :style="{ backgroundColor: c }"
            />
          </span>
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

<style scoped>
</style>

<!--
  菜单被 Teleport 到 body 下，scoped 样式够不到，所以这里有第二个非 scoped 块。
  用 CSS animation 而不是 Vue <Transition>：Teleport + Transition 时
  enter-from 类偶尔不会被移除，菜单会一直停在 opacity:0。
-->
<style>
@keyframes tile-menu-in {
  from {
    opacity: 0;
    transform: translateY(-4px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.tile-menu {
  animation: tile-menu-in 0.14s ease both;
  background-color: var(--color-elevated);
}
</style>
