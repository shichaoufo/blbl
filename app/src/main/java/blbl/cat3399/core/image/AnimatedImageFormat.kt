package blbl.cat3399.core.image

/**
 * 动图字节判定。**只看 magic bytes，不看 URL 后缀** —— 后缀可能被 CDN 改写或缺失。
 *
 * 抽成独立 object 是为了能直接在 JVM 单测里测（[blbl.cat3399.core.image.ImageLoader] 的
 * 静态初始化会碰 Android 类，单测里不能加载它）。
 */
internal object AnimatedImageFormat {
    /** `GIF87a` / `GIF89a` */
    fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 6 &&
            bytes[0].u == 0x47 && // 'G'
            bytes[1].u == 0x49 && // 'I'
            bytes[2].u == 0x46 && // 'F'
            bytes[3].u == 0x38 && // '8'
            (bytes[4].u == 0x37 || bytes[4].u == 0x39) && // '7' | '9'
            bytes[5].u == 0x61 // 'a'

    /** `RIFF....WEBP` —— 静态与动画 WebP 的前缀一样，只能确定"是 WebP"。 */
    fun isWebp(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0].u == 0x52 && // 'R'
            bytes[1].u == 0x49 && // 'I'
            bytes[2].u == 0x46 && // 'F'
            bytes[3].u == 0x46 && // 'F'
            bytes[8].u == 0x57 && // 'W'
            bytes[9].u == 0x45 && // 'E'
            bytes[10].u == 0x42 && // 'B'
            bytes[11].u == 0x50 // 'P'

    private val Byte.u: Int
        get() = toInt() and 0xFF
}
