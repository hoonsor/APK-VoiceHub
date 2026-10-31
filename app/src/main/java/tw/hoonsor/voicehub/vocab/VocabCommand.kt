package tw.hoonsor.voicehub.vocab

/**
 * 判斷「教詞」的語音指令。和派工一樣用本機規則，不經 AI，原話不被改寫。
 * 只有明講「聽成」「這個詞」「詞庫」才算，「記住明天要開會」要留給一般流程。
 * 純邏輯，有 JVM 測試。
 */
sealed interface VocabCommand {
    data class Add(val term: String, val aliases: List<String>) : VocabCommand
    data object Undo : VocabCommand
    data object Status : VocabCommand

    companion object {
        private const val GAP = "[，,、：:\\s]*"
        private const val END = "[。.！!]?$"
        private val MISHEARD = Regex("^(?:幫我)?記住$GAP(.+?)(?:這個詞)?$GAP(?:常常|常|老是|總是|會)?被?(?:聽成|聽錯成|誤聽成)$GAP(.+?)$END")
        private val THIS_WORD = Regex("^(?:幫我)?記住這個(?:詞|名字|名稱)$GAP(.+?)$END")
        private val ADD_TO_VOCAB = Regex("^(?:幫我)?把?(.+?)(?:加到|加進|加入|記到|存到)(?:個人)?詞庫$END")
        private val UNDO = Regex("^(?:幫我)?(?:取消|刪掉|撤銷|收回)(?:剛才|剛剛)(?:那個|的|新增的)?詞$END")
        private val STATUS = Regex("詞庫.{0,6}(?:幾個|多少).{0,3}候選|詞庫(?:狀態|現況)|候選詞.{0,3}(?:幾個|多少)")

        fun parse(utterance: String): VocabCommand? {
            val text = utterance.trim()
            if (UNDO.containsMatchIn(text)) return Undo
            if (STATUS.containsMatchIn(text)) return Status
            MISHEARD.find(text)?.let { return Add(it.groupValues[1].trim(), listOf(it.groupValues[2].trim())) }
            THIS_WORD.find(text)?.let { return Add(it.groupValues[1].trim(), emptyList()) }
            ADD_TO_VOCAB.find(text)?.let { return Add(it.groupValues[1].trim(), emptyList()) }
            return null
        }
    }
}
