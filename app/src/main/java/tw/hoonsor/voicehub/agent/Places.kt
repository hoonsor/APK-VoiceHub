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

    /**
     * 「去涮金鍋」「導航到台北車站」「騎車去學校」這種只講地點的句子，回傳地點，讓 APP 不經 AI 直接導航。
     * 「去」後面接的是動作（去買牛奶、去繳費、去接小孩）、有提醒或時間的字眼、或是問句，都回 null 交給 AI。
     */
    fun goTo(utterance: String): String? {
        val t = utterance.replace(Regex("[\\s，。！？!?、,.～~]"), "")
            .replace(Regex("^(請|幫我|我要|我想|那就)+"), "")
            .replace(Regex("(吧|囉|喔|了)$"), "")
        if (Regex("提醒|待辦|記得|記一下|明天|今天|後天|下週|下禮拜|點|分鐘|小時").containsMatchIn(t)) return null
        val m = Regex("^(?:導航|帶我)?(?:騎車|騎機車)?(?:去|到|前往)(.+)$").find(t)
            ?: Regex("^導航(.+)$").find(t)
            ?: return null
        val place = m.groupValues[1]
        if (place.length < 2 || place.length > 20) return null
        if (Regex("^(年|過|哪|不|掉|除|死|吧|嗎|買|拿|繳|看|找|接|送|辦|寄|領|洗|剪|修|換|問|跟|和|把|幫|做|弄|上班|上課|上學|睡|吃飯|散步|運動|走走|逛逛|玩)").containsMatchIn(place)) return null
        return place
    }

    /** 「回家」「我家」「家裡」都視為「家」；「去公司」「到學校」去掉動詞 */
    fun key(raw: String): String = raw.trim()
        .removePrefix("導航")
        .replace(Regex("^(回到|回去|回|去|到|我的|我)"), "")
        .removeSuffix("裡")
        .trim()
}
