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
import tw.hoonsor.voicehub.ui.Field
import tw.hoonsor.voicehub.ui.OutlineButton
import tw.hoonsor.voicehub.ui.ToggleRow
import tw.hoonsor.voicehub.ui.theme.C
import tw.hoonsor.voicehub.ui.theme.VoiceHubTheme

/**
 * 系統分享選單的入口：相簿、截圖、其他 APP 分享圖片到這裡，傳給 65 號海報工坊當參考圖。
 * 「不含個資、公務內容」預設不勾：沒勾的圖只存在 A 電腦，要倬確認後才會交給 Claude。
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent).take(MAX_IMAGES)
        if (uris.isEmpty()) {
            Toast.makeText(this, "沒有收到圖片", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent { VoiceHubTheme { ShareSheet(uris, onCancel = { finish() }, onSend = { note, safe -> send(uris, note, safe) }) } }
    }

    private fun send(uris: List<Uri>, note: String, safe: Boolean) {
        val app = applicationContext
        val outbox = Graph.references
        // 複製要在畫面關掉前做完（Uri 權限跟著這個畫面），上傳放到背景
        Graph.appScope.launch {
            val failed = uris.count { runCatching { outbox.add(it, note, safe) }.isFailure }
            finish()
            val rep = outbox.flush()
            val text = when {
                failed > 0 -> "有 $failed 張讀取失敗"
                rep.dropped.isNotEmpty() -> "被拒收：" + rep.dropped.first()
                rep.remaining > 0 -> "連不上海報工坊，先存著 ${rep.remaining} 張，之後自動補送（請開 Tailscale）"
                else -> rep.message ?: "已傳到海報工坊"
            }
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
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
private fun ShareSheet(uris: List<Uri>, onCancel: () -> Unit, onSend: (String, Boolean) -> Unit) {
    var note by remember { mutableStateOf("") }
    var safe by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(C.bg.copy(alpha = .6f)).clickable(onClick = onCancel), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.clickable(remember { MutableInteractionSource() }, null) {}.navigationBarsPadding().imePadding().padding(12.dp)) {
            Card {
                Text("做成海報樣板", color = C.text, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    uris.forEach { Thumb(it) }
                }
                Spacer(Modifier.height(10.dp))
                Field("想學它哪裡？（可不填）", note, { note = it.take(500) }, placeholder = "例如：學它的配色和大標題")
                Spacer(Modifier.height(6.dp))
                ToggleRow("確認不含個資、公務內容", "打開才會交給 Claude 分析。沒打開只存在電腦，要到海報工坊確認。", safe) { safe = it }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineButton("取消", onClick = onCancel)
                    OutlineButton(if (sending) "傳送中…" else "傳到海報工坊", primary = true) {
                        if (!sending) {
                            sending = true
                            onSend(note.trim(), safe)
                        }
                    }
                }
            }
        }
    }
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
