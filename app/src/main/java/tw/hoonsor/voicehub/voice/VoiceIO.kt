package tw.hoonsor.voicehub.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.coroutines.resume

/** 錄音結果 */
sealed interface Recording {
    data class Speech(val wav: ByteArray, val durationMs: Int) : Recording
    data object Silence : Recording
    data class Failed(val message: String) : Recording
}

/** 用 AudioRecord 錄 16kHz 單聲道，搭配 [Vad] 自動判斷講完了沒 */
class MicRecorder {
    @Volatile private var stopRequested = false

    /** 手動結束（按下麥克風鈕） */
    fun stop() { stopRequested = true }

    @SuppressLint("MissingPermission")
    suspend fun record(onLevel: (Float) -> Unit): Recording = withContext(Dispatchers.IO) {
        stopRequested = false
        val rate = 16_000
        val frame = rate * 30 / 1000 // 30ms
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, frame * 4 * 2))
        } catch (e: Exception) {
            return@withContext Recording.Failed("無法開啟麥克風：${e.message}")
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return@withContext Recording.Failed("麥克風被其他 APP 占用或沒有權限")
        }
        val vad = Vad()
        val pcm = ShortArray(frame)
        val out = ByteArrayOutputStream()
        // 開始講話前保留約 0.3 秒，避免第一個字被切掉
        val preRoll = ArrayDeque<ByteArray>()
        try {
            rec.startRecording()
            while (!vad.isDone && !stopRequested) {
                currentCoroutineContext().ensureActive()
                val n = rec.read(pcm, 0, frame)
                if (n <= 0) continue
                val rms = Vad.rms(pcm, n)
                onLevel(Vad.level(rms))
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    bytes[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = (pcm[i].toInt() shr 8 and 0xFF).toByte()
                }
                val before = vad.state
                vad.feed(rms)
                if (before != Vad.State.SPEAKING && vad.state == Vad.State.SPEAKING) {
                    preRoll.forEach { out.write(it) }
                    preRoll.clear()
                }
                if (vad.state == Vad.State.SPEAKING || vad.state == Vad.State.DONE_SPEECH || vad.state == Vad.State.DONE_MAX) {
                    out.write(bytes)
                } else {
                    preRoll.addLast(bytes)
                    if (preRoll.size > 10) preRoll.removeFirst()
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            onLevel(0f)
        }
        // 手動停止但還在「等待開口」狀態：把預錄的部分也送出去（可能講得很小聲）
        if (!vad.heardSpeech) {
            if (stopRequested && preRoll.isNotEmpty()) preRoll.forEach { out.write(it) } else return@withContext Recording.Silence
        }
        val data = out.toByteArray()
        if (data.size < rate / 4 * 2) return@withContext Recording.Silence // 少於 0.25 秒
        Recording.Speech(Wav.encode(data, rate), data.size / 2 * 1000 / rate)
    }
}

/** Android 內建語音辨識（沒有 Groq key 時的備援） */
class SystemStt(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun stop() { recognizer?.stopListening() }

    /** 必須在主執行緒呼叫 */
    suspend fun listen(onLevel: (Float) -> Unit, onPartial: (String) -> Unit): Result<String> =
        suspendCancellableCoroutine { cont ->
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                cont.resume(Result.failure(IllegalStateException("這支手機沒有可用的內建語音辨識")))
                return@suspendCancellableCoroutine
            }
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = sr
            fun finish(r: Result<String>) {
                if (cont.isActive) cont.resume(r)
                runCatching { sr.destroy() }
                recognizer = null
                onLevel(0f)
            }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) = finish(
                    Result.failure(
                        IllegalStateException(
                            when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "沒聽到聲音"
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "內建辨識需要網路"
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "沒有麥克風權限"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "辨識服務忙碌中"
                                else -> "內建辨識錯誤（$error）"
                            },
                        ),
                    ),
                )
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    finish(if (text.isBlank()) Result.failure(IllegalStateException("沒聽到聲音")) else Result.success(text))
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            sr.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
            )
            cont.invokeOnCancellation { runCatching { sr.destroy() } }
        }
}

