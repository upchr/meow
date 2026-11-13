<script setup lang="ts">
/**
 * 「发现新版本」居中弹框。
 *
 * ## 为什么不直接用 CenterDialog
 *
 * `CenterDialog` 只接受 `{id,label}` 选项列表，装不下"标题 + 版本号 + 更新日志
 * 多行 + 进度条"这套内容。但**视觉与交互契约照搬它**，两条硬约束必须一致：
 *
 *   1. **不 Teleport**：`tv-nav.js` 的焦点是按几何最近邻算的，Teleport 到
 *      body 后焦点不会跟过去（CenterDialog 的注释里记了实测）。
 *   2. **根节点带 `data-tv-scope`**：tv-nav 据此把可用焦点收窄到弹框内，
 *      否则方向键会跑到背景的卡片上（`activeScope()` 只认这个属性）。
 *
 * ## 按键语义（TV 上没有鼠标）
 *
 * - 上下：在「更新」「稍后」之间移动（tv-nav 的几何最近邻，竖排最不容易迷路）
 * - 确认：触发当前焦点按钮（tv-nav 直接 `.click()`）
 * - 返回 / Esc：等同「稍后」——用户想跳过就必须能退出，否则整屏被占住
 *
 * 返回键靠**原生侧**的 `__meowTvConsumeBack`：`App.vue` 在弹框打开期间接管它，
 * 组件自身只处理键盘 Esc（浏览器/PC 用）。两处都指向同一个 `onLater`。
 *
 * ## 为什么下载中不能点「稍后」关掉
 *
 * 下载是原生侧的常驻任务，关掉弹框它仍在跑。用户会以为"没在下载了"，
 * 然后在设置页看到进度还在涨。下载中把「稍后」换成「取消下载」并保留
 * 进度可见，是唯一不产生歧义的形态。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fileNameFor, formatNoteLines } from '@/api/appUpdate'
import {
  busy,
  closeDialog,
  error,
  found,
  percent,
  phase,
  platform,
  runCancel,
  runDownloadAndInstall,
  stage,
} from '@/stores/appUpdate'

const emit = defineEmits<{
  (e: 'close'): void
}>()

/** 主按钮（默认焦点） */
const primary = ref<HTMLButtonElement>()
/** 次按钮 */
const secondary = ref<HTMLButtonElement>()
/** 更新日志滚动区（TV 上作为方向键的滚动停靠点） */
const notesEl = ref<HTMLElement>()
/** 日志是否被截断（决定初始焦点与「↑↓ 滚动」提示） */
const notesOverflow = ref(false)

/** 更新日志按行拆开：`desc` 里存的是多行文本，原样塞进一个 div 会丢掉换行 */
const noteLines = computed(() => {
  return formatNoteLines(found.value?.notes || '')
})

/** 平台名（标题里给用户看，不写清单 id） */
/**
 * 标题里的平台名。
 *
 * <p>★ 取的是**当前设备**（`platform`），不是清单条目的 `packageId`。
 * 这不是笔误：这一行说的是"这个包装到哪台机器上"，所以必须跟设备一致。
 * 条目 id 与设备不符的情况已经在 `checkAppUpdate` / `startDownload` 里
 * 被三层校验拦下了（见 `entryFitsPlatform`），走不到这一步。
 */
const platformName = computed(() => (platform.value === 'windows-x64' ? 'Windows' : 'Android'))

/** 将要下载的文件名（原生侧会再净化一次，这里只是给用户看） */
const fileName = computed(() => (found.value ? fileNameFor(found.value) : ''))

