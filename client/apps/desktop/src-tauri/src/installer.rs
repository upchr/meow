//! 插件下载、校验、安装、升级、回滚。
//!
//! ## 为什么不引 reqwest
//!
//! 用系统自带的 `curl.exe`（Win10 1803+ 自带 8.21）和 `tar.exe`
//! （bsdtar 3.8.9，**实测能读写 zip**）。这样零新增依赖：
//!   - reqwest 虽在 Cargo.lock 里（tauri 传递依赖），但要拉 tokio 异步栈，
//!     且 target/ 下没编译过，加进来要重新编译几十个 crate
//!   - SHA256 用 sha2（本地已缓存源码，已在依赖树）
//!
//! ## 目录布局
//!
//! ```text
//! %APPDATA%/com.meow.player/
//! ├── plugins/                    ← 生效目录（PluginManager 读这里）
//! │   ├── apiserver/
//! │   └── danmu-api/
//! ├── plugins.d/                  ← 暂存与回滚
//! │   ├── <id>-<ver>.tmp/         ← 解压中
//! │   ├── <id>.bak/               ← 升级前的旧版（回滚用）
//! │   └── download-cache/         ← zip 缓存（支持断点续传）
//! └── enabled.json
//! ```
//!
//! ## 为什么用 rename 而不是复制
//!
//! 同盘 `rename` 是原子的：要么完成，要么没发生。
//! 复制中途断电会留下半截目录，下次启动会加载到损坏的插件。
//! 整个升级流程是「解压到 tmp → 校验 → rename 旧版为 bak → rename tmp 为正式」，
//! 任何一步失败都能回滚。

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::io::BufRead;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

// ---------------------------------------------------------------- 索引结构

/// 远端插件源清单（packages.json）。
///
/// 格式（顶层 key = 插件 id）：
/// ```json
/// {
///   "schema_version": 1,
///   "author": { "name": "...", "url": "..." },
///   "packages": {
///     "apiserver": {
///       "display_name": "影视后端",
///       "version": "0.6.2",
///       "desc": "...",
///       "size": 38843935,
///       "sha256": "...",
///       "url": "apiserver-0.6.2.zip",
///       "requires": [],
///       "hidden": false
///     }
///   }
/// }
/// ```
///
/// 为什么用「顶层 key = id」而不是数组：用户手写自定义源时一眼能看出装了哪个，
/// 改起来不容易错位；数组方案漏个括号整份配置就废了。
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Index {
    #[serde(default = "default_api_version")]
    pub schema_version: u32,
    #[serde(default)]
    pub generated_at: String,
    #[serde(default)]
    pub author: Option<AuthorInfo>,
    #[serde(default)]
    pub min_app_version: String,
    /// 插件 id → 包信息
    #[serde(default)]
    pub packages: std::collections::BTreeMap<String, PackageInfo>,
}

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct AuthorInfo {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub url: String,
}

/// 单个插件包。id 来自 packages 的 key，不在结构里重复。
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct PackageInfo {
    #[serde(default)]
    pub display_name: String,
    #[serde(default)]
    pub version: String,
    #[serde(default)]
    pub platform: Vec<String>,
    #[serde(default)]
    pub desc: String,
    #[serde(default)]
    pub author: String,
    #[serde(default)]
    pub homepage: String,
    #[serde(default)]
    pub size: u64,
    #[serde(default)]
    pub sha256: String,
    #[serde(default)]
    pub url: String,
    /// 依赖的其他插件 id（danmu-api 依赖 node）。
    /// 用 `de_vec_or_null` 容错：PowerShell 的 ConvertTo-Json 会把空数组
    /// 写成 `null`，`#[serde(default)]` 对 `null` 不生效。
    #[serde(default, deserialize_with = "de_vec_or_null")]
    pub requires: Vec<String>,
    #[serde(default)]
    pub hidden: bool,
    #[serde(default)]
    pub lasttime: String,
    #[serde(default = "default_api_version")]
    pub api_version: u32,
    #[serde(default)]
    pub min_app_version: String,
}

impl PackageInfo {
    /// 展示名兜底：没写 display_name 就用 id
    pub fn label(&self, id: &str) -> String {
        if self.display_name.is_empty() {
            id.to_string()
        } else {
            self.display_name.clone()
        }
    }
}

/// 把 `null` 当成空 Vec（兼容 PowerShell 生成的清单）。
fn de_vec_or_null<'de, D>(d: D) -> Result<Vec<String>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let opt = Option::<Vec<String>>::deserialize(d)?;
    Ok(opt.unwrap_or_default())
}

fn default_api_version() -> u32 {
    1
}

/// 一个插件源条目
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct SourceItem {
    pub id: String,
    pub name: String,
    pub url: String,
}

/// 插件源列表（含当前选中）
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct SourceList {
    pub current: String,
    pub items: Vec<SourceItem>,
}

impl Default for SourceList {
    /// 默认**不预置任何源**。
    ///
    /// 为什么不是填一个"官方源"：如果那个地址不存在（或还没上线），
    /// 用户首次打开设置页就会看到"拉取插件列表失败: 404"的红色报错，
    /// 像是应用坏了。空列表 + 明确引导（"点添加填插件源"）更诚实。
    fn default() -> Self {
        Self {
            current: String::new(),
            items: Vec::new(),
        }
    }
}

/// 规范化源地址：校验协议、按需补斜杠、保持 .json 原样。
fn normalize_source(url: &str) -> Result<String, String> {
    let t = url.trim();
    if t.is_empty() {
        return Err("地址不能为空".into());
    }
    // file:// 用于"导入本地清单文本"这条路径
    if t.starts_with("file://") {
        return Ok(t.to_string());
    }
    if !t.starts_with("http://") && !t.starts_with("https://") {
        return Err("必须以 http:// 或 https:// 开头".into());
    }
    if t.ends_with(".json") {
        // 指向具体清单文件时不补斜杠，否则变成 xxx.json/index.json
        Ok(t.to_string())
    } else if is_github_release_endpoint(t) {
        // ★ release 端点也不能补斜杠：GitHub 的 `/releases/latest/`
        //   （带尾斜杠）直接 404。踩过：补斜杠后表现为
        //   "插件源填对了却拉不到清单"，而错误信息只说 4xx。
        Ok(t.trim_end_matches('/').to_string())
    } else if t.ends_with('/') {
        Ok(t.to_string())
    } else {
        Ok(format!("{t}/"))
    }
}

/// 规范化后的源地址 → 候选清单地址（按顺序尝试，取第一个能取到的）。
///
/// 只给**目录**地址时不知道里面叫什么，所以按约定顺序试：
/// 1. `packages.json` —— 当前标准名
/// 2. `index.json`   —— 早期别名，保留兼容（旧地址、旧记录都指向它）
///
/// 不硬编码只认一个：改名后用户手里那些 `http://host:8099/` 形式的源
/// 还要能继续工作。
fn manifest_url_candidates(src: &str) -> Vec<String> {
    if src.ends_with(".json") {
        return vec![src.to_string()];
    }
    let base = if src.ends_with('/') {
        src.to_string()
    } else {
        format!("{src}/")
    };
    vec![format!("{base}packages.json"), format!("{base}index.json")]
}

/// 是否是允许挂公共代理的公开 GitHub 地址。
///
/// <p>只覆盖 raw / release 资产；`api.github.com` **不在内**——清单/资产走 API
/// 端点时通常带 token，公共代理不会转发 Authorization（实测 403/404）。
fn is_public_github_url(url: &str) -> bool {
    let u = url.to_ascii_lowercase();
    if u.starts_with("https://raw.githubusercontent.com/")
        || u.starts_with("https://gist.githubusercontent.com/")
    {
        return true;
    }
    if u.starts_with("https://github.com/") {
        return u.contains("/releases/download/") || u.contains("/releases/latest/download/");
    }
    // 公开仓的 API（匿名）也可以走代理；私有仓在 expand 里已因 token 非空短路
    if u.starts_with("https://api.github.com/") {
        return u.contains("/releases/");
    }
    false
}

fn apply_proxy_prefix(prefix: &str, url: &str) -> Option<String> {
    let p = prefix.trim();
    if !p.starts_with("https://") || p.chars().any(|c| c.is_whitespace()) {
        return None;
    }
    if p.contains("{url}") {
        return Some(p.replace("{url}", url));
    }
    if p.ends_with('/') {
        Some(format!("{p}{url}"))
    } else {
        Some(format!("{p}/{url}"))
    }
}

