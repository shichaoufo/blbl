package blbl.cat3399.feature.player.danmaku

import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.Choreographer
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.feature.player.danmaku.model.RenderSnapshot
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * AkDanmaku-style player loop:
 * - Choreographer drives frame pacing (vsync).
 * - ActionThread does per-frame act/update.
 * - Main thread does draw.
 * - Semaphore provides backpressure between draw and act to avoid update piling up.
 */
internal class DanmakuPlayer(
    private val view: DanmakuView,
) {
    companion object {
        private const val TAG = "DanmakuPlayer"

        private const val MSG_FRAME_UPDATE = 2101
        private const val MSG_OP_SET = 3101
        private const val MSG_OP_APPEND = 3102
        private const val MSG_OP_TRIM_RANGE = 3103
        private const val MSG_OP_TRIM_MAX = 3104
        private const val MSG_OP_SEEK = 3105
        private const val MSG_OP_CLEAR = 3106
        private const val MSG_OP_VIEWPORT = 3201
        private const val MSG_OP_CONFIG = 3202
        private const val MSG_OP_RELEASE = 3999
    }

    private val cacheManager =
        CacheManager(
            density = view.resources.displayMetrics.density,
            mainLooper = Looper.getMainLooper(),
            onRenderSign = { view.invalidateDanmakuAreaOnAnimation() },
        )

    private val engineMain: DanmakuEngineMainApi
    private val engineAction: DanmakuEngineActionApi
    private val timer = DanmakuTimer()

    private val drawSemaphore = Semaphore(0)

    private val actionThread = HandlerThread("Danmaku-Action").apply { start() }
    private val actionHandler = ActionHandler(actionThread.looper)

    // ---- 弹幕位置的时间基准 ----
    //
    // 位置是时间的函数，逐帧位移 = 时间增量 × 速度；增量不均匀，位移就不均匀。
    // 而 DanmakuTimer 是**累加**增量的（smoothPositionMs += dt × speed），所以时间轴的
    // 速率误差会 1:1 变成弹幕速度误差 —— 时间轴走快一点，弹幕就快一点。
    //
    // 因此时间轴只认帧回调上报的 vsync 时间戳**之差**，不认「回调次数 × 刷新周期」：
    // 回调链一旦跳拍（注册下一帧的动作发生在 act 完成之后），次数就少一拍，
    // 时间轴会整体走慢，只靠慢速锚点校正追回来 —— 那正是「时快时慢」的来源。
    // 完整推导与两版失败方案见 PresentationClock。
    private val frameClock = PresentationClock()

    /**
     * 空闲线程上的第二个 Choreographer（仅诊断，仅 debug）。
     *
     * 用来判定「节拍被拉伸 / 丢拍」到底是**显示源本身如此**，还是**我们主线程消费不及时吃掉了拍子**
     * —— 后者能修。判据见 [IdleVsyncProbe]：`idleN` 与主线程 `cb` 的差值就是被我们自己吃掉的拍数。
     */
    private val idleVsyncProbe = IdleVsyncProbe()

    /**
     * 逐 vsync 回调：推进时间轴，并通知 ActionThread 做这一帧的 act。
     *
     * 必须挂在**主线程**：它与 onDraw 同属一个 vsync（ANIMATION 阶段早于 TRAVERSAL 阶段），
     * 所以每次 onDraw 读到的就是本拍的时间戳。挂在 ActionThread 上时，那个线程正卡在
     * `drawSemaphore.acquire()` 里，回调只能等主线程画完才被派发 —— onDraw 读到的序号
     * 时而落后一拍、时而连跳两拍，时间轴 0 / 2 拍交替，弹幕就一顿一窜。
     *
     * 主线程繁忙被推迟时，Choreographer 上报的时间戳本身就是更晚的那一拍，
     * 时间轴按真实经过的时间补齐，不丢时间。
     */
    private val frameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                // 回调入口处的进程单调时钟（仅诊断）。与系统上报的 vsync 时间戳一起交给
                // 时间轴做对照，用来判断「逐帧增量不齐」是屏幕真的不均匀，还是时间戳被修饰过。
                val nowNs = System.nanoTime()
                frameCallbackCount++
                // 出帧链保持原版形态：本拍不在主线程请求重绘，重绘交给 ActionThread 的
                // act() 末尾（见 MSG_FRAME_UPDATE 分支），即「迟一拍」。
                //
                // 曾改成在主线程 doFrame 里直接 invalidate（5562ef7），**已回退**：
                // A/B 实测（同机、同为 TextureView 的干净 60Hz，只换这两处行为）显示
                //   主线程 invalidate：cb=4293 step=4294 → 丢帧 -0.02%，lateMax 4.80ms
                //   Action 线程旧链：  cb=4047 step=4044 → 丢帧 +0.07%，lateMax 3.35ms
                // 全在噪声里。旧链的「迟一拍」在干净节拍下只是恒定延迟、不掉帧；只有在
                // 节拍被拉长时（SurfaceView 等帧，实测 4.2%）才会与下一拍合并成真丢帧 ——
                // 而那件事已经由「渲染视图默认改 TextureView」解决。零收益，故回退。
                frameClock.onVsync(frameTimeNanos)
                frameClock.recordDispatch(nowNs, frameTimeNanos)
                if (started && !released) {
                    Choreographer.getInstance().postFrameCallback(this)
                    actionHandler.removeMessages(MSG_FRAME_UPDATE)
                    actionHandler.sendEmptyMessage(MSG_FRAME_UPDATE)
                }
            }
        }

    /**
     * 帧回调次数（窗口内）。**只在主线程读写**，不需要同步。
     *
     * 与日志里的出帧数（`fps` × 窗口时长）对照：两者相等 = 每次回调都画了一帧；
     * 回调明显多于出帧 = 我们自己的出帧链（act 线程 → postInvalidateOnAnimation）在掉拍，
     * 那是应用能修的；回调本身就少于屏幕拍数 = 节拍是系统给的，与出帧链无关。
     */
    private var frameCallbackCount: Int = 0

    private val seekSerial = AtomicInteger(0)
    private val uiFrameId = AtomicInteger(0)

    private val perfSampleRequested = AtomicBoolean(false)

    @Volatile
    private var perfLastActMs: Float = 0f

    @Volatile
    private var perfLastActAtUptimeMs: Long = 0L

    @Volatile
    private var debugEnabled: Boolean = false

    private val updateNsTotal = AtomicLong(0L)
    private val updateNsMax = AtomicLong(0L)
    private val updateCount = AtomicLong(0L)

    @Volatile
    private var started: Boolean = false

    @Volatile
    private var released: Boolean = false

    @Volatile
    private var viewportWidth: Int = 0

    @Volatile
    private var viewportHeight: Int = 0

    @Volatile
    private var viewportTopInsetPx: Int = 0

    @Volatile
    private var viewportBottomInsetPx: Int = 0

    @Volatile
    private var latestConfig: DanmakuConfig? = null

    internal fun debugSnapshot(): RenderSnapshot = engineMain.renderSnapshot()

    private var lastEnabled: Boolean = true

    init {
        val engine =
            DanmakuEngine(
                displayMetrics = view.resources.displayMetrics,
                cacheManager = cacheManager,
            )
        engineMain = engine
        engineAction = engine
    }

    fun startIfNeeded() {
        if (released) return
        if (started) return
        started = true
        // 主线程直接挂帧回调（见 frameCallback 上的说明）。
        postFrameCallback()
        // 空闲线程探针只在 debug 构建启动：它起一条线程 + 一个 Choreographer，
        // 是纯诊断开销，不该进 release。
        if (BuildConfig.DEBUG) idleVsyncProbe.start()
        view.postInvalidateOnAnimation()
    }

    fun setDebugEnabled(enabled: Boolean) {
        if (debugEnabled == enabled) return
        debugEnabled = enabled
        updateNsTotal.set(0L)
        updateNsMax.set(0L)
        updateCount.set(0L)
    }

    data class DebugState(
        val updateAvgMs: Float,
        val updateMaxMs: Float,
        val cachedDrawn: Int,
        val fallbackDrawn: Int,
        val cacheQueueDepth: Int,
        val poolCount: Int,
        val poolBytes: Long,
        val poolMaxBytes: Long,
        val bitmapCreated: Long,
        val bitmapReused: Long,
        val bitmapPutToPool: Long,
        val bitmapRecycled: Long,
    )

    fun debugState(): DebugState {
        val count = updateCount.get().coerceAtLeast(1L)
        val avgMs = (updateNsTotal.get().toDouble() / count.toDouble() / 1_000_000.0).toFloat()
        val maxMs = (updateNsMax.get().toDouble() / 1_000_000.0).toFloat()
        val pool = cacheManager.poolSnapshot()
        val stats = cacheManager.statsSnapshot()
        return DebugState(
            updateAvgMs = avgMs,
            updateMaxMs = maxMs,
            cachedDrawn = engineMain.lastDrawCachedCount(),
            fallbackDrawn = engineMain.lastDrawFallbackCount(),
            cacheQueueDepth = cacheManager.queueDepth(),
            poolCount = pool.count,
            poolBytes = pool.bytes,
            poolMaxBytes = pool.maxBytes,
            bitmapCreated = stats.bitmapCreated,
            bitmapReused = stats.bitmapReused,
            bitmapPutToPool = stats.bitmapPutToPool,
            bitmapRecycled = stats.bitmapRecycled,
        )
    }

    data class PerfSample(
        val actMs: Float,
        val actAtUptimeMs: Long,
    )

    fun requestPerfSample() {
        perfSampleRequested.set(true)
    }

    fun perfSample(): PerfSample = PerfSample(actMs = perfLastActMs, actAtUptimeMs = perfLastActAtUptimeMs)

    fun stop() {
        if (!started) return
        started = false
        idleVsyncProbe.shutdown()
        releaseSemaphoreIfNeeded()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        resetFrameClock()
    }

    fun release() {
        if (released) return
        released = true
        started = false
        idleVsyncProbe.shutdown()
        // 帧回调挂在主线程的 Choreographer 上，必须在主线程摘掉；release() 正好在主线程被调用。
        runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
        releaseSemaphoreIfNeeded()
        runCatching {
            actionHandler.obtainMessage(MSG_OP_RELEASE).sendToTarget()
        }
    }

    fun onViewportChanged(width: Int, height: Int, topInsetPx: Int, bottomInsetPx: Int) {
        viewportWidth = width.coerceAtLeast(0)
        viewportHeight = height.coerceAtLeast(0)
        viewportTopInsetPx = topInsetPx.coerceAtLeast(0)
        viewportBottomInsetPx = bottomInsetPx.coerceAtLeast(0)
        actionHandler.removeMessages(MSG_OP_VIEWPORT)
        actionHandler.sendEmptyMessage(MSG_OP_VIEWPORT)
    }

    fun updateConfig(config: DanmakuConfig) {
        latestConfig = config
        actionHandler.removeMessages(MSG_OP_CONFIG)
        actionHandler.sendEmptyMessage(MSG_OP_CONFIG)
    }

    fun setDanmakus(list: List<Danmaku>) {
        actionHandler.obtainMessage(MSG_OP_SET, list).sendToTarget()
    }

    fun appendDanmakus(list: List<Danmaku>, maxItems: Int, alreadySorted: Boolean) {
        val payload = AppendPayload(list = list, maxItems = maxItems, alreadySorted = alreadySorted)
        actionHandler.obtainMessage(MSG_OP_APPEND, payload).sendToTarget()
    }

    fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long) {
        actionHandler.obtainMessage(MSG_OP_TRIM_RANGE, TrimRangePayload(minTimeMs, maxTimeMs)).sendToTarget()
    }

    fun seekTo(positionMs: Long) {
        seekSerial.incrementAndGet()
        actionHandler.obtainMessage(MSG_OP_SEEK, positionMs).sendToTarget()
    }

    fun draw(
        canvas: Canvas,
        rawPositionMs: Long,
        isPlaying: Boolean,
        playbackSpeed: Float,
        config: DanmakuConfig,
    ) {
        if (released) return
        if (!config.enabled) {
            if (lastEnabled || started) {
                stop()
            }
            if (lastEnabled) {
                requestClear()
            }
            lastEnabled = false
            return
        }
        lastEnabled = true
        if (isPlaying) {
            startIfNeeded()
        } else if (started) {
            // Freeze danmaku on pause/buffering: no need to keep 60fps update loop running.
            started = false
            idleVsyncProbe.pause()
            releaseSemaphoreIfNeeded()
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            resetFrameClock()
        }

        val frameId = uiFrameId.incrementAndGet()
        engineMain.drainReleasedBitmaps(frameId)
        val nowNanos = presentationNs()
        timer.step(
            nowNanos = nowNanos,
            rawPositionMs = rawPositionMs,
            isPlaying = isPlaying,
            playbackSpeed = playbackSpeed,
            seekSerial = seekSerial.get(),
        )
        // 必须传亚毫秒位置：引擎用它算滚动弹幕的 x，而逐帧位移是位置的差分。
        // 只传整数毫秒会把 120Hz 的 8.333ms 帧间隔量化成 8/8/9 的规律性步长。
        engineMain.stepTime(positionMsExact = timer.currentPositionExactMs(), uiFrameId = frameId)

        // Drain extra permits so we never accumulate >1.
        drawSemaphore.tryAcquire()

        // Obtain render snapshot first, then release semaphore to allow ActionThread to compute next frame.
        val snapshot = engineMain.renderSnapshot()
        releaseSemaphoreIfNeeded()
        engineMain.draw(canvas, snapshot, config)
    }

    private fun postFrameCallback() {
        if (released) return
        if (!started) return
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * 本次绘制应使用的位置时间（ns）。实现与取舍详见 [PresentationClock]。
     *
     * 位置只由帧回调的 vsync 时间戳推进；单调时钟仅在没有帧基准时兜底，
     * 不参与速率 —— 主线程绘制相位的浮动因此不会进入弹幕位置。
     */
    private fun presentationNs(): Long = frameClock.presentationNs(System.nanoTime())

    /** 取走时间轴诊断统计（刷新周期 / 逐帧增量 / 速率误差）。仅用于日志。 */
    fun consumeClockStats(): PresentationClock.ClockStats = frameClock.consumeStats(System.nanoTime())

    /** 取走空闲线程 vsync 探针的统计（间隔分布 + 收到的 vsync 条数）。仅用于日志。 */
    fun consumeIdleVsyncStats(): IdleVsyncProbe.Stats = idleVsyncProbe.consume()

    /** 取走窗口内的帧回调次数并开启新窗口。仅用于日志。 */
    fun consumeFrameCallbackCount(): Int {
        val c = frameCallbackCount
        frameCallbackCount = 0
        return c
    }

    /**
     * 丢弃帧时间基准。
     *
     * 暂停期间没有帧回调；恢复后若沿用旧时间戳，会把整个暂停时长当成一帧的增量。
     * 时间轴本身保留（暂停中跟随单调时钟），下一拍从新的时间戳续接，不跳。
     */
    private fun resetFrameClock() {
        frameClock.reset()
    }

    private fun requestClear() {
        if (released) return
        actionHandler.removeMessages(MSG_OP_CLEAR)
        actionHandler.sendEmptyMessage(MSG_OP_CLEAR)
    }

    private fun releaseSemaphoreIfNeeded() {
        if (drawSemaphore.availablePermits() == 0) {
            drawSemaphore.release()
        }
    }

    private inner class ActionHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_FRAME_UPDATE -> {
                    if (released || !started) return
                    // 下一拍的帧回调由 FrameCallback.doFrame 在主线程自行续挂，这里只做 act。
                    try {
                        engineAction.preAct()
                        drawSemaphore.acquire()
                        if (released || !started) return
                        val sampleAct = perfSampleRequested.getAndSet(false)
                        val shouldMeasure = debugEnabled || sampleAct
                        val t0 = if (shouldMeasure) System.nanoTime() else 0L
                        engineAction.act()
                        if (shouldMeasure) {
                            val t1 = System.nanoTime()
                            val ns = (t1 - t0).coerceAtLeast(0L)
                            if (debugEnabled) {
                                updateCount.incrementAndGet()
                                updateNsTotal.addAndGet(ns)
                                updateMax(updateNsMax, ns)
                            }
                            if (sampleAct) {
                                perfLastActMs = (ns.toDouble() / 1_000_000.0).toFloat()
                                perfLastActAtUptimeMs = SystemClock.uptimeMillis()
                            }
                        }
                        // 重绘（原版形态，见 frameCallback 上的 A/B 说明，已实测回退无害）：
                        // doFrame → 发消息 → ActionThread.acquire（等主线程画完）
                        //   → act() → 请求重绘（下一帧生效，恒定迟一拍）。
                        // 节拍干净时这条链 1:1 出帧；节拍被拉长时迟到那一步会与下一拍
                        // 合并 → 真丢帧（SurfaceView 下实测 4.2%），那种情况应改渲染视图。
                        view.invalidateDanmakuAreaOnAnimation()
                    } catch (ie: InterruptedException) {
                        // Ignore.
                    } catch (t: Throwable) {
                        AppLog.w(TAG, "updateFrame crashed", t)
                    }
                }

                MSG_OP_SET -> {
                    @Suppress("UNCHECKED_CAST")
                    engineAction.setDanmakus(msg.obj as? List<Danmaku> ?: emptyList())
                    renderOnceIfPaused()
                }

                MSG_OP_APPEND -> {
                    val p = msg.obj as? AppendPayload ?: return
                    engineAction.appendDanmakus(p.list, alreadySorted = p.alreadySorted)
                    if (p.maxItems > 0) engineAction.trimToMax(p.maxItems)
                    renderOnceIfPaused()
                }

                MSG_OP_TRIM_RANGE -> {
                    val p = msg.obj as? TrimRangePayload ?: return
                    engineAction.trimToTimeRange(p.minTimeMs, p.maxTimeMs)
                    renderOnceIfPaused()
                }

                MSG_OP_SEEK -> {
                    val pos = (msg.obj as? Long) ?: 0L
                    engineAction.seekTo(pos)
                    renderOnceIfPaused(positionMs = pos)
                }

                MSG_OP_TRIM_MAX -> {
                    val maxItems = msg.arg1
                    engineAction.trimToMax(maxItems)
                    renderOnceIfPaused()
                }

                MSG_OP_CLEAR -> {
                    engineAction.clear()
                }

                MSG_OP_VIEWPORT -> {
                    engineAction.updateViewport(
                        width = viewportWidth,
                        height = viewportHeight,
                        topInsetPx = viewportTopInsetPx,
                        bottomInsetPx = viewportBottomInsetPx,
                    )
                    engineAction.seekTo(engineAction.currentPositionMs())
                    renderOnceIfPaused()
                }

                MSG_OP_CONFIG -> {
                    latestConfig?.let {
                        engineAction.updateConfig(it)
                        // Reset layout on config changes (text size/speed/area) to keep correctness simple.
                        engineAction.seekTo(engineAction.currentPositionMs())
                        renderOnceIfPaused()
                    }
                }

                MSG_OP_RELEASE -> {
                    removeCallbacksAndMessages(null)
                    Choreographer.getInstance().removeFrameCallback(frameCallback)
                    started = false
                    runCatching { actionThread.quitSafely() }
                    runCatching { actionThread.join(80L) }
                    engineAction.release()
                    cacheManager.release()
                }
            }
        }

        private fun renderOnceIfPaused(positionMs: Long? = null) {
            if (released || started) return
            val pos = positionMs ?: engineAction.currentPositionMs()
            engineAction.stepTime(positionMsExact = pos.toDouble(), uiFrameId = uiFrameId.get())
            val sampleAct = perfSampleRequested.getAndSet(false)
            val shouldMeasure = debugEnabled || sampleAct
            val t0 = if (shouldMeasure) System.nanoTime() else 0L
            runCatching { engineAction.act() }
            if (shouldMeasure) {
                val t1 = System.nanoTime()
                val ns = (t1 - t0).coerceAtLeast(0L)
                if (debugEnabled) {
                    updateCount.incrementAndGet()
                    updateNsTotal.addAndGet(ns)
                    updateMax(updateNsMax, ns)
                }
                if (sampleAct) {
                    perfLastActMs = (ns.toDouble() / 1_000_000.0).toFloat()
                    perfLastActAtUptimeMs = SystemClock.uptimeMillis()
                }
            }
            view.invalidateDanmakuAreaOnAnimation()
        }
    }

    private fun updateMax(target: AtomicLong, v: Long) {
        while (true) {
            val cur = target.get()
            if (v <= cur) return
            if (target.compareAndSet(cur, v)) return
        }
    }

    private data class AppendPayload(
        val list: List<Danmaku>,
        val maxItems: Int,
        val alreadySorted: Boolean,
    )

    private data class TrimRangePayload(
        val minTimeMs: Long,
        val maxTimeMs: Long,
    )
}
