package blbl.cat3399.feature.player.danmaku

/**
 * 弹幕位置的时间轴。
 *
 * 滚动弹幕 x = width - (nowMs - startTimeMs) × pxPerMs，逐帧位移 = 时间增量 × 速度；
 * 而 [DanmakuTimer] 是**累加**时间增量的（`smoothPositionMs += dt × speed`）。
 * 也就是说时间轴的**速率误差会 1:1 变成弹幕速度误差** —— 时间轴走快一点，弹幕就快一点。
 * 所以这根时间轴只有一条要求：**它的增量必须恒等于屏幕上真实流逝的时间。**
 *
 * 前两版都没做到，而且错在同一个地方 —— 拿「帧回调的**次数**」当时间的刻度尺，
 * 但真实时间只认「vsync 时间戳之**差**」：
 *
 *  1. 最初在 onDraw 里现取 `System.nanoTime()`：onDraw 落在 vsync 之后的哪个位置，
 *     取决于主线程这一帧遍历了多少东西，逐帧增量于是时大时小 —— 弹幕「呼吸」/「抖」。
 *  2. 上一版按「帧回调次数 × 刷新周期」推进，再用 `(now - desired)/8` 慢速校正锚点。
 *     这里有两处独立的问题：
 *     - **帧回调链会跳拍。** 注册下一帧的动作发生在 act 完成之后，act + 等主线程绘制
 *       一旦超过一个刷新周期，下一次回调就只能落在更后面的 vsync 上。次数少了一拍，
 *       时间轴就整整走慢一拍 → 弹幕突然变慢；只能等锚点慢速（1/8）追回 →
 *       于是「时快时慢」。刷新率越高越明显：120Hz 下一个刷新周期只有 8.3ms,
 *       act + 绘制更容易挤不进去。
 *     - 那个慢速校正的量取自 onDraw 的 `System.nanoTime()`，等于把主线程绘制相位的
 *       浮动整套搬进了**速率**（幅度衰减 8 倍，频带完整保留，缓慢负载变化照样通过）。
 *
 * 现在改成 **累加帧回调上报的 vsync 时间戳之差**：
 *  - 时间戳之差就是屏幕上两拍之间真实经过的时间；绝对值在各 ROM 上语义不一，差值不受影响；
 *  - 跳拍时上报的时间戳本身多走了一拍，时间轴自动补上 —— 不需要任何校正；
 *  - 没有周期估计、没有锚点、没有阈值，速率恒为 1。仿真中逐帧增量的方差为 0。
 *
 * 前提：帧回调与 onDraw 都在主线程，且同一 vsync 内 ANIMATION 阶段早于 TRAVERSAL 阶段，
 * 所以每次 onDraw 读到的就是本拍（见 [DanmakuPlayer] 的 FrameCallback）。
 *
 * 纯 Kotlin，不依赖 Android 框架，可直接单测。
 */
internal class PresentationClock {
    /** 上一拍的时间戳；0 表示还没有基准（起播最初 / 暂停后）。 */
    private var lastFrameNs: Long = 0L

    /** 连续收到回退时间戳的次数，用于区分「偶发异常样本」与「时间基准整体换了」。 */
    private var regressRun: Int = 0

    /** 时间轴。只作差分使用，绝对值无意义。 */
    private var timelineNs: Long = 0L

    /** 最近一次交付时看到的单调时钟，用于「还没有两拍时间戳」时兜底。 */
    private var lastRealNs: Long = 0L

    /** 上一次交付出去的时间轴值，用于区分「同一帧内的重复绘制」和统计逐帧增量。 */
    private var lastReturnedNs: Long = 0L
    private var returned: Boolean = false

    // ---- 以下仅用于诊断日志，不参与位置计算 ----

    /** 「时间轴 - 单调时钟」的基准，用于测时间轴的速率是否准。 */
    private var rateBaseNs: Long = Long.MIN_VALUE

