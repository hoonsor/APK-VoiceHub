package tw.hoonsor.voicehub

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tw.hoonsor.voicehub.agent.Orchestrator
import tw.hoonsor.voicehub.agent.ToolRegistry
import tw.hoonsor.voicehub.data.Settings
import tw.hoonsor.voicehub.dispatch.DispatchService
import tw.hoonsor.voicehub.ui.MainActivity
import tw.hoonsor.voicehub.voice.Speaker

/** 全 APP 共用的單例 */
object Graph {
    lateinit var app: Context
        private set
    lateinit var settings: Settings
        private set
    lateinit var registry: ToolRegistry
        private set
    lateinit var orchestrator: Orchestrator
        private set
    lateinit var dispatch: DispatchService
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var speakerInstance: Speaker? = null
    val speaker: Speaker
        get() = speakerInstance ?: Speaker(app).also { speakerInstance = it }

    fun init(context: Context) {
        app = context.applicationContext
        settings = Settings(app)
        dispatch = DispatchService(app) { settings.dispatchBaseUrl }
        registry = ToolRegistry(app)
        orchestrator = Orchestrator(app, settings, registry)
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        // 長按桌面圖示的「設定」捷徑（不用靜態 shortcuts.xml，因為 LAB 版套件名不同）
        runCatching {
            getSystemService(ShortcutManager::class.java).dynamicShortcuts = listOf(
                ShortcutInfo.Builder(this, "settings")
                    .setShortLabel("設定")
                    .setLongLabel("設定與健康檢查")
                    .setIcon(Icon.createWithResource(this, R.drawable.ic_tile))
                    .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
                    .build(),
            )
        }
    }
}
