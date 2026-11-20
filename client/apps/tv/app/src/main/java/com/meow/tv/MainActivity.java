package com.meow.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.webkit.WebViewAssetLoader;

import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 电视壳：一个 WebView 加载自研 Web 前端。
 *
 * <p>为什么用 WebView 而不是原生重写一套 UI：需求是"UI 与自研 Web 对齐"，
 * 同一份前端天然就是对齐的；原生重写只会得到两份会漂移的界面。
 * 电视上真正缺的不是界面，而是**遥控器导航**——由注入的 `tv-nav.js` 补齐。
 *
 * <p>地址有两种给法：
 * <ul>
 *   <li>首次启动在壳内设置页填（遥控可操作，会记住）；</li>
 *   <li>`adb shell am start ... --es url http://host:30010` 直接覆盖。</li>
 * </ul>
 *
 * <p>设置页还能填一个 **CatVod 配置地址**：进入 Web 后由页面同源调用
 * `/api/config/import` 导入——电视上没法像 PC 那样弹文件对话框，
 * 这是把"别人发的 json"落到后端的可行路径。
 */
public class MainActivity extends Activity {

    static final String PREF = "meow_tv";
    /** 后端 API 地址。**这是现在唯一需要配的东西**（前端已内置）。 */
    static final String K_API = "api_base";
    /**
     * 上次的后端形态：{@code "local"} = 端内网关，{@code "remote"} = 外部后端。
     *
     * <p>为什么要单独存一个标记：只看地址推断不出来 —— 用户点「启用本机执行」时
     * 前端只改了 localStorage，壳的 prefs 还是旧值；下次启动注入 prefs 就把
     * 本机选择覆盖掉了（表现为"上次明明开了本机执行，重开又要去设置页开一次"）。
     * 有了标记，启动时能明确恢复：local → 起网关，remote → 保持原地址。
     */
    static final String K_MODE = "api_mode";
    /**
     * 本机网关"正在启动"的时间戳。
     *
     * <p>用途是崩溃环保护：32 位设备（极米实测）起端内网关有概率 SIGABRT。
     * 启动前写、**稳定运行 30 秒后**才清；下次启动还看到它就说明上次死在中途。
     */
    static final String K_GW_BOOT = "gw_boot_at";
    /**
     * 本机网关在本机崩过（上次启动没走完）。
     *
     * <p>置真后不再自动启动网关 —— 否则每次开应用都重试、每次都在同一个点死，
     * 用户看到的是"一进就闪退"。手动「启用本机执行」会清掉它（用户明确要求再试）。
     */
    static final String K_GW_FAIL = "gw_failed";
    /** 网关连续存活到这个时长才算"稳定"（实测崩点在启动后 5 秒左右）。 */
    static final long GW_STABLE_MS = 30_000L;
    /**
     * 冷启动后等多久再自动起网关。
     *
     * <p><b>2026-11-05 改回 0（与 1.0.5 对齐）</b>：之前设成 10 秒是为了躲 32 位
     * ART 的 SIGABRT（首屏类加载和 883 个 spider 同时跑）。但实测（MuMu 64 位）：
     * 推迟 10 秒后，首屏请求打在一个还没监听的端口上 → 首页停在
     * "Failed to fetch"；10 秒后的 reload 又撞上网关预热，WebView 连续
     * 60 秒不响应，用户只能手动点「刷新」。
     *
     * <p>同一台机器上 1.0.5（立即起网关、约 1 秒就绪后 reload）冷启动
     * t=12s 首页 20 张海报、eval 正常，预热照样 93 秒也不影响界面 ——
     * 说明"立即启动 + 就绪后 reload"才是对的时序。
     *
     * <p>32 位防崩暂时让位（用户口径：先不针对 32 位）；要恢复时改成
     * `Process.is64Bit() ? 0 : 10_000`，不要整体拖慢 64 位设备。
     */
    static final long GW_BOOT_DELAY_MS = 0L;
    /** 旧版存"网页地址"的键。仅用于迁移，新逻辑不再写它。 */
    static final String K_URL = "web_url";
    static final String K_CONFIG = "config_url";
    /** 远程调试开关（默认关）。开着才起 DebugServer。 */
    static final String K_DEBUG = "debug_server";
    /** 远程调试令牌。开关每次从关到开都会生成新的，旧令牌立即失效。 */
    static final String K_DEBUG_TOKEN = "debug_token";
    /**
     * 设备令牌（auth v2）的壳侧副本。
     *
     * <p>★ 为什么要在 prefs 里再存一份：`meow.authToken` 原本只在前端
     * localStorage。**重装 APK（应用内更新走的也是 pm install -r）之后
     * WebView 的 localStorage 在部分 ROM / 模拟器上会被清空** —— 实测
     * MuMu Android 15：装完新包打开就是登录页，用户得重新配对一次。
     * prefs 与应用数据目录同生命周期、install -r 保留，所以在这里存一份，
     * 页面加载时补回 localStorage。
     *
     * <p>安全等级与 localStorage 相同（都在应用私有目录、明文）：这个值
     * 只对本机有效，网页端随时可撤销，不额外增加攻击面。
     */
    static final String K_AUTH = "auth_token";

    /*
     * WebView 文件选择器的请求码。
     *
     * <p>必须与 CacheDownloader.REQ_TREE（0x4D01）分开：两者都走
     * onActivityResult，混用会把"目录授权"和"选了图片"互相覆盖。
     */
    private static final int REQ_FILE_CHOOSER = 0x4D11;

    private WebView web;

    /**
     * 正在等待结果的 {@code <input type=file>} 回调。
     *
     * <p>WebView 只给一次回调机会：不调用 onReceiveValue，页面的 change
     * 事件永远不触发。取消（RESULT_CANCELED）也必须显式传 null，
     * 否则控件会停在"等待选择"状态。
     */
    private ValueCallback<Uri[]> fileChooserCallback;

    /**
     * 当前活动的 WebView 引用，仅供 {@link TvBridge} 做 origin 校验。
     *
     * <p>为什么用静态引用而不是把 Activity 传给桥：桥是挂在 WebView 上的
     * {@code addJavascriptInterface}，JS 侧拿到的对象与 Activity 不同生命周期；
     * 校验只想知道"当前文档是哪个地址"，一个静态弱引用就够。
     * 不用 WeakReference 是因为 WebView 的生命周期本来就跟 Activity 绑定，
     * 且 onDestroy 会清空它。
     */
    /**
     * 最近一次页面加载的 URL，仅供 {@link TvBridge} 做 origin 校验。
     *
     * <p>★ 为什么不直接读 {@code WebView.getUrl()}：WebView 的所有方法都必须在
     * UI 线程调用，而 JS 桥方法跑在 WebView 的 JavaBridge 线程上——在那里调
     * {@code getUrl()} 是未定义行为（实测：返回 null，于是所有需要 origin
     * 校验的桥方法都静默失效，表现为"点了没反应"）。
     * 所以这里只维护一个字符串，在 onPageFinished 里更新。
     */
    private static volatile String sCurrentUrl = "";

    /** 当前文档地址（纯字符串，任意线程可安全读）。 */
    static String currentUrl() {
        return sCurrentUrl;
    }

    private SharedPreferences prefs;
    /** 页面加载失败（含连续失败）。用于决定返回键去哪、以及是否显示错误页 */
    private boolean failed;
    /**
     * 样式探针是否已经因"探针自身跑不起来"重试过一次。
     *
     * <p>只重试一次：{@code err:} 通常是一次导航时序抖动（body 还没建好），
     * 等 1.5 秒重探就够了；无限重试会在真的老内核上形成静默死循环。
     * 每次 loadBuiltin 重新归零。
     */
    private boolean auditRetried;
    private WebViewAssetLoader mAssetLoader;
    /** 主线程 Handler（看门狗与 UI 回投都用它） */
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    /** 待导入的配置地址；页面加载完成后执行一次 */
    private String pendingConfig;

    @SuppressLint("SetJavaScriptEnabled")
    /** 鼠标空闲 3 秒后隐藏系统指针；一移动立刻恢复（电视/模拟器） */
    private final android.os.Handler pointerHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable hidePointerIcon = new Runnable() {
        @Override public void run() { setPointerIcon(android.view.PointerIcon.TYPE_NULL); }
    };

    /** 只在状态切换时打一行日志：便于 adb/logcat 验收，不会每次 hover 刷屏 */
    private int lastPointerType = Integer.MIN_VALUE;

    private void setPointerIcon(int type) {
        try {
            web.setPointerIcon(android.view.PointerIcon.getSystemIcon(this, type));
            if (type != lastPointerType) {
                lastPointerType = type;
                android.util.Log.i("MeowPointer",
                        type == android.view.PointerIcon.TYPE_NULL ? "pointer hidden" : "pointer shown");
            }
        } catch (Throwable ignored) {
            /* 个别 ROM 没有 PointerIcon：CSS 的 cursor:none 仍是兜底 */
        }
    }

