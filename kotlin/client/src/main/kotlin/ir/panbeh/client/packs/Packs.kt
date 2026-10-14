package ir.panbeh.client.packs

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.getAllState
import de.connect2x.trixnity.client.room.getState
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.client.user.getAccountData
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.core.model.events.m.room.PowerLevelsEventContent
import ir.panbeh.client.admin.core
import ir.panbeh.client.events.EmoteRooms
import ir.panbeh.client.events.RoomEmotes
import ir.panbeh.client.events.Types
import ir.panbeh.client.events.UserEmotes
import ir.panbeh.client.account.uploadPlain
import ir.panbeh.core.admin.levelAt
import ir.panbeh.core.packs.PACK_CAP
import ir.panbeh.core.packs.PackContent
import ir.panbeh.core.packs.PackImage
import ir.panbeh.core.packs.Usage
import ir.panbeh.core.packs.cleanCode
import ir.panbeh.core.packs.codeFrom
import ir.panbeh.core.packs.freeCode
import ir.panbeh.core.packs.packImages
import ir.panbeh.core.time.num
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where a pack lives: the personal one (account data) when [roomId] is null, else a room's, under [stateKey]. */
data class PackRef(val roomId: String? = null, val stateKey: String? = null) {
    val key get() = if (roomId == null) "me" else "$roomId|$stateKey"
}

/** A pack with its parsed content; [raw] is the stored JSON (kept so unknown fields survive edits). */
data class Pack(val ref: PackRef, val name: String, val avatar: String?, val content: PackContent, val raw: JsonObject) {
    val key get() = ref.key
    val roomId get() = ref.roomId
    val stateKey get() = ref.stateKey
}

/** An emoji or sticker usable in a chat: its shortcode, image and pack. */
data class Emote(val code: String, val img: PackImage, val pack: Pack)

/** The outcome of adding files: how many went in and how many didn't fit the cap. */
data class AddResult(val added: Int, val skippedFull: Int)

private val EMPTY = JsonObject(emptyMap())
private val LABEL = mapOf(Usage.EMOTICON to "اموجی", Usage.STICKER to "استیکر")

/** MSC2545 image packs (im.ponies.*): my personal pack, room packs, and the packs switched on for every chat. */
@OptIn(ExperimentalCoroutinesApi::class)
class Packs(private val client: MatrixClient) {
    private val api = client.api

    private val personalRaw: Flow<JsonObject> = client.user.getAccountData<UserEmotes>().map { it?.raw ?: EMPTY }

    /** My personal pack's content (emoji and stickers share it, told apart by usage). */
    val personal: Flow<PackContent> = personalRaw.map { parsePack(it) }

    private fun roomName(id: RoomId) = client.room.getById(id).map { it?.name?.explicitName ?: id.full }

    private fun pack(roomId: RoomId, key: String, raw: JsonObject, roomName: String): Pack {
        val c = parsePack(raw)
        return Pack(PackRef(roomId.full, key), c.pack?.displayName?.takeIf { it.isNotEmpty() } ?: roomName, c.pack?.avatarUrl, c, raw)
    }

    /** A room's packs; deleted ones (empty content) are left out. */
    fun roomPacks(roomId: String): Flow<List<Pack>> {
        val id = RoomId(roomId)
        return combine(client.room.getAllState<RoomEmotes>(id).flatMapLatest { m ->
            if (m.isNullOrEmpty()) flowOf(emptyList()) else combine(m.map { (k, f) -> f.map { k to it?.content?.raw } }) { it.toList() }
        }, roomName(id)) { l, name ->
            l.mapNotNull { (k, raw) -> raw?.takeIf { (it["images"] as? JsonObject).orEmpty().isNotEmpty() || it["pack"] != null }?.let { pack(id, k, it, name) } }
        }
    }

    private val globalRooms: Flow<Map<String, Set<String>>> = client.user.getAccountData<EmoteRooms>().map { c ->
        ((c?.raw?.get("rooms") as? JsonObject).orEmpty()).mapValues { (_, v) -> (v as? JsonObject)?.keys.orEmpty() }
    }

    /** Whether [p] (a room pack) is switched on for every chat. */
    fun isGlobal(p: PackRef): Flow<Boolean> = globalRooms.map { p.stateKey in globalRooms(it, p.roomId) }
    private fun globalRooms(m: Map<String, Set<String>>, roomId: String?) = m[roomId].orEmpty()

