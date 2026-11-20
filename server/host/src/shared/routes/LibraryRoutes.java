package routes;

import platform.Headers;
import platform.HttpExchange;

import core.Library;
import core.Transcode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;
import java.util.Map;

/**
 * 片库域路由：WebDAV / SMB / 本地目录的浏览与播放。
 *
 * <p><b>为什么能干净搬出</b>：真正的业务（PROPFIND、smbj 读文件、路径穿越防护）
 * 都在 {@code core.Library} 里，这里只做「解析参数 → 调 Library → 输出字节」。
 * 不碰站点注册表，也不碰鉴权（入口已统一处理）。
 *
 * <p><b>Range 请求必须精确处理</b>：视频拖动依赖 206 + Content-Range。
 * 早期这里踩过坑——把分片路径改写成相对地址导致播放器解析到源站 404，
 * 以及不支持 Range 时浏览器静默丢弃 seek 请求。这段逻辑原样保留，
 * 搬移只改调用前缀，不动语义。
 */
public final class LibraryRoutes {

    private LibraryRoutes() {
    }

    /**
     * 目录列举的隔离线程池：守护线程 + 可取消。
     *
     * <p>用独立池而不是公共池：远程挂载卡住时只废掉这个线程，不拖累其它请求；
     * 守护线程保证 JVM 退出不被它挂住。
     */
    private static final java.util.concurrent.ExecutorService LS_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "lib-ls");
                t.setDaemon(true);
                return t;
            });

    /**
     * 片库专用的 HTTP 客户端（WebDAV 的 PROPFIND / GET）。
     *
     * <p>单独一份而不是复用全局的：片库请求要发 PROPFIND 这种非标准方法，
     * 而且响应体常常是几十 MB 的文件流，超时策略与 API 调用完全不同。
     *
     * <p><b>为什么是 OkHttp 而不是 java.net.http</b>：后者在 android.jar 里
     * 完全不存在（实测 0 条），而本模块要跟 ApiServer 一起在 TV 端编译运行。
     * OkHttp 的 {@code Request.Builder.method(String, RequestBody)} 允许任意方法名，
     * PROPFIND/MKCOL 都能发；HttpURLConnection 会抛 ProtocolException。
     */
    private static final okhttp3.OkHttpClient LIBRARY_CLIENT =
            new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build();

    /**
     * WebDAV 代理。
     *
     * 浏览器直接对 WebDAV 服务器发 PUT/MKCOL 会被 CORS 挡死
     * （而且预检请求很多服务器不支持），所以统一由后端转发。
     *
     * POST /api/webdav
     *   body: {
     *     "url":  "http://192.168.1.10:5005/mysyc/meow/history.json",
     *     "user": "...", "pass": "...",
     *     "method": "PUT" | "GET" | "DELETE" | "MKCOL" | "PROPFIND",
     *     "body": "..."（PUT 时）
     *   }
     *   -> { "ok": true, "status": 201, "body": "..." }
     */
    public static void handleWebdav(HttpExchange ex) throws Exception {
        String raw = Api.read(ex.getRequestBody());
        JSONObject req = raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw);
        String url = req.optString("url", "");
        if (url.isEmpty()) {
            Api.sendJson(ex, 400, "{\"code\":-1,\"msg\":\"url required\"}");
            return;
        }
        String method = req.optString("method", "GET").toUpperCase();
        String user = req.optString("user", "");
        String pass = req.optString("pass", "");
        String payload = req.optString("body", "");

        /*
         * 公网加固（2026-11-19）：WebDAV 目标必须过 SSRF 校验，方法也要收窄。
         * method 藏在 body 里，GET 请求也能触发任意方法；不校验等于一条任意
         * 方法的 SSRF 隧道。宽松档允许局域网（自建 NAS 是真实场景），
         * 回环/链路本地（云元数据）仍拒绝。
         */
        if (!java.util.Set.of("GET", "PUT", "DELETE", "MKCOL", "PROPFIND", "MOVE")
                .contains(method)) {
            Api.sendJson(ex, 405, "{\"code\":-1,\"msg\":\"method not allowed\"}");
            return;
        }
        if (!core.UrlSafety.isSafeMediaUrl(url)) {
            Api.sendJson(ex, 403, "{\"code\":-1,\"msg\":\"url not allowed\"}");
            return;
        }

        // 必须用 OkHttp：HttpURLConnection 的 setRequestMethod 只允许
        // GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE，发 PROPFIND/MKCOL 会直接抛
        // ProtocolException: Invalid HTTP method，WebDAV 根本走不通。
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                .url(url)
                .method(method, payload.isEmpty()
                        ? null
                        : okhttp3.RequestBody.create(
                                okhttp3.MediaType.parse("application/octet-stream"), payload));

        if (!user.isEmpty() || !pass.isEmpty()) {
            String token = Base64.getEncoder()
                    .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
            rb.header("Authorization", "Basic " + token);
        }
        if ("PROPFIND".equals(method)) {
            // Depth 1 才会列出子项；Depth 0 只返回资源自身（之前固定 0，
            // 导致列目录永远只有根节点，误判成"没有共享文件夹"）
            String depth = req.optString("depth", "1");
            rb.header("Depth", depth);
            rb.header("Content-Type", "application/xml; charset=utf-8");
            if (payload.isEmpty()) {
                // 有些服务器要求 PROPFIND 带请求体
                rb.method(method, okhttp3.RequestBody.create(
                        okhttp3.MediaType.parse("application/xml; charset=utf-8"),
                        "<?xml version=\"1.0\"?>"
                        + "<propfind xmlns=\"DAV:\"><prop>"
                        + "<displayname/><resourcetype/><getcontentlength/><getlastmodified/><getcontenttype/>"
                        + "</prop></propfind>"));
            }
        } else if (!payload.isEmpty()) {
            rb.header("Content-Type", "application/json; charset=utf-8");
        }

        int code;
        String body = "";
        try (okhttp3.Response resp = LIBRARY_CLIENT.newBuilder()
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .build().newCall(rb.build()).execute()) {
            code = resp.code();
            okhttp3.ResponseBody b = resp.body();
            body = b == null ? "" : b.string();
        } catch (Exception e) {
            Api.sendJson(ex, 200, new JSONObject()
                    .put("ok", false)
                    .put("status", 0)
                    .put("body", "")
                    .put("msg", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .toString());
            return;
        }

        // 201/204 是 WebDAV 的正常结果
        boolean ok = code >= 200 && code < 300;
        Api.sendJson(ex, 200, new JSONObject()
                .put("ok", ok)
                .put("status", code)
                .put("body", body.length() > 200_000 ? body.substring(0, 200_000) : body)
                .toString());
    }
    /**
     * 本地 / NAS 片库。契约与 spider 同构，前端可以复用现有浏览链路。
     *
     * <p>GET  {@code /api/library}                     → 配置 + 每个库的顶层目录
     * <br>GET  {@code /api/library?lib=nas&path=电影}    → 列目录（dirs/files）
     * <br>GET  {@code /api/library/stream?lib=nas&path=电影/x.mp4} → 流（Range 透传）
     * <br>POST {@code /api/library}                     → 保存配置
     * <br>POST {@code /api/library?action=test}         → 测试连接
     *
     * <p><b>为什么 path 是「相对库根的路径」而不是绝对路径</b>：绝对路径就等价于
     * 任意文件读取。相对路径由服务端拼到用户配置的 base 上，穿越尝试会被 NAS 拒绝
     * （实测 {@code ../../../etc} 返回 400），也不会打到库外。
     */
    public static void handleLibrary(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        Map<String, String> q = Api.query(ex);

        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            String body = Api.read(ex.getRequestBody());
            if ("test".equalsIgnoreCase(q.getOrDefault("action", ""))) {
                JSONObject in = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
                JSONObject cfg = in.optJSONObject("lib");
                if (cfg == null) {
                    Api.sendJson(ex, 400, "{\"code\":-1,\"msg\":\"lib required\"}");
                    return;
                }
                try {
                    JSONObject r = core.Library.test(cfg);
                    Api.sendJson(ex, 200, r.toString());
                } catch (Exception e) {
                    Api.sendJson(ex, 200, new JSONObject().put("ok", false)
                            .put("msg", String.valueOf(e.getMessage())).toString());
                }
                return;
            }
            // 结构化结果：DUP_ROOT 这类是「用户输入问题」→ 400；写盘失败 → 500
            JSONObject r = core.Library.saveConfigEx(body);
            int status = r.optBoolean("ok", false) ? 200 : (r.has("code") ? 400 : 500);
            Api.sendJson(ex, status, r.toString());
            return;
        }

        /*
         * 目录树三端点（2026-11-20）。必须在 lib 分支**之前**判：
         * 放到后面会被"没指定库 → 返回配置"吃掉，弹窗永远只能拿到 libs。
         */
        String action = Api.trimToEmpty(q.get("action")).toLowerCase();
        if ("roots".equals(action)) {
            Api.sendJson(ex, 200, new JSONObject().put("ok", true)
                    .put("roots", core.LibraryRoots.all()).toString());
            return;
        }
        if ("capabilities".equals(action)) {
            Api.sendJson(ex, 200, core.LibraryRoots.capabilities().toString());
            return;
        }
        if ("ls".equals(action)) {
            String rootId = Api.trimToEmpty(q.get("root"));
            String rel = Api.trimToEmpty(q.get("path"));
            boolean withFiles = "1".equals(q.get("includeFiles"));
            if (rootId.isEmpty()) {
                Api.sendJson(ex, 400, "{\"code\":-1,\"msg\":\"root required\"}");
                return;
            }
            try {
                Api.sendJson(ex, 200, lsWithTimeout(rootId, rel, withFiles).toString());
            } catch (IllegalArgumentException e) {
                // 未知 root / 路径穿越 / 不可读：一律 400，不把异常栈甩给前端
                Api.sendJson(ex, 400, new JSONObject().put("code", -1)
                        .put("msg", String.valueOf(e.getMessage())).toString());
            } catch (Exception e) {
                Api.sendJson(ex, 500, new JSONObject().put("code", -1)
                        .put("msg", String.valueOf(e.getMessage())).toString());
            }
            return;
        }

        String libId = Api.trimToEmpty(q.get("lib"));
        String path = Api.trimToEmpty(q.get("path"));
        if (libId.isEmpty()) {
            // 没指定库：返回配置（抹掉密码）+ 每个库的顶层目录，供首页直接渲染分类
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("libs", core.Library.publicConfig());
            JSONArray tops = new JSONArray();
            JSONArray libs = core.Library.publicConfig();
            for (int i = 0; i < libs.length(); i++) {
                JSONObject l = libs.optJSONObject(i);
                if (l == null) continue;
                try {
                    JSONObject r = core.Library.list(l.optString("id"), "");
                    JSONObject o = new JSONObject();
                    o.put("id", l.optString("id"));
                    o.put("name", l.optString("name"));
                    o.put("dirs", r.optJSONArray("dirs"));
                    tops.put(o);
                } catch (Exception e) {
                    // 单个库连不上不能拖垮整个首页：标出错误让前端显示"该库不可用"
                    JSONObject o = new JSONObject();
                    o.put("id", l.optString("id"));
                    o.put("name", l.optString("name"));
                    o.put("error", String.valueOf(e.getMessage()));
                    tops.put(o);
                }
            }
            out.put("tops", tops);
            Api.sendJson(ex, 200, out.toString());
            return;
        }

        try {
            JSONObject r = core.Library.list(libId, path);
            // 带 scrape=1 时补元数据（海报/年份）。默认不刮：列目录要快，
            // 刮削是逐条查外网，几百个文件会把首屏拖到几十秒。
            if ("1".equals(q.getOrDefault("scrape", ""))) {
                r.put("scrape", true);
            }
            Api.sendJson(ex, 200, r.toString());
        } catch (Exception e) {
            Api.sendJson(ex, 200, new JSONObject().put("ok", false)
                    .put("msg", String.valueOf(e.getMessage())).toString());
        }
    }
    /**
     * 片库文件流。与 {@code /api/proxy/stream} 分开，因为它读的是
     * 「配置里的库 + 相对路径」，不需要也不应该走 SSRF 放行。
     *
     * <p>Range 必须原样转发：实测 NAS 对 10.6GB 的 mp4 返回 206 + 精确字节数，
     * 不转发 Range 会让 4K 单集（1.4–2GB）每次从头下载，拖动进度条直接失效。
     */
    /**
     * 目录列举加 3 秒上限。
     *
     * <p>授权路径可能是 rclone / 网盘挂载，卡住时 {@code File.listFiles()} 会一直阻塞；
     * 不设上限就把请求线程一起挂死，用户在弹窗里看到的是"永远转圈"。
     * 超时回一句人话，比无限等待有用。
     */
    private static JSONObject lsWithTimeout(String rootId, String rel, boolean withFiles)
            throws Exception {
        java.util.concurrent.Future<JSONObject> f = LS_POOL.submit(
                () -> core.LibraryRoots.ls(rootId, rel, withFiles));
        try {
            return f.get(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            throw new IllegalArgumentException("目录读取超时（网络盘/远程挂载可能较慢）");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IllegalArgumentException) throw (IllegalArgumentException) c;
            throw new Exception(c == null ? e : c);
        }
    }

    public static void handleLibraryStream(HttpExchange ex) throws IOException {
        Map<String, String> q = Api.query(ex);
        String libId = Api.trimToEmpty(q.get("lib"));
        String path = Api.trimToEmpty(q.get("path"));
        if (libId.isEmpty() || path.isEmpty()) {
            Api.sendJson(ex, 400, "{\"code\":-1,\"msg\":\"lib and path required\"}");
            return;
        }
        /*
         * ★ WebDAV 分支要在拼 URL 前挡掉 `..`（2026-10-30 安全审计 H13）。
         *
         * 本地/SMB 分支用的是 resolveLocalForStream / smbSubPathOf，各自有
         * 前缀校验；只有 WebDAV 是 `base + root + "/" + path` 拼成 URL，
         * 再交给 OkHttp —— 而 OkHttp 的 HttpUrl **会做 dot-segment 归一化**，
         * 于是 `path=../../secret.mp4` 变成 `base/secret.mp4`，越出配置的库根
         * （实测：配置 root=/movies，请求 ../../secret.mp4，目标服务器收到
         * GET /dav/secret.mp4）。库根之外的整个 WebDAV 目录因此可读。
         *
         * 判据放在这里而不是 joinUrl 里：这个参数只来自 URL query（用户可控），
         * 而 joinUrl 也被内部其它地方用来拼已知安全路径。
         */
        if (hasDotDotSegment(path)) {
            Api.sendJson(ex, 400, "{\"code\":-1,\"msg\":\"path must not contain '..'\"}");
            return;
        }
        JSONObject lib;
        try {
            lib = core.Library.findLib(libId);
        } catch (Exception e) {
            lib = null;
        }
        if (lib == null) {
            Api.sendJson(ex, 404, "{\"code\":-1,\"msg\":\"unknown library\"}");
            return;
        }
        // 本地目录与 SMB 不是 HTTP，走各自分支；WebDAV 继续走下面的 HttpClient
        String libType = lib.optString("type", "webdav");
        if ("local".equals(libType)) {
            sendLocalLibraryFile(ex, lib, path);
            return;
        }
        if ("smb".equals(libType)) {
            sendSmbLibraryFile(ex, lib, path);
            return;
        }
        String url;
        try {
            url = core.Library.joinUrl(lib.optString("base", ""),
                    lib.optString("root", "") + "/" + path);
        } catch (Exception e) {
            Api.sendJson(ex, 400, "{\"code\":-1,\"msg\":\"bad path\"}");
            return;
        }

        okhttp3.Request.Builder rb;
        try {
            rb = new okhttp3.Request.Builder().url(url).get();
        } catch (Exception e) {
            Api.sendJson(ex, 400, "{\"code\":-1,\"msg\":\"bad url\"}");
            return;
        }
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range != null && !range.isEmpty()) rb.header("Range", range);
        String user = lib.optString("user", "");
        String pass = lib.optString("pass", "");
        if (!user.isEmpty() || !pass.isEmpty()) {
            String token = Base64.getEncoder()
                    .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
            rb.header("Authorization", "Basic " + token);
        }

        okhttp3.Response resp;
        try {
            resp = LIBRARY_CLIENT.newBuilder()
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build().newCall(rb.build()).execute();
        } catch (Exception e) {
            Api.sendJson(ex, 502, "{\"code\":-1,\"msg\":" + JSONObject.quote(String.valueOf(e)) + "}");
            return;
        }
        int code = resp.code();
        Headers out = ex.getResponseHeaders();
        Api.cors(ex);
        out.set("Access-Control-Expose-Headers", "Content-Length,Content-Range,Accept-Ranges");
        out.set("Accept-Ranges", "bytes");
        String ct = resp.header("Content-Type");
        out.set("Content-Type", ct == null ? "application/octet-stream" : ct);
        String cr = resp.header("Content-Range");
        if (cr != null && !cr.isEmpty()) out.set("Content-Range", cr);
        long len = resp.body() == null ? -1L : resp.body().contentLength();

        InputStream in = resp.body() == null
                ? new java.io.ByteArrayInputStream(new byte[0]) : resp.body().byteStream();
        try {
            // len 必须原样用上游给的值（206 时是这一段的长度），不能传文件总长，
            // 否则 sendResponseHeaders 会因为长度不符把连接挂住。
            ex.sendResponseHeaders(code, len > 0 ? len : 0);
            OutputStream os = ex.getResponseBody();
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
            os.close();
        } catch (IOException ignored) {
            /* 客户端 seek 会主动断开旧连接，写失败是正常的 */
        } finally {
            try {
                in.close();
            } catch (Exception ignoredClose) {
            }
        }
    }
    /**
     * 本地目录里的文件。
     *
     * <p>Range 自己实现而不是交给 Files.copy：播放器拖动进度条会发
     * {@code Range: bytes=N-}，不返回 206 + Content-Range 的话，浏览器会把
     * 整个文件从头读一遍，seek 看起来"没反应"（不是报错，是静默失效）。
     *
     * <p>只支持单段 Range（{@code bytes=a-b}）。多段响应要 multipart/byteranges，
     * 复杂度高且播放器实际只会发单段。
     */
    public static void sendLocalLibraryFile(HttpExchange ex, JSONObject lib, String rel)
            throws IOException {
        java.nio.file.Path file;
        try {
            file = core.Library.resolveLocalForStream(lib, rel);
        } catch (Exception e) {
            Api.sendJson(ex, 403, "{\"code\":-1,\"msg\":" + JSONObject.quote(String.valueOf(e)) + "}");
            return;
        }
        if (!java.nio.file.Files.isRegularFile(file)) {
            Api.sendJson(ex, 404, "{\"code\":-1,\"msg\":\"not a file\"}");
            return;
        }
        long total;
        try {
            total = java.nio.file.Files.size(file);
        } catch (Exception e) {
            Api.sendJson(ex, 500, "{\"code\":-1,\"msg\":\"stat failed\"}");
            return;
        }
        Headers out = ex.getResponseHeaders();
        Api.cors(ex);
        out.set("Accept-Ranges", "bytes");
        out.set("Content-Type", core.Library.contentTypeOf(file.getFileName().toString()));

        long start = 0;
        long end = total - 1;
        String range = ex.getRequestHeaders().getFirst("Range");
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring(6).split(",")[0].trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                try {
                    String a = spec.substring(0, dash).trim();
                    String b = spec.substring(dash + 1).trim();
                    if (a.isEmpty()) {
                        // bytes=-N 表示最后 N 字节
                        long n = Long.parseLong(b);
                        start = Math.max(0, total - n);
                    } else {
                        start = Long.parseLong(a);
                        if (!b.isEmpty()) end = Math.min(Long.parseLong(b), total - 1);
                    }
                    if (start > end || start >= total) {
                        out.set("Content-Range", "bytes */" + total);
                        ex.sendResponseHeaders(416, -1);
                        ex.close();
                        return;
                    }
                    partial = true;
                } catch (NumberFormatException ignored) {
                    start = 0;
                    end = total - 1;
                    partial = false;
                }
            }
        }
        long len = end - start + 1;
        if (partial) out.set("Content-Range", "bytes " + start + "-" + end + "/" + total);
        try {
            ex.sendResponseHeaders(partial ? 206 : 200, len);
            OutputStream os = ex.getResponseBody();
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
                raf.seek(start);
                byte[] buf = new byte[1 << 20];
                long left = len;
                while (left > 0) {
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) break;
                    os.write(buf, 0, n);
                    left -= n;
                }
            }
            os.close();
        } catch (IOException ignored) {
            /* seek 会主动断开旧连接 */
        }
    }
    /**
     * SMB 共享里的文件。
     *
     * <p>smbj 的 `File.read(buffer, offset)` 支持随机读，直接按 Range 跳过去，
     * 不需要把整个文件读进内存。
     */
    // ------------------------------------------------------------ SMB 连接池（F4，2026-11-17）

    /**
     * 复用的 SMB 连接（client + conn + session + share）。
     *
     * <p><b>为什么要池化</b>：每个 Range 请求原来都要重新协商 + 认证 + 树连接
     * （局域网 10~30ms，WiFi 更高）。播放器一次起播会发好几个 Range（moov、
     * 首段、seek），转码后的 ffmpeg 更是持续发请求——握手开销直接摊在起播
     * 延迟与拖动响应上。池化后只在失效/空闲超时时重建。
     */
    private static final class SmbConn {
        com.hierynomus.smbj.SMBClient client;
        com.hierynomus.smbj.connection.Connection conn;
        com.hierynomus.smbj.session.Session session;
        com.hierynomus.smbj.share.DiskShare share;
        volatile long lastUsed;

        boolean alive() {
            try {
                return conn != null && conn.isConnected() && share != null && share.isConnected();
            } catch (Exception e) {
                return false;
            }
        }

        void close() {
            try {
                if (share != null) share.close();
            } catch (Exception ignored) {
            }
            try {
                if (session != null) session.close();
            } catch (Exception ignored) {
            }
            try {
                if (conn != null) conn.close();
            } catch (Exception ignored) {
            }
            try {
                if (client != null) client.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 池：libId@host → 连接。空闲 5 分钟回收（别在 NAS 上长期占会话）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, SmbConn> SMB_POOL =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SMB_IDLE_MS = 5 * 60_000L;

    private static String smbPoolKey(JSONObject lib) {
        return lib.optString("id", "") + "@" + core.Library.smbHostOf(lib);
    }

    /** 取一条可用连接（失效/过期就重建）。 */
    private static SmbConn smbConn(JSONObject lib) throws IOException {
        String k = smbPoolKey(lib);
        long now = System.currentTimeMillis();
        SmbConn c = SMB_POOL.get(k);
        if (c != null && c.alive() && now - c.lastUsed <= SMB_IDLE_MS) {
            c.lastUsed = now;
            return c;
        }
        if (c != null) {
            c.close();
            SMB_POOL.remove(k);
        }
        try {
            SmbConn n = new SmbConn();
            n.client = new com.hierynomus.smbj.SMBClient();
            n.conn = n.client.connect(core.Library.smbHostOf(lib));
            n.session = core.Library.authenticateSmb(n.conn, lib);
            n.share = (com.hierynomus.smbj.share.DiskShare)
                    n.session.connectShare(core.Library.smbShareOf(lib));
            n.lastUsed = now;
            SMB_POOL.put(k, n);
            return n;
        } catch (Exception e) {
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /** 打开共享文件（连接失效时重建一次再试）。 */
    private static com.hierynomus.smbj.share.File smbOpenFile(JSONObject lib, String path)
            throws IOException {
        java.util.EnumSet<com.hierynomus.msdtyp.AccessMask> access =
                java.util.EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ);
        java.util.EnumSet<com.hierynomus.mssmb2.SMB2ShareAccess> sharing =
                java.util.EnumSet.of(com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_READ);
        SmbConn c = smbConn(lib);
        try {
            // smbj 0.13 只有一个 openFile 重载，六个参数都得给：只读 + 允许别人同时读
            return c.share.openFile(path, access, null, sharing,
                    com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN, null);
        } catch (Exception first) {
            /*
             * 连接可能已被 NAS 侧断开（smbj 的 isConnected 有时滞后）：
             * 强制重建一次再试，避免一次抖动就让播放报 502。
             */
            String k = smbPoolKey(lib);
            SMB_POOL.remove(k);
            c.close();
            SmbConn n = smbConn(lib);
            try {
                return n.share.openFile(path, access, null, sharing,
                        com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN, null);
            } catch (Exception second) {
                throw second instanceof IOException ? (IOException) second : new IOException(second);
            }
        }
    }

    public static void sendSmbLibraryFile(HttpExchange ex, JSONObject lib, String rel)
            throws IOException {
        String path = core.Library.smbSubPathOf(lib, rel);
        com.hierynomus.smbj.share.File f = null;
        /*
         * S3（2026-11-17）：记录响应头是否已发出。
         *
         * 原来读流中途失败时 catch 里还会再 sendJson(502) —— 头已发时这行必然
         * 抛 "headers already sent"，把真实的 SMB 断连/超时异常盖掉，日志里
         * 只剩误导性的二次错误（OECT 上排障多花的时间就耗在这）。
         */
        boolean headersSent = false;
        try {
            f = smbOpenFile(lib, path);
            long total = f.getFileInformation().getStandardInformation().getEndOfFile();
            Headers out = ex.getResponseHeaders();
            Api.cors(ex);
            out.set("Accept-Ranges", "bytes");
            out.set("Content-Type", core.Library.contentTypeOf(path));

            long start = 0;
            long end = total - 1;
            String range = ex.getRequestHeaders().getFirst("Range");
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String spec = range.substring(6).split(",")[0].trim();
                int dash = spec.indexOf('-');
                if (dash >= 0) {
                    try {
                        String a = spec.substring(0, dash).trim();
                        String b = spec.substring(dash + 1).trim();
                        if (a.isEmpty()) {
                            start = Math.max(0, total - Long.parseLong(b));
                        } else {
                            start = Long.parseLong(a);
                            if (!b.isEmpty()) end = Math.min(Long.parseLong(b), total - 1);
                        }
                        if (start > end || start >= total) {
                            out.set("Content-Range", "bytes */" + total);
                            ex.sendResponseHeaders(416, -1);
                            ex.close();
                            return;
                        }
                        partial = true;
                    } catch (NumberFormatException ignored) {
                        start = 0;
                        end = total - 1;
                        partial = false;
                    }
                }
            }
            long len = end - start + 1;
            if (partial) out.set("Content-Range", "bytes " + start + "-" + end + "/" + total);
            ex.sendResponseHeaders(partial ? 206 : 200, len);
            headersSent = true;
            OutputStream os = ex.getResponseBody();
            /*
             * F3（2026-11-17）：读缓冲 64KB → 1MB。
             * 实测 OECT 直连吞吐只有 ~11-12MB/s，而 SMB2 单次 read 上限远大于
             * 64KB —— 小包往返是主要开销。16 个并发线程 × 1MB 约 16MB 内存。
             */
            byte[] buf = new byte[1 << 20];
            long pos = start;
            long left = len;
            while (left > 0) {
                int want = (int) Math.min(buf.length, left);
                int n = f.read(buf, pos, 0, want);
                if (n <= 0) break;
                os.write(buf, 0, n);
                pos += n;
                left -= n;
            }
            os.close();
        } catch (Exception e) {
            if (headersSent) {
                // 头已发：只能断连（客户端会按 Range 重试），但日志里必须是真实异常
                System.err.println("[library] smb stream aborted (headers sent): " + e
                        + " | lib=" + lib.optString("id", "") + " path=" + rel);
                try {
                    ex.close();
                } catch (Exception ignored) {
                }
            } else {
                System.err.println("[library] smb open failed: " + e
                        + " | lib=" + lib.optString("id", "") + " path=" + rel);
                Api.sendJson(ex, 502, "{\"code\":-1,\"msg\":" + JSONObject.quote(String.valueOf(e)) + "}");
            }
        } finally {
            if (f != null) {
                try {
                    f.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
    /**
     * 片库文件的转码（本地目录 / SMB）。
     *
     * <p><b>为什么单独一条路</b>：正常转码把 URL 交给 ffmpeg 自己去拉，
     * 但片库有两种情况 ffmpeg 都搞不定：
     * <ul>
     *   <li>本地目录 —— 通过 {@code /api/library/stream} 是 127.0.0.1，
     *       被 SSRF 防线拒（那条防线不能为它松口）；</li>
     *   <li>SMB —— ffmpeg 没有 SMB 协议支持，{@code smb://} 直接读不了。</li>
     * </ul>
     * 所以这里由后端取文件：本地直接给绝对路径（零拷贝），SMB 先落到临时文件。
     *
     * <p>临时文件用 {@code <id>.src} 命名，与转码产物同目录，取消/删除时一起清。
     */
    public static void handleLibraryTranscode(HttpExchange ex, Map<String, String> q,
                                               String libId, String libPath) throws Exception {
        JSONObject lib;
        try {
            lib = core.Library.findLib(libId);
        } catch (Exception e) {
            lib = null;
        }
        if (lib == null) {
            Api.sendJson(ex, 404, "{\"code\":-1,\"msg\":\"unknown library\"}");
            return;
        }
        // 幂等键用 lib|path 而不是临时文件路径：临时文件会被清，键要稳定
        String key = "lib|" + libId + "|" + libPath;
        /*
         * ★ status 轮询必须排在「取源」之前（S1，2026-11-17 复查发现）。
         *
         * 原来它排在 stage 分支后面：前端每 2 秒轮询一次状态，每次都会再走
         * 一遍 stageSmbSource —— 首次拷贝期间多个轮询**并发写同一个 .part**，
         * 把源文件写坏（比"客户端超时后重试"更常见，几乎是必现）。
         * 现在轮询只读内存状态，完全不碰源。
         */
        if ("status".equalsIgnoreCase(q.getOrDefault("mode", ""))) {
            core.Transcode.Job j = core.Transcode.status(core.Transcode.idFor(key));
            Api.sendJson(ex, 200, j == null
                    ? new JSONObject().put("ok", true).put("state", "idle").toString()
                    : core.Transcode.toJson(j).toString());
            return;
        }
        /*
         * 先确认 ffmpeg 可用再取源（2026-11-17 OECT 实测）：SMB 分支原来要把
         * 整个文件拷到临时目录才轮到 ffmpeg —— 2.3GB 白拷两分钟，最后才报
         * 「找不到 ffmpeg」。探测失败直接 502，让前端立刻拿到可读的原因。
         */
        if (core.Transcode.ffmpegPath().isEmpty()) {
            Api.sendJson(ex, 502,
                    "{\"code\":-1,\"msg\":\"找不到 ffmpeg（policy.json 或 PATH 均未配置），无法转码播放\"}");
            return;
        }
        String type = lib.optString("type", "webdav");
        String source;
        if ("local".equals(type)) {
            try {
                source = core.Library.resolveLocalForStream(lib, libPath).toString();
            } catch (Exception e) {
                Api.sendJson(ex, 403, "{\"code\":-1,\"msg\":" + JSONObject.quote(String.valueOf(e)) + "}");
                return;
            }
        } else if ("smb".equals(type)) {
            /*
             * ★ F2（2026-11-17）：不再把整个文件拷到临时目录，直接把**内部回环流
             * 地址**交给 ffmpeg。stream 端点支持 Range，ffmpeg 能按需 seek
             * （mp4 的 moov 在尾部也会先跳过去读）。
             *
             * OECT 实测：4K MKV 2.39GB 原来先拷 ~2 分钟才开始转，首片要等 2 分钟+；
             * 现在首片只受转码速度限制（目标 20~60 秒）。
             *
             * 拿不到端口（异常环境）时回退旧的 stage 拷贝，保证可用性。
             */
            int p = core.Transcode.apiPort;
            if (p > 0) {
                source = "http://127.0.0.1:" + p + "/api/library/stream?lib="
                        + urlEnc(libId) + "&path=" + urlEnc(libPath);
            } else {
                try {
                    source = core.Transcode.stageSmbSource(libId, libPath, lib);
                } catch (Exception e) {
                    Api.sendJson(ex, 502, "{\"code\":-1,\"msg\":" + JSONObject.quote(String.valueOf(e)) + "}");
                    return;
                }
            }
        } else {
            Api.sendJson(ex, 400,
                    "{\"code\":-1,\"msg\":\"WebDAV 片库请直接用 src 传流地址\"}");
            return;
        }
        core.Transcode.Job j = core.Transcode.ensureLocal(source, key);
        Api.sendJson(ex, 200, core.Transcode.toJson(j).toString());
    }

    /** URL query 编码（空格必须 %20，URLEncoder 默认给的是 +）。 */
    private static String urlEnc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * path 里有没有 `..` 段（含 `%2e%2e` 已解码后的形态）。
     *
     * <p>只查"整段等于 .."，不查"包含 .."：像 `S01E01..E02.mp4` 这种
     * 合法文件名不能误伤。
     */
    private static boolean hasDotDotSegment(String path) {
        if (path == null || path.isEmpty()) return false;
        for (String seg : path.split("[/\\\\]")) {
            if ("..".equals(seg.trim())) return true;
        }
        // OkHttp 也会把 %2e%2e 归一化，这里一并挡掉
        String lower = path.toLowerCase();
        return lower.contains("%2e%2e") || lower.contains("%2e.");
    }
}
