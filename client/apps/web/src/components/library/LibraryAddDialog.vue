<script setup lang="ts">
/**
 * 添加 / 编辑片库：片库页上的**原位弹框**，不是独立路由。
 *
 * <p><b>为什么从路由改成弹框</b>（2026-11-18 用户反馈）：
 * 跳路由会让 LibraryView 卸载重建，保存/取消返回时整页骨架闪一下，
 * 看起来像"刷新了页面"。弹框留在原页面上，焦点、滚动位置、已加载的
 * 海报全都不用重新算。
 *
 * <p><b>TV 的退出路径</b>：App.vue 的 onTvBack 会找最上层
 * `[data-tv-scope]` 派发 Escape，本组件监听 Escape 逐级退：
 * 目录浏览器 → 表单（新增时）→ 关闭。编辑模式没有类型选择这层，
 * 返回直接关弹框。
 *
 * <p>添加与编辑共用这一份表单（`edit-id` 有值即编辑）：字段、校验、
 * 测试连接完全相同，拆成两个组件只会让「改了一处忘了另一处」。
 * 编辑时跳过类型选择、直接进表单，密码框留空表示不修改。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { backendIsLocal, isDesktop } from '@/api'
import { fetchLibrary, fetchLibraryRoots, listLibraryRoot, saveLibrary, testLibrary, type LibraryInfo, type LibraryRoot } from '@/api/library'

const props = withDefaults(defineProps<{ editId?: string }>(), { editId: '' })
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'saved'): void
}>()

/** 存盘类型。本地目录与 Alist 本轮未实现，标为不可选而不是假装能用。 */
const TYPES = [
  { id: 'webdav', label: 'Webdav', hint: '群晖/威联通/Alist 自带的 WebDAV 接口' },
  { id: 'smb', label: 'SMB', hint: 'Windows 共享 / NAS 的 SMB 共享（smb://主机/共享名）' },
  { id: 'local', label: '本地目录', hint: '后端所在机器上的绝对路径' },
  {
    id: 'alist',
    label: 'Alist网页',
    disabled: true,
    // Alist 自带 WebDAV 接口，用 Webdav 类型填 http://主机:5244/dav 现在就能用。
    // 单独做一个"走 Alist JSON 接口"的类型没有额外收益，所以这里不实现，
    // 但提示要指对路——写"排到下版"会让人一直等一个不会来的功能。
    hint: '不需要单独的类型：Alist 自带 WEBDAV，请选「Webdav」填 http://主机:5244/dav',
  },
]

const stage = ref<'pick' | 'form'>(props.editId ? 'form' : 'pick')
const type = ref('webdav')
const editId = computed(() => props.editId || '')
const form = ref<{
  name: string
  base: string
  root: string
  user: string
  pass: string
  domain: string
}>({
  name: '',
  base: '',
  root: '',
  user: '',
  pass: '',
  domain: '',
})
const busy = ref(false)
const msg = ref('')
const msgOk = ref(false)

const isEdit = computed(() => !!editId.value)

/** 弹框里首个落点：类型选择时是 Webdav，表单时是名称输入框 */
const firstEl = ref<HTMLElement | null>(null)

/**
 * 把初始焦点交给 tv-nav（TV 壳）或原生 focus（浏览器）。
 *
 * ★ TV 上不能用原生 focus()：tv-nav 的焦点框是加 `.meow-tv-focus` class
 *   画出来的，原生 `:focus` 另有一套 `focus:ring-2`。两边同时生效时，
 *   方向键移动的是 tv-nav 的框，原生那圈留在原处 —— 用户看到的是
 *   "第一个选项闪一下、框已经到第二个了"（2026-11-18 用户报）。
 */
function tvNav(): ((t: Element, pin?: boolean) => boolean) | null {
  const w = window as unknown as { __meowTvNav?: unknown; __meowTvFocus?: (t: Element, pin?: boolean) => boolean }
  return typeof w.__meowTvNav === 'function' && typeof w.__meowTvFocus === 'function'
    ? w.__meowTvFocus
    : null
}

