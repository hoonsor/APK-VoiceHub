package tw.hoonsor.voicehub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.hoonsor.voicehub.agent.AgentResult
import tw.hoonsor.voicehub.agent.AppMatcher
import tw.hoonsor.voicehub.agent.BuiltinCatalog
import tw.hoonsor.voicehub.agent.ChineseNumber
import tw.hoonsor.voicehub.agent.OfflineParser
import tw.hoonsor.voicehub.ai.Planner
import tw.hoonsor.voicehub.voice.Vad
import java.io.File
import java.time.LocalDateTime

class ChineseNumberTest {
    @Test fun parses() {
        assertEquals(5, ChineseNumber.parse("五"))
        assertEquals(10, ChineseNumber.parse("十"))
        assertEquals(15, ChineseNumber.parse("十五"))
        assertEquals(23, ChineseNumber.parse("二十三"))
        assertEquals(2, ChineseNumber.parse("兩"))
        assertEquals(30, ChineseNumber.parse("三十"))
        assertEquals(105, ChineseNumber.parse("一百零五"))
        assertEquals(42, ChineseNumber.parse("42"))
        assertNull(ChineseNumber.parse("abc"))
    }
}

class OfflineParserTest {
    @Test fun flashlight() {
        assertEquals(true, OfflineParser.parse("打開手電筒")!!.params.getBoolean("on"))
        assertEquals(false, OfflineParser.parse("關掉手電筒")!!.params.getBoolean("on"))
    }

    @Test fun timer() {
        assertEquals(300, OfflineParser.parse("計時五分鐘")!!.params.getInt("seconds"))
        assertEquals(5400, OfflineParser.parse("倒數1小時30分")!!.params.getInt("seconds"))
        assertEquals(1800, OfflineParser.parse("計時半小時")!!.params.getInt("seconds"))
        assertEquals(90, OfflineParser.parse("計時九十秒")!!.params.getInt("seconds"))
    }

    @Test fun alarm() {
        val a = OfflineParser.parse("明天早上七點半叫我")!!
        assertEquals("alarm_set", a.action)
        assertEquals(7, a.params.getInt("hour"))
        assertEquals(30, a.params.getInt("minute"))
        val b = OfflineParser.parse("設一個下午3點20分的鬧鐘")!!
        assertEquals(15, b.params.getInt("hour"))
        assertEquals(20, b.params.getInt("minute"))
        val c = OfflineParser.parse("晚上十點的鬧鐘")!!
        assertEquals(22, c.params.getInt("hour"))
        val d = OfflineParser.parse("鬧鐘 21:05")!!
        assertEquals(21, d.params.getInt("hour"))
        assertEquals(5, d.params.getInt("minute"))
    }

    @Test fun openApp() {
        val a = OfflineParser.parse("幫我打開 LINE")!!
        assertEquals("app_open", a.action)
        assertEquals("LINE", a.params.getString("name"))
    }

    @Test fun media() {
        assertEquals("next", OfflineParser.parse("下一首")!!.params.getString("command"))
        assertEquals("pause", OfflineParser.parse("暫停音樂")!!.params.getString("command"))
    }

    @Test fun dial() {
        assertEquals("0912345678", OfflineParser.parse("撥 0912345678")!!.params.getString("number"))
        assertEquals("媽媽", OfflineParser.parse("打電話給媽媽")!!.params.getString("contact"))
    }

    @Test fun unknown() {
        assertNull(OfflineParser.parse("今天天氣如何"))
    }

    @Test fun yesNo() {
        assertTrue(OfflineParser.isAffirmative("確定"))
        assertTrue(OfflineParser.isAffirmative("好啊"))
        assertFalse(OfflineParser.isAffirmative("不要"))
        assertTrue(OfflineParser.isNegative("取消"))
        assertTrue(OfflineParser.isNegative("不要好了"))
    }
}

class AppMatcherTest {
    private val apps = listOf(
        AppMatcher.Entry("LINE", "jp.naver.line.android"),
        AppMatcher.Entry("YouTube", "com.google.android.youtube"),
        AppMatcher.Entry("YouTube Music", "com.google.android.apps.youtube.music"),
        AppMatcher.Entry("相機", "com.oplus.camera"),
        AppMatcher.Entry("鬧鐘", "com.hoonsor.alarm"),
        AppMatcher.Entry("照片整理箱", "tw.hoonsor.photobox"),
    )

    @Test fun exact() {
        val m = AppMatcher.match("line", apps)
        assertTrue(m is AppMatcher.Match.One && m.entry.packageName == "jp.naver.line.android")
    }

