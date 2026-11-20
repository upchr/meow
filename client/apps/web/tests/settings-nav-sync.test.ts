import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 设置页「左侧导航 ↔ 右侧分区」一致性。
 *
 * 踩过两次，都是肉眼很难发现的：
 *  1. 导航表把 sync 写在 offline 前面，模板里却是 offline 在前。滚动高亮是
 *     按导航表顺序逐项判断"顶部越过探针"的，顺序错了就出现"滚到 WebDAV
 *     那一段，左栏亮的却是离线缓存"。用户报的是"WebDAV 没有左侧快捷菜单"。
 *  2. 导航叫「跨端同步」而分区标题叫「WebDAV 同步」——同一个东西两个名字，
 *     在左栏按名字找不到。
 *
 * 这类问题 typecheck 和单测都过（纯字符串和顺序），只能在源码层面锁住。
 */
const SRC = join(__dirname, '..', 'src')
const SETTINGS = join(SRC, 'views', 'SettingsView.vue')

interface NavItem {
  id: string
  label: string
  desktopOnly: boolean
  tvOnly: boolean
  /** 原生壳才有（PC/TV），纯 Web 隐藏 */
  nativeOnly: boolean
}

function readSettings(): string {
  return readFileSync(SETTINGS, 'utf8')
}

/** 解出 NAV_ITEMS 常量表（源码里的字面量，用正则读够用且不引 vue 编译器）。 */
function parseNav(src: string): NavItem[] {
  const start = src.indexOf('const NAV_ITEMS = [')
  expect(start, '找不到 NAV_ITEMS，测试需要同步更新').toBeGreaterThan(-1)
  const end = src.indexOf('] as const', start)
  expect(end, 'NAV_ITEMS 结尾标记变了').toBeGreaterThan(start)
  const body = src.slice(start, end)
  const items: NavItem[] = []
  const re = /\{\s*id:\s*'([^']+)'\s*,\s*label:\s*'([^']+)'([^}]*)\}/g
  let m: RegExpExecArray | null
  while ((m = re.exec(body))) {
    items.push({
      id: m[1],
      label: m[2],
      desktopOnly: /desktopOnly:\s*true/.test(m[3]),
      tvOnly: /tvOnly:\s*true/.test(m[3]),
      nativeOnly: /nativeOnly:\s*true/.test(m[3]),
    })
  }
  return items
}

interface Section {
  id: string
  title: string
}

/** 解出模板里 <section data-sec="x"> 与其 <h2 class="set-title"> 的先后序列。 */
function parseSections(src: string): Section[] {
  const out: Section[] = []
  // 只看 <template> 之后的部分：脚本里有 `section[data-sec="${id}"]` 这种
  // 模板字符串，会被同一个正则命中（${id} 不是真实分区）。
  const tplAt = src.indexOf('<template>')
  expect(tplAt, '找不到 <template>').toBeGreaterThan(-1)
  const tpl = src.slice(tplAt)
  const secRe = /data-sec="([^"]+)"/g
  const positions: Array<{ id: string; at: number }> = []
  let m: RegExpExecArray | null
  while ((m = secRe.exec(tpl))) {
    // 跳过 HTML 注释里的示例
    const lineStart = tpl.lastIndexOf('\n', m.index) + 1
    const line = tpl.slice(lineStart, tpl.indexOf('\n', m.index))
    if (line.trimStart().startsWith('<!--')) continue
    positions.push({ id: m[1], at: m.index })
  }
  for (let i = 0; i < positions.length; i++) {
    const from = positions[i].at
    const to = i + 1 < positions.length ? positions[i + 1].at : tpl.length
    const chunk = tpl.slice(from, to)
    const t = /<h2 class="set-title">([^<]+)<\/h2>/.exec(chunk)
    out.push({ id: positions[i].id, title: t ? t[1].trim() : '' })
  }
  return out
}