/// 公开地址 → 直连 + 代理候选；私有/非 GitHub 原样返回。
fn expand_public_candidates(url: &str, proxies: &[String]) -> Vec<String> {
    // ★ 不要再判 source_token()（2026-11-27）：公开库根本不需要 token，但历史上
    //   只要 token 文件存在这里就「只走直连」→ 直连 objects.githubusercontent.com
    //   一抖动就整条失败（curl 35，实测连 drpy/apiserver 能下、node/pyrt 挂）。
    //   token 的边界由 download_one 里的 token_allowed_for 保证：只发给 GitHub
    //   自己的域名，代理候选天然不带凭证；私有源的直连（带 token）排第一，成功即停。
    if !is_public_github_url(url) {
        return vec![url.to_string()];
    }
    let mut out = vec![url.to_string()];
    let mut seen: std::collections::HashSet<String> = out.iter().cloned().collect();
    for p in proxies {
        if let Some(u) = apply_proxy_prefix(p, url) {
            if seen.insert(u.clone()) {
                out.push(u);
            }
        }
    }
    out
}

/// 是不是 GitHub 的 release 端点（`.../releases/latest` 或 `.../releases/tags/<tag>`）。
///
/// <p>这类地址返回的是**描述 release 的 JSON**（资产列表），不是清单本身。
/// 清单是其中一个叫 `packages.json` 的资产，要再取一层。
///
/// <p><b>为什么要支持它</b>：清单改挂 Release 资产（不进 git）后，
/// 客户端/安装器填的就是这个 repo 级固定地址——地址里没有 asset id，
/// 所以配一次永远不用改。插件安装器和应用更新必须认同一种地址。
fn is_github_release_endpoint(u: &str) -> bool {
    let s = u.trim().trim_end_matches('/');
    let lower = s.to_ascii_lowercase();
    if !lower.starts_with("https://api.github.com/repos/") {
        return false;
    }
    if let Some(pos) = lower.rfind("/releases/") {
        let tail = &lower[pos + "/releases/".len()..];
        return tail == "latest" || tail.starts_with("tags/");
    }
    false
}

/// 从 release JSON 里找出清单资产的下载地址。
fn manifest_asset_from(release_json: &str, prefer_public: bool) -> Option<(String, String)> {
    // 返回 (asset_api_url, repo)
    let v: serde_json::Value = serde_json::from_str(release_json).ok()?;
    let repo = v
        .get("url")
        .and_then(|x| x.as_str())
        .and_then(|u| {
            let m = u.find("/repos/")?;
            let rest = &u[m + "/repos/".len()..];
            let end = rest.find("/releases/")?;
            Some(rest[..end].to_string())
        })?;
    let assets = v.get("assets")?.as_array()?;
    for a in assets {
        if a.get("name").and_then(|x| x.as_str()) == Some("packages.json") {
            // 公开仓（无 token）优先标准下载链接，这样前端/下载器的代理才会生效；
            // 私有仓必须走 API 资产端点（标准链接带 token 也 404，实测）。
            if prefer_public {
                if let Some(u) = a.get("browser_download_url").and_then(|x| x.as_str()) {
                    return Some((u.to_string(), repo));
                }
            }
            if let Some(id) = a.get("id").and_then(|x| x.as_u64()) {
                return Some((
                    format!("https://api.github.com/repos/{repo}/releases/assets/{id}"),
                    repo,
                ));
            }
            if let Some(u) = a.get("browser_download_url").and_then(|x| x.as_str()) {
                return Some((u.to_string(), repo));
            }
        }
    }
    None
}

/// 把 GitHub 标准下载链接换成 API 资产端点（私有仓必需）。
///
/// <p><b>实测事实</b>（2026-10-29，仓库 upchr/meow 为 private）：
///
/// | 地址 | 匿名 | 带 token |
/// |---|---|---|
/// | `github.com/.../releases/download/<tag>/<file>` | 404 | **404** |
/// | `api.github.com/repos/.../releases/assets/<id>` | 404 | **200** |
///
/// 也就是标准链接对私有仓**根本不工作**，带 token 也一样。
/// 所以要先用 token 查一次该 tag 的资产列表拿到 id。
///
/// <p>查不到（公开仓、token 无权、网络抖动）就返回 None，调用方继续用原地址——
/// 公开仓原地址本来就能下，不能因为这次查询失败而阻断。
fn github_asset_api_url(std_url: &str, token: &str) -> Option<String> {
    let rest = std_url.strip_prefix("https://github.com/")?;
    // <owner>/<repo>/releases/download/<tag>/<file...>
    let mut it = rest.splitn(5, '/');
    let owner = it.next()?;
    let repo = it.next()?;
    if it.next()? != "releases" || it.next()? != "download" {
        return None;
    }
    let tail = it.next()?;
    let (tag, file) = tail.split_once('/')?;

    let api = format!("https://api.github.com/repos/{owner}/{repo}/releases/tags/{tag}");
    let body = http_get_text_with(&api, token).ok()?;
    let v: serde_json::Value = serde_json::from_str(&body).ok()?;
    let assets = v.get("assets")?.as_array()?;
    for a in assets {
        if a.get("name").and_then(|x| x.as_str()) == Some(file) {
            let id = a.get("id").and_then(|x| x.as_u64())?;
            return Some(format!(
                "https://api.github.com/repos/{owner}/{repo}/releases/assets/{id}"
            ));
        }
    }
    None
}

/// 带重试的 HTTP GET。
///
/// <p><b>为什么必须重试</b>：实测本机到 `api.github.com` 的 TLS 握手
/// 抖动频繁（`curl: (35) schannel: failed to receive handshake`），
/// 同一请求连试 5 次才通是常态。没有重试时表现为"插件源时好时坏"，
/// 用户会以为是源的问题。
fn http_get_text_retry(url: &str) -> Result<String, String> {
    let mut last = String::new();
    // 3 次而不是 5 次：外层已有"候选地址 + 索引缓存"两层保护，
    // 单地址再叠 5 次重试会让最坏情况变成 2×5=10 个子进程（用户实测卡顿）。
    const TRIES: u32 = 3;
    for i in 1..=TRIES {
        match http_get_text(url) {
            Ok(t) => return Ok(t),
            Err(e) => {
                last = e;
                if i < TRIES {
                    // 退避：网络拥塞时等长间隔不如递增更容易躲过
                    std::thread::sleep(std::time::Duration::from_secs(i as u64));
                }
            }
        }
    }
    Err(last)
}

/// 取清单文本：按候选顺序拉，第一个成功的返回。
fn fetch_manifest_text(src: &str, proxies: &[String]) -> Result<String, String> {
    let public = source_token().trim().is_empty();

    // ★ GitHub release 端点：先取 release JSON，再取里面 packages.json 资产
    if is_github_release_endpoint(src) {
        let rel = {
            let mut last = String::new();
            let mut ok = None;
            for u in expand_public_candidates(src, proxies) {
                match http_get_text_retry(&u) {
                    Ok(t) => { ok = Some(t); break; }
                    Err(e) => last = e,
                }
            }
            ok.ok_or_else(|| format!("拉取 release 信息失败（{src}）：{last}"))?
        };
        let (asset_url, _repo) = manifest_asset_from(&rel, public).ok_or_else(|| {
            format!("该 release 里没有 packages.json 资产（发版时 sync-packages 应上传它）：{src}")
        })?;
        let mut last = String::new();
        for u in expand_public_candidates(&asset_url, proxies) {
            match http_get_text_retry(&u) {
                Ok(t) => return Ok(t),
                Err(e) => last = e,
            }
        }
        return Err(format!("拉取 packages.json 资产失败：{last}"));
    }

    let cands = manifest_url_candidates(src);
    let mut last_err = String::new();
    for base in cands.iter() {
        for url in expand_public_candidates(base, proxies) {
            match http_get_text_retry(&url) {
                Ok(t) => return Ok(t),
                Err(e) => {
                    last_err = if cands.len() > 1 {
                        format!("拉取清单失败（试过 {}）：{e}", cands.join(" / "))
                    } else {
                        format!("拉取清单失败：{e}")
                    };
                }
            }
        }
    }
    Err(last_err)
}

