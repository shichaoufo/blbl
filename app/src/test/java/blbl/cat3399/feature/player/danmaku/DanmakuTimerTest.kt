package blbl.cat3399.feature.player.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuTimerTest {
    @Test
    fun speedChangesKeepPositionContinuousWhenRawClockLags() {
        val timer = DanmakuTimer()

        assertEquals(10_000L, timer.step(ns(0), 10_000L, true, 1f, 0))
        assertEquals(10_100L, timer.step(ns(100), 10_080L, true, 3f, 0))
        assertEquals(10_400L, timer.step(ns(200), 10_320L, true, 3f, 0))

        val released = timer.step(ns(300), 10_550L, true, 1f, 0)
        assertEquals(10_700L, released)
        assertEquals(10_800L, timer.step(ns(400), 10_650L, true, 1f, 0))
    }

    @Test
    fun pauseAndResumeDoNotPullDanmakuBackwards() {
        val timer = DanmakuTimer()

        timer.step(ns(0), 20_000L, true, 2f, 0)
        assertEquals(20_200L, timer.step(ns(100), 20_150L, true, 2f, 0))
        assertEquals(20_200L, timer.step(ns(200), 20_170L, false, 2f, 0))
        assertEquals(20_200L, timer.step(ns(300), 20_180L, true, 2f, 0))
        assertEquals(20_400L, timer.step(ns(400), 20_350L, true, 2f, 0))
    }

    @Test
    fun explicitSeekCanMoveDanmakuBackwards() {
        val timer = DanmakuTimer()

        timer.step(ns(0), 30_000L, true, 1f, 0)
        timer.step(ns(100), 30_100L, true, 1f, 0)

        assertEquals(5_000L, timer.step(ns(200), 5_000L, true, 1f, 1))
    }

    @Test
    fun unreportedForwardJumpStillReanchors() {
        val timer = DanmakuTimer()

        timer.step(ns(0), 1_000L, true, 1f, 0)
        val position = timer.step(ns(100), 4_000L, true, 1f, 0)

        assertEquals(4_000L, position)
        assertTrue(position >= timer.currentPositionMs())
    }

    /**
     * 位移是否顺滑，第一个前提是喂进来的时间戳等距。
     *
     * 旧实现把 onDraw 里现取的 System.nanoTime() 交给 [DanmakuTimer]；onDraw 相对
     * vsync 有一份随 View 树遍历工作量浮动的偏移（毫秒级），逐帧 dt 因此时大时小，
     * 位置步长跟着忽长忽短。时间轴改由 [PresentationClock] 按帧回调上报的 vsync
     * 时间戳之差推进后，dt 恒等于真实帧间隔 —— 下面用同一套平滑逻辑跑两种输入，
     * 把差异量化出来。
     *
     * 注意这只是必要条件：位置本身还必须保留亚毫秒精度，否则恒定的 dt 会被量化成
     * 周期性步长，反而比随机抖动更刺眼（见 DanmakuScrollMathTest）。
     */
    @Test
    fun uniformFrameTimestampsProduceUniformMotion() {
        val steady = motionDeltasMs(jitterNs = 0L)
        val jittered = motionDeltasMs(jitterNs = 3_000_000L)

        assertEquals(0L, spread(steady))
        assertTrue(
            "采样时刻抖动应明显破坏位移均匀性：steady=${spread(steady)} jittered=${spread(jittered)}",
            spread(jittered) > spread(steady) + 5L,
        )
    }

    /**
     * 按 [periodNs] 逐帧推进；[jitterNs] 模拟 onDraw 相对 vsync 的浮动偏移（正负交替）。
     * 返回逐帧位置增量（ms）。
     */
    private fun motionDeltasMs(
        periodNs: Long = 10_000_000L,
        frames: Int = 24,
        jitterNs: Long = 0L,
    ): List<Long> {
        val timer = DanmakuTimer()
        val deltas = ArrayList<Long>(frames)
        var elapsedNs = 0L
        var lastPosition = 0L
        for (i in 0 until frames) {
            elapsedNs += periodNs
            val offset = if (i % 2 == 0) jitterNs else -jitterNs
            val position =
                timer.step(
                    nowNanos = elapsedNs + offset,
                    rawPositionMs = elapsedNs / 1_000_000L,
                    isPlaying = true,
                    playbackSpeed = 1f,
                    seekSerial = 0,
                )
            if (i > 0) deltas.add(position - lastPosition)
            lastPosition = position
        }
        return deltas
    }

    private fun spread(values: List<Long>): Long =
        if (values.isEmpty()) 0L else (values.maxOrNull() ?: 0L) - (values.minOrNull() ?: 0L)

    private fun ns(milliseconds: Long): Long = milliseconds * 1_000_000L + 1L
}
