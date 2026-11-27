//! PC 端插件（sidecar 进程）管理器。
//!
//! 设计见 docs/33-插件系统.md。核心不是"做一个插件"，而是**多个可独立启停的进程**。
//!
//! 这里刻意不引入 reqwest / tokio / tauri-plugin-shell：
//!   - 健康检查只需一个 4 行的 HTTP GET，用 std::net::TcpStream 足够
//!   - 进程管理用 std::process::Command
//!   - 状态用 Mutex + 标准线程
//! 少三个依赖就少一堆构建期联网和版本冲突。
//!
//! ## 关于 "required"
//!
//! 它**只表示"没有它应用就没有后端"这个事实**，不是"不许关"。
//! 用户可以随时停掉 apiserver 并改用自己的远端地址（`meow.api`），
//! 这是合法的使用形态（连 NAS / 连别人的服务器）。所以：
//!   - `required` 不再禁止手动停止
//!   - `required` 也不再强制 autostart，是否启动看用户的 `enabled` 设置
//! 前端只在 required 插件**本应运行却没起来**时提示降级。

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// 插件状态机：STOPPED → STARTING → READY | FAILED
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Phase {
    Stopped,
    Starting,
    Ready,
    Failed,
}

#[derive(Debug, Clone, Serialize)]
pub struct PluginState {
    pub id: String,
    pub name: String,
    /// 没有它就没有后端（apiserver）。**不代表不许关**。
    pub required: bool,
    pub fatal: bool,
    pub phase: Phase,
    /// 实际监听端口（端口协商后的结果）
    pub port: u16,
    /// 给前端的可直接使用的基地址，如 http://127.0.0.1:9979
    pub base: String,
    /// FAILED 时的原因，给用户看
    pub error: Option<String>,
    /// 是否安装（插件目录里是否找得到可执行文件）
    pub installed: bool,
    /// manifest 里声明的版本，便于排查"插件没更新"
    pub version: String,
    /// 用户是否希望它随应用启动（可在设置页切换，持久化到磁盘）
    pub enabled: bool,
    /// 工具型插件（只提供文件，不作为进程启动）
    pub tool: bool,
}

/// manifest.json 的结构（字段语义见 docs/33-插件系统.md）
///
/// ★ `rename_all = "camelCase"` 不能漏：
/// manifest 用的是 `portFallback` / `healthTimeoutMs` / `execFallback` /
/// `dataDir` 这类驼峰键，而 Rust 字段是 snake_case。少了这个属性，
/// 那些字段全部被静默忽略 —— 表现为「portFallback 是空数组，9979 一被占用
/// 就报『备用端口全部被占用』」，且不报任何解析错误，极难排查。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub required: bool,
    #[serde(default)]
    pub fatal: bool,
    #[serde(default)]
    pub version: String,
    pub exec: String,
    #[serde(default)]
    pub exec_fallback: Vec<String>,
    #[serde(default)]
    pub args: Vec<String>,
    #[serde(default)]
    pub cwd: Option<String>,
    /// 工具型插件（如 node 运行时）没有端口，所以这里要 default。
    /// 缺省 0 表示"不监听端口"。
    #[serde(default)]
    pub port: u16,
    #[serde(default)]
    pub port_fallback: Vec<u16>,
    #[serde(default)]
    pub health: String,
    #[serde(default)]
    pub health_expect: String,
    #[serde(default = "default_health_timeout")]
    pub health_timeout_ms: u64,
    #[serde(default = "default_health_interval")]
    pub health_interval_ms: u64,
    #[serde(default)]
    pub data_dir: Option<String>,
    /// 默认是否随应用启动。用户可在设置页覆盖（存 enabled.json）。
    #[serde(default)]
    pub autostart: bool,
    /// 进程环境变量。值里可用 `{port}` / `{dataDir}` / `{appData}` 占位。
    ///
    /// 为什么需要：danmu_api 的端口是通过 `DANMU_API_PORT` **环境变量**读的，
    /// 不是命令行参数。只支持 args 就没法给它协商后的端口。
    #[serde(default)]
    pub env: HashMap<String, String>,
    #[serde(default)]
    pub inject: Option<Inject>,
    /// 这个插件提供什么能力：`backend`（宿主 API）或 `danmaku`（弹幕源）。
    ///
    /// 用它替代"靠 required 判断谁是后端"的旧做法——
    /// required 只表达"没有它就没后端"，而 capability 表达"它是什么"，
    /// 两者语义不同，混用会导致"后端插件不许停"这种错误约束。
    #[serde(default)]
    pub capability: Option<String>,
    /// 工具型插件：只提供文件（运行库/二进制），不是常驻服务。
    /// 装上后不启动、状态保持 Stopped，也不会显示"启动失败"。
    #[serde(default)]
    pub tool: bool,
}

/// 该插件是否是后端（提供 /api/*）。
fn is_backend(m: &Manifest) -> bool {
    match m.capability.as_deref() {
        Some(c) => c == "backend",
        // 没写 capability 时按 id 兜底，兼容老 manifest
        None => m.id == "apiserver",
    }
}

