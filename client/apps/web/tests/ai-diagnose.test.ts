import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { diagnose, CAUSE_LABEL, type DiagSources } from '../src/ai/diagnose'
import { effectiveMaxTokens, isReasoningModel, SYSTEM_PROMPT, buildUserMessage } from '../src/ai/llm'

/**
 * 诊断规则的回归。
 *
 * 这一层的判据全部来自真实排障记录（docs/47 §2.3），所以每条用例都用
 * **真机上见过的错误串**，不要自己编。判据改了就说明规则错了——
 * 改之前先回头看那条记录。
 */

function s(patch: Partial<DiagSources>): DiagSources {
  return { netFail: [], errors: [], backendLogs: [], ...patch }
}

describe('诊断：源侧问题', () => {
  it('清单 200 但解析失败 → 占位内容（AppRJ 302 到小红书 PNG 那次）', () => {
    const d = diagnose(
      s({
        hlsError: 'manifestParsingError (200)',
        line: '线路1',
        lines: ['线路1', '线路2', '线路3'],
      }),
    )
    expect(d.cause?.code).toBe('SOURCE_PLACEHOLDER')
    expect(d.cause?.severity).toBe('blocking')
    // 建议必须是"换线路"，且把候选线路带出来（否则前端无从执行）
    expect(d.suggestion.action).toBe('switch_line')
    expect(d.suggestion.options).toEqual(['线路2', '线路3'])
  })

  it('清单 403 → 防盗链，建议换线', () => {
    const d = diagnose(
      s({ hlsError: 'manifestLoadError (403)', line: '线路1', lines: ['线路1', '线路2'] }),
    )
    expect(d.cause?.code).toBe('SOURCE_403_REFERER')
    expect(d.suggestion.action).toBe('switch_line')
  })

  it('分片 404 → 清单改写可疑，指向日志页', () => {
    const d = diagnose(s({ hlsError: 'fragLoadError (404)' }))
    expect(d.cause?.code).toBe('HLS_RELATIVE_404')
    expect(d.suggestion.action).toBe('open_logs')
  })

  it('清单请求超时', () => {
    const d = diagnose(s({ hlsError: 'manifestLoadTimeOut' }))
    expect(d.cause?.code).toBe('NETWORK_TIMEOUT')
  })

  it('没有候选线路时降级成换源（switch_line 无从执行）', () => {
    const d = diagnose(s({ hlsError: 'manifestLoadError (403)', lines: ['线路1'], line: '线路1' }))
    expect(d.suggestion.action).toBe('switch_source')
    expect(d.suggestion.options).toBeUndefined()
  })
})

describe('诊断：客户端侧问题', () => {
  it('编码不支持 → 转码，且必须二次确认（分钟级任务）', () => {
    const d = diagnose(s({ hlsError: 'bufferAddCodecError' }))
    expect(d.cause?.code).toBe('CODEC_UNSUPPORTED')
    expect(d.suggestion.action).toBe('transcode')
    expect(d.suggestion.confirm).toBe(true)
  })

  it('parse=1 → 需要第三方解析器（前端不读这个字段，所以像"地址坏了"）', () => {
    const d = diagnose(s({ parse: 1 }))
    expect(d.cause?.code).toBe('PARSE_REQUIRED')
    expect(d.suggestion.action).toBe('switch_source')
  })

  it('起播看门狗到期且 readyState<3 → 超时', () => {
    const d = diagnose(s({ watchdogFired: true, player: { readyState: 0 } }))
    expect(d.cause?.code).toBe('NETWORK_TIMEOUT')
  })

  it('WebView 样式探针失败', () => {
    const d = diagnose(s({ styleProbe: 'nostyle:block' }))
    expect(d.cause?.code).toBe('WEBVIEW_TOO_OLD')
  })
})

describe('诊断：只影响体验、不影响播放', () => {
  it('能播但弹幕 0 条 → info，建议刷新弹幕', () => {
    const d = diagnose(
      s({ player: { readyState: 4, danmaku: { count: 0 } } }),
    )
    expect(d.cause?.code).toBe('DANMAKU_ONLY')
    expect(d.cause?.severity).toBe('info')
    expect(d.suggestion.action).toBe('refresh_danmaku')
  })

  it('能播且有弹幕 → 不命中任何规则', () => {
    const d = diagnose(s({ player: { readyState: 4, danmaku: { count: 120 } } }))
    expect(d.cause).toBeNull()
    expect(d.suggestion.action).toBe('none')
  })

  it('后端日志有 blocked origin → CORS', () => {
    const d = diagnose(
      s({ backendLogs: [{ level: 'warn', tag: 'cors', text: 'blocked origin http://x' }] }),
    )
    expect(d.cause?.code).toBe('CORS_BLOCKED')
  })
})

