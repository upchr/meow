import { apiBase, isDesktop, mediaTicketFor, request } from './index'

/**
 * 本地 / NAS 片库的前端接口。
 *
 * 后端把 WebDAV 目录映射成「目录 → 文件」两级结构，不伪装成 spider 的五方法：
 * 片库没有在线源那种 vod_id / play_from 语义，硬套会让播放页充满特判。
 * 浏览沿用同一套 UI，但数据从 `/api/library` 取。
 */

export interface LibraryEntry {
  /** 相对库根的路径，回传给后端用（不是绝对路径） */
  path: string
  name: string
  dir: boolean
  size?: number
  type?: string
  mtime?: string
}

export interface LibraryInfo {
  id: string
  name: string
  /** webdav | smb | local（alist 尚未实现） */
  type: string
  base: string
  user: string
  hasPass: boolean
  root: string
  /** SMB 域账号的域；其它类型为空串 */
  domain?: string
}

export interface LibraryTop {
  id: string
  name: string
  dirs?: LibraryEntry[]
  error?: string
}

export interface LibraryList {
  ok: boolean
  msg?: string
  dirs: LibraryEntry[]
  files: LibraryEntry[]
}

export interface LibraryOverview {
  ok: boolean
  libs: LibraryInfo[]
  tops: LibraryTop[]
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch(`${apiBase()}${path}`, init)
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return (await r.json()) as T
}

/** 概览：已配置的库 + 每个库的顶层目录（首页/入口页直接用）。 */
export function fetchLibrary(): Promise<LibraryOverview> {
  return req<LibraryOverview>('/api/library')
}

/** 列某个目录。path 为空表示库根。 */
export function fetchLibraryDir(lib: string, path: string): Promise<LibraryList> {
  const q = new URLSearchParams({ lib, path })
  return req<LibraryList>(`/api/library?${q}`)
}

// ------------------------------------------------- 目录树（「选择目录」弹窗）
//
// 与 fetchLibraryDir 的区别：那条要求 lib 已保存，而添加弹框在保存前没有 lib。
// 这组端点只认"本端真实有权限的根"，与片库配置无关。

/** 可浏览的根；source = share（应用共享）/ authorized（fnOS 授权）/ mount（挂载）/ drive（盘符）。 */
export interface LibraryRoot {
  id: string
  label: string
  path: string
  source: string
  readOnly?: boolean
  canBrowse?: boolean
}

export interface LibraryRootsResult {
  ok: boolean
  roots: LibraryRoot[]
}

export interface LibraryDirEntry {
  name: string
  mtime?: number
  /** 子目录是否可读；false 时前端显示一行灰字原因，不静默隐藏 */
  readable?: boolean
}

export interface LibraryLsResult {
  ok: boolean
  dirs: LibraryDirEntry[]
  files: LibraryDirEntry[]
  truncated?: boolean
}

export interface LibraryCapabilities {
  ok: boolean
  canBrowse: boolean
  canNativePicker: boolean
  canTvBridge: boolean
  roots: number
  hints: { localPathNote?: string }
}

/**
 * 带错误正文的 GET：`action=ls` 的 400 里有人话 msg（"path escapes root" 之类），
 * 丢掉它前端就只能显示 "HTTP 400"。
 */
async function reqMsg<T>(path: string): Promise<T> {
  const r = await fetch(`${apiBase()}${path}`)
  const text = await r.text()
  let parsed: unknown = null
  try {
    parsed = JSON.parse(text)
  } catch {
    /* 非 JSON（网关错误页）→ 用状态码 */
  }
  if (!r.ok) {
    const m = (parsed as { msg?: string } | null)?.msg
    throw new Error(m || `HTTP ${r.status}`)
  }
  return parsed as T
}

export function fetchLibraryRoots(): Promise<LibraryRootsResult> {
  return reqMsg<LibraryRootsResult>('/api/library?action=roots')
}

/** 列根下的一层目录。path 是相对根的路径（空 = 根本身）。 */
export function listLibraryRoot(root: string, path: string): Promise<LibraryLsResult> {
  const q = new URLSearchParams({ action: 'ls', root, path })
  return reqMsg<LibraryLsResult>(`/api/library?${q}`)
}

export function fetchLibraryCapabilities(): Promise<LibraryCapabilities> {
  return reqMsg<LibraryCapabilities>('/api/library?action=capabilities')
}

/** 保存整个库列表（后端按 id 覆盖写）。 */
export async function saveLibrary(libs: Partial<LibraryInfo>[]): Promise<boolean> {
  const r = await fetch(`${apiBase()}/api/library`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ libs }),
  })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  const j = (await r.json()) as { ok?: boolean }
  return !!j.ok
}

/** 测试连接（不落盘）。 */
export async function testLibrary(lib: Partial<LibraryInfo>): Promise<{ ok: boolean; msg?: string; entries?: number }> {
  const r = await fetch(`${apiBase()}/api/library?action=test`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ lib }),
  })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return (await r.json()) as { ok: boolean; msg?: string; entries?: number }
}

/**
 * 片库文件的播放地址。
 *
 * ★ 不能走 `proxyStream()`：那条通道的目标是任意 URL，后端用
 * {@code isSafeMediaUrl} 校验，而片库流是独立端点（只认配置里的库 + 相对路径）。
 * 用错端点会得到 403 `url not allowed`。
 */
export function libraryStream(lib: string, path: string): string {
  const q = new URLSearchParams({ lib, path })
  const mt = mediaTicketFor('library', lib)
  if (mt) q.set('mt', mt)
  return `${apiBase()}/api/library/stream?${q}`
}

// ---------------------------------------------------------------- 文件名解析

export interface ParsedName {
  /** 剧名/片名 */
  title: string
  /** 季号；未识别为 0 */
  season: number
  /** 集号；未识别为 0 */
  episode: number
  /** 年份；未识别为 0 */
  year: number
  /** 「第N部」里的部号；未识别为 0 */
  part: number
}

/** 视频扩展名白名单。列表里出现别的类型（.txt/.nfo）不该当影片。 */
const VIDEO_EXT = /\.(mp4|mkv|avi|ts|m2ts|mov|wmv|flv|webm|rmvb|iso)$/i

export function isVideoFile(name: string): boolean {
  return VIDEO_EXT.test(name)
}

/**
 * 从文件名提取剧名/季/集/年份/部号。
 *
 * 规则来自实测的真实片库（docs/37 §零）：
 *   - 剧集是 `白夜追凶.Day.and.Night.2017.E01.2160p...` —— **`E01` 不是 `S01E01`**，
 *     只写 `S(\d+)E(\d+)` 会全部匹配不到；
 *   - 电影合集是 `第1部.1972.特效中英字幕...` —— **`第N部` 是"部"不是"集"**，
 *     教父三部平铺在一个文件夹里；
 *   - 中文名 + 英文名混排，用点或空格分隔。
 *
 * 解析失败一律返回 title=去扩展名的原名，调用方按 title 分组，不会丢条目。
 */
