package tw.hoonsor.voicehub.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.SystemBarStyle
import tw.hoonsor.voicehub.BuildConfig
import tw.hoonsor.voicehub.ui.MainActivity
import tw.hoonsor.voicehub.ui.theme.VoiceHubTheme

/**
 * 對話畫面。從桌面圖示、長按電源鍵（數位助理）、快速設定磚進來，一打開就開始聽。
 */
class AssistantActivity : ComponentActivity() {
    private val vm: AssistantViewModel by viewModels()

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startListening() else vm.toggleKeyboard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VolumeGestureService.sync(this)
        // 深色介面：狀態列與導覽列圖示固定用淺色
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            VoiceHubTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.closeRequested) { if (state.closeRequested) finish() }
                AssistantScreen(
                    state = state,
                    onMic = { if (hasMic()) vm.onMic() else askMic.launch(Manifest.permission.RECORD_AUDIO) },
                    onSend = vm::submitText,
                    onToggleKeyboard = vm::toggleKeyboard,
                    onConfirm = vm::answerConfirm,
                    onSettings = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                    onDismiss = { finish() },
                )
            }
        }
        if (savedInstanceState == null) startSession(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 已開著又被叫一次（再按一次電源鍵）：開新的一輪
        vm.newSession()
        startSession(intent)
    }

    private fun startSession(intent: Intent?) {
        // LAB 版測試入口：adb shell am start -n <pkg>/.assistant.AssistantActivity --es text "計時五分鐘"
        val injected = if (BuildConfig.FLAVOR == "lab") intent?.getStringExtra(EXTRA_TEXT) else null
        // 從說明書點例句：只放進輸入框，由倬按送出（正式版也開放，因為不會自動執行）
        val draft = intent?.getStringExtra(EXTRA_DRAFT)?.take(500)
        when {
            !injected.isNullOrBlank() -> vm.submitText(injected)
            !draft.isNullOrBlank() -> vm.prefill(draft)
            hasMic() -> vm.startListening()
            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        lastResumedAt = android.os.SystemClock.elapsedRealtime()
    }

    companion object {
        /** 音量鍵手勢用來判斷畫面有沒有真的跳出來 */
        @Volatile var lastResumedAt = 0L
            private set
        const val EXTRA_TEXT = "text"
        const val EXTRA_DRAFT = "draft"
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onStop() {
        super.onStop()
        // 離開畫面就停止錄音，不在背景偷聽；朗讀與執行中的動作讓它做完（開別的 APP 時也要把結果唸完）
        if (!isChangingConfigurations) vm.stopMic()
    }
}
