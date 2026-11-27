package com.meow.tv;

import android.content.Context;
import android.util.Log;

import com.github.catvod.crawler.Spider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;

/**
 * 端上执行生态 jar（对标 Fongmi 的 JarLoader）。
 *
 * ## 为什么只能在 TV 端做
 *
 * 生态 jar 里是 `classes.dex`，只有 Android 的 Dalvik/ART 能跑。
 * PC（WebView2 + Rust）与后端（JVM）都执行不了 dex——这是运行时本质差异，
 * 不是缺个库。所以这条能力只对 TV 有意义。
 *
 * ## 和 Fongmi 的关系
 *
 * 我们不复用 Fongmi 的 JarLoader，但**完全照它的契约**：
 * - `;md5;` 后缀表示"下载后校验 md5"，不一致就重下（Fongmi 也是重下而不是报错）
 * - `DexClassLoader(parent = app ClassLoader)`，父加载器提供 catvod-api
 * - 类名约定 `com.github.catvod.spider.<api 去掉 csp_ 前缀>`
 *
 * ## 安全边界
 *
 * 只加载用户显式配置的 jar 地址，不做自动发现；md5 校验通过才落盘；
 * 加载失败要有可读原因（哪一步、什么异常），不能静默变成"这个源没内容"。
 */
public final class LocalSpiderHost {

    private static final String TAG = "MeowSpider";
    /**
     * jar 缓存目录。
     *
     * <p><b>必须放 codeCacheDir，不能放 filesDir</b>：Android 10+ 禁止从可写目录
     * 加载 dex（`SecurityException: Writable dex file ... is not allowed`，实测踩过）。
     * codeCacheDir 是系统指定的"可执行代码缓存"位置，DexClassLoader 从那里读才合法。
     */
    private static final String DIR = "spider-jars";

    /** key = api（含 csp_ 前缀），value = 已加载的 Spider 实例 */
    private static final Map<String, Spider> INSTANCES = new ConcurrentHashMap<>();
    /** key = jar 的 url（去 md5 后缀），value = DexClassLoader */
    private static final Map<String, DexClassLoader> LOADERS = new ConcurrentHashMap<>();
    /** 内容 md5 → loader：同内容的 jar 换个 URL 不再建第二份（类对象翻倍）。 */
    private static final Map<String, DexClassLoader> BY_HASH = new ConcurrentHashMap<>();
    /*
     * jar 失败负缓存：key = jarUrl，value = 失败时刻（elapsedRealtime）。
     *
     * ★ 实测（2026-11-27 .76 MuMu）：网关 boot 时 initAfterArgs 对每个站点
     *   spiderClassPresent → loadClassFromJars → prepare 同步下载；配置里的
     *   假 jar（gelonghui PNG）每次 15~45s 网络超时，137 站逐个探测 = 每站
     *   重下一遍，GatewayServer.class 锁全程被 boot 持有 —— 设置页点
     *   「启用本机执行」直接卡死，9978 永远不开。失败结果必须记住，
     *   TTL 内不再重试；恢复窗口靠 TTL 过期，换配置走 clear()。
     */
    private static final Map<String, Long> FAILED_AT = new ConcurrentHashMap<>();
    /** 负缓存有效期：过期后允许重试一次（网络恢复/镜像修复场景）。 */
    private static final long FAILED_TTL_MS = 120_000L;
    /** 最近一次失败原因，给 UI 展示 */
    private static volatile String lastError = "";

    private LocalSpiderHost() {
    }

    public static String lastError() {
        return lastError;
    }

    /**
     * 解析 `url;md5;hash` 三段。没有 md5 段时 md5 为空（不校验）。
     *
     * <p>用 split 而不是正则：`;md5;` 是固定字面量，而 URL 里可能有 `?` 和 `&`，
     * 正则写错一次就要调半天（这个项目在别处踩过转义。
     */
    static String[] splitMd5(String api) {
        if (api == null) return new String[]{"", ""};
        int i = api.indexOf(";md5;");
        if (i < 0) return new String[]{api.trim(), ""};
        return new String[]{api.substring(0, i).trim(), api.substring(i + 5).trim()};
    }

