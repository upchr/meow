import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 直播播放页必须遵守「播放页生命周期契约」。
 *
 * 起因（2026-11-06 用户连报）：直播页复刻了点播播放页的**结构**（路由级满屏、
 * 底栏、面板），却没有复刻**协议**，于是每漏一条就冒一个 bug，而且都是
 * 用户先发现的：
 *   · AI 悬浮球在直播页显示（点播靠 `!isPlay` 藏）
 *   · 离开直播页后伪全屏状态不复位 → 首页 AI 悬浮球也消失
 *   · 返回链直接置 null → 把 App 的兜底一起抹掉
 *   · 缺 stopMedia → 离开页面后旧 <video> 可能继续出声
 *
 * 这条门禁把点播（VideoPlayer，所有端验证过）的清理协议固化成静态检查：
 * 以后改直播页漏任何一条，CI 先红灯，而不是等用户发现。
 *
 * 只做字符串存在性检查 —— 语义正确性仍要靠实机冒烟（smoke/）。
 */
const ROOT = join(__dirname, '..', '..', '..', '..')
const LIVE = join(ROOT, 'client', 'apps', 'web', 'src', 'views', 'LiveView.vue')
const APP = join(ROOT, 'client', 'apps', 'web', 'src', 'App.vue')
const AI = join(ROOT, 'client', 'apps', 'web', 'src', 'components', 'ai', 'AiPanel.vue')

describe('直播播放页：路由级协议对齐点播', () => {
  const app = readFileSync(APP, 'utf8')
  const ai = readFileSync(AI, 'utf8')

  it('直播路由不渲染顶栏（与 isPlay 同款）', () => {
    expect(app, 'App.vue 的 AppHeader 缺少 isLive 判断').toContain('!isPlay && !isLive')
  })

  it('直播页的 AI 悬浮球不显示（与 isPlay 同款）', () => {
    expect(ai, 'AiPanel 的 visible 缺少 isLive 判断').toContain("route.name === 'live'")
    expect(ai, 'AiPanel 的 visible 没用 isLive').toMatch(/!isPlay\.value\s*&&\s*!isLive\.value/)
  })
})

