/**
 * 运行时口径统一 + TV Python 模块进更新中心（2026-11-28 用户实测，1.2.29 修）。
 *
 * 用户报的三件事：
 *   1. TV「运行时」分区那句副标题写着 "Python 模块"，可第一块明明是
 *      **Node 运行时**（18.20.4.24 / 端口 19341），用户直接读成了块标题；
 *   2. 同一件东西各端叫法不同（PC「弹幕聚合服务」/ TV「弹幕组件」、
 *      PC「Node 环境」/ TV 无标题、PC「Python 脚本运行时」/ TV「Python 运行时」）；
 *   3. TV 更新中心「检查全部」查不到 Python 运行时 —— UpdateKind 里没有 python，
 *      用户只能摸到「设置 → 运行时」单独点。
 *
 * 定案口径：**引擎叫「X 运行时」，业务包叫「X 组件」**。
 * 打包形态（插件 / 模块 APK / 载荷 / 随包）各端本来就不同，不在统一范围内。
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const WEB = join(__dirname, '..')
const read = (p: string) => readFileSync(p, 'utf8').replace(/\r\n/g, '\n')

const settings = read(join(WEB, 'src', 'views', 'SettingsView.vue'))
const center = read(join(WEB, 'src', 'components', 'common', 'UpdateCenter.vue'))
const store = read(join(WEB, 'src', 'stores', 'updateCenter.ts'))
const panel = read(join(WEB, 'src', 'components', 'common', 'RuntimeDepsPanel.vue'))

describe('运行时口径：分区标题与命名', () => {
  it('TV 分区副标题按「谁执行什么」写，不再只写 Python 模块', () => {
    expect(settings).toContain("'Node 运行时（弹幕）· QuickJS（.js）· Python 运行时（.py）'")
    // 只看返回语句（注释里留着旧措辞做历史说明，不该被它误伤）
    const at = settings.indexOf('const runtimeHint')
    expect(at).toBeGreaterThan(-1)
    const block = settings.slice(at, at + 900)
    expect(block, '旧的错副标题又回来了').not.toMatch(/if \(tvMode\) return 'Python 模块/)
  })

  it('Node 块有独立小标题，不会被读成分区标题', () => {
    expect(settings).toContain('>Node 运行时</span>')
    expect(settings).toContain('弹幕服务（danmu_api）跑在它上面')
  })

  it('插件显示名统一：引擎=运行时，业务包=组件', () => {
    expect(settings).toContain("'danmu-api': '弹幕组件'")
    expect(settings).toContain("node: 'Node 运行时'")
    expect(settings).toContain("drpy: 'drpy 运行时'")
    expect(settings).toContain("pyrt: 'Python 运行时'")
    expect(settings, '旧名「Node 环境」又回来了').not.toContain("node: 'Node 环境'")
  })

  it('TV 的 Python 状态只画一遍：面板不再渲染 TV 分支', () => {
    // 设置页对 TV 不挂面板（三块各自带安装/检查）
    expect(settings).toContain('<div v-if="!tvModule">')
    expect(panel, '面板又把 TV 分支加回来了').not.toContain("mode === 'tv'")
    expect(panel, 'pyState 又回来了').not.toContain('pyState')
    expect(panel, '面板又画了一遍「Python 模块」').not.toContain('>Python 模块</span>')
  })
})

describe('更新中心：Python 运行时也进「检查全部」', () => {
  it('UpdateKind 覆盖 python，检查全部/后台检查都带上它', () => {
    expect(store).toContain("'module' | 'python'")
    expect(store).toContain('checkPython(false)')
    expect(store).toContain("if (autoCheckDue('python')) jobs.push(checkPython(true))")
    // 自动检查复用「运行时」那一个勾选框（autoCheckModule）
    expect(store).toContain("kind === 'module' || kind === 'python'")
  })

  it('行与标签：Node 运行时 / Python 运行时 / 弹幕组件', () => {
    expect(center).toContain('data-update-row="python"')
    expect(center).toContain('>Python 运行时</span>')
    expect(center).toContain('>Node 运行时</span>')
    expect(center).toContain("'壳 / Node·Python 运行时 / 弹幕组件'")
    expect(center).toContain('checkPython(false)')
    // 面板状态读不到就整行不显示（老壳没有 pyModule 桥）
    expect(center).toContain("meowTv?.pyModuleState === 'function'")
  })
})

describe('checkPython 行为（假桥）', () => {
  beforeAll(() => {
    ;(window as unknown as { meowTv: Record<string, unknown> }).meowTv = {
      moduleCheck: () => '{}',
      pyModuleState: () =>
        JSON.stringify({
          installed: true,
          version: '0.1.23',
          running: true,
          port: 19351,
          abi: 'x86_64',
        }),
      pyModuleCheck: () =>
        JSON.stringify({
          ok: true,
          version: '0.1.24',
          size: 22095844,
          sha256: 'abc123',
          url: 'https://example.test/tv-python-x86_64-0.1.24.apk',
          abi: 'x86_64',
        }),
    }
  })

  afterAll(() => {
    delete (window as unknown as { meowTv?: unknown }).meowTv
  })

  it('已装 0.1.23 + 清单 0.1.24 → 标记可更新，且只是检查不下载', async () => {
    const m = await import('../src/stores/updateCenter')
    const has = await m.checkPython(true)
    expect(has).toBe(true)
    expect(m.pythonInstalled.value).toBe(true)
    expect(m.pythonCurrent.value).toBe('0.1.23')
    expect(m.pythonLatest.value?.version).toBe('0.1.24')
    expect(m.pythonLatest.value?.abi).toBe('x86_64')
    expect(m.pythonUpdateChecked.value).toBe(true)
    // 检查结束必须复位，否则按钮永久转圈
    expect(m.checking.python).toBe(false)
  })

  it('清单同版本 → 不给「可更新」', async () => {
    ;(window as unknown as { meowTv: Record<string, unknown> }).meowTv = {
      moduleCheck: () => '{}',
      pyModuleState: () =>
        JSON.stringify({ installed: true, version: '0.1.24', running: true, port: 19351 }),
      pyModuleCheck: () => JSON.stringify({ ok: true, version: '0.1.24', size: 1, url: 'u' }),
    }
    const m = await import('../src/stores/updateCenter')
    const has = await m.checkPython(true)
    expect(has).toBe(false)
    expect(m.pythonLatest.value).toBeNull()
    expect(m.pythonUpdateChecked.value).toBe(true)
  })

  it('未安装 → 只报状态，不去比对版本', async () => {
    ;(window as unknown as { meowTv: Record<string, unknown> }).meowTv = {
      moduleCheck: () => '{}',
      pyModuleState: () => JSON.stringify({ installed: false }),
      pyModuleCheck: () => {
        throw new Error('未安装时不该查清单')
      },
    }
    const m = await import('../src/stores/updateCenter')
    const has = await m.checkPython(true)
    expect(has).toBe(false)
    expect(m.pythonInstalled.value).toBe(false)
    expect(m.pythonUpdateChecked.value).toBe(false)
  })
})

/**
 * 升级链路的口径（2026-11-28 审计后修）：
 *   1. 依赖必须写进清单（否则装 drpy 不会带 Node，启动报「找不到可执行文件」）
 *   2. desc / display_name 里不能留死版本号（用户会看到 version 与说明对不上）
 *   3. Docker 的 pyrt/drpy 镜像不能因为「tag 已存在」永远跳过（镜像停在 0.1.0）
 */
