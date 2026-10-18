package tw.hoonsor.voicehub.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tw.hoonsor.voicehub.Graph
import tw.hoonsor.voicehub.assistant.AssistantActivity
import tw.hoonsor.voicehub.manual.Manual
import tw.hoonsor.voicehub.ui.theme.C

// ===================== 使用說明書 =====================

/** 說明書檔案在 assets 的路徑（來源是 repo 的 docs/manual/MANUAL.md） */
private const val MANUAL_ASSET = "MANUAL.md"

private fun loadManual(ctx: Context): Manual.Document =
    runCatching { ctx.assets.open(MANUAL_ASSET).bufferedReader(Charsets.UTF_8).use { Manual.parse(it.readText()) } }
        .getOrElse { Manual.Document(emptyList()) }

private fun isInstalled(ctx: Context, pkg: String): Boolean =
    runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

/** 點例句：打開對話畫面並把這句話放進輸入框（不會自動送出，按送出才執行） */
private fun tryPhrase(ctx: Context, phrase: String) {
    ctx.startActivity(
        Intent(ctx, AssistantActivity::class.java)
            .putExtra(AssistantActivity.EXTRA_DRAFT, phrase)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
fun ManualScreen() {
    val ctx = LocalContext.current
    val doc = remember { loadManual(ctx) }
    val catalogs by Graph.registry.catalogs.collectAsStateWithLifecycle()
    val resolver = remember(catalogs) { Manual.Resolver(catalogs) { isInstalled(ctx, it) } }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(doc, query) { Manual.filter(doc, query) }
    val uncovered = remember(doc, resolver) { resolver.uncovered(doc) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val searching = query.isNotBlank()

    // 進來時重新問一次各 APP 有哪些動作，狀態標籤才會是最新的
    LaunchedEffect(Unit) { runCatching { Graph.registry.refresh() } }

    if (doc.chapters.isEmpty()) {
        Text("說明書載入失敗，請重新安裝最新版。", color = C.danger, modifier = Modifier.padding(20.dp))
        return
    }

    // 每一章在清單中的位置（目錄跳轉用）；固定項目：搜尋框、目錄
    val chapterIndex = remember(shown) {
        var i = 2
        shown.chapters.map { ch -> i.also { i += 1 + ch.sections.size + if (ch.intro.isNotEmpty()) 1 else 0 } }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "search") {
            Field("搜尋說明書", query, { query = it }, placeholder = "例如：鬧鐘、導航、相簿、派工、聽不懂")
        }
        item(key = "toc") {
            if (searching) {
                val n = shown.chapters.sumOf { it.sections.size }
                Text(if (n == 0) "找不到「$query」，換個說法試試看。" else "找到 $n 節", color = C.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.chapters.forEachIndexed { i, ch ->
                        Pill(ch.title.substringBefore('（')) { scope.launch { listState.animateScrollToItem(chapterIndex[i]) } }
                    }
                    if (uncovered.isNotEmpty()) Pill("未收錄", color = C.warn) { scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) } }
                }
            }
        }
        shown.chapters.forEach { ch ->
            item(key = "ch:${ch.title}") { ChapterHeader(ch, resolver) }
            if (ch.intro.isNotEmpty()) item(key = "intro:${ch.title}") { Card { Blocks(ch.intro) { tryPhrase(ctx, it) } } }
            ch.sections.forEach { s ->
                val key = "${ch.title}/${s.title}"
                item(key = "sec:$key") {
                    SectionCard(s, resolver, open = searching || expanded[key] == true, onToggle = { expanded[key] = expanded[key] != true }) { tryPhrase(ctx, it) }
                }
            }
        }
        if (!searching && uncovered.isNotEmpty()) {
            item(key = "uncovered") { UncoveredCard(uncovered) { tryPhrase(ctx, it) } }
        }
    }
}

