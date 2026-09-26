package blbl.cat3399.feature.player.danmaku

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import blbl.cat3399.core.emote.ReplyEmotePanelRepository
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale

class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val player = DanmakuPlayer(this)

    private var positionProvider: (() -> Long)? = null
    private var isPlayingProvider: (() -> Boolean)? = null
    private var playbackSpeedProvider: (() -> Float)? = null
    private var configProvider: (() -> DanmakuConfig)? = null

    @Volatile private var debugEnabled: Boolean = false
    private val debugStats = DebugStatsCollector()

    private var lastConfig: DanmakuConfig? = null
    private var lastRawPositionMs: Long = 0L
    private var lastPositionChangeUptimeMs: Long = 0L

    @Volatile private var invalidateFull: Boolean = true
    @Volatile private var invalidateTopPx: Int = 0
    @Volatile private var invalidateBottomPx: Int = 0

    // Keep danmaku anchored to the video edge; window insets made the first lane drift on 16:9 TVs.
    private val viewportTopInsetPx: Int = dp(2f)
    private val viewportBottomInsetPx: Int = dp(52f)

    private var lastViewportW: Int = 0
    private var lastViewportH: Int = 0
    private var lastViewportTopInset: Int = 0
    private var lastViewportBottomInset: Int = 0

    private var perfLastLogAtUptimeMs: Long = 0L
    private var perfFramesSinceLog: Int = 0
    private var perfLogPosted: Boolean = false

    /** 显示模式清单只打一次。 */
    private var displayModesLogged: Boolean = false
    private val perfLogRunnable =
        object : Runnable {
            override fun run() {
                if (!isAttachedToWindow) {
                    perfLogPosted = false
                    return
                }
                try {
                    val cfg = runCatching { configProvider?.invoke() }.getOrNull()
                    val rawPos = runCatching { positionProvider?.invoke() }.getOrNull() ?: lastRawPositionMs
                    val isPlaying = runCatching { isPlayingProvider?.invoke() }.getOrNull() ?: false
                    val speed =
                        runCatching { playbackSpeedProvider?.invoke() }.getOrNull()
                            ?.takeIf { it.isFinite() && it > 0f }
                            ?: 1f
                    logPerfIfNeeded(cfg = cfg, rawPos = rawPos, isPlaying = isPlaying, playbackSpeed = speed, force = true)
                } catch (_: Throwable) {
                    // Ignore perf logging failures.
                } finally {
                    // Keep running while attached.
                    postDelayed(this, PERF_LOG_INTERVAL_MS)
                }
            }
        }

    data class DebugStats(
        val viewAttached: Boolean,
        val configEnabled: Boolean,
        val lastPositionMs: Long,
        val drawFps: Float,
        val lastFrameActive: Int,
        val lastFramePending: Int,
        val lastFrameCachedDrawn: Int,
        val lastFrameFallbackDrawn: Int,
        val lastFrameRequestsActive: Int,
        val lastFrameRequestsPrefetch: Int,
        val cacheItems: Int,
        val renderingItems: Int,
        val queueDepth: Int,
        val poolItems: Int,
        val poolBytes: Long,
        val poolMaxBytes: Long,
        val bitmapCreated: Long,
        val bitmapReused: Long,
        val bitmapPutToPool: Long,
        val bitmapRecycled: Long,
        val invalidateFull: Boolean,
        val invalidateTopPx: Int,
        val invalidateBottomPx: Int,
        val updateAvgMs: Float,
        val updateMaxMs: Float,
        val drawAvgMs: Float,
        val drawMaxMs: Float,
    )

    fun setDebugEnabled(enabled: Boolean) {
        if (debugEnabled == enabled) return
        debugEnabled = enabled
        debugStats.reset()
        player.setDebugEnabled(enabled)
    }

    fun getDebugStats(): DebugStats {
        val cfg = configProvider?.invoke() ?: defaultConfig()
        val snap = player.debugSnapshot()
        val p = player.debugState()
        debugStats.lastFrameActive = snap.count
        debugStats.lastFramePending = snap.pendingCount
        debugStats.lastFrameCachedDrawn = p.cachedDrawn
        debugStats.lastFrameFallbackDrawn = p.fallbackDrawn
        val now = SystemClock.uptimeMillis()
        return DebugStats(
            viewAttached = isAttachedToWindow,
            configEnabled = cfg.enabled,
            lastPositionMs = lastRawPositionMs,
            drawFps = debugStats.drawFps(now),
            lastFrameActive = debugStats.lastFrameActive,
            lastFramePending = debugStats.lastFramePending,
            lastFrameCachedDrawn = debugStats.lastFrameCachedDrawn,
            lastFrameFallbackDrawn = debugStats.lastFrameFallbackDrawn,
            lastFrameRequestsActive = 0,
            lastFrameRequestsPrefetch = 0,
            cacheItems = p.cachedDrawn,
            renderingItems = p.cacheQueueDepth,
            queueDepth = p.cacheQueueDepth,
            poolItems = p.poolCount,
            poolBytes = p.poolBytes,
            poolMaxBytes = p.poolMaxBytes,
            bitmapCreated = p.bitmapCreated,
            bitmapReused = p.bitmapReused,
            bitmapPutToPool = p.bitmapPutToPool,
            bitmapRecycled = p.bitmapRecycled,
            invalidateFull = invalidateFull,
            invalidateTopPx = invalidateTopPx,
            invalidateBottomPx = invalidateBottomPx,
            updateAvgMs = p.updateAvgMs,
            updateMaxMs = p.updateMaxMs,
            drawAvgMs = debugStats.avgDrawMs(),
            drawMaxMs = debugStats.maxDrawMs(),
        )
    }

    fun setPositionProvider(provider: () -> Long) {
        positionProvider = provider
    }

    fun setIsPlayingProvider(provider: () -> Boolean) {
        isPlayingProvider = provider
    }

    fun setPlaybackSpeedProvider(provider: () -> Float) {
        playbackSpeedProvider = provider
    }

    fun setConfigProvider(provider: () -> DanmakuConfig) {
        configProvider = provider
    }

    fun setDanmakus(list: List<Danmaku>) {
        player.setDanmakus(list)
        invalidate()
    }

    fun appendDanmakus(list: List<Danmaku>, maxItems: Int = 0, alreadySorted: Boolean = false) {
        if (list.isEmpty()) return
        player.appendDanmakus(list, maxItems = maxItems, alreadySorted = alreadySorted)
        invalidate()
    }

    fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long) {
        player.trimToTimeRange(minTimeMs, maxTimeMs)
        invalidate()
    }

    fun notifySeek(positionMs: Long) {
        player.seekTo(positionMs)
        lastRawPositionMs = positionMs
        lastPositionChangeUptimeMs = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ReplyEmotePanelRepository.warmup(context)
        updateViewportIfNeeded()
        startPerfLoggingIfNeeded()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPerfLogging()
        player.release()
        debugStats.reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateViewportIfNeeded()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        perfFramesSinceLog++

        val cfg = configProvider?.invoke() ?: defaultConfig()
        if (cfg != lastConfig) {
            lastConfig = cfg
            player.updateConfig(cfg)
        }

        updateViewportIfNeeded()
        updateInvalidateArea(cfg)

        if (!cfg.enabled) {
            player.draw(
                canvas = canvas,
                rawPositionMs = 0L,
                isPlaying = false,
                playbackSpeed = 1f,
                config = cfg,
            )
            return
        }

        val posProvider = positionProvider ?: return
        val rawPos = posProvider()
        val now = SystemClock.uptimeMillis()
        if (lastPositionChangeUptimeMs == 0L) lastPositionChangeUptimeMs = now
        if (rawPos != lastRawPositionMs) lastPositionChangeUptimeMs = now
        lastRawPositionMs = rawPos

        val isPlaying =
            runCatching { isPlayingProvider?.invoke() }.getOrNull()
                ?: (now - lastPositionChangeUptimeMs < STOP_WHEN_IDLE_MS)
        val speed =
            runCatching { playbackSpeedProvider?.invoke() }.getOrNull()
                ?.takeIf { it.isFinite() && it > 0f }
                ?: 1f

        // 无条件测绘制耗时：这是判断「高刷屏上还有没有余量」的唯一依据。
        // 面板每拍只给 8.33ms（120Hz），绘制一旦逼近它就会隔拍丢帧，逐帧位移退化成
        // 8.33/16.67 交替 —— 看上去就是弹幕一顿一顿地抖。两次 nanoTime 的开销可忽略。
        val drawT0 = System.nanoTime()
        player.draw(
            canvas = canvas,
            rawPositionMs = rawPos,
            isPlaying = isPlaying,
            playbackSpeed = speed,
            config = cfg,
        )
        val drawNs = (System.nanoTime() - drawT0).coerceAtLeast(0L)
        debugStats.recordWindowDraw(drawNs)
        if (debugEnabled) debugStats.recordDraw(nowUptimeMs = now, drawNs = drawNs)
    }

    private fun startPerfLoggingIfNeeded() {
        if (perfLogPosted) return
        perfLogPosted = true
        perfLastLogAtUptimeMs = 0L
        perfFramesSinceLog = 0
        removeCallbacks(perfLogRunnable)
        post(perfLogRunnable)
    }

    private fun stopPerfLogging() {
        perfLogPosted = false
        removeCallbacks(perfLogRunnable)
    }

    private fun logPerfIfNeeded(
        cfg: DanmakuConfig? = null,
        rawPos: Long = lastRawPositionMs,
        isPlaying: Boolean = false,
        playbackSpeed: Float = 1f,
        force: Boolean = false,
    ) {
        val now = SystemClock.uptimeMillis()
        val lastAt = perfLastLogAtUptimeMs
        val due = lastAt == 0L || now - lastAt >= PERF_LOG_INTERVAL_MS
        if (!force && !due) return

        val config = cfg ?: (configProvider?.invoke() ?: defaultConfig())
        if (configProvider == null && cfg == null) return

        val deltaMs = if (lastAt == 0L) 0L else (now - lastAt).coerceAtLeast(0L)
        val frames = perfFramesSinceLog.coerceAtLeast(0)
        val fps =
            if (deltaMs > 0L) {
                (frames.toFloat() * 1000f) / deltaMs.toFloat()
            } else {
                0f
            }
        perfLastLogAtUptimeMs = now
        perfFramesSinceLog = 0

        val snap = player.debugSnapshot()
        val p = player.debugState()
        val sample = player.perfSample()
        val clock = player.consumeClockStats()
        val idle = player.consumeIdleVsyncStats()
        val callbackCount = player.consumeFrameCallbackCount()
        logDisplayModesOnce()
        val poolMb = p.poolBytes.toDouble() / (1024.0 * 1024.0)
        val poolMaxMb = p.poolMaxBytes.toDouble() / (1024.0 * 1024.0)
        val inv =
            if (invalidateFull) {
                "full"
            } else {
                "${invalidateTopPx}-${invalidateBottomPx}"
            }
        val actAgeMs = if (sample.actAtUptimeMs > 0L) (now - sample.actAtUptimeMs).coerceAtLeast(0L) else -1L
        val (drawAvgMs, drawMaxMs) = debugStats.consumeWindowDraw()
        val dispHz = currentDisplayHz()

        AppLog.i(
            "DanmakuPerf",
            buildString(320) {
                append("dm=").append(if (config.enabled) "on" else "off")
                append(" play=").append(isPlaying)
                append(" spd=").append(String.format(Locale.US, "%.2f", playbackSpeed))
                append(" raw=").append(rawPos).append("ms")
                append(" smooth=").append(snap.positionMs).append("ms")
                append(" fps=").append(String.format(Locale.US, "%.1f", fps))
                append(" act=").append(snap.count)
                append(" pend=").append(snap.pendingCount)
                append(" hit=").append(p.cachedDrawn).append('/').append(snap.count)
                append(" fb=").append(p.fallbackDrawn)
                append(" q=").append(p.cacheQueueDepth)
                append(" pool=").append(String.format(Locale.US, "%.1f", poolMb)).append('/').append(String.format(Locale.US, "%.0f", poolMaxMb)).append("MB")
                append(" actMs=").append(String.format(Locale.US, "%.2f", sample.actMs))
                append(" age=").append(actAgeMs).append("ms")
                // 主线程单帧绘制耗时（窗口平均 / 峰值，ms）。判据：
                //   平均 ≈ vsP → 没有余量，帧间任何波动都会隔拍丢帧（dMax 会跳到 2×vsP）；
                //   平均 ≪ vsP → 绘制不是瓶颈，抖不来自应用。
                append(" drawMs=").append(String.format(Locale.US, "%.2f", drawAvgMs))
                append(" drawMax=").append(String.format(Locale.US, "%.2f", drawMaxMs))
                // 系统当前报告的显示刷新率（Hz）。与 vsP 对照：
                //   dispHz≈120 且 vsP≈8.33 → 真 120Hz；dispHz≈120 但 vsP≈16.67 → 系统只按 60Hz 给 vsync。
                append(" dispHz=").append(String.format(Locale.US, "%.1f", dispHz))
                // 实测节拍周期 = 帧回调间隔的**中位数**（ms）。16.67 说明系统按 60Hz 在给 vsync，
                // 8.33 才是 120Hz。弹幕的「顺滑」只跟这个值有关。
                // 取中位数而不是最小值：最小值会被单个离群样本拉低（实测出现过 0.68ms 的样本）。
                append(" vsP=").append(fmt(clock.refreshPeriodMs))
                // 帧回调间隔的**下四分位**（ms）。与 vsP 一起区分两种看起来一样的局面：
                //   vsQ1 ≈ vsP ≈ 16.67 → 节拍是整拍、只是偶尔丢了几拍（能在整拍上对齐）；
                //   vsQ1 跟着 vsP 一起升高（如 17.2 / 17.4）→ 节拍本身被整体拉伸，不是 60Hz。
                // 只看 vsP（中位数）时这两种情况分不开，而它们决定了「改应用有没有意义」。
                append(" vsQ1=").append(fmt(clock.q1GapMs))
                // 同一窗口里帧回调间隔的**最小值 / 最大值**（ms，按系统上报的时间戳）。
                // 关键是它们与 vsP 的**展宽**：展宽大而下面的 nm* 很窄 → 上报的时间戳被修饰过。
                append(" vsMin=").append(fmt(clock.minGapMs))
                append(" vsMax=").append(fmt(clock.maxGapMs))
                // 出帧（时间轴推进）增量的最小 / 最大（ms）。两者都 ≈ vsP 说明每一拍都真的出了一帧；
                // dMax ≈ 2×vsP 说明在丢帧（渲染管线跟不上面板），那种不顺改时间轴没用。
                append(" dMin=").append(fmt(clock.stepMinMs))
                append(" dMax=").append(fmt(clock.stepMaxMs))
                // 用**进程自己的单调时钟**测出的回调间隔（中位 / 最小 / 最大，ms）。
                // 与 vsP / vsMin / vsMax 对照：两组展宽相当 → 时间真的在不等地流逝（锅在显示链路）；
                // nm* 明显更窄 → 逐帧增量里那部分不齐是**假的**，可以用匀速推进把它消掉。
                append(" nmP=").append(fmt(clock.dispatchMedianMs))
                append(" nmMin=").append(fmt(clock.dispatchMinMs))
                append(" nmMax=").append(fmt(clock.dispatchMaxMs))
                // 回调从上报的 vsync 时刻到被处理的延迟（平均 / 最小 / 最大，ms）。
                // lateMax 鼓出一整个刷新周期 = 主线程被别的活卡住、事件被推迟处理（丢拍是我们自己的）；
                // 三个值全程接近恒定 = 主线程没参与丢拍。
                append(" late=").append(fmtSigned(clock.lateAvgMs))
                append(" lateMin=").append(fmtSigned(clock.lateMinMs))
                append(" lateMax=").append(fmtSigned(clock.lateMaxMs))
                // 时间轴相对真实时钟的漂移（ms）：≈0 说明时间轴速率准，弹幕速度就是准的。
                // 注意它是在「非 vsync 锁定」的时刻采样的，会带上 ±1 拍的相位噪声 ——
                // 单窗口值别当真，只看长程均值，或看 raw 与 smooth 的偏移是否恒定。
                append(" rateErr=").append(String.format(Locale.US, "%+.2f", clock.rateErrorMs))
                // 帧回调次数。与 fps×窗口时长 对照：
                //   相等 → 每次回调都画了一帧，出帧链是干净的；
                //   明显更多 → 有回调没画出来（出帧链掉拍），节拍不齐就是「抖」的直接来源，应用能修；
                //   本身就更少 → 连回调都少了，节拍是系统给的，与应用无关。
                append(" cb=").append(callbackCount)
                // 空闲线程探针（debug 构建才有）：在一条几乎空闲的线程上用**第二个 Choreographer**
                // 消费同一条 vsync 流。它是判定「节拍被拉伸 / 丢拍是谁造成的」最硬的一条判据 ——
                //   1) idleN 与上面 cb 的**差值** = 被我们主线程自己吃掉的拍数
                //      （Choreographer 的 mHavePendingVsync 会丢弃与未处理 vsync 同时到达的那一拍）；
                //   2) idleP/idleQ1 与 vsP/vsQ1 对照 —— 两者相当说明**显示源本身就是这个节拍**，
                //      应用侧无解；idleP 回到 16.67 而 vsP 更大则说明是我们吃掉了拍子，能修。
                append(" idleP=").append(fmt(idle.medianMs))
                append(" idleQ1=").append(fmt(idle.q1Ms))
                append(" idleMin=").append(fmt(idle.minMs))
                append(" idleMax=").append(fmt(idle.maxMs))
                append(" idleN=").append(idle.count)
                // 帧回调间隔 / 出帧增量按「窗口中位数」归一后的各档样本数（1× / 2× / 3× / ≥4×）。
                // **注意：主线程出帧修复之后，每次回调都恰好读一次时间轴，所以 gap 与 step 恒等。**
                // 它现在的用途是**回归防线**：step 一旦出现 gap 没有的 2×/3× 档，就说明出帧链又在掉拍。
                // 判别「回调被丢」与「时间戳在抖」要靠上面的 vs* 与 nm* 两组值的展宽对照。
                append(" gap=").append(histText(clock.gapHist))
                append(" step=").append(histText(clock.stepHist))
                append(" inv=").append(inv)
            },
        )

        // Ask action thread to sample act cost for the next log interval.
        player.requestPerfSample()
    }

    /** 档位分布的可读形式：`1×/2×/3×/≥4×` 的样本数。见 [StepHistogram]。 */
    private fun histText(h: StepHistogram.Result): String =
        "${h.k1}/${h.k2}/${h.k3}/${h.k4}"

    /**
     * 打一次显示模式清单（整个生命周期只打一次）。
     *
     * `Display.getRefreshRate()` 只反映系统**以为**的显示模式。实测 vsync 节拍与它不一致时
     * （本次 TCL：报 60.0Hz，实际周期 20.0ms = 50Hz），单看那一个数字无法判断面板到底有
     * 几档刷新率、现在挂在哪一档。把「当前模式 + 该模式支持的其它刷新率 + 系统认得的全部模式」
     * 一起打出来，才能确定面板的能力与当前档位。
     */
    private fun logDisplayModesOnce() {
        if (displayModesLogged) return
        // 先取 display 再置标志：视图还没附着时 display 为 null，那次不算数，下一轮再试。
        val d = display ?: return
        displayModesLogged = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            AppLog.i("DisplayMode", "sdk<23 reportedHz=${hzText(d.refreshRate)}")
            return
        }
        // 每个子项**单独**兜底：实测 TCL 的 Display.Mode 会在 alternativeRefreshRates /
        // supportedModes 上抛异常，一处失败不该把整条日志吞掉（上一版就是这样，只剩
        // 一行「probe failed」什么都没拿到）。
        // 另外 ART 的 OmitStackTraceInFastThrow 会把反复抛出的同类异常优化成**无栈**异常，
        // 所以这里显式带上异常类名与 message，不能指望堆栈。
        runCatching {
            val cur = d.mode
            val curText =
                runCatching {
                    "${cur.modeId}:${cur.physicalWidth}x${cur.physicalHeight}@${hzText(cur.refreshRate)}"
                }.getOrElse { "err(${it.javaClass.simpleName}: ${it.message})" }
            val alt =
                runCatching { cur.alternativeRefreshRates.joinToString(",") { hzText(it) } }
                    .getOrElse { "err(${it.javaClass.simpleName}: ${it.message})" }
            val supported =
                runCatching {
                    d.supportedModes.joinToString(",") {
                        "${it.modeId}:${it.physicalWidth}x${it.physicalHeight}@${hzText(it.refreshRate)}"
                    }
                }.getOrElse { "err(${it.javaClass.simpleName}: ${it.message})" }
            AppLog.i(
                "DisplayMode",
                "cur=$curText reportedHz=${hzText(d.refreshRate)} alt=[$alt] supported=[$supported]",
            )
        }.onFailure {
            AppLog.w("DisplayMode", "probe failed: ${it.javaClass.name}: ${it.message}", it)
        }
    }

    private fun hzText(hz: Float): String = String.format(Locale.US, "%.2f", hz)

    /**
     * 请求重绘弹幕区域。
     *
     * **线程自适应**，这不是可有可无的优化，而是「能不能同帧画出来」的分水岭：
     *  - 主线程（帧回调里）：用 `invalidate()` 直接标脏。`Choreographer.doFrame` 的
     *    ANIMATION 阶段早于 TRAVERSAL 阶段，所以**这一拍就画出来**，延迟 0 拍。
     *  - 其它线程：只能用 `postInvalidateOnAnimation()`，它要先 post 一个 Runnable 到消息队列，
     *    最快也要等**下一个** vsync —— 这正是出帧节拍比帧回调少一档的根源。
     */
    @Suppress("DEPRECATION") // 局部失效自 API 28 起被标记 deprecated，但语义正是我们要的：
                             // 只把弹幕区域标脏，不拖着整个 view 一起重绘。
    internal fun invalidateDanmakuAreaOnAnimation() {
        val onMainThread = Looper.myLooper() == Looper.getMainLooper()
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        if (w <= 0 || h <= 0 || invalidateFull) {
            if (onMainThread) invalidate() else postInvalidateOnAnimation()
            return
        }
        val top = invalidateTopPx.coerceIn(0, h)
        var bottom = invalidateBottomPx.coerceIn(top, h)
        if (bottom <= top) bottom = (top + 1).coerceAtMost(h)
        if (onMainThread) invalidate(0, top, w, bottom) else postInvalidateOnAnimation(0, top, w, bottom)
    }

    private fun updateInvalidateArea(cfg: DanmakuConfig) {
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        if (w <= 0 || h <= 0) {
            invalidateFull = true
            invalidateTopPx = 0
            invalidateBottomPx = 0
            return
        }
        val topInsetPx = viewportTopInsetPx
        val bottomInsetPx = viewportBottomInsetPx
        val safeTop = topInsetPx.coerceIn(0, h)
        val safeBottom = bottomInsetPx.coerceIn(0, h - safeTop)
        val availableHeight = (h - safeTop - safeBottom).coerceAtLeast(0)
        val top = safeTop
        val bottomRaw = safeTop + (availableHeight.toFloat() * cfg.area.coerceIn(0f, 1f)).toInt()
        val bottom = bottomRaw.coerceIn(top, h)
        invalidateTopPx = top
        invalidateBottomPx = bottom
        invalidateFull = top <= 0 && bottom >= h
    }

    private fun updateViewportIfNeeded() {
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        val top = viewportTopInsetPx
        val bottom = viewportBottomInsetPx
        if (w == lastViewportW && h == lastViewportH && top == lastViewportTopInset && bottom == lastViewportBottomInset) return
        lastViewportW = w
        lastViewportH = h
        lastViewportTopInset = top
        lastViewportBottomInset = bottom
        player.onViewportChanged(width = w, height = h, topInsetPx = top, bottomInsetPx = bottom)
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    /**
     * 系统当前报告的显示刷新率（Hz）；0 表示取不到（尚未附着到窗口）。
     *
     * 只用于诊断：它反映**系统选定**的显示模式，未必等于应用实际拿到的 vsync 节拍（见 vsP）。
     * 两者差一倍，说明应用被系统限流在更低的节拍上 —— 那不是应用能改的。
     */
    @Suppress("DEPRECATION")
    private fun currentDisplayHz(): Float = runCatching { display?.refreshRate ?: 0f }.getOrDefault(0f)

    /** 诊断日志用：负值代表「无样本」。 */
    private fun fmt(v: Float): String =
        if (v < 0f) "--" else String.format(Locale.US, "%.2f", v)

    /**
     * 诊断日志用：带符号输出，且**不用负值当「无样本」**(延迟本身可以是负的：
     * Choreographer 可能把时间戳向前修饰过)。无样本用 NaN 表示。
     */
    private fun fmtSigned(v: Float): String =
        if (v.isNaN()) "--" else String.format(Locale.US, "%+.2f", v)

    private fun defaultConfig(): DanmakuConfig =
        DanmakuConfig(
            enabled = true,
            opacity = 1f,
            textSizeSp = 18f,
            fontWeight = DanmakuFontWeight.Bold,
            strokeWidthPx = 4,
            speedLevel = 4,
            area = 1f,
            laneDensity = DanmakuLaneDensity.Standard,
            showHighLikeIcon = true,
        )

    private class DebugStatsCollector {
        private val lastDrawAtMs = AtomicLong()
        @Volatile private var smoothedDrawFps: Float = 0f

        private val drawNsTotal = AtomicLong()
        private val drawNsMax = AtomicLong()
        private val drawCount = AtomicLong()

        // 日志窗口内的绘制耗时（每 3s 取走一次，与 dMin/dMax 同一时间尺度）。
        // 全生命周期的 avgDrawMs 会被起播初期的慢帧永久拉高，判断当前余量必须看窗口值。
        private val winDrawNsTotal = AtomicLong()
        private val winDrawNsMax = AtomicLong()
        private val winDrawCount = AtomicLong()

        @Volatile var lastFrameActive: Int = 0
        @Volatile var lastFramePending: Int = 0
        @Volatile var lastFrameCachedDrawn: Int = 0
        @Volatile var lastFrameFallbackDrawn: Int = 0

        fun reset() {
            lastDrawAtMs.set(0L)
            smoothedDrawFps = 0f
            drawNsTotal.set(0L)
            drawNsMax.set(0L)
            drawCount.set(0L)
            winDrawNsTotal.set(0L)
            winDrawNsMax.set(0L)
            winDrawCount.set(0L)
            lastFrameActive = 0
            lastFramePending = 0
            lastFrameCachedDrawn = 0
            lastFrameFallbackDrawn = 0
        }

        fun recordDraw(nowUptimeMs: Long, drawNs: Long) {
            updateDrawFps(nowUptimeMs)
            drawCount.incrementAndGet()
            drawNsTotal.addAndGet(drawNs)
            updateMax(drawNsMax, drawNs)
        }

        fun recordWindowDraw(drawNs: Long) {
            winDrawCount.incrementAndGet()
            winDrawNsTotal.addAndGet(drawNs)
            updateMax(winDrawNsMax, drawNs)
        }

        /** 取走窗口内的绘制耗时（平均 ms, 峰值 ms）并开启新窗口。 */
        fun consumeWindowDraw(): Pair<Float, Float> {
            val count = winDrawCount.getAndSet(0L).coerceAtLeast(1L)
            val totalNs = winDrawNsTotal.getAndSet(0L).coerceAtLeast(0L)
            val maxNs = winDrawNsMax.getAndSet(0L).coerceAtLeast(0L)
            val avgMs = (totalNs.toDouble() / count.toDouble() / 1_000_000.0).toFloat()
            val maxMs = (maxNs.toDouble() / 1_000_000.0).toFloat()
            return avgMs to maxMs
        }

        fun drawFps(nowUptimeMs: Long): Float {
            val last = lastDrawAtMs.get()
            if (last == 0L) return 0f
            if (nowUptimeMs - last > 1_000L) return 0f
            return smoothedDrawFps
        }

        fun avgDrawMs(): Float {
            val count = drawCount.get().coerceAtLeast(1L)
            val totalNs = drawNsTotal.get().coerceAtLeast(0L)
            return (totalNs.toDouble() / count.toDouble() / 1_000_000.0).toFloat()
        }

        fun maxDrawMs(): Float = (drawNsMax.get().coerceAtLeast(0L).toDouble() / 1_000_000.0).toFloat()

        private fun updateDrawFps(nowUptimeMs: Long) {
            val prev = lastDrawAtMs.getAndSet(nowUptimeMs)
            if (prev == 0L) return
            val deltaMs = nowUptimeMs - prev
            if (deltaMs <= 0L) return
            val inst = 1000f / deltaMs.toFloat()
            val cur = smoothedDrawFps
            smoothedDrawFps = if (cur <= 0f) inst else (cur * 0.85f + inst * 0.15f)
        }

        private fun updateMax(target: AtomicLong, v: Long) {
            while (true) {
                val cur = target.get()
                if (v <= cur) return
                if (target.compareAndSet(cur, v)) return
            }
        }
    }

    private companion object {
        private const val STOP_WHEN_IDLE_MS = 700L
        private const val PERF_LOG_INTERVAL_MS = 3_000L
    }
}