export function parseVideoName(fileName: string): ParsedName {
  const out: ParsedName = { title: '', season: 0, episode: 0, year: 0, part: 0 }
  if (!fileName) return out
  let s = fileName.replace(VIDEO_EXT, '')
  // 记下「第N部」在不在，稍后决定标题从哪切：
  // 电影合集是 `第1部.1972.特效中英字幕....`，砍掉「第1部」后头一段就变成了
  // 「特效中英字幕」——那是字幕说明不是片名（实测踩过，测试也抓到了）。
  const partAtHead = /^第\s*[0-9一二三四五六七八九十]+\s*部/.test(s)

  // 第N部（中文数字或阿拉伯数字）
  const mPart = s.match(/第\s*([0-9一二三四五六七八九十]+)\s*部/)
  if (mPart) {
    out.part = cnNum(mPart[1])
    s = s.replace(mPart[0], ' ')
  }

  // 季集：优先 S01E02，其次 E02，最后「第N集」
  //
  // 另外要认「纯数字开头」：国产剧常见 `01 _ 郭女俠怒砸同福店.mkv`、`02 掌櫃的…`，
  // 编号后面既没有 E 也没有「第N集」。不认这种会让每集都被当成一部独立影片
  // （实测：武林外传 80 集在片库页刷出 80 张卡）。
  // 只认开头的 1–3 位数字 + 明确分隔符，且排除年份段（19xx/20xx），
  // 避免把 `1917.rmvb` 这种片名本身就是数字的当成集号。
  let m = s.match(/S(\d{1,2})[\s._-]*E(\d{1,3})/i)
  if (m) {
    out.season = Number(m[1])
    out.episode = Number(m[2])
    s = s.replace(m[0], ' ')
  } else {
    m = s.match(/(?:^|[\s._-])E(?:P)?(\d{1,3})(?:[\s._-]|$)/i)
    if (m) {
      out.episode = Number(m[1])
      s = s.replace(m[0], ' ')
    } else {
      m = s.match(/第\s*(\d{1,3})\s*[集话話]/)
      if (m) {
        out.episode = Number(m[1])
        s = s.replace(m[0], ' ')
      } else {
        // `01 _ 标题` / `02 - 标题` / `03.标题`：数字在最前，紧跟分隔符
        const hm = s.match(/^(\d{1,3})[._\-\s]+/)
        const num = hm ? Number(hm[1]) : 0
        if (hm && num > 0 && !/^(19|20)\d{2}$/.test(hm[1])) {
          out.episode = num
          s = s.replace(/^\d{1,3}[._\-\s]+/, ' ')
        } else {
          // `01.mp4`：剥掉扩展名后整个名字就是 `01`，没有分隔符可跟。
          // 实测踩过——毛骗S01/01.mp4 这种季目录不认就刷出 20 张"影片"卡。
          // 只认 1–3 位纯数字：`1917.mp4`（4 位）仍是片名，不会被当成第 1917 集。
          // ★ 用 bare[0] 不是 bare[1]：这个正则没有捕获组，配整个串才是 m[0]。
          const bare = s.match(/^\d{1,3}$/)
          if (bare && Number(bare[0]) > 0) {
            out.episode = Number(bare[0])
            s = ' '
          }
        }
      }
    }
  }

  // 年份（19xx / 20xx），取第一个
  m = s.match(/(?:^|[\s._([-])((?:19|20)\d{2})(?:[\s._)\]-]|$)/)
  if (m) {
    out.year = Number(m[1])
    s = s.replace(m[0], ' ')
  }

  // 标题取「年份/季集之前」那段：这是发布命名里唯一稳定的剧名位置。
  // 在噪音过滤之前切，否则 `Web-DL` 里的连字符会被当成分隔符把标题切碎。
  const head = s.split(/[.\s]+-/)[0]
  const firstSeg = head.split(/[.\s]{1,}/).filter((x) => x && !/^[-–—]$/.test(x))

  // 清掉画质/编码/发布组这些噪音
  const noise =
    /^(2160p|1080p|720p|480p|4k|uhd|hdr|web-?dl|webrip|bluray|bdrip|remux|hdtv|x264|x265|h264|h265|hevc|avc|aac|ac3|dts|ddp?5?\.?1|atmos|10bit|8bit|hdr10|fltth|mp4ba|chs|cht|eng|english|chinese)$/i
  const keep: string[] = []
  for (const seg of firstSeg) {
    if (noise.test(seg)) break // 一旦进入画质段，后面的都是发布信息
    if (/^[\s._\-(){}【】[\]]+$/.test(seg)) continue
    if (/中英字幕|特效中英字幕|国语|粤语|中字|字幕/.test(seg)) break
    keep.push(seg)
  }
  const title = partAtHead
    ? // 片名是上层文件夹名（文件名里没有），用「第N部」当条目名最不误导。
      // 年份单独由 ParsedName.year 带出，**不要拼进标题**，否则页面会把
      // `第1部 (1972)` 和 `(1972)` 各显示一次（实测踩过）。
      `第${out.part}部`
    : keep.join(' ').replace(/[[\](){}【】]/g, ' ').replace(/\s{2,}/g, ' ').trim()
  out.title = title || fileName.replace(VIDEO_EXT, '')
  return out
}

/** 中文数字转 int，只覆盖 1–99（「第十部」「第二十集」够用）。 */
function cnNum(s: string): number {
  if (/^\d+$/.test(s)) return Number(s)
  const digit: Record<string, number> = {
    一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9,
  }
  if (s === '十') return 10
  const i = s.indexOf('十')
  if (i < 0) return digit[s] || 0
  const tens = i === 0 ? 1 : digit[s[0]] || 0
  const ones = i === s.length - 1 ? 0 : digit[s[s.length - 1]] || 0
  return tens * 10 + ones
}

export interface TitleGroup {
  /** 卡片上显示的片名（`教父 第1部` 这种带部号的写法也在这里） */
  title: string
  year: number
  /**
   * 拿去豆瓣刮削的片名。默认等于 {@link title}，只有 `第N部` 这类结构性名字
   * 才换成上层目录名 —— 见 `groupByTitle` 里对 part 组的处理。
   */
  scrapeTitle?: string
  files: Array<{ path: string; name: string; size: number; episode: number; part: number }>
}

/** 刮削用的片名：结构性名字（`第N部`）在 groupByTitle 里已经换成目录名 */
export function scrapeTitleOf(g: { title: string; scrapeTitle?: string }): string {
  return g.scrapeTitle || g.title
}

/**
 * 目录名里藏着的季信息。
 *
 * 实测片库里有三种写法：`S01`（瑞克和莫蒂下）、`毛骗S01`（剧名和季粘在一起）、
 * `第一季`。**纯季目录名不能当剧名**——把它当剧名会让详情页标题写成「S01」，
 * 而剧名其实在上一层。
 *
 * @returns title 为空串 = 这个名字本身只是季标记，剧名要从上一层取
 */
export function parseFolderName(name: string): { title: string; season: number } {
  const s = (name || '').trim()
  if (!s) return { title: '', season: 0 }
  // 纯季目录：S01 / S1 / Season 2 / 第1季 / 第一季
  let m = s.match(/^s(?:eason)?[\s._-]*(\d{1,2})$/i)
  if (m) return { title: '', season: Number(m[1]) }
  m = s.match(/^第\s*([0-9一二三四五六七八九十]+)\s*季$/)
  if (m) return { title: '', season: cnNum(m[1]) }
  // 剧名 + 季：毛骗S01 / 某某 Season 2 / 某某第一季
  m = s.match(/^(.+?)[\s._-]*s(?:eason)?[\s._-]*(\d{1,2})$/i)
  if (m && m[1].trim()) return { title: m[1].trim(), season: Number(m[2]) }
  m = s.match(/^(.+?)[\s._-]*第\s*([0-9一二三四五六七八九十]+)\s*季$/)
  if (m && m[1].trim()) return { title: m[1].trim(), season: cnNum(m[2]) }
  return { title: s, season: 0 }
}

/**
 * 把一个目录里的文件按「片名」归组成剧集表。
 *
 * 为什么不按目录分组：实测电影合集是「教父/第1部、第2部、第3部」平铺在一个
 * 文件夹里，按目录分会变成"1 部剧 3 集"；按解析出的片名分，三部片子各自独立。
 *
 * **例外：带集号的文件，剧名要用上层文件夹名。**
 * 国产剧的命名是 `武林外传/01 _ 郭女俠怒砸同福店.mkv` —— 解析出的「片名」
 * 其实是这一集的副标题，每集都不同。按片名分会得到 80 个独立条目
 * （实测就是这么暴露的）。所以：目录内**有集号的文件占多数**时，
 * 把它们统一归到 `folderName` 名下。
 *
 * @param folderName 当前目录名。会被剥掉季标记（`毛骗S01` → `毛骗`）
 * @param parentName 上一层目录名。**只用于纯季目录**（`…/瑞克和莫蒂/S01/`），
 *   因为那时剧名不在当前目录名里，在上一层。
 */
