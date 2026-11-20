use std::net::SocketAddr;
use std::sync::Arc;

use axum::{
    body::Body,
    extract::ConnectInfo,
    http::{header, HeaderName, HeaderValue, Request, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{any, get},
    Router,
};
use tower_http::services::{ServeDir, ServeFile};

use crate::{config::Config, deps::Deps, plugins};

#[derive(Clone)]
pub struct AppState {
    pub cfg: Arc<Config>,
    pub deps: Deps,
    pub client: reqwest::Client,
    pub plugins: plugins::Registry,
}

/// TCP（30010）入口。
///
/// <p>fpk 的 web 构建用 `--base=/app/meow/`（子路径部署要求，见 build-fpk.sh），
/// index.html 里所有资源引用都带前缀。但 TCP 监听一直是**裸路径**路由：
/// 直接打开 `http://<IP>:30010/` 时，浏览器去取 `/app/meow/assets/*.js`，
/// 被 SPA fallback 回 index.html（200 text/html）→ HTML 被当 JS 执行报错 →
/// **纯黑屏**（2026-11-16 用户实测 OECT，手机浏览器全黑）。
///
/// <p>所以 TCP 上同时提供带前缀的入口：根路径 307 到 `/app/meow/`，
/// 前缀路由剥掉后再交给同一套应用路由（assets 正常命中）；裸路径照旧
/// （`/api`、`/assets` 直连仍可用，不破坏已有脚本与探针）。
/// 直连入口（TCP 30010）：把 socket 对端 IP 写成可信的 `X-Forwarded-For`
/// （见 `set_peer_addr`），后端才分得清"谁在访问"。
pub fn build_router(state: AppState) -> Router {
    let inner = app_routes(state).layer(middleware::from_fn(set_peer_addr));
    let nested = format!("{GATEWAY_PREFIX}/");
    Router::new()
        .route("/", get(app_root_redirect))
        .route(GATEWAY_PREFIX, get(app_root_redirect))
        .nest_service(nested.as_str(), inner.clone())
        .merge(inner)
}

/// 根路径 → 带前缀入口。
///
/// ★ 只在 TCP 路由里挂：网关路由（unix）收到的是**已剥掉前缀**的路径，
///   若那里也跳 `/app/meow/` 会 302 回网关前缀、再剥前缀、再 302 —— 死循环。
async fn app_root_redirect() -> Response {
    Response::builder()
        .status(StatusCode::TEMPORARY_REDIRECT)
        .header(header::LOCATION, format!("{GATEWAY_PREFIX}/"))
        .body(Body::empty())
        .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
}

fn app_routes(state: AppState) -> Router {
    let cfg = state.cfg.clone();
    // SPA：命中文件就发文件，否则回 index.html（前端是 hash/history 路由）
    let static_svc = ServeDir::new(cfg.web_dir.clone())
        .append_index_html_on_directories(true)
        .fallback(ServeFile::new(cfg.web_dir.join("index.html")));

    Router::new()
        .route("/healthz", get(crate::health::healthz))
        // 必须在 /api/*path 之前显式注册：否则会被当成站点名转给后端
        .route("/api/health", get(crate::health::api_health))
        .route("/api/_shell/info", get(crate::health::shell_info))
        // 卷号回查：`app/installed` 恒缺 installVolumeID/installType（跨版本实测），
        // 浏览器又够不到应用中心的 unix socket，只能由壳代查（socket 不可用时
        // 退回 TRIM_APPDEST_VOL，原因见 appcenter.rs 顶部）。必须排在 /api/*path 之前。
        .route("/api/_shell/app/detail", get(crate::appcenter::detail))
        // 自更新落盘。必须排在 /api/*path 之前，否则会被当成站点名转给后端。
        .route("/api/_shell/upgrade/fpk", crate::upgrade::route())
        // 精确路径与 Docker nginx 模板一致；/proxy 不反代（它是 jar 对后端本机的端口握手点）
        .route("/config.json", any(crate::proxy::forward))
        .route("/spider-latest.jar", any(crate::proxy::forward))
        .route("/api/*path", any(crate::proxy::forward))
        .fallback_service(static_svc)
        .layer(middleware::from_fn(cache_headers))
        /*
         * ★ CORS 必须覆盖 meowd **自己答**的路由（/api/health、/api/_shell/…）。
         *
         * 壳内页面永远从 `http://meow.local` 加载本地资源，再跨域请求后端；
         * 这些路由原先不带 CORS 头，浏览器把 health 探测拦掉 → 前端直接报
         * 「后端未连接」，而同一后端的 /api/sites 却正常（它代理到 apiserver，
         * 那边有 CORS）。2026-11-13 TV 连 fpk 后端复现。
         */
        .layer(middleware::from_fn(cors))
        .with_state(state)
}

/// 跨端 CORS：与 apiserver 同口径（默认放行任意 Origin）。
///
/// 代理转发来的响应已经带 `access-control-allow-origin` 时不重复添加 ——
/// 重复会让浏览器按逗号拼接，反而判成无效。
async fn cors(req: Request<Body>, next: Next) -> Response {
    let origin = req
        .headers()
        .get(header::ORIGIN)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_owned());
    if req.method().as_str() == "OPTIONS" {
        if let Some(o) = origin.as_deref() {
            let mut res = StatusCode::NO_CONTENT.into_response();
            apply_cors(&mut res, o);
            return res;
        }
    }
    let mut res = next.run(req).await;
    if let Some(o) = origin.as_deref() {
        apply_cors(&mut res, o);
    }
    res
}

