package tw.hoonsor.voicehub.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AiException(message: String) : Exception(message)

data class ChatMessage(val role: String, val content: String) {
    fun toJson(): JSONObject = JSONObject().put("role", role).put("content", content)
}

/** OpenAI 相容 API：Chat Completions、GET /models、Whisper 轉錄 */
object LlmClient {
    private const val TAG = "LlmClient"

    suspend fun chat(
        baseUrl: String,
        model: String,
        key: String,
        messages: List<ChatMessage>,
        temperature: Double = 0.2,
        maxTokens: Int = 1_200,
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
        // MiniMax：把思考內容與答案分開，content 只留最終答案
        if (baseUrl.contains("minimax", ignoreCase = true)) body.put("reasoning_split", true)

        val text = request("POST", baseUrl.trimEnd('/') + "/chat/completions", key, body.toString().toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", 45_000)
        val json = JSONObject(text)
        json.optJSONObject("base_resp")?.let { br ->
            val sc = br.optInt("status_code", 0)
            if (sc != 0) throw AiException("AI 服務錯誤（$sc）：" + br.optString("status_msg"))
        }
        val choices = json.optJSONArray("choices") ?: throw AiException("AI 回應格式不正確")
        choices.getJSONObject(0).getJSONObject("message").optString("content", "")
    }

    /** 測試按鈕：列出模型，順便確認 key 與網址 */
    suspend fun listModels(baseUrl: String, key: String): List<String> = withContext(Dispatchers.IO) {
        val text = request("GET", baseUrl.trimEnd('/') + "/models", key, null, null, 15_000)
        val arr = JSONObject(text).optJSONArray("data") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }
    }

    /** Whisper 轉錄（multipart/form-data） */
    suspend fun transcribe(baseUrl: String, key: String, model: String, wav: ByteArray, prompt: String): String =
        withContext(Dispatchers.IO) {
            val boundary = "----voicehub" + System.nanoTime()
            val out = java.io.ByteArrayOutputStream()
            fun field(name: String, value: String) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8))
            }
            field("model", model)
            field("language", "zh")
            field("response_format", "json")
            field("temperature", "0")
            field("prompt", prompt)
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
            out.write(wav)
            out.write("\r\n--$boundary--\r\n".toByteArray())
            val text = request(
                "POST", baseUrl.trimEnd('/') + "/audio/transcriptions", key, out.toByteArray(),
                "multipart/form-data; boundary=$boundary", 30_000,
            )
            JSONObject(text).optString("text").trim()
        }

    private fun request(method: String, url: String, key: String, body: ByteArray?, contentType: String?, readTimeout: Int): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            this.readTimeout = readTimeout
            setRequestProperty("Authorization", "Bearer $key")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            doOutput = body != null
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code ${url.substringAfter("//").substringBefore('/')}: ${text.take(300)}")
                throw AiException(
                    when (code) {
                        401, 403 -> "API key 無效或沒有權限（HTTP $code）"
                        404 -> "網址或模型名稱錯誤（HTTP 404）" + errorMessage(text)?.let { "：$it" }.orEmpty()
                        413 -> "錄音檔太大（HTTP 413）"
                        429 -> "呼叫太頻繁或額度用完（HTTP 429）"
                        else -> "服務錯誤（HTTP $code）" + errorMessage(text)?.let { "：$it" }.orEmpty()
                    },
                )
            }
            return text
        } catch (e: AiException) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            throw AiException("連不上網路（${e.message}）")
        } catch (e: java.net.SocketTimeoutException) {
            throw AiException("連線逾時")
        } catch (e: Exception) {
            throw AiException("連線失敗：${e.javaClass.simpleName} ${e.message.orEmpty()}")
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(text: String): String? = runCatching {
        val j = JSONObject(text)
        j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: j.optJSONObject("base_resp")?.optString("status_msg")
    }.getOrNull()
}
