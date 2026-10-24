package tw.hoonsor.voicehub.agent

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 執行 [BuiltinCatalog] 的系統動作。每個動作都只用 Android 公開 API，不需要無障礙權限。
 * 會跳到其他 APP 的動作回傳 data.leavesApp=true，對話視窗可據此自動收起。
 */
class BuiltinActions(private val context: Context) {

    private var torchOn = false

    fun execute(action: String, params: JSONObject, dryRun: Boolean): AgentResult {
        val spec = BuiltinCatalog.actions.firstOrNull { it.name == action }
            ?: return AgentResult.fail(AgentApi.NOT_FOUND, "沒有這個動作：$action")
        missingRequired(spec, params)?.let { return AgentResult.fail(AgentApi.INVALID_PARAMS, "缺少參數：$it") }
        if (dryRun) return AgentResult.ok("（試跑）會執行：${spec.title}")
        return try {
            when (action) {
                "app_list" -> appList(params.optString("query"))
                "app_open" -> appOpen(params.getString("name"))
                "alarm_set" -> alarmSet(params)
                "timer_set" -> timerSet(params)
                "phone_dial" -> phoneDial(params)
                "sms_draft" -> smsDraft(params)
                "map_navigate" -> mapNavigate(params)
                "web_search" -> webSearch(params.getString("query"))
                "url_open" -> urlOpen(params.getString("url"))
                "flashlight_set" -> flashlight(params.getBoolean("on"))
                "volume_set" -> volumeSet(params.getString("stream"), params.getInt("percent"))
                "media_control" -> mediaControl(params.getString("command"))
                "settings_open" -> settingsOpen(params.getString("page"))
                "calendar_event_add" -> calendarAdd(params)
                "battery_status" -> battery()
                else -> AgentResult.fail(AgentApi.NOT_FOUND, "沒有這個動作：$action")
            }
        } catch (e: ActivityNotFoundException) {
            AgentResult.fail(AgentApi.NOT_FOUND, "手機上沒有能處理這件事的 APP")
        } catch (e: SecurityException) {
            AgentResult.fail(AgentApi.PERMISSION_DENIED, "權限不足：${e.message}")
        } catch (e: org.json.JSONException) {
            AgentResult.fail(AgentApi.INVALID_PARAMS, "參數格式錯誤：${e.message}")
        } catch (e: Exception) {
            AgentResult.fail(AgentApi.INTERNAL, "${e.javaClass.simpleName}：${e.message}")
        }
    }

    private fun missingRequired(spec: ActionSpec, params: JSONObject): String? {
        val req = spec.params.optJSONArray("required") ?: return null
        for (i in 0 until req.length()) {
            val k = req.optString(i)
            if (!params.has(k) || params.isNull(k) || params.optString(k).isBlank()) return k
        }
        return null
    }

    private fun leaves(summary: String, extra: JSONObject = JSONObject()) =
        AgentResult.ok(summary, extra.put("leavesApp", true))

