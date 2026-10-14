package ir.panbeh.core.calls

import ir.panbeh.core.util.jsNum

// ---------- call stats (the overlay on each feed) ----------

/** One feed's numbers; bytes/ts carry over to the next sample for the bitrate. loss in %, rtt in ms. */
data class Stats(
    val bytes: Double, val ts: Double,
    val w: Double? = null, val h: Double? = null, val fps: Double? = null, val kbps: Long? = null,
    val loss: Double? = null, val rtt: Long? = null, val codec: String? = null,
)

enum class StatsDir { IN, OUT }

private fun Map<String, Any?>.n(k: String) = (this[k] as? Number)?.toDouble()

/**
 * A WebRTC stats report (its values, each a map of the RTCStats fields) → one feed's numbers: received (in) or sent (out).
 * Video wins over audio; simulcast layers add up. prev: the last sample, for the bitrate.
 */
fun parseStats(entries: Iterable<Map<String, Any?>>, dir: StatsDir, prev: Stats? = null, now: Long = System.currentTimeMillis()): Stats {
    val all = entries.toList()
    val type = if (dir == StatsDir.IN) "inbound-rtp" else "outbound-rtp"
    val rtp = all.filter { it["type"] == type }
    val kind = if (rtp.any { it["kind"] == "video" }) "video" else "audio"
    val mine = rtp.filter { it["kind"] == kind }
    fun max(k: String): Double? = mine.fold(null as Double?) { m, e -> e.n(k)?.takeIf { it > (m ?: -1.0) } ?: m }
    fun sum(k: String) = mine.sumOf { it.n(k) ?: 0.0 }
    val ts = mine.firstOrNull()?.n("timestamp") ?: now.toDouble()
    val bytes = sum(if (dir == StatsDir.IN) "bytesReceived" else "bytesSent")
    var s = Stats(bytes, ts, w = max("frameWidth"), h = max("frameHeight"), fps = max("framesPerSecond"))
    if (prev != null && ts > prev.ts && bytes >= prev.bytes) s = s.copy(kbps = Math.round((bytes - prev.bytes) * 8 / (ts - prev.ts)))
    if (dir == StatsDir.IN) {
        val lost = sum("packetsLost")
        val got = sum("packetsReceived")
        if (lost + got > 0) s = s.copy(loss = Math.round(1000 * lost / (lost + got)) / 10.0)
    } else {
        val remote = all.filter { it["type"] == "remote-inbound-rtp" && it["kind"] == kind && it["fractionLost"] is Number }
        if (remote.isNotEmpty()) s = s.copy(loss = Math.round(1000 * remote.maxOf { it.n("fractionLost")!! }) / 10.0)
    }
    val pair = all.firstOrNull { it["type"] == "candidate-pair" && it["nominated"] == true && it["state"] == "succeeded" && it["currentRoundTripTime"] is Number }
    if (pair != null) s = s.copy(rtt = Math.round(pair.n("currentRoundTripTime")!! * 1000))
    val codecId = mine.firstOrNull()?.get("codecId")
    val codec = all.firstOrNull { it["type"] == "codec" && it["id"] == codecId }
    codec?.get("mimeType")?.takeIf { it != "" }?.let { s = s.copy(codec = it.toString().split("/").getOrNull(1)) }
    return s
}

/** "1280×720 · 30fps · 1200kbps · 0.5% · 40ms · VP8" */
fun fmtStats(s: Stats) = listOf(
    if (s.w != null && s.h != null && s.w != 0.0 && s.h != 0.0) "${jsNum(s.w)}×${jsNum(s.h)}" else "",
    if (s.fps != null && s.fps != 0.0) "${Math.round(s.fps)}fps" else "",
    if (s.kbps != null) "${s.kbps}kbps" else "",
    if (s.loss != null) "${jsNum(s.loss)}%" else "",
    if (s.rtt != null) "${s.rtt}ms" else "",
    s.codec ?: "",
).filter { it.isNotEmpty() }.joinToString(" · ")
