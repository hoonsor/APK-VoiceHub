package tw.hoonsor.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 連真正的中心跑一次（平常沒設環境變數會跳過）：
 *   VOCAB_LIVE_TOKEN   裝置權杖
 *   VOCAB_LIVE_TERM    這時中心應該要有的詞
 *   VOCAB_LIVE_ALIAS   開啟替換的別名
 */
class VocabLiveTest {
    @Test fun readsSharedVocabFromRealHub() {
        val token = System.getenv("VOCAB_LIVE_TOKEN")
        assumeTrue(!token.isNullOrBlank())
        val term = System.getenv("VOCAB_LIVE_TERM")
        val alias = System.getenv("VOCAB_LIVE_ALIAS")
        val dir = Files.createTempDirectory("vocab-live").toFile()
        val client = VocabClient(VocabClient.DEFAULT_BASE_URL, "ime", dir) { token }

        val result = client.sync()
        assertEquals(null, result.error)
        assertTrue(client.terms().any { it.term == term })
        assertEquals("問$term", client.correct("問$alias").text)

        val offline = VocabClient("http://127.0.0.1:1", "ime", dir) { token }
        assertEquals("快取在離線時也要能替換", "問$term", offline.correct("問$alias").text)
        println("VOCAB_LIVE revision=${client.status().revision} terms=${client.status().termCount}")
        dir.deleteRecursively()
    }
}
