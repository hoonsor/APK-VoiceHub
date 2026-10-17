package tw.hoonsor.voicehub.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 簡易語音端點偵測：前 300ms 估背景噪音，音量超過門檻一段時間算「開始講話」，
 * 講完後安靜超過 [endSilenceMs] 就結束。純邏輯，有 JVM 測試（VadTest）。
 */
class Vad(
    private val frameMs: Int = 30,
    private val calibrateMs: Int = 300,
    private val startSpeechMs: Int = 90,
    private val endSilenceMs: Int = 1_200,
    private val noSpeechTimeoutMs: Int = 7_000,
    private val maxUtteranceMs: Int = 20_000,
    private val minThreshold: Double = 350.0,
) {
    enum class State { CALIBRATING, WAITING, SPEAKING, DONE_SPEECH, DONE_SILENCE, DONE_MAX }

    var state = State.CALIBRATING
        private set
    private var elapsed = 0
    private var noiseSum = 0.0
    private var noiseFrames = 0
    private var threshold = minThreshold
    private var loudRun = 0
    private var quietRun = 0
    private var speechMs = 0

    val isDone get() = state == State.DONE_SPEECH || state == State.DONE_SILENCE || state == State.DONE_MAX
    val heardSpeech get() = speechMs > 0

    /** 餵一個 frame 的 RMS，回傳目前狀態 */
    fun feed(rms: Double): State {
        if (isDone) return state
        elapsed += frameMs
        when (state) {
            State.CALIBRATING -> {
                noiseSum += rms
                noiseFrames++
                if (elapsed >= calibrateMs) {
                    threshold = maxOf(minThreshold, noiseSum / noiseFrames * 2.5)
                    state = State.WAITING
                }
            }
            State.WAITING -> {
                loudRun = if (rms > threshold) loudRun + frameMs else 0
                if (loudRun >= startSpeechMs) {
                    state = State.SPEAKING
                    speechMs = loudRun
                    quietRun = 0
                } else if (elapsed >= noSpeechTimeoutMs) {
                    state = State.DONE_SILENCE
                }
            }
            State.SPEAKING -> {
                speechMs += frameMs
                // 講話中門檻放低一點，避免句尾變小聲就被切斷
                quietRun = if (rms < threshold * 0.7) quietRun + frameMs else 0
                if (quietRun >= endSilenceMs) state = State.DONE_SPEECH
            }
            else -> Unit
        }
        if (!isDone && elapsed >= maxUtteranceMs) state = if (heardSpeech) State.DONE_MAX else State.DONE_SILENCE
        return state
    }

    companion object {
        fun rms(pcm: ShortArray, count: Int): Double {
            if (count <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until count) sum += pcm[i].toDouble() * pcm[i]
            return sqrt(sum / count)
        }

        /** 0..1 的音量（給光影動畫用） */
        fun level(rms: Double): Float = (rms / 4_000.0).coerceIn(0.0, 1.0).toFloat()
    }
}

/** 16-bit PCM → WAV */
object Wav {
    fun encode(pcm: ByteArray, sampleRate: Int = 16_000, channels: Int = 1): ByteArray {
        val byteRate = sampleRate * channels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(sampleRate); putInt(byteRate); putShort((channels * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return ByteArrayOutputStream(44 + pcm.size).apply { write(header); write(pcm) }.toByteArray()
    }
}
