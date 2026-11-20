package com.meow.tv;

import android.app.Activity;
import android.os.Build;
import android.webkit.JavascriptInterface;

/**
 * 给 Web 页面用的原生能力桥。
 *
 * <p>为什么需要它：Web UI 不知道"这台盒子能不能硬解 4K"。
 * 它能做的是在需要时问一句原生层，由原生决定用 ExoPlayer 还是留在 WebView。
 *
 * <p>安全约束：这些方法没有鉴权——WebView 加载的是用户自己填的地址，
 * 如果那个地址被换成恶意页面，它就能拉起播放器（无害）或读版本号（无害）。
 * 所以这里**只开放无副作用的能力**：不读写文件、不访问联系人、不发网络请求。
 * 真要加读写类能力，必须先做 origin 白名单校验。
 */
public class TvBridge {

    private final Activity activity;
    /**
     * 挂桥的 WebView。{@link #showIme} 要把焦点交给它——
     * Activity.getCurrentFocus() 拿到的是 WebView 这个 View，
     * 直接对它 showSoftInput 才会把输入法挂到页面里的输入框上。
     */
    private android.webkit.WebView webView;

    /** 主线程定时器（转回竖屏后延迟解除方向钉住） */
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    TvBridge(Activity activity) {
        this.activity = activity;
    }

