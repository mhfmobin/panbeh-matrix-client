package ir.panbeh.core.util

import kotlinx.serialization.json.*

/** JS truthiness of a JSON value (missing/null/false/0/"" are falsy). */
fun JsonElement?.truthy(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonPrimitive -> if (isString) content.isNotEmpty() else (booleanOrNull ?: doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: true)
    else -> true
}

/** The string at `key`, or null when absent or not a string. */
fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The number at `key`, or null when absent or not a number. */
fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

/** A number the way JS prints it: integral values without ".0". */
fun jsNum(d: Double): String = if (d.isFinite() && d == Math.floor(d) && Math.abs(d) < 1e21) d.toLong().toString() else d.toString()