describe('设置页左侧导航与右侧分区一一对应', () => {
  it('导航 id 顺序与模板 section 顺序完全一致', () => {
    const src = readSettings()
    // 按桌面 + TV 实际能出现的分区取全集（desktopOnly / tvOnly 只在对应端渲染，
    // 但源码里都在同一位置，顺序检查对两侧都成立）
    const navIds = parseNav(src).map((i) => i.id)
    const secIds = parseSections(src).map((s) => s.id)
    expect(secIds, '有 section 没进导航，或顺序不一致').toEqual(navIds)
  })

  it('每个导航项的名字与该分区标题一字不差', () => {
    const src = readSettings()
    const nav = parseNav(src)
    const sections = parseSections(src)
    const titleOf = new Map(sections.map((s) => [s.id, s.title]))
    const mismatched: string[] = []
    for (const item of nav) {
      const title = titleOf.get(item.id)
      expect(title, `分区 ${item.id} 没有 set-title`).toBeTruthy()
      if (title !== item.label) mismatched.push(`${item.id}: 导航「${item.label}」/ 分区「${title}」`)
    }
    expect(mismatched, `左侧导航名与分区标题不一致：\n${mismatched.join('\n')}`).toEqual([])
  })

  it('每个分区都能被点到（模板里确实渲染了这个 data-sec）', () => {
    const src = readSettings()
    const nav = parseNav(src)
    const secIds = new Set(parseSections(src).map((s) => s.id))
    const orphan = nav.filter((i) => !secIds.has(i.id)).map((i) => i.id)
    expect(orphan, `导航里有这些项，但模板里找不到对应 section：${orphan.join(', ')}`).toEqual([])
  })
})

describe('设置页不再重复提供片库管理', () => {
  it('没有「本地片库」分区与导航项', () => {
    const src = readSettings()
    expect(parseNav(src).some((i) => i.label === '本地片库')).toBe(false)
    expect(parseSections(src).some((s) => s.id === 'library')).toBe(false)
    expect(src).not.toContain('set-title">本地片库')
  })

  it('不再调用片库的保存/测试接口（那是片库页的职责）', () => {
    const src = readSettings()
    expect(src).not.toMatch(/\bsaveLibrary\b/)
    expect(src).not.toMatch(/\btestLibrary\b/)
    expect(src).not.toMatch(/\bfetchLibrary\b/)
  })
})

