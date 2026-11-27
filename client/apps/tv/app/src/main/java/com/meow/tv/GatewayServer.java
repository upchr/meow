package com.meow.tv;

import com.meow.tv.runtime.PythonService;
import com.meow.tv.runtime.RuntimeModuleHost;
import android.content.Context;

import java.io.File;

import platform.Bridge;
import platform.HttpServer;

/**
 * TV 端内嵌网关：**直接复用 apiserver 的 {@code ApiServer}**，不另写一份路由。
 *
 * <p><b>为什么不再有 LocalGateway</b>：原来那份是手抄的第二份实现，只覆盖 8 条路由，
 * 而前端需要 41 条。两份代码最实际的代价不是重复劳动，是**修一处漏一处**——
 * m3u8 的 {@code EXT-X-KEY} 改写在后端早就有了（{@code ApiServer.java} handleStream
 * 里），TV 那份没有，表现就是同一个源在 Web 能播、在 TV 黑屏。
 *
 * <p><b>怎么做到同一份源码两端都编</b>：
 * <ul>
 *   <li>所有 {@code com.sun.net.httpserver} 引用改到 {@code platform.*}（JDK 那个包
 *       在 Android 上不存在，而 JVM 上又不许自带同名包，javac 报
 *       「程序包已存在于另一个模块中: jdk.httpserver」——实测过）</li>
 *   <li>JVM 用 {@code jvm.JvmHttpServer}（包 JDK 的），Android 用
 *       {@link SocketHttpServer}；由 {@code platform.ServerProvider} 按名字探测装配</li>
 *   <li>{@code java.net.http} 全部换成 OkHttp（前者在 android.jar 里 0 条）</li>
 * </ul>
 *
 * <p><b>数据目录</b>：把 {@code --data-dir} 指到 {@code filesDir/gateway}，
 * {@code config.sites.json} 由 {@link #syncConfigFile(Context)} 从 SharedPreferences
 * 落盘——后端 {@code loadConfigSites()} 是直接读那个文件的，不这么做它看到的永远是空表。
 */
public final class GatewayServer {

    private static final String TAG = "MeowGateway";

    /** 与 jar 的 Proxy 握手约定一致：{@code Proxy.a()} 会扫 9978..9999 找本服务。 */
    public static final int PORT = 9978;

    private static volatile HttpServer server;
    private static volatile Context appCtx;
    private static volatile boolean started;

    private GatewayServer() {
    }

    public static boolean isRunning() {
        if (isolateMode() && !serviceProcess) return httpListening();
        return started;
    }

    /** 供类加载钩子在按需准备 jar 时取上下文。 */
    static Context appContext() {
        return appCtx;
    }

