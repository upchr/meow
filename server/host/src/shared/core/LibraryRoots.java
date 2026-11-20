package core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 可浏览的片库根：把四端"真正有权限的目录"统一成一组根，供「选择目录」弹窗用。
 *
 * <p>根的来源（优先级从高到低，同一路径只出现一次）：
 * <ol>
 *   <li>{@code MEOW_SHARE_PATHS} ← fnOS {@code TRIM_DATA_SHARE_PATHS}（data-share 共享目录）</li>
 *   <li>{@code MEOW_ALLOWED_PATHS} ← fnOS {@code TRIM_DATA_ACCESSIBLE_PATHS}（用户授权的文件夹）</li>
 *   <li>{@code MEOW_LIBRARY_ROOTS}（Docker 显式声明的挂载点）</li>
 *   <li>本机模式（PC 插件）才追加盘符与家目录</li>
 * </ol>
 *
 * <p>★ 为什么"没有就返回空"而不是猜：猜出来的路径（{@code /vol1}、{@code /mnt}）要么
 * 没权限、要么是宿主机路径在容器里不存在，用户点了只会得到"目录不可读"。
 * 空列表 + 一句引导，比一堆死链接有用。
 *
 * <p>★ 根的 {@code id} 是服务端每次按固定顺序重新生成的短标识，前端只回传 id，
 * 服务端再反查真实路径 —— 任何"把路径当参数传回来"的写法都等于开了任意目录读取。
 */
public final class LibraryRoots {

    /** 单层最多返回多少条（防呆：超大目录会把响应撑到几 MB）。 */
    public static final int MAX_ENTRIES = 2000;

    private LibraryRoots() {
    }

    /** 一个根：id 由来源+序号构成，path 只用于展示与回填。 */
    public static final class Root {
        public final String id;
        public final String label;
        public final String path;
        public final String source;
        public final boolean readOnly;

        Root(String id, String label, String path, String source, boolean readOnly) {
            this.id = id;
            this.label = label;
            this.path = path;
            this.source = source;
            this.readOnly = readOnly;
        }

        JSONObject json() throws org.json.JSONException {
            return new JSONObject().put("id", id).put("label", label).put("path", path)
                    .put("source", source).put("readOnly", readOnly)
                    .put("canBrowse", canBrowse(path));
        }
    }

    public static JSONArray all() throws org.json.JSONException {
        JSONArray arr = new JSONArray();
        for (Root r : roots()) arr.put(r.json());
        return arr;
    }

    /**
     * 把 {@code rootId + 相对路径} 解析成真实目录，并做穿越校验。
     *
     * @return 解析后的真实路径
     * @throws IllegalArgumentException 未知 root / 路径逃逸 / 不是目录
     */
    public static Path resolve(String rootId, String rel) {
        Path root = null;
        for (Root r : roots()) {
            if (r.id.equals(rootId)) {
                root = Paths.get(r.path);
                break;
            }
        }
        if (root == null) throw new IllegalArgumentException("unknown root");

        String sub = rel == null ? "" : rel.trim();
        Path candidate = sub.isEmpty() ? root : root.resolve(sub).normalize();

        /*
         * 两道校验缺一不可：
         *   1) normalize 之后必须仍在根内 —— 挡住 "../../etc"；
         *   2) toRealPath 之后必须仍在根的 realpath 内 —— 挡住"根里放个符号链接
         *      指向 /etc"这种绕过（normalize 不会解开链接）。
         */
        if (!candidate.startsWith(root)) throw new IllegalArgumentException("path escapes root");
        try {
            Path real = candidate.toRealPath();
            Path realRoot = root.toRealPath();
            if (!real.startsWith(realRoot)) throw new IllegalArgumentException("path escapes root");
            if (!Files.isDirectory(real)) throw new IllegalArgumentException("not a directory");
            return real;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("目录不可读: " + e.getMessage());
        }
    }

    /** 列一层目录；{@code includeFiles} 时顺带返回文件名。 */
    public static JSONObject ls(String rootId, String rel, boolean includeFiles) throws Exception {
        Path dir = resolve(rootId, rel);
        File[] kids = dir.toFile().listFiles();
        JSONArray dirs = new JSONArray();
        JSONArray files = new JSONArray();
        if (kids == null) {
            return new JSONObject().put("ok", true).put("dirs", dirs).put("files", files);
        }
        boolean truncated = false;
        int n = 0;
        // 排序稳定：目录在前、按名字（中文按码位，够用且不依赖 Locale）
        java.util.Arrays.sort(kids, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File f : kids) {
            if (n++ >= MAX_ENTRIES) {
                truncated = true;
                break;
            }
            String name = f.getName();
            if (name.startsWith(".")) continue;   // 隐藏文件不进目录树
            JSONObject o = new JSONObject().put("name", name).put("path", name)
                    .put("mtime", f.lastModified());
            if (f.isDirectory()) {
                // readable=false 也照样返回：前端显示一行灰字原因，比静默消失好
                o.put("readable", f.canRead());
                dirs.put(o);
            } else if (includeFiles) {
                o.put("size", f.length());
                files.put(o);
            }
        }
        return new JSONObject().put("ok", true).put("dirs", dirs).put("files", files)
                .put("truncated", truncated);
    }

