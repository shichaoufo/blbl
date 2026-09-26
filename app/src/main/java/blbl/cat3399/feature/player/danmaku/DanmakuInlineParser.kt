package blbl.cat3399.feature.player.danmaku

import blbl.cat3399.core.emote.ReplyEmotePanelRepository
import blbl.cat3399.core.model.DanmakuEmote
import blbl.cat3399.feature.player.danmaku.model.DanmakuInlineSegment
import blbl.cat3399.feature.player.danmaku.model.EMOTE_SCALE_LIVE_LARGE
import blbl.cat3399.feature.player.danmaku.model.EMOTE_SCALE_LIVE_SMALL

/**
 * 行内表情解析：把弹幕文本切成 [文本段 | 表情段] 序列（供引擎测量/绘制与缓存烘焙共用）。
 *
 * 表情有两个来源，优先级从高到低：
 * 1. 弹幕自带的表情（[DanmakuEmote]）：直播 `DANMU_MSG` 的 `emots` 会带上图片直链，
 *    房间专属表情 / 大表情都不在公共表情面板里，只能靠它。
 *    占位符在文本里的形态不固定（`[哇]` / 不带方括号的 `哇`），两种都做匹配。
 * 2. 评论区表情面板（[ReplyEmotePanelRepository]）：覆盖 `[doge]` 这类公共表情，
 *    视频弹幕和直播弹幕都走这条兜底。
 *
 * 特殊情形：弹幕自带表情、但文本里找不到任何对应占位符时，判定为「整条＝表情弹幕」。
 * B 站这种情况下给的文本往往就是表情名，画出来会和图片重复（表情旁边多出一串名字），
 * 因此**只渲染图片、不渲染文本** —— 与 BbQ 的纯表情分支一致
 * （BbQ 在 `emoticonUrl` 非空时只烘焙表情图，文本不参与渲染）。
 *
 * 返回 null 表示这条弹幕没有任何行内图（含点赞图标），调用方按纯文本处理。
 */
internal object DanmakuInlineParser {
    /** 一次匹配结果：[segment] 占 [length] 个字符，来源是弹幕自带表情还是公共表情面板。 */
    private class Match(
        val segment: DanmakuInlineSegment.Emote,
        val length: Int,
        val fromLiveEmotes: Boolean,
    )

    fun parse(
        text: String,
        liveEmotes: List<DanmakuEmote>?,
        showHighLikeIcon: Boolean,
        isHighLiked: Boolean,
    ): List<DanmakuInlineSegment>? {
        val out = ArrayList<DanmakuInlineSegment>(8)
        val hasIcon = showHighLikeIcon && isHighLiked
        if (hasIcon) out.add(DanmakuInlineSegment.HighLikeIcon)

        var lastTextStart = 0
        var pos = 0
        var liveMatched = 0
        var panelMatched = 0

        while (pos < text.length) {
            val hit = matchAt(text = text, pos = pos, liveEmotes = liveEmotes)
            if (hit != null) {
                if (pos > lastTextStart) {
                    out.add(DanmakuInlineSegment.Text(start = lastTextStart, end = pos))
                }
                out.add(hit.segment)
                if (hit.fromLiveEmotes) liveMatched++ else panelMatched++
                pos += hit.length
                lastTextStart = pos
                continue
            }
            if (text[pos] == '[') {
                // 没能解析的 [xxx] 整体跳过：不在方括号内部做裸名匹配，
                // 否则 "[doge]" 可能被拆成 "[ + 图 + e]"
                val close = text.indexOf(']', startIndex = pos + 1)
                pos = if (close < 0) pos + 1 else close + 1
            } else {
                pos++
            }
        }

        if (liveMatched == 0 && panelMatched == 0 && !liveEmotes.isNullOrEmpty()) {
            // 整条＝表情弹幕：只画图片，不画文本（文本通常是表情名，画出来会与图片重复）
            val images = ArrayList<DanmakuInlineSegment>(liveEmotes.size + 1)
            if (hasIcon) images.add(DanmakuInlineSegment.HighLikeIcon)
            var count = 0
            for (e in liveEmotes) {
                if (e.url.isBlank()) continue
                images.add(DanmakuInlineSegment.Emote(url = e.url, scale = liveScale(e), bulge = e.large))
                count++
            }
            return if (count > 0) images else null
        }

        if (out.isEmpty()) return null
        if (lastTextStart < text.length) {
            out.add(DanmakuInlineSegment.Text(start = lastTextStart, end = text.length))
        }
        return out
    }