/// 是否是"工具型"插件——只提供文件（如 node.exe），不该作为进程启动。
///
/// 判据：manifest 里 `tool: true`，或 exec 里带 `{script}` 占位
/// （意思是"我得靠别人指定脚本才能跑"，典型就是运行时）。
///
/// 这类插件装上后状态永远是 Stopped，前端不该把它当"启动失败"。
fn is_tool(m: &Manifest) -> bool {
    if m.tool {
        return true;
    }
    m.args.iter().any(|a| a.contains("{script}"))
}

#[derive(Debug, Clone, Deserialize)]
pub struct Inject {
    pub target: String,
    pub key: String,
    pub value: String,
    /// 这个键"归我管"：只在现有值指向**本机插件**（127.0.0.1/localhost）时覆盖。
    ///
    /// 为什么需要（2026-11-27）：drpy 与 pyrt 都 inject `jsRuntime`，而 pyrt
    /// 能同时服务 .py 并转发 .js 给 drpy，所以它该赢。以前这里没有 force，
    /// pyrt 启动时看到 jsRuntime 已是 drpy 写的 `http://127.0.0.1:9330` 就放弃
    /// 覆盖 → 宿主把 .py 源派给 drpy（/health 报 py=false）→ PC 上 .py 源全哑。
    /// fpk 端的 meowd 早已实现同一语义，这里对齐它；用户手配的局域网地址仍保留。
    #[serde(default)]
    pub force: bool,
}

fn default_health_timeout() -> u64 { 45_000 }
fn default_health_interval() -> u64 { 500 }

/// 一个正在运行（或曾经运行）的插件
pub(crate) struct Running {
    manifest: Manifest,
    #[allow(dead_code)]
    dir: PathBuf,
    port: u16,
    child: Option<Child>,
    phase: Phase,
    error: Option<String>,
}

pub struct PluginManager {
    inner: Arc<Mutex<HashMap<String, Running>>>,
    /// plugins 根目录（打包后是资源目录，开发时是仓库 plugins/）
    root: PathBuf,
    /// {appData} 展开结果
    app_data: PathBuf,
    /// 用户对各插件"是否随应用启动"的选择，持久化在 {appData}/enabled.json
    enabled: Arc<Mutex<HashMap<String, bool>>>,
    /// Windows Job Object 句柄。所有 sidecar 都加入同一个 job，
    /// 主进程无论怎么死（含被强杀），句柄关闭时内核会杀掉整个 job。
    #[cfg(windows)]
    job: Option<usize>,
}

impl PluginManager {
    pub fn new(root: PathBuf, app_data: PathBuf) -> Self {
        let enabled = load_enabled(&app_data);
        /*
         * 启动时清一遍孤儿：上一个实例（可能已被安装器替换）留下的 sidecar
         * 会一直占着插件目录，导致后续"升级插件"必然报文件被占用。
         * 此刻本实例还没启动任何插件，所以插件目录下的进程都不可能是我们的。
         */
        if let Ok(rd) = std::fs::read_dir(&root) {
            for e in rd.flatten() {
                let p = e.path();
                if p.is_dir() {
                    kill_orphans_in(&p);
                }
            }
        }
        Self {
            inner: Arc::new(Mutex::new(HashMap::new())),
            root,
            app_data,
            enabled: Arc::new(Mutex::new(enabled)),
            #[cfg(windows)]
            job: create_job(),
        }
    }

    /// 某个插件是否应该随应用启动。
    ///
    /// 优先级：用户的 enabled.json 选择 > manifest 的 autostart 默认值。
    fn is_enabled(&self, manifest: &Manifest) -> bool {
        let g = self.enabled.lock().unwrap();
        *g.get(&manifest.id).unwrap_or(&manifest.autostart)
    }

    /// 切换"随应用启动"并落盘。返回切换后的值。
    pub fn set_enabled(&self, id: &str, on: bool) -> Result<bool, String> {
        let dir = self.root.join(id);
        if !dir.join("manifest.json").exists() {
            return Err(format!("插件 {id} 未安装"));
        }
        {
            let mut g = self.enabled.lock().unwrap();
            g.insert(id.to_string(), on);
            save_enabled(&self.app_data, &g)?;
        }
        Ok(on)
    }