    TvBridge(Activity activity, android.webkit.WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    /**
     * 写后端地址这类"有副作用"的桥方法的前置检查。
     *
     * <p>类注释里说"只开放无副作用能力"，但连接设置必须能改后端地址、
     * 能起扫码推送——这两个都有副作用。放开的代价是：页面里任意 JS
     * 都能改后端地址，把用户导到一个恶意后端。
     *
     * <p>所以加一条 origin 白名单：只认内置前端所在的 {@code meow.local}。
     * 用户把「后端地址」填成外部网址时，WebView 加载的是别人的页面，
     * 那时这些方法必须失效。{@code getUrl()} 是当前文档地址，
     * 与「谁在调用」不完全等价（同源 iframe 也算），但已能挡住
     * "换了个页面就被接管"的主要风险。
     */
    private boolean trustedOrigin() {
        try {
            String u = MainActivity.currentUrl();
            return u != null && u.startsWith("http://meow.local/");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 页面里是否有输入框正在编辑（见 {@link #isEditing()}）。
     *
     * <p>volatile：由 WebView 的 JS 线程写、UI 线程读。
     */
    private static volatile boolean editing = false;

    /**
     * 页面推来的"正在输入"状态。
     *
     * <p><b>为什么需要这个</b>（2026-11-03 核实）：`MainActivity.dispatchKeyEvent`
     * 是**同步**的，而 `evaluateJavascript` 异步 —— 拿不到当下的 activeElement。
     * 不判断的后果：遥控器在搜索框里输入时，方向键被壳吃掉去搬焦点（跳到历史胶囊），
     * 再按 OK 就点到背景，输入法选词也收不到方向键。
     *
     * <p>由页面在 focusin/focusout 时推布尔过来，按键时读这个字段，
     * 最多差一次焦点切换的时间。
     */
    @JavascriptInterface
    public void setEditing(boolean on) {
        if (!trustedOrigin()) return;
        editing = on;
    }

    /** 供 MainActivity 同步判断：正在输入时方向键/回车要交给 WebView。 */
    public static boolean isEditing() {
        return editing;
    }

    /**
     * 页面推当前主题，给「配置页」这类没有 WebView 的原生页面用。
     *
     * <p>配置页在 WebView 起来之前就要画，读不到 CSS 变量；这里把最近一次
     * 主题存进 SharedPreferences，下次进配置页时照着画。只接受 meow.local
     * 推来的值，避免用户把后端地址填成外部页面后顺手改掉原生 UI。
     */
    @JavascriptInterface
    public void setUiTheme(String json) {
        if (!trustedOrigin()) return;
        InputTheme.save(activity, json);
    }

    /** 原生播放是否可用（有 ExoPlayer 且当前是 TV 构建） */
    @JavascriptInterface
    public boolean nativePlayerAvailable() {
        return true;
    }

    /**
     * 首投确认框（页面里的 confirmDialog）的结果回填。
     *
     * <p>接收端 HTTP 线程在 {@link CastReceiverBridge#confirm} 里等这个结果；
     * 只回答"允许/拒绝"，不写任何持久状态，但仍走 trustedOrigin：
     * 用户把后端地址填成外部页面时，那个页面不该能替用户点"允许"。
     */
    @JavascriptInterface
    public void castConfirmResult(String id, boolean allow) {
        if (!trustedOrigin()) return;
        CastReceiverBridge.resolveConfirm(id, allow);
    }

    /**
     * 用原生播放器打开一个地址。
     *
     * @param headers {@code k=v&k=v} 形式，值需 URL 编码
     * @return 是否成功拉起；false 时调用方应退回 WebView 内的 hls.js 播放
     */
    @JavascriptInterface
    public boolean openNative(String url, String title, String headers, long positionMs) {
        return NativePlayerActivity.open(activity, url, title, headers, positionMs);
    }

    /** 让页面知道当前是不是 TV（用于选焦点样式、隐藏鼠标相关交互） */
    @JavascriptInterface
    public String platform() {
        return "tv";
    }

    /**
     * 设备名：投屏会话里给对端显示"谁投过来的"。
     *
     * <p>用厂商 + 型号（如 {@code XGIMI XGIMI TV}），比固定 "TV" 更容易在
     * 多台设备同时在线时分辨；失败退回 "TV"。
     */
    @JavascriptInterface
    public String deviceName() {
        try {
            String m = (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER).trim();
            String model = (Build.MODEL == null ? "" : Build.MODEL).trim();
            if (model.isEmpty()) return "TV";
            if (m.isEmpty() || model.toLowerCase().startsWith(m.toLowerCase())) return model;
            return m + " " + model;
        } catch (Throwable t) {
            return "TV";
        }
    }

    /**
     * 全屏时旋转 Activity（2026-11-05）。
     *
     * <p>为什么需要：手机竖屏上播 1920×800 这类宽片，WebView 里只能显示成
     * 中间一条（上下大黑边）；而 WebView 默认没有实现
     * {@code WebChromeClient.onShowCustomView}，{@code requestFullscreen()}
     * 必然被拒，页面只能退化成"伪全屏"。真要看满就得把 Activity 转到横屏。
     *
     * <p>mode: {@code landscape} 转横屏 / {@code unspecified} 跟随设备
     * （退出全屏时用，不强制竖屏 —— 用户可能本来就横着拿）。
     * 电视上本来就是横屏，调用是空操作。
     */
    @JavascriptInterface
    public void setOrientation(String mode) {
        if (!trustedOrigin()) return;
        activity.runOnUiThread(() -> {
            try {
                if ("landscape".equals(mode)) {
                    /*
                     * 横屏**保持钉住**（2026-11-05 实测）：
                     * 解钉会立刻弹回竖屏 —— 系统旋转停在 0（自动旋转关闭/NAT 模拟器），
                     * 放开后 follow 系统就回来了，等于没转。
                     * 播放期间锁横屏是视频应用的常规行为；离开播放器时前端会
                     * 调 portrait 还原，那里会解除钉住。
                     */
                    activity.setRequestedOrientation(
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    return;
                }
                if ("portrait".equals(mode)) {
                    activity.setRequestedOrientation(
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    /*
                     * ★ 转回竖屏后必须**解除钉住**（2026-11-05 用户报「横屏测试转不动」）。
                     *
                     * setRequestedOrientation(PORTRAIT) 是持续生效的：不还原的话，
                     * 只要退出过一次全屏，整个应用就永久锁在竖屏 —— 之后无论怎么
                     * 转设备都不会横屏。延迟 800ms 是因为要等宽高比稳定，太早解除
                     * 会被系统按"当前仍是横屏"重新采一次方向，画面抖一下。
                     */
                    ui.postDelayed(() -> {
                        try {
                            activity.setRequestedOrientation(
                                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                        } catch (Exception ignored) {
                        }
                    }, 800);
                    return;
                }
                activity.setRequestedOrientation(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            } catch (Exception ignored) {
                /* 个别 ROM 会拒绝；失败就保持当前方向，不影响播放 */
            }
        });
    }

    @JavascriptInterface
    public String appVersion() {
        return BuildConfig.VERSION_NAME;
    }

    // ---------------------------------------------------------------- 文本输入

    /**
     * 弹原生输入框，让遥控器能打字。
     *
     * <p><b>为什么必须有这个</b>：TV 壳的 {@code dispatchKeyEvent} 把方向键和
     * 确认键全部转给 tv-nav 做几何选焦，从不交给 WebView。于是页面里
     * {@code <input type="text">} 拿到的是 {@code click()}（tv-nav 的
     * {@code enter()} 调的），而 WebView 只对**真实触摸**弹输入法——
     * {@code click()} 不弹。用户看到的是"框能选中、按键却毫无反应"。
     * 全站 45 个文本框（搜索页、片库添加、WebDAV、后端地址、弹幕屏蔽词…）
     * 在 TV 上全部不可用。
     *
     * <p><b>做法</b>：原生 AlertDialog + EditText。它自己会拉系统输入法，
     * 遥控器的方向键在这里是系统组件在用（此时 WebView 没有焦点，
     * 壳的 dispatchKeyEvent 也走不到 nav），天然不冲突。
     *
     * <p><b>同步返回</b>：JS 线程等 CountDownLatch，最多等 5 分钟（用户可能
     * 慢慢打字）。取消返回 null。阻塞 JS 线程是安全的——页面本来就在等结果。
     *
     * @param title   对话框标题，例如"搜索影片"
     * @param initial 预填内容（可空）
     * @param mask    是否密码样式（后端令牌这类）
     * @return 用户输入的文本；取消或出错返回 null
     */
    @JavascriptInterface
    public String inputText(String title, String initial, boolean mask) {
        return inputText(title, initial, mask, null);
    }

    /**
     * 带主题色的原生输入框。
     *
     * @param themeJson 页面传来的配色 JSON（见 web 端 nativeBridge.themePayload），
     *                  可为 null。传了才画成"和页面一致"的样子——系统默认的
     *                  灰底对话框放在深色 UI 上像另一个应用。
     */
    @JavascriptInterface
    public String inputText(String title, String initial, boolean mask, String themeJson) {
        final String[] out = new String[] { null };
        final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(1);
        final InputTheme th = InputTheme.parse(themeJson);
        activity.runOnUiThread(() -> {
            try {
                final android.widget.EditText et = new android.widget.EditText(activity);
                et.setText(initial == null ? "" : initial);
                et.setSingleLine(true);
                et.setSelectAllOnFocus(true);
                if (mask) {
                    et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                }
                // 左右内边距：电视上文字贴边看着很廉价
                int pad = (int) (activity.getResources().getDisplayMetrics().density * 20);
                android.widget.FrameLayout box = new android.widget.FrameLayout(activity);
                box.setPadding(pad, pad / 2, pad, 0);
                box.addView(et, new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
                th.applyToEditText(et);
                android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(activity)
                        .setTitle(title == null || title.isEmpty() ? "输入" : title)
                        .setView(box)
                        .setPositiveButton("确定", (d, w) -> {
                            out[0] = et.getText().toString();
                            NativeInput.end();
                            done.countDown();
                        })
                        .setNegativeButton("取消", (d, w) -> {
                            out[0] = null;
                            NativeInput.end();
                            done.countDown();
                        })
                        // 返回键取消：不 countDown 会让 JS 线程等满 5 分钟
                        .setOnCancelListener(d -> {
                            out[0] = null;
                            NativeInput.end();
                            done.countDown();
                        })
                        .create();
                dlg.show();
                th.applyToDialog(dlg);
                // 必须在 show() 之后置位：dispatchKeyEvent 的第一行会读它
                NativeInput.begin();
                et.requestFocus();
                /*
                 * ★ 回车 = 确定。
                 *
                 * 真机踩过：软键盘（投影仪的讯飞）弹出后**方向键被输入法吃掉**，
                 * 用户没法把焦点移到「确定」按钮上——只能按返回取消，等于输入框
                 * 在电视上不可用。而遥控器上的 OK 键在输入法里通常就是回车，
                 * 拦截它既符合直觉（打完字按 OK 提交），也绕开了"走不到按钮"。
                 * 需要换行时用键盘上的换行键，不影响（setSingleLine 本来也没有换行）。
                 */
                et.setOnEditorActionListener((v, actionId, ev) -> {
                    out[0] = et.getText().toString();
                    NativeInput.end();
                    done.countDown();
                    return true;
                });
            } catch (Throwable t) {
                NativeInput.end();
                out[0] = null;
                done.countDown();
            }
        });
        try {
            if (!done.await(5, java.util.concurrent.TimeUnit.MINUTES)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out[0];
    }

    // ---------------------------------------------------------------- 系统输入法

    /**
     * 主动弹出系统输入法（给页面靠上的输入框用）。
     *
     * <p><b>为什么需要</b>：{@link #inputText} 弹的是原生对话框，适合会被输入法
     * 遮住的输入框（页面下半部）。但搜索页/顶栏那种靠上的输入框根本不会被遮，
     * 多弹一个对话框纯属多一步——用户要的是"就地打字 + 立刻搜索"。
     *
     * <p>WebView 对**遥控器 OK 键**不会弹输入法（只对真实触摸弹），所以页面
     * 侧 {@code el.focus()} 之后必须由原生这边补一次
     * {@link android.view.inputmethod.InputMethodManager#showSoftInput}。
     *
     * <p>页面把要聚焦的元素标了 {@code data-tv-ime}，这里让 WebView 自己把它
     * 聚焦（{@code requestFocus} 会走 View 树的真实焦点流程，比在 JS 里
     * {@code el.focus()} 更能让输入法拿到正确的编辑器）。
     *
     * @return true 表示已尝试弹出（不代表输入法一定显示，取决于系统设置）
     */
    @JavascriptInterface
    public boolean showIme() {
        activity.runOnUiThread(() -> {
            try {
                /*
                 * ★ 顺序踩过两次，现在的顺序是唯一能用的：
                 *
                 *   ① 先 showSoftInput（此时 WebView 已经是窗口焦点目标）
                 *   ② 再 evaluateJavascript 把页面元素 focus 回来
                 *
                 * 反例一：只 requestFocus(webView) + showSoftInput —— 输入法弹出来了，
                 *        但打字进不去（WebView 的 View 焦点与页面 activeElement 是两层）。
                 * 反例二：先 evaluateJavascript(e.focus) 再 requestFocus(webView) ——
                 *        requestFocus 会把 WebView 内部的编辑焦点清掉，
                 *        实测 activeElement 又变回 <a>。
                 *
                 * 所以先把输入法挂上去、再补页面 focus，中间不要动 View 焦点。
                 */
                android.view.View target = webView != null ? webView : activity.getCurrentFocus();
                if (target == null) target = activity.getWindow().getDecorView();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) activity
                                .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(target, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
                if (webView != null) {
                    webView.evaluateJavascript(
                            "(function(){try{var e=document.querySelector('[data-tv-ime]');"
                                    + "if(e&&e.focus){e.focus()}return '1'}catch(x){return '0'}})()",
                            null);
                }
            } catch (Throwable ignore) {
                // 输入法起不来不该影响其他交互
            }
        });
        return true;
    }

    // ---------------------------------------------------------------- 远程调试（MCP）

    /**
     * 读取远程调试状态。
     *
     * <p>为什么需要从页面控制：调试开关原来只在**配置页**，而配置页只在
     * "没配过后端"时出现——一旦进了主界面就再也回不去，于是
     * "想开个 MCP 让 PC 按键"变成了必须重装或清数据的死结。
     *
     * @return JSON：{enabled, running, token, url, port, hint}
     */
    @JavascriptInterface
    public String debugState() {
        android.content.SharedPreferences sp =
                activity.getSharedPreferences(MainActivity.PREF, android.content.Context.MODE_PRIVATE);
        boolean on = sp.getBoolean(MainActivity.K_DEBUG, false);
        String tok = sp.getString(MainActivity.K_DEBUG_TOKEN, "");
        boolean running = DebugServer.isRunning();
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"ok\":true,\"enabled\":").append(on)
                .append(",\"running\":").append(running)
                .append(",\"token\":").append(q(tok))
                .append(",\"port\":").append(running ? DebugServer.port() : 0)
                .append(",\"url\":").append(q(running ? DebugServer.url() : ""))
                .append(",\"idleTimeoutMs\":").append(DebugServer.IDLE_TIMEOUT_MS)
                .append(",\"expiresInMs\":").append(running ? DebugServer.idleRemainingMs() : 0)
                .append(",\"appVersion\":").append(q(BuildConfig.VERSION_NAME));
        if (!on) {
            // 未开启时给地址提示：用户要知道该把什么地址填进 PC 侧
            String ip = DebugServer.lanIp();
            sb.append(",\"hint\":").append(q(ip == null || ip.isEmpty()
                    ? "打开开关后在主界面生效" : "开启后在 " + ip + ":9977 提供 MCP"));
        } else if (!running) {
            sb.append(",\"hint\":").append(q("开关已开，返回主界面后生效"));
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * 开关远程调试。
     *
     * <p>开启时**必换新令牌**（与配置页行为一致）：把"怀疑令牌泄漏"的补救
     * 动作做成一次点击，沿用旧令牌会让这个动作毫无作用。
     *
     * <p>服务本身要等回到主界面（`onResume`）才真正起——这里只改开关与令牌。
     * 不在这里直接 start，是因为 DebugServer 依赖 MainActivity 的 WebView，
     * 从页面 JS 线程去拉起会和 onResume 抢同一把状态。
     *
     * @return 与 {@link #debugState()} 同结构
     */
    @JavascriptInterface
    public String setDebugEnabled(boolean enabled) {
        android.content.SharedPreferences sp =
                activity.getSharedPreferences(MainActivity.PREF, android.content.Context.MODE_PRIVATE);
        if (enabled) {
            sp.edit().putBoolean(MainActivity.K_DEBUG, true)
                    .putString(MainActivity.K_DEBUG_TOKEN, DebugServer.newToken()).apply();
        } else {
            sp.edit().putBoolean(MainActivity.K_DEBUG, false)
                    .remove(MainActivity.K_DEBUG_TOKEN).apply();
            DebugServer.stop();
        }
        return debugState();
    }

    /** JS 侧字符串转义。手写是因为这里不引 gson，字段又只有几个。 */
    private static String q(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ---------------------------------------------------------------- 本地 jar 执行

    // ---------------------------------------------------------------- 连接设置

    /** 局域网扫描结果缓存。前端轮询它取结果，避免在 JS 桥线程里阻塞几秒。 */
    private static volatile String sScanResult = "";
    private static volatile boolean sScanning;

    /**
     * 读当前后端地址与配置地址。
     *
     * @return JSON：{api, config, debugPort}
     */
    @JavascriptInterface
    public String connState() {
        android.content.SharedPreferences sp =
                activity.getSharedPreferences(MainActivity.PREF, android.content.Context.MODE_PRIVATE);
        String api = sp.getString(MainActivity.K_API, "");
        String cfg = sp.getString(MainActivity.K_CONFIG, "");
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"ok\":true,\"api\":").append(q(api == null ? "" : api))
                .append(",\"config\":").append(q(cfg == null ? "" : cfg));
        // 电视 IP：显示出来让用户知道该把什么地址填进 PC 侧
        String ip = DebugServer.lanIp();
        sb.append(",\"lanIp\":").append(q(ip == null ? "" : ip));
        sb.append('}');
        return sb.toString();
    }

    /**
     * 保存后端地址（以及可选的 CatVod 配置地址）。
     *
     * <p>★ 这是原来只在 SetupActivity 里能做的事。之所以搬到桥上，是因为
     * 配置页是**前置门**：一旦进了主界面就再也回不去，配错了只能卸载重装。
     * 现在设置页随时能改，且改完不需要重新进应用。
     *
     * @return JSON：{ok, api, config} 或 {ok:false, error}
     */
    @JavascriptInterface
    public String saveConn(String api, String config) {
        if (!trustedOrigin()) {
            return "{\"ok\":false,\"error\":\"untrusted origin\"}";
        }
        String u = api == null ? "" : api.trim();
        if (u.isEmpty()) return "{\"ok\":false,\"error\":\"后端地址不能为空\"}";
        if (!u.startsWith("http")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);

        String c = config == null ? "" : config.trim();
        android.content.SharedPreferences sp =
                activity.getSharedPreferences(MainActivity.PREF, android.content.Context.MODE_PRIVATE);
        sp.edit()
                .putString(MainActivity.K_API, u)
                .putString(MainActivity.K_URL, u)
                .putString(MainActivity.K_CONFIG, c)
                // 形态跟着地址走：手填 127.0.0.1:9978 也算本机（下次启动会自动拉起网关）
                .putString(MainActivity.K_MODE,
                        MainActivity.isLocalGateway(u) ? "local" : "remote")
                .apply();
        return "{\"ok\":true,\"api\":" + q(u) + ",\"config\":" + q(c) + "}";
    }

    /**
     * 保存/清除设备令牌（auth v2）。
     *
     * <p>前端 localStorage 之外再存一份 prefs：重装 APK 后 localStorage 在
     * 部分 ROM / 模拟器上会被清空（实测 MuMu Android 15），壳在 onPageFinished
     * 用它补回 localStorage（见 {@link MainActivity#restoreAuthToken}）。
     * 传空串 = 删除（退出登录必须调它，否则下次启动又被补回去）。
     */
    @JavascriptInterface
    public void saveAuthToken(String token) {
        if (!trustedOrigin()) return;
        String t = token == null ? "" : token.trim();
        android.content.SharedPreferences sp =
                activity.getSharedPreferences(MainActivity.PREF, android.content.Context.MODE_PRIVATE);
        if (t.isEmpty()) sp.edit().remove(MainActivity.K_AUTH).apply();
        else sp.edit().putString(MainActivity.K_AUTH, t).apply();
    }

    /** 扫描是否还在跑。 */
    @JavascriptInterface
    public boolean scanRunning() {
        return sScanning;
    }

    /**
     * 取扫描结果。
     *
     * @return JSON 数组（与 {@link LanScan#toJson} 同形）；扫描中返回空数组
     */
    @JavascriptInterface
    public String scanResult() {
        return sScanResult == null || sScanResult.isEmpty() ? "[]" : sScanResult;
    }

    /**
     * 起一次局域网扫描（异步）。
     *
     * <p>★ 为什么不在桥方法里同步扫完再返回：{@code LanScan.scan} 要 5–12 秒，
     * 而 JS 桥调用是同步的——阻塞这么久会让整个页面卡住（WebView 主线程）。
     * 所以这里立刻返回，结果由前端轮询 {@link #scanResult()} 取。
     */
    @JavascriptInterface
    public boolean startScan() {
        if (!trustedOrigin()) return false;
        if (sScanning) return true;
        sScanning = true;
        sScanResult = "";
        new Thread(() -> {
            try {
                java.util.List<LanScan.Found> found =
                        new java.util.ArrayList<>(LanScan.scan(null, 9000));
                sScanResult = LanScan.toJson(found).toString();
            } catch (Throwable t) {
                sScanResult = "[]";
            } finally {
                sScanning = false;
            }
        }, "bridge-scan").start();
        return true;
    }

    /** 扫码推送是否在跑。 */
    @JavascriptInterface
    public boolean pushRunning() {
        return PushServer.isRunning();
    }

    /**
     * 起/停扫码推送，返回手机端要打开的地址（未运行时为空串）。
     *
     * @return {@code http://<电视IP>:<port>}
     */
    @JavascriptInterface
    public String togglePush() {
        if (!trustedOrigin()) return "";
        if (PushServer.isRunning()) {
            PushServer.stop();
            return "";
        }
        boolean ok = PushServer.start(activity, (url, config, prefsJson, importMode) -> {
            // 设置（prefsJson）已经在 PushServer 里同步应用完了（走前端 savePrefs，
            // 会派发 meow:prefs，主题/列数/播放器等立即刷新）——这里只管地址。
            //
            // 地址不能也走 prefs：它是壳自己的 SharedPreferences（K_API），
            // 前端读的是 localStorage['meow.api']，由 onResume 的注入补上。
            //
            // importMode 这条路径用不上：设置页的导入是前端直接调
            // /api/config/import，导入方式由页面上的选择器决定。手机页那个
            // 下拉只影响**配置页**（SetupActivity）那条路，因为那里是壳自己
            // 发起导入的。两条路各管一段，不互相覆盖。
            if (url == null || url.isEmpty()) return;
            String u = url.trim();
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            android.content.SharedPreferences sp = activity.getSharedPreferences(
                    MainActivity.PREF, android.content.Context.MODE_PRIVATE);
            sp.edit()
                    .putString(MainActivity.K_API, u)
                    .putString(MainActivity.K_URL, u)
                    .putString(MainActivity.K_CONFIG, config == null ? "" : config.trim())
                    .apply();
        });
        return ok ? PushServer.pushUrl() : "";
    }

    /**
     * 扫码推送的访问令牌（8 位）。
     *
     * <p>为什么单独给一个方法而不是让前端从 pushUrl 里截 {@code ?k=}：
     * 弹层要把它放大显示（手输地址时用），前端自己解析 URL 会在
     * "地址里有没有 query" 上做隐式假设；这里直接给纯值更可靠。
     *
     * <p>未运行时返回空串。
     */
    @JavascriptInterface
    public String pushToken() {
        if (!trustedOrigin()) return "";
        return PushServer.isRunning() ? PushServer.token() : "";
    }

    /**
     * 用已保存的后端地址重新加载页面。
     *
     * <p>改完后端地址必须让前端重新读 localStorage 里的 `meow.api`，
     * 否则页面还在用旧地址（那是壳在 onPageFinished 时注入的）。
     */
    @JavascriptInterface
    public void reloadApp() {
        if (!trustedOrigin()) return;
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).runOnUiThread(
                    () -> ((MainActivity) activity).reloadWithSavedBackend());
        }
    }

    /**
     * 启动端内网关并返回地址（形如 `http://127.0.0.1:9978`）。
     *
     * <p>前端拿到后把它填进「后端地址」，之后 `/api/{site}/{action}` 就由
     * 本地 jar 执行——**前端不需要任何改动**，这是把端上执行接入现有 UI 的
     * 最小成本方式。
     *
     * @return 网关地址；启动失败返回空串（端口被占等）
     */
    @JavascriptInterface
    public String startLocalGateway() {
        // 先置看护标志：即使这次 startIsolated 等超时（12 秒）返回 false，
        // gwKeeper 也要接着把 :spider 拉回来（2026-11-05 实测踩过）。
        MainActivity.gwWanted = true;
        // 走"带观察的启动"：万一起崩了，下次启动能识别出来自动停手（见 K_GW_BOOT）
        boolean ok = MainActivity.startGatewayWatched(activity);
        if (!ok) return "";
        persistLocalGateway();
        return GatewayServer.baseUrl();
    }


    /**
     * 记住"本机网关"形态：地址 + 显式标记一起写。
     *
     * <p>只写前端 localStorage 不够（2026-11-04 用户报「上次开了本机执行，重开又要开一次」）：
     * 壳启动时按 {@code prefs[K_API]} 注入并会覆盖 localStorage，所以本机选择必须落到 prefs。
     */
    private void persistLocalGateway() {
        MainActivity.gwWanted = true;
        activity.getSharedPreferences(MainActivity.PREF, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(MainActivity.K_API, GatewayServer.baseUrl())
                .putString(MainActivity.K_MODE, "local")
                // 用户明确要求再试一次 → 撤销"上次崩过"的结论
                .putBoolean(MainActivity.K_GW_FAIL, false)
                .apply();
    }

    /**
     * 带后端地址启动网关。
     *
     * <p>弹幕要走这个地址（本机聚合站实测全部失效，后端的弹幕链路是好的）。
     * 前端知道用户填的后端地址，原生侧读不到 localStorage，所以由页面传进来。
     *
     * @param backendBase 形如 {@code http://192.168.1.10:30011}；传空表示纯本机
     * @return 网关地址；启动失败返回空串
     */
    @JavascriptInterface
    public String startLocalGatewayWith(String backendBase) {
        // 同 startLocalGateway：看护标志先置，再谈这次成功与否
        MainActivity.gwWanted = true;
        // backendBase 已忽略：复用后端代码后弹幕在本机抓。
        boolean ok = MainActivity.startGatewayWatched(activity);
        if (!ok) return "";
        persistLocalGateway();
        return GatewayServer.baseUrl();
    }

    @JavascriptInterface
    public boolean localGatewayRunning() {
        return GatewayServer.isRunning();
    }

    /**
     * 读本机网关的崩溃记录（{@code filesDir/crash.log}）。
     *
     * <p><b>为什么走桥而不是 logcat</b>：部分电视（实测极米 Z6X Pro）读不到
     * logcat、dropbox 无权限、run-as 也不可用。崩溃后除了"进程消失"什么都拿不到。
     * 这条路只依赖"应用能写自己的目录"，所有设备都成立。
     *
     * <p>前端在设置页或诊断入口展示即可；空串表示没崩过。
     */
    @JavascriptInterface
    public String crashLog() {
        try {
            java.io.File f = new java.io.File(activity.getFilesDir(), "crash.log");
            if (!f.isFile()) return "";
            long len = f.length();
            long from = Math.max(0, len - 32 * 1024);
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                raf.seek(from);
                byte[] buf = new byte[(int) (len - from)];
                raf.readFully(buf);
                return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return "crashLog 读取失败：" + t.getClass().getSimpleName();
        }
    }

    /** 清掉崩溃记录（用户看过之后）。 */
    @JavascriptInterface
    public boolean clearCrashLog() {
        try {
            java.io.File f = new java.io.File(activity.getFilesDir(), "crash.log");
            return !f.exists() || f.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 应用在线更新

    /**
     * 当前 ABI，供前端挑清单里对应的安装包。
     *
     * <p>极米那类盒子是 armeabi-v7a，模拟器/新盒子是 arm64-v8a 或 x86_64。
     * 挑错会下到装不上的包（`INSTALL_FAILED_NO_MATCHING_ABIS`），
     * 所以必须由原生报，不能靠 UA 猜。
     */
    @JavascriptInterface
    public String appUpdaterPlatform() {
        try {
            for (String abi : android.os.Build.SUPPORTED_ABIS) {
                if ("arm64-v8a".equals(abi)) return "arm64";
                if ("armeabi-v7a".equals(abi)) return "armv7";
                // 模拟器是 x86_64：按 32 位包处理没意义，直接报原名
                if ("x86_64".equals(abi) || "x86".equals(abi)) return abi;
            }
        } catch (Throwable ignored) {
        }
        return "armv7";
    }

    /**
     * 拉清单原文（JSON 文本）。失败返回 null，原因用 {@link #appUpdateError()} 读。
     *
     * <p><b>为什么清单也走原生</b>：私有仓要带 {@code Authorization}，而
     * {@code raw.githubusercontent.com} 的 CORS 预检不放行该头（实测 403）——
     * 浏览器根本不允许带 token 发这个请求。GitHub Release 资产更是完全没有
     * CORS 头。详见 {@link AppUpdater} 的类注释。
     */
    @JavascriptInterface
    public String appUpdateManifest(String url, String token) {
        return AppUpdater.fetchManifest(url, token);
    }

    /** 上一次 {@link #appUpdateManifest} 失败的原因（成功时为空串）。 */
    @JavascriptInterface
    public String appUpdateError() {
        return AppUpdater.lastError();
    }

    // ------------------------------------------------ 非阻塞的文本拉取任务
    //
    // ★ 为什么需要：`appUpdateManifest` / `moduleCheck*` 是**同步**桥方法，
    //   网络在 WebView 的 JS 线程上跑。连上却不回数据的地址（被墙的 GitHub、
    //   黑洞代理）会把整页 JS 冻住，用户看到的就是"偶尔卡死、点了没反应"。
    //   改成"原生后台线程拉 + JS 轮询"：UI 全程可交互，任务本身仍有超时
    //   （见 AppUpdater.http() 的 readTimeout）。
    //
    // 一次只允许一个任务：清单并发拉没有意义，还会互相拖慢。
    private static final Object NETJOB_LOCK = new Object();
    private static volatile String netJobId = "";
    private static volatile boolean netJobRunning = false;
    private static volatile String netJobText = null;
    private static volatile String netJobErr = "";

    /**
     * 起一个文本拉取任务。
     *
     * @param kind "manifest"（单地址，原样返回）或 "manifest-any"
     *             （`|` 分隔的候选，逐个试，命中含 packages 的那份）
     * @return 任务 id；空串 = 被拒（非可信 origin）
     */
    @JavascriptInterface
    public String netJobStart(String kind, String urls, String token) {
        if (!trustedOrigin()) return "";
        final boolean any = "manifest-any".equals(kind);
        final String target = urls == null ? "" : urls;
        final String id = Long.toString(System.nanoTime(), 36);
        synchronized (NETJOB_LOCK) {
            // ★ 新任务**直接取代**旧任务，不做"忙就拒绝"。
            //   理由：JS 侧可能因为自己的 deadline 先放弃，而旧线程还在等一个
            //   黑洞地址；此时用户再点一次「检查」必须能立刻重来，而不是被
            //   "已有任务在跑"挡住（那会变成"点了没反应"）。旧线程的结果靠
            //   id 比对丢弃，不会覆盖新任务。
            netJobId = id;
            netJobRunning = true;
            netJobText = null;
            netJobErr = "";
        }
        new Thread(() -> {
            String text = null;
            String err = "";
            try {
                text = any
                        ? AppUpdater.fetchManifestAny(target, token)
                        : AppUpdater.fetchManifest(target, token);
                if (text == null) err = AppUpdater.lastError();
            } catch (Throwable e) {
                err = String.valueOf(e);
            }
            synchronized (NETJOB_LOCK) {
                // 已被更新的任务取代 → 结果没有任何人等，直接丢弃
                if (!id.equals(netJobId)) return;
                netJobText = text;
                netJobErr = err;
                netJobRunning = false;
            }
        }, "meow-netjob").start();
        return id;
    }

    /**
     * 轮询任务状态：{@code {"running":true}} 或
     * {@code {"running":false,"text":"..."}} / {@code {"running":false,"error":"..."}}。
     */
    @JavascriptInterface
    public String netJobPoll(String id) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            boolean mine = id != null && id.equals(netJobId);
            o.put("running", mine && netJobRunning);
            if (!mine) {
                o.put("error", "任务已被更新的检查取代");
            } else if (!netJobRunning) {
                if (netJobText != null) o.put("text", netJobText);
                else o.put("error", netJobErr == null || netJobErr.isEmpty() ? "拉取失败" : netJobErr);
            }
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    /**
     * 开始下载安装包（立即返回，进度用 {@link #appUpdateState()} 轮询）。
     *
     * <p>下载完**不自动安装**：安装是用户的点击。静默替换应用自身等于
     * 把远端变成任意代码执行入口。
     *
     * @param urlsPipe     候选地址，`|` 分隔（直连 + 若干 GitHub 加速代理）。
     *                     逐个尝试，第一个能下通的胜出。
     * @param expectSha256 清单里的 sha256；空串会被拒绝（无法校验的包不该落盘）
     */
    @JavascriptInterface
    public String appUpdateDownload(String urlsPipe, String expectSha256, String token, String fileName) {
        return AppUpdater.start(activity, urlsPipe, expectSha256, token, fileName);
    }

    /** 当前下载进度（一行 JSON）。 */
    @JavascriptInterface
    public String appUpdateState() {
        return AppUpdater.stateJson();
    }

    /** 取消下载（没在下载时返回 false）。 */
    @JavascriptInterface
    public boolean appUpdateCancel() {
        return AppUpdater.cancel();
    }

    /** 把已下载并校验通过的 apk 交给系统安装器。 */
    @JavascriptInterface
    public String appUpdateInstall() {
        return AppUpdater.install(activity);
    }

    /** 清掉更新下载缓存，返回释放的字节数。 */
    @JavascriptInterface
    public long appUpdateClearCache() {
        return AppUpdater.clearCache(activity);
    }

    // ------------------------------------------------------------ 运行时模块

    /**
     * 公开静态清单兜底（主仓 updates 分支；开源前 404，不影响私有 token 路径）。
     *
     * <p>★ 顺序：jsDelivr 在前、raw 在后（2026-11-13 .77 实测：`raw.githubusercontent.com`
     * 在这类网络里 TCP 直接不通，而 `cdn.jsdelivr.net` 可下）。兜底路径只在主源全失败时
     * 才走，多半就是"被墙"的场景 —— 用 raw 打头等于兜底永远不生效。
     * jsDelivr 对分支 ref 有小时级缓存，这份清单只用来比版本号，晚几小时无妨。
     */
    private static final String[] MODULE_MIRROR_SOURCES = {
            "https://cdn.jsdelivr.net/gh/upchr/meow@updates/packages.json",
            "https://raw.githubusercontent.com/upchr/meow/updates/packages.json",
    };

    /** 上一次 moduleCheck 的清单结果（下载时复用，避免页面上"检查→下载"两次拉清单）。 */
    private volatile String moduleCatalogJson = "";

    /**
     * 模块状态（一行 JSON）。
     *
     * <p>字段：installed / enabled / running / port / version / error / abi /
     * moduleVersionName（已装包版本）/ defaultPort。
     *
     * <p>为什么状态来自文件而不是静态字段：模块跑在 `:node` 进程，
     * 这里（主进程）读它的 static 永远是默认值。
     */
    @JavascriptInterface
    public String moduleState() {
        try {
            android.content.Context ctx = activity.getApplicationContext();
            org.json.JSONObject o = com.meow.tv.runtime.RuntimeModuleState.read(ctx);
            boolean installed = com.meow.tv.runtime.RuntimeModuleHost.nodeInstalled(ctx);
            org.json.JSONObject out = new org.json.JSONObject();
            out.put("installed", installed);
            out.put("enabled", o.optBoolean("enabled", true));
            out.put("running", o.optBoolean("running", false));
            out.put("port", o.optInt("port", com.meow.tv.runtime.RuntimeModuleState.DEFAULT_PORT));
            out.put("defaultPort", com.meow.tv.runtime.RuntimeModuleState.DEFAULT_PORT);
            out.put("error", o.optString("error", ""));
            out.put("version", installed
                    ? com.meow.tv.runtime.RuntimeModuleHost.nodeVersion(ctx)
                    : o.optString("version", ""));
            out.put("abi", com.meow.tv.runtime.RuntimeModuleCatalog.abi());
            out.put("catalogCached", !moduleCatalogJson.isEmpty());
            return out.toString();
        } catch (Throwable t) {
            return "{\"installed\":false,\"error\":\"" + t.getClass().getSimpleName() + "\"}";
        }
    }

    /**
     * 开关模块。
     *
     * <p>关：写状态 + 停服务。开：写状态 + 拉起服务（NodeService 自己会挑端口）。
     * 返回最新状态 JSON，页面不用再调一次 moduleState。
     */
    @JavascriptInterface
    public String moduleSetEnabled(boolean enabled) {
        try {
            android.content.Context ctx = activity.getApplicationContext();
            com.meow.tv.runtime.RuntimeModuleState.put(ctx, "enabled", enabled);
            if (enabled) {
                ctx.startService(new android.content.Intent(ctx,
                        com.meow.tv.runtime.NodeService.class));
            } else {
                // 先标停：node 是被 JNI 阻塞住的，stopService 会回收进程
                com.meow.tv.runtime.RuntimeModuleState.put(ctx, "running", false);
                ctx.stopService(new android.content.Intent(ctx,
                        com.meow.tv.runtime.NodeService.class));
            }
        } catch (Throwable t) {
            android.util.Log.w("MeowTv", "moduleSetEnabled failed: " + t);
        }
        return moduleState();
    }

    /**
     * 拉模块清单，返回本机该装哪个包（**不下载**）。
     *
     * <p>清单地址固定在原生（见 RuntimeModuleCatalog.SOURCE），页面传不了 URL ——
     * 否则任何被加载的远端页面都能让电视装任意 APK。
     */
    @JavascriptInterface
    public String moduleCheck() { return moduleCheck("", ""); }

    /**
     * 带源与令牌的版本（私有仓库必须传 token，否则 GitHub 对匿名请求一律 404）。
     *
     * <p>为什么由页面传：与 App 自身更新完全同一套信任模型 —— 页面本来就能
     * 下发"装一个新版本"，模块只是多一个包名。令牌不写进 APK（写进去等于
     * 每个用户手里都有一个能读私有仓的 token）。
     */
    @JavascriptInterface
    public String moduleCheck(String source, String token) {
        return moduleCheckCandidates(source, token, "");
    }

    /**
     * 带候选地址的版本（2026-11-12）：{@code urlsPipe} 是 JS 按「网络与加速 →
     * GitHub 加速」算好的直连+代理列表（`|` 分隔），拉清单时逐个尝试。
     *
     * <p>★ 方法名不能与两参版重名：Android WebView 的 JS 桥**不支持同名重载**，
     * 加了重载后 JS 侧 `moduleCheck(src, tok)` 会解析到错误的方法、拿到空串，
     * 页面报 `JSON.parse: End of input at character 0`（2026-11-12 投影仪实测）。
     * 桥方法一律一名字一签名，新能力用新名字（*Candidates）。
     */
    @JavascriptInterface
    public String moduleCheckCandidates(String source, String token, String urlsPipe) {
        try {
            String src = (source == null || source.trim().isEmpty())
                    ? com.meow.tv.runtime.RuntimeModuleCatalog.SOURCE : source.trim();
            String tk = token == null ? "" : token;
            // 诊断：清单地址一律落一条日志。踩过"改了更新源但页面缓存了旧 bundle"
            // 与"候选列表算错"，没有这行只能靠猜（2026-11-13 .77 实测）。
            android.util.Log.i("MeowTv", "moduleCheck src=" + src
                    + " pipe=" + (urlsPipe == null || urlsPipe.trim().isEmpty() ? "-" : "yes"));
            String pipe = urlsPipe == null ? "" : urlsPipe.trim();
            String text = pipe.isEmpty()
                    ? AppUpdater.fetchManifest(src, tk)
                    : AppUpdater.fetchManifestAny(pipe, tk);
            // 源可能给的是"仓库/release 端点"而不是 packages.json 本身
            // （与 App 更新同一个源）。那种情况先解析出 packages.json 资产再拉。
            if (text == null || !text.contains("\"packages\"")) {
                String asset = moduleManifestAssetUrl(text, tk);
                if (!asset.isEmpty()) text = AppUpdater.fetchManifest(asset, tk);
            }
            // 公开源兜底：官方静态清单（主仓 updates 分支，jsDelivr 优先）
            if ((text == null || !text.contains("\"packages\"")) && tk.trim().isEmpty()) {
                for (String mirror : MODULE_MIRROR_SOURCES) {
                    String alt = AppUpdater.fetchManifest(mirror, "");
                    if (alt != null && alt.contains("\"packages\"")) {
                        text = alt;
                        break;
                    }
                }
            }
            // 清单没拿到就必须给真实原因：直接交给 pick("") 会抛
            // JSONException("End of input at character 0 of")，页面只能显示
            // 这句对用户无意义的 JSON 解析错（2026-11-12 实测踩到）。
            if (text == null || !text.contains("\"packages\"")) {
                moduleCatalogJson = "";
                String why = AppUpdater.lastError();
                return "{\"ok\":false,\"error\":\"拉清单失败："
                        + AppUpdater.escape(why == null || why.isEmpty() ? "清单内容不完整" : why)
                        + "\"}";
            }
            moduleCatalogJson = text;
            org.json.JSONObject pick =
                    com.meow.tv.runtime.RuntimeModuleCatalog.pick(moduleCatalogJson);
            org.json.JSONObject out = new org.json.JSONObject(pick.toString());
            out.put("ok", true);
            out.put("source", src);
            return out.toString();
        } catch (Throwable t) {
            moduleCatalogJson = "";
            return "{\"ok\":false,\"error\":\"" + AppUpdater.escape(t.getMessage()) + "\"}";
        }
    }

    /**
     * 用**已经拉到的清单原文**产出 pick 结果。
     *
     * <p>踩过（1.2.5 真机实测）：`netJobStart("manifest-any")` 返回的是
     * packages.json **原文**，页面却当成 pick 结果用 —— `chk.url` 是 undefined，
     * 于是把**清单地址**当模块 APK 下载，15 秒后报
     * {@code SocketTimeoutException ...（已尝试 1 个地址）}。
     *
     * <p>「文本 → pick」的解析（ABI/platform 匹配）必须留在原生，页面不重复实现一套。
     */
    @JavascriptInterface
    public String moduleCheckFromText(String text) {
        try {
            if (text == null || !text.contains("\"packages\"")) {
                return "{\"ok\":false,\"error\":\"清单内容不完整\"}";
            }
            moduleCatalogJson = text;
            org.json.JSONObject pick =
                    com.meow.tv.runtime.RuntimeModuleCatalog.pick(text);
            org.json.JSONObject out = new org.json.JSONObject(pick.toString());
            out.put("ok", true);
            out.put("source", "netjob");
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + AppUpdater.escape(t.getMessage()) + "\"}";
        }
    }

    /**
     * 用清单原文产出**载荷**的 pick 结果（与 {@link #moduleCheckFromText} 同因同治）。
     *
     * <p>不写 moduleCatalogJson：那是模块下载复用的缓存，载荷自己会再 pick 一次。
     */
    @JavascriptInterface
    public String payloadCheckFromText(String text) {
        try {
            if (text == null || !text.contains("\"packages\"")) {
                return "{\"ok\":false,\"error\":\"清单内容不完整\"}";
            }
            org.json.JSONObject pick =
                    com.meow.tv.runtime.RuntimePayloadHost.pick(text);
            org.json.JSONObject out = new org.json.JSONObject(pick.toString());
            out.put("ok", true);
            out.put("source", "netjob");
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + AppUpdater.escape(t.getMessage()) + "\"}";
        }
    }

    /**
     * 下载本机 ABI 的模块包（sha256 校验由 AppUpdater 强制）。
     *
     * <p>下完页面调 {@code meowTv.appUpdateInstall()} 交给系统安装器 ——
     * 和 App 自身更新走同一条安装路径，不另写一套。
     */
    @JavascriptInterface
    public String moduleDownload() { return moduleDownload("", ""); }

    @JavascriptInterface
    public String moduleDownload(String source, String token) {
        return moduleDownloadCandidates(source, token, "");
    }

    /**
     * 带候选地址的版本（2026-11-12）：{@code urlsPipe} 是 JS 按「GitHub 加速」
     * 为**模块 APK** 算好的直连+代理列表（`|` 分隔），逐个尝试。
     *
     * <p>安全闸：每个候选必须**包含清单里的原始 url**（代理都是
     * `前缀+原地址` 或 `前缀里嵌原地址`）。这样即使页面被换掉，也只会下载
     * 同一个资产的不同线路，不会把下载目标改到别的包——sha256 仍由
     * AppUpdater 强制校验。
     *
     * <p>私有仓（带 token）不走代理：代理不转发 Authorization，且资产地址
     * 已被转成 api.github.com 端点，候选列表没有意义。
     *
     * <p>★ 同样不能与两参版重名（WebView 桥不支持同名重载）。
     */
    @JavascriptInterface
    public String moduleDownloadCandidates(String source, String token, String urlsPipe) {
        try {
            if (moduleCatalogJson.isEmpty()) {
                String check = moduleCheck(source, token);
                if (check.contains("\"ok\":false")) return check;
            }
            org.json.JSONObject pick =
                    com.meow.tv.runtime.RuntimeModuleCatalog.pick(moduleCatalogJson);
            String url = pick.optString("url", "");
            String sha = pick.optString("sha256", "");
            String abi = pick.optString("abi", com.meow.tv.runtime.RuntimeModuleCatalog.abi());
            /*
             * 版本闸门：不满足就别让用户下 50MB 再装失败。
             *
             *  · min_sdk      ← 模块 APK 的 minSdk（低于它系统直接拒装）
             *  · min_app_version ← 第一个支持运行时模块的壳版本；壳更老时模块
             *    即使装上也会因为 plugin-api 对不上而 AbstractMethodError
             */
            int minSdk = pick.optInt("min_sdk", 0);
            if (minSdk > 0 && android.os.Build.VERSION.SDK_INT < minSdk) {
                return "{\"ok\":false,\"error\":\"系统版本过低（需要 Android "
                        + minSdk + "+）\"}";
            }
            String minApp = pick.optString("min_app_version", "");
            if (!minApp.isEmpty()) {
                String cur = "";
                try {
                    cur = String.valueOf(activity.getPackageManager()
                            .getPackageInfo(activity.getPackageName(), 0).versionName);
                } catch (Throwable ignored) {
                }
                if (!cur.isEmpty() && !versionAtLeast(cur, minApp)) {
                    return "{\"ok\":false,\"error\":\"需要猫窝 " + minApp
                            + " 或更高（当前 " + cur + "），请先更新应用\"}";
                }
            }
            if (url.isEmpty() || sha.isEmpty()) {
                return "{\"ok\":false,\"error\":\"清单缺 url/sha256\"}";
            }
            String fileName = "meow-node-" + abi + ".apk";
            String tk2 = token == null ? "" : token;
            String real = moduleAssetUrl(url, tk2);
            // 公开仓 + 页面给了候选：逐个试（直连常被墙，代理是唯一出路）
            String pipe = urlsPipe == null ? "" : urlsPipe.trim();
            if (!pipe.isEmpty() && tk2.trim().isEmpty()) {
                StringBuilder ok = new StringBuilder();
                for (String raw : pipe.split("\\|")) {
                    String c = raw == null ? "" : raw.trim();
                    if (c.isEmpty()) continue;
                    if (!c.startsWith("http://") && !c.startsWith("https://")) continue;
                    // 只接受"原地址的线路变体"，不能是别的文件
                    if (!c.contains(real)) continue;
                    if (ok.length() > 0) ok.append('|');
                    ok.append(c);
                }
                if (ok.length() > 0) {
                    return AppUpdater.start(activity, ok.toString(), sha, tk2, fileName);
                }
            }
            return AppUpdater.start(activity, real, sha, tk2, fileName);
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + AppUpdater.escape(t.getMessage()) + "\"}";
        }
    }


    /**
     * 把 `github.com/.../releases/(latest|download/<tag>)/download/<name>` 解析成
     * `api.github.com/.../releases/assets/<id>`。
     *
     * <p><b>为什么必须解析</b>（2026-11-06 实测）：私有仓库的网页下载链接
     * **不认 Bearer token**，带 token 也照样 404；只有 API 资产端点 +
     * `Accept: application/octet-stream` 才给内容。匿名能下的是公开仓库，
     * 我们这个不是。
     */
    private String moduleAssetUrl(String url, String token) {
        try {
            // 公开仓（无 token）：标准 release 链接匿名可下，保留原地址，
            // 这样前端 downloadCandidates 的代理才会生效。
            // 私有仓才需要转 API asset（标准链接带 token 也是 404）。
            if (token == null || token.trim().isEmpty()) return url;
            int ri = url.indexOf("/releases/");
            if (ri < 0) return url;
            String rest = url.substring(ri + "/releases/".length());
            String tag = "";
            String name;
            if (rest.startsWith("latest/download/")) {
                name = rest.substring("latest/download/".length());
            } else if (rest.startsWith("download/")) {
                String tail = rest.substring("download/".length());
                int slash = tail.indexOf('/');
                if (slash < 0) return url;
                tag = tail.substring(0, slash);
                name = tail.substring(slash + 1);
            } else {
                return url;
            }
            String api = tag.isEmpty() || tag.isEmpty()
                    ? "https://api.github.com/repos/upchr/meow/releases/latest"
                    : "https://api.github.com/repos/upchr/meow/releases/tags/" + tag;
            String meta = AppUpdater.fetchManifest(api, token);
            org.json.JSONArray arr = new org.json.JSONObject(meta).optJSONArray("assets");
            if (arr == null) return url;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject a = arr.optJSONObject(i);
                if (a != null && name.equals(a.optString("name"))) {
                    return a.optString("url", url);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("MeowTv", "moduleAssetUrl failed: " + t);
        }
        return url;
    }


    // ------------------------------------------------------------ 弹幕载荷（danmu_api）
    //
    // 与运行时模块分开装：模块是通用 node 运行时（~50MB，按 ABI），载荷是可替换的
    // 业务代码（~10MB，与 PC 的 danmu-api 插件同一个 zip）。换弹幕逻辑不用重装模块，
    // 壳的 APK 也不必再内嵌这 10MB（下个版本从 assets 删掉，NodeService 仍保留兜底）。
    //
    // 清单条目：id=danmu-payload、platform=["android"]，版本与 danmu-api 一致。

    /** 上一次 payloadCheck 选中的版本（下载/应用时复用，不让页面传版本号）。 */
    private volatile String payloadVersion = "";

    /** 载荷状态：{"installed":bool,"version":"1.0.0","dir":"..."}。 */
    @JavascriptInterface
    public String payloadState() {
        try {
            android.content.Context ctx = activity.getApplicationContext();
            java.io.File dir = com.meow.tv.runtime.RuntimePayloadHost.currentDir(ctx);
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("installed", dir != null);
            o.put("version", dir == null ? "" : dir.getName());
            o.put("dir", dir == null ? "" : dir.getAbsolutePath());
            return o.toString();
        } catch (Throwable t) {
            return "{\"installed\":false,\"error\":\"" + AppUpdater.escape(t.getMessage()) + "\"}";
        }
    }

    @JavascriptInterface
    public String payloadCheck() { return payloadCheckCandidates("", "", ""); }

    /**
     * 拉清单并挑出载荷条目（不下载）。
     *
     * <p>复用 moduleCheckCandidates 的清单（同一个 packages.json），避免"检查模块"
     * 和"检查载荷"各拉一次。
     */
    @JavascriptInterface
    public String payloadCheckCandidates(String source, String token, String urlsPipe) {
        try {
            // ★ 每次都重新拉清单，不复用 moduleCatalogJson。
            //   实测（2026-11-13 .77）：先点过「检查更新」把旧清单缓存下来，
            //   之后清单新增了 danmu-payload，载荷检查仍读到旧副本 → 永远报
            //   「清单里没有 danmu-payload」，只有杀进程才好。多一次请求换正确性。
            String chk = moduleCheckCandidates(
                    source == null ? "" : source,
                    token == null ? "" : token,
                    urlsPipe == null ? "" : urlsPipe);
            if (chk.contains("\"ok\":false")) return chk;
            org.json.JSONObject pick = com.meow.tv.runtime.RuntimePayloadHost.pick(moduleCatalogJson);
            payloadVersion = pick.optString("version", "");
            org.json.JSONObject out = new org.json.JSONObject(pick.toString());
            out.put("ok", true);
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + AppUpdater.escape(t.getMessage()) + "\"}";
        }
    }

    /**
     * 下载载荷 zip（sha256 由 AppUpdater 强制校验，落盘后由 payloadApply 解压）。
     *
     * <p>安全闸与模块一致：候选必须是清单原始地址的线路变体；私有仓不走代理。
     */
    @JavascriptInterface
    public String payloadDownloadCandidates(String source, String token, String urlsPipe) {
        try {
            // ★ 清单检查**不能**吃 urlsPipe：那是给 zip 用的候选列表（下载地址），
            //   拿它当清单候选会让 fetchManifestAny 去"下载一个 zip 当 manifest"，
            //   内容里没有 "packages" → 检查必失败（2026-11-13 .77 端到端实测踩到：
            //   页面永远报"拉清单失败"，zip 只被取到一次就再也没动静）。
            String chk = payloadCheckCandidates(source, token, "");
            if (chk.contains("\"ok\":false")) return chk;
            org.json.JSONObject pick = com.meow.tv.runtime.RuntimePayloadHost.pick(moduleCatalogJson);
            String url = pick.optString("url", "");
            String sha = pick.optString("sha256", "");
            String ver = pick.optString("version", "");
            String tk = token == null ? "" : token;
            if (url.isEmpty() || sha.isEmpty()) {
                return "{\"ok\":false,\"error\":\"清单缺 url/sha256\"}";
            }
            String real = moduleAssetUrl(url, tk);
            String pipe = urlsPipe == null ? "" : urlsPipe.trim();
            StringBuilder use = new StringBuilder();
            if (!pipe.isEmpty() && tk.trim().isEmpty()) {
                for (String raw : pipe.split("\\|")) {
                    String c = raw == null ? "" : raw.trim();
                    if (c.isEmpty()) continue;
                    if (!c.startsWith("http://") && !c.startsWith("https://")) continue;
                    if (!c.contains(real)) continue;
                    if (use.length() > 0) use.append('|');
                    use.append(c);
                }
            }
            String urls = use.length() > 0 ? use.toString() : real;
            String r = AppUpdater.start(activity, urls, sha, tk, "danmu-payload-" + ver + ".zip");
            android.util.Log.i("MeowTv", "payloadDownload ver=" + ver + " -> " + r);
            return r;
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + AppUpdater.escape(t.getMessage()) + "\"}";
        }
    }

    /**
     * 把已下载的 zip 装成当前载荷版本，并重启 :node。
     *
     * <p>只认 AppUpdater 下载缓存里的文件（与"拉起安装器"同一条边界）——
     * 否则这个桥方法会变成"解压任意路径的 zip"。
     */
    @JavascriptInterface
    public String payloadApply() {
        try {
            String path = AppUpdater.lastPath;
            if (path == null || path.isEmpty()) {
                return "{\"ok\":false,\"error\":\"还没有下载好的载荷\"}";
            }
            java.io.File f = new java.io.File(path);
            String cacheRoot = activity.getCacheDir().getCanonicalPath();
            if (!f.getCanonicalPath().startsWith(cacheRoot)) {
                return "{\"ok\":false,\"error\":\"只允许使用下载缓存里的载荷\"}";
            }
            String ver = payloadVersion == null ? "" : payloadVersion;
            if (ver.isEmpty()) {
                String n = f.getName();
                if (n.startsWith("danmu-payload-") && n.endsWith(".zip")) {
                    ver = n.substring("danmu-payload-".length(), n.length() - ".zip".length());
                }
            }
            if (ver.isEmpty()) {
                return "{\"ok\":false,\"error\":\"不知道载荷版本（请重新检查更新）\"}";
            }
            android.util.Log.i("MeowTv", "payloadApply path=" + path + " ver=" + ver);
            String applied = com.meow.tv.runtime.RuntimePayloadHost.apply(
                    activity.getApplicationContext(), f, ver);
            android.util.Log.i("MeowTv", "payloadApply ok ver=" + applied);
            restartNodeService();
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("ok", true);
            o.put("version", applied);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + AppUpdater.escape(t.getMessage()) + "\"}";
        }
    }

    /** 载荷切换后重启 :node：stop → 1.5s → start（与模块安装后的做法一致）。 */
    private void restartNodeService() {
        try {
            final android.content.Context ctx = activity.getApplicationContext();
            ctx.stopService(new android.content.Intent(ctx, com.meow.tv.runtime.NodeService.class));
            new Thread(() -> {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
                try {
                    if (com.meow.tv.runtime.RuntimeModuleState.enabled(ctx)) {
                        ctx.startService(new android.content.Intent(
                                ctx, com.meow.tv.runtime.NodeService.class));
                    }
                } catch (Throwable t) {
                    android.util.Log.w("MeowTv", "restart node failed: " + t);
                }
            }, "meow-node-restart").start();
        } catch (Throwable t) {
            android.util.Log.w("MeowTv", "restartNodeService failed: " + t);
        }
    }

    /**
     * 从 release 元数据里找 `packages.json` 资产。
     *
     * <p>为什么要这一步：更新源在设置页只有**一个**输入框（应用更新与运行时模块
     * 共用），用户填的通常是仓库/release 端点，而不是某个具体资产。应用更新在
     * web 侧已经有这套解析（appUpdate.ts 的 pickManifestRelease），模块这边
     * 在原生侧做一个最小等价物，避免让页面维护两套地址。
     *
     * @param releaseJson 上一次 fetch 到的内容（可能已经是 packages.json）
     * @return 资产的 api url；解析不出来返回空串
     */
    private String moduleManifestAssetUrl(String releaseJson, String token) {
        if (releaseJson == null || releaseJson.isEmpty()) return "";
        try {
            org.json.JSONObject root = new org.json.JSONObject(releaseJson);
            org.json.JSONArray arr = root.optJSONArray("assets");
            if (arr == null) return "";
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject a = arr.optJSONObject(i);
                if (a != null && "packages.json".equals(a.optString("name"))) {
                    return a.optString("url", "");
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }


    /** `a >= b`？按点分段数字比较（1.10.0 > 1.9.9），非数字段按 0 处理。 */
    private static boolean versionAtLeast(String a, String b) {
        String[] x = String.valueOf(a).split("\\.");
        String[] y = String.valueOf(b).split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? parseIntSafe(x[i]) : 0;
            int yi = i < y.length ? parseIntSafe(y[i]) : 0;
            if (xi != yi) return xi > yi;
        }
        return true;
    }

    private static int parseIntSafe(String s) {
        try {
            int end = 0;
            while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
            return end == 0 ? 0 : Integer.parseInt(s.substring(0, end));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 重启模块：停再起（换端口/刚装完新版本时用）。 */
    @JavascriptInterface
    public String moduleRestart() {
        try {
            android.content.Context ctx = activity.getApplicationContext();
            ctx.stopService(new android.content.Intent(ctx,
                    com.meow.tv.runtime.NodeService.class));
            ctx.startService(new android.content.Intent(ctx,
                    com.meow.tv.runtime.NodeService.class));
        } catch (Throwable t) {
            android.util.Log.w("MeowTv", "moduleRestart failed: " + t);
        }
        return moduleState();
    }

    /** 卸载模块（系统会弹确认框；电视上用遥控确认）。 */
    @JavascriptInterface
    public String moduleUninstall() {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_DELETE,
                    android.net.Uri.parse("package:" + com.meow.tv.runtime.RuntimeModuleCatalog.PACKAGE));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            return "{\"ok\":true}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + AppUpdater.escape(t.getMessage()) + "\"}";
        }
    }

    /**
     * 旧接口：把前端下载好的 base64 包落盘并拉起安装器。
     *
     * <p>保留只为兼容 0.9.29 及更早的 Web 包（那个版本是页面 XHR 下载 +
     * base64 过桥）。新前端一律走 {@link #appUpdateDownload}——包不经 JS，
     * 也不会因为 CORS 在 GitHub 上失败。删掉它会让老 Web 包的更新按钮直接报错。
     *
     * @param base64 安装包内容（桥是字符串接口，只能 base64）
     * @return "ok" 表示已拉起安装器；其他字符串是错误原因
     */
    @JavascriptInterface
    public String installApk(String base64) {
        try {
            if (base64 == null || base64.isEmpty()) return "安装包为空";
            byte[] data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            if (data.length < 1024) return "安装包过小，可能下载失败";

            java.io.File dir = new java.io.File(activity.getCacheDir(), "updates");
            if (!dir.isDirectory() && !dir.mkdirs()) return "无法创建缓存目录";
            java.io.File apk = new java.io.File(dir, "meow-update.apk");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(apk)) {
                fos.write(data);
            }
            AppUpdater.lastPath = apk.getAbsolutePath();
            return AppUpdater.install(activity);
        } catch (Throwable t) {
            return "安装失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "（" + t.getMessage() + "）");
        }
    }

    // ---------------------------------------------------------------- 片库本地副本
    //
    // 片库数据全来自后端（NAS）。断网或 NAS 重启时，海报墙会变成一片灰块，
    // 而"用 NAS 库"的目的恰恰是局域网脱网访问。所以后端给出的数据顺手存一份，
    // 后端不可达时拿出来用。
    //
    // ★ 只读副本，不是独立数据源：永远后端优先，这里只在拉不到时兜底。
    //   所以不存在"两份数据谁覆盖谁"的问题。

    /** 存一份元数据 JSON（前端从后端拿到的原样文本）。 */
    @JavascriptInterface
    public boolean saveMetaMirror(String json) {
        return MetaMirror.writeMeta(activity, json);
    }

    /** 读本地元数据 JSON；没有返回空串。 */
    @JavascriptInterface
    public String loadMetaMirror() {
        return MetaMirror.readMeta(activity);
    }

    /**
     * 让原生把一张海报下载到本地，返回文件名。
     *
     * <p>为什么由原生下而不是前端 fetch：<b>CORS</b>。图床不给我们发
     * {@code Access-Control-Allow-Origin}，前端拿不到字节；而原生的
     * HttpURLConnection 没有这个限制，还能带 Referer（豆瓣图床必需）。
     *
     * @param url 后端代理地址或原始图床地址
     * @return 本地文件名（如 {@code a1b2c3d4.jpg}）；失败返回空串
     */
    @JavascriptInterface
    public String cachePoster(String url) {
        return MetaMirror.fetchPoster(activity, url);
    }

    /** 本地副本的访问路径（给前端拼 img.src）。 */
    @JavascriptInterface
    public String posterMirrorBase() {
        return "http://meow.local/mirror/poster/";
    }

    /** 本地副本统计：{ok, posters, bytes, metaBytes} */
    @JavascriptInterface
    public String metaMirrorStats() {
        return MetaMirror.stats(activity);
    }

    /** 清空本地副本（设置页用）。 */
    @JavascriptInterface
    public boolean clearMetaMirror() {
        return MetaMirror.clear(activity);
    }

    @JavascriptInterface
    public void stopLocalGateway() {
        GatewayServer.stop();
    }

    /** 本机已加载的 jar 站点数（诊断：0 说明配置没导进来或 jar 都没加载成功） */
    @JavascriptInterface
    public int localSiteCount() {
        LocalSites.load(activity);
        return LocalSites.size();
    }

    /** 最近一次失败原因，空串表示没失败 */
    @JavascriptInterface
    public String lastError() {
        return LocalSpiderHost.lastError();
    }

    /**
     * 零配置自启本机执行：没配后端地址时让 TV 自己就能出内容。
     *
     * <p><b>为什么需要它</b>：用户装了 APK 但没配后端 URL 时，首页只会显示
     * 「后端未连接 + 去设置」，必须手动点三个按钮（启用本机执行 → 填配置地址 →
     * 导入）才能看到东西。而 APK 里明明已经带了 dex jar。
     *
     * <p>这个方法把「首次启动就能用」做成一个原子动作：
     * <ol>
     *   <li>落内置 dex jar</li>
     *   <li>站点表为空时装内置兜底站（豆瓣/豆瓣预告/配置中心）</li>
     *   <li>起网关并返回地址供前端切 base</li>
     * </ol>
     *
     * <p><b>只在站点表为空且没有后端地址时调用</b>——已有配置时不能覆盖用户的选择。
     *
     * @return JSON：{ok, gateway, sites, message}
     */
    @JavascriptInterface
    public String bootstrapLocal() {
        try {
            String jar = installBundledJar();
            if (jar.isEmpty()) {
                return "{\"ok\":false,\"gateway\":\"\",\"sites\":0,\"message\":\"内置 jar 落地失败\"}";
            }
            LocalSites.load(activity);
            int added = LocalSites.size() == 0 ? LocalSites.addBundled(activity, jar) : 0;
            /*
             * ★ 零配置自启也必须过"本机能不能起网关"这道闸。
             *
             * 踩过（2026-11-04 "一进就闪退"）：前端在 localStorage 没有 meow.api 时
             * 会调 bootstrapLocal()，这里若无条件 GatewayServer.start()，32 位设备
             * 每次冷启动都在这条路径上 SIGABRT —— 用户看到的是"打开就回桌面"，
             * 而且因为地址每次都被清空，永远修不好。
             *
             * 起不来就明确返回 ok:false，让首页正常显示「后端未连接 + 去设置」。
             */
            /*
             * 64 位不再看 K_GW_FAIL：那个标记会被"强杀过"误置（见
             * MainActivity.restoreBackendMode 的注释），一次误判永久关功能。
             * 32 位一律不自动起（ART abort），所以这里只剩 is64Bit 一个条件。
             */
            boolean allowed = android.os.Process.is64Bit();
            boolean up = allowed && MainActivity.startGatewayWatched(activity);
            String gw = up ? GatewayServer.baseUrl() : "";
            /*
             * ★ 必须把网关地址同时写进 prefs[K_API]，不能只靠前端写 localStorage。
             *
             * 踩过（P0-4 冷启动永远落错误页，排查了两轮）：onPageFinished 里的
             * applyApiBaseIfNeeded() 在 prefs[K_API] 为空时会调 injectApiBase("")
             * **把 localStorage 的 meow.api 擦掉**。于是时序变成：
             *   1. 前端 bootstrapTvLocal() 写入 localStorage['meow.api'] ✔
             *   2. 页面 onPageFinished → applyApiBaseIfNeeded → prefs 为空 → 擦掉
             *   3. 下一次 base() 返回空串 → 请求 /api/sites 变**相对地址**
             *   4. meow.local 的 shouldInterceptRequest 把 /api/ 当 assets 找
             *      → 返回 text/plain "not found" → 前端报「响应不是 JSON」
             * 症状就是「后端明明起来了、日志也说写入成功，但首页永远显示未连接」。
             *
             * 写 prefs 才是这条链路的**唯一真源**：注入、reload、onResume 判断
             * 地址变更全看它。
             */
            if (up) {
                activity.getSharedPreferences(MainActivity.PREF, android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putString(MainActivity.K_API, gw)
                        // 记住形态：下次启动自动把网关拉起来（见 MainActivity.restoreBackendMode）
                        .putString(MainActivity.K_MODE, "local")
                        .apply();
                // 等端口真的能连上再返回。
                //
                // 踩过：GatewayServer.start 返回时 ServerSocket 已 bind，但 accept
                // 线程刚创建。前端紧接着 store.boot() 发 /api/health，若那一刻
                // 连接排队还没建立，WebView 直接抛 "Failed to fetch"（不是超时，
                // 是连接被拒），首页就落在"后端未连接"错误页。
                // 这个窗口只有几十毫秒，但冷启动每一次都会撞上。
                awaitGatewayReady();
                // 写进 prefs 还不够：首屏的注入已在 onPageFinished 里消费掉了
                // （那时 prefs 还是空的），所以必须让壳再注入一次并 reload。
                // 不做这一步的话 localStorage 里没有 meow.api，前端 resolveBase()
                // 退回同源空串，请求 /api/xxx 变成相对地址 → 被 assets 拦截器
                // 当成文件名 → "not found" → 首页永远"后端未连接"。
                // 方法内部自己切主线程，这里直接调。
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).applyBootstrappedApiBase();
                }
            }
            String msg = up
                    ? (added > 0 ? "已就绪（内置 jar + " + added + " 个内置站）" : "已就绪")
                    : (allowed ? "网关启动失败（端口 9978 被占）" : "本机执行在当前设备不可用");
            return "{\"ok\":" + up + ",\"gateway\":\"" + gw + "\",\"sites\":"
                    + LocalSites.size() + ",\"message\":\"" + msg + "\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"gateway\":\"\",\"sites\":0,\"message\":\""
                    + t.getClass().getSimpleName() + "\"}";
        }
    }

    /**
     * 比对设备时钟与后端时钟，偏差超过阈值就记一条可读告警。
     *
     * <p><b>为什么这个必须存在</b>：一批源（实测 Jpys / Gulu / AppRJ / Gz360 /
     * AppDrama2 / MiSou）在请求里带 {@code System.currentTimeMillis()} 生成的
     * 签名或协议时间戳，服务端会拒掉偏差过大的请求 —— 而失败表现是
     * <b>搜索静默返回空 list，不抛异常、不报错</b>，看起来像"这个源没资源"。
     *
     * <p>实测数据（MuMu 模拟器不跑 NTP，设备时间落后宿主 9h53m）：
     * <pre>
     *   docker/PC 后端（用宿主时间）：Jpys n=8，Gulu n=3
     *   TV 内置网关（用设备时间）：  Jpys n=0，Gulu n=0
     *   logcat: [search] 2-arg failed Jpys: JSONException — No value for data
     * </pre>
     *
     * <p>阈值取 5 分钟：接口签名一般容差 1-15 分钟，超过 5 分钟就该被当成
     * "值得怀疑"的状态报出来，但不至于把 1-2 分钟的误差也说成故障。
     *
     * <p>只记日志不弹窗：时钟错是设备设置问题，应用改不了它；
     * 但用户在日志页看到这一条，就能自己想明白为什么"某些源搜不到"。
     */
    /**
     * 时钟核对的"等网关就绪"包装：先等端口可连，再探一次 health。
     *
     * <p>为什么要等：{@code checkClockSkew} 会把结果写进 {@code core.LogBuffer}，
     * 而那是网关 {@code ApiServer.boot()} 里初始化的。在 boot 之前写进的是一个
     * 还没被 {@code /api/logs} 读的缓冲区 —— 实测告警"写了但查不到"。
     */
    /** URL 是否指向本机（127.0.0.1 / localhost）。 */
    private static boolean isLocalGatewayUrl(String apiBase) {
        try {
            String h = new java.net.URL(apiBase).getHost();
            return "127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h);
        } catch (Throwable t) {
            return false;
        }
    }

    static void checkClockSkewWhenReady(String apiBase) {
        if (apiBase == null || apiBase.isEmpty()) return;
        new Thread(() -> {
            awaitGatewayReady();
            checkClockSkew(apiBase);
        }, "meow-clock-wait").start();
    }

    static void checkClockSkew(String apiBase) {
        if (apiBase == null || apiBase.isEmpty()) return;
        /*
         * ★ 本机网关测不出东西：内置网关跑在同一个进程里，health 里的 now 就是
         *   设备自己的 System.currentTimeMillis()，两边必然相等。实测
         *   设备比宿主慢 9h52m，而内置网关上一次算出 skew=0min。
         *
         *   真正需要核对的是**远端后端**（docker / PC 插件）——那种情况下
         *   spider 跑在远端、用远端的时钟签名，设备时钟错了才会让源报错。
         *   注意电视内置网关这条路上，spider 用的也是设备时钟，所以时钟错
         *   依旧会让签名类源失败；只是"对比"这个手段在本地无意义。
         */
        if (isLocalGatewayUrl(apiBase)) {
            android.util.Log.i("MeowTv", "clockCheck 跳过：本机网关（时间同源，比较无意义）");
            return;
        }
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL(apiBase + "/api/health").openConnection();
                c.setConnectTimeout(3_000);
                c.setReadTimeout(3_000);
                long t0 = System.currentTimeMillis();
                java.io.InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                c.disconnect();
                long mid = (t0 + System.currentTimeMillis()) / 2;
                org.json.JSONObject o = new org.json.JSONObject(
                        new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                long server = o.optLong("now", 0L);
                if (server <= 0L) {
                    android.util.Log.i("MeowTv", "clockCheck: health 无 now 字段（后端版本较旧）");
                    return;
                }
                long skew = mid - server;
                long abs = Math.abs(skew);
                if (abs < 5 * 60 * 1000L) return;
                long mins = Math.abs(skew) / 60000L;
                String msg = "设备时钟与后端相差 " + mins + " 分钟（设备"
                        + (skew > 0 ? "快" : "慢") + "），签名类源"
                        + "（Jpys/Gulu/Gz360/MiSou 等）会静默搜不到结果。"
                        + "请把电视的日期时间设为自动校准。";
                android.util.Log.w("MeowTv", msg);
                core.LogBuffer.warn("dns", msg);
            } catch (Throwable t) {
                // 探测失败不影响任何流程；但留一行日志，否则"没告警"可能是
                // 探测根本没跑（实测就撞过一次：health 里还没有 now 字段时
                // 静默 return，看着像阈值判错了）。
                android.util.Log.i("MeowTv", "clockCheck 跳过: "
                        + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        }, "meow-clock-check").start();
    }

    /**
     * 等到网关端口真正可连接（最多 2 秒）。
     *
     * <p>判据是"能建立 TCP 连接"，不是"ServerSocket 已 bind"——这两件事之间隔着
     * accept 线程的启动。WebView 的 fetch 在连接被拒时抛的是
     * {@code TypeError: Failed to fetch}，和 CORS 失败一模一样的文案，
     * 排障时会误以为是跨域问题（实际 CORS 头是好的，实测 curl 带 Origin 正常回
     * {@code access-control-allow-origin}）。
     *
     * <p>用裸 Socket 探测而不是打一次 HTTP：这里只需确认监听已就绪。
     */
    static void awaitGatewayReady() {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            java.net.Socket s = new java.net.Socket();
            try {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", GatewayServer.port()), 150);
                return;
            } catch (Throwable ignored) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } finally {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 把 APK 内置的 dex 形态 spider jar 落地到 codeCacheDir，返回可用路径。
     *
     * <p><b>为什么需要内置</b>：本机执行的站点表来自配置文件，而配置里的 jar 地址
     * 是外网 URL。没有外网（或那个地址挂了）时本机执行完全不可用——一个站点都跑不起来。
     * 内置一份 1MB 的 dex jar，至少让「装了 APK 就有可执行内核」成立。
     *
     * <p><b>为什么必须放 codeCacheDir 且设只读</b>：Android 10+ 拒绝从可写目录
     * 加载 dex（{@code SecurityException: Writable dex file ... is not allowed}）。
     * assets 里的文件不可直接加载（不是真实文件路径），所以必须先拷出来。
     *
     * @return 落地后的绝对路径；失败返回空串
     */
    @JavascriptInterface
    public String installBundledJar() {
        try {
            java.io.File dir = new java.io.File(activity.getCodeCacheDir(), "spider-jars");
            if (!dir.exists() && !dir.mkdirs()) return "";
            java.io.File out = new java.io.File(dir, "bundled.jar");
            // 已经落地过就不重复拷：1MB 每次启动都拷一遍没必要
            if (!out.isFile() || out.length() < 1024) {
                java.io.InputStream in = activity.getAssets().open("spider.jar");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                } finally {
                    in.close();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            out.setReadOnly();
            if (out.canWrite()) return "";
            return out.getAbsolutePath();
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 缓存导出到 U 盘

    /**
     * 是否支持把缓存保存到 U 盘/外接存储。
     *
     * <p>前端据此显示「保存到本机」按钮。实现走 SAF（Storage Access Framework）：
     * targetSdk 34 下按路径写外部存储需要 MANAGE_EXTERNAL_STORAGE（敏感权限），
     * 而 SAF 只要用户点一次授权，不需要任何权限声明。
     */
    @JavascriptInterface
    public boolean hasCacheSaver() {
        return true;
    }

    /** 已授权的保存目录描述；空串表示还没授权。 */
    @JavascriptInterface
    public String cacheDirLabel() {
        return CacheDownloader.dirLabel(activity);
    }

    /**
     * 弹系统目录选择器（U 盘或内置存储）。
     *
     * <p>结果经 MainActivity.onActivityResult 回到 {@link CacheDownloader}；
     * 这里只负责发起，JS 侧稍后用 cacheDirLabel() 读新值。
     */
    @JavascriptInterface
    public boolean pickCacheDir() {
        return CacheDownloader.pickDir(activity);
    }

    // ------------------------------------------------ 片库「本地目录」选择器
    //
    // 与缓存目录不同，片库的路径要交给**后端**（本机网关）用 java.io.File 去
    // 列举/流式/转码，所以不能要 SAF 的 content:// 树 URI，必须是真实路径。
    // Android 11+ 读任意目录唯一能拿到真实路径的办法就是「所有文件访问权限」。
    // 这三个方法读文件系统 → 必须过 trustedOrigin()（见类注释）。

    /** 是否已授予「所有文件访问权限」（Android 10 及以下不需要）。 */
    @JavascriptInterface
    public boolean hasAllFiles() {
        if (!trustedOrigin()) return false;
        try {
            return android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 打开系统的「所有文件访问权限」页面。结果异步，JS 侧回来再查 hasAllFiles()。 */
    @JavascriptInterface
    public boolean requestAllFiles() {
        if (!trustedOrigin()) return false;
        if (android.os.Build.VERSION.SDK_INT < 30) return true;
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:" + activity.getPackageName()));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            return true;
        } catch (Throwable t) {
            try {
                activity.startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                return true;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    /**
     * 列子目录（片库本地目录选择器）。
     *
     * @param path 绝对路径；空串 = 返回可用的存储卷根（内置存储 + U 盘/SD 卡）
     * @return JSON 数组 [{"name","path"}]，读不到返回 []
     */
    @JavascriptInterface
    public String listDirs(String path) {
        org.json.JSONArray out = new org.json.JSONArray();
        if (!trustedOrigin()) {
            android.util.Log.w("MeowBridge", "listDirs denied: url=" + MainActivity.currentUrl());
            return out.toString();
        }        // ★ 每一步各自兜错：整段包一个 try 的话，getExternalFilesDirs 之类
        //   一抛异常连"内置存储"都出不来，界面表现为选择器一片空白（实测踩过）。
        java.util.List<java.io.File> list = new java.util.ArrayList<>();
        if (path == null || path.trim().isEmpty()) {
            try {
                java.io.File ext = android.os.Environment.getExternalStorageDirectory();
                if (ext != null) list.add(ext);
            } catch (Throwable t) {
                android.util.Log.w("MeowBridge", "ext dir: " + t);
            }
            try {
                java.io.File[] dirs = activity.getExternalFilesDirs(null);
                if (dirs != null) {
                    for (java.io.File f : dirs) {
                        if (f == null) continue;
                        // /storage/XXXX/Android/data/<pkg>/files → 上溯 4 级拿到卷根
                        java.io.File vol = f;
                        for (int i = 0; i < 4 && vol != null; i++) vol = vol.getParentFile();
                        if (vol == null || !vol.canRead()) continue;
                        if (!list.contains(vol)) list.add(vol);
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("MeowBridge", "external dirs: " + t);
            }
        } else {
            try {
                java.io.File[] kids = new java.io.File(path).listFiles();
                if (kids != null) {
                    java.util.Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                    for (java.io.File f : kids) {
                        if (!f.isDirectory() || f.getName().startsWith(".")) continue;
                        list.add(f);
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("MeowBridge", "list " + path + ": " + t);
            }
        }
        for (java.io.File f : list) {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", f.getName());
                o.put("path", f.getAbsolutePath());
                out.put(o);
            } catch (Throwable ignored) {
            }
        }
        return out.toString();
    }

    /** 把一集缓存导出到已授权目录；返回立即结果，进度用 cacheState() 轮询。 */
    @JavascriptInterface
    public String saveCache(String url, String name, String ep) {
        try {
            return CacheDownloader.start(activity, url, name, ep);
        } catch (Throwable t) {
            return "保存失败：" + t.getMessage();
        }
    }

    /** 下载进度：{"state":"running","percent":42,"message":"...","file":"..."} */
    @JavascriptInterface
    public String cacheState() {
        return CacheDownloader.stateJson();
    }

    /** 已保存到 U 盘的文件清单（JSON 数组：uri/name/bytes/at）。 */
    @JavascriptInterface
    public String savedCacheFiles() {
        return CacheDownloader.savedJson(activity);
    }

    /** 播放已保存到 U 盘的文件（content:// 直通原生 ExoPlayer）。 */
    @JavascriptInterface
    public boolean playSavedCache(String uri, String title) {
        return CacheDownloader.play(activity, uri, title);
    }

    /**
     * 导入一份 CatVod 配置，把其中"本机能执行"的 jar 站点落到端上。
     *
     * @param configUrl 配置地址（相对 jar 路径按它补全）
     * @return 形如 `{"ok":true,"total":298,"imported":12,"skipped":286,"message":"..."}`
     */
    @JavascriptInterface
    public String importLocalConfig(String configUrl) {
        try {
            LocalSites.load(activity);
            String normalized = core.ConfigText.idnUrl(configUrl);
            String json = fetch(normalized);
            if (json == null || json.isEmpty()) {
                return "{\"ok\":false,\"message\":\"配置下载失败\"}";
            }
            LocalSites.ImportResult r =
                    LocalSites.importFrom(activity, json, normalized);
            /*
             * 导入成功 = 站点表/ jar 地址都可能变了 → 旧 DexClassLoader 与旧
             * Spider 实例必须作废，否则"换了配置还跑旧代码"，而且每换一次
             * 就在非移动区多叠一份类对象（32 位 ART 的 MarkNonMoving 最敏感）。
             */
            if (r.ok) {
                /*
                 * ★ 反向同步网关表。
                 *
                 * 这个入口（"导入到本机"）原来只写端上的 SharedPreferences 表，
                 * 网关的 config.sites.json 一个字不动 —— 结果是导入完站能执行、
                 * 但站点列表（来自网关 /api/sites）里根本没有它们。
                 * 网关此刻可能还没起（前端是导入之后才 startLocalGateway），
                 * 所以这里直接写文件，不依赖网关进程。
                 */
                LocalSites.syncGatewayConfig(activity, json, normalized);
                LocalSpiderHost.clear(activity);
                GatewayServer.restart(activity);
            }
            return "{\"ok\":" + r.ok + ",\"total\":" + r.total
                    + ",\"imported\":" + r.imported + ",\"skipped\":" + r.skipped
                    + ",\"message\":\"" + r.message.replace("\"", "\\\"") + "\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"message\":\"" + t.getClass().getSimpleName() + "\"}";
        }
    }

    private String fetch(String url) {
        url = core.ConfigText.idnUrl(url);
        String last = null;
        /*
         * 配置站会按 UA 分流：okhttp 给 2423 加密配置，浏览器 UA 给 HTML 首页。
         * 先按 APK 客户端 UA 请求并解码；不是 JSON 再回退浏览器 UA。
         */
        for (String ua : new String[]{"okhttp/3.12.0", "Mozilla/5.0"}) {
            String decoded = core.ConfigText.decode(fetchOnce(url, ua));
            if (core.ConfigText.looksJson(decoded)) return decoded;
            if (decoded != null && !decoded.trim().isEmpty()) last = decoded;
        }
        return last;
    }

    private String fetchOnce(String url, String ua) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(12_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (c.getResponseCode() != 200) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 不依赖任何配置文件，用 APK 内置的 dex jar 直接建一份可执行站点表。
     *
     * <p><b>为什么要有这只手</b>：本机执行原本只有一条路——导入一个带 jar 的配置。
     * 用户没有配置、或配置里的 jar 地址不可达时，「本机执行」就完全没法用，
     * 而 APK 里明明已经带了一份能跑的 dex jar（{@link #installBundledJar}）。
     *
     * <p>站点表仍是空的话只装三个最稳的内置类（Douban / Config / YGP），
     * 它们不依赖 ext、也不需要签名，够验证链路是否通。真实使用还是建议导入配置。
     *
     * @return 与 importLocalConfig 同形：{ok,total,imported,skipped,message}
     */
    @JavascriptInterface
    public String importBundledSites() {
        try {
            String jarPath = installBundledJar();
            if (jarPath.isEmpty()) {
                return "{\"ok\":false,\"total\":0,\"imported\":0,\"skipped\":0,"
                        + "\"message\":\"内置 jar 落地失败\"}";
            }
            LocalSites.load(activity);
            int imported = LocalSites.addBundled(activity, jarPath);
            String msg = imported > 0
                    ? "已启用 " + imported + " 个内置站点"
                    : "内置站点已在表中";
            return "{\"ok\":true,\"total\":" + imported + ",\"imported\":" + imported
                    + ",\"skipped\":0,\"message\":\"" + msg + "\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"total\":0,\"imported\":0,\"skipped\":0,"
                    + "\"message\":\"" + t.getClass().getSimpleName() + "\"}";
        }
    }

    // ---------------------------------------------------------------- 页面嗅探

    /**
     * 用不可见 WebView 打开播放页，拦请求找媒体地址。
     *
     * <p>这是**纯 JS 计算地址**的唯一解法：服务端抓 HTML 只能拿到源码。
     * 调用后立即返回 true，结果要再调 {@link #takeSniffResult()} 取
     * （嗅探本身是异步的，最长 15 秒）。
     */
    @JavascriptInterface
    public boolean sniffPage(String pageUrl) {
        try {
            android.content.Intent i =
                    new android.content.Intent(activity, SniffActivity.class);
            i.putExtra(SniffActivity.EXTRA_PAGE, pageUrl);
            activity.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 嗅探是否还在跑 */
    @JavascriptInterface
    public boolean sniffRunning() {
        return SniffActivity.isRunning();
    }

    /** 取嗅探结果：有地址就返回它，没有返回空串（null 与"还没结束"要靠 running 区分） */
    @JavascriptInterface
    public String takeSniffResult() {
        String r = SniffActivity.takeResult();
        return r == null ? "" : r;
    }

    // ---------------------------------------------------------------- 系统音量 / 亮度
    //
    // 页面播放器的手势（左右分区上下滑）需要这两个能力：
    //   · 音量：TV 端已经把遥控器音量键旁路掉（只留静音），页面上没有别的入口，
    //     而 `video.volume` 只是元素音量——用户退出应用会发现系统音量没变。
    //     所以必须写 STREAM_MUSIC。
    //   · 亮度：Web 的 `filter: brightness()` 只能变暗、调不亮，而"太暗了调亮"
    //     恰恰是手机上最高频的操作。系统亮度只能由原生写。
    //
    // 两个都加了 trustedOrigin()：它们会被页面任意 JS 调用，
    // 且"把亮度调到 0"足以让用户以为设备坏了。
    // 读接口（get*）也要判：读亮度本身就是信息泄露（能推断使用时段/环境）。

    /** 系统媒体音量 0–100；读不到返回 -1 */
    @JavascriptInterface
    public int getVolume() {
        if (!trustedOrigin()) return -1;
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                    activity.getSystemService(Activity.AUDIO_SERVICE);
            if (am == null) return -1;
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            if (max <= 0) return -1;
            return Math.round(cur * 100f / max);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 设系统媒体音量 0–100。
     *
     * <p>用 {@code setStreamVolume} 而不是 {@code adjustStreamVolume}：
     * 手势是连续拖动，给的是绝对目标值；用 step 接口会漂。
     * flags 传 0（不弹系统音量条）——页面自己画了浮层，两个一起弹会打架。
     */
    @JavascriptInterface
    public boolean setVolume(int pct) {
        if (!trustedOrigin()) return false;
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                    activity.getSystemService(Activity.AUDIO_SERVICE);
            if (am == null) return false;
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            if (max <= 0) return false;
            int v = Math.max(0, Math.min(100, pct));
            int idx = Math.round(v * max / 100f);
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, idx, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 屏幕亮度 0–100；-1 = 跟随系统（自动）或读不到 */
    @JavascriptInterface
    public int getBrightness() {
        if (!trustedOrigin()) return -1;
        try {
            android.view.WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
            float b = lp.screenBrightness;
            // <0 是系统的"BRIGHTNESS_OVERRIDE_NONE"，即跟随自动亮度
            if (b < 0) return -1;
            return Math.round(b * 100f);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 设屏幕亮度 0–100；传 100 以上（或负数）恢复"跟随系统"。
     *
     * <p>为什么 100 要转成跟随系统：系统亮度上限因人而异（有的机器手动值
     * 也到不了屏幕物理最亮），写死 1.0f 反而比自动暗。用户滑到顶的预期是
     * "最亮"，交给系统去决定最亮是多少更稳。
     */
    @JavascriptInterface
    public boolean setBrightness(int pct) {
        if (!trustedOrigin()) return false;
        try {
            activity.runOnUiThread(() -> {
                try {
                    android.view.WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
                    if (pct < 0 || pct >= 100) {
                        lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                    } else {
                        // 下限 0.05：0 会让屏幕全黑，用户连"滑回来"都看不到手指在哪
                        lp.screenBrightness = Math.max(0.05f, Math.min(1f, pct / 100f));
                    }
                    activity.getWindow().setAttributes(lp);
                } catch (Throwable ignored) {
                }
            });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
