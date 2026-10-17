package tw.hoonsor.voicehub.agent

import org.json.JSONArray
import org.json.JSONObject

/** 動作風險等級（Hoonsor Agent API v1） */
enum class Risk(val wire: String) {
    READ("read"),
    REVERSIBLE("reversible"),
    DESTRUCTIVE("destructive");

    companion object {
        /** 不認得的值一律當成最危險，寧可多問一次 */
        fun of(s: String?): Risk = entries.firstOrNull { it.wire == s } ?: DESTRUCTIVE
    }
}

/** 一個可被語音呼叫的動作 */
data class ActionSpec(
    val name: String,
    val title: String,
    val description: String,
    val risk: Risk,
    val params: JSONObject,
    val examples: List<String>,
    /** 已停用：仍可執行（相容舊呼叫），但不再給 AI 看 */
    val deprecated: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("title", title)
        .put("description", description)
        .put("risk", risk.wire)
        .put("params", params)
        .put("examples", JSONArray(examples))
        .apply { if (deprecated) put("deprecated", true) }

    companion object {
        fun fromJson(j: JSONObject) = ActionSpec(
            name = j.optString("name"),
            title = j.optString("title", j.optString("name")),
            description = j.optString("description"),
            risk = Risk.of(j.optString("risk")),
            params = j.optJSONObject("params") ?: JSONObject().put("type", "object"),
            examples = j.optJSONArray("examples")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            deprecated = j.optBoolean("deprecated", false),
        )
    }
}

/** 一個 APP 的 describe 結果 */
data class AgentCatalog(
    val app: String,
    val packageName: String,
    val version: String,
    val description: String,
    val actions: List<ActionSpec>,
    /** 這個目錄是大腦內建的系統動作 */
    val builtin: Boolean = false,
) {
    fun action(name: String) = actions.firstOrNull { it.name == name }

    fun toDescribeJson(): JSONObject = JSONObject()
        .put("api", AgentApi.VERSION)
        .put("app", app)
        .put("package", packageName)
        .put("version", version)
        .put("description", description)
        .put("actions", JSONArray().apply { actions.forEach { put(it.toJson()) } })

    companion object {
        fun fromDescribe(j: JSONObject, fallbackPackage: String): AgentCatalog = AgentCatalog(
            app = j.optString("app", fallbackPackage),
            packageName = j.optString("package", fallbackPackage).ifBlank { fallbackPackage },
            version = j.optString("version"),
            description = j.optString("description"),
            actions = j.optJSONArray("actions")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(ActionSpec::fromJson) }
            } ?: emptyList(),
        )
    }
}

/** execute 的回傳 */
data class AgentResult(
    val ok: Boolean,
    val summary: String = "",
    val data: JSONObject? = null,
    val undo: JSONObject? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    /** 錯誤附帶的資料（AMBIGUOUS 的 candidates、NEEDS_CONFIRMATION 的 confirmToken…） */
    val errorData: JSONObject? = null,
) {
    fun toJson(requestId: String): JSONObject = if (ok) {
        JSONObject().put("ok", true).put("requestId", requestId).put("summary", summary)
            .put("data", data ?: JSONObject()).put("undo", undo ?: JSONObject.NULL)
    } else {
        JSONObject().put("ok", false).put("requestId", requestId).put(
            "error",
            JSONObject().put("code", errorCode ?: AgentApi.INTERNAL).put("message", errorMessage ?: "")
                .apply { if (errorData != null) put("data", errorData) },
        )
    }

    /** 給 AI 看的精簡版結果 */
    fun brief(): JSONObject = if (ok) {
        JSONObject().put("ok", true).put("summary", summary).apply { if (data != null && data.length() > 0) put("data", data) }
    } else {
        JSONObject().put("ok", false).put("code", errorCode).put("message", errorMessage)
            .apply { if (errorData != null) put("data", errorData) }
    }

    companion object {
        fun ok(summary: String, data: JSONObject? = null, undo: JSONObject? = null) =
            AgentResult(true, summary, data, undo)

        fun fail(code: String, message: String, data: JSONObject? = null) =
            AgentResult(false, errorCode = code, errorMessage = message, errorData = data)

        fun fromJson(j: JSONObject): AgentResult {
            if (j.optBoolean("ok", false)) {
                return AgentResult(
                    ok = true,
                    summary = j.optString("summary"),
                    data = j.optJSONObject("data"),
                    undo = j.optJSONObject("undo"),
                )
            }
            val e = j.optJSONObject("error")
            return AgentResult(
                ok = false,
                errorCode = e?.optString("code")?.ifBlank { null } ?: AgentApi.INTERNAL,
                errorMessage = e?.optString("message") ?: "回傳格式不正確",
                errorData = e?.optJSONObject("data") ?: j.optJSONObject("data"),
            )
        }
    }
}

object AgentApi {
    const val VERSION = 1
    const val PROVIDER_ACTION = "tw.hoonsor.agent.PROVIDER"
    const val PERMISSION = "tw.hoonsor.permission.AGENT"
    const val METHOD_DESCRIBE = "describe"
    const val METHOD_EXECUTE = "execute"
    const val METHOD_HEALTH = "health"
    const val EXTRA_JSON = "json"
    const val TIMEOUT_MS = 5_000L

    const val INVALID_PARAMS = "INVALID_PARAMS"
    const val NOT_FOUND = "NOT_FOUND"
    const val AMBIGUOUS = "AMBIGUOUS"
    const val NEEDS_CONFIRMATION = "NEEDS_CONFIRMATION"
    const val NEEDS_UI = "NEEDS_UI"
    const val PERMISSION_DENIED = "PERMISSION"
    const val INTERNAL = "INTERNAL"
    /** 大腦端自用：目標 APP 沒回應或沒安裝 */
    const val UNAVAILABLE = "UNAVAILABLE"

    fun request(
        requestId: String,
        action: String,
        params: JSONObject,
        dryRun: Boolean = false,
        confirmToken: String? = null,
        utterance: String? = null,
    ): JSONObject = JSONObject()
        .put("api", VERSION)
        .put("requestId", requestId)
        .put("action", action)
        .put("params", params)
        .put("dryRun", dryRun)
        .put("confirmToken", confirmToken ?: JSONObject.NULL)
        .put("utterance", utterance ?: JSONObject.NULL)
}

/** 小工具：組 JSON Schema */
object Schema {
    fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
        JSONObject().put("type", "object")
            .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            .put("required", JSONArray(required))

    fun str(desc: String, enum: List<String>? = null): JSONObject = JSONObject().put("type", "string").put("description", desc)
        .apply { if (enum != null) put("enum", JSONArray(enum)) }

    fun int(desc: String, min: Int? = null, max: Int? = null): JSONObject = JSONObject().put("type", "integer").put("description", desc)
        .apply {
            if (min != null) put("minimum", min)
            if (max != null) put("maximum", max)
        }

    fun bool(desc: String): JSONObject = JSONObject().put("type", "boolean").put("description", desc)

    fun intArray(desc: String): JSONObject = JSONObject().put("type", "array").put("description", desc)
        .put("items", JSONObject().put("type", "integer"))
}
