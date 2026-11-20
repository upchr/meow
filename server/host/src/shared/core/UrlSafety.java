package core;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;

import org.json.JSONObject;

/**
 * SSRF 防护：拒绝把请求打到本机、内网、云元数据等不该去的地方。
 *
 * <p>{@code /api/proxy/stream} 与 {@code /api/proxy/image} 的目标 URL 完全由调用方给，
 * 浏览器里任何一个页面都能借这台后端去访问它自己访问不到的东西。默认绑 127.0.0.1
 * 只是第一层，一旦绑到 0.0.0.0 给 TV 用（见 docs/36 §1.1 的端口与暴露约束），
 * 没有这层过滤就等于开放正向代理。
 *
 * <p>分两档，原因是两者的"合法目标"不同：
 * <ul>
 *   <li>{@link #isSafeMediaUrl} 宽松：**允许局域网段**。自建采集站/本地片源在
 *       192.168/10.x 上是真实场景，一刀切会把它们误杀。仍然拒绝回环与链路本地
 *       （后者包含 169.254.169.254 云元数据）。</li>
 *   <li>{@link #isSafeHttpUrl} 严格：**重定向目标必须用它**。从公网主机 302 到内网
 *       是典型的 SSRF 跳板，而没有任何正当理由出现这种跳转。</li>
 * </ul>
 *
 * <p>写法参照 {@code research/upstream/motao123-webtv/.../UrlSafety.java}
 * （docs/25 §7.1 指明它是唯一正确的实现）。
 *
 * <p><b>残留风险</b>：这里只做"连接前校验"，校验与真正连上之间仍有一个 DNS 重绑定的
 * 时间窗。彻底封死需要把校验通过的 IP 钉住再连（`openStream` 的 DoH 分支就是这么做的），
 * 但钉 IP 会破坏依赖 SNI 的 CDN——代码里 421 重试就是为它加的。所以默认不钉，
 * 接受这个窗口；要更高保证就开 safeDns。
 *
 * <p><b>DNS 解析必须能退化到 DoH</b>：解析失败时这里一律返回"不安全"（拒绝），
 * 因为连域名都解析不了就无从判断目标。但**解析失败有两种**：目标真的不存在，
 * 或者这台机器的系统 DNS 坏了。后者实测踩过——NAS 的 `/etc/resolv.conf`
 * 只有一个已失效的 nameserver，于是所有域名看起来都"不存在"，海报代理对豆瓣
 * 返回 403 `url not allowed`、刮削全挂，而同一时刻 DoH 能正常解析。
 * 所以走 {@link Doh#resolveWithFallback}：系统 DNS 优先，失败再问 DoH。
 */
public final class UrlSafety {

    private UrlSafety() {
    }

