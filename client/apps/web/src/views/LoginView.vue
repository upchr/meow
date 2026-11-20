<script setup lang="ts">
/**
 * 登录 / 首启 setup / 纯令牌模式。
 *
 * 同一个页面三种形态：
 *  - setupRequired：首次启动，输入服务端日志里的 setup 令牌 + 管理员密码；
 *  - authMode=password：账号密码登录；
 *  - authMode=token-only / passwordAllowed=false：输入设备令牌（纯 HTTP 部署）。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { request } from '@/api'
import { pairPoll, pairStart } from '@/api/auth'
import { doLogin, doSetup, deviceToken, passwordAllowed, setDeviceToken, setupRequired, authMode, refreshAuth } from '@/stores/auth'
import { isDesktopShell, isTvShell } from '@/compat/shell'

const router = useRouter()
const route = useRoute()
// 模板里不能写 import.meta（Vue 表达式解析器不允许），先落到 script 常量。
// 必须带 BASE_URL：fpk 部署在 /app/meow/ 下，写死 /icon-192.png 会 404。
const logoSrc = `${import.meta.env.BASE_URL}icon-192.png`
/**
 * TV 壳里页面跑在 `http://meow.local`，连接远程后端是**跨源**：
 * fetch 默认不带 Cookie，Set-Cookie 也存不下 —— 密码登录表单在 TV 上
 * 永远是死路（实测 2026-11-20：登录请求 200，随后 /me 不带 Cookie 401，
 * 守卫把人弹回登录页，死循环）。TV 一律走设备令牌/配对码。
 */
const mode = computed<'setup' | 'login' | 'token'>(() => {
  if (setupRequired.value) return 'setup'
  if (isTvShell()) return 'token'
  if (authMode.value === 'token-only' || !passwordAllowed.value) return 'token'
  return 'login'
})

const token = ref('')
/**
 * 进登录页前本地就有令牌 = 被 401 弹回来的（后端重启 / 设备被撤销 / 换了服务器）。
 * 不区分的话用户看到的就是"又让我登录"，不知道是令牌失效了。
 */
const hadToken = ref(!!deviceToken.value)

/**
 * 「为什么让你重新登录」的一句话。
 *
 * <p>优先用后端 401 带回的 `reason`（路由守卫写进 query），它比前端猜的准：
 * 会话过期 / 管理员改密 / 令牌被撤销 / 令牌过期 / 绑定 IP 不符 各有各的处置。
 * 没有 reason 但有本地令牌时落到通用文案（比如后端重启后旧票/旧令牌失效）。
 */
const reasonText = computed(() => {
  const map: Record<string, string> = {
    session_expired: '登录已过期，请重新登录',
    password_changed: '管理员改过密码，本设备的登录已失效，请重新登录或重新配对',
    token_invalid: '设备令牌无效（可能已在网页端被撤销），请重新配对或生成新令牌',
    token_expired: '设备令牌已过期，请重新配对或生成新令牌',
    ip_mismatch: '这个令牌绑定了其他 IP（换网络了？），请重新配对，或在网页端去掉 IP 绑定',
  }
  const r = String(route.query.reason || '')
  if (map[r]) return map[r]
  if (hadToken.value) {
    return '设备令牌已失效（后端重启、设备被撤销，或换了服务器地址）。重新配对，或到网页「账号与安全」生成新令牌。'
  }
  return ''
})
/** 记住上次用的用户名（不记密码）：每次重填用户名很烦，尤其中文输入法 */
const REMEMBER_USER = 'meow.authUser'
const user = ref(
  (typeof localStorage !== 'undefined' && localStorage.getItem(REMEMBER_USER)) || 'admin',
)
const pwd = ref('')
const pwd2 = ref('')
const busy = ref(false)
const err = ref('')
/** 密码明文显示：电视/手机小键盘输密码容易错一位 */
const showPwd = ref(false)

