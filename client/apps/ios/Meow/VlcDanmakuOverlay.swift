import QuartzCore
import UIKit

/**
 * 轻量弹幕层（CALayer/UILabel 自绘，零依赖）——iOS 版对应 TV 的 `DanmakuOverlay`。
 *
 * <p>为什么不让 web 那边画：原生播放器全屏接管时弹幕只能由原生层渲染；
 * 控制层那个透明 WKWebView 上的 canvas 会和 VLC 画面抢合成（真机实测掉帧）。
 * 参数与语义**逐项对齐** TV 的实现，避免两端"同一条弹幕一个 12 秒一个 8 秒"。
 *
 * <p>与播放器的关系：只读播放位置，不控制播放。位置跳变 > 2.5 秒视为 seek，
 * 清空在场弹幕重新开始，避免"拖完进度弹幕一次性糊满屏"。
 *
 * <p><b>平滑方式（2026-11-27 重写）</b>：播放器仍每 0.25 秒推一次位置，但那只是
 * **校准锚点**；真正的位移由本层自己的 `CADisplayLink` 以 60fps 按「锚点 + 单调时钟
 * 增量」插值算出。旧实现把每条弹幕做成 0.26 秒 `UIView.animate`，一帧要重建几十个
 * 动画、还要带 layer shadow，真机表现就是"一顿一顿"。
 */
final class VlcDanmakuOverlay: UIView {

    /// 一条弹幕。mode 用 B 站约定：1/2/3 滚动，4 底部，5 顶部。
    struct Item {
        let timeMs: Int
        let text: String
        let mode: Int
        let color: UIColor
        /// 渐变弹幕（`<d g="FF0000,00FF00">`）。非空时按逐字插值上色
        var gradient: [UIColor]? = nil
    }

    private static let STATIC_MS: Int = 4_000
    private static let SPAWN_WINDOW_MS: Int = 1_200
    private static let LANE_GAP_MS: Int = 350
    private static let SEEK_JUMP_MS: Int = 2_500
    private static let MAX_ACTIVE = 80

    private struct Active {
        let item: Item
        let label: UILabel
        /// 车道号（滚动/静态共用同一坐标系，0 = 最上面一行）
        let lane: Int
        let bornPosMs: Int
        let width: CGFloat
        let height: CGFloat
        /// 容器坐标下的顶边
        let y: CGFloat
        let scroll: Bool
        /// 这一条的滚动时长（毫秒）。长弹幕按网页 durFor 缩短，见 spawn。
        let durMs: Int
    }

    private var items: [Item] = []
    private var cursor = 0
    private var active: [Active] = []
    private var lastPosMs = -1
    /// 开关（控制层"弹"按钮）。关着时不发射，但仍记录位置
    private var enabled = true

    // 可被网页设置覆盖（metaJson.dm / dmOptions 指令）
    private var opacity: CGFloat = 0.9
    private var scale: CGFloat = 1
    private var scrollMs: Int = 12_000
    private var areaRatio: CGFloat = 0.7

    // 视频实际显示区域（aspect-fit 后的 rect，overlay 坐标）。nil = 退回全屏
    private var videoRect: CGRect?
    private var viewport: CGRect { videoRect ?? bounds }

    // 按当前尺寸算出的行布局
    private var textSize: CGFloat = 32
    private var laneH: CGFloat = 48
    private var lanes: Int = 8
    private var font: UIFont = .boldSystemFont(ofSize: 32)
    private var layoutSig = ""
    private var laneLastAt: [Int] = Array(repeating: -99_999, count: 64)

    // 60fps 时钟：锚点（播放器时间）+ 单调时钟增量，播放器 tick 只做校准
    private var clockMs: Double = 0
    private var clockAt: CFTimeInterval = 0
    private var clockValid = false
    private var playing = false
    private var rate: Double = 1
    private var link: CADisplayLink?

    // UILabel 池：密集段每秒新建几十个 UILabel 会顶到主线程预算
    private var pool: [UILabel] = []

    // MARK: - 配置

