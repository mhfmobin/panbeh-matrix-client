package ir.panbeh.core.prefs

import ir.panbeh.core.media.AutoDl
import ir.panbeh.core.media.mergeAutoDl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val ACCENTS = listOf("#007ded", "#8774e1", "#40a7a0", "#e5864a", "#e0578b", "#4fae4e")
val WALLPAPERS = linkedMapOf("doodle" to "طرح‌دار", "gradient" to "گرادیان", "plain" to "ساده")
val THEMES = linkedMapOf("system" to "سیستم", "light" to "روشن", "dark" to "تیره")
val CAM_Q = listOf("360" to "۳۶۰p", "540" to "۵۴۰p", "720" to "۷۲۰p", "1080" to "۱۰۸۰p")
val NOTE_Q = listOf("384" to "۳۸۴p", "480" to "۴۸۰p", "640" to "۶۴۰p")
val AUDIO_Q = listOf("low" to "کم", "normal" to "معمولی", "high" to "بالا")
val CACHE_MB = listOf(250 to "۲۵۰ مگابایت", 500 to "۵۰۰ مگابایت", 1024 to "۱ گیگابایت", 2048 to "۲ گیگابایت", 5120 to "۵ گیگابایت")
val LOW_DATA = listOf("off" to "خاموش", "auto" to "خودکار، با اتصال ضعیف", "on" to "همیشه")
const val CACHE_MB_DEFAULT = 1024

/** The web app's `panbeh.prefs`, same keys and defaults (Android build). */
@Serializable
data class Prefs(
    val theme: String = "system",
    val accent: String = ACCENTS[0],
    val wallpaper: String = "doodle",
    val notify: Boolean = true,
    val notifyDMs: Boolean = true,
    val notifyGroups: Boolean = true,
    /** all | name | none */
    val notifyPreview: String = "all",
    val previews: Boolean = true,
    val shareLastSeen: Boolean = true,
    val searchIndex: Boolean = true,
    val dev: Boolean = false,
    val legacyCalls: Boolean = false,
    val enterSends: Boolean = false, // on a phone keyboard Enter makes a new line
    val askSave: Boolean = false,
    val camQuality: String = "720",
    val screenQuality: String = "1080hi",
    val audioQuality: String = "high",
    val lowData: String = "off",
    val cacheMB: Int = CACHE_MB_DEFAULT,
    /** stored rules, merged with the defaults by [autoDownload] */
    val autoDl: JsonObject? = null,
    /** voice | video: what the mic button records */
    val recordMode: String = "voice",
    val noteQuality: String = "640",
) {
    val autoDownload: AutoDl get() = mergeAutoDl(autoDl)
    val legacyCallsOn: Boolean get() = dev && legacyCalls

    fun encode(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
        fun decode(s: String?): Prefs = s?.let { runCatching { json.decodeFromString<Prefs>(it) }.getOrNull() }
            ?.let { if (it.accent == "#3390ec") it.copy(accent = ACCENTS[0]) else it } // the old default blue
            ?: Prefs()
    }
}
