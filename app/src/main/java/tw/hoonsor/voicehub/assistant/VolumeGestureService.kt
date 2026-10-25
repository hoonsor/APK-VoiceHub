package tw.hoonsor.voicehub.assistant

import android.accessibilityservice.AccessibilityService
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
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as SysSettings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
 * 音量鍵手勢的偵測與叫出，前台服務和無障礙服務共用。
 * 來源有兩種：系統的「媒體音量改變」廣播（藍牙耳機的絕對音量只有這個），
 * 以及無障礙服務收到的實體音量鍵（時間是按下當下，不會因為 ColorOS 延遲送達而失準）。
 */
class VolumeGestureCore(private val ctx: Context, private val source: String) {
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var detector = VolumeGestureDetector(VolumePattern.of(Graph.settings.volumePattern))
    /** 自己還原音量時會再收到廣播，這段時間內忽略 */
    private var ignoreUntil = 0L
    /** 同一次按鍵會同時收到按鍵事件和音量廣播，有按鍵事件時忽略緊接著的廣播 */
    private var lastKeyAt = 0L
    private val main = Handler(Looper.getMainLooper())
    var enabled = true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!enabled) return
            val stream = intent.getIntExtra(EXTRA_STREAM, -1)
            if (stream != AudioManager.STREAM_MUSIC) return
            val after = intent.getIntExtra(EXTRA_VALUE, -1)
            val before = intent.getIntExtra(EXTRA_PREV, -1)
            if (after < 0 || before < 0 || after == before) return
            val now = SystemClock.elapsedRealtime()
            if (now < ignoreUntil || now - lastKeyAt < 700) return
            handle(before, after, now, "音量 $before → $after")
        }
    }

    fun register() {
        // 系統送的廣播，不需要對其他 APP 開放
        val filter = IntentFilter(ACTION_VOLUME_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(receiver, filter)
    }

    fun unregister() {
        runCatching { ctx.unregisterReceiver(receiver) }
    }

    fun reload() {
        detector = VolumeGestureDetector(VolumePattern.of(Graph.settings.volumePattern))
    }

    /** 無障礙服務收到實體音量鍵（按下那一刻，音量還沒變）；dir：+1 加、-1 減，eventTime 是 KeyEvent 的 uptimeMillis */
    fun onKey(dir: Int, eventTime: Long) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        if (now < ignoreUntil) return
        lastKeyAt = now
        // 換算成 elapsedRealtime，跟廣播用同一把尺
        val at = now - (SystemClock.uptimeMillis() - eventTime)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        handle(before, before + dir, at, "按鍵${if (dir > 0) "加" else "減"}（音量 $before）")
    }

    private fun handle(before: Int, after: Int, at: Long, what: String) {
        val original = detector.feed(before, after, at)
        if (original == null) {
            VolumeGestureService.log("[$source] $what")
            return
        }
        if (ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            VolumeGestureService.log("[$source] $what：按法正確，但螢幕鎖定中，不叫出")
            return
        }
        ignoreUntil = SystemClock.elapsedRealtime() + 1000
        // 按鍵事件比音量變化早到，等系統把最後一下套用完再還原
        main.postDelayed({ runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0) } }, 250)
        VolumeGestureService.log("[$source] $what：按法正確，叫出語音總控（${launch()}）")
    }

    /**
     * 叫出對話畫面。無障礙服務本身就能從背景開畫面；是預設數位助理時走系統助理通道；
     * 否則用「顯示在其他應用程式上層」權限開畫面。Android 15 起光有權限不夠，
     * 還必須真的有一個看得見的上層視窗，所以先放一個 1 像素的透明視窗再開，開完拿掉。
     * 1.5 秒內畫面沒出現（被系統或 ColorOS 擋下）就改發通知讓倬點。
     */
    private fun launch(): String {
        val open = Intent(ctx, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (ctx is AccessibilityService && runCatching { ctx.startActivity(open) }.isSuccess) return "無障礙通道"
        VoiceService.instance?.let { vis ->
            if (runCatching { vis.showSession(Bundle(), 0) }.isSuccess) return "數位助理通道"
        }
        if (!SysSettings.canDrawOverlays(ctx)) {
            VolumeGestureService.tapNotification(ctx, "沒有「顯示在其他應用程式上層」權限，無法直接跳出（設定頁可開啟）")
            return "缺少上層顯示權限，改發通知"
        }
        val wm = ctx.getSystemService(WindowManager::class.java)
        val pixel = View(ctx)
        val added = runCatching {
            wm.addView(
                pixel,
                WindowManager.LayoutParams(
                    1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.START },
            )
        }.isSuccess
        val requestedAt = SystemClock.elapsedRealtime()
        // 等透明視窗真的畫出來再開（太早開，系統還不認得它是看得見的視窗）
        main.postDelayed({
            runCatching { ctx.startActivity(open) }
                .onFailure { VolumeGestureService.log("開啟對話畫面失敗：${it.javaClass.simpleName}") }
        }, 120)
        main.postDelayed({
            if (added) runCatching { wm.removeView(pixel) }
            if (AssistantActivity.lastResumedAt < requestedAt) {
                VolumeGestureService.log("對話畫面沒有出現（被系統擋下），改發通知")
                VolumeGestureService.tapNotification(ctx, "系統擋下了直接跳出：請到 設定 › 音量鍵叫出 打開「無障礙服務」")
            }
        }, 1500)
        return if (added) "上層視窗開啟" else "上層視窗建立失敗，仍嘗試開啟"
    }

    private companion object {
        const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        const val EXTRA_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }
}