    func applyOptions(opacity: CGFloat, scale: CGFloat, durationSec: Int, areaRatio: CGFloat) {
        // 上下限与网页 DanmakuOptions 一致（0.2 / 0.5~2 / 4~30s / 0.3）
        self.opacity = min(1, max(0.2, opacity))
        self.scale = min(2, max(0.5, scale))
        self.scrollMs = min(30, max(4, durationSec)) * 1000
        self.areaRatio = min(1, max(0.3, areaRatio))
        recomputeMetrics()
    }

    /// 视频显示区域（aspect-fit 后的 rect）。弹幕只在画面内飞，不飘黑边。
    func setViewport(_ rect: CGRect) {
        let r = (rect.width > 1 && rect.height > 1) ? rect : nil
        if let a = videoRect, let b = r,
           abs(a.minX - b.minX) < 0.5, abs(a.minY - b.minY) < 0.5,
           abs(a.width - b.width) < 0.5, abs(a.height - b.height) < 0.5 { return }
        if videoRect == nil && r == nil { return }
        videoRect = r
        recomputeMetrics()
    }

    /// 弹幕开关（控制层"弹"按钮）：关掉时清屏且不再发射
    func setEnabled(_ on: Bool) {
        guard enabled != on else { return }
        enabled = on
        if !on {
            clearActive()
            stopLink()
        } else if playing {
            startLink()
        }
    }

    func setItems(_ list: [Item]) {
        items = list.sorted { $0.timeMs < $1.timeMs }
        cursor = 0
        clearActive()
        lastPosMs = -1
        clockValid = false
        if enabled && playing { startLink() }
    }

    // MARK: - 布局

    override func layoutSubviews() {
        super.layoutSubviews()
        // 没被 setViewport 钉住时跟着容器走（旋转/安全区变化）
        if videoRect == nil { recomputeMetrics() }
    }

