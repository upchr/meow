<script setup lang="ts">
import { ref, watch } from 'vue'
import { proxyImage } from '@/api'
import { ensureProxyTicket, refreshMediaTickets } from '@/api/mediaTicket'

/**
 * 统一的封面图。
 *
 * 源站的封面地址经常 404 / 防盗链 / 超时，直接 <img> 会在页面上留一个
 * 浏览器自带的"裂图"图标。这里统一处理：
 *   加载中 / 加载失败 → 显示占位（胶片图标），成功后才换成真图。
 *
 * 为什么不用 @error 直接 display:none：那样会留一块空白，
 * 网格里一行行空白很难看；占位至少要能看出"这里本该有图"。
 */
const props = withDefaults(
  defineProps<{
    src?: string
    alt?: string
    /** 是否走后端图片代理（源站防盗链时必须走） */
    proxied?: boolean
    /** 追加到 <img> 上的类（hover 缩放等），不能写在组件 class 上——那会落到根 div */
    imgClass?: string
  }>(),
  { src: '', alt: '', proxied: true, imgClass: '' },
)

const failed = ref(false)
const loaded = ref(false)
/** 是否已经为这张图刷过票重试；换 src 时重置 */
let retriedForTicket = false

function resolve(s: string): string {
  if (!s) return ''
  return props.proxied ? proxyImage(s) : s
}

const url = ref(resolve(props.src))

// 列表复用组件时 src 会变，状态必须跟着重置，否则新图会被上一次的 failed 挡住
watch(
  () => props.src,
  (s) => {
    failed.value = false
    loaded.value = false
    retriedForTicket = false
    url.value = resolve(s)
  },
)

/**
 * 加载失败。
 *
 * <p>★ 首次失败先怀疑**媒体票过期**（后端重启 30 分钟后旧票全失效，图片是
 * 401，而 `<img>` 拿不到状态码）：刷一次票、换 URL 重试。只有重试仍失败才落
 * 「点击重试」占位 —— 否则用户看到满屏裂图，以为网络坏了（2026-11-20 复核）。
 *
 * <p>每张图只自动重试一次：真 404 的源站不该被反复请求。
 */
async function onError(): Promise<void> {
  if (props.proxied && !retriedForTicket) {
    retriedForTicket = true
    refreshMediaTickets()
    try {
      await ensureProxyTicket()
      const next = resolve(props.src)
      if (next) {
        // 破缓存：同一 URL 的 401 可能被浏览器记住
        url.value = `${next}${next.includes('?') ? '&' : '?'}_r=${Date.now()}`
        return
      }
    } catch {
      /* 取票失败 → 走占位 */
    }
  }
  failed.value = true
  loaded.value = false
  // 清掉 src：img 元素在 v-if 收掉之前可能还会重绘一帧，
  // 留着旧地址会闪一下浏览器的裂图图标。
  url.value = ''
}

/**
 * 失败后点击重试（B2，缩范围：只处理海报，不碰播放器）。
 *
 * 带 _r 破坏缓存：源站 404 会被浏览器缓存，原样重设同一 URL
 * 会立刻再失败一次，看起来像"点了没反应"。
 */
function retry(e: Event) {
  e.stopPropagation()
  e.preventDefault()
  failed.value = false
  loaded.value = false
  // 用户主动点重试 = 再给一次"刷票重试"的机会（可能就是他刚重启完后端）
  retriedForTicket = false
  const base = resolve(props.src)
  url.value = base ? `${base}${base.includes('?') ? '&' : '?'}_r=${Date.now()}` : ''
}
</script>

<template>
  <div class="relative h-full w-full overflow-hidden bg-elevated">
    <img
      v-if="url && !failed"
      :src="url"
      :alt="alt"
      loading="lazy"
      referrerpolicy="no-referrer"
      class="h-full w-full object-cover transition-opacity duration-300"
      :class="[imgClass, loaded ? 'opacity-100' : 'opacity-0']"
      @load="loaded = true"
      @error="onError"
    />

    <!-- 占位：无图 / 加载中 / 加载失败都用它，避免浏览器裂图 -->
    <div
      v-if="!url || failed || !loaded"
      class="absolute inset-0 flex flex-col items-center justify-center"
      :class="
        failed
          ? 'pointer-events-auto cursor-pointer'
          : 'pointer-events-none'
      "
      :role="failed ? 'button' : undefined"
      :aria-label="failed ? '重新加载海报' : undefined"
      @click="failed ? retry($event) : undefined"
    >
      <div :class="url && !failed ? 'opacity-40' : 'opacity-60'">
        <svg
          class="h-8 w-8 text-ink-3/40"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.6"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <!-- 胶片图标 -->
          <rect x="2.5" y="4.5" width="19" height="15" rx="2" />
          <path d="M7 4.5v15M17 4.5v15M2.5 9.5h4.5M2.5 14.5h4.5M17 9.5h4.5M17 14.5h4.5" />
        </svg>
      </div>
      <span v-if="failed" class="mt-1 text-[length:var(--fs-tiny)] text-ink-3">点击重试</span>
    </div>
  </div>
</template>
