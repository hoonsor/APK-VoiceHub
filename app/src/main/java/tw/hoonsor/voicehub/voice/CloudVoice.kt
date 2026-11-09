package tw.hoonsor.voicehub.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * MiniMax 雲端語音（t2a_v2 串流）：邊收邊唸，第一段聲音到就開始播。
 * 跟 69 號影片專案用同一個聲音（預設 Chinese (Mandarin)_News_Anchor）；只送要唸的回覆文字。
 * 失敗時丟例外，由 [Speaker] 退回手機內建朗讀。
 */
object CloudVoice {
    const val DEFAULT_VOICE = "Chinese (Mandarin)_News_Anchor"
    const val DEFAULT_MODEL = "speech-2.8-turbo"
    private const val SAMPLE_RATE = 24_000

    @Volatile private var track: AudioTrack? = null
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var stopped = false

    /** 播完才回來；回傳 true＝播出了聲音（或被 [stop] 打斷），false＝沒有收到聲音 */
    suspend fun speak(baseUrl: String, key: String, model: String, voiceId: String, text: String, rate: Float): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("model", model)
                .put("text", text)
                .put("stream", true)
                .put("stream_options", JSONObject().put("exclude_aggregated_audio", true))
                .put("language_boost", "Chinese")
                .put("voice_setting", JSONObject().put("voice_id", voiceId).put("speed", rate.coerceIn(0.5f, 2f).toDouble()).put("vol", 1).put("pitch", 0))
                .put("audio_setting", JSONObject().put("sample_rate", SAMPLE_RATE).put("format", "pcm").put("channel", 1))
            val conn = (URL(baseUrl.trimEnd('/') + "/t2a_v2").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            var played = false
            stopped = false
            val t = newTrack()
            track = t
            this@CloudVoice.conn = conn
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("MiniMax 語音 HTTP ${conn.responseCode}")
                }
                val parser = StreamParser()
                var written = 0L
                conn.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        currentCoroutineContext().ensureActive()
                        if (stopped) break
                        val chunk = parser.feed(line) ?: continue
                        if (chunk.isEmpty()) continue
                        if (!played) { t.play(); played = true }
                        t.write(chunk, 0, chunk.size)
                        written += chunk.size
                    }
                }
                parser.error?.let { throw IllegalStateException(it) }
                if (played) {
                    // 等緩衝區播完：16-bit 單聲道，每個 frame 2 bytes
                    val frames = (written / 2).toInt()
                    while (!stopped && t.playState == AudioTrack.PLAYSTATE_PLAYING && t.playbackHeadPosition < frames) {
                        currentCoroutineContext().ensureActive()
                        Thread.sleep(40)
                    }
                }
                played || stopped
            } catch (e: Exception) {
                if (stopped) true else throw e // 被使用者打斷：不算失敗，也不要改用內建朗讀重唸
            } finally {
                runCatching { conn.disconnect() }
                runCatching { t.stop() }
                t.release()
                if (track === t) track = null
                if (this@CloudVoice.conn === conn) this@CloudVoice.conn = null
            }
        }

    fun stop() {
        stopped = true
        runCatching { track?.pause(); track?.flush() }
        runCatching { conn?.disconnect() }
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min, SAMPLE_RATE)) // 約 0.5 秒
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * 解析串流的每一行：可能帶「data:」前綴，也可能是整行 JSON。
     * 回傳這行的 PCM bytes（沒有聲音回 null）；status=2 的最後一段不放音（避免整段重播）。
     * 16-bit PCM 一定要偶數 bytes，奇數時把最後一個 byte 留到下一段。純邏輯，有 JVM 測試。
     */
    class StreamParser {
        var error: String? = null
            private set
        private var carry: Byte? = null

        fun feed(rawLine: String): ByteArray? {
            val line = rawLine.trim().removePrefix("data:").trim()
            if (!line.startsWith("{")) return null
            val j = runCatching { JSONObject(line) }.getOrNull() ?: return null
            j.optJSONObject("base_resp")?.let { br ->
                val code = br.optInt("status_code", 0)
                if (code != 0) {
                    error = "MiniMax 語音錯誤（$code）：" + br.optString("status_msg")
                    return null
                }
            }
            val data = j.optJSONObject("data") ?: return null
            if (data.optInt("status", 1) == 2) return null
            val hex = data.optString("audio")
            if (hex.isEmpty()) return null
            var bytes = hexToBytes(hex)
            carry?.let { c -> bytes = byteArrayOf(c) + bytes; carry = null }
            if (bytes.size % 2 == 1) {
                carry = bytes.last()
                bytes = bytes.copyOf(bytes.size - 1)
            }
            return bytes
        }

        companion object {
            fun hexToBytes(hex: String): ByteArray {
                val n = hex.length / 2
                val out = ByteArray(n)
                for (i in 0 until n) {
                    out[i] = ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
                }
                return out
            }
        }
    }
}
