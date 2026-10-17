package tw.hoonsor.voicehub.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import tw.hoonsor.voicehub.BuildConfig
import tw.hoonsor.voicehub.Graph
import tw.hoonsor.voicehub.dispatch.DispatchClient
import tw.hoonsor.voicehub.dispatch.DispatchOutcome
import tw.hoonsor.voicehub.dispatch.DispatchTrigger
import java.util.UUID

/** 一個實作 Agent API 的 APP 的連線狀態 */
data class RemoteAgent(
    val packageName: String,
    val authority: String,
    val label: String,
    val catalog: AgentCatalog? = null,
    val error: String? = null,
)

/**
 * 所有可用動作的總表：大腦內建的系統動作＋其他 APP 透過 Agent API 提供的動作。
 * 其他 APP 用 PackageManager 找 intent-filter `tw.hoonsor.agent.PROVIDER` 的 ContentProvider。
 */
class ToolRegistry(private val context: Context) {
    val builtin = BuiltinActions(context)
    val selfPackage: String = context.packageName
    val builtinCatalog = BuiltinCatalog.catalog(selfPackage, BuildConfig.VERSION_NAME)

    private val _remotes = MutableStateFlow<List<RemoteAgent>>(emptyList())
    val remotes: StateFlow<List<RemoteAgent>> = _remotes
    private val _catalogs = MutableStateFlow(listOf(builtinCatalog))
    val catalogs: StateFlow<List<AgentCatalog>> = _catalogs

