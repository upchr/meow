import Foundation

#if !targetEnvironment(simulator)

/// 端内 Node 运行时（nodejs-mobile）—— 对齐 TV 的「运行时模块 + danmu_api 载荷」。
///
/// <p>形态不同、功能相同：TV 是一个独立的 `:node` 进程，iOS 不允许 fork/exec，
/// 只能把 node 以**库**的形式跑在 App 进程里（nodejs-mobile 的 V8 启动即关 JIT）。
///
/// <p>载荷（danmu_api 的 ~40MB 运行期文件）打进 IPA，首次启动拷到 Documents，
/// 之后直接跑那份；包里的版本升级（`.payload-version` 变化）会重拷。
///
/// <p>**失败不影响主流程**：没载荷 / node 起不来 / 端口没通 → 只写日志，
/// JVM 宿主继续用内置弹幕源（弹弹play + 镜像）。
enum NodeBridge {
    /// 固定端口：TV 的 manifest 默认 9321，这里挪到 19321 避免与 PC 插件/别的壳撞。
    private static let port = 19321

    private static let lock = NSLock()
    private static var started = false
    /// 稳定计时只排一次：回前台补写 host 文件不该再起一个 5 分钟线程
    private static var stableTimerScheduled = false

    /// nodejs-mobile 唯一对外的 C 接口：`int node_start(int argc, char *argv[])`。
    /// 用 @_silgen_name 直接声明，避免依赖框架的 modulemap。
    @_silgen_name("node_start")
    private static func node_start(_ argc: Int32,
                                   _ argv: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?) -> Int32

    /// JS 源运行时（drpy）端口。与弹幕端口分开：宿主用 `GET {jsRuntime}/health`
    /// 探活（见 ApiServer 的 scriptRuntimeUsable），不能和弹幕的根路径混用。
    static let drpyPort = 19330

    /// 在后台起 node；只生效一次。
    static func startIfPossible() {
        DispatchQueue.global(qos: .utility).async {
            lock.lock()
            if started {
                lock.unlock()
                return
            }
            started = true
            lock.unlock()
            if crashLooping() { return }
            bootstrap()
        }
    }

    // MARK: - 崩溃环保护
    //
    // node 跑在 App 进程里，任何带走 node 的问题（native abort / 我们没拦住的
    // 退出）都会让 App 一起退。真机上出现过「启动几秒后自动退出」：如果每次
    // 启动都照起 node，用户会陷进「打开就退」的死循环，连设置页都进不去。
    // 这里记「上次启动时间 + 连续快速重启次数」，>=2 次就本轮跳过 node
    // （日志里留一行），App 至少能正常用，弹幕走内置源。

    private static let bootAtKey = "meow.node.bootAt"
    private static let bootCountKey = "meow.node.bootCount"
    /// 上一次启动是否成功跑出过健康服务（写 host 文件即算）。
    /// 只有「上次没健康又很快重启」才可能是崩溃环 —— 手动重启不该被误判。
    private static let wasHealthyKey = "meow.node.wasHealthy"
    /// 两次启动间隔小于这个值才算「快速重启」
    private static let crashWindow: TimeInterval = 30

    private static func crashLooping() -> Bool {
        let d = UserDefaults.standard
        let now = Date().timeIntervalSince1970
        let last = d.double(forKey: bootAtKey)
        let lastHealthy = d.bool(forKey: wasHealthyKey)
        var count = d.integer(forKey: bootCountKey)
        /*
         * ★ 只在「上一次启动没健康、且 30 秒内又启动」时计数（2026-11-25 用户实测）：
         * 旧逻辑任何 30 秒内的第二次启动都跳过 node —— 用户手动重启 App 一次，
         * 本次弹幕聚合就整个不可用了，而且只留一行 launch.log。
         * 健康过再重启说明 node 本身没问题，不该跳。
         */
        if last > 0, now - last < crashWindow, !lastHealthy {
            count += 1
        } else {
            count = 1
        }
        d.set(now, forKey: bootAtKey)
        d.set(count, forKey: bootCountKey)
        // 本次还没证明健康：真崩了下次启动才不会漏计
        d.set(false, forKey: wasHealthyKey)
        if count >= 2 {
            LaunchLog.write("[node] rapid restart without healthy; skip node this launch")
            return true
        }
        return false
    }

    /// 活了 5 分钟算「稳定」，清掉崩溃环计数（下次正常起）。
    private static func scheduleStableReset() {
        let t = Thread {
            Thread.sleep(forTimeInterval: 300)
            UserDefaults.standard.set(0, forKey: bootCountKey)
        }
        t.name = "meow-node-stable"
        t.start()
    }

