<script setup lang="ts">
/**
 * 账号与安全弹框（auth v2）。
 *
 * 从设置页「连接」区的按钮打开；内容复用 {@link AccountPanel}。
 *
 * 三条与 ConfirmDialog/AboutDialog 一致的硬约定：
 *  1. **Teleport to body**（经 useOverlayHost：全屏播放页要 Teleport 进全屏元素，
 *     否则一个像素都不画）——设置页 `<main>` 是 `relative z-10` 层叠上下文，
 *     弹框留在里面会被顶栏盖住；
 *  2. **`role="dialog"`** —— App.vue 的全局 Esc 处理看到它就不做 router.back，
 *     关闭由本组件自己的 Esc/BACK 监听负责；
 *  3. **`data-tv-scope`** —— tv-nav 把方向键收窄到弹框内；同一元素上的
 *     **`data-tv-idle-keep`** 让它豁免 tv-nav 的「20 秒空闲自动关」——
 *     这屏有设备清单/配对码要停下来看，被定时器收掉会被误认成「方向键按没了」。
 *
 * ★ 内容区**不能**加 `data-tv-scroll`。
 *
 *   `data-tv-scroll` 的语义是「整块可滚、内部没有可聚焦元素」（AboutDialog 的
 *   纯文字正文、日志列表）：容器自己成为焦点停靠点，上下键先滚容器、滚到头
 *   才移动焦点。本面板里全是 input/select/button，加了之后遥控器按上下只会
 *   滚内容，焦点走不到「新密码」「生成令牌」（2026-11-20 MuMu .77 实测：
 *   用户反馈「方向键支持受限」）。
 *
 *   不加也会滚：tv-nav 的 `scrollIntoViewWithin()` 每次焦点移动后会找
 *   `overflow-y:auto` 的祖先做最小滚动，那个祖先就是这里的 `overflow-y-auto`。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import AccountPanel from '@/components/AccountPanel.vue'
import { useOverlayHost } from '@/composables/useOverlayHost'

const emit = defineEmits<{
  (e: 'close'): void
}>()

const host = useOverlayHost()

/** 头部「返回」：面板里没有任何输入框时的保底落点 */
const backBtn = ref<HTMLButtonElement>()
/** 内容区：用来找第一个可见输入框当初始焦点（表单语义优先于「返回」） */
const body = ref<HTMLDivElement>()

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

/**
 * 弹框内的初始焦点：第一个**可见**的输入框，退回「返回」。
 *
 * <p>为什么不是「返回」：这一屏是表单，用户按 OK 打开就是要改密/建设备。
 * 落在「返回」上时，第一下方向键还得先猜内容在哪；落在「当前密码」上，
 * 下键就是新密码、再下是按钮，符合电视上「一路按下去」的习惯。
 *
 * <p>`visible` 判断不能省：AccountPanel 里三组表单按权限条件渲染，
 * 隐藏节点的 getBoundingClientRect 全是 0，focus 上去焦点框会画在屏幕角落。
 */
function firstField(): HTMLElement | null {
  const root = body.value
  if (!root) return null
  const list = root.querySelectorAll<HTMLElement>('input, select, textarea')
  for (let i = 0; i < list.length; i++) {
    const el = list[i]
    if (el.hasAttribute('disabled') || el.getAttribute('type') === 'hidden') continue
    const r = el.getBoundingClientRect()
    if (r.width > 0 && r.height > 0) return el
  }
  return null
}

function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape' || e.key === 'Backspace') {
    /*
     * 内层弹层（TileSelect 的下拉菜单、原生 select 弹窗）优先。
     *
     * 它们自己监听 Escape 关自己；本监听在 capture 阶段，会抢在它们前面。
     * 不判的话在「权限」下拉里按返回，会连整个账号弹框一起关掉。
     * 判据：DOM 里同时存在多个 `[data-tv-scope]` = 有内层弹层开着。
     */
    if (document.querySelectorAll('[data-tv-scope]').length > 1) return
    e.preventDefault()
    e.stopPropagation()
    emit('close')
  }
}

/** 我们自己标的 `data-tv-first` 元素：卸载时只清这一个，不动别人的 */
let firstMarked: HTMLElement | null = null

/**
 * 标 `data-tv-first`：tv-nav 的弹层落点约定。
 *
 * <p>只调 `__meowTvFocus` 不够 —— 它内部会 `pin` 住 1.2 秒，如果这一下因为
 * 布局未完成被判"不可见"而返回 false，连 MutationObserver 的 300ms 兜底纠正
 * 也一起被 pin 挡掉，结果就是**弹框里没有任何焦点框**（2026-11-20 MuMu .77
 * 实测：打开弹框后按方向键直接跳到设备列表的「撤销」）。
 *
 * <p>标上属性后，tv-nav 的 `preferredItem()` 在兜底时会优先选它 —— 双保险。
 */
function markFirst(el: HTMLElement | null): void {
  if (firstMarked && firstMarked !== el) firstMarked.removeAttribute('data-tv-first')
  firstMarked = el
  if (el) el.setAttribute('data-tv-first', '')
}