/// 从 URL 里取 host 作为默认显示名
fn host_of(url: &str) -> String {
    let rest = url
        .trim_start_matches("https://")
        .trim_start_matches("http://")
        .trim_start_matches("file://");
    rest.split('/').next().unwrap_or(url).to_string()
}

// ---------------------------------------------------------------- 进度

#[derive(Debug, Clone, Serialize, Default)]
pub struct Progress {
    pub id: String,
    /// idle | downloading | verifying | extracting | installing | done | failed | cancelled
    pub stage: String,
    /// 0-100；未知总量时为 -1
    pub percent: f64,
    pub received: u64,
    pub total: u64,
    pub message: String,
}

/// 下载中的任务，可被取消。
struct Active {
    cancel: Arc<AtomicBool>,
    child: Option<Child>,
}

pub struct Installer {
    /// plugins/ 生效目录
    plugins_dir: PathBuf,
    /// plugins.d/ 暂存与回滚
    staging_dir: PathBuf,
    app_data: PathBuf,
    /// 当前选中的插件源（规范化后的地址）
    source: Arc<Mutex<String>>,
    /// 全部插件源（持久化到 plugin-sources.json）
    sources: Arc<Mutex<SourceList>>,
    /// 当前任务
    active: Arc<Mutex<Option<Active>>>,
    /// 最近一次进度（前端轮询）
    progress: Arc<Mutex<Progress>>,
    /// 已安装记录
    installed: Arc<Mutex<InstalledMap>>,
    /// 索引结果缓存（成功或失败都缓存，见 index_cache 的注释）
    index_cache: Arc<Mutex<Option<IndexCache>>>,
}

/// 索引缓存条目。
///
/// <p><b>为什么成功和失败都要缓存</b>：`SettingsView.onMounted` 每次都调
/// `loadIndex()`，而一次失败的拉取会依次试 2 个候选地址 × 每个重试若干次
/// —— 每次进设置页都重新付出这个代价（实测用户原话："一进设置页都检查更新，
/// 导致命令行窗口弹出好几次检查，容易卡死"）。
///
/// <p>缓存的语义是"这个源在最近 N 秒内是这个结果"：
/// - 成功缓存 60 秒：用户切走再切回来不会重复拉
/// - 失败缓存 20 秒：短一些，因为网络抖动恢复得快；但仍能挡住
///   "设置页 ↔ 插件页来回点"造成的连续重试
struct IndexCache {
    at: std::time::Instant,
    ok: bool,
    body: Result<Index, String>,
}

/// 记录了"装了什么版本"，用于判断有无更新。
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
pub struct InstalledMap {
    #[serde(flatten)]
    pub entries: std::collections::BTreeMap<String, InstalledInfo>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct InstalledInfo {
    pub version: String,
    pub sha256: String,
    #[serde(rename = "installedAt", default)]
    pub installed_at: String,
}

impl Installer {
    pub fn new(plugins_dir: PathBuf, app_data: PathBuf) -> Self {
        let staging_dir = app_data.join("plugins.d");
        std::fs::create_dir_all(&staging_dir).ok();
        let installed = load_installed(&staging_dir);
        // 恢复上次的源列表；没有就用默认
        let sources = load_sources(&app_data);
        // 没有可用源时留空字符串（不是编一个假地址），
        // fetch_index 会据此给出"请先添加插件源"的可操作提示。
        let current_url = sources
            .items
            .iter()
            .find(|s| s.id == sources.current)
            .map(|s| s.url.clone())
            .unwrap_or_default();
        Self {
            plugins_dir,
            staging_dir,
            app_data,
            source: Arc::new(Mutex::new(current_url)),
            sources: Arc::new(Mutex::new(sources)),
            active: Arc::new(Mutex::new(None)),
            progress: Arc::new(Mutex::new(Progress::default())),
            installed: Arc::new(Mutex::new(installed)),
            index_cache: Arc::new(Mutex::new(None)),
        }
    }

    /// 全局 GitHub 加速前缀（由设置页写入 git-proxies.json）。
    /// 只接受 https://；坏数据直接忽略，不让一个手改文件把下载打死。
    fn git_proxies(&self) -> Vec<String> {
        let p = self.app_data.join("git-proxies.json");
        let Ok(text) = std::fs::read_to_string(p) else {
            return Vec::new();
        };
        let Ok(list) = serde_json::from_str::<Vec<String>>(text.trim_start_matches('\u{feff}')) else {
            return Vec::new();
        };
        list.into_iter()
            .map(|x| x.trim().to_string())
            .filter(|x| x.starts_with("https://") && !x.chars().any(|c| c.is_whitespace()))
            .collect()
    }

    /// 下载候选：公开 GitHub 地址 → 直连 + 代理；私有/其它源原样。
    fn download_candidates(&self, url: &str) -> Vec<String> {
        expand_public_candidates(url, &self.git_proxies())
    }

    pub fn source(&self) -> String {
        self.source.lock().unwrap().clone()
    }

    /// 全部插件源（含当前选中项）
    pub fn sources(&self) -> SourceList {
        self.sources.lock().unwrap().clone()
    }

    /// 新增一个源并设为当前。
    pub fn add_source(&self, url: &str, name: &str) -> Result<SourceList, String> {
        let normalized = normalize_source(url)?;
        let mut g = self.sources.lock().unwrap();
        // 已存在同地址：只切换，不重复添加
        if let Some(existing) = g.items.iter().find(|s| s.url == normalized).cloned() {
            g.current = existing.id.clone();
        } else {
            let id = format!("src-{}", g.items.len() + 1);
            g.items.push(SourceItem {
                id: id.clone(),
                name: if name.trim().is_empty() {
                    host_of(&normalized)
                } else {
                    name.trim().to_string()
                },
                url: normalized,
            });
            g.current = id;
        }
        let snapshot = g.clone();
        drop(g);
        self.persist_sources(&snapshot)?;
        *self.source.lock().unwrap() = snapshot
            .items
            .iter()
            .find(|s| s.id == snapshot.current)
            .map(|s| s.url.clone())
            .unwrap_or_default();
        Ok(snapshot)
    }

    /// 修改某项（改地址或改名）。改地址会重新规范化。
    pub fn update_source(&self, id: &str, url: &str, name: &str) -> Result<SourceList, String> {
        let normalized = normalize_source(url)?;
        let mut g = self.sources.lock().unwrap();
        let item = g
            .items
            .iter_mut()
            .find(|s| s.id == id)
            .ok_or_else(|| format!("源 {id} 不存在"))?;
        item.url = normalized;
        if !name.trim().is_empty() {
            item.name = name.trim().to_string();
        }
        let snapshot = g.clone();
        drop(g);
        self.persist_sources(&snapshot)?;
        if snapshot.current == id {
            *self.source.lock().unwrap() = snapshot
                .items
                .iter()
                .find(|s| s.id == id)
                .map(|s| s.url.clone())
                .unwrap_or_default();
        }
        Ok(snapshot)
    }

    /// 删除一个源。删掉当前源时自动切到第一个。
    pub fn remove_source(&self, id: &str) -> Result<SourceList, String> {
        let mut g = self.sources.lock().unwrap();
        g.items.retain(|s| s.id != id);
        if g.items.is_empty() {
            return Err("至少保留一个插件源".into());
        }
        if !g.items.iter().any(|s| s.id == g.current) {
            g.current = g.items[0].id.clone();
        }
        let snapshot = g.clone();
        drop(g);
        self.persist_sources(&snapshot)?;
        *self.source.lock().unwrap() = snapshot
            .items
            .iter()
            .find(|s| s.id == snapshot.current)
            .map(|s| s.url.clone())
            .unwrap_or_default();
        Ok(snapshot)
    }

    /// 切换当前源。
    pub fn select_source(&self, id: &str) -> Result<SourceList, String> {
        let mut g = self.sources.lock().unwrap();
        if !g.items.iter().any(|s| s.id == id) {
            return Err(format!("源 {id} 不存在"));
        }
        g.current = id.to_string();
        let snapshot = g.clone();
        let url = snapshot
            .items
            .iter()
            .find(|s| s.id == id)
            .map(|s| s.url.clone())
            .unwrap_or_default();
        drop(g);
        self.persist_sources(&snapshot)?;
        *self.source.lock().unwrap() = url;
        Ok(snapshot)
    }