    private fun start(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------- APP ----------

    fun launchableApps(): List<AppMatcher.Entry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { AppMatcher.Entry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
    }

    private fun appList(query: String): AgentResult {
        val all = launchableApps()
        val list = if (query.isBlank()) all else AppMatcher.rank(query, all).map { it.first }
        val names = JSONArray(list.take(80).map { it.label })
        val summary = when {
            list.isEmpty() -> "找不到符合「$query」的 APP"
            query.isBlank() -> "共有 ${all.size} 個 APP"
            else -> "找到 ${list.size} 個：" + list.take(5).joinToString("、") { it.label }
        }
        return AgentResult.ok(summary, JSONObject().put("apps", names).put("total", list.size))
    }

    private fun appOpen(name: String): AgentResult {
        return when (val m = AppMatcher.match(name, launchableApps())) {
            is AppMatcher.Match.One -> {
                val intent = context.packageManager.getLaunchIntentForPackage(m.entry.packageName)
                    ?: return AgentResult.fail(AgentApi.NOT_FOUND, "${m.entry.label} 無法直接開啟")
                start(intent)
                leaves("已開啟 ${m.entry.label}", JSONObject().put("package", m.entry.packageName))
            }
            is AppMatcher.Match.Many -> AgentResult.fail(
                AgentApi.AMBIGUOUS, "符合「$name」的 APP 不只一個",
                JSONObject().put("candidates", JSONArray(m.candidates.map { it.label })),
            )
            AppMatcher.Match.None -> AgentResult.fail(AgentApi.NOT_FOUND, "手機上找不到叫「$name」的 APP")
        }
    }

    // ---------- 時鐘 ----------

    private fun alarmSet(p: JSONObject): AgentResult {
        val h = p.getInt("hour")
        val m = p.getInt("minute")
        if (h !in 0..23 || m !in 0..59) return AgentResult.fail(AgentApi.INVALID_PARAMS, "時間不正確：$h:$m")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        p.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val days = p.optJSONArray("days")
        if (days != null && days.length() > 0) {
            // 1=週一…7=週日 → Calendar.SUNDAY(1)…SATURDAY(7)
            val cal = ArrayList((0 until days.length()).map { days.optInt(it) }.filter { it in 1..7 }.map { if (it == 7) 1 else it + 1 })
            intent.putExtra(AlarmClock.EXTRA_DAYS, cal)
        }
        start(intent)
        val label = p.optString("label").takeIf { it.isNotBlank() }?.let { "「$it」" }.orEmpty()
        return AgentResult.ok("已用系統時鐘設定 %02d:%02d 的鬧鐘%s".format(h, m, label))
    }

    private fun timerSet(p: JSONObject): AgentResult {
        val s = p.getInt("seconds")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, s)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        p.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        start(intent)
        return AgentResult.ok("已開始計時 ${durationText(s)}")
    }

    private fun durationText(s: Int): String = buildString {
        if (s >= 3600) append(s / 3600).append(" 小時 ")
        if (s % 3600 >= 60) append(s % 3600 / 60).append(" 分 ")
        if (s % 60 > 0) append(s % 60).append(" 秒")
    }.trim()

    // ---------- 通訊 ----------

