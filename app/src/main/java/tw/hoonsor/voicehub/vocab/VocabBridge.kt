package tw.hoonsor.voicehub.vocab

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tw.hoonsor.voicehub.agent.AgentApi
import tw.hoonsor.voicehub.agent.AgentResult
import tw.hoonsor.vocab.VocabClient
import tw.hoonsor.vocab.VocabTerm
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 語音總控和語音個人詞庫中心的所有接點。
 * 語音流程只讀本機快取；同步在背景做，失敗只記 log。沒設權杖時所有方法都回到原本的行為。
 */
class VocabBridge(context: Context, private val scope: CoroutineScope, private val token: () -> String?) {
    val client = VocabClient(VocabClient.DEFAULT_BASE_URL, ENTRY, context.filesDir, token)

    private val syncing = AtomicBoolean(false)
    @Volatile private var lastAttempt = 0L
    @Volatile private var lastClientId: String? = null

    /** 給 Whisper 的提示：基本句＋常用詞 */
    fun sttPrompt(base: String): String {
        syncIfStale()
        return buildSttPrompt(base, client.terms().map { it.term })
    }

    /** 辨識後替換開啟替換的別名；Android 內建辨識沒有提示可用，這裡是唯一的手段 */
    fun correct(text: String): String = try {
        syncIfStale()
        client.correct(text).text.also(client::noteUsage)
    } catch (e: Exception) {
        Log.w(TAG, "correct failed", e)
        text
    }

    fun plannerNote(): String = buildPlannerNote(client.terms())

    fun syncIfStale(now: Long = System.currentTimeMillis()) {
        if (now - lastAttempt < MIN_INTERVAL_MS) return
        syncInBackground()
    }

    fun syncInBackground(done: (() -> Unit)? = null) {
        if (!syncing.compareAndSet(false, true)) {
            done?.invoke()
            return
        }
        lastAttempt = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try {
                client.sync().error?.let { Log.i(TAG, "sync: $it") }
            } catch (e: Exception) {
                Log.w(TAG, "sync failed", e)
            } finally {
                syncing.set(false)
            }
            done?.invoke()
        }
    }

    /** 已裝 APP 的名稱提案到候選區；只送名稱，不送其他資料 */
    fun proposeApps(labels: List<String>, done: (Int) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val names = labels.map { it.trim() }.filter { it.length in 2..40 }.distinct()
            names.forEach { client.enqueuePropose(it, emptyList(), "app", "app") }
            client.sync()
            done(names.size)
        }
    }

    suspend fun execute(action: String, params: JSONObject, dryRun: Boolean): AgentResult {
        if (token()?.isNotBlank() != true) return AgentResult.fail(AgentApi.UNAVAILABLE, "還沒設定詞庫權杖，請到設定頁貼上。")
        return when (action) {
            "vocab_add" -> add(params, dryRun)
            "vocab_undo" -> undo(dryRun)
            "vocab_status" -> status(dryRun)
            else -> AgentResult.fail(AgentApi.NOT_FOUND, "沒有這個動作：$action")
        }
    }

    private suspend fun add(params: JSONObject, dryRun: Boolean): AgentResult {
        val term = params.optString("term").trim()
        if (term.isEmpty() || term.length > 40) return AgentResult.fail(AgentApi.INVALID_PARAMS, "要記住哪個詞？")
        val list = params.optJSONArray("aliases")
        val aliases = List(list?.length() ?: 0) { list!!.optString(it).trim() }.filter { it.isNotEmpty() }
        val heard = if (aliases.isEmpty()) "" else "，常被聽成${aliases.joinToString("、")}"
        if (dryRun) return AgentResult.ok("（試跑）會記住：$term$heard")
        val r = withContext(Dispatchers.IO) {
            lastClientId = client.enqueueAdd(term, aliases, params.optString("category").ifBlank { "other" })
            client.sync()
        }
        val data = JSONObject().put("term", term)
        return if (r.error == null) {
            AgentResult.ok("記住了：$term$heard。說錯的話可以說「取消剛才那個詞」。", data)
        } else {
            AgentResult.ok("先記在手機上了：$term$heard。連上網路後才會生效。", data.put("queued", true))
        }
    }

    private suspend fun undo(dryRun: Boolean): AgentResult {
        if (dryRun) return AgentResult.ok("（試跑）會取消最近新增的詞")
        val ok = withContext(Dispatchers.IO) { client.undo(lastClientId).also { client.sync() } }
        lastClientId = null
        return if (ok) AgentResult.ok("好，已經取消剛才那個詞。") else AgentResult.fail(AgentApi.NOT_FOUND, "沒有可以取消的詞。")
    }

    private suspend fun status(dryRun: Boolean): AgentResult {
        if (dryRun) return AgentResult.ok("（試跑）會查詢詞庫狀態")
        val s = withContext(Dispatchers.IO) { client.remoteStatus() }
            ?: return AgentResult.fail(AgentApi.UNAVAILABLE, "詞庫連不上，請確認網路。")
        return AgentResult.ok("詞庫有 ${s.optInt("terms")} 個詞，${s.optInt("candidates")} 個候選等你確認。", s)
    }

    companion object {
        const val ENTRY = "voicehub"
        private const val TAG = "VocabBridge"
        private const val MIN_INTERVAL_MS = 5 * 60_000L
        private const val STT_LIMIT = 40 // 和輸入法相同，Whisper 提示太長後面會被截掉
        private const val PLANNER_LIMIT = 60

        fun buildSttPrompt(base: String, terms: List<String>): String {
            val t = terms.filter { it.length <= 30 }.take(STT_LIMIT)
            return if (t.isEmpty()) base else base + "常用詞彙：${t.joinToString("、")}。"
        }

        fun buildPlannerNote(terms: List<VocabTerm>): String = terms.take(PLANNER_LIMIT).joinToString("、") { t ->
            val heard = t.aliases.map { it.text }
            if (heard.isEmpty()) t.term else "${t.term}（常被聽成：${heard.joinToString("、")}）"
        }
    }
}