    /**
     * 帧回调**间隔**的分布（按系统上报的 `frameTimeNanos` 之差）。
     *
     * 与 [stepHist] 对照可判断「有没有回调没画出来」：`gap` 均匀而 `step` 多出 2×/3× 档
     * = 出帧链掉拍。**但这条判据有前提**（2026-09-26 A/B 实测）：出帧链「迟一拍」只在
     * **节拍被拉长**时才会把两拍合并成一帧（SurfaceView 下实测丢帧 4.2%）；在干净 60Hz
     * 下旧链也只丢 0.07%。所以看到 `cb` > 出帧数时**先查 `vsP`**（节拍是不是干净），
     * 别直接去改出帧链。
     */
    private val gapHist = StepHistogram()

    /** 交付出去的时间轴**增量**（即出帧）的分布。 */
    private val stepHist = StepHistogram()

    /**
     * 帧回调**实际投递时刻**（`System.nanoTime()`）的间隔分布。
     *
     * `frameTimeNanos` 是系统**上报**的 vsync 时间戳，可能被显示 HAL / Choreographer 的
     * vsync 模型修饰过；`System.nanoTime()` 是本进程自己的单调时钟。两张间隔分布表一对照，
     * 就能判断「逐帧增量不齐」是屏幕真的在不等地出帧，还是上报的时间戳在抖：
     *
     *  - 两张表展宽相当 → 时间真的在不等地流逝，锅在显示链路，改应用无效；
     *  - vsync 表很宽（[minGapMs] 远小于 [refreshPeriodMs]）而这张表很窄
     *    → 逐帧增量里那部分不齐是**假的**，可以用匀速推进消掉。
     */
    private val dispatchHist = StepHistogram()

    /** 上一次回调的投递时刻（monotonic ns）。 */
    private var lastDispatchNs: Long = 0L

    /** 回调从上报的 vsync 时刻到被处理的延迟（`nanoTime - frameTimeNanos`，ns）。 */
    private var lateMinNs: Long = Long.MAX_VALUE
    private var lateMaxNs: Long = Long.MIN_VALUE
    private var lateSumNs: Long = 0L
    private var lateCount: Long = 0L

    /**
     * 交付一个 vsync 时间戳（帧回调里调用，主线程）。
     *
     * 只做一件事：把这一拍相对上一拍真实经过的时间累加到时间轴上。
     */
    fun onVsync(frameTimeNanos: Long) {
        if (frameTimeNanos <= 0L) return
        val prev = lastFrameNs
        if (prev <= 0L) {
            lastFrameNs = frameTimeNanos
            return
        }

        val d = frameTimeNanos - prev
        if (d <= 0L) {
            // 时间戳回退：这不是本帧的 vsync（语义异常）。**连基准一起保持不动**，
            // 下一拍仍与上一有效拍比较，避免把异常样本的歪基准带进来。
            // 若连续回退，说明时间基准整体换了（重连 surface），重设基准但不推进时间轴。
            if (++regressRun >= REGRESS_RUN_LIMIT) {
                lastFrameNs = frameTimeNanos
                regressRun = 0
            }
            return
        }
        regressRun = 0
        lastFrameNs = frameTimeNanos

        // 长时间挂起 / 时钟域跳变时只补一帧，避免弹幕瞬移；随后由 DanmakuTimer 的
        // 粗同步（偏差 ≥2s 时对齐媒体位置）兜住。
        timelineNs += if (d > MAX_STEP_NS) MAX_STEP_NS else d

        gapHist.record(d)
    }

    /**
     * 记录一次帧回调的**实际投递时刻**与延迟（仅诊断，不参与任何位置计算）。
     *
     * 两个用途：
     *  1. [dispatchHist]：进程自己的单调时钟测出的回调间隔 —— 与 [gapHist]（系统上报的
     *     时间戳之差）逐档对照，判断时间戳是否被修饰过（见 [dispatchHist]）。
     *  2. `late`（= `nowNanos - frameTimeNanos`）：回调从「上报的 vsync 时刻」到「真正跑到
     *     我们这里」的延迟。主线程被别的活卡住时，vsync 事件会被推迟处理，`late` 就会鼓出
     *     一整个拍（> 刷新周期）—— 这是「丢拍是不是我们自己造成的」唯一的直接判据。
     *
     * @param nowNanos 回调入口处的 `System.nanoTime()`
     * @param frameTimeNanos 同一次回调收到的 vsync 时间戳
     */
    fun recordDispatch(nowNanos: Long, frameTimeNanos: Long) {
        val prev = lastDispatchNs
        lastDispatchNs = nowNanos
        if (prev > 0L) {
            val d = nowNanos - prev
            if (d > 0L) dispatchHist.record(d)
        }

        // 时间戳被修饰过时 late 可能为负，这里取宽容区间，异常样本直接丢掉。
        val late = nowNanos - frameTimeNanos
        if (late > -MAX_STEP_NS && late < MAX_STEP_NS) {
            if (late < lateMinNs) lateMinNs = late
            if (late > lateMaxNs) lateMaxNs = late
            lateSumNs += late
            lateCount++
        }
    }

