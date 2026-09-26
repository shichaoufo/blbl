package blbl.cat3399.feature.player.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

/**
 * 「看起来顺不顺」的最后一道关口：位置时间轴的精度。
 *
 * 逐帧位移是 elapsed 的**差分**。把时间轴换成严格恒定的 vsync 节拍只是前半步 ——
 * 若位置仍被量化成整数毫秒，120Hz 下恒定的 8.333ms 帧间隔会被量化成 8、8、9、8、8、9…，
 * 位移出现「每三帧多走一点」的规律性节拍。恒定的周期性误差比随机抖动刺眼得多
 * （随机噪声会被视觉系统平均掉），所以只换时间基准反而可能**更抖**。
 */
class DanmakuScrollMathTest {
    private val width = 1920

    /** 约 8 秒穿屏 1920px。 */
    private val pxPerMs = 0.24f

    private val frameMs = 1_000.0 / 120.0

    @Test
    fun integerMillisecondAxisProducesPeriodicStepPattern() {
        val steps = scrollSteps(quantizeToMs = true)
        val spread = spread(steps)

        assertTrue(
            "整数毫秒会把恒定帧间隔量化成周期性步长，spread=$spread steps=${steps.take(6)}",
            spread > 0.1f,
        )
    }

    @Test
    fun subMillisecondAxisProducesUniformSteps() {
        val steps = scrollSteps(quantizeToMs = false)
        val spread = spread(steps)

        assertEquals("亚毫秒时间轴应得到严格均匀的位移", 0f, spread, 1e-4f)
    }

    /** 按 [frameMs] 逐帧推进，返回每帧的向左位移（px）。 */
    private fun scrollSteps(
        quantizeToMs: Boolean,
        frames: Int = 36,
    ): List<Float> {
        var elapsedMs = 0.0
        var prevX = Float.NaN
        val steps = ArrayList<Float>(frames)
        repeat(frames) {
            elapsedMs += frameMs
            // 模拟把位置交给引擎时的整数毫秒截断（旧行为：Long/Int 毫秒）
            val nowMs = if (quantizeToMs) floor(elapsedMs) else elapsedMs
            val x = danmakuScrollX(width = width, nowMs = nowMs, startTimeMs = 0, pxPerMs = pxPerMs)
            if (!prevX.isNaN()) steps += prevX - x
            prevX = x
        }
        return steps
    }

    private fun spread(values: List<Float>): Float =
        (values.maxOrNull() ?: 0f) - (values.minOrNull() ?: 0f)
}