    /// 兼容旧接口：直接设地址（等价于"新增或切换到该地址"）。
    pub fn set_source(&self, url: &str) -> Result<(), String> {
        self.add_source(url, "")?;
        Ok(())
    }

    /// 测试某个源是否可达（拉一次清单并回报包数）。
    pub fn test_source(&self, url: &str) -> Result<String, String> {
        let normalized = normalize_source(url)?;
        let body = fetch_manifest_text(&normalized, &self.git_proxies())?;
        let body = body.trim_start_matches('\u{feff}');
        let idx: Index =
            serde_json::from_str(body).map_err(|e| format!("清单格式不对: {e}"))?;
        let visible = idx.packages.values().filter(|p| !p.hidden).count();
        let author = idx
            .author
            .as_ref()
            .map(|a| a.name.clone())
            .unwrap_or_default();
        let mut msg = format!("可达，{} 个插件（{} 个可见）", idx.packages.len(), visible);
        if !author.is_empty() {
            msg.push_str(&format!("，发布者 {author}"));
        }
        Ok(msg)
    }

    /// 直接导入一份清单 JSON 文本（无服务器也能用：把 JSON 存本地）。
    pub fn import_manifest_text(&self, text: &str, name: &str) -> Result<SourceList, String> {
        let clean = text.trim_start_matches('\u{feff}');
        let idx: Index =
            serde_json::from_str(clean).map_err(|e| format!("JSON 解析失败: {e}"))?;
        if idx.schema_version != 1 {
            return Err(format!("schema_version={} 不受支持", idx.schema_version));
        }
        // 存成本地文件，然后把源指向这个本地路径的 file 标识
        std::fs::create_dir_all(&self.staging_dir).ok();
        let fname = format!(
            "local-manifest-{}.json",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis())
                .unwrap_or(0)
        );
        let path = self.staging_dir.join(&fname);
        std::fs::write(&path, clean).map_err(|e| e.to_string())?;
        // 用 file:// 前缀标记本地清单；下载 url 仍按清单里的相对路径解析到
        // 清单文件所在目录——但本地清单没有服务器，相对 url 会失败。
        // 所以导入本地清单时，url 必须是绝对地址。
        self.add_source(&format!("file://{}", path.display()), name)
    }

    fn persist_sources(&self, list: &SourceList) -> Result<(), String> {
        let p = self.app_data.join("plugin-sources.json");
        let out = serde_json::to_string_pretty(list).map_err(|e| e.to_string())?;
        let tmp = p.with_extension("json.tmp");
        std::fs::write(&tmp, out).map_err(|e| e.to_string())?;
        std::fs::rename(&tmp, &p).map_err(|e| e.to_string())
    }

    pub fn progress(&self) -> Progress {
        self.progress.lock().unwrap().clone()
    }

    pub fn installed_map(&self) -> InstalledMap {
        self.installed.lock().unwrap().clone()
    }

    fn set_progress(&self, p: Progress) {
        *self.progress.lock().unwrap() = p;
    }

    /// 拉远端清单（packages.json，兼容旧的 index.json）。
    pub fn fetch_index(&self) -> Result<Index, String> {
        let src = self.source();
        /*
         * 先看缓存。语义见 IndexCache 的注释：成功 60s、失败 20s。
         *
         * ★ 必须在"没配源"检查之前判缓存吗？不。没配源是零成本的判断，
         *   放在最前面能给出更好的提示文案（"还没有配置插件源"而不是
         *   上次那个源的失败原因）。
         */
        if src.trim().is_empty() {
            // 没配源时给可操作的提示，而不是抛一个 404 或空 URL 错误
            return Err("还没有配置插件源。请点「添加」填入插件源地址（.json 清单的 URL）".into());
        }
        // 命中缓存直接返回（成功/失败都算），避免每次进设置页都重拉
        {
            let g = self.index_cache.lock().unwrap();
            if let Some(c) = g.as_ref() {
                let ttl = if c.ok {
                    std::time::Duration::from_secs(60)
                } else {
                    std::time::Duration::from_secs(20)
                };
                if c.at.elapsed() < ttl {
                    return c.body.clone();
                }
            }
        }
        let result = self.fetch_index_uncached(&src);
        {
            let mut g = self.index_cache.lock().unwrap();
            *g = Some(IndexCache {
                at: std::time::Instant::now(),
                ok: result.is_ok(),
                body: result.clone(),
            });
        }
        result
    }

    /// 真正去拉索引（无缓存）。逻辑与原来的 fetch_index 完全一致。
    fn fetch_index_uncached(&self, src: &str) -> Result<Index, String> {
        // file:// 是"导入本地清单文本"用的：直接读本地文件
        let body = if let Some(path) = src.strip_prefix("file://") {
            std::fs::read_to_string(path).map_err(|e| format!("读本地清单失败: {e}"))?
        } else {
            // 源地址两种写法都支持：
            //   http://host:8099/                  → 依次试 packages.json / index.json
            //   http://host:8099/packages.json     → 直接用
            fetch_manifest_text(&src, &self.git_proxies())?
        };
        // 容错 BOM：PowerShell 5.1 的 -Encoding UTF8 / 某些编辑器会写 BOM，
        // serde_json 遇到会报 "expected value at line 1 column 1"。
        let body = body.trim_start_matches('\u{feff}');
        let idx: Index = serde_json::from_str(body)
            .map_err(|e| format!("插件源清单解析失败: {e}"))?;
        if idx.schema_version != 1 {
            return Err(format!(
                "插件源 schema_version={} 不受支持（本应用只认 1），请升级应用",
                idx.schema_version
            ));
        }
        Ok(idx)
    }

    /// 把清单里的相对 url 拼成绝对地址。
    ///
    /// 源是 `.../packages.json` 时，相对 url 要相对**该文件所在目录**解析。
    fn resolve_download_url(&self, rel: &str) -> String {
        // 绝对地址（http/https）：原样使用。
        if rel.starts_with("http://") || rel.starts_with("https://") {
            return rel.to_string();
        }
        // 协议相对（//host/path）：补上源的协议。
        // 不处理的话会被当相对路径，拼成 `http://source///host/path` 这种畸形地址——
        // 实测这类地址在宽容的测试服务器上会 200（自动规范化斜杠），
        // 但在 nginx / GitHub 上是 404，且错误信息指向莫名其妙的位置。
        if let Some(rest) = rel.strip_prefix("//") {
            let scheme = if self.source().starts_with("https://") {
                "https"
            } else {
                "http"
            };
            return format!("{scheme}://{rest}");
        }
        let src = self.source();
        // file:// 源：相对 url 解析到清单文件所在目录
        if let Some(path) = src.strip_prefix("file://") {
            let base = match path.rfind(['/', '\\']) {
                Some(i) => &path[..=i],
                None => "",
            };
            return format!("file://{base}{rel}");
        }
        let base = if src.ends_with(".json") {
            // 去掉文件名，只留目录
            match src.rfind('/') {
                Some(i) => src[..=i].to_string(),
                None => src.clone(),
            }
        } else if src.ends_with('/') {
            src.clone()
        } else {
            format!("{src}/")
        };
        // 相对路径里如果还带 ../ 或子目录，交给 HTTP 客户端按标准解析，
        // 这里只做朴素拼接（浏览器/curl 都会规范化）。
        format!("{base}{rel}")
    }

    /// 取消当前任务。
    pub fn cancel(&self) {
        let mut g = self.active.lock().unwrap();
        if let Some(a) = g.as_mut() {
            a.cancel.store(true, Ordering::SeqCst);
            if let Some(c) = a.child.as_mut() {
                let _ = c.kill();
            }
        }
    }

    /// 下载并安装/升级一个插件（含依赖）。
    ///
    /// 阻塞执行，前端在独立线程调用后轮询 `progress()`。
    pub fn install(&self, id: &str, app_version: &str) -> Result<InstalledInfo, String> {
        let idx = self.fetch_index()?;
        self.install_from_index(&idx, id, app_version)
    }

