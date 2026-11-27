/**
 * 2026-11-26 运行时归位 + iPhone 真机反馈（第 15 轮）门禁：
 *
 *  1. 导入配置时 `.py` 不再被当成「运行时不支持」整类丢掉（实测 110 个 py 站全丢）
 *  2. 运行时状态从「搜索与源」移到独立分区；PC 留在「本机插件」里
 *  3. iOS CPython：pyext 必须带 ad-hoc 签名，启动结果写 policy.pyRuntime
 *  4. 设置页跳转按**实际遮挡底边**让位（顶栏 + 吸顶胶囊），不在上面再叠一层
 *  5. 长 URL 不许撑宽页面（整页横向拖动）
 */
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const WEB = join(__dirname, '..')
const ROOT = join(WEB, '..', '..', '..')
const read = (p: string) => readFileSync(p, 'utf8').replace(/\r\n/g, '\n')

const api = read(join(ROOT, 'server', 'host', 'src', 'shared', 'ApiServer.java'))
const settings = read(join(WEB, 'src', 'views', 'SettingsView.vue'))
const panel = read(join(WEB, 'src', 'components', 'common', 'RuntimeDepsPanel.vue'))
const tvnav = read(join(ROOT, 'client', 'apps', 'tv', 'app', 'src', 'main', 'assets', 'tv-nav.js'))
const tokens = read(join(WEB, 'src', 'styles', 'tokens.css'))
const ci = read(join(ROOT, '.github', 'workflows', '_ios.yml'))
const pyRuntime = read(join(ROOT, 'client', 'apps', 'ios', 'Meow', 'PythonRuntime.swift'))
const cBridge = read(join(ROOT, 'client', 'apps', 'ios', 'Meow', 'MeowPython.c'))
const bootstrap = read(join(ROOT, 'client', 'apps', 'ios', 'Meow', 'Bootstrap.swift'))
const memC = read(join(ROOT, 'client', 'apps', 'ios', 'Meow', 'MeowMemory.c'))
const memSwift = read(join(ROOT, 'client', 'apps', 'ios', 'Meow', 'MemoryProbe.swift'))
const aiTools = read(join(WEB, 'src', 'ai', 'tools.ts'))

describe('导入：.py 站点保留', () => {
  it('脚本分支接受 .py，只有 node:/py: 内联被拒绝', () => {
    expect(api).toContain(
      'isScriptApi(api) && !api.startsWith("node:") && !api.startsWith("py:")',
    )
    // 旧判据：node: 或 .py 一律 reason = "运行时不支持"
    expect(api).not.toContain('api.startsWith("node:") || api.endsWith(".py")')
    expect(api).toContain('运行时不支持（node: 内联脚本没有对应运行时）')
    expect(api).toContain('运行时不支持（py: 内联脚本没有对应运行时）')
  })

  it('执行期按 /health caps 回答 .py 能不能跑', () => {
    expect(api).toContain('private static boolean runtimeSupportsPy()')
    expect(api).toContain('jsRuntime 只支持 .js：.py 源需要 pyrt 或内嵌 CPython')
  })

  it('运行时状态端点显式注册（不能落进站点分派）', () => {
    expect(api).toContain('"/api/runtime/status".equals(path)')
    expect(api).toContain('private static String runtimeStatusJson()')
  })
})

