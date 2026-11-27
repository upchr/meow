package com.meow.tv.runtime;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Python 运行时的协调者：拉起模块进程里的 PyService、探端口、把源脚本推过去。
 *
 * <p>为什么 Python 不在这里跑：Chaquopy 的 AndroidPlatform 要求 assets 与
 * SharedPreferences 来自同一个 Application。宿主进程里
 * createPackageContext(模块包).getApplicationContext() 返回 null，
 * 传宿主 Application 又读不到模块的 assets（stdlib 在模块 APK 里）。
 * 所以 Python 必须在模块自己的进程里跑，宿主只能通过 HTTP 与它相处。
 *
 * <p>脚本为什么要推：模块进程是另一个 uid，读不到本应用的 filesDir。
 * 本类把 filesDir/py-scripts/*.py 逐个 POST 到 /_put/&lt;name&gt;。
 *
 * <p>端口怎么定：从 19351 起探 20 个，命中 "service":"pyrt" 即为它。
 */
public class PythonService extends Service {

    private static final String TAG = "MeowPy";
    private static final int PORT_MIN = 19351;
    private static final int PORT_TRIES = 20;
    private static final String MODULE_PACKAGE = "com.meow.runtime.python";
    private static final String MODULE_SERVICE = "com.meow.runtime.python.PyService";
    public static final String STATE_FILE = "py-runtime.json";

