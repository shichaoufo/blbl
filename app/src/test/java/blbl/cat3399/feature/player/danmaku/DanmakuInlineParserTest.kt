package blbl.cat3399.feature.player.danmaku

import blbl.cat3399.core.model.DanmakuEmote
import blbl.cat3399.feature.player.danmaku.model.DanmakuInlineSegment
import blbl.cat3399.feature.player.danmaku.model.EMOTE_SCALE_LIVE_LARGE
import blbl.cat3399.feature.player.danmaku.model.EMOTE_SCALE_LIVE_SMALL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直播弹幕表情的解析规则。核心回归点：表情图旁边**不能**再出现表情名/占位符文字。
 */
class DanmakuInlineParserTest {

    private fun parse(text: String, emotes: List<DanmakuEmote>? = null) =
        DanmakuInlineParser.parse(
            text = text,
            liveEmotes = emotes,
            showHighLikeIcon = false,
            isHighLiked = false,
        )

    /** 把分段还原成可读串：文本原样、表情记 EMO、点赞图标记 ICON。 */
    private fun render(text: String, segments: List<DanmakuInlineSegment>?): String? =
        segments?.joinToString("|") { seg ->
            when (seg) {
                is DanmakuInlineSegment.Text -> text.substring(seg.start, seg.end)
                is DanmakuInlineSegment.Emote -> "EMO"
                DanmakuInlineSegment.HighLikeIcon -> "ICON"
            }
        }

    private val smallEmote = DanmakuEmote(key = "[哇]", url = "https://example.com/small.png", large = false)
    private val largeEmote = DanmakuEmote(key = "[妙]", url = "https://example.com/large.png", large = true)

    @Test
    fun plainTextHasNoInlineSegments() {
        assertNull(parse("普通弹幕"))
    }

    @Test
    fun videoDanmakuBracketTokenIsUntouchedWithoutLiveEmotes() {
        // 没有弹幕自带表情时行为不变：交给评论表情面板兜底（测试环境没有面板数据 → null）
        assertNull(parse("[doge]"))
    }

    @Test
    fun blankTextRendersImagesOnly() {
        assertEquals("EMO|EMO", render("", parse("", listOf(smallEmote, largeEmote))))
    }

    @Test
    fun emoteNameWithoutBracketsDoesNotLeakNextToImage() {
        // 用户实测的重复场景：文本是表情名（不带方括号），图片旁边又多了一串名字
        assertEquals("EMO", render("哇", parse("哇", listOf(smallEmote))))
    }

    @Test
    fun bracketedTextWithUnbracketedKeyStillMatches() {
        assertEquals("EMO", render("[妙]", parse("[妙]", listOf(smallEmote.copy(key = "妙")))))
    }

    @Test
    fun unmatchedPlaceholderKeepsImageButDropsText() {
        // 占位符和 emots 的 key 对不上时，仍按「整条＝表情弹幕」处理：只画图，不留文字
        assertEquals("EMO", render("[哇]", parse("[哇]", listOf(largeEmote))))
    }

    @Test
    fun mixedTextKeepsSurroundingText() {
        assertEquals("1|EMO|哈哈", render("1[哇]哈哈", parse("1[哇]哈哈", listOf(smallEmote))))
    }

    @Test
    fun mixedTextMatchesBareEmoteName() {
        assertEquals("1|EMO|哈哈", render("1哇哈哈", parse("1哇哈哈", listOf(smallEmote))))
    }

    @Test
    fun emoteScaleFollowsSize() {
        val small = parse("[哇]", listOf(smallEmote))?.single() as DanmakuInlineSegment.Emote
        val large = parse("[妙]", listOf(largeEmote))?.single() as DanmakuInlineSegment.Emote
        assertEquals(EMOTE_SCALE_LIVE_SMALL, small.scale, 0.0001f)
        assertEquals(EMOTE_SCALE_LIVE_LARGE, large.scale, 0.0001f)
        assertEquals(EMOTE_SCALE_LIVE_LARGE, DanmakuInlineParser.maxEmoteScale(parse("", listOf(smallEmote, largeEmote))), 0.0001f)
    }

    @Test
    fun emoteWithoutUrlIsIgnored() {
        assertNull(parse("", listOf(DanmakuEmote(key = "[哇]", url = ""))))
    }

    @Test
    fun emoteBulgeFlagFollowsLiveEmote() {
        val small = parse("[哇]", listOf(smallEmote))?.single() as DanmakuInlineSegment.Emote
        val large = parse("[妙]", listOf(largeEmote))?.single() as DanmakuInlineSegment.Emote
        assertFalse(small.bulge)
        assertTrue(large.bulge)
    }

    /**
     * 跨轨道只看大表情：小表情按单轨道布局，出图尺寸仍按其真实倍数。
     * （回归点：紧凑轨道密度下 1.15 倍字形高 > 一条轨道高，旧逻辑会误判成跨 2 条轨道。）
     */
    @Test
    fun onlyLargeEmoteCountsTowardLaneSpan() {
        val smallSegments = parse("[哇]", listOf(smallEmote))
        val largeSegments = parse("[妙]", listOf(largeEmote))

        assertEquals(0f, DanmakuInlineParser.maxLaneSpanScale(smallSegments), 0.0001f)
        assertEquals(EMOTE_SCALE_LIVE_LARGE, DanmakuInlineParser.maxLaneSpanScale(largeSegments), 0.0001f)

        assertEquals(EMOTE_SCALE_LIVE_SMALL, DanmakuInlineParser.maxEmoteScale(smallSegments), 0.0001f)
        assertEquals(EMOTE_SCALE_LIVE_LARGE, DanmakuInlineParser.maxEmoteScale(largeSegments), 0.0001f)
    }

    @Test
    fun mixedEmotesTakeLargeScaleForLaneSpan() {
        val segments = parse("", listOf(smallEmote, largeEmote))
        assertEquals(EMOTE_SCALE_LIVE_LARGE, DanmakuInlineParser.maxLaneSpanScale(segments), 0.0001f)
    }

    /** 公共表情（1 倍）与直播小表情都不撑高底图，因此都不跨轨道。 */
    @Test
    fun nonBulgeEmoteNeverSpansLanes() {
        val panel = listOf(DanmakuInlineSegment.Emote(url = "https://example.com/panel.png"))
        val small = listOf(
            DanmakuInlineSegment.Emote(url = "https://example.com/small.png", scale = EMOTE_SCALE_LIVE_SMALL),
        )
        assertEquals(0f, DanmakuInlineParser.maxLaneSpanScale(panel), 0.0001f)
        assertEquals(0f, DanmakuInlineParser.maxLaneSpanScale(small), 0.0001f)
    }

    /** 纯文本弹幕没有表情段：跨轨道倍数为 0（与 [maxEmoteScale] 的约定一致）。 */
    @Test
    fun textOnlySegmentsHaveNoLaneSpanScale() {
        assertEquals(0f, DanmakuInlineParser.maxLaneSpanScale(null), 0.0001f)
        assertEquals(
            0f,
            DanmakuInlineParser.maxLaneSpanScale(listOf(DanmakuInlineSegment.Text(start = 0, end = 2))),
            0.0001f,
        )
    }
}