/**
 * 纯「第N部」——是部号不是片名（`教父/第1部.1972…`）。
 *
 * 和 `coverTitleFor` 里那条护栏是同一判据：拿它去豆瓣搜会匹配成
 * 《封神第一部》（实测）。区别是那边防的是目录卡，这里防的是影片卡。
 */
const PART_ONLY = /^第\s*[0-9一二三四五六七八九十]+\s*部$/

/** `第N部` 组该用哪个名字去刮：目录名，目录名本身也是 `第N部` 时退到上一层 */
function partBaseName(folderTitle: string, parentName: string): string {
  if (folderTitle && !PART_ONLY.test(folderTitle)) return folderTitle
  if (parentName && !PART_ONLY.test(parentName)) return parentName
  return ''
}

export function groupByTitle(files: LibraryEntry[], folderName = '', parentName = ''): TitleGroup[] {
  const videos = files.filter((f) => isVideoFile(f.name))
  const parsed = videos.map((f) => ({ f, p: parseVideoName(f.name) }))
  // 多数文件带集号 → 这是一个剧集目录，剧名取文件夹名
  // （文件夹名剥掉季标记；纯季目录名则退回上一层，见 parseFolderName）
  const folderTitle = parseFolderName(folderName).title || parentName || folderName
  const withEp = parsed.filter((x) => x.p.episode > 0).length
  const episodic = !!folderTitle && withEp > 0 && withEp * 2 >= parsed.length

  const map = new Map<string, TitleGroup>()
  for (const { f, p } of parsed) {
    // ★ `第N部` 要挂上目录名：显示成「教父 第1部」，刮削用「教父」。
    //   直接用「第1部」去搜会得到《封神第一部》（实测，用户截图里整排
    //   教父三部曲挂着封神海报）。年份天然区分各部（1972/1974/1990）。
    const partOnly = !episodic && PART_ONLY.test(p.title)
    const partBase = partOnly ? partBaseName(folderTitle, parentName) : ''
    const title = partOnly && partBase ? `${partBase} ${p.title}` : episodic ? folderTitle : p.title
    const year = episodic ? 0 : p.year
    const key = `${title}|${year}`
    let g = map.get(key)
    if (!g) {
      g = { title, year, files: [], ...(partBase ? { scrapeTitle: partBase } : {}) }
      map.set(key, g)
    }
    g.files.push({
      path: f.path,
      name: f.name,
      size: f.size || 0,
      episode: p.episode,
      part: p.part,
    })
  }
  for (const g of map.values()) {
    g.files.sort((a, b) => {
      const x = a.episode || a.part
      const y = b.episode || b.part
      if (x !== y) return x - y
      return a.name.localeCompare(b.name)
    })
  }
  return Array.from(map.values()).sort((a, b) => a.title.localeCompare(b.title))
}

export function fmtSize(bytes: number): string {
  if (!bytes) return ''
  const gb = bytes / 1024 / 1024 / 1024
  if (gb >= 1) return `${gb.toFixed(1)}GB`
  return `${Math.round(bytes / 1024 / 1024)}MB`
}

/**
 * 一个目录到底是什么。
 *
 * 分类目录（电视剧/电影）和一部剧的目录（沉默的真相/）在协议层面长得一样，
 * 但用户的期待完全不同：前者该铺卡片墙等他点，后者该直接给详情页。
 * 判据只用已经拿到手的列表，**不再发额外请求**：
 *
 *   empty   — 没有可看的
 *   series  — 有视频文件、且多数带集号 → 一部剧，直接进详情
 *   movies  — 有视频文件、但没有集号（或只有少数）→ 电影合集，铺卡片
 *   mixed   — 既有子目录又有视频 → 铺卡片（子目录先，散片后）
 *   browse  — 只有子目录 → 分类层，铺卡片
 *
 * 注意 `series` 要求**没有子目录**：`毛骗/` 下面有 `毛骗S01..S03` 三个季，
 * 它是"剧"但不是"一部剧"，得让用户先选季。
 */
export type DirKind = 'empty' | 'series' | 'movies' | 'mixed' | 'browse'

export function dirKindOf(dirs: LibraryEntry[], files: LibraryEntry[]): DirKind {
  const videos = files.filter((f) => isVideoFile(f.name))
  const hasDirs = dirs.length > 0
  if (!videos.length) return hasDirs ? 'browse' : 'empty'
  if (hasDirs) return 'mixed'
  const withEp = videos.filter((f) => parseVideoName(f.name).episode > 0).length
  // 一半以上带集号才算"一部剧"：教父三部曲那种 0 集号是电影合集，
  // 而武林外传 77 集全部带集号。混排目录（电影+花絮）落到 movies 更安全。
  return withEp * 2 >= videos.length ? 'series' : 'movies'
}

/**
 * 一棵"剧集树"的标题：从当前目录名往上找，跳过纯季目录名（`S01`）。
 *
 * @param names 从根到当前的目录名序列（不含片库根）
 */
export function showTitleFrom(names: string[]): string {
  for (let i = names.length - 1; i >= 0; i--) {
    const t = parseFolderName(names[i]).title
    if (t) return t
  }
  return names[names.length - 1] || ''
}

/**
 * 目录卡片该用哪个片名去刮。
 *
 * 用户的口径是「目录海报应该显示目录下第一个影视的海报」，所以这里的输入是
 * **向下探到第一层有视频的目录**之后的 `names` 链与那层的视频列表。
 *
 * 三种情况分开处理，每条都对应实测踩过的错配：
 *   1. 剧集（多数带集号）→ 用目录名。文件名里是集号/副标题，搜不出东西。
 *   2. 单片电影 → **用文件名解析出的片名**。目录名常是中文别名，
 *      豆瓣上没有；`电影/色戒/Lust.Caution.2007…mkv` 用目录名「色戒」
 *      会匹配到 2002 年那部同名无关片，用文件名才是 2007 的《色，戒》。
 *   3. 其余（多部合集）→ 用目录名。`教父/第1部.1972…` 的文件名解析出来是
 *      「第1部」，拿去搜会匹配成《封神第一部》。
 */
export function coverTitleFor(names: string[], videos: LibraryEntry[]): string {
  if (!videos.length) return names.length ? showTitleFrom(names) : ''
  const withEp = videos.filter((f) => parseVideoName(f.name).episode > 0).length
  const episodic = withEp * 2 >= videos.length
  if (!episodic && videos.length === 1) {
    const p = parseVideoName(videos[0].name).title
    // 「第N部」不是片名，见上面第 3 条
    if (p && !/^第\s*[0-9一二三四五六七八九十]+\s*部$/.test(p)) return p
  }
  return showTitleFrom(names)
}

const CN_SEASON = ['', '一', '二', '三', '四', '五', '六', '七', '八', '九', '十']

/**
 * 季目录卡片的刮削名：`瑞克和莫蒂` + 1 → `瑞克和莫蒂 第一季`。
 *
 * 为什么要加季：祖先链修对之后，同一部剧的 `S01…S08` 都会刮到同一张剧海报，
 * 一整排重复的图看起来就像"没刮到"。豆瓣对热门剧有独立的季条目，带上
 * 「第一季」才能拿到各自的图。
 *
 * @returns '' = 不加季名（超过 10 季 / 没有剧名），调用方退回剧名本身
 */
export function seasonTitle(base: string, season: number): string {
  const cn = CN_SEASON[season] || ''
  if (!base || !cn) return ''
  // 剧名里已经带季了（`瑞克和莫蒂 第一季` 这种目录）就别叠第二遍
  if (base.includes(`第${cn}季`)) return ''
  return `${base} 第${cn}季`
}

// ---------------------------------------------------------------- 按需转码

