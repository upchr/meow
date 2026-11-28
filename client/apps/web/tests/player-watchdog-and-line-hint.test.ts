import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 2026-11-02 第三轮：切线路文案 + 假死看门狗。
 *
 * 两条都是"用户观察到与预期不符"后查出来的：
 *   ① 线路面板写着「从第 1 集开始，进度不保留」，但代码明明是
 *      `sameEpisodeIn()` 停在当前集 + `applyResume()` 续播 —— 文案是上一版遗留
 *   ② 404 的 m3u8 在 936ms 就 loadedmetadata、readyState=1 然后假死，
 *      20 秒的看门狗要白等满，用户只看到转圈
 */
const WEB = join(__dirname, '..')
const PLAYER = readFileSync(
  join(WEB, 'src', 'components', 'player', 'VideoPlayer.vue'),
  'utf8',
)
const PLAY = readFileSync(join(WEB, 'src', 'views', 'PlayView.vue'), 'utf8')
const LIB = readFileSync(join(WEB, 'src', 'views', 'LibraryView.vue'), 'utf8')

function codeOnly(s: string): string {
  // Windows 上源码可能是 CRLF，跨行 indexOf 断言必须归一化行尾
  const lf = String.fromCharCode(10)
  const normalized = s.split(String.fromCharCode(13) + lf).join(lf)
  return normalized
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
}

describe('切线路文案必须与代码行为一致', () => {
  it('不再声称「从第 1 集开始、进度不保留」', () => {
    // 模板里（含注释外的可见文案）不该再有这句
    const visible = PLAYER.replace(/<!--[\s\S]*?-->/g, '')
    expect(visible).not.toContain('换线路会从该线路第 1 集开始')
    expect(visible).not.toContain('当前集的播放进度不保留')
  })

  it('新文案说明"尽量同一集 + 保留进度"以及真实的失效条件', () => {
    const visible = PLAYER.replace(/<!--[\s\S]*?-->/g, '')
    expect(visible).toContain('尽量停在同一集')
    expect(visible).toContain('保留播放进度')
    // 真实会退回第 1 集的两个原因要写出来
    expect(visible).toMatch(/集名不同|集数不足/)
  })

  it('代码确实停在当前集（文案的事实依据）', () => {
    const code = codeOnly(PLAY)
    const i = code.indexOf('function sameEpisodeIn')
    expect(i).toBeGreaterThan(-1)
    const body = code.slice(i, i + 500)
    // 先按集名精确匹配
    expect(body).toMatch(/e\.name === epName\.value/)
    // 再按索引
    expect(body).toContain('currentIndex.value')
  })

  it('switchLine 换线前落盘进度、不 clearProgress（踩过的坑）', () => {
    const code = codeOnly(PLAY)
    const i = code.indexOf('function switchLine')
    expect(i).toBeGreaterThan(-1)
    const body = code.slice(i, i + 1200)
    expect(body).toContain('flushToHistory(true)')
    expect(body).not.toContain('clearProgress')
  })
})