describe('运行时状态放在哪', () => {
  it('独立分区：TV(模块) / fpk(依赖) / iOS(端内)；PC 放本机插件', () => {
    expect(settings).toContain("const fpkShell = ref(currentShell() === 'fpk')")
    expect(settings).toContain(
      'const showRuntimeNav = computed(() => (tvMode && tvModule) || ios || fpkShell.value)',
    )
    // fpk 身份是异步探测：设置页要自己补一次，否则分区永远不出现
    expect(settings).toContain('detectFpkShell()')
    expect(settings).toContain("{ id: 'runtime', label: '运行时' }")
    // 分区标题必须与导航 label 一致（settings-nav-sync 的判据）
    expect(settings).toContain('<h2 class="set-title">运行时</h2>')
    /*
     * ★ 模板里用到 ≠ 组件会渲染：RuntimeDepsPanel 必须显式 import。
     *   漏掉时 Vue 只在 console 报 Unresolved component，「运行时」分区
     *   只剩标题 + 副标题（2026-11-27 iOS 真机实测；静态门禁以前只查了
     *   `<RuntimeDepsPanel` 的出现，用法在模板里也算，所以没拦住）。
     */
    expect(settings).toContain(
      "import RuntimeDepsPanel from '@/components/common/RuntimeDepsPanel.vue'",
    )
    // PC：运行时状态并入插件列表（状态胶囊右侧标 JS / PY），不再单开一块
    // （2026-11-27 用户口径：去掉「脚本运行时」独立显示）
    expect(settings).not.toContain('<RuntimeDepsPanel :plugins="plugins" />')
    expect(settings).toContain('runtimeBadge')
    expect(settings).toContain("drpy: 'JS', pyrt: 'PY'")
    // iOS 面板的「去配置」要能跳到 JS 运行时输入框
    expect(settings).toContain('data-jsruntime')
    expect(settings).toContain('@goto="gotoSelector"')
  })

  it('面板按壳切换，iOS 读 policy.pyRuntime，不再靠猜', () => {
    expect(panel).toContain("mode.value = 'ios'")
    expect(panel).toContain("mode.value = 'desktop'")
    expect(panel).toContain('pyRuntime')
    expect(panel).toContain('/api/runtime/status')
    expect(panel).toContain('未启用（回退 Node）')
    expect(panel).toContain('端内 CPython + drpy')
    expect(panel).toContain('端内 Node（仅 JS）')
  })

  it('「搜索与源」不再渲染运行时依赖块', () => {
    const src = settings.slice(settings.indexOf('data-sec="source"'))
    const next = src.indexOf('data-sec=')
    // 取 source 分区到下一个 data-sec 之间
    expect(src.slice(0, next)).not.toContain('<RuntimeDepsPanel')
  })
})

describe('iOS CPython 可诊断', () => {
  it('pyext 在构建期打 ad-hoc 签名（侧载器漏签也能 dlopen）', () => {
    expect(ci).toContain('Ad-hoc sign CPython payload')
    expect(ci).toContain('codesign --force --sign - --timestamp=none')
    expect(ci).toContain('codesign --verify --strict "$APP/Frameworks/pyext/_ssl.cpython-312-iphoneos.so"')
  })

  it('C 桥：日志重定向 + 8MB 栈，import 失败有 traceback', () => {
    expect(cBridge).toContain('freopen(g_log, "a", stderr)')
    expect(cBridge).toContain('pthread_attr_setstacksize(&attr, 8 * 1024 * 1024)')
    expect(cBridge).toContain('meow_py_start(const char *script_path, const char *log_path)')
    expect(cBridge).toContain('PyErr_Print()')
  })

  it('启动结果写 policy.pyRuntime（state/error/at）', () => {
    expect(pyRuntime).toContain('obj["pyRuntime"] = st')
    expect(pyRuntime).toContain('record("running", "")')
    expect(pyRuntime).toContain('record("failed"')
    expect(pyRuntime).toContain('logTail()')
    // 生产调用必须传日志路径
    expect(pyRuntime).toContain('meow_py_start(script.path, pyLogPath.path)')
  })
})