    /// 扫描 plugins/ 下所有 manifest.json，但不启动。
    pub fn discover(&self) -> Vec<PluginState> {
        let mut out = Vec::new();
        let entries = match std::fs::read_dir(&self.root) {
            Ok(e) => e,
            Err(_) => return out,
        };
        /*
         * ★ 目录名过滤（2026-10-31 修"插件列表显示重复"）。
         *
         * 升级插件时会把旧目录改名成 `{id}.bak` 留作回滚，而早期版本把它
         * 放在 **plugins/ 根下**（现在是 `plugins.d/`）。那份残留里同样有
         * 完整的 manifest.json，于是被当成一个独立插件列出来 ——
         * 用户看到两个「影视后端」（0.9.38 + 0.9.39）、两个「drpy 脚本运行时」，
         * 而且都能点「升级/启动」，行为不可预期。
         *
         * 判据放在"是不是目录"之后、"有没有 manifest"之前：
         * 这些名字本来就不该被当成插件，不该依赖里面有没有 manifest。
         */
        let skip = |name: &str| -> bool {
            name.ends_with(".bak")
                || name.ends_with(".tmp")
                || name.ends_with(".old")
                || name.starts_with('.')
                || name == "download-cache"
        };
        for entry in entries.flatten() {
            let dir = entry.path();
            if !dir.is_dir() {
                continue;
            }
            let dir_name = dir.file_name().unwrap_or_default().to_string_lossy().to_string();
            if skip(&dir_name) {
                continue;
            }
            let mf = dir.join("manifest.json");
            if !mf.exists() {
                continue;
            }
            let manifest = match read_manifest(&mf) {
                Ok(m) => m,
                Err(e) => {
                    out.push(PluginState {
                        id: dir.file_name().unwrap_or_default().to_string_lossy().to_string(),
                        name: dir.file_name().unwrap_or_default().to_string_lossy().to_string(),
                        required: false,
                        fatal: false,
                        phase: Phase::Failed,
                        port: 0,
                        base: String::new(),
                        error: Some(format!("manifest 解析失败: {e}")),
                        installed: true,
                        version: String::new(),
                        enabled: false,
                        tool: false,
                    });
                    continue;
                }
            };
            let installed = resolve_exec(&manifest, &dir).is_some();
            let enabled = self.is_enabled(&manifest);
            let (phase, port, error) = {
                let g = self.inner.lock().unwrap();
                match g.get(&manifest.id) {
                    Some(r) => (r.phase, r.port, r.error.clone()),
                    None => (Phase::Stopped, manifest.port, None),
                }
            };
            out.push(PluginState {
                id: manifest.id.clone(),
                name: manifest.name.clone(),
                required: manifest.required,
                fatal: manifest.fatal,
                phase,
                port,
                base: if port > 0 { format!("http://127.0.0.1:{port}") } else { String::new() },
                error: if installed { error } else { Some("未安装".into()) },
                installed,
                version: manifest.version.clone(),
                enabled,
                tool: is_tool(&manifest),
            });
        }
        // 已安装的排前面，其次 required，再按 id
        out.sort_by(|a, b| {
            b.installed
                .cmp(&a.installed)
                .then(b.required.cmp(&a.required))
                .then(a.id.cmp(&b.id))
        });
        out
    }

