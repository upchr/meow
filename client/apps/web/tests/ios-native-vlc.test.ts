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

describe('原生弹幕：参数与 TV 逐项对齐', () => {
  it('常量一致（静态 4s / 追赶窗 1.2s / 车道间隔 350ms / seek 阈值 2.5s）', () => {
    expect(DANMAKU).toContain('STATIC_MS: Int = 4_000')
    expect(DANMAKU).toContain('SPAWN_WINDOW_MS: Int = 1_200')
    expect(DANMAKU).toContain('LANE_GAP_MS: Int = 350')
    expect(DANMAKU).toContain('SEEK_JUMP_MS: Int = 2_500')
  })

  it('字号/车道/区域按 TV 的公式算（h/32*scale、h*area/laneH）', () => {
    expect(DANMAKU).toContain('h / 32 * scale')
    expect(DANMAKU).toContain('Int(h * areaRatio / laneH)')
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
    expect(PLAYER).toContain('let barVisible = controlWebView?.isHidden == false')
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

  it('PlayView：选集/换线路在本层取地址后让原生换片', () => {
    expect(PLAY).toContain('async function nativeSelectEpisode(')
    expect(PLAY).toContain('async function nativeSelectLine(')
    expect(PLAY).toContain("iosNativeCmd('open', u)")
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
