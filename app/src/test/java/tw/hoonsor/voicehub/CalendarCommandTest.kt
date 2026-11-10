package tw.hoonsor.voicehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.hoonsor.voicehub.calendar.CalendarCommand
import tw.hoonsor.voicehub.calendar.CalendarCommand.Add
import tw.hoonsor.voicehub.calendar.CalendarCommand.Delete
import tw.hoonsor.voicehub.calendar.CalendarCommand.Move
import tw.hoonsor.voicehub.calendar.CalendarCommand.Unclear
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 用說的新增、改時間、刪除行程（今天＝2026-11-10 星期二 09:00） */
class CalendarCommandTest {
    private val now = LocalDateTime.of(2026, 11, 10, 9, 0)
    private fun d(day: Int, month: Int = 11) = LocalDate.of(2026, month, day)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)
    private fun parse(s: String, cals: List<String> = emptyList()) = CalendarCommand.parse(s, now, cals)

    @Test fun addWithLocation() =
        assertEquals(Add("考績會", d(12), t(14, 30), null, "會議室"), parse("行事曆新增後天下午兩點半考績會，在會議室"))

    @Test fun addRange() =
        assertEquals(Add("朝會", d(11), t(8), t(9)), parse("幫我新增行程明天早上八點到九點朝會"))

    @Test fun addRangeInheritsPeriod() =
        assertEquals(Add("研習", d(11), t(15), t(17)), parse("行事曆新增明天下午三點到五點研習"))

    @Test fun addAllDay() =
        assertEquals(Add("校慶", d(18), null, null), parse("新增行程下週三整天校慶"))

    @Test fun addDateOnlyIsAllDay() =
        assertEquals(Add("校慶", d(14), null, null), parse("行事曆新增這週六校慶"))

    @Test fun addToNamedCalendar() =
        assertEquals(Add("家長會", d(20), t(15), null, calendar = "學校"), parse("幫我在學校行事曆加一個11月20號下午三點家長會", listOf("學校", "個人")))

    @Test fun addEveningHalf() =
        assertEquals(Add("聚餐", d(11), t(19, 30), null), parse("行事曆加 明天晚上七點半 聚餐"))

    @Test fun addMinutes() =
        assertEquals(Add("面試", d(12), t(10, 15), null), parse("新增行程後天上午十點十五分面試"))

    @Test fun addBaForm() =
        assertEquals(Add("家長會", d(12), t(15), null), parse("把後天下午三點家長會加到行事曆"))

    @Test fun addNoDateUsesTodayOrTomorrow() {
        assertEquals(Add("人事會議", d(10), t(14), null), parse("行事曆新增兩點開人事會議"))
        assertEquals(Add("晨跑", d(11), t(7), null), parse("行事曆新增七點晨跑"))
    }

    @Test fun addTonight() =
        assertEquals(Add("聚餐", d(10), t(19), null), parse("行事曆新增今晚七點聚餐"))

    @Test fun addLeadingLocation() =
        assertEquals(Add("朝會", d(11), t(8), null, "禮堂"), parse("新增行程明天早上八點在禮堂開朝会".replace("会", "會")))

    @Test fun singleDigitAfterHourIsNotMinutes() =
        assertEquals(Add("二樓開會", d(11), t(15), null), parse("行事曆新增明天下午三點二樓開會"))

    @Test fun addUnclear() {
        assertTrue(parse("新增行程") is Unclear)
        assertTrue(parse("行事曆新增考績會") is Unclear)
    }

    @Test fun notCalendarCommands() {
        assertNull(parse("幫我新增一個鬧鐘明天七點"))
        assertNull(parse("明天有什麼行程"))
        assertNull(parse("明天下午兩點開人事會議")) // 沒說行事曆：交給 AI（打開新增畫面）
        assertNull(parse("打開音樂"))
        assertNull(parse("取消"))
        assertNull(parse("取消訂閱"))
    }

    @Test fun moveTime() =
        assertEquals(Move("考績會", d(11), null, t(15)), parse("把明天的考績會改到三點"))

    @Test fun moveToDayAndTime() =
        assertEquals(Move("考績會", null, d(16), t(10)), parse("考績會改到下週一早上十點"))

    @Test fun moveDateOnly() =
        assertEquals(Move("家長會", d(20), d(27), null), parse("把11月20號的家長會延到27號"))

    @Test fun notMoves() {
        assertNull(parse("把語言改成英文"))
        assertNull(parse("把明天的鬧鐘改到七點"))
        assertNull(parse("把考績會改成線上"))
    }

    @Test fun delete() {
        assertEquals(Delete("朝會", d(12)), parse("取消後天的朝會"))
        assertEquals(Delete("考績會", d(11)), parse("把明天的考績會行程刪掉"))
        assertEquals(Delete("家長會", null), parse("刪除家長會"))
    }

    @Test fun weekdayOffsetMatchesQueries() {
        assertEquals(8, CalendarCommand.weekdayOffset("下", 3, 2))
        assertEquals(1, CalendarCommand.weekdayOffset("", 3, 2))
        assertEquals(0, CalendarCommand.weekdayOffset("", 2, 2))
    }

    @Test fun matching() {
        assertTrue(CalendarCommand.matches("114 學年度考績會", "考績會"))
        assertTrue(CalendarCommand.matches("朝會", "行政朝會"))
        assertFalse(CalendarCommand.matches("考績委員會", "考績會"))
        assertFalse(CalendarCommand.matches("會", "考績會"))
    }

    @Test fun whenText() {
        assertEquals("明天 11 月 11 日星期三下午 2 點半", CalendarCommand.whenText(d(11), d(10), t(14, 30)))
        assertEquals("11 月 20 日星期五下午 3 點到 4 點", CalendarCommand.whenText(d(20), d(10), t(15), t(16)))
        assertEquals("後天 11 月 12 日星期四整天", CalendarCommand.whenText(d(12), d(10), null))
    }
}
