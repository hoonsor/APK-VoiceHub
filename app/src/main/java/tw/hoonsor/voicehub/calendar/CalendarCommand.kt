package tw.hoonsor.voicehub.calendar

import tw.hoonsor.voicehub.agent.ChineseNumber
import tw.hoonsor.voicehub.agent.OfflineParser
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 用說的新增、改時間、刪除行程：句子只在手機上用規則解析，不送 AI（標題可能有人名或公務內容）。
 * 解析不出來的回 [Unclear] 請倬換個說法；不像行事曆指令的回 null，交給一般流程。
 * 純邏輯，有 JVM 測試（CalendarCommandTest）。
 */
object CalendarCommand {
    sealed interface Cmd

    /** [start] 為 null＝整天；[end] 為 null＝沒說結束時間（預設一小時） */
    data class Add(
        val title: String,
        val date: LocalDate,
        val start: LocalTime?,
        val end: LocalTime?,
        val location: String = "",
        val calendar: String? = null,
    ) : Cmd

    /** [onDate] 為 null＝沒說是哪天的，從今天往後找；[newDate]、[newTime] 至少有一個 */
    data class Move(val query: String, val onDate: LocalDate?, val newDate: LocalDate?, val newTime: LocalTime?) : Cmd

    data class Delete(val query: String, val onDate: LocalDate?) : Cmd

    data class Unclear(val message: String) : Cmd

    data class Hit<T>(val value: T, val range: IntRange)
    data class DateHit(val date: LocalDate, val period: String?)
    data class TimeHit(val start: LocalTime, val end: LocalTime?)

    private const val N = "(?:\\d{1,2}|[零〇一二兩三四五六七八九十]{1,3})"

    /** 這些字出現時是別的功能（鬧鐘、待辦、設定…），不當成行事曆指令 */
    private val blocked = Regex("鬧鐘|計時|音量|亮度|派工|待辦|購物|清單|聯絡人|訂閱|通知|靜音|播放|音樂|導航|簡訊|語言|密碼|設定|字體|桌布")
    private val question = Regex("什麼|哪些|哪個|有沒有|幾個|嗎$|呢$")
    private val calWord = Regex("行事曆|日曆|行程|活動")

    private val relDay = Regex("大後天|後天|明天|明日|今天|今日|今晚|明晚|今早|明早")
    private val weekdayRe = Regex("(下下|下|這|本)?(?:個)?(?:星期|禮拜|週|周)([一二三四五六日天])")
    private val monthDay = Regex("($N)月($N)(?:日|號)")
    private val dayOnly = Regex("(?<![月\\d零〇一二兩三四五六七八九十])($N)(?:日|號)")

    /** 分鐘：「半」「一刻」、帶「分」的數字、兩位數；「三點二樓」的「二」不算分鐘 */
    private val timeRe = Regex("(凌晨|清晨|早上|上午|中午|下午|傍晚|晚上)?($N)(點鐘?|:|：)(半|一刻|三刻|${N}分|\\d{2}|[一二三四五六七八九十]{2,3})?")
    private val rangeSep = Regex("到|至|-|～|~|－")
    private val allDayRe = Regex("一整天|整天|全天")

    private val addPrefix = Regex(
        "^(?:請|麻煩)?(?:幫我|幫忙|替我)?(?:在|到|往)?(?:我的)?(?:行事曆|日曆)?(?:裡面|裡|上)?" +
            "(?:新增|加入|加上|加|排入|排|記下|記|建立|安排)(?:一個|一筆|個)?(?:新的)?(?:行程|活動|事件)?",
    )
    private val addSuffix = Regex("(?:加|新增|排|放|記|寫)(?:到|進|在)(?:我的)?(?:行事曆|日曆)(?:裡面|裡|上|中)?$")
    private val moveRe = Regex("^(?:請|麻煩)?(?:幫我)?(?:把|將)?(.+?)(?:改到|改成|改為|改在|移到|挪到|延到|延後到|順延到|提前到|調到|換到)(.+)$")
    private val delBa = Regex("^(?:請|麻煩)?(?:幫我)?(?:把|將)(.+?)(?:取消|刪除|刪掉|刪了|刪|移除)(?:掉|了)*$")
    private val delVerb = Regex("^(?:請|麻煩)?(?:幫我)?(?:取消|刪除|刪掉|移除)掉?(.+)$")

    fun mentionsCalendar(raw: String) = calWord.containsMatchIn(OfflineParser.normalize(raw))

