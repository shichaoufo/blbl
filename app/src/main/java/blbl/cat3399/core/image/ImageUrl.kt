package blbl.cat3399.core.image

import blbl.cat3399.core.net.BiliClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ImageUrl {
    private fun imageQuality(): String = runCatching { BiliClient.prefs.imageQuality }.getOrDefault("medium")

    private fun normalize(url: String): String {
        val u = url.trim()
        val fixed = when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://") -> "https://" + u.removePrefix("http://")
            else -> u
        }
        return fixed
    }

    private fun resized(url: String?, suffix: String): String? {
        val u = url ?: return null
        if (u.isBlank()) return null
        val normalized = normalize(u)
        val httpUrl = normalized.toHttpUrlOrNull() ?: return normalized
        val host = httpUrl.host.lowercase()
        val supportsResize =
            (host == "hdslb.com" ||
                host.endsWith(".hdslb.com") ||
                host == "biliimg.com" ||
                host.endsWith(".biliimg.com")) &&
                httpUrl.encodedPath.contains("/bfs/")
        if (!supportsResize) return normalized

        val queryIndex = normalized.indexOf('?')
        val base = if (queryIndex >= 0) normalized.substring(0, queryIndex) else normalized
        val query = if (queryIndex >= 0) normalized.substring(queryIndex) else ""
        if (base.contains("@")) return normalized
        return base + suffix + query
    }

    fun cover(url: String?): String? {
        val suffix = when (imageQuality()) {
            "small" -> "@320w_180h_1c.webp"
            "large" -> "@640w_360h_1c.webp"
            else -> "@480w_270h_1c.webp"
        }
        return resized(url, suffix)
    }

    fun poster(url: String?): String? {
        val suffix = when (imageQuality()) {
            "small" -> "@240w_340h_1c.webp"
            "large" -> "@480w_680h_1c.webp"
            else -> "@360w_510h_1c.webp"
        }
        return resized(url, suffix)
    }

    fun avatar(url: String?): String? = resized(url, "@80w_80h_1c.webp")

    /**
     * 动图（GIF / 动画 WebP）判定：只看 path 后缀、忽略查询参数，大小写不敏感。
     *
     * B站 CDN 对动图**保留原始后缀**（`.gif` / `.webp`），因此可以用后缀判断；
     * 与 BbQ 的 `GlobalState.isAnimatedImageUrl` 同一套规则。
     */
    fun isAnimatedImage(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return false
        val queryIndex = raw.indexOf('?')
        val path = if (queryIndex >= 0) raw.substring(0, queryIndex) else raw
        val lower = path.lowercase()
        return lower.endsWith(".gif") || lower.endsWith(".webp")
    }

    fun commentThumbnail(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return null
        // 动图**不能**追加 CDN 转换后缀：`@480w_360h_1c.webp` 会把 GIF 压成第一帧的静态图，
        // 那样即便本地能播也永远只会看到静止画面。这里原样返回（BbQ 同做法）。
        if (isAnimatedImage(raw)) return normalize(raw)

        val suffix = when (imageQuality()) {
            "small" -> "@320w_240h_1c.webp"
            "large" -> "@640w_480h_1c.webp"
            else -> "@480w_360h_1c.webp"
        }
        return resized(url, suffix)
    }
}