/** 密码强度（只提示，不阻断；后端底线是 ≥10 位，规则与账号面板一致） */
const pwdStrength = computed(() => {
  const p = pwd.value
  if (!p) return { text: '', cls: '' }
  if (p.length < 10) return { text: '太短（至少 10 位）', cls: 'text-hot' }
  const kinds = [/[a-z]/, /[A-Z]/, /\d/, /[^A-Za-z0-9]/].filter((r) => r.test(p)).length
  if (p.length >= 16 || (p.length >= 12 && kinds >= 3)) return { text: '强', cls: 'text-ok' }
  if (kinds >= 2) return { text: '中', cls: 'text-ink-2' }
  return { text: '弱：建议混用大小写/数字/符号', cls: 'text-hot' }
})

// 配对码：TV 上不用手抄长令牌 —— 电视显示 6 位码，网页「账号与安全」批准。
const pairId = ref('')
const pairCode = ref('')
const pairBusy = ref(false)
const pairMsg = ref('')
let pairTimer: number | undefined

function stopPair(): void {
  if (pairTimer) {
    clearInterval(pairTimer)
    pairTimer = undefined
  }
}

async function startPair(): Promise<void> {
  err.value = ''
  pairMsg.value = ''
  pairBusy.value = true
  stopPair()
  try {
    const r = await pairStart()
    pairId.value = r.pairId
    pairCode.value = r.code
    pairMsg.value = '在已登录的网页「设置 → 账号与安全 → TV 配对」输入这个码'
    pairTimer = window.setInterval(async () => {
      try {
        const p = await pairPoll(pairId.value)
        if (p.approved && p.token) {
          stopPair()
          setDeviceToken(p.token)
          await refreshAuth()
          await router.replace('/')
        }
      } catch {
        /* 单次轮询失败继续（网络抖动） */
      }
    }, 2000)
  } catch (e) {
    err.value = e instanceof Error ? e.message : String(e)
  } finally {
    pairBusy.value = false
  }
}

onBeforeUnmount(stopPair)

/** TV / PC 壳才有"本机模式"出口（浏览器/fpk 没有自带后端） */
const canUseLocal = isTvShell() || isDesktopShell()

/** TV 壳暴露的原生桥（只列本文件用到的） */
type TvBridge = {
  /** 起本机网关（幂等；已起会很快返回） */
  startLocalGateway?: () => string
  /** 写后端地址到壳的 prefs（**无条件**写，不看网关起没起） */
  saveConn?: (api: string, config: string) => string
  /** 让壳用"已保存的后端地址"重新注入 localStorage 并加载页面 */
  reloadApp?: () => void
  /** 零配置自启：落 jar → 装兜底站 → 起网关 → 写 prefs → 注入 localStorage */
  bootstrapLocal?: () => string
}

/** 切换中：桥里最长会同步阻塞十几秒（等网关端口可连），先让按钮变样 */
const localBusy = ref(false)

/**
 * TV / PC 壳自带后端：切回本机模式（不需要账号）。
 *
 * <p>三个桥必须**都调**，缺一个就是"点了没反应"：
 *  1. `startLocalGateway()` —— 起网关（幂等）。**可能同步阻塞十几秒**
 *     （`awaitGatewayReady`），所以外面套了 setTimeout 让按钮先重绘；
 *  2. `saveConn(gw, '')` —— 把地址写进壳的 prefs。这是**唯一真源**：
 *     只写 localStorage 会被壳在 reload 后覆盖回远程地址；
 *  3. `reloadApp()` —— 壳里唯一会 `injectApiBase + loadUrl` 的入口，
 *     把 prefs 同步进 localStorage。只调 1+2 不调它 → 网关起来了但首页
 *     永远显示「后端未连接：同源 /api」（2026-11-20 MuMu .77 实测）。
 *
 * <p>不再用 `bootstrapLocal()`：它内部只在网关**本次启动成功**时才写 prefs，
 * 网关已经在跑时这条路走不通 → prefs 还是远程地址 → reload 回登录页，
 * 表现就是用户说的"无法进入"。
 */