    /// 启动一个插件（幂等：已 READY 直接返回）。
    pub fn start(&self, id: &str) -> Result<PluginState, String> {
        // 先看有没有在跑
        {
            let g = self.inner.lock().unwrap();
            if let Some(r) = g.get(id) {
                if r.phase == Phase::Ready {
                    return Ok(state_of(r));
                }
            }
        }

        let dir = self.root.join(id);
        let mf = dir.join("manifest.json");
        if !mf.exists() {
            return Err(format!("插件 {id} 未安装（缺少 {}）", mf.display()));
        }
        let manifest = read_manifest(&mf)?;
        // 工具型插件（如 node 运行时、ffmpeg）只提供文件，不是常驻服务。
        // 试图启动它没有意义，直接返回一个"已就绪"的空状态，
        // 避免前端把它显示成"启动失败"。
        //
        // 但**如果它声明了 inject，这里要顺手接线**：ffmpeg 这种插件没有进程，
        // 永远走不到下面那段"健康检查通过后 inject"的逻辑，
        // 结果就是"装好了但后端不知道路径"（实测会静默失效）。
        if is_tool(&manifest) {
            let mut inject_error = None;
            if let Some(inj) = &manifest.inject {
                let target = inj
                    .target
                    .replace("{appData}", &self.app_data.to_string_lossy());
                // {pluginDir} 在 tool 插件里最常用：可执行文件就在插件目录里
                let value = inj
                    .value
                    .replace("{pluginDir}", &dir.to_string_lossy())
                    .replace("{port}", "0");
                if let Err(e) = inject_value(Path::new(&target), &inj.key, &value, inj.force) {
                    inject_error = Some(format!("已安装，但写配置失败: {e}"));
                }
            }
            return Ok(PluginState {
                id: manifest.id.clone(),
                name: manifest.name.clone(),
                required: manifest.required,
                fatal: false,
                phase: Phase::Ready,
                port: 0,
                base: String::new(),
                error: inject_error,
                installed: true,
                version: manifest.version.clone(),
                enabled: self.is_enabled(&manifest),
                tool: true,
            });
        }
        let exe = resolve_exec(&manifest, &dir)
            .ok_or_else(|| format!("找不到可执行文件：{}（也没有可用的 fallback）", manifest.exec))?;

        /*
         * 启动前先清同目录的孤儿。
         *
         * 不清的后果不是"启动失败"而是**更隐蔽的错误**：孤儿占着 9979，
         * `pick_port` 顺延到 9980，新实例和旧进程各跑一个后端，
         * 前端连的是新的、日志里却是两拨请求，端口还会一轮轮往上漂。
         *
         * 只在"当前没有本实例托管的子进程"时才扫，避免把正在启动的自己杀掉。
         */
        {
            let tracked = {
                let g = self.inner.lock().unwrap();
                g.get(id).map(|r| r.child.is_some()).unwrap_or(false)
            };
            if !tracked {
                kill_orphans_in(&dir);
            }
        }

        // 端口协商：主端口被占用就顺延
        let port = pick_port(manifest.port, &manifest.port_fallback)
            .ok_or_else(|| format!("端口 {} 及备用端口全部被占用", manifest.port))?;

        let data_dir = self.resolve_data_dir(&manifest);
        std::fs::create_dir_all(&data_dir).ok();

        let args: Vec<String> = manifest
            .args
            .iter()
            .map(|a| {
                a.replace("{port}", &port.to_string())
                    .replace("{dataDir}", &data_dir.to_string_lossy())
                    .replace("{appData}", &self.app_data.to_string_lossy())
            })
            .collect();

        let work_dir = match &manifest.cwd {
            Some(c) if c != "." => dir.join(c),
            _ => dir.clone(),
        };

        let mut cmd = Command::new(&exe);
        // ★ stderr 落盘（2026-11-26）：以前是 NUL，插件启动失败时一点线索都没有
        //   （pyrt「启动超时」只能靠手跑猜、猜了半小时）。文件在插件目录下 <id>.stderr.log。
        let stderr_sink = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(dir.join(format!("{}.stderr.log", manifest.id)))
            .map(std::process::Stdio::from)
            .unwrap_or_else(|_| std::process::Stdio::null());
        cmd.args(&args)
            .current_dir(&work_dir)
            .stdout(std::process::Stdio::null())
            .stderr(stderr_sink);
        // 环境变量同样支持占位。danmu_api 的端口走 DANMU_API_PORT 而不是命令行。
        for (k, v) in &manifest.env {
            let val = v
                .replace("{port}", &port.to_string())
                .replace("{dataDir}", &data_dir.to_string_lossy())
                .replace("{appData}", &self.app_data.to_string_lossy());
            cmd.env(k, val);
        }
        // Windows 下不弹黑框（走共享实现，别在这里内联 —— 见 proc 模块的注释：
        // 抄一次就只修一处，installer/appupdate 的 curl 就是这么漏掉的）
        crate::proc::quiet(&mut cmd);

        {
            let mut g = self.inner.lock().unwrap();
            g.insert(
                manifest.id.clone(),
                Running {
                    manifest: manifest.clone(),
                    dir: dir.clone(),
                    port,
                    child: None,
                    phase: Phase::Starting,
                    error: None,
                },
            );
        }

        let child = match cmd.spawn() {
            Ok(c) => c,
            Err(e) => {
                let msg = format!("启动失败: {e}");
                let mut g = self.inner.lock().unwrap();
                if let Some(r) = g.get_mut(id) {
                    r.phase = Phase::Failed;
                    r.error = Some(msg.clone());
                }
                return Err(msg);
            }
        };

        // 立刻加入 job：越早越好，防止 spawn 与 assign 之间就被强杀而漏掉
        #[cfg(windows)]
        assign_to_job(self.job, &child);
        #[cfg(not(windows))]
        assign_to_job((), &child);

        {
            let mut g = self.inner.lock().unwrap();
            if let Some(r) = g.get_mut(id) {
                r.child = Some(child);
            }
        }

        // 健康轮询：JVM 启动要几秒，不能启动完立刻发请求
        let healthy = wait_healthy(&manifest, port);
        let mut g = self.inner.lock().unwrap();
        let r = g.get_mut(id).unwrap();
        if healthy {
            r.phase = Phase::Ready;
            r.error = None;
            // 自动接线：把实际地址写进 policy.json，用户零配置
            if let Some(inj) = &manifest.inject {
                let target = inj
                    .target
                    .replace("{appData}", &self.app_data.to_string_lossy());
                let value = inj.value.replace("{port}", &port.to_string());
                if let Err(e) = inject_value(Path::new(&target), &inj.key, &value, inj.force) {
                    r.error = Some(format!("已启动，但写配置失败: {e}"));
                }
            }
            Ok(state_of(r))
        } else {
            // 超时：进程可能还活着但没就绪，标记 FAILED 并给出可读原因
            r.phase = Phase::Failed;
            r.error = Some(format!(
                "启动超时：{} {} 内未通过健康检查",
                port, manifest.health_timeout_ms
            ));
            if let Some(c) = r.child.as_mut() {
                let _ = c.kill();
            }
            Err(r.error.clone().unwrap_or_default())
        }
    }

    /// 结束占用某个插件目录的进程（不属于本实例托管的那些）。
    ///
    /// <p>给「升级插件」用：`stop()` 只能停自己起的子进程，而升级失败最常见的
    /// 原因恰恰是**不是自己起的**旧进程还占着文件（应用被安装器替换、
    /// 或上一轮异常退出留下的）。装之前先扫一次，能省掉一次必然失败。
    pub fn kill_orphans(&self, id: &str) {
        kill_orphans_in(&self.root.join(id));
    }

    pub fn stop(&self, id: &str) -> Result<(), String> {
        let dir = {
            let mut g = self.inner.lock().unwrap();
            let r = g.get_mut(id).ok_or_else(|| format!("插件 {id} 未运行"))?;
            if let Some(c) = r.child.as_mut() {
                let _ = c.kill();
                let _ = c.wait();
            }
            r.child = None;
            r.phase = Phase::Stopped;
            r.error = None;
            r.dir.clone()
        };
        /*
         * 兜底：`Child::kill()` 只杀直接子进程。manifest 若哪天改成批处理/包装器
         * 启动，真正的服务进程会留下来继续占文件。按目录再扫一遍，
         * 代价只有一次进程快照（毫秒级），换的是"停止"真的能停住。
         */
        kill_orphans_in(&dir);
        Ok(())
    }

