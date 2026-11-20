package core;

import platform.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 应用内账号体系（公网部署 v2）。
 *
 * <p>四类凭证：
 * <ul>
 *   <li>管理员密码（PBKDF2，仅 TLS 下允许登录）</li>
 *   <li>浏览器会话（HttpOnly Cookie，服务端会话表）</li>
 *   <li>设备令牌（TV/PC/脚本，X-Meow-Token；存 SHA-256）</li>
 *   <li>媒体票 mt（短时、带 scope，URL 自带）</li>
 * </ul>
 *
 * <p>存储：{@code data/auth.json}（与 policy.json 分离，避免被配置导出/备份带走）。
 * 文件权限在 POSIX 上设 0600。
 *
 * <p>兼容：auth.json 不存在且 policy.json / MEOW_AUTH_TOKEN 里有旧 token 时，
 * 自动导入为 {@code legacy}/{@code env} 设备令牌；未配置任何凭证时全部放行
 * （局域网自用不受影响）。
 */
public final class Auth {

    public static final String COOKIE = "meow_sid";
    private static final int PBKDF2_ITER = 210_000;
    private static final long SESSION_TTL_MS = 30L * 24 * 3600 * 1000;
    private static final int MAX_SESSIONS = 32;
    private static final int MAX_DEVICES = 20;
    private static final long PAIR_TTL_MS = 5 * 60 * 1000;
    private static final long TICKET_TTL_MS = 30 * 60 * 1000;
    private static final long TICKET_TTL_TLS_MS = 6L * 3600 * 1000;

    private static final Object LOCK = new Object();
    private static JSONObject state;
    private static final SecureRandom RAND = new SecureRandom();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, Ticket> TICKETS = new ConcurrentHashMap<>();
    private static final Map<String, Pair> PAIRS = new ConcurrentHashMap<>();
    private static final Map<String, long[]> RATE = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_USED_WRITE = new ConcurrentHashMap<>();

    private Auth() {
    }