export interface TranscodeResult {
  ok: boolean
  id?: string
  /** 后端实际值是 cancelled（双 l）；canceled 兼容旧值 */
  state?: 'idle' | 'running' | 'ready' | 'failed' | 'cancelled' | 'canceled'
  error?: string
  segments?: number
  /** 已转出多少秒（增量产出，可边看边转） */
  readySeconds?: number
  /** 源真实总时长（秒）。转码未完成时 video.duration 只是已转出时长，靠它显示对的进度条 */
  duration?: number
  playlist?: string
}

/**
 * 让后端把这个源现转成 HLS。
 *
 * **为什么不改源文件**：产物写进后端缓存目录，片库是只读资产。
 * 幂等：同一地址重复调用复用同一个任务。
 */
/**
 * 片库文件的转码（本地目录 / SMB）。
 *
 * **为什么不复用 startTranscode**：那条路把地址交给后端去 HTTP 拉，
 * 但片库有两种情况拉不了 —— 本地目录的流地址是 127.0.0.1（被 SSRF 防线拒），
 * SMB 的 ffmpeg 根本不认。所以这里传 lib + path，让后端自己取文件。
 */
export async function startLibraryTranscode(lib: string, path: string): Promise<TranscodeResult> {
  const q = new URLSearchParams({ lib, path })
  const r = await fetch(`${apiBase()}/api/transcode?${q}`)
  if (!r.ok) {
    // 后端 stage 前的预检（ffmpeg 缺失等）返回 502 + 人话 msg——
    // 直接丢成 "HTTP 502" 用户什么都看不懂（2026-11-17 OECT 场景）。
    let msg = `HTTP ${r.status}`
    try {
      const j = (await r.json()) as { msg?: string }
      if (j?.msg) msg = j.msg
    } catch {
      /* 响应不是 JSON 就保持状态码 */
    }
    throw new Error(msg)
  }
  return (await r.json()) as TranscodeResult
}

/** 等片库文件转码到可播。语义同 waitTranscode。 */
export async function waitLibraryTranscode(
  lib: string,
  path: string,
  onProgress?: (seconds: number) => void,
  /*
   * 600 秒（2026-11-17 从 180s 放宽）：4K HEVC 10bit 在弱设备（如 OEC/RK3566）
   * 上软解只有 ~0.9 fps，首片（2 秒内容）也要一两分钟，180s 会把"能转出来"的
   * 情况误判成失败。前端现在会显示"已就绪 X 秒"，用户能看到在进展。
   */
  timeoutMs = 600_000,
): Promise<TranscodeResult> {
  const deadline = Date.now() + timeoutMs
  let last = await startLibraryTranscode(lib, path)
  while (Date.now() < deadline) {
    if (last.state === 'failed') return last
    /*
     * 被别的任务「串行化」取消了（一次只跑一个重转码）：立刻返回失败，
     * 否则前端会一直轮询到 600 秒超时，用户看到的是"永远在转码"。
     */
    if (last.state === 'cancelled' || last.state === 'canceled') {
      return { ok: false, state: 'failed', error: '这个文件的转码被新任务打断了，请再点一次播放' }
    }
    if (last.playlist) return last
    if (last.readySeconds && onProgress) onProgress(last.readySeconds)
    await new Promise((r) => setTimeout(r, 2000))
    const q = new URLSearchParams({ lib, path, mode: 'status' })
    try {
      const r = await fetch(`${apiBase()}/api/transcode?${q}`)
      if (r.ok) last = (await r.json()) as TranscodeResult
    } catch {
      /* 单次失败继续等 */
    }
  }
  return { ok: false, state: 'failed', error: '转码超时（还没有可播分片）' }
}

export async function startTranscode(src: string): Promise<TranscodeResult> {
  const q = new URLSearchParams({ src })
  const r = await fetch(`${apiBase()}/api/transcode?${q}`)
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return (await r.json()) as TranscodeResult
}

/**
 * 等转码产出足够起播的分片。
 *
 * **为什么不能拿首次响应就判成败**：`POST /api/transcode` 只是"把任务排上"，
 * 此时 state=running 且 playlist 还是空——直接判 `!playlist` 会报"转码不可用"，
 * 而实际上几秒后就绪（实测踩过：界面显示转码不可用，后端已经 79 个分片）。
 */
export async function waitTranscode(
  src: string,
  onProgress?: (seconds: number) => void,
  timeoutMs = 90_000,
): Promise<TranscodeResult> {
  const deadline = Date.now() + timeoutMs
  // 首次调用顺带启动任务
  let last = await startTranscode(src)
  while (Date.now() < deadline) {
    if (last.state === 'failed') return last
    /*
     * 被别的任务「串行化」取消了（一次只跑一个重转码）：立刻返回失败，
     * 否则前端会一直轮询到 600 秒超时，用户看到的是"永远在转码"。
     */
    if (last.state === 'cancelled' || last.state === 'canceled') {
      return { ok: false, state: 'failed', error: '这个文件的转码被新任务打断了，请再点一次播放' }
    }
    if (last.playlist) return last
    if (last.readySeconds && onProgress) onProgress(last.readySeconds)
    await new Promise((r) => setTimeout(r, 2000))
    const q = new URLSearchParams({ src, mode: 'status' })
    try {
      const r = await fetch(`${apiBase()}/api/transcode?${q}`)
      if (r.ok) last = (await r.json()) as TranscodeResult
    } catch {
      /* 单次失败继续等，不要因为一次抖动就判死 */
    }
  }
  return { ok: false, state: 'failed', error: '转码超时（90 秒还没有可播分片）' }
}

/** 后端是否装了 ffmpeg（没装就别显示"转码播放"这种按钮）。 */
export async function transcodeAvailable(): Promise<boolean> {
  try {
    const r = await fetch(`${apiBase()}/api/transcode`)
    if (!r.ok) return false
    const j = (await r.json()) as { available?: boolean }
    return !!j.available
  } catch {
    return false
  }
}

// ---------------------------------------------------------------- 刮削

export interface ScrapedMeta {
  ok: boolean
  title?: string
  year?: number
  poster?: string
  id?: string
  episodes?: number
  /**
   * 后端实际命中的豆瓣条目标题（`enrich` 里回传）。
   *
   * 后端 `pick()` 在**一条都匹配不上**时会退回第一条建议（这是刻意的，
   * 但也意味着"有海报"不等于"刮对了"）。季名这种额外加料的名字要拿它
   * 复核一次，不沾亲就回退到剧名。
   */
  matchedTitle?: string
}

/**
 * 刮一条（按片名 + 年份）。失败返回 ok=false，调用方退回文件名。
 *
 * @param refresh 跳过缓存重查。用户点「重新搜刮」时必须为 true ——
 *   缓存命中会直接返回旧结果，看起来像"点了没反应"。
 * @param lib 片库 id。**参与后端缓存 key**：两个库各有《教父》时不能共用
 *   一条记录。省略则为旧的「全局条目」（老调用方兼容）。
 */
export async function scrapeOne(
  title: string,
  year: number,
  refresh = false,
  lib = '',
): Promise<ScrapedMeta> {
  const q = new URLSearchParams({ title })
  if (year > 0) q.set('year', String(year))
  if (refresh) q.set('refresh', '1')
  if (lib) q.set('lib', lib)
  try {
    const r = await fetch(`${apiBase()}/api/scrape?${q}`)
    if (!r.ok) return { ok: false }
    return (await r.json()) as ScrapedMeta
  } catch {
    return { ok: false }
  }
}

/**
 * 解除这一条刮削绑定（删掉「片名 → 豆瓣条目」的映射）。
 *
 * 效果：这张海报消失，下次列目录时重新匹配。之所以要做成显式操作而不是
 * 「重新搜刮」的副作用，是因为有些片名（比如《毛骗》）怎么搜都匹配到错的条目，
 * 用户需要的是"别给我乱配"，而不是"再试一次"。
 *
 * 后端还会顺带清掉旧的全局条目（没有库维度的那些），否则"解除绑定"看起来没生效。
 */