    /// 应用退出时调用，确保不留孤儿进程。
    pub fn shutdown_all(&self) {
        let mut g = self.inner.lock().unwrap();
        for r in g.values_mut() {
            if let Some(c) = r.child.as_mut() {
                let _ = c.kill();
                let _ = c.wait();
            }
            r.child = None;
            r.phase = Phase::Stopped;
        }
    }

    /// 后端插件（capability=backend）的基地址，前端启动时用它拼 API。
    ///
    /// 不再要求 `required`：用户可能把 apiserver 换成自己的远端地址，
    /// 那时本地没有 ready 的插件，返回 None 让前端走 `meow.api` 或同源。
    pub fn backend_base(&self) -> Option<String> {
        let g = self.inner.lock().unwrap();
        g.values()
            .find(|r| is_backend(&r.manifest) && r.phase == Phase::Ready)
            .map(|r| format!("http://127.0.0.1:{}", r.port))
    }

    /// 重启后端插件，返回 `(旧端口, 新端口)`。
    ///
    /// 顺序固定为 discover → stop → 等端口释放 → start：
    /// - 用 discover 找插件 id，而不是猜 `apiserver`。插件目录名可以改，
    ///   `capability=backend` 才是稳定判据。
    /// - `stop()` 会 kill 子进程并按插件目录扫一遍孤儿；但进程退出后
    ///   端口/TCP 句柄不是瞬间释放，立刻 start 可能被 `pick_port` 判成占用。
    /// - `start()` 内部最长等 45 秒健康检查，调用方必须放独立线程。
    pub fn restart_backend(&self) -> Result<(u16, u16), String> {
        let list = self.discover();
        let id = list
            .iter()
            .find_map(|p| {
                let mf = self.root.join(&p.id).join("manifest.json");
                read_manifest(&mf)
                    .ok()
                    .filter(|m| is_backend(m) && !is_tool(m))
                    .map(|m| m.id)
            })
            .ok_or_else(|| "没有安装后端插件（capability=backend）".to_string())?;

        let old_port = {
            let g = self.inner.lock().unwrap();
            g.get(&id).map(|r| r.port).unwrap_or(0)
        };

        // 没在运行时 stop 会返回「未运行」，这不是错误。
        let _ = self.stop(&id);
        std::thread::sleep(Duration::from_millis(500));

        let st = self.start(&id)?;
        Ok((old_port, st.port))
    }

    fn resolve_data_dir(&self, m: &Manifest) -> PathBuf {
        match &m.data_dir {
            Some(d) => PathBuf::from(
                d.replace("{appData}", &self.app_data.to_string_lossy()),
            ),
            None => self.app_data.join("data"),
        }
    }
}

// ---------------------------------------------------------------- Windows Job Object
//
// Tauri 的 RunEvent::ExitRequested 只在正常退出时触发。用户用任务管理器
// 结束进程、进程崩溃、或 Stop-Process -Force 时不会触发，sidecar 会残留。
// Job Object 是内核级保证：主进程持有的句柄一关闭（进程死亡时 OS 自动关），
// job 内所有进程立即终止。

#[cfg(windows)]
fn create_job() -> Option<usize> {
    use windows_sys::Win32::System::JobObjects::{
        CreateJobObjectW, JobObjectExtendedLimitInformation, SetInformationJobObject,
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };
    unsafe {
        let h = CreateJobObjectW(std::ptr::null(), std::ptr::null());
        if h.is_null() {
            eprintln!("[plugin] CreateJobObject failed");
            return None;
        }
        let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        let ok = SetInformationJobObject(
            h,
            JobObjectExtendedLimitInformation,
            &info as *const _ as *const core::ffi::c_void,
            std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
        );
        if ok == 0 {
            eprintln!("[plugin] SetInformationJobObject failed");
            return None;
        }
        Some(h as usize)
    }
}

#[cfg(windows)]
fn assign_to_job(job: Option<usize>, child: &std::process::Child) {
    use std::os::windows::io::AsRawHandle;
    use windows_sys::Win32::System::JobObjects::AssignProcessToJobObject;
    let Some(job) = job else { return };
    let handle = child.as_raw_handle();
    if handle.is_null() {
        return;
    }
    unsafe {
        if AssignProcessToJobObject(job as _, handle as _) == 0 {
            eprintln!("[plugin] AssignProcessToJobObject failed");
        }
    }
}

#[cfg(not(windows))]
fn assign_to_job(_job: (), _child: &std::process::Child) {}

// ---------------------------------------------------------------- 孤儿进程清理
//
// Job Object 能兜住绝大多数情况（主进程被杀 → 内核杀掉整个 job），但实测
// 出现过：升级应用后旧 apiserver 的 java.exe 仍占着插件目录，点「升级插件」
// 报 `无法移走旧版本（文件被占用）… (os error 32)`，而 `plugin_stop` 也杀不掉它
// ——因为新实例的 `Running.child` 是 None（那不是它起的）。
//
// 所以再加一层：**按可执行文件路径**找出跑在本实例插件目录下的进程并结束。
// 判据用路径而不是进程名（java.exe 满机都是），也不用 PID 文件（PID 会复用）。