    /** 找出其他實作 Agent API 的 APP（含同一支程式的正式版／LAB 版） */
    fun discover(): List<RemoteAgent> {
        val pm = context.packageManager
        return pm.queryIntentContentProviders(Intent(AgentApi.PROVIDER_ACTION), 0)
            .mapNotNull { ri ->
                val pi = ri.providerInfo ?: return@mapNotNull null
                if (pi.packageName == selfPackage) return@mapNotNull null
                val authority = pi.authority?.split(';')?.firstOrNull() ?: return@mapNotNull null
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pi.packageName, 0)).toString() }
                    .getOrDefault(pi.packageName)
                RemoteAgent(pi.packageName, authority, label)
            }
            .distinctBy { it.packageName }
    }

    /** 重新向每個 APP 要 describe（平行、各自 5 秒逾時） */
    suspend fun refresh() = coroutineScope {
        val found = discover()
        val updated = found.map { ra ->
            async {
                runCatching {
                    val json = call(ra.authority, AgentApi.METHOD_DESCRIBE, null)
                        ?: return@runCatching ra.copy(error = "沒有回應（可能被 ColorOS 關聯啟動擋住）")
                    ra.copy(catalog = AgentCatalog.fromDescribe(json, ra.packageName).let { c -> if (c.app.isBlank()) c.copy(app = ra.label) else c })
                }.getOrElse { e -> ra.copy(error = errorText(e)) }
            }
        }.awaitAll()
        _remotes.value = updated
        _catalogs.value = updated.mapNotNull { it.catalog } + builtinCatalog
    }

    private fun errorText(e: Throwable): String = when (e) {
        is SecurityException -> "權限被拒（兩邊簽章不同？）"
        else -> "${e.javaClass.simpleName}：${e.message}"
    }

    /** 呼叫 ContentProvider.call()，結果在 Bundle 的 json；逾時回 null */
    suspend fun call(authority: String, method: String, json: JSONObject?): JSONObject? = withContext(Dispatchers.IO) {
        val extras = Bundle().apply { if (json != null) putString(AgentApi.EXTRA_JSON, json.toString()) }
        // ContentResolver.call 會卡住執行緒，用 async＋逾時避免整個對話被一個 APP 拖住
        val deferred = async(Dispatchers.IO) {
            context.contentResolver.call(Uri.parse("content://$authority"), method, null, extras)
        }
        val bundle = withTimeoutOrNull(AgentApi.TIMEOUT_MS) { deferred.await() }
        if (bundle == null) {
            deferred.cancel()
            return@withContext null
        }
        bundle.getString(AgentApi.EXTRA_JSON)?.let { JSONObject(it) }
    }

    fun catalogFor(target: String): AgentCatalog? = _catalogs.value.firstOrNull { it.packageName == target }

    /** 找出這個呼叫實際要送去哪個 APP；target 寫錯但動作名只有一個 APP 有時自動修正 */
    fun resolve(target: String, action: String): AgentCatalog? {
        catalogFor(target)?.takeIf { it.action(action) != null }?.let { return it }
        val owners = _catalogs.value.filter { it.action(action) != null }
        return owners.singleOrNull() ?: owners.firstOrNull { !it.builtin }
    }

    fun riskOf(target: String, action: String): Risk? = resolve(target, action)?.action(action)?.risk

    suspend fun execute(
        target: String,
        action: String,
        params: JSONObject,
        dryRun: Boolean = false,
        confirmToken: String? = null,
        utterance: String? = null,
    ): AgentResult {
        val catalog = resolve(target, action)
            ?: return AgentResult.fail(AgentApi.NOT_FOUND, "沒有任何 APP 提供「$action」這個動作")
        if (catalog.builtin) {
            if (action in BuiltinCatalog.DISPATCH_ACTIONS) return dispatchAction(action, params, dryRun)
            return withContext(Dispatchers.Main) { builtin.execute(action, params, dryRun) }
        }
        val remote = _remotes.value.firstOrNull { it.packageName == catalog.packageName }
            ?: return AgentResult.fail(AgentApi.UNAVAILABLE, "${catalog.app} 已不在手機上")
        val req = AgentApi.request(UUID.randomUUID().toString(), action, params, dryRun, confirmToken, utterance)
        return try {
            val json = call(remote.authority, AgentApi.METHOD_EXECUTE, req)
                ?: return AgentResult.fail(AgentApi.UNAVAILABLE, "${catalog.app} 超過 5 秒沒有回應")
            AgentResult.fromJson(json)
        } catch (e: Exception) {
            Log.w("ToolRegistry", "execute $action failed", e)
            AgentResult.fail(AgentApi.UNAVAILABLE, "${catalog.app}：${errorText(e)}")
        }
    }

    /** 派工動作要連網，不能在主執行緒做，所以不放在 [BuiltinActions] */
    private suspend fun dispatchAction(action: String, params: JSONObject, dryRun: Boolean): AgentResult {
        val service = Graph.dispatch
        return when (action) {
            "dispatch_send" -> {
                val text = params.optString("text").trim()
                if (text.isEmpty()) return AgentResult.fail(AgentApi.INVALID_PARAMS, "要派什麼工？內容是空的")
                if (text.length > DispatchTrigger.MAX_LENGTH) return AgentResult.fail(AgentApi.INVALID_PARAMS, "內容超過 ${DispatchTrigger.MAX_LENGTH} 字")
                if (dryRun) return AgentResult.ok("（試跑）會送出派工：$text")
                val (outcome, flushed) = service.submit(text)
                val extra = service.flushText(flushed)?.let { " $it" }.orEmpty()
                when (outcome) {
                    is DispatchOutcome.Sent -> AgentResult.ok(
                        outcome.speech() + extra,
                        JSONObject().put("ticketId", outcome.ticketId).put("guess", outcome.guess ?: JSONObject.NULL),
                    )
                    is DispatchOutcome.Offline -> AgentResult.ok(
                        outcome.speech(),
                        JSONObject().put("queued", true).put("pending", service.queue.size).put("reason", outcome.reason),
                    )
                    is DispatchOutcome.Rejected -> AgentResult.fail(AgentApi.INVALID_PARAMS, outcome.speech())
                }
            }
            "dispatch_status" -> {
                val id = params.optInt("id", -1)
                if (id <= 0) return AgentResult.fail(AgentApi.INVALID_PARAMS, "要查第幾號派工單？")
                if (dryRun) return AgentResult.ok("（試跑）會查詢第 $id 號派工單")
                service.client.ticket(id).fold(
                    onSuccess = { t ->
                        val status = DispatchClient.statusText(t.optString("status"))
                        val project = t.optString("project").takeIf { it.isNotBlank() && it != "null" }
                        val title = t.optString("title")
                        AgentResult.ok(
                            "第 $id 號派工單" + (project?.let { "（$it）" } ?: "") + "目前$status" + (if (title.isNotBlank()) "：$title" else ""),
                            JSONObject().put("ticket", t),
                        )
                    },
                    onFailure = { e ->
                        val msg = e.message.orEmpty()
                        if (msg.startsWith("找不到")) AgentResult.fail(AgentApi.NOT_FOUND, msg)
                        else AgentResult.fail(AgentApi.UNAVAILABLE, "派工網站連不上，請確認 Tailscale 有開。")
                    },
                )
            }
            else -> AgentResult.fail(AgentApi.NOT_FOUND, "沒有這個動作：$action")
        }
    }

    suspend fun health(remote: RemoteAgent): JSONObject? = runCatching { call(remote.authority, AgentApi.METHOD_HEALTH, null) }
        .getOrElse { JSONObject().put("ok", false).put("issues", org.json.JSONArray().put(errorText(it))) }
}