async function focusFirst(pin = true) {
  await nextTick()
  const el = firstEl.value
  if (!el) return
  const focus = tvNav()
  if (focus) {
    focus(el, pin)
    return
  }
  el.focus()
}

/**
 * 地址框的占位示例：三种类型写法完全不同，给错了用户会照着填错。
 *
 * <p>本地目录还要再分一层：后端在**这台机器**（PC 插件 / TV 本机网关）时可以填
 * 本机路径，后端在 NAS / 容器里时只能填**它那台机器**上的路径 —— 否则用户会
 * 照示例填 `D:/影视`，然后永远"目录不存在"。
 */
const localBackend = backendIsLocal()
const basePlaceholder = computed(() => {
  if (type.value === 'smb') return 'smb://192.168.1.19/视频'
  if (type.value === 'local') {
    return localBackend ? 'D:/影视  或  /mnt/media' : '服务器上的路径，例如 /vol02/1000-0-xxx/电影'
  }
  return 'http://192.168.1.19:5005/'
})

/** 只有 SMB/WebDAV 需要账号；本地目录留空即可 */
const needsAuth = computed(() => type.value !== 'local')

// ------------------------------------------------- 「本地目录」的选目录
//
// 三种形态的"本地"含义不同，所以入口必须分开：
//   - PC：后端是应用内置 JVM，和用户同一台机器 → 系统文件夹选择器（路径可直接用）
//   - TV：后端是机内网关，同一台机器 → 内置目录浏览器（要真实路径，见下）
//   - Web/Docker：后端在容器里，本地路径是**容器内路径** → 只能手填

const onDesktop = isDesktop()

interface DirBridge {
  hasAllFiles?: () => boolean
  requestAllFiles?: () => boolean
  listDirs?: (path: string) => string
}

function tvDirBridge(): DirBridge | null {
  try {
    const b = (window as unknown as { meowTv?: DirBridge }).meowTv
    return b && typeof b.listDirs === 'function' ? b : null
  } catch {
    return null
  }
}

/**
 * TV 上是否已授予「所有文件访问权限」。
 *
 * 片库路径要交给后端（本机网关）用 java.io.File 读；SAF 给的是 content://
 * 树 URI，后端读不了，所以这里必须走"所有文件访问权限"拿真实路径。
 */
const tvAllFiles = ref(true)

function refreshAllFiles() {
  const b = tvDirBridge()
  tvAllFiles.value = b && typeof b.hasAllFiles === 'function' ? b.hasAllFiles() : true
}

function askAllFiles() {
  const b = tvDirBridge()
  if (!b || typeof b.requestAllFiles !== 'function') return
  b.requestAllFiles()
  // 权限页是异步返回的：回来后再查两次（用户可能在里面停留几秒）
  window.setTimeout(refreshAllFiles, 1200)
  window.setTimeout(refreshAllFiles, 4000)
}

/** PC：系统文件夹选择器（复用 Tauri dialog 插件，和「导入插件包」同一个入口） */
async function pickNativeDir() {
  const w = window as unknown as {
    __TAURI_INTERNALS__?: { invoke?: (c: string, a?: unknown) => Promise<unknown> }
    __TAURI__?: { core?: { invoke?: (c: string, a?: unknown) => Promise<unknown> } }
  }
  const invoke = w.__TAURI_INTERNALS__?.invoke || w.__TAURI__?.core?.invoke
  if (!invoke) {
    msgOk.value = false
    msg.value = '这个环境没有系统文件选择器，请手动填路径'
    return
  }
  try {
    const r = (await invoke('plugin:dialog|open', {
      options: { directory: true, multiple: false, title: '选择片库目录' },
    })) as string | string[] | null
    const p = Array.isArray(r) ? r[0] : r
    if (p) {
      form.value.base = p
      msg.value = ''
    }
  } catch (e) {
    msgOk.value = false
    msg.value = e instanceof Error ? e.message : String(e)
  }
}

/** 目录浏览器状态 */
const pickerOpen = ref(false)
const pickerPath = ref('')
const pickerDirs = ref<Array<{ name: string; path: string }>>([])
/** 进入过的路径栈，用于「上一级」（比字符串截断可靠） */
const pickerStack = ref<string[]>([])
/** 打开浏览器前的聚焦元素：关掉后还回去，别让焦点掉到弹框顶部 */
let pickerOpener: HTMLElement | null = null

