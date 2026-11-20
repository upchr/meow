import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 公网部署 v2：媒体票与登录守卫的契约。
 *
 * 这些都是"少一行就静默失效"的点：票没拼进 URL → 播放 401；
 * 守卫漏了 setupRequired → 首启直接进首页、发一堆 401。
 */
const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = join(HERE, '..', 'src')
/** 仓库根：web/tests → web → apps → client → root */
const REPO = join(HERE, '..', '..', '..', '..')

function src(rel: string): string {
  return readFileSync(join(ROOT, rel), 'utf8')
}

/** 读后端源码（契约测试要同时锁前后端两侧） */
function repo(rel: string): string {
  return readFileSync(join(REPO, rel), 'utf8')
}

describe('媒体票拼接（auth v2）', () => {
  beforeEach(() => {
    vi.resetModules()
    localStorage.clear()
  })

  it('proxyStream 带上缓存里的 proxy 票', async () => {
    const api = await import('../src/api/index')
    api.setMediaTicket('proxy', 'TICKET123')
    const u = api.proxyStream('https://cdn.example.com/a.m3u8')
    expect(u).toContain('mt=TICKET123')
  })

  it('libraryStream 按库取票，不串到别的库', async () => {
    const api = await import('../src/api/index')
    const lib = await import('../src/api/library')
    api.setMediaTicket('library', 'LIBTICKET', 'lib-a')
    expect(lib.libraryStream('lib-a', '电影/a.mkv')).toContain('mt=LIBTICKET')
    expect(lib.libraryStream('lib-b', '电影/a.mkv')).not.toContain('mt=')
  })
})

describe('播放前置取票（少一行就 401）', () => {
  it('播放页在 load 里先取票', () => {
    const s = src('views/PlayView.vue')
    const i = s.indexOf('async function load()')
    expect(i).toBeGreaterThan(0)
    const block = s.slice(i, i + 1200)
    expect(block).toContain('ensureProxyTicket()')
    expect(block).toContain('ensureLibraryTicket(')
  })

  it('直播换台先取票再拼代理地址', () => {
    const s = src('views/LiveView.vue')
    const i = s.indexOf('async function switchUrl')
    expect(i).toBeGreaterThan(0)
    const block = s.slice(i, i + 900)
    expect(block.indexOf('await ensureProxyTicket()')).toBeGreaterThan(0)
    expect(block.indexOf('await ensureProxyTicket()')).toBeLessThan(block.indexOf('proxyStream(url)'))
  })

  it('片库进库就预取票（下载是同步 click，不能临时 await）', () => {
    const s = src('views/LibraryView.vue')
    expect(s).toContain("watch(\n  currentLib,")
    expect(s).toContain('void ensureLibraryTicket(lib)')
  })

  it('未开鉴权的部署不请求票，且票 401 不跳登录（2026-11-19 复查回归）', () => {
    const play = src('views/PlayView.vue')
    expect(play).toContain('if (authRequired.value) {')
    const api = src('api/index.ts')
    expect(api).toContain("!path.startsWith('/api/stream/ticket')")
  })
})

describe('设备令牌是响应式的（2026-11-19 实测踩过）', () => {
  it('存令牌后 needToken 立刻变 false，不再被守卫弹回登录页', async () => {
    vi.resetModules()
    localStorage.clear()
    const st = await import('../src/stores/auth')
    st.authMode.value = 'token-only'
    st.setDeviceToken('')
    expect(st.needToken.value).toBe(true)
    st.setDeviceToken('tok-1')
    // computed 若直接读 localStorage，这里仍是 true —— 因为 localStorage 不是响应式的
    expect(st.needToken.value).toBe(false)
  })
})

