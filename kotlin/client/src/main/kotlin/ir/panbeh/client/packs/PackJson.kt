package ir.panbeh.client.packs

import ir.panbeh.core.packs.PackContent
import ir.panbeh.core.packs.PackImage
import ir.panbeh.core.packs.PackMeta
import ir.panbeh.core.packs.Usage
import ir.panbeh.core.util.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val usageName = mapOf(Usage.EMOTICON to "emoticon", Usage.STICKER to "sticker")

private fun usageOf(e: JsonElement?): List<Usage>? = (e as? JsonArray)?.mapNotNull { x ->
    when ((x as? JsonPrimitive)?.takeIf { it.isString }?.content) { "emoticon" -> Usage.EMOTICON; "sticker" -> Usage.STICKER; else -> null }
}

private fun usageJson(l: List<Usage>) = JsonArray(l.map { JsonPrimitive(usageName.getValue(it)) })

/** An im.ponies pack's JSON (user_emotes account data or room_emotes state content) as core's model. */
fun parsePack(o: JsonObject?): PackContent {
    if (o == null) return PackContent()
    val meta = (o["pack"] as? JsonObject)?.let { PackMeta(it.string("display_name"), it.string("avatar_url"), usageOf(it["usage"])) }
    val images = (o["images"] as? JsonObject)?.mapNotNull { (code, v) ->
        val i = v as? JsonObject ?: return@mapNotNull null
        code to PackImage(i.string("url") ?: return@mapNotNull null, i.string("body"), i["info"] as? JsonObject, usageOf(i["usage"]))
    }?.toMap()
    return PackContent(meta, images)
}

internal fun PackImage.toJson() = buildJsonObject {
    put("url", url)
    body?.let { put("body", it) }
    info?.let { put("info", it) }
    usage?.let { put("usage", usageJson(it)) }
}

internal fun JsonObject.imagesRaw(): Map<String, JsonElement> = (this["images"] as? JsonObject).orEmpty()
internal fun JsonObject.withImages(images: Map<String, JsonElement>) = JsonObject(this + ("images" to JsonObject(images)))
internal fun JsonObject.withPack(patch: Map<String, JsonElement>) = JsonObject(this + ("pack" to JsonObject(((this["pack"] as? JsonObject).orEmpty()) + patch)))
internal fun usageArray(l: List<Usage>): JsonElement = usageJson(l)
