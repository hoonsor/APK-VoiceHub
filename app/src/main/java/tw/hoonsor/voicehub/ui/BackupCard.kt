package tw.hoonsor.voicehub.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import tw.hoonsor.backup.AutoBackup
import tw.hoonsor.voicehub.data.SettingsBackup
import tw.hoonsor.voicehub.data.VoiceHubBackupSource
import tw.hoonsor.voicehub.ui.theme.C

/** 每日自動備份：開關、上次備份時間、立即備份、從備份檔還原 */
@Composable
fun AutoBackupCard() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    var on by remember { mutableStateOf(AutoBackup.isEnabled(ctx)) }
    var busy by remember { mutableStateOf(false) }
    val status = remember(tick, on, busy) { AutoBackup.statusText(ctx) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

    Card {
        ToggleRow(
            "每天自動備份設定",
            "存到手機的 ${AutoBackup.folder(VoiceHubBackupSource())}，用 Dropsync 同步到 Dropbox。內容沒變就不另存，只留最近 ${AutoBackup.KEEP} 份；API key 不備份。",
            on,
        ) { on = it; AutoBackup.setEnabled(ctx, it) }
        Text(status, color = C.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton(if (busy) "備份中…" else "立即備份", primary = true, enabled = !busy) {
                busy = true
                AutoBackup.runNowAsync(ctx) { err ->
                    (ctx as? Activity)?.runOnUiThread {
                        busy = false
                        Toast.makeText(ctx, err?.let { "備份失敗：$it" } ?: "已備份", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            OutlineButton("從備份檔還原") { pick.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }
        }
    }

    restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("用備份檔取代目前設定？") },
            text = { Text("常用地點、語音修正詞、提醒等設定會換成備份檔的內容；API key 不受影響。") },
            confirmButton = {
                TextButton(onClick = {
                    restoreUri = null
                    val msg = runCatching {
                        ctx.contentResolver.openInputStream(uri)!!.use { SettingsBackup.import(ctx, it) }
                    }.fold({ "已還原 $it 項設定" }, { "還原失敗：${it.message}" })
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    (ctx as? Activity)?.recreate() // 畫面上的欄位重新讀設定，避免按「儲存」又蓋回舊值
                }) { Text("還原") }
            },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("取消") } },
        )
    }
}