/** 文件大小（原生给的是字节） */
function fmtSize(n: number): string {
  if (!n) return ''
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

/** 下载中 = 取消下载；安装阶段不可取消（安装器已经交出去了）；其余 = 稍后 */
const secondaryLabel = computed(() => {
  if (phase.value === 'downloading') return '取消下载'
  if (phase.value === 'installing') return '安装中…'
  return '稍后'
})
/** 安装阶段禁用次按钮：这时点「稍后」既关不掉弹框也会让人以为能中断 */
const secondaryDisabled = computed(() => phase.value === 'installing')

/**
 * 主按钮：**只走一次**。
 *
 * <p>2026-11-13 修掉的死循环：原来这里不看状态，永远调
 * `runDownloadAndInstall()`；下载完之后按钮文案变成「安装」，可点下去
 * 还是重新下载 —— 用户看到进度条从头再来一遍，永远装不上。
 * 现在下载完成会在 store 里直接调起系统安装器（一步到位），
 * 所以这里不需要、也不允许再有第二个分支。
 */
function onPrimary(): void {
  if (busy.value) return
  void runDownloadAndInstall()
}

function onSecondary(): void {
  if (phase.value === 'downloading') {
    void runCancel()
    return
  }
  if (phase.value === 'installing') return
  onLater()
}

/** 「稍后」= 记住这个版本 + 关闭（下载中会被 store 拒绝） */
function onLater(): void {
  // 只有这个按钮算「表态」：记住版本，下次只有更高版本才提示
  if (closeDialog({ skip: true })) emit('close')
}

/** 点空白 / Esc / 电视返回：只关，不记住版本 */
function onDismiss(): void {
  if (closeDialog()) emit('close')
}

/**
 * 量一下日志区是否被截断。
 *
 * <p>TV 上日志一长就看不到后面的条目（用户 2026-11-13 反馈「弹框要能用
 * 方向键上下滚动看更新内容」）。判据用 scrollHeight/clientHeight，
 * 与详情页简介折叠同一个手法（实测可靠）。
 */
function measureNotes(): void {
  const el = notesEl.value
  notesOverflow.value = !!el && el.scrollHeight > el.clientHeight + 2
}

function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape' || e.key === 'Backspace') {
    e.preventDefault()
    onDismiss()
  }
}

/**
 * 上一任返回键处理器（挂载前的那个）。
 *
 * ★ 为什么要抢而不是让 App 统一处理：播放页 `VideoPlayer` 会把
 *   `window.__meowTvConsumeBack` 换成自己的（按「全屏→面板→控制栏」退层），
 *   而 App.vue 只在首页挂。启动检查可能正好在播放页命中新版本，
 *   此时弹框必须比播放页先拿到返回键，否则按返回会先关播放器面板、
 *   弹框留在原地盖着画面。
 *
 * ★ 卸载时必须还原而不是置空：置空会把播放页刚挂的那个清掉，
 *   之后按返回直接退回整集（`fallbackBack` 的注释里记过这个坑）。
 */
let prevBackHandler: (() => boolean) | null = null

function takeOverBackKey(): void {
  const w = window as unknown as { __meowTvConsumeBack?: (() => boolean) | null }
  prevBackHandler = w.__meowTvConsumeBack || null
  w.__meowTvConsumeBack = () => {
    // 先关弹框；下载中关不掉也吃掉这次返回 —— 否则事件落到下面，
    // 用户会看到"按返回没反应，但播放器面板被关掉了"。
    // 返回键只是"先不看"，不等于「稍后」—— 不能记住版本（踩过：skip=1.2.7）。
    onDismiss()
    return true
  }
}

function releaseBackKey(): void {
  const w = window as unknown as { __meowTvConsumeBack?: (() => boolean) | null }
  w.__meowTvConsumeBack = prevBackHandler
  prevBackHandler = null
}

onMounted(async () => {
  window.addEventListener('keydown', onKey)
  takeOverBackKey()
  await nextTick()
  measureNotes()
  /*
   * 初始焦点：
   *   · 日志被截断 → 先给日志区。TV 上否则要先按 ↑ 才能滚，
   *     用户只会觉得"方向键没用"（2026-11-13 实测反馈）；
   *   · 否则给主按钮：TV 上不设焦点的话第一次方向键会落到背景页面。
   */
  if (notesOverflow.value) notesEl.value?.focus()
  else primary.value?.focus()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  releaseBackKey()
  if (doneTimer !== undefined) window.clearTimeout(doneTimer)
})

/** 日志内容变化（例如清单晚到）后重量一次是否截断 */
watch(noteLines, () => void nextTick(measureNotes))

/**
 * 安装阶段结束（`phase === 'done'`）→ 自动收起弹框。
 *
 * <p>下载完成会自动调起安装器，弹框没理由继续挂着挡路。
 * 留 0.6s 让用户看一眼「安装程序已启动」，然后关。
 */
let doneTimer: number | undefined
watch(phase, (v) => {
  if (v !== 'done') return
  if (doneTimer !== undefined) window.clearTimeout(doneTimer)
  doneTimer = window.setTimeout(() => {
    doneTimer = undefined
    if (closeDialog()) emit('close')
  }, 600)
})
</script>

