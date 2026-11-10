package tw.hoonsor.voicehub.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 直接寫手機系統的行事曆，Google 帳號同步會把變更推到 Google 日曆，不需要 Google API。
 * 只寫「參與者以上」權限的行事曆；重複活動、別人邀請的活動不改（避免改到整個系列或主辦人的活動）。
 */
object CalendarWriter {
    data class WCal(val id: Long, val name: String, val account: String, val primary: Boolean)

    data class Found(
        val eventId: Long,
        val calId: Long,
        val calName: String,
        val title: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val allDay: Boolean,
        val recurring: Boolean,
        val writable: Boolean,
        val invited: Boolean,
    )

    fun canWrite(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun writable(ctx: Context): List<WCal> {
        if (!canWrite(ctx) || !CalendarReader.hasPermission(ctx)) return emptyList()
        val out = mutableListOf<WCal>()
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.IS_PRIMARY,
            ),
            "${CalendarContract.Calendars.SYNC_EVENTS}=1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null,
            "${CalendarContract.Calendars.ACCOUNT_NAME}, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME}",
        )?.use { c ->
            while (c.moveToNext()) {
                out += WCal(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), !c.isNull(3) && c.getInt(3) == 1)
            }
        }
        return out
    }

    /** 句子裡說了行事曆名稱就用它；否則用設定頁選的；都沒有就用第一個帳號的主要行事曆 */
    fun target(ctx: Context, preferredId: Long, spokenName: String?): WCal? {
        val list = writable(ctx)
        spokenName?.let { n -> list.firstOrNull { it.name == n }?.let { return it } }
        list.firstOrNull { it.id == preferredId }?.let { return it }
        return list.firstOrNull { it.primary } ?: list.firstOrNull()
    }

    fun insert(ctx: Context, cal: WCal, e: CalEvent, zone: ZoneId = ZoneId.systemDefault()): Long {
        val v = times(e.start, e.end, e.allDay, zone).apply {
            put(CalendarContract.Events.CALENDAR_ID, cal.id)
            put(CalendarContract.Events.TITLE, e.title)
            if (e.location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, e.location)
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: error("行事曆沒有接受新增")
        return ContentUris.parseId(uri)
    }

    fun update(ctx: Context, f: Found, start: LocalDateTime, end: LocalDateTime, allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()) {
        val n = ctx.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, f.eventId), times(start, end, allDay, zone), null, null)
        if (n < 1) error("找不到這個活動，可能已經被刪除")
    }

    fun delete(ctx: Context, f: Found) {
        val n = ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, f.eventId), null, null)
        if (n < 1) error("找不到這個活動，可能已經被刪除")
    }

    /** 整天的活動用 UTC 午夜存（Google 日曆的規則），其他用手機時區 */
    private fun times(start: LocalDateTime, end: LocalDateTime, allDay: Boolean, zone: ZoneId) = ContentValues().apply {
        val tz = if (allDay) ZoneOffset.UTC else zone
        val s = if (allDay) start.toLocalDate().atStartOfDay() else start
        val e = if (allDay) maxOf(end.toLocalDate(), start.toLocalDate().plusDays(1)).atStartOfDay() else end
        put(CalendarContract.Events.DTSTART, s.atZone(tz).toInstant().toEpochMilli())
        put(CalendarContract.Events.DTEND, e.atZone(tz).toInstant().toEpochMilli())
        put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id)
        put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
    }

    /** [from]～[to]（不含）之間的活動，給語音改時間、刪除時找對象 */
    fun find(ctx: Context, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Found> {
        if (!CalendarReader.hasPermission(ctx)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            ContentUris.appendId(it, to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        }.build()
        val out = mutableListOf<Found>()
        ctx.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.RRULE,
                CalendarContract.Instances.ORIGINAL_ID,
                CalendarContract.Instances.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Instances.ORGANIZER,
                CalendarContract.Instances.OWNER_ACCOUNT,
                CalendarContract.Instances.STATUS,
            ),
            "${CalendarContract.Instances.VISIBLE}=1", null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(12) && c.getInt(12) == CalendarContract.Events.STATUS_CANCELED) continue
                val allDay = c.getInt(6) == 1
                val tz = if (allDay) ZoneOffset.UTC else zone
                fun at(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), tz)
                val start = at(c.getLong(4))
                val end = if (c.isNull(5)) start else at(c.getLong(5))
                // 只留跟範圍重疊的（整天的活動多抓了前後一天）
                if (!end.isAfter(from.atStartOfDay()) && start != from.atStartOfDay()) continue
                if (!start.isBefore(to.atStartOfDay())) continue
                val organizer = c.getString(10).orEmpty()
                val owner = c.getString(11).orEmpty()
                out += Found(
                    eventId = c.getLong(0),
                    calId = c.getLong(1),
                    calName = c.getString(2).orEmpty(),
                    title = c.getString(3).orEmpty(),
                    start = start,
                    end = end,
                    allDay = allDay,
                    recurring = !c.getString(7).isNullOrBlank() || !c.isNull(8),
                    writable = c.getInt(9) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                    invited = organizer.isNotBlank() && owner.isNotBlank() && !organizer.equals(owner, ignoreCase = true),
                )
            }
        }
        return out
    }
}
