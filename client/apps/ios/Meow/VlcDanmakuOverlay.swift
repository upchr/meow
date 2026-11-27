import UIKit

/**
 * 轻量弹幕层（CALayer/UILabel 自绘，零依赖）——iOS 版对应 TV 的 `DanmakuOverlay`。
 *
 * <p>为什么不让 web 那边画：原生播放器全屏接管时 WKWebView 不可见，
 * 弹幕只能由原生层渲染。参数与语义**逐项对齐** TV 的实现，避免两个端
 * 出现"同样一条弹幕，一个端 12 秒一个端 8 秒"。
 *
 * <p>与播放器的关系：只读播放位置，不控制播放。位置跳变 > 2.5 秒视为 seek，
 * 清空在场弹幕重新开始，避免"拖完进度弹幕一次性糊满屏"。
 *
 * <p>平滑方式：播放器每 0.25 秒给一次位置，本层把每条弹幕的位移做成
 * 0.26 秒线性动画 —— 比每帧重算省 CPU，视觉上足够顺（弹幕只有横向平移）。
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
        let lane: Int
        let bornPosMs: Int
        let width: CGFloat
        let y: CGFloat
        let scroll: Bool
    }

    private var items: [Item] = []
    private var cursor = 0
    private var active: [Active] = []
    private var lastPosMs = -1
    /// 开关（控制层"弹"按钮）。关着时不发射，但仍记录位置
    private var enabled = true

    // 可被网页设置覆盖（metaJson.dm）
    private var opacity: CGFloat = 0.9
    private var scale: CGFloat = 1
    private var scrollMs: Int = 12_000
    private var areaRatio: CGFloat = 0.7

    // 按当前尺寸算出的行布局
    private var textSize: CGFloat = 32
    private var laneH: CGFloat = 48
    private var lanes: Int = 8
    private var topRows: Int = 2
    private var bottomRows: Int = 2
    private var layoutW: CGFloat = 0
    private var laneLastAt: [Int] = Array(repeating: -99_999, count: 64)

    // MARK: - 配置

    func applyOptions(opacity: CGFloat, scale: CGFloat, durationSec: Int, areaRatio: CGFloat) {
        self.opacity = min(1, max(0.1, opacity))
        self.scale = min(2, max(0.5, scale))
        self.scrollMs = min(30, max(4, durationSec)) * 1000
        self.areaRatio = min(1, max(0.2, areaRatio))
        layoutIfNeeded()
        recomputeLayout()
    }

    /// 弹幕开关（控制层"弹"按钮）：关掉时清屏且不再发射
    func setEnabled(_ on: Bool) {
        guard enabled != on else { return }
        enabled = on
        if !on { clearActive() }
    }

    func setItems(_ list: [Item]) {
        items = list.sorted { $0.timeMs < $1.timeMs }
        cursor = 0
        clearActive()
        lastPosMs = -1
    }

    // MARK: - 布局

    override func layoutSubviews() {
        super.layoutSubviews()
        recomputeLayout()
    }

    private func recomputeLayout() {
        let h = bounds.height
        let w = bounds.width
        guard h > 0, w > 0 else { return }
        if abs(w - layoutW) < 1 { return }
        layoutW = w
        textSize = min(56, max(18, h / 32 * scale))
        laneH = textSize * 1.5
        lanes = max(3, Int(h * areaRatio / laneH))
        topRows = 2
        bottomRows = 2
        // 尺寸变了，按新行数重排（清掉重来，比错位好）
        clearActive()
    }

    // MARK: - 位置驱动

    /// 播放器每 0.25 秒调一次；pos 不前进（暂停）时自然冻结。
    func tick(positionMs pos: Int) {
        guard layoutW > 0 else { return }
        if !enabled {
            // 关着也要记位置：重新打开时不该把这段时间当成 seek 而清屏重发
            lastPosMs = pos
            return
        }
        if lastPosMs >= 0 && abs(pos - lastPosMs) > Self.SEEK_JUMP_MS {
            clearActive()
        }
        lastPosMs = pos
        spawnIfDue(pos)
        layoutActive(pos)
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
        if active.count >= Self.MAX_ACTIVE {
            if let oldest = active.min(by: { $0.bornPosMs < $1.bornPosMs }) {
                oldest.label.removeFromSuperview()
                active.removeAll { $0.label === oldest.label }
            }
        }
        let scroll = item.mode == 1 || item.mode == 2 || item.mode == 3
        let label = UILabel()
        label.text = item.text
        label.font = .boldSystemFont(ofSize: textSize)
        label.textColor = item.color.withAlphaComponent(opacity)
        // 描边：亮画面上纯色字会糊，TV 那边是 outline+fill 双绘，这里用阴影近似
        label.layer.shadowColor = UIColor.black.withAlphaComponent(opacity * 0.85).cgColor
        label.layer.shadowRadius = 1
        label.layer.shadowOffset = .zero
        label.layer.shadowOpacity = 1
        label.sizeToFit()

        let y: CGFloat
        let lane: Int
        if scroll {
            // 滚动弹幕只在中间区域（避开顶部/底部静态占位）
            let first = topRows
            let last = max(first, lanes - bottomRows - 1)
            lane = pickLane(from: first, to: last, pos: pos, scroll: true)
            y = CGFloat(lane) * laneH
        } else if item.mode == 5 {
            lane = pickLane(from: 0, to: topRows - 1, pos: pos, scroll: false)
            y = CGFloat(lane) * laneH
        } else {
            lane = pickLane(from: max(0, lanes - bottomRows), to: lanes - 1, pos: pos, scroll: false)
            y = CGFloat(lane) * laneH
        }
        label.frame.origin = CGPoint(x: bounds.width, y: y)
        addSubview(label)
        active.append(Active(item: item, label: label, lane: lane,
                             bornPosMs: pos, width: label.bounds.width,
                             y: y, scroll: scroll))
    }

    /// 某车道是否可用：最后一条弹幕的右边缘已完全进入屏幕（留 8pt 间隙）。
    private func laneClear(_ lane: Int, at pos: Int) -> Bool {
        guard let last = active.last(where: { $0.lane == lane }) else { return true }
        if !last.scroll { return pos - last.bornPosMs > Self.STATIC_MS }
        let elapsed = max(0, pos - last.bornPosMs)
        let progress = min(1.0, CGFloat(elapsed) / CGFloat(scrollMs))
        let x = bounds.width - (bounds.width + last.width) * progress
        return x + last.width < bounds.width - 8
    }

    /// 找一条最近没投放过的车道；全忙则取最久没用的（宁可略挤也不丢弹幕）。
    private func pickLane(from: Int, to: Int, pos: Int, scroll: Bool) -> Int {
        let lo = max(0, min(from, lanes - 1))
        let hi = max(lo, min(to, lanes - 1))
        /*
         * 选道判据（2026-11-27 真机实报「弹幕挤在一起」）：
         * 只看"350ms 没用过"不够 —— 滚动弹幕 12 秒才走完一条道，快车道上前
         * 一条还没走远，第二条按时间间隔放进来就会追尾重叠。
         * 必须同时检查：该车道最后一条的**右边缘**是否已经完全进入屏幕。
         */
        for lane in lo...hi where pos - laneLastAt[lane] >= Self.LANE_GAP_MS {
            if scroll && !laneClear(lane, at: pos) { continue }
            laneLastAt[lane] = pos
            return lane
        }
        var best = lo
        var oldest = Int.max
        for lane in lo...hi where laneLastAt[lane] < oldest {
            oldest = laneLastAt[lane]
            best = lane
        }
        laneLastAt[best] = pos
        return best
    }

    private func layoutActive(_ pos: Int) {
        let w = bounds.width
        for a in active {
            let target: CGPoint
            if a.scroll {
                // 关键状态：按「生成位置 → 当前位置」的增量算，暂停/seek 都自动正确
                let elapsed = max(0, pos - a.bornPosMs)
                let progress = min(1.0, CGFloat(elapsed) / CGFloat(scrollMs))
                target = CGPoint(x: w - (w + a.width) * progress, y: a.y)
            } else {
                // 静态弹幕：居中，4 秒后消失
                target = CGPoint(x: (w - a.width) / 2, y: a.y)
                let age = pos - a.bornPosMs
                a.label.alpha = age > Self.STATIC_MS
                    ? 0 : min(1, CGFloat(Self.STATIC_MS - age) / 400) * opacity
            }
            UIView.animate(withDuration: 0.26, delay: 0, options: [.curveLinear, .beginFromCurrentState]) {
                a.label.frame.origin = target
            }
        }
        // 已经滚出屏幕的移出（滚动结束 = 位置过 1 或 4 秒静态到期）
        let dead = active.filter { a in
            let age = pos - a.bornPosMs
            return a.scroll ? age > scrollMs + 300 : age > Self.STATIC_MS + 300
        }
        for a in dead {
            a.label.removeFromSuperview()
        }
        if !dead.isEmpty {
            let ids = Set(dead.map { ObjectIdentifier($0.label) })
            active.removeAll { ids.contains(ObjectIdentifier($0.label)) }
        }
    }

    private func clearActive() {
        for a in active { a.label.removeFromSuperview() }
        active.removeAll()
        laneLastAt = Array(repeating: -99_999, count: 64)
    }

    // MARK: - 解析

    /**
     * 解析 B 站风格的弹幕 XML：`<d p="time,mode,size,color,...">text</d>`。
     *
     * <p>与 TV 的 `parseDanmaku` 同一套判据：p 的第 1 段是**秒**（可能带小数），
     * 第 2 段是模式，第 4 段是十进制 RGB。offsetMs 是弹幕时间轴校正（用户可配）。
     */
    static func parse(xml: String, offsetMs: Int) -> [Item] {
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
            let timeMs = Int(seconds * 1000) + offsetMs
            guard timeMs >= 0, !text.isEmpty else { continue }
            out.append(Item(timeMs: timeMs,
                            text: decodeEntities(text),
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
