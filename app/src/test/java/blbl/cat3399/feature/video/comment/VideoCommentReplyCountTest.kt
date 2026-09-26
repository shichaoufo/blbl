package blbl.cat3399.feature.video.comment

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主评论「二级评论数」取 `rcount` 而不是 `count`。
 *
 * 依据是真机接口实测（2026-09-26）：
 *  - `count=167 / rcount=129` → `/x/v2/reply/reply` 的 `page.count` = 129
 *  - `count=1 / rcount=0`     → `page.count` = 0、`replies` 为空
 * 后者就是「显示 1 条回复、点进去只有主评论自己」那个 bug。
 */
class VideoCommentReplyCountTest {
    private fun countOf(json: String): Int = parseVideoCommentReplyCount(JSONObject(json))

    @Test
    fun preferRcountOverCount() {
        // 实测样本：count 会多算 38
        assertEquals(129, countOf("""{"count":167,"rcount":129}"""))
    }

    @Test
    fun zeroReplyCommentMustNotShowOneReply() {
        // 实测样本 rpid=315094130241：楼接口返回 0 条回复，count 却是 1
        assertEquals(0, countOf("""{"count":1,"rcount":0}"""))
    }

    @Test
    fun fallBackToCountWhenRcountMissing() {
        assertEquals(7, countOf("""{"count":7}"""))
    }

    @Test
    fun missingBothIsZero() {
        assertEquals(0, countOf("{}"))
    }

    @Test
    fun negativeRcountIsClamped() {
        assertEquals(0, countOf("""{"count":3,"rcount":-2}"""))
    }

    @Test
    fun parsedItemCarriesRcount() {
        val json =
            JSONObject(
                """
                {
                  "rpid": 315094130241,
                  "mid": 1,
                  "count": 1,
                  "rcount": 0,
                  "ctime": 1700000000,
                  "like": 3,
                  "content": {"message": "hi"},
                  "member": {"mid": "1", "uname": "u"}
                }
                """.trimIndent(),
            )
        val item =
            parseVideoCommentReplyItem(
                obj = json,
                oid = 42L,
                contextTag = null,
                canOpenThread = true,
                upMid = 0L,
            )
        assertEquals(0, item?.replyCount)
        // 没有回复就不该生成预览、不该出现「查看全部 N 条回复」
        assertEquals(emptyList<VideoCommentReplyPreview>(), item?.replyPreviews)
    }
}
