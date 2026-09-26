package blbl.cat3399.feature.player.danmaku

/**
 * 滚动弹幕的 x 坐标。
 *
 * [nowMs] 必须带亚毫秒精度，这是「看起来顺不顺」的关键：
 * 逐帧位移是 elapsed 的**差分**，[pxPerMs] 又是亚像素级的数（例如 8 秒穿 1920px ≈ 0.24px/ms）。
 * 若 [nowMs] 被量化成整数毫秒，120Hz 下每帧 8.333ms 就会变成 8、8、9、8、8、9…，
 * 位移随之出现「每三帧多走一点」的规律性节拍 —— 恒定但周期性，肉眼比随机噪声明显得多
 * （随机抖动会被视觉系统平均掉，周期性误差不会）。60Hz 下同样存在，只是占比减半。
 *
 * @param width 视口宽度（px），弹幕从右边缘进入。
 * @param nowMs 当前播放位置（ms，可含小数）。
 * @param startTimeMs 该弹幕的起始位置（ms，整数即可：它只是一个常数相位偏移）。
 * @param pxPerMs 速度（px/ms）。
 */
internal fun danmakuScrollX(
    width: Int,
    nowMs: Double,
    startTimeMs: Int,
    pxPerMs: Float,
): Float {
    val elapsed = (nowMs - startTimeMs).coerceAtLeast(0.0)
    return width.toFloat() - (elapsed * pxPerMs).toFloat()
}