/**
 * 弹窗的数据源，两端能力不同：
 *   - http：后端 `/api/library?action=roots` —— 列出"本端真有权限的根"
 *           （fpk 的授权目录/共享目录、Docker 的挂载点、PC 的盘符）
 *   - tv  ：端内 `meowTv.listDirs`。SAF 的权限模型只有端内能处理
 *
 * <p>为什么 http 优先：端内桥给的是 Android 视角的路径，而远程后端（NAS、
 * Docker）根本不在这台机器上，桥选出来的路径后端读不了。
 */
const pickerMode = ref<'tv' | 'http'>('tv')
const pickerRoots = ref<LibraryRoot[]>([])
const pickerRootId = ref('')
/** 相对当前根的路径（http 模式） */
const pickerRel = ref('')
const pickerLoading = ref(false)
const pickerHint = ref('')

const pickerRoot = computed(
  () => pickerRoots.value.find((r) => r.id === pickerRootId.value) || null,
)

/** 右栏容器（TV 焦点要落到第一个目录项，而不是弹窗头部的「上一级」） */
const pickerListEl = ref<HTMLElement | null>(null)
/** 左栏容器（右栏是空目录时的焦点兜底） */
const pickerRootsEl = ref<HTMLElement | null>(null)

/**
 * 打开弹窗 / 进目录 / 换根之后，把 TV 焦点补到**第一个目录**。
 *
 * <p>tv-nav 只在弹层打开那一刻找一次 `data-tv-first`，而这里的列表是异步
 * 加载的；不补这一下，焦点会停在「上一级」——在根目录时按它没有任何反应，
 * 用户会以为遥控器失灵（同 AccountDialog 的两拍做法）。
 *
 * <p>右栏是空目录时退到左栏第一个根：否则焦点无处可去，用户连换根都做不到。
 */
function focusFirstDir() {
  void nextTick(() => {
    const el = (pickerListEl.value?.querySelector('button') ||
      pickerRootsEl.value?.querySelector('button') ||
      null) as HTMLElement | null
    if (!el) return
    const f = tvNav()
    if (f) f(el, true)
    else el.focus?.()
  })
}

/** 当前所在目录的绝对路径（回填 form.base 用） */
const pickerAbs = computed(() => {
  if (pickerMode.value === 'tv') return pickerPath.value
  const r = pickerRoot.value
  if (!r) return ''
  const base = r.path.replace(/[\\/]+$/, '')
  return pickerRel.value ? `${base}/${pickerRel.value}` : base
})

async function loadHttpRoots(): Promise<LibraryRoot[]> {
  try {
    const r = await fetchLibraryRoots()
    return r.roots || []
  } catch {
    return []
  }
}

async function lsHttp(rootId: string, rel: string) {
  pickerLoading.value = true
  pickerHint.value = ''
  try {
    const r = await listLibraryRoot(rootId, rel)
    pickerDirs.value = (r.dirs || []).map((d) => ({ name: d.name, path: d.name }))
    pickerRel.value = rel
    if (r.truncated) pickerHint.value = '目录太大，只显示前 2000 项'
  } catch (e) {
    pickerDirs.value = []
    pickerRel.value = rel
    pickerHint.value = e instanceof Error ? e.message : String(e)
  } finally {
    pickerLoading.value = false
    focusFirstDir()
  }
}

function loadDirs(path: string) {
  const b = tvDirBridge()
  if (!b || typeof b.listDirs !== 'function') return
  try {
    pickerDirs.value = JSON.parse(b.listDirs(path) || '[]') as Array<{ name: string; path: string }>
  } catch {
    pickerDirs.value = []
  }
  pickerPath.value = path
  focusFirstDir()
}