describe('升级链路：依赖声明 / 用户可见文案 / 运行时镜像重建', () => {
  const ROOT = join(WEB, '..', '..', '..')
  const sync = read(join(ROOT, 'deploy', 'scripts', 'sync-packages.ps1'))
  const units = read(join(ROOT, 'deploy', 'units.json'))
  const dockerYml = read(join(ROOT, '.github', 'workflows', '_docker.yml'))
  const dockerPs1 = read(join(ROOT, 'deploy', 'scripts', 'build-push-docker.ps1'))

  it('drpy / danmu 声明 requires: node，并写进清单', () => {
    expect(units).toMatch(/"id":\s*"drpy"[\s\S]{0,200}"requiresPlugins":\s*\[\s*"node"\s*\]/)
    expect(units).toMatch(/"id":\s*"danmu"[\s\S]{0,200}"requiresPlugins":\s*\[\s*"node"\s*\]/)
    expect(sync).toContain('$unitsDeps')
    expect(sync).toContain('requiresPlugins')
    // requires 必须是「声明优先」，不能又被旧值覆盖
    expect(sync).toContain('if ($unitsDeps.ContainsKey($pid2)) { $entry.requires = @($unitsDeps[$pid2]) }')
  })

  it('desc 不再沿用自动生成的版本串；display_name 用统一叫法', () => {
    expect(sync).toContain('function Test-AutoDesc')
    expect(sync).toContain('猫窝插件|猫窝弹幕组件|TV 运行时模块|TV Python 运行时')
    expect(sync).toContain("'drpy'      = 'drpy 运行时'")
    expect(sync).toContain("'danmu-api' = '弹幕组件'")
    expect(sync).toContain("'node'      = 'Node 运行时'")
    expect(sync).toContain("'pyrt'      = 'Python 运行时'")
    // 插件块不能无条件沿用旧 display_name（旧值是「apiserver 安装包」）
    expect(sync).toContain('-not $pluginDisplay.ContainsKey($pid2)')
  })

  it('Docker：运行时载荷变了就强制重打 + 提供手动补欠账入口', () => {
    expect(dockerYml).toContain('fetch-depth: 0')
    expect(dockerYml).toContain('判断运行时镜像是否需要重打')
    expect(dockerYml).toContain("if ('\${{ steps.rt.outputs.pyrt }}' -eq '1') { \$p.RebuildPyrt = \$true }")
    expect(dockerYml).toContain("if ('\${{ steps.rt.outputs.drpy }}' -eq '1') { \$p.RebuildDrpy = \$true }")
    // 历史欠账用 dispatch 输入补：自动判据只看「自上个 tag 起」的差集
    expect(dockerYml).toContain('rebuild_pyrt')
    expect(dockerYml).toContain('rebuild_drpy')
  })

  it('Docker 构建 tag 取插件 manifest 版本，不写死 0.1.0', () => {
    expect(dockerPs1).toContain("server\\plugin-manifests\\pyrt.manifest.json")
    expect(dockerPs1, 'pyrt 构建 tag 又写死 0.1.0 了').not.toContain("$PyrtVersion = '0.1.0'\n}")
  })

  it('x86_64 TV 明说 .js 不可用（上游 AAR 只有 ARM）', () => {
    expect(settings).toContain('内置 JS 引擎只有 ARM 版本')
    expect(settings).toContain('/^x86/.test(String(modState.abi')
  })
})

