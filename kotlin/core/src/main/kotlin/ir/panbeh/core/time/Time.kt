package ir.panbeh.core.time

import java.time.Instant
import java.time.ZoneId

private val DOW = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MON = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** JS `Date.toDateString()` ("Wed Sep 30 2026") in `zone`: the key that identifies a calendar day. */
fun dayKey(ts: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val d = Instant.ofEpochMilli(ts).atZone(zone)
    return "${DOW[d.dayOfWeek.value - 1]} ${MON[d.monthValue - 1]} ${d.dayOfMonth.toString().padStart(2, '0')} ${d.year.toString().padStart(4, '0')}"
}

private fun startOfDay(ts: Long, zone: ZoneId) =
    Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

fun dayLabel(ts: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val diff = Math.round((startOfDay(now, zone) - startOfDay(ts, zone)) / 86_400_000.0)
    if (diff == 0L) return "امروز"
    if (diff == 1L) return "دیروز"
    // Jalali years roll over at Nowruz, not Jan 1, so compare them in that calendar
    return if (fmtYear(ts, zone) == fmtYear(now, zone)) fmtDayMonth(ts, zone) else fmtDayMonthYear(ts, zone)
}

/** Chat-list timestamp: 14:05 today, "Mon" this week, "12 Mar" otherwise. */
fun listTime(ts: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (ts <= 0) return ""
    if (dayKey(ts, zone) == dayKey(now, zone)) return fmtTime(ts, zone)
    if (now - ts < 6 * 86_400_000) return fmtWeekday(ts, zone)
    return fmtShortDate(ts, zone)
}

fun clock(ts: Long, zone: ZoneId = ZoneId.systemDefault()) = fmtTime(ts, zone)

fun stamp(ts: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) =
    "${dayLabel(ts, now, zone)}، ${clock(ts, zone)}"

/** Last-seen text from m.presence; null when the server told us nothing. */
fun lastSeen(online: Boolean, activeTs: Long?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String? {
    if (online) return "آنلاین"
    if (activeTs == null || activeTs <= 0) return null
    val min = Math.floorDiv(now - activeTs, 60_000L)
    if (min < 1) return "چند لحظه پیش"
    if (min < 60) return "آخرین بازدید ${num(min)} دقیقه پیش"
    return "آخرین بازدید ${if (dayKey(activeTs, zone) == dayKey(now, zone)) "امروز" else dayLabel(activeTs, now, zone)}، ${clock(activeTs, zone)}"
}

/** "۱:۰۵" from milliseconds. */
fun fmtDuration(ms: Long): String {
    val s = maxOf(0L, Math.round(ms / 1000.0))
    return "${num(s / 60)}:${num(s % 60).padStart(2, '۰')}"
}

/** A day offered for scheduling: `ts` is its local midnight. */
data class ScheduleDay(val ts: Long, val label: String)

/** The days a message can be scheduled for: today, tomorrow, then weekday and date (Jalali), as each day's local midnight. */
fun scheduleDays(now: Long = System.currentTimeMillis(), n: Int = 31, zone: ZoneId = ZoneId.systemDefault()): List<ScheduleDay> {
    val d = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return List(n) { i ->
        val day = d.plusDays(i.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        ScheduleDay(day, if (i == 0) "امروز" else if (i == 1) "فردا" else "${fmtWeekday(day, zone)}، ${fmtDayMonth(day, zone)}")
    }
}

/** A length of time in its biggest whole unit: "۳۰ روز", "۵ ساعت", "۱۰ دقیقه". */
fun fmtSpan(ms: Double): String {
    val (n, unit) = if (ms >= 86_400_000) ms / 86_400_000 to "روز" else if (ms >= 3_600_000) ms / 3_600_000 to "ساعت" else ms / 60_000 to "دقیقه"
    return "${num(Math.floor(n).toLong())} $unit"
}

fun fmtSpan(ms: Long) = fmtSpan(ms.toDouble())

/** When a scheduled message goes out, for its list row: "امروز ساعت ۲۱:۰۰", "۱۹ مهر ساعت ۰۹:۳۰". */
fun scheduleLabel(ts: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) =
    "${dayLabel(ts, now, zone)} ساعت ${fmtTime(ts, zone)}"