    /** org.json 的 put/get 在部分版本里是受检异常；统一收敛成运行时异常。 */
    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void add(JSONArray a, Object v) {
        try {
            a.put(v);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JSONArray devices() {
        JSONArray a = st().optJSONArray("devices");
        return a == null ? new JSONArray() : a;
    }

    public static final class Result {
        public final boolean ok;
        /** full / read */
        public final String scope;
        /** session / device */
        public final String via;
        /**
         * 失败原因（ok=false 时有意义）。登录页据此告诉用户"为什么被踢出来"，
         * 否则只能看到"请重新登录"，分不清是过期、被撤销、还是换了网络。
         * <p>取值：no_credentials / session_expired / password_changed /
         * token_invalid / token_expired / ip_mismatch。
         */
        public final String reason;
        /**
         * 设备令牌的 id（via=device 时有值，会话为空）。
         * 设备列表用它给"当前这台"打标记，避免用户撤销错自己的设备。
         */
        public final String deviceId;

        Result(boolean ok, String scope, String via) {
            this(ok, scope, via, "", "");
        }

        Result(boolean ok, String scope, String via, String reason) {
            this(ok, scope, via, reason, "");
        }

        Result(boolean ok, String scope, String via, String reason, String deviceId) {
            this.ok = ok;
            this.scope = scope;
            this.via = via;
            this.reason = reason == null ? "" : reason;
            this.deviceId = deviceId == null ? "" : deviceId;
        }

        public static Result no() {
            return new Result(false, "", "", "no_credentials");
        }

        public static Result no(String reason) {
            return new Result(false, "", "", reason);
        }
    }

    private static final class Session {
        final int epoch;
        final long exp;

        Session(int epoch, long exp) {
            this.epoch = epoch;
            this.exp = exp;
        }
    }

    private static final class Ticket {
        final String kind;
        final String lib;
        final String pathPrefix;
        final Set<String> hosts;
        final String ip;
        final long exp;

        Ticket(String kind, String lib, String pathPrefix, Set<String> hosts, String ip, long exp) {
            this.kind = kind;
            this.lib = lib;
            this.pathPrefix = pathPrefix;
            this.hosts = hosts;
            this.ip = ip;
            this.exp = exp;
        }
    }

    private static final class Pair {
        final String code;
        final long exp;
        boolean approved;
        String deviceId;
        String token;

        Pair(String code, long exp) {
            this.code = code;
            this.exp = exp;
        }
    }

    // ------------------------------------------------------------ state

    private static JSONObject st() {
        JSONObject s = state;
        if (s != null) return s;
        synchronized (LOCK) {
            if (state != null) return state;
            state = load();
            return state;
        }
    }

    private static JSONObject load() {
        JSONObject o = new JSONObject();
        try {
            String raw = DataPaths.read("auth.json");
            if (raw != null && !raw.trim().isEmpty()) o = new JSONObject(raw);
        } catch (Throwable e) {
            System.out.println("[auth] auth.json parse failed: " + e);
        }
        if (!o.has("username")) put(o, "username", "admin");
        if (!o.has("passwordHash")) put(o, "passwordHash", "");
        if (!o.has("passwordSalt")) put(o, "passwordSalt", "");
        if (!o.has("iterations")) put(o, "iterations", PBKDF2_ITER);
        if (!o.has("sessionEpoch")) put(o, "sessionEpoch", 1);
        if (!o.has("configKeyHash")) put(o, "configKeyHash", "");
        if (!o.has("setupTokenHash")) put(o, "setupTokenHash", "");
        if (!o.has("devices")) put(o, "devices", new JSONArray());
        return o;
    }

    private static void save() {
        synchronized (LOCK) {
            try {
                DataPaths.write("auth.json", st().toString(2));
                try {
                    Path p = DataPaths.of("auth.json");
                    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
                } catch (Throwable ignored) {
                    /* Windows / 非 POSIX：忽略 */
                }
            } catch (Throwable e) {
                System.out.println("[auth] save failed: " + e);
            }
        }
    }

    /** 启动时调用（必须在 --data-dir 解析之后）。导入旧 token、生成 setup/configKey。 */
    public static void init() {
        JSONObject s = st();
        boolean authOn = !s.optString("passwordHash").isEmpty() || devices().length() > 0;
        // 旧 policy.json 的 authToken → legacy 设备令牌
        if (devices().length() == 0) {
            try {
                String old = new JSONObject(DataPaths.read("policy.json") == null
                        ? "{}" : DataPaths.read("policy.json")).optString("authToken", "");
                if (!old.trim().isEmpty()) {
                    addDevice("legacy", "full", 0, "", old);
                    System.out.println("[auth] imported legacy policy.authToken as device token");
                    authOn = true;
                }
            } catch (Throwable ignored) {
            }
        }
        String envTok = System.getenv("MEOW_AUTH_TOKEN");
        String envTrim = envTok == null ? "" : envTok.trim();
        JSONArray ds = devices();
        if (!envTrim.isEmpty()) {
            if (findDeviceByToken(envTrim) == null) {
                // 轮换 MEOW_AUTH_TOKEN：先删旧的 env 设备，否则旧令牌永久有效
                boolean changed = false;
                for (int i = ds.length() - 1; i >= 0; i--) {
                    JSONObject d = ds.optJSONObject(i);
                    if (d != null && "env".equals(d.optString("name"))) {
                        ds.remove(i);
                        changed = true;
                    }
                }
                if (changed) save();
                addDevice("env", "full", 0, "", envTrim);
                System.out.println("[auth] imported MEOW_AUTH_TOKEN as device token"
                        + (changed ? " (replaced previous env token)" : ""));
                authOn = true;
            }
        } else {
            // 环境变量被移除：清掉自动导入的 env 设备，避免"已经删了变量却还要求令牌"
            boolean changed = false;
            for (int i = ds.length() - 1; i >= 0; i--) {
                JSONObject d = ds.optJSONObject(i);
                if (d != null && "env".equals(d.optString("name"))) {
                    ds.remove(i);
                    changed = true;
                }
            }
            if (changed) save();
        }
        String mode = System.getenv("MEOW_AUTH_MODE");
        boolean wantAuth = mode != null && !mode.trim().isEmpty() && !"off".equalsIgnoreCase(mode.trim());
        if (wantAuth && s.optString("passwordHash").isEmpty() && s.optString("setupTokenHash").isEmpty()) {
            String envSetup = System.getenv("MEOW_SETUP_TOKEN");
            String setup;
            if (envSetup != null && !envSetup.trim().isEmpty()) {
                // 容器编排里指定固定 setup 令牌：不打印（日志会被收集），用完即失效
                setup = envSetup.trim();
                System.out.println("[auth] setup token taken from MEOW_SETUP_TOKEN");
            } else {
                setup = randomToken(24);
                printBox("首次启动 setup 令牌", setup);
            }
            put(s, "setupTokenHash", sha256(setup));
            save();
            System.out.println("[auth] 打开 /setup 输入此令牌并设置管理员密码");
        }
        // ★ 只有"明确要开新账号体系"（MEOW_AUTH_MODE=on）或显式给了
        // MEOW_CONFIG_KEY 才生成 configKey。
        // 不能因为 legacy authToken 存在就生成：老部署升级后 /config.json 突然
        // 要求 ?key=，Fongmi/TVBox 的旧地址全部 403（2026-11-19 复查发现）。
        String envKey0 = System.getenv("MEOW_CONFIG_KEY");
        boolean wantKey = wantAuth || (envKey0 != null && !envKey0.trim().isEmpty());
        if (wantKey && s.optString("configKeyHash").isEmpty()) {
            String key = envKey0 != null && !envKey0.trim().isEmpty() ? envKey0.trim() : randomToken(24);
            put(s, "configKeyHash", sha256(key));
            save();
            printBox("CatVod configKey（/config.json?key=）", key);
        }
        // 恢复上次运行留下的会话（只存哈希），重启后网页不用重新登录
        loadSessions();
    }

    private static void printBox(String title, String value) {
        System.out.println("==================================================");
        System.out.println(" " + title);
        System.out.println(" " + value);
        System.out.println("==================================================");
    }

    // ------------------------------------------------------------ 状态查询

    public static boolean hasPassword() {
        return !st().optString("passwordHash").isEmpty();
    }

    public static String username() {
        return st().optString("username", "admin");
    }

    public static boolean isApiAuthEnabled() {
        return hasPassword() || devices().length() > 0;
    }

    public static boolean isSetupRequired() {
        return !st().optString("setupTokenHash").isEmpty();
    }

    /** none / password / token-only */
    public static String authMode() {
        if (hasPassword()) return "password";
        if (devices().length() > 0) return "token-only";
        return "none";
    }

    public static boolean isSecure(HttpExchange ex) {
        try {
            String tls = System.getenv("MEOW_PUBLIC_TLS");
            if (tls != null && ("1".equals(tls.trim()) || "true".equalsIgnoreCase(tls.trim()))) return true;
            if (trustProxy()) {
                String proto = ex.getRequestHeaders().getFirst("X-Forwarded-Proto");
                if ("https".equalsIgnoreCase(proto == null ? "" : proto.trim())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 密码登录/setup 是否允许。
     *
     * <p>判据是"这一跳网络的信任范围"，不是"TLS 与否"：
     * <ul>
     *   <li>TLS（或反代声明了 https）→ 允许；</li>
     *   <li>显式开了 {@code MEOW_ALLOW_INSECURE_PASSWORD=1} → 允许（测试用）；</li>
     *   <li>本机回环、局域网（RFC1918 / CGNAT / ULA / 链路本地）→ 允许；</li>
     *   <li>公网地址 + 明文 → 禁止（密码过公网等于公开，只能上 TLS 或用设备令牌）。</li>
     * </ul>
     *
     * <p>★ 早先只放行"回环"，于是局域网 HTTP 部署（家里 NAS / Docker）在浏览器里
     * 根本没有密码框 —— 用户以为这个产品没有密码体系。真正的边界是公网，
     * 不是"非回环"。
     *
     * <p>★ 判断依赖 {@link #clientIp}，它只在 {@code MEOW_TRUST_PROXY} 打开时才读
     * {@code X-Forwarded-For}。反代/网关形态必须开那个开关（meowd 与 nginx 都会
     * 注入可信 XFF），否则所有请求都被当成 127.0.0.1 而落进"本机放行"。
     */
    public static boolean passwordAllowed(HttpExchange ex) {
        if (isSecure(ex)) return true;
        String env = System.getenv("MEOW_ALLOW_INSECURE_PASSWORD");
        if (env != null && "1".equals(env.trim())) return true;
        String ip = clientIp(ex);
        return isLoopback(ip) || isPrivateAddress(ip);
    }

    /**
     * 明文密码可接受的地址范围：RFC1918 / CGNAT / ULA / 链路本地。
     *
     * <p>刻意**不含** 169.254.0.0/16（IPv4 链路本地）：云环境的元数据服务就在那一段，
     * 把它当可信等于给 SSRF 开后门。
     */
    private static boolean isPrivateAddress(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        for (String cidr : new String[]{
                "10.0.0.0/8",
                "172.16.0.0/12",
                "192.168.0.0/16",
                "100.64.0.0/10",   // CGNAT；Tailscale/WireGuard 组网常落在这里
                "fc00::/7",
                "fe80::/10"}) {
            try {
                if (cidrContains(ip, cidr)) return true;
            } catch (Throwable ignored) {
                // 单条写错不该改变整体判定
            }
        }
        return false;
    }

    private static boolean trustProxy() {
        String env = System.getenv("MEOW_TRUST_PROXY");
        if (env != null && !env.trim().isEmpty()) return true;
        try {
            String p = DataPaths.read("policy.json");
            return p != null && new JSONObject(p).optBoolean("trustProxy", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------ 密码

    public static void setPassword(String user, String pass) {
        if (pass == null || pass.length() < 10) throw new IllegalArgumentException("密码至少 10 位");
        byte[] salt = new byte[16];
        RAND.nextBytes(salt);
        int iter = PBKDF2_ITER;
        String hash = pbkdf2(pass, salt, iter);
        JSONObject s = st();
        synchronized (LOCK) {
            put(s, "username", user == null || user.trim().isEmpty() ? "admin" : user.trim());
            put(s, "passwordSalt", b64(salt));
            put(s, "passwordHash", hash);
            put(s, "iterations", iter);
            put(s, "setupTokenHash", "");
            put(s, "sessionEpoch", s.optInt("sessionEpoch", 1) + 1);
            save();
        }
        /*
         * 改密 → sessionEpoch+1 让所有旧会话失效。
         *
         * ★ 这里**不清 SESSIONS 表**：清了的话 sessionAuth 只能报"会话过期"，
         *   用户分不清是自然过期还是管理员改了密码。留着记录、让 epoch 检查
         *   命中，前端能拿到 `password_changed` 并明确提示（2026-11-20 实测：
         *   清表后 reason 变成 session_expired）。
         */
    }

    public static boolean verifyPassword(String user, String pass) {
        JSONObject s = st();
        String want = s.optString("username", "admin");
        if (user == null || !want.equalsIgnoreCase(user.trim())) return false;
        String saltB64 = s.optString("passwordSalt", "");
        String hash = s.optString("passwordHash", "");
        if (saltB64.isEmpty() || hash.isEmpty()) return false;
        try {
            String got = pbkdf2(pass, Base64.getDecoder().decode(saltB64), s.optInt("iterations", PBKDF2_ITER));
            return MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8),
                    hash.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean verifySetupToken(String token) {
        String h = st().optString("setupTokenHash", "");
        if (h.isEmpty() || token == null) return false;
        return MessageDigest.isEqual(sha256(token.trim()).getBytes(StandardCharsets.UTF_8),
                h.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 本机运维：重置管理员密码（`--auth-reset-password=<新密码>`）。
     *
     * <p>前提：能拿到服务器 shell（本机 / SSH / docker exec）。密码规则与网页一致
     * （≥10 位）；epoch+1 会清掉全部会话，设备令牌与 configKey 不受影响。
     */
    public static void resetPassword(String newPass) {
        if (newPass == null || newPass.length() < 10) {
            throw new IllegalArgumentException("密码至少 10 位");
        }
        setPassword(username(), newPass);
    }

    /**
     * 本机运维：关闭鉴权（`--auth-off`）——清空密码/设备令牌/configKey/setup
     * 并落一份空 auth.json。等价于"删掉 auth.json"，但不用运维去找数据目录。
     */
    public static void disableAll() {
        JSONObject s = st();
        synchronized (LOCK) {
            for (String k : new String[]{"username", "passwordSalt", "passwordHash",
                    "iterations", "setupTokenHash", "configKeyHash"}) {
                s.remove(k);
            }
            put(s, "devices", new JSONArray());
            put(s, "sessionEpoch", s.optInt("sessionEpoch", 1) + 1);
            save();
        }
        SESSIONS.clear();
        saveSessions();
        TICKETS.clear();
        LAST_USED_WRITE.clear();
    }

    private static String pbkdf2(String pass, byte[] salt, int iter) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pass.toCharArray(), salt, iter, 256);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return b64(f.generateSecret(spec).getEncoded());
        } catch (Throwable e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    // ------------------------------------------------------------ 会话

    /*
     * ★ 会话落盘（2026-11-20）：以前只在内存里，后端一重启（升级/Docker 重建/
     *   NAS 重启）所有网页端都要重新登录。用户视角是"刚登录过又要登"。
     *
     *   盘上只存 **sid 的 SHA-256**（auth.json 的 `sessions` 数组），泄了也不能
     *   直接拿来用 —— 与设备令牌同一套处理。TTL / MAX_SESSIONS / sessionEpoch
     *   （改密时 +1 让旧会话全失效）全部照旧生效。
     */

    public static String createSession() {
        cleanupSessions();
        String sid = randomToken(32);
        SESSIONS.put(sha256(sid), new Session(st().optInt("sessionEpoch", 1),
                System.currentTimeMillis() + SESSION_TTL_MS));
        if (SESSIONS.size() > MAX_SESSIONS) {
            // 踢最旧：按 exp 排序
            List<Map.Entry<String, Session>> list = new ArrayList<>(SESSIONS.entrySet());
            list.sort((a, b) -> Long.compare(a.getValue().exp, b.getValue().exp));
            while (SESSIONS.size() > MAX_SESSIONS && !list.isEmpty()) {
                SESSIONS.remove(list.remove(0).getKey());
            }
        }
        saveSessions();
        return sid;
    }

    public static void destroySession(String sid) {
        if (sid == null) return;
        if (SESSIONS.remove(sha256(sid)) != null) saveSessions();
    }

    /** 把当前会话表写回 auth.json（只写哈希）。登录/退出/清理时调用。 */
    private static void saveSessions() {
        try {
            JSONObject s = st();
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, Session> e : SESSIONS.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("h", e.getKey());
                o.put("epoch", e.getValue().epoch);
                o.put("exp", e.getValue().exp);
                arr.put(o);
            }
            synchronized (LOCK) {
                put(s, "sessions", arr);
            }
            save();
        } catch (Throwable ignored) {
            // 落盘失败不影响本次登录；下次启动最多是"要重新登录"
        }
    }

    /** 启动时恢复未过期会话（超过 MAX_SESSIONS 只留最晚过期的）。 */
    private static void loadSessions() {
        try {
            JSONArray arr = st().optJSONArray("sessions");
            if (arr == null) return;
            long now = System.currentTimeMillis();
            int epoch = st().optInt("sessionEpoch", 1);
            List<Session> keep = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                long exp = o.optLong("exp", 0);
                String h = o.optString("h", "");
                if (h.isEmpty() || exp < now) continue;
                if (o.optInt("epoch", 1) != epoch) continue; // 改过密码 → 全失效
                keep.add(new Session(epoch, exp));
                SESSIONS.put(h, keep.get(keep.size() - 1));
            }
            if (SESSIONS.size() > MAX_SESSIONS) {
                List<Map.Entry<String, Session>> list = new ArrayList<>(SESSIONS.entrySet());
                list.sort((a, b) -> Long.compare(a.getValue().exp, b.getValue().exp));
                while (SESSIONS.size() > MAX_SESSIONS && !list.isEmpty()) {
                    SESSIONS.remove(list.remove(0).getKey());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static String sessionId(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq > 0 && COOKIE.equals(p.substring(0, eq))) return p.substring(eq + 1);
        }
        return null;
    }

    public static void setSessionCookie(HttpExchange ex, String sid) {
        long maxAge = SESSION_TTL_MS / 1000;
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + sid
                + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + maxAge
                + (isSecure(ex) ? "; Secure" : ""));
    }

    public static void clearSessionCookie(HttpExchange ex) {
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"
                + (isSecure(ex) ? "; Secure" : ""));
    }

    public static Result sessionAuth(HttpExchange ex) {
        String sid = sessionId(ex);
        if (sid == null) return Result.no("no_credentials");
        // 表里 key 是 sid 的 SHA-256（见 createSession 的注释）
        Session s = SESSIONS.get(sha256(sid));
        if (s == null) return Result.no("session_expired");
        if (s.exp < System.currentTimeMillis()) {
            SESSIONS.remove(sha256(sid));
            saveSessions();
            return Result.no("session_expired");
        }
        if (s.epoch != st().optInt("sessionEpoch", 1)) {
            SESSIONS.remove(sha256(sid));
            saveSessions();
            // 改过密码 → 旧会话全失效，这个原因值得单独告诉用户
            return Result.no("password_changed");
        }
        return new Result(true, "full", "session");
    }

    private static void cleanupSessions() {
        long now = System.currentTimeMillis();
        int before = SESSIONS.size();
        SESSIONS.entrySet().removeIf(e -> e.getValue().exp < now);
        if (SESSIONS.size() != before) saveSessions();
    }

    // ------------------------------------------------------------ 设备令牌

    public static Result authenticate(HttpExchange ex) {
        Result s = sessionAuth(ex);
        if (s.ok) return s;
        String tok = ex.getRequestHeaders().getFirst("X-Meow-Token");
        if (tok == null || tok.trim().isEmpty()) {
            // 没带令牌：保留会话那边的原因（no_credentials / session_expired）
            return Result.no(s.reason.isEmpty() ? "no_credentials" : s.reason);
        }
        JSONObject dev = findDeviceByToken(tok.trim());
        if (dev == null) return Result.no("token_invalid");
        long now = System.currentTimeMillis();
        long exp = dev.optLong("expiresAt", 0);
        if (exp > 0 && exp < now) return Result.no("token_expired");
        String bind = dev.optString("bindIp", "");
        if (!bind.isEmpty()) {
            String src = clientIp(ex);
            if (!bind.equals(src)) return Result.no("ip_mismatch");
        }
        String id = dev.optString("id", "");
        String ip = clientIp(ex);
        Long last = LAST_USED_WRITE.get(id);
        if (last == null || now - last > 3600_000L) {
            LAST_USED_WRITE.put(id, now);
            put(dev, "lastUsed", now);
            // 最近 IP 只在这条节流路径里更新，避免每个请求都落盘
            if (ip != null && !ip.isEmpty()) put(dev, "lastIp", ip);
            save();
        }
        return new Result(true, dev.optString("scope", "full"), "device", "", id);
    }

    public static JSONObject newDevice(String name, String scope, long expiresAt, String bindIp) throws Exception {
        String token = randomToken(32);
        JSONObject dev = addDevice(name, scope, expiresAt, bindIp, token);
        JSONObject out = new JSONObject(dev.toString());
        put(out, "token", token); // 只在这里返回一次
        return out;
    }

    // ------------------------------------------------------------ 审计事件

    private static final int MAX_EVENTS = 200;
    private static final java.util.Deque<JSONObject> EVENTS =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    /**
     * 记一条安全事件（内存环形，最近 200 条，重启清零）。
     *
     * <p>为什么不落盘：事件是"最近发生了什么"的排障视图，不是审计日志。
     * 每个请求都写 auth.json 是写放大；真要长期留存应该接外部日志。
     * 这里零成本，够回答"昨晚谁在试密码""我刚是不是误撤销了设备"。
     */
    public static void event(String type, String detail, HttpExchange ex) {
        try {
            JSONObject o = new JSONObject();
            o.put("at", System.currentTimeMillis());
            o.put("type", type == null ? "" : type);
            o.put("detail", detail == null ? "" : detail);
            o.put("ip", ex == null ? "" : clientIp(ex));
            EVENTS.addFirst(o);
            while (EVENTS.size() > MAX_EVENTS) EVENTS.pollLast();
        } catch (Throwable ignored) {
        }
    }

    /** 最近事件（新 → 旧），上限 200。 */
    public static JSONArray events(int limit) {
        int n = Math.max(1, Math.min(limit, MAX_EVENTS));
        JSONArray a = new JSONArray();
        int i = 0;
        for (JSONObject o : EVENTS) {
            if (i++ >= n) break;
            a.put(o);
        }
        return a;
    }

    /** 当前有效会话数（用于"改密会影响几个浏览器"的预告）。 */
    public static int sessionCount() {
        cleanupSessions();
        return SESSIONS.size();
    }

    /**
     * 下线除当前会话外的所有浏览器会话。
     *
     * @return 被移除的数量（当前会话不在其中）
     */
    public static int destroyOtherSessions(String keepSid) {
        String keep = keepSid == null ? "" : sha256(keepSid);
        int n = 0;
        for (String k : new ArrayList<>(SESSIONS.keySet())) {
            if (k.equals(keep)) continue;
            if (SESSIONS.remove(k) != null) n++;
        }
        if (n > 0) saveSessions();
        return n;
    }

    /**
     * 撤销所有设备令牌（TV/PC/脚本）。
     *
     * <p>浏览器会话不在此列 —— 「设备」和「浏览器」是两套凭据，
     * 一键退出设备不该把自己正在用的网页也踢掉。
     *
     * @return 被撤销的数量
     */
    public static int revokeAllDevices() {
        int n = devices().length();
        if (n == 0) return 0;
        JSONObject s = st();
        synchronized (LOCK) {
            put(s, "devices", new JSONArray());
        }
        save();
        return n;
    }

    private static JSONObject addDevice(String name, String scope, long expiresAt,
                                       String bindIp, String token) {
        JSONArray arr = devices();
        if (arr.length() >= MAX_DEVICES) throw new IllegalStateException("设备令牌已达上限");
        JSONObject dev = new JSONObject();
        put(dev, "id", randomToken(9));
        put(dev, "name", name == null || name.trim().isEmpty() ? "device" : name.trim());
        put(dev, "hash", sha256(token));
        put(dev, "created", System.currentTimeMillis());
        put(dev, "lastUsed", 0);
        put(dev, "expiresAt", expiresAt);
        put(dev, "scope", "read".equals(scope) ? "read" : "full");
        put(dev, "bindIp", bindIp == null ? "" : bindIp.trim());
        add(arr, dev);
        save();
        return dev;
    }

    private static JSONObject findDeviceByToken(String token) {
        String h = sha256(token);
        JSONArray arr = devices();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject d = arr.optJSONObject(i);
            if (d == null) continue;
            String dh = d.optString("hash", "");
            if (dh.length() == h.length()
                    && MessageDigest.isEqual(dh.getBytes(StandardCharsets.UTF_8),
                       h.getBytes(StandardCharsets.UTF_8))) {
                return d;
            }
        }
        return null;
    }

    public static JSONArray listDevices() {
        JSONArray out = new JSONArray();
        JSONArray arr = devices();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject d = arr.optJSONObject(i);
            if (d == null) continue;
            JSONObject o = new JSONObject();
            put(o, "id", d.optString("id"));
            put(o, "name", d.optString("name"));
            put(o, "created", d.optLong("created"));
            put(o, "lastUsed", d.optLong("lastUsed"));
            put(o, "expiresAt", d.optLong("expiresAt"));
            put(o, "scope", d.optString("scope", "full"));
            put(o, "bindIp", d.optString("bindIp"));
            add(out, o);
        }
        return out;
    }

    public static boolean revokeDevice(String id) {
        JSONArray arr = devices();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject d = arr.optJSONObject(i);
            if (d != null && id.equals(d.optString("id"))) {
                arr.remove(i);
                save();
                return true;
            }
        }
        return false;
    }

    // revokeAllDevices 见上方"审计事件"一节（带 LOCK，与 save 串行）

    // ------------------------------------------------------------ 配对码

    public static JSONObject pairStart() {
        cleanupPairs();
        String code;
        synchronized (PAIRS) {
            do {
                code = String.format("%06d", RAND.nextInt(1_000_000));
            } while (findPairByCode(code) != null);
        }
        String pairId = randomToken(16);
        PAIRS.put(pairId, new Pair(code, System.currentTimeMillis() + PAIR_TTL_MS));
        JSONObject o = new JSONObject();
        put(o, "pairId", pairId);
        put(o, "code", code);
        put(o, "expiresIn", PAIR_TTL_MS / 1000);
        return o;
    }

    public static boolean pairApprove(String code, String name) throws Exception {
        Pair p = findPairByCode(code);
        if (p == null || p.approved || p.exp < System.currentTimeMillis()) return false;
        JSONObject dev = newDevice(name == null || name.trim().isEmpty() ? "TV" : name, "full", 0, "");
        p.approved = true;
        p.deviceId = dev.optString("id");
        p.token = dev.optString("token");
        return true;
    }

    public static JSONObject pairPoll(String pairId) {
        Pair p = PAIRS.get(pairId);
        if (p == null) return null;
        if (p.exp < System.currentTimeMillis()) {
            PAIRS.remove(pairId);
            return null;
        }
        if (!p.approved) {
            JSONObject o = new JSONObject();
            put(o, "approved", false);
            return o;
        }
        PAIRS.remove(pairId);
        JSONObject o = new JSONObject();
        put(o, "approved", true);
        put(o, "token", p.token);
        put(o, "deviceId", p.deviceId);
        return o;
    }

    private static Pair findPairByCode(String code) {
        if (code == null) return null;
        String c = code.trim();
        for (Pair p : PAIRS.values()) {
            if (p.code.equals(c)) return p;
        }
        return null;
    }

    private static void cleanupPairs() {
        long now = System.currentTimeMillis();
        PAIRS.entrySet().removeIf(e -> e.getValue().exp < now);
    }

    // ------------------------------------------------------------ configKey

    public static boolean hasConfigKey() {
        return !st().optString("configKeyHash", "").isEmpty();
    }

    public static boolean configKeyValid(String key) {
        String h = st().optString("configKeyHash", "");
        if (h.isEmpty()) return false;
        if (key == null || key.isEmpty()) return false;
        return MessageDigest.isEqual(sha256(key).getBytes(StandardCharsets.UTF_8),
                h.getBytes(StandardCharsets.UTF_8));
    }

    public static String rotateConfigKey() {
        String key = randomToken(24);
        put(st(), "configKeyHash", sha256(key));
        save();
        return key;
    }

    // ------------------------------------------------------------ 媒体票

    public static String issueTicket(String kind, String lib, String pathPrefix,
                                     Set<String> hosts, String ip) {
        cleanupTickets();
        String mt = randomToken(32);
        long ttl = TICKET_TTL_MS;
        boolean tls = "https".equalsIgnoreCase(System.getenv("MEOW_PUBLIC_TLS"));
        if (tls) ttl = TICKET_TTL_TLS_MS;
        TICKETS.put(mt, new Ticket(kind, lib == null ? "" : lib,
                pathPrefix == null ? "" : pathPrefix,
                hosts == null ? new LinkedHashSet<>() : hosts,
                ip == null ? "" : ip,
                System.currentTimeMillis() + ttl));
        return mt;
    }

    public static boolean validateTicket(String mt, String kind, String lib, String path,
                                         String url, String ip) {
        if (mt == null || mt.isEmpty()) return false;
        Ticket t = TICKETS.get(mt);
        if (t == null || t.exp < System.currentTimeMillis()) {
            TICKETS.remove(mt);
            return false;
        }
        if (!t.kind.equals(kind)) return false;
        if (!t.ip.isEmpty() && !t.ip.equals(ip)) return false;
        if ("library".equals(kind)) {
            if (!t.lib.equals(lib == null ? "" : lib)) return false;
            if (!t.pathPrefix.isEmpty()) {
                String p = path == null ? "" : path;
                if (!p.startsWith(t.pathPrefix)) return false;
            }
            return true;
        }
        // proxy：有 host 白名单就按白名单；空集合 = 只要求是合法公网 URL
        // （HLS 分片常跨 CDN 域，逐个签 host 不现实；URL 仍过 isSafeMediaUrl）。
        String host = hostOf(url);
        if (host == null) return false;
        return t.hosts.isEmpty() || t.hosts.contains(host);
    }

    public static String ticketTtlSuffix() {
        return "";
    }

    private static void cleanupTickets() {
        long now = System.currentTimeMillis();
        TICKETS.entrySet().removeIf(e -> e.getValue().exp < now);
    }

    // ------------------------------------------------------------ 限流

    /** 固定窗口限流：窗口内超过 max 返回 false。 */
    public static boolean allow(String bucket, String ip, int max, long windowMs) {
        long now = System.currentTimeMillis();
        String key = bucket + "|" + (ip == null ? "unknown" : ip);
        long[] cell = RATE.computeIfAbsent(key, k -> new long[]{now, 0, 0});
        synchronized (cell) {
            if (now - cell[0] >= windowMs) {
                cell[0] = now;
                cell[1] = 0;
            }
            cell[1]++;
            if (cell[2] > now) return false;
            return cell[1] <= max;
        }
    }

    /** 记录一次失败；连续失败达到阈值时封禁 blockMs。 */
    public static void fail(String bucket, String ip, int threshold, long blockMs) {
        long now = System.currentTimeMillis();
        String key = bucket + "|" + (ip == null ? "unknown" : ip);
        long[] cell = RATE.computeIfAbsent(key, k -> new long[]{now, 0, 0});
        synchronized (cell) {
            cell[1]++;
            if (cell[1] >= threshold) {
                cell[2] = now + blockMs;
                cell[1] = 0;
            }
        }
    }

    /** 检查是否在封禁期（不增加计数）。 */
    public static boolean blocked(String bucket, String ip) {
        String key = bucket + "|" + (ip == null ? "unknown" : ip);
        long[] cell = RATE.get(key);
        if (cell == null) return false;
        synchronized (cell) {
            return cell[2] > System.currentTimeMillis();
        }
    }

    public static void clearRate(String bucket, String ip) {
        RATE.remove(bucket + "|" + (ip == null ? "unknown" : ip));
    }

    // ------------------------------------------------------------ 工具

    public static String clientIp(HttpExchange ex) {
        try {
            if (trustProxy()) {
                String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
                if (xff != null && !xff.trim().isEmpty()) {
                    String[] parts = xff.split(",");
                    String last = parts[parts.length - 1].trim();
                    if (!last.isEmpty()) return last;
                }
                String real = ex.getRequestHeaders().getFirst("X-Real-IP");
                if (real != null && !real.trim().isEmpty()) return real.trim();
            }
            return ex.getRemoteAddress().getAddress().getHostAddress();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * 来源白名单（可选，{@code MEOW_ALLOW_CIDR} 逗号分隔）。
     *
     * <p>只作用于后端 API；回环永远放行 —— 否则 fnOS 网关/本机反代会被
     * 自己挡掉（部署文档已列为红线）。未设该变量 = 不限制（旧部署零影响）。
     */
    public static boolean ipAllowed(HttpExchange ex) {
        String env = System.getenv("MEOW_ALLOW_CIDR");
        if (env == null || env.trim().isEmpty()) return true;
        String ip = clientIp(ex);
        if (isLoopback(ip)) return true;
        for (String raw : env.split(",")) {
            String cidr = raw.trim();
            if (cidr.isEmpty()) continue;
            try {
                if (cidrContains(ip, cidr)) return true;
            } catch (Throwable ignored) {
                // 单条写错不该让整站 403：忽略它，其他条目继续判
            }
        }
        return false;
    }

    private static boolean isLoopback(String ip) {
        return "127.0.0.1".equals(ip) || "::1".equals(ip)
                || "0:0:0:0:0:0:0:1".equals(ip);
    }

    private static boolean cidrContains(String ip, String cidr) throws Exception {
        int slash = cidr.indexOf('/');
        String net = slash < 0 ? cidr : cidr.substring(0, slash);
        byte[] a = InetAddress.getByName(stripZone(ip)).getAddress();
        byte[] n = InetAddress.getByName(stripZone(net)).getAddress();
        if (a.length != n.length) return false;
        int prefix = slash < 0 ? a.length * 8 : Integer.parseInt(cidr.substring(slash + 1));
        if (prefix < 0 || prefix > a.length * 8) return false;
        int full = prefix / 8;
        int rest = prefix % 8;
        for (int i = 0; i < full; i++) {
            if (a[i] != n[i]) return false;
        }
        if (rest == 0) return true;
        int mask = 0xFF << (8 - rest);
        return (a[full] & mask) == (n[full] & mask);
    }

    private static String stripZone(String ip) {
        int p = ip.indexOf('%');
        return p < 0 ? ip : ip.substring(0, p);
    }

    public static String hostOf(String url) {
        try {
            URI u = URI.create(url);
            String h = u.getHost();
            return h == null ? null : h.toLowerCase();
        } catch (Throwable e) {
            return null;
        }
    }

    public static Set<String> hostsOf(Iterable<String> urls) {
        Set<String> out = new LinkedHashSet<>();
        for (String u : urls) {
            String h = hostOf(u);
            if (h != null) out.add(h);
            if (out.size() >= 64) break;
        }
        return out;
    }

    public static boolean isReadMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    private static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RAND.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Throwable e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 调试/诊断：不返回任何密钥。 */
    public static JSONObject status() {
        JSONObject o = new JSONObject();
        put(o, "mode", authMode());
        put(o, "authRequired", isApiAuthEnabled());
        put(o, "password", hasPassword());
        put(o, "devices", devices().length());
        put(o, "configKey", hasConfigKey());
        put(o, "setupRequired", isSetupRequired());
        return o;
    }
}
