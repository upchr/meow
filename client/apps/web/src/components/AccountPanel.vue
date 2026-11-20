<script setup lang="ts">
/**
 * 账号与安全（公网部署 v2）。
 *
 * 只在**密码模式**下渲染内容：纯令牌部署没有会话，设备令牌由部署者
 * 通过 `MEOW_AUTH_TOKEN` 预置，网页这边没有可管理的入口。
 *
 * 三块：改密码 / 设备令牌 / TV 配对码 + configKey。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import TileSelect from '@/components/common/TileSelect.vue'
import { confirmDialog } from '@/composables/useConfirm'
import {
  changePassword,
  createDevice,
  getAuthStatus,
  listDevices,
  listEvents,
  logoutOthers,
  me,
  pairApprove,
  revokeDevice,
  rotateConfigKey,
  type AuthEvent,
  type DeviceToken,
} from '@/api/auth'
import { doLogout, username } from '@/stores/auth'

const router = useRouter()
const devices = ref<DeviceToken[]>([])
const hasConfigKey = ref(false)
const loading = ref(false)
const msg = ref('')
const err = ref('')

/** 影响面与"本机"标记：来自 /api/auth/me */
const meInfo = ref({ devices: 0, sessions: 0, deviceId: '' })
/** 最近安全事件（内存环形，重启清零） */
const events = ref<AuthEvent[]>([])
const eventsOpen = ref(false)
const othersBusy = ref(false)

const oldPwd = ref('')
const newPwd = ref('')
const newPwd2 = ref('')
const pwdBusy = ref(false)
/** 密码明文显示：电视/手机小键盘输密码容易错，给一个开关 */
const showPwd = ref(false)

const devName = ref('')
const devScope = ref<'full' | 'read'>('full')
const devDays = ref(30)
const devBindIp = ref('')
const devBusy = ref(false)
const newToken = ref('')
const newTokenName = ref('')

/**
 * 权限 / 有效期用设置页同款 TileSelect，不用原生 `<select>`。
 *
 * 踩过（2026-11-20 用户反馈「下拉框不对，用设置页那种」）：原生 select 在
 * 电视上弹出的是系统白底下拉（与深色主题完全脱节），遥控器操作也别扭；
 * 设置页的下拉是 `.tile` 磁贴 + Teleport 弹层、带 `data-tv-scope`，
 * 方向键/返回键都有约定。设备令牌这里跟它对齐。
 *
 * 标签里不放长句：磁贴宽度有限，`只读（不能改配置/播放记录）` 会被截断，
 * 细则写在表单下方的说明里。
 */
const scopeOptions = [
  { value: 'full', label: '完整权限' },
  { value: 'read', label: '只读' },
]
const dayOptions = [
  { value: 30, label: '30 天' },
  { value: 90, label: '90 天' },
  { value: 365, label: '1 年' },
  { value: 0, label: '永久' },
]

const pairCode = ref('')
const pairName = ref('TV')
const pairBusy = ref(false)

const configKey = ref('')
const keyBusy = ref(false)

const canCreateDevice = computed(() => devices.value.length < 20)

/**
 * 新密码强度（只提示，不阻断 —— 后端底线是 ≥10 位）。
 *
 * 判据是"长度 + 字符类数"：电视/手机上用户看不到自己输的什么，给一个
 * 弱/中/强比"至少 10 位"更有用。
 */
const pwdStrength = computed(() => {
  const p = newPwd.value
  if (!p) return { level: 0, text: '', cls: '' }
  if (p.length < 10) return { level: 1, text: '太短（至少 10 位）', cls: 'text-hot' }
  const kinds = [/[a-z]/, /[A-Z]/, /\d/, /[^A-Za-z0-9]/].filter((r) => r.test(p)).length
  if (p.length >= 16 || (p.length >= 12 && kinds >= 3)) {
    return { level: 3, text: '强', cls: 'text-ok' }
  }
  if (kinds >= 2) return { level: 2, text: '中', cls: 'text-ink-2' }
  return { level: 1, text: '弱：建议混用大小写/数字/符号', cls: 'text-hot' }
})

