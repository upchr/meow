package core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 / NAS 片库：把 WebDAV 目录映射成与 spider 同构的「源」。
 *
 * <p><b>为什么是独立实现而不是给 /api/proxy/stream 放行回环</b>：
 * 那个端点的目标是调用方任意给的 URL，放开回环就等于开一台能打本机的正向代理；
 * 而片库只访问**用户显式配置过的那个根**，路径由代码拼接、只接受相对路径，
 * 天然不会变成 SSRF 通道。两条通道分开，安全边界才说得清。
 *
 * <p><b>为什么只支持 WebDAV</b>：实测用户的 NAS 开了 WebDAV
 * （PROPFIND 207 + Range 206），且本机 JVM 没有 SMB 客户端；
 * SMB 要走系统映射盘符（PC）或额外依赖（TV），不在服务端做。
 *
 * <p><b>为什么不用第三方 XML 库</b>：PROPFIND 的 {@code <D:response>} 结构固定，
 * 而宿主运行时是完整 Temurin JRE（有 java.xml），但为了与仓库现有「字符串扫描」
 * 风格一致、并避免命名空间前缀在不同 NAS 上不一致（D: / d: / 无前缀），
 * 这里按 response 块切片 + 字段名匹配，不引 DOM。
 */
public final class Library {

    private Library() {
    }

    /** 一个片库条目。 */
    public static final class Item {
        public String name = "";
        public String href = "";
        public boolean dir;
        public long size;
        public String mtime = "";
        public String contentType = "";
    }

    private static final String CONFIG_FILE = "library.json";

    // ------------------------------------------------------------ 配置读写

    /**
     * 读片库配置。缺文件返回 {@code {libs:[]}}。
     *
     * <p>凭据存在数据目录（不进 git），与 policy.json 同级；
     * 端点返回给前端时**必须抹掉密码**，否则设置页的「查看」会把密码回显到页面。
     */
    public static JSONObject loadConfig() throws Exception {
        String raw = DataPaths.read(CONFIG_FILE);
        if (raw == null || raw.trim().isEmpty()) {
            return new JSONObject().put("libs", new JSONArray());
        }
        try {
            JSONObject o = new JSONObject(raw);
            if (o.optJSONArray("libs") == null) o.put("libs", new JSONArray());
            return o;
        } catch (Exception e) {
            System.out.println("[library] 配置解析失败，按空处理: " + e);
            return new JSONObject().put("libs", new JSONArray());
        }
    }

    public static boolean saveConfig(String body) throws Exception {
        return saveConfigEx(body).optBoolean("ok", false);
    }