    /** Switches a room pack on or off for every chat. */
    suspend fun setGlobal(p: PackRef, on: Boolean) {
        val raw = client.user.getAccountData<EmoteRooms>().first()?.raw ?: EMPTY
        val rooms = ((raw["rooms"] as? JsonObject).orEmpty()).toMutableMap()
        val packs = ((rooms[p.roomId!!] as? JsonObject).orEmpty()).toMutableMap()
        if (on) packs[p.stateKey!!] = EMPTY else packs.remove(p.stateKey!!)
        if (packs.isEmpty()) rooms.remove(p.roomId) else rooms[p.roomId] = JsonObject(packs)
        api.user.setAccountData(EmoteRooms(JsonObject(raw + ("rooms" to JsonObject(rooms)))), client.userId).getOrThrow()
    }

    /** The room packs I switched on for every chat (rooms I've left drop out). */
    private fun globals(): Flow<List<Pack>> = globalRooms.flatMapLatest { rooms ->
        val flows = rooms.flatMap { (rid, keys) ->
            keys.map { k ->
                val id = RoomId(rid)
                combine(client.room.getById(id), client.room.getState<RoomEmotes>(id, k)) { r, ev ->
                    if (r?.membership != Membership.JOIN) null else ev?.content?.raw?.let { pack(id, k, it, r.name?.explicitName ?: rid) }
                }
            }
        }
        if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { it.filterNotNull() }
    }

    /** Packs usable in [roomId] (or anywhere when null): mine, the room's, then the ones switched on everywhere. */
    fun packsFor(roomId: String? = null): Flow<List<Pack>> =
        combine(personalRaw, if (roomId == null) flowOf(emptyList()) else roomPacks(roomId), globals()) { mine, room, glob ->
            val c = parsePack(mine)
            val me = Pack(PackRef(), c.pack?.displayName?.takeIf { it.isNotEmpty() } ?: "بسته‌ی من", c.pack?.avatarUrl, c, mine)
            buildList { add(me); addAll(room); glob.forEach { g -> if (none { it.key == g.key }) add(g) } }
        }

    /** Emoji (or stickers) usable in [roomId], in pack order; a shortcode used by several packs resolves to the first. */
    fun emotesFor(roomId: String?, use: Usage): Flow<List<Emote>> = packsFor(roomId).map { l ->
        l.flatMap { p -> packImages(p.content, use).map { (code, img) -> Emote(code, img, p) } }
    }

    /** Whether I may edit the packs of [roomId]. */
    fun canEdit(roomId: String): Flow<Boolean> = combine(
        client.room.getState<PowerLevelsEventContent>(RoomId(roomId)).map { it?.content }, client.user.getPowerLevel(RoomId(roomId), client.userId),
    ) { pl, mine -> (if (mine is de.connect2x.trixnity.client.user.PowerLevel.User) mine.level.toDouble() else Double.POSITIVE_INFINITY) >= levelAt(pl.core(), "events.${Types.ROOM_EMOTES}") }

    // ---------- reading and writing one pack ----------

    private suspend fun read(ref: PackRef): JsonObject =
        if (ref.roomId == null) client.user.getAccountData<UserEmotes>().first()?.raw ?: EMPTY
        else client.room.getState<RoomEmotes>(RoomId(ref.roomId), ref.stateKey!!).first()?.content?.raw ?: EMPTY

    private suspend fun write(ref: PackRef, raw: JsonObject) {
        if (ref.roomId == null) api.user.setAccountData(UserEmotes(raw), client.userId).getOrThrow()
        else api.room.sendStateEvent(RoomId(ref.roomId), RoomEmotes(raw), ref.stateKey!!).getOrThrow()
    }

    private fun kindOf(c: PackContent, use: Usage?) = use ?: (c.pack?.usage?.takeIf { it.size == 1 }?.first() ?: Usage.EMOTICON)

    private fun listOf(c: PackContent, use: Usage?) =
        if (use != null) packImages(c, use) else c.images.orEmpty().entries.filter { it.value.url.startsWith("mxc://") }.map { it.key to it.value }

    // ---------- images ----------

    /**
     * Uploads one already-processed image unencrypted (packs are shared, so every client expects a plain mxc) and describes it.
     * The platform scales still images (padded to at most 2:1, longest side 128 px for emoji / 512 for stickers) before this.
     */
    suspend fun upload(bytes: ByteArray, contentType: String, fileName: String, width: Int, height: Int): PackImage {
        val url = client.uploadPlain(bytes, contentType)
        return PackImage(url, fileName, buildJsonObject { put("mimetype", contentType); put("size", bytes.size); put("w", width); put("h", height) })
    }