describe('假死看门狗：已有 metadata 但 currentTime 恒 0', () => {
  it('存在独立的 stallTimer，且判据是 currentTime 而不是 readyState', () => {
    const code = codeOnly(PLAYER)
    expect(code).toContain('stallTimer')
    /*
     * 2026-11-04 起判据体搬进 onStallTimeout（看门狗要能续期，
     * 不能再写成内联箭头函数——续期要能重新挂同一个回调）。
     */
    const i = code.indexOf('function onStallTimeout')
    expect(i).toBeGreaterThan(-1)
    const body = code.slice(i, i + 900)
    // 判据必须是"没进展"
    expect(body).toContain('v.currentTime > 0')
    // 必须要求已拿到 metadata（否则交给起播那条）
    expect(body).toMatch(/v\.readyState < 1/)
  })

  it('窗口不能太短（正常 4K 起播实测 14.3s）', () => {
    const code = codeOnly(PLAYER)
    /*
     * #16 起窗口是父组件可配的（按线路健康分档）：默认值必须仍是 12s，
     * 否则会误杀慢源（实测沉默的真相 4K = 14.3s 才 loadedmetadata）。
     */
    expect(code).toMatch(/onStallTimeout,\s*stallBudgetMs\(\)/)
    const f = code.indexOf('function stallBudgetMs')
    expect(f).toBeGreaterThan(-1)
    const body = code.slice(f, f + 260)
    expect(body).toContain('12_000')
    // 下限 2s：调用方传 0/垃圾值必须退回默认，不能被配成"立刻超时"
    expect(body).toContain('2_000')
  })

  it('clearStartupWatchdog 要同时清两个定时器', () => {
    const code = codeOnly(PLAYER)
    const i = code.indexOf('function clearStartupWatchdog')
    const body = code.slice(i, i + 400)
    expect(body).toContain('startupTimer')
    expect(body).toContain('stallTimer')
    expect((body.match(/clearTimeout/g) || []).length).toBeGreaterThanOrEqual(2)
  })

  it('★ failPlay 必须先撤看门狗再报错（否则 20s 后会重复 emit error）', () => {
    const code = codeOnly(PLAYER)
    const i = code.indexOf('function failPlay')
    expect(i).toBeGreaterThan(-1)
    // 2026-11-16：failPlay 增加了「已缓冲播放中不判死」守卫，emit 前多了一段，窗口放大
    const body = code.slice(i, i + 1600)
    expect(body).toContain('bufferedAheadSec(v) > 1')
    const clearIdx = body.indexOf('clearStartupWatchdog()')
    const emitIdx = body.indexOf("emit('error'")
    expect(clearIdx).toBeGreaterThan(-1)
    expect(emitIdx).toBeGreaterThan(-1)
    // 清理要在 emit 之前
    expect(clearIdx).toBeLessThan(emitIdx)
  })
})

describe('片库文案：先直连，播不了才转码', () => {
  it('不再写「MKV / HEVC 会先由后端转码」', () => {
    const visible = LIB.replace(/<!--[\s\S]*?-->/g, '')
    expect(visible).not.toContain('会先由后端转码再播')
    expect(visible).toContain('只有真的播不了才会自动转码')
  })
})

describe('4xx 快速判死：不能误杀正在播的流', () => {
  const code = codeOnly(PLAYER)

  it('★ 4xx 判断必须在非致命分支返回之前', () => {
    // hls.js 的 404 是先发非致命错误 + 指数退避重试，fatal 要 20 秒后才到。
    // 写在 fatal 之后等于永远等不到（第一版就是这个问题，真机实测 20 秒才报错）。
    const i = code.indexOf('h.on(Hls.Events.ERROR')
    expect(i).toBeGreaterThan(-1)
    const body = code.slice(i, i + 2600)
    const fastIdx = body.indexOf('httpCode >= 400')
    const fatalIdx = body.indexOf('if (!data.fatal) {')
    expect(fastIdx).toBeGreaterThan(-1)
    expect(fatalIdx).toBeGreaterThan(-1)
    expect(fastIdx).toBeLessThan(fatalIdx)
  })

  it('★ 必须要求"一个分片都没成功过"才快速判死', () => {
    const i = code.indexOf('h.on(Hls.Events.ERROR')
    /*
     * 窗口只用来界定"在同一个 ERROR 处理器里"，不是精确坐标：
     * 2026-11-28 在处理器开头加了一行（记录 fatal 与否，面板上色用），
     * 1200 就到不了 fragLoadedCount 判断了。放宽到 1600。
     */
    const body = code.slice(i, i + 1600)
    // 没有这个条件，播放中途的单个 404 会把能播的流误杀
    expect(body).toContain('fragLoadedCount === 0')
  })

  it('403 不快速判死（Referer / 时效 token 换一轮可能恢复）', () => {
    const i = code.indexOf('h.on(Hls.Events.ERROR')
    const body = code.slice(i, i + 1200)
    expect(body).toMatch(/httpCode !== 403/)
  })
})

describe('HLS 假死的判据必须用分片计数（readyState 恒 0）', () => {
  it('stallTimer 的 HLS 分支检查 fragLoadedCount', () => {
    const code = codeOnly(PLAYER)
    const i = code.indexOf('function onStallTimeout')
    const body = code.slice(i, i + 900)
    expect(body).toContain('if (hls.value)')
    expect(body).toContain('fragLoadedCount > 0')
  })

  it('fragLoadedCount 在 setupSource 归零、FRAG_LOADED 累加', () => {
    const code = codeOnly(PLAYER)
    expect((code.match(/fragLoadedCount = 0/g) || []).length).toBeGreaterThanOrEqual(1)
    expect(code).toContain('fragLoadedCount++')
  })
})
