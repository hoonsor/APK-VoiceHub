package tw.hoonsor.voicehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.hoonsor.voicehub.vocab.VocabBridge
import tw.hoonsor.voicehub.vocab.VocabCommand
import tw.hoonsor.vocab.VocabAlias
import tw.hoonsor.vocab.VocabTerm

class VocabCommandTest {
    @Test fun misheard() {
        val want = VocabCommand.Add("拉斐爾", listOf("拉法耶"))
        assertEquals(want, VocabCommand.parse("記住，拉斐爾常被聽成拉法耶"))
        assertEquals(want, VocabCommand.parse("幫我記住拉斐爾這個詞，常常被聽成拉法耶。"))
        assertEquals(want, VocabCommand.parse("記住拉斐爾會聽成：拉法耶"))
    }

    @Test fun plainWord() {
        val want = VocabCommand.Add("利姆路", emptyList())
        assertEquals(want, VocabCommand.parse("記住這個詞，利姆路"))
        assertEquals(want, VocabCommand.parse("把利姆路加到詞庫"))
        assertEquals(want, VocabCommand.parse("幫我把利姆路加進個人詞庫。"))
    }

    @Test fun undoAndStatus() {
        assertEquals(VocabCommand.Undo, VocabCommand.parse("取消剛才那個詞"))
        assertEquals(VocabCommand.Undo, VocabCommand.parse("刪掉剛剛的詞。"))
        assertEquals(VocabCommand.Status, VocabCommand.parse("詞庫有幾個候選"))
        assertEquals(VocabCommand.Status, VocabCommand.parse("詞庫狀態"))
    }

    @Test fun otherSentencesAreNotCommands() {
        assertNull(VocabCommand.parse("記住明天要開會"))
        assertNull(VocabCommand.parse("提醒我取消訂閱"))
        assertNull(VocabCommand.parse("我聽成別的了"))
        assertNull(VocabCommand.parse("打開字典"))
    }
}

class VocabPromptTest {
    private val base = "基本句。"

    @Test fun noTermsKeepsOriginalPrompt() {
        assertEquals(base, VocabBridge.buildSttPrompt(base, emptyList()))
    }

    @Test fun addsTermsWithLimit() {
        assertEquals("基本句。常用詞彙：甲、乙。", VocabBridge.buildSttPrompt(base, listOf("甲", "乙")))
        val many = VocabBridge.buildSttPrompt(base, List(50) { "詞$it" })
        assertEquals(40, many.substringAfter("：").count { it == '詞' })
    }

    @Test fun plannerNoteShowsAliases() {
        val terms = listOf(VocabTerm("拉斐爾", "project", false, listOf(VocabAlias("拉法耶", true))), VocabTerm("簡簽", "official", false, emptyList()))
        assertEquals("拉斐爾（常被聽成：拉法耶）、簡簽", VocabBridge.buildPlannerNote(terms))
        assertEquals("", VocabBridge.buildPlannerNote(emptyList()))
    }
}