    private fun resolveNumber(who: String): Pair<String, String>? {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.length >= 3 && digits.length >= who.count { !it.isWhitespace() } - 1) return who to digits
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("要用聯絡人名稱撥號，請先在健康檢查頁開啟聯絡人權限")
        }
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(who))
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0) to c.getString(1)
        }
        return null
    }

    private fun phoneDial(p: JSONObject): AgentResult {
        val who = p.optString("number").ifBlank { p.optString("contact") }
        if (who.isBlank()) return AgentResult.fail(AgentApi.INVALID_PARAMS, "要打給誰？")
        val (name, number) = resolveNumber(who) ?: return AgentResult.fail(AgentApi.NOT_FOUND, "聯絡人裡找不到「$who」")
        start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
        return leaves("已打開撥號畫面：$name，請按撥出")
    }

    private fun smsDraft(p: JSONObject): AgentResult {
        val to = p.optString("to")
        val number = if (to.isBlank()) "" else resolveNumber(to)?.second
            ?: return AgentResult.fail(AgentApi.NOT_FOUND, "聯絡人裡找不到「$to」")
        start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", p.getString("body")))
        return leaves("已填好簡訊草稿，請確認後按傳送")
    }

    // ---------- 網路與地圖 ----------

    private fun mapNavigate(p: JSONObject): AgentResult {
        val dest = p.getString("destination")
        // 沒指定就用設定的預設交通方式（預設機車）
        val wanted = p.optString("mode").ifBlank { tw.hoonsor.voicehub.Graph.settings.navMode }
        val mode = when (wanted) {
            "walking" -> "w"
            "bicycling" -> "b"
            "transit" -> "r"
            "motorcycle" -> "l" // Google 地圖的機車（兩輪車）模式
            else -> "d"
        }
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}&mode=$mode"))
        try {
            start(nav)
        } catch (e: ActivityNotFoundException) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}")))
        }
        val how = mapOf("walking" to "走路", "bicycling" to "騎腳踏車", "transit" to "搭大眾運輸", "motorcycle" to "騎機車", "driving" to "開車")[wanted] ?: "開車"
        return leaves("開始導航到 $dest（$how）")
    }

    private fun webSearch(q: String): AgentResult {
        try {
            start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q))
        } catch (e: ActivityNotFoundException) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        }
        return leaves("已搜尋「$q」")
    }

    private fun urlOpen(url: String): AgentResult {
        val u = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(url)) url else "https://$url"
        start(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        return leaves("已開啟 $u")
    }

    // ---------- 硬體 ----------

    private fun flashlight(on: Boolean): AgentResult {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return AgentResult.fail(AgentApi.NOT_FOUND, "這支手機沒有閃光燈")
        cm.setTorchMode(id, on)
        val was = torchOn
        torchOn = on
        return AgentResult.ok(
            if (on) "手電筒已打開" else "手電筒已關閉",
            undo = JSONObject().put("action", "flashlight_set").put("params", JSONObject().put("on", was)),
        )
    }

    private fun volumeSet(stream: String, percent: Int): AgentResult {
        val am = context.getSystemService(AudioManager::class.java)
        val type = when (stream) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(type)
        val before = am.getStreamVolume(type)
        val target = (max * percent.coerceIn(0, 100) / 100.0).let { Math.round(it).toInt() }
        am.setStreamVolume(type, target, AudioManager.FLAG_SHOW_UI)
        val name = mapOf("media" to "媒體", "ring" to "鈴聲", "alarm" to "鬧鐘", "notification" to "通知")[stream] ?: "媒體"
        return AgentResult.ok(
            "${name}音量已調到 $percent%",
            undo = JSONObject().put("action", "volume_set")
                .put("params", JSONObject().put("stream", stream).put("percent", if (max == 0) 0 else before * 100 / max)),
        )
    }

    private fun mediaControl(cmd: String): AgentResult {
        val am = context.getSystemService(AudioManager::class.java)
        val code = when (cmd) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        val text = mapOf("play" to "繼續播放", "pause" to "已暫停", "next" to "下一首", "previous" to "上一首")[cmd] ?: "已切換播放"
        return AgentResult.ok(text)
    }

    private fun settingsOpen(page: String): AgentResult {
        val action = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "notifications" -> Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS
            "data" -> Settings.ACTION_DATA_USAGE_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        try {
            start(Intent(action))
        } catch (e: ActivityNotFoundException) {
            start(Intent(Settings.ACTION_SETTINGS))
        }
        return leaves("已打開設定")
    }

    private fun calendarAdd(p: JSONObject): AgentResult {
        val zone = ZoneId.systemDefault()
        val start = runCatching { LocalDateTime.parse(p.getString("start").take(16)) }.getOrNull()
            ?: return AgentResult.fail(AgentApi.INVALID_PARAMS, "開始時間格式錯誤：${p.optString("start")}")
        val end = p.optString("end").takeIf { it.isNotBlank() }?.let { runCatching { LocalDateTime.parse(it.take(16)) }.getOrNull() }
            ?: start.plusHours(1)
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, p.getString("title"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.atZone(zone).toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.atZone(zone).toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, p.optBoolean("allDay", false))
        p.optString("location").takeIf { it.isNotBlank() }?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        start(intent)
        return leaves("已打開行事曆新增畫面，請確認後按儲存")
    }

    private fun battery(): AgentResult {
        val bm = context.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return AgentResult.ok(
            "電量 $pct%" + if (charging) "，充電中" else "",
            JSONObject().put("percent", pct).put("charging", charging),
        )
    }
}
