package tw.hoonsor.voicehub.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 上傳結果的分類（純邏輯，有 JVM 測試） */
object UploadPolicy {
    enum class Action { DONE, DROP, KEEP }

    /** 2xx 完成；閘道明確拒收（格式、大小）重送也沒用，丟掉；其他（連不上、5xx、404）留著下次補送 */
    fun actionFor(code: Int?): Action = when {
        code == null -> Action.KEEP
        code in 200..299 -> Action.DONE
        code == 413 || code == 415 || code == 422 -> Action.DROP
        else -> Action.KEEP
    }

    /** multipart 文字欄位 */
    fun field(boundary: String, name: String, value: String) =
        "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"

    /** multipart 檔案欄位的表頭 */
    fun fileHead(boundary: String, mime: String) =
        "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"share\"\r\nContent-Type: $mime\r\n\r\n"

    fun tail(boundary: String) = "\r\n--$boundary--\r\n"

    /** 閘道直接收的格式；其他（HEIC 等）在手機上先轉 JPEG */
    fun passThrough(mime: String?) = mime in setOf("image/jpeg", "image/png", "image/webp")
}

/**
 * 分享給語音總控的圖片：先複製到 APP 裡（分享來的 Uri 權限一關畫面就失效），再傳到 65 號海報工坊閘道。
 * 傳不出去（Tailscale 沒開、A 關機）就留著，開對話畫面或按「立即補送」時再送，跟語音派工的暫存同一種做法。
 * 不經過任何雲端，也不交給語音總控的 AI。
 */
class ReferenceOutbox(context: Context, private val baseUrl: () -> String) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "share-outbox").apply { mkdirs() }
    private val lock = Mutex()

    data class Report(val sent: Int, val dropped: List<String>, val remaining: Int, val message: String?)

    val size: Int get() = dir.listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

    suspend fun add(uri: Uri, note: String, safe: Boolean) = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val image = File(dir, "$id.img")
        val mime = app.contentResolver.getType(uri)
        val sendMime = if (UploadPolicy.passThrough(mime)) {
            app.contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(image).use { input.copyTo(it) } }
            mime!!
        } else {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
            FileOutputStream(image).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            "image/jpeg"
        }
        // 圖檔寫完才寫 json：json 是「這筆完整可送」的記號
        File(dir, "$id.json").writeText(JSONObject().put("note", note).put("safe", safe).put("mime", sendMime).toString())
    }

    /** 依序補送；連不上就停，剩下的留著 */
    suspend fun flush(): Report = lock.withLock {
        withContext(Dispatchers.IO) {
            var sent = 0
            var message: String? = null
            val dropped = mutableListOf<String>()
            val pending = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.lastModified() }
            for (meta in pending) {
                val image = File(dir, meta.name.removeSuffix(".json") + ".img")
                val (code, body) = runCatching { post(image, JSONObject(meta.readText())) }.getOrDefault(null to "")
                val action = UploadPolicy.actionFor(code)
                if (action == UploadPolicy.Action.KEEP) break
                if (action == UploadPolicy.Action.DONE) {
                    sent++
                    message = runCatching { JSONObject(body).optString("message") }.getOrNull()?.ifBlank { null } ?: message
                } else {
                    dropped += runCatching { JSONObject(body).optString("detail") }.getOrNull()?.ifBlank { null } ?: "閘道拒收（$code）"
                }
                image.delete()
                meta.delete()
            }
            Report(sent, dropped, size, message)
        }
    }

    private fun post(image: File, meta: JSONObject): Pair<Int, String> {
        val boundary = "voicehub" + UUID.randomUUID().toString().replace("-", "")
        val conn = URL(baseUrl().trimEnd('/') + "/api/references").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8_000
        conn.readTimeout = 60_000
        conn.setChunkedStreamingMode(64 * 1024)
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        try {
            conn.outputStream.use { out ->
                val text = UploadPolicy.field(boundary, "note", meta.optString("note")) +
                    UploadPolicy.field(boundary, "safe", meta.optBoolean("safe").toString()) +
                    UploadPolicy.field(boundary, "source", "voicehub") +
                    UploadPolicy.fileHead(boundary, meta.optString("mime", "image/jpeg"))
                out.write(text.toByteArray(Charsets.UTF_8))
                image.inputStream().use { it.copyTo(out) }
                out.write(UploadPolicy.tail(boundary).toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }
}