    /**
     * 取本次绘制应使用的位置时间（ns）。
     *
     * 位置只由帧回调推进；`nowNanos` 仅在「还没有两拍时间戳」时（起播最初一帧、暂停中）
     * 作为兜底，保证时间增量连续，绝不参与速率。
     */
    fun presentationNs(nowNanos: Long): Long {
        if (lastRealNs == 0L) {
            // 首次交付：以单调时钟为起点。DanmakuTimer 首帧本来就会重置，这个值用不上。
            lastRealNs = nowNanos
            timelineNs = nowNanos
            returned = true
            lastReturnedNs = timelineNs
            return timelineNs
        }

        if (lastFrameNs == 0L) {
            val d = nowNanos - lastRealNs
            if (d > 0L) timelineNs += if (d > MAX_STEP_NS) MAX_STEP_NS else d
        }
        lastRealNs = nowNanos

        if (!returned || timelineNs != lastReturnedNs) {
            if (returned) recordStep(timelineNs - lastReturnedNs)
            lastReturnedNs = timelineNs
            returned = true
        }
        return timelineNs
    }

    /**
     * 丢弃帧基准。暂停/停止时调用：暂停期间没有帧回调，恢复后若沿用旧时间戳，
     * 会把整个暂停时长当成一帧的增量。时间轴本身保留，续接不跳。
     */
    fun reset() {
        lastFrameNs = 0L
        regressRun = 0
        lastDispatchNs = 0L
    }

    /**
     * 取走诊断统计并开启新窗口。
     *
     * @param nowNanos 单调时钟当前值，用于测时间轴的速率误差。
     */
    fun consumeStats(nowNanos: Long): ClockStats {
        val gap = gapHist.consume()
        val step = stepHist.consume()
        val dispatch = dispatchHist.consume()

        val lateAvgMs =
            if (lateCount == 0L) {
                Float.NaN
            } else {
                ((lateSumNs.toDouble() / lateCount.toDouble()) / 1_000_000.0).toFloat()
            }
        val lateMinMs = if (lateMinNs == Long.MAX_VALUE) Float.NaN else lateMinNs / 1_000_000f
        val lateMaxMs = if (lateMaxNs == Long.MIN_VALUE) Float.NaN else lateMaxNs / 1_000_000f
        lateMinNs = Long.MAX_VALUE
        lateMaxNs = Long.MIN_VALUE
        lateSumNs = 0L
        lateCount = 0L

        val group = timelineNs - nowNanos
        val rateErrMs =
            if (rateBaseNs == Long.MIN_VALUE) {
                rateBaseNs = group
                0f
            } else {
                val e = group - rateBaseNs
                rateBaseNs = group
                e / 1_000_000f
            }

        return ClockStats(
            refreshPeriodMs = gap.medianMs,
            minGapMs = gap.minMs,
            q1GapMs = gap.q1Ms,
            maxGapMs = gap.maxMs,
            stepMinMs = step.minMs,
            stepMaxMs = step.maxMs,
            dispatchMedianMs = dispatch.medianMs,
            dispatchMinMs = dispatch.minMs,
            dispatchMaxMs = dispatch.maxMs,
            lateAvgMs = lateAvgMs.toFloat(),
            lateMinMs = lateMinMs,
            lateMaxMs = lateMaxMs,
            rateErrorMs = rateErrMs,
            gapHist = gap,
            stepHist = step,
        )
    }

    /** 当前时间轴值（ns）。测试用。 */
    fun timelineNs(): Long = timelineNs

    private fun recordStep(step: Long) {
        stepHist.record(step)
    }

