package blbl.cat3399.feature.player.danmaku.model

/** 评论区公共表情：相对字形高 1 倍（与改造前一致，不撑高底图）。 */
internal const val EMOTE_SCALE_INLINE = 1f

/** 直播小表情：约 1.15 倍字形高，略高于文字（对齐 B 站观感）。 */
internal const val EMOTE_SCALE_LIVE_SMALL = 1.15f

/** 直播大表情：约 1.8 倍字形高，会跨轨道占用。 */
internal const val EMOTE_SCALE_LIVE_LARGE = 1.8f

internal sealed interface DanmakuInlineSegment {
    data class Text(
        val start: Int,
        val end: Int,
    ) : DanmakuInlineSegment

    /**
     * 行内表情图。
     *
     * [scale] 是相对字形高（descent - ascent）的倍数：评论区公共表情用
     * [EMOTE_SCALE_INLINE]，直播表情用 [EMOTE_SCALE_LIVE_SMALL] / [EMOTE_SCALE_LIVE_LARGE]。
     *
     * [bulge] 标记「大表情」（B 站弹幕的 `bulge_display`）。只有它会撑高底图、
     * 跨多条轨道占用；小表情与公共表情无论 [scale] 多大，都只按一条轨道布局。
     */
    data class Emote(
        val url: String,
        val scale: Float = EMOTE_SCALE_INLINE,
        val bulge: Boolean = false,
    ) : DanmakuInlineSegment

    data object HighLikeIcon : DanmakuInlineSegment
}
