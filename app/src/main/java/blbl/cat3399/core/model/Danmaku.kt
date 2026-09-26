package blbl.cat3399.core.model

/**
 * 直播弹幕自带的表情图（B 站 DANMU_MSG 下发的 emots 字段）。
 *
 * - [key]：文本里的占位符（如 `[哇]`）。纯表情弹幕没有占位符，这里为空串。
 * - [url]：表情图直链，随弹幕一起下发，不依赖表情面板。
 * - [large]：对应服务端 size=2 的大表情（约 1.8 倍字形高），否则为小表情（约 1.15 倍）。
 */
data class DanmakuEmote(
    val key: String,
    val url: String,
    val large: Boolean = false,
)

data class Danmaku(
    val timeMs: Int,
    val mode: Int,
    val text: String,
    val color: Int,
    val fontSize: Int,
    val weight: Int,
    val midHash: String? = null,
    val dmid: Long? = null,
    val attr: Int = 0,
    /** 直播弹幕自带的表情（视频弹幕为 null）。见 [DanmakuEmote]。 */
    val emotes: List<DanmakuEmote>? = null,
)

val Danmaku.isHighLiked: Boolean
    get() = (attr and (1 shl 2)) != 0