    /**
     * 给"多 IP 逐个重试"用的候选列表（图片代理）。
     *
     * <p>只返回**通过宽松档校验**的地址：局域网允许（自建图床是真实场景），
     * 回环/链路本地仍拒绝。校验不过或解析不出就返回空列表，
     * 调用方据此退回单连接（保持原行为，也不会绕过安全判定）。
     *
     * <p>safeDns 关闭时直接返回空——那种部署下由 openStream 走系统 DNS，
     * 不该在这里多解析一次。
     */
    public static java.util.List<String> mirrorCandidates(String url) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String[] parts = schemeAndHost(url);
        if (parts == null) return out;
        if (!safeDnsEnabled()) return out;
        for (String ip : Doh.candidates(parts[1], provider())) {
            try {
                InetAddress a = InetAddress.getByName(ip);
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                out.add(ip);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** policy.safeDns 是否开启。 */
    private static boolean safeDnsEnabled() {
        try {
            String p = core.DataPaths.read("policy.json");
            if (p == null || p.trim().isEmpty()) return false;
            return new JSONObject(p).optBoolean("safeDns", false);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 解析域名，系统 DNS 失败时退到 DoH。
     *
     * <p>空数组 = 两路都失败，调用方按"不可达"拒绝。
     */
    private static InetAddress[] resolveAll(String host) {
        return Doh.resolveWithFallback(host, provider());
    }

    /** DoH provider 取自 policy.dnsProvider，默认 dnspub（国内可达性最好）。 */
    private static String provider() {
        try {
            String p = core.DataPaths.read("policy.json");
            if (p != null && !p.trim().isEmpty()) {
                JSONObject o = new JSONObject(p);
                String v = o.optString("dnsProvider", "");
                if (!v.isEmpty()) return v;
            }
        } catch (Throwable ignored) {
        }
        return "dnspub";
    }

    /** 严格档：仅公网 http/https。重定向目标用这个。 */
    public static boolean isSafeHttpUrl(String url) {
        String[] parts = schemeAndHost(url);
        if (parts == null) return false;
        InetAddress[] addrs = resolveAll(parts[1]);
        // 解析不出来就不放行：连域名都解析不了，后面的连接也只会失败
        if (addrs.length == 0) return false;
        for (InetAddress address : addrs) {
            if (isBlockedAddress(address)) return false;
        }
        return true;
    }

    /**
     * 宽松档：允许局域网，仍拒绝回环/链路本地/本机任意地址。
     *
     * <p>用于媒体流与图片——它们的合法来源可能就在用户的 NAS 上。
     *
     * <p><b>回环默认拒绝</b>（SSRF 面）。但"同机另一个服务提供片源"
     * （例如 NAS 上 127.0.0.1:8080 的本地图床/转码服务）是真实场景，
     * 设 {@code MEOW_ALLOW_LOOPBACK_MEDIA=1} 可放行回环；链路本地
     * （含 169.254.169.254 云元数据）无论如何都拒绝。
     */
    public static boolean isSafeMediaUrl(String url) {
        String[] parts = schemeAndHost(url);
        if (parts == null) return false;
        InetAddress[] addrs = resolveAll(parts[1]);
        if (addrs.length == 0) return false;
        boolean allowLoopback = envBool("MEOW_ALLOW_LOOPBACK_MEDIA");
        for (InetAddress address : addrs) {
            if (address.isAnyLocalAddress() || address.isLinkLocalAddress()) return false;
            if (address.isLoopbackAddress() && !allowLoopback) return false;
        }
        return true;
    }

    /** 环境变量布尔开关（"1" / "true"，忽略大小写与首尾空白）。 */
    private static boolean envBool(String key) {
        String v = System.getenv(key);
        if (v == null) return false;
        String s = v.trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    /**
     * 地址是否属于"出站代理绝不该连"的段。
     *
     * <p>除 Java 自带标志外，额外挡住三类它认不出的：
     * CGNAT 100.64/10（运营商级 NAT，常见于容器/云内网）、
     * 198.18/15（benchmark 段，部分 fake-ip 代理会用它）、
     * 64:ff9b::/96（NAT64，IPv4 映射后可能绕过前几项判断）。
     */
    public static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        if (address instanceof Inet4Address) {
            byte[] b = address.getAddress();
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            if (first == 100 && second >= 64 && second <= 127) return true;
            return first == 198 && (second == 18 || second == 19);
        }
        if (address instanceof Inet6Address) {
            byte[] b = address.getAddress();
            return b[0] == 0 && b[1] == 0x64 && b[2] == (byte) 0xff && b[3] == (byte) 0x9b;
        }
        return false;
    }

    /** 返回 {scheme, host}，非 http/https 或解析不出 host 时返回 null。 */
    private static String[] schemeAndHost(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Throwable e) {
            /*
             * CatVod 的 URL 常带未编码的非法字符——实测潮流源的解析地址里有 `|`
             * （多清晰度/Referer 分隔符），Java 的 URI 视为非法字符直接抛
             * IllegalArgumentException，schemeAndHost 返回 null 后整个端点
             * 403 "url not allowed"（2026-11-14 OECT 实测，与 SafeDNS 无关）。
             * 只补查询串里的非法字符再试一次；解析仍失败才算不可信。
             */
            try {
                uri = URI.create(encodeIllegal(url.trim()));
            } catch (Throwable e2) {
                return null;
            }
        }
        String scheme;
        String host;
        try {
            scheme = uri.getScheme();
            host = uri.getHost();
        } catch (Throwable e) {
            return null;
        }
        if (scheme == null || host == null || host.isEmpty()) return null;
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
        return new String[]{scheme, host};
    }

    /**
     * 把 URL 里 URI 非法字符（空格、`|`、`"<>{}^\`` 等）按百分比编码，
     * 只在首次 URI.create 失败时作为兜底——正常 URL 一个字符都不动。
     */
    private static String encodeIllegal(String url) {
        StringBuilder sb = new StringBuilder(url.length() + 16);
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "-._~".indexOf(c) >= 0          // unreserved
                    || ":/?#[]@".indexOf(c) >= 0        // 语法字符
                    || "!$&'()*+,;=".indexOf(c) >= 0;   // 保留字符
            if (safe) {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", (int) c));
            }
        }
        return sb.toString();
    }
}
