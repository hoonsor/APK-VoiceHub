package tw.hoonsor.voicehub.voice

import java.util.Locale

/** 一個可用的朗讀聲音（從 TextToSpeech.voices 轉來，與 Android 類別脫鉤以便測試） */
data class VoiceInfo(
    /** 系統的聲音名稱，例如 cmn-tw-x-ctc-local；存進設定的就是這個 */
    val name: String,
    /** BCP-47，例如 zh-TW */
    val localeTag: String,
    /** Voice.QUALITY_*：100～500，越大越好 */
    val quality: Int = 300,
    val network: Boolean = false,
    val notInstalled: Boolean = false,
)

data class VoiceOption(val info: VoiceInfo, val label: String, val detail: String)

/**
 * 設定頁「朗讀聲音」清單：只列中文、已安裝的聲音，台灣中文排最前面，
 * 本機（離線）優先、品質高的優先。純邏輯，有 JVM 測試（VoicesTest）。
 */
object VoiceCatalog {

    fun localeLabel(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        val lang = l.language.lowercase()
        val country = l.country.uppercase()
        return when {
            lang == "yue" || (lang == "zh" && country == "HK") -> "粵語（香港）"
            lang == "zh" && country == "TW" -> "中文（台灣）"
            lang == "cmn" && country == "TW" -> "中文（台灣）"
            lang == "zh" && country == "CN" -> "中文（中國）"
            lang == "cmn" -> "中文（中國）"
            lang == "zh" -> "中文"
            else -> l.getDisplayName(Locale.TAIWAN)
        }
    }

    private fun isChinese(tag: String): Boolean {
        val lang = Locale.forLanguageTag(tag).language.lowercase()
        return lang == "zh" || lang == "cmn" || lang == "yue"
    }

    private fun rank(tag: String): Int = when (localeLabel(tag)) {
        "中文（台灣）" -> 0
        "中文（中國）", "中文" -> 1
        else -> 2
    }

    /** 從系統名稱取出可辨識的短代號（Google：cmn-tw-x-ctc-local → ctc） */
    fun shortCode(name: String): String {
        Regex("-x-([a-z0-9]+)").find(name.lowercase())?.let { return it.groupValues[1] }
        return name.substringAfterLast('#').substringAfterLast('.').takeLast(12)
    }

    fun qualityLabel(q: Int): String = when {
        q >= 400 -> "高品質"
        q >= 300 -> "一般品質"
        else -> "較低品質"
    }

    fun options(voices: List<VoiceInfo>): List<VoiceOption> {
        val usable = voices.filter { isChinese(it.localeTag) && !it.notInstalled }
            .distinctBy { it.name }
            .sortedWith(compareBy<VoiceInfo> { rank(it.localeTag) }.thenBy { it.network }.thenByDescending { it.quality }.thenBy { it.name })
        val counters = mutableMapOf<String, Int>()
        return usable.map { v ->
            val loc = localeLabel(v.localeTag)
            val n = (counters[loc] ?: 0) + 1
            counters[loc] = n
            val detail = listOfNotNull(qualityLabel(v.quality), if (v.network) "需連網" else "離線可用", shortCode(v.name)).joinToString(" · ")
            VoiceOption(v, "$loc 聲音 $n", detail)
        }
    }
}
