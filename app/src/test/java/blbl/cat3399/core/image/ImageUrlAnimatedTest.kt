package blbl.cat3399.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动图 URL 的识别与「不追加 CDN 转换后缀」。
 *
 * 为什么必须这么做：评论缩略图会给 URL 追加 `@480w_360h_1c.webp` 让 CDN 裁图，
 * 而这个参数会把 GIF 压成第一帧的静态图 —— 动图必须原样请求。
 * 规则与 BbQ 的 `GlobalState.isAnimatedImageUrl` 一致。
 */
class ImageUrlAnimatedTest {
    @Test
    fun detectAnimatedSuffixes() {
        assertTrue(ImageUrl.isAnimatedImage("https://i0.hdslb.com/bfs/x.gif"))
        assertTrue(ImageUrl.isAnimatedImage("https://i0.hdslb.com/bfs/x.webp"))
        assertTrue(ImageUrl.isAnimatedImage("https://i0.hdslb.com/bfs/x.GIF"))
        assertTrue(ImageUrl.isAnimatedImage("https://i0.hdslb.com/bfs/x.WEBP"))
    }

    @Test
    fun ignoreQueryString() {
        assertTrue(ImageUrl.isAnimatedImage("https://i0.hdslb.com/bfs/x.gif?x-expires=1"))
        assertFalse(ImageUrl.isAnimatedImage("https://i0.hdslb.com/bfs/x.jpg?name=a.gif"))
    }

    @Test
    fun staticFormatsAreNotAnimated() {
        assertFalse(ImageUrl.isAnimatedImage("https://i0.hdslb.com/bfs/x.jpg"))
        assertFalse(ImageUrl.isAnimatedImage("https://i0.hdslb.com/bfs/x.png"))
        assertFalse(ImageUrl.isAnimatedImage(""))
        assertFalse(ImageUrl.isAnimatedImage(null))
    }

    @Test
    fun animatedThumbnailKeepsOriginalUrl() {
        val gif = "https://i0.hdslb.com/bfs/note/abc.gif"
        assertEquals(gif, ImageUrl.commentThumbnail(gif))
        assertEquals("https://i0.hdslb.com/bfs/note/abc.gif", ImageUrl.commentThumbnail("//i0.hdslb.com/bfs/note/abc.gif"))
    }

    @Test
    fun nonBfsHostKeepsOriginalUrl() {
        // 非 B站 CDN 不支持裁图参数，原样返回（静态图也一样）
        assertEquals("https://example.com/a.jpg", ImageUrl.commentThumbnail("https://example.com/a.jpg"))
    }

    @Test
    fun blankUrlIsNull() {
        assertNull(ImageUrl.commentThumbnail(null))
        assertNull(ImageUrl.commentThumbnail("   "))
    }
}
