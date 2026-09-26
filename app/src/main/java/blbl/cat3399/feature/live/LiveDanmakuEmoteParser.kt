package blbl.cat3399.feature.live

import blbl.cat3399.core.model.DanmakuEmote
import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析 `DANMU_MSG` 里携带的直播表情。
 *
 * 字段结构来自实测抓包（435 条真实弹幕）：
 *
 * - `info[0][15].extra` 是一段 JSON **字符串**，里面的 `emots` 字典以文本占位符
 *   （如 `[哇]`）为 key，value 字段固定为
 *   `count / descript / emoji / emoticon_id / emoticon_unique / height / url / width`
 *   —— **没有 `size`**。混排弹幕的文本里含该占位符；纯表情弹幕的文本可能为空。
 * - `info[0][13]` 是纯表情对象（整条就是一张图），字段为
 *   `bulge_display / emoticon_unique / height / in_player_area / is_dynamic / url / width`
 *   —— 同样**没有 `size`**。
 * - 区分大小表情的唯一信号是 **`bulge_display == 1`**：它是**弹幕级**标记，
 *   `extra` 顶层和 `info[0][13]` 上都会出现。实测 435 条里只有 1 条为 1，
 *   正是装扮大表情 `upower_[...]`；行内表情（`emoji_*`，图片 20×20）恒为 0。
 *
 * 因此「大表情」按 `bulge_display` 判定；`size` 仅作老数据的兼容兜底。返回项里的
 * [DanmakuEmote.large] 表示该表情要大尺寸渲染（1.8 倍字形高、跨轨道占用）。
 */
internal object LiveDanmakuEmoteParser {

    fun parse(meta: JSONArray?): List<DanmakuEmote> {
        if (meta == null) return emptyList()
        val out = ArrayList<DanmakuEmote>(2)

        val extraRaw =
            runCatching { meta.optJSONObject(15)?.optString("extra", "") }
                .getOrNull()
                .orEmpty()
        if (extraRaw.isNotBlank()) {
            val extra = runCatching { JSONObject(extraRaw) }.getOrNull()
            // 大表情是整条弹幕的属性，不是单个表情的属性
            val bulge = extra != null && optIntLenient(extra, "bulge_display") == 1
            val emots = runCatching { extra?.optJSONObject("emots") }.getOrNull()
            if (emots != null) {
                val keys = emots.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val e = emots.optJSONObject(key) ?: continue
                    val url = e.optString("url", "").trim()
                    if (url.isEmpty()) continue
                    out.add(
                        DanmakuEmote(
                            key = key,
                            url = url,
                            large = bulge || optIntLenient(e, "size") >= 2,
                        )
                    )
                }
            }
        }

        if (out.isEmpty()) {
            // 纯表情路径：文本里没有可用的占位符（大表情弹幕一般也走这里）
            val legacy = runCatching { meta.optJSONObject(13) }.getOrNull()
            val url = legacy?.optString("url", "").orEmpty().trim()
            if (url.isNotEmpty()) {
                val bulge = optIntLenient(legacy, "bulge_display") == 1
                out.add(
                    DanmakuEmote(
                        key = "",
                        url = url,
                        large = bulge || optIntLenient(legacy, "size") >= 2,
                    )
                )
            }
        }

        return out
    }

    /**
     * 宽松取整数：B 站偶尔把数值字段发成字符串（如 `"size": "2"`），
     * 标准 org.json 的 `optInt` 会直接忽略字符串，这里显式兼容。
     */
    private fun optIntLenient(obj: JSONObject?, key: String, fallback: Int = 0): Int {
        if (obj == null || !obj.has(key)) return fallback
        return when (val v = obj.opt(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull() ?: fallback
            else -> fallback
        }
    }
}
