package tw.hoonsor.voicehub.agent

/**
 * 用名稱找 APP：完全相同 > 開頭相同 > 包含 > 字元重疊。
 * 純邏輯，有 JVM 測試（AppMatcherTest）。
 */
object AppMatcher {

    data class Entry(val label: String, val packageName: String)

    /** 常見口語別名（說的 → APP 名稱裡會出現的字） */
    private val aliases = mapOf(
        "相機" to listOf("相機", "Camera", "camera"),
        "照相" to listOf("相機", "Camera"),
        "相簿" to listOf("相簿", "相冊", "圖庫", "Photos", "照片"),
        "照片" to listOf("相簿", "相冊", "圖庫", "Photos", "照片"),
        "設定" to listOf("設定", "設置", "Settings"),
        "計算機" to listOf("計算機", "計算器", "Calculator"),
        "時鐘" to listOf("時鐘", "Clock"),
        "地圖" to listOf("地圖", "Maps"),
        "瀏覽器" to listOf("Chrome", "瀏覽器", "Browser"),
        "油管" to listOf("YouTube"),
        "臉書" to listOf("Facebook"),
        "賴" to listOf("LINE"),
    )

    fun normalize(s: String): String = s.lowercase()
        .replace(Regex("[\\s・·._\\-]+"), "")
        .removeSuffix("app")
        .removeSuffix("應用程式")

    fun score(query: String, label: String): Int {
        val q = normalize(query)
        val l = normalize(label)
        if (q.isEmpty() || l.isEmpty()) return 0
        return when {
            q == l -> 100
            l.startsWith(q) -> 85
            q.startsWith(l) && l.length >= 2 -> 75
            l.contains(q) -> 70
            q.contains(l) && l.length >= 2 -> 60
            else -> {
                val overlap = q.toSet().intersect(l.toSet()).size
                val ratio = overlap.toDouble() / maxOf(q.toSet().size, 1)
                if (ratio >= 0.6 && overlap >= 2) (ratio * 50).toInt() else 0
            }
        }
    }

    /** 回傳依分數排序的候選（分數 > 0） */
    fun rank(query: String, entries: List<Entry>): List<Pair<Entry, Int>> {
        val queries = listOf(query) + (aliases[query.trim()] ?: emptyList())
        return entries.map { e -> e to queries.maxOf { score(it, e.label) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
    }

    sealed interface Match {
        data class One(val entry: Entry) : Match
        data class Many(val candidates: List<Entry>) : Match
        data object None : Match
    }

    fun match(query: String, entries: List<Entry>): Match {
        val ranked = rank(query, entries)
        if (ranked.isEmpty()) return Match.None
        val best = ranked.first().second
        val top = ranked.filter { it.second == best }.map { it.first }.distinctBy { it.packageName }
        return if (top.size == 1) Match.One(top.first()) else Match.Many(top.take(5))
    }
}