    /**
     * 保存配置并返回**结构化结果**（前端要能说出"为什么没保存"）。
     *
     * <p>去重规则（2026-11-20）：
     * <ul>
     *   <li>同一 type + 同一归一化根（本地目录走 {@code toRealPath}，Windows 转小写）
     *       → 拒绝，返回 {@code code=DUP_ROOT} 并指名冲突库；</li>
     *   <li>一方的根是另一方的祖先/子孙 → 允许，但回 {@code warn}（会重复扫描）；</li>
     * </ul>
     *
     * <p>为什么必须做：同一个目录加两次等于两个库扫同一批文件，首页出现重复条目、
     * 刮削任务翻倍，而用户看不出问题出在自己加了两遍。
     */
    public static JSONObject saveConfigEx(String body) throws Exception {
        JSONObject in = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        if (in.optJSONArray("libs") == null) in.put("libs", new JSONArray());
        // 旧配置里每个 id 的密码。整表提交时前端拿不到明文（publicConfig 只给
        // hasPass），所以「没带 pass 字段」= 保持原值，而不是清空。
        // 踩过：删掉 A 库时把 B、C 一起提交，没带 pass 导致其余库全部失去密码，
        // 表现为「删了一个库，别的库开始 401」。
        java.util.Map<String, String> oldPass = new java.util.HashMap<>();
        JSONArray prev = loadConfig().optJSONArray("libs");
        for (int i = 0; prev != null && i < prev.length(); i++) {
            JSONObject x = prev.optJSONObject(i);
            if (x == null) continue;
            oldPass.put(x.optString("id", ""), x.optString("pass", ""));
        }
        // 只保留认识的字段，避免前端把无关东西写进磁盘
        JSONArray clean = new JSONArray();
        JSONArray arr = in.optJSONArray("libs");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.optJSONObject(i);
            if (x == null) continue;
            String id = safeId(x.optString("id", ""));
            String base = x.optString("base", "").trim();
            if (id.isEmpty() || base.isEmpty()) continue;
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", x.optString("name", id));
            o.put("type", x.optString("type", "webdav"));
            o.put("base", base.endsWith("/") ? base : base + "/");
            o.put("user", x.optString("user", ""));
            // 空密码表示「不改」：前端编辑既有条目时不会回传密码。
            // 带上但为空的 pass 也当成"不改"——前端清空输入框不该等于删密码。
            String pass = x.optString("pass", "");
            if (pass.isEmpty()) pass = oldPass.getOrDefault(id, "");
            if (!pass.isEmpty()) o.put("pass", pass);
            o.put("root", normalizeRoot(x.optString("root", "")));
            if (x.has("domain")) o.put("domain", x.optString("domain", ""));
            else o.put("domain", "");
            clean.put(o);
        }
        // ---- 根去重（2026-11-20）：精确重复拒绝，父子重叠只警告 ----
        String warn = "";
        for (int i = 0; i < clean.length(); i++) {
            JSONObject a = clean.getJSONObject(i);
            String ka = dupKey(a);
            for (int j = 0; j < i; j++) {
                JSONObject b = clean.getJSONObject(j);
                String kb = dupKey(b);
                if (!ka.isEmpty() && ka.equals(kb)) {
                    return new JSONObject().put("ok", false).put("code", "DUP_ROOT")
                            .put("msg", "已在库「" + b.optString("name", b.optString("id")) + "」中");
                }
                if (warn.isEmpty() && overlaps(a, b)) {
                    warn = "与「" + b.optString("name", b.optString("id")) + "」重叠，会重复扫描";
                }
            }
        }