describe('片库页自己带增删改入口', () => {
  const LIB_VIEW = readFileSync(join(SRC, 'views', 'LibraryView.vue'), 'utf8')
  const LIB_ADD = readFileSync(
    join(SRC, 'components', 'library', 'LibraryAddDialog.vue'), 'utf8'
  )

  it('片库页有添加 / 编辑 / 删除', () => {
    expect(LIB_VIEW).toContain('添加片库')
    expect(LIB_VIEW).toContain('goEdit')
    expect(LIB_VIEW).toMatch(/@click="removeLib\(/)
    expect(LIB_VIEW).toMatch(/saveLibrary/)
  })

  it('编辑复用同一个弹框（edit-id），不另起一套表单', () => {
    // 2026-11-18 起添加/编辑改成片库页原位弹框，不再走 /library/add 路由
    expect(LIB_VIEW).toContain('addEditId.value = id')
    expect(LIB_VIEW).toContain(':edit-id="addEditId"')
    expect(LIB_ADD).toContain('editId?: string')
    // 编辑时必须保留原 id：换 id 等于新增，旧条目还留在配置里
    expect(LIB_ADD).toMatch(/const id = editId\.value \|\|/)
  })

  it('编辑时密码留空不覆盖已有密码', () => {
    // 空串绝不能进 item.pass：后端把"带空 pass"与"不带 pass"都当不改，
    // 但前端这里若把空串发出去，换到别的后端实现就会清空密码。
    expect(LIB_ADD).toMatch(/if \(form\.value\.pass\) item\.pass = form\.value\.pass/)
    expect(LIB_ADD).toContain('留空 = 不修改')
  })
})

describe('片库页层级与面包屑', () => {
  const LIB_VIEW = readFileSync(join(SRC, 'views', 'LibraryView.vue'), 'utf8')

  it('openReplace 不再压栈（否则返回后出现「A / B / B」并且退不出去）', () => {
    const start = LIB_VIEW.indexOf('async function openReplace')
    expect(start, 'openReplace 改了名字，测试需要同步').toBeGreaterThan(-1)
    const body = LIB_VIEW.slice(start, LIB_VIEW.indexOf('\n}', start))
    expect(
      body.includes('trail.value = [...trail.value'),
      'openReplace 又压栈了：调用方（返回/刷新）已经把 trail 摆好，再压会重复',
    ).toBe(false)
  })

  it('返回上一层时地址栏跟着退（否则刷新会回到子目录）', () => {
    const start = LIB_VIEW.indexOf('function back()')
    const body = LIB_VIEW.slice(start, LIB_VIEW.indexOf('\nasync function openReplace', start))
    expect(body).toMatch(/router\.replace\(\{\s*query:\s*\{\s*lib:\s*t\.lib/)
  })

  it('片库与缓存是两个页签，不是上下堆叠', () => {
    // 片库页签的文案是动态的：后端在本机叫「本地片库」，连远程后端叫「服务器片库」
    // （base 是后端那台机器上的路径，写死"本地"会让 TV/PC 用户以为是自己设备）
    expect(LIB_VIEW).toContain("label: libTabLabel")
    expect(LIB_VIEW).toContain("backendIsLocal() ? '本地片库' : '服务器片库'")
    expect(LIB_VIEW).toContain("label: '我的缓存'")
    // CachePanel 必须受 tab 控制（v-if），不能无条件渲染
    expect(LIB_VIEW).toMatch(/<CachePanel v-if="tab === 'cache'"/)
  })

  it('目录层用海报网格（poster-grid），不再是小灰块列表', () => {
    expect(LIB_VIEW).toMatch(/class="poster-grid[^"]*"\s*:style="colsStyle\(cols\)"/)
    expect(LIB_VIEW).toContain('useCardCols')
  })

  it('影片层有选集网格与立即播放（对齐详情页）', () => {
    expect(LIB_VIEW).toContain('立即播放')
    expect(LIB_VIEW).toMatch(/v-for="f in activeGroup\.files"/)
  })
})

describe('片库影片层的刮削绑定操作', () => {
  const LIB_VIEW = readFileSync(join(SRC, 'views', 'LibraryView.vue'), 'utf8')
  const LIB_API = readFileSync(join(SRC, 'api', 'library.ts'), 'utf8')

  it('影片层同时有「重新搜刮」和「解除绑定」', () => {
    expect(LIB_VIEW).toContain('重新搜刮')
    expect(LIB_VIEW).toContain('解除绑定')
    expect(LIB_VIEW).toMatch(/@click="rescrapeActive"/)
    expect(LIB_VIEW).toMatch(/@click="unbindActive"/)
  })

  it('重新搜刮必须跳过缓存（否则命中旧结果，看起来像没反应）', () => {
    // 详情页按钮 rescrapeActive 只是转调 rescrapeGroup（卡片浮层也用同一个），
    // 真正打外网的是 rescrapeGroup —— 断言必须盯它
    const start = LIB_VIEW.indexOf('async function rescrapeGroup')
    expect(start, 'rescrapeGroup 改名了，测试需同步').toBeGreaterThan(-1)
    const body = LIB_VIEW.slice(start, LIB_VIEW.indexOf('\n}', start))
    // 第 3 个实参必须是 true（refresh）。后面可能还有 lib（缓存 key 的库维度），
    // 所以不能要求 `true)` 收尾——用「,true 之后是 , 或 )」。
    expect(body, 'scrapeOne 少了 refresh 参数，会直接吃缓存').toMatch(
      /scrapeOne\([^)]*,\s*true\s*[,)]/,
    )
    // 前端 API 侧也要把 refresh 落成 query
    expect(LIB_API).toMatch(/if \(refresh\) q\.set\('refresh', '1'\)/)
  })

  it('解除绑定后清掉「这个位置刮过了」，否则再进目录不会重刮', () => {
    const start = LIB_VIEW.indexOf('async function unbindGroup')
    expect(start, 'unbindGroup 改名了，测试需同步').toBeGreaterThan(-1)
    const body = LIB_VIEW.slice(start, LIB_VIEW.indexOf('\n}', start))
    expect(body).toContain('scrapedFor.value = ')
  })

  it('解除绑定只删本地海报 + 调 DELETE，不做整表清空', () => {
    // 走的是 unbindScrape（带 title 的 DELETE），不是 scrape 的整表清理
    expect(LIB_VIEW).toMatch(/unbindScrape\(/)
    expect(LIB_API).toMatch(/method: 'DELETE'/)
  })

  it('绑定状态是可见的（已绑定海报 / 未绑定）', () => {
    expect(LIB_VIEW).toContain('已绑定海报')
    expect(LIB_VIEW).toContain('未绑定')
    expect(LIB_VIEW).toContain('boundPoster')
  })

  it('换一部片时清掉上一条搜刮提示', () => {
    const start = LIB_VIEW.indexOf('function openGroup')
    const body = LIB_VIEW.slice(start, LIB_VIEW.indexOf('\n}', start))
    expect(body).toMatch(/scrapeMsg\.value = ''/)
  })
})