/**
 * 焦点守护窗口（10 秒 × 300ms，用户一按键就停）。
 *
 * <p>踩过（2026-11-20 MuMu .77，带 computedStyle 诊断定位）：弹框刚打开时
 * `__meowTvFocus` 把 `meow-tv-focus` 加到了「当前密码」上，但设备列表/配置密钥
 * 异步加载完成后 Vue 重渲染会把 class 抹掉（tv-nav 自己的注释也记着这个坑，
 * 它只在**按键时**用 `currentFocus()` 补）。元素也可能被整体替换，所以每次都
 * 用 `firstField()` **重新查**，不持有旧引用。
 *
 * <p>补的判据是「**弹框内**没有焦点框」。不能用整页 querySelector：背景设置页
 * 的后端地址输入框也是 INPUT，会命中它 → guard 以为"有焦点"，实际焦点被弹框
 * 盖在后台，用户按方向键像是"没反应"（实测踩过）。
 */
let guardTimer = 0

function armFocusGuard(): void {
  if (guardTimer) window.clearInterval(guardTimer)
  const stop = (): void => {
    if (guardTimer) window.clearInterval(guardTimer)
    guardTimer = 0
  }
  // 用户开始用遥控器就不再抢焦点（capture + once：任何键的第一下就停）
  window.addEventListener('keydown', stop, { once: true, capture: true })
  let left = 33
  guardTimer = window.setInterval(() => {
    const inside = body.value ? body.value.querySelector('.meow-tv-focus') : null
    if (!inside) {
      const el = firstField() || backBtn.value || null
      if (el) {
        markFirst(el)
        focusEl(el, false)
      }
    }
    if (--left <= 0) {
      window.clearInterval(guardTimer)
      guardTimer = 0
    }
  }, 300)
}

function applyInitialFocus(): void {
  const el = firstField() || backBtn.value || null
  markFirst(el)
  // pin=false：这一下失败也要让 tv-nav 的 observer 还能兜底
  focusEl(el, false)
}

onMounted(() => {
  // capture：面板里的 input 会吃掉冒泡阶段的 Escape（INPUT 目标在全局处理里被跳过）
  window.addEventListener('keydown', onKey, true)
  /*
   * 初始焦点分两拍设（40ms / 320ms），不能同步设。
   *
   * 踩过（2026-11-20 MuMu .77 实测）：onMounted 里同步 focus 时弹框刚插入 DOM、
   * 样式还没算，`getBoundingClientRect()` 全 0 —— tv-nav 的 `visible()` 判它不可见，
   * `__meowTvFocus` 直接返回 false，焦点框留在背景页面上。用户按下第一下方向键
   * 时 tv-nav 找不到弹层内的起点，表现就是「弹框里方向键不管用」。
   *
   * 第二拍与 tv-nav observer 的 300ms 节流同频；两拍都失败时，observer 会靠
   * `data-tv-first` 兜底（markFirst 已标好）。之后交给守护窗口防止被重渲染抹掉。
   */
  requestAnimationFrame(() => setTimeout(applyInitialFocus, 40))
  setTimeout(applyInitialFocus, 320)
  armFocusGuard()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey, true)
  if (guardTimer) window.clearInterval(guardTimer)
  guardTimer = 0
  if (firstMarked) firstMarked.removeAttribute('data-tv-first')
  firstMarked = null
})
</script>

<template>
  <Teleport :to="host">
    <div
      role="dialog"
      aria-label="账号与安全"
      data-tv-scope
      data-tv-idle-keep
      class="fixed inset-0 z-[120] flex items-center justify-center bg-black/70 p-4 sm:p-6"
      @click.self="emit('close')"
    >
      <div
        class="flex max-h-full w-full max-w-xl flex-col overflow-hidden rounded-xl border border-line bg-base"
        style="box-shadow: 0 24px 60px -12px rgba(0, 0, 0, 0.75)"
      >
        <div class="flex shrink-0 items-center gap-3 border-b border-line px-4 py-3">
          <div class="min-w-0 flex-1">
            <div class="text-sm font-medium text-ink">账号与安全</div>
            <div class="mt-0.5 text-[11px] text-ink-3">密码 · 设备令牌 · TV 配对 · 配置密钥</div>
          </div>
          <button
            ref="backBtn"
            type="button"
            class="rounded-md bg-card px-2.5 py-1 text-[11px] text-ink transition hover:bg-card-hover"
            @click="emit('close')"
          >
            返回
          </button>
        </div>

        <!--
          内容区：只留 overflow-y-auto，**不要** data-tv-scroll / tabindex。
          原因见脚本头部第 3 条：那会把容器变成焦点停靠点，焦点永远走不到表单控件。
        -->
        <div ref="body" class="min-h-0 flex-1 overflow-y-auto px-4 py-3 outline-none">
          <AccountPanel />
        </div>
      </div>
    </div>
  </Teleport>
</template>