<template>
  <!--
    遮罩 click.self 只是"关掉看看"，**不算「稍后」**：记住版本会让这个版本
    之后再也不提示（踩过 skip=1.2.7 导致「检查更新永远已是最新」）。
    下载中 onDismiss 会自己拒绝，所以这里不用再判。
  -->
  <div
    class="fixed inset-0 z-[130] grid place-items-center bg-black/60 px-5 py-8"
    @click.self="onDismiss"
  >
    <!-- data-tv-scope：tv-nav 据此把焦点收窄到弹框内 -->
    <div
      data-tv-scope
      class="w-full max-w-[30rem] overflow-hidden rounded-xl border border-line bg-elevated shadow-2xl"
      role="dialog"
      aria-modal="true"
      aria-labelledby="upd-title"
    >
      <div class="border-b border-line px-4 py-3">
        <div id="upd-title" class="text-sm font-medium text-ink">发现新版本</div>
        <div class="mt-1 text-[12px] text-ink-3">
          {{ platformName }} · v{{ found?.version }}
          <span v-if="found?.size" class="ml-1.5">{{ fmtSize(found.size) }}</span>
          <!-- 发布日：同一版本号隔很久才看到时，用户要靠它判断"是不是旧包" -->
          <span v-if="found?.lasttime" class="ml-1.5">· {{ found.lasttime }} 发布</span>
        </div>
        <!--
          文件名单独一行：用户能一眼看出要下的是 exe 还是 apk。
          配错了清单时（例如 PC 上写着 .apk）这里会立刻露出来，
          比只看版本号更容易发现问题。
        -->
        <div v-if="fileName" class="mt-1 truncate font-mono text-[11px] text-ink-3">
          {{ fileName }}
        </div>
      </div>

      <!--
        更新日志：最多 8 行 + 可滚，避免长日志把按钮挤出屏幕。
        ★ tabindex="0" 是 TV 上"方向键能滚"的开关：tv-nav 的约定是
          「data-tv-scroll + tabindex=0 才算滚动停靠点」，缺一个就变成
          "焦点根本进不来、按上下没反应"（2026-11-13 用户实测反馈）。
      -->
      <div
        v-if="noteLines.length"
        ref="notesEl"
        tabindex="0"
        class="max-h-44 overflow-y-auto px-4 py-3 focus:outline-none"
        data-tv-scroll
      >
        <ul class="space-y-1">
          <li v-for="(line, i) in noteLines" :key="i" class="text-[12px] leading-relaxed text-ink-2">
            {{ line }}
          </li>
        </ul>
      </div>
      <div v-else class="px-4 py-3 text-[12px] text-ink-3">
        该版本没有附带更新说明。
      </div>
      <!-- 内容被截断时给个可操作提示：否则用户不知道还有 2 条 -->
      <p v-if="notesOverflow" class="px-4 pb-1 text-[11px] text-ink-3">↑ ↓ 滚动查看全部</p>

      <!-- 进度：只有下载/安装时才出现 -->
      <div v-if="busy || percent > 0" class="px-4 pb-1">
        <div class="h-1 w-full overflow-hidden rounded bg-card">
          <div class="h-full bg-accent transition-all" :style="{ width: percent + '%' }" />
        </div>
        <div class="mt-1.5 flex items-center gap-2 text-[11px] text-ink-3">
          <span class="min-w-0 flex-1 truncate">{{ stage }}</span>
          <span>{{ percent }}%</span>
        </div>
      </div>

      <p v-if="error" class="px-4 pb-1 text-[12px] text-hot">{{ error }}</p>

      <div class="flex gap-2 border-t border-line px-4 py-3">
        <button
          ref="primary"
          type="button"
          class="flex-1 rounded-lg bg-accent px-4 py-2.5 text-[13px] font-medium text-on-accent transition hover:bg-accent-dim focus:outline-none focus:ring-2 focus:ring-accent disabled:opacity-60"
          :disabled="busy"
          @click="onPrimary()"
        >
          {{ busy ? '处理中…' : '更新' }}
        </button>
        <button
          ref="secondary"
          type="button"
          class="flex-1 rounded-lg bg-card px-4 py-2.5 text-[13px] text-ink transition hover:bg-card-hover focus:outline-none focus:ring-2 focus:ring-accent disabled:opacity-60"
          :disabled="secondaryDisabled"
          @click="onSecondary()"
        >
          {{ secondaryLabel }}
        </button>
      </div>

    </div>
  </div>
</template>
