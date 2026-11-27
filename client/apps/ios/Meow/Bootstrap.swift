import Foundation
// 改后端地址要重建 WKUserScript（见 reloadWebWithCurrentBackend）
import WebKit

/// 启动顺序：
///  1. 准备沙盒数据目录（首次启动播种空配置）
///  2. 在 9979 起静态文件服务（web/dist）
///  3. 真机：静态链接的 OpenJDK 9 Zero 起在进程内，ApiServer 监听 9978
///     模拟器：不内嵌 JVM，用 `--api-base` 指向 macOS 上的 ApiServer 做 UI 验证
///  4. WebView 打开 http://127.0.0.1:9979/，注入 localStorage['meow.api']
@MainActor
final class Bootstrap: ObservableObject {
    @Published var webURL: URL?
    @Published var webReady = false
    /// 只有"连界面都起不来"才算失败；进度不再往界面上写文字 ——
    /// 开屏页（品牌图标）负责盖住整个启动过程（2026-11-24 用户要求）。
    @Published var failed = false
    @Published var failText = ""

    /// 不参与观察：webURL 变化时 RootView 已经会重建 WebView。
    var bootScript = ""

    /// 端内后端地址（JVM 起在 9978；起不来为空串）。
    /// ★ 必须在**用户覆盖之前**记下：改地址后要能答出「恢复端内」是哪个地址。
    static var embeddedBase = ""
    /// 启动参数 `--site`（重载重建注入脚本时要带上，否则深链丢失）
    static var launchSite = ""

    private let staticServer = StaticServer()
    private var started = false
    private var staticWatchdog: Timer?
    /** Python/pyrt 看门狗（有 .py 站点时保活；与 node 的 ensureServing 对齐） */
    private var pyWatchdog: Timer?
    /// 静态服务重启后自动重试加载过没有（限次，防循环）
    private var autoRetriedAfterRestart = false

    nonisolated init() {}

    func start() {
        guard !started else { return }
        started = true
        Task { await boot() }
    }

    /** 开屏页上的「重试」：整条启动链路重跑（静态服务先停掉，端口才回得来）。 */
    func retry() {
        guard failed else { return }
        failed = false
        failText = ""
        webURL = nil
        webReady = false
        staticServer.stop()
        started = false
        start()
    }

    private func fail(_ text: String) {
        failed = true
        failText = text
        LaunchLog.write("boot failed: \(text)")
    }

