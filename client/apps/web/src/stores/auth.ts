/** 应用内账号状态（公网 v2）。 */
import { computed, ref } from 'vue'
import {
  getAuthStatus,
  login as apiLogin,
  logout as apiLogout,
  me as apiMe,
  setupAdmin,
  type AuthMode,
} from '@/api/auth'
import { ensureProxyTicket, resetMediaTickets } from '@/api/mediaTicket'
import { apiBase, setCrossOriginCredentials } from '@/api'
import { isTvShell } from '@/compat/shell'

export const authMode = ref<AuthMode>('none')
export const authRequired = ref(false)
export const setupRequired = ref(false)
export const passwordAllowed = ref(true)
export const username = ref('')
export const authReady = ref(false)

/**
 * 设备令牌的响应式副本。
 *
 * ★ 不能让 needToken 直接读 localStorage：computed 只跟踪响应式依赖，
 * localStorage 不是响应式的 —— 首次判成「没有令牌」后会**永远缓存 true**，
 * 用户填完令牌仍被守卫弹回登录页（2026-11-19 实测踩过）。
 */
export const deviceToken = ref(
  typeof localStorage === 'undefined' ? '' : localStorage.getItem('meow.authToken') || '',
)

export function setDeviceToken(token: string): void {
  const v = token.trim()
  deviceToken.value = v
  if (typeof localStorage !== 'undefined') {
    try {
      if (v) localStorage.setItem('meow.authToken', v)
      else localStorage.removeItem('meow.authToken')
    } catch {
      /* 隐私模式可能拒绝写入：内存里仍有值，本次会话可用 */
    }
  }
  /*
   * TV 壳：同时存一份到 Android prefs。
   *
   * 重装 APK（应用内更新也走 pm install -r）之后 WebView 的 localStorage
   * 在部分 ROM/模拟器上会被清空（实测 MuMu Android 15），用户打开就是登录页、
   * 得重新配对。壳在 onPageFinished 用这份副本补回 localStorage。
   * 退出登录时 v 为空，桥会删掉它 —— 否则下次启动又被补回来。
   */
  try {
    const b = (window as unknown as { meowTv?: { saveAuthToken?: (t: string) => void } }).meowTv
    b?.saveAuthToken?.(v)
  } catch {
    /* 桥不可用（Web/PC）忽略 */
  }
}

/** 浏览器需要登录：密码模式且当前没有会话。 */
export const needLogin = computed(() => authMode.value === 'password' && !username.value)

/** 只能用令牌的部署（纯 HTTP / 只配了设备令牌）：本地没有令牌就去填。 */
export const needToken = computed(() => {
  if (authMode.value !== 'token-only') return false
  return !deviceToken.value
})

/**
 * API 地址是否与页面跨源（分体部署）。
 *
 * <p>base 为空 = 同源；TV 壳不算 —— 它的令牌走 header，Cookie 永远带不上，
 * 开 include 只会多出无意义的预检条件。
 */
function apiCrossOrigin(): boolean {
  if (typeof location === 'undefined') return false
  const b = apiBase()
  if (!b) return false
  try {
    return new URL(b, location.origin).origin !== location.origin
  } catch {
    return false
  }
}

export async function refreshAuth(): Promise<void> {
  try {
    const s = await getAuthStatus()
    authMode.value = s.mode
    authRequired.value = s.authRequired
    setupRequired.value = s.setupRequired
    passwordAllowed.value = s.passwordAllowed !== false
  } catch {
    // 后端未就绪：按"无鉴权"处理，保留旧行为（PC 首次启动等）
    authMode.value = 'none'
    authRequired.value = false
    setupRequired.value = false
  } finally {
    authReady.value = true
  }
  // 分体部署（web 与 api 不同源）+ 密码模式：request 全程 credentials:include，
  // 会话 Cookie 才能带上。前提是后端 MEOW_CORS 显式白名单了 web 的 Origin。
  // TV 壳（令牌走 header）与令牌模式不开 —— * + credentials 会被浏览器拒绝。
  setCrossOriginCredentials(
    apiCrossOrigin() && authMode.value === 'password' && !isTvShell(),
  )
  if (authMode.value === 'password') {
    try {
      const r = await apiMe()
      username.value = r.user || ''
    } catch {
      username.value = ''
    }
  }
  // 已通过鉴权就顺手取一张代理票：海报/封面是 <img> 直取，没有票会全变
  // "点击重试"（2026-11-19 MuMu .77 实测）。失败不阻塞启动。
  const authed =
    authMode.value === 'password'
      ? !!username.value
      : authMode.value === 'token-only' && !!deviceToken.value
  if (authed) void ensureProxyTicket()
}

export async function doSetup(token: string, user: string, pwd: string): Promise<void> {
  await setupAdmin({ token, username: user, password: pwd })
  resetMediaTickets()
  await refreshAuth()
}

export async function doLogin(user: string, pwd: string): Promise<void> {
  const r = await apiLogin({ username: user, password: pwd })
  username.value = r.user || user
  resetMediaTickets()
  await refreshAuth()
}

export async function doLogout(): Promise<void> {
  try {
    await apiLogout()
  } catch {
    /* 网络失败也要把本地状态清掉 */
  }
  username.value = ''
  // 令牌也一起清：TV/PC 用设备令牌登录时没有会话，只清 Cookie 等于没退出
  setDeviceToken('')
  resetMediaTickets()
}
