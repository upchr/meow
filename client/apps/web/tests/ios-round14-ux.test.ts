/**
 * 2026-11-26 第三次真机反馈：
 *
 *  1. 设置页胶囊跳转要"一次到位"：改成瞬时滚动（平滑滚动会被 spy/校正打断）
 *  2. 横屏→竖屏首页胶囊消失：iPad 宽度 >639 时胶囊条原本不吸顶
 *  3. 直播默认源是死的（连接被拒）：默认优先内置每日更新源
 */
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const WEB = join(__dirname, '..')
const read = (p: string) => readFileSync(p, 'utf8').replace(/\r\n/g, '\n')

const settings = read(join(WEB, 'src', 'views', 'SettingsView.vue'))
const homeCss = read(join(WEB, 'src', 'styles', 'tokens.css'))
const live = read(join(WEB, 'src', 'views', 'LiveView.vue'))

describe('直播底栏入口（非 TV）', () => {
  it('手机横屏也有控制栏，频道按钮在控制栏里开面板', () => {
    // 2026-11-28：底栏并入 meow-ctrl-bar（与点播播放器同构），点画面 = 显隐，
    // 「频道」按钮是打开分类-台面板的唯一入口；横竖屏都有（判据不再看方向）。
    expect(live).toContain('class="meow-ctrl-bar')
    expect(live).toContain('@click.stop="openGuide"')
    expect(live).toContain('title="频道列表"')
  })
})

describe('设置页胶囊跳转', () => {
  it('跳转用瞬时滚动，不再有 smooth 动画被打断', () => {
    const fn = settings.slice(settings.indexOf('function jumpToEl('),
      settings.indexOf('function stickyTopOffset('))
    expect(fn).toContain("behavior: 'auto'")
    expect(fn).not.toContain("behavior: 'smooth'")
    expect(fn).toContain('requestAnimationFrame(apply)')
    expect(fn).toContain('window.setTimeout(apply, 260)')
    // 让位高度按**实际遮挡底边**算（顶栏 / 吸顶胶囊 rect），不再用 CSS 变量相加；
    // 竖屏真机上「--header-h + 胶囊高度」量出来仍偏低，标题会被盖住。
    expect(settings).toContain('return Math.max(headerBottom, navBottom) + 24')
    expect(settings).toContain("document.querySelector<HTMLElement>('.app-header')")
  })
})

describe('首页胶囊吸顶（iOS/iPad）', () => {
  it('iOS 壳在任意宽度下都 sticky', () => {
    expect(homeCss).toContain("html[data-shell='ios'] .cat-chips")
    const block = homeCss.slice(homeCss.indexOf("html[data-shell='ios'] .cat-chips"))
    expect(block.slice(0, 300)).toContain('position: sticky')
    expect(block.slice(0, 300)).toContain('top: var(--header-h)')
  })
})

describe('直播默认源', () => {
  it('没有上次记录时优先内置「公共直播源（每日更新）」', () => {
    expect(live).toContain('function orderedLives(')
    const fn = live.slice(live.indexOf('function isBuiltinSource('),
      live.indexOf('/** 上次用的那条源'))
    expect(fn).toContain('公共直播源')
    expect(fn).toContain('guovin')
    // 有成功记录时按源得分排序
    expect(live).toContain('function srcScoreOf(')
    expect(live).toContain('bumpSrcScore(currentLineSrc(), 1)')
    expect(live).toContain('bumpSrcScore(currentLineSrc(), -1)')
    // 合并时按这个顺序组装（线路顺序 = 源顺序）
    const merged = live.slice(live.indexOf('async function refreshMerged('))
    expect(merged.slice(0, 300)).toContain('orderedLives(playlists.value.slice())')
    // 死线路少等：看门狗 6s → 4.5s
    expect(live).toContain('const BAD_CHANNEL_MS = 4500')
  })
})
