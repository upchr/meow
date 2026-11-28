import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 2026-11-28 用户报的五条交互问题的门禁。
 *
 * 每一条都是"改回去会立刻复发"的判据，不是实现细节：
 *   1. 多仓列表在导入别的源之后不能再摆出来（会让人以为旧仓还生效）；
 *   2. 配置地址要能回溯（历史弹框）；
 *   3. 日志模块下拉必须可被遥控器聚焦（原生 select 在 TV 壳里进不去）；
 *   4. 直播横屏点画面不能再弹「分类-台」（只由底栏频道按钮打开）；
 *   5. 播放信息面板里滑动不能穿透到底层的音量/亮度手势。
 */
const ROOT = join(__dirname, '..', '..', '..', '..')
const WEB = join(ROOT, 'client', 'apps', 'web')

const read = (...p: string[]) => readFileSync(join(...p), 'utf8')

const SETTINGS = read(WEB, 'src', 'views', 'SettingsView.vue')
const LIVE = read(WEB, 'src', 'views', 'LiveView.vue')
const LOGS = read(WEB, 'src', 'views', 'LogsView.vue')
const API_TS = read(WEB, 'src', 'api', 'index.ts')
const PILL = read(WEB, 'src', 'components', 'common', 'PillSelect.vue')
const HISTORY = read(WEB, 'src', 'components', 'common', 'SourceHistoryDialog.vue')
const API_JAVA = read(ROOT, 'server', 'host', 'src', 'shared', 'ApiServer.java')

describe('配置源：多仓列表与当前配置解耦', () => {
  it('仓列表与「共 N 个仓」都受 depotDetached 约束', () => {
    // 覆盖导入新源之后，后端把缓存标成 detached，前端不能再渲染旧仓列表
    expect(SETTINGS).toContain('v-if="!depotDetached && depotEntries.length && depotIndex"')
    expect(SETTINGS).toContain('v-if="!depotDetached && depotEntries.length"')
    expect(SETTINGS).toContain('v-if="depotMsg && !depotDetached"')
  })

  it('读缓存 / 探测 / 导入三条路径都同步 detached', () => {
    expect(SETTINGS).toContain('depotDetached.value = r.detached === true')
    // 重新探测 = 用户主动要这份列表，标记作废
    expect(SETTINGS).toMatch(/depotDetached\.value = false/)
  })

  it('后端按「导入地址是否属于这份多仓」打标记，探测时清掉', () => {
    expect(API_JAVA).toContain('private static boolean depotForeign(')
    expect(API_JAVA).toContain('depotMarkDetached(depotForeign(url))')
    expect(API_JAVA).toMatch(/\.put\("detached", false\)/)
  })
})

describe('配置源：导入历史', () => {
  it('后端有历史端点且导入成功后落盘', () => {
    expect(API_JAVA).toContain('"/api/config/history".equals(path)')
    expect(API_JAVA).toContain('recordImportHistory(url,')
    // 同地址去重：历史里不该出现两条一样的
    expect(API_JAVA).toContain('if (url.equals(e.optString("url", ""))) continue;')
  })

  it('前端地址行有历史按钮，弹框回填并直接读取', () => {
    expect(SETTINGS).toContain('aria-label="导入历史"')
    expect(SETTINGS).toContain('<SourceHistoryDialog')
    expect(SETTINGS).toContain('getConfigHistory')
    // 只回填不读等于多一步点击；读取是只读动作，直接跑
    expect(SETTINGS).toMatch(/async function pickImportHistory[\s\S]{0,300}doPreviewImport\(\)/)
    expect(API_TS).toContain('export async function getConfigHistory')
    // 老后端没有端点时不能把导入流程打断
    expect(API_TS).toMatch(/getConfigHistory[\s\S]{0,400}return \{ entries: \[\] \}/)
    // 弹框必须能被遥控器选到（scope）且列表能滚（tv-scroll）
    expect(HISTORY).toContain('data-tv-scope')
    expect(HISTORY).toContain('data-tv-scroll')
  })
})

describe('日志：模块下拉必须能被遥控器聚焦', () => {
  it('不再使用原生 <select>', () => {
    // 只看模板：脚本里的注释会提到 `<select>`（说明为什么不用它）
    const tpl = LOGS.slice(LOGS.indexOf('<template>'))
    expect(tpl).not.toMatch(/<select[\s>]/)
    expect(tpl).toContain('<PillSelect')
  })

  it('PillSelect 自带 tv 作用域与焦点回收（与顶栏站点下拉同款）', () => {
    expect(PILL).toContain('data-tv-scope')
    expect(PILL).toContain('__meowTvScopeClosed')
    // 选中项打 accent 底色 + 勾，和顶栏站点下拉一致
    expect(PILL).toContain('bg-accent font-medium text-on-accent')
    expect(PILL).toContain('m5 13 4 4L19 7')
  })
})

describe('直播：点画面不再弹「分类-台」', () => {
  it('横屏单击只切换底栏，不调 openGuide', () => {
    const i = LIVE.indexOf('function onStageClick()')
    expect(i).toBeGreaterThan(-1)
    const body = LIVE.slice(i, i + 1800)
    expect(body).not.toContain('openGuide()')
    expect(body).toContain('hideCtrl()')
    expect(body).toContain('wake()')
  })

  it('遥控器 OK 键那条路径保留（面板仍可打开）', () => {
    const i = LIVE.indexOf('function onKeydown(')
    expect(i).toBeGreaterThan(-1)
    expect(LIVE.slice(i, i + 6000)).toContain('openGuide()')
  })

  it('播放信息面板把 pointer 事件截住，手势不穿透到底层', () => {
    expect(LIVE).toContain('data-debug-panel')
    expect(LIVE).toContain('@pointerdown.stop')
    expect(LIVE).toContain('@pointermove.stop')
    expect(LIVE).toContain('@wheel.stop')
  })

  it('舞台带 touch-action: none（否则竖滑手势会被内核当成滚动取消）', () => {
    // 2026-11-28 .76 实测：同一条滑动手势时灵时不灵，根因就是缺这条
    const i = LIVE.indexOf('ref="stage"')
    expect(i).toBeGreaterThan(-1)
    expect(LIVE.slice(i, i + 300)).toContain('touch-action: none')
  })
})

describe('直播「分类-台」面板：点击语义对齐心智', () => {
  it('点正在播的台只关面板，不重开流', () => {
    const i = LIVE.indexOf('async function pickGuideChannel(')
    expect(i).toBeGreaterThan(-1)
    const body = LIVE.slice(i, i + 700)
    expect(body).toContain('showGuide.value = false')
    // 命中"同一个台"时要在调 guideOk 之前返回
    expect(body.indexOf('return')).toBeLessThan(body.indexOf('await guideOk()'))
    // 模板必须走 pickGuideChannel，不能再写成内联的重播
    expect(LIVE).toContain('@click="pickGuideChannel(i)"')
  })

  it('点当前分类不重置右列光标', () => {
    const i = LIVE.indexOf('function pickGuideGroup(')
    expect(i).toBeGreaterThan(-1)
    const body = LIVE.slice(i, i + 400)
    expect(body).toMatch(/if \(i !== guideGroup\.value\)/)
    expect(LIVE).toContain('@click="pickGuideGroup(gi)"')
  })
})