    private func boot() async {
        LaunchLog.write("boot start")
        /*
         * ★ 启动时**不挂** MPVolumeView（2026-11-26 用户报「任意页面按音量键
         * 都没有系统音量条」）：MPVolumeView 只要在窗口层级里就会全局抑制
         * 系统音量 HUD。现在由播放页/直播页通过 meowIos.volumeHud(true) 挂载，
         * 离开时摘掉，其它页面恢复系统音量条。
         */
        let fm = FileManager.default
        let docs = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dataDir = docs.appendingPathComponent("gateway", isDirectory: true)
        try? fm.createDirectory(at: dataDir, withIntermediateDirectories: true)
        LaunchLog.write("data dir ready: \(dataDir.path)")

        // 远程调试要求屏幕别自动锁：息屏 = App 挂起 = 网关端口消失（重启后自动恢复上次选择）
        ScreenAwake.restore()

        // 首次启动：拷一份空配置（0 站）。用户从设置页导入源包后覆盖。
        let config = dataDir.appendingPathComponent("config.sites.json")
        if !fm.fileExists(atPath: config.path),
           let seed = Bundle.main.url(forResource: "config.sites.example", withExtension: "json") {
            try? fm.copyItem(at: seed, to: config)
        }

        guard let webRoot = Bundle.main.resourceURL?.appendingPathComponent("web"),
              fm.fileExists(atPath: webRoot.appendingPathComponent("index.html").path) else {
            fail("缺少 web 资源（先构建 client/apps/web）")
            return
        }

        do {
            try await staticServer.start(root: webRoot, port: 9979)
            LaunchLog.write("static server on 9979")
        } catch {
            fail("静态服务启动失败：\(error.localizedDescription)")
            return
        }

        var base = ""
        #if targetEnvironment(simulator)
        base = Self.argValue("--api-base") ?? ""
        #else
        // ★ JVM 不可用时**不要 return**：界面（网页）照常加载，只是本地网关不可用。
        //   之前这里 return 会让 webURL 永远是 nil → 用户只看到转圈 + 一句提示，
        //   连首页/设置页都进不去（C 包实测）。
        if JvmBridge.isAvailable() {
            let javaHome = Bundle.main.resourceURL!.appendingPathComponent("openjdk").path
            let classPath = Self.hostClassPath()
            LaunchLog.write("jvm start (classpath \(classPath.count) chars)")
            let ok: Bool = await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
                JvmBridge.start(withJavaHome: javaHome,
                                classPath: classPath,
                                dataDir: dataDir.path,
                                port: 9978) { started, why in
                    LaunchLog.write("jvm start returned ok=\(started) why=\(why ?? "-")")
                    continuation.resume(returning: started)
                }
            }
            if ok {
                base = "http://127.0.0.1:9978"
                if !(await Self.waitHealth(base: base, attempts: 120)) {
                    // 不算致命：网页照样能进，首页自己会显示「后端未连接」并提供刷新
                    LaunchLog.write("health wait failed" + Self.logTailHint())
                } else {
                    LaunchLog.write("health ok")
                }
            } else {
                LaunchLog.write("jvm start failed" + Self.logTailHint())
            }
        } else {
            LaunchLog.write("jvm resources missing; loading web without backend")
        }
        #endif

        /*
         * 用户指定的后端地址（2026-11-27，对齐 TV 的「后端地址可配」）。
         *
         * 端内 JVM 仍然照常启动（页面/静态服务/弹幕载荷都依赖它这条链路），
         * 但**桥注入的 apiBase 以用户配置为准** —— resolveBase() 在 iOS 只认
         * 桥，所以这里换值就等于整页换后端。空串 = 用端内后端。
         */
        Self.embeddedBase = base
        let override = BackendOverride.current()
        if !override.isEmpty {
            base = override
            LaunchLog.write("backend override: \(override)")
        }

        Self.launchSite = Self.argValue("--site") ?? ""
        bootScript = Self.makeBootScript(base: base, site: Self.launchSite)
        webURL = URL(string: "http://127.0.0.1:9979/")
        LaunchLog.write("web url set (base=\(base.isEmpty ? "none" : base))")
        startStaticWatchdog()
        // 内存诊断：写 gateway/mem.json，宿主 /api/health 与 AI 工具直接读
        MemoryProbe.start()

