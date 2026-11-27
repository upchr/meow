import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * iOS 端内原生兜底播放（VLCKit）的接线门禁（2026-11-27）。
 *
 * 事故背景：iOS 不能 fork/exec（`Transcode.isIos()` 禁掉 ffmpeg 子进程），
 * 而 WKWebView 的 `<video>` 只认 MP4/HLS —— 片库里的 MKV/AVI/FLV/DTS 在
 * iPhone 上就是播不了，错误页还显示「找不到 ffmpeg」。解法与 TV 的
 * `openNative` 同构：端内 VLCKit 接管，原生控制条 + 原生弹幕 + 进度回传。
 *
 * 五条不能回退的约束：
 *   1. project.yml 必须 embed MobileVLCKit（缺了运行时 dyld 直接崩）；
 *   2. web 侧的能力探测必须看注入的 `nativeVlc` 字段，不能用 UA；
 *   3. 入口必须在"转码能力预检失败"分支里**优先于**错误页；
 *   4. 原生退出必须回传 `__meowVlcClosed`，否则进度永远停在被拉起的位置；
 *   5. 弹幕参数与 TV 的 DanmakuOverlay 逐项一致（两端不能各行其是）。
 */
const ROOT = join(__dirname, '..', '..', '..', '..')
const IOS = join(ROOT, 'client', 'apps', 'ios')
const read = (...p: string[]) => readFileSync(join(...p), 'utf8')

const PROJECT = read(IOS, 'project.yml')
const PREPARE = read(IOS, 'scripts', 'prepare-vlckit.sh')
const PLAYER = read(IOS, 'Meow', 'NativeVlcPlayer.swift')
const POWER = read(IOS, 'Meow', 'PowerTimer.swift')
const BOOT = read(IOS, 'Meow', 'Bootstrap.swift')
const PLIST = read(IOS, 'Meow', 'Info.plist')
const SETTINGS = read(ROOT, 'client', 'apps', 'web', 'src', 'views', 'SettingsView.vue')
const DANMAKU = read(IOS, 'Meow', 'VlcDanmakuOverlay.swift')
const WEBVIEW = read(IOS, 'Meow', 'WebView.swift')
const BOOTSTRAP = read(IOS, 'Meow', 'Bootstrap.swift')
const BRIDGE = read(ROOT, 'client', 'apps', 'web', 'src', 'api', 'nativeBridge.ts')
const PLAY = read(ROOT, 'client', 'apps', 'web', 'src', 'views', 'PlayView.vue')
// 控制层不再是独立组件：复用 VideoPlayer 本体（2016-11 后唯一的播放器 UI）
const LAYER = read(ROOT, 'client', 'apps', 'web', 'src', 'components', 'player', 'VideoPlayer.vue')
const CI = read(ROOT, '.github', 'workflows', '_ios.yml')

describe('VLCKit 打包：framework 与备料脚本', () => {
  it('project.yml embed MobileVLCKit（动态库不 embed 就是 dyld 崩）', () => {
    expect(PROJECT).toContain('build/vlckit/MobileVLCKit.framework')
    const at = PROJECT.indexOf('build/vlckit/MobileVLCKit.framework')
    expect(PROJECT.slice(at, at + 90)).toContain('embed: true')
  })

  it('备料脚本只取 arm64 真机 slice（模拟器 slice 会让包体翻倍）', () => {
    expect(PREPARE).toContain('MobileVLCKit.xcframework')
    expect(PREPARE).toContain('ios-arm64')
    expect(PREPARE).toContain('lipo -thin arm64')
    // 下载地址可被镜像覆盖（CI 缓存/内网）
    expect(PREPARE).toContain('VLCKIT_URL')
  })

  it('CI 缓存 + 校验 MobileVLCKit 已就位', () => {
    expect(CI).toContain('prepare-vlckit.sh')
    expect(CI).toContain('ios-vlckit-')
    expect(CI).toContain('MobileVLCKit.framework missing')
  })
})

