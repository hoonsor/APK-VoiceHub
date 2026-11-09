package tw.hoonsor.voicehub.calendar

import tw.hoonsor.voicehub.agent.OfflineParser
import java.time.LocalDate

/**
 * 「明天有什麼行程」這類查詢在手機上直接判斷，不送 AI（行事曆內容不離開手機）。
 * 回傳要查的那天距離今天幾天；不是查行程的句子回 null。純邏輯，有 JVM 測試。
 */
object CalendarTrigger {
    private val ask = Regex("行程|行事曆|有什麼事|有哪些事|要做什麼|要幹嘛|有什麼活動|有哪些活動|有什麼安排|有什麼會|要開什麼會|忙不忙|有沒有事")
    /** 新增、修改類的交給 AI（會走 calendar_event_add） */
    private val write = Regex("新增|加入|加一個|排一個|排入|安排一個|設|提醒我|取消|刪|改到|改成|移到|記得")
    private val weekday = Regex("(下下|下|這|本)?(?:個)?(?:星期|禮拜|週|周)([一二三四五六日天])")

    fun parse(raw: String, today: LocalDate): Int? {
        val t = OfflineParser.normalize(raw)
        if (t.length > 20 || !ask.containsMatchIn(t) || write.containsMatchIn(t)) return null
        weekday.find(t)?.let { m ->
            val target = "一二三四五六日天".indexOf(m.groupValues[2]).let { if (it == 7) 7 else it + 1 }
            val cur = today.dayOfWeek.value
            // 「下週三」＝下一週（從週一算起）的星期三；沒說「下」就是最近的那一天（今天也算）
            return when (m.groupValues[1]) {
                "下" -> 7 - cur + target
                "下下" -> 14 - cur + target
                else -> (target - cur + 7) % 7
            }
        }
        return when {
            t.contains("大後天") -> 3
            t.contains("後天") -> 2
            t.contains("明天") || t.contains("明日") -> 1
            t.contains("今天") || t.contains("今日") || t.contains("等一下") || t.contains("待會") -> 0
            else -> if (t.contains("行程") || t.contains("行事曆")) 0 else null
        }
    }
}
