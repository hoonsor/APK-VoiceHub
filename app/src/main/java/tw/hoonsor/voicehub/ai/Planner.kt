package tw.hoonsor.voicehub.ai

import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.voicehub.agent.AgentCatalog
import tw.hoonsor.voicehub.agent.AgentResult
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** AI 規劃出的一個動作呼叫 */
data class PlannedCall(val target: String, val action: String, val params: JSONObject) {
    fun toJson(): JSONObject = JSONObject().put("target", target).put("action", action).put("params", params)
}

/** AI 的一輪回答 */
data class Plan(val say: String, val calls: List<PlannedCall>)

class PlanParseException(message: String) : Exception(message)

/**
 * 組提示詞、解析 AI 回覆。純邏輯，有 JVM 測試（PlannerTest）。
 *
 * 設計：不用各家 API 的 tool calling（MiniMax／Groq 相容程度不一），
 * 改成要求模型只輸出固定格式的 JSON，換模型也不用改程式。
 */
object Planner {

    fun systemPrompt(catalogs: List<AgentCatalog>, now: LocalDateTime, placeNames: Collection<String> = emptyList()): String {
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.TRADITIONAL_CHINESE)
        val time = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        // 倬自己的 APP 排前面，系統動作放最後
        val ordered = catalogs.sortedBy { it.builtin }
        val tools = buildString {
            ordered.forEach { c ->
                append("### ").append(c.app).append("（target: ").append(c.packageName).append("）\n")
                if (c.description.isNotBlank()) append(c.description).append('\n')
                c.actions.filterNot { it.deprecated }.forEach { a ->
                    append("- ").append(a.name).append(" [").append(a.risk.wire).append("] ").append(a.title)
                    if (a.description.isNotBlank()) append("：").append(a.description)
                    append("\n  params: ").append(compactSchema(a.params))
                    if (a.examples.isNotEmpty()) append("\n  例句：").append(a.examples.joinToString("／"))
                    append('\n')
                }
                append('\n')
            }
        }
        return """
你是倬手機上的語音助理「語音總控」，像 Gemini Live 一樣聽指令、操作手機上的 APP，再用口語回報結果。
現在時間：$time（$weekday），時區 Asia/Taipei。

## 可用的動作
$tools
## 回覆格式（非常重要）
只輸出一個 JSON 物件，不要 Markdown、不要任何其他文字：
{"say": "要對倬說的話", "calls": [{"target": "套件名", "action": "動作名", "params": {}}]}

## 規則
1. 能用上面的動作完成就放進 calls（可以多個，依序執行）；params 必須符合 params 的 JSON Schema。target 一定要用上面列出的套件名。
2. 倬自己開發的 APP（非「系統」那一組）有對應動作時優先用它，沒有才用系統動作。
3. 沒有任何動作做得到：calls 給空陣列，say 直接說「這個我目前做不到」並說明原因，可以建議替代做法。不要假裝做到了。
4. 資訊不足、或有多種可能：calls 給空陣列，say 只問一個簡短的問題。
5. 一般聊天或常識問題（例如今天星期幾）：calls 給空陣列，直接在 say 回答。
6. 時間用 24 小時制；「明天、下週三、半小時後」依現在時間換算。
7. say 會被唸出來：台灣繁體中文、口語、一到兩句、不要 Markdown、不要表情符號、不要念出套件名或動作名。
8. 收到「執行結果」訊息時：依結果用口語回報（成功講結果，失敗講原因與建議）；需要接著做別的動作才再給 calls，否則 calls 給空陣列。
9. 結果是 NEEDS_CONFIRMATION 時由 APP 負責詢問，你不用重送。結果是 AMBIGUOUS 時請倬從候選中選一個。
10. 倬說的是「開發需求、點子、要利姆路去做的事」時用 dispatch_send，text 放倬的原話（只去掉觸發詞），不要改寫、不要判斷專案、不要拆成多筆。
11. 鬧鐘與計時器：有「倬的鬧鐘」時一律用它的動作（alarm_create、timer_start…），不要用系統的 alarm_set、timer_set。新增的鬧鐘和計時器預設一次性（響完自動刪除），params 不要帶 auto_delete；只有倬明確說「固定、重複、常駐、保留、響完不要刪」才帶 auto_delete: false。
12. 倬設定的常用地點：${if (placeNames.isEmpty()) "（沒有）" else placeNames.joinToString("、")}。說「回家」「去公司」這類常用地點時，map_navigate 的 destination 直接填名稱（例如「家」），不要自己猜地址；沒有設定的地點就請倬講地址或地標。
""".trim()
    }

    /** 把 JSON Schema 壓成短字串，省 token */
    fun compactSchema(schema: JSONObject): String {
        val props = schema.optJSONObject("properties") ?: return "{}"
        val required = schema.optJSONArray("required")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        if (props.length() == 0) return "{}"
        val parts = props.keys().asSequence().map { key ->
            val p = props.optJSONObject(key) ?: JSONObject()
            val type = p.optString("type", "any")
            val enum = p.optJSONArray("enum")?.let { a -> (0 until a.length()).joinToString("|") { a.optString(it) } }
            val range = if (p.has("minimum") || p.has("maximum")) " ${p.opt("minimum") ?: ""}..${p.opt("maximum") ?: ""}" else ""
            val desc = p.optString("description")
            buildString {
                append(key)
                if (key !in required) append('?')
                append(": ").append(enum ?: type).append(range)
                if (desc.isNotBlank()) append(" (").append(desc).append(')')
            }
        }
        return parts.joinToString("; ", "{", "}")
    }

    /** 執行結果回傳給 AI 的訊息 */
    fun resultsMessage(results: List<Pair<PlannedCall, AgentResult>>): String {
        val arr = JSONArray()
        results.forEach { (call, r) ->
            arr.put(JSONObject().put("target", call.target).put("action", call.action).put("result", r.brief()))
        }
        return "執行結果：$arr\n請依規則 8 回覆 JSON。"
    }

    private val think = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    private val fence = Regex("^```(?:json)?\\s*|\\s*```$", RegexOption.MULTILINE)

    /** 解析 AI 回覆；容忍 <think>、```json 圍欄與前後雜訊 */
    fun parsePlan(text: String): Plan {
        val cleaned = think.replace(text, "").trim().let { fence.replace(it, "").trim() }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            // 模型沒照格式、只回了一句話：當成單純回覆
            if (cleaned.isNotBlank() && cleaned.length < 300) return Plan(cleaned, emptyList())
            throw PlanParseException("AI 回覆不是 JSON")
        }
        val j = try {
            JSONObject(cleaned.substring(start, end + 1))
        } catch (e: Exception) {
            throw PlanParseException("AI 回覆的 JSON 無法解析")
        }
        val calls = mutableListOf<PlannedCall>()
        j.optJSONArray("calls")?.let { a ->
            for (i in 0 until a.length()) {
                val c = a.optJSONObject(i) ?: continue
                val action = c.optString("action").trim()
                if (action.isEmpty()) continue
                calls += PlannedCall(
                    target = c.optString("target").trim(),
                    action = action,
                    params = c.optJSONObject("params") ?: JSONObject(),
                )
            }
        }
        return Plan(j.optString("say").trim(), calls)
    }
}
