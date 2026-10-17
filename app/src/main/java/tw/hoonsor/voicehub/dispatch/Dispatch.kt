package tw.hoonsor.voicehub.dispatch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 語音派工：把開發需求的原話送到利姆路的派工收件匣（40-利姆路／raphael-dispatch）。
 * 規格：D:\01-Project\40-利姆路\raphael-dispatch\docs\voice-dispatch-api.md
 *
 * 原則：APP 只負責聽寫＋送出＋唸回覆。不在 APP 端用 AI 改寫、判斷專案或拆單，
 * 所以觸發詞在本機用固定規則判斷，送出的 text 是觸發詞之後的原話。
 */
object DispatchTrigger {
    /** 觸發詞（長的放前面，避免「派工」先吃掉「幫我派工」） */
    private val triggers = listOf(
        "幫我派工", "派個工", "派工",
        "記一個開發需求", "記個開發需求", "記一個需求", "開發需求",
        "我有個點子", "我有一個點子", "有個點子",
        "跟利姆路說", "告訴利姆路", "叫利姆路", "利姆路",
    )
    private val leading = Regex("^[\\s，。、,.:：；;!！?？～~-]+")
    private val politePrefix = Regex("^(請|麻煩|嘿|欸|喂)[\\s，,、]*")

    /** 是派工指令就回傳要送出的原文（可能是空字串＝只講了觸發詞）；不是派工回 null */
    fun extract(utterance: String): String? {
        var t = utterance.trim().replace(leading, "")
        t = t.replace(politePrefix, "")
        val trigger = triggers.firstOrNull { t.startsWith(it) } ?: return null
        return t.substring(trigger.length).replace(leading, "").trim()
    }

    private val statusWords = Regex("狀態|進度|做好|好了沒|好了嗎|怎麼樣|做到哪|處理了沒")
    private val ticketNo = Regex("([0-9]+|[零〇一二兩三四五六七八九十百]+)\\s*號")

    /** 「第 12 號派工單進度如何」→ 12；不是查單回 null。要先於 [extract] 判斷 */
    fun statusQuery(utterance: String): Int? {
        val t = utterance.replace(Regex("\\s+"), "")
        if (!t.contains("單") && !t.contains("派工")) return null
        if (!statusWords.containsMatchIn(t)) return null
        val m = ticketNo.find(t) ?: return null
        return tw.hoonsor.voicehub.agent.ChineseNumber.parse(m.groupValues[1])
    }

    const val MAX_LENGTH = 5000
}

/** 派工結果 */
sealed interface DispatchOutcome {
    /** 201：唸 message 即可 */
    data class Sent(val ticketId: Int, val message: String, val guess: String?) : DispatchOutcome
    /** 400／413 等：內容問題，不暫存 */
    data class Rejected(val error: String) : DispatchOutcome
    /** 連線失敗／逾時：已暫存待補送 */
    data class Offline(val reason: String) : DispatchOutcome

    fun speech(): String = when (this) {
        is Sent -> message
        is Rejected -> "內容無法送出：$error"
        is Offline -> "派工網站連不上，請確認 Tailscale 有開。這段話已經先存在手機，下次連上會自動補送。"
    }
}

class DispatchClient(private val baseUrl: () -> String) {

    private class HttpResult(val code: Int, val body: String)

