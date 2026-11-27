import Foundation

/// 播放页倒计时（iOS）。
///
/// <p>为什么要在原生层计时：WKWebView 切后台后 JS `setInterval` 会被节流/挂起，
/// 用户设了「30 分钟后暂停」，后台一直播到回来才停（2026-11-25 用户提问）。
/// 本 App 后台在放音频，进程不会被挂起，主队列的 DispatchSourceTimer 到点能准时触发。
///
/// <p>到点只做两件事：让页面暂停（`window.__meowPowerFire`，播放器里有现成桥）
/// + 释放音频会话（否则暂停后后台仍占着音频、锁屏播放条还在）。
/// **不退出 App**：iOS 上 `exit(0)` 是禁止行为，用户看到的是"突然闪退"；
/// 主流播放器（B站/爱奇艺）的睡眠定时也只到"暂停"这一层。
enum PowerTimer {
    private static var timer: DispatchSourceTimer?
    private static var deadline: Date?

    /// 设倒计时；seconds <= 0 取消。
    static func start(seconds: Int) {
        cancel()
        guard seconds > 0 else {
            LaunchLog.write("power timer: off")
            return
        }
        let t = DispatchSource.makeTimerSource(queue: .main)
        t.schedule(deadline: .now() + .seconds(seconds))
        t.setEventHandler {
            LaunchLog.write("power timer fired after \(seconds)s")
            WebViewRegistry.view?.evaluateJavaScript(
                "window.__meowPowerFire&&window.__meowPowerFire('pause')",
                completionHandler: nil)
            /*
             * 端内原生播放：播放器界面在**控制层那个独立 WebView** 里，
             * 上面那行 JS 只打到主 WebView，定时到点等于没停。这里直接
             * 暂停原生播放器（2026-11-27 审计发现）。
             */
            NativeVlcPlayer.activePlayer?.pauseFromPowerTimer()
            // 暂停后把后台音频也放掉：否则锁屏上还显示"正在播放"
            AudioSession.stopForSleep()
            cancel()
        }
        t.resume()
        timer = t
        deadline = Date().addingTimeInterval(TimeInterval(seconds))
        LaunchLog.write("power timer: \(seconds)s")
    }

    static func cancel() {
        timer?.cancel()
        timer = nil
        deadline = nil
    }

    /// 剩余毫秒（-1 = 没有定时）
    static func remainingMs() -> Int {
        guard let d = deadline else { return -1 }
        return max(0, Int(d.timeIntervalSinceNow * 1000))
    }
}
