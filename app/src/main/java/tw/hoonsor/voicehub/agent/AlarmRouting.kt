package tw.hoonsor.voicehub.agent

import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.voicehub.ai.PlannedCall

/**
 * 鬧鐘與計時器的路由與「一次性」規則。純邏輯，有 JVM 測試（AlarmRoutingTest）。
 *
 * - 倬的鬧鐘有 Agent API 時，系統的 alarm_set／timer_set 一律改送它的 alarm_create／timer_start
 *   （AI 可能沒照提示詞規則選，離線句型也只會產生系統動作）
 * - 預設一次性（響完自動刪除）：不帶 auto_delete，交給鬧鐘 APP 的預設值；
 *   只有倬明確說「固定、重複、常駐、保留、響完不要刪」才帶 auto_delete=false
 * - 退回系統時鐘時做不到一次性，回覆補一句提醒
 */
object AlarmRouting {
    const val ALARM_PACKAGE = "com.hoonsor.alarm"
    const val AUTO_DELETE = "auto_delete"

    /** 系統動作 → 倬的鬧鐘的對應動作 */
    private val routes = mapOf("alarm_set" to "alarm_create", "timer_set" to "timer_start")
    /** 會新建鬧鐘或計時器、要套用一次性規則的動作 */
    private val createActions = setOf("alarm_create", "timer_start")

    private val keepWords = Regex("固定|重複|常駐|保留|不要刪|別刪|不用刪|不刪|留著")
    private val negatedKeep = Regex("(不要|不用|別|不)(固定|重複|常駐|保留)")

    /** 倬是不是明確要「響完留下來」的鬧鐘或計時器 */
    fun wantsKeep(utterance: String): Boolean =
        keepWords.containsMatchIn(negatedKeep.replace(OfflineParser.normalize(utterance), ""))

    /** 修正一個呼叫：系統鬧鐘／計時改送倬的鬧鐘，並套用一次性規則；不相關的呼叫原樣回傳 */
    fun adjust(call: PlannedCall, catalogs: List<AgentCatalog>, utterance: String): PlannedCall {
        val routed = routeToOwnApp(call, catalogs)
        if (routed.action !in createActions) return routed
        val spec = catalogs.firstOrNull { !it.builtin && it.packageName == routed.target }?.action(routed.action) ?: return routed
        // 舊版鬧鐘 APP 沒有 auto_delete 參數時不要亂帶
        if (spec.params.optJSONObject("properties")?.has(AUTO_DELETE) != true) return routed
        val params = JSONObject(routed.params.toString())
        if (wantsKeep(utterance)) params.put(AUTO_DELETE, false) else params.remove(AUTO_DELETE)
        return routed.copy(params = params)
    }

    private fun routeToOwnApp(call: PlannedCall, catalogs: List<AgentCatalog>): PlannedCall {
        val own = routes[call.action] ?: return call
        val target = catalogs.firstOrNull { it.packageName == call.target && it.action(call.action) != null }
        if (target != null && !target.builtin) return call
        val app = catalogs.filter { !it.builtin && it.action(own) != null }
            .sortedByDescending { it.packageName == ALARM_PACKAGE }
            .firstOrNull() ?: return call
        val p = call.params
        val params = JSONObject()
        when (own) {
            "alarm_create" -> {
                p.opt("hour")?.let { params.put("hour", it) }
                p.opt("minute")?.let { params.put("minute", it) }
                p.optString("label").takeIf { it.isNotBlank() }?.let { params.put("memo", it) }
                p.optJSONArray("days")?.takeIf { it.length() > 0 }?.let { params.put("days", JSONArray(it.toString())) }
            }
            "timer_start" -> {
                p.opt("seconds")?.let { params.put("seconds", it) }
                p.optString("label").takeIf { it.isNotBlank() }?.let { params.put("label", it) }
            }
        }
        return PlannedCall(app.packageName, own, params)
    }

    /** 用系統時鐘成功設定後要補的提醒；倬本來就要留著、或不是鬧鐘／計時時回 null */
    fun fallbackNote(action: String, builtin: Boolean, utterance: String): String? {
        if (!builtin || action !in routes || wantsKeep(utterance)) return null
        return if (action == "alarm_set") "提醒你，系統時鐘的鬧鐘響完不會自動刪除"
        else "提醒你，系統時鐘的計時器沒辦法設成響完自動刪除"
    }
}