/**
 * configKey 的完整地址（复制给 CatVod 客户端用）。
 *
 * 以前只显示裸 key，用户得自己拼 `/config.json?key=`；fpk 还带 `/app/meow/`
 * 前缀，拼错就是 404。这里直接给出可粘贴的两条 URL。
 */
const configUrls = computed(() => {
  if (!configKey.value) return [] as string[]
  const base = `${location.origin}${import.meta.env.BASE_URL}`.replace(/\/+$/, '')
  return [
    `${base}/config.json?key=${configKey.value}`,
    `${base}/spider-latest.jar?key=${configKey.value}`,
  ]
})

/** 事件类型 → 人话 */
const EVENT_LABELS: Record<string, string> = {
  login_ok: '登录成功',
  login_fail: '登录失败',
  setup: '完成初始设置',
  password_changed: '修改密码',
  device_created: '创建设备令牌',
  device_revoked: '撤销设备令牌',
  pair_approved: '批准配对',
  config_key_rotated: '轮换配置密钥',
  logout_others: '退出所有设备',
}
function eventLabel(e: AuthEvent): string {
  return EVENT_LABELS[e.type] || e.type
}

function flash(text: string): void {
  msg.value = text
  err.value = ''
  window.setTimeout(() => {
    if (msg.value === text) msg.value = ''
  }, 4000)
}

function fail(e: unknown): void {
  err.value = e instanceof Error ? e.message : String(e)
  msg.value = ''
}

function fmtTime(ms: number): string {
  if (!ms) return '—'
  try {
    return new Date(ms).toLocaleString()
  } catch {
    return String(ms)
  }
}

async function reload(): Promise<void> {
  loading.value = true
  try {
    const [r, s, m] = await Promise.all([listDevices(), getAuthStatus(), me()])
    devices.value = r.devices || []
    hasConfigKey.value = !!s.configKey
    meInfo.value = {
      devices: m.devices || 0,
      sessions: m.sessions || 0,
      deviceId: m.deviceId || '',
    }
  } catch (e) {
    fail(e)
  } finally {
    loading.value = false
  }
  void loadEvents()
}

/** 事件区只是排障辅助：失败不弹错、不影响主流程。 */
async function loadEvents(): Promise<void> {
  try {
    const r = await listEvents(30)
    events.value = r.events || []
  } catch {
    /* 忽略 */
  }
}

async function submitPassword(): Promise<void> {
  err.value = ''
  if (newPwd.value.length < 10) {
    fail(new Error('新密码至少 10 位'))
    return
  }
  if (newPwd.value !== newPwd2.value) {
    fail(new Error('两次输入的新密码不一致'))
    return
  }
  pwdBusy.value = true
  try {
    await changePassword(oldPwd.value, newPwd.value)
    oldPwd.value = ''
    newPwd.value = ''
    newPwd2.value = ''
    flash('密码已更新')
  } catch (e) {
    fail(e)
  } finally {
    pwdBusy.value = false
  }
}

async function submitDevice(): Promise<void> {
  err.value = ''
  if (!devName.value.trim()) {
    fail(new Error('请给设备起个名字（例如 客厅电视）'))
    return
  }
  devBusy.value = true
  try {
    const r = await createDevice({
      name: devName.value.trim(),
      scope: devScope.value,
      expiresInDays: devDays.value > 0 ? devDays.value : 0,
      bindIp: devBindIp.value.trim(),
    })
    newToken.value = r.token
    newTokenName.value = r.name
    devName.value = ''
    await reload()
    flash('设备令牌已生成（只显示这一次）')
  } catch (e) {
    fail(e)
  } finally {
    devBusy.value = false
  }
}

async function removeDevice(d: DeviceToken): Promise<void> {
  err.value = ''
  /*
   * 二次确认：TV 上焦点落在「撤销」上按 OK 就直接掉线，而且没有撤销操作
   * 能救回来（只能重新配对）。危险动作必须有一步确认。
   */
  const ok = await confirmDialog(
    `撤销「${d.name}」？\n它下次请求会立即 401，需要重新配对或输入新令牌。`,
    { title: '撤销设备令牌', okText: '撤销', danger: true },
  )
  if (!ok) return
  try {
    await revokeDevice(d.id)
    await reload()
    flash(`已撤销「${d.name}」`)
  } catch (e) {
    fail(e)
  }
}