describe('设置页落点与横向溢出', () => {
  it('跳转让位按实际遮挡底边算（顶栏/胶囊 rect），并多校正两次', () => {
    expect(settings).toContain('const headerEl = document.querySelector<HTMLElement>(\'.app-header\')')
    expect(settings).toContain('return Math.max(headerBottom, navBottom) + 24')
    expect(settings).toContain('window.setTimeout(apply, 700)')
    expect(settings).toContain('window.setTimeout(apply, 1200)')
  })

  it('TV 遥控器移焦也把吸顶胶囊算进顶部遮挡', () => {
    expect(settings).toContain('data-sticky-top')
    expect(tvnav).toContain("document.querySelectorAll('[data-sticky-top]')")
    expect(tvnav).toContain('br.top <= h + 1 && br.bottom > h')
  })

  it('长串断行 + 内容列裁剪，页面不横向拖动', () => {
    expect(tokens).toContain('overflow-wrap: anywhere')
    expect(settings).toContain('overflow-x-hidden')
    /*
     * 裁剪必须带出血位：卡片铺满内容宽度时，闪框 outline（offset 2 + 2px）
     * 整条在裁剪区外 → 真机看到“聚焦时两侧圆环被裁”。
     */
    expect(settings).toContain('-m-2 flex min-w-0 flex-col gap-4 overflow-x-hidden p-2')
    // 插件源的 URL 行必须能给 flex 子项收缩
    expect(settings).toContain('min-w-0 flex-1 truncate font-mono')
  })

  it('后台挂起不能算进 CPython 探活时间', () => {
    // 启动后立刻切后台：墙钟计时会把“还没调度”误判成失败
    expect(pyRuntime).toContain('UIApplication.shared.applicationState == .background')
    expect(pyRuntime).toContain('scheduleOnForeground()')
    expect(pyRuntime).toContain('UIApplication.didBecomeActiveNotification')
    expect(pyRuntime).toContain('waited += Date().timeIntervalSince(began) + 0.4')
    // py.log 尾巴要从本轮标记开始截，否则只剩后续 NSLog
    expect(pyRuntime).toContain('options: .backwards')
    expect(pyRuntime).toContain('没有 [meow-py] 记录')
  })

  it('drpy 的 /health 没有 caps 也不能当成支持 .py', () => {
    expect(api).toContain('"meow-drpy".equals(o.optString("service", ""))')
    // 未知值只缓存 3 秒，不能让“启动时没探到”冻结 60 秒
    expect(api).toContain('RT_CAP_TTL_MS + 3_000L')
  })

  it('端内 CPython 的流为空也不能让 pyrt 静默退出', () => {
    const pyrt = read(join(ROOT, 'server', 'pyrt', 'pyrt.py'))
    // Python-Apple-support 默认不配 C stdio：sys.stdout/stderr 可能是 None
    expect(pyrt).toContain('_MEOW_PY_LOG')
    expect(pyrt).toContain('if sys.stdout is None or sys.stderr is None')
    expect(pyrt).toContain('[pyrt] fatal:')
    expect(pyrt).toContain('except Exception:\n        pass')
    // C 桥直接落异常类型/消息，不依赖 PyErr_Print
    expect(cBridge).toContain('static void dump_py_error')
    expect(cBridge).toContain('[meow-py] exception: %s: ')
    expect(cBridge).toContain('dump_py_error(g_log)')
    /*
     * PyRun_FileExFlags 不会设 __file__：pyrt.py 用它算 HERE，漏了就是
     * NameError（2026-11-26 真机第一条真实异常）。
     */
    expect(cBridge).toContain('PyDict_SetItemString(globals, "__file__", dunsel)')
    expect(pyRuntime).toContain('setenv("MEOW_PY_LOG"')
  })

  it('pyrt 看门狗 + 按需启动（对齐 node ensureServing）', () => {
    // 回前台/20s 定时器：探活失败就在已初始化的解释器里重跑 pyrt
    expect(pyRuntime).toContain('static func ensureRunning()')
    expect(pyRuntime).toContain('health dead after resume; rerun pyrt')
    expect(pyRuntime).toContain('NodeBridge.writeJsRuntime(url:')
    // 配置里没有 .py 站点不起解释器（省 30~50MB 常驻）
    expect(pyRuntime).toContain('guard hasPySites() else')
    expect(pyRuntime).toContain('record("disabled"')
    expect(pyRuntime).toContain('bare.hasSuffix(".py") || bare.hasPrefix("py:")')
    expect(bootstrap).toContain('PythonRuntime.ensureRunning()')
    expect(bootstrap).toContain('startPythonWatchdog()')
    expect(bootstrap).toContain('withTimeInterval: 20')
  })

  it('内存诊断进 health / 日志 / AI 工具', () => {
    // Swift 侧：mach task_info + os_proc_available_memory → gateway/mem.json
    expect(memC).toContain('MACH_TASK_BASIC_INFO')
    expect(memC).toContain('os_proc_available_memory')
    expect(memSwift).toContain('gateway/mem.json')
    expect(memSwift).toContain('low available memory')
    expect(bootstrap).toContain('MemoryProbe.start()')
    // 宿主并进 /api/health（iOS 才有文件）
    expect(api).toContain('core.DataPaths.read("mem.json")')
    expect(api).toContain('o.put("rssMB"')
    expect(api).toContain('o.put("memAvailMB"')
    // AI 的 meow_state 直接拿到内存，不用再教模型调别的
    expect(aiTools).toContain('memory?: { rssMB: number; availMB: number }')
    expect(aiTools).toContain('availMB < 200')
    // 设置页运行时面板也显示
    expect(panel).toContain('进程内存 RSS')
  })
})
