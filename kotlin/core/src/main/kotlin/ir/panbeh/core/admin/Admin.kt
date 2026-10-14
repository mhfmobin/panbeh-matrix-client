package ir.panbeh.core.admin

import ir.panbeh.core.time.num

/** "مدیر" / "ناظر" / "سطح ۲۵"; null for plain members. Infinity = a room v12 creator, who outranks everyone. */
fun roleLabel(level: Double): String? =
    if (level == Double.POSITIVE_INFINITY) "مالک" else if (level >= 100) "مدیر" else if (level >= 50) "ناظر" else if (level > 0) "سطح ${num(level)}" else null

/** m.room.power_levels content (levels are doubles so a creator can be Infinity). */
data class PowerLevels(
    val users: Map<String, Double>? = null, val usersDefault: Double? = null,
    val events: Map<String, Double>? = null, val eventsDefault: Double? = null, val stateDefault: Double? = null,
    val invite: Double? = null, val kick: Double? = null, val ban: Double? = null, val redact: Double? = null,
    val notifications: Map<String, Double>? = null,
)

/** A power-level setting by key, with the spec's defaults: "kick", "notifications.room", "events.<state type>" (falls back
 *  to state_default). "events_default" means sending messages, so an m.room.message override wins over it. */
fun levelAt(pl: PowerLevels, key: String): Double {
    if (key.startsWith("events.")) return pl.events?.get(key.substring(7)) ?: pl.stateDefault ?: 50.0
    if (key.startsWith("notifications.")) return pl.notifications?.get(key.substring(14)) ?: 50.0
    return when (key) {
        "events_default" -> pl.events?.get("m.room.message") ?: pl.eventsDefault ?: 0.0
        "invite" -> pl.invite ?: 0.0
        "kick" -> pl.kick ?: 50.0
        "ban" -> pl.ban ?: 50.0
        "redact" -> pl.redact ?: 50.0
        "state_default" -> pl.stateDefault ?: 50.0
        "users_default" -> pl.usersDefault ?: 50.0
        else -> 50.0
    }
}

/** New m.room.power_levels content with one setting changed (keys as in levelAt; unknown plain keys leave it unchanged). */
fun withLevel(pl: PowerLevels, key: String, level: Double): PowerLevels {
    if (key.startsWith("events.")) return pl.copy(events = (pl.events ?: emptyMap()) + (key.substring(7) to level))
    if (key.startsWith("notifications.")) return pl.copy(notifications = (pl.notifications ?: emptyMap()) + (key.substring(14) to level))
    return when (key) {
        "events_default" -> pl.copy(eventsDefault = level, events = (pl.events ?: emptyMap()) - "m.room.message")
        "invite" -> pl.copy(invite = level)
        "kick" -> pl.copy(kick = level)
        "ban" -> pl.copy(ban = level)
        "redact" -> pl.copy(redact = level)
        "state_default" -> pl.copy(stateDefault = level)
        "users_default" -> pl.copy(usersDefault = level)
        else -> pl
    }
}

/** Select options for a level: the presets I may grant, plus the current value if it's a custom one. */
fun levelOptions(presets: List<Pair<Double, String>>, mine: Double, current: Double): List<Pair<Double, String>> {
    val o = presets.filter { (l) -> l <= mine || l == current }.toMutableList()
    if (o.none { it.first == current }) o.add(current to if (current == Double.POSITIVE_INFINITY) "مالک" else if (current > 0) "سطح ${num(current)}" else "عضو")
    return o.sortedBy { it.first }
}

/** Room version features; unknown or non-numeric (experimental) versions count as unsupported. */
private fun versionAtLeast(version: String?, n: Int) = version != null && version.isNotEmpty() && version.all { it in '0'..'9' } && version.toDouble() >= n
fun supportsKnock(version: String?) = versionAtLeast(version, 7)
fun supportsRestricted(version: String?) = versionAtLeast(version, 8)
fun supportsKnockRestricted(version: String?) = versionAtLeast(version, 10)