fn apply_cors(res: &mut Response, origin: &str) {
    let h = res.headers_mut();
    if h.contains_key(header::ACCESS_CONTROL_ALLOW_ORIGIN) {
        return;
    }
    let Ok(v) = HeaderValue::from_str(origin) else {
        return;
    };
    h.insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, v);
    h.insert(header::VARY, HeaderValue::from_static("Origin"));
    h.insert(
        header::ACCESS_CONTROL_ALLOW_METHODS,
        HeaderValue::from_static("GET,POST,DELETE,OPTIONS"),
    );
    h.insert(
        header::ACCESS_CONTROL_ALLOW_HEADERS,
        HeaderValue::from_static("Content-Type,Range,X-Meow-Token"),
    );
    h.insert(
        header::ACCESS_CONTROL_EXPOSE_HEADERS,
        HeaderValue::from_static(
            "Content-Length,Content-Range,Accept-Ranges,X-Danmaku-Source,X-Danmaku-Count,X-Danmaku-Total,X-Danmaku-Empty",
        ),
    );
}

/// 飞牛微应用入口前缀。网关（trim_http_cgi）是**带前缀**转发到 unix socket 的
/// （对照第三方样本 fn-installer：它自己在服务端剥 `/app/fn-installer`），
/// 所以剥前缀必须由我们做。只挂在 unix listener 上，TCP 30010 仍是裸路径。
pub const GATEWAY_PREFIX: &str = "/app/meow";

/// 网关入口：`nest_service` 在**路由匹配阶段**就剥掉 `/app/meow/`
/// （`/app/meow/healthz` → `/healthz`、`/app/meow/api/*` → `/api/*`）。
///
/// 实测不能改用 `Router::layer`：axum 的 layer 是路由命中之后才执行，
/// `/app/meow/healthz` 那时已经落到 ServeDir 兜底并返回 index.html 了。
pub fn build_gateway_router(state: AppState) -> Router {
    // ★ 用 app_routes 而不是 build_router：后者带"根路径 307 到 /app/meow/"，
    //   而网关收到的是**已剥前缀**的路径，"/" 再跳回前缀会 302 死循环。
    let inner = app_routes(state)
        .layer(middleware::from_fn(set_forwarded_prefix))
        // 网关侧唯一可信来源：保留 X-Trim-*，打上 fnos-gateway 标记
        .layer(middleware::from_fn(mark_gateway_ingress));
    let nested = format!("{GATEWAY_PREFIX}/");
    Router::new()
        // iframe 入口 url 不带尾斜杠（与 FnDepot 一致），统一 301 过去
        .route(GATEWAY_PREFIX, get(gateway_redirect))
        .nest_service(nested.as_str(), inner)
}

async fn gateway_redirect(req: Request<Body>) -> Response {
    let location = match req.uri().query() {
        Some(q) => format!("{GATEWAY_PREFIX}/?{q}"),
        None => format!("{GATEWAY_PREFIX}/"),
    };
    Response::builder()
        .status(StatusCode::MOVED_PERMANENTLY)
        .header(header::LOCATION, location)
        .body(Body::empty())
        .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
}