    pub fn install_from_index(
        &self,
        idx: &Index,
        id: &str,
        app_version: &str,
    ) -> Result<InstalledInfo, String> {
        let entry = idx
            .packages
            .get(id)
            .ok_or_else(|| format!("插件源里没有 {id}"))?
            .clone();
        let label = entry.label(id);

        // 版本门槛：应用太旧就不装，避免 manifest 结构对不上
        if !entry.min_app_version.is_empty() && !version_ge(app_version, &entry.min_app_version) {
            return Err(format!(
                "{} 需要应用 {} 或更高（当前 {}）",
                label, entry.min_app_version, app_version
            ));
        }

        // 先装依赖（如 danmu-api 依赖 node）
        for dep in &entry.requires {
            let dep_ins = self.installed.lock().unwrap().entries.get(dep).cloned();
            let dep_remote = idx.packages.get(dep).map(|e| e.version.clone()).unwrap_or_default();
            let need = match &dep_ins {
                Some(i) => i.version != dep_remote,
                None => true,
            };
            if need {
                self.install_from_index(idx, dep, app_version)?;
            }
        }

        // 下载 zip
        let zip_path = self.download(id, &entry)?;

        // 校验 sha256
        self.set_progress(Progress {
            id: id.to_string(),
            stage: "verifying".into(),
            percent: 100.0,
            received: entry.size,
            total: entry.size,
            message: "校验完整性…".into(),
        });
        let actual = sha256_file(&zip_path)?;
        if !actual.eq_ignore_ascii_case(&entry.sha256) {
            let _ = std::fs::remove_file(&zip_path);
            self.fail(id, "SHA256 不匹配，包可能被篡改或下载损坏");
            return Err(format!(
                "SHA256 不匹配：期望 {}… 实际 {}…",
                &entry.sha256[..12.min(entry.sha256.len())],
                &actual[..12.min(actual.len())]
            ));
        }

        // 解压到暂存目录
        self.set_progress(Progress {
            id: id.to_string(),
            stage: "extracting".into(),
            percent: 100.0,
            received: entry.size,
            total: entry.size,
            message: "解压中…".into(),
        });
        let tmp = self.staging_dir.join(format!("{}-{}.tmp", id, entry.version));
        if tmp.exists() {
            let _ = std::fs::remove_dir_all(&tmp);
        }
        std::fs::create_dir_all(&tmp).map_err(|e| e.to_string())?;
        extract_zip(&zip_path, &tmp)?;

        // 结构校验：zip 里可能多包了一层目录
        let plugin_root = find_plugin_root(&tmp)
            .ok_or_else(|| "包结构不对：找不到 manifest.json".to_string())?;
        validate_manifest(&plugin_root, id)?;

        // 原子替换
        self.set_progress(Progress {
            id: id.to_string(),
            stage: "installing".into(),
            percent: 100.0,
            received: entry.size,
            total: entry.size,
            message: "安装中…".into(),
        });
        std::fs::create_dir_all(&self.plugins_dir).ok();
        let target = self.plugins_dir.join(id);
        // 纯壳插件升级：保留旧 dist/ 里的 spider.jar 等运行时文件（新包优先）
        preserve_plugin_dist(&target, &plugin_root);
        let backup = self.staging_dir.join(format!("{}.bak", id));
        if backup.exists() {
            let _ = std::fs::remove_dir_all(&backup);
        }
        if target.exists() {
            // 重试几次：进程 kill 后 Windows 释放文件句柄有几毫秒到几百毫秒的延迟。
            // 一次失败就放弃会让"升级"变成不可靠操作。
            let mut last_err = None;
            let mut moved = false;
            for attempt in 0..6 {
                match std::fs::rename(&target, &backup) {
                    Ok(()) => {
                        moved = true;
                        break;
                    }
                    Err(e) => {
                        last_err = Some(e);
                        // 首次失败等久一点，后面间隔缩短：总共约 2.7s
                        let wait = if attempt == 0 { 800 } else { 300 };
                        std::thread::sleep(std::time::Duration::from_millis(wait));
                    }
                }
            }
            if !moved {
                let e = last_err.map(|x| x.to_string()).unwrap_or_default();
                return Err(format!(
                    "无法移走旧版本（文件被占用）：{e}\n请先在插件列表里点「停止」，或重启应用后再试"
                ));
            }
        }
        if let Err(e) = std::fs::rename(&plugin_root, &target) {
            // 回滚
            if backup.exists() {
                let _ = std::fs::rename(&backup, &target);
            }
            return Err(format!("安装失败（已回滚）: {e}"));
        }
        // tmp 里若还有残留（多包一层目录的情况）清掉
        let _ = std::fs::remove_dir_all(&tmp);

        let info = InstalledInfo {
            version: entry.version.clone(),
            sha256: entry.sha256.clone(),
            installed_at: now_iso(),
        };
        {
            let mut g = self.installed.lock().unwrap();
            g.entries.insert(id.to_string(), info.clone());
            let _ = save_installed(&self.staging_dir, &g);
        }
        self.set_progress(Progress {
            id: id.to_string(),
            stage: "done".into(),
            percent: 100.0,
            received: entry.size,
            total: entry.size,
            message: "安装完成".into(),
        });
        Ok(info)
    }

    /// 安装成功后调用：确认新版能起来，删掉备份；失败则回滚。
    pub fn finalize(&self, id: &str, ok: bool) -> Result<(), String> {
        let backup = self.staging_dir.join(format!("{id}.bak"));
        if !backup.exists() {
            return Ok(());
        }
        if ok {
            let _ = std::fs::remove_dir_all(&backup);
            Ok(())
        } else {
            // 回滚：把备份改回正式目录
            let target = self.plugins_dir.join(id);
            if target.exists() {
                let _ = std::fs::remove_dir_all(&target);
            }
            std::fs::rename(&backup, &target).map_err(|e| e.to_string())?;
            Err("新版本启动失败，已回滚到上一版本".into())
        }
    }

    /// 从本地 zip 导入（离线安装）。
    pub fn import_zip(&self, zip_path: &Path) -> Result<String, String> {
        if !zip_path.is_file() {
            return Err("文件不存在".into());
        }
        let tmp = self.staging_dir.join(format!(
            "import-{}.tmp",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0)
        ));
        let _ = std::fs::remove_dir_all(&tmp);
        std::fs::create_dir_all(&tmp).map_err(|e| e.to_string())?;
        extract_zip(zip_path, &tmp)?;
        let root = find_plugin_root(&tmp).ok_or("包结构不对：找不到 manifest.json")?;
        let id = read_manifest_id(&root)?;
        // 自己装的包没有远端 sha256 可对比，用实际文件 hash 记录
        let hash = sha256_file(zip_path)?;
        let version = read_manifest_version(&root)?;