        #if !targetEnvironment(simulator)
        /*
         * 端内弹幕载荷（nodejs-mobile + danmu_api）：后台起，不挡界面。
         * 起不来只写日志 —— JVM 宿主会继续用内置弹幕源。
         */
        NodeBridge.startIfPossible()
        startPythonWatchdog()
        #endif
    }

    /// 静态服务 15s 探活：失效就在原端口重建（见 StaticServer.ensureServing）。
    ///
    /// <p>2026-11-25 真机：切后台回来后 Java 网关被看门狗救活了，但 9979 的
    /// NWListener 没人管 —— 表现是「API 正常，点搜索/剧集没反应」（懒加载 chunk
    /// 全拉不到）。这个定时器把静态服务也纳入保活。
    private func startStaticWatchdog() {
        staticWatchdog?.invalidate()
        staticWatchdog = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in
            guard let self else { return }
            Task { await self.staticServer.ensureServing() }
        }
    }

    /// Python 看门狗：20s 一次。没有 `.py` 站点时 ensureRunning 直接返回，
    /// 因此「只用 jar/js 的用户」不会被强行拉起源码解释器；导入含 py 的配置后
    /// 下一次 tick 就会启动，不需要重启 App。
    private func startPythonWatchdog() {
        pyWatchdog?.invalidate()
        pyWatchdog = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { _ in
            PythonRuntime.ensureRunning()
        }
    }

    /// 回前台：立刻补一次静态服务探活 + 唤醒 Java 看门狗 + node 弹幕服务探活。
    func appBecameActive() {
        LaunchLog.write("scene active; ensure static server + kick watchdog")
        JvmBridge.kickWatchdog()
        // node 的监听 socket 也会被挂起回收：探活失败就摘掉 danmaku-host.txt，
        // 让后端回退内置弹幕源（见 NodeBridge.ensureServing 的注释）。
        NodeBridge.ensureServing()
        /*
         * 回前台再对齐一次 `policy.jsRuntime`（2026-11-27 真机）：
         * 挂起会回收 node 的监听，policy 里的地址不会自己失效 —— 不对齐的话
         * `.js` 源会一直打一个死端口（每源等满 8 秒），设置页也显示「Node 不可用」。
         */
        NodeBridge.alignJsRuntime()
        // pyrt 同理：挂起会回收监听 socket/线程，探活失败就在已初始化的
        // 解释器里重跑 pyrt.py；配置里没有 .py 站点时这里直接返回。
        #if !targetEnvironment(simulator)
        PythonRuntime.ensureRunning()
        #endif
        Task {
            await staticServer.ensureServing()
            /*
             * A5（2026-11-25 日志）：静态服务重启后，如果页面**从没加载成功**
             * 且停在失败态，自动重试一次（限次防循环）——用户不必去点开屏页。
             * 已经加载成功的 SPA 不受静态服务重启影响，不重载（不打断播放）。
             */
            if failed && !webReady && !autoRetriedAfterRestart {
                autoRetriedAfterRestart = true
                LaunchLog.write("auto retry web load after static restart")
                retry()
            }
        }
    }

    // MARK: - helpers

    private static func hostClassPath() -> String {
        guard let res = Bundle.main.resourceURL else { return "" }
        var parts = [res.appendingPathComponent("jvm/host.jar").path]
        let libs = res.appendingPathComponent("jvm/libs", isDirectory: true)
        if let files = try? FileManager.default.contentsOfDirectory(at: libs,
                                                                    includingPropertiesForKeys: nil) {
            for f in files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent })
            where f.pathExtension.lowercased() == "jar" {
                parts.append(f.path)
            }
        }
        return parts.joined(separator: ":")
    }

    /// 当前生效的后端地址：用户覆盖优先，否则端内。
    static func effectiveBase() -> String {
        let o = BackendOverride.current()
        return o.isEmpty ? embeddedBase : o
    }

    /**
     * 改完后端地址后**用新 apiBase 重建注入脚本并重载**。

     * <p>为什么不让页面自己 `location.reload()`（2026-11-27 真机实测）：
     * boot 脚本是 `makeUIView` 时以 `WKUserScript(.atDocumentStart)` 注册进去的，
     * reload 只会重跑**旧**脚本 —— 注入的 `apiBase` 仍是启动那一刻的值，
     * 用户看到的就是「地址刚填上又被刷回 127.0.0.1:9978」。
     * 所以必须先换掉注册的脚本、再 reload。
     */
    static func reloadWebWithCurrentBackend() {
        guard let wv = WebViewRegistry.view else { return }
        let base = effectiveBase()
        let script = makeBootScript(base: base, site: launchSite)
        let uc = wv.configuration.userContentController
        uc.removeAllUserScripts()
        uc.addUserScript(WKUserScript(source: script,
                                      injectionTime: .atDocumentStart,
                                      forMainFrameOnly: false))
        LaunchLog.write("backend reload: base=\(base.isEmpty ? "none" : base)")
        wv.reload()
    }

    /**
     * 切回端内后端：**先把端内运行时拉齐，再重载页面**。

     * <p>为什么不能直接 reload（2026-11-27 真机）：node 跑在 App 进程里、
     * `node_start` 只能调一次；iOS 挂起会回收它的监听。切回端内后如果不管，
     * `policy.jsRuntime` 还是死地址 —— 设置页显示「Node 不可用 / CPython 未启用」，
     * `.js` 源每源等满 8 秒。
     *
     * <p>这里做三件事（都是幂等/带探活）：
     *   1. `startIfPossible()`：node 没起过就起（起过的这里什么都不做）；
     *   2. `PythonRuntime.ensureRunning()`：有 .py 站点且 pyrt 死了，在已初始化的
     *      解释器里重跑 pyrt.py（这条能真正救回 CPython）；
     *   3. `alignJsRuntime(waitSeconds: 6)`：给刚唤醒的 drpy 最多 6 秒复活时间，
     *      然后按真实活体重写 `jsRuntime`。
     * 对齐完成后再 reload，页面首次查询就会拿到（`?probe=1` 强制重探的）真实状态。
     */
    static func restoreEmbeddedRuntimeThenReload() {
        DispatchQueue.global(qos: .utility).async {
            NodeBridge.startIfPossible()
            #if !targetEnvironment(simulator)
            PythonRuntime.ensureRunning()
            #endif
            NodeBridge.alignJsRuntime(waitSeconds: 6)
            Task { @MainActor in reloadWebWithCurrentBackend() }
        }
    }

    private static func makeBootScript(base: String, site: String) -> String {
        var js: [String] = []
        // 壳身份：前端 compat/shell.ts 只认桥对象（不看 UA —— Safari 打开同一页面
        // 仍必须是 web 壳）。桥在 WKUserScript(.atDocumentStart) 注入，早于页面脚本。
        js.append("window.meowIos={platform:'ios',version:'\(Self.appVersion())',"
                  + "build:'\(Self.buildId())',"
                  + "apiBase:'\(Self.jsString(base))',"
                  + "runtime:'OpenJDK 9 Zero（静态链接 · -Xint）'}")
        /*
         * 后端地址可配（2026-11-27）：`apiBackend` 是当前生效值（含用户覆盖），
         * `setApiBackend(url)` 写入 UserDefaults，"" = 恢复端内后端。
         * 落盘后必须整页 reload 才生效（aria 的桥是文档级注入）。
         */
        js.append("window.meowIos.apiBackend='\(Self.jsString(base))'")
        js.append("window.meowIos.setApiBackend=function(u){try{"
                  + "window.webkit.messageHandlers.meowIos.postMessage({op:'apiBackend',value:String(u||'')});"
                  + "return true}catch(e){return false}}")
        /*
         * 原生兜底播放桥（2026-11-27）：web 的 <video> 播不了 MKV/AVI/FLV/DTS
         * 时交给端内 VLCKit。`nativeVlc` 是**同步的 feature detect** ——
         * postMessage 是单向异步，拿不到返回值，所以能力探测必须靠注入的字段：
         * 老壳没有它 → 前端 hasIosNativePlayer() 为 false → 退回原来的提示。
         */
        js.append("window.meowIos.nativeVlc=true")
        js.append("window.meowIos.openNativeVlc=function(o){try{"
                  + "window.webkit.messageHandlers.meowIos.postMessage(Object.assign({op:'nativeVlc'},o||{}))"
                  + "}catch(e){return false}return true}")
        /*
         * 转屏桥：播放页全屏要横屏。WKWebView 里没有 Screen Orientation API 的
         * lock（非全屏上下文一律拒绝），只能让原生转；前端 shellSetOrientation
         * 会同时试 meowTv / meowIos 两个桥。
         */
        js.append("window.meowIos.setOrientation=function(m){try{"
                  + "window.webkit.messageHandlers.meowIos.postMessage({op:'orientation',value:String(m||'')})"
                  + "}catch(e){}}")
        /*
         * 保持亮屏桥：iOS 息屏 = App 挂起 = 网关端口消失，远程调试直接断。
         * 界面上给一个开关（维护 → 调试），状态持久化在 UserDefaults。
         */
        js.append("window.meowIos.keepAwake=\(ScreenAwake.isOn ? "true" : "false")")
        js.append("window.meowIos.setKeepAwake=function(on){try{"
                  + "window.webkit.messageHandlers.meowIos.postMessage({op:'keepAwake',value:!!on})"
                  + "}catch(e){}}")
        /*
         * 系统音量桥：网页改不了 iOS 音量，由原生写 MPVolumeView。
         * `volume` 是启动时的快照（-1 = 读不到），setVolume 是单向设置；
         * 前端自己维护这个值做连续性，不需要回读（系统侧变化由侧键产生，
         * 下次手势的起点允许有偏差，比异步回读简单且不会跳）。
         */
        js.append("window.meowIos.volume=\(SystemVolume.current())")
        js.append("window.meowIos.setVolume=function(v){try{"
                  + "window.webkit.messageHandlers.meowIos.postMessage({op:'volume',value:Number(v)||0})"
                  + "}catch(e){}}")
        /*
         * 系统亮度桥：竖滑右区（iOS 是左区亮度）调真实屏幕亮度。
         * 传 -1 表示还原进入播放页前的亮度（页面销毁时调）。
         */
        /*
         * 音量 HUD 接管开关：播放/直播页挂 MPVolumeView（自己的浮层显示百分比，
         * 系统 HUD 被抑制）；离开时摘掉，把系统音量条还给其它页面。
         */
        js.append("window.meowIos.volumeHud=function(on){try{"
                  + "window.webkit.messageHandlers.meowIos.postMessage({op:'volumeHud',value:!!on})"
                  + "}catch(e){}}")
        js.append("window.meowIos.brightness=\(SystemBrightness.current())")
        js.append("window.meowIos.getBrightness=function(){return window.meowIos.brightness||0}")
        js.append("window.meowIos.setBrightness=function(v){try{"
                  + "window.webkit.messageHandlers.meowIos.postMessage({op:'brightness',value:Number(v)})"
                  + "}catch(e){}}")
        /*
         * 边缘返回手势开关：播放页/直播页关掉（横滑调进度会抢手势），
         * 其它页面开着。
         */
        js.append("window.meowIos.setNavGesture=function(on){try{"
                  + "window.webkit.messageHandlers.meowIos.postMessage({op:'navGesture',value:!!on})"
                  + "}catch(e){}}")
        /*
         * 播放倒计时：原生层计时（JS setInterval 切后台会被挂起）。
         * 秒数传 0 = 取消；到点原生 eval `window.__meowPowerFire`。
         */
        js.append("window.meowIos.powerTimer=function(sec){try{"
                  + "window.webkit.messageHandlers.meowIos.postMessage({op:'powerTimer',value:Number(sec)||0})"
                  + "}catch(e){}}")
        /*
         * 系统通知：导入源包/配置这种后台长任务完成时提醒（用户切走了也能看到）。
         * 首次调用时原生会弹权限框；拒绝后静默失败。
         */
        js.append("window.meowIos.notify=function(t,b){try{"
                  + "window.webkit.messageHandlers.meowIos.postMessage({op:'notify',"
                  + "title:String(t||''),body:String(b||'')})"
                  + "}catch(e){}}")
        /*
         * 播放状态桥：进后台时原生据此决定是否激活 AVAudioSession。
         * 只报状态，不要求在 play 里 setActive —— 那会打断 WebKit（实测 1s 自停）。
         */
        js.append("window.meowIos.setPlaying=function(on){try{"
                  + "window.webkit.messageHandlers.meowIos.postMessage({op:'playing',value:!!on})"
                  + "}catch(e){}}")
        if base.isEmpty {
            js.append("localStorage.removeItem('meow.api');localStorage.removeItem('meow.api.scope')")
        } else {
            let escaped = base
                .replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "'", with: "\\'")
            // 前端 resolveBase()：裸 meow.api 需要 scope 标记才可信（与桌面端同判据）。
            js.append("localStorage.setItem('meow.api','\(escaped)');" +
                      "localStorage.setItem('meow.api.scope','ios')")
        }
        if !site.isEmpty {
            let escapedSite = site
                .replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "'", with: "\\'")
            js.append("localStorage.setItem('meow.site','\(escapedSite)')")
        }
        return "try{" + js.joined(separator: ";") + "}catch(e){}"
    }

    /// 注入到页面里的字符串要转义单引号（值来自 Bundle，不来自用户输入，但统一处理）。
    private static func jsString(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\")
         .replacingOccurrences(of: "'", with: "\\'")
    }

    private static func appVersion() -> String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
    }

    /// 构建标识（CI 注入的 git SHA + 时间）：设置页显示它，用户装完能确认是哪个包。
    private static func buildId() -> String {
        Bundle.main.infoDictionary?["MeowBuild"] as? String ?? "dev"
    }

    /// 启动失败时把 JVM 日志与启动日志的尾部带出来（否则用户只看到「闪退」两个字）。
    private static func logTailHint() -> String {
        var parts: [String] = []
        if let jvm = JvmBridge.logTail(), !jvm.isEmpty { parts.append(jvm) }
        let launch = LaunchLog.tail(12)
        if !launch.isEmpty { parts.append(launch) }
        return parts.isEmpty ? "" : "\n\n" + parts.joined(separator: "\n----\n")
    }

    private static func argValue(_ key: String) -> String? {
        let args = ProcessInfo.processInfo.arguments
        for (i, a) in args.enumerated() {
            if a == key, i + 1 < args.count { return args[i + 1] }
            if a.hasPrefix(key + "=") { return String(a.dropFirst(key.count + 1)) }
        }
        return nil
    }

    private static func waitHealth(base: String, attempts: Int) async -> Bool {
        guard let url = URL(string: base + "/api/health") else { return false }
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 2
        cfg.timeoutIntervalForResource = 2
        let session = URLSession(configuration: cfg)
        for _ in 0..<attempts {
            do {
                let (data, resp) = try await session.data(from: url)
                if let http = resp as? HTTPURLResponse, http.statusCode == 200, !data.isEmpty {
                    return true
                }
            } catch {
                // JVM 还没监听：继续等
            }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
        return false
    }
}