describe('直播播放页：卸载清理协议对齐点播', () => {
  const src = readFileSync(LIVE, 'utf8')

  it('卸载时交还方向键', () => {
    expect(src, '缺少 __meowTvKeyOwner = false').toMatch(/__meowTvKeyOwner[^\n]*=\s*false/)
  })

  it('卸载时复位伪全屏事件（否则首页 AI 悬浮球永久消失）', () => {
    expect(src, '缺少 meow:pseudofs 复位').toMatch(/meow:pseudofs[^\n]*detail:\s*false/)
  })

  it('返回链只撤自己那层（不能直接置 null，会抹掉 App 的兜底）', () => {
    expect(src, '缺少 __meowOwn 标记').toContain('__meowOwn')
    expect(src, '没把上一任返回链还回去').toContain('prevConsumeBack')
    // 反例：直接 `__meowTvConsumeBack = null` 等于抹掉别人的 handler
    expect(src, '不允许把返回链直接置 null').not.toMatch(/__meowTvConsumeBack\s*=\s*null/)
  })

  it('卸载时显式停媒体（Chromium 摘掉节点不保证停播）', () => {
    expect(src, '缺少 stopMedia').toMatch(/function stopMedia\(/)
    expect(src, '卸载没调 stopMedia').toMatch(/stopMedia\(\)[\s\S]{0,80}destroyHls\(\)/)
  })

  it('菜单键钩子按判等注销（不误删别人的）', () => {
    expect(src).toMatch(/__meowTvMenuHook\s*===\s*handleTvMenuHere/)
  })
})

describe('直播播放页：频道面板导航（页面接管 + 列内夹停）', () => {
  const src = readFileSync(LIVE, 'utf8')

  it('面板内不得有原生可聚焦元素（否则 tv-nav 抢走方向键，页面收不到）', () => {
    // tv-nav 的 FOCUSABLE = 'a[href],button,input,select,textarea,[tabindex]:not([tabindex="-1"])'
    // 面板里只要有一个 button，tv-nav 就会把焦点框画上去，然后
    // `if (!markedEl())` 不成立 → 方向键不再派发给页面 → 页面导航是死代码。
    // 所以面板项一律 div role="button"。
    const i = src.indexOf('<Teleport')
    const j = src.indexOf('</Teleport>')
    expect(i, '找不到 Teleport').toBeGreaterThan(-1)
    expect(j, '找不到 Teleport 结束').toBeGreaterThan(i)
    const seg = src.slice(i, j)
    const bad = [/<button/g, /<input/g, /<select/g, /<textarea/g, /<a\s+href/g]
    for (const re of bad) {
      expect(re.test(seg), `面板内出现原生可聚焦元素：${re}`).toBe(false)
    }
    expect(seg, '面板项应用 role="button"').toContain('role="button"')
  })

  it('页面接管方向键：面板项有 data-guide-col / data-guide-idx（滚动跟随要用）', () => {
    expect(src).toContain('data-guide-col="group"')
    expect(src).toContain('data-guide-col="channel"')
    expect(src).toContain('data-guide-idx')
  })

  it('↑↓ 只动当前列（信号源/分类/台三列），←→ 才切列', () => {
    // 2026-11-12：顶部「信号源」也是可导航的一列（分类首条再 ↑ 进入）
    expect(src, '缺少 guideColumn 状态').toMatch(
      /guideColumn = ref<'source' \| 'group' \| 'channel'>/,
    )
    expect(src, '缺少列内夹停的 guideMoveChannel').toContain('function guideMoveChannel')
    expect(src, '缺少列内夹停的 guideMoveGroup').toContain('function guideMoveGroup')
    expect(src, '缺少信号源列移动').toContain('function guideMoveSource')
    expect(src, '缺少跨列的 guideSwitchColumn').toContain('function guideSwitchColumn')
    expect(src).toContain('data-guide-col="source"')
  })

  it('台列表按台名去重（同台多线路不占列表项）', () => {
    expect(src, '缺少去重 channels 实现').toMatch(/const channels = computed<ChannelItem\[\]>/)
    expect(src, '去重要按 name 合并 urls').toContain('byName')
  })

  it('面板声明 role="dialog" 但不用 data-tv-scope', () => {
    // role="dialog" → App.vue 的 onTvBack 认它是模态，返回键先关面板而不是退出直播页。
    // data-tv-scope 也能达到同样效果，但会触发 tv-nav 的 armScopeIdle：
    // 面板开着 20 秒不按键就被自动派发 Escape 关掉（看电视找台时很常见）。
    const seg = src.slice(src.indexOf('<Teleport'), src.indexOf('</Teleport>'))
    expect(seg, '面板根缺 role="dialog"').toMatch(/v-if="showGuide"[\s\S]{0,200}role="dialog"/)
    expect(seg, '面板不能用 data-tv-scope（会被 20 秒自动关）').not.toMatch(
      /v-if="showGuide"[\s\S]{0,200}data-tv-scope/,
    )
  })

  it('面板打开时控制栏不渲染（底栏按钮会让 tv-nav 抢走方向键）', () => {
    // 横屏本来就没有底栏；竖屏底栏一直在，它不是 FOCUSABLE 之外的东西——
    // 只要它在，tv-nav 就有候选元素、会画焦点框，然后 `if (!markedEl())`
    // 不成立、键不再派发给页面 → 面板按键全失效。
    // 2026-11-28：底栏并入 meow-ctrl-bar（点画面 = showCtrl 显隐），面板
    // 打开态从旧 v-if 条件里挪到整条控制栏的 v-if="!showGuide"。
    expect(src, '控制栏 v-if 必须排除面板打开态').toContain('v-if="!showGuide"')
    expect(src, '控制栏显隐必须由 showCtrl 驱动（点画面呼出）').toContain(
      `:class="showCtrl ? 'opacity-100' : 'pointer-events-none invisible opacity-0'"`,
    )
  })

  it('面板光标环抄全站焦点环规格（内白线 + 外扩 accent 环 + 辉光）', () => {
    // 用 ring-2 ring-white，或纯 inset 的 accent 环时，用户反馈"没有聚焦样式"：
    // 面板打开时光标默认停在**正在播的台**上，那行是 bg-accent 实心主题色，
    // accent 内环叠同色底 = 看不见。tv-nav 靠外扩环在深色背景上解决这件事，
    // 所以这里必须是外扩（不是 inset 版）——这是全站唯一验证过的形态。
    expect(src, '光标项应使用 guide-cursor').toContain("'guide-cursor'")
    expect(src, '缺少 .guide-cursor 样式').toMatch(/\.guide-cursor\s*\{/)
    expect(src, '缺少内沿 1px 白线').toMatch(/inset 0 0 0 1px rgba\(255, 255, 255/)
    expect(src, '外环必须是 0 0 0 2px（不能 inset，实心 accent 上看不见）').toMatch(
      /0 0 0 2px var\(--color-accent/,
    )
    expect(src, '缺少 accent 辉光').toMatch(/0 0 12px 2px var\(--color-accent/)
  })

  it('光标环会伸出元素，列表内边距必须留够（否则被 overflow 裁掉一条边）', () => {
    // 外环 2px + 辉光 12px ≈ 14px；px-4 / py-4 = 16px 留 2px 余量。
    // 只够静态边距还不够：scrollIntoView 会把首尾行贴到滚动端口边缘，
    // 所以还要 scroll-padding-block: 16px 把内边距留出来。
    expect(src, '列表容器内边距太小会裁掉焦点框').toContain(
      'guide-scroll min-h-0 flex-1 overflow-y-auto px-4 py-4',
    )
    expect(src, '缺少滚动对齐边距').toContain('scroll-padding-block: 16px')
  })
})

describe('直播播放页：全屏协议', () => {
  const src = readFileSync(LIVE, 'utf8')

  it('真全屏优先、失败降级伪全屏（与点播同款两条路）', () => {
    expect(src, '缺少 requestFullscreen').toContain('requestFullscreen')
    expect(src, '缺少 pseudoFs 降级').toContain('pseudoFs')
  })

  it('根容器 fixed inset-0（h-full 在 min-h-full 链上会塌成 0）', () => {
    expect(src, '根容器必须是 fixed inset-0').toMatch(/fixed inset-0/)
    // 反例：直播根用 h-full 时 MuMu 实测 main h=640 而根 h=0，画面只有声音
    expect(src, '根容器不能用 h-full 撑高').not.toMatch(
      /<div class="relative h-full w-full overflow-hidden bg-black"/,
    )
  })
})

describe('直播播放页：旋转控制对齐点播', () => {
  const src = readFileSync(LIVE, 'utf8')

  it('触屏进页面尝试横屏（与点播 tryLandscape 同款）', () => {
    expect(src, '缺少 tryLandscape').toMatch(/async function tryLandscape\(/)
    expect(src, 'onMounted 没调 tryLandscape').toMatch(/void tryLandscape\(\)/)
    // 只对触屏生效，否则 PC 双击全屏会被转走
    expect(src, '缺少 isTouch 判定').toContain('(pointer: coarse)')
  })

  it('壳侧转屏走 meowTv.setOrientation（WebView 里 lock() 必失败）', () => {
    expect(src, '缺少 shellSetOrientation').toMatch(/function shellSetOrientation\(/)
    expect(src, '没有调壳的 setOrientation').toContain("b.setOrientation(mode)")
    // 标准 API 只能"试一下"，失败静默
    expect(src, '缺少 lockLandscapeQuietly').toMatch(/async function lockLandscapeQuietly\(/)
    expect(src).toContain("so.lock('landscape')")
  })

  it('全屏进出都同步方向（进转横屏、退还原竖屏）', () => {
    expect(src, '缺 syncShellOrientation').toMatch(/function syncShellOrientation\(full: boolean\)/)
    // toggleFullscreen 里两条路（真全屏 / 伪全屏）都要转，退出要还原
    const fn = src.slice(src.indexOf('async function toggleFullscreen'), src.indexOf('async function toggleFullscreen') + 1600)
    expect(fn, '进全屏没转横屏').toContain('syncShellOrientation(true)')
    expect(fn, '退全屏没还原').toContain('syncShellOrientation(false)')
    expect(fn, '伪全屏分支漏了转屏').toMatch(/catch[\s\S]{0,300}syncShellOrientation\(true\)/)
  })

  it('离开直播页还原方向（否则整个应用留在横屏）', () => {
    expect(src, '卸载没还原方向').toMatch(
      /onBeforeUnmount[\s\S]{0,2200}if \(rotatedByFs \|\| pageLandscape\)/,
    )
    expect(src).toMatch(/shellSetOrientation\('portrait'\)/)
  })

  it('竖屏兜底提示：提示 + 尝试横屏 + 竖屏继续', () => {
    expect(src, '缺少 portraitHint').toContain('const portraitHint = ref(false)')
    expect(src, '提示里缺"尝试横屏"按钮').toContain('尝试横屏')
    expect(src, '提示里缺"竖屏继续"').toContain('竖屏继续')
    // 提示不能铺满整块盖住底栏（点播踩过：铺满后按钮点不动）
    expect(src).toMatch(/v-if="portraitHint"[\s\S]{0,120}pointer-events-none absolute inset-0/)
  })

  it('旋转时底栏形态跟着重算（syncLandscape + checkOrientation 同一个监听）', () => {
    expect(src, 'resize 没挂 onWindowResize').toContain("addEventListener('resize', onWindowResize)")
    expect(src).toMatch(/function onWindowResize\(\)[\s\S]{0,120}syncLandscape\(\)[\s\S]{0,120}checkOrientation\(\)/)
  })
})

describe('直播播放页：全屏退出按钮（手机唯一出口）', () => {
  const src = readFileSync(LIVE, 'utf8')

  it('判据复用点播那套：触屏 + 紧凑视口（大屏 TV 不给）', () => {
    // 用户口径：大屏 TV 不显示（遥控器有返回键、几何选焦会误触），
    // 竖屏设备切换成横屏的正常显示。
    expect(src, '缺少 compactViewport').toMatch(/function syncCompactViewport\(/)
    expect(src, '阈值必须是 480（与点播同款）').toContain('Math.min(window.innerWidth, window.innerHeight) < 480')
    expect(src, '缺少 showExitFsButton').toMatch(/const showExitFsButton = computed/)
    // 三要素：触屏、非 TV 或紧凑视口（=手机）
    expect(src).toMatch(/isTouch\.value[\s\S]{0,120}!isTvEnv\(\) \|\| compactViewport\.value/)
  })

  it('★ 不能用 landscape 判据（手机全屏后已转成横屏，按钮会被藏掉）', () => {
    const i = src.indexOf('const showExitFsButton')
    // 只取这个 computed 自己的表达式：原来写死 700 字符，后面新增的
    // showLiveBack（里面合法地用了 landscape）会落进窗口，误报成回归。
    const seg = src.slice(i, src.indexOf('/**', i))
    expect(seg, 'showExitFsButton 不能用 landscape 判定').not.toContain('landscape')
  })

  it('控制栏唤出时隐藏，且点击就是退出全屏', () => {
    expect(src, '按钮要和底栏互斥').toMatch(/showExitFsButton = computed\([\s\S]{0,300}!showCtrl\.value/)
    expect(src, '按钮点击应调 toggleFullscreen').toMatch(/title="退出全屏"[\s\S]{0,120}toggleFullscreen/)
  })
})

describe('直播播放页：播放交互（缓冲/音量/亮度/调试/画中画）', () => {
  const src = readFileSync(LIVE, 'utf8')

  it('缓冲态：waiting 置位、playing 复位、换源立刻置位', () => {
    // 缺这一条的症状：换台后黑屏 2–5 秒，用户以为按了没反应
    expect(src, '缺少 buffering 状态').toContain('const buffering = ref(false)')
    expect(src, 'video 没接 waiting').toMatch(/@waiting="onVideoWaiting"/)
    expect(src, 'video 没接 playing 复位').toMatch(/@playing="onVideoPlaying"/)
    expect(src, '切源没立刻置位').toMatch(/function switchUrl[\s\S]{0,300}buffering\.value = true/)
    expect(src, '缺少转圈 UI').toMatch(/v-if="buffering && !error"/)
  })

  it('竖滑手势分左右区：左 1/3 亮度、右 1/3 音量，中间留给单击', () => {
    expect(src, '缺少 pointermove 处理').toMatch(/function onStagePointerMove/)
    expect(src, '没接 pointermove').toMatch(/@pointermove="onStagePointerMove"/)
    expect(src, '分区判据缺失').toMatch(/pressStart\.x < third[\s\S]{0,80}brightness/)
    expect(src, '右区应是音量').toMatch(/pressStart\.x > w - third[\s\S]{0,40}volume/)
    // 必须走系统级：元素音量退出应用就没了
    expect(src, '音量没走系统桥').toContain('setSystemVolume(pct)')
    expect(src, '亮度没走系统桥').toContain('setSystemBrightness(pct)')
    // 拿不到系统亮度时只能压暗，要明说
    expect(src, '缺少 cssDim 兜底').toMatch(/const cssDim = ref\(0\)/)
    expect(src, '缺少滑动浮层').toMatch(/v-if="swipeHint"/)
  })

  it('触屏：单击只切控制栏（按点击前状态决定），双击立即暂停/播放', () => {
    // 这条是点播播放器（VideoPlayer）的行为，不是直播页
    const player = readFileSync(
      join(ROOT, 'client', 'apps', 'web', 'src', 'components', 'player', 'VideoPlayer.vue'),
      'utf8',
    )
    const i = player.indexOf('function onStageClick')
    const body = player.slice(i, i + 5200)
    /*
     * 关键判据：这个 click 之后还会冒泡到根节点触发 onWrapClick 的 wake()，
     * 所以必须记下**点击前**的可见状态 —— 否则 220ms 后无条件 toggle 会把
     * 刚被撑开的控制栏又收起来（用户报的「点一下闪一下就没」）。
     *
     * 2026-11-16 加严：click 到达时的 showCtrl 会被浏览器在 pointerup 后补发的
     * 合成 mousemove 提前改成 true（MuMu 实测 222ms 后收起），所以快照必须在
     * pointerdown 时取；这里锁住"用 pressVisible 快照，且不再直接读 showCtrl"。
     */
    expect(body, '要记下点击前的可见状态').toContain('pressVisible')
    expect(body, '合成 mousemove 不能污染快照').toMatch(
      /Date\.now\(\)\s*-\s*pressAt\s*<\s*PRESS_SNAPSHOT_MS/,
    )
    expect(body, '不能退回直接读 showCtrl').not.toContain(
      'const wasVisible = showCtrl.value',
    )
    expect(body, '显示中才收起').toContain('if (wasVisible) hideControlBar()')
    expect(body, '收起态只需续期').toContain('else wake()')
    expect(body, '单击要延迟 220ms 判双击').toContain('}, 220)')
    const d = player.indexOf('function onStageDblClick')
    const dbl = player.slice(d, d + 1600)
    // 双击动作已抽到 touchDoubleTapAction（2026-11-25 iOS 点击级兜底共用）
    expect(dbl, '双击要交给统一动作').toContain('touchDoubleTapAction(e)')
    const t = player.indexOf('function touchDoubleTapAction')
    const tap = player.slice(t, t + 900)
    expect(tap, '中区双击要立即 toggle').toContain('toggle()')
  })

  it('触屏长按：左右 2× 快退/快进，中间无动作（不再是长按倍速）', () => {
    const player = readFileSync(
      join(ROOT, 'client', 'apps', 'web', 'src', 'components', 'player', 'VideoPlayer.vue'),
      'utf8',
    )
    const i = player.indexOf('function onStagePointerDown')
    const body = player.slice(i, i + 1400)
    expect(body, '中间区不响应长按').toContain("if (zone === 'mid') return")
    expect(body, '左右复用按键扫描').toContain("startKeyScan(zone === 'left' ? -1 : 1)")
    expect(player, '长按倍速的旧实现要删干净').not.toContain('boosting')
  })

  it('滑动后补发的 click 不能当成暂停', () => {
    expect(src, '缺少 suppressClickUntil').toMatch(/let suppressClickUntil = 0/)
    expect(src, 'onStageClick 没先判抑制').toMatch(
      /function onStageClick\(\)[\s\S]{0,160}suppressClickUntil/,
    )
  })

  it('滚轮调音量（PC/Web）：节流 + deltaMode 归一化', () => {
    expect(src, '缺少 onStageWheel').toMatch(/function onStageWheel/)
    expect(src, '没接 wheel').toMatch(/@wheel="onStageWheel"/)
    expect(src, '缺少节流').toMatch(/wheelThrottleAt < 40/)
    expect(src, '缺少 deltaMode 归一化').toMatch(/e\.deltaMode === 1 \? 3/)
  })

  it('调试面板：三层快照 + __playerDebug + D 键 + 电视菜单键入口', () => {
    expect(src, '缺少 collectDebug').toMatch(/function collectDebug\(\)/)
    expect(src, '没挂 __playerDebug').toContain('__playerDebug')
    expect(src, '缺少分片耗时日志').toMatch(/Hls\.Events\.FRAG_LOADED/)
    expect(src, '键盘缺 D 键').toMatch(/case 'd':[\s\S]{0,80}toggleDebug/)
    // 电视遥控器没有 D 键：横屏下菜单键给调试面板
    expect(src, '电视上缺入口').toMatch(
      /if \(landscape\.value\) \{\s*toggleDebug\(\)/,
    )
    expect(src, '卸载没收调试轮询').toMatch(/clearInterval\(debugTimer\)/)
    // ref 会把快照包成深度响应式 Proxy，JSON.stringify 读出来嵌套对象全变 {}
    // （MuMu 实测：biz/state 明明有字段，MCP 读到的却是空对象）
    expect(src, 'window 上必须挂原始副本').toMatch(
      /__playerDebug = snap \? JSON\.parse\(JSON\.stringify\(snap\)\)/,
    )
  })

  it('画中画：不支持就不渲染按钮', () => {
    expect(src, '缺少 togglePiP').toMatch(/async function togglePiP/)
    expect(src).toContain('requestPictureInPicture')
    expect(src, '缺 pipSupported 判据').toMatch(/const pipSupported = computed/)
    expect(src, '按钮必须按支持情况渲染').toMatch(/v-if="pipSupported"/)
  })

  it('换台后恢复用户音量（stopMedia 会静音）', () => {
    expect(src, '换源没补回音量').toMatch(/const p = getPrefs\(\)[\s\S]{0,160}v\.muted = p\.muted/)
  })
})

describe('直播页：空态 / 错误态的出口', () => {
  const src = readFileSync(LIVE, 'utf8')

  it('空态和错误态有「返回」（顶栏被 isLive 藏了，屏幕上原本没有出口）', () => {
    expect(src, '缺少 leaveLive').toMatch(/function leaveLive\(\)/)
    expect(src, '没有返回按钮').toMatch(
      /v-if="!current && !showGuide"[\s\S]{0,400}title="返回"/,
    )
    expect(src, '返回没接 leaveLive').toMatch(/title="返回"[\s\S]{0,200}leaveLive/)
  })

  it('错误态有「去设置」（文案让用户去设置，就得有入口）', () => {
    expect(src, '缺少去设置按钮').toContain('去设置')
    // 至少两处：空态 + 错误态（两处文案都提到设置页）
    expect(
      (src.match(/router\.push\('\/settings'\)/g) || []).length,
      '去设置按钮少于两个',
    ).toBeGreaterThanOrEqual(2)
  })

  it('★ 没有台在播时方向键必须还给 tv-nav（否则遥控器选不中按钮）', () => {
    // keyOwner 按 current 动态设
    expect(src, 'keyOwner 没跟 current 走').toMatch(
      /watch\(\s*current[\s\S]{0,220}__meowTvKeyOwner\s*=\s*!!c/,
    )
    // 页面 onKeydown 在空态要让位
    expect(src, 'onKeydown 空态没让位').toMatch(
      /function onKeydown\(e: KeyboardEvent\)[\s\S]{0,600}isTvEnv\(\) && !current\.value\) return/,
    )
  })
})

describe('直播页：多源合并与持久化缓存', () => {
  const src = readFileSync(LIVE, 'utf8')
  const merge = readFileSync(
    join(ROOT, 'client', 'apps', 'web', 'src', 'api', 'liveMerge.ts'),
    'utf8',
  )

  it('多源合并：按配置顺序组装（分类归属不能被并行完成顺序打乱）', () => {
    expect(src, '没有合并开关').toMatch(/const mergeOn = ref\(getPrefs\(\)\.liveMerge/)
    expect(src, '没有渐进合并').toMatch(/async function refreshMerged\(/)
    // 关键：lives.map 决定顺序，不能按 got 的插入顺序
    expect(src, 'applyMerged 没按配置顺序组装').toMatch(
      /const ordered = lives\.map\(\(p\) => got\.get\(p\.url\)\)/,
    )
    // 每回来一条就应用一次（渐进，不等 Promise.all）
    expect(src, '没有渐进应用').toMatch(/got\.set\(p\.url[\s\S]{0,120}applyMerged\(/)
  })

  it('合并刷新后同步正在播的台的线路（否则 ←→ 换不到新线路）', () => {
    expect(src, '缺少 syncCurrentUrls').toMatch(/function syncCurrentUrls\(/)
    expect(src, 'applyMerged 没调 syncCurrentUrls').toMatch(
      /function applyMerged[\s\S]{0,900}syncCurrentUrls\(\)/,
    )
    // 正在播的线路不在新列表里时要保留（否则 currentUrlIndex = -1）
    expect(src, '丢了对当前线路的兜底').toMatch(/parsed\.unshift\(old\)/)
  })

  it('持久化缓存：冷启动 0 秒（内存 → 磁盘 → 转圈 三级）', () => {
    expect(src, '缺少磁盘缓存键').toMatch(/const DISK_KEY = 'meow\.live\.disk/)
    expect(src, '缺少 TTL').toMatch(/const DISK_TTL = 6 \* 3600_000/)
    expect(src, '读缓存没接进 load').toMatch(/else if \(!force && readDiskCache\(\)\)/)
    expect(src, '写缓存没接进 snapshotCache').toMatch(
      /function snapshotCache\(\)[\s\S]{0,400}saveDiskCache\(\)/,
    )
  })

  it('记住上次用的源（合并时决定线路优先顺序）', () => {
    expect(src, '缺少 SRC_KEY').toMatch(/const SRC_KEY = 'meow\.live\.source'/)
    expect(src, 'openPlaylist 没记源').toMatch(
      /async function openPlaylist[\s\S]{0,300}setItem\(SRC_KEY/,
    )
    // 排序改成 orderedLives：无成功记录时读上次选的源，有记录按源得分
    expect(src, '合并排序要读上次的源').toMatch(
      /function orderedLives[\s\S]{0,900}lastSourceUrl\(\)/,
    )
  })

  it('面板显示线路来源（台行的「N 线」看不出分布）', () => {
    expect(src, '缺少 guideSrcHint').toMatch(/const guideSrcHint = computed/)
    expect(src, '面板没渲染来源提示').toMatch(/v-if="guideSrcHint"/)
  })

  it('归一化不误并：CCTV-5+ / CCTV-4K 保持独立', () => {
    // 这两个是真实存在且容易写错的边界（去掉 4k 会把 CCTV-4K 并进 CCTV）
    expect(merge, '没排除 4k/8k').not.toMatch(/replace\([^)]*4k\|8k/)
    expect(merge).toContain('fhd|uhd|hd|sd')
  })

  it('新加了源之后进直播页必须重新合并（缓存命中也要刷）', () => {
    /*
     * 踩过（2026-11-11 用户加了国家源但在直播页看不到）：判据原来是
     * `!groups.value.length`，而进页面时磁盘/会话缓存已经把 groups 填满
     * → 条件恒假 → 后台合并永远不跑。连面板「刷新」也救不回来
     * （force 只重拉 /api/live，不重拉 m3u）。
     */
    expect(src, '还在用缓存内容当合并门槛').not.toMatch(
      /if \(!groups\.value\.length && playlists\.value\.length\)/,
    )
    expect(src, '没有记录本次聚合结果').toMatch(
      /aggHasGroups = next\.groups\.length > 0/,
    )
    expect(src, '合并门槛没改成聚合结果').toMatch(
      /if \(!aggHasGroups && playlists\.value\.length\)/,
    )
  })

  it('★ 坏线路先换线路、全试完才换台（多源的 6 条线路不能浪费）', () => {
    const i = src.indexOf('function armBadChannelWatchdog')
    const fn = src.slice(i, i + 2600)
    expect(fn, '没有台内换线').toMatch(/lineTryCount < cur\.urls\.length - 1/)
    expect(fn, '换线后没重新计时').toMatch(
      /switchUrl\(cur\.urls\[nextLine\]\.url\)[\s\S]{0,140}armBadChannelWatchdog\(\)/,
    )
    // 换线计数必须和跳台计数分开，否则整组很快被误判"全坏"
    expect(src, '缺少 lineTryCount').toMatch(/let lineTryCount = 0/)
    expect(src, 'playChannel 没重置换线计数').toMatch(
      /async function playChannel[\s\S]{0,320}lineTryCount = 0/,
    )
  })
})
