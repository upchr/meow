import platform.Headers;
import platform.HttpExchange;
import platform.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 影视仓 Web/PC 后端。
 *
 * 在原有 SpiderHost 基础上扩展成给 Vue 前端用的 REST API：
 *
 *   GET  /api/health
 *   GET  /api/sites
 *   GET  /api/config
 *   POST /api/{site}/home|category|detail|search|play|action
 *   POST /api/search                多站聚合搜索
 *   GET  /api/proxy/danmaku         弹幕（Fongmi 协议，两段式）
 *   GET  /api/proxy/stream          视频代理（注入 Referer/UA，支持 Range）
 *   GET  /api/proxy/hxq             韩圈代理
 *
 * 启动：java -cp "classes;libs/*" ApiServer [port]
 */
public class ApiServer {

    private static int port = 9979;

    /** 站点 key -> 显示名。按 config 的 29 个唯一 api 注册。 */
    private static final Map<String, String> SITES = new LinkedHashMap<>();

    /**
     * 配置文件（config.sites.json）里实际出现的站点 key。
     *
     * <p>用来判断"配置是不是权威"：配置里有站点时，源码里那份 44 条静态基线
     * （历史内置源名字）里**没进配置**的条目不再对外可见 —— 否则「替换式导入」
     * 重启后又被并回来，垃圾源删不掉（2026-11-11 用户要求剥离垃圾源）。
     */
    private static final java.util.Set<String> CONFIG_SITE_KEYS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * 站点 key -> 原始 api（只记**非 csp_** 的那些）。
     *
     * <p>jar 型站点的 api 恒为 `csp_类名`，类名就是 key，所以不必存；
     * 但 drpy 型站点的 api 是脚本地址（`./api/drpy2.min.js`），
     * 不记下来就没法知道该把请求转给 JS 运行时——这正是之前
     * "导入带 drpy 的配置后这些站直接消失"的原因。
     */
    private static final Map<String, String> API = new ConcurrentHashMap<>();
    static {
        SITES.put("Douban", "豆瓣");
        SITES.put("YGP", "豆瓣预告");
        SITES.put("Config", "配置中心");
        SITES.put("Duopan", "闪电优汐");
        SITES.put("AppRJ", "潮流");
        SITES.put("AppQi", "肥猫");
        SITES.put("AppDrama2", "干饭");
        SITES.put("Hxq", "韩圈");
        SITES.put("AppYQK", "优奇客");
        SITES.put("AppFQ", "番茄");
        SITES.put("Gulu", "咕噜");
        SITES.put("AppGet", "蔬菜");
        SITES.put("Jpys", "金牌");
        SITES.put("Wwys", "农民");
        SITES.put("Jianpian", "荐片");
        SITES.put("SaoHuo", "骚火");
        SITES.put("Gz360", "瓜子");
        SITES.put("Czsapp", "厂长");
        SITES.put("SP360", "360");
        SITES.put("Bili", "哔哩");
        SITES.put("Dm84", "动漫巴士");
        SITES.put("FirstAid", "急救");
        SITES.put("Kugou", "酷狗");
        SITES.put("Kanqiu", "看球");
        SITES.put("GuaziTY", "瓜子体育");
        SITES.put("MiSou", "米搜");
        SITES.put("PanSearch", "盘搜");
        SITES.put("Push", "推送");

        // 下面这批类 jar 里一直就有（47 个顶层类），但从未登记进 SITES——
        // 属于"有资源没接线"：不是没实现，只是没注册，所以首页一直少这些源。
        // 不注册的三个内部类：Init（静态初始化）、Proxy（端口探测/代理）、GoProxy（goproxy）——
        // 它们不是内容源，注册进去只会让搜索去请求一个没有 home 的类。
        // Ali / AList / Market 也先不放：实测缺 android.app.Dialog / ProgressDialog stub，
        // 注册了启动即 NoClassDefFoundError，比不注册更吵。
        SITES.put("WangPan", "网盘");
        SITES.put("XBPQ", "苹果CMS");
        SITES.put("DuoDuoTv", "多多TV");
        SITES.put("AppYsV2", "影视V2");
        SITES.put("Qfeng", "清风");
        SITES.put("Xlys", "小鹿影视");
        SITES.put("App3Q", "3Q影视");
        SITES.put("App99", "99影视");
        SITES.put("AppDrama", "花语无敌");
        SITES.put("Jiaozi", "饺子");
        SITES.put("TingBook", "花语在线");
        SITES.put("Tvyb", "TV影吧");
        SITES.put("JSDemo", "JS示例");
        // 这三个原本卡在缺 android.app.Dialog / ProgressDialog stub。
        // 补了 stub 后类能加载；但注意它们都**不是影视内容源**：
        //   Ali    阿里云盘，需要 ext 里带 refresh token 才有内容
        //   AList  需要 ext 指向一个 AList 服务
        //   Market 应用市场，靠 Dialog 弹安装确认，无头 JVM 里没有意义
        SITES.put("Ali", "阿里云盘");
        SITES.put("AList", "AList");
        SITES.put("Market", "应用市场");

        // ★ 公开壳不再内置扩展 jar 站点（原 Jable 的 spider-ext.jar）。
        //   需要它的用户：把 spider-ext.jar 放进源包，配置里用站点级 jar 指过去。
    }

    /** 站点 ext。带 | 的是"域名|密钥"格式，多个站点可能共用同一个 spider 但不同 ext。 */
    private static final Map<String, String> EXT = new HashMap<>();
    static {
        EXT.put("SaoHuo", "https://shdy5.us");
        EXT.put("Jpys", "https://y2s52n7.com");
        EXT.put("Wwys", "https://vip.wwgz.cn:5200");
        EXT.put("Dm84", "https://dm84.net");
        EXT.put("AppRJ", "http://v.rbotv.cn");
        // ★ 第三方服务令牌不写进源码（公开库要求）。
        //   私有部署要保留这几个 ext，设对应环境变量即可：
        //     MEOW_EXT_APPQI / MEOW_EXT_APPGET / MEOW_EXT_APPDRAMA2
        //   值就是原来冒号后面那整串（含 url|token 的 | 部分）。
        putEnvExt("AppQi", "MEOW_EXT_APPQI");
        putEnvExt("AppGet", "MEOW_EXT_APPGET");
        EXT.put("Jianpian", "https://api.ztcgi.com");        EXT.put("Czsapp", "https://www.czzy89.com");
        EXT.put("Duopan", "{\"site_urls\":[\"http://shandian.blog/\",\"http://sd.sduc.site/\"]}");
        putEnvExt("AppDrama2", "MEOW_EXT_APPDRAMA2");
        // ⚠️ 不要在这里调 loadMultiInstances()。
        // 它要读 DataPaths.file("config.sites.json")，而 DataPaths 的根目录
        // 依赖 main() 里 MainArgs.set(args) 传入的 --data-dir。
        // 静态块在类加载时执行，早于 main()，此时参数还是空的，
        // 结果就是 --data-dir 被静默忽略、永远落到 cwd/data。
        // 改为在 main() 里显式调用（见 initAfterArgs）。
    }

    /** 从环境变量注册一个 ext；未设置或为空则不注册（公开源码不含令牌）。 */
    private static void putEnvExt(String key, String env) {
        String v = System.getenv(env);
        if (v != null && !v.trim().isEmpty()) EXT.put(key, v.trim());
    }

    /**
     * 依赖启动参数的一次性初始化，必须在 {@code main()} 里 MainArgs.set 之后调用。
     */
    private static void initAfterArgs() {
        // 类加载期不能读 policy.json（那时 --data-dir 还没解析），在这里补读。
        SearchCache.reloadTtl();
        // 基线必须在 loadMultiInstances 之前拍：它会把多实例塞进 SITES，
        // 之后再拍就把这些派生项也当成基线，导入时清不掉、只会越滚越多。
        snapshotBaseline();
        loadMultiInstances();
        /*
         * 外部 spider jar：必须在 loadMultiInstances 之后（它才刚读完配置），
         * 且在第一个请求之前。Android 上这是空操作（见 core.Jars.isJvm）——
         * TV 自己的 DexClassLoader 钩子由 GatewayServer 装。
         */
        core.Jars.reload();
        // jar 的 Proxy 端口探测只覆盖 9978–9999，fpk 的 30011 不在范围内
        core.Jars.pinProxyPort(port);
        // 健康域已搬到 routes.HealthRoutes：先把核心注册表以最小接口注入，
        // 再让它读回落盘状态。注入必须在 load 之前——load 之后立刻可能有
        // 并发请求查 isSkipped，那时还没绑定就会 NPE。
        routes.HealthRoutes.bind(new routes.HealthRoutes.SiteSource() {
            @Override
            public int siteCount() {
                return implementedSiteCount();
            }

            @Override
            public int extCount() {
                return implementedExtCount();
            }

            @Override
            public boolean spiderJarPresent() {
                return ApiServer.spiderJarPresent();
            }

            @Override
            public java.util.List<String> siteKeys() {
                return implementedSiteKeys();
            }

            @Override
            public boolean probeCall(String site) {
                try {
                    String out = call(site, "home", "{}");
                    return out != null && !isErrorPayload(out);
                } catch (Throwable t) {
                    return false;
                }
            }
        });
        routes.CacheRoutes.bindPolicy(() -> loadPolicy().optString("cacheDir", ""));
        /*
         * 缓存一集时顺带把弹幕抓下来（见 VideoCache.prefetchDanmaku 的注释）。
         *
         * 这里调的就是播放时那条 fetchDanmakuAuto —— 不能另写一份抓取逻辑：
         * 磁盘缓存 key 是 `dm|<剧名>|<集>`，自己拼一遍极容易和播放路径错开
         * （剧名 strip 规则、集数解析规则各差一点，缓存就永远命中不了）。
         * 走同一个函数还能顺带吃到内存 TTL 缓存与负缓存。
         */
        core.VideoCache.bindDanmakuPrefetcher((vodName, ep) -> {
            String clean = cleanName(vodName);
            int epNo;
            try {
                epNo = parseEpisodeNo(ep);
            } catch (Exception e) {
                epNo = 1;
            }
            String xml = fetchDanmakuAuto(clean, epNo);
            System.out.println("[cache] 弹幕预取 " + clean + " 第" + epNo + "集 → "
                    + countTags(xml) + " 条");
        });
        loadHealth();
        // 缓存任务清单：重启后仍要能列出"上一轮没下完的"，否则用户在离线页
        // 找不到自己点过的整季下载，只能重新点一遍（然后重下已经有的分片）。
        core.VideoCache.loadManifest();
    }

    /**
     * 从 config.sites.json 展开多实例站点。
     *
     * 原配置里同一个 spider 类会注册成多个站点（AppDrama2 → 干饭/行动/再来/一碗，
     * Bili → 合集/明星MV/少儿/小学/初中/高中，AppGet → 蔬菜/方舟/番薯），
     * 区别只在 init 时的 ext。不展开的话这些源在搜索里根本不会出现——
     * 这正是「影视仓能搜到十几个源、我们只有三个」的直接原因。
     *
     * key 用 "类名#序号"，instance() 里会剥掉 # 后面的部分再加载类。
     */
    private static void loadMultiInstances() {
        try {
            /*
             * 配置文件是**权威**：一旦存在站点表，静态基线里那些"没进配置"的
             * 默认站就不该再出现在列表/搜索里（见 siteVisible）。
             *
             * 踩过（2026-11-11 用户要"剥离垃圾源、只留能用的"）：源码里那份
             * 44 条 SITES.put 基线会在每次 rebuildSites / 冷启动时 **重新并进来**，
             * 于是「替换式导入」重启后又变回"配置 + 44 条幽灵站"，
             * 荐片这类跑不起来的基线站永远删不掉。
             */
            synchronized (CONFIG_SITE_KEYS) {
                CONFIG_SITE_KEYS.clear();
            }
            java.io.File f = core.DataPaths.file("config.sites.json");
            if (!f.exists()) return;
            String txt = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            JSONArray arr = new JSONObject(txt).optJSONArray("sites");
            if (arr == null) return;

            java.util.Map<String, Integer> seen = new java.util.HashMap<>();
            /*
             * 非 csp_ 站的 key 占用登记：useKey -> api。
             *
             * 原来这里直接 SITES.put(key)，同一份配置里两个不同的 drpy 源只要
             * key 撞了（"某某影院"这类可读名字特别容易撞），后一个会**静默**
             * 顶掉前一个 —— 导入报告只报站数，用户完全看不出少了一个。
             * csp_ 分支早就用 #2/#3 编号解决同一个问题，这里补齐。
             *
             * 只和**配置文件内部**比，不和静态基线比：配置里写 "豆瓣" 仍然
             * 覆盖内置那个（ADR-D7：改源不发版）。
             */
            java.util.Map<String, String> takenKey = new java.util.HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                String api = s.optString("api", "");
                if (api.isEmpty()) continue;
                // 非 csp_ 的 api（drpy 的 .js、以及未来的 py:）不再丢弃：
                // 记进 API 表，由 call() 分派到对应运行时。
                if (!api.startsWith("csp_")) {
                    String key = s.optString("key", "");
                    if (key.isEmpty()) continue;
                    String useKey = key;
                    boolean duplicate = false;
                    for (int n = 1; ; n++) {
                        String prev = takenKey.get(useKey);
                        if (prev == null) break;              // 空位
                        if (prev.equals(api)) {               // 同 key 同 api = 重复条目
                            duplicate = true;
                            break;
                        }
                        useKey = key + "#" + (n + 1);         // 同 key 不同 api = 并列
                    }
                    if (duplicate) continue;
                    takenKey.put(useKey, api);
                    CONFIG_SITE_KEYS.add(useKey);
                    SITES.put(useKey, cleanSiteName(s.optString("name", key), key));
                    API.put(useKey, api);
                    Object extObj = s.opt("ext");
                    String ext = extObj == null || extObj == JSONObject.NULL ? ""
                            : (extObj instanceof String ? (String) extObj : String.valueOf(extObj));
                    if (!ext.isEmpty()) EXT.put(useKey, ext);
                    continue;
                }
                String cls = api.substring(4);

                Object extObj = s.opt("ext");
                String ext = extObj == null || extObj == JSONObject.NULL ? ""
                        : (extObj instanceof String ? (String) extObj : String.valueOf(extObj));

                int n = seen.merge(cls, 1, Integer::sum);
                String key = n == 1 ? cls : cls + "#" + n;
                // 配置里的 ext 一律优先，**包括首个实例**。
                //
                // 早先只在 n>1 时写 ext，理由是"硬编码那份实测可用"。但那样会让
                // 单实例站点的 ext 永远无法通过配置修改 —— 导入一份外部配置后，
                // 里面只有一条 csp_Market 时它的 ext 被静默丢弃，表现为
                // "导入成功但站点还是报错"（实测：Market/AList 的 init 拿不到 ext 就 NPE）。
                // ADR-D7 要的是"改源不发版"，所以配置该赢；配置里 ext 为空时才保留硬编码默认值。
                //
                // 这里原来还要求 n>1 才补 SITES，导致"jar 里有这个类、但我们没静态
                // 注册"的站导入后进不了站点表 —— 文件里写着、界面上找不到。
                // 缺了就补，静态注册那份不受影响（put 只在 key 不存在时发生）。
                if (!SITES.containsKey(key)) {
                    SITES.put(key, cleanSiteName(s.optString("name", cls), cls));
                }
                CONFIG_SITE_KEYS.add(key);
                if (!ext.isEmpty()) EXT.put(key, ext);
            }
            System.out.println("[api] multi-instance sites: " + SITES.size());
        } catch (Throwable t) {
            System.out.println("[api] config.sites.json ignored: " + t);
        }
    }

    /** 去掉配置里用于装饰的 emoji / 方框字符 */
    private static String cleanSiteName(String raw, String fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == 0x2503 || c == 0xFE0F || c == 0x20E3) continue;
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_') sb.append(c);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? fallback : out;
    }

    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(12);

    /**
     * 扇出池：搜索 / 直播这类「一次打 200+ 个源」的场景专用。
     *
     * <p><b>为什么不能共用 POOL</b>：POOL 只有 12 个线程，而搜索要提交 270 个任务。
     * 每个任务在池线程里先抢 Semaphore 再干活，12 个慢源（drpy 常 30s 才超时）
     * 占满线程后，后面 258 个任务连"开始"的机会都没有——20 秒总预算一过，
     * 聚合结果就是空的。实测日志：5 个 drpy 源各跑 30s，groups=0。
     *
     * <p>并发上限靠 gate（Semaphore）控制，池本身给足线程，
     * 这样"哪些源在跑"由 gate 决定，而不是被池大小意外截断。
     */
    /**
     * 聚合搜索的线程池。
     *
     * <p><b>为什么从 cachedThreadPool 换成有界池</b>：270 个源会一次性 submit
     * 270 个任务，cached 池会同时创建 270 个线程（每个约 512KB~1MB 栈），
     * TV 上这是几十到上百 MB 的瞬时占用。改成有界池后线程数封顶，
     * 超出部分排队。
     *
     * <p><b>为什么可以有队列（不学 Fongmi 的"排队即超时"）</b>：Fongmi 用
     * {@code withTimeout} 从 submit 时刻开始计时，线程池只有 20 个，
     * 站点超过 20 个时后面的任务还没跑就已经在倒计时 —— 这是它的缺陷。
     * 我们不用 per-task timeout，而在聚合层用共享的
     * {@code searchDeadline} + 收尾判据，排队中的任务不会"还没开始就失败"。
     *
     * <p>容量取 {@code threads 上限 × 4} 的量级：够放下一整批源，
     * 又能在极端情况下（配置错误导致 source 表异常膨胀）及早拒绝而不是 OOM。
     */
    /*
     * 扇出池：搜索 / 直播这类「一次打 200+ 个源」的场景专用。
     *
     * ★ 必须是 cached（不排队），不能是"固定 N 线程 + 队列"。
     *
     * 踩过（实测极米 Z6X Pro，Android 9 / 4 核 A55）：
     * 曾经改成 `ThreadPoolExecutor(cores*2, cores*2, 队列 2048)` —— 理由是
     * "270 个源会一次创建 270 个线程，内存吃不消"。结果投影仪上聚合搜索
     * 从"能出 11 个源"退化成 **1 个源**：
     *
     *   Gulu 单站 20s、Jianpian 20s、FirstAid 20s（都卡在 socket 读）
     *   8 个线程被这 3 类慢源占死，剩下 41 个源在队列里**永远等不到执行**，
     *   收尾一到就全被丢掉。进度实测 `returned=1/44` —— 44 个任务只完成了 1 个。
     *
     * cached 池的语义是"来一个跑一个"，慢源各占自己的线程、快源立刻能跑，
     * 这才是聚合搜索要的：**慢源不该阻塞快源**。
     *
     * 担心内存的话，看实测：TV 网关只有 44 个源（drpy 源在这个形态下是
     * unsupported 被跳过），cached 池最多 44 个线程；MuMu/docker 270 个源时
     * 线程数会上去，但它们内存充裕。真正的保护是"源表规模"，不是核数。
     */
    /**
     * 搜索线程的栈大小。
     *
     * <p>ART 默认每线程栈 1MB（`Thread` 默认值）。搜索线程只做"发请求 + 解析 JSON"，
     * 调用深度很浅，512KB 足够，且 12 个线程就省下 6MB 虚拟内存 ——
     * 32 位进程地址空间只有 4GB（其中 3GB 给用户态），这类累积不能忽视。
     *
     * <p>不能压更小：spider 内部有递归解析（如酷狗的树形分类），
     * 256KB 实测在个别源上会 `StackOverflowError`。
     *
     * <p><b>必须定义在所有用它的线程工厂之前</b>：静态字段按文件顺序初始化，
     * 放到后面拿到的是 0（`Thread` 会当成"用默认栈"），静默失效。
     */
    private static final long SEARCH_STACK_BYTES = 512L * 1024L;

    /**
     * 单源超时控制用的**共享**池。
     *
     * <p>只做"包一层 Future.get(timeout)"，不承担并发度控制（那是 searchPool 的事）。
     * 线程数取 24：足够覆盖同时在等的源，又不会像"每源一线程"那样把栈内存吃光
     * （实测 44 个源 = 44 个线程时 VmRSS 293MB，堆上限只有 192MB）。
     *
     * <p><b>32 位进程压到 8</b>：这里和 searchPool 是**两份**线程预算 ——
     * 搜索池 6 个线程每个都会往这里再提交一个（`inner.get(超时)`），
     * 24 个线程里有 18 个永远空转，白占 32 位进程的地址空间。
     * 实测崩溃时线程数从 71 涨到 108，"多出来的 36 个"正是这两个池。
     */
    /**
     * 单源超时的执行池。
     *
     * <p><b>为什么是 cached 而不是固定 N 线程</b>（2026-10-30 OECT 实测）：
     * 超时后 `cancel(true)` 对卡在 `socket.read()` 的 OkHttp **无效** ——
     * 被放弃的任务仍要跑到 jar 内部超时才退出（实测 Tvyb / TingBook
     * 各卡 **96 秒**）。固定 24 线程时，这些"已放弃但还没退出的残骸"
     * 会把池彻底占满，下一轮 submit 只能排队，8 秒超时全返回空。
     *
     * <p><b>上限必须贴近 effThreads，不能开大</b>（2026-10-30 修正）：
     * 早先给 96，"让残骸各自占线程别堵住新任务"。但那等于**放弃约束** ——
     * 慢源可以同时占满几十个线程、一路跑到 jar 内部的 96 秒，
     * 期间 searchPool 拼命提交新任务，整机线程数失控（实测 28 个
     * meow-site-to + 15 个 OkHttp 连接线程）。
     *
     * <p>改成 {@code effThreads + 8}：只留 8 个缓冲位给"已放弃但还没退出"
     * 的残骸，再多就 AbortPolicy 拒绝 —— 那个源本轮直接放弃。
     * 这样慢源对线程池的消耗是有界的，且随并发设置自动缩放。
     */
    private static final java.util.concurrent.ThreadPoolExecutor SITE_TIMEOUT_POOL =
            new java.util.concurrent.ThreadPoolExecutor(
                    /*
                     * core=0 + SynchronousQueue：任务来就建线程、空闲 60 秒回收，
                     * 但 max 由 sharedSiteTimeoutPool() 按本轮并发设置，
                     * 不是写死的常量。
                     */
                    0, 32, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(null, r, "meow-site-to", SEARCH_STACK_BYTES);
                        t.setDaemon(true);
                        return t;
                    },
                    /*
                     * AbortPolicy 而不是 CallerRuns：超限时抛
                     * RejectedExecutionException，调用方 catch 后把这个源
                     * 当"本轮放弃"处理。绝不能让 searchPool 线程自己跑
                     * spider —— 那会把它也卡住，正是我们要避免的。
                     */
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /**
     * 把超时池的上限对齐到本轮并发：{@code effThreads + 8}。
     *
     * <p>+8 是"残骸缓冲"：慢源被 8 秒超时放弃后仍会跑到 jar 内部超时才退出，
     * 期间各占一个线程。留 8 个缓冲位够吸收常见情况（实测同时卡住的
     * 通常 2~4 个），再多就拒绝 —— 拒绝比无限扩张好，至少线程数有界。
     */
    private static void alignSiteTimeoutPool(int effThreads) {
        try {
            int want = Math.max(4, Math.min(32, effThreads + 8));
            if (want > SITE_TIMEOUT_POOL.getMaximumPoolSize()) {
                SITE_TIMEOUT_POOL.setMaximumPoolSize(want);
            } else if (want < SITE_TIMEOUT_POOL.getMaximumPoolSize()) {
                SITE_TIMEOUT_POOL.setMaximumPoolSize(want);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 超时池线程数：32 位 8 个，其余 24 个。
     *
     * <p>32 位取值必须 ≥ searchPool 的并发上限（6）并留一点余量给
     * 探活（`probeOne` 也用这个池）：比它小会让搜索线程在 `submit` 上堵住，
     * 等于把并发上限又砍一刀。
     */
    private static int timeoutPoolSize() {
        try {
            String m = System.getProperty("sun.arch.data.model");
            if (m != null && m.trim().startsWith("32")) return 8;
        } catch (Throwable ignored) {
        }
        return 24;
    }

    private static final ExecutorService FANOUT = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(null, r, "meow-fanout", SEARCH_STACK_BYTES);
        t.setDaemon(true);
        return t;
    });

    /**
     * 单条解析器的超时。
     *
     * <p>公共 jx 接口经常半死（连得上但不返回），type 4 要并发跑好几条，
     * 没有上限就会把整个 /api/parse 拖到前端放弃。15s 是实测可接受的上界
     * ——超过这个时间用户已经在怀疑"是不是卡住了"。
     */
    private static final int PARSE_TIMEOUT_MS = 15_000;
    /** 后端版本；健康检查与前端能力协商用。 */
    public static final String VERSION = "1.2.8";
    /** 进程启动时间（毫秒），用于 uptime。 */
    private static volatile long STARTED_AT = 0;

    /**
     * 聚合搜索的总时间预算（毫秒）。
     *
     * <p>各 spider 内部没有统一的超时约束，个别站会卡在 socket 读上几十秒。
     * 到点就放弃未返回的站、用已有结果返回，避免一个慢站拖死整个搜索。
     * 可用 {@code -Dmeow.searchTimeout=毫秒} 覆盖。
     */
    private static final long SEARCH_TOTAL_TIMEOUT_MS = resolveSearchTimeout();

    private static long resolveSearchTimeout() {
        try {
            String v = System.getProperty("meow.searchTimeout");
            if (v != null && !v.trim().isEmpty()) {
                return Math.max(3_000L, Long.parseLong(v.trim()));
            }
        } catch (Throwable ignored) {
        }
        // 30 秒：异步任务不再占请求线程池，放宽不会拖累别的接口。
        // 实测最慢的真源（Jiaozi 首轮 30.8s）仍会被截，但它本来也没结果。
        return 30_000L;
    }

    /**
     * 聚合搜索的空闲收尾窗口（毫秒）。
     *
     * <p>已经有结果后，连续这么久没有新站返回就直接返回现有结果。
     */
    private static final long SEARCH_IDLE_MS = 3_500L;
    /**
     * 跳过之后至少要保留多少个源参与搜索。
     *
     * <p>失效判据是"累计失败"，遇到"配置刚导入还没跑过""整段网络异常"
     * 这类情况时可能一次把所有源判死 → 稳定返回 0 条，用户看到的是
     * "搜索坏了"。留一批源，宁可慢也不能没结果。
     */
    private static final int MIN_ALIVE_SITES = 8;

    /**
     * 空闲收尾的最早生效时间（毫秒）——在这之前无论多安静都不收尾。
     *
     * <p><b>为什么必须有这道门槛（实测数据）</b>：原来只有"空闲 2.5 秒就收尾"一条规则，
     * 于是快站在 0.2~0.6 秒就全回来了，2.5 秒后立刻收尾 —— 慢源还没跑完。
     * 逐站实测（MuMu，关键词「我不是大师」，各三轮）：
     * <pre>
     *   Jpys 0.2s / AppGet 0.1s / Bili 0.4s   ← 快源
     *   Gz360 17.9s → 0.8s → 0.8s             ← 首轮要冷启动（DNS + TLS 建连）
     * </pre>
     * 17.9 秒的 Gz360 永远赶不上 2.5 秒的收尾，表现就是<b>同一个关键词搜两次、
     * 源数不一样</b>（实测 6 轮里 Gz360 只出现 1 次、SP360 4 次）。
     *
     * <p><b>2026-10-29 调整：8 秒 → 3 秒</b>。原来定 8 秒是因为同步搜索下
     * "首屏 = 全部耗时"，必须一次等够才不会漏慢源。改成"后台任务 + 轮询快照"
     * 之后首屏由轮询保证（实测 1.06 秒就能看到第一批结果），等待预算不再需要
     * 替首屏兜底 —— 降到 3 秒即可，慢源继续在后台跑，用户随时能看到增量。
     */
    private static final long SEARCH_MIN_MS = resolveMinWait();

    /**
     * 收尾前的最短等待：**按设备能力自适应**。
     *
     * <p>同一份代码在不同设备上的"合适等待"差一个量级：
     * <ul>
     *   <li>docker / MuMu：单源 0.1~1s，3 秒足够让绝大多数源回来</li>
     *   <li>极米 Z6X Pro（armeabi-v7a）：单源冷启动 8~10s（实测），
     *       3 秒就收尾等于只收到最快的那 1~2 个源 —— 用户看到"只有 1 条结果"</li>
     * </ul>
     *
     * <p>判据用 {@code Runtime.maxMemory()} 而不是核数：这两个设备的核数一样（4），
     * 但堆上限差很多（192MB vs 512MB+）。堆小的设备往往同时是低端芯片，
     * 用它当代理指标比读 CPU 频率可靠（后者在 Android 上取不到）。
     *
     * <p>小堆（<256MB）等 8 秒、大堆等 3 秒。慢一点总比搜不到强 ——
     * 首屏已经由"后台任务 + 轮询快照"保证（实测 0.78 秒就能看到第一批结果），
     * 这里的等待只影响"最终能收到多少源"。
     */
    private static long resolveMinWait() {
        long heap = Runtime.getRuntime().maxMemory();
        return heap < 256L * 1024 * 1024 ? 8_000L : 3_000L;
    }

    /**
     * 聚合搜索里**单个源**的硬超时。
     *
     * <p>spider 内部 OkHttp 的超时是 30 秒（{@code merge/n/c} 与
     * {@code merge/q/d0} 里写死的 {@code readTimeout(30, SECONDS)}），
     * 而聚合要同时打几十个源。有源连不通时它占着线程干等 30 秒，把池占满，
     * 能用的源反而排不上队 —— 实测投影仪上 44 个源只出了 1 个结果。
     *
     * <p><b>这个"放弃等待"只是不再等，线程并不会因此释放</b>：
     * {@code Future.cancel(true)} 依赖 {@code Thread.interrupt()}，而
     * {@code java.net.Socket} 的阻塞读**不检查中断标志**。用 -javaagent
     * 在 {@code okhttp3.RealCall.execute()} 上插桩实测（2026-10-30）：
     * 外层 8 秒超时之后，仍有 11~20 个 Call 活着，最长到 **36 秒**才结束，
     * 且 {@code canceled=false}。所以真正兜住并发的是
     * {@code SITE_TIMEOUT_POOL} 的冗余容量（见 {@link #alignSiteTimeoutPool}），
     * 不是这里的 cancel。
     *
     * <p>8 秒的依据：实测可用源的冷启动首次请求在 0.2~1.3 秒之间（最慢的
     * Jpys 首轮 9.1 秒但次轮 0.2 秒），8 秒已能覆盖正常源的长尾；
     * 超过 8 秒的源对交互式搜索没有价值，让它失败比让它拖住整批更划算。
     */
    private static final long SEARCH_SITE_TIMEOUT_MS = resolveSiteTimeout();

    /**
     * 单源硬超时：小堆设备给 6 秒、其余 8 秒。
     *
     * <p>为什么要按设备分：小堆设备上线程池也小（按堆算出来的），
     * 队列轮转慢。16 个线程跑 44 个源，每源 8 秒 = 最坏要 24 秒；
     * 压到 6 秒是 18 秒，给收尾留出余量。
     *
     * <p>为什么不能更短：实测可用源的**冷启动首次**请求要 8~10 秒
     * （`Gz360` 10.7s→0.8s、`Jpys` 9.1s→0.2s），压太狠会把它们也砍掉。
     * 6 秒是"能保住大部分可用源、又不让慢源占满队列"的折中。
     */
    private static long resolveSiteTimeout() {
        long heap = Runtime.getRuntime().maxMemory();
        return heap < 256L * 1024 * 1024 ? 6_000L : 8_000L;
    }

    public static void main(String[] args) throws Exception {
        // 先把参数交给 DataPaths（它可能在任何静态初始化里被调用）
        core.DataPaths.MainArgs.set(args);
        for (String a : args) {
            // 支持三种写法：裸数字 / --port=NNNN / --port NNNN 的后半段
            if (!a.isEmpty() && !a.startsWith("--")) {
                try {
                    port = Integer.parseInt(a.trim());
                } catch (NumberFormatException ignored) {
                }
            } else if (a.startsWith("--port=")) {
                try {
                    port = Integer.parseInt(a.substring("--port=".length()).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // 端口也可用环境变量覆盖（Docker / PC sidecar 友好）
        String envPort = System.getenv("MEOW_PORT");
        if (envPort != null && !envPort.trim().isEmpty()) {
            try {
                port = Integer.parseInt(envPort.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        // 参数就绪后再做依赖 DataPaths 的初始化（静态块里做会丢掉 --data-dir）
        boot();
        String bind = "0.0.0.0";
        String envBind = System.getenv("MEOW_BIND");
        if (envBind != null && !envBind.trim().isEmpty()) bind = envBind.trim();

        listen(bind, port, 16);
        STARTED_AT = System.currentTimeMillis();
        logStartupTail(port);
    }

    /**
     * 起服务之后的日志尾巴。抽出来是因为 Android 侧不打印这些
     * （stdout 在 Android 上等于丢弃），但 JVM 侧仍需完整交代。
     */
    private static void logStartupTail(int actualPort) {
        System.out.println("[api] " + VERSION + " listening on http://127.0.0.1:" + actualPort);
        System.out.println("[api] sites: " + siteCount() + " | ext: " + implementedExtCount());
        System.out.println("[api] data: " + core.DataPaths.root().toAbsolutePath());
        System.out.println("[api] danmaku api: " + (danmakuApiConfigured() ? "configured" : "not configured (using built-in hosts)"));
        /*
         * 说清 /spider-latest.jar 下发的是哪种形态。
         *
         * 这个端点同时服务 PC 插件和 Android 客户端，两者要的形态不同（class vs dex）。
         * 以前只有 class 版可发，电视台客户端拿到必然报「jar 里没有类」，
         * 而这种失败发生在客户端，后端日志里一点痕迹都没有——加这行省掉一轮猜。
         */
        File served = findSpiderJarForClient();
        if (served == null) {
            System.out.println("[api] spider jar: NOT FOUND (clients cannot load sites)");
        } else {
            boolean dex = hasClassesDex(served);
            System.out.println("[api] spider jar: " + served.getPath()
                    + " (" + (dex ? "dex — Android OK" : "class only — Android CANNOT load") + ")");
        }
    }

    /**
     * 初始化：参数已就绪、DataPaths 已可用之后跑。
     *
     * <p>JVM 的 {@code main} 与 Android 的 {@code GatewayServer.start} 都调它，
     * 这样站点表、多实例、健康状态、缓存清单的加载逻辑只有一份。
     */
    public static void boot() {
        /*
         * ★ 必须在 initAfterArgs() 之前设。
         *
         * 安全 DNS（safeDns）模式下，Scrape.httpConnection 会连 DoH 解析出的
         * 真实 IP，再把 Host 头设回原域名。HttpURLConnection 默认禁止改 Host，
         * 而它只在**类第一次初始化时**读一次 sun.net.http.allowRestrictedHeaders。
         *
         * initAfterArgs() 里 Jars.reload() 可能已经发起 HTTP（下载/校验 spider jar），
         * 一旦 HttpURLConnection 先初始化，这里再设就无效 → Host 被静默丢掉 →
         * 豆瓣 frodo 等按 Host 分流的站点返回 EOF/400，原生源表现为空列表。
         * 2026-11-11 OECT 实测：safeDns=true 时豆瓣 0 条，safeDns=false 正常。
         */
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        initAfterArgs();
        System.setProperty("http.agent", "okhttp/3.12.0");
        try {
            java.security.Security.addProvider((java.security.Provider) Class
                    .forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                    .getDeclaredConstructor().newInstance());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 起 HTTP 监听。JVM 与 Android 只在这一步分叉——由 {@code platform.ServerProvider}
     * 决定用哪种传输实现，之后的 {@link #handle} 完全相同。
     *
     * @param bind    监听地址；Android 侧固定 {@code 127.0.0.1}
     * @param port    端口
     * @param threads 工作线程数
     */
    public static platform.HttpServer listen(String bind, int port, int threads) throws Exception {
        platform.ServerProvider.autoDetect();
        platform.HttpServer server = platform.HttpServer.create(
                new InetSocketAddress(bind, port), 0);
        server.start(ApiServer::handle, threads);
        return server;
    }

    /**
     * CORS 策略：`any`（默认，不限制来源）或 `list`（只放行白名单）。
     *
     * <p><b>★ 默认不拦截</b>（2026-11-02 用户明确要求："默认不拦截"）。
     *
     * <p>为什么要可配而不是硬编码网段：部署形态太多了 —— 局域网、Tailscale、
     * ZeroTier、frp 反代、Cloudflare Tunnel、公网域名……靠 `isPrivateHost()`
     * 猜网段必然漏（用户就是被"Tailscale 的 100.x 不在 RFC1918 里"卡住的）。
     * 网络边界该由**部署者**声明，不该由代码猜。
     *
     * <p>读取顺序：环境变量 `MEOW_CORS` → policy.json 的 `corsOrigins` → 默认 `*`。
     * <ul>
     *   <li>`MEOW_CORS=*` 或未设置 → 任何 Origin 都放行（本机自用 / 内网部署）</li>
     *   <li>`MEOW_CORS=https://a.com,https://b.com` → 只放行这些（公网暴露时用）</li>
     *   <li>`MEOW_CORS=none` → 只放行本机与各端壳，其余全拦（最严）</li>
     * </ul>
     *
     * <p>注意：CORS 只防"浏览器里的第三方页面"。任何能直连端口的人本来就能
     * 用 curl 写，所以真正的访问控制是网络边界 + `MEOW_AUTH_TOKEN`（若需要）。
     */
    /** 从环境变量/policy 解析出 CORS 策略。返回 null 表示"any"。 */
    private static String[] corsAllowList() {
        String raw = System.getenv("MEOW_CORS");
        if (raw == null || raw.trim().isEmpty()) {
            // 兼容旧配置：policy.json 里显式写了 corsOrigins 才当白名单
            JSONArray p = loadPolicy().optJSONArray("corsOrigins");
            if (p == null || p.length() == 0) return null;   // 默认 any
            String[] out = new String[p.length()];
            for (int i = 0; i < p.length(); i++) out[i] = p.optString(i, "").trim().toLowerCase();
            return out;
        }
        String v = raw.trim();
        if ("*".equals(v) || "any".equalsIgnoreCase(v)) return null;
        if ("none".equalsIgnoreCase(v)) return new String[0];
        String[] parts = v.split(",");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : parts) {
            String t = s.trim().toLowerCase();
            if (!t.isEmpty()) out.add(t);
        }
        return out.toArray(new String[0]);
    }

    /**
     * 计算本次请求该发的 `Access-Control-Allow-Origin`。
     *
     * <p>返回 {@code null} = **不下发**这个头（浏览器会拦下跨源读取）。
     *
     * <p>这个函数同时是"来源是否可信"的**唯一判据**（{@link #originAllowed}
     * 直接复用它）。两份判据分开写会漂移 —— 2026-11-02 的
     * "Tailscale 部署全站 403" 就是漂移的结果。
     *
     * <p>策略见 {@link #corsAllowList()}：默认 `*`（不限制）。
     */
    private static String corsAllowedOrigin(String origin) {
        if (origin == null || origin.isEmpty()) return "*";   // 非浏览器，不受约束
        String o = origin.trim().toLowerCase();
        String[] allow = corsAllowList();
        // null = 默认放行（本机自用 / 内网部署，用户明确要求"默认不拦截"）
        if (allow == null) return origin;
        // 空数组 = none：只放行本机与各端壳
        // 桌面壳：Tauri 在 Windows 用 http://tauri.localhost，其它平台用 tauri://localhost
        if (o.equals("tauri://localhost") || o.equals("http://tauri.localhost")
                || o.equals("https://tauri.localhost")) return origin;
        // file:// 页面（TV WebView / 本地 HTML 壳）
        if (o.equals("null")) return origin;
        /*
         * TV 内置页（WebViewAssetLoader）与 meow.local 永远放行：
         * 它们的来源既不是 http(s) 也不是任何"部署域名"，写进白名单也没法枚举。
         */
        if (o.startsWith("http://") || o.startsWith("https://")) {
            String host = hostOf(o);
            if (host.equals("localhost") || host.equals("127.0.0.1")
                    || host.equals("[::1]") || host.equals("::1")
                    || host.equals("meow.local")) {
                return origin;
            }
            if (host.equals("appassets.androidplatform.net")
                    || host.endsWith(".androidplatform.net")) return origin;
        }
        for (String x : allow) {
            if (!x.isEmpty() && x.equals(o)) return origin;
        }
        System.out.println("[cors] blocked origin " + origin);
            core.LogBuffer.warn("cors", "blocked origin " + core.LogBuffer.scrub(origin));
        return null;
    }

    /**
     * 从 `scheme://host[:port]` 里取出 host。
     *
     * <p>★ 原来用 `lastIndexOf(':')`，IPv6 字面量会被切坏：
     * `http://[::1]:30010` → `[::1]` 侥幸对，但 `http://[::1]`（无端口）
     * → `[::` 就错了。改成"先看有没有括号，有就取到右括号为止"。
     */
    private static String hostOf(String originLower) {
        int p = originLower.indexOf("://");
        if (p < 0) return originLower;
        String hostPort = originLower.substring(p + 3);
        // 去掉可能的 userinfo（Origin 头不会带，防御性处理）
        int at = hostPort.lastIndexOf('@');
        if (at >= 0) hostPort = hostPort.substring(at + 1);
        if (hostPort.startsWith("[")) {
            int end = hostPort.indexOf(']');
            return end > 0 ? hostPort.substring(0, end + 1) : hostPort;
        }
        int colon = hostPort.lastIndexOf(':');
        return colon > 0 ? hostPort.substring(0, colon) : hostPort;
    }

    /**
     * 主机名是否落在「内网可达」的地址族。
     *
     * <p><b>⚠ 已不是 CORS 判据</b>（2026-11-02）。CORS 现在按 {@link #corsAllowList()}
     * 的策略走，**默认完全不拦**，不再靠猜网段。
     *
     * <p>为什么删掉"猜网段"这条路：部署形态太多 —— 局域网、Tailscale、ZeroTier、
     * frp 反代、Cloudflare Tunnel、公网域名，枚举必然漏（用户就是被
     * "Tailscale 的 100.x 不属于 RFC1918" 卡住的）。网络边界由部署者声明，
     * 不该由代码猜。
     *
     * <p>这里保留给"需要判断某个地址是不是内网"的场景（例如 SSRF 校验），
     * 不参与 Origin 白名单。调用方只应关心"这个地址能否从公网直达"。
     */
    private static boolean isPrivateHost(String host) {
        String h = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home")) return true;
        if (h.endsWith(".ts.net") || h.endsWith(".tailscale.net")) return true;
        if (h.equals("appassets.androidplatform.net")) return true;
        if (h.endsWith(".androidplatform.net")) return true;
        String[] p = h.split("\\.");
        if (p.length != 4) return false;
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                n[i] = Integer.parseInt(p[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (n[i] < 0 || n[i] > 255) return false;
        }
        // 10/8、192.168/16、172.16/12、169.254/16（链路本地，直连时常见）
        if (n[0] == 10) return true;
        if (n[0] == 192 && n[1] == 168) return true;
        if (n[0] == 172 && n[1] >= 16 && n[1] <= 31) return true;
        if (n[0] == 169 && n[1] == 254) return true;
        // 100.64/10（CGNAT）：Tailscale / ZeroTier / 运营商级 NAT
        if (n[0] == 100 && n[1] >= 64 && n[1] <= 127) return true;
        return false;
    }

    /** 按来源重算一次 CORS 头（媒体/图片/jar 这些分支自己 set 头会覆盖全局策略）。 */
    private static void applyCors(HttpExchange ex) {
        String allow = corsAllowedOrigin(ex.getRequestHeaders().getFirst("Origin"));
        if (allow == null) ex.getResponseHeaders().remove("Access-Control-Allow-Origin");
        else ex.getResponseHeaders().set("Access-Control-Allow-Origin", allow);
    }

    // ---------------------------------------------------------------- 限流

    /**
     * 固定窗口计数：key = 桶名|IP，value = {窗口起点毫秒, 次数}。
     *
     * <p>为什么不用令牌桶：这里要挡的是"被刷"，不是给单个客户端做平滑；
     * 固定窗口的实现只有两个数，出问题时也能一眼从日志算出配额。
     */
    private static final ConcurrentHashMap<String, long[]> RATE_BUCKETS = new ConcurrentHashMap<>();

    /** policy.json 没配时的默认值：一次播放的 Range 请求通常个位数，给足余量。 */
    private static int rateLimitDefault(String bucket) {
        return bucket.equals("parse") ? 30 : 240;
    }

    /** 0 或负数 = 关闭该桶限流（自建环境不需要时可在 policy.json 关掉）。 */
    private static int rateLimitFor(String bucket) {
        try {
            JSONObject rl = loadPolicy().optJSONObject("rateLimit");
            if (rl == null) return rateLimitDefault(bucket);
            int v = rl.optInt(bucket, rateLimitDefault(bucket));
            return v;
        } catch (Throwable t) {
            return rateLimitDefault(bucket);
        }
    }

    private static long rateWindowMs() {
        try {
            JSONObject rl = loadPolicy().optJSONObject("rateLimit");
            int sec = rl == null ? 10 : rl.optInt("windowSec", 10);
            return Math.max(1, sec) * 1000L;
        } catch (Throwable t) {
            return 10_000L;
        }
    }

    /**
     * 调用方 IP。
     *
     * <p>默认只认 socket 地址：X-Forwarded-For 谁都能伪造，直接信等于把限流交给攻击者。
     * 部署在 nginx 后面（Docker compose 的默认形态）时可在 policy.json 打开
     * {@code trustProxy:true}，此时取 XFF 的**最后一跳**——那是 nginx 亲眼看得到的地址；
     * 取第一跳会拿到客户端自己填的值。
     */
    private static String clientIp(HttpExchange ex) {
        try {
            if (loadPolicy().optBoolean("trustProxy", false)) {
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

    /** 超过配额返回 true。窗口满时顺带清理过期条目，避免长期运行堆无限涨。 */
    private static boolean rateLimited(String ip, String bucket, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        String key = bucket + "|" + ip;
        long[] cell = RATE_BUCKETS.computeIfAbsent(key, k -> new long[]{now, 0});
        synchronized (cell) {
            if (now - cell[0] >= windowMs) {
                cell[0] = now;
                cell[1] = 0;
            }
            cell[1]++;
            if (cell[1] > limit) return true;
        }
        if (RATE_BUCKETS.size() > 4096) {
            for (Map.Entry<String, long[]> e : RATE_BUCKETS.entrySet()) {
                long[] v = e.getValue();
                boolean stale;
                synchronized (v) {
                    stale = now - v[0] >= windowMs;
                }
                if (stale) RATE_BUCKETS.remove(e.getKey(), v);
            }
        }
        return false;
    }

    /**
     * 期望的写操作令牌。**环境变量优先于 policy.json**。
     *
     * <p>为什么要支持环境变量（2026-10-30）：容器化部署的惯例是"配置从
     * 环境注入"，改一个密钥不该要求 `docker exec` 进容器写文件再重启。
     * 与本文件里 `MEOW_DANMAKU_API` / `MEOW_JS_RUNTIME` 同一套优先级：
     * 环境变量非空则用它，否则回落 policy.json。
     *
     * <p>两处都返回空串 = 鉴权关闭（默认）。这是刻意的默认值：
     * 只监听 127.0.0.1 的单机自用不需要令牌，设了反而给自己添麻烦。
     */
    private static String authTokenExpected() {
        String env = System.getenv("MEOW_AUTH_TOKEN");
        if (env != null && !env.trim().isEmpty()) return env.trim();
        return loadPolicy().optString("authToken", "");
    }

    /** 哪些写操作需要 token。GET/HEAD 永不拦。 */
    private static boolean writeRequiresAuth(String method, String path) {
        if (authTokenExpected().isEmpty()) return false;
        String m = method == null ? "" : method.toUpperCase();
        if (m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS")) return false;
        return path.startsWith("/api/state/")
                || path.equals("/api/config/import")
                || path.equals("/api/lives")
                || path.equals("/api/policy")
                || path.equals("/api/webdav")
                || path.startsWith("/api/library")
                || path.startsWith("/api/cache")
                || path.startsWith("/api/health/")
                || path.startsWith("/api/transcode")
                /*
                 * 下面这几个原来漏了（2026-10-30 安全审计 H15，实测都能匿名成功）：
                 * 清日志 / 清海报缓存 / 清刮削缓存 / 清搜索缓存都是写操作；
                 * /api/llm/relay 会带着用户的 apiKey 往任意 URL 发请求；
                 * /api/cast 能对内网任意地址发 SOAP。
                 */
                || path.equals("/api/llm/relay")
                || path.equals("/api/cast")
                || path.equals("/api/logs")
                || path.equals("/api/posters")
                || path.equals("/api/scrape")
                || path.equals("/api/search/cache");
    }

    /**
     * 这个跨源来源能不能对这个后端发起写请求。
     *
     * <p><b>为什么需要它</b>（2026-10-30 安全审计 H2）：所有写端点都接受
     * `text/plain` 且不校验 Origin，于是用户只要用浏览器打开一个恶意页面，
     * 那个页面就能 `POST /api/policy {"corsAllowAny":true}` 打开 CORS、
     * 改片库、写收藏 —— 全是**盲写**，不需要读响应。更糟的是
     * `/api/cache/export-to` 只判"远端是回环"，而 CSRF 请求的远端**正是**
     * 127.0.0.1，那道门形同虚设（实测能在本机任意目录建文件）。
     *
     * <p>判据不是"伪造 Origin"（伪造不了，浏览器只会填自己的），而是
     * **所有跨源页面发起的写请求**：它们的 Origin 一定不在这里。
     * 本机脚本 / curl / 应用自身都不带 Origin，直接放行。
     */
    private static boolean originAllowed(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.trim().isEmpty()) return true;   // 非浏览器
        String o = origin.trim().toLowerCase();
        // 默认策略是"完全不拦"（MEOW_CORS 未设置或为 *），直接放行。
        if (corsAllowList() == null) return true;
        /*
         * 配了白名单时才逐条判。判据与 CORS 下发共用同一个函数 ——
         * 两份手抄白名单必然漂移（2026-11-02：写门禁连 192.168 都没放行，
         * 局域网/隧道部署时首页第一个 POST 就 403，整页空白）。
         *
         * 注意 CORS 只防"浏览器里的第三方页面"；能直连端口的人本来就能 curl，
         * 真正的访问控制是网络边界与本函数之外的 MEOW_AUTH_TOKEN。
         */
        return corsAllowedOrigin(o) != null;
    }

    private static boolean authTokenMatches(HttpExchange ex) throws IOException {
        String expect = authTokenExpected();
        if (expect.isEmpty()) return true;
        String got = ex.getRequestHeaders().getFirst("X-Meow-Token");
        if (got == null || got.isEmpty()) {
            Map<String, String> q = query(ex);
            got = q.getOrDefault("token", "");
        }
        // 定长比较，避免用长度差做侧信道（本地威胁模型下意义有限，但不费事）
        if (got.length() != expect.length()) return false;
        int diff = 0;
        for (int i = 0; i < expect.length(); i++) diff |= expect.charAt(i) ^ got.charAt(i);
        return diff == 0;
    }

    // ---------------------------------------------------------------- routing

    private static void handle(HttpExchange ex) throws IOException {
        Headers h = ex.getResponseHeaders();
        // CORS 收紧（docs/25 S3）。默认白名单只放三类来源：
        //   1) 同源/无 Origin（curl、TV、同源页面）——不需要 CORS 头；
        //   2) 本机开发：localhost / 127.0.0.1 任意端口（vite dev 用）；
        //   3) 桌面壳：tauri:// 与 tauri.localhost。
        // 其余来源一律**不下发** Allow-Origin，浏览器自然拦掉——恶意网页
        // 没法再借用户浏览器读写 localhost 后端。局域网 Web 部署如果要跨端口
        // 访问，在 policy.json 的 corsOrigins 里显式加，或 corsAllowAny=true。
        String origin = ex.getRequestHeaders().getFirst("Origin");
        String allowOrigin = corsAllowedOrigin(origin);
        if (allowOrigin != null) {
            h.add("Access-Control-Allow-Origin", allowOrigin);
            if (!"*".equals(allowOrigin)) h.add("Vary", "Origin");
        }
        // DELETE 必须在这里列出来：浏览器对 DELETE 一定先发预检，不在列表里
        // 收藏删除会被 CORS 挡在预检阶段（请求根本到不了后端）。
        h.add("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
        h.add("Access-Control-Allow-Headers", "Content-Type,Range,X-Meow-Token");
        h.add("Access-Control-Expose-Headers",
                "Content-Length,Content-Range,Accept-Ranges,"
                        + "X-Danmaku-Source,X-Danmaku-Count,X-Danmaku-Empty");

        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        // 访问日志：TV/PC 排查"配了地址但没反应"时，先看这里有没有请求进来。
        // 只打路径不打 query（弹幕/代理的 query 里有长 URL，会淹没日志）。
        System.out.println("[req] " + method + " " + path);
        // 同一份写进环形缓冲，供网页「日志」页签读取。
        // health/日志自身这类高频轮询不记：它们几秒一次，会把有用的错误冲掉。
        if (!routes.LogRoutes.isNoisyPath(path)) {
            core.LogBuffer.info("req", method + " " + core.LogBuffer.scrub(path));
        }
        if ("OPTIONS".equalsIgnoreCase(method)) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        // 代理与解析端点的 query 完全由调用方给，等于半个开放正向代理；
        // 被刷时先耗尽的是源站配额和我们的线程池，所以在路由前就挡住。
        if (path.equals("/api/parse") || path.startsWith("/api/proxy/")) {
            boolean isParse = path.equals("/api/parse");
            int limit = rateLimitFor(isParse ? "parse" : "proxy");
            if (limit > 0 && rateLimited(clientIp(ex), isParse ? "parse" : "proxy", limit, rateWindowMs())) {
                sendJson(ex, 429, "{\"code\":-1,\"msg\":\"too many requests\"}");
                return;
            }
        }

        // 写操作鉴权（docs/25 S2）。默认 authToken 为空 = 关闭，保持既有部署
        // 可用；在 policy.json 里设 authToken 之后，所有写端点都要求
        // X-Meow-Token（或 ?token=）。GET 一律不拦——进度读取在每次播放都会
        // 发生，给它加 key 只会让人把 token 写进 URL 日志里。
        if (writeRequiresAuth(method, path) && !authTokenMatches(ex)) {
            sendJson(ex, 401, "{\"code\":-1,\"msg\":\"unauthorized: X-Meow-Token required\"}");
            return;
        }

        /*
         * 跨源写请求一律拒绝（2026-10-30 安全审计 H2）。
         *
         * 上面那条令牌检查在**没配 token 时直接放行**，所以默认部署（token 为空）
         * 对 CSRF 完全无防护：恶意页面可以盲写 policy/片库/收藏，
         * 还能借本机回环身份触发 `/api/cache/export-to` 在任意目录写文件。
         *
         * 这条对所有写方法生效，与 token 是否配置无关 —— 它挡的是
         * "浏览器里的第三方页面"，不是"没带令牌的合法客户端"。
         */
        String httpMethod = method == null ? "" : method.toUpperCase();
        boolean isWrite = !(httpMethod.equals("GET") || httpMethod.equals("HEAD")
                || httpMethod.equals("OPTIONS"));
        if (isWrite && !originAllowed(ex)) {
            sendJson(ex, 403, "{\"code\":-1,\"msg\":\"cross-origin write blocked\"}");
            return;
        }

        try {
            if ("/api/health".equals(path)) {
                sendJson(ex, 200, healthJson());
                return;
            }
            // 健康度：主动探活 + 手动降权 + 明细。
            // 三条都必须显式注册：否则 /api/health/probe 会被下面的站点分派
            // 当成 site="health"、action="probe" 去加载一个不存在的 spider，
            // 报出 "cannot load spider health" 这种把人带偏的错误。
            if ("/api/health/probe".equals(path)) {
                String body = read(ex.getRequestBody());
                sendJson(ex, 200, probeSites(body));
                return;
            }
            if ("/api/health/degrade".equals(path)) {
                handleHealthDegrade(ex);
                return;
            }
            if ("/api/health/state".equals(path)) {
                sendJson(ex, 200, healthStateJson());
                return;
            }
            /*
             * 重置源健康（清掉跳过/降档/降级标记）。
             *
             * ★ 必须放在 `/api/health/` 前缀兜底之前 —— 否则会被那条
             *   404 分支吃掉（路由顺序敏感，见 AGENTS §4.3）。
             *
             * 为什么需要它：失效剔除和超时拉黑的 TTL 是 10 分钟，
             * 期间用户即使修好了源站也看不到效果。有个"立即重来"的入口，
             * 排查时不用重启后端。
             */
            if ("/api/health/reset".equals(path)) {
                routes.HealthRoutes.resetAll();
                sendJson(ex, 200, "{\"ok\":true}");
                return;
            }
            /*
             * 搜索结果缓存：GET 看统计，DELETE 清空。
             *
             * 清空的需求很实际：缓存 TTL 是有结果的 2 小时，
             * 用户在别处修了源、或者只是想立刻看全量，需要能主动丢弃。
             */
            if ("/api/search/cache".equals(path)) {
                if ("DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
                    int n = SEARCH_CACHE.clear();
                    core.LogBuffer.info("search", "手动清空结果缓存 " + n + " 条");
                    sendJson(ex, 200, "{\"ok\":true,\"cleared\":" + n + "}");
                } else {
                    sendJson(ex, 200, SEARCH_CACHE.statsJson());
                }
                return;
            }
            if (path.startsWith("/api/health/")) {
                sendJson(ex, 404, "{\"code\":-1,\"msg\":\"unknown health endpoint\"}");
                return;
            }
            if ("/api/sites".equals(path)) {
                sendJson(ex, 200, sitesJson());
                return;
            }
            if ("/api/config".equals(path)) {
                sendJson(ex, 200, configJson());
                return;
            }
            // 显式注册必须排在前面的逐条 equals 表里：否则 /api/config/import 会落到
            // 下面的站点分派，被当成 site="config"、action="import" 去调一个不存在的类。
            if ("/api/config/import".equals(path)) {
                handleConfigImport(ex);
                return;
            }
            /*
             * 多仓探测：给一个索引地址，列出里面每个仓**能不能用**。
             *
             * 为什么要单独一个端点而不是让前端逐个 import：导入是有副作用的
             * （会覆盖本地配置），用户还没决定用哪个仓之前不能碰现有配置。
             * 这里只发 GET、只读，返回每个仓的可达性与站点构成，
             * 前端据此让用户挑一个，再调 /api/config/import 带 url 导入。
             *
             * 与 import 一样必须写在逐条 equals 表里：否则会落到
             * /api/{site}/{action} 被当成 site="config" 去加载类。
             */
            if ("/api/config/depot".equals(path)) {
                handleConfigDepot(ex);
                return;
            }
            /*
             * 配置备份：查 / 恢复。与 /api/config/import 同一族。
             *
             * DELETE = 丢弃备份（用户确认新配置没问题了）；
             * POST   = 用备份覆盖回来（撤销上一次导入）。
             */
            if ("/api/config/backup".equals(path)) {
                String m = ex.getRequestMethod();
                if ("DELETE".equalsIgnoreCase(m)) {
                    boolean gone = false;
                    try {
                        gone = java.nio.file.Files.deleteIfExists(
                                core.DataPaths.file(CONFIG_BACKUP).toPath());
                    } catch (Exception ignored) {
                    }
                    sendJson(ex, 200, new JSONObject().put("ok", true)
                            .put("removed", gone)
                            .put("backup", configBackupInfo()).toString());
                    return;
                }
                if ("POST".equalsIgnoreCase(m)) {
                    if (!configBackupInfo().optBoolean("exists")) {
                        sendJson(ex, 404, "{\"code\":-1,\"msg\":\"没有可恢复的备份\"}");
                        return;
                    }
                    boolean ok = restoreConfigBackup();
                    sendJson(ex, ok ? 200 : 500, new JSONObject().put("ok", ok)
                            .put("sites", siteCount())
                            .put("backup", configBackupInfo()).toString());
                    return;
                }
                sendJson(ex, 200, new JSONObject().put("ok", true)
                        .put("backup", configBackupInfo()).toString());
                return;
            }
            // CatVod 出口：Fongmi / motao123-webtv / TVBox 直接把这个地址填进"配置地址"。
            // 与 /api/config 并存——那份是给自家前端的能力协商，形状不同不要合并。
            if ("/config.json".equals(path)) {
                sendJson(ex, 200, catVodConfigJson(ex));
                return;
            }
            if ("/spider-latest.jar".equals(path)) {
                handleSpiderJar(ex);
                return;
            }
            if ("/api/policy".equals(path)) {
                handlePolicy(ex);
                return;
            }
            if ("/api/dns/resolve".equals(path)) {
                handleDnsResolve(ex);
                return;
            }
            if ("/api/search".equals(path)) {
                String body = read(ex.getRequestBody());
                /*
                 * 带 `async:true` → 建后台任务立刻返回 taskId，前端再轮询快照。
                 * 不带则走原来的同步路径（老客户端/自动化脚本继续可用）。
                 *
                 * ★ 为什么需要异步：请求线程池只有 12 个（POOL），一次同步搜索
                 *   要占住一个线程 2~20 秒。实测 12 个并发搜索就把池占满，
                 *   `/api/health` 从毫秒级涨到 9.1 秒 —— 表现是"整个应用卡死"。
                 */
                if (body != null && body.contains("\"async\"")) {
                    JSONObject rq;
                    try {
                        rq = new JSONObject(body);
                    } catch (Exception e) {
                        rq = new JSONObject();
                    }
                    if (rq.optBoolean("async", false)) {
                        sendJson(ex, 200, startSearchTask(body));
                        return;
                    }
                }
                sendJson(ex, 200, searchAll(body));
                return;
            }
            // 轮询搜索进度 / 取部分结果。路径必须在 /api/{site}/{action} 兜底之前，
            // 否则会被当成 site="search"、action="<taskId>" 去调站点类。
            if (path.startsWith("/api/search/task/")) {
                String id = path.substring("/api/search/task/".length());
                SearchTask t = SEARCH_TASKS.get(id);
                if (t == null) {
                    sendJson(ex, 404, "{\"code\":-1,\"msg\":\"task not found\"}");
                } else {
                    sendJson(ex, 200, taskJson(t, true));
                }
                return;
            }
            // 解析器端点。type 1 是"调外部 jx 接口拿 JSON"，type 4 是"并行跑所有
            // type 1 取最先成功的"——两者都不需要浏览器，所以能在这里同步完成。
            // type 0（WebView 嗅探）/ 2 / 3 需要浏览器或站点类实现，明确报不支持。
            if ("/api/parse".equals(path)) {
                handleParse(ex);
                return;
            }
            /*
             * 直播源（config.lives 表）的增删查。
             *
             * 为什么不搭 /api/config/import 的车：直播源的生命周期和点播站点
             * 完全不同（m3u 每天变，站点几个月不动），依赖也不同（不需要 jar
             * 里有对应类、不需要 rebuildSites，只要能把列表解析成频道）。
             * 混在整份配置导入里，用户想加一条源就得先找到一份带 lives 的
             * 完整配置，代价是整张站点表被换掉。
             *
             * 顺序放 /api/live 前面只是习惯；两者路径不同，不会互相吞掉。
             */
            if ("/api/lives".equals(path)) {
                handleLives(ex);
                return;
            }
            // 直播聚合：并发问所有站点要 liveContent，外加 config.lives 里的直连播放列表
            if ("/api/live".equals(path)) {
                String body = read(ex.getRequestBody());
                sendJson(ex, 200, liveAll(body));
                return;
            }
            // 解析一条 IPTV 播放列表（m3u / txt）。生态里直播绝大多数是这种分发方式，
            // 而不是 spider 的 liveContent（实测我们 jar 里 72 个类**零个**实现它）。
            if ("/api/live/playlist".equals(path)) {
                String body = read(ex.getRequestBody());
                sendJson(ex, 200, livePlaylist(body));
                return;
            }
            if ("/api/proxy/danmaku/search".equals(path)) {
                handleDanmakuSearch(ex);
                return;
            }
            if ("/api/proxy/danmaku/list".equals(path)) {
                handleDanmakuList(ex);
                return;
            }
            if ("/api/proxy/danmaku/xml".equals(path)) {
                handleDanmakuXml(ex);
                return;
            }
            /*
             * 弹幕预取（2026-11-02）：片尾前把下一集的弹幕先抓好，切集时 0ms。
             *
             * 必须排在 `/api/proxy/danmaku` 的精确匹配**之后**（它俩并列不是前缀关系，
             * 顺序无所谓），但要排在所有 startsWith 兜底之前 —— 否则会被
             * 站点分派当成 site=proxy 去调类（见 §4.3 的路由顺序纪律）。
             */
            if ("/api/proxy/danmaku/prefetch".equals(path)) {
                handleDanmakuPrefetch(ex);
                return;
            }
            if ("/api/proxy/danmaku".equals(path)) {
                handleDanmaku(ex);
                return;
            }
            if ("/api/proxy/stream".equals(path)) {
                /*
                 * 分片代理必须开闸：一个慢分片会把 handleStream 的线程占住最长
                 * 45 秒（connect 15s + read 30s），而 HTTP 服务端只有 16 个线程
                 * （TV 网关 24）。慢源并发一多，连 /api/health 都要排队。
                 * 宁可直接 503 让播放器重试，也不让整台后端跟着卡。
                 */
                if (!STREAM_SLOTS.tryAcquire()) {
                    sendText(ex, 503,
                            "{\"code\":-1,\"busy\":true,\"msg\":\"分片代理繁忙，请重试\"}",
                            "application/json; charset=utf-8");
                    return;
                }
                try {
                    handleStream(ex);
                } finally {
                    STREAM_SLOTS.release();
                }
                return;
            }
            if ("/api/proxy/image".equals(path)) {
                handleImage(ex);
                return;
            }
            if ("/api/webdav".equals(path)) {
                routes.LibraryRoutes.handleWebdav(ex);
                return;
            }
            // 本地 / NAS 片库。独立于 /api/proxy/*：那条通道接受任意 URL，
            // 片库只访问配置里声明过的那个根，路径由服务端拼接（见 core.Library）。
            if ("/api/library".equals(path)) {
                routes.LibraryRoutes.handleLibrary(ex);
                return;
            }
            if ("/api/library/stream".equals(path)) {
                routes.LibraryRoutes.handleLibraryStream(ex);
                return;
            }
            if (path.startsWith("/api/library/")) {
                sendJson(ex, 404, "{\"code\":-1,\"msg\":\"unknown library endpoint\"}");
                return;
            }
            // 离线缓存：断网可看的关键一环（分片落盘 + 本地清单）
            //
            // 三个子路径必须排在下面的 /api/cache/ 兜底**之前**：兜底的
            // handleCacheFile 把剩余段当成 "{id}/{file}"，`/api/cache/batch`
            // 会被它解析成 id="batch" 且没有文件名，回 404——
            // 前端会看到"批量下载接口不存在"这种假故障。
            if ("/api/cache/batch".equals(path)) {
                routes.CacheRoutes.handleCacheBatch(ex);
                return;
            }
            if ("/api/cache/status".equals(path)) {
                routes.CacheRoutes.handleCacheStatus(ex);
                return;
            }
            if ("/api/cache/tasks".equals(path)) {
                routes.CacheRoutes.handleCacheTasks(ex);
                return;
            }
            if ("/api/cache/resume".equals(path)) {
                routes.CacheRoutes.handleCacheResume(ex);
                return;
            }
            // 导出到本机/U盘：把一集缓存回流成单个文件。
            // 必须排在 "/api/cache/" 前缀分支之前，否则会被 handleCacheFile
            // 当成「id=export、文件名缺失」回 404（与 batch 同一个坑）。
            if ("/api/cache/export".equals(path)) {
                routes.CacheRoutes.handleCacheExport(ex);
                return;
            }
            // PC 端本机直存：后端与客户端同机，直接把合并后的文件写到目标路径，
            // 不走「下载一遍再保存」的弯路（也避免浏览器/WebView 落盘能力差异）。
            if ("/api/cache/export-to".equals(path)) {
                routes.CacheRoutes.handleCacheExportTo(ex);
                return;
            }
            if ("/api/cache".equals(path)) {
                routes.CacheRoutes.handleCache(ex);
                return;
            }
            /*
             * 缓存任务控制 / 按剧聚合 / 本地海报。
             *
             * ★ 三条都必须排在 `/api/cache/` 前缀兜底**之前** —— 否则会被
             *   handleCacheFile 当成"取某个 id 的文件"（AGENTS §4.3）。
             */
            if ("/api/cache/control".equals(path)) {
                routes.CacheRoutes.handleCacheControl(ex);
                return;
            }
            if ("/api/cache/grouped".equals(path)) {
                sendJson(ex, 200, new JSONObject()
                        .put("ok", true)
                        .put("items", core.VideoCache.grouped())
                        .put("totalBytes", core.VideoCache.totalBytes())
                        .toString());
                return;
            }
            if ("/api/cache/poster".equals(path)) {
                routes.CacheRoutes.handleCachePoster(ex);
                return;
            }
            if (path.startsWith("/api/cache/")) {
                routes.CacheRoutes.handleCacheFile(ex);
                return;
            }
            // 运行日志：网页「日志」页签读这里。
            // 必须写在显式路由表里——落到 /api/{site}/{action} 兜底会被
            // 当成站点名 logs 去加载类，报 ClassNotFoundException（信息误导人）。
            if ("/api/logs".equals(path)) {
                routes.LogRoutes.handle(ex);
                return;
            }
            // 模型转发（可选）：纯 Web 没有壳可代发请求，而厂商不返回 CORS 头，
            // 浏览器直连必被挡。这里只转发不编排，默认关闭（policy.llmRelay.enabled）。
            // ★ 必须在站点分派之前显式注册：落到 /api/{site}/{action} 会被当成
            //   site="llm" 去加载类，报 ClassNotFoundException。
            if ("/api/llm/relay".equals(path)) {
                routes.LlmRelay.handle(ex, loadPolicy());
                return;
            }
            // 刮削：按片名补海报/年份/豆瓣 id
            if ("/api/scrape".equals(path)) {
                handleScrape(ex);
                return;
            }
            // 海报磁盘缓存：统计 / 清空（设置页"数据与维护"用）
            if ("/api/posters".equals(path)) {
                if ("DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendJson(ex, 200, new JSONObject()
                            .put("ok", core.PosterStore.clear()).toString());
                } else {
                    sendJson(ex, 200, core.PosterStore.stats().toString());
                }
                return;
            }
            // 投屏：DLNA 发现渲染器 + AVTransport 控制
            if ("/api/cast".equals(path)) {
                handleCast(ex);
                return;
            }
            // 按需转码：浏览器放不了的格式（MKV/HEVC）现转 HLS，不动源文件
            if ("/api/transcode".equals(path)) {
                handleTranscode(ex);
                return;
            }
            if (path.startsWith("/api/transcode/")) {
                handleTranscodeFile(ex);
                return;
            }
            // 出口 C：三端共享状态。同样必须显式注册——否则 /api/state/progress
            // 会被下面的站点分派当成 site="state"、action="progress"。
            if ("/api/state/progress".equals(path)) {
                routes.StateRoutes.handleStateProgress(ex);
                return;
            }
            if ("/api/state/favorites".equals(path)) {
                routes.StateRoutes.handleStateFavorites(ex);
                return;
            }
            if ("/api/state/prefs".equals(path)) {
                routes.StateRoutes.handleStatePrefs(ex);
                return;
            }
            // /api/state 是保留前缀：拼错的子路径必须在这里 404，否则会掉进
            // 下面的站点兜底被当成 site="state"，报出 "cannot load spider state:
            // ClassNotFoundException" 这种误导人的 500（实测）。
            if (path.startsWith("/api/state/")) {
                sendJson(ex, 404, "{\"code\":-1,\"msg\":\"unknown state endpoint\"}");
                return;
            }
            // 这不是普通代理，是 jar 内 Proxy 类探测本地宿主的握手端点：
            // Proxy 的私有静态字段 a（端口）默认 -1，首次 getPort() 时遍历 9978-9999
            // 请求 {origin}/proxy?do=ck，只有正文恰为 "ok" 才把端口记成真实值。
            // 探不到就停在 -1，Hxq/Bili 的 play 便产出 http://127.0.0.1:-1/proxy?do=xxx。
            // 路径是 /proxy 而非 /api/proxy，且必须排在下面的 /api/proxy/ 兜底之前。
            if ("/proxy".equals(path)) {
                handleJarProxy(ex);
                return;
            }
            if (path.startsWith("/api/proxy/")) {
                sendJson(ex, 501, "{\"code\":-1,\"msg\":\"proxy not implemented\"}");
                return;
            }
            if (path.startsWith("/api/")) {
                String[] seg = path.split("/");
                if (seg.length >= 4) {
                    String site = URLDecoder.decode(seg[2], "UTF-8");
                    String action = seg[3];
                    String body = read(ex.getRequestBody());
                    sendJson(ex, 200, call(site, action, body));
                    return;
                }
            }
            sendJson(ex, 404, "{\"code\":-1,\"msg\":\"not found\"}");
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            System.err.println("[api] " + path + " failed: " + t);
            System.err.println(sw);
            sendJson(ex, 500, "{\"code\":-1,\"msg\":" + JSONObject.quote(String.valueOf(t)) + "}");
        }
    }

    // ---------------------------------------------------------------- health

    /**
     * 健康检查。
     *
     * <p>v1 只回 {@code {"ok":true}}，对 Docker HEALTHCHECK 和前端能力协商都没用。
     * 这里返回真实状态，前端据此决定降级（docs/26 §4.1、§9.2）。
     *
     * <p>判定 {@code ok} 的条件：HTTP 服务活着 且 spider jar 已加载 且 站点表非空。
     * 单个源站失效不影响 {@code ok}——那是健康状态机的事（阶段 4）。
     */
    private static String healthJson() {
        JSONObject o = new JSONObject();
        try {
            boolean spiderJar = spiderJarPresent();
            boolean configLoaded = siteCount() > 0;

            JSONArray caps = new JSONArray();
            if (danmakuApiConfigured()) caps.put("danmakuExternal");
            caps.put("danmaku");
            caps.put("search");
            caps.put("proxy");
            caps.put("webdav");
            caps.put("dns");
            caps.put("state");
            caps.put("parse");
            // 尚未实现的能力：live / multiDepot（前端据此隐藏 UI，别虚报）

            /*
             * ok 的判据不能只看 jar。
             *
             * 踩过（不内置 jar 的形态）：没有 spider.jar 但注册了原生源时，
             * 后端明明能出榜单/详情，health 却回 ok=false —— 前端据此把整个
             * 后端当成"没起来"，首页直接报错。原生源就是为了"没 jar 也能用"
             * 而存在的，所以它必须能把 ok 抬起来。
             */
            boolean hasNative = sources.NativeSources.size() > 0;
            o.put("ok", (spiderJar || hasNative) && configLoaded);
            o.put("nativeSources", sources.NativeSources.size());
            o.put("version", VERSION);
            o.put("capabilities", caps);
            o.put("sites", siteCount());
            o.put("ext", implementedExtCount());
            o.put("spiderJar", spiderJar);
            o.put("configLoaded", configLoaded);
            o.put("danmakuApi", danmakuApiConfigured() ? "configured" : "builtin");
            /*
             * 鉴权是否开启 + 来源。只暴露布尔与来源名，**绝不回显令牌本身**。
             *
             * 为什么需要暴露：容器部署时令牌从环境变量注入，用户改完 compose
             * 重启后没有办法确认"到底生效了没" —— 前端只看得到写操作被拒，
             * 分不清是没生效还是令牌填错了。给个来源就能一眼分清。
             */
            String tok = authTokenExpected();
            o.put("authRequired", !tok.isEmpty());
            o.put("authSource", tok.isEmpty() ? "none"
                    : (System.getenv("MEOW_AUTH_TOKEN") != null
                       && !System.getenv("MEOW_AUTH_TOKEN").trim().isEmpty()) ? "env" : "policy");
            JSONArray disabled = new JSONArray();
            for (Map.Entry<String, routes.HealthRoutes.SiteHealth> e : routes.HealthRoutes.all().entrySet()) {
                // 手动禁用单独列：前端要能画"已停用"徽标并在设置页给出恢复按钮。
                if (e.getValue().disabled) disabled.put(e.getKey());
            }
            o.put("disabled", disabled);
            o.put("disabledCount", disabled.length());
            /*
             * degraded / unsupported 交给 HealthRoutes.putSummary 拼：那边会把
             * "环境不支持"（drpy 没接运行时、ext 为空）从"源站反复失败"里分出来。
             * 这里自己拼过一次，结果 222 个 drpy 源全进了 degraded，
             * 一个配错的表就让 /api/health 报"244/270 降级"，看着像全线崩溃。
             */
            routes.HealthRoutes.putSummary(o);
            o.put("dataDir", core.DataPaths.root().toAbsolutePath().toString());
            /*
             * 服务器当前时间（毫秒）。
             *
             * 前端拿它和自己 Date.now() 相减判时钟偏移。**这不是锦上添花的字段**：
             * 一批源（Jpys / Gulu / AppRJ / Gz360 / AppDrama2 / MiSou）在请求里带
             * `System.currentTimeMillis()` 生成的签名或协议时间戳，服务端会拒掉偏差
             * 过大的请求，表现是"搜索静默返回空、不报错"。
             *
             * 实测（MuMu 模拟器不跑 NTP，设备时间落后宿主 9h53m）：
             *   docker/PC（用宿主时间）Jpys n=8、Gulu n=3
             *   TV 内置网关（用设备时间）Jpys n=0、Gulu n=0，日志里
             *   `Jpys: JSONException — No value for data`
             * 真机上用户改错时间、时区设错是同一类故障，所以壳里也要能看出来。
             */
            o.put("now", System.currentTimeMillis());
            long started = STARTED_AT;
            o.put("uptime", started > 0 ? (System.currentTimeMillis() - started) / 1000 : 0);
            /*
             * 堆/并发诊断。
             *
             * 排查"同配置同版本，投影仪必崩、MuMu 正常"时，唯一能直接看出差别的
             * 三个量就是：堆上限、按堆算出的并发、实际用的并发。没有它们，
             * 只能靠改代码 + 重打包反复猜。
             */
            o.put("heapMaxMB", Runtime.getRuntime().maxMemory() / (1024 * 1024));
            o.put("dalvikHeapLimitMB", dalvikHeapLimitMB());
            o.put("searchConcurrency", searchConcurrencyHook);
            // 堆诊断（2026-11-05）：排查 32 位聚合搜索时到底有多压迫
            try {
                Runtime rt = Runtime.getRuntime();
                long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                if (used > heapPeakMB) heapPeakMB = used;
                o.put("heapUsedMB", used);
                o.put("heapPeakMB", heapPeakMB);
            } catch (Throwable ignored) {
            }
            o.put("searchConcurrencyCapped", searchConcurrencyCapped);
            /*
             * 脚本运行时熔断状态。运维排查"为什么脚本源秒失败"时，
             * 这里能直接看到是熔断开了（容器挂了）还是源站本身没内容。
             */
            o.put("jsRuntimeBreaker", scriptRuntimeBreakerOpen() ? "open" : "closed");
            o.put("jsRuntimeFails", scriptRuntimeFails.get());
        } catch (Throwable t) {
            // 兜底也要能编译：android-json 的 put 抛受检异常
            try {
                o = new JSONObject();
                o.put("ok", true);
                o.put("version", VERSION);
            } catch (Throwable ignored) {
            }
        }
        return o.toString();
    }

    /**
     * 上一次聚合搜索实际使用的并发数（0 = 还没搜过）。
     *
     * <p>只用于 {@code /api/health} 诊断。写成 volatile 单值而不是 Map：
     * 这是给人看的，不是给逻辑用的。
     */
    /** 进程内 Java 堆使用峰值（MB），/api/health 采样更新。 */
    private static volatile long heapPeakMB = 0;

    private static volatile int searchConcurrencyHook = 0;

    /**
     * 上一轮搜索的并发是否被设备能力压低了（用户设置高于实际上限）。
     *
     * <p>给设置页提示用：32 位设备上用户选 24 实际只会跑 6，
     * 界面必须能告诉用户"本机内存受限，实际最多 N 路"，
     * 而不是让用户以为设置没生效。
     */
    private static volatile boolean searchConcurrencyCapped = false;

    /**
     * 当前进程是不是 32 位。
     *
     * <p><b>为什么这个判断决定要不要额外压并发</b>：32 位 Android 的
     * region space 上限 256MB 且不可增长，ART 的并发拷贝 GC 在
     * 堆压迫下会命中 `LogHeapCorruption`（实测投影仪必崩、MuMu 64 位从不复现）。
     * 判据用 {@code sun.arch.data.model}：ART 与 HotSpot 都提供这个属性，
     * 比解析 {@code os.arch} 字符串可靠（后者在 x86 上可能是 i386/i686/amd64）。
     *
     * <p>取不到时返回 false —— 宁可不压（PC 插件跑 JVM，本来内存就充裕），
     * 也不要因为读不到属性就把并发砍半。
     */
    /** 读取系统属性：Android 走 SystemProperties，JVM 退回 -D。 */
    private static String sysProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class).invoke(null, key);
            String s = v == null ? "" : String.valueOf(v);
            if (!s.isEmpty() && !"null".equals(s)) return s;
        } catch (Throwable ignored) {
        }
        return System.getProperty(key, "");
    }

    private static boolean boolProp(String key) {
        String v = sysProp(key);
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    /** Android 上可靠的位数判定（ART 取不到 sun.arch.data.model）。 */
    private static boolean is32BitAndroid() {
        // 首选 Build.SUPPORTED_64_BIT_ABIS：公开字段，反射不受 hidden-API 限制
        try {
            Class<?> b = Class.forName("android.os.Build");
            String[] abis = (String[]) b.getField("SUPPORTED_64_BIT_ABIS").get(null);
            if (abis != null && abis.length == 0) return true;
        } catch (Throwable ignored) {
        }
        // 兜底：Process.is64Bit()
        try {
            Class<?> c = Class.forName("android.os.Process");
            Object r = c.getMethod("is64Bit").invoke(null);
            return r instanceof Boolean && !((Boolean) r);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 系统声明的 Dalvik 堆上限（MB）；读不到返回 0。
     *
     * <p><b>为什么要单独读它</b>：{@code Runtime.maxMemory()} 在
     * {@code android:largeHeap="true"} 下返回的是 **large-heap 上限**，
     * 而 GC 实际按 {@code dalvik.vm.heapgrowthlimit} 起步增长。
     * 两者差一个量级时（实测投影仪 growth=192MB、largeHeap=512MB），
     * 按 maxMemory 估算"能吃多少"会系统性高估 —— 这正是
     * 44 站搜索在 32 位 ART 上把 region space 撑爆的原因。
     *
     * <p>Android 上走 {@code SystemProperties}（隐藏 API，只能反射）；
     * JVM 上必然取不到，返回 0，调用方按"没有这类限制"处理。
     */
    /**
     * GC 闸门阈值（字节）。0 = 关（JVM / 64 位 / 未配置）。
     *
     * <p>TV 端 32 位由 {@code GatewayServer} 在 `Class.forName("ApiServer")` 之前
     * 设成 2MB（见那里的注释）。这里必须是 static final：一个进程只读一次。
     */
    private static final long GC_GATE_BYTES = resolveGcGateBytes();
    /** 上次闸门 GC 时的累计分配量（-1 = 还没初始化）。 */
    private static long gcGateLastAlloc = -1L;

    private static long resolveGcGateBytes() {
        try {
            String v = System.getProperty("meow.gcgate");
            if (v != null && !v.trim().isEmpty()) {
                long mb = Long.parseLong(v.trim());
                return Math.max(0L, mb) * 1024L * 1024L;
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    /** 累计分配字节（`art.gc.bytes-allocated`）；JVM / 旧系统取不到返回 -1。 */
    private static long bytesAllocatedNow() {
        try {
            Class<?> d = Class.forName("android.os.Debug");
            Object v = d.getMethod("getRuntimeStat", String.class)
                    .invoke(null, "art.gc.bytes-allocated");
            return v == null ? -1L : Long.parseLong(String.valueOf(v).trim());
        } catch (Throwable t) {
            return -1L;
        }
    }

    /**
     * 到量就在**当前线程**做一次完整 GC。
     *
     * <p>为什么这样做能救命：ART 自己触发的是**并发** GC，标记阶段和 mutator
     * 同时跑，这台机器的 32 位实现会撞出 `LogHeapCorruption`；而显式
     * `System.gc()` 是阻塞式、独占的，且在"两个站点之间"这个对象图相对干净的
     * 时刻执行（串行模式下就一个搜索线程）。实测 boot 期那次显式 GC 从未崩过。
     */
    private static void gcGateTick() {
        if (GC_GATE_BYTES <= 0) return;
        long now = bytesAllocatedNow();
        if (now < 0) return;
        if (gcGateLastAlloc < 0) {
            gcGateLastAlloc = now;
            return;
        }
        if (now - gcGateLastAlloc < GC_GATE_BYTES) return;
        try {
            Runtime.getRuntime().gc();
        } catch (Throwable ignored) {
        }
        gcGateLastAlloc = bytesAllocatedNow();
    }

    private static long dalvikHeapLimitMB() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class)
                    .invoke(null, "dalvik.vm.heapgrowthlimit");
            String s = v == null ? "" : v.toString().trim();
            if (s.isEmpty()) return 0L;
            long mb;
            if (s.endsWith("m") || s.endsWith("M")) {
                mb = Long.parseLong(s.substring(0, s.length() - 1));
            } else if (s.endsWith("g") || s.endsWith("G")) {
                mb = Long.parseLong(s.substring(0, s.length() - 1)) * 1024L;
            } else {
                mb = Long.parseLong(s) / (1024L * 1024L);
            }
            return mb;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * spider jar 是否真的在。
     *
     * <p>不能再用 {@code com.github.catvod.crawler.Spider} 判断：源码里有一份
     * 给 JVM 宿主编译用的空实现桩，它恒能被类路径加载，导致空壳的
     * {@code /api/health} 一直谎报 {@code spiderJar=true}。
     *
     * <p>改用真实 jar 才有的站点类（完整内置 jar 实测含 Proxy/Bili）。
     * 两者都找不到就是没有可用的 spider 实现。
     */
    private static boolean spiderJarPresent() {
        return spiderClassPresent("Proxy") || spiderClassPresent("Bili");
    }

    /**
     * 这个站点自己的 jar 类能不能加载 —— "这台机器到底装没装这个源"的判据。
     *
     * <p>{@link #spiderJarPresent()} 也用真实站点类（Proxy/Bili）判断，
     * 不能再问 {@code com.github.catvod.crawler.Spider}：源码里有一份给 JVM
     * 编译用的空实现桩，问它恒为 true，空壳会被误判成“有 jar”。
     * 判断"某站点有没有实现"必须按站点类名问（与 {@code createInstance} 一致，
     * 多实例 key 形如 {@code Bili#2} 时要先去掉 {@code #n}）。
     */
    private static boolean spiderClassPresent(String site) {
        String k = site == null ? "" : site;
        int hash = k.indexOf('#');
        if (hash > 0) k = k.substring(0, hash);
        if (k.isEmpty()) return false;
        try {
            platform.Bridge.loadClass("com.github.catvod.spider." + k);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否配置了外部弹幕 API（policy.json 的 danmakuApi 或 MEOW_DANMAKU_API）。 */
    private static boolean danmakuApiConfigured() {
        String env = System.getenv("MEOW_DANMAKU_API");
        if (env != null && !env.trim().isEmpty()) return true;
        String p = loadPolicy().optString("danmakuApi", "");
        return !p.trim().isEmpty();
    }

    // ---------------------------------------------------------------- spider

    /**
     * 取（必要时创建）站点实例。
     *
     * <p><b>★ 不能用 computeIfAbsent 包住"类加载 + 实例化 + init"</b>。
     *
     * <p>踩过（实测极米 Z6X Pro / Android 9 / armeabi-v7a）：聚合搜索跑几次后
     * 进程被 SIGABRT 杀掉，dropbox 里的原生崩溃堆栈是——
     * <pre>
     *   signal 6 (SIGABRT), Abort message:
     *     'file_utils.cc:113] ... /dev/ashmem/dalvik-main space (region space) (deleted)'
     *   art::gc::Verification::LogHeapCorruption
     *   art::gc::collector::ConcurrentCopying::MarkNonMoving
     * </pre>
     * 也就是 ART 的并发 GC 在标记阶段检测到堆损坏后主动 abort。
     *
     * <p>根因：`ConcurrentHashMap.computeIfAbsent` 会在**持有桶锁**时执行 lambda，
     * 而这里 lambda 里做的是 DexClassLoader 加载类、反射 newInstance、再调
     * spider 的 `init()` —— 后者是第三方代码，可能触发 `<clinit>` 和大量分配。
     * 在 region-space GC 并发标记期间持锁跑这些，与 GC 的读屏障冲突，
     * 表现为堆损坏。MuMu（Android 15 / x86_64）用的是更新的 GC 实现，没暴露。
     *
     * <p>改成"锁外构建、锁内 double-check 放入"：重量级操作不持任何 GC 相关锁。
     */
    private static Object instance(String site) {
        Object hit = CACHE.get(site);
        if (hit != null) return hit;
        /*
         * 同一站点的并发创建要串行化（spider 的 <clinit> 不能并发跑）。
         * 用一个按站点分段的锁，而不是 CACHE 自身的桶锁。
         */
        synchronized (INSTANCE_LOCKS.computeIfAbsent(site, k -> new Object())) {
            Object again = CACHE.get(site);
            if (again != null) return again;
            Object created = createInstance(site);
            CACHE.put(site, created);
            return created;
        }
    }

    /**
     * 预热一个站点：把「类加载 + 实例化 + init」提前到启动期完成。
     *
     * <p>给 TV 端网关的启动预热用（见 GatewayServer.warmupSpiders）。
     * 单独开公开方法而不是让那边反射调 instance()：instance 是私有的，
     * 而「预热」本身就是个值得有名字的语义 —— 搜索路径和预热路径
     * 走同一个缓存与锁，预热完的站点在搜索时直接命中。
     *
     * <p>失败不抛：预热失败只是回到懒加载，不该让启动流程报错。
     *
     * @return true = 本次创建成功；false = 已存在或创建失败
     */
    /**
     * 预热要用的站点 key 列表（与搜索同一个命名空间）。
     *
     * <p>单独开一个方法而不是让 TV 侧读 LocalSites：LocalSites 是端上表，
     * key 与 SITES 未必一致，用错会预热出一批 ext 为空的实例并缓存下来，
     * 之后搜索命中缓存但跑不起来——比不预热更难排查。
     *
     * <p>只返回能执行的站：drpy 类源在没有 JS 运行时的情况下必然失败，
     * 预热它们只是白白消耗堆。
     */
    public static java.util.List<String> siteKeysForWarmup() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            // 与 /api/sites 同源过滤：空壳没有 jar，不该拿 44 个默认名去预热
            // （TV 实测 warmup ok=0 fail=44，白白消耗堆）。
            // 原生源（豆瓣）不需要 jar 类预热，跳过——它的 home 会走网络，
            // 放进 warmup 只会多一条 fail。
            for (String k : implementedSiteKeys()) {
                if (sources.NativeSources.get(k) != null) continue;
                if (routes.HealthRoutes.isSkipped(k)) continue;
                out.add(k);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 预热专用的单线程池。
     *
     * <p>用**一个**线程而不是每站新建：WARMUP 是串行跑的，某个源的 init 卡住时
     * 后续站点排队等就行，不需要并发。用单线程池的好处是"卡住的那个继续在池线程里
     * 跑完"，不会像 `new Thread` 那样每卡一次就永久漏一个线程
     * （实测这种泄漏在 32 位进程里会累积成 GC 压力）。
     */
    private static final ExecutorService WARMUP_ONE =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(null, r, "meow-warmup1", 512L * 1024L);
                t.setDaemon(true);
                return t;
            });

    public static boolean warmInstance(String site) {
        /*
         * 不检查 SITES.containsKey：前置预热的目标就是"把类加载提前"，
         * 而 LocalSites（端上站点表）与 SITES（API 站点表）来自两份数据，
         * 名字未必严格一致。检查会导致"看着跑了、其实全被跳过"，
         * 而这正是预热失效时最难发现的一种表现（不报错、只是没效果）。
         */
        if (site == null || site.isEmpty()) return false;
        if (CACHE.containsKey(site)) return false;
        try {
            /*
             * ★ 预热也要有超时，否则一个慢源就把整轮预热卡死。
             *
             * 实测（极米 Z6X Pro）：Jianpian 的 init 会卡 40 秒以上
             * （它在新浪微博的接口上轮询），预热线程停在第 15 个站不再前进，
             * 后面 29 个类永远不会被加载 —— 预热形同虚设。
             *
             * 3 秒足够覆盖正常源的 init（实测其余 43 个都在 1 秒内完成），
             * 超时就让它在搜索时懒加载：那一个站慢，不该拖累整体预热。
             */
            java.util.concurrent.Future<Object> f = WARMUP_ONE.submit(() -> instance(site));
            try {
                return f.get(3_000L, java.util.concurrent.TimeUnit.MILLISECONDS) != null;
            } catch (java.util.concurrent.TimeoutException te) {
                // 不 cancel：让它继续在预热线程里跑完，下一次预热/搜索直接命中
                return false;
            } catch (Throwable t) {
                /*
                 * 预热期就把"跑不起来的源"记进健康表：
                 * 冷启动后 /api/sites 立刻能看到 unsupported + 原因，
                 * 用户不用先点一次撞 500 才知道这个源在本构建里跑不了。
                 */
                try {
                    recordHealth(site, false, 0L, loadFailureChain(site, t));
                } catch (Throwable ignored) {
                }
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** 上一轮搜索的池；新一轮开始时关掉它（见 searchAll 里的自愈注释）。 */
    /**
     * 进程级共享搜索池（线程永久复用，对齐上游 Fongmi 的 Task.largeExecutor）。
     *
     * <p><b>core 必须等于 max</b>：ThreadPoolExecutor 在"线程数达到 core"之后
     * 会让任务先进无界队列，队列不满就不继续扩容 —— core 写小数会让池
     * 永远只有那么多线程（踩过：core=2 把 docker 的 12 并发压成 2）。
     *
     * <p>并发上限由 sharedSearchPool 按本轮 effThreads 动态设置 core/max，
     * 配合 allowCoreThreadTimeOut 让闲置线程可回收，同时保证线程对象在
     * 连续搜索之间复用。
     */
    private static final java.util.concurrent.ThreadPoolExecutor SEARCH_POOL =
            new java.util.concurrent.ThreadPoolExecutor(
                    4, 32, 30L, java.util.concurrent.TimeUnit.SECONDS,
                    /*
                     * ★ 有界队列（对齐上游影视仓的 128）。
                     *
                     * 我们原来用无界队列：270 个源一次 submit 全部塞进队列，
                     * 收尾时 drainTo 只能清掉"还没被取走的"，而工作线程会
                     * 持续从队头取任务、再发起新的慢请求 —— 越积越深。
                     *
                     * 有界 + AbortPolicy 之后：超出容量的任务立刻被拒
                     * （`catch (RejectedExecutionException) { continue; }`），
                     * 队列深度永远不会超过 128，收尾一次就能清空。
                     *
                     * 数量取 128 而不是更小：12 个并发槽时，128 ≈ 10 轮的
                     * 缓冲，足够让健康分排序生效（好源在前，先进队列）。
                     */
                    new java.util.concurrent.LinkedBlockingQueue<>(128),
                    r -> {
                        Thread t = new Thread(null, r, "meow-search", SEARCH_STACK_BYTES);
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    static {
        SEARCH_POOL.allowCoreThreadTimeOut(true);
    }

    /** 把共享池的并发对齐到本轮 effThreads（core = max = want）。 */
    private static java.util.concurrent.ExecutorService sharedSearchPool(int effThreads) {
        try {
            int want = Math.max(2, Math.min(32, effThreads));
            if (SEARCH_POOL.isShutdown()) {
                return Executors.newFixedThreadPool(want, r -> {
                    Thread th = new Thread(null, r, "meow-search", SEARCH_STACK_BYTES);
                    th.setDaemon(true);
                    return th;
                });
            }
            // 先升 max 再升 core；先降 core 再降 max。顺序反了会抛 IAE（core > max）。
            if (want > SEARCH_POOL.getMaximumPoolSize()) {
                SEARCH_POOL.setMaximumPoolSize(want);
                SEARCH_POOL.setCorePoolSize(want);
            } else {
                if (want < SEARCH_POOL.getCorePoolSize()) {
                    SEARCH_POOL.setCorePoolSize(want);
                }
                if (want < SEARCH_POOL.getMaximumPoolSize()) {
                    SEARCH_POOL.setMaximumPoolSize(want);
                }
            }
            return SEARCH_POOL;
        } catch (Throwable t) {
            return Executors.newFixedThreadPool(Math.max(2, effThreads), r -> {
                Thread th = new Thread(null, r, "meow-search", SEARCH_STACK_BYTES);
                th.setDaemon(true);
                return th;
            });
        }
    }

    /**
     * 搜索代号（epoch）—— 用于作废旧轮结果。
     *
     * <p>对齐上游做法（Fongmi 的 `ViewModelSearchRunner.epoch` /
     * 影视仓的 `searchEpoch`）：每轮开始时自增，迟到的旧轮结果直接丢弃，
     * 不污染新一轮、也不和新一轮抢着写同一个聚合表。
     *
     * <p><b>注意它能做什么、不能做什么</b>：只能让旧轮的**结果**作废并让
     * 聚合循环尽快退出；<b>不能中断已经在跑的 spider / HTTP 请求</b>
     * （Java Socket 读不响应 Thread.interrupt）。中断靠的是缩短单源超时
     * —— 见 callScriptRuntime 的熔断机制。
     */
    private static final java.util.concurrent.atomic.AtomicInteger SEARCH_EPOCH =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 按站点分段的创建锁；只包住"建实例"这一段。 */
    private static final Map<String, Object> INSTANCE_LOCKS = new ConcurrentHashMap<>();

    private static Object createInstance(String k) {
        {
            try {
                // 多实例：key 形如 "AppDrama2#2"，用 EXT 里对应那组 ext 初始化同一个类。
                // 原 config 里同一 spider 会注册成「干饭」「再来」「一碗」等多个站点。
                String cls = k;
                int hash = k.indexOf('#');
                if (hash > 0) cls = k.substring(0, hash);
                // 走平台钩子：JVM 是 Class.forName（spider 在类路径里），
                // Android 是 DexClassLoader（spider 在 codeCacheDir 的 classes.dex 里）。
                Class<?> c = platform.Bridge.loadClass("com.github.catvod.spider." + cls);
                Object o = c.getDeclaredConstructor().newInstance();
                try {
                    // Context 用反射拿而不是直接引用：写成 new android.content.Context()
                    // 会让整个类依赖 android stub 参与编译，只编译 ApiServer/SpiderHost
                    // （不把 src/android 一起交给 javac）时直接报"程序包android.content不存在"。
                    //
                    // ★ 参数类型必须查 **android.content.Context**，不能用实例的 getClass()：
                    //   Android 上注入的是 Application（Context 的子类），而 spider 声明的是
                    //   init(Context, String)。用 Application.class 去 getMethod 找不到方法，
                    //   异常被下面的 catch 吞掉 → 所有站点静默不 init（ext 全部失效）。
                    Object ctx = platform.Bridge.context();
                    Class<?> ctxCls = Class.forName("android.content.Context");
                    if (ctx == null) ctx = ctxCls.getDeclaredConstructor().newInstance();
                    c.getMethod("init", ctxCls, String.class)
                            .invoke(o, ctx, EXT.getOrDefault(k, ""));
                } catch (NoSuchMethodException ignored) {
                }
                return o;
            } catch (Exception e) {
                throw new RuntimeException("cannot load spider " + k + ": " + e, e);
            }
        }
    }

    private static String call(String site, String action, String body) throws Exception {
        long t0 = System.currentTimeMillis();
        try {
            /*
             * 原生源（sources/*）：宿主自己实现、**不依赖 spider.jar** 的站点。
             *
             * ★ 必须排在 js / jar 两个分支之前，而且判据只看站点名。
             *   理由：同一个站点名只能有一个实现者。放后面的话，"Douban" 会先走
             *   jar 的 csp_Douban（它没有 detailContent，详情页永远空），
             *   或者没有 jar 时直接 ClassNotFoundException → 前端 500。
             *
             * 返回 null = 本源不实现这个 action（目前只有 search）。
             * 这时：有 jar 继续往下交给 jar（豆瓣的 jar 实现只有搜索可用）；
             * 没有 jar 就给空结构——不能让它变成 500，否则搜索结果里
             * 这个站点会显示成"出错"而不是"没结果"。
             */
            sources.NativeSource ns = sources.NativeSources.get(site);
            if (ns != null) {
                String out = sources.NativeSources.invoke(ns, action, body);
                /*
                 * ★ 判据不能用 spiderJarPresent()。
                 *
                 * 踩过（本轮实测）：`server/host/src/com/github/catvod/crawler/Spider.java`
                 * 是给 JVM 宿主编译用的**空实现桩**，它让 spiderJarPresent() 恒为 true ——
                 * 于是"没装 jar"也会被判成有 jar，search 落到 instance() 抛
                 * ClassNotFoundException，前端拿到 500。
                 * 真正该问的是"这个站点自己的类在不在"。
                 */
                if (out == null && !spiderClassPresent(site)) {
                    out = emptyFallback(action, "");
                }
                if (out != null) {
                    long cost = System.currentTimeMillis() - t0;
                    boolean ok = !isErrorPayload(out);
                    int count = 0;
                    try {
                        JSONArray list = new JSONObject(out).optJSONArray("list");
                        if (list != null) count = list.length();
                    } catch (Throwable ignored) {
                    }
                    // 与 jar 分支同一本健康账：原生源挂了也该被降档/标记
                    routes.HealthRoutes.record(site, ok, ok ? null : "empty payload", cost, count);
                    return out;
                }
            }
            /*
             * 苹果CMS 直连（采集站）：配置里的 api 是一个 http 地址，
             * 既不是类名（csp_）也不是脚本（.js / py:），走这里。
             *
             * ★ 必须排在 isScriptApi 与 instance() 之前：这类站点以前能导入
             *   （导入报告还写着"苹果CMS直连"），但 call() 没有 http 分支 →
             *   落到 jar 分支撞 ClassNotFoundException，每个站烧满超时还污染健康分。
             */
            String directApi = API.get(site);
            if (directApi != null && directApi.startsWith("http")) {
                String out = sources.CmsSource.invoke(directApi, action, body);
                if (out == null) out = emptyFallback(action, "");
                long cost = System.currentTimeMillis() - t0;
                boolean ok = !isErrorPayload(out);
                int count = 0;
                try {
                    JSONArray list = new JSONObject(out).optJSONArray("list");
                    if (list != null) count = list.length();
                } catch (Throwable ignored) {
                }
                // 采集站内容每天在变，「这个词没搜到」是正常业务结果，不是源故障
                routes.HealthRoutes.record(site, ok, ok ? null : "empty payload", cost, count);
                return out;
            }
            // 非 jar 型源：交给外部运行时（drpy 的 .js 走 Node 服务）。
            // 放在 instance() 之前，否则会先撞 ClassNotFoundException。
            String jsApi = API.get(site);
            if (jsApi != null && isScriptApi(jsApi)) {
                String out = callScriptRuntime(site, jsApi, action, body);
                long cost = System.currentTimeMillis() - t0;
                boolean ok = !isErrorPayload(out);
                /*
                 * ★ "这次到底有没有产出"要单独判，不能用 ok。
                 *
                 * 踩过（2026-10-30 OECT 实测）：drpy 任务超时后返回**空 body**，
                 * `callScriptRuntime` 走 `emptyFallback` 把它兜底成 `{"list":[]}` ——
                 * 结构合法、`code` 字段都没有，`isErrorPayload` 判成成功。
                 * 于是"慢且失败"的源在健康表里被记成一次成功，
                 * 永远不会降档，每轮照样烧 6 秒。
                 *
                 * hasPayload 看的是"有没有真的拿到条目"，与结构合法性无关。
                 */
                boolean hasPayload = hasRealPayload(action, out);
                /*
                 * 驱动熔断。
                 *
                 * ★ 判据必须包含"跑满了超时"，不能只看返回文本或 ok 标志。
                 *
                 * 踩过（OECT 实测）：`slow drpy_js_* 8012ms` 证明这些源确实
                 * 卡满了 8 秒超时，但 `callScriptRuntime` 超时后会走
                 * `emptyFallback` 把空 body 兜底成 `{"list":[]}` ——
                 * 那是个**结构合法的空结果**，`isErrorPayload` 判为成功，
                 * 文本里也没有任何 "timeout" 字样。
                 * 只按文本/ok 判断的话 fails 永远是 0、熔断永远不触发，
                 * 227 个源每轮都各等 8 秒。
                 *
                 * 耗时是唯一可靠信号：容器正常时这些源 1~2 秒就回，
                 * 跑到接近超时上限的唯一解释就是运行时在卡。
                 */
                boolean timedOut = cost >= SCRIPT_RUNTIME_TIMEOUT_MS - 500L;
                boolean runtimeFault = timedOut || (!ok && looksLikeRuntimeFault(out));
                /*
                 * 只把"撞满超时"的源记为慢源。
                 *
                 * ★ 曾经试过把"失败且耗时过半"也算进来，**已回滚**。
                 * 原因（2026-10-30 OECT 实测）：drpy 侧任务超时对齐到 6 秒后，
                 * 几乎每个失败源都满足 `cost >= 4s`，于是全被打进 1 秒档；
                 * 而 drpy 处理一个源本来就要 2~6 秒 —— 1 秒必然不够，
                 * 结果是**全部失败**：`timedOut` 从 194 涨到 670，
                 * 搜索结果从 28~34 条掉到 15 条再掉到 0 条。
                 *
                 * 教训：快速试的超时必须是"比正常路径短、但仍够跑完"的值。
                 * 要治 drpy 的慢，只能在 drpy 侧或它的队列上做，不能靠压超时。
                 *
                 * ★ 判据必须跟着 SCRIPT_RUNTIME_FAST_TIMEOUT_MS 走，不能写死 7.5 秒。
                 * 踩过（2026-10-30 OECT 实测）：drpy 任务超时是 6 秒，所以源失败
                 * 在 6.0s 就返回，永远到不了 `8s - 500ms` 的门槛 →
                 * `markScriptSiteSlow` 一次都不触发 → 近百个源每轮各烧 6 秒。
                 *
                 * ★ 只对**失败**的源降档，成功的不动。
                 * 慢但能出结果的源（实测有 5 秒返回十几条的）是资产，
                 * 把它压到 3 秒会直接砍掉结果 —— 那是上一轮 1 秒档翻车的同一个坑。
                 *
                 * 判据用 hasPayload 而不是 ok：ok 只反映"结构不报错"，
                 * 而 drpy 超时被 emptyFallback 兜成 `{"list":[]}` 时 ok=true。
                 */
                boolean slowFail = !hasPayload && cost >= SCRIPT_RUNTIME_FAST_TIMEOUT_MS;
                if (timedOut || slowFail) markScriptSiteSlow(site);
                recordScriptRuntime(!runtimeFault);
                // ★ 错误信息要传进去：`jsRuntime 未配置` 这类要能归到 unsupported，
                //   否则会被当成源站失败累计 streak（见 HealthRoutes.classify）
                recordHealth(site, ok, cost, ok ? null : firstLine(out));
                return out;
            }
            Object o;
            try {
                o = instance(site);
            } catch (Throwable t) {
                /*
                 * ★ 实例化失败不能冒到 HTTP 层变成 500。
                 *
                 * 踩过（2026-11-11 用户报「好多源也是 500」）：实测 171 个源里
                 * 66 个 500，其中 58 个是 jar 里的源类依赖了当前构建没有的类
                 * （`com.github.catvod.spider.DexNative`、Sardine…）。500 一路
                 * 冒到最外层 dispatcher，**没有经过 recordHealth** ——
                 * 于是这些源永远不会被标 unsupported，每次点都再 500 一次，
                 * 前端也只能显示"服务器错误"，看不出是"这个源在本构建里根本跑不了"。
                 *
                 * 现在：记健康（classify 会把 "cannot load spider" 归到
                 * unsupported，自动从聚合搜索里摘掉）+ 回一句能看懂的话。
                 */
                String chain = loadFailureChain(site, t);
                recordHealth(site, false, System.currentTimeMillis() - t0, chain);
                String missing = missingClassOf(t);
                return new JSONObject().put("code", -1)
                        .put("msg", "该源在当前构建不可用"
                                + (missing.isEmpty() ? "" : "（缺少 " + missing + "）"))
                        .toString();
            }
            JSONObject b = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
            String raw = callRaw(o, action, b, site);
            String out = emptyFallback(action, raw);
            boolean empty = raw == null || raw.trim().isEmpty();
            boolean ok = !empty && !isErrorPayload(out);
            long cost = System.currentTimeMillis() - t0;
            /*
             * 空结构（{"list":[]}）仍记成功，这是有意的：搜一个冷门词返回空是
             * 源站正常行为，记失败会让热门词也跟着被拖累。
             */
            int count = 0;
            try {
                JSONArray list = new JSONObject(out).optJSONArray("list");
                if (list != null) count = list.length();
            } catch (Throwable ignored) {
            }
            // 带 count：健康分要奖励"能给出结果"的源（见 HealthRoutes.score）
            routes.HealthRoutes.record(site, ok, ok ? null : "empty payload", cost, count);
            /*
             * 但**慢到离谱的空结果**要单独标记。
             *
             * 实测 Jiaozi：首轮 30.8s、之后 0.2s，而它返回的是 {"list":[]}。
             * 按上面那条它被记成"成功"，`ok=141 fail=0 degraded=false` ——
             * 于是一个每次都可能卡 30 秒的源，永远不会被任何机制标记，
             * 聚合搜索每一轮都要为它等满收尾预算（实测 4.2~5.0s vs 最快 0.9s）。
             *
             * 处理方式不是降级（它确实连得上、也确实没结果），而是把
             * 历史耗时记下来 —— 收尾预算按最慢源算，超预算就先返回，
             * 让它自然落到长尾里。见 searchAll 的 waitBudget。
             */
            if (ok && cost >= 5_000L && isEmptyList(out)) {
                core.LogBuffer.warn("search", "slow-empty " + site + " " + cost + "ms");
            }
            return out;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - t0;
            recordHealth(site, false, cost, describeError(e));
            throw e;
        }
    }

    /**
     * 异常 → 可分类的一行描述。
     *
     * <p>不能只写类名：`InvocationTargetException` 本身不说明任何事，
     * 真实原因在 cause 链里（`HealthRoutes.classify` 也依赖这些字样）。
     * 实测踩过：TV 上几十条一模一样的 "InvocationTargetException"，
     * 既不知道哪个源、也不知道为什么，等于没记。
     */
    private static String describeError(Throwable t) {
        Throwable root = unwrap(t);
        String m = root.getMessage();
        String s = root.getClass().getSimpleName() + (m == null ? "" : " — " + firstLine(m));
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    /**
     * 站点实例化失败 → 一行可分类的描述。
     *
     * <p>前缀 `cannot load spider` 是给 {@code HealthRoutes.classify} 看的：
     * 它把这个字样归到 unsupported（环境不支持），而不是当成源站故障累计。
     */
    private static String loadFailureChain(String site, Throwable t) {
        return "cannot load spider " + site + ": " + describeError(t);
    }

    /**
     * 从异常链里抠出"缺的是哪个类"，抠不到返回空串。
     *
     * <p>2026-11-11 实测：171 个源里 58 个是 jar 依赖了当前构建没有的类
     * （`com.github.catvod.spider.DexNative`、`com.thegrizzlylabs.sardineandroid.Sardine`）。
     * 用户看到的必须是一句能行动的话（"缺少 X"），而不是 500。
     */
    private static String missingClassOf(Throwable t) {
        Throwable c = t;
        int depth = 0;
        while (c != null && depth++ < 8) {
            String m = c.getMessage();
            if (m != null) {
                int i = m.indexOf("Failed resolution of: L");
                if (i >= 0) {
                    String s = m.substring(i + "Failed resolution of: L".length());
                    int j = s.indexOf(';');
                    if (j > 0) return s.substring(0, j).replace('/', '.');
                }
                int q = m.indexOf("Didn't find class \"");
                if (q >= 0) {
                    String s = m.substring(q + "Didn't find class \"".length());
                    int j = s.indexOf('"');
                    if (j > 0) return s.substring(0, j);
                }
                if (c instanceof NoClassDefFoundError) {
                    String s = m.trim();
                    // NoClassDefFoundError 的消息就是类名（可能带 "Failed resolution of:"）
                    if (s.startsWith("com.") || s.startsWith("android.")
                            || s.startsWith("kotlin.") || s.startsWith("org.")) {
                        int sp = s.indexOf(' ');
                        return sp > 0 ? s.substring(0, sp) : s;
                    }
                }
            }
            c = c.getCause();
        }
        return "";
    }

    /** 取多行文本的第一行（压栈信息不该进健康表）。 */
    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        String t = i < 0 ? s : s.substring(0, i);
        return t.length() > 300 ? t.substring(0, 300) : t;
    }

    // ---------------------------------------------------------------- 源健康

    /**
     * 单个源的健康状态。
     *
     * <p>判定用**连续失败数**而不是成功率：源站经常"抽风三分钟"，用累计成功率
     * 会把偶发恢复的源永久标灰；连续 3 次失败才降级，任意一次成功立即恢复。
     *
     * <p>只放在内存里、只在状态**翻转**时落盘：健康是过程数据，每次调用都写盘
     * 会让一次搜索产生几十次 IO，收益却只有"重启不丢"这一点点。
     */


    /** 自动降级的源在聚合里被跳过的时间窗；过了窗口给一次自愈机会。 */
    /**
     * 主动探活的单站超时。
     *
     * <p>比搜索的总预算短得多：探活回答的是"这个源现在还能不能用"，
     * 8 秒还不回话的源对交互式搜索已经没有意义，再等只会让探活整页转圈。
     */
    private static final long PROBE_SITE_TIMEOUT_MS = routes.HealthRoutes.PROBE_SITE_TIMEOUT_MS;

    private static routes.HealthRoutes.SiteHealth healthOf(String site) {
        return routes.HealthRoutes.healthOf(site);
    }

    private static void recordHealth(String site, boolean success, long costMs, String error) {
        // ★ 必须走带 costMs 的重载：那个重载才做错误分类（unsupported / timeout / dns / tls）。
        //   早先这里调的是 3 参版本，导致 costMs 与 error 全被丢掉 ——
        //   表现就是 jsRuntime 未配置这类环境问题仍然按"源站失败"累计 streak。
        routes.HealthRoutes.record(site, success, error, costMs);
    }

    private static boolean isSiteSkipped(String site) {
        return routes.HealthRoutes.isSkipped(site);
    }

    private static void setSiteDisabled(String site, boolean disabled) {
        routes.HealthRoutes.setDisabled(site, disabled);
    }

    private static String healthStateJson() {
        return routes.HealthRoutes.stateJson();
    }

    private static File healthFile() {
        return core.DataPaths.file("site-health.json");
    }

    private static synchronized void saveHealthQuietly() {
        routes.HealthRoutes.saveQuietly();
    }

    /** 启动时读回，避免重启后所有灰源又变亮（用户会以为"好了"）。 */
    private static void loadHealth() {
        routes.HealthRoutes.load();
    }

    /**
     * `POST /api/health/probe` —— 主动探活。
     *
     * <p>body（都可省略）：{@code {"sites":["A","B"]}} 只探指定的站，
     * 省略则探整张站点表。返回 {@code {"results":[{site,ok,ms,error}],"probed":n}}。
     *
     * <p><b>为什么用 home 而不是 search</b>：home 不带关键词，多数源走的是
     * 首页固定接口，比 search 少一次站内查询、也不会被搜索限流打到。
     * 代价是 home 覆盖度不如 search——个别源没实现 homeContent 会返回空结构，
     * 这里按失败记（与 recordHealth 的"空即失败"口径一致）。
     *
     * <p>结果直接写回 site-health.json：探活的价值就是让健康表反映"现在"，
     * 只回给前端而不落盘的话，用户探完看列表还是旧状态。
     */
    private static String probeSites(String body) throws Exception {
        JSONObject req = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        JSONArray reqSites = req.optJSONArray("sites");
        List<String> targets = new ArrayList<>();
        if (reqSites != null) {
            for (int i = 0; i < reqSites.length(); i++) {
                String s = reqSites.optString(i, "");
                if (!s.isEmpty()) targets.add(s);
            }
        } else {
            synchronized (SITES) {
                targets.addAll(SITES.keySet());
            }
        }

        java.util.concurrent.CompletionService<JSONObject> done =
                new java.util.concurrent.ExecutorCompletionService<>(FANOUT);
        // 探活并发上限。FANOUT 是 cached 池，不限并发的话 270 个站会同时
        // 起 270 个探测线程（每个还要再套一层超时线程），在 TV/小机器上直接 OOM。
        // 24 是"一屏几十个源能在 8 秒内探完"与"线程数可控"的折中。
        java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(24);
        List<java.util.concurrent.Future<JSONObject>> futures = new ArrayList<>();
        for (String site : targets) {
            futures.add(done.submit(() -> {
                try {
                    gate.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new JSONObject().put("site", site).put("ok", false)
                            .put("ms", 0).put("error", "interrupted");
                }
                try {
                    return probeOne(site);
                } finally {
                    gate.release();
                }
            }));
        }
        JSONArray results = new JSONArray();
        // 没有总预算之外的第二道超时：单站已经被 routes.HealthRoutes.PROBE_SITE_TIMEOUT_MS 卡住，
        // 池是 cached 的，最坏情况是 N 个线程各等 8 秒后一起回来。
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.put(done.take().get());
            } catch (Exception ignored) {
            }
        }
        return new JSONObject().put("ok", true)
                .put("probed", results.length())
                .put("results", results)
                .toString();
    }

    /** 探一个站。所有异常都折成 ok=false，不让单站拖垮整批。 */
    private static JSONObject probeOne(String site) {
        JSONObject r = new JSONObject();
        long t0 = System.currentTimeMillis();
        String error = "";
        boolean okFlag = false;
        try {
            // 单独起线程 + Future.get(timeout)：spider 内部没有统一超时约束
            // （搜索那边也是这么处理的），不套一层的话卡在 socket 读上的源
            // 会让整批探活永远不返回。
            java.util.concurrent.FutureTask<String> ft =
                    new java.util.concurrent.FutureTask<>(() -> call(site, "home", "{}"));
            Thread th = new Thread(ft, "probe-" + site);
            th.setDaemon(true);
            th.start();
            String out = ft.get(routes.HealthRoutes.PROBE_SITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            okFlag = out != null && !out.trim().isEmpty() && !isErrorPayload(out);
            if (!okFlag) error = "empty or error payload";
        } catch (java.util.concurrent.TimeoutException te) {
            error = "timeout";
            // 超时也要让健康表知道：call() 自己还没返回，recordHealth 不会记这笔
            recordHealth(site, false, routes.HealthRoutes.PROBE_SITE_TIMEOUT_MS, "timeout");
        } catch (java.util.concurrent.ExecutionException ee) {
            // FutureTask.get() 把 call() 抛的异常包一层，展开才有诊断价值：
            // 只回 "ExecutionException" 的话，前端的源列表里每个坏源都长得一样。
            // 继续往下剥 shell：反射调用失败会套 InvocationTargetException，
            // 真正的原因（NPE / 连接超时）在再下一层。
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            int guard = 0;
            while (cause.getCause() != null && guard++ < 5
                    && (cause instanceof java.lang.reflect.InvocationTargetException
                    || cause instanceof RuntimeException)) {
                cause = cause.getCause();
            }
            error = cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        } catch (Exception e) {
            error = e.getClass().getSimpleName();
        }
        long ms = System.currentTimeMillis() - t0;
        try {
            r.put("site", site);
            r.put("ok", okFlag);
            r.put("ms", ms);
            r.put("error", okFlag ? "" : error);
        } catch (Exception ignored) {
        }
        return r;
    }

    /**
     * `POST /api/health/degrade` —— 手动降权 / 恢复。
     *
     * <p>body：{@code {"site":"AppQi","disabled":true}}；也接受
     * {@code {"sites":["A","B"],"disabled":true}} 批量。返回
     * {@code {"ok":true,"updated":["A"],"unknown":["X"],"state":{...healthState}}。
     *
     * <p>{@code disabled=false} 同时清掉自动降级状态：用户点"恢复"要的是
     * "立刻再试一次"，而不是"解除锁定后再等 30 分钟窗口过去"。
     */
    private static void handleHealthDegrade(HttpExchange ex) throws Exception {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"code\":-1,\"msg\":\"POST only\"}");
            return;
        }
        String body = read(ex.getRequestBody());
        JSONObject in;
        try {
            in = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (Exception e) {
            sendJson(ex, 400, new JSONObject().put("code", -1)
                    .put("msg", "bad json: " + e).toString());
            return;
        }
        boolean disabled = in.optBoolean("disabled", false);
        List<String> sites = new ArrayList<>();
        String one = trimToEmpty(in.optString("site", ""));
        if (!one.isEmpty()) sites.add(one);
        JSONArray arr = in.optJSONArray("sites");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = trimToEmpty(arr.optString(i, ""));
                if (!s.isEmpty() && !sites.contains(s)) sites.add(s);
            }
        }
        if (sites.isEmpty()) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"site or sites required\"}");
            return;
        }
        JSONArray updated = new JSONArray();
        JSONArray unknown = new JSONArray();
        for (String s : sites) {
            // 只接受站点表里真实存在的 key：拼错的 key 会被写进 site-health.json
            // 变成一条永远没人读的垃圾记录，且前端会以为"设置成功了"。
            boolean known;
            synchronized (SITES) {
                known = SITES.containsKey(s);
            }
            if (!known) {
                unknown.put(s);
                continue;
            }
            setSiteDisabled(s, disabled);
            updated.put(s);
        }
        sendJson(ex, 200, new JSONObject().put("ok", true)
                .put("disabled", disabled)
                .put("updated", updated)
                .put("unknown", unknown)
                .put("state", new JSONObject(healthStateJson())).toString());
    }

    /** 直播聚合的总预算：和搜索一样，不能让一个卡死的源拖住整个页面。 */
    private static final long LIVE_TOTAL_TIMEOUT_MS = 12_000;

    /**
     * `POST /api/live` —— 聚合所有站点的直播频道。
     *
     * <p>返回：
     * <pre>
     * {
     *   "groups":  [{"site":"Xxx","site_name":"…","group":"央视","channels":[{"name":"CCTV1","urls":["…"]}]}],
     *   "playlists": [{"name":"…","count":42}],
     *   "tried": 55, "failed": 12
     * }
     * </pre>
     *
     * <p>`channels[].urls` 里可能带 `$备用名` 后缀——这是 CatVod 的写法，
     * 原样透传，前端按 `$` 拆成"同一频道的多条线路"。
     *
     * <p>只调 `liveContent`，**不解析 m3u 播放列表**：播放列表是
     * `config.lives` 里的直连地址，属于另一条链路（Phase 2），
     * 混进来会让这个端点的语义变模糊。
     */
    private static String liveAll(String body) throws Exception {
        JSONObject req = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        JSONArray reqSites = req.optJSONArray("sites");
        List<String> targets = new ArrayList<>();
        if (reqSites != null) {
            for (int i = 0; i < reqSites.length(); i++) targets.add(reqSites.optString(i, ""));
        } else {
            synchronized (SITES) {
                targets.addAll(SITES.keySet());
            }
            List<String> alive = new ArrayList<>();
            for (String s : targets) {
                // 与搜索共用同一判定（手动停用 / 环境不支持）。见 HealthRoutes.isSkipped。
                if (!isSiteSkipped(s)) alive.add(s);
            }
            if (!alive.isEmpty()) targets = alive;
        }
        String url5 = req.optString("url", ""); // 部分源用 lives 表的地址
        String liveUrl = url5;
        if (liveUrl.isEmpty()) {
            JSONArray lives = livesWithBuiltin();
            for (int i = 0; i < lives.length(); i++) {
                JSONObject l = lives.optJSONObject(i);
                if (l != null && !l.optString("url", "").isEmpty()) {
                    liveUrl = l.optString("url", "");
                    break;
                }
            }
        }

        JSONArray groups = new JSONArray();
        final String arg = liveUrl;
        java.util.concurrent.CompletionService<String> done =
                new java.util.concurrent.ExecutorCompletionService<>(FANOUT);
        Map<java.util.concurrent.Future<String>, String> owner = new HashMap<>();
        for (String site : targets) {
            java.util.concurrent.Future<String> f = done.submit(() -> {
                try {
                    return call(site, "live", new JSONObject().put("url", arg).toString());
                } catch (Throwable t) {
                    return "";
                }
            });
            owner.put(f, site);
        }
        long deadline = System.currentTimeMillis() + LIVE_TOTAL_TIMEOUT_MS;
        int remaining = owner.size();
        int failed = 0;
        while (remaining-- > 0) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            java.util.concurrent.Future<String> f;
            try {
                f = done.poll(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            if (f == null) break;
            String site = owner.get(f);
            String raw;
            try {
                raw = f.get();
            } catch (Exception e) {
                failed++;
                continue;
            }
            if (raw == null || raw.trim().isEmpty() || "[]".equals(raw.trim())) {
                failed++;
                continue;
            }
            try {
                JSONArray arr = new JSONArray(raw);
                String siteName = SITES.getOrDefault(site, site);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject g = arr.optJSONObject(i);
                    if (g == null) continue;
                    JSONArray channels = g.optJSONArray("channel");
                    if (channels == null) channels = g.optJSONArray("channels");
                    if (channels == null || channels.length() == 0) continue;
                    groups.put(new JSONObject()
                            .put("site", site)
                            .put("site_name", siteName)
                            .put("group", g.optString("name", g.optString("group", "未分组")))
                            .put("channels", channels));
                }
            } catch (Exception ignore) {
                failed++;
            }
        }
        JSONObject out = new JSONObject();
        out.put("groups", groups);
        out.put("tried", targets.size());
        out.put("failed", failed);
        // config.lives 的直连播放列表：生态里直播的主力形态，前端据此列出可点的列表
        JSONArray playlists = new JSONArray();
        JSONArray lives = livesWithBuiltin();
        for (int i = 0; i < lives.length(); i++) {
            JSONObject l = lives.optJSONObject(i);
            if (l == null) continue;
            String u = l.optString("url", "");
            if (u.isEmpty()) continue;
            playlists.put(new JSONObject()
                    .put("name", l.optString("name", "直播源 " + (i + 1)))
                    .put("url", u));
        }
        out.put("playlists", playlists);
        return out.toString();
    }

    /**
     * `/api/lives` —— 直播源（{@code config.lives} 表）的增删查。
     *
     * <pre>
     * GET              → {ok, lives:[{name,url}]}
     * POST  {name,url,verify?} → 添加（同 url 已存在时只更新名字）
     * DELETE {url}     → 删除该 url 的全部条目
     * </pre>
     *
     * <p>只动 lives 表，**不碰 sites**。这正是它存在的意义：原来想加一条
     * 直播源，唯一的路是导入一份带 lives 的完整配置，代价是整张站点表被换掉。
     *
     * <p>{@code verify:true} 时先真拉一遍再写；失败就不写并回原因。默认不校验 ——
     * "现在打不开"不等于"以后都打不开"（源站临时维护很常见），强制校验会把
     * 用户挡在门外，而直播源本来就该允许先存着。
     */
    private static void handleLives(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod() == null ? "" : ex.getRequestMethod().toUpperCase();
        if ("GET".equals(method) || "HEAD".equals(method)) {
            JSONArray lives = livesWithBuiltin();
            sendJson(ex, 200, new JSONObject().put("ok", true)
                    .put("lives", lives)
                    // 内置源是"表为空时才给的默认值"，前端据此标出来，
                    // 让用户知道它可以删、删了就不会再来
                    // 还没被删的内置源 URL 列表（前端据此给设置页条目打「内置」标签）。
                    // ★ 以前是 boolean（"内置源是否生效"）；内置源有两条之后
                    //   必须逐条表达，否则删了一条就分不清剩下那条还算不算内置
                    .put("builtin", builtinLiveUrls())
                    .toString());
            return;
        }

        String body = read(ex.getRequestBody());
        JSONObject in;
        try {
            in = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (Exception e) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"bad json body\"}");
            return;
        }
        String url = in.optString("url", "").trim();
        JSONArray lives = new JSONArray(tableOrEmpty(loadConfigSites(), "lives").toString());

        if ("DELETE".equals(method)) {
            if (url.isEmpty()) {
                sendJson(ex, 400, "{\"code\":-1,\"msg\":\"url required\"}");
                return;
            }
            /*
             * 删内置源：它是"表里没有时补的默认项"，本来可能就不在表里。
             * 用逐条禁用标记把它关掉 —— 否则用户删完，下次进来表里没有，
             * 默认值又被端上来，等于删不掉。
             *
             * 内置源有两条（Guovin / iptv-org），**必须逐条记**：
             * 用户可能只不想要其中一条。
             */
            if (isBuiltinLiveUrl(url)) {
                boolean inTable = false;
                for (int i = 0; i < lives.length(); i++) {
                    JSONObject l = lives.optJSONObject(i);
                    if (l != null && url.equals(l.optString("url", "").trim())) inTable = true;
                }
                if (!inTable) {
                    JSONObject saved = loadConfigSites();
                    JSONArray off = new JSONArray();
                    for (String u : disabledBuiltin()) off.put(u);
                    off.put(url);
                    saved.put("builtinLiveOff", off);
                    if (!core.DataPaths.write("config.sites.json", saved.toString())) {
                        sendJson(ex, 500, "{\"code\":-1,\"msg\":\"cannot write config.sites.json\"}");
                        return;
                    }
                    invalidateConfigTables();
                    sendJson(ex, 200, new JSONObject().put("ok", true).put("removed", 1)
                            .put("lives", livesWithBuiltin()).toString());
                    return;
                }
            }
            JSONArray next = new JSONArray();
            int removed = 0;
            for (int i = 0; i < lives.length(); i++) {
                JSONObject l = lives.optJSONObject(i);
                if (l != null && url.equals(l.optString("url", "").trim())) {
                    removed++;
                    continue;
                }
                if (l != null) next.put(l);
            }
            if (removed == 0) {
                sendJson(ex, 404, "{\"code\":-1,\"msg\":\"not found\"}");
                return;
            }
            /*
             * 删的是内置源时，同时写逐条禁用标记。
             * 不写的话：表里那条没了 → 表可能变空 → 下次读表时空表又补默认值
             * → 用户发现"删了又回来了"。
             */
            boolean removedBuiltin = isBuiltinLiveUrl(url);
            if (!saveLives(next, removedBuiltin ? url : null)) {
                sendJson(ex, 500, "{\"code\":-1,\"msg\":\"cannot write config.sites.json\"}");
                return;
            }
            sendJson(ex, 200, new JSONObject().put("ok", true).put("removed", removed)
                    .put("lives", livesWithBuiltin()).toString());
            return;
        }

        if (!"POST".equals(method)) {
            sendJson(ex, 405, "{\"code\":-1,\"msg\":\"GET/POST/DELETE only\"}");
            return;
        }

        /*
         * 恢复内置源：清掉逐条禁用标记，让被删的内置源重新补回列表。
         *
         * 为什么需要：内置源可以逐条删，但删掉后想找回只能手动输地址 ——
         * 而且手动加回来的不再算「内置」（`builtinLiveOff` 仍记着它）。
         * 这个分支就是"删错了能撤回"，用户不用记那些 CDN 地址。
         */
        if (in.optBoolean("resetBuiltin", false)) {
            JSONObject saved = loadConfigSites();
            saved.remove("builtinLiveOff");
            if (!core.DataPaths.write("config.sites.json", saved.toString())) {
                sendJson(ex, 500, "{\"code\":-1,\"msg\":\"cannot write config.sites.json\"}");
                return;
            }
            invalidateConfigTables();
            sendJson(ex, 200, new JSONObject().put("ok", true)
                    .put("lives", livesWithBuiltin())
                    .put("builtin", builtinLiveUrls())
                    .toString());
            return;
        }

        if (url.isEmpty()) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"url required\"}");
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"url must be http(s)\"}");
            return;
        }

        // 同 url 已存在：只更新名字，不重复加（重复条目会让直播页列出两个一样的源）
        for (int i = 0; i < lives.length(); i++) {
            JSONObject l = lives.optJSONObject(i);
            if (l == null) continue;
            if (!url.equals(l.optString("url", "").trim())) continue;
            String nm = in.optString("name", "").trim();
            if (!nm.isEmpty()) l.put("name", nm);
            if (!saveLives(lives, null)) {
                sendJson(ex, 500, "{\"code\":-1,\"msg\":\"cannot write config.sites.json\"}");
                return;
            }
            sendJson(ex, 200, new JSONObject().put("ok", true).put("duplicate", true)
                    .put("lives", livesWithBuiltin())
                    .put("builtin", builtinLiveUrls())
                    .toString());
            return;
        }

        JSONObject verifyInfo = null;
        if (in.optBoolean("verify", false)) {
            verifyInfo = new JSONObject(livePlaylist(new JSONObject().put("url", url).toString()));
            int got = verifyInfo.optInt("channels", 0);
            if (!verifyInfo.optBoolean("ok", false) || got <= 0) {
                sendJson(ex, 200, new JSONObject()
                        .put("ok", false)
                        .put("verified", false)
                        .put("msg", verifyInfo.optString("msg", "列表里没有解析出频道"))
                        .toString());
                return;
            }
        }

        JSONObject entry = new JSONObject().put("url", url);
        String name = in.optString("name", "").trim();
        if (name.isEmpty()) name = hostNameOf(url);
        entry.put("name", name.isEmpty() ? ("直播源 " + (lives.length() + 1)) : name);
        lives.put(entry);
        if (!saveLives(lives, null)) {
            sendJson(ex, 500, "{\"code\":-1,\"msg\":\"cannot write config.sites.json\"}");
            return;
        }
        /*
         * ★ 必须回 livesWithBuiltin() 而不是 lives。
         *
         *   `lives` 只是**存储表**：内置源是"表里没有时补的默认项"，根本不在表里
         *   （用户删过才会留禁用标记）。早期这里回 raw lives，设置页加完一条
         *   自定义源就把两条内置源从列表里抹掉了 —— 后端数据没丢，但用户看到
         *   "内置的不显示了"，以为被覆盖。GET 一直是合并过的，所以只有 POST 后
         *   这一瞬间错位（2026-11-10 用户截图报障）。
         */
        JSONObject okOut = new JSONObject().put("ok", true).put("lives", livesWithBuiltin())
                .put("builtin", builtinLiveUrls());
        if (verifyInfo != null) {
            okOut.put("verified", true).put("channels", verifyInfo.optInt("channels", 0));
        }
        sendJson(ex, 200, okOut.toString());
    }

    /**
     * 写 lives 表并让 5 秒配置快照失效。只改这一个表，其余原样保留。
     *
     * @param disableBuiltinUrl 删的正好是某条内置源时传它的 url（记进逐条禁用
     *                          列表）；否则 null。内置源有两条，不能用一个
     *                          boolean 表示"全关"
     */
    private static boolean saveLives(JSONArray lives, String disableBuiltinUrl) {
        try {
            synchronized (IMPORT_LOCK) {
                JSONObject saved = loadConfigSites();
                saved.put("lives", lives);
                if (disableBuiltinUrl != null && !disableBuiltinUrl.isEmpty()) {
                    JSONArray off = new JSONArray();
                    for (String u : disabledBuiltin()) off.put(u);
                    if (!disabledBuiltin().contains(disableBuiltinUrl)) off.put(disableBuiltinUrl);
                    saved.put("builtinLiveOff", off);
                }
                if (!core.DataPaths.write("config.sites.json", saved.toString())) return false;
                invalidateConfigTables();
            }
            return true;
        } catch (Throwable t) {
            System.out.println("[lives] save failed: " + t);
            return false;
        }
    }

    /** 从 URL 里取主机名，给没填名字的源当默认显示名。 */
    private static String hostNameOf(String url) {
        try {
            String h = new URL(url).getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 解析 IPTV 播放列表。
     *
     * <p>两种格式都存在，必须都认：
     * <pre>
     * #EXTM3U
     * #EXTINF:-1 tvg-name="CCTV1" group-title="央视",CCTV-1
     * http://x/live.m3u8
     *
     * 央视,#genre#
     * CCTV1,http://x/live.m3u8
     * </pre>
     *
     * <p>txt 那种"分组名,#genre#"是行业事实标准写法，M3U 的 `group-title` 是可选的——
     * 没有分组信息的频道统一落到「未分组」，不要丢。
     *
     * <p>播放列表可能放在局域网（自建 IPTV hub），所以走**宽松档** SSRF：
     * 只拒回环与链路本地，和媒体代理一致。
     */
    private static String livePlaylist(String body) {
        JSONObject out = new JSONObject();
        try {
            JSONObject req = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
            String url = req.optString("url", "");
            if (url.isEmpty()) {
                out.put("ok", false).put("msg", "url required");
                return out.toString();
            }
            if (!core.UrlSafety.isSafeMediaUrl(url)) {
                out.put("ok", false).put("msg", "url not allowed");
                return out.toString();
            }
            String text;
            // 源的"新鲜度"：GitHub Pages 会给 Last-Modified，前端据此显示
            // 「更新于 X 天前」—— 实测 Guovin 那条停在 10-12（25 天前），
            // 这是死链率高的直接原因，必须在界面上能看见
            String lastModified = null;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(8_000);
                c.setReadTimeout(20_000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0");
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) {
                    out.put("ok", false).put("msg", "HTTP " + code);
                    return out.toString();
                }
                lastModified = c.getHeaderField("Last-Modified");
                text = read(c.getInputStream());
            } finally {
                c.disconnect();
            }
            JSONArray groups = parsePlaylist(text);
            int channels = 0;
            for (int i = 0; i < groups.length(); i++) {
                channels += groups.optJSONObject(i).optJSONArray("channels").length();
            }
            out.put("ok", true).put("groups", groups).put("channels", channels);
            if (lastModified != null && !lastModified.trim().isEmpty()) {
                out.put("updated", lastModified);
            }
            return out.toString();
        } catch (Throwable t) {
            try {
                out.put("ok", false).put("msg", "parse failed: " + t);
            } catch (Exception ignored) {
            }
            return out.toString();
        }
    }

    private static JSONArray parsePlaylist(String text) {
        JSONArray groups = new JSONArray();
        JSONObject byName = new JSONObject(); // group -> JSONObject（保存插入顺序靠数组）
        List<String> order = new ArrayList<>();
        String pendingName = null;
        String pendingGroup = null;
        try {
            // BOM / CRLF 都要吃掉，否则第一行 #EXTM3U 判断会失败。
            // 这里用 char 码点而不是转义：写转义时被工具链吃过一次，直接变成
            // 真控制字符写进字符串字面量，编译器只报"未结束的字符串文字"。
            String lf = String.valueOf((char) 10);
            String t = text.replace(String.valueOf((char) 0xFEFF), "")
                    .replace(String.valueOf((char) 13) + lf, lf)
                    .replace(String.valueOf((char) 13), lf);
            for (String rawLine : t.split(lf)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#EXTM3U")) continue;
                if (line.startsWith("#EXTINF")) {
                    pendingName = afterLastComma(line);
                    pendingGroup = attrOf(line, "group-title");
                    continue;
                }
                if (line.startsWith("#")) continue;
                if (line.endsWith(",#genre#")) {
                    // txt 格式的分组分隔行
                    pendingGroup = line.substring(0, line.length() - 8).trim();
                    continue;
                }
                int comma = line.indexOf(',');
                String channelName = pendingName;
                String channelUrl = line;
                if (channelName == null) {
                    // txt：`频道名,地址`；没有逗号就整行当地址
                    if (comma > 0 && !line.startsWith("http")) {
                        channelName = line.substring(0, comma).trim();
                        channelUrl = line.substring(comma + 1).trim();
                    } else {
                        channelName = "频道 " + (order.size() + 1);
                    }
                }
                if (!channelUrl.startsWith("http")) continue;
                String g = pendingGroup == null || pendingGroup.isEmpty() ? "未分组" : pendingGroup;
                /*
                 * 跳过源的元数据分组。
                 *
                 * Guovin 那类自动更新的列表会在最前面塞一个
                 * `group-title="🕘️更新时间"` + 一个时间戳"频道"，用户进直播页
                 * 默认选中的就是它 —— 右边显示一串 2026-10-12 11:25:50，
                 * 看着像坏了。它不是频道，只是列表的生成时间。
                 */
                if (isMetadataGroup(g)) {
                    pendingName = null;
                    continue;
                }
                JSONObject group = byName.optJSONObject(g);
                if (group == null) {
                    group = new JSONObject();
                    group.put("group", g);
                    group.put("channels", new JSONArray());
                    byName.put(g, group);
                    order.add(g);
                }
                group.getJSONArray("channels").put(new JSONObject()
                        .put("name", channelName == null ? "" : channelName)
                        .put("urls", new JSONArray().put(channelUrl)));
                pendingName = null;
            }
            for (String g : order) groups.put(byName.getJSONObject(g));
        } catch (Exception ignored) {
        }
        return groups;
    }

    /**
     * 这个分组名是不是"源的元数据"而不是真频道组。
     *
     * <p>只认两种明确写法（更新时间 / 更新日期），不做模糊匹配：真实频道组
     * 叫这些名字的概率极低，而误删一整个组比多显示一个时间戳更糟。
     */
    private static boolean isMetadataGroup(String g) {
        return g != null && (g.contains("更新时间") || g.contains("更新日期"));
    }

    private static String afterLastComma(String line) {
        int i = line.lastIndexOf(',');
        return i >= 0 && i < line.length() - 1 ? line.substring(i + 1).trim() : null;
    }

    private static String attrOf(String line, String key) {
        String needle = key + "=\"";
        int i = line.indexOf(needle);
        if (i < 0) return null;
        int start = i + needle.length();
        int end = line.indexOf('"', start);
        return end > start ? line.substring(start, end) : null;
    }

    // ---------------------------------------------------------------- 配置表消费

    /** config.sites.json 的短缓存：媒体代理每个分片都会查表，不能每次都读盘。 */
    private static volatile JSONObject CFG_SNAPSHOT = null;
    private static volatile long CFG_SNAPSHOT_AT = 0;

    private static JSONObject configTables() {
        long now = System.currentTimeMillis();
        JSONObject snap = CFG_SNAPSHOT;
        if (snap != null && now - CFG_SNAPSHOT_AT < 5000) return snap;
        snap = loadConfigSites();
        CFG_SNAPSHOT = snap;
        CFG_SNAPSHOT_AT = now;
        return snap;
    }

    private static void invalidateConfigTables() {
        CFG_SNAPSHOT = null;
        CFG_SNAPSHOT_AT = 0;
    }

    /**
     * `headers` 表：全局默认请求头。
     *
     * <p>只在调用方没给的时候兜底——spider 自己带的 Referer/UA 永远优先，
     * 否则会把"某站点专用头"套到别的站点上。
     */
    private static String defaultHeader(String key) {
        JSONArray rows = tableOrEmpty(configTables(), "headers");
        String want = key.toLowerCase();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.toLowerCase().equals(want)) return o.optString(k, "");
            }
        }
        return "";
    }

    /**
     * `hosts` 表：`"example.com=1.2.3.4"` 形式的域名覆盖。
     *
     * <p>只在 **http** 上生效。https 换 IP 会让 SNI 对不上，多数 CDN 直接
     * 421/SSL 错误——这条我们在 safeDns 上已经踩过一次，不再犯第二次。
     *
     * @return {@code [重写后的 URL, 原 Host 头]}，未命中返回 null
     */
    private static String[] tableHostOverride(URL u) {
        JSONArray rows = tableOrEmpty(configTables(), "hosts");
        String host = u.getHost();
        for (int i = 0; i < rows.length(); i++) {
            String row = rows.optString(i, "").trim();
            int eq = row.indexOf('=');
            if (eq <= 0) continue;
            String from = row.substring(0, eq).trim();
            String to = row.substring(eq + 1).trim();
            if (from.isEmpty() || to.isEmpty()) continue;
            if (from.equalsIgnoreCase(host)) {
                String rewritten = u.toString().replaceFirst(
                        "(?i)^(https?://)" + java.util.regex.Pattern.quote(host), "$1" + to);
                return new String[]{rewritten, from};
            }
            // 反向命中：入口已经改写成 IP 之后，后续每一跳拿到的 host 是 IP，
            // 这时仍要把原始域名补回 Host 头，否则虚拟主机/防盗链会拒。
            if (to.equalsIgnoreCase(host)) return new String[]{u.toString(), from};
        }
        return null;
    }

    /** ads 过滤开关：policy.filterAds，默认关（理由见调用点注释）。 */
    private static boolean filterAdsEnabled() {
        try {
            return loadPolicy().optBoolean("filterAds", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 该 URL 的主机是否命中 `ads` 表。
     *
     * <p>匹配照抄上游 `Util.containOrMatch`：配置项可以是普通子串（域名），
     * 也可以写成 `/正则/`。正则编译失败时退化成子串比较——配置里一个坏正则
     * 不该让整条流被静默丢掉。
     */
    private static boolean isAdHost(String url) {
        JSONArray rows = tableOrEmpty(configTables(), "ads");
        if (rows.length() == 0) return false;
        String host;
        try {
            host = new URL(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase();
        for (int i = 0; i < rows.length(); i++) {
            String rule = rows.optString(i, "").trim();
            if (rule.isEmpty()) continue;
            if (rule.length() > 2 && rule.startsWith("/") && rule.endsWith("/")) {
                try {
                    if (java.util.regex.Pattern.compile(rule.substring(1, rule.length() - 1))
                            .matcher(host).find()) return true;
                } catch (Exception ignore) {
                    if (h.contains(rule.toLowerCase())) return true;
                }
            } else if (h.contains(rule.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- rules 表

    /**
     * `rules` 表的一条。字段照抄 Fongmi 的 {@code bean.Rule}：
     * {@code hosts / regex / exclude / script / click}。
     *
     * <p>上游拿它喂 WebView 嗅探器（{@code Sniffer.getRule}）：按 host 选规则，
     * 再用 regex/exclude 判断某个 URL 值不值得拦。我们是 JVM，没有嗅探器，
     * 但**这套规则用来判断"这个地址像不像播放地址"完全够用**——
     * 正好补 type 0 页面嗅探的短板：现在三档正则会把广告 CDN 也当成候选。
     */
    private static final class SniffRule {
        String name = "";
        List<String> hosts = new ArrayList<>();
        List<String> regex = new ArrayList<>();
        List<String> exclude = new ArrayList<>();
        /** click 里的 URL 模板：命中后取真实地址（上游用它拼二次跳转） */
        List<String> click = new ArrayList<>();
    }

    /** 读 rules 表；坏 JSON / 缺字段都退化成空列表。 */
    private static List<SniffRule> loadRules() {
        List<SniffRule> out = new ArrayList<>();
        JSONArray arr = tableOrEmpty(configTables(), "rules");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            SniffRule r = new SniffRule();
            r.name = o.optString("name", "");
            JSONArray h = o.optJSONArray("hosts");
            if (h != null) for (int j = 0; j < h.length(); j++) r.hosts.add(h.optString(j, ""));
            JSONArray rx = o.optJSONArray("regex");
            if (rx != null) for (int j = 0; j < rx.length(); j++) r.regex.add(rx.optString(j, ""));
            JSONArray ex = o.optJSONArray("exclude");
            if (ex != null) for (int j = 0; j < ex.length(); j++) r.exclude.add(ex.optString(j, ""));
            JSONArray ck = o.optJSONArray("click");
            if (ck != null) for (int j = 0; j < ck.length(); j++) r.click.add(ck.optString(j, ""));
            out.add(r);
        }
        return out;
    }

    /**
     * 按 URL 找命中的规则。语义照抄 {@code Sniffer.getRule}：
     * 同时拿"页面 host"和"url 查询参数里的 host"去比，
     * **第一个命中的规则生效**（顺序有意义，配置里靠前的优先）。
     */
    private static SniffRule ruleFor(String pageUrl, String mediaUrl) {
        List<SniffRule> rules = loadRules();
        if (rules.isEmpty()) return null;
        String hosts;
        try {
            String a = new URL(pageUrl).getHost();
            String b = "";
            try {
                String q = new URL(pageUrl).getQuery();
                if (q != null) {
                    for (String kv : q.split("&")) {
                        if (kv.startsWith("url=") || kv.startsWith("v=")) {
                            b = new URL(URLDecoder.decode(kv.substring(kv.indexOf('=') + 1),
                                    StandardCharsets.UTF_8.name())).getHost();
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
                /* 查询参数里没有可解析的 url 就只比页面 host */
            }
            hosts = (a == null ? "" : a) + "," + (b == null ? "" : b);
        } catch (Exception e) {
            return null;
        }
        String h = hosts.toLowerCase();
        for (SniffRule r : rules) {
            for (String want : r.hosts) {
                if (want == null || want.isEmpty()) continue;
                if (matchHost(h, want)) return r;
            }
        }
        return null;
    }

    /** 子串或 `/正则/`，与 `isAdHost` 同一套写法。 */
    private static boolean matchHost(String hostCsv, String want) {
        String w = want.trim();
        if (w.isEmpty()) return false;
        if (w.length() > 2 && w.startsWith("/") && w.endsWith("/")) {
            try {
                return java.util.regex.Pattern.compile(w.substring(1, w.length() - 1))
                        .matcher(hostCsv).find();
            } catch (Exception ignore) {
                return hostCsv.contains(w.toLowerCase());
            }
        }
        return hostCsv.contains(w.toLowerCase());
    }

    /**
     * 用 rules 给嗅探候选打分：命中规则且不在 exclude 里 → 接受；命中 exclude → 拒绝。
     *
     * <p>没有 rules 表时返回 null（表示"无意见"），调用方退回原来的三档逻辑——
     * 不能因为配置里没写 rules 就让页面嗅探整体失效。
     */
    private static Boolean ruleVerdict(String pageUrl, String mediaUrl) {
        try {
            SniffRule r = ruleFor(pageUrl, mediaUrl);
            if (r == null) return null;
            for (String ex : r.exclude) {
                if (ex == null || ex.isEmpty()) continue;
                if (mediaUrl.contains(ex)) return Boolean.FALSE;
            }
            for (String rx : r.regex) {
                if (rx == null || rx.isEmpty()) continue;
                if (mediaUrl.contains(rx)) return Boolean.TRUE;
            }
            // 命中 host 但没写 regex：上游此时仍会走默认 SNIFFER 正则，
            // 我们交给调用方的三档逻辑兜底，所以这里不确定
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- doh 表

    /**
     * `doh` 表：[{name, url, ips?}]。上游用它做安全 DNS 的服务商列表。
     *
     * <p>我们原来只认 policy.json 的 `safeDns` 开关 + 硬编码的公共 DoH 提供商，
     * 配置里给了 `doh` 表也不看。现在优先用配置里的第一个可用项——
     * 用户指定了 DoH 就说明他所在网络下公共 DoH 可能不通。
     */
    private static String dohProviderFromTable() {
        JSONArray rows = tableOrEmpty(configTables(), "doh");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            String url = o.optString("url", "").trim();
            if (url.startsWith("http")) return url;
        }
        return "";
    }

    /** 对 http URL 应用 hosts 表；返回 {实际连接地址, 需要补的 Host 头}。 */
    private static String[] mapHost(String url) {        try {
            URL u = new URL(url);
            if (!"http".equalsIgnoreCase(u.getProtocol())) return new String[]{url, ""};
            String[] m = tableHostOverride(u);
            return m == null ? new String[]{url, ""} : m;
        } catch (Exception e) {
            return new String[]{url, ""};
        }
    }

    /** 哪些 api 形态属于"外部脚本运行时"。jar（csp_）不算。 */
    private static boolean isScriptApi(String api) {
        if (api == null || api.isEmpty()) return false;
        String a = api.toLowerCase();
        return a.endsWith(".js") || a.startsWith("js:") || a.startsWith("node:")
                || a.endsWith(".py") || a.startsWith("py:");
    }

    /**
     * 排序：jar 源在前，同类型内按健康分降序。
     *
     * <p>为什么 jar 必须硬优先（而不是交给健康分一起排）：
     * jar 源是本地代码直接发请求，延迟只由目标站决定（实测 0.2~3s）；
     * 脚本源要多跳一次运行时容器（我们 → 容器 → 目标站），容器一忙
     * 整段卡死。两者**延迟下限差一个数量级**，混排会让容器故障时
     * 快源也被压在几百个慢源后面 —— 实测 OECT 第一轮只 returned=45。
     *
     * <p>组内仍按健康分排，保持原语义。
     */
    private static java.util.List<String> sortJarFirstThenScore(java.util.List<String> targets) {
        java.util.List<String> sorted = routes.HealthRoutes.sortByScore(targets);
        java.util.List<String> jars = new ArrayList<>(sorted.size());
        java.util.List<String> scripts = new ArrayList<>();
        for (String s : sorted) {
            if (isScriptApi(API.get(s))) scripts.add(s);
            else jars.add(s);
        }
        jars.addAll(scripts);
        return jars;
    }

    /**
     * 把动作转给外部脚本运行时（drpy）。
     *
     * <p>协议故意做成"和本站点分派同形"：`POST {runtime}/{site}/{action}`，
     * 请求体就是原来的 body，额外用头带脚本地址与 ext。这样运行时不需要
     * 反查我们的站点表，也不会把两边的配置语义绑死。
     *
     * <p>运行时地址从 `policy.json` 的 `jsRuntime` 读（默认空 = 未配置），
     * 与 `danmakuApi` 同一套思路：不内置端口，由部署方或插件注入。
     */
    /**
     * 这个失败是不是"运行时本身有问题"（而不是源站没内容）。
     *
     * <p>只有运行时故障才该累积熔断计数。源站返回空 list 是正常业务结果，
     * 计入熔断会让"某个冷门词搜不到"误伤整个运行时。
     *
     * <p>判据取 callScriptRuntime 抛出的几类特征串：连接被拒、读超时、
     * HTTP 5xx。这几类都能在它的错误分支里看到原文。
     */
    private static boolean looksLikeRuntimeFault(String out) {
        if (out == null) return true;
        String s = out;
        return s.contains("js runtime HTTP 5") || s.contains("js runtime HTTP 502")
                || s.contains("js runtime HTTP 503") || s.contains("js runtime HTTP 504")
                || s.contains("Connection refused") || s.contains("Read timed out")
                || s.contains("connect timed out") || s.contains("connect timed out")
                || s.contains("SocketTimeoutException");
    }

    /**
     * 脚本运行时的**熔断器**（不是"跳过源"，是"缩短超时"）。
     *
     * <p><b>为什么不跳过源</b>：跳过会让那批源在冷却期内彻底消失，
     * 容器恢复了也发现不了（表现是"配了 200 多个源却只有 10 个可用"）。
     * 熔断只压超时，源仍然参与每一轮搜索，恢复后的第一次调用就能自证可用。
     *
     * <p><b>为什么要熔断</b>：所有脚本源共享同一个运行时容器，属同一故障域。
     * 容器挂掉时逐个等 30 秒是纯浪费——12 个并发槽 × 30 秒预算只能试 12 个源，
     * jar 源全被饿死（实测 OECT 第二轮起 returned=0）。
     * 熔断期用 1 秒超时：同样的预算能试 360 个源，等于"快速失败"。
     *
     * <p>判据是**连续失败计数**（不按时间窗）：连续 3 次失败即熔断，
     * 任意一次成功立刻闭合。比"5 分钟冷却"更贴近真实状态。
     */
    private static final java.util.concurrent.atomic.AtomicInteger scriptRuntimeFails =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** 恢复正常所需连续成功次数（与阈值对称，避免抖动）。 */
    private static final java.util.concurrent.atomic.AtomicInteger scriptRuntimeOks =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** 正常超时：容器健康时多数源 1~2 秒返回，8 秒足够且不误杀。 */
    private static final int SCRIPT_RUNTIME_TIMEOUT_MS = 8_000;
    /**
     * "这个源上次卡过"时用的缩短超时。
     *
     * <p><b>为什么不是 1 秒</b>（2026-10-30 OECT 实测，试过 1 秒已回滚）：
     * drpy 源要多跳一层外部容器（我们 → Node → 目标站），处理一个源
     * 本身就要 2~6 秒。压到 1 秒等于**必然超时**，于是所有源一起失败、
     * `timedOut` 从 194 涨到 670、结果从 28 条掉到 0 条。
     *
     * <p>3 秒是"能跑完大部分失败路径、又比 8 秒省一半以上"的位置：
     * 连不上的站通常在 2~3 秒内就会报 ECONNREFUSED / ENOTFOUND，
     * 真正能出结果的源 1~2 秒就返回，两者都能落在这个窗口内。
     */
    private static final int SCRIPT_RUNTIME_FAST_TIMEOUT_MS = 3_000;
    /**
     * 连续失败多少次算熔断。
     *
     * <p><b>从 3 降到 1</b>（2026-10-30 修正）：所有脚本源共享同一个运行时容器，
     * 属同一故障域 —— 第一个源超时基本就说明容器不可用。等 3 次意味着
     * 白付 3 × 8 秒；改成 1 次后，第一轮首个超时就把后续源压到 1 秒快速试。
     *
     * <p>误判风险很低：容器健康时脚本源 1~2 秒返回，不会触发；
     * 且恢复走对称判据 —— 连续成功 {@value #SCRIPT_RUNTIME_FAIL_THRESHOLD} 次才闭合
     * （见 recordScriptRuntime）。注意"熔断期间超时 1 秒"仍会真实调用容器，
     * 所以只要容器恢复，第一个 1 秒内返回的源就会开启恢复计数。
     */
    private static final int SCRIPT_RUNTIME_FAIL_THRESHOLD = 1;

    /** 熔断是否打开（打开 = 用 1 秒超时快速试）。 */
    private static boolean scriptRuntimeBreakerOpen() {
        return scriptRuntimeFails.get() >= SCRIPT_RUNTIME_FAIL_THRESHOLD;
    }

    /**
     * 运行时服务的健康状态缓存。
     *
     * <p><b>为什么不只看"熔断计数"</b>：熔断是按**调用失败**累加的，
     * 而"要不要提这一批源"是**提交前**的决定 —— 前者滞后一整轮。
     * 实测 OECT：226 个脚本源每轮各等 1~8 秒，光"证明容器坏了"就要
     * 十几秒，而这一轮的结果已经被拖垮了。
     *
     * <p>所以加一条**主动探测 + 短缓存**：每 {@value #RT_PROBE_TTL_MS} 毫秒
     * 最多探测一次 `/health`，探不通就把全部脚本源整类刨除本轮。
     * 探测本身有 800ms 上限，不会成为新的慢点。
     *
     * <p><b>为什么"整类刨除"是对的</b>：所有脚本源共用同一个容器，
     * 属同一故障域。容器挂了，226 个源一个都不可能成功 ——
     * 继续提交只是把并发槽一个个喂给 1~8 秒的超时。
     *
     * <p><b>恢复</b>：缓存到期的下一次探测只要通了就立刻放行，
     * 不需要人工介入，也不需要重启后端。
     */
    private static final java.util.concurrent.atomic.AtomicLong rtProbeAt =
            new java.util.concurrent.atomic.AtomicLong(0);
    private static volatile boolean rtHealthy = true;
    private static final long RT_PROBE_TTL_MS = 15_000L;

    /**
     * 探测运行时是否可用；结果缓存 15 秒。
     *
     * <p>失败只影响**本轮要不要提交脚本源**，不影响 jar 源 ——
     * 这正是 jar 优先之外的第二道保险：容器挂了，jar 源照样全跑。
     */
    private static boolean scriptRuntimeUsable() {
        long now = System.currentTimeMillis();
        long last = rtProbeAt.get();
        if (now - last < RT_PROBE_TTL_MS) return rtHealthy;
        if (!rtProbeAt.compareAndSet(last, now)) return rtHealthy;
        String base = loadPolicy().optString("jsRuntime", "");
        if (base.isEmpty()) base = System.getenv().getOrDefault("MEOW_JS_RUNTIME", "");
        if (base.isEmpty()) {
            // 没配运行时：不是"挂了"，而是这类源本来就不该提交
            rtHealthy = false;
            return false;
        }
        boolean ok = false;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(base.replaceAll("/$", "") + "/health").openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(800);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            ok = code == 200;
            try {
                c.getInputStream().close();
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
            ok = false;
        }
        if (ok != rtHealthy) {
            core.LogBuffer.info("search", "jsRuntime 健康探测 " + (ok ? "恢复" : "失败")
                    + " → " + (ok ? "脚本源恢复参与" : "本轮起跳过全部脚本源"));
            System.err.println("[search] jsRuntime probe " + (ok ? "UP" : "DOWN"));
        }
        rtHealthy = ok;
        return ok;
    }

    /**
     * 单个脚本源最近一次"卡满超时"的时间戳。
     *
     * <p><b>为什么按源记，而不只是全局熔断</b>：容器可能只是"某几个源的
     * 规则卡在远程拉取"，其余源是好的。全局熔断会把好源一起压成 1 秒
     * （虽然它们 1 秒内能返回，但边界上可能误杀）。按源记能精确到
     * "这一批源最近卡过"，让它们优先用缩短超时试。
     *
     * <p><b>TTL 必须是"明显大于一轮搜索耗时"的量级</b>。
     * 踩过（2026-10-30 OECT 实测）：原来是 60 秒，而 OECT 上一轮
     * （270 源、12 并发、含 134 个失效源）就要 30 秒 —— 两轮不到标记
     * 就过期了，慢源永远重新从 8 秒档开始，降档效果完全被抵消。
     * 实测连续跑 11 轮，耗时稳定在 30~32 秒，一次都没收敛。
     *
     * <p>10 分钟的依据：真正的慢源（站点已关停、DNS 解析不动）不会在
     * 几分钟内自己变好；而运行时整体恢复时可以靠 {@link #rtHealthy}
     * 的 15 秒探测兜底，不依赖这个 TTL 来"发现恢复"。
     * 表里只放"最近卡过"的源，正常源不进，不会无限增长。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> scriptSlowUntil =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 单个源被判定"慢"之后，多久内用缩短超时。 */
    private static final long SCRIPT_SLOW_TTL_MS = 10L * 60 * 1000;

    /** 这个脚本源最近是不是卡过（60 秒内）。 */
    private static boolean scriptSiteSlow(String site) {
        Long until = scriptSlowUntil.get(site);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            scriptSlowUntil.remove(site);
            return false;
        }
        return true;
    }

    /** 记下"这个源卡满了超时"，60 秒内它用快速超时。 */
    private static void markScriptSiteSlow(String site) {
        if (site == null || site.isEmpty()) return;
        scriptSlowUntil.put(site, System.currentTimeMillis() + SCRIPT_SLOW_TTL_MS);
        // 表可能被大量一次性慢源撑大：超过 512 条时清掉已过期的
        if (scriptSlowUntil.size() > 512) {
            long now = System.currentTimeMillis();
            scriptSlowUntil.entrySet().removeIf(e -> now >= e.getValue());
        }
    }

    /**
     * 记一次脚本运行时调用的成败，驱动熔断状态。
     *
     * <p><b>为什么要"连续成功 N 次"才关，而不是成功 1 次就关</b>
     * （2026-10-30 OECT 实测）：容器半死时慢源与快源混杂返回，
     * 成功一次就清零会让熔断反复开合 —— 日志里
     * "进入熔断" → "熔断关闭" 连续刷了十几轮，每次开合都改变超时，
     * 整体耗时反而不可预测（实测同一份配置 8s~31s 跳变）。
     *
     * <p>改成对称判据：
     *   - 失败：连续 {@value #SCRIPT_RUNTIME_FAIL_THRESHOLD} 次 → 熔断
     *   - 成功：连续 {@value #SCRIPT_RUNTIME_FAIL_THRESHOLD} 次 → 闭合
     * 中间任一相反结果都把对方计数清零，保持"连续"语义。
     *
     * <p>阈值当前为 1（见常量的理由），所以对称判据退化成"一次翻面"；
     * 若将来把阈值调大，这里的逻辑不用改。
     */
    private static void recordScriptRuntime(boolean ok) {
        if (ok) {
            scriptRuntimeFails.set(0);
            int n = scriptRuntimeOks.incrementAndGet();
            if (n == SCRIPT_RUNTIME_FAIL_THRESHOLD && scriptRuntimeBreakerOpenPreviously) {
                scriptRuntimeBreakerOpenPreviously = false;
                core.LogBuffer.info("search", "jsRuntime 熔断关闭（连续成功 " + n + " 次）");
            }
        } else {
            scriptRuntimeOks.set(0);
            int n = scriptRuntimeFails.incrementAndGet();
            if (n == SCRIPT_RUNTIME_FAIL_THRESHOLD) {
                scriptRuntimeBreakerOpenPreviously = true;
                core.LogBuffer.warn("search", "jsRuntime 连续失败达 " + n
                        + " 次，进入熔断（超时压到 " + SCRIPT_RUNTIME_FAST_TIMEOUT_MS + "ms）");
            }
        }
    }

    /** 上一次的状态，只为"翻转时才打日志"（避免刷屏）。 */
    private static volatile boolean scriptRuntimeBreakerOpenPreviously = false;

    private static String callScriptRuntime(String site, String api, String action, String body)
            throws IOException {
        String base = loadPolicy().optString("jsRuntime", "");
        // 容器部署不方便改 policy.json，允许用环境变量兜底（compose 里指向 drpy 服务）
        if (base.isEmpty()) base = System.getenv().getOrDefault("MEOW_JS_RUNTIME", "");
        if (base.isEmpty()) {
            return "{\"code\":-1,\"msg\":\"jsRuntime 未配置：该源是 drpy 脚本，需要先启动 JS 运行时插件\"}";
        }
        String url = base.replaceAll("/$", "") + "/" + URLEncoder.encode(site, "UTF-8")
                + "/" + URLEncoder.encode(action, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            /*
             * ★ 超时按运行时"健康度"动态给，不要写死 30 秒。
             *
             * 写死 30 秒的代价可以算出来：只有 12 个并发槽时，
             * 一轮 30 秒预算最多只能试 12 个脚本源（12×30÷30）。
             * 而配置里脚本源动辄 200+ 个、且全指向同一个运行时容器 ——
             * 容器一挂，这 200 多个源会逐个耗尽 30 秒，把 jar 源彻底饿死。
             * 实测 OECT：第一轮 returned=45、第二轮起全是 0。
             *
             * 改成"正常 8 秒 / 熔断期 1 秒"：
             *   - 容器正常时多数源 1~2 秒返回，8 秒足够，不误杀
             *   - 容器挂了时 1 秒快速失败，12 槽 × 30 秒能过 360 个源
             *   - 源**一个都不跳过**，只是"快速证明它现在不可用"，
             *     容器一旦恢复，下一次调用就能在 1 秒内成功并自动闭合熔断
             */
            boolean open = scriptRuntimeBreakerOpen() || scriptSiteSlow(site);
            int t = open ? SCRIPT_RUNTIME_FAST_TIMEOUT_MS : SCRIPT_RUNTIME_TIMEOUT_MS;
            c.setConnectTimeout(Math.min(t, 3_000));
            c.setReadTimeout(t);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            // 脚本地址可能是 .js，也可能以 ./ 开头（相对配置根）。原样传，
            // 由运行时按自己的方式解析——两个地方各猜一次一定会不一致。
            c.setRequestProperty("X-DRPY-Api", URLEncoder.encode(api, "UTF-8"));
            String ext = EXT.getOrDefault(site, "");
            if (!ext.isEmpty()) c.setRequestProperty("X-DRPY-Ext", URLEncoder.encode(ext, "UTF-8"));
            c.getOutputStream().write((body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8));
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = read(in);
            if (code >= 400) {
                return "{\"code\":-1,\"msg\":" + JSONObject.quote("js runtime HTTP " + code + ": " + (text.length() > 200 ? text.substring(0, 200) : text)) + "}";
            }
            return emptyFallback(action, text);
        } catch (java.net.SocketTimeoutException te) {
            /*
             * 超时必须在这里转成 JSON，不能让 IOException 冒泡出去。
             *
             * 原因：call() 只 catch 自己的逻辑异常，IOException 会直接抛到
             * 搜索任务层；那一层把所有异常都变成空串，于是"运行时超时"和
             * "这个源没内容"在日志里完全无法区分 —— 而前者正是需要驱动熔断的。
             */
            return "{\"code\":-1,\"msg\":\"js runtime SocketTimeoutException: "
                    + JSONObject.quote(String.valueOf(te.getMessage())) + "\"}";
        } catch (java.net.ConnectException ce) {
            return "{\"code\":-1,\"msg\":\"js runtime Connection refused: "
                    + JSONObject.quote(String.valueOf(ce.getMessage())) + "\"}";
        } finally {
            c.disconnect();
        }
    }

    /**
     * 这次调用有没有真的拿到内容（区别于"结构合法但空"）。
     *
     * <p><b>为什么不能复用 {@link #isErrorPayload}</b>：drpy 任务超时后返回
     * 空 body，`callScriptRuntime` 用 `emptyFallback` 把它兜成 `{"list":[]}` —
     * 结构完全合法、`code` 字段都没有，`isErrorPayload` 判成成功。
     * 于是"慢且没产出"的源在健康表里记成一次成功，永远不会降档，
     * 每轮继续烧 3~6 秒（实测 OECT 近百个这样的源）。
     *
     * <p>判据按动作区分：
     * <ul>
     *   <li>search：`list` 非空才算有产出（空数组 = 没搜到，但网络是通的）</li>
     *   <li>detail / home / category / play / action / proxy：响应体非空即可</li>
     * </ul>
     */
    private static boolean hasRealPayload(String action, String out) {
        if (out == null) return false;
        String t = out.trim();
        if (t.isEmpty()) return false;
        if ("search".equals(action)) {
            try {
                JSONArray list = new JSONObject(t).optJSONArray("list");
                return list != null && list.length() > 0;
            } catch (Exception e) {
                return false;
            }
        }
        // 其余动作只要不是空壳就行
        return !"{}".equals(t) && !"[]".equals(t);
    }

    /** spider 把错误写进 body（{"code":-1,...}）时也算失败。 */
    private static boolean isErrorPayload(String out) {
        if (out == null) return true;
        String t = out.trim();
        if (!t.startsWith("{")) return false;
        try {
            JSONObject o = new JSONObject(t);
            return o.optInt("code", 0) < 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * spider 返回空串时补一个结构合法的空结果。
     *
     * <p><b>为什么需要</b>：{@code Spider} 基类的 {@code detailContent} /
     * {@code searchContent} / {@code playerContent} 默认实现都是 {@code return ""}。
     * 没覆盖这些方法的源（实测 {@code Douban} 就没有 {@code detailContent}）
     * 会让宿主原样透传空 body，客户端 {@code JSON.parse("")} 抛异常，
     * 只能显示"后端返回空响应（服务可能正在重启）"——把"这个源不支持详情"
     * 误报成"后端挂了"，排查方向完全错。
     *
     * <p>空 body 违反 HTTP API 契约，兜底成空结构后客户端能正常走"无数据"分支。
     */
    private static String emptyFallback(String action, String raw) {
        if (raw != null && !raw.trim().isEmpty()) return raw;
        switch (action) {
            case "detail":
            case "category":
            case "search":
                return "{\"list\":[]}";
            case "home":
                return "{\"class\":[],\"filters\":{}}";
            case "play":
                return "{\"parse\":0,\"url\":\"\",\"msg\":\"该源未提供播放地址\"}";
            case "action":
                return "";
            default:
                return raw == null ? "" : raw;
        }
    }

    private static String callRaw(Object o, String action, JSONObject b, String site) throws Exception {
        synchronized (o) {
            switch (action) {
                case "home":
                    return str(method(o, "homeContent", boolean.class).invoke(o, true));
                case "category": {
                    String id = b.optString("id", b.optString("tid", ""));
                    String page = b.optString("page", b.optString("pg", "1"));
                    HashMap<String, String> filters = new HashMap<>();
                    JSONObject f = b.optJSONObject("filters");
                    if (f != null) {
                        // android-json 的 JSONObject 没有 keySet()，用 keys()
                        java.util.Iterator<String> it = f.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            filters.put(k, f.optString(k, ""));
                        }
                    }
                    return str(method(o, "categoryContent", String.class, String.class, boolean.class, HashMap.class)
                            .invoke(o, id, page, true, filters));
                }
                case "detail": {
                    String id = b.optString("id", b.optString("ids", ""));
                    // CatVod 约定 ids 是逗号分隔的多个 id（批量详情）。
                    // 但部分源（实测 SP360）的 id 本身就是 JSON 字符串，里面含逗号，
                    // 无条件 split 会把 JSON 切碎 → spider 里 JSONObject 解析抛
                    // "Unterminated object" → 详情页 500、这个源永远播不了。
                    // 所以：看起来像 JSON 的 id 整串传，不拆。
                    String t = id.trim();
                    java.util.List<String> ids = (t.startsWith("{") || t.startsWith("["))
                            ? new ArrayList<>(Arrays.asList(id))
                            : new ArrayList<>(Arrays.asList(id.split(",")));
                    return str(method(o, "detailContent", List.class).invoke(o, ids));
                }
                case "search": {
                    String wd = b.optString("wd", "");
                    String pg = b.optString("pg", "1");
                    return invokeSearch(o, wd, pg, site);
                }
                case "play": {
                    String flag = b.optString("flag", "");
                    String id = b.optString("id", "");
                    try {
                        return normalizePlay(str(method(o, "playerContent", String.class, String.class, List.class)
                                .invoke(o, flag, id, new ArrayList<String>())));
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        // 某些 spider 的 playerContent 对 id 格式很敏感（例如 Jpys 会抛
                        // ArrayIndexOutOfBoundsException）。这里降级成一条可读错误，
                        // 而不是让整个请求 500，前端可以提示用户换线路。
                        Throwable cause = ite.getCause() == null ? ite : ite.getCause();
                        return new JSONObject()
                                .put("parse", 1)
                                .put("url", "")
                                .put("msg", "该线路暂不可用：" + cause.getClass().getSimpleName())
                                .toString();
                    }
                }
                case "live": {
                    // CatVod 契约：liveContent(String url) 返回频道组数组。
                    // url 来自 config 的 lives 表，没配就传空串——
                    // 多数 spider 用不到这个参数，但要避免把 null 传进去触发 NPE。
                    String url = b.optString("url", "");
                    return normalizeLive(str(method(o, "liveContent", String.class).invoke(o, url)));
                }
                case "action":
                    return str(method(o, "action", String.class).invoke(o, b.optString("action", "")));
                default:
                    throw new IllegalArgumentException("unknown action: " + action);
            }
        }
    }

    /**
     * 调用 spider 的搜索，兼容 2 参与 3 参两种重载。
     *
     * <p><b>这是个真实的坑</b>：spider 基类同时声明了
     * {@code searchContent(key, quick)} 与 {@code searchContent(key, quick, pg)}，
     * 而基类的默认实现返回 {@code ""}。
     *
     * <p>部分 spider（实测 {@code AppYQK}）<b>只覆盖了 2 参版本</b>。
     * 如果宿主无条件调 3 参版，就会落到基类默认实现，<b>静默返回空串</b>——
     * 表现为"搜索永远没结果，但首页正常、也不报错"。
     *
     * <p>策略：先试 3 参（带分页，更完整），结果为空再退回 2 参。
     * 两者都空就是真的没结果。
     */
    private static String invokeSearch(Object spider, String wd, String pg, String site) {
        String out = "";
        try {
            out = str(method(spider, "searchContent", String.class, boolean.class, String.class)
                    .invoke(spider, wd, false, pg));
        } catch (Throwable ignored) {
            // 没有 3 参重载，或执行异常 → 走 2 参
        }
        System.out.println("[search] 3-arg len=" + (out == null ? -1 : out.length())
                + " emptyList=" + isEmptyList(out == null ? "" : out));
        if (out != null && !out.trim().isEmpty() && !isEmptyList(out)) return out;

        try {
            String two = str(method(spider, "searchContent", String.class, boolean.class)
                    .invoke(spider, wd, false));
            System.out.println("[search] 2-arg len=" + (two == null ? -1 : two.length()));
            // 2 参有内容就优先用它；否则保留 3 参的原始返回（可能是带分页信息的空结构）
            if (two != null && !two.trim().isEmpty() && !isEmptyList(two)) return two;
            if (out != null && !out.trim().isEmpty()) return out;
            return two == null ? "" : two;
        } catch (Throwable t) {
            // ★ 只记 t.toString() 得到的是 "java.lang.reflect.InvocationTargetException"，
            //   真实原因（NoSuchMethod / NPE / 源站异常）全在 cause 里。踩过：TV 上几十条
            //   一模一样的 "2-arg failed: InvocationTargetException"，既不知道是哪个源，
            //   也不知道为什么 —— 等于没记。这里解包到最内层并带上站点名。
            Throwable root = unwrap(t);
            String detail = site + ": " + root.getClass().getSimpleName()
                    + (root.getMessage() == null ? "" : " — " + root.getMessage());
            System.out.println("[search] 2-arg failed " + detail);
            core.LogBuffer.error("search", "2-arg failed " + detail);
            return out == null ? "" : out;
        }
    }

    /** 剥掉 InvocationTargetException / ExceptionInInitializerError 这层包装。 */
    private static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c
                && (c instanceof java.lang.reflect.InvocationTargetException
                    || c instanceof ExceptionInInitializerError
                    || c instanceof java.lang.reflect.UndeclaredThrowableException)) {
            c = c.getCause();
        }
        return c;
    }

    /** 判断 spider 返回的 JSON 是否"列表为空"（{@code {"list":[]}} 之类）。 */
    private static boolean isEmptyList(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray list = o.optJSONArray("list");
            return list != null && list.length() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Method method(Object o, String name, Class<?>... types) throws NoSuchMethodException {
        Method m = o.getClass().getMethod(name, types);
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * CatVod 的 play 结果里 url 有三种形状，前端只想要一个可用地址：
     *
     * <ul>
     *   <li>字符串：直接用；</li>
     *   <li>对象 {@code {"values":[{"n":"高清","v":"http://..."}],"position":0}}：
     *       CatVodSpider 现行画质形状，取 position 指向的那一项；</li>
     *   <li>数组：老式 {@code ["标清","http://a","高清","http://b"]} 成对写法，
     *       或纯地址 {@code ["http://a","http://b"]}。取第一个 http。</li>
     * </ul>
     *
     * <p>不管哪种形状都额外输出 {@code qualities:[{name,url}]}——
     * 归一化只该"多给一份可切换清单"，不该把标签信息丢掉：
     * 原实现把数组换成单串后，画质名就永久消失了。
     *
     * <p>{@code skips} / {@code subs} / {@code position} 原样透传，不做单位换算：
     * 它们的单位是**毫秒**（CatVodSpider 的 {@code Demo.skips()} 与
     * {@code Response.position()} 都按毫秒校验），换成本地口径会在两处各错一次。
     */
    private static String normalizePlay(String raw) {
        if (raw == null || raw.isEmpty()) return "{}";
        try {
            JSONObject o = new JSONObject(raw);
            JSONArray qualities = new JSONArray();
            Object u = o.opt("url");

            if (u instanceof JSONObject) {
                JSONObject q = (JSONObject) u;
                JSONArray values = q.optJSONArray("values");
                int picked = -1;
                if (values != null) {
                    for (int i = 0; i < values.length(); i++) {
                        JSONObject v = values.optJSONObject(i);
                        if (v == null) continue;
                        String name = v.optString("n", "");
                        String url = v.optString("v", "");
                        if (url.isEmpty()) continue;
                        qualities.put(new JSONObject().put("name", name).put("url", url));
                        if (picked < 0 && i == q.optInt("position", 0)) picked = qualities.length() - 1;
                    }
                    if (picked < 0 && qualities.length() > 0) picked = 0;
                }
                o.put("url", picked < 0 ? "" : qualities.getJSONObject(picked).optString("url", ""));
            } else if (u instanceof JSONArray) {
                JSONArray arr = (JSONArray) u;
                // 成对写法：偶数位是标签、奇数位是地址。
                // 判定必须同时要求"奇数位是 http"且"偶数位不是 http"——
                // 只看奇数位会把纯地址列表 ["http://a","http://b"] 误读成
                // "标签=http://a, 地址=http://b"（实测踩过）。
                boolean paired = arr.length() >= 2 && arr.length() % 2 == 0;
                for (int i = 0; paired && i < arr.length(); i += 2) {
                    if (arr.optString(i, "").startsWith("http")
                            || !arr.optString(i + 1, "").startsWith("http")) paired = false;
                }
                String chosen = null;
                if (paired) {
                    for (int i = 0; i < arr.length(); i += 2) {
                        String url = arr.optString(i + 1, "");
                        qualities.put(new JSONObject()
                                .put("name", arr.optString(i, "")).put("url", url));
                        if (chosen == null) chosen = url;
                    }
                } else {
                    for (int i = 0; i < arr.length(); i++) {
                        String s = arr.optString(i, "");
                        if (s.startsWith("http")) {
                            qualities.put(new JSONObject().put("name", "").put("url", s));
                            if (chosen == null) chosen = s;
                        }
                    }
                }
                o.put("url", chosen == null ? "" : chosen);
            }

            if (qualities.length() > 0) o.put("qualities", qualities);

            Object h = o.opt("header");
            if (h instanceof JSONObject) {
                o.put("header", ((JSONObject) h).toString());
            }
            return o.toString();
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * 直播结果归一化。
     *
     * <p>CatVod 约定是 `[{name, channel:[{name, urls:[...]}]}]`，但实测三种写法都有：
     * 顶层是数组、顶层是 `{data:[...]}`、或者干脆返回空串。前端只认数组，
     * 所以这里统一：非数组一律当空，单对象包成单元素数组。
     *
     * <p>`urls` 里每项形如 `http://x.m3u8$频道名`（`$` 后是备用显示名），
     * 这个 `$` 不能在这里剥——前端要用它做"同一频道的多线路"。
     */
    private static String normalizeLive(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "[]";
        try {
            String t = raw.trim();
            if (t.startsWith("[")) return t;
            JSONObject o = new JSONObject(t);
            JSONArray data = o.optJSONArray("data");
            if (data != null) return data.toString();
            JSONArray list = o.optJSONArray("list");
            if (list != null) return list.toString();
            // 单个组对象
            if (o.has("channel")) return "[" + t + "]";
            return "[]";
        } catch (Exception e) {
            return "[]";
        }
    }

    // ---------------------------------------------------------------- parse

    /**
     * `parses` 表里的一条。
     *
     * <p>字段语义照抄 CatVod：`type` 决定怎么解析，`url` 是解析服务的基址，
     * `ext.header` 是请求头，`ext.flag` 是"适配哪些播放线路"。
     */
    private static final class ParseEntry {
        String name = "";
        int type = 0;
        String url = "";
        JSONObject header = new JSONObject();
        List<String> flags = new ArrayList<>();
        /** ext 的原始 JSON 文本；type 2/3 要把整个 ext 编码后传给 jar 里的 parser 类 */
        String extJson = "";
    }

    /** 读数据目录 config.sites.json 的 parses 表；缺文件/坏 JSON 都返回空列表。 */
    private static List<ParseEntry> loadParses() {
        List<ParseEntry> out = new ArrayList<>();
        JSONArray arr = tableOrEmpty(loadConfigSites(), "parses");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            ParseEntry e = new ParseEntry();
            e.name = o.optString("name", "");
            e.type = o.optInt("type", 0);
            e.url = o.optString("url", "");
            JSONObject ext = o.optJSONObject("ext");
            if (ext != null) {
                // optString 对对象返回的是它的 JSON 文本，正好是下发给 parser 的形态
                e.extJson = ext.toString();
                JSONObject h = ext.optJSONObject("header");
                if (h != null) e.header = h;
                JSONArray f = ext.optJSONArray("flag");
                if (f != null) {
                    for (int j = 0; j < f.length(); j++) e.flags.add(f.optString(j, ""));
                }
            } else {
                String raw = o.optString("ext", "");
                if (raw.startsWith("{")) e.extJson = raw;
            }
            out.add(e);
        }
        return out;
    }

    /**
     * `POST /api/parse`（也支持 GET 带 query，方便 curl）。
     *
     * <p>请求：{@code {"url":"https://site/v/123.html","flag":"线路1","name":"可选"}}
     * <br>响应：{@code {"ok":true,"url":"...","header":"{...}","from":"解析器名"}}
     *
     * <p><b>type 0 怎么处理</b>：上游是 WebView 嗅探。服务端没有浏览器内核，
     * 这里退一步抓 HTML 按特征找地址（见 tryPageSniff）——能覆盖苹果CMS 系与
     * 「JSON 里塞 m3u8」的页面，纯 JS 拼算的地址依然拿不到。返回的 {@code note}
     * 会说明是哪种命中方式，前端据此判断可信度，不会假装成功。
     *
     * <p><b>type 2/3 怎么处理</b>：{@code url} 是 jar 内 parser 类的后缀
     * （{@code Json<后缀>} / {@code Mix<后缀>}），反射调用，语义照抄
     * Fongmi 的 {@code JarLoader.jsonExt/jsonExtMix}。
     *
     * <p><b>type 4 的真实语义</b>（读 Fongmi `ParseJob.superParse` 确认）：
     * 并行跑所有 type 1 与 type 0，先成功者胜。我们这里的并发池对四种类型
     * 一视同仁地并发，"先成功者胜"的语义不变。
     */
    private static void handleParse(HttpExchange ex) throws Exception {
        String body = read(ex.getRequestBody());
        JSONObject req;
        try {
            req = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (Exception e) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"bad json body\"}");
            return;
        }
        Map<String, String> q = query(ex);
        String webUrl = req.optString("url", q.getOrDefault("url", ""));
        String flag = req.optString("flag", q.getOrDefault("flag", ""));
        String only = req.optString("name", q.getOrDefault("name", ""));
        if (webUrl.isEmpty()) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"url required\"}");
            return;
        }
        // 解析目标由调用方给，和 /api/proxy/stream 一样必须挡住内网，
        // 否则这个端点就成了"能读内网 JSON"的探测口
        if (!core.UrlSafety.isSafeHttpUrl(webUrl)) {
            sendJson(ex, 403, "{\"code\":-1,\"msg\":\"url not allowed\"}");
            return;
        }

        List<ParseEntry> all = loadParses();
        List<ParseEntry> candidates = new ArrayList<>();
        for (ParseEntry e : all) {
            if (!only.isEmpty() && !only.equals(e.name)) continue;
            if (!serverSideParse(e.type)) continue;
            if (!only.isEmpty() || matchesFlag(e, flag)) candidates.add(e);
        }
        // flag 过滤后为空时退回全部可用类型（与 Fongmi getParses(type, flag) 一致：
        // ext.flag 是"优先适配"，不是"白名单"，否则一条没配 flag 的线路就全废）
        if (candidates.isEmpty() && only.isEmpty()) {
            for (ParseEntry e : all) if (serverSideParse(e.type)) candidates.add(e);
        }
        if (candidates.isEmpty()) {
            int unsupported = 0;
            boolean hasBrowser = false;
            for (ParseEntry e : all) {
                if (serverSideParse(e.type)) continue;
                unsupported++;
                if (e.type == 0) hasBrowser = true;
            }
            String msg;
            if (unsupported == 0) msg = "配置里没有 parses 表，无法解析";
            else if (hasBrowser) msg = "配置里的 " + unsupported + " 条解析器都依赖 WebView 嗅探，服务端跑不了";
            else msg = "配置里的 " + unsupported + " 条解析器类型服务端跑不了";
            sendJson(ex, 200, new JSONObject().put("ok", false).put("msg", msg).toString());
            return;
        }

        // 并发跑：先成功者胜。用独立小池子，避免占满 POOL 把正常浏览请求挤住
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, candidates.size()));
        List<Future<JSONObject>> futures = new ArrayList<>();
        try {
            for (ParseEntry e : candidates) {
                futures.add(pool.submit(() -> tryParse(e, webUrl, flag, all)));
            }
            JSONObject lastError = null;
            long deadline = System.currentTimeMillis() + PARSE_TIMEOUT_MS;
            for (Future<JSONObject> f : futures) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                JSONObject r;
                try {
                    r = f.get(left, TimeUnit.MILLISECONDS);
                } catch (Exception ignore) {
                    continue; // 超时/异常都只算这一条失败，继续看别人
                }
                if (r != null && r.optBoolean("ok", false)) {
                    sendJson(ex, 200, r.toString());
                    return;
                }
                if (r != null) lastError = r;
            }
            JSONObject out = lastError == null ? new JSONObject() : lastError;
            out.put("ok", false);
            if (!out.has("msg")) out.put("msg", "所有解析器都没返回可用地址");
            // 告诉前端到底试了几个、还有几个是浏览器类没试——
            // 否则用户只看到"解析失败"，无法判断是源问题还是能力不足。
            // type 0 现在也会被尝试（服务端页面嗅探），所以不再统计"跳过的浏览器解析器"。
            out.put("tried", candidates.size());
            sendJson(ex, 200, out.toString());
        } finally {
            pool.shutdownNow();
        }
    }

    /** `ext.flag` 为空表示"通用"；否则要求线路名命中其中一项（不区分大小写）。 */
    private static boolean matchesFlag(ParseEntry e, String flag) {
        if (e.flags.isEmpty()) return true;
        if (flag == null || flag.isEmpty()) return true;
        String f = flag.toLowerCase();
        for (String x : e.flags) {
            if (x != null && f.contains(x.toLowerCase())) return true;
        }
        return false;
    }

    /**
     * 跑一条 type1 解析器：{@code GET {entry.url}{webUrl}}，从 JSON 里取 url。
     *
     * <p>响应形状兼容两种（实测上游 `readJsonResult` 就是这么读的）：
     * 顶层 {@code url}，或 {@code data.url}。
     * 同时把响应里的 ua/referer/cookie 收进 header 带回播放器——
     * 解析出的地址大多需要和解析服务一致的 Referer 才能播。
     */
    private static JSONObject tryOneParse(ParseEntry e, String webUrl) {
        JSONObject out = new JSONObject();
        HttpURLConnection c = null;
        try {
            out.put("from", e.name);
            String target = e.url + webUrl; // CatVod 是字符串直拼，不是 URL 编码
            c = (HttpURLConnection) new URL(target).openConnection();
            c.setConnectTimeout(6_000);
            c.setReadTimeout(10_000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            java.util.Iterator<String> hk = e.header.keys();
            while (hk.hasNext()) {
                String key = hk.next();
                c.setRequestProperty(key, e.header.optString(key, ""));
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                out.put("ok", false).put("msg", "解析接口 HTTP " + code);
                return out;
            }
            String text = read(c.getInputStream());
            return resultFromParser(new JSONObject(text), e.header, e.name);
        } catch (Exception ex) {
            // android-json 的 put 抛受检异常，catch 里不能再抛
            try {
                out.put("ok", false).put("msg", "解析失败：" + ex.getClass().getSimpleName());
            } catch (Exception ignored) {
                /* 构造错误响应都失败就只能返回空对象 */
            }
            return out;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** type 1/2/3 服务端都能跑；type 0 用页面嗅探近似（见 tryPageSniff）。 */
    private static boolean serverSideParse(int type) {
        return type == 0 || type == 1 || type == 2 || type == 3;
    }

    /** 按 type 分派：1 走 HTTP 直连，2/3 反射 jar 里的 parser 类，0 走页面嗅探。 */
    private static JSONObject tryParse(ParseEntry e, String webUrl, String flag, List<ParseEntry> all) {
        if (e.type == 2) return tryJsonExt(e, webUrl, all);
        if (e.type == 3) return tryJsonMix(e, webUrl, flag, all);
        if (e.type == 0) return tryPageSniff(e, webUrl);
        return tryOneParse(e, webUrl);
    }

    /**
     * type 0 的服务端近似实现。
     *
     * <p>上游是「开 WebView 打开播放页，拦请求拿媒体地址」。JVM 宿主里没有浏览器内核，
     * 所以这里退一步：抓 HTML，按常见播放页的特征找地址。覆盖的是苹果CMS 系的
     * {@code player_aaaa} 与「JSON 里塞 m3u8」两类（占网页源绝大多数），
     * 纯前端算出来的地址（JS 拼签名）依然拿不到——那种只能靠客户端 WebView。
     *
     * <p>返回的 header 带 Referer=页面地址：这类地址几乎都校验 Referer。
     */
    private static JSONObject tryPageSniff(ParseEntry e, String webUrl) {
        JSONObject out = new JSONObject();
        try {
            out.put("from", e.name);
            String page = e.url.isEmpty() ? webUrl : e.url + webUrl; // 与 startWeb 的拼接方式一致
            if (!core.UrlSafety.isSafeHttpUrl(page)) return parseFail(out, "播放页地址不被允许");
            String[] got = fetchText(page, webUrl, 2);
            if (got == null) return parseFail(out, "播放页抓取失败");
            String[] hit = sniffMediaUrls(got[0], got[1]);
            if (hit == null) return parseFail(out, "页面里没有找到可播放地址（可能需要 JS 渲染）");
            /*
             * 第一档（player_config）拿到的是**播放器初始化参数**，不等于媒体地址。
             * MacCMS 有两种常见形态正好踩这里：
             *   · url 是下一层播放页（/play/123-1-1.html）
             *   · encrypt:1 时 url 是 base64
             * 不校验就直接 ok:true 返回，播放器看到的是"manifest 解析失败"，
             * 而且**永远等不到换线**——错误被归因成解码/清单问题。
             *
             * 只多跟一层：再深就不是嗅探，是把别人的站当爬虫代理了。
             */
            String resolved = resolveSniffedMedia(hit[0], got[1], 1);
            if (resolved == null) {
                return parseFail(out, "播放页没有给出媒体地址（可能是加密或需要 JS 渲染）");
            }
            if (!resolved.equals(hit[0])) {
                out.put("note", (hit[1].isEmpty() ? "" : hit[1] + "+") + "resolved");
            }
            hit = new String[]{resolved, hit[1]};
            // rules 表是否决/确认层：命中 exclude 就换下一个候选，命中 regex 就直接采信。
            // 三档正则只认"长得像媒体地址"，分不清正片和广告 CDN；rules 是配置作者
            // 针对具体站点写的，可信度更高。没有 rules 表时 ruleVerdict 返回 null，
            // 保持原行为不变。
            if (Boolean.FALSE.equals(ruleVerdict(page, hit[0]))) {
                String[] alt = sniffNextCandidate(got[0], got[1], hit[0], page);
                if (alt == null) return parseFail(out, "候选地址被 rules 表排除");
                hit = alt;
            }
            JSONObject header = new JSONObject();
            header.put("Referer", got[1]);
            header.put("User-Agent", "Mozilla/5.0");
            out.put("ok", true).put("url", hit[0]).put("header", header.toString());
            if (!hit[1].isEmpty()) out.put("note", hit[1]);
            return out;
        } catch (Throwable t) {
            return parseFail(out, "页面嗅探失败：" + t.getClass().getSimpleName());
        }
    }

    /**
     * 抓页面；{@code depth} 是还能跟几层 iframe。
     *
     * <p>返回 {@code {html, finalUrl}}，finalUrl 用来把相对地址补全、并作为 Referer。
     * 每跳都过严格 SSRF 校验：iframe 的地址来自对方页面，不能无条件信任。
     */
    private static String[] fetchText(String url, String referer, int depth) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(6_000);
            c.setReadTimeout(8_000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            c.setRequestProperty("Referer", referer);
            int code = c.getResponseCode();
            String location = c.getHeaderField("Location");
            if (code >= 300 && code < 400 && location != null && depth > 0) {
                String next = new URL(new URL(url), location).toString();
                if (!core.UrlSafety.isSafeHttpUrl(next)) return null;
                c.disconnect();
                return fetchText(next, referer, depth - 1);
            }
            if (code < 200 || code >= 300) return null;
            String text = readLimited(c.getInputStream(), 600_000);
            String base = c.getURL() == null ? url : c.getURL().toString();
            // 页面里常把播放器地址放在 iframe：跟一层（同源才跟，避免变成任意外链抓取器）
            String iframe = firstIframe(text, base);
            if (iframe != null && depth > 0 && sameOrigin(iframe, base)
                    && core.UrlSafety.isSafeHttpUrl(iframe)) {
                String[] inner = fetchText(iframe, base, depth - 1);
                if (inner != null && sniffMediaUrls(inner[0], inner[1]) != null) return inner;
            }
            return new String[]{text, base};
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 读最多 max 字节：播放页再大也只需要头部这些字符。 */
    private static String readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            int take = Math.min(n, max - total);
            if (take <= 0) break;
            bos.write(buf, 0, take);
            total += take;
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String firstIframe(String html, String base) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<iframe[^>]+src=[\"']([^\"']+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html);
        while (m.find()) {
            try {
                String abs = new URL(new URL(base), m.group(1)).toString();
                if (abs.startsWith("http")) return abs;
            } catch (Exception ignored) {
                /* 坏地址跳过 */
            }
        }
        return null;
    }

    private static boolean sameOrigin(String a, String b) {
        try {
            URL ua = new URL(a);
            URL ub = new URL(b);
            return ua.getHost().equalsIgnoreCase(ub.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从页面文本里找媒体地址。返回 {@code {url, 命中方式}}，找不到返回 null。
     *
     * <p>顺序就是可信度顺序：先苹果CMS 的 player_aaaa（结构化的），再 JSON 字段，
     * 最后才是裸地址（裸地址可能是广告位 / 预告片）。
     *
     * <p>预处理要先还原转义再匹配：页面里 {@code \/} 与 {@code \u002F} 很常见，
     * 不还原就会把 {@code https:\/\/x\/a.m3u8} 当成相对路径。
     */
    static String[] sniffMediaUrls(String html, String base) {
        if (html == null || html.isEmpty()) return null;
        String t = html.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/");
        // 1) 苹果CMS：player_aaaa={"url":"..."}
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("player_[a-z]+\\s*=\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+)\"",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(t);
        if (m.find()) {
            String raw = m.group(1);
            /*
             * MacCMS 的 encrypt:1 会把真实地址塞成 base64。
             * 这种值**不能当相对路径补全**（补完就成了 /aHR0c… 这种假地址），
             * 原样交给 resolveSniffedMedia 去解码。
             */
            if (decodeBase64Media(raw) != null) {
                return new String[]{raw, "player_config_b64"};
            }
            return new String[]{absolutize(raw, base), "player_config"};
        }
        // 2) JSON 字段：url/src/file 里带 .m3u8 / .mp4
        m = java.util.regex.Pattern
                .compile("\"(?:url|src|file|video|playurl)\"\\s*:\\s*\"([^\"]{4,600}?\\.(?:m3u8|mp4)[^\"]*)\"",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(t);
        if (m.find()) return new String[]{absolutize(m.group(1), base), "json_field"};
        // 3) 裸地址（用空格/引号/尖括号收边界，不用 \s，避免转义层数出错）
        m = java.util.regex.Pattern
                .compile("https?://[^\"'<> \\\\]{4,600}?\\.(?:m3u8|mp4)(?:\\?[^\"'<> \\\\]*)?",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(t);
        if (m.find()) return new String[]{m.group(), "bare_url"};
        return null;
    }

    /** 这个地址看起来就是媒体本身（而不是网页或一段 base64）。 */
    private static boolean looksLikeMediaUrl(String u) {
        if (u == null || u.isEmpty()) return false;
        return u.toLowerCase().matches("(?s).*\\.(m3u8|mp4|flv|mkv|m4s|ts|mov|webm)(\\?.*)?$");
    }

    /**
     * base64 形态的播放地址（MacCMS 的 {@code encrypt:1}）。
     *
     * <p>解出来必须还是媒体地址才算：正常 URL 偶尔也会"看起来像 base64"，
     * 少这一步会把真地址解成一串乱码。
     */
    private static String decodeBase64Media(String s) {
        if (s == null) return null;
        String t = s.trim().replace('-', '+').replace('_', '/').replaceAll("\\s", "");
        if (t.length() < 16) return null;
        try {
            String dec = new String(java.util.Base64.getDecoder().decode(t),
                    StandardCharsets.UTF_8).trim();
            if (dec.startsWith("http") && looksLikeMediaUrl(dec)) return dec;
        } catch (Throwable ignored) {
            /* 不是合法 base64：按普通地址处理 */
        }
        return null;
    }

    /**
     * 把一个嗅探结果解析成真正的媒体地址。
     *
     * <p>顺序：本来就是媒体 → base64 解一次 → 当播放页再嗅一层（限深）。
     * 返回 null 表示"这个页面确实没给媒体地址"——调用方要报解析失败，
     * 而不是把网页/base64 当播放地址交给播放器（那样报的是 manifest 解析失败，
     * 用户永远等不到换线）。
     */
    private static String resolveSniffedMedia(String url, String base, int depth) {
        if (url == null || url.isEmpty()) return null;
        if (looksLikeMediaUrl(url)) return url;
        String dec = decodeBase64Media(url);
        if (dec != null) return dec;
        if (depth <= 0) return null;
        if (url.equals(base)) return null;
        if (!core.UrlSafety.isSafeHttpUrl(url)) return null;
        String[] page = fetchText(url, base, 1);
        if (page == null) return null;
        String[] inner = sniffMediaUrls(page[0], page[1]);
        if (inner == null) return null;
        return resolveSniffedMedia(inner[0], page[1], depth - 1);
    }

    /**
     * 被 rules 排除后找下一个候选：把页面里**所有**媒体地址按同样三档顺序列出，
     * 跳过已经试过的那个，返回第一个没被 rules 排除的。
     *
     * <p>为什么需要它：一个播放页常有多个 .m3u8（预告片、广告、正片），
     * 三档正则只能给出第一个，被 rules 判为广告后如果直接放弃，
     * 就等于"明明有正片地址却报告解析失败"。
     */
    private static String[] sniffNextCandidate(String html, String base, String tried, String page) {
        if (html == null || html.isEmpty()) return null;
        String t = html.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/");
        // 收集全部候选（按可信度：player_config > json_field > bare_url）
        List<String[]> all = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("player_[a-z]+\\s*=\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+)\"",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
        while (m.find()) all.add(new String[]{absolutize(m.group(1), base), "player_config"});
        m = java.util.regex.Pattern
                .compile("\"(?:url|src|file|video|playurl)\"\\s*:\\s*\"([^\"]{4,600}?\\.(?:m3u8|mp4)[^\"]*)\"",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
        while (m.find()) all.add(new String[]{absolutize(m.group(1), base), "json_field"});
        m = java.util.regex.Pattern
                .compile("https?://[^\"'<> \\\\]{4,600}?\\.(?:m3u8|mp4)(?:\\?[^\"'<> \\\\]*)?",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
        while (m.find()) all.add(new String[]{m.group(), "bare_url"});

        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(tried);
        for (String[] c : all) {
            if (c[0] == null || !seen.add(c[0])) continue;
            if (Boolean.FALSE.equals(ruleVerdict(page, c[0]))) continue;
            return c;
        }
        return null;
    }

    private static String absolutize(String u, String base) {
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        try {
            return new URL(new URL(base), u).toString();
        } catch (Exception e) {
            return u;
        }
    }

    /**
     * 把解析器返回的 JSON 统一收敛成前端认的形状。
     *
     * <p>三种来源（type1 的 HTTP、type2/3 的 jar 类）字段命名一致，只有类型不同，
     * 所以这里不区分来源——字段映射写两遍迟早会漂。
     */
    private static JSONObject resultFromParser(JSONObject json, JSONObject configuredHeader, String from) throws Exception {
        JSONObject out = new JSONObject();
        out.put("from", from == null ? "" : from);
        String url = json.optString("url", "");
        if (url.isEmpty()) {
            JSONObject data = json.optJSONObject("data");
            if (data != null) url = data.optString("url", "");
        }
        if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            out.put("ok", false).put("msg", "解析接口未返回可用地址");
            return out;
        }
        JSONObject header = new JSONObject();
        java.util.Iterator<String> jk = json.keys();
        while (jk.hasNext()) {
            String key = jk.next();
            String lower = key.toLowerCase();
            if (lower.equals("ua")) header.put("User-Agent", json.optString(key, ""));
            else if (lower.equals("referer")) header.put("Referer", json.optString(key, ""));
            else if (lower.equals("cookie")) header.put("Cookie", json.optString(key, ""));
        }
        JSONObject inner = json.optJSONObject("header");
        if (inner != null) {
            java.util.Iterator<String> ik = inner.keys();
            while (ik.hasNext()) {
                String key = ik.next();
                header.put(key, inner.optString(key, ""));
            }
        }
        if (configuredHeader != null) {
            java.util.Iterator<String> ek = configuredHeader.keys();
            while (ek.hasNext()) {
                String key = ek.next();
                if (!header.has(key)) header.put(key, configuredHeader.optString(key, ""));
            }
        }
        out.put("ok", true).put("url", url);
        if (header.length() > 0) out.put("header", header.toString());
        // 解析器可能要求"再解析一次"（返回的还是网页地址），带上 jx 让前端决定
        String jx = json.optString("jx", "");
        if (!jx.isEmpty()) out.put("jx", jx);
        return out;
    }

    /**
     * type 2（jsonExt）：{@code url} 是 jar 里 parser 类的后缀，例如 {@code Basic}
     * 对应 {@code com.github.catvod.parser.JsonBasic}。
     *
     * <p>不能自己实现这套语义：parser 类是 jar 内的具体实现（Basic / Parallel /
     * Sequence），各自有不同的并发与降级策略，我们只负责按 CatVod 的入参形态调用。
     *
     * <p>入参 {@code jxs} 是「解析器名 → 解析器地址」，地址里要带上 {@code cat_ext}
     * （base64 后的 ext），parser 靠它才知道用哪个 header / flag。
     */
    private static JSONObject tryJsonExt(ParseEntry e, String webUrl, List<ParseEntry> all) {
        JSONObject out = new JSONObject();
        try {
            out.put("from", e.name);
            LinkedHashMap<String, String> jxs = new LinkedHashMap<>();
            for (ParseEntry x : all) {
                if (x.type == 1 && !x.name.isEmpty()) jxs.put(x.name, extUrl(x));
            }
            Class<?> cls = platform.Bridge.loadClass("com.github.catvod.parser.Json" + e.url);
            java.lang.reflect.Method m = cls.getMethod("parse", LinkedHashMap.class, String.class);
            JSONObject r = (JSONObject) m.invoke(null, jxs, webUrl);
            if (r == null) {
                out.put("ok", false).put("msg", "解析器 " + e.url + " 没有返回结果");
                return out;
            }
            return resultFromParser(r, e.header, e.name);
        } catch (ClassNotFoundException nf) {
            return parseFail(out, "jar 里没有 Json" + e.url + "（可用的：Basic/Parallel/Sequence）");
        } catch (Throwable t) {
            return parseFail(out, "解析器 " + e.url + " 执行失败：" + t.getClass().getSimpleName());
        }
    }

    /**
     * type 3（jsonExtMix）：url 指向 {@code Mix<后缀>}（jar 内有 Demo / Web）。
     *
     * <p>与 type 2 的差别是入参：每个解析器给三件套 {@code {type, ext, url}}，
     * 另外单独传站点名与 flag —— 同一条线路在不同站点下要选不同的解析器。
     */
    private static JSONObject tryJsonMix(ParseEntry e, String webUrl, String flag, List<ParseEntry> all) {
        JSONObject out = new JSONObject();
        try {
            out.put("from", e.name);
            LinkedHashMap<String, HashMap<String, String>> jxs = new LinkedHashMap<>();
            for (ParseEntry x : all) {
                if (!x.name.isEmpty()) jxs.put(x.name, mixMap(x));
            }
            Class<?> cls = platform.Bridge.loadClass("com.github.catvod.parser.Mix" + e.url);
            java.lang.reflect.Method m = cls.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
            JSONObject r = (JSONObject) m.invoke(null, jxs, e.name, flag == null ? "" : flag, webUrl);
            if (r == null) {
                out.put("ok", false).put("msg", "解析器 " + e.url + " 没有返回结果");
                return out;
            }
            return resultFromParser(r, e.header, e.name);
        } catch (ClassNotFoundException nf) {
            return parseFail(out, "jar 里没有 Mix" + e.url + "（可用的：Demo/Web）");
        } catch (Throwable t) {
            return parseFail(out, "解析器 " + e.url + " 执行失败：" + t.getClass().getSimpleName());
        }
    }

    /** catch 里不能再抛受检异常（android-json 的 put 是受检的），统一走这里。 */
    private static JSONObject parseFail(JSONObject out, String msg) {
        try {
            out.put("ok", false).put("msg", msg);
        } catch (Exception ignored) {
            /* 连错误信息都写不进去就只能返回原对象 */
        }
        return out;
    }

    /**
     * CatVod 的 {@code Parse.extUrl()}：ext 非空且 url 带查询串时，把 ext 以
     * {@code cat_ext=} 塞进查询串——parser 类只拿得到 URL，靠这个参数还原配置。
     */
    private static String extUrl(ParseEntry e) {
        int q = e.url.indexOf('?');
        if (e.extJson.isEmpty() || e.extJson.equals("{}") || q < 0) return e.url;
        return e.url.substring(0, q + 1) + "cat_ext=" + b64Url(e.extJson) + "&" + e.url.substring(q + 1);
    }

    /** CatVod 的 {@code Parse.mixMap()}：type 3 每个解析器交给 parser 的三件套。 */
    private static HashMap<String, String> mixMap(ParseEntry e) {
        HashMap<String, String> m = new HashMap<>();
        m.put("type", String.valueOf(e.type));
        m.put("ext", e.extJson.isEmpty() ? "{}" : e.extJson);
        m.put("url", e.url);
        return m;
    }

    /**
     * URL-safe base64（与 android {@code Base64.URL_SAFE} 同字母表、带 padding）。
     *
     * <p>刻意不按 android 的 76 列折行：折行后的 {@code \n} 会被 parser 当数据解，
     * 上游那个写法在 ext 超长时本身就是坏的；短的 ext（现实里全部）两者一致。
     */
    private static String b64Url(String s) {
        try {
            return java.util.Base64.getUrlEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- search all

    // ---------------------------------------------------------------- 异步搜索任务

    /**
     * 一次搜索任务的可读快照。
     *
     * <p><b>为什么要有这个东西</b>：原来 {@code POST /api/search} 在**请求线程**里
     * 跑完整个聚合（实测 2~8 秒，搜索不到的词会跑满 20 秒）。而请求线程池只有
     * 12 个（{@code POOL}），实测 12 个并发搜索就能把池占满 —— 此时
     * {@code /api/health} 的延迟从毫秒级涨到 **9.1 秒**，表现为"整个应用卡死"。
     *
     * <p>拆成"启动任务 + 轮询快照"之后：
     * <ul>
     *   <li>搜索在后台线程跑，占多久都不影响其他接口</li>
     *   <li>轮询是毫秒级短请求</li>
     *   <li>收尾可以放心放大（现在 15 秒），因为不再占请求线程</li>
     *   <li>用户看到前几条就能点进详情，后端继续搜 —— 详情走别的线程，互不影响</li>
     * </ul>
     */
    static final class SearchTask {
        final String id;
        final String wd;
        final long startedAt = System.currentTimeMillis();
        /**
         * 当前已完成聚合的结果（按完成顺序）——**只由聚合线程访问**。
         *
         * <p>轮询端点不读这个 map，读下面的 {@link #snapshot}。
         */
        final Map<String, JSONObject> merged = new LinkedHashMap<>();
        /**
         * 已序列化的结果快照（整段 JSON 数组文本），由聚合线程周期性整体替换。
         *
         * <p><b>为什么不用共享 map + 锁</b>：踩过（实测极米 Z6X Pro / Android 9 /
         * armeabi-v7a，累计 20+ 次 `data_app_native_crash`）——
         * <pre>
         *   signal 6 (SIGABRT), tid: HeapTaskDaemon
         *   Abort message: '/dev/ashmem/dalvik-main space (region space) (deleted)'
         *   art::gc::Verification::LogHeapCorruption
         *   art::gc::collector::ConcurrentCopying::MarkNonMoving
         * </pre>
         * 只要"聚合线程写、轮询线程读"共享同一个可变对象图，无论怎么加锁，
         * 总有窗口让 GC 在标记阶段看到被并发修改的对象。ART 的并发拷贝 GC
         * 直接把进程 abort（不是抛 OOM，捕不到也记不下）。
         *
         * <p>改成**只发布不可变的 String**：聚合线程算好整段 JSON 后一次性
         * volatile 赋值，轮询线程只读这个引用再拼进响应。String 不可变，
         * 两个线程之间没有任何共享可变状态，GC 也就没有机会看到不一致。
         */
        volatile String snapshot = "[]";
        /** 快照对应的条目数。 */
        volatile int snapshotCount;
        /** 上次发布快照的时间（只由聚合线程读写）。 */
        long lastPublishAt;
        volatile boolean done;
        volatile long finishedAt;
        volatile int returned;
        volatile int total;
        /**
         * 本轮因命中噪音词被剔除的条目数。
         *
         * <p>必须报给前端：不报的话用户看到结果变少会以为源站没有，
         * 而其实是自己配的词表滤掉了（设置页改完忘了自己开过滤很常见）。
         */
        volatile int noiseFiltered;
        /** rank 模式下被标记降级的条数（filter 模式恒为 0）。 */
        volatile int noiseDowngraded;
        volatile String error;

        SearchTask(String id, String wd) {
            this.id = id;
            this.wd = wd;
        }
    }

    private static final Map<String, SearchTask> SEARCH_TASKS = new ConcurrentHashMap<>();
    /**
     * 搜索编排线程池：只负责"启动 searchAll 并写回状态"，不干具体抓取。
     *
     * <p>与 {@code FANOUT} 分开是硬要求 —— searchAll 内部要用 FANOUT 提交全部源，
     * 如果编排本身也占 FANOUT，就成了"池中任务等同池任务"，见 startSearchTask 的注释。
     *
     * <p>cached 池：一次搜索占一个，结束后释放；并发搜索（用户快速改词）时短暂多几个。
     */
    private static final ExecutorService SEARCH_ORCH = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "meow-search-orch");
        t.setDaemon(true);
        return t;
    });
    /** 任务保留时长：够用户从详情页返回后还能看到结果，又不至于积压内存。 */
    /** 完成任务的最短保留时间（用户从详情页返回后还能看到结果）。 */
    private static final long SEARCH_TASK_MIN_TTL_MS = 60_000L;
    /** 最多保留几个任务 —— 低内存设备上这个比 TTL 更重要。 */
    private static final int SEARCH_TASK_MAX_KEPT = 3;

    /**
     * 清理过期/过多的搜索任务。
     *
     * <p><b>为什么要限个数而不只是 TTL</b>：实测极米 Z6X Pro（堆上限 192MB），
     * 连续 6 次搜索后进程被杀。每个完成任务都留着 `merged`（36 条结果对象 +
     * 每个 entry 的 sources 数组），5 分钟 TTL 下连续搜索会一直累积，
     * 顶到堆上限就 OOM，然后被极米的 `xgimi_reclaimservice` 干掉。
     *
     * <p>真机上用户也可能"搜一个词 → 换一个词 → 再换"，一分钟内好几次。
     * 所以：保留最近 3 个 + 最少 60 秒，超出的立刻丢。
     */
    private static void pruneSearchTasks() {
        long now = System.currentTimeMillis();
        java.util.List<SearchTask> done = new java.util.ArrayList<>();
        for (SearchTask t : SEARCH_TASKS.values()) if (t.done) done.add(t);
        // 先按时间清掉超过 60 秒的
        for (SearchTask t : done) {
            if (now - t.finishedAt > SEARCH_TASK_MIN_TTL_MS) SEARCH_TASKS.remove(t.id);
        }
        // 仍然超过上限 → 从最老的开始删（保留正在跑的不动）
        if (SEARCH_TASKS.size() > SEARCH_TASK_MAX_KEPT) {
            java.util.List<SearchTask> finished = new java.util.ArrayList<>();
            for (SearchTask t : SEARCH_TASKS.values()) if (t.done) finished.add(t);
            finished.sort((a, b) -> Long.compare(a.finishedAt, b.finishedAt));
            int excess = SEARCH_TASKS.size() - SEARCH_TASK_MAX_KEPT;
            for (int i = 0; i < excess && i < finished.size(); i++) {
                SEARCH_TASKS.remove(finished.get(i).id);
            }
        }
    }

    /** 把任务当前状态序列化成轮询响应。 */
    private static String taskJson(SearchTask t, boolean includeGroups) {
        /*
         * 普通字段用 JSONObject 拼（自带正确转义），
         * 只有 `groups` 是手工拼的 —— 它是聚合线程已序列化好的不可变文本，
         * 塞进 JSONObject 会被当普通字符串转义成 "groups":"[...]"。
         */
        String head;
        try {
            JSONObject o = new JSONObject();
            o.put("taskId", t.id);
            o.put("done", t.done);
            o.put("returned", t.returned);
            o.put("total", t.total);
            o.put("noiseFiltered", t.noiseFiltered);
            o.put("noiseDowngraded", t.noiseDowngraded);
            o.put("wd", t.wd);
            if (t.error != null) o.put("error", t.error);
            String h = o.toString();
            head = h.substring(0, h.length() - 1);   // 去掉结尾的 }
        } catch (Throwable e) {
            head = "{\"taskId\":\"\"";
        }
        StringBuilder sb = new StringBuilder(head);
        if (includeGroups) {
            sb.append(",\"groups\":").append(t.snapshot)
              .append(",\"count\":").append(t.snapshotCount);
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * 把当前聚合结果序列化成不可变快照发布出去。
     *
     * <p>由聚合线程调用（唯一写者），轮询线程只读 {@code task.snapshot}。
     * 这样两个线程之间没有任何共享可变对象 —— 这是 ART 并发拷贝 GC 报
     * "堆损坏" 的根因（见 SearchTask.snapshot 的注释）。
     */
    private static void publishSnapshot(SearchTask t, Map<String, JSONObject> merged) {
        publishSnapshot(t, merged, false);
    }

    /**
     * @param force true = 忽略节流，必定发布（收尾时用）
     */
    private static void publishSnapshot(SearchTask t, Map<String, JSONObject> merged, boolean force) {
        try {
            /*
             * ★ 节流：最多每 800ms 发布一次，且"条目数没变就不发"。
             *
             * 踩过（实测极米 Z6X Pro / Android 9 / armeabi-v7a，累计 22 次
             * `data_app_native_crash`，全部是 `HeapTaskDaemon` + region-space GC
             * 的 `LogHeapCorruption`）：每次发布都 `arr.toString()` 生成一份完整
             * JSON 文本。44 个源、每个源若干条结果，一轮搜索会生成几十份
             * 几 KB~几十 KB 的短命 String，加上 JSONObject 中转，
             * 正好踩中 Android 9 region-space 的并发拷贝 GC 的已知脆弱点 ——
             * 它在标记阶段看到跨 region 引用不一致就直接 abort 进程。
             *
             * 这里把发布频率压到"最多 1.25 次/秒"，且条目数不变就不重新序列化。
             * 用户侧感知不到差别（前端本来就是 500ms 轮询一次），
             * 但短命大对象数量降低一个量级。
             */
            long now = System.currentTimeMillis();
            synchronized (merged) {
                int size = merged.size();
                /*
                 * 32 位（小堆）用**纯时间节流**：搜索时每收一个源 size 都会变，
                 * 原来"size 变了就发"等于没节流 —— 44 个源 = 44 次全量序列化。
                 * 实测这台极米在聚合搜索时正是死在这类短命大对象上
                 * （HeapTaskDaemon + LogHeapCorruption）。前端本来就是 500ms
                 * 轮询，1.5 秒一份快照完全够用；收尾 force 一次保证完整。
                 */
                // is32BitAndroid() 而不是 is32Bit()：后者读 sun.arch.data.model，
                // ART 上取不到恒 false → 节流从未生效（踩过，串行那边同源 bug）
                boolean lowMem = dalvikHeapLimitMB() > 0 && is32BitAndroid();
                if (!force) {
                    if (lowMem) {
                        if (now - t.lastPublishAt < 1_500L) return;
                    } else if (size == t.snapshotCount && now - t.lastPublishAt < 1_500L) {
                        return;
                    }
                }
                t.snapshotCount = size;
                t.lastPublishAt = now;
                JSONArray arr = new JSONArray();
                for (JSONObject v : merged.values()) arr.put(v);
                t.snapshot = arr.toString();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 建一个后台搜索任务，立刻返回 taskId。
     *
     * <p>任务跑在 {@link FANOUT} 上而不是请求线程里 —— 这正是"不占请求池"的关键。
     */
    private static String startSearchTask(String body) {
        pruneSearchTasks();
        String id = Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString(java.util.concurrent.ThreadLocalRandom.current().nextInt(1 << 20), 36);
        String wd = "";
        try {
            wd = new JSONObject(body).optString("wd", "");
        } catch (Throwable ignored) {
        }
        final SearchTask task = new SearchTask(id, wd);
        SEARCH_TASKS.put(id, task);
        final String payload = body;
        /*
         * ★ 编排线程必须独立，不能跑在 FANOUT 上。
         *
         * 踩过（实测极米 Z6X Pro：连续两次全量搜索后网关彻底不响应）：
         * searchAll 内部会往 FANOUT 提交全部源的任务，而它自己又跑在 FANOUT 上 ——
         * 这是"池中任务等待同池任务"的线程饥饿。FANOUT 在 4 核设备上只有 8 个线程
         * （cores*2），一个搜索任务占 1 个去等另外 270 个任务，再叠上并发搜索，
         * 池很快被"等待者"填满，提交被 AbortPolicy 拒掉，而等待者永远等不到完成。
         * 表现：进程活着、端口连不上、/api/health 超时。
         *
         * 用独立的单线程调度器：它只负责"启动一次 searchAll 并写回状态"，
         * 真正的并发仍然由 FANOUT 承担，两者职责分开。
         */
        SEARCH_ORCH.execute(() -> {
            try {
                searchAll(payload, task);
            } catch (Throwable t) {
                task.error = String.valueOf(t);
            } finally {
                // 无论正常结束还是抛异常都要置 done，否则前端永远轮询下去
                task.done = true;
                task.finishedAt = System.currentTimeMillis();
            }
        });
        return taskJson(task, false);
    }

    private static String searchAll(String body) throws Exception {
        return searchAll(body, null);
    }

    /**
     * @param task 非 null 时把聚合进度实时写进它（轮询端点读这个）
     */
    private static String searchAll(String body, SearchTask task) throws Exception {
        JSONObject req = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        String wd = req.optString("wd", "");
        if (wd.isEmpty()) return "{\"groups\":[]}";
        int skippedDegraded = 0;
        /*
         * 噪音词表：用户自行维护（设置 → 搜索与源），命中即从聚合结果里剔除。
         *
         * <p>为什么每轮只读一次：policy.json 是本地文件，但每条结果都读盘会
         * 把这个方法变成 I/O 密集 —— 270 个源、上千条结果时差异明显。搜索是
         * 秒级操作，改完词表下一轮生效完全够用。
         *
         * <p>为什么不删掉前端 searchRank.ts 的 UGC_RE 而只留这个：那个在
         * **前端只做降权**（"解说"排后面但仍然可见），因为有人就是找解说合集；
         * 这里是**用户主动开启**的硬过滤。两者目的不同，不能互相替代。
         */
        java.util.List<String> noiseWords = noiseWords();
        /*
         * 词表的两种作用方式（设置页可选）：
         *   filter —— 命中即从聚合结果里剔除（默认，用户提需求时的原意）
         *   rank   —— 保留但排到最后，用户偶尔想看解说时不必反复改词表
         * 为什么要给这个开关：有人把"解说"当噪音、有人偶尔就想看解说合集。
         * 强制二选一都不对，而"改词表开关"太粗暴（词表内容会丢）。
         */
        boolean noiseFilterMode = !"rank".equals(noiseMode());
        java.util.concurrent.atomic.AtomicInteger noiseHit =
                new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger noiseMarked =
                new java.util.concurrent.atomic.AtomicInteger(0);

        List<String> targets = new ArrayList<>();
        JSONArray reqSites = req.optJSONArray("sites");
        if (reqSites != null) {
            for (int i = 0; i < reqSites.length(); i++) targets.add(reqSites.optString(i));
        } else {
            // 在 SITES 上同步地做一次快照，再遍历快照。
            // 导入配置会在另一线程 clear+put 整张表，直接遍历 keySet 会撞
            // ConcurrentModificationException，表现为"搜索偶发 500"。
            synchronized (SITES) {
                targets.addAll(SITES.keySet());
            }
            /*
             * 只跳过两种：手动停用、环境不支持（配置缺失 / 运行时没接）。
             * 见 HealthRoutes.isSkipped 的说明 —— "自动降级 30 分钟"已删除。
             */
            List<String> alive = new ArrayList<>();
            int skipped = 0;
            /*
             * ★ 不按"源的类型"跳过任何站点。
             *
             * 早先这里有一段"脚本运行时不可用 → 整类跳过 227 个脚本源"的逻辑，
             * 已删除。理由（2026-10-30 复核）：
             *   - 跳过是对用户可见的功能损失，容器恢复后也发现不了
             *   - 上游影视仓的做法是**只排序、永不跳过**（SiteHealthStore
             *     的 skip() 只排除空 key 与推送站）
             *   - 真正要治的是"慢源占满并发槽"，那由 callScriptRuntime 的
             *     熔断机制解决：容器挂了就把超时压到 1 秒快速失败，
             *     源仍参与每一轮，恢复后第一次调用即可自证可用
             */
            /*
             * 硬阈值：连续 2 次超时的源本轮不提交。
             *
             * 与 isSiteSkipped 的区别：
             *   - isSiteSkipped = "永远不该搜"（用户停用 / 环境不支持）
             *   - isTimeoutSkipped = "现在搜它只是白占 8 秒并发槽"
             *
             * 实测背景（OECT）：TingBook 卡 76 秒、Tvyb 卡 31 秒，
             * 健康分排序只能让它们排到末尾，但 270 个源 ÷ 12 并发意味着
             * 排最后也照样会被提交 —— 整轮被拖到 20 秒。
             *
             * 不设时间窗：任意一次非超时结果立刻清零（见 recordTimeout），
             * 所以不存在"被跳过很久"的问题。
             */
            int timeoutSkipped = 0;
            /*
             * ★ 运行时挂了 → 整类刨除脚本源。
             *
             * 所有 .js 源共用同一个 drpy 容器，属同一故障域：容器不可用时
             * 226 个源一个都不可能成功，继续提交只是把并发槽一个个喂给
             * 1~8 秒超时。实测（2026-10-30 OECT）：不刨除时整轮 23~30 秒
             * 且撞满总预算，jar 源的 36 条结果被 226 个必败源拖住。
             *
             * 只影响**本轮**：探测结果缓存 15 秒，容器恢复后下一次
             * 探测通过就立刻恢复参与，不需要重启。
             */
            final boolean rtUsable = scriptRuntimeUsable();
            int rtSkipped = 0;
            /*
             * 已确认失效的源（累计失败多、近期没成功过）。
             *
             * 见 HealthRoutes.isFailedSkipped 的说明：OECT 上近百个 drpy 源
             * 指向已关停的站点，drpy 每次 `fetch failed`。它们既不超时
             * （timeoutStreak 不涨）、也不是 unsupported（配置合法），
             * 旧逻辑一个都拦不住，每轮各烧 3~6 秒。
             */
            int failedSkipped = 0;
            for (String s : targets) {
                if (isSiteSkipped(s)) {
                    skipped++;
                    continue;
                }
                if (!rtUsable && isScriptApi(API.get(s))) {
                    rtSkipped++;
                    continue;
                }
                if (routes.HealthRoutes.isFailedSkipped(s)) {
                    failedSkipped++;
                    continue;
                }
                if (routes.HealthRoutes.isTimeoutSkipped(s)) {
                    timeoutSkipped++;
                    continue;
                }
                alive.add(s);
            }
            if (rtSkipped > 0) {
                core.LogBuffer.warn("search",
                        "jsRuntime 不可用，本轮跳过 " + rtSkipped + " 个脚本源（jar 源照常搜）");
                System.err.println("[search] jsRuntime DOWN, skip " + rtSkipped + " script sites");
            }
            if (failedSkipped > 0) {
                core.LogBuffer.info("search", "跳过 " + failedSkipped
                        + " 个已失效源（累计失败多且 30 分钟内无成功，60 秒后自动重试）");
                System.err.println("[search] skip " + failedSkipped + " failed sites");
            }
            if (timeoutSkipped > 0) {
                core.LogBuffer.info("search",
                        "跳过 " + timeoutSkipped + " 个连续超时源（下轮有成功即恢复）");
                System.err.println("[search] skip " + timeoutSkipped + " timeout sites");
            }
            if (!alive.isEmpty()) {
                /*
                 * ★ 保底：跳过之后至少留 MIN_ALIVE_SITES 个源。
                 *
                 * 失效判据是"累计失败"，配置刚导入、源站集体抽风、
                 * 或上次跑在错误网络下时，可能一次把所有源都判死 ——
                 * 那样搜索会稳定返回 0 条，看起来像"功能坏了"。
                 * 保留一批可搜的源，宁可慢一点也不能没结果。
                 *
                 * 保留策略：按健康分取最高的那批（sorted 里排名靠前的），
                 * 而不是随机 —— 它们的成功率历史上更高。
                 */
                if (alive.size() < MIN_ALIVE_SITES) {
                    int need = MIN_ALIVE_SITES - alive.size();
                    java.util.List<String> sortedAll = routes.HealthRoutes.sortByScore(targets);
                    for (String s : sortedAll) {
                        if (need <= 0) break;
                        if (alive.contains(s)) continue;
                        alive.add(s);
                        need--;
                    }
                    core.LogBuffer.info("search",
                            "跳过过多源，回补 " + (MIN_ALIVE_SITES - need) + " 个参与保证有结果");
                }
                targets = alive;
                skippedDegraded = skipped;
            }
            /*
             * ★ 排序有两条优先级，健康分只是其中一条。
             *
             * 第一条（硬优先）：**jar 源一律排在脚本源前面**。
             *
             * 为什么这是硬优先而不是交给健康分：jar 源是本地代码，延迟
             * 由目标站决定（实测 0.2~3s）；脚本源要多跳一次运行时容器
             * （我们→容器→目标站），容器一忙就整段卡住。两者**下限不同**，
             * 混在一起排会让容器故障时快源也被压在后面。
             *
             * 实测背景（OECT）：270 站里 226 个是脚本源，容器卡死时
             * 12 个并发槽全被它们占满，44 个 jar 源一个都轮不上 →
             * 第一轮 returned=45、第二轮起 0。把 jar 提到前面后，
             * 首屏必先是 jar 源的结果。
             *
             * 第二条：同类型内部按健康分排（历史上更可靠、更快的在前）。
             * 这与影视仓 SiteHealthStore.sortSites 同语义。
             *
             * 注意：排序只影响提交顺序，CompletionService 仍按完成顺序聚合，
             * 所以它真正的作用是"让好源更快进入并发窗口"——不改变结果集。
             */
            targets = sortJarFirstThenScore(targets);
        }

        // 并发上限由前端「搜索线程」设置控制，避免同时打太多源被限流
        int threads = req.optInt("threads", 12);
        threads = Math.max(1, Math.min(32, threads));
        long searchStart = System.currentTimeMillis();

        Map<String, Future<String>> futures = new LinkedHashMap<>();
        // 完成顺序队列：谁先回来先聚合谁，避免按提交顺序串行等待，
        // 让快站的结果不必等前面慢站超时才能进结果。
        // 用 FANOUT 而不是 POOL：否则 270 个任务排在 12 个线程后面，
        // 慢源占满线程就等于整次搜索白跑（见 FANOUT 的注释）。
        /*
         * ★ 用"按堆大小定容的固定池"而不是 cached 池 + 信号量。
         *
         * 踩过（实测极米 Z6X Pro / Android 9 / armeabi-v7a / 堆上限 192MB）：
         * 原来 cached 池会把 44 个任务各建一个线程，全部阻塞在 gate.acquire()
         * 上 —— 44 个线程 × 每次搜索，连续搜几次就顶到进程内存上限，
         * 然后被极米的 xgimi_reclaimservice 杀掉（进程消失、端口关闭、无堆栈）。
         *
         * 固定池的语义正好是我们要的：**线程数封顶、其余任务在队列里等**，
         * 不会为了"排队"额外创建线程。慢源占着线程时，快源排在后面照样能跑
         * （队列是 FIFO，而源已按健康分排过序，好源在前）。
         */
        /*
         * ★ 并发上限必须按 **Dalvik 真实增长上限** 算，不能按 maxMemory()。
         *
         * 踩过（2026-10-29 实测极米 Z6X Pro / Android 9 / armeabi-v7a / zygote32）：
         * 两台设备装同一个 APK、导入逐字节相同的 270 站配置 —— MuMu（x86_64）
         * 6.3s 出 10 个源，投影仪却在首次搜索结果刚回来的瞬间被 SIGABRT 杀掉。
         * tombstone 固定是：
         *   `HeapTaskDaemon` → `ConcurrentCopying::MarkNonMoving`
         *   → `Verification::LogHeapCorruption` → `abort`
         * 即 32 位 ART 的并发拷贝 GC 在标记阶段发现对象图不一致。
         *
         * 触发条件是"堆压迫下的 GC"，而压迫来源正是这里的并发数：本类
         * `android:largeHeap="true"`，`Runtime.maxMemory()` 因此返回 **512MB**，
         * 按"每源 4MB、用 1/3"算出 memBound=42，前端传的 12 就原样通过。
         * 但 ART 实际按 `dalvik.vm.heapgrowthlimit=192MB` 起步增长，
         * 12 路并发（每路 OkHttp 连接 + JSON 解析 + spider 对象）很快把
         * region space 顶到 growth limit，ART 于是开始
         * `VMRuntime.clampGrowthLimit` 式收缩 → 连续 GC → 命中该 bug。
         *
         * MuMu 是 64 位、region space 4GB 起，同样的并发远够不到压力线，
         * 所以从来没暴露 —— 这就是"同配置同版本，只有投影仪崩"的全部原因。
         *
         * 因此取两者**较小值**做预算：有 growthlimit 时按它算，取不到
         * （JVM/PC 插件）才退回 maxMemory。
         */
        long heapLimitMB = dalvikHeapLimitMB();
        long budgetBytes = heapLimitMB > 0
                ? heapLimitMB * 1024L * 1024L
                : Runtime.getRuntime().maxMemory();
        // 每个源按 4MB 估，只用预算的 1/3 给"同时跑"，其余留给 UI/缓存/其他请求
        int memBound = (int) Math.max(2, (budgetBytes / 3) / (4L * 1024 * 1024));
        /*
         * ★ 并发上限只在「32 位 Android」上生效，别让正常设备也被压。
         *
         * 这是一条设备能力边界，不是性能策略：
         *   - JVM（docker / PC 插件）：取不到 dalvik 属性，完全不进来
         *   - 64 位 Android（MuMu / 现代盒子）：region space 从 4GB 起，
         *     实测 12 并发 5/5 稳定，没有任何理由压它的并发
         *   - 32 位 Android：实测 6 源稳定 / 10 源第 2 轮必崩，
         *     压到 6 才有可用性（否则整个进程被打死，一个源都拿不到）
         */
        // is32BitAndroid()：is32Bit() 在 ART 上恒 false，判据从未生效（与串行/节流同源 bug）
        final boolean lowMemDevice = dalvikHeapLimitMB() > 0 && is32BitAndroid();
        if (lowMemDevice) {
            memBound = Math.min(memBound, 6);
        }
        /*
         * ★ 用户显式设置优先，memBound 只作兜底。
         *
         * 设置页「搜索线程」有 4/8/12/16/24 五档，默认 12（prefs.searchThreads），
         * 每次搜索都会带上来。早先这里无条件 `min(threads, memBound)`，
         * 等于把用户的选择悄悄改掉 —— 实测 MuMu 上选 24 实际只跑 16
         * （192MB/3/4MB=16），界面上看不出差别，用户只会觉得"选了没用"。
         *
         * 规则改成：
         *   1. 请求里带了 threads（>0）→ **完全按用户的**
         *   2. 没带 → 用 memBound 兜底（防止老客户端不带参数时按默认 12 冲大设备）
         *   3. 32 位 Android 例外：设备能力上限 6 优先于用户设置，
         *      但通过 searchConcurrencyCapped 暴露"被压低了"，让设置页能提示
         */
        final boolean userSet = req.has("threads") && req.optInt("threads", 0) > 0;
        int effThreads;
        if (userSet && !lowMemDevice) {
            effThreads = Math.max(1, Math.min(64, threads));
        } else {
            effThreads = Math.max(2, Math.min(threads, memBound));
        }
        /*
         * ★ 32 位 Android 聚合搜索必须串行（2026-11-05 真机定论）。
         *
         * 踩过：之前把串行挂在 lowMemDevice 下，而它依赖 is32Bit() 的
         * `sun.arch.data.model` —— ART 上取不到 → 开关从未生效，于是
         * "串行也崩"的结论是错的。
         *
         * 真串行（effThreads=1）实测：8 站聚合 **返回真实结果、网关不崩**；
         * 而 6 并发 1~2 秒必 SIGABRT。这和历史数据吻合（6 源稳、10 源崩）。
         * 64 位/PC 完全不受影响；搜索预算会让串行在 8~10 秒内返回部分结果。
         */
        /*
         * `debug.meow.noserial=1` 可临时取消 32 位强制串行（真机 A/B 用）。
         * 背景：Fongmi 在同一台机上并发搜多站、几秒出结果且不崩；我们是串行、
         * 10~18 秒。要判断"串行是不是必需的"，只能同一台机现场对比。
         */
        if ((is32BitAndroid() && !boolProp("debug.meow.noserial"))
                || boolProp("debug.meow.serial")) {
            effThreads = 1;
            core.LogBuffer.info("search", "32 位串行模式：本轮并发=1");
        }
        searchConcurrencyHook = effThreads;
        searchConcurrencyCapped = lowMemDevice && userSet && threads > effThreads;
        // 超时池上限跟随本轮并发（effThreads + 8），避免慢源无限占线程
        alignSiteTimeoutPool(effThreads);
        /*
         * ★ 进程级共享池：线程永久复用（与上游 Fongmi 的 Task.largeExecutor 同形态）。
         *
         * 早先"每轮新建池"是为了躲开跨轮残留，但那牺牲了线程复用，
         * 而且治的是症状 —— 真正的病根是 227 个不可用的脚本源各占 5~30 秒
         * （见 targets 过滤处的 jsRuntime 预检）。预检把这些源在本轮直接排除后，
         * 残留量降到"最多几个真的慢源"，共享池完全够用。
         *
         * ★ core 必须等于 max。ThreadPoolExecutor 的语义是：线程数到 core 后
         * 任务先入无界队列、队列不满就不扩到 max —— 所以 core 写小数会让
         * 池永远只有那么多线程（踩过：core=2 把 docker 的 12 并发压成 2）。
         *
         * 超出 effThreads 的并发由下面的信号量 gate 控制：任务照常提交、
         * 但真正同时跑的数量被压到 effThreads，其余在 gate 上排队（不占连接）。
         */
        java.util.concurrent.ExecutorService searchPool = sharedSearchPool(effThreads);
        /*
         * ★ 新一轮开始：先把旧轮的残留任务从队列里扔掉。
         *
         * 为什么必须在这里做，而不是只在旧轮收尾时做：
         * 旧轮的收尾可能**还没执行到**（它可能正卡在 poll 等某个慢源），
         * 此时队列里压着它提交的几十上百个任务。新轮若直接 submit，
         * 新任务只能排在它们后面 —— 12 个线程先跑完旧的慢源，
         * 新一轮在自己的预算内一个都轮不到（这就是 OECT"第 2 次搜索 0 条"）。
         *
         * 这里 drain 的是**旧轮遗留**（新轮自己的任务还没提交），
         * 所以清空队列是安全的：那些任务属于已被 SEARCH_EPOCH 作废的轮次。
         */
        try {
            if (searchPool instanceof java.util.concurrent.ThreadPoolExecutor) {
                java.util.List<Runnable> stale = new ArrayList<>();
                ((java.util.concurrent.ThreadPoolExecutor) searchPool).getQueue().drainTo(stale);
                if (!stale.isEmpty()) {
                    core.LogBuffer.info("search", "新一轮清掉旧轮遗留 " + stale.size() + " 个排队任务");
                }
            }
        } catch (Throwable ignored) {
        }
        java.util.concurrent.CompletionService<String> done =
                new java.util.concurrent.ExecutorCompletionService<>(searchPool);
        Map<Future<String>, String> futureToSite = new HashMap<>();
        /*
         * 强制刷新：跳过结果缓存（仍会写回）。
         * 前端「重新搜索」按钮用它；默认走缓存。
         */
        final boolean forceRefresh = req.optBoolean("refresh", false);
        /** 本轮命中缓存的源数量（诊断用）。 */
        int cacheHits = 0;
        /** 命中缓存的源及其原始 JSON，等 merged 建好后统一并入。 */
        final Map<String, String> cachePending = new LinkedHashMap<>();
        /*
         * ★ 并发闸门：真正限制"同时跑几个源"的是它，不是池大小。
         *
         * 共享池的 max 是 32（进程级），而本轮只想跑 effThreads 个。
         * 270 个源一次 submit 的话，池会一路扩到 32 —— 32 位设备上就是
         * 32 份 OkHttp 连接 + JSON 解析同时在堆里活着，正是 GC 压迫的来源。
         * 用信号量把"真正在跑"的数量压到 effThreads，其余在线程池队列里等，
         * 不占线程、不占连接。
         *
         * 与 Fongmi 的对应关系：它的 largeExecutor 固定 20 线程，
         * 靠"线程数=并发数"天然限流；我们共享池要能服务不同设备，
         * 所以用信号量把并发独立出来控制。
         */
        final java.util.concurrent.Semaphore gate =
                new java.util.concurrent.Semaphore(effThreads, true);
        /*
         * ★ 本轮代号。聚合循环每轮都检查它，被新一轮取代就立刻退出。
         *
         * 对齐上游：旧轮结果一律作废，避免"连点两次搜索，两个轮次的结果
         * 交错写进同一个 merged 表"。
         */
        final int myEpoch = SEARCH_EPOCH.incrementAndGet();
        /*
         * ★ 并发上限要按"设备堆大小"再压一层，不能只信前端传的 threads。
         *
         * 踩过（实测极米 Z6X Pro / Android 9 / armeabi-v7a）：
         * `dalvik.vm.heapgrowthlimit` 只有 **192MB**，而 44 个源同时跑时
         * 每个源要 2~5MB（OkHttp 连接 + JSON 解析 + spider 对象），
         * 合计 88~220MB 直接顶到堆上限 → OOM → 极米的 `xgimi_reclaimservice`
         * 把进程干掉。表现是"进程直接消失、端口关闭、拿不到任何堆栈"
         * （docs/28 §10.1 记了两轮的那个现象）。
         *
         * MuMu 上从没暴露是因为它内存 6GB、堆上限更高，同样的并发吃得下。
         *
         * 用 `Runtime.maxMemory()` 反推安全的并发数：每个源按 4MB 估，
         * 只用堆的 1/4 留给"同时跑"的部分（其余留给 UI、缓存、其他请求）。
         */
        /*
         * ★ 冷源（未命中缓存）必须排在最前面提交。
         *
         * 踩过（2026-10-30 用户报的场景）：搜「我不是大师」中途改搜「庆余年」，
         * 回头再搜「我不是大师」时 80% 命中缓存 → mostlyCached 立刻成立 →
         * 聚合循环几乎马上 break → **剩下 20% 的冷源任务还在池里排队就被
         * drainTo 扔掉了**。结果每次都是同样的 80% 命中、同样的 20% 缺失，
         * 那 20% 永远补不上，用户只能点强刷。
         *
         * 把冷源提到最前面，它们就能在 break 之前跑完并写进缓存，
         * 下一轮命中率自然往上爬（80% → 95% → 全中）。
         *
         * 为什么不"延后 break"：那会让每次搜索都为 2 个 30 秒的慢源等满
         * 预算，把 263 个已到手的结果一起拖住 —— 正是要避免的。
         * 让冷源先跑，是"既快又能自愈"的唯一组合。
         */
        {
            /*
             * ★ 分桶顺序必须是"先 jar，后冷热"，不能反过来。
             *
             * 踩过（2026-10-30 OECT 实测）：这层重排是在
             * sortJarFirstThenScore **之后**做的二次分桶，于是它把
             * "jar 优先"整个覆盖掉了 —— 实测 cold=254 warm=11，
             * 而 jar 源只有 44 个，意味着 254 个冷源里绝大部分是 drpy，
             * 重排后它们全部排到 jar 前面，12 个并发槽被 227 个
             * fetch failed 的 drpy 源占满，jar 源排队等 8 秒超时。
             * 表现就是"明明有 jar 优先，却还是被 drpy 占满"。
             *
             * 现在按 (isScript, 是否命中缓存) 两级排序：
             *   第一级 jar 源整体在前，第二级才在各自内部把冷源提前。
             * 这样既能保证 jar 先跑，又保留"冷源优先预热缓存"的自愈。
             */
            java.util.List<String> coldJar = new ArrayList<>();
            java.util.List<String> warmJar = new ArrayList<>();
            java.util.List<String> coldScript = new ArrayList<>();
            java.util.List<String> warmScript = new ArrayList<>();
            for (String s : targets) {
                boolean hit = !forceRefresh && SEARCH_CACHE.get(s, wd, "1") != null;
                boolean script = isScriptApi(API.get(s));
                if (script) {
                    if (hit) warmScript.add(s); else coldScript.add(s);
                } else {
                    if (hit) warmJar.add(s); else coldJar.add(s);
                }
            }
            if (!warmJar.isEmpty() || !warmScript.isEmpty()) {
                int coldN = coldJar.size() + coldScript.size();
                java.util.List<String> ordered = new ArrayList<>(targets.size());
                ordered.addAll(coldJar);
                ordered.addAll(warmJar);
                ordered.addAll(coldScript);
                ordered.addAll(warmScript);
                targets = ordered;
                core.LogBuffer.info("search", "冷热重排：冷源 " + coldN
                        + " 个优先（jar 仍整体在前），命中缓存 "
                        + (warmJar.size() + warmScript.size()) + " 个排后");
                System.err.println("[search] 冷热重排 cold=" + coldN
                        + " warm=" + (warmJar.size() + warmScript.size())
                        + " jar=" + (coldJar.size() + warmJar.size())
                        + " script=" + (coldScript.size() + warmScript.size()));
            }
        }
        for (String s : targets) {
            /*
             * ★ 结果缓存命中 → 直接并入结果，不提交任务。
             *
             * 为什么不能在 submit 之后靠 poll 收：聚合循环是"一次 poll 一个
             * 已完成任务"的模型，而提前收尾（break）会跳过尚未 poll 的那些。
             * 实测踩过：263 个命中却只聚合出 17 条，就是因为 break 时还有
             * 200 多个缓存结果排在 CompletionService 队列里没被取出。
             *
             * 直接写 merged 没有这个问题：结果当场就位，remaining/futures
             * 都不包含它，收尾逻辑自然不受影响。
             */
            if (!forceRefresh) {
                String cached = SEARCH_CACHE.get(s, wd, "1");
                if (cached != null) {
                    /*
                     * 先攒着，等 merged 建好后再统一并入（见下面的 cachePending）。
                     * 这里不能直接 merge：merged 要到提交循环之后才声明，
                     * 而缓存命中发生在提交循环内部。
                     */
                    cachePending.put(s, cached);
                    cacheHits++;
                    continue;
                }
            }
            Future<String> f;
            try {
                f = done.submit(() -> {
                    boolean acquired = false;
                    try {
                        try {
                            gate.acquire();
                            acquired = true;
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return "";
                        }
                        long t0 = System.currentTimeMillis();
                        try {
                            /*
                             * ★ jar 源必须有单源硬超时。
                             *
                             * 踩过（2026-10-30 OECT 实测）：删掉两段式执行之后，
                             * `SEARCH_SITE_TIMEOUT_MS` 变成了"只有定义没有使用"，
                             * 于是 jar 源完全没有超时保护 —— 实测 Tvyb / TingBook
                             * 各卡 **96 秒**（它们的 HTTP 超时在 jar 内部，
                             * 我们改不了）。12 个并发槽被这类源各占 96 秒，
                             * 整轮预算 30 秒内一个都出不来 → 返回 0 条。
                             *
                             * 恢复"池线程 + 超时池掐表"的两段式：
                             *   searchPool 线程 ──submit──> SITE_TIMEOUT_POOL 线程（跑 spider）
                             *        └── inner.get(8s) 掐表
                             *
                             * 已知限制（-javaagent 实测确认，2026-10-30）：
                             * `cancel(true)` 对卡在 socket.read() 的 OkHttp **无效**。
                             * 在 `okhttp3.RealCall.execute()` 上插桩观测到：
                             * 外层 8 秒超时之后仍有 11~20 个 Call 活着，
                             * 最长到 **36 秒**才结束，且 `canceled=false`。
                             * 根因是 `java.net.Socket` 的阻塞读不检查中断标志。
                             *
                             * 所以真正兜住并发的是超时池的**冗余容量**，不是 cancel：
                             * 池大小 = effThreads + 8（见 alignSiteTimeoutPool），
                             * 那 8 个位置专门容留"已放弃但还没退出的残骸"。
                             * 同一批插桩还证明 `RealCall.execute` 覆盖了所有
                             * jar 源的网络调用（44 个源 → 25 个类 → 96 次调用），
                             * 不存在绕过 OkHttp 的路径，所以没有别的可取消点。
                             */
                            java.util.concurrent.Future<String> inner;
                            try {
                                inner = SITE_TIMEOUT_POOL.submit(() -> call(s, "search",
                                        new JSONObject().put("wd", wd).put("pg", "1").toString()));
                            } catch (java.util.concurrent.RejectedExecutionException rex) {
                                return "";
                            }
                            try {
                                String got = inner.get(SEARCH_SITE_TIMEOUT_MS,
                                        java.util.concurrent.TimeUnit.MILLISECONDS);
                                // 正常返回（无论有没有结果）：清掉超时计数，
                                // 源站恢复后能立刻回到正常轮次
                                routes.HealthRoutes.recordTimeout(s, false);
                                /*
                                 * ★ 缓存写在**worker 里**，不写在聚合循环里。
                                 *
                                 * 踩过（2026-10-30 用户提出的场景）：搜「我不是大师」
                                 * 跑到 4~5 秒时用户改搜「庆余年」，第一轮被 epoch 作废、
                                 * 聚合循环 break；等用户再搜回「我不是大师」，只有
                                 * **已经进过聚合循环**的那些源有缓存，晚到的都没缓存。
                                 *
                                 * 更糟的是 mostlyCached 快速收尾：命中 ≥80% 就直接
                                 * break，剩下那些源的任务在池里跑完却没人 poll →
                                 * 永远不写缓存。于是每次都是同样的 80% 命中 + 20%
                                 * 真跑，**那 20% 永远补不上**，只能靠强刷。
                                 *
                                 * 写在这里就与"是否被 poll"解耦：任务一旦真的拿到
                                 * spider 的返回就落缓存，无论聚合循环收得早还是晚。
                                 * 空串不缓存（那是超时/异常的返回值，不是源站的真实答复，
                                 * 缓存了会在 3 分钟内让该源看起来一直"没结果"）。
                                 */
                                if (!got.isEmpty()) SEARCH_CACHE.put(s, wd, "1", got);
                                return got;
                            } catch (java.util.concurrent.TimeoutException te) {
                                // 放弃等待并尽量取消；取消不掉由 effThreads+8 的缓冲位兜住。
                                // 记一次超时：连续 2 次下一轮就不再提交这个源。
                                inner.cancel(true);
                                routes.HealthRoutes.recordTimeout(s, true);
                                return "";
                            } catch (Throwable t) {
                                inner.cancel(true);
                                // 非超时的异常不算"慢源"：清零计时器，让源站恢复时能回来
                                routes.HealthRoutes.recordTimeout(s, false);
                                return "";
                            }
                        } catch (Throwable t) {
                            return "";
                        } finally {
                            long cost = System.currentTimeMillis() - t0;
                            // 只记慢站，正常站在 1s 内，全记会淹没日志
                            if (cost > 3_000L) System.err.println("[search] slow " + s + " " + cost + "ms");
                        }
                    } finally {
                        // 任何 return 都要归还许可，否则闸门漏一次，
                        // 后续搜索的并发就永久少一格。
                        if (acquired) gate.release();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException rex) {
                // 队列满了：这个源本轮不参与，不影响其他源
                System.err.println("[search] rejected " + s);
                continue;
            }
            futures.put(s, f);
            futureToSite.put(f, s);
        }

        // 按片名合并
        //
        // 【为什么必须有总超时】各 spider 内部没有统一的超时约束，实测有个别站会
        // 卡在 socket 读上几十秒不返回。原先用无参 get() 等，一个站卡住整个搜索就
        // 一直挂着（实测 90s 仍无响应）。这里改为共享一个总预算：到点就放弃还没
        // 回来的站，用已拿到的结果返回。放弃的只是慢站，不影响快站的聚合。
        long searchDeadline = System.currentTimeMillis() + SEARCH_TOTAL_TIMEOUT_MS;
        /*
         * 有 task 时直接往它的 map 里写：轮询端点读的就是这一份，
         * 于是"搜到一个源用户就能看到一个"。
         * 加锁粒度是**每次写一个 entry**，轮询那边同步读取，不会读到半个对象。
         */
        Map<String, JSONObject> merged = task == null ? new LinkedHashMap<>() : task.merged;
        /*
         * 把命中缓存的结果先并进来。
         *
         * 它们不进 futures/remaining，所以：
         *   - 不占线程池、不抢信号量
         *   - 提前收尾时不会丢（聚合循环只 poll futures 里的任务）
         * 实测踩过：改成走 done.submit 时，263 个命中只因提前 break
         * 就只聚合出 17 条。
         */
        for (Map.Entry<String, String> e : cachePending.entrySet()) {
            mergeOne(merged, e.getKey(), e.getValue(), wd, noiseWords,
                    noiseFilterMode, noiseHit, noiseMarked);
        }
        cachePending.clear();
        int remaining = futures.size();
        final int totalTargets = futures.size();
        int returned = 0;
        // 本轮脚本源的真实成败（用于运行时自适应降级，见下面的赋值处）
        int scriptTried = 0, scriptEmpty = 0;
        /*
         * 提交给池的脚本源总数 —— 已不再用于"跳过"，只作诊断输出。
         *
         * 排查"为什么这轮只跑了几十个源"时，这行能区分两种情况：
         * 配置里本来就只有这些源，还是脚本源被熔断压制了。
         */
        int scriptSubmitted = 0;
        for (String s : targets) {
            if (isScriptApi(API.get(s))) scriptSubmitted++;
        }
        core.LogBuffer.info("search", "targets=" + targets.size() + " scriptSubmitted="
                + scriptSubmitted + " apiTable=" + API.size()
                + " breaker=" + (scriptRuntimeBreakerOpen() ? "OPEN" : "closed")
                + " fails=" + scriptRuntimeFails.get());
        if (task != null) task.total = totalTargets;
        /*
         * 收尾等待预算：取"本站表里已知最慢源的历史耗时"，夹在 [MIN, 12s] 之间。
         *
         * ★ 为什么不能拍一个固定值：站表规模与源站快慢在不同形态下差一个量级
         *   （PC 插件 44 站、TV 270 站；Gz360 首轮 17.9s、次轮 0.8s）。
         *   固定值要么让所有搜索都慢，要么继续截掉慢源。
         *
         * ★ 为什么用"最慢"而不是平均：抖动的定义就是"偶尔某个源没进来"，
         *   平均值会被大量快源稀释，正好漏掉那个会抖的源。
         */
        long slowest = 0L;
        int slowCount = 0;
        for (String s : targets) {
            long c = routes.HealthRoutes.lastCost(s);
            // 只把"明显慢"的计入：正常源 0.1~1s，超过 2s 才算慢源（实测 Gz360 首轮 17.9s）
            if (c >= 2_000L) { slowCount++; slowest = Math.max(slowest, c); }
        }
        /*
         * 上限 10 秒而不是 12：实测 12 秒上限下，最长一次搜索花了 15.6 秒
         * （总预算 20s 内但体验已明显卡）。用户能接受的搜索等待大约在
         * 5~8 秒，超过就该用已有结果先给出来。
         *
         * 超过 10 秒的慢源本来就该被降级机制处理（连续 3 次失败 → 30 分钟跳过），
         * 不该让每次搜索都为它等满。
         */
        long waitBudget = Math.min(10_000L, Math.max(SEARCH_MIN_MS, slowest + SEARCH_IDLE_MS));
        if (slowCount > 0) {
            core.LogBuffer.info("search", "slowSites=" + slowCount + " slowest=" + slowest
                    + "ms waitBudget=" + waitBudget + "ms");
        }
        long lastProgress = System.currentTimeMillis();
        while (remaining-- > 0) {
            /*
             * ★ GC 闸门（`meow.gcgate=<MB>`，TV 端 32 位才设）：在**站点间隙**
             * 主动做一次 GC，而不是让 ART 在搜索风暴里自己触发。
             *
             * <p>依据（2026-11-05 真机 + AOSP pie heap.cc）：
             * <ul>
             *   <li>ART 下一次并发 GC 只等 {@code 128~512KB} 分配
             *       （kMin/kMaxConcurrentRemainingBytes），搜索时变成每秒数次；</li>
             *   <li>该机器 32 位 ART 的 {@code ConcurrentCopying} 有缺陷，
             *       <b>并发</b> GC 撞上就 {@code LogHeapCorruption} → SIGABRT；</li>
             *   <li>而"安静时刻"的显式 GC（boot 期 preGc）实测从未崩过。</li>
             * </ul>
             * 串行模式下这个循环就是唯一的搜索线程，在这里 GC 等于把
             * "随机时刻的并发 GC"换成"站点之间的可控 GC"。
             */
            gcGateTick();
            /*
             * ★ 被新一轮取代 → 立即退出。
             *
             * 用户连点两次搜索时，第一轮的聚合循环立刻停，把 CPU 与线程
             * 让给新一轮；它已产出的部分结果不再写进快照（快照属于旧 task，
             * 前端已经在读新 task 了）。
             */
            if (SEARCH_EPOCH.get() != myEpoch) {
                core.LogBuffer.info("search", "第 " + myEpoch + " 轮被新一轮取代，提前退出");
                break;
            }
            /*
             * ★ 全部已返回 → 立刻结束，不要再等一个空闲窗口。
             *
             * 踩过（实测数据）：18 个快源最慢 0.90s 全部返回，但聚合耗时稳定在
             * 4.2~5.0s。原因就是少了这一句——第 18 个源处理完后 remaining 已为 0，
             * 循环仍会再进一次，此时 mostReturned 成立、idleLeft 刚重置为 3500ms，
             * 于是白白 poll 等满 3.5 秒才退出。
             *
             * 3.5s ≈ 多出的一整个空闲窗口，对"全部源都很快"的常见情况是纯浪费。
             */
            if (returned >= totalTargets) break;
            long left = searchDeadline - System.currentTimeMillis();
            if (left <= 0) break;
            /*
             * 空闲提前收尾要三条同时成立，缺一条都会把慢源随机截掉（这就是抖动的来源）：
             *   1) 已经有结果 —— 一个都没有时收尾会把"刚开始还没回来"误判成"全挂"
             *   2) 已经跑满 SEARCH_MIN_MS —— 给首轮建连的慢源起步机会
             *   3) 多数站已返回 —— 剩下的确实是长尾，不是在排队
             *
             * ★ 第 3 条用"已返回比例"而不是绝对数：各形态的站表规模差很多
             *   （TV/docker 270 个、PC 插件 44 个、降级过滤后实际参与的可能只有 26 个），
             *   用绝对数会在小站表上一开始就满足、在大站表上永远不满足。
             */
            /*
             * 全部真跑的源都已返回 → 立刻收尾，不等空闲窗口。
             *
             * 命中缓存的源不进 futures，所以 remaining/returned 只反映
             * 真实在跑的源；缓存结果已在 merged 里就位。
             */
            int pendingReal = totalTargets - returned;
            boolean noPendingReal = pendingReal <= 0;
            boolean longEnough = System.currentTimeMillis() - searchStart >= waitBudget;
            boolean mostReturned = totalTargets > 0 && returned * 10 >= totalTargets * 9;
            /*
             * ★ 绝大多数源命中缓存时，剩余的真源不值得等。
             *
             * 实测（2026-10-30）：同词第 3 次搜索 263 个命中、只剩 2 个真源，
             * 但那 2 个恰是 TingBook/Tvyb 这类卡 30 秒的 —— 为了等它们，
             * 263 个已到手的结果一起被拖到 8 秒。
             *
             * 判据用缓存命中比例 ≥80%：此时用户要的结果已经在手，
             * 少数慢源晚到也只会让首屏"多几条"，不值得让整页转 8 秒。
             * 命中比例低时（换新词、加了新源）仍按正常预算等。
             */
            boolean mostlyCached = totalTargets > 0 && cacheHits * 10 >= totalTargets * 8
                    && merged.size() > 0;
            if (merged.size() > 0 && (noPendingReal || mostlyCached || longEnough || mostReturned)) {
                long idleLeft = (noPendingReal || mostlyCached)
                        ? 0
                        : SEARCH_IDLE_MS - (System.currentTimeMillis() - lastProgress);
                if (idleLeft <= 0) break;
                left = Math.min(left, idleLeft);
            }
            Future<String> f;
            try {
                f = done.poll(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            if (f == null) break;
            lastProgress = System.currentTimeMillis();
            returned++;
            if (task != null) {
                task.returned = returned;
                task.noiseFiltered = noiseHit.get();
                task.noiseDowngraded = noiseMarked.get();
            }
            String name = futureToSite.getOrDefault(f, "");
            String raw;
            try {
                raw = f.get();
            } catch (Exception ex) {
                continue;
            }
            /*
             * 统计脚本源的真实成败 —— 只用于日志诊断。
             *
             * 不再驱动"跳过"决策：熔断在 callScriptRuntime 内部按调用成败
             * 自己维护（recordScriptRuntime），这里只是把"这轮脚本源整体
             * 表现如何"记下来，方便排查。
             */
            if (isScriptApi(API.get(name))) {
                scriptTried++;
                if (raw == null || raw.isEmpty()) scriptEmpty++;
            }
            /*
             * 缓存已在 worker 里写过了（见 SITE_TIMEOUT_POOL.submit 那段注释），
             * 这里只做聚合。**不要再在这里写一遍** —— 聚合循环会因
             * epoch 作废 / mostlyCached 提前 break 而漏掉晚到的源，
             * 写在这里就等于"只有被 poll 到的源才有缓存"。
             */
            if (raw == null || raw.isEmpty()) continue;
            mergeOne(merged, name, raw, wd, noiseWords,
                    noiseFilterMode, noiseHit, noiseMarked);
            /*
             * 每收一个源就发布一次快照（不可变 String，见 SearchTask.snapshot）。
             * 只在**聚合线程内部**构造，构造完一次性 volatile 赋值，
             * 轮询线程读到的永远是完整、自洽的一份。
             */
            if (task != null) publishSnapshot(task, merged);
        }

        /*
         * ★ 收尾：丢弃本轮还没开始的任务，但**不关闭池**。
         *
         * 共享池要留给下一轮复用，所以不能 shutdownNow。只把队列里
         * "本轮排队但没轮到"的任务摘掉即可：
         *   - 这些任务没开始跑，摘掉零副作用
         *   - 不摘的话它们会在池里继续跑，占用下一轮的线程预算
         *   - 正在跑的那几个（≤ effThreads）无法中断（OkHttp 不响应 interrupt），
         *     让它们自然结束：预检已经把 227 个必然超时的脚本源排除了，
         *     剩下的真慢源最多几个，对下一轮的影响可忽略
         *
         * 摘队列用 drainTo 而不是 clear：Future 已被 submit 返回，
         * 调用方（聚合循环）已经退出，这些 Future 不会再被读取。
         */
        try {
            java.util.List<Runnable> dropped = new ArrayList<>();
            if (searchPool instanceof java.util.concurrent.ThreadPoolExecutor) {
                ((java.util.concurrent.ThreadPoolExecutor) searchPool).getQueue().drainTo(dropped);
            }
            if (!dropped.isEmpty()) {
                core.LogBuffer.info("search", "收尾丢弃 " + dropped.size() + " 个未开始的源任务");
            }
        } catch (Throwable ignored) {
        }

        /*
         * 脚本源的整轮表现只记日志，**不做任何跳过/降级决策**。
         *
         * 熔断在调用层（callScriptRuntime → recordScriptRuntime）按单次成败
         * 自己维护，粒度更细、恢复更快：连续 3 次运行时故障即压超时，
         * 任意一次成功立刻恢复。这里只把汇总写进日志便于排查。
         */
        if (scriptSubmitted >= 5) {
            core.LogBuffer.info("search", "脚本源本轮：提交 " + scriptSubmitted
                    + "，回来 " + scriptTried + "，空 " + scriptEmpty
                    + "，熔断=" + (scriptRuntimeBreakerOpen() ? "OPEN" : "closed"));
        }

        JSONArray groups = new JSONArray();
        synchronized (merged) {
            for (JSONObject v : merged.values()) groups.put(v);
        }
        System.err.println("[search] wd=" + wd + " sites=" + futures.size()
                + " groups=" + groups.length()
                + " cacheHits=" + cacheHits
                + " elapsed=" + (System.currentTimeMillis() - searchStart) + "ms");
        core.LogBuffer.info("search", "wd=" + wd + " 源=" + futures.size()
                + " 缓存命中=" + cacheHits + " 条目=" + groups.length()
                + " 耗时=" + (System.currentTimeMillis() - searchStart) + "ms");
        if (task != null) {
            /*
             * 收尾时必须再发布一次快照。
             *
             * 踩过（2026-10-30）：快照原本只在"有源返回时"发布，
             * 而全部命中缓存的那一轮 `remaining=0`，聚合循环一次都没进，
             * 于是 snapshot 始终是空数组 —— 前端轮询拿到 done=true
             * 却 count=0，显示"没有结果"，实际服务端日志是 34 条。
             */
            publishSnapshot(task, merged, true);
            task.done = true;
            task.finishedAt = System.currentTimeMillis();
        }
        JSONObject out = new JSONObject().put("groups", groups);
        // 告诉前端跳过了几个死源，避免"为什么这次结果变少了"的困惑
        if (skippedDegraded > 0) out.put("skippedDegraded", skippedDegraded);
        return out.toString();
    }

    /**
     * 把一个源的原始 JSON 结果并入聚合表。
     *
     * <p><b>为什么抽成方法</b>：缓存命中路径和真跑路径必须走完全相同的
     * 解析与相关性过滤，否则"缓存里的结果"和"刚跑出来的结果"会不一致
     * （比如缓存写的是过滤前的原始 JSON，合并时漏了 relevant 判断，
     * 就会冒出与关键词无关的条目）。
     *
     * <p><b>不按片名合并</b>：每个源各出一条。合并（key=片名）看着"干净"，
     * 但会丢掉线路 —— 详情页的线路来自单一站点，合并后点进去只剩第一个源的
     * 线路。影视仓的做法就是每源一条（同一部片出现 N 次，各带自己的源标记）。
     */
    private static void mergeOne(Map<String, JSONObject> merged, String name, String raw,
                               String wd, java.util.List<String> noiseWords,
                               boolean noiseFilter, java.util.concurrent.atomic.AtomicInteger noiseHit,
                               java.util.concurrent.atomic.AtomicInteger noiseMarked) {
        try {
            JSONArray list = new JSONObject(raw).optJSONArray("list");
            if (list == null) return;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                String title = item.optString("vod_name", "").trim();
                if (title.isEmpty()) continue;
                // 相关性过滤：部分源（实测 Hxq 等）搜索接口失灵时会原样返回首页
                // 推荐列表，搜什么词都吐同一批热播。不过滤的话聚合结果里全是
                // 与关键词无关的条目，用户会以为搜错了。
                if (!relevant(title, wd)) continue;
                /*
                 * 噪音词过滤：命中片名或备注就从结果里剔除。
                 *
                 * 匹配 remarks 是必要的 —— 大量源站把"解说""合集"写在备注里
                 * 而不是片名（片名仍是正剧名），只看片名会漏掉这类。
                 * 用 contains 而非分词：用户填的是"解说""混剪"这种短词，
                 * 中文分词在这里没有收益，反而会让"解说的艺术"匹配不到"解说"。
                 */
                boolean noise = false;
                if (!noiseWords.isEmpty()) {
                    String remarks = item.optString("vod_remarks", "");
                    noise = isNoise(title, remarks, noiseWords);
                }
                if (noise) {
                    if (noiseFilter) {
                        if (noiseHit != null) noiseHit.incrementAndGet();
                        continue;
                    }
                    /* rank 模式：保留条目，只打标记。前端 scoreGroup 见到
                       `noise:true` 就把它压到所有非噪音之后。 */
                    if (noiseMarked != null) noiseMarked.incrementAndGet();
                }
                String key = name + '\u0001' + title + '\u0001' + item.optString("vod_id", "");
                // 轮询端点会并发读 merged，写这一块要加同一把锁
                synchronized (merged) {
                    JSONObject entry = merged.get(key);
                    if (entry == null) {
                        entry = new JSONObject()
                                .put("vod_name", title)
                                .put("vod_pic", item.optString("vod_pic", ""))
                                .put("vod_remarks", item.optString("vod_remarks", ""))
                                .put("sources", new JSONArray());
                        merged.put(key, entry);
                    }
                    /* 同一 key 可能被多个源收录，任一源判定噪音就整体降级
                       （同一部"解说"不会因为另一个源没标备注就变回正片）。 */
                    if (noise) entry.put("noise", true);
                    ((JSONArray) entry.get("sources")).put(new JSONObject()
                            .put("site", name)
                            .put("site_name", SITES.getOrDefault(name, name))
                            .put("vod_id", item.optString("vod_id", ""))
                            .put("remarks", item.optString("vod_remarks", "")));
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 判断搜索结果是否与关键词相关。
     *
     * 规则故意放得很松，只剔除"完全无关"的条目：
     *  - 片名包含关键词，或关键词包含片名（后者覆盖"我不是大师"匹配"我不是大师 第一季"）
     *  - 关键词较长时（≥3 字）允许只命中前半段，容忍"庆余年" vs "庆余年第二季"
     * 一个字的关键词不做过滤，否则几乎全被误杀。
     */
    private static boolean relevant(String title, String wd) {
        String t = norm(title);
        String w = norm(wd);
        if (w.isEmpty() || t.isEmpty()) return true;
        if (w.length() < 2) return true;
        if (t.contains(w) || w.contains(t)) return true;
        // 取关键词前 2/3 做宽松匹配（"庆余年第二季" 搜 "庆余年" 已在上一条覆盖）
        if (w.length() >= 3) {
            String head = w.substring(0, Math.max(2, w.length() * 2 / 3));
            if (t.contains(head)) return true;
        }
        return false;
    }

    /**
     * 词表的作用方式：{@code filter}（默认，剔除）或 {@code rank}（保留但沉底）。
     *
     * <p>只认这两个值：写错了按 filter 处理 —— 用户填了词表却因为拼错
     * 一个词就完全失效，比"多滤掉几条"更难排查。
     */
    private static String noiseMode() {
        try {
            String m = loadPolicy().optString("searchNoiseMode", "filter");
            return "rank".equals(m) ? "rank" : "filter";
        } catch (Throwable t) {
            return "filter";
        }
    }

    /**
     * 读用户维护的噪音词表（policy.json 的 {@code searchNoise}）。
     *
     * <p>分隔符与弹幕屏蔽词保持一致（逗号/中文逗号/分号/换行），用户在同一套
     * 输入习惯里不用换心智。
     *
     * <p>空表返回空 List（不是 null）：调用方 {@code noiseWords.isEmpty()} 判空，
     * 返回 null 会 NPE。这是"没配就不过滤"的唯一开关，不额外加布尔字段 ——
     * 一个词表 + 一个开关会让人误以为"填了但没开"是 bug。
     */
    private static java.util.List<String> noiseWords() {
        try {
            String raw = loadPolicy().optString("searchNoise", "");
            if (raw == null || raw.trim().isEmpty()) return java.util.Collections.emptyList();
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String w : raw.split("[,，;；\r\n]+")) {
                String t = w.trim();
                // 单字太容易误伤（"剧""片"会命中几乎所有内容），跳过
                if (t.length() >= 2) out.add(t);
            }
            return out;
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 单条结果是否命中噪音词。
     *
     * <p>同时看片名和备注：大量源站把"解说/合集"放在 remarks 里而片名仍是
     * 正剧名，只看片名会漏掉最难忍的那一类。
     *
     * <p>大小写不敏感（"Reaction" 与 "reaction" 都算）。
     */
    private static boolean isNoise(String title, String remarks,
                                   java.util.List<String> words) {
        if (words == null || words.isEmpty()) return false;
        String t = title == null ? "" : title.toLowerCase();
        String r = remarks == null ? "" : remarks.toLowerCase();
        for (String w : words) {
            String k = w.toLowerCase();
            if (t.contains(k) || r.contains(k)) return true;
        }
        return false;
    }

    /** 归一化：去掉标点/空白/全角符号，统一小写，便于比较 */
    private static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- danmaku

    /**
     * 弹幕源的显示名（对齐影视仓/肥猫界面的中文昵称）。
     * 肥猫在 {@code merge/p016q/C0151t.java} 里硬编码了这 6 个 Tab 名，
     * 我们这里做「id → 中文名」映射，同时允许动态源（danmu_api 的 from xxx）透传。
     */
    private static final Map<String, String> DANMAKU_LABELS = new LinkedHashMap<>();
    static {
        DANMAKU_LABELS.put("iqiyi", "爱爱");
        DANMAKU_LABELS.put("qiyi", "爱爱");
        DANMAKU_LABELS.put("tencent", "腾疼");
        DANMAKU_LABELS.put("qq", "腾疼");
        DANMAKU_LABELS.put("youku", "悠悠");
        DANMAKU_LABELS.put("bilibili", "哔哔");
        DANMAKU_LABELS.put("bilibili1", "哔哔");
        DANMAKU_LABELS.put("mango", "茫茫");
        DANMAKU_LABELS.put("imgo", "茫茫");
        DANMAKU_LABELS.put("migu", "咪咕");
        DANMAKU_LABELS.put("sohu", "搜狐");
        DANMAKU_LABELS.put("leshi", "乐视");
        DANMAKU_LABELS.put("xigua", "西瓜");
        DANMAKU_LABELS.put("renren", "人人");
        DANMAKU_LABELS.put("hanjutv", "韩剧");
        DANMAKU_LABELS.put("bahamut", "巴哈");
        DANMAKU_LABELS.put("dandan", "弹弹");
        DANMAKU_LABELS.put("animeko", "Animeko");
        DANMAKU_LABELS.put("360", "采集站");
        DANMAKU_LABELS.put("cms", "采集站");
        DANMAKU_LABELS.put("danmu_api", "自建");
        DANMAKU_LABELS.put("aggregator", "聚合");
    }

    private static String danmakuLabel(String source) {
        if (source == null) return "其他";
        return DANMAKU_LABELS.getOrDefault(source.toLowerCase(), source);
    }

    /**
     * 弹幕源优先级。数值越小越先尝试；同优先级并发。
     *
     * <p>顺序依据 2026-10-21 实测：
     * <ol>
     *   <li><b>iqiyi</b> —— 官方 Bullet 链路实测 26104 条，最稳</li>
     *   <li><b>tencent</b> —— {@code MbSearchHttp} 实测 200/15 条，可用</li>
     *   <li><b>danmu_api</b> —— 用户自建（policy.json 的 danmakuApi），覆盖面广</li>
     *   <li><b>aggregator</b> —— 肥猫联盟 6 域名（已大面积失效，仅作兜底）</li>
     * </ol>
     * youku / bilibili 需要 cookie，实测返回空；采集站 {@code vs.okcdn100.top} 已 404。
     * 这几个留着开关但默认关闭，等有 cookie 方案再打开。
     */
    private static final String[] DANMAKU_PRIORITY = {"iqiyi", "tencent", "danmu_api", "aggregator"};

    /**
     * 弹幕搜索 + 自动抓取的统一入口（手动兜底）。
     *
     * <p>GET /api/proxy/danmaku/search?name=xxx&amp;episode=1
     * <pre>
     * { "name": "我不是大师",
     *   "sources": [{"id":"iqiyi","label":"爱爱","count":24}, ...],
     *   "items": [ {"name":"...第1集...","url":"/api/proxy/danmaku/xml?id=iqiyi:4255991179147000:1",
     *               "source":"iqiyi","label":"爱爱","episode":1,"host":"..."} ] }
     * </pre>
     * 排序：先按 episode 数值，再按 source 优先级，保证「第1集/第2集/…/第10集」自然递增。
     */
    /**
     * 固定胶囊顺序（对齐影视仓/肥猫的 6 个 Tab）。
     *
     * <p>无论本次搜索是否命中，前端都按这个顺序渲染胶囊；
     * 未命中的显示 0 并在点击后是空列表，这样胶囊位置不会跳动。
     * 额外抓到的平台追加在后面。
     */
    private static final String[] DANMAKU_FIXED_LABELS = {
            "采集站", "爱爱", "腾疼", "悠悠", "哔哔", "茫茫"
    };

    private static void handleDanmakuSearch(HttpExchange ex) throws Exception {
        Map<String, String> q = query(ex);
        String name = q.getOrDefault("name", "").trim();
        if (name.isEmpty()) {
            sendJson(ex, 200, "{\"items\":[],\"sources\":[],\"fixed\":[]}");
            return;
        }
        String clean = cleanName(name);
        List<JSONObject> items = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        // 1) danmu_api / 聚合站 → via=aggregator
        //
        // 【性能】原先串行轮询 7 个 host、每个 8s 超时 → 最坏 56s。
        // 实测用户自建 danmu_api 5ms 就返回 200+ 条，但后面 6 个社区镜像
        // （vercel.junce.* 等）多为死域名，每个都要等满超时，导致总耗时 8s+。
        //
        // 改为：并发轮询 + 单 host 超时降到 4s + **首个成功且非空的 host 直接收工**。
        // 用户自建地址排在 danmakuHosts() 最前，正常情况 5ms 就结束。
        List<JSONObject> aggregated = pollAggregators(danmakuHosts(), clean, 4_000);
        for (JSONObject o : aggregated) {
            o.put("via", "aggregator");
            if (seen.add("aggregator|" + o.optString("source") + "|" + o.optString("name"))) {
                items.add(o);
            }
        }

        // 2) 官方 API 直连 → via=direct
        for (JSONObject o : searchOfficialSources(clean)) {
            o.put("via", "direct");
            if (seen.add("direct|" + o.optString("source") + "|" + o.optString("name"))) {
                items.add(o);
            }
        }

        // 3) 排序：episode 升序 → via(direct 先) → 源优先级 → name
        items.sort((a, b) -> {
            int ea = a.optInt("episode", 9999), eb = b.optInt("episode", 9999);
            if (ea != eb) return Integer.compare(ea, eb);
            int va = "direct".equals(a.optString("via")) ? 0 : 1;
            int vb = "direct".equals(b.optString("via")) ? 0 : 1;
            if (va != vb) return Integer.compare(va, vb);
            int pa = sourcePriority(a.optString("source")), pb = sourcePriority(b.optString("source"));
            if (pa != pb) return Integer.compare(pa, pb);
            return a.optString("name").compareTo(b.optString("name"));
        });

        // 4) 上限 3000（原 40 → 200 → 600 都曾白丢候选）
        //
        // 【2026-11-04 用户报「总数 197 只能滚动到 52」的真因】
        // 计数（下面第 5 步）原来遍历的是**未截断**的 items，而返回给前端的是
        // 截断后的 arr。实测一部剧：direct 130 + aggregator 197 = 327 条，
        // arr 只留 200 条（排序后 direct 优先，aggregator 只剩 52 条），
        // 于是胶囊写着「腾疼 197」，点进去只能翻到 52 条 —— 数字和内容对不上。
        // 现在：上限抬到 600（327 条约 60KB，局域网可接受），并且
        // **计数改成遍历截断后的 arr**，任何情况下「胶囊数字 == 能翻到的条数」。
        /*
         * 3000 是"长番兜底"而不是随意数字：海贼王/柯南这类 1000+ 集的，
         * 按集数升序排完截到 200/600 会把用户正在看的那一集（比如第 900 集）
         * 直接切掉 —— 用户在前端翻到底也找不到自己那一集。
         * 1100 条的 JSON 约 220KB，局域网一次拉完约 0.2s，可以接受。
         */
        int cap = Math.min(items.size(), 3000);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < cap; i++) arr.put(items.get(i));

        // 5) 分组：同一 label 下按 via 拆成 direct / aggregator 两路，
        //    前端用不同颜色区分，数据不混。
        //    ★ 必须数 arr（截断后）而不是 items，否则数字会大于实际可翻条数。
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (String lb : DANMAKU_FIXED_LABELS) counts.put(lb, new int[2]);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            int[] c = counts.computeIfAbsent(o.optString("label"), k -> new int[2]);
            if ("direct".equals(o.optString("via"))) c[0]++;
            else c[1]++;
        }
        JSONArray srcArr = new JSONArray();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            int d = e.getValue()[0], a = e.getValue()[1];
            if (d + a == 0) continue;   // 无命中的固定胶囊由前端补 0
            srcArr.put(new JSONObject()
                    .put("label", e.getKey())
                    .put("count", d + a)
                    .put("direct", d)
                    .put("aggregator", a));
        }
        JSONArray fixedArr = new JSONArray();
        for (String lb : DANMAKU_FIXED_LABELS) fixedArr.put(lb);

        sendJson(ex, 200, new JSONObject()
                .put("name", clean)
                .put("fixed", fixedArr)
                .put("sources", srcArr)
                .put("items", arr)
                .toString());
    }

    /** 源的尝试优先级（数字小的先）。 */
    private static int sourcePriority(String source) {
        if (source == null) return 99;
        for (int i = 0; i < DANMAKU_PRIORITY.length; i++) {
            if (DANMAKU_PRIORITY[i].equalsIgnoreCase(source)) return i;
        }
        return 50;
    }

    /**
     * 搜索聚合站（danmu_api / 肥猫联盟域名兼容形态），返回结构化候选。
     * 从 name 里的 {@code from xxx} 解析真实平台，映射成中文 label。
     */
    /**
     * 并发轮询多个聚合 host，返回**第一个非空**的结果。
     *
     * <p>为什么并发：用户自建 danmu_api 通常 5ms 返回，而内置兜底里
     * 多个社区镜像已是死域名，串行会把它们的超时累加到用户身上。
     *
     * <p>为什么"首个成功即收工"：聚合站返回的是**候选列表**，不同镜像
     * 内容高度重叠（都转同一批上游）。拿到一份非空就够用，继续等其余
     * 只会拖慢响应。若首个成功的候选很少（&lt; 3 条），才继续收集其余
     * 已完成的结果做并集。
     */
    private static List<JSONObject> pollAggregators(String[] hosts, String clean, int timeout) {
        List<JSONObject> out = new ArrayList<>();
        if (hosts == null || hosts.length == 0) return out;

        List<java.util.concurrent.Future<List<JSONObject>>> fs = new ArrayList<>();
        for (String h : hosts) {
            fs.add(POOL.submit(() -> {
                try {
                    return searchAggregator(h, clean, timeout);
                } catch (Throwable t) {
                    return new ArrayList<JSONObject>();
                }
            }));
        }

        long deadline = System.currentTimeMillis() + timeout + 1_000L;
        List<JSONObject> firstOk = null;
        for (java.util.concurrent.Future<List<JSONObject>> f : fs) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            try {
                List<JSONObject> r = f.get(left, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (r != null && !r.isEmpty()) {
                    if (firstOk == null) firstOk = r;
                    else firstOk.addAll(r);          // 其余成功的做并集
                    // 首个结果足够多 → 不再等剩下的
                    if (firstOk.size() >= 3) break;
                }
            } catch (Exception ignored) {
            }
        }
        if (firstOk != null) out.addAll(firstOk);
        return out;
    }

    private static List<JSONObject> searchAggregator(String host, String clean, int timeout) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        String searchUrl = danmakuUrl(host, URLEncoder.encode(clean, "UTF-8"), "1", "json");
        String find = fetchText(searchUrl, timeout);
        if (find == null || !find.trim().startsWith("[")) return out;
        JSONArray arr = new JSONArray(find);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String u = o.optString("url", "");
            String n = o.optString("name", "第" + (i + 1) + "集");
            if (u.isEmpty()) continue;
            String src = parseSourceFromName(n);
            out.add(new JSONObject()
                    .put("name", n)
                    .put("url", u)
                    .put("source", src)
                    .put("label", danmakuLabel(src))
                    .put("episode", parseEpisodeFromName(n))
                    .put("host", host));
        }
        return out;
    }

    /** 从「...from iqiyi - 【qiyi】 第1集」里解析出 iqiyi。 */
    private static String parseSourceFromName(String name) {
        if (name == null) return "aggregator";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("from\\s+([a-zA-Z0-9]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(name);
        if (m.find()) return m.group(1).toLowerCase();
        java.util.regex.Matcher b = java.util.regex.Pattern.compile("【([^】]+)】").matcher(name);
        if (b.find()) return b.group(1).toLowerCase();
        return "aggregator";
    }

    /** 从「第12集」「第 3 期」里解析出 12 / 3；解析不到返回 9999（排最后）。 */
    private static int parseEpisodeFromName(String name) {
        if (name == null) return 9999;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("第\\s*(\\d+)\\s*[集期话回]").matcher(name);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        // 「EP12」「E12」
        java.util.regex.Matcher e = java.util.regex.Pattern
                .compile("(?:EP|E)\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(name);
        if (e.find()) {
            try {
                return Integer.parseInt(e.group(1));
            } catch (Exception ignored) {
            }
        }
        return 9999;
    }

    /**
     * 官方源搜索：iqiyi（已验证）+ tencent（已验证）。
     *
     * <p>youku / bilibili 的搜索接口实测需要 cookie（返回空），采集站 {@code vs.okcdn100.top} 已 404，
     * 所以默认不纳入。它们的协议已从肥猫 {@code merge/p016q/J.java} 还原：
     * <pre>
     * youku:    https://search.youku.com/api/search?pg=1&keyword=     → pageComponentList
     * bilibili: https://api.bilibili.com/x/web-interface/search/type?search_type=media_bangumi
     * 采集站:    https://vs.okcdn100.top/api.php/provide/vod/?ac=detail&wd=
     * </pre>
     * 有 cookie 方案后补 searchYouku / searchBilibili 即可，接口已留好。
     */
    private static List<JSONObject> searchOfficialSources(String clean) {
        List<JSONObject> out = new ArrayList<>();
        // iqiyi：一次搜索能拿到整季的分集，直接展开
        try {
            out.addAll(searchIqiyi(clean));
        } catch (Throwable t) {
            System.err.println("[danmaku] search iqiyi failed: " + t);
        }
        try {
            out.addAll(searchTencent(clean));
        } catch (Throwable t) {
            System.err.println("[danmaku] search tencent failed: " + t);
        }
        /*
         * 360 看（采集站）：补的是「前两家搜不到正片」的缺口。
         *
         * 实测（2026-11-02）：
         *   · 凡人修仙传 —— 爱奇艺搜索只有片花/解说/游戏（正片在 B站 + 优酷），
         *     360 给出 {@code bilibili1} 196 集、{@code youku}；
         *   · 小城良方 —— 360 给 29 集，爱奇艺只给 20 集（多出未上线的预告集）；
         *   · 庆余年第二季 / 我不是大师 —— 360 的站内索引**反而 0 条**，
         *     所以它是**补充**而不是替代，必须排在前两家之后。
         *
         * 它自己不含弹幕（上游 getEpisodeDanmu 是空函数），返回的是
         * 「哪个平台、第几集、播放页 URL」——用来定位真正的弹幕源。
         */
        try {
            out.addAll(search360(clean));
        } catch (Throwable t) {
            System.err.println("[danmaku] search 360 failed: " + t);
        }
        return out;
    }

    /** 360 搜索的基础 URL（{@code kw} 由调用方编码）。 */
    private static final String S360_SEARCH =
            "https://api.so.360kan.com/index?force_v=1&from=&pageno=1&v_ap=1&tab=all&kw=";
    /** 360 需要桌面 Chrome 的 UA，否则返回空 rows。 */
    private static final String S360_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";

    /**
     * 从「庆余年第二季」里拆出 (基础词, 季度号)。
     *
     * <p>360 的站内搜索对关键词很敏感：搜「庆余年」有 4 条（含第二季），
     * 搜「庆余年第二季」**0 条**，搜「庆余年2」只有 2 条。所以必须先拆：
     * 基础词拿去请求，季度号只用来挑哪一条。
     *
     * @return {@code [基础词, 季度号字符串]}，无季度时第二项为空串
     */
    private static String[] splitSeasonKeyword(String raw) {
        String s = raw == null ? "" : raw.trim();
        java.util.regex.Matcher cn = java.util.regex.Pattern
                .compile("第\\s*([一二三四五六七八九十\\d]+)\\s*[季部]\\s*$").matcher(s);
        if (cn.find()) {
            return new String[]{s.substring(0, cn.start()).trim(), cn.group(1)};
        }
        java.util.regex.Matcher en = java.util.regex.Pattern
                .compile("(?i)\\s*(?:season\\s*(\\d+)|\\sS(\\d{1,2}))\\s*$").matcher(s);
        if (en.find()) {
            String n = en.group(1) != null ? en.group(1) : en.group(2);
            return new String[]{s.substring(0, en.start()).trim(), n};
        }
        // 结尾纯数字：只剥一位（「庆余年2」→「庆余年」+2），
        // 避免把「2046」「1921」这类片名拆坏
        java.util.regex.Matcher num = java.util.regex.Pattern.compile("^(.*[^\\d\\s])\\s*(\\d)$").matcher(s);
        if (num.find()) {
            return new String[]{num.group(1).trim(), num.group(2)};
        }
        return new String[]{s, ""};
    }

    /** 中文数字 → 阿拉伯数字（只覆盖季度会用到的 1–20）。 */
    private static int cnNum(String s) {
        if (s == null || s.isEmpty()) return 0;
        if (s.matches("\\d+")) return Integer.parseInt(s);
        String digits = "零一二三四五六七八九十";
        if ("十".equals(s)) return 10;
        if (s.length() == 1) {
            int i = digits.indexOf(s);
            return i < 0 ? 0 : i;
        }
        if (s.length() == 2 && s.startsWith("十")) {
            int i = digits.indexOf(s.charAt(1));
            return i < 0 ? 0 : 10 + i;
        }
        if (s.length() == 2 && s.endsWith("十")) {
            int i = digits.indexOf(s.charAt(0));
            return i < 0 ? 0 : i * 10;
        }
        if (s.length() == 3 && s.charAt(1) == '十') {
            int a = digits.indexOf(s.charAt(0));
            int b = digits.indexOf(s.charAt(2));
            return a < 0 || b < 0 ? 0 : a * 10 + b;
        }
        return 0;
    }

    /**
     * 360 看（采集站）搜索：给出「哪个平台、第几集」的定位信息。
     *
     * <p>覆盖的是爱奇艺/腾讯搜索不到的正片：芒果、优酷、B站、咪咕等。
     * 候选的 {@code url} 指向我们自己的 XML 端点，由 {@link #fetchDanmakuAuto0}
     * 按 {@code id=平台:平台内id:集} 再走对应平台的弹幕接口。
     *
     * <p><b>为什么只收有 seriesPlaylinks 的</b>：那个数组是 360 抓好的
     * 「每集播放页 URL」，里面直接带平台内的视频 id（如爱奇艺
     * {@code v_16qlxcepnkk.html}），比「按标题再搜一次」精确得多。
     * 没有它的条目（电影、综艺、或只给了专辑首页）拿不到逐集 id，展开不了。
     */
    /**
     * 从 360 的搜索结果里取某一集的腾讯 vid（自动抓取用）。
     *
     * <p>为什么自动抓取也走 360：它给的 {@code seriesPlaylinks} 已经是
     * 「每集一个播放页 URL」，URL 里直接带 vid；而 {@link #searchTencent}
     * 拿到的是 cid，还要多一次 {@code float_vinfo2} 请求才能换到 vid
     * （且对未上线/超前点播的集经常换不到）。
     *
     * @return vid；360 没有这部剧、或这部剧不在腾讯时返回空串
     */
    private static String tencentVidFrom360(String clean, int ep) {
        try {
            List<JSONObject> cands = search360(clean);
            for (JSONObject o : cands) {
                String id = o.optString("id", "");
                // 候选的 id 形如 "tencent:<vid>:<集>"（360 的来源标记在 source 字段）
                if (!id.startsWith("tencent:")) continue;
                if (o.optInt("episode", 0) != ep) continue;
                String[] p = id.split(":");
                if (p.length >= 3) return p[1];
            }
        } catch (Throwable t) {
            System.err.println("[danmaku] tencentVidFrom360: " + t);
        }
        return "";
    }

    private static List<JSONObject> search360(String clean) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        String[] parts = splitSeasonKeyword(clean);
        String base = parts[0].isEmpty() ? clean : parts[0];
        int wantSeason = cnNum(parts[1]);

        /*
         * ★ 360 会**间歇性返回空结果**（2026-11-02 实测）：
         *   同一条请求，第 1、2 次返回 {@code code:0} 但 `longData` 是空对象
         *   （连 rows 键都没有），第 3 次才给出 4 条。间隔约 2 秒。
         *   这不是限流报错，是它的缓存/预热行为——只看一次会得到"这部剧 360 没有"
         *   的错误结论（我在命令行里就因此误判过两次）。
         *
         * 所以这里重试 3 次、每次间隔 400ms。总耗时上限约 1.2s + 3×请求时间，
         * 而它在 searchOfficialSources 里是**串行最后一步**，只在这两家都没结果时
         * 才有意义，加这点延迟不影响主路径。
         */
        JSONArray rows = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            String body = fetchText(S360_SEARCH + URLEncoder.encode(base, "UTF-8"),
                    8_000, "https://www.360kan.com/", S360_UA);
            if (body != null && body.trim().startsWith("{")) {
                JSONObject data = new JSONObject(body).optJSONObject("data");
                JSONObject longData = data == null ? null : data.optJSONObject("longData");
                JSONArray r = longData == null ? null : longData.optJSONArray("rows");
                if (r != null && r.length() > 0) {
                    rows = r;
                    break;
                }
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (rows == null || rows.length() == 0) return out;

        /*
         * 挑哪一条：优先「季度号对得上」，其次「集数最多的正片」。
         *
         * 实测 rows 里常混着「解说版」「手语版」「特别版」——它们集数少、
         * 且没有 seriesPlaylinks，天然被下面的过滤挡掉；但同一部剧的不同年份
         * 版本（凡人修仙传 2020 动漫 / 2025 电视剧）必须靠集数多来选。
         */
        JSONObject picked = null;
        int bestScore = -1;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            JSONArray sp = r.optJSONArray("seriesPlaylinks");
            if (sp == null || sp.length() == 0) continue;
            String title = r.optString("titleTxt", "");
            int season = extractSeasonNo(title);
            int score = sp.length();
            // 季度完全对得上 → 直接锁定，不用比集数
            if (wantSeason > 0 && season == wantSeason) {
                picked = r;
                bestScore = Integer.MAX_VALUE;
                break;
            }
            // 没有季度要求时，只要不是番外/解说就收（按集数最多）
            boolean isSpinoff = title.contains("解说") || title.contains("手语")
                    || title.contains("特别版") || title.contains("预告");
            if (wantSeason == 0 && isSpinoff) continue;
            if (wantSeason > 0 && season > 0 && season != wantSeason) continue;
            if (score > bestScore) {
                bestScore = score;
                picked = r;
            }
        }
        if (picked == null) return out;

        String title = picked.optString("titleTxt", base);
        String cat = picked.optString("cat_name", "");
        String year = picked.optString("year", "");
        String seriesSite = picked.optString("seriesSite", "");
        JSONArray links = picked.optJSONArray("seriesPlaylinks");

        // 按平台分组：seriesPlaylinks 是「第 1..N 集」的顺序数组，
        // 下标即集号（与上游 kan360.js 的 `name = i + 1` 一致）。
        java.util.LinkedHashMap<String, List<JSONObject>> bySite = new java.util.LinkedHashMap<>();
        for (int i = 0; i < links.length(); i++) {
            Object item = links.opt(i);
            String epUrl = null;
            if (item instanceof JSONObject) {
                epUrl = ((JSONObject) item).optString("url", "");
            } else if (item instanceof String) {
                // 少数节点是纯字符串（关联链接），退回专辑主链接
                JSONObject pl = picked.optJSONObject("playlinks");
                epUrl = pl == null ? "" : pl.optString(seriesSite, "");
            }
            if (epUrl == null || epUrl.isEmpty()) continue;
            // 平台归属：优先 seriesSite；它为空时按 URL 域名猜
            String site = seriesSite.isEmpty() ? guessPlatformByUrl(epUrl) : seriesSite;
            if (site.isEmpty()) continue;
            /*
             * 只收我们有取弹幕实现的平台，且**归一成 handler key**。
             *
             * 360 的平台名（qiyi / qq / bilibili1 / imgo）与 {@link #loadDanmakuById}
             * 认的 key（iqiyi / tencent）不一致；不归一的话候选全变成空 XML。
             * 归一后 url 里的 id 形如 {@code iqiyi:tvId:3} ——
             * tvId 用 {@code v_xxx} 形式，由 {@link #loadDanmakuById} 现查真实时长。
             */
            String handler = danmakuHandlerFor(site);
            if (handler == null) continue;
            String innerId = extractPlatformId(site, epUrl);
            if (innerId.isEmpty()) continue;
            int ep = i + 1;
            String label = danmakuLabel(site);
            List<JSONObject> bucket = bySite.get(site);
            if (bucket == null) {
                bucket = new ArrayList<>();
                bySite.put(site, bucket);
            }
            bucket.add(new JSONObject()
                    .put("name", title + "(" + year + ") 第" + ep + "集")
                    .put("url", "/api/proxy/danmaku/xml?id=" + handler + ":" + innerId + ":" + ep)
                    .put("id", handler + ":" + innerId + ":" + ep)
                    .put("source", "360")
                    .put("label", label)
                    .put("platform", site)
                    .put("episode", ep)
                    .put("host", "360kan.com")
                    .put("cat", cat));
        }
        for (List<JSONObject> bucket : bySite.values()) out.addAll(bucket);
        if (!out.isEmpty()) {
            System.out.println("[danmaku] 360: " + title + " → "
                    + bySite.keySet() + " 共 " + out.size() + " 集");
        }
        return out;
    }

    /** 从标题里抽季度号（第2季 / 第二季 / Season 2 / S2）。 */
    private static int extractSeasonNo(String title) {
        if (title == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("第\\s*([一二三四五六七八九十\\d]+)\\s*[季部]").matcher(title);
        if (m.find()) return cnNum(m.group(1));
        java.util.regex.Matcher e = java.util.regex.Pattern
                .compile("(?i)(?:season\\s*(\\d+)|\\bS(\\d{1,2})\\b)").matcher(title);
        if (e.find()) {
            try {
                return Integer.parseInt(e.group(1) != null ? e.group(1) : e.group(2));
            } catch (Exception ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** 按 URL 域名猜平台 key（seriesSite 为空时兜底）。 */
    private static String guessPlatformByUrl(String url) {
        if (url == null) return "";
        String u = url.toLowerCase();
        if (u.contains("iqiyi.com")) return "qiyi";
        if (u.contains("v.qq.com") || u.contains("qq.com")) return "qq";
        if (u.contains("youku.com")) return "youku";
        if (u.contains("bilibili.com")) return "bilibili1";
        if (u.contains("mgtv.com")) return "mango";
        if (u.contains("miguvideo.com")) return "migu";
        if (u.contains("sohu.com")) return "sohu";
        if (u.contains("le.com")) return "leshi";
        if (u.contains("ixigua.com")) return "xigua";
        return "";
    }

    /**
     * 从平台播放页 URL 里抽出「平台内的视频 id」。
     *
     * <p>只有抽得出 id 的平台才算「能取到弹幕」，其余返回空串 → 该集被丢弃。
     */
    private static String extractPlatformId(String site, String url) {
        if (site == null || url == null) return "";
        String u = url.trim();
        try {
            String s = site.toLowerCase();
            if (s.equals("qiyi") || s.equals("iqiyi")) {
                // http://www.iqiyi.com/v_16qlxcepnkk.html → v_16qlxcepnkk
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("/v_([a-z0-9]+)\\.html").matcher(u);
                return m.find() ? "v_" + m.group(1) : "";
            }
            if (s.equals("qq") || s.equals("tencent")) {
                // https://v.qq.com/x/cover/<cid>/<vid>.html → vid
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("/([a-z0-9]{8,})\\.html").matcher(u);
                if (m.find()) return m.group(1);
                java.util.regex.Matcher q = java.util.regex.Pattern
                        .compile("[?&]vid=([a-z0-9]+)").matcher(u);
                return q.find() ? q.group(1) : "";
            }
            if (s.equals("youku")) {
                // https://v.youku.com/v_show/id_XNTk1MjM0NTY=.html → id_XNTk1MjM0NTY=
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("id_([A-Za-z0-9=]+)").matcher(u);
                return m.find() ? "id_" + m.group(1) : "";
            }
            if (s.startsWith("bilibili")) {
                // https://www.bilibili.com/bangumi/play/ep123456 → ep123456
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("/(ep\\d+|ss\\d+|BV[a-zA-Z0-9]+)").matcher(u);
                return m.find() ? m.group(1) : "";
            }
            if (s.equals("mango") || s.equals("imgo")) {
                // https://www.mgtv.com/b/466047/19712564.html → 19712564
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("/(\\d{6,})\\.html").matcher(u);
                return m.find() ? m.group(1) : "";
            }
            if (s.equals("migu")) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("/(\\d{6,})").matcher(u);
                return m.find() ? m.group(1) : "";
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * 这个平台有没有「取弹幕」的实现，返回 handler key（null = 不支持）。
     *
     * <p><b>只有腾讯能从 360 的数据里直接取到弹幕</b>（2026-11-02 实测）：
     * 360 的 `seriesPlaylinks[].url` 对腾讯是
     * {@code https://v.qq.com/x/cover/<cid>/<vid>.html}，那个 {@code vid}
     * （形如 {@code j4102tpddr4}）**正是 mfm.video.qq.com 要的 target_id**，
     * 实测一次就返回 284 条真实弹幕。这顺带解决了老实现里最绕的一步
     * （拿 cid 还要过 float_vinfo2 转 vid）。
     *
     * <p><b>爱奇艺不行</b>：360 给的 {@code v_xxx.html} 里那个 {@code v_xxx}
     * 与 Bullet 接口要的 16 位数字 tvId 是两套 id，且爱奇艺没有任何公开接口
     * 能做这个转换（pcw-api 的 baseinfo/info/videolist 全 404，播放页是 SPA
     * 外壳、HTML 里搜不到 tvId）。所以爱奇艺继续走 {@link #searchIqiyi}
     * 的「按标题搜整季」路线，360 这一路跳过它，避免产出点不动的候选。
     *
     * <p><b>新增平台时必须同步这个方法</b>，并且真的实现 {@link #loadDanmakuById}
     * 里对应的分支——否则用户会看到一堆点了只有空 XML 的候选。
     */
    private static String danmakuHandlerFor(String site) {
        if (site == null) return null;
        String s = site.toLowerCase();
        if (s.equals("qq") || s.equals("tencent")) return "tencent";
        return null;
    }


    /** 爱奇艺搜索：search.video.iqiyi.com → albumDocInfo.videoinfos（含 tvId / timeLength）。 */
    private static List<JSONObject> searchIqiyi(String clean) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        String searchUrl = IQIYI_SEARCH + URLEncoder.encode(clean, "UTF-8");
        String body = fetchText(searchUrl, 12_000, IQIYI_REFERER);
        if (body == null) return out;
        JSONObject data = new JSONObject(body).optJSONObject("data");
        JSONArray docs = data == null ? null : data.optJSONArray("docinfos");
        if (docs == null) return out;

        for (int i = 0; i < docs.length(); i++) {
            JSONObject album = docs.optJSONObject(i).optJSONObject("albumDocInfo");
            if (album == null) continue;
            if (!IQIYI_CHANNEL.matcher(album.optString("channel", "")).matches()) continue;
            String title = album.optString("albumTitle", clean);
            JSONArray vids = album.optJSONArray("videoinfos");
            if (vids == null) continue;
            for (int k = 0; k < vids.length(); k++) {
                JSONObject v = vids.optJSONObject(k);
                if (v == null) continue;
                String tvId = String.valueOf(v.optLong("tvId", 0));
                int ep = v.optInt("itemNumber", k + 1);
                if (tvId.length() < 8) continue;
                // 把时长一起带进缓存：真正取弹幕时按 300 秒一片算分片数，
                // 没有时长就只能瞎猜（早先传集数 → 只下 1 片）。
                int dur = v.optInt("timeLength", 0);
                if (dur > 0) IQIYI_DURATION.put(tvId, dur);
                out.add(new JSONObject()
                        .put("name", title + " 第" + ep + "集")
                        .put("url", "/api/proxy/danmaku/xml?id=iqiyi:" + tvId + ":" + ep)
                        .put("id", "iqiyi:" + tvId + ":" + ep)
                        .put("source", "iqiyi")
                        .put("label", "爱爱")
                        .put("episode", ep)
                        .put("host", "iqiyi.com"));
            }
            break;   // 只取第一个正片
        }
        return out;
    }

    /**
     * 腾讯视频搜索：MbSearchHttp（POST JSON）→ normalList.itemList[].doc.id（cid）。
     *
     * <p>注意：搜索结果给的是 <b>cid</b>（专辑/剧集 id），弹幕接口要的是 <b>video_id</b>
     * （形如 {@code k41020ir430}）。两者需要经过 {@code float_vinfo2} 转换，
     * 所以候选里先存 cid，真正取弹幕时再解析（见 {@link #fetchTencentByCid}）。
     */
    private static List<JSONObject> searchTencent(String clean) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        JSONObject payload = new JSONObject()
                .put("version", "")
                .put("sceneId", 21)
                .put("platform", "23")
                .put("filterValue", "firstTabid=150")
                .put("uuid", "05d689a9-7991-4f54-befb-b616e473b95a")
                .put("retry", 0)
                .put("query", clean);
        String body = postJson(TENCENT_SEARCH, payload.toString(), TENCENT_REFERER, 12_000);
        if (body == null) return out;
        JSONObject data = new JSONObject(body).optJSONObject("data");
        JSONObject normal = data == null ? null : data.optJSONObject("normalList");
        JSONArray list = normal == null ? null : normal.optJSONArray("itemList");
        if (list == null) return out;

        for (int i = 0; i < list.length() && i < 5; i++) {
            JSONObject doc = list.optJSONObject(i).optJSONObject("doc");
            if (doc == null) continue;
            String cid = doc.optString("id", "");
            if (cid.isEmpty()) continue;
            out.add(new JSONObject()
                    .put("name", clean + " · 腾疼")
                    .put("url", "/api/proxy/danmaku/xml?id=tencent:" + cid + ":1")
                    .put("id", "tencent:" + cid + ":1")
                    .put("source", "tencent")
                    .put("label", "腾疼")
                    .put("episode", 1)
                    .put("host", "v.qq.com"));
        }
        return out;
    }

    /**
     * 腾讯：cid → float_vinfo2 取 video_ids → 拉弹幕。
     *
     * @param cid 搜索结果里的 doc.id（专辑 id）
     * @param ep  集数（从 1 开始）；越界时取最后一个
     */
    private static String fetchTencentByCid(String cid, int ep) {
        try {
            String body = fetchTextPlain(TENCENT_VINFO + cid, 12_000, TENCENT_REFERER);
            if (body == null) return "";
            int st = body.indexOf('{');
            if (st < 0) return "";
            JSONObject o = new JSONObject(body.substring(st));
            JSONObject c = o.optJSONObject("c");
            JSONArray vids = c == null ? null : c.optJSONArray("video_ids");
            if (vids == null || vids.length() == 0) return "";
            int idx = Math.max(0, Math.min(ep - 1, vids.length() - 1));
            String vid = vids.optString(idx, "");
            if (vid.isEmpty()) return "";
            return fetchTencentXml(vid, ep);
        } catch (Throwable t) {
            System.err.println("[danmaku] tencent by cid: " + t);
            return "";
        }
    }

    /**
     * 弹幕代理。按肥猫 merge/p015p/m.java 的 Fongmi 协议实现：
     *   1. GET {host}/api/v2/fongmi/danmaku?name=&episode=&format=xml  -> [{name,url}]
     *   2. GET url                                   -> <i><d p="...">...</d></i>
     * 多源降级。
     */
    private static final String[] DANMAKU_HOSTS = {
            // 弹弹play 官方（肥猫 m.j() 的协议端点；需要 AppId 签名时会 403）
            "https://api.dandanplay.net",
            // 社区镜像 / 聚合站
            "https://vercel.junce.dpdns.org",
            "https://danmu.junce.dpdns.org",
            "https://startling-pothos-0ee7cd.netlify.app",
            "https://api.danmu.icu",
            "https://fc.lyz05.cn",
            "https://dmku.hls.one",
    };

    /**
     * 内置运行时模块提供的弹幕服务（TV：node 模块里的 danmu_api）。
     *
     * <p><b>为什么走注入而不是读文件</b>：TV 壳在**主进程**里起网关
     * （ApiServer 就在这个进程），而 node 在 `:node` 进程 —— 两者静态字段不共享。
     * 模块是否安装由壳用 PackageManager 判断（跨进程可行），判断完把地址注入进来。
     * PC/Docker 没人注入，等于不生效。
     *
     * <p>每次请求都问一次（不缓存）：端口是探出来的，模块重启可能换端口，
     * 缓存住就会"模块在跑、弹幕不出来"。
     *
     * <p>兼容旧路径：壳没装 hook 时读 {@code meow.danmaku.bundled} 系统属性。
     */
    private static String bundledDanmakuHost() {
        try {
            String v = platform.Bridge.danmakuHost();
            if (v != null && !v.trim().isEmpty()) return v.trim();
        } catch (Throwable ignored) {
        }
        try {
            String v = System.getProperty("meow.danmaku.bundled", "");
            return v == null ? "" : v.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 这个 host 是不是本机回环（本机服务的失败不该进 5 分钟冷却）。 */
    private static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        int i = h.indexOf("://");
        if (i >= 0) h = h.substring(i + 3);
        return h.startsWith("127.0.0.1") || h.startsWith("localhost") || h.startsWith("[::1]");
    }

    /** config.sites.json 里的 `danmaku` 字段（TVBox / Fongmi 生态约定）。
     *
     * <p><b>为什么要有它</b>（2026-11-06 实测）：上游配置普遍带这个字段，形如
     * `http://127.0.0.1:2525/danmu?name={name}&epid={episode}`（指向用户自建的
     * danmu_api）。Fongmi 的 `DanmakuSetting.getEffectiveApiUrl()` 就是
     * 「用户设置 → 配置的 danmaku 字段」，而我们**以前完全没读这个字段** ——
     * 导入配置后弹幕地址被直接丢掉，表现就是"Fongmi 有弹幕、我们没有"。
     *
     * <p>读文件是有成本的（config.sites.json 几百 KB），所以缓存 30 秒；
     * 导入配置会重写该文件，到期自然生效。
     */
    private static volatile String configDanmakuUrl = "";
    private static volatile long configDanmakuAt = 0L;

    private static String configDanmakuHost() {
        long now = System.currentTimeMillis();
        if (now - configDanmakuAt < 30_000L) return configDanmakuUrl;
        String v = "";
        try {
            v = loadConfigSites().optString("danmaku", "").trim();
        } catch (Throwable ignored) {
        }
        configDanmakuUrl = v;
        configDanmakuAt = now;
        return v;
    }

    /** 这个 host 是不是"带占位符的模板"（Fongmi 的 getCall 语义）。 */
    private static boolean isDanmakuTemplate(String host) {
        return host != null && (host.contains("{name}") || host.contains("{episode}"));
    }

    /**
     * 聚合站健康记忆：host → 下次可重试的时间戳。
     *
     * <p>为什么要它：这些站全是境外域名，出口不通时**每个都要等满超时**。
     * 实测一集查无弹幕时整条链要 36.9 秒，期间播放器一直显示"加载中"。
     * 记住失败、冷却期内直接跳过，最坏耗时从 37 秒降到秒级。
     *
     * <p>冷却 5 分钟而不是永久：网络可能恢复、站点可能复活，
     * 永久拉黑会让用户重启后才恢复。冷却到期自然重试一次。
     */
    private static final Map<String, Long> DANMAKU_HOST_DOWN = new ConcurrentHashMap<>();
    private static final long DANMAKU_HOST_COOLDOWN_MS = 5 * 60_000L;

    /** 该 host 是否在冷却期（刚失败过，别再等它的超时） */
    private static boolean danmakuHostDown(String host) {
        Long until = DANMAKU_HOST_DOWN.get(host);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            DANMAKU_HOST_DOWN.remove(host);   // 冷却到期，放它再试一次
            return false;
        }
        return true;
    }

    /** 记一次失败；成功则清除记录。 */
    private static void markDanmakuHost(String host, boolean ok) {
        if (host == null || host.isEmpty()) return;
        // 本机服务（node 模块）冷启动时端口还没听，失败是暂时的，
        // 进 5 分钟冷却会让"开机先播一集"永远拿不到弹幕
        if (isLoopbackHost(host)) return;
        if (ok) DANMAKU_HOST_DOWN.remove(host);
        else DANMAKU_HOST_DOWN.put(host, System.currentTimeMillis() + DANMAKU_HOST_COOLDOWN_MS);
    }

    /**
     * 实际使用的弹幕源列表：用户配置的排在最前。
     *
     * 公共弹幕站经常整体挂掉（额度耗尽 / 部署停用 / CF 拦截），
     * 写死在代码里就只能等发版，所以允许在设置里填自己的源。
     * 配置格式：host 或完整 URL，含 {name} / {episode} 占位符则直接替换，
     * 否则按 Fongmi 协议拼 /api/v2/fongmi/danmaku。
     */
    /**
     * 弹幕聚合服务地址列表（按优先级）。
     *
     * <p>来源三处，前面的优先：
     * <ol>
     *   <li>{@code MEOW_DANMAKU_API} 环境变量（Docker compose / PC sidecar 注入，
     *       支持逗号或换行分隔多个）</li>
     *   <li>{@code policy.json} 的 {@code danmakuApi} 字段（用户在设置页填的）</li>
     *   <li>内置 {@link #DANMAKU_HOSTS} 兜底</li>
     * </ol>
     *
     * <p>环境变量排最前，是因为容器部署时它由 compose 注入，比卷里的文件更"当前"。
     */
    private static String[] danmakuHosts() {
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        // 1) 环境变量
        String env = System.getenv("MEOW_DANMAKU_API");
        if (env != null && !env.trim().isEmpty()) {
            for (String line : env.split("[\\r\\n,]+")) {
                String s = line.trim();
                if (!s.isEmpty() && seen.add(s)) out.add(s);
            }
        }

        // 2) policy.json
        try {
            String custom = loadPolicy().optString("danmakuApi", "").trim();
            if (!custom.isEmpty()) {
                for (String line : custom.split("[\\r\\n,]+")) {
                    String s = line.trim();
                    if (!s.isEmpty() && seen.add(s)) out.add(s);
                }
            }
        } catch (Throwable ignored) {
        }

        /*
         * 2.2) 本机运行时模块自带的 danmu_api（TV 装了 node 模块才有）。
         *
         * 排在用户设置之后、配置字段之前：用户显式配过就听用户的；
         * 没配过时，本机服务比境外镜像快几个数量级（回环 vs 跨境）。
         */
        String bundled = bundledDanmakuHost();
        if (!bundled.isEmpty() && seen.add(bundled)) out.add(bundled);

        /*
         * 2.5) 导入配置里的 danmaku 字段。
         *
         * 优先级语义与 Fongmi 对齐：用户设置（policy / 环境变量）> 配置里的 danmaku >
         * 内置镜像。用户显式设过就不被配置覆盖。
         */
        String fromConfig = configDanmakuHost();
        if (!fromConfig.isEmpty()) {
            for (String line : fromConfig.split("[\r\n,]+")) {
                String s = line.trim();
                if (!s.isEmpty() && seen.add(s)) out.add(s);
            }
        }

        // 3) 内置兜底（跳过冷却中的站——它们刚失败过，再等超时只是拖慢响应）
        for (String h : DANMAKU_HOSTS) {
            if (seen.add(h) && !danmakuHostDown(h)) out.add(h);
        }
        return out.toArray(new String[0]);
    }

    /** 拼弹幕搜索地址：支持含占位符的自定义 URL */
    private static String danmakuUrl(String host, String name, String episode, String format) {
        if (host.contains("{name}") || host.contains("{episode}")) {
            return host.replace("{name}", name).replace("{episode}", episode);
        }
        return host + "/api/v2/fongmi/danmaku?name=" + name
                + "&episode=" + episode + "&format=" + format;
    }

    /**
     * 两段式第一段（TV / Fongmi 用）。
     *
     * <p>Fongmi 的 {@code DanmakuApi.search()} 期望这个端点返回 <b>候选列表 JSON</b>：
     * <pre>
     * GET /api/proxy/danmaku/list?name=我不是大师&amp;episode=1
     * → [ {"name":"我不是大师 第1集","url":"http://host/api/proxy/danmaku/xml?id=iqiyi:...", ...} ]
     * </pre>
     * 拿到列表后 Fongmi 自己再 GET 那个 url 拿 XML。
     * 在 config.json 里把 {@code danmaku} 指向本端点即可让 TV 用上我们的弹幕。
     */
    private static void handleDanmakuList(HttpExchange ex) throws Exception {
        Map<String, String> q = query(ex);
        String name = q.getOrDefault("name", "").trim();
        String episode = q.getOrDefault("episode", "1");
        if (name.isEmpty()) {
            sendJson(ex, 200, "[]");
            return;
        }
        String clean = cleanName(name);
        int ep = parseEpisodeFromName(episode);
        if (ep == 9999) {
            try {
                ep = parseEpisodeNo(episode);
            } catch (Exception ignored) {
                ep = 1;
            }
        }

        // 自动抓取路径：优先直连，拿到 XML 后包装成一条候选，让 Fongmi 走第二段
        String xml = fetchDanmakuAuto(clean, ep);
        JSONArray arr = new JSONArray();
        if (xml != null && xml.contains("<d ")) {
            arr.put(new JSONObject()
                    .put("name", clean + " 第" + ep + "集")
                    .put("url", baseUrlFor(ex) + "/api/proxy/danmaku?name="
                            + URLEncoder.encode(clean, "UTF-8") + "&episode=" + ep));
        }
        sendJson(ex, 200, arr.toString());
    }

    /**
     * 两段式第二段：按 id 取 XML。
     *
     * <pre>
     * GET /api/proxy/danmaku/xml?id=iqiyi:4255991179147000:1
     * GET /api/proxy/danmaku/xml?id=tencent:mzc00200j47k5l3:1
     * </pre>
     * id 格式 {@code 源:资源ID:集数}，由我们自己的 {@code /list} 或 {@code /search} 生成，
     * 不依赖上游临时地址（会失效）。
     */
    /**
     * 弹幕预取：片尾前把下一集的弹幕先抓好（2026-11-02 用户要求）。
     *
     * <pre>
     * POST /api/proxy/danmaku/prefetch?name=小城良方&episode=2
     * → {"ok":true,"queued":true}   立刻返回，抓取在后台线程
     * → {"ok":true,"cached":true}   已经在缓存里，什么都不用做
     * </pre>
     *
     * <p><b>为什么立刻返回</b>：预取是"顺手做的事"，前端在片尾前 2–3 分钟调，
     * 那时用户还在看片。让它阻塞 10 秒毫无意义，失败也不影响任何东西。
     *
     * <p><b>为什么只走内置源，不碰 danmu_api</b>：
     * danmu_api 默认限流 <b>3 次/分钟</b>（`RATE_LIMIT_MAX_REQUESTS`）。
     * 一次播放会预取 1 次，若连看 3 集就把额度吃光，用户真正要播的那集
     * 反而拿到 429 —— 预取反而害了主路径。
     * 内置源（爱奇艺 / 360→腾讯）没有限流，1–3 秒就能拿到，
     * 这才是值得预取的部分；danmu_api 的补全仍由 {@link #fetchDanmakuAuto0}
     * 的后台线程慢慢跑，不占预取预算。
     *
     * <p><b>幂等</b>：已有缓存直接返回 cached，不重复抓。
     * 同一集并发预取也只会有一次真正执行（靠 {@link #DANMAKU_INFLIGHT}）。
     */
    /**
     * 弹幕预取：片尾前把下一集的弹幕先抓好（2026-11-02 用户要求）。
     *
     * <pre>
     * POST /api/proxy/danmaku/prefetch?name=小城良方&episode=2
     * → {"ok":true,"queued":true}   立刻返回，抓取在后台线程
     * → {"ok":true,"cached":true}   已经在缓存里，什么都不用做
     * → {"ok":true,"disabled":true} 用户关掉了预取
     * </pre>
     *
     * <p><b>为什么立刻返回</b>：预取是"顺手做"，前端在片尾前 N 分钟调，
     * 那时用户还在看片。让它阻塞 10 秒毫无意义，失败也不影响任何东西。
     *
     * <p><b>★ 预取**优先走用户配置的降级链**（含 danmu_api）</b>
     * （2026-11-02 用户指正：「已经提前错开 3 分钟了，足够」）。
     *
     * <p>之前的实现为了躲 danmu_api 的限流（`RATE_LIMIT_MAX_REQUESTS` 默认
     * 3 次/分钟）只走内置源。但那个担心是**错估了时间尺度**：
     * 预取发生在片尾前 3 分钟，而限流窗口是 1 分钟 —— 一次播放只预取 1 集，
     * 三分钟内最多 1 次预取 + 1 次主请求，根本打不满 3 次额度。
     * 而且预取拿到的就是"更全的那份"，切集时直接命中，比"先给内置再补全"
     * 体验更好（用户不会看到弹幕从少变多）。
     *
     * <p>所以现在：预取和主请求走**同一套 {@link #fetchDanmakuAuto0}**，
     * 只是不阻塞、不返回内容。它自带并发抢占，所以即使 danmu_api 慢，
     * 内置源的结果也会先落进缓存。
     *
     * <p><b>幂等</b>：已有缓存直接返回 cached。同一集并发预取只会有一次
     * 真正执行（靠 {@link #DANMAKU_INFLIGHT}）。
     */
    private static void handleDanmakuPrefetch(HttpExchange ex) throws Exception {
        Map<String, String> q = query(ex);
        String name = q.getOrDefault("name", "").trim();
        String episode = q.getOrDefault("episode", "2");
        if (name.isEmpty()) {
            sendJson(ex, 200, "{\"ok\":false,\"msg\":\"name required\"}");
            return;
        }
        // 设置页可关（policy.json 的 danmakuPrefetch，默认开）
        if (!isDanmakuPrefetchEnabled()) {
            sendJson(ex, 200, "{\"ok\":true,\"disabled\":true}");
            return;
        }
        final String clean = cleanName(name);
        int epNo;
        try {
            epNo = parseEpisodeNo(episode);
        } catch (Exception e) {
            epNo = 2;
        }
        final int ep = epNo;
        final String cacheKey = "dm|" + clean + "|" + ep;

        // 已有缓存（内存或磁盘）→ 无事可做。
        // ★ 磁盘那半用只读探测：预取自己的"查有没有"不能篡改 activeKey/LRU
        if (DANMAKU_CACHE.get(cacheKey) != null || danmakuOnDiskUnread(cacheKey)) {
            sendJson(ex, 200, "{\"ok\":true,\"cached\":true,\"episode\":" + ep + "}");
            return;
        }

        sendJson(ex, 200, "{\"ok\":true,\"queued\":true,\"episode\":" + ep + "}");

        /*
         * 后台走完整降级链（与主请求同一条路径）。
         *
         * 直接调 fetchDanmakuAuto 而不是自己拼源：它已经有
         * 「并发抢占 + 后台补全 + 1.2 倍覆盖门槛」，重复实现只会让两边行为漂移。
         * 预取的价值就是"把主请求要做的活提前做完"，所以必须同一条路。
         */
        Thread t = new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                String r = fetchDanmakuAuto(clean, ep);
                System.err.println("[danmaku] prefetch " + clean + " ep" + ep
                        + (r == null || r.isEmpty()
                            ? " 无结果"
                            : " ← " + countTags(r) + " 条")
                        + " (" + (System.currentTimeMillis() - t0) + "ms)");
            } catch (Throwable ignored) {
                // 预取失败不影响任何东西
            }
        }, "danmaku-prefetch-" + clean);
        t.setDaemon(true);
        t.start();
    }

    /**
     * 预取开关（policy.json 的 {@code danmakuPrefetch}，默认 **true**）。
     *
     * <p>为什么默认开：预取是纯优化，失败无副作用；关掉只是省一点点流量，
     * 而用户会在"切集后弹幕要等 1–3 秒"上明显感知到差别。
     */
    private static boolean isDanmakuPrefetchEnabled() {
        try {
            if (!core.DataPaths.exists("policy.json")) return true;
            String txt = core.DataPaths.read("policy.json");
            if (txt == null) return true;
            return new JSONObject(txt).optBoolean("danmakuPrefetch", true);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 预取提前量（秒），policy.json 的 {@code danmakuPrefetchLead}，默认 180。
     *
     * <p>为什么默认 180：danmu_api 首次抓一集实测 7–12 秒，内置源 1–3 秒。
     * 3 分钟给足了余量（连最慢的 danmu_api 都抓得完），又不会太早 ——
     * 太早会让"用户只是随便拖到片尾看看"也触发预取，浪费一次上游请求。
     *
     * <p>允许用户调小（比如 60 秒）：网络快、只用内置源时没必要等 3 分钟。
     * 下限 10 秒，避免"一进片尾就预取"变成事实上的立即抓取。
     */
    private static int danmakuPrefetchLeadSec() {
        try {
            if (!core.DataPaths.exists("policy.json")) return 180;
            String txt = core.DataPaths.read("policy.json");
            if (txt == null) return 180;
            int v = new JSONObject(txt).optInt("danmakuPrefetchLead", 180);
            if (v < 10) v = 10;
            if (v > 3600) v = 3600;
            return v;
        } catch (Exception e) {
            return 180;
        }
    }

    private static void handleDanmakuXml(HttpExchange ex) throws Exception {
        Map<String, String> q = query(ex);
        String id = q.getOrDefault("id", "");
        String xml = id.isEmpty() ? "" : loadDanmakuById(id);
        // 空 id / 未知源也要返回合法 XML，否则客户端会把空 body 当成网络错误
        if (xml == null || xml.trim().isEmpty()) xml = "<?xml version=\"1.0\"?><i></i>";
        sendText(ex, 200, xml, "text/xml; charset=utf-8");
    }

    /**
     * 从自家弹幕 url 里取出 id；不是自家路径返回 null。
     *
     * <p>只认 {@code /api/proxy/danmaku/xml?id=} 这一种形态。允许带协议和主机
     * （前端或 TV 可能传绝对地址），也允许带别的查询参数。
     */
    private static String extractSelfDanmakuId(String url) {
        if (url == null) return null;
        int p = url.indexOf("/api/proxy/danmaku/xml");
        if (p < 0) return null;
        int qm = url.indexOf('?', p);
        if (qm < 0) return null;
        String qs = url.substring(qm + 1);
        for (String kv : qs.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            if ("id".equals(kv.substring(0, eq))) {
                try {
                    return URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return kv.substring(eq + 1);
                }
            }
        }
        return null;
    }

    /**
     * 按 id 取弹幕 XML。{@code 源:资源ID:集数}。
     *
     * <p><b>爱奇艺必须传时长，不能传集数</b>：Bullet 分片是按 300 秒切的，
     * 分片数 = ceil(时长/300)。早先这里把集数（1）当成分片依据，导致
     * 只下第 1 个分片 → 只能拿到前 5 分钟，42 分钟的剧只有 1/8 的弹幕。
     * 现在通过 {@link #iqiyiDurationOf} 反查该集的真实时长。
     *
     * <p><b>腾讯的第三段有两种含义</b>（2026-11-02 加 360 之后）：
     * 来自 {@link #searchTencent} 的候选第二段是 <b>cid</b>（专辑 id，需过
     * float_vinfo2 转 vid）；来自 {@link #search360} 的候选第二段**已经是 vid**
     * （360 的 playlink URL 里直接带 {@code j4102tpddr4} 这种）。
     * 两者靠 id 前缀区分：vid 形如 {@code [a-z]\d{6,}}，cid 是纯数字。
     * 判错会拿到空 XML（vid 当 cid 请求 vinfo 返回 404 的那种静默失败）。
     */
    private static String loadDanmakuById(String id) {
        String[] parts = id.split(":");
        String src = parts.length > 0 ? parts[0] : "";
        if ("iqiyi".equals(src) && parts.length >= 3) {
            String tvId = parts[1];
            int ep = parseIntSafe(parts[2], 1);
            return fetchIqiyiBulletXml(tvId, iqiyiDurationOf(tvId, ep));
        }
        if ("tencent".equals(src) && parts.length >= 3) {
            String key = parts[1];
            int ep = parseIntSafe(parts[2], 1);
            /*
             * 已经是 vid（360 给的）→ 直接取；
             * 是 cid（腾讯搜索给的纯数字）→ 先转 vid。
             */
            if (looksLikeTencentVid(key)) return fetchTencentXml(key, ep);
            return fetchTencentByCid(key, ep);
        }
        return "<?xml version=\"1.0\"?><i></i>";
    }

    /**
     * 判断一个腾讯 id 是 vid 还是 cid。
     *
     * <p>vid 的形态是「一个小写字母 + 若干数字」，如 {@code j4102tpddr4}、
     * {@code k41020ir430}（也可能夹字母）；cid 是纯数字（13 位左右）。
     * 判据取「含字母」——纯数字一定是 cid，含字母一定不是 cid。
     */
    private static boolean looksLikeTencentVid(String s) {
        if (s == null || s.isEmpty()) return false;
        if (!s.matches("[A-Za-z0-9]{6,20}")) return false;
        return s.matches(".*[A-Za-z].*");
    }

    /**
     * 反查某集的真实时长（秒），用于算分片数。
     *
     * <p>正常情况下搜索阶段已经写进 {@link #IQIYI_DURATION}，这里只是兜底
     * （比如进程重启后缓存空了，用户直接从历史记录点进来）。
     *
     * <p>兜底手段：拿 tvId 去搜索接口反查。搜索是按片名查的，我们没有片名，
     * 所以换一条路——直接请求 Bullet 的第 2 个分片探活：
     * 能拿到内容说明时长 &gt; 300s，继续往后探；拿不到就停。
     * 这样最多多花几个 HEAD 请求，但不需要额外的 API 和签名。
     */
    private static int iqiyiDurationOf(String tvId, int ep) {
        Integer cached = IQIYI_DURATION.get(tvId);
        if (cached != null && cached > 0) return cached;
        // 探活：从第 2 片开始试，找到第一个不存在的分片
        int frag = 2;
        while (frag <= 30) {   // 30 片 = 2.5 小时，足够任何正片
            String u = iqiyiBulletUrl(tvId, frag);
            String probe = fetchBulletProbe(u);
            if (probe == null) break;
            frag++;
        }
        int seconds = (frag - 1) * 300;
        if (seconds <= 0) seconds = 45 * 60;   // 探测全失败，保守默认 45 分钟
        IQIYI_DURATION.put(tvId, seconds);
        return seconds;
    }

    /** 只取响应体前几十字节判断分片是否存在（不下载整片）。 */
    private static String fetchBulletProbe(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4_000);
            c.setReadTimeout(4_000);
            c.setRequestProperty("Referer", IQIYI_REFERER);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36");
            int code = c.getResponseCode();
            if (code != 200) {
                c.disconnect();
                return null;
            }
            // 分片是 zlib 压缩的，读一小段就够判断非空
            java.io.InputStream in = c.getInputStream();
            byte[] buf = new byte[64];
            int n = in.read(buf);
            in.close();
            c.disconnect();
            return n > 0 ? "ok" : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 自动抓取的统一入口（多源按优先级，带缓存）。
     *
     * <p>优先级见 {@link #DANMAKU_PRIORITY}：iqiyi → tencent → danmu_api → 肥猫联盟。
     * 前两个是官方直连（实测可用），后两个是聚合形态（覆盖面广但依赖第三方）。
     * 命中缓存直接返回，避免重复 14 秒下载。
     */
    private static String fetchDanmakuAuto(String clean, int ep) {
        return fetchDanmakuAuto(clean, ep, null);
    }

    /**
     * 同 {@link #fetchDanmakuAuto(String, int)}，但把「哪个源命中」写进 {@code sourceOut[0]}。
     *
     * <p>为什么要回传来源：用户看不出这一集弹幕来自爱奇艺直连还是第三方聚合站，
     * 而两者质量差别很大（直连是平台原生的，聚合站可能缺集或串集）。
     * 播放器右上角显示来源，用户才能判断"这集弹幕少是源的问题还是本来就没弹幕"。
     *
     * <p>{@code sourceOut[0]} 的取值是 {@code iqiyi/tencent/danmu_api/aggregator}，
     * 空结果时写 {@code none}（与"没调用"区分开，后者保持 null）。
     */
    /**
     * 正在抓取的 key 集合（并发去重）。
     *
     * <p><b>为什么需要</b>：弹幕一次要打 3–5 个上游（iqiyi / danmu_api / 聚合站），
     * 慢的时候十几秒。这期间播放器很容易再发一次同样的请求 ——
     * seek 后重载清单、切回前台、用户手动点「重试」、前端空结果重试，
     * 任何一个都会让同一集被并发抓 N 遍，把上游打限流、也拖慢所有请求。
     *
     * <p>行为：后到的请求**短暂等待**先到的那个完成（最多 20 秒），
     * 然后直接读它写好的缓存。等待超时/先到者异常 → 自己抓一遍，不会卡死。
     */
    private static final java.util.Set<String> DANMAKU_INFLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static String fetchDanmakuAuto(String clean, int ep, String[] sourceOut) {
        return fetchDanmakuAuto(clean, ep, sourceOut, false);
    }

    /**
     * fresh=true：跳过内存/磁盘缓存与并发等待，强制向上游再抓一次。
     *
     * <p>为什么需要：客户端 12 秒后台补全检查如果只绕 IndexedDB，
     * 拿到的仍是后端 30 分钟缓存里的同一份，“后台补全”永远不会生效。
     * 这个参数让那次检查真正重抓；结果仍写回同一 cacheKey，后续请求直接受益。
     * 每集最多一次（客户端 dmUpgradeChecked 保证），不会放大上游压力。
     */
    private static String fetchDanmakuAuto(
            String clean, int ep, String[] sourceOut, boolean fresh) {
        String cacheKey = "dm|" + clean + "|" + ep;
        if (!fresh) {
            String cached = DANMAKU_CACHE.get(cacheKey);
            if (cached != null) {
                /*
                 * ★ 内存命中也要补一次落盘。
                 *
                 * 踩过（2026-10-30 实测）：内存 TTL 是 30 分钟、磁盘是 30 天，
                 * 但原来内存命中就直接 return —— 于是"先在线看过这一集、再点缓存"
                 * 的路径永远不会写盘（那份内容只活在内存里）。用户以为缓存好了，
                 * 断网后弹幕是空的，而日志里预取明明"成功 15280 条"。
                 *
                 * 写盘是幂等的（同 key 覆盖同一文件），重复写一次只多几 KB IO，
                 * 比"看起来成功了其实没存"好得多。空串是负缓存，不写。
                 */
                if (!cached.isEmpty()) danmakuDiskPut(cacheKey, cached);
                // 缓存里成功的结果自带来源标记（见下面的写入逻辑）；失败项无标记
                if (sourceOut != null) {
                    sourceOut[0] = cached.isEmpty() ? "none" : cachedSource(cacheKey);
                }
                return cached;
            }
            // 内存未命中 → 查磁盘。断网时上游全失败，这一步是唯一还能出弹幕的来源。
            String onDisk = danmakuDiskGet(cacheKey);
            if (onDisk != null) {
                // 回填内存，避免同一集反复读盘
                DANMAKU_CACHE.put(cacheKey, onDisk, 30 * 60_000L);
                DANMAKU_SRC_CACHE.put(cacheKey, "disk", 30 * 60_000L);
                if (sourceOut != null) sourceOut[0] = "disk";
                return onDisk;
            }

            /*
             * 并发去重：同一集已经有人在抓就等它，不要重复打上游。
             * 等不到（超时/对方异常）就自己抓，宁可多打一次也不能卡住。
             */
            if (!DANMAKU_INFLIGHT.add(cacheKey)) {
                for (int i = 0; i < 40; i++) {          // 最多等 20 秒
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    String now = DANMAKU_CACHE.get(cacheKey);
                    if (now != null) {
                        if (sourceOut != null) {
                            sourceOut[0] = now.isEmpty() ? "none" : cachedSource(cacheKey);
                        }
                        return now;
                    }
                    if (!DANMAKU_INFLIGHT.contains(cacheKey)) break;   // 对方已结束但没写缓存
                }
            }
        }
        try {
            return fetchDanmakuAuto0(clean, ep, cacheKey, sourceOut);
        } finally {
            // fresh 没有登记 inflight，不能误删别的请求的标记
            if (!fresh) DANMAKU_INFLIGHT.remove(cacheKey);
        }
    }

    /**
     * 真正去各上游抓。抽出来是为了让 {@link #fetchDanmakuAuto} 能用
     * try/finally 保证 INFLIGHT 一定被清理（异常路径也不留死锁）。
     */
    /**
     * 自动抓取的降级链（顺序可由 policy.json 的 {@code danmakuOrder} 配置）。
     *
     * <p><b>为什么顺序要可配</b>（2026-11-02 用户要求）：
     * 内置直连（爱奇艺/腾讯）拿的是**官方原始弹幕**，但只覆盖两个平台；
     * 用户自建的 danmu_api 覆盖 20+ 平台、且对同一部剧常常有更全的分集与
     * 更准的标题匹配。谁先谁后取决于用户装了哪个——写死任何一方都不对。
     *
     * <p>可选值（逗号分隔，前面的先试）：
     * <pre>
     *   aggregator  自建 danmu_api / 聚合站（policy.json 的 danmakuApi + 内置镜像）
     *   iqiyi       爱奇艺官方直连
     *   tencent     腾讯官方直连（经 360 定位 vid）
     * </pre>
     * 缺省为 {@code iqiyi,tencent,aggregator}（保持旧行为）。
     * 用户在设置页配了 danmu_api 时，前端会把默认顺序改成
     * {@code aggregator,iqiyi,tencent}——这正是"自建更准就先用它"。
     *
     * <p>未列出的源**不参与**（比如只写 {@code aggregator} 就是"只用自建"）。
     * 写错的项直接跳过，不会让整条链失败。
     */
    /**
     * 并发抢先 + 后台补全（2026-11-02 用户要求「B 方案」）。
     *
     * <p><b>为什么不再按顺序串行</b>：自建 danmu_api 首次抓一集要
     * 7–10 秒（它要现抓多个上游并合并；实测 SOURCE_ORDER 有 8 个源、
     * 同平台串行且每个间隔 1 秒规避风控）。串行意味着"配了 API 优先就得
     * 先干等 7 秒"，而内置直连 1 秒就能出结果。
     *
     * <p><b>现在的行为</b>：
     * <ol>
     *   <li>把 {@code danmakuOrder()} 里所有源**同时发出去**（各占一个线程）</li>
     *   <li>**先到先用**：第一个非空结果立刻返回（一般是 iqiyi/tencent，1–3 秒）</li>
     *   <li>剩下的继续在后台跑；跑完若**比当前结果明显更全**（≥1.2 倍条数），
     *       写进缓存。下一次请求（seek / 切回 / 重进）就用上更全的那份</li>
     * </ol>
     *
     * <p><b>为什么不"返回后再替换"</b>：HTTP 是一问一答，响应已经发出去了
     * 就没法改。要"边播边换"得用 SSE/WebSocket，代价远大于收益
     * （弹幕会突然变多、位置跳动，用户观感更差）。所以做成
     * 「这次快，下次全」——用户实际感知是"点开就有弹幕"，不是"等了 7 秒"。
     *
     * <p><b>为什么要求 1.2 倍</b>：避免"7978 条 → 8100 条"这种无意义替换，
     * 那只会让弹幕位置抖动，而用户看不出区别。
     */
    /**
     * 并发抢先 + 后台补全（2026-11-02 用户要求「B 方案」）。
     *
     * <p><b>为什么不再按顺序串行</b>：自建 danmu_api 首次抓一集要 7–10 秒
     * （它要现抓多个上游并合并；实测 SOURCE_ORDER 有 8 个源、同平台串行
     * 且每个间隔 1 秒规避风控）。串行意味着"配了 API 优先就得先干等 7 秒"，
     * 而内置直连 1 秒就能出结果。
     *
     * <p><b>现在的行为</b>：
     * <ol>
     *   <li>{@code danmakuOrder()} 里所有源**同时发出去**（各占一个线程）</li>
     *   <li>**先到先用**：第一个非空结果立刻返回（一般是 iqiyi/tencent，1–3 秒）</li>
     *   <li>剩下的继续跑；跑完若**明显更全**（≥1.2 倍条数）就写进缓存，
     *       下一次请求（seek / 切回 / 重进）自动用上更全的那份</li>
     * </ol>
     *
     * <p><b>为什么不"边播边换"</b>：HTTP 一问一答，响应发出后改不了。
     * 真要边播边换得上 SSE/WebSocket，代价远大于收益 —— 弹幕会突然变多、
     * 位置跳动，观感比"慢"更糟。做成「这次快，下次全」，用户感知是
     * "点开就有弹幕"，而不是"等了 7 秒"。
     *
     * <p><b>首屏最多等 6 秒</b>：所有源都超过 6 秒还没结果时返回空，
     * 由调用方走负缓存 + 前端重试。6 秒是权衡：内置直连通常 1–3 秒，
     * 留一倍余量；再长用户已经在看转圈了，不如先给个空让界面别卡。
     */
    private static String fetchDanmakuAuto0(
            String clean, int ep, String cacheKey, String[] sourceOut) {
        String[] order = danmakuOrder();
        // 单源不必起线程：少一层调度，也让日志更直白
        if (order.length == 1) {
            String only = order[0];
            String r = runDanmakuSource(only, clean, ep);
            return storeDanmaku(cacheKey, r, sourceTagOf(only), sourceOut);
        }

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(order.length);
        List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
        List<String> srcNames = new ArrayList<>();
        for (String src : order) {
            srcNames.add(src);
            futures.add(pool.submit(() -> runDanmakuSource(src, clean, ep)));
        }

        /*
         * 按「谁先完成」收，不是按提交顺序。
         *
         * ★ 不能用 `for (f : futures) f.get()` —— 那是**按数组顺序阻塞**：
         *   第一个源（聚合站）慢就白等，等于没并发。必须轮询 isDone()。
         *   轮询间隔 50ms，对 1–10 秒量级的任务完全够（多花的那点 CPU
         *   远小于一次网络往返）。
         */
        String firstResult = "";
        String firstSrc = "";
        List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) pending.add(i);
        long deadline = System.currentTimeMillis() + danmakuFirstScreenMs(order);
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            boolean anyDone = false;
            for (int k = pending.size() - 1; k >= 0; k--) {
                int idx = pending.get(k);
                if (!futures.get(idx).isDone()) continue;
                pending.remove(k);
                anyDone = true;
                String r = null;
                try {
                    r = futures.get(idx).get(0, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                }
                if (r != null && !r.isEmpty() && firstResult.isEmpty()) {
                    firstResult = r;
                    firstSrc = srcNames.get(idx);
                }
            }
            if (!firstResult.isEmpty()) break;   // 先到先用，剩下的交给后台
            if (!anyDone) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        String out = storeDanmaku(cacheKey, firstResult, sourceTagOf(firstSrc), sourceOut);

        /*
         * 后台补全：没完成的源继续跑，只在"明显更全"时覆盖缓存。
         *
         * 用 daemon 线程：JVM 退出时不该被它拖住。线程池不 shutdown ——
         * 后台线程还要从里面取 future；任务跑完后池自然空闲，由 GC 回收
         * （每个请求一个池，数量受用户播放行为限制，不会无限增长）。
         */
        if (!pending.isEmpty()) {
            final List<Integer> rest = new ArrayList<>(pending);
            final List<java.util.concurrent.Future<String>> fs = futures;
            final List<String> names = srcNames;
            // lambda 只能捕获 effectively-final，所以复制一份
            final String baseResult = firstResult;
            Thread t = new Thread(() -> {
                for (int idx : rest) {
                    try {
                        String r = fs.get(idx).get(30, java.util.concurrent.TimeUnit.SECONDS);
                        if (r == null || r.isEmpty()) continue;
                        tellLaterIfBetter(clean, ep, names.get(idx), r, baseResult);
                    } catch (Throwable ignored) {
                        // 单个源超时/异常不影响其它源
                    }
                }
            }, "danmaku-bg-" + clean);
            t.setDaemon(true);
            t.start();
        } else {
            pool.shutdown();
        }
        return out;
    }

    /**
     * 后台补全写入：只有「明显更全」才覆盖缓存。
     *
     * <p>判据用条数比（≥1.2 倍）而不是"非空就覆盖"：
     * 内置直连与 danmu_api 对同一集常有几百条差异（不同源漏几集/多几段），
     * 为这点差异换一次缓存会让用户下次看到的位置对不上。
     *
     * @return 被采纳时返回新内容，否则 null
     */
    private static String tellLaterIfBetter(
            String clean, int ep, String src, String candidate, String current) {
        int cn = countTags(candidate);
        int cur = countTags(current);
        if (cn <= cur) return null;
        if (cur > 0 && cn < (int) (cur * 1.2)) return null;
        String cacheKey = "dm|" + clean + "|" + ep;
        DANMAKU_CACHE.put(cacheKey, candidate, 30 * 60_000L);
        DANMAKU_SRC_CACHE.put(cacheKey, sourceTagOf(src), 30 * 60_000L);
        danmakuDiskPut(cacheKey, candidate);
        System.err.println("[danmaku] bg-upgrade " + clean + " ep" + ep
                + " " + src + ": " + cur + " -> " + cn);
        return candidate;
    }

    /** 执行单个源。异常一律吞成空串，让并发抢先不会因一个源炸掉整条链。 */
    private static String runDanmakuSource(String src, String clean, int ep) {
        try {
            if ("aggregator".equals(src)) return fetchDanmakuFromAggregators(clean, ep);
            if ("iqiyi".equals(src)) {
                String r = fetchIqiyiByTitle(clean, ep);
                if (r.isEmpty()) {
                    System.err.println("[danmaku] iqiyi returned empty for " + clean + " ep" + ep);
                } else {
                    System.err.println("[danmaku] iqiyi ok for " + clean + " ep" + ep
                            + " (" + r.length() + "B)");
                }
                return r;
            }
            if ("tencent".equals(src)) return fetchDanmakuFromTencent(clean, ep);
        } catch (Throwable t) {
            System.err.println("[danmaku] source " + src + " threw: " + t);
        }
        return "";
    }

    /** 源 key → 回传给前端的来源标记。 */
    private static String sourceTagOf(String src) {
        if ("aggregator".equals(src)) return "danmu_api";
        return src == null ? "" : src;
    }

    /** 写缓存 + 回填 sourceOut（原 fetchDanmakuAuto0 尾段的三行，抽出来复用）。 */
    private static String storeDanmaku(
            String cacheKey, String result, String hit, String[] sourceOut) {
        if (result != null && !result.isEmpty()) {
            DANMAKU_CACHE.put(cacheKey, result, 30 * 60_000L);
            DANMAKU_SRC_CACHE.put(cacheKey, hit == null || hit.isEmpty() ? "unknown" : hit,
                    30 * 60_000L);
            danmakuDiskPut(cacheKey, result);
        } else {
            DANMAKU_CACHE.put(cacheKey, "", 30_000L);
            result = "";
        }
        if (sourceOut != null) {
            sourceOut[0] = result.isEmpty() ? "none" : (hit == null || hit.isEmpty() ? "unknown" : hit);
        }
        return result;
    }

    /**
     * 自动抓取的源顺序（policy.json 的 {@code danmakuOrder}）。
     *
     * <p>解析规则见 {@link #fetchDanmakuAuto0} 的注释。去重、去空、丢掉未知项。
     * 解析失败或全为未知项时回退到默认顺序——**永远不返回空数组**，
     * 否则一次手误的配置会让"自动播放没有弹幕"变成静默故障。
     */
    private static String[] danmakuOrder() {
        final String[] DEFAULT = {"iqiyi", "tencent", "aggregator"};
        JSONObject policy;
        try {
            policy = loadPolicy();
        } catch (Throwable t) {
            return DEFAULT;
        }
        List<String> out = new ArrayList<>();
        /*
         * 两种写法都支持：
         *   "danmakuOrder": ["aggregator","iqiyi","tencent"]   ← 设置页写的
         *   "danmakuOrder": "aggregator,iqiyi,tencent"         ← 手改 policy.json
         * 数组优先（设置页走这条）。
         */
        org.json.JSONArray arr = policy.optJSONArray("danmakuOrder");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim().toLowerCase();
                if (s.isEmpty()) continue;
                if (!s.equals("aggregator") && !s.equals("iqiyi") && !s.equals("tencent")) continue;
                if (!out.contains(s)) out.add(s);
            }
        } else {
            String raw = policy.optString("danmakuOrder", "").trim();
            if (!raw.isEmpty()) {
                for (String part : raw.split("[,;\\s]+")) {
                    String s = part.trim().toLowerCase();
                    if (s.isEmpty()) continue;
                    if (!s.equals("aggregator") && !s.equals("iqiyi") && !s.equals("tencent")) continue;
                    if (!out.contains(s)) out.add(s);
                }
            }
        }
        String[] ret = out.isEmpty() ? DEFAULT : out.toArray(new String[0]);
        // 诊断：顺序直接决定"自动播放先问谁"，配错了排查起来很费劲
        // （实测踩过：policy 读到了但没生效，最后发现是网关进程没重启）。
        System.err.println("[danmaku] order = " + java.util.Arrays.toString(ret));
        return ret;
    }

    /**
     * 首屏等待预算：从所有源被提交起，最多等多久就先把结果回给播放器。
     *
     * <p><b>为什么不能写死 6 秒</b>（2026-11-02 实测）：各源耗时差一个数量级——
     * iqiyi 直连 ~0.2s（搜不到就秒回）、腾讯分片 3–5s、danmu_api 聚合
     * **11–14s**（它要串行查多个平台，再并发拉几十个分片）。
     *
     * <p><b>但给聚合 12 秒也不对</b>：用户点了播放，12 秒画面里一条弹幕都没有，
     * 体感比"少一些弹幕"差得多。折中：
     * <ul>
     *   <li>聚合<b>排第一</b> → 10s。用户明确选了"弹幕 API 优先"，就该等它；
     *       10s 是从实测 11–14s 里取的保守下界（多数命中在 9–11s）。</li>
     *   <li>聚合排后面 → 6s。直连最慢 5s，等得到；聚合慢慢在后台补。</li>
     *   <li>没有聚合（纯直连）→ 6s。</li>
     * </ul>
     */
    private static long danmakuFirstScreenMs(String[] order) {
        if (order.length > 0 && "aggregator".equals(order[0])) return 10_000L;
        return 6_000L;
    }

    /**
     * 腾讯自动抓取：先问 360 要这一集的精确 vid，拿不到再回退老的 cid 路。
     *
     * <p>走 360 而不是 {@link #searchTencent}：360 的 seriesPlaylinks 直接给出
     * 每集的 vid（如 {@code j4102tpddr4}），省掉 cid → float_vinfo2 → vid
     * 这一步（额外一次请求，且对未上线/超前点播的集经常换不到）。
     *
     * <p>受 {@link #isTencentAutoEnabled()} 控制：用户在设置页取消勾选
     * 「腾讯自动抓取」时，这一档整个跳过（手动搜索里仍然有腾讯候选）。
     */
    private static String fetchDanmakuFromTencent(String clean, int ep) {
        if (!isTencentAutoEnabled()) {
            System.err.println("[danmaku] tencent auto disabled by policy");
            return "";
        }
        try {
            String vid = tencentVidFrom360(clean, ep);
            if (vid != null && !vid.isEmpty()) {
                String s = fetchTencentXml(vid, ep);
                if (!s.isEmpty()) {
                    System.err.println("[danmaku] tencent(360) ok for " + clean + " ep" + ep
                            + " (" + s.length() + "B)");
                    return s;
                }
            }
        } catch (Throwable t) {
            System.err.println("[danmaku] tencent via 360 threw: " + t);
        }
        try {
            List<JSONObject> tx = searchTencent(clean);
            if (!tx.isEmpty()) {
                String cid = tx.get(0).optString("id", "").split(":")[1];
                String s = fetchTencentByCid(cid, ep);
                if (!s.isEmpty()) {
                    System.err.println("[danmaku] tencent ok (" + s.length() + "B)");
                    return s;
                }
                System.err.println("[danmaku] tencent empty for " + clean);
            }
        } catch (Throwable t) {
            System.err.println("[danmaku] tencent threw: " + t);
        }
        return "";
    }

    /**
     * 聚合站抓取（自建 danmu_api + 内置镜像），并发取最快成功的那个。
     *
     * <p>两个阶段：
     * <ol>
     *   <li>Fongmi 协议（{@code /api/v2/fongmi/danmaku} 先拿候选列表再取 XML）</li>
     *   <li>简单聚合接口兜底（直接返回 XML）</li>
     * </ol>
     */
    private static String fetchDanmakuFromAggregators(String clean, int ep) {
        String[] hosts = danmakuHosts();
        if (hosts.length == 0) {
            // 全部在冷却期：直接跳过这一档，别白等 9 秒预算
            System.err.println("[danmaku] all aggregator hosts in cooldown, skipped");
            return "";
        }
        /*
         * ★ 自建地址优先，且**所有**自建地址都参与（2026-11-09 修）。
         *
         * 旧实现只看 hosts[0]：用户配了多个自建镜像时，后面的会被静默忽略。
         * 现在把自建 host 全部放进池里并发试，先成功先用；只有没有任何自建
         * 地址时才用内置镜像（避免死镜像把整条链拖到 9 秒以上）。
         */
        List<String> customHosts = new ArrayList<>();
        for (String h : hosts) {
            if (isCustomDanmakuHost(h)) customHosts.add(h);
        }
        boolean isCustom = !customHosts.isEmpty();
        String[] pool = isCustom ? customHosts.toArray(new String[0]) : hosts;
        System.err.println("[danmaku] aggregator trying " + pool.length
                + (isCustom ? " (custom pool)" : " (builtin pool)") + ": "
                + java.util.Arrays.toString(pool));
        long t0 = System.currentTimeMillis();

        /*
         * ★ 只有一个 host 时**在当前线程直接跑**，绝不提交回 {@link #POOL}。
         *
         * 踩过（2026-11-02 实测，17 秒 miss）：`fetchDanmakuAuto0` 现在是并发抢占，
         * 它自己就跑在 POOL 的线程上、还占着 2~3 个槽。这里再往**同一个池**
         * 提交任务，池（12 线程）一旦被别的播放/搜索请求占满，新任务就永远
         * 排不上队 —— 外层等满 deadline 判 miss，日志是
         * 「aggregator miss after 17490ms」，而同一个 host 单独测只要 1.4 秒。
         * 这是典型的"池内提交同池任务"自锁：任务等的是自己占着的资源。
         *
         * 自建地址只有 1 个时本来也没有并发收益。
         */
        if (pool.length == 1) {
            String h = pool[0];
            try {
                // 带 {name}/{episode} 的模板（配置里的 danmaku 字段常见形态）
                // 不是弹弹play 三段式，必须走模板替换后直接取
                String b = isDanmakuTemplate(h)
                        ? fetchDanmakuByTemplate(h, clean, ep)
                        : fetchDanmakuByProtocol(h, clean, ep);
                boolean ok = b != null && b.contains("<d ");
                markDanmakuHost(h, ok);
                if (ok) {
                    System.err.println("[danmaku] aggregator ok in "
                            + (System.currentTimeMillis() - t0) + "ms");
                    return b;
                }
            } catch (Throwable t) {
                markDanmakuHost(h, false);
            }
            System.err.println("[danmaku] aggregator miss after "
                    + (System.currentTimeMillis() - t0) + "ms");
            return "";
        }

        List<java.util.concurrent.Future<String>> fs = new ArrayList<>();
        for (String h : pool) {
            final int epf = ep;
            fs.add(POOL.submit(() -> {
                try {
                    // 多个自建 host 里也可能混着模板地址（配置 danmaku 字段），
                    // 不能一律按弹弹play 三段式处理。
                    String b = isDanmakuTemplate(h)
                            ? fetchDanmakuByTemplate(h, clean, epf)
                            : fetchDanmakuByProtocol(h, clean, epf);
                    boolean ok = b != null && b.contains("<d ");
                    // 只记"被调用过且失败"。成功也记（清掉旧的失败标记）。
                    // 注意这里不区分"站挂了"和"这站没这部剧"——两者都是
                    // "这个 host 此刻没给结果"，冷却它对两者都合理。
                    markDanmakuHost(h, ok);
                    return ok ? b : null;
                } catch (Throwable t) {
                    markDanmakuHost(h, false);
                    return null;
                }
            }));
        }
        /*
         * ★ 自建地址的 deadline 必须 ≥ 25 秒（2026-11-02 MuMu 实测）。
         *
         * 实测三段耗时（TV → OECT danmu_api）：
         *   /api/v2/search/anime  54ms
         *   /api/v2/bangumi       91ms   （累计 ~145ms）
         *   /api/v2/comment       **9375ms**  ← 大头在这
         * 合计 9.5 秒。原来 deadline 设 8 秒，正好把**已经有完整内容**的
         * 请求判成超时，于是降级链落到 iqiyi（7978 条 vs danmu_api 的
         * 15162 条）—— 现象是"配了 API 优先但用的还是内置"。
         *
         * 为什么 comment 这么慢：danmu_api 要现从上游（爱奇艺/腾讯/360…）
         * 抓取、合并、去重、转格式，1.1MB XML 是一边抓一边吐的。
         * 自建地址值得等——它是用户明确配置的、且内容更全。
         *
         * 内置镜像保持 9 秒：它们是"兜底"，慢的就该让位给内置直连。
         */
        long deadline = System.currentTimeMillis() + (isCustom ? 28_000L : 9_000L);
        /*
         * 按「谁先成功」收，不是按数组顺序阻塞。
         *
         * 旧实现 `for (f : fs) f.get(left)` 会先等 pool[0]：用户配的多个
         * 自建镜像里第一个很慢时，第二个即使 200ms 就返回也要陪着等。
         * 现在轮询 isDone()，先成功先用（2026-11-09）。
         */
        List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < fs.size(); i++) pending.add(i);
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            boolean anyDone = false;
            for (int k = pending.size() - 1; k >= 0; k--) {
                int idx = pending.get(k);
                if (!fs.get(idx).isDone()) continue;
                pending.remove(k);
                anyDone = true;
                String b = null;
                try {
                    b = fs.get(idx).get(0, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                }
                if (b != null && b.contains("<d ")) {
                    System.err.println("[danmaku] aggregator ok in "
                            + (System.currentTimeMillis() - t0) + "ms");
                    return b;
                }
            }
            if (!anyDone) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        System.err.println("[danmaku] aggregator miss after "
                + (System.currentTimeMillis() - t0) + "ms");
        return "";
    }

    /**
     * 这个 host 是不是用户配的（policy 的 danmakuApi 或 MEOW_DANMAKU_API）。
     *
     * <p>用来区分"自建地址"和"内置镜像"：前者通常 5ms 就返回，
     * 后者多为死域名、每个要等满超时。混在一起并发时，死镜像会把
     * 整条链拖到 9 秒以上，用户体感是"切集后弹幕半天不出来"。
     */
    private static boolean isCustomDanmakuHost(String host) {
        if (host == null || host.isEmpty()) return false;
        for (String builtin : DANMAKU_HOSTS) {
            if (builtin.equals(host)) return false;
        }
        return true;
    }

    /** 缓存命中时取回命中源；没有记录就返回 unknown（老缓存没这个字段）。 */
    private static String cachedSource(String cacheKey) {
        String s = DANMAKU_SRC_CACHE.get(cacheKey);
        return s == null || s.isEmpty() ? "unknown" : s;
    }

    /**
     * 简易 TTL 缓存。容量 24 条，超过就清最旧的。
     * 肥猫用的是 12 条 / 12 MB，我们弹幕单集最大约 2.5 MB，24 条更实用。
     */
    private static final class TtlCache {
        private final java.util.LinkedHashMap<String, Object[]> map =
                new java.util.LinkedHashMap<>(32, 0.75f, true);
        private static final int MAX = 24;

        synchronized void put(String key, String value, long ttlMs) {
            map.put(key, new Object[]{value, System.currentTimeMillis() + ttlMs});
            while (map.size() > MAX) {
                java.util.Iterator<String> it = map.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        }

        /**
         * 命中返回缓存值，未命中返回 {@code null}。
         *
         * <p><b>空串是有效值</b>：失败时写入的就是空串，当作"这次没弹幕"的负缓存。
         * 早先这里把空串当未命中返回 null，导致负缓存永远不生效——
         * 已死的源每次请求都会被重新打一遍（实测确认，工作流复核也抓到同一点）。
         * 调用方要区分"没缓存"和"缓存了空"，看返回值是否为 null 即可。
         */
        synchronized String get(String key) {
            Object[] v = map.get(key);
            if (v == null) return null;
            if ((Long) v[1] < System.currentTimeMillis()) {
                map.remove(key);
                return null;
            }
            return (String) v[0];
        }
    }

    private static final TtlCache DANMAKU_CACHE = new TtlCache();

    // ---------------------------------------------------------------- 搜索结果缓存

    /**
     * 单源搜索结果缓存：{@code site + wd + pg} → spider 返回的原始 JSON。
     *
     * <p><b>为什么要缓存</b>（2026-10-30 实测依据）：同一个词连搜 3 轮，
     * 10 个源的返回**逐字节稳定**（Bili 17 条、Jpys 6 条、Gulu 3 条，
     * 三轮完全相同）。既然结果稳定，5 分钟内重复搜索没有任何理由
     * 再去打一遍 270 个底层源 —— 那既是 7~20 秒的等待，也是
     * OECT 上把 DNS / drpy 容器打爆的直接原因。
     *
     * <p><b>TTL 分两档</b>：
     * <ul>
     *   <li>有结果 → <b>2 小时</b>。片名搜索结果本身变化很慢，
     *       同一天反复搜同一部片是常见操作（换端、切换源、重新进播放页）。</li>
     *   <li>空结果 → <b>3 分钟</b>。空可能是"这个源此刻抖了"，
     *       长 TTL 会让它长时间不出结果；短 TTL 既减少白打、
     *       又保证源站恢复后能较快被重新发现。</li>
     * </ul>
     *
     * <p><b>为什么按"源"而不是按"整轮搜索"缓存</b>：按源缓存可以部分命中 ——
     * 用户加了新源、或某个源刚恢复时，只有缺的那几个会真跑，
     * 其余走缓存。整轮缓存一旦有一个源变了就整份失效，浪费大。
     *
     * <p><b>强制刷新</b>：请求带 {@code refresh:true} 时跳过读缓存
     * （仍会写回），对应前端的"重新搜索"动作。
     */
    private static final class SearchCache {
        /**
         * 有结果：默认 2 小时。
         *
         * <p>可被 `policy.json` 的 {@code searchCacheHitSec} 覆盖
         * （0 = 不缓存结果，每次真跑）。设置页有对应输入框。
         */
        /*
         * 类加载期只放默认值，**不读 policy.json**。
         * 静态初始化早于 main() 的 MainArgs.set(args)：此时 DataPaths.root()
         * 会先把 ./data 缓存下来，PC 的 --data-dir 就被静默忽略（实测）。
         * 真实值在 DataPaths 就绪后由 initAfterArgs() 调 reloadTtl() 载入。
         */
        static volatile long TTL_HIT = 2L * 3600 * 1000;
        /** 空结果：默认 3 分钟。可用 {@code searchCacheMissSec} 覆盖。 */
        static volatile long TTL_MISS = 180L * 1000;
        /**
         * 失败结果（运行时/网络错误）：默认 60 秒。
         * 可用 {@code searchCacheFailSec} 覆盖。**不建议调大**，理由见下。
         *
         * <p><b>为什么必须有这一档</b>（2026-10-30 OECT 实测）：
         * drpy 源失败时返回的是 `{"code":-1,"msg":"fetch failed"}` ——
         * 它是**非空字符串**，旧判据 `contains("\"list\"")` 之外的分支
         * 会把它算成"有结果"按 2 小时缓存。于是一次网络抖动 / 容器重启
         * 就把 226 个源锁死两小时：界面上一直没有它们，用户只能看到
         * "这次搜到的比昨天少"，而且怎么刷都刷不回来（缓存没到期）。
         *
         * <p>60 秒的取舍：足够挡住"同一轮里并发/重试反复打同一个坏源"，
         * 又能在容器恢复后一分钟内自动恢复。失败**必须短命**。
         */
        static volatile long TTL_FAIL = 60L * 1000;

        /** 从 policy.json 读秒数；缺失或非法用默认值。 */
        private static long resolveTtl(String key, long defSec) {
            try {
                long v = loadPolicy().optLong(key, defSec);
                return Math.max(0L, v) * 1000L;
            } catch (Throwable t) {
                return defSec * 1000L;
            }
        }

        /** 设置页改完 policy 后调用，让新 TTL 立刻生效（不必重启）。 */
        static void reloadTtl() {
            TTL_HIT = resolveTtl("searchCacheHitSec", 2L * 3600);
            TTL_MISS = resolveTtl("searchCacheMissSec", 180);
            TTL_FAIL = resolveTtl("searchCacheFailSec", 60);
        }
        /**
         * 容量上限（条数）。
         *
         * 270 个源 × 10 个热词 = 2700 条，每条原始 JSON 平均 2~5KB
         * → 最坏约 13MB。容器堆 512MB，这个占比可接受；
         * 再多就 LRU 淘汰最久未用的。
         */
        private static final int MAX = 3000;

        private final java.util.LinkedHashMap<String, Object[]> map =
                new java.util.LinkedHashMap<>(256, 0.75f, true);

        /**
         * 这条返回是否是"失败"（不是源站真的没内容）。
         *
         * 判据只看结构化标记，不做自然语言猜测：
         *   - `"code":-1`：本项目所有错误返回的统一形状
         *     （见 callScriptRuntime 的各条 return）
         *   - `fetch failed` / `SocketTimeoutException` / `Connection refused`：
         *     drpy 容器与外网故障的典型文案
         *
         * 空 `{"list":[]}` **不算**失败 —— 那是源站正常答复"没有这部剧"，
         * 走 TTL_MISS 即可。
         */
        static boolean isFailure(String value) {
            if (value == null || value.isEmpty()) return true;
            if (value.contains("\"code\":-1") || value.contains("\"code\": -1")) return true;
            return value.contains("fetch failed")
                    || value.contains("SocketTimeoutException")
                    || value.contains("Connection refused")
                    || value.contains("jsRuntime 未配置")
                    || value.contains("js runtime HTTP");
        }

        synchronized void put(String site, String wd, String pg, String value) {
            long ttl;
            if (isFailure(value)) {
                ttl = TTL_FAIL;
            } else {
                boolean hit = value.contains("\"list\"") && !value.contains("\"list\":[]");
                ttl = hit ? TTL_HIT : TTL_MISS;
            }
            map.put(key(site, wd, pg),
                    new Object[]{value, System.currentTimeMillis() + ttl});
            while (map.size() > MAX) {
                java.util.Iterator<String> it = map.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        }

        /** 命中返回原始 JSON；未命中返回 null。 */
        synchronized String get(String site, String wd, String pg) {
            String k = key(site, wd, pg);
            Object[] v = map.get(k);
            if (v == null) return null;
            if ((Long) v[1] < System.currentTimeMillis()) {
                map.remove(k);
                return null;
            }
            return (String) v[0];
        }

        /** 统计：条数与命中率（给 /api/health 诊断）。 */
        synchronized String stats() {
            return map.size() + "/" + MAX;
        }

        /**
         * 清空全部缓存，返回清掉的条数。
         *
         * <p>给设置页的「清空搜索缓存」按钮用：TTL 是有结果的 2 小时，
         * 用户在别处修了源、或只是想立刻看全量时需要一个主动丢弃的入口。
         */
        synchronized int clear() {
            int n = map.size();
            map.clear();
            return n;
        }

        /** 给 {@code GET /api/search/cache} 的统计体。 */
        synchronized String statsJson() {
            int fresh = 0, stale = 0;
            long now = System.currentTimeMillis();
            for (Object[] v : map.values()) {
                if ((Long) v[1] < now) stale++; else fresh++;
            }
            return "{\"size\":" + map.size() + ",\"max\":" + MAX
                    + ",\"fresh\":" + fresh + ",\"stale\":" + stale
                    + ",\"ttlHitSec\":" + (TTL_HIT / 1000)
                    + ",\"ttlMissSec\":" + (TTL_MISS / 1000)
                    + ",\"ttlFailSec\":" + (TTL_FAIL / 1000) + "}";
        }

        private static String key(String site, String wd, String pg) {
            return site + '\u0001' + wd + '\u0001' + pg;
        }
    }

    private static final SearchCache SEARCH_CACHE = new SearchCache();

    /**
     * 弹幕落盘缓存。
     *
     * <p>内存 TtlCache 进程重启就丢，而「断网可看」要求弹幕也在本地。
     * 落盘到 {@code <data>/cache/danmaku/}，按 key 的哈希命名（key 里有剧名/集数，
     * 直接当文件名会撞上非法字符与长度限制）。
     *
     * <p>读序：内存 → 磁盘 → 网络。只在拿到非空结果时写盘——空结果表示
     * "这集没弹幕"，存下来只会把磁盘塞满空文件。
     */
    private static final long DANMAKU_DISK_TTL_MS = core.VideoCache.DANMAKU_TTL_MS;

    private static java.io.File danmakuCacheFile(String key) {
        try {
            java.nio.file.Path dir = core.DataPaths.dir("cache/danmaku");
            return dir.resolve(md5Hex(key) + ".xml").toFile();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /**
     * 只判断"磁盘上有没有这一集的有效缓存"，**不动任何 LRU/active 状态**。
     *
     * <p>给预取端点用：它只是想知道"还差不差这一集"，如果复用
     * {@link #danmakuDiskGet}，探测本身就会刷新 lastModified 并把 activeKey
     * 改成被探测的那一集 —— 于是"检查下一集有没有缓存"这个纯读操作，
     * 反过来把正在播那集踢出保护范围。
     */
    private static boolean danmakuOnDiskUnread(String key) {
        java.io.File f = danmakuCacheFile(key);
        if (f == null || !f.isFile()) return false;
        if (System.currentTimeMillis() - f.lastModified() > DANMAKU_DISK_TTL_MS) return false;
        return f.length() > 0;
    }

    /** 磁盘缓存命中返回内容，否则 null。过期文件不在这里删（留给清理策略）。 */
    private static String danmakuDiskGet(String key) {
        java.io.File f = danmakuCacheFile(key);
        if (f == null || !f.isFile()) return null;
        if (System.currentTimeMillis() - f.lastModified() > DANMAKU_DISK_TTL_MS) return null;
        try {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (s.isEmpty()) return null;
            /*
             * 命中即刷新 lastModified（LRU 的"最近用过"信号）+ 标记"正在播"，
             * 让 enforceDanmakuQuota 不会把这一集删掉（见那里的注释）。
             */
            core.VideoCache.touchDanmaku(key);
            core.VideoCache.touchDanmakuFile(f.toPath());
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private static void danmakuDiskPut(String key, String xml) {
        if (xml == null || xml.isEmpty()) return;
        java.io.File f = danmakuCacheFile(key);
        if (f == null) return;
        try {
            java.nio.file.Files.write(f.toPath(), xml.getBytes(StandardCharsets.UTF_8));
            /*
             * 写完立刻检查配额（照搬视频缓存：写入点是唯一会让占用增长的地方，
             * 在这里检查最及时，不需要额外定时任务）。
             *
             * ★ 用 extraProtectKey 保住刚写完的这一份，**不要**调 touchDanmaku ——
             * 那会把它标成"正在播"，顶掉真正在播那集的保护。预取下一集时
             * 尤其致命：用户还在看 ep11，预取 ep12 却让 ep11 变成可淘汰，
             * 淘汰顺序恰好从最旧的 ep11 开始删（用户口径："别淘汰正在播那一集"）。
             */
            core.VideoCache.enforceDanmakuQuota(key);
        } catch (Exception e) {
            // 磁盘满/无权限只影响离线能力，绝不能影响在线播放
            System.out.println("[danmaku] 写盘失败 " + e.getClass().getSimpleName());
        }
    }

    /**
     * 「这一集弹幕是哪个源给的」。与 DANMAKU_CACHE 同 key、同 TTL。
     *
     * <p>为什么单独一张表而不是塞进内容里：内容是给播放器吃的 XML，
     * 混进来源标记就破坏了 XML 格式；而且来源要能在缓存命中时仍然说出来。
     */
    private static final TtlCache DANMAKU_SRC_CACHE = new TtlCache();

    /**
     * 爱奇艺 tvId → 时长（秒）。
     *
     * <p>取弹幕分片时必须有真实时长：分片数 = ceil(时长/300)。
     * 搜索阶段顺手把 {@code timeLength} 存进来，后续按 id 取弹幕就能命中；
     * 命中不了才去回查（见 {@code iqiyiDurationOf}）。
     */
    private static final Map<String, Integer> IQIYI_DURATION = new ConcurrentHashMap<>();

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 弹幕正文转成 XML 文本节点。
     *
     * 源站返回的正文可能已经带了 HTML 实体（实测腾讯会回 {@code &#128077;}），
     * 直接再转义 {@code &} 会变成 {@code &amp;#128077;}，前端 DOMParser 还原出的
     * 是一串字面量，屏幕上就显示成 {@code &#128077;} 而不是表情。
     * 所以先把已知实体解回字符，再统一转义一次；重复调用结果稳定（幂等）。
     */
    private static String escapeXmlText(String s) {
        if (s == null || s.isEmpty()) return "";
        String v = s;
        // 只解 XML 预定义实体和数字实体，其它自定义实体原样保留由下一步转义
        if (v.indexOf('&') >= 0) {
            v = v.replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&apos;", "'")
                    .replace("&amp;", "&");
            v = decodeNumericEntities(v);
        }
        return v.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 把 {@code &#123;} / {@code &#x1F44D;} 解成字符；越界或非法引用原样保留 */
    private static String decodeNumericEntities(String s) {
        int amp = s.indexOf("&#");
        if (amp < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '&' && i + 2 < s.length() && s.charAt(i + 1) == '#') {
                int start = i + 2;
                boolean hex = start < s.length() && (s.charAt(start) == 'x' || s.charAt(start) == 'X');
                int numStart = hex ? start + 1 : start;
                int j = numStart;
                while (j < s.length() && j - numStart < 8
                        && Character.digit(s.charAt(j), hex ? 16 : 10) >= 0) {
                    j++;
                }
                if (j > numStart && j < s.length() && s.charAt(j) == ';') {
                    try {
                        int cp = Integer.parseInt(s.substring(numStart, j), hex ? 16 : 10);
                        if (Character.isValidCodePoint(cp)
                                && !(cp >= 0xD800 && cp <= 0xDFFF)) {
                            sb.appendCodePoint(cp);
                            i = j + 1;
                            continue;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            sb.append(s.charAt(i));
            i++;
        }
        return sb.toString();
    }

    /** 按片名走爱奇艺官方：搜索 → 取 tvId → Bullet 分片。 */
    private static String fetchIqiyiByTitle(String clean, int ep) {
        try {
            String searchUrl = IQIYI_SEARCH + URLEncoder.encode(clean, "UTF-8");
            String body = fetchText(searchUrl, 12_000, IQIYI_REFERER);
            if (body == null) {
                System.err.println("[danmaku] iqiyi search: null response");
                return "";
            }
            if (!body.trim().startsWith("{")) {
                System.err.println("[danmaku] iqiyi search: non-json " + body.length() + "B: "
                        + body.substring(0, Math.min(100, body.length())));
                return "";
            }
            JSONObject data = new JSONObject(body).optJSONObject("data");
            JSONArray docs = data == null ? null : data.optJSONArray("docinfos");
            if (docs == null) {
                System.err.println("[danmaku] iqiyi search: no docinfos");
                return "";
            }
            for (int i = 0; i < docs.length(); i++) {
                JSONObject album = docs.optJSONObject(i).optJSONObject("albumDocInfo");
                if (album == null) continue;
                if (!IQIYI_CHANNEL.matcher(album.optString("channel", "")).matches()) continue;
                JSONArray vids = album.optJSONArray("videoinfos");
                if (vids == null || vids.length() == 0) continue;
                int idx = Math.max(0, Math.min(ep - 1, vids.length() - 1));
                JSONObject v = vids.optJSONObject(idx);
                if (v == null) continue;
                String tvId = String.valueOf(v.optLong("tvId", 0));
                if (tvId.length() < 8) continue;
                // 必须传真实时长：分片数 = ceil(时长/300)，传集数会只下 1 片
                int dur = v.optInt("timeLength", 0);
                return fetchIqiyiBulletXml(tvId, dur);
            }
        } catch (Throwable t) {
            System.err.println("[danmaku] iqiyi by title: " + t);
        }
        return "";
    }

    /**
     * 腾讯弹幕：走 {@code dm.video.qq.com/barrage/} 的**分片**接口。
     *
     * <p>旧实现用 {@code mfm.video.qq.com/danmu?second=N} 翻页，实测 {@code second}
     * 完全不生效（每次都返回同一批 300 条、timepoint 全在 0~29 秒），
     * 拼出来是"全部堆在第 0 秒"，所以那条线一直被默认关闭。
     * 新实现按官方分片表逐片取，时间轴真实。
     *
     * <p>分片按 30 秒切（{@code segment_name} 形如 {@code t/v1/30000/60000}，
     * 单位毫秒）。{@code barrage_list[].time_offset} 是**片内**毫秒偏移，
     * 绝对秒 = 片起点/1000 + time_offset/1000。
     *
     * <p>并发 4：分片数可达 90+（45 分钟片），串行会把搜索拖到十几秒。
     * 只取"有内容"的分片，空片直接跳过——合集类视频常有大量空段。
     */
    private static String fetchTencentXml(String vid, int ep) {
        String cacheKey = "tx|" + vid;
        String cached = DANMAKU_CACHE.get(cacheKey);
        if (cached != null) return cached;

        // 1) 分片表
        String baseBody = fetchTextPlain(TENCENT_SEG_BASE + vid, 12_000, TENCENT_REFERER);
        if (baseBody == null) return "";
        int bst = baseBody.indexOf('{');
        if (bst < 0) return "";
        org.json.JSONObject base;
        org.json.JSONObject idx;
        try {
            base = new org.json.JSONObject(baseBody.substring(bst));
            idx = base.optJSONObject("segment_index");
        } catch (Exception e) {
            return "";
        }
        if (idx == null) return "";

        // 2) 按起点排序（JSON 对象无序，必须显式排序才能正确累加绝对时间）
        List<Object[]> segs = new ArrayList<>();
        java.util.Iterator<String> it = idx.keys();
        while (it.hasNext()) {
            String k = it.next();
            org.json.JSONObject s = idx.optJSONObject(k);
            if (s == null) continue;
            String name = s.optString("segment_name", "");
            if (name.isEmpty()) continue;
            long startMs = 0;
            try {
                startMs = Long.parseLong(k);
            } catch (Exception ignored) {
                // 键不是数字时退回 segment_start 字段
                try {
                    startMs = Long.parseLong(s.optString("segment_start", "0"));
                } catch (Exception ignored2) {
                }
            }
            segs.add(new Object[]{startMs, name});
        }
        if (segs.isEmpty()) return "";
        segs.sort((a, b) -> Long.compare((Long) a[0], (Long) b[0]));

        // 3) 并发取片（上限 4，爱奇艺 CDN 那边的经验：太高会被限流）
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(4);
        List<String[]> all = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            for (Object[] s : segs) {
                final long startMs = (Long) s[0];
                final String name = (String) s[1];
                fs.add(pool.submit(() -> {
                    String body = fetchTextPlain(TENCENT_SEG_PART + vid + "/" + name,
                            10_000, TENCENT_REFERER);
                    if (body == null) return null;
                    int st = body.indexOf('{');
                    if (st < 0) return null;
                    try {
                        org.json.JSONArray list = new org.json.JSONObject(body.substring(st))
                                .optJSONArray("barrage_list");
                        if (list == null || list.length() == 0) return null;
                        /*
                         * 诊断（2026-11-02）：腾讯原始分片到底有没有颜色字段？
                         * 抓取层现在把颜色写死成白色 16777215，全站 4 万条全白。
                         * 打一次 key 列表就能确认上游是否提供 —— 有就接上，
                         * 没有就在注释里写明"上游不提供"，免得以后反复怀疑。
                         */
                        org.json.JSONObject probe = list.optJSONObject(0);
                        if (probe != null) {
                            String s0 = probe.toString();
                            System.err.println("[danmaku] tencent seg keys = "
                                    + probe.names()
                                    + " | sample = " + s0.substring(0, Math.min(320, s0.length())));
                        }
                        for (int i = 0; i < list.length(); i++) {
                            org.json.JSONObject c = list.optJSONObject(i);
                            if (c == null) continue;
                            String text = c.optString("content", "").trim();
                            if (text.isEmpty()) continue;
                            long off = 0;
                            try {
                                off = Long.parseLong(c.optString("time_offset", "0"));
                            } catch (Exception ignored) {
                            }
                            long absMs = startMs + off;
                            /*
                             * 颜色：优先读上游字段，读不到退回白色（16777215）。
                             *
                             * 腾讯分片对不同的 vid 返回的字段并不一致 —— 老接口用
                             * {@code color}，部分片用 {@code content_color} / {@code fontcolor}，
                             * 还有的干脆不带颜色。做成"多字段探测 + 白色兜底"，
                             * 有新字段时不用再改代码。
                             *
                             * 值域兼容三种写法：十进制 RGB（16777215）、"#RRGGBB"、
                             * 六位十六进制（ffffff）——统一归到十进制喂给 p 字段，
                             * 前端 parseDanmakuXml 直接 parseInt(...,10) 就能用。
                             */
                            String color = "16777215";
                            try {
                                String raw = null;
                                for (String k : new String[]{
                                        "color", "content_color", "fontcolor", "font_color"}) {
                                    String v = c.optString(k, "");
                                    if (!v.isEmpty()) { raw = v; break; }
                                }
                                if (raw != null) {
                                    raw = raw.trim();
                                    if (raw.startsWith("#")) raw = raw.substring(1);
                                    int rgb;
                                    if (raw.matches("(?i)[0-9a-f]{6}")) {
                                        rgb = Integer.parseInt(raw, 16);
                                    } else if (raw.matches("\\d+")) {
                                        rgb = Integer.parseInt(raw);
                                    } else {
                                        rgb = -1;
                                    }
                                    // 只接受合法 RGB 范围；0 是"纯黑"，在多数深色背景上
                                    // 等于看不见，按上游缺省处理成白色
                                    if (rgb > 0 && rgb <= 0xFFFFFF) {
                                        color = String.valueOf(rgb);
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                            all.add(new String[]{String.valueOf(absMs / 1000),
                                    color, escapeXmlText(text)});
                        }
                    } catch (Exception ignored) {
                    }
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> f : fs) {
                try {
                    f.get(12, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (all.isEmpty()) return "";

        List<String[]> sorted;
        synchronized (all) {
            sorted = new ArrayList<>(all);
        }
        sorted.sort((x, y) -> Integer.compare(Integer.parseInt(x[0]), Integer.parseInt(y[0])));
        int cap = Math.min(sorted.size(), 40000);
        StringBuilder sb = new StringBuilder(cap * 48 + 64);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><i>");
        for (int i = 0; i < cap; i++) {
            String[] b = sorted.get(i);
            sb.append("<d p=\"").append(b[0]).append(",1,25,")
                    .append(b[1]).append(",0,0,0,0\">").append(b[2]).append("</d>");
        }
        sb.append("</i>");
        String xml = sb.toString();
        DANMAKU_CACHE.put(cacheKey, xml, 30 * 60_000L);
        return xml;
    }

    /**
     * 腾讯自动抓取开关（policy.json 的 {@code danmakuTencent}）。
     *
     * <p><b>默认值从 false 改成 true</b>（2026-11-02）：老实现用
     * {@code mfm.video.qq.com/danmu?second=N} 翻页，{@code second} 实测无效，
     * 拿到的弹幕全堆在第 0 秒 —— 那时默认关闭是对的。
     * 现在换成 {@code dm.video.qq.com/barrage/} 分片接口（见
     * {@link #fetchTencentXml}），时间轴实测覆盖 0~3983 秒、每 5 分钟约
     * 3000 条，可以放心参与自动抓取。
     *
     * <p>仍然保留开关：极少数片子的腾讯源是"预告/花絮"（分片表为空），
     * 关掉能少一次无效请求。显式写 {@code false} 才关，缺省即开。
     */
    private static boolean isTencentAutoEnabled() {
        try {
            // 走 DataPaths 而不是相对路径：容器工作目录是 /、PC 是安装目录，
            // 相对路径会读到"碰巧存在"的别人的 policy.json，表现为"开关时灵时不灵"。
            if (!core.DataPaths.exists("policy.json")) return true;
            String txt = core.DataPaths.read("policy.json");
            if (txt == null) return true;
            return new JSONObject(txt).optBoolean("danmakuTencent", true);
        } catch (Exception e) {
            return true;
        }
    }

    /** 朴素 GET（不经过 openStream 的 DoH/SSL 分支），返回文本。 */
    private static String fetchTextPlain(String url, int timeout, String referer) {
        HttpURLConnection c = null;
        try {
            url = idnUrl(url);
            c = (HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            if (referer != null) c.setRequestProperty("Referer", referer);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 8 * 1024 * 1024) break;
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 通用 POST JSON（腾讯搜索用）。 */
    private static String postJson(String url, String json, String referer, int timeout) {
        try {
            HttpURLConnection c = openStream(url, referer, "Mozilla/5.0", true);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (referer != null) c.setRequestProperty("Referer", referer);
            try (OutputStream os = c.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 8 * 1024 * 1024) break;
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从请求里推断对外基址（给 /list 与 m3u8 分片改写生成绝对 url 用）。
     *
     * <p>协议按 X-Forwarded-Proto 判断：容器里 nginx 反代时对外可能是 https，
     * 若这里硬编码 http，播放器会把分片请求发到 https 页面的 http 地址上，
     * 被浏览器当混合内容拦掉。
     */
    private static String baseUrlFor(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || host.isEmpty()) host = "127.0.0.1:9979";
        String proto = ex.getRequestHeaders().getFirst("X-Forwarded-Proto");
        if (proto == null || proto.trim().isEmpty()) proto = "http";
        // 飞牛微应用（fnOS）经网关把 /app/meow/... 转到 unix socket，此时 Host 是
        // NAS:5666，真正入口却在 /app/meow 下；不补前缀，m3u8 分片会被改写成站点根
        // 地址（不属于本应用），播放必坏。TCP 直连没有该头，行为不变。
        //
        // ★ 同一层网关还常把端口丢掉（nginx `proxy_set_header Host $host` 只给主机名）。
        // 实测 fnOS 5666：Host=nas.local（无端口）→ 分片被改写成 http://nas.local/app/meow/...
        // → 打到 80 端口变成登录页 302，播放永远起不来。浏览器请求自带的 Origin/Referer
        // 是带端口的，主机名一致时优先用它（只换 authority，路径前缀仍走上面的头）。
        return externalOrigin(ex, host, proto) + forwardedPrefix(ex);
    }

    /** 取请求里更可信的外部源（含端口）；主机名必须与 Host 一致，避免被 Referer 带到别的主机。 */
    private static String externalOrigin(HttpExchange ex, String host, String proto) {
        String fallback = proto.trim().toLowerCase() + "://" + host;
        String want = authorityHost(host);
        if (want.isEmpty()) return fallback;
        String forwarded = ex.getRequestHeaders().getFirst("X-Forwarded-Host");
        if (forwarded != null && forwarded.contains(",")) forwarded = forwarded.split(",", 2)[0].trim();
        // 顺序有讲究：浏览器自带的 Origin/Referer 一定带端口；X-Forwarded-Host
        // 可能和 Host 一样被网关写成无端口，所以只在它们不可用时兜底。
        String[] candidates = {
                ex.getRequestHeaders().getFirst("Origin"),
                ex.getRequestHeaders().getFirst("Referer"),
                forwarded
        };
        for (String raw : candidates) {
            if (raw == null) continue;
            String v = raw.trim();
            if (v.isEmpty() || "null".equalsIgnoreCase(v)) continue;
            try {
                URI u = new URI(v);
                String scheme = u.getScheme();
                if (scheme == null) continue;
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) continue;
                String h = u.getHost();
                if (h == null || !h.equalsIgnoreCase(want)) continue;
                int port = u.getPort();
                return scheme.toLowerCase() + "://" + h + (port > 0 ? ":" + port : "");
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    private static String authorityHost(String authority) {
        try {
            String h = new URI("http://" + authority).getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 读 `X-Forwarded-Prefix`。只接受「以 / 开头、无空白 / `..` / `://`」的路径前缀，
     * 其余一律忽略——这个头来自客户端可见的请求头，不校验会变成任意跳转。
     */
    private static String forwardedPrefix(HttpExchange ex) {
        String raw = ex.getRequestHeaders().getFirst("X-Forwarded-Prefix");
        if (raw == null) return "";
        String p = raw.trim();
        if (p.isEmpty() || p.charAt(0) != '/') return "";
        if (p.contains("..") || p.contains("://")) return "";
        for (int i = 0; i < p.length(); i++) {
            if (Character.isWhitespace(p.charAt(i))) return "";
        }
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static void handleDanmaku(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String name = q.getOrDefault("name", "");
        String episode = q.getOrDefault("episode", "1");
        // 用户手选了具体弹幕源：直接用这个 url，不再走搜索
        String direct = q.getOrDefault("url", "");
        if (!direct.isEmpty()) {
            // 【坑】手动搜索里「直连」候选的 url 是我们自己的相对路径
            // （/api/proxy/danmaku/xml?id=iqiyi:...），不是外网地址。
            // 前端 proxyDanmakuByUrl() 会把它再包一层成
            // /api/proxy/danmaku?url=%2Fapi%2Fproxy%2Fdanmaku%2Fxml...
            // 这里如果直接 fetchText 相对路径，没有协议和主机，必然抛异常，
            // 结果是一条弹幕都没有 —— 表现为「直连候选点了没反应」。
            // 所以先识别自家路径，就地解析，不做 HTTP 自调用。
            String self = extractSelfDanmakuId(direct);
            if (self != null) {
                String xml = loadDanmakuById(self);
                sendText(ex, 200, xml, "text/xml; charset=utf-8");
                return;
            }
            String body = fetchText(direct, 20_000);
            if (body == null || !body.contains("<d ")) body = "<?xml version=\"1.0\"?><i></i>";
            sendText(ex, 200, body, "text/xml; charset=utf-8");
            return;
        }
        if (name.isEmpty()) {
            sendText(ex, 200, "<?xml version=\"1.0\"?><i></i>", "text/xml; charset=utf-8");
            return;
        }
        String clean = cleanName(name);
        int epNo;
        try {
            epNo = parseEpisodeNo(episode);
        } catch (Exception e) {
            epNo = 1;
        }

        // 统一走多源优先级链（iqiyi → danmu_api → 联盟），带 TTL 缓存。
        // 顺序见 DANMAKU_PRIORITY；14 秒的旧串行路径已改为分片并行，命中缓存 ~0ms。
        // fresh=1：客户端 12 秒后台补全检查用，跳过缓存强制重抓一次。
        boolean fresh = "1".equals(q.getOrDefault("fresh", ""));
        String[] src = new String[1];
        String xml = fetchDanmakuAuto(clean, epNo, src, fresh);
        String from = src[0] == null ? "unknown" : src[0];

        /*
         * ★ 主播放路径显式声明"这一集正在播"，配额淘汰时必须跳过它。
         *
         * 为什么放在这里而不是只靠写盘：预取也会写盘，两条路都会走
         * danmakuDiskPut，从写入侧分不出"用户在看"还是"顺手提前抓"。
         * 只有这个 handler 是播放器真正来取弹幕的入口。
         */
        if (!clean.isEmpty()) core.VideoCache.touchDanmaku("dm|" + clean + "|" + epNo);

        if (xml.isEmpty()) {
            System.err.println("[danmaku] all sources failed: " + clean + " ep" + epNo
                    + " hosts=" + Arrays.toString(danmakuHosts()));
            xml = EMPTY_DANMAKU_XML;
        }
        // 来源与空态都以响应头下发。
        // 为什么不塞进 XML：XML 是给人吃的格式（播放器 DOMParser），
        // 加自定义节点会破坏 `<i><d/></i>` 的约定；
        // 而响应头对播放器透明，前端却能在 fetch 后读到。
        ex.getResponseHeaders().set("X-Danmaku-Source", from);
        ex.getResponseHeaders().set("X-Danmaku-Count", String.valueOf(countTags(xml)));
        if (xml.isEmpty() || EMPTY_DANMAKU_XML.equals(xml)) {
            ex.getResponseHeaders().set("X-Danmaku-Empty", "1");
        }
        sendText(ex, 200, xml, "text/xml; charset=utf-8");
        return;
    }

    /** 空弹幕的标准回包。抽成常量：要能被"是不是空"的判断按值比较。 */
    private static final String EMPTY_DANMAKU_XML = "<?xml version=\"1.0\"?><i></i>";

    /** 数 `<d ` 出现次数——不解析 XML，仅用于给前端一个总条数（省得它自己数 DOM）。 */
    private static int countTags(String xml) {
        if (xml == null || xml.isEmpty()) return 0;
        int n = 0, i = 0;
        while ((i = xml.indexOf("<d ", i)) >= 0) {
            n++;
            i += 3;
        }
        return n;
    }

    /** 旧的多级降级链（保留作参考，当前不再调用）。 */
    private static void handleDanmakuLegacyChain(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String name = q.getOrDefault("name", "");
        String clean = cleanName(name);
        int epNo = 1;
        String xml = "";

        // 方案 A（肥猫主路径）：弹弹play 三段式协议，带 Referer。
        for (String host : xml.isEmpty() ? danmakuHosts() : new String[0]) {
            String body = fetchDanmakuByProtocol(host, clean, epNo);
            if (body != null && body.contains("<d ")) {
                xml = body;
                break;
            }
        }

        // 方案 B（兜底）：聚合站的 /api/v2/fongmi/danmaku 简化接口。
        outer:
        for (int round = 0; xml.isEmpty() && round < 2; round++) {
            for (String host : danmakuHosts()) {
                try {
                    String searchUrl = danmakuUrl(
                            host,
                            URLEncoder.encode(clean, "UTF-8"),
                            String.valueOf(epNo),
                            "xml");
                    String find = fetchText(searchUrl, 12_000, DANMAKU_REFERER);
                    if (find == null || find.isEmpty()) continue;
                    if (find.contains("<d ")) {
                        xml = find;
                        break outer;
                    }
                    if (!find.trim().startsWith("[")) continue;
                    JSONArray arr = new JSONArray(find);
                    // 一次搜索可能返回多个候选，逐个试
                    for (int i = 0; i < Math.min(arr.length(), 5); i++) {
                        String target = arr.optJSONObject(i).optString("url", "");
                        if (target.isEmpty()) continue;
                        String body = fetchText(target, 15_000, DANMAKU_REFERER);
                        if (body != null && body.contains("<d ")) {
                            xml = body;
                            break outer;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (xml.isEmpty()) {
                try {
                    Thread.sleep(150L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (xml.isEmpty()) {
            System.err.println("[danmaku] ALL sources failed for: " + clean + " ep" + epNo
                    + " hosts=" + Arrays.toString(danmakuHosts()));
            xml = "<?xml version=\"1.0\"?><i></i>";
        } else {
            System.err.println("[danmaku] ok: " + clean + " ep" + epNo + " -> "
                    + xml.length() + "B, d-count=" + countOccurrences(xml, "<d "));
        }
        sendText(ex, 200, xml, "text/xml; charset=utf-8");
    }

    private static int countOccurrences(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    /**
     * 从剧集名里提取集号。
     *
     * <p><b>不能直接 {@code replaceAll("[^0-9]","")}</b>：剧集名常带年份/季数，
     * 「2026 第5集」会被拼成 20265，后端去找第 20265 集 → 搜不到 → 回退到第 1 集，
     * 表现是"弹幕和画面对不上"（实测踩过）。
     *
     * <p>优先取「第N集/话/期」里的 N；其次取末尾的纯数字；都没有才退回整串数字。
     */
    static int parseEpisodeNo(String episode) {
        if (episode == null || episode.trim().isEmpty()) return 1;
        String s = episode.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("第\\s*(\\d{1,4})\\s*[集话話期]").matcher(s);
        if (m.find()) return Math.max(1, parseIntSafe(m.group(1), 1));
        m = java.util.regex.Pattern.compile("(?i)(?:\\d{1,2}|^|[\\s._-])E(?:P)?\\s*(\\d{1,4})\\b").matcher(s);
        if (m.find()) return Math.max(1, parseIntSafe(m.group(1), 1));
        m = java.util.regex.Pattern.compile("(\\d{1,4})\\s*$").matcher(s);
        if (m.find()) return Math.max(1, parseIntSafe(m.group(1), 1));
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.length() > 4) digits = digits.substring(digits.length() - 2);
        return Math.max(1, parseIntSafe(digits, 1));
    }

    private static String cleanName(String s) {
        // 不要用 `[（(].*?[）)]`：无括号时它会匹配空串，把整个片名吞掉。
        String r = s
                .replaceAll("[\\(（]\\s*(?:19|20)\\d{2}\\s*[\\)）]", "")
                .replaceAll("第\\s*\\d+\\s*季", "")
                .replaceAll("[\\(（][^\\)）]{0,20}[\\)）]", "")
                .trim();
        return r.isEmpty() ? s.trim() : r;
    }

    private static String fetchText(String url, int timeout) {
        return fetchText(url, timeout, null);
    }

    /**
     * 带 Referer 的抓取。
     *
     * 弹幕源的鉴别只在 referer 上：肥猫 merge/P/m.java 里 m.h() 固定带
     * `Referer: https://www.iqiyi.com/`，不带就会被当成爬虫挡掉。
     *
     * 连接走 openStream() 而不是直接 openConnection()：本机挂 TUN 代理时，
     * 系统 DNS 会把弹幕站解析成 198.18.x.x 的 fake-ip，curl/HttpURLConnection
     * 连这个地址只会超时——这是"弹幕全挂"的真正原因，和源站是否存活无关。
     * openStream 用 DoH 拿真实 IP，再用 Host 头回指域名。
     */
    private static String fetchText(String url, int timeout, String referer) {
        return fetchText(url, timeout, referer, "Mozilla/5.0");
    }

    /**
     * 带 Referer <b>和自定义 UA</b> 的抓取。
     *
     * <p>为什么需要单独一个 UA 参数：360 看的站内搜索对 UA 极敏感 ——
     * 传默认的 {@code "Mozilla/5.0"} 会返回 {@code rows: []}（HTTP 200、
     * 结构正常，只是没数据），必须用桌面 Chrome 的完整 UA 才有结果。
     * 这类"静默空结果"最难定位，所以显式留一个入口。
     */
    private static String fetchText(String url, int timeout, String referer, String ua) {
        try {
            // 强制 DoH：弹幕站被系统 DNS 解析成 TUN 的 fake-ip（198.18.x.x）时
            // 直连必然超时，与源的存活状态无关。这里不看 safeDns 开关。
            HttpURLConnection c = openStream(url, referer, ua, true);
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept", "*/*");
            if (referer != null) c.setRequestProperty("Referer", referer);
            if (c.getResponseCode() >= 400) {
                c.disconnect();
                return null;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            c.disconnect();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[fetch] " + url + " -> " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------ GitHub 加速（配置导入）

    /** 请求体里的 proxies 数组（只接受 https、最多 8 个）。 */
    private static List<String> proxiesFromBody(JSONObject in) {
        List<String> out = new ArrayList<>();
        JSONArray arr = in == null ? null : in.optJSONArray("proxies");
        if (arr == null) return out;
        for (int i = 0; i < arr.length() && out.size() < 8; i++) {
            String s = arr.optString(i, "").trim();
            if (s.startsWith("https://") && s.indexOf(' ') < 0) out.add(s);
        }
        return out;
    }

    /** 只有公开 GitHub 文件地址才挂代理；api.github.com（token 路径）不挂。 */
    private static boolean isConfigAccelHost(String url) {
        try {
            String host = new URL(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            if ("raw.githubusercontent.com".equals(host)
                    || "gist.githubusercontent.com".equals(host)) return true;
            if ("github.com".equals(host)) {
                return url.contains("/releases/download/") || url.contains("/releases/latest/download/");
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String applyProxyPrefix(String prefix, String url) {
        String p = prefix == null ? "" : prefix.trim();
        if (!p.startsWith("https://") || p.indexOf(' ') >= 0) return null;
        if (p.contains("{url}")) return p.replace("{url}", url);
        return p.endsWith("/") ? p + url : p + "/" + url;
    }

    /**
     * 配置导入的抓取：GitHub 公开 host 先直连，失败按 proxies 依次换。
     * 仍然走 fetchText/openStream（强制 DoH），不另造一套 HTTP 客户端。
     */
    /**
     * 配置抓取：先按 OkHttp/APK 客户端 UA 请求，再解 2423/图片伪装。
     *
     * <p>肥猫这类接口按 UA 分流：浏览器 UA 返回 HTML 首页，OkHttp UA 才返回
     * `2423...` 的 Hex+AES 配置（Fongmi 的 Decoder 就是这么做的）。只带
     * `Mozilla/5.0` 会永远导入失败，且报错看起来像“网页不是 JSON”。
     */
    private static String fetchConfigText(String url, int timeout) {
        String t = decodeConfigText(fetchText(url, timeout, null, "okhttp/3.12.0"));
        if (looksLikeJson(t)) return t;
        // 少数站反过来：APK UA 被挡，浏览器 UA 才给 JSON/普通配置。各试一次。
        String b = decodeConfigText(fetchText(url, timeout, null, "Mozilla/5.0"));
        if (looksLikeJson(b)) return b;
        return t != null && !t.trim().isEmpty() ? t : b;
    }

    private static boolean looksLikeJson(String s) {
        return core.ConfigText.looksJson(s);
    }

    private static String decodeConfigText(String raw) {
        return core.ConfigText.decode(raw);
    }

    private static String fetchTextAccel(String url, int timeout, List<String> proxies) {
        if (proxies == null || proxies.isEmpty() || !isConfigAccelHost(url)) {
            return fetchConfigText(url, timeout);
        }
        java.util.LinkedHashSet<String> cands = new java.util.LinkedHashSet<>();
        cands.add(url);
        for (String p : proxies) {
            String u = applyProxyPrefix(p, url);
            if (u != null) cands.add(u);
        }
        String last = "";
        for (String u : cands) {
            String t = fetchConfigText(u, timeout);
            if (t != null && !t.trim().isEmpty()) {
                if (!u.equals(url)) System.out.println("[config] accel hit: " + u);
                return t;
            }
            last = u;
        }
        System.out.println("[config] all candidates failed: " + url + " (last " + last + ")");
        return null;
    }

    /** 弹幕请求统一用的 Referer（肥猫实测值） */
    private static final String DANMAKU_REFERER = "https://www.iqiyi.com/";

    /** 爱奇艺官方搜刮（肥猫 m.g() 第 2 级降级）。 */
    private static final String IQIYI_REFERER = "https://www.iqiyi.com/";
    private static final String IQIYI_SEARCH =
            "https://search.video.iqiyi.com/o?if=html5&pageNum=1&pos=1&pageSize=10&site=iqiyi&key=";

    /**
     * 腾讯视频搜索（肥猫 {@code merge/p016q/J.k()}）。
     * POST JSON：{"query":"片名","sceneId":21,"platform":"23",...} → data.normalList.itemList[].doc.id
     */
    private static final String TENCENT_SEARCH =
            "https://pbaccess.video.qq.com/trpc.videosearch.mobile_search.HttpMobileRecall/MbSearchHttp";
    private static final String TENCENT_REFERER = "https://v.qq.com";

    /** 腾讯视频弹幕：按 video_id 分段拉取（peroid=30 秒一段）。 */
    private static final String TENCENT_DANMU = "https://mfm.video.qq.com/danmu";

    /**
     * 腾讯弹幕的**分片**接口（2026-11-02 实测后改用，替代上面的 {@link #TENCENT_DANMU}）。
     *
     * <p><b>为什么必须换</b>：{@code mfm.video.qq.com/danmu} 的 {@code second}
     * 参数**完全不生效** —— 实测传 0/30/60/120/300 返回的都是同一批 300 条
     * {@code timepoint} 落在 0~29 秒的弹幕（{@code count} 也被服务端锁死 300）。
     * 用它拼出来的 XML 是「3 万条全堆在第 0 秒」，播放时屏幕上糊成一片、
     * 后面 44 分钟一条没有。这正是老代码把腾讯线默认关掉的原因
     * （注释写"timepoint 恒为 0"）。
     *
     * <p>正确姿势（对齐 danmu_api 的 tencent.js）：
     * <pre>
     * GET https://dm.video.qq.com/barrage/base/{vid}
     *   → segment_index: { "0": {segment_name:"t/v1/0/30000"}, "30000": {...}, ... }
     * GET https://dm.video.qq.com/barrage/segment/{vid}/{segment_name}
     *   → barrage_list: [{ content, time_offset(该片内毫秒), ... }]
     * </pre>
     * 分片按 30 秒切，{@code time_offset} 是**片内**偏移，绝对时间 =
     * 分片起点秒 + time_offset/1000。
     */
    private static final String TENCENT_SEG_BASE = "https://dm.video.qq.com/barrage/base/";
    private static final String TENCENT_SEG_PART = "https://dm.video.qq.com/barrage/segment/";

    /** 腾讯 float_vinfo2：cid（专辑 id）→ video_ids（每集的 video_id）。 */
    private static final String TENCENT_VINFO =
            "https://node.video.qq.com/x/api/float_vinfo2?cid=";
    /**
     * 正片 channel 白名单（肥猫 m.java 的 `.*,(1|2|3|4|6|15)$`）。
     * 形如 "电视剧,2" / "电影,1" / "综艺,6" / "动漫,4" / "纪录片,15"。
     * 不加这层过滤会把"花絮/解读/预告"当成正片，拿到的是错的 tvId。
     */
    private static final java.util.regex.Pattern IQIYI_CHANNEL =
            java.util.regex.Pattern.compile(".*,(1|2|3|4|6|15)$");
    /**
     * Bullet 分片里的一条弹幕。四个捕获组：
     * contentId → showTime(秒) → color(RRGGBB) → content(文本)
     * 中间还有 font/opacity/uid 等字段，用 [\s\S]*? 非贪婪跨过。
     */
    private static final java.util.regex.Pattern IQIYI_BULLET = java.util.regex.Pattern.compile(
            "<contentId>(\\d+)</contentId>[\\s\\S]*?"
                    + "<showTime>(\\d+)</showTime>[\\s\\S]*?"
                    + "<color>([0-9a-fA-F]{6})</color>[\\s\\S]*?"
                    + "<content>(.*?)</content>");

    /**
     * 按弹弹play 协议取一集的弹幕（肥猫 merge/P/m.java 的 m.j() 逻辑）。
     *
     * 之前我们只调 `/api/v2/fongmi/danmaku`，那是聚合站自己的简化接口；
     * 肥猫实际走的是官方三段式：
     *   1. {host}/api/v2/search/anime?keyword=<片名>   -> animes[0].animeId
     *   2. {host}/api/v2/bangumi/<animeId>             -> bangumi.episodes[ep-1].episodeId
     *   3. {host}/api/v2/comment/<episodeId>?format=xml&withRelated=true&chConvert=1
     * 三步都要带 Referer，缺了会 403。
     */
    /**
     * 爱奇艺官方搜刮 —— 肥猫 {@code m.g()} 第 2 级降级的完整复刻。
     *
     * <p>这是目前唯一实测可用的官方入口（联盟三源已 402 / usage_exceeded）。
     * 链路：
     * <pre>
     * 1. https://search.video.iqiyi.com/o?if=html5&pageNum=1&pos=1&pageSize=10&site=iqiyi&key=片名
     *      → data.docinfos[].albumDocInfo
     *      → 按 channel 正则 {@code .*,(1|2|3|4|6|15)$} 过滤（剧/影/综/漫/纪录片等），
     *        能排除花絮、解读、预告这类干扰项
     * 2. 取 videoinfos[ep-1] 的 tvId 与 timeLength（秒）
     * 3. 按每片 300 秒算分片数，并发下载：
     *      https://cmts.iqiyi.com/bullet/{tvId末4取2}/{tvId末2}/{tvId}_300_{n}.z
     *      （注意是 段目录/末2位/完整tvId 三级，少了中间那层会 404）
     * 4. zlib 解压（注意用 wbits=15，即带 zlib 头的标准 zlib，不是 raw deflate）
     * 5. 解析 &lt;bulletInfo&gt; 的 content/showTime，转成 B 站式 XML
     * </pre>
     *
     * @return 成功返回 B 站式 XML；失败返回空串（调用方继续降级）
     */
    private static String fetchIqiyiOfficial(String name, int episode) {
        try {
            // 1) 搜索
            String searchUrl = IQIYI_SEARCH + URLEncoder.encode(name, "UTF-8");
            String body = fetchText(searchUrl, 15_000, IQIYI_REFERER);
            if (body == null || !body.trim().startsWith("{")) {
                System.err.println("[danmaku] iqiyi search bad response: "
                        + (body == null ? "null" : body.length() + "B " + body.substring(0, Math.min(60, body.length()))));
                return "";
            }

            JSONObject docinfos = new JSONObject(body).optJSONObject("data");
            JSONArray docs = docinfos == null ? null : docinfos.optJSONArray("docinfos");
            if (docs == null || docs.length() == 0) {
                System.err.println("[danmaku] iqiyi no docinfos");
                return "";
            }

            // 2) 按 channel 过滤，取第一个正片
            JSONObject album = null;
            StringBuilder chs = new StringBuilder();
            for (int i = 0; i < docs.length(); i++) {
                JSONObject a = docs.optJSONObject(i).optJSONObject("albumDocInfo");
                if (a == null) continue;
                String channel = a.optString("channel", "");
                chs.append('[').append(channel).append(']');
                if (IQIYI_CHANNEL.matcher(channel).matches()) {
                    album = a;
                    break;
                }
            }
            if (album == null) {
                System.err.println("[danmaku] iqiyi no channel match, seen=" + chs);
                return "";
            }

            JSONArray vids = album.optJSONArray("videoinfos");
            if (vids == null || vids.length() == 0) return "";
            int idx = Math.max(0, Math.min(episode - 1, vids.length() - 1));
            JSONObject vid = vids.optJSONObject(idx);
            if (vid == null) return "";

            String tvId = String.valueOf(vid.optLong("tvId", 0));
            int duration = vid.optInt("timeLength", 0);
            if (tvId.length() < 8 || duration <= 0) return "";

            // 3) 分片并行下载（每片 300 秒）
            return fetchIqiyiBulletXml(tvId, duration);
        } catch (Throwable t) {
            System.err.println("[danmaku] iqiyi official failed: " + t);
            return "";
        }
    }

    /**
     * 下载某个 tvId 的全部 Bullet 分片并拼成 XML。
     *
     * <p><b>并行是关键</b>：42 分钟的剧有 9 个分片，串行下载实测 13.9 秒，
     * 改成 4 线程并发后约 4~5 秒。肥猫在 {@code m.d()} 里用的是 3 线程 + 28 秒超时。
     *
     * @param tvId      爱奇艺视频 id
     * @param durationS 时长（秒）；为 0 时按 1 片处理
     */
    /**
     * 爱奇艺 Bullet 分片地址。
     *
     * <p>路径是三级：{@code 末4取2 / 末2 / 完整tvId_300_N.z}。
     * 少了中间那层会 404。抽出来是因为分片探测（{@link #iqiyiDurationOf}）
     * 和正式下载要拼同一个地址。
     */
    private static String iqiyiBulletUrl(String tvId, int frag) {
        return "https://cmts.iqiyi.com/bullet/"
                + tvId.substring(tvId.length() - 4, tvId.length() - 2) + "/"
                + tvId.substring(tvId.length() - 2) + "/"
                + tvId + "_300_" + frag + ".z";
    }

    private static String fetchIqiyiBulletXml(String tvId, int durationS) {
        String cacheKey = "iq|" + tvId;
        String cached = DANMAKU_CACHE.get(cacheKey);
        if (cached != null) return cached;

        int frags = Math.max(1, (Math.max(durationS, 1) + 299) / 300);

        List<String> raws = new ArrayList<>();
        // 并发数必须小：爱奇艺 CDN 对新建 TLS 连接有限流，实测 4 并发会大面积
        // SSLException(internal_error)。肥猫 m.d() 用的是 3 线程，这里取 2 更稳。
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<String>> fs = new ArrayList<>(frags);
            for (int n = 1; n <= frags; n++) {
                final String u = iqiyiBulletUrl(tvId, n);
                fs.add(pool.submit(() -> fetchBulletWithRetry(u)));
            }
            for (java.util.concurrent.Future<String> f : fs) {
                try {
                    String r = f.get(25, java.util.concurrent.TimeUnit.SECONDS);
                    if (r != null) raws.add(r);
                } catch (Exception ignored) {
                }
            }
        } finally {
            pool.shutdownNow();
        }

        List<String[]> all = new ArrayList<>();
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (String raw : raws) parseIqiyiBullets(raw, all, seenIds);
        if (all.isEmpty()) {
            System.err.println("[danmaku] iqiyi bullet: 0 items (tvId=" + tvId
                    + " frags=" + frags + " got=" + raws.size() + "/" + frags + ")");
            return "";
        }

        all.sort((x, y) -> Integer.compare(Integer.parseInt(x[0]), Integer.parseInt(y[0])));
        int cap = Math.min(all.size(), 40000);
        StringBuilder sb = new StringBuilder(cap * 48 + 64);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><i>");
        for (int i = 0; i < cap; i++) {
            String[] b = all.get(i);
            sb.append("<d p=\"").append(b[0]).append(",1,25,")
                    .append(b[1]).append(",0,0,0,0\">")
                    .append(b[2]).append("</d>");
        }
        sb.append("</i>");
        String xml = sb.toString();
        DANMAKU_CACHE.put(cacheKey, xml, 30 * 60_000L);
        return xml;
    }


    /** 未使用的旧 catch 分支占位（保持方法结构完整）。 */
    /** 下载单个 Bullet 分片并解压；失败返回 null。 */
    /**
     * 带重试的分片下载。
     *
     * <p>爱奇艺 CDN 对同时新建的 TLS 连接有限流，偶发
     * {@code SSLException: Received fatal alert: internal_error}。
     * 重试时加递增退避，把瞬时拒绝挡掉。
     */
    private static String fetchBulletWithRetry(String url) {
        for (int attempt = 0; attempt < 3; attempt++) {
            String r = fetchBullet(url);
            if (r != null) return r;
            try {
                Thread.sleep(200L * (attempt + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static String fetchBullet(String url) {
        HttpURLConnection c = null;
        try {
            // 不用 openStream：它的 DoH/自定义 SSL 分支在 cmts.iqiyi.com 上会触发
            // SSLException(internal_error)。这里用最朴素的直连，与 Python urllib 行为一致。
            java.net.URL u = new java.net.URL(url);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(12_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            c.setRequestProperty("Referer", IQIYI_REFERER);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code != 200) {
                System.err.println("[danmaku] bullet http " + code + ": ..."
                        + url.substring(Math.max(0, url.length() - 28)));
                return null;
            }
            try (InputStream in = c.getInputStream();
                 java.util.zip.InflaterInputStream z =
                         new java.util.zip.InflaterInputStream(in)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(256 * 1024);
                byte[] buf = new byte[8192];
                int n;
                while ((n = z.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 16 * 1024 * 1024) return null;   // 防御
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            System.err.println("[danmaku] bullet fail: " + t.getClass().getSimpleName()
                    + " @ ..." + url.substring(Math.max(0, url.length() - 22)));
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 从 Bullet XML 里抠出 contentId/showTime/color/content，转成 [time, colorDec, escapedText]。 */
    private static void parseIqiyiBullets(String xml, List<String[]> out, java.util.Set<String> seen) {
        java.util.regex.Matcher m = IQIYI_BULLET.matcher(xml);
        while (m.find()) {
            String contentId = m.group(1);
            String showTime = m.group(2);
            String colorHex = m.group(3);
            String content = m.group(4);
            if (content == null) continue;
            content = content.trim();
            if (content.isEmpty()) continue;
            // 分片边界可能重复（实测同一 tvId 各片 contentId 不重叠，但不同线路的
            // 分片会有交集），用 contentId 兜底去重。
            if (contentId != null && !contentId.isEmpty() && !seen.add(contentId)) continue;
            int t;
            try {
                t = Integer.parseInt(showTime);
            } catch (Exception e) {
                continue;
            }
            String color = "16777215";
            try {
                if (colorHex != null && colorHex.length() == 6) {
                    color = String.valueOf(Integer.parseInt(colorHex, 16));
                }
            } catch (Exception ignored) {
            }
            String text = escapeXmlText(content);
            out.add(new String[]{String.valueOf(t), color, text});
        }
    }

    /**
     * 按 URL 模板取弹幕（Fongmi `DanmakuApi.getCall` 的等价物）。
     *
     * <p>配置里的 {@code danmaku} 常写成
     * {@code http://127.0.0.1:2525/danmu?name={name}&epid={episode}}，
     * 替换占位符后**直接 GET**，而不是当弹弹play 的 host 去拼三段式路径
     * （那样会拼出 `...?name=..{episode}/api/v2/search/anime` 这种垃圾 URL）。
     *
     * <p>两种返回都认：
     * <ul>
     *   <li>直接是 XML（含 {@code <d }）→ 用它</li>
     *   <li>候选列表 JSON（{@code [{"name","url"}]}）→ 顺着前几个 url 取 XML</li>
     * </ul>
     */
    private static String fetchDanmakuByTemplate(String tpl, String name, int episode) {
        try {
            String url = tpl
                    .replace("{name}", URLEncoder.encode(name, "UTF-8"))
                    .replace("{episode}", String.valueOf(episode))
                    .replace("{epid}", String.valueOf(episode));
            String body = fetchText(url, 12_000, DANMAKU_REFERER);
            if (body == null || body.isEmpty()) return null;
            if (body.contains("<d ")) return body;
            String t = body.trim();
            if (!t.startsWith("[")) return null;
            /*
             * Fongmi 两段式：先给候选列表，再逐个取 XML。
             *
             * ★ 但不取第一个 —— 实测 danmu_api 对「三体」给的首个候选是
             * 「我的三体 第四季(2024)」（另一部片子，只有 10 条），而正片
             * 「三体(2022)」排在第 2 个（上万条）。Fongmi 的 search() 直接
             * findFirst()，所以它也会挑错；这里按片名匹配优先。
             */
            JSONArray arr = new JSONArray(t);
            /*
             * 候选打分（越小越好）：
             *   0 = 候选名**以片名开头**（"三体(2022)"）
             *   1 = 只是**包含**片名（"我的三体 第四季" —— 实测会抢走 0 分位）
             *   2 = 都不沾
             * 同分时名字更短的优先（剧名后面挂的字越少越可能是正片）。
             */
            String want = name.replace(" ", "").trim();
            int best = 0;
            int bestScore = Integer.MAX_VALUE;
            int bestLen = Integer.MAX_VALUE;
            for (int i = 0; i < Math.min(arr.length(), 8); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String cn = o.optString("name", "").replace(" ", "").trim();
                if (cn.isEmpty()) continue;
                int score = cn.startsWith(want) ? 0 : (cn.contains(want) ? 1 : 2);
                if (score < bestScore || (score == bestScore && cn.length() < bestLen)) {
                    best = i;
                    bestScore = score;
                    bestLen = cn.length();
                }
            }
            // 先试命中的那个，失败再依次试其余（最多 3 个，别把预算耗尽）
            int[] order = new int[Math.min(arr.length(), 3)];
            int n = 0;
            order[n++] = best;
            for (int i = 0; i < Math.min(arr.length(), 3) && n < order.length; i++) {
                if (i != best) order[n++] = i;
            }
            for (int k = 0; k < n; k++) {
                JSONObject o = arr.optJSONObject(order[k]);
                if (o == null) continue;
                String target = o.optString("url", "").trim();
                if (target.isEmpty()) continue;
                String xml = fetchText(target, 15_000, DANMAKU_REFERER);
                if (xml != null && xml.contains("<d ")) return xml;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String fetchDanmakuByProtocol(String host, String name, int episode) {
        try {
            // 1) 搜动画，关键词做几种归一化后逐个试（去括号年份 / 去第N季 / 去括号内容）
            JSONArray animes = null;
            for (String kw : keywordVariants(name)) {
                String u = host + "/api/v2/search/anime?keyword=" + URLEncoder.encode(kw, "UTF-8");
                String find = fetchText(u, 12_000, DANMAKU_REFERER);
                if (find == null || !find.trim().startsWith("{")) continue;
                JSONArray a = new JSONObject(find).optJSONArray("animes");
                if (a == null || a.length() == 0) continue;
                animes = a;
                break;
            }
            if (animes == null) return null;

            /*
             * ★ 不能直接取 animes[0]（2026-11-09 修）。
             *
             * danmu_api 的搜索结果是按 SOURCE_ORDER 合并的，第一条不一定是正片：
             * 实测「三体」第一条可能是「我的三体 第四季」（另一部片子，只有
             * 10 条），正片「三体(2022)」排在后面。这里按片名打分后再试：
             *   0 = 以片名开头，1 = 包含片名，2 = 不沾；同分取短名。
             * 先试最高分，失败再依次试最多 2 个备选（不把预算耗尽）。
             */
            int[] order = pickAnimeOrder(animes, name);
            for (int idx : order) {
                JSONObject o = animes.optJSONObject(idx);
                if (o == null) continue;
                String animeId = o.optString("animeId", "");
                if (animeId.isEmpty()) continue;
                String xml = fetchDanmakuForAnime(host, animeId, episode);
                if (xml != null) return xml;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取某部番的某一集弹幕（弹弹play 三段的后两段）。 */
    private static String fetchDanmakuForAnime(String host, String animeId, int episode) {
        try {
            String bg = fetchText(host + "/api/v2/bangumi/" + URLEncoder.encode(animeId, "UTF-8"),
                    12_000, DANMAKU_REFERER);
            if (bg == null) return null;
            JSONObject bo = new JSONObject(bg).optJSONObject("bangumi");
            if (bo == null) return null;
            JSONArray eps = bo.optJSONArray("episodes");
            if (eps == null || eps.length() == 0) return null;
            int idx = Math.max(0, Math.min(episode - 1, eps.length() - 1));
            int epId = eps.optJSONObject(idx).optInt("episodeId", -1);
            if (epId < 0) return null;
            String xml = fetchText(host + "/api/v2/comment/" + epId
                            + "?format=xml&withRelated=true&chConvert=1",
                    20_000, DANMAKU_REFERER);
            if (xml == null || !xml.contains("<d ") || !xml.contains("</i>")) return null;
            return xml;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 对搜索结果按片名打分，返回最多 3 个候选下标（优先正片）。
     *
     * <p>打分规则与 {@link #fetchDanmakuByTemplate} 的候选打分保持一致，
     * 避免同一部片在两条聚合路径上选出不同结果。
     */
    private static int[] pickAnimeOrder(JSONArray animes, String name) {
        String want = name.replace(" ", "").trim();
        int n = Math.min(animes.length(), 8);
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> {
            String na = animeTitleOf(animes.optJSONObject(a)).replace(" ", "").trim();
            String nb = animeTitleOf(animes.optJSONObject(b)).replace(" ", "").trim();
            int sa = animeTitleScore(na, want);
            int sb = animeTitleScore(nb, want);
            if (sa != sb) return Integer.compare(sa, sb);
            return Integer.compare(na.length(), nb.length());
        });
        int m = Math.min(3, n);
        int[] out = new int[m];
        for (int i = 0; i < m; i++) out[i] = idx[i];
        return out;
    }

    /** dandanplay 搜索结果的标题字段（不同镜像可能用 animeTitle / title / name）。 */
    private static String animeTitleOf(JSONObject o) {
        if (o == null) return "";
        String t = o.optString("animeTitle", "");
        if (t.isEmpty()) t = o.optString("title", "");
        if (t.isEmpty()) t = o.optString("name", "");
        return t == null ? "" : t;
    }

    private static int animeTitleScore(String title, String want) {
        if (title == null || title.isEmpty() || want.isEmpty()) return 2;
        if (title.startsWith(want)) return 0;
        if (title.contains(want)) return 1;
        return 2;
    }

    /** 片名归一化变体（对应肥猫 m.o()） */
    private static List<String> keywordVariants(String name) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        set.add(name);
        set.add(name.replaceAll("[（(]?(?:19|20)\\d{2}[）)]?", "").trim());
        set.add(name.replaceAll("第\\s*\\d+\\s*季", "").trim());
        set.add(name.replaceAll("[（(].*?[）)]", "").trim());
        set.remove("");
        return new ArrayList<>(set);
    }

    // ---------------------------------------------------------------- policy

    /**
     * 客户端偏好。目前只存安全 DNS，持久化到 host/policy.json，
     * 这样重启后端不用前端重新下发。
     */
    /**
     * 策略文件位置。
     *
     * <p>v1 用 {@code new File(user.dir, "policy.json")}，容器里工作目录是 {@code /}，
     * 配置会写到根目录、容器重建即丢。改用 {@link core.DataPaths}：
     * {@code MEOW_DATA} 环境变量 &gt; {@code --data-dir=} &gt; {@code ./data}。
     */
    private static java.io.File policyFile() {
        return core.DataPaths.file("policy.json");
    }

    private static JSONObject loadPolicy() {
        try {
            String s = core.DataPaths.read("policy.json");
            if (s == null || s.trim().isEmpty()) return new JSONObject();
            return new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void savePolicy(JSONObject p) {
        try {
            core.DataPaths.write("policy.json", p.toString(2));
            /*
             * 缓存 TTL 是启动时读一次存进 volatile 的（避免每次 put 都读盘）。
             * 这里改完 policy 必须立刻重算，否则用户在设置页调了
             * "缓存多久"要重启后端才生效 —— 那是典型的"控件没有真实行为"。
             */
            SearchCache.reloadTtl();
        } catch (Exception ignored) {
        }
    }

    private static void handlePolicy(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            /*
             * ★ 回给前端前把 authToken 摘掉（2026-10-30 安全审计 H15 实测）。
             *
             * 原来 GET 是原样 `loadPolicy().toString()`，于是**任何能连上端口
             * 的人**（GET 不做鉴权）都能读到 authToken 本身 ——
             * 令牌保护了写操作，却把它自己明文广播出去，等于没保护。
             *
             * 前端也不需要这个值：设置页只显示"是否要求令牌"，改令牌走
             * POST（那个需要令牌）。所以这里只回 authRequired。
             */
            JSONObject p = loadPolicy();
            boolean authOn = !authTokenExpected().isEmpty();
            p.remove("authToken");
            p.put("authRequired", authOn);
            sendJson(ex, 200, p.toString());
            return;
        }
        String body = read(ex.getRequestBody());
        try {
            JSONObject in = body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
            JSONObject cur = loadPolicy();
            java.util.Iterator<String> it = in.keys();
            while (it.hasNext()) {
                String k = it.next();
                cur.put(k, in.opt(k));
            }
            savePolicy(cur);
            sendJson(ex, 200, cur.toString());
        } catch (Exception e) {
            sendJson(ex, 400, new JSONObject().put("code", -1)
                    .put("msg", "bad policy: " + e).toString());
        }
    }

    // ---------------------------------------------------------------- dns

    /**
     * 安全 DNS（DoH）解析。
     *
     * 走 DoH 而不是本地 DNS，是因为本机常挂 TUN 代理，本地解析会拿到
     * fake-ip（198.18.0.0/15），此时视频 CDN 的连接出口会和源站签发的
     * 客户端 IP 不一致，触发 "the source ip is forbidden"。用 DoH 拿到
     * 真实 IP 后可以固定连接，避开 fake-ip。
     *
     * GET /api/dns/resolve?host=xxx[&provider=ali|dnspub|google]
     */
    private static final Map<String, String> DOH_PROVIDERS = new LinkedHashMap<>();
    static {
        DOH_PROVIDERS.put("ali", "https://223.5.5.5/resolve");
        DOH_PROVIDERS.put("dnspub", "https://doh.pub/dns-query");
        DOH_PROVIDERS.put("360", "https://doh.360.cn/resolve");
        DOH_PROVIDERS.put("google", "https://dns.google/resolve");
        DOH_PROVIDERS.put("cloudflare", "https://cloudflare-dns.com/dns-query");
    }

    private static final Map<String, Object[]> DNS_CACHE = new ConcurrentHashMap<>();

    /**
     * 解析 A 记录。**实现已收口到 {@link core.Doh}**——原来这里有一份私有拷贝，
     * 而 {@code core.UrlSafety}（SSRF 判定）和 {@code core.Scrape}（刮削）各自
     * 直接用系统 DNS，于是系统 DNS 一坏，两处同时静默失败、只有视频代理是好的。
     * 保留这个方法只是为了不改调用点。
     */
    private static String dohResolve(String host, String provider) throws Exception {
        return core.Doh.resolve(host, provider);
    }

    private static void handleDnsResolve(HttpExchange ex) throws Exception {
        Map<String, String> q = query(ex);
        String host = q.getOrDefault("host", "");
        String provider = q.getOrDefault("provider", "ali");
        if (host.isEmpty()) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"host required\"}");
            return;
        }
        String key = provider + "|" + host;
        Object[] hit = DNS_CACHE.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - (Long) hit[1] < 300_000L) {
            sendJson(ex, 200, new JSONObject().put("host", host).put("ip", hit[0])
                    .put("provider", provider).put("cached", true).toString());
            return;
        }
        try {
            String ip = dohResolve(host, provider);
            if (ip == null) {
                sendJson(ex, 200, new JSONObject().put("host", host).put("ip", "")
                        .put("provider", provider).put("msg", "no A record").toString());
                return;
            }
            DNS_CACHE.put(key, new Object[]{ip, now});
            sendJson(ex, 200, new JSONObject().put("host", host).put("ip", ip)
                    .put("provider", provider).put("cached", false).toString());
        } catch (Exception e) {
            sendJson(ex, 200, new JSONObject().put("host", host).put("ip", "")
                    .put("msg", String.valueOf(e)).toString());
        }
    }

    // ---------------------------------------------------------------- stream

    /**
     * 打开视频连接。开启安全 DNS 时，先把域名解析成 DoH 拿到的真实 IP，
     * 再让 HttpURLConnection 直连该 IP（Host 头仍用域名，SNI 不变）。
     *
     * 这样做的原因：本机若挂 TUN 代理，系统解析会返回 198.18.x.x 的 fake-ip，
     * 视频 CDN 的实际出口 IP 与源站签发时的客户端 IP 不同，会被
     * "the source ip is forbidden" 拦掉。
     */
    /**
     * 把 URL 里的 Unicode 域名转成 punycode。
     *
     * <p>Java 的 {@code URL}/{@code URI} 对中文域名不会自动做 IDN 转换：
     * {@code new URL("http://肥猫.net").openConnection()} 实测直接
     * {@code SocketException: Unexpected end of file from server}，而
     * {@code http://xn--z7x900a.net} 正常 200。所有抓取入口统一在这里归一化，
     * 否则「肥猫/饭太硬」这类中文域名的配置永远报 502 fetch failed。
     */
    private static String idnUrl(String url) {
        return core.ConfigText.idnUrl(url);
    }

    private static HttpURLConnection openStream(String url, String referer, String ua)
            throws IOException {
        return openStream(url, referer, ua, loadPolicy().optBoolean("safeDns", false));
    }

    /** forceDoh=true 时无视 safeDns 开关强制走 DoH（弹幕抓取用） */
    private static HttpURLConnection openStream(String url, String referer, String ua,
                                                boolean forceDoh) throws IOException {
        url = idnUrl(url);
        JSONObject policy = loadPolicy();
        boolean safeDns = forceDoh || policy.optBoolean("safeDns", false);
        // provider 可以是内置名（ali/tencent/...），也可以是配置 doh 表里给的完整 URL。
        // 用户明确指定 DoH 就说明他所在网络下公共 DoH 可能不通，优先用他的。
        String provider = policy.optString("dnsProvider", "").trim();
        if (provider.isEmpty()) {
            String fromTable = dohProviderFromTable();
            provider = fromTable.isEmpty() ? "ali" : fromTable;
        }
        URL u = new URL(url);
        // hosts 表覆盖（仅 http，理由见 tableHostOverride）
        String hostHeader = "";
        if ("http".equalsIgnoreCase(u.getProtocol())) {
            String[] mapped = tableHostOverride(u);
            if (mapped != null) {
                u = new URL(mapped[0]);
                hostHeader = mapped[1];
            }
        }
        if (!safeDns || !"https".equalsIgnoreCase(u.getProtocol())) {
            HttpURLConnection plain = (HttpURLConnection) u.openConnection();
            if (!hostHeader.isEmpty()) plain.setRequestProperty("Host", hostHeader);
            return plain;
        }
        try {
            String ip = dohResolve(u.getHost(), provider);
            if (ip == null || ip.isEmpty()) return (HttpURLConnection) u.openConnection();
            /*
             * ★ 保留域名做 TLS SNI，不能把 URL 换成纯 IP 了事。
             *
             * 踩过（2026-10-30，实测）：只改 URL + 设 Host 头时，JVM 的 TLS
             * 层拿到的连接目标是 IP，于是 ClientHello 里的 SNI 也是 IP。
             * Cloudflare 这类前置（gh-proxy.com 就是）看到 IP 形式的 SNI 直接回
             * `SSLHandshakeException: Received fatal alert: handshake_failure`，
             * 表现为"配置导入永远拉取失败"，而本机 curl 完全正常——
             * 因为 curl 走系统 DNS，SNI 是对的。
             *
             * 正解是自定义 SSLSocketFactory：TCP 连 IP，握手时把 SNI 与
             * 主机名校验都用原域名。这样 DoH 的意义（绕开被污染的
             * 系统 DNS）和 TLS 的完整性校验都还在。
             */
            javax.net.ssl.SSLSocketFactory base =
                    (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            final String hostName = u.getHost();
            final int port = u.getPort() > 0 ? u.getPort() : 443;
            final javax.net.ssl.SSLSocketFactory sniFactory =
                    new SniSocketFactory(base, hostName, port);
            String mapped = u.getProtocol() + "://" + ip
                    + (u.getPort() > 0 ? ":" + u.getPort() : "") + u.getFile();
            javax.net.ssl.HttpsURLConnection c =
                    (javax.net.ssl.HttpsURLConnection) new URL(mapped).openConnection();
            c.setSSLSocketFactory(sniFactory);
            c.setRequestProperty("Host", hostName);
            /*
             * 不再设自定义 HostnameVerifier：主机名校验已交给
             * SniSocketFactory 里的 endpointIdentificationAlgorithm=HTTPS。
             * 这里再设一个会覆盖掉 JSSE 的检查（见 SniSocketFactory.wrap 注释）。
             */
            return c;
        } catch (Exception e) {
            return (HttpURLConnection) u.openConnection();
        }
    }

    /**
     * 连 IP、但握手时用指定域名的 SNI。
     *
     * <p>只包装 {@code createSocket}：默认工厂已经建好到 IP 的连接，
     * 这里在握手前把 {@code SSLParameters.serverNames} 换成真实域名。
     * 少了这一步，Cloudflare 前置会直接拒绝（见调用点的实测记录）。
     */
    private static final class SniSocketFactory extends javax.net.ssl.SSLSocketFactory {
        private final javax.net.ssl.SSLSocketFactory base;
        private final javax.net.ssl.SNIHostName sni;
        private final int port;

        SniSocketFactory(javax.net.ssl.SSLSocketFactory base, String host, int port) {
            this.base = base;
            this.sni = new javax.net.ssl.SNIHostName(host);
            this.port = port;
        }

        private java.net.Socket wrap(java.net.Socket s) throws java.io.IOException {
            javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) s;
            javax.net.ssl.SSLParameters p = ssl.getSSLParameters();
            p.setServerNames(java.util.Collections.singletonList(sni));
            /*
             * ★ 让 JSSE 自己做主机名校验（2026-10-30 安全审计 H12）。
             *
             * 原来靠调用方 `setHostnameVerifier((h,session) -> { ...verify(); return true; })`
             * —— 默认 verifier 的返回值被丢弃、无条件 return true，
             * 而 JDK 的默认 verifier **恒返回 false**、安装自定义 verifier 又会
             * 关掉 SSLSocket 层的自动校验。净效果是**完全没有主机名校验**：
             * 任何持有任意有效证书的人都能冒充视频源/图床（实测连接
             * Cloudflare IP 且 Host 填一个不相干域名，TLS 与 HTTP 都通）。
             *
             * endpointIdentificationAlgorithm=HTTPS 是 JSSE 的标准做法：
             * 握手时按服务器证书的 SAN 校验 `host`，不通过就抛
             * SSLHandshakeException。设了它之后**必须去掉**自定义 verifier
             * （否则两套机制打架）。
             */
            p.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(p);
            return ssl;
        }

        @Override
        public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose)
                throws java.io.IOException {
            return wrap(base.createSocket(s, host, port, autoClose));
        }

        @Override
        public java.net.Socket createSocket(String host, int port)
                throws java.io.IOException {
            return wrap(base.createSocket(host, port));
        }

        @Override
        public java.net.Socket createSocket(String host, int port,
                                            java.net.InetAddress localHost, int localPort)
                throws java.io.IOException {
            return wrap(base.createSocket(host, port, localHost, localPort));
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress host, int port)
                throws java.io.IOException {
            return wrap(base.createSocket(host, port));
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress address, int port,
                                            java.net.InetAddress localAddress, int localPort)
                throws java.io.IOException {
            return wrap(base.createSocket(address, port, localAddress, localPort));
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return base.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return base.getSupportedCipherSuites();
        }
    }

    /**
     * 视频代理。解决：
     *  - 源站 Referer/UA 校验（浏览器改不了）
     *  - CORS
     *  - m3u8 内部分片 URL 需要同样代理
     * 支持 Range（拖动进度条必需）。
     */
    /** 代理最多跟随几跳重定向；超过就报错，避免被 302 环拖住。 */
    private static final int MAX_PROXY_REDIRECTS = 5;

    /**
     * 同时在跑的分片代理上限。
     *
     * <p>8 是"够 HLS 并行拉片、又给 API 留余量"的经验值：Chrome 同源并发是 6，
     * 起播瞬间清单 + 分片 + 弹幕/封面会一起打进来。超过就直接 503——
     * 让播放器重试，而不是让所有请求在无界队列里排队等 45 秒。
     */
    private static final java.util.concurrent.Semaphore STREAM_SLOTS =
            new java.util.concurrent.Semaphore(8, true);

    // ---------------------------------------------------------------- 广告分片

    /** 一份改写后的播放清单，附带护栏要用的计数。 */
    private static final class PlaylistOut {
        String body = "";
        /** 保留下来的分片数（非 # 行） */
        int segs;
        /** 保留分片的总时长（秒） */
        double keptDur;
    }

    /**
     * 规则里纯数字的条目 = 要整对删除的 EXTINF 时长；含 {@code #EXT} 的条目
     * 是整段正则，由 {@link #applyAdTextRules} 处理。
     *
     * <p><b>为什么不能复用 {@code ruleVerdict}</b>：那张表在嗅探链路里是 Fongmi
     * Sniffer 语义（{@code mediaUrl.contains(regex)}，用来否决"这个 URL 像不像视频"）。
     * 配置作者写的是**播放器语义**——同一张表两套语义必须走两条代码路径，
     * 否则 {@code "15.1666"} 这种时长规则会被当成 URL 子串，永远匹配不到。
     */
    private static java.util.Set<Double> adDurations(SniffRule r) {
        java.util.Set<Double> out = new java.util.HashSet<>();
        if (r == null) return out;
        for (String rx : r.regex) {
            String s = rx == null ? "" : rx.trim();
            if (s.isEmpty() || s.contains("#")) continue;
            try {
                out.add(Double.parseDouble(s));
            } catch (NumberFormatException ignored) {
                /* 既不是时长也不是 #EXT：不是给我们用的，跳过 */
            }
        }
        return out;
    }

    /**
     * 整段正则（含 {@code #EXT} 的条目）。
     *
     * <p>单条写错只跳过它，不能中断整份清单：配置来自网上，一条坏正则
     * 不该让用户整集播不了（踩过的同类形态见 `fmt` 表按行解析）。
     */
    private static String applyAdTextRules(String text, SniffRule r) {
        String out = text;
        if (r == null) return out;
        for (String rx : r.regex) {
            String s = rx == null ? "" : rx.trim();
            if (s.isEmpty() || !s.contains("#")) continue;
            try {
                out = java.util.regex.Pattern.compile(s, java.util.regex.Pattern.DOTALL)
                        .matcher(out).replaceAll("");
            } catch (Throwable t) {
                core.LogBuffer.warn("ads", "坏正则已跳过：" + core.LogBuffer.scrub(s));
            }
        }
        return out;
    }

    /**
     * 本清单适用哪条规则：m3u8 host 或任一分片 host 命中 hosts 表即算。
     *
     * <p>分片 host 也要参与匹配——配置里的 `ffzy`/`lz` 这类是 CDN 分片域名，
     * 而 m3u8 常在另一个域名上。只比清单 host 会让大部分规则永远不生效。
     */
    private static SniffRule playbackAdRule(String playlistUrl, String text, String baseUrl) {
        java.util.List<SniffRule> rules = loadRules();
        if (rules.isEmpty()) return null;
        StringBuilder hosts = new StringBuilder();
        try {
            String h = new URL(playlistUrl).getHost();
            if (h != null) hosts.append(h.toLowerCase());
        } catch (Exception ignored) {
            /* 相对地址或解析失败：只靠分片 host 也能判 */
        }
        int distinct = 0;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            try {
                String h = new URL(resolveUrl(baseUrl, t)).getHost();
                if (h != null && hosts.indexOf(h.toLowerCase()) < 0) {
                    hosts.append(',').append(h.toLowerCase());
                    // 够判定了就停：上千个分片全解析一遍纯属浪费
                    if (++distinct >= 8) break;
                }
            } catch (Exception ignored) {
            }
        }
        String h = hosts.toString().toLowerCase();
        for (SniffRule r : rules) {
            for (String want : r.hosts) {
                if (matchHost(h, want)) return r;
            }
        }
        return null;
    }

    private static double playlistDuration(String text) {
        double sum = 0;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("#EXTINF:")) continue;
            int comma = t.indexOf(',');
            String d = comma > 8 ? t.substring(8, comma) : t.substring(8);
            try {
                sum += Double.parseDouble(d.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return sum;
    }

    private static int playlistSegments(String text) {
        int n = 0;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) n++;
        }
        return n;
    }

    /**
     * 把清单里的分片/KEY 地址改写成代理地址，并按需跳过广告分片。
     *
     * <p>必须是"索引循环 + EXTINF 预读"：广告规则删的是
     * {@code #EXTINF} **和它下面那条 URI** 一对，单遍无状态循环做不到。
     *
     * @param adDur 要整对删除的 EXTINF 时长；null = 不按规则删
     */
    private static PlaylistOut buildProxiedPlaylist(String text, String baseUrl, String referer,
                                                    String ua, String selfBase,
                                                    String extraHeaders,
                                                    java.util.Set<Double> adDur) throws IOException {
        PlaylistOut out = new PlaylistOut();
        StringBuilder sb = new StringBuilder(text.length() + 256);
        boolean skipUri = false;
        double pendingDur = 0;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                if (t.startsWith("#EXTINF:") && adDur != null && !adDur.isEmpty()) {
                    int comma = t.indexOf(',');
                    String d = comma > 8 ? t.substring(8, comma) : t.substring(8);
                    boolean hit = false;
                    try {
                        double dur = Double.parseDouble(d.trim());
                        for (double want : adDur) {
                            // 0.02 与上游一致：EXTINF 常带 6 位小数，精确比较永远不相等
                            if (Math.abs(dur - want) <= 0.02) {
                                hit = true;
                                break;
                            }
                        }
                    } catch (NumberFormatException ignored) {
                    }
                    if (hit) {
                        skipUri = true;
                        continue;
                    }
                    pendingDur = parseExtinf(t);
                }
                // EXT-X-KEY / EXT-X-MAP 里的 URI 也要代理
                if (t.contains("URI=\"")) {
                    int a = t.indexOf("URI=\"") + 5;
                    int b = t.indexOf('"', a);
                    if (b > a) {
                        String inner = t.substring(a, b);
                        // 必须按 URL 规则解析：直接 baseUrl+inner 在 inner 以 "/" 开头时
                        // 会把路径拼坏（实测荐片的 AES key 变成 normal/1649851//api/...）
                        String abs = resolveUrl(baseUrl, inner);
                        t = t.substring(0, a)
                                + proxyUrl(abs, referer, ua, selfBase, extraHeaders)
                                + t.substring(b);
                    }
                }
                sb.append(t).append('\n');
                continue;
            }
            if (skipUri) {
                skipUri = false;
                pendingDur = 0;
                continue;
            }
            String abs = resolveUrl(baseUrl, t);
            // ads 表过滤（默认关闭，policy.filterAds=true 才生效）。
            //
            // 澄清语义：上游的 `ads` 只用于 WebView 嗅探器防广告跳转
            //（WebViewRules.shouldIgnore），不是媒体黑名单。拿它过滤分片
            // 是我们自己的用法——配置里若把整条 CDN 写进 ads 会误杀，
            // 所以必须显式开启，不能默认套用。
            if (filterAdsEnabled() && isAdHost(abs)) {
                pendingDur = 0;
                continue;
            }
            out.keptDur += pendingDur;
            pendingDur = 0;
            out.segs++;
            sb.append(proxyUrl(abs, referer, ua, selfBase, extraHeaders)).append('\n');
        }
        out.body = sb.toString();
        return out;
    }

    private static double parseExtinf(String t) {
        int comma = t.indexOf(',');
        String d = comma > 8 ? t.substring(8, comma) : t.substring(8);
        try {
            return Double.parseDouble(d.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- 请求头透传

    /**
     * 由代理自己决定的头：调用方给了也不能覆盖。
     *
     * <p>`Range` 尤其不能透传——分片请求的 Range 由播放器每一跳各自给，
     * 透传一个旧的会让上游返回 416。
     */
    private static final java.util.Set<String> FORBIDDEN_HEADERS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "host", "connection", "content-length", "content-type",
                    "transfer-encoding", "range", "accept-encoding", "te",
                    "upgrade", "proxy-authorization", "proxy-connection"));

    /**
     * 解析 `headers` 参数（JSON 对象）。
     *
     * <p>存在的原因：源站的鉴权头不止 Referer/UA，Cookie、Origin、自定义 token
     * 都是常见形态。原生播放器（ExoPlayer）拿的是**全部头**，代理只转
     * Referer/UA 的话这类源会在 Web 端永久 403——"原生能播、我们播不了"。
     */
    private static java.util.Map<String, String> parseHeaderMap(String json) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONObject o = new JSONObject(json);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String kl = k == null ? "" : k.trim().toLowerCase();
                if (kl.isEmpty() || FORBIDDEN_HEADERS.contains(kl)) continue;
                String v = o.optString(k, "");
                // 换行等于请求头注入，直接丢
                if (v == null || v.isEmpty() || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) continue;
                out.put(k.trim(), v);
                if (out.size() >= 24) break;
            }
        } catch (Throwable ignored) {
            /* 坏 JSON 就当没有额外头，不影响正常播放 */
        }
        return out;
    }

    /** 规范化成可回写进分片 URL 的 JSON 串；太长/为空返回 ""。 */
    private static String normalizeExtraHeaders(String raw) {
        java.util.Map<String, String> m = parseHeaderMap(raw);
        if (m.isEmpty()) return "";
        try {
            JSONObject o = new JSONObject();
            for (java.util.Map.Entry<String, String> e : m.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
            String s = o.toString();
            return s.length() > 2048 ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static void applyExtraHeaders(HttpURLConnection c, java.util.Map<String, String> h) {
        for (java.util.Map.Entry<String, String> e : h.entrySet()) {
            try {
                c.setRequestProperty(e.getKey(), e.getValue());
            } catch (Throwable ignored) {
                /* 非法头名（含空格等）跳过，不影响其余头 */
            }
        }
    }

    private static void handleStream(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String url = q.get("url");
        if (url == null || url.isEmpty()) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"url required\"}");
            return;
        }
        // hosts 表映射必须在 SSRF 校验**之前**：校验的应该是真正要连的地址。
        // 反过来的话 `fake.local=192.168.1.2` 这种映射永远过不了校验
        // （域名解析不出来 → 判不安全），表现为"配了 hosts 却没生效"。
        String[] mappedEntry = mapHost(url);
        url = mappedEntry[0];
        // SSRF 入口校验：目标完全由调用方给，不挡就等于开放正向代理。
        // 用宽松档（允许局域网），因为自建片源在 192.168/10.x 上是真实场景；
        // 回环与链路本地（云元数据 169.254.169.254）仍然拒绝。
        if (!core.UrlSafety.isSafeMediaUrl(url)) {
            // 记一条 warn：诊断层要靠它判「分片被 SSRF 防线挡了」。
            // url 先 scrub 再落库，避免把带 token 的源地址写进日志面板。
            core.LogBuffer.warn("stream", "blocked ssrf " + core.LogBuffer.scrub(url));
            sendJson(ex, 403, "{\"code\":-1,\"msg\":\"url not allowed\"}");
            return;
        }
        String referer = q.getOrDefault("referer", "");
        String ua = q.getOrDefault("ua", "");
        // headers 表只做兜底：调用方（spider → 前端 → 这里）给的永远优先
        if (referer.isEmpty()) referer = defaultHeader("Referer");
        if (ua.isEmpty()) ua = defaultHeader("User-Agent");
        if (ua.isEmpty()) ua = "Mozilla/5.0";
        String range = ex.getRequestHeaders().getFirst("Range");
        /*
         * 额外请求头（JSON）。Referer/UA 单独走参数是为了兼容老地址；
         * 其余（Cookie / Origin / 自定义 token）从这里透传。
         * 只保留白名单外的普通头，长度上限 2KB，换行直接丢。
         */
        String extraHeaders = normalizeExtraHeaders(q.get("headers"));
        java.util.Map<String, String> extraMap = parseHeaderMap(extraHeaders);

        // 手动跟随重定向，而不是让 HttpURLConnection 自己跟：
        // 自动跟随的连接目标不经任何检查，一个公网 URL 跳 302 到
        // http://169.254.169.254/ 就能读到云凭据。每一跳都用严格档复核。
        HttpURLConnection c;
        int code;
        String current = url;
        int hop = 0;
        try {
            while (true) {
                String[] mapped = mapHost(current);
                c = openStream(mapped[0], referer, ua);
                if (!mapped[1].isEmpty()) c.setRequestProperty("Host", mapped[1]);
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15_000);
                c.setReadTimeout(30_000);
                c.setRequestProperty("User-Agent", ua);
                if (!referer.isEmpty()) c.setRequestProperty("Referer", referer);
                if (range != null) c.setRequestProperty("Range", range);
                applyExtraHeaders(c, extraMap);

                code = c.getResponseCode();
                // 安全 DNS 把域名换成 IP 直连，某些 CDN（如 mux.dev）依赖 SNI 做路由，
                // 会返回 421。这种情况下退回正常解析重试一次，避免安全 DNS 反而弄坏别的站点。
                if (code == 421 || code == 400 || code == 502 || code == 503) {
                    JSONObject policy = loadPolicy();
                    if (policy.optBoolean("safeDns", false)) {
                        try {
                            c.disconnect();
                        } catch (Exception ignored) {
                        }
                        c = (HttpURLConnection) new URL(current).openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setConnectTimeout(15_000);
                        c.setReadTimeout(30_000);
                        c.setRequestProperty("User-Agent", ua);
                        if (!referer.isEmpty()) c.setRequestProperty("Referer", referer);
                        if (range != null) c.setRequestProperty("Range", range);
                        applyExtraHeaders(c, extraMap);
                        try {
                            code = c.getResponseCode();
                        } catch (IOException e) {
                            code = 502;
                        }
                    }
                }

                boolean redirect = code == 301 || code == 302 || code == 303
                        || code == 307 || code == 308;
                if (!redirect) break;

                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) {
                    sendJson(ex, 502, "{\"code\":-1,\"msg\":\"redirect without location\"}");
                    return;
                }
                if (hop++ >= MAX_PROXY_REDIRECTS) {
                    sendJson(ex, 502, "{\"code\":-1,\"msg\":\"too many redirects\"}");
                    return;
                }
                String next = resolveUrl(current, loc);
                if (!core.UrlSafety.isSafeHttpUrl(next)) {
                    System.out.println("[proxy] blocked redirect " + current + " -> " + next);
                    core.LogBuffer.warn("proxy", "blocked redirect " + core.LogBuffer.scrub(current) + " -> " + core.LogBuffer.scrub(next));
                    sendJson(ex, 403, "{\"code\":-1,\"msg\":\"redirect target not allowed\"}");
                    return;
                }
                current = next;
            }
        } catch (IOException e) {
            sendJson(ex, 502, "{\"code\":-1,\"msg\":" + JSONObject.quote(String.valueOf(e)) + "}");
            return;
        }
        Headers out = ex.getResponseHeaders();
        String ct = c.getContentType();
        // 用最终地址（跟随重定向之后）判断与解析相对路径：
        // 源站常见 302 到另一个主机/目录，拿最初的 url 当基准会把分片拼错。
        String lower = current.toLowerCase();
        boolean looksM3u8 = lower.contains(".m3u8") || (ct != null && ct.contains("mpegurl"));

        if (looksM3u8 && code < 400) {
            // m3u8：读出内容，把分片/子清单 URL 改写成本代理
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            c.disconnect();

            String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            // 有些"源"返回 HTML 或广告占位（甚至 ASCII 艺术），不是真 m3u8。
            // 真清单必须以 #EXTM3U 开头；否则原样透传，避免把垃圾当清单重写。
            if (!text.trim().startsWith("#EXTM3U")) {
                byte[] raw = bos.toByteArray();
                out.set("Content-Type", ct == null ? "text/plain; charset=utf-8" : ct);
                applyCors(ex);
                ex.sendResponseHeaders(200, raw.length);
                ex.getResponseBody().write(raw);
                ex.close();
                return;
            }

            String baseUrl = current.substring(0, current.lastIndexOf('/') + 1);
            // 分片改写必须带外部可达的绝对前缀，否则播放器会把它拼到源站域名上
            String selfBase = baseUrlFor(ex);

            PlaylistOut po = buildProxiedPlaylist(text, baseUrl, referer, ua, selfBase,
                    extraHeaders, null);

            /*
             * 广告分片（TVBox `rules` 表的**播放器语义**）。
             *
             * 只有显式开了 filterAds 才动：规则来自网上，配置作者写的正则
             * 一旦过宽就能把整集删空，所以默认不启用。
             *
             * 两条护栏缺一不可（配置写错是最常见的广告 bug）：
             *   · 一个分片都不剩 → 回退原文
             *   · 删掉超过一半时长 → 回退原文
             * 没有护栏时用户看到的是 hls.js 的"没有可播档位"，
             * 根本不会想到是去广告规则干的。
             */
            if (filterAdsEnabled()) {
                SniffRule ad = playbackAdRule(current, text, baseUrl);
                if (ad != null) {
                    String work = applyAdTextRules(text, ad);
                    PlaylistOut cut = buildProxiedPlaylist(work, baseUrl, referer, ua, selfBase,
                            extraHeaders, adDurations(ad));
                    double orig = playlistDuration(text);
                    int removed = playlistSegments(text) - cut.segs;
                    if (cut.segs == 0 || (orig > 0 && cut.keptDur < orig * 0.5)) {
                        core.LogBuffer.warn("ads", "规则「" + ad.name + "」删得过多（剩 "
                                + cut.segs + " 片 / " + Math.round(cut.keptDur)
                                + "s），已回退原清单");
                    } else if (removed > 0) {
                        po = cut;
                        core.LogBuffer.info("ads", "规则「" + ad.name + "」删除 " + removed
                                + " 个分片（保留 " + Math.round(cut.keptDur) + "s）");
                    }
                }
            }
            byte[] data = po.body.getBytes(StandardCharsets.UTF_8);
            out.set("Content-Type", "application/vnd.apple.mpegurl");
            applyCors(ex);
            ex.sendResponseHeaders(200, data.length);
            ex.getResponseBody().write(data);
            ex.close();
            return;
        }

        // 普通二进制流（mp4 / ts / key）：透传，保留 Range 语义
        //
        // ★ 「假流」识别：请求的是视频分片，上游却回 image/*。
        //
        // 实测（AppRJ 的 603 个分片）：清单本身完全合法，但每个 .ts 打开都是
        // 一张 PNG 占位图（首字节 0x89）。播放器侧表现是"能加载清单、起播就
        // 解码失败"，用户看到的只是一句"解码错误"，完全不知道是源站在放广告图。
        // 这里直接判死并给出可读原因，前端据此把这条线路记为失败、自动换线
        // （比让它慢慢解码失败快得多，也不会白占带宽）。
        //
        // 判据用请求的扩展名而不是"猜内容"：只有 .ts/.m4s/.mp4 这类**明确是视频**
        // 的请求才做这个检查，避免把正常的海报请求误判。
        if (ct != null && ct.toLowerCase().startsWith("image/") && looksVideoUrl(current)) {
            core.LogBuffer.warn("proxy", "fake stream: " + core.LogBuffer.scrub(current)
                    + " -> " + ct);
            // 手工拼 JSON：android-json 的 put 抛受检 JSONException，
            // 在这条 path 上再加 try/catch 只会把代码弄乱（sendError 同理）。
            sendText(ex, 502, "{\"code\":-1,\"fakeStream\":true,\"msg\":"
                    + JSONObject.quote("该线路返回的不是视频（" + ct + "），请换线路") + "}",
                    "application/json; charset=utf-8");
            c.disconnect();
            return;
        }
        applyCors(ex);
        out.set("Access-Control-Expose-Headers",
                "Content-Length,Content-Range,Accept-Ranges,"
                        + "X-Danmaku-Source,X-Danmaku-Count,X-Danmaku-Empty");
        if (ct != null) out.set("Content-Type", ct);
        String cr = c.getHeaderField("Content-Range");
        if (cr != null) out.set("Content-Range", cr);
        out.set("Accept-Ranges", "bytes");
        String cl = c.getHeaderField("Content-Length");
        long len = cl == null ? -1 : Long.parseLong(cl);
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ex.sendResponseHeaders(code, len > 0 ? len : 0);
        OutputStream os = ex.getResponseBody();
        if (in != null) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
            in.close();
        }
        os.close();
        c.disconnect();
    }

    /**
     * URL 是否明确指向视频分片。
     *
     * <p>只看路径扩展名，不看 query（分片地址普遍带 `?sign=…&t=…`）。
     * 用于「假流检测」：只有可能是视频的请求才值得校验上有没有谎报类型。
     */
    private static boolean looksVideoUrl(String url) {
        if (url == null) return false;
        String path = url;
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);
        String p = path.toLowerCase();
        return p.endsWith(".ts") || p.endsWith(".m4s") || p.endsWith(".mp4")
                || p.endsWith(".m4v") || p.endsWith(".flv") || p.endsWith(".mkv")
                || p.endsWith(".webm") || p.endsWith(".mov") || p.endsWith(".aac")
                || p.endsWith(".m4a") || p.endsWith(".mp3");
    }

    /**
     * 把清单里的相对 URI 解析成绝对 URL。
     *
     * 不能直接 baseUrl + uri：当 uri 以 "/" 开头时（很多源这样写密钥地址），     * 必须替换掉整个 path，否则会拼成 `normal/1649851//api/v2/...` 这种坏路径。
     */
    private static String resolveUrl(String baseUrl, String uri) {
        if (uri == null || uri.isEmpty()) return baseUrl;
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri;
        try {
            // 手工切出 origin，避免 URI.create 因为清单 URL 带空格/中文而失败
            int sep = baseUrl.indexOf("://");
            if (sep < 0) return baseUrl + uri;
            int slash = baseUrl.indexOf('/', sep + 3);
            String origin = slash < 0 ? baseUrl : baseUrl.substring(0, slash);
            if (uri.startsWith("/")) return origin + uri;

            // 相对路径：取 base 的目录部分（去掉 ?query 和最后一段）
            String dir = baseUrl;
            int q = dir.indexOf('?');
            if (q >= 0) dir = dir.substring(0, q);
            int lastSlash = dir.lastIndexOf('/');
            if (lastSlash > sep + 2) dir = dir.substring(0, lastSlash + 1);
            else dir = origin + "/";
            return dir + uri;
        } catch (Exception e) {
            return baseUrl + uri;
        }
    }

    /**
     * 把绝对地址包成"经本代理"的地址。
     *
     * <p><b>必须是绝对地址</b>（实测踩过）：早期返回相对路径
     * {@code /api/proxy/stream?...}，而 m3u8 里的分片行会被播放器
     * <b>以清单自身的 URL 为基准</b>解析，于是
     * {@code https://cdn.example/movie/index.m3u8} 里的
     * {@code /api/proxy/stream?...} 被解析成
     * {@code https://cdn.example/api/proxy/stream?...} —— 打到源站必然 404。
     * 表现是"清单能拿到、一播就失败"，且多个源同时中招
     * （Gulu / AppRJ / Jianpian 实测均如此）。
     *
     * @param selfBase 外部可达的本服务基址，如 {@code http://192.168.1.10:30010}
     * @param extraHeaders 额外请求头的 JSON 串（可空）；分片请求必须一起带上，
     *                     否则首片用 Cookie 拿到了、后续分片又 403
     */
    private static String proxyUrl(String abs, String referer, String ua, String selfBase,
                                   String extraHeaders)
            throws IOException {
        StringBuilder sb = new StringBuilder(selfBase).append("/api/proxy/stream?url=")
                .append(URLEncoder.encode(abs, "UTF-8"));
        if (referer != null && !referer.isEmpty())
            sb.append("&referer=").append(URLEncoder.encode(referer, "UTF-8"));
        if (ua != null && !ua.isEmpty())
            sb.append("&ua=").append(URLEncoder.encode(ua, "UTF-8"));
        if (extraHeaders != null && !extraHeaders.isEmpty())
            sb.append("&headers=").append(URLEncoder.encode(extraHeaders, "UTF-8"));
        return sb.toString();
    }

    // ---------------------------------------------------------------- image

    /**
     * 图片代理。
     *
     * CatVod 的 vod_pic 常见形态：
     *   https://img3.doubanio.com/xxx.jpg@Referer=https://api.douban.com/@User-Agent=Mozilla/5.0 ...
     * 即 URL 后面用 @ 挂自定义请求头。浏览器直接请求会被防盗链拦，所以这里
     * 解析出真实 URL 和 header 后代理，并做本地缓存。
     */
    // ---------------------------------------------------------------- webdav

    // ---------------------------------------------------------------- library

    // ---------------------------------------------------------------- cache

    /**
     * 正在合并的集（id → 开始时间）。
     *
     * <p>作用有二：① 同一集被两个请求同时导出时，第二个不要重复跑 ffmpeg
     * （媒体文件动辄几百 MB，两个进程同时写同一目录会互相拖慢甚至写坏）；
     * ② 已经合了很久还没完成，说明卡住了，告诉调用方稍后再试而不是一直等。
     *
     * <p>用 ConcurrentHashMap + 时间戳而不是锁：导出是长任务（可能几十秒），
     * 持锁会让其它集的导出全部排队。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> MERGING =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 超过这个时长还占着，视为上一次合并异常退出（进程被杀/断电），允许重新开始。 */
    private static final long MERGE_STALE_MS = 10 * 60 * 1000L;

    /**
     * ffmpeg 合并的超时（秒）。
     *
     * <p>只做 {@code -c copy} 不转码，速度是磁盘带宽级别：1 小时 1080p 约
     * 5–15 秒。给 3 分钟足够任何正常情况；超过说明卡住了，必须杀掉——
     * 否则请求永久挂起、后端线程被占死。
     */
    private static final int FFMPEG_TIMEOUT_SEC = 180;

    private static java.nio.file.Path dir(java.nio.file.Path p) {
        return p.getParent();
    }

    /** 真正跑 ffmpeg 的部分；调用方负责 MERGING 的登记与清理。 */
    /** 导出文件名：剧名 + 集名，去掉文件系统不允许的字符。 */
    /** 同名文件不覆盖：追加 (2)(3)…，避免用户导出两次把上次的删掉。 */
    /** RFC 5987 形式的中文文件名；同时给一个 ASCII 兜底名。 */
    // ---------------------------------------------------------------- scrape

    /**
     * 刮削：把片名换成海报与年份。
     *
     * <p>GET  {@code /api/scrape?title=白夜追凶&year=2017}  单条
     * <br>POST {@code /api/scrape}  {@code {"groups":[{title,year}]}}  批量
     * <br>DELETE {@code /api/scrape}                       清空缓存
     *
     * <p><b>失败必须退化成文件名</b>：内网/离线环境刮不到是常态，
     * 刮削只是"更好看"，不能变成"打不开"。任何异常都返回 ok=false 但保留 title。
     */
    /**
     * DLNA 投屏。
     *
     * <p>GET  {@code /api/cast}                          发现设备
     * <br>POST {@code /api/cast}  {action, device, url, title, seconds}
     *
     * <p><b>为什么不缓存设备列表</b>：电视刚开机时 SSDP 响应可能慢半拍，
     * 缓存住"上次的列表"会让用户找不到刚打开的设备。发现本身只要 2.5 秒。
     */
    private static void handleCast(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        Map<String, String> q = query(ex);
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            int timeout = 2500;
            try {
                timeout = Integer.parseInt(trimToEmpty(q.getOrDefault("timeout", "2500")));
            } catch (NumberFormatException ignored) {
            }
            List<core.Cast.Device> list = core.Cast.discover(Math.min(8000, Math.max(800, timeout)));
            sendJson(ex, 200, new JSONObject().put("ok", true)
                    .put("devices", core.Cast.toJson(list))
                    .put("count", list.size()).toString());
            return;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            sendJson(ex, 405, "{\"code\":-1,\"msg\":\"method not allowed\"}");
            return;
        }
        String body = read(ex.getRequestBody());
        JSONObject in = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        String action = trimToEmpty(in.optString("action", "play"));
        JSONObject devJson = in.optJSONObject("device");
        if (devJson == null) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"device required\"}");
            return;
        }
        core.Cast.Device d = core.Cast.fromJson(devJson);
        if (d.controlUrl.isEmpty()) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"device 缺少 controlUrl，请重新发现\"}");
            return;
        }
        JSONObject r;
        switch (action) {
            case "pause":
                r = core.Cast.pause(d);
                break;
            case "stop":
                r = core.Cast.stop(d);
                break;
            case "seek":
                r = core.Cast.seek(d, Math.max(0, in.optInt("seconds", 0)));
                break;
            default: {
                String url = trimToEmpty(in.optString("url", ""));
                if (url.isEmpty()) {
                    sendJson(ex, 400, "{\"code\":-1,\"msg\":\"url required\"}");
                    return;
                }
                // 只允许投我们自己的地址或局域网 http：让后端把任意 URL 推给电视，
                // 等于给了它一个"让内网设备去访问任意地址"的能力（SSRF 的变种）。
                if (!core.UrlSafety.isSafeMediaUrl(url)) {
                    sendJson(ex, 403, "{\"code\":-1,\"msg\":\"url not allowed\"}");
                    return;
                }
                r = core.Cast.play(d, url, trimToEmpty(in.optString("title", "")));
            }
        }
        sendJson(ex, 200, r.toString());
    }

    /**
     * 刮削端点。
     *
     * <pre>
     * POST   /api/scrape           {"groups":[{title,year}]}  批量补元数据
     * GET    /api/scrape?title=&amp;year=                         单条查询（走缓存）
     * GET    /api/scrape?title=&amp;year=&amp;refresh=1               单条强制重查（跳过缓存）
     * DELETE /api/scrape?title=&amp;year=                         解除这一条的绑定
     * DELETE /api/scrape                                     清空整个缓存
     * </pre>
     *
     * <p>带 title 的 DELETE 是「解除绑定」：删掉这条片名到豆瓣条目的映射，
     * 下次列目录会重新匹配。不带 title 才是原来的"清空全部"——
     * 两者共用一个方法，靠 title 是否为空区分，避免再加一个端点。
     */
    private static void handleScrape(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        Map<String, String> q = query(ex);
        String title = trimToEmpty(q.get("title"));
        // 片库 id：让缓存 key 带上库维度（两个库各有《教父》时不能共用一条）。
        // **可省略**——省略就是旧的全局条目，老客户端不受影响。
        String lib = trimToEmpty(q.get("lib"));
        int year = 0;
        try {
            year = Integer.parseInt(trimToEmpty(q.get("year")));
        } catch (Exception ignored) {
        }

        if ("DELETE".equalsIgnoreCase(method)) {
            if (title.isEmpty()) {
                sendJson(ex, 200, new JSONObject().put("ok", core.Scrape.clear()).toString());
                return;
            }
            boolean ok = core.Scrape.unbind(lib, title, year);
            sendJson(ex, 200, new JSONObject().put("ok", ok).put("title", title).toString());
            return;
        }
        if ("POST".equalsIgnoreCase(method)) {
            String body = read(ex.getRequestBody());
            JSONObject in = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
            JSONArray groups = in.optJSONArray("groups");
            if (groups == null) {
                sendJson(ex, 400, "{\"code\":-1,\"msg\":\"groups required\"}");
                return;
            }
            // force 让「重新搜刮」不用先 DELETE 再 GET：一次请求拿到新结果，
            // 中途失败也不会留下"删了旧的、没拿到新的"的空窗。
            boolean force = in.optBoolean("refresh", false) || in.optBoolean("force", false);
            // lib 优先取 body（批量时前端一次带上），退到 query
            String postLib = trimToEmpty(in.optString("lib", ""));
            if (postLib.isEmpty()) postLib = lib;
            sendJson(ex, 200, new JSONObject().put("ok", true)
                    .put("items", core.Scrape.enrich(postLib, groups, force)).toString());
            return;
        }
        if (title.isEmpty()) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"title required\"}");
            return;
        }
        boolean refresh = "1".equals(q.get("refresh")) || "true".equalsIgnoreCase(q.get("refresh"));
        sendJson(ex, 200, core.Scrape.lookup(lib, title, year, refresh).toString());
    }

    // ---------------------------------------------------------------- transcode

    /**
     * 按需转码。浏览器放不了的格式（MKV 容器 / HEVC / DTS 音轨）由 ffmpeg 现转 HLS。
     *
     * <p>GET  {@code /api/transcode?src=...}        查询/启动（幂等）
     * <br>GET  {@code /api/transcode/status?src=...} 只查状态
     * <br>GET  {@code /api/transcode/{id}/index.m3u8} 转码清单
     * <br>GET  {@code /api/transcode/{id}/{seg}}     转码分片
     * <br>DELETE {@code /api/transcode?id=...}       取消
     *
     * <p><b>为什么转 HLS 而不是 MP4</b>：MP4 要等 moov 写完（整片转完）才能播，
     * HLS 是增量的——前几个分片出来就能起播。实测 H.264 转码约 6.7 倍速。
     *
     * <p><b>为什么不改用户的文件</b>：产物写在这里的缓存目录，
     * 源文件只读。
     */
    private static void handleTranscode(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        Map<String, String> q = query(ex);
        String src = trimToEmpty(q.get("src"));
        String id = trimToEmpty(q.get("id"));

        if ("DELETE".equalsIgnoreCase(method)) {
            boolean ok = !id.isEmpty() && core.Transcode.cancel(id);
            sendJson(ex, ok ? 200 : 404, new JSONObject().put("ok", ok).toString());
            return;
        }
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            sendJson(ex, 405, "{\"code\":-1,\"msg\":\"method not allowed\"}");
            return;
        }
        // 片库来源（lib=xxx&path=yyy）：不走 URL 校验，也不用 HTTP 回环。
        // 本地目录直接把绝对路径给 ffmpeg；SMB 由后端读出来喂临时文件。
        // **必须排在 src 判空之前**：片库请求本来就不带 src，先判空会把它当成
        // "探测 ffmpeg 是否可用"直接返回，转码请求看起来毫无反应。
        String libId = trimToEmpty(q.get("lib"));
        String libPath = trimToEmpty(q.get("path"));
        if (!libId.isEmpty() && !libPath.isEmpty()) {
            routes.LibraryRoutes.handleLibraryTranscode(ex, q, libId, libPath);
            return;
        }
        if (src.isEmpty()) {
            // 不带 src：报 ffmpeg 是否可用 + 当前占用，前端据此决定是否显示"转码播放"
            String ff = core.Transcode.ffmpegPath();
            sendJson(ex, 200, new JSONObject()
                    .put("ok", true)
                    .put("available", !ff.isEmpty())
                    .put("bytes", core.Transcode.totalBytes())
                    .toString());
            return;
        }
        if (!core.UrlSafety.isSafeMediaUrl(src)
                && !Boolean.getBoolean("meow.test.allowLoopbackCache")) {
            sendJson(ex, 403, "{\"code\":-1,\"msg\":\"url not allowed\"}");
            return;
        }
        boolean statusOnly = "status".equalsIgnoreCase(q.getOrDefault("mode", ""))
                || "/api/transcode/status".equals(ex.getRequestURI().getPath());
        if (statusOnly) {
            core.Transcode.Job j = core.Transcode.status(core.Transcode.idFor(src));
            if (j == null) {
                sendJson(ex, 200, new JSONObject().put("ok", true).put("state", "idle").toString());
                return;
            }
            sendJson(ex, 200, core.Transcode.toJson(j).toString());
            return;
        }
        core.Transcode.Job j = core.Transcode.ensure(src, false);
        sendJson(ex, 200, core.Transcode.toJson(j).toString());
    }

    /** 转码产物（清单与分片）。路径只允许单段，防穿越。 */
    private static void handleTranscodeFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String rest = path.substring("/api/transcode/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            sendJson(ex, 404, "{\"code\":-1,\"msg\":\"not found\"}");
            return;
        }
        String id = URLDecoder.decode(rest.substring(0, slash), "UTF-8");
        String file = URLDecoder.decode(rest.substring(slash + 1), "UTF-8");
        if (id.isEmpty() || file.isEmpty() || id.contains("..") || file.contains("..")
                || id.contains("/") || id.contains("\\")
                || file.contains("/") || file.contains("\\")) {
            sendJson(ex, 404, "{\"code\":-1,\"msg\":\"not found\"}");
            return;
        }
        java.nio.file.Path p = core.Transcode.fileOf(id, file);
        if (p == null || !java.nio.file.Files.isRegularFile(p)) {
            sendJson(ex, 404, "{\"code\":-1,\"msg\":\"not ready\"}");
            return;
        }
        byte[] data = java.nio.file.Files.readAllBytes(p);
        Headers out = ex.getResponseHeaders();
        applyCors(ex);
        out.set("Content-Type", file.endsWith(".m3u8")
                ? "application/vnd.apple.mpegurl" : "video/mp2t");
        out.set("Accept-Ranges", "bytes");
        // 清单不能长缓存：转码过程中它一直在追加分片
        out.set("Cache-Control", file.endsWith(".m3u8")
                ? "no-cache" : "public, max-age=31536000, immutable");
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
        ex.close();
    }

    // ---------------------------------------------------------------- state

    /*
     * 出口 C：三端共享状态（播放进度 + 收藏）。
     *
     * 为什么不复用 /api/webdav：那条通道是「整份 history.json 覆盖式 PUT」，
     * 两台设备同时在线时后写的把先写的整份冲掉。这里按 (site, vod_id) 做并集，
     * 两个通道并存——用户已经配好的 WebDAV 不能因为新增 state 就失效。
     */

    /** 容量上限。个人自用场景，超限静默淘汰最旧，不值得让用户看到错误。 */
    /** 启动全量拉取只给 500 条：一次灌进 localStorage，再多也没人看。 */
    /**
     * 串行化「读-改-写」整段。
     *
     * <p>DataPaths.write 的临时文件名是固定的（state-progress.json.tmp），两个
     * POST 并发会撞 AccessDenied / NoSuchFileException（与 IMPORT_LOCK 同一个坑，
     * 实测 10 并发写挂 4 个）。两个文件共用一个锁即可——量小，串行没有代价。
     */
    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /** query 里的影片 id：驼峰 vodId 优先，其次下划线 vod_id。 */
    /** 取字符串字段；android-json 的 optString 对 JSON null 会返回字面量 "null"。 */
    /** 按数值键倒序，超出 max 的尾部丢弃（淘汰最旧）。顺带过滤非对象元素。 */
    /** 进度项对外形状：8 个键固定齐全，前端不用逐个判 undefined。 */
    /** 收藏项对外形状：三个可选字段为空时整个键不出现（与 docs/25 的契约一致）。 */
    /**
     * 设置同步（只同步 account 级字段）。
     *
     * <p>为什么不整份 Prefs 一起同步：音量/静音/倍速是每台设备自己的状态，
     * 同步过去会互相打架——手机调小音量，PC 打开就变小声，这不是用户想要的。
     * 画面缩放、壁纸同理（屏幕比例不同、壁纸是本地资源）。
     *
     * <p>WebDAV 地址与密码不在 Prefs 里（单独存），所以天然不会被同步；
     * 这里再显式列一遍黑名单，防止以后有人把凭据塞进 Prefs。
     */
    /** 可同步的键（account 级）：内容偏好、检索行为、弹幕、连播、跳过、主题。 */
    /** 明确不同步的键：设备本地状态 + 凭据。写出来是为了让"为什么不同步"可查。 */
    /** 读设置文件；缺失/损坏都返回空对象（首次启动就是没有文件）。 */
    /** 请求里带了该字段就用新的，否则沿用已存值（避免只改备注的其他字段被清空）。 */
    /**
     * 按图床域名猜 Referer。
     *
     * <p><b>为什么需要</b>：豆瓣、B 站等图床校验 Referer，裸请求直接 418/403。
     * 实测豆瓣 {@code img3.doubanio.com} 无 Referer 返回 418，而海报地址只从
     * 上游拿得到、前端不知道该配什么 Referer，于是所有卡片都没图。
     *
     * <p>只列已知需要的那几个，猜不到返回 null（不带 Referer，行为同以前）。
     */
    private static String defaultRefererFor(String url) {
        try {
            String host = new URL(url).getHost();
            if (host == null) return null;
            String h = host.toLowerCase();
            if (h.endsWith("doubanio.com") || h.endsWith("douban.com")) {
                return "https://movie.douban.com/";
            }
            if (h.endsWith("hdslb.com") || h.endsWith("bilibili.com")) {
                return "https://www.bilibili.com/";
            }
            if (h.contains("qpic.cn") || h.contains("gtimg.com") || h.contains("qq.com")) {
                return "https://v.qq.com/";
            }
            if (h.contains("iqiyipic.com") || h.contains("iqiyi.com")) {
                return "https://www.iqiyi.com/";
            }
            if (h.contains("youku.com")) return "https://www.youku.com/";
            if (h.contains("mgtv.com")) return "https://www.mgtv.com/";
            if (h.contains("sohu.com")) return "https://www.sohu.com/";
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 按指定 IP 打开连接（Host 头保持域名、SNI 不变、补主机名校验）。
     *
     * <p>为什么不用 {@code openStream}：它内部只钉**第一个** IP。阿里 CDN 的
     * {@code img3.doubanio.com} 一次返回 16 个 IP，而某台 NAS 到其中一部分会
     * 超时 10–26 秒、到另一部分 0.2 秒就通。只钉第一个 = 那张海报必然裂。
     *
     * <p>主机名校验必须自己做：HttpURLConnection 连的是 IP，不会校验证书里的
     * 域名，不补这一步等于关掉了校验（可被中间人利用）。
     */
    private static HttpURLConnection openAgainst(String url, String ip, String referer, String ua)
            throws IOException {
        URL u = new URL(url);
        String mapped = u.getProtocol() + "://" + ip
                + (u.getPort() > 0 ? ":" + u.getPort() : "") + u.getFile();
        HttpURLConnection c = (HttpURLConnection) new URL(mapped).openConnection();
        c.setRequestProperty("Host", u.getHost());
        if (c instanceof javax.net.ssl.HttpsURLConnection) {
            javax.net.ssl.HttpsURLConnection hc = (javax.net.ssl.HttpsURLConnection) c;
            /*
             * 必须换成 SNI 工厂（2026-10-30 安全审计 H12）。
             *
             * 这里连的是 IP，URL 里没有域名 —— 所以：
             *   · 不发 SNI，Cloudflare 这类前置直接 handshake_failure
             *   · JSSE 的主机名校验会拿 **IP** 去比证书，必然失败
             * 原实现在这里装了个"永远返回 true"的 HostnameVerifier 来绕过
             * 第二条，副作用是**彻底关掉了主机名校验**（任何有效证书都能冒充
             * 图床，实测拿 Cloudflare IP + 不相干 Host 头能通）。
             *
             * 现在用同一个 SniSocketFactory：TCP 连 IP，握手时 SNI 与
             * endpointIdentificationAlgorithm=HTTPS 都用原域名。
             */
            hc.setSSLSocketFactory(new SniSocketFactory(
                    (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault(),
                    u.getHost(),
                    u.getPort() > 0 ? u.getPort() : 443));
        }
        if (referer != null) c.setRequestProperty("Referer", referer);
        if (ua != null) c.setRequestProperty("User-Agent", ua);
        return c;
    }

    private static void handleImage(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String raw = q.get("url");
        if (raw == null || raw.isEmpty()) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"url required\"}");
            return;
        }
        String realUrl = raw;
        String referer = null;
        String ua = "Mozilla/5.0";
        // 解析 @Referer=xxx@User-Agent=yyy 尾巴
        int at = raw.indexOf('@');
        if (at > 0) {
            realUrl = raw.substring(0, at);
            String rest = raw.substring(at + 1);
            for (String part : rest.split("@")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String k = part.substring(0, eq).trim();
                String v = part.substring(eq + 1).trim();
                if ("Referer".equalsIgnoreCase(k)) referer = v;
                else if ("User-Agent".equalsIgnoreCase(k)) ua = v;
            }
        }
        if (q.containsKey("referer")) referer = q.get("referer");
        if (q.containsKey("ua")) ua = q.get("ua");

        // 与 /api/proxy/stream 同一个漏洞面：URL 由调用方给。
        // 图片用宽松档（本地海报/自建图床在局域网是合理场景），回环与云元数据仍拒绝。
        if (!core.UrlSafety.isSafeMediaUrl(realUrl)) {
            sendJson(ex, 403, "{\"code\":-1,\"msg\":\"url not allowed\"}");
            return;
        }

        // ★ 先查本地落盘的海报（core/PosterStore）。
        //   命中就**完全不走网络**——这是"局域网脱网访问"能不能成立的关键：
        //   前端只靠浏览器缓存（max-age=86400）的话，断网超过一天整面墙都裂，
        //   而 TV 的 WebView 缓存还可能被系统清理。
        byte[] cachedBytes = core.PosterStore.get(realUrl);
        if (cachedBytes != null) {
            String cReal = core.PosterStore.sniffOrNull(cachedBytes);
            ex.getResponseHeaders().set("Content-Type",
                    cReal == null ? "image/jpeg" : ("image/" + cReal));
            // 本地文件是稳定副本，可以给长缓存
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=604800");
            ex.getResponseHeaders().set("X-Meow-Poster", "disk");
            applyCors(ex);
            ex.sendResponseHeaders(200, cachedBytes.length);
            ex.getResponseBody().write(cachedBytes);
            ex.close();
            return;
        }

        // ★ 多 IP 逐个重试，而不是让 openStream 只钉第一个 IP。
        //
        //   实测踩过：img3.doubanio.com（阿里 CDN）一次返回 16 个 A 记录，
        //   而这台 NAS 到其中一部分 IP 连接**超时 10–26 秒**、到另一部分 0.2 秒就通。
        //   只钉第一个 IP 时，只要那一个不通，整面海报墙就是裂的——而同一时刻从
        //   别的机器直连同样的 URL 全都正常，看起来像"豆瓣挂了"，极难定位。
        //
        //   做法：DoH 拿全部候选 IP，按顺序试，连上就用（Host 头保持域名，
        //   SNI 不变，并补主机名校验）。safeDns 关闭时退回单连接（保持原行为）。
        HttpURLConnection c = null;
        IOException lastErr = null;
        // 多 IP 分支在 getResponseCode() 前就把请求头设完了；单路径分支在下面补。
        boolean headersDone = false;
        java.util.List<String> hosts = core.UrlSafety.mirrorCandidates(realUrl);
        if (hosts.isEmpty()) {
            // safeDns 未开 / 解析失败：走原来的单路径
            try {
                c = openStream(realUrl, referer != null ? referer : defaultRefererFor(realUrl), ua);
            } catch (IOException e) {
                sendJson(ex, 502, "{\"code\":-1,\"msg\":" + JSONObject.quote(String.valueOf(e)) + "}");
                return;
            }
        } else {
            String useReferer = referer != null ? referer : defaultRefererFor(realUrl);
            for (String ip : hosts) {
                HttpURLConnection cand = null;
                try {
                    cand = openAgainst(realUrl, ip, useReferer, ua);
                    // ★ 所有请求头必须在 getResponseCode() **之前**设好：
                    //   一旦发了请求，HttpURLConnection 就锁定，再 setRequestProperty
                    //   会抛 IllegalStateException: Already connected（实测踩过）。
                    cand.setInstanceFollowRedirects(false);
                    cand.setConnectTimeout(6_000);   // 单个 IP 别等太久，不通就换
                    cand.setReadTimeout(15_000);
                    cand.setRequestProperty("Accept", "image/webp,image/jpeg,image/png,image/gif,*/*;q=0.5");
                    // 触发连接：连不上会抛，换下一个 IP
                    cand.getResponseCode();
                    c = cand;
                    break;
                } catch (IOException e) {
                    lastErr = e;
                    if (cand != null) {
                        try {
                            cand.disconnect();
                        } catch (Exception ignored) {
                        }
                    }
                    // 换下一个 IP
                }
            }
            if (c == null) {
                sendJson(ex, 502, "{\"code\":-1,\"msg\":"
                        + JSONObject.quote("all " + hosts.size() + " IPs failed: " + lastErr) + "}");
                return;
            }
            // 下面那段"补 UA/Referer/Accept"只给单路径分支用：多 IP 分支在
            // getResponseCode() 之前已经把三个头都设好了，再设会抛
            // IllegalStateException: Already connected。
            headersDone = true;
        }
        if (!headersDone) {
            // 图片不做手动跳转跟随：不跟随等于直接拒绝跳到内网，
            // 而正常图床的跳转极少；若确有需要再按 stream 的写法补。
            c.setInstanceFollowRedirects(false);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", ua);
            // 防盗链图床要 Referer，而调用方（前端 PosterImage）不会知道该填什么。
            // 实测豆瓣图床不带 Referer 返回 418 / 403，海报全裂——这是"卡片没图"的根因。
            // 按域名兜底：只在调用方没显式给时才补，避免覆盖源站要求的特定值。
            if (referer == null) referer = defaultRefererFor(realUrl);
            if (referer != null) c.setRequestProperty("Referer", referer);
            // ★ 绝不能声明 avif：Chrome 85+ 才支持，而 TV 内核实测是 Chrome 66。
            //   原来写 image/avif,image/webp,... → 腾讯图床（vcover-vt-pic.qpic.cn）
            //   返回 image/avif → 这里原样透传 → 投影仪海报区只剩占位图标。
            //   只声明 webp 及以下：webp 自 Chrome 32 支持，是安全下限。
            c.setRequestProperty("Accept", "image/webp,image/jpeg,image/png,image/gif,*/*;q=0.5");
        }

        int code = c.getResponseCode();
        if (code >= 400) {
            sendJson(ex, code, "{\"code\":-1,\"msg\":\"upstream " + code + "\"}");
            c.disconnect();
            return;
        }
        String ct = c.getContentType();
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        c.disconnect();
        byte[] data = bos.toByteArray();

        // 按魔数判真实格式，不信任 Content-Type。
        //
        // ★ 实测的三种坏 MIME（都是"图本身没坏，只是头写错了"）：
        //   · Jpys    返回 binary/octet-stream，实际是 WEBP
        //   · AppYQK  返回 application/octet-stream，实际是 JPEG
        //   浏览器遇到非 image/* 的 Content-Type 直接不当图片渲染，
        //   而 PosterImage 只看到 onerror —— 表现为"这个源海报不显示"。
        String real = sniffImageType(data);
        String ctLower = ct == null ? "" : ct.toLowerCase();

        // AVIF/HEIC：老内核（TV Chrome 66，实测）解不了。必须在**声明格式**和
        //   **魔数**两处都拦——有的图床 Content-Type 写 octet-stream 但内容仍是 avif。
        boolean avif = ctLower.contains("avif") || ctLower.contains("heic")
                || ctLower.contains("heif") || "avif".equals(real) || "heic".equals(real);
        if (avif) {
            core.LogBuffer.warn("image", "unsupported format " + ct + " from " + core.LogBuffer.scrub(realUrl));
            sendJson(ex, 415, "{\"code\":-1,\"msg\":\"upstream image format not supported: " + ct + "\"}");
            return;
        }

        // 能识别就按真实格式下发；识别不出才退回上游的 Content-Type。
        // 只认浏览器都支持的四种（jpeg/png/gif/webp），webp 自 Chrome 32 起支持。
        String outCt = real == null ? (ct == null ? "image/jpeg" : ct) : ("image/" + real);
        // 顺手落盘，下次断网也能出图。这里不阻塞响应：失败只记一条 warn。
        core.PosterStore.put(realUrl, data, real);
        ex.getResponseHeaders().set("Content-Type", outCt);
        ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        applyCors(ex);
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
        ex.close();
    }

    /**
     * 按魔数判断图片真实格式，认不出返回 {@code null}。
     *
     * <p><b>为什么不信任 Content-Type</b>：实测两个源把图床返回的
     * {@code image/webp} / {@code image/jpeg} 标成了 {@code application/octet-stream}，
     * 浏览器见到非 image/* 直接不渲染 —— 表现为"这个源海报不显示"，
     * 但图本身完全正常。按字节头重写 Content-Type 就能救回来。
     *
     * <p>只认四种（jpeg/png/gif/webp）：这是全平台（含 TV 的 Chrome 66）都能解的集合。
     * avif/heic 单独识别出来，由调用方拒绝——不是纠正 MIME 能解决的，那是解码能力问题。
     */
    private static String sniffImageType(byte[] d) {
        if (d == null || d.length < 12) return null;
        // JPEG: FF D8 FF
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "jpeg";
        // PNG: 89 50 4E 47
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "png";
        // GIF: GIF8
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') return "gif";
        // WEBP: "RIFF" .... "WEBP"
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "webp";
        // ISOBMFF 家族：...ftyp<brand>。avif / heic 都在这里，返回固定标记由调用方拒绝。
        if (d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            String brand = new String(d, 8, 4, StandardCharsets.US_ASCII).toLowerCase();
            if (brand.startsWith("avi")) return "avif";
            if (brand.startsWith("hei") || brand.startsWith("mif") || brand.startsWith("msf")) return "heic";
        }
        return null;
    }

    /**
     * jar 内 Proxy 类的握手 + 转发端点。
     *
     * <p>{@code do=ck} 是端口探测握手，正文必须恰为 {@code "ok"}：javap 反编译可见
     * Proxy.a() 用 {@code String.equals} 精确比较返回值，多一个换行它就不认宿主。
     */
    private static void handleJarProxy(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String d = q.getOrDefault("do", "");

        if ("ck".equals(d)) {
            sendText(ex, 200, "ok", "text/plain; charset=utf-8");
            return;
        }

        Object[] r;
        try {
            // Proxy.proxy 是 static 方法，返回 Object[]{code, contentType, InputStream}，
            // 与 SpiderHost.proxy 的约定一致（server/host/src/SpiderHost.java:223 起）。
            Class<?> c = platform.Bridge.loadClass("com.github.catvod.spider.Proxy");
            Method m = c.getMethod("proxy", Map.class);
            r = (Object[]) m.invoke(null, q);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            // 501 而不是 500：这是宿主能力缺失（jar 版本不匹配），不是源站失效。
            // 500 在约定里代表"这个源/服务出错"，容易被前端当成站点挂了。
            sendJson(ex, 501, "{\"code\":-1,\"msg\":" + JSONObject.quote("jar proxy unavailable: " + e) + "}");
            return;
        } catch (Throwable t) {
            // 反射通了但 jar 内部自己抛，属于上游取流失败，与 SpiderHost 保持 502。
            sendJson(ex, 502, "{\"code\":-1,\"msg\":" + JSONObject.quote(String.valueOf(t)) + "}");
            return;
        }

        if (r == null) {
            // 实测（Probe2 反射调用）：jar 对未实现的 do 直接返回 null，不抛异常。
            // 这同样是能力缺失而非上游故障，跟反射失败一样走 501，避免被当成源失效。
            sendJson(ex, 501, "{\"code\":-1,\"msg\":" + JSONObject.quote("jar proxy has no handler for do=" + d) + "}");
            return;
        }
        if (r.length < 3 || !(r[2] instanceof InputStream)) {
            sendJson(ex, 502, "{\"code\":-1,\"msg\":\"jar proxy returned no body\"}");
            return;
        }

        int code = r[0] instanceof Number ? ((Number) r[0]).intValue() : 200;
        String ct = r[1] == null ? "application/octet-stream" : String.valueOf(r[1]);
        ex.getResponseHeaders().set("Content-Type", ct);
        applyCors(ex);
        ex.sendResponseHeaders(code, 0);
        try (InputStream in = (InputStream) r[2]; OutputStream os = ex.getResponseBody()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
        }
    }

    // ------------------------------------------------- CatVod 出口（TV 端）

    /**
     * spider jar 的 md5 缓存。
     *
     * <p>必须与 {@code /spider-latest.jar} 实际吐出的字节一致：Fongmi 的
     * {@code JarLoader.parseJar} 拿这里写的 md5 跟本地缓存文件比对（
     * {@code Crypto.equals(Path.jar(jar), md5)}），一致才复用缓存、不一致就重新下载。
     * 所以 md5 若和文件对不上，用户每次启动都会白下载一次 jar；
     * 反过来 jar 换了而这里不重算（缓存写死），用户会永远停留在旧缓存。
     * 因此缓存以「文件最新修改时间 + 长度」为 key，jar 一变就失效重算。
     */
    private static volatile String spiderJarMd5;
    private static volatile long spiderJarMd5Key = -1L;

    /** 当前 spider jar 文件；找不到时返回 null。 */
    private static File findSpiderJar() {
        // 优先问 classpath：CodeSource 给出的是实际被加载的那份 jar，
        // 天然覆盖 libs/spider.jar（Windows 反斜杠）与 Docker 的 libs/*（正斜杠）两种布局。
        //
        // Android 上 getProtectionDomain 取不到 CodeSource（dex 没有 jar 的 file: 位置，
        // 而且可能直接抛 SecurityException），会被下面的 catch 兜住走候选路径。
        try {
            Class<?> c = platform.Bridge.loadClass("com.github.catvod.spider.Proxy");
            java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null && "file".equals(cs.getLocation().getProtocol())) {
                File f = new File(cs.getLocation().toURI());
                if (f.isFile()) return f;
            }
        } catch (Throwable ignored) {
            // 落到下面的候选路径
        }
        // 兜底：按启动脚本的相对位置找。classes 与 libs 是兄弟目录（见 start-api.bat / Dockerfile.apiserver）。
        for (String p : new String[]{"libs/spider.jar", "host/libs/spider.jar", "../libs/spider.jar"}) {
            File f = new File(p);
            if (f.isFile()) return f;
        }
        return null;
    }

    /**
     * 要下发给客户端的 spider jar —— 优先 dex 形态。
     *
     * <p><b>为什么不能直接下发 classpath 那份</b>：本仓库的 {@code libs/spider.jar}
     * 是 883 个 {@code .class} 的 JVM jar，而这个端点同时服务于
     * <b>Android 客户端</b>（Fongmi / TVBox 系 / 我们自己的 TV 壳）。它们靠
     * {@code DexClassLoader} 加载，<b>只认 ZIP 里的 classes.dex</b>，拿到 class 版
     * 一律报「jar 里没有类 Xxx」。
     *
     * <p>实测：电视台 TV 用本后端 /config.json 能导入 39/40 站，但每次 play 都失败，
     * 原因就是下发的 jar 是 class 形态。换成 dex 形态后同一批站点立刻可用。
     *
     * <p>判据是<b>读中央目录看有没有 classes.dex</b>，不是看文件名——
     * 文件名两边都叫 spider*.jar，光看名字分不出来。
     *
     * <p>候选顺序：优先 patched（带 Init/goproxy 补丁，避开那套联网下载），
     * 其次未打补丁的；都没有才回落到 classpath 那份（PC 端插件仍需要它）。
     */
    private static File findSpiderJarForClient() {
        File dex = findDexSpiderJar();
        return dex != null ? dex : findSpiderJar();
    }

    /** dex 形态的 spider jar（能给 Android DexClassLoader 用）；没有返回 null。 */
    private static File findDexSpiderJar() {
        String[] candidates = {
                "server/build/dist/spider-complete-latest-patched.jar",
                "build/dist/spider-complete-latest-patched.jar",
                "../build/dist/spider-complete-latest-patched.jar",
                "server/build/dist/spider-complete-latest.jar",
                "build/dist/spider-complete-latest.jar",
                "../build/dist/spider-complete-latest.jar",
                "server/build/dist/spider-latest-patched.jar",
                "build/dist/spider-latest-patched.jar",
                "dist/spider-complete-latest-patched.jar",
        };
        for (String p : candidates) {
            File f = new File(p);
            if (f.isFile() && hasClassesDex(f)) return f;
        }
        // 兜底：扫 dist 目录，取第一个含 classes.dex 的
        for (String dirPath : new String[]{"server/build/dist", "build/dist", "dist", "../build/dist"}) {
            File dir = new File(dirPath);
            File[] fs = dir.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (f.isFile() && f.getName().endsWith(".jar") && hasClassesDex(f)) return f;
            }
        }
        return null;
    }

    /** jar 里有没有 classes.dex —— Android 能不能加载的唯一判据。 */
    private static boolean hasClassesDex(File jar) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar)) {
            return zf.getEntry("classes.dex") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** md5 of 要下发的 spider jar；缓存命中返回旧值，文件变了自动重算。 */
    private static String spiderJarMd5() {
        File f = findSpiderJarForClient();
        if (f == null) return "";
        long key = f.lastModified() * 31L + f.length();
        String cached = spiderJarMd5;
        if (cached != null && key == spiderJarMd5Key) return cached;
        synchronized (ApiServer.class) {
            if (spiderJarMd5 != null && key == spiderJarMd5Key) return spiderJarMd5;
            try (InputStream in = new FileInputStream(f)) {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest()) sb.append(String.format("%02x", b));
                spiderJarMd5 = sb.toString();
                spiderJarMd5Key = key;
                return spiderJarMd5;
            } catch (Exception e) {
                System.out.println("[api] spider jar md5 failed: " + e);
                return "";
            }
        }
    }

    /**
     * CatVod 形状的完整配置（九项）。
     *
     * <p>数据来自数据目录的 {@code config.sites.json}，刻意不读仓库里的
     * {@code reverse/artifacts/config.json}——那是逆向参照物，不进分发。
     * 文件缺席时各表输出空数组：TV 端拿到空 parses/ads 仍能播，报错就整份配置加载失败。
     */
    private static String catVodConfigJson(HttpExchange ex) throws Exception {
        JSONObject root = new JSONObject();
        String base = baseUrlFor(ex);
        String md5 = spiderJarMd5();
        // 拿不到 jar 就不写 md5：Fongmi 对空 md5 会走"总是下载"，比写个错的稳。
        root.put("spider", md5.isEmpty()
                ? base + "/spider-latest.jar"
                : base + "/spider-latest.jar;md5;" + md5);

        JSONObject cfg = loadConfigSites();
        root.put("sites", sitesForCatVod(cfg));
        for (String table : new String[]{"parses", "lives", "rules", "ads", "proxy", "hosts", "doh", "headers"}) {
            root.put(table, tableOrEmpty(cfg, table));
        }
        return root.toString();
    }

    /** 读数据目录的 config.sites.json；不存在或坏了都返回空对象（各表退化成空数组）。 */
    private static JSONObject loadConfigSites() {
        try {
            File f = core.DataPaths.file("config.sites.json");
            if (!f.isFile()) return new JSONObject();
            String txt = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return new JSONObject(txt);
        } catch (Throwable t) {
            System.out.println("[api] config.sites.json unreadable for /config.json: " + t);
            return new JSONObject();
        }
    }

    private static JSONArray tableOrEmpty(JSONObject cfg, String name) {
        JSONArray a = cfg.optJSONArray(name);
        return a == null ? new JSONArray() : a;
    }

    /**
     * sites 转 CatVod 形状。
     *
     * <p>type 用配置里的原值而不是硬编码 3：type=1 是苹果CMS 直连（api 就是 http 地址），
     * 写成 3 会让 TV 端按 csp_ 类名去 jar 里找类，直接找不到而整站消失。
     * ext 原样透传——它可能是字符串也可能是对象，转成 String 会破坏 {@code {"json":...}} 这类结构。
     *
     * <p><b>为什么要补 jar 字段</b>：我们自己的 TV 端（LocalSites.importFrom）只收
     * "api 是 csp_ <b>且</b>配了 jar" 的站点，靠 jar 才能 DexClassLoader 出实现类。
     * 而 config.sites.json 里的 csp_ 站点一个 jar 都没写——它们本来是给"jar 在 classpath 上"
     * 的场景用的。不补这一字段，TV 导入结果是 {@code 已导入 0/235 站}，
     * 界面表现就是"填了地址什么都不加载"。补的是相对路径，客户端按配置地址补全。
     */
    private static JSONArray sitesForCatVod(JSONObject cfg) throws Exception {
        JSONArray out = new JSONArray();
        JSONArray arr = cfg.optJSONArray("sites");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            String api = s.optString("api", "");
            String key = s.optString("key", "");
            if (api.isEmpty() && key.isEmpty()) continue;

            JSONObject o = new JSONObject();
            o.put("key", key.isEmpty() ? api : key);
            o.put("name", s.optString("name", o.optString("key")));
            o.put("type", s.optInt("type", 3));
            o.put("api", api);
            Object ext = s.opt("ext");
            if (ext != null && ext != JSONObject.NULL) o.put("ext", ext);
            // 透传这些可选字段：TV 端靠 searchable/filterable/playerType 决定是否发搜索请求、
            // 是否显示筛选、用哪个播放器。丢了表现为"能进站点但搜不到/筛选栏空"。
            for (String k : new String[]{"searchable", "filterable", "changeable", "playerType", "timeout", "jar", "style"}) {
                Object v = s.opt(k);
                if (v != null && v != JSONObject.NULL) o.put(k, v);
            }
            // csp_ 站点靠 jar 里的类才能执行；配置没写 jar 时补上我们自己的那份。
            // 相对路径由客户端按配置地址补全（LocalSites.absolutize），
            // 写死绝对地址会在"外网域名 / 内网 IP 访问同一后端"时指向错误的主机。
            if (api.startsWith("csp_") && !o.has("jar")) {
                o.put("jar", "./spider-latest.jar");
            }
            out.put(o);
        }
        return out;
    }

    /**
     * 提供 spider jar 本体。
     *
     * <p>ETag 用 md5：Fongmi 的 Download 会带 {@code If-None-Match}，命中就 304，
     * 避免每次启动重下 1.7MB。注意 ETag 必须加引号——RFC 7232 要求，不加有的客户端不认。
     */
    private static void handleSpiderJar(HttpExchange ex) throws IOException {
        // 下发 dex 形态（Android 客户端只认 classes.dex）；没有才回落到 class 版
        File f = findSpiderJarForClient();
        if (f == null || !f.isFile()) {
            sendJson(ex, 404, "{\"code\":-1,\"msg\":\"spider jar not found\"}");
            return;
        }
        String md5 = spiderJarMd5();
        String etag = "\"" + md5 + "\"";
        Headers h = ex.getResponseHeaders();

        // 必须读请求头：getResponseHeaders() 只有响应侧，在它上面读 If-None-Match 恒为 null，
        // 304 就永远不会触发。
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        if (inm != null && !md5.isEmpty() && inm.replace("\"", "").trim().equalsIgnoreCase(md5)) {
            h.set("ETag", etag);
            h.set("Cache-Control", "public, max-age=0, must-revalidate");
            ex.sendResponseHeaders(304, -1);
            ex.close();
            return;
        }

        h.set("Content-Type", "application/java-archive");
        h.set("ETag", etag);
        h.set("Last-Modified", java.time.Instant.ofEpochMilli(f.lastModified()).toString());
        h.set("Cache-Control", "public, max-age=0, must-revalidate");
        applyCors(ex);
        // 长度给 0 = chunked，避免把 1.7MB 整个读进堆；客户端本来就按流式下载。
        ex.sendResponseHeaders(200, 0);
        try (InputStream in = new FileInputStream(f); OutputStream os = ex.getResponseBody()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
        }
    }

    // ------------------------------------------------- 导入外部 CatVod 配置

    /** 启动时的基线站点表。导入前用它复位，避免反复导入只增不减、越滚越脏。 */
    private static volatile Map<String, String> sitesBaseline;
    private static volatile Map<String, String> extBaseline;

    /** 最近一次导入的摘要，供 GET /api/config/import 展示。 */
    private static volatile JSONObject lastImport;

    /**
     * 预览过的配置原文暂存：{url, raw, atMillis}。
     *
     * <p>预览和确认是两次请求，若都去下载同一份配置就是白等一轮
     * （公网源实测 2–10s，肥猫那种 200KB 的能到十几秒）。
     * 只留最近一次、60 秒过期、用完即弃 —— 用户改了源配置再重来
     * 也不会拿到陈旧的字节。
     */
    private static volatile String[] importPreviewSlot;

    /**
     * 计算导入计划：合并后的 sites + 给用户看的 diff。
     *
     * <p>**站点身份用 `key + api`**：同 key 同 api = 同一条（更新别名/ext），
     * 同 key 不同 api 才算冲突。只按 key 比会把 csp_ 的多实例（文件里两条都是
     * key=Bili、api=csp_Bili）误判成冲突。
     *
     * <p>两种模式：
     * <ul>
     *   <li>{@code replace}：结果就是导入的那份（现有全丢，diff 里报 removed）</li>
     *   <li>{@code merge}：现有全部保留，导入的新增/更新；冲突按 onConflict 处理
     *       （keep 留现有 / replace 用导入的 / both 另起 key#N）</li>
     * </ul>
     *
     * @return {@code {sites: 合并结果, diff: {toAdd,toUpdate,conflicts,unchanged,removed,total}}}
     */
    private static JSONObject planSites(JSONArray existing, JSONArray incoming,
                                        boolean merge, String onConflict) throws Exception {
        JSONArray toAdd = new JSONArray();
        JSONArray toUpdate = new JSONArray();
        JSONArray conflicts = new JSONArray();
        JSONArray removed = new JSONArray();
        int unchanged = 0;

        // 现有站点：保持原顺序，同时按 key 建索引（同 key 可能有 csp_ 多实例）
        java.util.List<JSONObject> result = new java.util.ArrayList<>();
        java.util.Map<String, java.util.List<JSONObject>> byKey = new java.util.LinkedHashMap<>();
        for (int i = 0; i < existing.length(); i++) {
            JSONObject s = existing.optJSONObject(i);
            if (s == null) continue;
            result.add(s);
            byKey.computeIfAbsent(s.optString("key", ""), k -> new java.util.ArrayList<>()).add(s);
        }

        for (int i = 0; i < incoming.length(); i++) {
            JSONObject in = incoming.optJSONObject(i);
            if (in == null) continue;
            String key = in.optString("key", "");
            String api = in.optString("api", "");
            java.util.List<JSONObject> olds = byKey.get(key);

            JSONObject sameApi = null;
            if (olds != null) {
                for (JSONObject o : olds) {
                    if (api.equals(o.optString("api", ""))) {
                        sameApi = o;
                        break;
                    }
                }
            }

            if (sameApi != null) {
                /*
                 * 同 key 同 api：可能是"完全一样"（重复导入同一份配置），
                 * 也可能是别名/ext 变了。前者算「不变」，后者才算「更新」——
                 * 原来一律记成 toUpdate，于是第二次预览显示成
                 * "更新 239 / 不变 239"，看着像表里有 478 条。
                 */
                JSONObject next = new JSONObject(sameApi.toString());
                next.put("name", in.optString("name", key));
                if (in.has("ext")) next.put("ext", in.opt("ext"));
                boolean changed = !next.toString().equals(sameApi.toString());
                if (changed) {
                    toUpdate.put(in);
                    if (merge) {
                        int idx = result.indexOf(sameApi);
                        result.set(idx, next);
                        java.util.List<JSONObject> slot = byKey.get(key);
                        slot.set(slot.indexOf(sameApi), next);
                    }
                } else {
                    unchanged++;
                }
                continue;
            }

            if (olds != null && !olds.isEmpty()) {
                // 同 key 不同 api = 冲突
                conflicts.put(new JSONObject()
                        .put("key", key)
                        .put("existing", olds.get(0))
                        .put("incoming", in));
                if (!merge) continue;   // replace 模式下导入的自然顶掉，不用额外处理
                if ("replace".equals(onConflict)) {
                    int idx = result.indexOf(olds.get(0));
                    result.set(idx, in);
                    java.util.List<JSONObject> slot = byKey.get(key);
                    slot.set(0, in);
                    toUpdate.put(in);
                } else if ("both".equals(onConflict)) {
                    JSONObject copy = new JSONObject(in.toString());
                    /*
                     * csp_ 站的运行时 key 取的是类名（api.substring(4)），
                     * 配置里那个 key 只是显示名 —— 两条 api 不同的 csp_ 天然
                     * 就能共存，给它编号反而会写进一个运行时不认的名字
                     * （文件里 "豆瓣#2"，跑起来却叫 FirstAid，用户对不上）。
                     * 只有非 csp_ 站才需要另起 key，它们的 key 就是运行时键。
                     */
                    if (!api.startsWith("csp_")) {
                        String nk = key + "#2";
                        for (int n = 2; byKey.containsKey(nk); n++) nk = key + "#" + (n + 1);
                        copy.put("key", nk);
                        java.util.List<JSONObject> one = new java.util.ArrayList<>();
                        one.add(copy);
                        byKey.put(nk, one);
                    }
                    result.add(copy);
                    toAdd.put(copy);
                }
                // keep：什么都不做（保留现有）
                continue;
            }

            // 全新站点
            toAdd.put(in);
            if (merge) {
                result.add(in);
                java.util.List<JSONObject> one = new java.util.ArrayList<>();
                one.add(in);
                byKey.put(key, one);
            }
        }

        JSONArray finalSites = new JSONArray();
        if (merge) {
            for (JSONObject s : result) finalSites.put(s);
        } else {
            // replace：结果就是导入的那份；顺手报出"现有的哪些会消失"
            java.util.Set<String> incomingKeys = new java.util.HashSet<>();
            for (int i = 0; i < incoming.length(); i++) {
                JSONObject s = incoming.optJSONObject(i);
                if (s != null) incomingKeys.add(s.optString("key", ""));
            }
            for (String k : byKey.keySet()) {
                if (!incomingKeys.contains(k)) removed.put(k);
            }
            for (int i = 0; i < incoming.length(); i++) {
                JSONObject s = incoming.optJSONObject(i);
                if (s != null) finalSites.put(s);
            }
        }

        JSONObject diff = new JSONObject()
                .put("toAdd", toAdd)
                .put("toUpdate", toUpdate)
                .put("conflicts", conflicts)
                .put("unchanged", unchanged)
                .put("removed", removed)
                .put("total", finalSites.length());
        return new JSONObject().put("sites", finalSites).put("diff", diff);
    }

    /**
     * 站点表的**运行时**键集合（把 csp_ 多实例展开成 #2/#3）。
     *
     * <p>必须复刻 {@link #loadMultiInstances()} 的编号规则：site-health.json
     * 按运行时 key 存，文件里两条 key=Bili 在健康表里是 Bili / Bili#2。
     * 只按文件 key 比会把"导入后 Bili#2 消失"漏报。
     */
    private static java.util.Set<String> runtimeKeys(JSONArray sites) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        java.util.Map<String, Integer> seenCsp = new java.util.HashMap<>();
        java.util.Map<String, Integer> seenOther = new java.util.HashMap<>();
        for (int i = 0; i < sites.length(); i++) {
            JSONObject s = sites.optJSONObject(i);
            if (s == null) continue;
            String api = s.optString("api", "");
            String key = s.optString("key", "");
            if (api.isEmpty() || key.isEmpty()) continue;
            if (api.startsWith("csp_")) {
                String cls = api.substring(4);
                int n = seenCsp.merge(cls, 1, Integer::sum);
                out.add(n == 1 ? cls : cls + "#" + n);
            } else {
                int n = seenOther.merge(key, 1, Integer::sum);
                out.add(n == 1 ? key : key + "#" + n);
            }
        }
        return out;
    }

    /** site-health.json 里有健康记录的站点 key（结构：{sites:{key:{...}}}）。 */
    private static java.util.Set<String> healthRecordKeys() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            File f = core.DataPaths.file("site-health.json");
            if (!f.isFile()) return out;
            String txt = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
            JSONObject sites = new JSONObject(txt).optJSONObject("sites");
            if (sites != null) {
                // org.json 没有 keySet()，只能用 keys() 迭代
                java.util.Iterator<String> it = sites.keys();
                while (it.hasNext()) out.add(it.next());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 这次导入会让哪些站点的健康/停用状态失效。
     *
     * <p>只在**站点从表里消失**时才算失效：保留下来的站 key 不变，状态继续有效。
     * 用户手动停用的站如果被换掉，下次它又"活"过来重新参与搜索，
     * 而界面上没有任何提示 —— 所以要提前告诉他。
     */
    private static JSONArray healthLostKeys(JSONArray before, JSONArray after) {
        JSONArray out = new JSONArray();
        try {
            java.util.Set<String> gone = runtimeKeys(before);
            gone.removeAll(runtimeKeys(after));
            if (gone.isEmpty()) return out;
            java.util.Set<String> withRecord = healthRecordKeys();
            for (String k : gone) {
                if (withRecord.contains(k)) out.put(k);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static final int IMPORT_TIMEOUT_MS = 15_000;

    /**
     * 导入时允许从导入配置**补齐**的表（仅当本地为空）。
     *
     * <p>为什么需要：`loadConfigSites()` 保留原有表的逻辑假定本地已有一份
     * 完整基线；TV 端首次导入时本地是 `{}`，于是磁盘上只剩 sites，
     * doh/hosts/parses 全丢（实测：TV 网关导入后 doh 表为空，DoH 启不了，
     * Gulu/Gz360/AppRJ 一批源永远搜不到）。
     *
     * <p>为什么只在这些表上补：sites 由本函数重建；wallpaper/logo 这类
     * 单值项不属于"表"，由默认值或用户设置决定，不该被导入覆盖。
     */
    private static final String[] CONFIG_TABLES_TO_SEED = {
            "parses", "lives", "doh", "hosts", "headers", "rules", "ads", "proxy", "flags",
    };

    /**
     * 内置的公共直播源（开盒即用）。
     *
     * <p>为什么要有：直播是"配置里没有就完全没有"的功能 —— 我们的 jar 里
     * 72 个类零个实现 {@code liveContent}，所以直播**只能**靠 IPTV 播放列表。
     * 不内置的话，新装的客户端打开直播页是一片黑，用户根本不知道
     * "去设置页填个 m3u 地址"这件事。
     *
     * <p><b>它是什么</b>：Guovin/iptv-api 每天自动更新的公开 m3u（837 个频道，
     * 实测 CCTV-1/5、卫视、地方台抽样 5/5 可播）。它只是**索引别人的流**，
     * 不托管内容。
     *
     * <p><b>为什么默认开着</b>：没有它，直播功能等于不存在。
     * 用户可以在设置页「直播源」里删掉它 —— 删掉之后不会自动加回来
     * （只有 lives 表为空、且是首次启动时才会补默认值）。
     *
     * <p><b>风险</b>：①第三方转播流，版权状况不明 ②这些是别人的服务器，
     * 挂了就是挂了 ③走 CDN/GitHub Pages，国内网络可能不稳。
     * 这三条都写进设置页的说明文字里。
     */
    private static final String[][] BUILTIN_LIVES = {
            { "公共直播源（每日更新）", "https://guovin.github.io/iptv-api/result.m3u" },
            /*
             * 用 **语言包** 而不是国家包（`countries/cn.m3u`）。
             *
             * 实测（2026-11-10）：zho 有 222 个唯一台，cn 只有 152，而 cn 的
             * 145/152 被 zho 覆盖 —— zho 独有 78 个（港澳台 + CCTV-4 各洲版 +
             * CGTN + 天映电影等海外中文台）。一条语言包顶替原来要加 cn+hk+tw
             * 三条国家包，分类结构也更简单。
             *
             * 解析耗时 0.7s（cn 是 0.15s），可接受。
             */
            { "iptv-org 中文台", "https://iptv-org.github.io/iptv/languages/zho.m3u" },
    };

    /** 某条 url 是不是内置源之一 */
    private static boolean isBuiltinLiveUrl(String url) {
        if (url == null) return false;
        for (String[] b : BUILTIN_LIVES) if (b[1].equals(url.trim())) return true;
        return false;
    }

    /**
     * 用户删过的内置源 URL 集合。
     *
     * <p>`builtinLiveOff` 的两种历史形态都要认：
     * <ul>
     *   <li>**布尔 true**（老数据）：当时只有一条内置源，true 就是"全关"→ 展开成全部 URL</li>
     *   <li>**URL 数组**（现在）：内置源有两条，用户可能只删其中一条，必须逐条记录</li>
     * </ul>
     */
    private static java.util.Set<String> disabledBuiltin() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            Object off = loadConfigSites().opt("builtinLiveOff");
            if (off instanceof Boolean) {
                if ((Boolean) off) for (String[] b : BUILTIN_LIVES) out.add(b[1]);
            } else if (off instanceof JSONArray) {
                JSONArray arr = (JSONArray) off;
                for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i, "").trim());
            }
        } catch (Throwable ignore) {
            /* 配置读不出来就当没禁用过 */
        }
        return out;
    }

    /** 还没被删的内置源 URL（前端据此给设置页条目打「内置」标签） */
    private static JSONArray builtinLiveUrls() {
        JSONArray out = new JSONArray();
        java.util.Set<String> off = disabledBuiltin();
        for (String[] b : BUILTIN_LIVES) if (!off.contains(b[1])) out.put(b[1]);
        return out;
    }

    /**
     * 取直播源列表：内置的（未删的）排前面，用户自己的接在后面。
     *
     * <p>★ 内置源与用户自己的源**并存**，不是二选一：
     *   内置源是"开盒即用"的默认项，用户加了自己的源之后它仍然在列表里
     *   （可以逐条删）。二选一的话，用户加了源就等于悄悄丢掉默认源，反而更意外。
     *
     * <p>去重：表里已经有同一个 url 时不再补（保留用户在表里给的名字）。
     *
     * <p>顺序有意义：`liveMerge` 合并时**分类归属由源顺序决定**（先出现的
     *   分类在前），内置的两条一个中文分类体系、一个英文，把 Guovin 放前面
     *   更符合看电视的习惯。
     */
    private static JSONArray livesWithBuiltin() {
        try {
            JSONArray local = tableOrEmpty(loadConfigSites(), "lives");
            JSONArray out = new JSONArray();
            java.util.Set<String> present = new java.util.HashSet<>();
            for (int i = 0; i < local.length(); i++) {
                JSONObject l = local.optJSONObject(i);
                if (l == null) continue;
                out.put(l);
                present.add(l.optString("url", "").trim());
            }
            java.util.Set<String> off = disabledBuiltin();
            for (String[] b : BUILTIN_LIVES) {
                if (off.contains(b[1]) || present.contains(b[1])) continue;
                out.put(new JSONObject().put("name", b[0]).put("url", b[1]));
            }
            return out;
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    /** 串行化「写配置文件 + 重建站点表」这一段，见 handleConfigImport 里的说明。 */
    private static final Object IMPORT_LOCK = new Object();

    /** 导入前的配置快照文件名（只留一份，语义是"撤销上一次导入"）。 */
    private static final String CONFIG_BACKUP = "config.sites.json.bak";

    /**
     * 把当前 config.sites.json 存成备份。
     *
     * <p>当前文件不存在（全新安装）时什么都不做：那说明没有"旧配置"可保护，
     * 写一个空备份反而会让「恢复」按钮看起来可用、点了却清空一切。
     */
    private static void backupConfigBeforeImport() {
        try {
            java.io.File cur = core.DataPaths.file("config.sites.json");
            if (!cur.isFile() || cur.length() == 0) return;
            java.nio.file.Files.copy(cur.toPath(),
                    core.DataPaths.file(CONFIG_BACKUP).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            // 备份失败不能挡住导入本身（用户明确点了"导入"），但要留痕
            System.out.println("[config] 备份失败: " + t);
        }
    }

    /** 备份是否存在 + 什么时候备的 + 里面有多少站，供前端决定要不要显示「恢复」。 */
    private static JSONObject configBackupInfo() {
        JSONObject no = new JSONObject();
        try {
            no.put("exists", false);
        } catch (Throwable ignored) {
        }
        try {
            java.io.File f = core.DataPaths.file(CONFIG_BACKUP);
            if (!f.isFile() || f.length() == 0) return no;
            int sites = 0;
            try {
                JSONArray a = new JSONObject(new String(
                        java.nio.file.Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8)).optJSONArray("sites");
                if (a != null) sites = a.length();
            } catch (Exception ignored) {
            }
            JSONObject out = new JSONObject();
            out.put("exists", true);
            out.put("at", f.lastModified());
            out.put("bytes", f.length());
            out.put("sites", sites);
            return out;
        } catch (Throwable t) {
            return no;
        }
    }

    /** 把备份文件覆盖回 config.sites.json 并重建站点表。 */
    private static boolean restoreConfigBackup() {
        try {
            java.io.File bak = core.DataPaths.file(CONFIG_BACKUP);
            if (!bak.isFile() || bak.length() == 0) return false;
            String txt = new String(java.nio.file.Files.readAllBytes(bak.toPath()),
                    StandardCharsets.UTF_8);
            // 先解析一次：坏掉的备份写回去等于把配置弄没
            new JSONObject(txt);
            synchronized (IMPORT_LOCK) {
                if (!core.DataPaths.write("config.sites.json", txt)) return false;
                invalidateConfigTables();
                rebuildSites();
            }
            return true;
        } catch (Throwable t) {
            System.out.println("[config] 恢复备份失败: " + t);
            return false;
        }
    }

    /**
     * jar 内所有顶层 spider 类名（不含 {@code $} 内部类）。
     *
     * <p>读取 jar 条目而不是硬编码类名：写死的名单一旦换 jar 就失效，
     * 而"api 里的 csp_X 在不在 jar 里"这个判断正是导入时跳过的依据。
     * 读不到（jar 不在 classpath）时返回 null，调用方据此退化为"试加载"。
     */
    private static java.util.Set<String> jarSpiderClasses() {
        File f = findSpiderJar();
        if (f == null) return null;
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(f)) {
            java.util.Set<String> out = new java.util.HashSet<>();
            String prefix = "com/github/catvod/spider/";
            java.util.Enumeration<java.util.jar.JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!n.startsWith(prefix) || !n.endsWith(".class")) continue;
                String cls = n.substring(prefix.length(), n.length() - 6);
                // 只要顶层类：内部类名里带 $，路径里还有 / 的是别的子包
                if (cls.indexOf('/') >= 0 || cls.indexOf('$') >= 0) continue;
                out.add(cls);
            }
            return out;
        } catch (Throwable t) {
            System.out.println("[import] jar entries unreadable: " + t);
            core.LogBuffer.warn("import", "jar entries unreadable: " + t);
            return null;
        }
    }

    /** 类名是否真的能被加载（jar 条目存在 ≠ 能实例化，但用来判断"有没有这个类"够了）。 */
    private static boolean spiderClassPresent(String cls, java.util.Set<String> fromJar) {
        if (fromJar != null) return fromJar.contains(cls);
        try {
            platform.Bridge.loadClass("com.github.catvod.spider." + cls);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 探测多仓索引：列出每个仓能不能用、有多少站我们**真的能加载**。
     *
     * <p><b>为什么做这个</b>：多仓索引（{@code {"urls":[{url,name}]}}）里十几个仓
     * 质量差异极大 —— 实测同一份索引里，有的仓 96 个 csp_ 站点全部能在我们的 jar 里
     * 找到对应类，有的仓 71 个里只有 8 个能对应上（其余是 CandyMuj 系列、网盘分享类，
     * jar 里根本没有这些类）。不带这一层判断就让用户随便挑，他很可能挑到一个
     * 导入后"站点数很多但全是跳过"的仓，然后以为功能坏了。
     *
     * <p><b>为什么不发搜索请求</b>：只判断"结构上能不能用"（可达 + JSON 合法 +
     * jar 里有这个类）。真正发一次搜索要十几秒乘以十几个仓，用户等不起；
     * 而且今天能搜不代表明天能搜，探测的目的只是筛掉明显不可用的。
     *
     * <p><b>只读</b>：不写 data 目录、不动运行时站点表。用户选定之后才走
     * /api/config/import。
     *
     * <p>POST body：{@code {"url":"<索引地址>"}}，返回
     * {@code {ok, entries:[{name,url,ok,error,sites,csp,inJar,reason}]}}。
     */
    private static void handleConfigDepot(HttpExchange ex) throws Exception {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"code\":-1,\"msg\":\"POST only\"}");
            return;
        }
        String body = read(ex.getRequestBody());
        JSONObject in;
        try {
            in = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (Exception e) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"bad json body\"}");
            return;
        }
        String url = in.optString("url", "").trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"url must be http(s)\"}");
            return;
        }
        final List<String> proxies = proxiesFromBody(in);
        String raw = fetchTextAccel(url, IMPORT_TIMEOUT_MS, proxies);
        if (raw == null || raw.trim().isEmpty()) {
            sendJson(ex, 502, "{\"code\":-1,\"msg\":\"fetch failed: " + url + "\"}");
            return;
        }
        JSONObject doc;
        try {
            doc = parseJsonLoose(raw);
        } catch (Exception e) {
            // 同上：JSONException 的 message 可能带整段二进制响应体
            sendJson(ex, 400, "{\"code\":-1,\"msg\":" + JSONObject.quote(
                    friendlyProbeError(e)) + "}");
            return;
        }
        JSONArray urls = doc.optJSONArray("urls");
        if (urls == null || urls.length() == 0) {
            /*
             * 不是多仓：它自己就是一份配置。直接回一条 entry，
             * 前端不必为"单仓地址"和"多仓索引"写两套流程。
             */
            sendJson(ex, 200, new JSONObject().put("ok", true)
                    .put("entries", new JSONArray().put(
                            probeOneRepo("当前地址", url, doc, proxies))).toString());
            return;
        }

        java.util.Set<String> jarClasses = jarSpiderClasses();
        final java.util.Set<String> known = jarClasses;
        // 并发探：十几个仓串行要一分多钟（每个都可能连不上、要等到超时）
        java.util.List<java.util.concurrent.Future<JSONObject>> futures = new ArrayList<>();
        java.util.List<String> names = new ArrayList<>();
        java.util.List<String> targets = new ArrayList<>();
        for (int i = 0; i < urls.length(); i++) {
            JSONObject u = urls.optJSONObject(i);
            if (u == null) continue;
            String uu = u.optString("url", "").trim();
            if (uu.isEmpty()) continue;
            String nm = u.optString("name", "仓 " + (i + 1));
            names.add(nm);
            targets.add(uu);
        }
        final JSONArray out = new JSONArray();
        // 用一个独立的小池子做限流并发，不用 FANOUT：探测是 IO 等待型，
        // 扔进 FANOUT 会跟正在跑的搜索抢线程（那个池子只有 cores*2）。
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            for (int i = 0; i < targets.size(); i++) {
                final String nm = names.get(i);
                final String uu = targets.get(i);
                futures.add(pool.submit(() -> probeOneRepo(nm, uu, null, proxies)));
            }
            long deadline = System.currentTimeMillis() + IMPORT_TIMEOUT_MS + 8_000L;
            for (java.util.concurrent.Future<JSONObject> f : futures) {
                long left = deadline - System.currentTimeMillis();
                JSONObject o;
                try {
                    if (left <= 0) throw new java.util.concurrent.TimeoutException();
                    o = f.get(left, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    o = new JSONObject().put("name", "?").put("ok", false)
                            .put("error", "timeout");
                    try {
                        f.cancel(true);
                    } catch (Exception ignored) {
                    }
                }
                try {
                    out.put(o);
                } catch (Throwable ignored) {
                }
            }
        } finally {
            pool.shutdownNow();
        }
        // 按"能用的站数"降序：用户最可能直接选第一个
        JSONArray sorted = new JSONArray();
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < out.length(); i++) list.add(out.optJSONObject(i));
        list.removeIf(java.util.Objects::isNull);
        list.sort((a, b) -> Integer.compare(
                b.optInt("inJar", 0) * 1000 + (b.optBoolean("ok") ? 1 : 0),
                a.optInt("inJar", 0) * 1000 + (a.optBoolean("ok") ? 1 : 0)));
        for (JSONObject j : list) sorted.put(j);
        sendJson(ex, 200, new JSONObject().put("ok", true).put("entries", sorted).toString());
    }

    /**
     * 探一个仓。{@code preloaded} 非空时用它（省一次重复拉取）。
     *
     * <p>返回的 {@code inJar} 是"这个仓里有多少 csp_ 站点在我们的 jar 里有对应类"，
     * 这是判断"导入后能不能真用"的核心指标——站点数再大，类加载不到也是跳过。
     */
    private static JSONObject probeOneRepo(String name, String url, JSONObject preloaded, List<String> proxies) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("url", url);
            JSONObject doc = preloaded;
            if (doc == null) {
                String raw = fetchTextAccel(url, IMPORT_TIMEOUT_MS, proxies);
                if (raw == null || raw.trim().isEmpty()) {
                    o.put("ok", false).put("error", "拉取失败（连不上或为空）");
                    return o;
                }
                doc = parseJsonLoose(raw);
            }
            /*
             * 再套一层：仓里也可能又是多仓（嵌套索引）。这里只跟一层，
             * 与 import 的处理保持一致 —— 深挖三层以上没有实际收益，
             * 而每一层都是一个可能卡住的网络请求。
             */
            JSONArray nested = doc.optJSONArray("urls");
            if (nested != null && nested.length() > 0) {
                JSONObject u0 = nested.optJSONObject(0);
                String uu = u0 == null ? "" : u0.optString("url", "");
                if (uu.isEmpty()) {
                    o.put("ok", false).put("error", "索引里没有可用地址");
                    return o;
                }
                String raw2 = fetchTextAccel(uu, IMPORT_TIMEOUT_MS, proxies);
                if (raw2 == null || raw2.trim().isEmpty()) {
                    o.put("ok", false).put("error", "内层仓拉取失败");
                    return o;
                }
                doc = parseJsonLoose(raw2);
                o.put("nested", nested.length());
            }
            JSONArray sites = doc.optJSONArray("sites");
            if (sites == null) {
                o.put("ok", false).put("error", "没有 sites 数组");
                return o;
            }
            java.util.Set<String> known = jarSpiderClasses();
            int csp = 0, inJar = 0, dir = 0;
            for (int i = 0; i < sites.length(); i++) {
                JSONObject s = sites.optJSONObject(i);
                if (s == null) continue;
                String api = s.optString("api", "").trim();
                if (api.startsWith("csp_")) {
                    csp++;
                    String cls = api.substring(4);
                    // known == null 表示读不到 jar：这时不能断言"缺"，
                    // 保守地全算作命中，让用户仍能导入（与 import 的退化策略一致）
                    if (known == null || known.contains(cls)) inJar++;
                } else if (s.optInt("type", 3) == 1) {
                    dir++;
                }
            }
            o.put("ok", true);
            o.put("sites", sites.length());
            o.put("csp", csp);
            o.put("inJar", inJar);
            o.put("direct", dir);
            /*
             * 给人看的一句话。用户不需要知道 csp_ 是什么，只需要知道
             * "这个仓导进去有多少站真能用"。
             */
            if (inJar == 0 && dir == 0) {
                o.put("hint", "这个仓的站点我们一个都加载不了");
            } else if (inJar + dir < 5) {
                o.put("hint", "只能用到很少几站");
            } else {
                o.put("hint", "可用 " + (inJar + dir) + " 站");
            }
            return o;
        } catch (Throwable t) {
            try {
                o.put("name", name);
                o.put("url", url);
                o.put("ok", false);
                o.put("error", friendlyProbeError(t));
            } catch (Throwable ignored) {
            }
            return o;
        }
    }

    /**
     * 把探测异常翻成一句人话。
     *
     * <p>踩过：某些仓返回的是 base64 / 二进制（实测两条仓的响应体是一大串十六进制），
     * org.json 抛的 JSONException 会把**整段内容**拼进 message。直接显示出来是
     * 两三百字乱码，用户看不懂，还把整行撑爆。
     */
    private static String friendlyProbeError(Throwable t) {
        String msg = t.getMessage() == null ? "" : t.getMessage();
        if (t instanceof org.json.JSONException
                || java.util.regex.Pattern.compile("Value\\s+[0-9a-f]{20,}",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(msg).find()) {
            return "返回的不是 JSON（可能是加密/二进制内容）";
        }
        if (t instanceof java.net.SocketTimeoutException
                || t instanceof java.net.ConnectException
                || t instanceof java.net.UnknownHostException) {
            return "连不上（超时 / 域名解析失败）";
        }
        String s = t.getClass().getSimpleName() + ": " + msg;
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    /**
     * 导入外部 CatVod 配置。
     *
     * <p>多仓（{@code {"urls":[{url,name}]}}）只取第一个仓再拉一层，不聚合全部。
     * 这不是偷懒：Fongmi 的 {@code VodConfig.parseDepot} 也只 {@code load(configs.get(0))}，
     * 其余仓只存成列表供用户手动切换。多仓本质是"收藏夹"，聚合所有仓还会让
     * 同名站点互相覆盖、搜索目标翻几倍——与真实客户端行为反而不一致。
     *
     * <p>POST body: {@code {"url":"http://..."}}，返回
     * {@code {ok, sites, skipped:[{api,reason}], depot:[{url,name}]}}。
     */
    private static void handleConfigImport(HttpExchange ex) throws Exception {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            JSONObject cur = lastImport;
            if (cur == null) {
                sendJson(ex, 200, new JSONObject().put("ok", true).put("imported", false).toString());
                return;
            }
            sendJson(ex, 200, cur.toString());
            return;
        }

        String body = read(ex.getRequestBody());
        String url;
        /*
         * 导入策略：
         *   mode       replace（默认，整表换掉）| merge（保留现有，把新的并进来）
         *   onConflict 只对 merge 有效：keep（默认，留现有的）| replace（用导入的）| both
         *   dryRun     true 时不写盘，只返回 diff —— 前端"预览"用
         *
         * 为什么要有 merge：导入是破坏性的，用户想"再补一份配置"时只能整表换掉，
         * 自己手加的站和另一份配置里独有的源全部消失，而且只有一次 .bak 可撤。
         */
        String mode = "replace";
        String onConflict = "keep";
        boolean dryRun = false;
        List<String> proxiesParsed = new ArrayList<>();
        // 请求体是否明确带了 proxies：带了才更新 jar 的加速候选。
        // 老客户端不带这个字段，不能当成"用户关掉了加速"把已存配置清空。
        boolean hasProxies = false;
        // 是否用导入配置里的顶层 spider 覆盖本地已有的。
        // 默认 false（保护用户手改的 jar 地址）；安装源包时必须传 true ——
        // 老用户的本地 spider 可能指向已失效的上游 dex 包，不覆盖就永远
        // ClassNotFoundException（实测升级迁移场景）。
        boolean replaceSpider = false;
        try {
            JSONObject in = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
            url = in.optString("url", "");
            mode = "merge".equalsIgnoreCase(in.optString("mode", "")) ? "merge" : "replace";
            String oc = in.optString("onConflict", "");
            if ("replace".equalsIgnoreCase(oc) || "both".equalsIgnoreCase(oc)) {
                onConflict = oc.toLowerCase();
            }
            dryRun = in.optBoolean("dryRun", false);
            proxiesParsed = proxiesFromBody(in);
            hasProxies = in.has("proxies");
            replaceSpider = in.optBoolean("replaceSpider", false);
        } catch (Exception e) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"bad json body\"}");
            return;
        }
        url = url.trim();
        url = idnUrl(url);
        if (url.isEmpty()) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"missing url\"}");
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"url must be http(s)\"}");
            return;
        }
        final boolean isMerge = "merge".equals(mode);
        final String conflictMode = onConflict;
        final boolean preview = dryRun;
        final List<String> proxies = proxiesParsed;
        // 配置 JSON 抓取用 proxies（fetchTextAccel）；jar 下载也要用同一套候选，
        // 否则 spider 指向 raw.githubusercontent 时"配置能拉、jar 拉不下来"。
        if (hasProxies) core.Jars.setAccelProxies(proxies);

        String configBase = url;
        String raw = null;
        // 确认导入时优先复用刚刚预览拉下来的那份（60s 内、同一 url）
        String[] slot = importPreviewSlot;
        if (!preview && slot != null && url.equals(slot[0])) {
            try {
                if (System.currentTimeMillis() - Long.parseLong(slot[2]) < 60_000L) {
                    raw = slot[1];
                    System.out.println("[import] reuse preview payload, skip refetch: " + url);
                }
            } catch (Exception ignored) {
            }
        }
        if (raw == null) raw = fetchTextAccel(url, IMPORT_TIMEOUT_MS, proxies);
        if (raw == null || raw.trim().isEmpty()) {
            sendJson(ex, 502, "{\"code\":-1,\"msg\":\"fetch failed: " + url + "\"}");
            return;
        }

        JSONObject doc;
        try {
            doc = parseJsonLoose(raw);
        } catch (Exception e) {
            // 伪装文件（base64 / 前后夹注释）在这里就要给出可读原因，
            // 否则前端只能看到 JSONException 的 "End of input"。
            sendJson(ex, 400, "{\"code\":-1,\"msg\":" + JSONObject.quote(
                    "not a JSON config: " + e.getMessage()) + "}");
            return;
        }

        // 多仓：只取第一个，其余只回给前端当可选列表
        JSONArray depotOut = new JSONArray();
        JSONArray urls = doc.optJSONArray("urls");
        if (urls != null) {
            for (int i = 0; i < urls.length(); i++) {
                JSONObject u = urls.optJSONObject(i);
                if (u == null) continue;
                depotOut.put(new JSONObject()
                        .put("url", u.optString("url", ""))
                        .put("name", u.optString("name", "")));
            }
            String first = urls.length() > 0
                    ? (urls.optJSONObject(0) == null ? "" : urls.optJSONObject(0).optString("url", ""))
                    : "";
            if (!first.isEmpty()) configBase = first;
            if (first.isEmpty()) {
                sendJson(ex, 400, "{\"code\":-1,\"msg\":\"depot has no usable url\"}");
                return;
            }
            String inner = fetchTextAccel(first, IMPORT_TIMEOUT_MS, proxies);
            if (inner == null || inner.trim().isEmpty()) {
                sendJson(ex, 502, "{\"code\":-1,\"msg\":\"fetch failed: " + first + "\"}");
                return;
            }
            try {
                doc = parseJsonLoose(inner);
            } catch (Exception e) {
                sendJson(ex, 400, "{\"code\":-1,\"msg\":" + JSONObject.quote(
                        "depot entry is not a JSON config: " + e.getMessage()) + "}");
                return;
            }
        }

        JSONArray srcSites = doc.optJSONArray("sites");
        if (srcSites == null) {
            sendJson(ex, 400, "{\"code\":-1,\"msg\":\"config has no sites array\"}");
            return;
        }

        // JVM 导入期预处理：只有 classes.dex 的 jar 先转成 class jar，
        // 再写回 spiderJvm / 站点 jar；Android 端跳过。
        JSONObject dex2jvm = core.Dex2Jvm.rewrite(doc, configBase);

        java.util.Set<String> jarClasses = jarSpiderClasses();
        invalidateConfigTables();
        JSONArray kept = new JSONArray();
        JSONArray skipped = new JSONArray();
        int okCount = 0;

        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < srcSites.length(); i++) {
            JSONObject s = srcSites.optJSONObject(i);
            if (s == null) continue;
            String api = s.optString("api", "").trim();
            String key = s.optString("key", "").trim();
            String name = s.optString("name", key);
            int type = s.optInt("type", 3);

            String reason = null;
            if (api.isEmpty()) {
                reason = "缺少 api 字段";
            } else if (api.endsWith(".js") || api.startsWith("js:")) {
                // drpy 脚本：相对路径必须按**配置文件的地址**补全。
                // 不补全的话 `./api/drpy2.min.js` 在运行时那边无从解析，
                // 到时候每个源都得靠猜。这里补成绝对地址落盘，一次算清。
                if (!api.startsWith("http") && !api.startsWith("js:")) {
                    String rel = api.startsWith("./") ? api.substring(2) : api;
                    api = resolveUrl(configBase, rel);
                }
            } else if (api.startsWith("node:") || api.endsWith(".py")) {
                reason = "运行时不支持";
            } else if (api.startsWith("csp_")) {
                String cls = api.substring(4);
                /*
                 * ★ 站点声明了外部 jar（或本次配置有顶层 spider）时，不能按**内置 jar**
                 * 判"缺类"——类在外部 jar 里，内置 jar 当然找不到。
                 *
                 * 踩过（2026-11-06 实测）：导入一份"站点级 jar 指向外部"的配置，
                 * sites 加 0 条、skipped 里写"缺少 spider 类 Jable"，整站静默消失。
                 * 真伪交给导入后的 core.Jars.reload + 首次调用（失败会进健康表），
                 * 静默丢站比慢一点更难查。
                 */
                boolean externalJar = !s.optString("jar", "").isEmpty()
                        || !doc.optString("spider", "").isEmpty()
                        || !doc.optString("spiderJvm", "").isEmpty();
                if (!externalJar && !spiderClassPresent(cls, jarClasses)) {
                    reason = "缺少 spider 类 " + cls;
                }
            } else if (!api.startsWith("http")) {
                // 既不是 csp_ 类名、也不是 http 地址：jar 和直连两条路都走不通
                reason = "无法识别的 api 形式";
            }
            if (reason != null) {
                skipped.put(new JSONObject().put("api", api).put("key", key).put("reason", reason));
                continue;
            }

            // 写回磁盘的形状与 loadMultiInstances 读的一致（csp_ 才能展开多实例），
            // 但直连站点（type=1 的苹果CMS）也要落盘，否则导入完就丢了。
            JSONObject out = new JSONObject();
            out.put("key", key.isEmpty() ? api : key);
            out.put("name", name);
            out.put("type", type);
            out.put("api", api);
            Object ext = s.opt("ext");
            // drpy 站点的 ext 通常是**规则文件路径**（`./js/xxx.js`）。它和 api
            // 一样必须按配置地址补成绝对 URL，否则运行时无从解析——引擎自己
            // 那条取规则的路子又会把结果吞成空串（见 server/drpy/host.js）。
            if (ext instanceof String) {
                String e = ((String) ext).trim();
                if ((e.startsWith("./") || e.startsWith("../")) && e.endsWith(".js")) {
                    ext = resolveUrl(configBase, e.substring(2));
                }
            }
            if (ext != null && ext != JSONObject.NULL) out.put("ext", ext);
            for (String k : new String[]{"searchable", "filterable", "changeable", "playerType", "timeout", "jar", "style"}) {
                Object v = s.opt(k);
                if (v != null && v != JSONObject.NULL) out.put(k, v);
            }
            // 直连标记：前端据此显示"苹果CMS直连"，与需要 jar 的 csp_ 站点区分开
            if (type == 1 && api.contains("api.php/provide/vod")) {
                out.put("_direct", "苹果CMS直连");
            }
            kept.put(out);
            okCount++;
            if (api.startsWith("csp_")) {
                seen.merge(api.substring(4), 1, Integer::sum);
            }
        }

        // 保留原有七表：导入只应替换 sites，把 parses/rules/ads… 一起冲掉会让
        // /config.json 出口 A 从"十项齐"退化成"只有 sites"（实测：导入一次后
        // parses/rules 全变 0，TV 端的解析器和去广告就静默失效）。
        JSONObject saved = loadConfigSites();
        JSONArray existingSites = tableOrEmpty(saved, "sites");
        JSONObject plan = planSites(existingSites, kept, isMerge, conflictMode);
        JSONObject diff = plan.optJSONObject("diff");
        diff.put("healthLost", healthLostKeys(existingSites, plan.optJSONArray("sites")));
        diff.put("mode", isMerge ? "merge" : "replace");
        diff.put("onConflict", isMerge ? conflictMode : "");

        if (preview) {
            /*
             * dryRun：只算不写。用户点「导入」前先看清会新增多少、更新多少、
             * 多少站冲突、哪些站会消失、哪些停用状态会失效，再决定。
             * 原来一步到位，点错仓就只能靠一层 .bak 回滚，而且回滚本身也是覆盖。
             */
            /*
             * 一次把「四种并法各自会怎样」全算出来（merge×keep/replace/both + replace）。
             *
             * 只回当前策略一份 diff 的后果：用户想比较「追加 vs 替换」必须先切按钮，
             * 而切按钮会清空预览、只能再点一次「读取并预览」——dryRun 每次都会
             * fetchTextAccel 重新抓配置，等于比较一次多等一轮网络。于是「先看结果
             * 再决定怎么并」这个顺序在界面上根本做不到，只能把策略摆到读取之前。
             *
             * planSites 是纯内存比对：不写盘、不联网、不改入参对象（同 key 更新走
             * new JSONObject(...) 拷贝）。四次调用成本可忽略，所以直接算齐。
             * 顶层 diff 仍是当前策略那份——老客户端只要它，行为不变。
             *
             * 结构：plans.merge.{keep|replace|both} + plans.replace。注意
             * plans.merge.replace 是**冲突策略**（用导入的覆盖），与顶层 replace 模式
             * 不是一回事，取值时别把 mode 和 onConflict 弄混。
             */
            JSONObject plans = new JSONObject();
            JSONObject mergePlans = new JSONObject();
            for (String cm : new String[]{"keep", "replace", "both"}) {
                JSONObject p = planSites(existingSites, kept, true, cm);
                JSONObject d = p.optJSONObject("diff");
                d.put("healthLost", healthLostKeys(existingSites, p.optJSONArray("sites")));
                d.put("mode", "merge");
                d.put("onConflict", cm);
                mergePlans.put(cm, d);
            }
            JSONObject replacePlan = planSites(existingSites, kept, false, "");
            JSONObject replaceDiff = replacePlan.optJSONObject("diff");
            replaceDiff.put("healthLost", healthLostKeys(existingSites, replacePlan.optJSONArray("sites")));
            replaceDiff.put("mode", "replace");
            replaceDiff.put("onConflict", "");
            plans.put("merge", mergePlans);
            plans.put("replace", replaceDiff);

            JSONObject pv = new JSONObject()
                    .put("ok", true)
                    .put("dryRun", true)
                    .put("url", url)
                    .put("mode", isMerge ? "merge" : "replace")
                    .put("onConflict", isMerge ? conflictMode : "")
                    .put("sites", okCount)
                    .put("totalSites", siteCount())
                    // 本地站点表**原始条数**（含未实现的），与 diff.total 同一口径。
                    // 用 siteCount() 会把"只有内置站能实现"的场景报成 1 个，
                    // 跟下面「写入后共 5 条」对不上（实测空壳环境）。
                    .put("existingSites", existingSites.length())
                    // 内置基线条数：这些站不在配置文件里，替换导入也删不掉。
                    // 预览里要报出来，否则用户以为「替换 = 清空」。
                    .put("builtinSites", implementedBaselineCount())
                    .put("skipped", skipped)
                    .put("depot", depotOut)
                    .put("dex2jvm", dex2jvm)
                    .put("plans", plans)
                    .put("diff", diff);
            // 存下原文，确认导入时复用（见 importPreviewSlot 的注释）
            importPreviewSlot = new String[]{url, raw, String.valueOf(System.currentTimeMillis())};
            sendJson(ex, 200, pv.toString());
            return;
        }

        saved.put("sites", plan.optJSONArray("sites"));
        /*
         * 顶层 spider（外部 jar 地址）以前只在**导出**时写、导入时丢。
         *
         * 后果很隐蔽：导入一份带自定义 jar 的配置后，站点级 jar 还在、
         * 顶层没了，于是靠顶层 spider 的那些站永远 ClassNotFound（沉默失败）。
         * 与上面七表同一策略：本地缺失才补，已有的优先（用户手改的不覆盖）。
         */
        String importedSpider = doc.optString("spider", "");
        if (!importedSpider.isEmpty()
                && (replaceSpider || saved.optString("spider", "").isEmpty())) {
            saved.put("spider", importedSpider);
        }
        /*
         * spiderJvm 是猫窝自定义字段（Fongmi 忽略）：统一配置里顶层 spider 是
         * dex jar，JVM 用 spiderJvm 指向 class jar。replaceSpider 时如果新配置
         * 没有这个字段，必须清掉本地旧值，否则旧的 class jar 会继续覆盖新 spider。
         */
        String importedSpiderJvm = doc.optString("spiderJvm", "");
        if (replaceSpider) {
            if (importedSpiderJvm.isEmpty()) saved.remove("spiderJvm");
            else saved.put("spiderJvm", importedSpiderJvm);
        } else if (!importedSpiderJvm.isEmpty()
                && saved.optString("spiderJvm", "").isEmpty()) {
            saved.put("spiderJvm", importedSpiderJvm);
        }
        /*
         * ★ 本地还没有的非 sites 表，用**本次导入的那份配置**补齐。
         *
         * 上面"保留原有七表"的前提是本地已有一份完整基线。但 TV 端首次
         * 导入时本地只有 `{}`（GatewayServer.syncConfigFile 建的空壳），
         * 于是导入完磁盘上只剩 sites —— 实测 doh/hosts 全丢，而
         * LocalDns 要靠 doh 表启用 DoH，缺了就有一批源永远搜不到
         * （Gulu/Gz360/AppRJ 等，见 LocalDns 的实测数字）。
         *
         * 只在本地缺失时补，已有的优先：用户手动改过的 parses/规则
         * 不该被下一次导入覆盖掉。
         */
        for (String table : CONFIG_TABLES_TO_SEED) {
            boolean missing = saved.optJSONArray(table) == null
                    || saved.optJSONArray(table).length() == 0;
            if (!missing) continue;
            JSONArray fromImport = doc.optJSONArray(table);
            if (fromImport != null && fromImport.length() > 0) {
                saved.put(table, fromImport);
            }
        }
        /*
         * lives 不再在这里"猜"。
         *
         * 原来这里有个 replaceDeadLives：本地 lives 全是内网/回环地址时才用
         * 导入的那份替换。那段启发式**之所以存在，就是因为直播源没有自己的
         * 入口**，只能搭 sites 导入这趟车，于是要猜用户这份 lives 是不是想换掉。
         * 现在 /api/lives 能单独增删了，导入只管 sites，语义干净：
         * 本地为空时由上面的 CONFIG_TABLES_TO_SEED 补一次，其余一律不动。
         */
        // 写盘 + 重建必须串行：DataPaths.write 的临时文件是固定的
        // "config.sites.json.tmp"，两个导入并发时第二个会撞 AccessDenied /
        // NoSuchFileException（实测 10 并发导入挂 4 个）。锁住整段而不是只锁
        // SITES，否则"文件已写、表还没重建"的中间态会被另一个导入当成基线。
        synchronized (IMPORT_LOCK) {
            /*
             * 覆盖前先备份当前配置。
             *
             * <p>导入是**破坏性**的：它整表替换 sites。用户点错一个仓、
             * 或者挑到"站点数很多但一个都加载不了"的仓之后，
             * 原来的 270 站就没了，而且界面上没有任何回退入口。
             * 这里每次导入前把旧文件存成 config.sites.json.bak，
             * 前端提供「恢复上一次配置」按钮。
             *
             * <p>只留一份（不按时间戳堆）：备份的意义是"撤销刚才那一次"，
             * 留十份只会占地方且让用户不知道该选哪个。
             */
            backupConfigBeforeImport();
            if (!core.DataPaths.write("config.sites.json", saved.toString())) {
                sendJson(ex, 500, "{\"code\":-1,\"msg\":\"cannot write config.sites.json\"}");
                return;
            }

            // 重建站点表。loadMultiInstances 只在 main() 里跑一次，导入后必须再跑，
            // 否则 /api/sites 还是旧表——文件写了但运行时不认，用户会以为导入没生效。
            rebuildSites();
        }

        /*
         * 同步端上的第二套表（TV 的 LocalSites）。
         *
         * ★ 踩过（2026-11-06 实测）：设置页走 /api/config/import 导入后，
         *   网关 47 站、端上 jar 表却是空的，jar 站点全部 ClassNotFoundException，
         *   界面却显示"导入成功"。原因是端上执行另有一张 SharedPreferences 表，
         *   只有 meowTv.importLocalConfig 会写它。
         * 放在锁外：平台侧要读配置、写 prefs、清 loader，不该占着导入锁。
         */
        platform.Bridge.notifyConfigImported(doc.toString(), url, !isMerge);

        JSONObject res = new JSONObject()
                .put("ok", true)
                .put("imported", true)
                .put("url", url)
                .put("mode", isMerge ? "merge" : "replace")
                .put("sites", okCount)
                .put("totalSites", siteCount())
                .put("skipped", skipped)
                .put("depot", depotOut)
                .put("dex2jvm", dex2jvm)
                // 导入完成后也回一份 diff：用户要知道"这次到底加了什么、丢了什么"，
                // 而不是只看到一个站点总数
                .put("diff", diff)
                // 让前端能立刻显示「恢复上一次配置」：导入刚把旧配置挪进备份
                .put("backup", configBackupInfo());
        lastImport = res;
        System.out.println("[import:" + (isMerge ? "merge" : "replace") + "] " + url
                + " -> " + okCount + " sites, " + skipped.length() + " skipped, table now "
                + SITES.size());
        sendJson(ex, 200, res.toString());
    }

    /**
     * 宽松解析：先按 JSON 试；失败了再剥一层伪装（BOM、前后非 JSON 垃圾、base64）。
     *
     * <p>生态里"伪装文件"通常是这两种：前面挂了注释的、以及整份 base64 的。
     * 直接 JSONObject 会抛 "End of input at character 0"，与"这不是配置文件"无法区分。
     */
    private static JSONObject parseJsonLoose(String raw) throws Exception {
        String s = raw.replace("\uFEFF", "").trim();
        int brace = s.indexOf('{');
        if (brace > 0) s = s.substring(brace);
        int last = s.lastIndexOf('}');
        if (last >= 0 && last < s.length() - 1) s = s.substring(0, last + 1);
        try {
            return new JSONObject(s);
        } catch (Exception first) {
            // base64：解出来还是 JSON 才算，否则把原始错误抛回去更准确
            try {
                String dec = new String(Base64.getMimeDecoder().decode(s.replaceAll("\\s", "")),
                        StandardCharsets.UTF_8).trim();
                int b2 = dec.indexOf('{');
                if (b2 >= 0) dec = dec.substring(b2);
                int l2 = dec.lastIndexOf('}');
                if (l2 >= 0) dec = dec.substring(0, l2 + 1);
                return new JSONObject(dec);
            } catch (Exception ignored) {
                throw first;
            }
        }
    }

    /**
     * 用磁盘上的 config.sites.json 重建 SITES/EXT。
     *
     * <p>两个 Map 都是 static final，不能换成新实例（持有引用的地方很多），
     * 只能 clear + put。而搜索线程会并发遍历 SITES.keySet()，
     * 遍历途中被 clear 会抛 ConcurrentModificationException —— 所以整段
     * 重建与遍历都在 SITES 自身上同步（见 searchAll 的 targets 拷贝）。
     */
    private static void rebuildSites() {
        Map<String, String> base = sitesBaseline;
        Map<String, String> baseExt = extBaseline;
        synchronized (SITES) {
            if (base != null) {
                SITES.clear();
                SITES.putAll(base);
            }
            if (baseExt != null) {
                EXT.clear();
                EXT.putAll(baseExt);
            }
            CACHE.clear();
            loadMultiInstances();
            /*
             * 导入换了 jar 地址要立刻重新挂载：否则用户看到的是
             * "导入成功、站还在，但跑的还是旧实现（或直接 ClassNotFound）"。
             */
            core.Jars.reload();
            core.Jars.pinProxyPort(port);
        }
    }

    /**
     * 这个站点名当前**真的有实现**吗？
     *
     * <p>公开壳删掉了 spider.jar / config.sites.json，但源码里仍保留了一份
     * 44 条的默认站点名（历史内置源的显示名）。如果直接把它们下发，空壳会
     * 显示 44 个点进去全失败的“幽灵站”。所以对外输出前统一按这里过滤：
     * <ul>
     *   <li>原生源（目前只有豆瓣）—— 不依赖 jar，编译进后端</li>
     *   <li>classpath / 导入 jar 里能加载到 {@code com.github.catvod.spider.<key>}</li>
     *   <li>配置导入的非 csp_ 站（drpy / 苹果CMS / http 直连）—— API 表里有地址</li>
     * </ul>
     *
     * <p>完整包有 spider.jar 时，44 条都会通过；公开空壳只剩豆瓣。这样
     * “空壳没有源”是接口层面的事实，而不是只靠文档约定。
     */
    private static boolean siteImplemented(String key) {
        if (key == null || key.isEmpty()) return false;
        if (sources.NativeSources.get(key) != null) return true;
        if (spiderClassPresent(key)) return true;
        String api = API.get(key);
        return api != null && !api.isEmpty() && !api.startsWith("csp_");
    }

    /**
     * 对外可见吗 = 有实现 **且** 被配置承认。
     *
     * <p>★ 配置文件里已经有站点时，源码里那份 44 条静态基线（历史内置源名）
     * 里**没进配置**的条目不再下发。理由：`rebuildSites()` 每次都先把基线
     * 灌回 SITES 再叠配置，于是「替换式导入」重启后又会冒出荐片这类跑不起来
     * 的基线站 —— 用户删不掉、列表里永远有 500（2026-11-11 实测）。
     *
     * <p>原生源（豆瓣 / 预告 / 配置中心）不依赖配置，永远可见。
     */
    private static boolean siteVisible(String key) {
        if (!siteImplemented(key)) return false;
        if (sources.NativeSources.get(key) != null) return true;
        if (!CONFIG_SITE_KEYS.isEmpty() && !CONFIG_SITE_KEYS.contains(key)) {
            Map<String, String> base = sitesBaseline;
            if (base != null && base.containsKey(key)) return false;
        }
        return true;
    }

    /** 对外可见站点键的快照（过滤掉没有实现的默认名与基线幽灵）。 */
    private static List<String> implementedSiteKeys() {
        List<String> out = new ArrayList<>();
        synchronized (SITES) {
            for (String key : SITES.keySet()) {
                if (siteVisible(key)) out.add(key);
            }
        }
        return out;
    }

    /** 对外可见站点数（health / 导入统计用）。 */
    private static int implementedSiteCount() {
        return implementedSiteKeys().size();
    }

    /** 对外可见站点的 ext 数（health 用）。 */
    private static int implementedExtCount() {
        int n = 0;
        synchronized (EXT) {
            for (String key : EXT.keySet()) {
                if (siteVisible(key)) n++;
            }
        }
        return n;
    }

    /** 启动基线里真正有实现的条数（导入预览的 builtinSites 用）。 */
    private static int implementedBaselineCount() {
        Map<String, String> base = sitesBaseline;
        if (base == null) return 0;
        int n = 0;
        for (String key : base.keySet()) {
            if (siteImplemented(key)) n++;
        }
        return n;
    }

    /** 站点总数的原子快照（导入会在另一线程 clear+put）。 */
    private static int siteCount() {
        return implementedSiteCount();
    }

    /** 启动后立刻存一份基线；必须在 loadMultiInstances() 之前调用。 */
    private static void snapshotBaseline() {
        sitesBaseline = new LinkedHashMap<>(SITES);
        extBaseline = new HashMap<>(EXT);
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, String> query(HttpExchange ex) throws IOException {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return q;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            q.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"),
                    URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
        }
        return q;
    }

    private static String sitesJson() throws Exception {
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, String> e : SITES.entrySet()) {
            // 空壳里默认表有 44 个名字，但没有 jar 的站不能当“可用源”下发；
            // 有配置时，基线里没进配置的默认站也不再下发（见 siteVisible）
            if (!siteVisible(e.getKey())) continue;
            JSONObject o = new JSONObject().put("key", e.getKey()).put("name", e.getValue())
                    .put("api", API.getOrDefault(e.getKey(), "csp_" + e.getKey()));
            String ext = EXT.get(e.getKey());
            if (ext != null) o.put("ext", ext);
            routes.HealthRoutes.SiteHealth h = routes.HealthRoutes.peek(e.getKey());
            if (h != null && (h.ok > 0 || h.fail > 0 || h.disabled || h.unsupported)) {
                /*
                 * ★ lastCostMs / lastCount / unsupported 必须一起下发。
                 *
                 * 踩过：前端按 `lastCostMs <= 1s` 把站点切成"快批/慢批"来做
                 * 逐步显示，但这三个字段没在 /api/sites 里 —— 前端读到的全是
                 * undefined，于是 270 个源全被判成快批，分批等于没做
                 * （实测：搜索期间结果数只从 0 跳到 34，中间没有任何增量）。
                 */
                o.put("health", new JSONObject()
                        .put("ok", h.ok).put("fail", h.fail)
                        .put("degraded", h.degraded)
                        .put("disabled", h.disabled)
                        .put("unsupported", h.unsupported)
                        .put("unsupportedReason", h.unsupportedReason)
                        .put("lastCount", h.lastCount)
                        .put("lastCostMs", h.lastCostMs)
                        .put("slowEmpty", h.slowEmpty)
                        .put("timeoutStreak", h.timeoutStreak)
                        .put("lastTimeoutAt", h.lastTimeoutAt)
                        .put("lastOkAt", h.lastOkAt)
                        .put("lastFailAt", h.lastFailAt)
                        // 与后端搜索时的真实跳过判据同源，前端不再自己猜
                        .put("skippedTimeout", routes.HealthRoutes.isTimeoutSkipped(e.getKey()))
                        .put("skippedFailed", routes.HealthRoutes.isFailedSkipped(e.getKey()))
                        .put("lastError", h.lastError));
            }
            arr.put(o);
        }
        return new JSONObject().put("sites", arr).toString();
    }

    private static String configJson() throws Exception {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, String> e : SITES.entrySet()) {
            // 与 /api/sites 同源：没有实现的名字不下发（否则 TV 导入会拿到幽灵站）
            if (!siteImplemented(e.getKey())) continue;
            JSONObject o = new JSONObject().put("key", e.getKey()).put("name", e.getValue())
                    .put("type", 3).put("api", "csp_" + e.getKey());
            String ext = EXT.get(e.getKey());
            if (ext != null) o.put("ext", ext);
            routes.HealthRoutes.SiteHealth h = routes.HealthRoutes.peek(e.getKey());
            if (h != null && (h.ok > 0 || h.fail > 0 || h.disabled || h.unsupported)) {
                o.put("health", new JSONObject()
                        .put("ok", h.ok).put("fail", h.fail)
                        .put("degraded", h.degraded)
                        .put("disabled", h.disabled)
                        .put("unsupported", h.unsupported)
                        .put("lastCount", h.lastCount)
                        .put("lastCostMs", h.lastCostMs)
                        .put("timeoutStreak", h.timeoutStreak)
                        .put("lastTimeoutAt", h.lastTimeoutAt)
                        .put("lastOkAt", h.lastOkAt)
                        .put("lastFailAt", h.lastFailAt)
                        // 与后端搜索时的真实跳过判据同源，前端不再自己猜
                        .put("skippedTimeout", routes.HealthRoutes.isTimeoutSkipped(e.getKey()))
                        .put("skippedFailed", routes.HealthRoutes.isFailedSkipped(e.getKey()))
                        .put("lastError", h.lastError));
            }
            arr.put(o);
        }
        root.put("sites", arr);
        /*
         * 带上 lives：CatVod 生态里直播源就是配置的 lives 表，别的客户端
         * （电视台 TV / zyfun 一类）拿这份 /config.json 导入时，原来只得到
         * 点播站、一条直播源都没有 —— 它们那边的直播页是空的，而我们从
         * 界面上看不出差别。
         */
        JSONArray lives = livesWithBuiltin();
        if (lives.length() > 0) root.put("lives", lives);
        return root.toString();
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange ex, int code, String body) throws IOException {
        sendText(ex, code, body, "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange ex, int code, String body, String type) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        /*
         * ★ 所有 API 响应一律 no-store（2026-11-02 实测踩过）。
         *
         * 起因：TV 壳为了"静态资源走缓存"设了 WebView 的
         * LOAD_CACHE_ELSE_NETWORK，结果把 `/api/...` 的 JSON 也缓存住了 ——
         * 弹幕搜索永远返回第一次那 1 条（后端其实有 58 条），
         * 只有清 WebView 缓存才恢复，极难定位。
         *
         * 壳侧已改回 LOAD_DEFAULT，但这类"中间层自作主张缓存"防不住
         * （还有运营商代理、企业网关、部分盒子的定制内核）。API 响应本来
         * 就是动态数据，从源头声明不可缓存才是根治。
         *
         * 注意：**图片代理与分片流不走这里**（它们有自己的 sendBytes 与
         * Cache-Control），所以不影响缓存收益。
         */
        ex.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        ex.getResponseHeaders().set("Pragma", "no-cache");
        ex.sendResponseHeaders(code, data.length);
        ex.getResponseBody().write(data);
        ex.close();
    }
}