describe('设置页登录守卫', () => {
  it('setup / 密码模式 / 纯令牌三种情况都会被拦到 /login', () => {
    const s = src('router/index.ts')
    expect(s).toContain('router.beforeEach')
    expect(s).toContain('setupRequired.value')
    expect(s).toContain("authMode.value === 'password' && !username.value")
    expect(s).toContain('needToken.value')
  })

  it('账号入口在「连接」区：密码模式给按钮 + 弹框，不占独立分区', () => {
    const s = src('views/SettingsView.vue')
    // 入口按钮：仅密码模式（token-only 没有会话，点开全是 401）
    expect(s).toContain("authRequired && authMode === 'password'")
    expect(s).toContain('accountOpen = true')
    // 弹框挂载与独立分区移除
    expect(s).toContain('<AccountDialog v-if="accountOpen"')
    expect(s).not.toContain("id: 'account'")
    expect(s).not.toContain('data-sec="account"')
  })

  it('登录页垂直居中：锚定视口而不是 min-h-full（auto 父级下解析为 0）', () => {
    const s = src('views/LoginView.vue')
    expect(s).toContain('min-h-[100vh] min-h-[100dvh]')
    // 根容器上不能再出现 min-h-full（注释里的"为什么不能用"不算）
    expect(s).not.toContain('flex min-h-full items-center')
  })

  it('账号弹框遵守 TV 弹框三约定（role=dialog / data-tv-scope / Esc 自关）', () => {
    const s = src('components/AccountDialog.vue')
    expect(s).toContain('role="dialog"')
    expect(s).toContain('data-tv-scope')
    expect(s).toContain("e.key === 'Escape'")
    expect(s).toContain('useOverlayHost')
  })

  it('账号弹框内容区不做 data-tv-scroll 停靠点，初始焦点落第一个输入框', () => {
    const s = src('components/AccountDialog.vue')
    // data-tv-scroll 是「内部无可聚焦元素」的滚动停靠点（AboutDialog 那种纯文字）。
    // 表单弹框加了它，遥控器上下只滚容器、焦点走不到控件（2026-11-20 MuMu 实测）。
    // 只看标签：脚本注释里解释"为什么不能加"是允许的。
    const tags = s.match(/<div[^>]*>/g) || []
    expect(tags.some((t) => t.includes('data-tv-scroll'))).toBe(false)
    // 落点：第一个可见输入框优先，没有才退回「返回」
    expect(s).toContain('firstField() || backBtn.value')
    // 常驻型面板：豁免 tv-nav 的 20 秒空闲自动关（否则看设备列表时弹框自己消失）
    expect(s).toContain('data-tv-idle-keep')
    // 双保险：标 data-tv-first，并用 pin=false 让 tv-nav 的 observer 还能兜底
    expect(s).toContain("el.setAttribute('data-tv-first', '')")
    expect(s).toContain('focusEl(el, false)')
  })

  it('登录页给 TV/PC 留「本机模式」出口（走 bootstrapLocal，不是只清 localStorage）', () => {
    const s = src('views/LoginView.vue')
    // 壳的地址真源是 Android prefs；只清 localStorage 会 reload 后又被写回来
    expect(s).toContain('bootstrapLocal')
    // 无鉴权时回首页（否则停在 #/login 没人跳走）
    expect(s).toContain("authMode.value === 'none'")
    // 两个次级入口必须是整行按钮：文字链接宽度不同会被 tv-nav 的列隔离跳过
    expect(s).toContain('w-full rounded-md border border-line')
  })

  it('设备令牌表单用设置页同款 TileSelect，不用原生 select', () => {
    const s = src('components/AccountPanel.vue')
    expect(s).toContain("import TileSelect from '@/components/common/TileSelect.vue'")
    expect(s).toContain('<TileSelect')
    // 只看模板：脚本注释里写着"不用原生 select"，整文件匹配会命中注释
    const tpl = s.slice(s.indexOf('<template>'))
    expect(tpl).not.toMatch(/<select[\s>]/)
  })

  it('401 带原因：后端 reason → ApiHttpError → 守卫 query → 登录页文案', () => {
    // 后端：unauthorizedJson 把 reason 透出去（白名单过滤）
    const api = repo('server/host/src/shared/ApiServer.java')
    expect(api).toContain('unauthorizedJson')
    expect(api).toContain('reason')
    // auth core：区分六种原因
    const auth = repo('server/host/src/shared/core/Auth.java')
    for (const r of [
      'no_credentials',
      'session_expired',
      'password_changed',
      'token_invalid',
      'token_expired',
      'ip_mismatch',
    ]) {
      expect(auth).toContain(r)
    }
    // 前端：错误对象带 reason，守卫带进 query，登录页映射成中文
    const idx = src('api/index.ts')
    expect(idx).toContain('reason = typeof j?.reason')
    expect(idx).toContain('unauthorizedHandler?.(reason)')
    expect(src('router/index.ts')).toContain('...(reason ? { reason } : {})')
    const login = src('views/LoginView.vue')
    expect(login).toContain('reasonText')
    expect(login).toContain('管理员改过密码')
  })

  it('会话落盘只存哈希，重启后网页不用重新登录', () => {
    const auth = repo('server/host/src/shared/core/Auth.java')
    expect(auth).toContain('loadSessions')
    expect(auth).toContain('saveSessions')
    // key 必须是 sha256(sid)，不能存明文
    expect(auth).toContain('SESSIONS.put(sha256(sid)')
    expect(auth).toContain('SESSIONS.get(sha256(sid))')
  })

  it('媒体票自愈：缓存留余量 + 图片失败刷票重试 + 切前台补刷', () => {
    const mt = src('api/mediaTicket.ts')
    expect(mt).toContain('refreshMediaTickets')
    expect(mt).toContain('inflight')
    // 缓存 15 分钟（票 30 分钟，留一半余量）
    expect(mt).toContain('15 * 60 * 1000')
    const poster = src('components/common/PosterImage.vue')
    expect(poster).toContain('ensureProxyTicket')
    expect(poster).toContain('retriedForTicket')
    expect(src('main.ts')).toContain('visibilitychange')
  })
  it('危险操作二次确认：撤销设备 / 轮换 configKey / 退出所有设备', () => {
    const s = src('components/AccountPanel.vue')
    expect(s).toContain('confirmDialog')
    expect(s).toContain('撤销设备令牌')
    expect(s).toContain('轮换配置密钥')
    expect(s).toContain('退出所有设备')
    expect(s).toContain('logoutOthers')
  })

  it('账号面板：本机标记 / 最近 IP / 完整配置地址 / 安全事件 / 密码强度', () => {
    const s = src('components/AccountPanel.vue')
    expect(s).toContain('meInfo.deviceId')
    expect(s).toContain('d.lastIp')
    expect(s).toContain('configUrls')
    expect(s).toContain('listEvents')
    expect(s).toContain('pwdStrength')
    expect(s).toContain('showPwd')
  })

  it('登录页：记住用户名 / 显示密码 / 忘记密码出路 / setup 令牌引导', () => {
    const s = src('views/LoginView.vue')
    expect(s).toContain('REMEMBER_USER')
    expect(s).toContain('showPwd')
    expect(s).toContain('--auth-reset-password')
    expect(s).toContain('setup 令牌')
  })

  it('TLS 模板带 HSTS；回环媒体开关进 compose', () => {
    const tls = repo('deploy/docker/nginx-tls.conf.template')
    expect(tls).toContain('Strict-Transport-Security')
    for (const f of [
      'deploy/docker/docker-compose.yml',
      'deploy/docker/docker-compose.build.yml',
      'deploy/docker/docker-compose.standalone.yml',
      'deploy/docker/docker-compose.with-sources.yml',
    ]) {
      expect(repo(f)).toContain('MEOW_ALLOW_LOOPBACK_MEDIA')
    }
  })
})

