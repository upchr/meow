import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 片库播放链路的门禁（2026-11-02）。
 *
 * 起因：用户报「tv 本地片库除了武林外传，其他的无法播放」。
 * 根因不是编码不支持，而是**片库页按文件名预判"MKV/H265 必须转码"**，
 * 而 TV 本地网关没有 ffmpeg（PC 插件与 Docker 镜像里才有）。
 * 实测 WebView 110 能直接播 6 个真实文件里的 5 个（含 MKV 4K、H265 4K）。
 *
 * 现在策略是「先直连、失败再转码」。这几条断言锁住三件事：
 *   ① 片库页不再按扩展名拦截
 *   ② 播放页的片子库转码兜底用的是 lib+path，不是流地址
 *   ③ `libraryDirect` / `isLibraryPlay` 是响应式的（否则连点两集还播上一集）
 */
const WEB = join(__dirname, '..')
const LIB = readFileSync(join(WEB, 'src', 'views', 'LibraryView.vue'), 'utf8')
const PLAY = readFileSync(join(WEB, 'src', 'views', 'PlayView.vue'), 'utf8')

function codeOnly(s: string): string {
  return s
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
}

describe('无声兜底：no-audio 命中后的转码分流（2026-11-21）', () => {
  const playSrc = PLAY // PLAY 已是 PlayView.vue 的内容（文件头 readFileSync 过）

  it('① 点播直链 no-audio → 能力预检后走 src 式转码', () => {
    expect(playSrc).toContain('void ensureTranscodeProbe().then((ok) =>')
    expect(playSrc).toContain('void tryTranscode(u)')
  })

  it('② WebDAV 片库（direct 是外网 src）→ 回退点播式 src 转码', () => {
    expect(playSrc).toContain("!direct.includes('/api/library/stream')")
    expect(playSrc).toContain('void tryTranscode(direct)')
  })

  it('③ Safari 能力表（AC3/E-AC3 可直解）进决策', () => {
    expect(playSrc).toContain('webviewAudioCodecs()')
  })
})

describe('片库播放：先直连，失败才转码', () => {
  it('片库页的 playOrTranscode 不再按扩展名拦截', () => {
    const code = codeOnly(LIB)
    const i = code.indexOf('async function playOrTranscode')
    expect(i).toBeGreaterThan(-1)
    const body = code.slice(i, i + 400)
    // 直接播，不再先走 waitLibraryTranscode
    expect(body).toContain('play(f.path)')
    expect(body).not.toContain('waitLibraryTranscode')
    expect(body).not.toContain('needsTranscode')
  })

  it('不该再存在"mkv 一律转码"的硬门禁函数', () => {
    const code = codeOnly(LIB)
    expect(code).not.toContain('function needsTranscode')
    // 弱提示可以留（只影响 title 文案）
    expect(code).toContain('looksHeavy')
  })
})

describe('片库转码兜底：必须用 lib+path', () => {
  it('播放页有 tryLibraryTranscode，且参数是 lib + file', () => {
    const code = codeOnly(PLAY)
    expect(code).toContain('async function tryLibraryTranscode')
    const i = code.indexOf('async function tryLibraryTranscode')
    /*
     * 切片要够长：函数开头的守卫 + 注释会随迭代增长（2026-11-27 加能力预检后
     * 800 字符已经够不到 await 那行，断言变成"测试红但代码对"）。
     */
    const body = code.slice(i, i + 2000)
    expect(body).toContain('waitLibraryTranscode(lib, file')
  })

  it('片库播不了时走片库转码，而不是 URL 转码', () => {
    const code = codeOnly(PLAY)
    const i = code.indexOf('transcodeTried && isLibraryPlay')
    expect(i).toBeGreaterThan(-1)
    const body = code.slice(i, i + 400)
    expect(body).toContain('tryLibraryTranscode')
  })

  it('ffmpeg 缺失时给出可执行建议（不能只报"找不到 ffmpeg"）', () => {
    const code = codeOnly(PLAY)
    const i = code.indexOf('async function tryLibraryTranscode')
    const body = code.slice(i, i + 2600)
    // 2026-11-20 起文案收口到 library.ts 的 transcodeErrorMessage（按后端 code 映射），
    // PlayView 只负责把结果渲染出来，所以这里锁"委托"而不是锁具体句子。
    expect(body).toContain('transcodeErrorMessage(r)')
    const lib = readFileSync(join(WEB, 'src', 'api', 'library.ts'), 'utf8')
    const j = lib.indexOf("case 'no_ffmpeg'")
    expect(j).toBeGreaterThan(-1)
    const msg = lib.slice(j, j + 300)
    expect(msg).toContain('ffmpeg')
    // 要指出"换 PC/OECT 后端"
    expect(msg).toMatch(/PC|OECT|后端/)
  })
})

describe('片库播放参数必须响应式（同路由只变 query）', () => {
  it('libraryDirect 是 computed（App.vue 的 key 是 route.path，切集不重建组件）', () => {
    const code = codeOnly(PLAY)
    expect(code).toContain('const libraryDirect = computed(')
    expect(code).toContain('const isLibraryPlay = computed(')
    expect(code).toContain('const libraryLib = computed(')
    expect(code).toContain('const libraryFile = computed(')
  })

  it('★ computed 在脚本里必须 .value（TS 不会报"恒为真"这类错）', () => {
    // 只看 <script setup> 段：模板里的引用本来就不带 .value（2026-11-21，
    // 播放器新增 :no-audio-fallback="isLibraryPlay" 时这条断言被模板误伤）
    const script = PLAY.match(/<script setup[^>]*>([\s\S]*?)<\/script>/)?.[1] ?? ''
    const code = codeOnly(script)
    // 除定义行外，所有 isLibraryPlay 引用都必须带 .value
    const refs = code.match(/isLibraryPlay(?!\.value)/g) || []
    // 只允许出现在定义处（const isLibraryPlay = computed...）
    const defs = code.match(/const isLibraryPlay = computed/g) || []
    expect(refs.length).toBe(defs.length)
  })
})
