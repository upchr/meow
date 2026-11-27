import UIKit
import WebKit
import MobileVLCKit

/**
 * iOS 版「原生兜底播放器」——与 TV 的 `openNative` 同构。
 *
 * ## 为什么需要它
 *
 * iOS 不能 fork/exec（`Transcode.isIos()` 直接禁掉 ffmpeg 子进程），所以
 * 「服务端转码 / 本机 ffmpeg」这两条路在 IPA 上都不存在；而 WKWebView 的
 * `<video>` 只认 MP4/HLS —— MKV/AVI/FLV 是**容器**不支持，DTS 是音频没有
 * 系统解码器。把 VLCKit（内含 libav*）挂上来，这些格式第一次能在 iPhone 上播。
 *
 * ## 职责边界（第一版最小集，与 TV 的兜底模式一致）
 *
 * 播放/暂停、进度拖动、时间、全屏（横屏）、关闭、标题、弹幕、进度回传。
 * **不做**：双击暂停、弹幕设置面板、手势亮度/音量 —— 那些在 web 主路径里，
 * 原生兜底模式的用户预期是"能看"，不做成第二套完整播放页。
 *
 * ## 与 web 的契约
 *
 * 进入：`meowIos.openNativeVlc({url, title, headers, positionMs, danmakuUrl, metaJson})`
 * 退出：`window.__meowVlcClosed({positionSec, durationSec, ended})`
 *
 * 为什么用「轮询 state」而不是 `VLCMediaPlayerDelegate`：delegate 的方法签名
 * 在 3.x 各小版本之间改过（`Notification` / `Notification!`），而 0.25 秒轮询
 * `state` 只依赖稳定属性 —— 少一个编译期踩坑点。
 */
final class NativeVlcPlayer: UIViewController, WKScriptMessageHandler {

    // MARK: - 入参

    private let mediaUrl: URL
    private let titleText: String
    private var headers: [String: String]
    private let startPositionMs: Int
    private var danmakuUrl: String?
    private let meta: [String: Any]
    /// 进度回传给谁（弱引用：webview 可能先被销毁）
    private weak var hostWebView: WKWebView?
    /// 控制层页面地址（同一路由 + native=1），nil = 退回内置 Swift 控制条
    private var controlURL: URL?
    /// 控制层要用的后端地址（那边没有宿主注入的 meowIos.apiBase）
    private var controlApiBase: String?

    // MARK: - 播放器与视图

    private let player = VLCMediaPlayer()
    private let videoContainer = UIView()
    /// 透明 WKWebView：加载播放页的 native 控制层（图一同一套 UI）
    private var controlWebView: WKWebView?
    /// true = 用 web 控制层（图一那套）；false = 用下面的内置 Swift 控制条（兜底）
    private var useWebControls: Bool { controlURL != nil }
    private let danmaku = VlcDanmakuOverlay()
    private let controls = UIView()
    private let topBar = UIView()
    private let titleLabel = UILabel()
    private let playButton = UIButton(type: .system)
    private let slider = UISlider()
    private let timeLabel = UILabel()
    private let fullButton = UIButton(type: .system)

    private var tickTimer: Timer?
    private var hideControlsWork: DispatchWorkItem?
    private var controlsVisible = true
    private var didStart = false
    private var didFinish = false
    /**
     * 用户主动暂停过。
     *
     * <p>回前台自动续播只对"被系统暂停"生效 —— 用户自己按的暂停必须尊重，
     * 否则切回来又自己播起来（WebKit 那边踩过同类问题）。
     */
    private var userPaused = false
    /// 起播看门狗：VLC 打不开流时 state 会长期停在 opening，界面就是黑屏 00:00
    private var startedAt = Date()
    private var readyOnce = false
    /// 起播失败的说明浮层（点一下屏幕即退出）
    private var errorLabel: UILabel?
    /// 关掉原生后让父页面跳去的路由（搜索/设置这类入口）。空 = 不跳
    private var pendingNav = ""
    /// `nav` 哨兵：用户按的是「返回」，父页面应 router.back() 而不是弹退出面板
    private static let backMarker = "__back__"
    /// tick 计数（pushState 降频用：起播时 4Hz，稳定后 2Hz）
    private var tickCount = 0
    /// 拖动中不回写 slider，否则 0.25s 的 tick 会把拖动位置抢回去
    private var seeking = false

    // MARK: - 生命周期

