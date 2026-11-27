import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 播放页「换集不退出全屏」的源码门禁（2026-11-09）。
 *
 * ## 用户报的现象
 *
 * PC 全屏播放时，一集结束自动播下一集会**退出全屏**（标题栏/任务栏回来），
 * 之后也回不到全屏。
 *
 * ## 真因
 *
 * `PlayView.vue` 模板第一条是 `v-if="loading"`，而换集/换线都会走 `load()`
 * → `loading=true` → 整页被 spinner 替换 → `VideoPlayer` 整个卸载。
 * WebView2 的 HTML 全屏由 Tauri 运行时翻译成窗口全屏：全屏元素离开 DOM
 * 时运行时会立刻把窗口退回窗口态；重建后 `requestFullscreen` 没有用户手势
 * 必被拒，只能进「CSS 撑满」的伪全屏 —— 窗口却回不到全屏。
 *
 * ## 锁住四件事
 *
 * 1. 整页 loading 只在「还没有任何可播地址」时出现；
 * 2. 加载浮层放在播放器子树里（真全屏才渲染后代，挂兄弟节点会被藏掉）；
 * 3. 换集/换线前显式暂停旧视频（播放器不再卸载，不会自己停）；
 * 4. PC 伪全屏路径补窗口级全屏（无手势恢复）。
 */
const ROOT = join(__dirname, '..', '..', '..', '..')

function read(rel: string): string {
  return readFileSync(join(ROOT, rel), 'utf8')
}

describe('播放页换集保持全屏', () => {
  const play = read('client/apps/web/src/views/PlayView.vue')
  const player = read('client/apps/web/src/components/player/VideoPlayer.vue')

  it('整页 loading 不能无条件显示（换集时播放器必须留在 DOM 里）', () => {
    /*
     * 2026-11-27：iOS 原生渲染模式（native=1）下画面由端内 VLCKit 出，
     * web 里没有 <video>，整页 loading 同样不该出现 → 条件多了
     * !iosNativeLayer。语义没变：仍然要求「还没有可播地址」。
     */
    expect(play).toContain('v-if="loading && !src && !iosNativeLayer"')
    // 换集途中 play.value 可能是尚未解析的加密串；这个分支也必须只在
    // 还没有可播地址时出现，否则一样会把播放器卸载。
    expect(play).toContain('v-else-if="needsParser && !src"')
    // 旧写法：任何 loading 都替换整页 —— 一旦回退，PC 全屏会再次丢失
    expect(play).not.toContain('<div v-if="loading" class="grid h-full place-items-center">')
  })

  it('加载浮层在 #overlay 插槽里（真全屏只渲染全屏元素的后代）', () => {
    const slot = play.indexOf('<template #overlay>')
    expect(slot, '找不到播放器的 #overlay 插槽').toBeGreaterThan(-1)
    const block = play.slice(slot, slot + 1200)
    expect(block).toContain('v-if="loading"')
    expect(block).toContain('{{ loadingText }}')
  })

  it('换集/换线前先暂停旧视频', () => {
    const switchEp = play.slice(play.indexOf('function switchEpisode'))
    expect(switchEp.slice(0, 900)).toContain('player.value?.pause()')
    const switchLine = play.slice(play.indexOf('function switchLine'))
    expect(switchLine.slice(0, 900)).toContain('player.value?.pause()')
  })

  it('src 只前进不回退，出错时才清空', () => {
    expect(play).toContain('const playableSrc = computed')
    expect(play).toContain('const src = ref(\'\')')
    // 解析中的加密串不能把 src 变成空串
    const watchBlock = play.slice(play.indexOf('watch(\n  playableSrc'))
    expect(watchBlock.slice(0, 400)).toContain('if (v) src.value = v')
    // 错误时必须清空，否则重试会先挂载失败的地址
    const errWatch = play.slice(play.indexOf('watch(error, (v)'))
    expect(errWatch.slice(0, 500)).toContain('src.value = \'\'')
  })

  it('PC 伪全屏路径恢复窗口级全屏，并在退出/卸载时还原', () => {
    expect(player).toContain('desktop_set_fullscreen')
    const enter = player.slice(player.indexOf('async function enterPseudoFs'))
    expect(enter.slice(0, 700)).toContain('setWindowFullscreen(true)')
    const exit = player.slice(player.indexOf('async function exitPseudoFs'))
    expect(exit.slice(0, 400)).toContain('setWindowFullscreen(false)')
    const unmount = player.slice(player.indexOf('onBeforeUnmount(() => {'))
    expect(unmount.slice(0, 500)).toContain('setWindowFullscreen(false)')
  })

  it('Tauri 侧注册了窗口全屏命令', () => {
    const lib = read('client/apps/desktop/src-tauri/src/lib.rs')
    expect(lib).toContain('fn desktop_set_fullscreen')
    expect(lib).toContain('desktop_set_fullscreen,')
  })
})