    private static func bootstrap() {
        guard let dir = installPayloadIfNeeded() else { return }
        /*
         * JS 源载荷（drpy）：与弹幕在**同一个 node 进程**里跑 —— nodejs-mobile
         * 一个进程只能 node_start 一次，入口包装里串起来（见 writeEntryWrapper）。
         * 装不起来只记日志：弹幕照旧，JS 源退回"jsRuntime 未配置"。
         */
        if let drpy = installDrpyIfNeeded() {
            setenv("MEOW_DRPY_DIR", drpy.path, 1)
            setenv("DRPY_PORT", "\(drpyPort)", 1)
            setenv("DRPY_BIND", "127.0.0.1", 1)
        }
        let script = dir.appendingPathComponent("danmu_api/server.js").path
        guard FileManager.default.fileExists(atPath: script) else {
            LaunchLog.write("[node] server.js missing")
            return
        }
        // 进程级环境变量：danmu_api 读的就是这些（与 TV 的 manifest 一致）
        setenv("DANMU_API_PORT", "\(port)", 1)
        setenv("DANMU_API_BIND", "127.0.0.1", 1)
        setenv("DANMU_API_PROXY_PORT", "0", 1)
        setenv("SOURCE_ORDER", "360,bilibili,tencent,iqiyi,youku,mango,migu,sohu", 1)
        setenv("NODE_ENV", "production", 1)
        chdir(dir.path)

        /*
         * ★ 必须用入口包装，不能直接跑 server.js（2026-11-24 真机实测：启动
         *   几秒后 App「自动退出」——node 的 stderr 里是
         *   `ReferenceError: WebAssembly is not defined`（nodejs-mobile 的 V8 没编
         *   WASM，内置 fetch/undici 一初始化就抛），未捕获 → node 退出 →
         *   因为 node 跑在 App 进程里，App 跟着退）。
         *
         * 包装层做三件事：
         *   1) 把全局 fetch 换成 node-fetch（不依赖 WASM）；
         *   2) 拦下 uncaughtException / unhandledRejection，只记日志；
         *   3) 屏蔽 process.exit —— 任何 JS 侧退出都不许带走 App。
         */
        let entry = writeEntryWrapper(in: dir, serverScript: script)
        let entryPath = entry?.path ?? script

        let nodeThread = Thread {
            LaunchLog.write("[node] node_start begin")
            // argv 必须活到 node 退出：malloc 出来不释放（进程级只有一个 node）
            let argv: [UnsafeMutablePointer<CChar>?] = [
                strdup("node"),
                strdup("--unhandled-rejections=warn"),
                /*
                 * 关掉实验性内置 fetch（undici）。nodejs-mobile 的 V8 没编 WASM，
                 * undici 的 lazyllhttp 一初始化就抛 `WebAssembly is not defined`。
                 * 入口虽然把 fetch 换成了 node-fetch，但 Node 18 的 globalThis.fetch
                 * 是惰性 getter，任何一次误触（含读取 Headers/Request/Response）
                 * 都会把 undici 拉起来。从源头关掉最稳（2026-11-25 日志 A8）。
                 */
                strdup("--no-experimental-fetch"),
                // 与 JVM(512m)、WKWebView 同住一个进程：V8 默认堆上限很大（~1.5G），
                // 让它按需长会挤压 WebKit 的图像解码，真机表现是「图片突然全碎」。
                strdup("--max-old-space-size=160"),
                strdup(entryPath),
                nil,
            ]
            var ptr = argv
            let rc = ptr.withUnsafeMutableBufferPointer { buf in
                node_start(Int32(buf.count - 1), buf.baseAddress)
            }
            LaunchLog.write("[node] node_start returned \(rc)")
        }
        nodeThread.name = "meow-node"
        nodeThread.stackSize = 8 * 1024 * 1024
        nodeThread.start()

        let waiter = Thread { waitHealthyThenInject() }
        waiter.name = "meow-node-health"
        waiter.start()
    }

