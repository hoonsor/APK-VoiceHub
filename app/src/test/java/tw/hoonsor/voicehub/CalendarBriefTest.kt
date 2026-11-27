package tw.hoonsor.voicehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.hoonsor.voicehub.calendar.CalEvent
import tw.hoonsor.voicehub.calendar.CalendarBrief
import tw.hoonsor.voicehub.calendar.CalendarTrigger
import tw.hoonsor.voicehub.calendar.DailyBrief
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class CalendarBriefTest {
    private val today = LocalDate.of(2026, 11, 9) // 星期一
    private val tomorrow = today.plusDays(1)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m)
    private fun ev(title: String, s: LocalDateTime, e: LocalDateTime, loc: String = "") = CalEvent(title, s, e, false, loc)
    private fun allDay(title: String, d: LocalDate, days: Long = 1) = CalEvent(title, d.atStartOfDay(), d.plusDays(days).atStartOfDay(), true)

    @Test fun spokenTimes() {
        assertEquals("早上 8 點", CalendarBrief.spokenTime(LocalTime.of(8, 0)))
        assertEquals("下午 2 點半", CalendarBrief.spokenTime(LocalTime.of(14, 30)))
        assertEquals("中午 12 點 15 分", CalendarBrief.spokenTime(LocalTime.of(12, 15)))
        assertEquals("凌晨 12 點", CalendarBrief.spokenTime(LocalTime.of(0, 0)))
        assertEquals("下午 2 點半到 4 點", CalendarBrief.spokenRange(LocalTime.of(14, 30), LocalTime.of(16, 0)))
        assertEquals("早上 10 點到下午 1 點", CalendarBrief.spokenRange(LocalTime.of(10, 0), LocalTime.of(13, 0)))
    }

    @Test fun emptyDay() {
        val b = CalendarBrief.compose(tomorrow, today, emptyList())
        assertEquals("明天 11 月 10 日星期二沒有行程。", b.spoken)
        assertEquals(0, b.count)
        assertEquals("11 月 17 日星期二沒有行程。", CalendarBrief.compose(today.plusDays(8), today, emptyList()).spoken)
    }

    @Test fun ordersAllDayFirstAndDedupes() {
        val b = CalendarBrief.compose(
            tomorrow, today,
            listOf(
                ev("考績會", at(tomorrow, 14, 30), at(tomorrow, 16)),
                ev("朝會", at(tomorrow, 8), at(tomorrow, 9), "禮堂"),
                ev("朝會", at(tomorrow, 8), at(tomorrow, 9), "禮堂"), // 同一個活動在兩個共享行事曆
                allDay("校慶", tomorrow),
            ),
        )
        assertEquals(3, b.count)
        assertEquals("明天 11 月 10 日星期二，共有 3 個行程。整天，校慶。早上 8 點，朝會，在禮堂。下午 2 點半到 4 點，考績會。", b.spoken)
        assertEquals(listOf("整天　校慶", "08:00–09:00　朝會（禮堂）", "14:30–16:00　考績會"), b.lines)
    }

    @Test fun keepsOnlyThatDay() {
        val b = CalendarBrief.compose(
            tomorrow, today,
            listOf(
                allDay("今天的整天活動", today), // 查詢範圍前後多抓一天，要篩掉
                allDay("後天的整天活動", tomorrow.plusDays(1)),
                allDay("三天研習", today, 3),
                ev("前一晚到今天早上", at(today, 22), at(tomorrow, 2)),
                ev("昨天的會", at(today, 9), at(today, 10)),
            ),
        )
        assertEquals(listOf("整天　三天研習", "～02:00　前一晚到今天早上"), b.lines)
    }

    @Test fun capsSpokenItems() {
        val many = (1..10).map { ev("活動$it", at(tomorrow, 7 + it), at(tomorrow, 8 + it)) }
        val b = CalendarBrief.compose(tomorrow, today, many)
        assertTrue(b.spoken.endsWith("另外還有 2 個，請看行事曆。"))
        assertEquals(10, b.lines.size)
    }

    @Test fun blankTitleAndLongLocation() {
        val b = CalendarBrief.compose(tomorrow, today, listOf(ev("  ", at(tomorrow, 9), at(tomorrow, 10), "台北市大安區某某路一段一百號十二樓會議室")))
        assertEquals("明天 11 月 10 日星期二，共有 1 個行程。早上 9 點，未命名行程。", b.spoken)
    }

    @Test fun triggerDays() {
        assertEquals(1, CalendarTrigger.parse("明天有什麼行程", today))
        assertEquals(0, CalendarTrigger.parse("今天要做什麼", today))
        assertEquals(2, CalendarTrigger.parse("後天有什麼活動？", today))
        assertEquals(0, CalendarTrigger.parse("我的行程", today))
        assertEquals(2, CalendarTrigger.parse("星期三有什麼行程", today)) // 今天星期一
        assertEquals(0, CalendarTrigger.parse("禮拜一有什麼事", today))
        assertEquals(9, CalendarTrigger.parse("下週三有什麼活動", today))
        assertEquals(13, CalendarTrigger.parse("下禮拜天有什麼安排", today))
    }

    @Test fun triggerIgnoresWrites() {
        assertNull(CalendarTrigger.parse("明天下午兩點新增一個行程開會", today))
        assertNull(CalendarTrigger.parse("把明天的行程取消", today))
        assertNull(CalendarTrigger.parse("明天天氣如何", today))
        assertNull(CalendarTrigger.parse("提醒我明天的行程", today))
    }

    @Test fun schedule() {
        val now = LocalDateTime.of(2026, 11, 9, 20, 0)
        assertEquals(LocalDateTime.of(2026, 11, 9, 21, 0), DailyBrief.nextFire(now, 21 * 60))
        assertEquals(LocalDateTime.of(2026, 11, 10, 7, 30), DailyBrief.nextFire(now, 7 * 60 + 30))
        assertEquals(LocalDateTime.of(2026, 11, 10, 20, 0), DailyBrief.nextFire(now, 20 * 60)) // 剛好現在：排明天
    }

    @Test fun briefDates() {
        val fri = LocalDate.of(2026, 11, 13)
        assertEquals(listOf(fri.plusDays(1)), DailyBrief.dates(fri, start = 1, days = 1, weekend = true)) // 預設：只唸明天
        assertEquals(emptyList<LocalDate>(), DailyBrief.dates(fri, start = 1, days = 1, weekend = false)) // 明天星期六：不唸
        assertEquals(listOf(today, tomorrow), DailyBrief.dates(today, start = 0, days = 2, weekend = true))
        assertEquals(listOf(fri), DailyBrief.dates(fri, start = 0, days = 3, weekend = false)) // 五六日：只剩星期五
        assertEquals(3, DailyBrief.dates(today, start = 1, days = 9, weekend = true).size) // 最多三天
        assertEquals("明天", DailyBrief.rangeWord(1, 1))
        assertEquals("今天、明天、後天", DailyBrief.rangeWord(0, 3))
        assertEquals("明天、後天、大後天", DailyBrief.rangeWord(1, 3))
    }

    @Test fun weatherCalls() {
        fun names(dates: List<LocalDate>) = DailyBrief.weatherCalls(today, dates).map { it.first }
        assertEquals(listOf("weather_tomorrow"), names(listOf(tomorrow))) // 預設：晚上唸明天
        assertEquals(listOf("weather_today", "weather_tomorrow"), names(listOf(today, tomorrow))) // 早上唸今天＋明天
        assertEquals(listOf("weather_today"), names(listOf(today)))
        assertEquals(emptyList<String>(), names(listOf(today.plusDays(2)))) // 後天以後沒有單日播報
        assertEquals("auto", DailyBrief.weatherCalls(today, listOf(tomorrow)).single().second.getString("place"))
        assertEquals("auto", DailyBrief.weatherCalls(today, listOf(today)).single().second.getString("place"))
        // 舊版天氣 APP 沒有 weather_today → 退回 weather_now（目前位置）
        assertEquals("weather_now", DailyBrief.weatherFallback("weather_today")?.first)
        assertEquals("current", DailyBrief.weatherFallback("weather_today")?.second?.getString("place"))
        assertEquals(null, DailyBrief.weatherFallback("weather_tomorrow"))
    }

    @Test fun composeSeveralDays() {
        val b = CalendarBrief.composeDays(today, listOf(
            today to listOf(ev("朝會", at(today, 8), at(today, 9))),
            tomorrow to emptyList(),
        ))
        assertEquals(1, b.count)
        assertEquals("今天 11 月 9 日星期一，共有 1 個行程。早上 8 點，朝會。 明天 11 月 10 日星期二沒有行程。", b.spoken)
        assertEquals(listOf("【今天 11/9（一）】", "08:00–09:00　朝會", "【明天 11/10（二）】", "沒有行程"), b.lines)
        // 只有一天時跟原本一樣，不加日期標題
        val one = CalendarBrief.composeDays(today, listOf(tomorrow to listOf(ev("考績會", at(tomorrow, 14, 30), at(tomorrow, 16)))))
        assertEquals(CalendarBrief.compose(tomorrow, today, listOf(ev("考績會", at(tomorrow, 14, 30), at(tomorrow, 16)))), one)
    }
}