    @Test fun prefixPrefersShorter() {
        val m = AppMatcher.match("YouTube", apps)
        assertTrue(m is AppMatcher.Match.One && m.entry.label == "YouTube")
    }

    @Test fun alias() {
        val m = AppMatcher.match("照相", apps)
        assertTrue(m is AppMatcher.Match.One && m.entry.label == "相機")
    }

    @Test fun partial() {
        val m = AppMatcher.match("照片", apps)
        assertTrue(m is AppMatcher.Match.One && m.entry.label == "照片整理箱")
    }

    @Test fun none() {
        assertTrue(AppMatcher.match("Instagram", apps) is AppMatcher.Match.None)
    }
}

class PlannerTest {
    @Test fun parsesPlainJson() {
        val p = Planner.parsePlan("""{"say":"好","calls":[{"target":"a.b","action":"timer_set","params":{"seconds":60}}]}""")
        assertEquals("好", p.say)
        assertEquals(1, p.calls.size)
        assertEquals(60, p.calls[0].params.getInt("seconds"))
    }

    @Test fun toleratesThinkAndFence() {
        val raw = "<think>想一下</think>\n```json\n{\"say\":\"做不到\",\"calls\":[]}\n```"
        val p = Planner.parsePlan(raw)
        assertEquals("做不到", p.say)
        assertTrue(p.calls.isEmpty())
    }

    @Test fun plainSentenceBecomesSay() {
        assertEquals("今天是星期六", Planner.parsePlan("今天是星期六").say)
    }

    @Test fun promptListsActions() {
        val prompt = Planner.systemPrompt(listOf(BuiltinCatalog.catalog("tw.hoonsor.voicehub", "0")), LocalDateTime.of(2026, 10, 17, 9, 0))
        assertTrue(prompt.contains("alarm_set"))
        assertTrue(prompt.contains("target: tw.hoonsor.voicehub"))
        assertTrue(prompt.contains("2026-10-17 09:00"))
    }

    @Test fun compactSchema() {
        val s = Planner.compactSchema(BuiltinCatalog.actions.first { it.name == "volume_set" }.params)
        assertTrue(s.contains("stream: media|ring|alarm|notification"))
        assertTrue(s.contains("percent: integer 0..100"))
    }

    @Test fun resultRoundTrip() {
        val r = AgentResult.fail("AMBIGUOUS", "有兩個", JSONObject().put("candidates", org.json.JSONArray().put("A").put("B")))
        val back = AgentResult.fromJson(r.toJson("1"))
        assertEquals("AMBIGUOUS", back.errorCode)
        assertEquals(2, back.errorData!!.getJSONArray("candidates").length())
    }
}

class VadTest {
    private fun run(frames: List<Double>): Vad {
        val v = Vad()
        frames.forEach { v.feed(it) }
        return v
    }

    @Test fun detectsUtterance() {
        val frames = List(10) { 100.0 } + List(40) { 3000.0 } + List(50) { 100.0 }
        val v = run(frames)
        assertEquals(Vad.State.DONE_SPEECH, v.state)
        assertTrue(v.heardSpeech)
    }

    @Test fun silenceTimesOut() {
        val v = run(List(300) { 120.0 })
        assertEquals(Vad.State.DONE_SILENCE, v.state)
        assertFalse(v.heardSpeech)
    }

    @Test fun shortBlipIsNotSpeech() {
        val frames = List(10) { 100.0 } + listOf(3000.0, 100.0) + List(20) { 100.0 }
        val v = run(frames)
        assertEquals(Vad.State.WAITING, v.state)
    }
}

/** docs/agent-manifest.json 必須與內建動作的 describe 完全一致（版本號除外） */
class ManifestTest {
    @Test fun manifestMatchesDescribe() {
        val file = listOf(File("../docs/agent-manifest.json"), File("docs/agent-manifest.json")).first { it.parentFile?.exists() == true }
        val actual = BuiltinCatalog.catalog("tw.hoonsor.voicehub", "x").toDescribeJson().apply { remove("version") }
        if (System.getenv("UPDATE_MANIFEST") == "1") {
            file.writeText(actual.toString(2) + "\n", Charsets.UTF_8)
        }
        val expected = JSONObject(file.readText(Charsets.UTF_8)).apply { remove("version") }
        assertTrue("docs/agent-manifest.json 與 BuiltinCatalog 不一致；執行 UPDATE_MANIFEST=1 gradlew testProdDebugUnitTest 重新產生", expected.similar(actual))
    }
}
