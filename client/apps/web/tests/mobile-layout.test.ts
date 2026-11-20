import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 移动端布局契约（2026-11-05）。
 *
 * 背景：手机（390×844）上历史/详情/设置三页和顶栏是"照 PC 缩一缩"的老样子：
 *  · 顶栏站点名被搜索框挤成 0 宽，只剩一个箭头；
 *  · 历史卡片的「详情 / 移除」只靠 hover 出现，触屏永远够不到；
 *  · 详情页海报撑满 390px 宽、2:3 后高 500+，播放按钮在首屏之外；
 *  · 设置页缓存目录那行 369px 的 Windows 路径把整页撑到 412px，可横向拖动。
 *
 * 约束：PC/TV 不能受影响。TV 的 CSS 视口实测 1138×640（docs/27 §4.1），
 * 所以移动端一律写 `max-sm:` 或 `@media (max-width: 639px)`，**不要**把
 * 桌面样式改写成 `sm:` 前缀反向覆盖（那种写法会把 ≥640 的默认值也带上）。
 *
 * 这里锁的是"哪个类还在"，不是像素值 —— 改布局时如果确实要动，连测试一起改。
 */
const SRC = join(__dirname, '..', 'src')
const TOKENS = join(SRC, 'styles', 'tokens.css')

function read(rel: string): string {
  return readFileSync(join(SRC, rel), 'utf8')
}

describe('移动端布局：顶栏', () => {
  const header = read('components/layout/AppHeader.vue')
  const tokens = readFileSync(TOKENS, 'utf8')

  it('手机高度 3.5rem 且扣掉 iOS 安全区，--header-h 用同一份算式', () => {
    const mobile = tokens.slice(tokens.indexOf('@media (max-width: 639px)'))
    expect(mobile).toContain('--header-h: calc(3.5rem + env(safe-area-inset-top))')
    expect(mobile).toContain('.app-header')
    expect(mobile).toContain('padding-top: env(safe-area-inset-top)')
  })

  it('手机不显示顶栏输入框，改成搜索图标入口（否则站点名会被挤没）', () => {
    expect(header).toContain('max-sm:hidden')
    expect(header).toMatch(/aria-label="搜索"/)
    expect(header).toMatch(/sm:hidden[^]*?aria-label="搜索"|aria-label="搜索"[^]*?sm:hidden/)
  })

  it('站点名有独立宽度上限，菜单项触屏高度 ≥ 44px', () => {
    expect(header).toContain('max-sm:max-w-[6.5rem]')
    expect(header).toContain('max-sm:py-3')
  })
})

describe('移动端布局：历史页', () => {
  const history = read('views/HistoryView.vue')

  it('手机是 3 列（与首页密度一致），不是 2 列大卡', () => {
    /*
     * 2026-11-17：从写死断点（sm:3 md:4 lg:6）改成与首页/收藏同一套
     * useCardCols —— 原来手机横屏落 md 档只有 4 列，卡比首页大一圈。
     * 现在列数由「目标卡宽 + 矮屏加列」算：手机竖屏实测 3 列、横屏 6 列。
     */
    expect(history).toContain('useCardCols')
    expect(history).toContain('colsStyle(cols)')
    expect(history).toContain('class="poster-grid')
    expect(history).not.toContain('grid grid-cols-2 gap-x-4')
    // 不能再出现写死列数的历史写法（会与首页脱钩）
    expect(history).not.toContain('md:grid-cols-4 lg:grid-cols-6')
  })

  it('「详情 / 移除」在触屏上常显 —— 没有 hover 也必须点得到', () => {
    expect(history).toContain('max-sm:opacity-100')
    // 移除按钮带确认：触屏上它是常显的，误触概率比 hover 高。
    // ★ 2026-11-12 改成"一律确认"：`(hover: none)` 是主指针设备的属性，
    //   模拟器/带鼠标的盒子会让它翻转，曾出现"同一点法有时弹框有时直接删"。
    expect(history).toContain('await confirmDialog(')
    expect(history).not.toContain("matchMedia('(hover: none)')")
  })

  it('统计数字在手机排一行，不占掉半屏', () => {
    expect(history).toContain('max-sm:grid-cols-4')
  })
})

