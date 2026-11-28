import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 安全修复门禁（2026-10-30 安全审计）。
 *
 * 这里锁的每一条都对应一个**实测复现过**的漏洞。它们共同的特点是
 * "删掉一行代码就悄悄退化，而且不影响任何正常功能" —— 只有真机被利用
 * 或者有人重新审一遍才会发现。所以必须用机器守住。
 */
const REPO = join(__dirname, '..', '..', '..', '..')

function read(rel: string): string {
  return readFileSync(join(REPO, rel), 'utf8')
}

/** 去掉注释，避免注释里描述"原来有这个问题"把断言骗过去。 */
function codeOnly(s: string): string {
  return s
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
    .replace(/^\s*\*.*$/gm, '')
}

describe('凭据不进备份（审计发现的关键缺陷）', () => {
  const prefs = codeOnly(read('client/apps/web/src/stores/prefs.ts'))

  it('备份里有敏感字段黑名单，且包含 llmApiKey', () => {
    expect(prefs).toContain('SECRET_PREF_KEYS')
    expect(prefs).toContain("'llmApiKey'")
  })

  it('makeBackup 必须走脱敏，不能直接 getPrefs()', () => {
    expect(prefs).toContain('redactPrefs(getPrefs())')
    expect(prefs).not.toMatch(/prefs:\s*getPrefs\(\),/)
  })

  it('applyBackup 不采纳备份里的 Key（外面流转过的文件不可信）', () => {
    const i = prefs.indexOf('export function applyBackup')
    expect(i).toBeGreaterThan(-1)
    const body = prefs.slice(i, i + 1500)
    expect(body).toContain('llmApiKey')
  })
})

describe('drpy 不再允许远程代码执行', () => {
  const server = codeOnly(read('server/drpy/server.js'))
  const host = codeOnly(read('server/drpy/host.js'))

  it('默认只绑 127.0.0.1，不再通配', () => {
    expect(server).toContain("process.env.DRPY_BIND || '127.0.0.1'")
    expect(server).not.toContain("listen(PORT, '0.0.0.0'")
  })

  it('请求要先过 authorized()', () => {
    expect(server).toContain('function authorized(')
    expect(server).toContain('if (!authorized(req))')
  })

  it('沙箱里不能再有 __req（那等于任意 require）', () => {
    expect(host).not.toContain('sandbox.__req = requireModule')
  })

  it('裸模块名走白名单，且不含危险模块', () => {
    expect(host).toContain('ALLOWED_BARE_MODULES')
    const i = host.indexOf('const ALLOWED_BARE_MODULES')
    const block = host.slice(i, i + 700)
    for (const bad of ['child_process', 'fs', 'net', 'vm']) {
      expect(block, `白名单里不能有 ${bad}`).not.toMatch(new RegExp(`['"]${bad}['"]`))
    }
  })
})

describe('PC MCP 不再对任意网页开放', () => {
  const mcp = codeOnly(read('client/apps/desktop/src-tauri/src/mcp.rs'))

  it('不下发 Access-Control-Allow-Origin: *', () => {
    expect(mcp).not.toContain('Access-Control-Allow-Origin: *')
    expect(mcp).toContain('fn origin_trusted')
  })

  it('不受信任的 Origin 直接拒绝', () => {
    expect(mcp).toContain('origin not allowed')
    expect(mcp).toContain('fn write_response_with_origin')
  })

  it('强制 application/json（挡住 text/plain 简单请求）', () => {
    expect(mcp).toContain('application/json')
    expect(mcp).toContain('415')
  })
})

describe('后端路径与跨源', () => {
  const api = codeOnly(read('server/host/src/shared/ApiServer.java'))
  const cache = codeOnly(read('server/host/src/shared/core/VideoCache.java'))
  const trans = codeOnly(read('server/host/src/shared/core/Transcode.java'))

  it('fileOf 拒盘符（Windows 上 P:xxx 是盘符相对路径）', () => {
    for (const src of [cache, trans]) {
      const i = src.indexOf('public static Path fileOf')
      expect(i).toBeGreaterThan(-1)
      const body = src.slice(i, i + 900)
      expect(body).toContain("indexOf(':')")
      expect(body).toContain('startsWith')
    }
  })

  it('policy GET 不回 authToken', () => {
    expect(api).toContain('p.remove("authToken")')
  })

  it('跨源写请求被拒', () => {
    expect(api).toContain('originAllowed(ex)')
    expect(api).toContain('cross-origin write blocked')
  })

  it('写端点鉴权列表包含之前漏掉的六个', () => {
    const i = api.indexOf('private static boolean writeRequiresAuth')
    const body = api.slice(i, i + 1400)
    for (const p of ['/api/llm/relay', '/api/cast', '/api/logs', '/api/posters', '/api/scrape', '/api/search/cache']) {
      expect(body, `writeRequiresAuth 漏了 ${p}`).toContain(p)
    }
  })

  it('TLS 主机名校验没有被自定义 verifier 关掉', () => {
    // "调默认 verifier 然后 return true"都是在关校验
    expect(api).not.toMatch(/getDefaultHostnameVerifier\(\)[\s\S]{0,120}return true/)
    // Android 9 起 endpointIdentificationAlgorithm 的 peerHost 是连接 IP，
    // 与证书 SAN 必然不匹配（No subjectAltNames match）→ 主机名校验改为
    // 显式 verifierFor：捕获原域名 + certMatchesHost（RFC 2818 SAN 规则）。
    // 门禁锁「SAN 匹配逻辑还在」，不再锁那行算法设置。
    expect(api).toContain('static javax.net.ssl.HostnameVerifier verifierFor(')
    expect(api).toContain('private static boolean certMatchesHost(')
  })
})