/// 判断可执行文件路径是否位于 `dir` 之下。
///
/// <p>必须带**分隔符边界**：只做 `starts_with` 会让 `...\plugins\api` 命中
/// `...\plugins\apiserver\jre\bin\java.exe`，于是升级 apiserver 时把 api 插件杀了。
fn exe_under(exe: &str, dir: &str) -> bool {
    let e = exe.replace('/', "\\").to_lowercase();
    let mut d = dir.replace('/', "\\").to_lowercase();
    if d.is_empty() {
        return false;
    }
    if !d.ends_with('\\') {
        d.push('\\');
    }
    e.starts_with(&d)
}

/// 结束所有可执行文件位于 `dir` 之下的进程（Windows）。
///
/// 只匹配**本实例插件目录**里的可执行文件，因此不会误伤：
/// 用户自己的 java、别的程序、以及另一个 data-dir 的实例。
#[cfg(windows)]
fn kill_orphans_in(dir: &std::path::Path) {
    use windows_sys::Win32::Foundation::{CloseHandle, INVALID_HANDLE_VALUE};
    use windows_sys::Win32::System::Diagnostics::ToolHelp::{
        CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W,
        TH32CS_SNAPPROCESS,
    };
    use windows_sys::Win32::System::Threading::{
        OpenProcess, QueryFullProcessImageNameW, TerminateProcess, PROCESS_QUERY_LIMITED_INFORMATION,
        PROCESS_TERMINATE,
    };

    let want = dir.to_string_lossy().to_string();
    if want.is_empty() {
        return;
    }

    unsafe {
        let snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if snap == INVALID_HANDLE_VALUE || snap.is_null() {
            return;
        }
        let mut entry: PROCESSENTRY32W = std::mem::zeroed();
        entry.dwSize = std::mem::size_of::<PROCESSENTRY32W>() as u32;
        let mut ok = Process32FirstW(snap, &mut entry);
        while ok != 0 {
            let pid = entry.th32ProcessID;
            if pid != 0 {
                let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_TERMINATE, 0, pid);
                if !h.is_null() {
                    let mut buf = [0u16; 32768];
                    let mut len = buf.len() as u32;
                    if QueryFullProcessImageNameW(h, 0, buf.as_mut_ptr(), &mut len) != 0 {
                        let exe = String::from_utf16_lossy(&buf[..len as usize]);
                        if exe_under(&exe, &want) {
                            let _ = TerminateProcess(h, 1);
                            eprintln!("[plugin] 清理孤儿进程 pid={pid} {exe}");
                        }
                    }
                    let _ = CloseHandle(h);
                }
            }
            ok = Process32NextW(snap, &mut entry);
        }
        let _ = CloseHandle(snap);
    }
}

#[cfg(not(windows))]
fn kill_orphans_in(_dir: &std::path::Path) {}

fn state_of(r: &Running) -> PluginState {
    PluginState {
        id: r.manifest.id.clone(),
        name: r.manifest.name.clone(),
        required: r.manifest.required,
        fatal: r.manifest.fatal,
        phase: r.phase,
        port: r.port,
        base: format!("http://127.0.0.1:{}", r.port),
        error: r.error.clone(),
        installed: true,
        version: r.manifest.version.clone(),
        // start() 返回的 state 一律视为"用户想让它在跑"
        enabled: true,
        tool: is_tool(&r.manifest),
    }
}

// ---------------------------------------------------------------- enabled 持久化
//
// 用户对"哪个插件随应用启动"的选择要跨会话保留。
// 存 {appData}/enabled.json，格式 {"apiserver": true, "danmu-api": false}。
// manifest 里的 autostart 只在文件里没有该键时作为默认值。

fn enabled_file(app_data: &Path) -> PathBuf {
    app_data.join("enabled.json")
}

fn load_enabled(app_data: &Path) -> HashMap<String, bool> {
    let p = enabled_file(app_data);
    match std::fs::read_to_string(&p) {
        Ok(t) if !t.trim().is_empty() => serde_json::from_str(&t).unwrap_or_default(),
        _ => HashMap::new(),
    }
}

fn save_enabled(app_data: &Path, map: &HashMap<String, bool>) -> Result<(), String> {
    std::fs::create_dir_all(app_data).ok();
    let p = enabled_file(app_data);
    let out = serde_json::to_string_pretty(map).map_err(|e| e.to_string())?;
    let tmp = p.with_extension("json.tmp");
    std::fs::write(&tmp, out).map_err(|e| e.to_string())?;
    std::fs::rename(&tmp, &p).map_err(|e| e.to_string())?;
    Ok(())
}

fn read_manifest(path: &Path) -> Result<Manifest, String> {
    let txt = std::fs::read_to_string(path).map_err(|e| e.to_string())?;
    serde_json::from_str(&txt).map_err(|e| e.to_string())
}

