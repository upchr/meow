/**
 * 媒体票（auth v2）。
 *
 * <p>浏览器 `<video>`、TV WebView、ExoPlayer 都无法可靠带自定义 header，
 * 所以播放地址用短时 `?mt=`。浏览器同源也可以靠 Cookie，但统一走票更少分支。
 *
 * <p>缓存 15 分钟（后端默认 30 分钟）——**留一半余量**：以前是 20 分钟，
 * 后端重启后旧票立即失效而前端还在用，海报全变「点击重试」、弹幕空白，
 * 用户以为是网络问题（2026-11-20 复核时一并核出）。现在有三层自愈：
 * 缓存留余量、`<img>` 失败时刷新重试一次、切回前台时补刷。
 *
 * <p>**未开鉴权**的部署会 401：失败后 60 秒内不再尝试（`disabledUntil`），
 * 否则每张海报都会打一次 401。
 */
import { request, setMediaTicket } from './index'

let disabledUntil = 0
const issuedAt = new Map<string, number>()
/** 同一 key 的取票请求合并：20 张海报同时 401 只发一次 */
const inflight = new Map<string, Promise<string>>()
const TTL_MS = 15 * 60 * 1000

function fresh(key: string): boolean {
  const t = issuedAt.get(key) || 0
  return t > 0 && Date.now() - t < TTL_MS
}

async function doIssue(key: string, body: Record<string, unknown>): Promise<string> {
  if (Date.now() < disabledUntil) return ''
  if (fresh(key)) return ''
  try {
    const r = await request<{ ok: boolean; mt: string }>('/api/stream/ticket', {
      method: 'POST',
      body,
    })
    if (!r?.mt) return ''
    const kind = String(body.kind) === 'library' ? 'library' : 'proxy'
    const lib = kind === 'library' ? String(body.lib || '') : ''
    setMediaTicket(kind, r.mt, lib)
    issuedAt.set(key, Date.now())
    return r.mt
  } catch (e) {
    const status = (e as { status?: number }).status
    if (status === 401 || status === 403) disabledUntil = Date.now() + 60_000
    return ''
  }
}

function issue(key: string, body: Record<string, unknown>): Promise<string> {
  const running = inflight.get(key)
  if (running) return running
  const p = doIssue(key, body).finally(() => inflight.delete(key))
  inflight.set(key, p)
  return p
}

/** 播放前调用；失败不影响后续（未开鉴权时会 401，直接忽略）。 */
export async function ensureProxyTicket(): Promise<string> {
  return issue('proxy:', { kind: 'proxy', urls: [] })
}

/** 片库播放前调用；票按库作用域。 */
export async function ensureLibraryTicket(lib: string): Promise<string> {
  if (!lib) return ''
  return issue(`library:${lib}`, { kind: 'library', lib, path: '' })
}

/**
 * 票可能失效时调用（海报/弹幕 401、切回前台）：只清时间戳，下次 ensure 重取。
 *
 * <p>`disabledUntil` 故意不清 —— 未开鉴权的部署本来就没有票体系，
 * 清了会让每张海报失败都打一次 401。后端重启的场景里取票本身是成功的
 * （200），`disabledUntil` 一直是 0，不受影响。
 */
export function refreshMediaTickets(): void {
  issuedAt.clear()
}

/** 登录/退出后清空，避免拿旧票。 */
export function resetMediaTickets(): void {
  issuedAt.clear()
  inflight.clear()
  disabledUntil = 0
}
