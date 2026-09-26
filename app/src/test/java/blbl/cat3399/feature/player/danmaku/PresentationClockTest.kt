package blbl.cat3399.feature.player.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * 时间轴只有一条硬指标：**增量恒等于真实流逝的时间**。
 *
 * 因为 DanmakuTimer 是累加增量的，时间轴走快 1% 弹幕就快 1% ——「时快时慢」就是
 * 时间轴的速率在抖动。所以这里的用例几乎都在测同一件事：无论绘制相位怎么漂、
 * 帧回调怎么跳拍，累计增量必须严格等于 vsync 时间戳之差之和。
 */
class PresentationClockTest {
    /** 120Hz —— 反馈「时快时慢」的那台电视（TCL Q10G）就是这个档位。 */
    private val period = 8_333_333L

    /** onDraw 相对 vsync 的偏移：真实设备上随 View 树遍历工作量浮动。 */
    private val drawOffset = 1_500_000L

    @Test
    fun firstCallStartsFromRealClock() {
        val clock = PresentationClock()

        assertEquals(123_456_789L, clock.presentationNs(123_456_789L))
    }

    /** 还没有两拍时间戳时（起播最初一帧）跟随单调时钟，保证增量连续。 */
    @Test
    fun followsRealClockUntilTwoVsyncSamples() {
        val clock = PresentationClock()

        clock.presentationNs(1_000_000L)
        assertEquals(2_500_000L, clock.presentationNs(2_500_000L))

        // 有了帧基准后，单调时钟不再推进时间轴（只剩一拍的基准，还没增量）
        clock.onVsync(10_000_000L)
        assertEquals(2_500_000L, clock.presentationNs(2_600_000L))
    }

    /**
     * 核心收益：主线程绘制相位在 ±3.5ms 内浮动（4K 电视上很常见，120Hz 下已接近半个
     * 刷新周期），逐帧增量仍严格等于一个刷新周期。
     *
     * 上一版在这里失败两次：网格吸附版的容差随周期变短（120Hz 只剩 4.2ms），
     * 序号驱动版又把绘制相位灌进了**速率**（(now - desired)/8 的慢速校正）。
     */
    @Test
    fun twitchyDrawPhaseDoesNotLeakIntoStepSize() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        var now = vsync + drawOffset

        clock.onVsync(vsync)
        vsync += period
        clock.onVsync(vsync)
        var prev = clock.presentationNs(now)

        val rnd = Random(20260926)
        var worst = 0L
        repeat(120) {
            vsync += period
            clock.onVsync(vsync)
            val jitter = ((rnd.nextDouble() * 2.0 - 1.0) * 3_500_000.0).toLong()
            now = vsync + drawOffset + jitter
            val cur = clock.presentationNs(now)
            val deviation = abs((cur - prev) - period)
            if (deviation > worst) worst = deviation
            prev = cur
        }