function doLocalMode(): void {
  const b = (window as unknown as { meowTv?: TvBridge }).meowTv
  let handled = false
  if (b?.startLocalGateway || b?.saveConn) {
    try {
      b.startLocalGateway?.()
    } catch {
      /* 端口被占也继续：下面 saveConn 写地址，让用户进首页去设置页处理 */
    }
    try {
      b.saveConn?.('http://127.0.0.1:9978', '')
    } catch {
      /* 忽略 */
    }
    if (b.reloadApp) {
      try {
        b.reloadApp()
        handled = true
      } catch {
        /* 落到下面的兜底 */
      }
    }
  }
  if (handled) return
  // PC 桌面端（无 meowTv 桥）：清掉手填地址，apiBase() 回落到 boot_info 的本地端口
  try {
    localStorage.removeItem('meow.api')
    localStorage.removeItem('meow.api.scope')
  } catch {
    /* 隐私模式忽略 */
  }
  // 回首页而不是原地 reload：hash 还停在 #/login，守卫放行后没人会跳走
  location.replace(`${import.meta.env.BASE_URL}#/`)
}

function useLocalMode(): void {
  if (localBusy.value) return
  localBusy.value = true
  // 先让"正在启动本机网关…"这一帧画出来，再进同步桥（否则全程无反馈）
  window.setTimeout(doLocalMode, 60)
}

onMounted(async () => {
  /*
   * 壳刚把保存的设备令牌补回 localStorage（MainActivity.restoreAuthToken
   * 在 onPageFinished 里注入并 reload）——重载后 URL 还是 #/login，守卫虽然
   * 会放行，但没有下一次导航把它送走。这里主动回首页。
   *
   * ★ 防死循环：令牌真失效时会被守卫再送回来，那时 sessionStorage 标记已置，
   *   不再跳，正常显示登录页 + 「令牌已失效」提示。
   */
  const stored = typeof localStorage === 'undefined' ? '' : localStorage.getItem('meow.authToken') || ''
  if (stored && !deviceToken.value) deviceToken.value = stored
  if (stored && !sessionStorage.getItem('meow.authJumped')) {
    sessionStorage.setItem('meow.authJumped', '1')
    void router.replace('/')
    return
  }
  if (!stored) sessionStorage.removeItem('meow.authJumped')

  /*
   * 无鉴权（刚切回本机模式 / 部署本就没开）：登录页没有意义，直接回首页。
   *
   * 踩过（2026-11-20 MuMu .77 实测）：点「改用本机模式」清了后端地址后页面
   * 重载，但 URL 还是 #/login —— 路由守卫虽然放行，已经没有"下一次导航"
   * 把它送走了，用户看到的是"点了按钮没反应"。所以这里主动 replace。
   */
  await refreshAuth()
  if (!setupRequired.value && authMode.value === 'none') {
    void router.replace('/')
    return
  }
  const first = document.querySelector<HTMLInputElement>('input[autofocus], input')
  first?.focus()
})

