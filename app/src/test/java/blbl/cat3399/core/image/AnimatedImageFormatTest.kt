package blbl.cat3399.core.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动图判定看**字节**而不是 URL 后缀：后缀可能被 CDN 改写（`.gif` 加 `@480w_360h_1c.webp`
 * 之后返回的就是静态 WebP 字节），也可能压根没有后缀。
 */
class AnimatedImageFormatTest {
    @Test
    fun detectsGif89a() {
        assertTrue(AnimatedImageFormat.isGif(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00)))
    }

    @Test
    fun detectsGif87a() {
        assertTrue(AnimatedImageFormat.isGif(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61, 0x01, 0x00)))
    }

    @Test
    fun rejectsPngJpegAndWebpAsGif() {
        // PNG
        assertFalse(AnimatedImageFormat.isGif(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        // JPEG
        assertFalse(AnimatedImageFormat.isGif(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)))
        // WebP（动画 WebP 的前缀与静态一致，靠 isWebp 识别、交给 ImageDecoder 处理）
        assertFalse(AnimatedImageFormat.isGif(webpHeader()))
        assertTrue(AnimatedImageFormat.isWebp(webpHeader()))
    }

    @Test
    fun rejectsTruncatedInput() {
        assertFalse(AnimatedImageFormat.isGif(byteArrayOf(0x47, 0x49, 0x46)))
        assertFalse(AnimatedImageFormat.isGif(byteArrayOf()))
        assertFalse(AnimatedImageFormat.isWebp(byteArrayOf(0x52, 0x49, 0x46, 0x46)))
    }

    private fun webpHeader(): ByteArray =
        byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // RIFF
            0xDA.toByte(), 0xE0.toByte(), 0x02, 0x00, // size
            0x57, 0x45, 0x42, 0x50, // WEBP
            0x56, 0x50, 0x38, 0x58, // VP8X
        )
}