    /** [calendars]＝可寫入的行事曆名稱，用來認出「加到學校行事曆」 */
    fun parse(raw: String, now: LocalDateTime, calendars: List<String> = emptyList()): Cmd? {
        val t = OfflineParser.normalize(raw)
        if (t.isEmpty() || t.length > 50 || blocked.containsMatchIn(t) || question.containsMatchIn(t)) return null
        val today = now.toLocalDate()
        (delBa.find(t) ?: delVerb.find(t))?.let { return parseDelete(it.groupValues[1], today) }
        moveRe.find(t)?.let { m -> parseMove(m.groupValues[1], m.groupValues[2], today)?.let { return it } }
        if (!calWord.containsMatchIn(t)) return null
        return parseAdd(t, now, calendars)
    }

    private fun parseAdd(t: String, now: LocalDateTime, calendars: List<String>): Cmd? {
        var s = t
        var calendar: String? = null
        for (name in calendars.filter { it.length >= 2 }.sortedByDescending { it.length }) {
            if ((name.endsWith("行事曆") || name.endsWith("日曆")) && s.contains(name)) {
                s = s.replaceFirst(name, "行事曆"); calendar = name; break
            }
            val m = Regex(Regex.escape(name) + "的?(?=行事曆|日曆)").find(s)
            if (m != null) { s = s.removeRange(m.range); calendar = name; break }
        }
        val date = findDate(s, now.toLocalDate())?.also { s = s.removeRange(it.range) }
        val allDay = allDayRe.find(s)?.also { s = s.removeRange(it.range) } != null
        val time = findTime(s, date?.value?.period)?.also { s = s.removeRange(it.range) }

        val suffix = if (s.startsWith("把")) addSuffix.find(s) else null
        if (suffix != null) {
            s = s.removeRange(suffix.range).drop(1)
        } else {
            val prefix = addPrefix.find(s)?.takeIf { it.range.first == 0 } ?: return null
            s = s.removeRange(prefix.range)
        }

        var location = ""
        Regex("^(.+?)(?:地點在|地點是|地點|在|於)(.{1,15})$").find(s)?.let { m ->
            s = m.groupValues[1]; location = m.groupValues[2]
        } ?: Regex("^(?:在|於)(.{1,10}?)(開|舉行|舉辦|辦|上|參加)(.+)$").find(s)?.let { m ->
            location = m.groupValues[1]
            s = (if (m.groupValues[2] in setOf("上", "參加")) m.groupValues[2] else "") + m.groupValues[3]
        }
        val title = cleanTitle(s)

        val day = date?.value?.date ?: time?.value?.let { if (it.start.isBefore(now.toLocalTime())) now.toLocalDate().plusDays(1) else now.toLocalDate() }
        if (title.isEmpty()) return Unclear("要新增什麼活動？請連日期和時間一起說，例如「行事曆新增明天下午兩點考績會」。")
        if (day == null) return Unclear("「$title」要排在哪一天、幾點？例如「行事曆新增明天下午兩點$title」。")
        val start = if (allDay) null else time?.value?.start
        return Add(title.take(40), day, start, if (start == null) null else time?.value?.end, location, calendar)
    }

    private fun parseMove(left: String, right: String, today: LocalDate): Cmd? {
        var l = left
        val onDate = findDate(l, today)?.also { l = l.removeRange(it.range) }
        findTime(l, null)?.let { l = l.removeRange(it.range) }
        val query = cleanQuery(l)
        if (query.length < 2) return null

        var r = right
        val newDate = findDate(r, today)?.also { r = r.removeRange(it.range) }
        val newTime = findTime(r, newDate?.value?.period)?.also { r = r.removeRange(it.range) }
        r = r.replace(Regex("^(?:的|在)"), "").replace(Regex("(?:去|吧|好了|開始|舉行|開會|那天|的時候)$"), "")
        if (r.isNotEmpty() || (newDate == null && newTime == null)) return null
        return Move(query, onDate?.value?.date, newDate?.value?.date, newTime?.value?.start)
    }

    private fun parseDelete(target: String, today: LocalDate): Cmd? {
        var s = target
        val onDate = findDate(s, today)?.also { s = s.removeRange(it.range) }
        findTime(s, null)?.let { s = s.removeRange(it.range) }
        val query = cleanQuery(s)
        if (query.length < 2) return null
        return Delete(query, onDate?.value?.date)
    }

    private fun cleanTitle(s: String): String {
        var x = s.replace(Regex("^(?:的|要|有|去)+"), "").replace(Regex("(?:的)?(?:行程|活動)$"), "").replace(Regex("的$"), "")
        if (x.startsWith("開") && x.length >= 3 && x.contains("會")) x = x.drop(1)
        return x
    }

    private fun cleanQuery(s: String): String =
        s.replace(Regex("^(?:(?:行事曆|日曆)(?:裡面|裡|上)?)?(?:的)?"), "")
            .replace(Regex("(?:的)?(?:行程|活動|事件|時間)$"), "")
            .replace(Regex("^的|的$"), "")

    // ── 日期與時間 ──

