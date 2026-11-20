<script setup lang="ts">
/**
 * 本地片库浏览页。
 *
 * 与在线源分开一个路由，而不是塞进「多一个源」：
 * 首页只渲染 `store.current` 一个源（`HomeView.vue:25`），做成源等于把片库
 * 藏进顶栏下拉；而仓库已有「直播与点播并列」的先例（`AppHeader.vue`）。
 *
 * 三级结构（目录 → 影片 → 播放）全部在本页内完成，**布局对齐在线源**：
 *   - 根/目录层：首页那种海报卡片墙（复用 PosterCard 的视觉规格）
 *   - 影片层：详情页那种「海报 + 信息 + 线路/选集」布局
 * 之所以要对齐，是因为用户在同一批页面间来回切，两套视觉语言会让"这片子
 * 到底有没有"变成一个需要重新辨认的问题。
 *
 * 缓存与片库用页签分开（原先是上下堆叠，缓存列表把片库挤到屏幕外）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiBase, backendIsLocal } from '@/api'
import CachePanel from '@/components/library/CachePanel.vue'
import LibraryAddDialog from '@/components/library/LibraryAddDialog.vue'
import PosterImage from '@/components/common/PosterImage.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import MeTabs from '@/components/common/MeTabs.vue'
import { colsStyle, useCardCols } from '@/composables/useCardCols'
import {
  cachePoster,
  clearMetaMirror,
  hasMetaMirror,
  loadMetaMirror,
  posterMirrorBase,
  saveMetaMirror,
} from '@/api/nativeBridge'
import {
  coverTitleFor,
  dirKindOf,
  fetchLibraryDir,
  fetchLibrary,
  fmtSize,
  groupByTitle,
  libraryStream,
  parseFolderName,
  posterUrl,
  saveLibrary,
  scrapeGroups,
  scrapeOne,
  scrapeTitleOf,
  seasonTitle,
  unbindScrape,
  waitLibraryTranscode,
  type LibraryEntry,
  type LibraryOverview,
  type TitleGroup,
} from '@/api/library'
import { ensureLibraryTicket } from '@/api/mediaTicket'
import { authRequired } from '@/stores/auth'
import { useReturnRestore } from '@/composables/useReturnRestore'
import { confirmDialog } from '@/composables/useConfirm'

// 返回上一页时恢复滚动位置与焦点（TV 上从详情返回不该跳回顶部）
useReturnRestore('library')

const route = useRoute()
const router = useRouter()

/** 页签：片库 / 缓存。缓存原先是本页下方的一块，把片库挤到屏幕外 */
type Tab = 'lib' | 'cache'
/**
 * 片库页签的文案。
 *
 * <p>片库的 base 是**后端那台机器**上的路径；TV/PC 连远程后端时，"本地片库"
 * 会让用户以为是自己手里这台设备的目录（实测就是这么误会的）。后端不在
 * 本机就叫「服务器片库」，语义与"这条路径在后端上"一致。
 */
const libTabLabel = backendIsLocal() ? '本地片库' : '服务器片库'
const tab = ref<Tab>('lib')

const loading = ref(true)
const overview = ref<LibraryOverview | null>(null)
/** 当前路径栈：每级是 {lib, path, name}，用于面包屑与返回 */
const trail = ref<Array<{ lib: string; path: string; name: string }>>([])
const dirs = ref<LibraryEntry[]>([])
const groups = ref<TitleGroup[]>([])
const error = ref('')
/**
 * 海报表：key → 已经过 `posterUrl()` 的代理地址。
 *
 * ★ **key 必须带库 id**（`<lib>|片名|年份` / `dir:<lib>|相对路径`）：
 *   路径是相对库根的，两个库都有 `电影/教父` 时，不带库就会互相覆盖，
 *   表现为"另一个库的片子挂了这个库的海报"。
 */
const posters = ref<Record<string, string>>({})
/** 这次目录的刮削是否已经跑过（避免每次进出都重打外网） */
const scrapedFor = ref('')
/** 已经探过封面的目录位置（目录封面要逐个子目录列一次，不能重复打） */
const dirCovered = ref<string[]>([])
/** 队列里/正在跑的刮削位置（同一位置不重复入队） */
const scrapeQueued = new Set<string>()
/** 有刮削在排队或执行（模板上的"正在补海报…"） */
const scraping = ref(false)
/**
 * 豆瓣限流中。
 *
 * 必须让用户看见：否则"没海报"会被理解成"我的片库文件命名有问题"，
 * 而实际是外网在限流、过几分钟自己就好了。实测连着打 10 个片名两轮，
 * 第一轮 5/10、第二轮 0/10。
 */
const scrapeLimit = ref(false)
/** 正在转码的路径 → 提示文案 */
const transcoding = ref<Record<string, string>>({})
/** 当前展开的影片（影片层视图）；null = 停在目录层 */
const activeGroup = ref<TitleGroup | null>(null)
/**
 * 这个详情是**自动**进去的（目录本身就是一部剧），不是用户点卡片进来的。
 *
 * 只影响返回键：自动进入时返回该退到上一层目录，而不是退到那张
 * 「只有一张卡」的墙上（那正是要去掉的多余一重）。
 */
const autoEntered = ref(false)

const currentLib = computed(() => trail.value[trail.value.length - 1]?.lib || '')
const currentPath = computed(() => trail.value[trail.value.length - 1]?.path || '')
const currentName = computed(() => trail.value[trail.value.length - 1]?.name || '')

// 下载走同步 a.click()（await 会丢用户手势、被浏览器拦掉），
// 所以票必须提前取好：进库/换库时就预取一次（auth v2，未开鉴权时静默失败）。
watch(
  currentLib,
  (lib) => {
    if (lib && authRequired.value) void ensureLibraryTicket(lib)
  },
  { immediate: true },
)

/** 与首页同一套列数规则，卡片宽度才不会在两个页面间跳变 */
const { cols } = useCardCols({ maxWidth: 1400 })

async function loadRoot() {
  loading.value = true
  error.value = ''
  try {
    overview.value = await fetchLibrary()
    if (!overview.value.libs.length) {
      error.value = '还没有配置片库。点上面的「添加片库」填一个 WebDAV 地址。'
      return
    }
    // 根层封面：逐个库探一层（取每个分类目录里第一个视频的片名去刮）。
    // **不能拿分类名本身去刮**——见 doScrapeDirs 的说明。
    for (const t of overview.value.tops || []) {
      void doScrapeDirs(t.id, `root|${t.id}`, t.dirs || [])
    }
  } catch (e) {
    // 后端不可用时，局域网里的 NAS 其实还在——只是没有进程去做 PROPFIND。
    // 把这条说清楚，否则用户会以为"片库坏了"（断网调试时最容易误判）。
    const m = e instanceof Error ? e.message : String(e)
    const offline = /Failed to fetch|NetworkError|HTTP 5|HTTP 0/.test(m)
    error.value = offline
      ? '连不上后端，列不了目录。后端进程没起来时，NAS 本身并未掉线——启动后端（或检查设置里的后端地址）后点「刷新」。'
      : m
    // TV 壳：目录列不出来时，把上次存的海报副本接上（至少不是一片灰）
    if (offline) restoreMirror(Object.keys(posters.value))
  } finally {
    loading.value = false
  }
}

