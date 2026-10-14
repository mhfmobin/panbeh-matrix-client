package ir.panbeh.core

import ir.panbeh.core.text.*
import ir.panbeh.core.time.*
import ir.panbeh.core.timeline.*
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

internal val Z: ZoneId = ZoneId.of("UTC")
internal val T = Instant.parse("2026-09-30T12:00:00Z").toEpochMilli()
private fun m(id: String, sender: String, min: Long, kind: MsgKind = MsgKind.MSG) = Msg(id, sender, T + min * 60_000, kind)
private fun rows(msgs: List<Msg>, now: Long = T, unreadAfter: String? = null) = buildRows(msgs, now, unreadAfter, Z)

class TimelineTest {
    @Test fun `groups same-sender bursts, splits on gap, sender, notice and day`() {
        val r = rows(listOf(
            m("a", "@x", 0), m("b", "@x", 1), m("c", "@x", 2), // one group
            m("d", "@x", 10), // gap > 5min
            m("e", "@y", 11), // other sender
            m("f", "@y", 12, MsgKind.NOTICE), m("g", "@y", 13), // notice breaks group
            m("h", "@y", 13 + 24 * 60), // next day
        ))
        val flags = r.filterIsInstance<Row.Message>().associate { it.id to "${if (it.first) 1 else 0}${if (it.last) 1 else 0}" }
        assertEquals(mapOf("a" to "10", "b" to "00", "c" to "01", "d" to "11", "e" to "11", "g" to "11", "h" to "11"), flags)
        assertEquals(listOf("امروز", "۹ مهر"), r.filterIsInstance<Row.Day>().map { it.label })
    }

    @Test fun `dayLabel compares Jalali years, not Gregorian`() {
        val now = Instant.parse("2026-10-01T12:00:00Z").toEpochMilli()
        assertEquals("۲۶ بهمن ۱۴۰۴", dayLabel(Instant.parse("2026-02-15T12:00:00Z").toEpochMilli(), now, Z)) // same Gregorian year, previous Jalali year
        assertEquals("۲۶ فروردین", dayLabel(Instant.parse("2026-04-15T12:00:00Z").toEpochMilli(), now, Z))
    }

    @Test fun `stamp starts with today`() = assertTrue(stamp(T, T, Z).startsWith("امروز، "))

    @Test fun `unread divider goes after the last read message and splits its burst`() {
        val msgs = listOf(m("a", "@x", 0), m("b", "@x", 1), m("c", "@x", 2))
        val r = rows(msgs, T, "a")
        assertEquals(listOf("day", "a11", "unread", "b10", "c01"), r.map {
            when (it) { is Row.Message -> "${it.id}${if (it.first) 1 else 0}${if (it.last) 1 else 0}"; is Row.Day -> "day"; is Row.Unread -> "unread"; else -> "?" }
        })
        assertTrue(rows(msgs, T, "c").none { it is Row.Unread }) // nothing after it
        assertTrue(rows(msgs).none { it is Row.Unread })
    }

    @Test fun `albums - tagged ones group, untagged only seconds apart, captions and the divider split them`() {
        fun at(id: String, sec: Long, album: String? = null, sender: String = "@x") = Msg(id, sender, T + sec * 1000, MsgKind.MSG, album)
        fun kinds(r: List<Row>) = r.filter { it !is Row.Day }.map {
            when (it) { is Row.Album -> it.ids.joinToString("+"); is Row.Message -> it.id; is Row.Unread -> "unread"; else -> "?" }
        }
        assertEquals(listOf("a+b", "c+d"), kinds(rows(listOf(at("a", 0, "t:1"), at("b", 40, "t:1"), at("c", 41, "t:2"), at("d", 42, "t:2"))))) // tags, however far apart
        assertEquals(listOf("a+b", "c", "d", "e"), kinds(rows(listOf(at("a", 0, "h"), at("b", 3, "h"), at("c", 20, "h"), at("d", 21), at("e", 22, "h"))))) // gap, caption (no album key)
        assertEquals(listOf("a", "b"), kinds(rows(listOf(at("a", 0, "h"), at("b", 1, "h", "@y"))))) // other sender
        assertEquals(listOf("a", "unread", "b+c"), kinds(rows(listOf(at("a", 0, "h"), at("b", 1, "h"), at("c", 2, "h")), T, "a")))
        assertEquals(2, rows(List(12) { at("m$it", it.toLong(), "h") }).count { it is Row.Album }) // 10 at most
        val r = rows(listOf(at("a", 0, "t:1"), at("b", 1, "t:1"), at("c", 2))).filter { it !is Row.Day }
        assertEquals(listOf(Row.Album("a", "b", listOf("a", "b"), true, false), Row.Message("c", "c", false, true)), r)
    }

    @Test fun `lastSeen buckets`() {
        fun ago(min: Double) = T - (min * 60_000).toLong()
        assertEquals("آنلاین", lastSeen(true, null, T, Z))
        assertNull(lastSeen(false, null, T, Z))
        assertEquals("چند لحظه پیش", lastSeen(false, ago(0.2), T, Z))
        assertEquals("آخرین بازدید ۵ دقیقه پیش", lastSeen(false, ago(5.0), T, Z))
        assertEquals("آخرین بازدید امروز، ۱۰:۳۰", lastSeen(false, ago(90.0), T, Z))
        assertEquals("آخرین بازدید دیروز، ۱۲:۰۰", lastSeen(false, ago(24.0 * 60), T, Z))
    }

    @Test fun `voice duration`() {
        assertEquals("۱:۰۵", fmtDuration(65_400))
        assertEquals("۰:۰۰", fmtDuration(0))
    }

    @Test fun `listTime buckets`() {
        assertEquals("", listTime(0, T, Z))
        assertEquals("۱۰:۳۰", listTime(T - 90 * 60_000, T, Z))
        assertEquals("سه\u200cشنبه", listTime(T - 24 * 3_600_000, T, Z)) // yesterday, Tuesday
        assertEquals("۲۹ شهریور", listTime(T - 10 * 86_400_000L, T, Z))
    }

    @Test fun `schedule days, spans and labels`() {
        val days = scheduleDays(Instant.parse("2026-10-11T22:30:00Z").toEpochMilli(), 3, Z)
        assertEquals(listOf("امروز", "فردا", days[2].label.split("، ")[0]), days.map { it.label.split("، ")[0] })
        val d1 = Instant.ofEpochMilli(days[1].ts).atZone(Z)
        assertEquals(0, d1.hour)
        assertEquals(12, d1.dayOfMonth)
        assertEquals("۳۰ روز", fmtSpan(30 * 86_400_000L))
        assertEquals("۵ ساعت", fmtSpan(5.5 * 3_600_000))
        assertEquals("۱۰ دقیقه", fmtSpan(600_000L))
        assertEquals("امروز ساعت ۲۱:۰۰", scheduleLabel(Instant.parse("2026-10-11T21:00:00Z").toEpochMilli(), Instant.parse("2026-10-11T08:00:00Z").toEpochMilli(), Z))
    }
}
