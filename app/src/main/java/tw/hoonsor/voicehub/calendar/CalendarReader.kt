package tw.hoonsor.voicehub.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 讀手機系統的行事曆資料（Google 日曆同步下來的那份），不用登入 Google、不用 API 金鑰。
 * 共享給這支手機上帳號的行事曆，要在 Google 日曆 APP 裡打開「同步」才會出現在這裡。
 */
object CalendarReader {
    data class Cal(val id: Long, val name: String, val account: String, val visible: Boolean)

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** 手機上有同步的行事曆（設定頁讓倬勾選要唸哪些） */
    fun calendars(ctx: Context): List<Cal> {
        if (!hasPermission(ctx)) return emptyList()
        val out = mutableListOf<Cal>()
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.VISIBLE,
            ),
            "${CalendarContract.Calendars.SYNC_EVENTS}=1", null,
            "${CalendarContract.Calendars.ACCOUNT_NAME}, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME}",
        )?.use { c ->
            while (c.moveToNext()) {
                out += Cal(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3) == 1)
            }
        }
        return out
    }

    /**
     * 某一天的行程。[excluded] 是倬在設定頁取消勾選的行事曆；在 Google 日曆 APP 裡隱藏的行事曆也不唸。
     * 已婉拒的邀請、已取消的活動不唸。
     */
    fun events(ctx: Context, date: LocalDate, excluded: Set<Long>, zone: ZoneId = ZoneId.systemDefault()): List<CalEvent> {
        if (!hasPermission(ctx)) throw SecurityException("沒有讀取行事曆的權限")
        // 整天的活動用 UTC 午夜存，查詢範圍前後各多抓一天，再由 CalendarBrief.clean 篩出這一天
        val from = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val out = mutableListOf<CalEvent>()
        ctx.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                CalendarContract.Instances.STATUS,
            ),
            "${CalendarContract.Instances.VISIBLE}=1", null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getLong(5) in excluded) continue
                if (!c.isNull(6) && c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                if (!c.isNull(7) && c.getInt(7) == CalendarContract.Events.STATUS_CANCELED) continue
                val allDay = c.getInt(3) == 1
                val tz = if (allDay) ZoneOffset.UTC else zone
                fun at(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), tz)
                val start = at(c.getLong(1))
                val end = if (c.isNull(2)) start else at(c.getLong(2))
                out += CalEvent(c.getString(0).orEmpty(), start, end, allDay, c.getString(4).orEmpty())
            }
        }
        return out
    }
}