/** 进入某目录（lib + 相对路径）。 */
async function open(lib: string, path: string, name: string) {
  loading.value = true
  error.value = ''
  activeGroup.value = null
  try {
    const r = await fetchLibraryDir(lib, path)
    if (!r.ok) {
      error.value = r.msg || '列目录失败'
      dirs.value = []
      groups.value = []
      return
    }
    dirs.value = r.dirs || []
    // 传目录名与上一层名：多集文件要靠它归组，纯季目录名要退回上一层取剧名
    trail.value = [...trail.value, { lib, path, name }]
    const parentName = trail.value[trail.value.length - 2]?.name || ''
    groups.value = groupByTitle(r.files || [], name, parentName)
    // ★ 这个目录就是"一部剧"时**直接进详情**，不要先铺一张只有一部片的卡片墙
    //   再多点一次（用户看到的是"平白多了一重"，图三就是这么来的）。
    //   分类层（只有子目录）和电影合集（无集号）照常铺卡片。
    if (dirKindOf(r.dirs || [], r.files || []) === 'series' && groups.value.length === 1) {
      openGroup(groups.value[0], true)
    }
    // 先让文件名出来，海报随后补（不 await）
    void doScrape(`${lib}|${path}`, groups.value, lib)
    // 祖先链给目录封面用：季目录卡要能剥出上层的剧名（否则刮成「S01」）
    // ★ **必须含当前层**：coverTitleByProbe 只会往后追加子目录名。
    //   少一级时 `动漫/瑞克和莫蒂/S01` 拼成 `['动漫','S01']`，S01 被当纯季名
    //   跳过后落回「动漫」→ 8 个季目录全刮成《剑来 第一季》（实测踩过）。
    const ancestors = trail.value.map((t) => t.name)
    void doScrapeDirs(lib, `${lib}|${path}`, r.dirs || [], ancestors)
    // TV 壳：上一轮存的海报副本先接上，后端这次要是拉不到图也不至于全灰
    restoreMirror(Object.keys(posters.value))
    // 地址栏带上位置，刷新/后退能回到同一目录
    router.replace({ query: { lib, path } })
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

/**
 * 补海报。**异步、不阻塞列目录**：刮削要逐条查外网，几百个文件
 * 会把首屏拖到几十秒；文件名先出来，海报后补。
 *
 * ★ **绝不拿目录名当片名去刮**。实测踩过：分类目录「动漫」「其他」
 *   被豆瓣匹配成《剑来 第一季》和一部 1966 年的同名实验短片，于是图一
 *   两个分类格子上挂着完全不相干的影片海报。目录卡片的图必须来自
 *   **目录里的实际影视**（见 doScrapeDirs），不是目录名。
 *
 * @param where 这一批属于哪个位置（`lib|path`）。**必须显式传入**——
 *   不能读 currentLib/currentPath，因为刮削是异步的，请求回来时用户
 *   可能已经进了别的目录，读到的是新位置，于是这份结果被记到错误的 key 上，
 *   回到上一层时又得重刮。
 * @param lib 片库 id，透传给后端参与缓存 key（两个库各有《教父》时不共用）。
 *   与 `where` 同理由调用方传入，同样不能读 currentLib。
 */
async function doScrape(where: string, g: TitleGroup[], lib: string) {
  // 刮削名 ≠ 卡片名：`教父 第1部` 这种带部号的卡片要拿「教父」去搜（scrapeTitle），
  // 存/读也都按刮削名做 key，两边才对得上（见 posterOf 的调用处）。
  const wanted = g.map((x) => ({ title: scrapeTitleOf(x), year: x.year })).filter((x) => x.title)
  if (!wanted.length) return
  // 首屏只等前 N 张（看得见的那些），剩下的排进同一队列的第二波
  enqueueScrape(where, () => runScrape(where, wanted.slice(0, VISIBLE_FIRST), lib))
  if (wanted.length > VISIBLE_FIRST) {
    enqueueScrape(`${where}#rest`, () =>
      runScrape(`${where}#rest`, wanted.slice(VISIBLE_FIRST), lib, true),
    )
  }
}

/**
 * 刮削串行队列。
 *
 * ★ 原来是 `if (scraping.value) return` 的单飞：用户在 A 目录还没刮完就进 B，
 *   B 那一批会被**直接丢掉而且不会补**，只能退出去再进一次（实测）。现在谁都要
 *   刮，只是排队——后端每条之间固定 900ms，并发发只会被豆瓣限流，所以串行是对的。
 */
const scrapeQueue: Array<{ where: string; run: () => Promise<void> }> = []

async function pumpScrapes() {
  if (scraping.value) return
  scraping.value = true
  try {
    while (scrapeQueue.length) {
      const job = scrapeQueue.shift() as { where: string; run: () => Promise<void> }
      try {
        await job.run()
      } catch {
        /* 单条失败不影响后面的 */
      }
      scrapeQueued.delete(job.where)
    }
  } finally {
    scraping.value = false
  }
}

function enqueueScrape(where: string, run: () => Promise<void>) {
  if (scrapeQueued.has(where) || scrapedFor.value === where) return
  scrapeQueued.add(where)
  scrapeQueue.push({ where, run })
  void pumpScrapes()
}

/**
 * 真正打外网的那一段（由队列串行调用）。
 *
 * @param quiet 第二波（`#rest`）传 true：不参与"这一层刮过了"与限流提示的判定，
 *   否则第二波回来会把第一波刚设置的提示又改一遍。
 */
async function runScrape(
  where: string,
  wanted: Array<{ title: string; year: number }>,
  lib: string,
  quiet = false,
) {
  const { items, rateLimited } = await scrapeGroups(wanted, lib)
  // 合并而不是替换：各层的海报要能共存，否则从子目录退回根层时
  // 根层的目录卡片又变回灰块（用户看到的是"海报一闪就没了"）。
  const next = { ...posters.value }
  items.forEach((m, i) => {
    const w = wanted[i]
    if (!w || !m.poster) return
    next[posterKey(lib, w.title, w.year)] = posterUrl(m.poster)
  })
  posters.value = next
  // TV 壳：把这批海报顺手存进应用私有目录，后端不可达时兜底（见 doMirror）。
  void doMirror(items)
  if (rateLimited) {
    // 被限流就排一次自动重试，别让用户自己盯着点「刷新」
    scheduleScrapeRetry()
  } else {
    retryCount = 0
  }
  // 被豆瓣限流时**不要把 where 记成已刮**，否则这一层在本次会话里
  // 再也不会重试——用户点「刷新」也看不到海报。
  // ★ 两个方向都要动：限流时置位；**成功时必须清掉**，否则一旦被限过一次，
  //   这个提示就永久挂在标题栏（实测踩过：后端早已恢复，界面一直说"限流"）。
  if (!quiet) {
    scrapeLimit.value = rateLimited
    if (!rateLimited) scrapedFor.value = where
  }
}

/** 首屏优先刮多少张（其余排后续波次，避免几百张把首屏拖到几十秒） */
const VISIBLE_FIRST = 12

/** 限流自动重试的次数与计时器 */
let retryTimer: ReturnType<typeof setTimeout> | undefined
let retryCount = 0

/**
 * 被豆瓣限流后自动重试（1 / 2 / 4 分钟，最多三次）。
 *
 * 原来限流就整层放弃，用户看到的是"海报突然全没了"，只能自己点「刷新」。
 * 只重跑**当前这一层**，而且不在详情页/别的层级里打断用户。
 */
function scheduleScrapeRetry() {
  if (retryTimer || retryCount >= 3) return
  const delay = 60_000 * 2 ** retryCount
  retryCount++
  retryTimer = setTimeout(() => {
    retryTimer = undefined
    if (activeGroup.value || !trail.value.length) return
    scrapedFor.value = ''
    dirCovered.value = []
    void openReplace(currentLib.value, currentPath.value, currentName.value)
  }, delay)
}

/**
 * TV 壳的本地副本。
 *
 * 只在有原生桥时生效（`hasMetaMirror()`）；Web/PC 上是空操作。
 *
 * 存两样东西：
 *   - 每张海报的字节 → `files/library/posters/<hash>.jpg`（原生下，绕开 CORS）
 *   - 片名 → 本地文件名的映射 → `files/library/meta.json`
 *
 * 读的时候（见 `restoreMirror`）如果后端拿不到海报，就用这份映射把 img.src
 * 指向 `http://meow.local/mirror/poster/<hash>.jpg`。
 *
 * ★ 只读副本，不是第二套数据源：永远是后端优先，这里只在拉不到时兜底。
 */
async function doMirror(items: Array<{ poster?: string }>) {
  if (!hasMetaMirror()) return
  try {
    const mirror: Record<string, string> = JSON.parse(loadMetaMirror() || '{}')
    let changed = false
    for (const m of items) {
      const remote = m.poster ? posterUrl(m.poster) : ''
      if (!remote || mirror[remote]) continue
      const name = cachePoster(remote)
      if (name) {
        mirror[remote] = name
        changed = true
      }
    }
    // 映射表的 key 是远端地址（稳定、可复算），value 是本地文件名
    if (changed) saveMetaMirror(JSON.stringify(mirror))
  } catch {
    /* 镜像失败不影响主流程 */
  }
}

/**
 * 后端不可达时，用本地副本把海报补回来。
 *
 * @param keys 形如 `片名|年份` 的 key（与 posters 的键一致）
 */
function restoreMirror(keys: string[]) {
  if (!hasMetaMirror() || !keys.length) return
  try {
    const base = posterMirrorBase()
    if (!base) return
    const mirror: Record<string, string> = JSON.parse(loadMetaMirror() || '{}')
    const next = { ...posters.value }
    let n = 0
    for (const k of keys) {
      const remote = posters.value[k]
      if (!remote) continue
      const name = mirror[remote]
      if (name) {
        // 指向本地文件：同源（meow.local），WebView 直接能渲染
        next[k] = base + name
        n++
      }
    }
    if (n) posters.value = next
  } catch {
    /* 读不到就当没有副本 */
  }
}

/**
 * 目录卡片的封面：**向下探到第一个有视频的地方**，拿那里的片名去刮。
 *
 * 用户的口径：「目录海报应该显示目录下第一个影视的海报」。所以分类目录
 * （`电影/`）也要有图，它的图来自第一个子目录（`电影/教父/`）里的影片。
 *
 * 探测规则（每层最多再下探 {@link MAX_DEPTH} 层，避免 18NO 那种深目录拖慢首屏）：
 *   - 本层有视频 → 就在这里取片名，停止下探
 *   - 本层只有子目录 → 取**第一个**子目录继续下探（只跟一条路径，
 *     不是扇出整棵树：封面只需要一张图）
 *   - 探到底都没有视频 → 这张卡不刮，保持文件夹图标
 *
 * 「灰块比错图诚实」这条仍然成立：拿目录名去搜会得到《剑来》挂在"动漫"
 * 分类上那种错配（实测），所以宁可不刮。
 */
const MAX_DEPTH = 3

function doScrapeDirs(
  lib: string,
  where: string,
  dirList: LibraryEntry[],
  ancestors: string[] = [],
) {
  if (!dirList.length) return
  // 已经刮过这个位置就不再打外网
  if (dirCovered.value.includes(where)) return
  dirCovered.value = [...dirCovered.value, where]
  // 首屏只先探前 N 个目录：探测要逐个列目录，几十上百个会把首屏拖到几十秒
  enqueueScrape(where, () => runScrapeDirs(lib, where, dirList.slice(0, VISIBLE_FIRST), ancestors))
  if (dirList.length > VISIBLE_FIRST) {
    enqueueScrape(`${where}#rest`, () =>
      runScrapeDirs(lib, `${where}#rest`, dirList.slice(VISIBLE_FIRST), ancestors, true),
    )
  }
}

async function runScrapeDirs(
  lib: string,
  where: string,
  dirList: LibraryEntry[],
  ancestors: string[],
  quiet = false,
) {
  if (!dirList.length) return
  const candidates: Array<{ dir: LibraryEntry; title: string; fallback?: string }> = []
  const queue = [...dirList]
  const workers = Array.from({ length: Math.min(4, queue.length) }, async () => {
    for (;;) {
      const d = queue.shift()
      if (!d) return
      try {
        const hit = await coverTitleByProbe(lib, d, ancestors)
        if (hit.title) candidates.push({ dir: d, ...hit })
      } catch {
        /* 单个目录探失败不影响其余封面 */
      }
    }
  })
  await Promise.all(workers)
  if (!candidates.length) return
  const { items, rateLimited } = await scrapeGroups(
    candidates.map((c) => ({ title: c.title, year: 0 })),
    lib,
  )
  if (rateLimited) {
    // 整层封面都没拿到：允许下次进这一层再试（不要留在 dirCovered 里）
    dirCovered.value = dirCovered.value.filter((x) => x !== where)
    if (!quiet) scrapeLimit.value = true
    scheduleScrapeRetry()
    return
  }
  // 封面这批成功了，就把限流提示收起来（同 doScrape 的理由）
  if (!quiet) scrapeLimit.value = false
  const next = { ...posters.value }
  // 季名（`瑞克和莫蒂 第一季`）没命中、或者命中的条目根本不沾亲时，
  // 记下来等第二轮用剧名再刮一次 —— 一排同图也好过一张错图。
  const missed: Array<{ i: number; base: string }> = []
  const mirrored: Array<{ poster?: string }> = []
  items.forEach((m, i) => {
    const c = candidates[i]
    if (!c) return
    const kin = !c.fallback || !m.matchedTitle || titleKin(m.matchedTitle, c.fallback)
    if (m.poster && kin) {
      next[dirKey(lib, c.dir.path)] = posterUrl(m.poster)
      mirrored.push(m)
      return
    }
    if (c.fallback) missed.push({ i, base: c.fallback })
  })
  // 第二轮：季名没搜到就退回剧名（一次性批量，不是逐个打）
  if (missed.length) {
    const second = await scrapeGroups(
      missed.map((x) => ({ title: x.base, year: 0 })),
      lib,
    )
    second.items.forEach((m, j) => {
      const x = missed[j]
      const c = x && candidates[x.i]
      if (!m?.poster || !c) return
      next[dirKey(lib, c.dir.path)] = posterUrl(m.poster)
      mirrored.push(m)
    })
  }
  posters.value = next
  // TV 壳：目录封面同样要存镜像。
  // ★ 这条最初漏了 —— 实测 TV 上「电视剧/」这一层的 7 张卡是**目录卡**
  //   （下面还有一层），走的是 doScrapeDirs 而不是 doScrape，于是图片写了
  //   一个都没有（metaBytes=0）。而断网时用户最先看到的就是这一层，
  //   不存等于"兜底方案在这层失效"。
  void doMirror(mirrored)
}

/**
 * 豆瓣命中的标题是不是真的沾亲（`pick()` 一条都匹配不上时会退回第一条建议，
 * 于是"有海报"不等于"刮对了"）。只用来复核额外加料的名字（季名）。
 */
function titleKin(matched: string, base: string): boolean {
  const norm = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '')
  const m = norm(matched)
  const b = norm(base)
  return !!m && !!b && m.includes(b)
}

/**
 * 沿「第一个子目录」这条链下探，找到第一层有视频的目录，返回该用于搜刮的片名。
 *
 * @param ancestors 当前所在层的目录名链（如 `['动漫','瑞克和莫蒂']`）。
 *   **必须包含当前层**（调用方传整条 trail）：`动漫/瑞克和莫蒂/S01` 这张卡
 *   本身是季目录，只从 `S01` 起算会得到片名「S01」（实测），带上祖先才能
 *   剥出「瑞克和莫蒂」。
 * @returns `{ title }`；`fallback` 只在「季目录加了季名」时出现 ——
 *   季名没命中就退回剧名（见 doScrapeDirs 的第二轮）。
 */
async function coverTitleByProbe(
  lib: string,
  d: LibraryEntry,
  ancestors: string[] = [],
): Promise<{ title: string; fallback?: string }> {
  // 每张卡最多列 6 次目录：只跟「第一个子目录」时，第一个子目录是花絮/空目录
  // 就整张卡永远灰（实测片库里有 `纪录片/<空>/正片` 这种结构），所以允许试兄弟目录，
  // 但必须有预算，否则一棵深目录树会把首屏拖死。
  const budget = { n: 0 }
  const hit = await probeDir(lib, d, [...ancestors, d.name], 0, budget)
  if (!hit.title) return hit
  // 这张卡本身就是季目录（S01 / 毛骗S02）→ 加上季名，让每季有自己的图。
  // ★ 只看**卡片自己**的名字：`瑞克和莫蒂` 探测时会下探到 S01，但那张卡
  //   代表整部剧，加季名会把它变成"第一季"。
  const season = parseFolderName(d.name).season
  const withSeason = season > 0 ? seasonTitle(hit.title, season) : ''
  if (withSeason) return { title: withSeason, fallback: hit.title }
  return hit
}

/** 每个目录最多试几个兄弟子目录（3 = 第一个没片还有两次机会） */
const MAX_BRANCH = 3

/** {@link coverTitleByProbe} 的递归体：先看本层有没有视频，没有就试子目录 */
async function probeDir(
  lib: string,
  dir: LibraryEntry,
  names: string[],
  depth: number,
  budget: { n: number },
): Promise<{ title: string; fallback?: string }> {
  if (budget.n >= 6) return { title: '' }
  budget.n++
  const r = await fetchLibraryDir(lib, dir.path)
  if (!r.ok) return { title: '' }
  const videos = (r.files || []).filter((f) => isVideoName(f.name))
  if (videos.length) return { title: coverTitleFor(names, videos) }
  if (depth + 1 >= MAX_DEPTH) return { title: '' }
  for (const next of (r.dirs || []).slice(0, MAX_BRANCH)) {
    const hit = await probeDir(lib, next, [...names, next.name], depth + 1, budget)
    if (hit.title) return hit
  }
  return { title: '' }
}

function isVideoName(name: string): boolean {
  return /\.(mp4|mkv|avi|ts|m2ts|mov|wmv|flv|webm|rmvb|iso)$/i.test(name)
}

/** 目录封面的 key（带库 id：两个库都有 `电影/教父` 时不能互相覆盖） */
function dirKey(lib: string, path: string): string {
  return `dir:${lib}|${path}`
}

/** 影片海报的 key（带库 id 与刮削名） */
function posterKey(lib: string, title: string, year: number): string {
  return `${lib}|${title}|${year}`
}

function dirPosterOf(d: LibraryEntry, lib = currentLib.value): string {
  return posters.value[dirKey(lib, d.path)] || ''
}

function posterOf(title: string, year = 0): string {
  return posters.value[posterKey(currentLib.value, title, year)] || ''
}

/**
 * 影片卡 / 详情页的海报。
 *
 * key 用**刮削名**而不是卡片名：`教父 第1部` 这类卡片的刮削名是「教父」
 * （见 groupByTitle），写和读必须是同一个名字，否则永远取不到。
 */
function groupPosterOf(g: TitleGroup): string {
  return posterOf(scrapeTitleOf(g), g.year)
}

/**
 * 海报加载失败。
 *
 * 豆瓣大图（`l_ratio_poster`）极少数条目没有 → 先退回小图（`s_`）；
 * 小图也失败就把这条从表里删掉，让卡片回到「片名占位」，
 * 而不是留一个浏览器裂图。
 */
function onPosterError(key: string, e: Event) {
  const img = e.target as HTMLImageElement
  const cur = posters.value[key] || ''
  if (cur.includes('l_ratio_poster')) {
    posters.value = { ...posters.value, [key]: cur.replace(/l_ratio_poster/g, 's_ratio_poster') }
    img.src = posters.value[key]
    return
  }
  const next = { ...posters.value }
  delete next[key]
  posters.value = next
}

// ---------------------------------------------------------------- 刮削绑定
//
// 一条刮削结果 = 「片名 → 豆瓣条目」的映射。用户能对它做两件事：
//   解除绑定：删掉这条映射（这张海报消失，下次重新匹配）
//   重新搜刮：跳过缓存重查一次（换了片名或上次抖动没刮到）
//
// 两者分开是因为诉求不同：有些片名怎么搜都匹配到错的条目
// （比如《毛骗》会被匹配到同名的无关条目），此时用户要的是"别乱配"，
// 而不是"再试一次"。

/** 当前影片是否已绑定到某个豆瓣条目（以海报是否存在为判据） */
const boundPoster = computed(() => {
  const g = activeGroup.value
  if (!g) return ''
  return groupPosterOf(g)
})

const scrapeBusy = ref(false)
const scrapeMsg = ref('')
const scrapeMsgOk = ref(true)

/**
 * 重新搜刮某一部（详情页按钮与卡片浮层共用）。
 *
 * 必须带 refresh：后端命中缓存会直接返回旧结果，
 * 不带就变成"点了没反应"（缓存里存的就是旧海报）。
 */
async function rescrapeGroup(g: TitleGroup) {
  if (scrapeBusy.value) return
  scrapeBusy.value = true
  scrapeMsg.value = ''
  try {
    // 刮削名（不是卡片名）：`教父 第1部` 要重搜「教父」，否则又会搜成《封神第一部》
    const want = scrapeTitleOf(g)
    const m = await scrapeOne(want, g.year, true, currentLib.value)
    if (m.ok && m.poster) {
      posters.value = {
        ...posters.value,
        [posterKey(currentLib.value, want, g.year)]: posterUrl(m.poster),
      }
      scrapeMsgOk.value = true
      scrapeMsg.value = `已匹配到「${m.title || want}」${m.year ? `（${m.year}）` : ''}`
    } else if (m.ok) {
      // 匹配到了但没有海报：豆瓣有条目却无图，也算成功但要说清
      scrapeMsgOk.value = false
      scrapeMsg.value = '匹配到条目，但它没有海报图'
    } else {
      scrapeMsgOk.value = false
      scrapeMsg.value = '没搜到匹配的条目。可以试试把文件夹改成豆瓣上的正式片名再搜。'
    }
  } finally {
    scrapeBusy.value = false
  }
}

async function rescrapeActive() {
  const g = activeGroup.value
  if (g) await rescrapeGroup(g)
}

/** 解除某一部的刮削绑定（详情页与卡片浮层共用） */
async function unbindGroup(g: TitleGroup) {
  if (scrapeBusy.value) return
  if (
    !(await confirmDialog(
      `解除「${g.title}」的刮削绑定？\n海报会被移除，下次进目录会重新匹配。文件本身不受影响。`,
    ))
  )
    return
  scrapeBusy.value = true
  scrapeMsg.value = ''
  try {
    // 后端缓存 key 用的是刮削名，解除绑定也必须用同一个名字
    const want = scrapeTitleOf(g)
    const ok = await unbindScrape(want, g.year, currentLib.value)
    const next = { ...posters.value }
    delete next[posterKey(currentLib.value, want, g.year)]
    posters.value = next
    scrapeMsgOk.value = ok
    // 解除后清掉"这个位置刮过了"的记录，否则再进同一目录不会重刮
    scrapedFor.value = ''
    scrapeMsg.value = ok
      ? '已解除绑定。下次进这个目录会重新匹配。'
      : '这条本来就没有绑定记录（可能是刚清过缓存）'
  } finally {
    scrapeBusy.value = false
  }
}

async function unbindActive() {
  const g = activeGroup.value
  if (g) await unbindGroup(g)
}

// ------------------------------------------------------------ 卡片浮层
//
// 以前「重新搜刮 / 解除绑定」只有进详情页才能点，而错配一眼就能在卡片墙上
// 看出来（整排《封神》）。卡片上给同一组操作：长按（触屏/TV 遥控没有右键）
// 或右键唤出，浮层**贴着被长按的那张卡**出现，不做固定角标。

const cardMenu = ref<{ g: TitleGroup; x: number; y: number } | null>(null)
let pressTimer: ReturnType<typeof setTimeout> | undefined

function openCardMenu(g: TitleGroup, x: number, y: number) {
  const w = 200
  const h = 110
  cardMenu.value = {
    g,
    x: Math.max(8, Math.min(x, window.innerWidth - w - 8)),
    y: Math.max(8, Math.min(y, window.innerHeight - h - 8)),
  }
}

function closeCardMenu() {
  cardMenu.value = null
}

function startCardPress(g: TitleGroup, e: PointerEvent) {
  stopCardPress()
  // 右键走 contextmenu，不启动长按计时
  if (e.pointerType === 'mouse' && e.button !== 0) return
  const x = e.clientX
  const y = e.clientY
  pressTimer = setTimeout(() => openCardMenu(g, x, y), 650)
}

function stopCardPress() {
  if (pressTimer) {
    clearTimeout(pressTimer)
    pressTimer = undefined
  }
}

/** 浮层里的两个动作（关掉浮层再执行，避免确认框被浮层盖住） */
async function cardAct(kind: 'rescrape' | 'unbind') {
  const m = cardMenu.value
  if (!m) return
  const g = m.g
  closeCardMenu()
  if (kind === 'rescrape') await rescrapeGroup(g)
  else await unbindGroup(g)
}

/** TV 壳：清空本机的海报副本（后端不可达时就没得回退了，所以要先确认）。 */
async function clearMirror() {
  if (
    !(await confirmDialog(
      '清空本机存的海报副本？\n清掉之后，后端连不上时就看不到海报了。NAS 上的原图不受影响。',
      { danger: true },
    ))
  )
    return
  if (clearMetaMirror()) {
    scrapeMsgOk.value = true
    scrapeMsg.value = '已清空本机海报副本'
  } else {
    scrapeMsgOk.value = false
    scrapeMsg.value = '清空失败（可能本来就没有副本）'
  }
}

/**
 * 添加 / 编辑改用**原位弹框**（2026-11-18 用户反馈）。
 *
 * <p>原来跳 `/library/add` 路由：组件被卸载重建，取消/保存回来时
 * `onMounted` 先 `loadRoot()`（`loading=true` → 整页骨架），用户看到
 * 的就是"关掉弹框片库闪一下刷新"。弹框不离开本页，焦点、滚动位置、
 * 已加载海报全都不用重算。
 *
 * <p>编辑复用同一个组件（`edit-id` 有值即编辑），字段与校验完全一致。
 */
const addOpen = ref(false)
const addEditId = ref('')
/** 打开弹框的按钮：关掉后把焦点还给它（tv-nav 的自动记录偶尔落到邻居） */
let addOpener: HTMLElement | null = null

function openerOf(ev?: Event): HTMLElement | null {
  const t = ev && ev.currentTarget
  return t instanceof HTMLElement ? t : null
}

function goAdd(ev?: Event) {
  addOpener = openerOf(ev)
  addEditId.value = ''
  addOpen.value = true
}

function goEdit(id: string, ev?: Event) {
  addOpener = openerOf(ev)
  addEditId.value = id
  addOpen.value = true
}

/**
 * 关弹框：先把焦点还给打开它的按钮。
 *
 * <p>tv-nav 自己会记 `scopeReturnEl`，但在真机上出现过"关掉后落到隔壁
 * 删除按钮"的情况（几何上更近）。显式归还比依赖它稳，和
 * `__meowTvFocus(el, true)` 的用法一致。
 */
function closeAdd() {
  addOpen.value = false
  const el = addOpener
  addOpener = null
  if (!el || !document.contains(el)) return
  void nextTick(() => {
    const w = window as unknown as { __meowTvFocus?: (t: Element, pin?: boolean) => boolean }
    if (typeof w.__meowTvFocus === 'function') w.__meowTvFocus(el, true)
    else el.focus()
  })
}

/**
 * 弹框保存后原地刷新库表。
 *
 * <p>不能直接 `loadRoot()`：它会把 `loading` 置 true，整页先变成骨架
 * 再画回来——那正是用户报的"闪一下刷新"。这里只换 `overview`，
 * 目录/海报状态原样保留。
 */
async function onLibSaved() {
  closeAdd()
  try {
    overview.value = await fetchLibrary()
    error.value = ''
    for (const t of overview.value.tops || []) {
      void doScrapeDirs(t.id, `root|${t.id}`, t.dirs || [])
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

/**
 * 删除一个片库。
 *
 * 整表提交（后端按 id 覆盖写）：所以必须把**其余条目原样**带上，
 * 且不能丢 pass —— 前端拿不到明文密码，后端会把「没带 pass」当成保持原值
 * （见 core/Library.saveConfig）。
 */
async function removeLib(id: string, name: string) {
  if (
    !(await confirmDialog(`删除片库「${name}」？\n只会移除这个配置，NAS 上的文件不受影响。`, {
      danger: true,
    }))
  )
    return
  try {
    const rest = (overview.value?.libs || []).filter((x) => x.id !== id)
    await saveLibrary(
      rest.map((x) => ({
        id: x.id,
        name: x.name,
        type: x.type,
        base: x.base,
        user: x.user,
        root: x.root,
        ...(x.domain ? { domain: x.domain } : {}),
      })),
    )
    await loadRoot()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

function back() {
  // 影片层先退回目录层。
  // ★ 但「自动进详情」的那一层不能退到卡片墙——那个墙只有一张卡，退过去
  //   等于让用户多点一次，正是要去掉的那一重。此时直接退到上一层目录。
  if (activeGroup.value) {
    if (!autoEntered.value) {
      activeGroup.value = null
      return
    }
    autoEntered.value = false
    popTrail()
    return
  }
  if (trail.value.length <= 1) {
    trail.value = []
    dirs.value = []
    groups.value = []
    router.replace({ query: {} })
    return
  }
  popTrail()
}

/** 退到上一层目录（地址栏同步，openReplace 不再压栈）。 */
function popTrail() {
  const next = trail.value.slice(0, -1)
  trail.value = next
  const t = next[next.length - 1]
  if (!t) {
    router.replace({ query: {} })
    return
  }
  // 地址栏跟着退回上一层，否则刷新/分享会回到子目录（和面包屑显示的不一致）
  router.replace({ query: { lib: t.lib, path: t.path } })
  // 回到上一层：openReplace 只刷新内容，**不再压栈**
  void openReplace(t.lib, t.path, t.name)
}

/**
 * 重新列某一层的内容（不动 trail 的层级）。
 *
 * ★ 这里绝不能 `trail = [...trail, x]`：调用方（返回上一层、刷新）都已经
 *   把 trail 摆好了，再压一次会得到「本地片库 / 电视剧 / 电视剧」这种
 *   重复面包屑，而且返回按钮永远退不出去（每点一次又加回来一项）。
 */
async function openReplace(lib: string, path: string, name: string) {
  loading.value = true
  error.value = ''
  activeGroup.value = null
  try {
    const r = await fetchLibraryDir(lib, path)
    dirs.value = r.ok ? r.dirs || [] : []
    const parentName = trail.value[trail.value.length - 2]?.name || ''
    groups.value = r.ok ? groupByTitle(r.files || [], name, parentName) : []
    if (r.ok && dirKindOf(r.dirs || [], r.files || []) === 'series' && groups.value.length === 1) {
      openGroup(groups.value[0], true)
    }
    void doScrape(`${lib}|${path}`, groups.value, lib)
    // 同 open()：祖先链必须含当前层，否则整层子目录封面会刮到分类名上
    const ancestors = trail.value.map((t) => t.name)
    void doScrapeDirs(lib, `${lib}|${path}`, dirs.value, ancestors)
    if (!r.ok) error.value = r.msg || '列目录失败'
  } finally {
    loading.value = false
  }
}

function playUrl(filePath: string): string {
  return libraryStream(currentLib.value, filePath)
}

/**
 * 文件名看起来"需要转码"的提示位（只用于列表上的角标与文案，**不再决定播放路径**）。
 *
 * <p><b>为什么不能再按扩展名预判</b>（2026-11-02 实测）：
 * 原来 `.mkv` / `h265` 一律先送去转码，理由是 canPlayType 对
 * `video/x-matroska` 返回空串。但**实测这台 TV 的 WebView 110 能直接播**：
 * 武林外传 mkv（720p）、沉默的真相 mkv（4K）、白夜追凶 H265 mp4（4K）
 * 六个真实文件里五个直连成功，只有 18GB 的 DTS MKV 超时。
 * 而 TV 本地网关**没有 ffmpeg**（PC 插件与 Docker 镜像里有），
 * 于是"预判要转码"= 必然失败 → 用户看到「除了武林外传都播不了」
 * （武林外传恰好是唯一一个走了别的路径的）。
 *
 * 现在的策略是**先直连、失败再转码**（见 PlayView 的 onPlayerError），
 * canPlayType 只能当"可能有坑"的弱提示，绝不能当门禁。
 */
function looksHeavy(name: string): boolean {
  const n = name.toLowerCase()
  return /\.mkv$/.test(n) || /hevc|h\.?265|x265|dts/.test(n)
}

/**
 * 起播：**永远先直连**。
 *
 * <p>转码兜底改到播放页做（那里才拿得到"真的播不了"这个事实）：
 * 直连成功 → 零等待；直连失败 → 播放页按 lib+path 请求转码。
 * 这样在没装 ffmpeg 的 TV 本地网关上也能播绝大多数文件。
 */
async function playOrTranscode(f: { path: string; name: string }) {
  play(f.path)
}

/**
 * 下载到本机。
 *
 * 片库文件是直链（不是 HLS），用浏览器 a[download] 最可靠：
 * 服务端不落盘、不占后端磁盘，用户拿到的是原文件。
 * 想"断网慢慢看"用同一路径也能下，但要注意 4K 单集 1.4–2GB。
 */
function downloadFile(f: { path: string; name: string }) {
  const a = document.createElement('a')
  a.href = libraryStream(currentLib.value, f.path)
  a.download = f.name
  document.body.appendChild(a)
  a.click()
  a.remove()
}

/** 后端给的是 /api/... 相对路径，PC 端 base 是绝对地址要拼上。 */
function absoluteApiUrl(p: string): string {
  if (/^https?:/i.test(p)) return p
  const b = apiBase()
  return b ? b.replace(/\/$/, '') + p : p
}

async function play(filePath: string) {
  // 媒体票（auth v2）：片库直链同样要靠 ?mt= 放行（<video> 带不了 header）。
  if (authRequired.value) await ensureLibraryTicket(currentLib.value)
  const u = playUrl(filePath)
  // 交给播放页：片库地址是 http(s)，但**不能**让它被包成 /api/proxy/stream
  // （那个端点拒绝回环、且只认任意 URL）。播放页按 query 里的 library 标记分流。
  void router.push({
    name: 'play',
    params: { site: '__library__' },
    query: { library: currentLib.value, file: filePath, direct: u },
  })
}

function groupKey(g: TitleGroup): string {
  return `${g.title}|${g.year}`
}

function episodeLabel(f: { episode: number; part: number; name: string }): string {
  if (f.episode > 0) return String(f.episode)
  if (f.part > 0) return `第${f.part}部`
  return f.name.replace(/\.[^.]+$/, '')
}

/** 影片层的当前集（详情页里高亮的那一格）。片库没有"上次看到哪"，默认第一集 */
const activeEp = ref('')
/**
 * 进入影片层。
 *
 * @param auto true = 目录本身就是一部剧，前端自动进来（不是用户点卡片）。
 *   只为了让返回键区别对待（见 back()）。
 */
function openGroup(g: TitleGroup, auto = false) {
  activeGroup.value = g
  autoEntered.value = auto
  activeEp.value = g.files[0]?.path || ''
  // 上一部片的搜刮结果不能带到这一部（否则会看到别人的"已匹配到…"）
  scrapeMsg.value = ''
}

/** 影片层：整组的体积与集数，放在标题下方（对齐详情页的元信息行） */
const groupMeta = computed(() => {
  const g = activeGroup.value
  if (!g) return ''
  const bytes = g.files.reduce((a, f) => a + (f.size || 0), 0)
  return [g.year ? String(g.year) : '', `${g.files.length} 集`, fmtSize(bytes)]
    .filter(Boolean)
    .join(' · ')
})

// 页签切换：从缓存页回到片库页时，保持在原目录
watch(tab, (t) => {
  if (t === 'lib' && !trail.value.length && !loading.value) void loadRoot()
})

/**
 * TV 返回键：与左上角「返回」按钮同一条路径（2026-11-04 用户要求）。
 *
 * <p><b>为什么原来会直接回首页</b>：片库内部的层级是组件自己的 `trail` 栈，
 * 地址栏用 `router.replace` 同步（刻意不压栈，见 popTrail 的注释），所以
 * 路由历史里没有"上一级目录"这一层 —— 壳调 `router.back()` 只能退回上一个
 * 真实路由（首页）。而左上角返回按钮走的是 `trail` 栈，两者自然不一致。
 *
 * <p>修法：注册页面级返回钩子（与设置页同一套约定），返回键先问本页；
 * 只有已经在最外层（没有 trail、没有打开的影片层）时才 return false，
 * 把按键还给壳去退出片库。
 */
function handleTvBackHere(): boolean {
  /*
   * ★ 只在「本地片库」页签接管（2026-11-04 补）。
   *
   * 切到「我的缓存」页签后 trail/activeGroup 还留着（切页签不清栈），
   * 此时按返回会去改那个看不见的 lib 栈、还 return true ——
   * 用户看到的是"按了几次返回都没反应"，而且 back() 里的 router.replace
   * 会把 ?tab=cache 从地址栏抹掉。
   * 缓存页签自己的详情层由 CachePanel 的 consume 处理，这里直接放行。
   */
  if (tab.value !== 'lib') return false
  if (!trail.value.length && !activeGroup.value) return false
  back()
  return true
}

onMounted(async () => {
  ;(window as unknown as { __meowTvPageBack?: () => boolean }).__meowTvPageBack =
    handleTvBackHere
  /*
   * 通知 App.vue 重新判定"返回键该不该由它接管"。
   *
   * 必须发：App 的 `watch(route.path)` 早于本组件 onMounted，
   * 那一刻钩子还不存在，判据会算出"不用接管"并把 `__meowTvConsumeBack` 摘掉 ——
   * 结果按返回时壳直接 goBack()（2026-10-30 设置页实测过同一症状）。
   */
  window.dispatchEvent(new Event('meow:tv-pageback'))
  const q = route.query
  // 深链直达缓存页签（原 /offline 路由会带 ?tab=cache）
  if (String(q.tab || '') === 'cache') tab.value = 'cache'
  const lib = String(q.lib || '')
  const path = String(q.path || '')
  // 有 lib+path 时**不要先 loadRoot**：根列表对目录层没用，只会让
  // 「从添加页取消回来」先闪一下根层再跳进目录（看起来像刷新了两次）。
  if (!(lib && path)) await loadRoot()
  if (lib && path) {
    // 深链：按路径逐级打开，保证面包屑完整
    const segs = path.split('/').filter(Boolean)
    let acc = ''
    for (const s of segs) {
      acc = acc ? `${acc}/${s}` : s
      const name = s
      if (acc === path) {
        await open(lib, acc, name)
      } else {
        trail.value = [...trail.value, { lib, path: acc, name }]
      }
    }
  }
})
onBeforeUnmount(() => {
  // 必须清：否则别的页面会继承"返回上一层目录"这个片库专属行为
  const w = window as unknown as { __meowTvPageBack?: () => boolean }
  if (w.__meowTvPageBack === handleTvBackHere) w.__meowTvPageBack = undefined
  window.dispatchEvent(new Event('meow:tv-pageback'))
  // 限流重试与长按计时器都要收掉，否则离页后还会去打外网/弹浮层
  if (retryTimer) clearTimeout(retryTimer)
  stopCardPress()
})

</script>

<template>
  <div class="mx-auto w-full max-w-[1400px] px-4 py-5 max-sm:px-3 max-sm:py-4">
    <!--
      「我的」三页签（历史/收藏/片库）：手机上从「我的」页签点进片库后，
      这条要跟着出现在片库页顶部，否则页签栏整个消失、回历史/收藏得多点一次
      底栏（2026-11-12 用户反馈「片库页上方看不见页签胶囊组」）。
      只给 <640：≥640 从顶栏「片库」进来（那里没有底部标签栏，也没有这条），
      片库页保持原来的紧凑头部。
    -->
    <div class="hidden max-sm:block">
      <MeTabs active="library" />
    </div>
    <!-- 页签：片库 / 缓存。两个都是"我本地有什么片子"，但量级差很多
         （缓存可能只有几集，片库可能上万文件），叠在一页会让后者永远在屏幕外 -->
    <div class="flex items-center gap-1.5">
      <button
        v-for="t in [
          { id: 'lib', label: libTabLabel },
          { id: 'cache', label: '我的缓存' },
        ]"
        :key="t.id"
        type="button"
        class="rounded-full border px-3.5 py-1.5 text-[13px] transition max-sm:py-2"
        :class="
          tab === t.id
            ? 'border-accent bg-accent font-medium text-on-accent'
            : 'border-line bg-card text-ink-2 hover:bg-card-hover'
        "
        @click="tab = (t.id as Tab)"
      >
        {{ t.label }}
      </button>
    </div>

    <!-- ══════════════ 我的缓存 ══════════════ -->
    <CachePanel v-if="tab === 'cache'" class="mt-5" />

    <!-- ══════════════ 本地片库 ══════════════ -->
    <template v-else>
      <div class="mt-4 flex items-center justify-between gap-3">
        <div class="flex min-w-0 items-center gap-2">
          <button
            v-if="trail.length || activeGroup"
            class="shrink-0 rounded-md bg-card px-2.5 py-1 text-xs transition hover:bg-card-hover"
            @click="back"
          >
            返回
          </button>
          <h1 class="truncate text-lg font-medium">
            {{ activeGroup ? activeGroup.title : libTabLabel }}
            <span v-if="trail.length && !activeGroup" class="ml-2 text-sm text-ink-3">
              / {{ trail.map((t) => t.name).join(' / ') }}
            </span>
          </h1>
        </div>
        <div class="flex shrink-0 items-center gap-2">
          <span v-if="scraping" class="text-[11px] text-ink-3">正在补海报…</span>
          <!-- 限流要说出来：不然"没海报"会被当成自己文件命名有问题 -->
          <span
            v-else-if="scrapeLimit"
            class="text-[11px] text-warn"
            title="豆瓣对密集请求限流。海报会自动重试，也可以点「刷新」再试一次"
          >
            豆瓣限流，海报稍后自动重试
          </span>
          <!--
            data-tv-key：给「返回恢复焦点」一个稳定锚点。
            没有它时 useReturnRestore 只能退到 nth-child 路径
            （`div:nth-child(1) > main:nth-child(3) > …`），页面结构一变
            （页签增删、列表空/非空切换）就命中不到，按返回会落到页签上。
          -->
          <button
            data-tv-key="library-add"
            class="rounded-md bg-card px-2.5 py-1 text-[11px] text-ink-3 transition hover:bg-card-hover max-sm:px-3 max-sm:py-1.5"
            @click="goAdd($event)"
          >
            添加片库
          </button>
          <button
            class="rounded-md bg-card px-2.5 py-1 text-[11px] text-ink-3 transition hover:bg-card-hover max-sm:px-3 max-sm:py-1.5"
            @click="trail.length ? openReplace(currentLib, currentPath, currentName) : loadRoot()"
          >
            刷新
          </button>
        </div>
      </div>

      <!-- 影片层：目录名做面包屑的第二段 -->
      <div v-if="activeGroup && trail.length" class="mt-1 text-[11px] text-ink-3">
        {{ trail.map((t) => t.name).join(' / ') }}
      </div>

      <!-- 卡片浮层操作的结果（列表页也要能看到：浮层不是详情页专属） -->
      <div v-if="!activeGroup && scrapeMsg" class="mt-1 text-[11px]" :class="scrapeMsgOk ? 'text-ok' : 'text-hot'">
        {{ scrapeMsg }}
      </div>

      <div v-if="loading" class="mt-16 grid place-items-center">
        <div class="text-center">
          <div class="mx-auto h-9 w-9 animate-spin rounded-full border-2 border-white/20 border-t-accent" />
          <p class="mt-3 text-xs text-ink-3">正在列目录…</p>
        </div>
      </div>

      <!-- 错误态要给下一步：只显示一句话，用户只能猜是回去还是重进 -->
      <EmptyState v-else-if="error" class="mt-16" icon="📁" :title="error">
        <button
          type="button"
          class="rounded-md bg-card px-4 py-2.5 text-sm text-ink-2 hover:bg-card-hover"
          @click="loadRoot"
        >
          重新加载片库
        </button>
      </EmptyState>

      <!-- ────────── 影片层：对齐详情页（海报 + 信息 + 选集）────────── -->
      <template v-else-if="activeGroup">
        <div class="mt-5 block sm:flex sm:flex-row sm:gap-6">
          <div class="w-full shrink-0 max-sm:float-left max-sm:mb-1 max-sm:mr-3.5 max-sm:w-24 sm:w-48">
            <div class="aspect-[2/3] overflow-hidden rounded-xl bg-elevated shadow-2xl max-sm:rounded-lg">
              <!--
                ★ 必须传 proxied=false：posters 里存的是已经过 posterUrl() 的
                「/api/proxy/image?url=...」。PosterImage 默认会再代理一次，
                变成 /api/proxy/image?url=%2Fapi%2Fproxy%2Fimage%3Furl%3D...，
                后端把这个内层路径当外网地址去拉，必然失败 ——
                表现为"已绑定海报"但画面里始终是胶片占位图。
              -->
              <PosterImage
                :src="groupPosterOf(activeGroup)"
                :alt="activeGroup.title"
                :proxied="false"
              />
            </div>
          </div>
          <div class="min-w-0 flex-1">
            <h2 class="text-2xl font-semibold tracking-tight max-sm:text-[20px]">{{ activeGroup.title }}</h2>
            <div class="mt-2 text-sm text-ink-3 max-sm:mt-1 max-sm:text-[13px]">{{ groupMeta }}</div>
            <p class="mt-3 text-[11px] leading-relaxed text-ink-3">
              来自片库
              <code class="rounded bg-field px-1">{{ currentLib }}</code>
              的「{{ currentName }}」目录。直接点集号播放；只有真的播不了才会自动转码
              （转码需要后端带 ffmpeg，Docker / PC 插件版自带）。
            </p>
            <div class="mt-5 flex flex-wrap items-center gap-3 max-sm:mt-4 max-sm:clear-both max-sm:gap-2">
              <button
                class="flex items-center gap-2 rounded-lg bg-accent px-6 py-2.5 text-sm font-semibold text-on-accent transition hover:bg-accent-dim max-sm:px-4 max-sm:py-2 max-sm:text-[13px]"
                @click="activeGroup.files[0] && playOrTranscode(activeGroup.files[0])"
              >
                <svg class="h-4 w-4" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M8 5v14l11-7z" />
                </svg>
                立即播放
              </button>

              <!-- 刮削绑定操作：重新搜刮 + 解除绑定 -->
              <button
                class="flex items-center gap-1.5 rounded-lg bg-card px-4 py-2.5 text-sm text-ink-2 transition hover:bg-card-hover hover:text-ink disabled:opacity-40 max-sm:px-3 max-sm:py-2 max-sm:text-[13px]"
                :disabled="scrapeBusy"
                :title="
                  boundPoster
                    ? '跳过缓存重新查一次豆瓣，换个匹配'
                    : '按当前目录名去豆瓣搜一次'
                "
                @click="rescrapeActive"
              >
                <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M21 12a9 9 0 1 1-3-6.7M21 3v6h-6" />
                </svg>
                {{ scrapeBusy ? '搜刮中…' : '重新搜刮' }}
              </button>
              <button
                v-if="boundPoster"
                class="flex items-center gap-1.5 rounded-lg px-4 py-2.5 text-sm text-ink-3 transition hover:bg-card-hover hover:text-hot disabled:opacity-40 max-sm:px-3 max-sm:py-2 max-sm:text-[13px]"
                :disabled="scrapeBusy"
                title="移除这张海报的匹配，下次进目录重新搜"
                @click="unbindActive"
              >
                <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M18 6 6 18M6 6l12 12" />
                </svg>
                解除绑定
              </button>
            </div>

            <!-- 绑定状态：让"有没有匹配上"变成一个可见事实，而不是靠海报猜 -->
            <p class="mt-2 flex flex-wrap items-center gap-2 text-[11px]">
              <span
                class="rounded px-1.5 py-0.5"
                :class="boundPoster ? 'bg-ok/15 text-ok' : 'bg-field text-ink-3'"
              >
                {{ boundPoster ? '已绑定海报' : '未绑定' }}
              </span>
              <span v-if="scrapeMsg" :class="scrapeMsgOk ? 'text-ok' : 'text-warn'">
                {{ scrapeMsg }}
              </span>
              <span v-else-if="!boundPoster" class="text-ink-3">
                海报按片名「{{ activeGroup.title }}」匹配；搜不到可以改文件夹名再试。
              </span>
              <!-- TV 壳才有本地副本（Web/PC 上 hasMetaMirror() 为 false，不渲染） -->
              <button
                v-if="hasMetaMirror()"
                class="rounded px-1.5 py-0.5 text-ink-3 transition hover:bg-card-hover hover:text-hot"
                title="清掉本机存的海报副本；后端不可达时就没法回退了"
                @click="clearMirror"
              >
                清空本机海报副本
              </button>
            </p>
          </div>
        </div>

        <div class="mt-8 flex flex-wrap items-center gap-3">
          <h2 class="text-sm font-medium text-ink-2">选集</h2>
          <span class="text-[11px] text-ink-3">共 {{ activeGroup.files.length }} 集</span>
        </div>
        <div class="mt-3 grid grid-cols-4 gap-2 sm:grid-cols-6 md:grid-cols-8 lg:grid-cols-10">
          <button
            v-for="f in activeGroup.files"
            :key="f.path"
            class="relative truncate rounded-md px-2 py-2 text-center text-[13px] transition max-sm:py-2.5"
            :class="
              activeEp === f.path
                ? 'bg-accent/20 text-accent'
                : 'bg-card text-ink-2 hover:bg-accent/20 hover:text-accent'
            "
            :title="f.name + (looksHeavy(f.name) ? '（疑似大编码，播不了会自动转码）' : '') + ' — 长按/右键可下载'"
            @click="((activeEp = f.path), playOrTranscode(f))"
            @contextmenu.prevent="downloadFile(f)"
          >
            {{ episodeLabel(f) }}
            <span v-if="transcoding[f.path]" class="mt-0.5 block truncate text-[10px] text-accent">
              {{ transcoding[f.path] }}
            </span>
          </button>
        </div>
      </template>

      <!-- ────────── 目录层：对齐首页的海报卡片墙 ────────── -->
      <template v-else>
        <!-- 已配置的片库：就在它对应的内容旁边管理（设置页那份已删除） -->
        <div v-if="!trail.length && overview?.libs?.length" class="mt-4 flex flex-wrap items-center gap-2">
          <span class="text-[11px] text-ink-3">已配置</span>
          <div
            v-for="l in overview.libs"
            :key="l.id"
            class="flex items-center gap-1.5 rounded-md border border-line bg-card py-1 pl-2.5 pr-1"
          >
            <span class="text-[11px] text-ink-2">{{ l.name }}</span>
            <span class="rounded bg-field px-1 py-0.5 text-[10px] text-ink-3">{{ l.type }}</span>
            <button
              class="rounded px-1.5 py-0.5 text-[11px] text-ink-3 transition hover:bg-card-hover hover:text-ink"
              @click="goEdit(l.id, $event)"
            >
              编辑
            </button>
            <button
              class="rounded px-1.5 py-0.5 text-[11px] text-ink-3 transition hover:bg-card-hover hover:text-hot"
              @click="removeLib(l.id, l.name)"
            >
              删除
            </button>
          </div>
        </div>

        <!-- 根：每个库的顶层目录 -->
        <template v-if="!trail.length">
          <div v-for="top in overview?.tops || []" :key="top.id" class="mt-6">
            <div class="mb-3 flex items-center gap-2 text-xs text-ink-3">
              <span class="font-medium text-ink-2">{{ top.name }}</span>
              <span v-if="top.error" class="text-hot">{{ top.error }}</span>
            </div>
            <div v-if="(top.dirs || []).length" class="poster-grid" :style="colsStyle(cols)">
              <!-- 目录也做成海报卡：只写名字会退化成一片灰块，与首页观感差太远。
                   封面取「目录里第一个影视」的刮削结果（dirPosterOf），
                   不是目录名去搜——分类名搜出来的是不相干的片子。 -->
              <button
                v-for="d in top.dirs || []"
                :key="d.path"
                class="group block w-full text-left"
                @click="open(top.id, d.path, d.name)"
              >
                <div class="relative aspect-[2/3] overflow-hidden rounded-lg bg-elevated">
                  <img
                    v-if="dirPosterOf(d, top.id)"
                    :src="dirPosterOf(d, top.id)"
                    :alt="d.name"
                    class="h-full w-full object-cover transition duration-300 group-hover:brightness-110"
                    loading="lazy"
                    @error="onPosterError(dirKey(top.id, d.path), $event)"
                  />
                  <!-- 没刮到图时不再只给一个灰图标：露出片名，至少能看出"这是哪一部、
                       到底刮到没有"。灰块 + 图标扫过去是一模一样的。 -->
                  <div
                    v-else
                    class="flex h-full w-full flex-col items-center justify-center gap-2 bg-field px-3 text-center text-ink-3"
                  >
                    <svg class="h-8 w-8 opacity-40" viewBox="0 0 24 24" fill="currentColor">
                      <path d="M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z" />
                    </svg>
                    <span class="line-clamp-3 text-[length:var(--fs-mini)] leading-snug">{{ d.name }}</span>
                  </div>
                  <span class="poster-badge font-medium">目录</span>
                  <div
                    class="absolute inset-0 grid place-items-center bg-field opacity-0 transition group-hover:opacity-100"
                  >
                    <span class="grid h-12 w-12 place-items-center rounded-full bg-accent/90 text-on-accent shadow-lg">
                      <svg class="ml-0.5 h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="m9 18 6-6-6-6" />
                      </svg>
                    </span>
                  </div>
                  <div
                    class="pointer-events-none absolute inset-x-0 bottom-0 h-16 bg-gradient-to-t from-black/80 to-transparent"
                  />
                </div>
                <div class="mt-2 px-0.5">
                  <div class="poster-title truncate font-medium text-ink group-hover:text-accent">
                    {{ d.name }}
                  </div>
                  <div class="mt-0.5 truncate text-[11px] text-ink-3">目录</div>
                </div>
              </button>
            </div>
          </div>
          <p v-if="!(overview?.tops || []).length && !error" class="mt-16 text-center text-sm text-ink-3">
            片库为空。点右上角「添加片库」填一个 WebDAV 地址。
          </p>
        </template>

        <!-- 目录内：子目录 + 影片 -->
        <template v-else>
          <div v-if="dirs.length" class="poster-grid mt-5" :style="colsStyle(cols)">
            <button
              v-for="d in dirs"
              :key="d.path"
              class="group block w-full text-left"
              @click="open(currentLib, d.path, d.name)"
            >
              <div class="relative aspect-[2/3] overflow-hidden rounded-lg bg-elevated">
                <img
                  v-if="dirPosterOf(d)"
                  :src="dirPosterOf(d)"
                  :alt="d.name"
                  class="h-full w-full object-cover transition duration-300 group-hover:brightness-110"
                  loading="lazy"
                  @error="onPosterError(dirKey(currentLib, d.path), $event)"
                />
                <div
                  v-else
                  class="flex h-full w-full flex-col items-center justify-center gap-2 bg-field px-3 text-center text-ink-3"
                >
                  <svg class="h-8 w-8 opacity-40" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z" />
                  </svg>
                  <span class="line-clamp-3 text-[length:var(--fs-mini)] leading-snug">{{ d.name }}</span>
                </div>
                <span class="poster-badge font-medium">目录</span>
                <div
                  class="pointer-events-none absolute inset-x-0 bottom-0 h-16 bg-gradient-to-t from-black/80 to-transparent"
                />
              </div>
              <div class="mt-2 px-0.5">
                <div class="poster-title truncate font-medium text-ink group-hover:text-accent">
                  {{ d.name }}
                </div>
                <div class="mt-0.5 truncate text-[11px] text-ink-3">目录</div>
              </div>
            </button>
          </div>

          <!-- 影片：点开进「影片层」（详情页布局），不在这里直接播 -->
          <div v-if="groups.length" class="poster-grid mt-5" :style="colsStyle(cols)">
            <button
              v-for="g in groups"
              :key="groupKey(g)"
              class="group block w-full text-left"
              @click="openGroup(g)"
              @contextmenu.prevent="openCardMenu(g, $event.clientX, $event.clientY)"
              @pointerdown="startCardPress(g, $event)"
              @pointerup="stopCardPress"
              @pointerleave="stopCardPress"
              @pointercancel="stopCardPress"
            >
              <div class="relative aspect-[2/3] overflow-hidden rounded-lg bg-elevated">
                <img
                  v-if="groupPosterOf(g)"
                  :src="groupPosterOf(g)"
                  :alt="g.title"
                  class="h-full w-full object-cover transition duration-300 group-hover:brightness-110"
                  loading="lazy"
                  @error="onPosterError(posterKey(currentLib, scrapeTitleOf(g), g.year), $event)"
                />
                <div
                  v-else
                  class="flex h-full w-full flex-col items-center justify-center gap-2 bg-field px-3 text-center text-ink-3"
                >
                  <svg class="h-8 w-8 opacity-40" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V4h-4z" />
                  </svg>
                  <span class="line-clamp-3 text-[length:var(--fs-mini)] leading-snug">{{ g.title }}</span>
                </div>
                <span v-if="g.files.length > 1" class="poster-badge font-medium">
                  {{ g.files.length }} 集
                </span>
                <div
                  class="absolute inset-0 grid place-items-center bg-field opacity-0 transition group-hover:opacity-100"
                >
                  <span class="grid h-12 w-12 place-items-center rounded-full bg-accent/90 text-on-accent shadow-lg">
                    <svg class="ml-0.5 h-5 w-5" viewBox="0 0 24 24" fill="currentColor">
                      <path d="M8 5v14l11-7z" />
                    </svg>
                  </span>
                </div>
                <div
                  class="pointer-events-none absolute inset-x-0 bottom-0 h-16 bg-gradient-to-t from-black/80 to-transparent"
                />
              </div>
              <div class="mt-2 px-0.5">
                <div class="poster-title truncate font-medium text-ink group-hover:text-accent">
                  {{ g.title }}
                </div>
                <div class="mt-0.5 truncate text-[11px] text-ink-3">
                  {{ [g.year ? String(g.year) : '', g.files.length > 1 ? `${g.files.length} 集` : fmtSize(g.files[0]?.size || 0)].filter(Boolean).join(' · ') || '　' }}
                </div>
              </div>
            </button>
          </div>

          <p v-if="!dirs.length && !groups.length" class="mt-16 text-center text-sm text-ink-3">
            这个目录里没有视频文件。
          </p>
        </template>
      </template>
    </template>

    <!-- 卡片浮层：长按（触屏）/ 右键唤出，**贴着那张卡**出现，不做固定角标。
         错配在卡片墙上就能一眼看出来（整排《封神》），没必要先进详情页再纠错。 -->
    <template v-if="cardMenu">
      <div class="fixed inset-0 z-40" @click="closeCardMenu" @contextmenu.prevent="closeCardMenu" />
      <div
        class="fixed z-50 w-[200px] overflow-hidden rounded-lg border border-line bg-card py-1 shadow-2xl"
        :style="{ left: cardMenu.x + 'px', top: cardMenu.y + 'px' }"
      >
        <div class="truncate px-3 py-1 text-[length:var(--fs-mini)] text-ink-3">
          {{ cardMenu.g.title }}
        </div>
        <button
          class="block w-full px-3 py-2 text-left text-sm text-ink-2 transition hover:bg-card-hover hover:text-ink"
          @click="cardAct('rescrape')"
        >
          重新搜刮
        </button>
        <button
          class="block w-full px-3 py-2 text-left text-sm text-ink-2 transition hover:bg-card-hover hover:text-hot"
          @click="cardAct('unbind')"
        >
          解除绑定
        </button>
      </div>
    </template>

    <!-- 添加 / 编辑片库：原位弹框（不跳路由，关掉不刷新本页） -->
    <LibraryAddDialog
      v-if="addOpen"
      :edit-id="addEditId"
      @close="closeAdd"
      @saved="onLibSaved"
    />
  </div>
</template>