        assertTrue(
            "绘制相位浮动不得进入逐帧增量，实测最大偏差 ${worst / 1_000_000.0}ms",
            worst == 0L,
        )
    }

    /**
     * 速率是硬指标：让绘制相位**缓慢**漂移（这正是「时快时慢」最刺眼的频段），
     * 累计增量必须严格等于真实经过的 vsync 时间。
     *
     * 序号驱动版在这里会走慢：它按「回调次数 × 周期」推进，只要回调链跳拍就少一拍。
     */
    @Test
    fun slowDrawPhaseDriftDoesNotChangeRate() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        val steps = 600

        clock.onVsync(vsync)
        var last = clock.presentationNs(vsync + drawOffset)
        for (i in 1..steps) {
            vsync += period
            clock.onVsync(vsync)
            // 绘制相位在 8 秒里缓慢来回漂 ±3ms
            val phase = 1_500_000L + ((kotlin.math.sin(i / 60.0) * 3_000_000.0).toLong())
            last = clock.presentationNs(vsync + phase)
        }

        assertEquals(
            "累计增量必须严格等于真实经过的 vsync 时间",
            (steps.toLong()) * period,
            last - (1_000_000_000L + drawOffset),
        )
    }

    /**
     * 帧回调链跳拍（注册下一帧的动作发生在 act 完成之后，超过一个刷新周期就跳一拍）：
     * 上报的时间戳本身多走了一拍，时间轴必须自动补齐 —— 不能少走。
     *
     * 上一版按回调**次数**推进，这里会少走一整拍，弹幕突然变慢。
     */
    @Test
    fun skippedVsyncCallbackAdvancesByRealElapsedTime() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L

        clock.onVsync(vsync)
        vsync += period
        clock.onVsync(vsync)
        var prev = clock.presentationNs(vsync + drawOffset)

        repeat(10) {
            vsync += period
            clock.onVsync(vsync)
            prev = clock.presentationNs(vsync + drawOffset)
        }

        // 这一拍的回调被跳过了：下一次上报的时间戳直接跨了两拍
        vsync += period
        vsync += period
        clock.onVsync(vsync)
        val after = clock.presentationNs(vsync + drawOffset)

        assertEquals("跳一拍就该补一拍", period * 2, after - prev)
    }

    /** 正常丢帧（时间戳跨两拍）与跳拍是同一件事，必须补足两拍。 */
    @Test
    fun droppedVsyncAdvancesExactlyTwoPeriods() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        repeat(6) {
            vsync += period
            clock.onVsync(vsync)
        }
        var prev = clock.presentationNs(vsync + drawOffset)
        repeat(4) {
            vsync += period
            clock.onVsync(vsync)
            prev = clock.presentationNs(vsync + drawOffset)
        }

        vsync += period * 2
        clock.onVsync(vsync)
        val after = clock.presentationNs(vsync + drawOffset)

        assertEquals(period * 2, after - prev)
    }

    /** 同一帧内的重复绘制（位图烘焙完成、父容器重绘都会额外触发 onDraw）不得推进时间轴。 */
    @Test
    fun repeatedDrawWithinSameVsyncDoesNotAdvanceTimeline() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        repeat(3) {
            vsync += period
            clock.onVsync(vsync)
        }

        val first = clock.presentationNs(vsync + drawOffset)
        val second = clock.presentationNs(vsync + drawOffset + 900_000L)
        val third = clock.presentationNs(vsync + drawOffset + 1_800_000L)

        assertEquals(first, second)
        assertEquals(first, third)
    }

    @Test
    fun timelineNeverRegresses() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        var now = vsync + drawOffset

        clock.onVsync(vsync)
        vsync += period
        clock.onVsync(vsync)
        var prev = clock.presentationNs(now)

        repeat(240) { i ->
            if (i % 3 == 0) {
                val again = clock.presentationNs(now + 800_000L)
                assertTrue("位置时间轴不得回退", again >= prev)
                prev = again
            } else {
                now += period
                vsync += period
                clock.onVsync(vsync)
                val next = clock.presentationNs(now)
                assertTrue("位置时间轴不得回退", next >= prev)
                prev = next
            }
        }
    }

    /** 时间戳回退（非本帧 vsync）必须被丢弃，且不得污染基准。 */
    @Test
    fun regressingTimestampIsIgnored() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        repeat(3) {
            vsync += period
            clock.onVsync(vsync)
        }
        val before = clock.presentationNs(vsync + drawOffset)

        clock.onVsync(vsync - period) // 回退的一拍
        val mid = clock.presentationNs(vsync + drawOffset + 100L)
        assertEquals("回退的时间戳不得推进时间轴", before, mid)

        clock.onVsync(vsync + period) // 正常的一拍
        val after = clock.presentationNs(vsync + drawOffset + period)
        assertEquals("回退样本不得污染下一拍的基准", before + period, after)
    }

    /** 长时间挂起（系统暂停、surface 重建）时只补一帧，避免弹幕瞬移。 */
    @Test
    fun hugeGapIsCapped() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        repeat(3) {
            vsync += period
            clock.onVsync(vsync)
        }
        val before = clock.presentationNs(vsync + drawOffset)

        vsync += 30_000_000_000L // 挂了 30 秒
        clock.onVsync(vsync)
        val after = clock.presentationNs(vsync + drawOffset)

        assertTrue("不得把挂起时长整段灌进位置，实测 ${(after - before) / 1_000_000.0}ms", after - before <= 500_000_000L)
    }

    /**
     * 暂停后恢复：reset() 之后从新的时间戳续接，不得把暂停时长当成一帧的增量。
     */
    @Test
    fun resetResumesWithoutJump() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        repeat(4) {
            vsync += period
            clock.onVsync(vsync)
        }
        var now = vsync + drawOffset
        clock.presentationNs(now)

        clock.reset()
        // 暂停 5 秒，期间只有 onDraw 在跑（跟随单调时钟）
        now += 5_000_000_000L
        val pausedVal = clock.presentationNs(now)

        // 恢复：帧回调重新建立基准
        vsync = now + 1_000_000L
        clock.onVsync(vsync)
        vsync += period
        clock.onVsync(vsync)
        val resumed = clock.presentationNs(vsync + drawOffset)

        assertTrue(
            "恢复后单帧增量不得包含暂停时长，实测 ${(resumed - pausedVal) / 1_000_000.0}ms",
            resumed - pausedVal <= 2 * period,
        )
    }

    /** 诊断统计：刷新周期取窗口内间隔的**中位数**，速率误差应约为 0。 */
    @Test
    fun statsReportRefreshPeriodAndRateError() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        var now = vsync + drawOffset

        // 第一拍只建立基准（没有上一拍可比），不计入增量 —— 这是一个恒定偏移，无害。
        clock.onVsync(vsync)
        clock.presentationNs(now)
        clock.consumeStats(now) // 建立速率误差基准

        repeat(120) {
            vsync += period
            clock.onVsync(vsync)
            now = vsync + drawOffset
            clock.presentationNs(now)
        }

        val stats = clock.consumeStats(now)
        assertEquals(period / 1_000_000f, stats.refreshPeriodMs, 0.01f)
        assertEquals(period / 1_000_000f, stats.stepMinMs, 0.01f)
        assertEquals(period / 1_000_000f, stats.stepMaxMs, 0.01f)
        assertTrue("速率误差应约为 0，实测 ${stats.rateErrorMs}ms", abs(stats.rateErrorMs) < 1f)
    }

    /**
     * 均匀节拍：所有样本都应落在 1× 档，其余三档为 0。
     *
     * 这是「出帧完全均匀」的基线 —— 一旦它不成立，抖就一定来自出帧链或系统节拍，
     * 而不是别处。
     */
    @Test
    fun histogramIsAllFirstBucketWhenCadenceIsUniform() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        clock.onVsync(vsync)
        repeat(60) {
            vsync += period
            clock.onVsync(vsync)
        }

        val h = clock.consumeStats(vsync).gapHist
        assertEquals(60, h.k1)
        assertEquals(0, h.k2)
        assertEquals(0, h.k3)
        assertEquals(0, h.k4)
        assertEquals(period / 1_000_000f, h.minMs, 0.01f)
        assertEquals(period / 1_000_000f, h.medianMs, 0.01f)
        assertEquals(period / 1_000_000f, h.maxMs, 0.01f)
    }

    /**
     * 离群样本不能带偏整张分布表 —— 这是真机踩到的坑（2026-09-26，TCL）。
     *
     * 那台设备的 vsync 时间戳偶尔会混进不到 1ms 的样本。若用「窗口内最小值」做归一基准，
     * 同一窗口里几十个正常的 16.7ms 会被**整体**判成「≥4×」——真机日志里出现过
     * `vsP=1.28 gap=1/0/0/167`，175 个正常样本全被算进 ≥4× 档，分布表彻底失去信息量。
     * 基准改取中位数后，正常样本必须仍然落在 1× 档；而离群值本身也归 1× 档（它比基准小，
     * 无害），不会被误报成「掉拍」。
     */
    @Test
    fun outlierSampleDoesNotShiftTheDistribution() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        clock.onVsync(vsync)
        repeat(60) {
            vsync += period
            clock.onVsync(vsync)
        }
        // 插一个离群的时间戳（不足 1ms 的伪间隔）
        vsync += 700_000L
        clock.onVsync(vsync)
        vsync += period
        clock.onVsync(vsync)

        val h = clock.consumeStats(vsync).gapHist
        assertEquals("61 个正常样本 + 1 个离群样本都该留在 1× 档", 62, h.k1)
        assertEquals(0, h.k2)
        assertEquals(0, h.k3)
        assertEquals(0, h.k4)
        assertEquals("最小值用来暴露离群样本", 0.7f, h.minMs, 0.05f)
        assertEquals("中位数仍然是真实周期", period / 1_000_000f, h.medianMs, 0.01f)
    }

    /**
     * 偶发漏一拍：多出来的样本必须落在 2× 档，且**不能**把 1× 档算多 ——
     * 掉拍率就是 `k2 / (k1 + k2 + k3 + k4)`，数错档这率就没有意义。
     */
    @Test
    fun histogramCountsSlippedBeatsInSecondBucket() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        clock.onVsync(vsync)
        var slips = 0
        repeat(100) { i ->
            if (i % 25 == 24) {
                vsync += period * 2
                slips++
            } else {
                vsync += period
            }
            clock.onVsync(vsync)
        }

        val h = clock.consumeStats(vsync).gapHist
        assertEquals(100 - slips, h.k1)
        assertEquals(slips, h.k2)
        assertEquals(0, h.k3)
        assertEquals(0, h.k4)
    }

    /**
     * 下四分位的用途：把「节拍是整拍、只是偶尔丢了几拍」与「节拍本身被拉伸」分开。
     *
     * 真机背景（2026-09-26，TCL）：直播时 `vsP` 中位是 17.41ms（57.4Hz）而不是 16.67，
     * 而这两种可能的原因必须分清 ——
     *  - 面板其实还是 60Hz 整拍（16.667），只是有一部分拍没送到应用：
     *    样本绝大多数仍落在 16.667 上，**Q1 与中位数都会是 16.667**；
     *  - 节拍真的被拉伸到 17.4：**Q1 会跟着中位数一起升高**。
     *
     * 只报中位数时这两种情况看起来一模一样，而它们直接决定「改应用有没有意义」。
     */
    @Test
    fun firstQuartileSeparatesSlippedBeatsFromStretchedCadence() {
        val grid = 16_666_667L

        // 情形一：60Hz 整拍 + 每 25 拍丢一拍 —— Q1 必须仍停在整拍上。
        run {
            val clock = PresentationClock()
            var vsync = 1_000_000_000L
            clock.onVsync(vsync)
            repeat(100) { i ->
                vsync += if (i % 25 == 24) grid * 2 else grid
                clock.onVsync(vsync)
            }
            val h = clock.consumeStats(vsync).gapHist
            assertEquals("整拍上的 Q1 不该被少数丢拍抬起", grid / 1_000_000f, h.q1Ms, 0.01f)
            assertEquals(grid / 1_000_000f, h.medianMs, 0.01f)
            assertEquals(4, h.k2)
        }

        // 情形二：节拍整体被拉伸（一拍不丢，但每一拍都是 17.4ms）—— Q1 必须跟着升上去。
        run {
            val clock = PresentationClock()
            val stretched = 17_400_000L
            var vsync = 1_000_000_000L
            clock.onVsync(vsync)
            repeat(100) {
                vsync += stretched
                clock.onVsync(vsync)
            }
            val h = clock.consumeStats(vsync).gapHist
            assertEquals("拉伸的节拍里 Q1 必须跟着中位数一起升高", stretched / 1_000_000f, h.q1Ms, 0.05f)
            assertEquals(stretched / 1_000_000f, h.medianMs, 0.01f)
            assertEquals(0, h.k2)
            assertEquals(0, h.k3)
            assertEquals(0, h.k4)
        }
    }

    /** 连丢两拍落在 3× 档；更长的空档归到 ≥4×，不能与 3× 混在一起。 */
    @Test
    fun histogramSeparatesThreeAndFourPlusBuckets() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        clock.onVsync(vsync)
        repeat(20) {
            vsync += period
            clock.onVsync(vsync)
        }
        vsync += period * 3 // 连丢两拍
        clock.onVsync(vsync)
        vsync += period * 5 // 长空档
        clock.onVsync(vsync)
        vsync += period
        clock.onVsync(vsync)

        val h = clock.consumeStats(vsync).gapHist
        assertEquals(21, h.k1)
        assertEquals(0, h.k2)
        assertEquals(1, h.k3)
        assertEquals(1, h.k4)
    }

    /** 统计必须按窗口重置：上一窗口的样本不能漏进下一窗口。 */
    @Test
    fun histogramResetsBetweenWindows() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        clock.onVsync(vsync)
        repeat(30) {
            vsync += period
            clock.onVsync(vsync)
        }
        assertEquals(30, clock.consumeStats(vsync).gapHist.k1)

        val empty = clock.consumeStats(vsync).gapHist
        assertEquals(-1f, empty.minMs, 0.0001f)
        assertEquals(0, empty.k1)

        repeat(7) {
            vsync += period
            clock.onVsync(vsync)
        }
        assertEquals(7, clock.consumeStats(vsync).gapHist.k1)
    }

    /**
     * 每拍都真的出了一帧时，`step` 与 `gap` 的分布必须一致。
     *
     * 这张「两张表相等」的断言就是出帧链的回归防线：哪天出帧链开始掉拍，
     * `gap` 还是均匀的，而 `step` 会先出现 2× 档。
     */
    @Test
    fun stepHistogramMatchesGapHistogramWhenEveryVsyncIsDrawn() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        var now = vsync + drawOffset
        clock.onVsync(vsync)
        clock.presentationNs(now)
        repeat(90) {
            vsync += period
            clock.onVsync(vsync)
            now = vsync + drawOffset
            clock.presentationNs(now)
        }

        val stats = clock.consumeStats(now)
        assertEquals(stats.gapHist.k1, stats.stepHist.k1)
        assertEquals(stats.gapHist.k2, stats.stepHist.k2)
        assertEquals(stats.gapHist.k3, stats.stepHist.k3)
        assertEquals(stats.gapHist.k4, stats.stepHist.k4)
    }

    /**
     * 掉拍时 `step` 必须落在 2× 档 —— 这正是「应用自己掉拍」的指纹：
     * 回调照旧每拍都有，只是那一次没画出来，于是出帧增量跨了两拍。
     */
    @Test
    fun stepHistogramFlagsDroppedDraw() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        var now = vsync + drawOffset
        clock.onVsync(vsync)
        clock.presentationNs(now)
        var drawn = 0
        repeat(40) { i ->
            vsync += period
            clock.onVsync(vsync)
            if (i == 20) return@repeat // 这一拍没画（出帧链掉拍）
            drawn++
            now = vsync + drawOffset
            clock.presentationNs(now)
        }

        val stats = clock.consumeStats(now)
        assertEquals(40, stats.gapHist.k1) // 回调一拍不落
        assertEquals(0, stats.gapHist.k2)
        assertEquals(drawn - 1, stats.stepHist.k1) // 第一次交付只建立基准，不计增量
        assertEquals(1, stats.stepHist.k2) // 唯一那次掉拍
    }

    /**
     * 双时钟对照：进程单调时钟测出的回调间隔（`dispatch*`）必须与系统上报的时间戳（`vs*`）
     * **各记一张表**，且两者可以给出完全不同的展宽。
     *
     * 这是区分「屏幕真的在不等地出帧」与「上报的时间戳被修饰过」的唯一手段 ——
     * 前者两张表一样宽，后者只有 `vs*` 宽。用例故意让两者不一致：时间戳乱跳，实际投递严格等间隔。
     */
    @Test
    fun dispatchHistogramIsMeasuredIndependentlyOfVsyncTimestamp() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        var now = vsync + drawOffset
        clock.onVsync(vsync)
        clock.recordDispatch(now, vsync)
        repeat(60) { i ->
            // 时间戳在 ±3ms 之间来回跳：相邻两拍的差因此是 period±3ms（展宽 6ms）。
            vsync += period + if (i % 2 == 0) 3_000_000L else -3_000_000L
            now += period
            clock.onVsync(vsync)
            clock.recordDispatch(now, vsync)
        }

        val s = clock.consumeStats(now)
        assertTrue(
            "上报的时间戳乱跳，vsync 表必须被撑开，实测 ${s.maxGapMs - s.minGapMs}ms",
            s.maxGapMs - s.minGapMs > 4f,
        )
        assertEquals("进程自己的时钟没抖，投递表必须很窄", period / 1_000_000f, s.dispatchMedianMs, 0.01f)
        assertEquals(period / 1_000_000f, s.dispatchMinMs, 0.01f)
        assertEquals(period / 1_000_000f, s.dispatchMaxMs, 0.01f)
    }

    /**
     * `late` = 回调从上报的 vsync 时刻到被处理的延迟，窗口内取平均 / 最值，并按窗口重置。
     *
     * 它是「丢拍是不是我们自己造成的」的直接判据：主线程被卡住时 vsync 事件会被推迟处理，
     * `lateMax` 就会鼓出一整个刷新周期。
     */
    @Test
    fun dispatchLateIsAveragedPerWindowAndResets() {
        val clock = PresentationClock()
        var vsync = 1_000_000_000L
        clock.onVsync(vsync)
        clock.recordDispatch(vsync + 2_000_000L, vsync)
        vsync += period
        clock.onVsync(vsync)
        clock.recordDispatch(vsync + 6_000_000L, vsync)

        val s = clock.consumeStats(vsync)
        assertEquals(2f, s.lateMinMs, 0.01f)
        assertEquals(6f, s.lateMaxMs, 0.01f)
        assertEquals(4f, s.lateAvgMs, 0.01f)

        // 新窗口没有样本 → NaN。不能用 -1 当哨兵：延迟本身可以是负的。
        val empty = clock.consumeStats(vsync)
        assertTrue(empty.lateAvgMs.isNaN())
        assertTrue(empty.lateMinMs.isNaN())
        assertTrue(empty.lateMaxMs.isNaN())
    }

    /** `reset()` 必须连投递时刻基准一起丢掉，否则恢复后第一帧会把整个暂停时长记成一次间隔。 */
    @Test
    fun resetDropsDispatchBase() {
        val clock = PresentationClock()
        var now = 1_000_000_000L
        clock.onVsync(now)
        clock.recordDispatch(now, now)

        clock.reset()
        now += 5_000_000_000L // 暂停 5 秒
        clock.onVsync(now)
        clock.recordDispatch(now, now)

        assertEquals("暂停时长不得成为一次间隔样本", -1f, clock.consumeStats(now).dispatchMinMs, 0.0001f)
    }
}
