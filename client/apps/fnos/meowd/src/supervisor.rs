use std::path::Path;
use std::process::Stdio;
use std::time::Duration;

use tokio::process::Child;
use tokio::sync::watch;
use tracing::{error, info, warn};

use crate::{config::Config, deps::Deps, plugins};

/// 连续稳定这么久就认为这次启动是健康的，退避计数归零
const MIN_STABLE: Duration = Duration::from_secs(60);
const MAX_BACKOFF: Duration = Duration::from_secs(30);
const MAX_RAPID_FAILURES: u32 = 5;

#[cfg(windows)]
const CLASSPATH_SEP: &str = ";";
#[cfg(not(windows))]
const CLASSPATH_SEP: &str = ":";

/// 守护一组子进程：Java 后端一个循环，每个随包插件各自一个循环。
///
/// ★ 两者**不共用重启循环**：插件崩溃只重启插件，绝不重启 Java——
///   否则一次弹幕服务异常会把正在播放的后端一起打断。
pub async fn run(
    cfg: Config,
    deps: Deps,
    reg: plugins::Registry,
    client: reqwest::Client,
    mut shutdown: watch::Receiver<bool>,
) {
    let entries = plugins::discover(&cfg.app_dest.join("plugins"));
    if !entries.is_empty() {
        info!(count = entries.len(), "bundled plugins discovered");
    }

    let (plugin_tx, plugin_rx) = watch::channel(false);
    let mut plugin_tasks = Vec::new();
    for entry in entries {
        plugin_tasks.push(tokio::spawn(plugins::run_one(
            cfg.clone(),
            deps.clone(),
            reg.clone(),
            client.clone(),
            entry,
            plugin_rx.clone(),
        )));
    }

    let (java_tx, java_rx) = watch::channel(false);
    let mut java_task = tokio::spawn(java_loop(cfg.clone(), deps.clone(), java_rx.clone()));
    // ★ 一个 JoinHandle 只能被 poll 到 Ready 一次：select 里已经消费掉的句柄，
    //   后面再 timeout(..., &mut java_task) 会 panic "JoinHandle polled after completion"。
    //   release 是 panic=abort，整个 meowd 会被带走，真正的 bind 错误反而看不到
    //   （2026-11-16 OECT 实测只剩这一条 panic）。java_done 记录"句柄已 poll 完"。
    let mut java_done = false;
    loop {
        tokio::select! {
            _ = shutdown.changed() => break,
            res = &mut java_task => {
                // 收到停止信号之后才结束 → 正常收尾，尾部不需要再 poll
                if *shutdown.borrow() {
                    java_done = true;
                    break;
                }
                // 没收到停止信号却自己结束：重建 Java 守护，别把整个 supervisor 收掉
                match res {
                    Ok(()) => warn!("java supervisor exited unexpectedly; restarting"),
                    Err(e) => warn!(error = %e, "java supervisor task failed; restarting"),
                }
                tokio::select! {
                    _ = shutdown.changed() => {
                        java_done = true;
                        break;
                    }
                    _ = tokio::time::sleep(Duration::from_secs(1)) => {}
                }
                java_task = tokio::spawn(java_loop(cfg.clone(), deps.clone(), java_rx.clone()));
            }
        }
    }

    // 顺序：先插件后 Java。插件退出时可能还在回调后端，反过来会打到已停的服务。
    let _ = plugin_tx.send(true);
    for task in plugin_tasks {
        if tokio::time::timeout(Duration::from_secs(12), task).await.is_err() {
            warn!("plugin task did not stop within 12s");
        }
    }
    let _ = java_tx.send(true);
    // 只有没被 select 消费过的句柄才能再 poll
    if !java_done
        && tokio::time::timeout(Duration::from_secs(15), &mut java_task)
            .await
            .is_err()
    {
        warn!("java task did not stop within 15s");
    }
    info!("supervisor exited");
}

async fn java_loop(cfg: Config, deps: Deps, mut shutdown: watch::Receiver<bool>) {
    let mut backoff = Duration::from_secs(1);
    let mut rapid = 0u32;

    loop {
        if *shutdown.borrow() {
            break;
        }

        let java = match deps.java_path() {
            Some(p) => p,
            None => {
                // 依赖可能稍后才被装上，定期重探而不是永久放弃
                warn!("java not found; api backend disabled, retry in 15s");
                deps.rescan(&cfg.app_dest);
                tokio::select! {
                    _ = shutdown.changed() => break,
                    _ = tokio::time::sleep(Duration::from_secs(15)) => continue,
                }
            }
        };

        let mut child = match spawn_api(&cfg, &java) {
            Ok(c) => c,
            Err(e) => {
                error!("failed to spawn api backend: {e}");
                tokio::select! {
                    _ = shutdown.changed() => break,
                    _ = tokio::time::sleep(Duration::from_secs(15)) => continue,
                }
            }
        };
        info!(pid = child.id().unwrap_or(0), java = %java.display(), "api backend started");

        let started = std::time::Instant::now();
        let status = tokio::select! {
            s = child.wait() => Some(s),
            _ = shutdown.changed() => None,
        };

        match status {
            None => {
                // 收到停止信号：先 TERM 整个进程组，超时再 KILL
                stop_child(&mut child).await;
                break;
            }
            Some(Ok(st)) => {
                warn!(code = st.code(), "api backend exited");
            }
            Some(Err(e)) => {
                warn!("api backend wait failed: {e}");
            }
        }

        if started.elapsed() >= MIN_STABLE {
            rapid = 0;
            backoff = Duration::from_secs(1);
        }
        rapid += 1;

        if rapid > MAX_RAPID_FAILURES {
            warn!(
                "api backend failed {} times in a row; cooling down {}s",
                rapid,
                MAX_BACKOFF.as_secs()
            );
            tokio::select! {
                _ = shutdown.changed() => break,
                _ = tokio::time::sleep(MAX_BACKOFF) => {}
            }
            rapid = 0;
        } else {
            tokio::select! {
                _ = shutdown.changed() => break,
                _ = tokio::time::sleep(backoff) => {}
            }
            backoff = (backoff * 2).min(MAX_BACKOFF);
        }
    }
}

