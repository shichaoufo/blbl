package blbl.cat3399.feature.player.danmaku

import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer

/**
 * 「空闲线程 vsync 探针」：在一条**独立的空闲线程**上挂第二个 Choreographer，只量 vsync 间隔。
 *
 * ## 为什么需要它
 *
 * 主线程上那个 Choreographer 有一个必须知道的行为 —— `FrameDisplayEventReceiver.onVsync`
 * 在「上一拍的 doFrame 还没跑」时会**直接丢弃**新到的 vsync（`mHavePendingVsync` 保护，
 * 只打一条 warning）。也就是说：**只要主线程偶尔被卡住超过一个刷新周期，我们自己就会把
 * 那一拍吃掉**，日志里表现为一个 2× 的间隔，紧接着的 `late` 也会鼓起来。
 *
 * 于是「主线程回调里量到的节拍被拉伸 / 丢拍」有两种根因，而它们在**同一条数据流上无法分开**
 * （这正是「掉拍率与 `late` 相关系数 +0.93」那种共变的来源：可能只是同一个共因的两个症状，
 * 也可能就是我们自己在丢）：
 *
 *  1. **显示源本身如此**（面板 / 合成器给的 vsync 节拍就不是 60Hz、或本来就少给拍）→ 应用侧无解；
 *  2. **主线程消费不及时**（我们自己吃掉了拍子）→ 应用能修，方向是压低主线程延迟。
 *
 * 本探针在一条几乎空闲的线程上消费**同一条 vsync 流**，于是把两者分开：
 *
 * ```
 * idleP ≈ vsP 且 idleN ≈ cb                      → 显示源就是这样，应用侧收工
 * idleP ≈ 16.67 而 vsP 更大，或 idleN 明显大于 cb → 主线程在吃拍子，能修
 * ```
 *
 * **`idleN` 与 `cb` 的对照是最硬的一条**：两者都是「本窗口收到的 vsync 条数」，空闲线程不受
 * 我们主线程影响，差值就是**被我们自己吃掉的拍数**。
 *
 * 仅诊断：不参与任何位置计算，运行在独立线程上，且由调用方限定只在 debug 构建启动。
 */
internal class IdleVsyncProbe {
    private val lock = Any()
    private val hist = StepHistogram()

    private var thread: HandlerThread? = null
    private var choreographer: Choreographer? = null

    /** 上一次回调的 vsync 时间戳。只在探针线程上读写。 */
    private var lastNs: Long = 0L

    /** 本窗口收到的 vsync 条数（与主线程的 `cb` 对照，差值 = 我们自己吃掉的拍数）。 */
    private var frames: Int = 0

    /**
     * 是否正在消费。**必须用它挡住重复注册**：Choreographer 允许同一个 FrameCallback 对象
     * 被注册多次，那样每拍会回调两次，间隔统计里会混进一堆 ~0ms 的假样本，把四分位彻底带偏。
     * 主线程写、探针线程读。
     */
    @Volatile
    private var running: Boolean = false

    private val callback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                val prev = lastNs
                lastNs = frameTimeNanos
                synchronized(lock) {
                    frames++
                    if (prev > 0L) {
                        val d = frameTimeNanos - prev
                        if (d > 0L) hist.record(d)
                    }
                }
                choreographer?.postFrameCallback(this)
            }
        }

    /** 开始（或从 [pause] 恢复）消费 vsync。线程只在第一次创建，暂停/恢复不churn线程。 */
    fun start() {
        if (running) return
        running = true
        val existing = thread
        if (existing == null) {
            val t = HandlerThread("Danmaku-IdleVsync")
            t.start()
            thread = t
            Handler(t.looper).post {
                // Choreographer 是 ThreadLocal，必须在目标线程上取。
                choreographer = Choreographer.getInstance()
                lastNs = 0L
                if (running) choreographer?.postFrameCallback(callback)
            }
        } else {
            Handler(existing.looper).post {
                // 清零基准：暂停期间没有回调，沿用旧时间戳会把整段暂停当成一帧。
                lastNs = 0L
                if (running) choreographer?.postFrameCallback(callback)
            }
        }
    }

    /** 暂停消费（保留线程）。暂停期间没有 vsync 样本。 */
    fun pause() {
        if (!running) return
        running = false
        val t = thread ?: return
        val c = choreographer ?: return
        Handler(t.looper).post { c.removeFrameCallback(callback) }
    }

    /** 关掉线程。释放时调用。 */
    fun shutdown() {
        val wasRunning = running
        running = false
        val t = thread ?: return
        thread = null
        val c = choreographer
        choreographer = null
        Handler(t.looper).post {
            if (wasRunning || c != null) c?.removeFrameCallback(callback)
            t.quitSafely()
        }
    }

    /** 取走本窗口的统计并开启新窗口。 */
    fun consume(): Stats =
        synchronized(lock) {
            val r = hist.consume()
            val n = frames
            frames = 0
            Stats(medianMs = r.medianMs, q1Ms = r.q1Ms, minMs = r.minMs, maxMs = r.maxMs, count = n)
        }

    internal data class Stats(
        /** 空闲线程上量到的 vsync 间隔中位数（ms）；-1 = 本窗口无样本（探针未启动）。 */
        val medianMs: Float,
        /** 空闲线程上量到的 vsync 间隔下四分位（ms）。与 [medianMs] 一起看分布形状。 */
        val q1Ms: Float,
        val minMs: Float,
        val maxMs: Float,
        /** 空闲线程本窗口收到的 vsync 条数。与主线程 `cb` 对照：差值就是被我们自己吃掉的拍数。 */
        val count: Int,
    )
}