    init(url: URL, title: String, headers: [String: String], positionMs: Int,
         danmakuUrl: String?, meta: [String: Any], host: WKWebView?,
         controlURL: URL? = nil, apiBase: String? = nil) {
        self.mediaUrl = url
        self.titleText = title
        self.headers = headers
        self.startPositionMs = max(0, positionMs)
        self.danmakuUrl = danmakuUrl
        self.meta = meta
        self.hostWebView = host
        self.controlURL = controlURL
        self.controlApiBase = apiBase
        self.currentMediaUrl = url.absoluteString
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        buildVideoLayer()
        /*
         * 控制层二选一（2026-11-27）：
         *   有 controlURL → 透明 WKWebView 加载播放页 native 模式（图一同一套 UI）
         *   没有 → 退回内置 Swift 控制条（老路径，只作兜底）
         *
         * ★ 点屏手势只在"内置控制条"模式下挂（见 buildControls）。web 控制层
         * 模式下**不要**在根视图上挂 UITapGestureRecognizer：全屏 WebView 才是
         * 唯一的控件来源，祖先手势会参与触摸仲裁（延迟 touchesEnded / 与网页
         * 自己的点屏逻辑抢），2026-11-27 真机回归就死在这类"双通道"上。
         */
        if useWebControls {
            setupControlWebView()
        } else {
            buildControls()
        }
        prepareMedia()
        attachDanmaku()
        startTicking()
        /*
         * 回前台补播（2026-11-27 补）：App 挂起时 VLC 的音频输出会被系统停掉，
         * 回前台**不会自己恢复** —— 表现是"切出去再回来画面还在但不动"。
         * 用户主动按过暂停的不补（尊重用户意图）。
         */
        NotificationCenter.default.addObserver(
            self, selector: #selector(appBecameActive),
            name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    // MARK: - web 控制层

    /**
     * 透明 WKWebView：加载播放页并注入 native 模式桥。
     *
     * <p>注入的三样东西：
     *   `__meowNativeLayer`  —— 页面据此隐藏 `<video>`、改渲染 NativeIosControlLayer
     *   `meowIos.nativeCmd`  —— 页面把 play/pause/seek/rate/close/open/orientation 发过来
     *   `meowIos.setOrientation` —— 复用页面已有的全屏/转屏调用（同一通道）
     */
    private func setupControlWebView() {
        guard let url = controlURL else { return }
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        let uc = WKUserContentController()
        let js = """
        window.__meowNativeLayer = true;
        window.meowIos = window.meowIos || {};
        window.meowIos.apiBase = '\(Self.jsQuote(controlApiBase ?? ""))';
        window.meowIos.nativeCmd = function(op, value){try{
          window.webkit.messageHandlers.meowVlc.postMessage({op:String(op||''),value:(value===undefined?0:value)});
          return true}catch(e){return false}};
        window.meowIos.setOrientation = function(m){try{
          window.webkit.messageHandlers.meowVlc.postMessage({op:'orientation',value:String(m||'')});
          return true}catch(e){return false}};
        /*
         * 音量/亮度桥（2026-11-27）：播放器的底栏滑条读 hasSystemVolume()、
         * 竖滑调亮度也都走这几个名字。控制层是**另一个 WebView**，没有宿主
         * Bootstrap 注入的那份 —— 不补的话"音量拖了没反应"（真机实报）。
         */
        window.meowIos.volume = \(SystemVolume.current());
        window.meowIos.setVolume = function(v){try{
          window.webkit.messageHandlers.meowVlc.postMessage({op:'volume',value:Number(v)||0});
          return true}catch(e){return false}};
        window.meowIos.volumeHud = function(on){try{
          window.webkit.messageHandlers.meowVlc.postMessage({op:'volumeHud',value:!!on});
          return true}catch(e){return false}};
        window.meowIos.brightness = \(SystemBrightness.current());
        window.meowIos.getBrightness = function(){return window.meowIos.brightness||0};
        window.meowIos.setBrightness = function(v){try{
          window.webkit.messageHandlers.meowVlc.postMessage({op:'brightness',value:Number(v)});
          return true}catch(e){return false}};
        /*
         * ★ 关键：网页自己有黑底（html/body/#app），WKWebView 只设 isOpaque=false
         * 不够 —— 那层 DOM 背景会把 VLC 画面整块盖住（2026-11-27 真机实报
         * 「弹幕在飞、进度在走，但看不到视频」）。只在本层把背景清成透明。
         */
        document.addEventListener('DOMContentLoaded', function(){
          document.body.classList.add('meow-native-layer');
          var st = document.createElement('style');
          /*
           * 清到第 5 层后代：html/body/#app/router-view 容器/页面根/播放器 root。
           * 只清"骨架"，不再往下——控制条、按钮、浮层的背景都在更深的层，
           * 否则会把底栏渐变和按钮底色一起清掉（2026-11-27 踩过）。
           */
          var sel = ['', ' #app', ' #app>div', ' #app>div>div',
                     ' #app>div>div>div', ' #app>div>div>div>div'];
          st.textContent = sel.map(function (x) {
            return 'body.meow-native-layer' + x;
          }).join(',') + '{background:transparent!important;background-image:none!important}';
          document.head.appendChild(st);
        });
        """
        uc.addUserScript(WKUserScript(source: js, injectionTime: .atDocumentStart,
                                      forMainFrameOnly: true))
        uc.add(self, name: "meowVlc")
        cfg.userContentController = uc

        let w = WKWebView(frame: view.bounds, configuration: cfg)
        w.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        w.backgroundColor = .clear
        w.scrollView.backgroundColor = .clear
        /*
         * ★ 透明只认这三个合法 API（2026-11-27 真机闪退教训）：
         *   有人会写 `setValue(false, forKey: "drawsBackground")` —— 那是 macOS
         *   WebKit 的 key，iOS 的 WKWebView 没有 → KVC 抛
         *   NSUnknownKeyException，**一进原生播放器就闪退**。
         *   iOS 上 isOpaque=false + 两层 backgroundColor 就够了，剩下的靠
         *   页面侧 CSS 把 html/body/#app 背景清掉。
         */
        w.isOpaque = false
        w.scrollView.isOpaque = false
        w.scrollView.isScrollEnabled = false
        w.scrollView.contentInsetAdjustmentBehavior = .never
        view.addSubview(w)
        controlWebView = w
        w.load(URLRequest(url: url))
        LaunchLog.write("[vlc] control layer: \(url.absoluteString.prefix(180))")
    }

    /// 把播放状态推给控制层（250ms 一次，与 web 播放页的 timeupdate 频率一致）
    ///
    /// <p>除了时间，还带**视频实际显示区域**（aspect-fit 后的 rect）：控制层的
    /// 弹幕画布要按这个区域裁剪，否则会飘到黑边上（2026-11-27 真机实报）。
    private func pushState() {
        guard let w = controlWebView else { return }
        let cur = Double(player.time.intValue) / 1000
        let total = (durationMs() ?? 0) / 1000
        let playing = player.isPlaying
        let r = videoRect()
        let js = "window.__meowNativeState={t:\(cur),d:\(total),p:\(playing),"
            + "vx:\(r.origin.x),vy:\(r.origin.y),vw:\(r.width),vh:\(r.height)}"
        w.evaluateJavaScript(js, completionHandler: nil)
    }

    /// 视频在 view 里的实际显示矩形（aspect-fit 居中）。拿不到视频尺寸时退回全屏。
    private func videoRect() -> CGRect {
        let vs = player.videoSize
        let b = view.bounds
        guard vs.width > 1, vs.height > 1, b.width > 1, b.height > 1 else { return b }
        let scale = min(b.width / vs.width, b.height / vs.height)
        let w = vs.width * scale
        let h = vs.height * scale
        return CGRect(x: (b.width - w) / 2, y: (b.height - h) / 2, width: w, height: h)
    }

    /// 注入 JS 字符串里的引号转义
    private static func jsQuote(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "'", with: "\\'")
    }

    // MARK: - WKScriptMessageHandler（web 控制层 → 原生）

    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        guard message.name == "meowVlc",
              let d = message.body as? [String: Any],
              let op = d["op"] as? String else { return }
        let value = d["value"]
        switch op {
        case "play":
            userPaused = false
            player.play()
            AudioSession.setPlaying(true)
        case "pause":
            userPaused = true
            player.pause()
            AudioSession.setPlaying(false)
        case "seek":
            let sec = (value as? NSNumber)?.doubleValue ?? 0
            if let total = durationMs(), total > 0 {
                player.position = Float(min(1, max(0, sec * 1000 / total)))
            }
            LaunchLog.write("[vlc] seek \(Int(sec))s")
        case "rate":
            let r = (value as? NSNumber)?.floatValue ?? 1
            // VLCKit 的 rate 只支持有限档位，越界会被忽略；这里夹到 0.5~2
            player.rate = min(2, max(0.5, r))
        case "open":
            /*
             * 选集/换线：web 层已经拿到新地址，这里只换 media。
             *
             * <p>value 两种形态：
             *   String            —— 只换地址（旧协议，header 沿用上一次）
             *   {url, headers}    —— 连 header 一起换（换线路时防盗链头可能不同）
             */
            var target = ""
            if let s = value as? String {
                target = s
            } else if let d = value as? [String: Any] {
                target = (d["url"] as? String) ?? ""
                if let raw = d["headers"] as? String {
                    headers = Self.parseHeaders(raw)
                }
            }
            guard let u = URL(string: target) else { return }
            /*
             * 同一地址不重开（2026-11-27）：控制层刚加载时会把自己的初始地址
             * 也推一遍，重开会把正在看的进度打回 0。
             */
            if target == currentMediaUrl { return }
            openNewMedia(u)
        case "close":
            finish(ended: false)
        case "back":
            /*
             * 用户按了「返回」（2026-11-27）。与 close 的区别：父页面要
             * router.back() 回片库/详情，而不是停在"已退出端内播放器"面板。
             * 标记复用 nav 字段（父页面识别这个哨兵值）。
             */
            pendingNav = Self.backMarker
            finish(ended: false)
        case "orientation":
            ShellOrientation.apply(value as? String ?? "")
        case "bar":
            /*
             * 控制栏显隐 → 只调「状态推送频率」，**不隐藏 WebView**。
             *
             * ★ 2026-11-27 真机回归（已回退）：把透明 WebView 整个 isHidden 后，
             * 点屏唤回要走「原生手势 → 放出 WebView → 调网页钩子」三跳，
             * 真机上这条链没跑通 —— 用户看到的是"控制栏和返回按钮再也呼不出来"，
             * 只能强退。控制层的 DOM 就是唯一的控件来源，**不能把它藏起来**。
             * 合成开销改由「隐藏时 1Hz 推状态」来省（见 tick 里的 barVisible）。
             */
            controlBarVisible = (value as? NSNumber)?.boolValue ?? true
        case "volume":
            // 网页改不了 iOS 系统音量，交给 MPVolumeView
            SystemVolume.set((value as? NSNumber)?.intValue ?? 0)
        case "volumeHud":
            if (value as? NSNumber)?.boolValue ?? false {
                SystemVolume.attach()
                let v = SystemVolume.current()
                controlWebView?.evaluateJavaScript(
                    "if(window.meowIos)window.meowIos.volume=\(v)", completionHandler: nil)
            } else {
                SystemVolume.detach()
            }
        case "brightness":
            // 负数 = 还原进页前的亮度
            SystemBrightness.set((value as? NSNumber)?.intValue ?? -1)
        case "dm":
            // 弹幕开关（弹幕由本层 VlcDanmakuOverlay 渲染，web 只发指令）
            danmaku.setEnabled((value as? NSNumber)?.boolValue ?? true)
        case "dmUrl":
            /*
             * 换集后的新弹幕地址（2026-11-27）。原生只拿得到拉起那一刻的
             * danmakuUrl —— 不换的话，切到第 2 集还在放第 1 集的弹幕。
             */
            if let s = value as? String, !s.isEmpty, s != (danmakuUrl ?? "") { loadDanmaku(s) }
        case "dmOptions":
            /*
             * 弹幕设置（透明度/字号/速度/区域/屏蔽词/校准）。
             * 控制层调一次面板就发一次，原生这边立即重排 —— 只读启动时的
             * metaJson 会导致"进了原生页后面板怎么调都没反应"。
             */
            if let d = value as? [String: Any] { applyDanmakuOptions(d) }
        case "mute":
            /*
             * 静音/取消。VLCKit 的 audio 对象在各小版本里可用性不一致，
             * 而系统音量这条路已经有现成实现且真机验证过（滑块就走它）。
             * 只记"静音前的音量"用于还原；拖动滑块写入的 0 不会被抢回。
             */
            let on = (value as? NSNumber)?.boolValue ?? false
            if on {
                let curVol = SystemVolume.current()
                if curVol > 0 { lastUnmutedVolume = curVol }
                SystemVolume.set(0)
            } else if SystemVolume.current() == 0, lastUnmutedVolume > 0 {
                SystemVolume.set(lastUnmutedVolume)
            }
        case "nav":
            // 搜索/设置这类跳转：先关原生，再让父页面路由过去
            pendingNav = value as? String ?? ""
            finish(ended: false)
        default:
            break
        }
    }

