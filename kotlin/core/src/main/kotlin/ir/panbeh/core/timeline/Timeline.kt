package ir.panbeh.core.timeline

import ir.panbeh.core.time.dayKey
import ir.panbeh.core.time.dayLabel
import java.time.ZoneId

enum class MsgKind { MSG, NOTICE }

/** A timeline message as buildRows sees it. album: "t:<id>" for Panbeh's tagged albums, "h" for media that may group by the heuristic (uncaptioned, close in time). */
data class Msg(val id: String, val sender: String, val ts: Long, val kind: MsgKind = MsgKind.MSG, val album: String? = null)

/** One row of the rendered timeline. */
sealed interface Row {
    data class Day(val key: String, val label: String) : Row
    data class Notice(val key: String, val id: String) : Row
    data object Unread : Row { const val key = "unread" }
    data class Message(val key: String, val id: String, val first: Boolean, val last: Boolean) : Row
    /** photos/videos shown as one grid; id is the last one's */
    data class Album(val key: String, val id: String, val ids: List<String>, val first: Boolean, val last: Boolean) : Row
}

private const val GROUP_GAP = 5 * 60_000L
private const val ALBUM_MAX = 10
private const val ALBUM_GAP = 10_000L

private fun groups(a: Msg?, b: Msg?, zone: ZoneId) =
    a != null && b != null && a.kind == MsgKind.MSG && b.kind == MsgKind.MSG &&
        a.sender == b.sender && b.ts - a.ts < GROUP_GAP && dayKey(a.ts, zone) == dayKey(b.ts, zone)

/** `m` joins the album `prev` ends: same sender and day, and the same tag, or both untagged and seconds apart. */
private fun joinsAlbum(prev: Msg, m: Msg, size: Int, zone: ZoneId) =
    !prev.album.isNullOrEmpty() && prev.album == m.album && size < ALBUM_MAX && prev.sender == m.sender &&
        dayKey(prev.ts, zone) == dayKey(m.ts, zone) && (prev.album != "h" || m.ts - prev.ts <= ALBUM_GAP)

private data class Block(var m: Msg, val ids: MutableList<String>)

/** Timeline rows: day separators + messages flagged as first/last of a same-sender burst, consecutive album members
 *  as one row, plus an "unread" divider after `unreadAfter` (a Msg id) when anything follows it (it splits albums too). */
fun buildRows(msgs: List<Msg>, now: Long = System.currentTimeMillis(), unreadAfter: String? = null, zone: ZoneId = ZoneId.systemDefault()): List<Row> {
    val units = ArrayList<Block>() // an album counts as its last message
    for (m in msgs) {
        val u = units.lastOrNull()
        if (u != null && u.m.id != unreadAfter && joinsAlbum(u.m, m, u.ids.size, zone)) { u.m = m; u.ids.add(m.id) }
        else units.add(Block(m, mutableListOf(m.id)))
    }
    val rows = ArrayList<Row>()
    var prevDay = ""
    units.forEachIndexed { i, (m, ids) ->
        if (dayKey(m.ts, zone) != prevDay) {
            prevDay = dayKey(m.ts, zone)
            rows.add(Row.Day("day-$prevDay", dayLabel(m.ts, now, zone)))
        }
        val prev = units.getOrNull(i - 1)?.m
        val next = units.getOrNull(i + 1)?.m
        // the divider splits a burst
        val first = !groups(prev, m, zone) || prev?.id == unreadAfter
        val last = !groups(m, next, zone) || m.id == unreadAfter
        if (m.kind == MsgKind.NOTICE) rows.add(Row.Notice(m.id, m.id))
        else if (ids.size > 1) rows.add(Row.Album(ids[0], m.id, ids.toList(), first, last))
        else rows.add(Row.Message(m.id, m.id, first, last))
        if (m.id == unreadAfter && i < units.size - 1) rows.add(Row.Unread)
    }
    return rows
}
