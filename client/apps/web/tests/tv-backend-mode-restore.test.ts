import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * TV 启动时恢复上次的后端形态（2026-11-04 用户报）。
 *
 * 现象：上次在设置里点了「启用本机执行」，关掉再打开又要去设置页开一次；
 * 反过来，之前连外部后端的，也不该被本机逻辑接管。
 *
 * 根因：点「启用本机执行」时前端只写了 localStorage['meow.api']，壳的
 * prefs[K_API] 还是旧值；启动时壳把 prefs 注入回 localStorage，本机选择
 * 就被覆盖掉了。
 *
 * ★ 同一天的第二轮：自动拉起网关让极米（32 位）每次冷启动都在同一个点
 * SIGABRT —— 所以"恢复"必须带**设备级闸门**，不能无条件起网关。
 */
const SRC = join(__dirname, '..', '..', 'tv', 'app', 'src', 'main', 'java', 'com', 'meow', 'tv')
const MAIN = readFileSync(join(SRC, 'MainActivity.java'), 'utf8')
const BRIDGE = readFileSync(join(SRC, 'TvBridge.java'), 'utf8')

/**
 * 取一个方法的正文。
 *
 * 踩过（2026-11-06）：原来是"从签名起固定截 1800 字符"，结果并行会话在方法里
 * 加了几段注释，方法体变长 → `remove(K_API)` 落到窗口外，测试假红。
 * 改成按"行首 4 空格 + }"找方法结束；找不到才退回定长窗口。
 */
function methodBody(src: string, sig: string, len = 1800) {
  const at = src.indexOf(sig)
  expect(at).toBeGreaterThan(-1)
  const rest = src.slice(at)
  // 方法结尾 = 行首 4 空格 + }（本项目 Java 缩进固定这样）
  // ★ 只匹配到 '}'，不带尾随换行：仓库 blob 是 LF，但 Windows(autocrlf) 检出是
  //   CRLF，写成 '\n    }\n' 在这里永远匹配不到（踩过）。
  const end = rest.indexOf('\n    }')
  return end > 0 ? rest.slice(0, end) : rest.slice(0, len)
}

describe('TV 后端形态持久化', () => {
  it('MainActivity 有 api_mode 标记与恢复逻辑', () => {
    expect(MAIN).toMatch(/static final String K_MODE = "api_mode"/)
    expect(MAIN).toMatch(/private String restoreBackendMode\(\)/)
    // 启动时用的是 restoreBackendMode 的结果，不再是裸读 prefs
    expect(MAIN).toMatch(/String api = restoreBackendMode\(\)/)
  })

  it('老安装没有标记时按地址推断一次', () => {
    const body = methodBody(MAIN, 'private String restoreBackendMode()')
    expect(body).toContain('isLocalGateway(api) ? "local"')
    expect(body).toContain('"remote"')
  })

  it('启用本机执行的三个入口都写 prefs（地址 + 标记）', () => {
    expect(BRIDGE).toMatch(/private void persistLocalGateway\(\)/)
    // startLocalGateway / startLocalGatewayWith / bootstrapLocal
    expect((BRIDGE.match(/persistLocalGateway\(\)/g) || []).length).toBeGreaterThanOrEqual(2)
    expect(BRIDGE).toMatch(/putString\(MainActivity\.K_MODE, "local"\)/)
  })

  it('保存外部地址时把标记改回 remote', () => {
    const body = methodBody(BRIDGE, 'public String saveConn(', 1400)
    expect(body).toContain('MainActivity.isLocalGateway(u) ? "local" : "remote"')
  })

  /**
   * 2026-11-13 实测（用户追问"改完重启怎么又变回去了"）：
   *
   * 设置页「连接 → 后端地址 → 保存」走的是 saveApi()，原来只写了
   * localStorage；而壳每次启动都会 restoreBackendMode() → injectApiBase(prefs[K_API])
   * 把它盖掉。于是改完当场生效、重启又回旧地址。
   * 壳内保存必须同时写回 prefs（TvBridge.saveConn）。
   */
  it('设置页保存后端地址时，TV 壳内同时写回 prefs', () => {
    const settings = readFileSync(
      join(__dirname, '..', 'src', 'views', 'SettingsView.vue'),
      'utf8',
    )
    const at = settings.indexOf('async function saveAndReconnect()')
    expect(at).toBeGreaterThan(-1)
    /*
     * 取整个函数体（到下一个函数声明为止），不要用固定字符窗口：
     * 函数里加几行注释就会把 TV 分支挤出窗口（2026-11-27 实测踩到）。
     */
    const end = settings.indexOf('function restoreEmbeddedBackend()', at)
    const body = settings.slice(at, end > at ? end : at + 4000)
    expect(body, 'TV 壳内保存没有写回 prefs，重启会被旧值覆盖').toContain('b.saveConn(url')
    // 第二个参数是配置地址，不能传空串把手填的配置源清掉
    expect(body).toContain('prev.config')
  })
})