    /**
     * 准备一个 jar，返回是否可用。
     *
     * @param jarUrl  jar 地址（不含 ;md5; 段）
     * @param md5     期望的 md5；空串表示不校验
     */
    static synchronized boolean prepare(Context ctx, String jarUrl, String md5) {
        if (jarUrl == null || jarUrl.isEmpty()) {
            lastError = "jar 地址为空";
            return false;
        }
        if (LOADERS.containsKey(jarUrl)) return true;
        // 负缓存命中：TTL 内直接判失败，不去碰网络（防启动期被坏 jar 卡死，见 FAILED_AT 注释）
        Long failedAt = FAILED_AT.get(jarUrl);
        if (failedAt != null) {
            if (android.os.SystemClock.elapsedRealtime() - failedAt < FAILED_TTL_MS) {
                lastError = "jar 近期下载失败，暂不重试：" + jarUrl;
                return false;
            }
            FAILED_AT.remove(jarUrl);
        }
        try {
            File dir = new File(ctx.getCodeCacheDir(), DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                lastError = "无法创建缓存目录";
                return false;
            }
            File jar = new File(dir, nameOf(jarUrl));
            /*
             * 本地文件（内置 jar）直接当源文件用，不走下载。
             *
             * ★ 踩过：内置 jar 通过 addBundled 传进来的是**绝对路径**
             * （/data/user/0/.../code_cache/spider-jars/bundled.jar），而这里原来
             * 无条件调 download(jarUrl, jar) —— 拿一个本地路径去 new URL() 必然失败，
             * 报「jar 下载失败：/data/user/0/...」。表现是"启用本机执行成功、3 个内置
             * 站点也在表里，但一调就报 jar 下载失败"。判据用 file: 前缀或已存在的
             * 绝对路径，两者都算本地文件。
             */
            boolean localFile = jarUrl.startsWith("file:")
                    || (jarUrl.startsWith("/") && new File(jarUrl).isFile());
            if (localFile) {
                File src = new File(jarUrl.startsWith("file:")
                        ? java.net.URI.create(jarUrl).getPath() : jarUrl);
                if (!src.isFile()) {
                    lastError = "内置 jar 不存在：" + jarUrl;
                    return false;
                }
                // 已经拷到目标位置且大小一致就不重复拷（1MB 每次启动拷一遍没必要）
                if (!jar.isFile() || jar.length() != src.length()) {
                    try (java.io.InputStream in = new java.io.FileInputStream(src);
                         java.io.FileOutputStream out = new java.io.FileOutputStream(jar)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
            } else if (!jar.isFile() || (!md5.isEmpty() && !md5.equals(md5Of(jar)))) {
                // 缓存命中且 md5 一致就不重复下载（Fongmi 同样做法）
                if (!download(jarUrl, jar, md5)) {
                    FAILED_AT.put(jarUrl, android.os.SystemClock.elapsedRealtime());
                    lastError = "jar 下载失败：" + jarUrl;
                    return false;
                }
            }
            if (!md5.isEmpty()) {
                String got = md5Of(jar);
                if (!got.equalsIgnoreCase(md5)) {
                    // 不一致就删掉，避免下次又用坏包
                    // （Fongmi 是重下，我们删了下次自然重下，效果一致但不死循环）
                    //noinspection ResultOfMethodCallIgnored
                    jar.delete();
                    FAILED_AT.put(jarUrl, android.os.SystemClock.elapsedRealtime());
                    lastError = "jar 校验失败：期望 " + md5 + "，实际 " + got;
                    return false;
                }
            }
            File opt = new File(ctx.getCodeCacheDir(), "spider-opt");
            if (!opt.exists() && !opt.mkdirs()) {
                lastError = "无法创建 dex 优化目录";
                return false;
            }
            // 加载前必须把 jar 设成只读：
            // Android 10+ 拒绝加载「可写」的 dex 文件（哪怕在 codeCacheDir 里），
            // 报 `Writable dex file ... is not allowed`。这是防代码注入的硬限制，
            // 唯一办法就是 chmod 444。实测不设只读一定失败。
            //noinspection ResultOfMethodCallIgnored
            jar.setReadOnly();
            if (jar.canWrite()) {
                lastError = "无法把 jar 设为只读（Android 10+ 拒绝加载可写 dex）";
                return false;
            }
            // parent 用 App 的 ClassLoader：catvod-api（Spider 抽象类等）由我们提供，
            // jar 里只含站点实现，靠父加载器解析基类
            // 同内容去重：配置里同一个 jar 换 URL、或 bundled 与配置 jar 内容相同时，
            // 复用已有 loader —— 否则同一批类在非移动区会各存一份。
            String hash = md5Of(jar);
            if (hash != null && !hash.isEmpty()) {
                DexClassLoader same = BY_HASH.get(hash);
                if (same != null) {
                    LOADERS.put(jarUrl, same);
                    return true;
                }
            }
            DexClassLoader loader = new DexClassLoader(
                    jar.getAbsolutePath(), opt.getAbsolutePath(), null,
                    ctx.getClassLoader());
            LOADERS.put(jarUrl, loader);
            if (hash != null && !hash.isEmpty()) BY_HASH.put(hash, loader);
            /*
             * 这里**不要**调 seedJarInitFor：普通 jar（肥猫等）的 Init 会被提前初始化，
             * 之后它的 searchContent 反而抛 JSONException（实测 2026-11-23 AppRJ 回归）。
             * Guard 系的初始化解在 ApiServer.createInstance 的实例化前那次 seed 就够了。
             */
            // seedJarInitFor(loader, ctx);
            lastError = "";
            return true;
        } catch (Throwable t) {
            lastError = "jar 加载异常：" + t.getClass().getSimpleName();
            Log.w(TAG, "prepare failed", t);
            return false;
        }
    }

    /**
     * 取一个站点实例（带缓存）。api 形如 `csp_Douban`。
     *
     * @return 实例；失败返回 null，原因见 {@link #lastError()}
     */
    static synchronized Spider spider(Context ctx, String api, String jarUrl, String md5, String ext) {
        if (api == null || api.isEmpty()) {
            lastError = "api 为空";
            return null;
        }
        Spider cached = INSTANCES.get(api);
        if (cached != null) return cached;
        if (!prepare(ctx, jarUrl, md5)) return null;
        DexClassLoader loader = LOADERS.get(jarUrl);
        if (loader == null) {
            lastError = "jar 未就绪";
            return null;
        }
        String cls = api.startsWith("csp_") ? api.substring(4) : api;
        try {
            Class<?> c = loader.loadClass("com.github.catvod.spider." + cls);
            /*
             * ★ 必须在 newInstance() 之前注入 Application（2026-11-23 实测 aiwex）。
             *
             * 加密类 jar 的 Guard 在**构造时**就会触发 DexNative.<clinit>：它要用
             * Init.context() 拿 Application → getCacheDir()，再把 jar 内
             * assets/wexguard_v8.so 落到缓存并 System.load。拿不到 Application 就
             * NPE → 之后所有 Guard 一律 NoClassDefFoundError: DexNative（看着像"缺类"，
             * 其实是静态初始化失败）。
             *
             * 原来的 seedJarInit 放在构造之后，对这种 jar 永远来不及。
             */
            seedJarInitFor(loader, ctx);
            Object o = c.getDeclaredConstructor().newInstance();
            if (!(o instanceof Spider)) {
                lastError = cls + " 不是 Spider 子类";
                return null;
            }
            Spider s = (Spider) o;
            // init 必须调：多数 spider 在这里解析 ext（线路、cookie、域名）
            try {
                s.init(ctx, ext == null ? "" : ext);
            } catch (Throwable t) {
                // init 失败不直接放弃：有些 spider 的 init 只做可选配置，
                // 不 init 也能跑 home。让它继续，真跑不了会在调用时抛。
                Log.w(TAG, "init failed for " + cls, t);
            }
            seedJarInit(ctx);
            INSTANCES.put(api, s);
            lastError = "";
            return s;
        } catch (ClassNotFoundException e) {
            lastError = "jar 里没有类 com.github.catvod.spider." + cls;
            return null;
        } catch (Throwable t) {
            // InvocationTargetException 把构造/静态块里的真实异常包了一层，
            // 只报类名的话用户看到 "Douban 实例化失败：InvocationTargetException"，
            // 完全不知道是缺类还是配置错。这里把 cause 一并带出来。
            Throwable cause = t;
            while (cause instanceof java.lang.reflect.InvocationTargetException
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            lastError = cls + " 实例化失败：" + cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : "（" + cause.getMessage() + "）");
            Log.w(TAG, "instantiate failed", cause);
            return null;
        }
    }

    /** 已加载的站点数（诊断用） */
    static int loadedCount() {
        return INSTANCES.size();
    }

    /**
     * 取一个已就绪的 DexClassLoader，给 {@link GatewayServer} 加载 jar 里的
     * {@code com.github.catvod.spider.Proxy} 用。
     *
     * <p><b>为什么需要它</b>：jar 里的 {@code Proxy.proxy(Map)} 是静态方法，
     * 但它位于 dex 里，只能用 DexClassLoader 加载——不能用 App 自己的 ClassLoader
     * （那是父加载器，看不到 dex 内的类）。网关的 {@code /proxy?do=*} 必须先
     * 拿到一个已完成 {@link #prepare} 的 loader，才能反射调用它。
     *
     * <p>挑哪个 loader：任取一个已加载站点的即可。不能保证每个站点的 jar 都含
     * {@code Proxy} 类（理论上可以不含），所以调用方要处理 ClassNotFoundException。
     * 站点表为空时返回 null——那种情况本来也没有可代理的东西。
     */
    static DexClassLoader anyLoader() {
        for (DexClassLoader l : LOADERS.values()) {
            if (l != null) return l;
        }
        return null;
    }

    /**
     * 按全限定名从已加载的 jar 里取类，供 {@code platform.Bridge} 的钩子使用。
     *
     * <p>顺序有讲究：
     * <ol>
     *   <li>先试 app 类路径——{@code com.github.catvod.crawler.Spider} 这类基类在
     *       宿主 APK 里（TV 工程内联了 39 个 catvod 类），不在 dex 里</li>
     *   <li>再遍历所有 dex loader——spider 与 parser（{@code parser.JsonXxx} /
     *       {@code MixXxx}）都在 jar 的 classes.dex 里</li>
     * </ol>
     *
     * <p>遍历前先做一次懒准备：{@code ApiServer} 并不认识 {@code LocalSites} 里的
     * jar 地址，第一个请求进来时未必有 loader，这里顺手把还没准备的都准备一遍
     * （{@code prepare} 是幂等的）。
     */
    static Class<?> loadClassFromJars(String fqcn) throws ClassNotFoundException {
        try {
            return Class.forName(fqcn);
        } catch (ClassNotFoundException ignored) {
            // 落下去查 dex
        }
        Context ctx = GatewayServer.appContext();
        // 启动期已准备好的 jar 直接复用；未准备的在首个请求时补齐
        if (ctx != null) {
            for (LocalSites.Site s : LocalSites.all()) {
                if (!LOADERS.containsKey(s.jar)) {
                    prepare(ctx, s.jar, s.md5);
                }
            }
        }
        int tried = 0;
        for (DexClassLoader l : LOADERS.values()) {
            if (l == null) continue;
            tried++;
            try {
                return l.loadClass(fqcn);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new ClassNotFoundException(fqcn + "（已查 app 类路径与 " + tried + " 个已加载 jar）");
    }

    /**
     * 启动时预注入 Application（见 {@link #seedJarInit} 的崩溃堆栈）。
     *
     * <p>与 {@code seedJarInit} 的差别：这个会先把内置 jar 准备好，
     * 让 {@code Init} 类能够被加载出来，再往里填 Context。
     * 网关启动时调一次即可——之后所有 spider 的静态初始化都能拿到它。
     */
    static void seedInitNow(Context ctx) {
        if (ctx == null) return;
        try {
            // 内置 jar 是零配置模式下唯一可用的 jar；先让它落地并进 LOADERS，
            // 否则 loadClassFromJars 找不到 Init 类。
            String bundled = bundledJarPath(ctx);
            if (bundled != null) prepare(ctx, bundled, "");
            /*
             * 内置的"扩展 jar"（spider-ext.jar）：和内置 jar 同一套机制，
             * 只是单独一个包，方便以后移除或改成外链导入。
             *
             * 为什么单独一个包而不是并进 spider.jar：spider.jar 是入库只读产物，
             * 改它等于污染上游二进制；单独一份 = 删文件即下线。
             */
            String ext = bundledExtJarPath(ctx);
            if (ext != null) prepare(ctx, ext, "");
            seedJarInit(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "seedInitNow failed: " + t.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- 调试开关
    /**
     * 端上排查开关（`adb shell setprop debug.meow.xxx 1`）。
     *
     * <p>只给真机 bisect 用：不同取值要在**同一个包**里切换，否则每换一个假设
     * 就要重新构建、安装、重跑，32 位 ART 的崩溃又只有概率性复现，根本没法比。
     * 生产默认值全部是"关"，不读它时行为与改动前一致。
     */
    static boolean dbg(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class).invoke(null, key);
            return "1".equals(String.valueOf(v));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 内置 jar 的落地路径；未落地则先拷一份。 */
    private static String bundledJarPath(Context ctx) {
        return copyAsset(ctx, "spider.jar", "bundled.jar", 1024);
    }

    /**
     * 内置"扩展 jar"：assets/spider-ext.jar（dex 形态）。
     *
     * <p>不存在就返回 null —— 这样"移出内置"只需要删掉这个 asset 文件，
     * 代码不用改、也不会报错。
     */
    private static String bundledExtJarPath(Context ctx) {
        return copyAsset(ctx, "spider-ext.jar", "bundled-ext.jar", 512);
    }

    /**
     * 把 assets 里的 jar 落到 codeCacheDir 并返回路径。
     *
     * @param minBytes 小于这个大小就当没拷贝成功（防止把截断的半截文件当 jar 用）
     */
    private static String copyAsset(Context ctx, String asset, String name, int minBytes) {
        try {
            java.io.File dir = new java.io.File(ctx.getCodeCacheDir(), DIR);
            if (!dir.exists() && !dir.mkdirs()) return null;
            java.io.File out = new java.io.File(dir, name);
            if (!out.isFile() || out.length() < minBytes) {
                try (java.io.InputStream in = ctx.getAssets().open(asset);
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
                //noinspection ResultOfMethodCallIgnored
                out.setReadOnly();
            }
            return out.isFile() ? out.getAbsolutePath() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把真实的 Application 塞进 jar 内的 {@code Init} 单例。
     *
     * <p><b>不做的后果是整进程崩溃，不是"某个站点不可用"</b>。实测堆栈：
     * <pre>
     * FATAL EXCEPTION: pool-9-thread-1
     * java.lang.ExceptionInInitializerError
     *   at com.github.catvod.spider.merge.b.G.f(...)
     *   at com.github.catvod.spider.WangPan.e(...)
     * Caused by: NullPointerException:
     *   Attempt to invoke virtual method 'java.lang.String
     *   android.content.Context.getPackageName()' on a null object reference
     *   at com.github.catvod.spider.merge.q.G.d(...)
     *   at com.github.catvod.spider.merge.b.F.&lt;clinit&gt;(...)
     * </pre>
     * 肥猫 jar 在**自己的线程池**里跑 {@code WangPan}，那条路径拿的是
     * {@code Init.context()} 这个静态单例；它没被赋值就是 null，
     * 子线程里的 NPE 会直接杀掉进程（Android 上未捕获异常 = 应用退出）。
     * 表现是"点了播放，整个 App 消失"。
     *
     * <p><b>为什么必须在 init 之后调</b>：jar 的 {@code Init.get()} 可能自己
     * 先建了实例，我们必须往它那个实例的字段里写，而不是另建一个。
     * 所以顺序是 get() → 取字段 → 空才填。
     *
     * <p><b>字段名不能写死</b>：普通 jar 叫 {@code c}，加密 jar（实测 aiwex）
     * 三个字段全是混淆名（{@code oOoOoOoOoOoOoO0o} 等）。按名字取会
     * {@code NoSuchFieldException}，注入静默失败。改为优先调公开静态
     * {@code Init.init(Context)}，没有这个方法时按**字段类型**（Application）找。
     *
     * <p>这个方法只负责"把已加载的 jar 都补一遍"，真正的注入在
     * {@link #seedJarInitFor(ClassLoader, Context)}；后者也会在实例化站点**之前**
     * 调用一次（Guard 的 clinit 在构造时就跑，晚了来不及）。
     */
    private static void seedJarInit(Context ctx) {
        if (ctx == null) return;
        for (DexClassLoader l : LOADERS.values()) {
            seedJarInitFor(l, ctx);
        }
    }

    /**
     * 用指定 loader 给 jar 内 {@code com.github.catvod.spider.Init} 注入 Application。
     *
     * <p>两条路径，按顺序：
     * <ol>
     *   <li>公开静态 {@code Init.init(Context)} —— 它内部会设置 Application，并调用
     *       {@code DexNative.getLoader(ctx)} 建 DexClassLoader（loader 由 {@code Init.<init>}
     *       自动指向 jar 自己的 ClassLoader）。这是加密 jar 能跑起来的关键一步。</li>
     *   <li>没有 init 方法时退回反射：{@code Init.get()} 拿单例，找**类型为 Application**
     *       的私有字段填进去（不按字段名）。</li>
     * </ol>
     *
     * <p>失败只记录：普通 jar 根本没有 Init，属于正常情况。
     */
    private static void seedJarInitFor(ClassLoader loader, Context ctx) {
        if (loader == null || ctx == null) return;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        try {
            Class<?> ic;
            try {
                ic = loader.loadClass("com.github.catvod.spider.Init");
            } catch (ClassNotFoundException e) {
                return; // 普通 jar：没有 Init，不需要注入
            }
            try {
                ic.getMethod("init", Context.class).invoke(null, app);
                return;
            } catch (NoSuchMethodException ignored) {
                // 老版本 jar 没有 init(Context)，走字段注入
            }
            Object inst = ic.getMethod("get").invoke(null);
            if (inst == null) return;
            for (java.lang.reflect.Field f : ic.getDeclaredFields()) {
                if (android.app.Application.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    if (f.get(inst) == null) {
                        f.set(inst, app);
                        Log.i(TAG, "seedJarInitFor: Application <- " + f.getName());
                    }
                    return;
                }
            }
        } catch (Throwable t) {
            /*
             * 加密 jar 的 Init.init 会触发 DexNative.<clinit>（解压并 load jar 内 .so）。
             * 这一步可能因为架构不符 / 宿主校验失败而抛，记录但要继续——
             * 真正的失败原因会在 newInstance 时以 NoClassDefFoundError 呈现。
             */
            Log.w(TAG, "seedJarInitFor failed: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "（" + t.getMessage() + "）"));
        }
    }

    /** 清空缓存（设置页"清空本地缓存"用） */
    static synchronized void clear(Context ctx) {
        INSTANCES.clear();
        LOADERS.clear();
        BY_HASH.clear();
        // 换配置 = 用户明确给了新 jar 集，负缓存一并失效允许重试
        FAILED_AT.clear();
        File dir = new File(ctx.getCodeCacheDir(), DIR);
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // ---------------------------------------------------------------- 内部

    private static String nameOf(String url) {
        // 用 url 的 hash 做文件名：不同站点可能都叫 spider.jar，用原名会互相覆盖
        // （这个坑在插件源缓存上踩过：key 只用文件名导致跨源串包）
        return "jar-" + Integer.toHexString(url.hashCode()) + ".jar";
    }

    /**
     * 下载 jar：直连失败时按「网络与加速」里配置的候选重试。
     *
     * <p>加速配置由配置导入时落盘（{@code core.Jars.setAccelProxies}），这里只
     * 负责按候选顺序试。每个候选都校验 md5：加速节点偶尔返回 HTML 错误页或
     * 旧包，不校验会把坏文件留在缓存里，之后 DexClassLoader 报错、排查方向全错。
     */
    private static boolean download(String url, File dest, String md5) {
        String base = core.ConfigText.idnUrl(url);
        for (String cand : core.Jars.jarCandidates(base)) {
            if (downloadOne(cand, dest)) {
                if (md5 == null || md5.isEmpty() || md5.equalsIgnoreCase(md5Of(dest))) {
                    if (!cand.equals(base)) Log.i(TAG, "jar 加速命中：" + cand);
                    return true;
                }
                Log.w(TAG, "jar 校验失败，换下一个候选：" + cand);
            }
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
        }
        return false;
    }

    private static boolean downloadOne(String url, File dest) {
        HttpURLConnection c = null;
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setRequestProperty("User-Agent", "meow-tv");
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            // 加速节点失败时常见 200 + HTML；不拦会被当 jar 缓存
            if (looksLikeHtml(tmp)) {
                Log.w(TAG, "jar 下载拿到 HTML（加速节点错误页）：" + url);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            // 先写临时文件再 rename：下到一半被杀不会留下半个 jar
            return tmp.renameTo(dest);
        } catch (Throwable t) {
            Log.w(TAG, "download failed " + url, t);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean looksLikeHtml(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] head = new byte[64];
            int n = in.read(head);
            for (int i = 0; i < n; i++) {
                byte b = head[i];
                if (b == ' ' || b == '\n' || b == '\r' || b == '\t') continue;
                return b == '<';
            }
        } catch (Exception ignored) {
            /* 读不了交给 md5/加载阶段报错 */
        }
        return false;
    }

    private static String md5Of(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 调一个动作；返回 JSON 字符串给前端（与后端 /api/{site}/{action} 同形）。 */
    static String call(Context ctx, String api, String jarUrl, String md5, String ext,
                       String action, Map<String, String> p) {
        Spider s = spider(ctx, api, jarUrl, md5, ext);
        if (s == null) return errorJson(lastError);
        try {
            switch (action) {
                case "home":
                    return s.homeContent(true);
                case "category":
                    return s.categoryContent(str(p, "id"), str(p, "page", "1"), true, new HashMap<>());
                case "detail": {
                    List<String> ids = new ArrayList<>();
                    ids.add(str(p, "id"));
                    return s.detailContent(ids);
                }
                case "search":
                    return s.searchContent(str(p, "wd"), true, str(p, "page", "1"));
                case "play": {
                    List<String> flags = new ArrayList<>();
                    return s.playerContent(str(p, "flag"), str(p, "id"), flags);
                }
                case "live":
                    return s.liveContent(str(p, "url"));
                default:
                    return errorJson("不支持的动作：" + action);
            }
        } catch (Throwable t) {
            Log.w(TAG, "call " + action + " failed", t);
            return errorJson(action + " 执行失败：" + t.getClass().getSimpleName());
        }
    }

    private static String str(Map<String, String> m, String k) {
        return str(m, k, "");
    }

    private static String str(Map<String, String> m, String k, String def) {
        String v = m == null ? null : m.get(k);
        return v == null || v.isEmpty() ? def : v;
    }

    private static String errorJson(String msg) {
        // 手拼 JSON：这里没有 org.json 的依赖保障（TV 上用系统 JSONObject 也行，
        // 但错误信息里可能带引号，手工转义一次比引依赖稳）
        String m = msg == null ? "" : msg.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"code\":-1,\"msg\":\"" + m + "\"}";
    }
}