/// 后端拼绝对地址（m3u8 分片等）时只拿得到 Host，必须靠这个头把子路径补回去；
/// 该头不是逐跳头，proxy::forward 会原样透传给 Java。
async fn set_forwarded_prefix(mut req: Request<Body>, next: Next) -> Response {
    if !req.headers().contains_key("x-forwarded-prefix") {
        req.headers_mut().insert(
            HeaderName::from_static("x-forwarded-prefix"),
            HeaderValue::from_static(GATEWAY_PREFIX),
        );
    }
    next.run(req).await
}

/// 直连入口（TCP 30010）：用 socket 对端覆盖 `X-Forwarded-For` / `X-Forwarded-Proto`。
///
/// <p>★ 必须覆盖而不是透传：客户端能自带 `X-Forwarded-For: 127.0.0.1`，而后端
/// `clientIp()` 取 XFF 最后一段 —— 开了 `MEOW_TRUST_PROXY` 后，伪造值就会被当成
/// 真实来源，登录限流、设备 `bindIp`、媒体票 IP 绑定、`passwordAllowed` 的回环
/// 判断全部可绕过。用对端地址覆盖后，客户端自带的那个一定被丢掉。
///
/// <p>unix socket（fnOS 网关）那条**不挂**这层：唯一对端是本机网关，真实客户端
/// IP 只能指望网关照实转发；实测 fnOS micro_app 网关连客户端自带的 XFF 都会
/// 清洗掉，所以网关形态下后端只能看到 127.0.0.1（文档已列为此形态的已知限制）。
async fn set_peer_addr(mut req: Request<Body>, next: Next) -> Response {
    /*
     * ★ 直连入口必须先把 fnOS 身份头删干净再往下走。
     *
     * 客户端可以自带 `X-Trim-Userid: 1000` / `X-Trim-Isadmin: true`，而
     * `proxy::forward` 会原样透传所有非逐跳头 —— 不删的话，任何能连到
     * 30010 的人都能冒充 fnOS 管理员。Java 侧只认 ingress + uid 的组合，
     * 这里的 ingress=tcp 与删头是同一道闸的两半（缺一不可）。
     */
    for name in ["x-trim-userid", "x-trim-username", "x-trim-isadmin"] {
        req.headers_mut().remove(name);
    }
    // 覆盖而不是 append：客户端自带的 x-meow-ingress 一律作废
    req.headers_mut().insert(
        HeaderName::from_static("x-meow-ingress"),
        HeaderValue::from_static("tcp"),
    );

    let peer = req.extensions().get::<ConnectInfo<SocketAddr>>().map(|ci| ci.0);
    if let Some(addr) = peer {
        if let Ok(value) = HeaderValue::from_str(&addr.ip().to_string()) {
            req.headers_mut()
                .insert(HeaderName::from_static("x-forwarded-for"), value);
        }
        // meowd 自己是明文 HTTP；TLS 终结在网关/反代那一侧，不在这里
        req.headers_mut().insert(
            HeaderName::from_static("x-forwarded-proto"),
            HeaderValue::from_static("http"),
        );
    }
    next.run(req).await
}

/// 网关入口（unix socket）：fnOS 已经验过身份，保留 `X-Trim-*` 并标记来源。
///
/// <p>与 `set_peer_addr` 是一对：TCP 删头 + `tcp`，socket 保头 + `fnos-gateway`。
/// Java 只认「`X-Meow-Ingress: fnos-gateway` 且带 `X-Trim-Userid`」的组合，
/// 所以哪怕有人能连上 socket 伪造头，也至少要过「能连 socket」这一关
/// （socket 权限由 fpk 安装目录决定，见文档 §4.1 的已知前提）。
async fn mark_gateway_ingress(mut req: Request<Body>, next: Next) -> Response {
    req.headers_mut().insert(
        HeaderName::from_static("x-meow-ingress"),
        HeaderValue::from_static("fnos-gateway"),
    );
    next.run(req).await
}

