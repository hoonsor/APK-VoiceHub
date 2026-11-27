package tw.hoonsor.voicehub.share

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.hoonsor.voicehub.Graph
import tw.hoonsor.voicehub.ui.Card
import tw.hoonsor.voicehub.ui.ChipRow
import tw.hoonsor.voicehub.ui.Field
import tw.hoonsor.voicehub.ui.OutlineButton
import tw.hoonsor.voicehub.ui.ToggleRow
import tw.hoonsor.voicehub.ui.theme.C
import tw.hoonsor.voicehub.ui.theme.VoiceHubTheme

/**
 * 系統分享選單的入口「分享給專案學習」：相簿、截圖、YouTube 等分享圖片或連結到這裡，
 * 選要給哪個專案學（海報 65 號、影片 69 號、遊戲美術 98 號），傳到電腦 A。
 * 「不含個資、公務內容」預設不勾：沒勾的只存在 A 電腦，要倬放行後才會交給 Claude。
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent).take(MAX_IMAGES)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val link = if (uris.isEmpty()) ShareText.firstUrl(text) else null
        if (uris.isEmpty() && link == null) {
            Toast.makeText(this, "沒有收到圖片或網址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val title = ShareText.title(text.ifBlank { intent.getStringExtra(Intent.EXTRA_SUBJECT) })
        val first = ShareTarget.default(isLink = link != null, last = Graph.settings.lastShareTarget)
        setContent {
            VoiceHubTheme {
                ShareSheet(uris, link, title, first, onCancel = { finish() }) { target, note, safe -> send(uris, link, text, target, note, safe) }
            }
        }
    }

    private fun send(uris: List<Uri>, link: String?, text: String, target: ShareTarget, note: String, safe: Boolean) {
        val app = applicationContext
        val outbox = Graph.references
        Graph.settings.lastShareTarget = target.key
        // 複製要在畫面關掉前做完（Uri 權限跟著這個畫面），上傳放到背景
        Graph.appScope.launch {
            val failed = if (link != null) {
                if (runCatching { outbox.addLink(link, text, note, safe, target) }.isFailure) 1 else 0
            } else {
                uris.count { runCatching { outbox.add(it, note, safe, target) }.isFailure }
            }
            finish()
            val rep = outbox.flush()
            val where = if (target == ShareTarget.POSTER) "海報工坊" else "喜好收件匣"
            val msg = when {
                failed > 0 -> "有 $failed 筆讀取失敗"
                rep.dropped.isNotEmpty() -> "被拒收：" + rep.dropped.first()
                rep.remaining > 0 -> "連不上$where，先存著 ${rep.remaining} 筆，之後自動補送（請開 Tailscale）"
                else -> rep.message ?: "已傳給${target.label}"
            }
            Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(extra(intent))
        Intent.ACTION_SEND_MULTIPLE -> extras(intent)
        else -> emptyList()
    }

    private fun extra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun extras(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()

    private companion object {
        const val MAX_IMAGES = 10
    }
}

@Composable
private fun ShareSheet(
    uris: List<Uri>,
    link: String?,
    title: String,
    first: ShareTarget,
    onCancel: () -> Unit,
    onSend: (ShareTarget, String, Boolean) -> Unit,
) {
    var target by remember { mutableStateOf(first) }
    var note by remember { mutableStateOf("") }
    var safe by remember { mutableStateOf(link != null) }   // 公開網址沒有個資疑慮，預設打開；圖片預設關
    var sending by remember { mutableStateOf(false) }
    val choices = ShareTarget.entries.filter { if (link != null) it.links else it.images }
    Box(Modifier.fillMaxSize().background(C.bg.copy(alpha = .6f)).clickable(onClick = onCancel), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.clickable(remember { MutableInteractionSource() }, null) {}.navigationBarsPadding().imePadding().padding(12.dp)) {
            Card {
                Text("分享給專案學習", color = C.text, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (link != null) {
                    if (title.isNotBlank()) Text(title, color = C.text, style = MaterialTheme.typography.bodyLarge)
                    Text(link, color = C.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    if (ShareText.isYouTube(link)) Text("會在你的電腦下載影片來分析畫面與剪輯節奏，只存在電腦、不公開。", color = C.muted, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        uris.forEach { Thumb(it) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("給哪個專案學？", color = C.textSoft, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                ChipRow(choices.map { it.key to it.label }, target.key) { k -> ShareTarget.of(k)?.let { target = it } }
                Spacer(Modifier.height(10.dp))
                Field("喜歡它哪裡？（可不填）", note, { note = it.take(500) }, placeholder = hint(target))
                Spacer(Modifier.height(6.dp))
                ToggleRow("確認不含個資、公務內容", "打開才會交給 Claude 分析。沒打開只存在電腦，要放行後才會學。", safe) { safe = it }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineButton("取消", onClick = onCancel)
                    OutlineButton(if (sending) "傳送中…" else "傳給${target.label}", primary = true) {
                        if (!sending) {
                            sending = true
                            onSend(target, note.trim(), safe)
                        }
                    }
                }
            }
        }
    }
}

private fun hint(t: ShareTarget) = when (t) {
    ShareTarget.POSTER -> "例如：學它的配色和大標題"
    ShareTarget.VIDEO -> "例如：開場很吸引人、講解節奏剛好"
    ShareTarget.GAME_ART -> "例如：角色的粗外框和低彩度配色"
}

@Composable
private fun Thumb(uri: Uri) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = Size(240, 240)
                context.contentResolver.loadThumbnail(uri, bounds, null)
            }.recoverCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, _, _ -> d.setTargetSampleSize(4) }
            }.getOrNull()
        }
    }
    Box(Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(C.field)) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