@Composable
private fun ChapterHeader(ch: Manual.Chapter, resolver: Manual.Resolver) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp, bottom = 2.dp)) {
        Text(ch.title, color = C.text, style = MaterialTheme.typography.titleLarge)
        ch.app?.let { pkg ->
            Spacer(Modifier.height(6.dp))
            val status = resolver.appStatus(pkg)
            val c = resolver.catalogFor(pkg)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(status)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (status) {
                        Manual.Status.AVAILABLE -> "已連線　v${c?.version}　${c?.actions?.count { !it.deprecated }} 個動作"
                        Manual.Status.INSTALLED_NO_API -> "已安裝，但這一版還沒開放語音控制"
                        Manual.Status.NOT_INSTALLED -> "手機上沒有安裝"
                        Manual.Status.MISSING_IN_VERSION -> ""
                    },
                    color = C.muted, style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(s: Manual.Section, resolver: Manual.Resolver, open: Boolean, onToggle: () -> Unit, onTry: (String) -> Unit) {
    val examples = s.blocks.filterIsInstance<Manual.Example>()
    Card {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.title, color = C.text, style = MaterialTheme.typography.titleMedium)
                if (!open && examples.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "「" + examples.take(2).joinToString("」「") { it.phrase } + "」" + if (examples.size > 2) "　等 ${examples.size} 句" else "",
                        color = C.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    )
                }
            }
            s.action?.let { if (it.pkg != Manual.SELF) { Spacer(Modifier.width(8.dp)); StatusBadge(resolver.actionStatus(it)) } }
            Spacer(Modifier.width(8.dp))
            Text(if (open) "收合" else "展開", color = C.accent, style = MaterialTheme.typography.labelLarge)
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            Blocks(s.blocks, onTry)
            s.action?.let {
                Spacer(Modifier.height(8.dp))
                Text("動作代號：${it.action}", color = C.dim, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun Blocks(blocks: List<Manual.Block>, onTry: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Manual.Paragraph -> Text(rich(b.text), color = C.textSoft, style = MaterialTheme.typography.bodyLarge)
                is Manual.Bullet -> Row {
                    Text("•", color = C.accent, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(16.dp))
                    Text(rich(b.text), color = C.textSoft, style = MaterialTheme.typography.bodyLarge)
                }
                is Manual.Tip -> Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.field).border(1.dp, C.auroraViolet.copy(alpha = 0.45f), RoundedCornerShape(12.dp)).padding(12.dp),
                ) { Text(rich(b.text), color = C.textSoft, style = MaterialTheme.typography.bodyMedium) }
                is Manual.Example -> ExampleRow(b, onTry)
            }
        }
    }
}

@Composable
private fun ExampleRow(e: Manual.Example, onTry: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.surfaceHi)
            .border(1.dp, C.border, RoundedCornerShape(14.dp))
            .clickable { onTry(e.phrase) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyph.Mic, null, tint = C.auroraTeal, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("「${e.phrase}」", color = C.text, style = MaterialTheme.typography.bodyLarge)
            if (e.note.isNotBlank()) Text(rich(e.note), color = C.muted, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.width(8.dp))
        Text("試試", color = C.accent, style = MaterialTheme.typography.labelMedium)
    }
}

/** 手機上偵測到、但說明書還沒寫到的 APP／動作：直接用 APP 自己提供的說明與例句 */
@Composable
private fun UncoveredCard(items: List<Pair<tw.hoonsor.voicehub.agent.AgentCatalog, List<String>>>, onTry: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text("說明書尚未收錄", color = C.warn, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
        Text(
            "手機上這些 APP 已經可以用語音控制，但說明書還沒寫到。以下是 APP 自己提供的說明與例句，一樣可以點來試；說明書下一版會補上。",
            color = C.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
        )
        items.forEach { (c, names) ->
            Card(Modifier.padding(bottom = 10.dp)) {
                Text("${c.app}　v${c.version}", color = C.text, style = MaterialTheme.typography.titleMedium)
                names.mapNotNull { c.action(it) }.forEach { a ->
                    Spacer(Modifier.height(12.dp))
                    Text(a.title, color = C.textSoft, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(a.description, color = C.muted, style = MaterialTheme.typography.bodyMedium)
                    a.examples.forEach { ex -> Spacer(Modifier.height(6.dp)); ExampleRow(Manual.Example(ex, ""), onTry) }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: Manual.Status) {
    val (label, color) = when (status) {
        Manual.Status.AVAILABLE -> "可用" to C.ok
        Manual.Status.MISSING_IN_VERSION -> "這版沒有" to C.warn
        Manual.Status.INSTALLED_NO_API -> "規劃中" to C.warn
        Manual.Status.NOT_INSTALLED -> "未安裝" to C.dim
    }
    Text(
        label, color = color, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Pill(text: String, color: androidx.compose.ui.graphics.Color = C.textSoft, onClick: () -> Unit) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(C.surface)
            .border(1.dp, C.border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** 行內格式：**粗體**、`程式字` */
private fun rich(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) && text.indexOf("**", i + 2) > 0 -> {
                val end = text.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = C.text)) { append(text.substring(i + 2, end)) }
                i = end + 2
            }
            text[i] == '`' && text.indexOf('`', i + 1) > 0 -> {
                val end = text.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = C.auroraTeal)) { append(text.substring(i + 1, end)) }
                i = end + 1
            }
            else -> { append(text[i]); i++ }
        }
    }
}