fn spawn_api(cfg: &Config, java: &Path) -> anyhow::Result<Child> {
    std::fs::create_dir_all(&cfg.log_dir)?;
    let log_path = cfg.log_dir.join("api.log");
    let out = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_path)?;
    let err = out.try_clone()?;

    let apiserver_dir = cfg.seed_dir.join("apiserver");
    if !apiserver_dir.join("apiserver.jar").exists() {
        anyhow::bail!("seed missing: {}", apiserver_dir.join("apiserver.jar").display());
    }

    let mut cmd = tokio::process::Command::new(java);
    cmd.current_dir(&apiserver_dir)
        /*
         * 必须显式给堆上限：JDK 默认 MaxRAMPercentage=25%，OECT 这类 4GB
         * 机器会拿到 ~972MB，长时间播放/大 XML 会把提交量推高（实测 PC
         * 同代码可到 800MB+）。512m 与 Docker 配置一致；堆内实测只用
         * 88–363MB。未设置时 JVM 只会“懒回收”，不会主动把提交量降回来。
         */
        .arg("-Xms64m")
        .arg("-Xmx512m")
        .arg("-Dfile.encoding=UTF-8")
        /*
         * ★ 必须同时设 sun.jnu.encoding：它才是 java.nio.file.Paths 把 String
         * 转成文件名字节时用的字符集。fnOS 的 cmd/main 起进程时不带 locale
         * （LANG 未设 → POSIX/C），JVM 会退到 ANSI_X3.4-1968，于是
         * `Path.of("电影")` 直接抛 "Malformed input or input contains
         * unmappable characters" —— 实测片库目录树里所有中文目录都点不进去。
         * Docker 侧的 JAVA_OPTS 早就设了这一个，fpk 这边漏了。
         */
        .arg("-Dsun.jnu.encoding=UTF-8")
        .arg("-cp")
        // 插件清单里的分号是 Windows 写法；Linux 的 classpath 分隔符是冒号。
        // 加平台判断只为了在 Windows 上能本地验证这条链路，生产路径仍是冒号。
        .arg(format!("apiserver.jar{CLASSPATH_SEP}spider.jar"))
        .arg("ApiServer")
        .arg(format!("--port={}", cfg.api_port))
        .arg(format!("--data-dir={}", cfg.data_dir.display()))
        .env("MEOW_BIND", &cfg.api_bind)
        .env("MEOW_INSTANCE_ID", format!("meow@{}", cfg.pkg_var.display()))
        /*
         * fnOS 的 cmd/main 不设 locale（LANG 未定义 → POSIX/C）。JVM 会把
         * sun.jnu.encoding 推成 ANSI_X3.4-1968，中文文件名直接抛
         * "Malformed input or input contains unmappable characters"。
         * -D 之外再钉一次环境变量：部分 JDK 会忽略 -Dsun.jnu.encoding 而以
         * locale 为准（实测 Java 21 两者都要）。
         */
        .env("LANG", "C.UTF-8")
        .env("LC_ALL", "C.UTF-8")
        /*
         * fnOS 的授权目录只注入到 meowd 这一层，Java 子进程默认读不到。
         * 用户在「应用设置 → 访问权限」里勾的文件夹走 TRIM_DATA_ACCESSIBLE_PATHS，
         * data-share 共享目录走 TRIM_DATA_SHARE_PATHS（都是冒号分隔）。
         * 两个都不传 = 后端只会看到空列表，片库弹窗永远"没有可用目录"。
         */
        .env(
            "MEOW_ALLOWED_PATHS",
            std::env::var("TRIM_DATA_ACCESSIBLE_PATHS").unwrap_or_default(),
        )
        .env(
            "MEOW_SHARE_PATHS",
            std::env::var("TRIM_DATA_SHARE_PATHS").unwrap_or_default(),
        )
        .stdin(Stdio::null())
        .stdout(Stdio::from(out))
        .stderr(Stdio::from(err));

    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        // 独立进程组：停止时按组收，避免 java 拉起的子进程变孤儿
        cmd.as_std_mut().process_group(0);
    }

    Ok(cmd.spawn()?)
}

async fn stop_child(child: &mut Child) {
    let pid = child.id().unwrap_or(0);
    #[cfg(unix)]
    {
        if pid > 0 {
            unsafe {
                libc::kill(-(pid as i32), libc::SIGTERM);
            }
        }
    }
    #[cfg(not(unix))]
    {
        let _ = pid;
        let _ = child.start_kill();
    }

    match tokio::time::timeout(Duration::from_secs(10), child.wait()).await {
        Ok(_) => info!("api backend stopped"),
        Err(_) => {
            #[cfg(unix)]
            {
                if pid > 0 {
                    unsafe {
                        libc::kill(-(pid as i32), libc::SIGKILL);
                    }
                }
            }
            #[cfg(not(unix))]
            {
                let _ = child.kill().await;
            }
            let _ = child.wait().await;
            warn!("api backend killed after 10s timeout");
        }
    }
}