/// 解析可执行文件。
///
/// **顺序很重要**：先在插件目录里找（含所有 fallback），最后才退回系统 PATH。
///
/// 踩过的坑：danmu-api 的 `exec: "node"` 会先在插件目录找 `node`/`node.exe`
/// （不存在），然后旧逻辑立刻退回 PATH，命中了**系统安装的 node**，
/// 于是我们随插件下载的 `plugins/node/node.exe` 永远用不上——
/// 用户机器上没装 node 时会直接启动失败，装了的机器则"看起来正常"但走了错误的解释器。
///
/// 另外 Windows 上 PathBuf 认正斜杠，但**不会自动补 `.exe`**，
/// 直接 `is_file()` 会永远 false → installed=false → 插件永远不启动。
///
/// 现在改成：
///   1. exec 在插件目录内（自动补 .exe/.bat/.cmd）
///   2. execFallback 逐个在插件目录内（支持 `../node/node.exe` 跨插件引用）
///   3. 全都找不到，才在 PATH 里找 exec 和 fallback 的裸命令名
fn resolve_exec(m: &Manifest, dir: &Path) -> Option<PathBuf> {
    // 阶段 1：插件目录内（含 fallback），**不查 PATH**
    if let Some(p) = look_local(dir, &m.exec) {
        return Some(p);
    }
    for f in &m.exec_fallback {
        if let Some(p) = look_local(dir, f) {
            return Some(p);
        }
    }
    // 阶段 2：退回 PATH（只对裸命令名生效，如系统 java/node）
    for cand in std::iter::once(&m.exec).chain(m.exec_fallback.iter()) {
        if !cand.contains('/') && !cand.contains('\\') {
            if let Some(p) = which(cand) {
                return Some(p);
            }
        }
    }
    None
}

/// 只在插件目录（及其相对路径）里找，**绝不查 PATH**。
fn look_local(dir: &Path, rel: &str) -> Option<PathBuf> {
    let raw = dir.join(rel);
    if raw.is_file() {
        return Some(raw);
    }
    // 已带扩展名就不重复追加
    if Path::new(rel).extension().is_none() {
        for ext in ["exe", "bat", "cmd"] {
            let with_ext = raw.with_extension(ext);
            if with_ext.is_file() {
                return Some(with_ext);
            }
        }
    }
    None
}

/// 极简 which：在 PATH 里找命令
fn which(cmd: &str) -> Option<PathBuf> {
    if cmd.contains('/') || cmd.contains('\\') {
        return None;
    }
    let path = std::env::var_os("PATH")?;
    for dir in std::env::split_paths(&path) {
        for ext in ["", ".exe", ".bat", ".cmd"] {
            let cand = dir.join(format!("{cmd}{ext}"));
            if cand.is_file() {
                return Some(cand);
            }
        }
    }
    None
}

/// 端口协商：主端口空闲就用它，否则按 fallback 顺序找第一个空闲的。
fn pick_port(primary: u16, fallback: &[u16]) -> Option<u16> {
    let mut cands = vec![primary];
    cands.extend_from_slice(fallback);
    for p in cands {
        if TcpListener::bind(("127.0.0.1", p)).is_ok() {
            return Some(p);
        }
    }
    None
}

/// 轮询健康检查直到超时。
fn wait_healthy(m: &Manifest, port: u16) -> bool {
    if m.health.is_empty() {
        // 没有健康端点：给一个固定的暖机时间
        std::thread::sleep(Duration::from_millis(1500));
        return true;
    }
    let deadline = Instant::now() + Duration::from_millis(m.health_timeout_ms);
    let interval = Duration::from_millis(m.health_interval_ms.max(100));
    while Instant::now() < deadline {
        if let Some(body) = http_get(port, &m.health, 1200) {
            if m.health_expect.is_empty() || body.contains(&m.health_expect) {
                return true;
            }
        }
        std::thread::sleep(interval);
    }
    false
}

/// 手写 HTTP/1.1 GET（只用于本地健康检查，够用且零依赖）。
fn http_get(port: u16, path: &str, timeout_ms: u64) -> Option<String> {
    let addr = format!("127.0.0.1:{port}");
    let mut s = TcpStream::connect_timeout(&addr.parse().ok()?, Duration::from_millis(timeout_ms)).ok()?;
    s.set_read_timeout(Some(Duration::from_millis(timeout_ms))).ok()?;
    let p = if path.starts_with('/') { path.to_string() } else { format!("/{path}") };
    let req = format!("GET {p} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n");
    s.write_all(req.as_bytes()).ok()?;
    let mut buf = Vec::new();
    s.read_to_end(&mut buf).ok()?;
    let txt = String::from_utf8_lossy(&buf).to_string();
    // 只看 body（\r\n\r\n 之后）
    Some(match txt.find("\r\n\r\n") {
        Some(i) => txt[i + 4..].to_string(),
        None => txt,
    })
}

