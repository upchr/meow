import SwiftUI
import WebKit

/// 当前 WKWebView 的弱引用。
///
/// <p>用途：`scenePhase == .active` 时给页面补发一次 `meow:ios-active` 事件。
/// `visibilitychange` 在 iOS 上偶尔早于/晚于 scene 真正 active，播放器靠这个
/// 事件做续播与横屏恢复（2026-11-25 用户报「切后台回来偶尔不自动播」）。
enum WebViewRegistry {
    static weak var view: WKWebView?
}

struct WebView: UIViewRepresentable {
    let url: URL
    let script: String
    var onReady: () -> Void = {}
    var onError: (String) -> Void = { _ in }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.preferences.javaScriptCanOpenWindowsAutomatically = true
        config.websiteDataStore = .default()

        let controller = WKUserContentController()
        if !script.isEmpty {
            controller.addUserScript(WKUserScript(source: script,
                                                  injectionTime: .atDocumentStart,
                                                  forMainFrameOnly: false))
        }
        // 前端诊断通道：页面里的网络级失败（请求没到后端 / 预检失败）连不上
        // 后端日志，只有这里能落到 launch.log。见 api/index.ts 的 webLog()。
        controller.add(context.coordinator, name: "meowLog")
        // 页面 → 原生的控制通道（目前只有转屏：播放页全屏要横屏）。
        controller.add(context.coordinator, name: "meowIos")
        config.userContentController = controller

        let view = WKWebView(frame: .zero, configuration: config)
        WebViewRegistry.view = view
        view.navigationDelegate = context.coordinator
        view.uiDelegate = context.coordinator
        view.isOpaque = false
        view.backgroundColor = .black
        view.scrollView.backgroundColor = .black
        view.scrollView.contentInsetAdjustmentBehavior = .never
        /*
         * 原生边缘返回手势（2026-11-25 用户反馈）：之前一直是 false，所以 IPA 里
         * 从左边缘右滑不会回上一页。开着；播放页/直播页会通过 navGesture 桥
         * 临时关掉，避免和横滑调进度、全屏手势打架。
         */
        view.allowsBackForwardNavigationGestures = true
        view.load(URLRequest(url: url))
        return view
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        // webURL 变化会重建视图；这里不重复 load，避免打断播放页。
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
        private let parent: WebView

        init(_ parent: WebView) {
            self.parent = parent
        }

        /// 页面通过 `window.webkit.messageHandlers.*.postMessage(...)` 调原生。
        func userContentController(_ userContentController: WKUserContentController,
                                   didReceive message: WKScriptMessage) {
            switch message.name {
            case "meowLog":
                LaunchLog.write("[web] \(message.body)")
            case "meowIos":
                if let dict = message.body as? [String: Any],
                   let op = dict["op"] as? String {
                    switch op {
                    case "orientation":
                        ShellOrientation.apply(dict["value"] as? String ?? "")
                    case "keepAwake":
                        // 远程调试必须让屏幕别自动锁：息屏 = App 挂起 = 网关端口消失
                        ScreenAwake.apply(dict["value"] as? Bool ?? false)
                    case "volume":
                        // 网页改不了 iOS 系统音量，交给 MPVolumeView（SystemVolume）
                        SystemVolume.set((dict["value"] as? NSNumber)?.intValue ?? 0)
                    case "playing":
                        // 播放状态上报：进后台时只有"在播"才激活音频会话
                        AudioSession.setPlaying(dict["value"] as? Bool ?? false)
                    case "volumeHud":
                        // true = 播放页接管音量 UI（挂 MPVolumeView，抑制系统 HUD）
                        if dict["value"] as? Bool ?? false {
                            SystemVolume.attach()
                            // 挂上后立刻把真实音量回填快照：启动快照可能读到 0
                            let v = SystemVolume.current()
                            message.webView?.evaluateJavaScript(
                                "if(window.meowIos)window.meowIos.volume=\(v)",
                                completionHandler: nil)
                        } else {
                            SystemVolume.detach()
                        }
                    case "brightness":
                        // 负数 = 还原进入播放页前的亮度
                        SystemBrightness.set((dict["value"] as? NSNumber)?.intValue ?? -1)
                    case "navGesture":
                        // 播放/直播页关掉边缘返回手势，避免和横滑 seek 冲突
                        message.webView?.allowsBackForwardNavigationGestures =
                            dict["value"] as? Bool ?? true
                    case "powerTimer":
                        // 倒计时（原生计时：JS 切后台会被挂起）
                        PowerTimer.start(seconds: (dict["value"] as? NSNumber)?.intValue ?? 0)
                    case "apiBackend":
                        /*
                         * 后端地址（2026-11-27）：落 UserDefaults，下次
                         * makeBootScript 注入新 apiBase。页面保存后会自己
                         * location.reload()，这里不回执。
                         */
                        BackendOverride.set(dict["value"] as? String ?? "")
                    case "notify":
                        Notifier.shared.notify(
                            title: dict["title"] as? String ?? "喵播放器",
                            body: dict["body"] as? String ?? "")
                    case "nativeVlc":
                        /*
                         * 原生兜底播放（MKV/AVI/FLV/DTS 等 web 播不了的格式）。
                         * 与 TV 的 openNative 同构：web 侧只在 hasIosNativePlayer()
                         * 为 true 时走这里，失败由原生回传 __meowVlcClosed 让 web 恢复。
                         */
                        NativeVlcPlayer.present(from: message.webView, dict: dict)
                    default:
                        break
                    }
                }
            default:
                break
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            LaunchLog.write("web didFinish")
            parent.onReady()
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            LaunchLog.write("web didFail: \(error.localizedDescription)")
            parent.onError(error.localizedDescription)
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            LaunchLog.write("web didFailProvisional: \(error.localizedDescription)")
            parent.onError(error.localizedDescription)
        }

        /// 渲染/网络进程被系统回收（内存压力下 iOS 会直接杀）。
        ///
        /// 症状是「页面还在但所有图片碎了、按钮点了没反应」或者白屏。WKWebView 不会
        /// 自己恢复，只能 reload —— reload 会重建进程。（2026-11-24 真机反馈）
        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            LaunchLog.write("web content process terminated; reload")
            webView.reload()
        }

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for navigationAction: WKNavigationAction,
                     windowFeatures: WKWindowFeatures) -> WKWebView? {
            if navigationAction.targetFrame == nil {
                webView.load(navigationAction.request)
            }
            return nil
        }
    }
}
