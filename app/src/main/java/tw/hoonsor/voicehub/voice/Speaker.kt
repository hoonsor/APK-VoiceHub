package tw.hoonsor.voicehub.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import tw.hoonsor.voicehub.Graph
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 語音朗讀。用設定裡的引擎（Settings.ttsEngine，空白＝系統預設）與聲音（Settings.ttsVoice，空白＝台灣中文預設聲音）。
 * 設定改了呼叫 [reload]；設定頁列聲音與試聽用暫時的 TextToSpeech，不影響正在用的那一個。
 */
class Speaker(context: Context) {
    data class Engine(val name: String, val label: String)

    private val app = context.applicationContext
    @Volatile private var ready = false
    private var tts: TextToSpeech? = null

    init {
        connect()
    }

    val isReady get() = ready

    private fun connect() {
        ready = false
        tts?.shutdown()
        var created: TextToSpeech? = null
        created = TextToSpeech(app, { status ->
            if (status == TextToSpeech.SUCCESS) {
                created?.let { applyVoice(it, Graph.settings.ttsVoice) }
                ready = true
            }
        }, Graph.settings.ttsEngine)
        tts = created
    }

    /** 引擎或聲音設定改了之後呼叫 */
    fun reload() = connect()

    /** 指定的聲音不在了（例如被解除安裝）就退回台灣中文 */
    private fun applyVoice(t: TextToSpeech, voiceName: String?) {
        val v = voiceName?.let { name -> runCatching { t.voices }.getOrNull()?.firstOrNull { it.name == name } }
        if (v != null) t.voice = v else t.language = Locale.TAIWAN
    }

    fun hasChinese(): Boolean = ready && (tts?.isLanguageAvailable(Locale.TAIWAN) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE

    /** 目前實際使用的聲音名稱（顯示在設定頁） */
    fun currentVoiceName(): String? = if (ready) runCatching { tts?.voice?.name }.getOrNull() else null

    fun stop() { if (ready) tts?.stop() }

    /** 引擎剛建立（APP 剛打開、剛換聲音）時要等初始化完成，最多等 [ms] 毫秒 */
    suspend fun awaitReady(ms: Long = 3_000): Boolean {
        var waited = 0L
        while (!ready && waited < ms) {
            kotlinx.coroutines.delay(100)
            waited += 100
        }
        return ready
    }

    /** 唸完才回來；引擎 3 秒內沒準備好就跳過（不讓對話卡住） */
    suspend fun speak(text: String, rate: Float) {
        if (text.isBlank() || !awaitReady()) return
        val t = tts ?: return
        speakWith(t, text, rate)
    }

    fun shutdown() = tts?.shutdown()

    /** 手機上安裝的朗讀引擎（Google 語音服務、手機廠商的引擎…） */
    fun engines(): List<Engine> = runCatching { tts?.engines.orEmpty().map { Engine(it.name, it.label) } }.getOrDefault(emptyList())

    /** 某個引擎提供的中文聲音（暫時建立一個 TTS 來查，最多等 5 秒） */
    suspend fun voicesOf(engine: String?): List<VoiceOption> = withTemp(engine) { t ->
        VoiceCatalog.options(
            runCatching { t.voices }.getOrNull().orEmpty().map { it.toInfo() },
        )
    } ?: emptyList()

    /** 試聽：用指定的引擎與聲音唸一句（不改設定） */
    suspend fun preview(engine: String?, voiceName: String?, text: String, rate: Float): Boolean =
        withTemp(engine) { t ->
            applyVoice(t, voiceName)
            speakWith(t, text, rate)
            true
        } ?: false

    private suspend fun <T> withTemp(engine: String?, block: suspend (TextToSpeech) -> T): T? {
        var temp: TextToSpeech? = null
        return try {
            val ok = withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    temp = TextToSpeech(app, { status -> if (cont.isActive) cont.resume(status == TextToSpeech.SUCCESS) }, engine)
                }
            } ?: false
            if (ok) temp?.let { block(it) } else null
        } finally {
            temp?.shutdown()
        }
    }

    private suspend fun speakWith(t: TextToSpeech, text: String, rate: Float): Unit = suspendCancellableCoroutine { cont ->
        val id = "u" + System.nanoTime()
        t.setSpeechRate(rate)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
        })
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
        cont.invokeOnCancellation { t.stop() }
    }

    private fun Voice.toInfo() = VoiceInfo(
        name = name,
        localeTag = locale.toLanguageTag(),
        quality = quality,
        network = isNetworkConnectionRequired,
        notInstalled = features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true,
    )
}
