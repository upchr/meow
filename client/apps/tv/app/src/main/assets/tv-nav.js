/**
 * 电视遥控导航：把方向键/确认键翻译成"焦点移动 + 点击"。
 *
 * WebView 本身只认 Tab 键做焦点移动，遥控器上下左右不会动——不注入这段，
 * 电视上就只有"能看见但选不中"。用几何最近邻而不是 DOM 顺序：
 * 影视列表是网格，按 DOM 顺序左右走会在换行时跳错。
 *
 * 与 Web 端零耦合：不依赖任何 class/id，只按"可聚焦元素"的通用规则收集。
 */
(function () {
  /*
   * ★ 重复注入时只补样式，不能整段 return。
   *
   * 踩过的坑：靠 `if (window.__meowTvNav) return` 直接退出。而
   * `applyApiBaseIfNeeded()` 会让 WebView reload 一次 —— 新文档里
   * <style> 全部被清空，但 window.__meowTvNav 仍留在 JS 上下文里。
   * 于是第二次注入被守卫挡住，样式再也装不回来：焦点类照常加、
   * getComputedStyle 读到浏览器默认的 outline:none，屏幕上就是
   * "按方向键毫无反应、不知道选到哪里"。实测电视端正是这个症状。
   *
   * 所以守卫改成"函数已存在就只确保样式在，然后退出"。
   */
  /*
   * 焦点框样式。两处注入（重复注入时的兜底 + 首次安装）共用这一段，
   * 避免两份定义漂移。
   *
   * ★ 弹层内改用"内阴影 + inset outline"：
   *   普通规则是 outline 3px + offset 2px + box-shadow spread 6px，合计
   *   11px 的外扩。弹层面板是 overflow:hidden（为了裁圆角），贴边的按钮
   *   （实测「重新扫描」在右边缘）那一圈会被切掉一半，看起来像"框被遮挡"。
   *   内阴影画在元素内部，不越过父级裁切区，弹层里用它是唯一稳的方案。
   */
  /**
   * 焦点框样式。
   *
   * ★ 不写 border-radius。踩过（实测）：原来写死 `border-radius:6px`，
   *   同一个分类胶囊**未聚焦时是 3.35544e+07px（无穷大→全圆）、聚焦变 6px**，
   *   药丸形瞬间被改成圆角矩形。而这条没加 !important 也生效 ——
   *   因为该 <style> 是运行时 append 到 head 末尾、且在任何 @layer 之外。
   *
   * ★ 用双层 box-shadow 而不是 outline：box-shadow 会自动跟随元素自身圆角，
   *   不用知道它到底是 rounded-full 还是 rounded-md。
   *   但两者都**会被 overflow 容器裁**，所以贴边的滚动容器还要补 padding
   *   （见 HomeView 分类行的 py-3 -my-3）。
   *
   * ★ 弹层内（[data-tv-scope]）改 inset：弹框里的按钮往往贴着边，
   *   外扩的环会被弹框的 overflow 裁掉。这就是为什么弹层里反倒从来没有
   *   "焦点框上下缺失"的症状。
   */
  var FOCUS_CSS =
    /*
     * ★ 双色环（2026-11-04 用户要求：「检查还有哪里可能聚焦样式看不出来，
     *   缺失双色环」）。
     *
     * 原来是纯 `0 0 0 3px accent` 画在元素外面：聚焦**实心 accent 按钮**
     * （搜索、选中态胶囊、播放键）时，环和按钮同色连成一片，只剩外面那圈
     * 28% 的 halo —— 深色背景上就是"按钮看起来大了 3px"，几乎没有焦点提示。
     *
     * 现在：最里面 2px 白 → 3px accent → 9px halo。
     *   深色元素上：白线 + 青环，边界清楚；
     *   实心 accent 元素上：白线压在同色边上，一眼能看出来。
     */
    /*
     * ★ 2026-11-04 二次调整：纯白 2px 硬环太扎眼。
     *
     * 现在的形态：
     *   1. 元素内沿 1px 半透明白（细高光，给实心 accent 按钮压出一条边界）；
     *   2. 外面 2px 实心 accent（主题色，随换肤变）；
     *   3. 再外面 12px 的 accent 辉光 —— box-shadow 的 blur 本身就是渐变，
     *      比原来 28% 的硬边 halo 柔和，也不会因为主题不是青色而串色
     *      （原来 `--tv-focus-halo` 兜底写死成青色 rgba(3,218,197,.28)）。
     */
    '.meow-tv-focus{' +
    'box-shadow:inset 0 0 0 1px var(--tv-focus-inner,rgba(255,255,255,.65)),' +
    ' 0 0 0 2px var(--color-accent,#03dac5),' +
    ' 0 0 12px 2px var(--color-accent,#03dac5) !important;' +
    'outline:none !important;transition:none !important}' +
    /*
     * ★ 弹层里的焦点环必须双色（2026-11-04 用户报「勾选的胶囊和搜索按钮
     *   看不出来焦点在哪，是按钮颜色和聚焦一样融合了吗」）。
     *
     * 原因：原来内环是 `inset 3px accent`，而弹层里最常被聚焦的恰恰是
     * **实心 accent 按钮**（搜索、选中的胶囊）—— 同色叠同色 = 1:1 不可见。
     * 现在外面再加一圈 accent、里面留 2px 白：
     *   深色按钮上 → 白 + accent 双层，清楚；
     *   accent 按钮上 → 白内环压在同色底上，也清楚。
     */
    '[data-tv-scope] .meow-tv-focus{' +
    'box-shadow:inset 0 0 0 1px var(--tv-focus-inner,rgba(255,255,255,.75)),' +
    ' inset 0 0 0 3px var(--color-accent,#03dac5),' +
    ' 0 0 10px 1px var(--color-accent,#03dac5) !important}' +
    /*
     * 密集网格（国家列表这类"格子在自己 overflow 容器里、四边都贴边"的
     * 布局）用**内描边**焦点环。
     *
     * ★ 2026-11-11 用户报「聚焦圆环被切割显示不全」：国家网格的容器是
     *   `max-h-56 overflow-y-auto`，外扩环（2px + 12px 辉光）在上下左右
     *   都会被容器裁掉；滚动到贴边那一行时尤其明显。
     *   这和弹层里的症状是同一个根因，解法也一样：画在元素内部就不会
     *   越过任何裁切区。
     *
     *   2px accent + 1px 内白（不用 3px：网格行高只有 29px，3px 内环会把
     *   国旗和文字挤成一条缝）+ 8px 外辉光（辉光允许被裁，破损不明显）。
     */
    '[data-tv-inset-focus] .meow-tv-focus{' +
    'box-shadow:inset 0 0 0 1px var(--tv-focus-inner,rgba(255,255,255,.75)),' +
    ' inset 0 0 0 2px var(--color-accent,#03dac5),' +
    ' 0 0 8px 0 var(--color-accent,#03dac5) !important}' +
    /*
     * 滚动停靠点（data-tv-scroll）的焦点框要更轻。
     *
     * 踩过（2026-10-30 统计面板截图）：给内容容器加 data-tv-first 后，
     * 焦点落在一个 700×490 的大容器上 —— 整块内容被 3px accent 框圈住，
     * 看起来像"选中了一大片"，而不是"这里可以滚动"。
     * 改成只画左侧竖条：既表明"当前焦点在这块可滚区域"，
     * 又不会盖住图表本身的视觉。
     */
    '[data-tv-scroll].meow-tv-focus{' +
    'box-shadow:inset 3px 0 0 0 var(--color-accent,#03dac5) !important}'

  /*
   * ★ 重复注入必须"重新接管"，不能只补样式就 return。
   *
   * 踩过（2026-10-30，排查了很久）：原来的守卫是
   * `if (window.__meowTvNav) { 补样式; return }`。
   * 但 WebView reload 之后（`applyApiBaseIfNeeded` 会触发一次 reload），
   * **`window` 上的函数还在、DOM 和 `document` 已经换了新的**。于是：
   *
   *   · `__meowTvNav` 来自旧闭包 —— 按键时它照样跑，看起来"导航能用"；
   *   · 旧闭包的 `MutationObserver` 绑在**旧 documentElement** 上，
   *     收不到新文档的任何变化 → `activeScope()` 永远不被调用；
   *   · 结果：弹层打开不聚焦当前值、关闭不还焦点、初始落点不生效，
   *     而手动调一次 `__meowTvNav` 就全好了 —— 这是最容易误判的一类症状。
   *
   * 所以这里把"旧闭包遗留的东西"清干净再重建：disconnect 旧 observer、
   * 删掉旧的 window 钩子（下面会重新装）。同一个文档内重复注入时
   * `disconnect` 是幂等的，无害。
   */
  try {
    if (window.__meowTvDispose) window.__meowTvDispose()
  } catch (e) {}

    /*
   * data-tv-skip：页面明确声明"这一块不参与遥控器移焦"。
   * 首页续播卡里海报和片名都是点击区，但遥控器只需要停在
   * 「继续播放 / 详情」两个按钮上 —— 否则 ↑↓ 在卡片里空转（2026-11-18）。
   */
  var FOCUSABLE = 'a[href]:not([data-tv-skip]),button:not([data-tv-skip]),input:not([data-tv-skip]),select:not([data-tv-skip]),textarea:not([data-tv-skip]),[tabindex]:not([tabindex="-1"]):not([data-tv-skip])'
  var current = -1
  /**
   * 最近一次真正加过焦点框的元素。
   *
   * <p>用途只有一个：框架把 class 抹掉后还能找回来（见 currentFocus）。
   */
  var lastFocused = null
  /**
   * 上下键“最近行”的容差（px）。
   *
   * 先取主轴距离最小的候选，只保留 `primary <= minPrimary + TOL` 的，
   * 再在保留集合里用次轴选列。原来直接比 `primary + secondary*2`：
   * 设置页一行右侧开关的横向惩罚能到 1000+，会输给两行/五行下的左对齐
   * 元素，表现就是“上/下键跳行”。
   *
   * 取 20 而不是 24：实测最小行距是详情页集数（40 高 + 8 间距，主轴约 56），
   * 设置页行距 52~77；20px 足够吸收同一视觉行内 1~2px 的错位/取整，
   * 又远小于最小行距，不会把相邻两行合并。
   */
  var VERTICAL_ROW_TOL = 20
  /**
   * 最后一次按键的时间戳。MutationObserver 用它做宽限期判断：
   * 用户正在按键时不纠正焦点（见 observer 里的说明）。
   */
  var lastKeyAt = 0

  // 用户是否亲手动过焦点。只有他按过方向键，才不再自动纠正初始位置。
  // 不能用 current 判断：MutationObserver 的兜底也会设置 current，
  // 于是 initialFocus 看到 current>=0 就永远跳过内容区优先的逻辑。
  var userMoved = false
  /**
   * 页面显式钉住的落点截止时刻（毫秒时间戳）。
   *
   * 见 `__meowTvFocus(target, true)` 与 `initialFocus()` 的注释：
   * `onRouteChange()` 会把 `userMoved` 清零，单靠它挡不住随后那次
   * `initialFocus()`，必须再有一个不随路由重置的窗口。
   */
  var pinUntil = 0
  /**
   * 本闭包是否已废弃（被后来的注入取代）。
   *
   * 踩过（2026-10-30，抓栈逐层定位）：只 `observer.disconnect()` 是不够的 ——
   * 旧闭包里已经排好的 `setTimeout(initialFocus, 120/700)` 和
   * observer 的 300ms 防抖**仍会执行**，而它们读的是**旧闭包里的
   * `userMoved` / `pinUntil`**（永远停在旧值）。
   * 症状正是用户报的"一瞬从添加片库 聚焦到 本地片库"：
   * 新闭包的 restore 刚落焦，旧闭包的 initialFocus 立刻把它改掉，
   * 而且新闭包的 `pinUntil` 守卫完全看不到。
   * 所有异步回调入口都要先判这个标记。
   */
  var disposed = false

  function visible(el) {
    if (!el || !el.getBoundingClientRect) return false
    var r = el.getBoundingClientRect()
    if (r.width < 2 || r.height < 2) return false
    var st = getComputedStyle(el)
    return st.visibility !== 'hidden' && st.display !== 'none' && st.opacity !== '0'
  }

  /**
   * 取「真正画着焦点框、且真的看得见」的那个元素。
   *
   * ★ 为什么不能直接用 `document.querySelector('.meow-tv-focus')`
   *   （2026-11-02，用户第四次报「TV 偶尔按向下键唤不出控制栏」）：
   *
   *   播放器的控制栏是 5s 自动收起的，收起后 `.meow-tv-focus` 这个 class
   *   可能还留在底栏的某个按钮上（页面自己的清理有 60ms 延迟，
   *   MutationObserver 还有 1.2s 宽限期）。而下面 `__meowTvNav` 的分派
   *   是靠"有没有这个 class"决定**要不要把方向键转发给播放器**的 ——
   *   只要它还在，方向键就被壳吃去移焦点，播放器永远收不到 ArrowDown，
   *   于是控制栏再也唤不出来。
   *
   *   加上可见性判据后：残留的框视为"没有焦点"，按键照常转发给播放器，
   *   播放器的 ArrowDown 分支就能把控制栏重新唤出来。
   */
  function markedEl() {
    var list = document.querySelectorAll('.meow-tv-focus')
    for (var i = 0; i < list.length; i++) {
      if (visible(list[i])) return list[i]
    }
    return null
  }

  /**
   * 元素是否还在视口内（与 visible 分开，用途不同）。
   *
   * ★ 为什么不能把它并进 visible()：items() 候选集靠 visible() 过滤，
   *   一旦加上视口判断，**列表下方还没滚到的元素会被排除**，
   *   方向键就再也下不去（下键在最后一行卡住）。这是最严重的一类回退。
   *
   * 它只用于「已有的焦点框是否还值得保留」这一件事：元素滚出视口后
   * 那道框要么飘在屏幕外、要么还留着光晕，用户会看到"不该存在的框"，
   * 而且按方向键会从屏幕外的坐标算几何，行为诡异（用户反馈：
   * "经常有非方向键焦点框驻留显示，影响判断"）。
   */
  function inViewport(el) {
    if (!el || !el.getBoundingClientRect) return false
    var r = el.getBoundingClientRect()
    if (r.width < 2 || r.height < 2) return false
    var vh = window.innerHeight || 0
    var vw = window.innerWidth || 0
    // 判「有交集」而不是「完全包含」：长列表的整行按钮常高于视口一半
    return !(r.bottom < 0 || r.top > vh || r.right < 0 || r.left > vw)
  }

  /*
   * ★ 弹层出现时必须把焦点范围收窄到弹层内。
   *
   * 踩过的坑：原来 items() 一律扫全文档。弹层是 fixed 居中的，背景里那些
   * 按钮（顶栏、左导航、分区里的操作按钮）照样在候选集里，而且它们几何
   * 上分布在弹层四周——按方向键的第一下往往往背景跑，用户感觉
   * "弹框里的东西选不中"（实测截图：焦点停在背景的「保存」上）。
   *
   * 判据用 `[data-tv-scope]`（页面显式声明"现在是模态"），而不是猜
   * z-index / position:fixed：几何与层级的启发式在真实页面里很容易误判
   * （播放器的全屏层也是 fixed）。页面自己最清楚谁该独占焦点。
   */
  /** 上一次见到的顶层 scope，用于识别"新打开了一个弹层"。 */
  var lastScopeEl = null
  /** 打开弹层前焦点所在的元素；弹层关闭时把焦点还给它。 */
  var scopeReturnEl = null
  /**
   * 按下 OK 的那个元素 —— 弹层打开时优先用它作为"原位置"。
   *
   * 见 enter() 里 enterOpener 的赋值注释：弹层出现后 `.meow-tv-focus`
   * 往往已经被搬进弹层，事后取样必然失败，只能提前记。
   */
  var enterOpener = null
  /** 弹层空闲关闭的定时器。0 表示没在计时。 */
  var scopeIdleTimer = 0
  /**
   * 弹层空闲多久自动关闭（毫秒）。
   *
   * 用户要求："聚焦从弹框内脱离后（除输入弹框输入情况下），应定时关闭"。
   *
   * 实现取"空闲"而不是"焦点真的跑到弹框外"——因为方向键在弹框打开时
   * 根本出不去（find() 只在 scope 内搜），"脱离"这个事件不会发生。
   * 真正会发生的场景是：用户打开弹框后走开了 / 忘了关，弹框一直挂在屏幕上
   * 遮住内容，而遥控器只能在弹框里那几项之间转圈 —— 这是实际痛点。
   *
   * 20 秒的依据：够读完一个弹框的选项、够做一次选择；短于此会误关，
   * 长于此用户已经觉得"卡住了"。
   *
   * ★ 不要写数字分隔符（`20_000`）。本文件注入到电视 WebView 执行，
   * 而极米投影仪是 **Chrome 66**，numeric separator 是 ES2021
   * （Chrome 75+）才有的语法 —— 写 `20_000` 会让**整个文件**
   * 抛 `SyntaxError: Invalid or unexpected token`，于是 tv-nav.js
   * 注入后一行都没执行：`__meowTvNav` 不存在，方向键全部失效，
   * 而菜单键/返回键走的是另一条路径（Vue 里的 __meowTvMenu）仍然正常。
   * 实测症状正是"首页只有菜单、返回键有效"。
   *
   * 同理：不要用 `?.`、`??`、`Object.fromEntries`、`Array.at()`，
   * 这些都是 Chrome 66 之后才有的。
   */
  var SCOPE_IDLE_CLOSE_MS = 20000

  function activeScope() {
    var scopes = document.querySelectorAll('[data-tv-scope]')
    if (!scopes.length) {
      /*
       * ★ 弹层关闭 → 焦点还给"打开它的那个元素"。
       *
       * 用户要求原话：「弹框、下拉框等框打开时，返回键优先作用于关闭弹框，
       * 同时聚焦到原位置」。关闭本身由 App.vue 的 onTvBack 派发 Escape
       * 完成（组件自己监听 Escape），这里只负责把焦点送回去。
       *
       * 为什么要记住而不是"关闭后重新算第一个"：站点下拉的触发按钮在顶栏、
       * 弹框在下方，几何上两者毫无关系；重新算会落到内容区某张卡片上，
       * 用户视角是"关掉下拉，焦点跑到海报墙去了"。
       */
      if (lastScopeEl) {
        lastScopeEl = null
        /*
         * 复用同一个归还入口。
         *
         * 这里原来自己实现了一遍（同步 highlight + 清 scopeReturnEl），
         * 与 `__meowTvScopeClosed` 形成两条并发路径：组件关闭时两条都会
         * 触发（一条来自 watch，一条来自 MutationObserver），先跑的那条
         * 把 scopeReturnEl 清掉，后跑的就还不了焦点。现在统一委托。
         */
        window.__meowTvScopeClosed()
      }
      return null
    }
    // 多个同时开着时取最后一个（后挂载的盖在上面）
    var top = scopes[scopes.length - 1]
    autoScrollAnchor(top)
    /*
     * ★ 弹层刚出现 → 主动把焦点搬进去。
     *
     * 踩过（实测）：弹框打开后焦点框还停在背景元素上，用户要按好几下方向键
     * 才"摸"进弹框；而背景是整页内容时，几何竞争常常轮不到弹框里的按钮。
     * TV 上没有任何指针，焦点不在弹框里 = 用户以为弹框是死的。
     *
     * 判据用"顶层 scope 元素变了"，而不是"有 scope 就抢"——
     * 后者会让每次 MutationObserver 回调都把焦点拽回第一项，
     * 用户刚移到第三项就被弹回第一项（这是最典型的"遥控器不听话"）。
     */
    if (top !== lastScopeEl) {
      /*
       * 记下"是谁打开的"。三级来源，够用就停：
       *   1. `enterOpener` —— 刚按 OK 的那个元素（最可靠，见 enter() 注释）
       *   2. `.meow-tv-focus` —— 焦点框还在外面时
       *   3. `document.activeElement` —— 上面两个都没有时兜一层
       *
       * 都要求"不在新弹层内"：焦点已经在里面说明这不是"刚打开"，
       * 而是弹层内部替换（菜单重渲染、列表刷新），那时没有"原位置"可言。
       */
      var opener = enterOpener || document.querySelector('.meow-tv-focus')
      enterOpener = null
      if (opener && !top.contains(opener)) {
        scopeReturnEl = opener
      } else if (!opener) {
        var ae = document.activeElement
        if (ae && ae !== document.body && !top.contains(ae)) scopeReturnEl = ae
      }
      lastScopeEl = top
      focusIntoScope(top)
    }
    armScopeIdle(top)
    return top
  }

  /**
   * 弹层空闲计时：到点就派发 Escape 让组件自己关。
   *
   * <p><b>为什么用 Escape 而不是直接改组件状态</b>：tv-nav 是壳注入的独立脚本，
   * 不该知道每个弹框组件内部的 `open` / `visible` 变量叫什么。而
   * CenterDialog / TileSelect / AiPanel / UpdateDialog 都已经监听
   * keydown 的 Escape（这是它们本来就有的"浏览器形态关闭方式"）。
   *
   * <p><b>为什么输入态不关闭</b>：用户明确要求"除输入弹框输入情况下"。
   * 判据是 `document.activeElement` 是 input/textarea，或弹层内含
   * 正在编辑的原生输入框 —— 边打字边被关掉是最难受的体验。
   */
  function armScopeIdle(scope) {
    if (!scope) {
      if (scopeIdleTimer) {
        clearTimeout(scopeIdleTimer)
        scopeIdleTimer = 0
      }
      return
    }
    /*
     * ★ 常驻型面板不参与空闲自动关闭。
     *
     * 踩过（2026-11-20 用户反馈「账号与安全弹框里方向键支持受限」）：账号弹框
     * 里有设备清单、配对码这类要停下来看的内容；盯着屏幕超过 20 秒，弹框就被
     * 这里的定时器派发 Escape 收掉 —— 他按下方向键时弹框刚好消失，看起来像
     * "方向键把弹框按没了"。设置面板的语义是"看完自己按返回"，不是"临时弹一下"。
     *
     * 约定：组件在 `[data-tv-scope]` 同一元素上标 `data-tv-idle-keep` 即豁免。
     */
    if (scope.getAttribute('data-tv-idle-keep') !== null) {
      if (scopeIdleTimer) {
        clearTimeout(scopeIdleTimer)
        scopeIdleTimer = 0
      }
      return
    }
    if (scopeIdleTimer) clearTimeout(scopeIdleTimer)
    scopeIdleTimer = setTimeout(function () {
      scopeIdleTimer = 0
      // 弹层可能已经自己关了
      var cur = document.querySelectorAll('[data-tv-scope]')
      if (!cur.length) return
      var topNow = cur[cur.length - 1]
      if (topNow !== scope) return
      if (isEditingIn(topNow)) {
        // 正在输入 → 再给一个周期，不直接关
        armScopeIdle(topNow)
        return
      }
      try {
        document.dispatchEvent(
          new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }),
        )
      } catch (e) {}
    }, SCOPE_IDLE_CLOSE_MS)
  }

  /** 弹层里有没有正在编辑的输入。 */
  function isEditingIn(scope) {
    var ae = document.activeElement
    if (ae && ae.tagName) {
      var t = ae.tagName.toLowerCase()
      if (t === 'input' || t === 'textarea') return true
    }
    // 有些原生输入框不落在 document.activeElement 上，按"文档里存在
    // 正在被编辑的输入"再兜一层
    var ins = scope.querySelectorAll('input, textarea')
    for (var i = 0; i < ins.length; i++) {
      if (ins[i] === document.activeElement) return true
    }
    return false
  }

  /**
   * 把焦点放到弹层内第一个可用元素。
   *
   * 优先级：`[data-tv-first]` > 当前值 > 第一个 input/textarea > 第一个可聚焦元素。
   *
   * <p><b>为什么"当前值"排在输入框之前</b>：用户要求原话「弹框、下拉框打开时，
   * 聚焦到框内第一个元素或当前对应值（目前首页源下拉，点击后没有聚焦到当前源）」。
   * 下拉框是"改选"语义，用户按 OK 打开就是想换一个——先落到当前值上，
   * 上下键即可从原位开始找邻近的，这是电视上最省按键的落点。
   * 用 `aria-selected` / `aria-checked` / `.meow-tv-current` 三种标记，
   * 都取不到才退回输入框。
   *
   * <p><b>为什么不改 `userMoved`</b>：这不是"用户动了"，只是上下文切换。
   */
  function focusIntoScope(scope) {
    var marked = currentFocus()
    // 已经在弹层里就别动（比如弹层内容刷新触发了 observer）
    if (marked && scope.contains(marked)) return
    var candidates = Array.prototype.slice
      .call(scope.querySelectorAll(FOCUSABLE))
      .filter(visible)
      .filter(usable)
    if (!candidates.length) return
    var first = scope.querySelector('[data-tv-first]')
    if (first && candidates.indexOf(first) < 0) first = null
    /*
     * 当前值：先看 aria 语义，再看我们自己的 `data-tv-current`。
     *
     * 只认 candidates 里的人 —— 一个 `aria-selected="true"` 的 li 若本身
     * 不可聚焦（下拉里常见：li 有语义、div 才带 tabindex），选它等于把
     * 焦点画到一个用户按不出反应的节点上。
     */
    var cur = null
    if (!first) {
      var declared = scope.querySelector(
        '[aria-selected="true"], [aria-checked="true"], [data-tv-current]',
      )
      if (declared && candidates.indexOf(declared) >= 0) cur = declared
    }
    var input = null
    if (!first && !cur) {
      for (var i = 0; i < candidates.length; i++) {
        var t = candidates[i].tagName.toLowerCase()
        if (t === 'input' || t === 'textarea') { input = candidates[i]; break }
      }
    }
    highlight(first || cur || input || candidates[0])
  }

  /**
   * 给弹层里「内容溢出但没有可聚焦元素」的滚动容器自动补一个停靠点。
   *
   * ★ 为什么必须自动补，而不是要求每个弹层手写 data-tv-scroll。
   *
   * 用户反馈："弹框显示不全，方向键无法滚到控制显示弹框内容（如设置页的
   * 局域网扫描弹框，开启二维码后弹框撑开后显示不全被遮挡）**可能是弹框通病**"
   * —— 确实通病。全仓 8 处 overflow-y-auto 里只有 1 处标了停靠点。
   *
   * 机理：items() 只收 FOCUSABLE（a/button/input/[tabindex]）。一个纯文字 +
   * 二维码的滚动容器里一个可聚焦元素都没有，于是它进不了候选集，
   * 方向键只能在弹层外层的两三个按钮间跳，内容一动不动。
   *
   * 判据（三条同时满足才补，避免污染正常结构）：
   *   1. scrollHeight 明显大于 clientHeight（真的溢出）
   *   2. 容器内没有已标 data-tv-scroll 的后代
   *   3. 容器内没有可聚焦的后代 —— 有的话用户能靠焦点移动滚到内容，
   *      再插一个停靠点反而会打断正常的按钮遍历
   *
   * 补法是运行时的（加属性 + tabindex），不改任何 .vue：
   * 弹层是动态挂载的，靠 DOM 变动触发 activeScope 时就地修正最省。
   */
  function autoScrollAnchor(scope) {
    var all = scope.querySelectorAll('*')
    for (var i = 0; i < all.length; i++) {
      var e = all[i]
      if (e.hasAttribute('data-tv-scroll')) continue
      // 只看真的能滚的
      if (e.scrollHeight - e.clientHeight < 24) continue
      var oy = getComputedStyle(e).overflowY
      if (oy !== 'auto' && oy !== 'scroll') continue
      // 内部已有停靠点 / 可聚焦元素 → 不用补
      if (e.querySelector('[data-tv-scroll]')) continue
      if (e.querySelector(FOCUSABLE)) continue
      e.setAttribute('data-tv-scroll', '')
      e.setAttribute('tabindex', '0')
      // 补的元素不该有可见轮廓（焦点框另有规则）
      if (!e.style.outline) e.style.outline = 'none'
    }
  }

  function items() {
    var scope = activeScope()
    var root = scope || document
    return Array.prototype.slice.call(root.querySelectorAll(FOCUSABLE))
      .filter(visible)
      .filter(usable)
  }

  /**
   * 能不能按 —— 排除禁用元素。
   *
   * 踩过：FOCUSABLE 选择器不排除 [disabled]，visible() 也只管尺寸，于是
   * **禁用按钮照样能拿到焦点框，按 OK 什么都不发生**。用户分不清是
   * "这个按钮现在不能用"还是"遥控器坏了"。
   *
   * 实测（设置页视口内 33 个可聚焦元素）：其中 10 个是 disabled，
   * 按 OK 全部静默无反应 —— 都是"未填 key 时的测试连接""无内容时的清除"
   * 这类条件未满足的提交按钮，UI 上只是 opacity 0.4~0.5。
   *
   * 视频/音频元素不按 disabled 判（它们没有这个属性语义，但可能有
   * aria-disabled 表示加载中）——所以先看 tagName 再看属性。
   */
  function usable(el) {
    if (!el) return false
    var tag = el.tagName ? el.tagName.toLowerCase() : ''
    // 表单控件才有真正的 disabled；a/div 上没有这个属性
    if (tag === 'button' || tag === 'input' || tag === 'select' ||
        tag === 'textarea' || tag === 'fieldset' || tag === 'optgroup') {
      if (el.disabled) return false
    }
    // aria-disabled 任何元素都可能有，语义就是"现在不可用"
    if (el.getAttribute && el.getAttribute('aria-disabled') === 'true') return false
    return true
  }

  function center(r) {
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 }
  }

  function highlight(el) {
    /*
     * ★ 焦点框不能只靠注入的 <style>。
     *
     * 踩过（2026-11-20 MuMu .77，computedStyle 诊断）：页面 reload 之后
     * `document.getElementById('meow-tv-focus-style')` 在新 document 里查不到
     * （旧闭包的 installStyle 加到了已销毁的 head），于是 class 明明加上了，
     * `outlineWidth=0 / boxShadow=none` —— 屏幕上没有任何焦点框。
     * 输入框更明显：Tailwind 的 `outline-none` 把原生 focus ring 也关了，
     * 用户看到的就是"弹框里方向键不知道选到哪"。
     *
     * installStyle 是幂等的，这里每次移动焦点都补一次，代价可忽略。
     */
    if (!document.getElementById('meow-tv-focus-style')) installStyle()
    var all = items()
    /*
     * ★ 清旧标记必须扫全文档，不能只遍历 all。
     *
     * 踩过的坑：原来这里是 `for (i<all.length) all[i].classList.remove(...)`。
     * all 是**当前作用域**（弹层打开后只有弹层内的元素），而旧焦点框可能
     * 停在作用域外（背景的「置顶」）——它不在 all 里，于是那个 class 永远
     * 摘不掉。表现是"背景上留着一个黄色框，按方向键不动"，因为
     * querySelector('.meow-tv-focus') 永远先返回背景那一个。
     */
    var stale = document.querySelectorAll('.meow-tv-focus')
    for (var s = 0; s < stale.length; s++) {
      stale[s].classList.remove('meow-tv-focus')
      // 一并清掉内联兜底（见下），否则旧输入框会一直留着光圈
      if (stale[s].style) stale[s].style.removeProperty('box-shadow')
    }
    if (!el) return
    el.classList.add('meow-tv-focus')
    /*
     * 输入框/下拉框额外给内联 outline。
     *
     * ★ 为什么 box-shadow 不行（2026-11-20 实测）：样式里给 `.meow-tv-focus`
     *   画的是 box-shadow（inset 白 + inset accent + 外发光）。同一条规则在
     *   <button> 上正常，在 <input> 上**完全不渲染**（class 在、computedStyle
     *   的 boxShadow 仍是 none）。这是 WebView 对表单控件绘制的差异，不是
     *   选择器或优先级问题。outline 画在边框外沿，绕过这一层，稳定可见。
     *   内联而不是加 CSS 规则：<style> 在某些 reload 场景会整体丢失（上一条
     *   注释），内联样式跟着元素走，不会丢。
     */
    /*
     * 输入框/下拉框额外给内联焦点环 —— 值**逐字复制**基础 `.meow-tv-focus`
     * 规则（搜索框用的就是它），视觉上完全一致。
     *
     * ★ 为什么不能只靠样式表：弹层里的 `[data-tv-scope] .meow-tv-focus`
     *   规则在这里不生效（2026-11-20 实测 class 在、computed boxShadow=none），
     *   之前用内联 outline 兜底，但 outline 不跟圆角、只有单色 —— 用户对比
     *   搜索框后反馈「输入框样式不对」（截图里是方角粗环）。
     *   box-shadow 跟随 border-radius，双层（白内 + 青外 + 辉光）才是搜索框
     *   那个观感。
     *
     * ★ inline 必须 `!important`：FOCUS_CSS 里 `.meow-tv-focus` 是
     *   `box-shadow:…!important`，样式表的 !important 只有 inline !important
     *   能压过（同权重下 inline 赢）。
     */
    var tagName = el.tagName ? el.tagName.toLowerCase() : ''
    if (el.style && (tagName === 'input' || tagName === 'select' || tagName === 'textarea')) {
      el.style.setProperty(
        'box-shadow',
        'inset 0 0 0 1px rgba(255,255,255,.65),' +
          ' 0 0 0 2px #03dac5,' +
          ' 0 0 12px 2px #03dac5',
        'important',
      )
    }
    lastFocused = el
    scrollIntoViewWithin(el)
    current = all.indexOf(el)
    /*
     * 广播「焦点变了」，供页面续期自己的一次性 UI。
     *
     * 踩过的坑：播放页的控制栏是"5 秒无操作自动收起"。控制栏显示时存在
     * .meow-tv-focus，方向键会被上面 dispatchKey 里的判据直接消费去移焦点，
     * **完全不经过页面自己的 keydown**，于是页面那个"最后一次操作后重新计时"
     * 的逻辑永远收不到信号 —— 用户正在左右选按钮，5 秒一到控制栏就收走，
     * 还没来得及到想去的位置（用户反馈原话）。
     *
     * 用中性 DOM 事件而不是回调注册：tv-nav 是壳注入的独立脚本，
     * 不该反向依赖某个页面的内部函数（播放页会卸载、直播页另有逻辑）。
     * 页面自己决定要不要理会这个事件。
     */
    try {
      window.dispatchEvent(new CustomEvent('meow:tv-focus'))
    } catch (e) {}
  }

  /**
   * 当前焦点元素；class 被框架抹掉时自动补回来。
   *
   * <p>★ Vue 的 `:class` 更新会**重写整个 className**，我们在元素上加的
   * `meow-tv-focus` 会被一起覆盖掉。
   *
   * <p>踩过（2026-11-04 用户报「DNS 测试按完，再按左键跑到最下面的关于」）：
   * 「DNS 测试」按下后 `dnsBusy` 变 true → 按钮的 `:class` 更新（加
   * `opacity-40`）→ 焦点框 class 被抹 → 下一键没有起点，`find()` 退回
   * 「最后一个可聚焦元素」兜底 → 焦点落到页面最底部的「关于」。
   * 同理，任何"按下后自己变 class"的控件都会中招。
   *
   * <p>修法：记住最后一次高亮的元素（lastFocused）。它还在文档里、
   * 还是可见可用的，就说明只是标记被抹了 → 补回去继续用它当起点。
   */
  function currentFocus() {
    var marked = document.querySelector('.meow-tv-focus')
    if (marked && document.contains(marked)) return marked
    if (lastFocused && document.contains(lastFocused)
        && visible(lastFocused) && usable(lastFocused)) {
      lastFocused.classList.add('meow-tv-focus')
      return lastFocused
    }
    return null
  }

  /**
   * 把元素滚进可视区，逐层处理所有可滚祖先。
   *
   * ★ 为什么不用 `el.scrollIntoView({block:'nearest'})`：
   *   实测在「弹层 -> 面板(overflow:hidden) -> 内层滚动区(overflow-y:auto)」
   *   这种嵌套里它不生效——焦点已经跑到容器底部之外（focusBottom 485、
   *   容器可视下沿约 466），scrollTop 仍然恒为 0。用户看到的是"焦点框
   *   往下走但内容不动，下面几条选不到"。
   *   原因大概是浏览器要同时满足多个滚动祖先，嵌套 overflow 时它会
   *   选择"不滚"而不是部分滚。自己算就没这个问题。
   *
   * 从内向外逐层修：父级滚动会改变子元素的位置，所以每层都要重新取矩形。
   */
  function scrollIntoViewWithin(el) {
    var pad = 8 // 留一点余量，别让焦点框贴着边缘
    /*
     * 横向的余量要比纵向大：焦点环是 2px accent + 12px 辉光，合计外扩 14px。
     * 分类胶囊这类 overflow-x 容器如果只留 8px，环会被容器裁掉一小圈。
     */
    var padX = 16
    var node = el.parentElement
    while (node && node !== document.body) {
      var st = getComputedStyle(node)
      var scrollableY =
        (st.overflowY === 'auto' || st.overflowY === 'scroll') &&
        node.scrollHeight > node.clientHeight + 1
      if (scrollableY) {
        var nr = node.getBoundingClientRect()
        var er = el.getBoundingClientRect()
        if (er.top < nr.top + pad) {
          node.scrollTop -= nr.top + pad - er.top
        } else if (er.bottom > nr.bottom - pad) {
          node.scrollTop += er.bottom - (nr.bottom - pad)
        }
      }
      /*
       * ★ 横向滚动容器必须单独处理（2026-11-10 用户报「首页分类胶囊右键
       *   聚焦出界、看不见所选」）。
       *
       * 原来这里只看 overflowY，于是 <div class="overflow-x-auto"> 里的
       * 胶囊永远不滚：tv-nav 是"加 class 不算 focus()"，页面的 focusin
       * 居中也收不到；焦点 index 一直往右走，元素坐标跑到 1700+（视口
       * 1500），用户看到的就是"框没了"。
       *
       * 这里做最小必要滚动，把元素拉回可视区；页面若还想居中（HomeView
       * 的 meow:tv-focus 监听）会在之后覆盖成居中，两者不冲突。
       */
      var scrollableX =
        (st.overflowX === 'auto' || st.overflowX === 'scroll') &&
        node.scrollWidth > node.clientWidth + 1
      if (scrollableX) {
        var nrx = node.getBoundingClientRect()
        var erx = el.getBoundingClientRect()
        if (erx.left < nrx.left + padX) {
          node.scrollLeft -= nrx.left + padX - erx.left
        } else if (erx.right > nrx.right - padX) {
          node.scrollLeft += erx.right - (nrx.right - padX)
        }
      }
      node = node.parentElement
    }
    /*
     * 最后兜一层窗口滚动（窄屏单列布局时内容在 window 里滚）。
     *
     * ★ 播放页必须跳过：全屏播放器是 fixed 定位、页面本身不该滚，
     *   而底栏按钮贴底（实测 bottom≈703、视口 720、pad=8 → 判成"超出 9px"），
     *   于是**每次移动焦点都 window.scrollBy**，画面跟着上下抖。
     *   用户看到的就是"按一下方向键，视频画面跳一下"。
     *
     *   判据用 `__meowTvKeyOwner`（播放页在自己挂载时置 true），
     *   比猜容器尺寸稳：播放器可能不是全屏、也可能是伪全屏。
     */
    if (window.__meowTvKeyOwner) return
    /*
     * ★ 顶部要额外让出顶栏高度。
     *
     * 顶栏是 sticky 且常驻（64px），焦点滚到 top=8 时正好被它盖住——
     * 用户看到的是"焦点框跑到顶栏后面去了，看不见选到哪儿"。
     * 底部同理不用让：没有类似常驻层，留 pad 就够。
     *
     * ★ 焦点本身在顶栏里时不能让：那时 wr.top 恒为 0，会让位计算
     *   每次都触发 window.scrollBy 把页面往上顶（用户看到的是
     *   "焦点停在顶栏上，页面自己往上跳"）。
     */
    var header = document.querySelector('.app-header')
    // 弹层内的元素不算"在顶栏里"：站点下拉 DOM 上嵌在 .app-header 内，
    // 但它是上拉列表，要靠下面的滚动逻辑做最小必要滚动（见 move() 里的实测）。
    if (header && header.contains(el) && !el.closest('[data-tv-scope]')) return
    var padTop = pad + stickyTopHeight()
    var wr = el.getBoundingClientRect()
    if (wr.top < padTop) window.scrollBy(0, wr.top - padTop)
    else if (wr.bottom > window.innerHeight - pad) {
      window.scrollBy(0, wr.bottom - (window.innerHeight - pad))
    }
  }

  /** 顶部常驻层（顶栏）占掉的高度；没有则 0 */
  function stickyTopHeight() {
    var h = 0
    var header = document.querySelector('.app-header')
    if (header) {
      var pos = getComputedStyle(header).position
      if (pos === 'sticky' || pos === 'fixed') {
        var r = header.getBoundingClientRect()
        // 只在它确实贴着视口顶部时才算（页面滚到最顶上时 r.top 可能是 margin）
        if (r.top <= 1) h = Math.max(0, r.bottom)
      }
    }
    return h
  }

  /**
   * 元素是否处于"钉住层"（自身或祖先 fixed/sticky）。
   *
   * 为什么需要：顶栏是 `position: sticky`，几何上永远贴在视口顶部，
   * 它的中心点与任何内容的距离都很小。而真正在它上面的内容（首页的
   * 胶囊分类、设置页的页头）一旦滚出视口，中心点距离会变得很大。
   * 于是"上键"永远优先选中顶栏——用户看到的是"往上滚不回页面顶部，
   * 直接跳到顶栏去了"（web 上用鼠标滚轮，所以没这个症状）。
   *
   * 结论：普通页流里导航时，钉住层不参与几何竞争；只有当那个方向
   * 上确实没有别的东西了（页面已经在最顶上），才允许走到顶栏。
   * 焦点本身已在钉住层里（播放器底栏、弹层）时不适用——那时整屏都是钉住的。
   */
  function pinned(el) {
    for (var n = el; n && n !== document.body; n = n.parentElement) {
      var pos = getComputedStyle(n).position
      if (pos === 'fixed' || pos === 'sticky') return true
    }
    return false
  }

  /*
   * pinned 的缓存。
   *
   * 每次按键要判 20~100 个元素，每个都要沿祖先链取 computedStyle；
   * Chrome 66 上这一下能吃掉十几毫秒（遥控器连按时可感知）。
   * 一次 find() 内复用结果，按完即丢——DOM 变化后不许留着旧判断。
   */
  var pinnedCache = null
  function pinnedCached(el) {
    if (!pinnedCache) pinnedCache = new WeakMap()
    var v = pinnedCache.get(el)
    if (v === undefined) {
      v = pinned(el)
      pinnedCache.set(el, v)
    }
    return v
  }

  /**
   * 最近的纵向滚动祖先；返回 null 代表 window。
   *
   * 为什么从 `parentElement` 开始：`data-tv-scroll` 容器自己就是焦点停靠点，
   * 它应该和外面的“返回/关闭”按钮属于同一层滚动区。若从自己开始算，
   * 容器会被判成独立区域，滚到底后反而出不去。
   */
  function nearestScrollParent(el) {
    for (var n = el.parentElement; n && n !== document.body; n = n.parentElement) {
      var st = getComputedStyle(n)
      if ((st.overflowY === 'auto' || st.overflowY === 'scroll') &&
          n.scrollHeight > n.clientHeight + 1) return n
    }
    return null
  }

  /*
   * 一次 find() 内缓存滚动区判断。
   *
   * 和 pinnedCached 同理：每次按键要扫几十个候选，每个都沿祖先链取
   * computedStyle，Chrome 66 上会明显增加按键耗时。一次按键后即丢，
   * DOM/布局变化后不沿用旧结果。
   */
  var scrollParentCache = null
  function scrollParentCached(el) {
    if (!scrollParentCache) scrollParentCache = new WeakMap()
    var v = scrollParentCache.get(el)
    if (v === undefined) {
      v = nearestScrollParent(el)
      scrollParentCache.set(el, v)
    }
    return v
  }

  /**
   * 元素属于哪一"纵向列"。
   *
   * <p>页面用 `data-tv-col="left|right"` 标记左右两栏；没标记（顶栏、
   * 返回、页脚、绝大多数页面）返回空串，行为与以前完全一致。
   *
   * <p>为什么要这个东西：详情页简介展开后，「收起简介」的 y 恰好落在
   * 集数第 3 行的水平带上。上下键的几何算法先按"最近一行"（primary ≤ 20px）
   * 取候选，它的纵向距离只有 1px，比真正的上一行（56px）小得多 →
   * 上键从 14 跳到左栏按钮。列隔离后左右两栏各自上下，跨列只能靠左右键。
   */
  function colOf(el) {
    var host = el && el.closest ? el.closest('[data-tv-col]') : null
    return host ? (host.getAttribute('data-tv-col') || '1') : ''
  }

  /** 在给定方向上找"最近且在正确一侧"的元素 */
  function find(dir, includePinned, colMode) {
    // 一次按键内复用滚动区判断；第二次 find(dir, true) 会重新算，避免沿用旧布局。
    scrollParentCache = null
    var all = items()
    if (!all.length) return null
    /*
     * ★ 起点的选取顺序很关键，踩过两次：
     *
     * 1. 先认 `.meow-tv-focus`（真正画着焦点框的那个）。它是唯一可靠的
     *    "用户现在看着哪儿"的证据。
     * 2. 再退回 activeElement（TV 上通常是 body，几乎帮不上忙）。
     * 3. **不要用 all[current]** —— current 是上一次列表的索引，
     *    列表一变（比如弹层打开把范围从 116 收窄到 6）就完全错位，
     *    于是从弹层里某个不相干的元素出发算方向，结果算不出目标、
     *    表现为"按方向键毫无反应"（实测：弹层打开后 down 连按三次焦点不动）。
     */
    var marked = currentFocus()
    var from = marked && all.indexOf(marked) >= 0 ? marked : null
    if (!from) {
      var ae = document.activeElement
      if (ae && ae !== document.body && all.indexOf(ae) >= 0) from = ae
    }
    if (!from) {
      // 没有可用起点：上/左从末尾开始，下/右从开头开始，符合电视直觉
      return dir === 'up' || dir === 'left' ? all[all.length - 1] : all[0]
    }
    /*
     * ★ 钉住层（顶栏）默认不参与几何竞争。
     *
     * 踩过的坑：顶栏 `position: sticky` 常驻视口顶部（中心 y≈32）。
     * 焦点卡片滚到顶上后中心 y≈114，两者只差 82，而"上一排卡片"差约 250——
     * 于是上键永远优先选中顶栏，胶囊分类/上一排卡片再也够不到。
     * 用户看到的是"往上滚不回页面顶部，直接跳到顶栏了"。
     * （web 用滚轮，没有这个问题，所以只在 TV 上暴露。）
     *
     * 只有当这个方向上确实没有普通内容了（页面已在顶部），
     * 才由调用方带上 includePinned 再扫一次，那时才允许走进顶栏。
     * 起点本身在钉住层里（播放器底栏、弹层）时不受影响。
     */
    var skipPinned = !includePinned && !pinned(from)
    /*
     * ★ 上下键优先只在“同一个纵向滚动区”里找。
     *
     * 踩过（2026-11-03 实测）：设置页右栏向上滚时，焦点被顶到 y≈96，
     * 真正的上一行已经在视口外（y=-110）；而左栏「置顶」固定 y≈97，
     * 几何上反而更近，于是上键跳到左栏。两者不在同一个滚动容器里，
     * 本就不该参与同一次上下比较。
     *
     * 只对上下生效：左右键是“换列”，左栏必须仍能通过 ←/→ 进入。
     * 弹层内不隔离：items() 已经把范围收窄到弹层，弹层内容滚动区和
     * 底部「返回/确定」按钮必须还能互相到达。
     * null 也是一种滚动区（window）：左栏没有自己的滚动容器，若把
     * “null 不参与隔离”当成例外，右栏输入框会因纵向差 12px 抢走左栏
     * 的下一项（实测 2026-11-03：01 连接 ↓ 落到接口地址输入框）。
     */
    var vertical = dir === 'up' || dir === 'down'
    var regionMode = null
    if (vertical && !pinned(from) && !document.querySelector('[data-tv-scope]')) {
      regionMode = 'same'
    }
    /*
     * ★ 有列隔离时，滚动区隔离要让位（2026-11-04 用户报）。
     *
     * 踩过：仙逆第 2 段（101-166），从 101 按上键直接跳到顶栏。
     * 原因不是列，而是**滚动区**：101 在选集滚动容器里（scrollParent=容器），
     * 而「1-100 / 101-166」胶囊和「播放源」在容器**外面**（scrollParent=window），
     * regionMode='same' 把它们全过滤掉了 → 同列没有候选 → 兜底进顶栏。
     *
     * 列已经保证了"不会串到另一栏"，滚动区这层在这里只会把本该可达的
     * 同列元素排除掉。两者同时存在时以列为准。
     */
    if (colMode) regionMode = null
    /*
     * ★ 起点在顶栏时，左右键**只在顶栏内部移动**。
     *
     * 用户反馈原话：「顶栏 ai 焦点后，右键应聚焦到搜索框、设置按钮。
     * 目前发现会聚焦到页面内容里，在页面内一直右键、上键才能到顶栏设置」。
     *
     * 原因是几何竞争：顶栏里 AI 图标右边是搜索框，但**下方**的内容区
     * 也可能有元素在 dx>4 的范围内，且 dy 更大——`secondary * 2.5` 的加权
     * 挡不住某些窄卡片。一旦争输，焦点就掉进页面内容，用户再也回不来。
     *
     * 顶栏是一维的（只有左右两方向有意义），所以这里直接把候选范围
     * 限制在顶栏内：找不到才放开（例如顶栏最右侧按右键，允许去内容区）。
     */
    if (pinned(from) && (dir === 'left' || dir === 'right')) {
      var barOnly = scan(all.filter(function (x) { return pinnedCached(x) }), from, dir, false)
      if (barOnly) return barOnly
      /*
       * ★ 顶栏内到头了就**停住**，不要掉进内容区。
       *
       * 踩过（实测）：原来这里注释写"允许走出顶栏"，于是顶栏最右的「设置」
       * 按右键会按全量几何落到内容区的某张卡片上。用户视角是"我在顶栏横着走，
       * 突然跑到下面的海报墙上，还得按好几下上键才回得来"——顶栏是一维的，
       * 走到头停住才符合直觉。
       *
       * 上下键不受影响（上面那段只拦 left/right）：从顶栏按 ↓ 仍然进内容区，
       * 这是离开顶栏的正常出口。
       */
      userMoved = true
      return false
    }
    /*
     * ★ 列隔离：上下键只在同一 `data-tv-col` 里竞争。
     *
     * colMode='same'  → 只要同列的元素（正常一列里上下走）
     * colMode='loose' → 只要"不属于任何列"的元素（返回、顶栏、页脚）
     *
     * 分成两轮而不是一次过滤：列里走到头时，用户的本意是离开这一列
     * （上顶栏 / 上返回），但**不是**跳到旁边那一列——那是左右键的语义。
     * 只对上下生效；左右键仍走全量，所以边界处能跨列。
     */
    if (colMode) {
      var wantCol = colMode === 'same' ? colOf(from) : ''
      all = all.filter(function (x) { return x === from || colOf(x) === wantCol })
    }
    var got = scan(all, from, dir, skipPinned, regionMode)
    /*
     * 同一滚动区里确实没有候选了，才允许跨区兜底。
     *
     * 这条是“不把列表锁死”的出口：详情页集数滚到顶/底、日志/对话滚到底，
     * 同区没有下一个元素时仍要能走到「返回」「输入框」等区域外按钮。
     * 正常中途移动时同区一定有候选，所以左栏不会重新参与竞争。
     */
    if (!got && includePinned && regionMode) {
      got = scan(all, from, dir, skipPinned, 'cross')
    }
    /*
     * ★ 左右键的跨列兜底（2026-11-04 用户报「101 集左键不行」）。
     *
     * 左右键默认"只走同一行"（2026-11-03 为了不让首页首列右键跳到
     * 上方分类胶囊）。但详情页 101 在最左列：左边那栏的按钮
     * （继续看/收藏）都在更低的行上，于是左键毫无反应。
     *
     * 只在两种条件同时满足时放开：
     *   ① 当前元素在某个 `data-tv-col` 列里（页面显式声明了左右栏）
     *   ② 目标在**另一列**（不是同列的其它行）
     * 没有列标记的页面（首页海报墙、列表页…）行为完全不变。
     */
    if (!got && !vertical) {
      var myCol = colOf(from)
      if (myCol) {
        var otherCol = all.filter(function (x) {
          if (x === from) return false
          var c = colOf(x)
          return !!c && c !== myCol
        })
        if (otherCol.length) {
          got = scan(otherCol, from, dir, skipPinned, regionMode, true)
        }
      }
    }
    return got
  }

  /** 点到区间的距离：落在区间内返回 0。 */
  function gapTo(v, lo, hi) {
    if (v < lo) return lo - v
    if (v > hi) return v - hi
    return 0
  }

  /**
   * 几何最近邻选焦。
   *
   * <p><b>为什么从"中心点距离"改成"区间距离"</b>：原来
   * `score = 主轴中心距 + 次轴中心距 × 2.5`，只要下一行元素与当前元素
   * 中心不对齐（宽度不同、右侧有同位文案、左右分栏），就会被判成"斜得远"。
   *
   * 实测（设置页，从「接口地址」按下键）：
   *   模型     732×33  dx=−68 dy=38  score=208
   *   API Key  868×33  dx=0   dy=76  score=76  ← 隔一行的赢了紧邻的
   * 因为「模型」框被右侧「可填，不限定厂商」挤窄，中心点左偏 68px，
   * ×2.5 放大成 170 的惩罚。
   *
   * 跳行率实测：设置页 96 个候选里 12 个（13%）会跳行；首页/搜索/历史/收藏
   * 全是 0%（均匀网格，横向惩罚在那里恰恰是对的）。
   *
   * <p><b>改法</b>：主轴用"到投影区间的距离"（有重叠即 0），次轴同样用区间
   * 距离 ×2。网格与表单同时对 —— 网格里同列元素的横向区间本来就重叠、
   * 异列不重叠；表单里紧邻的下一行纵向区间距离为 0。
   * 设置页跳行 14 → 1。
   */
  function scan(all, from, dir, skipPinned, regionMode, rowFree) {
    var strict = scanStrict(all, from, dir, skipPinned, regionMode, rowFree)
    if (strict || dir === 'up' || dir === 'down') return strict
    /*
     * 左右键在严格轮没找到人时放开重扫一次。
     *
     * 严格轮的判据是"横向被包住 + 纵向不重叠 → 出局"，用来修「左导航
     * 右键落回『置顶』」。放开后仍然只接受“同一行”的候选（scanImpl 结尾
     * 直接返回 rowBest）；所以这一轮只是不甘心漏掉同行里被严格判据误杀
     * 的控件，不会重新允许跨行。
     *
     * rowFree=true 时才会跨行（只由 find() 在"跨列兜底"时传，见那里的注释）。
     */
    return scanImpl(all, from, dir, skipPinned, false, regionMode, rowFree)
  }

  function scanStrict(all, from, dir, skipPinned, regionMode, rowFree) {
    return scanImpl(all, from, dir, skipPinned, true, regionMode, rowFree)
  }

  function scanImpl(all, from, dir, skipPinned, strict, regionMode, rowFree) {
    var a = from.getBoundingClientRect()
    var vertical = dir === 'up' || dir === 'down'
    /*
     * 垂直移动的“最近行优先”候选池。
     *
     * 不能边扫边比 `primary + secondary*2`：横向惩罚可能上千，
     * 会让两行/五行下的左对齐元素赢过紧邻行的右对齐开关。
     * 先全部收下来，循环结束后按 primary 取最近一行，再按 secondary 选列。
     */
    var verticalCands = vertical ? [] : null
    var isolateRegion = vertical && regionMode === 'same'
    var fromRegion = isolateRegion ? scrollParentCached(from) : null
    /*
     * ★ 左右键的「同行优先」备选（2026-11-02，用户报「首页第一行海报右键
     *   偶尔会跳到上方的分类胶囊里」）。
     *
     * 为什么会跳：主轴用的是**区间距离**，横向区间一旦重叠就算 0 分。
     * 分类胶囊那一行横跨整页，海报第一行的第一个卡片往右按，
     * 上方某颗胶囊的横向距离可能比右边那张海报还小；次轴只乘 2 倍，
     * 抵消不了这个优势 —— 于是焦点往上跑。
     *
     * 用户对电视的预期很明确：**横向就是横向**，同一行走到头就停住，
     * 不要跨到上一行/分类胶囊/顶栏。所以左右键再分一轮：先把"垂直方向
     * 与当前元素有交集的候选"筛出来，有就在里面选，一个都没有就返回 null。
     *
     * 容差 8px 给卡片标题行高不一致、以及轻微错位的情况。
     */
    var rowBest = null
    var rowScore = Infinity
    /*
     * rowFree 模式的备选：不要求同一行，直接按几何分取最优。
     * 只给 find() 的"跨列兜底"用（详情页 101 左键 → 左栏按钮）。
     */
    var freeBest = null
    var freeScore = Infinity
    for (var i = 0; i < all.length; i++) {
      var el = all[i]
      if (el === from) continue
      if (skipPinned && pinnedCached(el)) continue
      /*
       * 上下键只在同一个滚动区里竞争；pinned 层和“当前滚动的停靠点容器”
       * 例外。后者必须留：焦点落在滚动区内的最后一个元素时，下一步要能
       * 落到容器自身，再由容器把内容滚到底（见 move() 的 data-tv-scroll 分支）。
       */
      if (isolateRegion && !pinnedCached(el)) {
        var sameScrollStop = el.hasAttribute && el.hasAttribute('data-tv-scroll') && el.contains(from)
        if (!sameScrollStop && scrollParentCached(el) !== fromRegion) continue
      }
      var b = el.getBoundingClientRect()
      /*
       * 方向判定仍用中心点：必须"确实在那个方向"才有资格。
       * 用区间的话，一个横跨当前元素的超宽容器会在两个方向都算 0，
       * 导致按下键选到自身所在行。
       */
      var okSide
      if (dir === 'right') okSide = b.left + b.width / 2 > a.left + a.width / 2 + 4
      else if (dir === 'left') okSide = b.left + b.width / 2 < a.left + a.width / 2 - 4
      else if (dir === 'down') okSide = b.top + b.height / 2 > a.top + a.height / 2 + 4
      else okSide = b.top + b.height / 2 < a.top + a.height / 2 - 4
      if (!okSide) continue
      /*
       * ★ 左右键：横向被当前元素完整包住、且不在同一行的候选先出局。
       *
       * 踩过（用户反馈「左导航按右键进不去右栏，落回左栏的『置顶』，
       * 要按几下 ↓ 再按 → 才进得去」）：
       * 设置页左导航的「置顶」按钮靠右对齐，横向区间被当前导航项完全包住，
       * 主轴用区间距离因而算出 0（满分），垂直只差 30px → 总分 60；
       * 真正的右栏元素横向要走 32+32=64 → 「置顶」赢，焦点往上跳回本栏。
       * 按几下 ↓ 后「置顶」垂直拉开，右栏才终于胜出——正是"按几下才进得去"。
       *
       * 判据是「横向包含 + 纵向不重叠」，两个条件缺一不可：
       *   - 横向包含说明它和当前元素在同一列，那是上下键的分内事；
       *   - 若纵向也重叠（同一行），它是行内子控件 —— 输入框内嵌的清除按钮、
       *     卡片右上角的角标 —— 那是左右键该到的。
       * 容差 4px 给负 margin 贴边的按钮组。
       *
       * 只作第一轮：见 scan() 里的放开兜底。
       */
      if (!vertical && strict &&
          b.left >= a.left - 4 && (b.left + b.width) <= (a.left + a.width) + 4 &&
          !(b.top < a.top + a.height - 4 && (b.top + b.height) > a.top + 4)) {
        continue
      }
      // 主轴：到投影区间的距离（重叠即 0）
      var primary = vertical
        ? gapTo(a.top, b.top, b.bottom) + gapTo(b.top, a.top, a.bottom)
        : gapTo(a.left, b.left, b.right) + gapTo(b.left, a.left, a.right)
      // 次轴：同样用区间距离，权重 2（比原来的 2.5 略低 ——
      // 区间距离已经天然偏向"对齐的那一列"，不需要那么重的放大）
      var secondary = vertical
        ? (gapTo(a.left, b.left, b.right) + gapTo(b.left, a.left, a.right))
        : (gapTo(a.top, b.top, b.bottom) + gapTo(b.top, a.top, a.bottom))
      var score = primary + secondary * 2
      /*
       * ★ 滚动停靠点在上下方向上加一点权重。
       *
       * 踩过（2026-10-30 统计面板）：面板里 4 个候选 —— 「返回」（顶栏）、
       * 内容滚动容器（894×490）、「去找片」「关闭」（底栏）。
       * 焦点从「去找片」按上键时，几何上容器与「返回」都可能命中，
       * 「返回」因为更靠近垂直线而赢，于是**上键永远回不到内容区**，
       * 用户滚到底之后只能一路走按钮、看不到内容。
       *
       * 判据用 `data-tv-scroll`（tv-nav 自己的约定）：它代表"这里是一大块内容"，
       * 用户从按钮往上按，意图几乎总是回到内容区，而不是跳到标题栏的返回键。
       * 打 0.6 折既保证优先，又不会压过真正紧邻的按钮（折扣太狠会在
       * 普通页面里把焦点吸进可滚列表）。
       */
      if (vertical && el.hasAttribute && el.hasAttribute('data-tv-scroll')) score *= 0.6
      if (vertical) {
        verticalCands.push({
          el: el,
          primary: primary,
          secondary: secondary,
          score: score
        })
        continue
      }
      // 同行候选（见函数开头 rowBest 的注释）
      /*
       * ★ 判据是「候选的**中心**落在当前元素的纵向带内」，不是「两段区间有交集」。
       *
       * 踩过（2026-11-05 用户再报「首页第一行海报右键总跳到上方分类胶囊」）：
       * 区间交集 + 8px 容差在字体放大后会误判 —— 胶囊那一行（py-1.5 + text-sm）
       * 和第一行海报之间的视觉间隙只有十几 px，而胶囊的**下边缘**能蹭进容差带，
       * 于是同一行判据把它收进候选。中心点判据不会有这个问题：胶囊中心离
       * 海报行带宽还有几十 px，怎么放大都进不来。
       *
       * 同一行里中心点一定落在对方的纵向带内（含内嵌小控件、底/顶对齐的按钮），
       * 所以收紧不会漏掉正常的左右邻居。
       */
      var bandLo = a.top - 8
      var bandHi = a.bottom + 8
      var candCy = b.top + b.height / 2
      if (!vertical && candCy >= bandLo && candCy <= bandHi && score < rowScore) {
        rowScore = score
        rowBest = el
      }
      if (!vertical && rowFree && score < freeScore) {
        freeScore = score
        freeBest = el
      }
    }
    /*
     * 上下键：先锁定“最近的一行”，再在这一行里按横向距离选列。
     *
     * 这是与原来的关键差别：原来 `primary + secondary*2` 直接比总分，
     * 横向差 500px 就是 1000 分，足以让两行/五行下的左对齐元素赢过
     * 紧邻行的右对齐开关。这里不允许跨行比较次轴，所以设置页会逐行走。
     */
    if (vertical) {
      if (!verticalCands.length) return null
      var minPrimary = Infinity
      for (var vi = 0; vi < verticalCands.length; vi++) {
        if (verticalCands[vi].primary < minPrimary) minPrimary = verticalCands[vi].primary
      }
      var vBest = null
      var vSecondary = Infinity
      var vScore = Infinity
      for (var vj = 0; vj < verticalCands.length; vj++) {
        var vc = verticalCands[vj]
        if (vc.primary > minPrimary + VERTICAL_ROW_TOL) continue
        if (vc.secondary < vSecondary ||
            (vc.secondary === vSecondary && vc.score < vScore)) {
          vSecondary = vc.secondary
          vScore = vc.score
          vBest = vc.el
        }
      }
      return vBest
    }
    /*
     * 左右键：只走同一行；同一行没有候选就停住。
     *
     * 踩过（2026-11-03 验收）：原来这里 `return best` 会回退到全量几何，
     * 于是首页首列 ← 能跳到顶部分类胶囊、设置页行尾 → 能跳到 380px 外的
     * 「保存」、详情页集数行尾 → 能跳到上一行集数。
     *
     * 电视遥控的横向语义是“换列”，不是“换行/换区”。真正要换行时用户
     * 会按上/下，不能靠横向键顺手跨行。纵向移动完全不走这里（上方
     * `if (vertical)` 已先返回），所以不影响 ↑/↓ 的换行链路。
     */
    return rowBest || (rowFree ? freeBest : null)
  }

  /**
   * 让"整块可滚动但内部没有可聚焦元素"的容器也能被遥控器滚动。
   *
   * 为什么需要：日志列表、AI 对话区这类页面，可滚容器里一条可聚焦元素都没有
   * （几千行日志若每行都可聚焦，方向键要按几千次）。按通用规则焦点只能
   * 落在容器外的按钮上，上下键在容器之间跳，内容一动不动——
   * 用户看到的是"日志翻不了页"。
   *
   * 约定：容器自己标 `data-tv-scroll` + `tabindex="0"`，成为唯一焦点停靠点；
   * 上下键在它身上先滚动，滚到头才把按键还给焦点移动（否则到了底部就出不去）。
   */
  function scrollStep(el, dir) {
    /*
     * ★ 步长 0.5 屏，不是 0.7。
     *
     * 踩过（2026-10-30 统计面板实测）：0.7 时容器 490px、一屏要滚 343px，
     * 而内容只有 791px —— **按一次下键就从 0 直接到底（301px）**，
     * 中间的图表区一闪而过。用户想看的是"往下挪一点、再看下一屏"，
     * 不是"翻一页"。
     *
     * 0.5 屏在常见高度下大约 2~3 次到底，配合每次都有内容露出，
     * 才像"浏览"而不是"跳转"。最小 48px 保证小容器也滚得动。
     */
    var step = Math.max(48, Math.round((el.clientHeight || 0) * 0.5))
    var before = el.scrollTop
    el.scrollTop += dir === 'down' ? step : -step
    return el.scrollTop !== before
  }

  /**
   * 找到页面上真正在滚的那个容器。
   *
   * ★ 不能只看 window。设置页在 lg 断点下是「左导航 + 右内容」两栏，
   *   右栏自己才是滚动容器（`lg:overflow-y-auto`），window 根本不滚
   *   （window.scrollY 恒为 0）。原来只滚 window，于是顶栏按上键毫无反应，
   *   用户看到的就是"滚不回最上方的连接配置"。
   *
   * 判据是「可滚且当前有滚动量」，取滚动量最大的那个：单栏页面是 window /
   * documentElement，两栏页面是右栏，两种情况都能命中。
   */
  function activeScroller() {
    var best = null
    var bestTop = 0
    // window 侧（单栏页面）
    var winTop = window.scrollY ||
      (document.scrollingElement ? document.scrollingElement.scrollTop : 0) ||
      document.documentElement.scrollTop || 0
    if (winTop > 0) { best = null; bestTop = winTop } // best=null 代表滚 window
    // 元素侧：找滚动量更大的
    var all = document.querySelectorAll('*')
    for (var i = 0; i < all.length; i++) {
      var e = all[i]
      if (!e.scrollTop) continue
      var oy = window.getComputedStyle(e).overflowY
      if (oy !== 'auto' && oy !== 'scroll') continue
      if (e.scrollTop > bestTop) { best = e; bestTop = e.scrollTop }
    }
    return { el: best, top: bestTop }
  }

  /** 往上滚一屏；滚得动返回 true */
  function scrollPageUp() {
    var s = activeScroller()
    if (s.top <= 0) return false
    var step = Math.round((s.el ? s.el.clientHeight : window.innerHeight) * 0.6)
    if (s.el) s.el.scrollTop = Math.max(0, s.el.scrollTop - step)
    else window.scrollBy(0, -step)
    return true
  }

  /**
   * 滑块（input[type=range]）在电视上的左右键调速。
   *
   * 用户原话（2026-11-04）：「透明字号、滚动区域等滑动区域，在 tv 上应该
   * 做好控制。如聚焦到滑动区域时，对左右按键进行操控来调节」。
   *
   * 为什么必须在这里做：方向键在壳里就被 tv-nav 截获，页面收不到 keydown，
   * 所以只能在 tv-nav 里改。步长按 (max-min)/20 —— 20 格刚好"能微调、
   * 也不会按到手酸"，按住不放靠系统的按键重复天然连续。
   *
   * 调完派发 input + change：Vue 的 v-model（@input）立刻响应，
   * 拖动条上的数字和弹幕效果同步跟着变。
   */
  function adjustRange(el, dir) {
    var min = parseFloat(el.min)
    var max = parseFloat(el.max)
    var cur = parseFloat(el.value)
    if (isNaN(min)) min = 0
    if (isNaN(max)) max = 100
    if (isNaN(cur)) cur = min
    var step = parseFloat(el.step)
    if (isNaN(step) || step <= 0) step = (max - min) / 20 || 1
    var next = cur + (dir === 'right' ? step : -step)
    next = Math.max(min, Math.min(max, next))
    if (next === cur) return false
    el.value = String(next)
    el.dispatchEvent(new Event('input', { bubbles: true }))
    el.dispatchEvent(new Event('change', { bubbles: true }))
    return true
  }

  /**
   * 页面显式声明的左右出口：`data-tv-right` / `data-tv-left`。
   *
   * <p>为什么需要：几何选焦要求"目标与当前元素在同一行"（左右键的 rowBest
   * 规则），这样横向移动才稳定。但设置页这类「左侧导航 + 右侧内容」的
   * 两栏布局里，导航项和右侧第一个控件的高度**不对齐**（导航 33px 一项、
   * 右侧分区从卡片内边距开始），于是从导航按右键时会因为"同一行没有候选"
   * 而原地不动 —— 实测 15 个导航项里有 8 项（02/04/07/09/11/12/14/15）
   * 完全进不去内容区。
   *
   * <p>怎么用：给导航项标 `data-tv-right="section[data-sec=…]"`。目标可以
   * 是任意容器，不是可聚焦元素时取它内部第一个可见可用的控件；再给内容
   * 容器标 `data-tv-left="#导航项id"`，左键就能精确回到进入时的那一项，
   * 而不是几何上"差不多同一行"的邻居。
   *
   * <p>只在元素自己声明了属性时生效，其它页面的行为完全不变。
   */
  function lateralTarget(from, dir) {
    if (!from || !from.getAttribute) return null
    var attr = dir === 'right' ? 'data-tv-right' : 'data-tv-left'
    /*
     * 属性可以写在控件自己身上（导航项），也可以写在整块容器上
     * （内容区统一"左键回导航"）。向上找几层即可，不做无限继承。
     */
    var sel = null
    var node = from
    for (var i = 0; i < 8 && node && node.getAttribute; i++, node = node.parentElement) {
      sel = node.getAttribute(attr)
      if (sel) break
    }
    if (!sel) return null
    var target = null
    try {
      target = document.querySelector(sel)
    } catch (e) {
      return null
    }
    if (!target) return null
    var all = items()
    var resolved = all.indexOf(target) >= 0 ? target : null
    if (!resolved) {
      var candidates = Array.prototype.slice
        .call(target.querySelectorAll(FOCUSABLE))
        .filter(visible)
        .filter(usable)
      resolved = candidates.length ? candidates[0] : null
    }
    if (!resolved) return null
    return { el: resolved, owner: node, direct: node === from }
  }

  function move(dir) {
    var marked = currentFocus()
    /*
     * 焦点落在滑块上：左右键 = 调值，上下键照常移焦点（不然出不去）。
     * 放在最前面，压过后面所有几何选焦逻辑。
     */
    if (marked && (dir === 'left' || dir === 'right') &&
        marked.tagName === 'INPUT' && String(marked.type).toLowerCase() === 'range') {
      if (adjustRange(marked, dir)) {
        userMoved = true
        return true
      }
    }
    /*
     * 页面声明的左右出口优先于几何选焦（见 lateralTarget 的注释）。
     * 只处理左右：上下仍由几何/列隔离负责，页面不需要为纵轴再写一套。
     */
    if (marked && (dir === 'left' || dir === 'right')) {
      var lateral = lateralTarget(marked, dir)
      if (lateral) {
        /*
         * 直接写在控件上的属性 = 显式改道（导航项右键进内容区）。
         * 写在容器上的属性 = 本列的边界出口：同行还有邻居就照常走几何，
         * 只有几何无目标、或目标跑到容器外面（比如左侧导航）时才用它。
         * 少了这层判断，按钮行里按左键会直接跳过整行邻居跳到导航。
         */
        var geo = find(dir, false)
        if (lateral.direct || !geo || (lateral.owner && !lateral.owner.contains(geo))) {
          userMoved = true
          highlight(lateral.el)
          return true
        }
      }
    }
    /*
     * ★ 顶栏上的上键要"滚页面"，不是"往上找邻居"。
     *
     * 顶栏里没有任何元素在上方，按几何找一定返回 null，表现是
     * "焦点停在顶栏上，按上键毫无反应"。而用户此刻的意图几乎总是
     * "把页面滚回顶部"（他在顶栏上是因为刚才上键走到头了）。
     *
     * ★ 必须滚「真正在滚的容器」而不是 window：设置页是两栏布局，
     *   滚的是右栏而不是 window（见 activeScroller 的注释）。
     *
     * ★★ `!marked.closest('[data-tv-scope]')` 这一条不能删。
     *
     * 踩过（用户反馈「源下拉框，聚焦位于最低处时下键能显示完整聚焦，
     * 上键时聚焦被遮挡到下面了」，2026-10-30 MuMu 实测）：
     * 站点下拉是 `absolute` 定位、**DOM 上就嵌在 `.app-header` 里面**，
     * 于是每一个下拉项都满足 `closest('.app-header')`。在上拉列表里按上键，
     * 走的就是这个"滚页面"分支 → `scrollPageUp()` 按下拉自身的
     * `clientHeight × 0.6`（实测 446×0.6 = 268px）整块滚动，
     * **焦点一步没动、却跟着内容被推出可视区**。
     * 实测序列：scrollTop 856→588→320→52→0，而焦点矩形 top 461→729→997→1265→1317
     * —— 用户看到的正是"往上按，选中项反而掉到下面看不见了"。
     * 下键不受影响（不走这个分支），所以症状只在向上时出现。
     *
     * 判据用"在不在弹层作用域内"：弹层里的上下键必须交给几何移动，
     * 让 `scrollIntoViewWithin` 做**最小必要滚动**（一次一行）。
     */
    if (dir === 'up' && marked && marked.closest('.app-header')
        && !marked.closest('[data-tv-scope]')) {
      if (scrollPageUp()) {
        userMoved = true
        return true
      }
      /*
       * 页面已经在顶部（滚不动了）→ 停在顶栏。
       *
       * 踩过（2026-11-03 验收）：原来这里会把焦点送回内容区第一项，
       * 而内容区第一项在设置页是左栏「置顶」；从「置顶」再按 ↑ 又会被
       * 钉住层兜底送回顶栏「直播」，于是形成
       * `片库 → 置顶 → 直播 → 置顶…` 的同横排自环。
       *
       * 顶栏本身是横向一维的：到顶后按 ↑ 没有“更上面”的目标，停住即可；
       * 要回内容区请按 ↓（move 的下方逻辑会走 find('down')）。
       */
      userMoved = true
      return false
    }
    /*
     * ★ 顶栏 ↓ 要回“右侧内容区第一项”，不能落到左栏「置顶」。
     *
     * 踩过（2026-11-03）：从顶栏「片库」按 ↓，几何上左栏「置顶」
     * （y≈97）比右栏第一个输入框（y≈140）更近，于是焦点进了左导航。
     * 顶栏 ↓ 的语义是“离开顶栏、回到正在看的内容”，不是“进另一列菜单”。
     *
     * 只在“焦点在 header + ↓”时生效；↑ 已在上面的分支停住，不受影响。
     * 弹层内的 header 元素（站点下拉）必须排除，仍未这条老坑。
     */
    if (dir === 'down' && marked && marked.closest('.app-header')
        && !marked.closest('[data-tv-scope]')) {
      var firstContent = firstContentInScrollHost(items())
      if (firstContent) {
        userMoved = true
        highlight(firstContent)
        return true
      }
    }
    if (marked && marked.hasAttribute('data-tv-scroll') && (dir === 'up' || dir === 'down')) {
      // 滚得动就吃掉这次按键：焦点不动，用户是在翻内容
      if (scrollStep(marked, dir)) {
        userMoved = true
        return true
      }
      // 已经到头 → 落到下面的焦点移动，让他能离开这块区域
    }
    /*
     * ★ 上下键：焦点在某一列里时，只在同一列里找；列里到头了才允许
     *   走到"不属于任何列"的元素（返回、顶栏）。
     *
     * 踩过（2026-11-04 用户报「第 2、3 行集数之间上下移动会跳到左侧按钮区」）：
     * 简介展开后「收起简介」正好落在集数第 3 行的水平带上，纵向只差 1px，
     * 比真正的上一行（56px）更"近" —— 几何算法必然选它。
     * 列隔离后：右侧集数只会在集数/线路里上下，左侧只在简介/播放/收藏里上下。
     * 左右键不受影响（走下面不带 colMode 的分支），边界处仍可跨列。
     */
    var isVertical = dir === 'up' || dir === 'down'
    var el
    var curCol = isVertical ? colOf(currentFocus()) : ''
    if (curCol) {
      el = find(dir, false, 'same')
      // 同列没有候选 → 允许离开这一列（返回/顶栏），但不回另一列
      if (!el) el = find(dir, true, 'loose')
    } else {
      el = find(dir, false)
      /*
       * 这个方向上没有普通内容了 → 再带上钉住层（顶栏）扫一次。
       *
       * ★ 只对上下键开放（2026-11-02，用户报「第一行海报右键偶尔跳到上方」）。
       *
       * 横向走到一行最右边时，右侧确实再没有内容卡片了；此时带上钉住层扫，
       * 会命中右上角的「设置」—— 用户按右键本意是"这一行还没走完/到头了"，
       * 焦点却飞到顶栏，得按好几次下键才回得来。
       * 横向的语义是"换列"，不该跨出内容区；要上顶栏请按上键。
       *
       * 上下键保留这条兜底：页面滚到顶时按上键，用户的意图就是要离开内容区
       * （顶栏是唯一能去的地方，见上面 scrollPageUp 那段注释）。
       */
      if (!el && (dir === 'up' || dir === 'down')) el = find(dir, true)
    }
    // 找不到目标也算"用户动过了"：他按了键、但当前已在边界，
    // 不该让 1.2s 后的兜底把他的焦点抢回内容区第一个。
    userMoved = true
    if (!el) return false
    highlight(el)
    return true
  }

  function enter() {
    userMoved = true
    var all = items()
    /*
     * ★ 起点同样不能用 `all[current]`。
     *   current 是**上一次** items() 的索引；列表一变（弹层打开、异步加载）
     *   就指到别的元素上，按 ok 会点错东西（实测：面板里点到了背景按钮）。
     *   与 find() 用同一套判据：只认画着焦点框的那个。
     */
    var el = currentFocus()
    if (!el || all.indexOf(el) < 0) {
      var ae = document.activeElement
      el = ae && ae !== document.body && all.indexOf(ae) >= 0 ? ae : null
    }
    if (!el) {
      el = all[0]
      if (!el) return false
      highlight(el)
      return true
    }
    /*
     * ★ 文本输入框：借原生对话框打字，不能只 click()。
     *
     * 踩过的坑：click() 对 input 只是"聚焦"，而 WebView 只对**真实触摸**
     * 弹输入法。遥控器按 ok → 框选中了、按键毫无反应，全站 45 个文本框
     * 在电视上全部不可用（搜索页、片库添加、WebDAV、后端地址…）。
     *
     * 判据用 input 的 type：range/checkbox/radio/button 这些"不是打字的"
     * 保持原 click() 行为（它们本来就是按一下变状态）。
     *
     * ★ 用原生框的前提是"页面上的框会被输入法挡住"。位置在视口上方
     *   （搜索页/顶栏）时输入法在底部弹出，根本挡不住——这时弹原生框
     *   纯属多此一举：多一次确认、还丢了页面上的搜索建议与实时筛选。
     *   所以按几何判断：框底部落在视口上 45% 以内就直接聚焦、让系统
     *   输入法接管（WebView 对遥控器 OK 不弹输入法，但 focus() 之后
     *   用户可以用语音输入或外接键盘；至少不会平白多一个对话框）。
     *
     *   ★ 判据不能用"页面写个 data 属性让模板声明"——模板有 45 处输入框，
     *     逐个标必然漏。几何判断只有一条，且与"会不会被遮"直接对应。
     */
    if (el.tagName === 'INPUT' && !el.hasAttribute('data-tv-input-owner')) {
      var t = (el.getAttribute('type') || 'text').toLowerCase()
      var isTextual = t === 'text' || t === 'search' || t === 'password'
        || t === 'url' || t === 'email' || t === 'number' || t === 'tel'
      if (isTextual && window.meowTv && typeof window.meowTv.inputText === 'function'
          && needsNativeInput(el)) {
        var title = el.getAttribute('placeholder') || el.getAttribute('aria-label') || '输入'
        var mask = t === 'password'
        var v = window.meowTv.inputText(title, el.value || '', mask)
        if (typeof v === 'string' && v !== (el.value || '')) {
          el.value = v
          // Vue 的 v-model 靠 input 事件同步，直接改 value 不触发响应式
          el.dispatchEvent(new Event('input', { bubbles: true }))
          el.dispatchEvent(new Event('change', { bubbles: true }))
        }
        return true
      }
      /*
       * 上方输入框：不弹原生框。
       *
       * 输入法是从屏幕底部推上来的，页面靠上的输入框（搜索页/顶栏）
       * 根本不会被遮住，弹框只是白多一步确认。这里直接聚焦 + 让原生
       * 补一次 showSoftInput —— WebView 只对**真实触摸**弹输入法，
       * 遥控器 OK 不会，所以必须由壳补这一下，否则用户面对一个
       * 有焦点框但打不了字的输入框。
       */
      if (isTextual) {
        try { el.focus() } catch (e) { /* 不可聚焦就算了 */ }
        /*
         * data-tv-ime：给原生 showIme() 找目标用。
         * 只传 focus 不够——WebView 的 View 级焦点与页面 activeElement
         * 是两层，原生那边要先让 WebView 拿焦点、再按标记重新 focus，
         * 否则会出现"输入法弹出来了，但打字进不了框"。
         */
        try {
          var prevIme = document.querySelector('[data-tv-ime]')
          if (prevIme && prevIme !== el) prevIme.removeAttribute('data-tv-ime')
          el.setAttribute('data-tv-ime', '1')
        } catch (e2) { /* 属性设置失败不影响后续 */ }
        if (window.meowTv && typeof window.meowTv.showIme === 'function') {
          window.meowTv.showIme()
        }
        return true
      }
    }
    if (el.tagName === 'TEXTAREA' && window.meowTv
        && typeof window.meowTv.inputText === 'function'
        && needsNativeInput(el)) {
      var v2 = window.meowTv.inputText(el.getAttribute('placeholder') || '输入', el.value || '', false)
      if (typeof v2 === 'string' && v2 !== (el.value || '')) {
        el.value = v2
        el.dispatchEvent(new Event('input', { bubbles: true }))
        el.dispatchEvent(new Event('change', { bubbles: true }))
      }
      return true
    }
    /*
     * ★ 按下去的这个元素很可能是"打开弹层的那个"——先记下来。
     *
     * 为什么必须在这里记，而不是等弹层出现时在 activeScope() 里取：
     * 踩过（MuMu 实测 TileSelect）。Vue 的一次 patch 里可能同时插入菜单
     * 节点、又把焦点搬到菜单第一项，MutationObserver 批量回调到达时
     * `.meow-tv-focus` 已经在弹层内 —— 那时再取 opener 永远取不到，
     * 关闭后无处可还（实测 __meowTvScopeClosed 返回 false、焦点彻底消失）。
     * 打开前这一刻是唯一可靠的取样点。
     *
     * 只在"按完没打开弹层"时它才会过期，所以 activeScope 里仍保留
     * 一次兜底覆盖（弹层由路由/异步打开的路径，不经过 enter）。
     */
    enterOpener = el
    el.click()
    return true
  }

  /**
   * 这个输入框会不会被软键盘遮住？
   *
   * 判据是几何位置：输入法从底部推起，约占视口下 40%。输入框**顶部**
   * 落在视口上 55% 以内，就视为"不会被遮"——不用原生框。
   *
   * 为什么用顶部而不是底部：AI 面板的输入框贴在屏幕最下方（底栏），
   * 它的顶部也接近底部，两条判据都成立；但一个跨中线的宽输入框
   * （顶部在上半、底部在下半）其实还是会被遮，所以取**保守**的那个：
   * 只要底部越过中线就算"可能被遮"，用原生框。
   *
   * 例外：`data-tv-native-input` 强制走原生框（页面明确要求时）。
   */
  function needsNativeInput(el) {
    if (el.hasAttribute('data-tv-native-input')) return true
    var r = el.getBoundingClientRect()
    var vh = window.innerHeight || document.documentElement.clientHeight || 0
    if (!vh) return true
    // 中线以下 = 可能被输入法遮住
    return r.bottom > vh * 0.5
  }

  /**
   * 确认键的最小间隔（毫秒）。
   *
   * 遥控器按住 OK 不放会连发 ACTION_DOWN（repeatCount 递增），壳侧原样转成
   * 多次 `__meowTvNav('enter')`。实测连调 8 次 → click 8 次，**按住 = 连点**。
   *
   * 后果按页面不同，最后一条最危险：
   *   详情页   → 重复 push 路由，连开多个详情页
   *   播放页   → 连切多集，直接跳到后面几集
   *   收藏页   → 第一次移除后列表收缩，后续 click 落到**下一个**条目 → 连删多部
   *
   * 250ms 的依据：人按住键的重复间隔在 30~50ms，250ms 足够吃掉连发；
   * 而正常"点两下"的间隔通常 >300ms，不会被误吞。
   * **只对 enter 生效** —— 方向键连按是正常操作，加节流反而卡顿。
   */
  var ENTER_MIN_GAP = 250
  var lastEnterAt = 0

  /**
   * 把焦点框移到指定元素（或某个容器里的第一个可聚焦元素）。
   *
   * <p><b>为什么需要它</b>：有些页面希望"按键时把焦点送回某个固定区域"，
   * 例如设置页在两栏布局下，用户在右栏表单里按返回/菜单键，期望回到
   * 左导航第一项（而不是退出页面或什么都不发生）。这种"回固定锚点"的
   * 语义用方向键几何算不出来——几何上左栏就在左边，但用户想要的是
   * **无条件跳回**。
   *
   * <p>参数：选择器字符串（`'[data-tv-anchor]'` 之类）或元素本身。
   * 第二个参数 `pin`：传 true 表示"这是页面明确指定的落点，不要再被人抢"。
   * 会把 `userMoved` 置位，从而挡住 `initialFocus()`（60ms/1200ms 那两次）
   * 和 observer 的兜底纠正 —— 见下面的踩坑说明。
   *
   * <p>找不到 / 不可见就返回 false，让调用方自己决定下一步。
   */
  window.__meowTvFocus = function (target, pin) {
    // 旧闭包的实现不该再被调用（新闭包已接管同一个 window 属性，
    // 这里是防御：某些页面可能缓存了旧引用）
    if (disposed) return false
    var el = typeof target === 'string' ? document.querySelector(target) : target
    if (!el) return false
    if (!(el instanceof HTMLElement)) return false
    if (!visible(el) || !usable(el)) {
      // 传进来的是容器：取它内部第一个可用的
      var inner = Array.prototype.slice
        .call(el.querySelectorAll(FOCUSABLE))
        .filter(visible)
        .filter(usable)
      if (!inner.length) return false
      el = inner[0]
    }
    highlight(el)
    /*
     * ★ `pin` 的用处（2026-10-30 实测「片库页返回没聚焦到原位置」）。
     *
     * `useReturnRestore` 在挂载后恢复焦点，而 tv-nav 的 `initialFocus()`
     * 会在 60ms / 1200ms 各跑一次，把焦点搬去"内容区第一项"——
     * 恢复刚做完就被覆盖，用户看到的还是页签/第一张卡片。
     * `initialFocus` 的守卫是 `if (userMoved) return`，所以这里置位即可。
     *
     * 不用无条件置位：设置页那种"按返回回左导航"本身就是用户动作，
     * 之后他继续按键是自然的；而恢复焦点若不置位就会被静默覆盖。
     *
     * ★ 光置 `userMoved` 还不够：`onRouteChange()` 会在路由变化时把它清零
     *   （pushState 被 Vue 推到 DOM 更新之后，正好晚于本函数），
     *   随后 `initialFocus()` 就把焦点搬走了。所以再记一个 1.5 秒的窗口。
     *   1.5s 的依据：实测恢复链最长约 2s（每 120ms 重试、最多 17 次），
     *   但首帧成功后就不该再被人改；留 1.5s 足够覆盖 `initialFocus`
     *   的 120ms / 700ms 两次和 observer 的 300ms 兜底，
     *   又不至于让用户在 2 秒后按方向键时还被"钉住"影响。
     */
    if (pin) {
      userMoved = true
      pinUntil = Date.now() + 1500
    }
    return true
  }

  /**
   * 弹层关闭后立刻把焦点还给打开它的元素。
   *
   * <p><b>为什么需要这个同步入口</b>：`activeScope()` 里已经有同样的逻辑
   * （scope 全部消失 → 还焦点），但它只在 MutationObserver 回调或下一次
   * 按键时才跑。用户按返回键关下拉时，焦点会先"悬空"一会儿——画面上
   * 焦点框还在已消失的元素上（或直接没了），下次按键才开始找。
   * 组件知道"我这一刻关了"，直接调它最干脆。
   *
   * <p>返回 true 表示找到了归还目标并已落焦。
   */
  window.__meowTvScopeClosed = function (fallbackSelector) {
    if (disposed) return false
    var back = scopeReturnEl
    scopeReturnEl = null
    lastScopeEl = null
    /*
     * ★ 调用方给的稳定锚点**优先于**自动记录的打开者。
     *
     * 踩过（2026-10-30 站点明细弹框，逐帧追踪确认）：
     * 关闭瞬间焦点在 t=20ms 还是「重探失败源」（弹框内），
     * t=40ms 直接变成左导航「01 连接」——**从未经过「站点明细」按钮**，
     * 而 `__meowTvScopeClosed` 明明返回了 true。
     *
     * 原因：`scopeReturnEl` 里存着一个**不可用的元素**。弹框是用鼠标
     * （或脚本 click）打开的，没走 `enter()`，`enterOpener` 为空；
     * 而 `activeScope()` 的第三级兜底抓到了 `document.activeElement`，
     * 那多半是别的什么东西（甚至是已被卸载的节点）。
     * 旧代码只在 `!back` 时才用 fallback —— 于是拿到那个坏元素就直接返回，
     * 兜底永远轮不到。
     *
     * 现在改成：只要调用方给了选择器，就以它为准（调用方最清楚该还到哪）。
     * 自动记录只作为没给选择器时的默认行为保留。
     */
    if (fallbackSelector) {
      var fb = document.querySelector(fallbackSelector)
      if (fb) back = fb
    }
    if (!back) return false
    if (!document.contains(back)) return false
    /*
     * ★ 延后一拍还焦点。
     *
     * 踩过（MuMu 实测）：组件在 `watch(open)` 里同步调本函数，那一刻
     * scope 节点可能还没从 DOM 里摘掉（Vue 的 v-if 更新是异步的）。
     * 此时 highlight(back) 会把焦点画到 back 上，但紧接着 MutationObserver
     * 又看到"还有一个 scope"，把焦点搬回弹层第一项 —— 用户看到的是
     * "关了下拉，焦点却留在原地没动"。
     *
     * 两拍足够让 Vue 的 patch 落地：第一拍等 DOM 更新，第二拍再落焦。
     */
    var settle = function (attempt) {
      if (!document.contains(back)) return
      // 弹层又开了（用户连着按了 OK）→ 让它自己抢焦点，别覆盖
      if (document.querySelectorAll('[data-tv-scope]').length) return
      if (!visible(back) || !usable(back)) return
      highlight(back)
      /*
       * ★ 必须钉住，否则刚还回去的焦点又被抢走。
       *
       * 踩过（2026-10-30 站点明细弹框，抓桩确认）：
       * `__meowTvScopeClosed` 被调用、传了正确的兜底选择器、返回 true
       * （说明 highlight 执行了），但最终焦点仍落在左导航「01 连接」。
       * 因为弹层消失引发的 DOM 变动会让 observer 的 300ms 兜底和
       * `onRouteChange` 派生的 `initialFocus()` 再跑一次，
       * 而那次 `highlight` 没有 pin，`userMoved` 又已被前面的返回键处理清过。
       *
       * 弹层关闭是"用户明确结束了一个上下文"，落点该由它还回去的为准 ——
       * 与 `__meowTvFocus(el, true)` 同一种语义，直接置同样的窗口。
       */
      userMoved = true
      pinUntil = Date.now() + 1500
      if (attempt === 0 && document.querySelector('.meow-tv-focus') !== back) {
        window.setTimeout(function () { settle(1) }, 60)
      }
    }
    window.setTimeout(function () { settle(0) }, 0)
    return true
  }

  /**
   * 诊断用：把内部状态导出来（只在排查时读，不参与逻辑）。
   *
   * 为什么需要：`scopeReturnEl` 这类"记住上一刻"的变量没有 DOM 表现，
   * 猜原因（比如"是不是 opener 取不到"）会连着猜错好几轮。直接读出来
   * 一眼就能分辨是"没记上"还是"记上了但归还失败"。
   */
  window.__meowTvDebugState = function () {
    var f = document.querySelector('.meow-tv-focus')
    return {
      hasLastScope: !!lastScopeEl,
      hasReturnEl: !!scopeReturnEl,
      returnElText: scopeReturnEl
        ? (scopeReturnEl.innerText || scopeReturnEl.tagName || '').replace(/\n/g, '|').slice(0, 24)
        : null,
      focusText: f ? (f.innerText || f.tagName || '').replace(/\n/g, '|').slice(0, 24) : null,
      scopeCount: document.querySelectorAll('[data-tv-scope]').length,
      userMoved: userMoved,
    }
  }

  window.__meowTvNav = function (action) {
    if (disposed) return false
    /*
     * 遥控/键盘操作 = key 模式。
     *
     * ★ 这条路是壳原生直接调 JS，**不会产生 DOM keydown**，所以
     *   compat/inputModality.ts 的 keydown 监听收不到；而 MuMu 这类
     *   模拟器 maxTouchPoints > 0，首屏会被判成 touch，焦点环被 CSS 关掉。
     *   实测症状：方向键能移焦（逻辑在跑）但屏幕上看不到框，用户报
     *   "无法聚焦"。每次按键显式切回 key，触摸时 inputModality 会再切 touch。
     */
    try {
      /*
       * 只有横屏才切 key：竖屏设备（手机/竖放的模拟器）用户明确要求
       * 不显示焦点环。竖屏下即使是遥控/键盘也保持 touch。
       */
      if (window.innerWidth >= window.innerHeight) {
        document.documentElement.setAttribute('data-input', 'key')
      }
    } catch (e) {
      /* 老内核/文档未就绪：忽略，不影响按键逻辑 */
    }
    // 记录按键时刻：MutationObserver 的焦点纠正会看它做宽限期判断
    lastKeyAt = Date.now()
    /*
     * 每次按键先清掉上一次的"打开者"。
     *
     * 否则一次没打开弹层的 OK（比如点到普通链接）会把那个元素一直留着，
     * 用户下次用别的路径打开某个弹层时，关闭后会跳回上上步的位置。
     * enter() 内部在 click 前会重新赋值。
     */
    enterOpener = null
    if (action === 'enter') {
      var now = Date.now()
      if (now - lastEnterAt < ENTER_MIN_GAP) return true
      lastEnterAt = now
    }
    // 页面可以"独占方向键"：播放页的方向键要做快进/音量，不是移焦点。
    // 没有这个开关时，壳会把方向键全部吃掉，播放器里按左右只会移动焦点、
    // 视频一动不动（而且焦点可能落到某个按钮上，再按确认就误触了）。
    var owner = window.__meowTvKeyOwner
    if (owner) {
      /*
       * ★ enter 不能只派发 keydown。
       *
       * 踩过的坑：下面这段原来对 enter 也走 dispatchEvent(new KeyboardEvent('keydown'…))。
       * keydown 只会让**表单内的提交按钮**触发，普通 <button> 收到 keydown 毫无反应
       * （实测：控制栏里焦点明明在「选集」上、有 3px 描边，按 ok 面板不开）。
       *
       * 所以 enter 单独处理：焦点在可点击元素上就直接 click()；没有焦点框时
       * 才退回派发 Enter（全屏时播放器靠它切暂停/播放）。
       */
      if (action === 'enter') {
        var fb = markedEl()
        var ae0 = document.activeElement
        var tgt = fb || ae0
        if (tgt && tgt !== document.body && typeof tgt.click === 'function') {
          tgt.click()
          return true
        }
        var evE = new KeyboardEvent('keydown', {
          key: 'Enter', code: 'Enter', bubbles: true, cancelable: true,
        })
        ;(ae0 || document.body).dispatchEvent(evE)
        return true
      }
      var key =
        action === 'up' ? 'ArrowUp' :
        action === 'down' ? 'ArrowDown' :
        action === 'left' ? 'ArrowLeft' : 'ArrowRight'
      /*
       * ★ 页面上已经画出焦点框时，方向键直接用来移焦点，不再派发给播放器。
       *
       * 踩过的坑：原来无条件 dispatchEvent，而播放器的 ArrowLeft/Right
       * 分支里第一句就是 e.preventDefault()——它一旦收到就必然"吃掉"事件，
       * 于是这里 `!notCanceled` 成立、直接 return true，move() 永远不执行。
       * 表现：控制栏里焦点走到第三个按钮就再也动不了（实测 right3 → NONE）。
       *
       * 判据用"有没有一个**看得见**的 .meow-tv-focus"（就是用户眼睛看到的
       * 焦点框），比 showCtrl 之类的页面内部状态可靠：那是页面自己的 ref，
       * 壳读不到。★ 必须用 markedEl() 而不是裸 querySelector ——
       * 控制栏收起后 class 会短暂残留在隐形按钮上，见 markedEl 的注释。
       */
      if (!markedEl()) {
        var ev = new KeyboardEvent('keydown', {
          key: key,
          code: key,
          bubbles: true,
          cancelable: true,
        })
        var target = document.activeElement || document.body
        var notCanceled = target.dispatchEvent(ev)
        // 播放器处理了（preventDefault）就不再移动焦点。
        // 全屏播放时焦点框跟着画面边缘乱跳很干扰，也是"按了右键却像在选东西"的错觉来源。
        if (!notCanceled) return true
      }
      // 没有焦点框（纯看片）→ 上面已交给播放器；
      // 有焦点框 or 播放器没接手 → 回落到焦点移动
    }
    if (action === 'enter') return enter()
    return move(action)
  }

  // 页面内容变化（路由切换/异步列表）后原焦点可能已消失，重扫一次
  var timer
  var observer = new MutationObserver(function () {
    if (disposed) return
    /*
     * ★ 弹层开合必须抢在宽限期判断之前处理。
     *
     * 踩过（用户反馈「首页源下拉，点击后没有聚焦到当前源」）：
     * 下面那句 `if (now - lastKeyAt < 1200) return` 是为了不让播放页每秒
     * 刷新的进度文本打断用户手上的焦点。但"按 OK 打开下拉框"这个动作
     * **本身就是一次按键**——打开瞬间一定在宽限期内，于是这里直接 return，
     * `activeScope()` 根本没被调用，焦点框留在触发按钮上。
     * 用户看到的是"下拉弹出来了，但框还在『选择站点』上，不知道选哪儿"。
     *
     * 所以把 activeScope() 提到宽限期判断之前：它自己带"顶层 scope 变了才抢"
     * 的判据，弹层没变时是纯读操作，不会打断任何东西。
     */
    var scopeNow = activeScope()
    clearTimeout(timer)
    timer = setTimeout(function () {
      /*
       * ★ 刚按过键的宽限期内，不因为 DOM 变动去动焦点。
       *
       * 踩过的坑：播放页每秒都在更新进度文本（timeupdate → DOM 变动），
       * MutationObserver 每次都重扫，而 `current` 是**索引**——底栏按钮
       * 因为文案变化（「1x」→「1.5x」）导致 `all` 顺序/长度抖动时，
       * `current >= all.length` 或 `visible(all[current])` 会误判，
       * 于是 `highlight(null)` 把焦点框摘掉。
       * 表现就是"在底栏按两下右键，焦点就没了"（实测 right1→right2→NONE）。
       *
       * 宽限期 1.2s：用户正在按键时就该以他的操作为准；
       * 停手之后才开始做"焦点是否还有效"的纠正。
       */
      /*
       * ★ 先补焦点标记 —— 这一步**不移动焦点**，所以不受下面宽限期限制。
       *
       * Vue 的 `:class` 更新会重写整个 className，把 `meow-tv-focus` 抹掉
       * （见 currentFocus 的注释）。不补的话用户会看到"按完 OK 焦点框消失"，
       * 直到下一次按键才回来（实测 DNS 测试按完就是这样）。
       * 只在"当前完全没有焦点框 + lastFocused 仍在原位且可见"时补，
       * 不会跟主动清焦点（滚出视口/路由切换）打架。
       */
      if (!document.querySelector('.meow-tv-focus') && lastFocused
          && document.contains(lastFocused) && visible(lastFocused)
          && usable(lastFocused) && inViewport(lastFocused)) {
        lastFocused.classList.add('meow-tv-focus')
      }
      var now = Date.now()
      if (now - lastKeyAt < 1200) return
      var all = items()
      /*
       * ★ 焦点框还在 DOM 里、也还可见，就说明它有效 —— 不要因为
       *   `current` 索引错位就把它清掉。
       *
       * 踩过的坑：`current` 是 items() 的索引，而底栏按钮的文案会变
       * （「1x」→「1.5x」、「关闭弹幕」→「开启弹幕」），items 顺序/长度
       * 一变，`visible(all[current])` 就指到了别的元素（或越界），
       * 于是 highlight(null) 把正在用的焦点框摘掉。
       * 表现：在底栏连按右键，按两下焦点就没了（实测 right1→right2→NONE）。
       *
       * 判据用"元素自身是否仍是可见的可聚焦元素"，与索引无关。
       */
      var okMarked = currentFocus()
      /*
       * 焦点框「仍然有效」的判据要含视口：元素滚出屏幕后那道框会飘着
       * 或彻底看不见，但按方向键仍从屏幕外坐标算几何 —— 用户看到的是
       * "有个不该存在的框" + "方向键乱跳"。这里清掉它，让下一次按键
       * 走 initialFocus / 兜底重新落位。
       */
      if (!(okMarked && visible(okMarked) && inViewport(okMarked))
          && (current >= all.length || (current >= 0 && !visible(all[current])))) {
        current = -1
        highlight(null)
      }
      /*
       * ★ 弹层开着而焦点框不在里面 → 搬进去。
       *
       * 踩过：弹层打开后焦点框还停在背景的「置顶」上，而新范围（弹层内 6 个）
       * 里没有它——用户按方向键看似"毫无反应"（其实是在对旧元素算方向）。
       *
       * ★ 落点必须走 focusIntoScope，不能自己挑 `all[0]`。
       *   原来这里写的是 `firstContentItem(all) || all[0]`，那套优先级是
       *   **页面级**落点（优先内容区），对弹层完全不适用：它会把
       *   `data-tv-first` / 当前值全跳过，落到弹层 DOM 第一个元素上。
       *   用户要求的"下拉框打开聚焦当前值"就是这么丢的。
       */
      var scope = scopeNow
      if (scope) {
        var marked = currentFocus()
        if (!marked || !scope.contains(marked)) {
          focusIntoScope(scope)
          return
        }
      }
      // 焦点框仍有效：只校正索引（供 enter() 用），不做任何"纠正"——
      // 用户手上的焦点不该被 DOM 变动（进度文本每秒刷新）打断。
      var keep = currentFocus()
      if (keep && visible(keep)) {
        current = all.indexOf(keep)
        return
      }
      // ★ 焦点落点纠正：没有焦点 → 落到内容区第一个；焦点在顶栏但用户
      //   还没动过 → 也挪到内容区。
      //   电视上唯一能动的输入是遥控器，"没有焦点框"时用户完全看不出选到
      //   哪里（首页首次进入、路由切换、列表异步刷新后都会出现）。
      //   为什么优先内容区而不是 DOM 第一个：DOM 第一个永远是顶栏 logo/豆瓣，
      //   它和海报墙视觉平级，用户盯着海报区找不到框 —— 实测反馈就是
      //   "不知道选到哪里"。只在 userMoved=false 时纠正，用户一旦按过键
      //   就不再抢他的焦点。
      //
      //   ★ `pinUntil` 同样要判（见 initialFocus 的注释）：恢复焦点后
      //     这段兜底也会把落点改成内容区第一项，用户看到的就是"先落到
      //     原按钮又瞬移到页签"。
      if (!userMoved && !(pinUntil && Date.now() < pinUntil) && all.length) {
        var want = preferredItem(all) || firstContentItem(all) || all[0]
        if (currentFocus() !== want) highlight(want)
      }
    }, 300)
  })
  observer.observe(document.documentElement, { childList: true, subtree: true })

  /*
   * 卸载入口：重复注入（尤其 WebView reload 之后）时由脚本头部调用。
   *
   * 只做"断开旁观者"这一件事：observer 和 DOM 事件监听。window 上的
   * `__meowTvNav` / `__meowTvFocus` / `__meowTvDebugState` 等会被新闭包
   * 直接覆盖（赋值而非追加），所以不用逐个 delete —— 反倒要留着，
   * 避免新闭包装到一半时壳侧拿到 undefined。
   */
  window.__meowTvDispose = function () {
    disposed = true
    try { observer.disconnect() } catch (e) {}
    try { if (scopeIdleTimer) clearTimeout(scopeIdleTimer) } catch (e) {}
    try { if (timer) clearTimeout(timer) } catch (e) {}
    lastScopeEl = null
    scopeReturnEl = null
    enterOpener = null
    // 旧文档上画的焦点框 class 不会自己消失（reload 时整棵 DOM 已换，无所谓；
    // 同一文档重复注入时则可能残留两份）
    try {
      var stale = document.querySelectorAll('.meow-tv-focus')
      for (var i = 0; i < stale.length; i++) stale[i].classList.remove('meow-tv-focus')
    } catch (e) {}
  }

  function installStyle() {
    // ★ document.head 在 onPageFinished 刚触发时可能还没解析出来
    //   （readyState 仍是 'interactive'），这时 appendChild 会静默失败，
    //   结果是"焦点逻辑正常但看不见框"——正是用户报的症状。
    var host = document.head || document.documentElement
    if (!host || document.getElementById('meow-tv-focus-style')) return
    var style = document.createElement('style')
    style.id = 'meow-tv-focus-style'
    style.textContent = FOCUS_CSS
    host.appendChild(style)
  }
  installStyle()

  // 首次进入：立刻给第一个可聚焦元素焦点，不让用户面对"没有任何选中框"的界面。
  // 延后一拍等 Vue 把首屏渲染出来；再兜一次 1.2s（多数页面第二轮才出列表）。
  //
  // ★ 优先落在内容区，而不是顶栏。
  //   按 DOM 顺序取第一个可聚焦元素时，命中的永远是顶栏的「豆瓣」按钮——
  //   它在视觉上和下面的海报墙平级，用户盯着海报区找焦点框，看不到，
  //   就变成"不知道选到哪里了"（这正是实测里收到的反馈）。
  //   所以先找内容容器里的元素（main / .poster-grid / 表单），找不到再退回第一个。
  function firstContentItem(all) {
    var host =
      document.querySelector('main') ||
      document.querySelector('.poster-grid') ||
      document.querySelector('[data-sec]')
    if (!host) return null
    for (var i = 0; i < all.length; i++) {
      if (host.contains(all[i])) return all[i]
    }
    return null
  }

  /**
   * 找到页面右侧/主内容区的纵向滚动容器。
   *
   * `activeScroller()` 只看“当前滚动量最大的”，页面在顶部时返回 window；
   * 而顶栏 ↓ 需要的正是“右栏那个容器”（设置页左导航 + 右栏两栏布局）。
   * 所以这里独立按“可滚跨度最大”找，不依赖当前 scrollTop。
   */
  function contentScrollHost() {
    var best = null
    var bestSpan = 0
    var all = document.querySelectorAll('*')
    for (var i = 0; i < all.length; i++) {
      var e = all[i]
      var oy = window.getComputedStyle(e).overflowY
      if (oy !== 'auto' && oy !== 'scroll') continue
      if (e.clientHeight < 200) continue
      var span = e.scrollHeight - e.clientHeight
      if (span > bestSpan) {
        best = e
        bestSpan = span
      }
    }
    return best
  }

  /**
   * 主内容滚动区里的第一个可聚焦元素（按 items() 的 DOM 顺序）。
   *
   * 顶栏 ↓ 用它回内容区第一行；没有元素滚动容器（首页/详情/片库是
   * window 滚动）时返回 null，让 move() 继续走原来的几何 find。
   */
  function firstContentInScrollHost(all) {
    var host = contentScrollHost()
    if (!host) return null
    for (var i = 0; i < all.length; i++) {
      if (host.contains(all[i])) return all[i]
    }
    return null
  }

  /**
   * 页面可以显式指定"焦点首选落点"。
   *
   * 为什么需要：`firstContentItem` 取的是 DOM 顺序第一个可聚焦元素，而这
   * 在多数页面恰好不是用户想按的那个——详情页第一个是「返回」，AI 页第一个
   * 是消息滚动区，而用户进来就是要「播放」/「输入」。
   * 标 `data-tv-first` 的元素优先于几何/DOM 顺序（播放页进片、AI 页进输入框）。
   */
  function preferredItem(all) {
    var want = document.querySelector('[data-tv-first]')
    if (want && all.indexOf(want) >= 0 && visible(want)) return want
    return null
  }

  function initialFocus() {
    // 旧闭包的重置：见 disposed 的注释（它会把新闭包刚恢复的焦点改掉）
    if (disposed) return
    installStyle()
    /*
     * ★ 页面显式指定的落点（`pin`）在短时间内拥有最高优先级。
     *
     * 踩过（2026-10-30 实测「片库添加页返回，聚焦一瞬先到『添加片库』
     * 又跳到『本地片库』」——用户原话：「似乎有一瞬是从添加片库 聚焦到
     * 本地片库去了」）：`useReturnRestore` 在挂载后把焦点恢复到「添加片库」，
     * 但紧接着 Vue Router 的 pushState 触发 `onRouteChange()`，
     * 那里有 `userMoved = false`；随后本函数跑起来，`userMoved` 已经被重置，
     * 守卫失效 → `firstContentItem()` 把焦点搬去「本地片库」。
     * 抓栈确认：`highlight ← initialFocus (:1403)`，t=2338 落焦、t=2428 被覆盖。
     *
     * 所以 `pin` 不能只置 `userMoved`（会被 onRouteChange 清零），
     * 还要记时间戳；窗口期内本函数直接让路。
     */
    if (pinUntil && Date.now() < pinUntil) return
    if (userMoved) return
    var all = items()
    if (!all.length) return
    var el = preferredItem(all) || firstContentItem(all) || all[0]
    // 已经在内容区就不动它（避免每次 Mutation 都重置）
    if (currentFocus() === el) return
    highlight(el)
  }

  /*
   * ★ 路由切换时重新允许自动落焦点，并立刻落一次。
   *
   * 踩过：`userMoved` 一旦为 true 就永久不再自动落焦点（这是为了不打断
   * 用户手上的操作）。但**换页面**是个全新的上下文——旧的卡片已经不在
   * DOM 里，焦点框也跟着消失。这时还坚持"用户动过就不抢"，结果就是
   * 每进一个新页面（AI / 历史 / 详情）屏幕上都没有选中框，用户不知道
   * 自己选到了哪里，必须盲按一次方向键才出现。
   *
   * ★★ 只监听 hashchange 是不够的（这是一个很容易被测试方式掩盖的坑）。
   *
   *   Vue Router 用的是 `history.pushState`，**它不触发 hashchange**
   *   （也不触发 popstate，那个只给前进/后退）。所以只在 hashchange 上
   *   挂重置的话：手动改地址栏、浏览器后退能生效，而用户真实会用的
   *   「点顶栏 / 点卡片跳页」完全不生效。
   *
   *   更麻烦的是它验证不出来：拿 `location.hash = '#/ai'` 去测正好走了
   *   hashchange，通过；真实点击走 pushState，不通过。所以这里把
   *   pushState / replaceState 一起包住——这是 SPA 里唯一可靠的做法。
   *
   * ★ 只在 URL 真的变了才重置：Vue Router 会为滚动位置等反复调
   *   replaceState，同 URL 也重置的话会一直把用户的焦点抢回第一个。
   */
  var lastHref = location.href

  function onRouteChange() {
    if (location.href === lastHref) return
    lastHref = location.href
    /*
     * ★ 路由变化时重置 `userMoved`，但**不要**踩掉刚钉住的落点。
     *
     * 踩过（2026-10-30，抓栈定位）：`useReturnRestore` 在挂载后把焦点恢复到
     * 原按钮（`__meowTvFocus(el, true)`），紧接着 Vue 的 pushState 才落到
     * here —— 这里 `userMoved = false` 把它刚设的标记清零，随后
     * `initialFocus()` 就把焦点搬去内容区第一项。
     * 用户看到：「一瞬是从添加片库 聚焦到 本地片库去了」（本人原话）。
     *
     * `pinUntil` 是时间窗，不受这里重置影响；窗口内不置位即可。
     */
    if (!(pinUntil && Date.now() < pinUntil)) userMoved = false
    lastKeyAt = 0
    // 等新页面渲染完再落焦点；一拍不够（懒加载路由 + 异步数据）
    setTimeout(initialFocus, 120)
    setTimeout(initialFocus, 700)
  }

  if (history.pushState) {
    var rawPush = history.pushState
    history.pushState = function () {
      var ret = rawPush.apply(this, arguments)
      onRouteChange()
      return ret
    }
  }
  if (history.replaceState) {
    var rawReplace = history.replaceState
    history.replaceState = function () {
      var ret = rawReplace.apply(this, arguments)
      onRouteChange()
      return ret
    }
  }
  // 前进/后退与手动改 hash 走这两条
  window.addEventListener('popstate', onRouteChange)
  window.addEventListener('hashchange', onRouteChange)
  var onLoad = function () { setTimeout(initialFocus, 80) }
  window.addEventListener('load', onLoad)

  // dispose 时把上面两条全局监听摘掉：同一文档重复注入会叠加多份 onRouteChange，
  // 每份都拿自己闭包里早已过期的 DOM 引用去跑（必乱）。
  var baseDispose = window.__meowTvDispose
  window.__meowTvDispose = function () {
    try { window.removeEventListener('popstate', onRouteChange) } catch (e) {}
    try { window.removeEventListener('hashchange', onRouteChange) } catch (e) {}
    try { window.removeEventListener('load', onLoad) } catch (e) {}
    if (typeof baseDispose === 'function') baseDispose()
  }

  setTimeout(initialFocus, 60)
  setTimeout(initialFocus, 1200)
})()