async function openPicker(ev?: Event) {
  pickerOpener = ev && ev.currentTarget instanceof HTMLElement ? ev.currentTarget : null
  pickerStack.value = []
  pickerRoots.value = []
  pickerRootId.value = ''
  pickerRel.value = ''
  pickerDirs.value = []
  pickerHint.value = ''
  pickerOpen.value = true

  pickerLoading.value = true
  const roots = await loadHttpRoots()
  pickerLoading.value = false

  if (roots.length) {
    pickerMode.value = 'http'
    pickerRoots.value = roots
    pickerRootId.value = roots[0].id
    await lsHttp(roots[0].id, '')
    /*
     * 第一个根常常是「应用共享目录」（fnOS 的 @appshare/meow/library，装完就是空的），
     * 用户真正想找的影视目录排在后面。默认停在空目录 = 一打开就是"没有子目录"，
     * 用户会以为没识别到 —— 所以自动跳到第一个**有内容**的根。
     */
    if (!pickerDirs.value.length && roots.length > 1) {
      for (const r of roots.slice(1)) {
        await lsHttp(r.id, '')
        if (pickerDirs.value.length) {
          pickerRootId.value = r.id
          break
        }
      }
    }
    return
  }

  /*
   * 端内桥只在「后端就在这台机器」时才有意义：它返回的是 Android 视角的路径，
   * 远程后端（NAS / Docker / 另一台 PC）读不到。TV 连远程后端时跳过它，
   * 让下面的空态文案告诉用户该去哪授权。
   */
  if (localBackend && tvDirBridge()) {
    refreshAllFiles()
    if (!tvAllFiles.value) {
      askAllFiles()
      pickerOpen.value = false
      return
    }
    pickerMode.value = 'tv'
    loadDirs('')
    return
  }

  // 什么都没有：给引导文案，不做空白弹窗（用户会以为坏了）
  pickerMode.value = 'http'
  pickerHint.value = localBackend
    ? '本机后端没有可浏览的目录。可以手填绝对路径，或用「系统文件夹选择器」。'
    : '后端（NAS / Docker）没有可浏览的目录。\nfnOS：到「应用设置 → 访问权限」添加文件夹；\nDocker：在 compose 里挂载目录并设 MEOW_LIBRARY_ROOTS。'
}

function closeTvPicker() {
  pickerOpen.value = false
  const el = pickerOpener
  pickerOpener = null
  if (!el) return
  void nextTick(() => {
    const focus = tvNav()
    if (focus) focus(el, true)
    else el.focus()
  })
}

function enterDir(p: string) {
  pickerStack.value = [
    ...pickerStack.value,
    pickerMode.value === 'tv' ? pickerPath.value : pickerRel.value,
  ]
  if (pickerMode.value === 'tv') {
    loadDirs(p)
    return
  }
  const next = pickerRel.value ? `${pickerRel.value}/${p}` : p
  void lsHttp(pickerRootId.value, next)
}

function upDir() {
  const st = [...pickerStack.value]
  const prev = st.pop() ?? ''
  pickerStack.value = st
  if (pickerMode.value === 'tv') loadDirs(prev)
  else void lsHttp(pickerRootId.value, prev)
}

/** 切换根：路径栈要清空（不同根之间没有"上一级"关系） */
function switchRoot(id: string) {
  pickerRootId.value = id
  pickerStack.value = []
  pickerDirs.value = []
  if (pickerMode.value === 'http') void lsHttp(id, '')
}

function chooseDir() {
  const abs = pickerAbs.value
  if (!abs) {
    msgOk.value = false
    msg.value = '先进入要作为片库的那个目录，再点「选中此目录」'
    return
  }
  form.value.base = abs
  closeTvPicker()
  msg.value = ''
}

const canSave = computed(() => form.value.base.trim().length > 0)

function pick(id: string) {
  type.value = id
  stage.value = 'form'
  void focusFirst()
}