describe('移动端布局：详情页', () => {
  const detail = read('views/DetailView.vue')

  it('海报在手机上浮动成小图，简介绕排（别整屏海报）', () => {
    expect(detail).toContain('max-sm:float-left')
    expect(detail).toContain('max-sm:w-24')
    // 父级必须是 block：flex 容器里 float 会被忽略（float 不作用于 flex item），
    // 只有 block 才出现"海报在左、简介绕排"的效果
    expect(detail).toContain('class="block sm:flex sm:flex-row')
  })

  it('选集手机 4 列', () => {
    expect(detail).toContain('max-sm:grid-cols-4')
  })
})

describe('移动端布局：收藏页', () => {
  const fav = read('views/FavoritesView.vue')

  it('取消收藏按钮触屏常显，且不再写「悬停」文案', () => {
    expect(fav).toContain('max-sm:opacity-100')
    expect(fav).not.toContain('悬停右上角')
    expect(fav).toContain('右上角可取消收藏')
    // 与历史页同口径：一律确认，不再用 `(hover: none)` 当判据
    expect(fav).toContain('await confirmDialog(')
    expect(fav).not.toContain("matchMedia('(hover: none)')")
  })

  it('远端收藏晚到会刷新列表（冷启动竞态）', () => {
    expect(fav).toContain("meow:favorites-synced")
  })
})

describe('移动端布局：片库页', () => {
  const lib = read('views/LibraryView.vue')

  it('影片层海报同样是浮动小图（与详情页一致）', () => {
    expect(lib).toContain('max-sm:float-left')
    expect(lib).toContain('max-sm:w-24')
    expect(lib).toContain('class="mt-5 block sm:flex sm:flex-row sm:gap-6"')
  })
})

describe('移动端布局：AI 浮层', () => {
  const ai = read('components/ai/AiPanel.vue')

  it('手机上贴底满宽，且用 dvh 跟随键盘（保留 vh 兜底）', () => {
    expect(ai).toContain('height: 86vh')
    expect(ai).toContain('height: 86dvh')
    expect(ai).toContain('padding-bottom: env(safe-area-inset-bottom)')
  })
})