    /**
     * Adds uploaded images (file name → image) to a pack under free shortcodes made from their names. [use]: list and mark them
     * as that kind (my emoji and my stickers share the personal pack). Those beyond the cap are skipped and counted.
     */
    suspend fun add(ref: PackRef, images: List<Pair<String, PackImage>>, use: Usage? = null): AddResult {
        val raw = read(ref)
        val c = parsePack(raw)
        val cap = PACK_CAP.getValue(kindOf(c, use))
        val fits = images.take(maxOf(0, cap - listOf(c, use).size))
        if (fits.isEmpty()) return AddResult(0, images.size)
        val out = raw.imagesRaw().toMutableMap()
        for ((name, img) in fits) out[freeCode(codeFrom(name), out)] = (if (use != null) img.copy(usage = listOf(use)) else img).toJson()
        write(ref, raw.withImages(out))
        return AddResult(fits.size, images.size - fits.size)
    }

    /** Renames a shortcode; throws when the new one is taken. */
    suspend fun rename(ref: PackRef, code: String, to: String) {
        val next = cleanCode(to)
        if (next.isEmpty() || next == code) return
        val raw = read(ref)
        val images = raw.imagesRaw()
        if (next in images) throw IllegalArgumentException("«:$next:» در این بسته هست")
        write(ref, raw.withImages(images.entries.associate { (if (it.key == code) next else it.key) to it.value }))
    }

    /** Deletes an image. With [use] set and the image also valid for the other kind (no usage of its own), it only leaves [use]. */
    suspend fun delete(ref: PackRef, code: String, use: Usage? = null) {
        val raw = read(ref)
        val images = raw.imagesRaw().toMutableMap()
        val img = parsePack(raw).images?.get(code)
        val other = if (use == Usage.STICKER) Usage.EMOTICON else Usage.STICKER
        if (use != null && img != null && packImages(PackContent(parsePack(raw).pack, mapOf(code to img)), other).isNotEmpty())
            images[code] = JsonObject((images[code] as JsonObject) + ("usage" to usageArray(listOf(other))))
        else images.remove(code)
        write(ref, raw.withImages(images))
    }

    /** Takes someone else's emoji or sticker into my pack; nothing if I already have it for that use. */
    suspend fun copyToMine(code: String, img: PackImage, use: Usage) {
        val raw = read(PackRef())
        val c = parsePack(raw)
        if (packImages(c, use).any { it.second.url == img.url }) return
        if (packImages(c, use).size >= PACK_CAP.getValue(use)) throw IllegalStateException("${if (use == Usage.STICKER) "استیکرهای" else "اموجی‌های"} شما پر است (${num(PACK_CAP.getValue(use))} تا)")
        val images = raw.imagesRaw().toMutableMap()
        images[freeCode(cleanCode(code, "_").trim('_').ifEmpty { "emoji" }, images)] =
            PackImage(img.url, null, img.info, listOf(use)).toJson()
        write(PackRef(), raw.withImages(images))
    }

    suspend fun inMyPack(url: String, use: Usage) = packImages(parsePack(read(PackRef())), use).any { it.second.url == url }

    // ---------- room packs ----------

    /** Creates an empty room pack for [use] and returns its reference. [name] defaults to the room's name. */
    suspend fun createRoomPack(roomId: String, use: Usage, name: String?): PackRef {
        val id = RoomId(roomId)
        val taken = client.room.getAllState<RoomEmotes>(id).first().orEmpty().keys.associateWith { 1 }
        val key = freeCode(if (use == Usage.STICKER) "stickers" else "emoji", taken)
        val title = name?.ifEmpty { null } ?: roomName(id).first()
        val ref = PackRef(roomId, key)
        write(ref, buildJsonObject {
            put("pack", buildJsonObject { put("display_name", title); put("usage", usageArray(listOf(use))) })
            put("images", EMPTY)
        })
        return ref
    }

    suspend fun renamePack(ref: PackRef, name: String) = write(ref, read(ref).withPack(mapOf("display_name" to JsonPrimitive(name))))

    /** Deletes a room pack for everyone (empty content). */
    suspend fun deleteRoomPack(ref: PackRef) = write(ref, EMPTY)

    companion object {
        /** "اموجی" / "استیکر" */
        fun label(use: Usage) = LABEL.getValue(use)
    }
}