    private Thread worker;
    /** drpy 上游（本机 QuickJS）。由 GatewayServer 通过 Intent 传进来。 */
    private volatile String drpyUpstream = "";
    /** 持有绑定：解绑会让模块进程被回收，服务就没了。 */
    private android.content.ServiceConnection conn;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        applyIntent(intent);
        ensureWorker();
        return START_NOT_STICKY;
    }

    /**
     * 必须返回真 Binder：返回 null 的 Service 绑定会被系统立即取消并 stop，
     * 客户端就拿不到"有活跃绑定"的 oomAdj 加成。
     *
     * <p>为什么要绑定而不是 startService：Android 12+ 的 cached-app freezer
     * 会把没有前台组件、也没有活跃 binder 连接的进程直接冻住。实测 .77 上
     * `com.meow.tv:py` 长期停在 `do_freezer_trap`，19351 端口还在 LISTEN 但
     * 请求全部超时、堆了 4 条 CLOSE_WAIT —— 服务"在"但完全不可用。
     * 主进程持有绑定后该进程 importance 提升，前台使用期不会被冻结。
     *
     * <p>返回裸 {@code Binder} 而不是 LocalBinder：这是个跨进程绑定
     * （{@code com.meow.tv} → {@code com.meow.tv:py}），LocalBinder 到对端
     * 只剩 BinderProxy，强转必炸。这里只需要"连接存在"这件事本身。
     */
    @Override
    public IBinder onBind(Intent intent) {
        applyIntent(intent);
        ensureWorker();
        return new android.os.Binder();
    }

    private void applyIntent(Intent intent) {
        if (intent == null) return;
        String d = intent.getStringExtra("drpy");
        if (d != null) drpyUpstream = d;
    }

    private void ensureWorker() {
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::boot, "meow-py");
            worker.start();
        }
    }

    private void boot() {
        try {
            if (!RuntimeModuleHost.pythonInstalled(getApplicationContext())) {
                state(false, 0, "", "Python 运行时模块未安装");
                android.util.Log.i(TAG, "python module not installed");
                return;
            }
            String moduleVersion = RuntimeModuleHost.pythonVersion(getApplicationContext());
            File scripts = new File(getFilesDir(), "py-scripts");
            if (!scripts.isDirectory() && !scripts.mkdirs() && !scripts.isDirectory()) {
                state(false, 0, moduleVersion, "无法创建脚本目录");
                return;
            }
            String drpy = drpyUpstream;
            Intent start = new Intent();
            start.setClassName(MODULE_PACKAGE, MODULE_SERVICE);
            start.putExtra("drpy", drpy);
            /*
             * ★ 必须 bindService，不能 startService：
             *   Android 8+ 禁止后台进程启动其他应用的 Service，实测
             *   `Background start not allowed: service Intent { cmp=com.meow.runtime.python/.PyService }`
             *   —— 服务根本不起来，日志里只有这一行 warning。
             *   绑定不受该限制；模块侧在 onBind 里拉起 Python。
             */
            try {
                conn = new android.content.ServiceConnection() {
                    @Override
                    public void onServiceConnected(android.content.ComponentName n, IBinder b) {
                        android.util.Log.i(TAG, "模块 PyService 已绑定");
                    }

                    @Override
                    public void onServiceDisconnected(android.content.ComponentName n) {
                        android.util.Log.w(TAG, "模块 PyService 断开");
                    }
                };
                boolean bound = bindService(start, conn, android.content.Context.BIND_AUTO_CREATE);
                android.util.Log.i(TAG, "bindService=" + bound + ", drpy=" + drpy);
                if (!bound) {
                    state(false, 0, moduleVersion, "无法绑定模块服务");
                    return;
                }
            } catch (Throwable t) {
                state(false, 0, moduleVersion, "绑定模块服务失败: " + t.getMessage());
                return;
            }
            int port = waitForPort(25_000);
            if (port <= 0) {
                state(false, 0, moduleVersion, "Python 服务 25 秒内未就绪（看 logcat 的 MeowPySvc）");
                return;
            }
            int pushed = pushScripts(scripts, port);
            android.util.Log.i(TAG, "python ready: port=" + port + ", scripts pushed=" + pushed);
            state(true, port, moduleVersion, "");
            /*
             * 不在这里写 policy：本服务跑在 :py 进程，而 shared 的 DataPaths
             * 在非 :spider 进程里 write() 返回 false（实测 written=false，
             * 端口没落地）。改由 GatewayServer 异步读状态文件后接管。
             */
        } catch (Throwable t) {
            state(false, 0, "", t.getClass().getSimpleName() + ": " + t.getMessage());
            android.util.Log.w(TAG, "python boot failed", t);
        }
    }

    private int waitForPort(int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (int i = 0; i < PORT_TRIES; i++) {
                int p = PORT_MIN + i;
                String body = http("GET", "http://127.0.0.1:" + p + "/health", null, 1200);
                if (body != null && body.contains("\"service\":\"pyrt\"")) return p;
            }
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
        return 0;
    }

    private int pushScripts(File dir, int port) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".py"));
        android.util.Log.i(TAG, "pushScripts: dir=" + dir + " isDir=" + dir.isDirectory()
                + " found=" + (files == null ? -1 : files.length));
        if (files == null || files.length == 0) return 0;
        int ok = 0;
        for (File f : files) {
            try {
                byte[] data = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int off = 0;
                    while (off < data.length) {
                        int n = in.read(data, off, data.length - off);
                        if (n <= 0) break;
                        off += n;
                    }
                }
                String name = URLEncoder.encode(f.getName(), "UTF-8");
                String resp = http("POST", "http://127.0.0.1:" + port + "/_put/" + name, data, 4000);
                // pyrt 用 json.dumps 默认分隔符返回 `{"ok": true}`（带空格），
                // 判 `"ok":true` 会全部算失败 —— 实测 pushed=0 的根因就是这个。
                if (resp != null && resp.contains("\"ok\"") && !resp.contains("\"ok\": false")
                        && !resp.contains("\"ok\":false")) ok++;
            } catch (Throwable t) {
                android.util.Log.w(TAG, "push " + f.getName() + " failed: " + t);
            }
        }
        return ok;
    }

    private String http(String method, String url, byte[] body, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /*
     * 2026-11-28 中期分层：Python 端口的 policy 发布搬到
     * GatewayServer.publishPyRuntimeAddr()，写的是 pyRuntimeAddr（不是 jsRuntime）。
     * 这里原来的 readJsRuntime()/publishPolicy() 从没被调用过（死代码），一并删掉，
     * 免得下次有人照着它再去抢 jsRuntime。
     */

    private void state(boolean running, int port, String version, String error) {
        try {
            JSONObject o = new JSONObject();
            o.put("running", running);
            o.put("port", port);
            o.put("version", version);
            o.put("error", error);
            o.put("pid", android.os.Process.myPid());
            File f = new File(getFilesDir(), STATE_FILE);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }
}