        std::fs::create_dir_all(&self.plugins_dir).ok();
        let target = self.plugins_dir.join(&id);
        /*
         * ★ 升级到「纯壳插件」时保留旧 dist/（2026-11-09）。
         *
         * 新包不再带 spider.jar；如果直接整目录替换，用户原来能用的 jar 站会在
         * 升级后全部 ClassNotFoundException。这里在新目录落位前，把旧包里
         * dist/ 独有的文件补进新包（新包已有的文件优先，不覆盖）。
         * 失败不阻断安装：最坏结果是回到「需要用户重新导入源包」。
         */
        preserve_plugin_dist(&target, &root);
        let backup = self.staging_dir.join(format!("{id}.bak"));
        if backup.exists() {
            let _ = std::fs::remove_dir_all(&backup);
        }
        if target.exists() {
            std::fs::rename(&target, &backup).map_err(|e| e.to_string())?;
        }
        if let Err(e) = std::fs::rename(&root, &target) {
            if backup.exists() {
                let _ = std::fs::rename(&backup, &target);
            }
            return Err(format!("导入失败（已回滚）: {e}"));
        }
        let _ = std::fs::remove_dir_all(&tmp);
        {
            let mut g = self.installed.lock().unwrap();
            g.entries.insert(
                id.clone(),
                InstalledInfo {
                    version: version.clone(),
                    sha256: hash,
                    installed_at: now_iso(),
                },
            );
            let _ = save_installed(&self.staging_dir, &g);
        }
        Ok(id)
    }

    /// 卸载：删目录 + 清记录。
    pub fn uninstall(&self, id: &str) -> Result<(), String> {
        let target = self.plugins_dir.join(id);
        if target.exists() {
            std::fs::remove_dir_all(&target).map_err(|e| {
                format!("删除失败（插件可能还在运行，请先停止）: {e}")
            })?;
        }
        let mut g = self.installed.lock().unwrap();
        g.entries.remove(id);
        let _ = save_installed(&self.staging_dir, &g);
        Ok(())
    }

    // ------------------------------------------------------------ 下载

    fn download(&self, id: &str, entry: &PackageInfo) -> Result<PathBuf, String> {
        let cache = self.staging_dir.join("download-cache");
        std::fs::create_dir_all(&cache).ok();

        let mut url = self.resolve_download_url(&entry.url);
        // ★ 私有仓的标准下载链接带 token 也是 404（实测），要换成 API 资产端点。
        //   这与应用更新走的是同一条规则（`appupdate.rs`），但插件这条路径
        //   独立实现——不转的话表现是"清单能拉到、插件装不上"。
        if !source_token().trim().is_empty() {
            if let Some(api) = github_asset_api_url(&url, &source_token()) {
                url = api;
            }
        }
        // 打出实际下载地址：排查"清单 url 拼错"时这是最直接的证据
        eprintln!("[plugin] download {id} <- {url}");

        // ★ 缓存 key 必须带上完整下载地址的哈希。
        //
        // 原来只用 `id + 文件名`，实测踩坑：不同源里同名文件（都叫 tiny.zip）
        // 会互相命中缓存 —— 切到一个配置错误的源时，明明该 404 却"安装成功"，
        // 因为用的是上一个源留下的包。
        // 现在把 url 的短哈希塞进文件名，不同地址永不串。
        let url_hash = {
            let mut h = Sha256::new();
            h.update(url.as_bytes());
            let d = h.finalize();
            format!("{:x}", d)[..8].to_string()
        };
        let safe_url = entry.url.rsplit('/').next().unwrap_or("plugin.zip");
        let out = cache.join(format!("{id}-{url_hash}-{safe_url}"));

        // 已缓存且 hash 对得上：直接用，省一次下载
        if out.exists() {
            if let Ok(h) = sha256_file(&out) {
                if h.eq_ignore_ascii_case(&entry.sha256) {
                    self.set_progress(Progress {
                        id: id.to_string(),
                        stage: "downloading".into(),
                        percent: 100.0,
                        received: entry.size,
                        total: entry.size,
                        message: "使用本地缓存".into(),
                    });
                    return Ok(out);
                }
            }
            let _ = std::fs::remove_file(&out);
        }

        let cancel = Arc::new(AtomicBool::new(false));
        {
            let mut g = self.active.lock().unwrap();
            *g = Some(Active {
                cancel: cancel.clone(),
                child: None,
            });
        }

        // file:// 源（本地清单）：直接复制文件，不走 curl。
        // 这样"导入本地清单 JSON"也能真正下载——前提是清单里的 url 是
        // 绝对地址或指向同目录下的文件。
        if let Some(local) = url.strip_prefix("file://") {
            self.set_progress(Progress {
                id: id.to_string(),
                stage: "downloading".into(),
                percent: 100.0,
                received: entry.size,
                total: entry.size,
                message: "从本地复制".into(),
            });
            std::fs::copy(local, &out).map_err(|e| format!("复制本地插件包失败: {e}"))?;
            let mut g = self.active.lock().unwrap();
            *g = None;
            drop(g);
            return Ok(out);
        }

        let urls = self.download_candidates(&url);
        let mut last_err = String::new();
        for (i, u) in urls.iter().enumerate() {
            if i > 0 {
                self.set_progress(Progress {
                    id: id.to_string(),
                    stage: "downloading".into(),
                    percent: 0.0,
                    received: 0,
                    total: entry.size,
                    message: format!("换线路重试（{} / {}）…", i + 1, urls.len()),
                });
            }
            let _ = std::fs::remove_file(&out);
            match self.download_one(id, u, &out, entry, &cancel) {
                Ok(()) => return Ok(out),
                Err(e) if e == "已取消" => return Err(e),
                Err(e) => last_err = e,
            }
        }
        let msg = if last_err.is_empty() {
            "没有可用的下载地址".to_string()
        } else {
            format!("下载失败（已试 {} 个地址）：{last_err}", urls.len())
        };
        self.fail(id, &msg);
        Err(msg)
    }

    /// 单个候选地址的下载（原 download 的 curl 主体）。
    fn download_one(
        &self,
        id: &str,
        u: &str,
        out: &Path,
        entry: &PackageInfo,
        cancel: &Arc<AtomicBool>,
    ) -> Result<(), String> {
        let curl = find_curl()?;
        let tok = source_token();
        let mut dl = Command::new(&curl);
        // -L 跟随重定向（GitHub Releases 会 302 到 CDN）
        // -C -  断点续传
        // --fail  HTTP >=400 直接失败，不把错误页当内容
        // --progress-bar 输出到 stderr，下面解析
        dl.args(["-L", "--fail", "-C", "-", "--progress-bar", "-o"])
            .arg(out)
            .arg(u);
        // 紧邻构造点压窗口：放到下面那些条件头之后会漏读（维护时容易看漏）
        crate::proc::quiet(&mut dl);
        // 私有仓（GitHub 私有 repo 的 release 资产）必须带 token，否则 404。
        // ★ 但只发给 GitHub 自己的域名 —— 源指向自建服务器时不该收到用户的 PAT。
        if !tok.trim().is_empty() && token_allowed_for(u) {
            dl.arg("-H").arg(format!("Authorization: Bearer {}", tok.trim()));
        }
        // ★ API 资产端点必须显式要二进制。
        //
        // 踩过（2026-10-29）：不加这个头，`api.github.com/.../releases/assets/<id>`
        // 默认回一段**描述资产的 JSON**（几百字节），于是下载"成功"、
        // sha256 却对不上（期望 eef80f95… 实际 5d8a4ed7…），
        // 而且缓存里留下的是个假 zip。症状很像"清单写错 sha"，
        // 实际是 Accept 头没给。
        if u.contains("/releases/assets/") {
            dl.arg("-H").arg("Accept: application/octet-stream");
        }
        let mut child = dl
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|e| format!("无法启动 curl: {e}"))?;

        let stderr = child.stderr.take();
        if let Some(se) = stderr {
            let me = self.progress.clone();
            let id = id.to_string();
            let total_hint = entry.size;
            let canc = cancel.clone();
            std::thread::spawn(move || {
                let reader = std::io::BufReader::new(se);
                for line in reader.lines().map_while(Result::ok) {
                    if canc.load(Ordering::SeqCst) {
                        break;
                    }
                    // curl --progress-bar 形如：
                    //   "####  45.2% 12.3M 5s"
                    if let Some(pct) = parse_curl_percent(&line) {
                        let total = total_hint;
                        let received = ((pct / 100.0) * total as f64) as u64;
                        let mut g = me.lock().unwrap();
                        *g = Progress {
                            id: id.clone(),
                            stage: "downloading".into(),
                            percent: pct,
                            received,
                            total,
                            message: format!("下载中 {pct:.0}%"),
                        };
                    }
                }
            });
        }

        {
            let mut g = self.active.lock().unwrap();
            if let Some(a) = g.as_mut() {
                a.child = Some(child);
            }
        }
        // 等结束
        loop {
            let done = {
                let mut g = self.active.lock().unwrap();
                match g.as_mut().and_then(|a| a.child.as_mut()) {
                    Some(c) => match c.try_wait() {
                        Ok(Some(st)) => Some(st),
                        Ok(None) => None,
                        Err(e) => return Err(format!("等待 curl 失败: {e}")),
                    },
                    None => None,
                }
            };
            if let Some(st) = done {
                {
                    let mut g = self.active.lock().unwrap();
                    *g = None;
                }
                if cancel.load(Ordering::SeqCst) {
                    let _ = std::fs::remove_file(out);
                    self.set_progress(Progress {
                        id: id.to_string(),
                        stage: "cancelled".into(),
                        percent: 0.0,
                        received: 0,
                        total: entry.size,
                        message: "已取消".into(),
                    });
                    return Err("已取消".into());
                }
                if !st.success() {
                    let _ = std::fs::remove_file(out);
                    let msg = format!("下载失败（curl 退出码 {}）", st.code().unwrap_or(-1));
                    self.fail(id, &msg);
                    return Err(msg);
                }
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(200));
        }
        Ok(())
    }

    fn fail(&self, id: &str, msg: &str) {
        self.set_progress(Progress {
            id: id.to_string(),
            stage: "failed".into(),
            percent: 0.0,
            received: 0,
            total: 0,
            message: msg.to_string(),
        });
    }
}

// ---------------------------------------------------------------- 工具函数



