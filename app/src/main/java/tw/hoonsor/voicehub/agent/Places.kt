package tw.hoonsor.voicehub.agent

/**
 * 常用地點：倬在設定頁寫「家=某地標」，說「回家」「導航回家」就換成那個地點。
 * 實際地點只存在手機上，AI 只會看到名稱（家、公司），不會看到地址。純邏輯，有 JVM 測試。
 */
object Places {
    /** 「家=草藥園丁」每行一個；也接受全形＝與冒號 */
    fun parse(text: String): Map<String, String> = text.lines()
        .mapNotNull { line ->
            val parts = line.split('=', '＝', ':', '：', limit = 2)
            if (parts.size < 2) return@mapNotNull null
            val name = key(parts[0])
            val place = parts[1].trim()
            if (name.isEmpty() || place.isEmpty()) null else name to place
        }
        .toMap()

    /** 目的地是常用地點名稱就換成實際地點，否則原樣回傳 null */
    fun resolve(destination: String, places: Map<String, String>): String? = places[key(destination)]

    /**
     * 整句話就只是「回家」「導航去公司」這類常用地點時，回傳地點名稱，讓 APP 不經 AI 直接導航（快很多）。
     * 有講交通方式或其他內容（「開車回家」「回家路上買牛奶」）回 null，照常交給 AI。
     */
    fun shortcut(utterance: String, places: Map<String, String>): String? {
        if (places.isEmpty()) return null
        val t = utterance.replace(Regex("[\\s，。！？!?、,.～~]"), "")
            .replace(Regex("^(請|幫我|我要|我想)+"), "")
            .replace(Regex("^(導航|帶我)"), "")
            .replace(Regex("(吧|囉|喔|了)$"), "")
        val k = key(t)
        return k.takeIf { it in places }
    }

    /** 「回家」「我家」「家裡」都視為「家」；「去公司」「到學校」去掉動詞 */
    fun key(raw: String): String = raw.trim()
        .removePrefix("導航")
        .replace(Regex("^(回到|回去|回|去|到|我的|我)"), "")
        .removeSuffix("裡")
        .trim()
}
