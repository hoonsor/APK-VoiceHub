package tw.hoonsor.voicehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