    private fun http(method: String, path: String, body: JSONObject?): HttpResult {
        val conn = (URL(baseUrl().trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResult(code, text)
        } finally {
            conn.disconnect()
        }
    }

    /** 只送 text 與 source_app，多送欄位伺服器會回 400 */
    suspend fun send(text: String): DispatchOutcome = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isEmpty()) return@withContext DispatchOutcome.Rejected("內容是空的")
        if (clean.length > DispatchTrigger.MAX_LENGTH) return@withContext DispatchOutcome.Rejected("超過 ${DispatchTrigger.MAX_LENGTH} 字")
        val r = try {
            http("POST", "/api/voice", JSONObject().put("text", clean).put("source_app", SOURCE_APP))
        } catch (e: Exception) {
            return@withContext DispatchOutcome.Offline(e.javaClass.simpleName + (e.message?.let { "：$it" } ?: ""))
        }
        parseSend(r.code, r.body)
    }

    suspend fun health(): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val r = http("GET", "/api/health", null)
            if (r.code !in 200..299) error("HTTP ${r.code}")
            JSONObject(r.body).optBoolean("ok", false)
        }
    }

    suspend fun ticket(id: Int): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val r = http("GET", "/api/tickets/$id", null)
            when (r.code) {
                in 200..299 -> JSONObject(r.body).let { it.optJSONObject("ticket") ?: it }
                404 -> error("找不到第 $id 號派工單")
                else -> error(errorOf(r.body) ?: "HTTP ${r.code}")
            }
        }
    }

    companion object {
        const val SOURCE_APP = "voice-control"
        const val DEFAULT_BASE = "https://hoonsoropenclaw.tail71361e.ts.net"

        fun errorOf(body: String): String? = runCatching { JSONObject(body).optString("error").ifBlank { null } }.getOrNull()

        /** 純邏輯，有 JVM 測試 */
        fun parseSend(code: Int, body: String): DispatchOutcome = when (code) {
            201, 200 -> {
                val j = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
                val ticket = j.optJSONObject("ticket")
                val id = ticket?.optInt("id", -1) ?: -1
                val message = j.optString("message").ifBlank { if (id > 0) "已送出第 $id 號派工單。" else "已送出派工單。" }
                DispatchOutcome.Sent(id, message, j.optString("guess").takeIf { it.isNotBlank() && it != "null" })
            }
            in 400..499 -> DispatchOutcome.Rejected(errorOf(body) ?: "HTTP $code")
            // 5xx：伺服器暫時有問題，當成連不上，暫存補送
            else -> DispatchOutcome.Offline("HTTP $code")
        }

        /** 派工單狀態的中文 */
        fun statusText(status: String): String = when (status) {
            "new" -> "待評估"
            "needs_info" -> "需要你補充說明"
            "awaiting_approval" -> "等你核准"
            "approved" -> "已核准"
            "in_progress" -> "進行中"
            "done" -> "已完成"
            "cancelled" -> "已取消"
            else -> status
        }
    }
}

/**
 * 連不上時的本機暫存（SharedPreferences "dispatch_queue"）。
 * 下次任何一次派工成功、或打開對話畫面時自動補送；每筆每次只送一次，避免重複開單。
 */
class DispatchQueue(context: Context) {
    private val p = context.getSharedPreferences("dispatch_queue", Context.MODE_PRIVATE)
    private val lock = Mutex()

    data class Item(val text: String, val queuedAt: Long)

    fun items(): List<Item> = decode(p.getString("items", "[]") ?: "[]")

    val size get() = items().size

    fun add(text: String) {
        val list = items() + Item(text, System.currentTimeMillis())
        p.edit().putString("items", encode(list)).apply()
    }

    private fun save(list: List<Item>) = p.edit().putString("items", encode(list)).apply()

    /** 依序補送；回傳（成功筆數、被拒絕的內容、仍待補送筆數） */
    suspend fun flush(client: DispatchClient): FlushReport = lock.withLock {
        val pending = items()
        if (pending.isEmpty()) return FlushReport(0, emptyList(), 0)
        var sent = 0
        val rejected = mutableListOf<String>()
        val remain = mutableListOf<Item>()
        var offline = false
        for (item in pending) {
            if (offline) {
                remain += item
                continue
            }
            when (val r = client.send(item.text)) {
                is DispatchOutcome.Sent -> sent++
                is DispatchOutcome.Rejected -> rejected += "${item.text.take(20)}…：${r.error}"
                is DispatchOutcome.Offline -> {
                    offline = true
                    remain += item
                }
            }
        }
        save(remain)
        FlushReport(sent, rejected, remain.size)
    }

    data class FlushReport(val sent: Int, val rejected: List<String>, val remaining: Int)

    companion object {
        fun encode(list: List<Item>): String = JSONArray().apply {
            list.forEach { put(JSONObject().put("text", it.text).put("queuedAt", it.queuedAt)) }
        }.toString()

        fun decode(s: String): List<Item> = runCatching {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Item(it.optString("text"), it.optLong("queuedAt")) } }
                .filter { it.text.isNotBlank() }
        }.getOrDefault(emptyList())
    }
}

/** 對外的派工服務：送出、失敗暫存、成功後順便補送 */
class DispatchService(context: Context, baseUrl: () -> String) {
    val client = DispatchClient(baseUrl)
    val queue = DispatchQueue(context)

    suspend fun submit(text: String): Pair<DispatchOutcome, DispatchQueue.FlushReport?> {
        val r = client.send(text)
        return when (r) {
            is DispatchOutcome.Offline -> {
                queue.add(text.trim())
                r to null
            }
            is DispatchOutcome.Sent -> r to (if (queue.size > 0) queue.flush(client) else null)
            is DispatchOutcome.Rejected -> r to null
        }
    }

    fun flushText(rep: DispatchQueue.FlushReport?): String? {
        if (rep == null || (rep.sent == 0 && rep.rejected.isEmpty())) return null
        return buildString {
            if (rep.sent > 0) append("另外補送了 ${rep.sent} 筆之前暫存的派工。")
            if (rep.rejected.isNotEmpty()) append("有 ${rep.rejected.size} 筆暫存的內容被拒收。")
        }
    }
}