fn load_installed(staging: &Path) -> InstalledMap {
    let p = staging.join("installed.json");
    match std::fs::read_to_string(&p) {
        Ok(t) if !t.trim().is_empty() => serde_json::from_str(&t).unwrap_or_default(),
        _ => InstalledMap::default(),
    }
}

/// 读取插件源列表。文件不存在 / 损坏时退回默认单源。
fn load_sources(app_data: &Path) -> SourceList {
    let p = app_data.join("plugin-sources.json");
    if let Ok(t) = std::fs::read_to_string(&p) {
        let t = t.trim_start_matches('\u{feff}');
        if let Ok(mut list) = serde_json::from_str::<SourceList>(t) {
            if !list.items.is_empty() {
                // current 指向不存在的 id 时，兜底到第一个
                if !list.items.iter().any(|s| s.id == list.current) {
                    list.current = list.items[0].id.clone();
                }
                return list;
            }
        }
    }
    SourceList::default()
}

fn save_installed(staging: &Path, m: &InstalledMap) -> Result<(), String> {
    let p = staging.join("installed.json");
    let out = serde_json::to_string_pretty(m).map_err(|e| e.to_string())?;
    let tmp = p.with_extension("json.tmp");
    std::fs::write(&tmp, out).map_err(|e| e.to_string())?;
    std::fs::rename(&tmp, &p).map_err(|e| e.to_string())?;
    Ok(())
}

fn now_iso() -> String {
    // 不引 chrono，用秒级时间戳足够（前端只用来显示"安装于"）
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    secs.to_string()
}

/// 语义化版本比较：a >= b ？
fn version_ge(a: &str, b: &str) -> bool {
    let pa = parse_ver(a);
    let pb = parse_ver(b);
    pa >= pb
}

fn parse_ver(v: &str) -> (u64, u64, u64) {
    let core = v.split('-').next().unwrap_or(v);
    let mut it = core.split('.').map(|s| s.parse::<u64>().unwrap_or(0));
    (
        it.next().unwrap_or(0),
        it.next().unwrap_or(0),
        it.next().unwrap_or(0),
    )
}

fn sha256_file(p: &Path) -> Result<String, String> {
    use std::io::Read;
    let mut f = std::fs::File::open(p).map_err(|e| e.to_string())?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = f.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}

/// 读插件源的访问令牌（私有仓用）。
///
/// <p>存在 `{appData}/plugin-token.txt`，一行纯文本。为什么用文件而不是
/// 塞进 `plugin-sources.json`：那个文件会随源列表被序列化/回传前端，
/// token 混在里面容易被界面上误显示出来。单独一个文件，读写路径清晰。
///
/// <p>空或读不到 = 匿名（公开源不需要 token）。
/// 这个地址能不能收到访问令牌。
///
/// ★ **只发给 GitHub 自己的域名**，其他一律不发。
///
/// <p>为什么必须限域（安全 + 功能双重原因）：
/// - 用户填的是 GitHub PAT，但它同时供「插件源」和「应用更新源」使用。
///   源换成自建文件服务器（NAS / 内网 nginx）后，无条件的 `Authorization`
///   会把 PAT 明文送过去，对方的 access log 里就有了。
/// - 不少简易静态服务器遇到未知的 `Authorization` 头会直接 401，
///   表现为"自建源怎么都连不上"，而日志里只看到一条 401 没有任何线索。
///
/// <p>三个域名各有用途，缺一个就会让某条私有仓路径失效：
/// - `api.github.com`：release 端点、资产端点
/// - `raw.githubusercontent.com`：私有仓的原始文件（清单常用它）
/// - `codeload.github.com` / `objects.githubusercontent.com`：
///   tarball 与 release 资产重定向后的实际下载域名（带 token 的 302 会跟过去）
fn token_allowed_for(url: &str) -> bool {
    let u = url.trim().to_ascii_lowercase();
    if !u.starts_with("https://") {
        return false;
    }
    let host = u["https://".len()..]
        .split(['/', '?', '#'])
        .next()
        .unwrap_or("")
        // 去掉可能的 userinfo / 端口
        .rsplit('@').next()
        .unwrap_or("")
        .split(':').next()
        .unwrap_or("");
    matches!(
        host,
        "api.github.com"
            | "raw.githubusercontent.com"
            | "codeload.github.com"
            | "objects.githubusercontent.com"
            | "github.com"
    )
}

fn source_token() -> String {
    // 这里刻意不让 token 进 eprintln/日志：只在 curl 参数里出现一次
    match crate::installer_token_path() {
        Some(p) => std::fs::read_to_string(p)
            .map(|s| s.trim().to_string())
            .unwrap_or_default(),
        None => String::new(),
    }
}

fn find_curl() -> Result<PathBuf, String> {
    // 优先系统 curl（Win10 1803+ 自带）
    let sys = PathBuf::from(r"C:\Windows\System32\curl.exe");
    if sys.is_file() {
        return Ok(sys);
    }
    // 其次 PATH
    if let Some(p) = which("curl") {
        return Ok(p);
    }
    Err("找不到 curl.exe。请升级到 Windows 10 1803 以上，或用「导入本地插件包」手动安装".into())
}

fn find_tar() -> Result<PathBuf, String> {
    let sys = PathBuf::from(r"C:\Windows\System32\tar.exe");
    if sys.is_file() {
        return Ok(sys);
    }
    if let Some(p) = which("tar") {
        return Ok(p);
    }
    Err("找不到 tar.exe。请升级到 Windows 10 1803 以上".into())
}

fn which(cmd: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    for dir in std::env::split_paths(&path) {
        for ext in ["", ".exe"] {
            let c = dir.join(format!("{cmd}{ext}"));
            if c.is_file() {
                return Some(c);
            }
        }
    }
    None
}

/// 升级纯壳插件时保留旧 `dist/` 中独有的文件（spider.jar 等）。
///
/// 新包已有的文件优先；只补旧包独有、且新包缺失的文件。
/// 失败静默：安装本身不应因为保留旧运行时文件而失败。
fn preserve_plugin_dist(old_root: &Path, new_root: &Path) {
    let old_dist = old_root.join("dist");
    if !old_dist.is_dir() {
        return;
    }
    let new_dist = new_root.join("dist");
    if std::fs::create_dir_all(&new_dist).is_err() {
        return;
    }
    copy_missing_files(&old_dist, &new_dist);
}

fn copy_missing_files(src: &Path, dst: &Path) {
    let entries = match std::fs::read_dir(src) {
        Ok(e) => e,
        Err(_) => return,
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let target = dst.join(entry.file_name());
        if path.is_dir() {
            if std::fs::create_dir_all(&target).is_ok() {
                copy_missing_files(&path, &target);
            }
        } else if path.is_file() && !target.exists() {
            let _ = std::fs::copy(&path, &target);
        }
    }
}

/// 用 bsdtar 解 zip（实测 Windows 自带 3.8.9 支持）。
fn extract_zip(zip: &Path, dest: &Path) -> Result<(), String> {
    let tar = find_tar()?;
    let mut cmd = Command::new(&tar);
    cmd.arg("-xf").arg(zip).arg("-C").arg(dest);
    // tar.exe 同样是控制台程序；装插件时会弹一个黑框（然后立刻消失）
    crate::proc::quiet(&mut cmd);
    let out = cmd.output().map_err(|e| format!("解压失败: {e}"))?;
    if !out.status.success() {
        return Err(format!(
            "解压失败: {}",
            String::from_utf8_lossy(&out.stderr).trim()
        ));
    }
    Ok(())
}

/// zip 里可能把内容包了一层目录（如 `apiserver/manifest.json`），
/// 找到真正含 manifest.json 的那一层。
fn find_plugin_root(dir: &Path) -> Option<PathBuf> {
    if dir.join("manifest.json").is_file() {
        return Some(dir.to_path_buf());
    }
    let entries = std::fs::read_dir(dir).ok()?;
    for e in entries.flatten() {
        let p = e.path();
        if p.is_dir() && p.join("manifest.json").is_file() {
            return Some(p);
        }
    }
    None
}

fn read_manifest_id(dir: &Path) -> Result<String, String> {
    let t = std::fs::read_to_string(dir.join("manifest.json"))
        .map_err(|e| format!("读 manifest 失败: {e}"))?;
    let v: serde_json::Value = serde_json::from_str(&t).map_err(|e| e.to_string())?;
    v.get("id")
        .and_then(|x| x.as_str())
        .map(|s| s.to_string())
        .ok_or_else(|| "manifest 缺少 id".to_string())
}