describe('移动端导航：底部标签栏 + 顶栏搜索展开', () => {
  const app = readFileSync(join(SRC, 'App.vue'), 'utf8')
  const tab = readFileSync(join(SRC, 'components/layout/MobileTabBar.vue'), 'utf8')
  const header = read('components/layout/AppHeader.vue')
  const tokens = readFileSync(TOKENS, 'utf8')

  it('五个一级入口：首页 / 直播 / AI / 设置 / 我的，且只在手机显示', () => {
    expect(tab).toContain('to="/"')
    expect(tab).toContain('to="/live"')
    expect(tab).toContain('to="/ai"')
    expect(tab).toContain('to="/settings"')
    expect(tab).toContain('to="/me"')
    expect(tab).toContain('@media (max-width: 639px)')
    // 透明玻璃风：薄纱 token 底 + 背景模糊（底色走 token，不用 color-mix）
    expect(tab).toContain('backdrop-filter: blur(')
    expect(tab).toContain('background: var(--color-card)')
    // 悬浮胶囊，不贴底（用户明确要求）
    expect(tab).toContain('left: 0.75rem')
    expect(tab).toContain('bottom: calc(0.75rem + env(safe-area-inset-bottom))')
    expect(tab).toContain('border-radius: 1.5rem')
    // 中间 AI 球向上顶出（用户口径「异型的向上顶出的那种」）
    expect(tab).toContain('mtb-orb')
    expect(tab).toContain('top: -1.5rem')
    expect(tab).toContain('border-radius: 999px')
  })

  it('只有播放页不显示标签栏；直播页保留（它是一级入口），内容给底栏留出 6rem', () => {
    expect(app).toContain('has-tabbar')
    // 2026-11-12：直播成了一级入口，藏掉底栏会让这一格永远不亮也回不去
    // 2026-11-16：投屏接收页（ext-play）也是满屏播放，一并藏底栏
    expect(app).toContain(
      "const isPlay = computed(() => route.name === 'play' || route.name === 'extPlay')",
    )
    // 2026-11-19：登录页同样不显示底栏（公网部署，未登录时没有任何导航）
    expect(app).toContain('const showTabBar = computed(() => !isPlay.value && !isLogin.value)')
    expect(tokens).toContain('--tabbar-h: calc(6rem + env(safe-area-inset-bottom))')
    // 满屏画面（直播）只让开标签栏本体，不需要 6rem
    expect(tokens).toContain('--tabbar-clear')
    expect(tokens).toContain('.has-tabbar')
  })

  it('手机上悬浮 AI 球收起（标签栏已是一级入口，避免压住最后一行）', () => {
    expect(tokens).toMatch(/\.ai-fab \{\s*display: none;/)
  })

  it('<640 顶栏没有「…」菜单：入口全部由底栏 + 我的页承担（2026-11-12）', () => {
    // 菜单只给 640–767（那段没有底部标签栏）；<640 撤掉，不留重复入口
    expect(header).toContain('relative md:hidden max-sm:hidden" data-more-menu')
    // 手机原来靠菜单进的片库，现在在「我的」页签栏里；
    // 片库页顶部也放同一条页签（MeTabs），三个分区在哪都可见
    const me = read('views/MeView.vue')
    expect(me).toContain('<MeTabs')
    const meTabs = read('components/common/MeTabs.vue')
    expect(meTabs).toContain("router.push({ name: 'library' })")
    expect(meTabs).toContain('片库')
    const library = read('views/LibraryView.vue')
    expect(library).toContain('<MeTabs active="library" />')
    // 片库归属「我的」：底栏在这一段仍亮着（添加已是弹框，没有单独路由）
    expect(tab).toContain(
      "['me', 'history', 'favorites', 'library'].includes(String(route.name))",
    )
  })

  it('顶栏搜索在手机上是"输入框样式的按钮"，点击直达搜索页', () => {
    // 用户口径：形态要像输入框（够宽），交互不变 —— 点击直接去搜索页
    expect(header).toContain('aria-label="搜索"')
    expect(header).toContain('搜索影片…')
    expect(header).toContain('goSearch')
    expect(header).toContain("router.push({ name: 'search' })")
    // 不是真 input：真 input 会先弹键盘再跳页
    const pill = header.slice(header.indexOf('aria-label="搜索"') - 400, header.indexOf('aria-label="搜索"'))
    expect(pill).not.toContain('<input')
  })


  it('640–767px 也有直播/片库入口（顶栏是 md:flex，更多菜单必须覆盖到 md）', () => {
    /*
     * 2026-11-12 指南核验：直播/片库顶栏入口是 `hidden ... md:flex`（≥768 才显示），
     * 而「更多」菜单原是 sm:hidden（<640 才显示）→ 640–767 两处都没有入口。
     * 菜单容器必须是 md:hidden，这段宽度才落得进菜单。
     */
    expect(header).toContain('<div class="relative md:hidden max-sm:hidden" data-more-menu>')
    const menu = header.slice(header.indexOf('data-more-menu'))
    expect(menu).toContain('to="/live"')
    expect(menu).toContain('to="/library"')
  })

  it('「更多」菜单只兜底 AI 之外的入口：设置覆盖 640–767，手机交给标签栏', () => {
    /*
     * 2026-11-12 顶栏挤压修复：设在 640–767 从顶栏撤走（顶栏 md:block），
     * 由「更多」菜单兜住；<640 有底部标签栏，菜单里不再重复（hidden sm:block）。
     * AI 仍然不进菜单（手机上它是标签栏的一级入口）。
     */
    // 注意从模板里那个 div 开始切：`data-more-menu` 在 <script> 的点击外部判定里也出现过一次
    const menu = header.slice(header.indexOf('<div class="relative md:hidden max-sm:hidden" data-more-menu>'))
    expect(menu).not.toContain('To="/ai"')
    expect(menu).not.toContain('AI 助手')
    expect(menu).toContain('to="/settings"')
    expect(menu).toMatch(/class="hidden[^"]*sm:block"[\s\S]{0,80}?>\s*设置/)
  })
})

describe('移动端滚动：分类胶囊吸顶 + 搜索空闲态顺序', () => {
  const home = read('views/HomeView.vue')
  const settings = read('views/SettingsView.vue')
  const search = read('views/SearchView.vue')

  it('首页分类胶囊只在手机上吸顶（TV 的 tv-nav 把 sticky 祖先当钉住层）', () => {
    expect(home).toContain('max-sm:sticky')
    expect(home).toContain('max-sm:top-[var(--header-h)]')
    // PC/TV 必须保持 static：带 max-sm 前缀的那一行里不能再出现裸 sticky 类
    // PC/TV 必须保持 static：带 max-sm 前缀的那一行里不能出现裸 sticky 类
    const chipLine =
      home.split(String.fromCharCode(10)).find((l) => l.includes('max-sm:sticky')) || ''
    expect(chipLine.split(' ').includes('sticky')).toBe(false)
  })

  it('设置页分区胶囊吸顶，且是页面容器的直接子级（嵌在 aside 里粘不住）', () => {
    expect(settings).toContain('sticky top-[var(--header-h)]')
    const chips = settings.indexOf('aria-label="设置分区"')
    const aside = settings.indexOf('aria-label="设置分区导航"')
    expect(chips).toBeGreaterThan(-1)
    expect(aside).toBeGreaterThan(-1)
    // 手机胶囊出现在 aside 之前 = 挂在页面容器下，才不会被 aside 的高度限住
    expect(chips).toBeLessThan(aside)
  })

  it('搜索空闲态：最近搜索在热播榜之前（用户报「搜索历史沉底」）', () => {
    expect(search).toContain('max-sm:order-first')
    expect(search).toContain('max-sm:order-last')
    // 空态文案不能写"按 ←"（手机没有这个键）
    expect(search).not.toContain('按 ← 回到搜索框')
  })
})

describe('移动端滚动（续）：搜索条吸顶 / 胶囊跟随 / AI 页高度 / 片库返回', () => {
  const home = read('views/HomeView.vue')
  const search = read('views/SearchView.vue')
  const settings = read('views/SettingsView.vue')
  const ai = read('views/AiView.vue')
  const lib = read('views/LibraryView.vue')
  const libAdd = read('components/library/LibraryAddDialog.vue')

  it('首页吸顶条必须满宽：容器手机内边距 12px + 胶囊 -mx-3 抵消', () => {
    expect(home).toContain('px-6 py-6 max-sm:px-3 max-sm:py-4')
    expect(home).toContain('max-sm:-mx-3')
  })

  it('搜索页搜索框那一栏也吸顶（只在手机）', () => {
    expect(search).toContain('max-sm:sticky')
    expect(search).toContain('max-sm:top-[var(--header-h)]')
  })

  it('设置页胶囊跟随滚动：激活项带 data-chip，滚动时按 scrollLeft 居中', () => {
    expect(settings).toContain(':data-chip="i.id"')
    expect(settings).toContain('ref="chipsEl"')
    expect(settings).toContain('box.scrollTo(')
    expect(settings).toContain('watch(activeSec')
  })

  it('AI 独立页用 dvh（保留 vh 兜底），不再留出一截可滚动空白', () => {
    expect(ai).toContain('.ai-page')
    expect(ai).toContain('height: calc(100vh - var(--header-h) - var(--tabbar-h, 0px))')
    expect(ai).toContain('height: calc(100dvh - var(--header-h) - var(--tabbar-h, 0px))')
  })

  it('添加/编辑是原位弹框：不开路由、关掉不重载整页（2026-11-18）', () => {
    // 用户口径：弹框应直接显示在片库页上。跳路由会让返回时组件重建、
    // 骨架闪一下 —— 看起来像"关掉弹框把页面刷新了"。
    expect(lib).toContain('<LibraryAddDialog')
    expect(lib).not.toContain("name: 'libraryAdd'")
    expect(libAdd).toContain('data-tv-scope')
    // 保存后只换 overview，不能动 loading（那会把整页切成骨架）
    const start = lib.indexOf('async function onLibSaved')
    expect(start, 'onLibSaved 改了名字，测试需要同步').toBeGreaterThan(-1)
    const nextFn = lib.indexOf('\nasync function', start + 10)
    const body = lib.slice(start, nextFn > 0 ? nextFn : undefined)
    expect(body).not.toContain('loading.value')
  })
})

describe('移动端播放页：全屏时的返回出口', () => {
  const player = read('components/player/VideoPlayer.vue')

  it('手机形态视口才给返回键（真电视保持隐藏：遥控器有返回且会抢焦点）', () => {
    expect(player).toContain('compactViewport')
    expect(player).toContain('showBackButton')
    expect(player).toContain('Math.min(window.innerWidth, window.innerHeight) < 480')
  })

  it('返回键显隐与底部控制栏完全一致（2026-11-12 用户要求）', () => {
    // 删掉了「控制栏收起时单独浮一个返回键」：播放中左上角不该留按钮挡画面
    expect(player).not.toContain('showFloatingBack')
    // 唯一的返回键在顶栏里，而顶栏容器由 showCtrl 控制显隐
    const topBar = player.indexOf('meow-ctrl-bar absolute inset-x-0 top-0')
    expect(topBar).toBeGreaterThan(0)
    const backBtn = player.indexOf('v-if="showBackButton"', topBar)
    expect(backBtn).toBeGreaterThan(topBar)
    // 同一容器上必须还挂着 showCtrl 的显隐绑定
    const ctrlClass = player.indexOf("showCtrl ? 'opacity-100'", topBar)
    expect(ctrlClass).toBeGreaterThan(topBar)
    expect(ctrlClass).toBeLessThan(backBtn + 2000)
  })

  it('离开播放页不能只靠 history.back()（Android WebView 实测是空操作）', () => {
    expect(player).toContain('function leavePlayback')
    expect(player).toContain("router.replace(")
    // 兜底必须回详情，而不是首页（播放页几乎都从详情进来）
    expect(player).toContain("name: 'detail'")
  })
})

describe('移动端布局：设置页', () => {
  const settings = read('views/SettingsView.vue')
  const tokens = readFileSync(TOKENS, 'utf8')

  it('缓存目录这种长路径必须能断行 —— 实测它把 390 撑成 412', () => {
    expect(settings).toMatch(/cacheDir[^]*?break-all|cacheDir<\/code>/)
    expect(tokens).toContain('.set-card code')
    expect(tokens).toContain('overflow-wrap: anywhere')
  })

  it('标题行说明在手机上独立成行，输入框不再强制 16rem', () => {
    expect(tokens).toContain('flex-basis: 100%')
    expect(settings).toContain('max-sm:min-w-[10rem]')
  })

  it('带输入框的配额磁贴在手机上单列（两列时标题必被截断）', () => {
    expect(settings).toContain('max-sm:grid-cols-1')
  })
})
