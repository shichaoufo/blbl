package blbl.cat3399.core.image

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import blbl.cat3399.core.log.AppLog

/**
 * 自己驱动的 GIF Drawable —— 用 API 1 就存在的 [Movie]，不依赖 `AnimatedImageDrawable`。
 *
 * 为什么不用 `AnimatedImageDrawable`（API 28 的 ImageDecoder 路径）：
 *  - 它**连第一帧都要靠 `scheduleSelf()` 推进**，而 scheduleSelf 最终走
 *    `Drawable.Callback.scheduleDrawable()` —— 也就是说 drawable 必须先挂到 View 上、
 *    且 View 已 attach 到窗口，`start()` 才有效。顺序搞反（先 start 再 setImageDrawable）
 *    的症状就是「占位正确、内容全透明」，在定制 ROM 上更容易踩到。
 *  - [Movie] 是纯 skia 解码 + 我们自己按时间 seek 帧，行为只取决于我们的代码。
 *
 * 驱动方式：`draw()` 里 `scheduleSelf(下一帧)`。只要宿主 View 画过一次就会自持下去。
 * 另外实现 `OnAttachStateChangeListener`：列表里滚动出屏再回来时（View detach → attach）
 * 调度链会断，靠 attach 回调重启，否则 GIF 会停在某帧不动。
 */
internal class MovieGifDrawable(
    private val movie: Movie,
) : Drawable(),
    View.OnAttachStateChangeListener {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tick = Runnable { invalidateSelf() }

    private var startMs = -1L
    private var failed = false
    private val periodMs = movie.duration().coerceAtLeast(MIN_PERIOD_MS)

    override fun draw(canvas: Canvas) {
        if (failed) return
        try {
            val now = SystemClock.uptimeMillis()
            if (startMs < 0L) startMs = now
            movie.setTime(((now - startMs) % periodMs).toInt())

            val saveCount = canvas.save()
            canvas.clipRect(bounds)
            movie.draw(canvas, bounds.left.toFloat(), bounds.top.toFloat(), paint)
            canvas.restoreToCount(saveCount)

            scheduleSelf(tick, now + FRAME_MS)
        } catch (t: Throwable) {
            // 画在 View 的 onDraw 里，抛出去会整页崩溃 —— 记一次就停，退化成不显示。
            failed = true
            AppLog.w(TAG, "gif draw failed ${movie.width()}x${movie.height()}", t)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = movie.width()

    override fun getIntrinsicHeight(): Int = movie.height()

    override fun onViewAttachedToWindow(v: View) {
        startMs = -1L
        invalidateSelf()
    }

    override fun onViewDetachedFromWindow(v: View) {
        unscheduleSelf(tick)
    }

    private companion object {
        private const val TAG = "MovieGifDrawable"

        /** 驱动间隔。GIF 常见帧延迟在 50~120ms，100ms 足够不跳帧，又不至于抢主线程。 */
        private const val FRAME_MS = 100L
        private const val MIN_PERIOD_MS = 100
    }
}
