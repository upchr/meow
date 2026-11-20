package routes;

import platform.HttpExchange;

import core.DataPaths;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 跨端状态同步：播放进度、收藏、偏好。
 *
 * <p><b>为什么能干净搬出</b>：三个端点共用一套"读文件 → 合并/裁剪 → 写回"的模式，
 * 有自己的锁（STATE_LOCK）、自己的容量上限与排序键（progress 按 ep_at、
 * favorites 按收藏时间）。只依赖 DataPaths 与 HTTP 工具，不碰站点注册表。
 *
 * <p><b>两处必须一起搬的语义</b>：
 * <ul>
 *   <li>{@code stateTrim} 的裁剪键：progress 用 {@code ep_at}（最近观看时间），
 *       不是 {@code at}（条目创建时间）。用错会把"很久以前收藏但最近在看"的进度删掉。</li>
 *   <li>{@code STATE_PREFS_SKIP}：这些 key 是本机专属（如后端地址），
 *       跨端同步时必须丢弃，否则会把 A 机的后端地址推到 B 机。</li>
 * </ul>
 */
public final class StateRoutes {

    private StateRoutes() {
    }

    public static final String STATE_PROGRESS_FILE = "state-progress.json";
    public static final String STATE_FAVORITES_FILE = "state-favorites.json";
    public static final int STATE_PROGRESS_MAX = 2000;
    public static final int STATE_FAVORITES_MAX = 5000;
    public static final int STATE_PROGRESS_PULL_MAX = 500;
    public static final Object STATE_LOCK = new Object();
    public static final String STATE_PREFS_FILE = "state-prefs.json";
    public static final java.util.Set<String> STATE_PREFS_SYNC = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "homeCols", "searchThreads", "searchLayout", "cacheDays", "historyLimit",
                    "danmaku", "danmakuDensity", "danmakuOpacity", "danmakuScale",
                    "danmakuDuration", "danmakuArea",
                    "autoNextMode", "autoNextDelay",
                    "skipIntro", "skipOutro", "skipMiddle", "skipCountdown",
                    "themeStyle", "themeMode"
            ));
    public static final java.util.Set<String> STATE_PREFS_SKIP = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "volume", "muted", "rate", "videoFit",
                    "wallpaper", "wallpaperBlur", "wallpaperOpacity",
                    "webdavUrl", "webdavPass", "webdavUser"
            ));
    public static String stateQueryId(Map<String, String> q) {
        String v = Api.trimToEmpty(q.get("vodId"));
        return v.isEmpty() ? Api.trimToEmpty(q.get("vod_id")) : v;
    }
    /**
     * 读一个 state 文件。
     *
     * <p>文件缺失、损坏、形状不对一律当空列表：首次启动就是"没有文件"，
     * 手改坏或断电写坏也不该让前端启动时整片 500——丢的只是进度，重建一条
     * 比报错划算。
     */
    /**
     * 状态文件的实际路径：按 fnOS uid 分命名空间。
     *
     * <p>网关用户 → {@code users/<uid>/state-progress.json}；没有网关身份
     * （密码登录、设备令牌、本机回环）→ 原样返回文件名，继续用全局文件。
     * 后半句是兼容性红线：Docker / PC / TV 的既有历史不能因为接入网关头而凭空消失。
     *
     * <p>uid 已在 {@code FnosIdentity.from} 校验成纯数字，拼接不会穿越目录。
     */
    static String f(HttpExchange ex, String name) {
        return core.Auth.stateScope(ex) + name;
    }

    public static JSONArray stateItems(String name) {
        String raw = core.DataPaths.read(name);
        if (raw == null || raw.trim().isEmpty()) return new JSONArray();
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.optJSONArray("items");
            return arr == null ? new JSONArray() : arr;
        } catch (Exception e) {
            System.out.println("[state] " + name + " unreadable, treated as empty: " + e.getMessage());
            return new JSONArray();
        }
    }
    public static JSONArray stateTrim(JSONArray items, String sortKey, int max) {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null) list.add(it);
        }
        list.sort((a, b) -> Long.compare(stateLong(b, sortKey, 0), stateLong(a, sortKey, 0)));
        JSONArray out = new JSONArray();
        for (int i = 0; i < list.size() && i < max; i++) out.put(list.get(i));
        return out;
    }
    public static JSONObject progressOut(JSONObject it) throws Exception {
        return new JSONObject()
                .put("site", stateStr(it, "site"))
                .put("vod_id", stateStr(it, "vod_id"))
                .put("pos", stateDouble(it, "pos", 0))
                .put("dur", stateDouble(it, "dur", 0))
                .put("flag", stateStr(it, "flag"))
                .put("ep", stateStr(it, "ep"))
                .put("ep_url", stateStr(it, "ep_url"))
                .put("ep_at", stateLong(it, "ep_at", 0));
    }
    /**
     * GET  /api/state/progress?site=&amp;vodId=   单条（无此记录时 item 为 null）
     * GET  /api/state/progress                    全量，最多 500 条按 ep_at 倒序
     * POST /api/state/progress                    并集写，返回 merged
     */
    public static void handleStateProgress(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            Map<String, String> q = Api.query(ex);
            // site/vodId 两种拼法都收（驼峰来自 docs/25，下划线来自 HistoryItem）。
            // 同一个 helper 也用在 favorites，见 stateQueryId。
            String site = Api.trimToEmpty(q.get("site"));
            String vodId = stateQueryId(q);
            // 「一个键都没带」才是拉全量；带了键但值是空白算调用方写错，
            // 否则 ?site=&vodId= 这种拼错的 URL 会静默拿到 500 条。
            boolean anyKey = q.containsKey("site") || q.containsKey("vodId") || q.containsKey("vod_id");
            if (!anyKey) {
                JSONArray sorted = stateTrim(stateItems(f(ex, STATE_PROGRESS_FILE)),
                        "ep_at", STATE_PROGRESS_PULL_MAX);
                JSONArray items = new JSONArray();
                for (int i = 0; i < sorted.length(); i++) items.put(progressOut(sorted.getJSONObject(i)));
                Api.sendJson(ex, 200, new JSONObject().put("ok", true).put("items", items).toString());
                return;
            }
            if (site.isEmpty() || vodId.isEmpty()) {
                Api.sendJson(ex, 400, "{\"code\":-1,\"msg\":\"site and vodId required\"}");
                return;
            }
            JSONArray items = stateItems(f(ex, STATE_PROGRESS_FILE));
            int idx = stateIndexOf(items, site, vodId);
            Api.sendJson(ex, 200, new JSONObject().put("ok", true)
                    .put("item", idx < 0 ? JSONObject.NULL : progressOut(items.getJSONObject(idx)))
                    .toString());
            return;
        }

        String raw = Api.read(ex.getRequestBody());
        JSONObject in;
        try {
            in = raw == null || raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw);
        } catch (Exception e) {
            Api.sendJson(ex, 400, "{\"code\":-1,\"msg\":\"bad json body\"}");
            return;
        }
        // 只接受 POST；PUT/DELETE 等不能默默落进"写进度"分支。
        if (!"POST".equalsIgnoreCase(method)) {
            Api.sendJson(ex, 405, "{\"code\":-1,\"msg\":\"method not allowed\"}");
            return;
        }
        String site = Api.trimToEmpty(in.optString("site", ""));
        // body 用 vod_id、query 用 vodId，两种都收：前端 HistoryItem 是下划线，
        // docs/25 的契约是驼峰。只认一种必然有人写错。
        String vodId = Api.trimToEmpty(in.optString("vod_id", in.optString("vodId", "")));
        if (site.isEmpty() || vodId.isEmpty()) {
            Api.sendJson(ex, 400, "{\"code\":-1,\"msg\":\"site and vod_id required\"}");
            return;
        }
        // pos 非法直接 400，不要落成 0：0 会被前端当成"没看过"，比拒绝更糟
        double pos = stateDouble(in, "pos", Double.NaN);
        if (!Double.isFinite(pos) || pos < 0) {
            Api.sendJson(ex, 400, "{\"code\":-1,\"msg\":\"pos must be a finite number >= 0\"}");
            return;
        }
        double dur = stateDouble(in, "dur", 0);
        if (!Double.isFinite(dur) || dur < 0) dur = 0;
        long epAt = stateLong(in, "ep_at", 0);
        if (epAt <= 0) epAt = System.currentTimeMillis();

        JSONObject rec = new JSONObject()
                .put("site", site)
                .put("vod_id", vodId)
                .put("pos", pos)
                .put("dur", dur)
                .put("flag", pickMeta(in, null, "flag"))
                .put("ep", pickMeta(in, null, "ep"))
                .put("ep_url", pickMeta(in, null, "ep_url"))
                .put("ep_at", epAt);

        boolean merged;
        boolean wrote = true;
        synchronized (STATE_LOCK) {
            JSONArray items = stateItems(f(ex, STATE_PROGRESS_FILE));
            int idx = stateIndexOf(items, site, vodId);
            JSONObject prev = idx < 0 ? null : items.optJSONObject(idx);
            long prevAt = prev == null ? 0 : stateLong(prev, "ep_at", 0);
            // 严格大于才覆盖：两台设备各自推进度，谁的时间戳新谁赢。
            // 无条件覆盖会让"手机只打开一下"把 PC 上 20:00 的进度冲成 0。
            merged = prev == null || epAt > prevAt;
            if (merged) {
                if (prev != null) {
                    // 没带的字段沿用旧值，与前端 mergeInto 同一条规则
                    // （history.ts:84-92「点一下封面把进度清掉」踩过）。显式传空串
                    // 仍然能清空，所以不算吞掉调用方意图。
                    rec.put("flag", pickMeta(in, prev, "flag"))
                            .put("ep", pickMeta(in, prev, "ep"))
                            .put("ep_url", pickMeta(in, prev, "ep_url"));
                    // dur=0 表示"时长未知"（history.ts:37），不是"时长是 0"。
                    // 拿它覆盖已知时长会让前端续播进度条失去总长，所以沿用旧值。
                    if (dur <= 0) {
                        double prevDur = stateDouble(prev, "dur", 0);
                        if (prevDur > 0) rec.put("dur", prevDur);
                    }
                }
                if (idx < 0) items.put(rec);
                else items.put(idx, rec);       // put(idx,..) 是替换，不会新增一条
                wrote = stateWrite(f(ex, STATE_PROGRESS_FILE),
                        stateTrim(items, "ep_at", STATE_PROGRESS_MAX));
            }
        }
        if (!wrote) {
            Api.sendJson(ex, 500, "{\"code\":-1,\"msg\":\"cannot write " + STATE_PROGRESS_FILE + "\"}");
            return;
        }
        Api.sendJson(ex, 200, new JSONObject().put("ok", true).put("merged", merged).toString());
    }
    /**
     * GET    /api/state/favorites            按 at 倒序
     * POST   /api/state/favorites            幂等新增/更新元信息，返回 count
     * DELETE /api/state/favorites?site=&amp;vodId=  幂等删除，返回 count
     */
    public static void handleStateFavorites(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            JSONArray sorted = stateTrim(stateItems(f(ex, STATE_FAVORITES_FILE)),
                    "at", STATE_FAVORITES_MAX);
            JSONArray items = new JSONArray();
            for (int i = 0; i < sorted.length(); i++) items.put(favoriteOut(sorted.getJSONObject(i)));
            Api.sendJson(ex, 200, new JSONObject().put("ok", true).put("items", items).toString());
            return;
        }

        // 只接受 POST/PUT/DELETE；其它方法明确 405，不能默默落进 POST 分支——
        // HEAD/PUT 一旦被当成新增，调用方的探测请求会真的改数据。
        boolean delete = "DELETE".equalsIgnoreCase(method);
        boolean write = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method);
        if (!delete && !write) {
            Api.sendJson(ex, 405, "{\"code\":-1,\"msg\":\"method not allowed\"}");
            return;
        }
        JSONObject in = null;
        String site;
        String vodId;
        if (delete) {
            Map<String, String> q = Api.query(ex);
            site = Api.trimToEmpty(q.get("site"));
            vodId = stateQueryId(q);
        } else {
            String raw = Api.read(ex.getRequestBody());
            try {
                in = raw == null || raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw);
            } catch (Exception e) {
                Api.sendJson(ex, 400, "{\"code\":-1,\"msg\":\"bad json body\"}");
                return;
            }
            site = Api.trimToEmpty(in.optString("site", ""));
            vodId = Api.trimToEmpty(in.optString("vod_id", in.optString("vodId", "")));
        }
        if (site.isEmpty() || vodId.isEmpty()) {
            Api.sendJson(ex, 400, "{\"code\":-1,\"msg\":\"site and vod_id required\"}");
            return;
        }

        int count;
        boolean wrote = true;
        synchronized (STATE_LOCK) {
            JSONArray items = stateItems(f(ex, STATE_FAVORITES_FILE));
            int idx = stateIndexOf(items, site, vodId);
            if (delete) {
                // 不存在也回 ok：DELETE 必须幂等，否则前端重试/重复点击会看到假错误
                //
                // 没删掉任何东西就不写盘：否则一次对不存在项的 DELETE 会凭空
                // 造出一个空文件，还会白占一次固定名 .tmp。
                if (idx >= 0) {
                    items.remove(idx);
                    wrote = stateWrite(f(ex, STATE_FAVORITES_FILE), items);
                }
                count = items.length();
            } else {
                JSONObject prev = idx < 0 ? null : items.optJSONObject(idx);
                JSONObject rec = new JSONObject()
                        .put("site", site)
                        .put("vod_id", vodId)
                        .put("vod_name", pickMeta(in, prev, "vod_name"))
                        .put("vod_pic", pickMeta(in, prev, "vod_pic"))
                        .put("vod_remarks", pickMeta(in, prev, "vod_remarks"))
                        .put("flag", pickMeta(in, prev, "flag"))
                        // 已存在时保留原 at：重复收藏若刷新 at，列表顺序会被一次
                        // 无意义的点击打乱，也不再是真正的幂等（第二次 POST 结果会变）。
                        .put("at", prev != null ? stateLong(prev, "at", 0) : pickAt(in));
                if (idx < 0) items.put(rec);
                else items.put(idx, rec);
                items = stateTrim(items, "at", STATE_FAVORITES_MAX);
                count = items.length();
                wrote = stateWrite(f(ex, STATE_FAVORITES_FILE), items);
            }
        }
        if (!wrote) {
            Api.sendJson(ex, 500, "{\"code\":-1,\"msg\":\"cannot write " + STATE_FAVORITES_FILE + "\"}");
            return;
        }
        Api.sendJson(ex, 200, new JSONObject().put("ok", true).put("count", count).toString());
    }
    public static void handleStatePrefs(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();

        if ("GET".equalsIgnoreCase(method)) {
            JSONObject stored = statePrefsRead(f(ex, STATE_PREFS_FILE));
            Api.sendJson(ex, 200, new JSONObject()
                    .put("ok", true)
                    .put("prefs", stored.optJSONObject("prefs") == null ? new JSONObject() : stored.optJSONObject("prefs"))
                    .put("at", stateLong(stored, "at", 0))
                    .toString());
            return;
        }

        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)) {
            Api.sendJson(ex, 405, "{\"code\":-1,\"msg\":\"method not allowed\"}");
            return;
        }

        String raw = Api.read(ex.getRequestBody());
        JSONObject in;
        try {
            in = raw == null || raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw);
        } catch (Exception e) {
            Api.sendJson(ex, 400, "{\"code\":-1,\"msg\":\"bad json body\"}");
            return;
        }
        // 允许 {prefs:{...}} 也允许直接把字段摊在顶层，前端两种写法都自然
        JSONObject patch = in.optJSONObject("prefs") == null ? in : in.optJSONObject("prefs");
        if (patch == null || patch.length() == 0) {
            // 空对象与"只发了不可同步字段"要区分：前者是调用方漏了 body（400 提示），
            // 后者是合法请求（200 + applied:0），混成一种会让排查变模糊。
            Api.sendJson(ex, 400, "{\"code\":-1,\"msg\":\"prefs object required\"}");
            return;
        }

        JSONObject stored;
        long at = System.currentTimeMillis();
        synchronized (STATE_LOCK) {
            stored = statePrefsRead(f(ex, STATE_PREFS_FILE));
            JSONObject merged = stored.optJSONObject("prefs");
            if (merged == null) merged = new JSONObject();
            int applied = 0;
            java.util.Iterator<String> keys = patch.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                // 白名单优先：不在 SYNC 里的一律不落盘（含黑名单与未知新字段），
                // 否则前端以后加一个设备级字段就会悄悄开始跨端同步。
                if (!STATE_PREFS_SYNC.contains(k)) continue;
                Object v = patch.opt(k);
                if (v == null || v == JSONObject.NULL) {
                    merged.remove(k);
                } else {
                    merged.put(k, v);
                }
                applied++;
            }
            if (applied == 0) {
                // 全是不可同步字段：不写盘，但也不能报错——前端可能整份 Prefs 推上来
                Api.sendJson(ex, 200, new JSONObject().put("ok", true).put("applied", 0)
                        .put("prefs", merged).put("at", stateLong(stored, "at", 0)).toString());
                return;
            }
            JSONObject out = new JSONObject().put("prefs", merged).put("at", at);
            if (!core.DataPaths.write(f(ex, STATE_PREFS_FILE), out.toString(2))) {
                Api.sendJson(ex, 500, "{\"code\":-1,\"msg\":\"cannot write " + STATE_PREFS_FILE + "\"}");
                return;
            }
            Api.sendJson(ex, 200, new JSONObject().put("ok", true).put("applied", applied)
                    .put("prefs", merged).put("at", at).toString());
        }
    }

    public static boolean stateWrite(String name, JSONArray items) throws Exception {
        return core.DataPaths.write(name, new JSONObject().put("items", items).toString(2));
    }
    /**
     * 取数值字段。
     *
     * <p>android-json 的 {@code isNull} 对**不存在的键**也返回 true，
     * 所以必须先 {@code has} 再 {@code isNull}；少一个判断就会把"字段缺省"
     * 和"显式 null"混成同一件事（实测）。
     */
    public static long stateLong(JSONObject o, String key, long def) {
        if (o == null || !o.has(key) || o.isNull(key)) return def;
        return o.optLong(key, def);
    }
    public static double stateDouble(JSONObject o, String key, double def) {
        if (o == null || !o.has(key) || o.isNull(key)) return def;
        double v = o.optDouble(key, def);
        return Double.isFinite(v) ? v : def;
    }
    public static String stateStr(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return "";
        String v = o.optString(key, "");
        return "null".equals(v) ? "" : v;
    }
    public static int stateIndexOf(JSONArray items, String site, String vodId) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            if (site.equals(stateStr(it, "site")) && vodId.equals(stateStr(it, "vod_id"))) return i;
        }
        return -1;
    }
    public static JSONObject favoriteOut(JSONObject it) throws Exception {
        JSONObject o = new JSONObject()
                .put("site", stateStr(it, "site"))
                .put("vod_id", stateStr(it, "vod_id"))
                .put("vod_name", stateStr(it, "vod_name"))
                .put("at", stateLong(it, "at", 0));
        putIfNotEmpty(o, "vod_pic", stateStr(it, "vod_pic"));
        putIfNotEmpty(o, "vod_remarks", stateStr(it, "vod_remarks"));
        putIfNotEmpty(o, "flag", stateStr(it, "flag"));
        return o;
    }
    public static JSONObject statePrefsRead(String path) {
        String raw = core.DataPaths.read(path);
        if (raw == null || raw.trim().isEmpty()) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            System.out.println("[state] " + path + " unreadable, treated as empty: " + e.getMessage());
            return new JSONObject();
        }
    }
    public static String pickMeta(JSONObject in, JSONObject prev, String key) {
        if (in != null && in.has(key) && !in.isNull(key)) return stateStr(in, key);
        return prev == null ? "" : stateStr(prev, key);
    }
    public static long pickAt(JSONObject in) {
        long at = stateLong(in, "at", 0);
        return at > 0 ? at : System.currentTimeMillis();
    }

    public static void putIfNotEmpty(JSONObject o, String key, String v) throws Exception {
        if (v != null && !v.isEmpty()) o.put(key, v);
    }
}
