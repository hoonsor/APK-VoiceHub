package tw.hoonsor.voicehub.calendar

import android.content.Context
import tw.hoonsor.voicehub.calendar.CalendarCommand.Add
import tw.hoonsor.voicehub.calendar.CalendarCommand.Delete
import tw.hoonsor.voicehub.calendar.CalendarCommand.Move
import tw.hoonsor.voicehub.calendar.CalendarCommand.Unclear
import tw.hoonsor.voicehub.calendar.CalendarWriter.Found
import tw.hoonsor.voicehub.data.Settings
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 語音新增、改時間、刪除行程：[plan] 把一句話變成「要確認的動作」或一句回覆，
 * 倬說「確定」後才 [run] 寫入。全程不經 AI。要在背景執行緒呼叫（會查行事曆）。
 */
class CalendarVoice(private val ctx: Context, private val settings: Settings) {
    sealed interface Op { val label: String }
    data class Insert(val cal: CalendarWriter.WCal, val event: CalEvent) : Op { override val label = "新增行程" }
    data class Update(val found: Found, val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean) : Op { override val label = "修改行程" }
    data class Remove(val found: Found) : Op { override val label = "刪除行程" }

    sealed interface Plan
    data class Ask(val text: String, val op: Op) : Plan
    data class Say(val text: String, val retry: Boolean = false) : Plan

    /** 不是行事曆指令回 null（交給一般流程） */
    fun plan(raw: String, now: LocalDateTime = LocalDateTime.now()): Plan? {
        val first = CalendarCommand.parse(raw, now) ?: return null
        val calWord = CalendarCommand.mentionsCalendar(raw)
        if (!CalendarWriter.canWrite(ctx) || !CalendarReader.hasPermission(ctx)) {
            return if (first is Add || first is Unclear || calWord) Say(NO_PERMISSION) else null
        }
        val today = now.toLocalDate()
        val cmd = if (first is Add) CalendarCommand.parse(raw, now, CalendarWriter.writable(ctx).map { it.name }) ?: first else first
        return when (cmd) {
            is Unclear -> Say(cmd.message, retry = true)
            is Add -> planAdd(cmd, today)
            is Move -> pick(cmd.query, cmd.onDate, today, calWord) { f -> planMove(f, cmd, today) }
            is Delete -> pick(cmd.query, cmd.onDate, today, calWord) { f ->
                Ask("要刪除${oldText(f, today)}的「${f.title}」嗎？", Remove(f))
            }
        }
    }

    fun run(op: Op, today: LocalDate = LocalDate.now()): Result<String> = runCatching {
        when (op) {
            is Insert -> {
                CalendarWriter.insert(ctx, op.cal, op.event)
                "已新增到「${op.cal.name}」：${eventText(op.event, today)}。"
            }
            is Update -> {
                CalendarWriter.update(ctx, op.found, op.start, op.end, op.allDay)
                "已把「${op.found.title}」改到${CalendarCommand.whenText(op.start.toLocalDate(), today, if (op.allDay) null else op.start.toLocalTime())}。"
            }
            is Remove -> {
                CalendarWriter.delete(ctx, op.found)
                "已刪除「${op.found.title}」。要救回的話，到 Google 日曆網頁版的「垃圾桶」可以還原。"
            }
        }
    }

    private fun planAdd(cmd: Add, today: LocalDate): Plan {
        val cal = CalendarWriter.target(ctx, settings.calWriteId, cmd.calendar)
            ?: return Say("手機上沒有可以寫入的行事曆。請先在 設定 › 帳號 加入 Google 帳號，並在 Google 日曆 APP 打開同步。")
        val start = cmd.date.atTime(cmd.start ?: LocalTime.MIDNIGHT)
        val end = when {
            cmd.start == null -> cmd.date.plusDays(1).atStartOfDay()
            cmd.end != null -> cmd.date.atTime(cmd.end)
            else -> start.plusHours(1)
        }
        val event = CalEvent(cmd.title, start, end, cmd.start == null, cmd.location)
        return Ask("要在「${cal.name}」新增：${eventText(event, today)}。確定嗎？", Insert(cal, event))
    }

