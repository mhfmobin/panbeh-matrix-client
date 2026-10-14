package ir.panbeh.core.time

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** A Jalali (Persian) calendar date; `month` is 1..12. */
data class Jalali(val year: Int, val month: Int, val day: Int)

/** Month names as Intl fa-IR prints them, indexed by month - 1. */
val JALALI_MONTHS = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")

/** Weekday names as Intl fa-IR prints them (short and long are the same), by [DayOfWeek]. */
fun weekdayName(d: DayOfWeek) = when (d) {
    DayOfWeek.MONDAY -> "دوشنبه"
    DayOfWeek.TUESDAY -> "سه‌شنبه"
    DayOfWeek.WEDNESDAY -> "چهارشنبه"
    DayOfWeek.THURSDAY -> "پنجشنبه"
    DayOfWeek.FRIDAY -> "جمعه"
    DayOfWeek.SATURDAY -> "شنبه"
    DayOfWeek.SUNDAY -> "یکشنبه"
}

private val CUM_DAYS = intArrayOf(0, 31, 62, 93, 124, 155, 186, 216, 246, 276, 306, 336)

/** Gregorian → Jalali with ICU's (arithmetic, 33-year cycle) algorithm, which is what Intl uses; matches Node up to year 1502. */
fun jalaliOf(date: LocalDate): Jalali {
    val days = date.toEpochDay() + 2440588 - 1948320 // Julian day minus ICU's Persian epoch
    val year = 1 + Math.floorDiv(33 * days + 3, 12053L)
    val doy = (days - (365 * (year - 1) + Math.floorDiv(8 * year + 21, 33L))).toInt()
    val month = if (doy < 216) doy / 31 else (doy - 6) / 30
    return Jalali(year.toInt(), month + 1, doy - CUM_DAYS[month] + 1)
}

fun jalaliOf(ts: Long, zone: ZoneId = ZoneId.systemDefault()) = jalaliOf(Instant.ofEpochMilli(ts).atZone(zone).toLocalDate())

/** Latin digits → Persian digits (۰-۹). */
fun persianDigits(s: String) = s.map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")

/** Intl.NumberFormat("fa-IR"): Persian digits, "٬" grouping, "٫" decimal point, at most 3 fraction digits. */
fun num(n: Double): String {
    if (n.isNaN()) return "ناعدد"
    val neg = n < 0 || (n == 0.0 && 1 / n < 0)
    val sign = if (neg) "‎−" else ""
    if (n.isInfinite()) return "$sign∞"
    val plain = BigDecimal(abs(n)).setScale(3, RoundingMode.HALF_UP).toPlainString()
    val (int, frac) = plain.substringBefore('.') to plain.substringAfter('.', "").trimEnd('0')
    val grouped = int.reversed().chunked(3).joinToString("٬").reversed()
    return sign + persianDigits(grouped) + if (frac.isEmpty()) "" else "٫" + persianDigits(frac)
}

fun num(n: Long) = num(n.toDouble())
fun num(n: Int) = num(n.toDouble())

// Intl.DateTimeFormat("fa-IR", …) equivalents, in `zone`
private fun zoned(ts: Long, zone: ZoneId) = Instant.ofEpochMilli(ts).atZone(zone)
private fun two(n: Int) = persianDigits(n.toString().padStart(2, '0'))

/** {hour: "2-digit", minute: "2-digit"} → "۱۴:۰۵" */
fun fmtTime(ts: Long, zone: ZoneId = ZoneId.systemDefault()) = zoned(ts, zone).let { "${two(it.hour)}:${two(it.minute)}" }

/** {year: "numeric"} → "۱۴۰۵" */
fun fmtYear(ts: Long, zone: ZoneId = ZoneId.systemDefault()) = persianDigits(jalaliOf(ts, zone).year.toString())

/** {month: "long", day: "numeric"} → "۸ مهر" (also {day, month: "short"}: the short names are the long ones) */
fun fmtDayMonth(ts: Long, zone: ZoneId = ZoneId.systemDefault()) =
    jalaliOf(ts, zone).let { "${persianDigits(it.day.toString())} ${JALALI_MONTHS[it.month - 1]}" }

/** {month: "long", day: "numeric", year: "numeric"} → "۸ مهر ۱۴۰۵" */
fun fmtDayMonthYear(ts: Long, zone: ZoneId = ZoneId.systemDefault()) = "${fmtDayMonth(ts, zone)} ${fmtYear(ts, zone)}"

/** {weekday: "short"} → "چهارشنبه" */
fun fmtWeekday(ts: Long, zone: ZoneId = ZoneId.systemDefault()) = weekdayName(zoned(ts, zone).dayOfWeek)

/** {day: "numeric", month: "short"} → "۸ مهر" */
fun fmtShortDate(ts: Long, zone: ZoneId = ZoneId.systemDefault()) = fmtDayMonth(ts, zone)
