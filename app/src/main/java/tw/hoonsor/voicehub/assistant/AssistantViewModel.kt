package tw.hoonsor.voicehub.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tw.hoonsor.voicehub.Graph
import tw.hoonsor.voicehub.agent.Orchestrator
import tw.hoonsor.voicehub.ai.AiException
import tw.hoonsor.voicehub.ai.LlmClient
import tw.hoonsor.voicehub.data.SttEngine
import tw.hoonsor.voicehub.voice.MicRecorder
import tw.hoonsor.voicehub.voice.Recording
import tw.hoonsor.voicehub.voice.SystemStt

/** 對話畫面的狀態機：待命 → 聆聽 → 轉文字 → 思考 → 執行 → 朗讀 → 待命（或自動再聽） */
enum class Mode { IDLE, LISTENING, TRANSCRIBING, THINKING, EXECUTING, SPEAKING }

sealed interface Msg {
    val id: Long
    data class User(override val id: Long, val text: String) : Msg
    data class Bot(override val id: Long, val text: String, val error: Boolean = false) : Msg
    data class Action(override val id: Long, val title: String, val ok: Boolean, val detail: String) : Msg
    data class Note(override val id: Long, val text: String) : Msg
}

data class UiState(
    val mode: Mode = Mode.IDLE,
    val level: Float = 0f,
    val partial: String = "",
    val messages: List<Msg> = emptyList(),
    val confirming: Boolean = false,
    val keyboard: Boolean = false,
    /** 動作跳到其他 APP，畫面應關閉 */
    val closeRequested: Boolean = false,
)

class AssistantViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val settings = Graph.settings
    private val orchestrator: Orchestrator = Graph.orchestrator
    private val mic = MicRecorder()
    private var systemStt: SystemStt? = null
    private var job: Job? = null
    private var nextId = 0L

    init {
        // 每次打開都重新向其他 APP 要動作清單（各自 5 秒逾時，不擋聆聽）
        viewModelScope.launch { runCatching { Graph.registry.refresh() } }
        // 之前連不上而暫存的派工：打開對話畫面時自動補送
        if (Graph.dispatch.queue.size > 0) {
            Graph.appScope.launch {
                val rep = Graph.dispatch.queue.flush(Graph.dispatch.client)
                Graph.dispatch.flushText(rep)?.let { t -> add { id -> Msg.Note(id, t) } }
            }
        }
    }

    private fun add(m: (Long) -> Msg) = _state.update { it.copy(messages = it.messages + m(nextId++)) }

    /** 開新對話（例如再次長按電源鍵） */
    fun newSession() {
        cancel()
        orchestrator.reset()
        _state.value = UiState()
    }

    fun toggleKeyboard() = _state.update { it.copy(keyboard = !it.keyboard) }

    /** 麥克風鈕：待命時開始聽；聆聽中按＝講完了；其他狀態按＝中斷 */
    fun onMic() {
        when (_state.value.mode) {
            Mode.IDLE -> startListening()
            Mode.LISTENING -> { mic.stop(); systemStt?.stop() }
            else -> { cancel(); startListening() }
        }
    }

    /** 畫面被蓋住：只停麥克風；若正在聽就整輪取消 */
    fun stopMic() {
        if (_state.value.mode == Mode.LISTENING) cancel()
    }

    fun cancel() {
        job?.cancel()
        mic.stop()
        systemStt?.stop()
        Graph.speaker.stop()
        _state.update { it.copy(mode = Mode.IDLE, level = 0f, partial = "") }
    }

    fun startListening() {
        job?.cancel()
        job = viewModelScope.launch {
            Graph.speaker.stop()
            _state.update { it.copy(mode = Mode.LISTENING, partial = "", closeRequested = false) }
            val text = when (val engine = if (settings.hasGroq) settings.sttEngine else SttEngine.SYSTEM) {
                SttEngine.GROQ -> listenGroq()
                SttEngine.SYSTEM -> listenSystem()
            } ?: run {
                _state.update { it.copy(mode = Mode.IDLE, level = 0f, partial = "") }
                return@launch
            }
            process(text)
        }
    }

    private suspend fun listenGroq(): String? {
        val rec = mic.record { lv -> _state.update { it.copy(level = lv) } }
        return when (rec) {
            is Recording.Failed -> { add { id -> Msg.Bot(id, rec.message, error = true) }; null }
            Recording.Silence -> { add { id -> Msg.Note(id, "沒聽到聲音") }; null }
            is Recording.Speech -> {
                _state.update { it.copy(mode = Mode.TRANSCRIBING, level = 0f) }
                try {
                    LlmClient.transcribe(settings.groqBaseUrl, settings.groqKey!!, settings.sttModel, rec.wav, STT_PROMPT)
                        .let(::cleanTranscript)
                        .ifBlank { add { id -> Msg.Note(id, "沒聽清楚，請再說一次") }; null }
                } catch (e: AiException) {
                    add { id -> Msg.Bot(id, "語音轉文字失敗：${e.message}", error = true) }
                    null
                }
            }
        }
    }

    private suspend fun listenSystem(): String? {
        val stt = SystemStt(Graph.app).also { systemStt = it }
        val r = stt.listen(
            onLevel = { lv -> _state.update { it.copy(level = lv) } },
            onPartial = { p -> _state.update { it.copy(partial = p) } },
        )
        systemStt = null
        return r.getOrElse { e ->
            add { id -> Msg.Note(id, e.message ?: "沒聽到聲音") }
            null
        }
    }

    /** 文字輸入也走同一條路 */
    fun submitText(text: String) {
        if (text.isBlank()) return
        job?.cancel()
        job = viewModelScope.launch { process(text.trim()) }
    }

    fun answerConfirm(yes: Boolean) = submitText(if (yes) "確定" else "取消")

    private suspend fun process(text: String) {
        add { id -> Msg.User(id, text) }
        _state.update { it.copy(mode = Mode.THINKING, partial = "", level = 0f, confirming = false) }
        val reply = try {
            orchestrator.handle(text) { ev ->
                when (ev) {
                    is Orchestrator.Event.PhaseChanged -> _state.update {
                        it.copy(mode = if (ev.phase == Orchestrator.Phase.EXECUTING) Mode.EXECUTING else Mode.THINKING)
                    }
                    is Orchestrator.Event.ActionDone -> add { id ->
                        Msg.Action(id, ev.title, ev.result.ok, if (ev.result.ok) ev.result.summary else ev.result.errorMessage.orEmpty())
                    }
                    is Orchestrator.Event.Notice -> add { id -> Msg.Note(id, ev.text) }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Orchestrator.Reply("出錯了：${e.message}")
        }
        add { id -> Msg.Bot(id, reply.text) }
        _state.update { it.copy(confirming = reply.confirming) }

        if (settings.ttsEnabled) {
            _state.update { it.copy(mode = Mode.SPEAKING) }
            Graph.speaker.speak(reply.text, settings.speechRate)
        }
        when {
            // 已跳到別的 APP：不在背景再聽（Android 也不允許背景錄音）
            reply.leavesApp -> _state.update { it.copy(mode = Mode.IDLE, closeRequested = settings.autoClose) }
            reply.expectsAnswer || settings.autoListen -> startListening()
            else -> _state.update { it.copy(mode = Mode.IDLE) }
        }
    }

    override fun onCleared() {
        cancel()
    }

    private companion object {
        /** 給 Whisper 的提示：要繁體中文、台灣用語 */
        const val STT_PROMPT = "以下是台灣繁體中文的手機語音指令，例如：打開LINE、明天早上七點叫我、計時五分鐘、手電筒打開。"

        /** Whisper 在無聲時常幻聽出的句子 */
        private val hallucinations = listOf("請不吝點贊", "訂閱", "字幕由", "謝謝觀看", "明鏡與點點")

        fun cleanTranscript(s: String): String {
            val t = s.trim()
            return if (hallucinations.any { t.contains(it) } && t.length < 30) "" else t
        }
    }
}