    internal data class ClockStats(
        /**
         * 帧回调间隔的**中位数**（ms）—— 屏幕上真实的节拍周期。
         *
         * 取中位数而不是最小值：最小值会被单个离群样本（重复/乱序的 vsync 时间戳，
         * 实测能到 0.68ms）拉低，读出来的「周期」根本不是周期。
         * 16.67 = 系统按 60Hz 给 vsync；8.33 才是 120Hz。-1 表示本窗口无样本。
         */
        val refreshPeriodMs: Float,
        /** 帧回调间隔的**最小值**（ms）。远小于 [refreshPeriodMs] 就说明混进了离群样本。 */
        val minGapMs: Float,
        /**
         * 帧回调间隔的**下四分位**（ms）——与 [refreshPeriodMs] 一起区分「整拍上丢了几拍」
         * 与「节拍本身被拉伸」。两者接近 = 样本挤在同一个整拍上；`q1GapMs` 也跟着升高 =
         * 节拍整体上移（真的不是 60Hz）。详见 [StepHistogram.Result.q1Ms]。
         */
        val q1GapMs: Float,
        /** 帧回调间隔的**最大值**（ms）。与 [minGapMs] 一起看展宽：两者都 ≈ [refreshPeriodMs] 才是均匀节拍。 */
        val maxGapMs: Float,
        /** 逐帧时间增量的最小 / 最大（ms）。两者都 ≈ [refreshPeriodMs] 才说明每拍都真的出了一帧。 */
        val stepMinMs: Float,
        val stepMaxMs: Float,
        /**
         * 用进程自己的单调时钟测出的回调间隔：中位数 / 最小 / 最大（ms）。
         *
         * 与 `refreshPeriodMs` / [minGapMs] / [maxGapMs]（系统上报的时间戳）对照：
         * 两组展宽相当 = 时间戳可信、屏幕真的在不等地出帧；本组明显更窄 = 上报的时间戳在抖。
         */
        val dispatchMedianMs: Float,
        val dispatchMinMs: Float,
        val dispatchMaxMs: Float,
        /**
         * 回调从上报的 vsync 时刻到被处理的延迟（ms）：平均 / 最小 / 最大。
         *
         * `lateMaxMs > 一个刷新周期` = 主线程被别的活卡住、vsync 事件被推迟处理
         * （丢拍可能是我们自己造成的）；全程接近恒定 = 主线程没参与丢拍。
         * 本窗口无样本时为 `NaN`（延迟可以为负，所以不能用 -1 当哨兵）。
         */
        val lateAvgMs: Float,
        val lateMinMs: Float,
        val lateMaxMs: Float,
        /** 窗口内「时间轴 - 真实时钟」的漂移（ms）。≈0 说明时间轴速率准，弹幕速度就准。 */
        val rateErrorMs: Float,
        /** 帧回调间隔的档位分布。 */
        val gapHist: StepHistogram.Result,
        /** 出帧（时间轴增量）的档位分布。 */
        val stepHist: StepHistogram.Result,
    )

    private companion object {
        /**
         * 单帧增量上限（0.5s）。正常帧间隔在 2~17ms，长时间挂起才会超过它。
         * 宁可让弹幕落后一点（由 DanmakuTimer 的粗同步兜底），也不要瞬移。
         */
        const val MAX_STEP_NS = 500_000_000L

        /** 连续这么多次回退就认定时间基准换了，重设基准（不推进时间轴）。 */
        const val REGRESS_RUN_LIMIT = 3
    }
}

/**
 * 间隔样本的档位分布（仅诊断）。
 *
 * 归一基准是**本窗口内样本的中位数**（不是最小值），所以「全速节拍」和「本来就隔一拍出帧」
 * 都会落在同一个档里 —— 单看一张分布表分不出它们。它的判别力来自**两张表对照**：
 *
 *  - `gap`（帧回调间隔）均匀、`step`（出帧增量）却出现 2×/3× 档
 *    → **回调是齐的，是我们自己的出帧链掉了拍**。这是应用能修的。
 *  - `gap` 自己就出现 2×/3× 档 → 节拍本来就是系统/合成给的，与应用无关。
 *  - 两张表都只有 1× 档 → 出帧完全均匀，抖只能来自更下游（合成、面板、背光）。
 *
 * **基准为什么必须用中位数**：最小值极易被一个离群样本拉低。实测 TCL 上 vsync 时间戳
 * 里偶尔混进 0.68ms 的样本，用最小值当基准时，同一窗口里 175 个正常的 16.7ms 会被整体
 * 判成「≥4×」（日志里出现 `gap=1/0/0/167` 这种失真），分布表彻底失去信息量。
 * 中位数对少量离群值免疫，而且它本身就直接等于「真实节拍周期」。
 *
 * 各档样本数与总样本数之比就是「掉拍率」，直接可读。
 * 上限 [MAX_SAMPLES] 个样本；超出的只计数不存储（3s 窗口在 120Hz 下也只有 ~360 个）。
 */