/**
 * 中期分层（2026-11-28）：JS / PY 两个引擎分键，互不代理。
 *
 * 背景：以前宿主只认一个 `policy.jsRuntime`，pyrt 抢占它再把 .js 转发给 drpy ——
 * 结果 pyrt 挂了/被卸，.js 一起死；而且 TV 在 QuickJS 改固定 19381 之后，
 * Python 再也抢不到 jsRuntime，整类 .py 源被判"不支持"（.77 实测）。
 */
describe('运行时中期分层：jsRuntime / pyRuntimeAddr 分键', () => {
  const ROOT = join(WEB, '..', '..', '..')
  const api = read(join(ROOT, 'server', 'host', 'src', 'shared', 'ApiServer.java'))
  const drpyMf = read(join(ROOT, 'server', 'plugin-manifests', 'drpy.manifest.json'))
  const pyrtMf = read(join(ROOT, 'server', 'plugin-manifests', 'pyrt.manifest.json'))
  const tvGw = read(join(ROOT, 'client', 'apps', 'tv', 'app', 'src', 'main', 'java', 'com', 'meow', 'tv', 'GatewayServer.java'))
  const pyGradle = read(join(ROOT, 'client', 'apps', 'tv', 'runtime-python', 'build.gradle'))
  const compose = read(join(ROOT, 'deploy', 'docker', 'docker-compose.yml'))

  it('宿主：.py 优先 pyRuntimeAddr，未配才回落 jsRuntime，连不上再回落一次', () => {
    expect(api).toContain('private static String pyRuntimeAddr()')
    expect(api).toContain('MEOW_PY_RUNTIME')
    // 空 = 回落 jsRuntime（兼容老部署 / iOS 端内单地址）
    expect(api).toContain('String pyBase = isPyApi(api) ? pyRuntimeAddr() : ""')
    expect(api).toContain('boolean usePy = !pyBase.isEmpty()')
    // 独立 Python 引擎不可达 → 回落 jsRuntime
    expect(api).toContain('pyRuntimeAddr 不可达（')
    // 协议抽成公共方法（js/py 同一套 POST）
    expect(api).toContain('private static String postToScriptRuntime(')
    // health 暴露地址，排障能看出是"分开配"还是"单地址"
    expect(api).toContain('o.put("pyRuntimeAddr", pyRuntimeAddr())')
  })

  it('插件：drpy 独占 jsRuntime（force），pyrt 只写 pyRuntimeAddr', () => {
    expect(drpyMf).toContain('"key": "jsRuntime"')
    expect(drpyMf).toContain('"force": true')
    expect(pyrtMf).toContain('"key": "pyRuntimeAddr"')
    expect(pyrtMf).toContain('"force": true')
    // ★ 键名绝不能是 pyRuntime：iOS 用它存启动状态对象
    expect(pyrtMf).not.toContain('"key": "pyRuntime"')
  })

  it('TV：Python 端口只写 pyRuntimeAddr，并把旧 jsRuntime 交还 QuickJS', () => {
    expect(tvGw).toContain('publishPyRuntimeAddr(')
    expect(tvGw).toContain('isPyModulePort(')
    // 迁移：旧版写进 jsRuntime 的 py 端口要删掉，然后立刻让 QuickJS 接管
    expect(tvGw).toContain('if (removedLegacy) ensureLocalJsRuntime(ctx)')
    expect(tvGw).toContain('boolean oursLegacy = !old.isEmpty() && old.equals(obj.optString("pyRuntimeAddr"')
  })

  it('tv-python 模块：pyrt.py 构建期从唯一真源同步，不再留 fork', () => {
    expect(pyGradle).toContain("tasks.register('syncPyrtPayload'")
    expect(pyGradle).toContain("rootProject.file('../../../server/pyrt/pyrt.py')")
    expect(pyGradle).toContain("layout.buildDirectory.dir('generated/python')")
    // fork 文件不能回来
    expect(() => read(join(ROOT, 'client', 'apps', 'tv', 'runtime-python', 'src', 'main', 'python', 'pyrt.py'))).toThrow()
  })

  it('Docker：默认 .js→drpy、.py→pyrt', () => {
    expect(compose).toContain('MEOW_JS_RUNTIME: ${MEOW_JS_RUNTIME-http://drpy:9330}')
    expect(compose).toContain('MEOW_PY_RUNTIME: ${MEOW_PY_RUNTIME-http://pyrt:19331}')
  })
})
