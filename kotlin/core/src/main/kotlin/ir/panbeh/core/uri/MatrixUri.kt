package ir.panbeh.core.uri

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Parsing of matrix.to links and `matrix:` URIs (MSC2312 / Matrix spec appendix).
 * Pure: no SDK, no Android. Port of src/uri.ts.
 */
data class Target(
    val kind: Kind,
    /** Full identifier with sigil: @user:server, !room:server or #alias:server. */
    val id: String,
    val eventId: String? = null,
    val via: List<String> = emptyList(),
    val action: Action? = null,
) {
    enum class Kind { USER, ROOM, ROOM_ALIAS }
    enum class Action { JOIN, CHAT }
}

/** JS `decodeURIComponent`: strict UTF-8 percent-decoding, null where JS would throw URIError. */
private fun decodeComponent(s: String, plusIsSpace: Boolean = false): String? {
    if (s.indexOf('%') < 0 && !(plusIsSpace && s.indexOf('+') >= 0)) return s
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%') {
            val bytes = ByteArrayOutputStream()
            while (i < s.length && s[i] == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            }
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            try {
                out.append(dec.decode(ByteBuffer.wrap(bytes.toByteArray())))
            } catch (_: java.nio.charset.CharacterCodingException) {
                return null
            }
        } else {
            out.append(if (plusIsSpace && c == '+') ' ' else c)
            i++
        }
    }
    return out.toString()
}

// (?U): JS \s / \S are Unicode-aware.
private val USER = Regex("(?U)^@[^:\\s]+:\\S+$")
private val ROOM = Regex("(?U)^![^\\s]+$")
private val ALIAS = Regex("(?U)^#[^:\\s]+:\\S+$")
private val EVENT = Regex("(?U)^\\$\\S+$")

/** `URLSearchParams` parsing: `&`-separated, first `=` splits, `+` is space, lenient percent-decoding. */
private fun parseQuery(query: String): List<Pair<String, String>> =
    query.split('&').filter { it.isNotEmpty() }.map { part ->
        val eq = part.indexOf('=')
        val k = if (eq < 0) part else part.substring(0, eq)
        val v = if (eq < 0) "" else part.substring(eq + 1)
        (decodeComponent(k, true) ?: k) to (decodeComponent(v, true) ?: v)
    }

private fun build(id: String?, eventId: String?, query: String): Target? {
    if (id.isNullOrEmpty()) return null
    val kind = when {
        USER.matches(id) -> Target.Kind.USER
        ALIAS.matches(id) -> Target.Kind.ROOM_ALIAS
        ROOM.matches(id) -> Target.Kind.ROOM
        else -> return null
    }
    if (eventId != null && (kind == Target.Kind.USER || !EVENT.matches(eventId))) return null
    val q = parseQuery(query)
    val action = when (q.firstOrNull { it.first == "action" }?.second) {
        "join" -> Target.Action.JOIN
        "chat" -> Target.Action.CHAT
        else -> null
    }
    return Target(
        kind = kind,
        id = id,
        eventId = eventId?.takeIf { it.isNotEmpty() },
        via = q.filter { it.first == "via" }.map { it.second }.filter { it.isNotEmpty() },
        action = action,
    )
}

private val MATRIX_TO = Regex("^https://matrix\\.to/#/([^?]*)(?:\\?(.*))?$", RegexOption.IGNORE_CASE)

/** `https://matrix.to/#/<id>[/<$event>][?via=…]` */
private fun fromMatrixTo(s: String): Target? {
    val m = MATRIX_TO.matchEntire(s) ?: return null
    val segs = m.groupValues[1].split("/")
    if (segs.size > 2) return null
    val a = segs[0]
    val b = segs.getOrNull(1)
    // As in the TS: an undecodable event segment is treated as absent (null), not as a rejection.
    val eventId = if (b == null || b.isEmpty()) null else decodeComponent(b)
    return build(decodeComponent(a), eventId, m.groups[2]?.value ?: "")
}