    private void installPointerAutoHide() {
        setPointerIcon(android.view.PointerIcon.TYPE_NULL);
        try {
            web.setOnHoverListener((v, e) -> {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_HOVER_MOVE
                        || a == android.view.MotionEvent.ACTION_HOVER_ENTER) {
                    setPointerIcon(android.view.PointerIcon.TYPE_DEFAULT);
                    pointerHandler.removeCallbacks(hidePointerIcon);
                    pointerHandler.postDelayed(hidePointerIcon, 3000);
                }
                // 不能吞事件：WebView 还要靠 hover 做 CSS :hover / 控件悬停
                return false;
            });
        } catch (Throwable ignored) {
            /* 不支持 hover 的 ROM：保持 NULL（电视本来就是遥控器优先） */
        }
    }

    private void ensureWeb() {
        if (web != null) return;
        // 调试构建开启 WebView 远程调试：电视上"能看见但点不动/不播"只能靠它定位。
        // 连法：adb forward tcp:9460 localabstract:webview_devtools_remote_<pid>
        //
        // ★ release 包也留一个口子，但必须显式设属性：
        //     adb shell setprop debug.meow.webview 1
        //   为什么值得留：实测那台极米投影仪上 logcat 被系统屏蔽、
        //   release 包不能 run-as、uiautomator 读不到 WebView 内部 ——
        //   除了 CDP 没有任何办法读到"某个 CSS 类到底算出多少"，
        //   而这类问题（老内核特性缺失）只能靠实测定位。
        //   默认关闭，不影响普通用户。
        boolean dbgWeb = BuildConfig.DEBUG || "1".equals(systemProp("debug.meow.webview"));
        if (dbgWeb) WebView.setWebContentsDebuggingEnabled(true);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        /*
         * ★ 2026-11-05：两项都关掉（原来是 true/true）。
         *
         * 内置前端有自己的 `<meta viewport>`，让 meta 生效（wide viewport=true），
         * 但**关掉 overview**——overview 会在旋转后保留上一次算出的 fit 缩放：
         * 实测竖屏窗口 384px 时页面被放大 1.216、横屏时缩到 0.822
         * （用户报「旋转几次后页面不是 100% 了」）。
         * 配合前端在 MeowTV UA 下把 meta 锁成 min/max-scale=1，缩放恒为 1。
         */
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        // ★ UA 里必须带 MeowTV：前端 isTvEnv() 靠它判定电视环境
        //   （见 web/src/composables/useCardCols.ts），TV 专属样式——海报标题放大、
        //   卡宽目标 76px、顶栏字号——全部挂在这个判定上。
        //   踩过：这个标记一直没设，于是电视上一整套 10-foot 适配静默失效，
        //   表现为「字太小、列数按手机档算」。
        //   用 MeowTV 而不是 meow-tv：isTvEnv 的正则是 /MeowTV/i，带连字符匹配不上。
        s.setUserAgentString(s.getUserAgentString() + " MeowTV");
        /*
         * ── 渲染性能（"WebView 尽量接近原生观感"）──────────────────────
         *
         * 这些开关都不会改变功能，只影响合成/光栅化路径。逐条理由：
         *
         * · setLayerType(HARDWARE)：整页走 GPU 合成。电视盒子的 CPU 很弱，
         *   软件合成在 1080p 上直接掉到 20fps。ContentView 默认在硬件加速
         *   开启时已是 hardware，但显式设置能兜住"壳里某处关掉了"的情况。
         *
         * · offscreenPreRaster(true)：滚动/焦点移动时**预先把屏幕外的图块
         *   光栅化**。tv-nav 的每次按键都是"焦点跳一段"，不预光栅化就会在
         *   跳的瞬间看到一片空白再填上（用户描述"闪一下"）。
         *
         * · setInitialScale(0) / 关闭缩放：电视没有多点触控，任何缩放都只会
         *   让布局在 1.0 之外的倍率下重排，反而更慢。
         *
         * · CACHE_ELSE_NETWORK：静态资源（JS/CSS/字体）优先吃磁盘缓存。
         *   内置前端本来就是 assets，这条主要帮到远程后端形态下的图片与
         *   分片清单，减少每次进页面的等待。
         *
         * · setTextZoom(100)：有些 TV 固件把默认字体缩放改成 130%，会让
         *   整站的 rem/px 混排错位。显式钉死 100，字号统一由前端
         *   html[data-tv='1'] 的阶梯控制（docs/27 §4.8.2）。
         */
        web.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
        s.setOffscreenPreRaster(true);
        s.setSupportZoom(false);
        /*
         * ★ 缓存模式必须用默认值（LOAD_DEFAULT），**不要改成
         *   LOAD_CACHE_ELSE_NETWORK**（2026-11-02 实测踩过）。
         *
         * LOAD_CACHE_ELSE_NETWORK 的语义是"缓存里有就直接用，哪怕已过期"。
         * 它把 `/api/...` 的 JSON 也一并缓存了 —— 表现为弹幕搜索永远返回
         * 第一次那 1 条（后端其实有 58 条），加个随机参数就正常。
         * 这类问题极难定位：换姓名、重启应用都没用，只有清 WebView 缓存才恢复。
         *
         * 静态资源（JS/CSS）本来就从 assets 读、走 shouldInterceptRequest，
         * 拿到这条缓存收益的只有 /api，代价却是"接口结果永久停在旧值"。
         * 所以显式写默认值，防止以后又被当成"性能优化"加回来。
         */
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        // 走系统默认的"可执行部分 JS"即可；显式打开只是为了让
        // 老固件上默认值不明确的机型也有确定行为。
        s.setDatabaseEnabled(true);
        /*
         * 媒体播放不需要用户手势（电视上没有触摸）。已在上面设过，
         * 这里补一条：让 <video> 能直接进入全屏而不被内核拦。
         */
        s.setMediaPlaybackRequiresUserGesture(false);
        /*
         * 渲染进程优先级：可见时给 IMPORTANT，切走时降级。
         *
         * 电视盒子内存小（很多只有 1.5GB），后台留着高优先级渲染进程会被
         * 系统在播放器（ExoPlayer/原生 Activity）抢内存时优先杀掉，
         * 表现为"看完原生播放器返回，页面白了/重载了"。
         * minSdk 26 = Android 8.0，这条 API 是 API 26 引入的，可以直接用。
         */
        web.setRendererPriorityPolicy(
                android.webkit.WebView.RENDERER_PRIORITY_IMPORTANT, false);
        web.setWebViewClient(new WebViewClient() {
            /**
             * 内置前端的资源从 assets 提供。
             *
             * <p><b>为什么不用 WebViewAssetLoader</b>：它固定用
             * {@code https://appassets.androidplatform.net}，而 API 在
             * {@code http://局域网IP:端口}。页面是 https、图片是 http 时，
             * 即使设了 MIXED_CONTENT_ALWAYS_ALLOW，{@code <img>} 仍会被拦
             * （实测 fetch 能过、Image 加载 error）——表现为「卡片全没图」。
             *
             * <p>所以自己拦一个 http 源：页面与 API 同为 http，混合内容问题不存在。
             */
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest req) {
                if (req == null) return null;
                android.net.Uri u = req.getUrl();
                if (u == null || !"meow.local".equals(u.getHost())) return null;
                String path = u.getPath();
                /*
                 * ★ /api/ 一律放行，交给 WebView 走网络栈。
                 *
                 * 踩过（P0-4）：前端 resolveBase() 拿不到后端地址时会退化成
                 * **同源相对请求** `/api/sites`。本拦截器只认 assets，于是把
                 * /api/sites 当成一个 assets 文件名去找 → 找不到 → 返回
                 * text/plain "not found"（HTTP 200）。
                 *
                 * 后果极难辨认：前端拿到 200 但不是 JSON，报
                 * 「响应不是 JSON：not found」，看着像后端路由缺失，
                 * 实际是请求根本没出 WebView。放行后它会真的发到网络，
                 * 连不上就是明确的连接错误，比"假 200"好排查一万倍。
                 */
                if (path != null && path.startsWith("/api/")) return null;
                if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";
                // ★ /mirror/poster/<文件名>：从应用私有目录供图（MetaMirror 存的本地副本）。
                //   复用这个拦截器而不是另起 HTTP 服务——它本身就是"虚拟文件服务器"，
                //   且与页面同源（http://meow.local），不存在 file:// 的跨域与混合内容
                //   问题（那个坑见下面这段注释）。
                //   用途：后端（NAS）不可达时，海报墙仍显示上次缓存的图。
                if (path.startsWith("/mirror/poster/")) {
                    String name = path.substring("/mirror/poster/".length());
                    // 只放行 16 进制文件名 + .jpg：杜绝 ../ 穿越
                    if (!name.matches("[0-9a-f]{1,64}\\.jpg")) {
                        return new android.webkit.WebResourceResponse(
                                "text/plain", "UTF-8",
                                new java.io.ByteArrayInputStream("bad name".getBytes()));
                    }
                    try {
                        // 注意用 MainActivity.this 而不是 this：这里在匿名 WebViewClient 内部，
                        // this 指 WebViewClient，不是 Context（编译期就会报"不兼容的类型"）。
                        java.io.File f = new java.io.File(MetaMirror.posterDir(MainActivity.this), name);
                        if (f.isFile() && f.length() > 0) {
                            return new android.webkit.WebResourceResponse(
                                    "image/jpeg", null, new java.io.FileInputStream(f));
                        }
                    } catch (Throwable ignored) {
                    }
                    return new android.webkit.WebResourceResponse(
                            "text/plain", "UTF-8",
                            new java.io.ByteArrayInputStream("not found".getBytes()));
                }
                if (path.startsWith("/")) path = path.substring(1);
                try {
                    java.io.InputStream in = getAssets().open(path);
                    return new android.webkit.WebResourceResponse(
                            guessMime(path), "UTF-8", in);
                } catch (Exception e) {
                    return new android.webkit.WebResourceResponse(
                            "text/plain", "UTF-8",
                            new java.io.ByteArrayInputStream("not found".getBytes()));
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 记录当前文档地址：TvBridge 的 origin 校验要用它，
                // 而桥方法跑在 JavaBridge 线程上、不能读 WebView.getUrl()
                if (url != null) sCurrentUrl = url;
                if (watchdog != null) ui.removeCallbacks(watchdog);
                if (failed) return;
                // ★ 刚触发过 reload 时必须直接返回。
                //   applyApiBaseIfNeeded() 会 reload 一次让前端读到新的 meow.api，
                //   而这次 reload 是在本轮 onPageFinished 里发起的——紧接着跑
                //   injectNav / auditStyleApplied，打的是**正在被替换的旧页面**，
                //   此时 document.body 已经是 null，任何注入都会失败。
                //   实测后果：探针返回 err:... 被当成"样式没生效"→ 弹错误页，
                //   且 reload 完成后因 onPageFinished 已跑过而永不复检 → 用户
                //   看到"打不开这个地址"，点重试又走同一条路，形成死循环。
                if (applyApiBaseIfNeeded()) return;
                if (restoreAuthToken(url)) return;
                injectNav();
                importPendingConfig();
                auditStyleApplied(view);
            }

            /**
             * 主文档加载失败。
             *
             * <p>不处理的话 WebView 就是一片空白——用户看到的就是"黑屏"，
             * 而且因为白屏页也有历史记录，返回键只会在空白之间跳，困死在里头。
             * 这里把失败状态记下来，显示一个能回到配置页的说明页。
             */
            /**
             * 首个画面已经提交：页面在画了，撤掉"打不开这个地址"。
             *
             * <p>本机前端的 load 事件要等主 bundle 解析完（MuMu 实测 14.3s），
             * 而首帧几百毫秒就出来了 —— 看门狗只该盯"连一帧都没有"的情况。
             */
            @Override
            public void onPageCommitVisible(WebView view, String url) {
                cancelLoadWatchdog();
            }

            /** 注意：进度回调在 WebChromeClient 上（见下面那个匿名类），不在这里 */

            @android.annotation.TargetApi(android.os.Build.VERSION_CODES.M)
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req,
                                        WebResourceError err) {
                if (req != null && !req.isForMainFrame()) return;
                failed = true;
                showLoadError(view.getUrl(), String.valueOf(err == null ? "" : err.getDescription()));
            }

            /**
             * 渲染进程崩了：必须在这里消化掉，否则整个应用会被 abort。
             *
             * <p><b>实测（2026-11-05 MuMu）</b>：页面渲染进程 SIGSEGV 后，因为
             * 没人处理，Chromium 直接把整个应用杀掉：
             * <pre>
             * signal 11 (SIGSEGV) in com.android.webview:sandboxed_process0
             * Abort message: 'Render process (65100)'s crash wasn't handled by
             * all associated webviews, triggering application crash.'
             * </pre>
             * 用户看到的是"应用闪退"，实际只是网页渲染进程死了。重写并返回 true
             * 表示"我处理了"，系统就不会连带杀应用。
             *
             * <p>处理方式：销毁旧 WebView → 重建 Activity（新 WebView）→ 重新加载
             * 内置前端。崩溃循环保护：5 分钟内第 4 次不再重建，改走原生失败页，
             * 避免"重建→又崩→再重建"的无限闪退。
             */
            @Override
            public boolean onRenderProcessGone(WebView view,
                                               android.webkit.RenderProcessGoneDetail detail) {
                android.util.Log.w("MeowTv", "renderer gone: " + detail);
                try {
                    view.destroy();
                } catch (Throwable ignored) {
                }
                web = null;
                long now = System.currentTimeMillis();
                if (now - rendererGoneAt > RENDERER_GONE_WINDOW_MS) {
                    rendererGoneAt = now;
                    rendererGoneCount = 0;
                }
                rendererGoneCount++;
                if (rendererGoneCount > RENDERER_GONE_MAX) {
                    failed = false;
                    showLoadError("内置前端",
                            "网页渲染进程连续崩溃（" + rendererGoneCount + " 次）。"
                                    + "请更新「Android System WebView」后重启应用。");
                    return true;
                }
                failed = false;
                auditRetried = false;
                injectPending = true;
                ui.postDelayed(() -> MainActivity.this.recreate(), 300);
                return true;
            }
        });
        // 内置前端从 assets/www 提供。用 AssetLoader 而不是 file://：
        // file:// 下 localStorage、相对路径 fetch、ES module 都受限。
        //
        // **必须挂到根 "/" 而不是 "/assets/"**：Vite 产物的引用是
        // href="/favicon.ico"、src="/assets/index-xxx.js" 这种**根路径绝对地址**。
        // 挂 "/assets/" 时这些请求会被解析成 /assets/assets/... → 404，
        // 表现为页面"加载完成但一片空白"（实测踩过）。
        mAssetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebChromeClient(new WebChromeClient() {
            /**
             * 视频没有 poster 时必须返回一个非空 Bitmap。
             *
             * <p><b>不重写会直接闪退</b>（实测投影仪 Z6X Pro / Android 9）：
             * 页面一创建 {@code <video>}，Chromium 经
             * {@code DefaultVideoPosterRequestHandler} 在主线程回调这里；默认实现
             * 返回 null，而系统自身的
             * {@code WebViewContentsClientAdapter.getDefaultVideoPoster()} 紧接着
             * 调用 {@code bitmap.getWidth()} → NullPointerException → 整个应用被杀。
             * crash log：
             * <pre>
             * NullPointerException: Attempt to invoke virtual method
             * 'int android.graphics.Bitmap.getWidth()' on a null object reference
             *   at ...WebViewContentsClientAdapter.getDefaultVideoPoster
             * </pre>
             * 表现是"点播放应用直接没了"，看着像播控 bug，其实是系统 WebView 的坑。
             * 返回 1x1 透明图即可——前端自己的封面/海报会盖在上面。
             *
             * <p>★ 这个方法在 <b>WebChromeClient</b> 上，不在 WebViewClient 上
             * （放错位置编译报"方法不会覆盖或实现超类型的方法"）。
             */
            /**
             * 加载进度起来了（≥10%）说明资源和 JS 在正常进来，撤掉看门狗。
             *
             * <p>★ 这个回调在 <b>WebChromeClient</b> 上，不在 WebViewClient 上
             * （放错位置编译报"方法不会覆盖或实现超类型的方法"，与
             * {@link #getDefaultVideoPoster()} 同一个坑）。
             */
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 10) cancelLoadWatchdog();
            }

            @Override
            public android.graphics.Bitmap getDefaultVideoPoster() {
                return android.graphics.Bitmap.createBitmap(
                        1, 1, android.graphics.Bitmap.Config.ARGB_8888);
            }

            /**
             * {@code <input type=file>} 点击后由这里拉起系统文件选择器。
             *
             * <p><b>不重写会怎样</b>：默认实现返回 false，WebView 不会打开
             * 任何界面，页面上的「上传图片」看起来完全没反应（实测 2026-11-03：
             * 点上传后 topResumedActivity 仍是本 Activity，随后按返回被当成
             * 一次页面返回，路由从 {@code #/settings} 退到 {@code #/ai}）。
             *
             * <p><b>为什么必须 startActivityForResult</b>：WebView 只认
             * onActivityResult 里回填的 URI；直接 startActivity 会在返回时
             * 丢掉结果，控件一直等不到 change 事件。
             *
             * <p>取消（BACK 退出选择器）时回 null：不触发 change，页面停在
             * 原处，焦点留在触发按钮上。
             */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                // 前一次选择还没回填就又点了一次：先把它按取消处理，
                // 否则旧回调永远悬着，WebView 会一直认为选择器还在等结果。
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                    fileChooserCallback = null;
                }
                fileChooserCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                    return true;
                } catch (Throwable t) {
                    // 精简 TV 固件可能没有 DocumentsUI；明确回取消而不是留悬空回调
                    fileChooserCallback = null;
                    if (callback != null) callback.onReceiveValue(null);
                    return false;
                }
            }
        });
        // 原生能力桥：页面据此决定"这条线路用 ExoPlayer 还是留在 WebView"。
        // 名字固定 meowTv，前端按 window.meowTv 探测。
        web.addJavascriptInterface(new TvBridge(this, web), "meowTv");
        // 首投确认要往页面里调 confirmDialog（不再用原生 AlertDialog）
        CastReceiverBridge.attachWeb(web);
    }

    /**
     * 把配置地址交给页面同源导入。
     *
     * <p>为什么在 WebView 里发请求而不是原生发：后端可能被 nginx 反代在
     * Web 的同源下（Docker 默认如此），原生侧只知道 Web 地址，拼 /api 不一定对；
     * 页面自己发一定对。
     */
    private void importPendingConfig() {
        if (pendingConfig == null || web == null) return;
        String cfg = pendingConfig;
        pendingConfig = null;
        String js = "(async()=>{try{const r=await fetch('/api/config/import',{method:'POST',"
                + "headers:{'Content-Type':'application/json'},body:JSON.stringify({url:" + jsonStr(cfg) + "})});"
                + "const j=await r.json();console.log('[tv] import',JSON.stringify(j).slice(0,200));"
                + "if(j&&j.ok){location.reload()}}catch(e){console.log('[tv] import failed',e.message)}})()";
        web.evaluateJavascript(js, null);
    }

    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * 读系统属性。{@code android.os.SystemProperties} 是隐藏 API，
     * 编译期不可直接引用（会报 "cannot find symbol"），只能反射。
     *
     * @return 属性值；读不到返回空串（调用方按"未设置"处理）
     */
    private static String systemProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private void injectNav() {
        try {
            java.io.InputStream in = getAssets().open("tv-nav.js");
            byte[] buf = new byte[in.available()];
            //noinspection ResultOfMethodCallIgnored
            in.read(buf);
            in.close();
            web.evaluateJavascript(new String(buf, "UTF-8"), null);
        } catch (Exception ignored) {
            // 注入失败只是没有遥控导航，不该让整个应用崩
        }
    }

    /** 投屏接收：从任意 Context 拉起主界面并跳到指定 hash（接收页）。 */
    static void castOpen(android.content.Context ctx, String hash) {
        if (hash == null || !hash.startsWith("#/")) return;
        try {
            Intent i = new Intent(ctx, MainActivity.class);
            i.setAction(Intent.ACTION_VIEW);
            i.setData(android.net.Uri.parse("http://meow.local/index.html" + hash));
            i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (!(ctx instanceof android.app.Activity)) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 投屏接收端：主进程启动（会话与播放器桥必须同进程）
        try {
            CastReceiverBridge.attach(this);
            CastReceiverBridge.install(getApplicationContext());
        } catch (Throwable ignored) {
            // 接收端起不来不影响主功能
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        /*
         * 没有触摸屏的设备（电视/盒子/投影）永远是横屏。
         *
         * 踩过（2026-11-06）：直播页的全屏旋转会在"竖屏 → 横屏"后把方向还原成
         * 竖屏（手机上正确），但如果系统方向本身被判成竖屏（模拟器 / 异常的
         * system_rotation），电视会永久留在竖屏 —— 而遥控器用户没有任何入口
         * 转回来，只能重装或 adb。
         *
         * 判据用**硬件特征**而不是屏幕比例：手机（含 TV APK 装在手机上）有
         * touchscreen，不该被钉死；MuMu 这类模拟器也带 faketouch，所以只在
         * 真电视上生效。
         */
        if (!getPackageManager().hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)) {
            setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
        prefs = getSharedPreferences(PREF, MODE_PRIVATE);

        // 端上 jar 执行需要 catvod-api 拿到 Application 上下文
        com.github.catvod.Runtime.set(getApplicationContext());
        LocalSites.load(this);
        /*
         * 运行时模块（试点）：装了 node 模块就把 node 拉起来，跑在 :node 独立进程。
         * 独立进程是硬要求 —— node 崩是 native 级，不能带走主进程和网关。
         * 模块没装时这里直接跳过，行为与之前完全一致。
         */
        if (com.meow.tv.runtime.RuntimeModuleHost.nodeInstalled(this)) {
            try {
                startService(new Intent(this, com.meow.tv.runtime.NodeService.class));
                android.util.Log.i("MeowNode", "NodeService start requested");
            } catch (Throwable t) {
                android.util.Log.w("MeowNode", "start NodeService failed: " + t);
            }
        }

        String fromIntent = getIntent() == null ? null : getIntent().getStringExtra("url");
        if (fromIntent != null && !fromIntent.isEmpty()) prefs.edit().putString(K_URL, fromIntent).apply();
        // 深链：intent 里带的 hash（VIEW 数据 URI 的 #/play/... 部分）
        String hashFromIntent = hashOf(getIntent());
        if (hashFromIntent != null) pendingHash = hashFromIntent;
        String cfgFromIntent = getIntent() == null ? null : getIntent().getStringExtra("config");
        if (cfgFromIntent != null && !cfgFromIntent.isEmpty()) {
            prefs.edit().putString(K_CONFIG, cfgFromIntent).apply();
            pendingConfig = cfgFromIntent;
        }
        // 显式要求起端内网关：`--es localGateway 1`
        // 正常路径是页面点「启用本机执行」再调桥；这个参数只为自动化可测。
        // 必须丢后台线程：隔离模式下 start() 要轮询等端口最多 20 秒，
        // 主线程直调就是 20 秒无响应（ANR）。
        if (getIntent() != null && "1".equals(getIntent().getStringExtra("localGateway"))) {
            gwWanted = true;   // 没有它 gwKeeper 不看护：实测 :spider 崩后无人拉活
            final android.content.Context appCtx = getApplicationContext();
            new Thread(() -> {
                boolean up = GatewayServer.start(appCtx);
                android.util.Log.i("MeowTv", "localGateway=" + (up ? GatewayServer.baseUrl() : "FAILED"));
            }, "meow-gateway-boot").start();
        }
        String api = restoreBackendMode();
        // 地址指向端内网关 → 必须确认网关真的在跑（见 isLocalGateway 的注释）
        ensureLocalGateway(api);
        // 时钟核对与后端在哪无关：远端后端用远端时间签名，本地网关用设备时间签名，
        // 只要设备时钟错了，签名类源都会静默失败。区别只是"跟谁比"。
        TvBridge.checkClockSkewWhenReady(api);
        if (api == null || api.isEmpty()) {
            // ★ 没配后端时**不再弹配置页**，直接进主界面。
            //
            // 踩过：原来这里是 startActivity(SetupActivity) —— 配置页会压在主界面
            // 之上，用户第一眼看到的永远是它；配不上就困在那儿（那一版还 finish()
            // 了主界面，等于彻底进不去系统）。
            //
            // 现在统一由前端兜住：首页显示「后端未连接 + 去设置」，
            // 设置页「连接」分区里有完整的连接设置弹层（自动发现/扫码/手填）。
            // 好处是**同一个界面负责配置和展示**，不存在"两个地方都能改地址"
            // 的不一致，也不会再出现"配置页挡住系统"。
            loadBuiltin();
            return;
        }
        loadBuiltin();
    }

    /**
     * 加载内置前端。
     *
     * <p><b>为什么不再加载远端网页</b>：TV 上用遥控器敲网址是最劝退的一步，
     * 而且一旦那个 Web 服务没起，用户看到的就是黑屏。前端只有 2MB，
     * 直接打进 APK，用户只需要（或自动发现）一个后端 API 地址。
     *
     * <p>做法：assets/www 用 WebViewAssetLoader 挂到
     * {@code https://appassets.androidplatform.net/assets/www/}，
     * 后端口地址通过 {@code localStorage['meow.api']} 注入——前端
     * {@code resolveBase()} 第一优先级就是读它（见 api/index.ts）。
     */
    /**
     * WebView 内核版本够不够跑内置前端。
     *
     * <p><b>阈值是 Chromium 66，不是 73。</b>踩过的坑：曾按"es2017 构建目标 =
     * Chrome 58"定成 73，但真正的下限由 CSS 决定——Tailwind v4 输出
     * {@code @layer} 与 {@code color-mix()}，前者 Chrome 99 才有。虽然构建时
     * 已把 {@code @layer} 展平（见 vite-plugins/flattenCssLayers.ts），剩下
     * 用到的特性（CSS 变量、flex/grid、@supports）Chrome 66 都支持。
     *
     * <p>低于 66 一律挡下：那些内核连 ES6 都不完整，Vue 3 跑不起来，
     * 且不会有任何可读的报错——页面就是一片黑（壳的 windowBackground 是纯黑）。
     *
     * @return 可用的版本描述；不可用时返回 null
     */
    private String checkWebView() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo("com.google.android.webview", 0);
            int major = pi.versionCode / 1000000;
            String label = "WebView " + pi.versionName;
            // Chromium 的 versionCode 编码是 major*1000000 + minor*10000 + build
            if (major < 66) return null;
            return label;
        } catch (Exception e) {
            // 拿不到包信息：可能是 Chrome 提供内核、或厂商改过包名。
            // 这种情况**不拦**——猜测比放行更糟，真不行还有看门狗兜底。
            return "";
        }
    }

    /**
     * 判断一个后端地址是不是「端内网关」。
     *
     * <p>只认 127.0.0.1 / localhost / ::1 上的 {@link GatewayServer#port()}：
     * 别的地址是用户在设置页手填的远端后端，不该被本地逻辑接管。
     */
    static boolean isLocalGateway(String api) {
        if (api == null) return false;
        String u = api.trim().toLowerCase(java.util.Locale.ROOT);
        if (!u.startsWith("http")) u = "http://" + u;
        try {
            java.net.URI uri = java.net.URI.create(u);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null) return false;
            boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host)
                    || "::1".equals(host) || "[::1]".equals(host);
            return loopback && (port == GatewayServer.port() || port == -1);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 恢复上次的后端形态，返回本次要用的后端地址。
     *
     * <p><b>为什么需要它</b>（2026-11-04 用户报）：上次在设置里点了「启用本机执行」，
     * 关掉再打开又要去设置页开一次。原因是"启用本机执行"只由前端写了
     * {@code localStorage['meow.api']}，壳的 {@code prefs[K_API]} 还是旧值；
     * 启动时壳把 prefs 注入回 localStorage，本机选择就被覆盖掉了。
     *
     * <p>约定：
     * <ul>
     *   <li>{@code K_MODE=local}：地址可能被"清空地址/恢复默认"抹掉，但**形态**要恢复 ——
     *       这里把地址补回网关基址，由 {@link #ensureLocalGateway} 真正把网关拉起来</li>
     *   <li>{@code K_MODE=remote}：原样返回，绝不因为地址里恰好有 127.0.0.1 就改判</li>
     *   <li>老安装没有 K_MODE：按地址推断一次并补写，之后以显式标记为准</li>
     * </ul>
     */
    private String restoreBackendMode() {
        String mode = prefs.getString(K_MODE, "");
        String api = prefs.getString(K_API, "");
        if (mode.isEmpty()) {
            mode = isLocalGateway(api) ? "local" : (api.isEmpty() ? "" : "remote");
            if (!mode.isEmpty()) prefs.edit().putString(K_MODE, mode).apply();
        }
        /*
         * 上次启动留下的标记 = 网关没撑到稳定窗口进程就没了 → 记成"本机崩过"。
         *
         * ★ 只有 **32 位**才这么判（2026-11-06，78 号 MuMu 实测踩过）：
         *   `adb shell am force-stop`、系统低内存杀进程、用户手动强杀 ——
         *   这些都不是崩溃，但进程不会执行任何回调，K_GW_BOOT 必然残留。
         *   下次启动就被当成"上次崩过"→ 一次强杀 = **永久熔断**，
         *   首页从此停在「后端未连接」，只能去设置页手动点「启用本机执行」。
         *
         *   熔断本来是给极米 Z6X Pro（32 位 armeabi-v7a，起网关 6/6 SIGABRT）
         *   兜 boot-loop 的；64 位设备网关崩过的案例是 0，收益远小于误伤。
         */
        if (prefs.getLong(K_GW_BOOT, 0L) > 0) {
            if (!android.os.Process.is64Bit()) {
                prefs.edit().remove(K_GW_BOOT).putBoolean(K_GW_FAIL, true).apply();
            } else {
                prefs.edit().remove(K_GW_BOOT).apply();
            }
        }
        if (!"local".equals(mode)) return api;
        /*
         * ★ 自动拉起网关的两道闸（2026-11-04 用户报"一进就闪退"）：
         *
         * 1. **32 位一律不自动起**。极米 Z6X Pro（armeabi-v7a / Android 9）实测
         *    起端内网关 6/6 崩：ART `ConcurrentCopying::MarkNonMoving` SIGABRT，
         *    进程直接没。这不是概率"有时能好"，而是这台设备的宿命。
         * 2. 上次自动启动没走完（K_GW_BOOT 残留）也不起，等用户手动再试。
         *
         * 拦下时**必须把地址也清掉**：地址留着没人监听，首页只会停在"连不上"，
         * 比"未配置 → 去设置"更让人困惑（而且注入走的是 prefs[K_API]，
         * 不看本方法的返回值）。
         */
        /*
         * 32 位在**隔离模式**下允许自动起：网关跑在 :spider 进程里，崩的只是
         * 网关（START_STICKY 重拉 + gwKeeper 兜底），主进程安全。非隔离的
         * 32 位仍不自动起 —— 主进程会被 ART abort，一进就闪退。
         *
         * ★ 隔离模式下 **不看 K_GW_FAIL**（2026-11-05 修正）：这个标记原本表示
         * "上次自动起的网关没撑过稳定窗，别再来一次"。而隔离架构里
         * ":spider 崩掉" 是**常态**（32 位 ART 的 GC 缺陷，实测 2~3 分钟一次），
         * 拿它拦自动启动等于把功能永久关掉，用户必须每次手动点「启用本机执行」。
         * boot-loop 风险由 gwKeeper 的 5/15/45/120s 退避兜住。
         */
        boolean isolate = GatewayServer.isolateMode();
        /*
         * 64 位忽略 K_GW_FAIL：这个标记只可能来自"强杀被当成崩溃"的误判
         * （见上面 K_GW_BOOT 的注释），拿它拦住自动启动 = 一次事故永久关功能。
         * 32 位非隔离仍然尊重它 —— 那是真崩（ART abort，一进就闪退）。
         */
        boolean canAuto = (android.os.Process.is64Bit() || isolate)
                && (isolate || android.os.Process.is64Bit()
                        || !prefs.getBoolean(K_GW_FAIL, false));
        if (!canAuto) {
            prefs.edit().remove(K_API).apply();
            return "";
        }
        api = GatewayServer.baseUrl();
        prefs.edit().putString(K_API, api).apply();
        gwWanted = true;   // 形态是本地网关 → gwKeeper 必须看护（START_STICKY 反复崩后会被系统放弃）
        return api;
    }

    /**
     * 后端地址指向端内网关时，确保网关进程内实例真的起来了。
     *
     * <p><b>踩过（症状："昨天 TV 内置能播，今天不行"）</b>：网关是**进程内对象**，
     * 进程一死监听就没了，但 {@code prefs[K_API]} 里的
     * {@code http://127.0.0.1:9978} 还留着。而前端的 {@code bootstrapTvLocal()}
     * 见到 {@code localStorage['meow.api']} 非空就直接 return —— 那条分支是为
     * 「尊重用户手填的远端地址」设计的，对本地网关恰恰是错的。结果没有任何人再调
     * {@link GatewayServer#start}：**地址在、监听没了**，页面每个请求都是
     * connection refused，首页停在「后端未连接」。
     *
     * <p>判据只能放在原生侧：只有这里知道网关到底起没起。前端手里的地址无论
     * 新旧都是同一串字符。
     *
     * <p>为什么放后台线程：{@code ApiServer} 首次 {@code Class.forName} 要装
     * 44 个站点、加载 dex 里的 spider 类，实测占了冷启动的首屏时间。主线程等它
     * 会多一段黑屏。代价是页面可能先发一轮失败请求，所以网关卡就绪后立刻
     * reload 一次（与 {@code bootstrapLocal} 成功后走的是同一条路）。
     */
    private void ensureLocalGateway(String api) {
        if (!isLocalGateway(api)) return;
        // 时钟核对与"网关是不是刚启动"无关：同一进程里 Activity 重建（切后台回来、
        // 改完设置回首页）都会再走 onCreate，而那种情况下 isRunning() 已是 true，
        // 把检查放在"刚启动"分支里就永远不会执行 —— 实测漏过。
        //
        // 但也不能在这里直接调：此刻网关可能还没 boot()，core.LogBuffer 没就绪，
        // 告警会写进一个空的缓冲区（实测 /api/logs 里查不到）。所以延后到
        // 端口可连之后再探。
        if (GatewayServer.isRunning()) return;
        /*
         * K_GW_FAIL 只在**非隔离 + 32 位**下拦自动启动。
         *
         * 隔离模式里":spider 崩过"是设计内的常态（见 restoreBackendMode 的注释）；
         * 64 位忽略它，因为这个标记只可能来自"强杀被当成崩溃"的误判 ——
         * 一次 adb force-stop 就永久关掉本机执行，代价远大于收益。
         */
        if (!GatewayServer.isolateMode() && !android.os.Process.is64Bit()
                && prefs.getBoolean(K_GW_FAIL, false)) {
            android.util.Log.w("MeowTv", "skip gateway auto-start: last boot crashed");
            return;
        }
        final android.content.Context ctx = getApplicationContext();
        // ★ 不在 onCreate 里立刻起：等首屏那波类加载/解析过去（见 GW_BOOT_DELAY_MS）
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (GatewayServer.isRunning()) return;
            new Thread(() -> {
                startGatewayWatched(ctx);
                // 首屏那一轮请求打的是"还没在听的端口"，必须让前端重来一次。
                // 已经加载成功时这次 reload 也只是白闪一下，比永远连不上好得多。
                applyBootstrappedApiBase();
            }, "meow-gateway-boot").start();
        }, GW_BOOT_DELAY_MS);
    }

    /**
     * 起网关并把它纳入"稳定性观察"。
     *
     * <p>启动前落 {@link #K_GW_BOOT}，**存活满 {@link #GW_STABLE_MS} 才清**。
     * 早先版本在"端口就绪"就清标记，而实测崩溃发生在就绪后 1~2 秒（GC 线程
     * SIGABRT），标记已经清掉 → 下次启动照旧自动拉起 → 无限闪退。
     *
     * @return 网关是否已就绪
     */
    static boolean startGatewayWatched(android.content.Context ctx) {
        android.content.SharedPreferences sp =
                ctx.getSharedPreferences(PREF, MODE_PRIVATE);
        sp.edit().putLong(K_GW_BOOT, System.currentTimeMillis()).apply();
        long t0 = android.os.SystemClock.elapsedRealtime();
        boolean up = GatewayServer.start(ctx);
        android.util.Log.i("MeowTv", "localGateway(预启)="
                + (up ? GatewayServer.baseUrl() : "FAILED")
                + " 耗时 " + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
        if (!up) {
            sp.edit().remove(K_GW_BOOT).apply();
            return false;
        }
        TvBridge.awaitGatewayReady();
        // 还没到稳定窗口：标记继续留着，进程若在这段时间死掉，下次启动就知道
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!GatewayServer.isRunning()) return;
            sp.edit().putBoolean(K_GW_FAIL, false).remove(K_GW_BOOT).apply();
        }, GW_STABLE_MS);
        return true;
    }

    private void loadBuiltin() {
        String wv = checkWebView();
        if (wv == null) {
            // 明确的失败原因写进 prefs，配置页会读出来显示
            prefs.edit().putString("webview_too_old", "1").apply();
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        prefs.edit().remove("webview_too_old").apply();
        ensureWeb();
        /*
         * 电视/模拟器：CSS cursor:none 在部分 WebView（MuMu、投影仪）管不到
         * 系统指针图标。这里用系统 pointer icon 做同一套「3 秒空闲隐藏」。
         */
        installPointerAutoHide();
        setContentView(web);
        failed = false;
        // 重试/重新进入时探针的重试预算也要归零，否则"错误页 → 重试"永远不会再探一次
        auditRetried = false;
        // ★ 没有后端地址也要把页面加载起来。原来这里 startActivity(Setup)+finish，
        //   等于"没配好就永远到不了系统"。现在页面自己能开「连接设置」弹层，
        //   所以直接进主界面、由前端提示去填地址即可。
        /*
         * ★ 深链：intent 带的 `#/play/...` 必须拼到 loadUrl 里。
         *
         * <p>踩过：原来这里硬编码 `http://meow.local/index.html`，`K_URL`
         * 只写不读 —— 外部 `am start -a VIEW -d 'http://meow.local/index.html#/play/...'`
         * 进来后 hash 被重置成 `#/`，用户停在首页。表现为"深链不生效"，
         * 而实际上路由、播放本身都是好的（实测会话内直接写 hash 能播，
         * readyState 4、currentTime 正常走）。
         */
        final String home = "http://meow.local/index.html"
                + (pendingHash == null ? "" : pendingHash);
        // localStorage 是 per-origin 的，必须在**该 origin 下**才能写。
        // 顺序：先加载一次拿 origin → 写入 meow.api → 重载。
        // 重载后前端 resolveBase() 立刻读到正确地址，不会先打一次空 base。
        injectPending = true;
        web.loadUrl(home);
        armLoadWatchdog("内置前端");
    }

    /**
     * 首次加载完成后注入后端地址并重载。
     *
     * <p>只做一次：重载后会再次触发 onPageFinished，不设标志会无限重载。
     */
    private boolean injectPending;

    /**
     * 本次会话里用户开过本机网关（手动「启用本机执行」）。
     *
     * <p>只活一个进程生命周期：冷启动不带它，所以 32 位设备的"启动不自动起网关"
     * 仍然成立；它只用于**运行中被崩掉后自动拉回来**。
     */
    static volatile boolean gwWanted = false;

    /**
     * 看护重试的退避表（ms）：崩一次可能启动时再崩，密集重试只会连环崩。
     * 表用完后**不放弃**，按最后一档持续重试：这台设备（32 位 ART）的
     * :spider 是"崩-拉回-再崩"的常态，放弃重试等于功能永久不可用。
     */
    /*
     * 退避表要短。上一版是 5/15/45/120 秒 —— 端口先开之后冷启动只要 4 秒，
     * 45/120 秒纯属惩罚用户：实测压测里 :spider 崩一次要等 2~3 分钟才回来
     * （连续三轮 START-FAIL）。现在最坏 20 秒一轮，配合 5 秒的路由 tick
     * 约 25 秒内必有一轮尝试。
     */
    private static final long[] GW_RETRY_BACKOFF = {3_000L, 6_000L, 12_000L, 20_000L};
    private int gwRetry = 0;
    private long gwNextTryAt = 0L;
    private boolean gwGaveUpNotified = false;
    private final android.os.Handler gwKeeper =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable gwKeepTask = new Runnable() {
        @Override
        public void run() {
            try {
                /*
                 * 看护条件不能只靠 gwWanted 这个内存标志（2026-11-05 实测踩过）：
                 * 它只在"壳主动起网关"和"前端点了启用本机执行"两条路上被置位，
                 * 而**前端走 localStorage 直接打 127.0.0.1:9978** 时没人置位 ——
                 * 于是 :spider 崩掉之后没有任何人拉回，压测里连出三行 START-FAIL。
                 * 真正的判据是"当前后端地址指向本机网关"，这个状态落在 prefs 里，
                 * 跨进程重启也在。
                 */
                boolean want = gwWanted || isLocalGateway(prefs.getString(K_API, ""));
                if (want && GatewayServer.isolateMode() && !GatewayServer.httpListening()) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now >= gwNextTryAt) {
                        long delay = gwRetry < GW_RETRY_BACKOFF.length
                                ? GW_RETRY_BACKOFF[gwRetry] : GW_RETRY_BACKOFF[GW_RETRY_BACKOFF.length - 1];
                        if (gwRetry < GW_RETRY_BACKOFF.length) gwRetry++;
                        gwNextTryAt = now + delay;
                        startService(new Intent(MainActivity.this, GatewayService.class));
                        android.util.Log.i("MeowTv", "gateway keeper: restart #" + gwRetry
                                + " next in " + delay + "ms");
                        if (gwRetry >= GW_RETRY_BACKOFF.length && !gwGaveUpNotified) {
                            gwGaveUpNotified = true;
                            android.widget.Toast.makeText(MainActivity.this,
                                    "本机源反复崩溃，正在自动恢复，可稍后重试搜索",
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                } else if (GatewayServer.httpListening()) {
                    gwRetry = 0;
                    gwNextTryAt = 0L;
                    gwGaveUpNotified = false;
                }
            } catch (Throwable ignored) {
            }
            gwKeeper.postDelayed(this, 5_000L);
        }
    };

    /** 来自 intent 的深链 hash（形如 "#/play/AppYQK?id=..."），没有则为 null。 */
    private String pendingHash;

    /**
     * 从 intent 里取 hash 段。
     *
     * <p>两种来源都要认：
     * <ul>
     *   <li>{@code getData()}：{@code am start -a VIEW -d 'http://meow.local/index.html#/play/...'}，
     *       这是系统/第三方唤起应用的标准做法（也方便自动化测试）</li>
     *   <li>{@code --es url}：老的自动化入口，值里可能自带 {@code #/...}</li>
     * </ul>
     *
     * <p>只接受以 `#/` 开头的片段：别的 fragment 拼进 URL 会让前端路由 404。
     */
    private String hashOf(android.content.Intent intent) {
        if (intent == null) return null;
        String s = null;
        if (intent.getData() != null) s = intent.getData().getFragment();
        if (s == null || s.isEmpty()) {
            String u = intent.getStringExtra("url");
            if (u != null && u.contains("#/")) s = u.substring(u.indexOf("#/") + 1);
        }
        if (s == null || s.isEmpty()) return null;
        if (!s.startsWith("/")) s = "/" + s;
        return "#" + s;
    }

    /**
     * 前端零配置自启成功后，把网关地址立刻注入并重载。
     *
     * <p><b>为什么需要这个入口</b>：首屏的注入发生在 {@code onPageFinished}
     * → {@code applyApiBaseIfNeeded()}，那一刻 {@code prefs[K_API]} 还是空的
     * （{@code bootstrapLocal} 要等页面 JS 跑起来才被调用），于是它走
     * 「空地址 → injectApiBase("")」分支把 localStorage 清掉，并且**已经消费完
     * {@code injectPending}**。等前端随后把地址写进 prefs 时，再没有任何路径
     * 会去注入它 —— 表现就是「网关起来了、日志说写入成功，首页永远显示未连接」。
     *
     * <p>由 {@code TvBridge.bootstrapLocal()} 在写完 prefs 后调用。
     */
    void applyBootstrappedApiBase() {
        runOnUiThread(() -> {
            if (web == null) return;
            String api = prefs.getString(K_API, "");
            if (api == null || api.isEmpty()) return;
            injectApiBase(api);
            injectedApi = api;
            // 用 reload 而不是 loadUrl：保持同一个 origin，localStorage 才在
            web.reload();
            // 重载不挂看门狗：都是本机 assets，慢≠打不开（见 armLoadWatchdog 注释）
        });
    }

    /**
     * 设置页改完后端地址后，重新注入并加载。
     *
     * <p>为什么不能简单 reload：`meow.api` 是壳在 onPageFinished 里写进
     * localStorage 的，普通 reload 会先跑一遍旧值（页面立刻用旧地址发请求），
     * 再被重新注入、再 reload —— 观感是"改了地址但要闪两下"。
     * 这里直接把 injectPending 置真再 loadUrl，走的是首次加载那条路径。
     */
    void reloadWithSavedBackend() {
        failed = false;
        auditRetried = false;
        injectPending = true;
        if (web != null) {
            web.loadUrl("http://meow.local/index.html");
            // 重载不挂看门狗：都是本机 assets，慢≠打不开（见 armLoadWatchdog 注释）
        }
    }

    /**
     * 需要时注入后端地址并重载。
     *
     * @return true 表示本次触发了 reload —— 调用方必须立即返回，
     *         不要再对正在被替换的页面做任何注入/探针（见 onPageFinished 的注释）。
     */
    private boolean applyApiBaseIfNeeded() {
        if (!injectPending) return false;
        injectPending = false;
        if (web == null) return false;
        String api = prefs.getString(K_API, "");
        if (api.isEmpty()) {
            // 没有后端地址：清掉可能残留的旧值（用户删了地址、或首次进入），
            // 让前端 resolveBase() 退回同源空串 —— 它会显示"未连接"并引导去设置页。
            // 不再 reload：此时页面已经是可用的，reload 只会白闪一下。
            injectApiBase("");
            injectedApi = "";
            return false;
        }        injectApiBase(api);
        // 同步记录：onResume 靠它判断"地址变了没"，不记会重复 reload
        injectedApi = api;
        // 用 reload 而不是 loadUrl：保持同一个 origin，localStorage 才在
        web.reload();
        // 重载也要重新上表：否则 reload 卡住时没有任何兜底
        // 重载不挂看门狗：都是本机 assets，慢≠打不开（见 armLoadWatchdog 注释）
        return true;
    }

    /** assets 里的文件按扩展名给 Content-Type；错了不会白屏，但会影响字体/图片。 */
    private static String guessMime(String path) {        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".webmanifest")) return "application/manifest+json";
        return "application/octet-stream";
    }

    /** 把后端地址写进 WebView 的 localStorage（前端 resolveBase 的第一优先级）。 */
    private void injectApiBase(String api) {
        // 空地址时直接删键，而不是 setItem('')：resolveBase() 对空串虽也能退回
        // 同源，但留着空键会让"设置页读到 api='' 还是 undefined"在不同路径上不一致。
        String js = (api == null || api.isEmpty())
                ? "try{localStorage.removeItem('meow.api');"
                  + "localStorage.removeItem('meow.api.scope')}catch(e){}"
                : "try{localStorage.setItem('meow.api'," + jsonStr(api) + ");"
                  + "localStorage.removeItem('meow.api.scope')}catch(e){}";
        web.evaluateJavascript(js, null);
    }

    /** 本次页面生命周期里是否已经补过设备令牌（防 reload 自环）。 */
    private boolean authTokenRestored = false;

    /**
     * 把壳里保存的设备令牌补回 localStorage。
     *
     * <p>为什么需要（见 {@link #K_AUTH}）：重装 APK 后 WebView 的 localStorage
     * 在部分 ROM / 模拟器上会被清空，而页面 JS 在 onPageFinished 之前就跑完了
     * —— 前端判定"没有令牌"并跳到登录页，用户得重新配对一次。
     *
     * <p>两个细节：
     *  · **只补不覆盖**：页面里已有值（刚配对、或用户换了新令牌）优先；
     *  · 如果当前正停在登录页，补完 **reload 一次**让前端带着令牌重新判定。
     *    只补不 reload 的话，登录页已经在 `/login` 上、没有任何导航会重新触发
     *    守卫（用户看到的是"令牌明明还在却让我配对"）。`authTokenRestored`
     *    保证一个页面生命周期只 reload 一次，避免守卫再次送回时死循环。
     *
     * @return true 表示已发起 reload，调用方必须立即返回（同 applyApiBaseIfNeeded）
     */
    private boolean restoreAuthToken(String url) {
        if (web == null) return false;
        String tk = prefs.getString(K_AUTH, "");
        if (tk == null || tk.isEmpty()) return false;
        String js = "try{var k='meow.authToken';if(!localStorage.getItem(k))localStorage.setItem(k,"
                + jsonStr(tk) + ")}catch(e){}";
        if (url != null && url.contains("#/login") && !authTokenRestored) {
            authTokenRestored = true;
            web.evaluateJavascript(js, v -> web.reload());
            return true;
        }
        authTokenRestored = true;
        web.evaluateJavascript(js, null);
        return false;
    }

    private Runnable watchdog;

    /** 渲染进程崩溃的时间窗起点与次数（见 onRenderProcessGone 的循环保护）。 */
    private long rendererGoneAt = 0L;
    private int rendererGoneCount = 0;
    /** 窗口内允许的重建次数：超过就走原生失败页，不再自动重建。 */
    private static final long RENDERER_GONE_WINDOW_MS = 5 * 60 * 1000L;
    private static final int RENDERER_GONE_MAX = 3;

    /**
     * 加载完成后再看一眼样式到底有没有生效。
     *
     * <p><b>为什么需要这一步</b>：CSS 解析失败不算"加载失败"——页面 DOM 正常、
     * 图片正常、{@code onPageFinished} 正常回调，8 秒看门狗也会被取消，所以
     * 一条提示都不会出现。但老内核（Chrome 99 以下）遇到 {@code @layer}
     * 会把整块规则丢掉，实际看到的是元素全无布局、配着纯黑背景 = 黑屏。
     *
     * <p>判据选 {@code .flex} 的 computed display：它是 Tailwind 最基础的工具类，
     * 且展平后就是一条裸的 {@code .flex{display:flex}}——只要样式表被应用，
     * 结果必定是 flex。
     *
     * <p>探针元素要现造：首屏可能因为数据没回来而没有任何 {@code .flex} 节点，
     * 拿现成元素会把"正常"误判成"样式没生效"。
     */
    private void auditStyleApplied(WebView view) {
        if (view == null) return;
        view.evaluateJavascript(
                "(function(){try{"
                        // body 未就绪时明确区分：这是"探针跑不了"，不是"样式没生效"
                        + "if(!document.body) return 'err:no-body';"
                        + "var d=document.createElement('div');"
                        + "d.className='flex';"
                        + "d.style.position='absolute';d.style.left='-9999px';"
                        + "document.body.appendChild(d);"
                        + "var v=getComputedStyle(d).display;"
                        + "document.body.removeChild(d);"
                        + "return v==='flex'?'ok':'nostyle:'+v;"
                        + "}catch(e){return 'err:'+e.message}})()",
                value -> {
                    if (value == null) return;
                    String v = value.replace("\"", "").trim();
                    if (v.startsWith("ok")) return;
                    // ★ 只有 nostyle: 才是"样式确实没生效"（@layer 被丢的典型表现）。
                    //   err: 是探针自己跑不起来——页面还在导航、body 不在、脚本被中断。
                    //   把它也判成失败会让"配置后重载"这种正常时序直接弹错误页
                    //   （实测冷启动 3/3 命中，且重试永远失败）。
                    //
                    // ★ nostyle 同样要重试一次。onPageFinished 在 readyState 还是
                    //   'interactive' 时就会触发，此时 <link rel=stylesheet> 可能
                    //   还没应用，getComputedStyle 拿到的就是初始值 —— 探针会说
                    //   "样式没生效"，而页面其实是好的。实测（模拟器冷启动）：
                    //   错误页弹出时用 MCP 复测同一个表达式，结果已是 display:flex，
                    //   样式表 785 条规则正常加载。误报的代价比漏报高得多（用户
                    //   看到"打不开"就退出去了），所以先给 1.5s 再下结论。
                    if (v.startsWith("err:") || v.startsWith("nostyle:")) {
                        if (failed || auditRetried) return;
                        auditRetried = true;
                        ui.postDelayed(() -> {
                            if (!failed && web != null) auditStyleApplied(web);
                        }, 1500);
                        return;
                    }
                    if (failed) return;
                    failed = true;
                    showLoadError(view.getUrl(),
                            "页面样式没有生效（" + v + "）。通常是系统 WebView "
                                    + "版本太旧——本机需要 Chrome 66 及以上。"
                                    + "可在应用商店更新「Android System WebView」。");
                });
    }

    /**
     * 加载看门狗。
     *
     * <p><b>为什么必须有它</b>：{@code onReceivedError} 在部分 WebView 版本上
     * 对主文档失败**不回调**（实测：填一个拒绝连接的地址，页面就是纯黑屏，
     * 日志里连 ERR_CONNECTION_REFUSED 都没有）。只靠回调会把用户永远困在黑屏。
     *
     * <p>★ 它只负责"一个字都没画出来"那种情况。**一旦有内容提交（
     * {@code onPageCommitVisible}）或加载进度过 10%，立刻撤掉**：
     * 本机前端在这台 MuMu 上实测 DOMInteractive **14.3 秒**（解析主 bundle
     * 就占大半），而 onPageFinished 要等 load 事件 —— 拿 8 秒去卡它，会在
     * **页面其实完好**的情况下盖一张"打不开这个地址"的整屏错误页。用户看到
     * 的是"点了没反应/卡死"，实际是错误页压在正常界面上（2026-11-11 报障）。
     *
     * <p>窗口放到 30 秒：真·地址不通仍然等不到任何提交，30 秒后照常报错。
     * ★ 12 秒还不够：这台 MuMu 冷启动时网关预热和前端解析抢 CPU，实测
     *   load 事件 14.3 秒才到（而且预热期间更慢），12 秒照样误杀。
     *
     * <p>★ <b>只在首次加载时挂</b>。重载（自启注入后端地址 / 换后端 / 重新连接）
     * 一律不挂：那时页面早就活着，重载加载的还是本机 assets，"地址不通"根本不
     * 成立；而重载要重新解析一遍 bundle（十几秒），定时判死即使放到 30 秒、
     * 即使加"evaluateJavascript 探活"，都会在重载进行中误判（实测 2026-11-11：
     * 探活回调要等新上下文就绪，5 秒等不到就报错）。重载失败由
     * {@code onReceivedError} 兜底。
     */
    private void armLoadWatchdog(final String url) {
        if (watchdog != null) ui.removeCallbacks(watchdog);
        watchdog = () -> {
            if (!failed) {
                failed = true;
                showLoadError(url, "30 秒内没有加载完成，可能地址不通或后端没启动。");
            }
        };
        ui.postDelayed(watchdog, 30000);
    }

    /** 页面已经在画了 → "打不开这个地址"不再成立，撤掉看门狗。 */
    private void cancelLoadWatchdog() {
        if (watchdog != null) {
            ui.removeCallbacks(watchdog);
            watchdog = null;
        }
    }

    /**
     * 加载失败页：说清原因 + 两步退路。
     *
     * <p>重点是**不要只有一句"加载失败"**——用户遇到的多数情况是地址填错
     * （比如把 config.json 填成了网页地址），提示里要直接指出来。
     */
    private void showLoadError(String url, String reason) {
        /*
         * 用页面同步下来的主题色（InputTheme）：这是壳兜底的原生页，
         * 用户平时看不到，一旦出现说明 WebView 已经打不开页面 —— 这时候
         * 它必须"看着还是猫窝"，否则像另一个应用弹出的报错框。
         * 读不到主题时 InputTheme 自己会退回品牌深色。
         */
        InputTheme t = InputTheme.load(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(t.base());
        int pad = (int) (56 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad / 2, pad, pad / 2);
        root.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("打不开这个地址");
        title.setTextColor(t.ink());
        title.setTextSize(24);
        root.addView(title);

        TextView detail = new TextView(this);
        String shown = url == null ? "" : url;
        StringBuilder sb = new StringBuilder();
        sb.append(shown).append('\n');
        if (!reason.isEmpty()) sb.append(reason).append('\n');
        if (shown.contains("config.json")) {
            sb.append('\n');
            sb.append("看起来你把配置文件地址填成了网页地址。").append('\n');
            sb.append("「CatVod 配置地址」要填在配置页的第二个框，第一个框只填到端口。");
        } else {
            sb.append('\n');
            sb.append("常见原因：后端没启动、电视和它不在同一个 WiFi、端口写错。");
        }
        detail.setText(sb.toString());
        detail.setTextColor(t.ink3());
        detail.setTextSize(15);
        detail.setPadding(0, pad / 3, 0, pad / 3);
        root.addView(detail);

        Button fix = new Button(this);
        // 措辞说明它去哪儿：这是 WebView 已经加载失败后的兜底页，
        // 此时页面进不去、也就进不了设置页，原生配置页是唯一还能操作的地方。
        fix.setText("修改后端地址");
        fix.setAllCaps(false);
        fix.setTextColor(t.ink());
        fix.setBackgroundTintList(android.content.res.ColorStateList.valueOf(t.panel()));
        fix.setOnClickListener(v -> openSetup());
        root.addView(fix);

        Button retry = new Button(this);
        retry.setText("重试");
        retry.setAllCaps(false);
        retry.setTextColor(t.onAccent());
        retry.setBackgroundTintList(android.content.res.ColorStateList.valueOf(t.accent()));
        retry.setOnClickListener(v -> {
            // 重试就是重新加载内置前端（后端地址已经从 prefs 读）
            loadBuiltin();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 6;
        root.addView(retry, lp);

        setContentView(root);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        fix.setFocusable(true);
        fix.setFocusableInTouchMode(true);
        fix.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        /*
         * 原生输入框弹出时，方向键/确认键必须交给系统，不能转给 tv-nav。
         *
         * 否则：TvBridge.inputText 弹的 AlertDialog 里，用户既挪不了光标
         * 也点不了「确定」——按键全被下面这段吃掉了（实测会卡死在对话框）。
         * 判据放在最前面，连 ACTION_UP 也一起放行。
         */
        if (NativeInput.active()) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        /*
         * ESC ≡ 返回（横屏 / MuMu 模拟器 / 外接键盘）。
         *
         * 遥控器发的是 KEYCODE_BACK、走 onBackPressed；模拟器与外接键盘按 Esc
         * 发的是 KEYCODE_ESCAPE —— 不映射就是「方向键能导航，Esc 退不出去」。
         * 转给 onBackPressed 而不是自己 goBack()，是为了复用那套
         * 「先问页面 __meowTvConsumeBack 再决定」的逻辑（播放页的弹幕/选集面板）。
         * 只认 DOWN 且不连发，UP 一并吞掉，避免一次按键退两层。
         */
        if (code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                onBackPressed();
            }
            return true;
        }
        /*
         * 菜单键：卡片上的「移除 / 详情」是 hover 才出现的按钮，遥控器够不到。
         * 电视的惯例是选中卡片后按菜单键，列出"这张卡片还能做什么"。
         * 统一转给页面的 __meowTvMenu（页面自己知道当前卡片是什么）。
         */
        if (code == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (web == null) return super.dispatchKeyEvent(event);
            web.evaluateJavascript("window.__meowTvMenu&&window.__meowTvMenu()", null);
            return true;
        }
        boolean isNav = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (!isNav || web == null) return super.dispatchKeyEvent(event);
        /*
         * 输入框正在编辑：方向键/回车必须交给 WebView（光标移动、输入法选词）。
         * 不交的话壳会把方向键拿去搬焦点（跳到背景导航），再按 OK 就误触背景；
         * 输入法里也没法用方向键选词（2026-11-03 修，状态由页面 focusin/focusout 推来）。
         */
        if (TvBridge.isEditing()) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        /*
         * 长按不连发：原来每个 repeat 的 ACTION_DOWN 都会转成一次 enter，
         * 前端只做了 250ms 最小间隔 → 长按 OK 变成每秒 4 次点击
         * （详情页连切集、列表连删除）。方向键保留连发（长列表里翻页有用）。
         */
        if (event.getRepeatCount() > 0
                && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            return true;
        }
        String action;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: action = "up"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: action = "down"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: action = "left"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: action = "right"; break;
            default: action = "enter"; break;
        }
        web.evaluateJavascript("window.__meowTvNav&&window.__meowTvNav('" + action + "')", null);
        return true;
    }

    /**
     * 返回键先问页面，页面不要才自己处理。
     *
     * <p><b>为什么必须问</b>：播放页有嵌套的可关闭层（弹幕面板/选集面板/更多菜单/
     * 伪全屏），它靠 {@code __meowTvKeyOwner} + Esc 处理这些。而壳原先直接
     * {@code goBack()}，页面上的 Esc 分支**永远不会被触发**——表现为
     * 「面板开着按返回，整个播放页退出了」，用户想关面板却退出了整集。
     *
     * <p>做法：先异步问页面「你要不要吃掉这次返回」，页面通过
     * {@code window.__meowTvConsumeBack()} 返回 true 表示已处理。
     * 因为是异步，不能直接同步拿结果，所以用「延后一拍再决定」：
     * 页面同步返回 false 时立刻走原逻辑，返回 true 就什么都不做。
     */
    @Override
    public void onBackPressed() {
        if (web == null) {
            /*
             * 没有 WebView（还没建起来 / 已被回收）→ 直接退出应用。
             *
             * 踩过：原来是 openSetup()，用户按一次返回就被丢进配置页。
             * 而配置能力早已搬到「设置 → 连接」的弹层里，配置页不再承担
             * 任何"必须先过这一关"的职责——把它当成返回键的兜底毫无道理。
             */
            finish();
            return;
        }
        web.evaluateJavascript(
                "(function(){try{return window.__meowTvConsumeBack&&window.__meowTvConsumeBack()?'1':'0'}catch(e){return '0'}})()",
                value -> {
                    boolean consumed = value != null && value.contains("1");
                    if (consumed) return; // 页面处理了（关面板/退全屏），壳不动
                    fallbackBack();
                });
    }

    /** 页面不要这次返回时的原逻辑。 */
    private void fallbackBack() {
        // 白屏时 WebView 也可能有历史，所以在首页或加载失败时一律回配置页
        if (failed && web != null) {
            web.loadUrl("about:blank");
        }
        /*
         * ★ 先问页面"能不能自己回退一页"，再考虑 WebView 历史。
         *
         * 踩过（实测 MuMu，详情页按返回直接把应用退到桌面）：
         * Vue Router 用的是 **hash 路由 + pushState**，而 WebView 的
         * `canGoBack()` 只看它自己的历史栈。SPA 在同一次文档加载里
         * push 再多路由，`canGoBack()` 也可能仍是 false ——
         * 于是"从详情页返回"被判成"已经到根页面"，直接 finish()。
         *
         * 页面侧多的是路由信息（router.canGoBack() / 当前路由名），
         * 让它先决定，壳只在它也说"到头了"时才退出。
         */
        if (web != null && !failed) {
            web.evaluateJavascript(
                    "(function(){try{return window.__meowTvRouteBack&&window.__meowTvRouteBack()?'1':'0'}catch(e){return '0'}})()",
                    value -> {
                        if (value != null && value.contains("1")) return;
                        if (web != null && web.canGoBack() && !failed) {
                            web.goBack();
                        } else {
                            finish();
                        }
                    });
            return;
        }
        finish();
        return;
    }

    /** 旧的直接判定，保留以便对照（不再调用）。 */
    private void fallbackBackLegacy() {
        if (failed && web != null) {
            web.loadUrl("about:blank");
        }
        if (web != null && web.canGoBack() && !failed) {
            web.goBack();
            return;
        }
        /*
         * 已经到根页面（没有历史可退）→ 退出应用，**不再拉起配置页**。
         *
         * 踩过：原来是 startActivity(SetupActivity)，用户按一次返回就被丢进
         * 配置页——而他只是想退出应用。配置页现在只是「设置 → 连接」里的一个
         * 弹层入口，没有"必须停在这里"的理由。
         *
         * 首页的「再按一次退出」两段语义由前端接管
         * （App.vue 的 __meowTvConsumeBack）：页面侧第一次按返回会吃掉事件并
         * 提示，第二次才放行到这里。所以走到这里就等于用户已经确认退出。
         */
        finish();
    }

    /** 拉起配置页。用独立 Activity：配置页永远能退出，不会把用户困住。 */
    private void openSetup() {
        startActivity(new Intent(this, SetupActivity.class));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 外部 intent 的 url 参数现在表示"后端地址"（前端已内置）
        String u = intent == null ? null : intent.getStringExtra("url");
        if (u != null && !u.isEmpty()) {
            prefs.edit().putString(K_API, u).putString(K_URL, u).apply();
            loadBuiltin();
            return;
        }
        // 深链（含投屏接收页）：singleTask 下页面已加载，直接改 hash
        final String h = hashOf(intent);
        if (h != null) {
            // 与冷启动路径一致：hashOf 已经解码过一次，值仍是单层编码，交给 Vue 再解
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (web != null) {
                        web.evaluateJavascript(
                                "location.hash=" + org.json.JSONObject.quote(h), null);
                    }
                }
            });
        }
    }

    /**
     * 页面失去前台时暂停 WebView 里的媒体。
     *
     * <p><b>为什么必须有</b>（2026-11-02，用户报「旧片音轨 + 新片画面重叠」）：
     * NativePlayerActivity（ExoPlayer）是**另一个 Activity**，盖上来时本页只走
     * onPause，不销毁。Android 不会替你暂停 WebView 里的 {@code <video>} ——
     * 它只在 {@code pauseTimers()} 时才冻结 JS 定时器，而媒体解码是原生层在跑。
     * 结果：ExoPlayer 出画面，WebView 那一路继续放声音。
     *
     * <p>这正是"切换/返回/原生兜底"场景里最难复现的一类重叠：Web 端的
     * onBeforeUnmount / stopMedia 全都跑不到，因为页面根本没卸载。
     *
     * <p>用 evaluateJavascript 调页面的统一暂停入口（同时暂停 + 记住播放意图），
     * onResume 再按那条意图恢复，避免"投屏/原生播放回来后 Web 这边不动了"。
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) {
            web.evaluateJavascript(
                    "(function(){try{window.__meowWebviewPause&&window.__meowWebviewPause()}catch(e){}})()",
                    null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 与 onPause 的 __meowWebviewPause 配对：只有"被系统暂停过"才恢复，
        // 用户自己按暂停的不动（意图由页面自己记）。
        if (web != null) {
            web.evaluateJavascript(
                    "(function(){try{window.__meowWebviewResume&&window.__meowWebviewResume()}catch(e){}})()",
                    null);
        }
        // 配置页拨完开关回来时，这里是唯一让新状态生效的时机
        if (prefs != null) syncDebugServer();
        // 网关进程隔离后，:spider 崩了主进程还在 —— 这里负责把它拉回来
        gwKeeper.removeCallbacks(gwKeepTask);
        gwKeeper.postDelayed(gwKeepTask, 5_000L);
        // 扫码推送的设置读写要能摸到本页的 WebView。放 onResume 而不是 onCreate：
        // PushServer 可能在配置页就被开启（那时本 Activity 还没创建 web），
        // 用户返回主界面后必须能读到最新 web 引用。
        PushServer.setPrefsHost(pushPrefsHost);
        // ★ 后端地址可能在外面被改了（原生配置页 SetupActivity、或设置页弹层
        // 保存后 reloadApp）。onResume 是回到主界面后第一个可靠时机：
        // 不在这里补注入的话，prefs 里是新地址、localStorage 里还是 null/旧值，
        // 页面会一直显示「后端未连接」——实测过，用户会以为"连上了但没生效"。
        if (prefs != null && web != null) {
            String api = prefs.getString(K_API, "");
            if (api == null) api = "";
            if (!api.equals(injectedApi)) {
                injectedApi = api;
                injectApiBase(api);
                if (!api.isEmpty()) {
                    // 重载不挂看门狗：都是本机 assets，慢≠打不开（见 armLoadWatchdog 注释）
                    web.reload();
                }
            }
        }
    }

    /**
     * 上一次已经注入到 localStorage 的地址。
     *
     * <p>用来判断"地址变了没"——值不变就不 reload，否则每次息屏/切页回来
     * 都会白闪一次。
     */
    private String injectedApi = "\u0000";

    /**
     * 目录选择器（SAF）的回调。
     *
     * <p>SAF 只能用 startActivityForResult 发起——ACTION_OPEN_DOCUMENT_TREE
     * 没有 ActivityResultContract 的等价款，而我们要拿那个可持久化的 tree URI。
     * 结果交给 CacheDownloader 存起来（含 takePersistableUriPermission）。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        /*
         * 文件选择器的结果必须先处理。
         *
         * <p>取消时 resultCode 是 RESULT_CANCELED、data 为 null，照样要回调
         * onReceiveValue(null)——不回调的话 `<input type=file>` 会一直停在
         * "选择中"，下次点击可能不再触发。
         *
         * <p>clipData 里的 URI 全部交给页面；前端只用 files[0]。
         * input 没有 multiple，正常情况下只会有一项。
         */
        if (requestCode == REQ_FILE_CHOOSER) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null && clip.getItemCount() > 0) {
                    result = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        result[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            ValueCallback<Uri[]> cb = fileChooserCallback;
            fileChooserCallback = null;
            if (cb != null) cb.onReceiveValue(result);
            return;
        }
        if (CacheDownloader.handleResult(this, requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * 旋转后主动让 WebView 重排一次。
     *
     * <p>manifest 里声明了 `configChanges=orientation|screenSize`，Activity 不会重建，
     * WebView 由系统改尺寸；但部分内核不会重新计算布局视口（见 WebSettings 里的
     * 注释）。这里显式 requestLayout + invalidate，成本极低。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (web == null) return;
        web.post(() -> {
            if (web == null) return;
            web.requestLayout();
            web.invalidate();
        });
    }

    @Override
    protected void onDestroy() {
        DebugServer.stop();
        pointerHandler.removeCallbacks(hidePointerIcon);
        // 选择器还开着时 Activity 被销毁（低内存回收）：先按取消回填，
        // 否则 WebView 内部会一直挂着一个等结果的回调
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        // 桥方法会读它做 origin 校验：销毁后必须归空，否则校验拿到一个已销毁的 WebView
        sCurrentUrl = "";
        super.onDestroy();
    }

    // ------------------------------------------------------------ 远程调试

    /**
     * 远程调试服务的 WebView 接入点。
     *
     * <p><b>为什么每个方法都用 runOnUiThread + CountDownLatch</b>：DebugServer
     * 的请求跑在自己的线程池里，而 WebView 的任何方法（evaluateJavascript、
     * draw）都只能在 UI 线程调用；不切线程的后果是随机崩溃或静默无结果。
     * latch 用来把异步回调变回同步返回——HTTP 处理线程本来就可以阻塞。
     *
     * <p><b>为什么 timeout 不设长</b>：PC 侧 curl 等 10 秒还不返回就会以为电视挂了。
     * 5 秒对本地 JS 足够；页面里卡死的 JS（比如死循环）本来就该报超时而不是挂住连接。
     */
    private final DebugServer.Host debugHost = new DebugServer.Host() {
        @Override
        public String currentUrl() {
            final String[] out = {""};
            final CountDownLatch done = new CountDownLatch(1);
            runOnUi(() -> {
                try {
                    if (web != null && web.getUrl() != null) out[0] = web.getUrl();
                } finally {
                    done.countDown();
                }
            });
            try {
                if (!done.await(2, TimeUnit.SECONDS)) return "";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
            return out[0];
        }

        @Override
        public String evalJs(String js) {
            final String[] out = {null};
            final CountDownLatch done = new CountDownLatch(1);
            runOnUi(() -> {
                if (web == null) {
                    done.countDown();
                    return;
                }
                try {
                    web.evaluateJavascript(js, value -> {
                        out[0] = value;
                        done.countDown();
                    });
                } catch (Throwable t) {
                    done.countDown();
                }
            });
            try {
                if (!done.await(5, TimeUnit.SECONDS)) return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            return out[0];
        }

        @Override
        public Bitmap screenshot() {
            final Bitmap[] out = {null};
            final CountDownLatch done = new CountDownLatch(1);
            runOnUi(() -> {
                try {
                    if (web != null && web.getWidth() > 0 && web.getHeight() > 0) {
                        Bitmap b = Bitmap.createBitmap(web.getWidth(), web.getHeight(),
                                Bitmap.Config.ARGB_8888);
                        // draw 而不是 drawToBitmap：后者只在 API 30+ 才有，
                        // 而 TV 的 minSdk 是 23（很多投影仪还停在 5.1）。
                        // 注意：硬件加速下 WebView 没有软件层，某些内核上 draw 会
                        // 得到一张空图；这是该做法的已知代价，验收时先看返回的 bytes
                        // 是否小得离谱，再决定是否改用 PixelCopy。
                        web.draw(new Canvas(b));
                        out[0] = b;
                    }
                } catch (Throwable ignored) {
                    // 返回 null，调用方给 503；截图失败不该让整个服务挂掉
                } finally {
                    done.countDown();
                }
            });
            try {
                if (!done.await(5, TimeUnit.SECONDS)) return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            return out[0];
        }

        @Override
        public boolean sendKey(final int keyCode) {
            final boolean[] out = {false};
            final CountDownLatch done = new CountDownLatch(1);
            runOnUi(() -> {
                try {
                    // 分发给 Activity 而不是 web：壳自己的 dispatchKeyEvent 会把方向键
                    // 翻成 __meowTvNav 调用（几何选焦），直接喂 WebView 会绕过它。
                    // handled 取「按下」那一次的返回值：壳吞掉导航键才算真的有人处理。
                    boolean handled = dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                    dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
                    out[0] = handled;
                } catch (Throwable ignored) {
                    out[0] = false;
                } finally {
                    done.countDown();
                }
            });
            try {
                if (!done.await(2, TimeUnit.SECONDS)) return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return out[0];
        }

        @Override
        public void pressBack() {
            // 返回键走 onBackPressed 而不是合成 KeyEvent：壳在这条路上还会先问页面
            // 「你要不要吃掉这一次返回」，合成事件走过去也是同一条路，但少一层
            // tracking 旗标的坑（某些固件上合成 KeyEvent 的 ACTION_UP 会被丢掉）。
            // 这里不等待结果：返回键的页面回调是异步的，等它会把请求挂到超时。
            runOnUi(MainActivity.this::onBackPressed);
        }
    };

    /**
     * 扫码推送服务的设置读写口。
     *
     * <p><b>为什么要绕到前端</b>：prefs 存在 WebView 的 localStorage 里，
     * 但 `savePrefs()` 有模块级 cache —— 原生直接写 localStorage 会让页面
     * 继续读到旧值（表现是"手机改了、电视没变，刷新才好"）。
     * 所以读写都必须回到前端的 `window.__meowPrefsJson/Apply`。
     *
     * <p>那两个钩子在 `main.ts` 的 `installPrefsBridge()` 里挂上，页面一加载就有。
     * 调不到（WebView 未就绪 / 老包）时返回空，推送服务退化成"只改后端地址"
     * 的老行为，不会整页失败。
     */
    private final PushServer.PrefsHost pushPrefsHost = new PushServer.PrefsHost() {
        @Override
        public String readPrefsJson() {
            return evalJsSync("(window.__meowPrefsJson?window.__meowPrefsJson():'{}')", 5);
        }

        @Override
        public int applyPrefsJson(String json) {
            return applyViaJs("__meowPrefsApply", json);
        }

        @Override
        public String readWebdavJson() {
            return evalJsSync("(window.__meowWebdavJson?window.__meowWebdavJson():'{}')", 5);
        }

        @Override
        public int applyWebdavJson(String json) {
            return applyViaJs("__meowWebdavApply", json);
        }

        @Override
        public String readUpdateJson() {
            return evalJsSync("(window.__meowUpdateJson?window.__meowUpdateJson():'{}')", 5);
        }

        @Override
        public int applyUpdateJson(String json) {
            return applyViaJs("__meowUpdateApply", json);
        }

        /**
         * policy 读写都是异步的（要发 HTTP），而 evaluateJavascript 是同步回调，
         * 拿不到 Promise 结果 —— 所以走"投任务 + 轮询"，与 ai/bridge.ts 的
         * `__meowToolStart/Poll` 同一套。
         */
        @Override
        public String readPolicyJson() {
            String id = "pr" + System.nanoTime();
            evalJsSync("(window.__meowPolicyReadStart?window.__meowPolicyReadStart("
                    + jsStringLiteral(id) + "):false)", 5);
            // 后端 policy 是本地文件，正常几十毫秒；TV 本机执行时也只是读盘。
            // 3 秒还没回来说明后端连不上，返回空对象让表单留空而不是卡住。
            for (int i = 0; i < 20; i++) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                String got = evalJsSync(
                        "(function(){var r=window.__meowPolicyPoll&&window.__meowPolicyPoll("
                                + jsStringLiteral(id) + ");"
                                + "if(!r||r.pending)return '';return r.json||'{}'})()", 5);
                String v = PushServer.unwrapJsStringPublic(got);
                if (v != null && !v.isEmpty() && !"''".equals(v)) return v;
            }
            return "{}";
        }

        @Override
        public int applyPolicyJson(String json) {
            String id = "pw" + System.nanoTime();
            evalJsSync("(window.__meowPolicyStart?window.__meowPolicyStart("
                    + jsStringLiteral(id) + "," + jsStringLiteral(json) + "):false)", 5);
            for (int i = 0; i < 20; i++) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                String poll = evalJsSync(
                        "(function(){var r=window.__meowPolicyPoll&&window.__meowPolicyPoll("
                                + jsStringLiteral(id) + ");"
                                + "if(!r||r.pending)return '';return String(r.result==null?0:r.result)})()", 5);
                String v = PushServer.unwrapJsStringPublic(poll);
                if (v != null && !v.isEmpty() && !"''".equals(v)) {
                    try {
                        return (int) Double.parseDouble(v.trim());
                    } catch (Exception ignored) {
                        return 0;
                    }
                }
            }
            return 0;
        }

        /**
         * 命令类动作：复用前端设置页已有的同一批 API（清空搜索缓存 /
         * 重置源健康）。不在壳里重写，否则两处行为迟早漂移。
         */
        @Override
        public int runAction(String name) {
            String id = "ac" + System.nanoTime();
            evalJsSync("(window.__meowActionStart?window.__meowActionStart("
                    + jsStringLiteral(id) + "," + jsStringLiteral(name) + "):false)", 5);
            for (int i = 0; i < 20; i++) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                String poll = evalJsSync(
                        "(function(){var r=window.__meowPolicyPoll&&window.__meowPolicyPoll("
                                + jsStringLiteral(id) + ");"
                                + "if(!r||r.pending)return '';return String(r.result==null?0:r.result)})()", 5);
                String v = PushServer.unwrapJsStringPublic(poll);
                if (v != null && !v.isEmpty() && !"''".equals(v)) {
                    try {
                        return (int) Double.parseDouble(v.trim());
                    } catch (Exception ignored) {
                        return 0;
                    }
                }
            }
            return 0;
        }

        /** 调 `window.__meowXxxApply(json)`，返回写入项数。 */
        private int applyViaJs(String fn, String json) {
            String js = "(window." + fn + "?window." + fn + "("
                    + jsStringLiteral(json) + "):0)";
            String out = evalJsSync(js, 5);
            try {
                return out == null ? 0 : (int) Double.parseDouble(out.trim());
            } catch (Exception e) {
                return 0;
            }
        }
    };

    /**
     * 在 UI 线程执行 JS 并同步等结果。
     *
     * <p>与 debugHost 里的 evalJs 同一套做法，但**不共用**：那个绑在
     * 远程调试开关上；推送是日常功能，不该因为"没开远程调试"就用不了。
     */
    private String evalJsSync(final String js, int timeoutSec) {
        final String[] out = {null};
        final CountDownLatch done = new CountDownLatch(1);
        runOnUi(() -> {
            try {
                if (web == null) {
                    done.countDown();
                    return;
                }
                web.evaluateJavascript(js, value -> {
                    out[0] = value;
                    done.countDown();
                });
            } catch (Throwable t) {
                done.countDown();
            }
        });
        try {
            if (!done.await(timeoutSec, TimeUnit.SECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out[0];
    }

    /**
     * 把 Java 字符串编成 JS 字面量（含引号），供拼表达式用。
     *
     * <p>用单引号而不是 JSON：JSON 里 `\u2028/\u2029` 不需要转义，
     * 但 JS 字符串字面量里它们是**换行**，不转会 SyntaxError（老内核上尤其）。
     */
    private static String jsStringLiteral(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default: sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    /** 在 UI 线程上跑；已在 UI 线程时直接跑，避免无意义的 post 让结果晚一拍。 */
    private void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            runOnUiThread(r);
        }
    }

    /**
     * 按开关状态同步 DebugServer 的启停。
     *
     * <p>在 {@code onResume} 里调：用户在配置页拨开关后按返回键回来，
     * 这一步是唯一能让新状态生效的地方（配置页和主界面是两个 Activity）。
     */
    private void syncDebugServer() {
        if (prefs == null) {
            if (DebugServer.isRunning()) DebugServer.stop();
            return;
        }
        // ★ debug 包自动开启远程调试。
        //   动机：验收时如果只能靠 adb 去开开关、读令牌，那"没有 adb 的投影仪"
        //   就永远没法自动化——而这正是做 DebugServer 的原因。
        //   release 包**保持手动**：那是安全闸门，不能让局域网随便执行 JS。
        //   令牌固定成一个可预期的值，脚本无需读 prefs 就能连（仅 debug 包）。
        if (BuildConfig.DEBUG) {
            if (!prefs.getBoolean(K_DEBUG, false)) {
                prefs.edit().putBoolean(K_DEBUG, true).apply();
            }
            if (prefs.getString(K_DEBUG_TOKEN, "").isEmpty()) {
                prefs.edit().putString(K_DEBUG_TOKEN, "debug0000000auto").apply();
            }
        }
        boolean on = prefs.getBoolean(K_DEBUG, false);
        String tok = prefs.getString(K_DEBUG_TOKEN, "");
        // web 为 null 说明这个 Activity 正在 finish（没配后端或内核过旧），
        // 此时起服务只会让 /state /eval 一律 503，不如不起
        if (!on || tok == null || tok.isEmpty() || web == null) {
            if (DebugServer.isRunning()) DebugServer.stop();
            return;
        }
        if (DebugServer.isRunning() && tok.equals(DebugServer.token())) return;
        // 令牌变了（用户在配置页关掉又打开）：先停再起，否则旧令牌还能用
        if (DebugServer.isRunning()) DebugServer.stop();
        boolean ok = DebugServer.start(this, debugHost, tok);
        android.util.Log.i("MeowTv", "debugServer=" + (ok ? DebugServer.url() : "FAILED")
                + " token=" + (BuildConfig.DEBUG ? tok : "<hidden>"));
    }
}
