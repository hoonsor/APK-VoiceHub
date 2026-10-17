package tw.hoonsor.voicehub.agent

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.voicehub.Graph

/**
 * 本 APP 自己的 Agent API：把內建系統動作開放給其他 APP（例如未來的電腦端大腦）。
 * 只解析與分派；實際動作一律走 [BuiltinActions]。
 */
class AgentProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val out = when (method) {
            AgentApi.METHOD_DESCRIBE -> Graph.registry.builtinCatalog.toDescribeJson()
            AgentApi.METHOD_HEALTH -> health()
            AgentApi.METHOD_EXECUTE -> execute(extras?.getString(AgentApi.EXTRA_JSON))
            else -> AgentResult.fail(AgentApi.NOT_FOUND, "不支援的方法：$method").toJson("")
        }
        return Bundle().apply { putString(AgentApi.EXTRA_JSON, out.toString()) }
    }

    private fun health(): JSONObject {
        val issues = JSONArray()
        val s = Graph.settings
        if (!s.hasLlm && !s.hasGroq) issues.put("尚未設定 AI 的 API key")
        Graph.dispatch.queue.size.takeIf { it > 0 }?.let { issues.put("有 $it 筆派工暫存未送出") }
        return JSONObject().put("ok", issues.length() == 0).put("issues", issues)
    }

    private fun execute(raw: String?): JSONObject {
        val req = runCatching { JSONObject(raw ?: "") }.getOrNull()
            ?: return AgentResult.fail(AgentApi.INVALID_PARAMS, "缺少 json").toJson("")
        val id = req.optString("requestId")
        val action = req.optString("action")
        val params = req.optJSONObject("params") ?: JSONObject()
        val dryRun = req.optBoolean("dryRun", false)
        // 在 Binder 執行緒等結果：一般動作 registry 會切到主執行緒做；派工要連網，最多等 30 秒
        val reg = Graph.registry
        val limit = if (action in BuiltinCatalog.DISPATCH_ACTIONS) 30_000L else AgentApi.TIMEOUT_MS
        val result = runBlocking {
            withTimeoutOrNull(limit) {
                runCatching { reg.execute(reg.selfPackage, action, params, dryRun, req.optString("confirmToken").ifBlank { null }, req.optString("utterance").ifBlank { null }) }
                    .getOrElse { AgentResult.fail(AgentApi.INTERNAL, it.message ?: "錯誤") }
            }
        } ?: AgentResult.fail(AgentApi.INTERNAL, "逾時")
        return result.toJson(id)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