describe('本机网关的设备级闸门（防"一进就闪退"）', () => {
  it('32 位设备不自动起网关（实测延后起也会在 2 分钟内崩），并清掉网关地址', () => {
    const body = methodBody(MAIN, 'private String restoreBackendMode()')
    expect(body).toContain('android.os.Process.is64Bit()')
    expect(body).toContain('K_GW_FAIL')
    // 地址留着没人监听只会停在"连不上"，必须清掉退回"去设置"
    expect(body).toContain('remove(K_API)')
  })

  it('崩溃环保护：启动前落标记，稳定 30 秒才清', () => {
    expect(MAIN).toMatch(/static final String K_GW_BOOT = "gw_boot_at"/)
    expect(MAIN).toMatch(/static final String K_GW_FAIL = "gw_failed"/)
    expect(MAIN).toMatch(/GW_STABLE_MS = 30_000L/)
    const body = methodBody(MAIN, 'static boolean startGatewayWatched(', 1600)
    expect(body).toMatch(/putLong\(K_GW_BOOT/)
    // ★ 不能在"端口就绪"就清：实测崩点在就绪后 1~2 秒
    expect(body).toContain('postDelayed')
    expect(body).toMatch(/putBoolean\(K_GW_FAIL, false\)\.remove\(K_GW_BOOT\)/)
  })

  it('上次启动留下 K_GW_BOOT → 本次判定为"崩过"', () => {
    const body = methodBody(MAIN, 'private String restoreBackendMode()')
    expect(body).toMatch(/getLong\(K_GW_BOOT/)
    expect(body).toMatch(/putBoolean\(K_GW_FAIL, true\)/)
  })

  it('前端零配置自启（bootstrapLocal）也必须过同一道闸', () => {
    // 最新版前端在 localStorage 没有 meow.api 时会调 bootstrapLocal()，
    // 那里若无条件 GatewayServer.start()，32 位设备每次冷启动都会崩
    const body = methodBody(BRIDGE, 'public String bootstrapLocal()', 4000)
    expect(body).toContain('android.os.Process.is64Bit()')
    expect(body).toContain('startGatewayWatched')
  })

  it('没配后端时不要每 5 秒重试（首页会一直闪）', () => {
    const APP = readFileSync(join(__dirname, '..', 'src', 'App.vue'), 'utf8')
    expect(APP).toContain('function backendConfigured()')
    expect(APP).toContain('err && !retryTimer && backendConfigured()')
  })

  it('手动启用不受"自动启动"闸门影响（用户明确要求就用）', () => {
    const body = methodBody(BRIDGE, 'private void persistLocalGateway()', 800)
    expect(body).toMatch(/putBoolean\(MainActivity\.K_GW_FAIL, false\)/)
    // 手动入口不能因为 32 位就返回空：实测这台投影仪手动开网关能用
    const manual = methodBody(BRIDGE, 'public String startLocalGateway()', 400)
    expect(manual).not.toContain('is64Bit')
  })
})
