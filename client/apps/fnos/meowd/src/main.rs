mod appcenter;
mod config;
mod deps;
mod health;
mod http;
mod plugins;
mod proxy;
mod supervisor;
mod upgrade;
mod version;

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result};
#[cfg(unix)]
use axum::{body::Body, http::Request};
use tokio::net::TcpListener;
#[cfg(unix)]
use tokio::net::UnixListener;
use tokio::sync::watch;
use tracing::{info, warn};

fn main() -> Result<()> {
    // 必须在建 runtime 之前脱离调用方会话：fnOS 的 cmd/main 用 nohup 拉起我们，
    // 脚本退出后整个进程组可能被回收。
    detach_session();
    init_tracing();

    let cfg = config::Config::load();
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .context("build tokio runtime")?;
    runtime.block_on(run(cfg))
}

fn detach_session() {
    #[cfg(unix)]
    {
        // 已是会话首进程时 setsid 会失败（EPERM），属正常情况
        unsafe {
            libc::setsid();
        }
    }
}

fn init_tracing() {
    let filter = tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"));
    tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_target(false)
        .init();
}

async fn run(cfg: config::Config) -> Result<()> {
    cfg.ensure_dirs().context("create data dirs")?;
    let lock = acquire_lock(&cfg.run_dir)?;
    info!(
        shell = %format!("{}:{}", cfg.bind, cfg.shell_port),
        api = cfg.api_port,
        data = %cfg.data_dir.display(),
        "meowd starting"
    );

    let deps = deps::Deps::probe(&cfg.app_dest);
    let client = proxy::build_client()?;
    let plugins = plugins::new_registry();
    let state = http::AppState {
        cfg: Arc::new(cfg.clone()),
        deps: deps.clone(),
        client: client.clone(),
        plugins: plugins.clone(),
    };

    let (tx, rx) = watch::channel(false);
    tokio::spawn(shutdown_signal(tx));
    let supervisor = tokio::spawn(supervisor::run(cfg.clone(), deps, plugins, client, rx.clone()));

    let app = http::build_router(state.clone());
    #[cfg(unix)]
    let gateway_app = http::build_gateway_router(state);
    #[cfg(not(unix))]
    let _ = state;

    // TCP 与 unix socket 两条 listener 共享同一个关停信号
    #[cfg(unix)]
    let (rx1, rx2) = (rx.clone(), rx.clone());

    let addr = format!("{}:{}", cfg.bind, cfg.shell_port);
    let listener = TcpListener::bind(&addr)
        .await
        .with_context(|| format!("bind {addr}"))?;
    info!("listening on http://{addr}");

    #[cfg(unix)]
    {
        match bind_gateway_socket(&cfg.gateway_socket)? {
            Some(socket) => {
                let tcp = async move {
                    // ★ 带连接信息起服务：http::set_peer_addr 靠它把 socket 对端 IP
                    //   注入 X-Forwarded-For。少了这一句，直连 30010 的部署在后端看来
                    //   全是 127.0.0.1（鉴权限流、设备 bindIp、媒体票 IP 绑定全部失效）。
                    axum::serve(
                        listener,
                        app.into_make_service_with_connect_info::<SocketAddr>(),
                    )
                    .with_graceful_shutdown(wait_shutdown(rx1))
                    .await
                    .context("http server")
                };
                let gw = serve_gateway(socket, gateway_app, rx2);
                let (tcp_res, ()) = tokio::join!(tcp, gw);
                tcp_res?;
            }
            None => {
                // socket 建不起来不该拖垮整个应用：TCP 30010 仍可直连，网关入口返回 502
                axum::serve(
                    listener,
                    app.into_make_service_with_connect_info::<SocketAddr>(),
                )
                .with_graceful_shutdown(wait_shutdown(rx.clone()))
                .await
                .context("http server")?;
            }
        }
    }
    #[cfg(not(unix))]
    {
        axum::serve(
            listener,
            app.into_make_service_with_connect_info::<SocketAddr>(),
        )
        .with_graceful_shutdown(wait_shutdown(rx))
        .await
        .context("http server")?;
    }

    // 等守护任务把插件、后端进程组依次收干净（插件 10s + Java 10s，留余量）
    let _ = tokio::time::timeout(Duration::from_secs(30), supervisor).await;
    #[cfg(unix)]
    let _ = std::fs::remove_file(&cfg.gateway_socket);
    let _ = std::fs::remove_file(&lock);
    info!("meowd stopped");
    Ok(())
}