internal class StepHistogram {
    private val samplesNs = LongArray(MAX_SAMPLES)
    private var count = 0
    private var overflow = 0

    fun record(deltaNs: Long) {
        if (deltaNs <= 0L) return
        if (count < MAX_SAMPLES) {
            samplesNs[count++] = deltaNs
        } else {
            overflow++
        }
    }

    /** 取走本窗口的统计并开启新窗口。 */
    fun consume(): Result {
        if (count == 0) {
            overflow = 0
            return Result(minMs = -1f, q1Ms = -1f, medianMs = -1f, maxMs = -1f, k1 = 0, k2 = 0, k3 = 0, k4 = 0, overflow = 0)
        }
        // 原地排序即可：区间 [0, count) 下一窗口会从头覆写，元素顺序本身无意义。
        java.util.Arrays.sort(samplesNs, 0, count)
        val minNs = samplesNs[0]
        val q1Ns = samplesNs[count / 4]
        val baseNs = samplesNs[count / 2]
        val maxNs = samplesNs[count - 1]
        // 归一成一档后再分箱；容差 ±40%，避免基准本身偏低时把整档算错。
        val limit2 = baseNs * 14L / 10L
        val limit3 = baseNs * 24L / 10L
        val limit4 = baseNs * 34L / 10L
        var k1 = 0
        var k2 = 0
        var k3 = 0
        var k4 = 0
        for (i in 0 until count) {
            val g = samplesNs[i]
            when {
                g < limit2 -> k1++
                g < limit3 -> k2++
                g < limit4 -> k3++
                else -> k4++
            }
        }
        val result = Result(
            minMs = minNs / 1_000_000f,
            q1Ms = q1Ns / 1_000_000f,
            medianMs = baseNs / 1_000_000f,
            maxMs = maxNs / 1_000_000f,
            k1 = k1,
            k2 = k2,
            k3 = k3,
            k4 = k4,
            overflow = overflow,
        )
        count = 0
        overflow = 0
        return result
    }

    internal data class Result(
        /** 窗口内最短样本（ms）。远小于 [medianMs] 说明混进了离群样本（重复/乱序的时间戳）。 */
        val minMs: Float,
        /**
         * 下四分位（25 分位，ms）—— 与 [medianMs] 一起把两种截然不同的情况分开：
         *
         *  - **节拍是整拍、只是偶尔丢拍**：样本绝大多数落在同一个整拍上（例如 16.667），
         *    那么 `q1Ms ≈ medianMs ≈ 16.667`（丢的那几拍落在 2× 档，进不了四分位）；
         *  - **节拍本身被拉伸**：样本整体上移，`q1Ms` 也跟着上移（例如 17.2 配 17.4）。
         *
         * 只报最小值 / 中位数时这两种情况看起来一模一样（最小值被离群样本毁掉、中位数
         * 单独看不出分布形状），会直接决定「改应用有没有意义」这个结论。
         */
        val q1Ms: Float,
        /** 窗口内样本中位数（ms）= 归一基准 = 真实节拍周期。 */
        val medianMs: Float,
        /** 窗口内最长样本（ms）。 */
        val maxMs: Float,
        /** 约 1× / 2× / 3× / ≥4× 中位数的样本数。 */
        val k1: Int,
        val k2: Int,
        val k3: Int,
        val k4: Int,
        /** 因超出 [MAX_SAMPLES] 未纳入统计的样本数。 */
        val overflow: Int,
    )

    private companion object {
        const val MAX_SAMPLES = 1024
    }
}
