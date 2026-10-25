package tw.hoonsor.voicehub.assistant

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings as SysSettings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import tw.hoonsor.voicehub.Graph

/**
 * 音量鍵手勢的無障礙服務（選用，ColorOS 建議開）。
 * 為什麼需要：ColorOS 會凍結背景 APP，音量廣播延遲好幾秒才送到、還會被合併掉，手勢永遠比對不到；
 * 系統綁定中的無障礙服務不會被凍結，而且可以直接從背景開對話畫面。
 * 只收音量鍵與音量廣播，不讀畫面內容（設定檔沒有要任何事件類型、也不能讀取視窗內容），按鍵照常傳給系統。
 */
class VolumeAccessibilityService : AccessibilityService() {
    private var core: VolumeGestureCore? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        core = VolumeGestureCore(this, "無障礙").also {
            it.enabled = Graph.settings.volumeGesture
            it.register()
        }
        instance = this
        VolumeGestureService.log("無障礙服務已啟用")
        VolumeGestureService.sync(this) // 讓前台服務停止重複處理
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) core?.onKey(dir, event.eventTime)
        return false // 不攔截，音量照常調整
    }

    fun reload() {
        core?.apply {
            reload()
            enabled = Graph.settings.volumeGesture
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        core?.unregister()
        core = null
        instance = null
        super.onDestroy()
        VolumeGestureService.sync(this) // 前台服務接手
    }

    companion object {
        @Volatile var instance: VolumeAccessibilityService? = null
            private set

        /** 使用者有沒有在系統設定打開（服務可能還沒連上） */
        fun isEnabledInSettings(ctx: Context): Boolean {
            val me = ComponentName(ctx, VolumeAccessibilityService::class.java).flattenToString()
            val list = SysSettings.Secure.getString(ctx.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