export async function unbindScrape(title: string, year = 0, lib = ''): Promise<boolean> {
  const q = new URLSearchParams({ title })
  if (year > 0) q.set('year', String(year))
  if (lib) q.set('lib', lib)
  try {
    const r = await fetch(`${apiBase()}/api/scrape?${q}`, { method: 'DELETE' })
    if (!r.ok) return false
    const j = (await r.json()) as { ok?: boolean }
    return !!j.ok
  } catch {
    return false
  }
}

/**
 * 批量补元数据。
 *
 * 一次 POST 而不是前端循环：刮削要逐条查外网，串行在后端跑一次就够了；
 * 前端循环会并发几十个请求，豆瓣会限流。
 *
 * ★ 返回里可能有**末尾哨兵** `{"__meta":{rateLimited:true}}`（后端用它
 *   告诉前端"被豆瓣限流了，剩下的没查"）。这里过滤掉，并把标记交给调用方，
 *   否则它会被当成一条没有片名的刮削结果、错位到别的片名上。
 */
export async function scrapeGroups(
  groups: Array<{ title: string; year: number }>,
  lib = '',
): Promise<ScrapeBatchResult> {
  try {
    const r = await fetch(`${apiBase()}/api/scrape`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ groups, ...(lib ? { lib } : {}) }),
    })
    if (!r.ok) return { items: [], rateLimited: false }
    const j = (await r.json()) as { items?: unknown[] }
    const raw = j.items || []
    let rateLimited = false
    const items: ScrapedMeta[] = []
    for (const x of raw) {
      const o = x as Record<string, unknown>
      const meta = o.__meta as { rateLimited?: boolean } | undefined
      if (meta) {
        if (meta.rateLimited) rateLimited = true
        continue
      }
      items.push(o as unknown as ScrapedMeta)
    }
    return { items, rateLimited }
  } catch {
    return { items: [], rateLimited: false }
  }
}

/** 批量刮削的结果：items 与 items 一一对应（同序），rateLimited 表示被限流。 */
export interface ScrapeBatchResult {
  items: ScrapedMeta[]
  rateLimited: boolean
}

/**
 * 海报地址：走图片代理。
 *
 * 豆瓣图床有防盗链（直连返回 418），必须带 Referer 经后端取。
 */
export function posterUrl(src: string): string {
  if (!src) return ''
  const b = apiBase()
  // 豆瓣的小图（s_/m_）在 4K 电视上糊：同一文件 l_ 有 173 KB、s_ 只有 17 KB
  // （实测三者都 200）。这里统一抬到大图，**旧缓存里的 s_ 也被抬上去**；
  // 个别没有 l_ 的条目由 UI 的 @error 退回 s_（见 LibraryView.onPosterError）。
  const big = src
    .replace('/s_ratio_poster/', '/l_ratio_poster/')
    .replace('/m_ratio_poster/', '/l_ratio_poster/')
  const q = new URLSearchParams({ url: big, referer: 'https://movie.douban.com/' })
  // 海报是 <img> 直取，带不了 X-Meow-Token；鉴权开启时用短时票
  const mt = mediaTicketFor('proxy')
  if (mt) q.set('mt', mt)
  return `${b}/api/proxy/image?${q}`
}

// ---------------------------------------------------------------- 离线缓存

export interface CacheTask {
  ok?: boolean
  id: string
  site?: string
  vodId?: string
  ep?: string
  name?: string
  state: 'idle' | 'running' | 'done' | 'failed' | 'canceled' | 'paused'
  percent?: number
  done?: number
  total?: number
  bytes?: number
  error?: string
  /** 完成后可播的本地清单地址 */
  playlist?: string
  /** 剧名（纯剧名，不含"第N集"） */
  vodName?: string
  vodYear?: string
  vodArea?: string
  vodRemarks?: string
  /** 本地海报文件名；空 = 没有图 */
  posterFile?: string
  /** 本地海报地址（后端端点），有 posterFile 才有 */
  posterUrl?: string
}

/** 一集（按剧聚合后的 eps 元素）。 */
export interface CachedEpisode {
  id: string
  ep: string
  name?: string
  bytes: number
  /** 清单已落盘 → 可离线播放 */
  playable: boolean
  state: string
  percent: number
}

/** 一部剧的缓存聚合体（「我的缓存」一剧一卡片）。 */
export interface CachedVod {
  site: string
  vodId: string
  name: string
  poster?: string
  posterFile?: string
  /** 本地海报端点（有 posterFile 才由后端拼） */
  posterLocal?: string
  year?: string
  area?: string
  remarks?: string
  episodes: number
  doneEpisodes: number
  /** running + paused 的集数 */
  downloading: number
  totalBytes: number
  /** 最近访问时间（毫秒） */
  lastAt: number
  eps: CachedEpisode[]
}

export interface CacheGrouped {
  ok: boolean
  items: CachedVod[]
  totalBytes: number
}

/**
 * 按剧聚合的缓存列表。
 *
 * 为什么不复用 `listCache()`：那个是**一集一条**的平铺结构，
 * 「我的缓存」要的是一剧一张海报卡片，分组语义（哪些集下完了、
 * 哪些还在跑）由后端算才不会与前端漂移。
 */
export async function listCacheGrouped(): Promise<CacheGrouped> {
  try {
    const r = await fetch(`${apiBase()}/api/cache/grouped`)
    if (!r.ok) return { ok: false, items: [], totalBytes: 0 }
    const d = (await r.json()) as CacheGrouped
    // 后端只给 posterFile，这里补一个可直接用作 img src 的端点地址
    for (const it of d.items || []) {
      if (it.posterFile) {
        it.posterLocal = `${apiBase()}/api/cache/poster?site=${encodeURIComponent(it.site)}&vodId=${encodeURIComponent(it.vodId)}`
      }
    }
    return d
  } catch {
    return { ok: false, items: [], totalBytes: 0 }
  }
}

/**
 * 任务控制：暂停 / 取消 / 删除；以及清弹幕缓存。
 *
 * <p>`clearDanmaku` 不需要 id（弹幕缓存是全局的、按文件 TTL 的），
 * 第四个参数 `mode` 选 `expired`（默认，只删超 30 天的）还是 `all`。
 * 返回体因此分两种形状：带 id 的动作回 `done/failed`，清缓存回 `removed/bytes`。
 */
export interface CacheControlResult {
  ok: boolean
  done?: string[]
  failed?: Array<{ id: string; reason: string }>
  /** clearDanmaku 专用：删掉的文件数 */
  removed?: number
  /** clearDanmaku 专用：清理后剩余占用字节 */
  bytes?: number
}

export async function controlCache(
  action: 'pause' | 'cancel' | 'delete' | 'clearDanmaku',
  ids: string[],
  mode?: 'expired' | 'all',
): Promise<CacheControlResult> {
  const r = await fetch(`${apiBase()}/api/cache/control`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action, ids, ...(mode ? { mode } : {}) }),
  })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return (await r.json()) as CacheControlResult
}

export interface CacheList {
  ok: boolean
  items: CacheTask[]
  totalBytes: number
  /** 配额上限（字节），0 表示不限制 */
  quotaBytes?: number
  /** lru | off */
  evictMode?: string
  /** 弹幕缓存占用字节（与视频缓存分开算，不占配额） */
  danmakuBytes?: number
  /** 弹幕缓存文件数 */
  danmakuCount?: number
  /** 缓存实际落盘目录（PC 端用于"我的缓存在哪"） */
  cacheDir?: string
  /**
   * policy 里的 cacheDir 与进程内已解析的根不同 → 改了但还没重启。
   *
   * 缓存根**进程内只解析一次**（VideoCache.cacheRoot 的注释）：允许中途换盘会让
   * 任务跑到一半时分片散落两个目录、看起来"永远差几片"。所以这里只提示重启，
   * 不要在界面上偷偷重算路径。
   */
  cacheDirReloadRequired?: boolean
  /** policy.json 里写的 cacheDir 原值（未解析；空串 = 用默认目录） */
  cacheDirPolicy?: string
}