    /** 能力声明：前端据此决定用目录树 / 系统选择器 / TV 桥。 */
    public static JSONObject capabilities() throws org.json.JSONException {
        JSONObject hints = new JSONObject();
        hints.put("localPathNote", localPathNote());
        return new JSONObject().put("ok", true)
                .put("canBrowse", true)
                .put("canNativePicker", !inContainer())
                .put("canTvBridge", true)
                .put("roots", roots().size())
                .put("hints", hints);
    }

    // ------------------------------------------------------------ 内部

    private static List<Root> roots() {
        List<Root> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int[] seq = {0, 0, 0};

        addPathRoots(out, seen, seq, "share", env("MEOW_SHARE_PATHS"));
        addPathRoots(out, seen, seq, "authorized", env("MEOW_ALLOWED_PATHS"));
        addPathRoots(out, seen, seq, "mount", env("MEOW_LIBRARY_ROOTS"));

        boolean fpk = !env("MEOW_SHARE_PATHS").isEmpty() || !env("MEOW_ALLOWED_PATHS").isEmpty();
        if (out.isEmpty() && !fpk && !inContainer()) {
            // PC 本机插件：后端就在这台机器上，盘符 + 家目录是合法来源
            for (File f : File.listRoots()) {
                String p = f.getAbsolutePath();
                if (seen.add(p)) {
                    out.add(new Root("drive:" + (seq[2]++), p, p, "drive", !f.canWrite()));
                }
            }
            String home = System.getProperty("user.home", "");
            if (!home.isEmpty() && seen.add(home)) {
                out.add(new Root("drive:" + (seq[2]++), "家目录", home, "drive",
                        !new File(home).canWrite()));
            }
        }
        return out;
    }

    private static void addPathRoots(List<Root> out, Set<String> seen, int[] seq,
                                     String source, String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        int idx = source.equals("share") ? 0 : source.equals("authorized") ? 1 : 2;
        for (String part : raw.split(File.pathSeparator)) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            // 同一目录在多个来源里出现只保留第一个（share 优先）
            if (!seen.add(p)) continue;
            out.add(new Root(source + ":" + (seq[idx]++), labelOf(p), p, source,
                    !new File(p).canWrite()));
        }
    }

    private static String labelOf(String path) {
        String mounted = mountLabel(path);
        if (!mounted.isEmpty()) return mounted;
        try {
            Path p = Paths.get(path);
            Path name = p.getFileName();
            return name == null ? path : name.toString();
        } catch (Throwable t) {
            return path;
        }
    }

    /**
     * 网络挂载点的显示名：`//192.168.1.19/视频` → {@code 视频}。
     *
     * <p>为什么需要：fnOS 的「远程挂载」在 {@code TRIM_DATA_ACCESSIBLE_PATHS} 里给的是
     * **内部卷名**（如 {@code /vol02/1000-0-a4d36b4c}），而用户在授权界面看到的是
     * 「远程挂载/视频」。直接用目录名，左栏会显示一串哈希，用户认不出那是什么。
     *
     * <p>只对网络挂载（cifs 的 `//host/share`、nfs 的 `host:/export`）取末段；
     * 本地块设备（`/dev/sda1`）不取，否则整卷会显示成 "sda1"。
     */
    private static String mountLabel(String path) {
        try {
            for (String line : Files.readAllLines(Paths.get("/proc/mounts"))) {
                String[] f = line.split("\\s+");
                if (f.length < 2 || !f[1].equals(path)) continue;
                String dev = f[0];
                boolean network = dev.startsWith("//")
                        || (dev.contains(":") && !dev.startsWith("/"));
                if (!network) return "";
                String name = dev.substring(Math.max(dev.lastIndexOf('/'), dev.lastIndexOf(':')) + 1);
                return name;
            }
        } catch (Throwable ignored) {
            // 非 Linux / 无 /proc：退回目录名
        }
        return "";
    }

    private static boolean canBrowse(String path) {
        try {
            return Files.isDirectory(Paths.get(path), LinkOption.NOFOLLOW_LINKS)
                    && new File(path).canRead();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 容器里不列盘符：那只会把容器的 `/` 暴露成"根"。 */
    private static boolean inContainer() {
        return new File("/.dockerenv").exists();
    }

    private static String localPathNote() {
        if (inContainer()) return "容器内路径；宿主机目录需要先在 compose 里挂载";
        if (!env("MEOW_ALLOWED_PATHS").isEmpty() || !env("MEOW_SHARE_PATHS").isEmpty()) {
            return "fnOS 里授权的目录；要加更多去「应用设置 → 访问权限」";
        }
        return "这是本机路径";
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return v == null ? "" : v.trim();
    }
}
