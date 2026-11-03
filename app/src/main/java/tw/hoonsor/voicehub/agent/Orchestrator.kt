package tw.hoonsor.voicehub.agent

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.voicehub.ai.AiException
import tw.hoonsor.voicehub.ai.ChatMessage
import tw.hoonsor.voicehub.ai.LlmClient
import tw.hoonsor.voicehub.ai.Plan
import tw.hoonsor.voicehub.ai.PlanParseException
import tw.hoonsor.voicehub.ai.PlannedCall
import tw.hoonsor.voicehub.ai.Planner
import tw.hoonsor.voicehub.data.Settings
import tw.hoonsor.voicehub.dispatch.DispatchTrigger
import java.time.LocalDateTime

/**
 * 一句話的完整處理流程：
 * 1. AI 把話轉成動作（主力 MiniMax → 備援 Groq → 離線句型）
 * 2. 依序執行；destructive 先問、AMBIGUOUS 請倬選、NEEDS_UI 開畫面
 * 3. 有失敗或需要解讀資料時，把結果再給 AI 一輪，產生口語回報
 */
class Orchestrator(
    private val context: Context,
    private val settings: Settings,
    private val registry: ToolRegistry,
) {
    enum class Phase { THINKING, EXECUTING }

    sealed interface Event {
        data class PhaseChanged(val phase: Phase) : Event
        data class ActionDone(val title: String, val result: AgentResult) : Event
        data class Notice(val text: String) : Event
    }

    data class Reply(
        val text: String,
        /** 有動作跳到其他 APP，對話視窗可以收起 */
        val leavesApp: Boolean = false,
        /** 回覆是問句，應自動再聽 */
        val expectsAnswer: Boolean = false,
        /** 正在等確認 */
        val confirming: Boolean = false,
    )

    private data class Pending(val call: PlannedCall, val token: String, val utterance: String)

    private var pending: Pending? = null
    /** 只說了「派工」或「專案規劃」沒講內容，下一句話就是內容；記住是哪一種 */
    private var awaitingDispatch: DispatchTrigger.Request? = null
    private val history = ArrayDeque<ChatMessage>()

    val hasPending get() = pending != null

    fun reset() {
        pending = null
        awaitingDispatch = null
        history.clear()
    }

    suspend fun handle(utterance: String, onEvent: (Event) -> Unit): Reply {
        pending?.let { p ->
            when {
                OfflineParser.isNegative(utterance) -> {
                    pending = null
                    return remember(utterance, Reply("好，已取消。"))
                }
                OfflineParser.isAffirmative(utterance) -> {
                    pending = null
                    return confirm(p, onEvent)
                }
                else -> pending = null // 講了別的事：放棄確認，當新指令處理
            }
        }

        // 語音派工：本機規則判斷、原話直送，不經 AI（規格要求不在 APP 端改寫）
        awaitingDispatch?.let { waiting ->
            awaitingDispatch = null
            if (OfflineParser.isNegative(utterance) && utterance.length <= 6) {
                return remember(utterance, Reply(if (waiting.planning) "好，先不規劃。" else "好，不派工了。"))
            }
            val text = waiting.copy(text = utterance.trim()).payload
            return runDirect(utterance, "dispatch_send", JSONObject().put("text", text), onEvent)
        }
        DispatchTrigger.statusQuery(utterance)?.let { id ->
            return runDirect(utterance, "dispatch_status", JSONObject().put("id", id), onEvent)
        }
        DispatchTrigger.parse(utterance)?.let { req ->
            if (req.text.isEmpty()) {
                awaitingDispatch = req
                val ask = if (req.planning) "好，請說你的想法，想做什麼、給誰用都可以。" else "好，要派什麼工？請直接說需求。"
                return remember(utterance, Reply(ask, expectsAnswer = true))
            }
            return runDirect(utterance, "dispatch_send", JSONObject().put("text", req.payload), onEvent)
        }

        // 「回家」這類常用地點不用問 AI，直接導航（省掉一次 AI 往返）
        Places.shortcut(utterance, Places.parse(settings.places))?.let { name ->
            return runDirect(utterance, "map_navigate", JSONObject().put("destination", name), onEvent)
        }
        // 「去＋地點」直接用預設交通方式（機車）導航，不讓 AI 誤判成待辦事項
        Places.goTo(utterance)?.let { place ->
            return runDirect(utterance, "map_navigate", JSONObject().put("destination", place), onEvent)
        }

        onEvent(Event.PhaseChanged(Phase.THINKING))
        val messages = mutableListOf(ChatMessage("system", Planner.systemPrompt(registry.catalogs.value, LocalDateTime.now(), Places.parse(settings.places).keys)))
        messages += history
        messages += ChatMessage("user", utterance)

        var offline = false
        var plan: Plan = try {
            val raw = complete(messages)
            messages += ChatMessage("assistant", raw)
            Planner.parsePlan(raw)
        } catch (e: Exception) {
            if (e !is AiException && e !is PlanParseException) throw e
            val call = OfflineParser.parse(utterance)
                ?: return Reply("AI 目前連不上（${e.message}），這句話離線模式也聽不懂。")
            offline = true
            onEvent(Event.Notice("AI 連不上，改用離線模式"))
            Plan("", listOf(PlannedCall(registry.selfPackage, call.action, call.params)))
        }
        plan = adjusted(plan, utterance)

        var leaves = false
        var round = 0
        while (true) {
            if (plan.calls.isEmpty()) {
                val text = plan.say.ifBlank { "嗯，我沒聽懂，可以再說一次嗎？" }
                return remember(utterance, Reply(text, leaves, expectsAnswer = isQuestion(text)))
            }
            onEvent(Event.PhaseChanged(Phase.EXECUTING))
            val results = mutableListOf<Pair<PlannedCall, AgentResult>>()
            for (call in plan.calls) {
                val r = registry.execute(call.target, call.action, call.params, utterance = utterance).let { r ->
                    val note = if (r.ok) AlarmRouting.fallbackNote(call.action, registry.resolve(call.target, call.action)?.builtin == true, utterance) else null
                    if (note == null) r else r.copy(summary = r.summary.ifBlank { "${titleOf(call)}完成" } + "；" + note)
                }
                onEvent(Event.ActionDone(titleOf(call), r))
                results += call to r
                if (r.data?.optBoolean("leavesApp") == true) leaves = true
                when (r.errorCode) {
                    AgentApi.NEEDS_CONFIRMATION -> {
                        val token = r.errorData?.optString("confirmToken").orEmpty()
                        pending = Pending(call, token, utterance)
                        val q = r.errorMessage?.ifBlank { null } ?: "「${titleOf(call)}」無法復原"
                        return remember(utterance, Reply("$q，確定要執行嗎？", leaves, expectsAnswer = true, confirming = true))
                    }
                    AgentApi.NEEDS_UI -> if (openUi(r)) leaves = true
                }
                if (!r.ok && r.errorCode != AgentApi.NEEDS_UI) break // 前一步失敗就不做後面的
            }

            // 派工的回覆一律照伺服器的 message 唸，不再交給 AI 改寫
            val needsFollowUp = !offline && round < 2 && results.none { it.first.action in BuiltinCatalog.DISPATCH_ACTIONS } && results.any { (c, r) ->
                (!r.ok && r.errorCode != AgentApi.NEEDS_UI) ||
                    (registry.riskOf(c.target, c.action) == Risk.READ && registry.catalogFor(c.target)?.builtin != true) ||
                    c.action == "app_list"
            }
            if (!needsFollowUp) {
                return remember(utterance, Reply(localSummary(results), leaves, expectsAnswer = results.any { !it.second.ok }))
            }

            onEvent(Event.PhaseChanged(Phase.THINKING))
            messages += ChatMessage("user", Planner.resultsMessage(results))
            plan = try {
                val raw = complete(messages)
                messages += ChatMessage("assistant", raw)
                adjusted(Planner.parsePlan(raw), utterance)
            } catch (e: Exception) {
                return remember(utterance, Reply(localSummary(results), leaves))
            }
            round++
        }
    }

    /** 鬧鐘／計時改送倬的鬧鐘、套用一次性規則（AlarmRouting） */
    private fun adjusted(plan: Plan, utterance: String): Plan =
        plan.copy(calls = plan.calls.map { AlarmRouting.adjust(it, registry.catalogs.value, utterance) })

    /** 不經 AI 直接執行一個內建動作，回覆就是動作的 summary（派工時＝伺服器的 message） */
    private suspend fun runDirect(utterance: String, action: String, params: JSONObject, onEvent: (Event) -> Unit): Reply {
        onEvent(Event.PhaseChanged(Phase.EXECUTING))
        val call = PlannedCall(registry.selfPackage, action, params)
        val r = registry.execute(call.target, action, params, utterance = utterance)
        onEvent(Event.ActionDone(titleOf(call), r))
        return remember(utterance, Reply(localSummary(listOf(call to r)), r.data?.optBoolean("leavesApp") == true))
    }

    private suspend fun confirm(p: Pending, onEvent: (Event) -> Unit): Reply {
        onEvent(Event.PhaseChanged(Phase.EXECUTING))
        val r = registry.execute(p.call.target, p.call.action, p.call.params, confirmToken = p.token, utterance = p.utterance)
        onEvent(Event.ActionDone(titleOf(p.call), r))
        val leaves = r.data?.optBoolean("leavesApp") == true || (r.errorCode == AgentApi.NEEDS_UI && openUi(r))
        return remember("確定", Reply(localSummary(listOf(p.call to r)), leaves))
    }

    /** 不再問 AI，直接用各動作的 summary 組回覆 */
    private fun localSummary(results: List<Pair<PlannedCall, AgentResult>>): String {
        val parts = results.map { (c, r) ->
            when {
                r.ok -> r.summary.ifBlank { "${titleOf(c)}完成" }
                // 派工的錯誤訊息已是規格指定的完整句子
                c.action in BuiltinCatalog.DISPATCH_ACTIONS -> r.errorMessage.orEmpty()
                r.errorCode == AgentApi.AMBIGUOUS -> {
                    val cands = r.errorData?.optJSONArray("candidates")
                    val list = cands?.let { a -> (0 until a.length()).joinToString("、") { i -> a.optJSONObject(i)?.optString("name") ?: a.optString(i) } }
                    "${r.errorMessage}" + (list?.let { "：$it，要哪一個？" } ?: "")
                }
                r.errorCode == AgentApi.NEEDS_UI -> r.errorMessage?.ifBlank { null } ?: "已打開畫面，請在畫面上完成"
                else -> "沒辦法${titleOf(c)}：${r.errorMessage}"
            }
        }
        return parts.joinToString("；").ifBlank { "完成" }
    }

    private fun openUi(r: AgentResult): Boolean {
        val uri = r.errorData?.optString("intentUri")?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            context.startActivity(Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private fun titleOf(call: PlannedCall): String =
        registry.resolve(call.target, call.action)?.action(call.action)?.title ?: call.action

    private fun isQuestion(text: String) = text.trimEnd().let { it.endsWith("？") || it.endsWith("?") || it.endsWith("嗎") }

    /** 記住最近幾輪對話（「再加五分鐘」這種接續指令需要上下文） */
    private fun remember(utterance: String, reply: Reply): Reply {
        history += ChatMessage("user", utterance)
        history += ChatMessage("assistant", JSONObject().put("say", reply.text).put("calls", JSONArray()).toString())
        while (history.size > MAX_HISTORY * 2) history.removeFirst()
        return reply
    }

    /** 主力 → 備援；全部失敗丟 AiException */
    private suspend fun complete(messages: List<ChatMessage>): String {
        val errors = mutableListOf<String>()
        settings.llmKey?.takeIf { it.isNotBlank() }?.let { key ->
            try {
                return LlmClient.chat(settings.llmBaseUrl, settings.llmModel, key, messages)
            } catch (e: AiException) {
                errors += "主力 ${e.message}"
            }
        }
        if (settings.fallbackEnabled) {
            settings.groqKey?.takeIf { it.isNotBlank() }?.let { key ->
                try {
                    return LlmClient.chat(settings.groqBaseUrl, settings.fallbackModel, key, messages)
                } catch (e: AiException) {
                    errors += "備援 ${e.message}"
                }
            }
        }
        throw AiException(errors.joinToString("；").ifBlank { "尚未設定 AI 的 API key" })
    }

    private companion object {
        const val MAX_HISTORY = 6
    }
}
