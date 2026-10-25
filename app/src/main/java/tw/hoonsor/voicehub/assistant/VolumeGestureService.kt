package tw.hoonsor.voicehub.assistant

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings as SysSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tw.hoonsor.voicehub.Graph
import tw.hoonsor.voicehub.R
import tw.hoonsor.voicehub.voice.VolumeGestureDetector
import tw.hoonsor.voicehub.voice.VolumePattern
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 音量鍵手勢：在背景等「媒體音量改變」的系統廣播（耳機與手機側鍵都會觸發），
 * 按出設定的按法就叫出對話畫面，並把音量調回按之前的值。
 * 不錄音、不連網、不輪詢；音量沒變時完全不佔 CPU。Android 規定要常駐通知，否則會被關掉。
 */
class VolumeGestureService : Service() {
    private lateinit var audio: AudioManager
    private var detector = VolumeGestureDetector(VolumePattern.UP_DOWN_UP)
    /** 自己還原音量時會再收到廣播，這段時間內忽略 */
    private var ignoreUntil = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val stream = intent.getIntExtra(EXTRA_STREAM, -1)
            if (stream != AudioManager.STREAM_MUSIC) return
            val after = intent.getIntExtra(EXTRA_VALUE, -1)
            val before = intent.getIntExtra(EXTRA_PREV, -1)
            if (after < 0 || before < 0 || after == before) return
            val now = SystemClock.elapsedRealtime()
            if (now < ignoreUntil) return
            val original = detector.feed(before, after, now)
            if (original == null) {
                log("音量 $before → $after")
                return
            }
            if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                log("音量 $before → $after：按法正確，但螢幕鎖定中，不叫出")
                return
            }
            ignoreUntil = now + 800
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0) }
            log("音量 $before → $after：按法正確，叫出語音總控（${launch()}）")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFY_ID, notification())
        }
        // 系統送的廣播，不需要對其他 APP 開放
        val filter = IntentFilter(ACTION_VOLUME_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
        running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次啟動都重讀按法（設定頁換按法時會再 start 一次）
        detector = VolumeGestureDetector(VolumePattern.of(Graph.settings.volumePattern))
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, notification())
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        running.value = false
        super.onDestroy()
    }

    /**
     * 叫出對話畫面。是預設數位助理時走系統助理通道（不受背景啟動限制）；
     * 否則直接開畫面，需要「顯示在其他應用程式上層」權限，沒有就改發通知讓倬點。
     */
    private fun launch(): String {
        VoiceService.instance?.let { vis ->
            if (runCatching { vis.showSession(Bundle(), 0) }.isSuccess) return "數位助理通道"
        }
        if (SysSettings.canDrawOverlays(this)) {
            startActivity(Intent(this, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "直接開啟"
        }
        getSystemService(NotificationManager::class.java).notify(
            TAP_ID,
            Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("點這裡開始對話")
                .setContentText("沒有「顯示在其他應用程式上層」權限，無法直接跳出（健康檢查頁可開啟）")
                .setContentIntent(openAssistant())
                .setAutoCancel(true)
                .setTimeoutAfter(15_000)
                .build(),
        )
        return "缺少上層顯示權限，改發通知"
    }

    private fun openAssistant() = PendingIntent.getActivity(
        this, 0, Intent(this, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_IDLE, "音量鍵待命", NotificationManager.IMPORTANCE_MIN).apply {
            description = "音量鍵手勢需要的常駐通知，可以在系統設定把這個類別收起來"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "音量鍵叫出提醒", NotificationManager.IMPORTANCE_HIGH))
        val pattern = VolumePattern.of(Graph.settings.volumePattern)
        return Notification.Builder(this, CHANNEL_IDLE)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("音量鍵待命中")
            .setContentText("音量鍵按「${pattern.label}」叫出語音總控")
            .setContentIntent(openAssistant())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
        private const val CHANNEL_IDLE = "volume_gesture"
        private const val CHANNEL_ALERT = "volume_gesture_alert"
        private const val NOTIFY_ID = 41
        private const val TAP_ID = 42
        private const val LOG_MAX = 20

        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        private val logFlow = MutableStateFlow<List<String>>(emptyList())
        /** 最近的偵測紀錄（新的在前），設定頁顯示，用來確認耳機按鍵有沒有被收到 */
        val events: StateFlow<List<String>> = logFlow.asStateFlow()

        private fun log(msg: String) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(Date())
            logFlow.value = (listOf("$time  $msg") + logFlow.value).take(LOG_MAX)
        }

        fun clearLog() { logFlow.value = emptyList() }

        /** 依設定開或關。從背景（開機、更新後）啟動被系統拒絕時不當掉，等下次打開 APP 再啟動 */
        fun sync(context: Context) {
            val intent = Intent(context, VolumeGestureService::class.java)
            if (Graph.settings.volumeGesture) {
                runCatching { context.startForegroundService(intent) }
                    .onFailure { log("無法啟動待命服務：${it.javaClass.simpleName}（打開語音總控一次即可）") }
            } else {
                context.stopService(intent)
            }
        }
    }
}

/** 開機與 APP 更新後恢復音量鍵待命 */
class VolumeGestureBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            VolumeGestureService.sync(context)
        }
    }
}
