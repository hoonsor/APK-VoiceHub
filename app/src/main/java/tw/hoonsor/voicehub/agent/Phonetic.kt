package tw.hoonsor.voicehub.agent

/**
 * 依發音比對英文名稱：Whisper 常把 Tailscale 聽成「泰爾斯凱爾」「tail skill」這類近似音。
 * 做法：中文先轉拼音（[romanize]，APP 啟動時接上 Android ICU；JVM 測試用假的），
 * 再取「子音骨架」並把容易混的音歸成同一類（l/r、k/g/c/q、s/z/x/j、b/p、d/t、f/v），比骨架是否相同。
 * 純邏輯，有 JVM 測試（PhoneticTest）。
 */
object Phonetic {
    /** 中文 → 拼音（不含聲調）；預設原樣回傳（JVM 測試、ICU 不可用時） */
    @Volatile var romanize: (String) -> String = { it }

    private val classes = mapOf(
        'l' to 'l', 'r' to 'l',
        'k' to 'k', 'g' to 'k', 'c' to 'k', 'q' to 'k',
        's' to 's', 'z' to 's', 'x' to 's', 'j' to 's',
        'b' to 'p', 'p' to 'p',
        'd' to 't', 't' to 't',
        'f' to 'f', 'v' to 'f',
        'm' to 'm', 'n' to 'n',
    )

    /** 子音骨架：去掉母音、h、w、y，連續相同的類別只留一個 */
    fun skeleton(s: String): String {
        val latin = romanize(s).lowercase().filter { it in 'a'..'z' }
        val sb = StringBuilder()
        for (c in latin) {
            val k = classes[c] ?: continue
            if (sb.isEmpty() || sb.last() != k) sb.append(k)
        }
        return sb.toString()
    }

    fun hasLatin(s: String) = s.any { it in 'a'..'z' || it in 'A'..'Z' }

    /** 0＝不像；80＝骨架相同；72＝骨架差一個音（夠長才算） */
    fun score(query: String, label: String): Int {
        if (!hasLatin(label)) return 0
        val q = skeleton(query)
        val l = skeleton(label)
        if (q.length < 3 || l.length < 3) return 0
        if (q == l) return 80
        if (minOf(q.length, l.length) >= 4 && editDistance(q, l) <= 1) return 72
        return 0
    }

    fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}

/** 聽錯對照表：每行「聽成=正確」，轉文字後先替換（不分大小寫）。純邏輯，有 JVM 測試。 */
object SttFixes {
    fun parse(text: String): List<Pair<String, String>> = text.lines().mapNotNull { line ->
        val i = line.indexOf('=').takeIf { it > 0 } ?: line.indexOf('＝').takeIf { it > 0 } ?: return@mapNotNull null
        val from = line.substring(0, i).trim()
        val to = line.substring(i + 1).trim()
        if (from.isEmpty() || to.isEmpty()) null else from to to
    }.sortedByDescending { it.first.length } // 長的先換，避免短詞先吃掉

    fun apply(transcript: String, fixes: List<Pair<String, String>>): String =
        fixes.fold(transcript) { acc, (from, to) -> acc.replace(from, to, ignoreCase = true) }
}
