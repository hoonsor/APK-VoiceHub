package tw.hoonsor.voicehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.hoonsor.voicehub.voice.VoiceCatalog
import tw.hoonsor.voicehub.voice.VoiceInfo

class VoicesTest {
    // 依 Google 語音服務實際的命名方式
    private val voices = listOf(
        VoiceInfo("cmn-cn-x-ccc-local", "zh-CN", 400),
        VoiceInfo("cmn-tw-x-ctd-network", "zh-TW", 400, network = true),
        VoiceInfo("cmn-tw-x-ctc-local", "zh-TW", 400),
        VoiceInfo("cmn-tw-x-cte-local", "zh-TW", 300),
        VoiceInfo("yue-hk-x-yuc-local", "yue-HK", 400),
        VoiceInfo("en-us-x-tpf-local", "en-US", 500),
        VoiceInfo("cmn-tw-x-ctf-local", "zh-TW", 400, notInstalled = true),
    )

    @Test fun onlyInstalledChinese() {
        val names = VoiceCatalog.options(voices).map { it.info.name }
        assertTrue("en-us-x-tpf-local" !in names)
        assertTrue("cmn-tw-x-ctf-local" !in names)
        assertEquals(5, names.size)
    }

    @Test fun taiwanFirstLocalBeforeNetworkHigherQualityFirst() {
        val names = VoiceCatalog.options(voices).map { it.info.name }
        assertEquals(listOf("cmn-tw-x-ctc-local", "cmn-tw-x-cte-local", "cmn-tw-x-ctd-network", "cmn-cn-x-ccc-local", "yue-hk-x-yuc-local"), names)
    }

    @Test fun labels() {
        val o = VoiceCatalog.options(voices)
        assertEquals("中文（台灣） 聲音 1", o[0].label)
        assertEquals("中文（台灣） 聲音 3", o[2].label)
        assertEquals("中文（中國） 聲音 1", o[3].label)
        assertEquals("粵語（香港） 聲音 1", o[4].label)
        assertEquals("高品質 · 離線可用 · ctc", o[0].detail)
        assertEquals("高品質 · 需連網 · ctd", o[2].detail)
    }

    @Test fun localeNames() {
        assertEquals("中文（台灣）", VoiceCatalog.localeLabel("zh-TW"))
        assertEquals("中文（台灣）", VoiceCatalog.localeLabel("zh-Hant-TW"))
        assertEquals("粵語（香港）", VoiceCatalog.localeLabel("zh-HK"))
    }
}
