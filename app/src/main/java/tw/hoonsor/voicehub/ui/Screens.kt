package tw.hoonsor.voicehub.ui

import android.Manifest
import android.app.StatusBarManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon as AndroidIcon
import android.net.Uri
import android.os.Build
import android.provider.Settings as SysSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.json.JSONObject
import tw.hoonsor.voicehub.Graph
import tw.hoonsor.voicehub.R
import tw.hoonsor.voicehub.agent.AgentCatalog
import tw.hoonsor.voicehub.agent.Risk
import tw.hoonsor.voicehub.ai.AiException
import tw.hoonsor.voicehub.ai.ChatMessage
import tw.hoonsor.voicehub.ai.LlmClient
import tw.hoonsor.voicehub.ai.ProviderPolicy
import androidx.compose.material3.Icon
import tw.hoonsor.voicehub.assistant.AssistTileService
import tw.hoonsor.voicehub.data.Settings
import tw.hoonsor.voicehub.data.SttEngine
import tw.hoonsor.voicehub.ui.theme.C

private val pagePadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)

// ===================== 動作目錄 =====================

@Composable
fun CatalogScreen() {
    val catalogs by Graph.registry.catalogs.collectAsStateWithLifecycle()
    val remotes by Graph.registry.remotes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    val refresh: () -> Unit = {
        loading = true
        scope.launch { runCatching { Graph.registry.refresh() }; loading = false }
    }
    LaunchedEffect(Unit) { refresh() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "可用語音控制的 APP：${catalogs.size} 個，動作 ${catalogs.sumOf { it.actions.size }} 個",
                    color = C.textSoft, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                OutlineButton(if (loading) "掃描中" else "重新掃描", icon = Glyph.Refresh, enabled = !loading, onClick = refresh)
            }
        }
        val broken = remotes.filter { it.catalog == null }
        if (broken.isNotEmpty()) {
            item {
                Card {
                    Text("連不上的 APP", color = C.warn, style = MaterialTheme.typography.titleMedium)
                    broken.forEach {
                        Spacer(Modifier.height(6.dp))
                        Text("${it.label}：${it.error}", color = C.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        items(catalogs, key = { it.packageName }) { CatalogCard(it) }
        if (remotes.isEmpty()) {
            item {
                Text(
                    "目前還沒有其他 APP 開放 Hoonsor Agent API。鬧鐘、照片整理箱等 APP 補上 API 並用同一把簽章安裝後，重新掃描就會出現在這裡。",
                    color = C.dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun CatalogCard(c: AgentCatalog) {
    var open by remember { mutableStateOf(!c.builtin) }
    Card {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.app, color = C.text, style = MaterialTheme.typography.titleMedium)
                Text("${c.packageName}　v${c.version}　${c.actions.size} 個動作", color = C.dim, style = MaterialTheme.typography.labelMedium)
            }
            Text(if (open) "收合" else "展開", color = C.accent, style = MaterialTheme.typography.labelLarge)
        }
        if (open) {
            c.actions.forEach { a ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RiskBadge(a.risk)
                    Spacer(Modifier.size(8.dp))
                    Text(a.title, color = C.textSoft, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.size(6.dp))
                    Text(a.name, color = C.dim, style = MaterialTheme.typography.labelMedium)
                }
                if (a.examples.isNotEmpty()) {
                    Text("「" + a.examples.joinToString("」「") + "」", color = C.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun RiskBadge(risk: Risk) {
    val (label, color) = when (risk) {
        Risk.READ -> "查詢" to C.auroraTeal
        Risk.REVERSIBLE -> "可復原" to C.auroraBlue
        Risk.DESTRUCTIVE -> "需確認" to C.danger
    }
    Text(
        label, color = color, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ===================== API 測試台 =====================

@Composable
fun TestBenchScreen() {
    val catalogs by Graph.registry.catalogs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(catalogs.first().packageName) }
    val catalog = catalogs.firstOrNull { it.packageName == target } ?: catalogs.first()
    var action by remember(target) { mutableStateOf(catalog.actions.firstOrNull()?.name.orEmpty()) }
    var params by remember(target, action) { mutableStateOf(skeleton(catalog, action)) }
    var dryRun by remember { mutableStateOf(true) }
    var output by remember(target, action) { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("直接呼叫任一 APP 的 Agent API，不經過 AI。", color = C.muted, style = MaterialTheme.typography.bodyMedium)
        SectionTitle("APP")
        ChipRow(catalogs.map { it.packageName to it.app }, target) { target = it }
        SectionTitle("動作")
        ChipRow(catalog.actions.map { it.name to it.title }, action) { action = it }
        catalog.action(action)?.let { spec ->
            Text(spec.description, color = C.muted, style = MaterialTheme.typography.bodyMedium)
            Text("參數規格：" + tw.hoonsor.voicehub.ai.Planner.compactSchema(spec.params), style = Mono)
        }
        Field("params（JSON）", params, { params = it }, mono = true, minLines = 4)
        ToggleRow("試跑（dryRun）", "只檢查參數，不真的執行", dryRun) { dryRun = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("執行", icon = Glyph.Play, primary = true, enabled = !running) {
                val p = runCatching { JSONObject(params.ifBlank { "{}" }) }.getOrElse {
                    output = "params 不是合法 JSON：${it.message}"
                    return@OutlineButton
                }
                running = true
                scope.launch {
                    val r = Graph.registry.execute(target, action, p, dryRun = dryRun, utterance = "（測試台）")
                    output = r.toJson("bench").toString(2)
                    running = false
                }
            }
            OutlineButton("describe") { output = catalog.toDescribeJson().toString(2) }
        }
        if (output.isNotBlank()) {
            Card { SelectionContainer { Text(output, style = Mono) } }
        }
    }
}

private fun skeleton(c: AgentCatalog, action: String): String {
    val spec = c.action(action) ?: return "{}"
    val props = spec.params.optJSONObject("properties") ?: return "{}"
    val req = spec.params.optJSONArray("required")
    val out = JSONObject()
    for (i in 0 until (req?.length() ?: 0)) {
        val k = req!!.optString(i)
        val p = props.optJSONObject(k) ?: continue
        out.put(
            k,
            when (p.optString("type")) {
                "integer", "number" -> p.optInt("minimum", 0)
                "boolean" -> true
                "array" -> org.json.JSONArray()
                else -> p.optJSONArray("enum")?.optString(0) ?: ""
            },
        )
    }
    return out.toString(2)
}

@Composable
private fun ChipRow(items: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (key, label) ->
            val on = key == selected
            Text(
                label,
                color = if (on) C.onAccent else C.textSoft,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) C.accent else C.surfaceHi)
                    .border(1.dp, if (on) C.accent else C.borderHi, RoundedCornerShape(18.dp))
                    .clickable { onSelect(key) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// ===================== 健康檢查 =====================

@Composable
fun HealthScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val remotes by Graph.registry.remotes.collectAsStateWithLifecycle()
    val s = Graph.settings

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    var ttsMsg by remember { mutableStateOf("") }
    var tileMsg by remember { mutableStateOf("") }

    // tick 變動時重新讀狀態
    val mic = remember(tick) { ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val contacts = remember(tick) { ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED }
    val assistant = remember(tick) { ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT) }
    val hasLlm = remember(tick) { s.hasLlm }
    val hasGroq = remember(tick) { s.hasGroq }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HealthItem("麥克風權限", if (mic) "已允許" else "必須允許才能用語音", mic) {
            if (!mic) OutlineButton("允許", primary = true) { askMic.launch(Manifest.permission.RECORD_AUDIO) }
        }
        HealthItem(
            "預設數位助理（長按電源鍵叫出）",
            if (assistant) "已設定為預設數位助理" else "ColorOS：設定 › 應用程式 › 預設應用程式 › 數位助理應用程式 › 選「語音總控」。再到 設定 › 其他設定 › 電源鍵 › 長按電源鍵 › 選「語音助理」。",
            assistant,
        ) {
            OutlineButton("去設定", primary = !assistant) { openAssistantSettings(ctx) }
        }
        HealthItem(
            "AI 金鑰",
            when {
                hasLlm && hasGroq -> "主力 AI 與 Groq 都已設定"
                hasGroq -> "只有 Groq：語音辨識可用，AI 用 Groq 備援模型"
                hasLlm -> "沒有 Groq key：語音辨識改用 Android 內建"
                else -> "都沒設定：只能用離線固定句型。到「設定」填入 API key"
            },
            if (hasLlm && hasGroq) true else if (hasLlm || hasGroq) null else false,
        ) {}
        var dispatchMsg by remember { mutableStateOf("" to (null as Boolean?)) }
        val queued = remember(tick) { Graph.dispatch.queue.size }
        HealthItem(
            "語音派工（利姆路）",
            dispatchMsg.first.ifBlank { "需要手機的 Tailscale VPN 開著。" } + if (queued > 0) "\n暫存未送出：$queued 筆" else "",
            dispatchMsg.second ?: if (queued > 0) false else null,
        ) {
            OutlineButton("測試連線", icon = Glyph.Bolt) {
                dispatchMsg = "連線中…" to null
                scope.launch {
                    dispatchMsg = Graph.dispatch.client.health().fold(
                        { ok -> if (ok) "派工網站連線正常" to true else "網站有回應但狀態異常" to false },
                        { e -> "連不上（${e.message}）：請確認 Tailscale 有開" to false },
                    )
                }
            }
            if (queued > 0) {
                OutlineButton("立即補送", icon = Glyph.Refresh) {
                    scope.launch {
                        val rep = Graph.dispatch.queue.flush(Graph.dispatch.client)
                        dispatchMsg = (if (rep.remaining > 0) "仍連不上，還有 ${rep.remaining} 筆暫存" else "補送完成：${rep.sent} 筆" + if (rep.rejected.isNotEmpty()) "，被拒收 ${rep.rejected.size} 筆" else "") to (rep.remaining == 0)
                        tick++
                    }
                }
            }
        }
        HealthItem("中文語音朗讀", ttsMsg.ifBlank { "按測試聽聽看；沒聲音請到系統「文字轉語音」安裝中文語音" }, null) {
            OutlineButton("測試朗讀", icon = Glyph.Play) {
                scope.launch {
                    Graph.speaker.awaitReady()
                    ttsMsg = if (Graph.speaker.hasChinese()) {
                        "中文語音可用" + (Graph.speaker.currentVoiceName()?.let { "；目前聲音：$it（在設定 › 朗讀聲音更換）" } ?: "")
                    } else {
                        "找不到中文語音（或尚未初始化，請再按一次）"
                    }
                    Graph.speaker.speak("你好，我是語音總控。", s.speechRate)
                }
            }
        }
        HealthItem("聯絡人權限（選用）", if (contacts) "可以用名字撥號、傳簡訊" else "允許後可以說「打電話給某某」", if (contacts) true else null) {
            if (!contacts) OutlineButton("允許") { askContacts.launch(Manifest.permission.READ_CONTACTS) }
        }
        HealthItem("快速設定磚", tileMsg.ifBlank { "加入下拉選單，一鍵叫出語音總控" }, null) {
            OutlineButton("加入") {
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.getSystemService(StatusBarManager::class.java).requestAddTileService(
                        ComponentName(ctx, AssistTileService::class.java), "語音總控",
                        AndroidIcon.createWithResource(ctx, R.drawable.ic_tile), ctx.mainExecutor,
                    ) { code -> tileMsg = if (code <= 1) "已加入（或原本就有）" else "沒有加入（代碼 $code）" }
                } else {
                    tileMsg = "請下拉通知列 › 編輯 › 把「語音總控」拖進去"
                }
            }
        }
        HealthItem(
            "ColorOS 自啟動與關聯啟動",
            "其他 APP 被呼叫時若沒有回應，請到 手機管家 › 權限隱私 › 自啟動管理，把語音總控與被控制的 APP 都打開「允許自啟動」與「允許關聯啟動」；電池也設為「不最佳化」。",
            null,
        ) {
            OutlineButton("應用程式資訊") {
                ctx.startActivity(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        SectionTitle("已連線的 APP（Agent API）")
        if (remotes.isEmpty()) {
            Text("還沒有找到其他實作 Agent API 的 APP。", color = C.dim, style = MaterialTheme.typography.bodyMedium)
        }
        remotes.forEach { r ->
            var detail by remember(r.packageName) { mutableStateOf("") }
            HealthItem(r.label, r.error ?: detail.ifBlank { "describe 正常，${r.catalog?.actions?.size ?: 0} 個動作" }, r.error == null) {
                OutlineButton("health") {
                    scope.launch {
                        val h = Graph.registry.health(r)
                        detail = h?.toString() ?: "超過 5 秒沒有回應"
                    }
                }
            }
        }
        OutlineButton("重新掃描", icon = Glyph.Refresh) { scope.launch { Graph.registry.refresh() } }
    }
}

private fun openAssistantSettings(ctx: Context) {
    val intents = listOf(
        Intent(SysSettings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(SysSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(SysSettings.ACTION_SETTINGS),
    )
    for (i in intents) {
        if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

@Composable
private fun HealthItem(title: String, desc: String, ok: Boolean?, actions: @Composable () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ok)
            Spacer(Modifier.size(10.dp))
            Text(title, color = C.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text(desc, color = C.muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

// ===================== 設定 =====================

@Composable
fun SettingsScreen() {
    val s = Graph.settings
    val scope = rememberCoroutineScope()
    var llmBase by remember { mutableStateOf(s.llmBaseUrl) }
    var llmModel by remember { mutableStateOf(s.llmModel) }
    var llmKey by remember { mutableStateOf("") }
    var groqBase by remember { mutableStateOf(s.groqBaseUrl) }
    var sttModel by remember { mutableStateOf(s.sttModel) }
    var fallbackModel by remember { mutableStateOf(s.fallbackModel) }
    var groqKey by remember { mutableStateOf("") }
    var hasLlm by remember { mutableStateOf(s.hasLlm) }
    var hasGroq by remember { mutableStateOf(s.hasGroq) }
    var fallback by remember { mutableStateOf(s.fallbackEnabled) }
    var engine by remember { mutableStateOf(s.sttEngine) }
    var tts by remember { mutableStateOf(s.ttsEnabled) }
    var rate by remember { mutableStateOf(s.speechRate) }
    var autoListen by remember { mutableStateOf(s.autoListen) }
    var autoClose by remember { mutableStateOf(s.autoClose) }
    var dispatchBase by remember { mutableStateOf(s.dispatchBaseUrl) }
    var llmMsg by remember { mutableStateOf("" to (null as Boolean?)) }
    var groqMsg by remember { mutableStateOf("" to (null as Boolean?)) }
    var saved by remember { mutableStateOf("") }

    fun save() {
        s.llmBaseUrl = llmBase.ifBlank { Settings.DEFAULT_LLM_BASE }
        s.llmModel = llmModel.ifBlank { Settings.DEFAULT_LLM_MODEL }
        if (llmKey.isNotBlank()) s.llmKey = llmKey
        s.groqBaseUrl = groqBase.ifBlank { Settings.DEFAULT_GROQ_BASE }
        s.sttModel = sttModel.ifBlank { Settings.DEFAULT_STT_MODEL }
        s.fallbackModel = fallbackModel.ifBlank { Settings.DEFAULT_FALLBACK_MODEL }
        if (groqKey.isNotBlank()) s.groqKey = groqKey
        s.fallbackEnabled = fallback
        s.sttEngine = engine
        s.ttsEnabled = tts
        s.speechRate = rate
        s.autoListen = autoListen
        s.autoClose = autoClose
        s.dispatchBaseUrl = dispatchBase.ifBlank { Settings.DEFAULT_DISPATCH_BASE }
        llmKey = ""
        groqKey = ""
        hasLlm = s.hasLlm
        hasGroq = s.hasGroq
        saved = "已儲存"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("主力 AI（理解指令）")
        Card {
            Field("API 網址（OpenAI 相容）", llmBase, { llmBase = it })
            PolicyNotice(llmBase)
            Spacer(Modifier.height(10.dp))
            Field("模型 ID", llmModel, { llmModel = it })
            Spacer(Modifier.height(10.dp))
            Field("API key", llmKey, { llmKey = it }, secret = true, placeholder = if (hasLlm) "已設定（留空不變）" else "尚未設定")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("測試", icon = Glyph.Bolt) {
                    save()
                    llmMsg = "測試中…" to null
                    scope.launch { llmMsg = testChat(s.llmBaseUrl, s.llmModel, s.llmKey) }
                }
                if (hasLlm) OutlineButton("清除 key") { s.llmKey = null; hasLlm = false }
            }
            ResultText(llmMsg.first, llmMsg.second)
        }

        SectionTitle("Groq（語音轉文字＋AI 備援）")
        Card {
            Field("API 網址", groqBase, { groqBase = it })
            PolicyNotice(groqBase)
            Spacer(Modifier.height(10.dp))
            Field("語音轉文字模型", sttModel, { sttModel = it })
            Spacer(Modifier.height(10.dp))
            Field("備援 AI 模型", fallbackModel, { fallbackModel = it })
            Spacer(Modifier.height(10.dp))
            Field("API key", groqKey, { groqKey = it }, secret = true, placeholder = if (hasGroq) "已設定（留空不變）" else "尚未設定")
            Spacer(Modifier.height(6.dp))
            ToggleRow("主力 AI 失敗時改用 Groq", "", fallback) { fallback = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("測試", icon = Glyph.Bolt) {
                    save()
                    groqMsg = "測試中…" to null
                    scope.launch { groqMsg = testGroq(s) }
                }
                if (hasGroq) OutlineButton("清除 key") { s.groqKey = null; hasGroq = false }
            }
            ResultText(groqMsg.first, groqMsg.second)
        }

        SectionTitle("語音派工（利姆路 raphael-dispatch）")
        Card {
            Field("派工網址（經 Tailscale，不需金鑰）", dispatchBase, { dispatchBase = it })
            Spacer(Modifier.height(8.dp))
            Text("說「派工…」「我有個點子…」「叫利姆路…」開頭，原話會直接送出，不經 AI 改寫。", color = C.muted, style = MaterialTheme.typography.bodyMedium)
        }

        SectionTitle("語音")
        Card {
            Text("語音辨識引擎", color = C.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            ChipRow(SttEngine.entries.map { it.name to it.label }, engine.name) { engine = SttEngine.valueOf(it) }
            Spacer(Modifier.height(8.dp))
            ToggleRow("唸出回覆", "關掉後只顯示文字", tts) { tts = it }
            Text("朗讀速度", color = C.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
            ChipRow(listOf("0.9" to "慢", "1.0" to "正常", "1.1" to "稍快", "1.3" to "快"), rate.toString()) { rate = it.toFloat() }
            Spacer(Modifier.height(12.dp))
            VoicePicker(rate)
            Text("導航預設交通方式", color = C.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp))
            Text("說「導航到…」沒講怎麼去時用這個；說「開車去」「走路去」會照你說的。選了立刻生效。", color = C.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
            var nav by remember { mutableStateOf(s.navMode) }
            ChipRow(
                listOf("motorcycle" to "機車", "driving" to "開車", "walking" to "走路", "bicycling" to "腳踏車", "transit" to "大眾運輸"),
                nav,
            ) { nav = it; s.navMode = it }
            Spacer(Modifier.height(8.dp))
            ToggleRow("連續對話", "回覆完自動再聽下一句（問句一定會再聽）", autoListen) { autoListen = it }
            ToggleRow("跳到其他 APP 後自動關閉", "例如開啟 LINE、導航後收起對話視窗", autoClose) { autoClose = it }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlineButton("儲存", icon = Glyph.Check, primary = true) { save() }
            Spacer(Modifier.size(10.dp))
            Text(saved, color = C.ok, style = MaterialTheme.typography.labelLarge)
        }
        Text(
            "API key 以 Android Keystore 加密存在手機上，不會進入系統備份。指令內容會送到上面設定的 AI 服務，請勿用語音處理公務或個資。",
            color = C.dim, style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 網址屬於大陸廠牌雲端或 MiniMax 國際版時，即時顯示資料規範提醒（邊輸入邊更新，不用先儲存） */
@Composable
private fun PolicyNotice(url: String) {
    val n = remember(url) { ProviderPolicy.notice(url) } ?: return
    val color = if (n.danger) C.danger else C.warn
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Icon(Glyph.Cross.takeIf { n.danger } ?: Glyph.Bolt, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Column {
            Text(n.title, color = color, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(2.dp))
            Text(n.body, color = C.textSoft, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * 朗讀聲音：選引擎、選聲音、每個聲音都能試聽。選了立刻生效（不用按儲存）。
 */
@Composable
private fun VoicePicker(rate: Float) {
    val ctx = LocalContext.current
    val s = Graph.settings
    val scope = rememberCoroutineScope()
    val engines = remember { Graph.speaker.engines() }
    var engine by remember { mutableStateOf(s.ttsEngine) }
    var voice by remember { mutableStateOf(s.ttsVoice) }
    var options by remember { mutableStateOf<List<tw.hoonsor.voicehub.voice.VoiceOption>?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    // 從系統設定下載語音回來時重新讀清單
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(engine, refresh) {
        options = null
        options = Graph.speaker.voicesOf(engine)
    }
    fun choose(newEngine: String?, newVoice: String?) {
        engine = newEngine
        voice = newVoice
        s.ttsEngine = newEngine
        s.ttsVoice = newVoice
        Graph.speaker.reload()
    }
    fun preview(name: String?) {
        playing = name ?: "default"
        scope.launch {
            Graph.speaker.preview(engine, name, "你好，我是語音總控。明天下午三點要交人事月報。", rate)
            playing = null
        }
    }

    Text("朗讀聲音", color = C.text, style = MaterialTheme.typography.bodyLarge)
    Text("選了立刻生效；按「試聽」聽聽看再決定。", color = C.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
    if (engines.size > 1) {
        ChipRow(listOf("" to "系統預設引擎") + engines.map { it.name to it.label }, engine.orEmpty()) { picked ->
            choose(picked.ifBlank { null }, null)
        }
        Spacer(Modifier.height(8.dp))
    }
    VoiceRow("預設聲音", "使用這個引擎的台灣中文預設聲音", voice == null, playing == "default", onPick = { choose(engine, null) }, onPreview = { preview(null) })
    when (val list = options) {
        null -> Text("讀取聲音清單中…", color = C.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        else -> {
            list.forEach { o ->
                VoiceRow(o.label, o.detail, voice == o.info.name, playing == o.info.name, onPick = { choose(engine, o.info.name) }, onPreview = { preview(o.info.name) })
            }
            if (list.isEmpty()) {
                Text("這個引擎沒有中文聲音，或還沒下載語音資料。", color = C.warn, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            }
            Text("想要更多聲音：到系統的「文字轉語音」安裝語音資料（Google 語音服務 › 安裝語音資料 › 中文（台灣））。", color = C.dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            OutlineButton("開啟系統文字轉語音設定") {
                runCatching { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { ctx.startActivity(Intent(SysSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }
}

@Composable
private fun VoiceRow(label: String, detail: String, selected: Boolean, playing: Boolean, onPick: () -> Unit, onPreview: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) C.surfaceHi else C.surface)
            .border(1.dp, if (selected) C.accent.copy(alpha = 0.7f) else C.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onPick).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).border(2.dp, if (selected) C.accent else C.dim, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(C.accent)) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(label, color = if (selected) C.accent else C.text, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = C.muted, style = MaterialTheme.typography.labelMedium)
        }
        OutlineButton(if (playing) "播放中" else "試聽", icon = Glyph.Play, enabled = !playing, onClick = onPreview)
    }
    Spacer(Modifier.height(6.dp))
}

private suspend fun testChat(base: String, model: String, key: String?): Pair<String, Boolean> {
    if (key.isNullOrBlank()) return "尚未設定 API key" to false
    return try {
        val models = runCatching { LlmClient.listModels(base, key) }.getOrNull()
        val modelNote = when {
            models == null -> "（此服務不支援列出模型）"
            models.isEmpty() -> ""
            model in models -> "（模型存在）"
            else -> "（警告：模型清單裡沒有 $model，可能已下架）"
        }
        val reply = LlmClient.chat(base, model, key, listOf(ChatMessage("user", "只回覆兩個字：正常")), maxTokens = 200)
        "成功：${reply.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim().take(40)} $modelNote" to true
    } catch (e: AiException) {
        (e.message ?: "失敗") to false
    }
}

private suspend fun testGroq(s: Settings): Pair<String, Boolean> {
    val key = s.groqKey
    if (key.isNullOrBlank()) return "尚未設定 API key" to false
    return try {
        val models = LlmClient.listModels(s.groqBaseUrl, key)
        val problems = listOf(s.sttModel, s.fallbackModel).filter { it !in models }
        if (problems.isEmpty()) "成功：語音與備援模型都可用" to true
        else "key 正常，但找不到模型：${problems.joinToString()}（可能已下架，請改模型 ID）" to false
    } catch (e: AiException) {
        (e.message ?: "失敗") to false
    }
}
