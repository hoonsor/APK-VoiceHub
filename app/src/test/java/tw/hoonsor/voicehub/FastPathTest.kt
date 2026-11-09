package tw.hoonsor.voicehub

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.hoonsor.voicehub.agent.AppMatcher
import tw.hoonsor.voicehub.agent.FastPath
import tw.hoonsor.voicehub.agent.Phonetic
import tw.hoonsor.voicehub.agent.SttFixes

class FastPathTest {
    private fun action(s: String) = FastPath.match(s)?.action

    @Test fun simpleCommandsSkipAi() {
        assertEquals("volume_set", action("媒體音量調到三十"))
        assertEquals(30, FastPath.match("媒體音量調到三十")!!.params.getInt("percent"))
        assertEquals("media", FastPath.match("音量調到50%")!!.params.getString("stream"))
        assertEquals(100, FastPath.match("鬧鐘音量開到最大")!!.params.getInt("percent"))
        assertEquals("ring", FastPath.match("鈴聲關靜音")!!.params.getString("stream"))
        assertEquals(true, FastPath.match("打開手電筒")!!.params.getBoolean("on"))
        assertEquals(false, FastPath.match("手電筒關掉")!!.params.getBoolean("on"))
        assertEquals("next", FastPath.match("下一首")!!.params.getString("command"))
        assertEquals(300, FastPath.match("計時五分鐘")!!.params.getInt("seconds"))
        assertEquals(1800, FastPath.match("半小時倒數")!!.params.getInt("seconds"))
        val a = FastPath.match("早上七點半叫我")!!
        assertEquals("alarm_set", a.action)
        assertEquals(7, a.params.getInt("hour"))
        assertEquals(30, a.params.getInt("minute"))
        assertEquals(21, FastPath.match("晚上九點的鬧鐘")!!.params.getInt("hour"))
        assertEquals("battery_status", action("還剩多少電"))
        assertEquals("音樂播放器", FastPath.match("打開音樂播放器")!!.params.getString("name"))
        assertEquals("tailscale", FastPath.match("開啟tailscale")!!.params.getString("name"))
    }

    @Test fun anythingUnclearGoesToAi() {
        assertNull(action("明天早上七點叫我")) // 有日期：系統鬧鐘可能設成今天
        assertNull(action("每天早上七點叫我"))
        assertNull(action("五分鐘後提醒我倒垃圾")) // 有提醒內容
        assertNull(action("打開LINE然後傳訊息給媽媽"))
        assertNull(action("音量是不是太小"))
        assertNull(action("把音量調高一點"))
        assertNull(action("開燈"))
        assertNull(action("今天天氣如何"))
        assertNull(action("手電筒怎麼開"))
    }

    @Test fun appOpenNeedsConfidentMatch() {
        val apps = listOf(
            AppMatcher.Entry("LINE", "jp.naver.line.android"),
            AppMatcher.Entry("YouTube", "com.google.android.youtube"),
            AppMatcher.Entry("SoundFlow", "tw.hoonsor.soundflow"),
            AppMatcher.Entry("音樂", "com.heytap.music"),
            AppMatcher.Entry("Tailscale", "com.tailscale.ipn"),
        )
        assertEquals("tw.hoonsor.soundflow", FastPath.confidentApp("音樂播放器", apps)?.packageName)
        assertEquals("com.tailscale.ipn", FastPath.confidentApp("tailscale", apps)?.packageName)
        assertEquals("com.tailscale.ipn", FastPath.confidentApp("tail skill", apps)?.packageName)
        // 名稱後面還有別的要求：分數不夠高，交給 AI
        assertNull(FastPath.confidentApp("YouTube播放周杰倫", apps))
        assertNull(FastPath.confidentApp("藍牙", apps))
    }
}

class PhoneticTest {
    // JVM 沒有 Android ICU：用假的拼音表模擬 Whisper 常見的音譯
    private val fake = mapOf('泰' to "tai", '爾' to "er", '斯' to "si", '凱' to "kai", '蓋' to "gai", '颯' to "sa", '風' to "feng", '弗' to "fu", '洛' to "luo", '搜' to "sou", '得' to "de")

    @After fun reset() { Phonetic.romanize = { it } }

    @Test fun matchesTransliteration() {
        Phonetic.romanize = { s -> s.map { c -> fake[c]?.let { " $it " } ?: c.toString() }.joinToString("") }
        assertEquals(Phonetic.skeleton("Tailscale"), Phonetic.skeleton("泰爾斯凱爾"))
        assertTrue(Phonetic.score("泰爾斯蓋爾", "Tailscale") >= 80)
        assertTrue(Phonetic.score("tail skill", "Tailscale") >= 80)
        val apps = listOf(AppMatcher.Entry("Tailscale", "com.tailscale.ipn"), AppMatcher.Entry("設定", "com.android.settings"))
        val m = AppMatcher.match("泰爾斯凱爾", apps)
        assertTrue(m is AppMatcher.Match.One && m.entry.packageName == "com.tailscale.ipn")
    }

    @Test fun ignoresShortOrChineseLabels() {
        assertEquals(0, Phonetic.score("賴", "LINE")) // 太短，不猜
        assertEquals(0, Phonetic.score("設定", "相機"))
        assertEquals(0, Phonetic.score("tailscale", "Gmail"))
    }
}

class SttFixesTest {
    @Test fun replacesHeardWords() {
        val fixes = SttFixes.parse("泰斯蓋爾=Tailscale\n搜得否＝SoundFlow\n\n壞掉的一行\n=沒有左邊")
        assertEquals(2, fixes.size)
        assertEquals("開啟Tailscale", SttFixes.apply("開啟泰斯蓋爾", fixes))
        assertEquals("打開SoundFlow", SttFixes.apply("打開搜得否", fixes))
        assertEquals("開啟Tailscale", SttFixes.apply("開啟TAIL SCALE", SttFixes.parse("tail scale=Tailscale")))
    }
}

class CloudVoiceStreamTest {
    @Test fun parsesChunksAndKeepsEvenBytes() {
        val p = tw.hoonsor.voicehub.voice.CloudVoice.StreamParser()
        // 3 bytes：先送 2 個、留 1 個給下一段
        assertEquals(2, p.feed("data: {\"data\":{\"audio\":\"0a0b0c\",\"status\":1},\"base_resp\":{\"status_code\":0}}")!!.size)
        val next = p.feed("{\"data\":{\"audio\":\"0d\",\"status\":1}}")!!
        assertEquals(listOf<Byte>(0x0c, 0x0d), next.toList())
        // 最後一段（status 2）不放音，避免整段重播
        assertNull(p.feed("data: {\"data\":{\"audio\":\"0102\",\"status\":2}}"))
        assertNull(p.feed(""))
        assertNull(p.feed("event: ping"))
    }

    @Test fun reportsApiError() {
        val p = tw.hoonsor.voicehub.voice.CloudVoice.StreamParser()
        assertNull(p.feed("{\"base_resp\":{\"status_code\":2056,\"status_msg\":\"quota\"}}"))
        assertTrue(p.error!!.contains("2056"))
    }
}
