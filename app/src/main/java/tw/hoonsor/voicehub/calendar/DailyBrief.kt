package tw.hoonsor.voicehub.calendar

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tw.hoonsor.voicehub.Graph
import tw.hoonsor.voicehub.R
import tw.hoonsor.voicehub.ui.MainActivity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 每天定時唸明天的行程：AlarmManager 精準鬧鐘 → [DailyBriefReceiver] → [DailyBriefService]（前台服務，唸完就結束）。
 * 開機、更新 APP、改時間或時區後重新排程（[DailyBriefReceiver] 一起處理）。
 */
object DailyBrief {
    private const val TAG = "DailyBrief"
    const val ACTION_FIRE = "tw.hoonsor.voicehub.BRIEF_FIRE"

    /** 下一次要唸的時間（純邏輯，有測試） */
    fun nextFire(now: LocalDateTime, minuteOfDay: Int): LocalDateTime {
        val today = now.toLocalDate().atStartOfDay().plusMinutes(minuteOfDay.toLong())
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** 明天是週末而且設定不唸週末 → 跳過 */
    fun skipFor(tomorrow: LocalDate, weekend: Boolean) =
        !weekend && (tomorrow.dayOfWeek == DayOfWeek.SATURDAY || tomorrow.dayOfWeek == DayOfWeek.SUNDAY)

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, DailyBriefReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun canExact(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** 依設定排下一次或取消；回傳排好的時間（關閉時 null） */
    fun sync(ctx: Context): LocalDateTime? {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx)
        am.cancel(pi)
        val s = Graph.settings
        if (!s.briefEnabled) return null
        val at = nextFire(LocalDateTime.now(), s.briefMinute)
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runCatching {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi) // 沒有精準鬧鐘權限：可能晚幾分鐘
        }.onFailure { Log.w(TAG, "排程失敗：${it.message}") }
        return at
    }

    /** 立即試唸（設定頁的按鈕） */
    fun runNow(ctx: Context) {
        ctx.startForegroundService(Intent(ctx, DailyBriefService::class.java).putExtra(DailyBriefService.EXTRA_FORCE, true))
    }
}

class DailyBriefReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DailyBrief.ACTION_FIRE -> {
                // 精準鬧鐘觸發時系統允許從背景啟動前台服務
                runCatching { context.startForegroundService(Intent(context, DailyBriefService::class.java)) }
                    .onFailure { Log.w("DailyBrief", "無法啟動朗讀服務：${it.message}") }
                DailyBrief.sync(context)
            }
            else -> DailyBrief.sync(context) // 開機、更新 APP、改時間、改時區、精準鬧鐘權限改變
        }
    }
}

/** 讀明天的行程、發通知、用手機內建聲音唸出來，唸完就停 */
class DailyBriefService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Graph.speaker.stop()
            job?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        channels()
        startForeground(ONGOING_ID, ongoing(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) == true
        if (job?.isActive == true) return START_NOT_STICKY
        job = scope.launch {
            try {
                withTimeoutOrNull(3 * 60_000) { run(force) }
            } catch (e: Exception) {
                Log.w("DailyBrief", "朗讀失敗：${e.message}")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun run(force: Boolean) {
        val s = Graph.settings
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        if (!force && DailyBrief.skipFor(tomorrow, s.briefWeekend)) return
        if (!CalendarReader.hasPermission(this)) {
            post("沒辦法唸明天的行程", "語音總控還沒有讀取行事曆的權限。點這裡打開設定，到「明天行程提醒」按「允許」。")
            return
        }
        val brief = withContext(Dispatchers.IO) {
            CalendarBrief.compose(tomorrow, today, CalendarReader.events(this@DailyBriefService, tomorrow, s.briefExcluded))
        }
        if (brief.count == 0 && !s.briefWhenEmpty && !force) return
        val title = "明天${CalendarBrief.dateWord(tomorrow)}：" + if (brief.count == 0) "沒有行程" else "${brief.count} 個行程"
        post(title, brief.lines.joinToString("\n").ifEmpty { "沒有行程" })

        val audio = getSystemService(AudioManager::class.java)
        // 通話中不唸，只留通知
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) return
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        audio.requestAudioFocus(focus)
        try {
            Graph.speaker.speak(brief.spoken, s.speechRate, allowCloud = s.briefCloudVoice, waitMs = 10_000)
        } finally {
            audio.abandonAudioFocusRequest(focus)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "正在唸行程", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "明天行程", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun ongoing(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DailyBriefService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("正在唸明天的行程")
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun post(title: String, text: String) {
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        getSystemService(NotificationManager::class.java).notify(
            RESULT_ID,
            Notification.Builder(this, CH_RESULT)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(title)
                .setContentText(text.lineSequence().firstOrNull().orEmpty())
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val EXTRA_FORCE = "force"
        private const val ACTION_STOP = "tw.hoonsor.voicehub.BRIEF_STOP"
        private const val CH_ONGOING = "brief_ongoing"
        private const val CH_RESULT = "brief_result"
        private const val ONGOING_ID = 51
        private const val RESULT_ID = 52
    }
}