describe('来源 IP、密码可用范围与 fpk 配置（2026-11-20）', () => {
  it('passwordAllowed 放行局域网，只拦公网明文', () => {
    const auth = repo('server/host/src/shared/core/Auth.java')
    expect(auth).toContain('isPrivateAddress')
    expect(auth).toContain('192.168.0.0/16')
    expect(auth).toContain('172.16.0.0/12')
    // CGNAT 段：Tailscale/WireGuard 组网常落在这里，必须算可信
    expect(auth).toContain('100.64.0.0/10')
    // 169.254 是云元数据（SSRF）段，绝不能进"可信"名单
    const fn = auth.slice(auth.indexOf('private static boolean isPrivateAddress'))
    expect(fn.slice(0, fn.indexOf('return false'))).not.toContain('169.254')
  })

  it('meowd 直连入口用 socket 对端覆盖 XFF，且服务真的带上了连接信息', () => {
    const http = repo('client/apps/fnos/meowd/src/http.rs')
    expect(http).toContain('fn set_peer_addr')
    expect(http).toContain('x-forwarded-for')
    // 没有 into_make_service_with_connect_info 就拿不到对端，注入的会是空
    const main = repo('client/apps/fnos/meowd/src/main.rs')
    expect(main).toContain('into_make_service_with_connect_info::<SocketAddr>()')
  })

  it('fpk 的 meow.conf 不能被设置页整文件覆盖，且默认打开 trust proxy', () => {
    const init = repo('client/apps/fnos/fpk/cmd/config_init')
    // 整体重写会把 MEOW_AUTH_MODE / MEOW_SETUP_TOKEN 一起抹掉（实测踩过）
    expect(init).toContain('grep -v')
    expect(init).toContain('chmod 600')
    expect(repo('client/apps/fnos/fpk/cmd/install_init')).toContain('MEOW_TRUST_PROXY=1')
    // 老用户升级时要补上
    expect(repo('client/apps/fnos/fpk/cmd/upgrade_callback')).toContain('MEOW_TRUST_PROXY')
  })
})

