package core;

import platform.HttpExchange;

/**
 * fnOS 网关身份（{@code X-Trim-*}）的解析与**可信判定**。
 *
 * <p>唯一可信来源是 fnOS 统一网关：它只在请求已经通过 fnOS 登录校验之后，
 * 才把这三个头注进转发请求。meowd 的两个 listener 用不同中间件区分来源
 * （见 {@code meowd/src/http.rs} 的 {@code set_peer_addr} / {@code mark_gateway_ingress}）：
 * <ul>
 *   <li>unix socket（网关）→ **保留** {@code X-Trim-*}，加 {@code X-Meow-Ingress: fnos-gateway}</li>
 *   <li>TCP 30010（直连）→ **删除** {@code X-Trim-*}，加 {@code X-Meow-Ingress: tcp}</li>
 * </ul>
 *
 * <p>★ 所以这里只认「ingress = fnos-gateway **且** uid 合法」的组合。少任何一半
 * 都返回 null —— 只判"有没有 {@code X-Trim-Userid}"是不够的，那是客户端能随手
 * 伪造的值（{@code proxy::forward} 会原样透传非逐跳头）。
 */
public final class FnosIdentity {

    public static final String INGRESS_HEADER = "X-Meow-Ingress";
    public static final String INGRESS_GATEWAY = "fnos-gateway";
    public static final String INGRESS_TCP = "tcp";

    private FnosIdentity() {
    }

    /** 一次请求解析出的身份。不可信时 {@link #from} 返回 null 而不是这个对象。 */
    public static final class Who {
        public final String uid;
        public final String name;
        public final boolean admin;

        Who(String uid, String name, boolean admin) {
            this.uid = uid;
            this.name = name;
            this.admin = admin;
        }
    }

    public static Who from(HttpExchange ex) {
        if (ex == null || ex.getRequestHeaders() == null) return null;
        if (!INGRESS_GATEWAY.equals(trim(ex.getRequestHeaders().getFirst(INGRESS_HEADER)))) return null;

        String uid = trim(ex.getRequestHeaders().getFirst("X-Trim-Userid"));
        /*
         * uid 必须是纯数字（fnOS 给的是 POSIX uid）。
         * 放宽成任意字符串等于把"身份 key"交给客户端：这个值会进
         * <data>/users/<uid>/ 的落盘路径，带 / 或 .. 就能穿越。
         */
        if (uid.isEmpty() || uid.length() > 12 || !uid.matches("[0-9]+")) return null;

        String name = trim(ex.getRequestHeaders().getFirst("X-Trim-Username"));
        if (name.isEmpty()) name = "uid" + uid;
        if (name.length() > 64) name = name.substring(0, 64);
        // 控制字符会破坏日志与 JSON，直接抹掉
        name = name.replaceAll("[\\p{Cntrl}]", "");

        boolean admin = "true".equalsIgnoreCase(trim(ex.getRequestHeaders().getFirst("X-Trim-Isadmin")));
        return new Who(uid, name, admin);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