async fn cache_headers(req: Request<Body>, next: Next) -> Response {
    let path = req.uri().path().to_string();
    let is_asset = path.starts_with("/assets/");
    let is_index = path == "/" || path.ends_with("index.html");
    let mut res = next.run(req).await;
    let value = if is_asset {
        // 构建产物带内容哈希，可长期缓存
        Some(HeaderValue::from_static("public, max-age=31536000, immutable"))
    } else if is_index {
        Some(HeaderValue::from_static("no-cache"))
    } else {
        None
    };
    if let Some(v) = value {
        res.headers_mut().insert(header::CACHE_CONTROL, v);
    }
    res
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;
    use tower::ServiceExt;

    fn test_state() -> AppState {
        let root = std::env::temp_dir().join(format!(
            "meowd-gateway-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        let web = root.join("web");
        let _ = std::fs::create_dir_all(&web);
        std::fs::write(web.join("index.html"), "<html>meow</html>").unwrap();
        // TCP 前缀路由的判据：真实资源文件（不是 SPA fallback 的 index.html）
        let assets = web.join("assets");
        let _ = std::fs::create_dir_all(&assets);
        std::fs::write(assets.join("probe.js"), "console.log('meow')").unwrap();
        let cfg = Config {
            bind: "127.0.0.1".to_string(),
            shell_port: 0,
            api_bind: "127.0.0.1".to_string(),
            api_port: 1, // 测试只断言壳路由，不 probe 后端
            app_dest: root.clone(),
            gateway_socket: root.join("meow.sock"),
            pkg_var: root.join("var"),
            web_dir: web,
            seed_dir: root.join("seed"),
            data_dir: root.join("data"),
            run_dir: root.join("run"),
            log_dir: root.join("logs"),
        };
        AppState {
            cfg: Arc::new(cfg),
            deps: Deps::probe(&PathBuf::from(".")),
            client: crate::proxy::build_client().unwrap(),
            plugins: plugins::new_registry(),
        }
    }

    async fn request(uri: &str) -> Response {
        build_gateway_router(test_state())
            .oneshot(Request::builder().uri(uri).body(Body::empty()).unwrap())
            .await
            .unwrap()
    }

    #[tokio::test]
    async fn gateway_redirects_root_to_trailing_slash() {
        let res = request("/app/meow?x=1").await;
        assert_eq!(res.status(), StatusCode::MOVED_PERMANENTLY);
        assert_eq!(
            res.headers().get(header::LOCATION).unwrap(),
            "/app/meow/?x=1"
        );
    }

    #[tokio::test]
    async fn gateway_strips_prefix_for_shell_routes() {
        let res = request("/app/meow/healthz").await;
        assert_eq!(res.status(), StatusCode::OK);
        let bytes = axum::body::to_bytes(res.into_body(), 1 << 20)
            .await
            .unwrap();
        let json: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(json["ok"], serde_json::json!(true));
    }

    #[tokio::test]
    async fn gateway_serves_spa_through_prefix() {
        let res = request("/app/meow/settings").await;
        assert_eq!(res.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn gateway_rejects_prefix_collision() {
        let res = request("/app/meowx").await;
        assert_eq!(res.status(), StatusCode::NOT_FOUND);
    }

    // ---------------- TCP（30010）入口：带前缀的 web 资源必须真命中文件 ----------------
    //
    // 2026-11-16 用户实测：直接开 `http://<IP>:30010/` 黑屏。根因是 fpk 的 web
    // 构建 base=/app/meow/，index 引用 `/app/meow/assets/*`，而 TCP router 只有
    // 裸路径 → 被 SPA fallback 成 index.html（200 text/html），浏览器把 HTML
    // 当 JS 执行报错 → 全黑。

    async fn request_tcp(uri: &str) -> Response {
        build_router(test_state())
            .oneshot(Request::builder().uri(uri).body(Body::empty()).unwrap())
            .await
            .unwrap()
    }

    #[tokio::test]
    async fn tcp_root_redirects_to_app_prefix() {
        let res = request_tcp("/").await;
        assert_eq!(res.status(), StatusCode::TEMPORARY_REDIRECT);
        assert_eq!(res.headers().get(header::LOCATION).unwrap(), "/app/meow/");
    }

    #[tokio::test]
    async fn tcp_serves_assets_behind_prefix() {
        let res = request_tcp("/app/meow/assets/probe.js").await;
        assert_eq!(res.status(), StatusCode::OK);
        let ct = res
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        assert!(ct.contains("javascript"), "content-type 应为 JS，实际 {ct}");
        let bytes = axum::body::to_bytes(res.into_body(), 1 << 20).await.unwrap();
        assert_eq!(bytes.as_ref(), b"console.log('meow')");
    }

    #[tokio::test]
    async fn tcp_keeps_bare_paths_for_api_and_assets() {
        // 裸 /healthz 仍可用（探针/脚本依赖）
        let res = request_tcp("/healthz").await;
        assert_eq!(res.status(), StatusCode::OK);
        // 裸 /assets 也仍可用（不破坏已有直连）
        let res = request_tcp("/assets/probe.js").await;
        assert_eq!(res.status(), StatusCode::OK);
    }
}
