package ir.panbeh.client.packs

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.client.user.getAccountData
import ir.panbeh.client.KeyValueStore
import ir.panbeh.client.events.SavedGifs
import ir.panbeh.core.util.string
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/** A saved GIF: the message content without caption, reply or forward marks. */
typealias Gif = JsonObject

/** mautrix's flags marking an uploaded video as a GIF: autoplays, loops, muted, no controls. */
val GIF_INFO: JsonObject = buildJsonObject {
    put("fi.mau.gif", true); put("fi.mau.loop", true); put("fi.mau.autoplay", true); put("fi.mau.hide_controls", true); put("fi.mau.no_audio", true)
}

private const val MAX_GIFS = 100

/** The identity of a GIF: its url, or the url of its encrypted file. */
fun gifKey(g: Gif): String? = g.string("url") ?: (g["file"] as? JsonObject)?.string("url")

/** A message's content reduced to what a saved GIF keeps. */
fun gifOf(content: JsonObject): Gif = buildJsonObject {
    content["msgtype"]?.let { put("msgtype", it) }
    put("body", "gif")
    content["info"]?.let { put("info", it) }
    if (content.string("url") != null) put("url", content.getValue("url")) else content["file"]?.let { put("file", it) }
}

/** Saved GIFs (app.panbeh.gifs): newest first, at most 100, one per url. */
class Gifs(private val client: MatrixClient) {
    val saved: Flow<List<Gif>> = client.user.getAccountData<SavedGifs>().map(::parse)

    private fun parse(c: SavedGifs?): List<Gif> = (c?.raw?.get("gifs") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private suspend fun current() = parse(client.user.getAccountData<SavedGifs>().first())

    private suspend fun put(l: List<Gif>) {
        client.api.user.setAccountData(SavedGifs(buildJsonObject { put("gifs", JsonArray(l)) }), client.userId).getOrThrow()
    }

    suspend fun isSaved(content: JsonObject): Boolean = gifKey(gifOf(content)).let { k -> current().any { gifKey(it) == k } }

    /** Saves [content] first; saving it again moves it up instead of duplicating. */
    suspend fun save(content: JsonObject) {
        val g = gifOf(content)
        put((listOf(g) + current().filter { gifKey(it) != gifKey(g) }).take(MAX_GIFS))
    }

    suspend fun remove(content: JsonObject) {
        val k = gifKey(gifOf(content))
        put(current().filter { gifKey(it) != k })
    }

    /** Saves it, or removes it when it's saved already. Returns whether it is saved now. */
    suspend fun toggle(content: JsonObject): Boolean = if (isSaved(content)) { remove(content); false } else { save(content); true }
}

/** The emoji picker's recent row (local only): newest first, 24. */
class RecentEmoji(private val store: KeyValueStore) {
    fun list(): List<String> = runCatching { Json.parseToJsonElement(store[KEY] ?: "[]").jsonArray.map { (it as JsonPrimitive).content } }.getOrDefault(emptyList())

    /** Moves [e] (an emoji, or an mxc:// URI for a custom one) to the front. */
    fun add(e: String) {
        runCatching { store[KEY] = JsonArray((listOf(e) + list().filter { it != e }).take(MAX).map(::JsonPrimitive)).toString() }
    }

    private companion object { const val KEY = "panbeh.recentEmoji"; const val MAX = 24 }
}