    /// 写入口包装 `meow-entry.cjs`（每次覆盖，内容跟壳版本走）。
    private static func writeEntryWrapper(in dir: URL, serverScript: String) -> URL? {
        let rel = serverScript.hasPrefix(dir.path)
            ? String(serverScript.dropFirst(dir.path.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            : "danmu_api/server.js"
        let js = """
        // 猫窝 iOS 壳生成的入口（勿手改）。nodejs-mobile 跑在 App 进程里，
        // node 一退 App 就退 —— 这里把所有 JS 侧退出拦住，并把 fetch 换成 node-fetch。
        const LOG = (m, e) => { try { console.error('[meow-guard] ' + m, (e && (e.stack || e.message)) || e || ''); } catch (_) {} };
        process.on('unhandledRejection', (e) => LOG('unhandledRejection', e));
        process.on('uncaughtException', (e) => LOG('uncaughtException', e));
        process.exit = (code) => { LOG('blocked process.exit(' + code + ')'); };
        if (typeof process.reallyExit === 'function') {
          process.reallyExit = (code) => { LOG('blocked reallyExit(' + code + ')'); };
        }
        (async () => {
          try {
            const m = await import('node-fetch');
            const f = m && (m.default || m);
            // ★ 必须用 defineProperty 覆盖：Node 18 的 globalThis.fetch 是
            //   「惰性 getter」，直接赋值可能触发 getter 去加载内置 undici，
            //   而那正是没有 WebAssembly 会炸的东西。
            if (typeof f === 'function') {
              Object.defineProperty(globalThis, 'fetch', { value: f, configurable: true, writable: true });
            }
            // ★ 不要读 `typeof globalThis.Headers/Request/Response`：Node 18 的
            //   全局是惰性 getter，读一下就会加载 undici（无 WASM → 抛错）。
            //   无条件覆盖成 node-fetch 的实现，代价只是丢掉内置类。
            const def = (k, v) => { if (v) Object.defineProperty(globalThis, k, { value: v, configurable: true, writable: true }); };
            def('Headers', m && m.Headers); def('Request', m && m.Request); def('Response', m && m.Response);            LOG('fetch -> node-fetch');
          } catch (e) {
            LOG('node-fetch unavailable', e);
          }
          try {
            await import('./\(rel)');
          } catch (e) {
            LOG('server import failed', e);
          }
          /*
           * JS 源运行时（drpy）：同一进程第二个 HTTP 监听。端口/绑定由 Swift 侧
           * 经 env 传入；用绝对路径 require（wrapper 在 danmu 载荷目录，drpy 在
           * Documents/drpy）。失败只记日志，不能带走弹幕，更不能带走 App。
           */
          try {
            const d = process.env.MEOW_DRPY_DIR;
            if (d) { require(d + '/server.js'); LOG('drpy started'); }
          } catch (e) {
            LOG('drpy import failed', e);
          }
        })();
        """
        let f = dir.appendingPathComponent("meow-entry.cjs")
        do {
            try js.write(to: f, atomically: true, encoding: .utf8)
            return f
        } catch {
            LaunchLog.write("[node] entry wrapper failed: \(error.localizedDescription)")
            return nil
        }
    }

    /// 包内载荷 → Documents（版本变了重拷）。返回可写的载荷目录。
    private static func installPayloadIfNeeded() -> URL? {
        let fm = FileManager.default
        guard let bundled = Bundle.main.resourceURL?.appendingPathComponent("danmu-api"),
              fm.fileExists(atPath: bundled.appendingPathComponent("danmu_api/server.js").path) else {
            LaunchLog.write("[node] payload not bundled; keep built-in danmaku")
            return nil
        }
        let docs = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dest = docs.appendingPathComponent("danmu-api", isDirectory: true)
        let want = version(at: bundled)
        let have = version(at: dest)
        if !want.isEmpty, want == have,
           fm.fileExists(atPath: dest.appendingPathComponent("danmu_api/server.js").path) {
            return dest
        }
        do {
            try? fm.removeItem(at: dest)
            try fm.copyItem(at: bundled, to: dest)
            LaunchLog.write("[node] payload installed \(want)")
            return dest
        } catch {
            LaunchLog.write("[node] payload copy failed: \(error.localizedDescription)")
            return nil
        }
    }

    /// 包内 drpy 载荷 → Documents/drpy（版本变了重拷，与弹幕同语义）。
    private static func installDrpyIfNeeded() -> URL? {
        let fm = FileManager.default
        guard let bundled = Bundle.main.resourceURL?.appendingPathComponent("drpy"),
              fm.fileExists(atPath: bundled.appendingPathComponent("server.js").path) else {
            LaunchLog.write("[node] drpy payload not bundled; js sources stay off")
            return nil
        }
        let docs = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dest = docs.appendingPathComponent("drpy", isDirectory: true)
        let want = version(at: bundled)
        let have = version(at: dest)
        if !want.isEmpty, want == have,
           fm.fileExists(atPath: dest.appendingPathComponent("server.js").path) {
            return dest
        }
        do {
            try? fm.removeItem(at: dest)
            try fm.copyItem(at: bundled, to: dest)
            LaunchLog.write("[node] drpy installed \(want)")
            return dest
        } catch {
            LaunchLog.write("[node] drpy copy failed: \(error.localizedDescription)")
            return nil
        }
    }

    /// 把端内运行时地址写进 `policy.json` 的 `jsRuntime`（宿主只认这个键）。
    ///
    /// <p>规则：
    ///  - 用户手配的地址（非 127.0.0.1）永远保留 —— 不能被端内地址盖掉；
    ///  - 端内 loopback 地址允许升级（drpy 19330 → pyrt 19331），老用户不用清配置；
    ///  - 绝不整文件覆盖：policy.json 还有 authToken / corsOrigins / 缓存配额。
    static func writeJsRuntime(url: String) {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let f = docs.appendingPathComponent("gateway/policy.json")
        var obj: [String: Any] = [:]
        if let data = try? Data(contentsOf: f),
           let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            obj = j
        }
        if let cur = obj["jsRuntime"] as? String {
            let t = cur.trimmingCharacters(in: .whitespaces)
            if !t.isEmpty {
                if !t.hasPrefix("http://127.0.0.1:") {
                    LaunchLog.write("[node] jsRuntime custom; keep \(t)")
                    return
                }
                if t == url {
                    return
                }
            }
        }
        obj["jsRuntime"] = url
        do {
            let data = try JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted, .sortedKeys])
            try data.write(to: f, options: .atomic)
            LaunchLog.write("[node] jsRuntime injected -> \(url)")
        } catch {
            LaunchLog.write("[node] write policy.json failed: \(error.localizedDescription)")
        }
    }

    /// 短探活：`GET 127.0.0.1:<port>/health` 返回 200 才算活着。
    ///
    /// 只用于「切换后端 / 回前台」这种低频对齐，别放进请求热路径。
    static func endoRuntimeAlive(port: Int, timeout: TimeInterval = 1.2) -> Bool {
        guard let u = URL(string: "http://127.0.0.1:\(port)/health") else { return false }
        var req = URLRequest(url: u)
        req.timeoutInterval = timeout
        var ok = false
        let sem = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: req) { _, resp, _ in
            if let http = resp as? HTTPURLResponse { ok = http.statusCode == 200 }
            sem.signal()
        }.resume()
        _ = sem.wait(timeout: .now() + timeout + 1)
        return ok
    }

    /// 按**真实探测结果**对齐 `policy.jsRuntime`（只动 loopback，自定义地址保留）。
    ///
    /// <p>为什么要它（2026-11-27 真机）：iOS 挂起会回收 node 的监听 socket，
    /// 而 policy 里的 `jsRuntime` 不会跟着失效 —— 切回端内后端后设置页显示
    /// 「Node 不可用 / CPython 未启用」，`.js` 源还会先等满 8 秒超时。
    /// 这里按活体重写：pyrt 活着用 19331（它转发 .js），否则 drpy 19330；
    /// 都不活就保持原值（下次冷启动会重写），只留日志便于排障。
    ///
    /// - Parameter waitSeconds: 给刚被系统挂起唤醒的 drpy 一点复活时间（0 = 只探一次）
    static func alignJsRuntime(waitSeconds: TimeInterval = 0) {
        DispatchQueue.global(qos: .utility).async {
            let deadline = Date().addingTimeInterval(max(0, waitSeconds))
            var drpy = endoRuntimeAlive(port: drpyPort)
            while !drpy && Date() < deadline {
                Thread.sleep(forTimeInterval: 0.5)
                drpy = endoRuntimeAlive(port: drpyPort)
            }
            let py = endoRuntimeAlive(port: PythonRuntime.port)
            if py {
                LaunchLog.write("[node] align: jsRuntime -> pyrt (drpy=\(drpy))")
                writeJsRuntime(url: "http://127.0.0.1:\(PythonRuntime.port)")
            } else if drpy {
                LaunchLog.write("[node] align: jsRuntime -> drpy")
                writeJsRuntime(url: "http://127.0.0.1:\(drpyPort)")
            } else {
                LaunchLog.write("[node] align: no endo runtime alive; keep policy")
            }
        }
    }

    private static func version(at dir: URL) -> String {
        let f = dir.appendingPathComponent(".payload-version")
        let text = (try? String(contentsOf: f, encoding: .utf8)) ?? ""
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 探活 → 把地址写进 `gateway/danmaku-host.txt`（宿主每次请求读它）。
    ///
    /// ★ 只探根路径「有没有 HTTP 响应」，**不拿 ?name=test 打真实搜索**。
    ///   真机实测（2026-11-24）：首启那次搜索会点着 danmu_api 的 fetch 路径，
    ///   nodejs-mobile 的 V8 没有 WebAssembly → undici 抛 ReferenceError →
    ///   node 退出 → App 跟着退（表现为「启动几秒后自动退出」）。
    ///   根路径 404 也算活着，弹幕请求等真播放时再发。
    private static func waitHealthyThenInject() {
        let probe = "http://127.0.0.1:\(port)/"
        let deadline = Date().addingTimeInterval(240)
        while Date() < deadline {
            if httpAlive(probe) {
                writeHostFile()
                /*
                 * .py 源：drpy 就绪后拉起端内 CPython + pyrt（19331）。
                 * pyrt 自己把 .js 转发给 drpy，所以宿主只需要一个 jsRuntime。
                 * Python 起不来（老包/裁剪过度）就退回 drpy：.js 照常，.py 报清晰原因。
                 */
                // 先写 drpy：JS 源立刻可用，不用等 Python 初始化
                writeJsRuntime(url: "http://127.0.0.1:\(drpyPort)")
                #if !targetEnvironment(simulator)
                PythonRuntime.startIfPossible()
                if PythonRuntime.waitHealthy(timeout: 25) {
                    LaunchLog.write("[py] healthy; jsRuntime -> pyrt \(PythonRuntime.port)")
                    // 升级到 pyrt：.py 本地执行，.js 由 pyrt 转发给 drpy
                    writeJsRuntime(url: "http://127.0.0.1:\(PythonRuntime.port)")
                } else {
                    LaunchLog.write("[py] not ready; jsRuntime stays on drpy")
                }
                #endif
                return
            }
            Thread.sleep(forTimeInterval: 3)
        }
        LaunchLog.write("[node] health timeout (built-in danmaku stays)")
    }

    /// 写 data/danmaku-host.txt（宿主每次弹幕请求重读它）。
    private static func writeHostFile() {
        let tpl = "http://127.0.0.1:\(port)/api/v2/fongmi/danmaku?name={name}&episode={episode}"
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let f = docs.appendingPathComponent("gateway/danmaku-host.txt")
        do {
            try tpl.write(to: f, atomically: true, encoding: .utf8)
            LaunchLog.write("[node] healthy; danmaku host injected")
            // 证明本次启动是健康的：下次手动重启不会被 crashLooping 误判
            UserDefaults.standard.set(true, forKey: wasHealthyKey)
            lock.lock()
            let first = !stableTimerScheduled
            if first { stableTimerScheduled = true }
            lock.unlock()
            if first { scheduleStableReset() }
        } catch {
            LaunchLog.write("[node] write host failed: \(error.localizedDescription)")
        }
    }

    /**
     回前台哨兵（2026-11-25 日志 A2）。

     <p>iOS 挂起会回收监听 socket：进程还在、端口没了，宿主每次弹幕请求都打
     一个死端口（`Connection refused`，还会把本机 host 打进失败冷却）。

     <p>处置：探活失败就**摘掉 danmaku-host.txt**，让 ApiServer 回退内置弹幕源；
     探活成功则补写（文件可能被清过）。**不在进程内重启 node** ——
     nodejs-mobile 的 `node_start` 只支持调用一次，二次调用会把 App 一起带走；
     下次冷启动自然恢复。
     */
    static func ensureServing() {
        DispatchQueue.global(qos: .utility).async {
            lock.lock()
            let wasStarted = started
            lock.unlock()
            guard wasStarted else { return }
            if httpAlive("http://127.0.0.1:\(port)/") {
                writeHostFile()
                return
            }
            LaunchLog.write("[node] service dead after resume; drop danmaku host (built-in fallback)")
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            try? FileManager.default.removeItem(
                at: docs.appendingPathComponent("gateway/danmaku-host.txt"))
        }
    }

    /// 同步 GET：拿到任何 HTTP 响应（状态 < 500）就算「服务活着」。
    ///
    /// 不要求 200：danmu_api 没有根路由（404 很正常），而 5xx 说明它虽然监听了
    /// 但坏了，注入进去只会让每次弹幕请求都报错 —— 宁可等下次启动再试。
    private static func httpAlive(_ url: String) -> Bool {
        guard let u = URL(string: url) else { return false }
        var alive = false
        let sem = DispatchSemaphore(value: 0)
        var req = URLRequest(url: u)
        req.timeoutInterval = 10
        req.setValue("meow-ios", forHTTPHeaderField: "User-Agent")
        URLSession.shared.dataTask(with: req) { _, resp, _ in
            if let http = resp as? HTTPURLResponse {
                alive = http.statusCode < 500
            }
            sem.signal()
        }.resume()
        _ = sem.wait(timeout: .now() + 15)
        return alive
    }
}

#endif