    /**
     * 把未捕获异常写进 {@code filesDir/crash.log}。
     *
     * <p><b>为什么需要</b>：部分电视设备（实测极米 Z6X Pro）读不到 logcat
     * （{@code logcat read failure}）、{@code /data/system/dropbox/} 无权限、
     * {@code run-as} 也不可用。一旦子线程崩了，除了"进程消失"之外拿不到任何信息，
     * 排查只能靠猜。
     *
     * <p>写到应用私有目录再经网关 {@code /api/logs} 取出来，这条路只依赖
     * "应用自己被允许写自己的目录"，在所有设备上都成立。
     *
     * <p><b>为什么只记最近 64KB</b>：崩溃可能反复发生（用户重启后再点一次），
     * 累加会把磁盘写满；留最近的堆栈就够定位。
     */
    private static void installCrashRecorder() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                sw.write("=== " + new java.util.Date() + " thread=" + t.getName() + " ===\n");
                e.printStackTrace(new java.io.PrintWriter(sw));
                // 连锁 cause（InvocationTargetException 这类包装）也打全
                Throwable c = e.getCause();
                while (c != null) {
                    sw.write("--- cause ---\n");
                    c.printStackTrace(new java.io.PrintWriter(sw));
                    if (c.getCause() == c) break;
                    c = c.getCause();
                }
                byte[] add = sw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                File f = new File(appCtx.getFilesDir(), "crash.log");
                long keep = f.isFile() ? f.length() : 0;
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true)) {
                    if (keep > 64 * 1024) {
                        // 太大就重开：只留这一次的
                        fos.getChannel().truncate(0);
                    }
                    fos.write(add);
                }
            } catch (Throwable ignored) {
                // 记录失败不能影响原流程
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /**
     * 把网关启动失败的原因追加到 {@code filesDir/crash.log}。
     *
     * <p>与 {@link #installCrashRecorder()} 同一个文件、同一种用途，
     * 但那条路只捕未捕获异常；网关启动失败是被 catch 住的，需要单独记。
     */
    private static void writeStartFailure(Throwable cause) {
        if (appCtx == null) return;
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            sw.write("=== " + new java.util.Date() + " gateway start failed ===\n");
            cause.printStackTrace(new java.io.PrintWriter(sw));
            byte[] add = sw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            File f = new File(appCtx.getFilesDir(), "crash.log");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true)) {
                if (f.length() > 64 * 1024) fos.getChannel().truncate(0);
                fos.write(add);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 重启网关（导入配置后必须调，否则旧 loader / 旧站点表会一直生效）。
     *
     * <p>踩过（2026-11-05）：前端导入配置后调 {@code startLocalGatewayWith()}
     * 想"重启网关"，而 {@code start()} 看到 {@code started} 就直接 return ——
     * 实际什么都没重启，界面却提示"已重启"。
     */
    public static synchronized boolean restart(Context ctx) {
        if (isolateMode() && !serviceProcess) {
            try {
                android.content.Intent i = new android.content.Intent(ctx, GatewayService.class);
                i.setAction(GatewayService.ACTION_RESTART);
                ctx.startService(i);
            } catch (Throwable t) {
                return false;
            }
            for (int i = 0; i < 48; i++) {  // 12 秒，与 startIsolated 一致（端口先开、不预热）
                if (httpListening()) return true;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                }
            }
            return false;
        }
        return restartInProcess(ctx);
    }

    /** 进程内重启：先停监听、清静态状态，再重新起（服务进程里调）。 */
    static synchronized boolean restartInProcess(Context ctx) {
        try {
            if (server != null) server.stop();
        } catch (Throwable ignored) {
        }
        server = null;
        started = false;
        return startInProcess(ctx);
    }

    /**
     * 是否启用进程隔离。
     *
     * <p><b>32 位默认开启</b>（2026-11-05 实测）：那类设备的 ART 在聚合搜索时
     * 会 abort 进程，应用层修不了 GC，唯一能做的是把网关挪出主进程 ——
     * 实测主进程存活、官方服务重启后前端自动恢复。
     * 64 位保持进程内（少一层进程间开销，行为与改动前一致），
     * 调试时可用 {@code setprop debug.meow.iso 1/0} 强制切换。
     */
    static boolean isolateMode() {
        String v = "";
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            v = String.valueOf(c.getMethod("get", String.class).invoke(null, "debug.meow.iso"));
        } catch (Throwable ignored) {
        }
        if ("1".equals(v)) return true;
        if ("0".equals(v)) return false;
        return !android.os.Process.is64Bit();
    }

    /** true = 当前代码跑在 :spider 服务进程里（由 GatewayService 置位）。 */
    private static volatile boolean serviceProcess = false;

    /**
     * 长期持有的"配重"，用来把 ART 的 GC 频率压下来（32 位专用）。
     *
     * <p><b>原理（AOSP pie `runtime/gc/heap.cc`）</b>：
     * <ul>
     *   <li>GC 之后 {@code target_size = bytes_allocated + adjusted_max_free}，
     *       即"活对象 + 8~16MB"；</li>
     *   <li>下一次并发 GC 的触发点是 {@code footprint - remaining_bytes}，
     *       而 {@code remaining_bytes} 被**硬夹在 128KB~512KB**。</li>
     * </ul>
     * 也就是说：**只看分配量、不看堆有多大**，每分配几 MB 就来一次 GC。
     * 活对象越大，footprint 越大，两次 GC 之间能"攒"的分配量越多 ——
     * 拿一块常驻配重换"GC 次数下降"，在"每次 GC 都可能 abort"的机器上是划算的。
     *
     * <p>实测（极米 Z6X Pro）：不配重时搜索期 100 秒 34 次 GC；配重后见压测记录。
     */
    private static byte[] ballast;

    private static void holdBallast(int mb) {
        if (mb <= 0) return;
        try {
            byte[] b = new byte[mb * 1024 * 1024];
            // 必须逐页写一遍：否则 ART 只记账不落页，GC 统计里根本不算进 live set
            for (int i = 0; i < b.length; i += 4096) b[i] = 1;
            ballast = b;
            Runtime rt = Runtime.getRuntime();
            String msg = "ballast=" + mb + "MB held used="
                    + ((rt.totalMemory() - rt.freeMemory()) / 1048576L) + "MB total="
                    + (rt.totalMemory() / 1048576L) + "MB max=" + (rt.maxMemory() / 1048576L) + "MB";
            android.util.Log.i(TAG, msg);
            try {
                core.LogBuffer.info("warmup", msg);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "ballast " + mb + "MB failed: " + t);
        }
    }

    /**
     * 把 ART 的堆增长上限抬回 max（`VMRuntime.clearGrowthLimit()`）。
     *
     * <p><b>为什么需要它（2026-11-05 探针实测）</b>：这台极米上
     * {@code Runtime.maxMemory()=512MB}（largeHeap 生效），但
     * {@code totalMemory()} 始终只有 **4MB** —— 也就是 ART 的 growth limit
     * 被压在了 4MB。后果是**每分配几 MB 就 GC 一次**：实测搜索期间 100 秒
     * 34 次 GC（约 1 次/3 秒）。而 32 位 ART 的并发拷贝 GC 有缺陷
     * （{@code ConcurrentCopying::MarkNonMoving} → {@code LogHeapCorruption}
     * → SIGABRT），**每一次 GC 都是一次抽奖**，GC 越密死得越快。
     *
     * <p>AOSP 的 {@code ActivityThread.handleBindApplication} 本来会对
     * largeHeap 应用调 {@code clearGrowthLimit()}；这台 ROM 显然没做（或做了
     * 又被压回去），所以我们自己调一次。ART 的语义是"把增长上限放到 max"，
     * 不会主动 GC，成功之后同样的分配量就不再触发 GC。
     */
    private static void relaxHeapLimit() {
        try {
            Class<?> vmr = Class.forName("dalvik.system.VMRuntime");
            Object rt = vmr.getMethod("getRuntime").invoke(null);
            java.lang.reflect.Method clear = vmr.getMethod("clearGrowthLimit");
            clear.invoke(rt);
            String msg = "clearGrowthLimit ok";
            android.util.Log.i(TAG, msg);
            try {
                core.LogBuffer.info("warmup", msg + " max="
                        + (Runtime.getRuntime().maxMemory() / 1048576L) + "MB total="
                        + (Runtime.getRuntime().totalMemory() / 1048576L) + "MB");
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "clearGrowthLimit failed: " + t);
            try {
                core.LogBuffer.info("warmup", "clearGrowthLimit failed: "
                        + t.getClass().getSimpleName());
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 读 int 型系统属性（`adb shell setprop debug.meow.xxx N`）；读不到返回 def。 */
    private static int propInt(String key, int def) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            String v = String.valueOf(c.getMethod("get", String.class).invoke(null, key));
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * GC / 进程状态探针（**只在真机排查时开**，`debug.meow.gcprobe 1`）。
     *
     * <p><b>为什么需要它</b>：这台投影仪上 logcat 读不到、tombstone 无权限，
     * 进程一 abort 现场就没了。唯一能把"崩溃前发生了什么"留下来的办法是
     * **自己把时间线写进文件**（进程死了文件还在，下一个进程启动时读回来）。
     *
     * <p>查的是哪三件事：
     * <ul>
     *   <li>{@code art.gc.gc-count} / {@code blocking-gc-count}：GC 次数。
     *       ART 的 {@code Heap::UpdateProcessState} 会在**进程状态切换**时
     *       切换收集器类型（前台 CC ↔ 后台 HSC），切换本身就是一次 GC + 对象移动。</li>
     *   <li>{@code ActivityManager.getMyMemoryState().importance}：AMS 现在把我
     *       当什么（100=前台 / 125=前台服务 / 300=服务 / 400=缓存）。
     *       若崩溃前 importance 发生变化，就是状态切换触发的 GC。</li>
     *   <li>堆使用量：排除"分配压力触发 GC"。</li>
     * </ul>
     */
    private static void startGcProbe(Context ctx) {
        final File f = new File(ctx.getFilesDir(), "gc.log");
        new Thread(() -> {
            long lastGc = -1L, lastBlocking = -1L;
            int lastImp = Integer.MIN_VALUE;
            while (true) {
                try {
                    long gc = num(android.os.Debug.getRuntimeStat("art.gc.gc-count"));
                    long blocking = num(android.os.Debug.getRuntimeStat("art.gc.blocking-gc-count"));
                    long gcTime = num(android.os.Debug.getRuntimeStat("art.gc.gc-time"));
                    int imp = myImportance();
                    if (gc != lastGc || blocking != lastBlocking || imp != lastImp) {
                        Runtime rt = Runtime.getRuntime();
                        long usedMB = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
                        String line = "t=" + (System.currentTimeMillis() / 1000L)
                                + " gc=" + gc + " blocking=" + blocking
                                + " gcTime=" + gcTime + "ms"
                                + " importance=" + imp
                                + " used=" + usedMB + "MB"
                                + " total=" + (rt.totalMemory() / 1048576L) + "MB"
                                + " max=" + (rt.maxMemory() / 1048576L) + "MB"
                                // 累计分配/回收字节：判断"GC 频繁"是分配太快还是堆被压小
                                + " alloc=" + (num(android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")) / 1048576L) + "MB"
                                + " freed=" + (num(android.os.Debug.getRuntimeStat("art.gc.bytes-freed")) / 1048576L) + "MB";
                        append(f, line);
                        // 同时进内存日志：进程活着时直接 GET /api/logs 就能看实时时间线
                        try {
                            core.LogBuffer.info("gclog", line);
                        } catch (Throwable ignored) {
                        }
                        lastGc = gc;
                        lastBlocking = blocking;
                        lastImp = imp;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException ignored) {
                }
            }
        }, "meow-gc-probe").start();
    }

    private static long num(String s) {
        try {
            return Long.parseLong(String.valueOf(s).trim());
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** AMS 眼里的我（RunningAppProcessInfo.importance）。取不到返回 -1。 */
    private static int myImportance() {
        try {
            android.app.ActivityManager.RunningAppProcessInfo info =
                    new android.app.ActivityManager.RunningAppProcessInfo();
            android.app.ActivityManager.getMyMemoryState(info);
            return info.importance;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static synchronized void append(File f, String line) {
        try {
            if (f.length() > 64 * 1024) {
                // 只留最后一段：读出来截断重写，避免无限增长
                byte[] all = new byte[(int) f.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    int off = 0;
                    while (off < all.length) {
                        int n = in.read(all, off, all.length - off);
                        if (n <= 0) break;
                        off += n;
                    }
                }
                String s = new String(all, java.nio.charset.StandardCharsets.UTF_8);
                int cut = s.length() - 32 * 1024;
                int nl = s.indexOf('\n', Math.max(0, cut));
                s = nl > 0 ? s.substring(nl + 1) : "";
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, false)) {
                    out.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, true)) {
                out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 启动时把上一个进程留下的 gc.log 尾部读进 LogBuffer（进程已死，现场只能靠它）。 */
    private static void replayGcProbeLog(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), "gc.log");
            if (!f.isFile() || f.length() == 0) return;
            byte[] all = new byte[(int) Math.min(f.length(), 16 * 1024)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < all.length) {
                    int n = in.read(all, off, all.length - off);
                    if (n <= 0) break;
                    off += n;
                }
            }
            String[] lines = new String(all, java.nio.charset.StandardCharsets.UTF_8).split("\n");
            int from = Math.max(0, lines.length - 25);
            for (int i = from; i < lines.length; i++) {
                if (!lines[i].trim().isEmpty()) core.LogBuffer.info("gclog", lines[i]);
            }
        } catch (Throwable ignored) {
        }
    }

    static void markServiceProcess() {
        serviceProcess = true;
    }

    /** 设备侧探活：端口能连上就算网关就绪（跨进程唯一可靠判据）。 */
    static boolean httpListening() {
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 300);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 起独立进程的网关服务，并等端口可连。
     *
     * <p>等 12 秒而不是 40：端口先开、不预热之后，冷启动到可连实测约 2 秒
     * （首次装包还会叠加内置 jar 落地）。等太久没有意义 —— 进程若在启动期
     * 崩了，早返回才能让 gwKeeper 早一轮拉回。
     */
    private static boolean startIsolated(Context ctx) {
        try {
            ctx.startService(new android.content.Intent(ctx, GatewayService.class));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "startService failed: " + t);
            return false;
        }
        for (int i = 0; i < 48; i++) {  // 最多等 12 秒
            if (httpListening()) return true;
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
            }
        }
        return false;
    }

    public static int port() {
        return PORT;
    }

    public static String baseUrl() {
        return "http://127.0.0.1:" + PORT;
    }

    /**
     * 启动网关。
     *
     * <p>顺序是有讲究的：
     * <ol>
     *   <li>先定数据目录——{@code ApiServer} 的静态初始化会读它</li>
     *   <li>再装平台钩子（Context / 日志）</li>
     *   <li>把配置落盘，否则 {@code loadConfigSites()} 读不到表</li>
     *   <li>最后 {@code ApiServer.boot()} + {@code listen()}</li>
     * </ol>
     * 任何一步提前都会拿到空配置。
     */
    public static synchronized boolean start(Context ctx) {
        /*
         * 进程隔离模式（`setprop debug.meow.iso 1`，32 位默认开启见 plan）。
         *
         * 背景（2026-11-05 真机 bisect）：同一台投影仪上 Fongmi 聚合搜索不崩、
         * 我们崩，且已经排除「类数量/双 loader/并发数/Init 静态注入」——
         * 剩下的结构差异就是**我们和 WebView 共用同一个 ART 堆**。
         * 把网关整个搬到 :spider 进程后，那个进程是"纯 Java + jar"，
         * 与 Fongmi 的进程画像一致；即使它崩了，UI 进程也活着。
         */
        if (isolateMode() && !serviceProcess) {
            return startIsolated(ctx);
        }
        return startInProcess(ctx);
    }

    /** 真正的进程内启动（原 start 的正文）。 */
    static synchronized boolean startInProcess(Context ctx) {
        if (started) return true;
        appCtx = ctx.getApplicationContext();
        /*
         * 内置弹幕：装了 node 运行时模块就接上它自带的 danmu_api。
         *
         * 用 Bridge 钩子**每次现查**状态文件，而不是启动时把地址定死：
         * 端口是探出来的（19341 被占会顺延），模块崩溃重启也可能换端口 ——
         * 缓存住就会出现"模块在跑、弹幕不出来"。状态文件在 :node 与主进程之间
         * 也是唯一可靠通道（SharedPreferences 跨进程有内存缓存）。
         *
         * 只在"已装且用户没关"时才把地址给出去；不检查 running，因为 node
         * 解包要十几秒，这期间回环连接会立刻被拒、代价可忽略，而 ApiServer
         * 对回环地址不做 5 分钟失败冷却。
         */
        platform.Bridge.installDanmakuHost(() -> {
            try {
                if (!com.meow.tv.runtime.RuntimeModuleHost.nodeInstalled(appCtx)) return "";
                if (!com.meow.tv.runtime.RuntimeModuleState.enabled(appCtx)) return "";
                int p = com.meow.tv.runtime.RuntimeModuleState.port(appCtx);
                return "http://127.0.0.1:" + p
                        + "/api/v2/fongmi/danmaku?name={name}&episode={episode}";
            } catch (Throwable t) {
                return "";
            }
        });
        if (com.meow.tv.runtime.RuntimeModuleHost.nodeInstalled(appCtx)
                && com.meow.tv.runtime.RuntimeModuleState.enabled(appCtx)) {
            // 网关可能比 MainActivity 先起（自动化路径），这里再请求一次（幂等）
            bindNodeRuntime(appCtx);
        }
        // 上一个进程（很可能是被 ART abort 掉的）留下的 GC/状态时间线，先读进日志
        replayGcProbeLog(appCtx);
        if (propInt("debug.meow.gcprobe", 0) > 0) startGcProbe(appCtx);

        File dataDir = new File(appCtx.getFilesDir(), "gateway");
        if (!dataDir.isDirectory() && !dataDir.mkdirs()) {
            android.util.Log.w(TAG, "cannot create data dir " + dataDir);
            return false;
        }
        // ApiServer 的任何静态初始化都可能已经读过 DataPaths，所以这里必须在
        // 第一次碰 ApiServer 之前调。--data-dir 是它认的参数形式。
        core.DataPaths.MainArgs.set(new String[]{"--data-dir=" + dataDir.getAbsolutePath()});

        installCrashRecorder();

        Bridge.installContext(() -> appCtx);
        // Log.i 有两个重载 (String,String) 与 (String,String,Throwable)，方法引用会歧义，
        // 用显式 lambda 指定只走两参那个。
        Bridge.installLogger(line -> android.util.Log.i(TAG, line));
        // spider 与 parser 都在 jar 的 classes.dex 里，必须走 DexClassLoader。
        // 不装这个钩子会退回 Class.forName，在 ART 上永远 ClassNotFoundException。
        // 用显式 lambda 而不是方法引用：接口方法叫 loadClass，实现叫 loadClassFromJars，
        // 名字不同，方法引用匹配不上（会报"不兼容的类型"）。
        Bridge.installClassLoader(fqcn -> LocalSpiderHost.loadClassFromJars(fqcn));
        /*
         * 网关导入配置后，把端上第二张表（LocalSites/SharedPreferences）同步过来。
         *
         * ★ 两套表各管一半：网关表决定"有哪些站"，端上表决定"哪些 jar 能执行"。
         *   不挂这个钩子时的实测现象：设置页「导入配置」成功、站点数也对，
         *   但所有 jar 站点调用都 ClassNotFoundException（端上表还是旧的/空的）。
         */
        Bridge.installConfigImport((raw, base, replace) -> {
            if (raw == null || raw.isEmpty()) return;
            try {
                LocalSites.load(appCtx);
                // 替换模式先清空：否则被新配置删掉的 jar 站会永远留在端上表里
                if (replace) LocalSites.clearAll(appCtx);
                LocalSites.ImportResult r = LocalSites.importFrom(appCtx, raw, base);
                /*
                 * 清掉旧 loader：jar 地址可能换了（同 key 不同 jar），
                 * 不清就是"导入成功但跑的还是旧实现"，而且每次换配置都会
                 * 在非移动区多叠一份类对象（32 位 ART 的 MarkNonMoving 最敏感）。
                 */
                LocalSpiderHost.clear(appCtx);
                // 脚本可能换了内容（同 URL 改了实现），实例必须重建；
                // 只在导入时失效，跑起来之后不重下（脚本是启动期一次性的成本）。
                com.meow.tv.js.QuickJsRuntimeServer.invalidate();
                android.util.Log.i(TAG, "导入后同步端上 jar 表：" + r.imported + "/" + r.total);
            } catch (Throwable t) {
                android.util.Log.w(TAG, "导入后同步端上 jar 表失败", t);
            }
        });

        syncConfigFile(appCtx);
        ensureDanmakuApi(appCtx);
        // 内置 JS 运行时要在 ApiServer 起来之前把 jsRuntime 写进 policy：
        // 后端是启动时读一次 policy 还是每次调用读一次不保证，早写早生效。
        ensureLocalJsRuntime(appCtx);
        // Python 运行时（若装了模块）：它起来后会把 jsRuntime 覆盖成自己的端口，
        // 因为一个 pyrt 就能同时服务 .py（本地）与 .js（转发给上面那个 QuickJS）。
        startPythonRuntime(appCtx);
        // OkHttp.dns() 是静态单例，进程重启后回到"只有系统 DNS"。
        // 这里按存下来的原始配置重放一次 hosts/doh（见 LocalDns 的实测数据：
        // 缺了它那 5 个站在污染网络下永远搜不到）。
        LocalDns.applyStored(appCtx);

        try {
            // 站点表来自 SharedPreferences（不是 config.sites.json 的 sites 表，
            // 那份是原始配置；这里要的是"能执行"的过滤后子集，带 jar 绝对路径）
            LocalSites.load(appCtx);
            // 预先把 Application 塞进 jar 的 Init 单例。
            // 不等第一个请求：jar 里 WangPan 这类站点的 <clinit> 会在自己的线程池里
            // 读 Init.context()，为 null 时抛 NPE 直接杀进程（实测整 App 退出）。
            // 这里先 seed 一次，让静态初始化拿得到 Context。
            LocalSpiderHost.seedInitNow(appCtx);
            // ApiServer 在**默认包**里（后端就这一个文件不带 package），而 Java 语言
            // 规定「具名包不能 import 默认包的类」，所以这里只能反射调用。
            // 反射还有个好处：ApiServer 的静态初始化（读 DataPaths、注册 44 个站点）
            // 推迟到 Class.forName 那一刻，正好在下面 --data-dir 已设好之后。
            /*
             * 32 位：把聚合搜索总预算压到 18 秒。
             *
             * `ApiServer.SEARCH_TOTAL_TIMEOUT_MS` 是 static final，在**类加载时**
             * 读 `System.getProperty("meow.searchTimeout")` —— 必须赶在下面
             * `Class.forName` 之前设置，晚一步就无效。
             *
             * 依据（2026-11-05 真机）：这台机器的 :spider 活不过约 2 分钟，
             * 而串行搜索 44 站实测出现过 120 秒还没收尾的轮次。与其等进程被崩掉，
             * 不如按时把已经拿到的结果交出去（前端本来就是边搜边显示）。
             */
            if (!android.os.Process.is64Bit()) {
                System.setProperty("meow.searchTimeout", "18000");
                /*
                 * GC 闸门默认**不开**（`meow.gcgate=<MB>`，ApiServer.gcGateTick）。
                 * 实测（2026-11-05，42 站 × 10 轮）：开 2MB 一档是 6/10，
                 * 不开也是 6/10 —— 没有收益，还平白多出几十次 30ms 的停顿。
                 * 代码留着是因为它仍是唯一能控制 GC 时机的入口，
                 * 以后想再试直接 System.setProperty("meow.gcgate", "N") 即可。
                 */
            }
            Class<?> api = Class.forName("ApiServer");
            api.getMethod("boot").invoke(null);
            /*
             * ★ 端口必须**先开**，预热一律异步（2026-11-05 真机复测推翻 syncWarm）。
             *
             * 上一版把 32 位的预热同步放在 listen 之前，实测两个后果都很糟：
             *   - 预热要 **94 秒**（`ok=14 fail=30`，那 30 个站各等 3 秒 init 超时），
             *     而这 94 秒里端口不监听 —— 客户端只能看到"网关没起"；
             *   - 预热并不能避免崩溃：预热"完成"后的第一轮搜索照样 SIGABRT
             *     （`:spider` 进程存活 2~3 分钟就没了，与是否预热无关）。
             * 结论：预热是**纯开销**，不能放在可用性关键路径上。
             * 崩溃靠 gwKeeper 拉回（冷启动到端口可连实测 ~2 秒）。
             */
            server = (HttpServer) api.getMethod("listen", String.class, int.class, int.class)
                    /*
                     * 连接线程数 24 而不是 12。
                     *
                     * 实测（极米 Z6X Pro，Android 9）：12 个连接线程下，
                     * 连续做两次全量聚合搜索就会把网关打死 —— 第二次搜索时
                     * 网关端口直接连不上（RemoteDisconnected），进程还活着。
                     *
                     * 原因：连接线程要等 handler.handle() 返回才释放，而同步
                     * 搜索一次跑 20~30 秒；同时前端还在轮询 /api/search/task
                     * 和 /api/health。12 个很快被占满，后续连接在无界队列里排队，
                     * 客户端等不及就断，看起来像"服务挂了"。
                     *
                     * 24 个是折中：够覆盖"1 个搜索 + 若干轮询 + 页面其他请求"，
                     * 又不会在 3.5GB 的设备上开太多线程。
                     */
                    .invoke(null, "127.0.0.1", PORT, 24);
            started = true;
            android.util.Log.i(TAG, "gateway up on " + baseUrl()
                    + " sites=" + LocalSites.size());
            /*
             * 32 位：在"还没有搜索线程/网络对象"的窗口里主动做一次 GC。
             *
             * 依据：那台投影仪上**任何一次 GC 都可能 abort**（实测 dumpsys 触发
             * 的一次 GC 就把进程杀了），而 ART 的 growth limit 是每次 GC 后
             * 按空闲内存抬高的 —— 在对象图最小的时候主动付这一次代价，
             * 后续分配触发 GC 的频率会明显下降。
             *
             * 默认**只在 32 位开**（`debug.meow.pregc`，取值：
             *   0 = 关；1 = System.gc()；2 = 先占 24MB 再 gc（默认））。
             * 实测这次 GC 只要 27~28ms，多次冷启动都没在这一点崩过；
             * 64 位没有这个缺陷，不开。
             */
            // 先把 ART 的堆增长上限抬回 max，再谈 GC —— 否则 preGc 的 24MB 配重
            // 本身就会把 4MB 的小堆挤出一串 GC（实测 2 秒内 15 次）。
            if (!android.os.Process.is64Bit()
                    && propInt("debug.meow.growlimit", 1) > 0) {
                relaxHeapLimit();
            }
            // 常驻配重：拿内存换"GC 次数"，见 holdBallast 的原理说明
            if (!android.os.Process.is64Bit()) {
                /*
                 * 默认 **0**：配重只改变 GC 节奏，不改变 abort 概率（实测见下）。
                 *
                 * 2026-11-05 对照（同一台极米）：
                 *   - 前 60 秒 GC 次数随配重下降：18(0MB) / 13(4) / 11(16) / 10(32) / 7(48) / 11(64)
                 *   - 但成功率没有差异：全量 8 轮 0MB=7/8、48MB=7/8；
                 *     SaoHuo 单站天 0MB=6/6、32MB=6/6
                 *   - 而且配重越大单轮越慢（0MB 13s，48MB 19s）—— 一次 GC 要标记的对象变多了
                 * 结论：abort 概率跟"标记的总对象量"挂钩，不跟 GC 次数挂钩，
                 * 所以配重只是把代价挪个位置。要开就 `setprop debug.meow.ballast N`。
                 */
                holdBallast(propInt("debug.meow.ballast", 32));
            }
            int preGc = propInt("debug.meow.pregc", 0);   // 默认关：实测它的 24MB 配重反而制造一串 GC
            if (preGc > 0 && !android.os.Process.is64Bit()) {
                byte[] ballast = null;
                try {
                    if (preGc >= 2) ballast = new byte[24 * 1024 * 1024];
                    long t0 = android.os.SystemClock.elapsedRealtime();
                    java.lang.Runtime.getRuntime().gc();
                    String msg = "preGc mode=" + preGc + " took="
                            + (android.os.SystemClock.elapsedRealtime() - t0) + "ms"
                            + " heapUsed="
                            + ((java.lang.Runtime.getRuntime().totalMemory()
                            - java.lang.Runtime.getRuntime().freeMemory()) / 1048576L) + "MB";
                    android.util.Log.i(TAG, msg);
                    core.LogBuffer.info("warmup", msg);
                } catch (Throwable ignored) {
                    // OOM 等：丢掉配重继续，GC 本身不影响可用性
                }
                ballast = null;
            }
            /*
             * 预热模式（真机 A/B 用，`adb shell setprop debug.meow.warm N`）：
             *   0 = 不预热            （32 位实测：首轮 16s 就崩、无结果）
             *   1 = 只加载类          （749ms，不联网；但搜索仍要现 init，
             *                          32 位实测首轮 11.4s 崩溃、只回来 1 站）
             *   2 = 建实例 + init     （94s；唯一跑出完整一轮的配置）
             * 默认 2 与 64 位一致 —— 三种模式都试过，只有 2 出过 `done=True`。
             */
            int warmMode = propInt("debug.meow.warm", 2);
            if (warmMode == 1) warmupClassesOnly();
            else if (warmMode == 2) warmupSpiders(false);
            return true;
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null && cause != cause.getCause()) {
                cause = cause.getCause();
            }
            android.util.Log.e(TAG, "start failed: " + cause, cause);
            /*
             * ★ 同时写进 filesDir/crash.log。
             *
             * 实测极米 Z6X Pro（Android 9 / armeabi-v7a）：logcat 全不可用
             * （`logcat read failure`）、dropbox 无权限、run-as 不可用 ——
             * 网关起不来时**除了"端口没监听"之外拿不到任何信息**，
             * 排查只能靠猜。写自己的目录这条路在所有设备上都成立，
             * 再经 TvBridge.crashLog() 取出来。
             */
            writeStartFailure(cause);
            started = false;
            return false;
        }
    }

    /**
     * 启动后串行预热全部 jar 站点：一次加载一个 spider 类并 init。
     *
     * <p><b>为什么必须预热，不能等搜索时懒加载</b>（2026-10-29 变量控制实测，
     * 极米 Z6X Pro / Android 9 / armeabi-v7a / zygote32）：
     * 同一 APK + 逐字节相同的 270 站配置，MuMu（x86_64）聚合搜索正常，
     * 投影仪在顺序实例化第 8~37 个 spider 之间的**随机位置**被 SIGABRT 杀掉。
     * tombstone 固定是 {@code HeapTaskDaemon} →
     * {@code ConcurrentCopying::MarkNonMoving} →
     * {@code Verification::LogHeapCorruption}。
     *
     * 已排除的假说：单源问题（崩点随机漂移）、调用量（Bili 连调 40 次不崩）、
     * 并发压力（压到 4 路仍崩）、largeHeap（去掉照崩）。
     * 剩下的唯一相关性是「<b>不同 spider 类的加载与并发 GC 同时发生</b>」：
     * DexClassLoader 装载的 Class 对象分配在 non-moving space，
     * Android 9 32 位 ART 的并发拷贝 GC 在标记这一区域时有已知缺陷，
     * 类加载与 GC 竞争时对象图不一致 → abort。
     *
     * <p><b>预热为什么能躲开</b>：串行（一次一个类、间隔 30ms）把"分配
     * Class 对象"集中在没有网络对象的窗口里完成。等用户真正开始搜索时，
     * 所有类已在 CACHE 里，不再有"类加载 + 搜索网络同框"的崩点组合
     * （2026-11-05 压测：带加载窗口的搜索一轮 0/10，加载完成后连续多轮稳定）。
     *
     * <p><b>现在只有 64 位调它</b>（2026-11-05 复测）：32 位那 44 站里 30 个 init
     * 直接 3 秒超时（整轮 94 秒），而预热并不能阻止之后的崩溃 —— 纯开销，
     * 还会在端口可用前制造 30 次网络超时的临时对象。
     */
    private static void warmupSpiders(boolean sync) {
        final Context ctx = appCtx;
        if (ctx == null) return;
        Runnable body = () -> {
            long t0 = android.os.SystemClock.elapsedRealtime();
            int ok = 0, fail = 0;
            String firstErr = "";
            try {
                Class<?> api = Class.forName("ApiServer");
                java.lang.reflect.Method warm = api.getMethod("warmInstance", String.class);
                java.lang.reflect.Method keys = api.getMethod("siteKeysForWarmup");
                @SuppressWarnings("unchecked")
                java.util.List<String> all = (java.util.List<String>) keys.invoke(null);
                for (String key : all) {
                    try {
                        // 用 **API 站点表的 key**（与搜索完全同一个命名空间），
                        // 不能用 LocalSites 的 key：createInstance 会按 key 去
                        // EXT 取 ext 传 init，key 不一致就预热出一个 ext 为空的实例，
                        // 之后搜索命中缓存、但它跑不起来 —— 比不预热更糟。
                        Object r = warm.invoke(null, key);
                        if (Boolean.TRUE.equals(r)) ok++;
                        else fail++;
                        /*
                         * 每加载一个就记一条。
                         *
                         * 进程在预热过程中崩掉时，这条日志就是唯一的进度证据：
                         * 能看到"崩在第几个"，从而判断是"加载到第 N 个类必崩"
                         * 还是"某些特定类有毒"。
                         */
                        try {
                            core.LogBuffer.info("warmup", "[" + (ok + fail) + "] " + key
                                    + (Boolean.TRUE.equals(r) ? " ok" : " skip"));
                        } catch (Throwable ignored) {
                        }
                    } catch (Throwable t) {
                        fail++;
                        if (firstErr.isEmpty()) {
                            Throwable c = t;
                            while (c.getCause() != null && c != c.getCause()) c = c.getCause();
                            firstErr = key + ": " + c.getClass().getSimpleName()
                                    + (c.getMessage() == null ? "" : " " + c.getMessage());
                        }
                    }
                    // 每个类之间让一拍，给并发 GC 留出安静窗口。
                    // 30ms 是实测出来的：0 时预热与首屏加载抢堆，
                    // 反而更容易触发 GC 压力。
                    Thread.sleep(30);
                }
            } catch (Throwable t) {
                firstErr = "warmup 整体失败: " + t;
            }
            String msg = "ok=" + ok + " fail=" + fail
                    + " in " + (android.os.SystemClock.elapsedRealtime() - t0) + "ms"
                    + (firstErr.isEmpty() ? "" : " (" + firstErr + ")");
            /*
             * 写进 LogBuffer 而不是只打 android.util.Log：
             * 实测极米 Z6X Pro 读不到 logcat，只有 /api/logs 能取出来。
             * 预热有没有跑、跑了几个、第一个失败原因是什么，全靠这条。
             */
            android.util.Log.i(TAG, "warmup " + msg);
            try {
                core.LogBuffer.info("warmup", msg);
            } catch (Throwable ignored) {
            }
        };
        if (sync) body.run();
        else new Thread(body, "meow-warmup").start();
    }

    /**
     * 只把类加载进来：不 init、不建实例、不联网、不碰按站点的锁。
     *
     * <p>32 位串行搜索下，{@link #warmupSpiders} 那种"实例 + init"预热会和搜索
     * 抢 {@code ApiServer.INSTANCE_LOCKS}（spider 的 init 有卡 40 秒+ 的站），
     * 搜索排到那个站就跟着阻塞；而类加载本身是搜索最想提前的那部分，
     * 44 个类实测 749ms 完成。
     */
    private static void warmupClassesOnly() {
        final Context ctx = appCtx;
        if (ctx == null) return;
        new Thread(() -> {
            long t0 = android.os.SystemClock.elapsedRealtime();
            int ok = 0, fail = 0;
            String firstErr = "";
            try {
                Class<?> api = Class.forName("ApiServer");
                java.lang.reflect.Method keys = api.getMethod("siteKeysForWarmup");
                @SuppressWarnings("unchecked")
                java.util.List<String> all = (java.util.List<String>) keys.invoke(null);
                for (String key : all) {
                    if (android.os.SystemClock.elapsedRealtime() - t0 > 20_000L) {
                        firstErr = "预算用尽";
                        break;
                    }
                    try {
                        // 多实例站（Bili#2）没有独立类，按基名加载
                        String base = key == null ? "" : key.replaceAll("#.*$", "");
                        if (base.isEmpty()) continue;
                        LocalSpiderHost.loadClassFromJars("com.github.catvod.spider." + base);
                        ok++;
                    } catch (Throwable t) {
                        fail++;
                        if (firstErr.isEmpty()) firstErr = key + ": " + t.getClass().getSimpleName();
                    }
                    Thread.sleep(15);
                }
            } catch (Throwable t) {
                firstErr = "整体失败: " + t;
            }
            String msg = "classes ok=" + ok + " fail=" + fail
                    + " in " + (android.os.SystemClock.elapsedRealtime() - t0) + "ms"
                    + (firstErr.isEmpty() ? "" : " (" + firstErr + ")");
            android.util.Log.i(TAG, "warmup " + msg);
            try {
                core.LogBuffer.info("warmup", msg);
            } catch (Throwable ignored) {
            }
        }, "meow-warm-classes").start();
    }

    /**
     * 没有外部弹幕源时，自动指向用户配的后端。
     *
     * <p><b>为什么必须自动</b>：内置的 7 个聚合站（api.dandanplay.net 等）实测全部失效
     * 或被墙——{@code /api/proxy/danmaku} 返回空 XML，表现为"能播但没弹幕"。
     * 而后端 apiserver 接的是自建 danmu_api（compose 里 {@code MEOW_DANMAKU_API}），
     * 同一条接口实测能返 4 万条。TV 只要把 {@code danmakuApi} 指到后端就行。
     *
     * <p><b>为什么写到 policy.json 而不是硬编码</b>：后端 {@code danmakuHosts()} 的
     * 查找顺序是 环境变量 → policy.json → 内置列表。TV 没有环境变量，
     * policy 是唯一能注入的地方。
     *
     * <p><b>用占位符形式</b>：{@code danmakuUrl()} 见到 {@code {name}}/{@code {episode}}
     * 就原样替换，不会追加 {@code /api/v2/fongmi/danmaku} 后缀——所以这里要把
     * 完整路径写出来。TV 端不做名字转义（后端已在调用前 cleanName 过）。
     */
    static void ensureDanmakuApi(Context ctx) {
        try {
            String backend = ctx.getSharedPreferences(MainActivity.PREF, Context.MODE_PRIVATE)
                    .getString(MainActivity.K_API, "");
            if (backend == null || backend.trim().isEmpty()) {
                // 用户可能只填了「网页地址」而没单独填 API 地址（设置页保存连接时
                // 两者会一起写，但 intent 传参的自动化路径只写 K_URL）。
                // 弹幕只需要「能访问到后端的那个基址」，两者取其一即可。
                backend = ctx.getSharedPreferences(MainActivity.PREF, Context.MODE_PRIVATE)
                        .getString(MainActivity.K_URL, "");
            }
            if (backend == null || backend.trim().isEmpty()) {
                // 纯本机模式（没配后端）：只能用内置站，接受没有弹幕
                android.util.Log.i(TAG, "danmakuApi: 未配后端，用内置聚合站");
                return;
            }
            String base = backend.trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            /*
             * ★ 后端就是本机网关时**绝不能写 danmakuApi**（2026-11-06 实测踩过）：
             * 那会把 `http://127.0.0.1:9978/api/proxy/danmaku?...` 写进 policy，
             * 于是网关抓弹幕时把自己当成聚合站去调 —— 自己调自己，
             * 白等 6.5 秒超时，日志是 "aggregator miss after 6506ms"。
             * 本机模式该用的是内置运行时模块（node 里的 danmu_api），
             * 由 ApiServer 的 meow.danmaku.bundled 提供。
             */
            if (MainActivity.isLocalGateway(base)) {
                android.util.Log.i(TAG, "danmakuApi: 本机网关模式，跳过（用内置 danmu_api 模块）");
                // 旧版本可能已经写进去过，顺手清掉自指项
                String cur0 = core.DataPaths.read("policy.json");
                if (cur0 != null && cur0.contains("danmakuApi")) {
                    org.json.JSONObject p0 = new org.json.JSONObject(cur0);
                    String old = p0.optString("danmakuApi", "");
                    if (old.contains("127.0.0.1") || old.contains("localhost")) {
                        p0.remove("danmakuApi");
                        core.DataPaths.write("policy.json", p0.toString());
                        android.util.Log.i(TAG, "danmakuApi: 已移除自指项 " + old);
                    }
                }
                return;
            }
            String cur = core.DataPaths.read("policy.json");
            if (cur != null && cur.contains("danmakuApi")) {
                return;
            }
            String api = base + "/api/proxy/danmaku?name={name}&episode={episode}";

            org.json.JSONObject p = cur == null || cur.trim().isEmpty()
                    ? new org.json.JSONObject() : new org.json.JSONObject(cur);
            p.put("danmakuApi", api);
            boolean ok = core.DataPaths.write("policy.json", p.toString());
            android.util.Log.i(TAG, "danmakuApi -> " + api + " written=" + ok);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "ensureDanmakuApi failed", t);
        }
    }

    /**
     * 起内置 QuickJS 运行时，并把 `jsRuntime` 指向它（仅当没配外部运行时）。
     *
     * <p><b>为什么要写 policy</b>：后端 `callScriptRuntime()` 的地址来源是
     * policy.json 的 `jsRuntime`（其次才是 `MEOW_JS_RUNTIME` 环境变量）。
     * TV 没有环境变量，policy 是唯一注入点——与 `ensureDanmakuApi` 同一套路。
     *
     * <p><b>三种情况分开处理</b>：
     * <ul>
     *   <li>空 → 写本机地址（内置引擎接管）</li>
     *   <li>指向本机 + 端口在临时端口段（32768-60999）→ 覆盖。那是我们上次启动
     *       写的，端口每次随机，不覆盖就会指到一个没人监听的端口（表现为
     *       "jsRuntime Connection refused"）</li>
     *   <li>其他 → 原样保留。包括用户显式配的 `127.0.0.1:19331`（adb reverse
     *       到 PC 的 pyrt 就是这么用的）与局域网 NAS 地址——他配了就是要用那台，
     *       算力/源都在那边，不能悄悄改成本地</li>
     * </ul>
     */
    static void ensureLocalJsRuntime(Context ctx) {
        try {
            int p = com.meow.tv.js.QuickJsRuntimeServer.start();
            if (p <= 0) {
                android.util.Log.i(TAG, "jsRuntime: 内置 QuickJS 未启动（本机无对应 ABI 的 .so？）");
                return;
            }
            String local = com.meow.tv.js.QuickJsRuntimeServer.baseUrl();
            String cur = core.DataPaths.read("policy.json");
            org.json.JSONObject obj = cur == null || cur.trim().isEmpty()
                    ? new org.json.JSONObject() : new org.json.JSONObject(cur);
            String old = obj.optString("jsRuntime", "");
            /*
             * ★ 2026-11-28 迁移：旧版把 pyrt 的地址写进 jsRuntime（单地址时代）。
             *   现在 pyrt 只写 pyRuntimeAddr，jsRuntime 必须归 QuickJS —— 如果发现
             *   jsRuntime 正好等于 pyRuntimeAddr（是我们自己人写的），按"我们的地址"
             *   处理并抢回来。用户手配的其它地址仍然保留。
             */
            boolean oursLegacy = !old.isEmpty() && old.equals(obj.optString("pyRuntimeAddr", "").trim());
            if (!old.isEmpty() && !isOurEphemeral(old) && !oursLegacy) {
                android.util.Log.i(TAG, "jsRuntime: 保留已配置的运行时 " + old);
                return;
            }
            if (local.equals(old)) return;
            obj.put("jsRuntime", local);
            boolean ok = core.DataPaths.write("policy.json", obj.toString());
            android.util.Log.i(TAG, "jsRuntime -> " + local + " written=" + ok);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "ensureLocalJsRuntime failed", t);
        }
    }

    /**
     * 装了 Python 模块就拉起 {@code PythonService}（独立进程 {@code :py}）。
     *
     * <p>没装就什么都不做——TV 默认只有 QuickJS，`.py` 源由"连远端 pyrt"或
     * "装 Python 模块"两条路解决，不在主包里塞 CPython。
     */
    /** python 服务的进程级绑定：持有它才让 :py 进程有活跃 binder 连接，不被冻结。 */
    private static android.content.ServiceConnection pyConn;
    /** 已绑定标记：bindService 重复调用会让 unbind 次数对不上，只绑一次。 */
    private static volatile boolean pyBound;
    /** node 服务的进程级绑定，理由同 pyConn。 */
    private static android.content.ServiceConnection nodeConn;
    private static volatile boolean nodeBound;

    /**
     * 绑定 node 运行时（进程级，永不 unbind）。
     *
     * <p>与 {@link #startPythonRuntime} 同一个原因：`com.meow.tv:node` 是独立进程，
     * startService 之后没有活跃 binder 连接，Android 12+ 会把它冻在
     * `do_freezer_trap`——端口还在 LISTEN 但一切请求不处理。绑定一次就让
     * 进程 importance 提升，前台使用期足够用。
     */
    public static void bindNodeRuntime(Context ctx) {
        if (nodeBound) return;
        if (nodeConn == null) {
            nodeConn = new android.content.ServiceConnection() {
                @Override
                public void onServiceConnected(android.content.ComponentName n, android.os.IBinder b) {
                    android.util.Log.i(TAG, "node: 已绑定 " + n.flattenToShortString());
                }

                @Override
                public void onServiceDisconnected(android.content.ComponentName n) {
                    android.util.Log.w(TAG, "node: 绑定断开 " + n.flattenToShortString());
                }
            };
        }
        try {
            nodeBound = ctx.bindService(new android.content.Intent(ctx,
                            com.meow.tv.runtime.NodeService.class),
                    nodeConn, android.content.Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "node: bindService 抛错", t);
        }
        if (!nodeBound) {
            try {
                ctx.startService(new android.content.Intent(ctx,
                        com.meow.tv.runtime.NodeService.class));
            } catch (Throwable t) {
                android.util.Log.w(TAG, "start NodeService failed: " + t);
            }
        }
    }

    /**
     * 解绑 node。模块装/卸后要重启服务时必须先解绑：已绑定的 Service 对
     * `stopService` 免疫（系统按"还有客户端绑定"继续保活），不先解绑就重启不了。
     */
    public static void unbindNodeRuntime(Context ctx) {
        if (!nodeBound || nodeConn == null) return;
        try {
            ctx.unbindService(nodeConn);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "node: unbindService 失败", t);
        }
        nodeBound = false;
    }

    public static void startPythonRuntime(Context ctx) {
        try {
            if (!RuntimeModuleHost.pythonInstalled(ctx)) {
                android.util.Log.i(TAG, "python: 运行时模块未安装，跳过");
                return;
            }
            android.content.Intent i = new android.content.Intent(ctx, PythonService.class);
            /*
             * drpy 上游（本机 QuickJS）必须**随 Intent 传**：PythonService 在 :py
             * 进程，shared 的 DataPaths 在那里 read() 同样拿不到（与 write 失败同因），
             * 实测 PythonService 里读 policy 得到的是空串。
             */
            String qjs = com.meow.tv.js.QuickJsRuntimeServer.baseUrl();
            if (!qjs.isEmpty()) i.putExtra("drpy", qjs);
            /*
             * ★ bindService 而不是 startService：Android 12+ 的 cached-app
             * freezer 会冻住"没有前台组件、也没有活跃 binder 连接"的进程。
             * 实测 .77 上 `com.meow.tv:py` 长期 `do_freezer_trap`：19351 还在
             * LISTEN，但请求全超时、堆了 4 条 CLOSE_WAIT —— Service 对象活着、
             * 进程被冻，等于不可用。主进程持有绑定后该进程 importance 提升。
             *
             * 绑定持有后不解开（进程级）：unbind 会让 :py 退回可冻结状态。
             * bind 失败才退回 startService（至少让状态文件能被写出来）。
             */
            if (!pyBound) {
                if (pyConn == null) {
                    pyConn = new android.content.ServiceConnection() {
                        @Override
                        public void onServiceConnected(android.content.ComponentName n, android.os.IBinder b) {
                            android.util.Log.i(TAG, "python: 已绑定 " + n.flattenToShortString());
                        }

                        @Override
                        public void onServiceDisconnected(android.content.ComponentName n) {
                            android.util.Log.w(TAG, "python: 绑定断开 " + n.flattenToShortString());
                        }
                    };
                }
                try {
                    pyBound = ctx.bindService(i, pyConn, android.content.Context.BIND_AUTO_CREATE);
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "python: bindService 抛错，退回 startService", t);
                }
            }
            if (pyBound) {
                android.util.Log.i(TAG, "python: 已 bindService（保活）");
            } else {
                ctx.startService(i);
                android.util.Log.i(TAG, "python: bind 未成功，已 startService");
            }
            /*
             * Python 是异步就绪的（模块进程要起 CPython、pyrt 要 bind 端口），
             * 这里轮询状态文件（最多 10 秒）。就绪后把 jsRuntime 改指它 ——
             * 一个 pyrt 同时服务 .py（本地）与 .js（转发给上面那个 QuickJS），
             * 比只指向 QuickJS 多一种能力。
             *
             * 写 policy 必须在本进程（:spider）：shared 的 DataPaths 在 :py
             * 进程里 write() 返回 false（实测）。
             */
            new Thread(() -> {
                for (int n = 0; n < 20; n++) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    int p = pythonStatePort(ctx);
                    if (p > 0) {
                        publishPyRuntimeAddr(ctx, p);
                        return;
                    }
                }
                android.util.Log.i(TAG, "python: 10 秒内未就绪，pyRuntimeAddr 不写（.py 源暂时不可用）");
            }, "meow-py-watch").start();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "startPythonRuntime failed", t);
        }
    }

    /** 读 Python 协调者写的状态文件，返回它就绪时的端口（未就绪 = 0）。 */
    private static int pythonStatePort(Context ctx) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), PythonService.STATE_FILE);
            if (!f.isFile()) return 0;
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject o = new org.json.JSONObject(text);
            return o.optBoolean("running", false) ? o.optInt("port", 0) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 把 `pyRuntimeAddr` 指向 Python 模块端口（2026-11-28 中期分层）。
     *
     * <p>以前这里写的是 **jsRuntime**（单地址时代：pyrt 抢全局地址，再把 .js 转发给
     * QuickJS）。两个引擎分键之后：
     * <ul>
     *   <li>{@code jsRuntime} → QuickJS（本类 ensureLocalJsRuntime 负责），.js 源直连</li>
     *   <li>{@code pyRuntimeAddr} → Python 模块，.py 源直连</li>
     * </ul>
     * pyrt 在不在都不再影响 .js。
     *
     * <p>★ 键名是 pyRuntimeAddr 而不是 pyRuntime：iOS 壳用 `policy.pyRuntime`
     * 存启动状态对象（{state,error,at}），地址与状态必须分键。
     *
     * <p>★ 这也是 TV .py 源"1.2.25 之后变哑"的根治：旧逻辑只在现有 jsRuntime 为空
     * 或落在内核随机端口段（32768+）时才敢覆盖；而 QuickJS 改成固定 19381 之后
     * 就不在那个段里了 → Python 永远抢不到 jsRuntime → 宿主拿 QuickJS 的 /health
     * 问 py 能力得到 false，整类 .py 源判"不支持"（2026-11-28 在 .77 实测：
     * /api/health 的 unsupported 里就是一堆 *_py）。
     */
    private static void publishPyRuntimeAddr(Context ctx, int port) {
        try {
            String cur = core.DataPaths.read("policy.json");
            org.json.JSONObject o = cur == null || cur.trim().isEmpty()
                    ? new org.json.JSONObject() : new org.json.JSONObject(cur);
            String local = "http://127.0.0.1:" + port;
            String old = o.optString("pyRuntimeAddr", "").trim();
            if (local.equals(old)) return;
            // 只覆盖"本地"的旧值：用户手配的远端 py 引擎（NAS / adb reverse 到 PC）保留
            boolean oldIsLocal = old.contains("127.0.0.1") || old.contains("localhost");
            if (!old.isEmpty() && !oldIsLocal) {
                android.util.Log.i(TAG, "python: 保留已配置的 pyRuntimeAddr " + old);
                return;
            }
            o.put("pyRuntimeAddr", local);
            /*
             * 迁移：旧版把自己写进了 jsRuntime。如果那里是我们自己的 Python 端口
             * （127.0.0.1:19351..19370），删掉它 —— 下一次 ensureLocalJsRuntime 会把
             * jsRuntime 抢回给 QuickJS。用户手配的 127.0.0.1:19331（adb reverse 到
             * PC 的 pyrt）不在这个段里，保持不动。
             */
            String jsOld = o.optString("jsRuntime", "").trim();
            boolean removedLegacy = false;
            if (isPyModulePort(jsOld)) {
                o.remove("jsRuntime");
                removedLegacy = true;
                android.util.Log.i(TAG, "python: 移除旧版写入的 jsRuntime(" + jsOld + ")，交还 QuickJS");
            }
            boolean ok = core.DataPaths.write("policy.json", o.toString());
            android.util.Log.i(TAG, "python: pyRuntimeAddr -> " + local + " written=" + ok);
            /*
             * 删掉旧 jsRuntime 后必须**立刻**交还 QuickJS：ensureLocalJsRuntime 只在
             * 网关启动时跑一次，等下次启动才补的话，本次会话里 .js 源会全报
             * 「jsRuntime 未配置」（2026-11-28 在 .77 上实测到，立刻补调后正常）。
             */
            if (removedLegacy) ensureLocalJsRuntime(ctx);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "publishPyRuntimeAddr failed", t);
        }
    }

    /** 是否是本机 Python 模块的地址（127.0.0.1:19351..19370，端口段见 QuickJsRuntimeServer 注释）。 */
    private static boolean isPyModulePort(String addr) {
        if (addr == null) return false;
        if (!addr.contains("127.0.0.1") && !addr.contains("localhost")) return false;
        try {
            int port = java.net.URI.create(addr).getPort();
            return port >= 19351 && port <= 19370;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 判"这个地址是不是我们上次启动写进去的临时端口"。
     *
     * <p>QuickJS 服务用 `ServerSocket(0)` 拿随机端口，落在内核的临时端口段
     * （Linux/Android 默认 32768-60999）。用户手配的地址要么是不在这个段的固定端口
     * （pyrt 默认 19331、drpy 9330），要么是局域网 IP——都不会命中这条。
     * 用启发式而不是"留个标记文件"，是因为 policy 可能被用户/别的工具重写，
     * 标记会失配；端口段是内核保证的，判错概率极低。
     */
    private static boolean isOurEphemeral(String addr) {
        if (!addr.contains("127.0.0.1") && !addr.contains("localhost")) return false;
        try {
            java.net.URI u = java.net.URI.create(addr);
            int port = u.getPort();
            return port >= 32768 && port <= 60999;
        } catch (Throwable t) {
            return false;
        }
    }

    public static synchronized void stop() {
        started = false;
        HttpServer s = server;
        server = null;
        if (s != null) {
            try {
                s.stop();
            } catch (Throwable ignored) {
            }
        }
        Bridge.reset();
    }

    /**
     * 把 SharedPreferences 里的原始配置写到数据目录，供后端 {@code loadConfigSites()} 读。
     *
     * <p>不做这件事的后果很具体：{@code /api/parse} 拿不到 {@code parses} 表 →
     * {@code parse=1} 的源一律起不来；{@code /api/live} 拿不到 {@code lives} 表 → 直播空;
     * {@code doh}/{@code hosts}/{@code rules} 同理。
     */
    static void syncConfigFile(Context ctx) {
        try {
            String raw = ctx.getSharedPreferences("meow_tv_sites", Context.MODE_PRIVATE)
                    .getString("config_raw", "");
            File target = new File(new File(ctx.getFilesDir(), "gateway"), "config.sites.json");
            if (raw == null || raw.isEmpty()) {
                // 没导入过完整配置：留空文件，让后端按空表继续（站点仍走 LocalSites）
                if (!target.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    target.getParentFile().mkdirs();
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(target)) {
                        fos.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                return;
            }
            byte[] want = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (target.isFile() && target.length() == want.length) {
                // 长度相同就跳过：配置动辄几百 KB，每次启动都重写没必要
                return;
            }
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(target)) {
                fos.write(want);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "syncConfigFile failed", t);
        }
    }
}