/// 把 key=value 写进 policy.json（读-改-写，保留其他字段）。
///
/// **默认不覆盖用户手填的值**。`inject` 的语义是"插件启动后自动接线，让用户零配置"，
/// 不是"强制把地址改成我这里"。如果用户已经在设置页填了远端 danmu_api
/// （比如 NAS 上的），插件启动时把它改成 127.0.0.1 就是灾难。
///
/// 例外一：值等于当前插件地址时允许更新（端口协商后可能从 9321 变 9322）。
/// 例外二：manifest 声明 `force: true` 且现有值指向**另一个本机插件**
/// （127.0.0.1 / localhost）时覆盖 —— 用于 pyrt 接管 drpy 写下的 `jsRuntime`。
/// 用户手配的局域网地址（192.168.x / 域名）永远不覆盖。
fn inject_value(target: &Path, key: &str, value: &str, force: bool) -> Result<(), String> {
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent).ok();
    }
    let mut obj: serde_json::Map<String, serde_json::Value> = if target.exists() {
        match std::fs::read_to_string(target) {
            Ok(t) if !t.trim().is_empty() => serde_json::from_str(&t).unwrap_or_default(),
            _ => serde_json::Map::new(),
        }
    } else {
        serde_json::Map::new()
    };

    if let Some(existing) = obj.get(key).and_then(|v| v.as_str()) {
        let existing = existing.trim();
        if !existing.is_empty() && existing != value {
            // 已有非空值且不是我们自己写的：默认尊重用户配置，不覆盖
            let is_local_plugin = existing.starts_with("http://127.0.0.1:")
                || existing.starts_with("http://localhost:")
                || existing.starts_with("https://127.0.0.1:")
                || existing.starts_with("https://localhost:");
            if !(force && is_local_plugin) {
                return Ok(());
            }
        }
    }

    obj.insert(key.to_string(), serde_json::Value::String(value.to_string()));
    let out = serde_json::to_string_pretty(&obj).map_err(|e| e.to_string())?;
    // 原子写：先写 .tmp 再 rename，避免半截文件
    let tmp = target.with_extension("json.tmp");
    std::fs::write(&tmp, out).map_err(|e| e.to_string())?;
    std::fs::rename(&tmp, target).map_err(|e| e.to_string())?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{exe_under, inject_value};
    use std::path::PathBuf;

    /// 注入语义（2026-11-28 中期分层后）：
    ///   · force 只覆盖**本机插件**留下的地址（127.0.0.1 / localhost）
    ///   · 用户手配的局域网/NAS 地址永远保留
    ///   · 写 pyRuntimeAddr 不能碰 jsRuntime（两个引擎两个键）
    fn tmp_policy(tag: &str, body: &str) -> PathBuf {
        let mut p = std::env::temp_dir();
        let uniq = format!(
            "meow-inject-{}-{}-{}.json",
            tag,
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0)
        );
        p.push(uniq);
        std::fs::write(&p, body).unwrap();
        p
    }

    fn read_json(p: &PathBuf) -> serde_json::Value {
        serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap()
    }

    #[test]
    fn inject_force_overwrites_local_plugin_only() {
        // pyrt 抢 pyRuntimeAddr：旧值是另一个本机插件（drpy 的 9330）→ 覆盖
        let f = tmp_policy(
            "force-local",
            r#"{"jsRuntime":"http://127.0.0.1:9330","pyRuntimeAddr":"http://127.0.0.1:19331"}"#,
        );
        inject_value(&f, "pyRuntimeAddr", "http://127.0.0.1:19400", true).unwrap();
        let v = read_json(&f);
        assert_eq!(v["pyRuntimeAddr"], "http://127.0.0.1:19400");
        // 另一个键必须原样不动
        assert_eq!(v["jsRuntime"], "http://127.0.0.1:9330");

        // 用户手配的 NAS 地址 → 不覆盖（force 也不例外）
        let f2 = tmp_policy(
            "force-lan",
            r#"{"pyRuntimeAddr":"http://192.168.1.20:19331"}"#,
        );
        inject_value(&f2, "pyRuntimeAddr", "http://127.0.0.1:19400", true).unwrap();
        let v2 = read_json(&f2);
        assert_eq!(v2["pyRuntimeAddr"], "http://192.168.1.20:19331");

        // 无 force 时不覆盖任何非空旧值（drpy 以前就是这个行为）
        let f3 = tmp_policy("noforce", r#"{"jsRuntime":"http://127.0.0.1:19331"}"#);
        inject_value(&f3, "jsRuntime", "http://127.0.0.1:9330", false).unwrap();
        let v3 = read_json(&f3);
        assert_eq!(v3["jsRuntime"], "http://127.0.0.1:19331");
    }

    /// 孤儿进程判定的边界：只认"目录 + 分隔符"前缀。
    ///
    /// 踩过的风险：写成裸 `starts_with`，升级 `apiserver` 时会把
    /// `plugins\api` 这个插件的进程一起杀掉（两个插件名前缀相同）。
    #[test]
    fn exe_under_requires_separator_boundary() {
        let dir = r"C:\Users\me\AppData\Roaming\com.meow.player\plugins\apiserver";
        assert!(exe_under(
            r"C:\Users\me\AppData\Roaming\com.meow.player\plugins\apiserver\jre\bin\java.exe",
            dir
        ));
        // 前缀相同但不是一个插件目录
        assert!(!exe_under(
            r"C:\Users\me\AppData\Roaming\com.meow.player\plugins\apiserver2\jre\bin\java.exe",
            dir
        ));
        // 兄弟插件
        assert!(!exe_under(
            r"C:\Users\me\AppData\Roaming\com.meow.player\plugins\api\jre\bin\java.exe",
            dir
        ));
        // 完全无关
        assert!(!exe_under(r"C:\Windows\System32\notepad.exe", dir));
    }

    #[test]
    fn exe_under_is_case_and_separator_insensitive() {
        let dir = "C:/Users/ME/app/plugins/apiserver";
        assert!(exe_under(
            r"c:\users\me\app\plugins\APISERVER\jre\bin\java.exe",
            dir
        ));
    }

    #[test]
    fn exe_under_rejects_empty_dir() {
        assert!(!exe_under(r"C:\x\java.exe", ""));
    }
}
