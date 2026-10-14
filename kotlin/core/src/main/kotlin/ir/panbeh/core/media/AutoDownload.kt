package ir.panbeh.core.media

import kotlinx.serialization.json.*

enum class DlKind { PHOTOS, VIDEOS, FILES, GIFS, VOICE }

/** Who auto-download applies to. maxMB: 0 = no limit, null = the kind has no size cap. */
data class DlWho(val dm: Boolean, val group: Boolean, val maxMB: Int? = null)

/** voice: voice messages and round videos. */
data class DlRules(val photos: DlWho, val videos: DlWho, val files: DlWho, val gifs: DlWho, val voice: DlWho) {
    operator fun get(kind: DlKind) = when (kind) {
        DlKind.PHOTOS -> photos; DlKind.VIDEOS -> videos; DlKind.FILES -> files; DlKind.GIFS -> gifs; DlKind.VOICE -> voice
    }
}

data class AutoDl(val mobile: DlRules, val wifi: DlRules)

private val EVERYWHERE = DlWho(dm = true, group = true)
private fun rules(videosMB: Int, filesMB: Int) = DlRules(EVERYWHERE, EVERYWHERE.copy(maxMB = videosMB), EVERYWHERE.copy(maxMB = filesMB), EVERYWHERE, EVERYWHERE)

val AUTO_DL_DEFAULT = AutoDl(wifi = rules(50, 50), mobile = rules(10, 1))

private fun merged(base: DlWho, s: JsonElement?): DlWho {
    val o = s as? JsonObject ?: return base
    return DlWho(
        (o["dm"] as? JsonPrimitive)?.booleanOrNull ?: base.dm,
        (o["group"] as? JsonPrimitive)?.booleanOrNull ?: base.group,
        (o["maxMB"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: base.maxMB,
    )
}

private fun net(d: DlRules, s: JsonElement?): DlRules {
    val o = s as? JsonObject
    return DlRules(merged(d.photos, o?.get("photos")), merged(d.videos, o?.get("videos")), merged(d.files, o?.get("files")), merged(d.gifs, o?.get("gifs")), merged(d.voice, o?.get("voice")))
}

/** Stored rules (parsed JSON, any shape) over the defaults, per network and kind, so kinds added later get their defaults.
 *  Stored fields of the wrong type are ignored. */
fun mergeAutoDl(stored: JsonElement?): AutoDl {
    val s = stored as? JsonObject
    return AutoDl(mobile = net(AUTO_DL_DEFAULT.mobile, s?.get("mobile")), wifi = net(AUTO_DL_DEFAULT.wifi, s?.get("wifi")))
}

/** Whether media of `kind` (and `size` bytes, if known) downloads by itself in this kind of chat. */
fun shouldAutoDl(rules: DlRules, kind: DlKind, isDM: Boolean, size: Long? = null): Boolean {
    val r = rules[kind]
    if (!(if (isDM) r.dm else r.group)) return false
    return r.maxMB == null || r.maxMB == 0 || size == null || size == 0L || size <= r.maxMB.toLong() * 1024 * 1024
}