    /// CADisplayLink 会强引用 target：不摘掉的话本层永远不 deinit（经典循环引用），
    /// 播放器关掉后还在后台空转。离开窗口即停。
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { stopLink() }
    }

    deinit {
        link?.invalidate()
    }

    private func recomputeMetrics() {
        let h = viewport.height
        let w = viewport.width
        guard h > 1, w > 1 else { return }
        /*
         * 尺寸/参数签名：没变就什么都不做（layoutSubviews 会被频繁调用，
         * 每次都 clearActive 会让在场弹幕不间断地被抹掉）。
         */
        let sig = "\(Int(w))x\(Int(h))|\(scale)|\(areaRatio)|\(scrollMs)"
        guard sig != layoutSig else { return }
        layoutSig = sig
        /*
         * ★ 与网页 DanmakuRenderer 同一套公式（2026-11-27 用户实报
         * "点播页弹幕很高、片库这个就很低"）：
         *   字号 = 22 × 缩放 × 窄屏补偿 × 矮屏补偿（12~40）
         *   车道高 = 字号 + 10（网页是 ceil(fontSize)+上下 padding 8+2）
         *   车道数 = 高度 × 区域 / 车道高
         * 旧公式是 h/32 且车道高 = 字号×1.5，再**预留顶部/底部各 2 行**给静态
         * 弹幕 —— 结果片库只有 6 行左右可用，占屏不到一半，视觉上"低"一大截。
         * 网页的静态弹幕是**动态找空位**（staticOccupies），不需要预留行。
         */
        let narrow = w >= 640 ? 1 : max(0.62, w / 640)
        let short = min(1, max(0.66, h / 480))
        textSize = min(40, max(12, 22 * scale * narrow * short))
        laneH = textSize.rounded(.up) + 10
        lanes = max(3, Int(h * areaRatio / laneH))
        // 字重与网页 `600 ${fontSize}px` 对齐（bold=700 在真机上偏粗）
        font = .systemFont(ofSize: textSize, weight: .semibold)
        // 尺寸变了，按新行数重排（清掉重来，比错位好）
        clearActive()
    }

    // MARK: - 位置驱动

    /**
     * 播放器每 0.25 秒调一次（校准锚点）。
     *
     * <p>`playing=false` 时冻结在 pos 上：暂停画面不动，弹幕也不动。
     */
    func tick(positionMs pos: Int, playing: Bool, rate: Double) {
        let now = CACurrentMediaTime()
        self.rate = min(4, max(0.1, rate))
        guard enabled else {
            lastPosMs = pos
            self.playing = false
            stopLink()
            return
        }
        guard !layoutSig.isEmpty else {
            // 还没拿到尺寸：先记位置，等布局好了会从当前位置重新对齐
            lastPosMs = pos
            return
        }
        let seeked = lastPosMs >= 0 && abs(pos - lastPosMs) > Self.SEEK_JUMP_MS
        if seeked || !clockValid {
            clockMs = Double(pos)
            clockAt = now
            clockValid = true
            if seeked {
                clearActive()
                // 往回拖时游标必须回到目标时间点，否则要等播过旧位置才有弹幕
                resyncCursor(pos)
            }
        } else if self.playing != playing {
            // 播放/暂停切换：以播放器时间为准重新锚定，避免暂停期间时钟继续跑
            clockMs = Double(pos)
            clockAt = now
        } else if playing {
            /*
             * 缓慢校准：播放器时间与插值时钟的差值按 30% 收敛，而不是直接赋值。
             * 直接赋值 = 每 250ms 把弹幕瞬移一次，视觉上就是"一顿一顿"。
             */
            let predicted = clockMs + (now - clockAt) * 1000 * self.rate
            let drift = Double(pos) - predicted
            if abs(drift) > 400 {
                clockMs = Double(pos)
                clockAt = now
            } else {
                clockMs = predicted + drift * 0.3
                clockAt = now
            }
        } else {
            clockMs = Double(pos)
            clockAt = now
        }
        lastPosMs = pos
        self.playing = playing
        if playing {
            startLink()
        } else {
            stopLink()
            renderOnce()
        }
    }

    private func startLink() {
        guard link == nil, window != nil else { return }
        let l = CADisplayLink(target: self, selector: #selector(step))
        // ProMotion 屏默认 120Hz：弹幕位移用不到这么高的刷新，翻倍的主线程
        // 布局写入只会和 VLC 的渲染抢时间。
        l.preferredFramesPerSecond = 60
        l.add(to: .main, forMode: .common)
        link = l
    }

    private func stopLink() {
        link?.invalidate()
        link = nil
    }

    private func currentPosMs(_ now: CFTimeInterval) -> Int {
        let extra = playing ? (now - clockAt) * 1000 * rate : 0
        return Int(clockMs + extra)
    }

    private func renderOnce() {
        guard clockValid else { return }
        let pos = Int(clockMs)
        cleanup(pos)
        spawnIfDue(pos)
        layout(pos)
    }

    @objc private func step() {
        guard clockValid, enabled else { return }
        let pos = currentPosMs(CACurrentMediaTime())
        // 先清过期、再发射、最后落位：过期的不占车道，密集段才不会被迫叠字
        cleanup(pos)
        spawnIfDue(pos)
        layout(pos)
    }

    // MARK: - 发射

    private func resyncCursor(_ pos: Int) {
        let edge = pos - Self.SPAWN_WINDOW_MS
        var lo = 0
        var hi = items.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if items[mid].timeMs < edge { lo = mid + 1 } else { hi = mid }
        }
        cursor = lo
    }

    private func spawnIfDue(_ pos: Int) {
        while cursor < items.count, items[cursor].timeMs <= pos {
            let item = items[cursor]
            cursor += 1
            // 追赶窗口：seek 之后不要一次性补上百条历史弹幕
            if pos - item.timeMs > Self.SPAWN_WINDOW_MS { continue }
            spawn(item, pos)
        }
    }

    private func spawn(_ item: Item, _ pos: Int) {
        guard active.count < Self.MAX_ACTIVE else { return }
        let scroll = item.mode == 1 || item.mode == 2 || item.mode == 3
        let lane: Int
        let y: CGFloat
        if scroll {
            lane = scrollLane(pos)
            // 轨道满：丢弃，不叠字（TV 同一判据："宁可少，不叠"）
            guard lane >= 0 else { return }
            y = viewport.minY + CGFloat(lane) * laneH
        } else {
            /*
             * 静态（顶/底）也用**车道号**，与滚动同坐标系：
             * 顶 = 0..n 从上往下找空道；底 = lanes-1.. 从下往上找空道。
             * 不预留整段行（网页也是动态找空位），否则滚动区域会被切掉两行。
             */
            lane = staticLane(item.mode)
            guard lane >= 0 else { return }
            y = viewport.minY + CGFloat(lane) * laneH
        }

        let label = dequeueLabel()
        /*
         * ★ 样式用「纯色字 + layer shadow」而不是 NSAttributedString 的负
         * strokeWidth（2026-11-27 真机回归）：负描边是画在填充**之上**的，
         * 6% 的字号宽度会把中文密笔画的内芯整个盖掉 —— 屏幕上一片发脏的
         * 灰边字，用户直接说"弹幕样式坏了"。shadow 才是之前验收过的观感：
         * 1pt 软黑边、笔画完整。
         * 掉帧问题不在这里解决，而是靠 dequeueLabel 里的 shouldRasterize
         * （阴影烘进位图，移动时只做合成，不每帧重画模糊）。
         */
        label.text = item.text
        label.font = font
        label.textColor = item.color.withAlphaComponent(opacity)
        /*
         * 渐变弹幕（2026-11-27 对齐网页的 `g=` 属性）：逐字插值上色。
         * 网页是 canvas 渐变填充；iOS 侧用"每个字一个颜色"近似 —— 中文弹幕
         * 每个字 20~40pt 宽，肉眼就是连续渐变，且能继续复用 UILabel + 位图缓存
         * （CAGradientLayer 遮罩方案拿不到 layer shadow，描边会丢）。
         */
        if let g = item.gradient, g.count >= 2 {
            label.attributedText = Self.gradientText(item.text, colors: g,
                                                    font: font, opacity: opacity)
        }
        label.layer.shadowColor = UIColor.black.withAlphaComponent(opacity * 0.85).cgColor
        // 与网页 sprite 的 shadowBlur=3 对齐（位图缓存，不会每帧重算）
        label.layer.shadowRadius = 2
        label.layer.shadowOffset = .zero
        label.layer.shadowOpacity = 1
        label.alpha = 1
        // 位图按当前屏幕倍率烘：@2x/@3x 上不糊（overlay 已入窗口，scale 是准的）
        label.layer.rasterizationScale = contentScaleFactor > 1 ? contentScaleFactor : 2
        let fit = label.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude,
                                            height: CGFloat.greatestFiniteMagnitude))
        let w = fit.width.rounded(.up) + 2
        let h = max(laneH, fit.height.rounded(.up))
        label.frame = CGRect(x: viewport.maxX, y: y, width: w, height: h)
        addSubview(label)
        // 长弹幕按网页 durFor 缩短时长：宽度超过屏宽 20% 时按比例加速，
        // 免得一条长弹幕霸占整条车道十几秒（观感上"速度"两边才对得上）
        let dur = scroll ? durFor(width: w) : scrollMs
        active.append(Active(item: item, label: label, lane: lane,
                             bornPosMs: pos, width: w, height: h, y: y, scroll: scroll,
                             durMs: dur))
    }

    /**
     * 一条宽 width 的弹幕该滚多久（毫秒），逐条照搬网页 DanmakuRenderer.durFor：
     * 短弹幕用基准时长；超过屏宽 20% 的按 `参考/实际` 缩短（下限 30%），
     * 再用「每秒最多移动多少像素」封顶，避免被压到看不清。
     */
    private func durFor(width: CGFloat) -> Int {
        let base = scrollMs
        let w = viewport.width
        guard w > 1, base > 0 else { return base }
        let refW = w * 0.2
        if width <= refW { return base }
        let factor = max(0.3, refW / width)
        let byFactor = max(2_000, Int(Double(base) * Double(factor)))
        if byFactor >= base { return byFactor }
        let vBase = (w + refW) / CGFloat(base)          // 像素 / 毫秒
        let maxSpeed = vBase * 1.6
        let minDur = Int(((w + width) / maxSpeed).rounded())
        return max(2_000, byFactor, minDur)
    }

    /// 滚动车道是否可用：该道所有在场弹幕的右边缘都已进入画面并留 40pt 余量。
    ///
    /// <p>车道范围就是全部车道（0..<lanes）——不预留顶/底行，与网页一致；
    /// 静态弹幕靠 staticLane 动态避让（找空道，找不到就丢）。
    private func scrollLane(_ pos: Int) -> Int {
        guard viewport.width > 1, lanes > 0 else { return -1 }
        for lane in 0..<lanes where lane < laneLastAt.count {
            // 同车道两次投放的最小间隔：防止密集段两条弹幕贴在一起
            if pos - laneLastAt[lane] < Self.LANE_GAP_MS { continue }
            var right: CGFloat = -1_000_000
            for a in active where a.scroll && a.lane == lane {
                let x = currentX(a, at: pos)
                right = max(right, x + a.width)
            }
            if right < viewport.maxX - 40 {
                laneLastAt[lane] = pos
                return lane
            }
        }
        return -1
    }

    /// 顶/底静态弹幕的车道号：在**该侧前几行**里找没人用的空道。
    ///
    /// <p>top：0、1、2…（从上往下）；bottom：lanes-1、lanes-2…（从下往上）。
    /// 只看前 3 行（网页的静态弹幕同样只贴边放，铺满整屏的静态弹幕反而挡画面）。
    /// 找不到空道就丢弃，不压字。
    private func staticLane(_ mode: Int) -> Int {
        guard lanes > 0 else { return -1 }
        let maxRows = min(3, lanes)
        for row in 0..<maxRows {
            let lane = mode == 5 ? row : (lanes - 1 - row)
            if active.contains(where: { $0.lane == lane }) { continue }
            return lane
        }
        return -1
    }

    private func currentX(_ a: Active, at pos: Int) -> CGFloat {
        let elapsed = max(0, pos - a.bornPosMs)
        // 逐条时长（长弹幕已按 durFor 缩短）：用全局 scrollMs 会让长弹幕"慢半拍"
        let dur = a.durMs > 0 ? a.durMs : scrollMs
        let progress = min(1.0, CGFloat(elapsed) / CGFloat(dur))
        return viewport.maxX - (viewport.width + a.width) * progress
    }

    // MARK: - 渲染

    private func layout(_ pos: Int) {
        guard !active.isEmpty else { return }
        // 逐帧写 layer.position：禁用隐式动画，否则每次赋值都会重新起一段动画
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        for a in active {
            let x: CGFloat
            if a.scroll {
                x = currentX(a, at: pos)
            } else {
                x = viewport.minX + (viewport.width - a.width) / 2
                // 静态弹幕末尾 400ms 淡出（位置冻结，只改透明度）
                let age = pos - a.bornPosMs
                a.label.alpha = age > Self.STATIC_MS
                    ? 0 : min(1, CGFloat(Self.STATIC_MS - age) / 400)
            }
            a.label.layer.position = CGPoint(x: x + a.width / 2, y: a.y + a.height / 2)
        }
        CATransaction.commit()
    }

    private func cleanup(_ pos: Int) {
        guard !active.isEmpty else { return }
        var keep: [Active] = []
        keep.reserveCapacity(active.count)
        for a in active {
            let age = pos - a.bornPosMs
            // 按逐条时长判死（长弹幕被 durFor 缩短过，用全局时长会晚收 10 秒）
            let dur = a.durMs > 0 ? a.durMs : scrollMs
            let dead = a.scroll ? age > dur + 300 : age > Self.STATIC_MS + 300
            if dead {
                recycle(a.label)
            } else {
                keep.append(a)
            }
        }
        active = keep
    }

    private func clearActive() {
        for a in active { recycle(a.label) }
        active.removeAll()
        laneLastAt = Array(repeating: -99_999, count: 64)
    }

    // MARK: - Label 池

    private func dequeueLabel() -> UILabel {
        if let l = pool.popLast() { return l }
        let l = UILabel()
        l.isUserInteractionEnabled = false
        l.numberOfLines = 1
        l.lineBreakMode = .byClipping
        /*
         * 把「字 + 阴影」烘成一张位图：之后每帧只改 layer.position，
         * 不重新做阴影的离屏模糊（弹幕几十条同时飞时这是最大的 GPU 开销）。
         * rasterizationScale 跟屏幕走，否则 @2x/@3x 上会糊。
         */
        l.layer.shouldRasterize = true
        l.layer.rasterizationScale = contentScaleFactor > 0 ? contentScaleFactor : 2
        return l
    }

    private func recycle(_ l: UILabel) {
        l.removeFromSuperview()
        l.alpha = 1
        // 池上限按"同时在场"量级给：每条位图随字号增长，池开太大白占显存
        if pool.count < 40 { pool.append(l) }
    }

    // MARK: - 解析

    /**
     * 解析 B 站风格的弹幕 XML：`<d p="time,mode,size,color,...">text</d>`。
     *
     * <p>判据与网页 VideoPlayer 的 `filterDanmaku` 对齐（2026-11-27）：
     *   ① 实体解码（含 `&#128077;` 这类数字实体，否则表情会原样显示成乱码）；
     *   ② 纯符号/纯空白丢掉（`\p{L}\p{N}` 一个都没有）；
     *   ③ 屏蔽词命中丢掉；
     *   ④ 同文本 1.5 秒内重复只留第一条（刷屏）。
     * 不做 ③④ 的话，原生弹幕会比点播页"多出一堆重复的刷屏"。
     */
    static func parse(xml: String, offsetMs: Int, blockWords: [String] = []) -> [Item] {
        var out: [Item] = []
        guard let re = try? NSRegularExpression(
            pattern: "<d p=\"([^\"]*)\"[^>]*>(.*?)</d>",
            options: [.dotMatchesLineSeparators]) else { return out }
        let ns = xml as NSString
        let matches = re.matches(in: xml, range: NSRange(location: 0, length: ns.length))
        out.reserveCapacity(matches.count)
        var lastAt: [String: Int] = [:]
        for m in matches {
            guard m.numberOfRanges >= 3 else { continue }
            let p = ns.substring(with: m.range(at: 1))
            let text = ns.substring(with: m.range(at: 2))
            let parts = p.split(separator: ",", omittingEmptySubsequences: false)
            guard parts.count >= 4 else { continue }
            let seconds = Double(parts[0]) ?? 0
            let mode = Int(parts[1]) ?? 1
            let color = Int(parts[3]) ?? 0xFFFFFF
            let decoded = decodeEntities(text)
            guard !decoded.isEmpty else { continue }
            // 纯符号 / 纯 emoji（网页 pureSymbol 同一判据）
            guard decoded.rangeOfCharacter(from: .alphanumerics) != nil else { continue }
            var blocked = false
            for w in blockWords where !w.isEmpty {
                if decoded.contains(w) { blocked = true; break }
            }
            if blocked { continue }
            let timeMs = Int(seconds * 1000) + offsetMs
            guard timeMs >= 0 else { continue }
            // 同文本 1.5s 内重复 = 刷屏，只留第一条
            if let prev = lastAt[decoded], abs(timeMs - prev) < 1_500 { continue }
            lastAt[decoded] = timeMs
            /*
             * 渐变弹幕 `<d p="…" g="FF0000,00FF00">`：g 不在 p 里，从整段标签里抠。
             * 单色（无 g）时保持 undefined（走纯色路径，零额外开销）。
             */
            var gradient: [UIColor]? = nil
            let whole = ns.substring(with: m.range)
            if let gr = whole.range(of: "g=\"") {
                let tail = whole[gr.upperBound...]
                if let end = tail.firstIndex(of: "\"") {
                    gradient = Self.parseGradient(String(tail[..<end]))
                }
            }
            out.append(Item(timeMs: timeMs,
                            text: decoded,
                            mode: mode,
                            color: Self.color(fromDecimal: color),
                            gradient: gradient))
        }
        return out
    }

    private static func color(fromDecimal v: Int) -> UIColor {
        if v == 0 { return .white }
        return UIColor(red: CGFloat((v >> 16) & 0xFF) / 255,
                       green: CGFloat((v >> 8) & 0xFF) / 255,
                       blue: CGFloat(v & 0xFF) / 255,
                       alpha: 1)
    }

    /// `g="FF0000,00FF00"` → 颜色数组；最多取 5 段（再多肉眼也看不出）
    static func parseGradient(_ raw: String) -> [UIColor]? {
        let parts = raw.split(separator: ",").prefix(5).map { $0.trimmingCharacters(in: .whitespaces) }
        let colors = parts.compactMap { hex -> UIColor? in
            let s = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
            guard s.count == 6, let v = Int(s, radix: 16) else { return nil }
            return color(fromDecimal: v)
        }
        return colors.count >= 2 ? colors : nil
    }

    /// 逐字插值的富文本（渐变弹幕）
    private static func gradientText(_ text: String, colors: [UIColor],
                                     font: UIFont, opacity: CGFloat) -> NSAttributedString {
        let chars = Array(text)
        guard !chars.isEmpty else { return NSAttributedString(string: text) }
        let out = NSMutableAttributedString()
        let last = max(1, chars.count - 1)
        for (i, ch) in chars.enumerated() {
            let t = CGFloat(i) / CGFloat(last)
            let c = blend(colors, t).withAlphaComponent(opacity)
            out.append(NSAttributedString(string: String(ch), attributes: [
                .font: font,
                .foregroundColor: c,
            ]))
        }
        return out
    }

    /// 多段颜色在 [0,1] 上的线性插值
    private static func blend(_ colors: [UIColor], _ t: CGFloat) -> UIColor {
        guard colors.count >= 2 else { return colors.first ?? .white }
        let clamped = min(1, max(0, t))
        let scaled = clamped * CGFloat(colors.count - 1)
        let idx = min(colors.count - 2, Int(scaled))
        let frac = scaled - CGFloat(idx)
        return mix(colors[idx], colors[idx + 1], frac)
    }

    private static func mix(_ a: UIColor, _ b: UIColor, _ t: CGFloat) -> UIColor {
        var ar: CGFloat = 0, ag: CGFloat = 0, ab: CGFloat = 0, aa: CGFloat = 0
        var br: CGFloat = 0, bg: CGFloat = 0, bb: CGFloat = 0, ba: CGFloat = 0
        a.getRed(&ar, green: &ag, blue: &ab, alpha: &aa)
        b.getRed(&br, green: &bg, blue: &bb, alpha: &ba)
        return UIColor(red: ar + (br - ar) * t,
                       green: ag + (bg - ag) * t,
                       blue: ab + (bb - ab) * t,
                       alpha: aa + (ba - aa) * t)
    }

    /**
     * 弹幕正文的实体解码。
     *
     * <p>顺序不能反：`&amp;` 必须先处理，否则 `&amp;lt;` 会二次解码。
     * <p>数字实体（`&#128077;` / `&#x1F44D;`）也必须解 —— 网页那边解两层，
     * 不解的话原生弹幕里的表情会原样显示成 `&#128077;`（2026-11-27 对齐）。
     */
    private static func decodeEntities(_ s: String) -> String {
        var out = s
        for (k, v) in [("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", "\""), ("&#39;", "'")] {
            out = out.replacingOccurrences(of: k, with: v)
        }
        return decodeNumericEntities(out)
    }

    /// 解 `&#123;`（十进制）与 `&#x1F600;`（十六进制）两种数字实体。
    private static func decodeNumericEntities(_ s: String) -> String {
        guard s.contains("&#") else { return s }
        var out = String()
        out.reserveCapacity(s.count)
        var i = s.startIndex
        while i < s.endIndex {
            let next = s.index(after: i)
            if s[i] == "&", next < s.endIndex, s[next] == "#" {
                var j = s.index(after: next)
                var hex = false
                if j < s.endIndex, s[j] == "x" || s[j] == "X" {
                    hex = true
                    j = s.index(after: j)
                }
                var digits = ""
                while j < s.endIndex, s[j] != ";", digits.count < 8 {
                    digits.append(s[j])
                    j = s.index(after: j)
                }
                if j < s.endIndex, s[j] == ";", !digits.isEmpty,
                   let code = UInt32(digits, radix: hex ? 16 : 10),
                   code <= 0x10FFFF, !(0xD800...0xDFFF).contains(code),
                   let scalar = UnicodeScalar(code) {
                    out.unicodeScalars.append(scalar)
                    i = s.index(after: j)
                    continue
                }
            }
            out.append(s[i])
            i = next
        }
        return out
    }
}
