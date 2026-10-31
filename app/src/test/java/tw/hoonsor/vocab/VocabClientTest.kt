package tw.hoonsor.vocab

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.concurrent.thread

/** 用本機 HTTP 伺服器測真正的連線行為：304、離線、佇列補送、壞掉的快取。 */
class VocabClientTest {
    private lateinit var dir: File
    private lateinit var server: ServerSocket
    private val received = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())
    private var snapshotCode = 200
    private var writeCode = 200
    private val snapshot = """{"revision":"7-a","entry":"ime","terms":[
        {"term":"拉斐爾","category":"project","pinned":true,"aliases":[{"text":"拉法耶","replace":true}]}]}"""

    @Before fun start() {
        dir = Files.createTempDirectory("vocab").toFile()
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use { handle(it) }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return
            head.append(b.toChar())
        }
        val lines = head.toString().split("\r\n")
        val path = lines[0].split(" ")[1]
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
        var read = 0
        while (read < body.size) read += input.read(body, read, body.size - read)
        received += path to String(body, Charsets.UTF_8)
        val code = when {
            headers["authorization"] != "Bearer tok" -> 401
            path.startsWith("/api/v1/snapshot") -> if (path.contains("since=7-a")) 304 else snapshotCode
            else -> writeCode
        }
        val out = if (code == 200) {
            (if (path.startsWith("/api/v1/snapshot")) snapshot else """{"outcome":"undone"}""").toByteArray()
        } else ByteArray(0)
        val response = "HTTP/1.1 $code X\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(response.toByteArray())
            write(out)
            flush()
        }
    }

    @After fun stop() {
        server.close()
        dir.deleteRecursively()
    }

    private fun client(token: String? = "tok", url: String = "http://127.0.0.1:${server.localPort}") =
        VocabClient(url, "ime", dir) { token }

    private fun deadClient() = client(url = "http://127.0.0.1:1")

    @Test fun syncDownloadsThenUses304() {
        val c = client()
        assertTrue(c.sync().changed)
        assertEquals("拉斐爾", c.terms().single().term)
        assertFalse(c.sync().changed)
        assertEquals("7-a", c.status().revision)
    }

    @Test fun cacheSurvivesRestart() {
        client().sync()
        val offline = deadClient()
        assertEquals("拉斐爾", offline.terms().single().term)
        assertEquals("問拉斐爾", offline.correct("問拉法耶").text)
    }

    @Test fun offlineSyncKeepsCacheAndReportsError() {
        client().sync()
        val offline = deadClient()
        assertNotNull(offline.sync().error)
        assertEquals(1, offline.terms().size)
        assertNotNull(offline.status().lastError)
    }

    @Test fun noTokenMeansNoNetworkAndNoFailure() {
        val c = client(token = null)
        assertEquals(VocabClient.SyncResult(false, 0, null), c.sync())
        assertTrue(received.isEmpty())
        assertTrue(c.terms().isEmpty())
    }

    @Test fun emptyTermsWhenFirstUseHasNoCache() {
        assertTrue(deadClient().terms().isEmpty())
        assertEquals("問拉法耶", deadClient().correct("問拉法耶").text)
    }

    @Test fun corruptCacheIsIgnored() {
        File(dir, "vocab-ime.json").writeText("{壞掉")
        assertTrue(client().terms().isEmpty())
        val fresh = client()
        assertTrue(fresh.sync().changed)
        assertEquals(1, fresh.terms().size)
    }

    @Test fun offlineAddIsQueuedThenSentOnce() {
        val offline = deadClient()
        val id = offline.enqueueAdd("派工網站", listOf("派公網站"), "project")
        offline.sync()
        assertEquals(1, offline.status().pending)
        val online = client()
        assertEquals(1, online.sync().sent)
        assertEquals(0, online.status().pending)
        val posts = received.filter { it.first == "/api/v1/terms" }
        assertEquals(1, posts.size)
        assertEquals(id, JSONObject(posts[0].second).getString("clientId"))
        online.sync()
        assertEquals(1, received.count { it.first == "/api/v1/terms" })
    }

    @Test fun serverErrorKeepsQueue() {
        writeCode = 503
        val c = client()
        c.enqueuePropose("派工網站", emptyList(), "project", "scan")
        assertNotNull(c.sync().error)
        assertEquals(1, c.status().pending)
    }

    @Test fun rejectedItemIsDroppedNotRetriedForever() {
        writeCode = 400
        val c = client()
        c.enqueueAdd("壞資料", emptyList(), "other")
        assertNull(c.sync().error)
        assertEquals(0, c.status().pending)
    }

    @Test fun undoRemovesUnsentItemWithoutNetwork() {
        val offline = deadClient()
        val id = offline.enqueueAdd("派工網站", emptyList(), "project")
        assertTrue(offline.undo(id))
        assertEquals(0, offline.status().pending)
    }

    @Test fun undoAsksServerWhenAlreadySent() {
        val c = client()
        assertTrue(c.undo(null))
        assertEquals("/api/v1/undo", received.last().first)
    }

    @Test fun usageIsReportedOnceAsCounts() {
        val c = client()
        c.sync()
        c.noteUsage("問拉斐爾")
        c.noteUsage("拉斐爾在嗎")
        c.noteUsage("今天天氣很好")
        c.sync()
        val posts = received.filter { it.first == "/api/v1/usage" }
        assertEquals(1, posts.size)
        assertEquals(2, JSONObject(posts[0].second).getJSONObject("counts").getInt("拉斐爾"))
        c.sync()
        assertEquals(1, received.count { it.first == "/api/v1/usage" })
    }
}