describe('fnOS 网关身份与本地片库目录树（2026-11-20）', () => {
  it('meowd 双 listener 分权：TCP 删 X-Trim，socket 打 fnos-gateway', () => {
    const http = repo('client/apps/fnos/meowd/src/http.rs')
    expect(http).toContain('x-trim-userid')
    expect(http).toContain('x-meow-ingress')
    expect(http).toContain('fnos-gateway')
    // socket 路由必须真的挂上标记中间件，否则 Java 永远不认网关头
    expect(http).toMatch(/build_gateway_router[\s\S]{0,800}mark_gateway_ingress/)
  })

  it('Java 只在 ingress=fnos-gateway 且 uid 纯数字时才认身份', () => {
    const f = repo('server/host/src/shared/core/FnosIdentity.java')
    expect(f).toContain('INGRESS_GATEWAY')
    expect(f).toContain('X-Trim-Userid')
    expect(f).toContain('[0-9]+')
  })

  it('30010 默认只听回环，要局域网直连必须显式声明', () => {
    const main = repo('client/apps/fnos/fpk/cmd/main')
    expect(main).toContain('MEOW_SHELL_BIND:-127.0.0.1')
    expect(main).toContain('MEOW_AUTH_MODE')
  })

  it('对外地址可被 MEOW_PUBLIC_BASE 钉死', () => {
    expect(repo('server/host/src/shared/ApiServer.java')).toContain('MEOW_PUBLIC_BASE')
  })

  it('状态按 fnOS uid 分命名空间，无 uid 仍用全局（兼容 Docker/PC/TV）', () => {
    expect(repo('server/host/src/shared/routes/StateRoutes.java')).toContain('core.Auth.stateScope(ex)')
    expect(repo('server/host/src/shared/core/Auth.java')).toContain('users/')
  })

  it('fnOS 授权目录透传给 Java（否则片库弹窗永远空）', () => {
    const sup = repo('client/apps/fnos/meowd/src/supervisor.rs')
    expect(sup).toContain('TRIM_DATA_ACCESSIBLE_PATHS')
    expect(sup).toContain('MEOW_ALLOWED_PATHS')
    expect(sup).toContain('TRIM_DATA_SHARE_PATHS')
    expect(sup).toContain('MEOW_SHARE_PATHS')
  })

  it('目录树三端点在 lib 分支之前分发（否则被"返回配置"吃掉）', () => {
    const r = repo('server/host/src/shared/routes/LibraryRoutes.java')
    const iAction = r.indexOf('"roots".equals(action)')
    const iLib = r.indexOf('String libId = Api.trimToEmpty(q.get("lib"))')
    expect(iAction).toBeGreaterThan(0)
    expect(iLib).toBeGreaterThan(iAction)
    expect(r).toContain('"capabilities".equals(action)')
    expect(r).toContain('lsWithTimeout')
  })

  it('路径穿越与根去重都有护栏', () => {
    const roots = repo('server/host/src/shared/core/LibraryRoots.java')
    expect(roots).toContain('toRealPath')
    expect(roots).toContain('startsWith(root)')
    const lib = repo('server/host/src/shared/core/Library.java')
    expect(lib).toContain('DUP_ROOT')
    expect(lib).toContain('overlaps')
  })

  it('片库弹窗：浏览目录 + 根栏 + 空态引导', () => {
    const dlg = src('components/library/LibraryAddDialog.vue')
    expect(dlg).toContain('openPicker')
    expect(dlg).toContain('pickerRoots')
    expect(dlg).toContain('data-tv-scope')
    expect(dlg).toContain('MEOW_LIBRARY_ROOTS')
  })

  it('wizard_library_mode 不再是死选项', () => {
    const cb = repo('client/apps/fnos/fpk/cmd/install_callback')
    expect(cb).toContain('wizard_library_mode')
    expect(cb).toContain('library.json')
  })
})