async function test() {
  busy.value = true
  msg.value = ''
  try {
    const r = await testLibrary({
      id: 'probe',
      name: form.value.name,
      type: type.value,
      base: form.value.base,
      user: form.value.user,
      root: form.value.root,
      domain: form.value.domain,
      ...(form.value.pass ? { pass: form.value.pass } : {}),
    })
    msgOk.value = !!r.ok
    msg.value = r.ok ? `连接成功（根目录 ${r.entries ?? 0} 项）` : r.msg || '连接失败'
  } catch (e) {
    msgOk.value = false
    msg.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function save() {
  if (!canSave.value) return
  busy.value = true
  msg.value = ''
  try {
    const cur = await fetchLibrary()
    const all = cur.libs || []
    // 编辑时保留原 id：id 变了后端会当成新增，旧条目还留在配置里
    const id = editId.value || 'lib' + Date.now().toString(36)
    const item: Partial<LibraryInfo> & { pass?: string } = {
      id,
      name: form.value.name || form.value.base,
      type: type.value,
      base: form.value.base,
      user: form.value.user,
      root: form.value.root,
      ...(form.value.domain ? { domain: form.value.domain } : {}),
    }
    // 密码留空 = 不改。后端只在本字段出现时覆盖，所以空串绝不能发出去。
    if (form.value.pass) item.pass = form.value.pass
    // 整表提交：编辑要把原条目替换掉，而不是再追加一条同 id 的
    const others = all.filter((x) => x.id !== id)
    await saveLibrary([...others, item])
    emit('saved')
  } catch (e) {
    msgOk.value = false
    msg.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

function close() {
  emit('close')
}

/** 返回上一级：表单（新增）→ 类型选择；其余直接关弹框 */
function back() {
  if (stage.value === 'form' && !isEdit.value) {
    stage.value = 'pick'
    void focusFirst()
    return
  }
  close()
}

function onKey(e: KeyboardEvent) {
  if (e.key !== 'Escape' && e.key !== 'Backspace') return
  // Backspace 在输入框里是删字，不能当返回（Escape 不受此限：
  // TV 上 BACK 会被 App 翻译成 Escape，必须永远有出口）
  if (e.key === 'Backspace') {
    const ae = document.activeElement
    const tag = ae && ae.tagName ? ae.tagName.toLowerCase() : ''
    if (tag === 'input' || tag === 'textarea') return
  }
  e.preventDefault()
  if (pickerOpen.value) {
    // 返回键逐级退：在子目录里先回上一级，到根了才关弹窗（TV 遥控器的预期）
    if (pickerStack.value.length > 0) {
      upDir()
      return
    }
    closeTvPicker()
    return
  }
  back()
}

onMounted(async () => {
  window.addEventListener('keydown', onKey)
  // "所有文件访问权限"可能是在设置页里刚给的，打开时先探一次
  refreshAllFiles()

  if (!editId.value) {
    /*
     * 新增：**等这一轮渲染落地后再落焦**，不能只靠 tv-nav 的 activeScope()。
     *
     * 踩过（2026-11-18 投影仪实测）：onMounted 里的 refreshAllFiles() 会改
     * `tvAllFiles`，触发一次 Vue 重渲染；Vue patch `:class` 时重写整个
     * className，把 tv-nav 刚加到 Webdav 上的 `.meow-tv-focus` 抹掉。
     * 表现：打开弹框**没有任何焦点框**；按一下 ↓，tv-nav 先用 lastFocused
     * 把框补回 Webdav、紧接着再移到 SMB —— 用户看到"第一项闪一下就跳到第二项"。
     *
     * focusFirst() 里先 await nextTick()（等重渲染完成），再显式
     * `__meowTvFocus(el, true)`：框在 patch 之后写入，才留得住。
     */
    await focusFirst()
    return
  }

  // 编辑：从后端取回该条（密码后端只回 hasPass，所以密码框必然为空）
  try {
    const cur = await fetchLibrary()
    const l = (cur.libs || []).find((x) => x.id === editId.value)
    if (!l) {
      msgOk.value = false
      msg.value = '这个片库已经不在了，可能刚被删掉。'
      return
    }
    type.value = l.type || 'webdav'
    form.value = {
      name: l.name || '',
      base: l.base || '',
      root: l.root || '',
      user: l.user || '',
      pass: '',
      domain: l.domain || '',
    }
    stage.value = 'form'
    // 数据回来晚于 tv-nav 第一次落焦：再钉一次到名称输入框
    void focusFirst()
  } catch (e) {
    msgOk.value = false
    msg.value = e instanceof Error ? e.message : String(e)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
})
</script>

<template>
  <!--
    ★ 必须 Teleport 到 body：App.vue 的 <main class="relative z-10"> 建了层叠上下文，
      留在这里的 fixed 遮罩会被 sticky z-30 的顶栏整块压住（顶栏不暗、还能点）——
      和设置页「站点明细」弹框踩的是同一个坑（见 tv-nav-lateral-and-scope.test.ts）。
      tv-nav 的作用域按 `[data-tv-scope]` 属性找元素，不依赖 DOM 父级，所以
      Teleport 后方向键依旧只在弹框内走。
  -->
  <Teleport to="body">
    <div
      class="fixed inset-0 z-[120] grid place-items-center bg-black/55 px-4 py-6"
      @click.self="close"
    >
      <div
        data-tv-scope
        role="dialog"
        aria-modal="true"
        class="max-h-dialog-lg glass-panel flex w-full max-w-[34rem] flex-col overflow-hidden rounded-panel"
      >
      <div class="flex shrink-0 items-center gap-2 border-b border-line px-4 py-3">
        <h2 class="text-sm font-medium text-ink">{{ isEdit ? '编辑片库' : '添加片库' }}</h2>
        <span class="flex-1" />
        <button
          type="button"
          class="rounded-md bg-card px-3 py-1.5 text-xs text-ink-2 transition hover:bg-card-hover hover:text-ink focus:ring-2 focus:ring-accent"
          @click="close"
        >
          关闭
        </button>
      </div>

      <!-- 第一步：选类型 -->
      <div v-if="stage === 'pick'" class="min-h-0 flex-1 overflow-y-auto p-4">
        <p class="mb-3 text-center text-sm text-ink">请选择存盘类型</p>
        <div class="flex flex-col gap-2">
          <button
            v-for="(o, i) in TYPES"
            :key="o.id"
            :ref="(el) => { if (i === 0 && !o.disabled) firstEl = el as HTMLElement | null }"
            :data-tv-first="i === 0 && !o.disabled ? '' : null"
            type="button"
            class="w-full rounded-lg px-4 py-3 text-sm transition focus:outline-none"
            :class="
              o.disabled
                ? 'cursor-not-allowed bg-card text-ink-3 opacity-60'
                : 'bg-card text-ink hover:bg-card-hover focus:ring-2 focus:ring-accent'
            "
            :disabled="o.disabled"
            :title="o.hint"
            @click="!o.disabled && pick(o.id)"
          >
            <!-- 后端不在本机时叫「服务器目录」：base 是后端那台机器上的路径 -->
            {{ o.id === 'local' && !localBackend ? '服务器目录' : o.label }}
            <span v-if="o.disabled" class="ml-1.5 text-[11px] opacity-80">（下版）</span>
          </button>
        </div>
        <button
          type="button"
          class="mt-3 w-full rounded-lg px-4 py-2 text-xs text-ink-3 transition hover:bg-card focus:ring-2 focus:ring-accent"
          @click="close"
        >
          取消
        </button>
      </div>

      <!-- 第二步：表单 -->
      <div v-else class="min-h-0 flex-1 space-y-3 overflow-y-auto p-4">
        <div class="text-xs text-ink-3">
          类型：<span class="text-ink-2">{{ type }}</span>
          <span v-if="isEdit" class="ml-2 text-ink-3">（编辑时不能改类型，换了类型请新建一个）</span>
        </div>

        <label class="block">
          <span class="text-xs text-ink-3">名称</span>
          <input
            ref="firstEl"
            v-model="form.name"
            data-tv-first
            placeholder="NAS 影视"
            class="mt-1 w-full rounded-md border border-line bg-field px-3 py-2 text-sm outline-none focus:border-accent/60"
          />
        </label>

        <label class="block">
          <span class="text-xs text-ink-3">地址</span>
          <input
            v-model="form.base"
            :placeholder="basePlaceholder"
            class="mt-1 w-full rounded-md border border-line bg-field px-3 py-2 font-mono text-sm outline-none focus:border-accent/60"
          />
        </label>

        <!-- 本地目录：PC 用系统文件夹选择器、TV 用内置目录浏览器 —— 这两端后端与
             用户同机，选出来的真实路径后端能直接读。Web/Docker 的后端在容器里，
             本地路径是**容器内路径**，只能手填，所以不给按钮。 -->
        <div v-if="type === 'local'" class="rounded-lg border border-line bg-field/40 px-3 py-2">
          <div class="flex flex-wrap items-center gap-2">
            <button
              type="button"
              class="rounded-md bg-card px-3 py-1.5 text-sm text-ink-2 transition hover:bg-card-hover hover:text-ink"
              @click="openPicker"
            >
              浏览目录…
            </button>
            <!-- 系统选择器只在「后端就在这台机器」时出现：远程后端读不到本机路径 -->
            <button
              v-if="onDesktop && localBackend"
              type="button"
              class="rounded-md bg-card px-3 py-1.5 text-sm text-ink-2 transition hover:bg-card-hover hover:text-ink"
              @click="pickNativeDir"
            >
              系统文件夹选择器…
            </button>
            <span class="text-[length:var(--fs-mini)] text-ink-3">
              {{
                localBackend
                  ? '选完自动填上面的地址；也可以手动填/粘贴路径'
                  : '列的是服务器上的目录；手填时路径也必须存在于服务器'
              }}
            </span>
          </div>
        </div>

        <label class="block">
          <span class="text-xs text-ink-3">根路径</span>
          <input
            v-model="form.root"
            placeholder="/视频（留空 = 地址根）"
            class="mt-1 w-full rounded-md border border-line bg-field px-3 py-2 font-mono text-sm outline-none focus:border-accent/60"
          />
        </label>

        <div v-if="needsAuth" class="grid grid-cols-2 gap-3">
          <label class="block">
            <span class="text-xs text-ink-3">用户名</span>
            <input
              v-model="form.user"
              class="mt-1 w-full rounded-md border border-line bg-field px-3 py-2 text-sm outline-none focus:border-accent/60"
            />
          </label>
          <label class="block">
            <span class="text-xs text-ink-3">密码</span>
            <input
              v-model="form.pass"
              type="password"
              :placeholder="isEdit ? '留空 = 不修改' : ''"
              class="mt-1 w-full rounded-md border border-line bg-field px-3 py-2 text-sm outline-none focus:border-accent/60"
            />
          </label>
        </div>

        <!-- 域只有 SMB 用得上：域账号填这里的 DOMAIN，用户名只写账号名 -->
        <label v-if="type === 'smb'" class="block">
          <span class="text-xs text-ink-3">域（可留空）</span>
          <input
            v-model="form.domain"
            placeholder="WORKGROUP"
            class="mt-1 w-full rounded-md border border-line bg-field px-3 py-2 text-sm outline-none focus:border-accent/60"
          />
        </label>

        <p v-if="msg" class="text-xs" :class="msgOk ? 'text-ok' : 'text-hot'">{{ msg }}</p>
        <p class="text-[11px] leading-relaxed text-ink-3">
          密码存在后端数据目录，不会显示回页面，也不会进 git。
        </p>

        <div class="flex items-center gap-2 pt-1">
          <button
            type="button"
            class="rounded-md bg-card px-4 py-2 text-sm transition hover:bg-card-hover disabled:opacity-50"
            :disabled="busy"
            @click="test"
          >
            测试连接
          </button>
          <button
            type="button"
            class="rounded-md bg-accent px-4 py-2 text-sm font-medium text-on-accent disabled:opacity-50"
            :disabled="busy || !canSave"
            @click="save"
          >
            保存
          </button>
          <span class="flex-1" />
          <button
            type="button"
            class="rounded-md bg-card px-4 py-2 text-sm text-ink-2 transition hover:bg-card-hover hover:text-ink"
            @click="back"
          >
            返回
          </button>
        </div>
      </div>
    </div>

    <!-- TV 内置目录浏览器：遥控器可达（按钮 + 「上一级」），不做成右侧抽屉。
         ★ 自带 data-tv-scope：tv-nav 取"最后一个"作用域，它开着时方向键只在
           目录里走，不会跑到背后的表单。
         ★ 底色用 bg-elevated（实色）：bg-card 在浅色主题只有 5.5% 不透明度，
           浮层底下的表单会透上来，看起来像"面板塌了一半"。 -->
    <div
      v-if="pickerOpen"
      data-tv-scope
      class="fixed inset-0 z-[130] grid place-items-center bg-black/70 p-4 sm:p-6"
      @click.self="closeTvPicker"
    >
      <div
        class="flex w-full max-w-[42rem] flex-col overflow-hidden rounded-xl border border-line bg-elevated shadow-2xl"
        style="max-height: 76vh"
      >
        <div class="flex shrink-0 items-center gap-2 border-b border-line px-3 py-2">
          <button
            data-tv-first
            type="button"
            class="rounded-md bg-field px-2.5 py-1 text-sm text-ink-2 transition hover:bg-card-hover hover:text-ink"
            @click="upDir"
          >
            上一级
          </button>
          <div
            data-picker-path
            class="min-w-0 flex-1 truncate font-mono text-[length:var(--fs-mini)] text-ink-3"
          >
            {{ pickerAbs || (pickerMode === 'tv' ? '存储卷' : '选择目录') }}
          </div>
          <button
            type="button"
            class="rounded-md bg-accent px-3 py-1 text-sm font-medium text-on-accent"
            @click="chooseDir"
          >
            选中此目录
          </button>
        </div>
        <div class="flex min-h-0 flex-1">
          <!-- 左栏：本端真实可用的根（fnOS 授权目录 / 共享目录 / 挂载点 / 盘符）。
               只有一个根时不必占地方，但**不能隐藏**——TV 上就是靠它换存储卷。 -->
          <div
            v-if="pickerRoots.length > 1"
            ref="pickerRootsEl"
            class="w-28 shrink-0 overflow-y-auto border-r border-line p-1.5"
          >
            <button
              v-for="r in pickerRoots"
              :key="r.id"
              type="button"
              class="block w-full truncate rounded-md px-2 py-1.5 text-left text-[length:var(--fs-mini)] transition"
              :class="
                r.id === pickerRootId
                  ? 'bg-accent/20 text-accent'
                  : 'text-ink-3 hover:bg-card-hover hover:text-ink'
              "
              :title="r.path"
              @click="switchRoot(r.id)"
            >
              {{ r.label }}
            </button>
          </div>
          <div ref="pickerListEl" class="min-h-0 flex-1 overflow-y-auto p-2">
            <button
              v-for="d in pickerDirs"
              :key="d.path"
              type="button"
              class="block min-h-9 w-full truncate rounded-md px-3 py-2 text-left text-sm text-ink-2 transition hover:bg-card-hover hover:text-ink"
              @click="enterDir(d.path)"
            >
              📁 {{ d.name }}
            </button>
            <p v-if="pickerLoading" class="px-3 py-6 text-center text-xs text-ink-3">读取中…</p>
            <p
              v-else-if="!pickerDirs.length"
              class="whitespace-pre-line px-3 py-6 text-center text-xs leading-relaxed text-ink-3"
            >
              {{ pickerHint || '这个目录下没有子目录' }}
            </p>
            <p
              v-else-if="pickerHint"
              class="px-3 pb-2 text-center text-[length:var(--fs-mini)] text-hot"
            >
              {{ pickerHint }}
            </p>
          </div>
        </div>
        <div class="flex shrink-0 items-center justify-between border-t border-line px-3 py-2">
          <span class="truncate text-[length:var(--fs-mini)] text-ink-3">
            {{ pickerRoot ? pickerRoot.label + ' · ' : '' }}共 {{ pickerDirs.length }} 项 · 进入目标目录后点右上「选中此目录」
          </span>
          <button
            type="button"
            class="ml-2 shrink-0 rounded-md bg-field px-3 py-1 text-sm"
            @click="closeTvPicker"
          >
            取消
          </button>
        </div>
      </div>
    </div>
    </div>
  </Teleport>
</template>