describe('诊断：契约约束', () => {
  it('没命中时 cause 是 null，绝不编 UNKNOWN', () => {
    const d = diagnose(s({}))
    expect(d.ok).toBe(true)
    expect(d.cause).toBeNull()
    expect(d.suggestion.text).toContain('未发现')
  })

  it('多条命中时置信度降档（判据不唯一）', () => {
    // 403 + 超时同时命中两条，主因是 403（同为 blocking，规则表顺序在前）
    const d = diagnose(s({ hlsError: 'manifestLoadError (403)', parse: 1 }))
    expect(d.cause).not.toBeNull()
    expect(d.cause!.confidence).not.toBe('high')
  })

  it('explanation 恒为 null（人话由上层调 LLM 补，规则层不依赖模型）', () => {
    const d = diagnose(s({ hlsError: 'fragLoadError (403)' }))
    expect(d.explanation).toBeNull()
  })

  it('建议里的 action 必须落在契约枚举内', () => {
    const allowed = new Set([
      'switch_line', 'switch_source', 'retry', 'refresh_danmaku',
      'open_logs', 'transcode', 'disable_source', 'none',
    ])
    const cases: Array<Partial<DiagSources>> = [
      { hlsError: 'manifestParsingError (200)' },
      { hlsError: 'manifestLoadError (403)' },
      { hlsError: 'fragLoadError (404)' },
      { hlsError: 'bufferAddCodecError' },
      { hlsError: 'manifestLoadTimeOut' },
      { parse: 1 },
      { player: { readyState: 4, danmaku: { count: 0 } } },
      { styleProbe: 'nostyle:block' },
      {},
    ]
    for (const c of cases) {
      const d = diagnose(s(c))
      expect(allowed.has(d.suggestion.action), `${JSON.stringify(c)} → ${d.suggestion.action}`).toBe(true)
    }
  })

  it('证据 kind 只用契约里的 5 种', () => {
    const allowed = new Set(['hls', 'player', 'network', 'backend', 'style'])
    const d = diagnose(
      s({
        hlsError: 'fragLoadError (403)',
        player: { readyState: 0 },
        netFail: [{ method: 'GET', url: 'http://x/a.m3u8', status: 403, ok: false, ms: 10, error: null }],
        backendLogs: [{ level: 'warn', tag: 'stream', text: 'blocked ssrf' }],
        errors: [{ message: 'boom' }],
      }),
    )
    for (const e of d.evidence) {
      expect(allowed.has(e.kind), `未知 kind: ${e.kind}`).toBe(true)
    }
  })

  it('证据里的 URL 被裁剪，不把整条带 token 的地址塞进界面', () => {
    const longUrl = 'http://example.com/very/long/path/seg.m3u8?token=SECRET123456789'
    const d = diagnose(
      s({ netFail: [{ method: 'GET', url: longUrl, status: 403, ok: false, ms: 1, error: null }] }),
    )
    const net = d.evidence.find((e) => e.kind === 'network')
    expect(net).toBeTruthy()
    expect(net!.detail).not.toContain('SECRET123456789')
  })

  it('单条规则抛错不影响整体（match 里访问了不存在的字段）', () => {
    // player 为 null 时某些规则会读 player.xxx —— 必须被 try/catch 兜住
    const d = diagnose(s({ player: null as any, hlsError: null, watchdogFired: true }))
    expect(d.ok).toBe(true)
  })
})

describe('cause 标签表覆盖所有规则', () => {
  it('每个规则 code 都有中文短名（UI 要用）', () => {
    const codes = [
      'SOURCE_PLACEHOLDER', 'SOURCE_403_REFERER', 'HLS_RELATIVE_404',
      'CODEC_UNSUPPORTED', 'NETWORK_TIMEOUT', 'PARSE_REQUIRED',
      'SOURCE_EMPTY_LIST', 'DANMAKU_ONLY', 'CORS_BLOCKED', 'WEBVIEW_TOO_OLD',
    ]
    for (const c of codes) expect(CAUSE_LABEL[c], `缺标签: ${c}`).toBeTruthy()
  })
})

describe('推理模型的 token 预算（实测踩过）', () => {
  it('识别常见推理模型', () => {
    // deepseek-r1:14b 实测：max_tokens=300/900 时全部预算烧在思维链上，
    // finish_reason=length 且 content 为空 —— 用户看到的是"AI 没反应"
    for (const m of [
      'deepseek-r1:14b',
      'deepseek-r1',
      'deepseek-reasoner',
      'qwq:32b',
      'o1-mini',
      'o3',
      'qwen3:8b-thinking',
    ]) {
      expect(isReasoningModel(m), `${m} 应判为推理模型`).toBe(true)
    }
  })

  it('普通模型不受影响（不要把 chat 也当成推理模型）', () => {
    for (const m of [
      'deepseek-chat',
      'gpt-4o-mini',
      'qwen2.5:7b',
      'claude-3-5-sonnet',
      'glm-4',
      'Qwen/Qwen2.5-14B-Instruct',
    ]) {
      expect(isReasoningModel(m), `${m} 不该判为推理模型`).toBe(false)
    }
  })

  it('推理模型的预算被抬到 3000（实测 900 仍不够）', () => {
    const base = { enabled: true, baseUrl: 'x', apiKey: 'k', model: 'deepseek-r1:14b', timeoutMs: 8000, maxTokens: 300 }
    expect(effectiveMaxTokens(base)).toBe(3000)
  })

  it('用户配了更大的预算就尊重用户', () => {
    const cfg = { enabled: true, baseUrl: 'x', apiKey: 'k', model: 'deepseek-r1:14b', timeoutMs: 8000, maxTokens: 5000 }
    expect(effectiveMaxTokens(cfg)).toBe(5000)
  })

  it('普通模型用用户配的预算', () => {
    const cfg = { enabled: true, baseUrl: 'x', apiKey: 'k', model: 'deepseek-chat', timeoutMs: 8000, maxTokens: 300 }
    expect(effectiveMaxTokens(cfg)).toBe(300)
  })
})

