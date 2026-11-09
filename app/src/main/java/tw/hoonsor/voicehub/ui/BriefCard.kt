package tw.hoonsor.voicehub.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import tw.hoonsor.voicehub.Graph
import tw.hoonsor.voicehub.calendar.CalendarReader
import tw.hoonsor.voicehub.calendar.DailyBrief
import tw.hoonsor.voicehub.ui.theme.C
import java.time.format.DateTimeFormatter

/** 明天行程提醒：開關、時間、要唸哪些行事曆、權限狀態、立即試唸。選了立刻生效，不用按儲存。 */
@Composable
fun DailyBriefCard() {
    val ctx = LocalContext.current
    val s = Graph.settings
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val granted = remember(tick) { CalendarReader.hasPermission(ctx) }
    val notify = remember(tick) { Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
    val exact = remember(tick) { DailyBrief.canExact(ctx) }
    val battery = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val calendars = remember(tick, granted) { runCatching { CalendarReader.calendars(ctx) }.getOrDefault(emptyList()) }

    var on by remember { mutableStateOf(s.briefEnabled) }
    var minute by remember { mutableIntStateOf(s.briefMinute) }
    var weekend by remember { mutableStateOf(s.briefWeekend) }
    var empty by remember { mutableStateOf(s.briefWhenEmpty) }
    var cloud by remember { mutableStateOf(s.briefCloudVoice) }
    var excluded by remember { mutableStateOf(s.briefExcluded) }
    var next by remember { mutableStateOf(if (s.briefEnabled) DailyBrief.sync(ctx) else null) }

    fun resync() { next = DailyBrief.sync(ctx) }

    Card {
        ToggleRow("每天唸明天的行程", "到了設定的時間，用手機內建聲音唸出明天的行事曆，並留一則通知", on) {
            on = it
            s.briefEnabled = it
            if (it && !granted) askCalendar.launch(Manifest.permission.READ_CALENDAR)
            else if (it && !notify && Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            resync()
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("提醒時間", color = C.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            OutlineButton("%02d:%02d".format(minute / 60, minute % 60)) {
                TimePickerDialog(ctx, { _, h, m ->
                    minute = h * 60 + m
                    s.briefMinute = minute
                    resync()
                }, minute / 60, minute % 60, true).show()
            }
        }
        Spacer(Modifier.height(6.dp))
        ToggleRow("週末也唸", "關掉時，星期五、六晚上不唸（明天是週末）", weekend) { weekend = it; s.briefWeekend = it }
        ToggleRow("沒有行程也唸", "關掉時，明天沒行程就不出聲", empty) { empty = it; s.briefWhenEmpty = it }
        ToggleRow("用 MiniMax 雲端聲音唸", "比較自然，但行程內容會送到 MiniMax 國際版；有學校或人事行程時請保持關閉", cloud) { cloud = it; s.briefCloudVoice = it }
        if (cloud) Text("⚠ 行程標題可能有人名或公務內容，送雲端前不會去識別化。", color = C.warn, style = MaterialTheme.typography.bodyMedium)

        if (on) {
            Spacer(Modifier.height(10.dp))
            StatusLine(granted, if (granted) "已允許讀取行事曆" else "還沒允許讀取行事曆：不會唸")
            StatusLine(exact, if (exact) "可以準時觸發" else "沒有精準鬧鐘權限：可能晚幾分鐘")
            StatusLine(battery, if (battery) "電池不限制" else "電池最佳化中：ColorOS 可能擋掉定時提醒")
            StatusLine(notify, if (notify) "可以發通知" else "沒有通知權限：只唸不留通知")
            next?.let {
                Text("下一次：" + it.format(DateTimeFormatter.ofPattern("M/d HH:mm")), color = C.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!granted) OutlineButton("允許讀取行事曆", primary = true) { askCalendar.launch(Manifest.permission.READ_CALENDAR) }
            if (granted) OutlineButton("現在唸一次", primary = true) { DailyBrief.runNow(ctx) }
            if (on && !battery) OutlineButton("電池不限制") {
                runCatching {
                    ctx.startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            if (on && !exact) OutlineButton("允許精準鬧鐘") {
                runCatching {
                    ctx.startActivity(Intent(SysSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }

        if (granted) {
            Text("要唸的行事曆", color = C.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
            if (calendars.isEmpty()) {
                Text("手機上沒有同步的行事曆。請先在 設定 › 帳號 加入 Google 帳號，並在 Google 日曆 APP 打開共享行事曆的同步。", color = C.dim, style = MaterialTheme.typography.bodyMedium)
            }
            calendars.forEach { c ->
                val desc = c.account + if (!c.visible) "（在 Google 日曆裡隱藏中，不會唸）" else ""
                ToggleRow(c.name.ifBlank { "（沒有名稱）" }, desc, c.id !in excluded) { want ->
                    excluded = if (want) excluded - c.id else excluded + c.id
                    s.briefExcluded = excluded
                }
            }
        }
        Text(
            "共享給這支手機上帳號的行事曆，要在 Google 日曆 APP › 設定 › 該行事曆 › 打開「同步」才讀得到。行程只在手機上整理，不會送給 AI。說「明天有什麼行程」「下週三有什麼活動」也可以隨時查。",
            color = C.dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(ok)
        Spacer(Modifier.size(8.dp))
        Text(text, color = C.text, style = MaterialTheme.typography.bodyMedium)
    }
}
