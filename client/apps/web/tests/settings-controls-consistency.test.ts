import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * 设置页的控件必须统一。
 *
 * 起因（2026-10-30 用户连续截图两处）：
 *   1. 「搜索缓存时长」三个下拉写成原生 `<select>`，混在磁贴网格里 ——
 *      高度、圆角、点击反馈全不一致，而且原生弹层在深色主题下是系统白底；
 *   2. 离线缓存的「超限时」也是原生 `<select>`，同样的问题。
 *
 * 项目已有 `TileSelect`（磁贴样式的下拉）作为唯一正确形态，
 * 27 处设置项都用它。所以这条门禁很直接：设置页里不许出现原生 `<select>`。
 *
 * 为什么不在别处禁：LogsView 的模块筛选是紧凑工具条，原生 select 更合适；
 * 那里不在设置页的"磁贴网格"语境里。这条只约束设置页。
 */
const ROOT = join(__dirname, '..', '..', '..', '..')
const SETTINGS = join(ROOT, 'client', 'apps', 'web', 'src', 'views', 'SettingsView.vue')
const TOKENS = join(ROOT, 'client', 'apps', 'web', 'src', 'styles', 'tokens.css')

describe('设置页控件统一', () => {
  const src = readFileSync(SETTINGS, 'utf8')

  it('不使用原生 <select>（必须用 TileSelect）', () => {
    const hits = [...src.matchAll(/<select[\s>]/g)]
    expect(
      hits.length,
      `设置页有 ${hits.length} 处原生 <select>：它在深色主题下弹系统白底，` +
        `且高度/圆角与周围磁贴不一致。改用 TileSelect。`,
    ).toBe(0)
  })

  it('TileSelect 是设置页的主要下拉形态', () => {
    // 27 处使用；少于 20 说明有人改回了原生控件或删了大批设置项
    const n = (src.match(/<TileSelect/g) || []).length
    expect(n, `TileSelect 只用 ${n} 处，疑似退化`).toBeGreaterThanOrEqual(20)
  })

  it('搜索缓存的三档时长都在「搜索与源」区', () => {
    /*
     * 用户原话：「应该放到 浏览与搜索 区域内」。
     * 它原来在「数据与维护」—— 那是放"清除/重置"这类维护动作的地方，
     * 而缓存时长是**搜索行为的参数**，与搜索线程、搜索展示同类。
     *
     * 2026-10-30 再次重构：「浏览与搜索」整区并进了「搜索与源」，
     * 因为"某部剧搜不到"要在三个区之间跳。断言跟着改到 source 区。
     *
     * 模板里是 `v-for="f in cacheTtlFields"` 渲染三档，所以断言要检查
     * 那个循环变量，而不是逐个 key 名（踩过一次：直接找 searchCacheHitSec
     * 找不到，因为它只出现在 script 里的常量定义中）。
     */
    const i = src.indexOf('data-sec="source"')
    const j = src.indexOf('data-sec="play"')
    expect(i, '找不到搜索与源区').toBeGreaterThan(-1)
    expect(j, '找不到播放区（用作区段结束边界）').toBeGreaterThan(i)
    const block = src.slice(i, j)
    expect(block, '三档缓存磁贴不在搜索与源区').toContain('cacheTtlFields')
    expect(block, '缓存说明文案不在搜索与源区').toContain('searchCacheStats')
    // 反向：维护区不该再有它
    const mi = src.indexOf('data-sec="maint"')
    expect(mi).toBeGreaterThan(j)
    expect(src.slice(mi), '缓存时长还留在维护区').not.toContain('cacheTtlFields')
  })

  it('color-scheme 必须跟随 data-mode（否则 light 下原生控件仍是深色）', () => {
    /*
     * 原生控件（滚动条、select 弹层、数字框箭头）由 color-scheme 决定配色，
     * 不跟 CSS 变量。原来 `:root` 写死 dark，light 主题下点开下拉还是深色菜单。
     */
    const css = readFileSync(TOKENS, 'utf8')
    expect(css, '没有 light 的 color-scheme 覆盖').toMatch(
      /html\[data-mode=['"]light['"]\]\s*\{[^}]*color-scheme:\s*light/,
    )
  })
})

/**
 * 分区归属门禁（2026-10-30 第三轮）。
 *
 * 用户连续三轮在调整分区，每轮都是"某几个控件放错了地方"。这里把**已经
 * 达成一致的归属**锁成断言，避免下次重构又把它们挪回去 —— 挪动本身不报错，
 * 只有用户翻设置页才会发现，代价很高。
 *
 * 断言写法：取"该分区起点 → 下一个分区起点"的源码片段，检查关键词在其中。
 * 分区起点用 `data-sec="x"`，因为那是导航与滚动高亮共用的唯一标识。
 */
describe('设置页分区归属', () => {
  const src = readFileSync(SETTINGS, 'utf8')

  /** 取某个分区（data-sec）到下一个分区之间的源码。 */
  function blockOf(id: string): string {
    const i = src.indexOf(`data-sec="${id}"`)
    expect(i, `找不到分区 ${id}`).toBeGreaterThan(-1)
    const rest = src.slice(i + 10)
    const next = rest.search(/data-sec="/)
    return next < 0 ? rest : rest.slice(0, next)
  }

  it('搜索相关控件都在「搜索与源」区', () => {
    const b = blockOf('source')
    for (const k of ['cacheTtlFields', 'searchLayout', 'searchThreads', 'resetSourceHealth', 'clearSearchCacheNow', 'settings-sites']) {
      expect(b, `「搜索与源」区缺少 ${k}`).toContain(k)
    }
  })

  it('列数上限在「外观」区（列表密度属于外观）', () => {
    expect(blockOf('appearance'), '列数上限不在外观区').toContain('homeCols')
  })

  it('历史记录条数在「存储」区（这是留多久，不是怎么搜）', () => {
    expect(blockOf('storage'), '历史记录条数不在存储区').toContain('historyLimit')
    // 反向：不能又回到搜索区
    expect(blockOf('source'), '历史记录条数又跑回搜索区了').not.toContain('historyLimit')
  })

  it('本地弹幕在「弹幕」区（它属于弹幕，不属于离线缓存）', () => {
    expect(blockOf('danmaku'), '本地弹幕不在弹幕区').toContain('dmLocal')
    expect(blockOf('storage'), '本地弹幕又跑回存储区了').not.toContain('dmLocal')
  })

  it('DNS 在「网络与加速」区（网络层，不是源配置）', () => {
    const b = blockOf('net')
    expect(b, '安全DNS 不在网络与加速区').toContain('dnsChoice')
    expect(b, 'DNS 测试不在网络与加速区').toContain('probeDns')
    expect(blockOf('source'), 'DNS 又跑回搜索与源区了').not.toContain('dnsChoice')
  })

  it('WebDAV 有独立分区，不与存储混在一起', () => {
    const b = blockOf('sync')
    expect(b, 'WebDAV 分区里没有 dav').toContain('dav.')
    expect(blockOf('storage'), 'WebDAV 又混回存储区了').not.toContain('dav.url')
  })

  it('应用更新在「更新」区，且仍受 desktop/tvMode 门控', () => {
    const b = blockOf('update')
    expect(b, '应用更新不在更新区').toContain('updFound')
    expect(b, '应用更新的平台门控丢了（Web 端会看到灰按钮）').toMatch(
      /v-if="desktop \|\| tvMode"/,
    )
  })
})

/**
 * 2026-11-11 TV 截图定稿的三件事：
 *   1. 「重连后端」是即时动作，不该是带箭头的卡片；放到局域网/扫码下一行。
 *   2. 设置页说明文字过长，本机执行 / 运行时模块先压缩。
 *   3. 运行时模块的架构类型在版本号左边，且不换行。
 */
describe('设置页动作形态与文案（2026-11-11）', () => {
  const src = readFileSync(SETTINGS, 'utf8')

  it('重连后端是普通按钮，排在局域网发现/扫码下一行', () => {
    expect(src, '重连又变回磁贴了').not.toContain('const connectTiles')
    const lan = src.indexOf('局域网自动发现')
    const reconnect = src.indexOf('重连后端')
    expect(lan).toBeGreaterThan(-1)
    expect(reconnect, '重连按钮不在局域网/扫码之后').toBeGreaterThan(lan)
    const block = src.slice(reconnect - 400, reconnect + 200)
    expect(block, '重连不是普通按钮').toContain('bg-card')
    expect(block, '重连还带磁贴的跳转箭头').not.toContain('tile')
  })

  it('纯动作磁贴不带跳转箭头（箭头只留给会打开新页面的项）', () => {
    const at = src.indexOf("key: 'resetprefs'")
    expect(at, '找不到恢复默认设置磁贴').toBeGreaterThan(-1)
    expect(src.slice(at, at + 500), '恢复默认设置还带着跳转箭头').toContain('arrow: false')
    // 运行日志 / 关于是真的跳转，保留箭头
    expect(src.slice(src.indexOf("key: 'logs'"), src.indexOf("key: 'logs'") + 500))
      .not.toContain('arrow: false')
    expect(src.slice(src.indexOf("key: 'about'"), src.indexOf("key: 'about'") + 500))
      .not.toContain('arrow: false')
  })

  it('单动作磁贴全部改成普通按钮，不再占半张空磁贴', () => {
    expect(src, '还有单动作磁贴没改').not.toContain('const healthTiles')
    expect(src).not.toContain('const cacheTiles')
    expect(src).not.toContain('const storageTiles')
    expect(src).not.toContain('const desktopTiles')
    expect(src).toContain('@click="resetSourceHealth"')
    expect(src).toContain('@click="clearSearchCacheNow"')
    expect(src).toContain('@click="clearLocalCache"')
    expect(src).toContain('@click="resetTrayHint"')
  })

  it('本机执行/运行时模块文案已压缩，不再保留长解释', () => {
    expect(src).toContain('电视可脱离后端，直接运行配置里的 jar')
    expect(src).toContain('模块约 60MB，按 CPU 架构单独安装')
    expect(src, '旧的 60MB 解释又回来了').not.toContain('避免给所有人加 60MB')
    expect(src, '旧的纯 class 长解释又回来了').not.toContain('纯 .class 的 jar 安卓加载不了')
  })

  it('运行时模块状态：架构在版本左侧，且不换行', () => {
    expect(src).toContain('{{ modState.abi }} · 版本')
    const at = src.indexOf('{{ modState.abi }} · 版本')
    expect(src.slice(at - 120, at), '状态行没有禁止换行').toContain('whitespace-nowrap')
  })
})

describe('DNS 测试的动作形态', () => {
  it('DNS 测试是即时动作，不带跳转箭头', () => {
    const src = readFileSync(SETTINGS, 'utf8')
    const at = src.indexOf('DNS 测试')
    expect(at).toBeGreaterThan(-1)
    expect(src.slice(at, at + 900), 'DNS 测试又带上了跳转箭头').not.toContain('m9 18 6-6-6-6')
  })
})

describe('远程调试并入维护', () => {
  it('不再单独成区，内容落在维护区', () => {
    const src = readFileSync(SETTINGS, 'utf8')
    expect(src, '远程调试又单独成区了').not.toContain('data-sec="tvdebug"')
    expect(src, '导航里还留着远程调试').not.toContain("id: 'tvdebug'")
    const maintAt = src.indexOf('data-sec="maint"')
    expect(maintAt, '找不到维护区').toBeGreaterThan(-1)
    expect(src.slice(maintAt), '维护区里没有远程调试').toContain('远程调试')
    const debugAt = src.indexOf('远程调试', maintAt)
    const backupAt = src.indexOf('备份与诊断', maintAt)
    expect(debugAt, '远程调试不在备份与诊断上方').toBeLessThan(backupAt)
  })
})

describe('设置页字号层级与标签', () => {
  const src = readFileSync(SETTINGS, 'utf8')
  const TOKENS = join(ROOT, 'client', 'apps', 'web', 'src', 'styles', 'tokens.css')

  it('TV 上分组小标题不小于正文，避免“更新源”比分组标题还大', () => {
    const css = readFileSync(TOKENS, 'utf8')
    const at = css.indexOf("html[data-tv='1'] .set-sub")
    expect(at).toBeGreaterThan(-1)
    expect(css.slice(at, at + 120)).toContain('0.875rem')
  })

  it('更新源正文与标签和常规表单同一档', () => {
    expect(src).toContain('class="pb-3 text-[11px]"')
    expect(src).toContain('class="shrink-0 text-xs text-ink-3">更新源')
    expect(src).toContain('class="shrink-0 text-xs text-ink-3">备用源')
  })

  it('本机执行用统一 set-head，动作行不再被单列 set-grid 挤窄', () => {
    const at = src.indexOf('data-sec="localexec"')
    const end = src.indexOf('data-sec="runtime"', at)
    const block = src.slice(at, end)
    expect(block).toContain('class="set-head"')
    expect(block).not.toContain('class="set-grid mt-2"')
    // 两态：未启用只留动作 + 折叠配置；已启用才常显网关状态与配置项
    expect(block).toContain('v-if="!localGw"')
    expect(block).toContain('本机网关运行中')
    expect(block).toContain('@click="stopLocalExec()"')
    expect(block.indexOf('<details')).toBeLessThan(block.indexOf('导入到本机'))
  })

  it('搜索与源副标题不再提已经独立出去的直播源', () => {
    expect(src).toContain('站点源 · 站点管理 · 搜索怎么跑')
    expect(src).not.toContain('站点源 · 站点管理 · 直播源 · 搜索怎么跑')
  })

  it('WebDAV 四个字段有可见标签，不再只靠 placeholder', () => {
    const at = src.indexOf('data-sec="sync"')
    const end = src.indexOf('data-sec="ai"', at)
    const block = src.slice(at, end)
    for (const label of ['服务地址', '目录', '用户名', '密码']) {
      expect(block, `WebDAV 缺少「${label}」标签`).toContain(`>${label}<`)
    }
  })
})

describe('设置页字号刻度统一', () => {
  const src = readFileSync(SETTINGS, 'utf8')
  const TOKENS = join(ROOT, 'client', 'apps', 'web', 'src', 'styles', 'tokens.css')

  it('TV 设置页内 text-xs 也抬到 14px，避免次要按钮比主按钮小', () => {
    const css = readFileSync(TOKENS, 'utf8')
    expect(css).toContain("html[data-tv='1'] section[data-sec] [class~='text-xs']")
  })

  it('设置页不再使用 text-[12px]（TV 下会被抬到 15px）', () => {
    expect(src).not.toContain('text-[12px]')
  })

  it('禁用态透明度统一为 40%', () => {
    expect(src).not.toMatch(/disabled:opacity-(30|50)/)
  })
})

describe('窄磁贴/表单标签不被截断', () => {
  const src = readFileSync(SETTINGS, 'utf8')

  it('主题磁贴标签缩到「主题」，避免被色板挤成「主题…」', () => {
    expect(src).toContain('label="主题"')
    expect(src).not.toContain('label="主题风格"')
  })

  it('失败缓存磁贴标签不再超宽', () => {
    expect(src).toContain("label: '失败缓存'")
    expect(src).not.toContain("label: '失败结果缓存'")
  })

  it('连接区两个标签用 w-20，「后端地址 / 设备令牌」不换行', () => {
    expect(src).toContain('class="w-20 shrink-0 text-xs text-ink-3">后端地址')
    expect(src).toContain('class="w-20 shrink-0 text-xs text-ink-3">设备令牌')
  })
})

describe('设置页控件形态统一（2026-11-11）', () => {
  const src = readFileSync(SETTINGS, 'utf8')
  const SWITCH = join(ROOT, 'client', 'apps', 'web', 'src', 'components', 'common', 'ToggleSwitch.vue')
  const ACCEL = join(ROOT, 'client', 'apps', 'web', 'src', 'components', 'common', 'GitAccelPanel.vue')

  it('启用类开关统一用 ToggleSwitch（不再混用 checkbox）', () => {
    expect(src).toContain("import ToggleSwitch from '@/components/common/ToggleSwitch.vue'")
    const sw = readFileSync(SWITCH, 'utf8')
    expect(sw).toContain('role="switch"')
    expect(sw).toContain(":aria-checked=\"modelValue\"")
    // 四处：运行时模块 / AI / WebDAV / GitHub 加速
    expect(src).toContain('<ToggleSwitch')
    expect(readFileSync(ACCEL, 'utf8')).toContain('<ToggleSwitch')
    // 启用类开关不再用原生 checkbox
    expect(src).not.toContain(':checked="modState.enabled"')
    expect(src).not.toContain(':checked="llm.enabled"')
    expect(readFileSync(ACCEL, 'utf8')).not.toContain('v-model="accel.enabled" type="checkbox"')
  })

  it('分区标题行：左边标题+说明，右边状态/操作', () => {
    const css = readFileSync(join(ROOT, 'client', 'apps', 'web', 'src', 'styles', 'tokens.css'), 'utf8')
    // 说明不再 ml-auto（否则会被推到中间），只有 set-actions 靠右
    expect(css).toMatch(/\.set-head > \.set-actions \{\s*margin-left: auto;/)
    expect(css).not.toMatch(
      /\.set-head > \.set-hint,\s*\.set-head > \.set-actions \{\s*margin-left: auto;/,
    )
    // 状态类内容统一挂 set-actions
    expect(src).toContain('class="set-actions flex flex-wrap items-center gap-2"')
    expect(src).toContain('class="set-actions flex items-center text-[11px] text-ink-3"')
  })

  it('表单标签统一放左侧（不再一半在上一行）', () => {
    // WebDAV 四个字段
    const at = src.indexOf('data-sec="sync"')
    const block = src.slice(at, at + 4000)
    expect(block).toContain('w-20 shrink-0 text-xs text-ink-3">服务地址')
    expect(block).toContain('w-12 shrink-0 text-xs text-ink-3">目录')
    expect(block).toContain('w-14 shrink-0 text-xs text-ink-3">用户名')
    // 本机执行配置地址
    expect(src).toContain('w-20 shrink-0 text-xs text-ink-3">配置地址')
  })
})
