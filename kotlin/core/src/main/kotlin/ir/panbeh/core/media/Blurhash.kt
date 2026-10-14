package ir.panbeh.core.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign

// ---------- blurhash (MSC2448: info["xyz.amorgan.blurhash"]) ----------
// Pixels are IntArrays of packed ARGB (android.graphics.Color / Bitmap.getPixels layout), row-major, w × h.

private const val B83 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"
private fun enc83(n: Int, len: Int): String {
    var div = 1
    repeat(len - 1) { div *= 83 }
    val s = StringBuilder()
    repeat(len) { s.append(B83[(n / div) % 83]); div = maxOf(1, div / 83) }
    return s.toString()
}
private fun dec83(s: String) = s.fold(0) { n, c -> n * 83 + B83.indexOf(c) }
private fun toLinear(v: Int): Double { val x = v / 255.0; return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
private fun toSrgb(v: Double): Int { val x = v.coerceIn(0.0, 1.0); return ((if (x <= 0.0031308) x * 12.92 else 1.055 * x.pow(1 / 2.4) - 0.055) * 255 + 0.5).toInt() }
private fun signPow(v: Double, e: Double) = sign(v) * abs(v).pow(e)

/** ARGB pixels (keep them small, ~32px: the cost is pixels × components) → blurhash. */
fun blurhashEncode(px: IntArray, w: Int, h: Int, cx: Int = 4, cy: Int = 3): String {
    val f = ArrayList<DoubleArray>()
    for (j in 0 until cy) for (i in 0 until cx) {
        val norm = if (i != 0 || j != 0) 2.0 else 1.0
        val rgb = DoubleArray(3)
        for (y in 0 until h) for (x in 0 until w) {
            val basis = norm * cos(PI * i * x / w) * cos(PI * j * y / h)
            val p = px[x + y * w]
            rgb[0] += basis * toLinear((p shr 16) and 255)
            rgb[1] += basis * toLinear((p shr 8) and 255)
            rgb[2] += basis * toLinear(p and 255)
        }
        f.add(DoubleArray(3) { rgb[it] / (w * h) })
    }
    val dc = f[0]
    val ac = f.drop(1)
    val q = if (ac.isNotEmpty()) Math.floor(ac.maxOf { c -> c.maxOf { abs(it) } } * 166 - 0.5).toInt().coerceIn(0, 82) else 0
    val maxV = (q + 1) / 166.0
    fun quant(v: Double) = Math.floor(signPow(v / maxV, 0.5) * 9 + 9.5).toInt().coerceIn(0, 18)
    return enc83(cx - 1 + (cy - 1) * 9, 1) + enc83(q, 1) + enc83((toSrgb(dc[0]) shl 16) + (toSrgb(dc[1]) shl 8) + toSrgb(dc[2]), 4) +
        ac.joinToString("") { c -> enc83(quant(c[0]) * 361 + quant(c[1]) * 19 + quant(c[2]), 2) }
}

/** blurhash → w×h opaque ARGB pixels; null if it isn't a valid hash (it comes from other people's events). */
fun blurhashDecode(hash: String, w: Int, h: Int): IntArray? {
    if (hash.length < 6 || hash.any { it !in B83 }) return null
    val n = dec83(hash.substring(0, 1))
    val cy = n / 9 + 1
    val cx = n % 9 + 1
    if (hash.length != 4 + 2 * cx * cy) return null
    val maxV = (dec83(hash.substring(1, 2)) + 1) / 166.0
    val dc = dec83(hash.substring(2, 6))
    val colors = ArrayList<DoubleArray>()
    colors.add(doubleArrayOf(toLinear(dc shr 16), toLinear((dc shr 8) and 255), toLinear(dc and 255)))
    for (i in 1 until cx * cy) {
        val v = dec83(hash.substring(4 + 2 * i, 6 + 2 * i))
        colors.add(doubleArrayOf((v / 361).toDouble(), ((v / 19) % 19).toDouble(), (v % 19).toDouble()).map { d -> signPow((d - 9) / 9, 2.0) * maxV }.toDoubleArray())
    }
    val px = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val rgb = DoubleArray(3)
        for (j in 0 until cy) for (i in 0 until cx) {
            val basis = cos(PI * x * i / w) * cos(PI * y * j / h)
            val c = colors[i + j * cx]
            for (k in 0..2) rgb[k] += c[k] * basis
        }
        px[x + y * w] = (255 shl 24) or (toSrgb(rgb[0]) shl 16) or (toSrgb(rgb[1]) shl 8) or toSrgb(rgb[2])
    }
    return px
}
