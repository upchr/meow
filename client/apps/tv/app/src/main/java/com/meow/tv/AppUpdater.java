package com.meow.tv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 应用自身的在线更新（TV 侧原生实现）。
 *
 * <h3>为什么清单和下载都在原生做</h3>
 *
 * 三条实测约束，任何一条都足以否决"页面里 XHR"这条路：
 * <ol>
 *   <li><b>GitHub Release 资产没有 CORS 头</b>。实测最终 200 响应里完全没有
 *       {@code Access-Control-Allow-Origin}，页面对它发 XHR 必被浏览器拦。</li>
 *   <li><b>私有仓要带 Authorization</b>。{@code raw.githubusercontent.com} 的
 *       CORS 预检对 {@code access-control-request-headers: authorization}
 *       回 403 且不带 {@code Access-Control-Allow-Headers} —— 浏览器不允许
 *       带 token 发请求。</li>
 *   <li><b>整包经 JS 桥要 base64</b>。11MB 的 apk 变成约 15MB 字符串，
 *       过 {@code addJavascriptInterface} 的字符串参数很勉强。</li>
 * </ol>
 *
 * <p>所以清单读取、下载、校验全在原生。页面只负责"比版本 + 展示 + 点按钮"。
 *
 * <h3>为什么不用 CacheDownloader</h3>
 *
 * 那个是"导出缓存到 U 盘"，走 SAF（用户授权目录 + DocumentsContract），
 * 装 APK 要的是应用私有 {@code cacheDir}（且 {@code FileProvider} 只暴露那里）。
 * 两条路的目标目录与权限模型都不同，硬套会互相拖累。
 *
 * <h3>线程模型</h3>
 *
 * 与 {@link CacheDownloader} 一致：下载跑独立线程，进度写静态字段，
 * JS 用 {@link #stateJson()} 轮询（WebView 的 evaluateJavascript 是单次的，
 * 没有回调通道）。一次只允许跑一个任务。
 */
public final class AppUpdater {

    /** idle | downloading | verifying | ready | failed | cancelled */
    static volatile String state = "idle";
    static volatile String message = "";
    static volatile String lastPath = "";
    static final AtomicInteger percent = new AtomicInteger(0);
    static volatile long received = 0;
    static volatile long total = 0;

    private static final AtomicBoolean cancel = new AtomicBoolean(false);
    /** 同一时刻只允许一个下载：并发写同一个 apk 没有意义，还会互相覆盖 */
    private static volatile boolean busy = false;

    private AppUpdater() {
    }

    private static OkHttpClient http() {
        return new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                /*
                 * ★ 读超时**必须有上限**（原来是 0 = 永不超时）。
                 *
                 * OkHttp 的 readTimeout 是"两次读之间允许的空闲时间"，不是总时长：
                 * 11MB 的包只要还在冒数据就不会超时，慢网络照样能下完。而 0 的
                 * 代价是——连上却不回数据的地址（被墙的 GitHub、黑洞代理）会让
                 * 线程**永远**等下去。这条链路是同步 JS 桥（appUpdateManifest /
                 * moduleCheck），卡住的就是 WebView 的 JS 线程，表现是整页卡死、
                 * CPU 归零、只能杀进程（实测踩过：启动 5 秒后的自动检查正好撞上
                 * 用户在设置页）。30 秒是"确实死了"的判据，不是速度门槛。
                 */
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    private static void reset() {
        state = "idle";
        message = "";
        lastPath = "";
        percent.set(0);
        received = 0;
        total = 0;
    }

    /**
     * 读清单文本（供 JS parse）。
     *
     * <p>返回 null 表示失败，失败原因在 {@link #lastError()}——桥方法只能返
     * 一种类型，用两个调用比塞一个有歧义的字符串更好判断。
     */
    public static String fetchManifest(String url, String token) {
        lastError = "";
        if (url == null || url.trim().isEmpty()) {
            lastError = "清单地址为空";
            return null;
        }
        String u = url.trim();
        android.util.Log.i("MeowUpd", "manifest get " + u);
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            // 只认 http(s)：file:// 与自定义协议在 TV 上没有正当用途，
            // 且容易成为"读任意文件"的入口。
            lastError = "清单地址必须是 http(s)：" + u;
            return null;
        }
        // ★ 这里**只做单次 GET，不做 release 端点解析**。
        //
        // 踩过（2026-10-29）：一开始在这层也做了"release 端点 → 找 packages.json
        // → 拉内容"的解析，结果与前端 `fetchManifestText` 的解析**叠了两层**：
        // Java 已经把 tags/<tag> 解析成清单返回，前端拿到后又去里面找资产，
        // 报「release xxx 里没有 packages.json 资产」—— 而资产明明在，
        // 错误信息完全误导（看着像清单没上传）。
        //
        // 解析路径只能有一处：**前端**（`isReleaseEndpoint` / pickManifestRelease /
        // manifestAssetUrlFrom）。原生只负责"把这段地址的字节取回来"。
        return httpGetText(u, token);
    }

    /**
     * 按候选列表拉清单（`|` 分隔的直连 + GitHub 加速代理）。
     *
     * <p>★ 为什么模块链也要走加速（2026-11-12）：模块清单与 50MB 的模块 APK
     * 都从 GitHub 取，被墙用户直连必失败。应用自更新一直有候选列表
     * （JS 算好、`appUpdateDownload(urls.join('|'))` 传进来），模块链却只传
     * 单地址——同一台电视能更新壳、却装不上模块。这里补齐清单侧，
     * 下载侧由 {@link #start} 的 urlsPipe 直接支持。
     *
     * <p>判据是内容含 {@code "packages"}：代理错误页/HTML 会返回 200，
     * 只看"取到字节"会被它骗过去。
     */
    public static String fetchManifestAny(String urlsPipe, String token) {
        lastError = "";
        if (urlsPipe == null || urlsPipe.trim().isEmpty()) {
            lastError = "清单地址为空";
            return null;
        }
        java.util.List<String> urls = new java.util.ArrayList<>();
        if (urlsPipe != null) {
            for (String raw : urlsPipe.split("\\|")) {
                String u = raw == null ? "" : raw.trim();
                if (!u.isEmpty() && !urls.contains(u)) urls.add(u);
            }
        }
        if (urls.isEmpty()) {
            lastError = "所有候选地址都失败";
            return null;
        }
        if (urls.size() == 1) {
            String t = fetchManifest(urls.get(0), token);
            return (t != null && t.contains("\"packages\"")) ? t : null;
        }
        // ★ 并发竞速，不是串行逐个试（2026-11-12）。
        //   串行的代价：直连被墙时要等满 15s 连接超时才开始下一个候选，
        //   实测一次"已是最新"要 30–40 秒。并发之后总耗时 ≈ **最慢的那一条**。
        //   判据仍是内容含 "packages"（代理的 HTML 错误页会 200）。
        final String[] box = new String[1];
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        final Object lock = new Object();
        for (final String u : urls) {
            Thread t = new Thread(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    String text = fetchManifest(u, token);
                    boolean hit = text != null && text.contains("\"packages\"");
                    // 诊断：每条候选的成败与耗时都落日志。踩过「加速开着却只试直连」，
                    // 当时一行日志都没有，只能靠猜（2026-11-13 .77 实测）。
                    android.util.Log.i("MeowUpd", "cand " + (hit ? "ok" : "bad") + " "
                            + (System.currentTimeMillis() - t0) + "ms " + u);
                    if (!hit) return;
                    synchronized (lock) {
                        if (box[0] == null) {
                            box[0] = text;
                            lock.notifyAll();
                        }
                    }
                } catch (Throwable e) {
                    android.util.Log.i("MeowUpd", "cand err " + (System.currentTimeMillis() - t0)
                            + "ms " + u + " :: " + e.getMessage());
                }
            }, "meow-manifest-race");
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 50_000L;
            while (box[0] == null) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    lock.wait(Math.min(left, 500L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // 剩下的线程自己会因 OkHttp 的 15s/30s 超时结束；不必强杀（中断也拦不住 socket 读）
        if (box[0] == null) {
            lastError = "所有候选地址都失败";
            return null;
        }
        return box[0];
    }

    /**
     * 这个地址能不能收到访问令牌。
     *
     * <p><b>只发给 GitHub 自己的域名</b>，其他一律不发。两个理由：
     * <ul>
     *   <li><b>安全</b>：用户填的是 GitHub PAT，但它同时供「更新源」和
     *       「插件源」使用。源指向自建文件服务器（NAS / 内网 nginx）后，
     *       无条件的 Authorization 会把 PAT 明文送过去，对方 access log
     *       里就留下了。</li>
     *   <li><b>功能</b>：不少简易静态服务器遇到未知的 Authorization 头
     *       直接回 401，表现为"自建源怎么都连不上"，而日志只有一条 401。</li>
     * </ul>
     *
     * <p>私有仓的资产地址在 {@code toDownloadableUrl} 里已转成
     * {@code api.github.com/.../releases/assets/<id>}，所以两种仓都覆盖到了。
     */
    private static boolean tokenAllowedFor(String url) {
        if (url == null || !url.toLowerCase().startsWith("https://")) return false;
        String host = url.substring("https://".length());
        int cut = host.length();
        int s = host.indexOf('/');
        if (s >= 0 && s < cut) cut = s;
        int q = host.indexOf('?');
        if (q >= 0 && q < cut) cut = q;
        int h = host.indexOf('#');
        if (h >= 0 && h < cut) cut = h;
        host = host.substring(0, cut);
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        host = host.toLowerCase();
        return "api.github.com".equals(host)
                || "github.com".equals(host)
                || "raw.githubusercontent.com".equals(host)
                || "codeload.github.com".equals(host)
                || "objects.githubusercontent.com".equals(host);
    }

    /** 单次 GET，返回正文；失败时设 lastError 并返回 null。 */
    private static String httpGetText(String u, String token) {
        Response resp = null;
        try {
            Request.Builder rb = new Request.Builder()
                    .url(u)
                    .header("User-Agent", "meow-tv-updater");
            // 令牌只发给 GitHub 域名（自建源不该收到用户的 PAT，见 tokenAllowedFor）
            if (token != null && !token.trim().isEmpty() && tokenAllowedFor(u)) {
                rb.header("Authorization", "Bearer " + token.trim());
            }
            // ★ 只有「资产内容」端点要 octet-stream；元数据端点保持默认 JSON。
            //   不加这个头，拉 assets/<id> 会拿到描述资产的 JSON（没有 packages）。
            if (u.contains("/releases/assets/")) {
                rb.header("Accept", "application/octet-stream");
            }
            resp = http().newCall(rb.build()).execute();
            if (!resp.isSuccessful()) {
                // 401/404 是私有仓最常见的两种：前者 token 无效，后者仓库/文件不存在
                String hint = (resp.code() == 401 || resp.code() == 403)
                        ? "（私有仓需要访问令牌，或令牌无权读取）"
                        : (resp.code() == 404 ? "（地址不存在，或是私有仓未带令牌）" : "");
                lastError = "拉取清单失败：HTTP " + resp.code() + hint;
                return null;
            }
            ResponseBody body = resp.body();
            if (body == null) {
                lastError = "清单响应为空";
                return null;
            }
            return body.string();
        } catch (Throwable t) {
            lastError = "拉取清单失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "（" + t.getMessage() + "）");
            return null;
        } finally {
            if (resp != null) resp.close();
        }
    }

    /** 最近一次失败的详细原因（fetchManifest 返回 null 时读它）。 */
    static volatile String lastError = "";

    public static String lastError() {
        return lastError;
    }

    /**
     * 开始下载（立即返回，进度靠 {@link #stateJson()} 轮询）。
     *
     * <p>下载完**不自动安装**：安装是用户的点击。静默替换应用自身
     * 等于把远端变成任意代码执行入口。
     *
     * @param urlsPipe      候选地址，用 `|` 分隔（直连 + 若干 GitHub 加速代理）。
     *                      逐个尝试，第一个能下通的胜出——实测直连 github.com
     *                      经常 TLS 握手超时。
     * @param expectSha256  清单里的 sha256（空串直接拒绝：无法校验）
     * @return 一句话结果；真正的进度靠轮询
     */
    public static String start(Activity a, String urlsPipe, String expectSha256, String token, String fileName) {
        if (busy) return "已有下载在进行：" + message;
        if (urlsPipe == null || urlsPipe.trim().isEmpty()) return "下载地址为空";
        String[] urls = urlsPipe.split("\\|");
        for (String u : urls) {
            String t = u.trim();
            if (!t.startsWith("http://") && !t.startsWith("https://")) {
                return "下载地址必须是 http(s)：" + t;
            }
        }
        String want = expectSha256 == null ? "" : expectSha256.trim().toLowerCase();
        if (want.isEmpty()) {
            return "清单缺 sha256，拒绝下载（无法校验的安装包不该落盘）";
        }

        reset();
        cancel.set(false);
        busy = true;
        state = "downloading";
        message = "下载中 0%";

        final File dir = new File(a.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            busy = false;
            state = "failed";
            message = "无法创建缓存目录";
            return message;
        }
        // 文件名只用 basename 并过滤可疑字符：清单来自网络，
        // 不能让 "../../x" 把文件写到缓存目录之外。
        final File apk = new File(dir, safeName(fileName, urls[0].trim()));
        //noinspection ResultOfMethodCallIgnored
        apk.delete();

        final String[] fUrls = urls;
        final String fToken = token == null ? "" : token.trim();
        new Thread(() -> {
            String lastErr = "没有可用的下载地址";
            for (int i = 0; i < fUrls.length; i++) {
                if (cancel.get()) {
                    state = "cancelled";
                    message = "已取消";
                    busy = false;
                    return;
                }
                if (i > 0) {
                    // 让用户知道在换线路，而不是看着进度不动
                    percent.set(0);
                    message = "换线路重试（" + (i + 1) + " / " + fUrls.length + "）…";
                }
                // 换地址前清掉半截文件，避免把两份内容拼在一起
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                String r = tryOne(fUrls[i].trim(), want, fToken, apk);
                if (r == null) {
                    lastPath = apk.getAbsolutePath();
                    state = "ready";
                    android.util.Log.i("MeowUpd", "ready " + apk.getName()
                            + " " + apk.length() + "B");
                    percent.set(100);
                    message = "下载完成，校验通过";
                    busy = false;
                    return;
                }
                if (cancel.get()) {
                    state = "cancelled";
                    message = "已取消";
                    busy = false;
                    return;
                }
                // 校验失败是内容问题：换线路没有意义，而且可能意味着被投毒
                if (r.contains("校验失败")) {
                    //noinspection ResultOfMethodCallIgnored
                    apk.delete();
                    fail(r);
                    return;
                }
                lastErr = r;
            }
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            fail(lastErr + "（已尝试 " + fUrls.length + " 个地址）");
        }, "app-update").start();
        return "已开始下载";
    }

    /**
     * 试一个地址。
     *
     * @return null = 成功（文件已落盘且校验通过）；否则是错误描述
     */
    private static String tryOne(String url, String want, String token, File apk) {
        Response resp = null;
        try {
            Request.Builder rb = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "meow-tv-updater");
            // 同 httpGetText：只发给 GitHub 域名
            if (!token.isEmpty() && tokenAllowedFor(url)) {
                rb.header("Authorization", "Bearer " + token);
            }
            // ★ API 资产端点必须显式要二进制。不加的话拿到的是描述资产的
            //   JSON（几百字节），下载"成功"但 sha256 对不上 —— 与 PC 侧
            //   installer.rs / appupdate.rs 同一个坑。
            if (url.contains("/releases/assets/")) {
                rb.header("Accept", "application/octet-stream");
            }
            resp = http().newCall(rb.build()).execute();
            if (!resp.isSuccessful()) return "HTTP " + resp.code();
            ResponseBody body = resp.body();
            if (body == null) return "下载响应为空";

            total = body.contentLength();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long got = 0;
            byte[] buf = new byte[64 * 1024];
            /*
             * ★ 慢启动保护（2026-11-13 用户报「MuMu 更新过慢」）。
             *
             * 候选列表是「直连 + 若干加速代理」，直连排第一。国内直连
             * github.com 的 release 资产经常"能连上但只有几十 KB/s"——
             * 不报错，所以原来的"失败才换线路"永远不会触发，20MB 的包
             * 就这样慢慢爬完。
             *
             * 判据：前 5 秒内下载量 < 200KB（约 40KB/s）就放弃这条线路，
             * 交给外层换下一个候选。已经下到的字节由外层删除后重下。
             */
            final long slowStartAt = System.currentTimeMillis();
            final long SLOW_WINDOW_MS = 5000L;
            final long SLOW_MIN_BYTES = 200L * 1024L;
            try (InputStream in = body.byteStream();
                 FileOutputStream out = new FileOutputStream(apk)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancel.get()) return "已取消";
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    got += n;
                    received = got;
                    if (got < SLOW_MIN_BYTES
                            && System.currentTimeMillis() - slowStartAt > SLOW_WINDOW_MS) {
                        return "线路太慢（5 秒仅 " + (got / 1024) + "KB，已换下一条）";
                    }
                    if (total > 0) {
                        int pct = (int) Math.min(99, got * 100 / total);
                        percent.set(pct);
                        message = "下载中 " + pct + "%";
                    }
                }
                out.flush();
            }

            state = "verifying";
            percent.set(100);
            message = "校验中…";
            String have = hex(md.digest());
            if (!have.equalsIgnoreCase(want)) {
                return "校验失败：期望 " + shortSha(want) + " 实际 " + shortSha(have);
            }
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "（" + t.getMessage() + "）");
        } finally {
            if (resp != null) resp.close();
        }
    }

    public static boolean cancel() {
        if (!busy) return false;
        cancel.set(true);
        return true;
    }

    /**
     * 把已下载并校验通过的 apk 交给系统安装器。
     *
     * @return "ok" 表示已拉起安装器；其他字符串是错误原因
     */
    public static String install(Activity a) {
        if (lastPath == null || lastPath.isEmpty()) return "还没有下载好的安装包";
        File apk = new File(lastPath);
        // 只允许安装缓存目录里的包：这个能力是"拉起安装器"，
        // 不能让它变成"安装任意路径的 apk"。
        try {
            String cacheRoot = a.getCacheDir().getCanonicalPath();
            if (!apk.getCanonicalPath().startsWith(cacheRoot)) {
                return "只允许安装下载缓存里的包";
            }
        } catch (Throwable ignored) {
            return "无法校验安装包路径";
        }
        if (!apk.isFile()) return "安装包不存在";
        if (apk.length() < 1024) return "安装包过小，可能下载失败";

        try {
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(
                    androidx.core.content.FileProvider.getUriForFile(
                            a, a.getPackageName() + ".updates", apk),
                    "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            /*
             * 先确认真的有人接这个 Intent。
             *
             * 踩过（MuMu）：startActivity 不抛异常、安装器进程也起来了，
             * 但没有任何 Activity 进栈——用户看到的是"点了没反应"。
             * 部分定制 ROM / 模拟器把 apk 安装器藏起来。这里显式判一下，
             * 把路径回给前端提示，让用户能自己去找那个文件。
             */
            android.content.pm.ResolveInfo ri =
                    a.getPackageManager().resolveActivity(it, 0);
            if (ri == null) {
                return "没有可用的安装器；安装包已保存到 " + apk.getAbsolutePath();
            }
            a.startActivity(it);
            return "ok";
        } catch (Throwable t) {
            return "安装失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "（" + t.getMessage() + "）");
        }
    }

    /** JS 轮询用：一行 JSON。 */
    public static String stateJson() {
        return "{\"state\":\"" + state + "\",\"percent\":" + percent.get()
                + ",\"received\":" + received + ",\"total\":" + total
                + ",\"message\":\"" + escape(message) + "\""
                + ",\"path\":\"" + escape(lastPath) + "\"}";
    }

    /**
     * 清理**下载缓存**（只删 `cacheDir/updates/` 里的文件），返回释放的字节数。
     *
     * <p><b>边界写死在这里</b>：这个方法的能力是"删文件"。一旦哪天有人把
     * 目标目录改成 `getCacheDir()` 或 `getFilesDir()` 本身，就会连海报副本
     * （`files/library/`）、片库数据一起删掉。所以显式确认目录名，
     * 改错了直接返回 0 而不是静默误删。
     *
     * <p>不删的东西：设置（SharedPreferences / localStorage）、
     * 海报副本（`files/library/`）、片库元数据、插件。
     */
    public static long clearCache(Activity a) {
        File dir = new File(a.getCacheDir(), "updates");
        // 只允许清名为 updates 的子目录
        if (!"updates".equals(dir.getName())) {
            message = "拒绝清理：目标不是下载缓存目录";
            return 0;
        }
        long freed = 0;
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                // 只删文件，不递归进子目录
                if (f.isFile()) {
                    freed += f.length();
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        reset();
        return freed;
    }

    // ---------------------------------------------------------------- 工具

    private static void fail(String msg) {
        state = "failed";
        message = msg;
        percent.set(0);
        busy = false;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    private static String shortSha(String s) {
        return s.length() > 12 ? s.substring(0, 12) + "…" : s;
    }

    /** 从 url / 文件名取一个安全的本地文件名（挡目录穿越与非 ASCII）。 */
    static String safeName(String hint, String url) {
        String raw = (hint == null || hint.trim().isEmpty()) ? baseName(url) : hint.trim();
        String base = baseName(raw);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length() && sb.length() < 80; i++) {
            char c = base.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            sb.append(ok ? c : '_');
        }
        // 去掉开头的点，避免 ".apk" 这种纯扩展名或隐藏文件
        while (sb.length() > 0 && sb.charAt(0) == '.') sb.deleteCharAt(0);
        String out = sb.toString();
        return out.isEmpty() ? "update.apk" : out;
    }

    private static String baseName(String s) {
        if (s == null) return "update.apk";
        String t = s;
        int q = t.indexOf('?');
        if (q >= 0) t = t.substring(0, q);
        int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        if (slash >= 0) t = t.substring(slash + 1);
        return t;
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