/**
 * 缓存一集到本地。
 *
 * **目标不是省流量，是断网可看**：分片与清单都落盘，清单里的引用改成
 * 同目录相对文件名（断网时没有任何服务端能拼前缀）。
 */
export async function cacheEpisode(input: {
  site: string
  vodId: string
  ep: string
  name: string
  url: string
  /** 海报远端地址（详情页/历史里有就带上，离线时靠它出图） */
  poster?: string
  /** 纯剧名（不含"第N集"）；后端聚合时优先用它 */
  vodName?: string
  vodYear?: string
  vodArea?: string
  vodRemarks?: string
}): Promise<CacheTask> {
  const r = await fetch(`${apiBase()}/api/cache`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return (await r.json()) as CacheTask
}

export async function listCache(): Promise<CacheList> {
  try {
    const r = await fetch(`${apiBase()}/api/cache`)
    if (!r.ok) return { ok: false, items: [], totalBytes: 0 }
    return (await r.json()) as CacheList
  } catch {
    return { ok: false, items: [], totalBytes: 0 }
  }
}

export async function removeCache(id: string): Promise<boolean> {
  const r = await fetch(`${apiBase()}/api/cache?id=${encodeURIComponent(id)}`, {
    method: 'DELETE',
  })
  return r.ok
}

/**
 * 导出为单个文件的下载地址（Web 端直接交给浏览器下载）。
 *
 * 后端按 ffmpeg 可用性决定格式：有则 mp4（-c copy remux），无则拼成单个 .ts。
 * 响应头 `X-Meow-Export-Format` 说明实际格式，但浏览器下载拿不到响应头——
 * 所以 Web 端只用它下载，格式提示交给 PC/TV 那条能读到 header 的路径。
 */
export function cacheExportUrl(id: string): string {
  return `${apiBase()}/api/cache/export?id=${encodeURIComponent(id)}`
}

/**
 * 导出到本机目录（PC 专用）。
 *
 * 后端只接受**回环地址**调用——它能写任意路径，放开到局域网等于给后端
 * 一个任意写盘的口子。PC 的后端是本地 sidecar，正好满足。
 */
export async function exportCacheToLocal(
  id: string,
  dir: string,
  name?: string,
): Promise<{ ok?: boolean; path?: string; format?: string; bytes?: number; msg?: string }> {
  const r = await fetch(`${apiBase()}/api/cache/export-to`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, dir, name: name || '' }),
  })
  if (!r.ok) {
    const text = await r.text().catch(() => '')
    throw new Error(text || `HTTP ${r.status}`)
  }
  return (await r.json()) as { ok?: boolean; path?: string; format?: string; bytes?: number }
}

/**
 * 批量缓存整季。
 *
 * 一次请求起多集，而不是前端循环调用——后者每集一个往返，
 * 且前端要自己算并发度；后端已经有 4 路并行分片下载了。
 */
export async function cacheBatch(items: Array<{
  site: string
  vodId: string
  ep: string
  name: string
  url: string
}>): Promise<{ ok: boolean; startedCount: number; skipped: Array<{ ep: string; reason: string }> }> {
  const r = await fetch(`${apiBase()}/api/cache`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items }),
  })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return (await r.json()) as {
    ok: boolean
    startedCount: number
    skipped: Array<{ ep: string; reason: string }>
  }
}

// ---------------------------------------------------------------- 整季批量 / 续传

/** 批次里的一集。跟单集 CacheTask 同形，但 `resumable` 由批次端点额外给出。 */
export interface BatchItem {
  id: string
  ep: string
  name?: string
  state: 'idle' | 'running' | 'done' | 'failed' | 'canceled'
  percent?: number
  done?: number
  total?: number
  bytes?: number
  error?: string
  resumable?: boolean
}

/** `GET /api/cache/status?batch=<id>` 的返回 */
export interface BatchStatus {
  ok: boolean
  batch: string
  site?: string
  vodId?: string
  /** running = 还有没跑完的；partial = 有失败也有成功；failed = 全失败 */
  state: 'running' | 'done' | 'partial' | 'failed'
  total: number
  done: number
  failed: number
  /** 还没跑完的集数（后端字段名就叫 pending，不是"待开始"） */
  pending: number
  /** 各集百分比的平均值 */
  percent: number
  bytes: number
  items: BatchItem[]
}

export interface BatchStartResult {
  ok: boolean
  batch: string
  site?: string
  vodId?: string
  ids: string[]
  count: number
  /** 校验没过的集（缺 url / 地址不被允许），不中断整批 */
  skipped: Array<{ ep: string; reason: string }>
  items: Array<{ id: string; ep: string; state: string }>
  statusUrl?: string
  /** 一集都不合格时后端会带 ok:false + 这句 */
  msg?: string
}

/**
 * 整季批量下载（`POST /api/cache/batch`）。
 *
 * ★ 不要退回用 `cacheBatch()`（那是 `POST /api/cache` 的 items 分支）：
 * 旧分支一次性把每集都 start，整季 20 集 = 80 条连接打同一源站，实测 429/断流。
 * 新端点走全局固定 2 线程池（跨批次共享），一集下完再起下一集。
 */
export async function startBatchCache(input: {
  site: string
  vodId: string
  items: Array<{ ep: string; name?: string; url: string }>
}): Promise<BatchStartResult> {
  return request<BatchStartResult>('/api/cache/batch', {
    method: 'POST',
    body: input,
    // 每集只做校验与入队，不起下载线程，正常是一瞬间；给 30 秒防后端忙
    timeout: 30_000,
  })
}

/** 批次聚合进度。不带 id 时返回全部批次概览。 */
export async function fetchBatchStatus(batch: string): Promise<BatchStatus> {
  const q = new URLSearchParams({ batch })
  return request<BatchStatus>(`/api/cache/status?${q.toString()}`)
}

export async function fetchBatchList(): Promise<BatchStatus[]> {
  const r = await request<{ ok: boolean; batches?: BatchStatus[] }>('/api/cache/status')
  return r.batches || []
}

/**
 * 未完成的任务（含"重启前正在下"与"失败但可续"的）。
 *
 * 后端的判定：state 是 running / idle，或 failed && resumable。
 * 重启时还在 running 的会被改判 failed + error="backend restarted" ——
 * 刻意没有 interrupted 状态，所以前端按"可续"筛就够了。
 */
export interface UnfinishedTask {
  id: string
  site?: string
  vodId?: string
  ep?: string
  name?: string
  state: string
  percent?: number
  done?: number
  total?: number
  bytes?: number
  error?: string
  batchId?: string
  resumable?: boolean
  /** 目录里是否已经有分片（决定"继续下载"是不是真能省下已下的部分） */
  hasSegments?: boolean
  updatedAt?: number
}

export interface CacheTasksResult {
  ok: boolean
  items: UnfinishedTask[]
  cacheDir?: string
  cacheDirReloadRequired?: boolean
}

export async function fetchCacheTasks(): Promise<CacheTasksResult> {
  return request<CacheTasksResult>('/api/cache/tasks')
}

/**
 * 续传。粒度是**分片**而不是任务游标：后端在下载每个分片前检查文件存在且非空，
 * 存在的直接跳过，所以"从第一个缺失分片继续"天然成立。
 */
export async function resumeCacheTasks(ids: string[]): Promise<{
  ok: boolean
  resumed: CacheTask[]
  failed: Array<{ id: string; reason: string }>
}> {
  return request('/api/cache/resume', { method: 'POST', body: { ids }, timeout: 30_000 })
}