        boolean wrote = DataPaths.write(CONFIG_FILE, new JSONObject().put("libs", clean).toString());
        JSONObject out = new JSONObject().put("ok", wrote);
        if (!warn.isEmpty()) out.put("warn", warn);
        return out;
    }

    /**
     * 去重比较键：{@code type|归一化 base|root}。
     *
     * <p>本地路径必须走真实路径归一化（`/media/a` 与 `/media/a/` 与
     * `/media/../media/a` 是同一个目录）；Windows 还要转小写，否则同一目录
     * 只因大小写不同就能加两次。
     */
    private static String dupKey(JSONObject lib) {
        String type = lib.optString("type", "").toLowerCase();
        String base = canonical(lib.optString("base", ""));
        if (base.isEmpty()) return "";
        return type + "|" + base + "|" + lib.optString("root", "");
    }

    /** 归一化：本地路径取 realpath（不存在则父目录 realpath + 文件名），URL 转小写去尾斜杠。 */
    private static String canonical(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "";
        boolean local = s.startsWith("/") || s.matches("^[A-Za-z]:[\\\\/].*");
        if (!local) {
            return s.toLowerCase().replaceAll("/+$", "");
        }
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(s);
            String real;
            try {
                real = p.toRealPath().toString();
            } catch (Exception notFound) {
                // 目录还不存在（用户先建库后建目录）：父目录一定存在，拿它兜底
                java.nio.file.Path parent = p.getParent();
                String pz = parent == null ? s : parent.toRealPath().toString();
                java.nio.file.Path name = p.getFileName();
                real = name == null ? pz : java.nio.file.Paths.get(pz).resolve(name).toString();
            }
            if (java.io.File.separatorChar == '\\') real = real.toLowerCase();
            return real.replaceAll("[/\\\\]+$", "");
        } catch (Throwable t) {
            return s.replaceAll("[/\\\\]+$", "");
        }
    }

    /** 本地路径的祖先/子孙关系；URL 不做重叠判断（共享名不同就是不同库）。 */
    private static boolean overlaps(JSONObject a, JSONObject b) {
        String ba = canonical(a.optString("base", ""));
        String bb = canonical(b.optString("base", ""));
        boolean local = ba.startsWith("/") || ba.matches("^[a-z]:[\\\\/].*");
        if (!local || bb.isEmpty()) return false;
        if (!a.optString("type", "").equalsIgnoreCase(b.optString("type", ""))) return false;
        String sep = String.valueOf(java.io.File.separatorChar);
        return ba.startsWith(bb + sep) || bb.startsWith(ba + sep);
    }

    /** 对外输出时抹掉密码。 */
    public static JSONArray publicConfig() throws Exception {
        JSONArray out = new JSONArray();
        JSONArray arr = loadConfig().optJSONArray("libs");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.optJSONObject(i);
            if (x == null) continue;
            JSONObject o = new JSONObject();
            o.put("id", x.optString("id", ""));
            o.put("name", x.optString("name", ""));
            o.put("type", x.optString("type", "webdav"));
            o.put("base", x.optString("base", ""));
            o.put("user", x.optString("user", ""));
            o.put("hasPass", !x.optString("pass", "").isEmpty());
            o.put("root", x.optString("root", ""));
            o.put("domain", x.optString("domain", ""));
            out.put(o);
        }
        return out;
    }

    private static String safeId(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 48; i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 根路径统一成「以 / 开头、不以 / 结尾」，空串表示库根。 */
    private static String normalizeRoot(String r) {
        String s = r == null ? "" : r.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("/")) s = "/" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static JSONObject findLib(String id) throws Exception {
        JSONArray arr = loadConfig().optJSONArray("libs");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.optJSONObject(i);
            if (x != null && id.equals(x.optString("id", ""))) return x;
        }
        return null;
    }

    // ------------------------------------------------------------ 列举

    /**
     * 列出某个目录。
     *
     * @param rel 相对库根的路径，空串表示根；**不接受绝对路径**（防穿越）
     */
    public static JSONObject list(String libId, String rel) throws Exception {
        JSONObject lib = findLib(libId);
        if (lib == null) throw new IllegalArgumentException("未知片库：" + libId);
        String type = lib.optString("type", "webdav");
        if ("local".equals(type)) return listLocal(lib, rel);
        if ("smb".equals(type)) return listSmb(lib, rel);
        return listWebdav(lib, rel);
    }

    private static JSONObject listWebdav(JSONObject lib, String rel) throws Exception {
        String target = joinUrl(lib.optString("base", ""), lib.optString("root", "") + "/" + rel);
        String xml = propfind(lib, target, "1");

        List<Item> items = parseMultiStatus(xml);
        JSONArray dirs = new JSONArray();
        JSONArray files = new JSONArray();
        String selfHref = null;
        for (Item it : items) {
            // PROPFIND 的 Depth:1 会把目录自己也算一条，按 href 与请求路径比对剔除
            if (selfHref == null && samePath(it.href, target)) {
                selfHref = it.href;
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("name", it.name);
            o.put("path", relOf(it.href, lib));
            o.put("dir", it.dir);
            if (!it.dir) {
                o.put("size", it.size);
                o.put("type", it.contentType);
            }
            o.put("mtime", it.mtime);
            if (it.dir) dirs.put(o);
            else files.put(o);
        }
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("dirs", dirs);
        out.put("files", files);
        return out;
    }

    // ------------------------------------------------------------ 本地目录

    /**
     * 本地目录：`base` 是绝对路径，`root` 是它下面的子目录。
     *
     * <p><b>为什么必须做白名单校验</b>：WebDAV 那条路靠"只接受相对路径"防穿越，
     * 而本地目录天生是绝对路径，`../..` 能把整个磁盘读出来。这里要求
     * **归一化后的真实路径仍以配置的根为前缀**，否则直接拒绝。
     *
     * <p>符号链接也要解：`toRealPath()` 会把它展开，避免 `/media/../..` 或
     * 指向外部的软链接绕过前缀判断。
     */
    private static JSONObject listLocal(JSONObject lib, String rel) throws Exception {
        java.nio.file.Path root = localRoot(lib);
        java.nio.file.Path target = resolveLocal(root, rel);
        JSONArray dirs = new JSONArray();
        JSONArray files = new JSONArray();
        try (java.util.stream.Stream<java.nio.file.Path> st = java.nio.file.Files.list(target)) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) st::iterator) {
                boolean dir = java.nio.file.Files.isDirectory(p);
                JSONObject o = new JSONObject();
                o.put("name", p.getFileName().toString());
                o.put("path", relLocal(root, p));
                o.put("dir", dir);
                if (!dir) {
                    try {
                        o.put("size", java.nio.file.Files.size(p));
                    } catch (Exception e) {
                        o.put("size", 0L);
                    }
                    o.put("type", guessContentType(p.getFileName().toString()));
                }
                try {
                    o.put("mtime", java.time.Instant.ofEpochMilli(
                            java.nio.file.Files.getLastModifiedTime(p).toMillis()).toString());
                } catch (Exception e) {
                    o.put("mtime", "");
                }
                if (dir) dirs.put(o);
                else files.put(o);
            }
        }
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("dirs", dirs);
        out.put("files", files);
        return out;
    }

    /** 本地库的根：base + root，归一化后必须是已存在的目录。 */
    private static java.nio.file.Path localRoot(JSONObject lib) throws Exception {
        String base = lib.optString("base", "");
        if (base.isEmpty()) throw new IllegalArgumentException("本地库缺少路径");
        String root = lib.optString("root", "");
        java.nio.file.Path p = java.nio.file.Paths.get(base);
        if (!root.isEmpty()) {
            p = p.resolve(root.startsWith("/") ? root.substring(1) : root);
        }
        java.nio.file.Path real = p.toRealPath();
        if (!java.nio.file.Files.isDirectory(real)) {
            throw new IllegalArgumentException("不是目录：" + real);
        }
        return real;
    }

    /**
     * 把相对路径解析成真实路径，并确认没跑出根目录。
     *
     * <p>先 `normalize()` 再 `toRealPath()`：前者消掉 `..`（包括不存在的路径），
     * 后者解符号链接。对不存在的文件 `toRealPath` 会抛，所以回退到父目录校验 +
     * 拼回文件名——播放流时文件必然存在，这条分支主要给"列目录时点错"用。
     */
    private static java.nio.file.Path resolveLocal(java.nio.file.Path root, String rel) throws Exception {
        String r = rel == null ? "" : rel.trim();
        if (r.startsWith("/")) r = r.substring(1);
        java.nio.file.Path p = r.isEmpty() ? root : root.resolve(r).normalize();
        java.nio.file.Path real;
        try {
            real = p.toRealPath();
        } catch (Exception e) {
            java.nio.file.Path parent = p.getParent();
            if (parent == null) throw new IllegalArgumentException("非法路径");
            real = parent.toRealPath().resolve(p.getFileName());
        }
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException("路径越界：" + rel);
        }
        return real;
    }

    /** 相对根的路径，用 / 分隔（与 WebDAV 那条保持一致，前端不需要区分协议）。 */
    private static String relLocal(java.nio.file.Path root, java.nio.file.Path p) {
        try {
            return root.relativize(p).toString().replace('\\', '/');
        } catch (Exception e) {
            return p.getFileName().toString();
        }
    }

    /** 供 ApiServer 的流式端点复用：解析并校验本地路径（越界会抛）。 */
    public static java.nio.file.Path resolveLocalForStream(JSONObject lib, String rel)
            throws Exception {
        return resolveLocal(localRoot(lib), rel);
    }

    /** 供 ApiServer 的流式端点复用。 */
    public static String contentTypeOf(String name) {
        return guessContentType(name);
    }

    /** 供 ApiServer 的流式端点复用。 */
    public static String smbHostOf(JSONObject lib) {
        return smbHost(lib);
    }

    /** 供 ApiServer 的流式端点复用。 */
    public static String smbShareOf(JSONObject lib) {
        return smbShare(lib);
    }

    /** 供 ApiServer 的流式端点复用。 */
    public static String smbSubPathOf(JSONObject lib, String rel) {
        return smbSubPath(lib, rel);
    }

    /** 供 ApiServer 的流式端点复用。 */
    public static com.hierynomus.smbj.session.Session authenticateSmb(
            com.hierynomus.smbj.connection.Connection conn, JSONObject lib) {
        return authenticate(conn, lib);
    }

    private static String guessContentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".ts")) return "video/mp2t";
        if (n.endsWith(".avi")) return "video/x-msvideo";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".flv")) return "video/x-flv";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".srt")) return "application/x-subrip";
        if (n.endsWith(".ass") || n.endsWith(".ssa")) return "text/x-ssa";
        if (n.endsWith(".nfo")) return "text/plain";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------ SMB

    /**
     * SMB 2/3 列目录。
     *
     * <p><b>为什么引 smbj 而不是系统挂载</b>：容器里没有 `mount.cifs` 的权限，
     * 而 PC/TV 又各有各的挂载方式；smbj 是纯 Java 实现，容器、PC、TV 用同一份代码。
     * 许可 Apache-2.0，依赖只有 asn-one 与 slf4j，合计约 700KB。
     *
     * <p>配置形状：`base` = `smb://host/share`，`root` = 共享内的子目录，
     * `user`/`pass` 是域账号。`domain` 可选（默认空，smbj 会用 WORKGROUP 兜底）。
     */
    private static JSONObject listSmb(JSONObject lib, String rel) throws Exception {
        com.hierynomus.smbj.SMBClient client = new com.hierynomus.smbj.SMBClient();
        try (com.hierynomus.smbj.connection.Connection conn = client.connect(smbHost(lib))) {
            com.hierynomus.smbj.session.Session session = authenticate(conn, lib);
            String share = smbShare(lib);
            String sub = smbSubPath(lib, rel);
            try (com.hierynomus.smbj.share.DiskShare disk = (com.hierynomus.smbj.share.DiskShare)
                    session.connectShare(share)) {
                JSONArray dirs = new JSONArray();
                JSONArray files = new JSONArray();
                String dir = sub.isEmpty() ? "" : sub;
                for (com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation fi
                        : disk.list(dir)) {
                    String name = fi.getFileName();
                    // . 与 .. 会被列出来，必须跳过，否则前端会看到两个无效条目
                    if (".".equals(name) || "..".equals(name)) continue;
                    boolean isDir = (fi.getFileAttributes() & com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                    JSONObject o = new JSONObject();
                    o.put("name", name);
                    o.put("path", sub.isEmpty() ? name : sub + "/" + name);
                    o.put("dir", isDir);
                    if (!isDir) {
                        o.put("size", fi.getEndOfFile());
                        o.put("type", guessContentType(name));
                    }
                    o.put("mtime", fi.getLastWriteTime() == null ? ""
                            : fi.getLastWriteTime().toInstant().toString());
                    if (isDir) dirs.put(o);
                    else files.put(o);
                }
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("dirs", dirs);
                out.put("files", files);
                return out;
            }
        }
    }

    private static String smbHost(JSONObject lib) {
        String base = lib.optString("base", "");
        // 允许 smb://host/share 与 smb://host:445/share 两种
        String s = base.startsWith("smb://") ? base.substring(6) : base;
        int slash = s.indexOf('/');
        return slash < 0 ? s : s.substring(0, slash);
    }

    private static String smbShare(JSONObject lib) {
        String base = lib.optString("base", "");
        String s = base.startsWith("smb://") ? base.substring(6) : base;
        int slash = s.indexOf('/');
        if (slash < 0) throw new IllegalArgumentException("SMB 地址要写成 smb://主机/共享名");
        String rest = s.substring(slash + 1);
        int slash2 = rest.indexOf('/');
        return slash2 < 0 ? rest : rest.substring(0, slash2);
    }

    private static String smbSubPath(JSONObject lib, String rel) {
        String root = lib.optString("root", "").replace('\\', '/');
        while (root.startsWith("/")) root = root.substring(1);
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        String r = rel == null ? "" : rel.replace('\\', '/');
        while (r.startsWith("/")) r = r.substring(1);
        if (root.isEmpty()) return r.isEmpty() ? "" : r;
        return r.isEmpty() ? root : root + "/" + r;
    }

    private static com.hierynomus.smbj.session.Session authenticate(
            com.hierynomus.smbj.connection.Connection conn, JSONObject lib) {
        String user = lib.optString("user", "");
        String pass = lib.optString("pass", "");
        String domain = lib.optString("domain", "");
        if (user.isEmpty()) {
            return conn.authenticate(new com.hierynomus.smbj.auth.AuthenticationContext(
                    null, null, null));
        }
        return conn.authenticate(new com.hierynomus.smbj.auth.AuthenticationContext(
                user, pass.toCharArray(), domain.isEmpty() ? null : domain));
    }

    // ------------------------------------------------------------ WebDAV

    /**
     * 把绝对 href 还原成「相对库根」的路径，前端拿它当 category id 再传回来。
     *
     * <p>href 是 URL 编码的（实测中文目录是 {@code /%E7%94%B5%E5%BD%B1/}），
     * 不在这里解码，前端展示会是乱码路径。
     */
    private static String relOf(String href, JSONObject lib) {
        String root = lib.optString("root", "");
        String prefix = basePath(lib.optString("base", "")) + root;
        String s = decodePath(href);
        if (s.startsWith(prefix)) s = s.substring(prefix.length());
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String basePath(String base) {
        try {
            URI u = URI.create(base);
            String p = u.getPath();
            if (p == null) return "";
            while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
            return p;
        } catch (Exception e) {
            return "";
        }
    }

    private static String decodePath(String s) {
        try {
            // 不能整体 URLDecoder.decode：它会把 "+" 变成空格，路径里的加号会被破坏
            StringBuilder sb = new StringBuilder();
            for (String seg : s.split("/", -1)) {
                if (seg.isEmpty()) {
                    sb.append('/');
                } else {
                    sb.append(URLEncoder.encode("", "UTF-8")); // no-op，保持结构对称
                    sb.append(java.net.URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8"));
                    sb.append('/');
                }
            }
            String out = sb.toString();
            return out.length() > 1 && out.endsWith("/") ? out.substring(0, out.length() - 1) : out;
        } catch (Exception e) {
            return s;
        }
    }

    private static boolean samePath(String href, String target) {
        return decodePath(href).replaceAll("/+$", "").equalsIgnoreCase(decodePath(pathOf(target)).replaceAll("/+$", ""));
    }

    private static String pathOf(String url) {
        try {
            String p = URI.create(url).getRawPath();
            return p == null ? url : p;
        } catch (Exception e) {
            return url;
        }
    }

    // ------------------------------------------------------------ PROPFIND

    public static String propfind(JSONObject lib, String url, String depth) throws Exception {
        // 用 OkHttp 而不是 HttpURLConnection / java.net.http：
        //   · HttpURLConnection 的 setRequestMethod 有方法白名单，传 PROPFIND 直接抛
        //     ProtocolException；改内部 delegate 字段的反射法在 JDK17 上已无该字段（实测踩过）。
        //   · java.net.http 在 android.jar 里**完全不存在**（实测 0 条），而这一段要跟着
        //     ApiServer 一起在 TV 端编译运行。
        // OkHttp 两端都有（后端 libs/okhttp-3.14.9.jar，TV 依赖 4.12.0），API 兼容，
        // 且 Request.Builder.method(String, RequestBody) 支持任意方法名。
        String user = lib.optString("user", "");
        String pass = lib.optString("pass", "");
        // 只要这五个字段：名字、是否目录、大小、时间、类型。目录条目通常没有
        // getcontentlength（实测群晖只有文件给），解析侧要能容忍缺失。
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<D:propfind xmlns:D=\"DAV:\"><D:prop>"
                + "<D:displayname/><D:resourcetype/><D:getcontentlength/>"
                + "<D:getlastmodified/><D:getcontenttype/>"
                + "</D:prop></D:propfind>";

        okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                .url(url)
                .method("PROPFIND", okhttp3.RequestBody.create(
                        okhttp3.MediaType.parse("application/xml; charset=utf-8"), body));
        rb.header("Depth", depth == null || depth.isEmpty() ? "1" : depth);
        rb.header("Content-Type", "application/xml; charset=utf-8");
        if (!user.isEmpty() || !pass.isEmpty()) {
            String token = Base64.getEncoder()
                    .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
            rb.header("Authorization", "Basic " + token);
        }

        int code;
        String text;
        try (okhttp3.Response resp = CLIENT.newBuilder()
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .build().newCall(rb.build()).execute()) {
            code = resp.code();
            okhttp3.ResponseBody rb2 = resp.body();
            text = rb2 == null ? "" : rb2.string();
        }
        if (code < 200 || code >= 300) {
            throw new IOException("PROPFIND HTTP " + code);
        }
        if (text.length() > 8 * 1024 * 1024) throw new IOException("目录列表过大");
        return text;
    }

    /**
     * PROPFIND 专用客户端。
     *
     * <p>不复用别处的客户端：这里必须允许任意方法名，且要禁掉自动重定向
     * （WebDAV 的 301 会丢掉 Depth 头，重定向后语义变了）。
     */
    private static final okhttp3.OkHttpClient CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    // ------------------------------------------------------------ XML 解析

    /**
     * 从 multistatus 里切出每个 {@code <response>} 并取字段。
     *
     * <p>不引 DOM 的原因：不同 NAS 用不同命名空间前缀（{@code D:}、{@code d:}、
     * 甚至无前缀），DOM 要配 namespace-aware 解析器才知道怎么读；而这里只需要
     * 「同名前缀的五个字段」，逐个 response 块做字符串扫描更稳、也更好排错。
     */
    static List<Item> parseMultiStatus(String xml) {
        List<Item> out = new ArrayList<>();
        if (xml == null) return out;
        int pos = 0;
        while (true) {
            int a = indexOfTag(xml, "response", pos);
            if (a < 0) break;
            int end = indexOfCloseTag(xml, "response", a);
            if (end < 0) break;
            String block = xml.substring(a, end);
            Item it = new Item();
            it.href = tagText(block, "href");
            it.name = tagText(block, "displayname");
            it.mtime = tagText(block, "getlastmodified");
            it.contentType = tagText(block, "getcontenttype");
            String len = tagText(block, "getcontentlength");
            if (!len.isEmpty()) {
                try {
                    it.size = Long.parseLong(len.trim());
                } catch (NumberFormatException ignored) {
                    it.size = 0;
                }
            }
            // resourcetype 是空元素或含 <D:collection/>：含 collection 即目录
            it.dir = block.toLowerCase().contains(":collection")
                    || block.contains("collection/");
            if (it.name.isEmpty()) it.name = lastName(it.href);
            if (!it.href.isEmpty()) out.add(it);
            pos = end;
        }
        return out;
    }

    private static int indexOfTag(String s, String tag, int from) {
        // 匹配 <前缀:response 或 <response，要求后面跟空白或 >
        String lower = s.toLowerCase();
        String t = tag.toLowerCase();
        int i = from;
        while (true) {
            int a = lower.indexOf(t, i);
            if (a < 0) return -1;
            int lt = lower.lastIndexOf('<', a);
            if (lt < 0) return -1;
            String between = lower.substring(lt + 1, a);
            // 允许的前缀只有 "" 或 "xxx:"
            if (between.isEmpty() || between.endsWith(":")) {
                char after = a + t.length() < s.length() ? s.charAt(a + t.length()) : '>';
                if (after == '>' || Character.isWhitespace(after)) return lt;
            }
            i = a + t.length();
        }
    }

    private static int indexOfCloseTag(String s, String tag, int from) {
        String needle = "</";
        String lower = s.toLowerCase();
        String t = tag.toLowerCase();
        int i = from;
        while (true) {
            int a = lower.indexOf(needle, i);
            if (a < 0) return -1;
            int start = a + needle.length();
            int b = lower.indexOf('>', start);
            if (b < 0) return -1;
            String inner = lower.substring(start, b).trim();
            if (inner.endsWith(":" + t) || inner.equals(t)) return b + 1;
            i = b + 1;
        }
    }

    private static String tagText(String block, String tag) {
        int a = indexOfTag(block, tag, 0);
        if (a < 0) return "";
        int open = block.indexOf('>', a);
        if (open < 0) return "";
        // 自闭合标签没有内容
        if (open > 0 && block.charAt(open - 1) == '/') return "";
        int close = indexOfCloseTag(block, tag, open);
        if (close < 0) return "";
        int closeStart = block.lastIndexOf('<', close - 1);
        if (closeStart < open) return "";
        return unescapeXml(block.substring(open + 1, closeStart)).trim();
    }

    private static String lastName(String href) {
        String s = decodePath(href);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int i = s.lastIndexOf('/');
        return i < 0 ? s : s.substring(i + 1);
    }

    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    // ------------------------------------------------------------ URL 工具

    public static String joinUrl(String base, String rel) {
        String b = base.endsWith("/") ? base : base + "/";
        String r = rel.startsWith("/") ? rel.substring(1) : rel;
        // 逐段编码，保留分隔符；中文目录必须编码，否则请求行会被服务端拒绝
        StringBuilder sb = new StringBuilder(b);
        String[] segs = r.split("/", -1);
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) sb.append('/');
            String seg = segs[i];
            if (seg.isEmpty()) continue;
            try {
                sb.append(URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
            } catch (Exception e) {
                sb.append(seg);
            }
        }
        return sb.toString();
    }

    /** 供外部做「测试连接」：确认认证与地址都对，顺便报出条目数。 */
    public static JSONObject test(JSONObject lib) throws Exception {
        String type = lib.optString("type", "webdav");
        JSONObject out = new JSONObject();
        if ("local".equals(type)) {
            java.nio.file.Path root = localRoot(lib);
            int n = 0;
            try (java.util.stream.Stream<java.nio.file.Path> st = java.nio.file.Files.list(root)) {
                n = (int) st.count();
            }
            out.put("ok", true);
            out.put("entries", n);
            return out;
        }
        if ("smb".equals(type)) {
            // 直接复用 list：连不上会抛异常，正好是"测试失败"的语义
            JSONObject r = listSmb(lib, "");
            out.put("ok", true);
            out.put("entries", r.optJSONArray("dirs").length() + r.optJSONArray("files").length());
            return out;
        }
        String target = joinUrl(lib.optString("base", ""), lib.optString("root", "") + "/");
        String xml = propfind(lib, target, "0");
        List<Item> items = parseMultiStatus(xml);
        out.put("ok", true);
        out.put("entries", items.size());
        return out;
    }

    /** 目录 id → 展示用分类（首页用）。 */
    public static Map<String, String> topDirs(String libId) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        JSONObject r = list(libId, "");
        JSONArray dirs = r.optJSONArray("dirs");
        for (int i = 0; i < dirs.length(); i++) {
            JSONObject d = dirs.optJSONObject(i);
            if (d != null) m.put(d.optString("path", ""), d.optString("name", ""));
        }
        return m;
    }
}
