package blbl.cat3399.feature.live

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直播表情大小判定的回归测试。
 *
 * 用例数据全部取自真实抓包（435 条 DANMU_MSG），核心回归点是：
 * **`emots` / `info[0][13]` 里都没有 `size` 字段**，
 * 唯一能区分大表情的是**弹幕级**的 `bulge_display == 1`。
 * 早期实现按 `size >= 2` 判定，导致所有表情都被当成小表情、永远不会跨轨道。
 */
class LiveDanmakuEmoteParserTest {
    /** 构造 `info[0]`：index 13 = 纯表情对象，index 15 = `{"extra": "..."}`。 */
    private fun meta(legacy: JSONObject?, extra: JSONObject?): JSONArray {
        val a = JSONArray()
        for (i in 0..15) a.put(JSONObject())
        if (legacy != null) a.put(13, legacy)
        if (extra != null) a.put(15, JSONObject().put("extra", extra.toString()))
        return a
    }

    /** 行内小表情：`emots` 的 value 没有 size。 */
    @Test
    fun inlineEmoticonWithoutSizeIsSmall() {
        val emots =
            JSONObject().put(
                "[捂脸]",
                JSONObject()
                    .put("count", 5)
                    .put("descript", "[捂脸]")
                    .put("emoji", "[捂脸]")
                    .put("emoticon_id", 222)
                    .put("emoticon_unique", "emoji_222")
                    .put("height", 20)
                    .put("width", 20)
                    .put("url", "http://i0.hdslb.com/bfs/live/e6073c68.png"),
            )
        val extra = JSONObject().put("dm_type", 0).put("bulge_display", 0).put("emots", emots)

        val r = LiveDanmakuEmoteParser.parse(meta(null, extra))

        assertEquals(1, r.size)
        assertEquals("[捂脸]", r[0].key)
        assertEquals("http://i0.hdslb.com/bfs/live/e6073c68.png", r[0].url)
        assertFalse(r[0].large)
    }

    /** 大表情：`dm_type=1` + `bulge_display=1`，图片在 `info[0][13]`（emots 为空）。 */
    @Test
    fun bulgeDisplayMarksLargeEmoticon() {
        val legacy =
            JSONObject()
                .put("bulge_display", 1)
                .put("emoticon_unique", "upower_[鸣潮群星交错集_懵]")
                .put("height", 20)
                .put("width", 20)
                .put("in_player_area", 1)
                .put("is_dynamic", 0)
                .put("url", "https://i0.hdslb.com/bfs/garb/b66a3235.png")
        val extra =
            JSONObject()
                .put("dm_type", 1)
                .put("bulge_display", 1)
                .put("emoticon_unique", "upower_[鸣潮群星交错集_懵]")

        val r = LiveDanmakuEmoteParser.parse(meta(legacy, extra))

        assertEquals(1, r.size)
        assertEquals("https://i0.hdslb.com/bfs/garb/b66a3235.png", r[0].url)
        assertTrue(r[0].large)
    }

    /** 混排弹幕里也带大表情标记时，这条弹幕的表情都按大尺寸渲染。 */
    @Test
    fun bulgeDisplayAppliesToAllEmotesOfTheDanmaku() {
        val emots =
            JSONObject().put(
                "[哇]",
                JSONObject()
                    .put("emoticon_unique", "room_12345_678")
                    .put("height", 20)
                    .put("width", 20)
                    .put("url", "http://i0.hdslb.com/bfs/live/650c3e22.png"),
            )
        val extra = JSONObject().put("dm_type", 1).put("bulge_display", 1).put("emots", emots)

        val r = LiveDanmakuEmoteParser.parse(meta(null, extra))

        assertEquals(1, r.size)
        assertTrue(r[0].large)
    }

    /** 普通纯表情弹幕（无 bulge 标记）仍是小表情。 */
    @Test
    fun pureEmoticonWithoutBulgeIsSmall() {
        val legacy =
            JSONObject()
                .put("url", "https://i0.hdslb.com/bfs/live/abc.png")
                .put("width", 20)
                .put("height", 20)

        val r = LiveDanmakuEmoteParser.parse(meta(legacy, null))

        assertEquals(1, r.size)
        assertFalse(r[0].large)
    }

    /** 老数据里 `size` 可能是字符串或数字，两种都按大表情处理。 */
    @Test
    fun sizeFieldStillHonouredForLegacyPayloads() {
        val asString = LiveDanmakuEmoteParser.parse(
            meta(JSONObject().put("url", "https://x/str.png").put("size", "2"), null),
        )
        assertTrue(asString[0].large)

        val asNumber = LiveDanmakuEmoteParser.parse(
            meta(JSONObject().put("url", "https://x/num.png").put("size", 2), null),
        )
        assertTrue(asNumber[0].large)

        val asOne = LiveDanmakuEmoteParser.parse(
            meta(JSONObject().put("url", "https://x/one.png").put("size", 1), null),
        )
        assertFalse(asOne[0].large)
    }

    /** 没有表情信息时返回空列表（调用方据此走纯文本路径）。 */
    @Test
    fun noEmoticonReturnsEmpty() {
        assertTrue(LiveDanmakuEmoteParser.parse(null).isEmpty())
        assertTrue(LiveDanmakuEmoteParser.parse(meta(null, null)).isEmpty())
    }

    /** 缺 url 的表情对象直接跳过，避免渲染出空图。 */
    @Test
    fun emoticonWithoutUrlIsSkipped() {
        val emots = JSONObject().put("[x]", JSONObject().put("width", 20).put("height", 20))
        val extra = JSONObject().put("emots", emots)
        assertTrue(LiveDanmakuEmoteParser.parse(meta(null, extra)).isEmpty())
    }
}
