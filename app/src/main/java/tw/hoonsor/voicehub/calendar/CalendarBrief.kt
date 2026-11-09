package tw.hoonsor.voicehub.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 一個行程（已換成手機時區）。整天的行程 start＝當天 00:00、end＝結束那天的 00:00 */
data class CalEvent(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val location: String = "",
)

/**
 * 把某一天的行程整理成口語與通知文字。全部在手機上做，不送 AI（行事曆可能有人名、會議內容）。
 * 純邏輯，有 JVM 測試（CalendarBriefTest）。
 */
object CalendarBrief {
    data class Brief(val spoken: String, val lines: List<String>, val count: Int)

    const val MAX_SPOKEN = 8

    fun compose(date: LocalDate, today: LocalDate, events: List<CalEvent>): Brief {
        val head = "${dayWord(date, today)}${dateWord(date)}".trim()
        val list = clean(date, events)
        if (list.isEmpty()) return Brief("${head}沒有行程。", emptyList(), 0)

        val spokenItems = list.take(MAX_SPOKEN).map { spokenItem(date, it) }
        val more = list.size - spokenItems.size
        val spoken = buildString {
            append(head).append("，共有 ").append(list.size).append(" 個行程。")
            spokenItems.forEach { append(it).append("。") }
            if (more > 0) append("另外還有 ").append(more).append(" 個，請看行事曆。")
        }
        return Brief(spoken, list.map { lineItem(date, it) }, list.size)
    }

    /** 去掉重複（同一個活動出現在好幾個共享行事曆）、只留這一天的、整天的排前面 */
    fun clean(date: LocalDate, events: List<CalEvent>): List<CalEvent> {
        val dayStart = date.atStartOfDay()
        val dayEnd = date.plusDays(1).atStartOfDay()
        return events
            .map { it.copy(title = it.title.replace(Regex("\\s+"), " ").trim().ifEmpty { "未命名行程" }, location = it.location.replace(Regex("\\s+"), " ").trim()) }
            .filter { if (it.allDay) !date.isBefore(it.start.toLocalDate()) && date.isBefore(maxOf(it.end.toLocalDate(), it.start.toLocalDate().plusDays(1))) else it.start < dayEnd && it.end > dayStart || it.start == dayStart }
            .distinctBy { Triple(it.title, if (it.allDay) null else it.start, it.allDay) }
            .sortedWith(compareBy<CalEvent>({ !wholeDay(date, it) }, { it.start }))
    }

    private fun wholeDay(date: LocalDate, e: CalEvent) =
        e.allDay || (e.start <= date.atStartOfDay() && e.end >= date.plusDays(1).atStartOfDay())

    private fun spokenItem(date: LocalDate, e: CalEvent): String {
        val place = e.location.takeIf { it.isNotEmpty() && it.length <= 15 }?.let { "，在$it" }.orEmpty()
        val time = when {
            wholeDay(date, e) -> "整天"
            e.start.toLocalDate() < date -> "到${spokenTime(e.end.toLocalTime())}"
            e.end > e.start && e.end.toLocalDate() == date && e.end != e.start.plusHours(1) ->
                spokenRange(e.start.toLocalTime(), e.end.toLocalTime())
            else -> spokenTime(e.start.toLocalTime())
        }
        return "$time，${e.title}$place"
    }

    private fun lineItem(date: LocalDate, e: CalEvent): String {
        val time = when {
            wholeDay(date, e) -> "整天"
            e.start.toLocalDate() < date -> "～${hhmm(e.end.toLocalTime())}"
            e.end.toLocalDate() > date -> "${hhmm(e.start.toLocalTime())}～"
            else -> "${hhmm(e.start.toLocalTime())}–${hhmm(e.end.toLocalTime())}"
        }
        val place = e.location.takeIf { it.isNotEmpty() }?.let { "（$it）" }.orEmpty()
        return "$time　${e.title}$place"
    }

    private fun hhmm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

    fun period(h: Int) = when (h) {
        in 0..4 -> "凌晨"
        in 5..11 -> "早上"
        12 -> "中午"
        in 13..17 -> "下午"
        else -> "晚上"
    }

    /** 14:30 → 「下午 2 點半」 */
    fun spokenTime(t: LocalTime, withPeriod: Boolean = true): String {
        val h12 = when {
            t.hour == 0 -> 12
            t.hour > 12 -> t.hour - 12
            else -> t.hour
        }
        val min = when (t.minute) {
            0 -> "點"
            30 -> "點半"
            else -> "點 ${t.minute} 分"
        }
        return (if (withPeriod) period(t.hour) + " " else "") + "$h12 $min"
    }

    /** 同一個時段只講一次：「下午 2 點半到 4 點」「早上 10 點到下午 1 點」 */
    fun spokenRange(a: LocalTime, b: LocalTime): String =
        if (period(a.hour) != period(b.hour)) spokenTime(a) + "到" + spokenTime(b) else spokenTime(a) + "到 " + spokenTime(b, withPeriod = false)

    fun dayWord(date: LocalDate, today: LocalDate): String = when (date.toEpochDay() - today.toEpochDay()) {
        0L -> "今天"
        1L -> "明天"
        2L -> "後天"
        else -> ""
    }

    fun dateWord(date: LocalDate): String = " ${date.monthValue} 月 ${date.dayOfMonth} 日${weekday(date.dayOfWeek)}"

    fun weekday(d: DayOfWeek) = "星期" + "一二三四五六日"[d.value - 1]
}