/**
 * 音量鍵待命（前台服務）：在背景等音量變化，按出設定的按法就叫出對話畫面並把音量調回。
 * 不錄音、不連網、不輪詢；音量沒變時完全不佔 CPU。Android 規定要常駐通知，否則會被關掉。
 * ColorOS 會延遲送廣播給背景 APP，所以無障礙服務開著時改由它負責，這裡只保留通知。
 */
class VolumeGestureService : Service() {
    private lateinit var core: VolumeGestureCore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFY_ID, notification())
        }
        core = VolumeGestureCore(this, "通知")
        core.register()
        running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次啟動都重讀按法（設定頁換按法時會再 start 一次）
        core.reload()
        core.enabled = VolumeAccessibilityService.instance == null
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, notification())
        return START_STICKY
    }

    override fun onDestroy() {
        core.unregister()
        running.value = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("volume_gesture") // v0.3.0 的最低優先度類別，ColorOS 會藏到看不到
        nm.createNotificationChannel(NotificationChannel(CHANNEL_IDLE, "音量鍵待命", NotificationManager.IMPORTANCE_LOW).apply {
            description = "音量鍵手勢需要的常駐通知，可以在系統設定把這個類別收起來"
            setShowBadge(false)
        })
        val pattern = VolumePattern.of(Graph.settings.volumePattern)
        return Notification.Builder(this, CHANNEL_IDLE)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("音量鍵待命中")
            .setContentText("音量鍵按「${pattern.label}」叫出語音總控")
            .setContentIntent(openAssistant(this))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_IDLE = "volume_gesture_idle"
        private const val CHANNEL_ALERT = "volume_gesture_alert"
        private const val NOTIFY_ID = 41
        private const val TAP_ID = 42
        private const val LOG_MAX = 20

        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        private val logFlow = MutableStateFlow<List<String>>(emptyList())
        /** 最近的偵測紀錄（新的在前），設定頁顯示，用來確認耳機按鍵有沒有被收到 */
        val events: StateFlow<List<String>> = logFlow.asStateFlow()

        fun log(msg: String) {
            val time = SimpleDateFormat("HH:mm:ss.S", Locale.TAIWAN).format(Date())
            logFlow.value = (listOf("$time  $msg") + logFlow.value).take(LOG_MAX)
        }

        fun clearLog() { logFlow.value = emptyList() }

        private fun openAssistant(ctx: Context) = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** 沒辦法直接跳出畫面時，發一則可以點的通知 */
        fun tapNotification(ctx: Context, reason: String) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "音量鍵叫出提醒", NotificationManager.IMPORTANCE_HIGH))
            nm.notify(
                TAP_ID,
                Notification.Builder(ctx, CHANNEL_ALERT)
                    .setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle("點這裡開始對話")
                    .setContentText(reason)
                    .setStyle(Notification.BigTextStyle().bigText(reason))
                    .setContentIntent(openAssistant(ctx))
                    .setAutoCancel(true)
                    .setTimeoutAfter(15_000)
                    .build(),
            )
        }

        /** 依設定開或關。從背景（開機、更新後）啟動被系統拒絕時不當掉，等下次打開 APP 再啟動 */
        fun sync(context: Context) {
            val intent = Intent(context, VolumeGestureService::class.java)
            if (Graph.settings.volumeGesture) {
                runCatching { context.startForegroundService(intent) }
                    .onFailure { log("無法啟動待命服務：${it.javaClass.simpleName}（打開語音總控一次即可）") }
            } else {
                context.stopService(intent)
            }
            VolumeAccessibilityService.instance?.reload()
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
