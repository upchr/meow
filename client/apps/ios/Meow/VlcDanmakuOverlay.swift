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
    }

    private static let STATIC_MS: Int = 4_000
    private static let SPAWN_WINDOW_MS: Int = 1_200
    private static let LANE_GAP_MS: Int = 350
    private static let SEEK_JUMP_MS: Int = 2_500
    private static let MAX_ACTIVE = 80

    private struct Active {
        let item: Item
        let label: UILabel
        /// 滚动弹幕的车道号；静态弹幕是所在行号
        let lane: Int
        let bornPosMs: Int
        let width: CGFloat
        let height: CGFloat
        /// 容器坐标下的顶边
        let y: CGFloat
        let scroll: Bool
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
    private var topRows: Int = 2
    private var bottomRows: Int = 2
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
        self.opacity = min(1, max(0.1, opacity))
        self.scale = min(2, max(0.5, scale))
        self.scrollMs = min(30, max(4, durationSec)) * 1000
        self.areaRatio = min(1, max(0.2, areaRatio))
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
        textSize = min(56, max(18, h / 32 * scale))
        laneH = textSize * 1.5
        lanes = max(3, Int(h * areaRatio / laneH))
        /*
         * 顶/底静态各占 2 行（区域小则 1 行）。滚动弹幕只在中间走，
         * 不分开的话密集时静态弹幕会压在滚动弹幕上（TV 2026-11-20 真机截图可见）。
         */
        topRows = lanes >= 8 ? 2 : (lanes >= 5 ? 1 : 0)
        bottomRows = topRows
        if lanes - topRows - bottomRows < 2 {
            topRows = 0
            bottomRows = 0
        }
        font = .boldSystemFont(ofSize: textSize)
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
            lane = staticRow(item.mode)
            guard lane >= 0 else { return }
            if item.mode == 5 {
                y = viewport.minY + CGFloat(lane) * laneH
            } else {
                y = viewport.maxY - CGFloat(lane + 1) * laneH
            }
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
        label.layer.shadowColor = UIColor.black.withAlphaComponent(opacity * 0.85).cgColor
        label.layer.shadowRadius = 1
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
        active.append(Active(item: item, label: label, lane: lane,
                             bornPosMs: pos, width: w, height: h, y: y, scroll: scroll))
    }

    /// 滚动车道是否可用：该道所有在场弹幕的右边缘都已进入画面并留 40pt 余量。
    private func scrollLane(_ pos: Int) -> Int {
        let from = max(0, topRows)
        let to = max(from + 1, lanes - bottomRows)
        guard viewport.width > 1 else { return -1 }
        for lane in from..<to where lane < laneLastAt.count {
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

    /// 顶/底静态位：取**第一个空行**（不是"按在场条数排队"——上排过期后
    /// 按条数算会把新弹幕放进下排，正好压住还在显示的那条）。
    private func staticRow(_ mode: Int) -> Int {
        let maxRows = max(1, mode == 5 ? topRows : bottomRows)
        var used = Set<Int>()
        for a in active where !a.scroll && a.item.mode == mode { used.insert(a.lane) }
        for row in 0..<maxRows where !used.contains(row) { return row }
        return -1
    }

    private func currentX(_ a: Active, at pos: Int) -> CGFloat {
        let elapsed = max(0, pos - a.bornPosMs)
        let progress = min(1.0, CGFloat(elapsed) / CGFloat(scrollMs))
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
            let dead = a.scroll ? age > scrollMs + 300 : age > Self.STATIC_MS + 300
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
     * <p>与 TV 的 `parseDanmaku` 同一套判据：p 的第 1 段是**秒**（可能带小数），
     * 第 2 段是模式，第 4 段是十进制 RGB。offsetMs 是弹幕时间轴校正（用户可配），
     * blockWords 是屏蔽词（命中即丢弃，逗号/空白分隔，与 TV 一致）。
     */
    static func parse(xml: String, offsetMs: Int, blockWords: [String] = []) -> [Item] {
        var out: [Item] = []
        guard let re = try? NSRegularExpression(
            pattern: "<d p=\"([^\"]*)\"[^>]*>(.*?)</d>",
            options: [.dotMatchesLineSeparators]) else { return out }
        let ns = xml as NSString
        let matches = re.matches(in: xml, range: NSRange(location: 0, length: ns.length))
        out.reserveCapacity(matches.count)
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
            var blocked = false
            for w in blockWords where !w.isEmpty {
                if decoded.contains(w) { blocked = true; break }
            }
            if blocked { continue }
            let timeMs = Int(seconds * 1000) + offsetMs
            guard timeMs >= 0 else { continue }
            out.append(Item(timeMs: timeMs,
                            text: decoded,
                            mode: mode,
                            color: Self.color(fromDecimal: color)))
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

    /// 弹幕正文只可能出现这几个实体；顺序不能反（&amp; 必须先处理，否则 &amp;lt; 会二次解码）
    private static func decodeEntities(_ s: String) -> String {
        var out = s
        for (k, v) in [("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", "\""), ("&#39;", "'")] {
            out = out.replacingOccurrences(of: k, with: v)
        }
        return out
    }
}