/** The server's room versions capability: its default and each available version's stability. */
data class RoomVersions(val default: String, val available: Map<String, String>)

/** The server's default room version when the room should move to it: an older number, or a version the server marks
 *  unstable or doesn't know. Null when it's fine (or the server didn't say). */
fun upgradeTarget(version: String, rv: RoomVersions? = null): String? {
    if (rv == null || rv.default.isEmpty() || version == rv.default) return null
    if (versionAtLeast(version, 0) && versionAtLeast(rv.default, 0)) return if (version.toDouble() < rv.default.toDouble()) rv.default else null
    return if (rv.available[version] != "stable") rv.default else null
}

val JOIN_RULES = mapOf(
    "public" to "عمومی؛ هر کسی می‌تواند بپیوندد", "invite" to "خصوصی؛ فقط با دعوت", "knock" to "با درخواست عضویت",
    "restricted" to "اعضای فضا", "knock_restricted" to "اعضای فضا، یا با درخواست عضویت",
)
val HISTORY = mapOf("shared" to "اعضا، از ابتدا", "invited" to "اعضا، از زمان دعوت", "joined" to "اعضا، از زمان پیوستن", "world_readable" to "همه، حتی بدون عضویت")

private val ERRORS = mapOf(
    "M_FORBIDDEN" to "اجازه‌ی این کار را ندارید.",
    "M_ROOM_IN_USE" to "این نشانی قبلاً گرفته شده است.",
    "M_EXCLUSIVE" to "این نشانی برای سرویس دیگری (مثل پل ارتباطی) رزرو شده است.",
    "M_UNSUPPORTED_ROOM_VERSION" to "سرور از این نسخه‌ی گفتگو پشتیبانی نمی‌کند.",
    "M_TOO_LARGE" to "حجم آن برای سرور زیاد است.",
    "M_RESOURCE_LIMIT_EXCEEDED" to "سرور به سقف منابع خود رسیده است؛ با مدیر سرور تماس بگیرید.",
    "M_NOT_FOUND" to "پیدا نشد.",
    "M_UNKNOWN_TOKEN" to "نشست شما منقضی شده است؛ دوباره وارد شوید.",
    "M_CANNOT_LEAVE_SERVER_NOTICE_ROOM" to "نمی‌توان از گفتگوی اطلاعیه‌های سرور خارج شد.",
    "M_GUEST_ACCESS_FORBIDDEN" to "مهمان‌ها به این گفتگو دسترسی ندارند.",
)

/** Persian text for a Matrix error code; null = show the server's own message. `retryAfterMs` is the error's retry_after_ms. */
fun errorText(errcode: String?, retryAfterMs: Long? = null): String? {
    if (errcode == "M_LIMIT_EXCEEDED") {
        val s = Math.ceil((retryAfterMs ?: 0L) / 1000.0).toLong()
        return "درخواست‌ها زیاد بود" + if (s > 0) "؛ ${num(s)} ثانیه‌ی دیگر دوباره امتحان کنید." else "؛ کمی بعد دوباره امتحان کنید."
    }
    return errcode?.let { ERRORS[it] }
}

/** A user whose power level changed. */
data class LevelChange(val id: String, val from: Double, val to: Double)

/** Users whose power level differs between two m.room.power_levels contents (missing = users_default). */
fun levelChanges(prev: PowerLevels, next: PowerLevels): List<LevelChange> {
    fun at(c: PowerLevels, id: String) = c.users?.get(id) ?: c.usersDefault ?: 0.0
    val ids = LinkedHashSet<String>().apply { addAll(prev.users?.keys.orEmpty()); addAll(next.users?.keys.orEmpty()) }
    return ids.filter { at(prev, it) != at(next, it) }.map { LevelChange(it, at(prev, it), at(next, it)) }
}