export interface CastDevice {
  name: string
  host: string
  location: string
  /** AVTransport 控制地址。后端不缓存设备表，后续操作要带回来。 */
  controlUrl: string
  /** ConnectionManager 控制地址（能力预判用，可能没有）。 */
  connControlUrl?: string
  /** 设备稳定标识，跨重启不变。 */
  udn?: string
  type?: string
  /** dlna=标准渲染器；meow=装了我们的设备（走会话 API）。 */
  kind?: 'dlna' | 'meow'
  /** meow 设备基地址（http://ip:port）。 */
  base?: string
  /** meow 设备一次性令牌。 */
  token?: string
}

/** 发现局域网里的 DLNA 渲染器。 */
export async function castDiscover(timeoutMs = 2500): Promise<CastDevice[]> {
  try {
    const r = await fetch(`${apiBase()}/api/cast?timeout=${timeoutMs}`)
    if (!r.ok) return []
    const j = (await r.json()) as { devices?: CastDevice[] }
    return j.devices || []
  } catch {
    return []
  }
}

/** 推送播放 / 暂停 / 停止 / 拖进度。 */
export async function castControl(
  action: 'play' | 'pause' | 'stop' | 'seek',
  device: CastDevice,
  extra?: { url?: string; title?: string; seconds?: number },
): Promise<{ ok: boolean; msg?: string; code?: string }> {
  try {
    const r = await fetch(`${apiBase()}/api/cast`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action, device, ...extra }),
    })
    if (!r.ok) return { ok: false, msg: `HTTP ${r.status}` }
    return (await r.json()) as { ok: boolean; msg?: string; code?: string }
  } catch (e) {
    return { ok: false, msg: e instanceof Error ? e.message : String(e) }
  }
}

/** 接收端当前状态；position/duration 是**秒**。 */
export interface CastStatus {
  ok: boolean
  state?: string
  position?: number
  duration?: number
  msg?: string
}

/**
 * 轮询接收端状态（等价于 GetTransportInfo + GetPositionInfo）。
 *
 * <p>不用事件订阅：投屏场景 1.5s 一次足够，且 TV 的 Chrome 66 长连接会被固件杀。
 */
export async function castStatus(device: CastDevice): Promise<CastStatus> {
  try {
    const r = await fetch(`${apiBase()}/api/cast`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'status', device }),
    })
    if (!r.ok) return { ok: false, msg: `HTTP ${r.status}` }
    return (await r.json()) as CastStatus
  } catch (e) {
    return { ok: false, msg: e instanceof Error ? e.message : String(e) }
  }
}

/** 接收端支持的 MIME（ConnectionManager.GetProtocolInfo）；空数组=设备没提供。 */
export async function castProtocols(device: CastDevice): Promise<string[]> {
  try {
    const r = await fetch(`${apiBase()}/api/cast`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'protocols', device }),
    })
    if (!r.ok) return []
    const j = (await r.json()) as { ok?: boolean; sink?: string[] }
    return j.sink || []
  } catch {
    return []
  }
}

// ---------------------------------------------------------------- B 类：会话投屏

/** 推给"装了我们的设备"的媒体描述。position/duration 单位秒。 */
export interface CastMedia {
  url: string
  title?: string
  poster?: string
  site?: string
  vodId?: string
  ep?: string
  danmakuUrl?: string
  position?: number
  duration?: number
}

function meowUrl(dev: CastDevice, path: string, extra = ''): string {
  const base = (dev.base || '').replace(/\/$/, '')
  const ct = dev.token ? `ct=${encodeURIComponent(dev.token)}` : ''
  const q = [ct, extra].filter(Boolean).join('&')
  return `${base}${path}${q ? '?' + q : ''}`
}

export type CastSenderKind = 'pc' | 'tv' | 'web'

/** 投屏来源身份：接收端用它显示"谁投过来的"，配合 remote IP 唯一区分设备。 */
export interface CastSender {
  name: string
  kind: CastSenderKind
  /** TV 的厂商 + 型号，Web/PC 为空 */
  model?: string
}

/**
 * 判断当前投屏来源是 PC / TV / Web，并给出可读名称。
 *
 * <p>TV 壳有 `window.meowTv.platform()`，设备名取 `deviceName()`（Android
 * Build.MANUFACTURER + MODEL）；PC 是 Tauri 壳；其余一律按 Web 处理。
 * 名称可以被 `localStorage['meow.cast.senderName']` 覆盖（多设备同名时用）。
 */
export function castSenderIdentity(): CastSender {
  let custom = ''
  try {
    custom = (localStorage.getItem('meow.cast.senderName') || '').trim()
  } catch {
    /* 隐私模式可能禁用 localStorage */
  }
  try {
    const tv = (
      window as unknown as {
        meowTv?: { platform?: () => string; deviceName?: () => string }
      }
    ).meowTv
    if (tv && typeof tv.platform === 'function' && tv.platform() === 'tv') {
      const model =
        typeof tv.deviceName === 'function' ? String(tv.deviceName() || '').trim() : ''
      return { kind: 'tv', name: custom || model || 'TV', model }
    }
  } catch {
    /* 旧 WebView 访问未定义属性本身会抛 */
  }
  if (isDesktop()) return { kind: 'pc', name: custom || 'PC' }
  return { kind: 'web', name: custom || 'Web' }
}

/** 建/换会话（接收端会立刻打开媒体）。 */
export async function castSessionPlay(
  device: CastDevice,
  media: CastMedia,
  sender?: CastSender,
): Promise<{ ok: boolean; sessionId?: string; state?: string; msg?: string }> {
  try {
    const r = await fetch(meowUrl(device, '/api/cast/session'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ media, sender: sender || castSenderIdentity() }),
    })
    if (!r.ok) return { ok: false, msg: `HTTP ${r.status}` }
    return (await r.json()) as { ok: boolean; sessionId?: string; state?: string; msg?: string }
  } catch (e) {
    return { ok: false, msg: e instanceof Error ? e.message : String(e) }
  }
}

/** 轮询接收端会话状态。 */
export async function castSessionStatus(
  device: CastDevice,
  sessionId: string,
): Promise<CastStatus> {
  try {
    const r = await fetch(meowUrl(device, '/api/cast/session', `id=${encodeURIComponent(sessionId)}`))
    if (!r.ok) return { ok: false, msg: `HTTP ${r.status}` }
    const j = (await r.json()) as CastStatus & { position?: number; duration?: number }
    return j
  } catch (e) {
    return { ok: false, msg: e instanceof Error ? e.message : String(e) }
  }
}

/** 遥控：play / pause / seek / stop。 */
export async function castSessionCommand(
  device: CastDevice,
  sessionId: string,
  action: 'play' | 'pause' | 'seek' | 'stop',
  value?: number,
): Promise<{ ok: boolean; msg?: string }> {
  try {
    const r = await fetch(meowUrl(device, '/api/cast/command'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId, action, value }),
    })
    if (!r.ok) return { ok: false, msg: `HTTP ${r.status}` }
    return (await r.json()) as { ok: boolean; msg?: string }
  } catch (e) {
    return { ok: false, msg: e instanceof Error ? e.message : String(e) }
  }
}

// ---------------------------------------------------------------- 接收端开关（PC/TV 本机）

export interface ReceiverInfo {
  ok: boolean
  running: boolean
  port?: number
  name?: string
  udn?: string
  state?: string
  sessionId?: string
  msg?: string
}

export async function fetchReceiver(): Promise<ReceiverInfo | null> {
  try {
    const r = await fetch(`${apiBase()}/api/cast/receiver`)
    if (!r.ok) return null
    return (await r.json()) as ReceiverInfo
  } catch {
    return null
  }
}

