package tw.hoonsor.vocab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 語音入口端的詞庫快取與離線佇列（複製到 APP 使用，不要在 APP 內改）。
 *
 * - [terms] 只讀記憶體／本機快取，語音流程可以直接呼叫，絕不等網路。
 * - [sync] 會連網，要在背景執行緒呼叫；失敗時保留舊快取。
 * - 離線時的新增和提案先寫進佇列，下次 [sync] 補送；clientId 相同時伺服器不會重複新增。
 * - [token] 回傳 null 或空字串＝使用者還沒設定權杖，這時所有網路動作都跳過。
 */
class VocabClient(
    private val baseUrl: String,
    private val entry: String,
    dir: File,
    private val token: () -> String?,
) {
    data class Status(val revision: String?, val lastSyncAt: Long, val termCount: Int, val pending: Int, val lastError: String?)

    data class SyncResult(val changed: Boolean, val sent: Int, val error: String?)

    private val cacheFile = File(dir, "vocab-$entry.json")
    private val queueFile = File(dir, "vocab-$entry-queue.jsonl")
    private val lock = Any()
    private val usage = HashMap<String, Int>()

    @Volatile private var cached: List<VocabTerm>? = null
    @Volatile private var revision: String? = null
    @Volatile private var lastSyncAt = 0L
    @Volatile private var lastError: String? = null

    fun terms(): List<VocabTerm> {
        cached?.let { return it }
        return synchronized(lock) { cached ?: loadCache().also { cached = it } }
    }

    fun status(): Status {
        val n = terms().size
        return Status(revision, lastSyncAt, n, queueLines().size, lastError)
    }

    fun correct(text: String): ReplaceResult = VocabCore.applyReplacements(text, terms())

    /** 記下這句話用到哪些詞，下次 [sync] 批次回報，用來排序提示。只算次數，不記句子。 */
    fun noteUsage(text: String) {
        val used = terms().filter { text.contains(it.term, ignoreCase = true) }
        if (used.isEmpty()) return
        synchronized(usage) { for (t in used) usage[t.term] = (usage[t.term] ?: 0) + 1 }
    }

    fun sync(): SyncResult {
        val t = token()?.trim().orEmpty()
        if (t.isEmpty()) return SyncResult(false, 0, null)
        return try {
            terms()
            val sent = flushQueue(t)
            val since = revision?.let { "&since=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
            val r = request("GET", "/api/v1/snapshot?entry=$entry$since", t, null)
            var changed = false
            if (r.code == 200) {
                val snapshot = JSONObject(r.body)
                writeAtomic(cacheFile, r.body)
                val parsed = parse(snapshot)
                synchronized(lock) {
                    cached = parsed
                    revision = snapshot.getString("revision")
                }
                changed = true
            } else if (r.code != 304) {
                throw IllegalStateException("詞庫回應 ${r.code}")
            }
            flushUsage(t)
            lastSyncAt = System.currentTimeMillis()
            lastError = null
            SyncResult(changed, sent, null)
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            SyncResult(false, 0, lastError)
        }
    }

    /** 倬明確下令新增（用說的）。先排隊再送，斷網也不會遺失；回傳 clientId，可拿去 [undo]。 */
    fun enqueueAdd(term: String, aliases: List<String>, category: String): String =
        enqueue("/api/v1/terms", term, aliases, category, null)

    /** 自動來源的提案，進候選區等倬確認。source：correction、scan、app、calibrate。 */
    fun enqueuePropose(term: String, aliases: List<String>, category: String, source: String): String =
        enqueue("/api/v1/candidates", term, aliases, category, source)

    /** 取消最近一次新增；佇列裡還沒送出的就直接移除。 */
    fun undo(clientId: String?): Boolean {
        if (clientId != null && removeFromQueue(clientId)) return true
        val t = token()?.trim().orEmpty()
        if (t.isEmpty()) return false
        return try {
            val body = JSONObject().apply { if (clientId != null) put("clientId", clientId) }.toString()
            val r = request("POST", "/api/v1/undo", t, body)
            r.code == 200 && JSONObject(r.body).optString("outcome") == "undone"
        } catch (e: Exception) {
            lastError = e.message
            false
        }
    }

    /** 中心目前的候選數；連不上時回傳 null。 */
    fun remoteStatus(): JSONObject? {
        val t = token()?.trim().orEmpty()
        if (t.isEmpty()) return null
        return try {
            val r = request("GET", "/api/v1/status", t, null)
            if (r.code == 200) JSONObject(r.body) else null
        } catch (e: Exception) {
            lastError = e.message
            null
        }
    }

    private fun enqueue(path: String, term: String, aliases: List<String>, category: String, source: String?): String {
        val clientId = UUID.randomUUID().toString()
        val body = JSONObject()
            .put("term", term)
            .put("aliases", JSONArray(aliases))
            .put("category", category)
            .put("clientId", clientId)
        if (source != null) body.put("source", source)
        val line = JSONObject().put("path", path).put("body", body).toString()
        synchronized(lock) { queueFile.appendText(line + "\n") }
        return clientId
    }

    private fun queueLines(): List<String> = synchronized(lock) {
        if (queueFile.exists()) queueFile.readLines().filter { it.isNotBlank() } else emptyList()
    }

    private fun removeFromQueue(clientId: String): Boolean = synchronized(lock) {
        val lines = queueLines()
        val left = lines.filterNot { JSONObject(it).getJSONObject("body").optString("clientId") == clientId }
        if (left.size == lines.size) return false
        writeAtomic(queueFile, left.joinToString("") { it + "\n" })
        true
    }

    /** 一筆送成功才移除；碰到網路錯誤就停，留給下次。4xx 是內容有問題，重送也沒用，所以丟棄。 */
    private fun flushQueue(t: String): Int {
        var sent = 0
        for (line in queueLines()) {
            val item = JSONObject(line)
            val r = request("POST", item.getString("path"), t, item.getJSONObject("body").toString())
            if (r.code in 500..599 || r.code == 401 || r.code == 429) throw IllegalStateException("詞庫回應 ${r.code}")
            removeFromQueue(item.getJSONObject("body").getString("clientId"))
            if (r.code == 200) sent += 1
        }
        return sent
    }

    /** 次數只是排序參考，送失敗就放掉，不值得為它重試或寫檔 */
    private fun flushUsage(t: String) {
        val counts = synchronized(usage) { HashMap(usage).also { usage.clear() } }
        if (counts.isEmpty()) return
        val body = JSONObject().put("counts", JSONObject(counts as Map<*, *>)).toString()
        request("POST", "/api/v1/usage", t, body)
    }

    private fun loadCache(): List<VocabTerm> = try {
        if (!cacheFile.exists()) {
            emptyList()
        } else {
            val json = JSONObject(cacheFile.readText())
            revision = json.optString("revision").ifEmpty { null }
            parse(json)
        }
    } catch (_: Exception) {
        emptyList() // 快取壞掉就當作沒有，下次同步會重建
    }

    private fun parse(json: JSONObject): List<VocabTerm> {
        val terms = json.getJSONArray("terms")
        return List(terms.length()) { i ->
            val t = terms.getJSONObject(i)
            val aliases = t.optJSONArray("aliases") ?: JSONArray()
            VocabTerm(
                term = t.getString("term"),
                category = t.optString("category", "other"),
                pinned = t.optBoolean("pinned", false),
                aliases = List(aliases.length()) { n ->
                    val a = aliases.getJSONObject(n)
                    VocabAlias(a.getString("text"), a.optBoolean("replace", false))
                },
            )
        }
    }

    private class Response(val code: Int, val body: String)

    private fun request(method: String, path: String, t: String, body: String?): Response {
        val c = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 8_000
            c.readTimeout = 12_000
            c.setRequestProperty("Authorization", "Bearer $t")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else ""
            return Response(code, text)
        } finally {
            c.disconnect()
        }
    }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://voice-vocab-hub.vercel.app"
    }
}