async fn shutdown_signal(tx: watch::Sender<bool>) {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{signal, SignalKind};
        let mut term = signal(SignalKind::terminate()).ok();
        let mut int = signal(SignalKind::interrupt()).ok();
        match (term.as_mut(), int.as_mut()) {
            (Some(t), Some(i)) => {
                tokio::select! {
                    _ = t.recv() => {}
                    _ = i.recv() => {}
                }
            }
            (Some(t), None) => {
                t.recv().await;
            }
            (None, Some(i)) => {
                i.recv().await;
            }
            (None, None) => {
                let _ = tokio::signal::ctrl_c().await;
            }
        }
    }
    #[cfg(not(unix))]
    {
        let _ = tokio::signal::ctrl_c().await;
    }

    info!("shutdown signal received");
    let _ = tx.send(true);
    // 给 supervisor 一点时间发 TERM，然后 axum 才停连接
    tokio::time::sleep(Duration::from_millis(300)).await;
}

async fn wait_shutdown(mut rx: watch::Receiver<bool>) {
    loop {
        if *rx.borrow() {
            return;
        }
        // 发送端被丢弃（异常路径）时也退出，避免 serve 永远收不了尾
        if rx.changed().await.is_err() {
            return;
        }
    }
}

/// 给网关 unix socket 收连接。
///
/// 不能直接用 `axum::serve`：0.7.9 的签名是 `serve(TcpListener, _)`（实测），
/// 只收 TCP。这里自己 accept 后交给 hyper，再把 `Incoming` 换成 axum 的 `Body`
/// 送进同一个 Router —— 路由逻辑不复制第二份。
#[cfg(unix)]
async fn serve_gateway(listener: UnixListener, app: axum::Router, rx: watch::Receiver<bool>) {
    loop {
        let accepted = tokio::select! {
            _ = wait_shutdown(rx.clone()) => break,
            r = listener.accept() => r,
        };
        let (stream, _) = match accepted {
            Ok(v) => v,
            Err(e) => {
                warn!(error = %e, "gateway accept failed");
                continue;
            }
        };
        let app = app.clone();
        tokio::spawn(async move {
            let io = hyper_util::rt::TokioIo::new(stream);
            let service = hyper::service::service_fn(move |req: Request<hyper::body::Incoming>| {
                let app = app.clone();
                async move {
                    let (parts, body) = req.into_parts();
                    let req = Request::from_parts(parts, Body::new(body));
                    tower::ServiceExt::oneshot(app, req).await
                }
            });
            if let Err(e) = hyper::server::conn::http1::Builder::new()
                .serve_connection(io, service)
                .await
            {
                warn!(error = %e, "gateway connection error");
            }
        });
    }
}

/// 绑定网关 unix socket。
///
/// 崩溃/强杀会留下旧 inode，bind 前必须无条件删；建不出来返回 `Ok(None)`，
/// 由调用方降级成「只跑 TCP 30010」，不要阻断启动。
#[cfg(unix)]
fn bind_gateway_socket(path: &std::path::Path) -> Result<Option<UnixListener>> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).with_context(|| format!("create {}", dir.display()))?;
    }
    let _ = std::fs::remove_file(path);
    match UnixListener::bind(path) {
        Ok(listener) => {
            info!(socket = %path.display(), "gateway socket listening");
            Ok(Some(listener))
        }
        Err(e) => {
            warn!(
                socket = %path.display(),
                error = %e,
                "gateway socket unavailable; port entry still works"
            );
            Ok(None)
        }
    }
}

/// 单实例：pid 文件同时供 `cmd/main status` 判断运行状态。
fn acquire_lock(run_dir: &std::path::Path) -> Result<PathBuf> {
    let path = run_dir.join("meowd.pid");
    if let Ok(text) = std::fs::read_to_string(&path) {
        if let Ok(pid) = text.trim().parse::<i32>() {
            if pid > 0 && process_alive(pid) {
                anyhow::bail!("another meowd is already running (pid {pid})");
            }
        }
        let _ = std::fs::remove_file(&path);
    }
    std::fs::write(&path, std::process::id().to_string())
        .with_context(|| format!("write {}", path.display()))?;
    Ok(path)
}

#[cfg(unix)]
fn process_alive(pid: i32) -> bool {
    std::path::Path::new(&format!("/proc/{pid}")).exists()
}

#[cfg(not(unix))]
fn process_alive(pid: i32) -> bool {
    let _ = pid;
    warn!("process_alive check unsupported on this platform");
    false
}