describe('原生播放器：入口 / 收尾 / 方向', () => {
  it('提供 present(from:dict:) 给桥分发', () => {
    expect(PLAYER).toContain('static func present(from webView: WKWebView?, dict: [String: Any]) -> Bool')
  })

  it('只接受 http(s)（VLC 不支持 file:// / 相对路径）', () => {
    expect(PLAYER).toMatch(/scheme == "http" \|\| scheme == "https"/)
  })

  it('退出回传 __meowVlcClosed（进度/时长/是否播完）', () => {
    expect(PLAYER).toContain('__meowVlcClosed')
    expect(PLAYER).toContain('positionSec')
    expect(PLAYER).toContain('ended')
  })

  it('进出都走 ShellOrientation（WKWebView 的 lock 在非全屏一律拒绝）', () => {
    expect(PLAYER).toContain('ShellOrientation.apply("landscape")')
    expect(PLAYER).toContain('ShellOrientation.apply("portrait")')
  })

  it('不手动 setActive 音频会话（1s 自动暂停那个坑）', () => {
    expect(PLAYER).not.toContain('setActive(true)')
  })

  it('源站防盗链：UA/Referer 透传给 VLC', () => {
    expect(PLAYER).toContain(':http-user-agent=')
    expect(PLAYER).toContain(':http-referrer=')
  })
})

describe('原生弹幕：参数与 TV / 网页渲染器对齐', () => {
  it('常量一致（静态 4s / 追赶窗 1.2s / 车道间隔 350ms / seek 阈值 2.5s）', () => {
    expect(DANMAKU).toContain('STATIC_MS: Int = 4_000')
    expect(DANMAKU).toContain('SPAWN_WINDOW_MS: Int = 1_200')
    expect(DANMAKU).toContain('LANE_GAP_MS: Int = 350')
    expect(DANMAKU).toContain('SEEK_JUMP_MS: Int = 2_500')
  })

  it('字号/车道与网页 DanmakuRenderer 同一套公式（22pt × 补偿，车道 = 字号+10）', () => {
    /*
     * 2026-11-27 用户实报"点播页弹幕很高、片库这个就很低"：
     * 旧公式 h/32 + 车道×1.5 + 预留顶底各 2 行 → 片库只剩 6 行、占屏不到一半。
     * 现在与 danmaku.ts 的 fontSize/laneHeight/lanes 完全一致。
     */
    expect(DANMAKU).toContain('22 * scale * narrow * short')
    expect(DANMAKU).toContain('min(40, max(12,')
    expect(DANMAKU).toContain('laneH = textSize.rounded(.up) + 10')
    expect(DANMAKU).toContain('Int(h * areaRatio / laneH)')
    // 不再预留顶/底行（网页的静态弹幕是动态找空位）
    expect(DANMAKU).not.toContain('topRows')
    expect(DANMAKU).toContain('private func staticLane(')
  })

  it('解析 B 站 XML（p=time,mode,size,color）', () => {
    expect(DANMAKU).toContain('<d p=\\"([^\\"]*)\\"')
    expect(DANMAKU).toContain('split(separator: ","')
  })

  it('暂停时冻结（位置不前进就不推进位移）', () => {
    expect(DANMAKU).toContain('max(0, pos - a.bornPosMs)')
  })
})