    /** 表情段里最大的缩放倍数（决定底图高度与绘制尺寸）；没有表情段返回 0。 */
    fun maxEmoteScale(segments: List<DanmakuInlineSegment>?): Float {
        if (segments == null) return 0f
        var maxScale = 0f
        for (seg in segments) {
            if (seg is DanmakuInlineSegment.Emote && seg.scale > maxScale) maxScale = seg.scale
        }
        return maxScale
    }

    /**
     * 跨轨道判定用的倍数：**只统计大表情**。
     *
     * 小表情（1.15 倍）与公共表情（1 倍）一律视作不撑高底图，恒定只占一条轨道；
     * 这样紧凑轨道密度下也不会因为「1.15 倍字形高 > 一条轨道高」而多占一条。
     * 没有大表情时返回 0，调用方据此直接按单轨道处理。
     */
    fun maxLaneSpanScale(segments: List<DanmakuInlineSegment>?): Float {
        if (segments == null) return 0f
        var maxScale = 0f
        for (seg in segments) {
            if (seg !is DanmakuInlineSegment.Emote || !seg.bulge) continue
            if (seg.scale > maxScale) maxScale = seg.scale
        }
        return maxScale
    }

    private fun liveScale(emote: DanmakuEmote): Float =
        if (emote.large) EMOTE_SCALE_LIVE_LARGE else EMOTE_SCALE_LIVE_SMALL

    /**
     * 在 [pos] 处尝试匹配一个行内表情；匹配不到返回 null。
     *
     * 方括号占位符优先查弹幕自带表情（先按 `[哇]` 精确匹配，再按去掉方括号的 `哇` 匹配），
     * 查不到再交给公共表情面板；不带方括号的位置只认弹幕自带的表情名。
     */
    private fun matchAt(text: String, pos: Int, liveEmotes: List<DanmakuEmote>?): Match? {
        if (text[pos] == '[') {
            val close = text.indexOf(']', startIndex = pos + 1)
            if (close > pos + 1) {
                val token = text.substring(pos, close + 1)
                val live = findLiveEmote(token, liveEmotes)
                    ?: findLiveEmote(token.substring(1, token.length - 1), liveEmotes)
                if (live != null) {
                    return Match(
                        segment = DanmakuInlineSegment.Emote(url = live.url, scale = liveScale(live), bulge = live.large),
                        length = token.length,
                        fromLiveEmotes = true,
                    )
                }
                val panelUrl = ReplyEmotePanelRepository.urlForToken(token)
                if (panelUrl != null && panelUrl.startsWith("http")) {
                    return Match(
                        segment = DanmakuInlineSegment.Emote(url = panelUrl),
                        length = token.length,
                        fromLiveEmotes = false,
                    )
                }
            }
            return null
        }

        if (!liveEmotes.isNullOrEmpty()) {
            for (e in liveEmotes) {
                if (e.url.isBlank()) continue
                val len = bareNameLengthAt(text, pos, e.key) ?: continue
                return Match(
                    segment = DanmakuInlineSegment.Emote(url = e.url, scale = liveScale(e), bulge = e.large),
                    length = len,
                    fromLiveEmotes = true,
                )
            }
        }
        return null
    }

    /** 精确匹配（key == name）优先，其次按去掉方括号后的名字匹配。 */
    private fun findLiveEmote(name: String, liveEmotes: List<DanmakuEmote>?): DanmakuEmote? {
        if (liveEmotes.isNullOrEmpty() || name.isEmpty()) return null
        for (e in liveEmotes) {
            if (e.key == name && e.url.isNotBlank()) return e
        }
        return null
    }

    /** [key] 在 [pos] 处的可匹配长度：`[哇]` 这种带括号的名字也允许只出现 `哇`。 */
    private fun bareNameLengthAt(text: String, pos: Int, key: String): Int? {
        if (key.isEmpty() || key.startsWith("[")) {
            val bare = stripBrackets(key)
            return if (bare.isNotEmpty() && text.startsWith(bare, pos)) bare.length else null
        }
        return if (text.startsWith(key, pos)) key.length else null
    }

    private fun stripBrackets(key: String): String =
        if (key.length >= 2 && key.startsWith("[") && key.endsWith("]")) key.substring(1, key.length - 1) else key
}