/**
 * 用户手填的后端地址（iOS 的「后端地址」设置）。
 *
 * <p>为什么放在 UserDefaults 而不是 localStorage：BootScript 是
 * `.atDocumentStart` 注入的，页面 JS 还没跑 —— 启动时必须由壳先读出这份值，
 * 才能决定 `meowIos.apiBase` 指向端内 JVM 还是外部后端。
 *
 * <p>空串 = 用端内后端（默认）。写 "" 即清除覆盖。改完由设置页 location.reload()
 * 让桥重新注入（新值在下次 makeBootScript 生效）。
 */
enum BackendOverride {
    private static let key = "meow.api.base"

    static func current() -> String {
        let v = UserDefaults.standard.string(forKey: key) ?? ""
        return v.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 写入覆盖地址；非法协议返回 false（调用方不要拿它去重载网页）。
    @discardableResult
    static func set(_ raw: String) -> Bool {
        let v = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        // 只收 http(s)：file:// / about: 之类写进去会让整页白屏
        let ok = v.isEmpty || v.lowercased().hasPrefix("http://") || v.lowercased().hasPrefix("https://")
        guard ok else { return false }
        if v.isEmpty { UserDefaults.standard.removeObject(forKey: key) }
        else { UserDefaults.standard.set(v, forKey: key) }
        LaunchLog.write("backend override -> \(v.isEmpty ? "(embedded)" : v)")
        return true
    }
}
