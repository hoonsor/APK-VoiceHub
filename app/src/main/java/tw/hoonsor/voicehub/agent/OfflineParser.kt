package tw.hoonsor.voicehub.agent

import org.json.JSONObject

/**
 * AI 連不上時的離線備援：只認幾種固定句型，轉成內建系統動作。
 * 純邏輯，有 JVM 測試（OfflineParserTest）。
 */
object OfflineParser {

    data class Call(val action: String, val params: JSONObject)

    private val affirmative = Regex("^(是|對|好|要|確定|確認|執行|可以|沒問題|ok|okay|yes|嗯|對啊|好啊|好的|就這樣)")
    private val negative = Regex("^(不|否|取消|算了|不要|別|停|no|先不)")

    fun isAffirmative(text: String): Boolean = affirmative.containsMatchIn(normalize(text)) && !isNegative(text)
    fun isNegative(text: String): Boolean = negative.containsMatchIn(normalize(text))

    fun parse(raw: String): Call? {
        val t = normalize(raw)
        if (t.isEmpty()) return null

        if ("手電筒" in t) {
            val off = Regex("關|熄|不要").containsMatchIn(t)
            return Call("flashlight_set", JSONObject().put("on", !off))
        }

        mediaCommand(t)?.let { return Call("media_control", JSONObject().put("command", it)) }

        if (Regex("計時|倒數|後提醒|後叫我").containsMatchIn(t)) {
            durationSeconds(t)?.let { return Call("timer_set", JSONObject().put("seconds", it)) }
        }

        if (Regex("鬧鐘|叫我|叫醒").containsMatchIn(t)) {
            clockTime(t)?.let { (h, m) -> return Call("alarm_set", JSONObject().put("hour", h).put("minute", m)) }
        }

        Regex("^(?:打電話給|打給|撥電話給|撥給|撥打|撥)(.+)$").find(t)?.let { m ->
            val who = m.groupValues[1].trim()
            val digits = who.filter { it.isDigit() || it == '+' }
            return if (digits.length >= 3 && digits.length >= who.length - 1) {
                Call("phone_dial", JSONObject().put("number", digits))
            } else {
                Call("phone_dial", JSONObject().put("contact", who))
            }
        }

        Regex("^(?:幫我|請)?(?:打開|開啟|啟動|開)(.+?)(?:APP|app|應用程式)?$").find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotEmpty()) return Call("app_open", JSONObject().put("name", name))
        }

        Regex("^(?:搜尋|查一下|查詢|google|Google)(.+)$").find(t)?.let { m ->
            return Call("web_search", JSONObject().put("query", m.groupValues[1].trim()))
        }

        if (Regex("電量|多少電|還有電|充電").containsMatchIn(t)) return Call("battery_status", JSONObject())

        return null
    }

    private fun mediaCommand(t: String): String? = when {
        Regex("下一首|下一個|跳過這首").containsMatchIn(t) -> "next"
        Regex("上一首|上一個").containsMatchIn(t) -> "previous"
        Regex("^(暫停|停止播放|音樂暫停|暫停音樂|暫停播放)").containsMatchIn(t) -> "pause"
        Regex("^(繼續播放|播放音樂|繼續放|放音樂)").containsMatchIn(t) -> "play"
        else -> null
    }

    /** 「五分鐘」「1小時30分」「九十秒」「半小時」→ 秒數 */
    fun durationSeconds(t: String): Int? {
        var total = 0
        var found = false
        if ("半小時" in t || "半個小時" in t) { total += 1800; found = true }
        Regex("([0-9零〇一二兩三四五六七八九十百]+)個?(小時|鐘頭)(半)?").find(t)?.let {
            total += (ChineseNumber.parse(it.groupValues[1]) ?: 0) * 3600 + if (it.groupValues[3].isNotEmpty()) 1800 else 0
            found = true
        }
        Regex("([0-9零〇一二兩三四五六七八九十百]+)分(鐘)?(半)?").find(t)?.let {
            total += (ChineseNumber.parse(it.groupValues[1]) ?: 0) * 60 + if (it.groupValues[3].isNotEmpty()) 30 else 0
            found = true
        }
        Regex("([0-9零〇一二兩三四五六七八九十百]+)秒").find(t)?.let {
            total += ChineseNumber.parse(it.groupValues[1]) ?: 0
            found = true
        }
        return if (found && total > 0) total else null
    }

    /** 「早上七點半」「下午3點20分」「21:05」「晚上十點」→ (時, 分)，24 小時制 */
    fun clockTime(t: String): Pair<Int, Int>? {
        Regex("([0-9]{1,2})[:：]([0-9]{2})").find(t)?.let {
            val h = it.groupValues[1].toInt()
            val m = it.groupValues[2].toInt()
            if (h in 0..23 && m in 0..59) return adjust(t, h) to m
        }
        val m = Regex("([0-9零〇一二兩三四五六七八九十]+)[點点](半|一刻|三刻|([0-9零〇一二兩三四五六七八九十]+)分?)?").find(t) ?: return null
        val h = ChineseNumber.parse(m.groupValues[1]) ?: return null
        val minute = when (val tail = m.groupValues[2]) {
            "" -> 0
            "半" -> 30
            "一刻" -> 15
            "三刻" -> 45
            else -> ChineseNumber.parse(tail.removeSuffix("分")) ?: 0
        }
        if (h !in 0..24 || minute !in 0..59) return null
        return (adjust(t, h) % 24) to minute
    }

    private fun adjust(t: String, h: Int): Int = when {
        Regex("下午|晚上|傍晚|今晚").containsMatchIn(t) && h in 1..11 -> h + 12
        Regex("中午").containsMatchIn(t) && h in 1..2 -> h + 12
        Regex("凌晨|半夜").containsMatchIn(t) && h == 12 -> 0
        else -> h
    }

    fun normalize(s: String): String = s.trim()
        .replace(Regex("[，。！？、,.!?\\s]+"), "")
        .replace("一下", "")
}

/** 中文數字（含阿拉伯數字）→ Int，支援到 9999 */
object ChineseNumber {
    private val digit = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '兩' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    private val unit = mapOf('十' to 10, '百' to 100, '千' to 1000)

    fun parse(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty()) return null
        if (t.all { it.isDigit() }) return t.toIntOrNull()
        var total = 0
        var current = 0
        var seen = false
        for (c in t) {
            when {
                c in digit -> { current = digit.getValue(c); seen = true }
                c in unit -> {
                    total += (if (current == 0) 1 else current) * unit.getValue(c)
                    current = 0
                    seen = true
                }
                c.isDigit() -> { current = current * 10 + (c - '0'); seen = true }
                else -> return null
            }
        }
        return if (seen) total + current else null
    }
}
