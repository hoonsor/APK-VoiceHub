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

    /** 只有文字欄位時的結尾（前一個欄位已經以 \r\n 結束） */
    fun end(boundary: String) = "--$boundary--\r\n"

    /** 閘道直接收的格式；其他（HEIC 等）在手機上先轉 JPEG */
    fun passThrough(mime: String?) = mime in setOf("image/jpeg", "image/png", "image/webp")
}

/**
 * 分享目標（跟 101 號喜好收件匣 targets.py 一致，改了兩邊一起改）。
 * 海報的圖片照舊直接傳 65 號閘道（既有反推流程）；其他送 101 號收件匣 /api/share。
 */
enum class ShareTarget(val key: String, val label: String, val images: Boolean, val links: Boolean) {
    POSTER("poster", "海報", images = true, links = false),
    VIDEO("video", "影片", images = true, links = true),
    GAME_ART("game-art", "遊戲美術", images = true, links = true);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }

        /** 分享畫面預設選哪個：連結預設影片（YouTube 最常見）；圖片沿用上次選的 */
        fun default(isLink: Boolean, last: String?): ShareTarget {
            val t = of(last)
            return if (isLink) (t?.takeIf { it.links } ?: VIDEO) else (t ?: POSTER)
        }
    }
}

/** 分享文字處理（純邏輯，有 JVM 測試） */
object ShareText {
    private val url = Regex("""https?://[^\s<>"'，。、）]+""", RegexOption.IGNORE_CASE)
    private val yt = Regex("""^https?://([a-z0-9-]+\.)?(youtube\.com|youtu\.be)/""", RegexOption.IGNORE_CASE)

    fun firstUrl(text: String?): String? = text?.let { url.find(it)?.value?.trimEnd('.', ',', '!', '?', ')') }

    fun isYouTube(u: String?): Boolean = u != null && yt.containsMatchIn(u)

    /** 分享文字去掉網址後剩下的部分（通常是影片標題），給畫面顯示 */
    fun title(text: String?): String = (text ?: "").replace(url, "").trim().trim('-', '|', '｜', ' ').take(120)
}

/**
 * 分享給語音總控的圖片與連結：圖片先複製到 APP 裡（分享來的 Uri 權限一關畫面就失效），再傳到電腦 A。
 * 海報 → 65 號海報工坊閘道 /api/references；影片、遊戲美術 → 101 號喜好收件匣 /api/share（101 號 docs/DESIGN.md）。
 * 傳不出去（Tailscale 沒開、A 關機）就留著，開對話畫面或按「立即補送」時再送，跟語音派工的暫存同一種做法。
 * 不經過任何雲端，也不交給語音總控的 AI。
 */
class ReferenceOutbox(context: Context, private val baseUrl: () -> String, private val inboxUrl: () -> String = baseUrl) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "share-outbox").apply { mkdirs() }
    private val lock = Mutex()

    data class Report(val sent: Int, val dropped: List<String>, val remaining: Int, val message: String?)

    val size: Int get() = dir.listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

    /** 連結（YouTube 等）：沒有檔案，只寫 json */
    suspend fun addLink(url: String, text: String, note: String, safe: Boolean, target: ShareTarget) = withContext(Dispatchers.IO) {
        File(dir, UUID.randomUUID().toString() + ".json").writeText(
            JSONObject().put("kind", "link").put("target", target.key).put("url", url).put("text", text.take(1000))
                .put("note", note).put("safe", safe).toString(),
        )
    }

    suspend fun add(uri: Uri, note: String, safe: Boolean, target: ShareTarget = ShareTarget.POSTER) = withContext(Dispatchers.IO) {
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
        File(dir, "$id.json").writeText(JSONObject().put("kind", "image").put("target", target.key).put("note", note).put("safe", safe).put("mime", sendMime).toString())
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
        val target = meta.optString("target", "poster").ifBlank { "poster" }   // v0.14 以前的暫存沒有 target：都是海報
        val isLink = meta.optString("kind") == "link"
        val toWorkshop = target == "poster" && !isLink
        val endpoint = if (toWorkshop) baseUrl().trimEnd('/') + "/api/references" else inboxUrl().trimEnd('/') + "/api/share"
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8_000
        conn.readTimeout = 60_000
        conn.setChunkedStreamingMode(64 * 1024)
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        try {
            conn.outputStream.use { out ->
                var text = UploadPolicy.field(boundary, "note", meta.optString("note")) +
                    UploadPolicy.field(boundary, "safe", meta.optBoolean("safe").toString()) +
                    UploadPolicy.field(boundary, "source", "voicehub")
                if (!toWorkshop) text += UploadPolicy.field(boundary, "target", target)
                if (isLink) {
                    text += UploadPolicy.field(boundary, "url", meta.optString("url")) + UploadPolicy.field(boundary, "text", meta.optString("text"))
                    out.write((text + UploadPolicy.end(boundary)).toByteArray(Charsets.UTF_8))
                } else {
                    out.write((text + UploadPolicy.fileHead(boundary, meta.optString("mime", "image/jpeg"))).toByteArray(Charsets.UTF_8))
                    image.inputStream().use { it.copyTo(out) }
                    out.write(UploadPolicy.tail(boundary).toByteArray(Charsets.UTF_8))
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }
}