describe('web 桥：feature detect 与入口优先级', () => {
  it('iOS 桥字段 nativeVlc + openNativeVlc 由壳注入', () => {
    expect(BOOTSTRAP).toContain('window.meowIos.nativeVlc=true')
    expect(BOOTSTRAP).toContain('window.meowIos.openNativeVlc=function(o)')
  })

  it('WebView 分发 nativeVlc 到 NativeVlcPlayer', () => {
    expect(WEBVIEW).toContain('case "nativeVlc"')
    expect(WEBVIEW).toContain('NativeVlcPlayer.present(from: message.webView, dict: dict)')
  })

  it('能力探测看注入字段，不看 UA（Safari 打开网页版必须是 web 壳）', () => {
    const at = BRIDGE.indexOf('export function hasIosNativePlayer')
    expect(at).toBeGreaterThan(0)
    const body = BRIDGE.slice(at, at + 260)
    expect(body).toContain('nativeVlc === true')
    expect(body).not.toContain('userAgent')
  })

  it('openIosNative 只收 http(s)，返回 false 时调用方继续走 web 路径', () => {
    const at = BRIDGE.indexOf('export function openIosNative')
    const body = BRIDGE.slice(at, at + 900)
    expect(body).toContain('/^https?:/i.test(url)')
    expect(body).toContain('return false')
  })

  it('转码能力预检失败时**先**试原生，再给"本端不支持"', () => {
    // 两处（tryTranscode / tryLibraryTranscode）都要这个顺序
    const hits = PLAY.match(/if \(!repair && tryIosNative\(\)\) return/g) || []
    expect(hits.length).toBe(2)
    // 而且必须排在 handleTranscodeUnavailable 之前
    const at = PLAY.indexOf('if (!repair && tryIosNative()) return')
    expect(PLAY.slice(at, at + 160)).toContain('handleTranscodeUnavailable(repair)')
  })

  it('原生关闭回写历史进度（否则从原生回来历史停在进页位置）', () => {
    expect(PLAY).toContain('onIosNativeClosed')
    expect(PLAY).toMatch(/saveProgress\(\s*\{ site, vod_id: vodId \}/)
  })

  it('片库地址必须绝对化（VLC 在原生层拿不到 location.origin）', () => {
    const at = PLAY.indexOf('function tryIosNative')
    const body = PLAY.slice(at, at + 900)
    expect(body).toContain('absoluteApi(u)')
  })
})

/**
 * 透明 WebView 控制层（2026-11-27）。
 *
 * 为什么要改成这样：原生播放器（VLCKit）只负责**画面**，控制层用透明
 * WKWebView 加载同一路由的播放页（native=1）。这样图一那套控件/弹幕/选集/
 * 线路全部复用现有组件，不必在 Swift 里复刻一遍 —— 复刻等于永久维护两套 UI。
 *
 * 关键不变量：
 *   1. 控制层是**透明**的（isOpaque=false），否则会把 VLC 画面盖住；
 *   2. 控制层地址 = 父页地址 + native=1（复用同一组 site/vod/ep 参数）；
 *   3. 控制层是另一个 WebView → apiBase 必须显式带过去；
 *   4. native 模式下 web 播放链整体不渲染（不创建 <video>），且不能再拉起原生；
 *   5. 状态由原生每 250ms 推，操作由 web 走 nativeCmd 发回。
 */
describe('透明 WebView 控制层', () => {
  it('原生侧：控制层是透明 WKWebView，注入 native 标记与命令桥', () => {
    expect(PLAYER).toContain('private var controlWebView: WKWebView?')
    expect(PLAYER).toContain('window.__meowNativeLayer = true')
    expect(PLAYER).toContain('window.meowIos.nativeCmd = function(op, value)')
    expect(PLAYER).toContain('w.isOpaque = false')
    expect(PLAYER).toContain('w.scrollView.isScrollEnabled = false')
  })

  it('原生侧：状态每 tick 推给控制层，命令走 WKScriptMessageHandler', () => {
    expect(PLAYER).toContain('window.__meowNativeState={t:')
    expect(PLAYER).toContain('WKScriptMessageHandler')
    for (const op of ['"play"', '"pause"', '"seek"', '"rate"', '"open"', '"close"', '"orientation"']) {
      expect(PLAYER).toContain(`case ${op}:`)
    }
    expect(PLAYER).toContain('pushState()')
    expect(PLAYER).toContain('let every = controlBarVisible ? 1 : 4')
  })

  it('原生侧：控制层地址 = 父页地址 + native=1（复用同一组参数）', () => {
    expect(PLAYER).toContain('controlURL: Self.controlURL(from: webView?.url)')
    expect(PLAYER).toContain('&native=1')
  })

  it('原生侧：apiBase 要带过去（控制层是另一个 WebView，没有宿主注入的桥）', () => {
    expect(PLAYER).toContain('window.meowIos.apiBase =')
    expect(PLAYER).toContain('apiBase: dict["apiBase"] as? String')
  })

  it('web 侧：状态读取与命令发送的接口', () => {
    expect(BRIDGE).toContain('export function isIosNativeLayer()')
    expect(BRIDGE).toContain('export function readIosNativeState()')
    expect(BRIDGE).toContain('export function iosNativeCmd(')
    // 换片要走带 header 的封装（换线路时防盗链头可能不同）
    expect(BRIDGE).toContain('export function iosNativeOpen(')
    expect(BRIDGE).toContain("iosNativeCmd('open', {")
    expect(BRIDGE).toContain('headers: encodeHeaders(header),')
  })

  it('web 侧：控制层组件复用弹幕引擎与解析器（不另写一套）', () => {
    expect(LAYER).toContain('DanmakuRenderer')
    expect(LAYER).toContain('parseDanmakuXml')
    expect(LAYER).toContain('readIosNativeState()')
    expect(LAYER).toContain('iosNativeCmd(')
  })

  it('web 侧：控制层提供图一那套控件', () => {
    expect(LAYER).toContain('选集')
    expect(LAYER).toContain('线路')
    expect(LAYER).toContain('倍速')
    expect(LAYER).toContain('全屏')
    // 弹幕开关（复用播放器自己的 dmOn）
    expect(LAYER).toContain('dmOn')
    // 进度条拖动 → seek
    expect(LAYER).toContain("iosNativeCmd('seek'")
  })

  it('PlayView：native 模式不渲染 web 播放链，只渲染控制层', () => {
    expect(PLAY).toContain('const iosNativeLayer = ref(isIosNativeLayer())')
    expect(PLAY).toContain('v-else-if="iosNativeLayer || src"')
    expect(PLAY).toContain('v-if="loading && !src && !iosNativeLayer"')
    // 复用同一个 VideoPlayer：画面由原生出，src 传空
    expect(PLAY).toContain(":src=")
  })

  it('PlayView：native 层不能再拉起原生（防一层套一层）', () => {
    expect(PLAY).toContain('if (iosNativeLayer.value) return false')
  })

  it('PlayView：native 层的选集/换线走既有路由链路，load() 只交地址', () => {
    /*
     * 2026-11-27 真机实报「选集/换线路点了没反应」：.mkv 在原生层里仍会命中
     * 「容器不支持 → 转码」分支，而 iOS 的转码预检必然失败，play.value 永远
     * 不被赋值 → watch(play.url) 收不到新地址。原生层必须跳过能力判断。
     */
    const at = PLAY.indexOf('if (isLibraryPlay.value) {')
    const body = PLAY.slice(at, at + 1600)
    expect(body).toContain('if (iosNativeLayer.value) {')
    expect(body).toContain('play.value = { url: libraryDirect.value } as PlayResult')
    // 换片仍然由监听 play.url 的那一处统一发 open（带这一集的 header）
    expect(PLAY).toContain('iosNativeOpen(absoluteApi(u), play.value?.header, nativeSubUrl())')
    expect(PLAY).toContain('if (!iosNativeLayer.value || !u) return')
    // 原生侧两种 value 形态都收：纯 URL（老协议）与 {url, headers}
    expect(PLAYER).toContain('if let d = value as? [String: Any] {')
    expect(PLAYER).toContain('private static func parseHeaders(')
  })
})

/**
 * 控制层真机修复（2026-11-27 第二轮）。
 *
 * 真机反馈三件事：① 报错页盖在画面上（error 分支没排除 native 模式）；
 * ② 看不到视频（网页自己的黑底把 VLC 画面盖住了）；③ 返回后黑屏
 * （父页面状态没恢复 + 立刻又拉起原生）。
 */
describe('控制层：透明与状态恢复', () => {
  it('网页背景在 native 层清成透明（否则整块盖住 VLC 画面）', () => {
    expect(PLAYER).toContain('meow-native-layer')
    expect(PLAYER).toContain('background:transparent!important')
  })

  it('弹幕画布贴着视频实际显示区域（aspect-fit rect，避免飘黑边）', () => {
    expect(PLAYER).toContain('private func videoRect()')
    // Swift 字符串里是插值 `vx:\(...)`，这里只断言键名出现，避免转义歧义
    expect(PLAYER).toContain('vx:')
    expect(PLAYER).toContain('vw:')
    expect(LAYER).toContain('iosNative')
    expect(BRIDGE).toContain('vx: number')
  })

  it('native 模式不渲染错误页（否则报错盖在画面上）', () => {
    expect(PLAY).toContain('v-else-if="error && !iosNativeLayer"')
  })

  it('从原生返回要清干净错误/遮罩，且本帧不再自动拉起', () => {
    expect(PLAY).toContain('nativeJustClosed = true')
    expect(PLAY).toContain('if (nativeJustClosed)')
  })

  it('控制层复用播放器本体：不加载 src、状态来自原生推送', () => {
    expect(LAYER).toContain('const iosNative = isIosNativeLayer()')
    expect(LAYER).toContain('readIosNativeState()')
    // 操作入口都要转发给原生
    expect(LAYER).toContain("iosNativeCmd(playing.value ? 'pause' : 'play')")
    expect(LAYER).toContain("iosNativeCmd('seek'")
    expect(LAYER).toContain("iosNativeCmd('rate'")
    // 弹幕由原生画，web 引擎强制关
    expect(LAYER).toContain('dm.value.setEnabled(iosNative ? false : dmOn.value)')
  })
})

/**
 * 控制层第三轮真机修复（2026-11-27）：
 * ① 集数切换无效（native 层 load 仍走"容器不支持→转码"，play.value 永不落地）
 * ② 静音按钮无效（原生渲染时 <video> 不存在，改 muted 等于改了个寂寞）
 * ③ 弹幕挤（车道满时强行叠字；也不跟播放页的「显示区域」设置走）
 * ④ 弹幕一顿一顿（4Hz UIView.animate + layer shadow + WebView 常驻合成）
 */
describe('控制层：切集 / 静音 / 弹幕（第三轮）', () => {
  it('切集：原生层 load() 跳过容器/转码判定，直接把地址交给原生', () => {
    const at = PLAY.indexOf('if (isLibraryPlay.value) {')
    const body = PLAY.slice(at, at + 1600)
    expect(body).toContain('if (iosNativeLayer.value) {')
    expect(body).toContain('play.value = { url: libraryDirect.value } as PlayResult')
    // 同一地址不重开（控制层初次加载会把自己的初始地址推一遍，重开会回到 0）
    expect(PLAYER).toContain('if target == currentMediaUrl { return }')
  })

  it('静音：原生渲染走 mute 指令，且不动 volume.value（取消静音要能还原）', () => {
    expect(LAYER).toContain("iosNativeCmd('mute', muted.value ? 1 : 0)")
    expect(LAYER).toContain("iosNativeCmd('mute', p === 0 ? 1 : 0)")
    expect(PLAYER).toContain('case "mute":')
    expect(PLAYER).toContain('lastUnmutedVolume')
  })

  it('弹幕设置：变更实时推给原生（透明度/字号/速度/区域/屏蔽词/校准）', () => {
    expect(BRIDGE).toContain("| 'dmOptions'")
    expect(PLAYER).toContain('case "dmOptions":')
    expect(LAYER).toContain('function pushDmOptionsToNative()')
    expect(LAYER).toContain("block: props.dmBlockWords || ''")
    expect(LAYER).toContain('offset: dmOffset.value')
    // 校准单位：网页是秒，原生换毫秒（与 TV 同口径）
    expect(PLAYER).toContain('(dm["offset"] as? NSNumber)?.doubleValue ?? 0) * 1000')
  })

  it('弹幕：换集要换弹幕源（否则第 2 集还在飞第 1 集的弹幕）', () => {
    expect(BRIDGE).toContain("| 'dmUrl'")
    expect(PLAYER).toContain('case "dmUrl":')
    expect(LAYER).toContain("iosNativeCmd('dmUrl', u)")
  })

  it('弹幕：车道满就丢弃，不强行叠字（TV 同一判据"宁可少，不叠"）', () => {
    expect(DANMAKU).toContain('private func scrollLane(')
    expect(DANMAKU).toContain('return -1')
    expect(DANMAKU).toContain('guard lane >= 0 else { return }')
    // 静态弹幕动态找空道，找不到就丢（不压滚动弹幕）
    expect(DANMAKU).toContain('private func staticLane(')
    expect(DANMAKU).toContain('active.contains(where: { $0.lane == lane })')
  })

  it('弹幕：60fps 插值（displayLink），不再 4Hz UIView.animate', () => {
    expect(DANMAKU).toContain('CADisplayLink')
    expect(DANMAKU).toContain('CACurrentMediaTime()')
    expect(DANMAKU).toContain('CATransaction.setDisableActions(true)')
    expect(DANMAKU).not.toContain('UIView.animate(withDuration: 0.26')
    // 播放器只推校准锚点 + 播放状态 + 倍速
    expect(PLAYER).toContain('danmaku.tick(positionMs: Int(cur), playing: player.isPlaying')
  })

  it('弹幕样式：纯色字 + layer shadow（负描边会把中文笔画吃掉，已回退）', () => {
    // 2026-11-27 真机回归：NSAttributedString 负 strokeWidth 画在填充之上，
    // 中文密笔画内芯被盖 → 屏幕上是一片发脏的灰边字。
    expect(DANMAKU).not.toContain('strokeWidth: -6.0')
    expect(DANMAKU).toContain('label.layer.shadowColor')
    expect(DANMAKU).toContain('label.layer.shadowRadius = 2')
    // 掉帧靠位图缓存解决：阴影烘进 raster cache，移动时只做合成
    expect(DANMAKU).toContain('shouldRasterize = true')
    expect(DANMAKU).toContain('rasterizationScale')
  })

  it('弹幕：按视频实际显示区域裁剪（不飘黑边，区域设置才真的生效）', () => {
    expect(DANMAKU).toContain('func setViewport(')
    expect(DANMAKU).toContain('private var viewport: CGRect')
    expect(PLAYER).toContain('danmaku.setViewport(videoRect())')
  })

  it('控制栏显隐只降频，不隐藏 WebView（隐藏过=控制栏再也呼不出来，已回退）', () => {
    expect(LAYER).toContain('const nativeLayerIdle = computed(')
    expect(LAYER).toContain("iosNativeCmd('bar', idle ? 0 : 1)")
    expect(LAYER).toContain('!locked.value')
    expect(LAYER).toContain('!anyPanelOpen.value')
    expect(LAYER).toContain('!inIntro.value')
    // ★ 2026-11-27 真机回归：isHidden 过的 WebView 就是唯一的控件来源，
    // 藏起来以后点屏唤回链没跑通 → 底部控制栏/返回按钮彻底消失。
    expect(PLAYER).not.toContain('controlWebView?.isHidden = !on')
    expect(PLAYER).toContain('controlBarVisible = (value as? NSNumber)?.boolValue')
    expect(PLAYER).toContain('let every = controlBarVisible ? 1 : 4')
  })

  it('原生退出后不再黑屏：给出继续 / 下一集 / 返回三个出口', () => {
    expect(PLAY).toContain('v-if="nativeExited"')
    expect(PLAY).toContain('function nativePlayNext()')
  })

  it('左上角返回：走 back 指令让父页面后退，不再弹「已退出」面板', () => {
    expect(BRIDGE).toContain("| 'back'")
    expect(PLAYER).toContain('case "back":')
    expect(PLAYER).toContain('private static let backMarker = "__back__"')
    expect(PLAYER).toContain('pendingNav = Self.backMarker')
    expect(LAYER).toContain("iosNativeCmd('back')")
    expect(PLAY).toContain("if (r.nav === '__back__')")
    // 兜底：深链进入没有历史可退时回片库/首页
    expect(PLAY).toContain("{ name: 'library' } : { name: 'home' }")
  })

  it('点播页同样能进端内播放器（与片库共用同一套原生画面/弹幕）', () => {
    expect(PLAY).toContain('(hasNativePlayer() || hasIosNativePlayer())')
    expect(PLAY).toContain('if (hasIosNativePlayer() && tryIosNative()) return')
  })

  it('弹幕解析与网页 filterDanmaku 对齐（数字实体 / 纯符号 / 同文 1.5s 去重）', () => {
    expect(DANMAKU).toContain('decodeNumericEntities')
    expect(DANMAKU).toContain('rangeOfCharacter(from: .alphanumerics)')
    expect(DANMAKU).toContain('abs(timeMs - prev) < 1_500')
  })

  it('长弹幕按网页 durFor 加速（速度观感两边一致）', () => {
    expect(DANMAKU).toContain('private func durFor(width:')
    expect(DANMAKU).toContain('let durMs: Int')
    expect(DANMAKU).toContain('let dur = a.durMs > 0 ? a.durMs : scrollMs')
  })

  it('原生播放每 12 秒回传进度（切后台/被杀最多丢 12 秒）', () => {
    expect(PLAYER).toContain('"/api/state/progress"')
    expect(PLAYER).toContain('timeInterval: 12')
    expect(PLAYER).toContain('didEnterBackgroundNotification')
    expect(PLAYER).toContain('reportProgress(force: true)')
  })

  it('控制层渲染进程被回收时自动重载（否则控制条永久空白）', () => {
    expect(PLAYER).toContain('WKNavigationDelegate')
    expect(PLAYER).toContain('webViewWebContentProcessDidTerminate')
    expect(PLAYER).toContain('webView.reload()')
  })

  it('倍速上限与网页一致（3x）；弹幕重试/手选源也要同步给原生', () => {
    // 网页 rateOptions 到 3x，原生夹在 2 会让 2.5/3x 点了没反应
    expect(PLAYER).toContain('player.rate = min(3, max(0.5, r))')
    // 30 秒重试用同一个 URL、手选源走 dmOverrideUrl：都得从 loadDanmaku 推
    expect(LAYER).toContain("if (iosNative && url) iosNativeCmd('dmUrl', url)")
    expect(PLAYER).toContain('|| danmakuXml == nil')
  })

  it('对外接口在原生模式下改走指令（投屏暂停 / 从头开始 / 进度读取）', () => {
    // 投屏成功后 castTo 调 player.pause()：打 <video> 停不了 VLC，会两边同时出声
    expect(LAYER).toMatch(/function pause\(\)[\s\S]{0,500}iosNativeCmd\('pause'\)/)
    // 从头开始 / 恢复播放
    expect(LAYER).toContain("iosNativeCmd('seek', 0)")
    expect(LAYER).toMatch(/function play\(\)[\s\S]{0,200}iosNativeCmd\('play'\)/)
    // 进度/时长必须读原生推送的 ref，不能读没在播的 <video>
    expect(LAYER).toContain('if (iosNative) return currentTime.value')
    expect(LAYER).toContain('if (iosNative) return !playing.value')
    expect(LAYER).toContain('if (iosNative) return duration.value || props.totalDuration || 0')
  })

  it('后端地址可配（对齐 TV）：壳落盘 + 设置页可编辑 + 扫描 + 恢复端内', () => {
    expect(BOOT).toContain('BackendOverride.current()')
    expect(BOOT).toContain("postMessage({op:'apiBackend'")
    expect(BOOT).toContain("window.meowIos.apiBackend='")
    expect(WEBVIEW).toContain('case "apiBackend":')
    expect(SETTINGS).toContain('iosBackendConfigurable')
    expect(SETTINGS).toContain('function restoreEmbeddedBackend()')
    expect(SETTINGS).toContain('v-if="!ios || iosBackendConfigurable"')
    // 原生 URLSession 要能访问外部 http 后端
    expect(PLIST).toContain('NSAllowsArbitraryLoads')
  })

  it('字幕：随 meta/open 交给 VLC（:sub-file），不依赖运行期 slave API', () => {
    expect(PLAYER).toContain(':sub-file=')
    expect(PLAYER).toContain('private var pendingSubUrl')
    expect(PLAY).toContain('function nativeSubUrl()')
    expect(PLAY).toContain('iosNativeOpen(absoluteApi(u), play.value?.header, nativeSubUrl())')
    expect(BRIDGE).toContain('sub: sub || ')
  })

  it('自然播完交给网页 onEnded（连播倒计时/立即下一集/关闭连播三种设置一致）', () => {
    expect(PLAYER).toContain('private func notifyEnded()')
    expect(PLAYER).toContain('__meowVlcEnded')
    expect(PLAYER).toContain('notifyEnded()')
    expect(LAYER).toContain('__meowVlcEnded')
    expect(LAYER).toContain('onMediaEnded()')
    // 换集要复位 endedSent（否则第二集播完被吞，连播只生效一次）
    expect(LAYER).toContain('endedSent = false')
    // 不再"播完就 finish（弹已退出面板）"
    expect(PLAYER).toContain('notifyEnded()')
  })

  it('片头/片尾/中插跳过：seekBy 在原生模式改走指令', () => {
    expect(LAYER).toMatch(/function seekBy\(sec: number\)[\s\S]{0,400}iosNativeCmd\('seek'/)
  })

  it('弹幕：渐变（g=）与字重 600 对齐网页', () => {
    expect(DANMAKU).toContain('var gradient: [UIColor]? = nil')
    expect(DANMAKU).toContain('static func parseGradient(')
    expect(DANMAKU).toContain('Self.gradientText(')
    expect(DANMAKU).toContain('weight: .semibold')
  })

  it('卡死自愈：原生 12s 位置不动 → 通知控制层走换线/转码链', () => {
    expect(PLAYER).toContain('private func checkStall(')
    expect(PLAYER).toContain('Date().timeIntervalSince(lastMoveAt) > 12')
    expect(PLAYER).toContain('__meowVlcStalled')
    expect(LAYER).toContain('__meowVlcStalled')
    expect(LAYER).toMatch(/__meowVlcStalled[\s\S]{0,160}failPlay\(/)
  })

  it('媒体信息：原生上报片源分辨率（VLC videoSize），面板显示「原生 WxH」', () => {
    expect(PLAYER).toContain('sw:\\(sw),sh:\\(sh)')
    expect(BRIDGE).toContain('sw: Number(raw.sw) || 0')
    expect(LAYER).toContain("emit('native-size'")
    expect(PLAY).toContain('function onNativeSize(')
    expect(PLAY).toContain("via: 'native', srcWidth: s.w, srcHeight: s.h")
  })

  it('锁屏媒体信息：原生 MPNowPlayingInfoCenter + 远程控制；网页走 Media Session', () => {
    expect(PLAYER).toContain('MPNowPlayingInfoCenter.default().nowPlayingInfo')
    expect(PLAYER).toContain('MPRemoteCommandCenter.shared()')
    expect(PLAYER).toContain('changePlaybackPositionCommand')
    expect(PLAYER).toContain('private func clearNowPlaying()')
    expect(LAYER).toContain('function setupMediaSession()')
    expect(LAYER).toContain('mediaSessionApi()')
    expect(LAYER).toContain('setPositionState')
    // 原生模式跳过网页 Media Session（两边会互相覆盖）
    {
      const at = LAYER.indexOf('function setupMediaSession()')
      expect(at).toBeGreaterThan(0)
      expect(LAYER.slice(at, at + 160)).toContain('if (iosNative) return')
    }
  })

  it('PiP：iOS 走 webkitSetPresentationMode（原来只看 pictureInPictureEnabled 恒 false）', () => {
    expect(LAYER).toContain('webkitSupportsPresentationMode')
    expect(LAYER).toContain('webkitSetPresentationMode')
  })

  it('睡眠定时在原生播放时也能停（控制层补 powerTimer 桥 + 直接暂停）', () => {
    // 控制层是独立 WebView：不补 powerTimer 的话「更多 → 定时」设了没人收
    expect(PLAYER).toContain("op:'powerTimer'")
    expect(PLAYER).toContain('PowerTimer.start(seconds:')
    // 到点只往主 WebView 派 JS 打不到控制层 → PowerTimer 直接暂停原生播放器
    expect(POWER).toContain('NativeVlcPlayer.activePlayer?.pauseFromPowerTimer()')
    expect(PLAYER).toContain('func pauseFromPowerTimer()')
    expect(LAYER).toContain("iosNativeCmd('pause')")
  })
})