private val MATRIX_URI = Regex("^matrix:(?://[^/]*/)?([^?#]*)(?:\\?([^#]*))?(?:#.*)?$", RegexOption.IGNORE_CASE)

/** `matrix:u/user:server`, `matrix:r/alias:server[/e/event]`, `matrix:roomid/room:server[/e/event]` */
private fun fromMatrixUri(s: String): Target? {
    val m = MATRIX_URI.matchEntire(s) ?: return null
    val parts = m.groupValues[1].split("/")
    val sigil = when (parts[0].lowercase()) {
        "u" -> "@"
        "r" -> "#"
        "roomid" -> "!"
        else -> return null
    }
    val name = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { decodeComponent(it) }
    if (name.isNullOrEmpty()) return null
    var eventId: String? = null
    if (parts.size == 4 && parts[2] == "e" && parts[3].isNotEmpty()) {
        val e = decodeComponent(parts[3])
        if (e.isNullOrEmpty()) return null
        eventId = "$$e"
    } else if (parts.size != 2) return null
    return build(sigil + name, eventId, m.groups[2]?.value ?: "")
}

fun parseMatrixLink(s: String): Target? {
    val t = s.trim()
    return if (t.startsWith("matrix:", ignoreCase = true)) fromMatrixUri(t) else fromMatrixTo(t)
}

/** The `matrix.to` hash form (`#/!room:server/$event`) of a page hash such as location.hash. */
fun parseMatrixHash(hash: String): Target? =
    if (hash.startsWith("#/")) fromMatrixTo("https://matrix.to/$hash") else null

/**
 * Up to 3 homeservers of the room's joined members, most members first (what Element sends as ?via=).
 *
 * Deviation from TS: instead of a matrix-js-sdk `Room`, takes the user IDs of the room's joined members
 * (`@user:server`, one per member). Servers with equal counts keep first-seen order.
 */
fun viaServers(joinedMemberIds: List<String>): List<String> {
    val n = LinkedHashMap<String, Int>()
    for (u in joinedMemberIds) {
        val s = u.substring(u.indexOf(':') + 1)
        n[s] = (n[s] ?: 0) + 1
    }
    return n.entries.sortedByDescending { it.value }.take(3).map { it.key }
}

/** Same as [viaServers] for callers that already have a server -> joined-member-count map (iteration order breaks ties). */
fun viaServers(memberCountByServer: Map<String, Int>): List<String> =
    memberCountByServer.entries.sortedByDescending { it.value }.take(3).map { it.key }

private fun percentEncode(s: String, keep: (Char) -> Boolean, spaceAsPlus: Boolean = false): String {
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = (b.toInt() and 0xff).toChar()
        when {
            b >= 0 && keep(c) -> sb.append(c)
            spaceAsPlus && c == ' ' -> sb.append('+')
            else -> sb.append('%').append("%02X".format(b.toInt() and 0xff))
        }
    }
    return sb.toString()
}

/** JS `encodeURIComponent`. */
private fun encodeURIComponent(s: String) = percentEncode(s, { it.isLetterOrDigit() && it.code < 128 || it in "-_.!~*'()" })

/** `application/x-www-form-urlencoded` (URLSearchParams serialisation). */
private fun formEncode(s: String) = percentEncode(s, { it.isLetterOrDigit() && it.code < 128 || it in "*-._" }, spaceAsPlus = true)

/** `https://matrix.to/#/<id>[/<$event>][?via=…]`: the inverse of [parseMatrixLink] for matrix.to links. */
fun matrixToLink(id: String, eventId: String? = null, via: List<String> = emptyList()): String =
    "https://matrix.to/#/" + encodeURIComponent(id) +
        (if (!eventId.isNullOrEmpty()) "/" + encodeURIComponent(eventId) else "") +
        (if (via.isNotEmpty()) "?" + via.joinToString("&") { "via=" + formEncode(it) } else "")