async function submit(): Promise<void> {
  err.value = ''
  busy.value = true
  try {
    if (mode.value === 'setup') {
      if (pwd.value.length < 10) throw new Error('密码至少 10 位')
      if (pwd.value !== pwd2.value) throw new Error('两次密码不一致')
      await doSetup(token.value.trim(), user.value.trim() || 'admin', pwd.value)
    } else if (mode.value === 'login') {
      await doLogin(user.value.trim(), pwd.value)
    } else {
      const t = token.value.trim()
      if (!t) throw new Error('请输入设备令牌')
      setDeviceToken(t)
      // 用一个受保护端点验证令牌；401 会由全局 handler 处理，这里只提示
      await request('/api/config')
      await refreshAuth()
    }
    // 记住用户名（密码绝不存）
    try {
      localStorage.setItem(REMEMBER_USER, user.value.trim() || 'admin')
    } catch {
      /* 隐私模式忽略 */
    }
    // 回被拦之前的页面（401 时守卫把 `redirect` 带过来了），没有就回首页
    const back = typeof route.query.redirect === 'string' ? route.query.redirect : ''
    await router.replace(back && !back.startsWith('/login') ? back : '/')
  } catch (e) {
    /*
     * `request()` 抛的是 `[POST] /api/auth/login → 429 尝试过于频繁，请稍后再试`
     * 这种带路径与状态码的长串。登录页是用户第一眼看到的地方，只留后端给的
     * 那句人话（`→` 之后的部分）；没有箭头（网络错误等）就原样显示。
     */
    const msg = e instanceof Error ? e.message : String(e)
    err.value = msg.includes('→') ? (msg.split('→').pop() || msg).trim() : msg
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <!--
    ★ 不能用 min-h-full：它解析成 min-height:100%，而父级（main）的高度是
      flex 分配出来的 auto —— 百分比在 auto 父级下解析为 0，容器高度塌成
      内容本身的高度，items-center 等于没写，卡片贴在页面顶部（2026-11-19
      NAS 实测：1440×900 视口下卡片顶部对齐，下方空一大截）。
      这里直接锚定视口：先 100vh 兜底老内核，再 100dvh 覆盖移动端地址栏
      收起/展开的高度差（与设置页 wide:h-[calc(100vh-…)]/dvh 双写同一惯例）。
  -->
  <div class="flex min-h-[100vh] min-h-[100dvh] items-center justify-center bg-base px-4 py-10">
    <div class="w-full max-w-sm rounded-xl border border-line bg-card p-6 shadow-lg">
      <div class="mb-5 flex items-center gap-3">
        <img
          :src="logoSrc"
          alt=""
          width="40"
          height="40"
          class="h-10 w-10 rounded-[10px]"
        />
        <div>
          <div class="text-[15px] font-medium text-ink">猫窝 Meow</div>
          <div class="text-[12px] text-ink-3">
            {{ mode === 'setup' ? '首次启动 · 设置管理员' : mode === 'token' ? '设备令牌' : '登录' }}
          </div>
        </div>
      </div>

      <form class="space-y-3" @submit.prevent="submit">
        <!-- 为什么让你重新登录：放在表单最上面，第一眼就看到 -->
        <p
          v-if="reasonText"
          class="rounded-md border border-hot/40 bg-hot/10 px-3 py-2 text-[12px] leading-relaxed text-hot"
        >
          {{ reasonText }}
        </p>
        <!--
          公网 HTTP 关掉了密码登录（密码会明文过网）。不说清的话用户只看到
          "为什么没有密码框"，还会以为自己的账号坏了。局域网 HTTP 是允许的
          （见 Auth.passwordAllowed），所以这里只可能是公网地址走到这一步。
        -->
        <p
          v-if="mode === 'token' && !passwordAllowed && authMode === 'password'"
          class="rounded-md border border-line bg-base px-3 py-2 text-[11px] leading-relaxed text-ink-3"
        >
          你用公网地址 + HTTP 访问，密码登录已关闭（密码会明文过网）。三条出路：
          ① 配 HTTPS（反向代理或 <code>docker-compose.tls.yml</code>）后用账号密码；
          ② 在这里填设备令牌（网页设置里生成）；
          ③ 局域网内直接访问 NAS/服务器地址，密码框会出现。
        </p>
        <input
          v-if="mode === 'setup'"
          v-model="token"
          autofocus
          class="w-full rounded-md border border-line bg-base px-3 py-2 text-sm text-ink outline-none focus:border-accent"
          placeholder="setup 令牌（在服务器日志/控制台）"
          autocomplete="one-time-code"
        />
        <input
          v-if="mode !== 'token'"
          v-model="user"
          class="w-full rounded-md border border-line bg-base px-3 py-2 text-sm text-ink outline-none focus:border-accent"
          placeholder="用户名"
          autocomplete="username"
        />
        <input
          v-if="mode === 'token'"
          v-model="token"
          autofocus
          class="w-full rounded-md border border-line bg-base px-3 py-2 text-sm text-ink outline-none focus:border-accent"
          placeholder="设备令牌（网页设置里生成）"
          autocomplete="off"
        />
        <input
          v-if="mode !== 'token'"
          v-model="pwd"
          :type="showPwd ? 'text' : 'password'"
          class="w-full rounded-md border border-line bg-base px-3 py-2 text-sm text-ink outline-none focus:border-accent"
          placeholder="密码"
          :autocomplete="mode === 'setup' ? 'new-password' : 'current-password'"
        />
        <input
          v-if="mode === 'setup'"
          v-model="pwd2"
          :type="showPwd ? 'text' : 'password'"
          class="w-full rounded-md border border-line bg-base px-3 py-2 text-sm text-ink outline-none focus:border-accent"
          placeholder="确认密码"
          autocomplete="new-password"
        />

        <!-- 显示密码 + 强度：电视小键盘看不见输入，错一位就只能重来 -->
        <div v-if="mode !== 'token'" class="flex flex-wrap items-center gap-3">
          <span v-if="mode === 'setup' && pwdStrength.text" class="text-[11px]" :class="pwdStrength.cls">
            强度：{{ pwdStrength.text }}
          </span>
          <button
            type="button"
            class="ml-auto rounded border border-line px-2 py-1 text-[11px] text-ink-2 transition hover:text-ink"
            @click="showPwd = !showPwd"
          >
            {{ showPwd ? '隐藏密码' : '显示密码' }}
          </button>
        </div>

        <p v-if="err" class="text-[12px] text-hot">{{ err }}</p>
        <p v-if="mode === 'setup'" class="text-[11px] leading-relaxed text-ink-3">
          令牌只在服务器日志/控制台打印一次；公网部署请立刻完成设置。
        </p>
        <!--
          设置完就要面对"电视怎么连"：这里先说清路径，否则用户开完鉴权
          发现电视进不了首页，又不知道去哪配令牌（2026-11-20 复核）。
        -->
        <p v-if="mode === 'setup'" class="text-[11px] leading-relaxed text-ink-3">
          设置完成后：电视/电脑打开这个地址 → 点「用配对码登录」→ 在「设置 → 连接 → 账号与安全 → TV 配对」输入屏幕上的 6 位码批准即可。
        </p>
        <!--
          setup 令牌找不到：它只在首次启动时打印一次。不写清去哪看，
          用户只能猜或去翻文档。
        -->
        <details v-if="mode === 'setup'" class="text-[11px] leading-relaxed text-ink-3">
          <summary class="cursor-pointer select-none">找不到 setup 令牌？</summary>
          <p class="mt-1">
            Docker：<code class="rounded bg-base px-1">docker compose logs apiserver | grep -A3 "setup 令牌"</code><br />
            本机 / NAS：看启动日志（fpk 在「共享目录 / logs」）；也可以先
            <code class="rounded bg-base px-1">MEOW_SETUP_TOKEN=自定义值</code>
            再重启（仅在还没设置过管理员时生效）。
          </p>
        </details>
        <!--
          忘记密码：界面上必须有出路。以前只写在文档里，用户被锁在登录页
          根本不知道有 CLI。
        -->
        <details v-if="mode === 'login'" class="text-[11px] leading-relaxed text-ink-3">
          <summary class="cursor-pointer select-none">忘了密码？</summary>
          <p class="mt-1">在跑后端的机器上执行，改完立即退出、不会启动服务：</p>
          <ul class="mt-1 space-y-1.5">
            <li>
              <span class="text-ink-3">Docker</span><br />
              <code class="break-all rounded bg-base px-1">docker exec -it meow-apiserver java -cp "/app/classes:/app/libs/*" ApiServer --auth-reset-password=新密码 --data-dir=/data</code>
            </li>
            <li>
              <span class="text-ink-3">飞牛 / fnOS 套件</span><br />
              <code class="break-all rounded bg-base px-1">su meow -s /bin/sh -c 'java -jar /vol1/@appcenter/meow/seed/apiserver/apiserver.jar --auth-reset-password=新密码 --data-dir=/vol1/@appdata/meow/data'</code>
            </li>
            <li>
              <span class="text-ink-3">直接跑 jar</span><br />
              <code class="break-all rounded bg-base px-1">java -jar apiserver.jar --auth-reset-password=新密码 --data-dir=数据目录</code>
            </li>
          </ul>
          <p class="mt-1">
            密码至少 10 位。改完所有浏览器要重新登录，设备令牌与 TV 配对不受影响；
            彻底关掉鉴权用 <code class="rounded bg-base px-1">--auth-off</code>（同一位置换参数）。
          </p>
        </details>

        <button
          type="submit"
          :disabled="busy"
          class="w-full rounded-md bg-accent px-3 py-2 text-sm font-medium text-white transition disabled:opacity-50"
        >
          {{ busy ? '处理中…' : mode === 'setup' ? '设置并登录' : mode === 'token' ? '保存令牌' : '登录' }}
        </button>
      </form>

      <!-- 配对码（TV 首选）：电视显示 6 位码，在网页端批准 -->
      <!--
        配对码入口：密码模式的部署都给（TV 壳的 mode 是 token，但 authMode
        仍是 password —— 批准方在网页会话里，跨源可用）。纯令牌部署没有会话，
        批准必然 401，不显示这个入口。
      -->
      <div v-if="authMode === 'password'" class="mt-4 border-t border-line pt-3">
        <template v-if="pairCode">
          <div class="text-[11px] text-ink-3">配对码（10 分钟内有效）</div>
          <div class="my-1 text-2xl font-semibold tracking-[0.35em] text-accent">{{ pairCode }}</div>
          <p class="text-[11px] leading-relaxed text-ink-3">{{ pairMsg }}</p>
        </template>
        <template v-else>
          <!--
            ★ 整行按钮，不要做成下划线文字链接（2026-11-20 MuMu .77 实测）：
            文字链接宽度只有主按钮的一半，tv-nav 的列隔离（按 x 中心/宽度分列）
            把它判成"另一列"，从「保存令牌」按 ↓ 会直接跳过它、兜底落回输入框
            —— 遥控器根本到不了配对码入口。
          -->
          <button
            type="button"
            :disabled="pairBusy"
            class="w-full rounded-md border border-line px-3 py-2 text-[13px] text-ink-2 transition hover:border-accent/60 hover:text-accent disabled:opacity-50"
            @click="startPair"
          >
            {{ pairBusy ? '正在获取配对码…' : '用配对码登录（电视上不用手抄令牌）' }}
          </button>
        </template>
      </div>

      <!--
        本机模式出口（TV / PC 壳）：这两端自带后端，独立使用时根本不需要账号。
        没有这个出口时，一旦后端地址指向了一台开了鉴权的远程机器，用户会被
        永远拦在登录页 —— 想改回本机也进不了设置页（2026-11-20 用户反馈
        「tv、pc 这种是可以独立使用的，我怎么输入」）。
      -->
      <div v-if="canUseLocal" class="mt-3 border-t border-line pt-3">
        <p class="text-[11px] leading-relaxed text-ink-3">
          TV / PC 端自带后端：不连远程服务器时不需要账号。点下面会切到本机网关，直接进首页。
        </p>
        <!-- 同配对码入口：整行按钮，别做成文字链接（列隔离会跳过它） -->
        <button
          type="button"
          :disabled="localBusy"
          class="mt-2 w-full rounded-md border border-line px-3 py-2 text-[13px] text-ink-2 transition hover:border-accent/60 hover:text-accent disabled:opacity-60"
          @click="useLocalMode"
        >
          {{ localBusy ? '正在启动本机网关…' : '改用本机模式（无需登录）' }}
        </button>
      </div>
    </div>
  </div>
</template>
