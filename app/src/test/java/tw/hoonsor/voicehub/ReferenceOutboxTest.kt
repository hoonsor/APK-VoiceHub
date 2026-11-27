package tw.hoonsor.voicehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.hoonsor.voicehub.share.ShareTarget
import tw.hoonsor.voicehub.share.ShareText
import tw.hoonsor.voicehub.share.UploadPolicy
import tw.hoonsor.voicehub.share.UploadPolicy.Action

/** 參考圖上傳：什麼情況補送、什麼情況放棄，以及 multipart 格式 */
class ReferenceOutboxTest {
    @Test fun successIsDone() {
        assertEquals(Action.DONE, UploadPolicy.actionFor(201))
    }

    @Test fun rejectedByGatewayIsDropped() {
        listOf(413, 415, 422).forEach { assertEquals(Action.DROP, UploadPolicy.actionFor(it)) }
    }

    @Test fun offlineAndServerErrorsAreKept() {
        listOf(null, 404, 500, 502, 503).forEach { assertEquals(Action.KEEP, UploadPolicy.actionFor(it)) }
    }

    @Test fun multipartParts() {
        assertEquals("--b\r\nContent-Disposition: form-data; name=\"safe\"\r\n\r\nfalse\r\n", UploadPolicy.field("b", "safe", "false"))
        assertTrue(UploadPolicy.fileHead("b", "image/png").endsWith("Content-Type: image/png\r\n\r\n"))
        assertEquals("\r\n--b--\r\n", UploadPolicy.tail("b"))
    }

    @Test fun heicIsTranscoded() {
        assertTrue(UploadPolicy.passThrough("image/jpeg"))
        assertFalse(UploadPolicy.passThrough("image/heic"))
        assertFalse(UploadPolicy.passThrough(null))
    }

    @Test fun youtubeShareText() {
        val t = "超清楚的 Remotion 教學 https://youtu.be/dQw4w9WgXcQ?si=abc"
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=abc", ShareText.firstUrl(t))
        assertEquals("超清楚的 Remotion 教學", ShareText.title(t))
        assertTrue(ShareText.isYouTube("https://m.youtube.com/watch?v=x"))
        assertFalse(ShareText.isYouTube("https://example.com/youtube.com/"))
        assertEquals(null, ShareText.firstUrl("沒有網址"))
    }

    @Test fun defaultTarget() {
        assertEquals(ShareTarget.VIDEO, ShareTarget.default(isLink = true, last = null))
        assertEquals(ShareTarget.VIDEO, ShareTarget.default(isLink = true, last = "poster")) // 海報不收連結
        assertEquals(ShareTarget.GAME_ART, ShareTarget.default(isLink = true, last = "game-art"))
        assertEquals(ShareTarget.POSTER, ShareTarget.default(isLink = false, last = null))
        assertEquals(ShareTarget.GAME_ART, ShareTarget.default(isLink = false, last = "game-art"))
        // 跟 101 號 targets.py 的 key 一致
        assertEquals(listOf("poster", "video", "game-art"), ShareTarget.entries.map { it.key })
    }

    @Test fun textOnlyMultipartEnd() {
        assertEquals("--b--\r\n", UploadPolicy.end("b"))
    }
}
