package tw.hoonsor.voicehub.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import tw.hoonsor.voicehub.BuildConfig
import tw.hoonsor.voicehub.assistant.AssistantActivity
import tw.hoonsor.voicehub.ui.theme.C
import tw.hoonsor.voicehub.ui.theme.VoiceHubTheme

/** 設定、動作目錄、健康檢查、API 測試台 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            VoiceHubTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("語音總控", style = MaterialTheme.typography.titleLarge, color = C.text)
                            Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = C.dim)
                        }
                        OutlineButton("開始對話", icon = Glyph.Mic, primary = true) {
                            startActivity(Intent(this@MainActivity, AssistantActivity::class.java))
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            0 -> CatalogScreen()
                            1 -> ManualScreen()
                            2 -> TestBenchScreen()
                            3 -> HealthScreen()
                            else -> SettingsScreen()
                        }
                    }
                    BottomBar(tab) { tab = it }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf<Pair<String, ImageVector>>("動作" to Glyph.Grid, "說明書" to Glyph.Book, "測試台" to Glyph.Flask, "健康檢查" to Glyph.Heart, "設定" to Glyph.Gear)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(C.surface)
            .border(1.dp, C.border, RoundedCornerShape(20.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { i, (label, icon) ->
            val on = i == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) C.surfaceHi else C.surface)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, null, tint = if (on) C.accent else C.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(2.dp))
                Text(label, color = if (on) C.text else C.muted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