describe('系统提示词约束', () => {
  it('带领域知识，且明确禁止列点与推荐外部工具', () => {
    // 实测：不加这几条，deepseek-r1 会输出 Markdown 列表 + 推荐 HandBrake
    expect(SYSTEM_PROMPT).toContain('manifestParsingError')
    expect(SYSTEM_PROMPT).toContain('不要列点')
    expect(SYSTEM_PROMPT).toContain('不要推荐外部工具')
    // 反例段能显著压低跑偏率
    expect(SYSTEM_PROMPT).toContain('反例')
  })

  it('用户消息只给结构化事实，不预设结论语气', () => {
    const d = diagnose({ hlsError: 'fragLoadError (403)', netFail: [], errors: [], backendLogs: [] })
    const msg = buildUserMessage(d)
    expect(msg).toContain('规则层判定')
    expect(msg).toContain('证据')
  })
})

describe('AI 面板契约（docs/47 §三）', () => {
  const PANEL = readFileSync(join(__dirname, '..', 'src', 'components', 'ai', 'AiPanel.vue'), 'utf8')
  const APP = readFileSync(join(__dirname, '..', 'src', 'App.vue'), 'utf8')
  const AI_VIEW = readFileSync(join(__dirname, '..', 'src', 'views', 'AiView.vue'), 'utf8')

  it('z-index 是 60（高于顶栏 30 与播放面板 40，低于伪全屏 100）', () => {
    expect(PANEL).toContain('z-[60]')
    // 只看 class 属性里的实际用法；注释里提到 z-50 是说明为什么不用它
    const classAttrs = [...PANEL.matchAll(/class="([^"]*)"/g)].map((m) => m[1]).join(' ')
    expect(classAttrs, 'FAB 不能用 z-50，会盖住播放器的 z-40 面板').not.toMatch(/z-50/)
  })

  it('播放页隐藏 FAB（那里已有四块浮层在抢位）', () => {
    expect(PANEL).toMatch(/route\.name === 'play'/)
    expect(PANEL).toMatch(/visible = computed/)
  })

  it('伪全屏时隐藏（直播伪全屏 z-50 低于 FAB 的 z-60）', () => {
    expect(PANEL).toContain('hidden')
    // /ai 独立页也要藏浮层：那一页自己渲染了 inline 模式的同一组件，
    // 不藏会出现"浮层 + 页面主体"两份对话
    expect(APP).toMatch(/<AiPanel :hidden="inFullscreen \|\| route\.name === 'ai' \|\| isLogin"/)
    expect(APP).toContain("meow:pseudofs")
  })

  it('独立页走 inline 模式，与浮层共用同一份 DOM 与逻辑', () => {
    // 两条约束：① 独立页不出 FAB、不带 fixed；② 消息列表/表单只有一份实现
    expect(PANEL).toMatch(/inline\?: boolean/)
    expect(AI_VIEW).toMatch(/<AiPanel inline \/>/)
    // 只应有一处消息循环与一处提交表单
    expect((PANEL.match(/v-for="m in msgs"/g) || []).length).toBe(1)
    expect((PANEL.match(/@submit\.prevent="submit"/g) || []).length).toBe(1)
  })

  it('Ctrl/Cmd+K 打开，不用单键（播放器已占 f/d/Esc）', () => {
    expect(PANEL).toMatch(/e\.ctrlKey \|\| e\.metaKey/)
    expect(PANEL).toMatch(/=== 'k'/)
  })

  it('「播放」是一次真实点击，不是自动跳转（全屏与自动播放都要用户激活）', () => {
    // 必须有 @click 的播放按钮
    expect(PANEL).toMatch(/@click="h\.sources/)
    // 不允许在搜索结果回调里自动 router.push
    const submit = PANEL.slice(PANEL.indexOf('async function submit'), PANEL.indexOf('function play('))
    expect(submit).not.toContain('router.push')
  })

  it('第三方字符串不用 v-html（片名/源名来自站点，防注入）', () => {
    // 模板里不能出现 v-html 指令（注释里说明"禁止 v-html"是允许的）
    expect(PANEL).not.toMatch(/\sv-html=/)
  })

  it('有过程提示（搜索跨多源要几秒，否则像点了没反应）', () => {
    expect(PANEL).toContain('step')
    expect(PANEL).toMatch(/正在搜索各源/)
  })

  it('输入长度上限 100 字', () => {
    expect(PANEL).toMatch(/maxlength="100"/)
    expect(PANEL).toMatch(/slice\(0, 100\)/)
  })
})