    private fun planMove(f: Found, cmd: Move, today: LocalDate): Plan {
        val duration = Duration.between(f.start, f.end).takeIf { !it.isNegative && !it.isZero } ?: Duration.ofHours(1)
        val (start, allDay) = when {
            cmd.newTime != null -> (cmd.newDate ?: f.start.toLocalDate()).atTime(cmd.newTime) to false
            f.allDay -> cmd.newDate!!.atStartOfDay() to true
            else -> cmd.newDate!!.atTime(f.start.toLocalTime()) to false
        }
        // 整天的活動改成幾點開始：預設一小時
        val end = if (f.allDay && !allDay) start.plusHours(1) else start.plus(duration)
        if (start == f.start && allDay == f.allDay) return Say("「${f.title}」本來就是這個時間。")
        val newText = if (!allDay && !f.allDay && start.toLocalDate() == f.start.toLocalDate()) CalendarBrief.spokenTime(start.toLocalTime())
        else CalendarCommand.whenText(start.toLocalDate(), today, if (allDay) null else start.toLocalTime())
        return Ask("要把「${f.title}」從${oldText(f, today)}改到$newText。確定嗎？", Update(f, start, end, allDay))
    }

    /** 找出要改或刪的那一個；找不到、找到很多、不能改都回一句話 */
    private fun pick(query: String, onDate: LocalDate?, today: LocalDate, calWord: Boolean, then: (Found) -> Plan): Plan? {
        val from = onDate ?: today
        val to = onDate?.plusDays(1) ?: today.plusDays(SEARCH_DAYS)
        val all = CalendarWriter.find(ctx, from, to).filter { CalendarCommand.matches(it.title, query) }
            .distinctBy { Triple(it.eventId, it.start, it.calId) }
        val dayText = onDate?.let { CalendarCommand.whenText(it, today, null).removeSuffix("整天") } ?: "接下來 $SEARCH_DAYS 天"
        // 有說行事曆或日期（「取消後天的家長會」）才回找不到；否則交給一般流程
        if (all.isEmpty()) return if (calWord || onDate != null) Say("行事曆裡找不到${dayText}的「$query」。", retry = true) else null
        val list = all.filter { CalendarCommand.sameTitle(it.title, query) }.takeIf { it.size == 1 } ?: all
        if (list.size > 1) {
            val names = list.take(3).joinToString("、") { "${oldText(it, today)}的${it.title}" }
            val more = if (list.size > 3) "等 ${list.size} 個" else ""
            return Say("找到好幾個：$names$more。請加上日期或完整名稱再說一次。", retry = true)
        }
        val f = list.single()
        return when {
            f.recurring -> Say("「${f.title}」是重複的活動，為了不改到整個系列，請到 Google 日曆 APP 修改。")
            f.invited -> Say("「${f.title}」是別人邀請你的活動，只有主辦人能改。要不參加的話，請在 Google 日曆 APP 回覆。")
            !f.writable -> Say("「${f.title}」在唯讀的「${f.calName}」行事曆裡，沒辦法修改。")
            else -> then(f)
        }
    }

    private fun oldText(f: Found, today: LocalDate) =
        CalendarCommand.whenText(f.start.toLocalDate(), today, if (f.allDay) null else f.start.toLocalTime())

    private fun eventText(e: CalEvent, today: LocalDate): String {
        val time = CalendarCommand.whenText(
            e.start.toLocalDate(), today,
            if (e.allDay) null else e.start.toLocalTime(),
            if (e.allDay || e.end != e.start.plusHours(1)) e.end.toLocalTime().takeIf { !e.allDay && e.end.toLocalDate() == e.start.toLocalDate() } else null,
        )
        return "$time，${e.title}" + if (e.location.isNotBlank()) "，在${e.location}" else ""
    }

    companion object {
        const val SEARCH_DAYS = 60L
        const val NO_PERMISSION = "還沒允許語音總控修改行事曆。請到 設定 › 明天行程提醒 按「允許行事曆權限」。"
    }
}