    /// 换片（选集/换线）：保留播放器实例，只换 media（避免整页重建）
    private func openNewMedia(_ u: URL) {
        LaunchLog.write("[vlc] open \(u.absoluteString.prefix(160))")
        currentMediaUrl = u.absoluteString
        pendingSeekMs = 0
        userPaused = false
        didFinish = false
        didStart = false
        readyOnce = false
        startedAt = Date()
        errorLabel?.removeFromSuperview()
        errorLabel = nil
        /*
         * 换片先把弹幕清空：新一集的 danmakuUrl 随后由控制层用 `dmUrl` 推来，
         * 清空期间不能还飞着上一集的弹幕（真机上一眼就能看出来）。
         */
        danmakuXml = nil
        danmaku.setItems([])
        let media = VLCMedia(url: u)
        if let ua = headers["User-Agent"], !ua.isEmpty {
            media.addOption(":http-user-agent=\(ua)")
        }
        if let ref = headers["Referer"], !ref.isEmpty {
            media.addOption(":http-referrer=\(ref)")
        }
        player.media = media
        player.play()
        AudioSession.setPlaying(true)
    }

    @objc private func appBecameActive() {
        guard didStart, !didFinish, !userPaused, !player.isPlaying else { return }
        LaunchLog.write("[vlc] resume after background")
        player.play()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        scheduleHideControls()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        /*
         * 三级视图跟着容器尺寸走。之前只在 viewDidLoad 里赋一次 frame，
         * 旋转/安全区变化后视频层可能停在旧尺寸（表现为黑边或只有一角有画面）。
         */
        videoContainer.frame = view.bounds
        danmaku.frame = view.bounds
        controls.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        tickTimer?.invalidate()
        tickTimer = nil
    }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        // 原生播放一律横屏：竖屏里 16:9 的画面太小，且返回手势与 web 播放页一致
        .landscape
    }

    // MARK: - 视图搭建

    private func buildVideoLayer() {
        videoContainer.frame = view.bounds
        videoContainer.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        videoContainer.backgroundColor = .black
        view.addSubview(videoContainer)
        // VLCKit 自己往 drawable 上挂渲染层；这里只给一个空容器
        player.drawable = videoContainer

        danmaku.frame = view.bounds
        danmaku.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        // 弹幕层只负责画：不能吃掉点屏手势（控制层隐藏时靠它唤回控制栏）
        danmaku.isUserInteractionEnabled = false
        view.addSubview(danmaku)
    }

    private func buildControls() {
        controls.frame = view.bounds
        controls.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(controls)

        /*
         * 点屏切换控制条。★ 手势必须挂在 **view** 上，不能挂在 controls 上：
         * controls 一旦 isHidden，它自己的手势跟着失效，用户就再也呼不出控制条
         * —— 2026-11-27 真机实报「返回/状态栏都呼不出，只能强退」。
         * cancelsTouchesInView=false 让按钮、进度条照常收到触摸。
         */
        let tap = UITapGestureRecognizer(target: self, action: #selector(toggleControls))
        tap.cancelsTouchesInView = false
        view.addGestureRecognizer(tap)

        // 顶部：返回 + 标题
        topBar.translatesAutoresizingMaskIntoConstraints = false
        controls.addSubview(topBar)
        let back = UIButton(type: .system)
        back.setTitle("‹ 返回", for: .normal)
        back.setTitleColor(.white, for: .normal)
        back.titleLabel?.font = .systemFont(ofSize: 17, weight: .medium)
        back.addTarget(self, action: #selector(closePlayer), for: .touchUpInside)
        back.translatesAutoresizingMaskIntoConstraints = false
        topBar.addSubview(back)

        titleLabel.text = titleText
        titleLabel.textColor = .white
        titleLabel.font = .systemFont(ofSize: 15, weight: .medium)
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        topBar.addSubview(titleLabel)

        // 底部：播放/暂停 + 时间 + 进度 + 全屏
        let bottom = UIView()
        bottom.translatesAutoresizingMaskIntoConstraints = false
        controls.addSubview(bottom)

        playButton.setTitle("▶︎", for: .normal)
        playButton.setTitleColor(.white, for: .normal)
        playButton.titleLabel?.font = .systemFont(ofSize: 22)
        playButton.addTarget(self, action: #selector(togglePlay), for: .touchUpInside)
        playButton.translatesAutoresizingMaskIntoConstraints = false
        bottom.addSubview(playButton)

        timeLabel.text = "00:00 / 00:00"
        timeLabel.textColor = .white
        timeLabel.font = .monospacedDigitSystemFont(ofSize: 12, weight: .regular)
        timeLabel.translatesAutoresizingMaskIntoConstraints = false
        bottom.addSubview(timeLabel)

        slider.minimumValue = 0
        slider.maximumValue = 1
        slider.isContinuous = true
        slider.addTarget(self, action: #selector(sliderTouchDown), for: .touchDown)
        slider.addTarget(self, action: #selector(sliderChanged), for: .valueChanged)
        slider.addTarget(self, action: #selector(sliderTouchUp), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        slider.translatesAutoresizingMaskIntoConstraints = false
        bottom.addSubview(slider)

        fullButton.setTitle("⤢", for: .normal)
        fullButton.setTitleColor(.white, for: .normal)
        fullButton.titleLabel?.font = .systemFont(ofSize: 20)
        fullButton.addTarget(self, action: #selector(toggleOrientation), for: .touchUpInside)
        fullButton.translatesAutoresizingMaskIntoConstraints = false
        bottom.addSubview(fullButton)

        NSLayoutConstraint.activate([
            topBar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            topBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            topBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            topBar.heightAnchor.constraint(equalToConstant: 44),
            back.leadingAnchor.constraint(equalTo: topBar.leadingAnchor, constant: 16),
            back.centerYAnchor.constraint(equalTo: topBar.centerYAnchor),
            titleLabel.leadingAnchor.constraint(equalTo: back.trailingAnchor, constant: 12),
            titleLabel.trailingAnchor.constraint(lessThanOrEqualTo: topBar.trailingAnchor, constant: -16),
            titleLabel.centerYAnchor.constraint(equalTo: topBar.centerYAnchor),

            bottom.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            bottom.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            bottom.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -8),
            bottom.heightAnchor.constraint(equalToConstant: 44),
            playButton.leadingAnchor.constraint(equalTo: bottom.leadingAnchor),
            playButton.centerYAnchor.constraint(equalTo: bottom.centerYAnchor),
            playButton.widthAnchor.constraint(equalToConstant: 36),
            timeLabel.leadingAnchor.constraint(equalTo: playButton.trailingAnchor, constant: 8),
            timeLabel.centerYAnchor.constraint(equalTo: bottom.centerYAnchor),
            timeLabel.widthAnchor.constraint(equalToConstant: 96),
            slider.leadingAnchor.constraint(equalTo: timeLabel.trailingAnchor, constant: 8),
            slider.centerYAnchor.constraint(equalTo: bottom.centerYAnchor),
            fullButton.leadingAnchor.constraint(equalTo: slider.trailingAnchor, constant: 8),
            fullButton.trailingAnchor.constraint(equalTo: bottom.trailingAnchor),
            fullButton.centerYAnchor.constraint(equalTo: bottom.centerYAnchor),
            fullButton.widthAnchor.constraint(equalToConstant: 36),
        ])

        // 顶部/底部各一层渐变，白字压在亮画面上也看得清
        addGradient(to: topBar, from: CGColor(gray: 0, alpha: 0.65), to: CGColor(gray: 0, alpha: 0))
        addGradient(to: bottom, from: CGColor(gray: 0, alpha: 0), to: CGColor(gray: 0, alpha: 0.7))
    }

    private func addGradient(to target: UIView, from: CGColor, to: CGColor) {
        let layer = CAGradientLayer()
        layer.colors = [from, to]
        layer.frame = target.bounds
        target.layer.insertSublayer(layer, at: 0)
        target.layoutIfNeeded()
        layer.frame = target.bounds
    }

    // MARK: - 媒体准备

    private func prepareMedia() {
        let media = VLCMedia(url: mediaUrl)
        /*
         * 源站防盗链：VLC 自己拉流，UA/Referer 必须跟着走，否则 403
         * （与后端转码那条链路的 srcHeader 是同一件事）。
         */
        if let ua = headers["User-Agent"], !ua.isEmpty {
            media.addOption(":http-user-agent=\(ua)")
        }
        if let ref = headers["Referer"], !ref.isEmpty {
            media.addOption(":http-referrer=\(ref)")
        }
        player.media = media
        player.play()
        /*
         * ★ 告诉壳"正在播"（2026-11-27 补）：Info.plist 的 UIBackgroundModes
         * 只是允许，真正进后台要 AudioSession 在 background 时 setActive ——
         * 而 AudioSession.appDidEnterBackground() 只在 wantPlayback 时才激活。
         * 不调这一行，原生播放进后台/锁屏立刻静音（web 那条链有 meowIos.playing
         * 上报，原生这条链以前没人报）。
         */
        AudioSession.setPlaying(true)
        if startPositionMs > 0 {
            // VLCKit 的 position 是 0..1；时长常常要等 playing 之后才拿得到，
            // 所以起播位置在第一次 tick 里补（见 applyPendingSeek）
            pendingSeekMs = startPositionMs
        }
        LaunchLog.write("[vlc] play \(mediaUrl.absoluteString) start=\(startPositionMs)ms")
    }

    private var pendingSeekMs = 0
    /// 弹幕原始 XML：控制层改「校准/屏蔽词」时要按新参数重解析（只留解析结果就改不了）
    private var danmakuXml: String?
    /// 重解析代数号：滑块连续触发时只让最后一包结果落地
    private var danmakuParseGeneration = 0
    /// 拉取代数号：换集很快时丢弃晚到的上一集弹幕
    private var danmakuRequestGeneration = 0
    /// 最新的网页弹幕设置（dmOptions 覆盖 metaJson.dm，换集重拉时不能丢）
    private var currentDmOptions: [String: Any] = [:]
    /// 静音前的系统音量：mute op 用它还原（拖音量条时留给滑块值，不抢）
    private var lastUnmutedVolume = 0
    /// 当前媒体地址：`open` 收到同一地址时不重开（重开会把进度打回 0）
    private var currentMediaUrl = ""
    /// 控制栏是否可见（web 控制层通过 `bar` 指令上报）。只影响状态推送频率
    private var controlBarVisible = true

    private func applyPendingSeekIfNeeded() {
        guard pendingSeekMs > 0 else { return }
        guard let total = durationMs(), total > 0 else { return }
        let ratio = Float(min(1, Double(pendingSeekMs) / Double(total)))
        player.position = ratio
        pendingSeekMs = 0
        LaunchLog.write("[vlc] seek to \(Int(ratio * 100))%")
    }

    // MARK: - 弹幕

    private func attachDanmaku() {
        guard let raw = danmakuUrl, !raw.isEmpty else { return }
        loadDanmaku(raw)
    }

    /// 拉取并装载一集弹幕。换集时控制层会把新的 danmakuUrl 发过来。
    private func loadDanmaku(_ raw: String) {
        guard let url = URL(string: raw) else { return }
        danmakuUrl = raw
        danmakuXml = nil
        // 换集瞬间先清屏：新弹幕回来前不能还飞着上一集的
        danmaku.setItems([])
        applyVisualOptions(dmOptions())
        danmakuRequestGeneration += 1
        let gen = danmakuRequestGeneration
        URLSession.shared.dataTask(with: url) { [weak self] data, _, err in
            guard let self, let data, err == nil,
                  let xml = String(data: data, encoding: .utf8) else { return }
            /*
             * 解析用**回来的这一刻**的设置：加载途中用户可能已经改过面板
             * （dmOptions 先到、XML 后到），用请求发起时的快照会丢改动。
             */
            let dm = self.dmOptions()
            let items = Self.parseDanmaku(xml, dm: dm)
            LaunchLog.write("[vlc] danmaku \(items.count) items")
            DispatchQueue.main.async {
                // 换集很快时，晚到的上一集响应不能覆盖当前集
                guard gen == self.danmakuRequestGeneration else { return }
                self.danmakuXml = xml
                self.applyVisualOptions(dm)
                self.danmaku.setItems(items)
            }
        }.resume()
    }

    /// 只应用「看起来怎么样」的四项（透明度/字号/速度/区域）
    private func applyVisualOptions(_ dm: [String: Any]) {
        danmaku.applyOptions(
            opacity: CGFloat((dm["opacity"] as? NSNumber)?.doubleValue ?? 0.9),
            scale: CGFloat((dm["scale"] as? NSNumber)?.doubleValue ?? 1.0),
            durationSec: (dm["duration"] as? NSNumber)?.intValue ?? 12,
            areaRatio: CGFloat((dm["area"] as? NSNumber)?.doubleValue ?? 0.7))
    }

    /// 网页下发的弹幕设置块（metaJson.dm + 后续 dmOptions 覆盖）。没有就全部走默认值
    private func dmOptions() -> [String: Any] {
        if !currentDmOptions.isEmpty { return currentDmOptions }
        return (meta["dm"] as? [String: Any]) ?? [:]
    }

    /// 控制层改了弹幕设置（透明度/字号/速度/区域/屏蔽词/校准）→ 立即生效。
    ///
    /// <p>旧实现只在拉起原生那一刻读一次 meta，用户进了原生页再调面板就怎么调都没反应
    /// （2026-11-27 真机实报"弹幕挤、没办法对齐播放页的显示区域设置"）。
    /// 屏蔽词与校准只能在解析期应用，所以留着原始 XML 按新参数重解析。
    private func applyDanmakuOptions(_ d: [String: Any]) {
        // 记下来：换集时 loadDanmaku 要用**最新**设置，否则会把用户刚调好的
        // 透明度/字号/区域回退成拉起那一刻的 meta 值。
        currentDmOptions = d
        applyVisualOptions(d)
        guard let xml = danmakuXml else { return }
        /*
         * 重解析放后台：一集弹幕的 XML 常有 1~3MB，主线程正则要几十毫秒，
         * 正好卡在用户拖滑块的每一次 input 上（真机手感就是"调不动"）。
         * 代数号丢掉过期结果：滑块连续触发时只认最后一包。
         */
        danmakuParseGeneration += 1
        let gen = danmakuParseGeneration
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let items = Self.parseDanmaku(xml, dm: d)
            DispatchQueue.main.async {
                guard let self, gen == self.danmakuParseGeneration else { return }
                LaunchLog.write("[vlc] danmaku re-apply \(items.count) items")
                self.danmaku.setItems(items)
            }
        }
    }

    private static func parseDanmaku(_ xml: String, dm: [String: Any]) -> [VlcDanmakuOverlay.Item] {
        // 网页的 offset 单位是**秒**（dmOffset.ts 的 setDmOffset(sec)），这里换毫秒
        let offsetMs = Int(((dm["offset"] as? NSNumber)?.doubleValue ?? 0) * 1000)
        let raw = dm["block"] as? String ?? ""
        let words = raw.split(whereSeparator: {
            $0 == "," || $0 == "，" || $0.isWhitespace
        }).map { String($0) }.filter { !$0.isEmpty }
        return VlcDanmakuOverlay.parse(xml: xml, offsetMs: offsetMs, blockWords: words)
    }

    // MARK: - 轮询

    private func startTicking() {
        tickTimer?.invalidate()
        let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            self?.tick()
        }
        RunLoop.main.add(timer, forMode: .common)
        tickTimer = timer
    }

    private func tick() {
        applyPendingSeekIfNeeded()

        /*
         * 起播看门狗（2026-11-27 真机实报「黑屏 00:00、什么都没反应、只能强退」）：
         * VLC 拉流失败时 state 会长期停在 opening，界面既没有画面也没有任何提示。
         * 10 秒还没 ready 就亮出原因并允许点屏退出。
         */
        if !readyOnce, !didFinish, Date().timeIntervalSince(startedAt) > 10 {
            showFatal("这个地址打不开（VLC 拉流失败）")
        }

        // VLCKit 3.7 的 time 是非可选 VLCTime（可选链会编译失败，2026-11-27 CI 实测）
        let cur = Double(player.time.intValue)
        let total = durationMs() ?? 0
        if !seeking, total > 0 {
            slider.value = Float(min(1, max(0, cur / total)))
        }
        timeLabel.text = "\(Self.clock(cur)) / \(Self.clock(total))"
        playButton.setTitle(player.isPlaying ? "❚❚" : "▶︎", for: .normal)
        /*
         * 弹幕：先按视频实际显示区域（aspect-fit 后的 rect）裁剪画布 —— 否则
         * 16:9 片源在更宽的屏上会把弹幕放到左右黑边上（2026-11-27 真机实报
         * "没法对齐播放页的显示区域"）。位置由 overlay 自己的 displayLink 插值，
         * 这里只推校准锚点 + 播放状态 + 倍速。
         */
        danmaku.setViewport(videoRect())
        danmaku.tick(positionMs: Int(cur), playing: player.isPlaying,
                     rate: Double(player.rate))
        /*
         * 状态推送降频（2026-11-27 真机实报「弹幕很卡顿」）：
         * evaluateJavaScript 是主线程工作，4Hz 一直跑会和弹幕合成抢 CPU。
         * 控制栏可见时要顺滑（4Hz），隐藏时 1Hz 够了。
         *
         * <p>可见性来自 web 的 `bar` 指令（**不再**用 WebView 的 isHidden ——
         * 那条路会让控制层摸不到，2026-11-27 真机回归已回退）。
         */
        tickCount += 1
        let every = controlBarVisible ? 1 : 4
        if tickCount % every == 0 || !readyOnce { pushState() }

        switch player.state {
        case .ended:
            finish(ended: true)
        case .error:
            // 不直接关：先把原因亮出来，用户点一下屏幕才回网页（不然又是一次"闪退感"）
            showFatal("播放出错（VLC 报告 error）")
        case .stopped:
            // stop 只可能来自我们自己（close 里已处理）；这里兜一次防卡死
            if didStart && !didFinish { finish(ended: false) }
        case .playing, .buffering:
            didStart = true
            readyOnce = true
        default:
            break
        }
    }

    /**
     * 起播失败：亮出原因，并把"退出方式"说清楚。
     *
     * <p>这是"不能再有只能强退的界面"的兜底：只要走到这里，屏幕上一定有一个
     * 能点的出口（点任意位置 → 回播放页，控制条的返回按钮也同时亮出来）。
     */
    private func showFatal(_ text: String) {
        guard errorLabel == nil, !didFinish else { return }
        LaunchLog.write("[vlc] fatal: \(text) state=\(String(describing: player.state)) "
                        + "url=\(mediaUrl.absoluteString.prefix(160))")
        let l = UILabel()
        l.text = "\(text)\n\n点一下屏幕回到播放页"
        l.numberOfLines = 0
        l.textAlignment = .center
        l.textColor = .white
        l.font = .systemFont(ofSize: 16)
        l.backgroundColor = UIColor.black.withAlphaComponent(0.75)
        l.layer.cornerRadius = 10
        l.clipsToBounds = true
        l.frame = CGRect(x: 32, y: view.bounds.midY - 64,
                         width: max(120, view.bounds.width - 64), height: 128)
        l.autoresizingMask = [.flexibleWidth, .flexibleTopMargin, .flexibleBottomMargin]
        view.addSubview(l)
        errorLabel = l
        showControls(keepVisible: true)
    }

    private func durationMs() -> Double? {
        // VLCKit 3.7 的 media.length 是**非可选** VLCTime（只有 media 可选）——
        // 写成 `length?.` 会编译失败（2026-11-27 CI 实测）。
        guard let len = player.media?.length.intValue, len > 0 else { return nil }
        return Double(len)
    }

    private static func clock(_ ms: Double) -> String {
        let s = max(0, Int(ms / 1000))
        return String(format: "%02d:%02d", s / 60, s % 60)
    }

    // MARK: - 交互

    @objc private func togglePlay() {
        if player.isPlaying {
            userPaused = true
            player.pause()
            AudioSession.setPlaying(false)
        } else {
            userPaused = false
            player.play()
            // 用户显式续播：把"在播"重新告诉壳（进后台才激活音频会话）
            AudioSession.setPlaying(true)
        }
        showControls()
    }

    @objc private func sliderTouchDown() {
        seeking = true
        showControls(keepVisible: true)
    }

    @objc private func sliderChanged() {
        guard let total = durationMs(), total > 0 else { return }
        timeLabel.text = "\(Self.clock(Double(slider.value) * total)) / \(Self.clock(total))"
    }

    @objc private func sliderTouchUp() {
        player.position = slider.value
        seeking = false
        showControls()
    }

    @objc private func toggleOrientation() {
        /*
         * 强转另一种方向：本控制器只声明 landscape，切回竖屏只能交给壳的
         * ShellOrientation（WKWebView 的 lock 在非全屏上下文一律拒绝）。
         */
        ShellOrientation.apply(currentOrientation() == .landscapeRight
                               || currentOrientation() == .landscapeLeft ? "portrait" : "landscape")
    }

    private func currentOrientation() -> UIInterfaceOrientation {
        view.window?.windowScene?.interfaceOrientation ?? .landscapeRight
    }

    @objc private func toggleControls() {
        // 起播失败后：点一下直接回播放页（这个界面上不能再有"只能强退"的路径）
        if errorLabel != nil {
            finish(ended: false)
            return
        }
        /*
         * web 控制层模式：控制层永远可见（2026-11-27 真机回归后不再 isHidden），
         * 点击全部交给 web 自己处理（它负责控制栏的显隐与浮层）。这里的 isHidden
         * 守卫保留成"万一以后又重新隐藏"时的安全网。
         */
        if useWebControls {
            guard let w = controlWebView, w.isHidden else { return }
            w.isHidden = false
            w.evaluateJavaScript("window.__meowVlcShowBar&&window.__meowVlcShowBar()",
                                 completionHandler: nil)
            return
        }
        if controlsVisible { hideControls() } else { showControls() }
    }

    private func showControls(keepVisible: Bool = false) {
        controlsVisible = true
        controls.isHidden = false
        UIView.animate(withDuration: 0.18) { self.controls.alpha = 1 }
        if !keepVisible { scheduleHideControls() }
    }

    private func hideControls() {
        controlsVisible = false
        UIView.animate(withDuration: 0.2) { self.controls.alpha = 0 } completion: { _ in
            if !self.controlsVisible { self.controls.isHidden = true }
        }
    }

    private func scheduleHideControls() {
        hideControlsWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.player.isPlaying, !self.seeking else { return }
            self.hideControls()
        }
        hideControlsWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 4, execute: work)
    }

    @objc private func closePlayer() {
        // 内置控制条的返回键同样是"返回上一页"，不是"停在已退出面板"
        pendingNav = Self.backMarker
        finish(ended: false)
    }

    // MARK: - 收尾

    private func finish(ended: Bool) {
        guard !didFinish else { return }
        didFinish = true
        tickTimer?.invalidate()
        tickTimer = nil
        hideControlsWork?.cancel()

        let pos = Double(player.time.intValue)
        let total = durationMs() ?? 0
        player.stop()
        // 停了就别再占着音频会话（.playback 非混音，会压住别的 App）
        AudioSession.setPlaying(false)
        NotificationCenter.default.removeObserver(
            self, name: UIApplication.didBecomeActiveNotification, object: nil)

        // 竖屏还给 web（web 播放页是竖屏优先，恢复横屏意图由它自己决定）
        ShellOrientation.apply("portrait")

        let nav = Self.jsQuote(pendingNav)
        let js = "window.__meowVlcClosed&&window.__meowVlcClosed({"
            + "\"positionSec\":\(Int(pos / 1000)),"
            + "\"durationSec\":\(Int(total / 1000)),"
            + "\"ended\":\(ended),"
            + "\"nav\":\"\(nav)\"})"
        pendingNav = ""
        hostWebView?.evaluateJavaScript(js, completionHandler: nil)
        LaunchLog.write("[vlc] close pos=\(Int(pos / 1000))s ended=\(ended)")

        dismiss(animated: true)
    }

    // MARK: - 入口

    /**
     * 从 web 桥拉起。返回 false = 参数不合法或没有可用的宿主控制器。
     *
     * <p>注意 WKScriptMessageHandler 是异步通道，web 侧拿不到同步返回值，
     * 它靠 `meowIos.nativeVlc === true` 做 feature detect。
     */
    @discardableResult
    static func present(from webView: WKWebView?, dict: [String: Any]) -> Bool {
        guard let raw = dict["url"] as? String, let url = URL(string: raw),
              let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https"
        else {
            LaunchLog.write("[vlc] bad url: \(dict["url"] ?? "nil")")
            return false
        }
        let headers = Self.parseHeaders(dict["headers"] as? String ?? "")
        var meta: [String: Any] = [:]
        if let rawMeta = dict["metaJson"] as? String, !rawMeta.isEmpty,
           let data = rawMeta.data(using: .utf8),
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            meta = obj
        }
        guard let host = topPresenter() else {
            LaunchLog.write("[vlc] no presenter")
            return false
        }
        let vc = NativeVlcPlayer(
            url: url,
            title: dict["title"] as? String ?? "",
            headers: headers,
            positionMs: (dict["positionMs"] as? NSNumber)?.intValue ?? 0,
            danmakuUrl: dict["danmakuUrl"] as? String,
            meta: meta,
            host: webView,
            controlURL: Self.controlURL(from: webView?.url),
            apiBase: dict["apiBase"] as? String)
        /*
         * 原生播放是横屏、自带控制条：先把壳的屏幕锁打开，否则
         * supportedInterfaceOrientations 会被 AppDelegate 的全局设置压住。
         */
        ShellOrientation.apply("landscape")
        host.present(vc, animated: true)
        return true
    }

    /**
     * 桥上的 header 编码是 `k=v&k=v`（值 percent-encoded），与 TV 的
     * encodeHeaders 同构。`present` 与换片 `open` 共用这一份。
     */
    private static func parseHeaders(_ headerRaw: String) -> [String: String] {
        var headers: [String: String] = [:]
        for pair in headerRaw.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard kv.count == 2 else { continue }
            let k = String(kv[0]).removingPercentEncoding ?? String(kv[0])
            let v = String(kv[1]).removingPercentEncoding ?? String(kv[1])
            if !k.isEmpty { headers[k] = v }
        }
        return headers
    }

    /**
     * 控制层页面地址 = 父页面当前地址 + `native=1`。
     *
     * <p>父页面的 hash 里是 `#/play?site=..&vod=..&ep=..`，控制层要拿到同一组
     * 参数（剧集列表/线路列表/弹幕都要靠它重新拉）。所以直接复用父地址，
     * 只在 hash 的 query 后面追加 native=1 —— 不另造一套参数传递。
     */
    private static func controlURL(from parent: URL?) -> URL? {
        guard let p = parent else { return nil }
        var s = p.absoluteString
        guard let r = s.range(of: "#") else {
            return URL(string: s + (s.contains("?") ? "&" : "?") + "native=1")
        }
        var hash = String(s[r.lowerBound...])
        hash += hash.contains("?") ? "&native=1" : "?native=1"
        s = String(s[..<r.lowerBound]) + hash
        return URL(string: s)
    }

    private static func topPresenter() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
        let keyWindow = scenes
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }
        var top = keyWindow?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