export async function setReceiver(enabled: boolean, name?: string): Promise<ReceiverInfo | null> {
  try {
    const body: Record<string, unknown> = { enabled }
    if (typeof name === 'string') body.name = name
    const r = await fetch(`${apiBase()}/api/cast/receiver`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (!r.ok) return null
    return (await r.json()) as ReceiverInfo
  } catch {
    return null
  }
}

export interface ReceiverPending {
  ok: boolean
  sessionId?: string
  state?: string
  media?: CastMedia
  position?: number
  duration?: number
  sender?: string
}

/** PC 网页轮询：有没有人投到我。 */
export async function receiverPending(): Promise<ReceiverPending | null> {
  try {
    const r = await fetch(`${apiBase()}/api/cast/receiver/pending`)
    if (!r.ok) return null
    return (await r.json()) as ReceiverPending
  } catch {
    return null
  }
}

/** 已缓存那一集的本地清单地址（断网/优先本地时用它）。 */
export function cachedPlaylist(id: string): string {
  const b = apiBase()
  return `${b}/api/cache/${id}/playlist.m3u8`
}

/**
 * 缓存 id 与后端算法一致：md5(site|vodId|ep) 前 16 位。
 *
 * 前端也算一份是为了「进播放页时先看本地有没有」不额外打一次网络。
 * 两边都改才生效——这里刻意做成同步的纯函数。
 */
export function cacheIdFor(site: string, vodId: string, ep: string): string {
  // 轻量 md5：只用于生成 key，不做安全用途
  const str = `${site}|${vodId}|${ep}`
  return md5Hex(str).slice(0, 16)
}

function md5Hex(input: string): string {
  // 标准 MD5（RFC 1321）纯 JS 实现：不能用 SubtleCrypto，它是异步的
  function cmn(q: number, a: number, b: number, x: number, s: number, t: number) {
    a = (((a + q) | 0) + ((x + t) | 0)) | 0
    return (((a << s) | (a >>> (32 - s))) + b) | 0
  }
  function ff(a: number, b: number, c: number, d: number, x: number, s: number, t: number) {
    return cmn((b & c) | (~b & d), a, b, x, s, t)
  }
  function gg(a: number, b: number, c: number, d: number, x: number, s: number, t: number) {
    return cmn((b & d) | (c & ~d), a, b, x, s, t)
  }
  function hh(a: number, b: number, c: number, d: number, x: number, s: number, t: number) {
    return cmn(b ^ c ^ d, a, b, x, s, t)
  }
  function ii(a: number, b: number, c: number, d: number, x: number, s: number, t: number) {
    return cmn(c ^ (b | ~d), a, b, x, s, t)
  }
  function cycle(x: number[], k: number[]) {
    let a = k[0]
    let b = k[1]
    let c = k[2]
    let d = k[3]
    a = ff(a, b, c, d, x[0], 7, -680876936)
    d = ff(d, a, b, c, x[1], 12, -389564586)
    c = ff(c, d, a, b, x[2], 17, 606105819)
    b = ff(b, c, d, a, x[3], 22, -1044525330)
    a = ff(a, b, c, d, x[4], 7, -176418897)
    d = ff(d, a, b, c, x[5], 12, 1200080426)
    c = ff(c, d, a, b, x[6], 17, -1473231341)
    b = ff(b, c, d, a, x[7], 22, -45705983)
    a = ff(a, b, c, d, x[8], 7, 1770035416)
    d = ff(d, a, b, c, x[9], 12, -1958414417)
    c = ff(c, d, a, b, x[10], 17, -42063)
    b = ff(b, c, d, a, x[11], 22, -1990404162)
    a = ff(a, b, c, d, x[12], 7, 1804603682)
    d = ff(d, a, b, c, x[13], 12, -40341101)
    c = ff(c, d, a, b, x[14], 17, -1502002290)
    b = ff(b, c, d, a, x[15], 22, 1236535329)
    a = gg(a, b, c, d, x[1], 5, -165796510)
    d = gg(d, a, b, c, x[6], 9, -1069501632)
    c = gg(c, d, a, b, x[11], 14, 643717713)
    b = gg(b, c, d, a, x[0], 20, -373897302)
    a = gg(a, b, c, d, x[5], 5, -701558691)
    d = gg(d, a, b, c, x[10], 9, 38016083)
    c = gg(c, d, a, b, x[15], 14, -660478335)
    b = gg(b, c, d, a, x[4], 20, -405537848)
    a = gg(a, b, c, d, x[9], 5, 568446438)
    d = gg(d, a, b, c, x[14], 9, -1019803690)
    c = gg(c, d, a, b, x[3], 14, -187363961)
    b = gg(b, c, d, a, x[8], 20, 1163531501)
    a = gg(a, b, c, d, x[13], 5, -1444681467)
    d = gg(d, a, b, c, x[2], 9, -51403784)
    c = gg(c, d, a, b, x[7], 14, 1735328473)
    b = gg(b, c, d, a, x[12], 20, -1926607734)
    a = hh(a, b, c, d, x[5], 4, -378558)
    d = hh(d, a, b, c, x[8], 11, -2022574463)
    c = hh(c, d, a, b, x[11], 16, 1839030562)
    b = hh(b, c, d, a, x[14], 23, -35309556)
    a = hh(a, b, c, d, x[1], 4, -1530992060)
    d = hh(d, a, b, c, x[4], 11, 1272893353)
    c = hh(c, d, a, b, x[7], 16, -155497632)
    b = hh(b, c, d, a, x[10], 23, -1094730640)
    a = hh(a, b, c, d, x[13], 4, 681279174)
    d = hh(d, a, b, c, x[0], 11, -358537222)
    c = hh(c, d, a, b, x[3], 16, -722521979)
    b = hh(b, c, d, a, x[6], 23, 76029189)
    a = hh(a, b, c, d, x[9], 4, -640364487)
    d = hh(d, a, b, c, x[12], 11, -421815835)
    c = hh(c, d, a, b, x[15], 16, 530742520)
    b = hh(b, c, d, a, x[2], 23, -995338651)
    a = ii(a, b, c, d, x[0], 6, -198630844)
    d = ii(d, a, b, c, x[7], 10, 1126891415)
    c = ii(c, d, a, b, x[14], 15, -1416354905)
    b = ii(b, c, d, a, x[5], 21, -57434055)
    a = ii(a, b, c, d, x[12], 6, 1700485571)
    d = ii(d, a, b, c, x[3], 10, -1894986606)
    c = ii(c, d, a, b, x[10], 15, -1051523)
    b = ii(b, c, d, a, x[1], 21, -2054922799)
    a = ii(a, b, c, d, x[8], 6, 1873313359)
    d = ii(d, a, b, c, x[15], 10, -30611744)
    c = ii(c, d, a, b, x[6], 15, -1560198380)
    b = ii(b, c, d, a, x[13], 21, 1309151649)
    a = ii(a, b, c, d, x[4], 6, -145523070)
    d = ii(d, a, b, c, x[11], 10, -1120210379)
    c = ii(c, d, a, b, x[2], 15, 718787259)
    b = ii(b, c, d, a, x[9], 21, -343485551)
    const out = [a, b, c, d]
    for (let i = 0; i < 4; i++) k[i] = (k[i] + out[i]) | 0
  }
  const words: number[] = []
  const len = input.length
  for (let i = 0; i < len; i++) {
    words[i >> 2] = (words[i >> 2] || 0) | ((input.charCodeAt(i) & 0xff) << ((i % 4) * 8))
  }
  words[len >> 2] = (words[len >> 2] || 0) | (0x80 << ((len % 4) * 8))
  const n = ((len + 8) >> 6) + 1
  const x: number[] = new Array(n * 16).fill(0)
  for (let i = 0; i < words.length; i++) x[i] = words[i] | 0
  x[n * 16 - 2] = len * 8
  const k = [1732584193, -271733879, -1732584194, 271733878]
  for (let i = 0; i < x.length; i += 16) cycle(x.slice(i, i + 16), k)
  const hex = '0123456789abcdef'
  let s = ''
  for (let i = 0; i < 4; i++) {
    for (let j = 0; j < 4; j++) {
      const byte = (k[i] >> (j * 8)) & 0xff
      s += hex[(byte >> 4) & 0xf] + hex[byte & 0xf]
    }
  }
  return s
}