    /** 「下週三」＝下一週（從週一算起）的星期三；沒說「下」就是最近的那一天（今天也算） */
    fun weekdayOffset(prefix: String, target: Int, current: Int): Int = when (prefix) {
        "下" -> 7 - current + target
        "下下" -> 14 - current + target
        else -> (target - current + 7) % 7
    }

    fun findDate(t: String, today: LocalDate): Hit<DateHit>? {
        monthDay.find(t)?.let { m ->
            val mo = ChineseNumber.parse(m.groupValues[1]) ?: return@let
            val d = ChineseNumber.parse(m.groupValues[2]) ?: return@let
            val date = runCatching { LocalDate.of(today.year, mo, d) }.getOrNull() ?: return@let
            return Hit(DateHit(if (date.isBefore(today)) date.plusYears(1) else date, null), m.range)
        }
        weekdayRe.find(t)?.let { m ->
            val target = "一二三四五六日天".indexOf(m.groupValues[2]).let { if (it == 7) 7 else it + 1 }
            val off = weekdayOffset(m.groupValues[1], target, today.dayOfWeek.value)
            return Hit(DateHit(today.plusDays(off.toLong()), null), m.range)
        }
        relDay.find(t)?.let { m ->
            val (off, period) = when (m.value) {
                "大後天" -> 3 to null
                "後天" -> 2 to null
                "明天", "明日" -> 1 to null
                "明晚" -> 1 to "晚上"
                "明早" -> 1 to "早上"
                "今晚" -> 0 to "晚上"
                "今早" -> 0 to "早上"
                else -> 0 to null
            }
            return Hit(DateHit(today.plusDays(off.toLong()), period), m.range)
        }
        dayOnly.find(t)?.let { m ->
            val d = ChineseNumber.parse(m.groupValues[1]) ?: return@let
            val date = runCatching { today.withDayOfMonth(d) }.getOrNull() ?: return@let
            val fixed = if (date.isBefore(today)) runCatching { today.plusMonths(1).withDayOfMonth(d) }.getOrNull() else date
            return fixed?.let { Hit(DateHit(it, null), m.range) }
        }
        return null
    }

    /** 「下午兩點半到四點」；沒說上下午時 1～6 點當下午 */
    fun findTime(t: String, periodHint: String?): Hit<TimeHit>? {
        val m1 = timeRe.find(t) ?: return null
        val p1 = m1.groupValues[1].ifEmpty { periodHint }
        val start = toTime(m1, p1) ?: return null
        var last = m1.range.last
        var end: LocalTime? = null
        val sep = rangeSep.find(t, last + 1)?.takeIf { it.range.first == last + 1 }
        if (sep != null) {
            val m2 = timeRe.find(t, sep.range.last + 1)?.takeIf { it.range.first == sep.range.last + 1 }
            if (m2 != null) {
                var e = toTime(m2, m2.groupValues[1].ifEmpty { p1 })
                if (e != null && !e.isAfter(start) && e.hour < 12) e = e.plusHours(12)
                if (e != null && e.isAfter(start)) end = e
                last = m2.range.last
            }
        }
        return Hit(TimeHit(start, end), m1.range.first..last)
    }

    private fun toTime(m: MatchResult, period: String?): LocalTime? {
        val h = ChineseNumber.parse(m.groupValues[2]) ?: return null
        if (h > 24) return null
        val minute = when (val g = m.groupValues[4]) {
            "" -> 0
            "半" -> 30
            "一刻" -> 15
            "三刻" -> 45
            else -> ChineseNumber.parse(g.removeSuffix("分")) ?: return null
        }
        if (minute > 59) return null
        val hour = when (period) {
            "凌晨", "清晨" -> if (h == 12) 0 else h
            "早上", "上午" -> h
            "中午" -> if (h in 1..2) h + 12 else h
            "下午", "傍晚", "晚上" -> if (h < 12) h + 12 else h
            else -> if (h in 1..6) h + 12 else h
        } % 24
        return LocalTime.of(hour, minute)
    }

    // ── 確認與回報的口語 ──

    fun whenText(date: LocalDate, today: LocalDate, start: LocalTime?, end: LocalTime? = null): String {
        val day = (CalendarBrief.dayWord(date, today) + CalendarBrief.dateWord(date)).trim()
        return day + when {
            start == null -> "整天"
            end == null -> CalendarBrief.spokenTime(start)
            else -> CalendarBrief.spokenRange(start, end)
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[\\s，。！？、,.!?：:（）()「」]"), "")

    /** 說的名稱和行程標題互相包含就算符合（「考績會」對得到「114 學年度考績會」） */
    fun matches(title: String, query: String): Boolean {
        val a = norm(title)
        val b = norm(query)
        if (a.isEmpty() || b.isEmpty()) return false
        return a.contains(b) || (a.length >= 2 && b.contains(a))
    }

    fun sameTitle(title: String, query: String) = norm(title) == norm(query)
}