/** 「退出所有设备」：撤销全部设备令牌 + 下线其它浏览器会话，保留当前这台。 */
async function revokeOthers(): Promise<void> {
  err.value = ''
  const otherSessions = Math.max(0, meInfo.value.sessions - 1)
  const ok = await confirmDialog(
    `退出所有设备？\n会撤销 ${meInfo.value.devices} 个设备令牌、下线 ${otherSessions} 个其它浏览器会话；当前这台保留。`,
    { title: '退出所有设备', okText: '全部退出', danger: true },
  )
  if (!ok) return
  othersBusy.value = true
  try {
    const r = await logoutOthers()
    flash(`已撤销 ${r.devices} 个设备令牌、下线 ${r.sessions} 个会话`)
    await reload()
  } catch (e) {
    fail(e)
  } finally {
    othersBusy.value = false
  }
}

async function approvePair(): Promise<void> {
  err.value = ''
  const code = pairCode.value.replace(/\s+/g, '')
  if (!code) {
    fail(new Error('请输入电视上显示的配对码'))
    return
  }
  pairBusy.value = true
  try {
    await pairApprove(code, pairName.value.trim() || 'TV')
    pairCode.value = ''
    await reload()
    flash('已批准，电视会自动拿到令牌')
  } catch (e) {
    fail(e)
  } finally {
    pairBusy.value = false
  }
}

async function rotateKey(): Promise<void> {
  err.value = ''
  /*
   * 二次确认：旧 key 在服务端只存哈希、无法找回，误触一次所有已导入的
   * CatVod 客户端全部 403。
   */
  const ok = await confirmDialog(
    '重新生成配置密钥？\n已经导入过旧地址的客户端（Fongmi / TVBox 等）会立即 403，需要更新地址；旧密钥无法找回。',
    { title: '轮换配置密钥', okText: '轮换', danger: true },
  )
  if (!ok) return
  keyBusy.value = true
  try {
    const r = await rotateConfigKey()
    configKey.value = r.key
    hasConfigKey.value = true
    flash('配置密钥已轮换：旧地址立即失效，需要重新导入客户端')
    void loadEvents()
  } catch (e) {
    fail(e)
  } finally {
    keyBusy.value = false
  }
}

async function copy(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    flash('已复制')
  } catch {
    // 老 WebView / 非安全上下文没有 clipboard API —— 选中让用户手抄
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    ta.remove()
    flash('已复制')
  }
}

async function logout(): Promise<void> {
  await doLogout()
  await router.replace({ name: 'login' })
}

onMounted(reload)
</script>