fn read_manifest_version(dir: &Path) -> Result<String, String> {
    let t = std::fs::read_to_string(dir.join("manifest.json"))
        .map_err(|e| format!("读 manifest 失败: {e}"))?;
    let v: serde_json::Value = serde_json::from_str(&t).map_err(|e| e.to_string())?;
    Ok(v.get("version")
        .and_then(|x| x.as_str())
        .unwrap_or("0.0.0")
        .to_string())
}

fn validate_manifest(dir: &Path, expect_id: &str) -> Result<(), String> {
    let got = read_manifest_id(dir)?;
    if got != expect_id {
        return Err(format!("包内 id={got} 与期望的 {expect_id} 不一致"));
    }
    // 至少要能解析出 exec，否则装上也启动不了
    let t = std::fs::read_to_string(dir.join("manifest.json")).map_err(|e| e.to_string())?;
    let v: serde_json::Value = serde_json::from_str(&t).map_err(|e| e.to_string())?;
    if v.get("exec").and_then(|x| x.as_str()).unwrap_or("").is_empty() {
        return Err("manifest 缺少 exec".into());
    }
    Ok(())
}

/// 解析 curl --progress-bar 的百分比。
fn parse_curl_percent(line: &str) -> Option<f64> {
    // 形如 "# 45.2%" 或 " 45.2%"
    let idx = line.find('%')?;
    let before = &line[..idx];
    let num: String = before
        .chars()
        .rev()
        .take_while(|c| c.is_ascii_digit() || *c == '.')
        .collect::<String>()
        .chars()
        .rev()
        .collect();
    num.parse::<f64>().ok()
}

/// 极简 HTTP GET，只用于拉小文件（清单）。
/// 大文件下载走 curl 子进程，那里要进度和续传。
///
/// <p><b>为什么要带 token</b>：私有 GitHub 仓的 `raw` 必须带 `Authorization`
/// 才返回内容（实测匿名 404、带 token 200）。用户把源指向自己的私有仓时，
/// 没有这条就拉不到清单。
fn http_get_text(url: &str) -> Result<String, String> {
    http_get_text_with(url, &source_token())
}

fn http_get_text_with(url: &str, token: &str) -> Result<String, String> {
    let curl = find_curl()?;
    let mut cmd = Command::new(&curl);
    cmd.args(["-L", "--fail", "--silent", "--show-error", "--max-time", "30"]);
    /*
     * ★ 这处是"一进设置页弹好几次黑框"的主犯。
     *
     * 链路：SettingsView.onMounted → loadIndex() → plugin_index
     *       → fetch_index() → fetch_manifest_text() → 本函数
     *       → 每个候选地址起一个 curl；失败还退避重试 5 次。
     * 一次进页面最多 10 个黑框，用户看到的就是"啪啪啪闪几下"。
     */
    crate::proc::quiet(&mut cmd);
    // 同下载路径：令牌只发给 GitHub 域名（自建源不该收到 PAT）
    if !token.trim().is_empty() && token_allowed_for(url) {
        cmd.arg("-H").arg(format!("Authorization: Bearer {}", token.trim()));
    }
    // ★ 只有「资产内容」端点要 octet-stream；元数据端点保持默认（JSON）。
    //
    // 踩过：这里不加的话，拉 `api.github.com/.../releases/assets/<id>`
    // 会拿到**描述资产的 JSON**（含 id/name/url 字段，但没有包信息），
    // 于是 Index 解析成 `packages: {}` —— 症状是"插件源可达但 0 个插件"，
    // 而清单本身完全正常，非常难查。
    // 反过来，无差别加又会把 `/releases/latest` 这类元数据端点要二进制，回 4xx。
    if url.contains("/releases/assets/") {
        cmd.arg("-H").arg("Accept: application/octet-stream");
    }
    let out = cmd
        .arg(url)
        .output()
        .map_err(|e| format!("请求失败: {e}"))?;
    if !out.status.success() {
        return Err(format!(
            "拉取 {} 失败: {}",
            url,
            String::from_utf8_lossy(&out.stderr).trim()
        ));
    }
    String::from_utf8(out.stdout).map_err(|e| format!("响应不是 UTF-8: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// release 端点判定：插件安装器与应用更新必须认同一种地址。
    ///
    /// 踩过：清单挂 Release 资产后，插件安装器把
    /// `.../releases/latest` 当成目录去拼 `.../latest/packages.json`，
    /// 必然 404 —— 表现是"插件源填对了却拉不到清单"。
    #[test]
    fn 识别_github_release_端点() {
        assert!(is_github_release_endpoint(
            "https://api.github.com/repos/upchr/meow/releases/latest"
        ));
        assert!(is_github_release_endpoint(
            "https://api.github.com/repos/upchr/meow/releases/latest/"
        ));
        assert!(is_github_release_endpoint(
            "https://api.github.com/repos/upchr/meow/releases/tags/release-v0.9.34"
        ));
        // 资产端点不是 release 列表端点（它是内容本身）
        assert!(!is_github_release_endpoint(
            "https://api.github.com/repos/upchr/meow/releases/assets/123"
        ));
        // 普通目录 / 裸清单 / 标准下载链接都不算
        assert!(!is_github_release_endpoint("https://raw.githubusercontent.com/o/r/main/"));
        assert!(!is_github_release_endpoint("https://raw.githubusercontent.com/o/r/main/packages.json"));
        assert!(!is_github_release_endpoint(
            "https://github.com/upchr/meow/releases/download/v1/a.zip"
        ));
        assert!(!is_github_release_endpoint("http://127.0.0.1:8099/"));
    }

    #[test]
    fn 从_release_json_找清单资产() {
        let rel = r#"{
          "url": "https://api.github.com/repos/upchr/meow/releases/427443068",
          "assets": [
            {"id": 111, "name": "drpy-0.1.0.zip"},
            {"id": 222, "name": "packages.json"}
          ]
        }"#;
        let (url, repo) = manifest_asset_from(rel, true).expect("应能找到 packages.json");
        assert_eq!(url, "https://api.github.com/repos/upchr/meow/releases/assets/222");
        assert_eq!(repo, "upchr/meow");
    }

    #[test]
    fn 没有清单资产时返回_none() {
        let rel = r#"{
          "url": "https://api.github.com/repos/upchr/meow/releases/1",
          "assets": [{"id": 111, "name": "drpy-0.1.0.zip"}]
        }"#;
        assert!(manifest_asset_from(rel, true).is_none());
        assert!(manifest_asset_from("not json", true).is_none());
        assert!(manifest_asset_from("{}", true).is_none());
    }

    /// 标准链接 → API 端点的前置条件：只处理 releases/download 形状。
    /// （真正的网络查询在 github_asset_api_url 内，单测不联网，
    ///  这里只钉住"什么样的地址根本不该尝试转换"。）
    #[test]
    fn 只有标准下载链接才需要转换() {
        // 这两个形状不该被当作 release 端点去查询
        assert!(!is_github_release_endpoint(
            "https://github.com/o/r/releases/download/v1/a.zip"
        ));
        // API 端点本身不需要再转换
        assert!(!is_github_release_endpoint(
            "https://api.github.com/repos/o/r/releases/assets/1"
        ));
    }

    /// 纯壳插件升级：旧 dist/ 独有文件要保留；新包已有的优先。
    #[test]
    fn 纯壳插件升级保留旧_dist_文件() {
        let base = std::env::temp_dir().join(format!("meow-inst-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);
        let old = base.join("old");
        let new = base.join("new");
        std::fs::create_dir_all(old.join("dist")).unwrap();
        std::fs::create_dir_all(new.join("dist")).unwrap();
        std::fs::write(old.join("dist").join("spider.jar"), b"old").unwrap();
        std::fs::write(old.join("dist").join("keep.json"), b"old").unwrap();
        std::fs::write(new.join("dist").join("keep.json"), b"new").unwrap();

        preserve_plugin_dist(&old, &new);

        assert_eq!(
            std::fs::read(new.join("dist").join("spider.jar")).unwrap(),
            b"old"
        );
        assert_eq!(
            std::fs::read(new.join("dist").join("keep.json")).unwrap(),
            b"new"
        );
        let _ = std::fs::remove_dir_all(&base);
    }
}
