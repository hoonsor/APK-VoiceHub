package tw.hoonsor.voicehub.agent

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.agent.AgentRequest
import tw.hoonsor.agent.HoonsorAgent
import tw.hoonsor.agent.HoonsorAgentProvider
import tw.hoonsor.voicehub.Graph

/**
 * 本 APP 自己的 Agent API：把內建系統動作開放給其他 APP（例如未來的電腦端大腦）。
 * 用共用 SDK（tw.hoonsor.agent）實作，順便當作 SDK 的實際使用範例。
 */
class AgentProvider : HoonsorAgentProvider() {

    override fun describe(): JSONObject = Graph.registry.builtinCatalog.toDescribeJson()

    override fun health(): JSONObject {
        val issues = JSONArray()
        val s = Graph.settings
        if (!s.hasLlm && !s.hasGroq) issues.put("尚未設定 AI 的 API key")
        Graph.dispatch.queue.size.takeIf { it > 0 }?.let { issues.put("有 $it 筆派工暫存未送出") }
        return JSONObject().put("ok", issues.length() == 0).put("issues", issues)
    }

    /** 派工要連網，給 30 秒；其他動作照預設 5 秒 */
    override fun timeoutMs(action: String): Long =
        if (action in BuiltinCatalog.DISPATCH_ACTIONS) 30_000L else HoonsorAgent.DEFAULT_TIMEOUT_MS

    override fun execute(request: AgentRequest): JSONObject {
        val reg = Graph.registry
        // registry 會把需要主執行緒的動作切過去；這裡在 SDK 的背景執行緒等結果
        val result = runBlocking {
            withTimeoutOrNull(timeoutMs(request.action)) {
                reg.execute(reg.selfPackage, request.action, request.params, request.dryRun, request.confirmToken, request.utterance)
            }
        } ?: AgentResult.fail(AgentApi.INTERNAL, "逾時")
        return result.toJson(request.requestId)
    }
}