<template>
  <div class="space-y-4">
    <div class="flex flex-wrap items-center gap-2">
      <span class="text-xs text-ink-3">当前登录</span>
      <span class="rounded-full bg-accent/15 px-2 py-0.5 text-xs font-medium text-accent">
        {{ username || 'admin' }}
      </span>
      <button
        type="button"
        class="ml-auto rounded-md border border-line px-3 py-1.5 text-xs text-ink-2 transition hover:border-hot/60 hover:text-hot"
        @click="logout"
      >
        退出登录
      </button>
    </div>

    <p v-if="msg" class="text-[12px] text-ok">{{ msg }}</p>
    <p v-if="err" class="text-[12px] text-hot">{{ err }}</p>

    <!-- 改密码 -->
    <div class="rounded-lg border border-line p-3">
      <h3 class="mb-2 text-sm font-medium text-ink">修改密码</h3>
      <form class="space-y-2" @submit.prevent="submitPassword">
        <input
          v-model="oldPwd"
          :type="showPwd ? 'text' : 'password'"
          autocomplete="current-password"
          placeholder="当前密码"
          class="w-full rounded-md border border-line bg-base px-3 py-2 text-sm text-ink outline-none focus:border-accent"
        />
        <div class="grid gap-2 sm:grid-cols-2">
          <input
            v-model="newPwd"
            :type="showPwd ? 'text' : 'password'"
            autocomplete="new-password"
            placeholder="新密码（至少 10 位）"
            class="rounded-md border border-line bg-base px-3 py-2 text-sm text-ink outline-none focus:border-accent"
          />
          <input
            v-model="newPwd2"
            :type="showPwd ? 'text' : 'password'"
            autocomplete="new-password"
            placeholder="确认新密码"
            class="rounded-md border border-line bg-base px-3 py-2 text-sm text-ink outline-none focus:border-accent"
          />
        </div>
        <div class="flex flex-wrap items-center gap-3">
          <span v-if="pwdStrength.text" class="text-[11px]" :class="pwdStrength.cls">
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
        <button
          type="submit"
          :disabled="pwdBusy"
          class="w-full rounded-md bg-accent px-3 py-2 text-sm font-medium text-white transition disabled:opacity-50"
        >
          {{ pwdBusy ? '提交中…' : '更新密码' }}
        </button>
      </form>
      <p class="mt-2 text-[11px] leading-relaxed text-ink-3">
        改密会让<strong class="text-ink-2">当前所有登录</strong>失效：{{ meInfo.sessions }}
        个浏览器会话需重新登录、{{ meInfo.devices }} 个设备令牌需重新配对。纯 HTTP
        部署下只有在服务器本机或已配置 TLS 时才能改密。
      </p>
    </div>

    <!-- 设备令牌 -->
    <div class="rounded-lg border border-line p-3">
      <div class="mb-2 flex flex-wrap items-baseline gap-2">
        <h3 class="text-sm font-medium text-ink">设备令牌</h3>
        <span class="text-[11px] text-ink-3">TV / PC 壳连接公网后端时用，最多 20 个</span>
      </div>

      <div v-if="newToken" class="mb-3 rounded-md border border-accent/40 bg-accent/10 p-2">
        <div class="mb-1 text-[11px] text-ink-2">
          「{{ newTokenName }}」的令牌（只显示这一次，关掉就找不回）：
        </div>
        <div class="flex items-center gap-2">
          <code class="min-w-0 flex-1 truncate rounded bg-base px-2 py-1 text-[12px] text-ink">{{ newToken }}</code>
          <button
            type="button"
            class="shrink-0 rounded border border-line px-2 py-1 text-[11px] text-ink-2 hover:text-ink"
            @click="copy(newToken)"
          >
            复制
          </button>
        </div>
      </div>

      <div v-if="devices.length" class="mb-3 overflow-x-auto">
        <table class="w-full min-w-[40rem] text-left text-[12px]">
          <thead class="text-ink-3">
            <tr>
              <th class="py-1 pr-2 font-medium">名称</th>
              <th class="py-1 pr-2 font-medium">权限</th>
              <th class="py-1 pr-2 font-medium">绑定 IP</th>
              <th class="py-1 pr-2 font-medium">最近 IP</th>
              <th class="py-1 pr-2 font-medium">最近使用</th>
              <th class="py-1 pr-2 font-medium">到期</th>
              <th class="py-1 font-medium"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in devices" :key="d.id" class="border-t border-line/60">
              <td class="py-1.5 pr-2 text-ink">
                {{ d.name }}
                <!-- 当前这台：撤销按钮在它旁边，必须先标清楚 -->
                <span
                  v-if="meInfo.deviceId && d.id === meInfo.deviceId"
                  class="ml-1 rounded bg-accent/15 px-1.5 py-0.5 text-[10px] text-accent"
                >
                  本机
                </span>
              </td>
              <td class="py-1.5 pr-2 text-ink-2">{{ d.scope === 'read' ? '只读' : '完整' }}</td>
              <td class="py-1.5 pr-2 text-ink-2">{{ d.bindIp || '不限' }}</td>
              <td class="py-1.5 pr-2 text-ink-2">{{ d.lastIp || '—' }}</td>
              <td class="py-1.5 pr-2 text-ink-2">{{ fmtTime(d.lastUsed) }}</td>
              <td class="py-1.5 pr-2 text-ink-2">{{ d.expiresAt ? fmtTime(d.expiresAt) : '永久' }}</td>
              <td class="py-1.5 text-right">
                <button
                  type="button"
                  class="rounded border border-line px-2 py-1 text-[11px] text-ink-3 transition hover:border-hot/60 hover:text-hot"
                  @click="removeDevice(d)"
                >
                  撤销
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else-if="loading" class="mb-3 text-[12px] text-ink-3">正在读取…</p>
      <p v-else class="mb-3 text-[12px] text-ink-3">还没有设备令牌。</p>

      <!-- 换机/怀疑泄露时的一键清理：撤销全部设备 + 下线其它浏览器，保留当前 -->
      <div v-if="devices.length || meInfo.sessions > 1" class="mb-3 flex flex-wrap items-center gap-2">
        <button
          type="button"
          :disabled="othersBusy"
          class="rounded border border-line px-3 py-1.5 text-[12px] text-ink-2 transition hover:border-hot/60 hover:text-hot disabled:opacity-50"
          @click="revokeOthers"
        >
          {{ othersBusy ? '处理中…' : '退出所有设备（保留当前）' }}
        </button>
        <span class="text-[11px] text-ink-3">
          当前：{{ meInfo.devices }} 个设备令牌 / {{ meInfo.sessions }} 个浏览器会话
        </span>
      </div>

      <form class="grid gap-2 sm:grid-cols-2" @submit.prevent="submitDevice">
        <input
          v-model="devName"
          placeholder="设备名（客厅电视）"
          class="rounded-md border border-line bg-base px-3 py-2 text-sm text-ink outline-none focus:border-accent"
        />
        <input
          v-model="devBindIp"
          placeholder="绑定 IP（可空）"
          class="rounded-md border border-line bg-base px-3 py-2 text-sm text-ink outline-none focus:border-accent"
        />
        <TileSelect
          :model-value="devScope"
          label="权限"
          :options="scopeOptions"
          @update:model-value="(v) => (devScope = v as 'full' | 'read')"
        />
        <TileSelect
          :model-value="devDays"
          label="有效期"
          :options="dayOptions"
          @update:model-value="(v) => (devDays = Number(v))"
        />
        <button
          type="submit"
          :disabled="devBusy || !canCreateDevice"
          class="rounded-md bg-accent px-3 py-2 text-sm font-medium text-white transition disabled:opacity-50 sm:col-span-2"
        >
          {{ devBusy ? '生成中…' : '生成令牌' }}
        </button>
      </form>
      <p class="mt-2 text-[11px] leading-relaxed text-ink-3">
        只读令牌只能浏览，不能改配置或播放记录；绑定 IP 后该令牌只在指定来源可用。
      </p>
      <!-- 上限时按钮是灰的，不说清原因用户只会以为坏了 -->
      <p v-if="!canCreateDevice" class="mt-1 text-[11px] leading-relaxed text-hot">
        已达到 20 个令牌上限：先在列表里「撤销」不用的设备，再生成新的。
      </p>
    </div>

    <!-- TV 配对 -->
    <div class="rounded-lg border border-line p-3">
      <h3 class="mb-1 text-sm font-medium text-ink">TV 配对</h3>
      <p class="mb-2 text-[11px] leading-relaxed text-ink-3">
        电视上点「使用配对码登录」会显示 6 位码，在这里批准，电视自动获得令牌（不用手抄长串）。
      </p>
      <form class="grid gap-2 sm:grid-cols-3" @submit.prevent="approvePair">
        <input
          v-model="pairCode"
          inputmode="numeric"
          autocomplete="one-time-code"
          placeholder="配对码"
          class="rounded-md border border-line bg-base px-3 py-2 text-sm tracking-widest text-ink outline-none focus:border-accent"
        />
        <input
          v-model="pairName"
          placeholder="设备名（TV）"
          class="rounded-md border border-line bg-base px-3 py-2 text-sm text-ink outline-none focus:border-accent"
        />
        <button
          type="submit"
          :disabled="pairBusy"
          class="rounded-md bg-accent px-3 py-2 text-sm font-medium text-white transition disabled:opacity-50"
        >
          {{ pairBusy ? '提交中…' : '批准' }}
        </button>
      </form>
    </div>

    <!-- configKey -->
    <div class="rounded-lg border border-line p-3">
      <div class="mb-1 flex flex-wrap items-baseline gap-2">
        <h3 class="text-sm font-medium text-ink">源配置密钥（configKey）</h3>
        <span class="text-[11px]" :class="hasConfigKey ? 'text-ok' : 'text-ink-3'">
          {{ hasConfigKey ? '已启用' : '未启用' }}
        </span>
      </div>
      <p class="mb-2 text-[11px] leading-relaxed text-ink-3">
        CatVod 客户端拉 <code>/config.json</code>、<code>/spider-latest.jar</code> 时用
        <code>?key=</code> 鉴权，与设备令牌互不影响。轮换后旧地址立即失效。
      </p>
      <!-- 轮换后要说清"接下来干什么"：不更新客户端地址的人只会看到拉取失败 -->
      <p v-if="configKey" class="mb-2 text-[11px] leading-relaxed text-hot">
        新密钥只显示这一次。已经导入过旧地址的客户端（Fongmi / TVBox 等）会立刻 403 ——
        请把新 key 替换到它们的配置地址里。
      </p>
      <div v-if="configKey" class="mb-2 rounded-md border border-accent/40 bg-accent/10 p-2">
        <div class="mb-1 text-[11px] text-ink-2">新密钥（只显示这一次）：</div>
        <div class="flex items-center gap-2">
          <code class="min-w-0 flex-1 truncate rounded bg-base px-2 py-1 text-[12px] text-ink">{{ configKey }}</code>
          <button
            type="button"
            class="shrink-0 rounded border border-line px-2 py-1 text-[11px] text-ink-2 hover:text-ink"
            @click="copy(configKey)"
          >
            复制
          </button>
        </div>
        <!-- 完整地址：直接粘给 Fongmi/TVBox，省掉用户自己拼前缀拼错的坑 -->
        <div class="mt-2 space-y-1">
          <div class="text-[11px] text-ink-2">完整地址（粘贴到客户端）：</div>
          <div v-for="u in configUrls" :key="u" class="flex items-center gap-2">
            <code class="min-w-0 flex-1 truncate rounded bg-base px-2 py-1 text-[11px] text-ink-3">{{
              u
            }}</code>
            <button
              type="button"
              class="shrink-0 rounded border border-line px-2 py-1 text-[11px] text-ink-2 hover:text-ink"
              @click="copy(u)"
            >
              复制
            </button>
          </div>
        </div>
      </div>
      <button
        type="button"
        :disabled="keyBusy"
        class="rounded-md border border-line px-3 py-1.5 text-xs text-ink-2 transition hover:border-accent hover:text-accent disabled:opacity-50"
        @click="rotateKey"
      >
        {{ keyBusy ? '轮换中…' : hasConfigKey ? '轮换密钥' : '生成密钥' }}
      </button>
    </div>

    <!--
      最近安全事件（排障用）：回答"昨晚是不是有人在试密码""我刚才是不是误撤销了设备"。
      内存环形 200 条、重启清零 —— 它不是审计日志。
    -->
    <div class="rounded-lg border border-line p-3">
      <button
        type="button"
        class="flex w-full items-center gap-2 text-left"
        @click="eventsOpen = !eventsOpen"
      >
        <h3 class="text-sm font-medium text-ink">最近安全事件</h3>
        <span class="text-[11px] text-ink-3">{{ events.length }} 条</span>
        <span class="ml-auto text-[11px] text-ink-3">{{ eventsOpen ? '收起' : '展开' }}</span>
      </button>
      <div v-if="eventsOpen" class="mt-2 max-h-56 overflow-y-auto">
        <p v-if="!events.length" class="text-[11px] text-ink-3">
          还没有记录（后端重启后从零开始）
        </p>
        <div
          v-for="(e, i) in events"
          :key="i"
          class="flex flex-wrap items-baseline gap-2 border-t border-line/60 py-1 text-[11px]"
        >
          <span class="text-ink-2">{{ eventLabel(e) }}</span>
          <span class="text-ink-3">{{ fmtTime(e.at) }}</span>
          <span v-if="e.ip" class="text-ink-3">{{ e.ip }}</span>
          <span v-if="e.detail" class="text-ink-3">{{ e.detail }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
