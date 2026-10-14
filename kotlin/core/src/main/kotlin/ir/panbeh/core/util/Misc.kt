package ir.panbeh.core.util

import java.net.URI
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val NOT_ENDPOINT = Regex("(client|media|v\\d+|r0|unstable)")

/** Endpoint name for the net log: /_matrix/client/v3/rooms/!x/send/… → rooms. */
fun endpointOf(url: String): String =
    (URI(url).rawPath?.ifEmpty { null } ?: "/").split("/").drop(2).firstOrNull { !NOT_ENDPOINT.matches(it) } ?: "?"

/** Oldest-first (key, size) entries: the keys to drop, oldest first, so the rest fit in `cap` bytes. */
fun toEvict(entries: List<Pair<String, Long>>, cap: Long): List<String> {
    var total = entries.sumOf { it.second }
    val out = ArrayList<String>()
    for ((key, size) in entries) {
        if (total <= cap) break
        out.add(key)
        total -= size
    }
    return out
}

/** The app lock's PIN check value: PBKDF2-SHA-256 over the PIN (UTF-8) with a per-device salt (UTF-8), 256 bits, base64. Slow on purpose. */
fun hashPin(pin: String, salt: String, iterations: Int): String {
    val spec = PBEKeySpec(pin.toCharArray(), salt.toByteArray(Charsets.UTF_8), iterations, 256)
    return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
}

/** Wait (ms) before the next PIN try after `fails` wrong ones: none for the first 5, then 30s doubling, at most an hour. */
fun lockDelay(fails: Int): Long = if (fails < 5) 0 else minOf(3_600_000.0, 30_000 * Math.pow(2.0, (fails - 5).toDouble())).toLong()

/** One axis of a menu opened at `v`: after it, or before it when it would cross `max`; 8px from the edges. */
fun fitMenu(v: Double, size: Double, max: Double) = maxOf(8.0, minOf(if (v + size > max - 8) v - size else v, max - size - 8))
